package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ModelTokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalTaskPlanGenerationMetricsPresentationTest {
    @Test
    fun mapsAndPresentsKnownPlanGenerationTelemetry() {
        val metrics = ModelResponseResult(
            requestUrl = "https://example.test/v1/chat/completions",
            model = "model-a",
            latencyMs = 4_000L,
            firstByteLatencyMs = 320L,
            promptBytes = 6_144,
            usage = ModelTokenUsage(inputTokens = 120L, outputTokens = 30L, totalTokens = 150L),
            responseText = "{}",
        ).toPersonalTaskPlanGenerationMetricsUiState()

        assertEquals(
            "计划生成：模型 1 次 · 耗时 4.00s · TTFB 320ms · Prompt 6.0KB · Tokens 输入 120 · 输出 30 · 合计 150",
            presentPersonalTaskPlanGenerationMetrics(metrics),
        )
    }

    @Test
    fun makesMissingUsageAndFirstByteExplicit() {
        val text = presentPersonalTaskPlanGenerationMetrics(
            PersonalTaskPlanGenerationMetricsUiState(
                modelCallCount = 1,
                latencyMs = 850L,
                firstByteLatencyMs = null,
                promptBytes = 512,
                inputTokens = null,
                outputTokens = null,
                totalTokens = null,
            ),
        )

        assertEquals(
            "计划生成：模型 1 次 · 耗时 850ms · TTFB 未采集 · Prompt 512B · Tokens 未返回",
            text,
        )
    }
}
