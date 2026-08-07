package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility

internal enum class AgentTaskFilter(val label: String) {
    ALL("全部"),
    INTERRUPTED("已中断"),
    NEEDS_CONFIRMATION("需确认"),
    ACTIVE("处理中"),
    RETRYABLE("可重试"),
    COMPLETED("已完成"),
}

internal fun AgentTaskFilter.matches(
    status: AgentRunStatus,
    retryEligibility: AgentTaskRetryEligibility,
): Boolean {
    return when (this) {
        AgentTaskFilter.ALL -> true
        // long: 启动恢复只把已落定的失败/取消 Run 带到处理中断视图，不把待审批或活动 Run 误归为中断。
        AgentTaskFilter.INTERRUPTED -> status in interruptedStatuses
        // long: “需确认”只收拢不能直接重试的终态 Run，用户确认后仍创建关联新 Run，绝不恢复或重放旧执行栈。
        AgentTaskFilter.NEEDS_CONFIRMATION ->
            status in retryableStatuses &&
                retryEligibility is AgentTaskRetryEligibility.Retryable &&
                retryEligibility.requiresConfirmation
        AgentTaskFilter.ACTIVE -> status in activeStatuses
        AgentTaskFilter.RETRYABLE ->
            status in retryableStatuses && retryEligibility is AgentTaskRetryEligibility.Retryable
        AgentTaskFilter.COMPLETED -> status == AgentRunStatus.COMPLETED
    }
}

private val activeStatuses = setOf(
    AgentRunStatus.QUEUED,
    AgentRunStatus.THINKING,
    AgentRunStatus.WAITING_APPROVAL,
    AgentRunStatus.EXECUTING,
    AgentRunStatus.VERIFYING,
)

private val interruptedStatuses = setOf(
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
)

private val retryableStatuses = setOf(
    AgentRunStatus.BLOCKED,
    AgentRunStatus.FAILED,
    AgentRunStatus.CANCELLED,
    AgentRunStatus.BUDGET_EXHAUSTED,
)
