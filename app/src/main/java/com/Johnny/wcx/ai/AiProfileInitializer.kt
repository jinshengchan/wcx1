// Adapted from AARCNoa/zhiyou, AiProfileInitializer.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.ai.AiChatHistory.TimeWindow
import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.serialization.Serializable

/** Runs archive work over a fixed, account-bound history range. */
object AiProfileInitializer {

    private const val SLICE_MAX = 1_000
    private const val STYLE_MIN_OWN_LINES = 30

    @Serializable
    data class Plan(
        val fromYear: Int?,
        val fromSec: Long,
        val toSec: Long,
        val totalLines: Int,
        val slices: List<Slice>,
        val years: List<Int>,
        val allFromSec: Long,
        val allTotalLines: Int,
        val emptyReason: String?,
        internal val accountKey: String = "",
        internal val peer: String = "",
        internal val incremental: Boolean = false,
        internal val automatic: Boolean = false,
    )

    @Serializable
    data class Slice(val window: TimeWindow, val label: String, val year: Int)

    fun isRunning(wxId: String): Boolean = AiRunState.isRunning(wxId, AiRunState.Job.PROFILE)

    fun recoverInterrupted(account: String? = AiProfileStore.accountId()) {
        if (account == null) return
        AiProfileStore.listProfileIds(account).forEach { wxId ->
            val profile = AiProfileStore.readProfile(wxId, account) ?: return@forEach
            if (!profile.busy || AiRunState.isRunning(account, wxId)) return@forEach
            AiProfileStore.patchProfile(wxId, account) {
                if (AiRunState.isRunning(account, wxId)) it
                else if (it.state == AiProfileState.INITIALIZING) interrupted(it, "整理已中断，可以从断点继续")
                else it.copy(auditing = false, progress = "")
            }
        }
    }

    fun resumePlan(wxId: String, account: String? = AiProfileStore.accountId()): Plan? =
        AiProfileStore.readProfile(wxId, account)?.checkpoint

    /** Read-only preview. Every window is clipped to the same exclusive end timestamp. */
    fun preview(wxId: String, fromYear: Int? = null, account: String? = AiProfileStore.accountId()): Plan {
        val end = System.currentTimeMillis() / 1000
        val unavailable = Plan(fromYear, 0, end, 0, emptyList(), emptyList(), 0, 0, "微信账号或数据库未就绪")
        if (account == null || wxId.isBlank() || wxId == account || wxId.endsWith("@chatroom")) return unavailable
        if (fromYear != null && fromYear !in 1970..2100) return unavailable.copy(emptyReason = "请选择有效年份")
        return runCatching {
            val earliest = AiChatHistory.earliestSec(account, wxId, end) ?: 0
            val currentYear = AiChatHistory.yearOf(end)
            val years = if (earliest > 0) (currentYear downTo AiChatHistory.yearOf(earliest)).toList() else emptyList()
            val from = fromYear?.let { AiChatHistory.startOfMonth(it) } ?: earliest
            val allCount = count(account, wxId, TimeWindow(earliest, end), MessageRendering.TEXT_ONLY)
            val selectedCount = if (from == earliest) allCount
            else count(account, wxId, TimeWindow(from, end), MessageRendering.TEXT_ONLY)
            val slices = if (selectedCount == 0) emptyList() else years.asReversed().flatMap { year ->
                val start = maxOf(from, AiChatHistory.startOfMonth(year))
                val stop = minOf(end, AiChatHistory.startOfMonth(year + 1))
                split(account, wxId, TimeWindow(start, stop))
            }
            if (!AiProfileStore.isCurrentAccount(account)) return unavailable
            Plan(
                fromYear, from, end, selectedCount, slices, years, earliest, allCount,
                if (slices.isEmpty()) "所选时段没有可整理的文字消息" else null,
                AiProfileStore.accountKey(account), wxId,
            )
        }.getOrElse { unavailable.copy(emptyReason = "无法统计所选聊天记录，请重试") }
    }

