// Adapted from AARCNoa/zhiyou, AiImageSource.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.WeMessage
import com.tencent.mm.plugin.gif.MMWXGFJNI
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

internal object AiImageSource {
    private const val MAX_SIDE = 800
    private const val MAX_SOURCE_BYTES = 12L * 1024 * 1024

    /** Messages are oldest-first. Only the latest incoming turn can supply an image. */
    fun latestDataUrl(messages: List<WeMessage>, conversationId: String, account: String): String? {
        if (conversationId.isBlank() || conversationId.endsWith("@chatroom") ||
            messages.any { it.talker != conversationId } || WeDatabaseApi.currentAiAccount() != account
        ) return null

        for (message in messages.asReversed()) {
            if (message.isSend != 0) break
            if (message.typeCode != MessageRendering.TYPE_IMAGE || message.msgSvrId <= 0) continue
            selectedDataUrl(message.msgSvrId, conversationId, account)?.let { return it }
        }
        return null
    }

    fun selectedDataUrl(serverId: Long, talker: String, account: String): String? {
        if (serverId <= 0 || WeDatabaseApi.currentAiAccount() != account) return null
        val jpeg = runCatching {
            WeMessageApi.cachedAiImagePath(serverId, talker, account)?.let(::readJpeg)
        }.getOrNull() ?: return null
        if (WeDatabaseApi.currentAiAccount() != account) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    private fun readJpeg(path: Path): ByteArray? {
        if (path.fileSize() !in 1L..MAX_SOURCE_BYTES) return null
        val bytes = path.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val raw = if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val decoded = MMWXGFJNI.wxam2PicBuf(bytes, 0, MMWXGFJNI.WXAM_SCENE_MISC) ?: return null
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size, bounds)
            decoded
        } else {
            bytes
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_SIDE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        var scaled = bitmap
        try {
            val side = maxOf(bitmap.width, bitmap.height)
            if (side > MAX_SIDE) {
                val ratio = MAX_SIDE.toFloat() / side
                scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
            return ByteArrayOutputStream().use { out ->
                if (scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)) out.toByteArray() else null
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}
