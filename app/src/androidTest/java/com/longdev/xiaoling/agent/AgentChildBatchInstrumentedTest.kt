package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.model.ProviderRequestConfig
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** long: 父批次与 child 写入同一 UUID Room，只有原生时间能力；正式配置和用户内容不进入夹具。 */
class AgentChildBatchInstrumentedTest {
    private lateinit var f: ChildLifecycleFixture
    private val foreground = AgentPluginForegroundState()
    private val owner = Any()
    @Before fun setup() {
        assertEquals("begonia", android.os.Build.DEVICE)
        f = ChildLifecycleFixture(ApplicationProvider.getApplicationContext(), "stage289-child-batch-${UUID.randomUUID()}")
        foreground.resumed(owner)
    }
    @After fun clean() { f.delete() }
    private fun config(server: MockWebServer) = ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture",
        providerId = f.profile.providerId, httpDebugLoggingEnabled = false)
    private fun server(failed: String? = null, stalled: String? = null) = MockWebServer().apply {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                assertFalse(body.contains("notes.create"))
                val goal = if (body.contains("alpha-time")) "alpha-time" else "beta-time"
                if (goal == failed) return MockResponse().setResponseCode(400).setBody("fixture child failure")
                if (goal == stalled) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                val text = when (counts.computeIfAbsent(goal) { AtomicInteger() }.getAndIncrement()) {
                    0 -> """{"action":"tool","tool":"app.current_time","arguments":{}}"""
                    1 -> """{"action":"complete"}"""
                    else -> """{"style":"compact","tone":"neutral"}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
                    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
            }
        }
        start()
    }
    private suspend fun run(useCase: AgentRunUseCase, server: MockWebServer,
        goals: List<String> = f.specs.map { it.goal }, selection: AgentChildBatchSelection = AgentChildBatchSelection.capture(foreground),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {}) = useCase.runReadOnlyChildBatch("fixture-c", "fixture-m", goals,
            config(server), "只总结验证事实", f.profile, selection, onSnapshot = onSnapshot)
    private fun outcome(snapshot: AgentRunSnapshot) = AgentChildBatchPolicy.decode(
        (snapshot.events.single { it.type == AgentChildBatchPolicy.CONSUMED_EVENT }.metadata as RunEventMetadata.Reason).reason)

    @Test fun twoNativeChildrenAreConsumedExactlyOnceAndParentCompletes() = runBlocking {
        val server = server(); val useCase = f.useCase(foreground); val selection = AgentChildBatchSelection.capture(foreground)
        try {
            val snapshot = withTimeout(20_000) { run(useCase, server, selection = selection) }
            assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
            val consumed = outcome(snapshot)
            assertEquals(listOf("child-1", "child-2"), consumed.children.map { it.slotId })
            assertTrue(consumed.children.all { it.verifiedToolResults == 1 && it.status == AgentRunStatus.COMPLETED })
            assertEquals(consumed.summary(), snapshot.run.result)
            assertEquals(snapshot, f.repo.settleReadOnlyChildParent(snapshot.run.id) { true })
            assertEquals(6, server.requestCount)
            assertTrue(runCatching { run(useCase, server, selection = selection) }.isFailure)
            assertEquals(3, f.db.agentRunDao().getRecentRuns(20).size)
            assertTrue((AgentExecutionBudgetEvidencePolicy.read(requireNotNull(f.repo.runDetail(snapshot.run.id))) as
                AgentExecutionBudgetEvidenceAssessment.Available).snapshot.consumedMs > 0)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun oneChildEntryProducesOneSlotAndNoImplicitSecondGoal() = runBlocking {
        val server = server(); val useCase = f.useCase(foreground)
        try {
            val snapshot = run(useCase, server, goals = listOf("alpha-time"))
            assertEquals(1, outcome(snapshot).children.size)
            assertEquals(2, f.db.agentRunDao().getRecentRuns(20).size)
            assertEquals(3, server.requestCount)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun partialChildFailureKeepsSuccessAndParentCannotClaimComplete() = runBlocking {
        val server = server(failed = "beta-time"); val useCase = f.useCase(foreground)
        try {
            val snapshot = run(useCase, server)
            assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
            assertEquals(listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED), outcome(snapshot).children.map { it.status })
            assertEquals(1, outcome(snapshot).children.first().verifiedToolResults)
            assertTrue(snapshot.run.result!!.contains("完成 1/2"))
            assertEquals(snapshot, f.repo.settleReadOnlyChildParent(snapshot.run.id) { true })
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun foregroundPauseResumeInterruptsBothModelWaitsAndRejectsOldSelection() = runBlocking {
        val server = server(stalled = "alpha-time", failed = "beta-time"); val useCase = f.useCase(foreground)
        val selection = AgentChildBatchSelection.capture(foreground)
        try {
            val task = async(Dispatchers.IO) { runCatching { run(useCase, server, selection = selection) } }
            withTimeout(10_000) { while (server.requestCount == 0) delay(20) }
            foreground.paused(owner); foreground.resumed(owner)
            assertTrue(withTimeout(8_000) { task.await() }.exceptionOrNull() is CancellationException)
            val parent = f.db.agentRunDao().getRecentRuns(10).single { it.parentRunId == null }
            assertEquals(AgentRunStatus.CANCELLED.name, parent.status)
            assertTrue(f.db.agentRunDao().getRecentRuns(10).all { AgentRunStatus.valueOf(it.status).isTerminal })
            assertTrue(f.repo.snapshot(parent.id).events.none { it.type == AgentChildBatchPolicy.CONSUMED_EVENT })
            assertTrue(runCatching { run(useCase, server, selection = selection) }.isFailure)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun parentCancellationIsPersistedBeforeWaitingChildrenStop() = runBlocking {
        val server = server(stalled = "alpha-time", failed = "beta-time"); val useCase = f.useCase(foreground)
        try {
            val task = async(Dispatchers.IO) { runCatching { run(useCase, server) } }
            withTimeout(10_000) { while (server.requestCount == 0) delay(20) }
            val id = f.db.agentRunDao().getRecentRuns(10).single { it.parentRunId == null }.id
            assertTrue(f.repo.requestCancel(id, "用户停止"))
            assertTrue(withTimeout(8_000) { task.await() }.exceptionOrNull() is CancellationException)
            val snapshot = f.repo.snapshot(id)
            assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
            assertNotNull(snapshot.run.cancelRequestedAt)
            assertEquals(snapshot, f.repo.settleReadOnlyChildParent(id) { true })
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun initializationFailureAndPauseBeforeDispatchLeaveManagedTerminalParent() = runBlocking {
        val server = server(); val useCase = f.useCase(foreground)
        try {
            assertTrue(runCatching { run(useCase, server) { error("fixture callback") } }.isFailure)
            assertTrue(runCatching { run(useCase, server) { foreground.paused(owner); yield() } }.exceptionOrNull() is CancellationException)
            assertEquals(0, server.requestCount)
            val parents = f.db.agentRunDao().getRecentRuns(10)
            assertEquals(2, parents.size)
            parents.forEach { parent ->
                assertTrue(AgentRunStatus.valueOf(parent.status).isTerminal)
                val detail = requireNotNull(f.repo.runDetail(parent.id))
                assertTrue(AgentChildLifecyclePolicy.isManagedRun(detail))
                assertEquals(AgentTaskRetryEligibility.NotRetryable, AgentTaskRetryPolicy.evaluate(detail))
            }
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    private suspend fun preparedParent(): Pair<AgentRunRecord, AgentChildCheckpoint> {
        val parent = f.repo.createReadOnlyChildParent("fixture-c", "fixture-m", f.specs.map { it.goal }, f.profile, foreground.capture())
        return parent to f.prepare(parent)
    }
    private suspend fun complete(checkpoint: AgentChildCheckpoint) {
        checkpoint.slots.forEach { f.repo.claimReadOnlyChildRun(it.runId); f.repo.updateRunStatus(it.runId, AgentRunStatus.COMPLETED, "fixture result") }
    }
    @Test fun incompleteOrChangedGoalCannotProduceConsumedParent() = runBlocking {
        val (parent, checkpoint) = preparedParent(); val before = f.repo.snapshot(parent.id)
        assertTrue(runCatching { f.repo.settleReadOnlyChildParent(parent.id) { true } }.isFailure)
        assertEquals(before, f.repo.snapshot(parent.id))
        f.repo.updateRunStatus(checkpoint.slots.first().runId, AgentRunStatus.COMPLETED, "fixture")
        assertTrue(runCatching { f.repo.settleReadOnlyChildParent(parent.id) { true } }.isFailure)
        val another = f.repo.createReadOnlyChildParent("fixture-c", "fixture-m", listOf("different"), f.profile, foreground.capture())
        assertTrue(runCatching { f.prepare(another) }.isFailure)
        assertTrue(f.db.agentRunDao().getRunsByParentRunId(another.id).isEmpty())
    }
    @Test fun consumedMarkerFailureRollsBackParentAndDiskReopenPreservesIdempotence() = runBlocking {
        val (parent, checkpoint) = preparedParent(); complete(checkpoint)
        val before = f.repo.snapshot(parent.id)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER stage289_reject_consume BEFORE INSERT ON run_events WHEN NEW.type = 'multi_agent.results.consumed' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { f.repo.settleReadOnlyChildParent(parent.id) { true } }.isFailure)
        assertEquals(before, f.repo.snapshot(parent.id))
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER stage289_reject_consume")
        val completed = f.repo.settleReadOnlyChildParent(parent.id) { true }
        f.reopen()
        assertEquals(completed, f.repo.settleReadOnlyChildParent(parent.id) { false })
        assertEquals(0, f.repo.closeInterruptedRuns())
    }
    @Test fun cancellationRacingConsumeCommitsExactlyOneParentOutcomeAndKeepsChildSuccess() = runBlocking {
        repeat(8) {
            val (parent, checkpoint) = preparedParent(); complete(checkpoint)
            val children = checkpoint.slots.map { f.repo.snapshot(it.runId) }
            coroutineScope {
                listOf(async(Dispatchers.Default) { f.repo.settleReadOnlyChildParent(parent.id) { true } },
                    async(Dispatchers.Default) { f.repo.requestCancel(parent.id, "竞争取消") }).awaitAll()
            }
            val snapshot = f.repo.snapshot(parent.id)
            assertTrue(snapshot.run.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.CANCELLED))
            assertEquals(if (snapshot.run.status == AgentRunStatus.COMPLETED) 1 else 0,
                snapshot.events.count { it.type == AgentChildBatchPolicy.CONSUMED_EVENT })
            assertEquals(children, checkpoint.slots.map { f.repo.snapshot(it.runId) })
            assertEquals(snapshot, f.repo.settleReadOnlyChildParent(parent.id) { true })
        }
    }
    @Test fun permissionLostWhileReadingResultsCancelsInsteadOfConsumingSuccess() = runBlocking {
        val (parent, checkpoint) = preparedParent(); complete(checkpoint)
        var checks = 0
        val cancelled = f.repo.settleReadOnlyChildParent(parent.id) { ++checks == 1 }
        assertEquals(2, checks)
        assertEquals(AgentRunStatus.CANCELLED, cancelled.run.status)
        assertTrue(cancelled.events.none { it.type == AgentChildBatchPolicy.CONSUMED_EVENT })
        assertTrue(checkpoint.slots.all { f.repo.snapshot(it.runId).run.status == AgentRunStatus.COMPLETED })
    }
    @Test fun parentIdentityIsDeliveredEvenWhenCancellationWinsBeforeFirstSnapshot() = runBlocking {
        val server = server(); val useCase = f.useCase(foreground)
        var id: String? = null
        var snapshots = 0
        try {
            val result = runCatching { useCase.runReadOnlyChildBatch("fixture-c", "fixture-m", listOf("alpha-time"),
                config(server), "事实", f.profile, AgentChildBatchSelection.capture(foreground),
                onParentCreated = { created -> id = created; foreground.paused(owner); yield() }, onSnapshot = { snapshots += 1 }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(0, snapshots)
            assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(requireNotNull(id)).run.status)
            assertEquals(0, server.requestCount)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }
    @Test fun restartBeforePrepareClosesForegroundParentWithoutNativeRecovery() = runBlocking {
        val parent = f.repo.createReadOnlyChildParent("fixture-c", "fixture-m", listOf("alpha-time"), f.profile, foreground.capture())
        f.reopen()
        assertTrue(f.repo.recoverVerifiedToolRuns().isEmpty())
        assertEquals(1, f.repo.closeInterruptedRuns())
        assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(parent.id).run.status)
        assertTrue(f.db.agentRunDao().getRunsByParentRunId(parent.id).isEmpty())
    }
    @Test fun realActivityPauseRevokesBatchEvenAfterActivityResumes() = runBlocking {
        val app = f.context.applicationContext as XiaoLingApplication
        ActivityScenario.launch<MainActivity>(Intent(f.context, MainActivity::class.java)
            .putExtra("com.longdev.xiaoling.DEBUG_PLUGIN_LIFECYCLE_PROBE", true)).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val server = server(stalled = "alpha-time", failed = "beta-time"); val useCase = f.useCase(app.agentPluginForeground)
            try {
                val selection = AgentChildBatchSelection.capture(app.agentPluginForeground)
                val task = async(Dispatchers.IO) { runCatching { run(useCase, server, selection = selection) } }
                withTimeout(10_000) { while (server.requestCount == 0) delay(20) }
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(withTimeout(8_000) { task.await() }.exceptionOrNull() is CancellationException)
                assertEquals(AgentRunStatus.CANCELLED.name, f.db.agentRunDao().getRecentRuns(10).single { it.parentRunId == null }.status)
            } finally { useCase.closeMcpOwners(); server.shutdown() }
        }
    }
    @Test fun explicitRealProviderRunsTwoNativeChildrenUnderActualForeground() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("stage289RealRun") == "true") { "真实验收必须显式选择本方法并提供内存配置" }
        val port = requireNotNull(args.getString("stage289ConfigPort")).toInt().also { require(it in 1024..65535) }
        // long: USB 单次 socket 仅将测试凭据送入内存，正式 Provider、Intent、文件和 Run 审计都不保存密钥。
        val input = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 30_000
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "STAGE289_CONFIG_READY\n") })
            listener.accept().use { socket ->
                socket.soTimeout = 10_000
                val stream = DataInputStream(socket.getInputStream()); val size = stream.readInt().also { require(it in 1..16_384) }
                JSONObject(ByteArray(size).also(stream::readFully).toString(Charsets.UTF_8)).also { socket.getOutputStream().write(1) }
            }
        }
        val model = input.getString("model"); assertEquals("gpt-6-luna", model)
        val app = f.context.applicationContext as XiaoLingApplication
        ActivityScenario.launch<MainActivity>(Intent(f.context, MainActivity::class.java)
            .putExtra("com.longdev.xiaoling.DEBUG_PLUGIN_LIFECYCLE_PROBE", true)).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val useCase = f.useCase(app.agentPluginForeground)
            try {
                val profile = f.profile.copy(model = model)
                val config = ProviderRequestConfig(input.getString("baseUrl"), input.getString("apiKey"), model,
                    providerId = profile.providerId, maxTokens = 2000, httpDebugLoggingEnabled = false)
                val goals = listOf("读取当前时间事实一", "读取当前时间事实二").map {
                    "$it：请用 app.current_time 读取一次当前时间，然后结束。只使用这一个工具，不调用其他工具。"
                }
                val snapshot = withTimeout(70_000) { useCase.runReadOnlyChildBatch("fixture-live", "fixture-live-message", goals,
                    config, "简短总结已验证时间", profile, AgentChildBatchSelection.capture(app.agentPluginForeground)) }
                assertEquals(AgentRunStatus.COMPLETED, snapshot.run.status)
                val consumed = outcome(snapshot)
                assertEquals(2, consumed.children.size)
                consumed.children.forEach { child ->
                    val detail = requireNotNull(f.repo.runDetail(child.runId))
                    assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
                    assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
                    assertEquals(1, child.verifiedToolResults)
                    val frames = f.repo.contextTranscript(child.runId)
                    assertTrue(frames.isNotEmpty()); assertTrue(frames.all { !it.input.toString().contains(config.apiKey) })
                }
                assertEquals(snapshot, f.repo.settleReadOnlyChildParent(snapshot.run.id) { true })
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "STAGE289_LIVE model=$model parent=COMPLETED children=2 calls=2 nativeTime=true roomVerified=true consumed=1 actualForeground=true\n")
                })
            } finally { useCase.closeMcpOwners() }
        }
    }
}
