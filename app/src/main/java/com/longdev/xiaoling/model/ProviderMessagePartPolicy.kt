package com.longdev.xiaoling.model

object ProviderMessagePartPolicy {
    fun fromResponse(
        messageId: String,
        text: String,
        reasoningSummaries: List<ModelReasoningSummary>,
    ): List<MessagePart> {
        val reasoningParts = reasoningSummaries
            .filter { it.text.isNotBlank() && it.summaryIndex >= 0 }
            .distinctBy { it.providerItemId to it.summaryIndex }
            .mapIndexed { index, summary ->
                MessagePart.Reasoning(
                    id = "$messageId-reasoning-$index",
                    text = summary.text,
                    source = MessageReasoningSource.PROVIDER_SUMMARY,
                    providerItemId = summary.providerItemId,
                    summaryIndex = summary.summaryIndex,
                )
            }
        // long: Reasoning 是供应商给用户看的过程摘要，正文仍是消息兼容投影；固定先摘要后正文可让持久化顺序与 Responses output 语义一致。
        return reasoningParts + MessagePart.Text(id = "$messageId-text", text = text)
    }
}
