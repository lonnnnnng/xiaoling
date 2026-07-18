package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunHistoryMetrics
import com.longdev.xiaoling.agent.AgentRunMetrics
import com.longdev.xiaoling.agent.AgentRunStatus
import java.util.Locale

internal data class AgentRunHistoryMetricsPresentation(
    val headline: String,
    val detail: String,
    val telemetry: String?,
    val failureDistribution: String,
)

internal fun presentAgentRunMetrics(metrics: AgentRunMetrics): String {
    return "耗时 ${metrics.elapsedMs.toCompactDurationText()}" +
        " · 模型 ${metrics.modelCallCount}" +
        " · 工具 ${metrics.toolCallCount}" +
        " · 审批 ${metrics.approvalRequestCount}"
}

internal fun presentAgentRunLlmMetrics(metrics: AgentRunMetrics): String? {
    if (metrics.modelLatencyMs == 0L && metrics.promptBytes == 0L && metrics.totalTokens == null) return null
    return presentLlmMetrics(
        modelLatencyMs = metrics.modelLatencyMs,
        averageFirstByteLatencyMs = metrics.averageFirstByteLatencyMs,
        promptBytes = metrics.promptBytes,
        totalTokens = metrics.totalTokens,
        tokenUsageRequestCount = metrics.tokenUsageRequestCount,
        modelCallCount = metrics.modelCallCount,
    )
}

internal fun presentAgentRunHistoryMetrics(metrics: AgentRunHistoryMetrics): AgentRunHistoryMetricsPresentation {
    val headline = if (metrics.successRatePercent != null && metrics.averageElapsedMs != null) {
        "${metrics.runCount} 个 Run · 成功率 ${metrics.successRatePercent}% · 平均 ${metrics.averageElapsedMs.toCompactDurationText()}"
    } else {
        "${metrics.runCount} 个 Run · 暂无终态质量数据"
    }
    return AgentRunHistoryMetricsPresentation(
        headline = headline,
        detail = "终态 ${metrics.terminalRunCount}" +
            " · 非成功 ${metrics.nonSuccessfulRunCount}" +
            " · 模型 ${metrics.modelCallCount}" +
            " · 工具 ${metrics.toolCallCount}",
        telemetry = if (metrics.modelLatencyMs == 0L && metrics.promptBytes == 0L && metrics.totalTokens == null) {
            null
        } else {
            presentLlmMetrics(
                modelLatencyMs = metrics.modelLatencyMs,
                averageFirstByteLatencyMs = metrics.averageFirstByteLatencyMs,
                promptBytes = metrics.promptBytes,
                totalTokens = metrics.totalTokens,
                tokenUsageRequestCount = metrics.tokenUsageRequestCount,
                modelCallCount = metrics.modelCallCount,
            )
        },
        failureDistribution = metrics.failureCounts.toFailureDistributionText(),
    )
}

private fun presentLlmMetrics(
    modelLatencyMs: Long,
    averageFirstByteLatencyMs: Long?,
    promptBytes: Long,
    totalTokens: Long?,
    tokenUsageRequestCount: Int,
    modelCallCount: Int,
): String {
    val tokenText = totalTokens?.let { "$it（$tokenUsageRequestCount/$modelCallCount）" } ?: "未返回"
    return "模型耗时 ${modelLatencyMs.toCompactDurationText()}" +
        " · TTFB ${averageFirstByteLatencyMs?.toCompactDurationText() ?: "未采集"}" +
        " · Prompt ${promptBytes.toCompactByteText()}" +
        " · Token $tokenText"
}

private fun Map<AgentRunStatus, Int>.toFailureDistributionText(): String {
    val labels = listOf(
        AgentRunStatus.FAILED to "失败",
        AgentRunStatus.CANCELLED to "取消",
        AgentRunStatus.BUDGET_EXHAUSTED to "预算耗尽",
        AgentRunStatus.BLOCKED to "阻断",
    ).mapNotNull { (status, label) -> get(status)?.takeIf { it > 0 }?.let { "$label $it" } }
    return "失败分布 " + labels.ifEmpty { listOf("无") }.joinToString(" · ")
}

private fun Long.toCompactDurationText(): String {
    return when {
        this < 1_000L -> "${this}ms"
        this < 60_000L -> String.format(Locale.US, "%.2fs", this / 1_000.0)
        else -> "%dm %02ds".format(Locale.US, this / 60_000L, this % 60_000L / 1_000L)
    }
}

private fun Long.toCompactByteText(): String {
    return when {
        this < 1_024L -> "${this}B"
        this < 1_048_576L -> String.format(Locale.US, "%.1fKB", this / 1_024.0)
        else -> String.format(Locale.US, "%.1fMB", this / 1_048_576.0)
    }
}
