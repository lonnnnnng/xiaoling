package com.longdev.xiaoling.automation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledWorkflowStopCoordinatorTest {
    @Test
    fun stopWaitsForWorkerToPersistTerminalBeforeUsingFallback() = runTest {
        var task = runningTask()
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { task },
            requestScheduledTaskStop = { taskId ->
                events += "request:$taskId"
                task = task.copy(status = ScheduledTaskStatus.STOP_REQUESTED)
                task
            },
            cancelSystemWork = { taskId -> events += "cancel:$taskId" },
            waitForWorkerSettlement = {
                events += "wait"
                task = task.copy(status = ScheduledTaskStatus.CANCELLED, completedAt = 2L)
            },
            reconcileUnsettledTask = { taskId ->
                events += "reconcile:$taskId"
                false
            },
            settlementChecks = 3,
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, result.outcome)
        assertEquals(ScheduledTaskStatus.CANCELLED, result.task?.status)
        assertEquals(listOf("request:${task.id}", "cancel:${task.id}", "wait"), events)
    }

    @Test
    fun stopReconcilesLinkedLedgerWhenWorkerDoesNotSettleInTime() = runTest {
        var task = runningTask()
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { task },
            requestScheduledTaskStop = { taskId ->
                events += "request:$taskId"
                task = task.copy(status = ScheduledTaskStatus.STOP_REQUESTED)
                task
            },
            cancelSystemWork = { taskId -> events += "cancel:$taskId" },
            waitForWorkerSettlement = { events += "wait" },
            reconcileUnsettledTask = { taskId ->
                events += "reconcile:$taskId"
                task = task.copy(status = ScheduledTaskStatus.CANCELLED, completedAt = 3L)
                true
            },
            settlementChecks = 2,
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, result.outcome)
        assertEquals(ScheduledTaskStatus.CANCELLED, result.task?.status)
        assertEquals(listOf("request:${task.id}", "cancel:${task.id}", "wait", "wait", "reconcile:${task.id}"), events)
    }

    @Test
    fun stopReconcilesLedgerWhenSystemCancellationThrows() = runTest {
        var task = runningTask()
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { task },
            requestScheduledTaskStop = { taskId ->
                events += "request:$taskId"
                task = task.copy(status = ScheduledTaskStatus.STOP_REQUESTED)
                task
            },
            cancelSystemWork = { taskId ->
                events += "cancel:$taskId"
                error("WorkManager unavailable")
            },
            waitForWorkerSettlement = { events += "wait" },
            reconcileUnsettledTask = { taskId ->
                events += "reconcile:$taskId"
                task = task.copy(status = ScheduledTaskStatus.CANCELLED, completedAt = 4L)
                true
            },
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, result.outcome)
        assertEquals(true, result.systemCancellationFailed)
        assertEquals(ScheduledTaskStatus.CANCELLED, result.task?.status)
        assertEquals(listOf("request:${task.id}", "cancel:${task.id}", "reconcile:${task.id}"), events)
    }

    @Test
    fun stopCancelsPendingScheduleBeforeSystemWork() = runTest {
        var task = runningTask().copy(status = ScheduledTaskStatus.SCHEDULED, actualStartedAt = null)
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { taskId ->
                events += "cancel-ledger:$taskId"
                task = task.copy(status = ScheduledTaskStatus.CANCELLED, completedAt = 5L)
                task
            },
            requestScheduledTaskStop = { error("待执行任务不应请求运行中停止") },
            cancelSystemWork = { taskId -> events += "cancel-system:$taskId" },
            waitForWorkerSettlement = { events += "wait" },
            reconcileUnsettledTask = { false },
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.SCHEDULE_CANCELLED, result.outcome)
        assertEquals(ScheduledTaskStatus.CANCELLED, result.task?.status)
        assertEquals(listOf("cancel-ledger:${task.id}", "cancel-system:${task.id}"), events)
    }

    @Test
    fun stopUpgradesPendingCancellationToRunningStopAfterClaimRace() = runTest {
        var task = runningTask().copy(status = ScheduledTaskStatus.SCHEDULED, actualStartedAt = null)
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { taskId ->
                events += "cancel-ledger:$taskId"
                task = runningTask()
                task
            },
            requestScheduledTaskStop = { taskId ->
                events += "request:$taskId"
                task = task.copy(status = ScheduledTaskStatus.STOP_REQUESTED)
                task
            },
            cancelSystemWork = { taskId -> events += "cancel-system:$taskId" },
            waitForWorkerSettlement = {
                events += "wait"
                task = task.copy(status = ScheduledTaskStatus.CANCELLED, completedAt = 6L)
            },
            reconcileUnsettledTask = { false },
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOPPED, result.outcome)
        assertEquals(ScheduledTaskStatus.CANCELLED, result.task?.status)
        assertEquals(
            listOf("cancel-ledger:${task.id}", "request:${task.id}", "cancel-system:${task.id}", "wait"),
            events,
        )
    }

    @Test
    fun stopKeepsDurableRequestWhenSystemCancellationAndFallbackFail() = runTest {
        var task = runningTask()
        val events = mutableListOf<String>()
        val coordinator = ScheduledWorkflowStopCoordinator(
            loadTask = { task },
            cancelPendingTask = { task },
            requestScheduledTaskStop = { taskId ->
                events += "request:$taskId"
                task = task.copy(
                    status = ScheduledTaskStatus.STOP_REQUESTED,
                    errorMessage = "用户请求停止后台工作流",
                )
                task
            },
            cancelSystemWork = { taskId ->
                events += "cancel:$taskId"
                error("WorkManager unavailable")
            },
            waitForWorkerSettlement = { events += "wait" },
            reconcileUnsettledTask = { taskId ->
                events += "reconcile:$taskId"
                error("Room temporarily unavailable")
            },
        )

        val result = coordinator.stop(task.id)

        assertEquals(ScheduledWorkflowStopOutcome.STOP_REQUESTED, result.outcome)
        assertEquals(true, result.systemCancellationFailed)
        assertEquals(ScheduledTaskStatus.STOP_REQUESTED, result.task?.status)
        assertEquals(listOf("request:${task.id}", "cancel:${task.id}", "reconcile:${task.id}"), events)
    }

    private fun runningTask() = ScheduledTaskRecord(
        id = "scheduled-task-running",
        workflowId = "workflow-running",
        type = ScheduledTaskType.ONE_TIME,
        scheduleId = null,
        status = ScheduledTaskStatus.RUNNING,
        plannedAt = 1L,
        workRequestId = "work-request-running",
        workflowRunId = "workflow-run-running",
        actualStartedAt = 1L,
        completedAt = null,
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
