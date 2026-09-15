package dev.local.arcaea.apkmanager.data

import android.content.Context
import android.net.Uri
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.BuildOptions
import dev.local.arcaea.apkmanager.core.JsonObject
import dev.local.arcaea.apkmanager.core.JsonString
import dev.local.arcaea.apkmanager.core.ResourceZip
import dev.local.arcaea.apkmanager.core.SongBpmInfo
import dev.local.arcaea.apkmanager.core.isChartEmpty
import dev.local.arcaea.apkmanager.core.parseSongBpm
import dev.local.arcaea.apkmanager.core.Signer
import dev.local.arcaea.apkmanager.core.SongResourceBundle
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 导出结果 */
data class ExportSummary(
    val sizeBytes: Long,
    val signProblems: List<String>,
    /** 保留在缓存里的导出产物大小（可用「清除缓存」释放） */
    val cachedApkBytes: Long,
)

/** 缓存占用明细 */
data class CacheUsage(
    /** 导入的源 APK 副本（关闭工程后需要重新导入） */
    val sourceApkBytes: Long = 0,
    /** 上次导出的 APK 缓存 + 其它残留的 APK 中间产物 */
    val cachedApkBytes: Long = 0,
    /** 导入资源 / 改 id 产生的临时数据 + 自检文件 */
    val projectDataBytes: Long = 0,
) {
    val totalBytes: Long get() = sourceApkBytes + cachedApkBytes + projectDataBytes
    val isEmpty: Boolean get() = totalBytes == 0L
}

/**
 * 工程数据的读写与「导入 / 导出 APK」的落地实现。
 *
 * 设计：不依赖任何存储权限——导入用 SAF 选择文件，复制到应用私有工作目录后再处理；
 * 导出则先在工作目录产出未签名包、签好名，再写回 SAF 目标。
 */
class AppRepository(private val context: Context) {

