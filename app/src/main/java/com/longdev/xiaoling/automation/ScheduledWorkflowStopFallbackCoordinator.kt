package com.longdev.xiaoling.automation

internal class ScheduledWorkflowStopFallbackCoordinator(
    private val loadTask: suspend (String) -> ScheduledTaskRecord?,
    private val loadWorkflowRun: suspend (String) -> WorkflowRunDetail?,
    private val cancelAgentRun: suspend (String) -> Boolean,
    private val settleWorkflowAndTask: suspend (String, String, String) -> Unit,
    private val settleTaskWithoutWorkflow: suspend (String, String) -> Unit,
) {
    suspend fun reconcile(taskId: String): Boolean {
        val task = loadTask(taskId) ?: return false
        if (!ScheduledTaskPolicy.requiresExecutionReconciliation(task.status)) return false
        val reason = "用户停止后台工作流"
        val workflowRunId = task.workflowRunId
        val workflowRun = workflowRunId?.let { loadWorkflowRun(it) }

        // long: 用户主动停止与进程重入的缺失执行栈语义不同；即使 Agent 尚未关联，也必须把已创建的 Workflow 明确收敛为 CANCELLED，而不是伪报系统恢复失败。
        workflowRun?.run?.agentRunId?.let { cancelAgentRun(it) }
        if (workflowRunId != null) {
            // long: Workflow 与 Task 必须在一个事务里依据停止栅栏和既有 Workflow 终态共同收敛；分两次写会在迟到终态窗口制造互相矛盾的账本。
            settleWorkflowAndTask(task.id, workflowRunId, reason)
        } else {
            settleTaskWithoutWorkflow(task.id, reason)
        }
        return true
    }
}
