// Adapted from AARCNoa/zhiyou, AiCueSound.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.Johnny.wcx.preferences.WePrefs
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal object AiCueSound {
    enum class Style(val id: String, val label: String) {
        WOOD("wood", "木质"),
        DEEP("deep", "低沉"),
    }

    private const val RATE = 44_100
    private const val ENABLED = "ai_workbench_cue_sound"
    private const val STYLE = "ai_workbench_cue_style"
    private val playing = AtomicBoolean()

    var enabled: Boolean
        get() = WePrefs.getStringOrDef(ENABLED, "true") == "true"
        set(value) = WePrefs.putString(ENABLED, value.toString())

    var style: Style
        get() = WePrefs.getStringOrDef(STYLE, "").let { id ->
            Style.entries.firstOrNull { it.id == id } ?: Style.WOOD
        }
        set(value) = WePrefs.putString(STYLE, value.id)

    fun play() {
        if (enabled) preview(style)
    }

    fun preview(style: Style) {
        if (!playing.compareAndSet(false, true)) return
        Thread({
            try {
                runCatching { playNow(style) }
            } finally {
                playing.set(false)
            }
        }, "wcx-ai-cue").apply { isDaemon = true }.start()
    }

    private fun playNow(style: Style) {
        val length = RATE * (if (style == Style.WOOD) 220 else 460) / 1000
        val pcm = ShortArray(length) { index ->
            val time = index.toDouble() / RATE
            val value = when (style) {
                Style.WOOD -> {
                    val fundamental = sin(2 * PI * 300 * time) * exp(-38 * time)
                    val partial = sin(2 * PI * 600 * time) * exp(-55 * time) * 0.18
                    (fundamental + partial) * (time / 0.0015).coerceAtMost(1.0) * 0.34
                }
                Style.DEEP -> {
                    val sweep = time.coerceAtMost(0.13)
                    val cycle = 160 * sweep + 180 * sweep * sweep / 0.26 + 340 * (time - sweep)
                    sin(2 * PI * cycle) * exp(-8.5 * time) * (time / 0.008).coerceAtMost(1.0) * 0.38
                }
            }
            (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            if (track.write(pcm, 0, pcm.size) > 0) {
                track.play()
                Thread.sleep(length * 1000L / RATE + 120)
            }
        } finally {
            track.release()
        }
    }
}
