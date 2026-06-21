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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
                scaleIn(animationSpec = tween(220), initialScale = 0.85f, transformOrigin = origin) +
                    fadeIn(tween(220))
                ) togetherWith (
                scaleOut(animationSpec = tween(180), targetScale = 0.85f, transformOrigin = origin) +
                    fadeOut(tween(160))
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
        // Outer white ring (stroke only) with a transparent gap to the ball.
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(width = 2.dp, color = Color.White, shape = CircleShape),
        )
        // Inner solid white ball.
        Box(
            modifier = Modifier
                .size(44.dp)
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
        // Blue drag handle near the top-center; a gray ellipse fades in while held.
        var pressed by remember { mutableStateOf(false) }
        val ellipseAlpha by animateFloatAsState(if (pressed) 1f else 0f, label = "ellipse")
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pressed = true },
                        onDragEnd = { pressed = false },
                        onDragCancel = { pressed = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onPanelDrag(dragAmount.x, dragAmount.y)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = ellipseAlpha }
                    .size(width = 64.dp, height = 26.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFFDADADA)),
            )
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF4285F4)),
            )
        }

        // Single collapse control: a minus inside a small gray ball, top-right.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFFEAEAEA))
                .clickable { onCollapse() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF666666)),
            )
        }
    }
}
