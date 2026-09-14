package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.data.CacheUsage

/**
 * 缓存清理对话框（「工程」页「清除缓存」与导出完成提示共用）。
 *
 * 三个可勾选项分别对应导入的源 APK 副本 / 缓存的导出 APK / 项目缓存数据；
 * 存在未导出的改动时，「项目缓存数据」不可勾选（清理会让这些改动失效）。
 */
@Composable
fun CacheCleanupDialog(
    title: String,
    subtitle: String? = null,
    usage: CacheUsage,
    hasPendingChanges: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (deleteSourceApk: Boolean, deleteCachedApk: Boolean, deleteProjectData: Boolean) -> Unit,
) {
    var deleteSource by remember { mutableStateOf(false) }
    var deleteCached by remember { mutableStateOf(true) }
    var deleteProjectData by remember { mutableStateOf(true) }

    val canCleanProjectData = !hasPendingChanges
    val projectDataChecked = canCleanProjectData && deleteProjectData
    val canConfirm = deleteSource || deleteCached || projectDataChecked

    if (usage.isEmpty) {
        AppDialog(
            title = title,
            onDismiss = onDismiss,
            footer = {
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("知道了") }
            },
        ) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppGreen,
                )
            }
            Text(
                text = "当前没有可清理的缓存",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextDim,
            )
        }
        return
    }

    AppDialog(
        title = title,
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("暂不清理", color = AppTextDim) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onConfirm(deleteSource, deleteCached, projectDataChecked) },
                enabled = canConfirm,
            ) {
                Text("清理所选")
            }
        },
    ) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppGreen,
            )
        }
        Text(
            text = "以下缓存都可以安全删除，删除后不影响已导出的 APK。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextDim,
        )
        CleanupOption(
            checked = deleteSource,
            onCheckedChange = { deleteSource = it },
            enabled = true,
            title = "删除导入的源 APK 副本",
            description = "删除后关闭工程需要重新导入；本次会话仍可继续修改与导出",
            sizeBytes = usage.sourceApkBytes,
        )
        CleanupOption(
            checked = deleteCached,
            onCheckedChange = { deleteCached = it },
            enabled = true,
            title = "删除缓存的导出 APK",
            description = "导出时保留的产物副本，删除不影响你保存到其它位置的 APK",
            sizeBytes = usage.cachedApkBytes,
        )
        CleanupOption(
            checked = projectDataChecked,
            onCheckedChange = { deleteProjectData = it },
            enabled = canCleanProjectData,
            title = "删除项目缓存数据",
            description = "导入资源 / 改 id 产生的临时文件与自检文件",
            sizeBytes = usage.projectDataBytes,
            warning = if (hasPendingChanges) {
                "存在未导出的改动，清理后这些改动会失效，请先导出"
            } else {
                null
            },
        )
        Text(
            text = "共 ${formatBytes(usage.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CleanupOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    title: String,
    description: String,
    sizeBytes: Long,
    warning: String? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatBytes(sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppRed,
                )
            }
        }
    }
}
