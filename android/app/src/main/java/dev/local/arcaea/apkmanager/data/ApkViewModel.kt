package dev.local.arcaea.apkmanager.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.BuildOptions
import dev.local.arcaea.apkmanager.core.AffTransform
import dev.local.arcaea.apkmanager.core.SongBpmInfo
import dev.local.arcaea.apkmanager.core.SongZip
import dev.local.arcaea.apkmanager.core.JsonArray
import dev.local.arcaea.apkmanager.core.JsonNumber
import dev.local.arcaea.apkmanager.core.JsonObject
import dev.local.arcaea.apkmanager.core.JsonString
import dev.local.arcaea.apkmanager.core.Pack
import dev.local.arcaea.apkmanager.core.ProjectSnapshot
import dev.local.arcaea.apkmanager.core.SelfTest
import dev.local.arcaea.apkmanager.core.Signer
import dev.local.arcaea.apkmanager.core.Song
import dev.local.arcaea.apkmanager.core.bufferStartWithBar
import dev.local.arcaea.apkmanager.core.chartEndMs
import dev.local.arcaea.apkmanager.audio.Ffmpeg
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 导出完成后弹出的清理提示：让用户决定是否删除各类缓存 */
data class ExportCleanupPrompt(
    /** 刚保存到哪（文件名或目标描述） */
    val target: String,
    val usage: CacheUsage,
    /** 是否还有未导出的改动（有的话不允许清理项目缓存数据） */
    val hasPendingChanges: Boolean,
)

/** 练习谱生成请求参数（由 PracticeDialog 收集后交给 [ApkViewModel.generatePractice]） */
data class PracticeRequest(
    val songId: String,
    /** 要处理的难度 ratingClass 列表（0..4） */
    val rcs: List<Int>,
    /** 切割起始（毫秒，用户选择的区间起点，内部会再前移 1 小节做缓冲） */
    val startMs: Long,
    /** 切割结束（毫秒） */
    val endMs: Long,
    /** 变速倍速（<1 慢放、>1 快放） */
    val speed: Double,
    /** 复制新曲时的目标目录 id；原地修改时忽略 */
    val newId: String? = null,
    /** 复制新曲时的显示名（可为空，默认沿用源曲名） */
    val newTitle: String? = null,
    /** true=原地覆盖原谱面/音频；false=新建一首歌 */
    val overwriteInPlace: Boolean = false,
)

/** 界面状态。project 内部是可变的，界面请以 [version] 作为「需要重新读取」的信号。 */
data class UiState(
    val busy: Boolean = false,
    val stage: String = "",
    val progress: Int = 0,
    val project: ApkProject? = null,
    val snapshot: ProjectSnapshot? = null,
    val version: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val lastExport: String? = null,
    val lastExportWarnings: List<String> = emptyList(),
    val selfTestSummary: String? = null,
    val showSelfTest: Boolean = false,
    val freeSpace: Long = 0L,
    /** 当前缓存占用明细 */
    val cache: CacheUsage = CacheUsage(),
    /** 非空时界面弹出「导出完成 / 清理缓存」对话框 */
    val exportCleanup: ExportCleanupPrompt? = null,
    /** 已保存的包名预设 */
    val packagePresets: List<String> = emptyList(),
    /** 上次实际用过的包名，用于预填导出对话框 */
    val lastPackageName: String? = null,
)

