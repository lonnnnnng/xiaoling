package com.longdev.xiaoling.storage

import androidx.room.withTransaction
import com.longdev.xiaoling.agent.AgentMessagePartPolicy
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.data.MessageEntity
import com.longdev.xiaoling.data.MessagePartEntity
import com.longdev.xiaoling.data.RoomJson
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.json.JSONObject

class MessageRepository(
    private val database: XiaoLingDatabase,
) {
    suspend fun loadGroupedByConversation(): Map<String, List<StoredConversationMessage>> {
        val dao = database.conversationDao()
        val partsByMessage = dao.getAllMessageParts()
            .groupBy { it.messageId }
            // long: 单个损坏 part 不能阻断整个会话加载；后续可信投影会按消息正文和已验证 Agent 上下文重建可展示证据，避免接受残缺数据库字段。
            .mapValues { (_, parts) -> parts.mapNotNull { it.toMessagePartOrNull() } }
        return dao.getAllMessages()
            .groupBy { it.conversationId }
            .mapValues { (_, messages) -> messages.map { it.toStored(partsByMessage[it.id].orEmpty()) } }
    }

    suspend fun replaceAll(messages: List<Pair<String, StoredConversationMessage>>) {
        database.withTransaction {
            val dao = database.conversationDao()
            dao.deleteAllMessageParts()
            dao.deleteAllMessages()
            persist(messages)
        }
    }

    suspend fun insert(messages: List<Pair<String, StoredConversationMessage>>) {
        if (messages.isEmpty()) return
        database.withTransaction {
            val dao = database.conversationDao()
            dao.deleteMessageParts(messages.map { (_, message) -> message.id })
            persist(messages)
        }
    }

    suspend fun deleteByConversationIds(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        val dao = database.conversationDao()
        val messageIds = dao.getMessageIdsByConversationIds(conversationIds)
        if (messageIds.isNotEmpty()) dao.deleteMessageParts(messageIds)
        dao.deleteMessagesByConversationIds(conversationIds)
    }

    private suspend fun persist(messages: List<Pair<String, StoredConversationMessage>>) {
        val dao = database.conversationDao()
        val resolvedParts = messages.associate { (_, message) -> message.id to message.resolvedParts() }
        dao.insertMessages(messages.map { (conversationId, message) -> message.toEntity(conversationId) })
        dao.insertMessageParts(messages.flatMap { (_, message) ->
            resolvedParts.getValue(message.id).mapIndexed { index, part -> part.toEntity(message.id, index) }
        })
    }

    private fun StoredConversationMessage.toEntity(conversationId: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        text = text,
        createdAt = createdAt,
        origin = origin ?: MessageOrigin.LEGACY_VALUE,
        verifiedAgentContext = verifiedAgentContext,
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

    private fun MessageEntity.toStored(storedParts: List<MessagePart>) = StoredConversationMessage(
        id = id,
        role = role,
        text = text,
        createdAt = createdAt,
        origin = origin,
        verifiedAgentContext = verifiedAgentContext,
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
        parts = AgentMessagePartPolicy.resolve(
            messageId = id,
            text = text,
            origin = MessageOrigin.fromStored(origin, role),
            verifiedContext = VerifiedAgentContextCodec.decode(verifiedAgentContext),
            storedParts = storedParts,
        ),
    )

    private fun StoredConversationMessage.resolvedParts(): List<MessagePart> {
        return AgentMessagePartPolicy.resolve(
            messageId = id,
            text = text,
            origin = MessageOrigin.fromStored(origin, role),
            verifiedContext = VerifiedAgentContextCodec.decode(verifiedAgentContext),
            storedParts = parts,
        )
    }

    private fun MessagePart.toEntity(messageId: String, sequence: Int): MessagePartEntity = when (this) {
        is MessagePart.Text -> MessagePartEntity(
            id = id,
            messageId = messageId,
            sequence = sequence,
            type = TYPE_TEXT,
            text = text,
            toolName = null,
            argumentsJson = null,
            result = null,
            success = null,
            verificationStatus = null,
            memoryIdsJson = null,
        )
        is MessagePart.Tool -> MessagePartEntity(
            id = id,
            messageId = messageId,
            sequence = sequence,
            type = TYPE_TOOL,
            text = null,
            toolName = toolName,
            argumentsJson = JSONObject(arguments.toSortedMap()).toString(),
            result = result,
            success = success,
            verificationStatus = verificationStatus.name,
            memoryIdsJson = RoomJson.encodeStringList(memoryIdsUsed.distinct()),
        )
    }

    private fun MessagePartEntity.toMessagePartOrNull(): MessagePart? = runCatching {
        when (type) {
            TYPE_TEXT -> MessagePart.Text(id = id, text = requireNotNull(text))
            TYPE_TOOL -> MessagePart.Tool(
                id = id,
                toolName = requireNotNull(toolName),
                arguments = requireNotNull(argumentsJson).decodeStringMap(),
                result = requireNotNull(result),
                success = requireNotNull(success),
                verificationStatus = MessageToolVerificationStatus.valueOf(requireNotNull(verificationStatus)),
                memoryIdsUsed = RoomJson.decodeStringList(requireNotNull(memoryIdsJson)),
            )
            else -> null
        }
    }.getOrNull()

    private fun String.decodeStringMap(): Map<String, String> {
        val json = JSONObject(this)
        return buildMap {
            json.keys().forEach { key -> put(key, json.getString(key)) }
        }.toSortedMap()
    }

    companion object {
        private const val TYPE_TEXT = "TEXT"
        private const val TYPE_TOOL = "TOOL"
    }
}
