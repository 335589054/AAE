package dev.local.arcaea.apkmanager.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.JsonNumber
import dev.local.arcaea.apkmanager.core.Pack
import dev.local.arcaea.apkmanager.core.Song
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.data.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 歌曲页：列表（缩略图 / 难度徽章 / 搜索）+ 行操作菜单 + 新增 + 进入编辑页。 */
@Composable
fun SongsTab(state: UiState, vm: ApkViewModel, modifier: Modifier = Modifier) {
    val project = state.project
    if (project == null) {
        Column(modifier.fillMaxSize()) { EmptyHint("请先在「工程」页导入 APK") }
        return
    }
    val version = state.version
    val songs = remember(version) { project.songs }
    val packs = remember(version) { project.packs }

    var query by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    var deleteFor by remember { mutableStateOf<Song?>(null) }
    var showNew by remember { mutableStateOf(false) }

    val editing = editingId?.let { id -> songs.firstOrNull { it.id == id } }
    if (editing != null) {
        SongEditorScreen(
            project = project,
            song = editing,
            packs = packs,
            version = version,
            busy = state.busy,
            vm = vm,
            onClose = { editingId = null },
            onRenamed = { editingId = it },
        )
        return
    }

    val filtered = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            songs
        } else {
            songs.filter { song ->
                song.id.lowercase().contains(q) ||
                    (song.title() ?: "").lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索",
                    placeholder = "按 id / 标题过滤",
                )
            }
            Button(onClick = { showNew = true }, enabled = !state.busy) { Text("新增歌曲") }
        }
        Text(
            text = "共 ${songs.size} 首，当前显示 ${filtered.size} 首",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(filtered) { _, song ->
                SongRow(project, vm, song, version) { menuFor = song }
            }
            if (filtered.isEmpty()) {
                item { EmptyHint("没有匹配的歌曲") }
            }
        }
    }

    menuFor?.let { song ->
        AppDialog(
            title = song.title() ?: song.id,
            onDismiss = { menuFor = null },
            footer = {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { menuFor = null }) { Text("关闭", color = AppTextDim) }
            },
        ) {
            Text(
                text = "id：${song.id}　曲包：${song.set.ifBlank { "未设置" }}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
            MenuAction("编辑") {
                menuFor = null
                editingId = song.id
            }
            MenuAction("上移") {
                menuFor = null
                vm.moveSong(song.id, -1)
            }
            MenuAction("下移") {
                menuFor = null
                vm.moveSong(song.id, 1)
            }
            MenuAction("删除", danger = true) {
                menuFor = null
                deleteFor = song
            }
        }
    }

    deleteFor?.let { song ->
        val fileCount = project.listSongFiles(song.id).size
        ConfirmDialog(
            title = "删除歌曲",
            message = "将删除 ${song.id} 及其目录下的 $fileCount 个文件（导出后不可撤销）。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                deleteFor = null
                if (editingId == song.id) editingId = null
                vm.removeSong(song.id)
            },
            onDismiss = { deleteFor = null },
        )
    }

    if (showNew) {
        NewSongDialog(
            packIds = packs.map { it.id },
            busy = state.busy,
            onDismiss = { showNew = false },
            onCreate = { id, setId, title, zip ->
                showNew = false
                editingId = id
                vm.createSong(Song.template(id, setId, title), zip)
            },
        )
    }
}

@Composable
private fun MenuAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) AppRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NewSongDialog(
    packIds: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (id: String, setId: String, title: String, zip: Uri?) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var setId by remember { mutableStateOf(packIds.firstOrNull() ?: "") }
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var zipName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val trimmedId = id.trim()
    val validId = trimmedId.isNotEmpty() && !trimmedId.contains('/') && !trimmedId.contains(' ')

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            zipUri = uri
            zipName = displayName(context, uri)
        }
    }

    AppDialog(
        title = "新增歌曲",
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextDim) }
            Button(
                onClick = { onCreate(trimmedId, setId, title.trim(), zipUri) },
                enabled = validId && setId.isNotBlank() && !busy,
            ) {
                Text("创建")
            }
        },
    ) {
        AppTextField(
            value = id,
            onValueChange = { id = it },
            label = "歌曲 id（需与目录 assets/songs/<id>/ 一致）",
            placeholder = "mysong01",
        )
        AppTextField(
            value = title,
            onValueChange = { title = it },
            label = "标题（title_localized.en）",
            placeholder = "My New Song",
        )
        Selector(
            value = setId,
            options = packIds,
            onSelect = { setId = it },
            label = "所属曲包 set",
            placeholder = "（没有可用曲包）",
        )
        if (packIds.isEmpty()) {
            Text(
                text = "当前没有曲包，请先到「曲包」页新增。",
                style = MaterialTheme.typography.labelSmall,
                color = AppYellow,
            )
        }
        if (id.isNotEmpty() && !validId) {
            Text(
                text = "歌曲 id 不能为空，且不能包含 / 与空格。",
                style = MaterialTheme.typography.labelSmall,
                color = AppRed,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (zipUri == null) "选择 zip（可选）" else "重新选择 zip")
            }
            if (zipUri != null) {
                TextButton(
                    onClick = {
                        zipUri = null
                        zipName = null
                    },
                    enabled = !busy,
                ) {
                    Text("清除", color = AppRed)
                }
            }
        }
        if (zipUri != null) {
            Text(
                text = "已选择：${zipName ?: "（未知文件）"}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "选择后会把包内文件自动解压到 assets/songs/<id>/（支持根目录 / <歌曲目录>/… / assets/songs/<id>/… 三种结构），并尝试用包内的 songlist / slst 片段填充字段；id 与曲包始终以这里填写的为准。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        Text(
            text = "若压缩包内也带有曲名等信息，会用包内信息覆盖上面填写的标题（以包内为准）。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        Text(
            text = "创建后请在该歌曲的资源文件里导入封面（base.jpg / base_256.jpg）与谱面（<难度>.aff），否则导出后曲目不可用。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
    }
}

/** 读取 content Uri 的显示文件名（查询失败时退回路径末段）。 */
private fun displayName(context: Context, uri: Uri): String? {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    } catch (_: Throwable) {
        null
    }
    return queried ?: uri.lastPathSegment
}