/**
 * 界面与核心之间的唯一入口。
 * 所有会改工程的写操作都通过 [mutex] + 单线程调度串行化，避免与界面读取竞争。
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ApkViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)
    private val presets = PackageNamePresets(app)
    private val mutex = Mutex()
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    /** 签名密钥指纹只算一次 */
    private var fingerprintCache: String? = null

    private val _state = MutableStateFlow(UiState(freeSpace = repo.freeSpaceBytes()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            packagePresets = presets.all(),
            lastPackageName = presets.lastUsed,
            cache = repo.cacheUsage(),
        )
    }

    private fun version() = _state.value.version + 1

    private fun publish(
        project: ApkProject? = _state.value.project,
        busy: Boolean = _state.value.busy,
        stage: String = _state.value.stage,
        progress: Int = _state.value.progress,
        message: String? = _state.value.message,
        error: String? = _state.value.error,
        lastExport: String? = _state.value.lastExport,
        lastExportWarnings: List<String> = _state.value.lastExportWarnings,
        selfTestSummary: String? = _state.value.selfTestSummary,
        showSelfTest: Boolean = _state.value.showSelfTest,
        exportCleanup: ExportCleanupPrompt? = _state.value.exportCleanup,
        packagePresets: List<String> = _state.value.packagePresets,
        lastPackageName: String? = _state.value.lastPackageName,
    ) {
        _state.value = _state.value.copy(
            project = project,
            snapshot = project?.snapshot(),
            version = version(),
            busy = busy,
            stage = stage,
            progress = progress,
            message = message,
            error = error,
            lastExport = lastExport,
            lastExportWarnings = lastExportWarnings,
            selfTestSummary = selfTestSummary,
            showSelfTest = showSelfTest,
            exportCleanup = exportCleanup,
            packagePresets = packagePresets,
            lastPackageName = lastPackageName,
            freeSpace = repo.freeSpaceBytes(),
            cache = repo.cacheUsage(),
        )
    }

    fun consumeMessage() = publish(message = null, error = null)
    fun dismissSelfTest() = publish(showSelfTest = false)
    fun refresh() = publish()

    /* -------------------------------- 缓存清理 -------------------------------- */

    /** 关闭「导出完成」的清理提示 */
    fun dismissExportCleanup() = publish(exportCleanup = null)

    /**
     * 清理缓存（导入的源 APK / 缓存的导出 APK / 项目临时数据）。
     *
     * 存在未导出的改动时**不会**清理「项目缓存数据」——那些改动依赖导入、改 id 时产生的临时文件，
     * 删掉会让它们失效（提示里会说明）。
     */
    fun clearCache(deleteSourceApk: Boolean, deleteCachedApk: Boolean, deleteProjectData: Boolean) {
        val pending = _state.value.project?.dirtySinceExport == true
        val allowProjectData = deleteProjectData && !pending
        viewModelScope.launch(writeDispatcher) {
            mutex.withLock {
                val freed = repo.clearCache(deleteSourceApk, deleteCachedApk, allowProjectData)
                val skipped = if (deleteProjectData && !allowProjectData) {
                    "（存在未导出的改动，已跳过项目缓存数据）"
                } else {
                    ""
                }
                val sourceHint = if (deleteSourceApk) "。已删除导入的源 APK，关闭工程后需要重新导入" else ""
                publish(
                    exportCleanup = null,
                    message = if (freed > 0) "已释放 ${formatMb(freed)}$skipped$sourceHint" else "没有可清理的缓存$skipped",
                )
            }
        }
    }

    /* ------------------------------ 包名预设 ------------------------------ */

    /** 保存一个包名预设（已存在则提到最前），下次导出可一键填入 */
    fun savePackagePreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        publish(packagePresets = presets.add(trimmed), message = "已保存包名预设：$trimmed")
    }

    /** 删除一个包名预设 */
    fun removePackagePreset(name: String) {
        publish(packagePresets = presets.remove(name), message = "已删除包名预设：$name")
    }

    private fun formatMb(bytes: Long): String =
        if (bytes >= 1024L * 1024L * 1024L) {
            "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        } else {
            "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }

    /* ------------------------------ 导入 / 导出 ------------------------------ */

    fun importApk(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            publish(busy = true, stage = "正在导入安装包…", progress = 0, error = null, message = null)
            try {
                withContext(writeDispatcher) {
                    mutex.withLock {
                        _state.value.project?.close()
                        repo.importApk(uri) { stage, percent ->
                            publish(busy = true, stage = stage, progress = percent)
                        }
                        publish(busy = true, stage = "正在读取 songlist…", progress = 96)
                        val project = repo.openProject()
                        publish(
                            project = project,
                            busy = false,
                            stage = "",
                            progress = 100,
                            message = "已导入：${project.manifest.packageName}（${project.songs.size} 首 / ${project.packs.size} 个曲包）",
                            lastExport = null,
                            lastExportWarnings = emptyList(),
                        )
                    }
                }
            } catch (err: Throwable) {
                withContext(Dispatchers.Main) {
                    _state.value.project?.close()
                    publish(project = null, busy = false, stage = "", progress = 0, error = err.message ?: err.toString())
                }
            }
        }
    }

    fun closeProject() {
        viewModelScope.launch(writeDispatcher) {
            mutex.withLock {
                _state.value.project?.close()
                repo.clearWork()
                publish(project = null, message = null, error = null, lastExport = null, lastExportWarnings = emptyList())
            }
        }
    }

    /**
     * 一键清空应用全部数据（工作目录 + 应用/外部缓存），并关闭当前工程。
     * 供「清空全部数据」按钮使用，用于彻底释放存储空间。
     */
    fun wipeAllData() {
        viewModelScope.launch(writeDispatcher) {
            mutex.withLock {
                _state.value.project?.close()
                repo.clearAllData()
                publish(project = null, message = null, error = null, lastExport = null, lastExportWarnings = emptyList())
                publish(message = "已清空应用全部数据")
            }
        }
    }

    /**
     * 轻量读取资源压缩包里的曲目元数据（songdata.json / songlist / slst …），
     * 选择 zip 后立刻返回给界面做即时反馈（不落地文件）。
     */
    fun readResourceZipMetadata(uri: Uri): JsonObject? = repo.readResourceMetadata(uri)

    /** 从压缩包主难度谱面识别 BPM（2.aff→3.aff→4.aff），无 songdata 时用于填充 BPM。 */
    fun readResourceBpmFromZip(uri: Uri): SongBpmInfo? = repo.readResourceBpmFromZip(uri)

    fun exportTo(uri: Uri, packageName: String?, rewriteIdentifiers: Boolean) {
        val project = _state.value.project ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            publish(busy = true, stage = "正在重新打包…", progress = 0, error = null, message = null)
            try {
                withContext(writeDispatcher) {
                    mutex.withLock {
                        val summary = repo.export(project, uri, BuildOptions(packageName, rewriteIdentifiers)) { stage, percent ->
                            publish(busy = true, stage = stage, progress = percent)
                        }
                        project.markExported()
                        packageName?.let { presets.lastUsed = it }
                        publish(
                            busy = false,
                            stage = "",
                            progress = 100,
                            lastExport = formatMb(summary.sizeBytes),
                            lastExportWarnings = summary.signProblems,
                            lastPackageName = presets.lastUsed,
                            message = if (summary.signProblems.isEmpty()) {
                                "导出并签名成功"
                            } else {
                                "导出成功，但签名校验有告警"
                            },
                            exportCleanup = ExportCleanupPrompt(
                                target = uri.lastPathSegment?.substringAfterLast('/') ?: "所选位置",
                                usage = repo.cacheUsage(),
                                hasPendingChanges = project.dirtySinceExport,
                            ),
                        )
                    }
                }
            } catch (err: Throwable) {
                publish(busy = false, stage = "", progress = 0, error = "导出失败：${err.message ?: err}")
            }
        }
    }

    /* -------------------------------- 资源操作 -------------------------------- */

    /** 从 SAF 导入单个资源到指定路径（谱面 / 音频 / 封面） */
    fun importAsset(uri: Uri, relPath: String) {
        val project = _state.value.project ?: return
        runBusy("正在导入资源…") {
            val bytes = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: throw IllegalStateException("无法读取所选文件")
            mutex.withLock {
                project.stageWriteBytes(relPath, bytes)
                publish(message = "已导入 $relPath（${bytes.size} 字节）")
            }
        }
    }

    /** 导入资源压缩包到某首歌的目录（自动剥离 `assets/songs/<id>/` 等前缀） */
    fun importResourceZip(uri: Uri, songId: String) {
        val project = _state.value.project ?: return
        runBusy("正在导入资源包…") {
            val bundle = repo.extractResourceZip(uri) { stage, percent ->
                publish(busy = true, stage = stage, progress = percent)
            }
            mutex.withLock {
                for ((name, file) in bundle.files) {
                    project.stageWriteFile("${ApkProject.SONGS_ROOT}$songId/$name", file)
                }
                val warn = if (bundle.warnings.isEmpty()) "" else "\n\n" + bundle.warnings.joinToString("\n")
                publish(message = "已从压缩包导入 ${bundle.files.size} 个文件到 $songId$warn")
            }
        }
    }

    fun deleteAsset(relPath: String) {
        val project = _state.value.project ?: return
        runBusy("正在删除…") {
            mutex.withLock {
                project.stageDelete(relPath)
                publish(message = "已标记删除 $relPath")
            }
        }
    }

    fun exportAssetTo(uri: Uri, relPath: String) {
        val project = _state.value.project ?: return
        runBusy("正在导出资源…") {
            val bytes = withContext(Dispatchers.IO) { project.readEntry(relPath) }
                ?: throw IllegalStateException("资源不存在：$relPath")
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入所选位置")
            }
            publish(message = "已导出 $relPath")
        }
    }

    /** 读取资源字节（给图片显示用） */
    suspend fun loadAssetBytes(relPath: String): ByteArray? =
        withContext(Dispatchers.IO) { _state.value.project?.readEntry(relPath) }

    /**
     * 读取某首歌曲主难度谱面的最晚时刻（毫秒），用于练习谱生成器的区间滑块上限。
     * 按难度优先 2→3→4 找第一个存在的 aff。
     */
    suspend fun loadChartMaxMs(songId: String): Long {
        val project = _state.value.project ?: return 0L
        return withContext(Dispatchers.IO) {
            for (rc in listOf(2, 3, 4, 1, 0)) {
                val bytes = project.readEntry(ApkProject.songPrefix(songId) + "$rc.aff") ?: continue
                val ms = chartEndMs(bytes.toString(Charsets.UTF_8))
                if (ms > 0) return@withContext (ms / 1000).toLong() * 1000 // 取整到秒
            }
            0L
        }
    }

    /**
     * 反向导出单曲为资源 zip（含 songdata.json + 谱面/音频/封面）到 SAF。
     */
    fun reverseExportSong(uri: Uri, songId: String) {
        val project = _state.value.project ?: return
        runBusy("正在导出单曲…") {
            val song = project.song(songId) ?: throw IllegalStateException("找不到歌曲：$songId")
            mutex.withLock {
                // 收集该歌曲目录下的资源，只待谱面/音频/封面，跳过不需要的
                val files = LinkedHashMap<String, ByteArray>()
                for (name in project.listSongFiles(songId)) {
                    if (!isExportableResource(name)) continue
                    val bytes = withContext(Dispatchers.IO) { project.readEntry(ApkProject.songPrefix(songId) + name) }
                    if (bytes != null) files[name] = bytes
                }
                val out = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                } ?: throw IllegalStateException("无法写入所选位置")
                SongZip.write(song, files, out)
                out.close()
                publish(message = "已导出单曲 ${song.title() ?: song.id}（songdata.json + ${files.size} 个资源文件）")
            }
        }
    }

    /**
     * 生成练习谱：切割 + 变速选中的难度谱面与音频。
     */
    fun generatePractice(req: PracticeRequest) {
        val songId = req.songId
        val rcs = req.rcs
        val overwriteInPlace = req.overwriteInPlace
        val newId = req.newId
        val project = _state.value.project ?: return
        val song = project.song(songId) ?: throw IllegalArgumentException("找不到歌曲：$songId")
        runBusy("正在生成练习谱…") {
            mutex.withLock {
                if (!overwriteInPlace && newId.isNullOrBlank()) {
                    throw IOException("复制新曲时必须指定新的歌曲 id")
                }
                var changed = false
                val summaries = ArrayList<String>()
                val total = rcs.size
                rcs.forEachIndexed { index, rc ->
                    // 进度：每处理一个难度推进一次
                    publish(busy = true, stage = "正在生成 ${Song.difficultyLabel(rc)}…", progress = (index * 100) / total)
                    val affPath = ApkProject.songPrefix(songId) + "$rc.aff"
                    val affBytes = withContext(Dispatchers.IO) { project.readEntry(affPath) }
                        ?: throw IOException("缺少难度 $rc 的谱面 $rc.aff")
                    val affText = affBytes.toString(Charsets.UTF_8)
                    if (req.endMs <= req.startMs) throw IOException("结束时间必须大于开始时间")
                    val bufferStart = bufferStartWithBar(affText, req.startMs.toDouble())
                    if (bufferStart >= req.endMs) throw IOException("所选区间过短，不足预留缓冲")

                    val newAff = AffTransform.slice(affText, bufferStart, req.endMs.toDouble(), req.speed)

                    // 音频：与该难度/基础音频；0..4 难度优先取对应 <rc>.ogg 或 base.ogg
                    val audioName = audioNameFor(project, songId, rc)
                    val audioTmp: File? = if (audioName != null) {
                        val audioBytes = withContext(Dispatchers.IO) { project.readEntry(ApkProject.songPrefix(songId) + audioName) }
                        if (audioBytes != null) {
                            val src = Ffmpeg.uniqueFile(repo.importDir, "audio-src", "ogg")
                            src.writeBytes(audioBytes)
                            val out = Ffmpeg.uniqueFile(repo.importDir, "audio-out", "ogg")
                            Ffmpeg.transform(
                                getApplication<Application>(), src, out,
                                bufferStart.toLong(), (req.endMs - bufferStart).toLong(), req.speed,
                                onProgress = { frac ->
                                    // 音频变速细分：在当前难度区间内 0..1 映射成全局 0..99
                                    val base = (index * 100) / total
                                    publish(
                                        busy = true,
                                        stage = "正在变速音频（${Song.difficultyLabel(rc)}）…",
                                        progress = base + (frac * (100f / total)).toInt().coerceAtMost(99),
                                    )
                                },
                            )
                            src.delete()
                            out
                        } else null
                    } else null

                    val targetPrefix = if (overwriteInPlace) ApkProject.songPrefix(songId)
                    else {
                        ApkProject.songPrefix(newId!!)
                    }
                    // 写谱面
                    project.stageWriteBytes(targetPrefix + "$rc.aff", newAff.toByteArray(Charsets.UTF_8))
                    // 写音频（若有变速）
                    audioTmp?.let { project.stageWriteFile(targetPrefix + audioName!!, it) }

                    summaries.add("${Song.difficultyLabel(rc)}: ${(newAff.length / 1024)}KB" +
                        (if (audioTmp != null) " + 音频" else "（未变速音频）"))
                    changed = true
                }
                if (!changed) throw IOException("没有可处理的难度")

                // 非原地：需要新增歌曲（写 songlist），并沿用源歌曲的元数据（bpm/bg/set…）
                if (!overwriteInPlace && newId != null) {
                    publish(busy = true, stage = "正在建立新歌曲…", progress = 98)
                    val newSong = buildPracticeSong(song, newId, rcs, req.newTitle)
                    project.addSong(newSong)

                    // 把源歌曲的其它资源（封面 base.jpg / base_256.jpg、非本次重写的 .ogg 等）一并复制到新目录，
                    // 否则新曲在游戏里没有封面可显示。
                    val copiedExtra = copyExtraResources(project, songId, newId)
                    if (copiedExtra.isNotEmpty()) {
                        summaries.add("已复制 ${copiedExtra.size} 个其它资源")
                    }
                }
                publish(message = "已生成练习谱：${summaries.joinToString("；")}")
            }
        }
    }

    /**
     * 基于源歌曲构造练习新曲：深层复制源 song 的 json，只替换 id 与标题。
     * 这样 bpm / bpm_base / bg / set / side / artist / audioPreview 等字段都会自动沿用源歌曲。
     */
    private fun buildPracticeSong(source: Song, newId: String, rcs: List<Int>, newTitle: String?): Song {
        val cloned = JsonObject()
        for (key in source.json.keys) {
            cloned.put(key, source.json[key])
        }
        val newSong = Song(cloned)
        newSong.id = newId
        newSong.set = source.set
        // 显示名：用户指定则用指定的（不追加后缀）；否则沿用源曲名
        val finalTitle = newTitle?.takeIf { it.isNotBlank() } ?: (source.title("en") ?: source.id)
        newSong.setTitle("en", finalTitle)

        // 难度只保留本次生成的难度，复制源对应难度的字段
        val diffs = JsonArray()
        for (rc in rcs) {
            val srcD = source.difficulty(rc) ?: JsonObject()
            val d = JsonObject()
            d.put("ratingClass", JsonNumber.of(rc))
            d.put("chartDesigner", srcD.get("chartDesigner") ?: JsonString(""))
            d.put("jacketDesigner", srcD.get("jacketDesigner") ?: JsonString(""))
            d.put("rating", srcD.get("rating") ?: JsonNumber.of(0))
            diffs.add(d)
        }
        newSong.json.put("difficulties", diffs)
        return newSong
    }

    /**
     * 把源歌曲目录里的「其它」资源复制到新曲目录（练习谱新曲需要封面等文件）。
     * 跳过 .aff（谱面由切片生成）与音频（base.ogg/<rc>.ogg 由变速流程或整段复制处理，
     * 但这里仍复制未被本次重写的音频，如 base_256 之外的封面 jpg）。
     * @return 复制的文件名列表
     */
    private fun copyExtraResources(project: ApkProject, srcSongId: String, dstSongId: String): List<String> {
        val srcPrefix = ApkProject.songPrefix(srcSongId)
        val dstPrefix = ApkProject.songPrefix(dstSongId)
        val copied = ArrayList<String>()
        val audioExts = setOf("ogg", "opus", "mp3", "wav", "m4a", "aac", "flac")
        for (name in project.listSongFiles(srcSongId)) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0) name.substring(dot + 1).lowercase() else ""
            // 谱面与音频由前面流程处理，这里只复制封面等其它资源
            if (name.endsWith(".aff", ignoreCase = true)) continue
            if (audioExts.contains(ext)) continue
            val bytes = project.readEntry(srcPrefix + name) ?: continue
            project.stageWriteBytes(dstPrefix + name, bytes)
            copied.add(name)
        }
        return copied
    }

    private fun audioNameFor(project: ApkProject, songId: String, rc: Int): String? {
        val base = "${ApkProject.SONGS_ROOT}$songId/base.ogg"
        val override = "${ApkProject.SONGS_ROOT}$songId/$rc.ogg"
        return when {
            project.exists(override) -> "$rc.ogg"
            project.exists(base) -> "base.ogg"
            else -> null
        }
    }

    private fun isExportableResource(name: String): Boolean {
        if (name.endsWith(".aff", ignoreCase = true)) return true
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1).lowercase()
        return ext in setOf("ogg", "opus", "mp3", "wav", "m4a", "aac", "flac",
            "jpg", "jpeg", "png", "webp", "bmp")
    }

    /* ------------------------------ 歌曲 / 曲包 ------------------------------ */

    /**
     * 新增歌曲。可同时选择一个资源压缩包：会按 [dev.local.arcaea.apkmanager.core.ResourceZip] 的规则
     * 自动解压到 `assets/songs/<id>/`，并从包内的 songlist / slst 片段填充曲名、定数等字段
     * （id 与曲包始终以你填写的为准）。
     */
    fun createSong(song: Song, resourceZip: Uri? = null) {
        val project = _state.value.project ?: return
        runBusy("正在新增歌曲…") {
            if (resourceZip == null) {
                mutex.withLock {
                    project.addSong(song)
                    publish(message = "已新增歌曲 ${song.id}")
                }
                return@runBusy
            }
            val bundle = repo.extractResourceZip(resourceZip) { stage, percent ->
                publish(busy = true, stage = stage, progress = percent)
            }
            mutex.withLock {
                // 没有 songdata.json 时，用主难度谱面识别出的 BPM 填充 bpm / bpm_base
                if (bundle.metadata == null && bundle.bpm != null) {
                    song.bpm = bundle.bpm.range
                    song.bpmBase = bundle.bpm.base
                }
                project.addSongWithResources(song, bundle)
                val filled = if (bundle.metadata != null) "，并用包内信息填充了字段" else if (bundle.bpm != null) "，并根据主难度谱面识别了 BPM" else ""
                val warn = if (bundle.warnings.isEmpty()) "" else "\n\n" + bundle.warnings.joinToString("\n")
                publish(message = "已新增歌曲 ${song.id}，导入 ${bundle.files.size} 个资源文件$filled$warn")
            }
        }
    }

    /** 修改歌曲 id：整个资源目录会一起搬到新目录名下 */
    fun renameSong(oldId: String, newId: String) {
        val project = _state.value.project ?: return
        runBusy("正在重命名歌曲…") {
            mutex.withLock {
                project.renameSong(oldId, newId, repo.importDir)
                publish(message = "歌曲已重命名为 ${newId.trim()}")
            }
        }
    }

    /** 导出所用签名密钥的指纹（始终是同一把，便于覆盖安装更新） */
    fun signingKeyFingerprint(): String {
        fingerprintCache?.let { return it }
        val value = try {
            val key = Signer.loadKey(repo.keystoreBytes(), Signer.DEFAULT_STORE_PASSWORD)
            Signer.fingerprint(key.certificates.first())
        } catch (err: Throwable) {
            "（读取失败：${err.message}）"
        }
        fingerprintCache = value
        return value
    }

    fun addSong(song: Song) = runBusy("正在新增歌曲…") {
        mutex.withLock {
            _state.value.project?.addSong(song)
            publish(message = "已新增歌曲 ${song.id}")
        }
    }

    fun removeSong(id: String) = runBusy("正在删除歌曲…") {
        mutex.withLock {
            _state.value.project?.removeSong(id)
            publish(message = "已删除歌曲 $id")
        }
    }

    fun moveSong(id: String, delta: Int) = runBusy("") {
        mutex.withLock { _state.value.project?.moveSong(id, delta) }
    }

    fun addPack(pack: Pack) = runBusy("正在新增曲包…") {
        mutex.withLock {
            _state.value.project?.addPack(pack)
            publish(message = "已新增曲包 ${pack.id}")
        }
    }

    fun updatePack(id: String, block: (Pack) -> Unit) = runBusy("") {
        mutex.withLock {
            _state.value.project?.updatePack(id, block)
            publish()
        }
    }

    fun removePack(id: String, moveSongsTo: String? = null) = runBusy("正在删除曲包…") {
        mutex.withLock {
            _state.value.project?.removePack(id, moveSongsTo)
            publish(message = "已删除曲包 $id")
        }
    }

    /* --------------------------------- 自检 --------------------------------- */

    /** 端到端自检：用内置的小型 APK 跑完「打包 → 签名 → 校验」整条链路 */
    fun runSelfTest() {
        if (_state.value.busy) return
        viewModelScope.launch {
            publish(busy = true, stage = "正在运行自检…", progress = 0, error = null)
            val result = withContext(Dispatchers.IO) {
                SelfTest.run(
                    workDir = java.io.File(repo.workDir, "selftest"),
                    testApk = repo.selfTestApkBytes(),
                    keystore = repo.keystoreBytes(),
                ) { line -> android.util.Log.i(SelfTest.TAG, line) }
            }
            android.util.Log.i(SelfTest.TAG, result.summary)
            publish(
                busy = false,
                stage = "",
                progress = 100,
                selfTestSummary = result.summary,
                showSelfTest = true,
            )
        }
    }

    private fun runBusy(stage: String, block: suspend () -> Unit) {
        if (_state.value.project == null) return
        viewModelScope.launch {
            try {
                withContext(writeDispatcher) { block() }
            } catch (err: Throwable) {
                publish(error = err.message ?: err.toString())
            } finally {
                // 必须复位：进度回调会把 busy 置为 true，若不复位全屏遮罩会一直挡住界面
                publish(busy = false, stage = "", progress = 0)
            }
        }
    }

    override fun onCleared() {
        _state.value.project?.close()
        super.onCleared()
    }
}
