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
import org.mozilla.reference.browser.agent.core.AgentPanelBridge
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.AgentToolRegistry
import org.mozilla.reference.browser.agent.core.AgentToolResult
import org.mozilla.reference.browser.agent.core.ChatBackend
import org.mozilla.reference.browser.agent.core.Role
import org.mozilla.reference.browser.agent.core.ChatToolCall
import org.mozilla.reference.browser.agent.core.TaskTrackerManager
import org.mozilla.reference.browser.agent.core.compactSummary

private const val TOOL_LOOP_LIMIT = 250

// Hard cap on how much of a single tool result is fed back into the model transcript. Page
// source / fetched bodies / page_exec returns can be hundreds of KB; without a cap one tool
// call blows the context window. The UI card already shows only a short summary, so this only
// trims what the *model* sees. ~32K chars ≈ a few thousand tokens — enough for real results,
// small enough to keep many tool calls in one turn.
private const val TOOL_RESULT_CAP = 32_000

/** Truncates an over-long tool result, appending a marker so the model knows it was clipped. */
private fun capToolResult(text: String): String {
    if (text.length <= TOOL_RESULT_CAP) return text
    val omitted = text.length - TOOL_RESULT_CAP
    return text.take(TOOL_RESULT_CAP) +
        "\n\n…[工具结果过长，已截断 $omitted 字符。请用更精确的查询（page_search/page_query/container_grep）" +
        "或先把内容保存到容器文件（如 page_save_to_container）再分段读取，避免一次性拉取整页源码。]"
}

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
            state.messages.add(ChatMsg(fromUser = false, text = "请先在设置里填写 API Key，并选择一个模型。"))
            state.saveCurrentChat()
            state.generating = false
            return
        }
        // Structured transcript (state.convo) is the source of truth fed to the model; the
        // user turn is already appended by PanelState.send(). The system prompt is prepended
        // per request so it always reflects the current permission tier / persona / memory.
        val convo = state.convo
        val backend = ChatBackend.of(config.format)
        // The bridge backs the chat-history / memory / task-tracker tools the registry calls.
        val bridge = panelBridge(state)
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
            panelBridge = bridge,
        )
        val toolSpecs = registry.allowedToolSpecs(state.permTier)

        // Capture the turn id this coroutine belongs to. PanelState bumps turnEpoch on every
        // stop / new chat / chat switch / new send, so a turn that has been superseded or
        // stopped will see alive() == false and must not write into the now-current chat.
        val myEpoch = state.turnEpoch
        turnJob = scope.launch {
            fun alive() = state.turnEpoch == myEpoch
            var producedAnything = false
            try {
                var loops = 0
                while (loops < TOOL_LOOP_LIMIT) {
                    if (!alive()) return@launch
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
                        // Drop streamed fragments once this turn is no longer live (stopped or
                        // superseded by a new chat) so they never append to another chat.
                        if (alive()) {
                            // Rough running token estimate for the top working bar (~4 chars/token).
                            state.genTokens += (frag.length + 3) / 4
                            if (streamIndex < 0) {
                                streamIndex = state.messages.size
                                state.messages.add(ChatMsg(fromUser = false, text = frag))
                                state.saveCurrentChat()
                            } else {
                                val cur = state.messages[streamIndex]
                                state.messages[streamIndex] = cur.copy(text = cur.text + frag)
                            }
                        }
                    }
                    var reply = backend.complete(config, messages, toolSpecs, onDelta)
                    // Fallback: some OpenAI-compatible endpoints ignore stream=true and return a
                    // single non-SSE body, yielding nothing streamed. Retry once non-streaming.
                    if (streamIndex < 0 && reply.content.isBlank() && reply.toolCalls.isEmpty()) {
                        reply = backend.complete(config, messages, toolSpecs, null)
                    }
                    // Stop / chat-switch may have happened during streaming; discard this reply
                    // rather than commit it (and its tool calls) into the now-current chat.
                    if (!alive()) return@launch
                    if (reply.content.isNotBlank()) {
                        if (streamIndex < 0) {
                            state.messages.add(ChatMsg(fromUser = false, text = reply.content.trim()))
                        } else {
                            state.messages[streamIndex] =
                                state.messages[streamIndex].copy(text = reply.content.trim())
                        }
                        state.saveCurrentChat()
                        producedAnything = true
                    }
                    // Persist the assistant turn (text + any tool calls) into the transcript.
                    if (reply.content.isNotBlank() || reply.toolCalls.isNotEmpty()) {
                        convo.add(AgentMessage(Role.Assistant, reply.content, toolCalls = reply.toolCalls))
                        state.saveCurrentChat()
                    }
                    if (reply.toolCalls.isEmpty()) break
                    for (call in reply.toolCalls) {
                        val cardIndex = state.messages.size
                        state.messages.add(
                            ChatMsg(fromUser = false, text = "", tool = runningCard(call)),
                        )
                        state.saveCurrentChat()
                        val result = registry.call(state.permTier, call.name, call.arguments)
                        // A long-running tool call may outlive a stop / chat switch; bail before
                        // writing its card or result into the chat the user moved to.
                        if (!alive()) return@launch
                        state.messages[cardIndex] =
                            ChatMsg(fromUser = false, text = "", tool = buildToolCard(call, result))
                        state.saveCurrentChat()
                        // Tool calls are shown only here, inline in the chat stream — never
                        // repeated as sub-rows under their task (the task list must stay short).
                        producedAnything = true
                        // The model gets the FULL tool output; the UI card only shows a summary.
                        convo.add(
                            AgentMessage(
                                role = Role.Tool,
                                content = capToolResult(if (result.ok) result.text else "ERROR: ${result.text}"),
                                toolCallId = call.id,
                            ),
                        )
                        state.saveCurrentChat()
                    }
                }
                if (!alive()) return@launch
                if (loops >= TOOL_LOOP_LIMIT) {
                    state.messages.add(ChatMsg(fromUser = false, text = "工具循环已到 ${TOOL_LOOP_LIMIT} 次上限，已停止继续调用。"))
                    state.saveCurrentChat()
                }
                if (!producedAnything) {
                    state.messages.add(ChatMsg(fromUser = false, text = "(无内容)"))
                    state.saveCurrentChat()
                }
                maybeGenerateTitle(config, backend, state)
                maybeExtractMemories(config, backend, state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!alive()) return@launch
                // Paste the raw API/error text straight into the conversation (no emoji,
                // no decoration) so failures are easy to read and debug.
                state.messages.add(
                    ChatMsg(fromUser = false, text = e.message ?: e.javaClass.simpleName),
                )
                state.saveCurrentChat()
            } finally {
                // Only the live turn owns state.generating / persistence. A superseded or
                // stopped turn must not clear the spinner for the chat that replaced it
                // (stop() / newChat() / loadChat() already set generating = false themselves).
                if (alive()) {
                    state.generating = false
                    state.saveCurrentChat()
                }
            }
        }
    }

    /**
     * Bridge the chat-history / memory tools onto [PanelState]. The registry only ever
     * calls these on the main thread (via its runOnMainResult), so direct Compose-state
     * access here is safe.
     */
    private fun panelBridge(state: PanelState): AgentPanelBridge = object : AgentPanelBridge {
        // Single TaskTrackerManager per bridge instance — bound to state.tracker so every
        // mutation reassigns the mutable state slot (driving Compose recomposition) and
        // flushes to the per-chat JSON file via saveCurrentChat().
        private val taskManager = TaskTrackerManager(
            read = { state.tracker },
            write = { state.tracker = it },
            persist = { state.saveCurrentChat() },
        )

        override fun listChats(): JSONArray {
            val arr = JSONArray()
            state.chats.forEach { chat ->
                arr.put(
                    JSONObject()
                        .put("id", chat.id)
                        .put("title", chat.title)
                        .put("titled", chat.titled)
                        .put("current", chat.id == state.currentChatId),
                )
            }
            return arr
        }

        override fun openChat(id: String): Boolean {
            if (state.chats.none { it.id == id }) return false
            state.loadChat(id)
            return true
        }

        override fun renameChat(id: String, title: String): Boolean {
            val chat = state.chats.firstOrNull { it.id == id } ?: return false
            val cleaned = title.trim().replace(Regex("\\s+"), " ").take(20)
            if (cleaned.isBlank()) return false
            if (id == state.currentChatId) {
                state.replaceCurrentTitle(cleaned)
            } else {
                chat.title = cleaned
                state.persist()
            }
            return true
        }

        override fun listMemories(): JSONArray {
            val arr = JSONArray()
            state.memories.forEachIndexed { index, value ->
                arr.put(JSONObject().put("index", index).put("value", value))
            }
            return arr
        }

        override fun addMemory(value: String): Int {
            state.addMemory(value)
            return state.memories.size - 1
        }

        override fun deleteMemory(index: Int): Boolean {
            if (index !in state.memories.indices) return false
            state.removeMemory(index)
            return true
        }

        override fun taskTrackerSnapshot(): JSONObject = state.tracker.toJson()

        override fun taskCurrentId(): String? = state.tracker.currentTaskId

        override fun taskCreateGroup(title: String): String = taskManager.createGroup(title)

        override fun taskAddTask(groupId: String, title: String, description: String): String? =
            taskManager.addTask(groupId, title, description)

        override fun taskStart(taskId: String): Boolean = taskManager.startTask(taskId)

        override fun taskComplete(taskId: String): Boolean = taskManager.completeTask(taskId)

        override fun taskFail(taskId: String, error: String): Boolean = taskManager.failTask(taskId, error)

        override fun taskCancel(taskId: String): Boolean = taskManager.cancelTask(taskId)

        override fun taskUpdate(taskId: String, title: String?, description: String?): Boolean =
            taskManager.updateTask(taskId, title, description)

        override fun taskDelete(taskId: String): Boolean = taskManager.deleteTask(taskId)

        override fun taskClearAll(): Boolean = taskManager.clearAll()

        override fun taskRecordToolCall(
            taskId: String,
            name: String,
            status: String,
            durationMs: Long,
            error: String,
        ): Boolean = taskManager.recordToolCall(taskId, name, status, durationMs, error)
    }

    /** Card shown while a tool call is executing (before the result/approval resolves). */
    private fun runningCard(call: ChatToolCall): ToolCard {
        val args = try { JSONObject(call.arguments) } catch (_: Exception) { JSONObject() }
        return ToolCard(
            name = call.name,
            status = "Running",
            summary = toolHeader(call.name, args, running = true),
            running = true,
        )
    }

    /** Agent-standard English display verb for a tool: gerund (running) + past tense (done). */
    private fun toolVerb(name: String): Pair<String, String> = when (name) {
        "page_index", "page_query", "container_read", "private_file_read", "container_head",
        "network_get", "proxy_status",
        -> "Reading" to "Read"
        "container_list", "private_file_list", "network_list" -> "Listing" to "Listed"
        "page_search", "web_search", "container_grep" -> "Searching" to "Searched"
        "page_navigate", "page_reload", "page_back", "page_forward" -> "Navigating" to "Navigated"
        "page_exec", "page_fetch", "browser_request" -> "Running" to "Ran"
        else -> "Running" to "Ran"
    }

    /**
     * Agent-standard header text "Verb(target)" (e.g. "Read(agent.js)"); while [running] the
     * gerund + an ellipsis is used ("Reading(agent.js)…"). A blank target collapses to the bare
     * verb. Edit/write tools build their own "Update(path)" headers in [buildToolCard].
     */
    private fun toolHeader(name: String, args: JSONObject, running: Boolean): String {
        val (gerund, past) = toolVerb(name)
        val verb = if (running) gerund else past
        val target = argPreview(name, args)
        val head = if (target.isBlank()) verb else "$verb($target)"
        return if (running) "$head…" else head
    }

    /**
     * The single most meaningful argument for a tool, shown in the `name(arg)` header (agent
     * standard format). Collapsed to one line and clipped so the header never wraps.
     */
    private fun argPreview(name: String, args: JSONObject): String {
        val v = when (name) {
            "page_exec" -> args.optString("code")
            "write_code_file", "delete_code_from_file", "container_read", "container_write",
            "container_list", "container_serve_url", "private_file_read", "private_file_write",
            "private_file_list",
            -> args.optString("path")
            "page_search", "web_search" -> args.optString("query")
            "page_query", "page_click", "page_set_text", "page_set_html", "page_set_attr",
            "page_wait_for_element", "dom_highlight_element",
            -> args.optString("selector")
            "network_list" -> args.optString("urlContains")
            "page_navigate", "browser_request", "page_fetch" -> args.optString("url")
            else -> ""
        }
        val one = v.replace('\n', ' ').replace('\r', ' ').trim()
        return if (one.length > 64) one.take(64) + "…" else one
    }

    /**
     * Builds the compact, agent-style result card for one finished tool call. write_code_file /
     * delete_code_from_file get a "wrote/deleted <path> +N/-M" summary plus a +/- diff built
     * from the call arguments; other tools get a short one-line snippet (never the full body —
     * that only goes back to the model).
     */
    private fun buildToolCard(call: ChatToolCall, result: AgentToolResult): ToolCard {
        val status = if (result.ok) "Done" else "Failed"
        val args = try {
            org.json.JSONObject(call.arguments)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        // Agent-standard header: ● Verb(target). Edit/write tools below override it with an
        // "Update(<path>)" header + an "Added N lines" diff label since the +/- diff carries
        // the same intent.
        val header = toolHeader(call.name, args, running = false)
        if (!result.ok) {
            // Surface the full raw error text in the chat (not a one-line snippet) so tool
            // failures are easy to debug. ToolCardView renders [error] as a gray text block.
            return ToolCard(call.name, status, header, error = result.text.trim())
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
                ToolCard(call.name, status, "Update($path)", editDiff(removedText, code, startLine), diffLabel = diffLabel(added, removed))
            }
            "delete_code_from_file" -> {
                val path = res.optString("path", args.optString("path", ""))
                val removedText = res.optString("removedPreview", args.optString("code", ""))
                val removed = res.optInt("removedLines", lineCount(removedText))
                val startLine = res.optInt("removedStartLine", args.optInt("startLine", 1).coerceAtLeast(1))
                ToolCard(call.name, status, "Update($path)", removedDiff(removedText, startLine), diffLabel = diffLabel(0, removed))
            }
            "container_write", "private_file_write" -> {
                val path = args.optString("path", "")
                val content = args.optString("content", "")
                val added = lineCount(content)
                ToolCard(call.name, status, "Write($path)", addedDiff(content, 1), diffLabel = diffLabel(added, 0))
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
            "create_plan" -> ToolCard(call.name, status, "Create plan")
            "update_plan" -> ToolCard(call.name, status, "Update plan")
            // Every other tool: agent-standard header + the raw result shown in the ⎿ block
            // (capped/expandable by ToolCardView). Empty/`{}` results show just the header.
            else -> ToolCard(call.name, status, header, output = toolOutputText(result.text))
        }
    }

    /**
     * The raw tool result shown in the ⎿ block, pretty-printed when it is JSON so it reads like
     * a real command output. Empty / `{}` results return "" (header-only card). Capped so a huge
     * result never bloats a card — the model still gets the full untruncated text separately.
     */
    private fun toolOutputText(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == "{}" || t == "null") return ""
        val pretty = try {
            when {
                t.startsWith("{") -> JSONObject(t).toString(2)
                t.startsWith("[") -> JSONArray(t).toString(2)
                else -> t
            }
        } catch (_: Exception) {
            t
        }
        return if (pretty.length > 8000) pretty.take(8000) + "\n…" else pretty
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
        return ToolCard(name, status, "Update($count)", blocks.joinToString("\n"), diffLabel = diffLabel(totalAdded, totalRemoved))
    }

    private fun pageEditCard(name: String, status: String, selector: String, value: String): ToolCard {
        val added = lineCount(value).coerceAtLeast(1)
        val target = if (selector.isBlank()) "current page" else selector
        return ToolCard(name, status, "Update($target)", addedDiff(value, 1), diffLabel = diffLabel(added, 0))
    }

    /** English diff summary shown on the "⎿" line above a diff, e.g. "Added 3 lines, removed 1 line". */
    private fun diffLabel(added: Int, removed: Int): String {
        fun lines(n: Int) = if (n == 1) "$n line" else "$n lines"
        return when {
            added > 0 && removed > 0 -> "Added ${lines(added)}, removed ${lines(removed)}"
            removed > 0 -> "Removed ${lines(removed)}"
            else -> "Added ${lines(added)}"
        }
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
     * it in the current [SavedChat]. It never updates live after that first attempt; titles are
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
            panelBridge = panelBridge(state),
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

            # 任务追踪（agent_tasks_*）
            - 多步骤、跨多个工具调用、可能耗时较久的任务，开工前用 agent_tasks_create 建任务组，再用 agent_tasks_add 把可执行子任务依次加进去。子任务初始是 PENDING。
            - 真正开始做某子任务前调用 agent_tasks_start（自动记录开始时间、并把它设为 currentTask，后续工具调用都会自动挂在它名下）。完成后立即 agent_tasks_complete；失败用 agent_tasks_fail 带 error；改主意/不再做用 agent_tasks_cancel。
            - 一两步就能做完的请求（单次抓包、单次点击、单次问答）不要建组——任务卡是为长流程服务的，不是流水账。
            - 任务清单出现在聊天流里且会在每轮提示中以 compactSummary 形式回给你，所以你能跨工具调用 / 跨多轮保持上下文。开新一轮无关任务时可以 agent_tasks_clear 重置；但用户主动建过的组别轻易清。
            - 改子任务标题用 agent_tasks_rename（只改标题，等价只传 title 的 agent_tasks_update）；整条删掉某子任务用 agent_tasks_delete（不同于 agent_tasks_cancel 的“标记取消”）。
            - 这些 agent_tasks_* 工具都属于 S1，调用不会弹审批；放心多用。

            # 多标签（tab_*）
            - 涉及具体页面操作前若不确定当前页是哪个，先 tab_current；要在多个 tab 间挑一个，先 tab_list 看 id/url/title/selected，再 tab_switch 切过去。切换后再调 page_* 才作用在新 tab。

            # 页面交互（优先用专用工具，少用 page_exec）
            - 跳转/刷新/前进/后退当前标签页用 page_navigate / page_reload / page_back / page_forward；它们走浏览器原生导航，不依赖页面 JS/CSP。
            - 点元素用 page_click(selector)，输入用 page_type(selector,text)，等元素出现用 page_wait_for_element(selector,timeoutMs)。它们走原生事件，比自己拼 JS 丢给 page_exec 更稳，也不需要 S3。
            - selector 工具会尽量穿透 open shadow DOM，并在操作前临时绕过常见强 CSS（pointer-events/visibility/opacity/scroll-margin）。遇到强 CSS 页面，先用 page_query 看 visible/rect，再 click/type/wait。
            - page_exec 是兜底：只在没有专用工具能表达的场景（复杂计算 / 多步 DOM 操作 / 读自定义属性）才用，而且要 S3。
            - page_scroll 控制滚动；动态加载的列表往往要先滚再 wait_for_element。
            - 仅展示/调试用途（描边、高亮、临时改样式）用 dom_highlight_element / dom_inject_css，不要拼 JS。

            # 读网页源码（务必节制上下文，别拉整页）
            - 绝不要用 page_exec 返回 document.documentElement.outerHTML / innerHTML 这种整页源码——整页几十~几百 KB，会直接撑爆上下文，工具结果超长也会被自动截断。
            - 正确做法：直接 page_search(query) 拿 ±150 字符片段（首次会自动索引，无需先调 page_index），或 page_query(selector) 拿命中元素摘要，按需逐段取；想先看页面结构摘要（标题/heading/form/link）才用 page_index。
            - page_search / page_index 都跑在内容脚本世界，读的是已渲染 DOM，不受页面 CSP 限制；不要因为页面 CSP 严格就改用 page_exec 拉源码。
            - 需要完整源码做后续分析时，用 page_save_to_container 把整页 HTML 存进容器文件（内容不进上下文），再用 container_grep / container_sed / container_head 分段检索，而不是把源码塞进对话。

            # 网络等待（先发动作、再等结果）
            - 点击一个按钮后要等它发的请求/响应，用 network_wait_request / network_wait_response(method, urlContains, timeoutMs)。它们读 NetFlowStore 快照轮询，pump 安全。
            - 正确顺序：先 page_click 触发，再 network_wait_response 等结果——反过来会错过事件起始时间。
            - 拿到 flowId 后再用 network_get 取详情。

            # 控制台日志
            - console_list 是 best-effort：注入点之后的页面日志才能收到，注入前丢失；CSP 严格的页面可能完全拿不到。看不到日志不要硬试。

            # 网络规则（增量 vs 整表替换）
            - 已经有规则的情况下，追加单条用 mock_add_rule / replace_add_rule（不会覆盖现有规则）；想查当前规则用 mock_list / replace_list。
            - 整批改写或清空才用 mock_set / replace_set（会替换整张表）。
            - intercept_set 缺省 reqAll/respAll 都是 false——不要无参调用，否则不会拦截任何东西也不会出错；要拦请求/响应必须显式传 reqAll/respAll=true 或在 rules 里加 action=intercept 规则。
            - intercept_set 支持 interceptTelemetry/interceptHeartbeat/interceptNoise/interceptCookie 四类低价值流量开关；默认都放行。修改拦截后，前端面板会同步显示原生真实状态。
            - 改拦截前先用 intercept_list_rules 看当前快照（enabled、reqAll/respAll、四个低价值开关、rules 列表），避免重复或冲突下发。

            # Agent 容器（类 shell 文件工具）
            - Agent 有一个沙箱容器目录（外部存储），所有 container_* 工具的路径都是相对该根的相对路径；禁止用 / 开头的绝对路径或 .. 越界。
            - 基础增删改查：container_list / container_read / container_write / container_mkdir / container_touch / container_append / container_cp / container_mv / container_rm（删目录或 rmdir 的 recursive=true 等价 rm -rf）。这些写类工具是 S2、会弹审批。
            - 类 shell 只读检索（S1，不弹审批）：container_grep（正则搜内容）、container_find（按文件名通配符找）、container_head/tail（看首/尾 N 行）、container_wc（行/词/字节数）、container_stat（元信息）、container_du（递归算大小）、container_tree（树状列目录）、container_file_type（判 text/binary）、container_which（按文件名定位）、container_sed（读指定行区间）、container_realpath（规范化+越界校验）、container_diff（逐行比对两文件）。
            - 这些只是在沙箱内用 Kotlin 实现的等价能力，不是真的调用系统二进制，也不能跳出容器；不要假设有 bash/管道/重定向。

            # 凭证（先脱敏、谨慎明文）
            - 想知道某 flow 上有哪些凭证，先用 cookie_list_redacted / auth_headers_redacted（S2，值已脱敏，只暴露 scheme 与长度），据此判断要不要明文。
            - 真正需要明文才用 cookie_reveal / auth_reveal / set_cookie_headers_reveal（都是 S3，会弹原生确认；用户不批就拿不到）。明文凭证只用于当前任务，绝不原样回灌进聊天，也不要写进记忆或容器文件。
            - 改包/重放优先用占位符（如 {{cookie:<flowId>}}）让原生回填，而不是把明文 token 带进模型上下文。

            # 插件管理
            - plugin_list（S1）列出所有内置插件及启用态；plugin_get_permissions（S1）看单个插件描述。
            - plugin_enable / plugin_disable（S2，弹审批）启停插件，会触发其 onEnable/onDisable 并持久化。启停插件可能改变抓包/改包行为，动手前先 plugin_list 确认当前状态。

            # 会话与长期记忆
            - 用户问“之前我们聊过 X”时先 agent_history_list 找出 id，再 agent_history_open 切过去查。重命名用 agent_history_rename。
            - agent_memory_* 只用于跨对话长期记忆（用户身份、稳定偏好、长期项目背景）；一次性任务进展不要存记忆，那是任务追踪的事。

            # 输出风格
            - 简洁、直接、面向结果。先给答案/结论，再按需补充。能一句说清就不要三句。
            - 你的回复会被渲染成完整 Markdown（标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 围栏代码块 / 有序无序列表 / 任务列表 / 引用 / 表格 / 链接 / 分隔线）。该用结构时就用 Markdown，让回答更清晰；但别为了排版而排版，简单回答保持简单。
            - 文件名、路径、选择器、命令、函数名、工具名等用反引号行内代码（如 `AgentEngine.kt`、`git status`）；贴多行代码或命令用三反引号围栏并标注语言；涉及具体行号写成 `path:line`。
            - 需要强调的关键词用 **粗体**；分点说明用列表；多列数据用表格。
            - 不要堆砌寒暄和无意义的过程叙述；不要在结尾重复你刚做过的事。

            # 当前权限层（实时权威，以本字段为准，忽略历史对话里对权限层的任何旧描述）
            当前权限层 = ${state.permTier.name}（${state.permTier.describe()}）
            - S1：只读浏览器层信息、读取页面源码摘要/DOM 摘要、查看抓包、读写 Agent 本地容器文件、创建/更新计划、写入/删除容器代码文件。
            - S2：包含 S1，允许批量写 Agent 外部容器、生成容器文件 URL、从浏览器 App 进程发请求、受限写当前页面 DOM、滑动当前页面界面、原生导航当前标签页、开启/关闭代理、拦截/改请求响应、替换、Mock、弱网。
            - S3：包含 S2，允许从当前网页 page world 发起 fetch、执行任意 page world JavaScript、读取凭证明文、读写浏览器 App 私有目录文件。
            若用户要求的操作超出当前权限层，明确说明需要切换到哪一层，不要假装已经做了。

            # 个性化
            当前风格：${state.persona}
            推理档：${state.tier.name}
            记忆：
            $memory
        """.trimIndent()
    }

    private fun runtimeStatePrompt(state: PanelState, toolNames: List<String>): String {
        val base = """
            （运行时状态）
            当前权限层 = ${state.permTier.name}（${state.permTier.describe()}）。这是本轮请求的最新权威权限层；忽略历史对话里任何不同说法。
            当前模型可见工具 = ${toolNames.joinToString(", ").ifBlank { "无" }}。
            当前推理档 = ${state.tier.name}；临时聊天 = ${state.tempChat}；记忆启用 = ${state.memoryEnabled}。
            如果用户问“现在是什么权限层”，必须回答 ${state.permTier.name}。
        """.trimIndent()
        // Visual Task Tracker compact summary — empty trackers contribute nothing, so the
        // prompt stays lean for chats that don't use tasks. When tasks exist, the model gets
        // a stable Completed / In Progress / Remaining / Failed / Tools / Next Step section
        // before each turn so it can keep continuity across long sessions and tool churn.
        val summary = state.tracker.compactSummary()
        return if (summary.isBlank()) base else base + "\n\n" + summary
    }

    private fun AgentPermissionTier.describe(): String = when (this) {
        AgentPermissionTier.S1 -> "S1 最低权限"
        AgentPermissionTier.S2 -> "S2 基础改写权限"
        AgentPermissionTier.S3 -> "S3 浏览器私有/API 权限"
    }
}
