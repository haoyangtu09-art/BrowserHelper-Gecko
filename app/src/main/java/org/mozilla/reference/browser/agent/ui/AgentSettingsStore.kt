/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.reference.browser.agent.core.AgentMessage
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.ChatToolCall
import org.mozilla.reference.browser.agent.core.Role

private const val PREFS = "bh_agent_overlay"

class AgentSettingsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadInto(state: PanelState) {
        state.apiKey = sp.getString("api_key", "").orEmpty()
        state.apiUrl = sp.getString("api_url", "").orEmpty()
        state.searchKey = sp.getString("search_key", "").orEmpty()
        state.searchUrl = sp.getString("search_url", "").orEmpty()
        state.anthropicFormat = sp.getBoolean("anthropic_format", false)
        state.selectedModel = sp.getString("selected_model", null)
        state.persona = sp.getString("persona", "平衡").orEmpty().ifBlank { "平衡" }
        state.memoryEnabled = sp.getBoolean("memory_enabled", true)
        state.memorySummary = sp.getString("memory_summary", "").orEmpty()
        state.tier = enumValue(sp.getString("reason_tier", null), ReasonTier.Middle)
        state.permTier = enumValue(sp.getString("perm_tier", null), AgentPermissionTier.S1)
        state.autoApproveAll = sp.getBoolean("auto_approve_all", false)
        state.memories.clear()
        val arr = try {
            JSONArray(sp.getString("memories", "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { state.memories.add(it) }
        }
        state.chats.clear()
        val chats = try {
            JSONArray(sp.getString("recent_chats", "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }
        for (i in 0 until chats.length()) {
            val o = chats.optJSONObject(i) ?: continue
            state.chats.add(chatFromJson(o))
        }
    }

    fun save(state: PanelState) {
        sp.edit()
            .putString("api_key", state.apiKey)
            .putString("api_url", state.apiUrl)
            .putString("search_key", state.searchKey)
            .putString("search_url", state.searchUrl)
            .putBoolean("anthropic_format", state.anthropicFormat)
            .putString("selected_model", state.selectedModel)
            .putString("persona", state.persona)
            .putBoolean("memory_enabled", state.memoryEnabled)
            .putString("memory_summary", state.memorySummary)
            .putString("reason_tier", state.tier.name)
            .putString("perm_tier", state.permTier.name)
            .putBoolean("auto_approve_all", state.autoApproveAll)
            .putString("memories", JSONArray().also { arr -> state.memories.forEach { arr.put(it) } }.toString())
            .putString("recent_chats", JSONArray().also { arr -> state.chats.forEach { arr.put(chatToJson(it)) } }.toString())
            .apply()
    }

    private fun chatToJson(chat: SavedChat): JSONObject =
        JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("titled", chat.titled)
            .put("messages", JSONArray().also { arr -> chat.messages.forEach { arr.put(msgToJson(it)) } })
            .put("convo", JSONArray().also { arr -> chat.convo.forEach { arr.put(convoToJson(it)) } })

    private fun chatFromJson(o: JSONObject): SavedChat {
        val chat = SavedChat(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            title = o.optString("title", "新对话"),
            titled = o.optBoolean("titled", false),
        )
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgs.length()) {
            msgs.optJSONObject(i)?.let { chat.messages.add(msgFromJson(it)) }
        }
        val convo = o.optJSONArray("convo") ?: JSONArray()
        for (i in 0 until convo.length()) {
            convo.optJSONObject(i)?.let { chat.convo.add(convoFromJson(it)) }
        }
        return chat
    }

    private fun msgToJson(m: ChatMsg): JSONObject {
        val o = JSONObject().put("u", m.fromUser).put("t", m.text)
        m.tool?.let { card ->
            o.put(
                "tool",
                JSONObject()
                    .put("name", card.name)
                    .put("status", card.status)
                    .put("summary", card.summary)
                    .put("diff", card.diff),
            )
        }
        return o
    }

    private fun msgFromJson(o: JSONObject): ChatMsg {
        val tool = o.optJSONObject("tool")?.let {
            ToolCard(
                name = it.optString("name"),
                status = it.optString("status"),
                summary = it.optString("summary"),
                diff = it.optString("diff"),
            )
        }
        return ChatMsg(fromUser = o.optBoolean("u", false), text = o.optString("t"), tool = tool)
    }

    private fun convoToJson(m: AgentMessage): JSONObject {
        val o = JSONObject().put("role", m.role.wire).put("content", m.content)
        m.toolCallId?.let { o.put("toolCallId", it) }
        if (m.toolCalls.isNotEmpty()) {
            o.put(
                "toolCalls",
                JSONArray().also { arr ->
                    m.toolCalls.forEach {
                        arr.put(
                            JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments),
                        )
                    }
                },
            )
        }
        return o
    }

    private fun convoFromJson(o: JSONObject): AgentMessage {
        val role = Role.values().firstOrNull { it.wire == o.optString("role") } ?: Role.User
        val calls = ArrayList<ChatToolCall>()
        o.optJSONArray("toolCalls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                calls.add(ChatToolCall(c.optString("id"), c.optString("name"), c.optString("arguments")))
            }
        }
        return AgentMessage(
            role = role,
            content = o.optString("content"),
            toolCallId = o.optString("toolCallId").ifBlank { null },
            toolCalls = calls,
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        try {
            if (raw.isNullOrBlank()) fallback else enumValueOf<T>(raw)
        } catch (_: Throwable) {
            fallback
        }
}
