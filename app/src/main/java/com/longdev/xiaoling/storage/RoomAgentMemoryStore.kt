package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryCandidateManager
import com.longdev.xiaoling.agent.AgentMemoryCandidatePolicy
import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemoryManager
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryStore
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.agent.AgentMemorySensitiveCategory
import com.longdev.xiaoling.data.AgentMemoryCandidateEntity
import com.longdev.xiaoling.data.AgentMemoryEntity
import com.longdev.xiaoling.data.AgentMemoryFtsEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentMemoryStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentMemoryStore, AgentMemoryManager, AgentMemoryCandidateManager {
    override suspend fun remember(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
    ): AgentMemoryRecord {
        return database.withTransaction {
            rememberInTransaction(
                content = content,
                tags = tags,
                type = type,
                source = source,
                confidence = confidence,
            )
        }
    }

    override suspend fun search(query: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryRecord> {
        return list(
            query = query,
            filter = if (enabledOnly) AgentMemoryFilter.ENABLED else AgentMemoryFilter.ALL,
            limit = limit.coerceIn(1, 10),
        )
    }

    override suspend fun get(memoryId: String): AgentMemoryRecord? {
        return database.agentMemoryDao().getMemory(memoryId)?.toRecord()
    }

    override suspend fun list(query: String, filter: AgentMemoryFilter, limit: Int): List<AgentMemoryRecord> {
        val safeLimit = limit.coerceIn(1, 200)
        val enabledFilter = when (filter) {
            AgentMemoryFilter.ALL -> null
            AgentMemoryFilter.ENABLED -> true
            AgentMemoryFilter.DISABLED -> false
        }
        val normalizedQuery = query.trim()
        val dao = database.agentMemoryDao()
        if (normalizedQuery.isBlank()) {
            return dao.list(limit = safeLimit, enabledFilter = enabledFilter).map { it.toRecord() }
        }
        val searchTerms = splitAgentMemorySearchTerms(normalizedQuery)
        val ftsMatches = buildAgentMemoryFtsQuery(searchTerms)
            .takeIf { it.isNotBlank() }
            ?.let { ftsQuery ->
                runCatching {
                    dao.searchFts(ftsQuery = ftsQuery, limit = safeLimit, enabledFilter = enabledFilter)
                }.getOrDefault(emptyList())
            }
            .orEmpty()
        val likeMatches = dao.searchForManagement(
            buildAgentMemoryLikeQuery(
                terms = searchTerms,
                enabledFilter = enabledFilter,
                limit = safeLimit,
            ),
        )
        // long: Android FTS4 的 unicode61 不负责中文分词；LIKE 按搜索词逐项兜底中文和任意字面子串，避免空格或通配符让合法记忆漏召回或误命中。
        return (ftsMatches + likeMatches)
            .distinctBy { it.id }
            .map { it.toRecord() }
            .sortedWith(compareByDescending<AgentMemoryRecord> { it.pinned }.thenByDescending { it.updatedAt })
            .take(safeLimit)
    }

    override suspend fun update(memoryId: String, update: AgentMemoryUpdate): AgentMemoryRecord? {
        val content = update.content.trim()
        if (content.isBlank()) return null
        AgentMemoryCandidatePolicy.sensitiveCategoryIn("$content\n${update.tags}")?.let { category ->
            throw IllegalArgumentException("检测到${category.displayName}，未保存原文")
        }
        return database.withTransaction {
            val current = database.agentMemoryDao().getMemory(memoryId)?.toRecord() ?: return@withTransaction null
            val updated = current.copy(
                content = content,
                tags = update.tags.trim(),
                type = update.type.normalizeMemoryType(),
                confidence = update.confidence.coerceIn(0.0, 1.0),
                updatedAt = System.currentTimeMillis(),
            )
            database.agentMemoryDao().upsertMemory(updated.toEntity())
            replaceMemoryIndex(updated)
            updated
        }
    }

    override suspend fun setEnabled(memoryId: String, enabled: Boolean): AgentMemoryRecord? {
        return mutate(memoryId) { copy(enabled = enabled, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun setPinned(memoryId: String, pinned: Boolean): AgentMemoryRecord? {
        return mutate(memoryId) { copy(pinned = pinned, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun delete(memoryId: String): AgentMemoryRecord? {
        return database.withTransaction {
            val current = database.agentMemoryDao().getMemory(memoryId)?.toRecord()
                ?: return@withTransaction null
            database.agentMemoryDao().deleteMemoryIndex(memoryId)
            database.agentMemoryDao().deleteMemory(memoryId)
            current
        }
    }

    override suspend fun restore(memory: AgentMemoryRecord): AgentMemoryRecord {
        return database.withTransaction {
            database.agentMemoryDao().upsertMemory(memory.toEntity())
            replaceMemoryIndex(memory)
            memory
        }
    }

    override suspend fun createCandidate(
        userText: String,
        source: AgentMemorySource,
    ): AgentMemoryCandidateRecord? {
        return database.withTransaction {
            val dao = database.agentMemoryDao()
            val existingMemories = dao.listAllMemories().map { it.toRecord() }
            val draft = AgentMemoryCandidatePolicy.evaluateTurn(userText, source, existingMemories)
                ?: return@withTransaction null
            val existingCandidate = dao.listAllCandidates().firstOrNull { candidate ->
                if (draft.normalizedContent.isNotBlank()) {
                    candidate.normalizedContent == draft.normalizedContent
                } else {
                    candidate.status == AgentMemoryCandidateStatus.BLOCKED_SENSITIVE.name &&
                        candidate.sensitiveCategory == draft.sensitiveCategory?.name &&
                        candidate.sourceConversationId == source.conversationId
                }
            }
            if (existingCandidate != null) return@withTransaction existingCandidate.toRecord()

            val now = System.currentTimeMillis()
            val record = AgentMemoryCandidateRecord(
                id = "memory-candidate-${UUID.randomUUID()}",
                content = draft.content,
                normalizedContent = draft.normalizedContent,
                type = draft.type,
                topicKey = draft.topicKey,
                sourceConversationId = draft.source.conversationId,
                sourceRunId = draft.source.runId,
                sourceSummary = draft.source.summary,
                confidence = draft.confidence,
                status = draft.status,
                sensitiveCategory = draft.sensitiveCategory,
                relatedMemoryId = draft.relatedMemoryId,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsertCandidate(record.toEntity())
            record
        }
    }

    override suspend fun listCandidates(limit: Int): List<AgentMemoryCandidateRecord> {
        return database.agentMemoryDao().listCandidates(limit.coerceIn(1, 200)).map { it.toRecord() }
    }

    override suspend fun acceptCandidate(candidateId: String): AgentMemoryCandidateRecord? {
        return database.withTransaction {
            val dao = database.agentMemoryDao()
            val candidate = dao.getCandidate(candidateId)?.toRecord() ?: return@withTransaction null
            if (candidate.status !in setOf(AgentMemoryCandidateStatus.PENDING, AgentMemoryCandidateStatus.CONFLICT)) {
                return@withTransaction candidate
            }
            val existingDuplicate = dao.listAllMemories()
                .map { it.toRecord() }
                .firstOrNull {
                    AgentMemoryCandidatePolicy.normalizeContent(it.content) == candidate.normalizedContent
                }
            val memory = rememberInTransaction(
                content = candidate.content,
                tags = candidate.topicKey,
                type = candidate.type,
                source = AgentMemorySource(
                    candidate.sourceConversationId,
                    candidate.sourceRunId,
                    candidate.sourceSummary,
                ),
                confidence = candidate.confidence,
            )
            val createdNewMemory = existingDuplicate == null
            val updated = candidate.copy(
                status = if (createdNewMemory) AgentMemoryCandidateStatus.ACCEPTED else AgentMemoryCandidateStatus.DUPLICATE,
                relatedMemoryId = if (createdNewMemory) candidate.relatedMemoryId else memory.id,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertCandidate(updated.toEntity())
            updated
        }
    }

    override suspend fun rejectCandidate(candidateId: String): AgentMemoryCandidateRecord? {
        return database.withTransaction {
            val dao = database.agentMemoryDao()
            val candidate = dao.getCandidate(candidateId)?.toRecord() ?: return@withTransaction null
            if (candidate.status !in setOf(AgentMemoryCandidateStatus.PENDING, AgentMemoryCandidateStatus.CONFLICT)) {
                return@withTransaction candidate
            }
            val updated = candidate.copy(
                status = AgentMemoryCandidateStatus.REJECTED,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertCandidate(updated.toEntity())
            updated
        }
    }

    private suspend fun mutate(
        memoryId: String,
        block: AgentMemoryRecord.() -> AgentMemoryRecord,
    ): AgentMemoryRecord? {
        return database.withTransaction {
            val current = database.agentMemoryDao().getMemory(memoryId)?.toRecord() ?: return@withTransaction null
            val updated = current.block()
            database.agentMemoryDao().upsertMemory(updated.toEntity())
            replaceMemoryIndex(updated)
            updated
        }
    }

    private suspend fun replaceMemoryIndex(record: AgentMemoryRecord) {
        val dao = database.agentMemoryDao()
        dao.deleteMemoryIndex(record.id)
        dao.insertMemoryIndex(record.toFtsEntity())
    }

    private suspend fun rememberInTransaction(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
    ): AgentMemoryRecord {
        val dao = database.agentMemoryDao()
        val existingMemories = dao.listAllMemories().map { it.toRecord() }
        AgentMemoryCandidatePolicy.sensitiveCategoryIn(
            listOf(content, tags, source.summary).joinToString("\n"),
        )?.let { category ->
            throw IllegalArgumentException("检测到${category.displayName}，未保存原文")
        }
        val assessment = AgentMemoryCandidatePolicy.assessContent(
            content = content,
            type = type.normalizeMemoryType(),
            source = source,
            existingMemories = existingMemories,
        )
        if (assessment.status == AgentMemoryCandidateStatus.BLOCKED_SENSITIVE) {
            throw IllegalArgumentException(assessment.displaySummary)
        }
        assessment.relatedMemoryId
            ?.takeIf { assessment.status == AgentMemoryCandidateStatus.DUPLICATE }
            ?.let { duplicateId -> existingMemories.firstOrNull { it.id == duplicateId } }
            ?.let { return it }

        val now = System.currentTimeMillis()
        val record = AgentMemoryRecord(
            id = "memory-${UUID.randomUUID()}",
            content = assessment.content,
            tags = tags.trim(),
            type = assessment.type,
            sourceConversationId = source.conversationId,
            sourceRunId = source.runId,
            sourceSummary = source.summary,
            confidence = confidence.coerceIn(0.0, 1.0),
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        // long: 无论来自候选确认还是 memory.remember，正式写入前都执行同一套敏感过滤与去重；这样工具入口不能绕过候选页的隐私边界。
        dao.upsertMemory(record.toEntity())
        replaceMemoryIndex(record)
        return record
    }

    private fun AgentMemoryRecord.toEntity() = AgentMemoryEntity(
        id = id,
        content = content,
        tags = tags,
        type = type,
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        sourceSummary = sourceSummary,
        confidence = confidence,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
    )

    private fun AgentMemoryRecord.toFtsEntity() = AgentMemoryFtsEntity(
        memoryId = id,
        content = content,
        tags = tags,
        type = type,
        sourceSummary = sourceSummary,
    )

    private fun AgentMemoryEntity.toRecord() = AgentMemoryRecord(
        id = id,
        content = content,
        tags = tags,
        type = type,
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        sourceSummary = sourceSummary,
        confidence = confidence,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
    )

    private fun AgentMemoryCandidateRecord.toEntity() = AgentMemoryCandidateEntity(
        id = id,
        content = content,
        normalizedContent = normalizedContent,
        type = type,
        topicKey = topicKey,
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        sourceSummary = sourceSummary,
        confidence = confidence,
        status = status.name,
        sensitiveCategory = sensitiveCategory?.name,
        relatedMemoryId = relatedMemoryId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AgentMemoryCandidateEntity.toRecord() = AgentMemoryCandidateRecord(
        id = id,
        content = content,
        normalizedContent = normalizedContent,
        type = type,
        topicKey = topicKey,
        sourceConversationId = sourceConversationId,
        sourceRunId = sourceRunId,
        sourceSummary = sourceSummary,
        confidence = confidence,
        status = AgentMemoryCandidateStatus.entries.firstOrNull { it.name == status }
            ?: AgentMemoryCandidateStatus.REJECTED,
        sensitiveCategory = AgentMemorySensitiveCategory.entries.firstOrNull { it.name == sensitiveCategory },
        relatedMemoryId = relatedMemoryId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun String.normalizeMemoryType(): String {
        val normalized = trim()
        return when {
            normalized.equals("Preference", ignoreCase = true) -> "Preference"
            normalized.equals("ProfileFact", ignoreCase = true) -> "ProfileFact"
            normalized.equals("Procedure", ignoreCase = true) -> "Procedure"
            normalized.equals("Episode", ignoreCase = true) -> "Episode"
            else -> "Episode"
        }
    }
}

private fun splitAgentMemorySearchTerms(query: String): List<String> {
    return query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

internal fun buildAgentMemoryFtsQuery(query: String): String {
    return buildAgentMemoryFtsQuery(splitAgentMemorySearchTerms(query))
}

private fun buildAgentMemoryFtsQuery(terms: List<String>): String {
    return terms
        .joinToString(" AND ") { token ->
            "\"" + token.replace("\"", "\"\"") + "\"*"
        }
}

internal fun buildAgentMemoryLikePatterns(query: String): List<String> {
    return buildAgentMemoryLikePatterns(splitAgentMemorySearchTerms(query))
}

private fun buildAgentMemoryLikePatterns(terms: List<String>): List<String> {
    return terms.map { term ->
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

private fun buildAgentMemoryLikeQuery(
    terms: List<String>,
    enabledFilter: Boolean?,
    limit: Int,
): SimpleSQLiteQuery {
    val clauses = mutableListOf<String>()
    val arguments = mutableListOf<Any>()
    enabledFilter?.let { enabled ->
        clauses += "enabled = ?"
        arguments += if (enabled) 1L else 0L
    }
    buildAgentMemoryLikePatterns(terms).forEach { pattern ->
        clauses += """
            (
                content LIKE ? ESCAPE '\'
                OR tags LIKE ? ESCAPE '\'
                OR type LIKE ? ESCAPE '\'
                OR sourceSummary LIKE ? ESCAPE '\'
            )
        """.trimIndent()
        repeat(4) { arguments += pattern }
    }
    arguments += limit.toLong()
    val whereClause = clauses.joinToString(separator = " AND ").ifBlank { "1 = 1" }
    // long: 多词查询必须在数据库内按 AND 收窄，参数仍通过绑定传入，避免拼接用户输入造成 SQL/LIKE 语义注入。
    return SimpleSQLiteQuery(
        "SELECT * FROM agent_memories WHERE $whereClause ORDER BY pinned DESC, updatedAt DESC LIMIT ?",
        arguments.toTypedArray(),
    )
}
