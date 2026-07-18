package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecoveredAgentMessageTest {
    @Test
    fun missingAgentUserMessageIsRebuiltFromPersistedRun() {
        val messages = listOf(
            ChatMessage(
                id = "message-before",
                role = "assistant",
                text = "before",
                createdAt = 100L,
            ),
        )

        val recovered = messages.withRecoveredAgentUserMessage(run())

        assertEquals(listOf("message-before", "message-agent"), recovered.map(ChatMessage::id))
        assertEquals("/agent remember process rebuild marker", recovered.last().text)
        assertEquals(200L, recovered.last().createdAt)
    }

    @Test
    fun existingAgentUserMessageIsNotDuplicated() {
        val existing = ChatMessage(
            id = "message-agent",
            role = "user",
            text = "/agent original text",
            createdAt = 190L,
        )
        val messages = listOf(existing)

        val recovered = messages.withRecoveredAgentUserMessage(run())

        assertSame(messages, recovered)
        assertEquals("/agent original text", recovered.single().text)
    }

    private fun run() = AgentRunRecord(
        id = "run-recovery",
        conversationId = "conversation-recovery",
        userMessageId = "message-agent",
        goal = "remember process rebuild marker",
        status = AgentRunStatus.WAITING_APPROVAL,
        result = null,
        errorMessage = null,
        createdAt = 200L,
        updatedAt = 210L,
        completedAt = null,
    )
}
