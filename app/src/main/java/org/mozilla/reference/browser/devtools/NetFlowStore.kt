/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

/**
 * Native-side, bounded, queryable store of captured proxy flows.
 *
 * The MITM pump (ProxyProbe) only ever STREAMS flow events out via `emit()` to
 * the DevTools panel — nothing was retained natively, so an agent could not ask
 * "list the recent requests". This store is a pure in-memory TEE fed from the
 * exact same `emit()` events (flowReq / flowResp / flowReqBody / flowRespBody),
 * keyed by flowId. It is the data source for the `browser.network.*` agent tools
 * (BrowserHelper-Codex initiative).
 *
 * SAFETY (CLAUDE.md 坑#4): this records already-emitted DISPLAY data in memory.
 * It never touches byte forwarding, never buffers the live stream, never blocks
 * the pump. Bodies here are the bounded, decoded snapshots the panel already got.
 */
object NetFlowStore {
    private const val CAP = 500 // most-recent flows retained (ring buffer)

    private class Record(val flowId: String) {
        val code: String = nextCode()
        var ts: Long = System.currentTimeMillis()
        var respTs: Long = 0L
        var method: String = ""
        var url: String = ""
        var host: String = ""
        var status: Int = 0
        var reqHeaderBytes: Long = 0L
        var respHeaderBytes: Long = 0L
        var reqBodyBytes: Long = 0L
        var respBodyBytes: Long = 0L
        var reqHeaders: JSONObject? = null
        var respHeaders: JSONObject? = null
        var reqBody: String? = null
        var reqTruncated: Boolean = false
        var respBody: String? = null
        var respTruncated: Boolean = false
        var encoding: String = ""
    }

