/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Hosts the panel's internal navigation: the chat surface plus the slide-in drawer,
 * settings page, and model selector drawn as animated overlays above it. Each overlay
 * is an AnimatedVisibility keyed on the current nav target so it gets both an enter and
 * an exit transition (drawer slides from the left, settings from the right, the model
 * selector scales out of the Agent button at the top-left).
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
        AnimatedVisibility(
            visible = state.nav == PanelNav.Drawer,
            enter = slideInHorizontally(tween(240)) { -it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(200)),
        ) {
            DrawerSheet(
                state = state,
                onClose = { state.nav = PanelNav.Chat },
                onSettings = { state.nav = PanelNav.Settings },
            )
        }
        AnimatedVisibility(
            visible = state.nav == PanelNav.Settings,
            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            SettingsScreen(state, onBack = { state.nav = PanelNav.Drawer })
        }
        AnimatedVisibility(
            visible = state.nav == PanelNav.Personalization,
            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            PersonalizationScreen(state, onBack = { state.nav = PanelNav.Settings })
        }
        AnimatedVisibility(
            visible = state.nav == PanelNav.Memory,
            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            MemoryScreen(state, onBack = { state.nav = PanelNav.Settings })
        }
        AnimatedVisibility(
            visible = state.nav == PanelNav.ModelSelector,
            enter = scaleIn(
                tween(200),
                initialScale = 0.9f,
                transformOrigin = TransformOrigin(0f, 0f),
            ) + fadeIn(tween(200)),
            exit = scaleOut(
                tween(180),
                targetScale = 0.9f,
                transformOrigin = TransformOrigin(0f, 0f),
            ) + fadeOut(tween(160)),
        ) {
            ModelSelectorOverlay(state, onClose = { state.nav = PanelNav.Chat })
        }
    }
}

// Transparent dismiss layer: catches outside taps to close, but no gray tint — the
// floating feel comes from color contrast, not a scrim.
@Composable
private fun Scrim(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable { onClose() })
}

@Composable
private fun DrawerSheet(state: PanelState, onClose: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onClose)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.78f)
                .background(AgentColors.Panel)
                .padding(16.dp),
        ) {
            // Header: title on the left, settings gear pinned to the top-right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("HTTP Agent", style = AgentText.Title)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(AgentColors.Bg)
                        .clickable { onSettings() },
                    contentAlignment = Alignment.Center,
                ) { GearIcon(color = AgentColors.TextPrimary) }
            }
            Spacer(Modifier.height(16.dp))
            BasicText("最近", style = AgentText.Label)
            Spacer(Modifier.height(6.dp))
            // Created conversations live here; empty this round → only the "最近" label shows.
            state.recentChats.forEach { title ->
                Box(
                    Modifier.fillMaxWidth().clickable { }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                ) { BasicText(title, style = AgentText.Body) }
            }
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
        Spacer(Modifier.height(18.dp))
        BasicText("我的 Agent", style = AgentText.Label)
        Spacer(Modifier.height(8.dp))
        EntryRow("个性化", onClick = { state.nav = PanelNav.Personalization }) {
            FaceMouthIcon(color = AgentColors.TextPrimary, size = 22.dp)
        }
        EntryRow("记忆", onClick = { state.nav = PanelNav.Memory }) {
            OpenBookIcon(color = AgentColors.TextPrimary, size = 22.dp)
        }
    }
}

/** A settings list row: a left leading icon + label, tappable to open a sub-screen. */
@Composable
private fun EntryRow(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = AgentText.Body)
    }
}

@Composable
private fun PersonalizationScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Panel).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackIcon(color = AgentColors.TextPrimary) }
            Spacer(Modifier.width(8.dp))
            BasicText("个性化", style = AgentText.Title)
        }
        Spacer(Modifier.height(14.dp))
        listOf("理性", "平衡", "感性").forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.persona = option }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (state.persona == option) CheckIcon(size = 14.dp, color = AgentColors.Accent)
                }
                Spacer(Modifier.width(10.dp))
                BasicText(option, style = AgentText.Body)
            }
        }
    }
}

@Composable
private fun MemoryScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Panel).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackIcon(color = AgentColors.TextPrimary) }
            Spacer(Modifier.width(8.dp))
            BasicText("记忆", style = AgentText.Title)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("启用记忆", style = AgentText.Body)
            Spacer(Modifier.weight(1f))
            ToggleSwitch(state.memoryEnabled) { state.memoryEnabled = !state.memoryEnabled }
        }
        Box(
            Modifier.fillMaxWidth().clickable { }.padding(vertical = 12.dp),
        ) { BasicText("记忆摘要", style = AgentText.Body) }
    }
}

/** Minimal pill toggle (no Material): a track + a sliding knob. */
@Composable
private fun ToggleSwitch(on: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(AgentShapes.Pill)
            .background(if (on) AgentColors.Accent else AgentColors.Control)
            .clickable { onToggle() }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
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

/**
 * The model selector as a two-layer card anchored under the Agent button at the top-left
 * (rather than a bottom sheet). Layer 1 picks the reasoning tier (a checkmark marks the
 * selected one) and shows the current model; tapping the model row opens layer 2, which
 * lists models or shows "暂无模型" when none are configured.
 */
@Composable
private fun ModelSelectorOverlay(state: PanelState, onClose: () -> Unit) {
    var showModels by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Scrim { showModels = false; onClose() }
        // A single narrow white card anchored below the Agent button. The model list is an
        // inline accordion that expands BELOW the 模型 row (not a separate floating card).
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 44.dp)
                .width(180.dp)
                .clip(AgentShapes.Sheet)
                .background(Color.White)
                .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet)
                .padding(14.dp),
        ) {
            BasicText("智能", style = AgentText.Title)
            Spacer(Modifier.height(10.dp))
            ReasonTier.values().forEach { tier ->
                TierRow(tier.name, state.tier == tier) { state.tier = tier }
            }
            Spacer(Modifier.height(10.dp))
            // Faint divider between the tier picker and the model row.
            Box(Modifier.fillMaxWidth().height(1.dp).background(AgentColors.HairlineFaint))
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(AgentShapes.Sheet)
                    .clickable { showModels = !showModels }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("模型", style = AgentText.Label)
                    Spacer(Modifier.weight(1f))
                    BasicText(state.selectedModel ?: "选择模型", style = AgentText.Body)
                }
            }
            // Inline accordion: the model list expands vertically right below the 模型 row.
            AnimatedVisibility(
                visible = showModels,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140)),
            ) {
                Column {
                    if (state.models.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().clip(AgentShapes.Sheet).background(AgentColors.Bg)
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { BasicText("暂无模型", style = AgentText.Secondary) }
                    } else {
                        state.models.forEach { m ->
                            val sel = state.selectedModel == m
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(AgentShapes.Sheet)
                                    .background(if (sel) AgentColors.Control else AgentColors.Bg)
                                    .clickable { state.selectedModel = m; showModels = false }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                            ) { BasicText(m, style = AgentText.Body) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AgentShapes.Sheet)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) CheckIcon(size = 14.dp, color = AgentColors.Accent)
        }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = AgentText.Body)
    }
}
