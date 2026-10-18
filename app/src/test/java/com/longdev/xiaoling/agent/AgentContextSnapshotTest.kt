package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextSnapshotTest {
    private fun input() = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
        listOf(AgentContextMessage("system", "系统"), AgentContextMessage("user", "中文😀")), emptyList())

    @Test fun codecPreservesUtf8AndRejectsPartialUnknownOrAmbiguousInputs() {
        val value = input()
        val encoded = AgentContextSnapshotCodec.encode(value)
        assertEquals(value, AgentContextSnapshotCodec.decode(encoded))
        assertTrue(AgentContextSnapshotCodec.utf8Size(encoded) > encoded.length)
        for (bad in listOf(JSONObject(encoded).put("schemaVersion", 2), JSONObject(encoded).put("extra", true),
            JSONObject(encoded).put("imageCount", "1"))) {
            assertTrue(runCatching { AgentContextSnapshotCodec.decode(bad.toString()) }.isFailure)
        }
        assertTrue(runCatching { AgentContextSnapshotCodec.encode(value.copy(verifiedToolCallIds = listOf("same", "same"))) }.isFailure)
        assertTrue(runCatching { AgentContextSnapshotCodec.encode(value.copy(messages = value.messages.take(1))) }.isFailure)
        assertTrue(runCatching { AgentContextSnapshotCodec.encode(value.copy(imageCount = 1, documentCount = 1)) }.isFailure)
    }

    @Test fun sizeLimitCountsUtf8AndNeverSilentlyCutsText() {
        val tooLarge = input().copy(messages = listOf(AgentContextMessage("system", "s"),
            AgentContextMessage("user", "中".repeat(AgentContextSnapshotCodec.MAX_REQUEST_BYTES / 2))))
        assertTrue(runCatching { AgentContextSnapshotCodec.encode(tooLarge) }.isFailure)
        assertTrue(runCatching { AgentContextSnapshotCodec.encode(input().copy(messages = listOf(
            AgentContextMessage("system", "s"), AgentContextMessage("user", "\uD800")))) }.isFailure)
    }

    @Test fun productionPlannerAndSummaryFreezeExactTextBeforeSendingAndDoNotReadChangedArguments() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) { server.enqueue(response("{\"action\":\"complete\"}")) }
            val args = linkedMapOf("value" to "原参数")
            val execution = AgentToolExecution(ToolCall(id = "verified-call", name = "fixture.read", arguments = args, risk = ToolRisk.SAFE),
                ToolExecutionResult(true, "原结果", verified = true))
            val llm = model(server)
            var plan: AgentContextInput? = null
            llm.proposeNextActionWithContext("动态目标", listOf(ToolDefinition("fixture.read", "fixture", ToolRisk.SAFE)), listOf(execution)) {
                plan = it
                args["value"] = "等待期间的新参数"
            }
            val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(listOf("verified-call"), plan!!.verifiedToolCallIds)
            plan!!.messages.forEachIndexed { index, message -> assertEquals(message.content, wire.getJSONObject(index).getString("content")) }
            assertTrue(plan!!.messages.last().content.contains("原参数"))
            assertFalse(plan!!.messages.last().content.contains("等待期间的新参数"))
            var summary: AgentContextInput? = null
            llm.summarizeWithContext("动态目标", listOf(execution)) { summary = it }
            val summaryWire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(AgentLlmPhase.SUMMARIZE, summary!!.phase)
            summary!!.messages.forEachIndexed { index, message -> assertEquals(message.content, summaryWire.getJSONObject(index).getString("content")) }
            assertFalse(summary!!.messages.last().content.contains("原结果"))
        } finally { server.shutdown() }
    }

    @Test fun deviceInputProjectionOmitsAllTextIncludingNestedJsonWithoutChangingNetworkInput() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) { server.enqueue(response("{\"action\":\"complete\"}")) }
            val text = "原文\"\\\nfixture-sensitive-input"
            val execution = AgentToolExecution(ToolCall(id = "input-call", name = "device.type_text", risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("snapshot_id" to "snapshot", "ref" to "node", "text" to text)), ToolExecutionResult(true, "回读$text", true))
            val nested = JSONObject().put("text", text).toString()
            val another = AgentToolExecution(ToolCall(id = "read-call", name = "fixture.read", arguments = emptyMap(), risk = ToolRisk.SAFE),
                ToolExecutionResult(true, JSONObject().put("nested", nested).toString(), true))
            val llm = model(server)
            var snapshot: AgentContextInput? = null
            llm.proposeNextActionWithContext("输入$text", emptyList(), listOf(execution, another)) { snapshot = it }
            val escaped = JSONObject.quote(text).removeSurrounding("\"")
            val encoded = AgentContextSnapshotCodec.encode(snapshot!!)
            assertEquals(AgentContextProjection.DEVICE_INPUT_REDACTED, snapshot!!.projection)
            assertFalse(encoded.contains(text))
            assertFalse(snapshot!!.messages.last().content.contains(escaped))
            assertFalse(encoded.contains("fixture-sensitive-input"))
            assertTrue(snapshot!!.messages.all { it.content == "[含设备输入历史，正文未持久化]" })
            assertEquals(listOf("input-call", "read-call"), snapshot!!.verifiedToolCallIds)
            val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(wire.contains(escaped))
            val history = org.json.JSONArray(wire.substringAfter("已执行并验证的工具历史：").trim())
            assertEquals(text, JSONObject(JSONObject(history.getJSONObject(1).getString("content")).getString("nested")).getString("text"))
            llm.summarizeWithContext("goal", listOf(execution, another)) { snapshot = it }
            assertTrue(snapshot!!.messages.all { it.content == "[含设备输入历史，正文未持久化]" })
            server.takeRequest()
            Unit
        } finally { server.shutdown() }
    }

    @Test fun cancellationWhileCapturingPreventsNetworkRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                model(server).proposeNextActionWithContext("goal", emptyList(), emptyList()) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun browserDraftDoesNotReturnRawArgumentsToPlannerOrPersistentContext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("{\"action\":\"complete\"}")); server.start()
            val text = "browser-private-draft"
            val call = ToolCall(id = "draft-call", name = "browser.type", risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("session_id" to "s", "snapshot_id" to "p", "ref" to "r", "text" to text))
            var snapshot: AgentContextInput? = null
            model(server).proposeNextActionWithContext("继续浏览器任务", emptyList(), listOf(AgentToolExecution(call, ToolExecutionResult(true, "本机草稿更新", true)))) { snapshot = it }
            val wire = server.takeRequest().body.readUtf8()
            assertFalse(wire.contains(text)); assertTrue(wire.contains(BrowserTypeTextAuditPolicy.fingerprint(text)))
            assertFalse(AgentContextSnapshotCodec.encode(snapshot!!).contains(text))
            assertEquals(AgentContextProjection.DEVICE_INPUT_REDACTED, snapshot!!.projection)
            Unit
        }
    }

    @Test fun deviceInputArgumentsReturnOnlyFingerprintToNextPlannerRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("{\"action\":\"complete\"}")); server.start()
            val text = "device-private-input"
            val call = ToolCall(id = "device-call", name = "device.type_text", risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("snapshot_id" to "s", "ref" to "r", "text" to text))
            var snapshot: AgentContextInput? = null
            model(server).proposeNextActionWithContext("继续任务", emptyList(),
                listOf(AgentToolExecution(call, ToolExecutionResult(true, "输入已验证", true)))) { snapshot = it }
            val wire = server.takeRequest().body.readUtf8()
            assertFalse(wire.contains(text))
            assertTrue(wire.contains(DeviceTypeTextAuditPolicy.fingerprint(text)))
            assertTrue(wire.contains("text_length"))
            assertFalse(AgentContextSnapshotCodec.encode(snapshot!!).contains(text))
            Unit
        }
    }

    @Test fun captureFailurePreventsBothPlannerAndSummaryNetworkRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val llm = model(server)
            assertTrue(runCatching { llm.proposeNextActionWithContext("goal", emptyList(), emptyList()) { error("fixture") } }.isFailure)
            assertTrue(runCatching { llm.summarizeWithContext("goal", emptyList()) { error("fixture") } }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun model(server: MockWebServer) = OpenAiAgentLlm(OpenAiCompatibleClient(),
        ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture", httpDebugLoggingEnabled = false), "只选择总结样式")
    private fun response(text: String) = MockResponse().setResponseCode(200).setBody(JSONObject()
        .put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
}
