package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
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
    fun embeddingsRequestUsesConfiguredTransportAndRestoresIndexOrder() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {
                      "data": [
                        {"index":1,"embedding":[0.0,1.0]},
                        {"index":0,"embedding":[1.0,0.0]}
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val vectors = OpenAiCompatibleClient().createEmbeddings(
            config = config(userAgent = "Embedding Client/1.0").copy(
                embeddingModel = "text-embedding-test",
                customHeaders = mapOf("X-Test-Tenant" to "tenant-1"),
            ),
            inputs = listOf("第一段", "第二段"),
        )
        val request = server.takeRequest()

        assertEquals("/v1/embeddings", request.path)
        assertEquals("Embedding Client/1.0", request.getHeader("User-Agent"))
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("tenant-1", request.getHeader("X-Test-Tenant"))
        assertTrue(request.body.readUtf8().contains("text-embedding-test"))
        assertEquals(listOf(1.0f, 0.0f), vectors[0].toList())
        assertEquals(listOf(0.0f, 1.0f), vectors[1].toList())
    }

    @Test
    fun embeddingsRejectMalformedResponseBeforeReturningPartialVectors() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"index":0,"embedding":[1.0,0.0]},{"index":1,"embedding":[1.0]}]}""",
            ),
        )

        val failure = runCatching {
            OpenAiCompatibleClient().createEmbeddings(
                config = config(userAgent = "Embedding Test/1.0").copy(embeddingModel = "text-embedding-test"),
                inputs = listOf("第一段", "第二段"),
            )
        }.exceptionOrNull() as ApiFailure

        assertEquals(FailureKind.RESPONSE, failure.kind)
        assertTrue(failure.message.orEmpty().contains("维度"))
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
    fun nonStreamingResponsesReturnsDisplayableReasoningSummariesWithoutRawChainOfThought() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {
                      "output": [
                        {
                          "id": "rs-client-1",
                          "type": "reasoning",
                          "summary": [{"type": "summary_text", "text": "核对事实后给出简洁回答。"}],
                          "content": [{"type": "reasoning_text", "text": "原始思维链"}]
                        },
                        {
                          "type": "message",
                          "content": [{"type": "output_text", "text": "最终答案"}]
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val result = OpenAiCompatibleClient().sendMessage(
            config = config(userAgent = "Reasoning Summary/1.0", model = "gpt-test").copy(
                apiMode = ApiMode.RESPONSES,
                reasoningSummaryEnabled = true,
            ),
            messages = listOf(RequestMessage(role = "user", content = "回答问题")),
        )

        assertEquals("最终答案", result.responseText)
        assertEquals(listOf("核对事实后给出简洁回答。"), result.reasoningSummaries.map { it.text })
        assertEquals(listOf("rs-client-1"), result.reasoningSummaries.map { it.providerItemId })
        assertTrue(result.reasoningSummaries.none { it.text.contains("原始思维链") })
    }

    @Test
    fun streamingResponsesAggregatesReasoningSummarySeparatelyFromAnswerText() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                        data: {"type":"response.reasoning_summary_text.delta","item_id":"rs-stream-1","summary_index":0,"delta":"先核对"}

                        data: {"type":"response.reasoning_summary_text.delta","item_id":"rs-stream-1","summary_index":0,"delta":"事实。"}

                        data: {"type":"response.reasoning_summary_text.done","item_id":"rs-stream-1","summary_index":0,"text":"先核对事实，再回答。"}

                        data: {"type":"response.output_text.delta","delta":"最终"}

                        data: {"type":"response.output_text.done","text":"最终答案"}

                        data: [DONE]

                    """.trimIndent(),
                ),
        )

        val result = OpenAiCompatibleClient().sendMessage(
            config = config(userAgent = "Reasoning Stream/1.0", model = "gpt-test").copy(
                apiMode = ApiMode.RESPONSES,
                streamingEnabled = true,
                reasoningSummaryEnabled = true,
            ),
            messages = listOf(RequestMessage(role = "user", content = "回答问题")),
        )

        assertEquals("最终答案", result.responseText)
        assertEquals(listOf("先核对事实，再回答。"), result.reasoningSummaries.map { it.text })
        assertEquals(listOf("rs-stream-1"), result.reasoningSummaries.map { it.providerItemId })
    }

    @Test
    fun streamingDisconnectAfterDeltaKeepsDeliveredUpdateAndFailsAsConnection() = runBlocking {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val deliveredEvent = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分答案\"}\n\n"
        val serverThread = thread(name = "partial-stream-server") {
            serverSocket.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) {
                    // long: 请求正文与断流分类无关；读完请求头后即可发送一个完整 delta，再通过缺失的响应字节稳定制造 unexpected end of stream。
                }
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Content-Length: ${deliveredEvent.toByteArray().size + 128}\r\n" +
                                "Connection: close\r\n\r\n" +
                                deliveredEvent
                            ).toByteArray(),
                    )
                    flush()
                }
            }
        }
        val updates = mutableListOf<StreamDeltaUpdate>()
        try {
            val failure = runCatching {
                OpenAiCompatibleClient().sendMessage(
                    config = ProviderRequestConfig(
                        baseUrl = "http://${serverSocket.inetAddress.hostAddress}:${serverSocket.localPort}/v1",
                        apiKey = "test-key",
                        model = "gpt-test",
                        apiMode = ApiMode.RESPONSES,
                        streamingEnabled = true,
                    ),
                    messages = listOf(RequestMessage(role = "user", content = "回答问题")),
                    onStreamDelta = updates::add,
                )
            }.exceptionOrNull() as ApiFailure

            assertEquals(listOf("部分答案"), updates.map { it.accumulatedText })
            assertEquals(FailureKind.CONNECTION, failure.kind)
        } finally {
            serverSocket.close()
            serverThread.join(2_000L)
        }
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
