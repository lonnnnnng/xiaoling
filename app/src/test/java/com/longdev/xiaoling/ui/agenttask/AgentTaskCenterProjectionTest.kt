package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentEventTypes
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskCenterProjectionTest {
    @Test
    fun projectKeepsHistoryOrderAndMarksSelectedAndRetryingRuns() {
        val first = runDetail("run-1", AgentRunStatus.COMPLETED)
        val second = runDetail("run-2", AgentRunStatus.FAILED)
        val pending = AgentRetryConfirmationUiState(
            runId = second.snapshot.run.id,
            goal = second.snapshot.run.goal,
            evidenceCode = AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
            evidenceFingerprint = "fingerprint-2",
        )

        val result = AgentTaskCenterProjection.project(
            loading = true,
            error = "读取失败",
            history = listOf(first, second),
            selectedRunId = first.snapshot.run.id,
            retryingRunId = second.snapshot.run.id,
            pendingRetryConfirmation = pending,
        )

        assertTrue(result.loading)
        assertEquals("读取失败", result.error)
        assertEquals(listOf("run-1", "run-2"), result.runs.map { it.detail.snapshot.run.id })
        assertTrue(result.runs.first().selected)
        assertFalse(result.runs.first().retrying)
        assertFalse(result.runs.last().selected)
        assertTrue(result.runs.last().retrying)
        assertEquals(pending, result.pendingRetryConfirmation)
    }

    @Test
    fun projectLinksRetryToKnownSourceAndSourceToLatestRetry() {
        val source = runDetail("run-source", AgentRunStatus.FAILED, createdAt = 1L)
        val olderRetry = runDetail(
            id = "run-retry-old",
            status = AgentRunStatus.CANCELLED,
            createdAt = 2L,
            retryOfRunId = source.snapshot.run.id,
        )
        val latestRetry = runDetail(
            id = "run-retry-latest",
            status = AgentRunStatus.QUEUED,
            createdAt = 3L,
            retryOfRunId = source.snapshot.run.id,
        )

        val result = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(latestRetry, olderRetry, source),
            selectedRunId = source.snapshot.run.id,
            retryingRunId = null,
        )

        assertEquals("run-retry-latest", result.runs.last().linkedRetryRunNavigationId)
        assertEquals("run-source", result.runs.first().sourceRunNavigationId)
    }

    @Test
    fun projectDoesNotGuessMissingSourceOrTiedLatestRetry() {
        val source = runDetail("run-source", AgentRunStatus.FAILED, createdAt = 1L)
        val firstRetry = runDetail(
            id = "run-retry-1",
            status = AgentRunStatus.QUEUED,
            createdAt = 2L,
            retryOfRunId = source.snapshot.run.id,
        )
        val secondRetry = runDetail(
            id = "run-retry-2",
            status = AgentRunStatus.QUEUED,
            createdAt = 2L,
            retryOfRunId = source.snapshot.run.id,
        )
        val duplicateRetry = runDetail(
            id = "run-retry-1",
            status = AgentRunStatus.QUEUED,
            createdAt = 4L,
            retryOfRunId = source.snapshot.run.id,
        )
        val orphanRetry = runDetail(
            id = "run-retry-orphan",
            status = AgentRunStatus.QUEUED,
            createdAt = 3L,
            retryOfRunId = "run-trimmed",
        )

        val result = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(orphanRetry, duplicateRetry, secondRetry, firstRetry, source),
            selectedRunId = null,
            retryingRunId = null,
        )

        assertEquals(null, result.runs.first().sourceRunNavigationId)
        assertEquals(null, result.runs.last().linkedRetryRunNavigationId)
    }

    @Test
    fun projectExposesParentChildLineageWithoutGuessingMissingParents() {
        val parent = runDetail("run-parent", AgentRunStatus.THINKING, createdAt = 1L)
        val child = runDetail(
            id = "run-child",
            status = AgentRunStatus.COMPLETED,
            createdAt = 2L,
            rootRunId = parent.snapshot.run.id,
            parentRunId = parent.snapshot.run.id,
        )
        val orphanChild = runDetail(
            id = "run-orphan-child",
            status = AgentRunStatus.FAILED,
            createdAt = 3L,
            rootRunId = "run-missing-parent",
            parentRunId = "run-missing-parent",
        )

        val result = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(orphanChild, child, parent),
            selectedRunId = null,
            retryingRunId = null,
        )

        val projectedParent = result.runs.single { it.detail.snapshot.run.id == parent.snapshot.run.id }
        val projectedChild = result.runs.single { it.detail.snapshot.run.id == child.snapshot.run.id }
        val projectedOrphan = result.runs.single { it.detail.snapshot.run.id == orphanChild.snapshot.run.id }
        assertEquals(listOf(child.snapshot.run.id), projectedParent.childRunNavigationIds)
        assertEquals(parent.snapshot.run.id, projectedChild.parentRunNavigationId)
        assertEquals(1, projectedChild.lineageDepth)
        assertTrue(projectedOrphan.parentRunNavigationId == null)
        assertEquals(0, projectedOrphan.lineageDepth)
    }

    @Test
    fun projectDistinguishesTimeoutFromBudgetExhaustion() {
        val timeout = runDetail(
            id = "run-timeout",
            status = AgentRunStatus.BUDGET_EXHAUSTED,
            events = listOf(
                event(
                    runId = "run-timeout",
                    type = AgentEventTypes.RUN_TIMEOUT,
                    message = "执行预算已耗尽：工具调用超时",
                    metadata = RunEventMetadata.Reason("工具调用超时"),
                ),
            ),
        )
        val budgetExhausted = runDetail(
            id = "run-budget",
            status = AgentRunStatus.BUDGET_EXHAUSTED,
        )

        val result = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(timeout, budgetExhausted),
            selectedRunId = null,
            retryingRunId = null,
        )

        val timeoutUi = result.runs.first { it.detail.snapshot.run.id == "run-timeout" }
        val budgetUi = result.runs.first { it.detail.snapshot.run.id == "run-budget" }
        assertEquals(AgentTaskCenterTerminalReason.TIMEOUT, timeoutUi.terminalReason)
        assertEquals("执行预算已耗尽：工具调用超时", timeoutUi.timeoutReason)
        assertEquals(AgentTaskCenterTerminalReason.BUDGET_EXHAUSTED, budgetUi.terminalReason)
        assertEquals(null, budgetUi.timeoutReason)
    }

    @Test
    fun projectSummarizesCompleteChildrenOnlyWhenExpectedCountIsSatisfied() {
        val parent = runDetail(
            id = "run-parent-complete",
            status = AgentRunStatus.COMPLETED,
            events = listOf(
                event(
                    runId = "run-parent-complete",
                    type = AgentEventTypes.MULTI_AGENT_CHILDREN_EXPECTED,
                    message = "已冻结只读子 Agent 数量：1",
                    metadata = RunEventMetadata.ChildRunExpectation(1),
                ),
            ),
        )
        val child = runDetail(
            id = "run-child-complete",
            status = AgentRunStatus.COMPLETED,
            rootRunId = parent.snapshot.run.id,
            parentRunId = parent.snapshot.run.id,
        )

        val projected = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(child, parent),
            selectedRunId = null,
            retryingRunId = null,
        ).runs.single { it.detail.snapshot.run.id == parent.snapshot.run.id }

        assertEquals(AgentChildSummaryState.COMPLETE, projected.childSummary.state)
        assertEquals(1, projected.childSummary.expectedCount)
        assertEquals(1, projected.childSummary.completedCount)
        assertEquals(0, projected.childSummary.unknownCount)
    }

    @Test
    fun projectMarksMixedChildOutcomesAsPartial() {
        val parent = runDetail(
            id = "run-parent-partial",
            status = AgentRunStatus.COMPLETED,
            events = listOf(
                event(
                    runId = "run-parent-partial",
                    type = AgentEventTypes.MULTI_AGENT_CHILDREN_EXPECTED,
                    message = "已冻结只读子 Agent 数量：2",
                    metadata = RunEventMetadata.ChildRunExpectation(2),
                ),
            ),
        )
        val completed = runDetail(
            id = "run-child-success",
            status = AgentRunStatus.COMPLETED,
            rootRunId = parent.snapshot.run.id,
            parentRunId = parent.snapshot.run.id,
        )
        val failed = runDetail(
            id = "run-child-failed",
            status = AgentRunStatus.FAILED,
            rootRunId = parent.snapshot.run.id,
            parentRunId = parent.snapshot.run.id,
        )

        val projected = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(failed, completed, parent),
            selectedRunId = null,
            retryingRunId = null,
        ).runs.single { it.detail.snapshot.run.id == parent.snapshot.run.id }

        assertEquals(AgentChildSummaryState.PARTIAL, projected.childSummary.state)
        assertEquals(1, projected.childSummary.completedCount)
        assertEquals(1, projected.childSummary.failedCount)
        assertEquals(0, projected.childSummary.activeCount)
    }

    @Test
    fun projectKeepsChildSummaryUnknownWhenExpectationOrHistoryIsIncomplete() {
        val withoutExpectation = runDetail(
            id = "run-parent-no-expectation",
            status = AgentRunStatus.COMPLETED,
        )
        val visibleChild = runDetail(
            id = "run-child-visible",
            status = AgentRunStatus.COMPLETED,
            rootRunId = withoutExpectation.snapshot.run.id,
            parentRunId = withoutExpectation.snapshot.run.id,
        )
        val truncatedParent = runDetail(
            id = "run-parent-truncated",
            status = AgentRunStatus.COMPLETED,
            events = listOf(
                event(
                    runId = "run-parent-truncated",
                    type = AgentEventTypes.MULTI_AGENT_CHILDREN_EXPECTED,
                    message = "已冻结只读子 Agent 数量：2",
                    metadata = RunEventMetadata.ChildRunExpectation(2),
                ),
            ),
        )
        val oneChild = runDetail(
            id = "run-child-one-of-two",
            status = AgentRunStatus.COMPLETED,
            rootRunId = truncatedParent.snapshot.run.id,
            parentRunId = truncatedParent.snapshot.run.id,
        )

        val result = AgentTaskCenterProjection.project(
            loading = false,
            error = null,
            history = listOf(oneChild, truncatedParent, visibleChild, withoutExpectation),
            selectedRunId = null,
            retryingRunId = null,
        )

        val noExpectationUi = result.runs.single { it.detail.snapshot.run.id == withoutExpectation.snapshot.run.id }
        val truncatedUi = result.runs.single { it.detail.snapshot.run.id == truncatedParent.snapshot.run.id }
        assertEquals(AgentChildSummaryState.UNKNOWN, noExpectationUi.childSummary.state)
        assertEquals(AgentChildSummaryState.UNKNOWN, truncatedUi.childSummary.state)
        assertEquals(1, truncatedUi.childSummary.unknownCount)
    }

    private fun runDetail(
        id: String,
        status: AgentRunStatus,
        createdAt: Long = 1L,
        retryOfRunId: String? = null,
        rootRunId: String? = null,
        parentRunId: String? = null,
        events: List<RunEventRecord> = emptyList(),
    ): AgentRunDetailRecord {
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = id,
                    conversationId = "conversation-1",
                    userMessageId = "message-$id",
                    goal = "goal-$id",
                    status = status,
                    result = null,
                    errorMessage = null,
                    createdAt = createdAt,
                    updatedAt = 2L,
                    completedAt = if (status == AgentRunStatus.COMPLETED) 3L else null,
                    retryOfRunId = retryOfRunId,
                    rootRunId = rootRunId,
                    parentRunId = parentRunId,
                ),
                steps = emptyList(),
                events = events,
            ),
            approvals = emptyList(),
        )
    }

    private fun event(
        runId: String,
        type: String,
        message: String,
        metadata: RunEventMetadata,
    ) = RunEventRecord(
        id = "$runId-$type",
        runId = runId,
        type = type,
        message = message,
        createdAt = 4L,
        metadata = metadata,
    )
}
