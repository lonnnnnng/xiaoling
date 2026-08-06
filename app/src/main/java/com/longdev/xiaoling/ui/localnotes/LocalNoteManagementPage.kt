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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    modifier: Modifier = Modifier,
    viewModel: LocalNoteManagementViewModel = viewModel(),
) {
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

        state.error?.let { error ->
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
        state.selectedNote != null -> {
            LocalNoteDetailDialog(note = state.selectedNote, onClose = actions::closeDetail)
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
                    text = "创建于 ${note.createdAt.toLocalNoteTimeLabel()}\n更新于 ${note.updatedAt.toLocalNoteTimeLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
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
