/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mozilla.reference.browser.agent.core.AgentApprover
import org.mozilla.reference.browser.agent.core.AgentMessage
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.AgentToolRegistry
import org.mozilla.reference.browser.agent.core.ChatBackend
import org.mozilla.reference.browser.agent.core.Role
import org.mozilla.reference.browser.agent.core.ChatToolCall

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
        val index = state.messages.size
        state.messages.add(ChatMsg(fromUser = false, text = ""))

        turnJob = scope.launch {
            val acc = StringBuilder()
            try {
                var loops = 0
                while (loops < 6) {
                    loops += 1
                    val messages = ArrayList<AgentMessage>(convo.size + 1)
                    messages.add(AgentMessage(Role.System, systemPrompt(state)))
                    messages.addAll(convo)
                    val reply = backend.complete(config, messages, toolSpecs)
                    if (reply.content.isNotBlank()) {
                        appendAssistant(acc, index, state, reply.content)
                    }
                    // Persist the assistant turn (text + any tool calls) into the transcript.
                    if (reply.content.isNotBlank() || reply.toolCalls.isNotEmpty()) {
                        convo.add(AgentMessage(Role.Assistant, reply.content, toolCalls = reply.toolCalls))
                    }
                    if (reply.toolCalls.isEmpty()) break
                    for (call in reply.toolCalls) {
                        appendAssistant(acc, index, state, toolStatus(call, "等待确认"))
                        val result = registry.call(state.permTier, call.name, call.arguments)
                        val shown = result.text.take(900)
                        appendAssistant(
                            acc,
                            index,
                            state,
                            toolStatus(call, if (result.ok) "完成\n$shown" else "失败\n$shown"),
                        )
                        convo.add(
                            AgentMessage(
                                role = Role.Tool,
                                content = if (result.ok) result.text else "ERROR: ${result.text}",
                                toolCallId = call.id,
                            ),
                        )
                    }
                }
                if (loops >= 6) appendAssistant(acc, index, state, "工具循环已到上限，已停止继续调用。")
                if (acc.isEmpty() && state.messages[index].text.isEmpty()) {
                    state.messages[index] = ChatMsg(fromUser = false, text = "(无内容)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prefix = if (acc.isEmpty()) "" else acc.toString() + "\n\n"
                state.messages[index] = ChatMsg(fromUser = false, text = prefix + "⚠️ " + (e.message ?: e.javaClass.simpleName))
            } finally {
                state.generating = false
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

    private fun appendAssistant(acc: StringBuilder, index: Int, state: PanelState, text: String) {
        if (text.isBlank()) return
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(text.trim())
        state.messages[index] = ChatMsg(fromUser = false, text = acc.toString())
    }

    private fun toolStatus(call: ChatToolCall, status: String): String =
        "工具 ${call.name}: $status"

    private fun systemPrompt(state: PanelState): String {
        val memory = if (state.memoryEnabled && state.memories.isNotEmpty()) {
            state.memories.joinToString("\n") { "- $it" }
        } else {
            "（无）"
        }
        return """
            你是 BrowserHelper 内置悬浮窗 Agent，运行在 Android GeckoView 浏览器内部。
            你只能使用宿主提供的内部工具，不要假装已经执行工具。
            工具调用会由悬浮窗按具体范围向用户二次确认；用户选择“记住”后，只能复用同一文件、同一 host、同一 flow 或同一 JS 片段的授权。
            用户拒绝时必须停止相关操作或改用安全替代方案。

            当前权限层：${state.permTier.describe()}
            S1：只读浏览器层信息、读取页面源码摘要/DOM 摘要、查看抓包、读写 Agent 本地容器文件、创建/更新计划、写入/删除容器代码文件。
            S2：包含 S1，允许批量写 Agent 外部容器、生成容器文件 URL、从浏览器 App 进程发请求、受限写当前页面 DOM、开启/关闭代理、拦截/改请求响应、替换、Mock、弱网。
            S3：包含 S2，允许从当前网页 page world 发起 fetch、执行任意 page world JavaScript、读取凭证明文、读写浏览器 App 私有目录文件。

            当前风格：${state.persona}
            推理档：${state.tier.name}
            记忆：
            $memory
        """.trimIndent()
    }

    private fun AgentPermissionTier.describe(): String = when (this) {
        AgentPermissionTier.S1 -> "S1 最低权限"
        AgentPermissionTier.S2 -> "S2 基础改写权限"
        AgentPermissionTier.S3 -> "S3 浏览器私有/API 权限"
    }
}