    private fun count(account: String, wxId: String, window: TimeWindow, types: Set<Int>): Int {
        if (window.toSec <= window.fromSec) return 0
        check(AiProfileStore.isCurrentAccount(account))
        val total = AiChatHistory.count(account, wxId, window, types)
        check(total >= 0)
        return total
    }

    private fun split(account: String, wxId: String, window: TimeWindow): List<Slice> {
        val total = count(account, wxId, window, MessageRendering.CHAT_TYPES)
        if (total == 0) return emptyList()
        val length = window.toSec - window.fromSec
        if (total <= SLICE_MAX || length <= 1) {
            val start = AiPrompts.dateOf(window.fromSec)
            val end = AiPrompts.dateOf(window.toSec - 1)
            return listOf(Slice(window, if (start == end) start else "$start ~ $end", AiChatHistory.yearOf(window.fromSec)))
        }
        val mid = window.fromSec + length / 2
        return split(account, wxId, TimeWindow(window.fromSec, mid)) +
            split(account, wxId, TimeWindow(mid, window.toSec))
    }

    fun start(
        wxId: String,
        plan: Plan,
        fromIndex: Int = 0,
        account: String? = AiProfileStore.accountId(),
        expectedBinding: String? = null,
        restart: Boolean = false,
    ): Boolean {
        if (account == null || plan.accountKey != AiProfileStore.accountKey(account) || plan.peer != wxId ||
            plan.slices.isEmpty() || plan.emptyReason != null || fromIndex !in 0..plan.slices.size ||
            (restart && (fromIndex != 0 || plan.incremental))
        ) return false
        val canStart: (AiProfile) -> Boolean = { profile ->
            if (restart) profile.state == AiProfileState.FAILED && profile.checkpoint == plan
            else (profile.checkpoint == plan && profile.progressDone == fromIndex) || (fromIndex == 0 && !plan.incremental)
        }
        return launch(account, wxId, expectedBinding, canStart) { profile, binding ->
            val resume = !restart && profile.checkpoint == plan && profile.progressDone == fromIndex
            execute(account, wxId, profile, plan, fromIndex, binding, resume)
        }
    }

    fun startIncremental(
        wxId: String,
        account: String? = AiProfileStore.accountId(),
        expectedBinding: String? = null,
    ): Boolean = account != null && startIncremental(wxId, account, expectedBinding, null)

    internal fun startAutomatic(wxId: String, account: String, schedule: AiAutoIncrementalSchedule): Boolean =
        schedule.dueAt(System.currentTimeMillis()) != null && startIncremental(wxId, account, schedule.binding, schedule)

    private fun startIncremental(
        wxId: String,
        account: String,
        expectedBinding: String?,
        schedule: AiAutoIncrementalSchedule?,
    ): Boolean {
        val canStart: (AiProfile) -> Boolean = {
            it.isReady && it.coveredUntilSec > 0 && it.pendingAudit == null &&
                (schedule == null || it.autoIncremental == schedule)
        }
        return launch(account, wxId, expectedBinding, canStart) { profile, binding ->
            val attemptAt = System.currentTimeMillis()
            val reservedSchedule = schedule?.copy(lastAttemptAt = attemptAt)
            if (schedule != null) {
                // Claim the slot before requesting anything, including on runs with no new history.
                val reserved = AiProfileStore.patchProfile(wxId, account) {
                    if (canStart(it) && schedule.dueAt(attemptAt) != null &&
                        AiConfig.selection(AiConfig.Purpose.FRIEND_PROFILE)?.id == binding.id
                    ) it.copy(autoIncremental = reservedSchedule) else it
                }?.autoIncremental
                if (reserved != reservedSchedule) return@launch
            }
            val from = profile.coveredUntilSec
            val end = attemptAt / 1000
            val window = TimeWindow(from, end)
            val count = count(account, wxId, window, MessageRendering.TEXT_ONLY)
            if (count == 0) {
                // Keep the prior boundary so a later manual run can include delayed history inserts.
                AiProfileStore.patchProfile(wxId, account) { it.copy(lastError = null, progress = "没有新的文字消息") }
                return@launch
            }
            val plan = Plan(
                null, from, end, count, split(account, wxId, window), emptyList(), from, count,
                null, AiProfileStore.accountKey(account), wxId, incremental = true, automatic = schedule != null,
            )
            execute(account, wxId, profile, plan, 0, binding, resume = false, reservedSchedule = reservedSchedule)
        }
    }

