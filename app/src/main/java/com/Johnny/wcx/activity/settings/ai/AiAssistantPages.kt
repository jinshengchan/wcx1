package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiDailyReport
import com.Johnny.wcx.ai.AiDailyReportState
import com.Johnny.wcx.ai.AiImportantAlert
import com.Johnny.wcx.ai.AiImportantAlerts
import com.Johnny.wcx.ai.AiTool
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.items.ai.GroupDigestEntry
import com.Johnny.wcx.features.items.chat.AiAssistantMenus
import com.Johnny.wcx.features.items.chat.AiToolSettings
import com.Johnny.wcx.features.items.chat.showAiTool
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AiAssistantHelp() {
    Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("模型", style = MaterialTheme.typography.titleSmall)
        Text("在「AI 回复助手」添加 API 配置，再到本页「设置」选择工具模型。好友档案和群聊摘要继续各自选择模型。")
        Text("从微信使用", style = MaterialTheme.typography.titleSmall)
        Text("打开「聊天输入栏增强」，长按发送键或加号可查看聊天总结、待办、问答、群纪要、周报和跨会话工具。长按消息可润色、写备注、读图翻译或速读长消息。长按有文字的朋友圈可生成评论建议。")
        Text("生成与定时", style = MaterialTheme.typography.titleSmall)
        Text("手动工具点击「生成」才会向所选服务发送内容。每日总结、好友档案和群聊摘要的定时运行需要分别开启。关闭总开关会暂停运行并隐藏相关入口。")
    }
}

@Composable
internal fun AiAssistantSettings(context: Context, account: String?, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiSettingSwitch("AI 助手总开关", enabled, onEnabled)
        if (!enabled) {
            Text("开启后可使用 AI 回复、好友档案、群聊摘要和各项工具。")
        } else if (account == null) {
            Text("请先进入微信主界面，等待当前账号的消息库就绪。")
        } else {
            Text("工具与每日总结模型", style = MaterialTheme.typography.titleSmall)
            AiProviderChoice(AiConfig.Purpose.TOOLS, revision) { provider ->
                changeAiProvider(context, account, AiConfig.Purpose.TOOLS, provider) {
                    revision++
                    AiDailyReport.wake()
                }
            }
            var doubleTap by remember { mutableStateOf(AiToolSettings.doubleTapEnabled) }
            AiSettingSwitch("双击回复建议", doubleTap) { doubleTap = it; AiToolSettings.doubleTapEnabled = it }
            var groupEntry by remember { mutableStateOf(GroupDigestEntry.entryEnabled) }
            AiSettingSwitch("首页群总结入口", groupEntry) { groupEntry = it; GroupDigestEntry.entryEnabled = it }
            var alerts by remember { mutableStateOf(AiImportantAlerts.enabled) }
            AiSettingSwitch("重要信息提醒", alerts) { alerts = it; AiImportantAlerts.enabled = it }
            Text("从自动回复处理的来信中按规则提取期限、金额和地点，保存在本机。")
            AiWorkbenchSettings()
            HorizontalDivider()
            Text("AI 工具入口", style = MaterialTheme.typography.titleSmall)
            val tools = AiAssistantMenus.inputTools + AiAssistantMenus.messageTools +
                listOf(AiTool.MOMENTS_COMMENT, AiTool.MOMENTS_COPY)
            tools.forEach { tool ->
                var active by remember(tool) { mutableStateOf(AiToolSettings.enabled(tool)) }
                AiSettingSwitch(tool.title, active) { active = it; AiToolSettings.setEnabled(tool, it) }
                if (tool == AiTool.MOMENTS_COPY && active) {
                    TextButton(onClick = { showAiTool(context, tool) }) { Text("生成朋友圈文案") }
                }
            }
        }
    }
}

@Composable
private fun AiSettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

