package com.longdev.xiaoling.storage

import com.longdev.xiaoling.data.MessageEntity
import com.longdev.xiaoling.data.XiaoLingDatabase

class MessageRepository(
    private val database: XiaoLingDatabase,
) {
    suspend fun loadGroupedByConversation(): Map<String, List<StoredConversationMessage>> {
        return database.conversationDao()
            .getAllMessages()
            .groupBy { it.conversationId }
            .mapValues { (_, messages) -> messages.map { it.toStored() } }
    }

    suspend fun replaceAll(messages: List<Pair<String, StoredConversationMessage>>) {
        database.conversationDao().deleteAllMessages()
        database.conversationDao().insertMessages(messages.map { (conversationId, message) ->
            message.toEntity(conversationId)
        })
    }

    private fun StoredConversationMessage.toEntity(conversationId: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        text = text,
        createdAt = createdAt,
        providerId = meta?.providerId,
        providerName = meta?.providerName,
        model = meta?.model,
        apiMode = meta?.apiMode,
        streaming = meta?.streaming,
        requestUrl = meta?.requestUrl,
        firstTokenLatencyMs = meta?.firstTokenLatencyMs,
        latencyMs = meta?.latencyMs,
        promptTokens = meta?.promptTokens,
        completionTokens = meta?.completionTokens,
        totalTokens = meta?.totalTokens,
        finishReason = meta?.finishReason,
        errorKind = meta?.errorKind,
        errorMessage = meta?.errorMessage,
    )

    private fun MessageEntity.toStored() = StoredConversationMessage(
        id = id,
        role = role,
        text = text,
        createdAt = createdAt,
        meta = StoredMessageMeta(
            providerId = providerId,
            providerName = providerName,
            model = model,
            apiMode = apiMode,
            streaming = streaming,
            requestUrl = requestUrl,
            firstTokenLatencyMs = firstTokenLatencyMs,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            finishReason = finishReason,
            errorKind = errorKind,
            errorMessage = errorMessage,
        ),
    )
}
