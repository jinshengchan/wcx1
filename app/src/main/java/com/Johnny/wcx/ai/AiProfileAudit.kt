// Adapted from AARCNoa/zhiyou, AiProfileAudit.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stages model proposals for review and applies only confirmed entries from the saved proposal. */
object AiProfileAudit {

    fun isRunning(wxId: String): Boolean = AiRunState.isRunning(wxId, AiRunState.Job.AUDIT)

    fun start(
        wxId: String,
        resume: Boolean = false,
        account: String? = AiProfileStore.accountId(),
        expectedBinding: String? = null,
    ): Boolean {
        if (account == null) return false
        val binding = AiConfig.capture(AiConfig.Purpose.FRIEND_PROFILE) ?: return false
        if (binding.account != account || (expectedBinding != null && expectedBinding != binding.id)) return false
        if (!AiRunState.start(account, wxId, AiRunState.Job.AUDIT)) return false
        val profile = AiProfileStore.readProfile(wxId, account)
        if (profile == null || !profile.isReady || profile.busy) {
            AiRunState.finish(account, wxId)
            return false
        }
        Thread({
            try {
                run(account, wxId, profile, resume, binding)
            } catch (_: Exception) {
                pause(account, wxId)
            } finally {
                AiRunState.finish(account, wxId)
            }
        }, "wcx-ai-profile-audit").start()
        return true
    }

    private fun basis(profile: AiProfile): String = AiProfileStore.accountKey(
        Json.encodeToString(profile.tags) + "\n" + Json.encodeToString(profile.memories),
    )

    private fun run(account: String, wxId: String, original: AiProfile, resume: Boolean, binding: AiConfig.Binding) {
        val guide = AiProfileLimits.reviewGuide(original)
        val slices = auditSlices(AiMemoryIndex.numbered(original.memories))
        val tagSlices = if (original.tags.isEmpty()) 0 else 1
        val total = slices.size + tagSlices
        val basis = basis(original)
        val continuing = resume && basis == original.auditBasis && original.progressTotal == total
        val from = if (continuing) original.progressDone.coerceIn(0, total) else 0
        var profile = AiProfileStore.patchProfile(wxId, account) {
            it.copy(
                auditing = true,
                auditBasis = basis,
                pendingAudit = if (continuing) it.pendingAudit else null,
                progressDone = from,
                progressTotal = total,
                progress = "正在审查好友档案",
            )
        } ?: return

        for (position in from until total) {
            val memorySlice = slices.getOrNull(position - tagSlices)
            val system = if (memorySlice == null) AiPrompts.auditTagSystem(guide) else AiPrompts.auditMemorySystem(guide)
            val user = if (memorySlice == null) AiPrompts.auditTagUser(profile, guide) else AiPrompts.auditMemoryUser(memorySlice)
            val response = AiConfig.chatWithTools(
                messages = listOf(AiConfig.ChatMessage("system", system), AiConfig.ChatMessage("user", user)),
                tools = if (memorySlice == null) AiTools.forAuditTags() else AiTools.forAuditMemories(),
                temperature = 0.2,
                binding = binding,
            )
            if (response.error != null || response.finishReason == "length") {
                pause(account, wxId)
                return
            }
            val numbers = memorySlice?.mapTo(HashSet()) { it.first }
            val staged = response.toolCalls.fold(profile) { updated, call ->
                AiTools.proposeAudit(updated, call, numbers) ?: run {
                    pause(account, wxId)
                    return
                }
            }
            profile = AiProfileStore.patchProfile(wxId, account) {
                it.copy(
                    pendingAudit = staged.pendingAudit,
                    progressDone = position + 1,
                    progress = "已审查 ${position + 1}/$total 片",
                )
            } ?: run {
                pause(account, wxId)
                return
            }
        }

        if (AiProfileStore.patchProfile(wxId, account) {
            it.copy(
                auditing = false,
                auditBasis = if (it.pendingAudit != null) basis else "",
                progress = if (it.pendingAudit == null) "审查完成，没有需要清理的内容" else "审查完成，等待确认",
                progressDone = 0,
                progressTotal = 0,
            )
        } == null) pause(account, wxId)
    }

    private fun pause(account: String, wxId: String) {
        AiProfileStore.patchProfile(wxId, account) {
            it.copy(auditing = false, progress = "审查中断，可以从断点继续")
        }
    }

    fun auditSlices(numbered: List<Pair<Int, MemoryEntry>>): List<List<Pair<Int, MemoryEntry>>> {
        val output = mutableListOf<List<Pair<Int, MemoryEntry>>>()
        var start = 0
        while (start < numbered.size) {
            var end = minOf(start + AiTools.AUDIT_SLICE_MAX, numbered.size)
            if (end < numbered.size) {
                val day = numbered[end].second.occurredAt.take(10)
                var boundary = end
                while (boundary > start && numbered[boundary - 1].second.occurredAt.take(10) == day) boundary--
                if (boundary > start) end = boundary
            }
            output += numbered.subList(start, end)
            start = end
        }
        return output
    }