@Composable
internal fun AiAssistantReports(context: Context, account: String) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val report by produceState(AiDailyReportState(), account, revision) {
        while (true) {
            value = withContext(Dispatchers.IO) { AiDailyReport.read(account) }
            delay(2000)
        }
    }
    val alerts by produceState(emptyList<AiImportantAlert>(), account, revision) {
        while (true) {
            value = withContext(Dispatchers.IO) { AiImportantAlerts.pending(account) }
            delay(2000)
        }
    }
    var hourText by remember(report.hour) { mutableStateOf(report.hour.toString()) }
    val hour = hourText.toIntOrNull()?.takeIf { it in 0..23 }
    val selection = AiConfig.selection(AiConfig.Purpose.TOOLS)?.takeIf { it.account == account }
    Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (alerts.isNotEmpty()) {
            Text("重要信息 · ${alerts.size} 条", style = MaterialTheme.typography.titleSmall)
            alerts.take(15).forEach { alert -> Text("${alert.name}：${alert.text}") }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { AiImportantAlerts.clear(account) }
                    revision++
                }
            }) { Text("清空提醒") }
            HorizontalDivider()
        }
        Text("每日总结", style = MaterialTheme.typography.titleSmall)
        if (selection == null) Text("请先在设置中选择工具模型。")
        Button(enabled = selection != null, onClick = {
            showAiTool(context, AiTool.DAILY_REPORT, onResult = { answer ->
                scope.launch {
                    withContext(Dispatchers.IO) { AiDailyReport.saveReport(account, answer) }
                    revision++
                }
            })
        }) { Text("生成今天的总结") }
        OutlinedTextField(
            value = hourText,
            onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
            label = { Text("定时小时（0–23，本机时间）") },
            singleLine = true,
        )
        val configured = report.bindingId.isNotBlank()
        if (configured) {
            Text(if (report.bindingId == selection?.id) "定时已开启 · 每天 ${report.hour}:00" else "定时已暂停，请重新确认所选模型。")
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { AiDailyReport.schedule(account, report.hour, "") }
                    revision++
                }
            }) { Text("关闭定时") }
        }
        TextButton(enabled = hour != null && selection != null, onClick = {
            val model = selection ?: return@TextButton
            val scheduledHour = hour ?: return@TextButton
            confirmAiAction(
                context, "开启每日总结",
                "每天 ${scheduledHour}:00 会向 ${model.endpointHost} 发送当天活跃会话的部分近期消息和当天已更新的工作群进度，以生成总结。",
                "开启",
            ) {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        AiConfig.selection(AiConfig.Purpose.TOOLS) == model &&
                            AiDailyReport.schedule(account, scheduledHour, model.id)
                    }
                    if (!saved) showToast(context, "设置保存失败，请检查模型和微信账号")
                    revision++
                }
            }
        }) { Text(if (configured) "更新定时设置" else "开启定时") }
        if (report.lastError.isNotBlank()) Text(report.lastError)
        if (report.text.isNotBlank()) {
            HorizontalDivider()
            Text(timeLabel(report.reportAt))
            Text(report.text)
        }
    }
}

@Composable
internal fun AiWorkGroupSettings(context: Context, account: String, group: GroupDigestConfig) {
    val scope = rememberCoroutineScope()
    var goal by remember(group.groupId, group.workGoal) { mutableStateOf(group.workGoal) }
    HorizontalDivider()
    Text("工作群", style = MaterialTheme.typography.titleSmall)
    AiSettingSwitch("作为工作群跟踪", group.workGroup) { enabled ->
        scope.launch {
            withContext(Dispatchers.IO) { GroupDigestStore.update(group.groupId, account) { it.copy(workGroup = enabled) } }
        }
    }
    if (group.workGroup) {
        OutlinedTextField(goal, { goal = it.take(300) }, label = { Text("工作目标") })
        Row {
            TextButton(enabled = goal != group.workGoal, onClick = {
                val target = goal.trim()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        GroupDigestStore.update(group.groupId, account) {
                            it.copy(workGoal = target, workProgress = "", workProgressAt = 0)
                        }
                    }
                }
            }) { Text("保存目标") }
            TextButton(onClick = {
                showAiTool(context, AiTool.WORK_PROGRESS, talker = group.groupId, workGoal = group.workGoal,
                    onResult = { answer ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                GroupDigestStore.update(group.groupId, account) {
                                    if (it.workGroup && it.workGoal == group.workGoal) it.copy(
                                        workProgress = answer.take(5_000), workProgressAt = System.currentTimeMillis(),
                                    ) else it
                                }
                            }
                        }
                    })
            }) { Text("更新进度") }
        }
        if (group.workProgress.isNotBlank()) {
            Text(timeLabel(group.workProgressAt))
            Text(group.workProgress)
        }
    }
}
