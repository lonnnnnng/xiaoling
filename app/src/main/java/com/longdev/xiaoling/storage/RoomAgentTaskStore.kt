package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentTaskInspectionRecord
import com.longdev.xiaoling.agent.AgentTaskInspectionResult
import com.longdev.xiaoling.agent.AgentTaskCancelOutcome
import com.longdev.xiaoling.agent.AgentTaskCancelRecord
import com.longdev.xiaoling.agent.AgentTaskCancelResult
import com.longdev.xiaoling.agent.AgentTaskRecord
import com.longdev.xiaoling.agent.AgentTaskRetryRecord
import com.longdev.xiaoling.agent.AgentTaskRetryResult
import com.longdev.xiaoling.agent.AgentTaskRetryVerificationResult
import com.longdev.xiaoling.agent.AgentTaskScheduleMutationRecord
import com.longdev.xiaoling.agent.AgentTaskScheduleMutationResult
import com.longdev.xiaoling.agent.AgentTaskScheduleState
import com.longdev.xiaoling.agent.AgentTaskRunDiagnosis
import com.longdev.xiaoling.agent.AgentTaskRunStepRecord
import com.longdev.xiaoling.agent.AgentTaskStore
import com.longdev.xiaoling.automation.ScheduledTaskPolicy
import com.longdev.xiaoling.automation.ScheduledTaskScheduler
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledWorkflowStopCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopFallbackCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopOutcome
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleRecord
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepStatus
import kotlinx.coroutines.delay

private sealed interface RecurringScheduleLookup {
    data class Found(
        val name: String,
        val workflowEnabled: Boolean,
        val schedule: WorkflowScheduleRecord,
    ) : RecurringScheduleLookup

    data class Ambiguous(val matchCount: Int) : RecurringScheduleLookup
    data object NotFound : RecurringScheduleLookup
    data object NoRecurringSchedule : RecurringScheduleLookup
}