    // Insertion-ordered, capped. Eldest evicted once over CAP. Guarded by `lock`.
    private val lock = Any()
    private val records = object : LinkedHashMap<String, Record>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Record>): Boolean =
            size > CAP
    }

    private fun nextCode(): String {
        repeat(24) {
            val code = ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
            if (records.values.none { it.code == code }) return code
        }
        return ThreadLocalRandom.current().nextInt(100000, 1000000).toString()
    }

    /** Tee point: called from ProxyProbe.emit() for every proxy flow event. */
    fun record(obj: JSONObject) {
        val type = obj.optString("type")
        val flowId = obj.optString("flowId")
        if (flowId.isEmpty()) return
        synchronized(lock) {
            val r = records.getOrPut(flowId) { Record(flowId) }
            when (type) {
                "flowReq" -> {
                    r.method = obj.optString("method", r.method)
                    r.url = obj.optString("url", r.url)
                    r.host = obj.optString("host", r.host)
                    if (obj.has("ts")) r.ts = obj.optLong("ts", r.ts)
                    r.reqHeaderBytes = obj.optLong("reqHeaderBytes", r.reqHeaderBytes)
                    val headerBody = contentLength(obj.optJSONObject("reqHeaders"))
                    r.reqBodyBytes = obj.optLong("reqBodyBytes", if (headerBody >= 0) headerBody else r.reqBodyBytes)
                    obj.optJSONObject("reqHeaders")?.let { r.reqHeaders = it }
                }
                "flowResp" -> {
                    r.status = obj.optInt("status", r.status)
                    r.respTs = obj.optLong("ts", System.currentTimeMillis())
                    r.respHeaderBytes = obj.optLong("respHeaderBytes", r.respHeaderBytes)
                    val headerBody = contentLength(obj.optJSONObject("respHeaders"))
                    r.respBodyBytes = obj.optLong("respBodyBytes", if (headerBody >= 0) headerBody else r.respBodyBytes)
                    obj.optJSONObject("respHeaders")?.let { r.respHeaders = it }
                }
                "flowReqBody" -> {
                    r.reqBody = obj.optString("reqBody", r.reqBody ?: "")
                    r.reqBodyBytes = obj.optLong("reqBodyBytes", r.reqBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: r.reqBodyBytes)
                    r.reqTruncated = obj.optBoolean("truncated", r.reqTruncated)
                }
                "flowRespBody" -> {
                    r.respBody = obj.optString("respBody", r.respBody ?: "")
                    r.respBodyBytes = obj.optLong("respBodyBytes", r.respBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: r.respBodyBytes)
                    r.respTruncated = obj.optBoolean("truncated", r.respTruncated)
                    r.encoding = obj.optString("encoding", r.encoding)
                }
                "reqIntercept" -> {
                    r.method = obj.optString("method", r.method)
                    r.url = obj.optString("url", r.url)
                    r.host = hostFromUrl(r.url).ifBlank { r.host }
                    if (obj.has("ts")) r.ts = obj.optLong("ts", r.ts)
                    r.reqHeaderBytes = obj.optLong("reqHeaderBytes", r.reqHeaderBytes)
                    obj.optJSONObject("reqHeaders")?.let { r.reqHeaders = it }
                    r.reqBody = obj.optString("reqBody", r.reqBody ?: "")
                    r.reqBodyBytes = obj.optLong("reqBodyBytes", r.reqBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: r.reqBodyBytes)
                }
                "respIntercept" -> {
                    r.status = obj.optInt("status", r.status)
                    r.respTs = obj.optLong("ts", System.currentTimeMillis())
                    r.respHeaderBytes = obj.optLong("respHeaderBytes", r.respHeaderBytes)
                    obj.optJSONObject("respHeaders")?.let { r.respHeaders = it }
                    r.respBody = obj.optString("respBody", r.respBody ?: "")
                    r.respBodyBytes = obj.optLong("respBodyBytes", r.respBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: r.respBodyBytes)
                }
                else -> {}
            }
        }
    }

    fun clear() {
        synchronized(lock) { records.clear() }
    }

    private fun headerValue(headers: JSONObject?, name: String): String {
        if (headers == null) return ""
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(name, ignoreCase = true)) return headers.optString(k, "")
        }
        return ""
    }

    private fun contentLength(headers: JSONObject?): Long =
        headerValue(headers, "content-length").toLongOrNull() ?: -1L

    private fun hostFromUrl(url: String): String =
        try {
            java.net.URI(url).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }

    // Header names whose VALUE is a credential. Default L1 tool output masks these
    // so a (possibly prompt-injected) model never sees raw session tokens. Per the
    // decided bhcodex architecture, cookie/token redaction happens in the APK
    // BEFORE bytes leave to bhcodex. A future L3 "reveal" tool (native confirm)
    // can expose the raw value on explicit user tap.
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-auth-token",
        "x-api-key",
        "x-csrf-token",
        "x-xsrf-token",
        "openai-organization",
    )

    /**
     * Return a copy of [headers] with credential-bearing values masked. The mask
     * keeps the scheme prefix (e.g. "Bearer") and the original length as a hint so
     * the agent knows the header exists without reading the secret.
     */
    private fun redactHeaders(headers: JSONObject?): JSONObject {
        val out = JSONObject()
        if (headers == null) return out
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = headers.optString(k, "")
            if (SENSITIVE_HEADERS.contains(k.lowercase())) {
                out.put(k, maskValue(v))
            } else {
                out.put(k, v)
            }
        }
        return out
    }

    private fun maskValue(v: String): String {
        if (v.isEmpty()) return v
        val sp = v.indexOf(' ')
        // Preserve an auth scheme like "Bearer " / "Basic " if present.
        val scheme = if (sp in 1..10 && v.substring(0, sp).all { it.isLetter() }) v.substring(0, sp + 1) else ""
        return "${scheme}***redacted(${v.length})***"
    }

    private fun summary(r: Record): JSONObject = JSONObject()
        .put("id", r.code)
        .put("code", r.code)
        .put("flowId", r.flowId)
        .put("ts", r.ts)
        .put("method", r.method)
        .put("url", r.url)
        .put("host", r.host)
        .put("status", r.status)
        .put("contentType", headerValue(r.respHeaders, "content-type"))
        .put("reqBytes", requestBytes(r))
        .put("respBytes", responseBytes(r))
        .put("latencyMs", latencyMs(r))
        .put("reqBodyBytes", r.reqBodyBytes)
        .put("respBodyBytes", r.respBodyBytes)

    /**
     * Recent flows newest-first, after optional filters.
     * @param method  case-insensitive exact match (empty = any)
     * @param urlContains substring match on url (empty = any)
     * @param sinceMs  only flows with ts >= sinceMs (0 = any)
     * @param limit  max rows (<=0 → 10)
     */
    fun listJson(method: String = "", urlContains: String = "", sinceMs: Long = 0, limit: Int = 10): JSONArray {
        val out = JSONArray()
        val cap = if (limit <= 0) 10 else limit.coerceAtMost(100)
        synchronized(lock) {
            // newest-first: iterate insertion order in reverse
            val all = records.values.toList()
            for (i in all.indices.reversed()) {
                if (out.length() >= cap) break
                val r = all[i]
                if (method.isNotEmpty() && !r.method.equals(method, ignoreCase = true)) continue
                if (urlContains.isNotEmpty() && !r.url.contains(urlContains, ignoreCase = true)) continue
                if (sinceMs > 0 && r.ts < sinceMs) continue
                out.put(summary(r))
            }
        }
        return out
    }

    /**
     * Raw (UN-redacted) value of a single header for one flow — searched in request
     * headers first, then response headers. For the L3 `cookie_reveal` tool, which
     * is gated behind a native confirmation dialog (AgentConfirm). Returns null if
     * the flow or header is absent.
     */
    fun revealHeader(flowId: String, name: String): String? {
        synchronized(lock) {
            val r = resolveRecord(flowId) ?: return null
            val fromReq = headerValue(r.reqHeaders, name)
            if (fromReq.isNotEmpty()) return fromReq
            val fromResp = headerValue(r.respHeaders, name)
            if (fromResp.isNotEmpty()) return fromResp
            return null
        }
    }

    fun codeFor(flowId: String): String? = synchronized(lock) { records[flowId]?.code }

    fun flowIdFor(idOrCode: String): String? = synchronized(lock) { resolveRecord(idOrCode)?.flowId }

    /** Selected record data for one flow code/flowId, or null if unknown. */
    fun getJson(flowId: String, part: String = "all"): JSONObject? {
        synchronized(lock) {
            val r = resolveRecord(flowId) ?: return null
            val out = JSONObject()
                .put("id", r.code)
                .put("code", r.code)
                .put("flowId", r.flowId)
                .put("ts", r.ts)
                .put("method", r.method)
                .put("url", r.url)
                .put("host", r.host)
                .put("status", r.status)
                .put("contentType", headerValue(r.respHeaders, "content-type"))
                .put("reqBytes", requestBytes(r))
                .put("respBytes", responseBytes(r))
                .put("latencyMs", latencyMs(r))
            when (part.lowercase()) {
                "summary", "overview" -> {}
                "requestheaders", "reqheaders", "headers", "request_headers" ->
                    out.put("reqHeaders", redactHeaders(r.reqHeaders))
                "requestbody", "reqbody", "request_body" -> out
                    .put("reqBody", r.reqBody ?: "")
                    .put("reqBodyBytes", r.reqBodyBytes)
                    .put("reqTruncated", r.reqTruncated)
                "responseheaders", "respheaders", "response_headers" ->
                    out.put("respHeaders", redactHeaders(r.respHeaders))
                "responsebody", "respbody", "response_body" -> out
                    .put("respBody", r.respBody ?: "")
                    .put("respBodyBytes", r.respBodyBytes)
                    .put("respTruncated", r.respTruncated)
                    .put("encoding", r.encoding)
                else -> out
                    .put("reqHeaders", redactHeaders(r.reqHeaders))
                    .put("respHeaders", redactHeaders(r.respHeaders))
                    .put("reqBody", r.reqBody ?: "")
                    .put("reqBodyBytes", r.reqBodyBytes)
                    .put("reqTruncated", r.reqTruncated)
                    .put("respBody", r.respBody ?: "")
                    .put("respBodyBytes", r.respBodyBytes)
                    .put("respTruncated", r.respTruncated)
                    .put("encoding", r.encoding)
            }
            return out
        }
    }

    private fun resolveRecord(idOrCode: String): Record? =
        records[idOrCode] ?: records.values.firstOrNull { it.code == idOrCode }

    private fun requestBytes(r: Record): Long = r.reqHeaderBytes + r.reqBodyBytes

    private fun responseBytes(r: Record): Long = r.respHeaderBytes + r.respBodyBytes

    private fun latencyMs(r: Record): Long =
        if (r.respTs > 0L && r.ts > 0L) (r.respTs - r.ts).coerceAtLeast(0L) else -1L
}
