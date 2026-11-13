package com.Johnny.wcx.features.api.ui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "消息 View 创建监听服务", categories = ["API"], description = "提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    fun interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View
        )
    }

    interface IMessageViewLifecycleListener {
        fun onMessageViewAttached(view: View, message: MessageInfo) {}
        fun onMessageViewDetached(view: View, message: MessageInfo) {}
        fun onMessageViewRecycled(view: View, message: MessageInfo) {}
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val lifecycleListeners = CopyOnWriteArrayList<IMessageViewLifecycleListener>()
    private val currentBindings =
        Collections.synchronizedMap(WeakHashMap<View, MessageInfo>())
    private val attachStateListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    fun addLifecycleListener(listener: IMessageViewLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: IMessageViewLifecycleListener) {
        val removed = lifecycleListeners.remove(listener)
        WeLogger.i(
            TAG,
            "lifecycle listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${lifecycleListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    // 8.0.79/8.0.80 的原 "[onBindView]" 已是孤儿串；精确匹配仍存在的日志常量。
    private val methodChatItemOnBindView by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView] finish position:"
            )
        }
    }

    override fun onEnable() {
        if (methodChatItemOnBindView.isPlaceholder) {
            WeLogger.w(TAG, "onBindView 锚点未命中，消息徽标及 View 监听不可用")
            return
        }
        methodChatItemOnBindView.hookAfter {
            cacheAdapter(this)
            val holder = args[0] ?: return@hookAfter
            val view = holder.reflekt()
                .firstFieldOrNull {
                    type = View::class
                    superclass()
                }
                ?.get() as? View ?: return@hookAfter
            val message = runCatching { getMsgInfoFromParam(this) }.getOrElse {
                WeLogger.w(TAG, "getMsgInfoFromParam failed: ${it.message}")
                return@hookAfter
            }
            ensureAttachStateListener(view)

            val previous = synchronized(currentBindings) { currentBindings[view] }
            val bindingChanged = previous?.instance !== message.instance
            if (view.isAttachedToWindow && bindingChanged && previous != null) {
                dispatchLifecycle { it.onMessageViewDetached(view, previous) }
            }
            synchronized(currentBindings) {
                currentBindings[view] = message
            }
            if (view.isAttachedToWindow && bindingChanged) {
                dispatchLifecycle { it.onMessageViewAttached(view, message) }
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }
    }

    // 只持弱引用，避免聊天页关闭后被 API 单例长期持有；切换会话时更新。
    @Volatile private var adapterOwner: WeakReference<Any>? = null
    @Volatile private var cachedAdapter: WeakReference<Any>? = null

    private fun cacheAdapter(param: XC_MethodHook.MethodHookParam) {
        val owner = param.thisObject ?: return
        if (adapterOwner?.get() === owner && cachedAdapter?.get() != null) return
        runCatching {
            if (WeMessageApi.classChattingDataAdapter.isPlaceholder) return
            val adapter = owner.reflekt()
                .firstFieldOrNull { type = WeMessageApi.classChattingDataAdapter.clazz }
                ?.get() ?: return
            adapterOwner = WeakReference(owner)
            cachedAdapter = WeakReference(adapter)
        }.onFailure { WeLogger.w(TAG, "cacheAdapter failed: ${it.message}") }
    }

    /** 尝试按服务端 id 查可见消息；返回前校验实际 id，避免给复用的行贴错徽标。 */
    fun findMessageViewById(serverId: Long): Pair<View, MessageInfo>? {
        if (serverId == 0L || WeMessageApi.classMsgInfo.isPlaceholder) return null
        val adapter = cachedAdapter?.get() ?: return null
        return runCatching {
            val view = pickAdapterMethod(adapter, View::class.java)
                ?.invoke(adapter, serverId) as? View ?: return@runCatching null
            val raw = pickAdapterMethod(adapter, WeMessageApi.classMsgInfo.clazz)
                ?.invoke(adapter, serverId) ?: return@runCatching null
            val message = MessageInfo(raw)
            if (!view.isAttachedToWindow || message.serverId != serverId) return@runCatching null
            view to message
        }.onFailure { WeLogger.w(TAG, "findMessageViewById failed: ${it.message}") }.getOrNull()
    }

    private fun pickAdapterMethod(target: Any, returnType: Class<*>): java.lang.reflect.Method? =
        target.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                (method.parameterTypes[0] == Long::class.javaPrimitiveType ||
                    method.parameterTypes[0] == java.lang.Long::class.java) &&
                returnType.isAssignableFrom(method.returnType)
        }

    private fun ensureAttachStateListener(view: View) {
        synchronized(attachStateListeners) {
            if (attachStateListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewAttached(view, message) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewDetached(view, message) }
                }
            }
            attachStateListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
    }

    private fun dispatchLifecycle(callback: (IMessageViewLifecycleListener) -> Unit) {
        for (listener in lifecycleListeners) {
            try {
                callback(listener)
            } catch (ex: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
            }
        }
    }

    fun getChattingContextFromParam(param: XC_MethodHook.MethodHookParam): Any {
        return param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    /** Returns every (view, message) pair currently bound whose message satisfies [matcher]. */
    fun findBoundViews(matcher: (MessageInfo) -> Boolean): List<Pair<View, MessageInfo>> {
        synchronized(currentBindings) {
            return currentBindings.entries
                .filter { matcher(it.value) }
                .map { it.key to it.value }
        }
    }

    fun getBoundMessage(view: View): MessageInfo? =
        synchronized(currentBindings) { currentBindings[view] }

    fun getMsgInfoFromParam(param: XC_MethodHook.MethodHookParam): MessageInfo {
        val item = param.args[1]
        val current = item?.let { findMessageInfo(it) }
        if (current != null) return current

        val chattingDataAdapter = param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }

    private fun findMessageInfo(value: Any, depth: Int = 2): MessageInfo? {
        if (value.javaClass == WeMessageApi.classMsgInfo.clazz) {
            return MessageInfo(value)
        }
        if (depth == 0) return null

        value.reflekt().fields { superclass() }.forEach { field ->
            val nested = runCatching { field.get() }.getOrNull() ?: return@forEach
            val result = findMessageInfo(nested, depth - 1)
            if (result != null) return result
        }
        return null
    }
}
