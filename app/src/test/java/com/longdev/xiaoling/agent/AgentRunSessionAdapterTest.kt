package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunSessionAdapterTest {
    @Test
    fun legacyRunUsesOwnIdAsSharedRootWithoutProcessSessionPersistence() {
        val record = AgentRunRecord(
            id = "run-legacy",
            conversationId = "conversation-1",
            userMessageId = "message-1",
            goal = "读取当前时间",
            status = AgentRunStatus.THINKING,
            result = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 2L,
            completedAt = null,
        )

        assertEquals("run-legacy", record.toSharedRunIdentity().rootRunId)
        assertNull(record.toSharedRunIdentity().parentRunId)
        assertEquals(SharedAgentRunState.RUNNING, record.status.toSharedRunState())
    }

    @Test
    fun retryRunPreservesExplicitLineage() {
        val record = AgentRunRecord(
            id = "run-retry",
            conversationId = "conversation-1",
            userMessageId = "message-2",
            goal = "重试读取当前时间",
            status = AgentRunStatus.WAITING_APPROVAL,
            result = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 2L,
            completedAt = null,
            retryOfRunId = "run-original",
            rootRunId = "run-root",
            parentRunId = "run-original",
        )

        assertEquals("run-root", record.toSharedRunIdentity().rootRunId)
        assertEquals("run-original", record.toSharedRunIdentity().parentRunId)
        assertEquals(SharedAgentRunState.WAITING_APPROVAL, record.status.toSharedRunState())
    }

    @Test
    fun blockedRunMapsToTerminalFailureState() {
        assertEquals(SharedAgentRunState.FAILED, AgentRunStatus.BLOCKED.toSharedRunState())
    }
}
