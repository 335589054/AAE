package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/* --------------------------------- 排版组件 --------------------------------- */

/** 卡片容器：深色卡片 + 12dp 圆角，可选标题与右上角操作区。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = AppCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (actions != null) actions()
                }
            }
            content()
        }
    }
}

/** 小徽章（彩色描边 + 半透明底）。 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 「标签 + 值」两行信息块。 */
@Composable
fun InfoCell(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTextDim)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = AppTextDim)
}

/** 便携输入框。 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val labelSlot: (@Composable () -> Unit)? = if (label != null) {
        { Text(label, style = MaterialTheme.typography.labelSmall) }
    } else {
        null
    }
    val placeholderSlot: (@Composable () -> Unit)? = if (placeholder != null) {
        { Text(placeholder, style = MaterialTheme.typography.bodySmall) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        label = labelSlot,
        placeholder = placeholderSlot,
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/** 下拉选择器（用文字箭头，避免依赖图标库）。 */
@Composable
fun Selector(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "（未设置）",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) {
            FieldLabel(label)
            Spacer(Modifier.height(4.dp))
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.ifBlank { placeholder },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** 通用弹窗容器：标题 + 可滚动内容 + 底部按钮。 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    maxContentHeight: androidx.compose.ui.unit.Dp = 520.dp,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = AppCardAlt,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
                if (footer != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        footer()
                    }
                }
            }
        }
    }
}

/** 简单确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确认",
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppCardAlt,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodySmall, color = AppTextDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) AppRed else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppTextDim) }
        },
    )
}

/** 三个状态（未设置 / 是 / 否）切换行，用于 Boolean? 字段。 */
@Composable
fun TriStateField(
    label: String,
    value: Boolean?,
    onSelect: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf<Pair<String, Boolean?>>("未设置" to null, "是" to true, "否" to false)
    Column(modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, option) ->
                val selected = value == option
                OutlinedButton(
                    onClick = { onSelect(option) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else AppTextDim,
                    )
                }
            }
        }
    }
}

/** 忙碌遮罩：显示阶段文案与进度。 */
@Composable
fun BusyOverlay(stage: String, progress: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = AppCardAlt) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stage.ifBlank { "正在处理…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("$progress%", style = MaterialTheme.typography.labelSmall, color = AppTextDim)
            }
        }
    }
}

/** 空状态提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 字节数格式化（MB / GB）。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
