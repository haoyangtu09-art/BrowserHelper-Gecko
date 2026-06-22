/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.AgentAttachmentKind
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes
import org.mozilla.reference.browser.agent.ui.theme.AgentText

data class ChatMsg(
    val fromUser: Boolean,
    val text: String,
    // When non-null this message renders as an agent-style tool card (status line + optional
    // diff block) instead of plain prose. The model's full tool output is NOT shown here — it
    // only goes back to the model — so the conversation stays clean.
    val tool: ToolCard? = null,
)

/**
 * Compact, agent-style summary of one tool call rendered in the transcript. [summary] is a
 * one-liner (e.g. "wrote app/x.kt  +12 -3"); [diff] is optional unified-style text whose
 * `+`/`-` leading lines are colored green/red inside a code frame.
 */
data class ToolCard(
    val name: String,
    val status: String,
    val summary: String,
    val diff: String = "",
)

/** Click with no ripple/indication, so transparent dismiss layers don't gray-flash on tap. */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null) { onClick() }
}

/**
 * Chat surface: top bar (menu / Agent / state action) + message list (with a slowly
 * blinking dot while generating) + input bar (+ / field / send↔stop).
 */
@Composable
fun ChatScreen(
    state: PanelState,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showUpload by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
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
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp),
                )
            }
            ChatTopBar(
                state = state,
                onOpenDrawer = onOpenDrawer,
                onOpenModels = onOpenModels,
                menuOpen = showMenu,
                onToggleMenu = { showMenu = it },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 22.dp),
            )
            // Tap anywhere over the conversation to dismiss the overflow menu, then draw
            // the menu above that dismiss layer.
            if (showMenu) {
                Box(Modifier.fillMaxSize().noRippleClickable { showMenu = false })
                OverflowMenu(
                    onShare = { showMenu = false },
                    onDelete = { showMenu = false; state.newChat() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
                )
            }
        }
        InputBar(state, onPlusClick = { showUpload = !showUpload })
      }
      // Plus opens a small floating popup anchored just above the plus button (like the
      // model selector), not a full-width sheet sliding up from the bottom edge.
      if (showUpload) {
          Box(Modifier.fillMaxSize().noRippleClickable { showUpload = false })
      }
      AnimatedVisibility(
          visible = showUpload,
          enter = scaleIn(tween(200), initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)) + fadeIn(tween(200)),
          exit = scaleOut(tween(180), targetScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)) + fadeOut(tween(160)),
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 66.dp),
      ) {
          UploadSheet(onPick = { kind ->
              showUpload = false
              state.pickAttachment(kind)
          })
      }
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: hamburger menu, then the "Agent" capsule right beside it — both white
        // floating buttons hovering over the conversation.
        FloatingCircleButton(onClick = onOpenDrawer) { MenuLinesIcon(color = AgentColors.TextPrimary, size = 18.dp) }
        Spacer(Modifier.width(8.dp))
        FloatingPill(onClick = onOpenModels) { BasicText("Agent", style = AgentText.Title) }
        Spacer(Modifier.weight(1f))
        // On the first send the single round button elongates into the pen+dots pill: the
        // SizeTransform stretches the width while the icons cross-fade, so it visibly morphs.
        AnimatedContent(
            targetState = state.messages.isEmpty(),
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(160)) using
                    SizeTransform { _, _ -> tween(260, easing = FastOutSlowInEasing) }
            },
            label = "topRightButton",
        ) { empty ->
            if (empty) {
                FloatingCircleButton(onClick = { state.tempChat = !state.tempChat }) {
                    Box(contentAlignment = Alignment.Center) {
                        ChatBubbleIcon(color = AgentColors.TextPrimary, size = 18.dp)
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
}

/** White circular button. No shadow — it reads as floating purely by being whiter than
 *  the off-white Surface behind it. */
@Composable
private fun FloatingCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** White pill button (the "Agent" capsule). Floating feel via color contrast, no shadow. */
@Composable
private fun FloatingPill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(AgentShapes.Pill)
            .background(Color.White)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Pen (new chat) + dots (overflow) packed into a single floating ellipse capsule. */
@Composable
private fun FloatingDualPill(onNew: () -> Unit, onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(AgentShapes.Pill)
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().clickable { onNew() }.padding(start = 13.dp, end = 9.dp),
            contentAlignment = Alignment.Center,
        ) { PenSquareIcon(color = AgentColors.TextPrimary, size = 15.dp) }
        Box(Modifier.width(1.dp).height(16.dp).background(AgentColors.HairlineFaint))
        Box(
            Modifier.fillMaxHeight().clickable { onMore() }.padding(start = 9.dp, end = 13.dp),
            contentAlignment = Alignment.Center,
        ) { MoreDotsIcon(color = AgentColors.TextPrimary, size = 15.dp) }
    }
}

@Composable
private fun OverflowMenu(onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(AgentShapes.Sheet)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet),
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
    var followBottom by remember { mutableStateOf(true) }
    var lastScrollValue by remember { mutableStateOf(0) }
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    LaunchedEffect(scroll.value, scroll.maxValue) {
        if (scroll.value < lastScrollValue - 4 && scroll.maxValue - scroll.value > 48) {
            followBottom = false
        }
        if (scroll.maxValue - scroll.value <= 48) {
            followBottom = true
        }
        lastScrollValue = scroll.value
    }
    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text,
        state.messages.lastOrNull()?.tool,
        state.generating,
        scroll.maxValue,
        followBottom,
    ) {
        if (followBottom) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Clear the floating top bar so the first message isn't hidden beneath it.
        Spacer(Modifier.height(64.dp))
        state.messages.forEach { msg ->
            if (msg.fromUser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val bubbleShape = RoundedCornerShape(18.dp)
                    Box(
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(bubbleShape)
                            .background(AgentColors.UserBubble)
                            .border(1.dp, AgentColors.HairlineFaint, bubbleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { BasicText(msg.text, style = AgentText.Body) }
                }
            } else if (msg.tool != null) {
                ToolCardView(msg.tool)
            } else if (msg.text.isNotBlank()) {
                // No avatar / no left indent: the assistant reply reads as plain text spanning
                // the full width. The blank streaming placeholder is skipped so the wait state
                // shows only the single dot below (not an empty bubble + a dot).
                Column(Modifier.fillMaxWidth()) {
                    AssistantContent(msg.text)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).noRippleClickable {
                            clipboard.setPrimaryClip(ClipData.newPlainText("assistant", msg.text))
                        },
                        contentAlignment = Alignment.Center,
                    ) { CopyIcon(size = 14.dp, color = AgentColors.TextSecondary) }
                }
            }
        }
        if (state.generating) {
            // Single slowly-pulsing dot — no avatar — so the wait state shows one dot, not two.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                BlinkingDot(Modifier.padding(start = 2.dp, top = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Renders assistant text, splitting fenced ``` blocks out into rounded CodeBlock boxes. The
 * box is a standing container, so as the streamed text grows the frame is present first and
 * code appears inside it — not code-first-then-frame. Non-code prose gets light markdown:
 * `**bold**` is rendered bold and stray asterisks are dropped.
 */
@Composable
private fun AssistantContent(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseSegments(unescapeText(text)).forEach { (isCode, body) ->
            if (isCode) {
                CodeBlock(body)
            } else {
                BasicText(mdInline(body.trim()), style = AgentText.Body)
            }
        }
    }
}

