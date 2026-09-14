package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.Pack
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.data.UiState

/** 曲包页：列表 / 空曲包告警 / 编辑 / 新增 / 删除（迁移或连带删除歌曲）。 */
@Composable
fun PacksTab(state: UiState, vm: ApkViewModel, modifier: Modifier = Modifier) {
    val project = state.project
    if (project == null) {
        Column(modifier.fillMaxSize()) { EmptyHint("请先在「工程」页导入 APK") }
        return
    }
    val version = state.version
    val packs = remember(version) { project.packs }
    var editingId by remember { mutableStateOf<String?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "曲包（${packs.size}）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { showNew = true }, enabled = !state.busy) { Text("新增曲包") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(packs) { _, pack ->
                PackRow(
                    pack = pack,
                    songCount = project.songCountInPack(pack.id),
                    onEdit = { editingId = pack.id },
                    onRemove = { removingId = pack.id },
                )
            }
            if (packs.isEmpty()) {
                item { EmptyHint("当前 APK 的 packlist 中没有曲包") }
            }
        }
    }

    editingId?.let { id ->
        packs.firstOrNull { it.id == id }?.let { pack ->
            PackEditDialog(pack = pack, vm = vm, onDismiss = { editingId = null })
        }
    }

    removingId?.let { id ->
        packs.firstOrNull { it.id == id }?.let { pack ->
            PackRemoveDialog(
                project = project,
                pack = pack,
                allPacks = packs,
                vm = vm,
                onDismiss = { removingId = null },
            )
        }
    }

    if (showNew) {
        NewPackDialog(
            onDismiss = { showNew = false },
            onCreate = { id, section, name ->
                showNew = false
                vm.addPack(Pack.template(id, section, name))
            },
        )
    }
}

@Composable
private fun PackRow(
    pack: Pack,
    songCount: Int,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val emptyNormal = songCount == 0 && !pack.isExtendPack
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AppCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = pack.name() ?: pack.id,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pack.isExtendPack) Pill("扩展包", AppPrimary)
            if (emptyNormal) Pill("空曲包 · 会闪退", AppRed)
        }
        Text(
            text = "id：${pack.id}　section：${pack.section ?: "未设置"}　歌曲数：$songCount",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (emptyNormal) {
            Text(
                text = "删除该曲包，或给它添加歌曲，或勾选 is_extend_pack",
                style = MaterialTheme.typography.bodySmall,
                color = AppRed,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = onRemove) {
                Text("删除", color = AppRed)
            }
        }
    }
}

