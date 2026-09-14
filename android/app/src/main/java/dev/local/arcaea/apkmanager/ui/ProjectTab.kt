package dev.local.arcaea.apkmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.data.UiState

/** 工程页：导入 / 工程信息 / 未导出改动 / 清理工作文件。 */
@Composable
fun ProjectTab(state: UiState, vm: ApkViewModel, modifier: Modifier = Modifier) {
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importApk(uri)
    }
    var confirmClose by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }
    val project = state.project

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (project == null) {
            SectionCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("未导入安装包", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "请选择你要修改的 Arcaea 安装包（.apk）",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextDim,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "导入后不会自动联网，全部在本机处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextDim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { pickApk.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    ) {
                        Text("导入 APK")
                    }
                }
            }
        } else {
            val manifest = project.manifest
            val override = project.packageNameOverride
            val packageText = if (override.isNullOrBlank()) {
                manifest.packageName
            } else {
                "$override（原 ${manifest.packageName}）"
            }

            SectionCard(
                title = "当前工程",
                actions = {
                    Text(
                        text = project.apkFile.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextDim,
                    )
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCell("包名", packageText, Modifier.weight(1f), mono = true)
                    InfoCell(
                        "版本名",
                        manifest.versionName ?: "未声明",
                        Modifier.weight(1f),
                        mono = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCell(
                        "versionCode",
                        manifest.versionCode?.toString() ?: "未声明",
                        Modifier.weight(1f),
                    )
                    InfoCell(
                        "minSdk / targetSdk",
                        "${manifest.minSdk ?: "?"} / ${manifest.targetSdk ?: "?"}",
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCell("歌曲数", "${project.songs.size}", Modifier.weight(1f))
                    InfoCell("曲包数", "${project.packs.size}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCell(
                        "当前安装包大小",
                        formatBytes(project.apkFile.length()),
                        Modifier.weight(1f),
                    )
                    InfoCell("工作目录可用空间", formatBytes(state.freeSpace), Modifier.weight(1f))
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickApk.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    ) {
                        Text("更换安装包")
                    }
                    OutlinedButton(
                        onClick = { confirmClose = true },
                        enabled = !state.busy,
                    ) {
                        Text("清除工作文件")
                    }
                }
                Text(
                    text = "清除工作文件会删除导入的副本与打包中间产物（会关闭当前工程），" +
                        "尚未导出的修改会一并丢失。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }

            val pending = state.snapshot?.pending ?: emptyList()
            SectionCard(title = "尚未导出的改动（${pending.size}）") {
                if (pending.isEmpty()) {
                    Text(
                        text = "没有未导出的改动",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextDim,
                    )
                } else {
                    pending.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = "点击右上角「导出」即可把这些改动写入新的安装包。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextDim,
                    )
                }
            }

            val usage = state.cache
            SectionCard(title = "缓存与清理") {
                CleanupInfoRow("导入的源 APK", usage.sourceApkBytes)
                CleanupInfoRow("缓存的导出 APK", usage.cachedApkBytes)
                CleanupInfoRow("项目缓存数据", usage.projectDataBytes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 ${formatBytes(usage.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "可用空间 ${formatBytes(state.freeSpace)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextDim,
                    )
                }
                Text(
                    text = "删除缓存不会影响已导出的 APK；有未导出的改动时不会清理项目缓存数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = { showCache = true },
                    enabled = !state.busy,
                ) {
                    Text("清除缓存")
                }
            }
        }
    }

    if (confirmClose) {
        ConfirmDialog(
            title = "清除工作文件",
            message = "将关闭当前工程并删除工作目录中的临时文件（导入的安装包副本 / 打包中间产物）。" +
                "尚未导出的修改会全部丢失，确定继续吗？",
            confirmText = "确定清除",
            danger = true,
            onConfirm = {
                confirmClose = false
                vm.closeProject()
            },
            onDismiss = { confirmClose = false },
        )
    }

    if (showCache) {
        CacheCleanupDialog(
            title = "清除缓存",
            subtitle = null,
            usage = state.cache,
            hasPendingChanges = state.snapshot?.pending?.isNotEmpty() == true,
            onDismiss = { showCache = false },
            onConfirm = { s, c, p ->
                showCache = false
                vm.clearCache(s, c, p)
            },
        )
    }
}

/** 「缓存与清理」卡片里的一行明细：名称 + 占用大小（为 0 时显示「无」）。 */
@Composable
private fun CleanupInfoRow(label: String, bytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (bytes <= 0L) "无" else formatBytes(bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
