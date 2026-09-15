package dev.local.arcaea.apkmanager.core

import java.io.File

/** 从资源压缩包里解析出来的内容：资源文件 + 可选的单曲元数据片段 */
data class SongResourceBundle(
    /** 规范化后的文件名 → 已落地的本地文件（写入 `assets/songs/<id>/`） */
    val files: List<Pair<String, File>>,
    /** 压缩包里带的 songlist / slst / songlist.txt / song.json 片段（可能为空） */
    val metadata: JsonObject?,
    /**
     * 包内没有 songdata 时，从主难度谱面（2.aff→3.aff→4.aff）识别出的 BPM。
     * 有 songdata 时为 null（以 songdata 为准）。用于填充 bpm / bpm_base。
     */
    val bpm: SongBpmInfo? = null,
    /** 导入过程中发现的提示（例如某个谱面文件是空谱面，物量为 0） */
    val warnings: List<String> = emptyList(),
    /**
     * 不属于歌曲目录、需要写到 APK 其它位置的资源：
     * 「相对 APK 根的完整路径 → 已落地的本地文件」，例如
     * `assets/img/bg/1080/djmax_wagd.jpg`（压缩包把背景图放在标题目录下时会被归到这里）。
     */
    val extras: List<Pair<String, File>> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty() && metadata == null && extras.isEmpty()
}

/**
 * 判断一段 Arcaea 谱面文本是否「空谱面」（没有任何音符/物件，游戏里物量为 0）。
 *
 * 策略：先剔除所有 `timing(...)`（计时语句里也含 `(` `)`，不能当作音符证据），
 * 再检查剩余文本里是否有任何「物件命令」：arc / hold / flick / count，
 * 或地面 note `(tick, lane);`。
 *
 * 注意不依赖「行首」正则：真实谱面可能用 CR-only 换行、把多个 note 写在同一行，
 * 或带缩进——这些都必须是有效的非空谱面。
 */
fun isChartEmpty(affText: String): Boolean {
    if (affText.isBlank()) return true
    // 去掉所有 timing(...) 调用（含可选结尾分号），它们不是音符
    val nonTiming = affText.replace(Regex("timing\\s*\\([^)]*\\)\\s*;?"), "")
    // 弧线 / 长条 / 微调 / 分离 tap：arc(...) / hold(...) / flick(...) / count(...)
    val hasNoteCommand = Regex("\\b(?:arc|hold|flick|count)\\s*\\(").containsMatchIn(nonTiming)
    // 地面 note：(tick, lane); —— 不限制行首，兼容同行情景与 CR 换行
    val hasGroundNote = Regex("\\(\\s*-?\\d+\\s*,\\s*-?\\d+\\s*\\)\\s*;").containsMatchIn(nonTiming)
    return !hasNoteCommand && !hasGroundNote
}

/** 从谱面文本解析出来的 BPM 信息 */
data class SongBpmInfo(
    /** BPM 显示值：单数值，或「最小值-最大值」的范围 */
    val range: String,
    /** 谱面中占时最长（主导）的 BPM */
    val base: Double,
)

/**
 * 解析 Arcaea 谱面文本中的 BPM。
 * - range：读取所有 `timing(...)` 的 BPM（含区间渐变的端点），跨度为 min-max；
 * - base：按相邻 timing 的起始位置差近似「占时」，取累计占时最长的 BPM。
 */
fun parseSongBpm(affText: String): SongBpmInfo? {
    data class Seg(val start: Double, val bpm: Double)
    val segs = ArrayList<Seg>()
    val timingRe = Regex("timing\\s*\\(([^)]*)\\)")
    timingRe.findAll(affText).forEach { m ->
        val parts = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // timing 以三元组堆叠：(tick, bpm, divisor) [，(tick2, bpm2, divisor2) …]
        var i = 0
        while (i + 2 < parts.size) {
            val tick = parts[i].toDoubleOrNull() ?: break
            val bpm = parts[i + 1].toDoubleOrNull()
            i += 3
            if (bpm != null) segs.add(Seg(tick, bpm))
        }
    }
    if (segs.isEmpty()) return null

    val bs = segs.map { it.bpm }.distinct().sorted()
    val range = if (bs.size <= 1) { fmtBpm(bs.first()) } else { "${fmtBpm(bs.first())}-${fmtBpm(bs.last())}" }

    // 占时：相邻 timing 的 start 差；末尾段不给时长（无后续起点则无法估算）
    val ordered = segs.sortedBy { it.start }
    val durationByBpm = mutableMapOf<Double, Double>()
    for (i in ordered.indices) {
        if (i >= ordered.lastIndex) break
        val dur = ordered[i + 1].start - ordered[i].start
        durationByBpm[ordered[i].bpm] = (durationByBpm[ordered[i].bpm] ?: 0.0) + dur
    }
    val base = if (durationByBpm.isEmpty()) {
        segs.maxByOrNull { it.start }?.bpm ?: bs.first()
    } else {
        durationByBpm.maxByOrNull { it.value }?.key
            ?: bs.first()
    }
    return SongBpmInfo(range, base)
}

