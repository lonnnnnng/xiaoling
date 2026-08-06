package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRetryLaunchPolicyTest {
    @Test
    fun resolvesOnlyVerifiedCommittedTaskRetryFromConsistentLedger() {
        val detail = completedTaskRetryDetail()

        val launch = TaskRetryLaunchPolicy.resolve(detail)

        assertEquals("workflow-run-private", launch?.workflowRunId)
        assertEquals("每日回顾", launch?.taskName)
        assertNull(
            TaskRetryLaunchPolicy.resolve(
                detail.copy(
                    toolLedger = detail.toolLedger.copy(
                        results = detail.toolLedger.results.map { result -> result.copy(executorVerified = false) },
                    ),
                ),
            ),
        )
        assertNull(
            TaskRetryLaunchPolicy.resolve(
                detail.copy(
                    toolLedger = detail.toolLedger.copy(
                        results = detail.toolLedger.results.map { result ->
                            result.copy(
                                executionReceipt = result.executionReceipt?.copy(toolCallId = "tool-call-drifted"),
                            )
                        },
                    ),
                ),
            ),
        )
    }

    @Test
    fun acceptsOnlyQueuedLinkedWorkflowRunInTheSameConversation() {
        val request = TaskRetryLaunchRequest("workflow-run-private", "每日回顾")
        val detail = queuedWorkflowRetryDetail()

        assertTrue(TaskRetryLaunchPolicy.canStart(request, detail, "conversation-direct"))
        assertFalse(TaskRetryLaunchPolicy.canStart(request, detail.copy(run = detail.run.copy(status = WorkflowRunStatus.RUNNING)), "conversation-direct"))
        assertFalse(TaskRetryLaunchPolicy.canStart(request, detail.copy(run = detail.run.copy(retryOfWorkflowRunId = null)), "conversation-direct"))
        assertFalse(TaskRetryLaunchPolicy.canStart(request, detail, "conversation-other"))
        assertFalse(TaskRetryLaunchPolicy.canStart(request, detail.copy(steps = emptyList()), "conversation-direct"))
    }

    private fun completedTaskRetryDetail(): AgentRunDetailRecord {
        val runId = "agent-run-task-retry"
        val callId = "tool-call-task-retry"
        val arguments = mapOf("name" to "每日回顾")
        val receipt = ToolExecutionReceipt(
            toolCallId = callId,
            operationId = "workflow-run-private",
            idempotencyKey = callId,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val proposed = RunEventRecord(
            id = "event-proposed",
            runId = runId,
            type = "tool.call.proposed",
            message = "proposed",
            createdAt = 1L,
            metadata = RunEventMetadata.ToolCall(callId, "tasks.retry", ToolRisk.REQUIRES_APPROVAL, arguments),
        )
        val validated = RunEventRecord(
            id = "event-validated",
            runId = runId,
            type = "tool.call.validated",
            message = "validated",
            createdAt = 2L,
            metadata = RunEventMetadata.ToolCall(callId, "tasks.retry", ToolRisk.REQUIRES_APPROVAL, arguments),
        )
        val result = RunEventRecord(
            id = "event-result",
            runId = runId,
            type = "tool.result",
            message = "result",
            createdAt = 3L,
            metadata = RunEventMetadata.ToolResult(
                toolName = "tasks.retry",
                content = "任务已排队",
                durationMs = 10L,
                success = true,
                verified = true,
                toolCallId = callId,
                replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                executionReceipt = receipt,
            ),
        )
        val verified = RunEventRecord(
            id = "event-verified",
            runId = runId,
            type = "tool.verify",
            message = "verified",
            createdAt = 4L,
            metadata = RunEventMetadata.ToolVerification(
                toolName = "tasks.retry",
                status = ToolVerificationStatus.PASSED,
                toolCallId = callId,
            ),
        )
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = runId,
                    conversationId = "conversation-direct",
                    userMessageId = "message-direct",
                    goal = "重试每日回顾任务",
                    status = AgentRunStatus.COMPLETED,
                    result = "done",
                    errorMessage = null,
                    createdAt = 1L,
                    updatedAt = 5L,
                    completedAt = 5L,
                ),
                steps = emptyList(),
                events = listOf(proposed, validated, result, verified),
            ),
            approvals = emptyList(),
            toolLedger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = callId,
                        runId = runId,
                        toolName = "tasks.retry",
                        risk = ToolRisk.REQUIRES_APPROVAL,
                        arguments = arguments,
                        proposedEventId = proposed.id,
                        validatedEventId = validated.id,
                        createdAt = proposed.createdAt,
                        validatedAt = validated.createdAt,
                    ),
                ),
                results = listOf(
                    AgentToolResultRecord(
                        toolCallId = callId,
                        runId = runId,
                        eventId = result.id,
                        toolName = "tasks.retry",
                        content = "任务已排队",
                        success = true,
                        errorMessage = null,
                        durationMs = 10L,
                        executorVerified = true,
                        verificationStatus = ToolVerificationStatus.PASSED,
                        verifiedEventId = verified.id,
                        memoryIdsUsed = emptyList(),
                        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                        executionReceipt = receipt,
                        createdAt = result.createdAt,
                        verifiedAt = verified.createdAt,
                    ),
                ),
            ),
        )
    }

    private fun queuedWorkflowRetryDetail(): WorkflowRunDetail {
        val run = WorkflowRunRecord(
            id = "workflow-run-private",
            workflowId = "workflow-private",
            trigger = WorkflowTrigger.MANUAL,
            scheduledTaskId = null,
            plannedAt = null,
            conversationId = "conversation-direct",
            agentRunId = null,
            status = WorkflowRunStatus.QUEUED,
            result = null,
            errorMessage = null,
            createdAt = 1L,
            startedAt = null,
            completedAt = null,
            retryOfWorkflowRunId = "workflow-run-source",
        )
        return WorkflowRunDetail(
            run = run,
            steps = listOf(
                WorkflowStepRecord(
                    id = "workflow-step-private",
                    workflowRunId = run.id,
                    sequence = 1,
                    type = "AGENT_RUN",
                    status = WorkflowStepStatus.PENDING,
                    title = "步骤 1",
                    detail = "执行任务",
                    agentRunId = null,
                    result = null,
                    errorMessage = null,
                    createdAt = 1L,
                    startedAt = null,
                    completedAt = null,
                ),
            ),
        )
    }
}
