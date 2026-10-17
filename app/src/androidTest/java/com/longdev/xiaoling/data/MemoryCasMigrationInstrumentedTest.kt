package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.agent.AgentMemoryDeleteOperationVerification
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** long: 真实旧 schema 迁移保留用户字段和旧 operation，仅为历史记忆补 CAS 基线。 */
@RunWith(AndroidJUnit4::class)
class MemoryCasMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XiaoLingDatabase::class.java)

    @Test fun migrate40To41PreservesMemoryFtsAndLegacyOperationThenAllowsCas() = runBlocking {
        assertEquals("begonia", android.os.Build.DEVICE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage287-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 40).apply {
                execSQL("""INSERT INTO agent_memories
                    (id,content,tags,type,sourceConversationId,sourceRunId,sourceSummary,confidence,enabled,createdAt,updatedAt,pinned,expiresAt,lastReferencedAt)
                    VALUES ('legacy','legacyword','oldtag','Episode','conversation','run','source',0.8,1,100,200,1,9999999999999,300)""")
                execSQL("INSERT INTO agent_memories_fts (memoryId,content,tags,type,sourceSummary) VALUES ('legacy','legacyword','oldtag','Episode','source')")
                execSQL("INSERT INTO agent_memory_operations (idempotencyKey,memoryId,payloadHash,resultHash,createdAt) VALUES ('old-operation','legacy','payload','result',150)")
                // long: 固定旧版 canonical hash 验证只读恢复兼容，不让新 codec 自己生成自己的预期值。
                execSQL("INSERT INTO agent_memory_operations (idempotencyKey,memoryId,payloadHash,resultHash,createdAt) VALUES ('old-delete','deleted','4efe543f95638ffa7b20bf117b48c8e5aa545560e4052812e15db838d5c7ec96','b11c5a7c9687d81f064f49e6782c2193c00c2219fcb14ad0b2965f36c7c1386d',160)")
                close()
            }
            helper.runMigrationsAndValidate(name, 41, true, *XiaoLingDatabase.migrations()).close()
            val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
            try {
                val store = RoomAgentMemoryStore(context, database, File(context.cacheDir, "$name.undo"))
                val old = requireNotNull(store.get("legacy"))
                assertEquals(1L, old.version)
                assertEquals("legacyword", old.content)
                assertEquals("oldtag", old.tags)
                assertEquals("Episode", old.type)
                assertEquals("conversation", old.sourceConversationId)
                assertEquals("run", old.sourceRunId)
                assertEquals("source", old.sourceSummary)
                assertEquals(0.8, old.confidence, 0.0)
                assertTrue(old.enabled)
                assertTrue(old.pinned)
                assertEquals(9999999999999L, old.expiresAt)
                assertEquals(300L, old.lastReferencedAt)
                assertEquals(100L, old.createdAt)
                assertEquals(200L, old.updatedAt)
                val dao = database.agentMemoryDao()
                val operation = requireNotNull(dao.getMemoryOperation("old-operation"))
                assertNull(operation.expectedVersion)
                assertEquals("payload", operation.payloadHash)
                assertEquals("result", operation.resultHash)
                assertEquals(150L, operation.createdAt)
                assertEquals(listOf("legacy"), dao.searchFts("legacyword", 10, null, false, 1000).map { it.id })
                val updated = requireNotNull(store.update("legacy", AgentMemoryUpdate("currentword", old.tags, old.type, old.confidence), old.version))
                assertEquals(2L, updated.version)
                assertEquals(old.copy(content = "currentword", version = 2, updatedAt = updated.updatedAt), updated)
                assertTrue(dao.searchFts("legacyword", 10, null, false, 1000).isEmpty())
                assertEquals(listOf("legacy"), dao.searchFts("currentword", 10, null, false, 1000).map { it.id })
                assertEquals(operation, dao.getMemoryOperation("old-operation"))
                assertEquals(AgentMemoryDeleteOperationVerification.Verified, store.verifyDeletedOperation("old-delete", "deleted"))
                assertNull(dao.getMemoryOperation("old-delete")?.expectedVersion)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
