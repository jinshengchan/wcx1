// Revoke anchor adapted from Johnny520/wcx v348 (GPL-3.0).
package com.Johnny.wcx.features.items.chat

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.features.items.secret_friend.SecretFriendState
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.WeLogger
import com.tencent.wcdb.database.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(name = "语音防撤回救援", categories = ["聊天"], description = "撤回前保留已下载的语音，在消息仍存在时补回被清理的音频")
object VoiceRecallRescue : ClickableFeature(), IResolveDex {
    private const val TAG = "VoiceRecallRescue"
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private const val MAX_COPIES = 500
    private const val HOLD_COPY_PARAM = "wcx_voice_rescue_snapshot"
    private val newId = Regex("<newmsgid>\\s*(\\d+)\\s*</newmsgid>")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val methodRevokeHandler by dexMethod(allowFailure = true) {
        matcher { usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s") }
    }

    private data class Owner(val wxId: String, val db: SQLiteDatabase, val root: Path) {
        fun current() = WeDatabaseApi.isReady && WeApi.selfWxId == wxId && WeDatabaseApi.db === db &&
            RuntimeConfig.userDataDir.toAbsolutePath().normalize() == root
    }

    private fun owner(): Owner? = WeDatabaseApi.currentAiAccount()?.let { account ->
        Owner(account, WeDatabaseApi.db, RuntimeConfig.userDataDir.toAbsolutePath().normalize()).takeIf { it.current() }
    }

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (methodRevokeHandler.isPlaceholder) {
            WeLogger.w(TAG, "未定位撤回处理，自动语音备份不可用")
            return
        }
        methodRevokeHandler.hookBefore(priority = 70) {
            val id = revokeId(args) ?: return@hookBefore
            val captured = runCatching { owner() }.getOrNull() ?: return@hookBefore
            if (Looper.myLooper() != Looper.getMainLooper()) {
                runCatching { backup(captured, id) }.onFailure { WeLogger.w(TAG, "语音备份失败", it) }
            }
            setObjectExtra(HOLD_COPY_PARAM, captured)
        }
        methodRevokeHandler.hookAfter {
            val id = revokeId(args) ?: return@hookAfter
            val captured = getObjectExtra(HOLD_COPY_PARAM) as? Owner ?: return@hookAfter
            scope.launch {
                delay(1_200L)
                if (isActive) runCatching { restore(captured, id) }.onFailure {
                    WeLogger.w(TAG, "语音回填失败", it)
                }
            }
        }
    }

    override fun onDisable() { scope.cancel() }

    private fun revokeId(args: Array<Any?>): Long? =
        (args.filterIsInstance<String>().firstNotNullOfOrNull { newId.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: args.filterIsInstance<Long>().firstOrNull())?.takeIf { it > 0 }

    private fun directory(owner: Owner): Path {
        val key = MessageDigest.getInstance("SHA-256").digest(owner.wxId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return HostInfo.application.filesDir.toPath().resolve("wcx-voice-rescue").resolve(key)
    }

    private fun voicePath(owner: Owner, id: Long): Path? {
        if (!owner.current()) return null
        val path = owner.db.rawQuery(
            "SELECT imgPath, talker FROM message WHERE msgSvrId = ? AND type = 34 LIMIT 1",
            arrayOf<Any>(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val talker = cursor.getString(1).orEmpty()
            if (talker.isBlank() || talker in HideContacts.hiddenContacts || SecretFriendState.isStoredSecret(talker)) return null
            cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return null
        }
        val raw = WeMessageApi.getVoiceFullPath(path).takeIf { it.isNotBlank() } ?: return null
        val target = File(raw).toPath().toAbsolutePath().normalize()
        val root = owner.root.resolve("voice2")
        if (!owner.current() || !target.startsWith(root) || target == root ||
            !target.parent.toRealPath().startsWith(root.toRealPath())
        ) return null
        return target
    }

    @Synchronized
    private fun backup(owner: Owner, id: Long): Boolean {
        val source = voicePath(owner, id) ?: return false
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source) || Files.size(source) !in 1..MAX_BYTES) return false
        val dir = Files.createDirectories(directory(owner))
        val target = dir.resolve("$id.amr")
        val previous = dir.toFile().listFiles()?.filter { it.isFile && it.name.endsWith(".amr") }
        if (previous != null && previous.size >= MAX_COPIES && !Files.exists(target)) return false
        val temp = Files.createTempFile(dir, "voice-", ".tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            if (!owner.current()) return false
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Synchronized
    private fun restore(owner: Owner, id: Long) {
        val target = voicePath(owner, id) ?: return
        val backup = directory(owner).resolve("$id.amr")
        if (Files.exists(target) || !Files.isRegularFile(backup) || Files.size(backup) !in 1..MAX_BYTES || !owner.current()) return
        val temp = Files.createTempFile(target.parent, "wcx-voice-", ".tmp")
        try {
            Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING)
            if (owner.current()) Files.move(temp, target)
        } catch (_: FileAlreadyExistsException) {
            return
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun onClick(context: ComponentActivity) = showComposeDialog(context) {
        val scope = rememberCoroutineScope()
        val talker = remember { WeCurrentConversationApi.value }
        val account = remember { WeApi.selfWxId }
        var status by remember { mutableStateOf("正在读取语音备份…") }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            status = withContext(Dispatchers.IO) {
                owner()?.let { current -> "本账号已备份 ${directory(current).toFile().listFiles()?.count { it.name.endsWith(".amr") } ?: 0} 条" }
                    ?: "微信账号或消息库未就绪"
            }
        }
        AlertDialogContent(
            title = { Text("语音防撤回救援") },
            text = { Column {
                Text(status)
                Text("开启开关后，已下载的语音会在可用时备份到微信私有目录。可先备份当前聊天最近 50 条语音。")
            } },
            dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            val current = owner()?.takeIf { it.wxId == account } ?: return@withContext "微信账号已变化"
                            var removed = 0
                            synchronized(this@VoiceRecallRescue) {
                                directory(current).toFile().listFiles()?.forEach {
                                    if (current.current() && it.name.endsWith(".amr") && it.delete()) removed++
                                }
                            }
                            "已删除 $removed 条备份"
                        }
                        busy = false
                    }
                }) { Text("清理备份") }
                Button(enabled = !busy && talker.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching {
                                val current = owner()?.takeIf { it.wxId == account } ?: return@runCatching "微信账号已变化"
                                val ids = current.db.rawQuery(
                                    "SELECT msgSvrId FROM message WHERE talker = ? AND type = 34 AND msgSvrId > 0 ORDER BY createTime DESC LIMIT 50",
                                    arrayOf<Any>(talker),
                                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                                "已备份 ${ids.count { backup(current, it) }}/${ids.size} 条语音"
                            }.getOrElse { "归档失败，请确认聊天语音已下载" }
                        }
                        busy = false
                    }
                }) { Text("备份当前聊天") }
            },
        )
    }
}
