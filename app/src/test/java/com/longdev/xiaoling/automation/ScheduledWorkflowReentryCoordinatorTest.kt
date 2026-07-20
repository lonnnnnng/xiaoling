package com.longdev.xiaoling.automation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledWorkflowReentryCoordinatorTest {
    @Test
    fun scheduledTaskDoesNotEnterReentryRecovery() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            task = scheduledTask(ScheduledTaskStatus.SCHEDULED),
            events = events,
        )

        assertFalse(coordinator.reconcile("task-1"))
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun runningTaskClosesLinkedAgentBeforeWorkflowAndTask() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            task = scheduledTask(ScheduledTaskStatus.RUNNING, workflowRunId = "workflow-run-1"),
            run = workflowRun(agentRunId = "agent-run-1"),
            events = events,
        )

        assertTrue(coordinator.reconcile("task-1"))
        assertEquals(
            listOf(
                "load-run:workflow-run-1",
                "close-agent:agent-run-1",
                "reconcile-workflow:workflow-run-1",
                "reconcile-task:task-1",
            ),
            events,
        )
    }

    @Test
    fun runningTaskWithoutLinkedRunOnlySettlesCurrentTask() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            task = scheduledTask(ScheduledTaskStatus.RUNNING, workflowRunId = null),
            events = events,
        )

        assertTrue(coordinator.reconcile("task-1"))
        assertEquals(listOf("reconcile-task:task-1"), events)
    }

    private fun coordinator(
        task: ScheduledTaskRecord?,
        run: WorkflowRunDetail? = null,
        events: MutableList<String>,
    ) = ScheduledWorkflowReentryCoordinator(
        loadTask = { taskId -> task?.takeIf { it.id == taskId } },
        loadWorkflowRun = { runId ->
            events += "load-run:$runId"
            run
        },
        closeAgentRun = { runId ->
            events += "close-agent:$runId"
            true
        },
        reconcileWorkflowRun = { runId ->
            events += "reconcile-workflow:$runId"
            true
        },
        reconcileScheduledTask = { taskId ->
            events += "reconcile-task:$taskId"
            true
        },
    )

    private fun scheduledTask(
        status: ScheduledTaskStatus,
        workflowRunId: String? = null,
    ) = ScheduledTaskRecord(
        id = "task-1",
        workflowId = "workflow-1",
        type = ScheduledTaskType.ONE_TIME,
        scheduleId = null,
        status = status,
        plannedAt = 1_000L,
        workRequestId = "work-request-1",
        workflowRunId = workflowRunId,
        actualStartedAt = if (status == ScheduledTaskStatus.RUNNING) 1_100L else null,
        completedAt = null,
        errorMessage = null,
        createdAt = 900L,
        updatedAt = 1_100L,
    )

    private fun workflowRun(agentRunId: String?) = WorkflowRunDetail(
        run = WorkflowRunRecord(
            id = "workflow-run-1",
            workflowId = "workflow-1",
            trigger = WorkflowTrigger.SCHEDULED,
            scheduledTaskId = "task-1",
            plannedAt = 1_000L,
            conversationId = "conversation-1",
            agentRunId = agentRunId,
            status = WorkflowRunStatus.RUNNING,
            result = null,
            errorMessage = null,
            createdAt = 1_100L,
            startedAt = 1_100L,
            completedAt = null,
        ),
        steps = emptyList(),
    )
}
