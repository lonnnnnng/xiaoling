package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentConversationDetailPolicy
import com.longdev.xiaoling.agent.AgentConversationDetailRecord
import com.longdev.xiaoling.agent.AgentConversationMessageRecord
import com.longdev.xiaoling.agent.AgentConversationMessageRole
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

    override suspend fun get(conversationId: String): AgentConversationDetailRecord? {
        val normalizedId = AgentConversationDetailPolicy.normalizeId(conversationId) ?: return null
        val dao = database.conversationDao()
        val conversation = dao.getConversation(normalizedId) ?: return null
        val messages = dao.getMessagesByConversationId(normalizedId)
            .mapNotNull { message ->
                val role = when (message.role) {
                    "user" -> AgentConversationMessageRole.USER
                    "assistant" -> AgentConversationMessageRole.ASSISTANT
                    else -> return@mapNotNull null
                }
                AgentConversationMessageRecord(
                    role = role,
                    text = message.text,
                    createdAt = message.createdAt,
                )
            }
        // long: 详情只从当前 Room 单会话回读用户/助手文本，不加载 MessagePart，因此工具参数、附件 BLOB、推理和 Provider 元数据不会进入 Agent 上下文。
        return AgentConversationDetailRecord(
            id = conversation.id,
            title = conversation.title,
            updatedAt = conversation.updatedAt,
            messages = AgentConversationDetailPolicy.boundMessages(messages),
        )
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
