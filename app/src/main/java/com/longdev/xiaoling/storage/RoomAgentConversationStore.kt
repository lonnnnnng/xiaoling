package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentConversationRecord
import com.longdev.xiaoling.agent.AgentConversationStore
import com.longdev.xiaoling.data.XiaoLingDatabase

class RoomAgentConversationStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentConversationStore {
    override suspend fun list(limit: Int): List<AgentConversationRecord> {
        return loadAll()
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 10))
    }

    override suspend fun search(query: String, limit: Int): List<AgentConversationRecord> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val messagesByConversation = database.conversationDao().getAllMessages().groupBy { it.conversationId }
        // long: 会话检索是只读工具，直接从 Room 快照构造结果；不依赖当前 Compose 页面状态，应用重启后仍能查到历史会话。
        return database.conversationDao()
            .getAllConversations()
            .filter { conversation ->
                conversation.title.contains(normalized, ignoreCase = true) ||
                    conversation.summary.contains(normalized, ignoreCase = true) ||
                    messagesByConversation[conversation.id].orEmpty().any { it.text.contains(normalized, ignoreCase = true) }
            }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 10))
            .map { conversation ->
                conversation.toRecord(messagesByConversation[conversation.id].orEmpty().size)
            }
    }

    private suspend fun loadAll(): List<AgentConversationRecord> {
        val messagesByConversation = database.conversationDao().getAllMessages().groupBy { it.conversationId }
        return database.conversationDao()
            .getAllConversations()
            .map { conversation ->
                conversation.toRecord(messagesByConversation[conversation.id].orEmpty().size)
            }
    }

    private fun com.longdev.xiaoling.data.ConversationEntity.toRecord(messageCount: Int): AgentConversationRecord {
        return AgentConversationRecord(
            id = id,
            title = title,
            summary = summary,
            messageCount = messageCount,
            updatedAt = updatedAt,
        )
    }
}
