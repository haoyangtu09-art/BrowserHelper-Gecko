/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual Task Tracking System — pure data model.
 *
 * One [TaskTrackerState] lives per chat (persisted alongside the chat transcript) and is shown
 * inline in the chat stream as a Claude-Code-style card: collapsed = "Working on: X / +N more";
 * expanded = group sections with status icons + per-task durations + tool-call sub-items.
 *
 * All classes are immutable; [TaskTrackerManager] produces new copies and reassigns the
 * mutable PanelState slot to trigger Compose recomposition.
 */

enum class TaskStatus { PENDING, ACTIVE, DONE, FAILED, CANCELLED }

/** One tool invocation recorded under a task while it was the current task. */
data class TaskToolCallRecord(
    val name: String,
    val status: String,
    val durationMs: Long,
    val error: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("status", status)
        .put("durationMs", durationMs)
        .put("error", error)
        .put("timestamp", timestamp)

    companion object {
        fun fromJson(o: JSONObject): TaskToolCallRecord = TaskToolCallRecord(
            name = o.optString("name"),
            status = o.optString("status"),
            durationMs = o.optLong("durationMs", 0L),
            error = o.optString("error"),
            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

/** One trackable task. Timing fields are filled in by [TaskTrackerManager] transitions. */
data class TaskItem(
    val id: String,
    val title: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String = "",
    val toolCalls: List<TaskToolCallRecord> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("description", description)
            .put("status", status.name)
            .put("createdAt", createdAt)
        startedAt?.let { o.put("startedAt", it) }
        finishedAt?.let { o.put("finishedAt", it) }
        durationMs?.let { o.put("durationMs", it) }
        if (error.isNotEmpty()) o.put("error", error)
        if (toolCalls.isNotEmpty()) {
            o.put("toolCalls", JSONArray().also { arr -> toolCalls.forEach { arr.put(it.toJson()) } })
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TaskItem {
            val toolCalls = ArrayList<TaskToolCallRecord>()
            o.optJSONArray("toolCalls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { toolCalls.add(TaskToolCallRecord.fromJson(it)) }
                }
            }
            return TaskItem(
                id = o.optString("id"),
                title = o.optString("title"),
                description = o.optString("description"),
                status = parseStatus(o.optString("status"), TaskStatus.PENDING),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                startedAt = if (o.has("startedAt")) o.optLong("startedAt") else null,
                finishedAt = if (o.has("finishedAt")) o.optLong("finishedAt") else null,
                durationMs = if (o.has("durationMs")) o.optLong("durationMs") else null,
                error = o.optString("error"),
                toolCalls = toolCalls,
            )
        }
    }
}

/** A logical grouping of tasks (e.g. "Phase 1 — Core" / "Validation"). */
data class TaskGroup(
    val id: String,
    val title: String,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val tasks: List<TaskItem> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("status", status.name)
        .put("createdAt", createdAt)
        .put("tasks", JSONArray().also { arr -> tasks.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): TaskGroup {
            val tasks = ArrayList<TaskItem>()
            o.optJSONArray("tasks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { tasks.add(TaskItem.fromJson(it)) }
                }
            }
            return TaskGroup(
                id = o.optString("id"),
                title = o.optString("title"),
                status = parseStatus(o.optString("status"), TaskStatus.ACTIVE),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                tasks = tasks,
            )
        }
    }
}

/** Whole tracker for one chat. */
data class TaskTrackerState(
    val currentTaskId: String? = null,
    val groups: List<TaskGroup> = emptyList(),
    val isExpanded: Boolean = true,
    val lastUpdated: Long = 0L,
) {
    val isEmpty: Boolean get() = groups.isEmpty() || groups.all { it.tasks.isEmpty() }

    val allTasks: List<TaskItem> get() = groups.flatMap { it.tasks }

    /** True when the tracker has tasks and every one of them has reached a terminal state — i.e.
     *  the plan is finished. The next user turn clears a finished tracker (see PanelState.send). */
    val allComplete: Boolean get() = !isEmpty && allTasks.all {
        it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED
    }

    fun task(id: String): TaskItem? {
        for (g in groups) for (t in g.tasks) if (t.id == id) return t
        return null
    }

    fun currentTask(): TaskItem? = currentTaskId?.let { task(it) }

    /** Counts {done, active, pending, failed, cancelled}. */
    fun counts(): IntArray {
        var done = 0; var active = 0; var pending = 0; var failed = 0; var cancelled = 0
        for (g in groups) for (t in g.tasks) when (t.status) {
            TaskStatus.DONE -> done++
            TaskStatus.ACTIVE -> active++
            TaskStatus.PENDING -> pending++
            TaskStatus.FAILED -> failed++
            TaskStatus.CANCELLED -> cancelled++
        }
        return intArrayOf(done, active, pending, failed, cancelled)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("isExpanded", isExpanded)
            .put("lastUpdated", lastUpdated)
            .put("groups", JSONArray().also { arr -> groups.forEach { arr.put(it.toJson()) } })
        currentTaskId?.let { o.put("currentTaskId", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): TaskTrackerState {
            if (o == null) return TaskTrackerState()
            val groups = ArrayList<TaskGroup>()
            o.optJSONArray("groups")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { groups.add(TaskGroup.fromJson(it)) }
                }
            }
            return TaskTrackerState(
                currentTaskId = o.optString("currentTaskId").ifBlank { null },
                groups = groups,
                isExpanded = o.optBoolean("isExpanded", true),
                lastUpdated = o.optLong("lastUpdated", 0L),
            )
        }
    }
}

private fun parseStatus(raw: String?, fallback: TaskStatus): TaskStatus =
    try {
        if (raw.isNullOrBlank()) fallback else TaskStatus.valueOf(raw)
    } catch (_: Throwable) {
        fallback
    }
