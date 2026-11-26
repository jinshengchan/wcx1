// Adapted from AARCNoa/zhiyou, GroupDigestRunner.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.features.ai.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object GroupDigestRunner {
    private val purpose = AiConfig.Purpose.GROUP_DIGEST
    private const val REQUEST_TIMEOUT_MS = 180_000
    private const val TOTAL_BUDGET_MS = 10 * 60_000L
    private const val MAX_FAILURES = 3
    internal const val BINDING_CHANGED = "群摘要模型已变更，请重新启用自动总结"

    private data class GroupKey(val account: String, val groupId: String)
    private data class Run(val job: Job, val manual: Boolean)

    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = ConcurrentHashMap<GroupKey, Run>()

    fun start(
        groupId: String,
        manual: Boolean = true,
        account: String? = GroupDigestStore.accountId(),
        expectedBinding: String? = null,
        notBeforeSec: Long? = null,
    ): Boolean = account != null && start(manualScope, GroupKey(account, groupId), manual, expectedBinding, notBeforeSec)

    fun isRunning(groupId: String, account: String? = GroupDigestStore.accountId()): Boolean =
        account != null && runs.containsKey(GroupKey(account, groupId))

    fun cancel(groupId: String, account: String? = GroupDigestStore.accountId()) {
        if (account != null) runs[GroupKey(account, groupId)]?.job?.cancel()
    }

    internal fun cancelAutomatic(groupId: String, account: String?) {
        if (account != null) runs[GroupKey(account, groupId)]?.takeUnless { it.manual }?.job?.cancel()
    }

    internal fun cancelOtherAccounts(account: String?) {
        runs.forEach { (key, run) -> if (key.account != account) run.job.cancel() }
    }

    internal fun pause() {
        runs.values.forEach { it.job.cancel() }
    }

    internal fun hasAutomaticRun(): Boolean = runs.values.any { !it.manual }

    internal fun startAutomatic(scope: CoroutineScope, groupId: String, account: String): Boolean =
        start(scope, GroupKey(account, groupId), manual = false, expectedBinding = null, notBeforeSec = null)

    private fun start(
        scope: CoroutineScope,
        key: GroupKey,
        manual: Boolean,
        expectedBinding: String?,
        notBeforeSec: Long?,
    ): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) { run(key, manual, expectedBinding, notBeforeSec) }
        val active = Run(job, manual)
        if (runs.putIfAbsent(key, active) != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion {
            runs.remove(key, active)
            if (manual) GroupDigestScheduler.schedule(key.groupId, key.account)
        }
        return job.start()
    }

    private suspend fun run(key: GroupKey, manual: Boolean, expectedBinding: String?, notBeforeSec: Long?) {
        if (!AiConfig.isMasterEnabled || (!manual && AiRunState.hasLongTask())) return
        val config = GroupDigestStore.readConfig(key.groupId, key.account) ?: return
        val now = System.currentTimeMillis()
        val slot = if (manual) 0L else config.toSchedule()?.dueSlotEnd(now) ?: 0L
        if (!manual && slot == 0L) return
        val binding = AiConfig.capture(purpose)?.takeIf { it.account == key.account }
        if (!AiConfig.isMasterEnabled) return
        if (binding == null || (manual && expectedBinding != null && expectedBinding != binding.id) ||
            (!manual && config.scheduleBinding != binding.id)
        ) {
            update(key, config, manual) { current ->
                current.copy(
                    enabled = manual && current.enabled,
                    lastStatus = GroupDigestStatus.UNCONFIGURED,
                    lastError = if (manual) "群摘要模型未配置或已变更" else BINDING_CHANGED,
                )
            }
            return
        }

        if (!manual && update(key, config, manual) { it.copy(lastAttemptedSlotEnd = slot) } == null) return

        try {
            ensureCurrent(key, binding)
            val (windowStart, toSec) = config.windowSec(now, manual)
            val fromSec = maxOf(windowStart, notBeforeSec ?: windowStart)
            if (toSec <= fromSec) {
                markEmpty(key, config, manual, "时间窗口为空")
                return
            }
            val textCount = GroupTranscript.textCount(key.groupId, fromSec, toSec, key.account)
            ensureCurrent(key, binding)
            if (textCount < MIN_TEXT_LINES) {
                markEmpty(key, config, manual, "这段只有 $textCount 条文字，少于 $MIN_TEXT_LINES 条")
                return
            }
            val transcript = GroupTranscript.load(key.groupId, fromSec, toSec, key.account)
            ensureCurrent(key, binding)
            val chunks = GroupTranscript.chunk(transcript.lines)
            val lines = chunks.sumOf { it.size }
            if (chunks.sumOf { chunk -> chunk.count { it.isText } } < MIN_TEXT_LINES) {
                markEmpty(key, config, manual, "可用于总结的文字不足 $MIN_TEXT_LINES 条")
                return
            }
            val sampled = transcript.sampled || lines < transcript.lines.size
            val coveredFrom = if (sampled) maxOf(fromSec, chunks.first().first().atSec) else fromSec
            val parsed = withTimeoutOrNull(TOTAL_BUDGET_MS) {
                generate(key, binding, config.name, chunks, coveredFrom, toSec)
            }
            ensureCurrent(key, binding)
            if (parsed == null) {
                markFailed(key, config, manual)
                return
            }
            if (!parsed.significant) {
                update(key, config, manual) {
                    it.copy(
                        lastSlotEnd = toSec * 1000L,
                        lastStatus = GroupDigestStatus.EMPTY,
                        lastError = "没有值得总结的内容",
                        failures = 0,
                    )
                }
                return
            }
            val digest = GroupDigest(
                id = now.toString(),
                fromSec = coveredFrom,
                toSec = toSec,
                createdAt = now,
                headline = parsed.headline.ifBlank { "（无标题）" },
                points = parsed.points,
                todos = parsed.todos,
                messageCount = lines,
                manual = manual,
                sampled = sampled,
            )
            update(key, config, manual) { it.withDigest(digest) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ensureCurrent(key, binding)
            markFailed(key, config, manual)
        }
    }

    private suspend fun generate(
        key: GroupKey,
        binding: AiConfig.Binding,
        name: String,
        chunks: List<List<GroupTranscript.Line>>,
        fromSec: Long,
        toSec: Long,
    ): GroupDigestPrompts.Parsed? {
        if (chunks.size == 1) {
            val raw = call(key, binding, GroupDigestPrompts.system(),
                GroupDigestPrompts.user(name, fromSec, toSec, GroupTranscript.transcript(chunks.single())))
            return raw?.let(::parseFinal)
        }

        val parts = mutableListOf<String>()
        for (lines in chunks) {
            val raw = call(key, binding, GroupDigestPrompts.sliceSystem(), GroupDigestPrompts.sliceUser(
                name, lines.first().atSec, lines.last().atSec + 1, GroupTranscript.transcript(lines),
            )) ?: return null
            val part = GroupDigestPrompts.parse(raw) ?: return null
            val points = part.points.ifEmpty { listOfNotNull(part.headline.takeIf { it.isNotBlank() }) }
            if (part.significant && (points.isNotEmpty() || part.todos.isNotEmpty())) {
                parts += (points.map { "要点：$it" } + part.todos.map { "待办：$it" }).joinToString("\n")
            }
        }
        if (parts.isEmpty()) return GroupDigestPrompts.Parsed(false, "", emptyList(), emptyList())
        val merged = call(key, binding, GroupDigestPrompts.mergeSystem(),
            GroupDigestPrompts.mergeUser(name, fromSec, toSec, parts)) ?: return null
        return parseFinal(merged)
    }

    private fun parseFinal(raw: String): GroupDigestPrompts.Parsed? {
        val parsed = GroupDigestPrompts.parse(raw)
        if (parsed != null) {
            return parsed.takeUnless { it.significant && it.headline.isBlank() && it.points.isEmpty() && it.todos.isEmpty() }
        }
        if (raw.trimStart().startsWith('{') || raw.trimStart().startsWith("```")) return null
        return GroupDigestPrompts.Parsed(true, GroupDigestPrompts.fallbackHeadline(raw), emptyList(), emptyList())
    }

    private suspend fun call(key: GroupKey, binding: AiConfig.Binding, system: String, user: String): String? {
        ensureCurrent(key, binding)
        val result = AiConfig.requestWithTools(
            messages = listOf(
                AiConfig.ChatMessage(role = "system", content = system),
                AiConfig.ChatMessage(role = "user", content = user),
            ),
            temperature = 0.3,
            readTimeoutMs = REQUEST_TIMEOUT_MS,
            purpose = purpose,
            binding = binding,
        )
        if (result.finishReason == "length") return null
        return result.text?.takeIf { it.isNotBlank() }
    }

    private suspend fun ensureCurrent(key: GroupKey, binding: AiConfig.Binding) {
        currentCoroutineContext().ensureActive()
        if (GroupDigestStore.accountId() != key.account || AiConfig.bindingId(purpose) != binding.id) {
            throw CancellationException("Group digest account or model changed")
        }
    }

    private suspend fun update(
        key: GroupKey,
        config: GroupDigestConfig,
        manual: Boolean,
        transform: (GroupDigestConfig) -> GroupDigestConfig,
    ): GroupDigestConfig? {
        val context = currentCoroutineContext()
        return GroupDigestStore.update(key.groupId, key.account) { current ->
            context.ensureActive()
            if (current.matchesRun(config, manual)) transform(current) else current
        }?.takeIf { it.matchesRun(config, manual) }
    }

    private fun GroupDigestConfig.matchesRun(original: GroupDigestConfig, manual: Boolean): Boolean =
        recordId == original.recordId && (manual || revision == original.revision)

    private suspend fun markEmpty(key: GroupKey, config: GroupDigestConfig, manual: Boolean, reason: String) {
        update(key, config, manual) { it.copy(lastStatus = GroupDigestStatus.EMPTY, lastError = reason) }
    }

    private suspend fun markFailed(key: GroupKey, config: GroupDigestConfig, manual: Boolean) {
        update(key, config, manual) { current ->
            val failures = if (manual) current.failures else current.failures + 1
            val pause = !manual && failures >= MAX_FAILURES
            current.copy(
                enabled = current.enabled && !pause,
                failures = failures,
                lastStatus = if (pause) GroupDigestStatus.PAUSED else GroupDigestStatus.FAILED,
                lastError = if (pause) "连续生成失败，自动总结已暂停" else "群摘要生成失败",
            )
        }
    }
}
