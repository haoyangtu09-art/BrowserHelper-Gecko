/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoPreferenceController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local TLS-terminating MITM proxy for the user's OWN GeckoView traffic, on the
 * user's OWN device, with the root CA installed manually by the user — i.e. a
 * Charles/Fiddler-equivalent network inspector wired into the in-app DevTools
 * panel. It decrypts HTTPS, parses HTTP/1.1, streams every flow to the panel,
 * and can pause/edit requests & responses, run string-replace rules, and apply
 * weak-network throttling. Nothing is exfiltrated off-device.
 *
 * Config (scope/filters/rules/throttle/intercept flags) and pause resolutions
 * arrive from the panel over the `devtools_inject` native port via
 * [DevToolsHelper], which also forwards proxy events back to the panel.
 */
object ProxyProbe {
    private const val TAG = "BHProxy"
    private const val LOCALHOST = "127.0.0.1"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var appContext: Context? = null

    // ---- panel control channel (wired by DevToolsHelper) -------------------

    /** Emit a JSON event to the panel; no-op when the panel/port isn't connected. */
    @Volatile private var channel: ((JSONObject) -> Unit)? = null

    fun setChannel(emit: ((JSONObject) -> Unit)?) {
        channel = emit
    }

    private fun emit(obj: JSONObject) {
        obj.put("ch", "proxy")
        channel?.invoke(obj)
    }

    // ---- config pushed from the panel --------------------------------------

    // Empty = decrypt all hosts; otherwise only these hosts (and subdomains) are
    // TLS-terminated, the rest are blind-tunnelled untouched.
    @Volatile private var scopeHosts: List<String> = emptyList()

    @Volatile private var interceptReqOn = false
    @Volatile private var interceptRespOn = false
    @Volatile private var interceptNoise = false

    @Volatile private var throttleEnabled = false
    @Volatile private var throttleLatencyMs = 0L
    @Volatile private var throttleKbps = 0

    private data class InterceptRule(
        val action: String, // "intercept" | "pass"
        val host: String,
        val path: String,
        val method: String,
        val interceptResp: Boolean,
    )

    private data class ReplaceRule(
        val from: String,
        val to: String,
        val enabled: Boolean,
        val scope: String, // "req" | "resp" | "both"
    )

    private val interceptRules = CopyOnWriteArrayList<InterceptRule>()
    private val replaceRules = CopyOnWriteArrayList<ReplaceRule>()

    // ---- paused-flow registry ----------------------------------------------

    private class PausedFlow {
        val latch = CountDownLatch(1)
        @Volatile var action = "continue" // "continue" | "abort"
        @Volatile var edited: JSONObject? = null
    }

    private val pausedFlows = ConcurrentHashMap<String, PausedFlow>()
    private val flowSeq = AtomicLong(0)

    // ------------------------------------------------------------------------

    @Synchronized
    fun toggle(context: Context) {
        appContext = context.applicationContext
        if (running) stop() else start()
    }

    /**
     * Called once when the GeckoRuntime is (re)created. The proxy prefs are
     * written to PREF_BRANCH_USER, which persists to disk and is restored on the
     * next process launch — but the ServerSocket lives only in this in-memory
     * object and dies with the process. So after a background kill + relaunch,
     * Gecko points at a dead local port and every request fails with
     * NS_ERROR_PROXY_CONNECTION_REFUSED until the probe is re-armed. Reset to
     * direct on startup so a cold launch is always clean; the user re-toggles to
     * re-arm (a fresh socket + fresh port).
     */
    @Synchronized
    fun resetProxyStateOnStartup(context: Context) {
        appContext = context.applicationContext
        running = false
        setInt("network.proxy.type", 0)
    }

