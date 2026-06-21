/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.core.AgentApprovalDecision
import org.mozilla.reference.browser.agent.core.AgentPermissionTier
import org.mozilla.reference.browser.agent.core.AgentToolInfo
import org.mozilla.reference.browser.agent.core.agentToolInfos
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Hosts the panel's internal navigation: the chat surface plus the slide-in drawer,
 * settings page, and model selector drawn as animated overlays above it. Drawer is a
 * left-side layer; settings and its subpages share one right-side full-screen layer so
 * page switches cannot briefly expose the wrong page underneath.
 */
@Composable
fun AgentPanelHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = rememberPanelState()
    val scope = rememberCoroutineScope()
    val engine = remember { AgentEngine(context.applicationContext) }
    val settings = remember { AgentSettingsStore(context.applicationContext) }
    state.onTurn = { engine.start(scope, state) }
    state.onStop = { engine.cancel() }
    state.onLoadModels = { engine.loadModels(scope, state) }
    state.onPersist = { settings.save(state) }
    state.onToolSelfTest = { name -> engine.testTool(scope, state, name) }
    state.onToolSelfTestAll = { engine.testAllTools(scope, state) }
    LaunchedEffect(Unit) { settings.loadInto(state) }
    val fullScreenNav = when (state.nav) {
        PanelNav.Settings, PanelNav.Personalization, PanelNav.Memory, PanelNav.Advanced -> state.nav
        else -> null
    }
    var lastFullScreenNav by remember { mutableStateOf<PanelNav?>(PanelNav.Settings) }
    LaunchedEffect(fullScreenNav) {
        if (fullScreenNav != null) lastFullScreenNav = fullScreenNav
    }
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
            visible = fullScreenNav != null,
            enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            AnimatedContent(
                targetState = fullScreenNav ?: lastFullScreenNav,
                transitionSpec = {
                    (slideInHorizontally(tween(220)) { it } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(140)))
                },
                label = "agentFullScreenNav",
            ) { nav ->
                when (nav) {
                    PanelNav.Settings -> SettingsScreen(state, onBack = { state.nav = PanelNav.Drawer })
                    PanelNav.Personalization -> PersonalizationScreen(state, onBack = { state.nav = PanelNav.Settings })
                    PanelNav.Memory -> MemoryScreen(state, onBack = { state.nav = PanelNav.Settings })
                    PanelNav.Advanced -> AdvancedScreen(state, onBack = { state.nav = PanelNav.Settings })
                    else -> Box(Modifier.fillMaxSize().background(AgentColors.Panel))
                }
            }
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
        // Plan mode card pinned to the top of the conversation area.
        AnimatedVisibility(
            visible = state.planMode,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlanCard(state)
        }
        // Secondary-confirmation sheet rises from the bottom, above everything else.
        AnimatedVisibility(
            visible = state.pendingApproval != null,
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            state.pendingApproval?.let { req ->
                ApprovalSheet(
                    req = req,
                    onDecision = { decision ->
                        state.resolveApproval(decision)
                    },
                )
            }
        }
    }
}

/**
 * Bottom secondary-confirmation sheet (照搬 Codex 审批语义). Title + summary, then three
 * options divided by hairlines: approve once / approve this scoped resource for the
 * conversation / deny. Tool calls suspend until this sheet resolves.
 */
@Composable
private fun ApprovalSheet(req: ApprovalReq, onDecision: (AgentApprovalDecision) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(AgentShapes.Sheet)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            BasicText(req.title, style = AgentText.Title)
            Spacer(Modifier.height(6.dp))
            BasicText(req.detail, style = AgentText.Secondary)
        }
        ApprovalOption(req.approveLabel) { onDecision(AgentApprovalDecision.Approve) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AgentColors.HairlineFaint))
        ApprovalOption(req.approveSessionLabel) { onDecision(AgentApprovalDecision.ApproveSession) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AgentColors.HairlineFaint))
        ApprovalOption(req.denyLabel) { onDecision(AgentApprovalDecision.Deny) }
    }
}

