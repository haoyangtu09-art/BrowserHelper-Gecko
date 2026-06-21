/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.Context
import org.json.JSONArray
import org.mozilla.reference.browser.agent.core.AgentPermissionTier

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
        state.tier = enumValue(sp.getString("reason_tier", null), ReasonTier.Middle)
        state.permTier = enumValue(sp.getString("perm_tier", null), AgentPermissionTier.S1)
        state.memories.clear()
        val arr = try {
            JSONArray(sp.getString("memories", "[]") ?: "[]")
        } catch (_: Throwable) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { state.memories.add(it) }
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
            .putString("reason_tier", state.tier.name)
            .putString("perm_tier", state.permTier.name)
            .putString("memories", JSONArray().also { arr -> state.memories.forEach { arr.put(it) } }.toString())
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        try {
            if (raw.isNullOrBlank()) fallback else enumValueOf<T>(raw)
        } catch (_: Throwable) {
            fallback
        }
}
