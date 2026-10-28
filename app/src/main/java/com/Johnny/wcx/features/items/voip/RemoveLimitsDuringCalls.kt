package com.Johnny.wcx.features.items.voip

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Modifier

@Feature(name = "移除通话时聊天限制", categories = ["聊天", "音视频通话"], description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    private const val TAG = "RemoveLimitsDuringCalls"

    override fun onEnable() {
        val targets = listOf(
            methodIsDuringCall,
            methodIsMultiTalking,
            methodIsCameraUsing,
            methodIsCameraUsing2,
            methodIsVoiceUsing,
            methodIsVoiceUsing2,
            methodCheckAppBrandVoiceUsing,
            methodCheckAppBrandVoiceUsing2,
            methodCheckDeviceUsing,
            methodCheckAudioDeviceUsing,
            methodCheckSpeakerUsing,
        ).mapNotNull { target ->
            // Optional lookups can contain a placeholder or a stale cached descriptor.
            // Resolve before installing hooks so one missing target does not discard the rest.
            val method = runCatching { target.method }.getOrNull()
            if (method == null ||
                (method.returnType != Boolean::class.javaPrimitiveType &&
                    method.returnType != java.lang.Boolean::class.java)
            ) {
                WeLogger.w(TAG, "skip unavailable/non-boolean target: ${target.key}")
                null
            } else method
        }.distinct()
        check(targets.isNotEmpty()) { "No compatible call restriction methods found" }
        targets.forEach { target ->
            target.hookBefore {
                if (configuredEnabled) result = false
            }
        }
        WeLogger.i(TAG, "installed ${targets.size} call restriction hook(s)")
    }

    // 8.0.77 通话栈加固: 全部 dexMethod 使用 allowFailure, 类被移除/混淆时降级为 placeholder,
    // 避免 Dex 扫描阶段抛异常拖垮整个模块启动。
    private val methodIsDuringCall by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                modifiers(Modifier.ABSTRACT)
            }

            modifiers(Modifier.STATIC)
            paramCount = 0
            returnType = "boolean"

            addInvoke {
                declaredClass = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
            }
        }
    }
    private val methodIsMultiTalking by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 1
        }
    }

    private val methodIsCameraUsing by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        }
    }
    private val methodIsCameraUsing2 by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
        }
    }
    private val methodIsVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 1
        }
    }
    private val methodIsVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 2
        }
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 1
        }
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            returnType = "boolean"
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 2
        }
    }

    // Additional device occupancy checks that may block voice message playback during calls.
    // These cover methods beyond the core set above that some WeChat versions use.
    private val methodCheckDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.DeviceOccupy", "checkDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckAudioDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAudioDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckSpeakerUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.DeviceOccupy", "checkSpeakerUsing")
            returnType = "boolean"
        }
    }
}

