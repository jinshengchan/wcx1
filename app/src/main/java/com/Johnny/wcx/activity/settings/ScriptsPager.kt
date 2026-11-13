package com.Johnny.wcx.activity.settings

import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.items.scripting_python.PythonScriptingFeature
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.ui.miuix.ScriptPluginMiuixTabContent
import h.Hchat.ui.miuix.ScriptPluginReadmeTabDialog

/**
 * 「脚本」独立页 —— WCX 专属脚本插件中心。
 *
 * 子页面（在线插件 / 本地插件管理 / 脚本功能诊断）通过设置页导航栈整页跳转，
 * 与 Hchat 的观感一致（不再使用 Dialog 覆盖层）。
 */
@Composable
fun ScriptsPager(
    onOpenMarket: () -> Unit = {},
    onOpenManager: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenFaq: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalComponentActivity.current
    var pythonEnabled by remember { mutableStateOf(PythonScriptingFeature.configuredEnabled) }
    var readmePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }

    MiuixListScaffold(title = "脚本", blurTopBar = false, horizontalPadding = 0.dp) {
        item {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ListItem(
                    modifier = Modifier.clickable { PythonScriptingFeature.onClick(activity) },
                    headlineContent = { Text("Python 脚本") },
                    supportingContent = { Text(if (Process.is64Bit()) "插件与运行时管理（需单独安装运行时）" else "此设备不支持 Python 运行时") },
                    trailingContent = {
                        Switch(
                            checked = pythonEnabled,
                            enabled = Process.is64Bit(),
                            onCheckedChange = { checked ->
                                PythonScriptingFeature.applyToggle(checked)
                                pythonEnabled = checked
                            },
                        )
                    },
                )
            }
        }
        item {
            // 父级 Pager 与内层 TopAppBar 各有一层 backdrop；内容滚动越界会被采样成顶部灰块，这里裁剪掉溢出绘制。
            Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                ScriptPluginMiuixTabContent(
                    context = context,
                    onOpenManager = onOpenManager,
                    onOpenReadme = { plugin -> readmePlugin = plugin },
                    onOpenMarket = onOpenMarket,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenFaq = onOpenFaq,
                )
            }
        }
        item {
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }

    val pending = readmePlugin
    if (pending != null) {
        ScriptPluginReadmeTabDialog(
            context = context,
            plugin = pending,
            onClose = { readmePlugin = null },
        )
    }
}
