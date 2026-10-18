package com.longdev.xiaoling.shared.http

object SharedHttpContract {
    const val VERSION = 1
    const val MAX_REQUEST_BYTES = 512 * 1024
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val DEFAULT_RESPONSE_BYTES = 1024 * 1024
    const val MAX_CONCURRENT_REQUESTS = 4

    fun origin(url: String): String {
        valid(url.length in 1..8192 && url.all { it.code in 33..126 } && '\\' !in url && '#' !in url)
        val match = Regex("(https?)://([^/?]+)(?:[/?].*)?").matchEntire(url)
        valid(match != null)
        val scheme = match!!.groupValues[1]
        val authority = match.groupValues[2]
        val parts = authority.split(':')
        valid(parts.size in 1..2)
        val host = parts[0].lowercase()
        valid(host.length in 1..253 && host.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' }
        })
        val port = if (parts.size == 1) (if (scheme == "https") 443 else 80) else {
            valid(parts[1].matches(Regex("[1-9][0-9]{0,4}")))
            parts[1].toInt().also { valid(it in 1..65535) }
        }
        // long: URL 原文不归一化正文路径；仅规范 origin，避免默认端口或域名大小写绕过宿主目标绑定。
        var i = 0
        while (i < url.length) {
            if (url[i] == '%') {
                valid(i + 2 < url.length && url.substring(i + 1, i + 3).all { it in "0123456789abcdefABCDEF" })
                i += 3
            } else i++
        }
        return "$scheme://$host:$port"
    }

    fun headers(values: Map<String, String>, response: Boolean = false) {
        valid(values.size <= if (response) 64 else 32)
        valid(values.keys.map { it.lowercase() }.distinct().size == values.size)
        var bytes = 0
        for ((key, value) in values) {
            valid(key.length in 1..128 && key.all { it in "!#$%&'*+-.^_`|~" || it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' })
            valid(value.length <= 4096 && value.all { it.code in 32..126 || (response && it == '\t') })
            if (!response) valid(key.lowercase() !in setOf("host", "cookie", "content-length", "connection", "transfer-encoding", "proxy-authorization"))
            bytes += key.length + value.length
        }
        valid(bytes <= if (response) 32 * 1024 else 16 * 1024)
    }

    internal fun valid(value: Boolean) { require(value) { "HTTP 契约输入无效" } }
}

enum class SharedHttpMethod { GET, POST }

/** long: 请求是宿主能力而不是模型工具参数；防御性复制和脱敏toString避免令牌/正文随日志泄露。 */
class SharedHttpRequest(
    val url: String,
    val method: SharedHttpMethod = SharedHttpMethod.GET,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = byteArrayOf(),
    val timeoutMillis: Long = 30_000,
    val maxResponseBytes: Int = SharedHttpContract.DEFAULT_RESPONSE_BYTES,
) {
    private val headerValues = headers.also { SharedHttpContract.headers(it) }.toMap()
    private val bodyValue = body.also { SharedHttpContract.valid(it.size <= SharedHttpContract.MAX_REQUEST_BYTES) }.copyOf()
    val headers: Map<String, String> get() = headerValues.toMap()
    val body: ByteArray get() = bodyValue.copyOf()
    init {
        SharedHttpContract.origin(url)
        SharedHttpContract.headers(headerValues)
        SharedHttpContract.valid(bodyValue.size <= SharedHttpContract.MAX_REQUEST_BYTES)
        SharedHttpContract.valid(method != SharedHttpMethod.GET || bodyValue.isEmpty())
        SharedHttpContract.valid(timeoutMillis in 100..60_000)
        SharedHttpContract.valid(maxResponseBytes in 1..SharedHttpContract.MAX_RESPONSE_BYTES)
    }
    override fun toString() = "SharedHttpRequest(method=$method, headerCount=${headerValues.size}, bodyBytes=${bodyValue.size})"
}

/** long: HTTP状态与原始字节不代表业务验证或提交成功，解码与许可由上层协议独立负责。 */
class SharedHttpResponse(val statusCode: Int, headers: Map<String, String>, body: ByteArray) {
    private val headerValues = headers.also { SharedHttpContract.headers(it, response = true) }.toMap()
    private val bodyValue = body.also { SharedHttpContract.valid(it.size <= SharedHttpContract.MAX_RESPONSE_BYTES) }.copyOf()
    val headers: Map<String, String> get() = headerValues.toMap()
    val body: ByteArray get() = bodyValue.copyOf()
    init {
        SharedHttpContract.valid(statusCode in 100..599)
        SharedHttpContract.headers(headerValues, response = true)
        SharedHttpContract.valid(bodyValue.size <= SharedHttpContract.MAX_RESPONSE_BYTES)
    }
    override fun toString() = "SharedHttpResponse(statusCode=$statusCode, headerCount=${headerValues.size}, bodyBytes=${bodyValue.size})"
}

class SharedHttpTargetPolicy private constructor(private val origins: Set<String>) {
    fun accepts(url: String): Boolean = SharedHttpContract.origin(url) in origins
    override fun toString() = "SharedHttpTargetPolicy(originCount=${origins.size})"
    companion object {
        fun https(endpointUrls: Set<String>): SharedHttpTargetPolicy {
            SharedHttpContract.valid(endpointUrls.size in 1..8)
            val origins = endpointUrls.map { SharedHttpContract.origin(it) }.toSet()
            SharedHttpContract.valid(origins.all { it.startsWith("https://") })
            return SharedHttpTargetPolicy(origins)
        }
        // long: 清文HTTP仅用于模块内部真实socket夹具，不能作为产品配置或模型提供的任意地址开关。
        internal fun loopbackTest(endpointUrl: String): SharedHttpTargetPolicy {
            val origin = SharedHttpContract.origin(endpointUrl)
            SharedHttpContract.valid(origin.startsWith("http://127.0.0.1:"))
            return SharedHttpTargetPolicy(setOf(origin))
        }
    }
}

enum class SharedHttpFailure { TARGET_REJECTED, CLIENT_CLOSED, CLIENT_BUSY, TIMEOUT, NETWORK, INVALID_RESPONSE, RESPONSE_TOO_LARGE }
class SharedHttpException(val failure: SharedHttpFailure) : IllegalStateException("HTTP 请求失败：${failure.name}")

interface SharedHttpClient {
    suspend fun execute(request: SharedHttpRequest): SharedHttpResponse
    fun close()
}
