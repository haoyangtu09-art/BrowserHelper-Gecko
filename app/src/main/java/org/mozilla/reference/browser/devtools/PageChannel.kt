/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.devtools

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Async bridge between BrowserBridge (worker threads, blocking) and the active
 * page's content script (main thread, via DevToolsHelper port).
 *
 * Usage:
 *   1. DevToolsHelper.install() calls PageChannel.setSender { obj -> emitToPanel(obj) }.
 *   2. BrowserBridge tool handler calls PageChannel.exec(cmd, args, timeoutMs) which
 *      blocks its worker thread until the content script responds or times out.
 *   3. Content script dispatches {action:"agentResp", requestId, result{}} back via
 *      port.postMessage → DevToolsHelper.onPortMessage → PageChannel.resolve().
 *
 * Fail-safe: timeout returns an {error:...} object; the tool handler turns this into
 * a toolError. No socket is ever left hanging.
 */
object PageChannel {
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()

    @Volatile private var sender: ((JSONObject) -> Unit)? = null

    /** Called by DevToolsHelper once a panel port is ready. */
    fun setSender(fn: (JSONObject) -> Unit) { sender = fn }

    /** Clear sender when no port is active (tab changed / extension reloaded). */
    fun clearSender() { sender = null }

    /**
     * Send [cmd] to the content script and block until a response arrives or
     * [timeoutMs] elapses. Always returns a JSONObject; check for key "error".
     *
     * Must NOT be called on the main thread (blocks).
     */
    fun exec(cmd: String, args: JSONObject = JSONObject(), timeoutMs: Long = 15_000): JSONObject {
        val s = sender
            ?: return JSONObject().put("error", "no active page (open DevTools on the target page first)")
        val reqId = "ag${seq.incrementAndGet()}_${System.currentTimeMillis()}"
        val future = CompletableFuture<JSONObject>()
        pending[reqId] = future
        try {
            s.invoke(
                JSONObject()
                    .put("action", "agentCmd")
                    .put("requestId", reqId)
                    .put("cmd", cmd)
                    .put("args", args),
            )
        } catch (t: Throwable) {
            pending.remove(reqId)
            return JSONObject().put("error", "send failed: ${t.message}")
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(reqId)
            JSONObject().put("error", "timeout after ${timeoutMs}ms — is DevTools open on the target page?")
        } catch (t: Throwable) {
            pending.remove(reqId)
            JSONObject().put("error", "channel error: ${t.message}")
        }
    }

    /** Called by DevToolsHelper when it receives an agentResp port message. */
    fun resolve(reqId: String, result: JSONObject) {
        pending.remove(reqId)?.complete(result)
    }
}
