/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BrowserBridge — local MCP server that exposes BrowserHelper's capabilities as
 * agent tools (BrowserHelper-Codex initiative, Phase 1).
 *
 * Architecture: the forked Codex (`bhcodex`) runs in Termux and connects here as
 * a streamable-HTTP MCP client:
 *   codex mcp add browserhelper --url http://127.0.0.1:<port>/mcp \
 *        --bearer-token-env-var BH_BRIDGE_TOKEN
 * The "tool layer" (real execution) lives entirely in the APK; the Rust side only
 * declares/routes. Bearer token gates the localhost socket so other local apps
 * cannot drive the agent (Termux and the app are different UIDs → no shared file,
 * so the token is shown in-app and pasted into the bhcodex env once).
 *
 * Phase 1 ships only L1 (read-only, non-sensitive) tools backed by NetFlowStore.
 * L2/L3 tools (page ops, intercept/modify, cookie/auth) and the native
 * confirmation dialog land in later phases — the permission gate will be enforced
 * HERE in the APK, never trusted from the model.
 *
 * Minimal hand-rolled HTTP/1.1 (same style as ProxyProbe): one request per
 * connection, Connection: close. Stateless MCP (no Mcp-Session-Id). Responds to
 * POST /mcp with a single JSON-RPC result as application/json; GET → 405 (no
 * server-initiated SSE stream needed for request/response tools).
 */
object BrowserBridge {
    private const val TAG = "BHBridge"
    private const val LOCALHOST = "127.0.0.1"
    private const val PREFS = "bh_devtools" // shared with ProxyProbe's REPLACE_PREFS bucket
    private const val PREF_TOKEN = "bridge_token"
    private val PORT_CANDIDATES = intArrayOf(8771, 8772, 8773, 8780)
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val SERVER_NAME = "browserhelper"
    private const val SERVER_VERSION = "0.1"
    private const val MAX_BODY = 1 * 1024 * 1024 // request body cap

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var boundPort = 0
    @Volatile private var token: String = ""
    @Volatile private var appCtx: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Run [block] on the main thread and wait (max 5s). For ProxyProbe.setEnabled. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        mainHandler.post { try { block() } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
    }

    fun isRunning(): Boolean = running
    fun port(): Int = boundPort
    fun token(): String = token

    /** The exact commands to register this server with bhcodex (for the UI to surface). */
    fun connectCommand(): String =
        "export BH_BRIDGE_TOKEN=$token\n" +
            "bhcodex mcp add browserhelper --url http://$LOCALHOST:$boundPort/mcp " +
            "--bearer-token-env-var BH_BRIDGE_TOKEN"

