package com.longdev.xiaoling.ui.workflow

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

@Composable
internal fun WorkflowManagementDialogs(
    state: WorkflowManagementUiState,
    actions: WorkflowManagementActions,
) {
    state.pendingRetryConfirmation?.let { pending ->
        WorkflowRetryConfirmationDialog(
            pending = pending,
            onConfirm = actions::confirmWorkflowRunRetry,
            onDismiss = actions::cancelWorkflowRunRetry,
        )
    }
}

@Composable
private fun WorkflowRetryConfirmationDialog(
    pending: WorkflowRetryConfirmationUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "确认重试工作流",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pending.workflowName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "将从步骤 ${pending.retryFromSequence} 重新执行，复用前 ${pending.reusedStepCount} 个已完成步骤。新 Run 会保留来源 Run ID，旧 Run 和历史快照不会修改。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "待重试步骤可能已产生部分外部副作用；写入工具仍会重新请求审批。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("workflow-retry-confirm"),
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("创建新 Run")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("workflow-retry-cancel"),
            ) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}
