package com.longdev.xiaoling.ui.agenttask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
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
import com.longdev.xiaoling.ui.presentAgentTaskRetryEvidence

@Composable
internal fun AgentTaskCenterDialogs(
    state: AgentTaskCenterUiState,
    actions: AgentTaskCenterActions,
) {
    state.pendingRetryConfirmation?.let { pending ->
        AgentRetryConfirmationDialog(
            pending = pending,
            onConfirm = actions::confirmAgentRunRetry,
            onDismiss = actions::cancelAgentRunRetry,
        )
    }
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
