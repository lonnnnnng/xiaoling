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
class AgentContextMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), XiaoLingDatabase::class.java)

    @Test fun migrate41To42PreservesOldRunAndMemoryAndDoesNotInventContextOrRecovery() = runBlocking {
        assertEquals("begonia", android.os.Build.DEVICE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stage287-context-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 41).apply {
                execSQL("INSERT INTO agent_runs (id,conversationId,userMessageId,goal,status,createdAt,updatedAt) VALUES ('legacy-run','conversation','message','old goal','THINKING',100,200)")
                execSQL("INSERT INTO agent_steps (id,runId,sequence,type,status,title,detail,createdAt) VALUES ('legacy-step','legacy-run',1,'llm.plan','RUNNING','old title','old detail',150)")
                execSQL("INSERT INTO agent_memories (id,content,tags,type,sourceSummary,confidence,enabled,createdAt,updatedAt,version) VALUES ('legacy-memory','old memory','tag','Episode','source',0.8,1,100,200,4)")
                close()
            }
            helper.runMigrationsAndValidate(name, 42, true, *XiaoLingDatabase.migrations()).close()
            val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
            try {
                val repo = RoomAgentRunRepository(context, database)
                val old = repo.snapshot("legacy-run")
                assertEquals("old goal", old.run.goal)
                assertEquals(100L, old.run.createdAt)
                assertEquals(200L, old.run.updatedAt)
                assertEquals("old detail", old.steps.single().detail)
                assertEquals(4L, database.agentMemoryDao().getMemory("legacy-memory")?.version)
                assertTrue(repo.contextTranscript("legacy-run").isEmpty())
                val recoveryBefore = AgentRunResumePolicy.assess(requireNotNull(repo.runDetail("legacy-run")))
                val input = AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
                    listOf(AgentContextMessage("system", "s"), AgentContextMessage("user", "u")), emptyList())
                repo.appendContextSnapshot("legacy-run", "legacy-step", input)
                assertEquals(input, repo.contextTranscript("legacy-run").single().input)
                // long: 新表存在或快照写入都不能扩大旧 Run 的恢复资格，控制面仍只接受原审批/工具证据。
                assertEquals(recoveryBefore, AgentRunResumePolicy.assess(requireNotNull(repo.runDetail("legacy-run"))))
                database.openHelper.writableDatabase.execSQL("DELETE FROM agent_runs WHERE id = 'legacy-run'")
                assertTrue(repo.contextTranscript("legacy-run").isEmpty())
                assertEquals(4L, database.agentMemoryDao().getMemory("legacy-memory")?.version)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
