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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** long: 仅使用 UUID 数据库与手机 loopback 模型夹具，不读取正式配置或用户正文。 */
@RunWith(AndroidJUnit4::class)
class RoomAgentContextSnapshotInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "stage287-context-${UUID.randomUUID()}.db"
    private val databases = mutableListOf<XiaoLingDatabase>()
    @Before fun requireTarget() { assertEquals("begonia", android.os.Build.DEVICE) }
    @After fun clean() { databases.forEach { it.close() }; context.deleteDatabase(name) }
    private fun open(): RoomAgentRunRepository {
        val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
        databases += db
        return RoomAgentRunRepository(context, db)
    }
    private fun input(text: String = "完整中文😀目标") = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
        listOf(AgentContextMessage("system", "系统约束"), AgentContextMessage("user", text)), emptyList())
    private suspend fun run(repo: RoomAgentRunRepository) = repo.createRun("fixture-conversation", "fixture-message", "fixture-goal").also {
        repo.updateRunStatus(it.id, AgentRunStatus.THINKING)
    }
    private suspend fun step(repo: RoomAgentRunRepository, id: String) = repo.appendStep(id, AgentStepTypes.LLM_PLAN, "规划", "隔离夹具", AgentStepStatus.RUNNING)

    @Test fun immutableInputSurvivesReopenAndRejectsOverwriteCrossRunAndWrongPhase() = runBlocking {
        var repo = open()
        val run = run(repo)
        val step = step(repo, run.id)
        val mutable = input().messages.toMutableList()
        val value = input().copy(messages = mutable)
        repo.appendContextSnapshot(run.id, step.id, value)
        mutable[1] = AgentContextMessage("user", "后来变化")
        repo.appendContextSnapshot(run.id, step.id, input())
        val before = repo.contextTranscript(run.id).single()
        assertEquals(1, before.sequence)
        assertEquals(input(), before.input)
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, step.id, input("旧步骤覆盖")) }.isFailure)
        val other = run(repo)
        assertTrue(runCatching { repo.appendContextSnapshot(other.id, step.id, input()) }.isFailure)
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, step.id, input().copy(phase = AgentLlmPhase.SUMMARIZE)) }.isFailure)
        databases.forEach { it.close() }; databases.clear()
        repo = open()
        assertEquals(listOf(before), repo.contextTranscript(run.id))
        assertTrue(repo.contextTranscript(other.id).isEmpty())
        assertEquals(AgentRunStatus.THINKING, repo.snapshot(run.id).run.status)
    }

    @Test fun twoDatabaseWritersCannotReplaceOneFrozenStep() = runBlocking {
        val first = open()
        val run = run(first)
        val step = step(first, run.id)
        val second = open()
        second.snapshot(run.id)
        val start = CompletableDeferred<Unit>()
        val jobs = listOf(first to "writer-one", second to "writer-two").map { (repo, text) ->
            async(Dispatchers.IO) { start.await(); runCatching { repo.appendContextSnapshot(run.id, step.id, input(text)) } }
        }
        start.complete(Unit)
        val results = withTimeout(20_000) { jobs.awaitAll() }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(first.contextTranscript(run.id), second.contextTranscript(run.id))
        assertEquals(1, first.contextTranscript(run.id).size)
        val frozen = first.contextTranscript(run.id).single().input
        val sameStart = CompletableDeferred<Unit>()
        val sameJobs = listOf(first, second).map { repo ->
            async(Dispatchers.IO) { sameStart.await(); repo.appendContextSnapshot(run.id, step.id, frozen) }
        }
        sameStart.complete(Unit)
        withTimeout(20_000) { sameJobs.awaitAll() }
        assertEquals(1, first.contextTranscript(run.id).size)
        val separateSteps = listOf(step(first, run.id), step(first, run.id))
        val separateStart = CompletableDeferred<Unit>()
        val separateJobs = listOf(first, second).mapIndexed { index, repo ->
            async(Dispatchers.IO) { separateStart.await(); repo.appendContextSnapshot(run.id, separateSteps[index].id, input("separate-$index")) }
        }
        separateStart.complete(Unit)
        withTimeout(20_000) { separateJobs.awaitAll() }
        assertEquals(listOf(1, 2, 3), first.contextTranscript(run.id).map { it.sequence })
        assertEquals(separateSteps.map { it.id }.toSet(), first.contextTranscript(run.id).drop(1).map { it.stepId }.toSet())
    }

    @Test fun insertionFailureLeavesPrefixAndSequenceIntactThenTerminalAndCancelRejectLateInput() = runBlocking {
        val repo = open()
        val run = run(repo)
        val one = step(repo, run.id)
        repo.appendContextSnapshot(run.id, one.id, input())
        val prefix = repo.contextTranscript(run.id)
        val two = step(repo, run.id)
        val sql = databases.single().openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_context BEFORE INSERT ON agent_context_snapshots BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, two.id, input("second")) }.isFailure)
        assertEquals(prefix, repo.contextTranscript(run.id))
        sql.execSQL("DROP TRIGGER reject_context")
        repo.appendContextSnapshot(run.id, two.id, input("second"))
        assertEquals(listOf(1, 2), repo.contextTranscript(run.id).map { it.sequence })
        val late = step(repo, run.id)
        repo.updateRunStatus(run.id, AgentRunStatus.COMPLETED)
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, late.id, input()) }.isFailure)
        val cancelled = run(repo)
        val cancelledStep = step(repo, cancelled.id)
        assertTrue(repo.requestCancel(cancelled.id, "fixture取消"))
        assertTrue(runCatching { repo.appendContextSnapshot(cancelled.id, cancelledStep.id, input()) }.isFailure)
        assertTrue(repo.contextTranscript(cancelled.id).isEmpty())
    }

    @Test fun pendingToolOrCorruptStoredInputCannotBeAcceptedOrSilentlyRepaired() = runBlocking {
        val repo = open()
        val run = run(repo)
        val step = step(repo, run.id)
        repo.appendContextSnapshot(run.id, step.id, input())
        repo.appendEvent(run.id, "tool.call.proposed", "待响应", RunEventMetadata.ToolCall("pending-call", "fixture.read", ToolRisk.SAFE, emptyMap()))
        val next = step(repo, run.id)
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, next.id, input()) }.isFailure)
        assertEquals(1, repo.contextTranscript(run.id).size)
        databases.single().openHelper.writableDatabase.execSQL("UPDATE agent_context_snapshots SET recordSha256 = 'corrupt' WHERE runId = ?", arrayOf(run.id))
        assertTrue(runCatching { repo.contextTranscript(run.id) }.isFailure)
        assertEquals("corrupt", databases.single().agentRunDao().getContextSnapshots(run.id).single().recordSha256)
    }

    @Test fun corruptStepHeaderOrAssociationRejectsReadAndAppendWithoutRepairingRows() = runBlocking {
        val repo = open()
        val run = run(repo)
        val first = step(repo, run.id)
        repo.appendContextSnapshot(run.id, first.id, input())
        val before = repo.contextTranscript(run.id).single()
        val other = run(repo)
        val foreign = step(repo, other.id)
        val next = step(repo, run.id)
        val db = databases.single()
        val sql = db.openHelper.writableDatabase
        for (badStepId in listOf("missing-step", foreign.id)) {
            sql.execSQL("UPDATE agent_context_snapshots SET stepId = ? WHERE runId = ?", arrayOf(badStepId, run.id))
            assertTrue(runCatching { repo.contextTranscript(run.id) }.isFailure)
            assertTrue(runCatching { repo.appendContextSnapshot(run.id, next.id, input()) }.isFailure)
            assertEquals(badStepId, db.agentRunDao().getContextSnapshots(run.id).single().stepId)
            // long: 即便摘要与损坏 header 一致，Step 仍须真实存在且属于同 Run；校验不应只依赖散列。
            val row = db.agentRunDao().getContextSnapshots(run.id).single()
            sql.execSQL("UPDATE agent_context_snapshots SET recordSha256 = ? WHERE runId = ?",
                arrayOf(AgentContextSnapshotCodec.recordHash(run.id, badStepId, row.sequence, row.payloadJson), run.id))
            assertTrue(runCatching { repo.contextTranscript(run.id) }.isFailure)
            assertTrue(runCatching { repo.appendContextSnapshot(run.id, next.id, input()) }.isFailure)
        }
        sql.execSQL("UPDATE agent_context_snapshots SET stepId = ?, recordSha256 = ? WHERE runId = ?",
            arrayOf(first.id, before.recordSha256, run.id))
        sql.execSQL("UPDATE agent_steps SET type = 'tool.execute' WHERE id = ?", arrayOf(first.id))
        assertTrue(runCatching { repo.contextTranscript(run.id) }.isFailure)
        assertTrue(runCatching { repo.appendContextSnapshot(run.id, next.id, input()) }.isFailure)
        assertEquals(1, db.agentRunDao().getContextSnapshots(run.id).size)
    }

    @Test fun updatingSameRunPreservesSnapshotsAndExplicitDeletionStillCascades() = runBlocking {
        val repo = open()
        val run = run(repo)
        repo.appendContextSnapshot(run.id, step(repo, run.id).id, input())
        val prefix = repo.contextTranscript(run.id)
        val db = databases.single()
        val entity = requireNotNull(db.agentRunDao().getRun(run.id))
        db.agentRunDao().upsertRun(entity.copy(goal = "updated goal"))
        assertEquals("updated goal", repo.snapshot(run.id).run.goal)
        assertEquals(prefix, repo.contextTranscript(run.id))
        repo.appendContextSnapshot(run.id, step(repo, run.id).id, input("second"))
        assertEquals(listOf(1, 2), repo.contextTranscript(run.id).map { it.sequence })
        db.openHelper.writableDatabase.execSQL("DELETE FROM agent_runs WHERE id = ?", arrayOf(run.id))
        assertTrue(repo.contextTranscript(run.id).isEmpty())
    }

    @Test fun frameAndRunByteLimitsRejectWholeNewFrameWithoutTruncatingPrefix() = runBlocking {
        val repo = open()
        val small = run(repo)
        repeat(AgentContextSnapshotCodec.MAX_FRAMES) {
            repo.appendContextSnapshot(small.id, step(repo, small.id).id, input("frame-$it"))
        }
        val prefix = repo.contextTranscript(small.id)
        assertTrue(runCatching { repo.appendContextSnapshot(small.id, step(repo, small.id).id, input()) }.isFailure)
        assertEquals(prefix, repo.contextTranscript(small.id))
        val large = run(repo)
        val value = input("x".repeat(AgentContextSnapshotCodec.MAX_REQUEST_BYTES - 1_000))
        repeat(16) { repo.appendContextSnapshot(large.id, step(repo, large.id).id, value) }
        assertTrue(runCatching { repo.appendContextSnapshot(large.id, step(repo, large.id).id, value) }.isFailure)
        val retained = repo.contextTranscript(large.id)
        assertEquals(16, retained.size)
        assertTrue(retained.all { it.input == value })
    }

    @Test fun productionRuntimePersistsPlannerAndSummaryBeforeLoopbackRequestsAndKeepsVerifiedToolPair() = runBlocking {
        val repo = open()
        val server = MockWebServer()
        server.start()
        try {
            listOf("{\"action\":\"tool\",\"tool\":\"fixture.read\",\"arguments\":{}}", "{\"action\":\"complete\"}",
                "{\"style\":\"compact\",\"tone\":\"neutral\"}").forEach { server.enqueue(response(it)) }
            val definition = ToolDefinition("fixture.read", "隔离只读工具", ToolRisk.SAFE)
            val registry = object : ToolRegistry {
                override fun availableTools() = listOf(definition)
                override fun definition(name: String) = definition.takeIf { it.name == name }
                override suspend fun execute(call: ToolCall) = ToolExecutionResult(true, "fixture 已观察事实", true)
            }
            val summary = MinimalAgentRuntime(repo, registry, model(server)).run("fixture-conversation", "fixture-message", "fixture读取事实")
            assertEquals(AgentRunStatus.COMPLETED, summary.status)
            val frames = repo.contextTranscript(summary.runId)
            assertEquals(listOf(1, 2, 3), frames.map { it.sequence })
            assertEquals(listOf(AgentLlmPhase.PLAN, AgentLlmPhase.PLAN, AgentLlmPhase.SUMMARIZE), frames.map { it.input.phase })
            assertTrue(frames.first().input.verifiedToolCallIds.isEmpty())
            val verified = databases.single().agentRunDao().getToolResults(summary.runId).single()
            assertEquals(ToolVerificationStatus.PASSED.name, verified.verificationStatus)
            assertEquals(listOf(verified.toolCallId), frames[1].input.verifiedToolCallIds)
            assertEquals(frames[1].input.verifiedToolCallIds, frames[2].input.verifiedToolCallIds)
            assertTrue(frames[1].input.messages.last().content.contains(verified.content))
            frames.forEach { frame ->
                val wire = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                frame.input.messages.forEachIndexed { index, message -> assertEquals(message.content, wire.getJSONObject(index).getString("content")) }
            }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun productionRuntimeDatabaseFailureStopsBeforeNetworkAndFailsItsStep() = runBlocking {
        val repo = open()
        databases.single().openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_context BEFORE INSERT ON agent_context_snapshots BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        val server = MockWebServer()
        server.start()
        try {
            val error = runCatching { MinimalAgentRuntime(repo, llm = model(server)).run("fixture-conversation", "fixture-message", "fixture持久化失败") }.exceptionOrNull()
            assertTrue(error is AgentContextSnapshotException)
            assertEquals(0, server.requestCount)
            val run = databases.single().agentRunDao().getRecentRuns(1).single()
            assertEquals(AgentRunStatus.FAILED.name, run.status)
            assertTrue(repo.contextTranscript(run.id).isEmpty())
            assertEquals(AgentStepStatus.FAILED, repo.snapshot(run.id).steps.single { it.type == AgentStepTypes.LLM_PLAN }.status)
        } finally { server.shutdown() }
    }

    private fun model(server: MockWebServer) = OpenAiAgentLlm(OpenAiCompatibleClient(),
        ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture", httpDebugLoggingEnabled = false), "选择总结样式")
    private fun response(text: String) = MockResponse().setResponseCode(200).setBody(JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
}
