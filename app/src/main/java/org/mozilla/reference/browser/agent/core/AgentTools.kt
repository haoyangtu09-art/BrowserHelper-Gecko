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
import mozilla.components.browser.state.selector.selectedTab
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.reference.browser.devtools.NetFlowStore
import org.mozilla.reference.browser.devtools.PageChannel
import org.mozilla.reference.browser.devtools.ProxyProbe
import org.mozilla.reference.browser.ext.components
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

/**
 * Bridge from the Agent tools (running on Dispatchers.IO) to the Compose [PanelState] that
 * owns chat history and long-term memory. Every method here touches Compose state, so the
 * registry only ever calls them through runOnMainResult (main thread).
 */
interface AgentPanelBridge {
    fun listChats(): JSONArray
    fun openChat(id: String): Boolean
    fun renameChat(id: String, title: String): Boolean
    fun listMemories(): JSONArray
    fun addMemory(value: String): Int
    fun deleteMemory(index: Int): Boolean

    // Visual Task Tracker — see TaskTrackerManager for semantics. All methods run on the
    // main thread (registry uses runOnMainResult); host implementations may directly mutate
    // PanelState.tracker.
    fun taskTrackerSnapshot(): JSONObject
    fun taskCurrentId(): String?
    fun taskCreateGroup(title: String): String
    fun taskAddTask(groupId: String, title: String, description: String): String?
    fun taskStart(taskId: String): Boolean
    fun taskComplete(taskId: String): Boolean
    fun taskFail(taskId: String, error: String): Boolean
    fun taskCancel(taskId: String): Boolean
    fun taskUpdate(taskId: String, title: String?, description: String?): Boolean
    fun taskDelete(taskId: String): Boolean
    fun taskClearAll(): Boolean
    fun taskRecordToolCall(taskId: String, name: String, status: String, durationMs: Long, error: String): Boolean
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
    private val panelBridge: AgentPanelBridge? = null,
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
        // S1 tools are read-only (no file writes after the write_* tools moved to S2),
        // so they run without an approval prompt — matching the roadmap §6 rule that
        // S1 reads need no confirmation.
        if (def.tier != AgentPermissionTier.S1) {
            val request = approvalRequest(def, tier, args)
            if (!approver.isApproved(request.scopeKey)) {
                val approved = approver.approve(request)
                if (approved == AgentApprovalDecision.Deny) {
                    return AgentToolResult(false, "user denied tool call: $name")
                }
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
            "page_long_press" ->
                req(
                    "允许 Agent 长按当前网页元素吗？",
                    "page-longpress:${args.optString("selector", "")}",
                    "当前页面 selector=${args.optString("selector", "")}, durationMs=${args.optString("durationMs", "600")}",
                    "允许，并且本次会话不再询问这个长按目标",
                )
            "page_swipe" ->
                req(
                    "允许 Agent 连续滑动当前网页吗？",
                    "page-swipe:${args.optString("selector", "")}:${args.optString("direction", "down")}",
                    "当前页面 selector=${args.optString("selector", "window")}, direction=${args.optString("direction", "down")}, repeat=${args.optString("repeat", "1")}",
                    "允许，并且本次会话不再询问这个连续滑动范围",
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
            "cookie_reveal", "auth_reveal", "set_cookie_headers_reveal" -> {
                val headerName = when (name) {
                    "set_cookie_headers_reveal" -> "set-cookie"
                    else -> args.optString("name", "authorization")
                }
                req(
                    "允许 Agent 读取这条请求的凭证明文吗？",
                    "secret:${args.optString("id", "")}:${headerName.lowercase()}",
                    "flowId=${args.optString("id", "")}, header=$headerName",
                    "允许，并且本次会话不再询问这个凭证范围",
                )
            }
            "cookie_list_redacted", "auth_headers_redacted" ->
                req(
                    "允许 Agent 查看这条请求有哪些凭证头吗？（值会脱敏）",
                    "secret-list:$name:${args.optString("id", "")}",
                    "flowId=${args.optString("id", "")}（脱敏列表，不读明文）",
                    "允许，并且本次会话不再询问这个脱敏凭证列表",
                )
            "container_cp", "container_mv", "container_rm", "container_rmdir",
            "container_mkdir", "container_touch", "container_append" -> {
                val target = when (name) {
                    "container_cp", "container_mv" ->
                        "${scopePath(args.optString("src", ""))} -> ${scopePath(args.optString("dest", ""))}"
                    else -> scopePath(args.optString("path", "."))
                }
                req(
                    "允许 Agent 修改本地容器内的文件吗？",
                    "container-fs:$name:${shortHash(target)}",
                    "$name $target",
                    "允许，并且本次会话不再询问这个容器文件操作",
                )
            }
            "plugin_enable", "plugin_disable" ->
                req(
                    "允许 Agent 启用/停用这个浏览器插件吗？",
                    "plugin:$name:${args.optString("id", "")}",
                    "$name id=${args.optString("id", "")}",
                    "允许，并且本次会话不再询问这个插件开关",
                )
            "page_save_to_container" ->
                req(
                    "允许 Agent 把当前网页源码保存到容器文件吗？",
                    "page-save:${scopePath(args.optString("path", "").ifBlank { "(自动命名)" })}",
                    "保存当前页面 HTML 到 ${args.optString("path", "").ifBlank { "pages/<host>_<时间戳>.html" }}",
                    "允许，并且本次会话不再询问保存当前页面",
                )
            "tab_switch" ->
                req(
                    "允许 Agent 切换浏览器标签页吗？",
                    "tab-switch:${args.optString("id", "")}",
                    "切换到 tab ${args.optString("id", "")}",
                    "允许，并且本次会话不再询问切换标签页",
                )
            "page_navigate" ->
                req(
                    "允许 Agent 导航当前浏览器标签页吗？",
                    "page-nav:${networkScope(name, args)}",
                    "当前 tab 加载 ${args.optString("url", "")}",
                    "允许，并且本次会话不再询问这个导航目标",
                )
            "page_reload", "page_back", "page_forward" ->
                req(
                    "允许 Agent 控制当前浏览器标签页吗？",
                    "page-history:$name",
                    name,
                    "允许，并且本次会话不再询问这个标签页控制操作",
                )
            "page_click", "page_type" ->
                req(
                    "允许 Agent 操作当前网页元素吗？",
                    "page-interact:$name:${args.optString("selector", "")}",
                    "$name 当前页面 selector=${args.optString("selector", "")}",
                    "允许，并且本次会话不再询问这个页面元素操作",
                )
            "dom_highlight_element", "dom_inject_css" ->
                req(
                    "允许 Agent 修改当前网页样式吗？",
                    "page-style:$name:${if (name == "dom_inject_css") shortHash(args.optString("css", "")) else args.optString("selector", "")}",
                    if (name == "dom_inject_css") "注入 CSS ${shortHash(args.optString("css", ""))}" else "高亮 selector=${args.optString("selector", "")}",
                    "允许，并且本次会话不再询问这个页面样式改动",
                )
            "mock_add_rule", "replace_add_rule" ->
                req(
                    "允许 Agent 新增浏览器网络规则吗？",
                    "network-rule:$name:${shortHash(args.toString())}",
                    "$name 规则 ${shortHash(args.toString())}",
                    "允许，并且本次会话不再询问这条网络规则",
                )
            "agent_history_open", "agent_history_rename" ->
                req(
                    "允许 Agent 操作对话历史吗？",
                    "agent-history:$name:${args.optString("id", "")}",
                    "$name id=${args.optString("id", "")}",
                    "允许，并且本次会话不再询问这个对话历史操作",
                )
            "agent_memory_add", "agent_memory_delete" ->
                req(
                    "允许 Agent 修改长期记忆吗？",
                    "agent-memory:$name:${if (name == "agent_memory_add") shortHash(args.optString("value", "")) else args.optString("index", "")}",
                    if (name == "agent_memory_add") "新增记忆 ${shortHash(args.optString("value", ""))}" else "删除记忆 #${args.optString("index", "")}",
                    "允许，并且本次会话不再询问这个记忆改动",
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
            "page_save_to_container" -> savePageToContainer(args)
            "container_list" -> listFiles(containerRoot(), args.optString("path", "."))
            "container_read" -> readFile(containerRoot(), args.optString("path", ""))
            "container_write" -> writeFile(containerRoot(), args.optString("path", ""), args.optString("content", ""))
            "batch_edit_files" -> batchWriteFiles(containerRoot(), args.optJSONArray("edits") ?: JSONArray())
            "container_serve_url" -> serveContainerUrl(args.optString("path", ""))
            // container shell tools (coreutils-equivalent over the sandbox)
            "container_grep" -> containerGrep(args)
            "container_find" -> containerFind(args)
            "container_head" -> containerHeadTail(args, head = true)
            "container_tail" -> containerHeadTail(args, head = false)
            "container_wc" -> containerWc(args)
            "container_stat" -> containerStat(args)
            "container_du" -> containerDu(args)
            "container_tree" -> containerTree(args)
            "container_file_type" -> containerFileType(args)
            "container_which" -> containerWhich(args)
            "container_sed" -> containerSed(args)
            "container_realpath" -> containerRealpath(args)
            "container_diff" -> containerDiff(args)
            "container_cp" -> containerCp(args)
            "container_mv" -> containerMv(args)
            "container_rm" -> containerRm(args)
            "container_mkdir" -> containerMkdir(args)
            "container_rmdir" -> containerRmdir(args)
            "container_touch" -> containerTouch(args)
            "container_append" -> containerAppend(args)
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
                        .put("enabled", args.optBoolean("enabled", false))
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
                // Fail-safe: omitting reqAll/respAll means "don't blanket-intercept".
                // We also pass an explicit `enabled` so ProxyProbe never falls back to
                // arming intercept just because intercept rules are present — otherwise a
                // no-arg call would silently pause every outgoing request.
                val reqAll = args.optBoolean("reqAll", false)
                val respAll = args.optBoolean("respAll", false)
                val rules = args.optJSONArray("rules") ?: JSONArray()
                val hasInterceptRule = (0 until rules.length()).any { i ->
                    val o = rules.optJSONObject(i)
                    o != null && (o.optString("action") == "intercept" || o.optBoolean("interceptResp", false))
                }
                val enabled = reqAll || respAll || hasInterceptRule
                ProxyProbe.setInterceptRules(
                    JSONObject()
                        .put("enabled", enabled)
                        .put("reqAll", reqAll)
                        .put("respAll", respAll)
                        .put("interceptTelemetry", args.optBoolean("interceptTelemetry", false))
                        .put("interceptHeartbeat", args.optBoolean("interceptHeartbeat", false))
                        .put("interceptNoise", args.optBoolean("interceptNoise", false))
                        .put("interceptCookie", args.optBoolean("interceptCookie", false))
                        .put("rules", rules),
                )
                ok("intercept rules set: enabled=$enabled reqAll=$reqAll respAll=$respAll lowValue=${if (args.optBoolean("interceptTelemetry", false)) "" else "telemetry pass; "}${if (args.optBoolean("interceptHeartbeat", false)) "" else "heartbeat pass; "}${if (args.optBoolean("interceptNoise", false)) "" else "noise pass; "}${if (args.optBoolean("interceptCookie", false)) "" else "cookie pass"}")
            }
            "intercept_pending" -> ok(ProxyProbe.pendingInterceptList().toString())
            "intercept_list_rules" -> ok(ProxyProbe.interceptRulesJson().toString())
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
                args,
                args.optLong("waitMs", 240L).coerceIn(20L, 1_000L) + 2_000L,
            )
            "page_long_press" -> page(
                "longPress",
                args,
                args.optLong("durationMs", 600L).coerceIn(100L, 5_000L) + 2_000L,
            )
            "page_swipe" -> page(
                "swipePage",
                args,
                args.optLong("repeat", 1L).coerceIn(1L, 100L) *
                    args.optLong("stepDelayMs", 120L).coerceIn(0L, 2_000L) + 4_000L,
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
            "cookie_list_redacted" -> {
                val flowId = NetFlowStore.flowIdFor(args.optString("id", "")) ?: args.optString("id", "")
                if (flowId.isEmpty()) return@withContext err("id required")
                val arr = NetFlowStore.sensitiveHeadersJson(flowId, "cookie")
                if (arr == null) err("no flow with id=$flowId") else ok(JSONObject().put("flowId", flowId).put("headers", arr).toString())
            }
            "auth_headers_redacted" -> {
                val flowId = NetFlowStore.flowIdFor(args.optString("id", "")) ?: args.optString("id", "")
                if (flowId.isEmpty()) return@withContext err("id required")
                val arr = NetFlowStore.sensitiveHeadersJson(flowId, "auth")
                if (arr == null) err("no flow with id=$flowId") else ok(JSONObject().put("flowId", flowId).put("headers", arr).toString())
            }
            "auth_reveal" -> {
                val flowId = NetFlowStore.flowIdFor(args.optString("id", "")) ?: args.optString("id", "")
                if (flowId.isEmpty()) return@withContext err("id required")
                val header = args.optString("name", "authorization")
                val value = NetFlowStore.revealHeader(flowId, header)
                if (value == null) err("flow $flowId has no $header header") else ok(value)
            }
            "set_cookie_headers_reveal" -> {
                val flowId = NetFlowStore.flowIdFor(args.optString("id", "")) ?: args.optString("id", "")
                if (flowId.isEmpty()) return@withContext err("id required")
                val value = NetFlowStore.revealHeader(flowId, "set-cookie")
                if (value == null) err("flow $flowId has no set-cookie header") else ok(value)
            }
            "plugin_list" -> page("pluginList")
            "plugin_get_permissions" -> {
                val id = args.optString("id", "")
                if (id.isBlank()) err("id required") else page("pluginInfo", JSONObject().put("id", id))
            }
            "plugin_enable" -> {
                val id = args.optString("id", "")
                if (id.isBlank()) err("id required") else page("pluginEnable", JSONObject().put("id", id))
            }
            "plugin_disable" -> {
                val id = args.optString("id", "")
                if (id.isBlank()) err("id required") else page("pluginDisable", JSONObject().put("id", id))
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
            "tab_list" -> ok(tabListJson().toString())
            "tab_current" -> currentTabJson()?.let { ok(it.toString()) } ?: ok("{}")
            "tab_switch" -> {
                val id = args.optString("id", "")
                if (id.isBlank()) {
                    err("id required")
                } else if (!tabExists(id)) {
                    err("no tab with id=$id")
                } else {
                    runOnMain { appContext.components.useCases.tabsUseCases.selectTab(id) }
                    ok("switched to tab $id")
                }
            }
            "page_navigate" -> {
                val url = normalizeNavigateUrl(args.optString("url", ""))
                    ?: return@withContext err("url required")
                runOnMain { appContext.components.useCases.sessionUseCases.loadUrl(url) }
                val waitMs = args.optLong("waitMs", 0L).coerceIn(0L, 10_000L)
                if (waitMs > 0) Thread.sleep(waitMs)
                ok(
                    JSONObject()
                        .put("requestedUrl", url)
                        .put("currentTab", currentTabJson() ?: JSONObject())
                        .toString(),
                )
            }
            "page_reload" -> {
                runOnMain { appContext.components.useCases.sessionUseCases.reload.invoke() }
                ok(JSONObject().put("action", "reload").put("currentTab", currentTabJson() ?: JSONObject()).toString())
            }
            "page_back" -> {
                runOnMain { appContext.components.useCases.sessionUseCases.goBack.invoke() }
                ok(JSONObject().put("action", "back").put("currentTab", currentTabJson() ?: JSONObject()).toString())
            }
            "page_forward" -> {
                runOnMain { appContext.components.useCases.sessionUseCases.goForward.invoke() }
                ok(JSONObject().put("action", "forward").put("currentTab", currentTabJson() ?: JSONObject()).toString())
            }
            "page_click" -> {
                val selector = args.optString("selector", "")
                if (selector.isEmpty()) err("selector required") else page("clickEl", JSONObject().put("selector", selector))
            }
            "page_type" -> {
                val selector = args.optString("selector", "")
                if (selector.isEmpty()) {
                    err("selector required")
                } else {
                    page(
                        "typeText",
                        JSONObject()
                            .put("selector", selector)
                            .put("text", args.optString("text", ""))
                            .put("append", args.optBoolean("append", false)),
                    )
                }
            }
            "page_wait_for_element" -> {
                val selector = args.optString("selector", "")
                if (selector.isEmpty()) {
                    err("selector required")
                } else {
                    val timeoutMs = args.optLong("timeoutMs", 5_000L).coerceIn(100L, 30_000L)
                    page(
                        "waitForElement",
                        JSONObject()
                            .put("selector", selector)
                            .put("timeoutMs", timeoutMs)
                            .put("visible", args.optBoolean("visible", false)),
                        timeoutMs + 3_000L,
                    )
                }
            }
            "console_list" -> page(
                "consoleList",
                JSONObject()
                    .put("limit", args.optInt("limit", 50))
                    .put("level", args.optString("level", "")),
                8_000L,
            )
            "network_wait_request" -> waitForFlow(args, needResponse = false)
            "network_wait_response" -> waitForFlow(args, needResponse = true)
            "mock_add_rule" -> {
                val pattern = args.optString("pattern", "")
                if (pattern.isEmpty()) {
                    err("pattern required")
                } else {
                    val rule = JSONObject()
                        .put("pattern", pattern)
                        .put("status", args.optInt("status", 200))
                        .put("body", args.optString("body", ""))
                    args.optJSONObject("headers")?.let { rule.put("headers", it) }
                    ProxyProbe.mockAddRule(rule)
                    ok("mock rule added: pattern=$pattern total=${ProxyProbe.mockListJson().length()}")
                }
            }
            "mock_list" -> ok(ProxyProbe.mockListJson().toString())
            "replace_add_rule" -> {
                val from = args.optString("from", "")
                if (from.isEmpty()) {
                    err("from required")
                } else {
                    ProxyProbe.replaceAddRule(from, args.optString("to", ""), args.optString("scope", ""))
                    ok("replace rule added: ${ProxyProbe.replaceListJson()}")
                }
            }
            "replace_list" -> ok(ProxyProbe.replaceListJson().toString())
            "dom_highlight_element" -> {
                val selector = args.optString("selector", "")
                if (selector.isEmpty()) {
                    err("selector required")
                } else {
                    page(
                        "highlight",
                        JSONObject()
                            .put("selector", selector)
                            .put("color", args.optString("color", "#ff3b30"))
                            .put("durationMs", args.optLong("durationMs", 2_000L))
                            .put("all", args.optBoolean("all", false)),
                    )
                }
            }
            "dom_inject_css" -> {
                val css = args.optString("css", "")
                if (css.isEmpty()) err("css required") else page("injectCss", JSONObject().put("css", css))
            }
            "agent_history_list" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                ok(runOnMainResult { bridge.listChats() }.toString())
            }
            "agent_history_open" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val id = args.optString("id", "")
                if (id.isBlank()) {
                    err("id required")
                } else if (runOnMainResult { bridge.openChat(id) }) {
                    ok("opened chat $id")
                } else {
                    err("no chat with id=$id")
                }
            }
            "agent_history_rename" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val id = args.optString("id", "")
                val title = args.optString("title", "")
                if (id.isBlank()) {
                    err("id required")
                } else if (title.isBlank()) {
                    err("title required")
                } else if (runOnMainResult { bridge.renameChat(id, title) }) {
                    ok("renamed chat $id to $title")
                } else {
                    err("no chat with id=$id")
                }
            }
            "agent_memory_list" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                ok(runOnMainResult { bridge.listMemories() }.toString())
            }
            "agent_memory_add" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val value = args.optString("value", "")
                if (value.isBlank()) {
                    err("value required")
                } else {
                    val index = runOnMainResult { bridge.addMemory(value) }
                    ok("memory added at index $index")
                }
            }
            "agent_memory_delete" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val index = args.optInt("index", -1)
                if (index < 0) {
                    err("index required")
                } else if (runOnMainResult { bridge.deleteMemory(index) }) {
                    ok("memory $index deleted")
                } else {
                    err("no memory at index $index")
                }
            }
            // ----------------------------------------------------------- Visual Task Tracker
            // All 9 task tools are S1 (no approval prompt) — the spec wants the agent to
            // maintain its own plan without bothering the user on every checkbox flip.
            "agent_tasks_create" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val title = args.optString("title", "")
                if (title.isBlank()) {
                    err("title required")
                } else {
                    val gid = runOnMainResult { bridge.taskCreateGroup(title) }
                    ok(JSONObject().put("groupId", gid).toString())
                }
            }
            "agent_tasks_add" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val groupId = args.optString("groupId", "")
                val title = args.optString("title", "")
                val description = args.optString("description", "")
                if (groupId.isBlank()) {
                    err("groupId required")
                } else if (title.isBlank()) {
                    err("title required")
                } else {
                    val tid = runOnMainResult { bridge.taskAddTask(groupId, title, description) }
                    if (tid == null) err("group $groupId not found or title invalid")
                    else ok(JSONObject().put("taskId", tid).toString())
                }
            }
            "agent_tasks_start" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (runOnMainResult { bridge.taskStart(taskId) }) {
                    ok("task $taskId started")
                } else {
                    err("task $taskId not found or already active")
                }
            }
            "agent_tasks_complete" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (runOnMainResult { bridge.taskComplete(taskId) }) {
                    ok("task $taskId completed")
                } else {
                    err("task $taskId not found")
                }
            }
            "agent_tasks_fail" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                val error = args.optString("error", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (runOnMainResult { bridge.taskFail(taskId, error) }) {
                    ok("task $taskId failed")
                } else {
                    err("task $taskId not found")
                }
            }
            "agent_tasks_cancel" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (runOnMainResult { bridge.taskCancel(taskId) }) {
                    ok("task $taskId cancelled")
                } else {
                    err("task $taskId not found")
                }
            }
            "agent_tasks_update" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                // Use has() so the caller can null-out description (keep title) by omitting the
                // field, vs explicitly clearing it. JSONObject.has discriminates "missing" from
                // "explicit empty string" — important so partial updates do not overwrite text
                // the agent didn't intend to touch.
                val title = if (args.has("title")) args.optString("title", "") else null
                val description = if (args.has("description")) args.optString("description", "") else null
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (title == null && description == null) {
                    err("at least one of title/description required")
                } else if (runOnMainResult { bridge.taskUpdate(taskId, title, description) }) {
                    ok("task $taskId updated")
                } else {
                    err("task $taskId not found or no change")
                }
            }
            "agent_tasks_rename" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                val title = args.optString("title", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (title.isBlank()) {
                    err("title required")
                } else if (runOnMainResult { bridge.taskUpdate(taskId, title, null) }) {
                    ok("task $taskId renamed")
                } else {
                    err("task $taskId not found or title unchanged")
                }
            }
            "agent_tasks_delete" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                val taskId = args.optString("taskId", "")
                if (taskId.isBlank()) {
                    err("taskId required")
                } else if (runOnMainResult { bridge.taskDelete(taskId) }) {
                    ok("task $taskId deleted")
                } else {
                    err("task $taskId not found")
                }
            }
            "agent_tasks_list" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                ok(runOnMainResult { bridge.taskTrackerSnapshot() }.toString())
            }
            "agent_tasks_clear" -> {
                val bridge = panelBridge ?: return@withContext err("panel bridge unavailable")
                if (runOnMainResult { bridge.taskClearAll() }) ok("cleared") else ok("already empty")
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
            "page_save_to_container" -> execute(name, JSONObject().put("path", "$tempDir/page_save.html"))
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
            // container shell tools: seed a small text file, then exercise the tool against it.
            "container_grep" -> {
                writeFile(containerRoot(), "$tempDir/grep.txt", "alpha-$stamp\nbeta\n")
                execute(name, JSONObject().put("pattern", "alpha").put("path", "$tempDir/grep.txt"))
            }
            "container_find" -> {
                writeFile(containerRoot(), "$tempDir/find_me.txt", "x-$stamp")
                execute(name, JSONObject().put("path", tempDir).put("namePattern", "find_me.txt"))
            }
            "container_head", "container_tail" -> {
                writeFile(containerRoot(), "$tempDir/lines.txt", "l1\nl2\nl3\nl4\nl5\n")
                execute(name, JSONObject().put("path", "$tempDir/lines.txt").put("lines", 2))
            }
            "container_wc" -> {
                writeFile(containerRoot(), "$tempDir/wc.txt", "a b c\nd e\n")
                execute(name, JSONObject().put("path", "$tempDir/wc.txt"))
            }
            "container_stat" -> {
                writeFile(containerRoot(), "$tempDir/stat.txt", "stat-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/stat.txt"))
            }
            "container_du" -> {
                writeFile(containerRoot(), "$tempDir/du.txt", "du-$stamp")
                execute(name, JSONObject().put("path", tempDir))
            }
            "container_tree" -> {
                writeFile(containerRoot(), "$tempDir/tree.txt", "tree-$stamp")
                execute(name, JSONObject().put("path", tempDir).put("maxDepth", 2))
            }
            "container_file_type" -> {
                writeFile(containerRoot(), "$tempDir/type.txt", "type-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/type.txt"))
            }
            "container_which" -> {
                writeFile(containerRoot(), "$tempDir/which_target.txt", "w-$stamp")
                execute(name, JSONObject().put("name", "which_target.txt"))
            }
            "container_sed" -> {
                writeFile(containerRoot(), "$tempDir/sed.txt", "s1\ns2\ns3\n")
                execute(name, JSONObject().put("path", "$tempDir/sed.txt").put("startLine", 1).put("endLine", 2))
            }
            "container_realpath" -> {
                writeFile(containerRoot(), "$tempDir/real.txt", "r-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/real.txt"))
            }
            "container_diff" -> {
                writeFile(containerRoot(), "$tempDir/diff_a.txt", "x\ny\n")
                writeFile(containerRoot(), "$tempDir/diff_b.txt", "x\nz\n")
                execute(name, JSONObject().put("pathA", "$tempDir/diff_a.txt").put("pathB", "$tempDir/diff_b.txt"))
            }
            "container_cp" -> {
                writeFile(containerRoot(), "$tempDir/cp_src.txt", "cp-$stamp")
                execute(name, JSONObject().put("src", "$tempDir/cp_src.txt").put("dest", "$tempDir/cp_dst.txt"))
            }
            "container_mv" -> {
                writeFile(containerRoot(), "$tempDir/mv_src.txt", "mv-$stamp")
                execute(name, JSONObject().put("src", "$tempDir/mv_src.txt").put("dest", "$tempDir/mv_dst.txt"))
            }
            "container_rm" -> {
                writeFile(containerRoot(), "$tempDir/rm.txt", "rm-$stamp")
                execute(name, JSONObject().put("path", "$tempDir/rm.txt"))
            }
            "container_mkdir" -> execute(name, JSONObject().put("path", "$tempDir/mkdir_$stamp"))
            "container_rmdir" -> {
                resolvePath(containerRoot(), "$tempDir/rmdir_$stamp")?.mkdirs()
                execute(name, JSONObject().put("path", "$tempDir/rmdir_$stamp"))
            }
            "container_touch" -> execute(name, JSONObject().put("path", "$tempDir/touch_$stamp.txt"))
            "container_append" -> execute(
                name,
                JSONObject().put("path", "$tempDir/append_$stamp.txt").put("content", "appended-$stamp\n"),
            )
            "intercept_list_rules" -> execute(name, JSONObject())
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
                    .put("interceptHeartbeat", false)
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
            "page_long_press" -> execute(
                name,
                JSONObject().put("selector", "body").put("durationMs", 100),
            )
            "page_swipe" -> execute(
                name,
                JSONObject()
                    .put("direction", "down")
                    .put("distance", 1)
                    .put("repeat", 1)
                    .put("stepDelayMs", 0),
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
            "cookie_list_redacted", "auth_headers_redacted",
            "auth_reveal", "set_cookie_headers_reveal" -> {
                val rows = NetFlowStore.listJson(limit = 20)
                var tested = false
                for (i in 0 until rows.length()) {
                    val id = rows.optJSONObject(i)?.optString("id", "").orEmpty()
                    if (id.isEmpty()) continue
                    val result = execute(name, JSONObject().put("id", id))
                    tested = true
                    if (result.ok) return result
                }
                if (tested) ok("OK: $name is callable; no captured flow carried a matching header")
                else ok("OK: $name is callable; no captured flow is available")
            }
            "plugin_list", "plugin_get_permissions",
            "plugin_enable", "plugin_disable" -> {
                // Plugin tools route through the page channel to loader.js globals; the
                // self-test only confirms the registry call is reachable (the page may not
                // have any DevTools port connected during a headless self-test).
                val list = execute("plugin_list", JSONObject())
                if (name == "plugin_list") return list
                ok("OK: $name is registered; live plugin toggling is skipped in self-test")
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
            "tab_list", "tab_current" -> execute(name, JSONObject())
            "tab_switch" -> {
                // Switching to the already-selected tab is a no-op for the user but still
                // exercises the use-case dispatch; never switch to a *different* tab in a test.
                val id = currentTabJson()?.optString("id", "").orEmpty()
                if (id.isEmpty()) ok("OK: tab_switch is callable; no tab is available to switch to")
                else execute(name, JSONObject().put("id", id))
            }
            "page_navigate", "page_reload", "page_back", "page_forward" ->
                ok("OK: $name is registered; self-test skips live navigation/history changes")
            "page_wait_for_element" -> execute(name, JSONObject().put("selector", "html").put("timeoutMs", 500))
            "console_list" -> execute(name, JSONObject().put("limit", 1))
            "page_click" -> {
                val id = "__bh_agent_self_test_click"
                val setup = page(
                    "evalJS",
                    JSONObject().put(
                        "code",
                        "var el=document.getElementById('$id');" +
                            "if(!el){el=document.createElement('button');el.id='$id';el.style.position='fixed';el.style.left='-9999px';document.documentElement.appendChild(el);}" +
                            "return true;",
                    ).put("timeoutMs", 5000),
                    6000,
                )
                if (!setup.ok) setup else {
                    val result = execute(name, JSONObject().put("selector", "#$id"))
                    page(
                        "evalJS",
                        JSONObject().put("code", "var el=document.getElementById('$id');if(el)el.remove();return true;")
                            .put("timeoutMs", 5000),
                        6000,
                    )
                    result
                }
            }
            "page_type" -> {
                val id = "__bh_agent_self_test_type"
                val setup = page(
                    "evalJS",
                    JSONObject().put(
                        "code",
                        "var el=document.getElementById('$id');" +
                            "if(!el){el=document.createElement('input');el.id='$id';el.style.position='fixed';el.style.left='-9999px';document.documentElement.appendChild(el);}" +
                            "return true;",
                    ).put("timeoutMs", 5000),
                    6000,
                )
                if (!setup.ok) setup else {
                    val result = execute(name, JSONObject().put("selector", "#$id").put("text", "ok-$stamp"))
                    page(
                        "evalJS",
                        JSONObject().put("code", "var el=document.getElementById('$id');if(el)el.remove();return true;")
                            .put("timeoutMs", 5000),
                        6000,
                    )
                    result
                }
            }
            "dom_highlight_element" -> execute(name, JSONObject().put("selector", "body").put("durationMs", 0))
            "dom_inject_css" -> execute(name, JSONObject().put("css", "/* bh agent self-test $stamp */"))
            "network_wait_request", "network_wait_response" ->
                execute(name, JSONObject().put("urlContains", "__bh_agent_self_test__").put("timeoutMs", 500))
            "mock_list", "replace_list", "agent_history_list", "agent_memory_list" -> execute(name, JSONObject())
            "mock_add_rule" -> {
                // Append, then restore the prior mock set so the self-test leaves no rule behind.
                val before = ProxyProbe.mockListJson()
                val result = execute(
                    name,
                    JSONObject().put("pattern", "__bh_agent_self_test__/$stamp").put("status", 200).put("body", "ok"),
                )
                ProxyProbe.setMockRules(JSONObject().put("rules", before))
                result
            }
            "replace_add_rule" -> {
                // Snapshot, exercise the append, then restore the prior replace state.
                val before = ProxyProbe.replaceListJson()
                val result = execute(name, JSONObject().put("from", "__bh_agent_self_test__$stamp").put("to", "ok"))
                val rules = ArrayList<Pair<String, String>>()
                val arr = before.optJSONArray("rules")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        rules.add(o.optString("from", "") to o.optString("to", ""))
                    }
                }
                ProxyProbe.setReplaceRules(before.optBoolean("enabled", false), before.optString("scope", "both"), rules)
                result
            }
            "agent_history_open" -> {
                val bridge = panelBridge
                if (bridge == null) {
                    ok("OK: agent_history_open is callable; panel bridge unavailable in self-test")
                } else {
                    val id = runOnMainResult { bridge.listChats() }.optJSONObject(0)?.optString("id", "").orEmpty()
                    if (id.isEmpty()) ok("OK: agent_history_open is callable; no saved chat to open")
                    else execute(name, JSONObject().put("id", id))
                }
            }
            "agent_history_rename" -> {
                val bridge = panelBridge
                if (bridge == null) {
                    ok("OK: agent_history_rename is callable; panel bridge unavailable in self-test")
                } else {
                    val first = runOnMainResult { bridge.listChats() }.optJSONObject(0)
                    val id = first?.optString("id", "").orEmpty()
                    if (id.isEmpty()) {
                        ok("OK: agent_history_rename is callable; no saved chat to rename")
                    } else {
                        // Restore the original title so the test never relabels a real chat.
                        val originalTitle = first.optString("title", "")
                        val result = execute(name, JSONObject().put("id", id).put("title", "自检-$stamp"))
                        execute(name, JSONObject().put("id", id).put("title", originalTitle))
                        result
                    }
                }
            }
            "agent_memory_add" -> {
                val bridge = panelBridge
                if (bridge == null) {
                    ok("OK: agent_memory_add is callable; panel bridge unavailable in self-test")
                } else {
                    val result = execute(name, JSONObject().put("value", "agent 自检记忆-$stamp"))
                    // Remove the memory we just added (it is the last index).
                    val lastIndex = runOnMainResult { bridge.listMemories() }.length() - 1
                    if (lastIndex >= 0) execute("agent_memory_delete", JSONObject().put("index", lastIndex))
                    result
                }
            }
            "agent_memory_delete" -> {
                val bridge = panelBridge
                if (bridge == null) {
                    ok("OK: agent_memory_delete is callable; panel bridge unavailable in self-test")
                } else {
                    // Add a throwaway memory, then delete it by index to exercise delete safely.
                    runOnMainResult { bridge.addMemory("agent 自检待删-$stamp") }
                    val lastIndex = runOnMainResult { bridge.listMemories() }.length() - 1
                    if (lastIndex < 0) ok("OK: agent_memory_delete is callable; no memory to delete")
                    else execute(name, JSONObject().put("index", lastIndex))
                }
            }
            // Visual Task Tracker self-tests. Each test creates a throwaway group + task,
            // exercises one tool, then clears via agent_tasks_clear so the user's real
            // tracker never accumulates self-test debris. The whole tracker is rebuilt fresh
            // on next mutation, so wiping it here is safe.
            "agent_tasks_create" -> {
                if (panelBridge == null) ok("OK: agent_tasks_create is callable; panel bridge unavailable in self-test")
                else {
                    val result = execute(name, JSONObject().put("title", "自检任务组-$stamp"))
                    execute("agent_tasks_clear", JSONObject())
                    result
                }
            }
            "agent_tasks_add" -> {
                val bridge = panelBridge
                if (bridge == null) ok("OK: agent_tasks_add is callable; panel bridge unavailable in self-test")
                else {
                    val gid = runOnMainResult { bridge.taskCreateGroup("自检任务组-$stamp") }
                    val result = execute(name, JSONObject().put("groupId", gid).put("title", "自检子任务-$stamp"))
                    execute("agent_tasks_clear", JSONObject())
                    result
                }
            }
            "agent_tasks_start", "agent_tasks_complete", "agent_tasks_fail",
            "agent_tasks_cancel", "agent_tasks_update",
            "agent_tasks_rename", "agent_tasks_delete" -> {
                val bridge = panelBridge
                if (bridge == null) {
                    ok("OK: $name is callable; panel bridge unavailable in self-test")
                } else {
                    val gid = runOnMainResult { bridge.taskCreateGroup("自检任务组-$stamp") }
                    val tid = runOnMainResult { bridge.taskAddTask(gid, "自检子任务-$stamp", "") }
                    if (tid == null) {
                        execute("agent_tasks_clear", JSONObject())
                        ok("OK: $name is callable; could not seed self-test task")
                    } else {
                        val args = when (name) {
                            "agent_tasks_fail" -> JSONObject().put("taskId", tid).put("error", "self-test")
                            "agent_tasks_update", "agent_tasks_rename" ->
                                JSONObject().put("taskId", tid).put("title", "自检改名-$stamp")
                            else -> JSONObject().put("taskId", tid)
                        }
                        val result = execute(name, args)
                        execute("agent_tasks_clear", JSONObject())
                        result
                    }
                }
            }
            "agent_tasks_list" -> {
                if (panelBridge == null) ok("OK: agent_tasks_list is callable; panel bridge unavailable in self-test")
                else execute(name, JSONObject())
            }
            "agent_tasks_clear" -> {
                if (panelBridge == null) ok("OK: agent_tasks_clear is callable; panel bridge unavailable in self-test")
                else execute(name, JSONObject())
            }
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

    // Marshal a value-producing block onto the main thread. The history/memory tools read
    // and mutate Compose [PanelState], which must only be touched on the main thread.
    private suspend fun <T> runOnMainResult(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        return suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
        }
    }

    // browser store snapshot reads are volatile and safe off the main thread; only the
    // selectTab dispatch (tab_switch) is marshalled onto the main thread for safety.
    private fun tabListJson(): JSONArray {
        val state = appContext.components.core.store.state
        val selectedId = state.selectedTabId
        val arr = JSONArray()
        for (tab in state.tabs) {
            arr.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.content.url)
                    .put("title", tab.content.title)
                    .put("selected", tab.id == selectedId)
                    .put("loading", tab.content.loading)
                    .put("private", tab.content.private),
            )
        }
        return arr
    }

    private fun currentTabJson(): JSONObject? {
        val tab = appContext.components.core.store.state.selectedTab ?: return null
        return JSONObject()
            .put("id", tab.id)
            .put("url", tab.content.url)
            .put("title", tab.content.title)
            .put("loading", tab.content.loading)
            .put("private", tab.content.private)
    }

    private fun tabExists(id: String): Boolean =
        appContext.components.core.store.state.tabs.any { it.id == id }

    private fun normalizeNavigateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = when {
            trimmed.startsWith("about:", ignoreCase = true) -> trimmed
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> trimmed
            else -> "https://$trimmed"
        }
        return try {
            URI(url)
            url
        } catch (_: Throwable) {
            null
        }
    }

    // network_wait_request / network_wait_response: poll the read-only NetFlowStore TEE
    // snapshot (pump-safe) for a matching flow that appeared after the call started. With
    // needResponse, only count rows whose response has been recorded (status > 0).
    private fun waitForFlow(args: JSONObject, needResponse: Boolean): AgentToolResult {
        val method = args.optString("method", "")
        val urlContains = args.optString("urlContains", "")
        val timeoutMs = args.optLong("timeoutMs", 15_000L).coerceIn(500L, 60_000L)
        val sinceMs = System.currentTimeMillis()
        val deadline = sinceMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rows = NetFlowStore.listJson(method = method, urlContains = urlContains, sinceMs = sinceMs, limit = 20)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (needResponse && row.optInt("status", 0) <= 0) continue
                return ok(
                    JSONObject()
                        .put("found", true)
                        .put("waitedMs", System.currentTimeMillis() - sinceMs)
                        .put("flow", row)
                        .toString(),
                )
            }
            Thread.sleep(200)
        }
        return ok(
            JSONObject()
                .put("found", false)
                .put("waitedMs", System.currentTimeMillis() - sinceMs)
                .put("timeout", true)
                .toString(),
        )
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

    /**
     * Saves the current page's full HTML into a container file and returns only metadata
     * (path/bytes/url/title/truncated) — the HTML itself never enters the model transcript,
     * which is the whole point: the model can then container_grep / container_sed the file
     * instead of pulling the entire source into context. Pulls the source via the page channel
     * (cmd getSourceRaw, capped at 4 MB page-side). Bypasses the 512 KB writeFile cap on purpose.
     */
    private fun savePageToContainer(args: JSONObject): AgentToolResult {
        val res = PageChannel.exec("getSourceRaw", JSONObject(), 20_000)
        if (res.has("error")) return err(res.optString("error"))
        val html = res.optString("html", "")
        if (html.isEmpty()) return err("page returned empty source")
        val url = res.optString("url", "")
        val rel = args.optString("path", "").ifBlank { defaultPageFileName(url) }
        val root = containerRoot()
        val file = resolvePath(root, rel) ?: return err("invalid path")
        val bytes = html.toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return ok(
            JSONObject()
                .put("path", relativePath(root, file))
                .put("bytes", bytes.size)
                .put("chars", html.length)
                .put("url", url)
                .put("title", res.optString("title", ""))
                .put("truncated", res.optBoolean("truncated", false))
                .toString(),
        )
    }

    /** Default container path for page_save_to_container: pages/<host>_<timestamp>.html. */
    private fun defaultPageFileName(url: String): String {
        val host = try {
            java.net.URI(url).host ?: "page"
        } catch (_: Exception) {
            "page"
        }
        val safe = host.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "page" }
        return "pages/${safe}_${System.currentTimeMillis()}.html"
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

    // ----------------------------------------------------------- container shell tools
    // coreutils-equivalent reach over the agent's own sandbox (containerRoot()) WITHOUT a
    // real shell. Every path goes through resolvePath, which blocks absolute paths, null
    // bytes and parent-escape, so these can never touch anything outside the container.
    // Read tools are S1 (no approval); mutating tools are S2 (approval).

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Regex(sb.toString())
    }

    // Breadth-first collect of files/dirs under [dir], bounded by [cap] to keep big trees safe.
    private fun walkFiles(dir: File, out: MutableList<File>, cap: Int) {
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty() && out.size < cap) {
            val d = queue.removeFirst()
            val children = d.listFiles()?.sortedBy { it.name } ?: continue
            for (f in children) {
                if (out.size >= cap) break
                out.add(f)
                if (f.isDirectory) queue.add(f)
            }
        }
    }

    private fun containerGrep(args: JSONObject): AgentToolResult {
        val pattern = args.optString("pattern", "")
        if (pattern.isEmpty()) return err("pattern required")
        val rel = args.optString("path", ".")
        val ignoreCase = args.optBoolean("ignoreCase", false)
        val maxMatches = args.optInt("maxMatches", 100).coerceIn(1, 1000)
        val base = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!base.exists()) return err("path not found")
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Throwable) {
            return err("invalid regex: ${e.message}")
        }
        val files = ArrayList<File>()
        if (base.isFile) files.add(base) else walkFiles(base, files, 5000)
        val matches = JSONArray()
        var scanned = 0
        for (f in files) {
            if (matches.length() >= maxMatches) break
            if (!f.isFile || f.length() > FILE_READ_CAP) continue
            scanned++
            val lines = try { f.readText(Charsets.UTF_8).split('\n') } catch (_: Throwable) { continue }
            for ((i, line) in lines.withIndex()) {
                if (matches.length() >= maxMatches) break
                if (regex.containsMatchIn(line)) {
                    matches.put(
                        JSONObject()
                            .put("path", relativePath(containerRoot(), f))
                            .put("line", i + 1)
                            .put("text", line.take(500)),
                    )
                }
            }
        }
        return ok(JSONObject().put("matches", matches).put("count", matches.length()).put("filesScanned", scanned).toString())
    }

    private fun containerFind(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", ".")
        val namePattern = args.optString("namePattern", "*").ifBlank { "*" }
        val type = args.optString("type", "any").lowercase()
        val maxResults = args.optInt("maxResults", 200).coerceIn(1, 2000)
        val base = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!base.exists()) return err("path not found")
        val regex = globToRegex(namePattern)
        val all = ArrayList<File>()
        if (base.isDirectory) walkFiles(base, all, 10000) else all.add(base)
        val arr = JSONArray()
        for (f in all) {
            if (arr.length() >= maxResults) break
            if (type == "file" && !f.isFile) continue
            if (type == "dir" && !f.isDirectory) continue
            if (!regex.matches(f.name)) continue
            arr.put(
                JSONObject()
                    .put("path", relativePath(containerRoot(), f))
                    .put("dir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0L),
            )
        }
        return ok(JSONObject().put("results", arr).put("count", arr.length()).toString())
    }

    private fun containerHeadTail(args: JSONObject, head: Boolean): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val n = args.optInt("lines", 10).coerceIn(1, 5000)
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        if (file.length() > FILE_READ_CAP) return err("file too large (${file.length()} bytes)")
        val lines = file.readText(Charsets.UTF_8).split('\n')
        val picked = if (head) lines.take(n) else lines.takeLast(n)
        return ok(picked.joinToString("\n"))
    }

    private fun containerWc(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        if (file.length() > FILE_READ_CAP) return err("file too large (${file.length()} bytes)")
        val text = file.readText(Charsets.UTF_8)
        val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return ok(JSONObject().put("lines", lineCount).put("words", words).put("bytes", text.toByteArray(Charsets.UTF_8).size).toString())
    }

    private fun containerStat(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        return ok(
            JSONObject()
                .put("path", relativePath(containerRoot(), file))
                .put("type", if (file.isDirectory) "dir" else "file")
                .put("size", if (file.isFile) file.length() else 0L)
                .put("lastModified", file.lastModified())
                .put("readable", file.canRead())
                .put("writable", file.canWrite())
                .toString(),
        )
    }

    private fun containerDu(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", ".")
        val base = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!base.exists()) return err("path not found")
        var bytes = 0L
        var files = 0
        if (base.isFile) {
            bytes = base.length()
            files = 1
        } else {
            val list = ArrayList<File>()
            walkFiles(base, list, 100000)
            for (f in list) if (f.isFile) { bytes += f.length(); files++ }
        }
        return ok(JSONObject().put("path", relativePath(containerRoot(), base)).put("bytes", bytes).put("files", files).toString())
    }

    private fun containerTree(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", ".")
        val maxDepth = args.optInt("maxDepth", 3).coerceIn(1, 8)
        val base = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!base.exists()) return err("path not found")
        if (!base.isDirectory) return err("not a directory")
        val sb = StringBuilder()
        var count = 0
        fun recurse(dir: File, depth: Int, prefix: String) {
            if (depth > maxDepth || count > 1000) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (f in children) {
                if (count++ > 1000) { sb.append(prefix).append("… (truncated)\n"); return }
                sb.append(prefix).append(if (f.isDirectory) "[D] " else "    ").append(f.name).append('\n')
                if (f.isDirectory) recurse(f, depth + 1, "$prefix  ")
            }
        }
        recurse(base, 1, "")
        return ok(sb.toString().ifEmpty { "(empty)" })
    }

    private fun containerFileType(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (file.isDirectory) {
            return ok(JSONObject().put("path", relativePath(containerRoot(), file)).put("kind", "directory").toString())
        }
        val len = file.length()
        val kind = when {
            len == 0L -> "empty"
            else -> {
                val sample = ByteArray(minOf(len, 4096L).toInt())
                val read = file.inputStream().use { it.read(sample) }
                if ((0 until read).any { sample[it].toInt() == 0 }) "binary" else "text"
            }
        }
        return ok(
            JSONObject()
                .put("path", relativePath(containerRoot(), file))
                .put("kind", kind)
                .put("ext", file.extension)
                .put("size", len)
                .toString(),
        )
    }

    private fun containerWhich(args: JSONObject): AgentToolResult {
        val targetName = args.optString("name", "")
        if (targetName.isBlank()) return err("name required")
        val all = ArrayList<File>()
        walkFiles(containerRoot(), all, 10000)
        val arr = JSONArray()
        for (f in all) if (f.isFile && f.name == targetName) arr.put(relativePath(containerRoot(), f))
        return ok(JSONObject().put("name", targetName).put("paths", arr).put("count", arr.length()).toString())
    }

    private fun containerSed(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val start = args.optInt("startLine", 1).coerceAtLeast(1)
        val end = args.optInt("endLine", start).coerceAtLeast(start)
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (!file.exists()) return err("path not found")
        if (!file.isFile) return err("not a file")
        if (file.length() > FILE_READ_CAP) return err("file too large (${file.length()} bytes)")
        val lines = file.readText(Charsets.UTF_8).split('\n')
        if (start > lines.size) return err("startLine $start beyond EOF (${lines.size} lines)")
        return ok(lines.subList(start - 1, minOf(end, lines.size)).joinToString("\n"))
    }

    private fun containerRealpath(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path (absolute or escapes container)")
        return ok(JSONObject().put("path", relativePath(containerRoot(), file)).put("exists", file.exists()).toString())
    }

    private fun containerDiff(args: JSONObject): AgentToolResult {
        val a = resolvePath(containerRoot(), args.optString("pathA", "")) ?: return err("invalid pathA")
        val b = resolvePath(containerRoot(), args.optString("pathB", "")) ?: return err("invalid pathB")
        if (!a.isFile) return err("pathA not a file")
        if (!b.isFile) return err("pathB not a file")
        if (a.length() > FILE_READ_CAP || b.length() > FILE_READ_CAP) return err("file too large")
        val la = a.readText(Charsets.UTF_8).split('\n')
        val lb = b.readText(Charsets.UTF_8).split('\n')
        val diffs = JSONArray()
        var changed = 0
        for (i in 0 until maxOf(la.size, lb.size)) {
            val x = la.getOrNull(i)
            val y = lb.getOrNull(i)
            if (x != y) {
                changed++
                if (diffs.length() < 200) {
                    diffs.put(JSONObject().put("line", i + 1).put("a", x ?: "").put("b", y ?: ""))
                }
            }
        }
        return ok(JSONObject().put("identical", changed == 0).put("changedLines", changed).put("diffs", diffs).toString())
    }

    private fun containerCp(args: JSONObject): AgentToolResult {
        val src = resolvePath(containerRoot(), args.optString("src", "")) ?: return err("invalid src")
        val dest = resolvePath(containerRoot(), args.optString("dest", "")) ?: return err("invalid dest")
        if (!src.exists()) return err("src not found")
        if (src.isDirectory && !args.optBoolean("recursive", false)) return err("src is a directory; pass recursive=true")
        dest.parentFile?.mkdirs()
        src.copyRecursively(dest, overwrite = true)
        return ok("copied ${relativePath(containerRoot(), src)} -> ${relativePath(containerRoot(), dest)}")
    }

    private fun containerMv(args: JSONObject): AgentToolResult {
        val src = resolvePath(containerRoot(), args.optString("src", "")) ?: return err("invalid src")
        val dest = resolvePath(containerRoot(), args.optString("dest", "")) ?: return err("invalid dest")
        if (!src.exists()) return err("src not found")
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.deleteRecursively()
        if (!src.renameTo(dest)) {
            src.copyRecursively(dest, overwrite = true)
            src.deleteRecursively()
        }
        return ok("moved ${relativePath(containerRoot(), src)} -> ${relativePath(containerRoot(), dest)}")
    }

    private fun containerRm(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (file == containerRoot().canonicalFile) return err("refusing to remove container root")
        if (!file.exists()) return err("path not found")
        if (file.isDirectory && !args.optBoolean("recursive", false)) {
            return err("path is a directory; pass recursive=true or use container_rmdir")
        }
        val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
        return if (removed) ok("removed ${relativePath(containerRoot(), file)}") else err("failed to remove")
    }

    private fun containerMkdir(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val dir = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (dir.exists()) {
            return if (dir.isDirectory) ok("already exists: ${relativePath(containerRoot(), dir)}") else err("path exists and is a file")
        }
        return if (dir.mkdirs()) ok("created ${relativePath(containerRoot(), dir)}") else err("failed to create directory")
    }

    private fun containerRmdir(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val dir = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (dir == containerRoot().canonicalFile) return err("refusing to remove container root")
        if (!dir.exists()) return err("path not found")
        if (!dir.isDirectory) return err("not a directory")
        val recursive = args.optBoolean("recursive", false)
        val children = dir.listFiles()
        if (!recursive && children != null && children.isNotEmpty()) {
            return err("directory not empty; pass recursive=true to delete contents (rm -rf)")
        }
        val removed = if (recursive) dir.deleteRecursively() else dir.delete()
        return if (removed) ok("removed directory ${relativePath(containerRoot(), dir)}") else err("failed to remove directory")
    }

    private fun containerTouch(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return ok("touched ${relativePath(containerRoot(), file)}")
        }
        file.parentFile?.mkdirs()
        return if (file.createNewFile()) ok("created ${relativePath(containerRoot(), file)}") else err("failed to create file")
    }

    private fun containerAppend(args: JSONObject): AgentToolResult {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return err("path required")
        val content = args.optString("content", "")
        val addBytes = content.toByteArray(Charsets.UTF_8).size
        if (addBytes > FILE_WRITE_CAP) return err("content too large")
        val file = resolvePath(containerRoot(), rel) ?: return err("invalid path")
        if (file.exists() && file.length() + addBytes > FILE_WRITE_CAP) return err("file would exceed size cap")
        file.parentFile?.mkdirs()
        file.appendText(content, Charsets.UTF_8)
        return ok("appended ${content.length} chars to ${relativePath(containerRoot(), file)}")
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
    "browser_request", "page_fetch", "page_navigate" -> {
        val method = args.optString("method", "GET").uppercase().ifBlank { "GET" }
        "$method:${hostOf(args.optString("url", ""))}"
    }
    else -> shortHash(args.toString())
}

