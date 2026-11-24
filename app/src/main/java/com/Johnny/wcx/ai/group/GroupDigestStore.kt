// Adapted from AARCNoa/zhiyou, GroupDigestStore.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.AiProfileStore
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.div
import kotlin.io.path.exists

/** One atomic file per group, with an in-memory index for scheduling and displaying groups. */
object GroupDigestStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val groupIdPattern = Regex("[A-Za-z0-9_.@-]+@chatroom")
    private var indexAccount: String? = null
    private val schedules = mutableMapOf<String, GroupDigestSchedule>()
    private val groups = mutableMapOf<String, GroupDigestSummary>()

    fun accountId(): String? = AiProfileStore.accountId()

    @Synchronized
    internal fun loadSchedules(account: String): List<GroupDigestSchedule> {
        return if (ensureIndex(account)) schedules.values.toList() else emptyList()
    }

    @Synchronized
    internal fun loadGroups(account: String): List<GroupDigestSummary> {
        return if (ensureIndex(account)) groups.values.sortedBy { it.name } else emptyList()
    }

    @Synchronized
    internal fun unreadTotal(account: String): Int =
        if (ensureIndex(account)) groups.values.sumOf { it.unread } else 0

    private fun ensureIndex(account: String): Boolean {
        if (account != accountId()) return false
        if (indexAccount == account) return true
        val files = directory(account).toFile().listFiles().orEmpty()
        val configs = files.asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                val groupId = file.name.removeSuffix(".json")
                if (groupIdPattern.matches(groupId)) readFile(file.toPath(), groupId) else null
            }
            .sortedBy { it.groupId }
            .toList()
        if (account != accountId()) return false
        schedules.clear()
        groups.clear()
        configs.forEach(::index)
        indexAccount = account
        return true
    }

    @Synchronized
    fun readConfig(groupId: String, account: String? = accountId()): GroupDigestConfig? {
        val file = configFile(groupId, account) ?: return null
        return readFile(file, groupId)?.takeIf { account == accountId() }
    }

    internal fun markRead(groupId: String, account: String): GroupDigestConfig? =
        update(groupId, account) { current ->
            if (current.unread == 0) current
            else current.copy(digests = current.digests.map { if (it.read) it else it.copy(read = true) })
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
        if (account == indexAccount) {
            schedules.remove(groupId)
            groups.remove(groupId)
        }
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

    private fun index(config: GroupDigestConfig) {
        val schedule = config.toSchedule()
        if (schedule == null) schedules.remove(config.groupId) else schedules[config.groupId] = schedule
        groups[config.groupId] = config.toSummary()
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
        if (account == indexAccount) index(next)
        if (scheduleChanged) {
            if (current != null) GroupDigestRunner.cancelAutomatic(next.groupId, account)
            GroupDigestScheduler.schedule(next.groupId, account)
        }
        return next
    }
}
