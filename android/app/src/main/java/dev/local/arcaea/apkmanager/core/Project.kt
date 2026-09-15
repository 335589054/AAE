package dev.local.arcaea.apkmanager.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.BufferedOutputStream

/** 工程内的资源改动：内存字节，或一个待写入的源文件（大文件用文件避免占内存） */
sealed interface WriteOp {
    data class Bytes(val data: ByteArray) : WriteOp
    data class FromFile(val file: File) : WriteOp
}

data class BuildOptions(
    /** 为空表示保持原包名 */
    val packageName: String? = null,
    /** 改包名时是否同时改写以旧包名为前缀的自定义权限名 / Provider 授权名 */
    val rewriteIdentifiers: Boolean = true,
)

/** 资源检查结果 */
data class ProjectSnapshot(
    /** 会导致游戏异常的问题（导出前应处理） */
    val warnings: List<String>,
    /** 可忽略的提示 */
    val notes: List<String>,
    /** 尚未导出的改动摘要 */
    val pending: List<String>,
    val dirty: Boolean,
)

/**
 * 一个已导入的 APK 工程：读取/修改 songlist、packlist、歌曲资源，并重新打包导出。
 *
 * 读取与写入都由 [ZipReader] / [ZipWriter] 完成，未改动的条目**直通拷贝压缩数据**，
 * 因此 1GB 级的 APK 也能在手机上快速完成重打包。
 */
class ApkProject(val apkFile: File) : Closeable {

    companion object {
        const val SONGS_ROOT = "assets/songs/"
        const val SONGLIST_PATH = "${SONGS_ROOT}songlist"
        const val PACKLIST_PATH = "${SONGS_ROOT}packlist"
        const val UNLOCKS_PATH = "${SONGS_ROOT}unlocks"
        const val PACK_DIR = "${SONGS_ROOT}pack/"
        const val MANIFEST_PATH = "AndroidManifest.xml"
        const val BASE_AUDIO = "base.ogg"

        /** 本身已是压缩格式，写入时不再 deflate */
        private val STORE_EXTENSIONS = setOf(
            ".png", ".jpg", ".jpeg", ".ogg", ".mp3", ".mp4", ".webm", ".aac", ".m4a", ".zip", ".jar", ".so",
        )

        /** 签名相关条目在重打包时剔除，随后由签名步骤重新生成 */
        fun isSignatureEntry(name: String): Boolean {
            if (!name.startsWith("META-INF/")) return false
            val rest = name.removePrefix("META-INF/")
            if (rest.contains('/')) return false
            return rest == "MANIFEST.MF" ||
                Regex("\\.(SF|RSA|DSA|EC)$", RegexOption.IGNORE_CASE).containsMatchIn(rest) ||
                rest.startsWith("CERT.", ignoreCase = true)
        }

        fun shouldCompress(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            if (dot < 0) return true
            return name.substring(dot).lowercase() !in STORE_EXTENSIONS
        }

        /** 包名合法性：至少两段，每段以字母开头，只含字母数字下划线 */
        fun isValidPackageName(name: String): Boolean =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$").matches(name)

        /**
         * 歌曲 / 曲包 id 合法性：只允许字母、数字、下划线、连字符、点。
         * 因为 id 同时是目录名（`assets/songs/<id>/`），带斜杠或空格会产生非法路径。
         */
        fun isValidId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 64 && Regex("^[A-Za-z0-9_.\\-]+$").matches(id)

        /** 歌曲目录前缀 */
        fun songPrefix(songId: String): String = "$SONGS_ROOT$songId/"
    }

    val reader: ZipReader = ZipReader(apkFile)
    val manifest: ManifestInfo
    private val originalManifestRaw: ByteArray
    private val manifestIsBinary: Boolean

    val songsArray: JsonArray
    val packsArray: JsonArray
    private val unlocksRaw: String
    val unlocksObject: JsonObject

    private val originalSongsText: String
    private val originalPacksText: String
    private val originalUnlocksText: String

    private val writes = LinkedHashMap<String, WriteOp>()
    private val deletes = LinkedHashSet<String>()

    /** songId → 该目录下的文件名（不含前缀） */
    private val dirIndex = LinkedHashMap<String, MutableList<String>>()

