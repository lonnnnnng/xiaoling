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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认重新运行",
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
                val evidence = presentAgentTaskRetryEvidence(pending.evidenceCode)
                Text(
                    text = "${evidence.label} · ${evidence.code.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${evidence.detail} ${evidence.suggestedAction} 写入工具仍需重新审批。",
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
