package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentContextCompressionMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XiaoLingDatabase::class.java)

    @Test fun migrate42To43PreservesV1FrameHashMemoryAndDoesNotInventCompression() = runBlocking {
        assertEquals("begonia", android.os.Build.DEVICE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage287-compression-migration-${UUID.randomUUID()}.db"
        val input = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS", listOf(
            AgentContextMessage("system", "old system"), AgentContextMessage("user", "完整旧输入😀")), emptyList())
        val payload = AgentContextSnapshotCodec.encode(input)
        val hash = AgentContextSnapshotCodec.recordHash("legacy-run", "legacy-step", 1, payload)
        try {
            helper.createDatabase(name, 42).apply {
                execSQL("INSERT INTO agent_runs (id,conversationId,userMessageId,goal,status,createdAt,updatedAt) VALUES ('legacy-run','conversation','message','old goal','THINKING',100,200)")
                execSQL("INSERT INTO agent_steps (id,runId,sequence,type,status,title,detail,createdAt) VALUES ('legacy-step','legacy-run',1,'llm.plan','RUNNING','title','detail',150)")
                execSQL("INSERT INTO agent_memories (id,content,tags,type,sourceSummary,confidence,enabled,createdAt,updatedAt,version) VALUES ('legacy-memory','old memory','tag','Episode','source',0.8,1,100,200,4)")
                execSQL("INSERT INTO agent_context_snapshots (runId,sequence,stepId,payloadJson,recordSha256,payloadBytes,createdAt) VALUES ('legacy-run',1,'legacy-step',?,?,?,160)",
                    arrayOf<Any>(payload, hash, AgentContextSnapshotCodec.utf8Size(payload)))
                close()
            }
            helper.runMigrationsAndValidate(name, 43, true, *XiaoLingDatabase.migrations()).close()
            val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
            try {
                val repo = RoomAgentRunRepository(context, db)
                val frame = repo.contextTranscript("legacy-run").single()
                assertEquals(input, frame.input)
                assertEquals(hash, frame.recordSha256)
                assertNull(frame.compressionVersion)
                assertEquals(160L, frame.createdAt)
                assertTrue(repo.contextCompressions("legacy-run").isEmpty())
                assertEquals(4L, db.agentMemoryDao().getMemory("legacy-memory")!!.version)
                repo.appendContextSnapshot("legacy-run", "legacy-step", input)
                assertEquals(listOf(frame), repo.contextTranscript("legacy-run"))
                assertEquals("old goal", repo.snapshot("legacy-run").run.goal)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
