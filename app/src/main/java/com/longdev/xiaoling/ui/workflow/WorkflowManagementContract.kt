package com.longdev.xiaoling.ui.workflow

import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecisionPolicy
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecisionStatus
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
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
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
    val deviceActions: List<WorkflowDeviceActionUiState> = emptyList(),
    val reusedFromStepId: String?,
)

internal enum class WorkflowDeviceActionUiOutcome {
    VERIFIED,
    USER_DENIED,
    CANCELLED,
    WINDOW_CHANGED,
    OVERLAY_UNAVAILABLE,
    SERVICE_DISCONNECTED,
    BUSY,
}

internal data class WorkflowDeviceActionUiState(
    val outcome: WorkflowDeviceActionUiOutcome,
    val action: String,
    val detail: String,
    val beforePackageName: String? = null,
    val afterPackageName: String? = null,
    val afterNodeCount: Int? = null,
    val afterRedactedNodeCount: Int? = null,
    val afterTruncated: Boolean? = null,
    val afterObservedAt: Long? = null,
    val decisionRuleVersion: String? = null,
) {
    val actionLabel: String
        get() = when (action) {
            "back" -> "返回"
            "home" -> "返回桌面"
            "type_text" -> "输入文本（内容不展示）"
            else -> action
        }

    val followUpGuidance: String
        get() = when (action) {
            // long: SAFE 系统导航没有节点目标也不创建独立审批，历史页只能要求重新观察并让下一动作按自己的风险规则重新判定。
            "back" -> "本次返回不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行"
            "home" -> "本次返回桌面不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行"
            else -> "节点引用已失效，后续动作必须重新观察和审批"
        }
}

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

data class WorkflowDeviceActionApprovalEvidence(
    val runId: String,
    val toolName: String,
    val outcome: WorkflowDeviceActionApprovalOutcome,
)

enum class WorkflowDeviceActionApprovalOutcome {
    PENDING,
    APPROVED,
    USER_DENIED,
    EXPIRED,
    CANCELLED,
    WINDOW_CHANGED,
    OVERLAY_UNAVAILABLE,
    SERVICE_DISCONNECTED,
    BUSY,
}

internal object WorkflowDeviceActionApprovalEvidencePolicy {
    fun project(approval: ApprovalRequestRecord): WorkflowDeviceActionApprovalEvidence? {
        if (approval.toolName !in DEVICE_ACTION_TOOL_NAMES) return null
        val outcome = when (approval.status) {
            ApprovalRequestStatus.PENDING -> WorkflowDeviceActionApprovalOutcome.PENDING
            ApprovalRequestStatus.APPROVED -> WorkflowDeviceActionApprovalOutcome.APPROVED
            ApprovalRequestStatus.DENIED -> WorkflowDeviceActionApprovalOutcome.USER_DENIED
            ApprovalRequestStatus.EXPIRED -> WorkflowDeviceActionApprovalOutcome.EXPIRED
            ApprovalRequestStatus.CANCELLED -> classifyCancelledReason(approval.decisionReason)
        }
        // long: 原始审批参数和原因只在 IO 投影瞬间参与分类；返回值只有 Run、工具名和枚举，不给 Compose 留下承载 snapshot/ref 的字段。
        return WorkflowDeviceActionApprovalEvidence(
            runId = approval.runId,
            toolName = approval.toolName,
            outcome = outcome,
        )
    }

