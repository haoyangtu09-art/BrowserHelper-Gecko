/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.core.TaskGroup
import org.mozilla.reference.browser.agent.core.TaskItem
import org.mozilla.reference.browser.agent.core.TaskStatus
import org.mozilla.reference.browser.agent.core.TaskToolCallRecord
import org.mozilla.reference.browser.agent.core.TaskTrackerState
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Visual Task Tracker card rendered inline in the chat transcript. Follows the project's
 * BasicText / foundation-only style (no MaterialTheme ambient in the overlay window).
 *
 *  - Collapsed: one-line "● Working on: <title>  +N more" + tap to expand.
 *  - Expanded: per-group title chip + every task with its icon + duration + tool-call rows.
 *
 * Status visual mapping (matches Claude Code / Cursor agent / Codex):
 *  - PENDING   ◻ gray
 *  - ACTIVE    ◼ blue (Accent)
 *  - DONE      ✔ green
 *  - FAILED    ✕ red
 *  - CANCELLED ⊘ dim
 *
 * The card is driven entirely off state.tracker (a Compose mutableStateOf<TaskTrackerState>);
 * every TaskTrackerManager.mutate reassigns the slot so the card recomposes automatically.
 */
@Composable
fun TaskTrackerCard(state: PanelState) {
    val tracker = state.tracker
    if (tracker.groups.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AgentColors.Panel)
            .border(1.dp, AgentColors.HairlineFaint, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Header(tracker = tracker, onClick = { state.tracker = tracker.copy(isExpanded = !tracker.isExpanded) })
        AnimatedVisibility(
            visible = tracker.isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(10.dp))
                tracker.groups.forEachIndexed { index, group ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    GroupBlock(group = group, currentTaskId = tracker.currentTaskId)
                }
            }
        }
    }
}

/**
 * Header row. Collapsed: showing "Working on" + summary counts. Always tappable to toggle
 * the expanded sub-tree. The chevron-like indicator is just "▾" / "▸" so we stay font-only
 * (no extra vector assets).
 */
@Composable
private fun Header(tracker: TaskTrackerState, onClick: () -> Unit) {
    val counts = tracker.counts()
    val current = tracker.currentTask()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (current?.status == TaskStatus.FAILED) StatusFailed else AgentColors.Accent,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.padding(end = 8.dp)) {
            BasicText(
                if (tracker.isExpanded) "任务追踪" else (current?.let { "进行中：${it.title}" } ?: "任务追踪"),
                style = AgentText.Title,
            )
            // Compact counts shown only in collapsed mode so the header doesn't get noisy
            // when the body already lists everything.
            if (!tracker.isExpanded) {
                Spacer(Modifier.height(2.dp))
                BasicText(buildSummaryLine(counts), style = AgentText.Secondary)
            }
        }
        Spacer(Modifier.weight(1f))
        BasicText(
            if (tracker.isExpanded) "▾" else "▸",
            style = AgentText.Secondary,
        )
    }
}

// IntArray order from TaskTrackerState.counts(): {done, active, pending, failed, cancelled}.
private fun buildSummaryLine(counts: IntArray): String {
    val parts = ArrayList<String>()
    if (counts[0] > 0) parts.add("已完成 ${counts[0]}")
    if (counts[1] > 0) parts.add("进行中 ${counts[1]}")
    if (counts[2] > 0) parts.add("待办 ${counts[2]}")
    if (counts[3] > 0) parts.add("失败 ${counts[3]}")
    if (counts[4] > 0) parts.add("取消 ${counts[4]}")
    return if (parts.isEmpty()) "无任务" else parts.joinToString(" · ")
}

