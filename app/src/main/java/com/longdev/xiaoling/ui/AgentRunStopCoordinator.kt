package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.isTerminal

internal class AgentRunStopCoordinator(
    private val loadRun: suspend (String) -> AgentRunDetailRecord?,
    private val linkedScheduledTaskId: suspend (String) -> String?,
    private val stopScheduledTask: suspend (String) -> Unit,
    private val requestCancel: suspend (String, String) -> Boolean,
    private val isExecuting: (String) -> Boolean,
    private val cancelInactiveRun: suspend (String, String) -> Boolean,
    private val settleCancelledWorkflow: suspend (String, String) -> Unit,
) {
    suspend fun stop(runId: String, reason: String): AgentRunDetailRecord? {
        val initial = loadRun(runId) ?: return null
        if (initial.snapshot.run.status.isTerminal) return initial
        val scheduledTaskId = linkedScheduledTaskId(runId)
        if (scheduledTaskId != null) {
            // long: 后台停止必须先写 Task 的 STOP_REQUESTED，再取消系统 Work 并有界兜底，不能仅关闭 Agent 留下正在运行的任务。
            stopScheduledTask(scheduledTaskId)
        } else {
            requestCancel(runId, reason)
            if (!isExecuting(runId)) {
                cancelInactiveRun(runId, reason)
                if (loadRun(runId)?.snapshot?.run?.status == AgentRunStatus.CANCELLED) {
                    // long: 恢复的审批等待没有执行协程可进入 finally；直接关闭关联 Workflow 和未完成后缀，保留已完成步骤。
                    settleCancelledWorkflow(runId, reason)
                }
            }
        }
        return loadRun(runId)
    }
}
