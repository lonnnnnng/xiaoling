package com.longdev.xiaoling.storage

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** long: 三个独立进程共享本轮 UUID 数据库，证明已提交 outbox 可恢复，而非把同一对象重建当作进程证据。 */
@RunWith(AndroidJUnit4::class)
class MemoryMaintenanceProcessInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val name: String get() {
        assertEquals("begonia", android.os.Build.DEVICE)
        val value = requireNotNull(args.getString("maintenanceDatabase"))
        val uuid = value.removePrefix("stage287-maintenance-process-").removeSuffix(".db")
        require(value == "stage287-maintenance-process-${UUID.fromString(uuid)}.db")
        return value
    }
    private fun open() = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
    private fun report(manifest: JSONObject? = null, cleaned: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("memoryMaintenancePid", Process.myPid().toString())
            manifest?.let { putString("memoryMaintenanceManifest", it.toString()) }
            if (cleaned) putString("memoryMaintenanceCleaned", "true")
        })
    }
    private fun manifest() = JSONObject(requireNotNull(args.getString("maintenanceManifest")))

    @Test fun seedPendingOutboxAndUnchangedUserFact() = runBlocking {
        val db = open()
        try {
            val now = System.currentTimeMillis()
            val memory = RoomAgentMemoryStore(context, db, File(context.cacheDir, "$name.undo"))
                .remember("正式隔离事实", "fixture", "Episode", AgentMemorySource(null, null, "fixture"), 0.8)
            db.agentRunDao().upsertRun(AgentRunEntity("fixture-run", null, "fixture-conversation", "fixture-message", "隔离目标",
                "COMPLETED", "隔离结果", null, now - 10, now - 1, now - 1))
            db.openHelper.writableDatabase.execSQL("INSERT INTO messages (id,conversationId,role,text,createdAt,origin) VALUES ('fixture-message','fixture-conversation','user','我喜欢中文回答',?,'USER')", arrayOf(now - 10))
            val store = RoomMemoryMaintenanceStore(db)
            val request = store.begin(now)
            assertTrue(store.scanOne(request.requestId))
            assertTrue(db.agentMemoryDao().listCandidates(100).isEmpty())
            val row = requireNotNull(db.memoryMaintenanceDao().outbox("fixture-run"))
            report(JSONObject().put("requestId", request.requestId).put("memoryId", memory.id)
                .put("factHash", MemoryMaintenancePolicy.hash(memory.toString())).put("outboxHash", row.payloadHash)
                .put("cursorAt", store.current()?.cursorAt).put("cursorRunId", store.current()?.cursorRunId))
        } finally { db.close() }
    }

    @Test fun restorePendingOutboxWithoutRepeatingCandidateOrChangingFact() = runBlocking {
        val db = open()
        try {
            val expected = manifest()
            val store = RoomMemoryMaintenanceStore(db)
            assertEquals(expected.getString("requestId"), store.current()?.requestId)
            assertEquals(expected.getLong("cursorAt"), store.current()?.cursorAt)
            assertEquals(expected.getString("cursorRunId"), store.current()?.cursorRunId)
            assertEquals(expected.getString("outboxHash"), db.memoryMaintenanceDao().outbox("fixture-run")?.payloadHash)
            assertTrue(store.deliverOne(expected.getString("requestId")))
            assertFalse(store.deliverOne(expected.getString("requestId")))
            assertFalse(store.scanOne(expected.getString("requestId")))
            store.complete(expected.getString("requestId"))
            assertEquals(1, db.agentMemoryDao().listCandidates(100).size)
            val facts = RoomAgentMemoryStore(context, db, File(context.cacheDir, "$name.undo"))
            assertEquals(expected.getString("factHash"), MemoryMaintenancePolicy.hash(requireNotNull(facts.get(expected.getString("memoryId"))).toString()))
            report()
        } finally { db.close() }
    }

    @Test fun verifyDecisionsAndRemoveOnlySuccessfulFixture() = runBlocking {
        val db = open()
        try {
            val expected = manifest()
            val store = RoomMemoryMaintenanceStore(db)
            assertEquals("SUCCEEDED", store.current()?.status)
            assertEquals("DELIVERED", db.memoryMaintenanceDao().outbox("fixture-run")?.deliveryStatus)
            assertEquals(expected.getString("outboxHash"), db.memoryMaintenanceDao().outbox("fixture-run")?.payloadHash)
            assertEquals(1, db.agentMemoryDao().listCandidates(100).size)
            val facts = RoomAgentMemoryStore(context, db, File(context.cacheDir, "$name.undo"))
            assertEquals(expected.getString("factHash"), MemoryMaintenancePolicy.hash(requireNotNull(facts.get(expected.getString("memoryId"))).toString()))
            assertEquals("COMPLETED", db.agentRunDao().getRun("fixture-run")?.status)
            assertNull(facts.latestDeleted())
        } finally { db.close() }
        // long: 失败保留现场，全部持久证据通过后才删除明确命名的隔离数据库，不碰正式 Room/撤销文件。
        assertTrue(context.deleteDatabase(name))
        assertFalse(context.getDatabasePath(name).exists())
        report(cleaned = true)
    }
}
