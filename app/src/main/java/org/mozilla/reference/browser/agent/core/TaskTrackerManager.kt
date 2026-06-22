/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import java.util.UUID

/**
 * Single source of truth for Visual Task Tracker mutations.
 *
 * Wraps a [TaskTrackerState] snapshot accessor + setter pair (typically backed by
 * `PanelState.tracker`) so mutations:
 *   - run on whatever thread the caller is on (the AgentEngine bridge always runs us on
 *     the main thread via runOnMainResult so Compose state writes are safe),
 *   - automatically fill in createdAt/startedAt/finishedAt/durationMs timestamps,
 *   - automatically maintain [TaskTrackerState.currentTaskId] (latest ACTIVE task),
 *   - automatically roll up a group's status (ACTIVE if any task is ACTIVE; DONE only when
 *     every task is terminal and at least one is DONE; FAILED if all terminal tasks failed),
 *   - emit a fresh copy() so the mutable state slot triggers Compose recomposition,
 *   - hand off to [persist] (a thin "flush to disk" callback) without knowing how the host
 *     persists (PanelState.saveCurrentChat in production; no-op in tests).
 *
 * The 9 `agent_tasks_*` tools live in AgentTools and forward to this class via the
 * AgentPanelBridge interface, so the model never touches Compose state directly.
 */
