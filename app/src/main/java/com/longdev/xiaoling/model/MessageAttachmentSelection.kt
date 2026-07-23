package com.longdev.xiaoling.model

data class MessageAttachmentSelection(
    val image: ImageAttachment? = null,
    val document: DocumentAttachment? = null,
) {
    private val hasAttachment: Boolean
        get() = image != null || document != null

    private val hasMixedAttachments: Boolean
        get() = image != null && document != null

    fun agentRejectionReason(apiMode: ApiMode): String? = when {
        hasMixedAttachments -> "单条消息只能携带一种附件，请保留图片或文档"
        hasAttachment && apiMode != ApiMode.RESPONSES ->
            "/agent 附件仅支持 Responses 模式，请切换到 Responses"
        else -> null
    }

    fun chatRejectionReason(apiMode: ApiMode): String? = when {
        hasMixedAttachments -> "单条消息只能携带一种附件，请保留图片或文档"
        hasAttachment && apiMode != ApiMode.RESPONSES ->
            "当前 Chat Completions 模式不支持附件，请切换到 Responses"
        else -> null
    }

    fun toUserMessageParts(messageId: String, text: String): List<MessagePart> {
        require(!hasMixedAttachments) { "单条消息只能携带一种附件" }
        val attachmentPart = image
            ?.let { MessagePart.Image(id = "$messageId-image-0", attachment = it) }
            ?: document?.let { MessagePart.Document(id = "$messageId-document-0", attachment = it) }
            ?: return emptyList()
        // long: 发送入口和 Room 恢复都依赖附件在 Text 之前的稳定顺序；在领域边界统一构造，避免 UI 分支产生 Image/Document 混合或不同序列。
        return listOf(
            attachmentPart,
            MessagePart.Text(id = "$messageId-text", text = text),
        )
    }
}
