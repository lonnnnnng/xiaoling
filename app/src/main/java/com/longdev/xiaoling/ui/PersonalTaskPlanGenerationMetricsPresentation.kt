package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ModelResponseResult

data class PersonalTaskPlanGenerationMetricsUiState(
    val modelCallCount: Int,
    val latencyMs: Long,
    val firstByteLatencyMs: Long?,
    val promptBytes: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

// long: 计划尚未创建 Workflow/Run，遥测只映射到待确认状态，避免把确认前请求伪装成执行链历史。
internal fun ModelResponseResult.toPersonalTaskPlanGenerationMetricsUiState(): PersonalTaskPlanGenerationMetricsUiState {
    return PersonalTaskPlanGenerationMetricsUiState(
        modelCallCount = 1,
        latencyMs = latencyMs,
        firstByteLatencyMs = firstByteLatencyMs,
        promptBytes = promptBytes,
        inputTokens = usage?.inputTokens,
        outputTokens = usage?.outputTokens,
        totalTokens = usage?.totalTokens,
    )
}

internal fun presentPersonalTaskPlanGenerationMetrics(
    metrics: PersonalTaskPlanGenerationMetricsUiState,
): String {
    val tokenText = if (metrics.inputTokens == null &&
        metrics.outputTokens == null &&
        metrics.totalTokens == null
    ) {
        "未返回"
    } else {
        "输入 ${metrics.inputTokens?.toString() ?: "未知"}" +
            " · 输出 ${metrics.outputTokens?.toString() ?: "未知"}" +
            " · 合计 ${metrics.totalTokens?.toString() ?: "未知"}"
    }
    return "计划生成：模型 ${metrics.modelCallCount} 次" +
        " · 耗时 ${metrics.latencyMs.toCompactDurationText()}" +
        " · TTFB ${metrics.firstByteLatencyMs?.toCompactDurationText() ?: "未采集"}" +
        " · Prompt ${metrics.promptBytes.toLong().toCompactByteText()}" +
        " · Tokens $tokenText"
}
