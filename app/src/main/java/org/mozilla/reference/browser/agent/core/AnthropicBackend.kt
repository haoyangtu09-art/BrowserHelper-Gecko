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
        onTextDelta: ((String) -> Unit)?,
    ): ChatReply {
        val system = messages.filter { it.role == Role.System }
            .joinToString("\n") { it.content }
        val turns = messages.filter { it.role != Role.System }
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            if (system.isNotEmpty()) put("system", system)
            put("messages", buildMessages(turns))
            if (onTextDelta != null) put("stream", true)
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
        if (onTextDelta != null) {
            return completeStreaming(endpoint, headers(config), body, onTextDelta)
        }
        val obj = postJson(endpoint, headers(config), body)
        val content = obj.optJSONArray("content") ?: return ChatReply("")
        val text = StringBuilder()
        val calls = ArrayList<ChatToolCall>()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optStringValue("type")) {
                "text" -> text.append(part.optStringValue("text"))
                "tool_use" -> {
                    val name = part.optStringValue("name")
                    if (name.isEmpty()) continue
                    calls.add(
                        ChatToolCall(
                            id = part.optStringValue("id", "call_$i"),
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
     * Accumulates a streamed Messages reply. Content blocks arrive as `content_block_start`
     * (carrying the block type + tool_use id/name), then `content_block_delta` with either a
     * `text_delta` (streamed to [onTextDelta]) or an `input_json_delta` (tool argument JSON
     * fragments). Blocks are tracked by their `index` and assembled into the final reply.
     */
    private suspend fun completeStreaming(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
        onTextDelta: (String) -> Unit,
    ): ChatReply {
        val text = StringBuilder()
        // index -> [isToolUse, id, name, accumulator(text or partial_json)]
        val blocks = LinkedHashMap<Int, ToolBlock>()
        postSse(endpoint, headers, body) { payload ->
            val ev = try {
                JSONObject(payload)
            } catch (_: Exception) {
                return@postSse
            }
            when (ev.optStringValue("type")) {
                "content_block_start" -> {
                    val idx = ev.optInt("index", 0)
                    val block = ev.optJSONObject("content_block")
                    if (block?.optStringValue("type") == "tool_use") {
                        blocks[idx] = ToolBlock(
                            id = block.optStringValue("id", "call_$idx"),
                            name = block.optStringValue("name"),
                        )
                    }
                }
                "content_block_delta" -> {
                    val d = ev.optJSONObject("delta") ?: return@postSse
                    when (d.optStringValue("type")) {
                        "text_delta" -> {
                            val frag = d.optStringValue("text")
                            if (frag.isNotEmpty()) {
                                text.append(frag)
                                onTextDelta(frag)
                            }
                        }
                        "input_json_delta" -> {
                            val idx = ev.optInt("index", 0)
                            blocks[idx]?.args?.append(d.optStringValue("partial_json"))
                        }
                    }
                }
            }
        }
        val calls = blocks.values.mapNotNull { b ->
            if (b.name.isEmpty()) return@mapNotNull null
            ChatToolCall(id = b.id, name = b.name, arguments = b.args.toString().ifEmpty { "{}" })
        }
        return ChatReply(text.toString(), calls)
    }

    private class ToolBlock(val id: String, val name: String) {
        val args = StringBuilder()
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
            .mapNotNull { arr.optJSONObject(it)?.optStringValue("id")?.ifEmpty { null } }
            .sorted()
    }

    private fun headers(config: AgentConfig) = mapOf(
        "x-api-key" to config.apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
    )
}

private fun JSONObject.optStringValue(name: String, fallback: String = ""): String {
    if (!has(name) || isNull(name)) return fallback
    val value = opt(name) ?: return fallback
    return if (value == JSONObject.NULL) fallback else value.toString()
}
