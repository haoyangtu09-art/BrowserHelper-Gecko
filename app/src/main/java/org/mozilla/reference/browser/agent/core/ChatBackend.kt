/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

/**
 * A chat provider. [complete] runs one model turn (optionally with tools) and returns the
 * assistant reply; [listModels] populates the model selector. Implementations own only their
 * wire format — the turn loop (history building, UI updates) lives in the engine above them.
 */
interface ChatBackend {
    suspend fun complete(
        config: AgentConfig,
        messages: List<AgentMessage>,
        tools: List<ChatToolSpec> = emptyList(),
    ): ChatReply

    suspend fun listModels(config: AgentConfig): List<String>

    companion object {
        fun of(format: ApiFormat): ChatBackend = when (format) {
            ApiFormat.OpenAI -> OpenAiBackend()
            ApiFormat.Anthropic -> AnthropicBackend()
        }
    }
}
