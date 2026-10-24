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
 * 测试版：好友资料页显示精确到日的添加时间。
 *
 * 背景：微信自带的“添加时间”只显示到月（如 2026年02月），底层时间戳字段未公开确认。
 * 本测试版不改动微信原行，而是另起一行展示推算结果，并在副标题和日志里同时输出
 * 全部候选数据源，供对照微信自带月份验证哪个数据源是对的；验证通过后再做正式版
 * （替换原行、去掉测试标记）。
 *
 * 候选数据源（按优先级）：
 * 1. message 表最早一条 type=10000 系统消息的 createTime（加好友通过时微信插入的
 *    “你已添加了X，现在可以开始聊天了”）；聊天记录被清空后失效。
 * 2. rcontact 整行里数值落在时间戳区间内的 Long 列（各版本微信表结构有差异，
 *    需对照验证；本测试版只展示、不采信）。
 */
@Feature(name = "显示详细添加时间（测试版）", categories = ["联系人与群组", "联系人详情页面"], description = "在联系人详情页面显示精确到日的添加时间（测试版：副标题列出候选数据源供对照验证）")
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
        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "添加时间：${result.displayDate}（测试）",
                summary = result.summary,
                position = 2
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
        val summary: String
    )

    private fun resolveAddTime(wxId: String): ResolveResult {
        val escaped = wxId.replace("'", "''")

        // 候选 1：最早一条 type=10000 系统消息
        val sysMsgTime = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT createTime FROM message " +
                    "WHERE talker = '$escaped' AND type = 10000 " +
                    "ORDER BY createTime ASC LIMIT 1"
            ).firstOrNull()?.get("createTime") as? Long
        }.getOrNull()
        val sysMsgDate = sysMsgTime?.let { formatTimestamp(it) }

        // 候选 2：rcontact 整行里落在时间戳区间内的 Long 列
        val rcontactCandidates = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT * FROM rcontact WHERE username = '$escaped' LIMIT 1"
            ).firstOrNull()
                ?.entries
                ?.mapNotNull { (column, value) ->
                    val ts = value as? Long ?: return@mapNotNull null
                    val date = formatTimestamp(ts) ?: return@mapNotNull null
                    "$column=$date"
                }
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())

        WeLogger.i(
            TAG,
            "addTime candidates for $wxId: sysMsg=$sysMsgDate, rcontact=$rcontactCandidates"
        )

        val displayDate = sysMsgDate
            ?: rcontactCandidates.firstOrNull()?.substringAfter("=", "")
            ?: "未知"
        val summary = buildString {
            append("系统消息：${sysMsgDate ?: "无"}")
            if (rcontactCandidates.isNotEmpty()) {
                append("；rcontact：${rcontactCandidates.joinToString("，")}")
            }
        }
        return ResolveResult(displayDate, summary)
    }

    /**
     * 秒级（约 1.8e9）或毫秒级（约 1.8e12）时间戳转 yyyy-MM-dd；
     * 不在 2000-01-01 到 2100-01-01 区间内的数值视为非时间戳，返回 null。
     */
    private fun formatTimestamp(value: Long): String? {
        val millis = when {
            value in 946684800L..4102444800L -> value * 1000L
            value in 946684800000L..4102444800000L -> value
            else -> return null
        }
        return dateFormat.format(Date(millis))
    }
}
