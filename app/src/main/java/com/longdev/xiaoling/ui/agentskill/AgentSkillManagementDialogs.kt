package com.longdev.xiaoling.ui.agentskill

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun AgentSkillManagementDialogs(
    state: AgentSkillManagementUiState,
    onConfirmLocalSkillDelete: () -> Unit,
    onCancelLocalSkillDelete: () -> Unit,
) {
    state.pendingLocalSkillDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = {
                if (!state.deletingLocalSkill) onCancelLocalSkillDelete()
            },
            title = { Text("删除本地 Skill", style = MaterialTheme.typography.titleSmall) },
            text = { Text(skill.definition.name, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(
                    onClick = onConfirmLocalSkillDelete,
                    enabled = !state.deletingLocalSkill,
                    modifier = Modifier.testTag("skill-delete-confirm"),
                ) {
                    Text(if (state.deletingLocalSkill) "删除中" else "删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelLocalSkillDelete,
                    enabled = !state.deletingLocalSkill,
                    modifier = Modifier.testTag("skill-delete-cancel"),
                ) {
                    Text("取消")
                }
            },
        )
    }
}
