/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.mozilla.reference.browser.agent.core.TaskGroup
import org.mozilla.reference.browser.agent.core.TaskItem
import org.mozilla.reference.browser.agent.core.TaskStatus
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Minimalist task list rendered inline in the chat transcript — deliberately NOT a card.
 * No border, no background, no dots/spheres, no chevron, no accent colors: it reads as a flat
 * Claude-Code-style todo list that scrolls with the conversation, matching the app's plain
 * black/white GPT look.
 *
 * Per group the list is ordered current-first so it reads like a todo list, and finished tasks
 * collapse so the list never grows without bound (the whole point — tool calls already show in
 * the chat stream above, so they are NOT repeated here):
 *  - ACTIVE   "▸ <title>"  black, bold (highlighted)
 *  - PENDING  "  <title>"  dark gray
 *  - terminal "✔ <title>"  light gray + strikethrough (only the most recent one is shown)
 *  - "… +N 已完成"          light gray summary when more than one task is finished
 *
 * Driven off state.tracker (Compose mutableStateOf<TaskTrackerState>); every
 * TaskTrackerManager.mutate reassigns the slot so the list recomposes automatically.
 */
@Composable
fun TaskTrackerCard(state: PanelState) {
    val tracker = state.tracker
    if (tracker.groups.isEmpty()) return
    val multiGroup = tracker.groups.size > 1
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        tracker.groups.forEachIndexed { index, group ->
            if (group.tasks.isEmpty()) return@forEachIndexed
            if (index > 0) Spacer(Modifier.height(6.dp))
            if (multiGroup && group.title.isNotBlank()) {
                BasicText(group.title, style = AgentText.Label)
                Spacer(Modifier.height(2.dp))
            }
            GroupList(group)
        }
    }
}

@Composable
private fun GroupList(group: TaskGroup) {
    val active = group.tasks.filter { it.status == TaskStatus.ACTIVE }
    val pending = group.tasks.filter { it.status == TaskStatus.PENDING }
    val terminal = group.tasks.filter {
        it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED
    }
    Column(Modifier.fillMaxWidth()) {
        active.forEach { TaskLine(it) }
        pending.forEach { TaskLine(it) }
        // Finished tasks collapse: show only the most recent one, fold the rest into a count
        // line so a long run of completed steps doesn't keep stretching the conversation.
        terminal.lastOrNull()?.let { TaskLine(it) }
        if (terminal.size > 1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Spacer(Modifier.width(MARKER_WIDTH))
                BasicText("… +${terminal.size - 1} 已完成", style = AgentText.Label)
            }
        }
    }
}

@Composable
private fun TaskLine(task: TaskItem) {
    val terminal = task.status == TaskStatus.DONE ||
        task.status == TaskStatus.FAILED ||
        task.status == TaskStatus.CANCELLED
    val color = when (task.status) {
        TaskStatus.ACTIVE -> AgentColors.TextPrimary
        TaskStatus.PENDING -> AgentColors.TextSecondary
        else -> AgentColors.TextTertiary
    }
    // PENDING = hollow square, ACTIVE = filled square (a "drawn" checkbox that fills in once a
    // task starts); terminal states use a glyph (✔ done / ✕ failed / – cancelled).
    val terminalMarker = when (task.status) {
        TaskStatus.DONE -> "✔"
        TaskStatus.FAILED -> "✕"
        TaskStatus.CANCELLED -> "–"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(MARKER_WIDTH)) {
            when (task.status) {
                TaskStatus.PENDING ->
                    Box(Modifier.padding(top = 3.dp).size(SQUARE).border(1.5.dp, AgentColors.TextSecondary))
                TaskStatus.ACTIVE ->
                    Box(Modifier.padding(top = 3.dp).size(SQUARE).background(AgentColors.TextPrimary))
                else ->
                    BasicText(terminalMarker, style = AgentText.Body.copy(color = color))
            }
        }
        BasicText(
            task.title,
            style = AgentText.Body.copy(
                color = color,
                fontWeight = if (task.status == TaskStatus.ACTIVE) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (terminal) TextDecoration.LineThrough else null,
            ),
        )
    }
}

private val MARKER_WIDTH = 18.dp
private val SQUARE = 10.dp