internal class RoomAgentTaskStore(
    context: Context,
    private val repository: RoomWorkflowRepository = RoomWorkflowRepository(context.applicationContext),
    private val stopCoordinator: ScheduledWorkflowStopCoordinator = defaultStopCoordinator(
        context.applicationContext,
        repository,
    ),
    private val scheduler: ScheduledTaskScheduler = WorkManagerScheduledTaskScheduler(context.applicationContext),
) : AgentTaskStore {
    override suspend fun list(limit: Int): List<AgentTaskRecord> {
        require(limit in 1..10) { "任务清单条数必须在 1 到 10 之间" }
        val workflows = repository.listWorkflows()
            .sortedByDescending { workflow -> workflow.updatedAt }
            .take(limit)
        if (workflows.isEmpty()) return emptyList()

        val workflowIds = workflows.mapTo(hashSetOf()) { workflow -> workflow.id }
        val latestRuns = repository.latestRunsForWorkflows(workflowIds.toList())
            .associateBy { run -> run.workflowId }
        val scheduledTasks = repository.listScheduledTasks()
            .asSequence()
            .filter { task -> task.workflowId in workflowIds && ScheduledTaskPolicy.isUnsettled(task.status) }
            .groupBy { task -> task.workflowId }
            .mapValues { (_, tasks) -> tasks.minBy { task -> task.plannedAt } }
        val schedules = repository.listWorkflowSchedules()
            .asSequence()
            .filter { schedule -> schedule.workflowId in workflowIds }
            .associateBy { schedule -> schedule.workflowId }

        return workflows.map { workflow ->
            val schedule = schedules[workflow.id]
            val scheduledTask = scheduledTasks[workflow.id]
            val schedulePlannedAt = schedule?.nextPlannedAt
            val taskPlannedAt = scheduledTask?.plannedAt
            val nextPlannedAt = listOfNotNull(schedulePlannedAt, taskPlannedAt).minOrNull()
            val scheduleType = when (nextPlannedAt) {
                schedulePlannedAt -> schedule?.type?.name
                taskPlannedAt -> scheduledTask?.type?.name
                else -> schedule?.type?.name
            }
            // long: Agent 只读取用户可理解的任务摘要；Room 内部 Run/Task/Schedule ID、错误详情和步骤输出不进入工具结果。
            AgentTaskRecord(
                name = workflow.name,
                goal = workflow.goal,
                enabled = workflow.enabled,
                stepCount = workflow.steps.size,
                updatedAt = workflow.updatedAt,
                latestRunStatus = latestRuns[workflow.id]?.status?.name,
                scheduleType = scheduleType,
                nextPlannedAt = nextPlannedAt,
                recurringScheduleEnabled = schedule?.enabled,
            )
        }
    }

    override suspend fun inspect(name: String): AgentTaskInspectionResult {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "任务名称不能为空" }
        require(normalizedName.length <= 100) { "任务名称不能超过 100 个字符" }
        val matches = repository.listWorkflows().filter { workflow -> workflow.name == normalizedName }
        if (matches.isEmpty()) return AgentTaskInspectionResult.NotFound
        if (matches.size > 1) return AgentTaskInspectionResult.Ambiguous(matches.size)

        val workflow = matches.single()
        val recurringSchedule = repository.listWorkflowSchedules().singleOrNull { schedule ->
            schedule.workflowId == workflow.id
        }
        val latestRun = repository.latestRunsForWorkflows(listOf(workflow.id)).singleOrNull()
        if (latestRun == null) {
            return AgentTaskInspectionResult.Found(
                AgentTaskInspectionRecord(
                    name = workflow.name,
                    goal = workflow.goal,
                    enabled = workflow.enabled,
                    latestRunStatus = null,
                    latestRunTrigger = null,
                    latestRunStartedAt = null,
                    latestRunCompletedAt = null,
                    diagnosis = null,
                    steps = emptyList(),
                    recurringScheduleType = recurringSchedule?.type?.name,
                    recurringScheduleEnabled = recurringSchedule?.enabled,
                    recurringNextPlannedAt = recurringSchedule?.nextPlannedAt,
                ),
            )
        }

        val detail = repository.runDetail(latestRun.id)
        // long: Agent 诊断只暴露稳定状态分类；原始错误、步骤输入输出和内部 ID 留在任务中心审计页，不进入模型上下文。
        return AgentTaskInspectionResult.Found(
            AgentTaskInspectionRecord(
                name = workflow.name,
                goal = workflow.goal,
                enabled = workflow.enabled,
                latestRunStatus = latestRun.status.name,
                latestRunTrigger = latestRun.trigger.name,
                latestRunStartedAt = latestRun.startedAt,
                latestRunCompletedAt = latestRun.completedAt,
                diagnosis = latestRun.toDiagnosis(detail?.steps),
                steps = detail?.steps.orEmpty()
                    .sortedBy { step -> step.sequence }
                    .map { step -> AgentTaskRunStepRecord(sequence = step.sequence, status = step.status.name) },
                recurringScheduleType = recurringSchedule?.type?.name,
                recurringScheduleEnabled = recurringSchedule?.enabled,
                recurringNextPlannedAt = recurringSchedule?.nextPlannedAt,
            ),
        )
    }

    override suspend fun retry(
        name: String,
        conversationId: String,
        idempotencyKey: String,
    ): AgentTaskRetryResult {
        return when (
            val result = repository.retryLatestRunByTaskName(
                name = name,
                conversationId = conversationId,
                idempotencyKey = idempotencyKey,
            )
        ) {
            WorkflowTaskRetryCommitResult.NotFound -> AgentTaskRetryResult.NotFound
            is WorkflowTaskRetryCommitResult.Ambiguous -> AgentTaskRetryResult.Ambiguous(result.matchCount)
            is WorkflowTaskRetryCommitResult.Rejected -> AgentTaskRetryResult.Rejected(result.reason)
            WorkflowTaskRetryCommitResult.IdempotencyConflict -> AgentTaskRetryResult.IdempotencyConflict
            is WorkflowTaskRetryCommitResult.Queued -> AgentTaskRetryResult.Queued(
                AgentTaskRetryRecord(
                    name = name.trim(),
                    workflowRunId = result.detail.run.id,
                    reusedStepCount = result.reusedStepCount,
                    alreadyQueued = result.alreadyQueued,
                ),
            )
        }
    }

    override suspend fun verifyRetry(
        name: String,
        conversationId: String,
        idempotencyKey: String,
        workflowRunId: String,
    ): AgentTaskRetryVerificationResult {
        val verified = repository.verifyTaskRetry(
            name = name,
            conversationId = conversationId,
            idempotencyKey = idempotencyKey,
            workflowRunId = workflowRunId,
        ) ?: return AgentTaskRetryVerificationResult.Failed
        return AgentTaskRetryVerificationResult.Verified(
            AgentTaskRetryRecord(
                name = name.trim(),
                workflowRunId = verified.detail.run.id,
                reusedStepCount = verified.reusedStepCount,
                alreadyQueued = true,
            ),
        )
    }

    override suspend fun cancel(
        name: String,
        conversationId: String,
        idempotencyKey: String,
    ): AgentTaskCancelResult {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "任务名称不能为空" }
        require(normalizedName.length <= 100) { "任务名称不能超过 100 个字符" }
        // long: 取消依据 Room 的 ScheduledTask 状态而不是模型文本；会话和 ToolCall 只保留在 Runtime 审计，不能改变精确任务解析。
        val workflows = repository.listWorkflows().filter { workflow -> workflow.name == normalizedName }
        if (workflows.isEmpty()) return AgentTaskCancelResult.NotFound
        if (workflows.size > 1) return AgentTaskCancelResult.Ambiguous(workflows.size)
        val workflowId = workflows.single().id
        val tasks = repository.listScheduledTasks()
            .filter { task -> task.workflowId == workflowId }
        val activeTasks = tasks.filter { task -> ScheduledTaskPolicy.isUnsettled(task.status) }
        if (activeTasks.size > 1) return AgentTaskCancelResult.Ambiguous(activeTasks.size)
        val task = activeTasks.singleOrNull()
        if (task == null) {
            val latest = tasks.maxByOrNull { scheduledTask -> scheduledTask.updatedAt }
            return if (latest?.status == ScheduledTaskStatus.CANCELLED) {
                AgentTaskCancelResult.AlreadyCancelled(normalizedName)
            } else {
                AgentTaskCancelResult.NoActiveSchedule
            }
        }
        val stopped = stopCoordinator.stop(task.id)
        // long: 先按 Coordinator 的稳定 outcome 分支，再读取同一返回中的任务状态，避免把停止竞态误判为普通失败。
        return when (stopped.outcome) {
            ScheduledWorkflowStopOutcome.NOT_FOUND -> AgentTaskCancelResult.NotFound
            ScheduledWorkflowStopOutcome.NOT_RUNNING -> {
                if (stopped.task?.status == ScheduledTaskStatus.CANCELLED) {
                    AgentTaskCancelResult.AlreadyCancelled(normalizedName)
                } else {
                    AgentTaskCancelResult.NoActiveSchedule
                }
            }
            ScheduledWorkflowStopOutcome.SCHEDULE_CANCELLED,
            ScheduledWorkflowStopOutcome.STOPPED,
            ScheduledWorkflowStopOutcome.STOP_REQUESTED,
            -> AgentTaskCancelResult.Cancelled(
                AgentTaskCancelRecord(
                    name = normalizedName,
                    status = stopped.task?.status?.name ?: ScheduledTaskStatus.STOP_REQUESTED.name,
                    outcome = stopped.outcome.toAgentTaskCancelOutcome(),
                    systemCancellationFailed = stopped.systemCancellationFailed,
                ),
            )
        }
    }

    override suspend fun pause(
        name: String,
        conversationId: String,
        idempotencyKey: String,
    ): AgentTaskScheduleMutationResult {
        val lookup = when (val resolved = resolveRecurringSchedule(name)) {
            RecurringScheduleLookup.NotFound -> return AgentTaskScheduleMutationResult.NotFound
            RecurringScheduleLookup.NoRecurringSchedule -> return AgentTaskScheduleMutationResult.NoRecurringSchedule
            is RecurringScheduleLookup.Ambiguous -> return AgentTaskScheduleMutationResult.Ambiguous(resolved.matchCount)
            is RecurringScheduleLookup.Found -> resolved
        }
        val result = runCatching { repository.pauseWorkflowSchedule(lookup.schedule.id) }
            .getOrElse { error ->
                return AgentTaskScheduleMutationResult.Rejected(error.message ?: "暂停周期计划失败")
            }
            ?: return AgentTaskScheduleMutationResult.Rejected("周期计划状态已经变化，请重新读取任务后再暂停。")
        val systemCancellationFailed = result.cancelledTaskId?.let { taskId ->
            runCatching { scheduler.cancel(taskId) }.isFailure
        } ?: false
        val current = repository.listWorkflowSchedules().singleOrNull { schedule -> schedule.id == result.schedule.id }
        if (current == null || current.enabled || current.nextTaskId != null) {
            return AgentTaskScheduleMutationResult.Rejected("周期计划暂停后回读不一致，已停止报告成功。")
        }
        val record = AgentTaskScheduleMutationRecord(
            name = lookup.name,
            state = AgentTaskScheduleState.PAUSED,
            scheduleType = current.type.name,
            nextPlannedAt = null,
            runningTaskUnaffected = result.runningTaskUnaffected,
            systemOperationFailed = systemCancellationFailed,
        )
        // long: 同一计划的重复暂停由 Room 当前状态判定为幂等成功；会话和 ToolCall ID 只参与 Runtime 审计，不参与猜测任务身份。
        return if (result.changed) {
            AgentTaskScheduleMutationResult.Changed(record)
        } else {
            AgentTaskScheduleMutationResult.AlreadyInState(record)
        }
    }

    override suspend fun resume(
        name: String,
        conversationId: String,
        idempotencyKey: String,
    ): AgentTaskScheduleMutationResult {
        val lookup = when (val resolved = resolveRecurringSchedule(name)) {
            RecurringScheduleLookup.NotFound -> return AgentTaskScheduleMutationResult.NotFound
            RecurringScheduleLookup.NoRecurringSchedule -> return AgentTaskScheduleMutationResult.NoRecurringSchedule
            is RecurringScheduleLookup.Ambiguous -> return AgentTaskScheduleMutationResult.Ambiguous(resolved.matchCount)
            is RecurringScheduleLookup.Found -> resolved
        }
        if (!lookup.workflowEnabled) return AgentTaskScheduleMutationResult.Rejected("任务已停用，不能恢复周期计划。")
        val result = runCatching { repository.resumeWorkflowSchedule(lookup.schedule.id) }
            .getOrElse { error ->
                return AgentTaskScheduleMutationResult.Rejected(error.message ?: "恢复周期计划失败")
            }
            ?: return AgentTaskScheduleMutationResult.Rejected("周期计划状态已经变化，请重新读取任务后再恢复。")
        val task = result.task
        if (result.changed && task != null) {
            try {
                val workRequestId = scheduler.enqueue(task)
                repository.attachWorkRequest(task.id, workRequestId)
            } catch (error: Throwable) {
                runCatching { scheduler.cancel(task.id) }
                val rolledBack = runCatching {
                    repository.rollbackWorkflowScheduleResume(
                        scheduleId = result.schedule.id,
                        taskId = task.id,
                        reason = error.message ?: "恢复周期计划后系统入队失败",
                    )
                }.getOrDefault(false)
                return AgentTaskScheduleMutationResult.Rejected(
                    if (rolledBack) {
                        "周期计划恢复后系统入队失败，计划已保持暂停，可重新尝试恢复。"
                    } else {
                        "周期计划恢复期间状态再次变化，请重新读取任务。"
                    },
                )
            }
        }
        val current = repository.listWorkflowSchedules().singleOrNull { schedule -> schedule.id == result.schedule.id }
            ?: return AgentTaskScheduleMutationResult.Rejected("周期计划恢复后无法回读，已停止报告成功。")
        if (!current.enabled) {
            return AgentTaskScheduleMutationResult.Rejected("周期计划恢复期间状态再次变化，请重新读取任务。")
        }
        val currentTask = current.nextTaskId?.let { taskId -> repository.getScheduledTask(taskId) }
        if (
            result.changed && (
                current.nextTaskId != task?.id ||
                    currentTask?.status != ScheduledTaskStatus.SCHEDULED ||
                    currentTask.workRequestId.isNullOrBlank()
                )
        ) {
            return AgentTaskScheduleMutationResult.Rejected("周期计划恢复后的系统调度证据不一致，已停止报告成功。")
        }
        val record = AgentTaskScheduleMutationRecord(
            name = lookup.name,
            state = AgentTaskScheduleState.ACTIVE,
            scheduleType = current.type.name,
            nextPlannedAt = current.nextPlannedAt,
            runningTaskUnaffected = false,
            systemOperationFailed = false,
        )
        return if (result.changed) {
            AgentTaskScheduleMutationResult.Changed(record)
        } else {
            AgentTaskScheduleMutationResult.AlreadyInState(record)
        }
    }

    private suspend fun resolveRecurringSchedule(name: String): RecurringScheduleLookup {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "任务名称不能为空" }
        require(normalizedName.length <= 100) { "任务名称不能超过 100 个字符" }
        val workflows = repository.listWorkflows().filter { workflow -> workflow.name == normalizedName }
        if (workflows.isEmpty()) return RecurringScheduleLookup.NotFound
        if (workflows.size > 1) return RecurringScheduleLookup.Ambiguous(workflows.size)
        val workflow = workflows.single()
        val schedules = repository.listWorkflowSchedules().filter { schedule -> schedule.workflowId == workflow.id }
        if (schedules.isEmpty()) return RecurringScheduleLookup.NoRecurringSchedule
        if (schedules.size > 1) return RecurringScheduleLookup.Ambiguous(schedules.size)
        return RecurringScheduleLookup.Found(normalizedName, workflow.enabled, schedules.single())
    }

    private fun ScheduledWorkflowStopOutcome.toAgentTaskCancelOutcome(): AgentTaskCancelOutcome {
        return when (this) {
            ScheduledWorkflowStopOutcome.SCHEDULE_CANCELLED -> AgentTaskCancelOutcome.SCHEDULE_CANCELLED
            ScheduledWorkflowStopOutcome.STOPPED -> AgentTaskCancelOutcome.STOPPED
            ScheduledWorkflowStopOutcome.STOP_REQUESTED -> AgentTaskCancelOutcome.STOP_REQUESTED
            else -> error("不可见的任务取消终态：$this")
        }
    }

    companion object {
        private fun defaultStopCoordinator(
            context: Context,
            repository: RoomWorkflowRepository,
        ): ScheduledWorkflowStopCoordinator {
            val agentRunRepository = RoomAgentRunRepository(context)
            val fallback = ScheduledWorkflowStopFallbackCoordinator(
                loadTask = repository::getScheduledTask,
                loadWorkflowRun = repository::runDetail,
                cancelAgentRun = { runId ->
                    agentRunRepository.cancelActiveRun(runId, "用户取消任务")
                },
                settleWorkflowAndTask = { taskId, workflowRunId, reason ->
                    repository.settleScheduledWorkflowRun(
                        taskId = taskId,
                        workflowRunId = workflowRunId,
                        workflowStatus = WorkflowRunStatus.CANCELLED,
                        taskStatus = ScheduledTaskStatus.CANCELLED,
                        errorMessage = reason,
                    )
                },
                settleTaskWithoutWorkflow = { taskId, reason ->
                    repository.finishScheduledTask(taskId, ScheduledTaskStatus.CANCELLED, reason)
                },
            )
            val scheduler = WorkManagerScheduledTaskScheduler(context)
            return ScheduledWorkflowStopCoordinator(
                loadTask = repository::getScheduledTask,
                cancelPendingTask = repository::cancelScheduledTask,
                requestScheduledTaskStop = { taskId ->
                    repository.requestScheduledTaskStop(taskId, "用户请求取消任务")
                },
                cancelSystemWork = scheduler::cancel,
                waitForWorkerSettlement = { delay(100L) },
                reconcileUnsettledTask = fallback::reconcile,
            )
        }
    }

    private fun WorkflowRunRecord.toDiagnosis(steps: List<WorkflowStepRecord>?): AgentTaskRunDiagnosis? {
        if (steps == null) return AgentTaskRunDiagnosis.EVIDENCE_INCOMPLETE
        return when (status) {
            WorkflowRunStatus.BLOCKED -> AgentTaskRunDiagnosis.AWAITING_ACTION
            WorkflowRunStatus.FAILED -> when {
                workerStopReasonCode != null || !workerStopReasonName.isNullOrBlank() -> {
                    AgentTaskRunDiagnosis.SYSTEM_INTERRUPTED
                }
                steps.any { step -> step.status == WorkflowStepStatus.FAILED } -> AgentTaskRunDiagnosis.STEP_FAILED
                else -> AgentTaskRunDiagnosis.EXECUTION_FAILED
            }
            WorkflowRunStatus.CANCELLED -> AgentTaskRunDiagnosis.CANCELLED
            WorkflowRunStatus.QUEUED,
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.COMPLETED,
            -> null
        }
    }
}
