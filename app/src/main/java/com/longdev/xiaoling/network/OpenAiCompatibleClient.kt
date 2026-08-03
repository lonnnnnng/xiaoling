package com.longdev.xiaoling.network

import android.util.Log
import com.longdev.xiaoling.BuildConfig
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ModelReasoningSummary
import com.longdev.xiaoling.model.ModelTokenUsage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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

class OpenAiCompatibleClient(
    private val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(config: ProviderRequestConfig): List<String> = withContext(Dispatchers.IO) {
        val requestUrl = adapter.modelsUrl(config)
        val request = requestBuilder(requestUrl, config).get().build()
        execute(request, config.httpDebugLoggingEnabled) { body ->
            adapter.parseModelsResponse(body).ifEmpty {
                throw ApiFailure(FailureKind.RESPONSE, "服务器返回成功，但模型列表为空")
            }
        }
    }

    suspend fun sendMessage(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
        onStreamDelta: (suspend (StreamDeltaUpdate) -> Unit)? = null,
    ): ModelResponseResult = sendMessageInternal(config, messages, onStreamDelta, outputFormat = null)

    suspend fun sendStructuredMessage(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
        outputFormat: LlmStructuredOutputFormat,
    ): ModelResponseResult = sendMessageInternal(
        config = config.copy(streamingEnabled = false, reasoningSummaryEnabled = false),
        messages = messages,
        onStreamDelta = null,
        outputFormat = outputFormat,
    )

    private suspend fun sendMessageInternal(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
        onStreamDelta: (suspend (StreamDeltaUpdate) -> Unit)?,
        outputFormat: LlmStructuredOutputFormat?,
    ): ModelResponseResult = withContext(Dispatchers.IO) {
        require(config.model.isNotBlank()) { "请输入或选择模型名称" }
        require(messages.isNotEmpty()) { "请输入消息" }
        val generationRequest = adapter.prepareGenerationRequest(config, messages, outputFormat)
        val requestUrl = generationRequest.requestUrl
        val body = generationRequest.body

        val request = requestBuilder(requestUrl, config)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        if (config.httpDebugLoggingEnabled) NetworkDebugLogger.logRequest(request, body)
        val startedAtMs = monotonicNowMs()
        val completion = if (config.streamingEnabled) {
            executeStreaming(
                apiMode = config.apiMode,
                request = request,
                startedAtMs = startedAtMs,
                httpDebugLoggingEnabled = config.httpDebugLoggingEnabled,
                onDelta = onStreamDelta,
            )
        } else {
            val execution = executeWithTiming(request, startedAtMs, config.httpDebugLoggingEnabled) { responseBody ->
                ParsedCompletion(
                    text = adapter.parseGenerationResponse(config.apiMode, responseBody),
                    reasoningSummaries = adapter.parseReasoningSummaries(config.apiMode, responseBody),
                    usage = adapter.parseTokenUsage(config.apiMode, responseBody),
                )
            }
            ModelCompletion(
                text = execution.value.text,
                firstByteLatencyMs = execution.firstByteLatencyMs,
                firstTokenLatencyMs = null,
                reasoningSummaries = execution.value.reasoningSummaries,
                usage = execution.value.usage,
            )
        }
        ModelResponseResult(
            requestUrl = requestUrl,
            model = config.model.trim(),
            latencyMs = monotonicNowMs() - startedAtMs,
            firstByteLatencyMs = completion.firstByteLatencyMs,
            firstTokenLatencyMs = completion.firstTokenLatencyMs,
            // long: Prompt 大小以实际发送的最终 JSON UTF-8 字节计量，避免字符数在中文、转义和 typed item 下失真。
            promptBytes = body.toByteArray(Charsets.UTF_8).size,
            usage = completion.usage,
            responseText = completion.text,
            reasoningSummaries = completion.reasoningSummaries,
        )
    }

    suspend fun createEmbeddings(
        config: ProviderRequestConfig,
        inputs: List<String>,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        val model = config.embeddingModel?.trim().orEmpty()
        require(model.isNotBlank()) { "当前提供方没有可用的 Embedding 模型" }
        require(inputs.isNotEmpty()) { "Embedding 输入不能为空" }
        require(inputs.all { it.isNotBlank() }) { "Embedding 输入不能包含空文本" }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray(inputs))
            .put("encoding_format", "float")
            .toString()
        val requestUrl = ProviderApiUrlBuilder.embeddingsUrl(config.baseUrl)
        val request = requestBuilder(requestUrl, config)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        if (config.httpDebugLoggingEnabled) NetworkDebugLogger.logRequest(request, body)
        execute(request, config.httpDebugLoggingEnabled) { responseBody -> parseEmbeddings(responseBody, inputs.size) }
    }

    private fun requestBuilder(requestUrl: String, config: ProviderRequestConfig): Request.Builder {
        val url = requestUrl.toHttpUrlOrNull()
            ?: throw ApiFailure(FailureKind.REQUEST_URL, "Base URL 不是有效的 HTTP 地址")
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // long: 部分兼容网关会按客户端 UA 选择协议或账号路径；空白自定义值必须回退稳定默认值，不能让 OkHttp 注入自己的 UA 改变上游行为。
            .header("User-Agent", config.userAgent.trim().ifBlank { ProviderRequestConfig.DEFAULT_USER_AGENT })
            .apply {
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                // long: 自定义 Header 最后写入，便于 Azure 等非 Bearer 服务覆盖默认鉴权头。
                config.customHeaders.forEach { (name, value) -> header(name, value) }
            }
    }

    private suspend fun <T> execute(
        request: Request,
        httpDebugLoggingEnabled: Boolean,
        parse: (String) -> T,
    ): T = executeWithTiming(
        request = request,
        startedAtMs = null,
        httpDebugLoggingEnabled = httpDebugLoggingEnabled,
        parse = parse,
    ).value

    private suspend fun <T> executeWithTiming(
        request: Request,
        startedAtMs: Long?,
        httpDebugLoggingEnabled: Boolean,
        parse: (String) -> T,
    ): HttpExecution<T> {
        val call = client.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                val bodySource = response.body?.source()
                val firstByteLatencyMs = if (startedAtMs != null && bodySource != null) {
                    // long: call.execute() 只代表响应头已到达；主动请求首个 body 字节后再计时，避免把 TTFB 错记成响应头耗时。
                    bodySource.request(1L)
                    monotonicNowMs() - startedAtMs
                } else {
                    null
                }
                val body = bodySource?.readUtf8().orEmpty()
                if (httpDebugLoggingEnabled) NetworkDebugLogger.logResponse(request, response.code, body)
                if (!response.isSuccessful) throw ApiFailureClassifier.fromHttp(response.code, body)
                return try {
                    HttpExecution(parse(body), firstByteLatencyMs)
                } catch (error: ApiFailure) {
                    throw error
                } catch (error: Exception) {
                    throw ApiFailure(FailureKind.RESPONSE, error.message ?: "无法解析服务器响应")
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw ApiFailureClassifier.fromNetwork(error)
        } finally {
            cancellationHandle.dispose()
        }
    }

    private suspend fun executeStreaming(
        apiMode: ApiMode,
        request: Request,
        startedAtMs: Long,
        httpDebugLoggingEnabled: Boolean,
        onDelta: (suspend (StreamDeltaUpdate) -> Unit)?,
    ): ModelCompletion {
        val call = client.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (httpDebugLoggingEnabled) NetworkDebugLogger.logStreamResponseStart(request, response.code)
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    if (httpDebugLoggingEnabled) NetworkDebugLogger.logResponse(request, response.code, errorBody)
                    throw ApiFailureClassifier.fromHttp(response.code, errorBody)
                }

                val builder = StringBuilder()
                val reasoningSummaryBuffers = linkedMapOf<Pair<String?, Int>, StringBuilder>()
                var firstTokenLatencyMs: Long? = null
                var finalTextFromStream: String? = null
                val body = response.body ?: throw ApiFailure(FailureKind.RESPONSE, "服务器没有返回流式响应")
                // long: 流式响应也要在首个 SSE 字节实际可读后记录 TTFB；已缓冲字节仍由 charStream 消费，不会丢失首个事件。
                body.source().request(1L)
                val firstByteLatencyMs = monotonicNowMs() - startedAtMs
                body.charStream().buffered().useLines { lines ->
                    lines.forEach { line ->
                        currentCoroutineContext().ensureActive()
                        val data = line.trim().removePrefix("data:").trim()
                        if (data.isBlank()) return@forEach
                        if (httpDebugLoggingEnabled) NetworkDebugLogger.logStreamEvent(data)
                        val streamEvent = adapter.parseStreamEvent(apiMode, data) ?: return@forEach
                        streamEvent.reasoningSummaryDelta?.let { summary ->
                            reasoningSummaryBuffers
                                .getOrPut(summary.providerItemId to summary.summaryIndex, ::StringBuilder)
                                .append(summary.text)
                        }
                        streamEvent.reasoningSummaries.forEach { summary ->
                            // long: done 事件携带供应商最终摘要，必须覆盖本地 delta 累积，避免断流补包或重复事件导致持久化内容漂移。
                            reasoningSummaryBuffers[summary.providerItemId to summary.summaryIndex] = StringBuilder(summary.text)
                        }
                        streamEvent.finalText?.let { finalText ->
                            // long: Responses API 的 done/completed 事件会给出服务端汇总后的完整文本；它能纠正部分网关把换行拆成独立 delta 时客户端漏拼的问题。
                            finalTextFromStream = finalText
                        }
                        streamEvent.deltaText?.let { delta ->
                            val currentFirstTokenLatencyMs = firstTokenLatencyMs ?: run {
                                // long: 流式对话需要区分“首字到达”和“完整返回”，这里在第一个可读 delta 抵达时记录首字耗时。
                                val latency = monotonicNowMs() - startedAtMs
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
                    firstByteLatencyMs = firstByteLatencyMs,
                    firstTokenLatencyMs = firstTokenLatencyMs,
                    reasoningSummaries = reasoningSummaryBuffers.mapNotNull { (key, value) ->
                        value.toString().takeIf { it.isNotBlank() }?.let { text ->
                            ModelReasoningSummary(providerItemId = key.first, summaryIndex = key.second, text = text)
                        }
                    },
                    usage = null,
                ).also {
                    if (httpDebugLoggingEnabled) NetworkDebugLogger.logStreamCompleted(completedText)
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw ApiFailureClassifier.fromNetwork(error)
        } finally {
            cancellationHandle.dispose()
        }
    }

    private data class ModelCompletion(
        val text: String,
        val firstByteLatencyMs: Long?,
        val firstTokenLatencyMs: Long?,
        val reasoningSummaries: List<ModelReasoningSummary>,
        val usage: ModelTokenUsage?,
    )

    private data class ParsedCompletion(
        val text: String,
        val reasoningSummaries: List<ModelReasoningSummary>,
        val usage: ModelTokenUsage?,
    )

    private data class HttpExecution<T>(
        val value: T,
        val firstByteLatencyMs: Long?,
    )
}

private fun parseEmbeddings(body: String, expectedCount: Int): List<FloatArray> {
    val data = JSONObject(body).optJSONArray("data")
        ?: throw ApiFailure(FailureKind.RESPONSE, "Embedding 响应缺少 data")
    require(data.length() == expectedCount) {
        "Embedding 响应数量不匹配：期望 $expectedCount，实际 ${data.length()}"
    }
    val indexed = buildMap<Int, FloatArray> {
        repeat(data.length()) { position ->
            val item = data.getJSONObject(position)
            val index = item.optInt("index", position)
            val values = item.optJSONArray("embedding")
                ?: error("Embedding 响应第 $position 项缺少 embedding")
            val vector = FloatArray(values.length()) { valueIndex -> values.getDouble(valueIndex).toFloat() }
            require(vector.isNotEmpty() && vector.all(Float::isFinite)) { "Embedding 响应包含无效向量" }
            require(put(index, vector) == null) { "Embedding 响应 index 重复：$index" }
        }
    }
    val vectors = (0 until expectedCount).map { index -> indexed[index] ?: error("Embedding 响应缺少 index=$index") }
    require(vectors.map(FloatArray::size).distinct().size == 1) { "Embedding 响应向量维度不一致" }
    return vectors
}

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

private object NetworkDebugLogger {
    private const val TAG = "XiaoLingHttp"
    private val enabled: Boolean
        get() = BuildConfig.XIAOLING_HTTP_LOGS_ENABLED

    fun logRequest(request: Request, body: String) {
        if (!enabled) return
        // long: 调试模型兼容性时需要看到真实请求体，但鉴权头必须脱敏，避免 logcat 泄露用户密钥。
        Log.d(TAG, "REQUEST ${request.method} ${request.url}")
        Log.d(TAG, "REQUEST headers=${request.headers.redactedForLog()}")
        Log.d(TAG, "REQUEST body=${NetworkDebugLogSanitizer.sanitize(body)}")
    }

    fun logResponse(request: Request, code: Int, body: String) {
        if (!enabled) return
        Log.d(TAG, "RESPONSE ${request.method} ${request.url} code=$code")
        Log.d(TAG, "RESPONSE body=${NetworkDebugLogSanitizer.sanitize(body)}")
    }

    fun logStreamResponseStart(request: Request, code: Int) {
        if (!enabled) return
        Log.d(TAG, "STREAM_RESPONSE ${request.method} ${request.url} code=$code")
    }

    fun logStreamEvent(data: String) {
        if (!enabled) return
        Log.d(TAG, "STREAM_EVENT data=${NetworkDebugLogSanitizer.sanitize(data)}")
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