/**
 * Unescapes the common backslash escapes (`\n` `\t` `\r` `\"` `\\`) that some providers leak
 * as literal two-character sequences into the assistant text, so they render as real newlines
 * / tabs / quotes instead of visible `\n`. Fast-paths text with no backslashes.
 */
private fun unescapeText(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2; continue }
                't' -> { sb.append('\t'); i += 2; continue }
                'r' -> { i += 2; continue }
                '"' -> { sb.append('"'); i += 2; continue }
                '\\' -> { sb.append('\\'); i += 2; continue }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * Minimal inline markdown: turns `**bold**` spans bold and strips any leftover single `*`
 * markers, so model output peppered with asterisks renders clean (bolded, not literal stars).
 */
private fun mdInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("**", i)
        if (open < 0) {
            append(text.substring(i).replace("*", ""))
            break
        }
        val close = text.indexOf("**", open + 2)
        if (close < 0) {
            append(text.substring(i).replace("*", ""))
            break
        }
        append(text.substring(i, open).replace("*", ""))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(text.substring(open + 2, close))
        }
        i = close + 2
    }
}

/** Splits text on ``` fences into (isCode, body) segments; a leading language line on a code
 *  fence is dropped. */
private fun parseSegments(text: String): List<Pair<Boolean, String>> {
    val parts = text.split("```")
    val out = ArrayList<Pair<Boolean, String>>()
    for (i in parts.indices) {
        val seg = parts[i]
        if (i % 2 == 0) {
            if (seg.isNotBlank()) out.add(false to seg)
        } else {
            val body = if (seg.contains('\n')) seg.substringAfter('\n') else seg
            out.add(true to body.trimEnd('\n'))
        }
    }
    return out
}

/** A rounded dark code box with a two-paper copy button pinned to the top-right corner. */
@Composable
private fun CodeBlock(code: String) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(12.dp),
    ) {
        BasicText(
            code,
            style = AgentText.Body.copy(color = Color(0xFFE6E6E6)),
            modifier = Modifier.fillMaxWidth().padding(end = 24.dp, top = 18.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).noRippleClickable {
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
            },
            contentAlignment = Alignment.Center,
        ) { CopyIcon(size = 15.dp, color = Color(0xFFBFBFBF)) }
    }
}

