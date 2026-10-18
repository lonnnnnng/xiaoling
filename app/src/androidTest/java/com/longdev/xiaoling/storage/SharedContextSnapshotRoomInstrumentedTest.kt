package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** long: 用旧 JSONObject/Java 摘要直接植入 UUID Room，证明升级后保留原文且格式损坏不能逃出恢复门禁。 */
class SharedContextSnapshotRoomInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private suspend fun fixture(transform: (String) -> String, check: suspend (XiaoLingDatabase, RoomAgentRunRepository, String, String, String) -> Unit) {
        assertEquals("begonia", android.os.Build.DEVICE)
        val name = "stage291-context-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).build()
        try {
            val repo = RoomAgentRunRepository(context, db)
            val run = repo.createRun("fixture-chat", "fixture-message", "兼容旧输入")
            repo.updateRunStatus(run.id, AgentRunStatus.THINKING)
            val step = repo.appendStep(run.id, AgentStepTypes.LLM_PLAN, "规划", "旧格式帧", AgentStepStatus.RUNNING)
            val raw = transform(ContextSnapshotCompatibilityFixtures.oldWire())
            // long: 旧版本没有 capture marker，直接保存合法旧帧；摘要不能由本轮共享函数生成。
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_context_snapshots (runId, sequence, stepId, payloadJson, recordSha256, payloadBytes, createdAt, compressionVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(run.id, 1, step.id, raw, ContextSnapshotCompatibilityFixtures.oldHash(run.id, step.id, 1, raw), raw.toByteArray(Charsets.UTF_8).size, 1_000L, null))
            check(db, repo, run.id, step.id, raw)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun raw(db: XiaoLingDatabase, runId: String): String = db.openHelper.readableDatabase
        .query("SELECT payloadJson FROM agent_context_snapshots WHERE runId = ?", arrayOf(runId)).use { cursor ->
            assertTrue(cursor.moveToFirst()); cursor.getString(0)
        }

    @Test fun oldFrameIsReadableAndSameStepStaysByteIdenticalAndIdempotent() = runBlocking {
        fixture({ it }) { db, repo, run, step, original ->
            assertEquals(ContextSnapshotCompatibilityFixtures.input(), repo.contextTranscript(run).single().input)
            assertEquals(AgentContextRecoveryStatus.VALIDATED, repo.runDetail(run)!!.contextRecoveryStatus)
            repo.appendContextSnapshot(run, step, ContextSnapshotCompatibilityFixtures.input())
            assertEquals(original, raw(db, run))
            assertEquals(1, repo.contextTranscript(run).size)
        }
    }

    @Test fun legacyWhitespaceIsReadWithoutRewriteAndDifferentWireCannotOverwriteStep() = runBlocking {
        fixture({ " \n$it \n" }) { db, repo, run, step, original ->
            assertEquals(ContextSnapshotCompatibilityFixtures.input(), repo.contextTranscript(run).single().input)
            assertTrue(runCatching { repo.appendContextSnapshot(run, step, ContextSnapshotCompatibilityFixtures.input()) }.isFailure)
            assertEquals(original, raw(db, run))
        }
    }

    @Test fun duplicateIdentityWithValidRawHashIsInvalidAndNeverSilentlyRepaired() = runBlocking {
        fixture({ it.replace("\"model\":", "\"model\":\"foreign\",\"model\":") }) { db, repo, run, step, original ->
            assertEquals(AgentContextRecoveryStatus.INVALID, repo.runDetail(run)!!.contextRecoveryStatus)
            var actions = 0
            assertTrue(runCatching { repo.withRecoveryExecution(run) { actions++; Unit } }.isFailure)
            assertEquals(0, actions)
            assertTrue(runCatching { repo.contextTranscript(run) }.isFailure)
            assertTrue(runCatching { repo.appendContextSnapshot(run, step, ContextSnapshotCompatibilityFixtures.input()) }.isFailure)
            assertEquals(original, raw(db, run))
        }
    }
}
