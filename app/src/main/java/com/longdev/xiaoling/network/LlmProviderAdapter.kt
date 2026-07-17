package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.json.JSONArray
import org.json.JSONObject

interface LlmProviderAdapter {
    fun modelsUrl(config: ProviderRequestConfig): String

    fun parseModelsResponse(body: String): List<String>

    fun prepareGenerationRequest(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
    ): LlmGenerationRequest

    fun parseGenerationResponse(apiMode: ApiMode, body: String): String

    fun parseStreamEvent(apiMode: ApiMode, data: String): LlmStreamEvent?
}

data class LlmGenerationRequest(
    val requestUrl: String,
    val body: String,
)

data class LlmStreamEvent(
    val deltaText: String? = null,
    val finalText: String? = null,
)

data class RequestMessage(
    val role: String,
    val content: String,
)

class OpenAiCompatibleAdapter : LlmProviderAdapter {
    override fun modelsUrl(config: ProviderRequestConfig): String =
        ProviderApiUrlBuilder.modelsUrl(config.baseUrl)

    override fun parseModelsResponse(body: String): List<String> =
        OpenAiResponseParser.parseModels(body)

    override fun prepareGenerationRequest(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
    ): LlmGenerationRequest {
        val requestUrl = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> ProviderApiUrlBuilder.chatCompletionsUrl(config.baseUrl)
            ApiMode.RESPONSES -> ProviderApiUrlBuilder.responsesUrl(config.baseUrl)
        }
        val payload = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> chatPayload(config, messages)
            ApiMode.RESPONSES -> responsesPayload(config, messages)
        }
        return LlmGenerationRequest(requestUrl = requestUrl, body = payload.toString())
    }

    override fun parseGenerationResponse(apiMode: ApiMode, body: String): String = when (apiMode) {
        ApiMode.CHAT_COMPLETIONS -> OpenAiResponseParser.parseChatText(body)
        ApiMode.RESPONSES -> OpenAiResponseParser.parseResponsesText(body)
    }

    override fun parseStreamEvent(apiMode: ApiMode, data: String): LlmStreamEvent? =
        OpenAiResponseParser.parseStreamEvent(apiMode, data)

    private fun chatPayload(config: ProviderRequestConfig, messages: List<RequestMessage>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put("messages", messages.toStructuredMessages())
        .put("temperature", config.temperature)
        .put("max_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun responsesPayload(config: ProviderRequestConfig, messages: List<RequestMessage>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        // long: Responses 的消息数组必须保留每轮角色边界；拼成单一字符串会让 system、assistant 和 user 退化成不可审计的普通文本。
        .put("input", messages.toStructuredMessages())
        .put("temperature", config.temperature)
        .put("max_output_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun List<RequestMessage>.toStructuredMessages(): JSONArray = JSONArray().apply {
        forEach { message ->
            put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content),
            )
        }
    }
}
