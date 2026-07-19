package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.longdev.xiaoling.data.KnowledgeChunkEntity
import com.longdev.xiaoling.data.KnowledgeChunkFtsEntity
import com.longdev.xiaoling.data.KnowledgeDocumentEntity
import com.longdev.xiaoling.data.KnowledgeRetrievalEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeChunkRecord
import com.longdev.xiaoling.knowledge.KNOWLEDGE_PREVIEW_CHARACTER_LIMIT
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeSearchResult
import com.longdev.xiaoling.knowledge.KnowledgeTextPolicy
import com.longdev.xiaoling.knowledge.ImportedKnowledgeText
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import org.json.JSONArray
import java.util.UUID

class RoomKnowledgeDocumentStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val clock: () -> Long = System::currentTimeMillis,
) : KnowledgeDocumentStore {
    override suspend fun importUtf8Document(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord {
        val payload = prepareImport(displayName, mimeType, bytes)
        val now = clock()
        val document = KnowledgeDocumentRecord(
            id = "knowledge-${UUID.randomUUID()}",
            displayName = payload.displayName,
            mimeType = payload.mimeType,
            contentHash = payload.text.contentHash,
            revision = 1,
            parserVersion = KnowledgeTextPolicy.PARSER_VERSION,
            byteSize = payload.text.byteSize,
            characterCount = payload.text.characterCount,
            normalizedText = payload.text.normalizedText,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            val chunks = document.buildChunks()
            database.knowledgeDao().insertDocument(document.toEntity())
            database.knowledgeDao().insertChunks(chunks.map { it.toEntity() })
            database.knowledgeDao().insertChunkIndexes(chunks.map { it.toFtsEntity() })
        }
        return document
    }

    override suspend fun replaceUtf8Document(
        documentId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord {
        val payload = prepareImport(displayName, mimeType, bytes)
        return database.withTransaction {
            val dao = database.knowledgeDao()
            val existing = dao.getDocument(documentId)
                ?: throw IllegalArgumentException("知识文档不存在")
            val replaced = existing.toRecord().copy(
                displayName = payload.displayName,
                mimeType = payload.mimeType,
                contentHash = payload.text.contentHash,
                revision = existing.revision + 1,
                parserVersion = KnowledgeTextPolicy.PARSER_VERSION,
                byteSize = payload.text.byteSize,
                characterCount = payload.text.characterCount,
                normalizedText = payload.text.normalizedText,
                updatedAt = clock(),
            )
            val chunks = replaced.buildChunks()
            // long: 替换全文、版本和两套 chunk 索引必须处于同一事务；任何一步失败都会保留旧 revision，避免审计引用指向半更新内容。
            check(dao.updateDocument(replaced.toEntity()) == 1) { "知识文档替换失败" }
            dao.deleteChunkIndexes(documentId)
            dao.deleteChunks(documentId)
            dao.insertChunks(chunks.map { it.toEntity() })
            dao.insertChunkIndexes(chunks.map { it.toFtsEntity() })
            replaced
        }
    }

    override suspend fun getDocument(documentId: String): KnowledgeDocumentRecord? {
        return database.knowledgeDao().getDocument(documentId)?.toRecord()
    }

    override suspend fun listDocuments(): List<KnowledgeDocumentSummary> {
        return database.knowledgeDao().listDocumentSummaries().map { summary ->
            KnowledgeDocumentSummary(
                id = summary.id,
                displayName = summary.displayName,
                mimeType = summary.mimeType,
                contentHash = summary.contentHash,
                revision = summary.revision,
                parserVersion = summary.parserVersion,
                byteSize = summary.byteSize,
                characterCount = summary.characterCount,
                enabled = summary.enabled,
                createdAt = summary.createdAt,
                updatedAt = summary.updatedAt,
                chunkCount = summary.chunkCount,
            )
        }
    }

    override suspend fun getDocumentDetail(documentId: String): KnowledgeDocumentDetail? {
        // long: 管理页只展示有界预览；通过 SQL projection 避免把最大 64 MB 全文复制进 Compose 状态和 UI 文本布局。
        return database.knowledgeDao()
            .getDocumentDetail(documentId, KNOWLEDGE_PREVIEW_CHARACTER_LIMIT)
            ?.let { detail ->
                KnowledgeDocumentDetail(
                    id = detail.id,
                    displayName = detail.displayName,
                    mimeType = detail.mimeType,
                    contentHash = detail.contentHash,
                    revision = detail.revision,
                    parserVersion = detail.parserVersion,
                    byteSize = detail.byteSize,
                    characterCount = detail.characterCount,
                    previewText = detail.previewText.toKnowledgePreview(),
                    previewTruncated = detail.previewTruncated,
                    enabled = detail.enabled,
                    createdAt = detail.createdAt,
                    updatedAt = detail.updatedAt,
                )
            }
    }

    override suspend fun getChunks(documentId: String): List<KnowledgeChunkRecord> {
        return database.knowledgeDao().getChunks(documentId).map { it.toRecord() }
    }

    override suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference> {
        val distinctReferences = references.distinct()
        if (distinctReferences.isEmpty()) return emptyList()
        return database.withTransaction {
            val dao = database.knowledgeDao()
            val documents = dao.getDocuments(distinctReferences.map { it.documentId }.distinct())
                .associateBy { it.id }
            val chunks = dao.getChunksByIds(distinctReferences.map { it.chunkId }.distinct())
                .associateBy { it.id }
            distinctReferences.filter { reference ->
                val document = documents[reference.documentId]
                val chunk = chunks[reference.chunkId]
                document?.enabled == true &&
                    document.revision == reference.documentRevision &&
                    document.displayName == reference.documentName &&
                    chunk?.documentId == reference.documentId &&
                    chunk.documentRevision == reference.documentRevision &&
                    chunk.sequence == reference.chunkSequence &&
                    chunk.startOffset == reference.startOffset &&
                    chunk.endOffset == reference.endOffset
            }
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        sourceConversationId: String?,
        sourceRunId: String?,
    ): KnowledgeSearchResult {
        val canonicalQuery = query.trim()
        require(canonicalQuery.isNotBlank()) { "知识检索词不能为空" }
        val boundedLimit = limit.coerceIn(1, 20)
        return database.withTransaction {
            val dao = database.knowledgeDao()
            val fetchLimit = (boundedLimit * 2).coerceAtMost(40)
            val ftsHits = dao.searchFts(buildKnowledgeFtsQuery(canonicalQuery), fetchLimit)
            val likeHits = dao.searchLike(buildKnowledgeLikeQuery(canonicalQuery, fetchLimit))
            val chunks = (ftsHits + likeHits).distinctBy { it.id }.take(boundedLimit)
            val documents = dao.getDocuments(chunks.map { it.documentId }.distinct()).associateBy { it.id }
            val hits = chunks.mapNotNull { chunk ->
                documents[chunk.documentId]?.let { document -> chunk.toHit(document) }
            }
            val retrieval = KnowledgeRetrievalRecord(
                id = "knowledge-retrieval-${UUID.randomUUID()}",
                query = canonicalQuery,
                chunkIds = hits.map { it.chunkId },
                documentIds = hits.map { it.documentId }.distinct(),
                sourceConversationId = sourceConversationId,
                sourceRunId = sourceRunId,
                createdAt = clock(),
            )
            // long: 空召回同样写入审计，后续才能区分“没有命中”与“根本没有执行检索”。
            dao.insertRetrieval(retrieval.toEntity())
            KnowledgeSearchResult(hits = hits, retrieval = retrieval)
        }
    }

    override suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalRecord> {
        return database.knowledgeDao().recentRetrievals(limit.coerceIn(1, 100)).map { it.toRecord() }
    }

    override suspend fun setEnabled(documentId: String, enabled: Boolean): KnowledgeDocumentRecord? {
        return database.withTransaction {
            val dao = database.knowledgeDao()
            if (dao.setEnabled(documentId, enabled, clock()) != 1) return@withTransaction null
            dao.getDocument(documentId)?.toRecord()
        }
    }

    override suspend fun delete(documentId: String): Boolean {
        return database.withTransaction {
            val dao = database.knowledgeDao()
            if (dao.getDocument(documentId) == null) return@withTransaction false
            // long: 先删除显式维护的 FTS 行和 chunks，再删除文档；删除返回时不能留下仍可被检索的孤立索引。
            dao.deleteChunkIndexes(documentId)
            dao.deleteChunks(documentId)
            dao.deleteDocument(documentId) == 1
        }
    }

    private fun KnowledgeDocumentRecord.buildChunks(): List<KnowledgeChunkRecord> {
        // long: chunk ID 固定携带文档身份和 revision；替换即使正文相同也会生成新引用，历史审计不会误指向新版本内容。
        return KnowledgeTextPolicy.chunk(normalizedText).map { chunk ->
            KnowledgeChunkRecord(
                id = "knowledge-chunk-$id-r$revision-${chunk.sequence}-${contentHash.take(12)}",
                documentId = id,
                documentRevision = revision,
                sequence = chunk.sequence,
                startOffset = chunk.startOffset,
                endOffset = chunk.endOffset,
                text = chunk.text,
            )
        }
    }

    private fun resolveTextMimeType(displayName: String, mimeType: String, bytes: ByteArray): String {
        val resolved = DocumentAttachmentPolicy.resolveMimeType(displayName, mimeType, bytes)
        require(resolved in SUPPORTED_TEXT_MIME_TYPES) { "知识库当前只支持 TXT、Markdown、JSON 和 CSV 文本" }
        return resolved
    }

    private fun prepareImport(displayName: String, mimeType: String, bytes: ByteArray): PreparedKnowledgeImport {
        val canonicalName = displayName.canonicalKnowledgeName()
        return PreparedKnowledgeImport(
            displayName = canonicalName,
            mimeType = resolveTextMimeType(canonicalName, mimeType, bytes),
            text = KnowledgeTextPolicy.decodeUtf8(bytes),
        )
    }

    private fun String.canonicalKnowledgeName(): String {
        val value = replace('\\', '/').substringAfterLast('/').trim().take(120)
        require(value.isNotBlank()) { "知识文档名称不能为空" }
        return value
    }

    private fun String.toKnowledgePreview(): String {
        if (length <= KNOWLEDGE_PREVIEW_CHARACTER_LIMIT) return this
        var endOffset = KNOWLEDGE_PREVIEW_CHARACTER_LIMIT
        // long: SQLite substr 按 Unicode code point 计数，而知识正文 offset 使用 UTF-16；二次收紧预算并回退高代理项，避免预览越界或生成半个 emoji。
        if (this[endOffset - 1].isHighSurrogate() && this[endOffset].isLowSurrogate()) {
            endOffset -= 1
        }
        return substring(0, endOffset)
    }

    private fun KnowledgeDocumentRecord.toEntity() = KnowledgeDocumentEntity(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        contentHash = contentHash,
        revision = revision,
        parserVersion = parserVersion,
        byteSize = byteSize,
        characterCount = characterCount,
        normalizedText = normalizedText,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun KnowledgeDocumentEntity.toRecord() = KnowledgeDocumentRecord(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        contentHash = contentHash,
        revision = revision,
        parserVersion = parserVersion,
        byteSize = byteSize,
        characterCount = characterCount,
        normalizedText = normalizedText,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun KnowledgeChunkRecord.toEntity() = KnowledgeChunkEntity(
        id = id,
        documentId = documentId,
        documentRevision = documentRevision,
        sequence = sequence,
        startOffset = startOffset,
        endOffset = endOffset,
        text = text,
    )

    private fun KnowledgeChunkRecord.toFtsEntity() = KnowledgeChunkFtsEntity(
        chunkId = id,
        documentId = documentId,
        text = text,
    )

    private fun KnowledgeChunkEntity.toRecord() = KnowledgeChunkRecord(
        id = id,
        documentId = documentId,
        documentRevision = documentRevision,
        sequence = sequence,
        startOffset = startOffset,
        endOffset = endOffset,
        text = text,
    )

    private fun KnowledgeChunkEntity.toHit(document: KnowledgeDocumentEntity) = KnowledgeSearchHit(
        chunkId = id,
        documentId = documentId,
        documentRevision = documentRevision,
        documentName = document.displayName,
        sequence = sequence,
        startOffset = startOffset,
        endOffset = endOffset,
        text = text,
    )

    private fun KnowledgeRetrievalRecord.toEntity() = KnowledgeRetrievalEntity(
        id = id,
        query = query,
        chunkIdsJson = JSONArray(chunkIds).toString(),
        documentIdsJson = JSONArray(documentIds).toString(),
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        createdAt = createdAt,
    )

    private fun KnowledgeRetrievalEntity.toRecord() = KnowledgeRetrievalRecord(
        id = id,
        query = query,
        chunkIds = JSONArray(chunkIdsJson).toStringList(),
        documentIds = JSONArray(documentIdsJson).toStringList(),
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        createdAt = createdAt,
    )

    private fun JSONArray.toStringList(): List<String> = List(length()) { index -> getString(index) }

    companion object {
        private val SUPPORTED_TEXT_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "application/json",
            "text/csv",
        )
    }

    private data class PreparedKnowledgeImport(
        val displayName: String,
        val mimeType: String,
        val text: ImportedKnowledgeText,
    )
}

// long: FTS 和 LIKE 必须从同一组空白分词生成；引号与通配符分别转义，用户检索词只能改变匹配值，不能改变查询语义。
private fun splitKnowledgeSearchTerms(query: String): List<String> {
    return query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
}

internal fun buildKnowledgeFtsQuery(query: String): String {
    return splitKnowledgeSearchTerms(query).joinToString(" AND ") { term ->
        "\"${term.replace("\"", "\"\"")}\"*"
    }
}

internal fun buildKnowledgeLikePatterns(query: String): List<String> {
    return splitKnowledgeSearchTerms(query).map { term ->
        buildString {
            append('%')
            term.forEach { character ->
                if (character == '%' || character == '_' || character == '\\') append('\\')
                append(character)
            }
            append('%')
        }
    }
}

private fun buildKnowledgeLikeQuery(query: String, limit: Int): SimpleSQLiteQuery {
    val clauses = mutableListOf("knowledge_documents.enabled = 1")
    val arguments = mutableListOf<Any>()
    buildKnowledgeLikePatterns(query).forEach { pattern ->
        clauses += "knowledge_chunks.text LIKE ? ESCAPE '\\'"
        arguments += pattern
    }
    arguments += limit.toLong()
    // long: 多词字面查询在数据库内按 AND 收窄，所有用户文本继续使用绑定参数，避免拼接输入形成 SQL 或 LIKE 语义注入。
    return SimpleSQLiteQuery(
        """
        SELECT knowledge_chunks.* FROM knowledge_chunks
        JOIN knowledge_documents ON knowledge_documents.id = knowledge_chunks.documentId
        WHERE ${clauses.joinToString(" AND ")}
        ORDER BY knowledge_documents.updatedAt DESC, knowledge_chunks.sequence ASC
        LIMIT ?
        """.trimIndent(),
        arguments.toTypedArray(),
    )
}
