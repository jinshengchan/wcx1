// Adapted from AARCNoa/zhiyou, GroupDigestStore.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.AiProfileStore
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.div
import kotlin.io.path.exists

/** One atomic file per group. Scheduling metadata is derived from those files and cached in memory. */
object GroupDigestStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val groupIdPattern = Regex("[A-Za-z0-9_.@-]+@chatroom")
    private var indexAccount: String? = null
    private val schedules = mutableMapOf<String, GroupDigestSchedule>()

    fun accountId(): String? = AiProfileStore.accountId()

    @Synchronized
    internal fun loadSchedules(account: String): List<GroupDigestSchedule> {
        if (account != accountId()) return emptyList()
        if (indexAccount == account) return schedules.values.toList()
        val files = directory(account).toFile().listFiles().orEmpty()
        val enabled = files.asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                val groupId = file.name.removeSuffix(".json")
                if (groupIdPattern.matches(groupId)) readFile(file.toPath(), groupId)?.toSchedule() else null
            }
            .sortedBy { it.groupId }
            .toList()
        if (account != accountId()) return emptyList()
        schedules.clear()
        enabled.associateByTo(schedules) { it.groupId }
        indexAccount = account
        return enabled
    }

    @Synchronized
    fun readConfig(groupId: String, account: String? = accountId()): GroupDigestConfig? {
        val file = configFile(groupId, account) ?: return null
        return readFile(file, groupId)?.takeIf { account == accountId() }
    }

    /** Adds a group once. An existing group keeps its schedule and history. */
    @Synchronized
    fun saveConfig(config: GroupDigestConfig, account: String? = accountId()): Boolean {
        val file = configFile(config.groupId, account) ?: return false
        if (file.exists()) return readFile(file, config.groupId) != null
        return save(file, requireNotNull(account), null, config) != null
    }

    @Synchronized
    fun update(
        groupId: String,
        account: String? = accountId(),
        block: (GroupDigestConfig) -> GroupDigestConfig,
    ): GroupDigestConfig? {
        val file = configFile(groupId, account) ?: return null
        val current = readFile(file, groupId) ?: return null
        val next = block(current)
        if (next.groupId != groupId) return null
        if (next == current) return next.takeIf { account == accountId() }
        return save(file, requireNotNull(account), current, next)
    }

    @Synchronized
    fun deleteConfig(groupId: String, account: String? = accountId()): Boolean {
        val file = configFile(groupId, account) ?: return false
        if (!AiProfileStore.delete(file)) return false
        if (account == indexAccount) schedules.remove(groupId)
        GroupDigestRunner.cancel(groupId, account)
        GroupDigestScheduler.schedule(groupId, account)
        return true
    }

    private fun directory(account: String): Path = AiProfileStore.rootFor(account) / "groups"

    private fun configFile(groupId: String, account: String?): Path? =
        account?.takeIf { groupIdPattern.matches(groupId) && it == accountId() }?.let { directory(it) / "$groupId.json" }

    private fun readFile(file: Path, groupId: String): GroupDigestConfig? {
        val text = AiProfileStore.readText(file) ?: return null
        return runCatching { json.decodeFromString<GroupDigestConfig>(text) }.getOrNull()
            ?.takeIf { it.groupId == groupId }
    }

    private fun save(
        file: Path,
        account: String,
        current: GroupDigestConfig?,
        requested: GroupDigestConfig,
    ): GroupDigestConfig? {
        val now = System.currentTimeMillis()
        val consentChanged = requested.enabled &&
            (current?.enabled != true || current.scheduleBinding != requested.scheduleBinding)
        val scheduleChanged = current == null || current.enabled != requested.enabled ||
            current.anchorAt != requested.anchorAt || current.intervalMinutes != requested.intervalMinutes ||
            current.scheduleBinding != requested.scheduleBinding
        val next = requested.copy(
            revision = if (scheduleChanged) UUID.randomUUID().toString() else current.revision,
            recordId = current?.recordId ?: UUID.randomUUID().toString(),
            anchorAt = if (consentChanged) now else requested.anchorAt,
            scheduleFrom = if (consentChanged) now else current?.scheduleFrom ?: 0L,
            lastAttemptedSlotEnd = if (scheduleChanged) 0L else requested.lastAttemptedSlotEnd,
            lastStatus = if (consentChanged) "" else requested.lastStatus,
            lastError = if (consentChanged) "" else requested.lastError,
            failures = if (consentChanged) 0 else requested.failures,
        ).trimmed(now)
        val text = runCatching { json.encodeToString(next) }.getOrNull() ?: return null
        if (!AiProfileStore.writeAtomic(file, text)) return null
        if (account == indexAccount) {
            val schedule = next.toSchedule()
            if (schedule == null) schedules.remove(next.groupId) else schedules[next.groupId] = schedule
        }
        if (scheduleChanged) {
            if (current != null) GroupDigestRunner.cancelAutomatic(next.groupId, account)
            GroupDigestScheduler.schedule(next.groupId, account)
        }
        return next
    }
}
