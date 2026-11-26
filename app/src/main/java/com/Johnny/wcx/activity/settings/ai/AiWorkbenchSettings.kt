package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.ai.AiCueSound
import com.Johnny.wcx.features.ai.AiReplyOptions
import com.Johnny.wcx.features.ai.AiReplySettings
import com.Johnny.wcx.features.ai.ReasoningEffort

@Composable
internal fun AiWorkbenchSettings() {
    val stored = remember { AiReplySettings.read() }
    var effort by remember { mutableStateOf(stored.effort) }
    var maxTokens by remember { mutableStateOf(stored.maxTokens.toString()) }
    var temperature by remember { mutableStateOf(stored.temperature.toString()) }
    var enabled by remember { mutableStateOf(AiCueSound.enabled) }
    var style by remember { mutableStateOf(AiCueSound.style) }
    var effortMenu by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val outputLimit = maxTokens.trim().toIntOrNull()?.takeIf { it in 0..AiReplySettings.MAX_OUTPUT_TOKENS }
    val variability = temperature.trim().toDoubleOrNull()?.takeIf { it in 0.0..2.0 }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("双击提示音", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                AiCueSound.enabled = it
                if (it) AiCueSound.preview(style)
            })
        }
        if (enabled) {
            Row {
                AiCueSound.Style.entries.forEach { item ->
                    TextButton(onClick = {
                        style = item
                        AiCueSound.style = item
                        AiCueSound.preview(item)
                    }) { Text(if (style == item) "${item.label} · 已选" else item.label) }
                }
            }
        }
        Text("智能建议与档案自动回复")
        Column {
            TextButton(onClick = { effortMenu = true }) { Text("深度思考：${effort.label}") }
            DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                ReasoningEffort.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label) }, onClick = {
                        effort = item
                        effortMenu = false
                        saved = false
                    })
                }
            }
        }
        Text("深度思考需要所选模型支持。")
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it; saved = false },
            label = { Text("输出上限（0 为模型默认）") },
            isError = outputLimit == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = temperature,
            onValueChange = { temperature = it; saved = false },
            label = { Text("回复温度（0–2）") },
            isError = variability == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = outputLimit != null && variability != null, onClick = {
            AiReplySettings.save(AiReplyOptions(effort, outputLimit ?: return@Button, variability ?: return@Button))
            saved = true
        }) { Text(if (saved) "已保存" else "保存回复参数") }
    }
}
