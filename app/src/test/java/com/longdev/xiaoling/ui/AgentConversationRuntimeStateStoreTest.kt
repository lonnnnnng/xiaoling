package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.shared.agent.SharedAgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRuntimeStateStoreTest {
    @Test
    fun latestRunIsReplacedOnlyWithinItsConversation() {
        val store = AgentConversationRuntimeStateStore()
        val firstConversationRun = snapshot(runId = "run-1", conversationId = "conversation-1")
        val secondConversationRun = snapshot(runId = "run-2", conversationId = "conversation-2")
        val replacement = snapshot(runId = "run-3", conversationId = "conversation-1")

        store.rememberRun(firstConversationRun)
        store.rememberRun(secondConversationRun)
        store.rememberRun(replacement)

        assertEquals(replacement, store.stateFor("conversation-1").activeRun)
        assertEquals(secondConversationRun, store.stateFor("conversation-2").activeRun)
        assertNull(store.stateFor("missing").activeRun)
    }

    @Test
    fun approvalUpdateKeepsRunAndOtherConversationState() {
        val store = AgentConversationRuntimeStateStore()
        val run = snapshot(runId = "run-1", conversationId = "conversation-1")
        val otherRun = snapshot(runId = "run-2", conversationId = "conversation-2")
        val pending = approval(requestId = "approval-1", conversationId = "conversation-1")

        store.rememberRun(run)
        store.rememberRun(otherRun)
        store.rememberApproval(pending)
        store.rememberApproval(pending.copy(deciding = true))

        assertEquals(run, store.stateFor("conversation-1").activeRun)
        assertEquals(pending.copy(deciding = true), store.stateFor("conversation-1").pendingApproval)
        assertEquals(otherRun, store.stateFor("conversation-2").activeRun)
        assertNull(store.stateFor("conversation-2").pendingApproval)
    }

    @Test
    fun clearingApprovalKeepsConversationRun() {
        val store = AgentConversationRuntimeStateStore()
        val run = snapshot(runId = "run-1", conversationId = "conversation-1")
        store.rememberRun(run)
        store.rememberApproval(approval(requestId = "approval-1", conversationId = "conversation-1"))

        store.clearApproval("conversation-1")

        assertEquals(run, store.stateFor("conversation-1").activeRun)
        assertNull(store.stateFor("conversation-1").pendingApproval)
    }

    @Test
    fun clearingConversationRemovesItsRunAndApprovalOnly() {
        val store = AgentConversationRuntimeStateStore()
        val otherRun = snapshot(runId = "run-2", conversationId = "conversation-2")
        store.rememberRun(snapshot(runId = "run-1", conversationId = "conversation-1"))
        store.rememberApproval(approval(requestId = "approval-1", conversationId = "conversation-1"))
        store.rememberRun(otherRun)

        store.clearConversation("conversation-1")

        assertEquals(AgentConversationRuntimeState(), store.stateFor("conversation-1"))
        assertEquals(otherRun, store.stateFor("conversation-2").activeRun)
    }

    @Test
    fun freshConversationClearsReusedIdWithoutAffectingOtherConversations() {
        val store = AgentConversationRuntimeStateStore()
        val run = snapshot(runId = "run-1", conversationId = "conversation-1")
        val approval = approval(requestId = "approval-1", conversationId = "conversation-1")
        val otherRun = snapshot(runId = "run-2", conversationId = "conversation-2")
        store.rememberRun(run)
        store.rememberApproval(approval)
        store.rememberRun(otherRun)

        assertEquals(
            AgentConversationRuntimeState(),
            store.stateForSelection("conversation-1", restoreRuntimeState = false),
        )
        assertEquals(AgentConversationRuntimeState(), store.stateFor("conversation-1"))
        assertEquals(otherRun, store.stateFor("conversation-2").activeRun)
    }

    @Test
    fun attachingRecoveredRunRestoresSharedSessionProjection() {
        val store = AgentConversationRuntimeStateStore()
        val run = snapshot(
            runId = "run-recovered",
            conversationId = "conversation-recovered",
            status = AgentRunStatus.WAITING_APPROVAL,
            events = listOf(
                RunEventRecord("event-1", "run-recovered", "run.created", "Run 已创建", 1L),
                RunEventRecord("event-2", "run-recovered", "approval.requested", "等待用户确认", 2L),
            ),
        )

        assertTrue(store.attachRecoveredRun(run))

        val attached = store.stateFor("conversation-recovered")
        assertEquals(run, attached.activeRun)
        assertEquals(SharedAgentRunState.WAITING_APPROVAL, attached.sharedSessionSnapshot?.state)
        assertEquals(2L, attached.sharedSessionSnapshot?.eventSequence)
    }

    @Test
    fun attachingRunWithMixedEventChainFailsClosed() {
        val store = AgentConversationRuntimeStateStore()
        val run = snapshot(
            runId = "run-invalid",
            conversationId = "conversation-invalid",
            events = listOf(
                RunEventRecord("event-1", "other-run", "run.created", "错误事件", 1L),
            ),
        )

        assertFalse(store.attachRecoveredRun(run))
        assertEquals(AgentConversationRuntimeState(), store.stateFor("conversation-invalid"))
    }

    private fun snapshot(
        runId: String,
        conversationId: String,
        status: AgentRunStatus = AgentRunStatus.EXECUTING,
        events: List<RunEventRecord> = emptyList(),
    ) = AgentRunSnapshot(
        run = AgentRunRecord(
            id = runId,
            conversationId = conversationId,
            userMessageId = "message-$runId",
            goal = "goal-$runId",
            status = status,
            result = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 1L,
            completedAt = null,
        ),
        steps = emptyList(),
        events = events,
    )

    private fun approval(requestId: String, conversationId: String) = AgentApprovalUiState(
        requestId = requestId,
        runId = "run-$requestId",
        conversationId = conversationId,
        toolCallId = "call-$requestId",
        toolName = "device.snapshot",
        toolDescription = "观察当前屏幕",
        riskLabel = "低风险",
        arguments = emptyMap(),
        expiresAt = Long.MAX_VALUE,
    )
}
