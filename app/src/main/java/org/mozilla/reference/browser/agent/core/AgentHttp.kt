/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Endpoint normalization. Users may configure the base URL loosely; we always resolve a
 * `.../v1` base and append the provider-specific path ourselves. A full endpoint pasted in
 * (`.../chat/completions` or `.../messages`) is stripped back to its base first.
 */
internal object Endpoints {
    // A path segment like "v1", "v2", "v1beta", "v1alpha" — a real API version prefix.
    private val versionSegment = Regex("^v\\d+[a-z0-9]*$", RegexOption.IGNORE_CASE)

    private fun base(raw: String, default: String): String {
        var b = raw.trim().ifEmpty { default }.trimEnd('/')
        b = b.removeSuffix("/chat/completions").removeSuffix("/messages").trimEnd('/')
        if (!hasVersionSegment(b)) b += "/v1"
        return b
    }

    // Only treat the URL as already-versioned when a *path segment* is a version (e.g. ".../v1"
    // or ".../v1beta"); a host that merely contains "v1" (e.g. "v1.example.com") must not count.
    private fun hasVersionSegment(url: String): Boolean {
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            ""
        }
        return path.split('/').any { versionSegment.matches(it) }
    }

    fun openAiBase(raw: String) = base(raw, "https://api.openai.com/v1")
    fun anthropicBase(raw: String) = base(raw, "https://api.anthropic.com/v1")
}

/** Plain GET returning the body text. Non-2xx throws [AgentHttpException]. */
internal suspend fun getText(
    endpoint: String,
    headers: Map<String, String>,
): String = withContext(Dispatchers.IO) {
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 20_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw AgentHttpException(code, text)
        text
    } finally {
        conn.disconnect()
    }
}

/**
 * Streaming JSON POST over Server-Sent Events. Each `data:` payload (minus the prefix) is
 * handed to [onData] as it arrives; the terminal `[DONE]` sentinel stops the stream. Non-2xx
 * throws [AgentHttpException] with the (truncated) error body. The body must request streaming
 * (e.g. `"stream": true`) for the server to actually emit SSE.
 */
internal suspend fun postSse(
    endpoint: String,
    headers: Map<String, String>,
    body: String,
    onData: (String) -> Unit,
) = withContext(Dispatchers.IO) {
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "text/event-stream")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw AgentHttpException(code, text)
        }
        conn.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.startsWith("data:")) {
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") return@use
                    if (payload.isNotEmpty()) onData(payload)
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}

/** Plain JSON POST returning the response object. Non-2xx throws [AgentHttpException]. */
internal suspend fun postJson(
    endpoint: String,
    headers: Map<String, String>,
    body: String,
): JSONObject = withContext(Dispatchers.IO) {
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw AgentHttpException(code, text)
        JSONObject(text)
    } finally {
        conn.disconnect()
    }
}
