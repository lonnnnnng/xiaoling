package com.longdev.xiaoling.automation

internal class ScheduledWorkflowStopFallbackCoordinator(
    private val loadTask: suspend (String) -> ScheduledTaskRecord?,
    private val loadWorkflowRun: suspend (String) -> WorkflowRunDetail?,
    private val cancelAgentRun: suspend (String) -> Boolean,
    private val cancelWorkflowRun: suspend (String, String) -> Unit,
    private val cancelScheduledTask: suspend (String, String) -> Unit,
) {
    suspend fun reconcile(taskId: String): Boolean {
        val task = loadTask(taskId) ?: return false
        if (!ScheduledTaskPolicy.requiresExecutionReconciliation(task.status)) return false
        val reason = "用户停止后台工作流"
        val workflowRunId = task.workflowRunId
        val workflowRun = workflowRunId?.let { loadWorkflowRun(it) }

        // long: 用户主动停止与进程重入的缺失执行栈语义不同；即使 Agent 尚未关联，也必须把已创建的 Workflow 明确收敛为 CANCELLED，而不是伪报系统恢复失败。
        workflowRun?.run?.agentRunId?.let { cancelAgentRun(it) }
        workflowRunId?.let { cancelWorkflowRun(it, reason) }
        // long: 调度实例最后收敛，保证页面看到 CANCELLED 时，能够关联到的 Agent 和 Workflow 已先完成终态写入。
        cancelScheduledTask(task.id, reason)
        return true
    }
}