    /** 是否存在尚未导出的改动 */
    var dirtySinceExport = false
        private set

    var packageNameOverride: String? = null
        private set

    var rewriteIdentifiers = true
        private set

    init {
        val manifestRaw = reader.readFile(MANIFEST_PATH)
            ?: throw IOException("这不是一个有效的 APK：缺少 AndroidManifest.xml")
        originalManifestRaw = manifestRaw
        manifest = Axml.readManifest(manifestRaw)
        manifestIsBinary = manifest.isBinary

        originalSongsText = reader.readText(SONGLIST_PATH)
            ?: throw IOException("APK 中缺少 $SONGLIST_PATH，无法作为谱面工程打开")
        originalPacksText = reader.readText(PACKLIST_PATH) ?: "{\n  \"packs\": []\n}\n"
        originalUnlocksText = reader.readText(UNLOCKS_PATH) ?: "{\n  \"unlocks\": []\n}\n"

        songsArray = (Json.parse(originalSongsText) as? JsonObject)?.optArray("songs")
            ?: throw IOException("$SONGLIST_PATH 结构异常：缺少 songs 数组")
        packsArray = (Json.parse(originalPacksText) as? JsonObject)?.optArray("packs")
            ?: JsonArray()
        unlocksObject = (Json.parse(originalUnlocksText) as? JsonObject) ?: JsonObject()
        unlocksRaw = originalUnlocksText

        for (name in reader.listAll()) {
            if (!name.startsWith(SONGS_ROOT)) continue
            val rest = name.removePrefix(SONGS_ROOT)
            val slash = rest.indexOf('/')
            if (slash <= 0 || rest.startsWith("pack/")) continue
            val songId = rest.substring(0, slash)
            dirIndex.getOrPut(songId) { mutableListOf() }.add(rest.substring(slash + 1))
        }
    }

    /* ------------------------------ 只读信息 ------------------------------ */

    val songs: List<Song>
        get() = songsArray.toList().filterIsInstance<JsonObject>().map { Song(it) }

    val packs: List<Pack>
        get() = packsArray.toList().filterIsInstance<JsonObject>().map { Pack(it) }

    fun song(id: String): Song? =
        songsArray.toList().filterIsInstance<JsonObject>()
            .firstOrNull { it.optString("id") == id }
            ?.let { Song(it) }

    fun pack(id: String): Pack? =
        packsArray.toList().filterIsInstance<JsonObject>()
            .firstOrNull { it.optString("id") == id }
            ?.let { Pack(it) }

    fun songIds(): List<String> = songs.map { it.id }

    fun songCountInPack(packId: String): Int = songs.count { it.set == packId }

    /** 曲包是否为「非 extend 的空曲包」——这种状态会让游戏打开歌单时闪退 */
    fun isEmptyNormalPack(pack: Pack): Boolean =
        songCountInPack(pack.id) == 0 && !pack.isExtendPack

    /* ------------------------------ 资源访问 ------------------------------ */

    fun exists(path: String): Boolean = readEntry(path) != null

