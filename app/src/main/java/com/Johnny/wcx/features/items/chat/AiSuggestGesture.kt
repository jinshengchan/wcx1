// Adapted from AARCNoa/zhiyou, AiSuggestGesture.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiRunState
import com.Johnny.wcx.ai.AiSuggestEngine
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.ai.AiCueSound
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(name = "AI 建议双击", categories = ["API"], description = "双击好友聊天页查看 AI 回复建议")
object AiSuggestGesture : ApiFeature() {
    private val watchers = WeakHashMap<Activity, TapWatcher>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sequence = 0L

    override fun onEnable() {
        val hostBase = runCatching {
            Class.forName("com.tencent.mm.ui.MMFragmentActivity", false, ClassLoaders.HOST)
        }.getOrNull()
        listOfNotNull(Activity::class.java, hostBase).mapNotNull { type ->
            runCatching { type.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java) }.getOrNull()
        }.distinct().forEach { method ->
            method.hookBefore {
                val activity = thisObject as? Activity ?: return@hookBefore
                val event = args.firstOrNull() as? MotionEvent ?: return@hookBefore
                if (!AiConfig.isMasterEnabled || !AiToolSettings.doubleTapEnabled) return@hookBefore
                if (activity.isFinishing || activity.isDestroyed) return@hookBefore
                val watcher = watchers.getOrPut(activity) { TapWatcher(ViewConfiguration.get(activity).scaledTouchSlop) }
                if (watcher.onTouch(event)) onDoubleTap(activity, event)
            }
        }
        ChatFooter::class.reflekt().firstMethodOrNull { name = "setUserName" }?.hookAfter {
            sequence++
            AiSuggestOverlay.conversationChanged(thisObject as? ChatFooter, args.firstOrNull() as? String)
        }
    }

    override fun onDisable() {
        sequence++
        AiSuggestOverlay.dismiss()
    }

    private fun onDoubleTap(activity: Activity, event: MotionEvent) {
        if (!AiConfig.isMasterEnabled || !AiToolSettings.doubleTapEnabled) return
        val footer = visibleFooter(activity) ?: return
        val rect = Rect()
        if (footer.getGlobalVisibleRect(rect) && rect.contains(event.x.toInt(), event.y.toInt())) return
        val talker = conversation(activity, footer) ?: return
        if (AiSuggestOverlay.isShowingFor(talker)) return
        val seq = ++sequence
        scope.launch {
            val lookup = withContext(Dispatchers.IO) {
                val account = AiProfileStore.accountId() ?: return@withContext null
                val profile = AiProfileStore.readProfile(talker, account)?.takeIf { it.suggestEnabled }
                    ?: return@withContext null
                val failure = when {
                    profile.busy || AiRunState.isRunning(account, talker) -> "正在整理或审查档案，稍后再试"
                    !profile.isReady -> "好友档案尚未整理完成"
                    else -> null
                }
                val access = if (failure == null) AiSuggestEngine.access(talker, AiConfig.Purpose.FRIEND_SUGGEST, profile) else null
                Lookup(access?.takeIf { it.binding.account == account },
                    failure ?: "请在好友档案重新确认智能建议的模型")
            } ?: return@launch
            if (!isActive || !AiConfig.isMasterEnabled || !AiToolSettings.doubleTapEnabled || seq != sequence || activity.isFinishing || activity.isDestroyed ||
                visibleFooter(activity) !== footer || conversation(activity, footer) != talker) return@launch
            val access = lookup.access
            if (access == null) {
                showToast(lookup.failure)
            } else {
                AiCueSound.play()
                AiSuggestOverlay.show(activity, footer, access) {
                    visibleFooter(activity) === footer && conversation(activity, footer) == talker
                }
            }
        }
    }

    private class Lookup(val access: AiSuggestEngine.Access?, val failure: String)

    private fun visibleFooter(activity: Activity): ChatFooter? {
        val window = activity.window?.decorView ?: return null
        val rect = Rect()
        WeCurrentConversationApi.chatFooter?.let {
            if (it.rootView === window && it.isShown && it.getGlobalVisibleRect(rect)) return it
        }
        fun find(view: View): ChatFooter? {
            if (!view.isShown || !view.getGlobalVisibleRect(rect)) return null
            if (view is ChatFooter) return view
            if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return find(window)
    }

    private fun conversation(activity: Activity, footer: ChatFooter): String? {
        val page = generateSequence(footer.parent as? View) { it.parent as? View }.firstOrNull { it is ChattingUILayout }
        val current = page?.let {
            val rect = Rect()
            WeChatMessageViewApi.findBoundViews { true }.asSequence().mapNotNull { (view, message) ->
                if (!view.isShown || !view.getGlobalVisibleRect(rect) ||
                    generateSequence(view) { node -> node.parent as? View }.none { node -> node === page }) null
                else runCatching { message.talker to rect.bottom }.getOrNull()
            }.maxByOrNull { it.second }?.first
        } ?: activity.intent?.getStringExtra("Chat_User")?.takeUnless {
            activity.javaClass.name == "com.tencent.mm.ui.LauncherUI"
        }
        return current?.takeIf { it.isNotBlank() && !it.isGroupChatWxId && !it.startsWith("gh_") }
    }

    private class TapWatcher(slop: Int) {
        private val slopSquared = slop.toFloat() * slop
        private var lastEventTime = -1L
        private var lastAction = -1
        private var downAt = -1L
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var upAt = -1L
        private var upX = 0f
        private var upY = 0f

        fun onTouch(event: MotionEvent): Boolean {
            val action = event.actionMasked
            val time = event.eventTime
            if (time == lastEventTime && action == lastAction) return false
            lastEventTime = time
            lastAction = action
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = time
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> if (distance(event, downX, downY) > slopSquared) moved = true
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    downAt = -1L
                    upAt = -1L
                }
                MotionEvent.ACTION_UP -> {
                    val tap = downAt >= 0 && time - downAt <= 250 && !moved && distance(event, downX, downY) <= slopSquared
                    val doubleTap = tap && upAt >= 0 && downAt - upAt in 1L..250L &&
                        distance(event, upX, upY) <= slopSquared * 9
                    downAt = -1L
                    upAt = if (tap && !doubleTap) time else -1L
                    upX = event.x
                    upY = event.y
                    return doubleTap
                }
            }
            return false
        }

        private fun distance(event: MotionEvent, x: Float, y: Float): Float {
            val dx = event.x - x
            val dy = event.y - y
            return dx * dx + dy * dy
        }
    }
}
