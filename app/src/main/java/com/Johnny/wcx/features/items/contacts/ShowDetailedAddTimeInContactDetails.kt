package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.currentWxId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测试版：好友资料页在“地区”下一行显示精确到日的添加时间。
 *
 * 位置：锚定标题为“地区”的一行，插到它下一行（该好友没填地区、无地区行时回落到固定位置 2）。
 * 格式：添加时间：yyyy-MM-dd (N天)，N = 当天 - 添加日期的天数；日期未知时不带天数。
 * 副标题和日志里同时输出全部候选数据源，供对照微信自带月份验证；验证通过后再做正式版
 * （去掉测试标记）。
 *
 * 候选数据源（按优先级）：
 * 1. message 表最早一条 type=10000 系统消息的 createTime（加好友通过时微信插入的
 *    “你已添加了X，现在可以开始聊天了”）；聊天记录被清空后失效。
 * 2. rcontact 整行里数值落在时间戳区间内的 Long 列（各版本微信表结构有差异，
 *    需对照验证；本测试版只展示、不采信）。
 */
@Feature(name = "显示详细添加时间（测试版）", categories = ["联系人与群组", "联系人详情页面"], description = "在联系人详情页面地区下一行显示精确到日的添加时间（测试版：副标题列出候选数据源供对照验证）")
object ShowDetailedAddTimeInContactDetails : SwitchFeature(), IContactInfoProvider {

    // 测试版默认开启，装上即生效；正式版再改回默认关闭。
    override val defaultEnabled: Boolean = true

    private const val TAG = "ShowDetailedAddTime"
    private const val PREF_KEY = "add_time_detail_test"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId ?: return emptyList()
        // 群资料页（ChatroomInfoUI）传进来的是群 id，不是好友，不展示。
        if (wxId.endsWith("@chatroom")) return emptyList()

        val result = resolveAddTime(wxId)
        val daysSuffix = result.daysAgo?.let { " (${it}天)" } ?: ""
        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "添加时间：${result.displayDate}$daysSuffix（测试）",
                summary = result.summary,
                position = 2,
                afterTitle = "地区"
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean = false

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    private data class ResolveResult(
        val displayDate: String,
        val daysAgo: Int?,
        val summary: String
    )

    private fun resolveAddTime(wxId: String): ResolveResult {
        val escaped = wxId.replace("'", "''")

        // 候选 1：最早一条 type=10000 系统消息
        val sysMsgMillis = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT createTime FROM message " +
                    "WHERE talker = '$escaped' AND type = 10000 " +
                    "ORDER BY createTime ASC LIMIT 1"
            ).firstOrNull()?.get("createTime") as? Long
        }.getOrNull()?.let { normalizeToMillis(it) }
        val sysMsgDate = sysMsgMillis?.let { dateFormat.format(Date(it)) }

        // 候选 2：rcontact 整行里落在时间戳区间内的 Long 列（按时间从早到晚排）
        val rcontactCandidates = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT * FROM rcontact WHERE username = '$escaped' LIMIT 1"
            ).firstOrNull()
                ?.entries
                ?.mapNotNull { (column, value) ->
                    val millis = (value as? Long)?.let { normalizeToMillis(it) } ?: return@mapNotNull null
                    column to millis
                }
                ?.sortedBy { it.second }
                ?: emptyList()
        }.getOrDefault(emptyList())

        val bestMillis = sysMsgMillis ?: rcontactCandidates.firstOrNull()?.second
        val displayDate = bestMillis?.let { dateFormat.format(Date(it)) } ?: "未知"
        val daysAgo = bestMillis?.let {
            val diff = System.currentTimeMillis() - it
            if (diff < 0) null else (diff / 86_400_000L).toInt()
        }

        WeLogger.i(
            TAG,
            "addTime candidates for $wxId: sysMsg=$sysMsgDate, " +
                "rcontact=${rcontactCandidates.map { (column, millis) -> "$column=${dateFormat.format(Date(millis))}" }}"
        )

        val summary = buildString {
            append("系统消息：${sysMsgDate ?: "无"}")
            if (rcontactCandidates.isNotEmpty()) {
                append("；rcontact：${rcontactCandidates.joinToString("，") { (column, millis) -> "$column=${dateFormat.format(Date(millis))}" }}")
            }
        }
        return ResolveResult(displayDate, daysAgo, summary)
    }

    /**
     * 秒级（约 1.8e9）或毫秒级（约 1.8e12）时间戳转毫秒；
     * 不在 2000-01-01 到 2100-01-01 区间内的数值视为非时间戳，返回 null。
     */
    private fun normalizeToMillis(value: Long): Long? {
        return when (value) {
            in 946684800L..4102444800L -> value * 1000L
            in 946684800000L..4102444800000L -> value
            else -> null
        }
    }
}
