package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AiDailyReportEntry(val reportAt: Long, val text: String)

private fun Map<String, AiDailyReportEntry>.latestReportEntries() =
    entries.sortedByDescending { it.key }.take(14)

@Serializable
internal data class AiDailyReportState(
    val hour: Int = 22,
    val bindingId: String = "",
    val enabledAt: Long = 0,
    val lastAttempt: String = "",
    // Legacy fields are folded into history on read and cleared on the next write.
    val reportAt: Long = 0,
    val text: String = "",
    val lastError: String = "",
    val history: Map<String, AiDailyReportEntry> = emptyMap(),
) {
    val recentReports: List<Map.Entry<String, AiDailyReportEntry>>
        get() = history.latestReportEntries()
}

internal object AiDailyReport {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var controller: Job? = null
    @Volatile private var automatic: Job? = null

    @Synchronized
    fun start(wakeAt: (Long?) -> Unit) {
        if (controller?.isActive == true) return
        controller = scope.launch {
            while (isActive) {
                val wait = try {
                    checkSchedule(wakeAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeLogger.w("AiDailyReport", "每日总结定时检查失败", e)
                    TimeUnit.MINUTES.toMillis(1)
                }
                withTimeoutOrNull(wait) { changes.receive() }
            }
        }
    }

    @Synchronized
    fun stop() {
        controller?.cancel()
        controller = null
        automatic?.cancel()
    }

    fun wake() {
        if (!AiConfig.isMasterEnabled) automatic?.cancel()
        changes.trySend(Unit)
    }

    private fun path(account: String) = AiProfileStore.rootFor(account).resolve("daily-report.json")

    fun read(account: String): AiDailyReportState = AiProfileStore.readText(path(account))?.let {
        runCatching { json.decodeFromString<AiDailyReportState>(it) }.getOrNull()
    }?.let { state ->
        if (state.reportAt <= 0 || state.text.isBlank()) return@let state
        val day = Instant.ofEpochMilli(state.reportAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val history = if (day in state.history) state.history else
            state.history + (day to AiDailyReportEntry(state.reportAt, state.text))
        state.copy(reportAt = 0, text = "", history = history)
    } ?: AiDailyReportState()

    @Synchronized
    private fun update(account: String, block: (AiDailyReportState) -> AiDailyReportState?): Boolean {
        if (!AiProfileStore.isCurrentAccount(account)) return false
        val state = block(read(account)) ?: return false
        return AiProfileStore.writeAtomic(path(account), json.encodeToString(state))
    }

    fun schedule(account: String, hour: Int, bindingId: String): Boolean {
        val saved = update(account) {
            if (bindingId.isNotEmpty()) {
                val binding = AiConfig.capture(AiConfig.Purpose.TOOLS)
                if (binding == null || binding.account != account || binding.id != bindingId) return@update null
            }
            it.copy(hour = hour.coerceIn(0, 23), bindingId = bindingId, enabledAt = System.currentTimeMillis(), lastError = "")
        }
        if (saved) {
            automatic?.cancel()
            wake()
        }
        return saved
    }

    fun saveReport(binding: AiConfig.Binding, day: LocalDate, text: String): Boolean = update(binding.account) {
        if (AiConfig.isCurrent(binding)) it.withReport(day, text) else null
    }

    private fun AiDailyReportState.withReport(day: LocalDate, text: String): AiDailyReportState {
        val entry = AiDailyReportEntry(System.currentTimeMillis(), text.take(10_000))
        val latest = (history + (day.toString() to entry)).latestReportEntries()
        return copy(history = latest.associate { it.toPair() }, lastError = "")
    }

    private fun AiDailyReportState.matchesSchedule(binding: AiConfig.Binding, scheduledAt: Long): Boolean =
        enabledAt == scheduledAt && bindingId == binding.id && AiConfig.isCurrent(binding)

    private fun checkSchedule(wakeAt: (Long?) -> Unit): Long {
        val minute = TimeUnit.MINUTES.toMillis(1)
        val binding = AiConfig.capture(AiConfig.Purpose.TOOLS)
        if (binding == null) {
            wakeAt(null)
            return minute
        }
        val account = binding.account
        val state = read(account)
        if (state.bindingId != binding.id) {
            wakeAt(null)
            return minute
        }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayKey = today.toString()
        val trigger = today.atTime(state.hour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        val next = if (trigger > now) trigger else
            today.plusDays(1).atTime(state.hour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        wakeAt(next)

        if (trigger <= now && trigger > state.enabledAt && state.lastAttempt != dayKey && automatic?.isActive != true) {
            automatic = scope.launch {
                val saved = update(account) {
                    if (it.matchesSchedule(binding, state.enabledAt) && it.lastAttempt != dayKey) {
                        it.copy(lastAttempt = dayKey)
                    } else null
                }
                if (!saved) return@launch
                try {
                    val report = AiAssistantTools.run(AiTool.DAILY_REPORT, binding, reportDate = today)
                    update(account) {
                        if (it.matchesSchedule(binding, state.enabledAt)) it.withReport(today, report) else null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update(account) {
                        if (it.matchesSchedule(binding, state.enabledAt)) {
                            it.copy(lastError = "定时总结未生成，可手动重试")
                        } else null
                    }
                }
            }
        }
        return (next - now).coerceIn(1000L, minute)
    }
}
