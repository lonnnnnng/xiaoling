package com.longdev.xiaoling.knowledge

class KnowledgeDocumentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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

data class KnowledgeSearchHit(
    val chunkId: String,
    val documentId: String,
    val documentRevision: Int,
    val documentName: String,
    val sequence: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

data class KnowledgeRetrievalRecord(
    val id: String,
    val query: String,
    val chunkIds: List<String>,
    val documentIds: List<String>,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val createdAt: Long,
)

data class KnowledgeSearchResult(
    val hits: List<KnowledgeSearchHit>,
    val retrieval: KnowledgeRetrievalRecord,
)

interface KnowledgeDocumentStore {
    suspend fun importUtf8Document(
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
