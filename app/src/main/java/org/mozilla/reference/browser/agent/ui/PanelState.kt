/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class PanelNav { Chat, Drawer, Settings, ModelSelector, Personalization, Memory }

/** Reasoning effort tiers, mapped to a token budget (wired in a later round). */
enum class ReasonTier { Low, Middle, High }

/** Permission tiers shown on the model-selector slider, mirroring BrowserBridge L1/L2/L3. */
enum class PermTier { P1, P2, P3 }

/**
 * A pending secondary-confirmation request, modeled on Codex's approval object. This round
 * it is produced by a mock trigger; later it will be filled before a real tool call. The
 * three decisions map to Codex ReviewDecision: approve / approve-for-session / deny.
 */
data class ApprovalReq(val kind: String, val title: String, val detail: String)

/**
 * In-memory UI state for the Agent panel. This round is UI-only: messages are mock,
 * "generating" just toggles the blinking dot / stop button, and settings are held in
 * memory (EncryptedSharedPreferences persistence + live model fetch come later).
 */
class PanelState {
    val messages = mutableStateListOf<ChatMsg>()
    var input by mutableStateOf("")
    var generating by mutableStateOf(false)
    var tempChat by mutableStateOf(false)
    var nav by mutableStateOf(PanelNav.Chat)
    var tier by mutableStateOf(ReasonTier.Middle)

    // Model list is driven by what the configured API key returns; empty → "暂无模型".
    val models = mutableStateListOf<String>()
    var selectedModel by mutableStateOf<String?>(null)

    // Recent conversations shown in the drawer; empty this round → only the "最近" label.
    val recentChats = mutableStateListOf<String>()

    // "我的 Agent": personalization style + memory toggle (memory only this round).
    var persona by mutableStateOf("平衡")
    var memoryEnabled by mutableStateOf(true)

    // Stored memories shown on the memory screen (in-memory this round).
    val memories = mutableStateListOf<String>()

    // Permission tier picked on the model-selector slider (UI-only this round).
    var permTier by mutableStateOf(PermTier.P1)

    // Secondary-confirmation (二次确认) state. pendingApproval drives the bottom sheet;
    // approvalSkip holds the kinds the user chose "不再询问" for this session.
    var pendingApproval by mutableStateOf<ApprovalReq?>(null)
    val approvalSkip = mutableStateListOf<String>()

    // Plan mode (mirrors the assistant's own enter/write/exit-plan flow). UI表征 only.
    var planMode by mutableStateOf(false)
    var planText by mutableStateOf("")

    // Settings (memory only this round).
    var apiKey by mutableStateOf("")
    var apiUrl by mutableStateOf("")
    var searchKey by mutableStateOf("")
    var searchUrl by mutableStateOf("")
    var anthropicFormat by mutableStateOf(false) // false = OpenAI, true = Anthropic

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        messages.add(ChatMsg(fromUser = true, text = text))
        input = ""
        generating = true
    }

    fun stop() {
        generating = false
    }

    fun newChat() {
        messages.clear()
        generating = false
        input = ""
    }
}

@Composable
fun rememberPanelState(): PanelState = remember { PanelState() }
