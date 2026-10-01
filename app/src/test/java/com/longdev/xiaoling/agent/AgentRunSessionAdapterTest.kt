package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentRunState
import com.longdev.xiaoling.shared.agent.SharedAgentRunSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun activeCancelRequestMapsToSharedCancellationStateBeforeRoomTerminalStatus() {
        val record = AgentRunRecord(
            id = "run-cancel-requested",
            conversationId = "conversation-cancel-requested",
            userMessageId = "message-cancel-requested",
            goal = "保留取消请求",
            status = AgentRunStatus.WAITING_APPROVAL,
            result = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 2L,
            completedAt = null,
            cancelRequestedAt = 2L,
            cancelRequestedReason = "用户停止 Agent 任务",
        )

        assertEquals(SharedAgentRunState.CANCEL_REQUESTED, record.toSharedRunState())
        assertEquals(
            SharedAgentRunState.CANCEL_REQUESTED,
            SharedAgentRunSession.restore(
                AgentRunSnapshot(record, emptyList(), emptyList()).toSharedRunSnapshot(),
            ).state,
        )
    }

    @Test
    fun roomSnapshotProjectsOrderedEventsIntoRestorableSharedSnapshot() {
        val roomSnapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = "run-attach",
                conversationId = "conversation-attach",
                userMessageId = "message-attach",
                goal = "恢复审批后的运行上下文",
                status = AgentRunStatus.COMPLETED,
                result = "已完成",
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 4L,
                completedAt = 4L,
                rootRunId = "run-root",
                parentRunId = "run-parent",
            ),
            steps = emptyList(),
            events = listOf(
                RunEventRecord("event-1", "run-attach", "run.created", "Run 已创建", 1L),
                RunEventRecord("event-2", "run-attach", "approval.requested", "等待用户确认", 2L),
                RunEventRecord("event-3", "run-attach", "run.status", "COMPLETED", 4L),
            ),
        )

        val sharedSnapshot = roomSnapshot.toSharedRunSnapshot()
        val restored = SharedAgentRunSession.restore(sharedSnapshot)

        assertEquals("run-root", sharedSnapshot.identity.rootRunId)
        assertEquals("run-parent", sharedSnapshot.identity.parentRunId)
        assertEquals(3L, sharedSnapshot.eventSequence)
        assertEquals(listOf(1L, 2L, 3L), sharedSnapshot.events.map { it.sequence })
        assertEquals(SharedAgentRunState.COMPLETED, restored.state)
        assertEquals(sharedSnapshot.events, restored.snapshot().events)
    }

    @Test
    fun cancelledRoomRunKeepsDurableCancelRequestInSharedSnapshot() {
        val roomSnapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = "run-cancelled",
                conversationId = "conversation-cancelled",
                userMessageId = "message-cancelled",
                goal = "保留取消意图",
                status = AgentRunStatus.CANCELLED,
                result = null,
                errorMessage = "用户停止 Agent 任务",
                createdAt = 1L,
                updatedAt = 3L,
                completedAt = 3L,
                cancelRequestedAt = 2L,
                cancelRequestedReason = "用户停止 Agent 任务",
            ),
            steps = emptyList(),
            events = emptyList(),
        )

        val sharedSnapshot = roomSnapshot.toSharedRunSnapshot()

        assertTrue(sharedSnapshot.cancelRequested)
        assertEquals(SharedAgentRunState.CANCELLED, SharedAgentRunSession.restore(sharedSnapshot).state)
    }
}
