package com.longdev.endpointtester.network

import android.os.SystemClock
import com.longdev.endpointtester.model.ApiMode
import com.longdev.endpointtester.model.EndpointConfig
import com.longdev.endpointtester.model.ModelTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(config: EndpointConfig): List<String> = withContext(Dispatchers.IO) {
        val endpoint = EndpointUrlBuilder.modelsUrl(config.baseUrl)
        val request = requestBuilder(endpoint, config).get().build()
        execute(request) { body ->
            OpenAiResponseParser.parseModels(body).ifEmpty {
                throw ApiFailure(FailureKind.RESPONSE, "服务器返回成功，但模型列表为空")
            }
        }
    }

    suspend fun testModel(config: EndpointConfig, prompt: String): ModelTestResult = withContext(Dispatchers.IO) {
        require(config.model.isNotBlank()) { "请输入或选择模型名称" }
        val endpoint = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> EndpointUrlBuilder.chatCompletionsUrl(config.baseUrl)
            ApiMode.RESPONSES -> EndpointUrlBuilder.responsesUrl(config.baseUrl)
        }
        val payload = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> chatPayload(config, prompt)
            ApiMode.RESPONSES -> responsesPayload(config, prompt)
        }

        val request = requestBuilder(endpoint, config)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        val startedAtMs = SystemClock.elapsedRealtime()
        val completion = if (config.streamingEnabled) {
            executeStreaming(request, startedAtMs)
        } else {
            ModelCompletion(
                text = execute(request) { body ->
                    when (config.apiMode) {
                        ApiMode.CHAT_COMPLETIONS -> OpenAiResponseParser.parseChatText(body)
                        ApiMode.RESPONSES -> OpenAiResponseParser.parseResponsesText(body)
                    }
                },
                firstTokenLatencyMs = null,
            )
        }
        ModelTestResult(
            endpoint = endpoint,
            model = config.model.trim(),
            latencyMs = SystemClock.elapsedRealtime() - startedAtMs,
            firstTokenLatencyMs = completion.firstTokenLatencyMs,
            responseText = completion.text,
        )
    }

    private fun chatPayload(config: EndpointConfig, prompt: String): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .put("temperature", config.temperature)
        .put("max_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun responsesPayload(config: EndpointConfig, prompt: String): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put("input", prompt)
        .put("temperature", config.temperature)
        .put("max_output_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun requestBuilder(endpoint: String, config: EndpointConfig): Request.Builder {
        val url = endpoint.toHttpUrlOrNull()
            ?: throw ApiFailure(FailureKind.ENDPOINT, "Base URL 不是有效的 HTTP 地址")
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "EndpointModelTester/0.1.0")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                // long: 自定义 Header 最后写入，便于 Azure 等非 Bearer 服务覆盖默认鉴权头。
                config.customHeaders.forEach { (name, value) -> header(name, value) }
            }
    }

    private inline fun <T> execute(request: Request, parse: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiFailureClassifier.fromHttp(response.code, body)
                return try {
                    parse(body)
                } catch (error: ApiFailure) {
                    throw error
                } catch (error: Exception) {
                    throw ApiFailure(FailureKind.RESPONSE, error.message ?: "无法解析服务器响应")
                }
            }
        } catch (error: IOException) {
            throw ApiFailureClassifier.fromNetwork(error)
        }
    }

    private fun executeStreaming(request: Request, startedAtMs: Long): ModelCompletion {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiFailureClassifier.fromHttp(response.code, response.body?.string().orEmpty())
                }

                val builder = StringBuilder()
                var firstTokenLatencyMs: Long? = null
                val body = response.body ?: throw ApiFailure(FailureKind.RESPONSE, "服务器没有返回流式响应")
                body.charStream().buffered().useLines { lines ->
                    lines.forEach { line ->
                        val data = line.trim().removePrefix("data:").trim()
                        if (data.isBlank()) return@forEach
                        OpenAiResponseParser.parseStreamDelta(data)?.let { delta ->
                            if (firstTokenLatencyMs == null) {
                                // long: 流式测试需要区分“首字到达”和“完整返回”，这里在第一个可读 delta 抵达时记录首字耗时。
                                firstTokenLatencyMs = SystemClock.elapsedRealtime() - startedAtMs
                            }
                            builder.append(delta)
                        }
                    }
                }
                return ModelCompletion(
                    text = builder.toString().ifBlank {
                    throw ApiFailure(FailureKind.RESPONSE, "流式响应没有返回可读文本")
                    },
                    firstTokenLatencyMs = firstTokenLatencyMs,
                )
            }
        } catch (error: IOException) {
            throw ApiFailureClassifier.fromNetwork(error)
        }
    }

    private data class ModelCompletion(
        val text: String,
        val firstTokenLatencyMs: Long?,
    )
}
