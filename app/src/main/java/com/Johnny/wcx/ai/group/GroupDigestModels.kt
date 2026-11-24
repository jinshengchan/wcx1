// Adapted from AARCNoa/zhiyou, GroupDigestModels.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import kotlinx.serialization.Serializable

internal data class GroupDigestSchedule(
    val groupId: String,
    val intervalMinutes: Int,
    val anchorAt: Long,
    val binding: String,
    private val lastAttemptedSlotEnd: Long,
    private val lastSlotEnd: Long,
) {
    fun dueSlotEnd(nowMs: Long): Long =
        dueSlotEndOf(anchorAt, intervalMinutes, lastAttemptedSlotEnd, lastSlotEnd, nowMs)
}

internal data class GroupDigestSummary(
    val groupId: String,
    val name: String,
    val avatarUrl: String,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val anchorAt: Long,
    val lastStatus: String,
    val lastError: String,
    val lastHeadline: String,
    val unread: Int,
)

@Serializable
data class GroupDigestConfig(
    val groupId: String,
    val name: String = "",
    val avatarUrl: String = "",
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val anchorAt: Long = 0,
    val lastSlotEnd: Long = 0,
    val lastStatus: String = "",
    val lastError: String = "",
    val failures: Int = 0,
    val digests: List<GroupDigest> = emptyList(),
    val scheduleBinding: String = "",
    internal val scheduleFrom: Long = 0,
    internal val lastAttemptedSlotEnd: Long = 0,
    internal val revision: String = "",
    internal val recordId: String = "",
) {
    internal val unread: Int get() = digests.count { !it.read }

    fun nextRunAt(nowMs: Long): Long = nextRunAtOf(anchorAt, intervalMinutes, nowMs)

    /** The stored schedule uses milliseconds. The database window uses [from, to) in seconds. */
    fun windowSec(nowMs: Long, manual: Boolean = true): Pair<Long, Long> {
        val fromMs = if (manual) {
            val start = lastSlotEnd.takeIf { it > 0L } ?: nowMs - intervalMillis(intervalMinutes)
            maxOf(start, nowMs - MAX_SPAN_MS)
        } else {
            maxOf(lastSlotEnd, scheduleFrom.takeIf { it > 0L } ?: anchorAt, nowMs - MAX_SPAN_MS)
        }
        // Round up the consent boundary so enabling a schedule mid-second cannot read earlier messages.
        return (if (manual) fromMs / 1000L else (fromMs + 999L) / 1000L) to nowMs / 1000L
    }

    internal fun trimmed(nowMs: Long): GroupDigestConfig {
        val kept = digests.filter { it.createdAt >= nowMs - KEEP_DAYS_MS }.takeLast(MAX_DIGESTS)
        return if (kept.size == digests.size) this else copy(digests = kept)
    }

    internal fun withDigest(digest: GroupDigest): GroupDigestConfig = copy(
        digests = (digests.filterNot { it.id == digest.id } +
            digest.copy(read = digests.firstOrNull { it.id == digest.id }?.read ?: false))
            .sortedBy { it.createdAt },
        lastSlotEnd = digest.toSec * 1000L,
        lastStatus = GroupDigestStatus.OK,
        lastError = "",
        failures = 0,
    )

    internal fun toSchedule(): GroupDigestSchedule? = if (enabled) GroupDigestSchedule(
        groupId, intervalMinutes, anchorAt, scheduleBinding, lastAttemptedSlotEnd, lastSlotEnd,
    ) else null

    internal fun toSummary() = GroupDigestSummary(
        groupId, name.ifBlank { groupId }, avatarUrl, enabled, intervalMinutes, anchorAt,
        lastStatus, lastError, digests.lastOrNull()?.headline.orEmpty(), unread,
    )
}

@Serializable
data class GroupDigest(
    val id: String,
    val fromSec: Long,
    val toSec: Long,
    val createdAt: Long,
    val headline: String,
    val points: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
    val messageCount: Int = 0,
    val manual: Boolean = false,
    val sampled: Boolean = false,
    // Old files predate unread indicators. Only newly generated summaries raise an alert.
    val read: Boolean = true,
)

object GroupDigestStatus {
    const val OK = "ok"
    const val EMPTY = "empty"
    const val FAILED = "failed"
    const val PAUSED = "paused"
    const val UNCONFIGURED = "unconfigured"
}

const val DEFAULT_INTERVAL_MINUTES = 1440
const val MIN_INTERVAL_MINUTES = 60
const val MAX_INTERVAL_MINUTES = 168 * 60
val PRESET_INTERVALS = intArrayOf(60, 360, 720, 1440, 2880, 4320)

const val MIN_TEXT_LINES = 3
private const val MAX_DIGESTS = 100
private const val KEEP_DAYS_MS = 90L * 24 * 3_600_000L
private const val MAX_SPAN_MS = 7L * 24 * 3_600_000L
internal const val MAX_MESSAGES_PER_RUN = 3000
internal const val MAX_CHUNK_LINES = 800
internal const val MAX_CHUNK_CHARS = 20_000
internal const val MAX_CHUNKS = 8
internal const val HEADLINE_MAX = 30
internal const val POINT_MAX = 40
internal const val MAX_POINTS = 6

fun nextRunAtOf(anchorAt: Long, intervalMinutes: Int, nowMs: Long): Long {
    if (anchorAt <= 0L) return 0L
    if (nowMs < anchorAt) return anchorAt
    val interval = intervalMillis(intervalMinutes)
    return anchorAt + ((nowMs - anchorAt) / interval + 1) * interval
}

private fun dueSlotEndOf(
    anchorAt: Long,
    intervalMinutes: Int,
    lastAttemptedSlotEnd: Long,
    lastSlotEnd: Long,
    nowMs: Long,
): Long {
    if (anchorAt <= 0L) return 0L
    val interval = intervalMillis(intervalMinutes)
    val elapsed = nowMs - anchorAt
    if (elapsed < interval) return 0L
    val slotEnd = anchorAt + (elapsed / interval) * interval
    return slotEnd.takeIf { it > lastAttemptedSlotEnd && it / 1000L * 1000L > lastSlotEnd } ?: 0L
}

private fun intervalMillis(minutes: Int): Long =
    minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L
