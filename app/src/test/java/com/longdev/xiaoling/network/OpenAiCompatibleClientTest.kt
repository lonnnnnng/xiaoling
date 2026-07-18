package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ProviderRequestConfig
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun modelRequestUsesConfiguredUserAgent() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-test"}]}"""))

        OpenAiCompatibleClient().fetchModels(config(userAgent = "Custom Client/1.0"))

        assertEquals("Custom Client/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun blankUserAgentFallsBackToCodexDesktopDefault() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-test"}]}"""))

        OpenAiCompatibleClient().fetchModels(config(userAgent = "  "))

        assertEquals(ProviderRequestConfig.DEFAULT_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        assertEquals(
            "Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)",
            ProviderRequestConfig.DEFAULT_USER_AGENT,
        )
    }

    @Test
    fun connectionResetDuringResponseIsClassifiedAsConnectionFailure() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"response should be interrupted"}}]}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val failure = runCatching {
            OpenAiCompatibleClient().sendMessage(
                config = config(userAgent = "Fault Injection/1.0", model = "gpt-test"),
                messages = listOf(RequestMessage(role = "user", content = "ping")),
            )
        }.exceptionOrNull() as ApiFailure

        assertEquals(FailureKind.CONNECTION, failure.kind)
    }

    @Test
    fun nonStreamingResponseCapturesRequestBytesFirstByteAndUsage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {
                      "choices":[{"message":{"content":"pong"}}],
                      "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}
                    }
                """.trimIndent(),
            ),
        )

        val result = OpenAiCompatibleClient().sendMessage(
            config = config(userAgent = "Telemetry/1.0", model = "gpt-test"),
            messages = listOf(RequestMessage(role = "user", content = "ping")),
        )
        val recordedRequest = server.takeRequest()

        assertEquals("pong", result.responseText)
        assertEquals(recordedRequest.bodySize, result.promptBytes.toLong())
        assertNotNull(result.firstByteLatencyMs)
        assertTrue(result.firstByteLatencyMs!! in 0L..result.latencyMs)
        assertEquals(12L, result.usage?.inputTokens)
        assertEquals(3L, result.usage?.outputTokens)
        assertEquals(15L, result.usage?.totalTokens)
    }

    @Test
    fun firstByteLatencyWaitsForBodyAfterHeaders() = runBlocking {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val responseBody = """{"choices":[{"message":{"content":"delayed"}}]}"""
        val serverThread = thread(name = "delayed-response-server") {
            serverSocket.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) {
                    // long: 读到空行即代表请求头结束；body 很小且已由客户端发送，不需要解析 Prompt 内容才能构造延迟响应。
                }
                val output = socket.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${responseBody.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                Thread.sleep(200L)
                output.write(responseBody.toByteArray())
                output.flush()
            }
        }
        try {
            val result = OpenAiCompatibleClient().sendMessage(
                config = ProviderRequestConfig(
                    baseUrl = "http://${serverSocket.inetAddress.hostAddress}:${serverSocket.localPort}/v1",
                    apiKey = "test-key",
                    model = "gpt-test",
                ),
                messages = listOf(RequestMessage(role = "user", content = "ping")),
            )

            assertTrue("firstByteLatencyMs=${result.firstByteLatencyMs}", result.firstByteLatencyMs!! >= 150L)
        } finally {
            serverSocket.close()
            serverThread.join(2_000L)
        }
    }

    private fun config(userAgent: String, model: String = "") = ProviderRequestConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = model,
        userAgent = userAgent,
    )
}
