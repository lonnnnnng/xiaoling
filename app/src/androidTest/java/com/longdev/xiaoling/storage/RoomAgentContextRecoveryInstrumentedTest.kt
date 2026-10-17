package com.longdev.xiaoling.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import com.longdev.xiaoling.model.ProviderRequestConfig
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomAgentContextRecoveryInstrumentedTest {
    private fun fixture(): AgentContextRecoveryFixture {
        assertEquals("begonia", android.os.Build.DEVICE)
        return AgentContextRecoveryFixture(ApplicationProvider.getApplicationContext<Context>(), "stage287-recovery-${UUID.randomUUID()}.db")
    }

    @Test fun capturedMissingCorruptOrOrphanContextRejectsRecoveryAndRetainsLedger() = runBlocking {
        val fixture = fixture()
        try {
            for (corruption in listOf("source", "allFrames", "anchor", "anchorType", "frameType", "utf8", "result", "tailFrame")) {
                val id = fixture.seed()
                val before = fixture.repo.toolLedger(id)
                val sql = fixture.db.openHelper.writableDatabase
                when (corruption) {
                    "source" -> sql.execSQL("DELETE FROM agent_context_compressions WHERE runId = ?", arrayOf(id))
                    "allFrames" -> sql.execSQL("DELETE FROM agent_context_snapshots WHERE runId = ?", arrayOf(id))
                    "anchor" -> sql.execSQL("UPDATE run_events SET metadataJson = '{}' WHERE runId = ? AND type = 'context.snapshot.captured'", arrayOf(id))
                    "anchorType" -> {
                        val marker = fixture.db.agentRunDao().getEvents(id).first { it.type == "context.snapshot.captured" }
                        sql.execSQL("UPDATE run_events SET metadataJson = ? WHERE id = ?", arrayOf(JSONObject(marker.metadataJson!!).put("sequence", "1").toString(), marker.id))
                    }
                    "frameType", "utf8" -> {
                        val frame = fixture.db.agentRunDao().getContextSnapshots(id).first()
                        // long: ASCII JSON 中的孤立代理字符逃逸可通过存储字节检查，解码后仍须按内容损坏拒绝，不能中止后续 Run 恢复。
                        val payload = if (corruption == "utf8") frame.payloadJson.replace("读取三个隔离事实", "\\ud800")
                            else JSONObject(frame.payloadJson).put("model", 123).toString()
                        val hash = AgentContextSnapshotCodec.recordHash(id, frame.stepId, frame.sequence, payload, frame.compressionVersion)
                        sql.execSQL("UPDATE agent_context_snapshots SET payloadJson = ?, payloadBytes = ?, recordSha256 = ? WHERE runId = ? AND sequence = 1",
                            arrayOf<Any>(payload, AgentContextSnapshotCodec.utf8Size(payload), hash, id))
                    }
                    "result" -> sql.execSQL("UPDATE agent_tool_results SET content = 'changed' WHERE runId = ? AND toolCallId = ?", arrayOf(id, before.results.first().toolCallId))
                    "tailFrame" -> sql.execSQL("DELETE FROM agent_context_snapshots WHERE runId = ? AND sequence = 3", arrayOf(id))
                }
                val brokenLedger = fixture.repo.toolLedger(id)
                val detail = requireNotNull(fixture.repo.runDetail(id))
                assertEquals(AgentContextRecoveryStatus.INVALID, detail.contextRecoveryStatus)
                assertEquals(AgentRunRestartDispositionCode.CONTEXT_EVIDENCE_INVALID, AgentRunResumePolicy.assess(detail).restartDisposition?.code)
                assertTrue(fixture.repo.recoverVerifiedToolRuns(setOf(id)).isEmpty())
                assertTrue(runCatching { fixture.useCase().resumeVerifiedToolRun(detail) }.isFailure)
                assertEquals(1, fixture.repo.closeInterruptedRuns(runIds = setOf(id)))
                assertEquals(brokenLedger, fixture.repo.toolLedger(id))
                assertEquals(AgentRunStatus.CANCELLED, fixture.repo.snapshot(id).run.status)
                assertEquals(0, fixture.repo.closeInterruptedRuns(runIds = setOf(id)))
            }
        } finally { fixture.delete() }
    }

    @Test fun twoRepositoriesDeduplicateMarkersAndOnlyOneProductionRecoveryCompletes() = runBlocking {
        val fixture = fixture()
        try {
            val id = fixture.seed()
            val second = RoomAgentRunRepository(fixture.context, fixture.db)
            val details = listOf(fixture.repo, second).map { repo -> async(Dispatchers.IO) { repo.recoverVerifiedToolRuns(setOf(id)).single() } }.awaitAll()
            assertEquals(1, fixture.repo.snapshot(id).events.count { it.type == "run.recovered" })
            val before = fixture.manifest(id).toString()
            val useCases = listOf(fixture.useCase(), fixture.useCase(second))
            val results = useCases.mapIndexed { index, useCase -> async(Dispatchers.IO) { runCatching { useCase.resumeVerifiedToolRun(details[index]) } } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(AgentRunStatus.COMPLETED, fixture.repo.snapshot(id).run.status)
            assertEquals(before, fixture.manifest(id).toString())
            assertEquals(1, fixture.repo.snapshot(id).events.count { it.type == AgentEventTypes.RECOVERY_SUMMARY })
        } finally { fixture.delete() }
    }

    @Test fun approvalChecksFreshContextCancellationAndRequestBeforeConsumingConfirmation() = runBlocking {
        val fixture = fixture()
        try {
            for (mode in listOf("valid", "cancel", "missing", "stale")) {
                val (id, request) = fixture.seedApproval()
                assertEquals(1, fixture.repo.recoverPendingApprovalRuns(setOf(id)).size)
                when (mode) {
                    "cancel" -> fixture.repo.requestCancel(id, "隔离取消")
                    "missing" -> fixture.db.openHelper.writableDatabase.execSQL("DELETE FROM agent_context_snapshots WHERE runId = ?", arrayOf(id))
                }
                val confirmed = if (mode == "stale") request.copy(arguments = mapOf("value" to "stale")) else request
                val approved = fixture.repo.approveRecoveredRequest(confirmed, "显式确认")
                assertEquals(mode == "valid", approved != null)
                val status = requireNotNull(fixture.repo.runDetail(id)).approvals.single().status
                assertEquals(if (mode == "valid") ApprovalRequestStatus.APPROVED else ApprovalRequestStatus.PENDING, status)
                assertNull(fixture.repo.approveRecoveredRequest(confirmed, "重复确认"))
            }
        } finally { fixture.delete() }
    }

    @Test fun anchorWriteFailureRollsBackSnapshotAndRetryDoesNotCreateHole() = runBlocking {
        val fixture = fixture()
        try {
            val run = fixture.repo.createRun("fixture", "fixture", "原子保存")
            val step = fixture.repo.appendStep(run.id, AgentStepTypes.LLM_PLAN, "规划", "夹具", AgentStepStatus.RUNNING)
            val input = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS", listOf(AgentContextMessage("system", "约束"), AgentContextMessage("user", "空历史")), emptyList())
            val sql = fixture.db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_context_anchor BEFORE INSERT ON run_events WHEN NEW.type = 'context.snapshot.captured' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertTrue(runCatching { fixture.repo.appendContextSnapshot(run.id, step.id, input) }.isFailure)
            assertTrue(fixture.repo.contextTranscript(run.id).isEmpty())
            sql.execSQL("DROP TRIGGER reject_context_anchor")
            fixture.repo.appendContextSnapshot(run.id, step.id, input)
            fixture.repo.appendContextSnapshot(run.id, step.id, input)
            assertEquals(listOf(1), fixture.repo.contextTranscript(run.id).map { it.sequence })
            assertEquals(1, fixture.repo.snapshot(run.id).events.count { it.type == "context.snapshot.captured" })
        } finally { fixture.delete() }
    }

    @Test fun approvalFromAnotherRunIsRejectedBeforeEitherConfirmationIsConsumed() = runBlocking {
        val fixture = fixture()
        try {
            val (id, request) = fixture.seedApproval()
            val (otherId, otherRequest) = fixture.seedApproval()
            val detail = requireNotNull(fixture.repo.runDetail(id))
            val error = runCatching { fixture.useCase().resumeApprovedRun(detail, otherRequest,
                ProviderRequestConfig("http://127.0.0.1:1/v1", "fixture-key", "fixture", httpDebugLoggingEnabled = false), "样式", approvalReason = "旧确认") }.exceptionOrNull()
            assertEquals("审批请求不属于当前 Agent Run", error?.message)
            assertEquals(ApprovalRequestStatus.PENDING, requireNotNull(fixture.repo.runDetail(id)).approvals.single { it.id == request.id }.status)
            assertEquals(ApprovalRequestStatus.PENDING, requireNotNull(fixture.repo.runDetail(otherId)).approvals.single().status)
        } finally { fixture.delete() }
    }
}