    @Synchronized
    fun start(context: Context) {
        if (running) return
        appCtx = context
        token = loadOrCreateToken(context)
        val srv = bindFirstFree() ?: run {
            Log.w(TAG, "no free port for bridge")
            return
        }
        server = srv
        boundPort = srv.localPort
        running = true
        Log.i(TAG, "bridge listening on $LOCALHOST:$boundPort")
        Thread({ acceptLoop(srv) }, "bh-bridge-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        boundPort = 0
    }

    private fun bindFirstFree(): ServerSocket? {
        val addr = InetAddress.getByName(LOCALHOST)
        for (p in PORT_CANDIDATES) {
            try {
                return ServerSocket(p, 16, addr)
            } catch (_: Throwable) {}
        }
        return try { ServerSocket(0, 16, addr) } catch (_: Throwable) { null }
    }

    private fun loadOrCreateToken(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(PREF_TOKEN, null)?.let { if (it.isNotEmpty()) return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val tok = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString(PREF_TOKEN, tok).apply()
        return tok
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            val sock = try { srv.accept() } catch (_: Throwable) { break }
            // One thread per connection: an L3 tool may block on a native confirm
            // dialog (up to 60s), so we must not stall the accept loop / other calls.
            Thread({
                try { handle(sock) } catch (_: Throwable) {} finally { try { sock.close() } catch (_: Throwable) {} }
            }, "bh-bridge-conn").apply { isDaemon = true }.start()
        }
    }

    // ── HTTP/1.1 (minimal) ────────────────────────────────────────────────────

    private fun handle(sock: Socket) {
        val ins = sock.getInputStream()
        val out = sock.getOutputStream()
        val head = readHead(ins) ?: return
        val lines = head.split("\r\n")
        val reqLine = lines.firstOrNull() ?: return
        val parts = reqLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        // Header map (lower-cased keys)
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }

        if (method == "GET") { // no SSE stream offered
            writeText(out, 405, "Method Not Allowed", "no server stream")
            return
        }
        if (method != "POST" || !path.startsWith("/mcp")) {
            writeText(out, 404, "Not Found", "")
            return
        }

        // Bearer auth — the real gate keeping other local apps out.
        val auth = headers["authorization"] ?: ""
        if (auth != "Bearer $token") {
            writeText(out, 401, "Unauthorized", "bad or missing bearer token")
            return
        }

        val clen = headers["content-length"]?.toIntOrNull() ?: 0
        if (clen > MAX_BODY) { writeText(out, 413, "Payload Too Large", ""); return }
        val body = readBody(ins, clen)
        val req = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Throwable) {
            writeJson(out, 200, jsonRpcError(null, -32700, "parse error")); return
        }
        val resp = dispatch(req)
        if (resp == null) {
            // Notification (no id) — ack with 202 and empty body per streamable HTTP.
            writeText(out, 202, "Accepted", "")
        } else {
            writeJson(out, 200, resp)
        }
    }

    private fun readHead(ins: InputStream): String? {
        val buf = StringBuilder()
        var last4 = 0
        while (true) {
            val b = ins.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.substring(0, buf.length - 4)
            if (buf.length > 64 * 1024) return null
        }
    }

    private fun readBody(ins: InputStream, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = ins.read(out, got, len - got)
            if (n < 0) break
            got += n
        }
        return if (got == len) out else out.copyOf(got)
    }

    private fun writeText(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeJson(out: OutputStream, code: Int, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code OK\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ── JSON-RPC / MCP ────────────────────────────────────────────────────────

    /** Returns the JSON-RPC response, or null for notifications (no id → 202 ack). */
    private fun dispatch(req: JSONObject): JSONObject? {
        val method = req.optString("method", "")
        val hasId = req.has("id") && !req.isNull("id")
        val id: Any? = if (hasId) req.opt("id") else null
        when (method) {
            "initialize" -> {
                val clientVer = req.optJSONObject("params")?.optString("protocolVersion", "") ?: ""
                return jsonRpcResult(
                    id,
                    JSONObject()
                        .put("protocolVersion", clientVer.ifEmpty { PROTOCOL_VERSION })
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION)),
                )
            }
            "notifications/initialized", "notifications/cancelled" -> return null
            "ping" -> return jsonRpcResult(id, JSONObject())
            "tools/list" -> return jsonRpcResult(id, JSONObject().put("tools", toolsList()))
            "tools/call" -> return handleToolCall(id, req.optJSONObject("params") ?: JSONObject())
            else -> {
                if (!hasId) return null
                return jsonRpcError(id, -32601, "method not found: $method")
            }
        }
    }

    private fun toolsList(): JSONArray {
        val arr = JSONArray()
        arr.put(
            tool(
                "network_list",
                "List recently captured HTTP(S) flows from the MITM proxy, newest first. " +
                    "Returns summaries (id, ts, method, url, host, status, contentType, body lengths). " +
                    "Use network_get with an id for full headers+bodies.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("method", strProp("Filter by HTTP method (e.g. POST). Empty = any."))
                            .put("urlContains", strProp("Only flows whose URL contains this substring."))
                            .put("sinceSeconds", JSONObject().put("type", "number").put("description", "Only flows newer than N seconds ago."))
                            .put("limit", JSONObject().put("type", "number").put("description", "Max rows (default 50).")),
                    ),
            ),
        )
        arr.put(
            tool(
                "network_get",
                "Get the full record (request+response headers and bodies) for one captured flow by id. " +
                    "Credential headers (Authorization, Cookie, Set-Cookie, x-api-key, ...) are MASKED in the " +
                    "output as ***redacted(N)***; use cookie_reveal to read a raw value (needs user approval).",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("id", strProp("Flow id from network_list.")))
                    .put("required", JSONArray().put("id")),
            ),
        )
        // ── Proxy control (L2) ───────────────────────────────────────────────
        arr.put(tool("proxy_status", "Check whether the MITM proxy is currently running.", emptySchema()))
        arr.put(tool("proxy_start", "Start the MITM proxy (captures all browser HTTPS traffic).", emptySchema()))
        arr.put(tool("proxy_stop", "Stop the MITM proxy.", emptySchema()))
        // ── Network conditioning (L2) ─────────────────────────────────────────
        arr.put(
            tool(
                "throttle_set",
                "Configure network throttling (weak-network simulation). Set enabled=false to disable.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("enabled", boolProp("Enable throttling."))
                        .put("latencyMs", numProp("Added latency per response in ms (0 = none)."))
                        .put("kbps", numProp("Downstream bandwidth limit in KB/s (0 = unlimited).")),
                ),
            ),
        )
        // ── Text replacement (L2) ─────────────────────────────────────────────
        arr.put(
            tool(
                "replace_set",
                "Configure text replacement rules applied to proxied request/response bodies. " +
                    "Rules are {from, to} pairs (substring replace). scope: req | resp | both.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("enabled", boolProp("Enable replacement."))
                        .put("scope", strProp("Which direction: req, resp, or both (default both)."))
                        .put(
                            "rules",
                            JSONObject().put("type", "array")
                                .put("description", "Array of {from:string, to:string} replacement pairs.")
                                .put("items", JSONObject().put("type", "object")
                                    .put("properties", JSONObject()
                                        .put("from", strProp("Text to search for."))
                                        .put("to", strProp("Replacement text.")))),
                        ),
                ),
            ),
        )
        // ── Mock rules (L2) ───────────────────────────────────────────────────
        arr.put(
            tool(
                "mock_set",
                "Set mock rules: URLs matching a pattern get a synthetic response (never reaches the server). " +
                    "Pattern is a case-insensitive substring of the full URL. Pass empty rules array to clear all mocks.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject().put(
                        "rules",
                        JSONObject().put("type", "array")
                            .put("description", "Array of mock rules.")
                            .put("items", JSONObject().put("type", "object")
                                .put("properties", JSONObject()
                                    .put("pattern", strProp("URL substring to match (case-insensitive)."))
                                    .put("status", numProp("HTTP status code (default 200)."))
                                    .put("body", strProp("Response body string."))
                                    .put("headers", JSONObject().put("type", "object")
                                        .put("description", "Extra response headers as {name:value}.")))),
                    ),
                ).put("required", JSONArray().put("rules")),
            ),
        )
        // ── Page content (L1) ─────────────────────────────────────────────────
        arr.put(
            tool(
                "page_index",
                "Index the current page: fetches outerHTML into a JS-side buffer (512KB cap) and " +
                    "returns metadata only (title, url, headings, forms, links). Call before page_search. " +
                    "Requires DevTools to be open on the target page.",
                emptySchema(),
            ),
        )
        arr.put(
            tool(
                "page_search",
                "Search the indexed page source for a text query. Returns snippets (±150 chars context). " +
                    "Call page_index first. Args: query (required), limit (default 10, max 50).",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("query", strProp("Text to search for (case-insensitive substring)."))
                        .put("limit", numProp("Max results (default 10)."))
                ).put("required", JSONArray().put("query")),
            ),
        )
        arr.put(
            tool(
                "page_query",
                "Run a CSS selector on the live DOM. Returns matched elements (tag, text, attrs, outerHTML snippet). " +
                    "Requires DevTools open. Args: selector (required), limit (default 20).",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("selector", strProp("CSS selector string."))
                        .put("limit", numProp("Max elements to return (default 20)."))
                ).put("required", JSONArray().put("selector")),
            ),
        )
        // ── Intercept (L2) ───────────────────────────────────────────────────
        arr.put(
            tool(
                "intercept_set",
                "Configure request/response intercept rules. Matching flows are PAUSED — " +
                    "call intercept_pending to see them, then intercept_resolve/resp_intercept_resolve to proceed. " +
                    "Set reqAll/respAll=true to intercept everything, or use rules for specific hosts/paths.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("reqAll", boolProp("Intercept ALL requests (default false)."))
                        .put("respAll", boolProp("Intercept ALL responses (default false)."))
                        .put(
                            "rules",
                            JSONObject().put("type", "array")
                                .put("description", "Specific rules: [{host, path, method, hasBody, interceptResp}].")
                                .put("items", JSONObject().put("type", "object")
                                    .put("properties", JSONObject()
                                        .put("host", strProp("Exact host (e.g. api.example.com)."))
                                        .put("path", strProp("Exact path (no query, e.g. /v1/chat)."))
                                        .put("method", strProp("HTTP method (GET/POST/…)."))
                                        .put("hasBody", boolProp("Match only requests with a body."))
                                        .put("interceptResp", boolProp("Also intercept the response for this rule.")))),
                        ),
                ),
            ),
        )
        arr.put(
            tool(
                "intercept_pending",
                "List flows currently paused waiting for an intercept decision. " +
                    "Returns [{flowId, type:req|resp, url, method, ts}]. Poll this after intercept_set.",
                emptySchema(),
            ),
        )
        arr.put(
            tool(
                "intercept_resolve",
                "Resolve a paused REQUEST intercept. decision: continue (forward, optionally with edits) or abort (return 403). " +
                    "Header values support PLACEHOLDER SUBSTITUTION: use {{cookie:<flowId>}} or {{auth:<flowId>}} " +
                    "or {{header:<flowId>:<headerName>}} — the APK replaces these with the real credential value " +
                    "BEFORE forwarding, so the real secret never enters the model context.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("flowId", strProp("Flow id from intercept_pending."))
                        .put("decision", strProp("continue or abort."))
                        .put("url", strProp("Modified URL (optional, continue only)."))
                        .put("method", strProp("Modified method (optional, continue only)."))
                        .put("reqHeaders", JSONObject().put("type", "object").put("description",
                            "Modified request headers. Values may contain {{cookie:id}}/{{auth:id}}/{{header:id:name}} placeholders."))
                        .put("reqBody", strProp("Modified request body (optional, continue only)."))
                ).put("required", JSONArray().put("flowId").put("decision")),
            ),
        )
        arr.put(
            tool(
                "resp_intercept_resolve",
                "Resolve a paused RESPONSE intercept. Header values support the same placeholder substitution as intercept_resolve.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("flowId", strProp("Flow id from intercept_pending."))
                        .put("decision", strProp("continue or abort."))
                        .put("status", numProp("Modified HTTP status (optional, continue only)."))
                        .put("respHeaders", JSONObject().put("type", "object").put("description",
                            "Modified response headers (with optional placeholders)."))
                        .put("respBody", strProp("Modified response body (optional, continue only)."))
                ).put("required", JSONArray().put("flowId").put("decision")),
            ),
        )
        // ── Page execution (L3) ───────────────────────────────────────────────
        arr.put(
            tool(
                "cookie_reveal",
                "[SENSITIVE / L3] Reveal the RAW (un-redacted) value of one credential header for a captured " +
                    "flow (e.g. the real Authorization bearer or Cookie). Each call pops a NATIVE confirmation " +
                    "dialog in the app — the value is only returned if the human taps Allow. Use sparingly.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", strProp("Flow id from network_list."))
                            .put("name", strProp("Header name to reveal (default: authorization). e.g. cookie, set-cookie.")),
                    )
                    .put("required", JSONArray().put("id")),
            ),
        )
        arr.put(
            tool(
                "page_exec",
                "[SENSITIVE / L3] Execute arbitrary JavaScript in the page world (full browser API access: " +
                    "fetch, document.cookie, window.*, history, localStorage, …). Uses Gecko wrappedJSObject — " +
                    "no CSP restriction. Return value is JSON-serialized. Requires native confirmation per call.",
                JSONObject().put("type", "object").put(
                    "properties", JSONObject()
                        .put("code", strProp("JS code to run. Last expression is the return value (use return statement)."))
                        .put("timeoutMs", numProp("Eval timeout ms (default 10000)."))
                ).put("required", JSONArray().put("code")),
            ),
        )
        return arr
    }

    // L3 = sensitive tools that MUST pass a native human confirmation before they
    // run. The gate is enforced HERE in the APK; bhcodex can never bypass it.
    private val L3_TOOLS = setOf("cookie_reveal", "page_exec")

    private fun confirmSummary(name: String, args: JSONObject): String = when (name) {
        "cookie_reveal" ->
            "Agent(bhcodex) 请求读取 flow ${args.optString("id")} 的真实「${args.optString("name", "authorization")}」" +
                "头(含会话令牌/Cookie)。允许后明文会发送给 Termux 侧的 bhcodex。是否允许?"
        "page_exec" -> {
            val snippet = args.optString("code", "").take(120)
            "Agent(bhcodex) 请求在当前页面执行 JS(可访问 cookie/fetch/localStorage 等全部 API)：\n\n$snippet\n\n是否允许?"
        }
        else -> "Agent(bhcodex) 请求执行敏感操作「$name」。是否允许?"
    }

    private fun handleToolCall(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        if (L3_TOOLS.contains(name)) {
            val ok = AgentConfirm.requireConfirm("Agent 敏感操作确认", confirmSummary(name, args))
            if (!ok) return toolError(id, "用户拒绝或确认超时 / 无前台界面 (L3 需原生确认)")
        }
        return try {
            when (name) {
                "network_list" -> {
                    val sinceSec = args.optDouble("sinceSeconds", 0.0)
                    val sinceMs = if (sinceSec > 0) System.currentTimeMillis() - (sinceSec * 1000).toLong() else 0L
                    val rows = NetFlowStore.listJson(
                        method = args.optString("method", ""),
                        urlContains = args.optString("urlContains", ""),
                        sinceMs = sinceMs,
                        limit = args.optInt("limit", 50),
                    )
                    toolText(id, rows.toString())
                }
                "network_get" -> {
                    val flowId = args.optString("id", "")
                    val rec = NetFlowStore.getJson(flowId)
                    if (rec == null) toolError(id, "no flow with id=$flowId") else toolText(id, rec.toString())
                }
                "cookie_reveal" -> {
                    val flowId = args.optString("id", "")
                    val header = args.optString("name", "authorization")
                    val v = NetFlowStore.revealHeader(flowId, header)
                    if (v == null) toolError(id, "flow $flowId 无「$header」头") else toolText(id, v)
                }
                // ── Proxy control ────────────────────────────────────────────
                "proxy_status" -> toolText(id, if (ProxyProbe.isRunning()) "running" else "stopped")
                "proxy_start" -> {
                    val ctx = appCtx ?: return toolError(id, "no context")
                    onMain { ProxyProbe.setEnabled(ctx, true) }
                    toolText(id, if (ProxyProbe.isRunning()) "proxy started" else "proxy start requested")
                }
                "proxy_stop" -> {
                    val ctx = appCtx ?: return toolError(id, "no context")
                    onMain { ProxyProbe.setEnabled(ctx, false) }
                    toolText(id, "proxy stopped")
                }
                // ── Throttle ─────────────────────────────────────────────────
                "throttle_set" -> {
                    val data = JSONObject()
                        .put("throttleEnabled", args.optBoolean("enabled", false))
                        .put("latencyMs", args.optLong("latencyMs", 0L))
                        .put("kbps", args.optLong("kbps", 0L))
                    ProxyProbe.setThrottle(data)
                    val enabled = args.optBoolean("enabled", false)
                    toolText(id, if (enabled) "throttle: ${args.optLong("latencyMs")}ms latency, ${args.optLong("kbps")} kbps" else "throttle disabled")
                }
                // ── Replace ───────────────────────────────────────────────────
                "replace_set" -> {
                    val enabled = args.optBoolean("enabled", false)
                    val scope = args.optString("scope", "both")
                    val rulesArr = args.optJSONArray("rules")
                    val rules = ArrayList<Pair<String, String>>()
                    if (rulesArr != null) {
                        for (i in 0 until rulesArr.length()) {
                            val o = rulesArr.optJSONObject(i) ?: continue
                            val from = o.optString("from", "")
                            if (from.isNotEmpty()) rules.add(from to o.optString("to", ""))
                        }
                    }
                    ProxyProbe.setReplaceRules(enabled, scope, rules)
                    toolText(id, "replace ${if (enabled) "enabled" else "disabled"}: ${rules.size} rules, scope=$scope")
                }
                // ── Mock ──────────────────────────────────────────────────────
                "mock_set" -> {
                    val rulesArr = args.optJSONArray("rules") ?: JSONArray()
                    ProxyProbe.setMockRules(JSONObject().put("rules", rulesArr))
                    toolText(id, "mock rules set: ${rulesArr.length()} rules")
                }
                // ── Intercept ─────────────────────────────────────────────────
                "intercept_set" -> {
                    ProxyProbe.setInterceptRules(
                        JSONObject()
                            .put("reqAll", args.optBoolean("reqAll", false))
                            .put("respAll", args.optBoolean("respAll", false))
                            .put("interceptTelemetry", false)
                            .put("interceptNoise", false)
                            .put("interceptCookie", false)
                            .put("rules", args.optJSONArray("rules") ?: JSONArray()),
                    )
                    toolText(id, "intercept rules set (reqAll=${args.optBoolean("reqAll")}, respAll=${args.optBoolean("respAll")})")
                }
                "intercept_pending" -> toolText(id, ProxyProbe.pendingInterceptList().toString())
                "intercept_resolve" -> {
                    val flowId = args.optString("flowId", "")
                    if (flowId.isEmpty()) return toolError(id, "flowId required")
                    val decision = JSONObject().put("decision", args.optString("decision", "continue"))
                    // Only carry edited fields; absence = keep original. headEdited makes
                    // ProxyProbe rebuild the request line/headers (else forwarded verbatim).
                    var headEdited = false
                    if (args.has("url")) { decision.put("url", args.optString("url", "")); headEdited = true }
                    if (args.has("method")) { decision.put("method", args.optString("method", "")); headEdited = true }
                    if (args.has("reqHeaders")) {
                        decision.put("reqHeaders", resolveHeaderPlaceholders(args.optJSONObject("reqHeaders")))
                        headEdited = true
                    }
                    if (headEdited) decision.put("headEdited", true)
                    if (args.has("reqBody")) decision.put("reqBody", resolvePlaceholders(args.optString("reqBody", "")))
                    ProxyProbe.resolveIntercept(flowId, decision)
                    toolText(id, "intercept resolved: flowId=$flowId decision=${decision.optString("decision")}")
                }
                "resp_intercept_resolve" -> {
                    val flowId = args.optString("flowId", "")
                    if (flowId.isEmpty()) return toolError(id, "flowId required")
                    val decision = JSONObject().put("decision", args.optString("decision", "continue"))
                    var headEdited = false
                    if (args.has("status")) { decision.put("status", args.optInt("status", 0)); headEdited = true }
                    if (args.has("respHeaders")) {
                        decision.put("respHeaders", resolveHeaderPlaceholders(args.optJSONObject("respHeaders")))
                        headEdited = true
                    }
                    if (headEdited) decision.put("headEdited", true)
                    if (args.has("respBody")) decision.put("respBody", resolvePlaceholders(args.optString("respBody", "")))
                    ProxyProbe.resolveRespIntercept(flowId, decision)
                    toolText(id, "resp intercept resolved: flowId=$flowId decision=${decision.optString("decision")}")
                }
                // ── Page tools ────────────────────────────────────────────────
                "page_index" -> {
                    val res = PageChannel.exec("getSource")
                    if (res.has("error")) toolError(id, res.optString("error")) else toolText(id, res.toString())
                }
                "page_search" -> {
                    val query = args.optString("query", "")
                    if (query.isEmpty()) return toolError(id, "query required")
                    val res = PageChannel.exec("searchSource", JSONObject().put("query", query).put("limit", args.optInt("limit", 10)))
                    if (res.has("error")) toolError(id, res.optString("error")) else toolText(id, res.toString())
                }
                "page_query" -> {
                    val sel = args.optString("selector", "")
                    if (sel.isEmpty()) return toolError(id, "selector required")
                    val res = PageChannel.exec("queryDOM", JSONObject().put("selector", sel).put("limit", args.optInt("limit", 20)))
                    if (res.has("error")) toolError(id, res.optString("error")) else toolText(id, res.toString())
                }
                "page_exec" -> {
                    val code = args.optString("code", "")
                    if (code.isEmpty()) return toolError(id, "code required")
                    val res = PageChannel.exec("evalJS", JSONObject().put("code", code).put("timeoutMs", args.optInt("timeoutMs", 10000)), 15_000)
                    if (res.has("error")) toolError(id, res.optString("error")) else toolText(id, res.toString())
                }
                else -> toolError(id, "unknown tool: $name")
            }
        } catch (t: Throwable) {
            toolError(id, "tool error: ${t.message}")
        }
    }

    // ── small builders ────────────────────────────────────────────────────────

    /**
     * Resolve credential placeholders in [value] before sending to ProxyProbe.
     * Format: {{cookie:<flowId>}}, {{auth:<flowId>}}, {{header:<flowId>:<headerName>}}
     * The real credential is pulled from NetFlowStore (un-redacted internal store)
     * and substituted HERE in the APK — the model never sees the real value.
     */
    private val PLACEHOLDER_RE = Regex("""\{\{(\w+):(\w+)(?::([^}]+))?\}\}""")
    private fun resolvePlaceholders(value: String): String = PLACEHOLDER_RE.replace(value) { m ->
        val type = m.groupValues[1].lowercase()
        val flowId = m.groupValues[2]
        val extra = m.groupValues[3].ifEmpty { null }
        val headerName = when (type) {
            "cookie" -> "cookie"
            "auth", "authorization" -> "authorization"
            "header" -> extra ?: return@replace m.value
            else -> return@replace m.value
        }
        NetFlowStore.revealHeader(flowId, headerName) ?: m.value
    }

    private fun resolveHeaderPlaceholders(headers: JSONObject?): JSONObject {
        val out = JSONObject()
        if (headers == null) return out
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, resolvePlaceholders(headers.optString(k, "")))
        }
        return out
    }

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun numProp(desc: String) = JSONObject().put("type", "number").put("description", desc)
    private fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

    private fun tool(name: String, desc: String, schema: JSONObject) =
        JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)

    private fun jsonRpcResult(id: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun jsonRpcError(id: Any?, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    /** MCP tools/call success → result.content = [{type:text,text}]. */
    private fun toolText(id: Any?, text: String) = jsonRpcResult(
        id,
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
    )

    /** MCP tools/call failure → result with isError=true (NOT a protocol error). */
    private fun toolError(id: Any?, text: String) = jsonRpcResult(
        id,
        JSONObject()
            .put("isError", true)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
    )
}
