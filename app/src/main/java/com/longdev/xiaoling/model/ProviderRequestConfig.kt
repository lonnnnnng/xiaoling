package com.longdev.xiaoling.model

enum class ApiMode(val label: String) {
    CHAT_COMPLETIONS("Chat Completions"),
    RESPONSES("Responses API"),
}

data class ProviderRequestConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val userAgent: String = DEFAULT_USER_AGENT,
    val customHeaders: Map<String, String> = emptyMap(),
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val temperature: Double = 0.0,
    val maxTokens: Int = ProviderProfile.FIXED_MAX_TOKENS,
    val topP: Double = 1.0,
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)"
    }
}

data class ModelResponseResult(
    val requestUrl: String,
    val model: String,
    val latencyMs: Long,
    val firstTokenLatencyMs: Long? = null,
    val responseText: String,
)