    private fun launch(
        account: String,
        wxId: String,
        expectedBinding: String?,
        canStart: (AiProfile) -> Boolean,
        work: (AiProfile, AiConfig.Binding) -> Unit,
    ): Boolean {
        val binding = AiConfig.capture(AiConfig.Purpose.FRIEND_PROFILE) ?: return false
        if (binding.account != account || (expectedBinding != null && binding.id != expectedBinding)) return false
        if (!AiRunState.start(account, wxId, AiRunState.Job.PROFILE)) return false
        val profile = AiProfileStore.readProfile(wxId, account)
        if (profile == null || profile.busy || !canStart(profile)) {
            AiRunState.finish(account, wxId)
            return false
        }
        Thread({
            try {
                work(profile, binding)
            } catch (_: Exception) {
                fail(account, wxId, "整理中断，请重试")
            } finally {
                AiRunState.finish(account, wxId)
            }
        }, "wcx-ai-profile").start()
        return true
    }

    private fun execute(
        account: String,
        wxId: String,
        base: AiProfile,
        plan: Plan,
        fromIndex: Int,
        binding: AiConfig.Binding,
        resume: Boolean,
        reservedSchedule: AiAutoIncrementalSchedule? = null,
    ) {
        val authorizationChanged = "自动整理授权或模型已变化，可以手动继续"
        fun automaticAllowed(profile: AiProfile): Boolean = reservedSchedule == null ||
            (profile.autoIncremental == reservedSchedule &&
                AiConfig.selection(AiConfig.Purpose.FRIEND_PROFILE)?.id == binding.id)

        val mode = when {
            plan.automatic -> "自动增量"
            plan.incremental -> "增量"
            else -> "全量"
        }
        var profile = AiProfileStore.patchProfile(wxId, account) {
            if (!automaticAllowed(it)) return@patchProfile it
            it.copy(
                state = AiProfileState.INITIALIZING,
                lastError = null,
                progress = "$mode 准备中",
                progressDone = fromIndex,
                progressTotal = plan.slices.size,
                checkpoint = plan,
                pendingAudit = null,
                auditBasis = "",
                styleNotes = if (resume) it.styleNotes else emptyList(),
                styleSampleCount = if (resume) it.styleSampleCount else 0,
            )
        }?.takeIf { it.state == AiProfileState.INITIALIZING && it.checkpoint == plan } ?: return

        for (index in fromIndex until plan.slices.size) {
            val slice = plan.slices[index]
            val lines = AiChatHistory.load(account, wxId, slice.window, SLICE_MAX)
            if (lines == null) {
                fail(account, wxId, "读取分片失败，或分片内消息过多")
                return
            }
            val samples = lines.count { it.fromMe && it.isText && it.text.length in 3..40 }
            val styleWindow = if (plan.incremental || profile.style.userEdited) null
            else StyleWindow(slice.label, slice.window.fromSec, slice.window.toSec)
            if (reservedSchedule != null && AiProfileStore.readProfile(wxId, account)?.let(::automaticAllowed) != true) {
                fail(account, wxId, authorizationChanged)
                return
            }
            val staged = if (lines.none { it.isText }) profile else extract(profile, slice, lines, styleWindow, binding)
            if (staged == null) {
                fail(account, wxId, "模型未返回可保存的分片，检查服务配置后继续")
                return
            }
            // One atomic commit records both the slice output and the next unread slice.
            profile = AiProfileStore.patchProfile(wxId, account) {
                if (!automaticAllowed(it)) return@patchProfile interrupted(it, authorizationChanged)
                it.copy(
                    tags = staged.tags,
                    memories = staged.memories,
                    styleNotes = staged.styleNotes,
                    styleSampleCount = it.styleSampleCount + samples,
                    progress = "$mode · ${slice.label}（${index + 1}/${plan.slices.size}）",
                    progressDone = index + 1,
                )
            } ?: run {
                fail(account, wxId, "保存分片失败，可以从断点继续")
                return
            }
            if (profile.state != AiProfileState.INITIALIZING) return
        }

        val style = if (plan.incremental) profile.style else summarizeStyle(profile, binding)
        val now = System.currentTimeMillis()
        val start = if (plan.incremental) base.sourceRange.substringBefore(" ~ ").ifBlank { AiPrompts.dateOf(plan.fromSec) }
        else AiPrompts.dateOf(plan.fromSec)
        val end = AiPrompts.dateOf(plan.toSec - 1)
        val source = when {
            plan.automatic -> "自动整理"
            plan.incremental -> "增量整理"
            else -> "首次整理"
        }
        val change = ProfileChange.diffOf(base, profile, source, now)
        if (AiProfileStore.patchProfile(wxId, account) {
            if (!automaticAllowed(it)) return@patchProfile interrupted(it, authorizationChanged)
            it.copy(
                state = AiProfileState.READY,
                initializedAt = now,
                lastError = null,
                progress = "",
                sourceRange = "$start ~ $end",
                coveredUntilSec = plan.toSec,
                checkpoint = null,
                changes = it.changes.recorded(change),
                style = if (it.style.userEdited) it.style else style,
                styleNotes = emptyList(),
                styleSampleCount = 0,
            )
        } == null) fail(account, wxId, "保存整理结果失败，可以从断点继续")
    }