/* --------------------------------- 列表行 --------------------------------- */

@Composable
private fun SongRow(
    project: ApkProject,
    vm: ApkViewModel,
    song: Song,
    version: Int,
    onClick: () -> Unit,
) {
    val prefix = ApkProject.songPrefix(song.id)
    val files = remember(song.id, version) { project.listSongFiles(song.id) }
    val thumbPath = remember(song.id, version) { pickThumbPath(prefix, files) }
    val image = rememberThumbnail(vm, thumbPath, version)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AppCard)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ThumbBox(image, 64.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = song.title() ?: song.id,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = song.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.set.isNotBlank()) Pill(song.set, AppTextDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                song.difficultyList().forEach { difficulty ->
                    val rc = (difficulty["ratingClass"] as? JsonNumber)?.toInt() ?: return@forEach
                    val rating = Song.difficultyRating(difficulty)
                    Pill(
                        text = "${Song.difficultyLabel(rc)} $rating",
                        color = if (rating > 0) AppPrimary else AppTextDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbBox(image: ImageBitmap?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(AppCardAlt)
            .border(1.dp, AppOutline, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("无图", style = MaterialTheme.typography.labelSmall, color = AppTextDim)
        }
    }
}

/* ------------------------------- 缩略图缓存 ------------------------------- */

/** 缩略图候选文件名，按优先级排列。 */
internal val THUMB_CANDIDATES = listOf(
    "base_256.jpg",
    "1080_base_256.jpg",
    "base.jpg",
    "1080_base.jpg",
)

/** 从「该歌曲目录下实际存在的文件」里挑一张缩略图（用 listSongFiles 判断，避免真的去读大文件）。 */
internal fun pickThumbPath(prefix: String, files: List<String>): String? =
    THUMB_CANDIDATES.firstOrNull { files.contains(it) }?.let { prefix + it }

/** 极简内存缓存（上限 60 项），键为 zip 内的相对路径。 */
internal object ThumbCache {
    private const val LIMIT = 60
    private val entries = LinkedHashMap<String, ImageBitmap?>()

    fun contains(path: String): Boolean = entries.containsKey(path)

    fun get(path: String): ImageBitmap? = entries[path]

    fun put(path: String, image: ImageBitmap?) {
        while (entries.size >= LIMIT && entries.isNotEmpty()) {
            entries.remove(entries.keys.first())
        }
        entries[path] = image
    }

    fun clear() = entries.clear()
}

/** 读取并解码缩略图（带缓存）。 */
@Composable
internal fun rememberThumbnail(vm: ApkViewModel, relPath: String?, version: Int): ImageBitmap? {
    var image by remember(relPath) {
        mutableStateOf(if (relPath != null && ThumbCache.contains(relPath)) ThumbCache.get(relPath) else null)
    }
    LaunchedEffect(relPath, version) {
        val path = relPath ?: return@LaunchedEffect
        if (ThumbCache.contains(path)) {
            image = ThumbCache.get(path)
            return@LaunchedEffect
        }
        val bytes = vm.loadAssetBytes(path)
        val decoded = if (bytes == null) {
            null
        } else {
            withContext(Dispatchers.Default) { decodeSampledBitmap(bytes, 160) }
        }
        ThumbCache.put(path, decoded)
        image = decoded
    }
    return image
}

/** 按最大边缩小解码，避免把整张 1080 封面都读进内存。 */
internal fun decodeSampledBitmap(bytes: ByteArray, maxSize: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (err: Throwable) {
        null
    }
    return bitmap?.asImageBitmap()
}
