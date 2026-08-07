package com.longdev.xiaoling.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.agent.AgentMemoryDecayPolicy
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MemoryManagementPage(
    state: MemoryManagementUiState,
    actions: MemoryManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memoryListState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (state.memories.isEmpty() && !state.loading) {
            actions.refreshMemories()
        }
    }
    val selectedMemoryIndex = state.memories.indexOfFirst { item -> item.selected }
    LaunchedEffect(
        selectedMemoryIndex,
        state.candidatesEnabled,
        state.loadingCandidates,
        state.candidates.size,
        state.deletedMemoryForUndo?.id,
    ) {
        if (selectedMemoryIndex < 0) return@LaunchedEffect
        val precedingItemCount = (if (state.deletedMemoryForUndo != null) 1 else 0) +
            if (state.candidatesEnabled) {
                2 + if (state.candidates.isEmpty() && !state.loadingCandidates) 1 else state.candidates.size
            } else {
                0
            }
        // long: 答案导航会在进入页面前选中当前 Room 记录；按实际前置区块计算索引，确保目标卡片不会被候选记忆或撤销提示留在屏幕之外。
        memoryListState.scrollToItem(precedingItemCount + selectedMemoryIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MemoryManagementHeader(
            loading = state.loading,
            onBack = onBack,
            onRefresh = actions::refreshMemories,
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("候选记忆", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    text = if (state.candidatesEnabled) "已开启" else "已关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = state.candidatesEnabled,
                    onCheckedChange = actions::updateMemoryCandidatesEnabled,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = actions::updateMemorySearchQuery,
            placeholder = { Text("搜索内容、标签、类型或来源", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { actions.updateMemorySearchQuery("") },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "清空搜索", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        MemoryFilterBar(
            selected = state.filter,
            onSelected = actions::updateMemoryFilter,
        )

        LazyColumn(
            state = memoryListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.deletedMemoryForUndo?.let { deleted ->
                item(key = "memory-delete-undo") {
                    DeletedMemoryUndo(
                        memory = deleted,
                        onUndo = actions::undoMemoryDelete,
                    )
                }
            }
            if (state.candidatesEnabled) {
                item(key = "memory-candidate-title") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("待处理候选", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (state.loadingCandidates) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        }
                    }
                }
                if (!state.loadingCandidates && state.candidates.isEmpty()) {
                    item(key = "memory-candidate-empty") {
                        Text(
                            "暂无待处理候选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = state.candidates,
                    key = { item -> item.record.id },
                ) { item ->
                    MemoryCandidateCard(
                        item = item,
                        onAccept = { actions.acceptMemoryCandidate(item.record.id) },
                        onReject = { actions.rejectMemoryCandidate(item.record.id) },
                    )
                }
                item(key = "confirmed-memory-title") {
                    Text("正式记忆", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                }
            }
            when {
                state.error != null -> item {
                    MemoryManagementSection(title = "读取失败") {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.loading && state.memories.isEmpty() -> item {
                    MemoryManagementSection(title = "长期记忆") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Text("正在读取长期记忆", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.memories.isEmpty() -> item {
                    MemoryManagementSection(title = "长期记忆") {
                        Text(
                            text = if (state.searchQuery.isBlank() && state.filter == AgentMemoryFilter.ALL) {
                                "还没有长期记忆。Agent 只有在用户批准 memory.remember 后才会写入。"
                            } else {
                                "没有符合当前搜索和筛选条件的记忆"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(
                    items = state.memories,
                    key = { item -> item.record.id },
                ) { item ->
                    val memory = item.record
                    MemoryItemCard(
                        memory = memory,
                        selected = item.selected,
                        mutating = item.mutating,
                        onSelect = { actions.selectMemory(memory.id) },
                        onPinnedChange = { actions.setMemoryPinned(memory.id, it) },
                        onEnabledChange = { actions.setMemoryEnabled(memory.id, it) },
                        onExpirationChange = { actions.setMemoryExpiry(memory.id, it) },
                        onEdit = { actions.openMemoryEdit(memory.id) },
                        onDelete = { actions.requestMemoryDelete(memory.id) },
                        onOpenConversation = { actions.openMemorySourceConversation(memory.id) },
                        onOpenRun = { actions.openMemorySourceRun(memory.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryManagementHeader(
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
        }
        Text(
            text = "长期记忆",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.size(30.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "刷新长期记忆", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MemoryFilterBar(
    selected: AgentMemoryFilter,
    onSelected: (AgentMemoryFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AgentMemoryFilter.entries.forEach { filter ->
            val active = filter == selected
            Surface(
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .clickable { onSelected(filter) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(filter.toUiLabel(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DeletedMemoryUndo(
    memory: AgentMemoryRecord,
    onUndo: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已删除：${memory.content}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, modifier = Modifier.height(30.dp)) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("撤销", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MemoryCandidateCard(
    item: MemoryManagementCandidateUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val candidate = item.record
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (item.conflict) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(candidate.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    text = item.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.conflict) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    candidate.createdAt.toFullTimeLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = candidate.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.conflict) {
                Text(
                    "关联旧记忆：${candidate.relatedMemoryId.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = !item.mutating,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(item.acceptLabel, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onReject,
                    enabled = !item.mutating,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("忽略", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MemoryItemCard(
    memory: AgentMemoryRecord,
    selected: Boolean,
    mutating: Boolean,
    onSelect: () -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onExpirationChange: (AgentMemoryExpiryOption) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenRun: () -> Unit,
) {
    val expired = AgentMemoryDecayPolicy.isExpired(memory, System.currentTimeMillis())
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory-management-item-${memory.id}")
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            )
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = memory.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onPinnedChange(!memory.pinned) },
                    enabled = !mutating,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (memory.pinned) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (memory.pinned) "取消置顶" else "置顶",
                        modifier = Modifier.size(17.dp),
                        tint = if (memory.pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = memory.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !mutating,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
                IconButton(onClick = onEdit, enabled = !mutating, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑记忆", modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onDelete, enabled = !mutating, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除记忆", modifier = Modifier.size(17.dp))
                }
            }

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodySmall,
                color = if (memory.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (selected) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (memory.tags.isNotBlank()) {
                Text(
                    text = "标签：${memory.tags}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        !memory.enabled -> "已禁用"
                        expired -> "已过期，不参与检索"
                        else -> "参与检索"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (memory.enabled && !expired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "置信度 ${(memory.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = memory.updatedAt.toFullTimeLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "过期策略：" + when {
                    memory.expiresAt == null -> "永久保留"
                    expired -> "已过期（${memory.expiresAt.toFullTimeLabel()}）"
                    else -> memory.expiresAt.toFullTimeLabel()
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selected) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("过期策略", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AgentMemoryExpiryOption.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { onExpirationChange(option) },
                            enabled = !mutating,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(option.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                memory.sourceSummary.takeIf { it.isNotBlank() }?.let {
                    MemoryAuditField(label = "来源摘要", value = it)
                }
                memory.sourceConversationId?.let {
                    MemoryAuditField(label = "会话", value = it)
                }
                memory.sourceRunId?.let {
                    MemoryAuditField(label = "Run", value = it)
                }
                MemoryAuditField(label = "创建时间", value = memory.createdAt.toFullTimeLabel())
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    memory.sourceConversationId?.let {
                        OutlinedButton(
                            onClick = onOpenConversation,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("来源会话", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    memory.sourceRunId?.let {
                        OutlinedButton(
                            onClick = onOpenRun,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("来源 Run", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryAuditField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MemoryManagementSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(7.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            content()
        }
    }
}

private fun AgentMemoryFilter.toUiLabel(): String = when (this) {
    AgentMemoryFilter.ALL -> "全部"
    AgentMemoryFilter.ENABLED -> "已启用"
    AgentMemoryFilter.DISABLED -> "已禁用"
}

private fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(MEMORY_FULL_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val MEMORY_FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
