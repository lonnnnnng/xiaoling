package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ProviderRequestConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** long: 半开 socket 让取消等待真实响应，而不是只取消尚未进入网络的协程。 */
class OpenAiCancellationTest {
    @Test fun cancellationClosesRequestBeforeHeadersForBothModes() = runBlocking {
        listOf(false, true).forEach { verify(it, false) }
    }
    @Test fun cancellationClosesStalledBodyForBothModes() = runBlocking {
        listOf(false, true).forEach { verify(it, true) }
    }
    private suspend fun verify(streaming: Boolean, sendHeaders: Boolean) = coroutineScope {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val ready = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val worker = thread(name = "plugin-model-cancellation-fixture") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val value = input.read(); check(value >= 0); header.append(value.toChar()) }
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    if (sendHeaders) {
                        val type = if (streaming) "text/event-stream" else "application/json"
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: 999999\r\n\r\n{\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                    ready.complete(Unit)
                    // long: 客户端取消必须使服务端读到 EOF，证明实际 socket 已关闭，而非仅 UI 停止等待。
                    check(input.read() == -1); closed.complete(Unit)
                }
            } catch (e: Throwable) { ready.completeExceptionally(e); closed.completeExceptionally(e) }
        }
        var error: Throwable? = null
        val job = launch(Dispatchers.IO) { try {
            OpenAiCompatibleClient().sendMessage(ProviderRequestConfig("http://127.0.0.1:${server.localPort}/v1", "fixture", "fixture",
                streamingEnabled = streaming, httpDebugLoggingEnabled = false), listOf(RequestMessage("user", "fixture")))
        } catch (e: Throwable) { error = e } }
        try {
            withTimeout(3_000) { ready.await() }; job.cancel()
            withTimeout(3_000) { job.join(); closed.await() }
            assertTrue(error is CancellationException); assertTrue(job.isCancelled)
        } finally { job.cancel(); server.close(); worker.join(2_000) }
    }
}
