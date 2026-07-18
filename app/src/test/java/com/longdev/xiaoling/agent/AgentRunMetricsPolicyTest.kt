package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunMetricsPolicyTest {
    @Test
    fun completedRunMetricsComeFromPersistedRunAndStepAudit() {
        val detail = detail(
            id = "run-completed",
            status = AgentRunStatus.COMPLETED,
            createdAt = 1_000L,
            updatedAt = 6_100L,
            completedAt = 6_100L,
            stepTypes = listOf("llm.plan", AgentStepTypes.TOOL_EXECUTE, AgentStepTypes.TOOL_VERIFY, "llm.plan", "llm.summarize"),
            approvalCount = 1,
        )

        val metrics = AgentRunMetricsPolicy.summarizeRun(detail, nowMs = 99_000L)

        assertEquals(5_100L, metrics.elapsedMs)
        assertEquals(3, metrics.modelCallCount)
        assertEquals(1, metrics.toolCallCount)
        assertEquals(1, metrics.approvalRequestCount)
    }

    @Test
    fun historyRatesAndAverageUseOnlyTerminalRuns() {
        val details = listOf(
            detail(
                id = "run-success",
                status = AgentRunStatus.COMPLETED,
                createdAt = 1_000L,
                updatedAt = 6_000L,
                completedAt = 6_000L,
                stepTypes = listOf("llm.plan", AgentStepTypes.TOOL_EXECUTE, "llm.summarize"),
            ),
            detail(
                id = "run-failed",
                status = AgentRunStatus.FAILED,
                createdAt = 2_000L,
                updatedAt = 5_000L,
                completedAt = 5_000L,
                stepTypes = listOf("llm.plan"),
            ),
            detail(
                id = "run-active",
                status = AgentRunStatus.EXECUTING,
                createdAt = 4_000L,
                updatedAt = 5_000L,
                completedAt = null,
                stepTypes = listOf("llm.plan", AgentStepTypes.TOOL_EXECUTE),
            ),
        )

        val metrics = AgentRunMetricsPolicy.summarizeHistory(details, nowMs = 10_000L)

        assertEquals(3, metrics.runCount)
        assertEquals(2, metrics.terminalRunCount)
        assertEquals(1, metrics.completedRunCount)
        assertEquals(1, metrics.nonSuccessfulRunCount)
        assertEquals(50, metrics.successRatePercent)
        assertEquals(4_000L, metrics.averageElapsedMs)
        assertEquals(4, metrics.modelCallCount)
        assertEquals(2, metrics.toolCallCount)
    }

    @Test
    fun activeOnlyHistoryDoesNotInventTerminalRates() {
        val metrics = AgentRunMetricsPolicy.summarizeHistory(
            listOf(
                detail(
                    id = "run-active",
                    status = AgentRunStatus.THINKING,
                    createdAt = 2_000L,
                    updatedAt = 3_000L,
                    completedAt = null,
                    stepTypes = listOf("llm.plan"),
                ),
            ),
            nowMs = 5_000L,
        )

        assertNull(metrics.successRatePercent)
        assertNull(metrics.averageElapsedMs)
    }

    private fun detail(
        id: String,
        status: AgentRunStatus,
        createdAt: Long,
        updatedAt: Long,
        completedAt: Long?,
        stepTypes: List<String>,
        approvalCount: Int = 0,
    ): AgentRunDetailRecord {
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = id,
                    conversationId = "conversation-$id",
                    userMessageId = "message-$id",
                    goal = "指标测试 $id",
                    status = status,
                    result = null,
                    errorMessage = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    completedAt = completedAt,
                ),
                steps = stepTypes.mapIndexed { index, type ->
                    AgentStepRecord(
                        id = "step-$id-$index",
                        runId = id,
                        sequence = index + 1,
                        type = type,
                        status = AgentStepStatus.COMPLETED,
                        title = type,
                        detail = type,
                        createdAt = createdAt + index,
                        completedAt = completedAt,
                    )
                },
                events = emptyList(),
            ),
            approvals = List(approvalCount) { index -> approval(id, index) },
        )
    }

    private fun approval(runId: String, index: Int): ApprovalRequestRecord {
        return ApprovalRequestRecord(
            id = "approval-$runId-$index",
            runId = runId,
            conversationId = "conversation-$runId",
            toolCallId = "tool-call-$runId-$index",
            toolName = "notes.create",
            toolDescription = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = emptyMap(),
            status = ApprovalRequestStatus.APPROVED,
            decisionReason = "测试批准",
            createdAt = 1L,
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = 2L,
        )
    }
}
