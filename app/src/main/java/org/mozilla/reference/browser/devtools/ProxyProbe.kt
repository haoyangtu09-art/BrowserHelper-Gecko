/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.GeckoPreferenceController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Phase 0 spike (MITM-proxy plan): a toggleable, transparent local proxy that
 * proves GeckoView routes its traffic through a local port (the "0a" question).
 *
 * It is a blind tunnel: HTTPS CONNECTs are relayed byte-for-byte (TLS is NOT
 * terminated), so browsing keeps working while it is on. It does not yet prove
 * CA trust (that needs TLS termination, the next step). Default OFF; flip via
 * the toolbar menu. On the first observed CONNECT it shows a Toast so routing
 * can be confirmed on-device.
 */
object ProxyProbe {
    private const val TAG = "BHProxySpike"
    private const val LOCALHOST = "127.0.0.1"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var firstConnectReported = false
    @Volatile private var firstHttpReported = false
    @Volatile private var firstUpstreamErrReported = false
    @Volatile private var firstMitmReported = false
    @Volatile private var firstMitmErrReported = false
    private var appContext: Context? = null

    // Panel tee channel (wired by DevToolsHelper). Emits decrypted-flow metadata
    // to the DevTools panel for DISPLAY ONLY; it never affects byte forwarding.
    @Volatile private var channel: ((JSONObject) -> Unit)? = null
    private val flowSeq = AtomicLong(0)

    // ── Request-direction character replacement (panel 「替换」 feature) ──
    // Rewrites only the user's own outgoing requests on the user's own device.
    // SAFETY (CLAUDE.md 坑#4): this NEVER touches the response pump, never buffers
    // unbounded, and is a no-op unless the user explicitly enables it. When OFF the
    // request path is byte-identical to the working forward-as-read model.
    private class ReplaceRule(val from: ByteArray, val to: ByteArray, val fromStr: String, val toStr: String)
    @Volatile private var replaceEnabled = false
    @Volatile private var replaceReqScope = false
    @Volatile private var replaceRulesList: List<ReplaceRule> = emptyList()
    private const val REPLACE_CAP = 1 * 1024 * 1024 // only buffer/replace request bodies ≤1MB

    fun setChannel(emit: ((JSONObject) -> Unit)?) {
        channel = emit
    }

    private fun emit(obj: JSONObject) {
        obj.put("ch", "proxy")
        try { channel?.invoke(obj) } catch (_: Throwable) {}
    }

    /** Panel → native: install request-rewrite rules. Empty/disabled = no-op pump. */
    @Synchronized
    fun setReplaceRules(enabled: Boolean, scope: String, rules: List<Pair<String, String>>) {
        replaceEnabled = enabled
        replaceReqScope = scope == "req" || scope == "both"
        replaceRulesList = rules.filter { it.first.isNotEmpty() }.map {
            ReplaceRule(it.first.toByteArray(Charsets.UTF_8), it.second.toByteArray(Charsets.UTF_8), it.first, it.second)
        }
    }

    private fun replaceActiveForReq(): Boolean =
        replaceEnabled && replaceReqScope && replaceRulesList.isNotEmpty()

    // Byte-level replace-all of every rule's from→to over [data]. Operates on raw
    // bytes so UTF-8 text matches and binary is left untouched (won't match).
    private fun applyReplaceBytes(data: ByteArray): ByteArray {
        var cur = data
        for (rule in replaceRulesList) cur = replaceBytes(cur, rule.from, rule.to)
        return cur
    }

