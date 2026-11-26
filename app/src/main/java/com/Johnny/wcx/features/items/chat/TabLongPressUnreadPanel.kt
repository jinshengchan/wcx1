// WeChat tab long-press anchor adapted from Johnny520/wcx v348 (GPL-3.0).
package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.batch.BatchConversation
import com.Johnny.wcx.features.items.batch.ConversationBatchData
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.baseActivity
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.tencent.mm.ui.LauncherUI
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@Feature(
    name = "长按微信页签看未读",
    categories = ["聊天"],
    description = "查看未读会话，可点开会话或将面板中的未读标为已读",
)
object TabLongPressUnreadPanel : SwitchFeature(), IResolveDex {
    private const val TAG = "TabLongPressUnreadPanel"
    private val showing = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var closePanel: (() -> Unit)? = null
    private val wrappedTabs = WeakHashMap<View, WeakReference<TabListener>>()

    private val methodMainTabLongClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.LauncherUITabView", "onMainTabLongClick")
            paramTypes("android.view.View")
        }
    }

    private val classBottomTabView by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.LauncherUITabView", "[updateMainTabUnread] unread : ")
        }
    }

    override fun onEnable() {
        if (!methodMainTabLongClick.isPlaceholder) {
            methodMainTabLongClick.hookAfter {
                val tab = args.firstOrNull() as? View ?: return@hookAfter
                if (tab.tag == 0) openFromTab(tab)
            }
            return
        }

        val setTo = runCatching {
            val hostClass = if (classBottomTabView.isPlaceholder) {
                Class.forName("com.tencent.mm.ui.LauncherUIBottomTabView", false, ClassLoaders.HOST)
            } else classBottomTabView.clazz
            hostClass.getDeclaredMethod("setTo", Int::class.javaPrimitiveType!!)
        }.getOrNull()
        if (setTo == null) {
            WeLogger.w(TAG, "当前版本没有可用的微信页签长按入口")
            return
        }
        setTo.hookAfter {
            val root = thisObject as? ViewGroup ?: return@hookAfter
            installFallback(root)
        }
    }

    override fun onDisable() {
        mainHandler.post {
            if (isActive) return@post
            closePanel?.invoke()
            for ((tab, reference) in wrappedTabs) {
                val wrapper = reference.get() ?: continue
                if (listenerOf(tab) === wrapper) tab.setOnLongClickListener(wrapper.original)
            }
            wrappedTabs.clear()
        }
    }

    private class TabListener(val original: View.OnLongClickListener) : View.OnLongClickListener {
        override fun onLongClick(view: View): Boolean {
            val consumed = original.onLongClick(view)
            return openFromTab(view) || consumed
        }
    }

    private fun installFallback(root: ViewGroup) {
        if (!isEnabled) return
        val container = (root.getChildAt(0) as? ViewGroup) ?: root
        val tab = container.getChildAt(0) ?: return
        if (tab.tag != 0) return
        val original = listenerOf(tab) ?: return
        if (original is TabListener) return
        val wrapper = TabListener(original)
        tab.setOnLongClickListener(wrapper)
        wrappedTabs[tab] = WeakReference(wrapper)
    }

    private fun listenerOf(tab: View): View.OnLongClickListener? = runCatching {
        val listeners = View::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }.get(tab) ?: return null
        listeners.javaClass.getDeclaredField("mOnLongClickListener")
            .apply { isAccessible = true }.get(listeners) as? View.OnLongClickListener
    }.getOrNull()

    private fun openFromTab(tab: View): Boolean {
        if (!isEnabled || !isActive || tab.tag != 0) return false
        val activity = tab.context.baseActivity ?: return false
        if (activity !is LauncherUI || activity.isFinishing || activity.isDestroyed) return false
        if (!showing.compareAndSet(false, true)) return true
        return try {
            showPanel(activity)
            true
        } catch (e: Exception) {
            showing.set(false)
            WeLogger.w(TAG, "未读面板无法打开", e)
            false
        }
    }

    private fun showPanel(activity: Activity) = showComposeDialog(activity) {
        DisposableEffect(Unit) {
            closePanel = onDismiss
            onDispose {
                closePanel = null
                showing.set(false)
            }
        }
        val scope = rememberCoroutineScope()
        val preview by produceState<Result<Pair<ConversationBatchData, List<BatchConversation>>>?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val source = ConversationBatchData.capture()
                    source to source.loadConversations(unreadOnly = true)
                }
            }
        }
        val source = preview?.getOrNull()?.first
        val rows = preview?.getOrNull()?.second.orEmpty()
        val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        var marking by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        AlertDialogContent(
            title = { Text(if (rows.isEmpty()) "未读会话" else "未读会话（${rows.size}）") },
            text = {
                Column {
                    when {
                        marking -> Text("正在标为已读…")
                        result != null -> Text(result.orEmpty())
                        preview == null -> Text("正在读取未读会话…")
                        source == null -> Text("读取失败，请确认微信已登录")
                        rows.isEmpty() -> Text("当前没有可显示的未读会话")
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 370.dp)) {
                            items(rows, key = { it.wxId }) { row ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (!source.isCurrent() || !source.isVisible(row.wxId)) {
                                            showToast(activity, "会话或微信账号已变化")
                                        } else {
                                            onDismiss()
                                            runCatching { WeApi.openContact(activity, row.wxId, WeApi.OpenContactDestination.CONVERSATION) }
                                                .onFailure { showToast(activity, "暂时无法打开会话") }
                                        }
                                    },
                                    headlineContent = { Text(row.displayName) },
                                    supportingContent = {
                                        val time = row.lastActive
                                        val formatted = if (time > 0) {
                                            formatter.format(Date(if (time < 10_000_000_000L) time * 1000 else time))
                                        } else ""
                                        Text(listOf(row.category.label, formatted).filter { it.isNotBlank() }.joinToString(" · "))
                                    },
                                    trailingContent = { Text(row.unread.toString()) },
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onDismiss, enabled = !marking) { Text("关闭") } },
            confirmButton = {
                if (source != null && rows.isNotEmpty() && result == null) {
                    Button(enabled = !marking, onClick = {
                        marking = true
                        scope.launch {
                            try {
                                val selected = rows.mapTo(linkedSetOf()) { it.wxId }
                                val before = withContext(Dispatchers.IO) { source.stillUnread(selected) }
                                val requested = linkedSetOf<String>()
                                for ((index, id) in before.withIndex()) {
                                    check(isEnabled && source.isCurrent()) { "微信账号或功能状态已变化" }
                                    if (source.isVisible(id)) {
                                        WeConversationApi.markAsRead(id)
                                        requested += id
                                    }
                                    if ((index + 1) % 16 == 0) yield()
                                }
                                if (source.isCurrent()) WeConversationApi.reloadConversations()
                                val stillUnread = withContext(Dispatchers.IO) { source.stillUnread(requested).size }
                                result = "已标为已读 ${requested.size - stillUnread} 个，仍有未读 $stillUnread 个"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                result = "无法确认已读结果，请返回微信会话列表查看"
                                WeLogger.w(TAG, "批量标为已读未能确认", e)
                            } finally {
                                marking = false
                            }
                        }
                    }) { Text("面板内全部已读") }
                }
            },
        )
    }
}
