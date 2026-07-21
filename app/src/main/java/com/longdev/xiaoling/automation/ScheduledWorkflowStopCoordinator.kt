package com.longdev.xiaoling.automation

internal enum class ScheduledWorkflowStopOutcome {
    NOT_FOUND,
    NOT_RUNNING,
    SCHEDULE_CANCELLED,
    STOPPED,
    STOP_REQUESTED,
}

internal data class ScheduledWorkflowStopResult(
    val outcome: ScheduledWorkflowStopOutcome,
    val task: ScheduledTaskRecord?,
    val systemCancellationFailed: Boolean = false,
)

internal class ScheduledWorkflowStopCoordinator(
    private val loadTask: suspend (String) -> ScheduledTaskRecord?,
    private val cancelPendingTask: suspend (String) -> ScheduledTaskRecord?,
    private val requestScheduledTaskStop: suspend (String) -> ScheduledTaskRecord?,
    private val cancelSystemWork: suspend (String) -> Unit,
    private val waitForWorkerSettlement: suspend () -> Unit,
    private val reconcileUnsettledTask: suspend (String) -> Boolean,
    private val settlementChecks: Int = 6,
) {
    init {
        require(settlementChecks > 0) { "停止状态检查次数必须大于 0" }
    }

    suspend fun stop(taskId: String): ScheduledWorkflowStopResult {
        val initial = loadTask(taskId)
            ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null)
        when {
            initial.status == ScheduledTaskStatus.SCHEDULED -> {
                // long: 先在 Room 事务内取消待执行实例；若 Worker 已抢占为 RUNNING，返回的新状态会把同一次点击升级为运行中停止，避免依赖过期 UI 快照。
                val cancelled = cancelPendingTask(taskId)
                    ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null)
                when (cancelled.status) {
                    ScheduledTaskStatus.CANCELLED -> {
                        val systemCancellationFailed = runCatching { cancelSystemWork(taskId) }.isFailure
                        return ScheduledWorkflowStopResult(
                            outcome = ScheduledWorkflowStopOutcome.SCHEDULE_CANCELLED,
                            task = cancelled,
                            systemCancellationFailed = systemCancellationFailed,
                        )
                    }
                    ScheduledTaskStatus.RUNNING -> Unit
                    else -> return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_RUNNING, cancelled)
                }
            }
            ScheduledTaskPolicy.requiresExecutionReconciliation(initial.status) -> Unit
            else -> return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_RUNNING, initial)
        }

        val stopRequested = requestScheduledTaskStop(taskId)
            ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null)
        if (stopRequested.status != ScheduledTaskStatus.STOP_REQUESTED) {
            return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_RUNNING, stopRequested)
        }

        // long: STOP_REQUESTED 已先成为持久化取消栅栏；WorkManager 或即时兜底异常只影响本轮速度，后续启动恢复仍会继续收敛同一链。
        val systemCancellationFailure = runCatching { cancelSystemWork(taskId) }.exceptionOrNull()
        if (systemCancellationFailure != null) {
            runCatching { reconcileUnsettledTask(taskId) }
            val settled = loadTask(taskId)
                ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null, true)
            val outcome = if (ScheduledTaskPolicy.requiresExecutionReconciliation(settled.status)) {
                ScheduledWorkflowStopOutcome.STOP_REQUESTED
            } else {
                ScheduledWorkflowStopOutcome.STOPPED
            }
            return ScheduledWorkflowStopResult(outcome, settled, true)
        }
        repeat(settlementChecks) {
            waitForWorkerSettlement()
            val current = loadTask(taskId)
                ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null)
            if (!ScheduledTaskPolicy.requiresExecutionReconciliation(current.status)) {
                return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.STOPPED, current)
            }
        }

        // long: Worker 没有在有界窗口内写入终态时才按持久化关联链兜底，防止系统已接受停止请求但任务中心永久显示运行中。
        runCatching { reconcileUnsettledTask(taskId) }
        val settled = loadTask(taskId)
            ?: return ScheduledWorkflowStopResult(ScheduledWorkflowStopOutcome.NOT_FOUND, null)
        val outcome = if (ScheduledTaskPolicy.requiresExecutionReconciliation(settled.status)) {
            ScheduledWorkflowStopOutcome.STOP_REQUESTED
        } else {
            ScheduledWorkflowStopOutcome.STOPPED
        }
        return ScheduledWorkflowStopResult(outcome, settled)
    }
}
