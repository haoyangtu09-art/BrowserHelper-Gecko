/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentElevation
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes
import org.mozilla.reference.browser.agent.ui.theme.AgentText

data class ChatMsg(val fromUser: Boolean, val text: String)

/**
 * Chat surface: top bar (menu / Agent / state action) + message list (with a slowly
 * blinking dot while generating) + input bar (+ / field / send↔stop). Pure UI on mock
 * in-memory state; no model/tool calls this round.
 */
@Composable
fun ChatScreen(
    state: PanelState,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        // Conversation fills the area; the top bar floats above it as white buttons, so
        // messages scroll underneath and only the buttons occlude the context.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.messages.isEmpty() && !state.generating) {
                BasicText(
                    "有什么可以帮你的?",
                    style = AgentText.Secondary.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                MessageList(state)
            }
            if (state.tempChat && state.messages.isEmpty()) {
                BasicText(
                    "此聊天不会被保存",
                    style = AgentText.Label.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
                )
            }
            ChatTopBar(
                state = state,
                onOpenDrawer = onOpenDrawer,
                onOpenModels = onOpenModels,
                menuOpen = showMenu,
                onToggleMenu = { showMenu = it },
                modifier = Modifier.align(Alignment.TopStart),
            )
            // Tap anywhere over the conversation to dismiss the overflow menu, then draw
            // the menu above that dismiss layer.
            if (showMenu) {
                Box(Modifier.fillMaxSize().clickable { showMenu = false })
                OverflowMenu(
                    onShare = { showMenu = false },
                    onDelete = { showMenu = false; state.newChat() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
                )
            }
        }
        InputBar(state)
    }
}

@Composable
private fun ChatTopBar(
    state: PanelState,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    menuOpen: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: hamburger menu, then the "Agent" capsule right beside it — both white
        // floating buttons hovering over the conversation.
        FloatingCircleButton(onClick = onOpenDrawer) { MenuLinesIcon(color = AgentColors.TextPrimary) }
        Spacer(Modifier.width(8.dp))
        FloatingPill(onClick = onOpenModels) { BasicText("Agent", style = AgentText.Title) }
        Spacer(Modifier.weight(1f))
        if (state.messages.isEmpty()) {
            FloatingCircleButton(onClick = { state.tempChat = !state.tempChat }) {
                Box(contentAlignment = Alignment.Center) {
                    ChatBubbleIcon(color = AgentColors.TextPrimary)
                    if (state.tempChat) {
                        Box(
                            Modifier.size(12.dp).clip(CircleShape).background(AgentColors.Accent),
                            contentAlignment = Alignment.Center,
                        ) { CheckIcon(size = 10.dp) }
                    }
                }
            }
        } else {
            // New-chat (pen) and overflow (dots) wrapped together in one floating pill.
            FloatingDualPill(
                onNew = { state.newChat() },
                onMore = { onToggleMenu(!menuOpen) },
            )
        }
    }
}

/** White circular button with a soft drop shadow so it reads as floating over content. */
@Composable
private fun FloatingCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(AgentElevation.Floating, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** White pill button with the same floating shadow (the "Agent" capsule). */
@Composable
private fun FloatingPill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .shadow(AgentElevation.Floating, AgentShapes.Pill)
            .clip(AgentShapes.Pill)
            .background(Color.White)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Pen (new chat) + dots (overflow) packed into a single floating ellipse capsule. */
@Composable
private fun FloatingDualPill(onNew: () -> Unit, onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .shadow(AgentElevation.Floating, AgentShapes.Pill)
            .clip(AgentShapes.Pill)
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().clickable { onNew() }.padding(start = 14.dp, end = 10.dp),
            contentAlignment = Alignment.Center,
        ) { PenSquareIcon(color = AgentColors.TextPrimary, size = 20.dp) }
        Box(Modifier.width(1.dp).height(20.dp).background(AgentColors.HairlineFaint))
        Box(
            Modifier.fillMaxHeight().clickable { onMore() }.padding(start = 10.dp, end = 14.dp),
            contentAlignment = Alignment.Center,
        ) { MoreDotsIcon(color = AgentColors.TextPrimary, size = 20.dp) }
    }
}

@Composable
private fun OverflowMenu(onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(140.dp)
            .shadow(AgentElevation.Floating, AgentShapes.Sheet)
            .clip(AgentShapes.Sheet)
            .background(AgentColors.Panel),
    ) {
        MenuRow("分享") { onShare() }
        MenuRow("删除") { onDelete() }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        style = AgentText.Body,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun MessageList(state: PanelState) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Clear the floating top bar so the first message isn't hidden beneath it.
        Spacer(Modifier.height(56.dp))
        state.messages.forEach { msg ->
            if (msg.fromUser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(AgentColors.UserBubble)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { BasicText(msg.text, style = AgentText.Body) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    AssistantAvatar()
                    Spacer(Modifier.width(10.dp))
                    BasicText(msg.text, style = AgentText.Body, modifier = Modifier.weight(1f).padding(top = 4.dp))
                }
            }
        }
        if (state.generating) {
            // Single slowly-pulsing dot — no avatar — so the wait state shows one dot, not two.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BlinkingDot(Modifier.padding(start = 4.dp, top = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).background(AgentColors.Control),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(AgentColors.TextSecondary))
    }
}

/** A single black dot pulsing slowly to signal the model is generating. */
@Composable
private fun BlinkingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "gen")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "genAlpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .size(9.dp)
            .clip(CircleShape)
            .background(AgentColors.TextPrimary),
    )
}

@Composable
private fun InputBar(state: PanelState) {
    var showUpload by remember { mutableStateOf(false) }
    Column {
        if (showUpload) UploadMenu { showUpload = false }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showUpload = !showUpload }) { PlusIcon(color = AgentColors.TextPrimary) }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(AgentShapes.Field)
                    .background(AgentColors.Bg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.input.isEmpty()) BasicText("问问…", style = AgentText.Hint)
                BasicTextField(
                    value = state.input,
                    onValueChange = { state.input = it },
                    textStyle = AgentText.Body,
                    cursorBrush = SolidColor(AgentColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            SendStopButton(
                generating = state.generating,
                onSend = { state.send() },
                onStop = { state.stop() },
            )
        }
    }
}

@Composable
private fun SendStopButton(generating: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (generating) AgentColors.Stop else AgentColors.Accent)
            .clickable { if (generating) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        if (generating) {
            // Rounded square = stop.
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
        } else {
            SendArrowIcon(size = 19.dp, color = Color.White)
        }
    }
}

@Composable
private fun UploadMenu(onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf("相机", "照片", "文件", "插件").forEach { label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(AgentShapes.Upload)
                    .background(AgentColors.Bg)
                    .clickable { onPick() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText(label, style = AgentText.Secondary) }
        }
    }
}

@Composable
fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}
