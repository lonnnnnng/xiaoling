package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * long: 在真实 Android 设备内启动 loopback MCP Server，验证客户端的握手、会话、工具发现和工具调用链路。
 */
@RunWith(AndroidJUnit4::class)
class McpE2eInstrumentedTest {
    private lateinit var server: MockWebServer
    private val requestBodies = mutableListOf<String>()

    @Before
    fun setUp() {
        requestBodies.clear()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // long: MockWebServer 在 Android 上的 RecordedRequest body 只能可靠读取一次，先保存原文供断言复用。
                    val body = request.body.readUtf8()
                    requestBodies += body
                    val rpc = JSONObject(body)
                    val method = rpc.optString("method")
                    return when (method) {
                        "initialize" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject()
                                .put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                                .put("capabilities", JSONObject()
                                    .put("tools", JSONObject())
                                    .put("resources", JSONObject())
                                    .put("prompts", JSONObject()))
                                .put("serverInfo", JSONObject().put("name", "device-mock").put("version", "1.0")),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "notifications/initialized" -> MockResponse()
                            .setResponseCode(202)
                            .setHeader("Mcp-Session-Id", SESSION_ID)

                        "tools/list" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put("tools", JSONArray().put(
                                JSONObject()
                                    .put("name", TOOL_NAME)
                                    .put("description", "回显文本，用于真机 MCP E2E 验证")
                                    .put(
                                        "inputSchema",
                                        JSONObject()
                                            .put("type", "object")
                                            .put("required", JSONArray().put("text"))
                                            .put("additionalProperties", false)
                                            .put("properties", JSONObject().put("text", JSONObject().put("type", "string"))),
                                    )
                                    .put("annotations", JSONObject().put("readOnlyHint", true)),
                            )),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "tools/call" -> {
                            val params = rpc.getJSONObject("params")
                            assertEquals(TOOL_NAME, params.getString("name"))
                            val text = params.getJSONObject("arguments").getString("text")
                            jsonRpcResponse(
                                rpc.optString("id"),
                                JSONObject()
                                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "echo:$text")))
                                    .put("isError", false),
                            ).setHeader("Mcp-Session-Id", SESSION_ID)
                        }

                        "resources/list" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put("resources", JSONArray().put(JSONObject()
                                .put("uri", "memo://device")
                                .put("name", "设备测试资源")
                                .put("mimeType", "text/plain"))),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "resources/read" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put("contents", JSONArray().put(JSONObject()
                                .put("uri", "memo://device")
                                .put("mimeType", "text/plain")
                                .put("text", "device-resource-ok"))),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "prompts/list" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put("prompts", JSONArray().put(JSONObject()
                                .put("name", "device-summary")
                                .put("arguments", JSONArray().put(JSONObject()
                                    .put("name", "topic")
                                    .put("required", true))))),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "prompts/get" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put("messages", JSONArray().put(JSONObject()
                                .put("role", "user")
                                .put("content", JSONObject().put("type", "text").put("text", "device-prompt-ok")))),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        else -> jsonRpcError(rpc.optString("id"), -32601, "unknown method: $method")
                    }
                }
            }
        }
        server.start(0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun initializeListAndCallToolWorkOnRealDevice() = runBlocking {
        val client = StreamableHttpMcpClient()
        val config = McpServerConfig(
            id = "device-mcp-e2e",
            name = "Device MCP E2E",
            url = server.url("/mcp").toString(),
            bearerToken = TOKEN,
        )

        val tools = client.listTools(config)
        assertEquals(listOf(TOOL_NAME), tools.map(McpToolDescriptor::name))
        assertTrue(tools.single().readOnlyHint)

        val result = client.callTool(
            server = config,
            name = TOOL_NAME,
            arguments = JSONObject().put("text", "hello-device"),
            knownTools = tools,
        )
        assertTrue(result.contains("echo:hello-device"))

        val initialize = server.takeRequest(5, TimeUnit.SECONDS)!!
        val initialized = server.takeRequest(5, TimeUnit.SECONDS)!!
        val list = server.takeRequest(5, TimeUnit.SECONDS)!!
        val call = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("initialize", JSONObject(requestBodies[0]).getString("method"))
        assertEquals("notifications/initialized", JSONObject(requestBodies[1]).getString("method"))
        assertEquals("tools/list", JSONObject(requestBodies[2]).getString("method"))
        assertEquals("tools/call", JSONObject(requestBodies[3]).getString("method"))
        assertEquals("Bearer $TOKEN", initialize.getHeader("Authorization"))
        assertEquals(SESSION_ID, list.getHeader("Mcp-Session-Id"))
        assertEquals(SESSION_ID, call.getHeader("Mcp-Session-Id"))
        println("DEVICE_MCP_E2E initialize=true tools_list=true tools_call=true session=true bearer=true")
    }

    @Test
    fun resourcesAndPromptsWorkOnRealDeviceLoopback() = runBlocking {
        val client = StreamableHttpMcpClient()
        val config = McpServerConfig(
            id = "device-mcp-resources",
            name = "Device MCP Resources",
            url = server.url("/mcp").toString(),
        )

        val resources = client.listResources(config)
        assertEquals(listOf("memo://device"), resources.map(McpResourceDescriptor::uri))
        assertEquals("device-resource-ok", client.readResource(config, "memo://device").single().text)
        val prompts = client.listPrompts(config)
        assertEquals(listOf("device-summary"), prompts.map(McpPromptDescriptor::name))
        val result = client.getPrompt(config, "device-summary", mapOf("topic" to "device"))
        assertTrue(result.messages.single().contentJson.contains("device-prompt-ok"))

        val requests = (1..6).map { server.takeRequest(5, TimeUnit.SECONDS)!! }
        assertTrue(requests.drop(2).all { it.getHeader("Mcp-Session-Id") == SESSION_ID })
        assertEquals(
            listOf("initialize", "notifications/initialized", "resources/list", "resources/read", "prompts/list", "prompts/get"),
            requestBodies.map { JSONObject(it).getString("method") },
        )
        println("DEVICE_MCP_CONTENT resources_list=true resources_read=true prompts_list=true prompts_get=true session=true")
    }

    private fun jsonRpcResponse(id: String, result: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())

    private fun jsonRpcError(id: String, code: Int, message: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message)).toString())

    private companion object {
        const val SESSION_ID = "device-mcp-session"
        const val TOKEN = "device-e2e-token"
        const val TOOL_NAME = "device.echo"
    }
}
