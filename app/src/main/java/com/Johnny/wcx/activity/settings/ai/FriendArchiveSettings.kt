// Adapted from AARCNoa/zhiyou, AiProfileDialog.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiAutoIncrementalSchedule
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.ChatGoal
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.WeDateTimeField
import com.Johnny.wcx.ui.content.WeDateTimeMode
import com.Johnny.wcx.ui.content.formatMinuteOfDay
import com.Johnny.wcx.ui.content.parseMinuteOfDay
import com.Johnny.wcx.ui.utils.showComposeDialog

internal fun LazyListScope.friendArchiveSettings(
    context: Context,
    account: String,
    name: String,
    profile: AiProfile,
    selection: AiConfig.Selection?,
    canEdit: Boolean,
    save: ((AiProfile) -> AiProfile?) -> Unit,
) {
    val canEnable = canEdit && profile.isReady
    val requestData = "你与「$name」的近期私聊、按需查阅的历史消息和记忆，以及档案标签、聊天目标、人设和回复风格"
    fun setConsent(
        title: String,
        description: String,
        enabled: Boolean,
        binding: String,
        write: (AiProfile, Boolean, String) -> AiProfile,
    ) {
        item {
            ConsentRow(
                title,
                enabled,
                binding == selection?.id,
                canEnable && selection != null,
                disable = { save { write(it, false, "") } },
                enable = {
                    val selected = selection ?: return@ConsentRow
                    confirmAiAction(
                        context,
                        "启用$title",
                        "$description\n\n模型：${selected.displayName()}",
                        "确认启用",
                    ) {
                        save {
                            it.takeIf { it.isReady && !it.busy && bindingStillSelected(account, selected.id) }
                                ?.let { write(it, true, selected.id) }
                        }
                    }
                },
            )
        }
    }

    item {
        HorizontalDivider()
        Text("回复设置", style = MaterialTheme.typography.titleSmall)
        TextButton(enabled = canEdit, onClick = {
            editGoal(context, profile.goal) { goal ->
                save { it.takeIf { it.goal == profile.goal }?.copy(goal = goal) }
            }
        }) { Text("编辑聊天目标和禁忌") }
        TextButton(enabled = canEdit, onClick = {
            showArchiveEdit(context, "编辑我的人设", profile.persona, allowEmpty = true) { persona ->
                save { it.takeIf { it.persona == profile.persona }?.copy(persona = persona) }
            }
        }) { Text("编辑我的人设") }
        Column {
            TextButton(enabled = canEdit, onClick = {
                showArchiveEdit(context, "编辑回复风格", profile.style.summary, allowEmpty = true) { summary ->
                    save { current ->
                        current.takeIf { it.style == profile.style }
                            ?.copy(style = current.style.copy(summary = summary, userEdited = true, insufficient = false))
                    }
                }
            }) { Text("编辑回复风格") }
            if (profile.style.summary.isNotBlank()) {
                Text(profile.style.summary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            when {
                profile.style.userEdited -> TextButton(enabled = canEdit, onClick = {
                    save { it.takeIf { it.style == profile.style }?.copy(style = it.style.copy(userEdited = false)) }
                }) { Text("允许全量整理重新归纳风格") }
                profile.style.basedUntil.isNotBlank() -> WorkbenchStatus("已归纳至 ${profile.style.basedUntil}")
                profile.style.insufficient -> WorkbenchStatus("本人发言样本不足，暂未归纳")
            }
        }
    }
    setConsent(
        "智能建议",
        "在聊天页请求建议时，将发送$requestData，用于生成解读与回复候选。",
        profile.suggestEnabled,
        profile.suggestBinding,
    ) { current, enabled, binding -> current.copy(suggestEnabled = enabled, suggestBinding = binding) }
    setConsent(
        "好友自动回复",
        "「$name」发来新消息时，可能自动发送$requestData，并以你的微信账号向对方发送模型生成的回复。模型识别出的重要事件会记在本机 AI 总览。",
        profile.autoReplyEnabled,
        profile.autoReplyBinding,
    ) { current, enabled, binding -> current.copy(autoReplyEnabled = enabled, autoReplyBinding = binding) }
    item {
        val delay = profile.effectiveAutoReplyDelaySeconds
        TextButton(enabled = canEdit, onClick = {
            editAutoReplyDelay(context, delay) { seconds ->
                save {
                    it.takeIf { it.autoReplyDelaySeconds == profile.autoReplyDelaySeconds }
                        ?.copy(autoReplyDelaySeconds = seconds)
                }
            }
        }) { Text("自动回复延迟 · $delay 秒") }
    }
    setConsent(
        "发送最近图片",
        "为「$name」生成智能建议或自动回复时，会把近期私聊中对方最近发送且已保存在本机的一张图片上传到模型。启用好友自动回复后，图片也可能在后台上传。",
        profile.visionEnabled,
        profile.visionBinding,
    ) { current, enabled, binding -> current.copy(visionEnabled = enabled, visionBinding = binding) }
    item {
        val schedule = profile.autoIncremental
        val active = schedule != null && schedule.binding == selection?.id
        fun chooseTime() {
            val selected = selection ?: return
            editIncrementalTime(context, name, selected, schedule) { minute ->
                save {
                    it.takeIf { it.isReady && !it.busy && bindingStillSelected(account, selected.id) &&
                        it.autoIncremental == schedule
                    }?.copy(
                        autoIncremental = AiAutoIncrementalSchedule(
                            minute, selected.id, System.currentTimeMillis(), schedule?.lastAttemptAt ?: 0,
                        )
                    )
                }
            }
        }
        ConsentRow(
            "自动增量整理",
            schedule != null,
            active,
            canEnable && profile.coveredUntilSec > 0 && selection != null,
            disable = { save { it.copy(autoIncremental = null) } },
            enable = ::chooseTime,
        )
        if (active) {
            val now = System.currentTimeMillis()
            TextButton(enabled = canEnable, onClick = ::chooseTime) {
                Text("每天 ${formatMinuteOfDay(schedule.minuteOfDay)} · 修改时间")
            }
            if (schedule.lastAttemptAt > 0) WorkbenchStatus("上次尝试 ${timeLabel(schedule.lastAttemptAt)}")
            WorkbenchStatus(when {
                !profile.isReady && !profile.busy -> "恢复档案后继续按计划整理"
                schedule.dueAt(now) != null -> "本次待执行"
                else -> "下次 ${timeLabel(schedule.nextAt(now))}"
            })
        }
    }
}

@Composable
private fun ConsentRow(
    title: String,
    optedIn: Boolean,
    matchesBinding: Boolean,
    canEnable: Boolean,
    disable: () -> Unit,
    enable: () -> Unit,
) {
    val active = optedIn && matchesBinding
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = active,
            enabled = active || canEnable,
            role = Role.Switch,
            onValueChange = { if (it) enable() else disable() },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(
            checked = active,
            enabled = active || canEnable,
            onCheckedChange = null,
        )
    }
    if (optedIn && !matchesBinding) {
        WorkbenchStatus("模型配置已变化，请重新授权。")
        TextButton(onClick = disable) { Text("撤销授权") }
    }
}

private fun bindingStillSelected(account: String, binding: String): Boolean =
    AiConfig.selection(AiConfig.Purpose.FRIEND_PROFILE)?.let { it.account == account && it.id == binding } == true

private fun editAutoReplyDelay(context: Context, current: Int, save: (Int) -> Unit) = showComposeDialog(context) {
    var input by remember { mutableStateOf(current.toString()) }
    val range = AiProfile.AUTO_REPLY_DELAY_RANGE
    val seconds = input.trim().toIntOrNull()?.takeIf { it in range }
    AlertDialogContent(
        title = { Text("好友自动回复延迟") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("好友发来消息后，至少等待设定的秒数再开始生成回复。")
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("等待秒数（${range.first}–${range.last}）") },
                    isError = input.isNotBlank() && seconds == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    listOf(range.first, AiProfile.DEFAULT_AUTO_REPLY_DELAY_SECONDS, 30).forEach { preset ->
                        TextButton(onClick = { input = preset.toString() }) { Text("$preset 秒") }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(enabled = seconds != null, onClick = {
                save(seconds ?: return@Button)
                onDismiss()
            }) { Text("保存") }
        },
    )
}

private fun editGoal(context: Context, current: ChatGoal?, save: (ChatGoal?) -> Unit) = showComposeDialog(context) {
    var goal by remember { mutableStateOf(current ?: ChatGoal()) }
    AlertDialogContent(
        title = { Text("聊天目标和禁忌") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(goal.relation, { goal = goal.copy(relation = it) }, label = { Text("当前关系") }, singleLine = true)
                OutlinedTextField(goal.purpose, { goal = goal.copy(purpose = it) }, label = { Text("聊天目标") })
                OutlinedTextField(goal.stage, { goal = goal.copy(stage = it) }, label = { Text("关系阶段") }, singleLine = true)
                OutlinedTextField(goal.taboos, { goal = goal.copy(taboos = it) }, label = { Text("禁忌和边界") })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                val trimmed = ChatGoal(goal.relation.trim(), goal.purpose.trim(), goal.stage.trim(), goal.taboos.trim())
                save(trimmed.takeUnless { it.isEmpty })
                onDismiss()
            }) { Text("保存") }
        },
    )
}

private fun editIncrementalTime(
    context: Context,
    name: String,
    selected: AiConfig.Selection,
    current: AiAutoIncrementalSchedule?,
    save: (Int) -> Unit,
) = showComposeDialog(context) {
    var time by remember {
        mutableStateOf(formatMinuteOfDay(current?.minuteOfDay ?: AiAutoIncrementalSchedule.DEFAULT_MINUTE))
    }
    val minute = parseMinuteOfDay(time)
    AlertDialogContent(
        title = { Text("自动增量整理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("微信运行时每天按设定时间，将与「$name」尚未整理的私聊记录（$MEDIA_PLACEHOLDERS）和已有标签发送到模型。当天错过可在微信重新打开时补跑。")
                Text("模型：${selected.displayName()}")
                WeDateTimeField(time, { time = it }, "执行时间", WeDateTimeMode.TIME_ONLY, Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                enabled = minute != null && (current == null || minute != current.minuteOfDay || current.binding != selected.id),
                onClick = {
                    minute?.let(save)
                    onDismiss()
                },
            ) { Text("确认并保存") }
        },
    )
}
