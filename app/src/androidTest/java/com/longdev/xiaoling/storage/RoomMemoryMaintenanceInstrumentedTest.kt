package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.automation.MemoryMaintenanceWorker
import com.longdev.xiaoling.data.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** long: 整理测试只用 UUID Room 与撤销文件；故障注入不接触正式候选、记忆或 Provider。 */
@RunWith(AndroidJUnit4::class)
class RoomMemoryMaintenanceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "stage287-maintenance-${UUID.randomUUID()}.db"
    private val databases = mutableListOf<XiaoLingDatabase>()
    private val now = 1_800_000_000_000L
    private val undoFile = File(context.cacheDir, "$name.undo")
    private fun open(): XiaoLingDatabase = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name)
        .addMigrations(*XiaoLingDatabase.migrations()).build().also { databases += it }
    @Before fun target() { assertEquals("begonia", android.os.Build.DEVICE) }
    @After fun close() { databases.forEach { it.close() }; context.deleteDatabase(name); AgentMemoryDeleteUndoStore(context, undoFile).clear() }

    private suspend fun seed(db: XiaoLingDatabase, id: String, text: String = "我喜欢中文回答 $id", status: String = "COMPLETED",
        completed: Long = now - 100, origin: String = "USER", parent: String? = null) {
        db.agentRunDao().upsertRun(AgentRunEntity(id, null, "c", "message-$id", "模型派生目标不会被整理", status,
            "模型结果不会被整理", null, completed - 10, completed, completed, parentRunId = parent))
        db.openHelper.writableDatabase.execSQL("INSERT INTO messages (id,conversationId,role,text,createdAt,origin) VALUES (?,?,?,?,?,?)",
            arrayOf<Any>("message-$id", "c", "user", text, completed - 10, origin))
    }

    private suspend fun finish(store: RoomMemoryMaintenanceStore, request: String) {
        repeat(60) {
            if (store.deliverOne(request)) return@repeat
            if (store.scanOne(request)) return@repeat
            store.complete(request)
            return
        }
        error("隔离整理超出预算")
    }

    @Test fun stableCursorLimitsEachRequestAndNeverChangesFormalFacts() = runBlocking {
        val db = open()
        val memories = RoomAgentMemoryStore(context, db, undoFile)
        val original = memories.remember("正式事实保持原样", "tag", "Episode", AgentMemorySource(null, null, "fixture"), 0.8)
        val edited = requireNotNull(memories.update(original.id, AgentMemoryUpdate("用户最新编辑", "tag", "Episode", 0.8), original.version))
        val operation = db.agentMemoryDao().getMemoryOperation("unused")
        repeat(23) { seed(db, "run-%02d".format(it)) }
        seed(db, "failed", status = "FAILED")
        seed(db, "thinking", status = "THINKING")
        seed(db, "child", parent = "run-00")
        seed(db, "old", completed = now - MemoryMaintenancePolicy.WINDOW_MILLIS - 1)
        val store = RoomMemoryMaintenanceStore(db)
        val first = store.begin(now)
        assertEquals(first, store.begin(now + 1))
        finish(store, first.requestId)
        assertEquals(20, store.current()?.scanned)
        assertEquals(20, db.memoryMaintenanceDao().outboxCount())
        assertEquals(20, memories.listCandidates().size)
        val second = store.begin(now)
        finish(store, second.requestId)
        assertEquals(3, store.current()?.scanned)
        assertEquals(23, db.memoryMaintenanceDao().outboxCount())
        assertEquals(edited, memories.get(original.id))
        assertEquals(listOf(edited), memories.list("", AgentMemoryFilter.ALL, 100))
        assertEquals(operation, db.agentMemoryDao().getMemoryOperation("unused"))
        assertNull(memories.latestDeleted())
        assertEquals("COMPLETED", db.agentRunDao().getRun("run-00")?.status)
    }

    @Test fun outboxAndCursorRollbackTogetherAndDeliveryFailureReplaysOnceAfterReopen() = runBlocking {
        var db = open()
        seed(db, "run")
        var store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER maintenance_insert_fail BEFORE INSERT ON memory_maintenance_outbox BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { store.scanOne(request.requestId) }.isFailure)
        assertEquals(request, store.current())
        assertEquals(0, db.memoryMaintenanceDao().outboxCount())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER maintenance_insert_fail")
        assertTrue(store.scanOne(request.requestId))
        val captured = store.current()
        val outbox = db.memoryMaintenanceDao().outbox("run")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER maintenance_settle_fail BEFORE UPDATE ON memory_maintenance_outbox BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { store.deliverOne(request.requestId) }.isFailure)
        assertTrue(db.agentMemoryDao().listCandidates(100).isEmpty())
        assertEquals(captured, store.current())
        assertEquals(outbox, db.memoryMaintenanceDao().outbox("run"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER maintenance_settle_fail")
        db.close(); db = open(); store = RoomMemoryMaintenanceStore(db)
        assertTrue(store.deliverOne(request.requestId))
        assertFalse(store.deliverOne(request.requestId))
        assertEquals(1, db.agentMemoryDao().listCandidates(100).size)
        assertEquals("DELIVERED", db.memoryMaintenanceDao().outbox("run")?.deliveryStatus)
        assertEquals(outbox?.payloadHash, db.memoryMaintenanceDao().outbox("run")?.payloadHash)
    }

    @Test fun existingRejectedCandidateIsNotReactivatedAndSensitiveTextIsNotCopied() = runBlocking {
        val db = open()
        val memory = RoomAgentMemoryStore(context, db, undoFile)
        val old = requireNotNull(memory.createCandidate("我喜欢中文回答", AgentMemorySource("c", "old", "原候选")))
        val rejected = requireNotNull(memory.rejectCandidate(old.id))
        seed(db, "same", "我喜欢中文回答")
        seed(db, "secret", "请记住 api_key=sk-fictional123456789012345")
        seed(db, "oversize", "我喜欢" + "中".repeat(3000))
        seed(db, "legacy", "我喜欢英文", origin = "LEGACY")
        val store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now)
        finish(store, request.requestId)
        assertEquals(rejected, db.agentMemoryDao().getCandidate(old.id)?.let { memory.listCandidates().single { c -> c.id == it.id } })
        assertEquals(old.id, db.memoryMaintenanceDao().outbox("same")?.candidateId)
        val secret = requireNotNull(db.memoryMaintenanceDao().outbox("secret"))
        assertEquals("", secret.content)
        assertFalse(secret.toString().contains("fictional"))
        assertNull(db.memoryMaintenanceDao().outbox("oversize"))
        assertNull(db.memoryMaintenanceDao().outbox("legacy"))
        assertTrue(memory.list("", AgentMemoryFilter.ALL, 100).isEmpty())
    }

    @Test fun cancelAndSourceDriftCannotPublishStaleSuggestionOrConsumeOldDecision() = runBlocking {
        val db = open()
        seed(db, "run")
        val store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now)
        assertTrue(store.scanOne(request.requestId))
        val cursor = store.current()
        store.cancel(request.requestId)
        assertFalse(store.deliverOne(request.requestId))
        assertFalse(store.scanOne(request.requestId))
        assertEquals(cursor?.cursorAt, store.current()?.cursorAt)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET text = '用户修改后的原话' WHERE id = 'message-run'")
        val next = store.begin(now)
        assertTrue(store.deliverOne(next.requestId))
        assertEquals("SOURCE_CHANGED", db.memoryMaintenanceDao().outbox("run")?.deliveryStatus)
        assertTrue(db.agentMemoryDao().listCandidates(100).isEmpty())
        assertFalse(store.deliverOne(request.requestId))
    }

    @Test fun twoRoomConnectionsDeduplicateScanAndRejectDamagedOutbox() = runBlocking {
        val first = open(); val second = open()
        seed(first, "run")
        val a = RoomMemoryMaintenanceStore(first); val b = RoomMemoryMaintenanceStore(second)
        val request = a.begin(now)
        coroutineScope { listOf(a, b).map { store -> async(Dispatchers.IO) { store.scanOne(request.requestId) } }.awaitAll() }
        assertEquals(1, first.memoryMaintenanceDao().outboxCount())
        assertEquals(1, a.current()?.scanned)
        first.openHelper.writableDatabase.execSQL("UPDATE memory_maintenance_outbox SET content = '损坏内容' WHERE runId = 'run'")
        assertTrue(runCatching { b.deliverOne(request.requestId) }.isFailure)
        assertTrue(first.agentMemoryDao().listCandidates(100).isEmpty())
        assertEquals("PENDING", first.memoryMaintenanceDao().outbox("run")?.deliveryStatus)
    }

    @Test fun productionWorkerCompletesBoundedRequestAndDisabledGateKeepsPendingOutbox() = runBlocking {
        val db = open(); seed(db, "run")
        val store = RoomMemoryMaintenanceStore(db)
        var allowed = true
        fun worker(requestId: String, attempt: Int = 0): MemoryMaintenanceWorker =
            TestListenableWorkerBuilder<MemoryMaintenanceWorker>(context)
                .setInputData(workDataOf(MemoryMaintenanceWorker.REQUEST_ID to requestId))
                .setRunAttemptCount(attempt)
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                        MemoryMaintenanceWorker(appContext, workerParameters, store) { allowed }
                }).build()
        val request = store.begin(now)
        assertTrue(worker(request.requestId).doWork() is ListenableWorker.Result.Success)
        assertEquals("SUCCEEDED", store.current()?.status)
        seed(db, "later", completed = now - 10)
        val next = store.begin(now)
        assertTrue(store.scanOne(next.requestId))
        allowed = false
        worker(next.requestId).doWork()
        assertEquals("CANCELLED", store.current()?.status)
        assertEquals("PENDING", db.memoryMaintenanceDao().outbox("later")?.deliveryStatus)
    }

    @Test fun fullOutboxStopsWithoutMovingCursorOrDeletingOldReceipts() = runBlocking {
        val db = open(); seed(db, "run"); seed(db, "later", completed = now - 10)
        val store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now)
        assertTrue(store.scanOne(request.requestId))
        assertTrue(store.deliverOne(request.requestId))
        val prototype = requireNotNull(db.memoryMaintenanceDao().outbox("run"))
        repeat(MemoryMaintenancePolicy.MAX_OUTBOX_ROWS - 1) { index ->
            db.memoryMaintenanceDao().insertOutbox(prototype.copy(runId = "receipt-$index"))
        }
        val state = store.current()
        assertTrue(runCatching { store.scanOne(request.requestId) }.isFailure)
        assertEquals(state, store.current())
        assertEquals(MemoryMaintenancePolicy.MAX_OUTBOX_ROWS, db.memoryMaintenanceDao().outboxCount())
        assertNull(db.memoryMaintenanceDao().outbox("later"))
        assertEquals(prototype, db.memoryMaintenanceDao().outbox("run"))
    }

    @Test fun inheritedOutboxExpiresAndCountsAgainstNewTwentyRunBudget() = runBlocking {
        val db = open(); seed(db, "old-pending")
        val store = RoomMemoryMaintenanceStore(db)
        val old = store.begin(now)
        assertTrue(store.scanOne(old.requestId)); store.cancel(old.requestId)
        val later = now + MemoryMaintenancePolicy.WINDOW_MILLIS + 1000
        repeat(20) { seed(db, "new-%02d".format(it), completed = later - 100) }
        val next = store.begin(later)
        finish(store, next.requestId)
        assertEquals(20, store.current()?.scanned)
        assertEquals("OUTSIDE_WINDOW", db.memoryMaintenanceDao().outbox("old-pending")?.deliveryStatus)
        assertEquals(19, db.agentMemoryDao().listCandidates(100).size)
        assertNull(db.memoryMaintenanceDao().outbox("new-19"))
    }

    @Test fun replayKeepsAcceptedMemoryVersionAndUserDecisionIntact() = runBlocking {
        val db = open()
        val memory = RoomAgentMemoryStore(context, db, undoFile)
        val old = requireNotNull(memory.createCandidate("我喜欢中文回答", AgentMemorySource("c", "old", "原候选")))
        val accepted = requireNotNull(memory.acceptCandidate(old.id))
        val facts = memory.list("", AgentMemoryFilter.ALL, 100)
        seed(db, "same", "我喜欢中文回答")
        val store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now); finish(store, request.requestId)
        assertEquals(accepted, memory.listCandidates().single())
        assertEquals(facts, memory.list("", AgentMemoryFilter.ALL, 100))
        assertEquals(old.id, db.memoryMaintenanceDao().outbox("same")?.candidateId)
    }

    @Test fun upstreamTimeoutPropagatesWithoutMovingCursorOrMarkingBusinessFailure() = runBlocking {
        val db = open(); seed(db, "run")
        val store = RoomMemoryMaintenanceStore(db)
        val request = store.begin(now)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
        entered.await()
        val worker = TestListenableWorkerBuilder<MemoryMaintenanceWorker>(context)
            .setInputData(workDataOf(MemoryMaintenanceWorker.REQUEST_ID to request.requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    MemoryMaintenanceWorker(appContext, workerParameters, store) { true }
            }).build()
        try {
            assertTrue(runCatching { withTimeout(100) { worker.doWork() } }.exceptionOrNull() is TimeoutCancellationException)
        } finally { release.complete(Unit); blocker.join() }
        assertEquals(request, store.current())
        assertEquals(0, db.memoryMaintenanceDao().outboxCount())
        assertTrue(store.scanOne(request.requestId))
    }

    @Test fun individuallyValidButInconsistentBudgetRejectsBeforeOutboxOrCursorWrite() = runBlocking {
        val db = open(); seed(db, "run")
        val store = RoomMemoryMaintenanceStore(db); val request = store.begin(now)
        val damaged = request.copy(scanned = 20, remaining = 20)
        db.memoryMaintenanceDao().saveCheckpoint(damaged)
        assertTrue(runCatching { store.scanOne(request.requestId) }.isFailure)
        assertEquals(damaged, store.current())
        assertEquals(0, db.memoryMaintenanceDao().outboxCount())
        assertTrue(db.agentMemoryDao().listCandidates(100).isEmpty())
    }
}
