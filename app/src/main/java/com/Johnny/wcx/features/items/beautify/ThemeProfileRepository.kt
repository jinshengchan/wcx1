package com.Johnny.wcx.features.items.beautify

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 每套主题独立保存；只有完整配置和素材校验成功后才发布新快照。 */
internal class ThemeProfileRepository(private val root: File) {
    data class Seed(val manifest: JSONObject, val asset: (String) -> File?)
    class Snapshot internal constructor(
        val themeId: String,
        val revision: Long,
        val profile: ThemeProfile,
        private val assets: Map<String, File>
    ) {
        val mainTextColorEnabled = profile.toJson().getJSONObject("appearance")
            .optJSONObject("compatibility")?.optBoolean("mainTextColorEnabled", true) ?: true
        fun asset(relative: String): File? = assets[relative]
    }

    private val cache = mutableMapOf<String, Snapshot>()
    private var revision = 0L

    @Synchronized
    fun read(id: String, seed: () -> Seed): Snapshot {
        cache[id]?.let { return it }
        val dir = directory(id)
        val saved = File(dir, "profile.json")
        if (saved.isFile) {
            val profile = ThemeProfile.parse(JSONObject(saved.readText()))
            return publish(prepare(id, profile, { ThemePack.safeAsset(dir, it) }))
        }
        val initial = seed()
        val profile = ThemeProfile.parse(initial.manifest)
        ThemePack.validateAssets(profile, initial.asset)
        root.mkdirs()
        val staged = File(root, ".stage-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            ThemePack.resourcePaths(profile.toJson(), initial.asset).keys.forEach { relative ->
                val dest = requireNotNull(ThemePack.safeAsset(staged, relative))
                dest.parentFile?.mkdirs()
                requireNotNull(initial.asset(relative)).copyTo(dest)
            }
            ThemePack.ensurePanelDirectory(profile, staged)
            File(staged, "profile.json").writeText(profile.toJson().toString(2))
            val verified = ThemeProfile.parse(JSONObject(File(staged, "profile.json").readText()))
            val prepared = prepare(id, verified, { ThemePack.safeAsset(staged, it) }, { ThemePack.safeAsset(dir, it) })
            check(!dir.exists()) { "主题配置目录已存在且不完整" }
            Files.move(staged.toPath(), dir.toPath())
            return publish(prepared)
        } finally {
            staged.deleteRecursively()
        }
    }

    /** 新素材使用新的相对路径；已发布素材不覆盖，旧快照可以继续安全读取。 */
    @Synchronized
    fun save(id: String, manifest: JSONObject, assets: Map<String, File> = emptyMap()): Snapshot {
        val previous = requireNotNull(cache[id]) { "请先读取主题配置" }
        val profile = ThemeProfile.parse(manifest)
        val dir = directory(id)
        val resolve: (String) -> File? = { assets[it] ?: ThemePack.safeAsset(dir, it) }
        ThemePack.validateAssets(profile, resolve)
        if (profile.toJson().toString() == previous.profile.toJson().toString() && assets.isEmpty()) return previous
        val copied = mutableListOf<File>()
        val staged = File.createTempFile("profile-", ".tmp", dir)
        try {
            for ((relative, source) in assets) {
                val dest = requireNotNull(ThemePack.safeAsset(dir, relative)) { "素材路径越界" }
                require(!dest.exists()) { "新素材必须使用新的相对路径：$relative" }
                dest.parentFile?.mkdirs()
                source.copyTo(dest)
                copied.add(dest)
            }
            val validated = ThemeProfile.parse(profile.toJson())
            ThemePack.validateAssets(validated) { ThemePack.safeAsset(dir, it) }
            staged.writeText(validated.toJson().toString(2))
            // 先读回验证，再原子提交；失败时仍保留旧文件和缓存。
            val readBack = ThemeProfile.parse(JSONObject(staged.readText()))
            val prepared = prepare(id, readBack, { ThemePack.safeAsset(dir, it) })
            Files.move(staged.toPath(), File(dir, "profile.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return publish(prepared)
        } catch (failure: Throwable) {
            copied.forEach { it.delete() }
            throw failure
        } finally {
            staged.delete()
        }
    }

    private fun directory(id: String): File {
        require(id.isNotBlank()) { "主题标识为空" }
        val dir = File(root, id).canonicalFile
        require(dir.parentFile == root.canonicalFile) { "主题标识无效" }
        return dir
    }

    private fun prepare(id: String, profile: ThemeProfile, asset: (String) -> File?,
                        storedAsset: (String) -> File? = asset): Snapshot {
        ThemePack.validateAssets(profile, asset)
        val paths = ThemePack.resourcePaths(profile.toJson(), asset).keys.toMutableSet()
        profile.toJson().optString("panel").takeIf { it.isNotBlank() }?.let { paths.add(it) }
        val files = paths.associateWith { requireNotNull(storedAsset(it)) }.toMap()
        return Snapshot(id, revision + 1, profile, files)
    }

    private fun publish(snapshot: Snapshot): Snapshot {
        revision = snapshot.revision
        cache[snapshot.themeId] = snapshot
        return snapshot
    }
}