    fun readEntry(name: String): ByteArray? {
        if (deletes.contains(name)) return null
        writes[name]?.let { op ->
            return when (op) {
                is WriteOp.Bytes -> op.data
                // 暂存文件可能已被「清理缓存 / 清空数据」删除：此时按「文件不存在」处理，
                // 而不是抛 FileNotFoundException（否则界面读取 → Compose 协程会因此闪退）。
                is WriteOp.FromFile -> try {
                    op.file.readBytes()
                } catch (_: IOException) {
                    null
                }
            }
        }
        // 其余读取都来自 ZIP 源，走到这里说明工程可能正在被关闭 / 源文件被清理。
        // 同样按「文件不存在 / 读取失败」兜底返回 null，绝不让异常冒泡到界面协程导致闪退。
        return try {
            if (name == MANIFEST_PATH && packageNameOverride != null) {
                Axml.setPackageName(originalManifestRaw, packageNameOverride!!, rewriteIdentifiers)
            } else {
                reader.readFile(name)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** 某首歌目录下的文件名（已计入暂存的增删） */
    fun listSongFiles(songId: String): List<String> {
        val prefix = songPrefix(songId)
        val set = LinkedHashSet(dirIndex[songId] ?: emptyList())
        for (deleted in deletes) {
            if (deleted.startsWith(prefix)) set.remove(deleted.removePrefix(prefix))
        }
        for (written in writes.keys) {
            if (written.startsWith(prefix)) set.add(written.removePrefix(prefix))
        }
        return set.sorted()
    }

    /** 已存在的歌曲目录名（不含 tutorial / pack） */
    fun songDirs(): List<String> = dirIndex.keys.toList()

    private fun assertMutable(relPath: String) {
        if (!relPath.startsWith("assets/")) {
            throw IOException("出于安全考虑，只允许修改 assets/ 下的文件（收到：$relPath）")
        }
        if (isSignatureEntry(relPath)) {
            throw IOException("签名文件由签名步骤自动生成，不能手动修改")
        }
    }

    fun stageWrite(relPath: String, op: WriteOp) {
        assertMutable(relPath)
        deletes.remove(relPath)
        writes[relPath] = op
        dirtySinceExport = true
    }

    fun stageWriteBytes(relPath: String, data: ByteArray) = stageWrite(relPath, WriteOp.Bytes(data))

    fun stageWriteFile(relPath: String, file: File) = stageWrite(relPath, WriteOp.FromFile(file))

    fun stageDelete(relPath: String) {
        assertMutable(relPath)
        writes.remove(relPath)
        deletes.add(relPath)
        dirtySinceExport = true
    }

    /** 删除整首歌的目录 */
    fun stageDeleteSongDir(songId: String) {
        val prefix = songPrefix(songId)
        for (name in reader.listAll()) {
            if (name.startsWith(prefix)) {
                deletes.add(name)
                writes.remove(name)
            }
        }
        for (name in writes.keys.toList()) {
            if (name.startsWith(prefix)) writes.remove(name)
        }
        dirtySinceExport = true
    }

    /* --------------------------- 歌曲 / 曲包增删改 --------------------------- */

    fun addSong(song: Song, index: Int = -1) {
        val id = song.id.trim()
        if (id.isEmpty()) throw IOException("歌曲 id 不能为空")
        if (!isValidId(id)) throw IOException("歌曲 id 只能包含字母、数字、下划线、连字符和点：$id")
        if (songIds().contains(id)) throw IOException("歌曲 id 已存在：$id")
        if (dirIndex.containsKey(id)) {
            throw IOException("APK 中已存在同名目录 assets/songs/$id/，请换一个 id")
        }
        if (song.set.isNotEmpty() && packs.none { it.id == song.set }) {
            throw IOException("曲包不存在：${song.set}")
        }
        if (index in 0..songsArray.size) songsArray.add(index, song.json) else songsArray.add(song.json)
        dirtySinceExport = true
    }

    fun removeSong(id: String) {
        val index = songsArray.toList().filterIsInstance<JsonObject>().indexOfFirst { it.optString("id") == id }
        if (index < 0) throw IOException("找不到歌曲：$id")
        songsArray.removeAt(index)
        stageDeleteSongDir(id)
        dirtySinceExport = true
    }

    /** 新增歌曲，并同时把资源包（zip）里的文件写入该歌曲目录 */
    fun addSongWithResources(song: Song, bundle: SongResourceBundle) {
        bundle.metadata?.let { ResourceZip.mergeSongFragment(song, it) }
        addSong(song)
        val prefix = songPrefix(song.id)
        for ((name, file) in bundle.files) {
            stageWriteFile(prefix + name, file)
        }
    }

    /**
     * 修改歌曲 id：把整个资源目录搬到新目录名下（目录名必须与 id 一致）。
     *
     * 逐文件经 [tempDir] 中转，避免把一首歌的全部资源同时读进内存；
     * 搬运用的是「暂存写入 + 暂存删除」，因此导出一致由重打包流程处理。
     */
    fun renameSong(oldId: String, newId: String, tempDir: File) {
        val trimmed = newId.trim()
        if (trimmed.isEmpty()) throw IOException("歌曲 id 不能为空")
        if (!isValidId(trimmed)) throw IOException("歌曲 id 只能包含字母、数字、下划线、连字符和点：$trimmed")
        if (trimmed == oldId) return
        val song = song(oldId) ?: throw IOException("找不到歌曲：$oldId")
        if (songIds().contains(trimmed)) throw IOException("歌曲 id 已存在：$trimmed")
        if (dirIndex.containsKey(trimmed)) {
            throw IOException("APK 中已存在同名目录 assets/songs/$trimmed/，请换一个 id")
        }

        val files = listSongFiles(oldId)
        val oldPrefix = songPrefix(oldId)
        val newPrefix = songPrefix(trimmed)
        if (files.isNotEmpty()) {
            // 目录名带唯一后缀：重复给不同歌曲改 id（且复用同一个旧 id）时不会互相删掉临时文件
            val stageDir = File(tempDir, "rename-${oldId}-${System.nanoTime()}").apply {
                deleteRecursively()
                mkdirs()
            }
            for (name in files) {
                val bytes = readEntry(oldPrefix + name) ?: continue
                val staged = File(stageDir, name)
                staged.parentFile?.mkdirs()
                staged.writeBytes(bytes)
                stageWrite(newPrefix + name, WriteOp.FromFile(staged))
                stageDelete(oldPrefix + name)
            }
        }
        song.id = trimmed
        dirIndex.remove(oldId)?.let { dirIndex[trimmed] = it }
        dirtySinceExport = true
    }

    /** 上移 / 下移（delta = -1 / +1） */
    fun moveSong(id: String, delta: Int): Boolean {
        val list = songsArray.toList().filterIsInstance<JsonObject>()
        val from = list.indexOfFirst { it.optString("id") == id }
        if (from < 0) return false
        val to = from + delta
        if (to < 0 || to >= list.size) return false
        val node = songsArray.removeAt(from)
        songsArray.add(to, node)
        dirtySinceExport = true
        return true
    }

    fun addPack(pack: Pack) {
        val id = pack.id.trim()
        if (id.isEmpty()) throw IOException("曲包 id 不能为空")
        if (packs.any { it.id == id }) throw IOException("曲包 id 已存在：$id")
        packsArray.add(pack.json)
        dirtySinceExport = true
    }

    fun updatePack(id: String, block: (Pack) -> Unit) {
        val pack = pack(id) ?: throw IOException("找不到曲包：$id")
        block(pack)
        dirtySinceExport = true
    }

    /** 删除曲包；moveSongsTo 为空时把该包下的歌曲一并删除 */
    fun removePack(id: String, moveSongsTo: String? = null) {
        val index = packsArray.toList().filterIsInstance<JsonObject>().indexOfFirst { it.optString("id") == id }
        if (index < 0) throw IOException("找不到曲包：$id")
        val affected = songs.filter { it.set == id }
        if (moveSongsTo != null) {
            if (packs.none { it.id == moveSongsTo }) throw IOException("目标曲包不存在：$moveSongsTo")
            affected.forEach { it.set = moveSongsTo }
        } else {
            affected.forEach { removeSong(it.id) }
        }
        packsArray.removeAt(index)
        // 同时清理该曲包的横幅图片，避免留下无用资源
        for (banner in listOf("${PACK_DIR}select_$id.png", "${PACK_DIR}1080_select_$id.png")) {
            if (reader.has(banner)) stageDelete(banner)
        }
        dirtySinceExport = true
    }

    /* ------------------------------ 包名 / 检查 ------------------------------ */

    fun setPackageNameOverride(pkg: String?, rewrite: Boolean = true) {
        val next = pkg?.trim()?.takeIf { it.isNotEmpty() }
        if (next == packageNameOverride && rewrite == rewriteIdentifiers) return
        packageNameOverride = next
        rewriteIdentifiers = rewrite
        dirtySinceExport = true
    }

    private fun serializeSonglist(): ByteArray =
        (Json.write(JsonObject.of("songs" to songsArray), 2) + "\n").toByteArray(Charsets.UTF_8)

    private fun serializePacklist(): ByteArray =
        (Json.write(JsonObject.of("packs" to packsArray), 2) + "\n").toByteArray(Charsets.UTF_8)

    /** 资源检查：返回会导致游戏异常的问题与可忽略的提示 */
    fun snapshot(): ProjectSnapshot {
        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val songIdSet = songIds().toSet()
        val packIdSet = packs.map { it.id }.toSet()

        var missingDir = 0
        for (song in songs) {
            val files = listSongFiles(song.id)
            if (files.isEmpty()) {
                missingDir++
                warnings.add("歌曲「${song.id}」在 songlist 中有条目，但没有对应目录（游戏中不可用）")
                continue
            }
            fun has(name: String) = files.contains(name)

            if (!has("base.jpg") && !has("1080_base.jpg")) {
                notes.add("「${song.id}」缺少封面（base.jpg 与 1080_base.jpg 均不存在）")
            }
            if (!has("base_256.jpg") && !has("1080_base_256.jpg")) {
                notes.add("「${song.id}」缺少缩略封面（base_256.jpg 与 1080_base_256.jpg 均不存在）")
            }

            var playable = 0
            for (difficulty in song.difficultyList()) {
                val rc = (difficulty["ratingClass"] as? JsonNumber)?.toInt() ?: continue
                if (!song.isPlayable(difficulty)) continue
                playable++
                if (!has("$rc.aff")) {
                    warnings.add("「${song.id}」难度 $rc 声明了定数但没有谱面 $rc.aff（进入该难度会闪退）")
                }
                if (Song.isAudioOverride(difficulty) && !has("$rc.ogg")) {
                    warnings.add("「${song.id}」难度 $rc 声明了 audioOverride 但没有 $rc.ogg")
                }
            }
            if (playable == 0) {
                warnings.add("「${song.id}」没有任何可游玩难度（所有 rating 都是 0），歌单可能显示异常")
            }
            if (playable > 0) {
                val anyOverride = song.difficultyList().any { Song.isAudioOverride(it) }
                if (!has(BASE_AUDIO) && !anyOverride) {
                    notes.add("「${song.id}」缺少 base.ogg 且没有 audioOverride，播放将无声")
                }
            }
            song.bg?.let { bg ->
                if (!exists("assets/img/bg/1080/$bg.jpg") && !exists("assets/img/bg/1080/$bg.png")) {
                    warnings.add("「${song.id}」的背景资源缺失：assets/img/bg/1080/$bg.jpg")
                }
            }
            if (song.set.isNotEmpty() && song.set !in packIdSet) {
                warnings.add("歌曲「${song.id}」的 set=\"${song.set}\" 在 packlist 中不存在")
            }
        }
        if (missingDir > 0) notes.add("共 $missingDir 首歌缺少资源目录")

        for (pack in packs) {
            if (!exists("${PACK_DIR}select_${pack.id}.png") && !exists("${PACK_DIR}1080_select_${pack.id}.png")) {
                warnings.add("曲包「${pack.id}」缺少横幅（select_${pack.id}.png 与 1080_select_${pack.id}.png 均不存在）")
            }
            if (songCountInPack(pack.id) == 0) {
                // 实测结论：extend 包可以为空；普通包为空会导致游戏打开歌单时闪退
                if (pack.isExtendPack) {
                    notes.add("曲包「${pack.id}」下没有任何歌曲（标记为 extend 包，允许为空）")
                } else {
                    warnings.add(
                        "曲包「${pack.id}」下没有任何歌曲，且它不是 extend 包（缺少 \"is_extend_pack\": true）" +
                            "——这会导致游戏打开歌单时直接闪退。请三选一：给该曲包添加歌曲 / 删除该曲包 / 给它加上 is_extend_pack。",
                    )
                }
            }
        }

        for (dir in songDirs()) {
            if (dir !in songIdSet && dir != "tutorial") {
                notes.add("目录 assets/songs/$dir/ 存在，但没有对应的 songlist 条目（资源未被使用）")
            }
        }

        val pending = mutableListOf<String>()
        val songlistDirty = serializeSonglist().contentEquals(originalSongsText.toByteArray(Charsets.UTF_8))
            .not()
        val packlistDirty = serializePacklist().contentEquals(originalPacksText.toByteArray(Charsets.UTF_8))
            .not()
        if (songlistDirty) pending.add("歌曲列表（songlist）已修改")
        if (packlistDirty) pending.add("曲包列表（packlist）已修改")
        packageNameOverride?.let {
            pending.add(
                "包名将改为 $it" +
                    if (rewriteIdentifiers) "（同时改写自定义权限名与 Provider 授权名）" else "（保留原有权限名与授权名）",
            )
        }
        for (name in writes.keys) pending.add("写入资源：$name")
        for (name in deletes) pending.add("删除资源：$name")

        return ProjectSnapshot(warnings, notes, pending, dirtySinceExport)
    }

    /* -------------------------------- 导出 -------------------------------- */

    /**
     * 重新打包为「未签名」的 APK。未改动的条目直通拷贝，不重新压缩。
     * 完成后再交给 [Signer] 签名。
     */
    fun exportUnsigned(outFile: File, onProgress: (String, Int) -> Unit = { _, _ -> }) {
        // 先确认所有「来自文件」的暂存资源都还在：临时文件可能因为重新导入资源包、
        // 清理缓存等原因被删掉，提前报出可读的错误，而不是在中途抛出 ENOENT。
        val missing = writes.filterValues { it is WriteOp.FromFile && !it.file.exists() }.keys.toList()
        if (missing.isNotEmpty()) {
            throw IOException(
                "以下已导入的临时资源文件已丢失，无法完成导出：" +
                    missing.take(5).joinToString("", prefix = "\n  ") { it } +
                    (if (missing.size > 5) "\n  …共 ${missing.size} 个" else "") +
                    "\n请重新导入这些资源后再试（通常发生在重新导入过资源包、或清理过缓存之后）。",
            )
        }

        val overrides = LinkedHashMap<String, WriteOp>()

        onProgress("正在序列化 songlist / packlist…", 1)
        overrides[SONGLIST_PATH] = WriteOp.Bytes(serializeSonglist())
        overrides[PACKLIST_PATH] = WriteOp.Bytes(serializePacklist())
        overrides[UNLOCKS_PATH] = WriteOp.Bytes(unlocksRaw.toByteArray(Charsets.UTF_8))
        overrides[MANIFEST_PATH] = if (packageNameOverride != null) {
            onProgress("正在修改 AndroidManifest 包名…", 2)
            WriteOp.Bytes(Axml.setPackageName(originalManifestRaw, packageNameOverride!!, rewriteIdentifiers))
        } else {
            WriteOp.Bytes(originalManifestRaw)
        }
        overrides.putAll(writes)

        val out = BufferedOutputStream(FileOutputStream(outFile), 1 shl 20)
        val writer = ZipWriter(out)
        try {
            val entries = reader.entries.values.toList()
            val done = HashSet<String>()
            var index = 0
            for (entry in entries) {
                index++
                if (index % 200 == 0) {
                    onProgress("正在重新打包（$index/${entries.size}）…", 5 + (index * 75 / entries.size))
                }
                if (deletes.contains(entry.name) || isSignatureEntry(entry.name)) {
                    done.add(entry.name)
                    continue
                }
                val override = overrides[entry.name]
                if (override != null) {
                    val data = when (override) {
                        is WriteOp.Bytes -> override.data
                        is WriteOp.FromFile -> override.file.readBytes()
                    }
                    writer.addBytes(
                        name = entry.name,
                        data = data,
                        compress = shouldCompress(entry.name),
                        dosTime = entry.dosTime,
                        dosDate = entry.dosDate,
                        externalAttrs = entry.externalAttrs,
                    )
                    done.add(entry.name)
                    continue
                }
                writer.addRawEntry(reader, entry)
            }

            for ((name, op) in overrides) {
                if (done.contains(name) || deletes.contains(name) || isSignatureEntry(name)) continue
                val data = when (op) {
                    is WriteOp.Bytes -> op.data
                    is WriteOp.FromFile -> op.file.readBytes()
                }
                writer.addBytes(name, data, compress = shouldCompress(name))
            }

            onProgress("正在写入中央目录…", 85)
            writer.finish()
        } catch (err: Throwable) {
            out.close()
            outFile.delete()
            throw err
        } finally {
            out.close()
        }
    }

    /** 导出完成后清理「未导出改动」标记 */
    fun markExported() {
        dirtySinceExport = false
    }

    override fun close() {
        reader.close()
    }
}
