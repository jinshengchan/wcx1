package com.Johnny.wcx.features.items.system

import android.content.Context
import android.view.View
import androidx.compose.material3.Text
import androidx.core.view.isVisible
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Modifier

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    // 8.0.80: UIUtils.C() wraps the cached tablet-environment helper b(...).
    // Match the wrapper: forcing only a model whitelist or the uncached helper
    // leaves callers seeing the host's previously cached false result.
    private val methodIsTablet by dexMethod {
        matcher {
            paramCount = 0
            returnType("boolean")
            invokeMethods {
                add {
                    usingEqStrings("inTabletEnv, isFoldableDevice, return false")
                    returnType("boolean")
                }
                matchType = MatchType.Contains
            }
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
            returnType("void")
        }
    }

    override fun onEnable() {
        val tablet = methodIsTablet.method
        val loginEntry = methodOtherDeviceLoginButtonIsVisible.method
        check(tablet.returnType == Boolean::class.javaPrimitiveType &&
            tablet.parameterCount == 0 && Modifier.isStatic(tablet.modifiers)) {
            "Tablet environment target has an incompatible signature"
        }
        check(loginEntry.returnType == Void.TYPE && loginEntry.parameterCount > 0 &&
            View::class.java.isAssignableFrom(loginEntry.parameterTypes[0])) {
            "Other-device login entry has an incompatible signature"
        }

        tablet.hookBefore {
            if (configuredEnabled) result = true
        }
        loginEntry.hookAfter {
            if (!configuredEnabled) return@hookAfter
            val view = args.firstOrNull() as? View ?: return@hookAfter
            view.isVisible = true
        }
        WeLogger.i("ForceTabletMode", "hooks installed: tablet=${tablet.declaringClass.name}#${tablet.name}, " +
            "loginEntry=${loginEntry.declaringClass.name}#${loginEntry.name}")
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
