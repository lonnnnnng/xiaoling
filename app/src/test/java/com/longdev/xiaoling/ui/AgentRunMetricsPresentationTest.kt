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
            ),
        )

        assertEquals("3 个 Run · 成功率 50% · 平均 4.00s", presentation.headline)
        assertEquals("终态 2 · 非成功 1 · 模型 4 · 工具 2", presentation.detail)
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
    }
}