/**
 * Agent-style tool result: a one-line status (colored dot + summary like "wrote app/x.kt
 * +12 -3"), optionally followed by a diff code frame. The model's full raw output is never
 * shown here — it only flows back to the model — so the transcript stays compact.
 */
@Composable
private fun ToolCardView(tool: ToolCard) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when (tool.status) {
                "完成" -> Color(0xFF188038)
                "失败" -> Color(0xFFB3261E)
                else -> AgentColors.Accent
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(
                tool.summary.ifBlank { tool.name },
                style = AgentText.Secondary.copy(color = AgentColors.TextPrimary),
                modifier = Modifier.weight(1f),
            )
        }
        if (tool.diff.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            DiffBlock(tool.diff)
        }
    }
}

/**
 * A dark code frame rendering a unified-style diff: lines starting with `+` get a green tint
 * and lines starting with `-` get a red tint (added / removed), everything else is neutral.
 */
@Composable
private fun DiffBlock(diff: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(vertical = 8.dp),
    ) {
        diff.split('\n').forEach { line ->
            val marker = diffMarker(line)
            val added = marker == '+'
            val removed = marker == '-'
            val bg = when {
                added -> Color(0x3328A745)
                removed -> Color(0x33D73A49)
                else -> Color.Transparent
            }
            val fg = when {
                added -> Color(0xFF7EE787)
                removed -> Color(0xFFFFA198)
                else -> Color(0xFFE6E6E6)
            }
            BasicText(
                line.ifEmpty { " " },
                style = AgentText.Label.copy(color = fg),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            )
        }
    }
}

private fun diffMarker(line: String): Char? {
    if (line.startsWith("+")) return '+'
    if (line.startsWith("-")) return '-'
    val trimmed = line.trimStart()
    var i = 0
    while (i < trimmed.length && trimmed[i].isDigit()) i++
    if (i == 0) return null
    while (i < trimmed.length && trimmed[i].isWhitespace()) i++
    return when (trimmed.getOrNull(i)) {
        '+' -> '+'
        '-' -> '-'
        else -> null
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
private fun InputBar(state: PanelState, onPlusClick: () -> Unit) {
    // Plus + text field + send wrapped in one white container with a fixed rounded-rectangle
    // shape. Buttons stay vertically centered in the input, including the one-line state.
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, shape)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlusButton(onClick = onPlusClick)
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.weight(1f).heightIn(min = 32.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (state.input.isEmpty()) BasicText("问问…", style = AgentText.Hint)
            BasicTextField(
                value = state.input,
                onValueChange = { state.input = it },
                textStyle = AgentText.Body,
                cursorBrush = SolidColor(AgentColors.Accent),
                maxLines = 6,
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

/** Plus button with a press-scale touch animation (shrinks to 0.86 while held). */
@Composable
private fun PlusButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "plusScale")
    Box(
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) { PlusIcon(color = AgentColors.TextPrimary, size = 18.dp) }
}

@Composable
private fun SendStopButton(generating: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Box(
        modifier = Modifier
            .size(27.dp)
            .clip(CircleShape)
            .background(if (generating) AgentColors.Stop else AgentColors.Accent)
            .clickable { if (generating) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        if (generating) {
            // Rounded square = stop.
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        } else {
            SendArrowIcon(size = 15.dp, color = Color.White)
        }
    }
}

/** Floating popup shown above the plus button: a narrow white rounded card listing the four
 *  upload sources as rows (same look as the model selector, not a bottom sheet). */
@Composable
private fun UploadSheet(onPick: (AgentAttachmentKind) -> Unit) {
    Column(
        modifier = Modifier
            .width(182.dp)
            .clip(AgentShapes.Sheet)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet)
            .padding(vertical = 6.dp),
    ) {
        UploadRow("相机", AgentAttachmentKind.Camera, onPick) {
            CameraIcon(color = AgentColors.TextPrimary, size = 18.dp)
        }
        UploadRow("图片", AgentAttachmentKind.Image, onPick) {
            ImageLandscapeIcon(size = 21.dp)
        }
        UploadRow("文件", AgentAttachmentKind.File, onPick) {
            SpiralClipIcon(color = AgentColors.TextPrimary, size = 20.dp)
        }
        UploadRow("插件", AgentAttachmentKind.Plugin, onPick) {
            PluginPlugIcon(color = AgentColors.TextPrimary, size = 20.dp)
        }
    }
}

@Composable
private fun UploadRow(
    label: String,
    kind: AgentAttachmentKind,
    onPick: (AgentAttachmentKind) -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(kind) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AgentColors.Control),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = AgentText.Body)
    }
}
