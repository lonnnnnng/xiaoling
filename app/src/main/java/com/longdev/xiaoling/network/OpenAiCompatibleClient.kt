package com.longdev.xiaoling.network

import android.os.SystemClock
import android.util.Log
import com.longdev.xiaoling.BuildConfig
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ModelResponseResult
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

    suspend fun fetchModels(config: ProviderRequestConfig): List<String> = withContext(Dispatchers.IO) {
        val requestUrl = ProviderApiUrlBuilder.modelsUrl(config.baseUrl)
        val request = requestBuilder(requestUrl, config).get().build()
        execute(request) { body ->
            OpenAiResponseParser.parseModels(body).ifEmpty {
                throw ApiFailure(FailureKind.RESPONSE, "服务器返回成功，但模型列表为空")
            }
        }
    }

    suspend fun sendMessage(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
        onStreamDelta: (suspend (StreamDeltaUpdate) -> Unit)? = null,
    ): ModelResponseResult = withContext(Dispatchers.IO) {
        require(config.model.isNotBlank()) { "请输入或选择模型名称" }
        require(messages.isNotEmpty()) { "请输入消息" }
        val requestUrl = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> ProviderApiUrlBuilder.chatCompletionsUrl(config.baseUrl)
            ApiMode.RESPONSES -> ProviderApiUrlBuilder.responsesUrl(config.baseUrl)
        }
        val payload = when (config.apiMode) {
            ApiMode.CHAT_COMPLETIONS -> chatPayload(config, messages)
            ApiMode.RESPONSES -> responsesPayload(config, messages)
        }

        val request = requestBuilder(requestUrl, config)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        NetworkDebugLogger.logRequest(request, payload)
        val startedAtMs = SystemClock.elapsedRealtime()
        val completion = if (config.streamingEnabled) {
            executeStreaming(config.apiMode, request, startedAtMs, onStreamDelta)
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
        ModelResponseResult(
            requestUrl = requestUrl,
            model = config.model.trim(),
            latencyMs = SystemClock.elapsedRealtime() - startedAtMs,
            firstTokenLatencyMs = completion.firstTokenLatencyMs,
            responseText = completion.text,
        )
    }

    private fun chatPayload(config: ProviderRequestConfig, messages: List<RequestMessage>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put(
            "messages",
            JSONArray().apply {
                messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", message.content),
                    )
                }
            },
        )
        .put("temperature", config.temperature)
        .put("max_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun responsesPayload(config: ProviderRequestConfig, messages: List<RequestMessage>): JSONObject = JSONObject()
        .put("model", config.model.trim())
        .put("input", messages.toResponsesInput())
        .put("temperature", config.temperature)
        .put("max_output_tokens", config.maxTokens)
        .put("top_p", config.topP)
        .put("stream", config.streamingEnabled)

    private fun List<RequestMessage>.toResponsesInput(): String {
        return joinToString("\n\n") { message ->
            val label = when (message.role) {
                "system" -> "系统上下文"
                "assistant" -> "assistant"
                else -> "user"
            }
            "$label:\n${message.content}"
        }
    }

    private fun requestBuilder(requestUrl: String, config: ProviderRequestConfig): Request.Builder {
        val url = requestUrl.toHttpUrlOrNull()
            ?: throw ApiFailure(FailureKind.REQUEST_URL, "Base URL 不是有效的 HTTP 地址")
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "XiaoLing/${BuildConfig.VERSION_NAME}")
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
                NetworkDebugLogger.logResponse(request, response.code, body)
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

    private suspend fun executeStreaming(
        apiMode: ApiMode,
        request: Request,
        startedAtMs: Long,
        onDelta: (suspend (StreamDeltaUpdate) -> Unit)?,
    ): ModelCompletion {
        try {
            client.newCall(request).execute().use { response ->
                NetworkDebugLogger.logStreamResponseStart(request, response.code)
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    NetworkDebugLogger.logResponse(request, response.code, errorBody)
                    throw ApiFailureClassifier.fromHttp(response.code, errorBody)
                }

                val builder = StringBuilder()
                var firstTokenLatencyMs: Long? = null
                var finalTextFromStream: String? = null
                val body = response.body ?: throw ApiFailure(FailureKind.RESPONSE, "服务器没有返回流式响应")
                body.charStream().buffered().useLines { lines ->
                    lines.forEach { line ->
                        val data = line.trim().removePrefix("data:").trim()
                        if (data.isBlank()) return@forEach
                        NetworkDebugLogger.logStreamEvent(data)
                        OpenAiResponseParser.parseStreamFinalText(apiMode, data)?.let { finalText ->
                            // long: Responses API 的 done/completed 事件会给出服务端汇总后的完整文本；它能纠正部分网关把换行拆成独立 delta 时客户端漏拼的问题。
                            finalTextFromStream = finalText
                        }
                        OpenAiResponseParser.parseStreamDelta(apiMode, data)?.let { delta ->
                            val currentFirstTokenLatencyMs = firstTokenLatencyMs ?: run {
                                // long: 流式对话需要区分“首字到达”和“完整返回”，这里在第一个可读 delta 抵达时记录首字耗时。
                                val latency = SystemClock.elapsedRealtime() - startedAtMs
                                firstTokenLatencyMs = latency
                                latency
                            }
                            builder.append(delta)
                            onDelta?.invoke(
                                StreamDeltaUpdate(
                                    deltaText = delta,
                                    accumulatedText = builder.toString(),
                                    firstTokenLatencyMs = currentFirstTokenLatencyMs,
                                ),
                            )
                        }
                    }
                }
                val completedText = finalTextFromStream ?: builder.toString()
                return ModelCompletion(
                    text = completedText.ifBlank {
                        throw ApiFailure(FailureKind.RESPONSE, "流式响应没有返回可读文本")
                    },
                    firstTokenLatencyMs = firstTokenLatencyMs,
                ).also {
                    NetworkDebugLogger.logStreamCompleted(completedText)
                }
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

private object NetworkDebugLogger {
    private const val TAG = "XiaoLingHttp"
    private val enabled: Boolean
        get() = BuildConfig.XIAOLING_HTTP_LOGS_ENABLED

    fun logRequest(request: Request, payload: JSONObject) {
        if (!enabled) return
        // long: 调试模型兼容性时需要看到真实请求体，但鉴权头必须脱敏，避免 logcat 泄露用户密钥。
        Log.d(TAG, "REQUEST ${request.method} ${request.url}")
        Log.d(TAG, "REQUEST headers=${request.headers.redactedForLog()}")
        Log.d(TAG, "REQUEST body=$payload")
    }

    fun logResponse(request: Request, code: Int, body: String) {
        if (!enabled) return
        Log.d(TAG, "RESPONSE ${request.method} ${request.url} code=$code")
        Log.d(TAG, "RESPONSE body=$body")
    }

    fun logStreamResponseStart(request: Request, code: Int) {
        if (!enabled) return
        Log.d(TAG, "STREAM_RESPONSE ${request.method} ${request.url} code=$code")
    }

    fun logStreamEvent(data: String) {
        if (!enabled) return
        Log.d(TAG, "STREAM_EVENT data=$data")
    }

    fun logStreamCompleted(text: String) {
        if (!enabled) return
        Log.d(TAG, "STREAM_COMPLETED text=$text")
    }

    private fun okhttp3.Headers.redactedForLog(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("authorization", ignoreCase = true) || name.contains("key", ignoreCase = true)) {
                "***MASKED***"
            } else {
                values(name).joinToString(",")
            }
        }
    }
}

data class StreamDeltaUpdate(
    val deltaText: String,
    val accumulatedText: String,
    val firstTokenLatencyMs: Long,
)

data class RequestMessage(
    val role: String,
    val content: String,
)