    fun classifyExecutionFailure(reason: String?): WorkflowDeviceActionApprovalOutcome? = when {
        reason.containsAny(WINDOW_CHANGED_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.WINDOW_CHANGED
        reason.containsAny(SERVICE_DISCONNECTED_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.SERVICE_DISCONNECTED
        else -> null
    }

    private fun classifyCancelledReason(reason: String?): WorkflowDeviceActionApprovalOutcome = when {
        reason.containsAny(BUSY_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.BUSY
        reason.containsAny(WINDOW_CHANGED_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.WINDOW_CHANGED
        reason.containsAny(OVERLAY_UNAVAILABLE_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.OVERLAY_UNAVAILABLE
        reason.containsAny(SERVICE_DISCONNECTED_REASON_SIGNATURES) -> WorkflowDeviceActionApprovalOutcome.SERVICE_DISCONNECTED
        else -> WorkflowDeviceActionApprovalOutcome.CANCELLED
    }

    private fun String?.containsAny(signatures: Set<String>): Boolean {
        return this != null && signatures.any(::contains)
    }

    // long: 这里只投影会产生 Room Approval 的动作；SAFE back 以 approval.skipped 和 Tool Ledger 审计，不能伪造一条审批卡。
    private val DEVICE_ACTION_TOOL_NAMES = setOf("device.tap_ref", "device.type_text")
    private val BUSY_REASON_SIGNATURES = setOf("已有设备动作审批正在显示", "已有设备动作审批正在处理")
    private val WINDOW_CHANGED_REASON_SIGNATURES = setOf(
        "活动页面已经切换",
        "目标页面内容已经变化",
        "多个无法区分的 Accessibility overlay",
        "Accessibility overlay 身份发生变化",
        "额外窗口",
        "窗口集合发生变化",
        "window generation",
        "页面已变化",
    )
    private val OVERLAY_UNAVAILABLE_REASON_SIGNATURES = setOf(
        "当前窗口状态不允许显示设备动作审批",
        "浮层不可用",
        "无法确认审批浮层",
        "系统拒绝显示设备动作审批浮层",
    )
    private val SERVICE_DISCONNECTED_REASON_SIGNATURES = setOf("无障碍服务已断开", "服务断连", "服务已断开")
}

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
        deviceActionApprovalsByAgentRunId: Map<String, List<WorkflowDeviceActionApprovalEvidence>> = emptyMap(),
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
                        deviceActionApprovalsByAgentRunId = deviceActionApprovalsByAgentRunId,
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
        deviceActionApprovalsByAgentRunId: Map<String, List<WorkflowDeviceActionApprovalEvidence>>,
    ): WorkflowRunUiState {
        val projectedSteps = detail.steps.map { step ->
            val input = runCatching { WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot) }.getOrNull()
            val output = WorkflowStepSnapshotCodec.decodeOutput(step.outputSnapshot ?: step.result)
            val deviceActionApprovals = step.agentRunId
                ?.let { expectedAgentRunId ->
                    deviceActionApprovalsByAgentRunId[expectedAgentRunId]
                        .orEmpty()
                        .filter { evidence -> evidence.runId == expectedAgentRunId }
                }
                .orEmpty()
            val verifiedDeviceActions = output?.deviceActionDecisions.orEmpty().map { decision ->
                // long: Compose 只接收版本化动作判定中的白名单摘要；snapshot、ref、节点正文和原始参数在这里没有字段可承载。
                WorkflowDeviceActionUiState(
                    outcome = when (decision.status) {
                        WorkflowDeviceActionDecisionStatus.VERIFIED -> WorkflowDeviceActionUiOutcome.VERIFIED
                    },
                    action = decision.action,
                    detail = "已执行并验证",
                    beforePackageName = decision.beforePackageName,
                    afterPackageName = decision.afterPackageName,
                    afterNodeCount = decision.afterNodeCount,
                    afterRedactedNodeCount = decision.afterRedactedNodeCount,
                    afterTruncated = decision.afterTruncated,
                    afterObservedAt = decision.afterObservedAt,
                    decisionRuleVersion = decision.ruleVersion,
                )
            }
            val failedDeviceActions = deviceActionApprovals.mapNotNull(::projectDeviceActionApprovalFailure) +
                listOfNotNull(
                    projectDeviceActionExecutionFailure(
                        approvals = deviceActionApprovals,
                        errorMessage = step.errorMessage ?: detail.run.errorMessage,
                    ),
                )
            WorkflowStepUiState(
                sequence = step.sequence,
                title = step.title,
                statusLabel = workflowStatusLabel(step.status.name),
                goal = input?.goal ?: step.detail,
                previousOutputs = input?.previousOutputs.orEmpty().map { output ->
                    output.redactRawDeviceSnapshot(DEVICE_OBSERVATION_PREVIOUS_OUTPUT_NOTICE)
                        .redactRawDeviceAction(DEVICE_ACTION_PREVIOUS_OUTPUT_NOTICE)
                },
                output = output?.text
                    ?.takeIf(String::isNotBlank)
                    ?.redactRawDeviceSnapshot(DEVICE_OBSERVATION_STEP_OUTPUT_NOTICE)
                    ?.redactRawDeviceAction(DEVICE_ACTION_STEP_OUTPUT_NOTICE),
                deviceObservations = step.agentRunId
                    ?.let(deviceObservationsByAgentRunId::get)
                    .orEmpty(),
                // long: 成功判定是该步骤的最终可信结果；没有成功判定时保留每次审批失败，并补充批准后在执行验证阶段发生的失败，避免多次尝试互相遮蔽。
                deviceActions = verifiedDeviceActions.ifEmpty { failedDeviceActions },
                reusedFromStepId = step.reusedFromStepId,
            )
        }
        return WorkflowRunUiState(
            id = detail.run.id,
            status = detail.run.status,
            createdAt = detail.run.createdAt,
            retryOfWorkflowRunId = detail.run.retryOfWorkflowRunId,
            result = detail.run.result
                ?.redactRawDeviceSnapshot(DEVICE_OBSERVATION_RUN_RESULT_NOTICE)
                ?.redactRawDeviceAction(DEVICE_ACTION_RUN_RESULT_NOTICE),
            errorMessage = detail.run.errorMessage
                ?.redactRawDeviceSnapshot(DEVICE_OBSERVATION_RUN_ERROR_NOTICE)
                ?.redactRawDeviceAction(DEVICE_ACTION_RUN_ERROR_NOTICE),
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

    private fun String.redactRawDeviceAction(notice: String): String {
        // long: 历史失败记录可能早于动作白名单判定落库；页面发现原始动作结果签名时只显示固定提示，不能把 JSON 当普通答案正文渲染。
        return if (WorkflowDeviceActionDecisionPolicy.containsPotentialRawActionResult(this)) notice else this
    }

    private fun projectDeviceActionApprovalFailure(
        approval: WorkflowDeviceActionApprovalEvidence,
    ): WorkflowDeviceActionUiState? {
        val action = DEVICE_ACTION_BY_TOOL_NAME[approval.toolName] ?: return null
        return when (approval.outcome) {
            WorkflowDeviceActionApprovalOutcome.USER_DENIED -> WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.USER_DENIED,
                action = action,
                detail = "用户拒绝了本次设备动作",
            )
            WorkflowDeviceActionApprovalOutcome.CANCELLED -> cancelledDeviceAction(
                action,
                WorkflowDeviceActionUiOutcome.CANCELLED,
                "本次设备动作审批已取消",
            )
            WorkflowDeviceActionApprovalOutcome.WINDOW_CHANGED -> cancelledDeviceAction(
                action,
                WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                "审批期间页面窗口发生变化，设备动作未执行",
            )
            WorkflowDeviceActionApprovalOutcome.OVERLAY_UNAVAILABLE -> cancelledDeviceAction(
                action,
                WorkflowDeviceActionUiOutcome.OVERLAY_UNAVAILABLE,
                "设备动作审批浮层不可用，设备动作未执行",
            )
            WorkflowDeviceActionApprovalOutcome.SERVICE_DISCONNECTED -> cancelledDeviceAction(
                action,
                WorkflowDeviceActionUiOutcome.SERVICE_DISCONNECTED,
                "无障碍服务已断开，设备动作未执行",
            )
            WorkflowDeviceActionApprovalOutcome.BUSY -> cancelledDeviceAction(
                action,
                WorkflowDeviceActionUiOutcome.BUSY,
                "已有设备动作审批正在处理，本次动作未执行",
            )
            WorkflowDeviceActionApprovalOutcome.PENDING,
            WorkflowDeviceActionApprovalOutcome.APPROVED,
            WorkflowDeviceActionApprovalOutcome.EXPIRED,
            -> null
        }
    }

    private fun cancelledDeviceAction(
        action: String,
        outcome: WorkflowDeviceActionUiOutcome,
        detail: String,
    ): WorkflowDeviceActionUiState {
        return WorkflowDeviceActionUiState(
            outcome = outcome,
            action = action,
            detail = detail,
        )
    }

    private fun projectDeviceActionExecutionFailure(
        approvals: List<WorkflowDeviceActionApprovalEvidence>,
        errorMessage: String?,
    ): WorkflowDeviceActionUiState? {
        // long: 步骤错误没有 ToolCall ID，只能绑定最后一个已批准的白名单动作；不能把一次窗口变化同时归因到步骤中所有历史审批。
        val approvedAction = approvals.asReversed().firstNotNullOfOrNull { approval ->
            DEVICE_ACTION_BY_TOOL_NAME[approval.toolName]
                ?.takeIf { approval.outcome == WorkflowDeviceActionApprovalOutcome.APPROVED }
        } ?: return null
        return when (WorkflowDeviceActionApprovalEvidencePolicy.classifyExecutionFailure(errorMessage)) {
            WorkflowDeviceActionApprovalOutcome.WINDOW_CHANGED -> WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.WINDOW_CHANGED,
                action = approvedAction,
                detail = "审批后页面窗口发生变化，设备动作未通过执行验证",
            )
            WorkflowDeviceActionApprovalOutcome.SERVICE_DISCONNECTED -> WorkflowDeviceActionUiState(
                outcome = WorkflowDeviceActionUiOutcome.SERVICE_DISCONNECTED,
                action = approvedAction,
                detail = "审批后无障碍服务已断开，设备动作未通过执行验证",
            )
            WorkflowDeviceActionApprovalOutcome.PENDING,
            WorkflowDeviceActionApprovalOutcome.APPROVED,
            WorkflowDeviceActionApprovalOutcome.USER_DENIED,
            WorkflowDeviceActionApprovalOutcome.EXPIRED,
            WorkflowDeviceActionApprovalOutcome.CANCELLED,
            WorkflowDeviceActionApprovalOutcome.OVERLAY_UNAVAILABLE,
            WorkflowDeviceActionApprovalOutcome.BUSY,
            null,
            -> null
        }
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
    private const val DEVICE_OBSERVATION_RUN_ERROR_NOTICE = "设备观察错误详情已隐藏，请查看步骤中的已验证证据"
    private const val DEVICE_ACTION_STEP_OUTPUT_NOTICE = "设备动作原始结果已隐藏，请查看下方本地判定"
    private const val DEVICE_ACTION_PREVIOUS_OUTPUT_NOTICE = "设备动作原始输出已隐藏，请查看对应步骤证据"
    private const val DEVICE_ACTION_RUN_RESULT_NOTICE = "设备动作原始结果已隐藏，请查看步骤中的本地判定"
    private const val DEVICE_ACTION_RUN_ERROR_NOTICE = "设备动作错误详情已隐藏，请查看步骤中的本地判定"
    private val DEVICE_ACTION_BY_TOOL_NAME = mapOf(
        "device.tap_ref" to "tap_ref",
        "device.type_text" to "type_text",
    )
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
