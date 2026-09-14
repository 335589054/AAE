package dev.local.arcaea.apkmanager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.data.UiState

private const val APK_MIME = "application/vnd.android.package-archive"

/** 导出对话框：改动条数 / 改包名 / 问题确认 / 进度 / 结果。 */
@Composable
fun ExportDialog(state: UiState, vm: ApkViewModel, onDismiss: () -> Unit) {
    val project = state.project ?: return
    val originalPackage = project.manifest.packageName
    val busy = state.busy
    val fingerprint = remember { vm.signingKeyFingerprint() }

    var rename by remember { mutableStateOf(!project.packageNameOverride.isNullOrBlank()) }
    var packageName by remember {
        mutableStateOf(project.packageNameOverride ?: defaultNewPackage(originalPackage))
    }
    var rewrite by remember { mutableStateOf(project.rewriteIdentifiers) }
    var acknowledged by remember { mutableStateOf(false) }

    val warnings = state.snapshot?.warnings ?: emptyList()
    val pending = state.snapshot?.pending ?: emptyList()
    val trimmed = packageName.trim()
    val validPackage = ApkProject.isValidPackageName(trimmed)
    val canExport = !busy && (!rename || validPackage) && (warnings.isEmpty() || acknowledged)

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME),
    ) { uri ->
        if (uri != null) {
            val pkg = if (rename) trimmed else null
            // 说明：工程实际导出时读取的是 ApkProject.packageNameOverride（BuildOptions 未被仓库层使用），
            // 所以这里先把包名写回工程，再交给 ViewModel 导出，保证「修改包名」真正生效。
            project.setPackageNameOverride(pkg, rewrite)
            vm.exportTo(uri, pkg, rewrite)
        }
    }

    AppDialog(
        title = "导出安装包",
        onDismiss = onDismiss,
        dismissible = !busy,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭", color = AppTextDim) }
            Button(
                onClick = { createDocument.launch(suggestedFileName(rename, trimmed, originalPackage)) },
                enabled = canExport,
            ) {
                Text(if (state.lastExport == null) "选择保存位置并导出" else "再次导出")
            }
        },
    ) {
        Text(
            text = "将要写入的改动：${pending.size} 条",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pending.isEmpty()) {
            Text(
                text = "没有未导出的改动，导出的安装包与原包基本一致。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        } else {
            pending.take(20).forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }
            if (pending.size > 20) {
                Text(
                    text = "… 还有 ${pending.size - 20} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = rename, onCheckedChange = { rename = it }, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            Text("修改包名", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "原包名：$originalPackage",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        if (rename) {
            AppTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = "新的包名",
                placeholder = "com.example.arcaea.mod",
                enabled = !busy,
            )
            if (!validPackage) {
                Text(
                    text = "包名不合法：至少两段、每段以字母开头，只能包含字母、数字与下划线。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppRed,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rewrite, onCheckedChange = { rewrite = it }, enabled = !busy)
                Text(
                    text = "同时改写自定义权限名与 Provider 授权名",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = "改包名后是一个全新应用，不会继承原版存档与登录状态；与原版共存时必须开启上面的选项。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        }

        if (warnings.isNotEmpty()) {
            NoticeCard(
                color = AppRed,
                title = "存在 ${warnings.size} 个会导致游戏异常的问题：",
                lines = warnings.take(6) + if (warnings.size > 6) listOf("… 还有 ${warnings.size - 6} 条，请在「检查」页查看") else emptyList(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                    enabled = !busy,
                )
                Text(
                    text = "我已了解上述问题，仍要导出",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppRed,
                )
            }
        }

        if (busy) {
            Text(
                text = state.stage.ifBlank { "正在处理…" },
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${state.progress}%　请不要切换应用",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        }

        state.lastExport?.let { size ->
            NoticeCard(
                color = AppGreen,
                title = "导出成功（$size）",
                lines = emptyList(),
            )
            if (state.lastExportWarnings.isNotEmpty()) {
                NoticeCard(
                    color = AppYellow,
                    title = "签名校验告警：",
                    lines = state.lastExportWarnings,
                )
            }
            Text(
                text = "文件已保存到你所选的位置，可直接安装（覆盖安装需要与原版签名一致或先卸载原版）。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        }

        Text(
            text = "签名密钥（固定）：$fingerprint",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        Text(
            text = "导出始终使用同一把内置密钥，因此同一台设备上的新包可以直接覆盖安装更新。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
    }
}

@Composable
private fun NoticeCard(color: Color, title: String, lines: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.40f), MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = color)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun defaultNewPackage(original: String): String =
    if (original.isBlank()) "com.example.arcaea.mod" else "$original.mod"

private fun suggestedFileName(rename: Boolean, packageName: String, original: String): String {
    val base = if (rename && packageName.isNotBlank()) {
        packageName.substringAfterLast('.')
    } else {
        original.substringAfterLast('.').ifBlank { "arcaea" }
    }
    return "${base}_mod.apk"
}
