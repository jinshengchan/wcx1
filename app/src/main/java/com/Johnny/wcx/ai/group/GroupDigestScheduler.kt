// Adapted from AARCNoa/zhiyou, GroupDigestScheduler.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Timers live in WeChat's main process. Reopening it checks the persisted schedule for catch-up. */
object GroupDigestScheduler {
    private const val MAX_CHECK_DELAY_MS = 15_000L
    private val purpose = AiConfig.Purpose.GROUP_DIGEST
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var controller: Job? = null

    @Synchronized
    internal fun onEnable(wakeAt: (Long?) -> Unit = {}) {
        if (controller?.isActive == true) return
        controller = scope.launch {
            while (isActive) {
                val waitMs = checkSchedules(this, wakeAt)
                withTimeoutOrNull(waitMs) { changed.receive() }
            }
        }
    }

    @Synchronized
    fun onDisable() {
        controller?.cancel()
        controller = null
    }

    fun schedule(groupId: String, account: String? = GroupDigestStore.accountId()) {
        if (account != null && groupId.isNotBlank()) wake()
    }

    internal fun wake() {
        changed.trySend(Unit)
    }

    fun cancel(groupId: String, account: String? = GroupDigestStore.accountId()) {
        GroupDigestRunner.cancelAutomatic(groupId, account)
        schedule(groupId, account)
    }

    private fun checkSchedules(scope: CoroutineScope, wakeAt: (Long?) -> Unit): Long {
        if (!AiConfig.isMasterEnabled) {
            wakeAt(null)
            return MAX_CHECK_DELAY_MS
        }
        val account = GroupDigestStore.accountId()
        GroupDigestRunner.cancelOtherAccounts(account)
        if (account == null) {
            wakeAt(null)
            return MAX_CHECK_DELAY_MS
        }

        val now = System.currentTimeMillis()
        val binding = AiConfig.bindingId(purpose)
        var nextWake: Long? = null
        for (group in GroupDigestStore.loadSchedules(account)) {
            if (binding == null || binding != group.binding) {
                GroupDigestStore.update(group.groupId, account) { current ->
                    val selected = AiConfig.bindingId(purpose)
                    if (current.enabled && (selected == null || current.scheduleBinding != selected)) {
                        current.copy(
                            enabled = false,
                            lastStatus = GroupDigestStatus.UNCONFIGURED,
                            lastError = GroupDigestRunner.BINDING_CHANGED,
                        )
                    } else {
                        current
                    }
                }
                continue
            }
            if (group.dueSlotEnd(now) > 0L && !AiRunState.hasLongTask() &&
                !GroupDigestRunner.hasAutomaticRun() && !GroupDigestRunner.isRunning(group.groupId, account)
            ) {
                GroupDigestRunner.startAutomatic(scope, group.groupId, account)
            }
            val next = nextRunAtOf(group.anchorAt, group.intervalMinutes, now)
            if (next > now) nextWake = minOf(nextWake ?: next, next)
        }
        wakeAt(nextWake)
        return minOf(nextWake?.minus(now) ?: MAX_CHECK_DELAY_MS, MAX_CHECK_DELAY_MS)
    }
}
