package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentBackgroundApprovalRequiredException
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentRunSummary
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.storage.ScheduledWorkflowClaim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledWorkflowOrchestratorTest {
    @Test
    fun completionLinksAgentRunThenSettlesAndNotifies() = runTest {
        val claim = testClaim()
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(
            claim = claim,
            runAgent = { _, _, onAgentRunId ->
                onAgentRunId("agent-run-1")
                completedSummary()
            },
            events = events,
        )

        orchestrator.execute(claim.task.id)

        assertEquals(
            listOf("agent:1:agent-run-1", "step:1:COMPLETED", "settle:COMPLETED", "notify:COMPLETED"),
            events,
        )
    }

    @Test
    fun multipleStepsRunSequentiallyAndNotifyOnce() = runTest {
        val claim = testClaim(stepCount = 3)
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(
            claim = claim,
            runAgent = { _, step, onAgentRunId ->
                onAgentRunId("agent-run-${step.sequence}")
                completedSummary(step.sequence)
            },
            events = events,
        )

        orchestrator.execute(claim.task.id)

        assertEquals(
            listOf(
                "agent:1:agent-run-1",
                "step:1:COMPLETED",
                "agent:2:agent-run-2",
                "step:2:COMPLETED",
                "agent:3:agent-run-3",
                "step:3:COMPLETED",
                "settle:COMPLETED",
                "notify:COMPLETED",
            ),
            events,
        )
    }

    @Test
    fun approvalRequirementSettlesBlockedAndNotifiesWithoutCompletion() = runTest {
        val claim = testClaim()
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(
            claim = claim,
            runAgent = { _, _, _ -> throw AgentBackgroundApprovalRequiredException("notes.create") },
            events = events,
        )

        orchestrator.execute(claim.task.id)

        assertEquals(listOf("settle:BLOCKED", "notify:BLOCKED"), events)
    }

    @Test
    fun runtimeFailureSettlesFailedAndNotifies() = runTest {
        val claim = testClaim()
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(
            claim = claim,
            runAgent = { _, _, _ -> error("上游不可用") },
            events = events,
        )

        orchestrator.execute(claim.task.id)

        assertEquals(listOf("settle:FAILED", "notify:FAILED"), events)
    }

    @Test
    fun cancellationSettlesAndNotifiesBeforePropagating() = runTest {
        val claim = testClaim()
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(
            claim = claim,
            runAgent = { _, _, _ -> throw CancellationException("stop") },
            events = events,
        )

        val error = runCatching { orchestrator.execute(claim.task.id) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(listOf("settle:CANCELLED", "notify:CANCELLED"), events)
    }

    @Test
    fun rejectedClaimDoesNotRunOrNotifySuccess() = runTest {
        val events = mutableListOf<String>()
        val orchestrator = ScheduledWorkflowOrchestrator(
            claimTask = { null },
            runAgent = { _, _, _ -> error("领取失败后不应执行") },
            markAgentRunStarted = { _, _, _ -> error("领取失败后不应关联 Agent Run") },
            completeStep = { _, _, _ -> error("领取失败后不应完成步骤") },
            settle = { _, _ -> error("领取失败后不应写执行终态") },
            notify = { _, _, _ -> error("领取失败后不应发送成功通知") },
            onClaimRejected = { events += "claim-rejected:$it" },
        )

        orchestrator.execute("task-missing")

        assertEquals(listOf("claim-rejected:task-missing"), events)
    }

    private fun orchestrator(
        claim: ScheduledWorkflowClaim,
        runAgent: suspend (ScheduledWorkflowClaim, WorkflowStepRecord, suspend (String) -> Unit) -> AgentRunSummary,
        events: MutableList<String>,
    ) = ScheduledWorkflowOrchestrator(
        claimTask = { claim },
        runAgent = runAgent,
        markAgentRunStarted = { _, step, agentRunId -> events += "agent:${step.sequence}:$agentRunId" },
        completeStep = { _, step, summary ->
            events += "step:${step.sequence}:COMPLETED"
            step.copy(
                status = WorkflowStepStatus.COMPLETED,
                result = summary.responseText,
                outputSnapshot = summary.responseText,
            )
        },
        settle = { current, outcome ->
            val status = when (outcome) {
                is ScheduledExecutionOutcome.Completed -> ScheduledTaskStatus.COMPLETED
                is ScheduledExecutionOutcome.Blocked -> ScheduledTaskStatus.BLOCKED
                is ScheduledExecutionOutcome.Failed -> ScheduledTaskStatus.FAILED
                is ScheduledExecutionOutcome.Cancelled -> ScheduledTaskStatus.CANCELLED
            }
            events += "settle:${status.name}"
            current.task.copy(status = status)
        },
        notify = { _, task, _ -> events += "notify:${task.status.name}" },
        onClaimRejected = { events += "claim-rejected:$it" },
    )

    private fun testClaim(stepCount: Int = 1): ScheduledWorkflowClaim {
        val task = ScheduledTaskRecord(
            id = "scheduled-task-1",
            workflowId = "workflow-1",
            type = ScheduledTaskType.ONE_TIME,
            scheduleId = null,
            status = ScheduledTaskStatus.RUNNING,
            plannedAt = 1_000L,
            workRequestId = "work-request-1",
            workflowRunId = "workflow-run-1",
            actualStartedAt = 1_100L,
            completedAt = null,
            errorMessage = null,
            createdAt = 900L,
            updatedAt = 1_100L,
        )
        val workflow = WorkflowRecord("workflow-1", "读取时间", "读取当前时间", true, 800L, 800L)
        val run = WorkflowRunRecord(
            id = "workflow-run-1",
            workflowId = workflow.id,
            trigger = WorkflowTrigger.SCHEDULED,
            scheduledTaskId = task.id,
            plannedAt = task.plannedAt,
            conversationId = "conversation-1",
            agentRunId = null,
            status = WorkflowRunStatus.QUEUED,
            result = null,
            errorMessage = null,
            createdAt = 1_100L,
            startedAt = 1_100L,
            completedAt = null,
        )
        val steps = (1..stepCount).map { sequence ->
            WorkflowStepRecord(
                id = "workflow-step-$sequence",
                workflowRunId = run.id,
                sequence = sequence,
                type = "AGENT_RUN",
                status = WorkflowStepStatus.PENDING,
                title = "后台步骤 $sequence",
                detail = "目标 $sequence",
                agentRunId = null,
                result = null,
                errorMessage = null,
                createdAt = 1_100L,
                startedAt = null,
                completedAt = null,
                definitionStepId = "definition-$sequence",
                idempotencyKey = "definition-$sequence",
                inputSnapshot = "{}",
            )
        }
        return ScheduledWorkflowClaim(task, workflow, WorkflowRunDetail(run, steps), "message-1")
    }

    private fun completedSummary(sequence: Int = 1) = AgentRunSummary(
        runId = "agent-run-$sequence",
        status = AgentRunStatus.COMPLETED,
        responseText = "步骤 $sequence 完成",
        verifiedContext = VerifiedAgentContext(
            runId = "agent-run-$sequence",
            toolName = "app.current_time",
            arguments = emptyMap(),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "当前时间：12:00",
        ),
    )
}
