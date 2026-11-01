package com.Johnny.wcx.features.items.chat

import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.collections.LruCache
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "自动语音转文字", categories = ["聊天"], description = "自动将语音消息转为文字")
object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private data class ConversionState(
        var attempts: Int = 0, var processing: Boolean = false,
        var completed: Boolean = false, var retryAt: Long = 0L
    )
    private val processedMessages = LruCache<Long, ConversionState>()
    private var diagnosticCount = 0
    private val diagnosedComponents = mutableSetOf<Class<*>>()

    private fun diagnostic(stage: String) {
        if (diagnosticCount++ < 20) WeLogger.i("AutoSpeechToText", stage)
    }

    override fun onEnable() {
        diagnosticCount = 0
        diagnosedComponents.clear()
        WeChatMessageViewApi.addListener(this)
        diagnostic("listener registered; conversion attempts limited to 2 per cached message")
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        if (!configuredEnabled) return
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code || msgInfo.id <= 0) return
        val state = synchronized(processedMessages) {
            processedMessages[msgInfo.id] ?: ConversionState().also { processedMessages[msgInfo.id] = it }
        }
        synchronized(state) {
            if (state.completed || state.processing || SystemClock.uptimeMillis() < state.retryAt) return
            state.processing = true
        }
        state.retryAt = SystemClock.uptimeMillis() + 30_000L
        var stage = "check-result"
        try {
            if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) == true) {
                state.completed = true
                diagnostic("conversion confirmed")
                return
            }
            if (state.attempts >= 2) return
            state.retryAt = SystemClock.uptimeMillis() + 30_000L
            stage = "resolve-component"
            val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
            val apiMan = chattingContext.reflekt().firstField {
                type = WeServiceApi.apiManagerClass
            }.get() ?: error("API manager unavailable")
            val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
            val status = api.reflekt().firstMethod {
                parameters(Long::class)
                returnType { clazz -> clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems") }
            }.invoke(msgInfo.id)
            // An in-flight/unknown host state must never cause another conversion request.
            if (status?.toString() != "NoTransform") {
                diagnostic("host conversion state busy/unknown; request skipped")
                return
            }
            stage = "request-conversion"
            if (diagnosedComponents.size < 4 && diagnosedComponents.add(api.javaClass)) {
                val candidates = generateSequence(api.javaClass) { it.superclass }.take(4)
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { it.returnType == Void.TYPE && it.parameterCount in 1..8 &&
                        it.parameterTypes.first().isAssignableFrom(msgInfo.instance.javaClass) }
                    .take(8).map { method ->
                        "${method.declaringClass.name}#${method.name}(" +
                            method.parameterTypes.joinToString { it.name } + ")"
                    }.toList()
                diagnostic("conversion component=${api.javaClass.name}; expected=(MsgInfo,boolean,int,int[,int]); candidates=$candidates")
            }
            if (!configuredEnabled) return
            val legacyConverter = api.reflekt().firstMethodOrNull {
                parameters(WeMessageApi.classMsgInfo.clazz, Boolean::class, Int::class, Int::class)
                returnType = Void.TYPE
            }
            if (legacyConverter != null) {
                state.attempts++
                legacyConverter.invoke(msgInfo.instance, false, -1, 0)
            } else {
                // 8.0.80 (3220): gp.w0 forwards the extra int to ExtTranslateVoiceEvent.
                // Its native queued-conversion caller ep.run passes 0 for this argument.
                val converter = api.reflekt().firstMethod {
                    parameters(WeMessageApi.classMsgInfo.clazz, Boolean::class, Int::class, Int::class, Int::class)
                    returnType = Void.TYPE
                }
                state.attempts++
                converter.invoke(msgInfo.instance, false, -1, 0, 0)
            }
            // A void return only acknowledges submission, not asynchronous completion.
            diagnostic("conversion submitted; waiting for host result")
        } catch (ex: Exception) {
            diagnostic("$stage failed: ${(ex.cause ?: ex).javaClass.simpleName}; retry on a later bind only")
        } finally {
            synchronized(state) { state.processing = false }
        }
    }
}

