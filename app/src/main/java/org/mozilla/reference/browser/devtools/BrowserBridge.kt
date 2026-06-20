/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

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
            // Each request handled inline then closed; tool calls are fast/in-memory.
            try { handle(sock) } catch (_: Throwable) {} finally { try { sock.close() } catch (_: Throwable) {} }
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
                "Get the full record (request+response headers and bodies) for one captured flow by id.",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("id", strProp("Flow id from network_list.")))
                    .put("required", JSONArray().put("id")),
            ),
        )
        return arr
    }

    private fun handleToolCall(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
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
                else -> toolError(id, "unknown tool: $name")
            }
        } catch (t: Throwable) {
            toolError(id, "tool error: ${t.message}")
        }
    }

    // ── small builders ────────────────────────────────────────────────────────

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)

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
