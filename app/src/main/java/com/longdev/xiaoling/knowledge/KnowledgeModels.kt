package com.longdev.xiaoling.knowledge

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
