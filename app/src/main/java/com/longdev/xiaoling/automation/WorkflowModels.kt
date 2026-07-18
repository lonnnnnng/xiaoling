package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentRunStatus

data class WorkflowRecord(
    val id: String,
    val name: String,
    val goal: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkflowRunRecord(
    val id: String,
    val workflowId: String,
    val trigger: WorkflowTrigger,
    val conversationId: String,
    val agentRunId: String?,
    val status: WorkflowRunStatus,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)

data class WorkflowStepRecord(
    val id: String,
    val workflowRunId: String,
    val sequence: Int,
    val type: String,
    val status: WorkflowStepStatus,
    val title: String,
    val detail: String,
    val agentRunId: String?,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
)

data class WorkflowRunDetail(
    val run: WorkflowRunRecord,
    val steps: List<WorkflowStepRecord>,
)

enum class WorkflowTrigger {
    MANUAL,
}

enum class WorkflowRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WorkflowStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object WorkflowAgentRunStatusPolicy {
    fun terminalStatus(agentStatus: AgentRunStatus): WorkflowRunStatus? {
        // long: 前台执行、审批恢复和启动对账必须共享同一终态映射，新增 Agent 状态时不能让两条链路产生不同 Workflow 结论。
        return when (agentStatus) {
            AgentRunStatus.COMPLETED -> WorkflowRunStatus.COMPLETED
            AgentRunStatus.CANCELLED -> WorkflowRunStatus.CANCELLED
            AgentRunStatus.FAILED,
            AgentRunStatus.BUDGET_EXHAUSTED -> WorkflowRunStatus.FAILED
            else -> null
        }
    }
}

object WorkflowDefinitionPolicy {
    const val MAX_NAME_LENGTH = 80
    const val MAX_GOAL_LENGTH = 2_000

    fun validate(name: String, goal: String) {
        require(name.isNotBlank()) { "工作流名称不能为空" }
        require(name.length <= MAX_NAME_LENGTH) { "工作流名称不能超过 $MAX_NAME_LENGTH 个字符" }
        require(goal.isNotBlank()) { "工作流目标不能为空" }
        require(goal.length <= MAX_GOAL_LENGTH) { "工作流目标不能超过 $MAX_GOAL_LENGTH 个字符" }
    }
}
