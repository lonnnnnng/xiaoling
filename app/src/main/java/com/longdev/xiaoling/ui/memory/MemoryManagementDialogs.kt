package com.longdev.xiaoling.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AgentMemoryRecord

private val agentMemoryTypes = listOf("Preference", "ProfileFact", "Episode", "Procedure")

@Composable
internal fun MemoryManagementDialogs(
    state: MemoryManagementUiState,
    actions: MemoryManagementActions,
) {
    state.editingMemory?.let { draft ->
        AgentMemoryEditDialog(
            draft = draft,
            saving = state.savingMemoryEdit,
            actions = actions,
        )
    }
    state.pendingMemoryDelete?.let { memory ->
        AgentMemoryDeleteDialog(
            memory = memory,
            deleting = state.deletingMemory,
            onConfirm = actions::confirmMemoryDelete,
            onDismiss = actions::cancelMemoryDelete,
        )
    }
}

@Composable
private fun AgentMemoryEditDialog(
    draft: AgentMemoryEditUiState,
    saving: Boolean,
    actions: MemoryManagementActions,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) actions.cancelMemoryEdit() },
        title = {
            Text(
                text = "编辑长期记忆",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.content,
                    onValueChange = actions::updateMemoryEditContent,
                    label = { Text("记忆内容") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory-edit-content"),
                )
                OutlinedTextField(
                    value = draft.tags,
                    onValueChange = actions::updateMemoryEditTags,
                    label = { Text("标签") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory-edit-tags"),
                )
                Text("类型", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    agentMemoryTypes.forEach { type ->
                        val selected = type == draft.type
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .testTag("memory-edit-type-$type")
                                .clickable(enabled = !saving) { actions.updateMemoryEditType(type) },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 9.dp),
                            ) {
                                Text(type, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(
                    text = "置信度 " + (draft.confidence * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = draft.confidence.toFloat(),
                    onValueChange = { actions.updateMemoryEditConfidence(it.toDouble()) },
                    enabled = !saving,
                    valueRange = 0f..1f,
                    steps = 19,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = actions::saveMemoryEdit,
                enabled = !saving && draft.content.isNotBlank(),
                modifier = Modifier.testTag("memory-edit-save"),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions::cancelMemoryEdit,
                enabled = !saving,
                modifier = Modifier.testTag("memory-edit-cancel"),
            ) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun AgentMemoryDeleteDialog(
    memory: AgentMemoryRecord,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除长期记忆", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "删除后，该记忆及其检索索引会立即移除，之后不再参与 Agent 检索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("memory-delete-confirm"),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !deleting,
                modifier = Modifier.testTag("memory-delete-cancel"),
            ) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}