    private fun toast(msg: String) {
        Log.i(TAG, msg)
        val ctx = appContext ?: return
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun start() {
        try {
            appContext?.let { MitmCa.ensureRootCa(it) }
            val srv = ServerSocket(0, 50, InetAddress.getByName(LOCALHOST))
            server = srv
            running = true
            val port = srv.localPort
            Thread({ acceptLoop(srv) }, "bh-proxy-accept").apply { isDaemon = true }.start()
            applyProxyPrefs(port)
            toast("抓包代理已开启 (port=$port)。若证书刚安装，请重启 App 后再用")
        } catch (t: Throwable) {
            running = false
            toast("抓包代理启动失败: ${t.message}")
        }
    }

    private fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        clearProxyPrefs()
        releaseAll()
        toast("抓包代理已关闭 (已恢复直连)")
    }

    // ---- panel → proxy messages --------------------------------------------

    fun onPanelMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "config" -> applyConfig(msg)
            "rules" -> applyRules(msg)
            "resolveReq", "resolveResp" -> resolveFlow(msg)
            "releaseAll" -> releaseAll()
        }
    }

    private fun applyConfig(msg: JSONObject) {
        msg.optJSONArray("scopeHosts")?.let { arr ->
            scopeHosts = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        }
        interceptReqOn = msg.optBoolean("interceptOn", interceptReqOn)
        interceptRespOn = msg.optBoolean("respInterceptOn", interceptRespOn)
        interceptNoise = msg.optBoolean("interceptNoise", interceptNoise)
        msg.optJSONObject("throttle")?.let { t ->
            throttleEnabled = t.optBoolean("enabled", false)
            throttleLatencyMs = t.optLong("latencyMs", 0L)
            throttleKbps = t.optInt("kbps", 0)
        }
    }

    private fun applyRules(msg: JSONObject) {
        msg.optJSONArray("interceptRules")?.let { arr ->
            val list = ArrayList<InterceptRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    InterceptRule(
                        action = o.optString("action", "intercept"),
                        host = o.optString("host", ""),
                        path = o.optString("path", ""),
                        method = o.optString("method", ""),
                        interceptResp = o.optBoolean("interceptResp", false),
                    ),
                )
            }
            interceptRules.clear()
            interceptRules.addAll(list)
        }
        msg.optJSONArray("replaceRules")?.let { arr ->
            val list = ArrayList<ReplaceRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    ReplaceRule(
                        from = o.optString("from", ""),
                        to = o.optString("to", ""),
                        enabled = o.optBoolean("enabled", true),
                        scope = o.optString("scope", "both"),
                    ),
                )
            }
            replaceRules.clear()
            replaceRules.addAll(list)
        }
    }

    private fun resolveFlow(msg: JSONObject) {
        val id = msg.optString("flowId")
        val pf = pausedFlows.remove(id) ?: return
        pf.action = msg.optString("action", "continue")
        pf.edited = msg
        pf.latch.countDown()
    }

    private fun releaseAll() {
        val it = pausedFlows.entries.iterator()
        while (it.hasNext()) {
            val pf = it.next().value
            pf.action = "continue"
            pf.edited = null
            pf.latch.countDown()
            it.remove()
        }
    }

    // ---- accept / dispatch -------------------------------------------------

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            val client = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handle(client) }, "bh-proxy-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: run { client.close(); return }
            val firstLine = head.substringBefore("\r\n")
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0].equals("CONNECT", ignoreCase = true)) {
                val hostPort = parts[1]
                val host = hostPort.substringBefore(":")
                val pPort = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                if (inScope(host)) {
                    mitm(client, host, pPort)
                } else {
                    blindTunnel(client, cin, cout, host, pPort)
                }
            } else {
                // Plain HTTP: blind forward to Host:80 (not inspected for now).
                val host = headerValue(head, "Host") ?: run { client.close(); return }
                val h = host.substringBefore(":")
                val p = host.substringAfter(":", "80").toIntOrNull() ?: 80
                val upstream = Socket(h, p)
                upstream.getOutputStream().write(head.toByteArray())
                upstream.getOutputStream().flush()
                pump(cin, upstream.getOutputStream())
                pump(upstream.getInputStream(), cout)
            }
        } catch (_: Throwable) {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun inScope(host: String): Boolean {
        if (scopeHosts.isEmpty()) return true
        return scopeHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Relay the raw (still-encrypted) CONNECT tunnel byte-for-byte. */
    private fun blindTunnel(client: Socket, cin: InputStream, cout: OutputStream, host: String, port: Int) {
        try {
            val upstream = Socket(host, port)
            pump(cin, upstream.getOutputStream())
            pump(upstream.getInputStream(), cout)
        } catch (_: Throwable) {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    // ---- TLS-terminating HTTP/1.1 engine -----------------------------------

    private fun mitm(client: Socket, host: String, port: Int) {
        val tlsClient = try {
            val ctx = MitmCa.serverContextFor(host)
            (ctx.socketFactory.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                startHandshake()
            }
        } catch (e: Throwable) {
            toast("与浏览器 TLS 握手失败 $host: ${e.message}（是否已安装并信任根证书？）")
            try { client.close() } catch (_: Throwable) {}
            return
        }
        val upstream = try {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { startHandshake() }
        } catch (e: Throwable) {
            toast("上游 TLS 失败 $host: ${e.message}")
            try { tlsClient.close() } catch (_: Throwable) {}
            return
        }
        try {
            val cIn = tlsClient.inputStream
            val cOut = tlsClient.outputStream
            val uIn = upstream.inputStream
            val uOut = upstream.outputStream
            while (true) {
                val req = readMessage(cIn, isResponse = false, reqMethod = null) ?: break
                val method = req.startLine.split(" ").getOrElse(0) { "" }.uppercase()
                val pathPart = req.startLine.split(" ").getOrElse(1) { "/" }
                val url = "https://$host$pathPart"
                val isWebSocket = req.hasToken("Upgrade", "websocket")

                // Force identity so upstream bodies come back uncompressed (no
                // gzip/br decode needed to view / edit / replace).
                if (!isWebSocket) req.setHeader("Accept-Encoding", "identity")

                applyReplace(req, isResponse = false)

                val flowId = flowSeq.incrementAndGet().toString()
                emitFlowReq(flowId, url, method, host, req)

                val topNav = req.hasTokenValue("Sec-Fetch-Dest", "document") ||
                    req.hasTokenValue("Sec-Fetch-Mode", "navigate")

                if (!isWebSocket && !topNav && shouldInterceptReq(url, method, host, pathPart, req)) {
                    val pf = pause(flowId, "reqPaused", url, method, req, null)
                    if (pf.action == "abort") { break }
                    pf.edited?.let { applyEditedRequest(req, it) }
                }

                writeMessage(uOut, req, throttle = true)

                if (isWebSocket) {
                    // After the upgrade response, both sides speak the WS framing
                    // protocol; stop parsing and blind-relay.
                    val resp = readMessage(uIn, isResponse = true, reqMethod = method) ?: break
                    writeMessage(cOut, resp, throttle = false)
                    pump(cIn, uOut)
                    pump(uIn, cOut)
                    return
                }

                val started = System.currentTimeMillis()
                val resp = readMessage(uIn, isResponse = true, reqMethod = method) ?: break
                applyReplace(resp, isResponse = true)
                val status = resp.startLine.split(" ").getOrElse(1) { "0" }.toIntOrNull() ?: 0
                emitFlowResp(flowId, status, resp, System.currentTimeMillis() - started)

                if (shouldInterceptResp(url, method, host, pathPart, req)) {
                    val pf = pause(flowId, "respPaused", url, method, req, resp)
                    if (pf.action == "abort") { break }
                    pf.edited?.let { applyEditedResponse(resp, it) }
                }

                writeMessage(cOut, resp, throttle = true)

                if (req.hasToken("Connection", "close") || resp.hasToken("Connection", "close")) break
            }
        } catch (_: Throwable) {
        } finally {
            try { tlsClient.close() } catch (_: Throwable) {}
            try { upstream.close() } catch (_: Throwable) {}
        }
    }

    // ---- intercept decisions -----------------------------------------------

    private fun isNoise(url: String, method: String): Boolean {
        val u = url.lowercase()
        return u.contains("/heartbeat") || u.contains("/ping") || u.contains("/telemetry") ||
            u.contains("/beacon") || u.contains("/collect") || u.contains("/keepalive") ||
            u.endsWith(".gif") || u.contains("/log?") || u.contains("/metrics")
    }

    private fun ruleMatch(rule: InterceptRule, host: String, path: String, method: String): Boolean {
        if (rule.host.isNotEmpty() && !host.contains(rule.host, ignoreCase = true)) return false
        if (rule.path.isNotEmpty() && !path.contains(rule.path, ignoreCase = true)) return false
        if (rule.method.isNotEmpty() && !rule.method.equals(method, ignoreCase = true)) return false
        return true
    }

    private fun shouldInterceptReq(url: String, method: String, host: String, path: String, req: HttpMessage): Boolean {
        for (r in interceptRules) {
            if (ruleMatch(r, host, path, method)) return r.action == "intercept"
        }
        if (!interceptReqOn) return false
        return interceptNoise || !isNoise(url, method)
    }

    private fun shouldInterceptResp(url: String, method: String, host: String, path: String, req: HttpMessage): Boolean {
        for (r in interceptRules) {
            if (ruleMatch(r, host, path, method)) return r.action == "intercept" && r.interceptResp
        }
        if (!interceptRespOn) return false
        return interceptNoise || !isNoise(url, method)
    }

    // ---- pause / resume ----------------------------------------------------

    private fun pause(
        flowId: String,
        type: String,
        url: String,
        method: String,
        req: HttpMessage,
        resp: HttpMessage?,
    ): PausedFlow {
        val pf = PausedFlow()
        pausedFlows[flowId] = pf
        val ev = JSONObject()
            .put("type", type)
            .put("flowId", flowId)
            .put("url", url)
            .put("method", method)
        putBodyAndHeaders(ev, "req", req)
        if (resp != null) {
            val status = resp.startLine.split(" ").getOrElse(1) { "0" }.toIntOrNull() ?: 0
            ev.put("status", status)
            putBodyAndHeaders(ev, "resp", resp)
        }
        emit(ev)
        try {
            pf.latch.await()
        } catch (_: InterruptedException) {
        }
        return pf
    }

    private fun applyEditedRequest(req: HttpMessage, edited: JSONObject) {
        edited.optString("startLine").takeIf { it.isNotEmpty() }?.let { req.startLine = it }
        applyEditedHeadersBody(req, edited, "req")
    }

    private fun applyEditedResponse(resp: HttpMessage, edited: JSONObject) {
        edited.optString("startLine").takeIf { it.isNotEmpty() }?.let { resp.startLine = it }
        applyEditedHeadersBody(resp, edited, "resp")
    }

    private fun applyEditedHeadersBody(m: HttpMessage, edited: JSONObject, kind: String) {
        edited.optJSONArray("${kind}Headers")?.let { arr ->
            m.headers.clear()
            for (i in 0 until arr.length()) {
                val line = arr.optString(i)
                val idx = line.indexOf(':')
                if (idx > 0) m.headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
            }
        }
        if (edited.has("${kind}Body")) {
            m.body = decodeBody(edited.optString("${kind}Body"), edited.optString("${kind}BodyEnc", "utf8"))
            m.fixContentLength()
        }
    }

    // ---- emit helpers ------------------------------------------------------

    private fun emitFlowReq(flowId: String, url: String, method: String, host: String, req: HttpMessage) {
        val ev = JSONObject()
            .put("type", "flowReq")
            .put("flowId", flowId)
            .put("url", url)
            .put("method", method)
            .put("host", host)
            .put("ts", System.currentTimeMillis())
        putBodyAndHeaders(ev, "req", req)
        emit(ev)
    }

    private fun emitFlowResp(flowId: String, status: Int, resp: HttpMessage, duration: Long) {
        val ev = JSONObject()
            .put("type", "flowResp")
            .put("flowId", flowId)
            .put("status", status)
            .put("duration", duration)
        putBodyAndHeaders(ev, "resp", resp)
        emit(ev)
    }

    private fun putBodyAndHeaders(obj: JSONObject, kind: String, m: HttpMessage) {
        val arr = JSONArray()
        m.headers.forEach { arr.put("${it.first}: ${it.second}") }
        obj.put("${kind}Headers", arr)
        val asUtf8 = runCatching { String(m.body, Charsets.UTF_8) }.getOrNull()
        if (asUtf8 != null && asUtf8.toByteArray(Charsets.UTF_8).contentEquals(m.body)) {
            obj.put("${kind}Body", asUtf8)
            obj.put("${kind}BodyEnc", "utf8")
        } else {
            obj.put("${kind}Body", Base64.encodeToString(m.body, Base64.NO_WRAP))
            obj.put("${kind}BodyEnc", "b64")
        }
    }

    private fun decodeBody(s: String, enc: String): ByteArray =
        if (enc == "b64") Base64.decode(s, Base64.NO_WRAP) else s.toByteArray(Charsets.UTF_8)

    // ---- string-replace rules ----------------------------------------------

    private fun applyReplace(m: HttpMessage, isResponse: Boolean) {
        val want = if (isResponse) "resp" else "req"
        val active = replaceRules.filter { it.enabled && (it.scope == want || it.scope == "both") && it.from.isNotEmpty() }
        if (active.isEmpty()) return
        val asUtf8 = runCatching { String(m.body, Charsets.UTF_8) }.getOrNull() ?: return
        if (!asUtf8.toByteArray(Charsets.UTF_8).contentEquals(m.body)) return // non-text, skip
        var changed = false
        var text = asUtf8
        for (r in active) {
            if (text.contains(r.from)) {
                text = text.replace(r.from, r.to)
                changed = true
            }
        }
        if (changed) {
            m.body = text.toByteArray(Charsets.UTF_8)
            m.fixContentLength()
        }
    }

    // ---- HTTP/1.1 message model + parse/serialize --------------------------

    private class HttpMessage(
        var startLine: String,
        val headers: MutableList<Pair<String, String>>,
        var body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun setHeader(name: String, value: String) {
            headers.removeAll { it.first.equals(name, ignoreCase = true) }
            headers.add(name to value)
        }

        fun removeHeader(name: String) {
            headers.removeAll { it.first.equals(name, ignoreCase = true) }
        }

        /** True if header [name] contains the comma/space token [token]. */
        fun hasToken(name: String, token: String): Boolean {
            val v = header(name) ?: return false
            return v.split(",", " ").any { it.trim().equals(token, ignoreCase = true) }
        }

        fun hasTokenValue(name: String, value: String): Boolean =
            header(name)?.trim()?.equals(value, ignoreCase = true) == true

        fun fixContentLength() {
            removeHeader("Transfer-Encoding")
            setHeader("Content-Length", body.size.toString())
        }

        fun serialize(): ByteArray {
            val sb = StringBuilder()
            sb.append(startLine).append("\r\n")
            headers.forEach { sb.append(it.first).append(": ").append(it.second).append("\r\n") }
            sb.append("\r\n")
            val headBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
            return headBytes + body
        }
    }

    /**
     * Read one full HTTP/1.1 message (start line + headers + body) from [input].
     * Returns null at clean EOF. Body framing follows Content-Length /
     * Transfer-Encoding: chunked; responses to HEAD and 1xx/204/304 have no body.
     */
    private fun readMessage(input: InputStream, isResponse: Boolean, reqMethod: String?): HttpMessage? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val startLine = lines[0]
        val headers = ArrayList<Pair<String, String>>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) headers.add(line.substring(0, idx).trim() to line.substring(idx + 1).trim())
        }
        val msg = HttpMessage(startLine, headers, ByteArray(0))

        val status = if (isResponse) startLine.split(" ").getOrElse(1) { "0" }.toIntOrNull() ?: 0 else 0
        val noBody = when {
            isResponse && reqMethod.equals("HEAD", ignoreCase = true) -> true
            isResponse && (status in 100..199 || status == 204 || status == 304) -> true
            else -> false
        }
        if (noBody) return msg

        val te = msg.header("Transfer-Encoding")
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            msg.body = readChunked(input)
            msg.fixContentLength()
            return msg
        }
        val cl = msg.header("Content-Length")?.trim()?.toIntOrNull()
        if (cl != null) {
            msg.body = readN(input, cl)
            return msg
        }
        if (isResponse) {
            // No CL, not chunked: body runs until the connection closes.
            msg.body = readToEnd(input)
        }
        return msg
    }

    private fun writeMessage(out: OutputStream, m: HttpMessage, throttle: Boolean) {
        val bytes = m.serialize()
        if (throttle && throttleEnabled && throttleLatencyMs > 0) {
            try { Thread.sleep(throttleLatencyMs) } catch (_: InterruptedException) {}
        }
        if (throttle && throttleEnabled && throttleKbps > 0) {
            writeThrottled(out, bytes, throttleKbps)
        } else {
            out.write(bytes)
        }
        out.flush()
    }

    private fun writeThrottled(out: OutputStream, bytes: ByteArray, kbps: Int) {
        val bytesPerSec = (kbps.toLong() * 1024L / 8L).coerceAtLeast(1L)
        val slice = 4096
        var off = 0
        while (off < bytes.size) {
            val n = minOf(slice, bytes.size - off)
            out.write(bytes, off, n)
            out.flush()
            off += n
            val sleepMs = n * 1000L / bytesPerSec
            if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
        }
    }

    // ---- low-level stream readers ------------------------------------------

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineAscii(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Consume trailing headers up to the blank line.
                while (true) {
                    val l = readLineAscii(input) ?: break
                    if (l.isEmpty()) break
                }
                break
            }
            out.write(readN(input, size))
            readLineAscii(input) // trailing CRLF after chunk data
        }
        return out.toByteArray()
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** Read one CRLF-terminated line as ASCII (CRLF stripped); null at EOF. */
    private fun readLineAscii(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() > 0) out.toString("ISO-8859-1") else null
            if (b == '\n'.code) {
                val s = out.toString("ISO-8859-1")
                return if (s.endsWith("\r")) s.dropLast(1) else s
            }
            out.write(b)
            if (out.size() > 64 * 1024) return out.toString("ISO-8859-1")
        }
    }

    // Relay bytes on a daemon thread; closes both ends when one side hits EOF.
    private fun pump(from: InputStream, to: OutputStream) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Throwable) {
            } finally {
                try { to.close() } catch (_: Throwable) {}
                try { from.close() } catch (_: Throwable) {}
            }
        }, "bh-proxy-pump").apply { isDaemon = true }.start()
    }

    // Read request head (request line + headers) up to the blank line, byte by
    // byte so we never consume the body / tunneled bytes that follow.
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var state = 0 // counts \r \n \r \n
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() > 0) out.toString("ISO-8859-1") else null
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return out.toString("ISO-8859-1")
            if (out.size() > 256 * 1024) return out.toString("ISO-8859-1")
        }
    }

    private fun headerValue(head: String, name: String): String? {
        head.split("\r\n").forEach { line ->
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) {
                return line.substring(i + 1).trim()
            }
        }
        return null
    }

    // ---- proxy prefs -------------------------------------------------------

    private fun applyProxyPrefs(port: Int) {
        setInt("network.proxy.type", 1)
        setStr("network.proxy.http", LOCALHOST)
        setInt("network.proxy.http_port", port)
        setStr("network.proxy.ssl", LOCALHOST)
        setInt("network.proxy.ssl_port", port)
        setBool("network.proxy.share_proxy_settings", true)
    }

    private fun clearProxyPrefs() {
        setInt("network.proxy.type", 0)
    }

    private fun setInt(name: String, value: Int) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("pref THREW $name: ${t.message}") }
    }

    private fun setStr(name: String, value: String) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("pref THREW $name: ${t.message}") }
    }

    private fun setBool(name: String, value: Boolean) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("pref THREW $name: ${t.message}") }
    }
}
