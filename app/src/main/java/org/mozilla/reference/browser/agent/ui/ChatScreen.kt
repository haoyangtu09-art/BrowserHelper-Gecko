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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    // "Running" while executing, "Done"/"Failed" once resolved. Drives the blinking bullet.
    val status: String,
    // Agent-standard header rendered after the ● bullet, e.g. "Read(agent.js)" / "Update(x.kt)".
    val summary: String,
    val diff: String = "",
    // Raw tool result shown in the ⎿ block (capped to 6 lines, expandable, copyable). Used by
    // non-edit tools; edit/write tools use [diff] instead. Empty → header-only card.
    val output: String = "",
    // Full raw error text for a failed tool call, shown verbatim (multi-line) in the chat so
    // failures are easy to debug. Empty for successful calls.
    val error: String = "",
    // English one-line diff summary ("Added 3 lines") shown on the ⎿ line above an edit diff.
    val diffLabel: String = "",
    // True while the tool is still executing — the bullet blinks and a "Running… (Xs)" line shows.
    val running: Boolean = false,
)

/** Click with no ripple/indication, so transparent dismiss layers don't gray-flash on tap. */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
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
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
                )
            }
            ChatTopBar(
                state = state,
                onOpenDrawer = onOpenDrawer,
                onOpenModels = onOpenModels,
                menuOpen = showMenu,
                onToggleMenu = { showMenu = it },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 34.dp),
            )
            // Tap anywhere over the conversation to dismiss the overflow menu, then draw
            // the menu above that dismiss layer.
            if (showMenu) {
                Box(Modifier.fillMaxSize().noRippleClickable { showMenu = false })
                OverflowMenu(
                    onShare = { showMenu = false },
                    onDelete = { showMenu = false; state.newChat() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp),
                )
            }
        }
        InputBar(state, onPlusClick = { showUpload = !showUpload })
      }
      // Compact working pill floating just above the input bar's left edge while generating. It
      // sits in the outer Box (not the top row) so the upload sheet, anchored at the same corner,
      // can slide up and cover it.
      if (state.generating) {
          WorkingBar(
              state,
              modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 58.dp),
          )
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
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 40.dp),
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
            Modifier.fillMaxHeight().clickable { onNew() }.padding(start = 12.dp, end = 5.dp),
            contentAlignment = Alignment.Center,
        ) { PenSquareIcon(color = AgentColors.TextPrimary, size = 15.dp) }
        Box(
            Modifier.fillMaxHeight().clickable { onMore() }.padding(start = 5.dp, end = 12.dp),
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
        // Re-scroll when the task tracker mutates so the active task row stays in view.
        state.tracker.lastUpdated,
        state.tracker.isExpanded,
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
        Spacer(Modifier.height(76.dp))
        state.messages.forEachIndexed { idx, msg ->
            // The message still being streamed is the last one while generating; its copy
            // button is suppressed until generation completes.
            val streaming = state.generating && idx == state.messages.lastIndex
            if (msg.fromUser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val bubbleShape = RoundedCornerShape(18.dp)
                    Box(
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(bubbleShape)
                            .background(AgentColors.UserBubble)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { BasicText(msg.text, style = AgentText.Body.copy(fontSize = 10.5.sp)) }
                }
            } else if (msg.tool != null) {
                ToolCardView(msg.tool)
            } else if (msg.text.isNotBlank()) {
                // No avatar / no left indent: the assistant reply reads as plain text spanning
                // the full width. The blank streaming placeholder is skipped so the wait state
                // shows only the single dot below (not an empty bubble + a dot).
                Column(Modifier.fillMaxWidth()) {
                    AssistantContent(msg.text)
                    if (!streaming) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).noRippleClickable {
                                clipboard.setPrimaryClip(ClipData.newPlainText("assistant", msg.text))
                            },
                            contentAlignment = Alignment.Center,
                        ) { CopyIcon(size = 14.dp, color = AgentColors.TextSecondary, bg = AgentColors.Surface) }
                    }
                }
            }
        }
        // Visual task list — a flat, borderless todo list at the bottom of the transcript, just
        // above the latest message. Empty trackers self-skip. The "generating" state is shown by
        // the floating WorkingBar at the top, so there is no separate bottom dot here.
        if (state.tracker.groups.isNotEmpty()) {
            TaskTrackerCard(state)
        }
        // Blinking black dot at the foot of the transcript while the model works — the inline
        // "typing" indicator (the compact WorkingBar above the input shows the verb + clock).
        if (state.generating) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) { ToolBullet(running = true) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Renders an assistant chat message as full Markdown (headings, bold/italic/strikethrough, inline
 * code, fenced code blocks, lists, task lists, quotes, tables, links). The actual rendering lives
 * in [MarkdownContent] (MarkdownText.kt) which parses with commonmark-java and emits bare-Compose;
 * tool / plan / task cards deliberately do NOT go through here and keep their own flat styling.
 */
@Composable
private fun AssistantContent(text: String) {
    MarkdownContent(text)
}

/**
 * Agent-standard tool result: a header line "● name(arg)" followed by a "⎿"-connected result
 * block. Non-edit tools show their raw output (capped to 6 lines, expandable + copyable);
 * edit/write tools show a +/- diff; failures show the full raw error. The result block sits in
 * a plain gray frame with NO +/- coloring (that is reserved for file-write diffs).
 */
@Composable
private fun ToolCardView(tool: ToolCard) {
    val running = tool.status == "Running"
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Gray bullet (blinking while running) — logs read as a quiet subordinate of the
            // prose, never competing with it.
            ToolBullet(running)
            Spacer(Modifier.width(6.dp))
            BasicText(
                tool.summary.ifBlank { tool.name },
                style = ToolLogStyle,
                modifier = Modifier.weight(1f),
            )
        }
        when {
            running -> RunningConnector()
            tool.error.isNotBlank() -> Connector { OutputBlock(tool.error, capped = true) }
            tool.diff.isNotBlank() -> DiffConnector(tool.diffLabel, tool.diff)
            tool.output.isNotBlank() -> Connector { OutputBlock(tool.output, capped = true) }
        }
    }
}

