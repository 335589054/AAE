package dev.local.arcaea.apkmanager.core

import java.io.File

/** 从资源压缩包里解析出来的内容：资源文件 + 可选的单曲元数据片段 */
data class SongResourceBundle(
    /** 规范化后的文件名 → 已落地的本地文件 */
    val files: List<Pair<String, File>>,
    /** 压缩包里带的 songlist / slst / songlist.txt / song.json 片段（可能为空） */
    val metadata: JsonObject?,
) {
    val isEmpty: Boolean get() = files.isEmpty() && metadata == null
}

/**
 * 资源压缩包（zip）的解析规则，与 Windows 版保持一致。
 *
 * 支持三种常见结构：
 * 1. 根目录直接放文件：`2.aff` / `base.ogg` / `base.jpg` …
 * 2. 外面套一层歌曲目录：`<歌曲id>/2.aff` …
 * 3. 完整 APK 结构：`assets/songs/<歌曲id>/2.aff` …（会自动剥离前缀）
 */
object ResourceZip {

    /** 单曲元数据片段的文件名（不带扩展名判断大小写） */
    private val METADATA_NAMES = setOf(
        "songlist",
        "songlist.txt",
        "slst",
        "song.json",
        // 资源包里常见的单曲数据文件（结构与 songlist 的单曲条目一致）
        "songdata.json",
        "songdata",
    )

    /**
     * 写入歌曲目录的文件名必须安全：非空、不以点开头、不含路径分隔符。
     * 注意要在**切分之后**判断，否则 `X/.outside` 这类条目会漏过。
     */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && !name.contains('/') && !name.contains('\\')

    /** 判断某个 zip 条目是否为单曲元数据片段 */
    fun isMetadataName(rawName: String): Boolean {
        val name = rawName.replace('\\', '/').trim()
        if (name.isEmpty() || name.endsWith("/")) return false
        if (name.contains("assets/songs/pack/")) return false
        return name.substringAfterLast('/').lowercase() in METADATA_NAMES
    }

    /**
     * 条目名规范化：返回要写入歌曲目录的文件名；返回 null 表示忽略该条目
     * （目录、macOS 垃圾文件、层级过深的内容等）。
     */
    fun normalizeEntryName(rawName: String): String? {
        val name = rawName.replace('\\', '/').trim()
        if (name.isEmpty() || name.endsWith("/")) return null
        if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name == ".DS_Store") return null
        if (name.startsWith(".")) return null
        if (isMetadataName(name)) return null // 元数据片段由调用方单独处理，不写入歌曲目录

        val songsIdx = name.indexOf("assets/songs/")
        val candidate = if (songsIdx >= 0) {
            val rest = name.substring(songsIdx + "assets/songs/".length)
            val slash = rest.indexOf('/')
            // 这一层是 songlist / packlist 之类的整包元数据，不写入单曲目录
            if (slash < 0 || rest.startsWith("pack/")) return null
            rest.substring(slash + 1)
        } else {
            val parts = name.split('/').filter { it.isNotEmpty() }
            when (parts.size) {
                1 -> parts[0]   // 根目录直接放文件
                2 -> parts[1]   // <歌曲目录>/文件
                else -> return null // 层级过深，忽略（避免误吞嵌套目录）
            }
        }
        return candidate.takeIf { isSafeFileName(it) }
    }

    /** 从元数据片段里取出第一个歌曲对象；兼容 {"songs":[…]} / {"song":{…}} / 裸对象 */
    fun parseSongFragment(bytes: ByteArray): JsonObject? {
        val text = Json.stripBom(bytes.toString(Charsets.UTF_8)).trim()
        if (text.isEmpty()) return null
        return try {
            when (val parsed = Json.parse(text)) {
                is JsonObject -> {
                    val fromSongs = parsed.optArray("songs")?.takeIf { it.size > 0 }?.let { it.get(0) as? JsonObject }
                    fromSongs ?: (parsed["song"] as? JsonObject) ?: parsed
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把元数据片段合并进新建的歌曲：**id 与曲包始终以用户填写的为准**。
     * 其余字段（曲名、曲师、BPM、背景、日期、版本、预览区间、难度列表…）若片段里有就覆盖默认值。
     */
    fun mergeSongFragment(target: Song, fragment: JsonObject) {
        val keepId = target.id
        val keepSet = target.set
        for (key in fragment.keys) {
            if (key == "id" || key == "set") continue
            fragment[key]?.let { target.json.put(key, it) }
        }
        target.id = keepId
        target.set = keepSet

        // 难度条目会整体来自 songdata.json（即「自动添加难度」），
        // 但有些文件只写了 ratingClass / rating，这里补齐其余字段，保证结构完整可导。
        for (difficulty in target.difficultyList()) {
            if (difficulty["chartDesigner"] == null) difficulty.put("chartDesigner", JsonString(""))
            if (difficulty["jacketDesigner"] == null) difficulty.put("jacketDesigner", JsonString(""))
            if (difficulty["rating"] == null) difficulty.put("rating", JsonNumber.of(0))
        }
    }
}
