// Adapted from AARCNoa/zhiyou, AiProfileStore.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.utils.HostInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object AiProfileStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val privateRoot: Path get() = HostInfo.application.noBackupFilesDir.toPath() / "wcx_ai"
    private val summaryOrder = compareBy<AiProfileSummary>({ it.pyInitial }, { it.sortKey })
    private var indexedAccount: String? = null
    private val summaries = mutableMapOf<String, AiProfileSummary>()

    fun accountId(): String? = WeDatabaseApi.currentAiAccount()

    internal fun isCurrentAccount(account: String?): Boolean = account != null && account == accountId()

    internal fun accountKey(account: String): String = MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun rootFor(account: String): Path = privateRoot / accountKey(account)

    private fun profileFile(account: String, wxId: String): Path? {
        if (wxId.isBlank() || wxId == account || wxId.endsWith("@chatroom") ||
            wxId == "." || wxId == ".." || wxId.any { it == '/' || it == '\\' || it == '\u0000' }
        ) {
            return null
        }
        return rootFor(account) / "profiles" / "$wxId.json"
    }

    @Synchronized
    fun readProfile(wxId: String, account: String? = accountId()): AiProfile? {
        if (account == null) return null
        val file = profileFile(account, wxId) ?: return null
        val text = readText(file) ?: return null
        return runCatching { json.decodeFromString<AiProfile>(text) }
            .getOrNull()?.takeIf { it.wxId == wxId && isCurrentAccount(account) }
    }

    @Synchronized
    fun saveProfile(profile: AiProfile, account: String? = accountId()): Boolean {
        if (account == null) return false
        val file = profileFile(account, profile.wxId) ?: return false
        // 新建档案只能写空位，避免重复点击覆盖已有档案或损坏后尚可恢复的文件。
        if (file.exists()) return false
        return save(profile, account, file)
    }

    private fun save(profile: AiProfile, account: String, file: Path): Boolean {
        val text = runCatching { json.encodeToString(profile) }.getOrNull() ?: return false
        if (!writeAtomic(file, text)) return false
        if (indexedAccount == account) summaries[profile.wxId] = profile.toSummary()
        return true
    }

    fun patchProfile(wxId: String, block: (AiProfile) -> AiProfile): AiProfile? =
        patchProfile(wxId, accountId(), block)

    @Synchronized
    fun patchProfile(wxId: String, account: String?, block: (AiProfile) -> AiProfile): AiProfile? {
        if (account == null) return null
        val current = readProfile(wxId, account) ?: return null
        val updated = block(current)
        if (updated.wxId != wxId || !isCurrentAccount(account)) return null
        if (updated == current) return current
        val file = profileFile(account, wxId) ?: return null
        return if (save(updated, account, file)) updated else null
    }

    @Synchronized
    fun deleteProfile(wxId: String, account: String? = accountId()): Boolean {
        if (account == null || AiRunState.isRunning(account, wxId)) return false
        val file = profileFile(account, wxId) ?: return false
        if (!delete(file)) return false
        if (indexedAccount == account) summaries.remove(wxId)
        return true
    }

    internal fun listProfileIds(account: String?): List<String> {
        if (account == null || !isCurrentAccount(account)) return emptyList()
        return (rootFor(account) / "profiles").toFile().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
    }

    @Synchronized
    fun loadIndex(account: String? = accountId()): AiProfileIndex {
        if (account == null || !isCurrentAccount(account)) return AiProfileIndex()
        if (indexedAccount != account) {
            indexedAccount = null
            summaries.clear()
            val entries = listProfileIds(account).mapNotNull { readProfile(it, account)?.toSummary() }
            if (!isCurrentAccount(account)) return AiProfileIndex()
            entries.associateByTo(summaries) { it.wxId }
            indexedAccount = account
        }
        return if (isCurrentAccount(account)) AiProfileIndex(summaries.values.sortedWith(summaryOrder)) else AiProfileIndex()
    }

    fun loadFriends(account: String? = accountId()): AiFriendSnapshot {
        if (account == null) return AiFriendSnapshot()
        val text = readText(rootFor(account) / "friends.json") ?: return AiFriendSnapshot()
        return runCatching { json.decodeFromString<AiFriendSnapshot>(text) }
            .getOrNull()?.takeIf { isCurrentAccount(account) } ?: AiFriendSnapshot()
    }

    fun saveFriends(friends: List<AiFriend>, account: String? = accountId()): Boolean {
        if (account == null) return false
        val snapshot = AiFriendSnapshot(
            exportedAt = System.currentTimeMillis(),
            friends = friends.sortedWith(compareBy({ it.groupKey }, { it.sortKey })),
        )
        return writeAtomic(rootFor(account) / "friends.json", json.encodeToString(snapshot))
    }

    private fun allowed(file: Path): Boolean {
        val account = accountId() ?: return false
        val path = file.toAbsolutePath().normalize()
        val root = rootFor(account).toAbsolutePath().normalize()
        return path != root && path.startsWith(root)
    }

    fun readText(file: Path): String? = runCatching {
        if (!allowed(file) || !file.exists()) return null
        file.readText().takeIf { allowed(file) }
    }.getOrNull()

    fun writeAtomic(file: Path, text: String): Boolean = runCatching {
        if (!allowed(file)) return false
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".wcx-ai-", ".tmp")
        try {
            tmp.writeText(text)
            if (!allowed(file)) return false
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } finally {
            Files.deleteIfExists(tmp)
        }
    }.getOrDefault(false)

    fun delete(file: Path): Boolean = runCatching {
        allowed(file) && Files.deleteIfExists(file)
    }.getOrDefault(false)
}

private fun AiProfile.toSummary(): AiProfileSummary = AiProfileSummary(
    wxId = wxId,
    nickname = nickname,
    remark = remark,
    pyInitial = AiLetterIndex.groupOf(pyInitial.ifBlank { nickname }),
    sortKey = quanPin.ifBlank { nickname.lowercase() },
    avatarUrl = avatarUrl,
    state = state,
    auditing = auditing,
    progress = progress,
    tagCount = tags.size,
    memoryCount = memories.size,
    lastError = lastError,
    autoIncremental = autoIncremental,
    autoReplyEnabled = autoReplyEnabled,
)
