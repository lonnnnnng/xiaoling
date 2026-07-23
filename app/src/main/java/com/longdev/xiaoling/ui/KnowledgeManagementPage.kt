package com.longdev.xiaoling.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingIndexSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import java.util.Locale

@Composable
internal fun KnowledgeManagementPage(
    onBack: () -> Unit,
    preferredDocumentId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: KnowledgeManagementViewModel = viewModel(),
) {
    val state = viewModel.uiState
    var replacingDocumentId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<KnowledgeDocumentDetail?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val documentId = replacingDocumentId
        replacingDocumentId = null
        if (uri != null) {
            if (documentId == null) viewModel.importDocument(uri) else viewModel.replaceDocument(documentId, uri)
        }
    }

    LaunchedEffect(preferredDocumentId) {
        preferredDocumentId?.let(viewModel::refresh)
    }

    KnowledgeManagementContent(
        state = state,
        onBack = onBack,
        onImport = {
            replacingDocumentId = null
            documentLauncher.launch(KNOWLEDGE_PICKER_MIME_TYPES)
        },
        onRefresh = viewModel::refresh,
        onSearchQueryChanged = viewModel::updateSearchQuery,
        onSearch = viewModel::search,
        onSelectDocument = viewModel::selectDocument,
        onSetEnabled = viewModel::setEnabled,
        onRebuildEmbeddings = viewModel::rebuildEmbeddings,
        onReplace = { documentId ->
            replacingDocumentId = documentId
            documentLauncher.launch(KNOWLEDGE_PICKER_MIME_TYPES)
        },
        onDelete = { documentId ->
            pendingDelete = state.selectedDocument?.takeIf { it.id == documentId }
        },
        modifier = modifier,
    )

    pendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除知识文档") },
            text = { Text("将删除 ${document.displayName} 的全文、全部 chunks 和检索索引。历史检索审计仍保留原引用。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteDocument(document.id)
                    },
                    enabled = document.id !in state.mutatingDocumentIds,
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun KnowledgeManagementContent(
    state: KnowledgeManagementUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectDocument: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRebuildEmbeddings: (String) -> Unit,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mutationInProgress = state.importing || state.mutatingDocumentIds.isNotEmpty()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(17.dp))
                }
                Text("知识库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.loadingDocuments && !mutationInProgress,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新知识库", modifier = Modifier.size(17.dp))
                }
                Button(onClick = onImport, enabled = !mutationInProgress) {
                    if (state.importing) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(if (state.importing) "导入中" else "导入")
                }
            }
        }

        state.error?.let { error ->
            item { KnowledgeStatusBand(error, success = false) }
        }
        state.notice?.let { notice ->
            item { KnowledgeStatusBand("${notice.title}：${notice.message}", notice.success) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    modifier = Modifier.weight(1f),
                    label = { Text("检索预览") },
                    placeholder = { Text("输入关键词") },
                    singleLine = true,
                )
                IconButton(
                    onClick = onSearch,
                    enabled = !state.searching && !mutationInProgress,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (state.searching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "执行知识检索")
                    }
                }
            }
        }

        if (state.lastRetrieval != null || state.searchHits.isNotEmpty()) {
            item {
                Text(
                    text = "命中 ${state.searchHits.size} 个分块",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.lastRetrieval?.let { retrieval ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "审计 ${retrieval.id}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "query：${retrieval.query} · ${retrieval.chunkIds.size} chunks · ${retrieval.documentIds.size} documents",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = retrieval.embeddingDiagnosticsText(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            retrieval.embeddingCalibrationText()?.let { calibrationText ->
                                Text(
                                    text = calibrationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            items(state.searchHits, key = { "search-${it.chunkId}" }) { hit ->
                KnowledgeSearchHitCard(hit)
            }
        }

        state.selectedDocument?.let { document ->
            item {
                KnowledgeDocumentDetailCard(
                    document = document,
                    summary = state.documents.firstOrNull { it.id == document.id },
                    embeddingIndexes = state.selectedEmbeddingIndexes,
                    mutating = document.id in state.mutatingDocumentIds,
                    onSetEnabled = { enabled -> onSetEnabled(document.id, enabled) },
                    onRebuildEmbeddings = { onRebuildEmbeddings(document.id) },
                    onReplace = { onReplace(document.id) },
                    onDelete = { onDelete(document.id) },
                )
            }
        }

        item {
            HorizontalDivider()
            Text(
                text = "文档 ${state.documents.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.loadingDocuments && state.documents.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
        } else if (state.documents.isEmpty()) {
            item {
                Text(
                    text = "还没有知识文档",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(state.documents, key = KnowledgeDocumentSummary::id) { document ->
                KnowledgeDocumentSummaryCard(
                    document = document,
                    selected = document.id == state.selectedDocumentId,
                    enabled = !mutationInProgress,
                    onClick = { onSelectDocument(document.id) },
                )
            }
        }
    }
}

@Composable
private fun KnowledgeStatusBand(text: String, success: Boolean) {
    Surface(
        color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun KnowledgeDocumentDetailCard(
    document: KnowledgeDocumentDetail,
    summary: KnowledgeDocumentSummary?,
    embeddingIndexes: List<KnowledgeEmbeddingIndexSummary>,
    mutating: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onRebuildEmbeddings: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("文档详情 · ${document.displayName}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "revision ${document.revision} · parser ${document.parserVersion} · ${summary?.chunkCount ?: 0} 个分块",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = document.enabled,
                    onCheckedChange = onSetEnabled,
                    enabled = !mutating,
                )
            }
            Text(
                "${document.mimeType} · ${document.byteSize.knowledgeSizeLabel()} · ${document.characterCount} 字符",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "SHA-256 ${document.contentHash}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = document.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (document.previewTruncated) {
                Text(
                    text = "仅显示前 4,000 个字符，检索仍使用完整正文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (embeddingIndexes.isEmpty()) {
                Text(
                    text = "Embedding：尚未建立，检索将使用词法兜底",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Embedding 索引",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                embeddingIndexes.forEach { index ->
                    Text(
                        text = "${index.providerId} · ${index.model} · ${index.dimensions} 维 · ${index.chunkCount} 个分块",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onRebuildEmbeddings,
                    enabled = document.enabled && !mutating,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "重建 Embedding 索引", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onReplace, enabled = !mutating, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "替换知识文档", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, enabled = !mutating, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除知识文档", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun KnowledgeDocumentSummaryCard(
    document: KnowledgeDocumentSummary,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(document.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "revision ${document.revision} · ${document.chunkCount} 个分块 · ${document.byteSize.knowledgeSizeLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (document.enabled) "启用" else "停用",
                style = MaterialTheme.typography.labelSmall,
                color = if (document.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KnowledgeSearchHitCard(hit: KnowledgeSearchHit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${hit.documentName} · revision ${hit.documentRevision} · chunk ${hit.sequence + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "offset ${hit.startOffset}..${hit.endOffset}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hit.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Long.knowledgeSizeLabel(): String {
    return when {
        this >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024.0))
        this >= 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
        else -> "$this B"
    }
}

private fun KnowledgeRetrievalRecord.embeddingDiagnosticsText(): String {
    val statusLabel = when (embeddingStatus) {
        KnowledgeEmbeddingStatus.LEXICAL_ONLY -> "仅词法"
        KnowledgeEmbeddingStatus.USED -> "语义融合"
        KnowledgeEmbeddingStatus.NO_INDEX -> "无可用索引，词法兜底"
        KnowledgeEmbeddingStatus.PROVIDER_UNAVAILABLE -> "Provider 不可用，词法兜底"
        KnowledgeEmbeddingStatus.DIMENSION_MISMATCH -> "维度不匹配，词法兜底"
    }
    val identity = listOfNotNull(embeddingProviderId, embeddingModel)
        .filter(String::isNotBlank)
        .joinToString(" / ")
    return "Embedding：$statusLabel" + identity.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
}

private fun KnowledgeRetrievalRecord.embeddingCalibrationText(): String? {
    val candidateCount = embeddingCandidateCount ?: return null
    val observations = buildList {
        add("$candidateCount 个语义候选")
        embeddingTopScore?.let { add("top1 ${it.calibrationScoreText()}") }
        embeddingSecondScore?.let { add("top2 ${it.calibrationScoreText()}") }
        embeddingScoreMargin?.let { add("margin ${it.calibrationScoreText()}") }
    }
    // long: 这行只呈现按当前 Provider/模型采集的 shadow 数据；文案不使用“通过”或“拒绝”，避免把未校准分数误解为生产门禁。
    return "校准观测：${observations.joinToString(" · ")}"
}

private fun Double.calibrationScoreText(): String = String.format(Locale.US, "%.4f", this)

private val KNOWLEDGE_PICKER_MIME_TYPES = arrayOf(
    "text/plain",
    "text/markdown",
    "application/json",
    "text/csv",
    "application/octet-stream",
)
