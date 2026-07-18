package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunHistoryMetrics
import com.longdev.xiaoling.agent.AgentRunMetrics
import java.util.Locale

internal data class AgentRunHistoryMetricsPresentation(
    val headline: String,
    val detail: String,
)

internal fun presentAgentRunMetrics(metrics: AgentRunMetrics): String {
    return "耗时 ${metrics.elapsedMs.toCompactDurationText()}" +
        " · 模型 ${metrics.modelCallCount}" +
        " · 工具 ${metrics.toolCallCount}" +
        " · 审批 ${metrics.approvalRequestCount}"
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
    )
}

private fun Long.toCompactDurationText(): String {
    return when {
        this < 1_000L -> "${this}ms"
        this < 60_000L -> String.format(Locale.US, "%.2fs", this / 1_000.0)
        else -> "%dm %02ds".format(Locale.US, this / 60_000L, this % 60_000L / 1_000L)
    }
}