class TaskTrackerManager(
    private val read: () -> TaskTrackerState,
    private val write: (TaskTrackerState) -> Unit,
    private val persist: () -> Unit = {},
) {
    // --------------------------------------------------------------------- group / task CRUD

    fun createGroup(title: String): String {
        val cleaned = title.trim().take(80).ifBlank { "任务组" }
        val id = newId()
        mutate { s ->
            s.copy(groups = s.groups + TaskGroup(id = id, title = cleaned, status = TaskStatus.ACTIVE))
        }
        return id
    }

    /** Returns null if [groupId] doesn't exist or if [title] is blank. */
    fun addTask(groupId: String, title: String, description: String = ""): String? {
        val cleanedTitle = title.trim().take(120)
        if (cleanedTitle.isBlank()) return null
        val cleanedDesc = description.trim().take(400)
        val id = newId()
        val applied = mutate { s ->
            val gi = s.groups.indexOfFirst { it.id == groupId }
            if (gi < 0) return@mutate s
            val task = TaskItem(id = id, title = cleanedTitle, description = cleanedDesc, status = TaskStatus.PENDING)
            val groups = s.groups.toMutableList()
            groups[gi] = groups[gi].copy(tasks = groups[gi].tasks + task)
            s.copy(groups = groups)
        }
        return if (applied) id else null
    }

    fun startTask(taskId: String): Boolean = transition(taskId, TaskStatus.ACTIVE)

    fun completeTask(taskId: String): Boolean = transition(taskId, TaskStatus.DONE)

    fun failTask(taskId: String, error: String): Boolean = transition(taskId, TaskStatus.FAILED, error)

    fun cancelTask(taskId: String): Boolean = transition(taskId, TaskStatus.CANCELLED)

    fun updateTask(taskId: String, title: String?, description: String?): Boolean = mutate { s ->
        val loc = locate(s, taskId) ?: return@mutate s
        val (gi, ti) = loc
        val task = s.groups[gi].tasks[ti]
        val newTitle = title?.trim()?.take(120)?.ifBlank { null } ?: task.title
        val newDesc = description?.trim()?.take(400) ?: task.description
        if (newTitle == task.title && newDesc == task.description) return@mutate s
        val groups = s.groups.toMutableList()
        val tasks = groups[gi].tasks.toMutableList()
        tasks[ti] = task.copy(title = newTitle, description = newDesc)
        groups[gi] = groups[gi].copy(tasks = tasks)
        s.copy(groups = groups)
    }

    /** Removes a single task by id. Returns true iff the task existed and was removed. */
    fun deleteTask(taskId: String): Boolean = mutate { s ->
        val loc = locate(s, taskId) ?: return@mutate s
        val (gi, ti) = loc
        val groups = s.groups.toMutableList()
        val tasks = groups[gi].tasks.toMutableList()
        tasks.removeAt(ti)
        groups[gi] = groups[gi].copy(tasks = tasks)
        s.copy(groups = groups)
    }

    fun clearAll(): Boolean = mutate { _ ->
        TaskTrackerState(isExpanded = true)
    }

    // --------------------------------------------------------------------- linking & UI flags

    fun recordToolCall(
        taskId: String,
        name: String,
        status: String,
        durationMs: Long,
        error: String = "",
    ): Boolean = mutate { s ->
        val loc = locate(s, taskId) ?: return@mutate s
        val (gi, ti) = loc
        val task = s.groups[gi].tasks[ti]
        val rec = TaskToolCallRecord(name = name, status = status, durationMs = durationMs, error = error)
        // Keep at most 25 tool-call rows per task — enough for human review, bounded to
        // avoid runaway growth on long-running tasks with many small page_query calls.
        val combined = (task.toolCalls + rec).takeLast(25)
        val groups = s.groups.toMutableList()
        val tasks = groups[gi].tasks.toMutableList()
        tasks[ti] = task.copy(toolCalls = combined)
        groups[gi] = groups[gi].copy(tasks = tasks)
        s.copy(groups = groups)
    }

    /** Sets [TaskTrackerState.isExpanded]. */
    fun setExpanded(expanded: Boolean): Boolean = mutate { s ->
        if (s.isExpanded == expanded) s else s.copy(isExpanded = expanded)
    }

    fun toggleExpanded(): Boolean = mutate { s -> s.copy(isExpanded = !s.isExpanded) }

    // --------------------------------------------------------------------- internals

    /**
     * Core mutator. [transform] receives the current state and returns the next one (or the
     * same instance to no-op). Wraps the result with auto-derived currentTaskId / group
     * status / lastUpdated, writes it back, then persists. Returns true iff something
     * actually changed.
     */
    private fun mutate(transform: (TaskTrackerState) -> TaskTrackerState): Boolean {
        val before = read()
        val raw = transform(before)
        if (raw === before) return false
        val rolled = rollUp(raw)
        if (rolled === before) return false
        write(rolled)
        persist()
        return true
    }

    /**
     * Force [taskId] to [target] status. Fills timestamps / durationMs / clearing of error
     * field on revert. Returns true iff the task existed and the status actually changed.
     */
    private fun transition(taskId: String, target: TaskStatus, error: String = ""): Boolean =
        mutate { s ->
            val loc = locate(s, taskId) ?: return@mutate s
            val (gi, ti) = loc
            val task = s.groups[gi].tasks[ti]
            if (task.status == target && (target != TaskStatus.FAILED || task.error == error)) return@mutate s
            val now = System.currentTimeMillis()
            val newTask = when (target) {
                TaskStatus.ACTIVE -> task.copy(
                    status = TaskStatus.ACTIVE,
                    startedAt = task.startedAt ?: now,
                    finishedAt = null,
                    durationMs = null,
                    error = "",
                )
                TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                    val started = task.startedAt ?: task.createdAt
                    task.copy(
                        status = target,
                        startedAt = task.startedAt ?: now,
                        finishedAt = now,
                        durationMs = (now - started).coerceAtLeast(0L),
                        error = if (target == TaskStatus.FAILED) error.trim().take(500) else "",
                    )
                }
                TaskStatus.PENDING -> task.copy(
                    status = TaskStatus.PENDING,
                    startedAt = null,
                    finishedAt = null,
                    durationMs = null,
                    error = "",
                )
            }
            val groups = s.groups.toMutableList()
            val tasks = groups[gi].tasks.toMutableList()
            tasks[ti] = newTask
            groups[gi] = groups[gi].copy(tasks = tasks)
            s.copy(groups = groups)
        }

    /**
     * After every mutation, derive (a) per-group status from its tasks, (b) state.currentTaskId
     * from the latest ACTIVE task, (c) state.lastUpdated. Pure function.
     */
    private fun rollUp(s: TaskTrackerState): TaskTrackerState {
        val groups = s.groups.map { g ->
            if (g.tasks.isEmpty()) {
                if (g.status == TaskStatus.ACTIVE) g else g.copy(status = TaskStatus.ACTIVE)
            } else {
                val gs = deriveGroupStatus(g.tasks)
                if (g.status == gs) g else g.copy(status = gs)
            }
        }
        // currentTaskId = latest ACTIVE task across all groups, falling back to last PENDING
        // (so UI's "Working on" line still has something to show right after start).
        val flat = groups.flatMap { it.tasks }
        val active = flat.lastOrNull { it.status == TaskStatus.ACTIVE }
        val fallback = flat.lastOrNull { it.status == TaskStatus.PENDING }
        val nextCurrent = active?.id ?: fallback?.id
        return s.copy(
            groups = groups,
            currentTaskId = nextCurrent,
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private fun deriveGroupStatus(tasks: List<TaskItem>): TaskStatus {
        if (tasks.any { it.status == TaskStatus.ACTIVE }) return TaskStatus.ACTIVE
        if (tasks.any { it.status == TaskStatus.PENDING }) return TaskStatus.ACTIVE
        val terminal = tasks.filter {
            it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED
        }
        if (terminal.isEmpty()) return TaskStatus.ACTIVE
        if (terminal.any { it.status == TaskStatus.DONE }) {
            return if (terminal.any { it.status == TaskStatus.FAILED }) TaskStatus.FAILED else TaskStatus.DONE
        }
        if (terminal.all { it.status == TaskStatus.FAILED }) return TaskStatus.FAILED
        if (terminal.all { it.status == TaskStatus.CANCELLED }) return TaskStatus.CANCELLED
        return TaskStatus.ACTIVE
    }

    private fun locate(s: TaskTrackerState, taskId: String): Pair<Int, Int>? {
        s.groups.forEachIndexed { gi, g ->
            g.tasks.forEachIndexed { ti, t ->
                if (t.id == taskId) return gi to ti
            }
        }
        return null
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)
}

/**
 * Render the tracker state into the compact summary block requested by the spec:
 * Completed / In Progress / Remaining / Files / Next Step. Returns "" when the state
 * is empty (no need to dilute the summary header).
 */
fun TaskTrackerState.compactSummary(): String {
    if (isEmpty) return ""
    val sb = StringBuilder()
    sb.append("## 任务追踪摘要\n\n")

    val flat = allTasks
    val completed = flat.filter { it.status == TaskStatus.DONE }
    val active = flat.filter { it.status == TaskStatus.ACTIVE }
    val pending = flat.filter { it.status == TaskStatus.PENDING }
    val failed = flat.filter { it.status == TaskStatus.FAILED }

    if (completed.isNotEmpty()) {
        sb.append("Completed\n")
        completed.forEach { sb.append("✔ ${it.title}\n") }
        sb.append('\n')
    }
    if (active.isNotEmpty()) {
        sb.append("In Progress\n")
        active.forEach { sb.append("◼ ${it.title}\n") }
        sb.append('\n')
    }
    if (pending.isNotEmpty()) {
        sb.append("Remaining\n")
        pending.forEach { sb.append("◻ ${it.title}\n") }
        sb.append('\n')
    }
    if (failed.isNotEmpty()) {
        sb.append("Failed\n")
        failed.forEach { sb.append("✕ ${it.title}${if (it.error.isNotBlank()) " — ${it.error}" else ""}\n") }
        sb.append('\n')
    }

    // Files touched: pull unique file-path arguments out of any tool-call records. Cheap
    // heuristic — tool names containing "file"/"write"/"read" usually take a "path" arg
    // but we don't have args here, so just list the tools that ran instead.
    val tools = flat.flatMap { it.toolCalls }.map { it.name }.toSet()
    if (tools.isNotEmpty()) {
        sb.append("Tools\n")
        tools.sorted().forEach { sb.append("- $it\n") }
        sb.append('\n')
    }

    val next = pending.firstOrNull() ?: active.firstOrNull()
    if (next != null) {
        sb.append("Next Step\n")
        sb.append("→ ${next.title}\n")
        if (next.description.isNotBlank()) sb.append("  ${next.description}\n")
    }
    return sb.toString().trimEnd()
}
