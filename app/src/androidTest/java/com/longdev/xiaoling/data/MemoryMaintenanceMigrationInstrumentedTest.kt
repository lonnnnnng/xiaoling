package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** long: 新整理表初始为空，旧事实、候选决议和冻结帧只读保留，不能由升级自动触发回填。 */
@RunWith(AndroidJUnit4::class)
class MemoryMaintenanceMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XiaoLingDatabase::class.java)

    @Test fun migrate43To44PreservesMemoryDecisionAndFrozenContextWithoutStartingRequest() = runBlocking {
        assertEquals("begonia", android.os.Build.DEVICE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage287-maintenance-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 43).apply {
                execSQL("""INSERT INTO agent_memories
                    (id,content,tags,type,sourceConversationId,sourceRunId,sourceSummary,confidence,enabled,createdAt,updatedAt,pinned,expiresAt,lastReferencedAt,version)
                    VALUES ('memory','oldtext','tag','Preference','conversation','run','source',0.8,0,100,200,1,500,300,4)""")
                execSQL("INSERT INTO agent_memories_fts (memoryId,content,tags,type,sourceSummary) VALUES ('memory','oldtext','tag','Preference','source')")
                execSQL("""INSERT INTO agent_memory_candidates
                    (id,content,normalizedContent,type,topicKey,sourceConversationId,sourceRunId,sourceSummary,confidence,status,sensitiveCategory,relatedMemoryId,createdAt,updatedAt)
                    VALUES ('candidate','oldtext','oldtext','Preference','topic','conversation','run','source',0.9,'REJECTED',NULL,'memory',100,200)""")
                execSQL("""INSERT INTO agent_runs
                    (id,retryOfRunId,conversationId,userMessageId,goal,status,result,errorMessage,createdAt,updatedAt,completedAt)
                    VALUES ('run',NULL,'conversation','message','goal','COMPLETED','result',NULL,100,200,200)""")
                execSQL("INSERT INTO agent_context_snapshots (runId,sequence,stepId,payloadJson,recordSha256,payloadBytes,createdAt,compressionVersion) VALUES ('run',1,'step','frozen','hash',6,150,NULL)")
                close()
            }
            helper.runMigrationsAndValidate(name, 44, true, *XiaoLingDatabase.migrations()).close()
            val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
            try {
                val memory = requireNotNull(db.agentMemoryDao().getMemory("memory"))
                assertEquals(4L, memory.version)
                assertEquals("oldtext", memory.content)
                assertFalse(memory.enabled)
                assertTrue(memory.pinned)
                assertEquals(500L, memory.expiresAt)
                assertEquals(300L, memory.lastReferencedAt)
                assertEquals("REJECTED", db.agentMemoryDao().getCandidate("candidate")?.status)
                assertEquals(listOf("memory"), db.agentMemoryDao().searchFts("oldtext", 10, null, false, 0).map { it.id })
                val frame = db.agentRunDao().getContextSnapshots("run").single()
                assertEquals("frozen", frame.payloadJson)
                assertEquals("hash", frame.recordSha256)
                assertNull(frame.compressionVersion)
                assertNull(db.memoryMaintenanceDao().checkpoint())
                assertEquals(0, db.memoryMaintenanceDao().outboxCount())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
