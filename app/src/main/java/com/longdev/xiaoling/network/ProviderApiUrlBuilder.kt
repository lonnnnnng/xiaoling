package com.longdev.xiaoling.network

object ProviderApiUrlBuilder {
    private val knownSuffixes = listOf(
        "/chat/completions",
        "/embeddings",
        "/models",
        "/responses",
    )

    fun modelsUrl(input: String): String = "${apiRoot(input)}/models"

    fun chatCompletionsUrl(input: String): String = "${apiRoot(input)}/chat/completions"

    fun responsesUrl(input: String): String = "${apiRoot(input)}/responses"

    fun embeddingsUrl(input: String): String = "${apiRoot(input)}/embeddings"

    fun validate(input: String): String? {
        val normalized = input.trim()
        if (normalized.isBlank()) return "请输入 Base URL"
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return "Base URL 必须以 http:// 或 https:// 开头"
        }
        return null
    }

    private fun apiRoot(input: String): String {
        var normalized = input.trim().trimEnd('/')
        // long: 用户可能直接粘贴完整接口地址；先回退到 API 根路径，避免生成重复的 /chat/completions。
        knownSuffixes.firstOrNull { normalized.endsWith(it, ignoreCase = true) }?.let { suffix ->
            normalized = normalized.dropLast(suffix.length).trimEnd('/')
        }
        return normalized
    }
}