@Composable
private fun GroupBlock(group: TaskGroup, currentTaskId: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(groupChipColor(group.status))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                BasicText(
                    group.title,
                    style = AgentText.Label.copy(color = Color.White, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.width(8.dp))
            BasicText(groupStatusLabel(group.status), style = AgentText.Secondary)
        }
        Spacer(Modifier.height(6.dp))
        group.tasks.forEach { task ->
            TaskRow(task = task, isCurrent = task.id == currentTaskId)
        }
        if (group.tasks.isEmpty()) {
            BasicText("（任务组为空）", style = AgentText.Hint)
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, isCurrent: Boolean) {
    val durationLabel = task.durationMs?.let { formatDuration(it) } ?: ""
    // Subtle highlight band on the currently active task so users can scan to "what's
    // running right now" without reading the icons. Only ACTIVE tasks get the tint.
    val bg = if (isCurrent && task.status == TaskStatus.ACTIVE) AgentColors.Bg else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BasicText(
                statusIcon(task.status),
                style = AgentText.Body.copy(color = statusColor(task.status), fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        task.title,
                        style = AgentText.Body.copy(
                            color = statusColor(task.status),
                            fontWeight = if (task.status == TaskStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                    if (durationLabel.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        BasicText(durationLabel, style = AgentText.Label)
                    }
                }
                if (task.description.isNotBlank()) {
                    BasicText(task.description, style = AgentText.Secondary)
                }
                if (task.status == TaskStatus.FAILED && task.error.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    BasicText("错误：${task.error}", style = AgentText.Secondary.copy(color = StatusFailed))
                }
                // Tool calls associated with this task — each shown as a tiny dim sub-row.
                // This is the "tool calls appear under their owning task" affordance from
                // the spec. We cap rows visually here too (manager already caps at 25 in data).
                if (task.toolCalls.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    task.toolCalls.forEach { rec ->
                        ToolCallRow(rec)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallRow(rec: TaskToolCallRecord) {
    val ok = rec.status == "完成"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        BasicText(
            if (ok) "↳" else "↳",
            style = AgentText.Label.copy(color = if (ok) AgentColors.TextTertiary else StatusFailed),
        )
        Spacer(Modifier.width(4.dp))
        BasicText(
            rec.name,
            style = AgentText.Label.copy(
                color = if (ok) AgentColors.TextSecondary else StatusFailed,
            ),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(formatDuration(rec.durationMs), style = AgentText.Label)
        if (!ok && rec.error.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            BasicText(rec.error.take(80), style = AgentText.Label.copy(color = StatusFailed))
        }
    }
}

/** ✔ / ◼ / ◻ / ✕ / ⊘ glyph table — kept inline so the renderer is font-only. */
private fun statusIcon(s: TaskStatus): String = when (s) {
    TaskStatus.DONE -> "✔"
    TaskStatus.ACTIVE -> "◼"
    TaskStatus.PENDING -> "◻"
    TaskStatus.FAILED -> "✕"
    TaskStatus.CANCELLED -> "⊘"
}

/** Color table mirroring Claude Code's task indicator. */
private fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.DONE -> StatusDone
    TaskStatus.ACTIVE -> AgentColors.Accent
    TaskStatus.PENDING -> AgentColors.TextSecondary
    TaskStatus.FAILED -> StatusFailed
    TaskStatus.CANCELLED -> AgentColors.TextTertiary
}

private fun groupChipColor(s: TaskStatus): Color = when (s) {
    TaskStatus.DONE -> StatusDone
    TaskStatus.ACTIVE -> AgentColors.Accent
    TaskStatus.PENDING -> AgentColors.TextSecondary
    TaskStatus.FAILED -> StatusFailed
    TaskStatus.CANCELLED -> AgentColors.TextTertiary
}

private fun groupStatusLabel(s: TaskStatus): String = when (s) {
    TaskStatus.DONE -> "已完成"
    TaskStatus.ACTIVE -> "进行中"
    TaskStatus.PENDING -> "待开始"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}

/** "1.2s" / "350ms" / "1m 12s" — compact, agent-card-friendly. */
private fun formatDuration(ms: Long): String {
    if (ms < 0) return ""
    if (ms < 1_000) return "${ms}ms"
    val s = ms / 1_000.0
    if (s < 60) return "%.1fs".format(s)
    val m = (ms / 60_000)
    val rs = ((ms % 60_000) / 1_000)
    return "${m}m ${rs}s"
}

// Module-local color constants — green for DONE and red for FAILED. Kept private here
// (rather than added to AgentColors) so the rest of the UI doesn't pick them up by accident.
private val StatusDone = Color(0xFF2E7D32)
private val StatusFailed = Color(0xFFD32F2F)