@Composable
private fun ApprovalOption(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = AgentText.Body,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * Plan-mode card (mirrors the assistant's enter/write/exit-plan flow). Shows the plan text
 * with two actions: approve to start, or keep editing. create_plan/update_plan tools can
 * write this card through AgentEngine.
 */
@Composable
private fun PlanCard(state: PanelState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(AgentShapes.Sheet)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(AgentColors.Accent))
            Spacer(Modifier.width(8.dp))
            BasicText("计划模式", style = AgentText.Title)
        }
        Spacer(Modifier.height(8.dp))
        BasicText(
            state.planText.ifBlank { "（暂无计划内容）" },
            style = AgentText.Body,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.clip(AgentShapes.Pill).background(AgentColors.Accent)
                    .clickable { state.planMode = false }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { BasicText("批准并开始", style = AgentText.Body.copy(color = Color.White)) }
            Box(
                Modifier.clip(AgentShapes.Pill).background(AgentColors.Bg)
                    .clickable { state.planMode = false }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { BasicText("继续编辑", style = AgentText.Body) }
        }
    }
}

// Transparent dismiss layer: catches outside taps to close, but no gray tint and no ripple
// — the floating feel comes from color contrast, and indication=null kills the gray flash
// that the default clickable shows on touch-down.
@Composable
private fun Scrim(onClose: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().clickable(interactionSource = src, indication = null) { onClose() })
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
            Spacer(Modifier.weight(1f))
            // Manual demo triggers for the secondary-confirmation sheet and plan-mode card.
            BasicText("演示", style = AgentText.Label)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clickable {
                    state.pendingApproval = ApprovalReq(
                        kind = "page_exec",
                        title = "允许 Agent 在当前网页执行这段 JavaScript 吗？",
                        detail = "权限层：S3\n范围：演示 JS 片段\n\nAgent 想在当前页面执行一段脚本。",
                        scopeKey = "demo:page-js",
                        scopeLabel = "演示 JS 片段",
                        approveSessionLabel = "允许，并且本次会话不再询问这段 JavaScript",
                    )
                    onClose()
                }.padding(horizontal = 4.dp, vertical = 12.dp),
            ) { BasicText("二次确认演示", style = AgentText.Body) }
            Box(
                Modifier.fillMaxWidth().clickable {
                    state.planText = "1. 读取页面结构\n2. 定位目标元素\n3. 生成并执行改写脚本"
                    state.planMode = true
                    onClose()
                }.padding(horizontal = 4.dp, vertical = 12.dp),
            ) { BasicText("进入计划模式", style = AgentText.Body) }
        }
    }
}

@Composable
private fun SettingsScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Surface).padding(16.dp).verticalScroll(rememberScrollState())) {
        ScreenHeader("设置", onBack)
        Spacer(Modifier.height(16.dp))
        // "我的 Agent" sits at the top now (was below the API fields).
        BasicText("我的 Agent", style = AgentText.Label)
        Spacer(Modifier.height(8.dp))
        GroupCard {
            EntryRow("个性化", onClick = { state.nav = PanelNav.Personalization }) {
                FaceMouthIcon(color = AgentColors.TextPrimary, size = 20.dp)
            }
            GroupDivider()
            EntryRow("记忆", onClick = { state.nav = PanelNav.Memory }) {
                OpenBookIcon(color = AgentColors.TextPrimary, size = 20.dp)
            }
            GroupDivider()
            EntryRow("高级", onClick = { state.nav = PanelNav.Advanced }) {
                GearIcon(color = AgentColors.TextPrimary, size = 20.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        // API / request-format settings moved to the bottom.
        BasicText("接口", style = AgentText.Label)
        Spacer(Modifier.height(8.dp))
        GroupCard {
            LabeledField("api key", state.apiKey, secret = true) { state.apiKey = it; state.persist() }
            GroupDivider()
            LabeledField("api url", state.apiUrl) { state.apiUrl = it; state.persist() }
            GroupDivider()
            LabeledField("搜索引擎 api key", state.searchKey, secret = true) { state.searchKey = it; state.persist() }
            GroupDivider()
            LabeledField("搜索引擎 api url", state.searchUrl) { state.searchUrl = it; state.persist() }
        }
        Spacer(Modifier.height(14.dp))
        BasicText("请求格式", style = AgentText.Label)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormatOption("OpenAI", !state.anthropicFormat) { state.anthropicFormat = false; state.persist() }
            FormatOption("Anthropic", state.anthropicFormat) { state.anthropicFormat = true; state.persist() }
        }
    }
}

/**
 * Shared sub-screen header: a floating pure-white circular back button (pops against the
 * grayer page background) + title, with an optional trailing slot pinned to the right.
 */
@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color.White).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { BackIcon(color = AgentColors.TextPrimary) }
        Spacer(Modifier.width(10.dp))
        BasicText(title, style = AgentText.Title)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** A grouped settings card: a gray large-rounded container; rows separated by GroupDivider. */
