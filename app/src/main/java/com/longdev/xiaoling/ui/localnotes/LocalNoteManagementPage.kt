package com.longdev.xiaoling.ui.localnotes

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.xiaoling.agent.AgentNoteRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LocalNoteManagementPage(
    onBack: () -> Unit,
    preferredNoteId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: LocalNoteManagementViewModel = viewModel(),
) {
    LaunchedEffect(preferredNoteId) {
        preferredNoteId?.let(viewModel::selectNote)
    }
    LocalNoteManagementContent(
        state = viewModel.uiState,
        actions = viewModel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LocalNoteManagementContent(
    state: LocalNoteManagementUiState,
    actions: LocalNoteManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // long: 页面标题和返回入口固定在滚动区之外，笔记较多时用户仍能确认当前位置并立即返回设置。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(17.dp))
            }
            Text("本地笔记", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = actions::refresh,
                enabled = !state.loading,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新本地笔记", modifier = Modifier.size(17.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = actions::updateSearchQuery,
                modifier = Modifier.weight(1f),
                label = { Text("搜索笔记") },
                placeholder = { Text("输入标题或正文关键词") },
                singleLine = true,
                trailingIcon = if (state.searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = actions::clearSearch) {
                            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                        }
                    }
                } else {
                    null
                },
            )
            IconButton(
                onClick = actions::search,
                enabled = !state.loading,
                modifier = Modifier.size(40.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = "搜索本地笔记")
                }
            }
        }

        state.notice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(9.dp))
            }
        }

        state.error?.takeIf { state.pendingDeleteNote == null && state.editingNote == null }?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(9.dp))
            }
        }

        Text(
            text = if (state.showingSearchResults) {
                "搜索结果 ${state.notes.size} 条 · 最多展示 10 条"
            } else {
                "最近 ${state.notes.size} 条 · 最多展示 10 条"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading && state.notes.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (state.notes.isEmpty()) {
                item {
                    Text(
                        text = if (state.showingSearchResults) "没有找到匹配的本地笔记" else "还没有本地笔记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            } else {
                items(state.notes, key = AgentNoteRecord::id) { note ->
                    LocalNoteItemCard(note = note, onClick = { actions.selectNote(note.id) })
                }
            }
        }
    }

    when {
        state.pendingDeleteNote != null -> {
            LocalNoteDeleteConfirmationDialog(
                note = state.pendingDeleteNote,
                deleting = state.deleting,
                error = state.error,
                onConfirm = actions::confirmDelete,
                onCancel = actions::cancelDelete,
            )
        }
        state.editingNote != null -> {
            LocalNoteEditDialog(
                source = state.editingNote,
                title = state.editTitle,
                content = state.editContent,
                saving = state.savingEdit,
                error = state.error,
                onTitleChange = actions::updateEditTitle,
                onContentChange = actions::updateEditContent,
                onConfirm = actions::confirmEdit,
                onCancel = actions::cancelEdit,
            )
        }
        state.selectedNote != null -> {
            LocalNoteDetailDialog(
                note = state.selectedNote,
                onEdit = { actions.requestEdit(state.selectedNote.id) },
                onDelete = { actions.requestDelete(state.selectedNote.id) },
                onClose = actions::closeDetail,
            )
        }
        state.loadingDetail && state.selectedNoteId != null -> {
            LocalNoteLoadingDialog(onClose = actions::closeDetail)
        }
    }
}

@Composable
private fun LocalNoteItemCard(
    note: AgentNoteRecord,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(note.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "更新于 ${note.updatedAt.toLocalNoteTimeLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalNoteDetailDialog(
    note: AgentNoteRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(note.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "版本 ${note.revision} · 创建于 ${note.createdAt.toLocalNoteTimeLabel()}\n更新于 ${note.updatedAt.toLocalNoteTimeLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑笔记", modifier = Modifier.size(17.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除笔记",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp),
                    )
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun LocalNoteEditDialog(
    source: AgentNoteRecord,
    title: String,
    content: String,
    saving: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val canSave = title.isNotBlank() && content.isNotBlank() &&
        (title.trim() != source.title || content.trim() != source.content)
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("编辑本地笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "基于版本 ${source.revision} 保存；如果笔记已在其他位置更新，本次不会覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("标题") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local-note-edit-title"),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("正文") },
                    minLines = 5,
                    maxLines = 10,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local-note-edit-content"),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canSave && !saving) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                }
                Text(if (saving) "保存中" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
        },
    )
}

@Composable
private fun LocalNoteDeleteConfirmationDialog(
    note: AgentNoteRecord,
    deleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onCancel() },
        title = { Text("删除本地笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将永久删除“${note.title}”的标题和正文。历史 Agent 工具调用不会恢复已删除内容。")
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                if (deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(if (deleting) "删除中" else "确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !deleting) { Text("取消") }
        },
    )
}

@Composable
private fun LocalNoteLoadingDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("读取笔记") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在读取完整正文...")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

private fun Long.toLocalNoteTimeLabel(): String {
    return SimpleDateFormat(LOCAL_NOTE_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val LOCAL_NOTE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
