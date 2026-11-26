// Native batch unsubscribe anchor adapted from Johnny520/wcx v348 (GPL-3.0).
package com.Johnny.wcx.features.items.batch

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.baseActivity
import com.Johnny.wcx.utils.android.showToast
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Feature(
    name = "批量取关公众号",
    categories = ["批量操作"],
    description = "选择当前已关注的公众号，经确认后调用微信原生批量取关",
)
object BatchUnsubscribeOfficialAccounts : ClickableFeature(), IResolveDex {
    override val noSwitchWidget = true
    private const val TAG = "BatchUnsubscribeOfficialAccounts"
    private val running = AtomicBoolean(false)

    private val classBizPlugin by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.FlutterBizConversationPlugin", "batch unsubscribe finished, reload now")
        }
    }

    override fun onClick(context: ComponentActivity) {
        if (classBizPlugin.isPlaceholder) {
            showToast(context, "当前微信版本的原生批量取关不可用")
            return
        }
        if (running.get()) {
            showToast(context, "上一批取关仍在等待微信处理")
            return
        }
        showComposeDialog(context) {
            val result by produceState<Result<Pair<ConversationBatchData, List<BatchConversation>>>?>(null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = ConversationBatchData.capture()
                        source to source.loadFollowed().sortedBy { it.displayName }
                    }
                }
            }
            val loaded = result?.getOrNull()
            if (loaded != null && loaded.second.isNotEmpty()) {
                val (source, accounts) = loaded
                ContactsSelector(
                    title = "选择要取关的公众号",
                    contacts = accounts,
                    initialSelectedWxIds = emptySet(),
                    onDismiss = onDismiss,
                    onConfirm = { ids ->
                        val selected = accounts.filter { it.wxId in ids }
                        if (selected.isEmpty()) {
                            showToast(context, "请至少选择一个公众号")
                        } else {
                            val closeSelection = onDismiss
                            confirmConversationBatch(context, "取关公众号", selected,
                                "微信将取消关注这些公众号。", "确认取关",
                            ) {
                                closeSelection()
                                submit(context, source, selected)
                            }
                        }
                    },
                )
            } else {
                AlertDialogContent(
                    title = { Text("批量取关公众号") },
                    text = {
                        Text(when {
                            result == null -> "正在读取已关注的公众号…"
                            loaded == null -> "读取公众号失败，请确认微信已登录"
                            else -> "没有可操作的已关注公众号"
                        })
                    },
                    confirmButton = { TextButton(onDismiss) { Text("关闭") } },
                )
            }
        }
    }

    private fun submit(context: Context, source: ConversationBatchData, rows: List<BatchConversation>) {
        val host = context.baseActivity ?: return
        if (!running.compareAndSet(false, true)) return
        try {
            showComposeDialog(context, directlyDismissable = false) {
                var status by remember { mutableStateOf("正在核对确认名单…") }
                var details by remember { mutableStateOf("") }
                var finished by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    var invoked = false
                    try {
                        val ids = rows.mapTo(linkedSetOf()) { it.wxId }
                        val followed = withContext(Dispatchers.IO) { source.stillFollowed(ids) }
                        val selected = ids.filter { it in followed && source.isVisible(it) }
                        check(source.isCurrent() && !host.isFinishing && !host.isDestroyed)
                        if (selected.isEmpty()) {
                            status = "名单中的公众号已取关或不可操作"
                            return@LaunchedEffect
                        }
                        invokeNative(host, selected) { invoked = true }
                        status = "微信已接收请求，正在核对本地关注状态…"
                        var pending = selected.toSet()
                        for (attempt in 0 until 10) {
                            delay(1_000L)
                            pending = withContext(Dispatchers.IO) { source.stillFollowed(pending) }
                            if (pending.isEmpty()) break
                        }
                        status = "取关状态核对完成"
                        details = "本地显示已取关 ${selected.size - pending.size} 个，待确认 ${pending.size} 个，跳过 ${ids.size - selected.size} 个"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        WeLogger.w(TAG, "微信原生取关未能确认", e)
                        status = if (invoked) "无法确认取关结果，请重新打开公众号列表查看"
                            else "微信账号或页面已变化，取关未提交"
                    } finally {
                        running.set(false)
                        finished = true
                    }
                }
                AlertDialogContent(
                    title = { Text("批量取关公众号") },
                    text = { Column {
                        Text(status)
                        if (details.isNotBlank()) Text(details)
                    } },
                    confirmButton = { if (finished) Button(onDismiss) { Text("完成") } },
                )
            }
        } catch (e: Exception) {
            running.set(false)
            WeLogger.w(TAG, "无法显示取关进度", e)
        }
    }

    private fun invokeNative(activity: Activity, usernames: List<String>, onInvoked: () -> Unit) {
        check(!classBizPlugin.isPlaceholder)
        val clazz = classBizPlugin.clazz
        val ctor = clazz.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
        val activityField = clazz.declaredFields.single { it.type == Activity::class.java }
        val method = clazz.declaredMethods.single {
            it.name == "unSubscribeAllContacts" && it.parameterTypes.contentEquals(arrayOf(List::class.java))
        }
        ctor.isAccessible = true
        val plugin = ctor.newInstance(0)
        activityField.isAccessible = true
        activityField.set(plugin, activity)
        method.isAccessible = true
        onInvoked()
        method.invoke(plugin, ArrayList(usernames))
    }
}
