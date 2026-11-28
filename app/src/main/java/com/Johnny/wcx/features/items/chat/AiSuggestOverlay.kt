// Adapted from AARCNoa/zhiyou, AiSuggestOverlay.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.Johnny.wcx.ai.AiSuggestEngine
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AiSuggestOverlay {
    private const val DUPLICATE_TAP_MS = 80L
    private const val VISIBILITY_CHECK_MS = 250L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Session? = null

    private class Session(
        val footer: ChatFooter,
        val talker: String,
        val releaseManual: () -> Unit,
        val isChatVisible: () -> Boolean,
    ) {
        var close: (() -> Unit)? = null
        var refresh by mutableIntStateOf(0)
        var sending by mutableStateOf(false)
        var lastTapRefreshAt = -DUPLICATE_TAP_MS
    }

    fun refreshFor(footer: ChatFooter, talker: String): Boolean {
        val session = active?.takeIf { it.footer === footer && it.talker == talker } ?: return false
        refresh(session, fromTap = true)
        return true
    }

    private fun refresh(session: Session, fromTap: Boolean) {
        if (active !== session || session.sending) return
        if (!AiConfig.isMasterEnabled || !session.isChatVisible()) {
            dismiss()
            return
        }
        if (fromTap) {
            if (!AiToolSettings.doubleTapEnabled) return
            val now = SystemClock.uptimeMillis()
            if (now - session.lastTapRefreshAt < DUPLICATE_TAP_MS) return
            session.lastTapRefreshAt = now
        }
        session.refresh++
    }

    private fun Modifier.refreshOnDoubleTap(session: Session): Modifier = pointerInput(session) {
        val slop = viewConfiguration.touchSlop
        val timeout = viewConfiguration.doubleTapTimeoutMillis
        var previousTap: PointerInputChange? = null
        // Observe content taps without consuming events needed by candidate selection and scrolling.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pointer: PointerInputChange
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull()
                if (change == null || change.id != down.id || change.isConsumed ||
                    change.uptimeMillis - down.uptimeMillis > timeout ||
                    (change.position - down.position).getDistance() > slop) {
                    previousTap = null
                    return@awaitEachGesture
                }
                pointer = change
            } while (pointer.pressed)
            val first = previousTap
            val tapGaps = viewConfiguration.doubleTapMinTimeMillis..timeout
            if (first != null && down.uptimeMillis - first.uptimeMillis in tapGaps &&
                (pointer.position - first.position).getDistance() <= slop * 3) {
                previousTap = null
                refresh(session, fromTap = true)
            } else {
                previousTap = pointer
            }
        }
    }

    fun conversationChanged(footer: ChatFooter?, talker: String?) {
        val session = active ?: return
        if (session.talker != talker && (session.footer === footer ||
                (footer?.isShown == true && footer.rootView === session.footer.rootView))) dismiss()
    }

    fun dismissFor(talker: String) {
        val session = active?.takeIf { it.talker == talker } ?: return
        main.post { if (active === session) dismiss() }
    }

    fun dismiss() {
        val session = active ?: return
        if (Looper.myLooper() != main.looper) {
            main.post { if (active === session) dismiss() }
            return
        }
        active = null
        session.releaseManual()
        session.close?.invoke()
    }

    fun show(
        activity: Activity,
        footer: ChatFooter,
        access: AiSuggestEngine.Access,
        isChatVisible: () -> Boolean,
        stillCurrentConversation: () -> Boolean,
    ) {
        dismiss()
        val profile = access.profile
        val session = Session(footer, profile.wxId, AIAutoReply.beginManual(profile.wxId), isChatVisible)
        active = session
        val title = profile.remark.ifBlank { profile.nickname }.ifBlank { "好友" }
        try {
            showComposeDialog(activity) {
                DisposableEffect(session) {
                    session.close = onDismiss
                    val attachment = object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {}
                        override fun onViewDetachedFromWindow(view: View) {
                            if (active === session) dismiss()
                        }
                    }
                    footer.addOnAttachStateChangeListener(attachment)
                    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
                    val observer = LifecycleEventObserver { _, event ->
                        if ((event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) &&
                            active === session) dismiss()
                    }
                    lifecycle?.addObserver(observer)
                    if (active !== session) onDismiss()
                    onDispose {
                        footer.removeOnAttachStateChangeListener(attachment)
                        lifecycle?.removeObserver(observer)
                        session.close = null
                        if (active === session) dismiss()
                    }
                }
                LaunchedEffect(session) {
                    while (active === session) {
                        if (!AiConfig.isMasterEnabled || !session.isChatVisible()) {
                            dismiss()
                            break
                        }
                        delay(VISIBILITY_CHECK_MS)
                    }
                }
                val generation = session.refresh
                var suggestion by remember(generation) { mutableStateOf<AiSuggestEngine.Suggestion?>(null) }
                var selected by remember(generation) { mutableIntStateOf(-1) }
                var showTrace by remember(generation) { mutableStateOf(false) }
                val sendScope = rememberCoroutineScope()
                LaunchedEffect(generation) {
                    val result = withContext(Dispatchers.IO) {
                        AiSuggestEngine.suggest(access, canContinue = {
                            active === session && session.refresh == generation
                        })
                    }
                    if (active === session && session.refresh == generation) suggestion = result
                }
                val result = suggestion
                val reply = result?.replies?.getOrNull(selected)
                fun applyReply(send: Boolean) {
                    if (reply == null || active !== session || session.sending ||
                        session.refresh != generation) return
                    if (!session.isChatVisible() || !stillCurrentConversation()) {
                        dismiss()
                        showToast("会话已变更")
                        return
                    }
                    session.sending = true
                    sendScope.launch {
                        val authorized = withContext(Dispatchers.IO) {
                            active === session && AiSuggestEngine.isCurrent(access)
                        }
                        if (active !== session) return@launch
                        if (!session.isChatVisible() || !stillCurrentConversation()) {
                            dismiss()
                            showToast("会话已变更")
                            return@launch
                        }
                        val binding = access.binding
                        val selection = if (authorized && AiConfig.isMasterEnabled) {
                            AiConfig.selection(binding.purpose)
                        } else null
                        if (selection == null || selection.account != binding.account || selection.id != binding.id) {
                            dismiss()
                            showToast("好友档案或模型配置已变更")
                            return@launch
                        }
                        if (send) {
                            val sent = WeMessageApi.sendAiReply(profile.wxId, reply)
                            dismiss()
                            showToast(if (sent) "回复已交给微信发送" else "发送结果未确认")
                            return@launch
                        }
                        val filled = runCatching {
                            if (footer.lastText.isNotBlank()) false else {
                                footer.setMode(1)
                                footer.lastText = reply
                                true
                            }
                        }.getOrNull()
                        if (filled == true) dismiss() else {
                            session.sending = false
                            showToast(if (filled == false) "输入框已有草稿，请先处理草稿" else "无法填入输入框")
                        }
                    }
                }
                AlertDialogContent(
                    title = { Text("AI 建议 · $title") },
                    text = {
                        DefaultColumn(Modifier.heightIn(max = 440.dp), scrollable = true) {
                            DefaultColumn(Modifier.fillMaxWidth().selectableGroup().refreshOnDoubleTap(session)) {
                                if (result == null) {
                                    CircularProgressIndicator()
                                    Text("正在生成回复建议…")
                                } else {
                                    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (result.read.isNotBlank()) Text(result.read)
                                    result.replies.forEachIndexed { index, candidate ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().selectable(
                                                selected == index, enabled = !session.sending,
                                                role = Role.RadioButton, onClick = { selected = index },
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = selected == index, onClick = null)
                                            Text(candidate)
                                        }
                                    }
                                    if (result.error == null && result.replies.isEmpty()) Text("没有可发送的候选回复")
                                }
                            }
                            if (result != null) {
                                if (result.steps.isNotEmpty()) {
                                    TextButton(onClick = { showTrace = !showTrace }) {
                                        Text(if (showTrace) "收起生成过程" else "查看生成过程")
                                    }
                                    if (showTrace) result.steps.forEach { step ->
                                        Text(buildString {
                                            appendLine("第 ${step.round} 轮")
                                            step.reasoning?.let { appendLine(it) }
                                            append(step.actions.joinToString(" · "))
                                        })
                                    }
                                }
                                TextButton(enabled = !session.sending, onClick = { refresh(session, fromTap = false) }) {
                                    Text("重新生成")
                                }
                                TextButton(enabled = reply != null && !session.sending, onClick = {
                                    applyReply(send = false)
                                }) { Text("填入输入框") }
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = { dismiss() }) { Text("关闭") } },
                    confirmButton = {
                        Button(enabled = reply != null && !session.sending, onClick = {
                            applyReply(send = true)
                        }) { Text("发送所选") }
                    },
                )
            }
        } catch (_: Exception) {
            dismiss()
            showToast("无法打开 AI 建议")
        }
    }
}
