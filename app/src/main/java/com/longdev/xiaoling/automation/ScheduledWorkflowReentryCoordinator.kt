package com.longdev.xiaoling.automation

internal class ScheduledWorkflowReentryCoordinator(
    private val loadTask: suspend (String) -> ScheduledTaskRecord?,
    private val loadWorkflowRun: suspend (String) -> WorkflowRunDetail?,
    private val closeAgentRun: suspend (String) -> Boolean,
    private val reconcileWorkflowRun: suspend (String) -> Boolean,
    private val reconcileScheduledTask: suspend (String) -> Boolean,
) {
    suspend fun reconcile(taskId: String): Boolean {
        val task = loadTask(taskId) ?: return false
        if (task.status !in setOf(ScheduledTaskStatus.RUNNING, ScheduledTaskStatus.STOP_REQUESTED)) return false

        val workflowRunId = task.workflowRunId
        val workflowRun = workflowRunId?.let { loadWorkflowRun(it) }
        workflowRun?.run?.agentRunId?.let { agentRunId ->
            // long: 同一 WorkRequest 被系统重新拉起时，旧执行栈已经消失；只关闭当前任务关联的 Agent，不能扫描并影响其他前台 Run。
            closeAgentRun(agentRunId)
        }
        workflowRunId?.let { reconcileWorkflowRun(it) }
        // long: ScheduledTask 必须最后依据 Workflow 终态收敛；顺序倒置会把尚未对账的 RUNNING 误写成缺失执行栈失败。
        reconcileScheduledTask(task.id)
        return true
    }
}
