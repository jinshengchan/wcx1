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
 * 诊断版：定位“添加时间”正确数据源（不要当正式版用）。
 *
 * 背景：正式版只用聊天记录里最早一条 type=10000 系统消息，好友 A 对得上（2026-03-06），
 * 但好友“艳”对不上——我方行 2026-09-18，微信自带却是 2017年08月。说明系统消息源只在
 * 原始加好友消息还在时才准，清过记录、迁过机就失真。
 *
 * 本诊断版在地区行下方插两行：
 * 第 1 行：正式版同款主行（系统消息日期），用于确认主行行为未变；
 * 第 2 行：诊断行，列出系统消息内容片段 + rcontact 整行里所有落在时间戳区间内的
 * Long 列（列名=日期），供对照微信自带月份找胜出源。
 * 对照锚点：艳 → 2017年08月；A → 2026年03月。两个锚点都对得上的列才是胜出源。
 *
 * 其余行为与正式版一致：头部注入、4 秒观察期、重试、防重复。默认开启，装上即见。
 */
@Feature(name = "显示详细添加时间（诊断版）", categories = ["联系人与群组", "联系人详情页面"], description = "诊断版：在地区下方显示添加时间主行 + 系统消息与 rcontact 候选对照行，用于定位正确数据源")
object ShowDetailedAddTimeInContactDetails : SwitchFeature() {

    // 诊断版默认开启，装上即生效；正式版再改回默认关闭。
    override val defaultEnabled: Boolean = true

    private const val TAG = "ShowDetailedAddTime"
    private const val INSERT_TAG = "add_time_diag_inserted"

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
        // 群资料页传进来的是群 id，不是好友，不展示。
        if (wxId.endsWith("@chatroom")) return

        val diag = resolveDiagnostic(wxId)
        val daysSuffix = diag.daysAgo?.let { " (${it}天)" } ?: ""
        val mainLine = "添加时间：${diag.displayDate}$daysSuffix"
        val diagLine = "诊断：${diag.diagText}"
        val lines = listOf(mainLine, diagLine)
        val decorView = activity.window?.decorView as? ViewGroup ?: return

        var attempt = 0
        fun runAttempt() {
            if (hasInsertedMarker(decorView)) return
            if (insertLines(decorView, lines)) {
                WeLogger.i(TAG, "inserted header addTime for $wxId: $mainLine | $diagLine")
                startHeaderWatcher(decorView, wxId, lines)
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

    /** 本次已插过（防重试与观察期重复插）。 */
    private fun hasInsertedMarker(root: ViewGroup): Boolean {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child.tag == INSERT_TAG) return true
            if (child is ViewGroup && hasInsertedMarker(child)) return true
        }
        return false
    }

    /**
     * 插一组行：先找以“地区”开头的 TextView 做兄弟行插入；没有则找含“地区”的整块文本做块内插行。
     * 已插过则直接返回 false，避免观察期与重试并发时插出双行。
     */
    private fun insertLines(root: ViewGroup, lines: List<String>): Boolean {
        if (hasInsertedMarker(root)) return false
        val regionView = findRegionTextView(root)
        if (regionView != null) {
            val parent = regionView.parent as? ViewGroup ?: return false
            var index = parent.indexOfChild(regionView)
            for (line in lines) {
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
                index++
                parent.addView(textView, index)
            }
            return true
        }
        val blockView = findBlockTextView(root) ?: return false
        return insertLinesAfterRegionInText(blockView, lines)
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

    /** 在整块文本的地区行后插一组行；已插过的行跳过，全插过则返回 false。 */
    private fun insertLinesAfterRegionInText(textView: TextView, lines: List<String>): Boolean {
        val original = textView.text?.toString() ?: return false
        val missing = lines.filter { !original.contains(it) }
        if (missing.isEmpty()) return false
        val textLines = original.split("\n").toMutableList()
        val index = textLines.indexOfFirst { it.contains("地区") }
        if (index < 0) return false
        var insertAt = index + 1
        for (line in missing) {
            textLines.add(insertAt, line)
            insertAt++
        }
        textView.text = textLines.joinToString("\n")
        textView.tag = INSERT_TAG
        return true
    }

    /**
     * 插上后观察一段时间：微信异步刷新重建头部时会清掉插入的行，发现没了就立刻补上。
     * 观察期约 4 秒，结束即停；不常驻、不碰微信数据，只是补自己插的行。
     */
    private fun startHeaderWatcher(decorView: ViewGroup, wxId: String, lines: List<String>) {
        var checks = 0
        fun tick() {
            if (checks >= WATCH_CHECKS) return
            checks++
            if (!hasInsertedMarker(decorView)) {
                if (insertLines(decorView, lines)) {
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

    private data class DiagResult(
        val displayDate: String,
        val daysAgo: Int?,
        val diagText: String
    )

    private fun resolveDiagnostic(wxId: String): DiagResult {
        val escaped = wxId.replace("'", "''")

        // 候选 1：最早一条 type=10000 系统消息（整行取回，用于看内容片段）。
        val sysMsgRow = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT * FROM message " +
                    "WHERE talker = '$escaped' AND type = 10000 " +
                    "ORDER BY createTime ASC LIMIT 1"
            ).firstOrNull()
        }.getOrNull()
        val sysMsgMillis = (sysMsgRow?.get("createTime") as? Long)?.let { normalizeToMillis(it) }
        // 内容启发式：取行里除 talker 外最长的非空字符串，截 24 字；列名未知，仅作诊断参考。
        val sysMsgContent = sysMsgRow?.entries
            ?.mapNotNull { (_, value) -> (value as? String)?.takeIf { it.isNotBlank() && it != wxId } }
            ?.maxByOrNull { it.length }
            ?.take(24)
        val sysMsgDate = sysMsgMillis?.let { dateFormat.format(Date(it)) } ?: "无"

        // 候选 2：rcontact 整行里落在时间戳区间内的 Long 列（按时间从早到晚排）。
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
        val rcontactText = if (rcontactCandidates.isEmpty()) {
            "无"
        } else {
            rcontactCandidates.joinToString("，") { (column, millis) -> "$column=${dateFormat.format(Date(millis))}" }
        }

        val displayDate = sysMsgMillis?.let { dateFormat.format(Date(it)) } ?: "未知"
        // 按日历日期相减，不是毫秒相除：添加当天的时刻晚于当前时刻时毫秒法会少算一天。
        val daysAgo = sysMsgMillis?.let {
            val addDate = LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
            val diff = ChronoUnit.DAYS.between(addDate, LocalDate.now())
            if (diff < 0) null else diff.toInt()
        }

        val diagText = "sysMsg=$sysMsgDate" +
            (if (sysMsgContent != null) "「$sysMsgContent」" else "") +
            "；rcontact：$rcontactText"
        WeLogger.i(TAG, "diag addTime for $wxId: $diagText")
        return DiagResult(displayDate, daysAgo, diagText)
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
