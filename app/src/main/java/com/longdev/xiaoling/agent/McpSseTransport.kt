package com.longdev.xiaoling.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink
import okio.BufferedSource
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** long: 503/Retry-After 等 follow-up 不受 retryOnConnectionFailure 控制，单次请求体阻止网络层重放工具副作用。 */
internal fun mcpOneShotBody(json: String): RequestBody {
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
    }
}

internal fun decodeMcpUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

internal fun validateMcpRpcResponse(json: JSONObject, expectedId: String) {
    require(json.optString("jsonrpc") == "2.0" && json.opt("id") == expectedId) { "MCP JSON-RPC 响应标识无效" }
    require(json.has("result") xor json.has("error")) { "MCP JSON-RPC 响应必须包含 result 或 error" }
    if (json.has("error")) {
        val error = json.optJSONObject("error") ?: error("MCP JSON-RPC error 无效")
        require(error.opt("code") is Number && error.opt("message") is String) { "MCP JSON-RPC error 无效" }
    } else require(json.opt("result") is JSONObject) { "MCP JSON-RPC result 必须是 object" }
}

/** long: 长度编码消除分隔符歧义，摘要避免 token.hashCode 碰撞导致不同凭据共享会话，也不在 key 中保留明文。 */
internal fun mcpServerBinding(server: McpServerConfig): String {
    val values = listOf(server.id, server.name, server.transport.wireName, server.url, server.bearerToken,
        server.enabled.toString(), server.command.orEmpty()) + listOf(server.args.size.toString()) + server.args +
        listOf(if (server.enabledToolNames == null) "legacy" else "explicit", server.enabledToolNames?.size.toString()) +
        server.enabledToolNames.orEmpty().sorted()
    val encoded = values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal data class McpSseEvent(val type: String, val data: String)

/** long: 逐行读取让持续 SSE 无需等待 EOF；单事件和总响应分别限流，半个事件断开时不发布结果。 */
internal class McpSseEventReader(private val source: BufferedSource, private val maxStreamBytes: Long = Long.MAX_VALUE) {
    private var consumed = 0L
    private var firstLine = true
    private var skipLf = false

    fun next(): McpSseEvent? {
        var type = "message"
        val data = ArrayList<String>()
        var eventBytes = 0
        while (true) {
            val raw = line() ?: return null
            eventBytes += raw.toByteArray(Charsets.UTF_8).size + 1
            require(eventBytes <= McpPolicy.MAX_RESPONSE_BYTES) { "MCP SSE 事件超过大小限制" }
            val line = if (firstLine) raw.removePrefix("\uFEFF").also { firstLine = false } else raw
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return McpSseEvent(type.ifEmpty { "message" }, data.joinToString("\n"))
                type = "message"
                eventBytes = 0
                continue
            }
            if (line.startsWith(":")) continue
            val field = line.substringBefore(':')
            val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
            when (field) { "event" -> type = value; "data" -> data += value }
        }
    }

    private fun line(): String? {
        val bytes = ByteArrayOutputStream()
        while (!source.exhausted()) {
            val byte = source.readByte().toInt() and 255
            require(++consumed <= maxStreamBytes) { "MCP SSE 响应超过大小限制" }
            if (skipLf) { skipLf = false; if (byte == 10) continue }
            if (byte == 13 || byte == 10) { skipLf = byte == 13; return decodeMcpUtf8(bytes.toByteArray()) }
            bytes.write(byte)
            require(bytes.size() <= McpPolicy.MAX_RESPONSE_BYTES) { "MCP SSE 行超过大小限制" }
        }
        return null
    }
}

internal fun resolveMcpSseEndpoint(base: HttpUrl, raw: String): HttpUrl {
    require(raw.isNotBlank() && raw == raw.trim() && raw.none(Char::isISOControl)) { "MCP SSE endpoint 无效" }
    val endpoint = base.resolve(raw) ?: error("MCP SSE endpoint 无效")
    // long: endpoint 来自远端事件，必须同源，防止认证头被转发到另一个主机或降级到明文。
    require(endpoint.scheme == base.scheme && endpoint.host == base.host && endpoint.port == base.port &&
        endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null) { "MCP SSE endpoint 必须同源" }
    McpServerPolicy.validateResolvedHost(endpoint)
    return endpoint
}

