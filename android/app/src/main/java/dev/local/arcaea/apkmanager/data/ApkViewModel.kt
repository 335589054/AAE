package dev.local.arcaea.apkmanager.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.BuildOptions
import dev.local.arcaea.apkmanager.core.SongBpmInfo
import dev.local.arcaea.apkmanager.core.SongZip
import dev.local.arcaea.apkmanager.core.JsonObject
import dev.local.arcaea.apkmanager.core.Pack
import dev.local.arcaea.apkmanager.core.ProjectSnapshot
import dev.local.arcaea.apkmanager.core.SelfTest
import dev.local.arcaea.apkmanager.core.Signer
import dev.local.arcaea.apkmanager.core.Song
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
                // 不属于歌曲目录的资源（如背景图）按完整 APK 相对路径写入
                for ((path, file) in bundle.extras) {
                    project.stageWriteFile(path, file)
                }
                val warn = if (bundle.warnings.isEmpty()) "" else "\n\n" + bundle.warnings.joinToString("\n")
                publish(message = "已从压缩包导入 ${bundle.files.size + bundle.extras.size} 个文件到 $songId$warn")
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
