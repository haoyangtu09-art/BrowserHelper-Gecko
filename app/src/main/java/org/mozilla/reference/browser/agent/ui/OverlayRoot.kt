/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.agent.AgentAttachmentActivity
import org.mozilla.reference.browser.agent.AgentAttachmentBridge
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes

// Base (scale = 1f) panel content size in dp. The panel is always laid out at this size
// and uniformly scaled, so resizing grows/shrinks the whole UI together. Must match the
// BASE_*_DP constants the service uses to size the WRAP_CONTENT window.
private const val PANEL_BASE_W_DP = 320f
private const val PANEL_BASE_H_DP = 520f

/**
 * Floating Agent UI: a pure-white draggable ball (snaps to the nearest edge with an
 * animation handled natively) that expands — animated, growing from the side the ball
 * sits on — into a phone-ratio rounded panel. The panel has a single collapse control
 * (a gray minus ball, top-right) and a blue drag handle near the top-center that shows
 * a gray ellipse while held.
 */
@Composable
fun OverlayRoot(
    expanded: Boolean,
    anchorRight: Boolean,
    dimmed: Boolean,
    panelScale: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onWake: () -> Unit,
    onBallDrag: (Float, Float) -> Unit,
    onBallDragEnd: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
    onPanelResize: (Float, Float) -> Unit,
) {
    // Panel state + backend wiring live here, ABOVE the AnimatedContent, so collapsing to the
    // ball (which tears down the panel subtree) does not destroy the conversation. Only the
    // panel/ball visuals are swapped; this state persists for the lifetime of the overlay.
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
    state.onGenerateMemorySummary = { engine.generateMemorySummary(scope, state) }
    state.onPickAttachment = { kind -> AgentAttachmentActivity.launch(context.applicationContext, kind) }
    LaunchedEffect(Unit) { settings.loadInto(state) }
    DisposableEffect(state) {
        AgentAttachmentBridge.listener = { result -> state.addAttachment(result) }
        onDispose {
            if (AgentAttachmentBridge.listener != null) AgentAttachmentBridge.listener = null
        }
    }

    AnimatedContent(
        targetState = expanded,
        // Keep the shared content pinned to the anchored side during the size change.
        // Without this the small ball is laid out at the container's top-start while the
        // window grows leftward for a right-side panel, so the ball visibly jumps left
        // before the panel grows.
        contentAlignment = if (anchorRight) Alignment.TopEnd else Alignment.TopStart,
        transitionSpec = {
            // Grow/shrink from the anchored corner with one shared easing so the scale and
            // fade stay in lockstep — symmetric in/out durations avoid the "闪现" snap feel.
            val origin = TransformOrigin(if (anchorRight) 1f else 0f, 0f)
            (
                scaleIn(animationSpec = tween(260, easing = FastOutSlowInEasing), initialScale = 0.82f, transformOrigin = origin) +
                    fadeIn(tween(220, easing = FastOutSlowInEasing))
                ) togetherWith (
                scaleOut(animationSpec = tween(260, easing = FastOutSlowInEasing), targetScale = 0.82f, transformOrigin = origin) +
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
                ) using SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "overlay",
    ) { isExpanded ->
        if (isExpanded) {
            AgentPanel(
                state = state,
                scale = panelScale,
                onCollapse = onCollapse,
                onPanelDrag = onPanelDrag,
                onPanelResize = onPanelResize,
            )
        } else {
            AgentBall(
                dimmed = dimmed,
                onExpand = onExpand,
                onWake = onWake,
                onDrag = onBallDrag,
                onDragEnd = onBallDragEnd,
            )
        }
    }
}

@Composable
private fun AgentBall(
    dimmed: Boolean,
    onExpand: () -> Unit,
    onWake: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // When parked at the edge with no recent interaction the ball dims so it stays
    // out of the way; any touch wakes it (handled natively) and animates back to 1f.
    val ballAlpha by animateFloatAsState(if (dimmed) 0.4f else 1f, label = "ballDim")
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(49.dp)
            .graphicsLayer { alpha = ballAlpha }
            // A non-consuming down observer so even a parked/dimmed ball wakes the
            // instant it is touched, before the tap/drag detectors below decide.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onWake()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onExpand() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Inner solid white disc carrying the agent glyph; sized to meet the ring's
        // inner edge (ring 49 − 2×5 border) so there is no gap and the ring's stroke
        // still shows outside the disc against the page behind it. A large-radius
        // squircle (not a perfect circle) per the requested ball outline.
        Box(
            modifier = Modifier
                .size(39.dp)
                .clip(AgentShapes.Squircle)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.agent_ball_icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(5.dp)
                    .size(26.dp),
            )
        }
        // Outer semi-transparent white ring (stroke only); width/color unchanged per
        // request ("最外层那条线不要动"), but follows the squircle outline now.
        Box(
            modifier = Modifier
                .size(49.dp)
                .border(width = 5.dp, color = Color(0x80FFFFFF), shape = AgentShapes.Squircle),
        )
    }
}

