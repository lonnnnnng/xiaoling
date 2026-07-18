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
    val scheduledTaskId: String?,
    val plannedAt: Long?,
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
    SCHEDULED,
}

enum class WorkflowRunStatus {
    QUEUED,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WorkflowStepStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object WorkflowAgentRunStatusPolicy {
    fun terminalStatus(agentStatus: AgentRunStatus): WorkflowRunStatus? {
        // long: 前台执行、审批恢复和启动对账必须共享同一终态映射，新增 Agent 状态时不能让两条链路产生不同 Workflow 结论。
        return when (agentStatus) {
            AgentRunStatus.COMPLETED -> WorkflowRunStatus.COMPLETED
            AgentRunStatus.BLOCKED -> WorkflowRunStatus.BLOCKED
            AgentRunStatus.CANCELLED -> WorkflowRunStatus.CANCELLED
            AgentRunStatus.FAILED,
            AgentRunStatus.BUDGET_EXHAUSTED -> WorkflowRunStatus.FAILED
            else -> null
        }
    }
}

data class ScheduledTaskRecord(
    val id: String,
    val workflowId: String,
    val type: ScheduledTaskType,
    val status: ScheduledTaskStatus,
    val plannedAt: Long,
    val workRequestId: String?,
    val workflowRunId: String?,
    val actualStartedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ScheduledTaskType {
    ONE_TIME,
}

enum class ScheduledTaskStatus {
    SCHEDULED,
    RUNNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

object ScheduledTaskPolicy {
    const val MIN_DELAY_MINUTES = 1
    const val MAX_DELAY_MINUTES = 7 * 24 * 60

    fun plannedAt(now: Long, delayMinutes: Int): Long {
        require(delayMinutes in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) {
            "一次性调度延迟必须在 $MIN_DELAY_MINUTES 到 $MAX_DELAY_MINUTES 分钟之间"
        }
        return Math.addExact(now, Math.multiplyExact(delayMinutes.toLong(), 60_000L))
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
