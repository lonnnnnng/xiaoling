package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** long: 两个真实 SQLite 连接竞争同一版本；数据库和撤销文件都属于本轮 UUID，不触碰正式记忆。 */
@RunWith(AndroidJUnit4::class)
class RoomAgentMemoryCasInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "stage287-memory-${UUID.randomUUID()}.db"
    private val undoFile = File(context.cacheDir, "$name.undo.json")
    private val databases = mutableListOf<XiaoLingDatabase>()
    private val undo get() = AgentMemoryDeleteUndoStore(context, undoFile)

    @Before fun requireTarget() { assertEquals("begonia", android.os.Build.DEVICE) }
    @After fun closeFixture() { databases.forEach { it.close() }; context.deleteDatabase(name); undo.clear() }

    private fun open(): RoomAgentMemoryStore {
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name)
            .addMigrations(*XiaoLingDatabase.migrations()).allowMainThreadQueries().build()
        databases += database
        return RoomAgentMemoryStore(context, database, undoFile)
    }
    private suspend fun create(store: RoomAgentMemoryStore, text: String = "originalword stage287") =
        store.remember(text, "stage287", "Episode", AgentMemorySource(null, null, "隔离 CAS 验收"), 0.8, "call-$text")
    private fun update(text: String) = AgentMemoryUpdate(text, "stage287", "Episode", 0.8)

    @Test fun twoDatabaseWritersPublishOneWinnerAndReturnCurrentConflictWithAtomicFts() = runBlocking {
        val first = open()
        val memory = create(first)
        val second = open()
        second.get(memory.id)
        val start = CompletableDeferred<Unit>()
        val jobs = listOf(first to "winnerone stage287", second to "winnertwo stage287").map { (store, text) ->
            async(Dispatchers.IO) { start.await(); runCatching { store.update(memory.id, update(text), memory.version) } }
        }
        start.complete(Unit)
        val results = withTimeout(20_000) { jobs.awaitAll() }
        assertEquals(1, results.count { it.isSuccess })
        val winner = requireNotNull(results.single { it.isSuccess }.getOrThrow())
        val conflict = results.single { it.isFailure }.exceptionOrNull() as AgentMemoryVersionConflictException
        assertEquals(2L, winner.version)
        assertEquals(winner, conflict.current)
        assertEquals(winner, first.get(memory.id))
        val dao = databases.first().agentMemoryDao()
        for (word in listOf("originalword", "winnerone", "winnertwo")) {
            val hits = dao.searchFts(word, 10, null, false, System.currentTimeMillis())
            assertEquals(if (winner.content.startsWith(word)) listOf(memory.id) else emptyList<String>(), hits.map { it.id })
        }
    }

    @Test fun staleContentGovernanceAndAgentDeletionLeaveWinnerLedgerAndUndoUntouched() = runBlocking {
        val store = open()
        val memory = create(store)
        val winner = requireNotNull(store.update(memory.id, update("currentword stage287"), memory.version))
        val dao = databases.single().agentMemoryDao()
        val operation = dao.getMemoryOperation("call-originalword stage287")
        val attempts: List<suspend () -> Unit> = listOf(
            { store.update(memory.id, update("loserword"), memory.version); Unit },
            { store.setEnabled(memory.id, false, memory.version); Unit },
            { store.setPinned(memory.id, true, memory.version); Unit },
            { store.setExpiresAt(memory.id, System.currentTimeMillis() + 60_000, memory.version); Unit },
            { store.delete(memory.id, memory.version); Unit },
            { store.deleteForAgent(memory.id, "stale-delete", memory.version); Unit },
        )
        for (attempt in attempts) {
            val error = runCatching { attempt() }.exceptionOrNull() as AgentMemoryVersionConflictException
            assertEquals(winner, error.current)
            assertEquals(winner, store.get(memory.id))
            assertNull(undo.load())
            assertEquals(operation, dao.getMemoryOperation("call-originalword stage287"))
            assertNull(dao.getMemoryOperation("stale-delete"))
        }
        assertFalse(store.deleteForAgent(memory.id, "missing-version"))
        assertEquals(listOf(memory.id), dao.searchFts("currentword", 10, null, false, Long.MAX_VALUE).map { it.id })
    }

    @Test fun reopenNoopRecallAndRememberDoNotLoseVersionAndUndoInvalidatesOldEditors() = runBlocking {
        val store = open()
        val memory = create(store)
        val pinned = requireNotNull(store.setPinned(memory.id, true, memory.version))
        assertEquals(pinned, store.setPinned(memory.id, true, pinned.version))
        store.search("originalword", 10)
        val before = requireNotNull(store.get(memory.id))
        assertEquals(pinned.version, before.version)
        assertEquals(before, create(store))
        databases.forEach { it.close() }; databases.clear()
        val reopened = open()
        assertEquals(before, reopened.get(memory.id))
        val deleted = requireNotNull(reopened.delete(memory.id, before.version))
        val restored = reopened.restore(deleted)
        assertEquals(before.copy(version = before.version + 1), restored)
        val stale = runCatching { reopened.update(memory.id, update("obsoleteword"), before.version) }.exceptionOrNull()
        assertEquals(restored, (stale as AgentMemoryVersionConflictException).current)
        assertTrue(runCatching { reopened.restore(deleted) }.exceptionOrNull() is AgentMemoryVersionConflictException)
        assertNull(reopened.latestDeleted())
    }

    @Test fun onlyLatestUndoCanRestoreAndFtsFailureRollsBackVersionWithoutLosingSnapshot() = runBlocking {
        val store = open()
        val first = create(store, "firstword stage287")
        val oldUndo = requireNotNull(store.delete(first.id, first.version))
        val second = create(store, "secondword stage287")
        val latest = requireNotNull(store.delete(second.id, second.version))
        assertTrue(runCatching { store.restore(oldUndo) }.isFailure)
        assertNull(store.get(first.id))
        val sql = databases.single().openHelper.writableDatabase
        // long: 仅破坏 UUID 测试库的独立 FTS 表，让主记录插入后的索引写入失败，验证事务回滚仍保留撤销凭证。
        sql.execSQL("DROP TABLE agent_memories_fts")
        assertTrue(runCatching { store.restore(latest) }.isFailure)
        assertNull(store.get(second.id))
        assertEquals(latest, store.latestDeleted())
    }

    @Test fun indexFailureRollsBackContentAndVersion() = runBlocking {
        val store = open()
        val memory = create(store)
        databases.single().openHelper.writableDatabase.execSQL("DROP TABLE agent_memories_fts")
        assertTrue(runCatching { store.update(memory.id, update("failedword"), memory.version) }.isFailure)
        assertEquals(memory, store.get(memory.id))
        assertNull(undo.load())
    }

    @Test fun agentDeleteRejectsNaturalExpiryWithoutChangingVersionOrLedger() = runBlocking {
        val store = open()
        val memory = create(store)
        val expiring = requireNotNull(store.setExpiresAt(memory.id, System.currentTimeMillis() + 2_000, memory.version))
        delay(2_100)
        assertFalse(store.deleteForAgent(memory.id, "expired-delete", expiring.version))
        assertEquals(expiring, store.get(memory.id))
        assertNull(databases.single().agentMemoryDao().getMemoryOperation("expired-delete"))
        assertNull(undo.load())
    }

    @Test fun legacyUndoStartsAtMigrationVersionAndCannotOverwriteAnExistingIdentity() = runBlocking {
        val store = open()
        val memory = create(store)
        store.delete(memory.id, memory.version)
        val legacy = JSONObject(undoFile.readText()).put("version", 1).apply { remove("memoryVersion") }
        undoFile.writeText(legacy.toString())
        val snapshot = requireNotNull(store.latestDeleted())
        assertEquals(1L, snapshot.version)
        val restored = store.restore(snapshot)
        assertEquals(2L, restored.version)
        assertEquals(restored, (runCatching { store.restore(snapshot.copy(content = "forgedword")) }.exceptionOrNull() as AgentMemoryVersionConflictException).current)
        assertEquals(restored, store.get(memory.id))
    }
}
