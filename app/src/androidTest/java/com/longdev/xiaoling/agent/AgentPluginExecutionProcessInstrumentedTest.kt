package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** long: 外部逐轮重启独立进程，仅共享指定 UUID 的测试账本，旧许可不持久化。 */
class AgentPluginExecutionProcessInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(): Fixture {
        assertEquals("begonia", android.os.Build.DEVICE)
        val uuid = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("pluginExecutionUuid")))
        return Fixture("stage288-plugin-execution-process-$uuid")
    }
    private inner class Fixture(val name: String) : AutoCloseable {
        val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, "$name.db").addMigrations(*XiaoLingDatabase.migrations()).build()
        val repo = RoomAgentRunRepository(context, db)
        val plugins = AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(context, name))
        val foreground = AgentPluginForegroundState()
        fun gateway(): AgentPluginToolRegistry {
            val signed = requireNotNull(plugins.get("signed.read")!!.signedManifest)
            val selection = AgentPluginExecutionSelection.capture(plugins, foreground, "signed.read", AgentPluginSigning.verify(signed).manifestSha256)
            val native = XiaoLingToolRegistry(clock = SystemAgentClock(), conversationStore = RoomAgentConversationStore(context, db),
                noteStore = RoomAgentNoteStore(context, db), memoryStore = RoomAgentMemoryStore(context, db),
                knowledgeStore = RoomKnowledgeDocumentStore(context, db))
            return AgentPluginToolRegistry(native, plugins, foreground, selection, setOf("app.current_time"))
        }
        fun ledger(g: AgentPluginToolRegistry) = object : AgentRunLedger by repo {
            override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?, parentRunId: String?) =
                repo.createPluginRun(conversationId, userMessageId, goal, g.evidenceJson())
        }
        override fun close() { db.close() }
        fun delete() { close(); check(context.deleteDatabase("$name.db")); check(context.deleteSharedPreferences(name)) }
    }
    private fun model() = object : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) = pluginTimeCall()
        override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) = """{"style":"compact","tone":"neutral"}"""
    }
    private fun report(phase: String, cleaned: Boolean = false) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("pluginExecutionPid", Process.myPid().toString())
        if (cleaned) putString("pluginExecutionCleaned", "true")
        putString("stream", "STAGE288_PROCESS phase=$phase pid=${Process.myPid()}\n")
    })
    @Test fun seedVerifiedPluginBeforeProcessExit() = runBlocking {
        fixture().use { f ->
            assertTrue(f.plugins.list().isEmpty()); assertTrue(f.db.agentRunDao().getRecentRuns(1).isEmpty())
            val signed = AgentPluginSigningFixture.sign(AgentPluginSigningFixture.manifest().copy(capabilities = setOf("app.current_time")))
            f.plugins.installSigned(signed, AgentPluginSigning.verify(signed)); f.plugins.setEnabled("signed.read", true)
            f.foreground.resumed(this); val g = f.gateway()
            val runtime = MinimalAgentRuntime(f.ledger(g), g, model(), faultInjector = object : AgentRuntimeFaultInjector {
                override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) { throw AgentProcessTerminationSimulation() }
            })
            assertTrue(runCatching { g.guardRun { runtime.run("fixture-c", "fixture-m", "读取时间") } }.exceptionOrNull() is AgentProcessTerminationSimulation)
            val id = f.db.agentRunDao().getRecentRuns(1).single().id
            assertEquals(AgentRunStatus.VERIFYING, f.repo.snapshot(id).run.status)
            assertEquals(ToolVerificationStatus.PASSED, f.repo.toolLedger(id).results.single().verificationStatus)
            report("seed-verified")
        }
    }
    @Test fun restartRejectsOldForegroundAndConvergesWithoutNativeResume() = runBlocking {
        fixture().use { f ->
            assertTrue(runCatching { f.gateway() }.isFailure)
            val id = f.db.agentRunDao().getRecentRuns(1).single().id
            val before = f.repo.toolLedger(id)
            assertEquals(AgentRunResumeKind.RESTART_REQUIRED, AgentRunResumePolicy.assess(requireNotNull(f.repo.runDetail(id))).kind)
            assertTrue(f.repo.recoverVerifiedToolRuns().isEmpty())
            assertEquals(1, f.repo.closeInterruptedRuns())
            assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(id).run.status)
            assertEquals(before, f.repo.toolLedger(id)); report("restarted-cancelled")
        }
    }
    @Test fun newExplicitSelectionRunsAndPreservesOldTerminalEvidence() = runBlocking {
        val f = fixture()
        var completed = false
        try {
            val old = f.db.agentRunDao().getRecentRuns(1).single().id
            val before = f.repo.toolLedger(old)
            assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(old).run.status)
            f.foreground.resumed(this); val g = f.gateway()
            val summary = g.guardRun { MinimalAgentRuntime(f.ledger(g), g, model()).run("fixture-c", "fixture-m-new", "再次读取时间") }
            assertEquals(AgentRunStatus.COMPLETED, summary.status)
            assertNotEquals(old, summary.runId); assertEquals(before, f.repo.toolLedger(old))
            assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(old).run.status)
            assertEquals(ToolVerificationStatus.PASSED, f.repo.toolLedger(summary.runId).results.single().verificationStatus)
            completed = true
        } finally { if (completed) f.delete() else f.close() }
        report("new-selection-completed", cleaned = true)
    }
}
