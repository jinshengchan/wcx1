package com.Johnny.wcx.features.items.beautify

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** WCX 自有 Compose 控件订阅主题变化，不改变原功能的开关。 */
internal object ThemeOwnedAppearance {
    private val changes = MutableStateFlow(0L)
    fun invalidate() { changes.update { it + 1 } }

    @Composable
    fun current(): ThemeRuntime.Effective? {
        val context = LocalContext.current
        val revision by changes.collectAsState()
        return remember(context, revision) { ThemeRuntime.current(context) }
    }

    fun color(snapshot: ThemeRuntime.Effective?, section: String, key: String, fallback: Color): Color =
        (snapshot?.profile?.color(section, key) as? ColorRule.Value)?.let { Color(it.argb) } ?: fallback
}

@Composable
internal fun ThemeOwnedBackground(
    snapshot: ThemeRuntime.Effective?,
    section: String,
    key: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val configured = snapshot?.profile?.background(section, key)
    val legacy = remember(snapshot, section, key) {
        if (section == "drawer" && key == "whole" && configured === BackgroundRule.Inherit) {
            snapshot?.profile?.toJson()?.optString("wallpaper_panel")?.takeIf { it.isNotBlank() }?.let {
                BackgroundRule.Image(it, com.Johnny.wcx.preferences.WePrefs.getIntOrDef("theme_panel_alpha", 100).coerceIn(0, 100) / 100f)
            }
        } else null
    }
    val rule = legacy ?: configured
    val color = when (rule) {
        is BackgroundRule.Solid -> Color(rule.argb)
        BackgroundRule.Transparent -> Color.Transparent
        else -> null
    }
    val file = (rule as? BackgroundRule.Image)?.let { snapshot?.asset(it.asset) }
    Box(if (color != null) modifier.background(color) else modifier) {
        if (file != null && rule is BackgroundRule.Image) {
            Image(
                painter = rememberAsyncImagePainter(file),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = rule.opacity,
            )
        }
        content()
    }
}

@Composable
internal fun ThemeOwnedFabIcon(snapshot: ThemeRuntime.Effective?, fallback: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val file = snapshot?.profile?.fabIcon()?.let { snapshot?.asset(it) }
    if (file == null) {
        androidx.compose.material3.Icon(fallback, contentDescription = null, tint = tint, modifier = modifier)
    } else {
        val painter = rememberAsyncImagePainter(file)
        val state by painter.state.collectAsState()
        if (state is coil3.compose.AsyncImagePainter.State.Success) {
            Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
        } else {
            androidx.compose.material3.Icon(fallback, contentDescription = null, tint = tint, modifier = modifier)
        }
    }
}
