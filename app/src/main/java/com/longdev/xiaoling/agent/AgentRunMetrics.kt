package com.longdev.xiaoling.agent

import kotlin.math.roundToInt

data class AgentRunMetrics(
    val elapsedMs: Long,
    val modelCallCount: Int,
    val toolCallCount: Int,
    val approvalRequestCount: Int,
)

data class AgentRunHistoryMetrics(
    val runCount: Int,
    val terminalRunCount: Int,
    val completedRunCount: Int,
    val nonSuccessfulRunCount: Int,
    val successRatePercent: Int?,
    val averageElapsedMs: Long?,
    val modelCallCount: Int,
    val toolCallCount: Int,
)

object AgentRunMetricsPolicy {
    private val modelStepTypes = setOf(AgentStepTypes.LLM_PLAN, AgentStepTypes.LLM_SUMMARIZE)

    fun summarizeRun(detail: AgentRunDetailRecord, nowMs: Long): AgentRunMetrics {
        val snapshot = detail.snapshot
        val run = snapshot.run
        val endAt = run.completedAt
            ?: run.updatedAt.takeIf { run.status.isTerminal }
            ?: nowMs
        return AgentRunMetrics(
            elapsedMs = (endAt.coerceAtLeast(run.createdAt) - run.createdAt),
            modelCallCount = snapshot.steps.count { it.type in modelStepTypes },
            toolCallCount = snapshot.steps.count { it.type == AgentStepTypes.TOOL_EXECUTE },
            approvalRequestCount = detail.approvals.size,
        )
    }

    fun summarizeHistory(details: List<AgentRunDetailRecord>, nowMs: Long): AgentRunHistoryMetrics {
        val runMetrics = details.associateWith { summarizeRun(it, nowMs) }
        val terminalDetails = details.filter { it.snapshot.run.status.isTerminal }
        val completedCount = terminalDetails.count { it.snapshot.run.status == AgentRunStatus.COMPLETED }
        // long: 活动 Run 尚未形成业务终态，不能进入成功率和平均耗时分母，否则刷新时会让历史质量指标无意义地波动。
        val successRate = terminalDetails.takeIf { it.isNotEmpty() }?.let {
            (completedCount * 100.0 / it.size).roundToInt()
        }
        val averageElapsed = terminalDetails.takeIf { it.isNotEmpty() }?.let {
            it.sumOf { detail -> runMetrics.getValue(detail).elapsedMs } / it.size
        }
        return AgentRunHistoryMetrics(
            runCount = details.size,
            terminalRunCount = terminalDetails.size,
            completedRunCount = completedCount,
            nonSuccessfulRunCount = terminalDetails.size - completedCount,
            successRatePercent = successRate,
            averageElapsedMs = averageElapsed,
            modelCallCount = runMetrics.values.sumOf { it.modelCallCount },
            toolCallCount = runMetrics.values.sumOf { it.toolCallCount },
        )
    }
}
