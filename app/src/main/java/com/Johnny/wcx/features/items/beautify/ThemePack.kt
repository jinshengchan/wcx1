package com.Johnny.wcx.features.items.beautify

import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.KnownPaths
import org.json.JSONObject
import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 主题包（Theme Pack）。
 *
 * 目标：把分散的美化维度收进**一个可整体切换的主题包**，像系统主题那样一次换全套：
 * 壁纸、左右气泡、配色、文案、字体、底部 Tab 图标、表情面板图标、悬浮球图标。
 *
 * 目录结构（解压后位于 `.../WCX/themes/<id>/`）：
 * ```
 * manifest.json          {
 *                          "name": "主题名", "author": "作者", "version": "1.0",
 *                          "colors": { "brand": "#FF6B6B" },
 *                          "strings": { "app_name": "微信" },
 *                          "wallpaper": "wallpaper.png",
 *                          "bubble_left": "bubble_left.9.png",
 *                          "bubble_right": "bubble_right.9.png",
 *                          "font": "font/nickname.ttf",
 *                          "tab": ["tab/chats.png","tab/contacts.png","tab/discover.png","tab/me.png"],
 *                          "panel": "panel"
 *                        }
 * wallpaper.png / bubble_*.9.png / font/... / tab/... / panel/...
 * ```
 *
 * 所有资源都按相对路径从 manifest 里取，缺哪一项就退回内置能力，不会因为少一个文件就整套失效。
 */
object ThemePack {

    private const val TAG = "ThemePack"
    private const val MANIFEST = "manifest.json"
    private const val PREF_CURRENT = "theme_pack_current"
    internal var snapshotProvider: ((String) -> ThemeProfileRepository.Snapshot?)? = null
    internal var onChanged: ((Pack, String) -> Unit)? = null

    /** 一个已安装的主题包。 */
    data class Pack(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val dir: File,
        val manifest: JSONObject,
    ) {
        /** 取包内文件，路径越界（../）一律拒绝。 */
        fun asset(relative: String?): File? {
            if (relative.isNullOrBlank()) return null
            val file = File(dir, relative)
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
            val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
            if (!canonical.path.startsWith(root.path + File.separator)) return null
            return canonical.takeIf { it.isFile }
        }

        fun color(key: String): String? =
            manifest.optJSONObject("colors")?.optString(key)?.takeIf { it.isNotBlank() }

        fun text(key: String): String? =
            manifest.optJSONObject("strings")?.optString(key)?.takeIf { it.isNotBlank() }

        internal val profile: ThemeProfile by lazy { ThemeProfile.parse(manifest) }

        fun wallpaper(): File? = (profile.background("chats") as? BackgroundRule.Image)?.let { asset(it.asset) }

        /**
         * 分页面壁纸：某个页面单独设了就用它，没设就退回全局壁纸。
         *
         * @param key chats / contacts / discover / me / chatting
         */
        fun wallpaperFor(key: String): File? {
            return (profile.background(key) as? BackgroundRule.Image)?.let { asset(it.asset) }
        }

        fun bubbleOther(): File? = asset(manifest.optString("bubble_left"))

        fun bubbleSelf(): File? = asset(manifest.optString("bubble_right"))

        fun font(): File? = asset(manifest.optString("font"))

        /** 底部四个 Tab（会话/通讯录/发现/我）的图标，下标与微信一致。 */
        fun tabIcon(index: Int): File? {
            return asset(profile.tabIcon(index, false))
        }

        /** 表情面板图标目录下的第 index 张（按文件名排序，保证稳定）。 */
        fun panelIcons(): List<File> {
            val rel = manifest.optString("panel").takeIf { it.isNotBlank() } ?: return emptyList()
            val dir = safeAsset(this.dir, rel) ?: return emptyList()
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    private val root: File by lazy {
        File(KnownPaths.moduleData.toFile(), "themes").apply { mkdirs() }
    }

    /** 全部已安装主题包（按名字排序）。 */
    fun list(): List<Pack> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { load(it) }.sortedBy { it.name.lowercase() }
    }

    fun find(id: String): Pack? {
        if (id.isBlank()) return null
        val dir = File(root, id).canonicalFile
        if (dir.parentFile != root.canonicalFile) return null
        return load(dir)
    }

    private fun load(dir: File): Pack? {
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.isFile) return null
        val json = runCatching { JSONObject(manifestFile.readText()) }.getOrElse {
            WeLogger.w(TAG, "主题包清单解析失败：${dir.name} / ${it.message}")
            return null
        }
        if (runCatching { ThemeProfile.parse(json) }.isFailure) return null
        return Pack(
            id = dir.name,
            name = json.optString("name").takeIf { it.isNotBlank() } ?: dir.name,
            author = json.optString("author"),
            version = json.optString("version"),
            dir = dir,
            manifest = json,
        )
    }

