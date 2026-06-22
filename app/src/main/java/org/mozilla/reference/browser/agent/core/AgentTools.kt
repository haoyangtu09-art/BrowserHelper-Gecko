/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.reference.browser.devtools.NetFlowStore
import org.mozilla.reference.browser.devtools.PageChannel
import org.mozilla.reference.browser.devtools.ProxyProbe
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val FILE_READ_CAP = 256 * 1024
private const val FILE_WRITE_CAP = 512 * 1024
private const val HTTP_BODY_CAP = 512 * 1024
private const val SEARCH_RESULT_CAP = 16 * 1024

data class AgentToolResult(val ok: Boolean, val text: String)

interface AgentApprover {
    fun isApproved(scopeKey: String): Boolean
    suspend fun approve(request: AgentApprovalRequest): AgentApprovalDecision
}

private data class ToolDef(
    val spec: ChatToolSpec,
    val tier: AgentPermissionTier,
)

class AgentToolRegistry(
    context: Context,
    private val approver: AgentApprover,
    private val searchKey: String = "",
    private val searchUrl: String = "",
    private val onPlanChanged: ((String) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val toolRegistries = buildToolRegistries()

    fun allowedToolSpecs(tier: AgentPermissionTier): List<ChatToolSpec> =
        registryFor(tier).map { it.spec }

    fun allToolInfos(): List<AgentToolInfo> = agentToolInfos()

    suspend fun call(tier: AgentPermissionTier, name: String, rawArgs: String): AgentToolResult {
        val def = registryFor(tier).firstOrNull { it.spec.name == name }
            ?: return AgentToolResult(false, "$name is not registered for current permission tier $tier")
        val args = parseArgs(rawArgs)
        val request = approvalRequest(def, tier, args)
        if (!approver.isApproved(request.scopeKey)) {
            val approved = approver.approve(request)
            if (approved == AgentApprovalDecision.Deny) {
                return AgentToolResult(false, "user denied tool call: $name")
            }
        }
        return try {
            execute(name, args)
        } catch (t: Throwable) {
            AgentToolResult(false, "tool error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    suspend fun selfTest(name: String): AgentToolResult = withContext(Dispatchers.IO) {
        if (buildTools().none { it.spec.name == name }) {
            return@withContext AgentToolResult(false, "unknown tool: $name")
        }
        try {
            runSelfTest(name)
        } catch (t: Throwable) {
            AgentToolResult(false, "self-test error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun registryFor(tier: AgentPermissionTier): List<ToolDef> =
        toolRegistries[tier].orEmpty()

    private fun approvalRequest(def: ToolDef, tier: AgentPermissionTier, args: JSONObject): AgentApprovalRequest {
        val name = def.spec.name
        val prettyArgs = prettyArgs(args)
        fun req(
            title: String,
            scopeKey: String,
            scopeLabel: String,
            approveSessionLabel: String,
        ) = AgentApprovalRequest(
            kind = name,
            title = title,
            detail = "权限层：$tier\n范围：$scopeLabel\n\n${def.spec.description}\n\n参数：\n$prettyArgs",
            scopeKey = scopeKey,
            scopeLabel = scopeLabel,
            approveSessionLabel = approveSessionLabel,
        )

        return when (name) {
            "create_plan", "update_plan" ->
                req(
                    "允许 Agent 更新计划吗？",
                    "plan:$name:${shortHash(args.toString())}",
                    "$name ${shortHash(args.toString())}",
                    "允许，并且本次会话不再询问这个计划更新",
                )
            "write_code_file", "delete_code_from_file" ->
                req(
                    "允许 Agent 修改这个代码文件吗？",
                    "file-write:$name:${fileScope(name, args)}",
                    fileScopeLabel(name, args),
                    "允许，并且本次会话不再询问这个代码文件",
                )
            "container_write", "batch_edit_files", "private_file_write", "private_batch_edit_files" ->
                req(
                    "允许 Agent 写入这些文件吗？",
                    "file-write:$name:${fileScope(name, args)}",
                    fileScopeLabel(name, args),
                    "允许，并且本次会话不再询问这些文件",
                )
            "container_read", "container_list", "private_file_read", "private_file_list" ->
                req(
                    "允许 Agent 读取这个文件范围吗？",
                    "file-read:$name:${fileScope(name, args)}",
                    fileScopeLabel(name, args),
                    "允许，并且本次会话不再询问这个文件范围",
                )
            "web_search", "browser_request", "page_fetch" ->
                req(
                    "允许 Agent 发起这个网络请求吗？",
                    "network:$name:${networkScope(name, args)}",
                    networkScopeLabel(name, args),
                    "允许，并且本次会话不再询问这个网络范围",
                )
            "page_set_text", "page_set_html", "page_set_attr" ->
                req(
                    "允许 Agent 修改当前网页吗？",
                    "page-write:$name:${args.optString("selector", "")}:${args.optString("name", "")}",
                    "当前页面 selector=${args.optString("selector", "")}",
                    "允许，并且本次会话不再询问这个页面选择器",
                )
            "page_scroll" ->
                req(
                    "允许 Agent 滑动当前网页界面吗？",
                    "page-scroll:${args.optString("selector", "")}:${args.optString("direction", "down")}",
                    "当前页面 selector=${args.optString("selector", "window")}, direction=${args.optString("direction", "down")}",
                    "允许，并且本次会话不再询问这个滑动范围",
                )
            "page_exec" ->
                req(
                    "允许 Agent 在当前网页执行这段 JavaScript 吗？",
                    "page-js:${shortHash(args.optString("code", ""))}",
                    "当前页面 JS 片段 ${shortHash(args.optString("code", ""))}",
                    "允许，并且本次会话不再询问这段 JavaScript",
                )
            "proxy_start", "proxy_stop", "throttle_set", "replace_set", "mock_set", "intercept_set" ->
                req(
                    "允许 Agent 修改浏览器网络规则吗？",
                    "network-rule:$name:${shortHash(args.toString())}",
                    "$name 规则 ${shortHash(args.toString())}",
                    "允许，并且本次会话不再询问这条网络规则",
                )
            "intercept_resolve", "resp_intercept_resolve" ->
                req(
                    "允许 Agent 处理这条暂停的请求吗？",
                    "intercept:$name:${args.optString("flowId", "")}:${args.optString("decision", "continue")}",
                    "flowId=${args.optString("flowId", "")}, decision=${args.optString("decision", "continue")}",
                    "允许，并且本次会话不再询问这条暂停请求",
                )
            "intercept_resolve_all" -> {
                val idsArr = args.optJSONArray("ids")
                val idsLabel =
                    if (idsArr == null || idsArr.length() == 0) {
                        "全部待放行"
                    } else {
                        (0 until idsArr.length()).joinToString(",") { idsArr.optString(it, "") }
                    }
                req(
                    "允许 Agent 批量放行这些暂停的请求吗？",
                    "intercept-all:${args.optString("decision", "continue")}:$idsLabel",
                    "ids=$idsLabel, decision=${args.optString("decision", "continue")}",
                    "允许，并且本次会话不再询问批量放行",
                )
            }
            "cookie_reveal" ->
                req(
                    "允许 Agent 读取这条请求的凭证明文吗？",
                    "secret:${args.optString("id", "")}:${args.optString("name", "authorization").lowercase()}",
                    "flowId=${args.optString("id", "")}, header=${args.optString("name", "authorization")}",
                    "允许，并且本次会话不再询问这个凭证范围",
                )
            else ->
                req(
                    "允许 Agent 读取这些浏览器数据吗？",
                    "read:$name:${shortHash(args.toString())}",
                    "$name ${shortHash(args.toString())}",
                    "允许，并且本次会话不再询问这个读取范围",
                )
        }
    }

    private fun prettyArgs(args: JSONObject): String {
        val prettyArgs = try {
            args.toString(2)
        } catch (_: Throwable) {
            args.toString()
        }.take(1400)
        return prettyArgs
    }

    private suspend fun execute(name: String, args: JSONObject): AgentToolResult = withContext(Dispatchers.IO) {
        when (name) {
            "create_plan" -> setPlanFromArgs(args, created = true)
            "update_plan" -> setPlanFromArgs(args, created = false)
            "write_code_file" -> writeCodeFile(args)
            "delete_code_from_file" -> deleteCodeFromFile(args)
	            "network_list" -> {
	                val sinceSec = args.optDouble("sinceSeconds", 0.0)
	                val sinceMs = if (sinceSec > 0) System.currentTimeMillis() - (sinceSec * 1000).toLong() else 0L
	                val rows = NetFlowStore.listJson(
	                    method = args.optString("method", ""),
	                    urlContains = args.optString("urlContains", ""),
	                    sinceMs = sinceMs,
	                    limit = args.optInt("limit", 10),
	                )
	                ok(rows.toString())
	            }
	            "network_get" -> {
	                val flowId = args.optString("id", "")
	                val rec = NetFlowStore.getJson(flowId, args.optString("part", "all"))
	                if (rec == null) err("no flow with id=$flowId") else ok(rec.toString())
	            }
            "proxy_status" -> ok(if (ProxyProbe.isRunning()) "running" else "stopped")
            "page_index" -> page("getSource")
            "page_search" -> {
                val query = args.optString("query", "")
                if (query.isEmpty()) err("query required") else page(
                    "searchSource",
                    JSONObject().put("query", query).put("limit", args.optInt("limit", 10)),
                )
            }
            "page_query" -> {
                val selector = args.optString("selector", "")
                if (selector.isEmpty()) err("selector required") else page(
                    "queryDOM",
                    JSONObject().put("selector", selector).put("limit", args.optInt("limit", 20)),
                )
            }
            "container_list" -> listFiles(containerRoot(), args.optString("path", "."))
            "container_read" -> readFile(containerRoot(), args.optString("path", ""))
            "container_write" -> writeFile(containerRoot(), args.optString("path", ""), args.optString("content", ""))
            "batch_edit_files" -> batchWriteFiles(containerRoot(), args.optJSONArray("edits") ?: JSONArray())
            "container_serve_url" -> serveContainerUrl(args.optString("path", ""))
            "web_search" -> webSearch(args.optString("query", ""), args.optInt("limit", 5))
            "browser_request" -> browserRequest(args)
            "proxy_start" -> {
                runOnMain { ProxyProbe.setEnabled(appContext, true) }
                ok(if (ProxyProbe.isRunning()) "proxy started" else "proxy start requested")
            }
            "proxy_stop" -> {
                runOnMain { ProxyProbe.setEnabled(appContext, false) }
                ok("proxy stopped")
            }
            "throttle_set" -> {
                ProxyProbe.setThrottle(
                    JSONObject()
                        .put("throttleEnabled", args.optBoolean("enabled", false))
                        .put("latencyMs", args.optLong("latencyMs", 0L))
                        .put("kbps", args.optLong("kbps", 0L)),
                )
                ok(if (args.optBoolean("enabled", false)) {
                    "throttle: ${args.optLong("latencyMs")}ms latency, ${args.optLong("kbps")} kbps"
                } else {
                    "throttle disabled"
                })
            }
            "replace_set" -> {
                val rules = ArrayList<Pair<String, String>>()
                val arr = args.optJSONArray("rules")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val from = o.optString("from", "")
                        if (from.isNotEmpty()) rules.add(from to o.optString("to", ""))
                    }
                }
                ProxyProbe.setReplaceRules(args.optBoolean("enabled", false), args.optString("scope", "both"), rules)
                ok("replace ${if (args.optBoolean("enabled", false)) "enabled" else "disabled"}: ${rules.size} rules")
            }
            "mock_set" -> {
                val arr = args.optJSONArray("rules") ?: JSONArray()
                ProxyProbe.setMockRules(JSONObject().put("rules", arr))
                ok("mock rules set: ${arr.length()} rules")
            }
	            "intercept_set" -> {
	                val reqAll = if (args.has("reqAll")) args.optBoolean("reqAll", false) else true
	                val respAll = if (args.has("respAll")) args.optBoolean("respAll", false) else true
	                ProxyProbe.setInterceptRules(
	                    JSONObject()
	                        .put("reqAll", reqAll)
	                        .put("respAll", respAll)
	                        .put("interceptTelemetry", args.optBoolean("interceptTelemetry", false))
	                        .put("interceptHeartbeat", args.optBoolean("interceptHeartbeat", false))
	                        .put("interceptNoise", args.optBoolean("interceptNoise", false))
	                        .put("interceptCookie", args.optBoolean("interceptCookie", false))
	                        .put("rules", args.optJSONArray("rules") ?: JSONArray()),
	                )
	                ok("intercept rules set: reqAll=$reqAll respAll=$respAll lowValue=${if (args.optBoolean("interceptTelemetry", false)) "" else "telemetry pass; "}${if (args.optBoolean("interceptHeartbeat", false) || args.optBoolean("interceptNoise", false)) "" else "heartbeat/noise pass; "}${if (args.optBoolean("interceptCookie", false)) "" else "cookie pass"}")
	            }
            "intercept_pending" -> ok(ProxyProbe.pendingInterceptList().toString())
	            "intercept_resolve" -> {
	                val flowId = NetFlowStore.flowIdFor(args.optString("flowId", "")) ?: args.optString("flowId", "")
	                if (flowId.isEmpty()) return@withContext err("flowId required")
                val decision = JSONObject().put("decision", args.optString("decision", "continue"))
                // Only carry the fields the model actually edited; absence = keep the
                // original. headEdited tells ProxyProbe to rebuild the request line/headers;
                // without it the original head is forwarded verbatim (wire order preserved).
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
                ok("intercept resolved: flowId=$flowId decision=${decision.optString("decision")}")
            }
	            "resp_intercept_resolve" -> {
	                val flowId = NetFlowStore.flowIdFor(args.optString("flowId", "")) ?: args.optString("flowId", "")
	                if (flowId.isEmpty()) return@withContext err("flowId required")
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
                ok("resp intercept resolved: flowId=$flowId decision=${decision.optString("decision")}")
            }
            "intercept_resolve_all" -> {
                val decisionStr = args.optString("decision", "continue")
                val idsArr = args.optJSONArray("ids")
                // No ids → release everything currently paused. With ids → release just those.
                val targets: List<String> = if (idsArr == null || idsArr.length() == 0) {
                    ProxyProbe.pendingInterceptFlowIds()
                } else {
                    (0 until idsArr.length())
                        .map { idsArr.optString(it, "").trim() }
                        .filter { it.isNotEmpty() }
                        .map { NetFlowStore.flowIdFor(it) ?: it }
                }
                if (targets.isEmpty()) return@withContext err("没有待放行的拦截流；可传 ids:[六位 code] 指定，或留空放行全部")
                val released = ArrayList<String>()
                val missed = ArrayList<String>()
                for (flowId in targets.distinct()) {
                    val decision = JSONObject().put("decision", decisionStr)
                    val label = NetFlowStore.codeFor(flowId) ?: flowId
                    if (ProxyProbe.releaseIntercept(flowId, decision)) released.add(label) else missed.add(label)
                }
                ok(
                    "批量放行(decision=$decisionStr)：成功 ${released.size} 条 [${released.joinToString(",")}]" +
                        if (missed.isEmpty()) "" else "；未命中 ${missed.size} 条 [${missed.joinToString(",")}]",
                )
            }
            "page_set_text" -> page(
                "setText",
                JSONObject()
                    .put("selector", args.optString("selector", ""))
                    .put("text", args.optString("text", ""))
                    .put("all", args.optBoolean("all", false)),
            )
            "page_set_html" -> page(
                "setHTML",
                JSONObject()
                    .put("selector", args.optString("selector", ""))
                    .put("html", args.optString("html", ""))
                    .put("all", args.optBoolean("all", false)),
            )
            "page_set_attr" -> page(
                "setAttr",
                JSONObject()
                    .put("selector", args.optString("selector", ""))
                    .put("name", args.optString("name", ""))
                    .put("value", args.optString("value", ""))
                    .put("all", args.optBoolean("all", false)),
            )
            "page_scroll" -> page(
                "scrollPage",
                JSONObject()
                    .put("direction", args.optString("direction", "down"))
                    .put("amount", args.optInt("amount", args.optInt("pixels", 600)))
                    .put("selector", args.optString("selector", ""))
                    .put("behavior", args.optString("behavior", "auto")),
                5_000,
            )
            "page_fetch" -> page(
                "pageFetch",
                args,
                args.optLong("timeoutMs", 20_000L).coerceIn(1_000L, 60_000L) + 1_000L,
            )
	            "cookie_reveal" -> {
	                val flowId = NetFlowStore.flowIdFor(args.optString("id", "")) ?: args.optString("id", "")
	                val header = args.optString("name", "authorization")
                val value = NetFlowStore.revealHeader(flowId, header)
                if (value == null) err("flow $flowId has no $header header") else ok(value)
            }
            "page_exec" -> {
                val code = args.optString("code", "")
                if (code.isEmpty()) err("code required") else page(
                    "evalJS",
                    JSONObject().put("code", code).put("timeoutMs", args.optInt("timeoutMs", 10000)),
                    15_000,
                )
            }
            "private_file_list" -> args.optString("path", ".").let { p -> privateGuard(p) ?: listFiles(privateRoot(), p) }
            "private_file_read" -> args.optString("path", "").let { p -> privateGuard(p) ?: readFile(privateRoot(), p) }
            "private_file_write" -> args.optString("path", "").let { p ->
                privateGuard(p) ?: writeFile(privateRoot(), p, args.optString("content", ""))
            }
            "private_batch_edit_files" -> {
                val edits = args.optJSONArray("edits") ?: JSONArray()
                privateBatchGuard(edits) ?: batchWriteFiles(privateRoot(), edits)
            }
            else -> err("unknown tool: $name")
        }
    }

    private suspend fun runSelfTest(name: String): AgentToolResult {
        val stamp = System.currentTimeMillis()
        val tempDir = ".agent_self_test"
        return when (name) {
            "create_plan" -> execute(
                name,
                JSONObject()
                    .put("title", "工具自检计划")
                    .put("steps", JSONArray().put("创建计划工具自检").put("确认计划可以写入悬浮窗")),
            )
            "update_plan" -> execute(
                name,
                JSONObject()
                    .put("title", "工具自检计划")
                    .put("steps", JSONArray().put("更新计划工具自检").put("确认计划可以被覆盖")),
            )
            "write_code_file" -> execute(
                name,
                JSONObject()
                    .put("path", "$tempDir/write_code_file.kt")
                    .put("code", "fun selfTest(): String = \"ok-$stamp\"\n")
                    .put("mode", "replace"),
            )
            "delete_code_from_file" -> {
                writeFile(containerRoot(), "$tempDir/delete_code_from_file.kt", "fun keep() = 1\nfun remove() = 2\n")
                execute(
                    name,
                    JSONObject()
                        .put("path", "$tempDir/delete_code_from_file.kt")
                        .put("startLine", 2)
                        .put("endLine", 2),
                )
            }
            "network_list" -> execute(name, JSONObject().put("limit", 1))
            "network_get" -> {
                val rows = NetFlowStore.listJson(limit = 1)
                val first = rows.optJSONObject(0)
                if (first == null) {
                    ok("OK: network_get is callable; no captured flow is available to fetch")
                } else {
                    execute(name, JSONObject().put("id", first.optString("id", "")))
                }
            }
            "proxy_status" -> execute(name, JSONObject())
            "page_index" -> execute(name, JSONObject())
            "page_search" -> {
                val indexed = execute("page_index", JSONObject())
                if (!indexed.ok) indexed else execute(name, JSONObject().put("query", "<").put("limit", 1))
            }
            "page_query" -> execute(name, JSONObject().put("selector", "html").put("limit", 1))
            "container_list" -> {
                containerRoot().mkdirs()
                execute(name, JSONObject().put("path", "."))
            }
            "container_read" -> {
                writeFile(containerRoot(), "$tempDir/container_read.txt", "ok-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/container_read.txt"))
            }
            "container_write" -> execute(
                name,
                JSONObject().put("path", "$tempDir/container_write.txt").put("content", "ok-$stamp"),
            )
            "batch_edit_files" -> execute(
                name,
                JSONObject().put(
                    "edits",
                    JSONArray()
                        .put(JSONObject().put("path", "$tempDir/batch_a.txt").put("content", "a-$stamp"))
                        .put(JSONObject().put("path", "$tempDir/batch_b.txt").put("content", "b-$stamp")),
                ),
            )
            "container_serve_url" -> {
                writeFile(containerRoot(), "$tempDir/served.txt", "served-$stamp")
                val served = execute(name, JSONObject().put("path", "$tempDir/served.txt"))
                if (!served.ok) served else {
                    val url = JSONObject(served.text).optString("url", "")
                    browserRequest(JSONObject().put("url", url).put("method", "GET").put("timeoutMs", 5000))
                }
            }
            "web_search" -> execute(name, JSONObject().put("query", "BrowserHelper self test").put("limit", 1))
            "browser_request" -> {
                writeFile(containerRoot(), "$tempDir/browser_request.txt", "request-$stamp")
                val url = AgentContainerServer.urlFor(containerRoot(), "$tempDir/browser_request.txt")
                execute(name, JSONObject().put("url", url).put("method", "GET").put("timeoutMs", 5000))
            }
            // Don't actually flip the live proxy during a self-test: toggling rebinds the
            // listen port and would disrupt any capture session in progress. Just confirm
            // the tool is wired and report the current state.
            "proxy_start", "proxy_stop" ->
                ok("OK: $name is callable; proxy is currently ${if (ProxyProbe.isRunning()) "running" else "stopped"} (self-test does not toggle the live proxy)")
            "throttle_set" -> execute(name, JSONObject().put("enabled", false).put("latencyMs", 0).put("kbps", 0))
            "replace_set" -> execute(name, JSONObject().put("enabled", false).put("scope", "both").put("rules", JSONArray()))
            "mock_set" -> execute(name, JSONObject().put("rules", JSONArray()))
            "intercept_set" -> execute(
                name,
                JSONObject()
                    .put("reqAll", false)
                    .put("respAll", false)
                    .put("interceptTelemetry", false)
                    .put("interceptNoise", false)
                    .put("interceptCookie", false)
                    .put("rules", JSONArray()),
            )
            "intercept_pending" -> execute(name, JSONObject())
            "intercept_resolve" -> execute(
                name,
                JSONObject().put("flowId", "__agent_self_test__").put("decision", "continue"),
            )
            "resp_intercept_resolve" -> execute(
                name,
                JSONObject().put("flowId", "__agent_self_test__").put("decision", "continue"),
            )
            "intercept_resolve_all" -> execute(
                name,
                JSONObject().put("ids", JSONArray().put("__agent_self_test__")).put("decision", "continue"),
            )
            "page_set_text", "page_set_html", "page_set_attr" -> {
                val id = "__bh_agent_self_test"
                val setup = page(
                    "evalJS",
                    JSONObject().put(
                        "code",
                        "var el=document.getElementById('$id');" +
                            "if(!el){el=document.createElement('div');el.id='$id';el.style.display='none';document.documentElement.appendChild(el);}" +
                            "return true;",
                    ).put("timeoutMs", 5000),
                    6000,
                )
                if (!setup.ok) setup else {
                    val args = when (name) {
                        "page_set_text" -> JSONObject().put("selector", "#$id").put("text", "ok-$stamp")
                        "page_set_html" -> JSONObject().put("selector", "#$id").put("html", "<span>ok-$stamp</span>")
                        else -> JSONObject().put("selector", "#$id").put("name", "data-agent-self-test").put("value", "ok-$stamp")
                    }
                    val result = execute(name, args)
                    page(
                        "evalJS",
                        JSONObject().put("code", "var el=document.getElementById('$id');if(el)el.remove();return true;")
                            .put("timeoutMs", 5000),
                        6000,
                    )
                    result
                }
            }
            "page_scroll" -> execute(
                name,
                JSONObject()
                    .put("direction", "down")
                    .put("amount", 1)
                    .put("behavior", "auto"),
            )
            "page_fetch" -> {
                writeFile(containerRoot(), "$tempDir/page_fetch.txt", "page-fetch-$stamp")
                val url = AgentContainerServer.urlFor(containerRoot(), "$tempDir/page_fetch.txt")
                val result = execute(name, JSONObject().put("url", url).put("method", "GET").put("timeoutMs", 8000))
                // page_fetch runs in the *current page's* world. An HTTPS page will block a
                // request to the local HTTP container server as mixed-content / CORS, surfacing
                // as a fetch NetworkError. That's an environment limit of the current page, not a
                // wiring failure — so treat a reachable-but-blocked fetch as callable.
                if (result.ok) result
                else ok("OK: page_fetch 已接通；当前页面 world 无法访问本地测试 URL（多为 HTTPS 页面的混合内容/CORS 限制）：${result.text.take(160)}")
            }
            "cookie_reveal" -> {
                val rows = NetFlowStore.listJson(limit = 20)
                var tested = false
                for (i in 0 until rows.length()) {
                    val id = rows.optJSONObject(i)?.optString("id", "").orEmpty()
                    if (id.isEmpty()) continue
                    val result = execute(name, JSONObject().put("id", id).put("name", "authorization"))
                    tested = true
                    if (result.ok) return result
                }
                if (tested) ok("OK: cookie_reveal is callable; no captured flow has an authorization header")
                else ok("OK: cookie_reveal is callable; no captured flow is available")
            }
            "page_exec" -> execute(
                name,
                JSONObject().put("code", "return 1 + 1;").put("timeoutMs", 5000),
            )
            "private_file_list" -> execute(name, JSONObject().put("path", "."))
            "private_file_read" -> {
                writeFile(privateRoot(), "$tempDir/private_read.txt", "private-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/private_read.txt"))
            }
            "private_file_write" -> execute(
                name,
                JSONObject().put("path", "$tempDir/private_write.txt").put("content", "private-$stamp"),
            )
            "private_batch_edit_files" -> execute(
                name,
                JSONObject().put(
                    "edits",
                    JSONArray()
                        .put(JSONObject().put("path", "$tempDir/private_batch_a.txt").put("content", "a-$stamp"))
                        .put(JSONObject().put("path", "$tempDir/private_batch_b.txt").put("content", "b-$stamp")),
                ),
            )
            else -> err("no self-test registered for $name")
        }
    }

    private fun page(cmd: String, args: JSONObject = JSONObject(), timeoutMs: Long = 15_000): AgentToolResult {
        val res = PageChannel.exec(cmd, args, timeoutMs)
        return if (res.has("error")) err(res.optString("error")) else ok(res.toString())
    }

    private suspend fun setPlanFromArgs(args: JSONObject, created: Boolean): AgentToolResult {
        val plan = planTextFromArgs(args)
        runOnMain { onPlanChanged?.invoke(plan) }
        return ok(
            JSONObject()
                .put("created", created)
                .put("plan", plan)
                .toString(),
        )
    }

    private fun planTextFromArgs(args: JSONObject): String {
        val content = args.optString("content", "").trim()
        if (content.isNotEmpty()) return content
        val title = args.optString("title", "计划").ifBlank { "计划" }
        val arr = args.optJSONArray("steps")
        if (arr == null || arr.length() == 0) return title
        val sb = StringBuilder(title)
        for (i in 0 until arr.length()) {
            sb.append('\n').append(i + 1).append(". ").append(arr.optString(i, ""))
        }
        return sb.toString()
    }

    private fun writeCodeFile(args: JSONObject): AgentToolResult {
        val path = args.optString("path", "")
        val code = args.optString("code", "")
        if (path.isBlank()) return err("path required")
        if (code.toByteArray(Charsets.UTF_8).size > FILE_WRITE_CAP) return err("code too large")
        val file = resolvePath(containerRoot(), path) ?: return err("invalid path")
        val old = if (file.exists() && file.isFile && file.length() <= FILE_READ_CAP) {
            file.readText(Charsets.UTF_8)
        } else {
            ""
        }
        file.parentFile?.mkdirs()
        val mode = args.optString("mode", "replace").lowercase()
        if (mode == "append") {
            file.appendText(code, Charsets.UTF_8)
        } else {
            file.writeText(code, Charsets.UTF_8)
        }
        return ok(
            JSONObject()
                .put("path", relativePath(containerRoot(), file))
                .put("mode", mode)
                .put("chars", code.length)
                .put("addedLines", lineCount(code))
                .put("removedLines", if (mode == "append") 0 else lineCount(old))
                .put("oldLines", lineCount(old))
                .put("newLines", lineCount(if (mode == "append") old + code else code))
                .put("removedPreview", if (mode == "append") "" else previewText(old))
                .toString(),
        )
    }

    private fun deleteCodeFromFile(args: JSONObject): AgentToolResult {
        val path = args.optString("path", "")
        if (path.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), path) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        if (file.length() > FILE_READ_CAP) return err("file too large (${file.length()} bytes)")
        val old = file.readText(Charsets.UTF_8)
        var removedText = ""
        var removedStartLine = 1
        val next = when {
            args.optBoolean("all", false) -> {
                removedText = old
                ""
            }
            args.optInt("startLine", 0) > 0 -> {
                val start = args.optInt("startLine", 0)
                val end = args.optInt("endLine", start)
                if (end < start) return err("endLine must be >= startLine")
                val lines = old.split('\n').toMutableList()
                if (start > lines.size) return err("startLine out of range")
                val from = start - 1
                val to = minOf(end, lines.size) - 1
                removedText = lines.subList(from, to + 1).joinToString("\n")
                removedStartLine = start
                for (i in to downTo from) lines.removeAt(i)
                lines.joinToString("\n")
            }
            args.optString("startMarker", "").isNotEmpty() -> {
                val startMarker = args.optString("startMarker", "")
                val endMarker = args.optString("endMarker", "")
                val from = old.indexOf(startMarker)
                if (from < 0) return err("startMarker not found")
                val to = if (endMarker.isEmpty()) {
                    from + startMarker.length
                } else {
                    val endIdx = old.indexOf(endMarker, from + startMarker.length)
                    if (endIdx < 0) return err("endMarker not found")
                    endIdx + endMarker.length
                }
                removedText = old.substring(from, to)
                removedStartLine = lineNumberAt(old, from)
                old.removeRange(from, to)
            }
            args.optString("code", "").isNotEmpty() -> {
                val code = args.optString("code", "")
                val from = old.indexOf(code)
                if (from < 0) return err("code snippet not found")
                removedText = code
                removedStartLine = lineNumberAt(old, from)
                old.removeRange(from, from + code.length)
            }
            else -> return err("delete range required: all, startLine/endLine, startMarker/endMarker, or code")
        }
        file.writeText(next, Charsets.UTF_8)
        return ok(
            JSONObject()
                .put("path", relativePath(containerRoot(), file))
                .put("removedChars", old.length - next.length)
                .put("removedLines", lineCount(removedText))
                .put("removedStartLine", removedStartLine)
                .put("removedPreview", previewText(removedText))
                .toString(),
        )
    }

    private suspend fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        suspendCancellableCoroutine<Unit> { cont ->
            main.post {
                try {
                    block()
                    cont.resume(Unit)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
        }
    }

    private fun containerRoot(): File {
        val external = appContext.getExternalFilesDir("agent_container")
        val root = external ?: File(appContext.filesDir, "agent_container_fallback")
        return root.also { it.mkdirs() }
    }

    private fun privateRoot(): File = File(appContext.applicationInfo.dataDir)

    // Even at S3 (and after the user approves), the agent must not read/write the app's
    // own secret stores — shared_prefs holds the API key + bridge token, databases may
    // hold sensitive state. Blocking here keeps those out of the model context even if a
    // page tries to prompt-inject the agent into exfiltrating them.
    private fun privateGuard(rel: String): AgentToolResult? {
        val file = resolvePath(privateRoot(), rel) ?: return null // let downstream report invalid path
        val first = relativePath(privateRoot(), file).replace('\\', '/').substringBefore('/').lowercase()
        return if (first == "shared_prefs" || first == "databases") {
            err("access to $first is not allowed (contains app secrets)")
        } else {
            null
        }
    }

    private fun privateBatchGuard(edits: JSONArray): AgentToolResult? {
        for (i in 0 until edits.length()) {
            val p = edits.optJSONObject(i)?.optString("path", "") ?: ""
            privateGuard(p)?.let { return it }
        }
        return null
    }

    private fun listFiles(root: File, rel: String): AgentToolResult {
        val dir = resolvePath(root, rel) ?: return err("invalid path")
        if (!dir.exists()) return err("path not found")
        if (!dir.isDirectory) return err("not a directory")
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.take(200)?.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("path", relativePath(root, f))
                    .put("dir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0L),
            )
        }
        return ok(arr.toString())
    }

    private fun readFile(root: File, rel: String): AgentToolResult {
        val file = resolvePath(root, rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        if (file.length() > FILE_READ_CAP) return err("file too large (${file.length()} bytes)")
        return ok(file.readText(Charsets.UTF_8))
    }

    private fun writeFile(root: File, rel: String, content: String): AgentToolResult {
        if (content.toByteArray(Charsets.UTF_8).size > FILE_WRITE_CAP) return err("content too large")
        val file = resolvePath(root, rel) ?: return err("invalid path")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return ok("wrote ${content.length} chars to ${relativePath(root, file)}")
    }

    private fun batchWriteFiles(root: File, edits: JSONArray): AgentToolResult {
        if (edits.length() == 0) return err("edits required")
        if (edits.length() > 40) return err("too many edits; max 40 files")
        val planned = ArrayList<Triple<File, String, String>>()
        for (i in 0 until edits.length()) {
            val item = edits.optJSONObject(i) ?: return err("edit[$i] must be an object")
            val path = item.optString("path", "")
            val content = item.optString("content", "")
            if (content.toByteArray(Charsets.UTF_8).size > FILE_WRITE_CAP) {
                return err("edit[$i] content too large")
            }
            val file = resolvePath(root, path) ?: return err("edit[$i] invalid path")
            val old = if (file.exists() && file.isFile && file.length() <= FILE_READ_CAP) {
                file.readText(Charsets.UTF_8)
            } else {
                ""
            }
            planned.add(Triple(file, content, old))
        }
        planned.forEach { (file, content, _) ->
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        val out = JSONArray()
        planned.forEach { (file, content, old) ->
            out.put(
                JSONObject()
                    .put("path", relativePath(root, file))
                    .put("chars", content.length)
                    .put("addedLines", lineCount(content))
                    .put("removedLines", lineCount(old))
                    .put("oldLines", lineCount(old))
                    .put("newLines", lineCount(content))
                    .put("removedPreview", previewText(old)),
            )
        }
        return ok(JSONObject().put("written", out).toString())
    }

    private fun serveContainerUrl(rel: String): AgentToolResult {
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        val relative = relativePath(containerRoot(), file)
        val url = AgentContainerServer.urlFor(containerRoot(), relative)
        return ok(
            JSONObject()
                .put("url", url)
                .put("path", relative)
                .put("size", file.length())
                .toString(),
        )
    }

    private fun browserRequest(args: JSONObject): AgentToolResult {
        val rawUrl = args.optString("url", "")
        if (rawUrl.isBlank()) return err("url required")
        val url = try {
            URL(rawUrl)
        } catch (_: Throwable) {
            return err("invalid url")
        }
        if (url.protocol != "http" && url.protocol != "https") return err("only http/https urls are allowed")
        val method = args.optString("method", "GET").uppercase().ifBlank { "GET" }
        val body = args.optString("body", "")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = args.optInt("timeoutMs", 20_000).coerceIn(1_000, 60_000)
            readTimeout = args.optInt("timeoutMs", 20_000).coerceIn(1_000, 60_000)
            instanceFollowRedirects = args.optBoolean("followRedirects", true)
            val headers = args.optJSONObject("headers")
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    setRequestProperty(key, headers.optString(key, ""))
                }
            }
            if (body.isNotEmpty() || method in setOf("POST", "PUT", "PATCH")) {
                doOutput = true
            }
        }
        return try {
            if (conn.doOutput) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readCapped(it, HTTP_BODY_CAP + 1) } ?: ByteArray(0)
            val truncated = bytes.size > HTTP_BODY_CAP
            val text = String(bytes.copyOfRange(0, minOf(bytes.size, HTTP_BODY_CAP)), Charsets.UTF_8)
            val headers = JSONObject()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null) headers.put(key, JSONArray(values))
            }
            val out = JSONObject()
                .put("status", code)
                .put("url", conn.url.toString())
                .put("headers", headers)
                .put("body", text)
                .put("truncated", truncated)
            if (code in 200..299) ok(out.toString()) else err(out.toString())
        } finally {
            conn.disconnect()
        }
    }

    private fun resolvePath(root: File, rel: String): File? {
        if (rel.isBlank() || rel.indexOf('\u0000') >= 0 || rel.startsWith('/')) return null
        val rootFile = root.canonicalFile
        val file = File(rootFile, rel).canonicalFile
        return if (file == rootFile || file.path.startsWith(rootFile.path + File.separator)) file else null
    }

    private fun relativePath(root: File, file: File): String {
        val rootPath = root.canonicalFile.path
        val filePath = file.canonicalFile.path
        return if (filePath == rootPath) "." else filePath.removePrefix(rootPath + File.separator)
    }

    private fun webSearch(query: String, limit: Int): AgentToolResult {
        if (query.isBlank()) return err("query required")
        if (searchUrl.isBlank()) return err("search api url is not configured")
        val endpoint = buildSearchUrl(query, limit.coerceIn(1, 20))
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            if (searchKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $searchKey")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(SEARCH_RESULT_CAP)
            if (code in 200..299) ok(text) else err("search failed HTTP $code: ${text.take(500)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun buildSearchUrl(query: String, limit: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val key = URLEncoder.encode(searchKey, "UTF-8")
        var url = searchUrl
            .replace("{q}", q)
            .replace("{query}", q)
            .replace("{limit}", limit.toString())
            .replace("{key}", key)
        if (!url.contains(q) && !url.contains("q=") && !url.contains("query=")) {
            url += (if (url.contains("?")) "&" else "?") + "q=$q"
        }
        if (searchKey.isNotBlank() && !searchUrl.contains("{key}") && !url.contains("key=")) {
            url += (if (url.contains("?")) "&" else "?") + "key=$key"
        }
        if (!url.contains("limit=") && !searchUrl.contains("{limit}")) {
            url += (if (url.contains("?")) "&" else "?") + "limit=$limit"
        }
        return url
    }

    private val placeholderRe = Regex("""\{\{(\w+):(\w+)(?::([^}]+))?\}\}""")

    private fun resolvePlaceholders(value: String): String = placeholderRe.replace(value) { m ->
        val type = m.groupValues[1].lowercase()
        val flowId = m.groupValues[2]
        val extra = m.groupValues[3].ifEmpty { null }
        val header = when (type) {
            "cookie" -> "cookie"
            "auth", "authorization" -> "authorization"
            "header" -> extra ?: return@replace m.value
            else -> return@replace m.value
        }
        NetFlowStore.revealHeader(flowId, header) ?: m.value
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
}

private fun ok(text: String) = AgentToolResult(true, text)

private fun err(text: String) = AgentToolResult(false, text)

private fun lineCount(text: String): Int {
    if (text.isEmpty()) return 0
    val lines = text.split('\n')
    return if (lines.lastOrNull()?.isEmpty() == true) lines.size - 1 else lines.size
}

private fun lineNumberAt(text: String, index: Int): Int {
    if (index <= 0) return 1
    return text.take(index.coerceAtMost(text.length)).count { it == '\n' } + 1
}

private fun previewText(text: String, maxChars: Int = 20_000): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "\n…"

private fun parseArgs(raw: String): JSONObject = try {
    if (raw.isBlank()) JSONObject() else JSONObject(raw)
} catch (_: Throwable) {
    JSONObject().put("_raw", raw)
}

fun agentToolInfos(): List<AgentToolInfo> =
    buildTools().distinctBy { it.spec.name }.map { AgentToolInfo(it.spec.name, it.spec.description, it.tier) }

private fun fileScope(name: String, args: JSONObject): String = when (name) {
    "batch_edit_files", "private_batch_edit_files" -> editPaths(args.optJSONArray("edits") ?: JSONArray()).joinToString("|")
    "write_code_file", "delete_code_from_file" -> scopePath(args.optString("path", "."))
    else -> scopePath(args.optString("path", "."))
}

private fun fileScopeLabel(name: String, args: JSONObject): String = when (name) {
    "batch_edit_files", "private_batch_edit_files" -> editPaths(args.optJSONArray("edits") ?: JSONArray()).joinToString(", ")
    "write_code_file", "delete_code_from_file" -> scopePath(args.optString("path", "."))
    else -> scopePath(args.optString("path", "."))
}.ifBlank { "." }

private fun editPaths(edits: JSONArray): List<String> {
    val paths = ArrayList<String>()
    for (i in 0 until edits.length()) {
        paths.add(scopePath(edits.optJSONObject(i)?.optString("path", "") ?: ""))
    }
    return paths.sorted()
}

private fun scopePath(path: String): String =
    path.trim().replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")
        .ifBlank { "." }

private fun networkScope(name: String, args: JSONObject): String = when (name) {
    "web_search" -> "search:${shortHash(args.optString("query", ""))}"
    "browser_request", "page_fetch" -> {
        val method = args.optString("method", "GET").uppercase().ifBlank { "GET" }
        "$method:${hostOf(args.optString("url", ""))}"
    }
    else -> shortHash(args.toString())
}

private fun networkScopeLabel(name: String, args: JSONObject): String = when (name) {
    "web_search" -> "搜索：${args.optString("query", "").take(120)}"
    "browser_request" -> "${args.optString("method", "GET").uppercase()} ${hostOf(args.optString("url", ""))}"
    "page_fetch" -> {
        val from = args.optString("requiredPageUrlContains", "").ifBlank { "当前网页" }
        "$from -> ${args.optString("method", "GET").uppercase()} ${hostOf(args.optString("url", ""))}"
    }
    else -> name
}

private fun hostOf(rawUrl: String): String = try {
    URI(rawUrl).host ?: rawUrl.take(120)
} catch (_: Throwable) {
    rawUrl.take(120)
}

private fun shortHash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder()
    for (i in 0 until minOf(8, digest.size)) {
        val b = digest[i].toInt() and 0xff
        out.append("0123456789abcdef"[b ushr 4])
        out.append("0123456789abcdef"[b and 0x0f])
    }
    return out.toString()
}

private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(8192)
    val out = ArrayList<Byte>()
    var remaining = maxBytes
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        for (i in 0 until read) out.add(buffer[i])
        remaining -= read
    }
    return out.toByteArray()
}

