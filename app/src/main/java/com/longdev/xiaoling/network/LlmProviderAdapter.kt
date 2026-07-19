package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.ModelReasoningSummary
import com.longdev.xiaoling.model.ModelTokenUsage
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.json.JSONArray
import org.json.JSONObject

interface LlmProviderAdapter {
    fun modelsUrl(config: ProviderRequestConfig): String

    fun parseModelsResponse(body: String): List<String>

    fun prepareGenerationRequest(
        config: ProviderRequestConfig,
        messages: List<RequestInputItem>,
    ): LlmGenerationRequest

    fun parseGenerationResponse(apiMode: ApiMode, body: String): String

    fun parseReasoningSummaries(apiMode: ApiMode, body: String): List<ModelReasoningSummary>

    fun parseTokenUsage(apiMode: ApiMode, body: String): ModelTokenUsage?

    fun parseStreamEvent(apiMode: ApiMode, data: String): LlmStreamEvent?
}

data class LlmGenerationRequest(
    val requestUrl: String,
    val body: String,
)

data class LlmStreamEvent(
    val deltaText: String? = null,
    val finalText: String? = null,
    val reasoningSummaryDelta: ModelReasoningSummary? = null,
    val reasoningSummaries: List<ModelReasoningSummary> = emptyList(),
)

sealed interface RequestInputItem

data class RequestMessage(
    val role: String,
    val content: String,
    val images: List<ImageAttachment> = emptyList(),
    val documents: List<DocumentAttachment> = emptyList(),
) : RequestInputItem {
    init {
        require((images.isEmpty() && documents.isEmpty()) || role == "user") { "只有 user 消息可以携带附件" }
        require(images.size <= 1) { "每条 user 消息最多携带一张图片" }
        require(documents.size <= 1) { "每条 user 消息最多携带一个文档" }
        require(images.isEmpty() || documents.isEmpty()) { "每条 user 消息只能携带一种附件" }
    }
}

data class RequestFunctionCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, String>,
) : RequestInputItem {
    init {
        require(callId.isNotBlank()) { "函数调用 callId 不能为空" }
        require(name.isNotBlank()) { "函数调用名称不能为空" }
    }
}

data class RequestFunctionCallOutput(
    val callId: String,
    val output: String,
) : RequestInputItem {
    init {
        require(callId.isNotBlank()) { "函数调用结果 callId 不能为空" }
    }
}

class OpenAiCompatibleAdapter : LlmProviderAdapter {
    override fun modelsUrl(config: ProviderRequestConfig): String =
        ProviderApiUrlBuilder.modelsUrl(config.baseUrl)

    override fun parseModelsResponse(body: String): List<String> =
        OpenAiResponseParser.parseModels(body)

    override fun prepareGenerationRequest(
        config: ProviderRequestConfig,
        messages: List<RequestInputItem>,
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

    override fun parseReasoningSummaries(apiMode: ApiMode, body: String): List<ModelReasoningSummary> = when (apiMode) {
        ApiMode.CHAT_COMPLETIONS -> emptyList()
        ApiMode.RESPONSES -> OpenAiResponseParser.parseResponsesReasoningSummaries(body)
    }

    override fun parseTokenUsage(apiMode: ApiMode, body: String): ModelTokenUsage? =
        OpenAiResponseParser.parseTokenUsage(body)

    override fun parseStreamEvent(apiMode: ApiMode, data: String): LlmStreamEvent? =
        OpenAiResponseParser.parseStreamEvent(apiMode, data)

    private fun chatPayload(config: ProviderRequestConfig, messages: List<RequestInputItem>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put("messages", messages.toChatMessages())
        .put("temperature", config.temperature)
        .put("max_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun responsesPayload(config: ProviderRequestConfig, messages: List<RequestInputItem>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        // long: Responses 的消息数组必须保留每轮角色边界；拼成单一字符串会让 system、assistant 和 user 退化成不可审计的普通文本。
        .put("input", messages.toResponsesItems())
        .put("temperature", config.temperature)
        .put("max_output_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)
        .apply {
            if (config.reasoningSummaryEnabled) {
                // long: Reasoning 摘要需要用户显式开启；只请求供应商可展示的 summary，不请求或暴露原始 reasoning_text。
                put("reasoning", JSONObject().put("summary", "auto"))
            }
        }

    private fun List<RequestInputItem>.toChatMessages(): JSONArray = JSONArray().apply {
        forEach { item ->
            require(item is RequestMessage) {
                "Chat Completions 不支持 Responses typed Item：${item::class.simpleName}"
            }
            require(item.images.isEmpty()) { "当前 Chat Completions 模式不支持图片，请切换到 Responses" }
            require(item.documents.isEmpty()) { "当前 Chat Completions 模式不支持文档，请切换到 Responses" }
            put(item.toMessageJson())
        }
    }

    private fun List<RequestInputItem>.toResponsesItems(): JSONArray = JSONArray().apply {
        forEach { item ->
            put(
                when (item) {
                    is RequestMessage -> item.toResponsesMessageJson()
                    is RequestFunctionCall -> JSONObject()
                        .put("type", "function_call")
                        // long: call_id 是函数调用与执行结果之间的协议主键，两类 Item 必须原样使用同一值，不能改用本地事件 id。
                        .put("call_id", item.callId)
                        .put("name", item.name)
                        .put("arguments", item.arguments.toJsonObject().toString())
                    is RequestFunctionCallOutput -> JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", item.callId)
                        .put("output", item.output)
                },
            )
        }
    }

    private fun RequestMessage.toMessageJson(): JSONObject = JSONObject()
        .put("role", role)
        .put("content", content)

    private fun RequestMessage.toResponsesMessageJson(): JSONObject = JSONObject()
        .put("role", role)
        .put(
            "content",
            if (images.isEmpty() && documents.isEmpty()) {
                content
            } else {
                // long: Responses 只有结构化 content 才能在同一用户消息中绑定正文与附件；Data URL 保留已校验的 MIME 和原始字节，调试日志必须由统一 sanitizer 隐去 Base64。
                JSONArray().apply {
                    put(JSONObject().put("type", "input_text").put("text", content))
                    images.forEach { image ->
                        put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", image.toDataUrl())
                                .put("detail", image.detail.apiValue),
                        )
                    }
                    documents.forEach { document ->
                        put(
                            JSONObject()
                                .put("type", "input_file")
                                .put("filename", document.fileName)
                                .put("file_data", document.toDataUrl())
                                .apply {
                                    if (document.mimeType == "application/pdf") {
                                        put("detail", document.detail.apiValue)
                                    }
                                },
                        )
                    }
                }
            },
        )

    private fun ImageAttachment.toDataUrl(): String {
        return "data:$mimeType;base64,${encodedBase64()}"
    }

    private fun DocumentAttachment.toDataUrl(): String {
        return "data:$mimeType;base64,${encodedBase64()}"
    }

    private fun Map<String, String>.toJsonObject(): JSONObject = JSONObject().apply {
        toSortedMap().forEach { (key, value) -> put(key, value) }
    }
}
