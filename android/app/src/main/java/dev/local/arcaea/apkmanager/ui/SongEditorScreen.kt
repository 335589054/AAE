package dev.local.arcaea.apkmanager.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.JsonBoolean
import dev.local.arcaea.apkmanager.core.JsonNumber
import dev.local.arcaea.apkmanager.core.JsonObject
import dev.local.arcaea.apkmanager.core.JsonString
import dev.local.arcaea.apkmanager.core.Pack
import dev.local.arcaea.apkmanager.core.Song
import dev.local.arcaea.apkmanager.core.localized
import dev.local.arcaea.apkmanager.core.putLocalized
import dev.local.arcaea.apkmanager.data.ApkViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌曲编辑页（在「歌曲」页内整屏切换）。
 *
 * 字段编辑先落在本地状态里，点「保存修改」时再写回 songlist 的 JsonObject，
 * 随后调用 vm.refresh() 让 state.version 变化以刷新整棵界面。
 */

/* -------------------------------- 编辑态数据 -------------------------------- */

private data class DiffEdit(
    val rc: Int,
    val exists: Boolean,
    val rating: String,
    val ratingPlus: Boolean,
    val chartDesigner: String,
    val jacketDesigner: String,
    val audioOverride: Boolean,
    val title: String,
)

private data class SongEdit(
    val title: String,
    val artist: String,
    val bpm: String,
    val bpmBase: String,
    val set: String,
    val side: String,
    val bg: String,
    val date: String,
    val version: String,
    val purchase: String,
    val audioPreview: String,
    val audioPreviewEnd: String,
    val diffs: List<DiffEdit>,
)

private fun numberText(value: Double?): String {
    if (value == null) return ""
    return if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}

private fun readDiff(rc: Int, difficulty: JsonObject): DiffEdit = DiffEdit(
    rc = rc,
    exists = true,
    rating = (difficulty["rating"] as? JsonNumber)?.toInt()?.toString() ?: "",
    ratingPlus = difficulty.optBoolean("ratingPlus") == true,
    chartDesigner = difficulty.optString("chartDesigner") ?: "",
    jacketDesigner = difficulty.optString("jacketDesigner") ?: "",
    audioOverride = Song.isAudioOverride(difficulty),
    title = difficulty.localized("title_localized", "en") ?: "",
)

private fun readSongEdit(song: Song): SongEdit = SongEdit(
    title = song.title() ?: "",
    artist = song.artist ?: "",
    bpm = song.bpm ?: "",
    bpmBase = numberText(song.bpmBase),
    set = song.set,
    side = song.side?.toString() ?: "",
    bg = song.bg ?: "",
    date = song.date?.toString() ?: "",
    version = song.version ?: "",
    purchase = song.purchase ?: "",
    audioPreview = song.audioPreview?.toString() ?: "",
    audioPreviewEnd = song.audioPreviewEnd?.toString() ?: "",
    diffs = (0..4).map { rc ->
        val difficulty = song.difficulty(rc)
        if (difficulty == null) {
            DiffEdit(rc, false, "", false, "", "", false, "")
        } else {
            readDiff(rc, difficulty)
        }
    },
)

private fun JsonObject.writeBoolean(key: String, value: Boolean) {
    // 值本来不存在时保持「不存在」，避免给 songlist 添加无意义的新字段
    if (value || has(key)) put(key, JsonBoolean(value))
}

private fun applySongEdit(song: Song, edit: SongEdit) {
    song.setTitle("en", edit.title)
    song.artist = edit.artist
    song.bpm = edit.bpm
    song.bpmBase = edit.bpmBase.trim().toDoubleOrNull()
    song.set = edit.set
    song.side = edit.side.trim().toIntOrNull()
    song.bg = edit.bg.ifBlank { null }
    song.date = edit.date.trim().toLongOrNull()
    song.version = edit.version.ifBlank { null }
    song.purchase = edit.purchase
    song.audioPreview = edit.audioPreview.trim().toLongOrNull()
    song.audioPreviewEnd = edit.audioPreviewEnd.trim().toLongOrNull()

    for (diff in edit.diffs) {
        if (!diff.exists) continue
        val target = song.ensureDifficulty(diff.rc)
        target.put("rating", JsonNumber.of(diff.rating.trim().toIntOrNull() ?: 0))
        target.put("chartDesigner", JsonString(diff.chartDesigner))
        target.put("jacketDesigner", JsonString(diff.jacketDesigner))
        target.writeBoolean("ratingPlus", diff.ratingPlus)
        target.writeBoolean("audioOverride", diff.audioOverride)
        if (diff.title.isNotBlank()) target.putLocalized("title_localized", "en", diff.title)
    }
}

