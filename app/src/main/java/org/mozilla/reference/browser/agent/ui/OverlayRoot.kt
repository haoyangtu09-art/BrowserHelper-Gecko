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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

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
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onBallDrag: (Float, Float) -> Unit,
    onBallDragEnd: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
) {
    AnimatedContent(
        targetState = expanded,
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
            AgentPanel(onCollapse = onCollapse, onPanelDrag = onPanelDrag)
        } else {
            AgentBall(onExpand = onExpand, onDrag = onBallDrag, onDragEnd = onBallDragEnd)
        }
    }
}

@Composable
private fun AgentBall(
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(56.dp)
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
        // Outer semi-transparent white ring (stroke only), thick and hugging the ball.
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(width = 3.dp, color = Color(0x80FFFFFF), shape = CircleShape),
        )
        // Inner solid white ball, sized to meet the ring's inner edge (no gap).
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun AgentPanel(
    onCollapse: () -> Unit,
    onPanelDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .width(320.dp)
            .height(520.dp),
    ) {
        // Blue drag handle near the top-center; a gray ellipse stays visible the whole
        // time the bar is held (touch-down through drag to release). A single gesture
        // loop drives both the press feedback and the drag so the ellipse doesn't vanish
        // once dragging starts.
        var pressed by remember { mutableStateOf(false) }
        val ellipseAlpha by animateFloatAsState(if (pressed) 1f else 0f, label = "ellipse")
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
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

        // Single collapse control: a minus inside a small gray ball, top-right (compact).
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
}
