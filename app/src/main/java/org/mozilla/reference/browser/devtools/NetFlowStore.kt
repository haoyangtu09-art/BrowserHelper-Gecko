/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import org.json.JSONArray
import org.json.JSONObject

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
        var ts: Long = System.currentTimeMillis()
        var method: String = ""
        var url: String = ""
        var host: String = ""
        var status: Int = 0
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
                    obj.optJSONObject("reqHeaders")?.let { r.reqHeaders = it }
                }
                "flowResp" -> {
                    r.status = obj.optInt("status", r.status)
                    obj.optJSONObject("respHeaders")?.let { r.respHeaders = it }
                }
                "flowReqBody" -> {
                    r.reqBody = obj.optString("reqBody", r.reqBody ?: "")
                    r.reqTruncated = obj.optBoolean("truncated", r.reqTruncated)
                }
                "flowRespBody" -> {
                    r.respBody = obj.optString("respBody", r.respBody ?: "")
                    r.respTruncated = obj.optBoolean("truncated", r.respTruncated)
                    r.encoding = obj.optString("encoding", r.encoding)
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
        .put("id", r.flowId)
        .put("ts", r.ts)
        .put("method", r.method)
        .put("url", r.url)
        .put("host", r.host)
        .put("status", r.status)
        .put("contentType", headerValue(r.respHeaders, "content-type"))
        .put("reqBodyLen", r.reqBody?.length ?: 0)
        .put("respBodyLen", r.respBody?.length ?: 0)

    /**
     * Recent flows newest-first, after optional filters.
     * @param method  case-insensitive exact match (empty = any)
     * @param urlContains substring match on url (empty = any)
     * @param sinceMs  only flows with ts >= sinceMs (0 = any)
     * @param limit  max rows (<=0 → 50)
     */
    fun listJson(method: String = "", urlContains: String = "", sinceMs: Long = 0, limit: Int = 50): JSONArray {
        val out = JSONArray()
        val cap = if (limit <= 0) 50 else limit
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
            val r = records[flowId] ?: return null
            val fromReq = headerValue(r.reqHeaders, name)
            if (fromReq.isNotEmpty()) return fromReq
            val fromResp = headerValue(r.respHeaders, name)
            if (fromResp.isNotEmpty()) return fromResp
            return null
        }
    }

    /** Full record (headers + bodies) for one flow, or null if unknown. */
    fun getJson(flowId: String): JSONObject? {
        synchronized(lock) {
            val r = records[flowId] ?: return null
            return JSONObject()
                .put("id", r.flowId)
                .put("ts", r.ts)
                .put("method", r.method)
                .put("url", r.url)
                .put("host", r.host)
                .put("status", r.status)
                .put("reqHeaders", redactHeaders(r.reqHeaders))
                .put("respHeaders", redactHeaders(r.respHeaders))
                .put("reqBody", r.reqBody ?: "")
                .put("reqTruncated", r.reqTruncated)
                .put("respBody", r.respBody ?: "")
                .put("respTruncated", r.respTruncated)
                .put("encoding", r.encoding)
        }
    }
}
