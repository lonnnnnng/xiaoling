package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class McpResourcesPromptsTest {
    @Test
    fun resourceAndPromptRequestsRequireNegotiatedCapabilities() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = JSONObject(request.body.readUtf8())
                return when (payload.optString("method")) {
                    "initialize" -> rpc(payload, JSONObject()
                        .put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                        .put("capabilities", JSONObject().put("tools", JSONObject())))
                        .setHeader("Mcp-Session-Id", "session-tools-only")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        try {
            val client = StreamableHttpMcpClient()
            val config = McpServerConfig("tools-only", "Tools only", server.url("/mcp").toString())
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.listResources(config) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.listPrompts(config) }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resourcesAndPromptsSupportPaginationAndSessionReuse() = runTest {
        val server = MockWebServer()
        val methods = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = JSONObject(request.body.readUtf8())
                val method = payload.optString("method")
                methods += method
                return when (method) {
                    "initialize" -> rpc(payload, JSONObject()
                        .put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                        .put("capabilities", JSONObject()
                            .put("resources", JSONObject())
                            .put("prompts", JSONObject())))
                        .setHeader("Mcp-Session-Id", "session-resources")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "resources/list" -> {
                        if (payload.optJSONObject("params")?.optString("cursor") == "resource-page-2") {
                            rpc(payload, JSONObject().put("resources", JSONArray().put(resource("memo://two", "two"))))
                        } else {
                            rpc(payload, JSONObject()
                                .put("resources", JSONArray().put(resource("memo://one", "one")))
                                .put("nextCursor", "resource-page-2"))
                        }
                    }
                    "resources/read" -> rpc(payload, JSONObject().put(
                        "contents",
                        JSONArray().put(JSONObject()
                            .put("uri", "memo://one")
                            .put("mimeType", "text/plain")
                            .put("text", "hello")),
                    ))
                    "prompts/list" -> {
                        if (payload.optJSONObject("params")?.optString("cursor") == "prompt-page-2") {
                            rpc(payload, JSONObject().put("prompts", JSONArray().put(prompt("follow-up"))))
                        } else {
                            rpc(payload, JSONObject()
                                .put("prompts", JSONArray().put(prompt("summarize")))
                                .put("nextCursor", "prompt-page-2"))
                        }
                    }
                    "prompts/get" -> rpc(payload, JSONObject()
                        .put("description", "总结模板")
                        .put("messages", JSONArray().put(JSONObject()
                            .put("role", "user")
                            .put("content", JSONObject().put("type", "text").put("text", "请总结：${payload.getJSONObject("params").getJSONObject("arguments").getString("topic")}")))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val client = StreamableHttpMcpClient()
            val config = McpServerConfig("local-mcp", "Local", server.url("/mcp").toString())

            val resources = client.listResources(config)
            assertEquals(listOf("memo://one", "memo://two"), resources.map { it.uri })
            val contents = client.readResource(config, "memo://one")
            assertEquals("hello", contents.single().text)
            assertEquals(null, contents.single().blobBase64)

            val prompts = client.listPrompts(config)
            assertEquals(listOf("summarize", "follow-up"), prompts.map { it.name })
            val prompt = client.getPrompt(config, "summarize", mapOf("topic" to "项目"))
            assertEquals("总结模板", prompt.description)
            assertEquals("user", prompt.messages.single().role)
            assertTrue(prompt.messages.single().contentJson.contains("项目"))

            assertEquals(1, methods.count { it == "initialize" })
            assertTrue(methods.count { it == "resources/list" } == 2)
            assertTrue(methods.count { it == "prompts/list" } == 2)
            val requests = (1..8).mapNotNull { server.takeRequest(2, TimeUnit.SECONDS) }
            assertTrue(requests.drop(2).any { it.getHeader("Mcp-Session-Id") == "session-resources" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resourceAndPromptResponsesFailClosedOnAmbiguousContentAndUnknownRole() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val payload = JSONObject(request.body.readUtf8())
                return when (payload.optString("method")) {
                    "initialize" -> rpc(payload, JSONObject()
                        .put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                        .put("capabilities", JSONObject()
                            .put("resources", JSONObject())
                            .put("prompts", JSONObject())))
                        .setHeader("Mcp-Session-Id", "session-invalid")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "resources/read" -> rpc(payload, JSONObject().put(
                        "contents", JSONArray().put(JSONObject()
                            .put("uri", "memo://bad")
                            .put("text", "text")
                            .put("blob", "YmFk")),
                    ))
                    "prompts/get" -> rpc(payload, JSONObject().put(
                        "messages", JSONArray().put(JSONObject()
                            .put("role", "system")
                            .put("content", JSONObject().put("type", "text"))),
                    ))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val client = StreamableHttpMcpClient()
            val config = McpServerConfig("invalid-mcp", "Invalid", server.url("/mcp").toString())
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.readResource(config, "memo://bad") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { client.getPrompt(config, "bad") }
            }
        } finally {
            server.shutdown()
        }
    }

    private fun rpc(request: JSONObject, result: JSONObject) = MockResponse()
        .setBody(JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", request.getString("id"))
            .put("result", result)
            .toString())

    private fun resource(uri: String, name: String) = JSONObject()
        .put("uri", uri)
        .put("name", name)
        .put("description", "测试资源")
        .put("mimeType", "text/plain")

    private fun prompt(name: String) = JSONObject()
        .put("name", name)
        .put("description", "测试 Prompt")
        .put("arguments", JSONArray().put(JSONObject()
            .put("name", "topic")
            .put("description", "主题")
            .put("required", true)))
}
