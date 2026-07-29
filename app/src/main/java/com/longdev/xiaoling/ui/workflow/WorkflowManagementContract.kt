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
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.ToolVerificationStatus
import com.longdev.xiaoling.device.DeviceSnapshotCodec

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
        // long: 旧 Workflow 可能把完整 snapshot JSON 写入步骤结果和前序输入；只要同时出现设备快照核心字段就整段替换，宁可少展示模型文本也不能让节点正文与 ref 回流历史 UI。
        val containsRawSnapshot = contains("\"snapshot_id\"") &&
            contains("\"package\"") &&
            contains("\"captured_at\"") &&
            contains("\"nodes\"")
        return if (containsRawSnapshot) notice else this
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
        return ledger.results.mapNotNull { result ->
            if (
                result.runId != expectedAgentRunId ||
                result.toolName != DEVICE_SNAPSHOT_TOOL_NAME ||
                !result.success ||
                result.verificationStatus != ToolVerificationStatus.PASSED
            ) {
                return@mapNotNull null
            }
            val summary = DeviceSnapshotCodec.decodeSummary(result.content) ?: return@mapNotNull null
            // long: “已验证”只能来自独立 Tool Ledger 的 PASSED 结果；模型自由文本不能生成或提升设备观察证据。
            WorkflowDeviceObservationUiState(
                packageName = summary.packageName,
                nodeCount = summary.nodeCount,
                redactedNodeCount = summary.redactedNodeCount,
                truncated = summary.truncated,
                capturedAt = summary.capturedAt,
                durationMs = result.durationMs,
                verificationLabel = "已验证",
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
