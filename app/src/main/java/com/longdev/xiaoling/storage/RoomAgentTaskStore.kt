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
import com.longdev.xiaoling.agent.AgentTaskRunDiagnosis
import com.longdev.xiaoling.agent.AgentTaskRunStepRecord
import com.longdev.xiaoling.agent.AgentTaskStore
import com.longdev.xiaoling.automation.ScheduledTaskPolicy
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledWorkflowStopCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopFallbackCoordinator
import com.longdev.xiaoling.automation.ScheduledWorkflowStopOutcome
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepStatus
import kotlinx.coroutines.delay

internal class RoomAgentTaskStore(
    context: Context,
    private val repository: RoomWorkflowRepository = RoomWorkflowRepository(context.applicationContext),
    private val stopCoordinator: ScheduledWorkflowStopCoordinator = defaultStopCoordinator(
        context.applicationContext,
        repository,
    ),
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
            .filter { schedule -> schedule.workflowId in workflowIds && schedule.enabled }
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
                else -> null
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
