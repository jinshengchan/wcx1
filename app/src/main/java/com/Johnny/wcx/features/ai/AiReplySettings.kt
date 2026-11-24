package com.Johnny.wcx.features.ai

import com.Johnny.wcx.preferences.WePrefs

internal enum class ReasoningEffort(val id: String, val label: String) {
    OFF("", "关闭"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    MAX("max", "极限"),
}

internal data class AiReplyOptions(
    val effort: ReasoningEffort = ReasoningEffort.OFF,
    val maxTokens: Int = 0,
    val temperature: Double = 0.9,
)

internal object AiReplySettings {
    private const val OPTIONS = "ai_workbench_reply_options"
    const val MAX_OUTPUT_TOKENS = 4_000_000

    fun read(): AiReplyOptions {
        val parts = WePrefs.getStringOrDef(OPTIONS, "").split('|')
        return AiReplyOptions(
            ReasoningEffort.entries.firstOrNull { it.id == parts.first() } ?: ReasoningEffort.OFF,
            parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, MAX_OUTPUT_TOKENS) ?: 0,
            parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it in 0.0..2.0 } ?: 0.9,
        )
    }

    fun save(options: AiReplyOptions) {
        val (effort, maxTokens, temperature) = options
        WePrefs.putString(OPTIONS, "${effort.id}|$maxTokens|$temperature")
    }
}
