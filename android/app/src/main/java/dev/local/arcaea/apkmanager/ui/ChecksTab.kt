package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.data.UiState

/** 检查页：会导致游戏异常的问题（红）与可忽略的提示（灰）。 */
@Composable
fun ChecksTab(state: UiState, vm: ApkViewModel, modifier: Modifier = Modifier) {
    if (state.project == null) {
        Column(modifier.fillMaxSize()) { EmptyHint("请先在「工程」页导入 APK") }
        return
    }
    val warnings = state.snapshot?.warnings ?: emptyList()
    val notes = state.snapshot?.notes ?: emptyList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "问题（会导致游戏异常）：${warnings.size}",
            actions = {
                OutlinedButton(onClick = { vm.refresh() }) { Text("重新检查") }
            },
        ) {
            if (warnings.isEmpty()) {
                Text(
                    text = "没有发现问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppGreen,
                )
            } else {
                warnings.forEach { warning ->
                    MessageCard(warning, AppRed)
                }
                Text(
                    text = "以上问题会让游戏在打开歌单 / 进入难度时闪退，建议先处理再导出。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                )
            }
        }

        SectionCard(title = "提示（可忽略）：${notes.size}") {
            if (notes.isEmpty()) {
                Text(
                    text = "没有发现问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppGreen,
                )
            } else {
                notes.forEach { note ->
                    MessageCard(note, AppTextDim)
                }
            }
        }

        SectionCard(title = "关于修改范围") {
            Text(
                text = "• 只修改 assets/ 下的资源与 AndroidManifest.xml 的包名，不会触碰 classes.dex、lib/、resources.arsc。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
            Text(
                text = "• 未改动的 zip 条目会原样直通拷贝，因此不会重新压缩整个安装包。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
            Text(
                text = "• 原有签名会在导出时被移除，并由内置密钥重新生成 v1 / v2 / v3 签名。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MessageCard(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("!", style = MaterialTheme.typography.bodySmall, color = color)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
