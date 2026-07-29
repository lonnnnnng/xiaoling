package com.longdev.xiaoling.ui.workflow

import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowDeviceObservationDecisionPolicy
import com.longdev.xiaoling.automation.WorkflowDeviceObservationDecisionStatus
import com.longdev.xiaoling.automation.WorkflowDeviceObservationEvidenceInput
import com.longdev.xiaoling.automation.WorkflowDeviceObservationResolution
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.ToolVerificationStatus

data class WorkflowRetryConfirmationUiState(
    val runId: String,
    val workflowName: String,
    val retryFromSequence: Int,
    val reusedStepCount: Int,
)

interface WorkflowManagementActions {
    fun refreshWorkflows()

    fun createWorkflow(name: String, stepGoals: List<String>)

    fun updateWorkflow(workflowId: String, name: String, stepGoals: List<String>)

    fun setWorkflowEnabled(workflowId: String, enabled: Boolean)

    fun runWorkflow(workflowId: String)

    fun requestWorkflowRunRetry(runId: String)

    fun confirmWorkflowRunRetry()

    fun cancelWorkflowRunRetry()

    fun scheduleWorkflowOnce(workflowId: String, delayMinutes: Int)

    fun scheduleWorkflowRecurring(
        workflowId: String,
        type: WorkflowScheduleType,
        hour: Int,
        minute: Int,
        dayOfWeek: Int?,
    )

    fun cancelScheduledTask(taskId: String)

    fun cancelWorkflowSchedule(scheduleId: String)
}

internal data class WorkflowManagementUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<WorkflowItemUiState> = emptyList(),
    val pendingRetryConfirmation: WorkflowRetryConfirmationUiState? = null,
)

internal data class WorkflowItemUiState(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val primaryGoal: String,
    val stepGoals: List<String>,
    val latestRun: WorkflowRunUiState?,
    val runs: List<WorkflowRunUiState>,
    val scheduledTasks: List<WorkflowScheduledTaskUiState>,
    val schedule: WorkflowScheduleUiState?,
    val scheduling: Boolean,
    val running: Boolean,
    val canEdit: Boolean,
    val canRun: Boolean,
    val canSchedule: Boolean,
    val canToggleEnabled: Boolean,
)

internal data class WorkflowRunUiState(
    val id: String,
    val status: WorkflowRunStatus,
    val createdAt: Long,
    val retryOfWorkflowRunId: String?,
    val result: String?,
    val errorMessage: String?,
    val workerStopReasonCode: Int?,
    val workerStopReasonName: String?,
    val steps: List<WorkflowStepUiState>,
    val canRetry: Boolean,
)

internal data class WorkflowStepUiState(
    val sequence: Int,
    val title: String,
    val statusLabel: String,
    val goal: String,
    val previousOutputs: List<String>,
    val output: String?,
    val deviceObservations: List<WorkflowDeviceObservationUiState> = emptyList(),
    val reusedFromStepId: String?,
)

data class WorkflowDeviceObservationUiState(
    val packageName: String,
    val nodeCount: Int,
    val redactedNodeCount: Int,
    val truncated: Boolean,
    val capturedAt: Long,
    val durationMs: Long,
    val verificationLabel: String,
    val decisionLabel: String,
    val decisionReason: String,
    val decisionRuleVersion: String,
    val decisionScope: String,
)

internal data class WorkflowScheduledTaskUiState(
    val id: String,
    val type: ScheduledTaskType,
    val status: ScheduledTaskStatus,
    val plannedAt: Long,
    val actualStartedAt: Long?,
    val errorMessage: String?,
    val workerStopReasonCode: Int?,
    val workerStopReasonName: String?,
    val mutating: Boolean,
    val canCancel: Boolean,
)

internal data class WorkflowScheduleUiState(
    val id: String,
    val type: WorkflowScheduleType,
    val timeOfDayMinutes: Int,
    val dayOfWeek: Int?,
    val zoneId: String,
    val enabled: Boolean,
    val nextPlannedAt: Long?,
    val updatedAt: Long,
    val canCancel: Boolean,
)