    private fun replaceBytes(src: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        if (from.isEmpty() || src.size < from.size) return src
        val out = ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            if (i + from.size <= src.size && regionEquals(src, i, from)) {
                out.write(to)
                i += from.size
            } else {
                out.write(src[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun regionEquals(src: ByteArray, off: Int, pat: ByteArray): Boolean {
        for (j in pat.indices) if (src[off + j] != pat[j]) return false
        return true
    }

    // Replace in the request line + headers (already buffered text).
    private fun applyReplaceStr(head: String): String {
        var cur = head
        for (rule in replaceRulesList) if (rule.fromStr.isNotEmpty()) cur = cur.replace(rule.fromStr, rule.toStr)
        return cur
    }

    // Force the Content-Length header value to [size] so framing stays correct even
    // if a rule altered the body length (or matched inside the header text).
    private fun forceContentLength(head: String, size: Int): String {
        val lines = head.split("\r\n")
        val sb = StringBuilder()
        var done = false
        for ((idx, line) in lines.withIndex()) {
            val c = line.indexOf(':')
            if (!done && c > 0 && line.substring(0, c).trim().equals("Content-Length", ignoreCase = true)) {
                sb.append("Content-Length: ").append(size)
                done = true
            } else {
                sb.append(line)
            }
            if (idx < lines.size - 1) sb.append("\r\n")
        }
        return sb.toString()
    }

    // Read exactly [n] bytes (or until EOF) into a fresh array.
    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    @Synchronized
    fun toggle(context: Context) {
        appContext = context.applicationContext
        if (running) stop() else start()
    }

    /** Explicit on/off, driven by the DevTools panel's 监听 button. Idempotent. */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        appContext = context.applicationContext
        if (on && !running) start()
        else if (!on && running) stop()
    }

    fun isRunning(): Boolean = running

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
            reimportEnterpriseRoots()
            val srv = ServerSocket(0, 50, InetAddress.getByName(LOCALHOST))
            server = srv
            running = true
            firstConnectReported = false
            firstHttpReported = false
            firstUpstreamErrReported = false
            firstMitmReported = false
            firstMitmErrReported = false
            val port = srv.localPort
            Thread({ acceptLoop(srv) }, "bh-proxy-accept").apply { isDaemon = true }.start()
            applyProxyPrefs(port)
            toast("PROXY-SPIKE: probe ON, port=$port (打开一个 https 网站看是否弹出 CONNECT)")
        } catch (t: Throwable) {
            running = false
            toast("PROXY-SPIKE: start FAILED: ${t.message}")
        }
    }

    /**
     * Gecko only imports Android user-store CAs when security.enterprise_roots
     * transitions false→true (the pref observer fires on *change*). Since the
     * pref is persisted true after the first launch, a CA installed later is
     * never picked up. Force a false→true cycle here so the CA the user just
     * installed gets imported into NSS without reinstalling the app.
     */
    private fun reimportEnterpriseRoots() {
        try {
            GeckoPreferenceController.setGeckoPref(
                "security.enterprise_roots.enabled",
                false,
                GeckoPreferenceController.PREF_BRANCH_USER,
            ).accept(
                { _ ->
                    GeckoPreferenceController.setGeckoPref(
                        "security.enterprise_roots.enabled",
                        true,
                        GeckoPreferenceController.PREF_BRANCH_USER,
                    ).accept(
                        { _ -> toast("PROXY-SPIKE: 已重新导入用户证书，请刷新网页再试") },
                        { e -> toast("PROXY-SPIKE: 重新导入证书失败(on): ${e?.message}") },
                    )
                },
                { e -> toast("PROXY-SPIKE: 重新导入证书失败(off): ${e?.message}") },
            )
        } catch (t: Throwable) {
            toast("PROXY-SPIKE: 重新导入证书异常: ${t.message}")
        }
    }

    private fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        clearProxyPrefs()
        toast("PROXY-SPIKE: probe OFF (已恢复直连)")
    }

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
                if (!firstConnectReported) {
                    firstConnectReported = true
                    toast("PROXY-SPIKE: 收到 CONNECT $hostPort —— GeckoView 已走本地代理 ✅")
                }
                // Tell the browser the tunnel is up, then MITM the TLS inside it.
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                mitm(client, host, pPort)
            } else {
                // Plain HTTP: best-effort blind forward to Host:80.
                val host = headerValue(head, "Host")
                if (host == null) { client.close(); return }
                val h = host.substringBefore(":")
                val p = host.substringAfter(":", "80").toIntOrNull() ?: 80
                if (!firstHttpReported) {
                    firstHttpReported = true
                    toast("PROXY-SPIKE: 收到 HTTP $h —— GeckoView 已走本地代理 ✅")
                }
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

    // Phase 0b: terminate TLS inside the CONNECT tunnel. We present a leaf cert
    // for [host] (signed by our installed root CA) to the browser, open a real
    // TLS connection upstream, then relay the now-plaintext HTTP both ways —
    // sniffing the first request line to prove decryption works. Forces HTTP/1.1
    // (no ALPN h2 advertised). This only inspects the user's own traffic on the
    // user's own device; nothing is exfiltrated.
    private fun mitm(client: Socket, host: String, port: Int) {
        val tlsClient = try {
            val ctx = MitmCa.serverContextFor(host)
            (ctx.socketFactory.createSocket(client, host, port, true) as SSLSocket).apply {
                useClientMode = false
                startHandshake()
            }
        } catch (e: Throwable) {
            if (!firstMitmErrReported) {
                firstMitmErrReported = true
                toast("PROXY-SPIKE: 与浏览器 TLS 握手失败 $host: ${e.message}（是否已安装并信任根证书？）")
            }
            try { client.close() } catch (_: Throwable) {}
            return
        }
        val upstream = try {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { startHandshake() }
        } catch (e: Throwable) {
            if (!firstUpstreamErrReported) {
                firstUpstreamErrReported = true
                toast("PROXY-SPIKE: 上游 TLS 失败 $host: ${e.message}")
            }
            try { tlsClient.close() } catch (_: Throwable) {}
            return
        }
        try {
            val cIn = tlsClient.inputStream
            val cOut = tlsClient.outputStream
            val uIn = upstream.inputStream
            val uOut = upstream.outputStream
            // HTTP/1.1 keep-alive returns responses in request order, so we correlate
            // flowIds through a per-connection FIFO: the request loop enqueues, the
            // response loop dequeues in lock-step.
            val flowQueue = ConcurrentLinkedQueue<FlowInfo>()

            // Request direction (increment 2a): frame each request on a background
            // thread — read head, forward VERBATIM, emit flowReq, then stream-copy
            // the body (Content-Length) while teeing a bounded copy to the panel,
            // and loop for keep-alive follow-ups. Anything we can't frame safely
            // (chunked / Upgrade / malformed) bails to a raw blocking copy so we
            // never stall the page. Forwarding stays byte-for-byte (the working
            // pump model from a7b65e87 is preserved; the panel only OBSERVES).
            Thread({ pumpRequests(cIn, uOut, host, flowQueue) }, "bh-proxy-req")
                .apply { isDaemon = true }.start()

            // Response direction (increment 2b): loop reading each response head (so
            // EVERY keep-alive request gets its own flowResp → duration fills in, no
            // more pending "…"), tee bounded (<256K) Content-Length AND chunked bodies
            // (de-chunked on the copy; gzip/deflate/brotli decoded for the panel only).
            // 101 / Upgrade / no-Content-Length non-chunked bail to a raw blocking copy
            // — keeps the page stable. Forwarding is always byte-for-byte.
            pumpResponses(uIn, cOut, flowQueue)
        } catch (_: Throwable) {
            try { tlsClient.close() } catch (_: Throwable) {}
            try { upstream.close() } catch (_: Throwable) {}
        }
    }

    // Parse a raw request head ("METHOD path HTTP/1.1\r\nHeader: v\r\n...") into a
    // panel flowReq event. Display-only; failures are swallowed by the caller.
    private fun emitFlowReq(flowId: String, host: String, head: String) {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val parts = lines[0].split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }
        val headers = JSONObject()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers.put(lines[i].substring(0, idx).trim(), lines[i].substring(idx + 1).trim())
        }
        emit(
            JSONObject()
                .put("type", "flowReq")
                .put("flowId", flowId)
                .put("url", "https://$host$path")
                .put("method", method)
                .put("host", host)
                .put("reqHeaders", headers)
                .put("ts", System.currentTimeMillis()),
        )
    }

    // Parse a raw response head ("HTTP/1.1 200 OK\r\nHeader: v\r\n...") into a
    // panel flowResp event. Display-only.
    private fun emitFlowResp(flowId: String, head: String) {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val status = lines[0].split(" ").getOrElse(1) { "0" }.toIntOrNull() ?: 0
        val headers = JSONObject()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers.put(lines[i].substring(0, idx).trim(), lines[i].substring(idx + 1).trim())
        }
        emit(
            JSONObject()
                .put("type", "flowResp")
                .put("flowId", flowId)
                .put("status", status)
                .put("respHeaders", headers),
        )
    }

    // Request direction framing loop (runs on its own daemon thread). Reads each
    // request head, forwards it verbatim, emits flowReq, then handles the body:
    //   - Content-Length: N → copy exactly N bytes (write-as-you-read) and tee a
    //     bounded snapshot to the panel, then loop for the next keep-alive request.
    //   - chunked / Upgrade(WebSocket) / malformed-CL → we can't frame it safely,
    //     so fall back to a raw blocking copy of the rest and stop framing.
    //   - no body (GET/HEAD/…): loop straight to the next request.
    // Never buffers-then-forwards, never edits headers, never blocks the page.
    private fun pumpRequests(cIn: InputStream, uOut: OutputStream, host: String, flowQueue: ConcurrentLinkedQueue<FlowInfo>) {
        var first = true
        try {
            while (true) {
                val reqHead = readHead(cIn) ?: break
                val flowId = flowSeq.incrementAndGet().toString()
                if (first) {
                    first = false
                    if (!firstMitmReported) {
                        firstMitmReported = true
                        toast("PROXY-SPIKE: 已解密 HTTPS ✅ $host ▶ ${reqHead.substringBefore("\r\n").trim()}")
                    }
                }
                val method = reqHead.trimStart().substringBefore(' ').trim()
                val te = headerValue(reqHead, "Transfer-Encoding")
                val upgrade = headerValue(reqHead, "Upgrade")
                val expect = headerValue(reqHead, "Expect")
                val clStr = headerValue(reqHead, "Content-Length")
                val cl = clStr?.toLongOrNull()
                // A request is safely rewritable only when its length is fully known and
                // bounded: not chunked, not an Upgrade, no 100-continue handshake (which
                // would deadlock if we read the body before forwarding the head), valid
                // Content-Length, and body ≤ cap. Anything else → untouched verbatim pump.
                val rewritable = replaceActiveForReq() &&
                    upgrade == null &&
                    (te == null || !te.contains("chunked", ignoreCase = true)) &&
                    (expect == null || !expect.contains("100-continue", ignoreCase = true)) &&
                    !(clStr != null && cl == null) &&
                    (cl == null || cl <= REPLACE_CAP)

                if (rewritable) {
                    // Buffer the bounded body, replace head+body, fix Content-Length, then
                    // forward. The body is a complete known-size upload, so reading it in
                    // full does NOT stall like a streaming response would (CLAUDE.md 坑#4).
                    val body = if (cl != null && cl > 0) readExact(cIn, cl.toInt()) else ByteArray(0)
                    val newBody = applyReplaceBytes(body)
                    var newHead = applyReplaceStr(reqHead)
                    if (clStr != null) newHead = forceContentLength(newHead, newBody.size)
                    uOut.write(newHead.toByteArray(Charsets.ISO_8859_1))
                    if (newBody.isNotEmpty()) uOut.write(newBody)
                    uOut.flush()
                    emitFlowReq(flowId, host, newHead)
                    flowQueue.add(FlowInfo(flowId, method))
                    if (newBody.isNotEmpty()) {
                        val teeLen = minOf(newBody.size, 256 * 1024)
                        emitFlowReqBody(flowId, newBody.copyOf(teeLen), newBody.size > teeLen)
                    }
                    continue
                }

                // ── verbatim forward-as-read (unchanged working model) ──
                uOut.write(reqHead.toByteArray(Charsets.ISO_8859_1))
                uOut.flush()
                emitFlowReq(flowId, host, reqHead)
                // Hand the flowId+method to the response loop (FIFO, in request order).
                flowQueue.add(FlowInfo(flowId, method))
                if (upgrade != null || (te != null && te.contains("chunked", ignoreCase = true))) {
                    // WebSocket upgrade or chunked body: framing is unsafe → raw copy rest.
                    pumpInline(cIn, uOut)
                    return
                }
                if (clStr != null && cl == null) {
                    // Malformed Content-Length → don't guess, bail to raw copy.
                    pumpInline(cIn, uOut)
                    return
                }
                if (cl != null && cl > 0) {
                    val tee = streamCopy(cIn, uOut, cl, 256 * 1024)
                    emitFlowReqBody(flowId, tee.bytes, tee.truncated)
                }
                // else: no body → loop to next request.
            }
        } catch (_: Throwable) {
        } finally {
            try { uOut.close() } catch (_: Throwable) {}
            try { cIn.close() } catch (_: Throwable) {}
        }
    }

    // Correlates a request with its response across the two relay threads (FIFO).
    private class FlowInfo(val flowId: String, val method: String)

    // Response direction framing loop (increment 2b-safe; runs on the mitm thread).
    // Reads each response head, forwards it verbatim, emits flowResp (so every
    // keep-alive request gets a response → duration is known, no perpetual "…"),
    // then handles the body conservatively:
    //   - 1xx interim (100/103): no body, and the FINAL response belongs to the
    //     same request → do NOT dequeue, just loop.
    //   - 101 Switching Protocols / Upgrade: WebSocket → raw copy rest, stop.
    //   - HEAD response / 204 / 304: no body → loop.
    //   - Content-Length: forward the N bytes; tee them (decoded) only when the
    //     body is small/non-streaming (<256K); loop for the next keep-alive resp.
    //   - chunked: relayChunked frames it (forward-as-read), tees a bounded
    //     de-chunked snapshot, then loops — unless a malformed size bails to raw copy.
    //   - no-Content-Length, non-chunked: can't frame safely here → raw copy rest, stop.
    private fun pumpResponses(uIn: InputStream, cOut: OutputStream, flowQueue: ConcurrentLinkedQueue<FlowInfo>) {
        try {
            while (true) {
                val respHead = readHead(uIn) ?: break
                cOut.write(respHead.toByteArray(Charsets.ISO_8859_1))
                cOut.flush()
                val status = respHead.substringBefore("\r\n").split(" ").getOrElse(1) { "" }.toIntOrNull() ?: 0
                if (status == 101 || headerValue(respHead, "Upgrade") != null) {
                    pumpInline(uIn, cOut)
                    return
                }
                if (status in 100..199) {
                    // Interim response: no body, same request's final response follows.
                    continue
                }
                val info = flowQueue.poll()
                val flowId = info?.flowId ?: flowSeq.incrementAndGet().toString()
                emitFlowResp(flowId, respHead)

                val method = info?.method ?: "GET"
                if (method.equals("HEAD", ignoreCase = true) || status == 204 || status == 304) {
                    continue // no body by definition
                }
                val ce = headerValue(respHead, "Content-Encoding")
                val te = headerValue(respHead, "Transfer-Encoding")
                if (te != null && te.contains("chunked", ignoreCase = true)) {
                    // chunked body: frame chunk-by-chunk (forward-as-read), teeing a
                    // bounded snapshot. SSE tokens are forwarded the instant they
                    // arrive (tee never delays the stream). A malformed chunk size
                    // bails to raw copy of the rest (page stays correct).
                    val r = relayChunked(uIn, cOut, 256 * 1024)
                    emitFlowRespBody(flowId, r.bytes, r.truncated, ce)
                    if (r.bailed) return
                    continue
                }
                val cl = headerValue(respHead, "Content-Length")?.toLongOrNull()
                if (cl == null) {
                    // No Content-Length and not chunked → connection-delimited /
                    // streaming (SSE / Connection: close): can't know where it ends.
                    pumpInline(uIn, cOut)
                    return
                }
                if (cl > 0) {
                    // Always forward all bytes; tee (for the panel) only small bodies.
                    val teeLimit = if (cl <= 256 * 1024) (256 * 1024) else 0
                    val tee = streamCopy(uIn, cOut, cl, teeLimit)
                    if (teeLimit > 0) emitFlowRespBody(flowId, tee.bytes, tee.truncated, ce)
                }
                // cl == 0 → no body. Loop for the next keep-alive response.
            }
        } catch (_: Throwable) {
        } finally {
            try { cOut.close() } catch (_: Throwable) {}
            try { uIn.close() } catch (_: Throwable) {}
        }
    }

    // Result of relaying a chunked response body: the teed (bounded) snapshot, a
    // truncated flag (tee hit its cap), and bailed (we stopped framing and raw-copied
    // the rest, so the caller must return instead of looping for the next response).
    private class ChunkResult(val bytes: ByteArray, val truncated: Boolean, val bailed: Boolean)

    // Relay an HTTP/1.1 chunked response body chunk-by-chunk, forwarding every byte
    // the instant it is read (SSE tokens are never delayed by the tee) and teeing a
    // bounded de-chunked snapshot for the panel. Framing only drives the tee; the
    // forwarded stream is always byte-for-byte. A malformed chunk size or premature
    // EOF bails to a raw copy of the rest (bailed=true) so the page stays correct.
    private fun relayChunked(from: InputStream, to: OutputStream, teeLimit: Int): ChunkResult {
        val tee = ByteArrayOutputStream()
        var truncated = false
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                // Read + forward the chunk-size line (up to and including the LF).
                val sizeLine = readLineForward(from, to)
                    ?: return ChunkResult(tee.toByteArray(), truncated, true)
                val hex = sizeLine.trim().substringBefore(';').trim()
                val size = hex.toLongOrNull(16)
                    ?: run {
                        // Malformed chunk size → don't guess; raw copy the rest.
                        pumpInline(from, to)
                        return ChunkResult(tee.toByteArray(), truncated, true)
                    }
                if (size == 0L) {
                    // Last chunk: forward trailers up to the terminating blank line.
                    while (true) {
                        val tl = readLineForward(from, to) ?: break
                        if (tl == "\r\n" || tl == "\n") break
                    }
                    return ChunkResult(tee.toByteArray(), truncated, false)
                }
                // Forward exactly [size] bytes (write-as-read), teeing up to the cap.
                var remaining = size
                while (remaining > 0) {
                    val want = if (remaining < buf.size) remaining.toInt() else buf.size
                    val n = from.read(buf, 0, want)
                    if (n < 0) return ChunkResult(tee.toByteArray(), truncated, true)
                    to.write(buf, 0, n)
                    to.flush()
                    if (tee.size() < teeLimit) {
                        val canTee = minOf(n, teeLimit - tee.size())
                        tee.write(buf, 0, canTee)
                        if (canTee < n) truncated = true
                    } else {
                        truncated = true
                    }
                    remaining -= n
                }
                // Forward the CRLF that terminates the chunk data.
                readLineForward(from, to) ?: return ChunkResult(tee.toByteArray(), truncated, true)
            }
        } catch (_: Throwable) {
            return ChunkResult(tee.toByteArray(), truncated, true)
        }
    }

    // Read a single line (up to and including LF), forwarding each byte as it is read,
    // and return it (incl. terminator) for parsing. Null on EOF before any byte.
    // Capped so a pathological never-ending line can't exhaust memory.
    private fun readLineForward(from: InputStream, to: OutputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = from.read()
            if (b < 0) {
                if (out.size() == 0) return null
                break
            }
            out.write(b)
            to.write(b)
            if (b == '\n'.code) break
            if (out.size() > 64 * 1024) break
        }
        to.flush()
        return out.toString("ISO-8859-1")
    }

    // Tee a (bounded) response body snapshot to the panel. Decompresses gzip/deflate/
    // brotli on the COPY only (the forwarded stream is never touched). Truncated
    // compressed streams are decoded best-effort (decode what we can, swallow the
    // trailing error). Decoded as UTF-8.
    private fun emitFlowRespBody(flowId: String, body: ByteArray, truncated: Boolean, contentEncoding: String?) {
        val ce = contentEncoding?.lowercase() ?: ""
        val decoded = try {
            when {
                ce.contains("gzip") -> decodeBestEffort(java.util.zip.GZIPInputStream(body.inputStream()))
                ce.contains("br") -> decodeBestEffort(org.brotli.dec.BrotliInputStream(body.inputStream()))
                ce.contains("deflate") -> decodeBestEffort(java.util.zip.InflaterInputStream(body.inputStream()))
                else -> body
            }
        } catch (_: Throwable) {
            body // fall back to raw bytes if decompression fails outright
        }
        emit(
            JSONObject()
                .put("type", "flowRespBody")
                .put("flowId", flowId)
                .put("respBody", String(decoded, Charsets.UTF_8))
                .put("truncated", truncated)
                .put("encoding", contentEncoding ?: ""),
        )
    }

    // Decode a (possibly truncated) compressed stream best-effort: return whatever
    // decoded before an error. A teed body capped mid-stream yields a corrupt tail,
    // so the final read often throws — we keep the bytes decoded so far.
    private fun decodeBestEffort(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: Throwable) {
            // Truncated/partial compressed stream → keep what we got.
        }
        return out.toByteArray()
    }

    // Copy exactly [len] bytes from→to (write-as-you-read, flushing each chunk) and
    // tee at most [teeLimit] bytes into the returned snapshot for the panel.
    private class TeeResult(val bytes: ByteArray, val truncated: Boolean)
    private fun streamCopy(from: InputStream, to: OutputStream, len: Long, teeLimit: Int): TeeResult {
        val tee = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var remaining = len
        var truncated = false
        while (remaining > 0) {
            val want = if (remaining < buf.size) remaining.toInt() else buf.size
            val n = from.read(buf, 0, want)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
            if (tee.size() < teeLimit) {
                val canTee = minOf(n, teeLimit - tee.size())
                tee.write(buf, 0, canTee)
                if (canTee < n) truncated = true
            } else {
                truncated = true
            }
            remaining -= n
        }
        return TeeResult(tee.toByteArray(), truncated)
    }

    // Blocking byte relay (no thread) — used to bail out of request framing.
    private fun pumpInline(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
        }
    }

    // Tee a (bounded) request body snapshot to the panel. Decoded as UTF-8 since
    // these are typically JSON/text API payloads; invalid bytes become U+FFFD.
    private fun emitFlowReqBody(flowId: String, body: ByteArray, truncated: Boolean) {
        emit(
            JSONObject()
                .put("type", "flowReqBody")
                .put("flowId", flowId)
                .put("reqBody", String(body, Charsets.UTF_8))
                .put("truncated", truncated),
        )
    }

    // Relay bytes on a daemon thread; closes nothing so both directions can run.
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
    // byte so we never consume the tunneled body / TLS bytes that follow.
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
            if (out.size() > 64 * 1024) return out.toString("ISO-8859-1")
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
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }

    private fun setStr(name: String, value: String) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }

    private fun setBool(name: String, value: Boolean) {
        try {
            GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ _ -> }, { e -> toast("PROXY-SPIKE: pref FAIL $name: ${e?.message}") })
        } catch (t: Throwable) { toast("PROXY-SPIKE: pref THREW $name: ${t.message}") }
    }
}
