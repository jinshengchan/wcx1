package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.content.ContextWrapper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.currentWxId
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * 好友资料页在“地区”正下方显示精确到日的添加时间。
 *
 * 位置：微信原生的添加时间只精确到月，且在下方“更多信息”里；本功能在头部区块内、
 * 地区行正下方另起一行显示，样式照抄地区行，无箭头无副标题。
 * 格式：添加时间：yyyy-MM-dd (N天)，N 为按日历日期相减的天数（不是毫秒相除向下取整，
 * 后者会因添加时刻晚于当前时刻而少算一天）。
 *
 * 数据源：好友资料表 rcontact 的 createTime 列（好友记录创建时间，语义上就是加好友那一刻）。
 * 2026-10-24 诊断版三例实测全对：艳 2017-08-18（对自带 2017年08月）、陈梦妮 2014-09-23
 * （对自带 2014年09月）、A 2026-03-06（对参考图）；三例 rcontact 均只有 createTime 一个
 * 时间戳候选列，无歧义。
 * 注意：聊天记录里最早一条 type=10000 系统消息不可靠——艳的那条是 2026-09-18 的后来
 * 系统消息（内容为图片标记，非加好友问候），陈梦妮与 A 则一条系统消息都没有；故本功能
 * 不再用系统消息作源或兜底。createTime 拿不到才显示“未知”，不猜、不编。
 * 非好友、群资料页不展示。
 *
 * 渲染时机（B 方案）：hook 资料页头部的绑定方法（NormalProfileHeaderPreference 的 onBindView，
 * 8.0.79 上混淆名 D），在每次绑定完成后把行插进本次绑定的头部视图；微信异步拿到数据重建
 * 头部时，绑定方法会再跑一次，行跟着重建，不再有旧版“initView 时插上、重建时被清掉、
 * 观察期每 400ms 补上”造成的闪烁，因此也不需要重试与观察期。绑定方法用其体内唯一的
 * 日志字符串定位，不依赖混淆名；沙箱核过 8.0.79 dex，该字符串全量仅出现一次。
 */
@Feature(name = "显示详细添加时间", categories = ["联系人与群组", "联系人详情页面"], description = "在联系人详情页面地区正下方显示精确到日的添加时间")
object ShowDetailedAddTimeInContactDetails : SwitchFeature(), IResolveDex {

    private const val TAG = "ShowDetailedAddTime"
    private const val INSERT_TAG = "add_time_detail_inserted"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val bindHeader by dexMethod {
        matcher {
            usingStrings("[onBindView] never attach!")
            paramCount = 1
            paramTypes(View::class.java)
            returnType(Void.TYPE)
        }
    }

    override fun onEnable() {
        runCatching {
            bindHeader.hookAfter {
                val root = args[0] as? ViewGroup ?: return@hookAfter
                var context = root.context
                while (context is ContextWrapper && context !is Activity) {
                    context = context.baseContext
                }
                val activity = context as? Activity ?: return@hookAfter
                runCatching { tryInsertHeaderAddTime(activity, root) }
                    .onFailure { WeLogger.e(TAG, "insert header addTime failed", it) }
            }
        }.onFailure { WeLogger.e(TAG, "hook bindHeader failed", it) }
    }

    private fun tryInsertHeaderAddTime(activity: Activity, root: ViewGroup) {
        val wxId = activity.currentWxId ?: return
        // 群资料页传进来的是群 id，不是好友，不展示。
        if (wxId.endsWith("@chatroom")) return
        if (hasInsertedMarker(root)) return

        val result = resolveAddTime(wxId)
        val daysSuffix = result.daysAgo?.let { " (${it}天)" } ?: ""
        val line = "添加时间：${result.displayDate}$daysSuffix"
        if (insertOnce(root, line)) {
            WeLogger.i(TAG, "inserted header addTime for $wxId: $line")
        } else {
            WeLogger.i(TAG, "region not found for $wxId on this bind, next bind will retry")
        }
    }

    /** 本次绑定已插过（防同一头部视图重复绑定时插出双行）。 */
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
     * 已插过则直接返回 false，避免重复绑定时插出双行。
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

    /** 唯一数据源：好友资料表 rcontact 的 createTime；拿不到则显示未知。 */
    private fun resolveAddTime(wxId: String): ResolveResult {
        val escaped = wxId.replace("'", "''")
        val createMillis = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT createTime FROM rcontact " +
                    "WHERE username = '$escaped' LIMIT 1"
            ).firstOrNull()?.get("createTime") as? Long
        }.getOrNull()?.let { normalizeToMillis(it) }

        val displayDate = createMillis?.let { dateFormat.format(Date(it)) } ?: "未知"
        // 按日历日期相减，不是毫秒相除：添加当天的时刻晚于当前时刻时毫秒法会少算一天。
        val daysAgo = createMillis?.let {
            val addDate = LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
            val diff = ChronoUnit.DAYS.between(addDate, LocalDate.now())
            if (diff < 0) null else diff.toInt()
        }

        WeLogger.i(TAG, "addTime for $wxId: rcontact.createTime=${createMillis?.let { dateFormat.format(Date(it)) }}")
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