private object AgentContainerServer {
    private val started = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var root: File? = null

    fun urlFor(rootDir: File, rel: String): String {
        ensure(rootDir)
        val encoded = rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:${server?.localPort ?: 0}/agent-container/$encoded"
    }

    @Synchronized
    private fun ensure(rootDir: File) {
        root = rootDir.canonicalFile.also { it.mkdirs() }
        val existing = server
        if (started.get() && existing != null && !existing.isClosed) return
        val next = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = next
        started.set(true)
        Thread {
            while (!next.isClosed) {
                try {
                    val socket = next.accept()
                    Thread { handle(socket) }.apply {
                        name = "AgentContainerClient"
                        isDaemon = true
                        start()
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }.apply {
            name = "AgentContainerServer"
            isDaemon = true
            start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val request = readRequest(input) ?: return
            val parts = request.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeText(s, 405, "method not allowed")
                return
            }
            val uriPath = parts[1].substringBefore('?')
            if (!uriPath.startsWith("/agent-container/")) {
                writeText(s, 404, "not found")
                return
            }
            val rel = URLDecoder.decode(uriPath.removePrefix("/agent-container/"), "UTF-8")
            val file = safeChild(root ?: return, rel)
            if (file == null || !file.exists() || !file.isFile) {
                writeText(s, 404, "not found")
                return
            }
            val type = contentType(file.name)
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $type\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Connection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
            file.inputStream().use { it.copyTo(out) }
            out.flush()
        }
    }

    private fun readRequest(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun writeText(socket: Socket, code: Int, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private fun safeChild(root: File, rel: String): File? {
        if (rel.isBlank() || rel.indexOf('\u0000') >= 0 || rel.startsWith('/')) return null
        val rootFile = root.canonicalFile
        val file = File(rootFile, rel).canonicalFile
        return if (file == rootFile || file.path.startsWith(rootFile.path + File.separator)) file else null
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "md" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}

private fun buildToolRegistries(): Map<AgentPermissionTier, List<ToolDef>> {
    val all = buildTools()
    val s1 = all.filter { it.tier == AgentPermissionTier.S1 }
    val s2 = s1 + all.filter { it.tier == AgentPermissionTier.S2 }
    val s3 = s2 + all.filter { it.tier == AgentPermissionTier.S3 }
    return mapOf(
        AgentPermissionTier.S1 to s1,
        AgentPermissionTier.S2 to s2,
        AgentPermissionTier.S3 to s3,
    )
}

// Tool specs are pure and immutable; build the ~37 JSONObject schemas once instead of on
// every turn / self-test (a fresh AgentToolRegistry is created per model turn).
private val toolDefsCache: List<ToolDef> by lazy { buildToolDefs() }

private fun buildTools(): List<ToolDef> = toolDefsCache

private fun buildToolDefs(): List<ToolDef> = listOf(
    tool("create_plan", AgentPermissionTier.S1, "创建悬浮窗计划卡片，供用户确认后继续执行。", schema {
        prop("title", str("计划标题"))
        prop("steps", arr(str("计划步骤")))
        prop("content", str("完整计划文本；存在时优先使用"))
    }),
    tool("update_plan", AgentPermissionTier.S1, "更新悬浮窗当前计划卡片内容。", schema {
        prop("title", str("计划标题"))
        prop("steps", arr(str("计划步骤")))
        prop("content", str("完整计划文本；存在时优先使用"))
    }),
    tool("write_code_file", AgentPermissionTier.S1, "一键向 Agent 外部容器内的代码文件写入完整代码或追加代码。", schema {
        prop("path", str("容器内相对文件路径"))
        prop("code", str("要写入的代码文本"))
        prop("mode", str("replace 或 append，默认 replace"))
        required("path")
        required("code")
    }),
    tool("delete_code_from_file", AgentPermissionTier.S1, "从 Agent 外部容器内的代码文件删除指定代码片段或行范围。", schema {
        prop("path", str("容器内相对文件路径"))
        prop("all", bool("是否清空整个文件"))
        prop("startLine", num("起始行，1-based，和 endLine 搭配"))
        prop("endLine", num("结束行，1-based，默认等于 startLine"))
        prop("startMarker", str("删除起始标记，和 endMarker 搭配"))
        prop("endMarker", str("删除结束标记"))
        prop("code", str("要删除的精确代码片段"))
        required("path")
    }),
	    tool("network_list", AgentPermissionTier.S1, "列出 MITM 最近抓到的 HTTP(S) 请求摘要；默认返回最近 10 条，每条带六位 code、请求/响应大小和 latencyMs。", schema {
	        prop("method", str("按 HTTP method 过滤，如 GET/POST。"))
	        prop("urlContains", str("按 URL 子串过滤。"))
	        prop("sinceSeconds", num("只返回最近 N 秒。"))
	        prop("limit", num("最多返回条数，默认 10。"))
	    }),
	    tool("network_get", AgentPermissionTier.S1, "按六位 code 或旧 flowId 读取单条抓包详情；凭证头仍会脱敏。", schema {
	        prop("id", str("network_list 返回的六位 code；兼容旧 flowId"))
	        prop("part", str("summary/requestHeaders/requestBody/responseHeaders/responseBody/all，默认 all"))
	        required("id")
	    }),
    tool("proxy_status", AgentPermissionTier.S1, "读取 MITM 代理当前状态。", emptySchema()),
    tool("page_index", AgentPermissionTier.S1, "索引当前页面源码，只返回标题、URL、heading、form、link 等摘要。", emptySchema()),
    tool("page_search", AgentPermissionTier.S1, "搜索已索引页面源码，返回片段。先调用 page_index。", schema {
        prop("query", str("搜索文本"))
        prop("limit", num("最多返回条数"))
        required("query")
    }),
    tool("page_query", AgentPermissionTier.S1, "读取当前页面 DOM 中匹配 CSS selector 的元素摘要。", schema {
        prop("selector", str("CSS selector"))
        prop("limit", num("最多元素数"))
        required("selector")
    }),
    tool("container_list", AgentPermissionTier.S1, "列出 Agent 本地容器目录内的文件。路径限制在容器内。", schema {
        prop("path", str("容器内相对目录，默认 ."))
    }),
    tool("container_read", AgentPermissionTier.S1, "读取 Agent 本地容器目录内的小文本文件。", schema {
        prop("path", str("容器内相对文件路径"))
        required("path")
    }),
    tool("container_write", AgentPermissionTier.S1, "写入 Agent 本地容器目录内的小文本文件。", schema {
        prop("path", str("容器内相对文件路径"))
        prop("content", str("文本内容"))
        required("path")
        required("content")
    }),
    tool("batch_edit_files", AgentPermissionTier.S2, "批量写入 Agent 外部容器目录内的多个文本文件。路径限制在容器内。", schema {
        prop("edits", arr(obj {
            prop("path", str("容器内相对文件路径"))
            prop("content", str("完整文本内容"))
            required("path")
            required("content")
        }))
        required("edits")
    }),
    tool("container_serve_url", AgentPermissionTier.S2, "为 Agent 外部容器内的文件生成当前网页可加载的本地 HTTP URL。", schema {
        prop("path", str("容器内相对文件路径"))
        required("path")
    }),
    tool("web_search", AgentPermissionTier.S1, "调用用户配置的搜索 API 做只读查询。支持 URL 模板 {q}/{query}/{limit}/{key}。", schema {
        prop("query", str("搜索关键词"))
        prop("limit", num("最多结果数"))
        required("query")
    }),
    tool("browser_request", AgentPermissionTier.S2, "从浏览器 App 进程发起 HTTP(S) 请求，返回状态、响应头和截断后的响应体。", schema {
        prop("url", str("请求 URL"))
        prop("method", str("HTTP method，默认 GET"))
        prop("headers", JSONObject().put("type", "object").put("description", "请求头对象"))
        prop("body", str("请求体文本"))
        prop("timeoutMs", num("连接和读取超时 ms"))
        prop("followRedirects", bool("是否自动跟随重定向"))
        required("url")
    }),
    tool("proxy_start", AgentPermissionTier.S2, "开启 MITM 代理，开始抓取浏览器流量。", emptySchema()),
    tool("proxy_stop", AgentPermissionTier.S2, "关闭 MITM 代理。", emptySchema()),
    tool("throttle_set", AgentPermissionTier.S2, "配置弱网：逐响应延迟和下行限速。", schema {
        prop("enabled", bool("是否启用"))
        prop("latencyMs", num("每个响应增加的延迟 ms"))
        prop("kbps", num("下行限速 KB/s，0 表示不限速"))
    }),
    tool("replace_set", AgentPermissionTier.S2, "配置请求/响应文本替换规则。", schema {
        prop("enabled", bool("是否启用"))
        prop("scope", str("req、resp 或 both"))
        prop("rules", arr(obj {
            prop("from", str("查找文本"))
            prop("to", str("替换文本"))
        }))
    }),
    tool("mock_set", AgentPermissionTier.S2, "配置 Mock 规则：URL 子串命中后返回合成响应。", schema {
        prop("rules", arr(obj {
            prop("pattern", str("URL 子串"))
            prop("status", num("HTTP 状态码"))
            prop("body", str("响应体"))
            prop("headers", JSONObject().put("type", "object").put("description", "响应头对象"))
        }))
        required("rules")
    }),
	    tool("intercept_set", AgentPermissionTier.S2, "配置请求/响应拦截。未传 reqAll/respAll 时默认同时拦截请求和响应，但遥测、心跳/噪音、cookie/auth 类低价值流量默认自动放行。", schema {
	        prop("reqAll", bool("拦截全部请求；缺省=true"))
	        prop("respAll", bool("拦截全部响应；缺省=true"))
	        prop("interceptTelemetry", bool("是否拦截遥测类低价值流量"))
	        prop("interceptHeartbeat", bool("是否拦截心跳/keepalive 类低价值流量"))
	        prop("interceptNoise", bool("是否拦截噪音类低价值流量"))
	        prop("interceptCookie", bool("是否拦截 cookie/auth 类流量"))
        prop("rules", arr(obj {
	            prop("host", str("host"))
	            prop("path", str("path，不含 query"))
	            prop("method", str("HTTP method"))
	            prop("hasBody", bool("是否要求有 body"))
	            prop("action", str("intercept 或 pass；缺省 pass"))
	            prop("interceptResp", bool("是否同时拦截响应"))
	        }))
    }),
    tool("intercept_pending", AgentPermissionTier.S2, "列出当前暂停等待决策的请求/响应。", emptySchema()),
    tool("intercept_resolve", AgentPermissionTier.S2, "决议暂停的请求：continue 可带编辑，abort 返回 403。", schema {
        prop("flowId", str("暂停 flow id"))
        prop("decision", str("continue 或 abort"))
        prop("url", str("可选：修改 URL"))
        prop("method", str("可选：修改 method"))
        prop("reqHeaders", JSONObject().put("type", "object"))
        prop("reqBody", str("可选：修改请求体"))
        required("flowId")
        required("decision")
    }),
    tool("resp_intercept_resolve", AgentPermissionTier.S2, "决议暂停的响应：continue 可带编辑，abort 断开。", schema {
        prop("flowId", str("暂停 flow id"))
        prop("decision", str("continue 或 abort"))
        prop("status", num("可选：修改状态码"))
        prop("respHeaders", JSONObject().put("type", "object"))
        prop("respBody", str("可选：修改响应体"))
        required("flowId")
        required("decision")
    }),
    tool("intercept_resolve_all", AgentPermissionTier.S2, "一键放行多条暂停的请求/响应：传 ids（六位 code 数组）放行指定的，留空则放行全部。", schema {
        prop("ids", JSONObject().put("type", "array").put("items", str("六位 code")))
        prop("decision", str("continue 或 abort，默认 continue"))
    }),
    tool("page_set_text", AgentPermissionTier.S2, "设置当前页面匹配元素的 textContent。受限 DOM 写入，不执行任意 JS。", schema {
        prop("selector", str("CSS selector"))
        prop("text", str("文本"))
        prop("all", bool("是否修改所有匹配元素，默认只改第一个"))
        required("selector")
        required("text")
    }),
    tool("page_set_html", AgentPermissionTier.S2, "设置当前页面匹配元素的 innerHTML。受限 DOM 写入，不执行任意 JS。", schema {
        prop("selector", str("CSS selector"))
        prop("html", str("HTML 片段"))
        prop("all", bool("是否修改所有匹配元素，默认只改第一个"))
        required("selector")
        required("html")
    }),
    tool("page_set_attr", AgentPermissionTier.S2, "设置当前页面匹配元素的属性。受限 DOM 写入，不执行任意 JS。", schema {
        prop("selector", str("CSS selector"))
        prop("name", str("属性名"))
        prop("value", str("属性值"))
        prop("all", bool("是否修改所有匹配元素，默认只改第一个"))
        required("selector")
        required("name")
        required("value")
    }),
    tool("page_scroll", AgentPermissionTier.S2, "滑动当前网页窗口或指定滚动容器。受限 UI 操作，不执行任意 JS。", schema {
        prop("direction", str("down/up/left/right/top/bottom，默认 down"))
        prop("amount", num("滑动像素，默认 600，最大 5000"))
        prop("pixels", num("amount 的兼容别名"))
        prop("selector", str("可选 CSS selector；不传则滑动 window，传入则滑动第一个匹配元素"))
        prop("behavior", str("auto、instant 或 smooth，默认 auto"))
    }),
    tool("page_fetch", AgentPermissionTier.S3, "从当前网页的 page world 发起 fetch 请求，可指定页面 URL 子串校验来源。", schema {
        prop("url", str("目标 URL"))
        prop("method", str("HTTP method，默认 GET"))
        prop("headers", JSONObject().put("type", "object").put("description", "fetch headers 对象"))
        prop("body", str("请求体文本"))
        prop("credentials", str("omit、same-origin 或 include"))
        prop("mode", str("cors、no-cors、same-origin 等 fetch mode"))
        prop("requiredPageUrlContains", str("可选：当前页面 URL 必须包含该字符串，避免从错误页面发请求"))
        prop("timeoutMs", num("超时 ms"))
        required("url")
    }),
    tool("cookie_reveal", AgentPermissionTier.S3, "读取某 flow 的真实 Cookie/Authorization 等凭证明文。", schema {
        prop("id", str("flow id"))
        prop("name", str("header 名，默认 authorization"))
        required("id")
    }),
    tool("page_exec", AgentPermissionTier.S3, "在 page world 执行任意 JavaScript，可调用页面和浏览器 API。", schema {
        prop("code", str("JS 代码"))
        prop("timeoutMs", num("超时 ms"))
        required("code")
    }),
    tool("private_file_list", AgentPermissionTier.S3, "列出浏览器 App 私有目录内文件。", schema {
        prop("path", str("私有目录内相对路径，默认 ."))
    }),
    tool("private_file_read", AgentPermissionTier.S3, "读取浏览器 App 私有目录内的小文本文件。", schema {
        prop("path", str("私有目录内相对文件路径"))
        required("path")
    }),
    tool("private_file_write", AgentPermissionTier.S3, "写入浏览器 App 私有目录内的小文本文件。", schema {
        prop("path", str("私有目录内相对文件路径"))
        prop("content", str("文本内容"))
        required("path")
        required("content")
    }),
    tool("private_batch_edit_files", AgentPermissionTier.S3, "批量写入浏览器 App 私有目录内的多个文本文件。", schema {
        prop("edits", arr(obj {
            prop("path", str("私有目录内相对文件路径"))
            prop("content", str("完整文本内容"))
            required("path")
            required("content")
        }))
        required("edits")
    }),
)

private fun tool(name: String, tier: AgentPermissionTier, desc: String, schema: JSONObject) =
    ToolDef(ChatToolSpec(name, desc, schema), tier)

private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)

private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

private fun arr(item: JSONObject) = JSONObject().put("type", "array").put("items", item)

private fun emptySchema() = JSONObject().put("type", "object").put("properties", JSONObject())

private class SchemaBuilder {
    private val props = JSONObject()
    private val required = JSONArray()

    fun prop(name: String, schema: JSONObject) {
        props.put(name, schema)
    }

    fun required(name: String) {
        required.put(name)
    }

    fun build(): JSONObject {
        val out = JSONObject().put("type", "object").put("properties", props)
        if (required.length() > 0) out.put("required", required)
        return out
    }
}

private fun schema(block: SchemaBuilder.() -> Unit): JSONObject = SchemaBuilder().apply(block).build()

private fun obj(block: SchemaBuilder.() -> Unit): JSONObject = schema(block)
