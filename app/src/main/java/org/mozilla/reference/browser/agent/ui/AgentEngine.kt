/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.reference.browser.agent.core.AgentApprover
import org.mozilla.reference.browser.agent.core.AgentConfig
import org.mozilla.reference.browser.agent.core.AgentMessage
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.AgentToolRegistry
import org.mozilla.reference.browser.agent.core.AgentToolResult
import org.mozilla.reference.browser.agent.core.ChatBackend
import org.mozilla.reference.browser.agent.core.Role
import org.mozilla.reference.browser.agent.core.ChatToolCall

private const val TOOL_LOOP_LIMIT = 250

/**
 * Orchestrates one model turn over [PanelState]. The UI calls [start] after the user message
 * is already appended to the structured transcript ([PanelState.convo]); the engine prepends
 * the system prompt, calls the backend, and runs the tool loop, rewriting a single assistant
 * entry in [PanelState.messages] as text and tool statuses accrue. Provider wire details live
 * in the backends.
 */
class AgentEngine(context: Context) {
    private var turnJob: Job? = null
    private val appContext = context.applicationContext

    fun start(scope: CoroutineScope, state: PanelState) {
        turnJob?.cancel()
        val config = state.buildConfig()
        if (config == null) {
            state.messages.add(ChatMsg(fromUser = false, text = "⚠️ 请先在设置里填写 API Key，并选择一个模型。"))
            state.generating = false
            return
        }
        // Structured transcript (state.convo) is the source of truth fed to the model; the
        // user turn is already appended by PanelState.send(). The system prompt is prepended
        // per request so it always reflects the current permission tier / persona / memory.
        val convo = state.convo
        val backend = ChatBackend.of(config.format)
        val registry = AgentToolRegistry(
            appContext,
            object : AgentApprover {
                override fun isApproved(scopeKey: String): Boolean = state.hasApprovalGrant(scopeKey)

                override suspend fun approve(request: org.mozilla.reference.browser.agent.core.AgentApprovalRequest) =
                    state.requestApproval(request)
            },
            searchKey = state.searchKey,
            searchUrl = state.searchUrl,
            onPlanChanged = { text ->
                state.planText = text
                state.planMode = true
            },
        )
        val toolSpecs = registry.allowedToolSpecs(state.permTier)

        turnJob = scope.launch {
            var producedAnything = false
            try {
                var loops = 0
                while (loops < TOOL_LOOP_LIMIT) {
                    loops += 1
                    val messages = ArrayList<AgentMessage>(convo.size + 2)
                    messages.add(AgentMessage(Role.System, systemPrompt(state)))
                    messages.addAll(convo)
                    // Keep volatile runtime facts at the end of the context so stale history
                    // cannot convince the model it is still on an older permission tier.
                    messages.add(AgentMessage(Role.System, runtimeStatePrompt(state, toolSpecs.map { it.name })))

                    // Stream the assistant text live into a dedicated message; tool calls (if
                    // any) come back in the same reply and are rendered as separate cards.
                    var streamIndex = -1
                    val onDelta: (String) -> Unit = { frag ->
                        if (streamIndex < 0) {
                            streamIndex = state.messages.size
                            state.messages.add(ChatMsg(fromUser = false, text = frag))
                        } else {
                            val cur = state.messages[streamIndex]
                            state.messages[streamIndex] = cur.copy(text = cur.text + frag)
                        }
                    }
                    var reply = backend.complete(config, messages, toolSpecs, onDelta)
                    // Fallback: some OpenAI-compatible endpoints ignore stream=true and return a
                    // single non-SSE body, yielding nothing streamed. Retry once non-streaming.
                    if (streamIndex < 0 && reply.content.isBlank() && reply.toolCalls.isEmpty()) {
                        reply = backend.complete(config, messages, toolSpecs, null)
                    }
                    if (reply.content.isNotBlank()) {
                        if (streamIndex < 0) {
                            state.messages.add(ChatMsg(fromUser = false, text = reply.content.trim()))
                        } else {
                            state.messages[streamIndex] =
                                state.messages[streamIndex].copy(text = reply.content.trim())
                        }
                        producedAnything = true
                    }
                    // Persist the assistant turn (text + any tool calls) into the transcript.
                    if (reply.content.isNotBlank() || reply.toolCalls.isNotEmpty()) {
                        convo.add(AgentMessage(Role.Assistant, reply.content, toolCalls = reply.toolCalls))
                    }
                    if (reply.toolCalls.isEmpty()) break
                    for (call in reply.toolCalls) {
                        val cardIndex = state.messages.size
                        state.messages.add(
                            ChatMsg(fromUser = false, text = "", tool = runningCard(call)),
                        )
                        val result = registry.call(state.permTier, call.name, call.arguments)
                        state.messages[cardIndex] =
                            ChatMsg(fromUser = false, text = "", tool = buildToolCard(call, result))
                        producedAnything = true
                        // The model gets the FULL tool output; the UI card only shows a summary.
                        convo.add(
                            AgentMessage(
                                role = Role.Tool,
                                content = if (result.ok) result.text else "ERROR: ${result.text}",
                                toolCallId = call.id,
                            ),
                        )
                    }
                }
                if (loops >= TOOL_LOOP_LIMIT) {
                    state.messages.add(ChatMsg(fromUser = false, text = "工具循环已到 ${TOOL_LOOP_LIMIT} 次上限，已停止继续调用。"))
                }
                if (!producedAnything) {
                    state.messages.add(ChatMsg(fromUser = false, text = "(无内容)"))
                }
                maybeGenerateTitle(config, backend, state)
                maybeExtractMemories(config, backend, state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.messages.add(
                    ChatMsg(fromUser = false, text = "⚠️ " + (e.message ?: e.javaClass.simpleName)),
                )
            } finally {
                state.generating = false
            }
        }
    }

    /** Card shown while a tool call is executing (before the result/approval resolves). */
    private fun runningCard(call: ChatToolCall): ToolCard =
        ToolCard(name = call.name, status = "运行中", summary = "${call.name} 运行中…")

    /**
     * Builds the compact, agent-style result card for one finished tool call. write_code_file /
     * delete_code_from_file get a "wrote/deleted <path> +N/-M" summary plus a +/- diff built
     * from the call arguments; other tools get a short one-line snippet (never the full body —
     * that only goes back to the model).
     */
    private fun buildToolCard(call: ChatToolCall, result: AgentToolResult): ToolCard {
        val status = if (result.ok) "完成" else "失败"
        if (!result.ok) {
            return ToolCard(call.name, status, "${call.name} 失败：${oneLine(result.text)}")
        }
        val args = try {
            org.json.JSONObject(call.arguments)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        val res = try {
            org.json.JSONObject(result.text)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        return when (call.name) {
            "write_code_file" -> {
                val path = res.optString("path", args.optString("path", ""))
                val code = args.optString("code", "")
                val mode = args.optString("mode", "replace")
                val added = res.optInt("addedLines", lineCount(code))
                val removed = res.optInt("removedLines", 0)
                val startLine = if (mode == "append") res.optInt("oldLines", 0) + 1 else 1
                val removedText = res.optString("removedPreview", "")
                ToolCard(call.name, status, "Edited $path (+$added -$removed)", editDiff(removedText, code, startLine))
            }
            "delete_code_from_file" -> {
                val path = res.optString("path", args.optString("path", ""))
                val removedText = res.optString("removedPreview", args.optString("code", ""))
                val removed = res.optInt("removedLines", lineCount(removedText))
                val startLine = res.optInt("removedStartLine", args.optInt("startLine", 1).coerceAtLeast(1))
                ToolCard(call.name, status, "Edited $path (+0 -$removed)", removedDiff(removedText, startLine))
            }
            "container_write", "private_file_write" -> {
                val path = args.optString("path", "")
                val content = args.optString("content", "")
                val added = lineCount(content)
                ToolCard(call.name, status, "Wrote $path (+$added -0)", addedDiff(content, 1))
            }
            "batch_edit_files", "private_batch_edit_files" -> batchEditCard(call.name, status, args, res)
            "page_set_text" -> pageEditCard(call.name, status, args.optString("selector", ""), args.optString("text", ""))
            "page_set_html" -> pageEditCard(call.name, status, args.optString("selector", ""), args.optString("html", ""))
            "page_set_attr" -> pageEditCard(
                call.name,
                status,
                args.optString("selector", ""),
                "${args.optString("name", "")}=\"${args.optString("value", "")}\"",
            )
            "create_plan" -> ToolCard(call.name, status, "创建计划")
            "update_plan" -> ToolCard(call.name, status, "更新计划")
            else -> ToolCard(call.name, status, safeToolSummary(call, args, res))
        }
    }

    private fun oneLine(text: String): String =
        text.trim().replace("\n", " ").let { if (it.length > 96) it.take(96) + "…" else it }

    private fun safeToolSummary(call: ChatToolCall, args: JSONObject, res: JSONObject): String =
        when (call.name) {
            "container_read" -> "读取 ${args.optString("path", "")}，完整结果已返回给模型"
            "container_write" -> "写入 ${res.optString("path", args.optString("path", ""))}"
            "container_list" -> "列出容器目录"
            "page_index", "page_search", "page_query" -> "已读取页面信息，完整结果已返回给模型"
            "network_list", "network_get" -> "已读取网络记录，完整结果已返回给模型"
            "proxy_status" -> "已读取代理状态"
            "web_search" -> "已完成搜索，完整结果已返回给模型"
            "batch_edit_files", "private_batch_edit_files" -> "批量编辑完成"
            "container_serve_url" -> "生成容器资源地址"
            "browser_request", "page_fetch" -> "请求完成，响应正文已返回给模型"
            "proxy_start" -> "代理已开启"
            "proxy_stop" -> "代理已关闭"
            "throttle_set" -> "弱网规则已更新"
            "replace_set" -> "替换规则已更新"
            "mock_set" -> "Mock 规则已更新"
            "intercept_set" -> "拦截规则已更新"
            "intercept_pending" -> "已读取待处理拦截"
            "intercept_resolve", "resp_intercept_resolve" -> "拦截处理完成"
            "page_set_text", "page_set_html", "page_set_attr" -> "页面内容已更新"
            "page_exec" -> "页面脚本已执行，完整结果已返回给模型"
            "cookie_reveal" -> "凭据已读取，完整结果已返回给模型"
            "private_file_list" -> "列出私有目录"
            "private_file_read" -> "读取私有文件，完整结果已返回给模型"
            "private_file_write" -> "写入私有文件"
            else -> "${call.name} 完成，完整结果已返回给模型"
        }

    private fun batchEditCard(name: String, status: String, args: JSONObject, res: JSONObject): ToolCard {
        val edits = args.optJSONArray("edits") ?: JSONArray()
        val written = res.optJSONArray("written") ?: JSONArray()
        var totalAdded = 0
        var totalRemoved = 0
        val blocks = ArrayList<String>()
        for (i in 0 until edits.length()) {
            val edit = edits.optJSONObject(i) ?: continue
            val meta = written.optJSONObject(i) ?: JSONObject()
            val path = meta.optString("path", edit.optString("path", ""))
            val content = edit.optString("content", "")
            val added = meta.optInt("addedLines", lineCount(content))
            val removed = meta.optInt("removedLines", 0)
            totalAdded += added
            totalRemoved += removed
            blocks.add("• Edited $path (+$added -$removed)")
            blocks.add(editDiff(meta.optString("removedPreview", ""), content, 1))
        }
        val count = if (edits.length() == 1) "1 file" else "${edits.length()} files"
        return ToolCard(name, status, "Edited $count (+$totalAdded -$totalRemoved)", blocks.joinToString("\n"))
    }

    private fun pageEditCard(name: String, status: String, selector: String, value: String): ToolCard {
        val added = lineCount(value).coerceAtLeast(1)
        val target = if (selector.isBlank()) "current page" else selector
        return ToolCard(name, status, "Edited page $target (+$added -0)", addedDiff(value, 1))
    }

    private fun editDiff(oldText: String, newText: String, startLine: Int): String {
        val out = ArrayList<String>()
        if (oldText.isNotBlank()) {
            out.add(removedDiff(oldText, startLine))
        }
        out.add(addedDiff(newText, startLine))
        return out.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun addedDiff(text: String, startLine: Int): String =
        numberedLines(text, startLine, '+')

    private fun removedDiff(text: String, startLine: Int): String =
        numberedLines(text, startLine, '-')

    private fun numberedLines(text: String, startLine: Int, sign: Char, maxLines: Int = 80): String {
        val lines = splitLines(text)
        val out = ArrayList<String>()
        lines.take(maxLines).forEachIndexed { idx, line ->
            out.add("${(startLine + idx).toString().padStart(4)} $sign $line")
        }
        if (lines.size > maxLines) {
            out.add("     ⋮ ${lines.size - maxLines} more lines")
        }
        return out.joinToString("\n")
    }

    private fun lineCount(text: String): Int = splitLines(text).size

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines
    }

    /**
     * Once the first two real user sentences exist, asks the model for a short title and stores
     * it in [PanelState.recentChats]. It never updates live after that first attempt; titles are
     * a history label, not another per-turn summarization job.
     */
    private suspend fun maybeGenerateTitle(config: AgentConfig, backend: ChatBackend, state: PanelState) {
        if (state.tempChat || state.titleGenerated) return
        val seed = titleSeed(state) ?: return
        state.titleGenerated = true
        try {
            val msgs = listOf(
                AgentMessage(
                    Role.System,
                    "根据下面前两句用户输入，生成一个不超过 12 个汉字的对话主题。只输出主题本身，不要引号、标点或解释。",
                ),
                AgentMessage(Role.User, seed),
            )
            val reply = backend.complete(config.copy(maxTokens = 40), msgs, emptyList(), null)
            val title = reply.content.trim().replace("\n", " ").take(20)
            state.replaceCurrentTitle(title)
        } catch (_: Exception) {
            // Title is best-effort; the local provisional title remains in the drawer.
        }
    }

    private fun titleSeed(state: PanelState): String? {
        val pieces = ArrayList<String>()
        state.convo
            .filter { it.role == Role.User && isRealUserTitleText(it.content) }
            .forEach { msg ->
                pieces.addAll(firstSentences(msg.content))
                if (pieces.size >= 2) return pieces.take(2).joinToString("\n").take(500)
            }
        return pieces.firstOrNull()?.take(500)
    }

    private fun isRealUserTitleText(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() &&
            !t.startsWith("（系统提示）") &&
            !t.startsWith("（运行时状态）") &&
            !t.startsWith("我已批准上面的计划")
    }

    private fun firstSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text.trim()) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '\n') {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) out.add(s)
                sb.clear()
                if (out.size >= 2) return out
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    private suspend fun maybeExtractMemories(config: AgentConfig, backend: ChatBackend, state: PanelState) {
        if (!state.memoryEnabled || state.tempChat) return
        val transcript = memoryTranscript(state)
        if (transcript.isBlank()) return
        state.memoryUpdating = true
        try {
            val existing = if (state.memories.isEmpty()) {
                "（无）"
            } else {
                state.memories.joinToString("\n") { "- $it" }
            }
            val msgs = listOf(
                AgentMessage(
                    Role.System,
                    """
                        你是 BrowserHelper Agent 的长期记忆整理器。
                        只保留长期有用的信息：用户偏好、身份/项目背景、稳定约束、反复出现的工作习惯。
                        不要记录一次性任务、临时页面内容、工具状态、短期计划、普通寒暄。
                        只输出 JSON，不要 Markdown，不要解释。格式：
                        {"memories":["单条长期记忆","..."]}
                        memories 只返回新增的高价值条目，最多 6 条；不要把所有旧记忆重新输出，也不要生成摘要。
                    """.trimIndent(),
                ),
                AgentMessage(
                    Role.User,
                    """
                        现有摘要：
                        ${state.memorySummary.ifBlank { "（无）" }}

                        现有记忆：
                        $existing

                        最近对话：
                        $transcript
                    """.trimIndent(),
                ),
            )
            val reply = backend.complete(config.copy(maxTokens = 260), msgs, emptyList(), null)
            val obj = parseJsonObject(reply.content) ?: return
            val arr = obj.optJSONArray("memories")
            val items = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let { items.add(it) }
                }
            }
            if (items.isNotEmpty()) {
                state.mergeMemoryUpdate("", items)
            }
        } catch (_: Exception) {
            // Memory extraction is best-effort; never disturb the visible conversation.
        } finally {
            state.memoryUpdating = false
        }
    }

    fun generateMemorySummary(scope: CoroutineScope, state: PanelState) {
        val config = state.buildConfig()
        if (config == null) {
            state.memorySummaryError = "请先填写 API Key，并选择模型。"
            return
        }
        if (state.memoryUpdating) return
        scope.launch {
            state.memoryUpdating = true
            state.memorySummaryError = ""
            try {
                val memoryItems = state.memories.filter { it.isNotBlank() }
                if (memoryItems.isEmpty()) {
                    state.memorySummary = ""
                    state.memorySummaryError = "还没有可摘要的记忆。"
                    state.persist()
                    return@launch
                }
                val msgs = listOf(
                    AgentMessage(
                        Role.System,
                        """
                            你是 BrowserHelper Agent 的长期记忆摘要器。
                            把用户已保存的长期记忆压缩成一段摘要，用于后续系统提示。
                            摘要只能写稳定偏好、身份/项目背景、长期约束和工作习惯。
                            不要逐条罗列，不要把所有记忆原样塞进去，不要 Markdown。
                            只输出一到三句话摘要正文。
                        """.trimIndent(),
                    ),
                    AgentMessage(
                        Role.User,
                        memoryItems.joinToString("\n") { "- $it" }.take(6000),
                    ),
                )
                val reply = ChatBackend.of(config.format).complete(config.copy(maxTokens = 180), msgs, emptyList(), null)
                val summary = cleanGeneratedText(reply.content).take(4000)
                if (summary.isNotBlank()) {
                    state.updateMemorySummary(summary)
                } else {
                    state.memorySummaryError = "模型没有返回摘要。"
                }
            } catch (e: Exception) {
                state.memorySummaryError = "生成失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                state.memoryUpdating = false
            }
        }
    }

    private fun cleanGeneratedText(raw: String): String =
        raw.trim()
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun memoryTranscript(state: PanelState): String =
        state.convo
            .filter {
                ((it.role == Role.User && isRealUserTitleText(it.content)) || it.role == Role.Assistant) &&
                    it.content.isNotBlank()
            }
            .takeLast(10)
            .joinToString("\n") { msg ->
                val role = if (msg.role == Role.User) "用户" else "助手"
                "$role: ${msg.content.take(1200)}"
            }

    private fun parseJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    JSONObject(trimmed.substring(start, end + 1))
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    /** Cancels the running turn; the partial assistant text already in the list is kept. */
    fun cancel() {
        turnJob?.cancel()
        turnJob = null
    }

    /** Fetches the provider's model list into [PanelState.models]; silent on failure. */
    fun loadModels(scope: CoroutineScope, state: PanelState) {
        val config = state.buildConfig(requireModel = false) ?: return
        scope.launch {
            state.modelsLoading = true
            try {
                val list = ChatBackend.of(config.format).listModels(config)
                state.models.clear()
                state.models.addAll(list)
                if (state.selectedModel == null) {
                    state.selectedModel = list.firstOrNull()
                    state.persist()
                }
            } catch (e: Exception) {
                // Leave the list empty; the selector shows "暂无模型".
            } finally {
                state.modelsLoading = false
            }
        }
    }

    fun testTool(scope: CoroutineScope, state: PanelState, name: String) {
        scope.launch {
            runToolSelfTest(state, name)
        }
    }

    fun testAllTools(scope: CoroutineScope, state: PanelState) {
        if (state.toolsChecking) return
        scope.launch {
            state.toolsChecking = true
            try {
                org.mozilla.reference.browser.agent.core.agentToolInfos().forEach { tool ->
                    runToolSelfTest(state, tool.name)
                }
            } finally {
                state.toolsChecking = false
            }
        }
    }

    private suspend fun runToolSelfTest(state: PanelState, name: String) {
        state.toolChecks[name] = ToolCheckState("测试中", "")
        val registry = AgentToolRegistry(
            appContext,
            object : AgentApprover {
                override fun isApproved(scopeKey: String): Boolean = true
                override suspend fun approve(request: org.mozilla.reference.browser.agent.core.AgentApprovalRequest) =
                    org.mozilla.reference.browser.agent.core.AgentApprovalDecision.Approve
            },
            searchKey = state.searchKey,
            searchUrl = state.searchUrl,
            // Self-test must not engage the live plan card: testing create_plan/update_plan
            // would otherwise pop the plan UI mid-run and look like the plan started executing
            // before approval. Swallow the callback during self-test.
            onPlanChanged = { },
        )
        val result = registry.selfTest(name)
        state.toolChecks[name] = ToolCheckState(if (result.ok) "OK" else "失败", result.text)
    }

    private fun systemPrompt(state: PanelState): String {
        val memory = if (state.memoryEnabled) {
            val parts = ArrayList<String>()
            if (state.memorySummary.isNotBlank()) {
                parts.add("摘要：${state.memorySummary}")
            }
            val memoryItems = state.memories.filter { it.isNotBlank() }
            if (memoryItems.isNotEmpty()) {
                parts.add("条目：\n" + memoryItems.joinToString("\n") { "- $it" })
            }
            parts.joinToString("\n").ifBlank { "（无）" }
        } else {
            "（记忆已关闭）"
        }
        // Adapted/condensed from the local Codex prompt, retargeted to a browser Agent.
        return """
            你是 BrowserHelper 内置悬浮窗 Agent，运行在 Android GeckoView 浏览器内部，帮助用户检查、调试和改写当前网页与其网络流量。
            你像一个干练、克制的工程搭档：先理解意图，再用最直接的方式完成，不绕弯，不空谈。

            # 工作原则
            - 以当前运行时上下文为准，不要被旧历史里的权限层、模型名、工具清单或页面状态带偏。
            - 你不能只靠描述完成任务；凡是需要读取、修改、请求、拦截、写文件或执行 JS 的动作，都必须调用宿主工具。
            - 不要编造工具结果、网页内容、网络记录、文件内容或浏览器私有数据。工具失败就说明失败，并换更小范围的做法。
            - 你可能处在脏状态或长会话里；保留用户已有工作，不主动撤销无关改动。

            # 工具与执行
            - 你只能通过宿主提供的内部工具实际操作浏览器/页面/网络/容器；绝不假装已经调用工具或编造工具结果。
            - 工具按能力分三层（见下方“权限层”），你只会收到当前权限层可用的工具定义；看不到的工具就是当前不可用，不要假设它存在。
            - 每次工具调用会由悬浮窗按“具体范围”向用户二次确认（同一文件 / 同一 host+method / 同一拦截 flow / 同一段 JS / 同一条网络规则）。用户选择“记住”只在本会话、对同一范围复用授权。
            - 用户拒绝某次调用时，停止该操作或改用更安全、更小副作用的替代方案，并简短说明你打算怎么改。
            - 工具完整返回只供你推理，不要把原始 JSON、源码全文、响应体全文或私密数据原样回灌到聊天里；向用户只给结论和必要片段。

            # 计划工具
            - 只有多步、需要协调或容易跑偏的任务才用 create_plan / update_plan 写计划卡片；简单的一两步任务直接做，不要写计划。
            - 写了计划后，turn 不会结束；用户在计划卡点“批准并开始”后，你会收到“已批准”的消息，然后按计划继续执行。完成一个子步骤就 update_plan 标记进展。
            - 不要写只有单步的计划，也不要把显而易见的事拆成计划。

            # 输出风格
            - 简洁、直接、面向结果。先给答案/结论，再按需补充。能一句说清就不要三句。
            - 引用文件/路径/选择器时用反引号行内代码；贴代码或命令用三反引号围栏。涉及具体行号时写成 `path:line`。
            - 不要堆砌寒暄和无意义的过程叙述；不要在结尾重复你刚做过的事。

            # 当前权限层（实时权威，以本字段为准，忽略历史对话里对权限层的任何旧描述）
            当前权限层 = ${state.permTier.name}（${state.permTier.describe()}）
            - S1：只读浏览器层信息、读取页面源码摘要/DOM 摘要、查看抓包、读写 Agent 本地容器文件、创建/更新计划、写入/删除容器代码文件。
            - S2：包含 S1，允许批量写 Agent 外部容器、生成容器文件 URL、从浏览器 App 进程发请求、受限写当前页面 DOM、开启/关闭代理、拦截/改请求响应、替换、Mock、弱网。
            - S3：包含 S2，允许从当前网页 page world 发起 fetch、执行任意 page world JavaScript、读取凭证明文、读写浏览器 App 私有目录文件。
            若用户要求的操作超出当前权限层，明确说明需要切换到哪一层，不要假装已经做了。

            # 个性化
            当前风格：${state.persona}
            推理档：${state.tier.name}
            记忆：
            $memory
        """.trimIndent()
    }

    private fun runtimeStatePrompt(state: PanelState, toolNames: List<String>): String =
        """
            （运行时状态）
            当前权限层 = ${state.permTier.name}（${state.permTier.describe()}）。这是本轮请求的最新权威权限层；忽略历史对话里任何不同说法。
            当前模型可见工具 = ${toolNames.joinToString(", ").ifBlank { "无" }}。
            当前推理档 = ${state.tier.name}；临时聊天 = ${state.tempChat}；记忆启用 = ${state.memoryEnabled}。
            如果用户问“现在是什么权限层”，必须回答 ${state.permTier.name}。
        """.trimIndent()

    private fun AgentPermissionTier.describe(): String = when (this) {
        AgentPermissionTier.S1 -> "S1 最低权限"
        AgentPermissionTier.S2 -> "S2 基础改写权限"
        AgentPermissionTier.S3 -> "S3 浏览器私有/API 权限"
    }
}