/** BPM 数字格式化：去掉多余的尾 0，保留最多两位小数 */
private fun fmtBpm(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.')

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
        // 社区打包常见的变体（如 latentduality.zip 用 songlist.json）
        "songlist.json",
        "slst",
        "song.json",
        // 资源包里常见的单曲数据文件（结构与 songlist 的单曲条目一致）
        "songdata.json",
        "songdata",
    )

    /**
     * 出现在歌曲目录之外的 APK / 资源目录前缀。
     * 这些路径即使扩展名看起来像资源（.png/.jpg），也不该写进歌曲目录，
     * 否则 `assets/img/bg/...`、`META-INF/...` 等会被误当成单曲资源。
     */
    private val NON_SONG_ROOTS = listOf(
        "assets/songs/pack/",
        "assets/img/",
        "assets/char/",
        "assets/music/",
        "META-INF/",
        "lib/",
        "res/",
        "kotlin/",
    )

    /** 非 `assets/songs/` 结构时允许的最大层级（兼容 `<标题>/<歌曲id>/资源文件`） */
    private const val MAX_NESTING = 3

    /**
     * 单曲目录里允许出现的资源扩展名。
     * 只接受这些类型，可以避免把压缩包里的无关文件（如 AndroidManifest.xml、classes.dex、
     * META-INF 下的 .version 等）写进歌曲目录。
     */
    private val RESOURCE_EXTENSIONS = setOf(
        "aff", "ogg", "opus", "mp3", "wav", "m4a", "aac", "flac",
        "jpg", "jpeg", "png", "webp", "bmp",
    )

    /** 是否是单曲资源文件（按扩展名判断） */
    fun isResourceFileName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in RESOURCE_EXTENSIONS
    }

    /**
     * 该条目是否是「一个完整 APK」的特征文件。
     * 常见误用是把整个 APK 改名成 .zip 再当作单曲资源包导入，这里用于及早给出明确报错。
     */
    fun isApkMarkerEntry(rawName: String): Boolean {
        val name = rawName.replace('\\', '/')
        val base = name.substringAfterLast('/')
        if (base == "AndroidManifest.xml") return true
        if (base == "resources.arsc") return true
        if (Regex("^classes\\d*\\.dex$").matches(base)) return true
        if (name.startsWith("META-INF/") &&
            Regex("\\.(RSA|SF|DSA|EC)$", RegexOption.IGNORE_CASE).containsMatchIn(base)
        ) {
            return true
        }
        return false
    }

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
        val base = name.substringAfterLast('/').lowercase()
        if (base in METADATA_NAMES) return true
        // 兼容 songdata.json 的常见变体命名（如 songdata..json / songdata_v2.json / songdata_payload.json），
        // 否则这些文件会被当成普通文件、既读不到也不会写入歌曲目录。仅当以 songdata 开头且以 .json 结尾才匹配。
        if (base.startsWith("songdata") && base.endsWith(".json")) return true
        return false
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
            if (NON_SONG_ROOTS.any { name.startsWith(it) }) return null
            val parts = name.split('/').filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> return null
                // 根目录直接放文件 / `<歌曲id>/文件` / `<标题>/<歌曲id>/文件` 三种都取最深层文件名。
                // 后者是社区常见打包方式（如 Lost Requiem.zip），旧实现会整包丢弃。
                parts.size <= MAX_NESTING -> parts.last()
                else -> return null // 层级过深，忽略（避免误吞嵌套目录）
            }
        }
        return candidate.takeIf { isSafeFileName(it) && isResourceFileName(it) }
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