/** Small gray monospace style shared by every tool-log line, keeping logs flat + subordinate. */
private val ToolLogStyle = AgentText.Label.copy(
    color = AgentColors.TextSecondary,
    fontFamily = FontFamily.Monospace,
    fontSize = 8.4.sp,
    lineHeight = 11.sp,
)

/** Small black "●" bullet before a tool header / working bar; pulses while still running. */
@Composable
private fun ToolBullet(running: Boolean) {
    if (running) {
        val transition = rememberInfiniteTransition(label = "toolDot")
        val alpha by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "toolDotAlpha",
        )
        BasicText("●", style = BulletStyle, modifier = Modifier.graphicsLayer { this.alpha = alpha })
    } else {
        BasicText("●", style = BulletStyle)
    }
}

/** A small black dot: the tool-log / working-bar bullet, smaller than the log text around it. */
private val BulletStyle = ToolLogStyle.copy(color = AgentColors.TextPrimary, fontSize = 8.sp)

/** Lays out the "⎿" connector glyph beside an indented result block. */
@Composable
private fun Connector(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        BasicText(
            "⎿",
            style = ToolLogStyle.copy(color = AgentColors.TextTertiary),
            modifier = Modifier.padding(start = 3.dp, end = 6.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** "⎿ Running… (Xs)" line shown under a tool header while it is still executing. The seconds
 *  tick up from when the running card first appears. */
@Composable
private fun RunningConnector() {
    val start = remember { System.currentTimeMillis() }
    var secs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            secs = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        BasicText(
            "⎿",
            style = ToolLogStyle.copy(color = AgentColors.TextTertiary),
            modifier = Modifier.padding(start = 3.dp, end = 6.dp),
        )
        BasicText("Running… (${secs}s)", style = ToolLogStyle.copy(color = AgentColors.TextTertiary))
    }
}

/** "⎿ Added N lines" label followed by the indented +/- diff (file-edit tools only). */
@Composable
private fun DiffConnector(label: String, diff: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            BasicText(
                "⎿",
                style = ToolLogStyle.copy(color = AgentColors.TextTertiary),
                modifier = Modifier.padding(start = 3.dp, end = 6.dp),
            )
            BasicText(label.ifBlank { "Diff" }, style = ToolLogStyle.copy(color = AgentColors.TextTertiary))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f)) { DiffBlock(diff) }
        }
    }
}

/**
 * Tool output / raw error in a plain gray box (monospace, no +/- coloring). When [capped] the
 * box shows the first [CAP_LINES] lines with a "… +N 行" hint, plus a 展开/收起 toggle; a copy
 * button always sits at the bottom-right so the full text is one tap away.
 */
