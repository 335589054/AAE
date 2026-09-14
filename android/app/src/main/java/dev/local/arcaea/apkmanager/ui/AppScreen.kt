package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.data.ApkViewModel

private val TAB_TITLES = listOf("工程", "歌曲", "曲包", "检查")

/** 应用主界面：顶栏 + 底部 4 个 Tab + 全局提示。 */
@Composable
fun AppScreen(vm: ApkViewModel) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        val message = state.message
        if (message != null && state.error == null) {
            snackbarHostState.showSnackbar(message)
            vm.consumeMessage()
        }
    }

    Scaffold(
        containerColor = AppBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                subtitle = state.project?.manifest?.packageName,
                canExport = state.project != null && !state.busy,
                onSelfTest = { vm.runSelfTest() },
                onExport = { showExport = true },
            )
        },
        bottomBar = { AppBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                0 -> ProjectTab(state, vm)
                1 -> SongsTab(state, vm)
                2 -> PacksTab(state, vm)
                else -> ChecksTab(state, vm)
            }
        }
    }

    if (showExport) {
        ExportDialog(state, vm, onDismiss = { showExport = false })
    }

    // 导出完成后的清理提示：放在导出对话框之后组合，保证它显示在最上层（不会被导出对话框遮挡）。
    state.exportCleanup?.let { prompt ->
        CacheCleanupDialog(
            title = "导出完成",
            subtitle = "已保存：${prompt.target}",
            usage = prompt.usage,
            hasPendingChanges = prompt.hasPendingChanges,
            onDismiss = { vm.dismissExportCleanup() },
            onConfirm = { s, c, p -> vm.clearCache(s, c, p) },
        )
    }

    if (state.showSelfTest) {
        AppDialog(
            title = "自检结果",
            onDismiss = { vm.dismissSelfTest() },
            footer = {
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.dismissSelfTest() }) { Text("知道了") }
            },
        ) {
            Text(
                text = state.selfTestSummary ?: "（没有输出）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { vm.consumeMessage() },
            containerColor = AppCardAlt,
            title = { Text("出错了", style = MaterialTheme.typography.titleMedium) },
            text = { Text(error, style = MaterialTheme.typography.bodySmall, color = AppTextDim) },
            confirmButton = {
                TextButton(onClick = { vm.consumeMessage() }) { Text("知道了") }
            },
        )
    }

    if (state.busy && !showExport) {
        BusyOverlay(state.stage, state.progress)
    }
}

@Composable
private fun AppTopBar(
    subtitle: String?,
    canExport: Boolean,
    onSelfTest: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = AppBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Arcaea 安装包修改器",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle ?: "未导入安装包",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSelfTest) { Text("自检") }
            Button(onClick = onExport, enabled = canExport) { Text("导出") }
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = AppCard) {
        TAB_TITLES.forEachIndexed { index, title ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Text(title, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}
