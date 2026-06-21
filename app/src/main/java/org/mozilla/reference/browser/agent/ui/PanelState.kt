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

enum class PanelNav { Chat, Drawer, Settings, ModelSelector }

/** Reasoning effort tiers, mapped to a token budget (wired in a later round). */
enum class ReasonTier { Low, Middle, High }

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
