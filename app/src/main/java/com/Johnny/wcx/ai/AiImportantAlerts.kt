// Important-message rules adapted from Johnny520/wcx v348 (GPL-3.0).
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.preferences.WePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AiImportantAlert(val talker: String, val name: String, val text: String, val at: Long)

internal object AiImportantAlerts {
    var enabled: Boolean
        get() = WePrefs.getBoolOrDef("ai_workbench_important_alerts", false)
        set(value) { WePrefs.putBool("ai_workbench_important_alerts", value) }

    private val urgent = Regex("截止|务必|一定要|尽快|最后期限|别忘|记得|过期|逾期|deadline", RegexOption.IGNORE_CASE)
    private val time = Regex("明天|后天|今天|下周|周[一二三四五六日天]|\\d{1,2}[:：]\\d{2}|\\d{1,2}[月日号]")
    private val money = Regex("\\d+(\\.\\d+)?\\s*(元|块|万)|转账|付款|报销|发票")
    private val place = Regex("地址|集合|地点|会议室|开会|签到|楼下|门口|定位")
    private val json = Json { ignoreUnknownKeys = true }

    private fun path(account: String) = AiProfileStore.rootFor(account).resolve("important-alerts.json")

    @Synchronized
    fun pending(account: String): List<AiImportantAlert> {
        if (!AiConfig.isMasterEnabled || !AiProfileStore.isCurrentAccount(account)) return emptyList()
        return read(account).filter { AiAssistantTools.visible(it.talker) }
    }

    private fun read(account: String): List<AiImportantAlert> = AiProfileStore.readText(path(account))?.let {
        runCatching { json.decodeFromString<List<AiImportantAlert>>(it) }.getOrNull()
    }.orEmpty()

    @Synchronized
    fun clear(account: String) {
        if (AiProfileStore.isCurrentAccount(account)) AiProfileStore.delete(path(account))
    }

    @Synchronized
    fun record(talker: String, text: String) {
        if (!enabled || !AiConfig.isMasterEnabled) return
        val body = text.trim().take(200)
        if (body.length < 4 || (!urgent.containsMatchIn(body) &&
                listOf(time, money, place).count { it.containsMatchIn(body) } < 2)) return

        val account = AiProfileStore.accountId() ?: return
        runCatching {
            if (!AiAssistantTools.visible(talker)) return
            val alerts = read(account)
            val now = System.currentTimeMillis()
            if (alerts.any { it.talker == talker && it.text == body && now - it.at < 600_000 }) return
            val name = WeDatabaseApi.getDisplayName(talker).take(60)
            if (!AiProfileStore.isCurrentAccount(account) || !AiConfig.isMasterEnabled) return
            val updated = (listOf(AiImportantAlert(talker, name, body, now)) + alerts).take(60)
            AiProfileStore.writeAtomic(path(account), json.encodeToString(updated))
        }
    }
}
