package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConversationRepository(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val stateStore: RoomStateStore = RoomStateStore(context),
    private val legacyStore: ConversationStore = ConversationStore(context),
    private val messageRepository: MessageRepository = MessageRepository(database),
) {
    suspend fun load(): StoredConversations = withContext(Dispatchers.IO) {
        ensureMigrated()
        val conversations = loadConversationsFromRoom().ifEmpty { listOf(newConversation()) }
        val selected = stateStore.selectedConversationId()
            ?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.first().id
        StoredConversations(conversations = conversations, selectedConversationId = selected)
    }

    suspend fun save(
        conversations: List<StoredConversation>,
        selectedConversationId: String,
        deletedConversationIds: Set<String> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        ensureMigrated()
        val safeConversations = conversations.ifEmpty { listOf(newConversation()) }
        database.withTransaction {
            val dao = database.conversationDao()
            if (deletedConversationIds.isNotEmpty()) {
                // long: 删除必须来自用户操作产生的显式 ID，不能由快照差集推断；否则前台旧快照会误删后台 Workflow 刚创建的会话和工具证据。
                messageRepository.deleteByConversationIds(deletedConversationIds.toList())
                dao.deleteConversations(deletedConversationIds.toList())
            }
            dao.insertConversations(safeConversations.map { it.toEntity() })
            messageRepository.insert(safeConversations.flatMap { conversation ->
                conversation.messages.map { conversation.id to it }
            })
        }
        stateStore.saveSelectedConversationId(
            selectedConversationId.takeIf { id -> safeConversations.any { it.id == id } } ?: safeConversations.first().id,
        )
    }

    private suspend fun ensureMigrated() {
        if (stateStore.conversationsMigrated()) return
        val legacy = legacyStore.load()
        val conversations = legacy.conversations.ifEmpty { listOf(newConversation()) }
        database.withTransaction {
            if (database.conversationDao().getAllConversations().isEmpty()) {
                // long: 会话迁移只在首次进入 Room 时执行，避免用户之后清理会话又被旧 SharedPreferences 数据恢复。
                database.conversationDao().insertConversations(conversations.map { it.toEntity() })
                messageRepository.replaceAll(conversations.flatMap { conversation ->
                    conversation.messages.map { conversation.id to it }
                })
            }
        }
        stateStore.saveSelectedConversationId(
            legacy.selectedConversationId.takeIf { id -> conversations.any { it.id == id } } ?: conversations.first().id,
        )
        stateStore.markConversationsMigrated()
    }

    private suspend fun loadConversationsFromRoom(): List<StoredConversation> {
        val conversations = database.conversationDao().getAllConversations()
        val messages = messageRepository.loadGroupedByConversation()
        return conversations
            .map { conversation ->
                StoredConversation(
                    id = conversation.id,
                    title = conversation.title,
                    summary = conversation.summary,
                    summaryUntilMessageId = conversation.summaryUntilMessageId,
                    summaryUpdatedAt = conversation.summaryUpdatedAt,
                    summaryModel = conversation.summaryModel,
                    messages = messages[conversation.id].orEmpty(),
                    createdAt = conversation.createdAt,
                    updatedAt = conversation.updatedAt,
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    private fun newConversation(): StoredConversation {
        val now = System.currentTimeMillis()
        return StoredConversation(
            id = "conversation-$now",
            title = "新会话",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun StoredConversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        summary = summary,
        summaryUntilMessageId = summaryUntilMessageId,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryModel = summaryModel,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

}
