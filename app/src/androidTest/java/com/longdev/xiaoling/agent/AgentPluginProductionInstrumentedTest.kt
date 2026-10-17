package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentSkillStore
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket

/** long: UUID Room 和临时插件注册表隔离验收写入，生产 UseCase 调真实宿主时间能力。 */
class AgentPluginProductionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "stage288-plugin-execution-${UUID.randomUUID()}.db"
    private lateinit var db: XiaoLingDatabase
    private lateinit var repo: RoomAgentRunRepository
    @Before fun prepare() {
        assertEquals("begonia", android.os.Build.DEVICE)
        db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
        repo = RoomAgentRunRepository(context, db)
    }
    @After fun clean() { db.close(); if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name)) }
    private fun useCase(f: PluginExecutionFixture) = AgentRunUseCase(context, OpenAiCompatibleClient(),
        McpSessionRegistry(object : McpServerStore {
            override fun list() = emptyList<McpServerConfig>()
            override fun get(id: String): McpServerConfig? = null
            override fun upsert(config: McpServerConfig): McpServerConfig = error("夹具不得写正式 MCP")
            override fun delete(id: String): Boolean = error("夹具不得写正式 MCP")
        }), repo, f.plugins, f.foreground, RoomAgentSkillStore(context, db))
    private fun profile(model: String = "fixture") = AgentProfileSnapshot("stage288-plugin", "只读插件验收", "P",
        "stage288-provider", model, ApiMode.CHAT_COMPLETIONS, "按用户目标仅调用 app.current_time，完成后结束。",
        AgentContextPolicy.CURRENT_CONVERSATION, listOf("app.current_time", "notes.create"), emptyList(), false)
    private fun config(server: MockWebServer) = ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture",
        providerId = "stage288-provider", httpDebugLoggingEnabled = false)
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString())
    private fun enqueueSuccess(server: MockWebServer) {
        listOf("""{"action":"tool","tool":"app.current_time","arguments":{}}""",
            """{"action":"complete"}""", """{"style":"compact","tone":"neutral"}""").forEach { server.enqueue(response(it)) }
    }
    private suspend fun run(useCase: AgentRunUseCase, f: PluginExecutionFixture, server: MockWebServer,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {}) = useCase.run("fixture-conversation", "fixture-message", "读取一次当前时间",
        config = config(server), summarySystemPrompt = "简短总结已验证时间", agentProfile = profile(),
        memoryRecallEnabled = false, pluginSelection = f.selection(), onSnapshot = onSnapshot)

    @Test fun productionUseCaseExecutesNativeTimeWithAtomicIdentityAndTypedRoomLedger() = runBlocking {
        val f = PluginExecutionFixture(); val useCase = useCase(f); val server = MockWebServer(); server.start(); enqueueSuccess(server)
        try {
            val summary = withTimeout(20_000) { run(useCase, f, server) }
            val detail = requireNotNull(repo.runDetail(summary.runId))
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            assertEquals(1, detail.snapshot.events.count { it.type == AgentPluginExecutionPolicy.SELECTION_EVENT })
            val evidence = JSONObject((detail.snapshot.events.single { it.type == AgentPluginExecutionPolicy.SELECTION_EVENT }.metadata as RunEventMetadata.Reason).reason)
            assertEquals(f.confirmation.manifestSha256, evidence.getString("manifestSha256"))
            assertEquals(listOf("app.current_time"), (0 until evidence.getJSONArray("capabilities").length()).map { evidence.getJSONArray("capabilities").getString(it) })
            assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
            assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
            assertTrue(detail.toolLedger.results.single().content.isNotBlank())
            assertEquals(3, server.requestCount)
            val wire = JSONObject(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()).getJSONArray("messages").toString()
            assertFalse(wire.contains("notes.create")); assertFalse(wire.contains(f.signed.manifest.description))
            assertTrue(runCatching { useCase.run("c", "m", "重试", config = config(server), summarySystemPrompt = "样式",
                agentProfile = profile(), retryOfRunId = summary.runId) }.isFailure)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }

    @Test fun modelWaitDisableCancelsRoomRunWithoutLateSuccess() = runBlocking {
        val f = PluginExecutionFixture(); val useCase = useCase(f); val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            val job = async(Dispatchers.IO) { runCatching { run(useCase, f, server) } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            f.plugins.setEnabled("signed.read", false); f.plugins.setEnabled("signed.read", true)
            assertTrue(withTimeout(5_000) { job.await() }.exceptionOrNull() is CancellationException)
            val id = db.agentRunDao().getRecentRuns(1).single().id
            assertEquals(AgentRunStatus.CANCELLED, repo.snapshot(id).run.status)
            assertTrue(repo.toolLedger(id).results.isEmpty())
            repo.updateRunStatus(id, AgentRunStatus.COMPLETED, result = "迟到成功")
            assertEquals(AgentRunStatus.CANCELLED, repo.snapshot(id).run.status)
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }

    @Test fun cancellationDuringInitializationAndThrowingSnapshotBothSettleRun() = runBlocking {
        val f = PluginExecutionFixture(); val useCase = useCase(f); val server = MockWebServer(); server.start()
        try {
            val result = runCatching { run(useCase, f, server) { snapshot ->
                if (snapshot.run.status == AgentRunStatus.QUEUED) {
                    f.foreground.paused(f.owner); yield()
                }
            } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(AgentRunStatus.CANCELLED.name, db.agentRunDao().getRecentRuns(1).single().status)
            f.foreground.resumed(f.owner)
            assertTrue(runCatching { run(useCase, f, server) { error("fixture snapshot failed") } }.isFailure)
            assertEquals(AgentRunStatus.FAILED.name, db.agentRunDao().getRecentRuns(1).single().status)
            assertEquals(0, server.requestCount)
            db.agentRunDao().getRecentRuns(2).forEach { assertTrue(AgentPluginExecutionPolicy.isPluginRun(requireNotNull(repo.runDetail(it.id)))) }
        } finally { useCase.closeMcpOwners(); server.shutdown() }
    }

    @Test fun markerInsertionFailureRollsBackRunAndCreatedEvent() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_plugin_marker BEFORE INSERT ON run_events WHEN NEW.type = 'plugin.execution.selected' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { repo.createPluginRun("c", "m", "fixture", "{}") }.isFailure)
        assertTrue(db.agentRunDao().getRecentRuns(10).isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM run_events").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun restartClosesPluginRunAndDoesNotOfferNativeRecoveryOrReplay() = runBlocking {
        val run = repo.createPluginRun("c", "m", "fixture", "damaged-audit")
        repo.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        db.close()
        db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
        repo = RoomAgentRunRepository(context, db)
        val useCase = useCase(PluginExecutionFixture())
        try {
            assertTrue(useCase.recoverCommittedToolRuns().isEmpty()); assertTrue(useCase.recoverVerifiedToolRuns().isEmpty())
            assertEquals(1, useCase.closeInterruptedRuns())
            val detail = requireNotNull(repo.runDetail(run.id))
            assertEquals(AgentRunStatus.CANCELLED, detail.snapshot.run.status)
            assertEquals(AgentRunRestartDispositionCode.PLUGIN_FOREGROUND_SELECTION_EXPIRED, detail.latestRecoveryMetadata()!!.restartDisposition!!.code)
            assertEquals(AgentTaskRetryEligibility.NotRetryable, AgentTaskRetryPolicy.evaluate(detail))
            assertTrue(useCase.requalifyNotCommittedReplay(detail) is AgentNotCommittedReplayQualificationAssessment.Ineligible)
            assertEquals(0, useCase.closeInterruptedRuns())
        } finally { useCase.closeMcpOwners() }
    }

    @Test fun realActivityPauseResumeRevokesOldForegroundSelection() {
        val app = context.applicationContext as XiaoLingApplication
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra("com.longdev.xiaoling.DEBUG_PLUGIN_LIFECYCLE_PROBE", true)).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val revision = app.agentPluginForeground.capture()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(app.agentPluginForeground.isCurrent(revision))
            assertFalse(app.agentPluginForeground.state.value.resumed)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse(app.agentPluginForeground.isCurrent(revision))
            assertTrue(app.agentPluginForeground.state.value.resumed)
            assertTrue(app.agentPluginForeground.isCurrent(app.agentPluginForeground.capture()))
        }
    }

    @Test fun explicitRealProviderUsesSignedSelectionAndNativeTimeLedger() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式真实模型验收执行", args.getString("stage288RealRun") == "true")
        val port = requireNotNull(args.getString("stage288ConfigPort")).toInt().also { require(it in 1024..65535) }
        // long: 凭据通过单次 USB socket 进入内存，既不读取正式 Provider，也不放进 Intent、文件、快照或日志。
        val input = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 30_000
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "STAGE288_CONFIG_READY\n") })
            listener.accept().use { socket ->
                socket.soTimeout = 10_000
                val stream = DataInputStream(socket.getInputStream()); val size = stream.readInt()
                require(size in 1..16_384)
                val bytes = ByteArray(size).also(stream::readFully)
                JSONObject(bytes.toString(Charsets.UTF_8)).also { socket.getOutputStream().write(1) }
            }
        }
        val model = input.getString("model"); assertEquals("gpt-6-luna", model)
        val config = ProviderRequestConfig(input.getString("baseUrl"), input.getString("apiKey"), model,
            providerId = "stage288-provider", streamingEnabled = false, maxTokens = 2000, httpDebugLoggingEnabled = false)
        val f = PluginExecutionFixture(); val useCase = useCase(f)
        try {
            val summary = withTimeout(70_000) { useCase.run("fixture-live", "fixture-live-message",
                "请用 app.current_time 读取一次当前时间，然后结束。只使用这一个工具，不调用其他工具。",
                config = config, summarySystemPrompt = "简短总结已验证时间", agentProfile = profile(model),
                memoryRecallEnabled = false, pluginSelection = f.selection()) }
            val detail = requireNotNull(repo.runDetail(summary.runId))
            assertEquals(AgentRunStatus.COMPLETED, summary.status)
            assertTrue(AgentPluginExecutionPolicy.isPluginRun(detail))
            assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
            assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
            assertTrue(repo.contextTranscript(summary.runId).isNotEmpty())
            assertTrue(repo.contextTranscript(summary.runId).all { !it.input.toString().contains(config.apiKey) })
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "STAGE288_LIVE model=$model status=${summary.status} calls=1 nativeTime=true roomVerified=true\n")
            })
        } finally { useCase.closeMcpOwners() }
    }
}