    /** 工作目录：优先用应用专属外部目录（空间大），不可用则退回内部目录 */
    val workDir: File
        get() {
            val external = context.getExternalFilesDir("work")
            val dir = external ?: File(context.filesDir, "work")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val inputApk: File get() = File(workDir, "input.apk")
    private val unsignedApk: File get() = File(workDir, "unsigned.apk")
    private val signedApk: File get() = File(workDir, "signed.apk")

    /** 上次导出后保留在缓存里的产物；由「清除缓存」释放 */
    val cachedExportApk: File get() = File(workDir, "last-export.apk")

    /** 解压目录的唯一序号，避免同一毫秒内多次导入撞名 */
    private var zipSequence = 0

    fun assetBytes(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    /** 内置的自动签名密钥 */
    fun keystoreBytes(): ByteArray = assetBytes(KEYSTORE_ASSET)

    /** 内置的自检用小型 APK */
    fun selfTestApkBytes(): ByteArray = assetBytes(SELFTEST_ASSET)

    fun freeSpaceBytes(): Long = workDir.usableSpace

    /** 把用户选择的 APK 复制到工作目录（SAF URI 不能直接随机读取，必须先落地） */
    fun importApk(uri: Uri, onProgress: (String, Int) -> Unit = { _, _ -> }): File {
        val target = inputApk
        target.delete()
        onProgress("正在复制安装包到工作目录…", 0)
        val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress("正在复制安装包…", ((copied * 100) / total).toInt())
                }
            }
        } ?: throw IOException("无法读取所选文件")
        if (target.length() == 0L) throw IOException("所选文件为空")
        onProgress("复制完成", 100)
        return target
    }

    /** 打开工程（读取失败会抛出带中文说明的异常） */
    fun openProject(): ApkProject = ApkProject(inputApk)

    /**
     * 导出：重新打包 → 应用内签名 → 写回目标 URI。
     * 需要约 2 倍 APK 大小的临时空间（未签名包 + 签名包）。
     */
    fun export(
        project: ApkProject,
        target: Uri,
        options: BuildOptions,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): ExportSummary {
        unsignedApk.delete()
        signedApk.delete()
        try {
            // 让传入的选项真正生效：改包名/标识符改写都记录在工程状态里，由导出阶段读取
            project.setPackageNameOverride(options.packageName, options.rewriteIdentifiers)
            project.exportUnsigned(unsignedApk) { message, percent ->
                onProgress(message, (percent * 0.8).toInt())
            }
            onProgress("正在读取内置签名密钥…", 82)
            val key = Signer.loadKey(keystoreBytes(), Signer.DEFAULT_STORE_PASSWORD)
            val minSdk = (project.manifest.minSdk ?: 26).coerceAtLeast(1)
            Signer.sign(unsignedApk, signedApk, key, minSdk) { message ->
                onProgress(message, 88)
            }

            onProgress("正在校验签名…", 92)
            val problems = Signer.verify(signedApk)

            onProgress("正在保存到所选位置…", 95)
            val total = signedApk.length()
            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                signedApk.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            onProgress("正在保存…", 95 + ((copied * 5) / total).toInt())
                        }
                    }
                }
            } ?: throw IOException("无法写入所选位置")

            onProgress("导出完成", 100)

            // 签名产物保留一份在缓存里（重命名而非复制，不额外占空间），
            // 方便稍后再次另存；用户可在导出完成提示或「清除缓存」里删掉。
            val cached = cachedExportApk
            cached.delete()
            val cachedBytes = if (signedApk.renameTo(cached)) cached.length() else 0L

            return ExportSummary(total, problems, cachedBytes)
        } finally {
            unsignedApk.delete()
            signedApk.delete()
        }
    }

    /** 资源包导入 / 改名搬运用的临时目录（导出后会随 [clearWork] 一起清理） */
    val importDir: File
        get() = File(workDir, "import").apply { if (!exists()) mkdirs() }

    /**
     * 轻量读取压缩包里的曲目元数据片段（songdata.json / songlist / slst …），
     * **不落地任何文件**，仅用于界面上「选择 zip 后立刻反馈已读取到的曲目信息」。
     * 返回解析出的第一个对象；压缩包里没有元数据时返回 `null`。
     */
    fun readResourceMetadata(uri: Uri): JsonObject? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (ResourceZip.isMetadataName(entry.name)) {
                        val fragment = ResourceZip.parseSongFragment(zip.readBytes())
                        zip.closeEntry()
                        if (fragment != null) return fragment
                    } else {
                        zip.closeEntry()
                    }
                }
                return null
            }
        }
        return null
    }

    /**
     * 从压缩包里读取「主难度」谱面的 BPM（优先级 2.aff → 3.aff → 4.aff）。
     * 用于没有 songdata.json 的压缩包：根据谱面里出现的 timing 识别 BPM 范围与主导 BPM。
     *
     * 压缩包条目的出现顺序不保证按难度排序，因此这里扫描完整个包后，
     * 取难度数值最小（2 < 3 < 4）的那份，而不是遇到第一个就返回。
     */
    fun readResourceBpmFromZip(uri: Uri): SongBpmInfo? {
        var best: SongBpmInfo? = null
        var bestDifficulty = 99
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val base = entry.name.replace('\\', '/').substringAfterLast('/')
                    val difficulty = base.removeSuffix(".aff").takeIf { it.length == 1 }?.toIntOrNull()
                    if (difficulty != null && difficulty in 2..4 && !ResourceZip.isMetadataName(entry.name)) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        zip.closeEntry()
                        parseSongBpm(text)?.let { info ->
                            // 主难度优先级：难度数值越小越高（2 → 3 → 4）
                            if (difficulty < bestDifficulty) {
                                best = info
                                bestDifficulty = difficulty
                            }
                        }
                        continue
                    }
                    zip.closeEntry()
                }
                return best
            }
        }
        return null
    }

    /**
     * 解压用户选择的资源压缩包，并按 [ResourceZip] 的规则规范化条目名
     * （支持根目录 / `<歌曲id>/…` / `assets/songs/<歌曲id>/…` 三种结构），
     * 同时尝试解析包内的单曲元数据片段（songlist / slst / songlist.txt / song.json）。
     */
    fun extractResourceZip(uri: Uri, onProgress: (String, Int) -> Unit = { _, _ -> }): SongResourceBundle {
        // 每次都用一个全新的子目录：以前复用同一个目录并在开头 deleteRecursively()，
        // 导致「第二次导入资源包」会把第一次导入、尚未导出（仅暂存）的临时文件删掉，
        // 最终导出时报 ENOENT。这里改成唯一目录，之前的暂存文件不再受影响。
        val outDir = File(importDir, "zip-${System.currentTimeMillis()}-${zipSequence++}").apply { mkdirs() }
        val files = LinkedHashMap<String, File>()
        val warnings = mutableListOf<String>()
        var metadata: JsonObject? = null
        // 没有 songdata 时，从主难度谱面识别 BPM：按难度优先级 2→3→4（数值越小优先级越高）
        var bpmFromAff: SongBpmInfo? = null
        var bpmPriority = 99
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val raw = entry.name

                    // 及早识别「把整个 APK 改名成 zip」这种误用，避免把无关文件写进歌曲目录
                    if (ResourceZip.isApkMarkerEntry(raw)) {
                        throw IOException(
                            "这个压缩包看起来是一个完整的 APK（检测到 $raw），不是单曲资源包。\n" +
                                "请改为：只把该歌曲的资源文件（谱面 .aff、音频 .ogg、封面 .jpg/png，可含 songdata.json）" +
                                "打成一个压缩包；若要修改整个安装包，请到「工程」页导入 APK。",
                        )
                    }

                    if (ResourceZip.isMetadataName(raw)) {
                        val fragment = ResourceZip.parseSongFragment(zip.readBytes())
                        if (fragment != null && metadata == null) {
                            metadata = fragment
                            onProgress("已读取资源包内的曲目信息", 0)
                        }
                        zip.closeEntry()
                        continue
                    }

                    val name = ResourceZip.normalizeEntryName(raw)
                    if (name == null) {
                        zip.closeEntry()
                        continue
                    }
                    val file = File(outDir, name)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    files[name] = file
                    // 空谱面检测：.aff 没有任何音符（物量为 0，游戏中无法游玩）
                    if (name.endsWith(".aff", ignoreCase = true) && file.exists()) {
                        val text = file.readBytes().toString(Charsets.UTF_8)
                        if (isChartEmpty(text)) {
                            warnings.add(
                                "$name 是空谱面（只有 AudioOffset/timing，没有任何音符），" +
                                    "游戏里会显示物量 0、无法游玩。请提供含音符的 .aff。",
                            )
                        }
                    }
                    // BPM 识别：只在没有 songdata 时，按主难度优先级（2→3→4）记录
                    if (metadata == null && name.endsWith(".aff", ignoreCase = true)) {
                        val difficulty = name.substringBeforeLast('.').toIntOrNull()
                        if (difficulty != null && difficulty in 2..4 && file.exists() && difficulty < bpmPriority) {
                            parseSongBpm(file.readText(Charsets.UTF_8))?.let {
                                bpmFromAff = it
                                bpmPriority = difficulty
                            }
                        }
                    }
                    onProgress("正在解压 $name…", 0)
                    zip.closeEntry()
                }
            }
        } ?: throw IOException("无法读取所选压缩包")

        // 背景图归类：社区 zip 常把 `<bg>.jpg` 放在标题目录下（如 Lost Requiem/djmax_wagd.jpg），
        // 但游戏只从 assets/img/bg/1080/ 读取背景图，所以按 songlist 的 bg 字段把同名图片挪到那里。
        // 排除常规封面名（base.jpg / 1080_base.jpg 等），避免把封面误当成背景图。
        val extras = ArrayList<Pair<String, File>>()
        val bg = (metadata?.get("bg") as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() }
        if (bg != null) {
            val jacketNames = setOf(
                "base.jpg", "base.png", "base_256.jpg", "base_256.png",
                "1080_base.jpg", "1080_base.png", "1080_base_256.jpg", "1080_base_256.png",
            )
            for (ext in listOf("jpg", "jpeg", "png", "webp", "bmp")) {
                val key = files.keys.firstOrNull {
                    it.equals("$bg.$ext", ignoreCase = true) && it.lowercase() !in jacketNames
                } ?: continue
                val moved = files.remove(key) ?: continue
                extras.add("assets/img/bg/1080/${bg.lowercase()}.$ext" to moved)
                break
            }
        }

        if (files.isEmpty() && metadata == null && extras.isEmpty()) throw IOException("压缩包里没有可用的歌曲资源")
        onProgress("解压完成（${files.size + extras.size} 个文件）", 100)
        return SongResourceBundle(
            files.toList(), metadata, bpmFromAff?.takeIf { metadata == null }, warnings, extras,
        )
    }

    /** 当前缓存占用明细 */
    fun cacheUsage(): CacheUsage = CacheUsage(
        sourceApkBytes = inputApk.takeIf { it.exists() }?.length() ?: 0L,
        cachedApkBytes = listOf(cachedExportApk, unsignedApk, signedApk)
            .filter { it.exists() }
            .sumOf { it.length() },
        projectDataBytes = listOf(File(workDir, "import"), File(workDir, "selftest"))
            .sumOf { dirSize(it) },
    )

    /**
     * 按需清理缓存，返回释放的字节数。
     *
     * 说明：删除源 APK 后**当前会话仍可继续导出**（读取走的是已打开的文件句柄），
     * 但关闭工程或重启应用后必须重新导入；若还有未导出的改动依赖导入/改 id 的临时数据，
     * 就不要勾选「项目缓存数据」。
     */
    fun clearCache(
        deleteSourceApk: Boolean = false,
        deleteCachedApk: Boolean = true,
        deleteProjectData: Boolean = true,
    ): Long {
        val before = cacheUsage().totalBytes
        if (deleteSourceApk) inputApk.delete()
        if (deleteCachedApk) {
            cachedExportApk.delete()
            unsignedApk.delete()
            signedApk.delete()
        }
        if (deleteProjectData) {
            File(workDir, "import").deleteRecursively()
            File(workDir, "selftest").deleteRecursively()
        }
        return (before - cacheUsage().totalBytes).coerceAtLeast(0L)
    }

    /** 清理整个工作目录（关闭工程时使用） */
    fun clearWork() {
        inputApk.delete()
        unsignedApk.delete()
        signedApk.delete()
        cachedExportApk.delete()
        File(workDir, "import").deleteRecursively()
        File(workDir, "selftest").deleteRecursively()
    }

    /**
     * 一键清空应用全部数据：删除整个工作目录（含导入的源 APK、打包中间产物、暂存资源、
     * 自检文件）+ 应用缓存目录 + 外部缓存目录。常用于「几乎用完了 / 想彻底重置」的场景。
     * 注意：会关闭当前工程，尚未导出的改动一并丢失。
     */
    fun clearAllData() {
        clearWork()
        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val KEYSTORE_ASSET = "auto-sign.p12"
        const val SELFTEST_ASSET = "selftest.apk"
    }
}
