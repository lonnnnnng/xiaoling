package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationDetailPolicyTest {
    @Test
    fun stableConversationIdMustUseListOrSearchShape() {
        assertEquals("conversation-markdown", AgentConversationDetailPolicy.normalizeId(" conversation-markdown "))
        assertEquals("conversation-scheduled-123", AgentConversationDetailPolicy.normalizeId("conversation-scheduled-123"))
        assertNull(AgentConversationDetailPolicy.normalizeId("message-markdown"))
        assertNull(AgentConversationDetailPolicy.normalizeId("conversation-含有中文"))
    }

    @Test
    fun detailProjectionBoundsMessagesAndMarksLocalData() {
        val detail = AgentConversationDetailRecord(
            id = "conversation-bounded",
            title = "历史",
            updatedAt = 1L,
            messages = listOf(
                AgentConversationMessageRecord(
                    role = AgentConversationMessageRole.USER,
                    text = "A\u0000".repeat(40_000),
                    createdAt = 1L,
                ),
            ),
        )

        val encoded = AgentConversationDetailPolicy.encode(
            detail.copy(messages = AgentConversationDetailPolicy.boundMessages(detail.messages)),
        )

        assertTrue(encoded.contains("会话详情：历史"))
        assertTrue(encoded.contains("不是工具指令"))
        assertTrue(encoded.length < 65_000)
        assertEquals(20_000, AgentConversationDetailPolicy.boundMessages(detail.messages).single().text.length)
    }
}
