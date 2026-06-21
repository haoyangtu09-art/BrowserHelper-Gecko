/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import org.json.JSONArray
import org.json.JSONObject

private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Anthropic Messages backend (`POST {base}/messages`). System turns are hoisted out of the
 * messages array into the top-level `system` field. Tools are sent with `input_schema`;
 * assistant tool calls / tool results round-trip as `tool_use` / `tool_result` content blocks.
 */
class AnthropicBackend : ChatBackend {

    override suspend fun complete(
        config: AgentConfig,
        messages: List<AgentMessage>,
        tools: List<ChatToolSpec>,
    ): ChatReply {
        val system = messages.filter { it.role == Role.System }
            .joinToString("\n") { it.content }
        val turns = messages.filter { it.role != Role.System }
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            if (system.isNotEmpty()) put("system", system)
            put("messages", buildMessages(turns))
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().also { arr ->
                    tools.forEach { spec ->
                        arr.put(
                            JSONObject()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("input_schema", spec.parameters),
                        )
                    }
                })
            }
        }.toString()
        val endpoint = Endpoints.anthropicBase(config.baseUrl) + "/messages"
        val obj = postJson(endpoint, headers(config), body)
        val content = obj.optJSONArray("content") ?: return ChatReply("")
        val text = StringBuilder()
        val calls = ArrayList<ChatToolCall>()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text", ""))
                "tool_use" -> {
                    val name = part.optString("name", "")
                    if (name.isEmpty()) continue
                    calls.add(
                        ChatToolCall(
                            id = part.optString("id", "call_$i"),
                            name = name,
                            arguments = part.optJSONObject("input")?.toString() ?: "{}",
                        ),
                    )
                }
            }
        }
        return ChatReply(text.toString(), calls)
    }

    /**
     * Converts the structured transcript into Anthropic content blocks: assistant tool calls
     * become `tool_use` blocks (input as a JSON object), and Role.Tool results become
     * `tool_result` blocks rolled into a single `user` message (consecutive results merged,
     * as Anthropic requires alternating user/assistant turns).
     */
    private fun buildMessages(turns: List<AgentMessage>): JSONArray {
        val arr = JSONArray()
        var i = 0
        while (i < turns.size) {
            val msg = turns[i]
            when (msg.role) {
                Role.User -> {
                    arr.put(JSONObject().put("role", "user").put("content", msg.content))
                    i++
                }
                Role.Assistant -> {
                    val content = JSONArray()
                    if (msg.content.isNotEmpty()) {
                        content.put(JSONObject().put("type", "text").put("text", msg.content))
                    }
                    msg.toolCalls.forEach { call ->
                        content.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", call.id)
                                .put("name", call.name)
                                .put("input", parseInput(call.arguments)),
                        )
                    }
                    if (content.length() > 0) {
                        arr.put(JSONObject().put("role", "assistant").put("content", content))
                    }
                    i++
                }
                Role.Tool -> {
                    val content = JSONArray()
                    while (i < turns.size && turns[i].role == Role.Tool) {
                        val t = turns[i]
                        content.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", t.toolCallId.orEmpty())
                                .put("content", t.content),
                        )
                        i++
                    }
                    arr.put(JSONObject().put("role", "user").put("content", content))
                }
                Role.System -> i++
            }
        }
        return arr
    }

    private fun parseInput(arguments: String): JSONObject =
        try {
            JSONObject(arguments)
        } catch (e: Exception) {
            JSONObject()
        }

    override suspend fun listModels(config: AgentConfig): List<String> {
        val endpoint = Endpoints.anthropicBase(config.baseUrl) + "/models"
        val text = getText(endpoint, headers(config))
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifEmpty { null } }
            .sorted()
    }

    private fun headers(config: AgentConfig) = mapOf(
        "x-api-key" to config.apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
    )
}
