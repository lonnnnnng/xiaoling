package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.longdev.xiaoling.data.KnowledgeChunkEntity
import com.longdev.xiaoling.data.KnowledgeChunkEmbeddingEntity
import com.longdev.xiaoling.data.KnowledgeChunkFtsEntity
import com.longdev.xiaoling.data.KnowledgeDocumentEntity
import com.longdev.xiaoling.data.KnowledgeDocumentSummaryEntity
import com.longdev.xiaoling.data.KnowledgeRetrievalEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeChunkRecord
import com.longdev.xiaoling.knowledge.KNOWLEDGE_PREVIEW_CHARACTER_LIMIT
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingIndexSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingProvider
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingRebuildResult
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingRebuildStatus
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingSimilarity
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingVectorCodec
import com.longdev.xiaoling.knowledge.KnowledgeSearchFusionPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeSearchResult
import com.longdev.xiaoling.knowledge.KnowledgeTextPolicy
import com.longdev.xiaoling.knowledge.assessAgainst
import com.longdev.xiaoling.knowledge.ImportedKnowledgeText
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class RoomKnowledgeDocumentStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val embeddingProvider: KnowledgeEmbeddingProvider? = null,
    private val embeddingIndexTimeoutMillis: Long = EMBEDDING_INDEX_TIMEOUT_MS,
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
        val chunks = document.buildChunks()
        database.withTransaction {
            database.knowledgeDao().insertDocument(document.toEntity())
            database.knowledgeDao().insertChunks(chunks.map { it.toEntity() })
            database.knowledgeDao().insertChunkIndexes(chunks.map { it.toFtsEntity() })
        }
        rebuildEmbeddings(document.id)
        return document
    }

    override suspend fun replaceUtf8Document(
        documentId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord {
        val payload = prepareImport(displayName, mimeType, bytes)
        val (replaced, chunks) = database.withTransaction {
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
            dao.deleteChunkEmbeddings(documentId)
            dao.insertChunks(chunks.map { it.toEntity() })
            dao.insertChunkIndexes(chunks.map { it.toFtsEntity() })
            replaced to chunks
        }
        rebuildEmbeddings(replaced.id)
        return replaced
    }

    override suspend fun getDocument(documentId: String): KnowledgeDocumentRecord? {
        return database.knowledgeDao().getDocument(documentId)?.toRecord()
    }

    override suspend fun listDocuments(): List<KnowledgeDocumentSummary> {
        return database.knowledgeDao().listDocumentSummaries().map { it.toSummary() }
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

    override suspend fun getEmbeddingIndexes(documentId: String): List<KnowledgeEmbeddingIndexSummary> {
        return database.knowledgeDao().getEmbeddingIndexSummaries(documentId).map { summary ->
            KnowledgeEmbeddingIndexSummary(
                providerId = summary.providerId,
                model = summary.model,
                documentRevision = summary.documentRevision,
                dimensions = summary.dimensions,
                chunkCount = summary.chunkCount,
                updatedAt = summary.updatedAt,
            )
        }
    }

    override suspend fun rebuildEmbeddings(documentId: String): KnowledgeEmbeddingRebuildResult {
        val (document, chunks) = database.withTransaction {
            val dao = database.knowledgeDao()
            val current = dao.getDocument(documentId)?.toRecord()
                ?: throw IllegalArgumentException("知识文档不存在")
            current to dao.getChunks(documentId).map { it.toRecord() }
        }
        if (!document.enabled) {
            return document.embeddingRebuildResult(KnowledgeEmbeddingRebuildStatus.DOCUMENT_DISABLED)
        }
        val provider = embeddingProvider
            ?: return document.embeddingRebuildResult(KnowledgeEmbeddingRebuildStatus.NO_PROVIDER)
        val batch = try {
            withTimeoutOrNull(embeddingIndexTimeoutMillis) {
                provider.embed(chunks.map(KnowledgeChunkRecord::text))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            // long: Provider 已返回但向量身份、维度或数值校验失败属于响应无效；它与网络不可用分开呈现，同时继续保留旧索引。
            return document.embeddingRebuildResult(KnowledgeEmbeddingRebuildStatus.INVALID_RESPONSE)
        } catch (_: Exception) {
            null
        } ?: return document.embeddingRebuildResult(KnowledgeEmbeddingRebuildStatus.PROVIDER_UNAVAILABLE)
        if (batch.vectors.size != chunks.size) {
            return document.embeddingRebuildResult(
                status = KnowledgeEmbeddingRebuildStatus.INVALID_RESPONSE,
                providerId = batch.providerId,
                model = batch.model,
            )
        }
        val dimensions = batch.vectors.firstOrNull()?.size
            ?: return document.embeddingRebuildResult(
                status = KnowledgeEmbeddingRebuildStatus.INVALID_RESPONSE,
                providerId = batch.providerId,
                model = batch.model,
            )
        val indexedAt = clock()
        val embeddings = chunks.zip(batch.vectors).map { (chunk, vector) ->
            KnowledgeChunkEmbeddingEntity(
                chunkId = chunk.id,
                documentId = document.id,
                documentRevision = document.revision,
                providerId = batch.providerId,
                model = batch.model,
                dimensions = dimensions,
                vectorBlob = KnowledgeEmbeddingVectorCodec.encode(vector),
                createdAt = indexedAt,
            )
        }
        return database.withTransaction {
            val dao = database.knowledgeDao()
            val current = dao.getDocument(document.id)
            if (current == null || current.revision != document.revision) {
                return@withTransaction document.embeddingRebuildResult(
                    status = KnowledgeEmbeddingRebuildStatus.STALE_DOCUMENT,
                    providerId = batch.providerId,
                    model = batch.model,
                )
            }
            if (!current.enabled) {
                return@withTransaction document.embeddingRebuildResult(
                    status = KnowledgeEmbeddingRebuildStatus.DOCUMENT_DISABLED,
                    providerId = batch.providerId,
                    model = batch.model,
                )
            }
            val currentChunks = dao.getChunks(document.id)
            if (currentChunks.map { it.id } != chunks.map { it.id } ||
                currentChunks.any { it.documentRevision != document.revision }
            ) {
                return@withTransaction document.embeddingRebuildResult(
                    status = KnowledgeEmbeddingRebuildStatus.STALE_DOCUMENT,
                    providerId = batch.providerId,
                    model = batch.model,
                )
            }
            // long: 重建只替换当前 Provider 与模型的索引空间；切换模型后已有空间仍可审计和复用，失败路径也不会先删掉可用向量。
            dao.deleteChunkEmbeddings(document.id, batch.providerId, batch.model)
            dao.upsertChunkEmbeddings(embeddings)
            document.embeddingRebuildResult(
                status = KnowledgeEmbeddingRebuildStatus.INDEXED,
                providerId = batch.providerId,
                model = batch.model,
                indexedChunkCount = embeddings.size,
            )
        }
    }

    override suspend fun inspectReferences(references: List<KnowledgeReference>): List<KnowledgeReferenceStatus> {
        val distinctReferences = references.distinct()
        if (distinctReferences.isEmpty()) return emptyList()
        return database.withTransaction {
            val dao = database.knowledgeDao()
            // long: 对话气泡只核验引用身份，不读取可能达到 64 MB 的知识全文；查询还要分批控制绑定参数数量，长会话不能因超过 SQLite 上限而让整批引用变成“暂无法核验”。
            val documents = mutableMapOf<String, KnowledgeDocumentSummary>()
            distinctReferences.map { it.documentId }.distinct()
                .chunked(SQLITE_QUERY_PARAMETER_BATCH_SIZE)
                .forEach { documentIds ->
                    dao.getDocumentSummaries(documentIds).forEach { document ->
                        documents[document.id] = document.toSummary()
                    }
                }
            val chunks = mutableMapOf<String, KnowledgeChunkRecord>()
            distinctReferences.map { it.chunkId }.distinct()
                .chunked(SQLITE_QUERY_PARAMETER_BATCH_SIZE)
                .forEach { chunkIds ->
                    dao.getChunksByIds(chunkIds).forEach { chunk ->
                        chunks[chunk.id] = chunk.toRecord()
                    }
                }
            distinctReferences.map { reference ->
                reference.assessAgainst(
                    document = documents[reference.documentId],
                    chunk = chunks[reference.chunkId],
                )
            }
        }
    }

    override suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference> {
        return inspectReferences(references)
            .filter { it.availability == KnowledgeReferenceAvailability.CURRENT }
            .map { it.reference }
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
        val semantic = loadSemanticCandidates(canonicalQuery, fetchLimit = (boundedLimit * 2).coerceAtMost(40))
        return database.withTransaction {
            val dao = database.knowledgeDao()
            val fetchLimit = (boundedLimit * 2).coerceAtMost(40)
            val ftsHits = dao.searchFts(buildKnowledgeFtsQuery(canonicalQuery), fetchLimit)
            val likeHits = dao.searchLike(buildKnowledgeLikeQuery(canonicalQuery, fetchLimit))
            val lexicalChunks = (ftsHits + likeHits).distinctBy { it.id }
            val semanticChunks = semantic.chunkIds
                .let { ids -> dao.getChunksByIds(ids).associateBy { it.id } }
            val fusedIds = KnowledgeSearchFusionPolicy.fuse(
                ftsIds = ftsHits.map { it.id },
                likeIds = likeHits.map { it.id },
                semanticIds = semantic.chunkIds,
                // long: 先保留额外候选，最终 enabled/revision 复核淘汰并发失效项后仍能尽量补足用户请求数量。
                limit = fetchLimit,
            )
            val chunksById = (lexicalChunks + semanticChunks.values).associateBy { it.id }
            val candidateChunks = fusedIds.mapNotNull(chunksById::get)
            val documents = dao.getDocuments(candidateChunks.map { it.documentId }.distinct()).associateBy { it.id }
            // long: 语义向量在事务外计算，最终组装时再次核对 enabled 和 revision，避免并发禁用或替换把过期 chunk 带进新答案。
            val chunks = candidateChunks.filter { chunk ->
                documents[chunk.documentId]?.let { document ->
                    document.enabled && document.revision == chunk.documentRevision
                } == true
            }.take(boundedLimit)
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
                embeddingProviderId = semantic.providerId,
                embeddingModel = semantic.model,
                embeddingStatus = semantic.status,
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
            dao.deleteChunkEmbeddings(documentId)
            dao.deleteChunks(documentId)
            dao.deleteDocument(documentId) == 1
        }
    }

    private suspend fun loadSemanticCandidates(query: String, fetchLimit: Int): SemanticCandidates {
        val provider = embeddingProvider ?: return SemanticCandidates()
        val batch = try {
            withTimeoutOrNull(EMBEDDING_QUERY_TIMEOUT_MS) { provider.embed(listOf(query)) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return SemanticCandidates(
            providerId = null,
            model = null,
            status = KnowledgeEmbeddingStatus.PROVIDER_UNAVAILABLE,
        )
        val queryVector = batch.vectors.singleOrNull()
            ?: return SemanticCandidates(batch.providerId, batch.model, KnowledgeEmbeddingStatus.DIMENSION_MISMATCH)
        val index = database.knowledgeDao().getEmbeddingIndex(
            providerId = batch.providerId,
            model = batch.model,
            limit = MAX_EMBEDDING_INDEX_ROWS,
        )
        if (index.isEmpty()) return SemanticCandidates(batch.providerId, batch.model, KnowledgeEmbeddingStatus.NO_INDEX)
        val scored = index.mapNotNull { row ->
            val vector = runCatching { KnowledgeEmbeddingVectorCodec.decode(row.vectorBlob, row.dimensions) }.getOrNull()
                ?: return@mapNotNull null
            KnowledgeEmbeddingSimilarity.cosine(queryVector, vector)?.let { row.chunkId to it }
        }
        if (scored.isEmpty()) return SemanticCandidates(batch.providerId, batch.model, KnowledgeEmbeddingStatus.DIMENSION_MISMATCH)
        return SemanticCandidates(
            providerId = batch.providerId,
            model = batch.model,
            status = KnowledgeEmbeddingStatus.USED,
            chunkIds = scored.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(fetchLimit)
                .map { it.first },
        )
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

    private fun KnowledgeDocumentRecord.embeddingRebuildResult(
        status: KnowledgeEmbeddingRebuildStatus,
        providerId: String? = null,
        model: String? = null,
        indexedChunkCount: Int = 0,
    ) = KnowledgeEmbeddingRebuildResult(
        documentId = id,
        documentRevision = revision,
        status = status,
        providerId = providerId,
        model = model,
        indexedChunkCount = indexedChunkCount,
    )

    private fun KnowledgeDocumentSummaryEntity.toSummary() = KnowledgeDocumentSummary(
        id = id,
        displayName = displayName,
        mimeType = mimeType,
        contentHash = contentHash,
        revision = revision,
        parserVersion = parserVersion,
        byteSize = byteSize,
        characterCount = characterCount,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chunkCount = chunkCount,
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
        embeddingProviderId = embeddingProviderId,
        embeddingModel = embeddingModel,
        embeddingStatus = embeddingStatus.name,
        createdAt = createdAt,
    )

    private fun KnowledgeRetrievalEntity.toRecord() = KnowledgeRetrievalRecord(
        id = id,
        query = query,
        chunkIds = JSONArray(chunkIdsJson).toStringList(),
        documentIds = JSONArray(documentIdsJson).toStringList(),
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        embeddingProviderId = embeddingProviderId,
        embeddingModel = embeddingModel,
        embeddingStatus = runCatching { KnowledgeEmbeddingStatus.valueOf(embeddingStatus) }
            .getOrDefault(KnowledgeEmbeddingStatus.LEXICAL_ONLY),
        createdAt = createdAt,
    )

    private fun JSONArray.toStringList(): List<String> = List(length()) { index -> getString(index) }

    companion object {
        private const val SQLITE_QUERY_PARAMETER_BATCH_SIZE = 900
        private const val MAX_EMBEDDING_INDEX_ROWS = 2_000
        private const val EMBEDDING_INDEX_TIMEOUT_MS = 30_000L
        private const val EMBEDDING_QUERY_TIMEOUT_MS = 2_000L

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

    private data class SemanticCandidates(
        val providerId: String? = null,
        val model: String? = null,
        val status: KnowledgeEmbeddingStatus = KnowledgeEmbeddingStatus.LEXICAL_ONLY,
        val chunkIds: List<String> = emptyList(),
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
