package com.longdev.xiaoling.knowledge

class KnowledgeDocumentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class KnowledgeDocumentImportIdempotencyConflictException : IllegalStateException(
    "知识导入工具调用已绑定到其他内容",
)

const val KNOWLEDGE_PREVIEW_CHARACTER_LIMIT = 4_000

data class KnowledgeDocumentRecord(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val normalizedText: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class KnowledgeDocumentSummary(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val chunkCount: Int,
)

data class KnowledgeDocumentDetail(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val previewText: String,
    val previewTruncated: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class KnowledgeDocumentImport(
    val fileName: String,
    val declaredMimeType: String,
    val bytes: ByteArray,
)

data class KnowledgeChunkRecord(
    val id: String,
    val documentId: String,
    val documentRevision: Int,
    val sequence: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

enum class KnowledgeSearchMatchChannel {
    LEXICAL,
    SEMANTIC,
}

data class KnowledgeSearchHit(
    val chunkId: String,
    val documentId: String,
    val documentRevision: Int,
    val documentName: String,
    val sequence: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val matchChannels: Set<KnowledgeSearchMatchChannel> = emptySet(),
)

data class KnowledgeRetrievalRecord(
    val id: String,
    val query: String,
    val chunkIds: List<String>,
    val documentIds: List<String>,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val embeddingProviderId: String? = null,
    val embeddingModel: String? = null,
    val embeddingStatus: KnowledgeEmbeddingStatus = KnowledgeEmbeddingStatus.LEXICAL_ONLY,
    val embeddingTopScore: Double? = null,
    val embeddingSecondScore: Double? = null,
    val embeddingScoreMargin: Double? = null,
    val embeddingCandidateCount: Int? = null,
    val embeddingScoreMean: Double? = null,
    val embeddingScoreStandardDeviation: Double? = null,
    val embeddingTopScoreZScore: Double? = null,
    val createdAt: Long,
)

enum class KnowledgeEmbeddingStatus {
    LEXICAL_ONLY,
    USED,
    NO_INDEX,
    PROVIDER_UNAVAILABLE,
    DIMENSION_MISMATCH,
}

enum class KnowledgeEmbeddingRebuildStatus {
    INDEXED,
    NO_PROVIDER,
    PROVIDER_UNAVAILABLE,
    DOCUMENT_DISABLED,
    STALE_DOCUMENT,
    INVALID_RESPONSE,
}

data class KnowledgeEmbeddingRebuildResult(
    val documentId: String,
    val documentRevision: Int,
    val status: KnowledgeEmbeddingRebuildStatus,
    val providerId: String? = null,
    val model: String? = null,
    val indexedChunkCount: Int = 0,
)

data class KnowledgeEmbeddingIndexSummary(
    val providerId: String,
    val model: String,
    val documentRevision: Int,
    val dimensions: Int,
    val chunkCount: Int,
    val updatedAt: Long,
)

data class KnowledgeSearchResult(
    val hits: List<KnowledgeSearchHit>,
    val retrieval: KnowledgeRetrievalRecord,
)

data class KnowledgeReference(
    val retrievalId: String,
    val documentId: String,
    val documentName: String,
    val documentRevision: Int,
    val chunkId: String,
    val chunkSequence: Int,
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(retrievalId.isNotBlank()) { "知识检索引用缺少 retrieval ID" }
        require(documentId.isNotBlank()) { "知识检索引用缺少 document ID" }
        require(documentName.isNotBlank()) { "知识检索引用缺少文档名称" }
        require(documentRevision > 0) { "知识检索引用的文档 revision 必须大于 0" }
        require(chunkId.isNotBlank()) { "知识检索引用缺少 chunk ID" }
        require(chunkSequence >= 0) { "知识检索引用的 chunk sequence 不能小于 0" }
        require(startOffset >= 0 && endOffset > startOffset) { "知识检索引用的 offset 范围无效" }
    }
}

data class KnowledgeDocumentNavigationTarget(
    val documentId: String,
    val reference: KnowledgeReference? = null,
) {
    init {
        require(documentId.isNotBlank()) { "知识文档导航缺少 document ID" }
        require(reference == null || reference.documentId == documentId) { "知识引用与导航文档不一致" }
    }
}

enum class KnowledgeReferenceAvailability {
    CURRENT,
    HISTORICAL,
    UNAVAILABLE,
}

enum class KnowledgeReferenceIssue {
    NONE,
    DOCUMENT_REPLACED,
    DOCUMENT_DISABLED,
    DOCUMENT_DELETED,
    EVIDENCE_CHANGED,
}

data class KnowledgeReferenceStatus(
    val reference: KnowledgeReference,
    val availability: KnowledgeReferenceAvailability,
    val issue: KnowledgeReferenceIssue,
    val currentDocumentName: String?,
    val currentDocumentRevision: Int?,
    val currentDocumentEnabled: Boolean?,
) {
    val canOpenDocument: Boolean
        get() = currentDocumentRevision != null
}

data class KnowledgeReferenceLocation(
    val status: KnowledgeReferenceStatus,
    val chunk: KnowledgeChunkRecord?,
) {
    val locatedCurrentEvidence: Boolean
        get() = status.availability == KnowledgeReferenceAvailability.CURRENT && chunk != null
}

fun KnowledgeReference.assessAgainst(
    document: KnowledgeDocumentSummary?,
    chunk: KnowledgeChunkRecord?,
): KnowledgeReferenceStatus {
    if (document == null) {
        return KnowledgeReferenceStatus(
            reference = this,
            availability = KnowledgeReferenceAvailability.UNAVAILABLE,
            issue = KnowledgeReferenceIssue.DOCUMENT_DELETED,
            currentDocumentName = null,
            currentDocumentRevision = null,
            currentDocumentEnabled = null,
        )
    }
    val status = when {
        // long: 文档停用代表当前知识库明确禁止继续使用；即使 revision 已更新，也应优先显示不可用，避免“历史版本”弱化停用边界。
        !document.enabled ->
            KnowledgeReferenceAvailability.UNAVAILABLE to KnowledgeReferenceIssue.DOCUMENT_DISABLED
        // long: revision 只会递增；当前版本更新时历史引用仍可审计，但绝不能继续显示为当前知识证据。
        document.id == documentId && document.revision > documentRevision ->
            KnowledgeReferenceAvailability.HISTORICAL to KnowledgeReferenceIssue.DOCUMENT_REPLACED
        document.id != documentId || document.revision != documentRevision || document.displayName != documentName ->
            KnowledgeReferenceAvailability.UNAVAILABLE to KnowledgeReferenceIssue.EVIDENCE_CHANGED
        chunk == null ||
            chunk.id != chunkId ||
            chunk.documentId != documentId ||
            chunk.documentRevision != documentRevision ||
            chunk.sequence != chunkSequence ||
            chunk.startOffset != startOffset ||
            chunk.endOffset != endOffset ->
            KnowledgeReferenceAvailability.UNAVAILABLE to KnowledgeReferenceIssue.EVIDENCE_CHANGED
        else -> KnowledgeReferenceAvailability.CURRENT to KnowledgeReferenceIssue.NONE
    }
    return KnowledgeReferenceStatus(
        reference = this,
        availability = status.first,
        issue = status.second,
        currentDocumentName = document.displayName,
        currentDocumentRevision = document.revision,
        currentDocumentEnabled = document.enabled,
    )
}

interface KnowledgeDocumentStore {
    suspend fun importUtf8Document(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord

    suspend fun importUtf8DocumentOnce(
        idempotencyKey: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord

    suspend fun replaceUtf8Document(
        documentId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord

    suspend fun getDocument(documentId: String): KnowledgeDocumentRecord?
    suspend fun listDocuments(): List<KnowledgeDocumentSummary>
    suspend fun getDocumentDetail(documentId: String): KnowledgeDocumentDetail?
    suspend fun getChunks(documentId: String): List<KnowledgeChunkRecord>

    suspend fun rebuildEmbeddings(documentId: String): KnowledgeEmbeddingRebuildResult {
        val document = getDocument(documentId) ?: throw IllegalArgumentException("知识文档不存在")
        return KnowledgeEmbeddingRebuildResult(
            documentId = document.id,
            documentRevision = document.revision,
            status = KnowledgeEmbeddingRebuildStatus.NO_PROVIDER,
        )
    }

    suspend fun getEmbeddingIndexes(documentId: String): List<KnowledgeEmbeddingIndexSummary> = emptyList()

    suspend fun inspectReferences(references: List<KnowledgeReference>): List<KnowledgeReferenceStatus> {
        val distinctReferences = references.distinct()
        if (distinctReferences.isEmpty()) return emptyList()
        val documentIds = distinctReferences.map { it.documentId }.toSet()
        val documents = listDocuments().filter { it.id in documentIds }.associateBy { it.id }
        val chunks = documentIds.flatMap { getChunks(it) }.associateBy { it.id }
        return distinctReferences.map { reference ->
            reference.assessAgainst(
                document = documents[reference.documentId],
                chunk = chunks[reference.chunkId],
            )
        }
    }

    suspend fun locateReference(reference: KnowledgeReference): KnowledgeReferenceLocation {
        val status = inspectReferences(listOf(reference)).single()
        val chunk = if (status.availability == KnowledgeReferenceAvailability.CURRENT) {
            getChunks(reference.documentId).singleOrNull { candidate -> candidate.id == reference.chunkId }
        } else {
            null
        }
        // long: 定位只接受状态核验过的同一当前 chunk；历史 revision、边界漂移或 chunk 缺失都不能猜测相邻原文。
        return KnowledgeReferenceLocation(
            status = status,
            chunk = chunk?.takeIf { candidate ->
                candidate.documentRevision == reference.documentRevision &&
                    candidate.sequence == reference.chunkSequence &&
                    candidate.startOffset == reference.startOffset &&
                    candidate.endOffset == reference.endOffset
            },
        )
    }

    /**
     * long:
     * 只保留仍然指向当前启用文档 revision 和 chunk 边界的引用；历史审计记录本身不在这里被改写。
     */
    suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference>

    suspend fun search(
        query: String,
        limit: Int,
        sourceConversationId: String? = null,
        sourceRunId: String? = null,
    ): KnowledgeSearchResult

    suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalRecord>
    suspend fun setEnabled(documentId: String, enabled: Boolean): KnowledgeDocumentRecord?
    suspend fun delete(documentId: String): Boolean
}