@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AgentShapes.Panel).background(AgentColors.Control),
        content = content,
    )
}

/** Pure-white 1dp divider line between grouped rows. */
@Composable
private fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White))
}

/** A settings list row: a left leading icon + label, tappable to open a sub-screen. */
@Composable
private fun EntryRow(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = AgentText.Body)
    }
}

@Composable
private fun PersonalizationScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Surface).padding(16.dp)) {
        ScreenHeader("个性化", onBack)
        Spacer(Modifier.height(16.dp))
        GroupCard {
            val options = listOf("理性", "平衡", "感性")
            options.forEachIndexed { i, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.persona = option; state.persist() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (state.persona == option) CheckIcon(size = 14.dp, color = AgentColors.Accent)
                    }
                    Spacer(Modifier.width(10.dp))
                    BasicText(option, style = AgentText.Body)
                }
                if (i < options.lastIndex) GroupDivider()
            }
        }
    }
}

@Composable
private fun MemoryScreen(state: PanelState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AgentColors.Surface).padding(16.dp).verticalScroll(rememberScrollState())) {
        ScreenHeader("记忆", onBack)
        Spacer(Modifier.height(16.dp))
        GroupCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("启用记忆", style = AgentText.Body)
                Spacer(Modifier.weight(1f))
                ToggleSwitch(state.memoryEnabled) { state.memoryEnabled = !state.memoryEnabled; state.persist() }
            }
            GroupDivider()
            Box(
                Modifier.fillMaxWidth().clickable { }.padding(horizontal = 14.dp, vertical = 13.dp),
            ) { BasicText("记忆摘要", style = AgentText.Body) }
        }
        Spacer(Modifier.height(14.dp))
        // Stored memories: header + add affordance, then a deletable list (empty → hint).
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("已保存的记忆", style = AgentText.Label)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color.White)
                    .clickable { state.memories.add("记忆 ${state.memories.size + 1}"); state.persist() },
                contentAlignment = Alignment.Center,
            ) { PlusIcon(color = AgentColors.TextPrimary, size = 16.dp) }
        }
        Spacer(Modifier.height(8.dp))
        if (state.memories.isEmpty()) {
            BasicText("还没有记忆", style = AgentText.Secondary, modifier = Modifier.padding(vertical = 10.dp))
        } else {
            state.memories.forEachIndexed { i, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(AgentShapes.Field).background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(item, style = AgentText.Body, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).clickable { state.memories.removeAt(i); state.persist() },
                        contentAlignment = Alignment.Center,
                    ) { BasicText("×", style = AgentText.Body.copy(color = AgentColors.TextSecondary)) }
                }
            }
        }
    }
}

