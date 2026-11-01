package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.items.chat.VoiceMessagePlaybackOptimization.MODE_DISABLE
import com.Johnny.wcx.features.items.chat.VoiceMessagePlaybackOptimization.MODE_INHERIT_PROGRESS
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.RadioButtonWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.reflection.bool
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Modifier

/**
 * 语音消息播放逻辑优化。
 *
 * 微信在聊天语音播放时通过距离传感器自动切换听筒/扬声器, 其完整链路 (8.0.65-8.0.76):
 * - AutoPlay 控制器 (`com.tencent.mm.ui.chatting` 包内, 类名各版本不同:
 *   8.0.65 x0 / 8.0.67-69 y0 / 8.0.74-76 v0) 持有 SensorController;
 * - 传感器回调方法 (8.0.65 x1 / 8.0.67 s1 / 8.0.69 x1 / 8.0.74 f1 / 8.0.76 c1,
 *   参数 boolean, false=贴近耳朵 true=离开耳朵) 收到事件后延迟 50ms 调度切换任务;
 * - 切换任务类 (8.0.65 e1 / 8.0.67-69 f1 / 8.0.74-76 c1) 的 `onTimerExpired()` 执行真正切换:
 *   - 离开耳朵: setSpeakerOn(true) + switchSpeaker(), 若 RepairerConfigVoiceSpeakerSeek
 *     开启再回退 2 秒;
 *   - 贴近耳朵: setSpeakerOn(false), 若 VoiceSpeakerSeek 开启则 switchSpeaker() + 回退 2 秒,
 *     否则重新 `start play` 从头播放 — 这就是「切换后从头重播」的来源。
 *
 * 本功能两个模式:
 * - [MODE_DISABLE]: 直接拦截传感器回调, 完全禁用自动切换 (贴近/离开都不再切);
 * - [MODE_INHERIT_PROGRESS]: 仍自动切换, 但只执行 setSpeakerOn + switchSpeaker
 *   (switchSpeaker 内部对 MediaPlayer 保存 currentPosition 后 seekTo, Speex/Silk 仅重建
 *   AudioTrack 流类型), 跳过从头重播与 2 秒回退, 播放进度原样继承。
 *
 * 锚点按版本可选；启用时验证必要目标，运行时结构不明确则保留宿主行为。
 */
@Feature(
    name = "语音消息播放逻辑优化",
    categories = ["聊天"],
    description = "优化语音消息听筒/扬声器自动切换: 完全禁用自动切换, 或切换后继承播放进度不再从头重播"
)
object VoiceMessagePlaybackOptimization : ClickableFeature(), IResolveDex {

    /** 完全禁用自动切换 */
    private const val MODE_DISABLE = 0

    /** 切换后继承播放进度 */
    private const val MODE_INHERIT_PROGRESS = 1

    private var playbackMode by prefOption("voice_playback_mode", MODE_INHERIT_PROGRESS)

    /**
     * AutoPlay 的距离传感器回调 (boolean: false=贴近耳朵 true=离开耳朵)。
     * 方法名各版本不同, 用方法体内日志常量 `onSensorEvent, isON:` 锚定。
     */
    private val methodSensorCallback by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
            }
            usingEqStrings("onSensorEvent, isON:")
            paramTypes(bool)
            returnType("void")
        }
    }

    /** 切换任务入口 `onTimerExpired()`: 无参 boolean 返回, 方法名跨版本稳定。 */
    private val methodSwitchTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("speaker off")
            }
            name = "onTimerExpired"
            paramCount = 0
            returnType("boolean")
        }
    }

    /** AutoPlay.setSpeakerOn(boolean) (8.0.65-74 G / 8.0.76 H): 记录当前扬声器状态。 */
    private val methodSetSpeakerOn by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay")
            }
            usingEqStrings("speakerOn has been set %s")
            paramTypes(bool)
        }
    }

    /** AutoPlay.switchSpeaker() (8.0.65-74 K / 8.0.76 L): 无参切换, 内部继承播放位置。 */
    private val methodSwitchSpeaker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay")
            }
            usingEqStrings("switchSpeaker, isSpeakerOn: %b, isPlaying: %b")
            paramCount = 0
        }
    }

    private var diagnosticCount = 0
    private fun diagnostic(message: String) {
        if (diagnosticCount++ < 20) WeLogger.i("VoicePlayback", message)
    }

    override fun onEnable() {
        diagnosticCount = 0
        val sensor = runCatching { methodSensorCallback.method }.getOrNull()?.takeIf {
            it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
        }
        val taskMethod = runCatching { methodSwitchTask.method }.getOrNull()?.takeIf {
            it.name == "onTimerExpired" && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }
        val setSpeaker = runCatching { methodSetSpeakerOn.method }.getOrNull()?.takeIf {
            it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) && !Modifier.isStatic(it.modifiers)
        }
        val switchSpeaker = runCatching { methodSwitchSpeaker.method }.getOrNull()?.takeIf {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers)
        }
        if (playbackMode == MODE_INHERIT_PROGRESS) {
            check(taskMethod != null && setSpeaker != null && switchSpeaker != null) {
                "Playback inheritance unavailable: compatible task/speaker methods not resolved"
            }
        } else check(sensor != null || taskMethod != null) { "Voice sensor/task unavailable" }

        sensor?.hookBefore {
            if (configuredEnabled && playbackMode == MODE_DISABLE) result = null
        }
        taskMethod?.hookBefore {
            if (!configuredEnabled) return@hookBefore
            if (playbackMode == MODE_DISABLE) {
                result = false
                return@hookBefore
            }
            if (playbackMode != MODE_INHERIT_PROGRESS || setSpeaker == null || switchSpeaker == null) return@hookBefore
            val task = thisObject ?: return@hookBefore
            // Never choose the first boolean or controller field when several fit.
            val fields = generateSequence(task.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filterNot { Modifier.isStatic(it.modifiers) }.toList()
            try {
                val controllers = fields.mapNotNull { field ->
                    field.isAccessible = true
                    field.get(task)?.takeIf { setSpeaker.declaringClass.isInstance(it) && switchSpeaker.declaringClass.isInstance(it) }
                }
                val directions = fields.filter { it.type == Boolean::class.javaPrimitiveType }
                if (controllers.size != 1 || directions.size != 1) {
                    diagnostic("ambiguous controller/direction fields; host playback retained")
                    return@hookBefore
                }
                val direction = directions.single().apply { isAccessible = true }.getBoolean(task)
                setSpeaker.invoke(controllers.single(), direction)
                switchSpeaker.invoke(controllers.single())
                result = false
                diagnostic("speaker switched with host position-preserving method")
            } catch (ex: Exception) {
                diagnostic("switch failed: ${(ex.cause ?: ex).javaClass.simpleName}; host playback retained")
            }
        }
        diagnostic("hooks installed: sensor=${sensor != null} task=${taskMethod != null} inherit=${setSpeaker != null && switchSpeaker != null}")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(playbackMode) }

            AlertDialogContent(
                title = { Text("语音消息播放逻辑优化") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "切换后继承播放进度",
                                description = "自动切换继续生效，切换后从原进度继续播放",
                                selected = mode == MODE_INHERIT_PROGRESS,
                                onClick = {
                                    mode = MODE_INHERIT_PROGRESS
                                    playbackMode = MODE_INHERIT_PROGRESS
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "完全禁用自动切换",
                                description = "贴近/离开耳朵均不自动切换听筒与扬声器",
                                selected = mode == MODE_DISABLE,
                                onClick = {
                                    mode = MODE_DISABLE
                                    playbackMode = MODE_DISABLE
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }
}

