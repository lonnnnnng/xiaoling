package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpTransportBoundaryTest {
    @Test fun sseParserHandlesBomCommentsCrLfAndMultilineWithoutPublishingPartialEvent() {
        val reader = McpSseEventReader(Buffer().writeUtf8("\uFEFF: heartbeat\r\nevent: message\r\ndata: first\r\ndata: second\r\n\r\ndata: partial"))
        assertEquals(McpSseEvent("message", "first\nsecond"), reader.next())
        assertNull(reader.next())
        assertEquals(McpSseEvent("message", "ok"), McpSseEventReader(Buffer().writeUtf8("data: ok\r\r")).next())
    }

    @Test fun sseParserRejectsInvalidUtf8OversizedEventAndUnboundedHeartbeats() {
        assertThrows(Exception::class.java) { McpSseEventReader(Buffer().write(byteArrayOf(0xC3.toByte(), 0x28, 10))).next() }
        assertThrows(IllegalArgumentException::class.java) { McpSseEventReader(Buffer().writeUtf8("data: " + "x".repeat(McpPolicy.MAX_RESPONSE_BYTES) + "\n\n")).next() }
        assertThrows(IllegalArgumentException::class.java) { McpSseEventReader(Buffer().writeUtf8(": heartbeat\n\n".repeat(20)), 64).next() }
    }

    @Test fun endpointRejectsCrossOriginCredentialsFragmentsAndControls() {
        val base = "http://127.0.0.1:1234/sse".toHttpUrl()
        assertEquals("/messages", resolveMcpSseEndpoint(base, "/messages?stream=1").encodedPath)
        listOf("http://127.0.0.1:1235/messages", "https://example.com/messages", "http://user@127.0.0.1:1234/messages", "/messages#part", "/messages\n", " /messages").forEach {
            assertThrows(Exception::class.java) { resolveMcpSseEndpoint(base, it) }
        }
    }

    @Test fun invalidLegacyVersionMimeAndEndpointNeverPublishSession() = runBlocking {
        for (variant in 0..2) McpSseFixture().use { fixture ->
            when (variant) { 0 -> fixture.protocolVersion = "2099-01-01"; 1 -> fixture.mimeType = "application/json"; 2 -> fixture.endpointOverride = "https://example.com/steal" }
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                assertTrue(runCatching { registry.client("settings").connect(config) }.isFailure)
                assertEquals(McpConnectionState.DISCONNECTED, registry.state("settings", config.id))
                assertTrue(registry.availableTools("settings", config.id).isEmpty())
                assertEquals(0, fixture.methodCount("tools/list"))
                if (variant != 0) assertEquals(0, fixture.requests.size)
            } finally { registry.close() }
        }
    }

    @Test fun malformedJsonRpcResponseDoesNotMatchARequest() {
        listOf(JSONObject().put("id", "1").put("result", JSONObject()),
            JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject()),
            JSONObject().put("jsonrpc", "2.0").put("id", "1").put("result", JSONObject()).put("error", JSONObject()),
            JSONObject().put("jsonrpc", "2.0").put("id", "1").put("result", JSONArray())).forEach {
            assertThrows(Exception::class.java) { validateMcpRpcResponse(it, "1") }
        }
    }

    @Test fun streamableSession404FailsCallAndNextExplicitDiscoveryReinitializes() = runBlocking {
        val initializations = AtomicInteger()
        val calls = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(initializations) { rpc ->
                if (rpc.optString("method") == "tools/call") { calls.incrementAndGet(); MockResponse().setResponseCode(404) }
                else toolResponse(rpc)
            }
            server.start()
            val config = McpServerConfig("http-expired", "Expired", server.url("/mcp").toString())
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val owned = registry.client("run")
                owned.listTools(config)
                assertTrue(runCatching { owned.callTool(config, "fixture.echo", JSONObject()) }.isFailure)
                assertFalse(owned.isConnected(config))
                owned.listTools(config)
                assertEquals(2, initializations.get()); assertEquals(1, calls.get())
            } finally { registry.close() }
        }
    }

    @Test fun cancellationDuringHttpBodyReadSettlesPromptly() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val initializations = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(initializations) { rpc -> toolResponse(rpc).setHeader("X-Stall", "true").setBodyDelay(2, TimeUnit.SECONDS) }
            server.start()
            val okHttp = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.header("X-Stall") != "true") response else {
                    val body = response.body!!
                    val source = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long { bodyStarted.countDown(); return super.read(sink, byteCount) }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = source
                    }).build()
                }
            }.build()
            val client = StreamableHttpMcpClient(okHttp)
            val config = McpServerConfig("http-body", "Body", server.url("/mcp").toString())
            try {
                val job = launch(Dispatchers.IO) { client.listTools(config) }
                assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
                job.cancel(); withTimeout(5000) { job.join() }
                assertTrue(job.isCancelled); assertFalse(client.isConnected(config))
            } finally { client.close() }
        }
    }

    @Test fun http503RetryAfterZeroDoesNotResubmitToolCall() = runBlocking {
        val initializations = AtomicInteger()
        val calls = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(initializations) { rpc ->
                if (rpc.optString("method") == "tools/call") { calls.incrementAndGet(); MockResponse().setResponseCode(503).setHeader("Retry-After", "0") }
                else toolResponse(rpc)
            }
            server.start()
            val config = McpServerConfig("http-once", "Once", server.url("/mcp").toString())
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                client.listTools(config)
                assertTrue(runCatching { client.callTool(config, "fixture.echo", JSONObject()) }.isFailure)
                assertEquals(1, calls.get())
            } finally { registry.close() }
        }
    }

    @Test fun streamableSseReturnsMatchingResponseWithoutReadingTrailingStream() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(AtomicInteger()) { rpc ->
                val matched = JSONObject().put("jsonrpc", "2.0").put("id", rpc.getString("id"))
                    .put("result", JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "fixture.echo"))))
                // long: 匹配回复后仍有超过总响应上限的流数据，客户端必须立即完成而不能读到 EOF 才解析。
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setChunkedBody("event: message\ndata: $matched\n\n" + "x".repeat(McpPolicy.MAX_RESPONSE_BYTES * 2), 4096)
            }
            server.start()
            val client = StreamableHttpMcpClient()
            try {
                val config = McpServerConfig("http-sse", "HTTP SSE", server.url("/mcp").toString())
                assertEquals("fixture.echo", withTimeout(5000) { client.listTools(config) }.single().name)
            } finally { client.close() }
        }
    }

    @Test fun failedConfigurationSaveRetiresConnectionsAndBlocksOldConfigUntilRepair() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val base = McpFixtureStore(config)
            var failSave = true
            val store = object : McpServerStore by base {
                override fun upsert(config: McpServerConfig): McpServerConfig {
                    if (failSave) throw IOException("synthetic save failure")
                    return base.upsert(config)
                }
            }
            val registry = McpSessionRegistry(store)
            try {
                val client = registry.client("settings")
                client.listTools(config)
                assertTrue(runCatching { registry.upsert(config.copy(name = "changed")) }.isFailure)
                assertEquals(config, store.get(config.id))
                assertFalse(client.isConnected(config))
                assertTrue(runCatching { client.listTools(config) }.isFailure)
                assertEquals(1, fixture.getCount.get())
                failSave = false
                val repaired = registry.upsert(config.copy(name = "repaired"))
                client.listTools(repaired)
                assertEquals(2, fixture.getCount.get())
            } finally { registry.close() }
        }
    }

    @Test fun oldGenerationFailureCannotInvalidateFreshSession() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val toolRequests = AtomicInteger()
        val initializations = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(initializations, ::toolResponse)
            server.start()
            val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                if (JSONObject(buffer.readUtf8()).optString("method") == "tools/list" && toolRequests.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); throw IOException("old generation failure")
                }
                chain.proceed(chain.request())
            }.build()
            val client = StreamableHttpMcpClient(okHttp)
            val config = McpServerConfig("http-generation", "Generation", server.url("/mcp").toString())
            try {
                val old = async(Dispatchers.IO) { runCatching { client.listTools(config) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                client.close()
                client.listTools(config)
                release.countDown()
                assertTrue(old.await().isFailure)
                assertTrue(client.isConnected(config))
                client.listTools(config)
                assertEquals(2, initializations.get())
            } finally { release.countDown(); client.close() }
        }
    }

    private fun dispatcher(initializations: AtomicInteger, response: (JSONObject) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val rpc = JSONObject(request.body.readUtf8())
            return when (rpc.optString("method")) {
                "initialize" -> rpcResponse(rpc, JSONObject().put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))).setHeader("Mcp-Session-Id", "session-${initializations.incrementAndGet()}")
                "notifications/initialized" -> MockResponse().setResponseCode(202)
                else -> response(rpc)
            }
        }
    }

    private fun toolResponse(rpc: JSONObject) = rpcResponse(rpc, JSONObject().put("tools", JSONArray().put(
        JSONObject().put("name", "fixture.echo").put("inputSchema", JSONObject().put("type", "object")))))
    private fun rpcResponse(rpc: JSONObject, result: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", rpc.get("id")).put("result", result).toString())
}