@Composable
private fun AgentPanel(
    state: PanelState,
    scale: Float,
    onCollapse: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
    onPanelResize: (Float, Float) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    // The panel content is always laid out at its base size and uniformly scaled, so the
    // whole UI (floating buttons, text, input) grows/shrinks together when resized.
    Box(
        modifier = Modifier
            .padding(8.dp)
            .size((PANEL_BASE_W_DP * scale).dp, (PANEL_BASE_H_DP * scale).dp),
    ) {
        Box(
            modifier = Modifier
                .size(PANEL_BASE_W_DP.dp, PANEL_BASE_H_DP.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .clip(shape)
                // Slightly off-white surface so white buttons/popups read as hovering.
                .background(AgentColors.Surface)
                // Faint gray inset stroke hugging the rounded edge.
                .border(1.dp, AgentColors.HairlineFaint, shape),
        ) {
            // Chat surface + internal navigation fills the whole rounded panel. The top drag
            // handle is a transparent overlay, so there is no blank white strip hiding context.
            AgentPanelHost(state = state, modifier = Modifier.fillMaxSize())
            // Window chrome overlay: blue drag handle (center) + collapse minus (end).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .align(Alignment.TopCenter),
            ) {
                var pressed by remember { mutableStateOf(false) }
                val ellipseAlpha by animateFloatAsState(if (pressed) 1f else 0f, label = "ellipse")
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                pressed = true
                                var down = true
                                while (down) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        val delta = change.positionChange()
                                        if (delta != Offset.Zero) {
                                            onPanelDrag(delta.x, delta.y)
                                            change.consume()
                                        }
                                    }
                                    down = event.changes.any { it.pressed }
                                }
                                pressed = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { alpha = ellipseAlpha }
                            .size(width = 60.dp, height = 22.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFFDADADA)),
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 42.dp, height = 3.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFF4285F4)),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 10.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEAEAEA))
                        .clickable { onCollapse() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 2.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFF666666)),
                    )
                }
            }
            // Keep resize grips attached to the panel's rounded corners. They live inside
            // the scaled panel so their anchors follow the corners while inverse layers keep
            // the touch targets at a stable physical size as the whole panel shrinks.
            ResizeHandle(
                corner = ResizeCorner.Start,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        val inv = 1f / scale.coerceAtLeast(0.01f)
                        scaleX = inv
                        scaleY = inv
                        transformOrigin = TransformOrigin(0f, 1f)
                    },
                onResize = { dx, dy -> onPanelResize(-dx, dy) },
            )
            ResizeHandle(
                corner = ResizeCorner.End,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        val inv = 1f / scale.coerceAtLeast(0.01f)
                        scaleX = inv
                        scaleY = inv
                        transformOrigin = TransformOrigin(1f, 1f)
                    },
                onResize = onPanelResize,
            )
        }
    }
}

private enum class ResizeCorner { Start, End }

/**
 * A subtle curved resize line in either bottom corner. The visual is just a thin gray arc,
 * while the invisible 30dp target remains easy to drag at every panel scale.
 */
@Composable
private fun ResizeHandle(corner: ResizeCorner, modifier: Modifier, onResize: (Float, Float) -> Unit) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(30.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val path = Path()
            if (corner == ResizeCorner.End) {
                path.moveTo(size.width * 0.22f, size.height * 0.86f)
                path.quadraticTo(
                    size.width * 0.86f,
                    size.height * 0.86f,
                    size.width * 0.86f,
                    size.height * 0.22f,
                )
            } else {
                path.moveTo(size.width * 0.78f, size.height * 0.86f)
                path.quadraticTo(
                    size.width * 0.14f,
                    size.height * 0.86f,
                    size.width * 0.14f,
                    size.height * 0.22f,
                )
            }
            drawPath(
                path = path,
                color = Color(0xFFB7B7B7),
                style = Stroke(width = size.minDimension * 0.08f, cap = StrokeCap.Round),
            )
        }
    }
}
