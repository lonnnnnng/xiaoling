package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentSkillStore
import com.longdev.xiaoling.ui.agenttask.AgentChildSummaryState
import com.longdev.xiaoling.ui.agenttask.AgentTaskCenterProjection
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** long: 所有持久写入限定 UUID Room，模型使用 loopback；不触碰正式 Provider、MCP、插件或 Memory undo。 */
internal class ChildLifecycleFixture(val context: Context, val name: String) : AutoCloseable {
    var db: XiaoLingDatabase = open()
        private set
    val repo get() = RoomAgentRunRepository(context, db)
    val profile = AgentProfileSnapshot("stage289", "子任务验收", "读", "stage289-provider", "fixture",
        ApiMode.CHAT_COMPLETIONS, "读取当前时间后结束。", AgentContextPolicy.CURRENT_CONVERSATION,
        listOf("app.current_time", "notes.create"), emptyList(), false)
    val childProfile get() = profile.copy(allowedToolNames = listOf("app.current_time"))
    val specs = listOf(ReadOnlyChildAgentSpec("child-1", "alpha-time"), ReadOnlyChildAgentSpec("child-2", "beta-time"))
    private fun open() = Room.databaseBuilder(context, XiaoLingDatabase::class.java, "$name.db")
        .addMigrations(*XiaoLingDatabase.migrations()).build()
    suspend fun parent(origin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        source: AgentInvocationSource = AgentInvocationSource.DIRECT, external: Boolean = false, budgetMs: Long = 120_000): AgentRunRecord {
        val run = repo.createInvokedRun("fixture-c", "fixture-m", "读取两个事实", null, null,
            AgentChildLifecyclePolicy.invocation(origin, source, external))
        repo.appendEvent(run.id, AgentEventTypes.PROFILE_SELECTED, "冻结 Profile", RunEventMetadata.AgentProfileSelection(profile))
        repo.appendEvent(run.id, AgentEventTypes.EXECUTION_BUDGET_UPDATED, "预算", RunEventMetadata.ExecutionBudget(budgetMs, 0))
        repo.updateRunStatus(run.id, AgentRunStatus.THINKING)
        return run
    }
    suspend fun prepare(parent: AgentRunRecord) = repo.prepareReadOnlyChildRuns(parent.id, "fixture-c", "fixture-m", specs, profile, childProfile)
    fun useCase() = AgentRunUseCase(context, OpenAiCompatibleClient(), McpSessionRegistry(object : McpServerStore {
        override fun list() = emptyList<McpServerConfig>()
        override fun get(id: String): McpServerConfig? = null
        override fun upsert(config: McpServerConfig): McpServerConfig = error("夹具不得写 MCP")
        override fun delete(id: String): Boolean = error("夹具不得写 MCP")
    }), repo, skillStore = RoomAgentSkillStore(context, db))
    fun reopen() { close(); db = open() }
    override fun close() { db.close() }
    fun delete() { close(); check(context.deleteDatabase("$name.db")) }
}

class AgentChildLifecycleInstrumentedTest {
    private lateinit var f: ChildLifecycleFixture
    @Before fun setup() {
        assertEquals("begonia", android.os.Build.DEVICE)
        f = ChildLifecycleFixture(ApplicationProvider.getApplicationContext(), "stage289-child-${UUID.randomUUID()}")
    }
    @After fun clean() { f.delete() }

    @Test fun batchFreezeIsAtomicAndDuplicatePreparationCannotCreateMoreChildren() = runBlocking {
        val parent = f.parent(budgetMs = 9000)
        val checkpoint = f.prepare(parent)
        assertEquals(9000L, checkpoint.options.runTimeoutMs)
        assertEquals(2, f.db.agentRunDao().getRunsByParentRunId(parent.id).size)
        assertTrue(runCatching { f.prepare(parent) }.isFailure)
        checkpoint.slots.forEach { slot ->
            val child = f.repo.snapshot(slot.runId)
            assertEquals(parent.id, child.run.rootRunId); assertEquals(parent.id, child.run.parentRunId)
            assertEquals(1, child.events.count { it.type == "run.created" })
            assertEquals(checkpoint, f.repo.readOnlyChildCheckpoint(slot.runId))
        }
        assertEquals(3, f.db.agentRunDao().getRecentRuns(10).size)
    }

