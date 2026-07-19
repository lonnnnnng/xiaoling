package com.longdev.xiaoling.model

import com.longdev.xiaoling.knowledge.KnowledgeReference

sealed interface MessagePart {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
    ) : MessagePart

    data class Reasoning(
        override val id: String,
        val text: String,
        val source: MessageReasoningSource,
        val providerItemId: String?,
        val summaryIndex: Int = 0,
    ) : MessagePart

    data class Image(
        override val id: String,
        val attachment: ImageAttachment,
    ) : MessagePart

    data class Document(
        override val id: String,
        val attachment: DocumentAttachment,
    ) : MessagePart

    data class Tool(
        override val id: String,
        val toolName: String,
        val arguments: Map<String, String>,
        val result: String,
        val success: Boolean,
        val verificationStatus: MessageToolVerificationStatus,
        val memoryIdsUsed: List<String>,
        val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    ) : MessagePart
}

enum class MessageReasoningSource {
    PROVIDER_SUMMARY,
}

// long: MessagePart 是长期存储和展示契约，独立枚举避免运行时 Agent 状态改名时直接破坏历史消息解码；二者只在可信投影边界显式映射。
enum class MessageToolVerificationStatus {
    VERIFIED,
    FAILED,
    READABLE_ONLY,
}
