package com.longdev.xiaoling.network

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class FailureKind(val title: String) {
    AUTHENTICATION("鉴权失败"),
    REQUEST_URL("接口地址错误"),
    RATE_LIMIT("限流或额度不足"),
    MODEL("模型不可用"),
    TIMEOUT("请求超时"),
    DNS("域名解析失败"),
    TLS("TLS 连接失败"),
    CONNECTION("无法连接服务器"),
    RESPONSE("响应格式不兼容"),
    UNKNOWN("请求失败"),
}

class ApiFailure(
    val kind: FailureKind,
    override val message: String,
    val statusCode: Int? = null,
) : IOException(message)

object ApiFailureClassifier {
    fun fromHttp(statusCode: Int, body: String): ApiFailure {
        val detail = extractServerMessage(body)
        val kind = when (statusCode) {
            401, 403 -> FailureKind.AUTHENTICATION
            404 -> if (detail.contains("model", ignoreCase = true)) FailureKind.MODEL else FailureKind.REQUEST_URL
            429 -> FailureKind.RATE_LIMIT
            else -> if (detail.contains("model", ignoreCase = true)) FailureKind.MODEL else FailureKind.UNKNOWN
        }
        return ApiFailure(kind, "HTTP $statusCode · $detail", statusCode)
    }

    fun fromNetwork(error: IOException): ApiFailure = when (error) {
        is SocketTimeoutException -> ApiFailure(FailureKind.TIMEOUT, "服务器在限定时间内没有响应")
        is UnknownHostException -> ApiFailure(FailureKind.DNS, "无法解析服务器域名")
        is SSLException -> ApiFailure(FailureKind.TLS, error.message ?: "TLS 握手失败")
        is ConnectException -> ApiFailure(FailureKind.CONNECTION, error.message ?: "连接被拒绝")
        is ApiFailure -> error
        is ProtocolException -> if (error.isConnectionInterruption()) {
            ApiFailure(FailureKind.CONNECTION, error.message ?: "网络连接意外中断")
        } else {
            ApiFailure(FailureKind.RESPONSE, error.message ?: "HTTP 响应协议不兼容")
        }
        // long: 只有明确的 EOF、连接重置或流中断才归为连接失败；无法识别的 I/O 异常保留 UNKNOWN，避免把协议问题错误纳入自动重试范围。
        else -> if (error.isConnectionInterruption()) {
            ApiFailure(FailureKind.CONNECTION, error.message ?: "网络连接意外中断")
        } else {
            ApiFailure(FailureKind.UNKNOWN, error.message ?: "未知网络错误")
        }
    }

    private fun IOException.isConnectionInterruption(): Boolean {
        if (this is EOFException) return true
        val reason = message.orEmpty()
        return listOf(
            "unexpected end of stream",
            "connection reset",
            "broken pipe",
            "stream was reset",
            "socket closed",
        ).any { marker -> reason.contains(marker, ignoreCase = true) }
    }

    private fun extractServerMessage(body: String): String {
        if (body.isBlank()) return "服务器没有返回错误详情"
        return runCatching {
            val json = JSONObject(body)
            val error = json.opt("error")
            when (error) {
                is JSONObject -> error.optString("message").ifBlank { error.toString() }
                is String -> error
                else -> json.optString("message").ifBlank { body.take(300) }
            }
        }.getOrElse { body.take(300) }
    }
}
