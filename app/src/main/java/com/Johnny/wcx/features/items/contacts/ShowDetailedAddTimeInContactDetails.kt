package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.currentWxId
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import dev.ujhhgtg.reflekt.reflekt
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * 测试版：好友资料页在“地区”正下方显示精确到日的添加时间，对齐参考图样式。
 *
 * 位置：上一版走偏好列表插行，落在头部区块下方、带箭头和副标题，与参考图不符。
 * 本版改走头部注入：ContactInfoUI.initView 跑完后遍历视图树，找到文字以“地区”
 * 开头的 TextView，在其父布局的下一位插入一个同样式 TextView，紧贴在地区行下方。
 *
 * 格式：添加时间：yyyy-MM-dd (N天)，N = 按日历日期相减的天数（不是毫秒相除向下取整，
 * 后者会因添加时刻晚于当前时刻而少算一天，如 2026-03-06 到 2026-10-24 应为 232 天）。
 * 候选数据源同时写日志（标签 ShowDetailedAddTime），供对照微信自带月份验证；
 * 验证通过后再做正式版（去掉测试标记）。
 *
 * 候选数据源（按优先级）：
 * 1. message 表最早一条 type=10000 系统消息的 createTime（加好友通过时微信插入的
 *    “你已添加了X，现在可以开始聊天了”）；聊天记录被清空后失效。
 * 2. rcontact 整行里数值落在时间戳区间内的 Long 列（各版本微信表结构有差异，
 *    需对照验证；本测试版只展示、不采信）。
 */
@Feature(name = "显示详细添加时间（测试版）", categories = ["联系人与群组", "联系人详情页面"], description = "在联系人详情页面地区正下方显示精确到日的添加时间（测试版：候选数据源写日志供对照验证）")
object ShowDetailedAddTimeInContactDetails : SwitchFeature() {

    // 测试版默认开启，装上即生效；正式版再改回默认关闭。
    override val defaultEnabled: Boolean = true

    private const val TAG = "ShowDetailedAddTime"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onEnable() {
        ContactInfoUI::class.reflekt().apply {
            firstMethod { name = "initView" }
                .hookAfter {
                    val activity = thisObject as Activity
                    runCatching { insertHeaderAddTime(activity) }
                        .onFailure { WeLogger.e(TAG, "insert header addTime failed", it) }
                }
        }
    }

    private fun insertHeaderAddTime(activity: Activity) {
        val wxId = activity.currentWxId ?: return
        // 群资料页（ChatroomInfoUI）传进来的是群 id，不是好友，不展示。
        if (wxId.endsWith("@chatroom")) return

        val result = resolveAddTime(wxId)
        val daysSuffix = result.daysAgo?.let { " (${it}天)" } ?: ""
        val text = "添加时间：${result.displayDate}$daysSuffix（测试）"

        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val regionView = findRegionTextView(decorView) ?: run {
            WeLogger.i(TAG, "region TextView not found for $wxId")
            return
        }
        val parent = regionView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(regionView)

        val textView = TextView(activity).apply {
            this.text = text
            // 照抄地区行的样式：字号、颜色、字体、内边距、布局参数。
            setTextSize(TypedValue.COMPLEX_UNIT_PX, regionView.textSize)
            setTextColor(regionView.textColors)
            typeface = regionView.typeface
            setPadding(
                regionView.paddingLeft,
                regionView.paddingTop,
                regionView.paddingRight,
                regionView.paddingBottom
            )
            layoutParams = runCatching {
                ViewGroup.MarginLayoutParams(regionView.layoutParams as ViewGroup.MarginLayoutParams)
            }.getOrElse {
                ViewGroup.LayoutParams(
                    regionView.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    regionView.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        parent.addView(textView, index + 1)
        WeLogger.i(TAG, "inserted header addTime for $wxId: $text")
    }

    /** 深度遍历找文字以“地区”开头的 TextView（头部区块的地区行）。 */
    private fun findRegionTextView(root: ViewGroup): TextView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child is TextView) {
                val text = child.text?.toString() ?: ""
                if (text.startsWith("地区")) return child
            } else if (child is ViewGroup) {
                findRegionTextView(child)?.let { return it }
            }
        }
        return null
    }

    private data class ResolveResult(
        val displayDate: String,
        val daysAgo: Int?
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
        // 按日历日期相减，不是毫秒相除：添加当天的时刻晚于当前时刻时毫秒法会少算一天。
        val daysAgo = bestMillis?.let {
            val addDate = LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
            val diff = ChronoUnit.DAYS.between(addDate, LocalDate.now())
            if (diff < 0) null else diff.toInt()
        }

        WeLogger.i(
            TAG,
            "addTime candidates for $wxId: sysMsg=$sysMsgDate, " +
                "rcontact=${rcontactCandidates.map { (column, millis) -> "$column=${dateFormat.format(Date(millis))}" }}"
        )
        return ResolveResult(displayDate, daysAgo)
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
