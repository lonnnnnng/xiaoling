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
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.AgentToolResultRecord
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowManagementProjectionTest {
    @Test
    fun projectRedactsRawDeviceSnapshotFromStepPreviousOutputsAndRunResult() {
        val workflow = workflow(id = "workflow-redact-output", enabled = true)
        val agentRunId = "agent-run-redact-output"
        val rawSnapshot = """
            {"snapshot_id":"snapshot-secret","package":"com.example.notes","captured_at":1700000000000,"redacted_node_count":0,"truncated":false,"nodes":[{"text":"银行卡密码 123456","ref":"ref-secret"}]}
        """.trimIndent()
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-redact-output",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-redact-output",
                workflowRunId = "workflow-run-redact-output",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "Agent 任务已完成\n- 工具：device.snapshot\n- 结果：$rawSnapshot",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
                inputSnapshot = WorkflowStepSnapshotCodec.encodeInput(
                    goal = "观察设备",
                    previousOutputs = listOf(rawSnapshot),
                ),
            ),
        ).let { detail ->
            detail.copy(run = detail.run.copy(result = "执行结果：$rawSnapshot"))
        }

        val projectedRun = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(toolResult(content = rawSnapshot, runId = agentRunId)),
            ),
        ).items.single().runs.single()

        assertEquals("设备观察已记录，请查看下方已验证证据", projectedRun.steps.single().output)
        assertEquals(
            listOf("设备观察输出已脱敏，请查看对应步骤证据"),
            projectedRun.steps.single().previousOutputs,
        )
        assertEquals("设备观察已记录，请查看步骤中的已验证证据", projectedRun.result)
        assertFalse(projectedRun.toString().contains("银行卡密码"))
        assertFalse(projectedRun.toString().contains("ref-secret"))
        assertFalse(projectedRun.toString().contains("snapshot-secret"))
    }

    @Test
    fun projectRejectsFailedUnverifiedAndMalformedDeviceObservationEvidence() {
        val workflow = workflow(id = "workflow-reject-evidence", enabled = true)
        val agentRunId = "agent-run-reject-evidence"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-reject-evidence",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-reject-evidence",
                workflowRunId = "workflow-run-reject-evidence",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "观察未形成可信证据",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val validSnapshot = """
            {"package":"com.example.notes","captured_at":1700000000000,"redacted_node_count":0,"truncated":false,"nodes":[]}
        """.trimIndent()

        val projectedStep = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(
                    toolResult(
                        content = validSnapshot,
                        runId = agentRunId,
                        success = false,
                        verificationStatus = ToolVerificationStatus.PASSED,
                    ),
                    toolResult(
                        content = validSnapshot,
                        runId = agentRunId,
                        verificationStatus = ToolVerificationStatus.FAILED,
                    ),
                    toolResult(content = "not-json", runId = agentRunId),
                    toolResult(content = validSnapshot, runId = agentRunId, toolName = "notes.list"),
                ),
            ),
        ).items.single().runs.single().steps.single()

        assertTrue(projectedStep.deviceObservations.isEmpty())
    }

    @Test
    fun projectDoesNotBindDeviceObservationFromAnotherAgentRun() {
        val workflow = workflow(id = "workflow-isolated", enabled = true)
        val expectedAgentRunId = "agent-run-expected"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-isolated",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-isolated",
                workflowRunId = "workflow-run-isolated",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = expectedAgentRunId,
                result = "已观察当前页面",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val validSnapshot = """
            {"package":"com.example.other","captured_at":1700000000000,"redacted_node_count":0,"truncated":false,"nodes":[]}
        """.trimIndent()

        val projectedStep = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = expectedAgentRunId,
                results = listOf(toolResult(content = validSnapshot, runId = "agent-run-other")),
            ),
        ).items.single().runs.single().steps.single()

        assertTrue(projectedStep.deviceObservations.isEmpty())
    }

    @Test
    fun projectIncludesVerifiedDeviceObservationWithoutExposingRawSnapshotData() {
        val workflow = workflow(id = "workflow-observe", enabled = true)
        val agentRunId = "agent-run-observe"
        val workflowRun = run(
            workflowId = workflow.id,
            runId = "workflow-run-observe",
            status = WorkflowRunStatus.COMPLETED,
            step = WorkflowStepRecord(
                id = "step-observe",
                workflowRunId = "workflow-run-observe",
                sequence = 1,
                type = "AGENT",
                status = WorkflowStepStatus.COMPLETED,
                title = "观察当前页面",
                detail = "观察设备",
                agentRunId = agentRunId,
                result = "已观察当前页面",
                errorMessage = null,
                createdAt = 1L,
                startedAt = 2L,
                completedAt = 3L,
            ),
        )
        val rawSnapshot = """
            {
              "snapshot_id":"snapshot-secret",
              "package":"com.example.notes",
              "window_title":"私人笔记",
              "window_id":7,
              "window_generation":8,
              "captured_at":1700000000000,
              "expires_at":1700000005000,
              "redacted_node_count":1,
              "truncated":false,
              "nodes":[
                {"index":0,"text":"银行卡密码 123456","ref":"ref-secret","bounds":[0,0,100,100],"actions":["tap"]},
                {"index":1,"redacted":true}
              ]
            }
        """.trimIndent()

        val result = WorkflowManagementProjection.project(
            loading = false,
            error = null,
            workflows = listOf(workflow),
            runs = listOf(workflowRun),
            scheduledTasks = emptyList(),
            schedules = emptyList(),
            mutatingWorkflowIds = emptySet(),
            mutatingScheduledTaskIds = emptySet(),
            mutatingWorkflowScheduleIds = emptySet(),
            schedulingWorkflowId = null,
            runningWorkflowId = null,
            sendingMessage = false,
            deviceObservationsByAgentRunId = observationsFor(
                agentRunId = agentRunId,
                results = listOf(toolResult(content = rawSnapshot)),
            ),
        )

        val observation = result.items.single().runs.single().steps.single().deviceObservations.single()
        assertEquals("com.example.notes", observation.packageName)
        assertEquals(2, observation.nodeCount)
        assertEquals(1, observation.redactedNodeCount)
        assertFalse(observation.truncated)
        assertEquals(1_700_000_000_000L, observation.capturedAt)
        assertEquals(193L, observation.durationMs)
        assertEquals("已验证", observation.verificationLabel)
        assertFalse(observation.toString().contains("私人笔记"))
        assertFalse(observation.toString().contains("银行卡密码"))
        assertFalse(observation.toString().contains("ref-secret"))
        assertFalse(observation.toString().contains("snapshot-secret"))
    }

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

    private fun toolResult(
        content: String,
        runId: String = "agent-run-observe",
        toolName: String = "device.snapshot",
        success: Boolean = true,
        verificationStatus: ToolVerificationStatus = ToolVerificationStatus.PASSED,
    ): AgentToolResultRecord {
        return AgentToolResultRecord(
            toolCallId = "tool-call-snapshot",
            runId = runId,
            eventId = "event-result",
            toolName = toolName,
            content = content,
            success = success,
            errorMessage = null,
            durationMs = 193L,
            executorVerified = true,
            verificationStatus = verificationStatus,
            verifiedEventId = "event-verified",
            memoryIdsUsed = emptyList(),
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
            executionReceipt = null,
            createdAt = 4L,
            verifiedAt = 5L,
        )
    }

    private fun observationsFor(
        agentRunId: String,
        results: List<AgentToolResultRecord>,
    ): Map<String, List<WorkflowDeviceObservationUiState>> {
        return mapOf(
            agentRunId to WorkflowDeviceObservationProjection.project(
                expectedAgentRunId = agentRunId,
                ledger = AgentToolLedgerRecord(results = results),
            ),
        )
    }
}
