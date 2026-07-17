package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryManager
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryStore
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.data.AgentMemoryEntity
import com.longdev.xiaoling.data.AgentMemoryFtsEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentMemoryStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentMemoryStore, AgentMemoryManager {
    override suspend fun remember(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
    ): AgentMemoryRecord {
        val now = System.currentTimeMillis()
        val record = AgentMemoryRecord(
            id = "memory-${UUID.randomUUID()}",
            content = content,
            tags = tags,
            type = type.normalizeMemoryType(),
            sourceConversationId = source.conversationId,
            sourceRunId = source.runId,
            sourceSummary = source.summary,
            confidence = confidence.coerceIn(0.0, 1.0),
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        // long: 记忆写入是 Agent 后续“记住用户偏好”的事实来源，只保存用户批准后的精炼文本和来源摘要，不把整段对话或 API Key 一类敏感上下文一起落库。
        database.withTransaction {
            database.agentMemoryDao().upsertMemory(record.toEntity())
            replaceMemoryIndex(record)
        }
        return record
    }

    override suspend fun search(query: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryRecord> {
        return list(
            query = query,
            filter = if (enabledOnly) AgentMemoryFilter.ENABLED else AgentMemoryFilter.ALL,
            limit = limit.coerceIn(1, 10),
        )
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

    override suspend fun delete(memoryId: String): Boolean {
        return database.withTransaction {
            database.agentMemoryDao().deleteMemoryIndex(memoryId)
            database.agentMemoryDao().deleteMemory(memoryId) > 0
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