    private fun extract(
        profile: AiProfile,
        slice: Slice,
        lines: List<AiChatHistory.Line>,
        styleWindow: StyleWindow?,
        binding: AiConfig.Binding,
    ): AiProfile? {
        val response = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage("system", AiPrompts.initSystem(withStyle = styleWindow != null)),
                AiConfig.ChatMessage("user", AiPrompts.initYearUser(slice.label, AiChatHistory.transcript(lines), profile)),
            ),
            tools = AiTools.forInit(withStyle = styleWindow != null),
            temperature = 0.3,
            readTimeoutMs = 180_000,
            binding = binding,
        )
        if (response.error != null || response.finishReason == "length" || response.toolCalls.isEmpty()) return null
        return response.toolCalls.fold(profile) { updated, call ->
            AiTools.applyProfileCall(updated, call, slice.year, slice.window.toSec, styleWindow) ?: return null
        }
    }

    private fun summarizeStyle(profile: AiProfile, binding: AiConfig.Binding): ReplyStyle {
        if (profile.style.userEdited || profile.styleNotes.isEmpty()) return profile.style
        if (profile.styleSampleCount < STYLE_MIN_OWN_LINES) {
            return if (profile.style.summary.isBlank()) profile.style.copy(insufficient = true) else profile.style
        }
        val notes = profile.styleNotes.sortedBy { it.fromSec }
        val end = notes.maxOf { it.toSec }
        val cut = end - (end - notes.first().fromSec) / 3
        val recent = notes.filter { it.toSec > cut }
        val (late, early) = if (recent.size >= 3) recent to notes.filter { it.toSec <= cut }
        else notes to emptyList()
        val response = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage("system", AiPrompts.styleSummarySystem()),
                AiConfig.ChatMessage("user", AiPrompts.styleSummaryUser(late, early)),
            ),
            tools = AiTools.forStyle(),
            temperature = 0.3,
            readTimeoutMs = 180_000,
            binding = binding,
        )
        if (response.error != null || response.finishReason == "length") return profile.style
        val call = response.toolCalls.singleOrNull() ?: return profile.style
        return AiTools.applyStyleCall(profile, call)?.style ?: profile.style
    }

    private fun interrupted(profile: AiProfile, message: String): AiProfile =
        profile.copy(state = AiProfileState.FAILED, progress = "", lastError = message)

    private fun fail(account: String, wxId: String, message: String) {
        AiProfileStore.patchProfile(wxId, account) {
            if (it.isReady && it.checkpoint == null) it.copy(lastError = message, progress = "")
            else interrupted(it, message)
        }
    }
}
