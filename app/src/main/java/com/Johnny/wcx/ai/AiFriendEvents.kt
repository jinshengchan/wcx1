// Adapted from AARCNoa/zhiyou, AiOverviewStore.kt at 91a3d19. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
internal data class AiFriendEvent(
    val title: String,
    val detail: String,
    val at: Long,
    val sourceId: Long,
    val read: Boolean = false,
) {
    fun recordedOn(day: LocalDate): Boolean = date(at) == day

    private fun date(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
}

internal object AiFriendEvents {
    private const val KEEP_MS = 180L * 24 * 60 * 60 * 1000
    private const val MAX_PER_FRIEND = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val punctuation = Regex("[\\p{P}\\p{Z}\\s]+")
    private var cachedAccount: String? = null
    private var cached = emptyMap<String, List<AiFriendEvent>>()

    private fun path(account: String) = AiProfileStore.rootFor(account).resolve("friend-events.json")

    fun overview(account: String): Map<String, List<AiFriendEvent>> = synchronized(AiProfileStore) {
        if (!current(account)) return@synchronized emptyMap()
        val stored = read(account).orEmpty()
        if (stored.isEmpty()) return@synchronized emptyMap()
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val profiles = AiProfileStore.loadIndex(account).profiles.mapTo(HashSet()) { it.wxId }
        val events = stored.filterKeys { it in profiles && AiAssistantTools.visible(it) }
            .mapValues { (_, list) -> list.filter { it.at >= cutoff } }.filterValues { it.isNotEmpty() }
        if (current(account)) events else emptyMap()
    }

    fun unreadTotal(account: String): Int = overview(account).values.sumOf { events -> events.count { !it.read } }

    fun recordedTitles(account: String, friend: String, day: LocalDate): List<String> = synchronized(AiProfileStore) {
        if (!current(account) || !AiAssistantTools.visible(friend)) return@synchronized emptyList()
        read(account)?.get(friend).orEmpty().asSequence().filter { it.recordedOn(day) }.take(30).map { it.title }.toList()
    }

    fun append(
        account: String,
        friend: String,
        sourceId: Long,
        title: String,
        detail: String,
        allowed: () -> Boolean,
    ): Unit = synchronized(AiProfileStore) {
        if (!current(account) || sourceId <= 0 || !AiAssistantTools.visible(friend) || !allowed()) return@synchronized
        val profile = AiProfileStore.readProfile(friend, account) ?: return@synchronized
        if (!profile.autoReplyEnabled || profile.friendEventBinding.isBlank() ||
            profile.friendEventBinding != profile.autoReplyBinding
        ) {
            return@synchronized
        }
        val shortTitle = title.trim().take(60)
        val key = normalize(shortTitle)
        if (key.isBlank()) return@synchronized
        val now = System.currentTimeMillis()
        val day = LocalDate.now()
        val previous = read(account) ?: return@synchronized
        val entries = previous[friend].orEmpty()
        if (entries.any { it.sourceId == sourceId || (it.recordedOn(day) && normalize(it.title) == key) }) return@synchronized
        val updated = (listOf(AiFriendEvent(shortTitle, detail.trim().take(240), now, sourceId)) + entries)
            .filter { it.at >= now - KEEP_MS }.take(MAX_PER_FRIEND)
        if (allowed() && current(account)) save(account, previous + (friend to updated))
    }

    fun markRead(account: String, friend: String, displayedSources: Set<Long>): Unit = synchronized(AiProfileStore) {
        if (!current(account) || !AiAssistantTools.visible(friend)) return@synchronized
        val previous = read(account) ?: return@synchronized
        val entries = previous[friend] ?: return@synchronized
        val updated = entries.map { event -> if (event.sourceId in displayedSources) event.copy(read = true) else event }
        if (updated != entries) save(account, previous + (friend to updated))
    }

    fun clear(account: String, friend: String): Boolean = synchronized(AiProfileStore) {
        if (!current(account) || !AiAssistantTools.visible(friend)) return@synchronized false
        val stored = read(account) ?: return@synchronized false
        friend !in stored || save(account, stored - friend)
    }

    /** Archive changes and event writes share the archive lock, so neither can resurrect deleted events. */
    fun updateArchiveRemovingEvents(account: String, friend: String, updateArchive: () -> Boolean): Boolean =
        synchronized(AiProfileStore) {
            if (!AiProfileStore.isCurrentAccount(account)) return@synchronized false
            val stored = read(account) ?: return@synchronized false
            if (friend !in stored) return@synchronized updateArchive()
            if (!save(account, stored - friend)) return@synchronized false
            if (updateArchive()) return@synchronized true
            save(account, stored) // Preserve events if the archive update cannot complete.
            false
        }

    private fun normalize(value: String) = value.lowercase().replace(punctuation, "")

    private fun current(account: String) = AiConfig.isMasterEnabled && AiProfileStore.isCurrentAccount(account)

    private fun read(account: String): Map<String, List<AiFriendEvent>>? {
        if (cachedAccount == account) return cached
        val file = path(account)
        val text = AiProfileStore.readText(file)
        val stored = if (text != null) {
            runCatching { json.decodeFromString<Map<String, List<AiFriendEvent>>>(text) }.getOrNull() ?: return null
        } else if (Files.notExists(file)) {
            emptyMap()
        } else {
            return null
        }
        if (!AiProfileStore.isCurrentAccount(account)) return null
        cached = stored
        cachedAccount = account
        return cached
    }

    private fun save(account: String, events: Map<String, List<AiFriendEvent>>): Boolean {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val retained = events.mapValues { (_, list) -> list.filter { it.at >= cutoff }.take(MAX_PER_FRIEND) }
            .filterValues { it.isNotEmpty() }
        if (!AiProfileStore.isCurrentAccount(account) ||
            !AiProfileStore.writeAtomic(path(account), json.encodeToString(retained))
        ) return false
        cached = retained
        cachedAccount = account
        return true
    }
}
