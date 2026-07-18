package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunHistoryMetrics
import com.longdev.xiaoling.agent.AgentRunMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunMetricsPresentationTest {
    @Test
    fun runSummaryShowsAuditableCountsAndCompactDuration() {
        val text = presentAgentRunMetrics(
            AgentRunMetrics(
                elapsedMs = 5_100L,
                modelCallCount = 3,
                toolCallCount = 1,
                approvalRequestCount = 1,
            ),
        )

        assertEquals("耗时 5.10s · 模型 3 · 工具 1 · 审批 1", text)
    }

    @Test
    fun runTelemetryShowsPromptFirstByteAndUsageCoverage() {
        val text = presentAgentRunLlmMetrics(
            AgentRunMetrics(
                elapsedMs = 5_100L,
                modelCallCount = 3,
                toolCallCount = 1,
                approvalRequestCount = 1,
                modelLatencyMs = 4_000L,
                averageFirstByteLatencyMs = 320L,
                promptBytes = 6_144L,
                inputTokens = 120L,
                outputTokens = 30L,
                totalTokens = 150L,
                tokenUsageRequestCount = 1,
            ),
        )

        assertEquals("模型耗时 4.00s · TTFB 320ms · Prompt 6.0KB · Token 150（1/3）", text)
    }

    @Test
    fun historySummarySeparatesQualityAndVolumeMetrics() {
        val presentation = presentAgentRunHistoryMetrics(
            AgentRunHistoryMetrics(
                runCount = 3,
                terminalRunCount = 2,
                completedRunCount = 1,
                nonSuccessfulRunCount = 1,
                successRatePercent = 50,
                averageElapsedMs = 4_000L,
                modelCallCount = 4,
                toolCallCount = 2,
                modelLatencyMs = 3_000L,
                averageFirstByteLatencyMs = 250L,
                promptBytes = 2_048L,
                totalTokens = 80L,
                tokenUsageRequestCount = 1,
                failureCounts = mapOf(com.longdev.xiaoling.agent.AgentRunStatus.FAILED to 1),
            ),
        )

        assertEquals("3 个 Run · 成功率 50% · 平均 4.00s", presentation.headline)
        assertEquals("终态 2 · 非成功 1 · 模型 4 · 工具 2", presentation.detail)
        assertEquals("模型耗时 3.00s · TTFB 250ms · Prompt 2.0KB · Token 80（1/4）", presentation.telemetry)
        assertEquals("失败分布 失败 1", presentation.failureDistribution)
    }

    @Test
    fun activeOnlyHistoryStatesThatTerminalQualityIsUnavailable() {
        val presentation = presentAgentRunHistoryMetrics(
            AgentRunHistoryMetrics(
                runCount = 1,
                terminalRunCount = 0,
                completedRunCount = 0,
                nonSuccessfulRunCount = 0,
                successRatePercent = null,
                averageElapsedMs = null,
                modelCallCount = 1,
                toolCallCount = 0,
            ),
        )

        assertEquals("1 个 Run · 暂无终态质量数据", presentation.headline)
        assertEquals("终态 0 · 非成功 0 · 模型 1 · 工具 0", presentation.detail)
        assertEquals(null, presentation.telemetry)
        assertEquals("失败分布 无", presentation.failureDistribution)
    }
}
