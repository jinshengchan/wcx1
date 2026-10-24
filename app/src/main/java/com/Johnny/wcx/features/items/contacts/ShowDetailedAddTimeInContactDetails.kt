package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.util.TypedValue
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
 * 位置：偏好列表插行会落在头部区块下方、带箭头和间隔，与参考图不符；所以改走头部注入。
 * 上一版（只试一次、只认文字以“地区”开头的 TextView）在真机上完全没显示：头部地区行
 * 可能是一整块文本的一部分，也可能文字比 initView 设得晚，单次扫描命中不了就静默放弃了。
 * 本版三重兜底：①文字以“地区”开头的 TextView → 在其父布局下一位插同样式行；
 * ②含“地区”的整块文本 → 在块内地区行后插一行；③都没命中则 0.5s/1.5s/3s 各重试一次。
 * 每次结果都写日志（标签 ShowDetailedAddTime），以便区分“没找到”和“插入失败”。
 *
 * 闪烁问题（2026-10-24 真机反馈：部分联系人该行显示 1 秒、消失 1 秒、再显示）：
 * 资料未全缓存的联系人，微信拿到异步数据后会重建头部，把外加的行清掉，之后逻辑
 * 第二次触发又补上。修法：插上后启动 4 秒观察期，每 400ms 查一次头部，被清掉就
 * 立刻补上（缺口从约 1 秒缩到 0.4 秒内）；观察期结束即停，不常驻后台。
 *
 * 格式：添加时间：yyyy-MM-dd (N天)，N = 按日历日期相减的天数（不是毫秒相除向下取整，
 * 后者会因添加时刻晚于当前时刻而少算一天，如 2026-03-06 到 2026-10-24 应为 232 天）。
 * 候选数据源同时写日志，供对照微信自带月份验证；验证通过后再做正式版（去掉测试标记）。
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
    private const val INSERT_TAG = "add_time_test_inserted"

    // 头部观察期：插上后每 400ms 查一次、共 10 次（约 4 秒），微信重建头部清掉行时立刻补上。
    private const val WATCH_INTERVAL_MS = 400L
    private const val WATCH_CHECKS = 10

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onEnable() {
        ContactInfoUI::class.reflekt().apply {
            firstMethod { name = "initView" }
                .hookAfter {
                    val activity = thisObject as Activity
                    runCatching { tryInsertHeaderAddTime(activity) }
                        .onFailure { WeLogger.e(TAG, "insert header addTime failed", it) }
                }
        }
    }

    private fun tryInsertHeaderAddTime(activity: Activity) {
        val wxId = activity.currentWxId ?: return
        // 群资料页（ChatroomInfoUI）传进来的是群 id，不是好友，不展示。
        if (wxId.endsWith("@chatroom")) return

        val result = resolveAddTime(wxId)
        val daysSuffix = result.daysAgo?.let { " (${it}天)" } ?: ""
        val line = "添加时间：${result.displayDate}$daysSuffix"
        val decorView = activity.window?.decorView as? ViewGroup ?: return

        var attempt = 0
        fun runAttempt() {
            if (hasInsertedMarker(decorView)) return
            if (insertOnce(decorView, line)) {
                WeLogger.i(TAG, "inserted header addTime for $wxId: $line")
                startHeaderWatcher(decorView, wxId, line)
                return
            }
            attempt++
            if (attempt <= 3) {
                val delay = longArrayOf(500L, 1500L, 3000L)[attempt - 1]
                WeLogger.i(TAG, "region not found yet for $wxId, retry $attempt in ${delay}ms")
                decorView.postDelayed(
                    { runCatching { runAttempt() }.onFailure { WeLogger.e(TAG, "retry insert failed", it) } },
                    delay
                )
            } else {
                WeLogger.i(TAG, "region not found for $wxId after retries, giving up")
            }
        }
        runAttempt()
    }

    /** 本次已插过（防重试重复插）。 */
    private fun hasInsertedMarker(root: ViewGroup): Boolean {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child.tag == INSERT_TAG) return true
            if (child is ViewGroup && hasInsertedMarker(child)) return true
        }
        return false
    }

    /**
     * 试一次：先找以“地区”开头的 TextView 做兄弟行插入；没有则找含“地区”的整块文本做块内插行。
     * 已插过则直接返回 false，避免观察期与重试并发时插出双行。
     */
    private fun insertOnce(root: ViewGroup, line: String): Boolean {
        if (hasInsertedMarker(root)) return false
        val regionView = findRegionTextView(root)
        if (regionView != null) {
            val parent = regionView.parent as? ViewGroup ?: return false
            val index = parent.indexOfChild(regionView)
            val textView = TextView(regionView.context).apply {
                tag = INSERT_TAG
                text = line
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
            return true
        }
        val blockView = findBlockTextView(root) ?: return false
        return insertLineAfterRegionInText(blockView, line)
    }

    /**
     * 插上后观察一段时间：微信异步刷新重建头部时会清掉插入的行，发现没了就立刻补上。
     * 观察期约 4 秒，结束即停；不常驻、不碰微信数据，只是补自己插的那行。
     */
    private fun startHeaderWatcher(decorView: ViewGroup, wxId: String, line: String) {
        var checks = 0
        fun tick() {
            if (checks >= WATCH_CHECKS) return
            checks++
            if (!hasInsertedMarker(decorView)) {
                if (insertOnce(decorView, line)) {
                    WeLogger.i(TAG, "re-inserted header addTime for $wxId after header refresh")
                }
            }
            if (checks < WATCH_CHECKS) {
                decorView.postDelayed(
                    { runCatching { tick() }.onFailure { WeLogger.e(TAG, "header watcher failed", it) } },
                    WATCH_INTERVAL_MS
                )
            }
        }
        decorView.postDelayed(
            { runCatching { tick() }.onFailure { WeLogger.e(TAG, "header watcher failed", it) } },
            WATCH_INTERVAL_MS
        )
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

    /** 深度遍历找文字含“地区”的 TextView（地区行是一整块文本的一部分时用）。 */
    private fun findBlockTextView(root: ViewGroup): TextView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child is TextView) {
                val text = child.text?.toString() ?: ""
                if (text.contains("地区")) return child
            } else if (child is ViewGroup) {
                findBlockTextView(child)?.let { return it }
            }
        }
        return null
    }

    /** 在整块文本的地区行后插一行；已插过则返回 false。 */
    private fun insertLineAfterRegionInText(textView: TextView, line: String): Boolean {
        val original = textView.text?.toString() ?: return false
        if (original.contains(line)) return false
        val lines = original.split("\n").toMutableList()
        val index = lines.indexOfFirst { it.contains("地区") }
        if (index < 0) return false
        lines.add(index + 1, line)
        textView.text = lines.joinToString("\n")
        textView.tag = INSERT_TAG
        return true
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
