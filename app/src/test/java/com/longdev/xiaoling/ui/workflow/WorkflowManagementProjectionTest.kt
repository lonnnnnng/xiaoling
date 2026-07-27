package com.longdev.xiaoling.ui.workflow

import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionRecord
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowManagementProjectionTest {
    @Test
    fun projectAggregatesWorkflowStateAndDerivesAvailableActions() {
        val activeWorkflow = workflow(id = "workflow-active", enabled = true)
        val disabledWorkflow = workflow(id = "workflow-disabled", enabled = false)
        val activeRun = run(
            workflowId = activeWorkflow.id,
            runId = "run-active",
            status = WorkflowRunStatus.RUNNING,
        )
        val failedRun = run(
            workflowId = activeWorkflow.id,
            runId = "run-failed",
            status = WorkflowRunStatus.FAILED,
            step = WorkflowStepRecord(
                id = "step-failed",
                workflowRunId = "run-failed",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.FAILED,
                title = "步骤 1",
                detail = "fallback goal",
                agentRunId = null,
                result = null,
                errorMessage = "provider unavailable",
                createdAt = 2L,
                startedAt = 3L,
                completedAt = 4L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "真实目标",
                    previousOutputs = listOf("前序输出"),
                ),
                outputSnapshot = WorkflowStepSnapshotCodec.encodeOutput("失败前输出"),
            ),
        )
        val scheduledTask = task(
            id = "task-scheduled",
            workflowId = activeWorkflow.id,
            type = ScheduledTaskType.ONE_TIME,
            status = ScheduledTaskStatus.SCHEDULED,
        )
        val completedTask = task(
            id = "task-completed",
            workflowId = activeWorkflow.id,
            type = ScheduledTaskType.RECURRING,
            status = ScheduledTaskStatus.COMPLETED,
        )
        val schedule = schedule(workflowId = activeWorkflow.id)
        val pendingRetry = WorkflowRetryConfirmationUiState(
            runId = failedRun.run.id,
            workflowName = activeWorkflow.name,
            retryFromSequence = 1,
            reusedStepCount = 0,
        )

        val result = WorkflowManagementProjection.project(
            loading = false,
            error = "last refresh failed",
            workflows = listOf(activeWorkflow, disabledWorkflow),
            runs = listOf(activeRun, failedRun),
            scheduledTasks = listOf(scheduledTask, completedTask),
            schedules = listOf(schedule),
            mutatingWorkflowIds = setOf(disabledWorkflow.id),
            mutatingScheduledTaskIds = setOf(scheduledTask.id),
            mutatingWorkflowScheduleIds = setOf(schedule.id),
            schedulingWorkflowId = activeWorkflow.id,
            runningWorkflowId = null,
            sendingMessage = false,
            pendingRetryConfirmation = pendingRetry,
        )

        assertEquals("last refresh failed", result.error)
        assertEquals(listOf(activeWorkflow.id, disabledWorkflow.id), result.items.map { it.id })
        assertEquals(pendingRetry, result.pendingRetryConfirmation)

        val active = result.items.first()
        assertTrue(active.running)
        assertTrue(active.scheduling)
        assertFalse(active.canEdit)
        assertFalse(active.canRun)
        assertFalse(active.canSchedule)
        assertFalse(active.canToggleEnabled)
        assertEquals(schedule.id, active.schedule?.id)
        assertFalse(active.schedule?.canCancel ?: true)
        assertEquals(listOf(scheduledTask.id, completedTask.id), active.scheduledTasks.map { it.id })
        assertTrue(active.scheduledTasks.first().canCancel)
        assertTrue(active.scheduledTasks.first().mutating)
        assertFalse(active.scheduledTasks.last().canCancel)
        assertEquals(listOf(activeRun.run.id, failedRun.run.id), active.runs.map { it.id })
        assertFalse(active.runs.first().canRetry)
        assertFalse(active.runs.last().canRetry)
        assertEquals("真实目标", active.runs.last().steps.single().goal)
        assertEquals(listOf("前序输出"), active.runs.last().steps.single().previousOutputs)
        assertEquals("失败前输出", active.runs.last().steps.single().output)

        val disabled = result.items.last()
        assertFalse(disabled.running)
        assertFalse(disabled.canEdit)
        assertFalse(disabled.canRun)
        assertFalse(disabled.canSchedule)
        assertFalse(disabled.canToggleEnabled)
        assertNull(disabled.schedule)
    }

    @Test
    fun projectDisablesGlobalActionsWhileAnotherWorkflowIsBusyAndRestoresThemWhenIdle() {
        val first = workflow(id = "workflow-first", enabled = true)
        val second = workflow(id = "workflow-second", enabled = true)
        val failedRun = run(
            workflowId = second.id,
            runId = "run-failed",
            status = WorkflowRunStatus.FAILED,
        )

        val busy = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(first, second),
            runs = listOf(failedRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = first.id,
            runningWorkflowId = first.id,
            sendingMessage = false,
        ).items.last()

        assertFalse(busy.canRun)
        assertFalse(busy.canSchedule)
        assertFalse(busy.runs.single().canRetry)

        val idle = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(first, second),
            runs = listOf(failedRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
        ).items.last()

        assertTrue(idle.canRun)
        assertTrue(idle.canSchedule)
        assertTrue(idle.runs.single().canRetry)
    }

    private fun workflow(id: String, enabled: Boolean): WorkflowRecord {
        return WorkflowRecord(
            id = id,
            name = id,
            goal = "goal-$id",
            enabled = enabled,
            createdAt = 1L,
            updatedAt = 2L,
            steps = listOf(
                WorkflowStepDefinitionRecord(
                    id = "step-$id",
                    workflowId = id,
                    sequence = 1,
                    goal = "goal-$id",
                    idempotencyKey = "key-$id",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            ),
        )
    }

    private fun run(
        workflowId: String,
        runId: String,
        status: WorkflowRunStatus,
        step: WorkflowStepRecord? = null,
    ): WorkflowRunDetail {
        return WorkflowRunDetail(
            run = WorkflowRunRecord(
                id = runId,
                workflowId = workflowId,
                trigger = WorkflowTrigger.MANUAL,
                scheduledTaskId = null,
                plannedAt = null,
                conversationId = "conversation-1",
                agentRunId = null,
                status = status,
                result = null,
                errorMessage = null,
                createdAt = 1L,
                startedAt = null,
                completedAt = null,
            ),
            steps = listOfNotNull(step),
        )
    }

    private fun task(
        id: String,
        workflowId: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
    ): ScheduledTaskRecord {
        return ScheduledTaskRecord(
            id = id,
            workflowId = workflowId,
            type = type,
            scheduleId = null,
            status = status,
            plannedAt = 10L,
            workRequestId = null,
            workflowRunId = null,
            actualStartedAt = null,
            completedAt = null,
            errorMessage = null,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun schedule(workflowId: String): WorkflowScheduleRecord {
        return WorkflowScheduleRecord(
            id = "schedule-1",
            workflowId = workflowId,
            type = WorkflowScheduleType.DAILY,
            timeOfDayMinutes = 9 * 60,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
            enabled = true,
            nextTaskId = "task-scheduled",
            nextPlannedAt = 10L,
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
