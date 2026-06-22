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
import java.io.File

private const val PREFS = "bh_agent_overlay"

class AgentSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val memoryRoot = File(appContext.filesDir, "agent_memory")
    private val chatRoot = File(memoryRoot, "chats")
    private val indexFile = File(memoryRoot, "chat_index.json")
    private val memoryFile = File(memoryRoot, "memories.json")

    fun loadInto(state: PanelState) {
        state.apiKey = sp.getString("api_key", "").orEmpty()
        state.apiUrl = sp.getString("api_url", "").orEmpty()
        state.searchKey = sp.getString("search_key", "").orEmpty()
        state.searchUrl = sp.getString("search_url", "").orEmpty()
        state.anthropicFormat = sp.getBoolean("anthropic_format", false)
        state.selectedModel = sp.getString("selected_model", null)
        state.persona = sp.getString("persona", "平衡").orEmpty().ifBlank { "平衡" }
        state.tier = enumValue(sp.getString("reason_tier", null), ReasonTier.Middle)
        state.permTier = enumValue(sp.getString("perm_tier", null), AgentPermissionTier.S1)
        state.autoApproveAll = sp.getBoolean("auto_approve_all", false)
        loadMemoryInto(state)
        loadChatsInto(state)
    }

    private fun loadMemoryInto(state: PanelState) {
        val obj = readJson(memoryFile)
        state.memoryEnabled = obj?.optBoolean("memoryEnabled", true) ?: sp.getBoolean("memory_enabled", true)
        state.memorySummary = obj?.optString("summary", "") ?: sp.getString("memory_summary", "").orEmpty()
        state.memories.clear()
        val arr = obj?.optJSONArray("memories") ?: try {
            JSONArray(sp.getString("memories", "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { state.memories.add(it) }
        }
    }

    private fun loadChatsInto(state: PanelState) {
        state.chats.clear()
        var currentId: String? = null
        val index = readJson(indexFile)
        val indexChats = index?.optJSONArray("chats")
        if (indexChats != null) {
            currentId = index.optString("currentChatId").ifBlank { null }
            for (i in 0 until indexChats.length()) {
                val meta = indexChats.optJSONObject(i) ?: continue
                val id = meta.optString("id").trim()
                if (id.isBlank()) continue
                val chat = readJson(chatFile(id))?.let { chatFromJson(it) }
                    ?: SavedChat(id = id, title = meta.optString("title", "新对话"), titled = meta.optBoolean("titled", false))
                state.chats.add(chat)
            }
        } else if (chatRoot.isDirectory) {
            chatRoot.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(20)
                ?.forEach { file -> readJson(file)?.let { state.chats.add(chatFromJson(it)) } }
        }
        if (state.chats.isEmpty()) {
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
        val current = currentId?.let { id -> state.chats.firstOrNull { it.id == id } }
        if (current != null) {
            state.currentChatId = current.id
            state.currentChatTitle = current.title
            state.titleGenerated = current.titled
            state.messages.clear()
            state.messages.addAll(current.messages)
            state.convo.clear()
            state.convo.addAll(current.convo)
        }
    }

    fun save(state: PanelState) {
        save(state, blocking = false)
    }

    fun saveBlocking(state: PanelState) {
        save(state, blocking = true)
    }

    private fun save(state: PanelState, blocking: Boolean) {
        writeMemoryFiles(state)
        writeChatFiles(state)
        val editor = sp.edit()
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
            .putString("recent_chats", (chatIndexJson(state).optJSONArray("chats") ?: JSONArray()).toString())
        if (blocking) editor.commit() else editor.apply()
    }

    private fun writeMemoryFiles(state: PanelState) {
        val obj = JSONObject()
            .put("version", 1)
            .put("memoryEnabled", state.memoryEnabled)
            .put("summary", state.memorySummary)
            .put("memories", JSONArray().also { arr -> state.memories.forEach { arr.put(it) } })
            .put("updatedAt", System.currentTimeMillis())
        writeJson(memoryFile, obj)
    }

    private fun writeChatFiles(state: PanelState) {
        state.chats.forEach { chat ->
            writeJson(chatFile(chat.id), chatToJson(chat).put("updatedAt", System.currentTimeMillis()))
        }
        writeJson(indexFile, chatIndexJson(state))
    }

    private fun chatIndexJson(state: PanelState): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("currentChatId", state.currentChatId ?: "")
            .put("updatedAt", System.currentTimeMillis())
            .put(
                "chats",
                JSONArray().also { arr ->
                    state.chats.forEach { chat ->
                        arr.put(
                            JSONObject()
                                .put("id", chat.id)
                                .put("title", chat.title)
                                .put("titled", chat.titled)
                                .put("file", chatFile(chat.id).name),
                        )
                    }
                },
            )

    private fun chatToJson(chat: SavedChat): JSONObject =
        JSONObject()
            .put("version", 1)
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

    private fun chatFile(id: String): File = File(chatRoot, safeFileName(id) + ".json")

    private fun safeFileName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { java.util.UUID.randomUUID().toString() }

    private fun readJson(file: File): JSONObject? =
        try {
            if (!file.isFile) null else JSONObject(file.readText())
        } catch (_: Throwable) {
            null
        }

    private fun writeJson(file: File, obj: JSONObject) {
        try {
            val parent = file.parentFile ?: return
            parent.mkdirs()
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(obj.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
