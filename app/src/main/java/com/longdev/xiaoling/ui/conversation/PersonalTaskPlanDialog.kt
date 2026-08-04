package com.longdev.xiaoling.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.ui.PendingPersonalTaskPlanUiState

@Composable
internal fun PersonalTaskPlanDialog(
    state: PendingPersonalTaskPlanUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = state.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.sourceGoal,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "计划上下文：长期记忆 ${state.memoryContextCount} 条 · " +
                        "本地知识 ${state.knowledgeContextCount} 个片段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.reminderScheduleLabel?.let { label ->
                    Text(
                        text = "应用内提醒：$label\n这是非精确定时，系统可能在计划时间后延迟执行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                state.targetAppPackage?.let { packageName ->
                    Text(
                        text = "限定应用：$packageName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                state.goalVerificationSpec?.let { verification ->
                    Text(
                        text = buildString {
                            append("完成标准：")
                            append(verification.requiredToolNames.joinToString(" -> "))
                            verification.expectedFinalPackageName?.let { packageName ->
                                append("\n完成时应用：$packageName")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.steps.forEachIndexed { index, goal ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(text = goal, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    text = if (state.reminderScheduleLabel == null) {
                        "确认只会开始执行这份计划；需要确认的具体动作仍会逐项审批。"
                    } else {
                        "确认只会创建这条应用内提醒；需要审批的动作到时不会在后台自动获批，而会通知你处理。"
                    } +
                        state.approvalToolNames.takeIf(List<String>::isNotEmpty)
                            ?.joinToString(prefix = " 可能触发审批：", separator = "、")
                            .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = buildString {
                        append("${state.agentName} · ${state.model}\n")
                        append("工具边界：")
                        append(state.allowedToolNames.joinToString("、").ifBlank { "无可用工具" })
                        append("。执行不能使用未列出的工具，也不会绕过现有审批、验证和 Room 审计。")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("personal-task-plan-confirm"),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.reminderScheduleLabel == null) "确认并执行" else "确认并创建提醒")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("personal-task-plan-cancel"),
            ) {
                Text("返回修改")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}