@Composable
private fun AdvancedScreen(state: PanelState, onBack: () -> Unit) {
    val tools = remember { agentToolInfos() }
    Column(Modifier.fillMaxSize().background(AgentColors.Surface).padding(16.dp).verticalScroll(rememberScrollState())) {
        ScreenHeader("高级", onBack) {
            Box(
                Modifier.clip(AgentShapes.Pill)
                    .background(if (state.toolsChecking) AgentColors.Control else AgentColors.Accent)
                    .clickable { if (!state.toolsChecking) state.onToolSelfTestAll?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                BasicText(
                    if (state.toolsChecking) "自检中" else "全部自检",
                    style = AgentText.Label.copy(color = if (state.toolsChecking) AgentColors.TextSecondary else Color.White),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        BasicText("工具自检", style = AgentText.Label)
        Spacer(Modifier.height(8.dp))
        tools.forEach { tool ->
            ToolCheckRow(
                tool = tool,
                check = state.toolChecks[tool.name],
                onClick = { state.onToolSelfTest?.invoke(tool.name) },
            )
        }
    }
}

@Composable
private fun ToolCheckRow(tool: AgentToolInfo, check: ToolCheckState?, onClick: () -> Unit) {
    val status = check?.status ?: "未测"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(AgentShapes.Field)
            .background(Color.White)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(tool.name, style = AgentText.Body, modifier = Modifier.weight(1f))
            TierBadge(tool.tier)
            Spacer(Modifier.width(8.dp))
            BasicText(status, style = AgentText.Label.copy(color = statusColor(status)))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(tool.description, style = AgentText.Secondary)
        if (status == "失败") {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(AgentShapes.Sheet).background(AgentColors.Surface).padding(10.dp)) {
                BasicText(check?.detail.orEmpty(), style = AgentText.Secondary.copy(color = AgentColors.TextPrimary))
            }
        }
    }
}

@Composable
private fun TierBadge(tier: AgentPermissionTier) {
    val bg = when (tier) {
        AgentPermissionTier.S1 -> AgentColors.Control
        AgentPermissionTier.S2 -> Color(0xFFE8F0FE)
        AgentPermissionTier.S3 -> Color(0xFFFCE8E6)
    }
    val fg = when (tier) {
        AgentPermissionTier.S1 -> AgentColors.TextSecondary
        AgentPermissionTier.S2 -> AgentColors.Accent
        AgentPermissionTier.S3 -> Color(0xFFB3261E)
    }
    Box(Modifier.clip(AgentShapes.Pill).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        BasicText(tier.name, style = AgentText.Label.copy(color = fg))
    }
}

private fun statusColor(status: String): Color = when (status) {
    "OK" -> Color(0xFF188038)
    "失败" -> Color(0xFFB3261E)
    "测试中" -> AgentColors.Accent
    else -> AgentColors.TextSecondary
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        BasicText(label, style = AgentText.Label)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().clip(AgentShapes.Field).background(Color.White)
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                .width(220.dp)
                .clip(AgentShapes.Sheet)
                .background(Color.White)
                .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet)
                .padding(14.dp),
        ) {
            // Title row: "智能" on the left, the S1/S2/S3 permission slider on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("智能", style = AgentText.Title)
                Spacer(Modifier.weight(1f))
                PermissionSlider(state.permTier) { state.permTier = it; state.persist() }
            }
            Spacer(Modifier.height(10.dp))
            ReasonTier.values().forEach { tier ->
                TierRow(tier.name, state.tier == tier) { state.tier = tier; state.persist() }
            }
            Spacer(Modifier.height(10.dp))
            // Faint divider between the tier picker and the model row.
            Box(Modifier.fillMaxWidth().height(1.dp).background(AgentColors.HairlineFaint))
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(AgentShapes.Sheet)
                    .clickable {
                        val opening = !showModels
                        showModels = opening
                        if (opening) state.onLoadModels?.invoke()
                    }
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
                        ) { BasicText(if (state.modelsLoading) "加载中…" else "暂无模型", style = AgentText.Secondary) }
                    } else {
                        state.models.forEach { m ->
                            val sel = state.selectedModel == m
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(AgentShapes.Sheet)
                                    .background(if (sel) AgentColors.Control else AgentColors.Bg)
                                    .clickable { state.selectedModel = m; state.persist(); showModels = false }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                            ) { BasicText(m, style = AgentText.Body) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A compact pill bar with three permission segments (S1/S2/S3) and a white sliding knob that
 * animates to the selected segment.
 */
@Composable
private fun PermissionSlider(tier: PermTier, onPick: (PermTier) -> Unit) {
    val tiers = PermTier.values()
    val index = tiers.indexOf(tier)
    val knob by animateFloatAsState(index.toFloat(), label = "permKnob")
    val segW = 30.dp
    Box(
        modifier = Modifier
            .width(segW * tiers.size)
            .height(24.dp)
            .clip(AgentShapes.Pill)
            .background(AgentColors.Control),
    ) {
        // Sliding knob behind the labels.
        Box(
            modifier = Modifier
                .padding(2.dp)
                .width(segW - 4.dp)
                .fillMaxHeight()
                .offset(x = segW * knob)
                .clip(AgentShapes.Pill)
                .background(AgentColors.Accent),
        )
        Row(Modifier.fillMaxHeight()) {
            tiers.forEach { t ->
                val sel = t == tier
                Box(
                    modifier = Modifier
                        .width(segW)
                        .fillMaxHeight()
                        .clip(AgentShapes.Pill)
                        .clickable { onPick(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        t.name,
                        style = AgentText.Label.copy(color = if (sel) Color.White else AgentColors.TextSecondary),
                    )
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
