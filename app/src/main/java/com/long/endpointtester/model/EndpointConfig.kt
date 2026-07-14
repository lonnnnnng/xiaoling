package com.longdev.endpointtester.model

data class EndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: Map<String, String> = emptyMap(),
)

data class ModelTestResult(
    val endpoint: String,
    val model: String,
    val latencyMs: Long,
    val responseText: String,
)
