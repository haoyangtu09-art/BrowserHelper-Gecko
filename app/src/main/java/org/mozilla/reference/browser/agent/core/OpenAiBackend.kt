/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Chat Completions backend (`POST {base}/chat/completions`). Tools are sent as
 * `function` specs with `tool_choice:auto`; the reply's `tool_calls` drive the engine's tool
 * loop. System turns go inline in the messages array as role "system".
 */
class OpenAiBackend : ChatBackend {

    override suspend fun complete(
        config: AgentConfig,
        messages: List<AgentMessage>,
        tools: List<ChatToolSpec>,
    ): ChatReply {
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("messages", messages.toJson())
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().also { arr ->
                    tools.forEach { spec ->
                        arr.put(
                            JSONObject()
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", spec.name)
                                        .put("description", spec.description)
                                        .put("parameters", spec.parameters),
                                ),
                        )
                    }
                })
                put("tool_choice", "auto")
            }
        }.toString()
        val endpoint = Endpoints.openAiBase(config.baseUrl) + "/chat/completions"
        val obj = postJson(endpoint, mapOf("Authorization" to "Bearer ${config.apiKey}"), body)
        val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return ChatReply("")
        val calls = ArrayList<ChatToolCall>()
        val arr = msg.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                if (name.isEmpty()) continue
                calls.add(
                    ChatToolCall(
                        id = c.optString("id", "call_$i"),
                        name = name,
                        arguments = fn.optString("arguments", "{}"),
                    ),
                )
            }
        }
        return ChatReply(msg.optString("content", ""), calls)
    }

    override suspend fun listModels(config: AgentConfig): List<String> {
        val endpoint = Endpoints.openAiBase(config.baseUrl) + "/models"
        val text = getText(endpoint, mapOf("Authorization" to "Bearer ${config.apiKey}"))
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifEmpty { null } }
            .sorted()
    }

    private fun List<AgentMessage>.toJson(): JSONArray {
        val arr = JSONArray()
        forEach { msg ->
            val obj = JSONObject().put("role", msg.role.wire).put("content", msg.content)
            if (msg.role == Role.Tool && !msg.toolCallId.isNullOrEmpty()) {
                obj.put("tool_call_id", msg.toolCallId)
            }
            if (msg.toolCalls.isNotEmpty()) {
                obj.put("tool_calls", JSONArray().also { calls ->
                    msg.toolCalls.forEach { call ->
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.arguments),
                                ),
                        )
                    }
                })
            }
            arr.put(obj)
        }
        return arr
    }
}
