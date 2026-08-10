package com.longdev.xiaoling.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentImport
import com.longdev.xiaoling.knowledge.KnowledgeDocumentNavigationTarget
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingIndexSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingRebuildResult
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingRebuildStatus
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceIssue
import com.longdev.xiaoling.knowledge.KnowledgeReferenceLocation
import com.longdev.xiaoling.storage.KnowledgeDocumentReader
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import com.longdev.xiaoling.storage.SelectedProviderKnowledgeEmbeddingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class KnowledgeManagementNotice(
    val success: Boolean,
    val title: String,
    val message: String,
)

data class KnowledgeManagementUiState(
    val loadingDocuments: Boolean = false,
    val documents: List<KnowledgeDocumentSummary> = emptyList(),
    val selectedDocumentId: String? = null,
    val selectedDocument: KnowledgeDocumentDetail? = null,
    val selectedEmbeddingIndexes: List<KnowledgeEmbeddingIndexSummary> = emptyList(),
    val loadingDetail: Boolean = false,
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchHits: List<KnowledgeSearchHit> = emptyList(),
    val lastRetrieval: KnowledgeRetrievalRecord? = null,
    val mutatingDocumentIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val error: String? = null,
    val notice: KnowledgeManagementNotice? = null,
    val referenceLocation: KnowledgeReferenceLocationUiState? = null,
)

data class KnowledgeReferenceLocationUiState(
    val title: String,
    val detail: String,
    val sourceText: String? = null,
    val success: Boolean,
)

