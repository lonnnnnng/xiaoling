package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XiaoLingDatabaseMigrationInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        XiaoLingDatabase::class.java,
    )

    private var openedDatabase: XiaoLingDatabase? = null

    @After
    fun tearDown() {
        openedDatabase?.close()
    }

    @Test
    fun migrate4To7PreservesUserDataAndInitializesNewFields() = runBlocking {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 4).apply {
            insertVersion4Fixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            7,
            true,
            *XiaoLingDatabase.migrations(),
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, MIGRATION_DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }

        val providers = database.providerDao().getAll()
        val conversations = database.conversationDao().getAllConversations()
        val messages = database.conversationDao().getAllMessages()
        val run = database.agentRunDao().getRun("run-v4")
        val steps = database.agentRunDao().getSteps("run-v4")
        val approvals = database.agentRunDao().getApprovalRequests("run-v4")
        val events = database.agentRunDao().getEvents("run-v4")
        val memories = database.agentMemoryDao().search("%紧凑界面%", 10, enabledOnly = false)
        val note = database.agentNoteDao().getNote("note-v4")

        assertEquals(listOf("provider-v4"), providers.map { it.id })
        assertEquals(listOf("conversation-v4"), conversations.map { it.id })
        assertEquals(listOf("message-user-v4", "message-assistant-v4"), messages.map { it.id })
        assertEquals(listOf("LEGACY", "LEGACY"), messages.map { it.origin })
        assertEquals(listOf(null, null), messages.map { it.verifiedAgentContext })
        assertEquals("run-v4", run?.id)
        assertEquals(listOf("step-v4"), steps.map { it.id })
        assertEquals(listOf("approval-v4"), approvals.map { it.id })
        assertEquals(listOf("event-v4"), events.map { it.id })
        assertNull(events.single().metadataJson)
        assertEquals(listOf("memory-v4"), memories.map { it.id })
        assertEquals("迁移测试笔记", note?.title)
    }

    @Test
    fun createAndOpenFreshVersion7Database() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }

        assertNotNull(database.openHelper.writableDatabase)
        assertEquals(7, database.openHelper.writableDatabase.version)
        assertNull(database.agentRunDao().getRun("missing"))
    }

    @Test
    fun migrate6To7MakesLegacyJsonEventMetadataReadableThroughRepository() = runBlocking {
        val legacyPayload = """{"id":"tool-call-v6","name":"fake.echo","risk":"REQUIRES_APPROVAL","arguments":{"goal":"历史任务"}}"""
        val legacyApprovalPayload = """{"id":"approval-v6","tool":"memory.remember","risk":"REQUIRES_APPROVAL","status":"APPROVED","expiresAt":9223372036854775807,"decisionReason":"用户确认保存","arguments":{"content":"紧凑界面"}}"""
        migrationHelper.createDatabase(METADATA_MIGRATION_DATABASE_NAME, 6).apply {
            execSQL(
                "INSERT INTO agent_runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("run-v6", "conversation-v6", "message-v6", "历史任务", "THINKING", null, null, 100L, 100L, null),
            )
            execSQL(
                "INSERT INTO run_events VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("event-v6", "run-v6", "tool.call.proposed", legacyPayload, 101L),
            )
            execSQL(
                "INSERT INTO run_events VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("approval-event-v6", "run-v6", "approval.request_decided", legacyApprovalPayload, 102L),
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            METADATA_MIGRATION_DATABASE_NAME,
            7,
            true,
            *XiaoLingDatabase.migrations(),
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, METADATA_MIGRATION_DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }

        val events = RoomAgentRunRepository(context, database)
            .snapshot("run-v6")
            .events
        val event = events.single { it.id == "event-v6" }
        val toolCall = event.metadata as RunEventMetadata.ToolCall
        assertEquals("tool-call-v6", toolCall.id)
        assertEquals("fake.echo", toolCall.toolName)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, toolCall.risk)
        assertEquals("历史任务", toolCall.arguments["goal"])
        assertEquals("模型提出工具调用：fake.echo", event.message)
        val approval = events.single { it.id == "approval-event-v6" }
        val approvalMetadata = approval.metadata as RunEventMetadata.ApprovalRequest
        assertEquals("memory.remember", approvalMetadata.toolName)
        assertEquals("用户确认保存", approvalMetadata.reason)
        assertEquals("审批状态已更新：memory.remember", approval.message)
    }

    private fun SupportSQLiteDatabase.insertVersion4Fixture() {
        // long: 迁移夹具覆盖用户可持续积累的全部 v4 数据，避免只验证表结构却漏掉真实会话、审批、笔记或记忆的保留语义。
        execSQL(
            "INSERT INTO providers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("provider-v4", "兼容服务", "https://example.test/v1", "iv", "ciphertext", "model-v4", "[]", "[]", "2026-07-17T12:00:00+08:00"),
        )
        execSQL(
            "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("conversation-v4", "历史会话", "历史摘要", null, null, null, 100L, 200L),
        )
        insertVersion4Message("message-user-v4", "user", "请记住我喜欢紧凑界面", 110L)
        insertVersion4Message("message-assistant-v4", "assistant", "好的", 120L)
        execSQL(
            "INSERT INTO agent_runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("run-v4", "conversation-v4", "message-user-v4", "保存偏好", "WAITING_APPROVAL", null, null, 130L, 140L, null),
        )
        execSQL(
            "INSERT INTO agent_steps VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("step-v4", "run-v4", 1, "TOOL", "WAITING_APPROVAL", "写入记忆", "等待确认", 131L, null),
        )
        execSQL(
            "INSERT INTO approval_requests VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("approval-v4", "run-v4", "conversation-v4", "tool-call-v4", "memory.remember", "写入长期记忆", "REQUIRES_APPROVAL", "{}", "PENDING", null, 132L, 999999L, null),
        )
        execSQL(
            "INSERT INTO run_events VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("event-v4", "run-v4", "approval.requested", "等待用户确认", 133L),
        )
        execSQL(
            "INSERT INTO agent_memories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("memory-v4", "用户喜欢紧凑界面", "ui", "Preference", "conversation-v4", "run-v4", "用户明确表达", 0.95, 1, 150L, 150L),
        )
        execSQL(
            "INSERT INTO agent_notes VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("note-v4", "迁移测试笔记", "保留这条内容", 160L, 160L),
        )
    }

    private fun SupportSQLiteDatabase.insertVersion4Message(id: String, role: String, text: String, createdAt: Long) {
        execSQL(
            """
            INSERT INTO messages (
                id, conversationId, role, text, createdAt, providerId, providerName, model,
                apiMode, streaming, requestUrl, firstTokenLatencyMs, latencyMs, promptTokens,
                completionTokens, totalTokens, finishReason, errorKind, errorMessage
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id, "conversation-v4", role, text, createdAt, "provider-v4", "兼容服务", "model-v4",
                "chat-completions", 1, "https://example.test/v1/chat/completions", 10L, 20L, 5, 6, 11,
                "stop", null, null,
            ),
        )
    }

    companion object {
        private const val MIGRATION_DATABASE_NAME = "xiaoling-migration-test"
        private const val METADATA_MIGRATION_DATABASE_NAME = "xiaoling-metadata-migration-test"
    }
}
