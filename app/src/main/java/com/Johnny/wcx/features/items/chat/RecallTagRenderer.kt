package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 防撤回「圆角标签」的渲染器。
 *
 * 做法：给每条被拦下来的撤回消息记一条 `msgSvrId|原文`，然后挂到聊天消息容器
 * （`ChattingItemContainer`）的 `onAttachedToWindow` 上——容器是横向 LinearLayout
 * （头像 + 气泡），往它末尾 addView 一个圆角小标签就落在气泡右侧。
 *
 * 为什么挂在 `onAttachedToWindow` 而不是 `onBindViewHolder`：前者是公开 framework 方法，
 * 名字稳定、不依赖混淆；后者在微信里是混淆名，每版都变。视图复用时 container 会带着旧标签
 * 重新 attach，所以每次进入都要先按当前内容复核一遍：命中就补标签，不命中就把旧标签摘掉，
 * 避免"标签串到别的消息上"。
 *
 * 匹配顺序：先用容器 tag 里的消息对象取内容，取不到再退化为遍历子 TextView 的文本比对。
 * 两条路径都失败时什么都不做——渲染层出问题绝不影响防撤回本身。
 */
internal object RecallTagRenderer {

    private const val TAG = "RecallTagRenderer"

    /** 被拦下的撤回消息：`msgSvrId|原文`。与 AntiMessageRecall 共用。 */
    internal const val KEY_BLOCKED = "recall_blocked_msgs"

    private const val MAX_ENTRIES = 200

    /** 标签 View 的标记，用来判断"这个容器里是不是已经加过了"。 */
    private val LABEL_TAG = "wcx_recall_label".hashCode()

    private const val CONTAINER_CLASS = "com.tencent.mm.ui.chatting.viewitems.ChattingItemContainer"

    private var installed = false

    /** 当前标签文字（默认「已撤回」，可在设置里改）。 */
    internal var labelText: String = "已撤回"

    internal fun blockedEntries(): Set<String> = WePrefs.getStringSetOrDef(KEY_BLOCKED, emptySet())

    /** 记一条被拦下的撤回消息（原文用于渲染时匹配）。 */
    internal fun rememberBlocked(msgSvrId: String, content: String) {
        runCatching {
            val entries = blockedEntries().toMutableSet()
            entries.add("$msgSvrId|${content.take(80)}")
            // 无序遍历的 Set 没有"最旧"概念，超出上限就整批保留最近的若干条即可。
            val trimmed = if (entries.size <= MAX_ENTRIES) entries
            else entries.toList().takeLast(MAX_ENTRIES).toSet()
            WePrefs.putStringSet(KEY_BLOCKED, trimmed)
        }.onFailure { WeLogger.w(TAG, "记录被撤回消息失败", it) }
    }

    internal fun clearBlocked() {
        runCatching { WePrefs.putStringSet(KEY_BLOCKED, emptySet()) }
    }

    /** 安装渲染钩子（幂等）。 */
    internal fun install() {
        if (installed) return
        val clazz = runCatching { CONTAINER_CLASS.toClass() }.getOrElse { ex ->
            WeLogger.w(TAG, "未找到 $CONTAINER_CLASS，圆角标签不可用（不影响防撤回）", ex)
            return
        }
        val method = runCatching { clazz.getDeclaredMethod("onAttachedToWindow") }.getOrElse { ex ->
            WeLogger.w(TAG, "ChattingItemContainer.onAttachedToWindow 不存在，圆角标签不可用", ex)
            return
        }
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { decorate(param.thisObject as? ViewGroup) }
                        .onFailure { WeLogger.w(TAG, "渲染圆角标签失败", it) }
                }
            })
        }.onFailure { ex ->
            WeLogger.w(TAG, "hook onAttachedToWindow 失败，圆角标签不可用", ex)
            return
        }
        installed = true
        WeLogger.i(TAG, "圆角标签渲染已安装")
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    private fun decorate(container: ViewGroup?) {
        container ?: return
        val entries = blockedEntries()
        val existing = container.findViewWithTag<View>(LABEL_TAG)

        val matched = entries.isNotEmpty() && matchesBlocked(container, entries)
        if (!matched) {
            existing?.let { runCatching { container.removeView(it) } }
            return
        }
        if (existing != null) return

        val label = buildLabel(container.context) ?: return
        runCatching {
            if (container is LinearLayout) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.gravity = Gravity.CENTER_VERTICAL
                lp.marginStart = dp(container.context, 4)
                container.addView(label, lp)
            } else {
                container.addView(label)
            }
        }.onFailure {
            WeLogger.w(TAG, "addView 标签失败", it)
        }
    }

    private fun matchesBlocked(container: ViewGroup, entries: Set<String>): Boolean {
        val wanted = entries.mapNotNull { it.substringAfter('|', "").takeIf { t -> t.isNotBlank() } }
        if (wanted.isEmpty()) return false

        val texts = mutableListOf<String>()
        collectTexts(container, texts, 0)
        if (texts.isEmpty()) return false

        return texts.any { text ->
            val normalized = text.trim()
            normalized.isNotEmpty() && wanted.any { it == normalized }
        }
    }

    private fun collectTexts(view: View, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        if (view is TextView) {
            // 我们自己加的标签不要再参与匹配。
            if (view.tag == LABEL_TAG) return
            view.text?.toString()?.let { out.add(it) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTexts(view.getChildAt(i), out, depth + 1)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildLabel(context: Context?): TextView? {
        context ?: return null
        val text = labelText.ifBlank { "已撤回" }
        return TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(0xFFFF2D6B.toInt())
            includeFontPadding = false
            setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 9).toFloat()
                setColor(0xFFFFEBF1.toInt())
            }
            tag = LABEL_TAG
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
