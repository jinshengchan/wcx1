// Adapted from AARCNoa/zhiyou, AiSuggestOverlay.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.Johnny.wcx.ai.AiSuggestEngine
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AiSuggestOverlay {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: Session? = null

    private class Session(val footer: ChatFooter, val talker: String, val releaseManual: () -> Unit) {
        var close: (() -> Unit)? = null
    }

    fun isShowingFor(talker: String): Boolean = active?.talker == talker

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
        if (Looper.myLooper() != main.looper) {
            main.post(::dismiss)
            return
        }
        val session = active ?: return
        active = null
        session.releaseManual()
        session.close?.invoke()
    }

    fun show(
        activity: Activity,
        footer: ChatFooter,
        access: AiSuggestEngine.Access,
        stillCurrentConversation: () -> Boolean,
    ) {
        dismiss()
        val profile = access.profile
        val session = Session(footer, profile.wxId, AIAutoReply.beginManual(profile.wxId))
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
                var refresh by remember { mutableIntStateOf(0) }
                var suggestion by remember { mutableStateOf<AiSuggestEngine.Suggestion?>(null) }
                var selected by remember { mutableIntStateOf(-1) }
                var showTrace by remember { mutableStateOf(false) }
                var sending by remember { mutableStateOf(false) }
                val sendScope = rememberCoroutineScope()
                LaunchedEffect(refresh) {
                    selected = -1
                    showTrace = false
                    suggestion = null
                    suggestion = withContext(Dispatchers.IO) {
                        AiSuggestEngine.suggest(access, canContinue = { active === session })
                    }
                }
                val result = suggestion
                val reply = result?.replies?.getOrNull(selected)
                AlertDialogContent(
                    title = { Text("AI 建议 · $title") },
                    text = {
                        DefaultColumn(Modifier.heightIn(max = 440.dp).selectableGroup(), scrollable = true) {
                            if (result == null) {
                                CircularProgressIndicator()
                                Text("正在生成回复建议…")
                            } else {
                                result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (result.read.isNotBlank()) Text(result.read)
                                result.replies.forEachIndexed { index, candidate ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().selectable(
                                            selected == index, enabled = !sending,
                                            role = Role.RadioButton, onClick = { selected = index },
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = selected == index, onClick = null)
                                        Text(candidate)
                                    }
                                }
                                if (result.error == null && result.replies.isEmpty()) Text("没有可发送的候选回复")
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
                                TextButton(enabled = !sending, onClick = {
                                    selected = -1
                                    suggestion = null
                                    refresh++
                                }) { Text("重新生成") }
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = { dismiss() }) { Text("关闭") } },
                    confirmButton = {
                        Button(enabled = reply != null && !sending, onClick = {
                            if (reply == null || active !== session || sending) return@Button
                            if (!stillCurrentConversation()) {
                                dismiss()
                                showToast("会话已变更")
                                return@Button
                            }
                            sending = true
                            sendScope.launch {
                                val sent = withContext(Dispatchers.IO) {
                                    if (active !== session || !AiSuggestEngine.isCurrent(access) || active !== session) null
                                    else WeMessageApi.sendAiReply(profile.wxId, reply)
                                }
                                if (active === session) {
                                    dismiss()
                                    showToast(when (sent) {
                                        true -> "回复已交给微信发送"
                                        false -> "发送结果未确认"
                                        null -> "好友档案或模型配置已变更"
                                    })
                                }
                            }
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
