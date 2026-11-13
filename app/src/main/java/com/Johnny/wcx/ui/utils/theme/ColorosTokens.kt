package com.Johnny.wcx.ui.utils.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Only the colors used by the independent Python and extension settings pages. */
@Immutable
data class ColorosTokens(
    val bgPage: Color,
    val textTitle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
) {
    companion object {
        val Light = ColorosTokens(
            bgPage = Color(0xFFF0F1F2),
            textTitle = Color.Black,
            textPrimary = Color(0xFF191919),
            textSecondary = Color(0xFF757575),
            textTertiary = Color(0xFFAEAEB2),
        )
        val Dark = ColorosTokens(
            bgPage = Color(0xFF181818),
            textTitle = Color.White,
            textPrimary = Color(0xFFEBEBEB),
            textSecondary = Color(0xFFA0A0A0),
            textTertiary = Color(0xFF6F6F6F),
        )
    }
}

object ColorosMetrics {
    val spacingSide = 16.dp
    val spacingRowH = 16.dp
    val spacingGroup = 16.dp
}

val LocalColorosTokens = compositionLocalOf { ColorosTokens.Light }

object ColorosTheme {
    val tokens: ColorosTokens
        @Composable get() = LocalColorosTokens.current
}