@Composable
private fun OutputBlock(text: String, capped: Boolean) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var expanded by remember(text) { mutableStateOf(false) }
    val lines = text.split('\n')
    val overflow = capped && lines.size > CAP_LINES
    val shown = if (overflow && !expanded) lines.take(CAP_LINES).joinToString("\n") else text
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CodeBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        BasicText(
            shown,
            style = AgentText.Label.copy(
                color = AgentColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.4.sp,
                lineHeight = 11.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (overflow && !expanded) {
            BasicText(
                "… +${lines.size - CAP_LINES} lines",
                style = AgentText.Label.copy(color = AgentColors.TextTertiary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (overflow || expanded) {
                BasicText(
                    if (expanded) "collapse" else "expand",
                    style = AgentText.Label.copy(color = AgentColors.TextSecondary),
                    modifier = Modifier.noRippleClickable { expanded = !expanded }.padding(end = 12.dp),
                )
            }
            Box(
                Modifier.size(20.dp).clip(CircleShape).noRippleClickable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("tool", text))
                },
                contentAlignment = Alignment.Center,
            ) { CopyIcon(size = 13.dp, color = AgentColors.TextSecondary, bg = CodeBg) }
        }
    }
}

/** Max lines a capped tool-output block shows before folding the rest behind "… +N 行". */
private const val CAP_LINES = 5

/**
 * A dark code frame rendering a unified-style diff: lines starting with `+` get a green tint
 * and lines starting with `-` get a red tint (added / removed), everything else is neutral.
 */
@Composable
private fun DiffBlock(diff: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CodeBg)
            .padding(vertical = 6.dp),
    ) {
        diff.split('\n').forEach { line ->
            val marker = diffMarker(line)
            val added = marker == '+'
            val removed = marker == '-'
            // Gray box, muted neutral text; only the +/- lines keep red/green (tints darkened so
            // they read on the light-gray background).
            val bg = when {
                added -> Color(0x1A188038)
                removed -> Color(0x1AB3261E)
                else -> Color.Transparent
            }
            val fg = when {
                added -> Color(0xFF188038)
                removed -> Color(0xFFB3261E)
                else -> AgentColors.TextSecondary
            }
            BasicText(
                line.ifEmpty { " " },
                style = AgentText.Label.copy(color = fg, fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 9.dp, vertical = 1.dp),
            )
        }
    }
}

/** Shared light-gray background for code / diff / error boxes (replaces the old near-black). */
private val CodeBg = Color(0xFFEDEEF0)

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

/** Compact floating status pill shown above the input while the model is working: a blinking
 *  bullet + cycling gerund + elapsed time, e.g. "Cooking… 1m 12s". */
@Composable
private fun WorkingBar(state: PanelState, modifier: Modifier = Modifier) {
    // Re-tick once a second so the elapsed clock advances.
    val start = state.turnStartMs
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((nowMs - start) / 1000).coerceAtLeast(0)
    val timeStr = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
    val word = WORKING_WORDS[((elapsed / 3) % WORKING_WORDS.size).toInt()]
    Row(
        modifier = modifier
            .clip(AgentShapes.Pill)
            .background(Color.White)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Compact: just the cycling verb + elapsed clock, so the pill stays ~1/3 of the input width.
        BasicText("$word… $timeStr", style = ToolLogStyle.copy(fontSize = 9.sp))
    }
}

/** Playful cycling verbs for the working bar (Claude-Code style), one swapped in every ~3s. */
private val WORKING_WORDS = listOf(
    "Thinking", "Working", "Imagining", "Reasoning", "Composing", "Crafting",
    "Cooking", "Brewing", "Conjuring", "Pondering", "Tinkering", "Noodling",
    "Simmering", "Percolating", "Mulling", "Scheming", "Hatching", "Wrangling",
)

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
            .width(137.dp)
            .clip(AgentShapes.Sheet)
            .background(Color.White)
            .border(1.dp, AgentColors.HairlineFaint, AgentShapes.Sheet)
            .padding(vertical = 4.dp),
    ) {
        UploadRow("相机", AgentAttachmentKind.Camera, onPick) {
            CameraIcon(color = AgentColors.TextPrimary, size = 14.dp)
        }
        UploadRow("图片", AgentAttachmentKind.Image, onPick) {
            ImageLandscapeIcon(size = 16.dp)
        }
        UploadRow("文件", AgentAttachmentKind.File, onPick) {
            SpiralClipIcon(color = AgentColors.TextPrimary, size = 15.dp)
        }
        UploadRow("插件", AgentAttachmentKind.Plugin, onPick) {
            PluginPlugIcon(color = AgentColors.TextPrimary, size = 15.dp)
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 29.dp, height = 24.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(AgentColors.Control),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(8.dp))
        BasicText(label, style = AgentText.Body.copy(fontSize = 10.5.sp))
    }
}
