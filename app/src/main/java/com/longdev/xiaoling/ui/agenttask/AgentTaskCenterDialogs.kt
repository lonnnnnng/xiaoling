package com.longdev.xiaoling.ui.agenttask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.ui.presentAgentTaskRetryEvidence

@Composable
internal fun AgentTaskCenterDialogs(
    state: AgentTaskCenterUiState,
    actions: AgentTaskCenterActions,
) {
    if (state.childBatchDraftOpen) {
        AgentChildBatchDialog(state.childBatchAgentLabel, actions::confirmReadOnlyChildBatch, actions::cancelReadOnlyChildBatchDraft)
    }
    state.pendingRetryConfirmation?.let { pending ->
        AgentRetryConfirmationDialog(
            pending = pending,
            onConfirm = actions::confirmAgentRunRetry,
            onDismiss = actions::cancelAgentRunRetry,
        )
    }
}

@Composable
private fun AgentChildBatchDialog(agentLabel: String, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = first.isNotBlank() && first.trim().length <= 2_000 && second.trim().length <= 2_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建只读多 Agent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent：$agentLabel", style = MaterialTheme.typography.bodySmall)
                Text("填写 1–2 个独立目标。子任务只使用已授权的基础只读能力；离开应用前台会停止。结果保存到独立会话。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(first, { first = it }, label = { Text("目标 1") },
                    modifier = Modifier.testTag("agent-child-goal-1"), maxLines = 3, isError = first.trim().length > 2_000)
                OutlinedTextField(second, { second = it }, label = { Text("目标 2（可选）") },
                    modifier = Modifier.testTag("agent-child-goal-2"), maxLines = 3, isError = second.trim().length > 2_000)
            }
        },
        confirmButton = { Button(onClick = {
            onConfirm(listOf(first.trim(), second.trim()).filter(String::isNotBlank))
        }, enabled = valid, modifier = Modifier.testTag("agent-child-confirm")) { Text("开始只读任务") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("agent-child-draft-cancel")) { Text("取消") } },
    )
}

@Composable
private fun AgentRetryConfirmationDialog(
    pending: AgentRetryConfirmationUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = presentAgentRetryConfirmation(pending)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = pending.goal,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val evidence = presentAgentTaskRetryEvidence(
                    pending.evidenceCode,
                    restartRequired = pending.kind != AgentRetryConfirmationKind.EVIDENCE_RETRY,
                )
                Text(
                    text = "${evidence.label} · ${evidence.code.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = presentation.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("agent-retry-confirm"),
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("创建新 Run")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("agent-retry-cancel"),
            ) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

internal data class AgentRetryConfirmationPresentation(
    val title: String,
    val detail: String,
)

internal fun presentAgentRetryConfirmation(
    pending: AgentRetryConfirmationUiState,
): AgentRetryConfirmationPresentation {
    val evidence = presentAgentTaskRetryEvidence(
        pending.evidenceCode,
        restartRequired = pending.kind != AgentRetryConfirmationKind.EVIDENCE_RETRY,
    )
    return when (pending.kind) {
        AgentRetryConfirmationKind.EVIDENCE_RETRY -> AgentRetryConfirmationPresentation(
            title = "确认重新运行",
            detail = "${evidence.detail} ${evidence.suggestedAction} 写入工具仍需重新审批。",
        )
        AgentRetryConfirmationKind.RESTART_REQUIRED_RELAUNCH -> AgentRetryConfirmationPresentation(
            title = "确认创建关联新 Run",
            detail = "来源 Run 无法原地恢复。将保留旧 Run 的终态和审计记录，并创建关联新 Run；" +
                "不会恢复旧模型协程、旧 Executor 或重放旧工具。新 Run 内的写入工具仍需重新审批。",
        )
        AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY -> AgentRetryConfirmationPresentation(
            title = "确认受控关联重试",
            detail = "将创建关联新 Run 并使用来源 Run 冻结的工具名称、风险和参数。" +
                "不会恢复旧 Run、旧模型协程或旧 Executor；新 Run 内的工具仍需重新审批。",
        )
    }
}
