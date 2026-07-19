package com.longdev.xiaoling.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderMessagePartPolicyTest {
    @Test
    fun providerReasoningSummariesBecomeDeduplicatedReasoningPartsBeforeText() {
        val summaries = listOf(
            ModelReasoningSummary(providerItemId = "rs-1", summaryIndex = 0, text = "先核对事实。"),
            ModelReasoningSummary(providerItemId = "rs-1", summaryIndex = 0, text = "重复事件不应重复展示。"),
            ModelReasoningSummary(providerItemId = "rs-2", summaryIndex = 1, text = "再组织答案。"),
        )

        val parts = ProviderMessagePartPolicy.fromResponse(
            messageId = "message-provider",
            text = "最终答案",
            reasoningSummaries = summaries,
        )

        assertEquals(
            listOf(
                MessagePart.Reasoning(
                    id = "message-provider-reasoning-0",
                    text = "先核对事实。",
                    source = MessageReasoningSource.PROVIDER_SUMMARY,
                    providerItemId = "rs-1",
                    summaryIndex = 0,
                ),
                MessagePart.Reasoning(
                    id = "message-provider-reasoning-1",
                    text = "再组织答案。",
                    source = MessageReasoningSource.PROVIDER_SUMMARY,
                    providerItemId = "rs-2",
                    summaryIndex = 1,
                ),
                MessagePart.Text(id = "message-provider-text", text = "最终答案"),
            ),
            parts,
        )
    }
}
