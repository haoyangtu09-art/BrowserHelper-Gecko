/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Step 1 of the floating Agent UI: a draggable, edge-snapping white ball that expands
 * into a placeholder panel. The full ChatGPT-style chat shell (top bar, message list,
 * input bar, drawer, settings, model selector) lands in later steps; this exists to
 * validate the riskiest plumbing first — overlay window, Compose-in-window, the
 * focusable-on-expand IME path, the plugin toggle and the permission flow.
 */
@Composable
fun OverlayRoot(
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onClose: () -> Unit,
) {
    Box {
        if (expanded) {
            AgentPanel(onCollapse = onCollapse, onClose = onClose)
        } else {
            AgentBall(onExpand = onExpand, onDrag = onDrag, onDragEnd = onDragEnd)
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
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
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
        Text(text = "AI", color = Color(0xFF4285F4), fontSize = 16.sp)
    }
}

@Composable
private fun AgentPanel(
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(8.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .width(320.dp)
            .height(520.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Agent", color = Color(0xFF111111), fontSize = 18.sp)
                Row {
                    Text(
                        text = "收起",
                        color = Color(0xFF4285F4),
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onCollapse() },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "关闭",
                        color = Color(0xFF999999),
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onClose() },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {}
            Text(
                text = "v0.1 占位面板（完整 UI 外壳后续补全）",
                color = Color(0xFF999999),
                fontSize = 12.sp,
            )
        }
    }
}