internal object WorkflowManagementProjection {
    fun project(
        loading: Boolean,
        error: String?,
        workflows: List<WorkflowRecord>,
        runs: List<WorkflowRunDetail>,
        scheduledTasks: List<ScheduledTaskRecord>,
        schedules: List<WorkflowScheduleRecord>,
        mutatingWorkflowIds: Set<String>,
        mutatingScheduledTaskIds: Set<String>,
        mutatingWorkflowScheduleIds: Set<String>,
        schedulingWorkflowId: String?,
        runningWorkflowId: String?,
        sendingMessage: Boolean,
        deviceObservationsByAgentRunId: Map<String, List<WorkflowDeviceObservationUiState>> = emptyMap(),
        pendingRetryConfirmation: WorkflowRetryConfirmationUiState? = null,
    ): WorkflowManagementUiState {
        // long: Run、调度实例和周期规则只在这里按 workflowId 汇合，避免 Compose 重组时各自筛选并产生不一致的 busy 判断。
        val runsByWorkflow = runs.groupBy { it.run.workflowId }
        val tasksByWorkflow = scheduledTasks.groupBy(ScheduledTaskRecord::workflowId)
        val schedulesByWorkflow = schedules.associateBy(WorkflowScheduleRecord::workflowId)
        val globalRunBusy = sendingMessage || runningWorkflowId != null
        val globalScheduleBusy = schedulingWorkflowId != null
        return WorkflowManagementUiState(
            loading = loading,
            error = error,
            items = workflows.map { workflow ->
                val workflowRunDetails = runsByWorkflow[workflow.id].orEmpty()
                val mutating = workflow.id in mutatingWorkflowIds
                val running = runningWorkflowId == workflow.id ||
                    workflowRunDetails.firstOrNull()?.run?.status in ACTIVE_RUN_STATUSES
                val scheduling = schedulingWorkflowId == workflow.id
                val workflowRuns = workflowRunDetails.map { detail ->
                    projectRun(
                        detail = detail,
                        retryAllowed = workflow.enabled && !running && !globalRunBusy,
                        deviceObservationsByAgentRunId = deviceObservationsByAgentRunId,
                    )
                }
                WorkflowItemUiState(
                    id = workflow.id,
                    name = workflow.name,
                    enabled = workflow.enabled,
                    primaryGoal = workflow.steps.firstOrNull()?.goal ?: workflow.goal,
                    stepGoals = workflow.steps.sortedBy { it.sequence }.map { it.goal }
                        .ifEmpty { listOf(workflow.goal) },
                    latestRun = workflowRuns.firstOrNull(),
                    runs = workflowRuns,
                    scheduledTasks = tasksByWorkflow[workflow.id].orEmpty().map { task ->
                        projectTask(task, mutatingScheduledTaskIds)
                    },
                    schedule = schedulesByWorkflow[workflow.id]?.let { schedule ->
                        projectSchedule(schedule, mutatingWorkflowScheduleIds)
                    },
                    scheduling = scheduling,
                    running = running,
                    canEdit = !mutating && !running,
                    canRun = workflow.enabled && !mutating && !running && !globalRunBusy,
                    canSchedule = workflow.enabled && !mutating && !globalScheduleBusy,
                    canToggleEnabled = !mutating && !running,
                )
            },
            pendingRetryConfirmation = pendingRetryConfirmation,
        )
    }

    private fun projectRun(
        detail: WorkflowRunDetail,
        retryAllowed: Boolean,
        deviceObservationsByAgentRunId: Map<String, List<WorkflowDeviceObservationUiState>>,
    ): WorkflowRunUiState {
        val projectedSteps = detail.steps.map { step ->
            val input = runCatching { WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot) }.getOrNull()
            WorkflowStepUiState(
                sequence = step.sequence,
                title = step.title,
                statusLabel = workflowStatusLabel(step.status.name),
                goal = input?.goal ?: step.detail,
                previousOutputs = input?.previousOutputs.orEmpty().map { output ->
                    output.redactRawDeviceSnapshot(DEVICE_OBSERVATION_PREVIOUS_OUTPUT_NOTICE)
                },
                output = WorkflowStepSnapshotCodec.outputText(step.outputSnapshot ?: step.result)
                    ?.takeIf(String::isNotBlank)
                    ?.redactRawDeviceSnapshot(DEVICE_OBSERVATION_STEP_OUTPUT_NOTICE),
                deviceObservations = step.agentRunId
                    ?.let(deviceObservationsByAgentRunId::get)
                    .orEmpty(),
                reusedFromStepId = step.reusedFromStepId,
            )
        }
        return WorkflowRunUiState(
            id = detail.run.id,
            status = detail.run.status,
            createdAt = detail.run.createdAt,
            retryOfWorkflowRunId = detail.run.retryOfWorkflowRunId,
            result = detail.run.result?.redactRawDeviceSnapshot(DEVICE_OBSERVATION_RUN_RESULT_NOTICE),
            errorMessage = detail.run.errorMessage,
            workerStopReasonCode = detail.run.workerStopReasonCode,
            workerStopReasonName = detail.run.workerStopReasonName,
            steps = projectedSteps,
            canRetry = retryAllowed && detail.run.status in RETRYABLE_RUN_STATUSES,
        )
    }

    private fun String.redactRawDeviceSnapshot(notice: String): String {
        // long: UI 与执行链共享同一快照签名规则，避免历史 JSON 在页面已脱敏、送入下一 Workflow 步骤时却仍被当作普通模型文本。
        return if (WorkflowDeviceObservationDecisionPolicy.containsPotentialRawSnapshot(this)) notice else this
    }

    private fun projectTask(
        task: ScheduledTaskRecord,
        mutatingScheduledTaskIds: Set<String>,
    ): WorkflowScheduledTaskUiState {
        return WorkflowScheduledTaskUiState(
            id = task.id,
            type = task.type,
            status = task.status,
            plannedAt = task.plannedAt,
            actualStartedAt = task.actualStartedAt,
            errorMessage = task.errorMessage,
            workerStopReasonCode = task.workerStopReasonCode,
            workerStopReasonName = task.workerStopReasonName,
            mutating = task.id in mutatingScheduledTaskIds,
            canCancel = task.status == ScheduledTaskStatus.RUNNING ||
                (task.type == ScheduledTaskType.ONE_TIME && task.status == ScheduledTaskStatus.SCHEDULED),
        )
    }

    private fun projectSchedule(
        schedule: WorkflowScheduleRecord,
        mutatingWorkflowScheduleIds: Set<String>,
    ): WorkflowScheduleUiState {
        return WorkflowScheduleUiState(
            id = schedule.id,
            type = schedule.type,
            timeOfDayMinutes = schedule.timeOfDayMinutes,
            dayOfWeek = schedule.dayOfWeek,
            zoneId = schedule.zoneId,
            enabled = schedule.enabled,
            nextPlannedAt = schedule.nextPlannedAt,
            updatedAt = schedule.updatedAt,
            canCancel = schedule.enabled && schedule.id !in mutatingWorkflowScheduleIds,
        )
    }

    private val ACTIVE_RUN_STATUSES = setOf(WorkflowRunStatus.QUEUED, WorkflowRunStatus.RUNNING)
    private val RETRYABLE_RUN_STATUSES = setOf(
        WorkflowRunStatus.BLOCKED,
        WorkflowRunStatus.FAILED,
        WorkflowRunStatus.CANCELLED,
    )
    private const val DEVICE_OBSERVATION_STEP_OUTPUT_NOTICE = "设备观察已记录，请查看下方已验证证据"
    private const val DEVICE_OBSERVATION_PREVIOUS_OUTPUT_NOTICE = "设备观察输出已脱敏，请查看对应步骤证据"
    private const val DEVICE_OBSERVATION_RUN_RESULT_NOTICE = "设备观察已记录，请查看步骤中的已验证证据"
}

