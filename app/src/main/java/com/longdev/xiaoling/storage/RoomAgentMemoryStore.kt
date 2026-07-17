package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryStore
import com.longdev.xiaoling.data.AgentMemoryEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentMemoryStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentMemoryStore {
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
        database.agentMemoryDao().upsertMemory(record.toEntity())
        return record
    }

    override suspend fun search(query: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryRecord> {
        val pattern = query.trim().takeIf { it.isNotBlank() }?.let { "%$it%" }.orEmpty()
        return database.agentMemoryDao()
            .search(pattern = pattern, limit = limit.coerceIn(1, 10), enabledOnly = enabledOnly)
            .map { it.toRecord() }
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
