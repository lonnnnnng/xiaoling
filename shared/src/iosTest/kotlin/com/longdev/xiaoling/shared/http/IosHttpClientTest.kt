@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.http

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.NSLock
import platform.darwin.inet_addr
import platform.posix.*
import kotlin.test.*

/** long: 真正的loopback socket验证系统task行为；不注入URLProtocol，也不接触宿主配置或真实令牌。 */
class IosHttpClientTest {
    private data class Reply(val status: Int = 200, val body: String = "正文😀", val headers: String = "", val chunked: Boolean = false, val declared: Int? = null, val stall: Boolean = false, val stallBody: Boolean = false, val rawBody: ByteArray? = null)
    private data class Incoming(val path: String, val headers: String, val body: String)

    private class Server(private val replies: Map<String, Reply>) {
        private val lock = NSLock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val active = mutableSetOf<Int>()
        private val requests = mutableListOf<Incoming>()
        private var closed = false
        private var acceptedConnections = 0
        private val listener = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) }
        val ready = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Unit>()
        val port: Int
        val url: String get() = "http://127.0.0.1:$port"
        private val job: Job

        init {
            port = memScoped {
                val address = alloc<sockaddr_in>()
                address.sin_len = sizeOf<sockaddr_in>().toUByte()
                address.sin_family = AF_INET.toUByte()
                address.sin_port = 0u
                address.sin_addr.s_addr = inet_addr("127.0.0.1")
                check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) == 0)
                check(listen(listener, 8) == 0)
                check(fcntl(listener, F_SETFL, O_NONBLOCK) == 0)
                val length = alloc<socklen_tVar>(); length.value = sizeOf<sockaddr_in>().toUInt()
                check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0)
                // long: ntohs是当前绑定未导入的宏；按网络序读取两个原始字节，不假设宿主端序。
                val networkPort = alloc<UShortVar>(); networkPort.value = address.sin_port
                val bytes = networkPort.ptr.reinterpret<UByteVar>()
                bytes[0].toInt() * 256 + bytes[1].toInt()
            }
            job = scope.launch {
                try {
                    while (!locked { closed }) {
                        val fd = accept(listener, null, null)
                        if (fd < 0) {
                            if (locked { closed }) break
                            check(errno == EAGAIN || errno == EWOULDBLOCK)
                            delay(5)
                            continue
                        }
                        val accepted = locked { acceptedConnections++; if (closed) false else { active += fd; true } }
                        if (!accepted) { platform.posix.close(fd); break }
                        scope.launch {
                            try { serve(fd) }
                            catch (error: Throwable) { ready.completeExceptionally(error); peerClosed.completeExceptionally(error) }
                            finally { locked { active.remove(fd); platform.posix.close(fd) } }
                        }
                    }
                } finally { platform.posix.close(listener) }
            }
        }

        private fun serve(fd: Int) {
            // long: Darwin的accept继承listener非阻塞标记；暂停响应必须真正等待，不能用EAGAIN冒充对端关闭。
            check(fcntl(fd, F_SETFL, 0) == 0)
            memScoped {
                val one = alloc<IntVar>(); one.value = 1
                check(setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().toUInt()) == 0)
                val timeout = alloc<timeval>(); timeout.tv_sec = 5; timeout.tv_usec = 0
                check(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().toUInt()) == 0)
                check(setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().toUInt()) == 0)
            }
            val byte = ByteArray(1)
            fun next(): Int = byte.usePinned { recv(fd, it.addressOf(0), 1u, 0).toInt() }.also { check(it > 0) }.let { byte[0].toInt() and 255 }
            val header = buildString {
                while (!endsWith("\r\n\r\n")) { check(length < 16_384); append(next().toChar()) }
            }
            val size = Regex("(?im)^Content-Length: ([0-9]+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
            check(size <= SharedHttpContract.MAX_REQUEST_BYTES)
            val body = ByteArray(size) { next().toByte() }.decodeToString()
            val path = header.lineSequence().first().split(' ')[1]
            locked { requests += Incoming(path, header, body) }
            val reply = replies[path] ?: Reply(404, "missing")
            if (!reply.stall || reply.stallBody) {
                val bytes = reply.rawBody ?: reply.body.encodeToByteArray()
                val framing = if (reply.chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${reply.declared ?: bytes.size}\r\n"
                sendBytes(fd, "HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n$framing${reply.headers}\r\n".encodeToByteArray())
                if (reply.chunked) {
                    sendBytes(fd, "${bytes.size.toString(16)}\r\n".encodeToByteArray()); sendBytes(fd, bytes); sendBytes(fd, "\r\n0\r\n\r\n".encodeToByteArray())
                } else sendBytes(fd, bytes)
            }
            ready.complete(Unit)
            if (reply.stall) {
                val count = byte.usePinned { recv(fd, it.addressOf(0), 1u, 0).toInt() }
                check(count == 0 || (count < 0 && errno == ECONNRESET)) { "取消后socket未关闭" }
                peerClosed.complete(Unit)
            }
        }
        private fun sendBytes(fd: Int, bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned { send(fd, it.addressOf(offset), (bytes.size - offset).toULong(), 0).toInt() }
                check(count > 0); offset += count
            }
        }
        fun received(): List<Incoming> = locked { requests.toList() }
        fun acceptedCount(): Int = locked { acceptedConnections }
        suspend fun close() {
            // long: 仅关闭当前实例持有的listener/连接；shutdown唤醒阻塞原生调用，worker独占最终close。
            locked { closed = true; shutdown(listener, SHUT_RDWR); active.forEach { shutdown(it, SHUT_RDWR) } }
            withTimeout(6_000) { job.join(); scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            scope.cancel()
        }
        private inline fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }
    }

    private suspend fun fixture(replies: Map<String, Reply>, block: suspend (Server, IosHttpClient) -> Unit) {
        val server = Server(replies)
        val client = IosHttpClient(SharedHttpTargetPolicy.loopbackTest(server.url))
        try { withTimeout(10_000) { block(server, client) } }
        finally { client.close(); server.close() }
    }

    @Test fun getAndPostUseRealSocketAndPreserveBytesStatusAndHostHeaders(): Unit = runBlocking {
        fixture(mapOf("/get" to Reply(), "/post" to Reply(401, "auth-failed"))) { server, client ->
            val response = client.execute(SharedHttpRequest(server.url + "/get"))
            assertEquals(200, response.statusCode); assertEquals("正文😀", response.body.decodeToString())
            val post = client.execute(SharedHttpRequest(server.url + "/post", SharedHttpMethod.POST, mapOf("Authorization" to "Bearer fixture-only"), "请求😀".encodeToByteArray()))
            assertEquals(401, post.statusCode); assertEquals("auth-failed", post.body.decodeToString())
            assertEquals("请求😀", server.received()[1].body)
            assertTrue(server.received()[1].headers.contains("Authorization: Bearer fixture-only", ignoreCase = true))
        }
    }
    @Test fun redirectsNeverVisitTargetEvenWhenItHasSameOrigin(): Unit = runBlocking {
        fixture(mapOf("/redirect" to Reply(302, "redirect", "Location: /target\r\n"))) { server, client ->
            val response = client.execute(SharedHttpRequest(server.url + "/redirect", headers = mapOf("Authorization" to "Bearer fixture-only")))
            assertEquals(302, response.statusCode)
            assertEquals(listOf("/redirect"), server.received().map { it.path })
        }
    }
    @Test fun cookiesAndCacheDoNotPersistAcrossRequests(): Unit = runBlocking {
        fixture(mapOf("/same" to Reply(headers = "Set-Cookie: fixture=private; Path=/\r\nCache-Control: public, max-age=3600\r\n"))) { server, client ->
            repeat(2) { assertEquals(200, client.execute(SharedHttpRequest(server.url + "/same")).statusCode) }
            assertEquals(2, server.received().size)
            assertTrue(server.received().all { !Regex("(?im)^Cookie:").containsMatchIn(it.headers) })
        }
    }
    @Test fun declaredAndChunkedOverflowRejectWithoutReturningPartialBody(): Unit = runBlocking {
        fixture(mapOf("/declared" to Reply(body = "", declared = 65), "/chunked" to Reply(body = "x".repeat(65), chunked = true), "/exact" to Reply(body = "x".repeat(64)))) { server, client ->
            for (path in listOf("/declared", "/chunked")) {
                val error = assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + path, maxResponseBytes = 64)) }
                assertEquals(SharedHttpFailure.RESPONSE_TOO_LARGE, error.failure)
            }
            assertEquals(64, client.execute(SharedHttpRequest(server.url + "/exact", maxResponseBytes = 64)).body.size)
        }
    }
    @Test fun cancellationBeforeHeadersAndDuringBodyClosesActualSocket(): Unit = runBlocking {
        for (body in listOf(false, true)) fixture(mapOf("/stall" to Reply(body = "x", declared = 9999, stall = true, stallBody = body))) { server, client ->
            val call = async { client.execute(SharedHttpRequest(server.url + "/stall")) }
            withTimeout(3_000) { server.ready.await() }; call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            withTimeout(3_000) { server.peerClosed.await() }
        }
    }
    @Test fun totalDeadlineCancelsStalledSocketAndHasFixedFailure(): Unit = runBlocking {
        fixture(mapOf("/stall" to Reply(stall = true))) { server, client ->
            val error = assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + "/stall", timeoutMillis = 500)) }
            assertEquals(SharedHttpFailure.TIMEOUT, error.failure)
            withTimeout(3_000) { server.peerClosed.await() }
            assertNull(error.cause); assertFalse(error.message!!.contains(server.url))
        }
    }
    @Test fun closeSettlesPendingRequestAndRejectsReuse(): Unit = runBlocking {
        fixture(mapOf("/stall" to Reply(stall = true))) { server, client -> supervisorScope {
            val call = async { client.execute(SharedHttpRequest(server.url + "/stall")) }
            withTimeout(3_000) { server.ready.await() }; client.close(); client.close()
            assertEquals(SharedHttpFailure.CLIENT_CLOSED, assertFailsWith<SharedHttpException> { call.await() }.failure)
            withTimeout(3_000) { server.peerClosed.await() }
            assertEquals(SharedHttpFailure.CLIENT_CLOSED, assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + "/stall")) }.failure)
        } }
    }
    @Test fun targetMismatchAndCancelledCallerHaveZeroServerAccess(): Unit = runBlocking {
        fixture(emptyMap()) { server, client ->
            assertEquals(SharedHttpFailure.TARGET_REJECTED, assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest("https://other.example.com/private")) }.failure)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                client.execute(SharedHttpRequest(server.url))
            }
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(server.received().isEmpty())
            assertEquals(0, server.acceptedCount())
        }
    }
    @Test fun concurrencyLimitDoesNotQueueAnUnboundedFifthRequest(): Unit = runBlocking {
        fixture(mapOf("/stall" to Reply(stall = true))) { server, client ->
            val calls = List(4) { async(start = CoroutineStart.UNDISPATCHED) { client.execute(SharedHttpRequest(server.url + "/stall")) } }
            assertEquals(SharedHttpFailure.CLIENT_BUSY, assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + "/stall")) }.failure)
            calls.forEach { it.cancel() }; calls.forEach { assertFailsWith<CancellationException> { it.await() } }
        }
    }
    @Test fun truncatedWireHasFixedNetworkErrorAndNoBodyOrUrlLeak(): Unit = runBlocking {
        fixture(mapOf("/broken" to Reply(body = "private-partial", declared = 100))) { server, client ->
            val error = assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + "/broken")) }
            assertEquals(SharedHttpFailure.NETWORK, error.failure)
            assertNull(error.cause); assertFalse(error.message!!.contains("private")); assertFalse(error.message!!.contains(server.url))
        }
    }
    @Test fun nativeResponseKeepsNullAndNonUtf8BytesUnchanged(): Unit = runBlocking {
        val bytes = byteArrayOf(0, 1, 0xff.toByte(), 2)
        fixture(mapOf("/bytes" to Reply(rawBody = bytes))) { server, client ->
            assertContentEquals(bytes, client.execute(SharedHttpRequest(server.url + "/bytes")).body)
        }
    }
    @Test fun crossOriginRedirectDoesNotConnectToTheOtherServer(): Unit = runBlocking {
        val target = Server(emptyMap())
        try {
            fixture(mapOf("/redirect" to Reply(302, "redirect", "Location: ${target.url}/target\r\n"))) { server, client ->
                assertEquals(302, client.execute(SharedHttpRequest(server.url + "/redirect", headers = mapOf("Authorization" to "Bearer fixture-only"))).statusCode)
                assertEquals(1, server.received().size)
                assertEquals(0, target.acceptedCount())
            }
        } finally { target.close() }
    }
    @Test fun basicChallengeIsRejectedWithoutCredentialResubmission(): Unit = runBlocking {
        fixture(mapOf("/challenge" to Reply(401, "challenge", "WWW-Authenticate: Basic realm=\"fixture\"\r\n"))) { server, client ->
            assertEquals(SharedHttpFailure.NETWORK, assertFailsWith<SharedHttpException> { client.execute(SharedHttpRequest(server.url + "/challenge")) }.failure)
            assertEquals(1, server.received().size)
            assertFalse(server.received().single().headers.contains("Authorization:", ignoreCase = true))
        }
    }
    @Test fun http503WithRetryAfterIsReturnedWithoutApplicationRetry(): Unit = runBlocking {
        fixture(mapOf("/status" to Reply(503, "busy", "Retry-After: 0\r\n"))) { server, client ->
            assertEquals(503, client.execute(SharedHttpRequest(server.url + "/status", SharedHttpMethod.POST, body = "once".encodeToByteArray())).statusCode)
            assertEquals(1, server.received().size)
        }
    }
}
