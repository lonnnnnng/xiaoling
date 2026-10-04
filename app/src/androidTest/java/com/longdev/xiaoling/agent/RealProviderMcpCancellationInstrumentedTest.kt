package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * long: 真实 Provider 只负责规划 MCP 工具，loopback Server 负责制造可控的半开调用；
 * 取消必须同时中断 OkHttp、把 Room Run/Step 收敛为 CANCELLED，并拒绝迟到 tool.result。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderMcpCancellationInstrumentedTest {
    private lateinit var server: MockWebServer
    private lateinit var mcpStore: AndroidMcpServerStore
    private val callStarted = CountDownLatch(1)
    private val callCount = AtomicInteger(0)
    private val observedMethods = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val rpc = JSONObject(request.body.readUtf8())
                    val method = rpc.optString("method")
                    synchronized(observedMethods) { observedMethods += method }
                    return when (method) {
                        "initialize" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject()
                                .put("protocolVersion", McpPolicy.PROTOCOL_VERSION)
                                .put("capabilities", JSONObject().put("tools", JSONObject()))
                                .put("serverInfo", JSONObject().put("name", "stage-real-provider-mcp").put("version", "1")),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "notifications/initialized" -> MockResponse()
                            .setResponseCode(202)
                            .setHeader("Mcp-Session-Id", SESSION_ID)

                        "tools/list" -> jsonRpcResponse(
                            rpc.optString("id"),
                            JSONObject().put(
                                "tools",
                                JSONArray().put(
                                    JSONObject()
                                        .put("name", BLOCKING_TOOL_NAME)
                                        .put("description", "等待取消验收的只读 MCP 工具")
                                        .put(
                                            "inputSchema",
                                            JSONObject()
                                                .put("type", "object")
                                                .put("additionalProperties", false)
                                                .put("properties", JSONObject().put("text", JSONObject().put("type", "string"))),
                                        )
                                        .put("annotations", JSONObject().put("readOnlyHint", true)),
                                ),
                            ),
                        ).setHeader("Mcp-Session-Id", SESSION_ID)

                        "tools/call" -> {
                            callCount.incrementAndGet()
                            callStarted.countDown()
                            // long: 让真实 Agent 已进入远程工具执行后保持半开，取消才能覆盖网络层和 Runtime 层。
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }

                        else -> jsonRpcError(rpc.optString("id"), -32601, "unknown method: $method")
                    }
                }
            }
        }
        server.start(0)
        mcpStore = AndroidMcpServerStore(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        runCatching { mcpStore.delete(MCP_SERVER_ID) }
        runCatching { server.shutdown() }
    }

    @Test
    fun realProviderMcpCallCancellationSettlesRunAndDropsLateToolResult() = runBlocking {
        requireRealProviderRun()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = ProviderRepository(targetContext).load()
        val provider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val model = provider?.model?.trim().orEmpty()
        assumeTrue("Redmi 当前 Provider Base URL 不可用", baseUrl.isNotBlank())
        assumeTrue("Redmi 当前 Provider API Key 不可用", apiKey.isNotBlank())
        assumeTrue("Redmi 当前 Provider 模型不可用", model.isNotBlank())

        val serverConfig = McpServerConfig(
            id = MCP_SERVER_ID,
            name = "第 283 阶段 MCP 取消验收",
            url = server.url("/mcp").toString(),
            enabled = true,
        )
        mcpStore.upsert(serverConfig)

        val providerId = "stage283-real-provider"
        val profile = AgentProfileSnapshot(
            id = "stage283-mcp-cancel-agent",
            name = "第 283 阶段 MCP 取消 Agent",
            avatar = "M",
            providerId = providerId,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只按用户目标依次使用 mcp.list_tools 和 mcp.call；不得调用其他工具，不得泄露 Provider 或凭据。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("mcp.list_tools", "mcp.call"),
            allowedSkillIds = listOf("mcp-tools"),
            memoryEnabled = false,
        )
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            apiMode = ApiMode.RESPONSES,
            streamingEnabled = false,
            reasoningSummaryEnabled = false,
            userAgent = ProviderRequestConfig.DEFAULT_USER_AGENT,
            maxTokens = 4_000,
        )
        val runId = AtomicReference<String?>(null)
        val useCase = AgentRunUseCase(targetContext, OpenAiCompatibleClient())
        val job: Job = launch(Dispatchers.IO) {
            useCase.run(
                conversationId = "conversation-stage283-${UUID.randomUUID()}",
                userMessageId = "message-stage283-${UUID.randomUUID()}",
                goal = "请使用 server_id=$MCP_SERVER_ID，先调用 mcp.list_tools，然后调用 mcp.call 调用工具 $BLOCKING_TOOL_NAME，参数为 {\"text\":\"stage283\"}。测试会在 mcp.call 请求发出后取消本次任务；不要调用其他工具。",
                config = config,
                summarySystemPrompt = "只返回简短中性 JSON，不要复述敏感配置。",
                agentProfile = profile,
                memoryRecallEnabled = false,
                approvalGate = object : ApprovalGate {
                    override suspend fun requestApproval(
                        runId: String,
                        toolCall: ToolCall,
                        definition: ToolDefinition,
                    ): ApprovalDecision = ApprovalDecision(true, "第283阶段真机取消验收")
                },
                onSnapshot = { snapshot -> runId.compareAndSet(null, snapshot.run.id) },
            )
        }

        val cancelReason = "第283阶段验收取消"
        try {
            withTimeout(120_000) {
                while (runId.get() == null) delay(50)
                check(callStarted.await(120, TimeUnit.SECONDS)) { "真实 Provider 未进入 MCP tools/call" }
            }
            val settledRunId = requireNotNull(runId.get())
            // long: 与真实 UI 的 stopGenerating 保持同一顺序，先把取消意图落到 Room，再中断模型/网络协程。
            assertTrue(RoomAgentRunRepository(targetContext).requestCancel(settledRunId, cancelReason))
            job.cancel(CancellationException(cancelReason))
            withTimeout(15_000) { job.join() }
            // long: 给 OkHttp 回调一个短窗口，确认半开连接的迟到响应不会再次写入 Run 账本。
            delay(500)

            val detail = RoomAgentRunRepository(targetContext).runDetail(settledRunId)
            assertNotNull(detail)
            val snapshot = requireNotNull(detail).snapshot
            assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
            assertTrue(snapshot.run.cancelRequestedAt != null)
            assertEquals(cancelReason, snapshot.run.cancelRequestedReason)
            assertTrue(snapshot.steps.none { it.status == AgentStepStatus.PENDING || it.status == AgentStepStatus.RUNNING })
            assertTrue(snapshot.events.any { it.type == "run.cancelled" })
            assertFalse(snapshot.events.any { it.type == "run.completed" || it.type == "run.failed" })
            assertFalse(snapshot.events.any { event -> event.type == "tool.result" && event.message.contains(BLOCKING_TOOL_NAME) })
            assertEquals(1, callCount.get())
            synchronized(observedMethods) {
                assertTrue(observedMethods.contains("tools/list"))
                assertTrue(observedMethods.contains("tools/call"))
            }
            println(
                "STAGE283_REAL_MCP_CANCEL runId=$settledRunId status=${snapshot.run.status} " +
                    "mcpCallCount=${callCount.get()} activeSteps=0 lateToolResult=false terminalOverride=false",
            )
        } finally {
            // long: Provider 没按预期规划时也要主动收口测试协程，避免半开 HTTP 请求污染后续 instrumentation。
            runId.get()?.let { id ->
                runCatching { RoomAgentRunRepository(targetContext).requestCancel(id, "第283阶段测试清理") }
            }
            if (job.isActive) job.cancel(CancellationException("第283阶段测试清理"))
            withTimeoutOrNull(15_000) { job.join() }
        }
    }

    private fun requireRealProviderRun() {
        assumeTrue(
            "第283阶段真实 Provider MCP 取消验收只在显式 stage283RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第283阶段 Android 验收只允许 Redmi begonia", "begonia", android.os.Build.DEVICE)
    }

    private fun jsonRpcResponse(id: String, result: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())

    private fun jsonRpcError(id: String, code: Int, message: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message))
                .toString(),
        )

    private companion object {
        const val ARG_REAL_RUN = "stage283RealRun"
        const val MCP_SERVER_ID = "stage283-mcp-cancel"
        const val SESSION_ID = "stage283-mcp-session"
        const val BLOCKING_TOOL_NAME = "blocking.wait"
    }
}
