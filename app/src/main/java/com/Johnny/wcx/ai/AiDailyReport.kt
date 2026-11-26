package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
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
internal data class AiDailyReportState(
    val hour: Int = 22,
    val bindingId: String = "",
    val enabledAt: Long = 0,
    val lastAttempt: String = "",
    val reportAt: Long = 0,
    val text: String = "",
    val lastError: String = "",
)

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
    } ?: AiDailyReportState()

    @Synchronized
    private fun update(account: String, block: (AiDailyReportState) -> AiDailyReportState): Boolean {
        if (!AiProfileStore.isCurrentAccount(account)) return false
        return AiProfileStore.writeAtomic(path(account), json.encodeToString(block(read(account))))
    }

    fun schedule(account: String, hour: Int, bindingId: String): Boolean {
        if (bindingId.isNotEmpty() && !AiConfig.isMasterEnabled) return false
        val saved = update(account) {
            it.copy(hour = hour.coerceIn(0, 23), bindingId = bindingId, enabledAt = System.currentTimeMillis(), lastError = "")
        }
        if (saved) {
            automatic?.cancel()
            wake()
        }
        return saved
    }

    fun saveReport(account: String, text: String): Boolean = update(account) {
        it.copy(text = text.take(10_000), reportAt = System.currentTimeMillis(), lastError = "")
    }

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
        val today = LocalDate.now(zone)
        val dayKey = today.toString()
        val trigger = today.atTime(state.hour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        val next = if (trigger > now) trigger else
            today.plusDays(1).atTime(state.hour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        wakeAt(next)

        if (trigger <= now && trigger > state.enabledAt && state.lastAttempt != dayKey && automatic?.isActive != true) {
            automatic = scope.launch {
                if (!AiConfig.isCurrent(binding)) return@launch
                var eligible = false
                val saved = update(account) {
                    eligible = it.enabledAt == state.enabledAt && it.bindingId == binding.id && it.lastAttempt != dayKey
                    if (eligible) it.copy(lastAttempt = dayKey) else it
                }
                if (!saved || !eligible) return@launch
                try {
                    val report = AiAssistantTools.run(AiTool.DAILY_REPORT, binding)
                    update(account) {
                        if (it.enabledAt == state.enabledAt && AiConfig.isCurrent(binding)) {
                            it.copy(text = report.take(10_000), reportAt = System.currentTimeMillis(), lastError = "")
                        } else it
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update(account) {
                        if (it.enabledAt == state.enabledAt && AiConfig.isMasterEnabled) {
                            it.copy(lastError = "定时总结未生成，可手动重试")
                        } else it
                    }
                }
            }
        }
        return (next - now).coerceIn(1000L, minute)
    }
}
