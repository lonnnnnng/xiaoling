package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** long: 压缩只验 UUID 数据库与手机 loopback，不读取正式 Provider/Memory undo。 */
@RunWith(AndroidJUnit4::class)
class RoomAgentContextCompressionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "stage287-compression-${UUID.randomUUID()}.db"
    private val databases = mutableListOf<XiaoLingDatabase>()
    private val oldText = "原文开头😀" + "中😀".repeat(12_000) + "中间完整事实" + "尾部".repeat(1_000)
    @Before fun requireTarget() { assertEquals("begonia", android.os.Build.DEVICE) }
    @After fun clean() { databases.forEach { it.close() }; context.deleteDatabase(name) }
    private fun open(): RoomAgentRunRepository {
        val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
        databases += db
        return RoomAgentRunRepository(context, db)
    }
    private suspend fun step(repo: RoomAgentRunRepository, runId: String) = repo.appendStep(runId, AgentStepTypes.LLM_PLAN, "规划", "隔离夹具", AgentStepStatus.RUNNING)
    private suspend fun seed(repo: RoomAgentRunRepository, olderText: String = oldText): Pair<String, AgentContextCompressionCandidate> {
        val run = repo.createRun("fixture-conversation", "fixture-message", "保留完整原输入")
        repo.updateRunStatus(run.id, AgentRunStatus.THINKING)
        val rows = JSONArray()
        listOf(olderText, "最近完整事实").forEachIndexed { index, text ->
            val id = "call-$index"
            val args = mapOf("value" to "$index")
            repo.appendEvent(run.id, "tool.call.proposed", "夹具调用", RunEventMetadata.ToolCall(id, "fixture.read", ToolRisk.SAFE, args))
            repo.appendEvent(run.id, "tool.result", "夹具结果", RunEventMetadata.ToolResult("fixture.read", text, 1, true, null, toolCallId = id))
            repo.appendEvent(run.id, "tool.verify", "夹具通过", RunEventMetadata.ToolVerification("fixture.read", ToolVerificationStatus.PASSED, id))
            rows.put(JSONObject().put("step", index + 1).put("tool", "fixture.read").put("arguments", JSONObject(args))
                .put("success", true).put("content", text).put("knowledge_references", JSONArray()))
        }
        val prefix = "用户目标：保留完整原输入\n可用工具和 Skill 保持\n已执行并验证的工具历史：\n"
        val source = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS", listOf(
            AgentContextMessage("system", "系统约束"), AgentContextMessage("user", prefix + rows)), listOf("call-0", "call-1"))
        return run.id to requireNotNull(AgentContextCompressionPolicy.prepare(source, prefix.length))
    }

    @Test fun versionsAreImmutableSurviveReopenAndDoNotChangeRecoveryOrFullToolResults() = runBlocking {
        var repo = open()
        val (runId, candidate) = seed(repo)
        val first = step(repo, runId)
        val before = AgentRunResumePolicy.assess(requireNotNull(repo.runDetail(runId)))
        repo.appendCompressedContextSnapshot(runId, first.id, candidate.input, candidate)
        repo.appendCompressedContextSnapshot(runId, first.id, candidate.input, candidate)
        assertEquals(before, AgentRunResumePolicy.assess(requireNotNull(repo.runDetail(runId))))
        val two = step(repo, runId)
        repo.appendCompressedContextSnapshot(runId, two.id, candidate.input, candidate)
        val frames = repo.contextTranscript(runId)
        val versions = repo.contextCompressions(runId)
        assertEquals(listOf(1, 2), versions.map { it.version })
        assertEquals(listOf(1, 2), frames.map { it.compressionVersion })
        assertEquals(candidate.sourceInput, versions.first().sourceInput)
        assertEquals(oldText, repo.toolLedger(runId).results.single { it.toolCallId == "call-0" }.content)
        assertTrue(runCatching { repo.appendContextSnapshot(runId, first.id, candidate.input) }.isFailure)
        databases.forEach { it.close() }; databases.clear()
        repo = open()
        assertEquals(frames, repo.contextTranscript(runId))
        assertEquals(versions, repo.contextCompressions(runId))
        databases.single().openHelper.writableDatabase.execSQL("DELETE FROM agent_runs WHERE id = ?", arrayOf(runId))
        assertTrue(repo.contextTranscript(runId).isEmpty())
        assertTrue(repo.contextCompressions(runId).isEmpty())
    }

    @Test fun sourceInsertFailureRollsBackFrameAndRetryKeepsVersionAndPrefixThenPendingCancelReject() = runBlocking {
        val repo = open()
        val (runId, candidate) = seed(repo)
        val first = step(repo, runId)
        repo.appendContextSnapshot(runId, first.id, candidate.sourceInput)
        val prefix = repo.contextTranscript(runId)
        val next = step(repo, runId)
        val sql = databases.single().openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_compression BEFORE INSERT ON agent_context_compressions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate) }.isFailure)
        assertEquals(prefix, repo.contextTranscript(runId))
        assertTrue(repo.contextCompressions(runId).isEmpty())
        sql.execSQL("DROP TRIGGER reject_compression")
        repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate)
        assertEquals(1, repo.contextCompressions(runId).single().version)
        assertEquals(listOf(1, 2), repo.contextTranscript(runId).map { it.sequence })
        val pending = step(repo, runId)
        repo.appendEvent(runId, "tool.call.proposed", "pending", RunEventMetadata.ToolCall("pending", "fixture.read", ToolRisk.SAFE, emptyMap()))
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, pending.id, candidate.input, candidate) }.isFailure)
        assertTrue(repo.requestCancel(runId, "fixture取消"))
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, pending.id, candidate.input, candidate) }.isFailure)
        assertEquals(1, repo.contextCompressions(runId).size)
    }

    @Test fun sourceDriftCorruptionOrMissingSourceRejectsReadAndAppendWithoutRepair() = runBlocking {
        val repo = open()
        val (runId, candidate) = seed(repo)
        val first = step(repo, runId)
        val sql = databases.single().openHelper.writableDatabase
        sql.execSQL("UPDATE agent_tool_results SET content = 'changed' WHERE toolCallId = 'call-0'")
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, first.id, candidate.input, candidate) }.isFailure)
        assertTrue(repo.contextTranscript(runId).isEmpty())
        sql.execSQL("UPDATE agent_tool_results SET content = ? WHERE toolCallId = 'call-0'", arrayOf(oldText))
        repo.appendCompressedContextSnapshot(runId, first.id, candidate.input, candidate)
        val alternateHistory = " " + candidate.sourceInput.messages.last().content.substring(candidate.historyStart)
        val alternateSource = candidate.sourceInput.copy(messages = listOf(candidate.sourceInput.messages.first(),
            AgentContextMessage("user", candidate.sourceInput.messages.last().content.take(candidate.historyStart) + alternateHistory)))
        val alternate = requireNotNull(AgentContextCompressionPolicy.prepare(alternateSource, candidate.historyStart))
        assertEquals(candidate.input, alternate.input)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, first.id, alternate.input, alternate) }.isFailure)
        val next = step(repo, runId)
        val resultEntity = databases.single().agentRunDao().getToolResults(runId).single { it.toolCallId == "call-0" }
        for ((column, value) in listOf<Pair<String, Any>>("verificationStatus" to "FAILED", "success" to 0, "executorVerified" to 1,
            "knowledgeReferencesJson" to "[{\"bad\":true}]")) {
            sql.execSQL("UPDATE agent_tool_results SET $column = ? WHERE toolCallId = 'call-0'", arrayOf<Any>(value))
            assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
            assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate) }.isFailure)
            val original: Any? = when (column) {
                "verificationStatus" -> resultEntity.verificationStatus
                "success" -> if (resultEntity.success) 1 else 0
                "executorVerified" -> resultEntity.executorVerified?.let { if (it) 1 else 0 }
                else -> resultEntity.knowledgeReferencesJson
            }
            sql.execSQL("UPDATE agent_tool_results SET $column = ? WHERE toolCallId = 'call-0'", arrayOf(original))
        }
        sql.execSQL("UPDATE agent_tool_calls SET argumentsJson = '{\"value\":\"changed\"}' WHERE id = 'call-0'")
        assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
        sql.execSQL("UPDATE agent_tool_calls SET argumentsJson = '{\"value\":\"0\"}' WHERE id = 'call-0'")
        val payload = AgentContextSnapshotCodec.encode(candidate.input)
        val duplicate = com.longdev.xiaoling.data.AgentContextSnapshotEntity(runId, 2, next.id, payload,
            AgentContextSnapshotCodec.recordHash(runId, next.id, 2, payload, 1), AgentContextSnapshotCodec.utf8Size(payload), 100, 1)
        assertTrue(runCatching { databases.single().agentRunDao().insertContextSnapshot(duplicate) }.isFailure)
        // long: 独立测试库移除索引后注入旧/损坏重复版本，证明读取校验也逐帧检查来源，不能只依赖 SQL 唯一索引。
        sql.execSQL("DROP INDEX index_agent_context_snapshots_runId_compressionVersion")
        databases.single().agentRunDao().insertContextSnapshot(duplicate)
        assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
        assertTrue(runCatching { repo.contextCompressions(runId) }.isFailure)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate) }.isFailure)
        sql.execSQL("DELETE FROM agent_context_snapshots WHERE runId = ? AND sequence = 2", arrayOf(runId))
        sql.execSQL("CREATE UNIQUE INDEX index_agent_context_snapshots_runId_compressionVersion ON agent_context_snapshots (runId, compressionVersion)")
        sql.execSQL("UPDATE agent_context_compressions SET historyStart = historyStart + 1 WHERE runId = ?", arrayOf(runId))
        assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
        assertTrue(runCatching { repo.contextCompressions(runId) }.isFailure)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate) }.isFailure)
        sql.execSQL("UPDATE agent_context_compressions SET historyStart = ? WHERE runId = ?", arrayOf<Any>(candidate.historyStart, runId))
        assertEquals(1, repo.contextCompressions(runId).size)
        sql.execSQL("UPDATE agent_tool_results SET content = 'changed-again' WHERE toolCallId = 'call-0'")
        assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
        sql.execSQL("UPDATE agent_tool_results SET content = ? WHERE toolCallId = 'call-0'", arrayOf(oldText))
        sql.execSQL("DELETE FROM agent_context_compressions WHERE runId = ?", arrayOf(runId))
        assertTrue(runCatching { repo.contextTranscript(runId) }.isFailure)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, next.id, candidate.input, candidate) }.isFailure)
        assertEquals(1, databases.single().agentRunDao().getContextSnapshots(runId).size)
    }

    @Test fun twoConnectionsDeduplicateVersionAndDifferentStepsKeepContinuousVersions() = runBlocking {
        val first = open()
        val (runId, candidate) = seed(first)
        val one = step(first, runId)
        val second = open()
        second.snapshot(runId)
        val gate = CompletableDeferred<Unit>()
        val jobs = listOf(first, second).map { repo -> async(Dispatchers.IO) {
            gate.await(); repo.appendCompressedContextSnapshot(runId, one.id, candidate.input, candidate)
        } }
        gate.complete(Unit)
        withTimeout(20_000) { jobs.awaitAll() }
        assertEquals(1, first.contextCompressions(runId).size)
        val steps = listOf(step(first, runId), step(first, runId))
        val separate = CompletableDeferred<Unit>()
        val next = listOf(first, second).mapIndexed { i, repo -> async(Dispatchers.IO) {
            separate.await(); repo.appendCompressedContextSnapshot(runId, steps[i].id, candidate.input, candidate)
        } }
        separate.complete(Unit)
        withTimeout(20_000) { next.awaitAll() }
        assertEquals(listOf(1, 2, 3), first.contextCompressions(runId).map { it.version })
        assertEquals(first.contextCompressions(runId), second.contextCompressions(runId))
    }

    @Test fun versionCapacityRejectsNewFrameWithoutDroppingOriginals() = runBlocking {
        val repo = open()
        val (runId, candidate) = seed(repo)
        repeat(AgentContextCompressionPolicy.MAX_VERSIONS) {
            repo.appendCompressedContextSnapshot(runId, step(repo, runId).id, candidate.input, candidate)
        }
        val prefix = repo.contextTranscript(runId)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, step(repo, runId).id, candidate.input, candidate) }.isFailure)
        assertEquals(prefix, repo.contextTranscript(runId))
        assertEquals(32, repo.contextCompressions(runId).size)
    }

    @Test fun sourceByteCapacityRollsBackNewVersionBeforeVersionCountLimit() = runBlocking {
        val repo = open()
        val (runId, candidate) = seed(repo, "x".repeat(850_000))
        val bytes = AgentContextSnapshotCodec.utf8Size(AgentContextSnapshotCodec.encode(candidate.sourceInput))
        val accepted = (AgentContextCompressionPolicy.MAX_SOURCE_BYTES / bytes).toInt()
        assertTrue(accepted < AgentContextCompressionPolicy.MAX_VERSIONS)
        repeat(accepted) { repo.appendCompressedContextSnapshot(runId, step(repo, runId).id, candidate.input, candidate) }
        val prefix = repo.contextTranscript(runId)
        val originals = repo.contextCompressions(runId)
        assertTrue(runCatching { repo.appendCompressedContextSnapshot(runId, step(repo, runId).id, candidate.input, candidate) }.isFailure)
        assertEquals(prefix, repo.contextTranscript(runId))
        assertEquals(originals, repo.contextCompressions(runId))
        assertTrue(originals.all { it.sourceInput == candidate.sourceInput })
    }

    @Test fun productionRuntimeSendsCompressedWireAfterSourceCommitAndFailureMakesZeroDerivedRequests() = runBlocking {
        val repo = open()
        val definition = ToolDefinition("fixture.read", "读取隔离事实", ToolRisk.SAFE, inputSchema = listOf(ToolInputField("value", "索引", required = true)))
        val registry = object : ToolRegistry {
            override fun availableTools() = listOf(definition)
            override fun definition(name: String) = definition.takeIf { name == it.name }
            override suspend fun execute(call: ToolCall) = ToolExecutionResult(true, if (call.arguments["value"] == "0") oldText else "最近完整事实")
        }
        for (fail in listOf(false, true)) {
            val server = MockWebServer()
            var requests = 0
            var sourceCommittedBeforeWire = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests++
                    val responseText = when (requests) {
                        1 -> """{"action":"tool","tool":"fixture.read","arguments":{"value":"0"}}"""
                        2 -> """{"action":"tool","tool":"fixture.read","arguments":{"value":"1"}}"""
                        3 -> {
                            sourceCommittedBeforeWire = runBlocking {
                                val id = databases.single().agentRunDao().getRecentRuns(1).single().id
                                repo.contextCompressions(id).size == 1
                            }
                            """{"action":"complete"}"""
                        }
                        else -> """{"style":"compact","tone":"neutral"}"""
                    }
                    return response(responseText)
                }
            }
            if (fail) databases.single().openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_compression BEFORE INSERT ON agent_context_compressions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            server.start()
            try {
                val runtime = MinimalAgentRuntime(repo, registry, OpenAiAgentLlm(OpenAiCompatibleClient(),
                    ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture", httpDebugLoggingEnabled = false), "样式"))
                val result = runCatching { runtime.run("fixture-conversation", "fixture-message", "读取两个隔离事实") }
                val id = databases.single().agentRunDao().getRecentRuns(1).single().id
                val frames = repo.contextTranscript(id)
                if (fail) {
                    assertTrue(result.exceptionOrNull() is AgentContextSnapshotException)
                    assertEquals(2, requests)
                    assertEquals(2, frames.size)
                    assertTrue(repo.contextCompressions(id).isEmpty())
                    assertEquals(AgentRunStatus.FAILED, repo.snapshot(id).run.status)
                    assertEquals(2, repo.toolLedger(id).results.count { it.verificationStatus == ToolVerificationStatus.PASSED })
                } else {
                    assertEquals(AgentRunStatus.COMPLETED, result.getOrThrow().status)
                    assertTrue(sourceCommittedBeforeWire)
                    assertEquals(4, requests)
                    assertEquals(1, frames[2].compressionVersion)
                    assertTrue(repo.contextCompressions(id).single().sourceInput.messages.last().content.contains("中间完整事实"))
                    assertFalse(frames[2].input.messages.last().content.contains("中间完整事实"))
                    frames.forEach { frame ->
                        val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                        frame.input.messages.forEachIndexed { index, message -> assertEquals(message.content, wire.getJSONObject(index).getString("content")) }
                    }
                }
            } finally { server.shutdown() }
        }
    }

    private fun response(text: String) = MockResponse().setBody(JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
}
