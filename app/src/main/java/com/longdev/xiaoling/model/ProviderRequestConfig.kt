package com.longdev.xiaoling.model

enum class ApiMode(val label: String) {
    CHAT_COMPLETIONS("Chat Completions"),
    RESPONSES("Responses API"),
}

data class ProviderRequestConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val providerId: String? = null,
    val userAgent: String = DEFAULT_USER_AGENT,
    val customHeaders: Map<String, String> = emptyMap(),
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val reasoningSummaryEnabled: Boolean = false,
    val temperature: Double = 0.0,
    val maxTokens: Int = ProviderProfile.FIXED_MAX_TOKENS,
    val topP: Double = 1.0,
    val embeddingModel: String? = null,
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)"
    }
}

fun ProviderProfile.preferredEmbeddingModel(): String? {
    // long: 只有 Provider 实际同步到 Embedding 模型才开启语义索引；普通对话模型不能被猜测成兼容向量接口。
    val candidates = availableModels.distinct()
    return candidates.firstOrNull { it.equals("text-embedding-3-small", ignoreCase = true) }
        ?: candidates.firstOrNull { it.equals("text-embedding-3-large", ignoreCase = true) }
        ?: candidates.firstOrNull { it.contains("embedding", ignoreCase = true) }
}

data class ModelResponseResult(
    val requestUrl: String,
    val model: String,
    val latencyMs: Long,
    val firstByteLatencyMs: Long? = null,
    val firstTokenLatencyMs: Long? = null,
    val promptBytes: Int = 0,
    val usage: ModelTokenUsage? = null,
    val responseText: String,
    val reasoningSummaries: List<ModelReasoningSummary> = emptyList(),
)

data class ModelReasoningSummary(
    val providerItemId: String?,
    val summaryIndex: Int,
    val text: String,
)

data class ModelTokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)