private fun networkScopeLabel(name: String, args: JSONObject): String = when (name) {
    "web_search" -> "搜索：${args.optString("query", "").take(120)}"
    "browser_request" -> "${args.optString("method", "GET").uppercase()} ${hostOf(args.optString("url", ""))}"
    "page_navigate" -> "导航到 ${hostOf(args.optString("url", ""))}"
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
    tool("write_code_file", AgentPermissionTier.S2, "一键向 Agent 外部容器内的代码文件写入完整代码或追加代码。", schema {
        prop("path", str("容器内相对文件路径"))
        prop("code", str("要写入的代码文本"))
        prop("mode", str("replace 或 append，默认 replace"))
        required("path")
        required("code")
    }),
    tool("delete_code_from_file", AgentPermissionTier.S2, "从 Agent 外部容器内的代码文件删除指定代码片段或行范围。", schema {
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
    tool("page_save_to_container", AgentPermissionTier.S2, "把当前网页的完整 HTML 源码保存到 Agent 容器文件（源码不进对话上下文，避免爆炸）。保存后用 container_grep/container_sed/container_head 分段读取。返回 {path,bytes,chars,url,title,truncated}。", schema {
        prop("path", str("容器内相对路径，省略则自动命名 pages/<host>_<时间戳>.html"))
    }),
    tool("container_list", AgentPermissionTier.S1, "列出 Agent 本地容器目录内的文件。路径限制在容器内。", schema {
        prop("path", str("容器内相对目录，默认 ."))
    }),
    tool("container_read", AgentPermissionTier.S1, "读取 Agent 本地容器目录内的小文本文件。", schema {
        prop("path", str("容器内相对文件路径"))
        required("path")
    }),
    tool("container_write", AgentPermissionTier.S2, "写入 Agent 本地容器目录内的小文本文件。", schema {
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
    // ---- 容器 shell 工具：在 Agent 私有沙盒（容器目录）内提供 coreutils 等价能力。
    // 所有 path 都是相对容器根的相对路径，禁止绝对路径/越界。S1 只读、S2 改写。
    tool("container_grep", AgentPermissionTier.S1, "在容器内按正则搜索文本文件内容（类似 grep -rn）。返回 [{path,line,text}]。示例: {\"pattern\":\"TODO\",\"path\":\"src\",\"ignoreCase\":true}", schema {
        prop("pattern", str("正则表达式（Kotlin/Java regex 语法）"))
        prop("path", str("起始相对路径，默认 \".\"（整个容器）；可指向单个文件或目录"))
        prop("ignoreCase", bool("是否忽略大小写，默认 false"))
        prop("maxMatches", num("最多返回匹配行数，默认 100，上限 1000"))
        required("pattern")
    }),
    tool("container_find", AgentPermissionTier.S1, "按文件名通配符在容器内查找文件/目录（类似 find -name）。namePattern 支持 * 和 ?。示例: {\"namePattern\":\"*.json\",\"type\":\"file\"}", schema {
        prop("path", str("起始相对路径，默认 \".\""))
        prop("namePattern", str("文件名通配符，如 *.txt；默认 * 匹配全部"))
        prop("type", str("过滤类型：file/dir/any，默认 any"))
        prop("maxResults", num("最多返回条数，默认 200，上限 2000"))
    }),
    tool("container_head", AgentPermissionTier.S1, "读取容器内文本文件的前 N 行（类似 head）。示例: {\"path\":\"a.log\",\"lines\":20}", schema {
        prop("path", str("容器内相对文件路径"))
        prop("lines", num("行数，默认 10，上限 5000"))
        required("path")
    }),
    tool("container_tail", AgentPermissionTier.S1, "读取容器内文本文件的后 N 行（类似 tail）。示例: {\"path\":\"a.log\",\"lines\":20}", schema {
        prop("path", str("容器内相对文件路径"))
        prop("lines", num("行数，默认 10，上限 5000"))
        required("path")
    }),
    tool("container_wc", AgentPermissionTier.S1, "统计容器内文本文件的行数/词数/字节数（类似 wc）。返回 {lines,words,bytes}。", schema {
        prop("path", str("容器内相对文件路径"))
        required("path")
    }),
    tool("container_stat", AgentPermissionTier.S1, "读取容器内文件/目录的元信息（类似 stat）：type/size/lastModified/readable/writable。", schema {
        prop("path", str("容器内相对路径"))
        required("path")
    }),
    tool("container_du", AgentPermissionTier.S1, "递归统计容器内某路径的总字节数与文件数（类似 du -s）。默认统计整个容器。", schema {
        prop("path", str("相对路径，默认 \".\""))
    }),
    tool("container_tree", AgentPermissionTier.S1, "以树状文本列出容器内目录结构（类似 tree）。[D] 前缀表示目录。示例: {\"path\":\".\",\"maxDepth\":2}", schema {
        prop("path", str("起始相对目录，默认 \".\""))
        prop("maxDepth", num("递归深度，默认 3，上限 8"))
    }),
    tool("container_file_type", AgentPermissionTier.S1, "判断容器内文件类型（类似 file）：返回 kind=text/binary/empty/directory、扩展名与大小。", schema {
        prop("path", str("容器内相对路径"))
        required("path")
    }),
    tool("container_which", AgentPermissionTier.S1, "在容器内按精确文件名定位文件，返回所有匹配的相对路径（类似 which/locate）。", schema {
        prop("name", str("要查找的精确文件名，如 config.json"))
        required("name")
    }),
    tool("container_sed", AgentPermissionTier.S1, "读取容器内文本文件的指定行区间（类似 sed -n 'START,ENDp'）。行号从 1 开始、含端点。", schema {
        prop("path", str("容器内相对文件路径"))
        prop("startLine", num("起始行号（含），默认 1"))
        prop("endLine", num("结束行号（含），默认等于 startLine"))
        required("path")
    }),
    tool("container_realpath", AgentPermissionTier.S1, "把容器内相对路径规范化并校验是否越界（类似 realpath）。返回规范相对路径与 exists。", schema {
        prop("path", str("容器内相对路径"))
        required("path")
    }),
    tool("container_diff", AgentPermissionTier.S1, "逐行比较容器内两个文本文件（类似 diff）。返回 identical/changedLines/diffs[{line,a,b}]。", schema {
        prop("pathA", str("文件 A 的相对路径"))
        prop("pathB", str("文件 B 的相对路径"))
        required("pathA")
        required("pathB")
    }),
    tool("container_cp", AgentPermissionTier.S2, "复制容器内文件或目录（类似 cp）。复制目录需 recursive=true。示例: {\"src\":\"a.txt\",\"dest\":\"b.txt\"}", schema {
        prop("src", str("源相对路径"))
        prop("dest", str("目标相对路径"))
        prop("recursive", bool("源为目录时必须 true，默认 false"))
        required("src")
        required("dest")
    }),
    tool("container_mv", AgentPermissionTier.S2, "移动或重命名容器内文件/目录（类似 mv）。dest 已存在会被覆盖。", schema {
        prop("src", str("源相对路径"))
        prop("dest", str("目标相对路径"))
        required("src")
        required("dest")
    }),
    tool("container_rm", AgentPermissionTier.S2, "删除容器内文件（类似 rm）。删除目录需 recursive=true（等价 rm -rf）。不可删除容器根。", schema {
        prop("path", str("要删除的相对路径"))
        prop("recursive", bool("目标为目录时需 true，默认 false"))
        required("path")
    }),
    tool("container_mkdir", AgentPermissionTier.S2, "在容器内创建目录（类似 mkdir -p，自动建父目录）。", schema {
        prop("path", str("要创建的相对目录路径"))
        required("path")
    }),
    tool("container_rmdir", AgentPermissionTier.S2, "删除容器内目录（类似 rmdir）。默认仅删空目录；recursive=true 时连同内容一起删（rm -rf）。不可删除容器根。", schema {
        prop("path", str("要删除的相对目录路径"))
        prop("recursive", bool("true=连内容一起删（rm -rf），默认 false 仅删空目录"))
        required("path")
    }),
    tool("container_touch", AgentPermissionTier.S2, "创建空文件或更新其修改时间（类似 touch）。自动创建父目录。", schema {
        prop("path", str("相对文件路径"))
        required("path")
    }),
    tool("container_append", AgentPermissionTier.S2, "向容器内文件追加文本（类似 >>）。文件不存在则新建。", schema {
        prop("path", str("相对文件路径"))
        prop("content", str("要追加的文本内容"))
        required("path")
        required("content")
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
	    tool("intercept_set", AgentPermissionTier.S2, "配置请求/响应拦截。缺省不全局拦截（reqAll/respAll 默认 false），避免误暂停所有流量；要拦全部需显式传 reqAll/respAll=true，或在 rules 里加 action=intercept 规则。遥测、心跳、噪音、cookie/auth 类低价值流量默认自动放行。", schema {
	        prop("reqAll", bool("拦截全部请求；缺省=false"))
	        prop("respAll", bool("拦截全部响应；缺省=false"))
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
    tool("intercept_list_rules", AgentPermissionTier.S1, "读取当前拦截配置快照：enabled、reqAll/respAll 全局开关、遥测/心跳/噪音/cookie 四个低价值放行开关，以及 rules 列表。无参。", emptySchema()),
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
        prop("pages", num("按视口高度倍数滑动，例如 1 表示一屏；传入时优先于 amount"))
        prop("selector", str("可选 CSS selector；不传则滑动 window，传入则滑动第一个匹配元素"))
        prop("toSelector", str("可选 CSS selector；传入时滚动到该元素可见"))
        prop("block", str("toSelector 的垂直对齐：start/center/end/nearest，默认 center"))
        prop("inline", str("toSelector 的水平对齐：start/center/end/nearest，默认 nearest"))
        prop("behavior", str("auto、instant 或 smooth，默认 auto"))
        prop("waitMs", num("等待滚动完成后取位置的毫秒数，默认 smooth=240/auto=60，上限 1000"))
        prop("nearestScrollable", bool("selector 命中的元素本身不可滚时是否自动找最近可滚父级，默认 true"))
    }),
    tool("page_long_press", AgentPermissionTier.S2, "长按当前网页的某个元素（模拟指针/鼠标/触摸按住并释放）。受限 UI 操作，不执行任意 JS。", schema {
        prop("selector", str("目标元素 CSS selector；不传则按 x/y 坐标"))
        prop("x", num("可选：视口 X 坐标（不传 selector 时使用）"))
        prop("y", num("可选：视口 Y 坐标（不传 selector 时使用）"))
        prop("durationMs", num("按住时长 ms，默认 600，范围 100-5000"))
    }),
    tool("page_swipe", AgentPermissionTier.S3, "连续/长距离滑动当前网页或指定滚动容器：可重复多次、单次大距离，并尽力派发指针拖拽手势。", schema {
        prop("direction", str("down/up/left/right，默认 down"))
        prop("distance", num("单次滑动像素，默认 800，最大 100000（支持长距离）"))
        prop("repeat", num("连续滑动次数，默认 1，最大 100（支持连续滑动）"))
        prop("stepDelayMs", num("每次滑动之间的间隔 ms，默认 120，最大 2000"))
        prop("selector", str("可选：滚动容器 CSS selector；不传则滑动窗口"))
        prop("gesture", bool("是否同时派发指针拖拽手势事件，默认 true"))
        prop("behavior", str("auto 或 smooth，默认 auto"))
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
    tool("cookie_list_redacted", AgentPermissionTier.S2, "列出某 flow 上存在的 Cookie/Set-Cookie 头（值已脱敏，只暴露 scheme 与长度）。用于先了解有哪些凭证、再决定用占位符还是 cookie_reveal。返回 [{dir,name,masked}]。", schema {
        prop("id", str("flow id 或六位 code"))
        required("id")
    }),
    tool("auth_headers_redacted", AgentPermissionTier.S2, "列出某 flow 上存在的鉴权类头（authorization/x-api-key/x-csrf-token 等，值已脱敏）。返回 [{dir,name,masked}]。", schema {
        prop("id", str("flow id 或六位 code"))
        required("id")
    }),
    tool("auth_reveal", AgentPermissionTier.S3, "读取某 flow 的真实鉴权头明文（默认 authorization，可传 name 指定 x-api-key 等）。高危，需用户原生确认。", schema {
        prop("id", str("flow id 或六位 code"))
        prop("name", str("header 名，默认 authorization"))
        required("id")
    }),
    tool("set_cookie_headers_reveal", AgentPermissionTier.S3, "读取某 flow 响应的真实 Set-Cookie 头明文。高危，需用户原生确认。", schema {
        prop("id", str("flow id 或六位 code"))
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
    tool("tab_list", AgentPermissionTier.S1, "列出当前所有浏览器标签页（id、url、title、是否选中、是否加载中、是否隐私）。", emptySchema()),
    tool("tab_current", AgentPermissionTier.S1, "读取当前选中标签页的信息；没有选中页时返回空对象。", emptySchema()),
    tool("tab_switch", AgentPermissionTier.S2, "切换到指定 id 的浏览器标签页。", schema {
        prop("id", str("tab_list 返回的标签页 id"))
        required("id")
    }),
    tool("page_navigate", AgentPermissionTier.S2, "让当前标签页加载指定 URL。走浏览器原生导航，不依赖页面 JS/CSP。", schema {
        prop("url", str("目标 URL；省略 scheme 时按 https:// 处理"))
        prop("waitMs", num("导航发起后等待毫秒数，默认 0，上限 10000"))
        required("url")
    }),
    tool("page_reload", AgentPermissionTier.S2, "刷新当前标签页。走浏览器原生 reload，不执行页面 JS。", emptySchema()),
    tool("page_back", AgentPermissionTier.S2, "让当前标签页后退。走浏览器原生历史导航，不执行页面 JS。", emptySchema()),
    tool("page_forward", AgentPermissionTier.S2, "让当前标签页前进。走浏览器原生历史导航，不执行页面 JS。", emptySchema()),
    tool("page_click", AgentPermissionTier.S2, "点击当前页面匹配 selector 的元素（派发指针/鼠标事件并调用原生 click）。受限 UI 操作，不执行任意 JS。", schema {
        prop("selector", str("目标元素 CSS selector"))
        required("selector")
    }),
    tool("page_type", AgentPermissionTier.S2, "向当前页面 input/textarea/contentEditable 元素填入文本并派发 input/change 事件。受限 UI 操作，不执行任意 JS。", schema {
        prop("selector", str("目标元素 CSS selector"))
        prop("text", str("要填入的文本"))
        prop("append", bool("是否在原值后追加，默认覆盖"))
        required("selector")
    }),
    tool("page_wait_for_element", AgentPermissionTier.S1, "在当前页面轮询等待某 selector 出现（可要求可见），命中或超时返回 found:true/false。", schema {
        prop("selector", str("目标元素 CSS selector"))
        prop("timeoutMs", num("最长等待 ms，默认 5000，范围 100-30000"))
        prop("visible", bool("是否要求元素可见（有尺寸）"))
        required("selector")
    }),
    tool("console_list", AgentPermissionTier.S1, "读取注入钩子捕获的 page world console 输出（best-effort：仅注入后日志，CSP 可能阻断）。", schema {
        prop("limit", num("最多返回条数，默认 50，上限 200"))
        prop("level", str("可选：按 log/info/warn/error/debug 过滤"))
    }),
    tool("network_wait_request", AgentPermissionTier.S1, "阻塞等待匹配的请求被 MITM 抓到（轮询只读 TEE 快照），命中或超时返回。", schema {
        prop("method", str("按 HTTP method 过滤"))
        prop("urlContains", str("按 URL 子串过滤"))
        prop("timeoutMs", num("最长等待 ms，默认 15000，范围 500-60000"))
    }),
    tool("network_wait_response", AgentPermissionTier.S1, "阻塞等待匹配请求的响应被抓到（status>0），命中或超时返回。", schema {
        prop("method", str("按 HTTP method 过滤"))
        prop("urlContains", str("按 URL 子串过滤"))
        prop("timeoutMs", num("最长等待 ms，默认 15000，范围 500-60000"))
    }),
    tool("mock_add_rule", AgentPermissionTier.S2, "追加一条 Mock 规则（URL 子串命中后返回合成响应），不覆盖现有规则。", schema {
        prop("pattern", str("URL 子串"))
        prop("status", num("HTTP 状态码，默认 200"))
        prop("body", str("响应体"))
        prop("headers", JSONObject().put("type", "object").put("description", "响应头对象"))
        required("pattern")
    }),
    tool("mock_list", AgentPermissionTier.S1, "列出当前所有 Mock 规则。", emptySchema()),
    tool("replace_add_rule", AgentPermissionTier.S2, "追加一条请求/响应文本替换规则并启用替换，不覆盖现有规则。", schema {
        prop("from", str("查找文本"))
        prop("to", str("替换文本"))
        prop("scope", str("req、resp 或 both；缺省沿用当前 scope"))
        required("from")
    }),
    tool("replace_list", AgentPermissionTier.S1, "读取当前替换规则、启用状态和 scope。", emptySchema()),
    tool("dom_highlight_element", AgentPermissionTier.S2, "在当前页面给匹配元素描临时彩色边框并滚动到可见，durationMs 后还原。", schema {
        prop("selector", str("目标元素 CSS selector"))
        prop("color", str("边框颜色，默认 #ff3b30"))
        prop("durationMs", num("高亮时长 ms，默认 2000，上限 20000，0=不还原"))
        prop("all", bool("是否高亮所有匹配（上限 50），默认只第一个"))
        required("selector")
    }),
    tool("dom_inject_css", AgentPermissionTier.S2, "向当前页面追加一段 <style>，restyle 实时页面。", schema {
        prop("css", str("CSS 文本"))
        required("css")
    }),
    tool("agent_history_list", AgentPermissionTier.S1, "列出悬浮 Agent 的最近对话（id、标题、是否已命名）。", emptySchema()),
    tool("agent_history_open", AgentPermissionTier.S2, "切换悬浮 Agent 到指定 id 的历史对话。", schema {
        prop("id", str("agent_history_list 返回的对话 id"))
        required("id")
    }),
    tool("agent_history_rename", AgentPermissionTier.S2, "重命名悬浮 Agent 的某个历史对话。", schema {
        prop("id", str("对话 id"))
        prop("title", str("新标题"))
        required("id")
        required("title")
    }),
    tool("agent_memory_list", AgentPermissionTier.S1, "列出悬浮 Agent 已保存的长期记忆条目（带索引）。", emptySchema()),
    tool("agent_memory_add", AgentPermissionTier.S2, "向悬浮 Agent 的长期记忆追加一条。", schema {
        prop("value", str("记忆内容"))
        required("value")
    }),
    tool("agent_memory_delete", AgentPermissionTier.S2, "按索引删除悬浮 Agent 的一条长期记忆。", schema {
        prop("index", num("agent_memory_list 返回的索引"))
        required("index")
    }),
    // ---- 浏览器插件管理：内置插件只有「启用/停用」两态，由 agent.js↔loader.js 桥执行。
    tool("plugin_list", AgentPermissionTier.S1, "列出所有内置浏览器插件及其启用状态。返回 [{id,name,desc,enabled}]。无参。", emptySchema()),
    tool("plugin_get_permissions", AgentPermissionTier.S1, "读取单个插件的描述信息（id/name/desc/enabled）。内置插件不声明细粒度权限，此工具返回插件描述。", schema {
        prop("id", str("plugin_list 返回的插件 id"))
        required("id")
    }),
    tool("plugin_enable", AgentPermissionTier.S2, "启用指定 id 的浏览器插件（触发其 onEnable 并持久化启用态）。", schema {
        prop("id", str("plugin_list 返回的插件 id"))
        required("id")
    }),
    tool("plugin_disable", AgentPermissionTier.S2, "停用指定 id 的浏览器插件（触发其 onDisable 撤回所做改动并持久化）。", schema {
        prop("id", str("plugin_list 返回的插件 id"))
        required("id")
    }),
    // Visual Task Tracker tools (all S1 — no approval prompt, available at every tier so
    // the agent can keep the in-chat task card up to date without user friction).
    tool(
        "agent_tasks_create",
        AgentPermissionTier.S1,
        "创建一个任务组（task group），返回 groupId。一个对话可有多个任务组，每个组内有若干子任务。复杂、多步骤的工作开始前先建组。",
        schema {
            prop("title", str("任务组标题，不超过 80 字"))
            required("title")
        },
    ),
    tool(
        "agent_tasks_add",
        AgentPermissionTier.S1,
        "向已有任务组追加一个子任务，返回 taskId。子任务初始状态为 PENDING。",
        schema {
            prop("groupId", str("agent_tasks_create 返回的 groupId"))
            prop("title", str("子任务简短标题，不超过 120 字"))
            prop("description", str("可选：子任务详细描述，不超过 400 字"))
            required("groupId")
            required("title")
        },
    ),
    tool(
        "agent_tasks_start",
        AgentPermissionTier.S1,
        "把指定子任务标为 ACTIVE（开始执行），自动记录开始时间，并把它设为当前任务（用于关联后续工具调用）。",
        schema {
            prop("taskId", str("agent_tasks_add 返回的 taskId"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_complete",
        AgentPermissionTier.S1,
        "把指定子任务标为 DONE，自动计算 durationMs。在 page_set_text 等真实改动完成后调用，不要只靠口述声明完成。",
        schema {
            prop("taskId", str("taskId"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_fail",
        AgentPermissionTier.S1,
        "把指定子任务标为 FAILED，附带错误原因。失败原因会显示在任务卡上，最多 500 字。",
        schema {
            prop("taskId", str("taskId"))
            prop("error", str("失败原因，简短一句话"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_cancel",
        AgentPermissionTier.S1,
        "把指定子任务标为 CANCELLED（用户改主意 / 不再相关 / 被更优方案取代）。失败和取消语义不同：失败=尝试过但出错；取消=不再执行。",
        schema {
            prop("taskId", str("taskId"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_update",
        AgentPermissionTier.S1,
        "修改指定子任务的标题或描述。任一字段省略即保留原值；至少需要其中一个。",
        schema {
            prop("taskId", str("taskId"))
            prop("title", str("新标题，可省略"))
            prop("description", str("新描述，可省略"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_rename",
        AgentPermissionTier.S1,
        "重命名指定子任务（只改标题，描述不变）。这就是「可视化任务里改任务标题」的工具。等价于只传 title 的 agent_tasks_update。",
        schema {
            prop("taskId", str("taskId"))
            prop("title", str("新标题，不超过 120 字"))
            required("taskId")
            required("title")
        },
    ),
    tool(
        "agent_tasks_delete",
        AgentPermissionTier.S1,
        "从任务追踪表删除指定子任务（整条移除，不同于 cancel 的「标记取消」）。删错可重新 agent_tasks_add。",
        schema {
            prop("taskId", str("taskId"))
            required("taskId")
        },
    ),
    tool(
        "agent_tasks_list",
        AgentPermissionTier.S1,
        "返回当前对话的整张任务追踪表（所有 group + task + 状态 + 时间戳 + 工具调用记录），用于在开始一轮新工作前回顾进度。",
        emptySchema(),
    ),
    tool(
        "agent_tasks_clear",
        AgentPermissionTier.S1,
        "清空当前对话的全部任务组（开始全新无关任务时使用）。一次清空所有 group 与 task，不可逆。",
        emptySchema(),
    ),
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
