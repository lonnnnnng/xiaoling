package com.longdev.endpointtester.model

enum class ApiMode(val label: String) {
    CHAT_COMPLETIONS("Chat Completions"),
    RESPONSES("Responses API"),
}

data class EndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val temperature: Double = 0.0,
    val maxTokens: Int = ProviderProfile.FIXED_MAX_TOKENS,
    val topP: Double = 1.0,
)

data class ModelTestResult(
    val endpoint: String,
    val model: String,
    val latencyMs: Long,
    val firstTokenLatencyMs: Long? = null,
    val responseText: String,
)
