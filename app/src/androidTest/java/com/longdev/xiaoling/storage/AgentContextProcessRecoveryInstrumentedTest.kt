package com.longdev.xiaoling.storage

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 三轮独立 instrumentation 用同一 UUID 数据库；缺少显式编排参数时不触碰任何正式数据库。 */
@RunWith(AndroidJUnit4::class)
class AgentContextProcessRecoveryInstrumentedTest {
    private fun fixture(): AgentContextRecoveryFixture {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("需要独立进程编排参数", args.containsKey("contextRecoveryDatabase"))
        assertEquals("begonia", android.os.Build.DEVICE)
        return AgentContextRecoveryFixture(ApplicationProvider.getApplicationContext<Context>(), requireNotNull(args.getString("contextRecoveryDatabase")))
    }
    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }

    @Test fun seedDurableBoundaries() = runBlocking {
        fixture().use { fixture ->
            val manifest = JSONObject()
            for (mode in listOf("valid", "source", "missing", "cancel", "halfPlan")) {
                val id = fixture.seed(mode == "halfPlan")
                manifest.put(mode, fixture.manifest(id))
                when (mode) {
                    "source" -> fixture.db.openHelper.writableDatabase.execSQL("UPDATE agent_context_compressions SET historyStart = historyStart + 1 WHERE runId = ?", arrayOf(id))
                    "missing" -> fixture.db.openHelper.writableDatabase.execSQL("DELETE FROM agent_context_snapshots WHERE runId = ?", arrayOf(id))
                    "cancel" -> assertTrue(fixture.repo.requestCancel(id, "独立进程取消"))
                }
            }
            report("contextRecoveryManifest", manifest.toString())
            report("contextRecoveryPid", Process.myPid().toString())
        }
    }

    @Test fun restoreUsingProductionRecoveryWithoutToolOrModelReplay() = runBlocking {
        fixture().use { fixture ->
            val manifest = JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("contextRecoveryManifest")))
            val id = manifest.getJSONObject("valid").getString("runId")
            assertEquals(manifest.getJSONObject("valid").toString(), fixture.manifest(id).toString())
            assertTrue(fixture.repo.contextCompressions(id).single().sourceInput.messages.last().content.contains("原文中间事实"))
            assertFalse(fixture.repo.contextTranscript(id).last().input.messages.last().content.contains("原文中间事实"))
            val before = fixture.repo.snapshot(id)
            val first = fixture.repo.recoverVerifiedToolRuns(setOf(id)).single()
            assertEquals(first, fixture.repo.recoverVerifiedToolRuns(setOf(id)).single())
            assertEquals(0, fixture.repo.closeInterruptedRuns(runIds = setOf(id)))
            val summary = fixture.useCase().resumeVerifiedToolRun(first)
            assertEquals(id, summary.runId)
            assertEquals(AgentRunStatus.COMPLETED, fixture.repo.snapshot(id).run.status)
            assertEquals(manifest.getJSONObject("valid").toString(), fixture.manifest(id).toString())
            val after = fixture.repo.snapshot(id)
            assertEquals(before.events.count { it.type == AgentEventTypes.LLM_REQUEST_COMPLETED }, after.events.count { it.type == AgentEventTypes.LLM_REQUEST_COMPLETED })
            assertEquals(3, after.events.count { it.type == "tool.verify" })
            assertEquals(1, after.events.count { it.type == "run.recovered" })
            assertTrue(runCatching { fixture.useCase().resumeVerifiedToolRun(first) }.isFailure)
            assertEquals(after, fixture.repo.snapshot(id))
            for (mode in listOf("source", "missing", "cancel", "halfPlan")) {
                val runId = manifest.getJSONObject(mode).getString("runId")
                val ledger = fixture.repo.toolLedger(runId)
                val detail = requireNotNull(fixture.repo.runDetail(runId))
                if (mode in listOf("source", "missing")) {
                    assertEquals(AgentContextRecoveryStatus.INVALID, detail.contextRecoveryStatus)
                    assertEquals(AgentRunRestartDispositionCode.CONTEXT_EVIDENCE_INVALID, AgentRunResumePolicy.assess(detail).restartDisposition?.code)
                    var entered = false
                    assertTrue(runCatching { fixture.repo.withRecoveryExecution(runId) { entered = true } }.isFailure)
                    assertFalse(entered)
                }
                if (mode == "halfPlan") {
                    assertEquals(AgentContextRecoveryStatus.VALIDATED, detail.contextRecoveryStatus)
                    assertEquals(AgentRunStatus.THINKING, detail.snapshot.run.status)
                    assertEquals(AgentRunResumeKind.RESTART_REQUIRED, AgentRunResumePolicy.assess(detail).kind)
                    assertEquals(2, ledger.results.size)
                }
                assertTrue(fixture.repo.recoverVerifiedToolRuns(setOf(runId)).isEmpty())
                assertEquals(1, fixture.repo.closeInterruptedRuns(runIds = setOf(runId)))
                assertEquals(ledger, fixture.repo.toolLedger(runId))
                assertEquals(0, fixture.repo.closeInterruptedRuns(runIds = setOf(runId)))
            }
            report("contextRecoveryPid", Process.myPid().toString())
        }
    }

    @Test fun verifySettledEvidenceAndCleanOnlyFixture() = runBlocking {
        val fixture = fixture()
        try {
            val manifest = JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("contextRecoveryManifest")))
            for (mode in listOf("valid", "source", "missing", "cancel", "halfPlan")) {
                val id = manifest.getJSONObject(mode).getString("runId")
                assertEquals(if (mode == "valid") AgentRunStatus.COMPLETED else AgentRunStatus.CANCELLED, fixture.repo.snapshot(id).run.status)
                assertEquals(if (mode == "halfPlan") 2 else 3, fixture.repo.toolLedger(id).results.size)
                assertTrue(fixture.repo.recoverVerifiedToolRuns(setOf(id)).isEmpty())
                assertEquals(0, fixture.repo.closeInterruptedRuns(runIds = setOf(id)))
                if (mode == "valid") assertEquals(manifest.getJSONObject(mode).toString(), fixture.manifest(id).toString())
            }
            report("contextRecoveryPid", Process.myPid().toString())
            fixture.delete()
            assertFalse(fixture.context.getDatabasePath(fixture.name).exists())
            report("contextRecoveryCleaned", "true")
        } finally { fixture.close() }
    }
}
