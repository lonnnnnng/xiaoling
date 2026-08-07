package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
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

    private fun runDetail(
        id: String,
        status: AgentRunStatus,
        createdAt: Long = 1L,
        retryOfRunId: String? = null,
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
                ),
                steps = emptyList(),
                events = emptyList(),
            ),
            approvals = emptyList(),
        )
    }
}
