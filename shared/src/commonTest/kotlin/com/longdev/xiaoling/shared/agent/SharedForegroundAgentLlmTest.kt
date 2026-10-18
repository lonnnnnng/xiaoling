package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** long: 注入传输只验证规划/历史/取消协议；真实Provider和UIKit证据由Simulator宿主单独提供。 */
class SharedForegroundAgentLlmTest {
    private val key = "fixture-provider-only-credential"
    private val config = SharedForegroundModelConfig("https://provider.example.com/v1/chat/completions", "fixture-model", key)
    private val tools = listOf(SharedToolDefinition("workspace.read_file", "读相对文件", false, false))
    private class Client : SharedHttpClient {
        val requests = mutableListOf<SharedHttpRequest>()
        var reply: suspend () -> SharedHttpResponse = { envelope("{\"action\":\"complete\"}") }
        override suspend fun execute(request: SharedHttpRequest): SharedHttpResponse { requests += request; return reply() }
        override fun close() = Unit
    }
    private fun llm(client: Client, history: SharedForegroundRunHistory = SharedForegroundRunHistory(), before: () -> Unit = {}) =
        SharedForegroundAgentLlm(config, client, history, { "local-call-id" }, before)
    companion object {
        private fun envelope(content: String, finish: String = "stop"): SharedHttpResponse = SharedHttpResponse(200, emptyMap(), buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject { put("finish_reason", finish); put("message", buildJsonObject { put("content", content) }) }) })
        }.toString().encodeToByteArray())
    }
    @Test fun configurationIsHostOnlyAndDiagnosticsHideCredential() {
        assertFalse(config.toString().contains(key)); assertFalse(config.toString().contains("provider.example.com"))
        assertFailsWith<IllegalArgumentException> { SharedForegroundModelConfig("http://example.com/", "model", key) }
        assertFailsWith<IllegalArgumentException> { SharedForegroundModelConfig("https://example.com/?token=x", "model", key) }
        assertFailsWith<IllegalArgumentException> { SharedForegroundModelConfig(config.endpoint, "model", "key\r\nInjected: yes") }
    }
    @Test fun plannerUsesActualResultsAndGeneratesLocalCallIdentity() = runTest {
        val client = Client(); val history = SharedForegroundRunHistory()
        val first = SharedToolCall("first", "workspace.read_file", mapOf("path" to "验收.txt"))
        history.record(first, SharedToolExecutionResult(true, "actual-nonce-123"))
        client.reply = { envelope("{\"action\":\"tool\",\"tool\":\"workspace.read_file\",\"arguments\":{\"path\":\"验收.txt\",\"max_chars\":100}}") }
        val next = llm(client, history).proposeNextAction("核对标记", tools, listOf(first)) as SharedAgentPlanDecision.CallTool
        assertEquals("local-call-id", next.toolCall.id); assertEquals("100", next.toolCall.arguments["max_chars"])
        val request = client.requests.single()
        assertTrue(request.body.decodeToString().contains("actual-nonce-123")); assertFalse(request.body.decodeToString().contains(key))
        assertEquals("Bearer $key", request.headers["Authorization"]); assertEquals(SharedHttpMethod.POST, request.method)
    }
    @Test fun historyIsCopiedAndCannotForgeCommitOrDuplicateExecution() {
        val history = SharedForegroundRunHistory(); val args = mutableMapOf("path" to "original")
        val call = SharedToolCall("id", "workspace.read_file", args)
        history.record(call, SharedToolExecutionResult(true, "facts")); args["path"] = "changed"
        val frozenCall = call.copy(arguments = mapOf("path" to "original"))
        assertEquals("original", history.matched(listOf(frozenCall)).single().toolCall.arguments["path"])
        assertFailsWith<IllegalArgumentException> { history.matched(listOf(call)) }
        assertFailsWith<IllegalArgumentException> { history.record(frozenCall, SharedToolExecutionResult(true, "duplicate")) }
        assertFailsWith<IllegalArgumentException> { SharedForegroundRunHistory().record(call, SharedToolExecutionResult(true, "forged", verified = true)) }
        val result = SharedToolExecutionResultCodec.decode(history.frames().single(), "id").result
        assertNull(result.verified); assertNull(result.executionReceipt)
    }
    @Test fun missingActualHistoryStopsBeforeNetwork() = runTest {
        val client = Client()
        assertFailsWith<IllegalArgumentException> { llm(client).proposeNextAction("goal", tools, listOf(SharedToolCall("missing", "workspace.read_file", emptyMap()))) }
        assertTrue(client.requests.isEmpty())
    }
    @Test fun emptyHistoryCannotBeDeclaredComplete() = runTest {
        val client = Client()
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).proposeNextAction("读取文件", tools, emptyList()) }.code)
        val history = SharedForegroundRunHistory(); val call = SharedToolCall("read", "workspace.read_file", mapOf("path" to "a"))
        history.record(call, SharedToolExecutionResult(true, "actual-result"))
        assertEquals(SharedAgentPlanDecision.Complete, llm(client, history).proposeNextAction("读取文件", tools, listOf(call)))
    }
    @Test fun malformedExtraDuplicateAndNonintegerPlansAreRejected() = runTest {
        for (plan in listOf("{\"action\":\"complete\",\"extra\":true}", "{\"action\":\"complete\",\"action\":\"complete\"}",
            "```json\n{\"action\":\"complete\"}\n```", "{\"action\":\"tool\",\"tool\":\"shell.exec\",\"arguments\":{}}",
            "{\"action\":\"tool\",\"tool\":\"workspace.read_file\",\"arguments\":{\"max_chars\":1.0}}",
            "{\"action\":\"tool\",\"tool\":\"workspace.read_file\",\"arguments\":{\"path\":[]}}")) {
            val client = Client().apply { reply = { envelope(plan) } }
            assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).proposeNextAction("goal", tools, emptyList()) }.code)
        }
    }
    @Test fun truncatedResponseAndAuthenticationReflectionAreFixedFailures() = runTest {
        val client = Client().apply { reply = { envelope("private-response", "length") } }
        val error = assertFailsWith<SharedForegroundModelException> { llm(client).proposeNextAction("goal", tools, emptyList()) }
        assertNull(error.cause); assertFalse(error.message!!.contains("private-response"))
        client.reply = { envelope("{\"summary\":\"$key\"}") }
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).summarize("goal", emptyList()) }.code)
    }
    @Test fun summaryReceivesBodyEvidenceAndHasNoBusinessVerification() = runTest {
        val client = Client().apply { reply = { envelope("{\"summary\":\"真实标记是actual-nonce\"}") } }
        val history = SharedForegroundRunHistory(); val call = SharedToolCall("first", "workspace.read_file", mapOf("path" to "a"))
        history.record(call, SharedToolExecutionResult(true, "actual-nonce"))
        assertEquals("真实标记是actual-nonce", llm(client, history).summarize("goal", listOf(call)))
        assertTrue(client.requests.single().body.decodeToString().contains("actual-nonce"))
    }
    @Test fun unicodeCredentialReflectionIsRejectedAtBothJsonLayers() = runTest {
        // long: 使用含JSON转义字符的夹具key，避免只覆盖原文能直接匹配的常见认证值。
        val credential = "fixture-\"\\-credential"
        val cfg = SharedForegroundModelConfig(config.endpoint, config.model, credential)
        val encoded = credential.map { "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
        val client = Client()
        val adapter = SharedForegroundAgentLlm(cfg, client, SharedForegroundRunHistory(), { "id" }, {})
        client.reply = { envelope("{\"summary\":\"$encoded\"}") }
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { adapter.summarize("goal", emptyList()) }.code)
        val inner = buildJsonObject { put("summary", credential) }.toString()
        val outerContent = inner.map { "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
        client.reply = { SharedHttpResponse(200, emptyMap(), ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + outerContent + "\"}}]}").encodeToByteArray()) }
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { adapter.summarize("goal", emptyList()) }.code)
    }
    @Test fun credentialInGoalOrActualHistoryStopsBeforeNetwork() = runTest {
        val client = Client()
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).proposeNextAction(key, tools, emptyList()) }.code)
        val history = SharedForegroundRunHistory(); val call = SharedToolCall("id", "workspace.read_file", emptyMap())
        history.record(call, SharedToolExecutionResult(true, key))
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client, history).summarize("goal", listOf(call)) }.code)
        assertTrue(client.requests.isEmpty())
    }
    @Test fun invalidEscapesInInnerAndOuterJsonHaveFixedFailureCode() = runTest {
        val client = Client().apply { reply = { envelope("{\"summary\":\"\\q\"}") } }
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).summarize("goal", emptyList()) }.code)
        client.reply = { SharedHttpResponse(200, emptyMap(), "{\"choices\":\"\\q\"}".encodeToByteArray()) }
        assertEquals("RESPONSE_INVALID", assertFailsWith<SharedForegroundModelException> { llm(client).summarize("goal", emptyList()) }.code)
    }
    @Test fun oversizedHistoryIsNotSilentlyTruncatedOrSent() = runTest {
        val client = Client(); val history = SharedForegroundRunHistory(); val call = SharedToolCall("id", "workspace.read_file", emptyMap())
        history.record(call, SharedToolExecutionResult(true, "x".repeat(70_000)))
        assertEquals("INPUT_LIMIT", assertFailsWith<SharedForegroundModelException> { llm(client, history).proposeNextAction("goal", tools, listOf(call)) }.code)
        assertTrue(client.requests.isEmpty())
    }
    @Test fun beforeRequestCancellationHasZeroTransportAccess() = runTest {
        val client = Client()
        val call = async { val context = currentCoroutineContext(); llm(client, before = { context.cancel() }).proposeNextAction("goal", tools, emptyList()) }
        assertFailsWith<CancellationException> { call.await() }; assertTrue(client.requests.isEmpty())
    }
    @Test fun cancellationWhileWaitingForProviderIsPropagated() = runTest {
        val entered = CompletableDeferred<Unit>(); val client = Client().apply { reply = { entered.complete(Unit); awaitCancellation() } }
        val call = async { llm(client).proposeNextAction("goal", tools, emptyList()) }; entered.await(); call.cancel()
        assertFailsWith<CancellationException> { call.await() }
    }
    @Test fun httpFailureNeverEchoesProviderBody() = runTest {
        val client = Client().apply { reply = { SharedHttpResponse(401, emptyMap(), "private-error-body".encodeToByteArray()) } }
        val error = assertFailsWith<SharedForegroundModelException> { llm(client).proposeNextAction("goal", tools, emptyList()) }
        assertEquals("HTTP_STATUS_401", error.code); assertNull(error.cause); assertFalse(error.message!!.contains("private-error"))
    }
}
