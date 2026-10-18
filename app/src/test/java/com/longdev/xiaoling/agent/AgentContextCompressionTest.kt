package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompressionTest {
    private val old = "原文开头😀" + "中😀".repeat(20_000) + "中间完整事实" + "尾".repeat(2_000)
    private fun fixture(content: String = old, recent: String = "最近完整事实"): Pair<AgentContextInput, Int> {
        val history = JSONArray().apply {
            listOf(content, recent).forEachIndexed { i, text -> put(JSONObject().put("step", i + 1)
                .put("tool", "fixture.read").put("arguments", JSONObject().put("value", "$i"))
                .put("success", true).put("content", text).put("knowledge_references", JSONArray())) }
        }.toString()
        val prefix = "目标内含同样历史：$history\n可用工具和 Skill 保持\n已执行并验证的工具历史：\n"
        return AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS", listOf(
            AgentContextMessage("system", "系统边界"), AgentContextMessage("user", prefix + history)), listOf("old", "recent")) to prefix.length
    }

    @Test fun literalProjectionKeepsPrefixRecentArgumentsIdsAndUnicodeWithoutInventingFacts() {
        val (source, start) = fixture()
        val candidate = requireNotNull(AgentContextCompressionPolicy.prepare(source, start))
        assertEquals(source.messages.first(), candidate.input.messages.first())
        assertEquals(source.messages.last().content.take(start), candidate.input.messages.last().content.take(start))
        val rows = JSONObject(candidate.input.messages.last().content.substring(start)).getJSONArray("executions")
        val older = rows.getJSONObject(0)
        assertEquals("old", older.getString("tool_call_id"))
        assertEquals("0", older.getJSONObject("arguments").getString("value"))
        assertFalse(older.has("content"))
        assertTrue(old.startsWith(older.getString("content_excerpt_head")))
        assertTrue(old.endsWith(older.getString("content_excerpt_tail")))
        assertTrue(AgentContextSnapshotCodec.utf8Size(older.getString("content_excerpt_head")) <= 1_024)
        assertEquals("最近完整事实", rows.getJSONObject(1).getString("content"))
        assertTrue(older.getInt("omitted_utf8_bytes") > 0)
        AgentContextCompressionPolicy.validate(candidate)
        assertEquals(source, AgentContextSnapshotCodec.decode(AgentContextSnapshotCodec.encode(source)))
    }

    @Test fun smallRedactedAttachedSummaryOrNoSavingInputKeepsOriginalPath() {
        val (source, start) = fixture("短结果")
        assertNull(AgentContextCompressionPolicy.prepare(source, start))
        val (large, largeStart) = fixture()
        assertNull(AgentContextCompressionPolicy.prepare(large.copy(projection = AgentContextProjection.DEVICE_INPUT_REDACTED), largeStart))
        assertNull(AgentContextCompressionPolicy.prepare(large.copy(imageCount = 1, apiMode = "RESPONSES"), largeStart))
        assertNull(AgentContextCompressionPolicy.prepare(large.copy(documentCount = 1, apiMode = "RESPONSES"), largeStart))
        assertNull(AgentContextCompressionPolicy.prepare(large.copy(phase = AgentLlmPhase.SUMMARIZE), largeStart))
        assertNull(AgentContextCompressionPolicy.prepare(large.copy(verifiedToolCallIds = listOf("one")), largeStart))
        val (littleSaving, littleSavingStart) = fixture("x".repeat(15_000), "y".repeat(300_000))
        assertNull(AgentContextCompressionPolicy.prepare(littleSaving, littleSavingStart))
    }

    @Test fun invalidSourceOversizeAndChangedDerivedInputAreRejected() {
        val (source, start) = fixture()
        assertTrue(runCatching { AgentContextCompressionPolicy.prepare(source, start - 1) }.isFailure)
        val candidate = requireNotNull(AgentContextCompressionPolicy.prepare(source, start))
        val badHistory = JSONArray(source.messages.last().content.substring(start))
        for ((field, value) in listOf<Pair<String, Any>>("tool" to "device.type_text", "success" to false, "step" to 2)) {
            val rows = JSONArray(badHistory.toString())
            rows.getJSONObject(0).put(field, value)
            val bad = source.copy(messages = listOf(source.messages.first(), AgentContextMessage("user", source.messages.last().content.take(start) + rows)))
            assertTrue(runCatching { AgentContextCompressionPolicy.prepare(bad, start) }.isFailure)
        }
        assertTrue(runCatching { AgentContextCompressionPolicy.validate(candidate.copy(input = candidate.input.copy(model = "changed"))) }.isFailure)
        val (huge, hugeStart) = fixture("x".repeat(1_048_576))
        assertTrue(runCatching { AgentContextCompressionPolicy.prepare(huge, hugeStart) }.isFailure)
        assertNotEquals(AgentContextSnapshotCodec.recordHash("r", "s", 1, "p"), AgentContextSnapshotCodec.recordHash("r", "s", 1, "p", 1))
    }

    @Test fun productionAdapterSendsDerivedTextOnlyAfterCapturingFullSourceAndCaptureFailureSendsNothing() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"action\":\"complete\"}"}}]}"""))
            val llm = OpenAiAgentLlm(OpenAiCompatibleClient(), ProviderRequestConfig(server.url("/v1").toString(),
                "fixture-key", "fixture", httpDebugLoggingEnabled = false), "样式", selectedSkills = listOf(
                AgentSkillDefinition("first", name = "读取", description = "夹具", instructions = "第一行\n第二行", toolNames = emptySet(), keywords = emptySet()),
                AgentSkillDefinition("second", name = "总结", description = "夹具", instructions = "保留原文", toolNames = emptySet(), keywords = emptySet()),
            ))
            val tools = listOf(ToolDefinition("fixture.read", "读取", ToolRisk.SAFE), ToolDefinition("fixture.list", "目录", ToolRisk.SAFE))
            val executions = listOf(old, "最近完整事实").mapIndexed { i, text -> AgentToolExecution(
                ToolCall("call-$i", "fixture.read", mapOf("value" to "$i"), ToolRisk.SAFE), ToolExecutionResult(true, text)) }
            var captured: AgentContextCompressionCandidate? = null
            llm.proposeNextActionWithCompression("保留目标\n不猜测缺失事实", tools, executions) { input, candidate ->
                captured = requireNotNull(candidate)
                assertEquals(input, candidate.input)
                assertTrue(candidate.sourceInput.messages.last().content.contains("中间完整事实"))
            }
            val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            captured!!.input.messages.forEachIndexed { i, message -> assertEquals(message.content, wire.getJSONObject(i).getString("content")) }
            assertFalse(captured!!.input.messages.last().content.contains("中间完整事实"))
            assertTrue(runCatching { llm.proposeNextActionWithCompression("goal", emptyList(), executions) { _, _ -> error("fixture") } }.isFailure)
            assertTrue(runCatching { llm.proposeNextActionWithCompression("goal", emptyList(), executions) { _, _ -> throw kotlinx.coroutines.CancellationException("fixture") } }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun multipleToolsWithSmallVerifiedHistoryKeepExactBoundaryAndSendOriginalInput() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"action\":\"complete\"}"}}]}"""))
            val llm = OpenAiAgentLlm(OpenAiCompatibleClient(), ProviderRequestConfig(server.url("/v1").toString(),
                "fixture-key", "fixture", httpDebugLoggingEnabled = false), "样式")
            val tools = listOf(ToolDefinition("fixture.read", "读取", ToolRisk.SAFE), ToolDefinition("fixture.list", "目录", ToolRisk.SAFE))
            val executions = listOf("first", "second").map { id -> AgentToolExecution(
                ToolCall(id, "fixture.read", emptyMap(), ToolRisk.SAFE), ToolExecutionResult(true, "短结果")) }
            lateinit var captured: AgentContextInput
            llm.proposeNextActionWithCompression("两步读取", tools, executions) { input, candidate ->
                assertNull(candidate)
                captured = input
                val text = input.messages.last().content
                val start = text.indexOf("已执行并验证的工具历史：\n") + "已执行并验证的工具历史：\n".length
                assertEquals(2, AgentContextCompressionPolicy.history(input, start).length())
            }
            val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(captured.messages.last().content, wire.getJSONObject(1).getString("content"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