class KnowledgeManagementViewModel internal constructor(
    application: Application,
    private val store: KnowledgeDocumentStore,
    private val readImport: (Uri) -> KnowledgeDocumentImport,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        store = RoomKnowledgeDocumentStore(
            context = application,
            embeddingProvider = SelectedProviderKnowledgeEmbeddingProvider(application),
        ),
        readImport = KnowledgeDocumentReader(application)::read,
    )

    var uiState by mutableStateOf(KnowledgeManagementUiState())
        private set

    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null
    private var mutationJob: Job? = null

    init {
        refresh()
    }

    fun refresh(preferredDocumentId: String? = uiState.selectedDocumentId) {
        if (mutationJob?.isActive == true) return
        loadJob?.cancel()
        detailJob?.cancel()
        uiState = uiState.copy(loadingDocuments = true, referenceLocation = null, error = null)
        loadJob = viewModelScope.launch {
            try {
                loadSnapshot(preferredDocumentId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    loadingDocuments = false,
                    loadingDetail = false,
                    error = error.message ?: "无法读取知识库",
                )
            }
        }
    }

    fun openNavigationTarget(target: KnowledgeDocumentNavigationTarget) {
        if (mutationJob?.isActive == true) return
        val reference = target.reference
        if (reference == null) {
            refresh(target.documentId)
            return
        }
        loadJob?.cancel()
        detailJob?.cancel()
        uiState = uiState.copy(
            loadingDocuments = true,
            loadingDetail = true,
            selectedDocumentId = target.documentId,
            selectedDocument = null,
            selectedEmbeddingIndexes = emptyList(),
            referenceLocation = null,
            error = null,
        )
        loadJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    KnowledgeNavigationSnapshot(
                        documents = store.listDocuments(),
                        document = store.getDocumentDetail(target.documentId),
                        embeddingIndexes = store.getEmbeddingIndexes(target.documentId),
                        location = store.locateReference(reference),
                    )
                }
                if (uiState.selectedDocumentId != target.documentId) return@launch
                uiState = uiState.copy(
                    loadingDocuments = false,
                    documents = snapshot.documents,
                    selectedDocument = snapshot.document,
                    selectedEmbeddingIndexes = snapshot.embeddingIndexes,
                    loadingDetail = false,
                    referenceLocation = snapshot.location.toUiState(),
                    error = if (snapshot.document == null) "知识文档已不存在" else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    loadingDocuments = false,
                    loadingDetail = false,
                    referenceLocation = KnowledgeReferenceLocationUiState(
                        title = "无法定位引用原文",
                        detail = error.message ?: "知识引用读取失败",
                        success = false,
                    ),
                )
            }
        }
    }

    fun selectDocument(documentId: String) {
        if (mutationJob?.isActive == true) return
        if (documentId == uiState.selectedDocumentId && uiState.selectedDocument != null) return
        loadJob?.cancel()
        detailJob?.cancel()
        uiState = uiState.copy(
            loadingDocuments = false,
            selectedDocumentId = documentId,
            selectedDocument = null,
            selectedEmbeddingIndexes = emptyList(),
            loadingDetail = true,
            referenceLocation = null,
            error = null,
        )
        detailJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    KnowledgeDetailSnapshot(
                        document = store.getDocumentDetail(documentId),
                        embeddingIndexes = store.getEmbeddingIndexes(documentId),
                    )
                }
                if (uiState.selectedDocumentId != documentId) return@launch
                uiState = uiState.copy(
                    selectedDocument = detail.document,
                    selectedEmbeddingIndexes = detail.embeddingIndexes,
                    loadingDetail = false,
                    error = if (detail.document == null) "知识文档已不存在" else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (uiState.selectedDocumentId != documentId) return@launch
                uiState = uiState.copy(
                    loadingDetail = false,
                    error = error.message ?: "无法读取知识文档详情",
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val searchWasActive = searchJob?.isActive == true
        if (searchWasActive) searchJob?.cancel()
        uiState = uiState.copy(
            searchQuery = query,
            searching = if (searchWasActive) false else uiState.searching,
            searchHits = if (searchWasActive) emptyList() else uiState.searchHits,
            lastRetrieval = if (searchWasActive) null else uiState.lastRetrieval,
            error = null,
        )
    }

    fun search() {
        if (mutationJob?.isActive == true) return
        val query = uiState.searchQuery.trim()
        if (query.isBlank()) {
            uiState = uiState.copy(searchHits = emptyList(), lastRetrieval = null, error = "请输入检索词")
            return
        }
        searchJob?.cancel()
        uiState = uiState.copy(searching = true, error = null)
        searchJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.search(query, limit = 10) }
                uiState = uiState.copy(
                    searching = false,
                    searchHits = result.hits,
                    lastRetrieval = result.retrieval,
                    error = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    searching = false,
                    searchHits = emptyList(),
                    lastRetrieval = null,
                    error = error.message ?: "知识检索失败",
                )
            }
        }
    }

    fun importDocument(uri: Uri) {
        if (uiState.importing || mutationJob?.isActive == true) return
        prepareForMutation(invalidateReferences = false)
        uiState = uiState.copy(importing = true, error = null, notice = null)
        mutationJob = viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val imported = readImport(uri)
                    store.importUtf8Document(
                        displayName = imported.fileName,
                        mimeType = imported.declaredMimeType,
                        bytes = imported.bytes,
                    )
                }
                uiState = uiState.copy(
                    notice = KnowledgeManagementNotice(true, "导入成功", document.displayName),
                )
                reloadAfterMutation(document.id, operationCommitted = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    error = error.message ?: "知识文档导入失败",
                    notice = KnowledgeManagementNotice(false, "导入失败", error.message ?: "无法导入知识文档"),
                )
            } finally {
                uiState = uiState.copy(importing = false)
            }
        }
    }

    fun replaceDocument(documentId: String, uri: Uri) {
        mutateDocument(
            documentId = documentId,
            fallbackError = "知识文档替换失败",
            failureNotice = { error ->
                KnowledgeManagementNotice(false, "替换失败", error.message ?: "无法替换知识文档")
            },
            operation = {
                val imported = readImport(uri)
                store.replaceUtf8Document(
                    documentId = documentId,
                    displayName = imported.fileName,
                    mimeType = imported.declaredMimeType,
                    bytes = imported.bytes,
                )
            },
        ) { document ->
            uiState = uiState.copy(
                notice = KnowledgeManagementNotice(true, "替换成功", "已切换到 revision ${document.revision}"),
            )
            reloadAfterMutation(document.id, operationCommitted = true)
        }
    }

    fun setEnabled(documentId: String, enabled: Boolean) {
        mutateDocument(
            documentId = documentId,
            fallbackError = "知识文档状态更新失败",
            operation = {
                store.setEnabled(documentId, enabled)
                    ?: error("知识文档已不存在")
            },
        ) { document ->
            uiState = uiState.copy(
                notice = KnowledgeManagementNotice(
                    true,
                    if (enabled) "文档已启用" else "文档已停用",
                    document.displayName,
                ),
            )
            reloadAfterMutation(documentId, operationCommitted = true)
        }
    }

    fun rebuildEmbeddings(documentId: String) {
        mutateDocument(
            documentId = documentId,
            fallbackError = "Embedding 索引重建失败",
            failureNotice = { error ->
                KnowledgeManagementNotice(false, "索引重建失败", error.message ?: "无法重建 Embedding 索引")
            },
            operation = { store.rebuildEmbeddings(documentId) },
        ) { result ->
            uiState = uiState.copy(notice = result.toNotice())
            reloadAfterMutation(
                preferredDocumentId = documentId,
                operationCommitted = result.status == KnowledgeEmbeddingRebuildStatus.INDEXED,
            )
        }
    }

    fun deleteDocument(documentId: String) {
        mutateDocument(
            documentId = documentId,
            fallbackError = "知识文档删除失败",
            operation = { store.delete(documentId) },
        ) { deleted ->
            uiState = uiState.copy(
                notice = KnowledgeManagementNotice(
                    deleted,
                    if (deleted) "已删除" else "文档未删除",
                    if (deleted) "全文、chunks 和检索索引已清理" else "知识文档已不存在",
                ),
            )
            reloadAfterMutation(null, operationCommitted = deleted)
        }
    }

    fun clearNotice() {
        uiState = uiState.copy(notice = null)
    }

    private fun prepareForMutation(invalidateReferences: Boolean) {
        loadJob?.cancel()
        detailJob?.cancel()
        uiState = uiState.copy(
            loadingDocuments = false,
            loadingDetail = false,
            selectedDocument = if (invalidateReferences) null else uiState.selectedDocument,
            selectedEmbeddingIndexes = if (invalidateReferences) emptyList() else uiState.selectedEmbeddingIndexes,
            searching = if (invalidateReferences) false else uiState.searching,
            searchHits = if (invalidateReferences) emptyList() else uiState.searchHits,
            lastRetrieval = if (invalidateReferences) null else uiState.lastRetrieval,
            referenceLocation = if (invalidateReferences) null else uiState.referenceLocation,
        )
        if (invalidateReferences) {
            // long: 替换、停用和删除都会使当前 chunk 引用失效；取消在途检索，避免旧请求稍后覆盖新 revision 或停用状态。
            searchJob?.cancel()
        }
    }

    private fun <T> mutateDocument(
        documentId: String,
        fallbackError: String,
        failureNotice: ((Exception) -> KnowledgeManagementNotice?)? = null,
        operation: suspend () -> T,
        onCommitted: suspend (T) -> Unit,
    ) {
        if (documentId in uiState.mutatingDocumentIds || mutationJob?.isActive == true) return
        prepareForMutation(invalidateReferences = true)
        uiState = uiState.copy(
            mutatingDocumentIds = uiState.mutatingDocumentIds + documentId,
            error = null,
            notice = null,
        )
        mutationJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                onCommitted(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uiState = uiState.copy(
                    error = error.message ?: fallbackError,
                    notice = failureNotice?.invoke(error),
                )
            } finally {
                // long: 文档变更直到提交后的快照重载结束都保持 busy，避免控件提前可点却被串行门禁静默拒绝。
                uiState = uiState.copy(
                    mutatingDocumentIds = uiState.mutatingDocumentIds - documentId,
                )
            }
        }
    }

    private suspend fun reloadAfterMutation(preferredDocumentId: String?, operationCommitted: Boolean) {
        // long: 数据库提交和界面快照刷新是两个事实；刷新失败只能提示重新加载，不能把已经完成的启停、替换或删除误报为提交失败。
        try {
            loadSnapshot(preferredDocumentId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val prefix = if (operationCommitted) "操作已完成，但刷新知识库失败" else "无法刷新知识库"
            uiState = uiState.copy(
                loadingDocuments = false,
                loadingDetail = false,
                error = "$prefix：${error.message ?: "未知错误"}",
            )
        }
    }

    private suspend fun loadSnapshot(preferredDocumentId: String?) {
        val snapshot = withContext(Dispatchers.IO) {
            val documents = store.listDocuments()
            val selectedId: String? = preferredDocumentId?.takeIf { id -> documents.any { it.id == id } }
                ?: documents.firstOrNull()?.id
            val selected = if (selectedId == null) null else store.getDocumentDetail(selectedId)
            val embeddingIndexes = if (selectedId == null) emptyList() else store.getEmbeddingIndexes(selectedId)
            KnowledgeSnapshot(documents, selectedId, selected, embeddingIndexes)
        }
        uiState = uiState.copy(
            loadingDocuments = false,
            documents = snapshot.documents,
            selectedDocumentId = snapshot.selectedDocumentId,
            selectedDocument = snapshot.selectedDocument,
            selectedEmbeddingIndexes = snapshot.embeddingIndexes,
            loadingDetail = false,
            referenceLocation = null,
        )
    }

    private data class KnowledgeSnapshot(
        val documents: List<KnowledgeDocumentSummary>,
        val selectedDocumentId: String?,
        val selectedDocument: KnowledgeDocumentDetail?,
        val embeddingIndexes: List<KnowledgeEmbeddingIndexSummary>,
    )

    private data class KnowledgeDetailSnapshot(
        val document: KnowledgeDocumentDetail?,
        val embeddingIndexes: List<KnowledgeEmbeddingIndexSummary>,
    )

    private data class KnowledgeNavigationSnapshot(
        val documents: List<KnowledgeDocumentSummary>,
        val document: KnowledgeDocumentDetail?,
        val embeddingIndexes: List<KnowledgeEmbeddingIndexSummary>,
        val location: KnowledgeReferenceLocation,
    )

    private fun KnowledgeReferenceLocation.toUiState(): KnowledgeReferenceLocationUiState {
        if (locatedCurrentEvidence) {
            val currentChunk = requireNotNull(chunk)
            return KnowledgeReferenceLocationUiState(
                title = "当前引用原文",
                detail = "revision ${currentChunk.documentRevision} · chunk ${currentChunk.sequence} · offset [${currentChunk.startOffset}, ${currentChunk.endOffset})",
                sourceText = currentChunk.text,
                success = true,
            )
        }
        val detail = when (status.availability) {
            KnowledgeReferenceAvailability.HISTORICAL ->
                "文档已更新到 revision ${status.currentDocumentRevision ?: "?"}，历史引用不能冒充当前原文"
            KnowledgeReferenceAvailability.UNAVAILABLE -> when (status.issue) {
                KnowledgeReferenceIssue.DOCUMENT_DISABLED -> "文档已停用，当前不提供原文定位"
                KnowledgeReferenceIssue.DOCUMENT_DELETED -> "文档已删除，当前原文不可用"
                else -> "引用 chunk、revision 或 offset 已变化，拒绝猜测当前位置"
            }
            KnowledgeReferenceAvailability.CURRENT -> "当前 chunk 无法按完整引用身份回读"
        }
        return KnowledgeReferenceLocationUiState(
            title = "引用原文不可定位",
            detail = detail,
            success = false,
        )
    }

    private fun KnowledgeEmbeddingRebuildResult.toNotice(): KnowledgeManagementNotice {
        return when (status) {
            KnowledgeEmbeddingRebuildStatus.INDEXED -> KnowledgeManagementNotice(
                success = true,
                title = "索引重建完成",
                message = "$providerId · $model · $indexedChunkCount 个分块",
            )
            KnowledgeEmbeddingRebuildStatus.NO_PROVIDER -> KnowledgeManagementNotice(
                false,
                "索引未重建",
                "当前没有可用的 Embedding Provider",
            )
            KnowledgeEmbeddingRebuildStatus.PROVIDER_UNAVAILABLE -> KnowledgeManagementNotice(
                false,
                "索引未重建",
                "Embedding Provider 不可用，已有索引保持不变",
            )
            KnowledgeEmbeddingRebuildStatus.DOCUMENT_DISABLED -> KnowledgeManagementNotice(
                false,
                "索引未重建",
                "文档已停用，请先启用后重试",
            )
            KnowledgeEmbeddingRebuildStatus.STALE_DOCUMENT -> KnowledgeManagementNotice(
                false,
                "索引未重建",
                "文档版本已变化，请基于最新版本重试",
            )
            KnowledgeEmbeddingRebuildStatus.INVALID_RESPONSE -> KnowledgeManagementNotice(
                false,
                "索引未重建",
                "Embedding 响应与文档分块不匹配，已有索引保持不变",
            )
        }
    }
}