internal object WorkflowDeviceObservationProjection {
    fun project(
        expectedAgentRunId: String,
        ledger: AgentToolLedgerRecord,
    ): List<WorkflowDeviceObservationUiState> {
        val deviceResults = ledger.results.filter { it.toolName == DEVICE_SNAPSHOT_TOOL_NAME }
        val resolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
            expectedAgentRunId = expectedAgentRunId,
            results = ledger.results.map { result ->
                WorkflowDeviceObservationEvidenceInput(
                    runId = result.runId,
                    toolName = result.toolName,
                    content = result.content,
                    success = result.success,
                    verified = result.verificationStatus == ToolVerificationStatus.PASSED,
                    durationMs = result.durationMs,
                )
            },
        )
        val decisions = (resolution as? WorkflowDeviceObservationResolution.Decided)?.decisions
            ?: return emptyList()
        return decisions.mapIndexed { index, decision ->
            val decisionReason = buildList {
                if (decision.redactedNodeCount > 0) add("${decision.redactedNodeCount} 个节点已脱敏")
                if (decision.truncated) add("节点或文本达到快照上限")
            }.joinToString("；").ifBlank { "快照未脱敏且未截断" }
            // long: “已验证”与本地结论都只能来自独立 Tool Ledger；模型自由文本不能生成证据，也不能把摘要提升为“目标已完成”。
            WorkflowDeviceObservationUiState(
                packageName = decision.packageName,
                nodeCount = decision.nodeCount,
                redactedNodeCount = decision.redactedNodeCount,
                truncated = decision.truncated,
                capturedAt = decision.capturedAt,
                durationMs = deviceResults.getOrNull(index)?.durationMs ?: 0L,
                verificationLabel = "已验证",
                decisionLabel = when (decision.status) {
                    WorkflowDeviceObservationDecisionStatus.REVIEWABLE -> "可复核"
                    WorkflowDeviceObservationDecisionStatus.LIMITED -> "有限可复核"
                },
                decisionReason = decisionReason,
                decisionRuleVersion = decision.ruleVersion,
                decisionScope = "仅确认包名与快照摘要，不确认节点正文、目标完成或动作授权",
            )
        }
    }

    private const val DEVICE_SNAPSHOT_TOOL_NAME = "device.snapshot"
}

internal fun workflowStatusLabel(status: String): String = when (status) {
    WorkflowRunStatus.QUEUED.name,
    "PENDING" -> "等待"
    WorkflowRunStatus.RUNNING.name -> "运行中"
    WorkflowRunStatus.BLOCKED.name,
    "BLOCKED" -> "待处理"
    WorkflowRunStatus.COMPLETED.name -> "已完成"
    "SKIPPED" -> "已复用"
    WorkflowRunStatus.FAILED.name -> "失败"
    WorkflowRunStatus.CANCELLED.name -> "已取消"
    else -> status
}
