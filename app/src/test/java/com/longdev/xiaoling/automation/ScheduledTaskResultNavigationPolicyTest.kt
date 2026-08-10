package com.longdev.xiaoling.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskResultNavigationPolicyTest {
    @Test
    fun terminalTaskWithExactCurrentIdentityCanNavigate() {
        val task = scheduledTask(status = ScheduledTaskStatus.COMPLETED)
        val target = ScheduledTaskResultNavigationPolicy.targetFor(task)

        assertTrue(target != null)
        assertTrue(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = requireNotNull(target),
                task = task,
                workflowExists = true,
                workflowRun = workflowRun(),
            ),
        )
    }

    @Test
    fun nonTerminalTaskCannotIssueNavigation() {
        listOf(
            ScheduledTaskStatus.SCHEDULED,
            ScheduledTaskStatus.RUNNING,
            ScheduledTaskStatus.STOP_REQUESTED,
        ).forEach { status ->
            assertTrue(ScheduledTaskResultNavigationPolicy.targetFor(scheduledTask(status)) == null)
        }
    }

    @Test
    fun missingWorkflowOrIdentityDriftFailsClosed() {
        val task = scheduledTask(status = ScheduledTaskStatus.FAILED)
        val target = requireNotNull(ScheduledTaskResultNavigationPolicy.targetFor(task))

        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target,
                task = task,
                workflowExists = false,
                workflowRun = workflowRun(),
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target.copy(workflowId = "workflow-forged"),
                task = task,
                workflowExists = true,
                workflowRun = workflowRun(),
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target.copy(scheduledTaskId = "scheduled-task-forged"),
                task = task,
                workflowExists = true,
                workflowRun = workflowRun(),
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target.copy(workflowRunId = "workflow-run-forged"),
                task = task,
                workflowExists = true,
                workflowRun = workflowRun(),
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target,
                task = task,
                workflowExists = true,
                workflowRun = null,
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target,
                task = task,
                workflowExists = true,
                workflowRun = workflowRun().copy(scheduledTaskId = "scheduled-task-forged"),
            ),
        )
    }

    @Test
    fun terminalTaskWithoutRunCanNavigateWithoutInventingRunIdentity() {
        val task = scheduledTask(status = ScheduledTaskStatus.CANCELLED).copy(workflowRunId = null)
        val target = requireNotNull(ScheduledTaskResultNavigationPolicy.targetFor(task))

        assertTrue(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target,
                task = task,
                workflowExists = true,
                workflowRun = null,
            ),
        )
        assertFalse(
            ScheduledTaskResultNavigationPolicy.matchesCurrentState(
                target = target,
                task = task,
                workflowExists = true,
                workflowRun = workflowRun(),
            ),
        )
    }

    private fun scheduledTask(status: ScheduledTaskStatus) = ScheduledTaskRecord(
        id = "scheduled-task-stage233",
        workflowId = "workflow-stage233",
        type = ScheduledTaskType.ONE_TIME,
        scheduleId = null,
        status = status,
        plannedAt = 1_000L,
        workRequestId = "work-stage233",
        workflowRunId = "workflow-run-stage233",
        actualStartedAt = 1_100L,
        completedAt = 1_200L,
        errorMessage = null,
        createdAt = 900L,
        updatedAt = 1_200L,
    )

    private fun workflowRun() = WorkflowRunRecord(
        id = "workflow-run-stage233",
        workflowId = "workflow-stage233",
        trigger = WorkflowTrigger.SCHEDULED,
        scheduledTaskId = "scheduled-task-stage233",
        plannedAt = 1_000L,
        conversationId = "conversation-stage233",
        agentRunId = null,
        status = WorkflowRunStatus.COMPLETED,
        result = "完成",
        errorMessage = null,
        createdAt = 900L,
        startedAt = 1_100L,
        completedAt = 1_200L,
    )
}
