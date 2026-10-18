@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")

package com.longdev.xiaoling.shared.http

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** long: 宿主登记HTTPS目标并持有生命周期；独立ephemeral session不能继承全局Cookie、凭据或磁盘缓存。 */
class IosHttpClient(private val targets: SharedHttpTargetPolicy) : SharedHttpClient {
    private val lock = NSLock()
    private var closed = false
    private val exchanges = mutableMapOf<ULong, Exchange>()
    private val delegate = Delegate(this)
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            HTTPShouldSetCookies = false
            HTTPCookieStorage = null
            URLCredentialStorage = null
            URLCache = null
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            timeoutIntervalForRequest = 60.0
            timeoutIntervalForResource = 60.0
            HTTPMaximumConnectionsPerHost = 4
        }, delegate, NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
    )

    override suspend fun execute(request: SharedHttpRequest): SharedHttpResponse {
        currentCoroutineContext().ensureActive()
        if (!targets.accepts(request.url)) throw SharedHttpException(SharedHttpFailure.TARGET_REJECTED)
        // long: 系统请求超时会随数据重置，协程总时限另外覆盖响应头、慢正文和回调等待的完整请求。
        return withTimeoutOrNull(request.timeoutMillis) { exchange(request) }
            ?: throw SharedHttpException(SharedHttpFailure.TIMEOUT)
    }

    private suspend fun exchange(request: SharedHttpRequest): SharedHttpResponse = suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString(request.url) ?: throw SharedHttpException(SharedHttpFailure.TARGET_REJECTED)
        val native = NSMutableURLRequest.requestWithURL(url, NSURLRequestReloadIgnoringLocalCacheData, request.timeoutMillis / 1000.0).apply {
            HTTPMethod = request.method.name
            HTTPShouldHandleCookies = false
            request.headers.forEach { (key, value) -> setValue(value, forHTTPHeaderField = key) }
            val bytes = request.body
            if (bytes.isNotEmpty()) HTTPBody = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        }
        var task: NSURLSessionDataTask? = null
        val failure = locked {
            when {
                closed -> SharedHttpFailure.CLIENT_CLOSED
                exchanges.size >= SharedHttpContract.MAX_CONCURRENT_REQUESTS -> SharedHttpFailure.CLIENT_BUSY
                else -> {
                    val created = session.dataTaskWithRequest(native)
                    task = created
                    exchanges[created.taskIdentifier] = Exchange(created, continuation, request.maxResponseBytes)
                    null
                }
            }
        }
        if (failure != null) continuation.resumeWithException(SharedHttpException(failure))
        else {
            val started = task!!
            continuation.invokeOnCancellation { locked { exchanges.remove(started.taskIdentifier) }; started.cancel() }
            started.resume()
        }
    }

    override fun close() {
        val pending = locked {
            if (closed) return
            closed = true
            exchanges.values.toList().also { exchanges.clear() }
        }
        // long: 先撤销账本再取消task，迟到网络回调不能向已关闭宿主发布正文；关闭不是POST未提交的证据。
        pending.forEach { it.task.cancel(); it.continuation.resumeWithException(SharedHttpException(SharedHttpFailure.CLIENT_CLOSED)) }
        session.invalidateAndCancel()
    }

    private fun response(task: NSURLSessionDataTask, response: NSURLResponse): Boolean {
        var failure: SharedHttpFailure? = null
        val found = locked {
            val exchange = exchanges[task.taskIdentifier] ?: return@locked false
            val http = response as? NSHTTPURLResponse
            if (http == null || http.statusCode !in 100..599) failure = SharedHttpFailure.INVALID_RESPONSE
            else if (http.expectedContentLength > exchange.buffer.size) failure = SharedHttpFailure.RESPONSE_TOO_LARGE
            else try {
                val headers = http.allHeaderFields.entries.associate { it.key.toString().lowercase() to it.value.toString() }
                SharedHttpContract.headers(headers, response = true)
                exchange.status = http.statusCode.toInt()
                exchange.headers = headers
            } catch (_: IllegalArgumentException) { failure = SharedHttpFailure.INVALID_RESPONSE }
            true
        }
        failure?.let { fail(task, it) }
        return found && failure == null
    }

    private fun data(task: NSURLSessionDataTask, data: NSData) {
        val overflow = locked {
            val exchange = exchanges[task.taskIdentifier] ?: return@locked false
            if (data.length > (exchange.buffer.size - exchange.size).toULong()) true
            else {
                if (data.length > 0u) exchange.buffer.usePinned { memcpy(it.addressOf(exchange.size), data.bytes, data.length) }
                exchange.size += data.length.toInt()
                false
            }
        }
        if (overflow) fail(task, SharedHttpFailure.RESPONSE_TOO_LARGE)
    }

    private fun complete(task: NSURLSessionTask, error: NSError?) {
        val exchange = locked { exchanges.remove(task.taskIdentifier) } ?: return
        when {
            error != null -> exchange.continuation.resumeWithException(SharedHttpException(
                if (error.domain == NSURLErrorDomain && error.code == NSURLErrorTimedOut) SharedHttpFailure.TIMEOUT else SharedHttpFailure.NETWORK))
            exchange.status == null -> exchange.continuation.resumeWithException(SharedHttpException(SharedHttpFailure.INVALID_RESPONSE))
            else -> exchange.continuation.resume(SharedHttpResponse(exchange.status!!, exchange.headers, exchange.buffer.copyOf(exchange.size)))
        }
    }

    private fun fail(task: NSURLSessionTask, failure: SharedHttpFailure) {
        val exchange = locked { exchanges.remove(task.taskIdentifier) } ?: return
        task.cancel()
        exchange.continuation.resumeWithException(SharedHttpException(failure))
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private class Exchange(val task: NSURLSessionDataTask, val continuation: CancellableContinuation<SharedHttpResponse>, limit: Int) {
        val buffer = ByteArray(limit)
        var size = 0
        var status: Int? = null
        var headers: Map<String, String> = emptyMap()
    }

    private class Delegate(private val owner: IosHttpClient) : NSObject(), NSURLSessionDataDelegateProtocol {
        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveResponse: NSURLResponse, completionHandler: (NSURLSessionResponseDisposition) -> Unit) {
            completionHandler(if (owner.response(dataTask, didReceiveResponse)) NSURLSessionResponseAllow else NSURLSessionResponseCancel)
        }
        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) = owner.data(dataTask, didReceiveData)
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) = owner.complete(task, didCompleteWithError)
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse, newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) {
            // long: 重定向不得携带宿主认证头访问另一个目标，连同源跳转也由上层显式重新选择。
            completionHandler(null)
        }
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didReceiveChallenge: NSURLAuthenticationChallenge, completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) {
            challenge(didReceiveChallenge, completionHandler)
        }
        override fun URLSession(session: NSURLSession, didReceiveChallenge: NSURLAuthenticationChallenge, completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) {
            // long: 连接层与task层都拒绝隐式认证，不能把宿主请求变成系统凭据或客户端证书交互。
            challenge(didReceiveChallenge, completionHandler)
        }
        private fun challenge(value: NSURLAuthenticationChallenge, completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit) {
            completionHandler(if (value.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust)
                NSURLSessionAuthChallengePerformDefaultHandling else NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }
}