    var currentId: String
        get() = WePrefs.getString(PREF_CURRENT) ?: ""
        set(value) {
            WePrefs.putString(PREF_CURRENT, value)
        }

    fun current(): Pack? = find(currentId)

    /** 当前主题包，且必须在启用状态下才返回（关掉主题包时其它美化维度按各自开关走）。 */
    fun active(): Pack? {
        if (currentId.isBlank()) return null
        return find(currentId)?.also {
            if (!it.dir.isDirectory) {
                WeLogger.w(TAG, "主题包目录不存在：${it.id}")
                return null
            }
        }
    }

    /**
     * 导入主题包（zip）。解压到 `themes/<id>/`，成功后**不自动应用**，
     * 由调用方决定是否切换——导入失败时不留半成品目录。
     */
    fun importZip(zip: File): Result<Pack> = runCatching {
        require(zip.isFile) { "文件不存在" }
        val id = "pack_" + UUID.randomUUID()
        val target = File(root, id)
        target.mkdirs()

        try {
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                // 防 Zip-Slip：拒绝绝对路径与越界
                val out = File(target, name)
                val canonical = out.canonicalFile
                if (!canonical.path.startsWith(target.canonicalFile.path + File.separator)) {
                    error("压缩包条目越界：$name")
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { output -> input.copyTo(output) }
                    entries++
                }
            }
        }
        if (entries == 0) {
            target.deleteRecursively()
            error("压缩包里没有任何文件")
        }
        // 有的包会把内容套一层目录，这里向下找一层 manifest
        if (!File(target, MANIFEST).isFile) {
            val inner = target.listFiles()?.firstOrNull { File(it, MANIFEST).isFile }
            if (inner != null) {
                inner.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
                inner.deleteRecursively()
            }
        }
        val pack = load(target) ?: run {
            target.deleteRecursively()
            error("缺少 $MANIFEST 或格式不正确")
        }
        validateAssets(ThemeProfile.parse(pack.manifest)) { safeAsset(pack.dir, it) }
        WeLogger.i(TAG, "主题包已导入：${pack.name}（${pack.id}），共 $entries 个文件")
        pack
        } catch (failure: Throwable) {
            target.deleteRecursively()
            throw failure
        }
    }

    /** 将包移出列表；同一文件系统内移动失败时保留原目录。 */
    internal fun stageDelete(id: String): File {
        require(id.isNotBlank()) { "主题标识为空" }
        val dir = File(root, id).canonicalFile
        require(dir.parentFile == root.canonicalFile && dir.isDirectory) { "主题包不存在" }
        val staged = File(root.parentFile, ".theme-delete-" + UUID.randomUUID())
        Files.move(dir.toPath(), staged.toPath())
        return staged
    }

    /**
     * 把直接丢进 themes 目录的 zip 自动装上（装完删掉 zip）。
     *
     * 这样用户不需要文件选择器：用任意文件管理器把主题包丢进目录，回来点「刷新」即可。
     */
    fun importPendingZips(): List<Pack> {
        val zips = root.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            ?: return emptyList()
        return zips.mapNotNull { zip ->
            // importZip 本身已返回 Result，不能再套一层 runCatching（会变成 Result<Result<Pack>>）
            importZip(zip)
                .onSuccess { zip.delete() }
                .onFailure { WeLogger.w(TAG, "自动导入失败：${zip.name} / ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * 把主题包打成 zip，直接分享给别人导入即可。
     * 输出到 `themes/exported/`，文件名取自主题名（非法字符替换掉）。
     */
    fun exportZip(pack: Pack): Result<File> {
        snapshotProvider?.invoke(pack.id)?.let { return exportProfile(pack.name, it.profile, it::asset) }
        return exportProfile(pack.name, pack.profile) { safeAsset(pack.dir, it) }
    }

    internal fun exportProfile(name: String, profile: ThemeProfile, asset: (String) -> File?): Result<File> = runCatching {
        validateAssets(profile, asset)
        val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "theme" }
        val out = File(exportDir(), "$safeName.zip")
        val staged = File.createTempFile("export-", ".zip", exportDir())
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(MANIFEST))
                zos.write(profile.toJson().toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                profile.toJson().optString("panel").takeIf { it.isNotBlank() }?.let { relative ->
                    zos.putNextEntry(ZipEntry(ThemeProfile.assetPath(relative) + "/"))
                    zos.closeEntry()
                }
                resourcePaths(profile.toJson(), asset).keys.forEach { relative ->
                    val source = requireNotNull(asset(relative)) { "缺少素材：$relative" }
                    zos.putNextEntry(ZipEntry(relative))
                    source.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            Files.move(staged.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
        out
    }

    internal fun cloneProfile(name: String, profile: ThemeProfile, asset: (String) -> File?): Result<Pack> = runCatching {
        validateAssets(profile, asset)
        val dir = File(root, "pack_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            resourcePaths(profile.toJson(), asset).keys.forEach { relative ->
                val dest = requireNotNull(safeAsset(dir, relative))
                dest.parentFile?.mkdirs()
                requireNotNull(asset(relative)).copyTo(dest)
            }
            ensurePanelDirectory(profile, dir)
            val manifest = profile.toJson().put("name", name)
            File(dir, MANIFEST).writeText(manifest.toString(2))
            load(dir) ?: error("复制主题失败")
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
    }

    fun clone(pack: Pack, name: String = "${pack.name} 副本"): Result<Pack> {
        snapshotProvider?.invoke(pack.id)?.let { return cloneProfile(name, it.profile, it::asset) }
        return cloneProfile(name, pack.profile) { safeAsset(pack.dir, it) }
    }

    /**
     * 用现成的几张图生成一个主题包（用于把内置主题也导出分享）。
     */
    fun createPackFromFiles(
        name: String,
        wallpaper: File?,
        bubbleLeft: File?,
        bubbleRight: File?,
    ): Result<Pack> = runCatching {
        val id = "pack_" + UUID.randomUUID()
        val dir = File(root, id).apply { mkdirs() }
        val manifest = JSONObject().apply {
            put("name", name)
            put("author", "WCX")
            put("version", "1.0")
        }
        wallpaper?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "wallpaper.png"), overwrite = true)
            manifest.put("wallpaper", "wallpaper.png")
        }
        bubbleLeft?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "bubble_left.9.png"), overwrite = true)
            manifest.put("bubble_left", "bubble_left.9.png")
        }
        bubbleRight?.takeIf { it.isFile }?.let {
            it.copyTo(File(dir, "bubble_right.9.png"), overwrite = true)
            manifest.put("bubble_right", "bubble_right.9.png")
        }
        File(dir, MANIFEST).writeText(manifest.toString(2))
        load(dir) ?: error("生成主题包清单失败")
    }

    /**
     * 新建一个空主题包：只写清单，图片留空。
     *
     * 自由度最高的用法——有人只想换两个气泡、不想换壁纸，那就只补气泡那两张图，
     * 其余项留空即保持微信原样，不会因为缺图整套失效。
     */
    fun createEmptyPack(name: String, author: String = "我"): Result<Pack> = runCatching {
        val id = "pack_" + UUID.randomUUID()
        val dir = File(root, id).apply { mkdirs() }
        val manifest = JSONObject().apply {
            put("name", name)
            put("author", author)
            put("version", "1.0")
        }
        File(dir, MANIFEST).writeText(manifest.toString(2))
        load(dir) ?: error("新建主题包失败")
    }

    /**
     * 给主题包写入一张图片。
     *
     * @param role wallpaper / bubble_left / bubble_right
     * @param ninePatch 气泡必须是 9-patch（.9.png），由调用方决定文件后缀
     */
    fun writeImage(pack: Pack, context: Context, uri: Uri, role: String, ninePatch: Boolean = false): Pack? = runCatching {
        require(role in listOf("wallpaper", "wallpaper_chats", "wallpaper_contacts", "wallpaper_discover", "wallpaper_me", "wallpaper_chatting", "wallpaper_panel", "bubble_left", "bubble_right"))
        val suffix = if (ninePatch) ".9.png" else ".png"
        val fileName = "image-" + UUID.randomUUID() + suffix
        val dest = File(pack.dir, fileName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: error("读不到这张图片")
            val manifest = JSONObject(File(pack.dir, MANIFEST).readText()).put(role, fileName)
            val page = if (role == "wallpaper") "common" else role.removePrefix("wallpaper_")
            if (page == "common" || page in ThemeProfile.PAGE_KEYS) {
                val canonical = ThemeProfile.parse(manifest).toJson()
                canonical.getJSONObject("appearance").getJSONObject("pages").put(page,
                    JSONObject().put("kind", "image").put("asset", fileName))
                saveManifest(pack, canonical, role)
            } else saveManifest(pack, manifest, role)
        } catch (failure: Throwable) {
            val stillReferenced = runCatching {
                File(pack.dir, MANIFEST).readText().contains("\"$fileName\"")
            }.getOrDefault(true)
            if (!stillReferenced) dest.delete()
            throw failure
        }
    }.onFailure { WeLogger.w(TAG, "写入图片失败：$role / ${it.message}") }.getOrNull()

    /** 清单使用显式 native 阻断公共壁纸回退；旧素材保留，不影响已发布快照。 */
    fun resetRole(pack: Pack, role: String): Pack? = runCatching {
        require(role in listOf("wallpaper", "wallpaper_chats", "wallpaper_contacts", "wallpaper_discover", "wallpaper_me", "wallpaper_chatting", "wallpaper_panel", "bubble_left", "bubble_right"))
        val manifest = ThemeProfile.parse(JSONObject(File(pack.dir, MANIFEST).readText())).toJson()
        manifest.remove(role)
        val page = if (role == "wallpaper") "common" else role.removePrefix("wallpaper_")
        if (page == "common" || page in ThemeProfile.PAGE_KEYS) {
            manifest.getJSONObject("appearance").getJSONObject("pages").put(page, JSONObject().put("kind", "native"))
        }
        saveManifest(pack, manifest, role)
    }.getOrNull()

    private fun saveManifest(pack: Pack, manifest: JSONObject, role: String): Pack {
        snapshotProvider?.let { requireNotNull(it(pack.id)) { "无法读取编辑前的主题配置" } }
        val profile = ThemeProfile.parse(manifest)
        validateAssets(profile) { safeAsset(pack.dir, it) }
        val manifestFile = File(pack.dir, MANIFEST)
        val previous = manifestFile.readBytes()
        val canonical = profile.toJson()
        val updated = pack.copy(
            name = canonical.optString("name").ifBlank { pack.id },
            author = canonical.optString("author"), version = canonical.optString("version"), manifest = canonical
        )
        val staged = File.createTempFile("manifest-", ".tmp", pack.dir)
        var committed = false
        try {
            staged.writeText(canonical.toString(2))
            ThemeProfile.parse(JSONObject(staged.readText()))
            Files.move(staged.toPath(), manifestFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            committed = true
            onChanged?.invoke(updated, role)
            return updated
        } catch (failure: Throwable) {
            if (committed) {
                runCatching {
                    staged.writeBytes(previous)
                    Files.move(staged.toPath(), manifestFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.onFailure(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.delete()
        }
    }

    /** 修改主题名称 / 描述（只改清单，不动素材目录）。 */
    fun updateMeta(pack: Pack, name: String?, description: String?): Pack? = runCatching {
        val manifestFile = File(pack.dir, MANIFEST)
        val manifest = JSONObject(manifestFile.readText())
        name?.takeIf { it.isNotBlank() }?.let { manifest.put("name", it) }
        description?.let { manifest.put("description", it) }
        saveManifest(pack, manifest, "metadata")
    }.getOrNull()

    /** 保存这套主题对应的美化开关快照（切换主题时用来同步页面开关）。 */
    fun saveSwitches(pack: Pack, switches: Map<String, Boolean>): Pack? = runCatching {
        val manifestFile = File(pack.dir, MANIFEST)
        val manifest = JSONObject(manifestFile.readText())
        val obj = JSONObject()
        switches.forEach { (key, value) -> obj.put(key, value) }
        manifest.put("switches", obj)
        saveManifest(pack, manifest, "switches")
    }.getOrNull()

    /** 读取开关快照；没有记录时返回空表（调用方保持当前状态）。 */
    fun switches(pack: Pack): Map<String, Boolean> {
        val obj = pack.manifest.optJSONObject("switches") ?: return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        obj.keys().forEach { key ->
            result[key] = obj.optBoolean(key, false)
        }
        return result
    }

    /** 备份目录（导出/备份的 zip 都放这里）。 */
    fun backupDir(): File = File(root, "backup").apply { mkdirs() }

    /** 把主题包备份成带时间戳的 zip。 */
    fun backup(pack: Pack): Result<File> = runCatching {
        val out = File(backupDir(), "${pack.name}_${System.currentTimeMillis()}.zip")
        val exported = exportZip(pack).getOrThrow()
        exported.copyTo(out, overwrite = true)
        WeLogger.i(TAG, "主题包已备份：${out.absolutePath}")
        out
    }

    /** 导出目录，分享时从这里取 zip。 */
    fun exportDir(): File = File(root, "exported").apply { mkdirs() }

    /** 主题根目录，供设置页展示/引导用户放素材。 */
    fun themesDir(): File = root

    /** 只收集格式约定的素材位；扩展元数据保持原样。值表示是否需要图像解码校验。 */
    internal fun resourcePaths(manifest: JSONObject, asset: (String) -> File?): Map<String, Boolean> {
        val paths = linkedMapOf<String, Boolean>()
        fun add(value: Any?, image: Boolean) {
            if (value is String && value.isNotBlank()) {
                val path = ThemeProfile.assetPath(value)
                paths[path] = paths[path] == true || image
            }
        }
        for (key in listOf("wallpaper", "bubble_left", "bubble_right") + ThemeProfile.PAGE_KEYS.map { "wallpaper_$it" } + "wallpaper_panel") {
            add(manifest.opt(key), true)
        }
        add(manifest.opt("font"), false)
        manifest.optJSONArray("tab")?.let { values -> for (i in 0 until values.length()) add(values.opt(i), true) }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.optString("kind") == "image") add(value.opt("asset"), true)
                    value.keys().forEach { key ->
                        if (key == "fab") {
                            add(value.optJSONObject(key)?.opt("iconAsset"), true)
                            visit(value.opt(key))
                        } else if (key == "tabIcons") {
                            val icons = value.optJSONObject(key)
                            for (state in listOf("normal", "selected")) icons?.optJSONArray(state)?.let { a ->
                                for (i in 0 until a.length()) add(a.opt(i), true)
                            }
                        } else visit(value.opt(key))
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
            }
        }
        visit(manifest.optJSONObject("appearance"))
        manifest.optString("panel").takeIf { it.isNotBlank() }?.let { relative ->
            val path = ThemeProfile.assetPath(relative)
            val directory = asset(path)
            require(directory != null && directory.isDirectory) { "缺少面板目录：$relative" }
            directory.walkTopDown().filter { it.isFile }.forEach {
                add("$path/" + it.relativeTo(directory).invariantSeparatorsPath, it.extension.equals("png", true))
            }
        }
        return paths
    }

    internal fun safeAsset(dir: File, relative: String): File? = runCatching {
        val path = ThemeProfile.assetPath(relative)
        File(dir, path).canonicalFile.takeIf { it.path.startsWith(dir.canonicalPath + File.separator) }
    }.getOrNull()

    internal fun ensurePanelDirectory(profile: ThemeProfile, destination: File) {
        profile.toJson().optString("panel").takeIf { it.isNotBlank() }?.let { relative ->
            val directory = requireNotNull(safeAsset(destination, relative))
            check(directory.isDirectory || directory.mkdirs()) { "无法创建面板目录" }
        }
    }

    internal fun validateAssets(profile: ThemeProfile, asset: (String) -> File?) {
        for ((relative, image) in resourcePaths(profile.toJson(), asset)) {
            val file = asset(relative)
            require(file != null && file.isFile && file.length() > 0L) { "缺少素材：$relative" }
            if (image) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "无效图片：$relative" }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                require(bitmap != null) { "图片无法解码：$relative" }
                bitmap.recycle()
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 随机背景图池（"随机聊天背景"开关用，最多 5 张）
    // ══════════════════════════════════════════════════════════

    const val RANDOM_POOL_MAX = 5

    /** 随机背景图池目录。 */
    fun randomPoolDir(): File = File(root, "random_pool").apply { mkdirs() }

    /** 图池里现有的图片，按添加顺序（文件名递增）排序。 */
    fun randomPoolFiles(): List<File> = randomPoolDir()
        .listFiles { f -> f.isFile && f.name.endsWith(".img") }
        ?.sortedBy { it.name }
        ?: emptyList()

    /** 往图池里加一张（复制成 .img，避免扩展名歧义），超过 [RANDOM_POOL_MAX] 时拒绝。 */
    fun addRandomPoolImage(context: Context, uri: Uri): Result<File> = runCatching {
        val files = randomPoolFiles()
        require(files.size < RANDOM_POOL_MAX) { "最多 $RANDOM_POOL_MAX 张，先删掉一张" }
        val target = File(randomPoolDir(), String.format("%03d_%d.img", files.size + 1, System.currentTimeMillis()))
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读不到这张图片")
        require(bytes.isNotEmpty()) { "这张图片是空的" }
        target.writeBytes(bytes)
        WeLogger.i(TAG, "随机背景池新增一张：${target.name}（共 ${files.size + 1} 张）")
        target
    }

    /** 从图池删除一张。 */
    fun removeRandomPoolImage(file: File): Boolean = runCatching {
        val ok = file.delete()
        if (ok) WeLogger.i(TAG, "随机背景池删除：${file.name}")
        ok
    }.getOrDefault(false)
}
