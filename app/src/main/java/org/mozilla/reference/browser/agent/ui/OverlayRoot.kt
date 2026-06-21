/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentShapes

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
    panelWidthDp: Float,
    panelHeightDp: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onWake: () -> Unit,
    onBallDrag: (Float, Float) -> Unit,
    onBallDragEnd: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
    onPanelResize: (Float, Float) -> Unit,
) {
    AnimatedContent(
        targetState = expanded,
        // Keep the shared content pinned to the anchored side during the size change.
        // Without this the small ball is laid out at the container's top-start while the
        // window grows leftward for a right-side panel, so the ball visibly jumps left
        // before the panel grows.
        contentAlignment = if (anchorRight) Alignment.TopEnd else Alignment.TopStart,
        transitionSpec = {
            val origin = TransformOrigin(if (anchorRight) 1f else 0f, 0f)
            (
                scaleIn(animationSpec = tween(240), initialScale = 0.85f, transformOrigin = origin) +
                    fadeIn(tween(240))
                ) togetherWith (
                scaleOut(animationSpec = tween(320), targetScale = 0.85f, transformOrigin = origin) +
                    fadeOut(tween(300))
                ) using SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "overlay",
    ) { isExpanded ->
        if (isExpanded) {
            AgentPanel(
                widthDp = panelWidthDp,
                heightDp = panelHeightDp,
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
    widthDp: Float,
    heightDp: Float,
    onCollapse: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
    onPanelResize: (Float, Float) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .padding(8.dp)
            .width(widthDp.dp)
            .height(heightDp.dp)
            .clip(shape)
            .background(Color.White)
            // Faint gray inset stroke hugging the rounded edge.
            .border(1.dp, AgentColors.HairlineFaint, shape),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Window chrome zone: blue drag handle (center) + collapse minus (end).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
            ) {
                // Blue drag handle near the top-center; a gray ellipse stays visible the
                // whole time the bar is held (touch-down through drag to release). A single
                // gesture loop drives both the press feedback and the drag so the ellipse
                // doesn't vanish once dragging starts.
                var pressed by remember { mutableStateOf(false) }
                val ellipseAlpha by animateFloatAsState(if (pressed) 1f else 0f, label = "ellipse")
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
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
                            .size(width = 60.dp, height = 24.dp)
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

                // Single collapse control: a minus inside a small gray ball, top-right.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
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

            // Chat surface + internal navigation (drawer / settings / model selector).
            AgentPanelHost(modifier = Modifier.weight(1f))
        }
        // Bottom-right resize grip: drag to change panel width/height (handled natively).
        ResizeHandle(
            modifier = Modifier.align(Alignment.BottomEnd),
            onResize = onPanelResize,
        )
    }
}

/** A small diagonal grip in the panel's bottom-right corner; dragging resizes the window. */
@Composable
private fun ResizeHandle(modifier: Modifier, onResize: (Float, Float) -> Unit) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(24.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val sw = size.minDimension * 0.10f
            drawLine(
                AgentColors.Hairline,
                Offset(size.width, size.height * 0.32f),
                Offset(size.width * 0.32f, size.height),
                sw, StrokeCap.Round,
            )
            drawLine(
                AgentColors.Hairline,
                Offset(size.width, size.height * 0.7f),
                Offset(size.width * 0.7f, size.height),
                sw, StrokeCap.Round,
            )
        }
    }
}