    private fun AiAuditPlan.contains(selection: AiAuditPlan): Boolean =
        createdAt == selection.createdAt && mergeTags.containsAll(selection.mergeTags) &&
            deleteTags.containsAll(selection.deleteTags) && deleteMemories.containsAll(selection.deleteMemories) &&
            mergeGroups.containsAll(selection.mergeGroups) && markImportant.containsAll(selection.markImportant)

    fun apply(wxId: String, plan: AiAuditPlan, account: String? = AiProfileStore.accountId()): ProfileChange? {
        if (account == null || plan.isEmpty) return null
        var change: ProfileChange? = null
        val saved = AiProfileStore.patchProfile(wxId, account) { profile ->
            val recorded = profile.pendingAudit
            if (profile.busy || AiRunState.isRunning(account, wxId) || recorded == null ||
                !recorded.contains(plan) || profile.auditBasis != basis(profile)
            ) return@patchProfile profile
            val (next, applied) = applyPlan(profile, plan)
            change = applied
            next
        }
        return change.takeIf { saved != null }
    }

    private fun applyPlan(profile: AiProfile, plan: AiAuditPlan): Pair<AiProfile, ProfileChange> {
        val now = System.currentTimeMillis()
        val tags = profile.tags.associateByTo(linkedMapOf()) { TagRef.id(it.category, it.key) }
        val mergedTags = mutableSetOf<String>()
        var mergedTagCount = 0
        for (group in plan.mergeTags) {
            val id = TagRef.id(group.category, group.key)
            val ids = TagRef.ids(group.keys) + id
            val expected = profile.tags.filter { TagRef.id(it.category, it.key) in ids }
            val targets = ids.mapNotNull { tags[it] }
            if (targets.size < 2 || targets.size != expected.size || !targets.containsAll(expected)) continue
            val latest = targets.maxBy { it.updatedAt }
            targets.forEach { tags.remove(TagRef.id(it.category, it.key)) }
            tags[id] = ProfileTag(
                category = group.category,
                key = group.key,
                value = group.value.ifBlank { latest.value }.take(AiTools.VALUE_MAX),
                confidence = targets.maxOf { it.confidence },
                evidence = targets.firstOrNull { it.evidence.isNotBlank() }?.evidence.orEmpty(),
                updatedAt = latest.updatedAt,
            )
            mergedTags += id
            mergedTagCount++
        }
        var removedTags = 0
        for (ref in plan.deleteTags) {
            val id = TagRef.id(ref.category, ref.key)
            if (id !in mergedTags && tags.remove(id) != null) removedTags++
        }

        val memories = profile.memories.associateByTo(linkedMapOf()) { it.id }
        fun find(ref: AuditMemoryRef): MemoryEntry? = memories.values.firstOrNull {
            it.occurredAt == ref.occurredAt && it.title == ref.title
        }

        var mergedGroups = 0
        var mergedItems = 0
        val mergedMemories = mutableSetOf<String>()
        for (group in plan.mergeGroups) {
            val targets = group.items.mapNotNull(::find).distinctBy { it.id }
            if (targets.size < 2 || targets.size != group.items.size || targets.any { it.id in mergedMemories }) continue
            val occurredAt = AiDates.normalize(group.occurredAt) ?: targets.minBy { AiDates.sortKey(it.occurredAt) }.occurredAt
            val title = group.title.trim().ifBlank { "日常闲聊" }.take(AiTools.TITLE_MAX)
            val id = AiTools.memoryId(occurredAt, title)
            if (id in memories && targets.none { it.id == id }) continue
            val detail = group.detail.ifBlank { targets.joinToString("；") { it.title } }.take(AiTools.DETAIL_MAX)
            targets.forEach { memories.remove(it.id) }
            memories[id] = MemoryEntry(id, occurredAt, title, detail, now, targets.any { it.important })
            mergedMemories += id
            mergedGroups++
            mergedItems += targets.size
        }

        val removedMemories = plan.deleteMemories.count { ref ->
            find(ref)?.takeUnless { it.id in mergedMemories }?.let { memories.remove(it.id) } != null
        }
        var marked = 0
        for (ref in plan.markImportant) {
            val target = find(ref) ?: continue
            if (!target.important) {
                memories[target.id] = target.copy(important = true)
                marked++
            }
        }
        val change = ProfileChange(
            at = now,
            source = "审查",
            tagsMerged = mergedTagCount,
            tagsRemoved = removedTags,
            memoriesRemoved = removedMemories,
            memoriesMerged = mergedGroups,
            memoriesMergedItems = mergedItems,
            memoriesMarked = marked,
        )
        return clearProposal(profile).copy(
            tags = tags.values.toList(),
            memories = AiProfileLimits.memories(memories.values),
            changes = profile.changes.recorded(change),
        ) to change
    }

    fun discard(wxId: String, expectedPlan: AiAuditPlan? = null, account: String? = AiProfileStore.accountId()) {
        if (account == null) return
        AiProfileStore.patchProfile(wxId, account) {
            if (!it.isReady || it.busy || AiRunState.isRunning(account, wxId) ||
                (expectedPlan != null && expectedPlan != it.pendingAudit)
            ) it else clearProposal(it)
        }
    }

    private fun clearProposal(profile: AiProfile): AiProfile = profile.copy(
        pendingAudit = null, auditBasis = "", auditing = false, progress = "", progressDone = 0, progressTotal = 0,
    )
}
