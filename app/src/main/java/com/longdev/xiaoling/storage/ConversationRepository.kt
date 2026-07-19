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
        val conversationEntities = database.conversationDao().getAllConversations()
            .sortedByDescending { it.updatedAt }
        val selectedId = stateStore.selectedConversationId()
            ?.takeIf { id -> conversationEntities.any { it.id == id } }
            ?: conversationEntities.firstOrNull()?.id
        val messages = messageRepository.loadGroupedByConversation(selectedId?.let(::setOf).orEmpty())
        val conversations = conversationEntities.map { conversation ->
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
        }.ifEmpty { listOf(newConversation()) }
        val selected = selectedId?.takeIf { id -> conversations.any { it.id == id } } ?: conversations.first().id
        StoredConversations(conversations = conversations, selectedConversationId = selected)
    }

    suspend fun loadConversationMessages(conversationId: String): List<StoredConversationMessage> = withContext(Dispatchers.IO) {
        ensureMigrated()
        messageRepository.loadConversation(conversationId)
    }

    suspend fun save(
        conversations: List<StoredConversation>,
        selectedConversationId: String,
        deletedConversationIds: Set<String> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        ensureMigrated()
        // long: 删除 ID 是用户动作的权威事实；即使异步会话加载被取消后传入含旧会话的陈旧快照，也必须先过滤，不能在同一事务中先删后复活。
        val retainedConversations = conversations.filterNot { it.id in deletedConversationIds }
        val safeConversations = retainedConversations.ifEmpty { listOf(newConversation()) }
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
