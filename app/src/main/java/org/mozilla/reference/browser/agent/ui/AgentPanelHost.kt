/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Hosts the panel's internal navigation: the chat surface plus the slide-in drawer,
 * settings page, and model selector drawn as overlays above it.
 */
@Composable
fun AgentPanelHost(modifier: Modifier = Modifier) {
    val state = rememberPanelState()
    Box(modifier.fillMaxSize()) {
        ChatScreen(
            state = state,
            onOpenDrawer = { state.nav = PanelNav.Drawer },
            onOpenModels = { state.nav = PanelNav.ModelSelector },
        )
        when (state.nav) {
            PanelNav.Chat -> Unit
            PanelNav.Drawer -> DrawerSheet(
                onClose = { state.nav = PanelNav.Chat },
                onSettings = { state.nav = PanelNav.Settings },
            )
            PanelNav.Settings -> SettingsScreen(state, onBack = { state.nav = PanelNav.Drawer })
            PanelNav.ModelSelector -> ModelSelectorSheet(state, onClose = { state.nav = PanelNav.Chat })
        }
    }
}

@Composable
private fun Scrim(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable { onClose() })
}

@Composable
private fun DrawerSheet(onClose: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onClose)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.78f)
                .background(AgentColors.Panel)
                .padding(16.dp),
        ) {
            BasicText("记忆", style = AgentText.Title)
            Spacer(Modifier.height(12.dp))
            listOf("偏好与习惯", "项目上下文", "最近会话").forEach {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clip(AgentShapes.Sheet).background(AgentColors.Bg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) { BasicText(it, style = AgentText.Body) }
            }
            Spacer(Modifier.weight(1f))
            // Gear → settings.
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(AgentColors.Bg)
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center,
            ) { GearIcon(color = AgentColors.TextPrimary) }
        }
    }
}

@Composable
private fun SettingsScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Panel).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackIcon(color = AgentColors.TextPrimary) }
            Spacer(Modifier.width(8.dp))
            BasicText("设置", style = AgentText.Title)
        }
        Spacer(Modifier.height(14.dp))
        LabeledField("api key", state.apiKey, secret = true) { state.apiKey = it }
        LabeledField("api url", state.apiUrl) { state.apiUrl = it }
        LabeledField("搜索引擎 api key", state.searchKey, secret = true) { state.searchKey = it }
        LabeledField("搜索引擎 api url", state.searchUrl) { state.searchUrl = it }
        Spacer(Modifier.height(8.dp))
        BasicText("请求格式", style = AgentText.Label)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormatOption("OpenAI", !state.anthropicFormat) { state.anthropicFormat = false }
            FormatOption("Anthropic", state.anthropicFormat) { state.anthropicFormat = true }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        BasicText(label, style = AgentText.Label)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().clip(AgentShapes.Field).background(AgentColors.Bg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = AgentText.Body,
                cursorBrush = SolidColor(AgentColors.Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FormatOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(AgentShapes.Pill)
            .background(if (selected) AgentColors.Accent else AgentColors.Bg)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        BasicText(label, style = if (selected) AgentText.Body.copy(color = Color.White) else AgentText.Body)
    }
}

@Composable
private fun ModelSelectorSheet(state: PanelState, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onClose)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AgentColors.Panel)
                .padding(16.dp),
        ) {
            BasicText("推理模式", style = AgentText.Label)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasonTier.values().forEach { tier ->
                    TierOption(tier.name, state.tier == tier) { state.tier = tier }
                }
            }
            BasicText(
                "对应 token 限额",
                style = AgentText.Label,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            BasicText("模型", style = AgentText.Label)
            Spacer(Modifier.height(8.dp))
            if (state.models.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(AgentShapes.Sheet).background(AgentColors.Bg)
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) { BasicText("暂无模型", style = AgentText.Secondary) }
            } else {
                state.models.forEach { m ->
                    val sel = state.selectedModel == m
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(AgentShapes.Sheet)
                            .background(if (sel) AgentColors.Control else AgentColors.Bg)
                            .clickable { state.selectedModel = m }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) { BasicText(m, style = AgentText.Body) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TierOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(AgentShapes.Pill)
            .background(if (selected) AgentColors.Accent else AgentColors.Bg)
            .border(1.dp, if (selected) AgentColors.Accent else AgentColors.Control, AgentShapes.Pill)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        BasicText(label, style = if (selected) AgentText.Body.copy(color = Color.White) else AgentText.Body)
    }
}