/* --------------------------------- 界面 --------------------------------- */

@Composable
fun SongEditorScreen(
    project: ApkProject,
    song: Song,
    packs: List<Pack>,
    version: Int,
    busy: Boolean,
    vm: ApkViewModel,
    onClose: () -> Unit,
    onRenamed: (String) -> Unit,
) {
    var saved by remember(song.id) { mutableStateOf(readSongEdit(song)) }
    var edit by remember(song.id) { mutableStateOf(saved) }
    val dirty = edit != saved
    var confirmExit by remember { mutableStateOf(false) }
    var newId by remember(song.id) { mutableStateOf(song.id) }
    var confirmRename by remember { mutableStateOf(false) }
    val trimmedNewId = newId.trim()
    val canRename = trimmedNewId.isNotEmpty() && trimmedNewId != song.id && !busy

    fun requestClose() {
        if (dirty) confirmExit = true else onClose()
    }

    fun patchDiff(rc: Int, block: (DiffEdit) -> DiffEdit) {
        edit = edit.copy(diffs = edit.diffs.map { if (it.rc == rc) block(it) else it })
    }

    BackHandler { requestClose() }

    val prefix = ApkProject.songPrefix(song.id)
    val files = remember(song.id, version) { project.listSongFiles(song.id) }
    var sizes by remember(song.id, version) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(song.id, version) {
        sizes = withContext(Dispatchers.IO) {
            files.associateWith { name ->
                val path = prefix + name
                project.reader.entries[path]?.uncompressedSize
                    ?: (project.readEntry(path)?.size?.toLong() ?: 0L)
            }
        }
    }

    var importName by remember(song.id) { mutableStateOf("2.aff") }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = importName.trim()
        if (uri != null && name.isNotEmpty() && !name.contains('/')) {
            ThumbCache.clear()
            vm.importAsset(uri, prefix + name)
        }
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ThumbCache.clear()
            vm.importResourceZip(uri, song.id)
        }
    }
    var exportingPath by remember { mutableStateOf<String?>(null) }
    val exportAsset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = exportingPath
        exportingPath = null
        if (uri != null && path != null) vm.exportAssetTo(uri, path)
    }
    var deletePath by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { requestClose() }) { Text("返回列表") }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title() ?: song.id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = prefix,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dirty) Pill("有未保存的修改", AppYellow)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "基本信息") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(
                            value = newId,
                            onValueChange = { newId = it },
                            label = "歌曲 id（目录 assets/songs/<id>/）",
                            enabled = !busy,
                        )
                    }
                    Button(onClick = { confirmRename = true }, enabled = canRename) { Text("重命名") }
                }
                if (trimmedNewId.isEmpty()) {
                    Text(
                        text = "歌曲 id 不能为空。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppRed,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.title, { edit = edit.copy(title = it) }, label = "标题（en）")
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.artist, { edit = edit.copy(artist = it) }, label = "曲师 artist")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.bpm, { edit = edit.copy(bpm = it) }, label = "bpm（可为文本）")
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.bpmBase, { edit = edit.copy(bpmBase = it) }, label = "bpm_base（数字）")
                    }
                }
                Selector(
                    value = edit.set,
                    options = (packs.map { it.id } + edit.set).distinct().filter { it.isNotBlank() },
                    onSelect = { edit = edit.copy(set = it) },
                    label = "所属曲包 set",
                    placeholder = "（未设置）",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.side, { edit = edit.copy(side = it) }, label = "side（数字）")
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.bg, { edit = edit.copy(bg = it) }, label = "bg（背景名，如 base）")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.date, { edit = edit.copy(date = it) }, label = "date（Unix 秒）")
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField(edit.version, { edit = edit.copy(version = it) }, label = "version")
                    }
                }
                AppTextField(edit.purchase, { edit = edit.copy(purchase = it) }, label = "purchase")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(
                            edit.audioPreview,
                            { edit = edit.copy(audioPreview = it) },
                            label = "audioPreview（毫秒）",
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        AppTextField(
                            edit.audioPreviewEnd,
                            { edit = edit.copy(audioPreviewEnd = it) },
                            label = "audioPreviewEnd（毫秒）",
                        )
                    }
                }
                Text(
                    text = "数字字段留空会写入 null；修改只影响导出的 songlist，不会动其它资源。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }

            SectionCard(title = "难度（PST / PRS / FTR / BYD / ETR）") {
                Text(
                    text = "定数大于 0 才会被游戏视为可游玩难度；此时必须有对应的 <难度>.aff 谱面文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
                for (rc in 0..4) {
                    val row = edit.diffs.firstOrNull { it.rc == rc } ?: continue
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(
                                text = Song.difficultyLabel(rc),
                                color = if (row.exists) AppPrimary else AppTextDim,
                            )
                            Text(
                                text = "ratingClass $rc",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextDim,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = {
                                    song.ensureDifficulty(rc)
                                    val next = edit.diffs.map {
                                        if (it.rc == rc) it.copy(exists = true) else it
                                    }
                                    edit = edit.copy(diffs = next)
                                    saved = saved.copy(diffs = next)
                                    vm.refresh()
                                },
                            ) {
                                Text("添加该难度")
                            }
                        }
                        if (row.exists) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        row.rating,
                                        { value -> patchDiff(rc) { it.copy(rating = value) } },
                                        label = "定数 rating",
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        row.chartDesigner,
                                        { value -> patchDiff(rc) { it.copy(chartDesigner = value) } },
                                        label = "谱师 chartDesigner",
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        row.jacketDesigner,
                                        { value -> patchDiff(rc) { it.copy(jacketDesigner = value) } },
                                        label = "画师 jacketDesigner",
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        row.title,
                                        { value -> patchDiff(rc) { it.copy(title = value) } },
                                        label = "该难度标题（en，可留空）",
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(
                                    checked = row.ratingPlus,
                                    onCheckedChange = { value -> patchDiff(rc) { it.copy(ratingPlus = value) } },
                                )
                                Text("ratingPlus", style = MaterialTheme.typography.labelSmall)
                                Checkbox(
                                    checked = row.audioOverride,
                                    onCheckedChange = { value -> patchDiff(rc) { it.copy(audioOverride = value) } },
                                )
                                Text(
                                    text = "audioOverride（该难度使用 $rc.ogg）",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(title = "资源文件（${files.size}）") {
                if (files.isEmpty()) {
                    Text(
                        text = "该歌曲目录下没有任何资源文件，导出后曲目不可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppYellow,
                    )
                }
                files.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = sizes[name]?.let { formatBytes(it) } ?: "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextDim,
                        )
                        TextButton(onClick = {
                            exportingPath = prefix + name
                            exportAsset.launch(name)
                        }) { Text("导出", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { deletePath = prefix + name }) {
                            Text("删除", style = MaterialTheme.typography.labelSmall, color = AppRed)
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(
                            value = importName,
                            onValueChange = { importName = it },
                            label = "目标文件名（默认 2.aff）",
                            placeholder = "2.aff / base.ogg / base.jpg",
                        )
                    }
                    Button(
                        onClick = { importFile.launch(arrayOf("*/*")) },
                        enabled = !busy && importName.trim().isNotEmpty() && !importName.contains('/'),
                    ) { Text("导入单个文件") }
                }
                OutlinedButton(
                    onClick = { importZip.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入资源压缩包（自动剥离目录前缀）")
                }
                Text(
                    text = "谱面用 <难度>.aff（如 2.aff），音频用 base.ogg / <难度>.ogg，封面用 base.jpg 与 base_256.jpg。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Surface(color = AppBackground) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dirty) {
                    Pill("有未保存的修改", AppYellow)
                } else {
                    Text("已同步", style = MaterialTheme.typography.labelSmall, color = AppTextDim)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { requestClose() }) { Text("关闭") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        applySongEdit(song, edit)
                        saved = edit
                        vm.refresh()
                    },
                    enabled = dirty,
                ) { Text("保存修改") }
            }
        }
    }

    deletePath?.let { path ->
        ConfirmDialog(
            title = "删除资源文件",
            message = "将标记删除 $path（导出后生效，撤销需要重新导入）。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                deletePath = null
                ThumbCache.clear()
                vm.deleteAsset(path)
            },
            onDismiss = { deletePath = null },
        )
    }

    if (confirmRename) {
        val oldId = song.id
        val targetId = trimmedNewId
        ConfirmDialog(
            title = "修改歌曲 id",
            message = "将把资源目录 assets/songs/$oldId/ 整个搬到 assets/songs/$targetId/，并同步更新 songlist 中的 id。",
            confirmText = "确认修改",
            onConfirm = {
                confirmRename = false
                if (dirty) {
                    applySongEdit(song, edit)
                    saved = edit
                }
                vm.renameSong(oldId, targetId)
                onRenamed(targetId)
            },
            onDismiss = { confirmRename = false },
        )
    }

    if (confirmExit) {
        ConfirmDialog(
            title = "放弃未保存的修改？",
            message = "当前歌曲还有未保存的字段修改，返回列表会丢失这些改动。",
            confirmText = "放弃修改",
            danger = true,
            onConfirm = {
                confirmExit = false
                onClose()
            },
            onDismiss = { confirmExit = false },
        )
    }
}