    @Test fun secondChildInsertFailureRollsBackAllSlotsAndParentEvidence() = runBlocking {
        val parent = f.parent()
        val before = f.repo.snapshot(parent.id)
        f.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER stage289_fail_second BEFORE INSERT ON agent_runs
            WHEN NEW.userMessageId = 'fixture-m.child-2' BEGIN SELECT RAISE(ABORT, 'stage289 injected child failure'); END""")
        assertTrue(runCatching { f.prepare(parent) }.isFailure)
        assertEquals(before, f.repo.snapshot(parent.id))
        assertEquals(1, f.db.agentRunDao().getRecentRuns(10).size)
    }

    @Test fun concurrentClaimsHaveOneWinnerAndParentCancelFreezesBothSlots() = runBlocking {
        val parent = f.parent(); val checkpoint = f.prepare(parent)
        val results = coroutineScope { List(2) { async { runCatching { f.repo.claimReadOnlyChildRun(checkpoint.slots.first().runId) } } }.awaitAll() }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(f.repo.requestCancel(parent.id, "父取消"))
        checkpoint.slots.forEach { slot ->
            val before = f.repo.snapshot(slot.runId)
            assertEquals(AgentRunStatus.CANCELLED, before.run.status)
            assertTrue(runCatching { f.repo.claimReadOnlyChildRun(slot.runId) }.isFailure)
            f.repo.updateRunStatus(slot.runId, AgentRunStatus.COMPLETED, "迟到结果")
            f.repo.appendEvent(slot.runId, "late", "迟到事件")
            assertEquals(before, f.repo.snapshot(slot.runId))
        }
    }

    @Test fun backgroundWorkflowExternalAndCancelledParentsCannotPrepareSlots() = runBlocking {
        val parents = listOf(f.parent(origin = AgentExecutionOrigin.BACKGROUND), f.parent(source = AgentInvocationSource.WORKFLOW),
            f.parent(external = true), f.parent().also { f.repo.requestCancel(it.id, "取消") })
        parents.forEach { parent -> assertTrue(runCatching { f.prepare(parent) }.isFailure) }
        assertEquals(4, f.db.agentRunDao().getRecentRuns(10).size)
    }

    @Test fun prepareRacingParentCancelHasNoOrphanOrActiveChild() = runBlocking {
        repeat(8) {
            val parent = f.parent()
            val (prepared, cancelled) = coroutineScope {
                val spawn = async(Dispatchers.Default) { runCatching { f.prepare(parent) } }
                val stop = async(Dispatchers.Default) { f.repo.requestCancel(parent.id, "并发父取消") }
                spawn.await() to stop.await()
            }
            assertTrue(cancelled)
            val children = f.db.agentRunDao().getRunsByParentRunId(parent.id)
            assertEquals(if (prepared.isSuccess) 2 else 0, children.size)
            assertTrue(children.all { it.status == AgentRunStatus.CANCELLED.name })
        }
    }

    @Test fun parentTerminalRacingChildCompletionFreezesWinnerAndDoesNotCancelRetry() = runBlocking {
        repeat(8) {
            val parent = f.parent(); val checkpoint = f.prepare(parent)
            val child = checkpoint.slots.first().runId
            f.repo.claimReadOnlyChildRun(child)
            val retry = f.repo.createRun("retry-c", "retry-m", "普通重试", parent.id)
            coroutineScope {
                listOf(async(Dispatchers.Default) { f.repo.updateRunStatus(parent.id, AgentRunStatus.COMPLETED, "父完成") },
                    async(Dispatchers.Default) { f.repo.updateRunStatus(child, AgentRunStatus.COMPLETED, "子完成") }).awaitAll()
            }
            val before = f.repo.snapshot(child)
            assertTrue(before.run.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.CANCELLED))
            f.repo.updateRunStatus(child, AgentRunStatus.COMPLETED, "迟到替换")
            assertEquals(before, f.repo.snapshot(child))
            assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(checkpoint.slots.last().runId).run.status)
            assertEquals(AgentRunStatus.QUEUED, f.repo.snapshot(retry.id).run.status)
        }
    }

    @Test fun directChildEntryRejectsChangedApiModeBeforeClaim() = runBlocking {
        val parent = f.parent(); val checkpoint = f.prepare(parent); val slot = checkpoint.slots.first()
        val useCase = f.useCase()
        try {
            assertTrue(runCatching { useCase.run("fixture-c", "fixture-m.${slot.id}", f.specs.first().goal,
                config = ProviderRequestConfig("http://127.0.0.1:1/v1", "fixture-key", "fixture", providerId = "stage289-provider",
                    apiMode = ApiMode.RESPONSES), summarySystemPrompt = "事实", agentProfile = checkpoint.profile,
                memoryRecallEnabled = false, parentRunId = parent.id, childRunId = slot.runId) }.isFailure)
            assertTrue(f.repo.snapshot(slot.runId).events.none { it.type == AgentChildLifecyclePolicy.CLAIM_EVENT })
            assertEquals(AgentRunStatus.QUEUED, f.repo.snapshot(slot.runId).run.status)
        } finally { useCase.closeMcpOwners() }
    }

    @Test fun diskReopenClosesUnfinishedSlotsAndPreservesCompletedEvidence() = runBlocking {
        val parent = f.parent(); val checkpoint = f.prepare(parent)
        f.repo.claimReadOnlyChildRun(checkpoint.slots.first().runId)
        f.repo.updateRunStatus(checkpoint.slots.first().runId, AgentRunStatus.COMPLETED, "已完成事实")
        val completed = f.repo.snapshot(checkpoint.slots.first().runId)
        f.reopen()
        assertEquals(checkpoint, f.repo.readOnlyChildCheckpoint(checkpoint.slots.last().runId))
        assertTrue(f.repo.recoverVerifiedToolRuns().isEmpty())
        assertTrue(f.repo.closeInterruptedRuns() >= 1)
        assertEquals(completed, f.repo.snapshot(checkpoint.slots.first().runId))
        assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(checkpoint.slots.last().runId).run.status)
        assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(parent.id).run.status)
    }

    private fun server(stalled: String? = null, failed: String? = null): MockWebServer = MockWebServer().apply {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val goal = if (body.contains("alpha-time")) "alpha-time" else "beta-time"
                assertFalse(body.contains("notes.create"))
                if (goal == stalled) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                if (goal == failed) return MockResponse().setResponseCode(400).setBody("fixture failure")
                val text = when (counts.computeIfAbsent(goal) { AtomicInteger() }.getAndIncrement()) {
                    0 -> """{"action":"tool","tool":"app.current_time","arguments":{}}"""
                    1 -> """{"action":"complete"}"""
                    else -> """{"style":"compact","tone":"neutral"}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices",
                    JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
            }
        }
        start()
    }
    private suspend fun execute(parent: AgentRunRecord, server: MockWebServer, useCase: AgentRunUseCase) =
        useCase.runReadOnlyChildAgents(parent.id, "fixture-c", "fixture-m", f.specs.map { it.goal },
            ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture", providerId = "stage289-provider",
                httpDebugLoggingEnabled = false), "只总结已验证事实", f.profile)

    @Test fun productionUseCaseRunsTwoIsolatedNativeChildrenAndReturnsRoomStatuses() = runBlocking {
        val parent = f.parent(); val server = server(); val useCase = f.useCase()
        try {
            val results = withTimeout(20_000) { execute(parent, server, useCase) }
            assertEquals(listOf("child-1", "child-2"), results.map { it.childId })
            assertTrue(results.all { it.status == AgentRunStatus.COMPLETED })
            results.forEach { result ->
                val detail = requireNotNull(f.repo.runDetail(requireNotNull(result.childRunId)))
                assertEquals(result.status, detail.snapshot.run.status)
                assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
                assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
                assertEquals(listOf("app.current_time"), detail.agentProfileSnapshotOrNull()!!.allowedToolNames)
                assertEquals(1, detail.snapshot.events.count { it.type == AgentChildLifecyclePolicy.CLAIM_EVENT })
            }
            assertEquals(3, f.db.agentRunDao().getRecentRuns(10).size)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }

    @Test fun childTimeoutDoesNotCancelCompletedSiblingAndAggregationIsPartial() = runBlocking {
        val parent = f.parent(budgetMs = 1500); val server = server(stalled = "beta-time"); val useCase = f.useCase()
        try {
            val results = withTimeout(12_000) { execute(parent, server, useCase) }
            assertEquals(AgentRunStatus.COMPLETED, results.first().status)
            assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, results.last().status)
            assertPartial(parent, results)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }

    @Test fun ordinaryChildFailureIsIsolatedAndKeepsSuccessfulSibling() = runBlocking {
        val parent = f.parent(); val server = server(failed = "beta-time"); val useCase = f.useCase()
        try {
            val results = withTimeout(20_000) { execute(parent, server, useCase) }
            assertEquals(AgentRunStatus.COMPLETED, results.first().status)
            assertEquals(AgentRunStatus.FAILED, results.last().status)
            assertPartial(parent, results)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    private suspend fun assertPartial(parent: AgentRunRecord, results: List<ReadOnlyChildAgentResult>) {
        val history = (results.map { requireNotNull(f.repo.runDetail(requireNotNull(it.childRunId))) } + requireNotNull(f.repo.runDetail(parent.id)))
        val projected = AgentTaskCenterProjection.project(false, null, history, null, null).runs.single { it.detail.snapshot.run.id == parent.id }
        assertEquals(AgentChildSummaryState.PARTIAL, projected.childSummary.state)
        assertEquals(1, projected.childSummary.completedCount); assertEquals(1, projected.childSummary.failedCount)
    }

    @Test fun persistedParentCancelInterruptsModelWaitAndLeavesNoActiveChild() = runBlocking {
        val parent = f.parent(); val server = server(stalled = "alpha-time", failed = "beta-time"); val useCase = f.useCase()
        try {
            val task = async { execute(parent, server, useCase) }
            withTimeout(10_000) { while (server.requestCount == 0) delay(20) }
            assertTrue(f.repo.requestCancel(parent.id, "取消父任务"))
            assertTrue(runCatching { withTimeout(10_000) { task.await() } }.exceptionOrNull() is CancellationException)
            assertTrue(f.db.agentRunDao().getRunsByParentRunId(parent.id).all { AgentRunStatus.valueOf(it.status).isTerminal })
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
}
