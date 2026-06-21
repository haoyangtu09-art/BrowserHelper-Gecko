/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.mozilla.reference.browser.agent.core.AgentApprovalDecision
import org.mozilla.reference.browser.agent.core.AgentApprovalRequest
import org.mozilla.reference.browser.agent.core.AgentConfig
import org.mozilla.reference.browser.agent.core.AgentMessage
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.ApiFormat
import org.mozilla.reference.browser.agent.core.Role

enum class PanelNav { Chat, Drawer, Settings, ModelSelector, Personalization, Memory, Advanced }

/** Reasoning effort tiers, mapped to a token budget (wired in a later round). */
enum class ReasonTier { Low, Middle, High }

/** Permission tiers shown on the model-selector slider. */
typealias PermTier = AgentPermissionTier
typealias ApprovalReq = AgentApprovalRequest

data class ToolCheckState(
    val status: String = "未测",
    val detail: String = "",
)

/**
 * UI state for the Agent panel. Conversation is in memory; provider settings, model
 * selection, permission tier, personalization, and memories are persisted by the host.
 */
class PanelState {
    val messages = mutableStateListOf<ChatMsg>()

    // Structured transcript fed to the model (system prompt is prepended per turn, not stored
    // here). Kept separate from [messages] (display-only) so tool calls / tool results survive
    // across turns without being reconstructed from rendered text.
    val convo = mutableListOf<AgentMessage>()
    var input by mutableStateOf("")
    var generating by mutableStateOf(false)
    var tempChat by mutableStateOf(false)
    var nav by mutableStateOf(PanelNav.Chat)
    var tier by mutableStateOf(ReasonTier.Middle)

    // Model list is driven by what the configured API key returns; empty → "暂无模型".
    val models = mutableStateListOf<String>()
    var selectedModel by mutableStateOf<String?>(null)
    var modelsLoading by mutableStateOf(false)

    // Recent conversations shown in the drawer; empty this round → only the "最近" label.
    val recentChats = mutableStateListOf<String>()

    // "我的 Agent": personalization style + memory toggle (memory only this round).
    var persona by mutableStateOf("平衡")
    var memoryEnabled by mutableStateOf(true)

    // Stored memories shown on the memory screen (in-memory this round).
    val memories = mutableStateListOf<String>()

    val toolChecks = mutableStateMapOf<String, ToolCheckState>()
    var toolsChecking by mutableStateOf(false)

    // Permission tier picked on the model-selector slider.
    var permTier by mutableStateOf(PermTier.S1)

    // Secondary-confirmation (二次确认) state. pendingApproval drives the bottom sheet.
    // ApproveSession stores the request scope for this conversation only; it is not a
    // global "always allow this tool" grant.
    var pendingApproval by mutableStateOf<ApprovalReq?>(null)
    private var approvalWaiter: CompletableDeferred<AgentApprovalDecision>? = null
    private val approvalGrants = LinkedHashSet<String>()

    // Plan mode (mirrors the assistant's own enter/write/exit-plan flow). UI表征 only.
    var planMode by mutableStateOf(false)
    var planText by mutableStateOf("")

    // Settings (memory only this round).
    var apiKey by mutableStateOf("")
    var apiUrl by mutableStateOf("")
    var searchKey by mutableStateOf("")
    var searchUrl by mutableStateOf("")
    var anthropicFormat by mutableStateOf(false) // false = OpenAI, true = Anthropic

    // Backend hooks wired by the host (AgentPanelHost) to an AgentEngine/settings store.
    var onTurn: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onLoadModels: (() -> Unit)? = null
    var onPersist: (() -> Unit)? = null
    var onToolSelfTest: ((String) -> Unit)? = null
    var onToolSelfTestAll: (() -> Unit)? = null

    /** Builds a provider config from the current settings, or null if not usable yet. */
    fun buildConfig(requireModel: Boolean = true): AgentConfig? {
        val key = apiKey.trim()
        if (key.isEmpty()) return null
        val model = selectedModel?.trim().orEmpty()
        if (requireModel && model.isEmpty()) return null
        return AgentConfig(
            format = if (anthropicFormat) ApiFormat.Anthropic else ApiFormat.OpenAI,
            apiKey = key,
            baseUrl = apiUrl.trim(),
            model = model,
            maxTokens = when (tier) {
                ReasonTier.Low -> 700
                ReasonTier.Middle -> 1400
                ReasonTier.High -> 2400
            },
        )
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        messages.add(ChatMsg(fromUser = true, text = text))
        convo.add(AgentMessage(Role.User, text))
        input = ""
        generating = true
        onTurn?.invoke()
    }

    fun stop() {
        generating = false
        onStop?.invoke()
    }

    fun newChat() {
        onStop?.invoke()
        messages.clear()
        convo.clear()
        approvalGrants.clear()
        generating = false
        input = ""
    }

    fun persist() {
        onPersist?.invoke()
    }

    suspend fun requestApproval(req: ApprovalReq): AgentApprovalDecision {
        approvalWaiter?.complete(AgentApprovalDecision.Deny)
        val waiter = CompletableDeferred<AgentApprovalDecision>()
        approvalWaiter = waiter
        pendingApproval = req
        return try {
            waiter.await()
        } catch (_: CancellationException) {
            AgentApprovalDecision.Deny
        } finally {
            if (approvalWaiter === waiter) {
                approvalWaiter = null
                pendingApproval = null
            }
        }
    }

    fun hasApprovalGrant(scopeKey: String): Boolean = approvalGrants.contains(scopeKey)

    fun resolveApproval(decision: AgentApprovalDecision) {
        val req = pendingApproval
        if (decision == AgentApprovalDecision.ApproveSession && req != null) {
            approvalGrants.add(req.scopeKey)
        }
        val waiter = approvalWaiter
        approvalWaiter = null
        pendingApproval = null
        waiter?.complete(decision)
    }
}

@Composable
fun rememberPanelState(): PanelState = remember { PanelState() }
