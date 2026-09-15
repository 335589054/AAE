package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.local.arcaea.apkmanager.core.ApkProject
import dev.local.arcaea.apkmanager.core.Song
import dev.local.arcaea.apkmanager.data.PracticeRequest

/**
 * 练习谱生成参数对话框：难度多选 + 切割区间（手动输入 或 双指针滑块）+ 变速倍速 + （复制新曲 or 原地覆盖）。
 */
@Composable
fun PracticeDialog(
    songId: String,
    songTitle: String,
    validDifficulties: List<Int>,
    /** 谱面最晚时刻（毫秒），作为区间滑块的右端上限；为 0 时滑块禁用（不解析出时长） */
    maxMs: Long = 0L,
    busy: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (PracticeRequest) -> Unit,
) {
    val overwriteInPlace = remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var startS by remember { mutableStateOf("0") }
    var endS by remember { mutableStateOf("0") }
    var speedText by remember { mutableStateOf("1.0") }
    val selected = remember { mutableStateMapOf<Int, Boolean>().apply { validDifficulties.forEach { put(it, it == 2) } } }

    // 滑块与文本输入双向同步：全部统一用「秒」为内部单位（aff 里的毫秒只用于换算）。
    // 注意 maxMs 是异步加载的（打开对话框时可能还是 0），等它到位后再初始化区间右端。
    val maxSec = (maxMs / 1000).toFloat().coerceAtLeast(0f)
    var sliderRange by remember(maxSec) { mutableStateOf(0f..maxSec) }
    var endEdited by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(maxSec) {
        // 时长加载到且用户还没手动设过结束时间时，把结束默认设为满时长
        if (maxSec > 0 && !endEdited) {
            endS = String.format("%.1f", maxSec)
            sliderRange = 0f..maxSec
        }
    }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    fun startMs(): Long = ((startS.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    fun endMs(): Long = ((endS.toDoubleOrNull() ?: 0.0) * 1000).toLong()

    val validId = newId.trim().isNotEmpty() && ApkProject.isValidId(newId.trim())
    val canGenerate = !busy &&
        selected.values.any { it } &&
        endMs() > startMs() &&
        speedText.toDoubleOrNull() != null &&
        (!overwriteInPlace.value || validId)

    AppDialog(
        title = "生成练习谱 · $songTitle",
        onDismiss = onDismiss,
        dismissible = !busy,
        footer = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = AppTextDim) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val rcs = validDifficulties.filter { selected[it] == true }
                    if (rcs.isEmpty()) return@Button
                    onGenerate(
                        PracticeRequest(
                            songId = songId,
                            rcs = rcs,
                            startMs = startMs(),
                            endMs = endMs(),
                            speed = speedText.toDoubleOrNull() ?: 1.0,
                            newId = newId.trim().ifBlank { null },
                            newTitle = newTitle.trim().ifBlank { null },
                            overwriteInPlace = overwriteInPlace.value,
                        ),
                    )
                },
                enabled = canGenerate,
            ) { Text("生成") }
        },
    ) {
        Text("选择要提取的难度：", style = MaterialTheme.typography.labelSmall, color = AppTextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            validDifficulties.forEach { rc ->
                val checked = selected[rc] == true
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { selected[rc] = it }, enabled = !busy)
                    Text(Song.difficultyLabel(rc), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Text("切割区间（秒，从原谱面选取；开头会自动预留 1 小节缓冲）：", style = MaterialTheme.typography.labelSmall, color = AppTextDim)

        // 区间滑块：拖拽两个指针选择开始/结束，中间段为选中区间（内部单位秒）
        if (maxSec > 0f) {
            RangeSlider(
                value = sliderRange,
                onValueChange = { range ->
                    sliderRange = range
                    // 拖动时同步到文本（秒，保留 1 位小数）
                    if (!editingStart) startS = String.format("%.1f", range.start)
                    if (!editingEnd) {
                        endS = String.format("%.1f", range.endInclusive)
                        endEdited = true
                    }
                },
                valueRange = 0f..maxSec,
                enabled = !busy && validDifficulties.any { selected[it] == true },
            )
            Text(
                text = "${String.format("%.1f", sliderRange.start)} s  ~  ${String.format("%.1f", sliderRange.endInclusive)} s",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        } else {
            Text(
                text = "（正在读取谱面时长…）",
                style = MaterialTheme.typography.labelSmall,
                color = AppYellow,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = startS,
                onValueChange = {
                    startS = it
                    editingStart = true
                    it.toDoubleOrNull()?.let { v ->
                        // v 是秒，直接用秒更新滑块
                        sliderRange = v.toFloat().coerceIn(0f, sliderRange.endInclusive)..sliderRange.endInclusive
                    }
                },
                label = "开始 (秒)",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onFocusChangedRelease = { editingStart = false },
            )
            Text("~", style = MaterialTheme.typography.bodySmall)
            AppTextField(
                value = endS,
                onValueChange = {
                    endS = it
                    editingEnd = true
                    endEdited = true
                    it.toDoubleOrNull()?.let { v ->
                        // v 是秒，直接用秒更新滑块
                        sliderRange = sliderRange.start..v.toFloat().coerceIn(sliderRange.start, maxSec)
                    }
                },
                label = "结束 (秒)",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onFocusChangedRelease = { editingEnd = false },
            )
        }

        AppTextField(
            value = speedText,
            onValueChange = { speedText = it },
            label = "变速倍速（0.5 = 半速，1.0 不变，2.0 二倍速）",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        if (!overwriteInPlace.value) {
            AppTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = "新歌曲显示名（留空则沿用源曲名）",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                placeholder = songTitle,
            )
            AppTextField(
                value = newId,
                onValueChange = { newId = it },
                label = "新歌曲 id（目录名；仅字母/数字/下划线/连字符/点）",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                placeholder = "practice_$songId",
            )
            Text(
                "将新建歌曲并把切好/变好速的谱面与音频写入新目录，同时加入 songlist。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextDim,
            )
        } else {
            Text(
                "将覆盖原谱面与音频（导出安装包后生效）；建议先另存一份原谱面。",
                style = MaterialTheme.typography.labelSmall,
                color = AppYellow,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { overwriteInPlace.value = false },
                enabled = !busy,
            ) { Text("复制新曲", color = if (!overwriteInPlace.value) MaterialTheme.colorScheme.primary else AppTextDim) }
            OutlinedButton(
                onClick = { overwriteInPlace.value = true },
                enabled = !busy,
            ) { Text("原地修改", color = if (overwriteInPlace.value) AppRed else AppTextDim) }
        }
    }
}