@Composable
private fun PackEditDialog(pack: Pack, vm: ApkViewModel, onDismiss: () -> Unit) {
    var section by remember(pack.id) { mutableStateOf(pack.section ?: Pack.SECTIONS.first()) }
    var name by remember(pack.id) { mutableStateOf(pack.name() ?: "") }
    var description by remember(pack.id) { mutableStateOf(pack.description() ?: "") }
    var plusCharacter by remember(pack.id) { mutableStateOf(pack.plusCharacter?.toString() ?: "") }
    var customBanner by remember(pack.id) { mutableStateOf(pack.customBanner) }
    var extend by remember(pack.id) { mutableStateOf(pack.isExtendPack) }
    var activeExtend by remember(pack.id) { mutableStateOf(pack.isActiveExtendPack) }

    AppDialog(
        title = "编辑曲包 ${pack.id}",
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextDim) }
            Button(onClick = {
                vm.updatePack(pack.id) { target ->
                    target.section = section
                    target.setName("en", name)
                    target.setDescription("en", description)
                    target.plusCharacter = plusCharacter.trim().toIntOrNull()
                    target.customBanner = customBanner
                    target.isExtendPack = extend
                    target.isActiveExtendPack = activeExtend
                }
                onDismiss()
            }) { Text("保存") }
        },
    ) {
        InfoCell("曲包 id（只读）", pack.id, mono = true)
        Selector(
            value = section,
            options = (Pack.SECTIONS + section).distinct(),
            onSelect = { section = it },
            label = "分组 section",
        )
        AppTextField(name, { name = it }, label = "名称（name_localized.en）")
        AppTextField(
            description,
            { description = it },
            label = "描述（description_localized.en）",
            singleLine = false,
        )
        AppTextField(
            plusCharacter,
            { plusCharacter = it },
            label = "plus_character（数字，-1 表示无）",
        )
        TriStateField("custom_banner", customBanner, { customBanner = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = extend, onCheckedChange = { extend = it })
            Text(
                text = "is_extend_pack（扩展包，允许没有任何歌曲）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TriStateField("is_active_extend_pack", activeExtend, { activeExtend = it })
        Text(
            text = "曲包 id 与横幅文件名绑定（select_<id>.png），这里不提供修改；" +
                "缺少横幅会由「检查」页提示。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
    }
}

@Composable
private fun PackRemoveDialog(
    project: ApkProject,
    pack: Pack,
    allPacks: List<Pack>,
    vm: ApkViewModel,
    onDismiss: () -> Unit,
) {
    val songCount = project.songCountInPack(pack.id)
    val others = allPacks.filter { it.id != pack.id }.map { it.id }
    var moveSongs by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(others.firstOrNull() ?: "") }

    AppDialog(
        title = "删除曲包 ${pack.id}",
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextDim) }
            Button(
                onClick = {
                    vm.removePack(pack.id, if (moveSongs) target.ifBlank { null } else null)
                    onDismiss()
                },
                enabled = !moveSongs || target.isNotBlank(),
            ) { Text("确认删除") }
        },
    ) {
        Text(
            text = "该曲包下有 $songCount 首歌曲，请选择处理方式：",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextDim,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !moveSongs, onClick = { moveSongs = false })
            Text("连同包内 $songCount 首歌一起删除", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = moveSongs, onClick = { moveSongs = true })
            Text("把歌曲移动到其它曲包", style = MaterialTheme.typography.bodySmall)
        }
        if (moveSongs) {
            Selector(
                value = target,
                options = others,
                onSelect = { target = it },
                label = "目标曲包",
                placeholder = "（没有其它曲包）",
                enabled = others.isNotEmpty(),
            )
        } else if (songCount > 0) {
            Text(
                text = "删除曲包会连带删除这些歌曲及其资源目录（导出后不可撤销）。",
                style = MaterialTheme.typography.labelSmall,
                color = AppRed,
            )
        }
        if (others.isEmpty() && moveSongs) {
            Text(
                text = "当前没有其它曲包可选，请先新增一个曲包。",
                style = MaterialTheme.typography.labelSmall,
                color = AppYellow,
            )
        }
    }
}

@Composable
private fun NewPackDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("sidestory") }
    val trimmedId = id.trim()
    val validId = trimmedId.isNotEmpty() && !trimmedId.contains(' ') && !trimmedId.contains('/')

    AppDialog(
        title = "新增曲包",
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextDim) }
            Button(onClick = { onCreate(trimmedId, section, name.trim()) }, enabled = validId) {
                Text("创建")
            }
        },
    ) {
        AppTextField(
            value = id,
            onValueChange = { id = it },
            label = "曲包 id（建议只用字母 / 数字 / 下划线）",
            placeholder = "mypack",
        )
        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "名称（en）",
            placeholder = "My Pack",
        )
        Selector(
            value = section,
            options = Pack.SECTIONS,
            onSelect = { section = it },
            label = "分组 section",
        )
        Text(
            text = "新建的曲包必须先添加至少一首歌，或勾选 is_extend_pack，否则游戏打开歌单时会闪退；" +
                "横幅图片需放入 assets/songs/pack/select_<id>.png。",
            style = MaterialTheme.typography.labelSmall,
            color = AppYellow,
        )
    }
}
