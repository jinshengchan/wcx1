// Adapted from AARCNoa/zhiyou, AiAutoIncrementalScheduler.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class AiAutoIncrementalSchedule(
    val minuteOfDay: Int,
    val binding: String,
    val enabledAt: Long,
    val lastAttemptAt: Long = 0,
) {
    private fun slot(day: LocalDate, zone: ZoneId): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        return day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
    }

    internal fun dueAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return slot(today, zone).takeIf { it <= now && it > enabledAt && it > lastAttemptAt }
    }

    internal fun nextAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todaySlot = slot(today, zone)
        return if (todaySlot > now) todaySlot else slot(today.plusDays(1), zone)
    }

    companion object {
        const val DEFAULT_MINUTE = 9 * 60
    }
}

/** Runs in WeChat's main process and catches up today's eligible slot after startup. */
object AiAutoIncrementalScheduler {
    private const val MAX_CHECK_DELAY_MS = 15_000L
    private val purpose = AiConfig.Purpose.FRIEND_PROFILE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var controller: Job? = null

    @Synchronized
    internal fun onEnable(wakeAt: (Long?) -> Unit = {}) {
        if (controller?.isActive == true) return
        controller = scope.launch {
            var recoveredAccount: String? = null
            while (isActive) {
                val account = AiProfileStore.accountId()
                if (account != recoveredAccount) {
                    AiProfileInitializer.recoverInterrupted(account)
                    recoveredAccount = account
                }
                val now = System.currentTimeMillis()
                val nextWake = account?.let { checkSchedules(it, now) }
                wakeAt(nextWake)
                val waitMs = minOf(nextWake?.minus(now) ?: MAX_CHECK_DELAY_MS, MAX_CHECK_DELAY_MS)
                withTimeoutOrNull(waitMs) { changed.receive() }
            }
        }
    }

    @Synchronized
    fun onDisable() {
        controller?.cancel()
        controller = null
    }

    internal fun wake() {
        changed.trySend(Unit)
    }

    private fun checkSchedules(account: String, now: Long): Long? {
        val selected = AiConfig.selection(purpose)?.takeIf { it.account == account }
        var nextWake: Long? = null
        var busy = AiRunState.hasLongTask()
        for (profile in AiProfileStore.loadIndex(account).profiles) {
            val schedule = profile.autoIncremental ?: continue
            if (selected == null || schedule.binding != selected.id) {
                AiProfileStore.patchProfile(profile.wxId, account) { current ->
                    val latest = AiConfig.selection(purpose)?.takeIf { it.account == account }
                    if (current.autoIncremental == schedule && schedule.binding != latest?.id) {
                        current.copy(autoIncremental = null)
                    } else {
                        current
                    }
                }
                continue
            }
            if (!busy && profile.state == AiProfileState.READY && !profile.auditing && schedule.dueAt(now) != null) {
                busy = AiProfileInitializer.startAutomatic(profile.wxId, account, schedule)
            }
            val next = schedule.nextAt(now)
            nextWake = minOf(nextWake ?: next, next)
        }
        return nextWake
    }
}
