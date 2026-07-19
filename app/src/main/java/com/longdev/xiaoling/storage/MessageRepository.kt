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
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessageDocumentDetail
import com.longdev.xiaoling.model.MessageImageDetail
import com.longdev.xiaoling.model.MessageReasoningSource
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import org.json.JSONObject

class MessageRepository(
    private val database: XiaoLingDatabase,
) {
    suspend fun loadGroupedByConversation(
        binaryConversationIds: Set<String> = emptySet(),
    ): Map<String, List<StoredConversationMessage>> {
        val dao = database.conversationDao()
        val fullBinaryParts = if (binaryConversationIds.isEmpty()) {
            emptyList()
        } else {
            dao.getBinaryMessagePartsByConversationIds(binaryConversationIds.toList())
        }
        // long: 会话列表只需要文本和附件元数据；图片/文档 BLOB 仅为当前会话加载，避免附件随会话数量增长后在应用启动时一次性占满堆内存。
        val partsByMessage = (dao.getAllMessagePartsWithoutBinaryData() + fullBinaryParts)
            .sortedWith(compareBy(MessagePartEntity::messageId, MessagePartEntity::sequence))
            .groupBy { it.messageId }
            // long: 单个损坏 part 不能阻断整个会话加载；后续可信投影会按消息正文和已验证 Agent 上下文重建可展示证据，避免接受残缺数据库字段。
            .mapValues { (_, parts) -> parts.mapNotNull { it.toMessagePartOrNull() } }
        return dao.getAllMessages()
            .groupBy { it.conversationId }
            .mapValues { (_, messages) -> messages.map { it.toStored(partsByMessage[it.id].orEmpty()) } }
    }

    suspend fun loadConversation(conversationId: String): List<StoredConversationMessage> {
        val dao = database.conversationDao()
        val partsByMessage = dao.getMessagePartsByConversationId(conversationId)
            .groupBy { it.messageId }
            .mapValues { (_, parts) -> parts.mapNotNull { it.toMessagePartOrNull() } }
        return dao.getMessagesByConversationId(conversationId)
            .map { it.toStored(partsByMessage[it.id].orEmpty()) }
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
            val resolvedParts = messages.associate { (_, message) -> message.id to message.resolvedParts() }
            val messagesById = messages.associate { (_, message) -> message.id to message }
            val binaryLessMessageIds = resolvedParts
                .filter { (messageId, parts) ->
                    val message = messagesById.getValue(messageId)
                    MessageOrigin.fromStored(message.origin, message.role) == MessageOrigin.USER &&
                        parts.none { it is MessagePart.Image || it is MessagePart.Document }
                }
                .keys
                .toList()
            val preservedBinaryMessageIds = if (binaryLessMessageIds.isEmpty()) {
                emptySet()
            } else {
                dao.getMessageIdsWithBinaryParts(binaryLessMessageIds).toSet()
            }
            val replaceAllPartMessageIds = messages.map { (_, message) -> message.id }
                .filterNot(preservedBinaryMessageIds::contains)
            if (replaceAllPartMessageIds.isNotEmpty()) {
                dao.deleteMessageParts(replaceAllPartMessageIds)
            }
            if (preservedBinaryMessageIds.isNotEmpty()) {
                // long: 非当前会话不会把附件 BLOB 载入内存；保存轻量快照时只替换非附件 part，并为新 Text 保留 sequence 1，避免误删既有图片或文档。
                dao.deleteNonBinaryMessageParts(preservedBinaryMessageIds.toList())
            }
            persist(
                messages = messages,
                resolvedParts = resolvedParts,
                sequenceOffsets = preservedBinaryMessageIds.associateWith { 1 },
            )
        }
    }

    suspend fun deleteByConversationIds(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        val dao = database.conversationDao()
        val messageIds = dao.getMessageIdsByConversationIds(conversationIds)
        if (messageIds.isNotEmpty()) dao.deleteMessageParts(messageIds)
        dao.deleteMessagesByConversationIds(conversationIds)
    }

    private suspend fun persist(
        messages: List<Pair<String, StoredConversationMessage>>,
        resolvedParts: Map<String, List<MessagePart>> = messages.associate { (_, message) -> message.id to message.resolvedParts() },
        sequenceOffsets: Map<String, Int> = emptyMap(),
    ) {
        val dao = database.conversationDao()
        dao.insertMessages(messages.map { (conversationId, message) -> message.toEntity(conversationId) })
        dao.insertMessageParts(messages.flatMap { (_, message) ->
            val offset = sequenceOffsets[message.id] ?: 0
            resolvedParts.getValue(message.id).mapIndexed { index, part -> part.toEntity(message.id, index + offset) }
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
            reasoningSource = null,
            providerItemId = null,
            summaryIndex = null,
            mimeType = null,
            fileName = null,
            binaryData = null,
            imageDetail = null,
        )
        is MessagePart.Reasoning -> MessagePartEntity(
            id = id,
            messageId = messageId,
            sequence = sequence,
            type = TYPE_REASONING,
            text = text,
            toolName = null,
            argumentsJson = null,
            result = null,
            success = null,
            verificationStatus = null,
            memoryIdsJson = null,
            reasoningSource = source.name,
            providerItemId = providerItemId,
            summaryIndex = summaryIndex,
            mimeType = null,
            fileName = null,
            binaryData = null,
            imageDetail = null,
        )
        is MessagePart.Image -> MessagePartEntity(
            id = id,
            messageId = messageId,
            sequence = sequence,
            type = TYPE_IMAGE,
            text = null,
            toolName = null,
            argumentsJson = null,
            result = null,
            success = null,
            verificationStatus = null,
            memoryIdsJson = null,
            reasoningSource = null,
            providerItemId = null,
            summaryIndex = null,
            mimeType = attachment.mimeType,
            fileName = attachment.fileName,
            binaryData = attachment.copyData(),
            imageDetail = attachment.detail.name,
        )
        is MessagePart.Document -> MessagePartEntity(
            id = id,
            messageId = messageId,
            sequence = sequence,
            type = TYPE_DOCUMENT,
            text = null,
            toolName = null,
            argumentsJson = null,
            result = null,
            success = null,
            verificationStatus = null,
            memoryIdsJson = null,
            reasoningSource = null,
            providerItemId = null,
            summaryIndex = null,
            mimeType = attachment.mimeType,
            fileName = attachment.fileName,
            binaryData = attachment.copyData(),
            imageDetail = null,
            documentExtractedText = attachment.extractedText,
            documentPageCount = attachment.pageCount,
            documentDetail = attachment.detail.name,
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
            knowledgeReferencesJson = KnowledgeReferenceCodec.encodeToString(knowledgeReferences.distinct()),
            reasoningSource = null,
            providerItemId = null,
            summaryIndex = null,
            mimeType = null,
            fileName = null,
            binaryData = null,
            imageDetail = null,
        )
    }

    private fun MessagePartEntity.toMessagePartOrNull(): MessagePart? = runCatching {
        when (type) {
            TYPE_TEXT -> MessagePart.Text(id = id, text = requireNotNull(text))
            TYPE_REASONING -> MessagePart.Reasoning(
                id = id,
                text = requireNotNull(text),
                source = MessageReasoningSource.valueOf(requireNotNull(reasoningSource)),
                providerItemId = providerItemId,
                summaryIndex = requireNotNull(summaryIndex),
            )
            TYPE_IMAGE -> MessagePart.Image(
                id = id,
                attachment = ImageAttachmentPolicy.create(
                    fileName = requireNotNull(fileName),
                    mimeType = requireNotNull(mimeType),
                    data = requireNotNull(binaryData),
                    detail = MessageImageDetail.valueOf(requireNotNull(imageDetail)),
                ),
            )
            TYPE_DOCUMENT -> MessagePart.Document(
                id = id,
                attachment = DocumentAttachmentPolicy.create(
                    fileName = requireNotNull(fileName),
                    mimeType = requireNotNull(mimeType),
                    data = requireNotNull(binaryData),
                    pageCount = documentPageCount,
                    extractedText = documentExtractedText,
                    detail = MessageDocumentDetail.valueOf(requireNotNull(documentDetail)),
                ),
            )
            TYPE_TOOL -> MessagePart.Tool(
                id = id,
                toolName = requireNotNull(toolName),
                arguments = requireNotNull(argumentsJson).decodeStringMap(),
                result = requireNotNull(result),
                success = requireNotNull(success),
                verificationStatus = MessageToolVerificationStatus.valueOf(requireNotNull(verificationStatus)),
                memoryIdsUsed = RoomJson.decodeStringList(requireNotNull(memoryIdsJson)),
                knowledgeReferences = KnowledgeReferenceCodec.decode(knowledgeReferencesJson),
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
        private const val TYPE_REASONING = "REASONING"
        private const val TYPE_IMAGE = "IMAGE"
        private const val TYPE_DOCUMENT = "DOCUMENT"
        private const val TYPE_TOOL = "TOOL"
    }
}
