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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
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
    @Volatile private var replaceRespScope = false
    @Volatile private var replaceRulesList: List<ReplaceRule> = emptyList()
    private const val REPLACE_PREFS = "bh_devtools"
    private const val REPLACE_PREFS_KEY = "replace_config"
    private const val REPLACE_CAP = 1 * 1024 * 1024 // only buffer/replace bodies ≤1MB (raw/compressed)
    private const val REPLACE_DECODED_CAP = 8 * 1024 * 1024 // bail if a compressed resp decodes larger
    private const val REPLACE_CHUNK_RAW_CAP = 4 * 1024 * 1024 // de-chunked doc buffer cap (still-encoded)
    private const val CHUNK_REPLACE_TIMEOUT_MS = 30000 // read timeout while buffering a chunked doc

    // ── Request/response intercept ("拦截/断点" feature) ──
    // The native proxy PAUSES a matched flow before forwarding and round-trips with
    // the panel (emit intercept event → panel edits → resolveIntercept completes the
    // future). SAFETY (CLAUDE.md 坑#4): only BOUNDED flows are ever paused (not
    // chunked/upgrade/100-continue, valid Content-Length ≤ REPLACE_CAP); streaming /
    // SSE / WebSocket always forward verbatim. Every pause has a hard timeout so a
    // disconnected panel can never hang the socket — on timeout we forward the
    // original unmodified (fail-open).
    private class InterceptRule(
        val host: String,
        val path: String,
        val method: String,
        val hasBody: Boolean,
        val action: String,
        val interceptResp: Boolean,
    )
    // "Intercept all" gates: when on, every BOUNDED request/response is paused for
    // the panel EXCEPT low-value telemetry/noise/cookie traffic (mirrors the panel's
    // display filters). Explicit rules override: action=pass whitelists, action=
    // intercept forces, interceptResp pauses that flow's response.
    @Volatile private var reqInterceptAll = false
    @Volatile private var respInterceptAll = false
    // Per-class intercept toggles: when on, the matching low-value class is NO LONGER
    // let through by "intercept all" (it gets paused like everything else). Off = pass
    // (default), mirroring the panel's 拦截遥测包/拦截噪音包/拦截cookie包 checkboxes.
    @Volatile private var interceptTelemetry = false
    @Volatile private var interceptNoise = false
    @Volatile private var interceptCookie = false
    @Volatile private var interceptRulesList: List<InterceptRule> = emptyList()
    private val pendingIntercepts = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val pendingRespIntercepts = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private const val INTERCEPT_PREFS_KEY = "intercept_config"
    private const val INTERCEPT_TIMEOUT_MS = 60000L
    // Low-value request classes "intercept all" lets through (ports panel/render.js
    // isTelemetryReq / isNoiseReq / isCookieReq regexes; tested against the lowercased URL).
    private val telemetryRe = Regex("(collect|telemetry|analytics|metrics|beacon|sentry|trace|traces|stats|gtm|gtag|google-analytics|doubleclick|amplitude|mixpanel|segment|datadog|bugsnag|track|pixel|rum)([/?#_.-]|$)")
    private val noiseRe = Regex("(heartbeat|ping|keepalive|poll|socket\\.io|sockjs|realtime|tunnel|presence|typing|status|health|alive|connect|events?)([/?#_.-]|$)|[?&](ping|heartbeat|keepalive|beacon)=")
    private val cookieRe = Regex("(/|[?&._-])(cookie|cookiesync|setuid|usersync|user-sync|idsync|id-sync|cksync|syncuser|getuid|gen_204|__cf)([/?#._-]|$)")

    // ── Mock (Map Local): synthesize a response for matched requests without ever
    // touching upstream. A matched BOUNDED request's body is drained, a synthetic
    // response (status/headers/body) is written to the browser, and the connection
    // is closed (Connection: close). Streaming/unbounded requests are never mocked.
    private class MockRule(val pattern: String, val status: Int, val headers: Map<String, String>, val body: String)
    @Volatile private var mockRulesList: List<MockRule> = emptyList()
    private const val MOCK_PREFS_KEY = "mock_config"

    // ── Throttle (弱网): pace the DOWNLOAD (response) direction only. latencyMs is
    // applied once per response (before forwarding its head); kbps rate-limits the
    // body via ThrottledOutputStream, which writes through immediately (NO buffering,
    // 坑#4-safe) and only sleeps between chunks. Off (kbps<=0 & latency<=0) = no-op.
    @Volatile private var throttleEnabled = false
    @Volatile private var throttleLatencyMs = 0L
    @Volatile private var throttleKbps = 0L
    private const val THROTTLE_PREFS_KEY = "throttle_config"
    // Persisted user intent for the 监听 toggle. On cold launch we honor it: if the
    // proxy was ON, auto-arm a fresh socket so capture resumes without a manual re-toggle.
    private const val PROXY_ENABLED_KEY = "proxy_enabled"

    // ── SSE response hold ("截流" plugin) ──
    // Hold a configured-host text/event-stream response: forward the head, run a
    // heartbeat thread that keeps the browser's transport layer alive (SSE comment
    // lines, which the EventSource/stream parser ignores), de-chunk the full upstream
    // stream into one buffer, then BLOCK until the panel releases an (optionally edited)
    // body — replayed as one chunk + terminator. SAFETY (CLAUDE.md 坑#4): this is the
    // ONLY place a streaming response is ever buffered, and it is tightly gated —
    // armed hosts only + text/event-stream + identity encoding, off by default. Any
    // heartbeat write failure (the browser closed the stream) fail-opens immediately so
    // the socket can never hang. The wait itself has no hard timeout BY DESIGN (the
    // heartbeat is what keeps the page alive while the user edits); the read-side buffer
    // is still bounded by SSE_HOLD_CAP + the inherited chunked read timeout.
    @Volatile private var sseHoldEnabled = false
    @Volatile private var sseHoldHosts: List<String> = emptyList()
    @Volatile private var sseHoldHeartbeat = ": ping\n\n"
    private const val SSE_HOLD_PREFS_KEY = "sse_hold_config"
    private const val SSE_HOLD_CAP = 8 * 1024 * 1024 // de-chunked SSE buffer cap
    private const val SSE_HEARTBEAT_MS = 10000L // heartbeat interval while held

    fun setChannel(emit: ((JSONObject) -> Unit)?) {
        channel = emit
    }

    private fun emit(obj: JSONObject) {
        obj.put("ch", "proxy")
        try { channel?.invoke(obj) } catch (_: Throwable) {}
    }

    /** Panel → native: install rewrite rules (scope req/resp/both). Empty/disabled = no-op pump. */
    @Synchronized
    fun setReplaceRules(enabled: Boolean, scope: String, rules: List<Pair<String, String>>) {
        replaceEnabled = enabled
        replaceReqScope = scope == "req" || scope == "both"
        replaceRespScope = scope == "resp" || scope == "both"
        replaceRulesList = rules.filter { it.first.isNotEmpty() }.map {
            ReplaceRule(it.first.toByteArray(Charsets.UTF_8), it.second.toByteArray(Charsets.UTF_8), it.first, it.second)
        }
        saveReplaceConfig(enabled, scope, rules)
    }

    // Persist the replace config natively so it survives page reloads AND cold
    // restarts: the panel restarts on every navigation and re-pushes asynchronously,
    // but the native proxy keeps the last-known rules as the source of truth so
    // replacement is never interrupted. Stored as a small JSON blob.
    private fun saveReplaceConfig(enabled: Boolean, scope: String, rules: List<Pair<String, String>>) {
        val ctx = appContext ?: return
        try {
            val arr = org.json.JSONArray()
            for (r in rules) {
                if (r.first.isEmpty()) continue
                arr.put(JSONObject().put("from", r.first).put("to", r.second))
            }
            val obj = JSONObject().put("enabled", enabled).put("scope", scope).put("rules", arr)
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(REPLACE_PREFS_KEY, obj.toString()).apply()
        } catch (_: Throwable) {}
    }

    // Restore the persisted replace config into native memory. Called on cold start
    // (appContext ready) so the proxy can replace immediately once armed, without
    // waiting for the panel to connect and re-push.
    @Synchronized
    private fun loadReplaceConfig() {
        val ctx = appContext ?: return
        try {
            val s = ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getString(REPLACE_PREFS_KEY, null) ?: return
            val obj = JSONObject(s)
            val scope = obj.optString("scope", "both")
            val arr = obj.optJSONArray("rules")
            val rules = ArrayList<ReplaceRule>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val from = o.optString("from", "")
                    if (from.isEmpty()) continue
                    val to = o.optString("to", "")
                    rules.add(ReplaceRule(from.toByteArray(Charsets.UTF_8), to.toByteArray(Charsets.UTF_8), from, to))
                }
            }
            replaceEnabled = obj.optBoolean("enabled", false)
            replaceReqScope = scope == "req" || scope == "both"
            replaceRespScope = scope == "resp" || scope == "both"
            replaceRulesList = rules
        } catch (_: Throwable) {}
    }

    private fun replaceActiveForReq(): Boolean =
        replaceEnabled && replaceReqScope && replaceRulesList.isNotEmpty()

    private fun replaceActiveForResp(): Boolean =
        replaceEnabled && replaceRespScope && replaceRulesList.isNotEmpty()

    /** Panel → native: install intercept config (intercept-all gates + override rules). */
    @Synchronized
    fun setInterceptRules(data: JSONObject) {
        reqInterceptAll = data.optBoolean("reqAll", false)
        respInterceptAll = data.optBoolean("respAll", false)
        interceptTelemetry = data.optBoolean("interceptTelemetry", false)
        interceptNoise = data.optBoolean("interceptNoise", false)
        interceptCookie = data.optBoolean("interceptCookie", false)
        val rulesJson = data.optJSONArray("rules")
        interceptRulesList = parseInterceptRules(rulesJson)
        saveInterceptConfig(reqInterceptAll, respInterceptAll, rulesJson)
    }

    private fun parseInterceptRules(rulesJson: org.json.JSONArray?): List<InterceptRule> {
        val list = ArrayList<InterceptRule>()
        if (rulesJson != null) {
            for (i in 0 until rulesJson.length()) {
                val o = rulesJson.optJSONObject(i) ?: continue
                list.add(
                    InterceptRule(
                        o.optString("host", ""),
                        o.optString("path", ""),
                        o.optString("method", "GET").uppercase(),
                        o.optBoolean("hasBody", false),
                        o.optString("action", "pass"),
                        o.optBoolean("interceptResp", false),
                    ),
                )
            }
        }
        return list
    }

    private fun saveInterceptConfig(reqAll: Boolean, respAll: Boolean, rulesJson: org.json.JSONArray?) {
        val ctx = appContext ?: return
        try {
            val obj = JSONObject().put("reqAll", reqAll).put("respAll", respAll)
                .put("interceptTelemetry", interceptTelemetry)
                .put("interceptNoise", interceptNoise)
                .put("interceptCookie", interceptCookie)
                .put("rules", rulesJson ?: org.json.JSONArray())
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(INTERCEPT_PREFS_KEY, obj.toString()).apply()
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun loadInterceptConfig() {
        val ctx = appContext ?: return
        try {
            val s = ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getString(INTERCEPT_PREFS_KEY, null) ?: return
            val obj = JSONObject(s)
            reqInterceptAll = obj.optBoolean("reqAll", false)
            respInterceptAll = obj.optBoolean("respAll", false)
            interceptTelemetry = obj.optBoolean("interceptTelemetry", false)
            interceptNoise = obj.optBoolean("interceptNoise", false)
            interceptCookie = obj.optBoolean("interceptCookie", false)
            interceptRulesList = parseInterceptRules(obj.optJSONArray("rules"))
        } catch (_: Throwable) {}
    }

    private fun ruleMatches(r: InterceptRule, host: String, path: String, method: String, hasBody: Boolean): Boolean =
        r.host.equals(host, ignoreCase = true) && r.path == path &&
            r.method.equals(method, ignoreCase = true) && r.hasBody == hasBody

    /** Panel → native: install mock rules (URL-keyword → synthesized response). */
    @Synchronized
    fun setMockRules(data: JSONObject) {
        mockRulesList = parseMockRules(data.optJSONArray("rules"))
        saveMockConfig(data.optJSONArray("rules"))
    }

    private fun parseMockRules(arr: org.json.JSONArray?): List<MockRule> {
        val list = ArrayList<MockRule>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pattern = o.optString("pattern", "")
                if (pattern.isEmpty()) continue
                val headers = LinkedHashMap<String, String>()
                val h = o.optJSONObject("headers")
                if (h != null) {
                    val keys = h.keys()
                    while (keys.hasNext()) { val k = keys.next(); headers[k] = h.optString(k, "") }
                }
                list.add(MockRule(pattern, o.optInt("status", 200), headers, o.optString("body", "")))
            }
        }
        return list
    }

    private fun saveMockConfig(arr: org.json.JSONArray?) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(MOCK_PREFS_KEY, (arr ?: org.json.JSONArray()).toString()).apply()
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun loadMockConfig() {
        val ctx = appContext ?: return
        try {
            val s = ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getString(MOCK_PREFS_KEY, null) ?: return
            mockRulesList = parseMockRules(org.json.JSONArray(s))
        } catch (_: Throwable) {}
    }

    // First mock rule whose pattern is a substring of the full URL (case-insensitive).
    private fun matchMock(host: String, target: String): MockRule? {
        if (mockRulesList.isEmpty()) return null
        val url = "https://$host$target".lowercase()
        return mockRulesList.firstOrNull { url.contains(it.pattern.lowercase()) }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 301 -> "Moved Permanently"
        302 -> "Found"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "OK"
    }

    // Build a full synthetic HTTP/1.1 response (head + body) for a mock rule. The body
    // is sent as identity with a correct Content-Length; Connection: close so the
    // browser doesn't expect more on this (about-to-be-closed) tunnel.
    private fun buildMockResponse(rule: MockRule): ByteArray {
        val bodyBytes = rule.body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(rule.status).append(' ').append(reasonPhrase(rule.status)).append("\r\n")
        var hasCT = false
        for ((k, v) in rule.headers) {
            if (k.equals("content-length", ignoreCase = true) || k.equals("connection", ignoreCase = true) ||
                k.equals("transfer-encoding", ignoreCase = true) || k.equals("content-encoding", ignoreCase = true)) continue
            if (k.equals("content-type", ignoreCase = true)) hasCT = true
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (!hasCT) sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }

    /** Panel → native: install weak-network throttle (download latency + bandwidth). */
    @Synchronized
    fun setThrottle(data: JSONObject) {
        applyThrottleConfig(data)
        saveThrottleConfig(data)
    }

    private fun applyThrottleConfig(data: JSONObject) {
        throttleLatencyMs = maxOf(0L, data.optLong("latencyMs", 0L))
        throttleKbps = maxOf(0L, data.optLong("kbps", 0L))
        throttleEnabled = data.optBoolean("enabled", false) && (throttleLatencyMs > 0 || throttleKbps > 0)
    }

    private fun saveThrottleConfig(data: JSONObject) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(THROTTLE_PREFS_KEY, data.toString()).apply()
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun loadThrottleConfig() {
        val ctx = appContext ?: return
        try {
            val s = ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getString(THROTTLE_PREFS_KEY, null) ?: return
            applyThrottleConfig(JSONObject(s))
        } catch (_: Throwable) {}
    }

    // Apply the once-per-response latency (download direction). Cheap no-op when off.
    private fun throttleLatency() {
        if (throttleEnabled && throttleLatencyMs > 0) {
            try { Thread.sleep(throttleLatencyMs) } catch (_: InterruptedException) {}
        }
    }

    // Wrap the client output stream so the response body is paced to throttleKbps.
    // Always wraps (the stream reads the volatile fields live and is a pass-through
    // when throttle is off / kbps<=0), so runtime changes take effect on new writes.
    private fun throttleWrap(out: OutputStream): OutputStream = ThrottledOutputStream(out)

    // Write-through bandwidth limiter: forwards bytes immediately in small slices and
    // sleeps between slices so throughput stays under throttleKbps. NEVER buffers the
    // whole body (坑#4): streaming/SSE still flows, just paced. Pass-through when off.
    private class ThrottledOutputStream(private val out: OutputStream) : OutputStream() {
        private var windowStartNs = System.nanoTime()
        private var windowBytes = 0L
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val bps = if (throttleEnabled) throttleKbps * 1024L else 0L
            if (bps <= 0L) { out.write(b, off, len); return }
            var p = off
            var remaining = len
            val slice = 8 * 1024
            while (remaining > 0) {
                val n = minOf(slice, remaining)
                out.write(b, p, n)
                out.flush()
                p += n; remaining -= n; windowBytes += n
                val elapsedNs = System.nanoTime() - windowStartNs
                val expectedNs = (windowBytes.toDouble() / bps * 1_000_000_000.0).toLong()
                val sleepNs = expectedNs - elapsedNs
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) } catch (_: InterruptedException) {}
                }
                if (elapsedNs > 1_000_000_000L) { windowStartNs = System.nanoTime(); windowBytes = 0L }
            }
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /** Panel → native: arm/disarm the SSE hold and set the host allow-list + heartbeat. */
    @Synchronized
    fun setSseHoldConfig(data: JSONObject) {
        applySseHoldConfig(data)
        saveSseHoldConfig(data)
    }

    private fun applySseHoldConfig(data: JSONObject) {
        sseHoldEnabled = data.optBoolean("enabled", false)
        val arr = data.optJSONArray("hosts")
        val hosts = ArrayList<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val h = arr.optString(i, "").trim().lowercase()
                if (h.isNotEmpty()) hosts.add(h)
            }
        }
        sseHoldHosts = hosts
        val hb = data.optString("heartbeat", "")
        if (hb.isNotEmpty()) sseHoldHeartbeat = hb
    }

    private fun saveSseHoldConfig(data: JSONObject) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SSE_HOLD_PREFS_KEY, data.toString()).apply()
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun loadSseHoldConfig() {
        val ctx = appContext ?: return
        try {
            val s = ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getString(SSE_HOLD_PREFS_KEY, null) ?: return
            applySseHoldConfig(JSONObject(s))
        } catch (_: Throwable) {}
    }

    private fun sseHoldActiveFor(host: String): Boolean {
        if (!sseHoldEnabled || host.isEmpty()) return false
        val h = host.lowercase()
        return sseHoldHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun isEventStream(contentType: String?): Boolean =
        contentType != null && contentType.lowercase().contains("text/event-stream")

    // Telemetry/noise/cookie body guard at request time: small request body, GET-only.
    // (Panel lowValueGuard's resp-size clause is always satisfied here since the response
    // hasn't arrived → sb==0.)
    private fun lowValueGuard(method: String, reqBodyLen: Long): Boolean {
        if (reqBodyLen > 512) return false
        if (!method.equals("GET", ignoreCase = true) && reqBodyLen > 0) return false
        return true
    }

    // True for low-value telemetry/noise/cookie traffic that "intercept all" lets pass.
    // A class whose per-class intercept toggle is ON is no longer treated as low-value
    // (so it gets paused like everything else). Default (all toggles off) = the old
    // behavior: telemetry/noise/cookie all pass.
    private fun isLowValueUrl(host: String, target: String, method: String, reqBodyLen: Long): Boolean {
        if (!lowValueGuard(method, reqBodyLen)) return false
        val url = "https://$host$target".lowercase()
        if (!interceptTelemetry && telemetryRe.containsMatchIn(url)) return true
        if (!interceptNoise && noiseRe.containsMatchIn(url)) return true
        if (!interceptCookie && cookieRe.containsMatchIn(url)) return true
        return false
    }

    // Request paused iff: explicit pass rule → never; explicit intercept rule → always;
    // else (intercept-all on) everything bounded except low-value telemetry/noise/cookie.
    private fun shouldInterceptReq(host: String, target: String, pathOnly: String, method: String, hasBody: Boolean, reqBodyLen: Long): Boolean {
        val rule = interceptRulesList.firstOrNull { ruleMatches(it, host, pathOnly, method, hasBody) }
        if (rule != null) {
            if (rule.action == "pass") return false
            if (rule.action == "intercept") return true
        }
        if (!reqInterceptAll) return false
        return !isLowValueUrl(host, target, method, reqBodyLen)
    }

    // Response paused iff: a matching rule asks for it (interceptResp); else any present
    // rule governs fully (explicit → no blanket); else (intercept-all-resp on) every
    // bounded response except low-value telemetry/noise/cookie.
    private fun shouldInterceptResp(info: FlowInfo): Boolean {
        val pathOnly = info.target.substringBefore('?').substringBefore('#')
        val rule = interceptRulesList.firstOrNull { ruleMatches(it, info.host, pathOnly, info.method, info.hasBody) }
        if (rule != null) {
            if (rule.interceptResp) return true
            return false
        }
        if (!respInterceptAll) return false
        return !isLowValueUrl(info.host, info.target, info.method, if (info.hasBody) 1L else 0L)
    }

    /** Panel → native: the user's decision for a paused request flow. */
    fun resolveIntercept(flowId: String, decision: JSONObject) {
        pendingIntercepts.remove(flowId)?.complete(decision)
    }

    /** Panel → native: the user's decision for a paused response flow. */
    fun resolveRespIntercept(flowId: String, decision: JSONObject) {
        pendingRespIntercepts.remove(flowId)?.complete(decision)
    }

    // Parse a raw HTTP head's header lines into a JSONObject (last value wins).
    private fun headersJson(head: String): JSONObject {
        val headers = JSONObject()
        val lines = head.split("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        return headers
    }

    // Block the request thread until the panel resolves this flow (or timeout).
    // Returns the decision JSON, or null on timeout/error (caller forwards original).
    private fun awaitReqIntercept(flowId: String, host: String, target: String, reqHead: String, body: ByteArray): JSONObject? {
        val fut = CompletableFuture<JSONObject>()
        pendingIntercepts[flowId] = fut
        val method = reqHead.substringBefore("\r\n").trimStart().substringBefore(' ').trim()
        val bodyText = if (isLikelyText(body)) String(body, Charsets.UTF_8) else ""
        emit(
            JSONObject()
                .put("type", "reqIntercept")
                .put("flowId", flowId)
                .put("url", "https://$host$target")
                .put("method", method)
                .put("reqHeaders", headersJson(reqHead))
                .put("reqBody", bodyText),
        )
        return try {
            fut.get(INTERCEPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            pendingIntercepts.remove(flowId)
            null
        }
    }

    // Rebuild a request head from the panel's edited decision (origin-form target,
    // edited headers, fresh Content-Length; Transfer-Encoding/old CL dropped).
    private fun buildReqHead(decision: JSONObject, host: String, origHead: String, bodyLen: Int): String {
        val method = decision.optString("method", "GET").uppercase()
        val target = requestTargetFromUrl(decision.optString("url", ""), origHead)
        val sb = StringBuilder()
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        val headers = decision.optJSONObject("reqHeaders")
        var hasHost = false
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals("content-length", ignoreCase = true) || k.equals("transfer-encoding", ignoreCase = true)) continue
                if (k.equals("host", ignoreCase = true)) hasHost = true
                sb.append(k).append(": ").append(headers.optString(k)).append("\r\n")
            }
        }
        if (!hasHost) sb.append("Host: ").append(host).append("\r\n")
        sb.append("Content-Length: ").append(bodyLen).append("\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    // Derive the origin-form request target (path+query) from the panel's URL field,
    // falling back to the original request line's target if the URL is empty/relative.
    private fun requestTargetFromUrl(url: String, origHead: String): String {
        val orig = origHead.substringBefore("\r\n").split(" ").getOrElse(1) { "/" }
        if (url.isEmpty()) return orig
        val schemeIdx = url.indexOf("://")
        if (schemeIdx < 0) return if (url.startsWith("/")) url else orig
        val afterScheme = url.substring(schemeIdx + 3)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash)
    }

    // Block the response thread until the panel resolves this flow (or timeout).
    // Body is the already-decoded (identity) response body so the panel can edit text.
    private fun awaitRespIntercept(flowId: String, respHead: String, decodedBody: ByteArray): JSONObject? {
        val fut = CompletableFuture<JSONObject>()
        pendingRespIntercepts[flowId] = fut
        val status = respHead.substringBefore("\r\n").split(" ").getOrElse(1) { "" }.toIntOrNull() ?: 0
        val bodyText = if (isLikelyText(decodedBody)) String(decodedBody, Charsets.UTF_8) else ""
        emit(
            JSONObject()
                .put("type", "respIntercept")
                .put("flowId", flowId)
                .put("status", status)
                .put("respHeaders", headersJson(respHead))
                .put("respBody", bodyText),
        )
        return try {
            fut.get(INTERCEPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            pendingRespIntercepts.remove(flowId)
            null
        }
    }

    // Rebuild a response head from the panel's edited decision (status line reason kept
    // from the original; edited headers; identity body so old CE/CL/TE are dropped).
    private fun buildRespHead(decision: JSONObject, origHead: String, bodyLen: Int): String {
        val origLine = origHead.substringBefore("\r\n").split(" ", limit = 3)
        val status = decision.optInt("status", origLine.getOrElse(1) { "200" }.toIntOrNull() ?: 200)
        val reason = origLine.getOrElse(2) { "" }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        val headers = decision.optJSONObject("respHeaders")
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.equals("content-length", ignoreCase = true) ||
                    k.equals("transfer-encoding", ignoreCase = true) ||
                    k.equals("content-encoding", ignoreCase = true)
                ) {
                    continue
                }
                sb.append(k).append(": ").append(headers.optString(k)).append("\r\n")
            }
        }
        sb.append("Content-Length: ").append(bodyLen).append("\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

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

    // Remove every header line named [name] (case-insensitive) from a raw head,
    // preserving the request/status line, the remaining headers and the CRLF framing.
    private fun stripHeader(head: String, name: String): String {
        val lines = head.split("\r\n")
        val kept = lines.filterIndexed { idx, line ->
            if (idx == 0) return@filterIndexed true // request/status line
            val c = line.indexOf(':')
            !(c > 0 && line.substring(0, c).trim().equals(name, ignoreCase = true))
        }
        return kept.joinToString("\r\n")
    }

    // Content-types we will buffer-and-replace even when chunked/compressed. These are
    // FINITE documents that terminate promptly. event-stream (SSE) is explicitly excluded
    // because it is a long-lived stream that must never be buffered (CLAUDE.md 坑#4).
    private fun isReplaceableDoc(ct: String?): Boolean {
        val c = ct?.lowercase() ?: return false
        if (c.contains("event-stream")) return false
        return c.contains("text/html") || c.contains("xhtml") ||
            c.contains("application/json") || c.contains("javascript") ||
            c.contains("text/css") || c.contains("xml") || c.contains("text/plain")
    }

    // Rewrite Accept-Encoding to encodings we can decode (gzip/deflate/br), but only when
    // it advertises zstd (which we have no decoder for). Leaves all other requests as-is.
    private fun downgradeAcceptEncoding(head: String): String {
        val cur = headerValue(head, "Accept-Encoding") ?: return head
        if (!cur.lowercase().contains("zstd")) return head
        val lines = head.split("\r\n").toMutableList()
        for (i in lines.indices) {
            val c = lines[i].indexOf(':')
            if (c > 0 && lines[i].substring(0, c).trim().equals("Accept-Encoding", ignoreCase = true)) {
                lines[i] = "Accept-Encoding: gzip, deflate, br"
            }
        }
        return lines.joinToString("\r\n")
    }

    // Whether we have a decoder for this Content-Encoding (so buffering to replace is
    // worthwhile). Unknown encodings (e.g. zstd) can't be decoded → don't buffer them;
    // stream verbatim instead (the browser still decodes them natively).
    private fun canDecodeCe(ce: String?): Boolean {
        val c = ce?.lowercase()?.trim() ?: return true
        if (c.isEmpty() || c == "identity") return true
        return c.contains("gzip") || c.contains("br") || c.contains("deflate")
    }

    // Re-frame a (de-chunked) body as identity: drop Transfer-Encoding and any existing
    // Content-Length, optionally drop Content-Encoding (when stripCe — i.e. we decoded the
    // body), then add an accurate Content-Length. Preserves the status line and CRLF tail.
    private fun toIdentityHead(head: String, stripCe: Boolean, size: Int): String {
        var h = stripHeader(head, "Transfer-Encoding")
        if (stripCe) h = stripHeader(h, "Content-Encoding")
        h = stripHeader(h, "Content-Length")
        val lines = h.split("\r\n").toMutableList()
        if (lines.isNotEmpty()) lines.add(1, "Content-Length: $size")
        return lines.joinToString("\r\n")
    }

    // De-chunk a chunked response body into one buffer (still in its original
    // Content-Encoding). Bounded by [rawCap] and guarded by a socket read timeout so a
    // mislabeled never-ending stream can't hang the connection. Returns null on cap
    // overflow / timeout / malformed framing / premature EOF — the caller then tears the
    // connection down (bytes were already consumed; it cannot resume verbatim).
    private fun readChunkedToBuffer(upstream: Socket, input: InputStream, rawCap: Int): ByteArray? {
        val prevTimeout = try { upstream.soTimeout } catch (_: Throwable) { 0 }
        try { upstream.soTimeout = CHUNK_REPLACE_TIMEOUT_MS } catch (_: Throwable) {}
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val sizeLine = readAsciiLine(input) ?: return null
                val hex = sizeLine.trim().substringBefore(';').trim()
                val size = hex.toLongOrNull(16) ?: return null
                if (size == 0L) {
                    // Last chunk: consume trailers up to the terminating blank line.
                    while (true) {
                        val tl = readAsciiLine(input) ?: break
                        if (tl == "\r\n" || tl == "\n" || tl.isEmpty()) break
                    }
                    return out.toByteArray()
                }
                var remaining = size
                while (remaining > 0) {
                    val want = if (remaining < buf.size) remaining.toInt() else buf.size
                    val n = input.read(buf, 0, want)
                    if (n < 0) return null
                    out.write(buf, 0, n)
                    if (out.size() > rawCap) return null
                    remaining -= n
                }
                readAsciiLine(input) ?: return null // CRLF after chunk data
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { upstream.soTimeout = prevTimeout } catch (_: Throwable) {}
        }
    }

    // Write one HTTP/1.1 chunk: "<hexlen>\r\n<data>\r\n". The caller must hold the
    // cOut monitor so the heartbeat thread and the final replay never interleave bytes.
    private fun writeChunk(out: OutputStream, data: ByteArray) {
        out.write((Integer.toHexString(data.size) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    // Hold a configured SSE response (the head — with Transfer-Encoding: chunked — was
    // already written verbatim by the caller). A daemon heartbeat thread writes SSE
    // comment chunks every SSE_HEARTBEAT_MS to keep the browser's transport alive while
    // we (1) de-chunk the whole upstream stream into a bounded buffer, (2) hand it to
    // the panel via the EXISTING response-intercept queue/detail UI (放行/搜索/复制),
    // and (3) BLOCK with no hard timeout until the panel releases an edited body. The
    // edited (or, on fail-open, original) body is replayed as one chunk + the 0-length
    // terminator, completing the chunked stream the browser is reading. Returns true
    // (caller continues the response loop).
    //
    // Reuses pendingRespIntercepts + the "respIntercept" event + resolveRespIntercept so
    // the SSE hold shows up in the same intercepted-response list/detail box as a normal
    // response intercept — the only differences are the heartbeat keep-alive and the
    // no-timeout wait (a quick bounded response uses awaitRespIntercept's 60s timeout).
    //
    // Fail-open guarantees (CLAUDE.md 坑#4): a heartbeat write failure means the browser
    // closed the stream → we stop and unblock the wait; the de-chunk read is bounded by
    // SSE_HOLD_CAP + the inherited 30s read timeout; if buffering fails we replay an
    // empty terminator so the socket is never left hanging.
    private fun holdSseAndReplay(flowId: String, upstream: Socket, uIn: InputStream, cOut: OutputStream, respHead: String): Boolean {
        val beating = java.util.concurrent.atomic.AtomicBoolean(true)
        val hbBytes = sseHoldHeartbeat.toByteArray(Charsets.UTF_8)
        val fut = CompletableFuture<JSONObject>()
        pendingRespIntercepts[flowId] = fut
        val hb = Thread {
            try {
                while (beating.get()) {
                    Thread.sleep(SSE_HEARTBEAT_MS)
                    if (!beating.get()) break
                    synchronized(cOut) {
                        if (!beating.get()) return@synchronized
                        writeChunk(cOut, hbBytes)
                        cOut.flush()
                    }
                }
            } catch (_: Throwable) {
                // Browser closed the stream (write failed) or thread interrupted: stop
                // beating and fail-open the wait so the response thread never hangs.
                beating.set(false)
                pendingRespIntercepts.remove(flowId)?.complete(JSONObject().put("decision", "failopen"))
            }
        }
        hb.isDaemon = true
        hb.start()

        // Buffer the full upstream SSE stream (de-chunked) under the read-timeout guard.
        val raw = readChunkedToBuffer(upstream, uIn, SSE_HOLD_CAP)

        // Hand the captured stream to the panel via the existing respIntercept UI.
        val status = respHead.substringBefore("\r\n").split(" ").getOrElse(1) { "" }.toIntOrNull() ?: 200
        val bodyText = if (raw != null && isLikelyText(raw)) String(raw, Charsets.UTF_8) else ""
        emit(
            JSONObject()
                .put("type", "respIntercept")
                .put("flowId", flowId)
                .put("status", status)
                .put("respHeaders", headersJson(respHead))
                .put("respBody", bodyText),
        )

        // Block until the panel releases. No hard timeout BY DESIGN — the heartbeat keeps
        // the page alive while the user edits; only a heartbeat write failure (browser
        // gone) ends the wait early via fail-open.
        val decision = try { fut.get() } catch (_: Throwable) { null }
        pendingRespIntercepts.remove(flowId)
        beating.set(false)
        hb.interrupt()

        val verdict = decision?.optString("decision") ?: "failopen"
        // abort: head already sent → just terminate the (so-far heartbeat-only) stream.
        val outBody = when {
            verdict == "abort" -> ByteArray(0)
            verdict == "continue" -> (decision?.optString("respBody") ?: "").toByteArray(Charsets.UTF_8)
            raw != null -> raw // fail-open with captured data → replay original
            else -> ByteArray(0)
        }
        try {
            synchronized(cOut) {
                if (outBody.isNotEmpty()) writeChunk(cOut, outBody)
                cOut.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                cOut.flush()
            }
        } catch (_: Throwable) {}
        return true
    }

    // Read one line (through LF) as ISO-8859-1; null on immediate EOF. Length-capped.
    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            out.write(b)
            if (b == '\n'.code) break
            if (out.size() > 64 * 1024) break
        }
        return out.toString("ISO-8859-1")
    }

    // Fully decode a bounded response body so its text can be replaced. Returns null
    // when we must leave the body untouched: unknown encoding (e.g. zstd), a decode
    // error (corrupt/partial), or a decoded size beyond REPLACE_DECODED_CAP (a
    // decompression-bomb guard — the caller then forwards the original bytes verbatim).
    private fun decodeForReplace(body: ByteArray, contentEncoding: String?): ByteArray? {
        val ce = contentEncoding?.lowercase()?.trim() ?: ""
        if (ce.isEmpty() || ce == "identity") return body
        val stream = when {
            ce.contains("gzip") -> java.util.zip.GZIPInputStream(body.inputStream())
            ce.contains("br") -> org.brotli.dec.BrotliInputStream(body.inputStream())
            ce.contains("deflate") -> java.util.zip.InflaterInputStream(body.inputStream())
            else -> return null
        }
        return try {
            val out = ByteArrayOutputStream(body.size * 2)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > REPLACE_DECODED_CAP) return null
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
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
        saveProxyEnabled(on)
        if (on && !running) start()
        else if (!on && running) stop()
    }

    private fun saveProxyEnabled(on: Boolean) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PROXY_ENABLED_KEY, on).apply()
        } catch (_: Throwable) {}
    }

    private fun loadProxyEnabled(): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.getSharedPreferences(REPLACE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PROXY_ENABLED_KEY, false)
        } catch (_: Throwable) { false }
    }

    fun isRunning(): Boolean = running

    /**
     * Called once when the GeckoRuntime is (re)created. The proxy prefs are
     * written to PREF_BRANCH_USER, which persists to disk and is restored on the
     * next process launch — but the ServerSocket lives only in this in-memory
     * object and dies with the process. So after a background kill + relaunch,
     * Gecko points at a dead local port and every request fails with
     * NS_ERROR_PROXY_CONNECTION_REFUSED until the probe is re-armed.
     *
     * We honor the persisted 监听 intent: if the proxy was ON, AUTO-ARM here with a
     * FRESH socket + fresh port (start() rewrites network.proxy.type=1 to the new
     * port), so a relaunch resumes capture without a manual re-toggle and the panel's
     * 监听 state matches reality. If it was OFF, reset to direct for a clean launch.
     */
    @Synchronized
    fun resetProxyStateOnStartup(context: Context) {
        appContext = context.applicationContext
        running = false
        // Restore the persisted configs so a cold launch resumes replace/intercept/
        // mock/throttle without depending on the panel re-pushing.
        loadReplaceConfig()
        loadInterceptConfig()
        loadSseHoldConfig()
        loadMockConfig()
        loadThrottleConfig()
        if (loadProxyEnabled()) {
            // Auto-arm: bind a new ServerSocket + point Gecko at it (kills the dead-port
            // failure mode). start() handles CA + enterprise-roots reimport too.
            start()
        } else {
            setInt("network.proxy.type", 0)
        }
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
            // Wrap the download stream for 弱网 throttle (live no-op when off, never buffers).
            val cOut = throttleWrap(tlsClient.outputStream)
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
            Thread({ pumpRequests(cIn, uOut, host, flowQueue, tlsClient) }, "bh-proxy-req")
                .apply { isDaemon = true }.start()

            // Response direction (increment 2b): loop reading each response head (so
            // EVERY keep-alive request gets its own flowResp → duration fills in, no
            // more pending "…"), tee bounded (<256K) Content-Length AND chunked bodies
            // (de-chunked on the copy; gzip/deflate/brotli decoded for the panel only).
            // 101 / Upgrade / no-Content-Length non-chunked bail to a raw blocking copy
            // — keeps the page stable. Forwarding is always byte-for-byte.
            pumpResponses(upstream, uIn, cOut, flowQueue)
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
    private fun pumpRequests(cIn: InputStream, uOut: OutputStream, host: String, flowQueue: ConcurrentLinkedQueue<FlowInfo>, clientSock: Socket) {
        var first = true
        try {
            while (true) {
                var reqHead = readHead(cIn) ?: break
                // When response-replace is armed, drop zstd from Accept-Encoding so the
                // server answers with gzip/deflate/br — encodings we can actually decode
                // and rewrite (otherwise zstd bodies pass through unreplaced). Header-only
                // edit on the buffered head; the body pump is untouched.
                if (replaceActiveForResp()) reqHead = downgradeAcceptEncoding(reqHead)
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
                // The same boundedness gate used for rewrite also gates intercept: we
                // only ever PAUSE a flow we can fully buffer and re-emit (CLAUDE.md 坑#4).
                val bounded = upgrade == null &&
                    (te == null || !te.contains("chunked", ignoreCase = true)) &&
                    (expect == null || !expect.contains("100-continue", ignoreCase = true)) &&
                    !(clStr != null && cl == null) &&
                    (cl == null || cl <= REPLACE_CAP)
                val bodyPresent = cl != null && cl > 0
                val target = reqHead.substringBefore("\r\n").split(" ").getOrElse(1) { "/" }
                val pathOnly = target.substringBefore('?').substringBefore('#')
                // Mock (Map Local) takes precedence: a matched bounded request is answered
                // locally with a synthetic response — upstream is never contacted. Drain the
                // (bounded) request body so the TLS stream is consumed, emit the flowReq for
                // panel visibility, write the synthetic response, then close (return).
                val mockRule = if (bounded) matchMock(host, target) else null
                if (mockRule != null) {
                    if (cl != null && cl > 0) readExact(cIn, cl.toInt())
                    emitFlowReq(flowId, host, reqHead)
                    try {
                        synchronized(clientSock) {
                            clientSock.outputStream.write(buildMockResponse(mockRule))
                            clientSock.outputStream.flush()
                        }
                    } catch (_: Throwable) {}
                    return
                }
                val doIntercept = bounded && shouldInterceptReq(host, target, pathOnly, method, bodyPresent, cl ?: 0L)
                if (doIntercept) {
                    // Buffer the bounded body (a complete known-size upload — safe to read
                    // in full), pause for the panel, then forward the decision. On timeout
                    // (decision == null) or "continue" we forward; on "abort" we reset.
                    val body = if (cl != null && cl > 0) readExact(cIn, cl.toInt()) else ByteArray(0)
                    val decision = awaitReqIntercept(flowId, host, target, reqHead, body)
                    val verdict = decision?.optString("decision") ?: "continue"
                    if (verdict == "abort") {
                        try {
                            synchronized(clientSock) {
                                clientSock.outputStream.write(
                                    "HTTP/1.1 403 Intercepted by BrowserHelper\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                                clientSock.outputStream.flush()
                            }
                        } catch (_: Throwable) {}
                        return
                    }
                    // Forward the edited request on "continue" (decision present), else the
                    // original on timeout/fail-open. The inline decision != null check lets
                    // Kotlin smart-cast it non-null for optString/buildReqHead.
                    val fBody = if (decision != null && verdict == "continue") {
                        decision.optString("reqBody", "").toByteArray(Charsets.UTF_8)
                    } else {
                        body
                    }
                    // Only rebuild the head when the user actually edited method/url/
                    // headers. Otherwise keep the ORIGINAL head bytes verbatim (wire
                    // order preserved) — rebuilding from an unordered JSONObject scrambles
                    // header order, which anti-bot layers (Cloudflare/ChatGPT) fingerprint,
                    // making the server withhold its response (observed as a no-reply hang).
                    // Just patch Content-Length to match the (possibly edited) body.
                    val fHead = if (decision != null && verdict == "continue" && decision.optBoolean("headEdited", false)) {
                        buildReqHead(decision, host, reqHead, fBody.size)
                    } else if (decision != null && verdict == "continue") {
                        forceContentLength(reqHead, fBody.size)
                    } else {
                        reqHead
                    }
                    uOut.write(fHead.toByteArray(Charsets.ISO_8859_1))
                    if (fBody.isNotEmpty()) uOut.write(fBody)
                    uOut.flush()
                    // The panel already created this flow's list entry from the reqIntercept
                    // event (and reflects the user's edits locally), so we DON'T re-emit
                    // flowReq here — that would duplicate the row. We still enqueue the
                    // flowId internally so the response loop stays FIFO-aligned.
                    flowQueue.add(FlowInfo(flowId, method, host, target, bodyPresent))
                    continue
                }
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
                    flowQueue.add(FlowInfo(flowId, method, host, target, bodyPresent))
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
                flowQueue.add(FlowInfo(flowId, method, host, target, bodyPresent))
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
    private class FlowInfo(
        val flowId: String,
        val method: String,
        val host: String,
        val target: String,
        val hasBody: Boolean,
    )

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
    private fun pumpResponses(upstream: Socket, uIn: InputStream, cOut: OutputStream, flowQueue: ConcurrentLinkedQueue<FlowInfo>) {
        try {
            while (true) {
                val respHead = readHead(uIn) ?: break
                val status = respHead.substringBefore("\r\n").split(" ").getOrElse(1) { "" }.toIntOrNull() ?: 0
                // 101 / interim 1xx: forward head verbatim, no body framing here.
                if (status == 101 || headerValue(respHead, "Upgrade") != null) {
                    cOut.write(respHead.toByteArray(Charsets.ISO_8859_1)); cOut.flush()
                    pumpInline(uIn, cOut)
                    return
                }
                if (status in 100..199) {
                    // Interim response: no body, same request's final response follows.
                    cOut.write(respHead.toByteArray(Charsets.ISO_8859_1)); cOut.flush()
                    continue
                }
                // 弱网: delay each final response head by the configured latency (no-op when off).
                throttleLatency()
                val info = flowQueue.poll()
                val flowId = info?.flowId ?: flowSeq.incrementAndGet().toString()
                val method = info?.method ?: "GET"
                val noBody = method.equals("HEAD", ignoreCase = true) || status == 204 || status == 304
                val ce = headerValue(respHead, "Content-Encoding")
                val te = headerValue(respHead, "Transfer-Encoding")
                val cl = headerValue(respHead, "Content-Length")?.toLongOrNull()

                // Response interception (Phase 2) — pause the BOUNDED response for the
                // panel to edit status/headers/body, then forward the decision. Same
                // boundedness gate as rewrite: known Content-Length, not chunked, has a
                // body, ≤cap. Streaming / SSE / chunked / no-CL are NEVER paused (they
                // fall through to the verbatim model below, CLAUDE.md 坑#4). Timeout /
                // panel-disconnect → fail-open (forward decoded body), never hangs.
                val respIntercept = info != null && !noBody &&
                    (te == null || !te.contains("chunked", ignoreCase = true)) &&
                    cl != null && cl >= 1L && cl <= REPLACE_CAP.toLong() &&
                    shouldInterceptResp(info)
                if (respIntercept) {
                    val raw = readExact(uIn, cl.toInt())
                    val decoded = decodeForReplace(raw, ce)
                    if (decoded == null) {
                        // Can't decode → no editable text; forward original untouched.
                        cOut.write(respHead.toByteArray(Charsets.ISO_8859_1))
                        cOut.write(raw); cOut.flush()
                        emitFlowResp(flowId, respHead)
                        val teeLen = minOf(raw.size, 256 * 1024)
                        emitFlowRespBody(flowId, raw.copyOf(teeLen), raw.size > teeLen, ce)
                        continue
                    }
                    val decision = awaitRespIntercept(flowId, respHead, decoded)
                    val verdict = decision?.optString("decision") ?: "continue"
                    if (verdict == "abort") {
                        try {
                            cOut.write(
                                "HTTP/1.1 502 Intercepted by BrowserHelper\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.ISO_8859_1),
                            )
                            cOut.flush()
                        } catch (_: Throwable) {}
                        return
                    }
                    val fBody = if (decision != null && verdict == "continue") {
                        decision.optString("respBody", "").toByteArray(Charsets.UTF_8)
                    } else {
                        decoded
                    }
                    val fHead = if (decision != null && verdict == "continue") {
                        buildRespHead(decision, respHead, fBody.size)
                    } else {
                        // Fail-open: forward the decoded body as identity (drop CE, fix CL).
                        var h = respHead
                        if (ce != null) h = stripHeader(h, "Content-Encoding")
                        forceContentLength(h, fBody.size)
                    }
                    cOut.write(fHead.toByteArray(Charsets.ISO_8859_1))
                    if (fBody.isNotEmpty()) cOut.write(fBody)
                    cOut.flush()
                    emitFlowResp(flowId, fHead)
                    val teeLen = minOf(fBody.size, 256 * 1024)
                    emitFlowRespBody(flowId, fBody.copyOf(teeLen), fBody.size > teeLen, null)
                    continue
                }

                // Response rewrite — ONLY the safe bounded subset: known Content-Length,
                // not chunked, has a body, ≤cap. Streaming / SSE / chunked / no-CL are
                // NEVER buffered (CLAUDE.md 坑#4): they fall straight through to the
                // verbatim forward-as-read paths below. When replace is OFF or scope
                // excludes resp, respRewritable is false → byte-identical to the working
                // model. The body is fully read into memory either way, so even the
                // "can't decode → forward original" branch never desyncs the socket.
                val respRewritable = replaceActiveForResp() && !noBody &&
                    (te == null || !te.contains("chunked", ignoreCase = true)) &&
                    cl != null && cl >= 1L && cl <= REPLACE_CAP.toLong()

                if (respRewritable) {
                    val raw = readExact(uIn, cl.toInt())
                    val decoded = decodeForReplace(raw, ce)
                    if (decoded == null) {
                        // Unknown/oversized/corrupt encoding → forward the original bytes
                        // untouched (no replacement), keeping the page correct.
                        cOut.write(respHead.toByteArray(Charsets.ISO_8859_1))
                        cOut.write(raw); cOut.flush()
                        emitFlowResp(flowId, respHead)
                        val teeLen = minOf(raw.size, 256 * 1024)
                        emitFlowRespBody(flowId, raw.copyOf(teeLen), raw.size > teeLen, ce)
                        continue
                    }
                    val newBody = applyReplaceBytes(decoded)
                    var newHead = applyReplaceStr(respHead)
                    // We send the (possibly decompressed) body as identity, so drop the
                    // old Content-Encoding and rewrite Content-Length to the real size.
                    if (ce != null) newHead = stripHeader(newHead, "Content-Encoding")
                    newHead = forceContentLength(newHead, newBody.size)
                    cOut.write(newHead.toByteArray(Charsets.ISO_8859_1))
                    if (newBody.isNotEmpty()) cOut.write(newBody)
                    cOut.flush()
                    emitFlowResp(flowId, newHead)
                    val teeLen = minOf(newBody.size, 256 * 1024)
                    emitFlowRespBody(flowId, newBody.copyOf(teeLen), newBody.size > teeLen, null)
                    continue
                }

                // ── verbatim forward-as-read (unchanged working model) ──
                cOut.write(respHead.toByteArray(Charsets.ISO_8859_1)); cOut.flush()
                emitFlowResp(flowId, respHead)
                if (noBody) continue // no body by definition
                if (te != null && te.contains("chunked", ignoreCase = true)) {
                    // SSE hold ("截流" plugin): armed host + text/event-stream + identity
                    // encoding only. Keeps the head's chunked framing; heartbeats the
                    // browser while it buffers + waits for the panel's edited body. This
                    // is the single, tightly-gated streaming-buffer exception (坑#4).
                    if (sseHoldActiveFor(info?.host ?: "") &&
                        isEventStream(headerValue(respHead, "Content-Type")) &&
                        (ce == null || ce.isEmpty() || ce.equals("identity", ignoreCase = true))) {
                        if (holdSseAndReplay(flowId, upstream, uIn, cOut, respHead)) continue
                    }
                    // Gated buffered replace: ONLY for finite document content-types
                    // (html/json/js/css/xml/text, never SSE). De-chunk the whole body
                    // (bounded + read-timeout), decode, replace, re-emit as identity.
                    // This is the one deliberate exception to forward-as-read; it never
                    // touches streaming/SSE responses, which stay on the pump path below.
                    if (replaceActiveForResp() && isReplaceableDoc(headerValue(respHead, "Content-Type")) &&
                        canDecodeCe(ce)) {
                        val raw = readChunkedToBuffer(upstream, uIn, REPLACE_CHUNK_RAW_CAP)
                        if (raw == null) return // partial/timeout/overflow → tear down
                        val decoded = decodeForReplace(raw, ce)
                        if (decoded == null) {
                            // Decodable encoding but the stream was corrupt/oversized:
                            // forward the de-chunked bytes as identity, KEEPING
                            // Content-Encoding so the browser still decodes them. No
                            // replacement (page stays correct).
                            val h = toIdentityHead(respHead, false, raw.size)
                            cOut.write(h.toByteArray(Charsets.ISO_8859_1))
                            if (raw.isNotEmpty()) cOut.write(raw)
                            cOut.flush()
                            emitFlowResp(flowId, h)
                            val teeLen = minOf(raw.size, 256 * 1024)
                            emitFlowRespBody(flowId, raw.copyOf(teeLen), raw.size > teeLen, ce)
                            continue
                        }
                        val newBody = applyReplaceBytes(decoded)
                        val h = toIdentityHead(applyReplaceStr(respHead), ce != null, newBody.size)
                        cOut.write(h.toByteArray(Charsets.ISO_8859_1))
                        if (newBody.isNotEmpty()) cOut.write(newBody)
                        cOut.flush()
                        emitFlowResp(flowId, h)
                        val teeLen = minOf(newBody.size, 256 * 1024)
                        emitFlowRespBody(flowId, newBody.copyOf(teeLen), newBody.size > teeLen, null)
                        continue
                    }
                    // chunked body: frame chunk-by-chunk (forward-as-read), teeing a
                    // bounded snapshot. SSE tokens are forwarded the instant they
                    // arrive (tee never delays the stream). A malformed chunk size
                    // bails to raw copy of the rest (page stays correct).
                    val r = relayChunked(uIn, cOut, 256 * 1024)
                    emitFlowRespBody(flowId, r.bytes, r.truncated, ce)
                    if (r.bailed) return
                    continue
                }
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
        var undecodable = false
        val decoded = try {
            when {
                ce.contains("gzip") -> decodeBestEffort(java.util.zip.GZIPInputStream(body.inputStream()))
                ce.contains("br") -> decodeBestEffort(org.brotli.dec.BrotliInputStream(body.inputStream()))
                ce.contains("deflate") -> decodeBestEffort(java.util.zip.InflaterInputStream(body.inputStream()))
                ce.isNotEmpty() && ce != "identity" -> { undecodable = true; body } // unknown enc (zstd…)
                else -> body
            }
        } catch (_: Throwable) {
            undecodable = true // claimed gzip/br/deflate but stream isn't → still compressed
            body
        }
        // Never dump still-compressed / binary bytes into the panel as mojibake: show a
        // clean placeholder instead (display-only; the forwarded stream is untouched).
        val text = if (undecodable || !isLikelyText(decoded)) {
            "（二进制或无法解码内容" + (if (ce.isNotEmpty()) "：$ce" else "") + "，${decoded.size} 字节）"
        } else {
            String(decoded, Charsets.UTF_8)
        }
        emit(
            JSONObject()
                .put("type", "flowRespBody")
                .put("flowId", flowId)
                .put("respBody", text)
                .put("truncated", truncated)
                .put("encoding", contentEncoding ?: ""),
        )
    }

    // Cheap binary sniff for the panel: NUL byte or a high fraction of C0 control bytes
    // (excluding tab/CR/LF) means it isn't displayable text (e.g. still-compressed bytes,
    // images). High bytes (UTF-8 multibyte, incl. CJK) are treated as text.
    private fun isLikelyText(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val n = minOf(data.size, 4096)
        var bad = 0
        for (i in 0 until n) {
            val b = data[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0B..0x0C) || (b in 0x0E..0x1F)) bad++
        }
        return bad * 100 / n < 5
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