internal class LegacySseMcpConnection(
    private val server: McpServerConfig,
    client: OkHttpClient,
    private val onClosed: () -> Unit,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(5, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var response: Response? = null
    private val calls = linkedSetOf<Call>()
    private val endpoint = CompletableDeferred<HttpUrl>()
    private val pending = linkedMapOf<String, CompletableDeferred<JSONObject>>()
    val isOpen: Boolean get() = synchronized(lock) { !closed && response != null && endpoint.isCompleted && !endpoint.isCancelled }

    suspend fun start() {
        try {
            val base = server.url.toHttpUrl()
            McpServerPolicy.validateResolvedHost(base)
            val request = authenticated(Request.Builder().url(base).header("Accept", "text/event-stream")).get().build()
            val call = client.newCall(request)
            synchronized(lock) { check(!closed); calls += call }
            scope.launch {
                try {
                    val received = headers(call)
                    synchronized(lock) {
                        if (closed) { received.close(); throw IOException("MCP SSE 已关闭") }
                        response = received
                    }
                    received.use {
                        require(it.isSuccessful && it.header("Content-Type").orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
                            "MCP SSE GET 必须返回 text/event-stream"
                        }
                        val reader = McpSseEventReader(it.body?.source() ?: throw IOException("MCP SSE 正文为空"))
                        while (true) {
                            val event = reader.next() ?: throw IOException("MCP SSE 事件流已断开")
                            when (event.type) {
                                "endpoint" -> {
                                    require(!endpoint.isCompleted) { "MCP SSE endpoint 重复" }
                                    endpoint.complete(resolveMcpSseEndpoint(base, event.data))
                                }
                                "message" -> receive(JSONObject(event.data))
                            }
                        }
                    }
                } catch (error: Exception) { shutdown(error) }
            }
            withTimeout(15_000) { endpoint.await() }
        } catch (error: Exception) { shutdown(error); throw error }
    }

    suspend fun request(payload: JSONObject): JSONObject = withTimeout(60_000) {
        val id = payload.opt("id") as? String
        val reply = id?.let { CompletableDeferred<JSONObject>() }
        synchronized(lock) {
            check(isOpen) { "MCP SSE 会话已断开" }
            require(pending.size < 32) { "MCP SSE 待响应请求超过限制" }
            // long: 回复可能早于 POST 的 HTTP ACK，先登记 id 再发送，避免丢失已经返回的工具结果。
            if (id != null) pending[id] = reply!!
        }
        try {
            post(payload)
            reply?.await() ?: JSONObject()
        } catch (error: Exception) { shutdown(error); throw error }
        finally { if (id != null) synchronized(lock) { pending.remove(id) } }
    }

    private fun receive(json: JSONObject) {
        require(json.optString("jsonrpc") == "2.0") { "MCP SSE JSON-RPC 版本无效" }
        if (json.has("method")) {
            if (json.optString("method") in setOf("notifications/tools/list_changed", "notifications/resources/list_changed", "notifications/prompts/list_changed")) {
                throw IOException("MCP 目录已变化，请重新发现")
            }
            if (json.has("id")) {
                // long: 未声明服务器主动执行能力，只回复协议错误；远端请求不能启动本地工具或模型。
                scope.launch { runCatching { post(JSONObject().put("jsonrpc", "2.0").put("id", json.get("id"))
                    .put("error", JSONObject().put("code", -32601).put("message", "Client method not supported"))) }.onFailure { shutdown(it) } }
            }
            return
        }
        val id = json.opt("id") as? String ?: throw IOException("MCP SSE 响应缺少字符串 id")
        validateMcpRpcResponse(json, id)
        synchronized(lock) { pending[id] }?.complete(json)
    }

    private suspend fun post(payload: JSONObject) {
        val url = endpoint.await()
        McpServerPolicy.validateResolvedHost(url)
        val call = client.newCall(authenticated(Request.Builder().url(url)).header("Content-Type", "application/json")
            .post(mcpOneShotBody(payload.toString())).build())
        synchronized(lock) { check(!closed); calls += call }
        try {
            headers(call).use { require(it.isSuccessful) { "MCP SSE POST 失败：HTTP ${it.code}" } }
        } finally { synchronized(lock) { calls.remove(call) }; call.cancel() }
    }

    private fun authenticated(builder: Request.Builder): Request.Builder = builder.apply {
        if (server.bearerToken.isNotBlank()) header("Authorization", "Bearer ${server.bearerToken}")
    }

    private suspend fun headers(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    fun close() = shutdown(IOException("MCP SSE 连接已关闭"))

    private fun shutdown(error: Throwable) {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            response = null
            calls.toList().also { calls.clear() } to pending.values.toList().also { pending.clear() }
        }
        endpoint.completeExceptionally(error)
        state.second.forEach { it.completeExceptionally(error) }
        state.first.forEach(Call::cancel)
        // long: Response 只能由读取线程的 use 关闭；另一线程 close 会并发 drain 同一个 Source，Call.cancel 已足够中断正文读取。
        scope.cancel()
        onClosed()
    }
}
