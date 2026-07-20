package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionBudgetEvidencePolicyTest {
    @Test
    fun runWithoutBudgetEventsRemainsLegacyCompatible() {
        assertEquals(
            AgentExecutionBudgetEvidenceAssessment.Legacy,
            AgentExecutionBudgetEvidencePolicy.read(detail()),
        )
    }

    @Test
    fun monotonicBudgetEventsRestoreLatestSnapshot() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0),
                budgetEvent(2, 100, 20),
                budgetEvent(3, 100, 70),
            ),
        )

        assertEquals(
            AgentExecutionBudgetEvidenceAssessment.Available(
                AgentExecutionBudgetSnapshot(totalTimeoutMs = 100, consumedMs = 70),
            ),
            assessment,
        )
    }

    @Test
    fun budgetEventWithoutStructuredMetadataFailsClosed() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0).copy(metadata = null),
            ),
        )

        assertInvalidReason(assessment, "缺少结构化快照")
    }

    @Test
    fun firstBudgetEventMustStartAtZero() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(budgetEvent(1, 100, 20)),
        )

        assertInvalidReason(assessment, "缺少初始零值")
    }

    @Test
    fun totalBudgetCannotDriftWithinOneRun() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0),
                budgetEvent(2, 120, 20),
            ),
        )

        assertInvalidReason(assessment, "总额在同一 Run 内发生漂移")
    }

    @Test
    fun consumedBudgetCannotMoveBackwards() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0),
                budgetEvent(2, 100, 70),
                budgetEvent(3, 100, 20),
            ),
        )

        assertInvalidReason(assessment, "累计值发生回退")
    }

    @Test
    fun committedToolResultWithoutFollowingBudgetSnapshotFailsClosed() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0),
                toolResultEvent(2),
            ),
        )

        assertInvalidReason(assessment, "工具结果缺少后续执行预算快照")
    }

    @Test
    fun committedToolResultWithFollowingBudgetSnapshotRemainsRecoverable() {
        val assessment = AgentExecutionBudgetEvidencePolicy.read(
            detail(
                budgetEvent(1, 100, 0),
                toolResultEvent(2),
                budgetEvent(3, 100, 40),
            ),
        )

        assertEquals(
            AgentExecutionBudgetEvidenceAssessment.Available(
                AgentExecutionBudgetSnapshot(totalTimeoutMs = 100, consumedMs = 40),
            ),
            assessment,
        )
    }

    private fun assertInvalidReason(
        assessment: AgentExecutionBudgetEvidenceAssessment,
        expectedReason: String,
    ) {
        assertTrue(assessment is AgentExecutionBudgetEvidenceAssessment.Invalid)
        assertTrue((assessment as AgentExecutionBudgetEvidenceAssessment.Invalid).reason.contains(expectedReason))
    }

    private fun detail(vararg events: RunEventRecord): AgentRunDetailRecord {
        val run = AgentRunRecord(
            id = "run-budget-evidence",
            conversationId = "conversation-budget-evidence",
            userMessageId = "message-budget-evidence",
            goal = "恢复执行预算",
            status = AgentRunStatus.WAITING_APPROVAL,
            result = null,
            errorMessage = null,
            createdAt = 0,
            updatedAt = 0,
            completedAt = null,
        )
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(run, steps = emptyList(), events = events.toList()),
            approvals = emptyList(),
        )
    }

    private fun budgetEvent(sequence: Long, totalMs: Long, consumedMs: Long): RunEventRecord {
        return RunEventRecord(
            id = "event-budget-$sequence",
            runId = "run-budget-evidence",
            type = AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            message = "执行预算：$consumedMs/$totalMs",
            createdAt = sequence,
            metadata = RunEventMetadata.ExecutionBudget(totalMs, consumedMs),
        )
    }

    private fun toolResultEvent(sequence: Long): RunEventRecord {
        return RunEventRecord(
            id = "event-tool-result-$sequence",
            runId = "run-budget-evidence",
            type = "tool.result",
            message = "工具执行成功：notes.create",
            createdAt = sequence,
            metadata = RunEventMetadata.ToolResult(
                toolName = "notes.create",
                content = "已创建笔记",
                durationMs = 40,
                success = true,
                verified = true,
            ),
        )
    }
}
