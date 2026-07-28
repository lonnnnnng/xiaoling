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
import com.longdev.xiaoling.agent.ToolVerificationStatus
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun migrate4To20PreservesUserDataAndInitializesNewFields() = runBlocking {
        migrationHelper.createDatabase(MIGRATION_DATABASE_NAME, 4).apply {
            insertVersion4Fixture()
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE_NAME,
            20,
            true,
            *XiaoLingDatabase.migrations(),
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, MIGRATION_DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
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
        assertNull(note?.idempotencyKey)
        // long: 老版本没有本地 Skill；升级只创建空表，内置定义由应用启动时同步，避免把运行时代码硬编码进迁移夹具。
        assertEquals(0, database.agentSkillDao().list().size)
        assertEquals(0, database.workflowDao().listWorkflows().size)
        assertEquals(0, RoomAgentRunRepository(context, database).toolLedger("run-v4").calls.size)
    }

    @Test
    fun createAndOpenFreshCurrentDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }

        assertNotNull(database.openHelper.writableDatabase)
        assertEquals(XiaoLingDatabase.CURRENT_VERSION, database.openHelper.writableDatabase.version)
        assertNull(database.agentRunDao().getRun("missing"))
    }

    @Test
    fun migrate6To9MakesLegacyJsonEventMetadataReadableThroughRepository() = runBlocking {
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
            9,
            true,
            *XiaoLingDatabase.migrations(),
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, METADATA_MIGRATION_DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
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

    @Test
    fun migrate7To8PreservesLegacyRunAndInitializesRetryLink() {
        migrationHelper.createDatabase(RETRY_LINK_MIGRATION_DATABASE_NAME, 7).apply {
            execSQL(
                "INSERT INTO agent_runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "run-v7",
                    "conversation-v7",
                    "message-v7",
                    "保留历史任务",
                    "FAILED",
                    "历史结果",
                    "历史错误",
                    100L,
                    200L,
                    200L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            RETRY_LINK_MIGRATION_DATABASE_NAME,
            8,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT id, status, result, errorMessage, retryOfRunId FROM agent_runs WHERE id = ?",
            arrayOf("run-v7"),
        ).use { cursor ->
            // long: v7 的 Run 没有重试来源；迁移只能补 null 关联，原状态、结果和错误必须原样保留，不能伪造一次重试。
            assertEquals(true, cursor.moveToFirst())
            assertEquals("run-v7", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("FAILED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("历史结果", cursor.getString(cursor.getColumnIndexOrThrow("result")))
            assertEquals("历史错误", cursor.getString(cursor.getColumnIndexOrThrow("errorMessage")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("retryOfRunId")))
        }
        migrated.close()
    }

    @Test
    fun migrate8To9PreservesMemoryAndBuildsSearchIndex() {
        migrationHelper.createDatabase(MEMORY_FTS_MIGRATION_DATABASE_NAME, 8).apply {
            execSQL(
                "INSERT INTO agent_memories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "memory-v8",
                    "User prefers compact dashboards",
                    "ui compact",
                    "Preference",
                    "conversation-v8",
                    "run-v8",
                    "用户明确要求紧凑布局",
                    0.9,
                    1,
                    100L,
                    200L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MEMORY_FTS_MIGRATION_DATABASE_NAME,
            9,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT id, pinned FROM agent_memories WHERE id = ?",
            arrayOf("memory-v8"),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("memory-v8", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pinned")))
        }
        migrated.query(
            "SELECT memoryId FROM agent_memories_fts WHERE agent_memories_fts MATCH ?",
            arrayOf("\"compact\"*"),
        ).use { cursor ->
            // long: v8 旧记忆升级后无需再次编辑即可被 FTS 找到，避免管理页上线后看似丢失历史内容。
            assertEquals(true, cursor.moveToFirst())
            assertEquals("memory-v8", cursor.getString(cursor.getColumnIndexOrThrow("memoryId")))
        }
        migrated.close()
    }

    @Test
    fun migrate9To17PreservesConfirmedMemoryAndCreatesEmptyCandidateTable() {
        migrationHelper.createDatabase(MEMORY_CANDIDATE_MIGRATION_DATABASE_NAME, 9).apply {
            execSQL(
                "INSERT INTO agent_memories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "memory-v9",
                    "用户喜欢紧凑界面",
                    "ui",
                    "Preference",
                    "conversation-v9",
                    "run-v9",
                    "用户明确表达偏好",
                    0.95,
                    1,
                    100L,
                    200L,
                    1,
                ),
            )
            execSQL(
                "INSERT INTO agent_memories_fts (memoryId, content, tags, type, sourceSummary) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("memory-v9", "用户喜欢紧凑界面", "ui", "Preference", "用户明确表达偏好"),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MEMORY_CANDIDATE_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT content, pinned FROM agent_memories WHERE id = 'memory-v9'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("用户喜欢紧凑界面", cursor.getString(cursor.getColumnIndexOrThrow("content")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("pinned")))
        }
        migrated.query("SELECT COUNT(*) AS candidateCount FROM agent_memory_candidates").use { cursor ->
            // long: 升级不能把历史正式记忆倒推成候选，否则用户会被要求重复确认已经存在的事实。
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("candidateCount")))
        }
        migrated.query("SELECT COUNT(*) AS skillCount FROM agent_skills").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("skillCount")))
        }
        migrated.query("SELECT COUNT(*) AS workflowCount FROM workflows").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("workflowCount")))
        }
        migrated.close()
    }

    @Test
    fun migrate11To12CreatesEmptySkillTableAndIndex() {
        migrationHelper.createDatabase(SKILL_MIGRATION_DATABASE_NAME, 11).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            SKILL_MIGRATION_DATABASE_NAME,
            12,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_skills'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_agent_skills_source_enabled_updatedAt'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
        }
        migrated.query("SELECT COUNT(*) AS skillCount FROM agent_skills").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("skillCount")))
        }
        migrated.close()
    }

    @Test
    fun migrate12To17CreatesWorkflowAndScheduledTaskLedgerTables() {
        migrationHelper.createDatabase(WORKFLOW_MIGRATION_DATABASE_NAME, 12).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            WORKFLOW_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        listOf("workflows", "workflow_step_definitions", "workflow_runs", "workflow_steps", "scheduled_tasks", "workflow_schedules").forEach { table ->
            migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
            }
        }
        migrated.query("SELECT COUNT(*) AS workflowCount FROM workflows").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("workflowCount")))
        }
        migrated.close()
    }

    @Test
    fun migrate13To17AddsMultiStepLedgerWithoutChangingOldWorkflowRows() {
        migrationHelper.createDatabase(SCHEDULED_TASK_MIGRATION_DATABASE_NAME, 13).apply {
            execSQL(
                "INSERT INTO workflows VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("workflow-v13", "历史工作流", "读取当前时间", 1, 100L, 100L),
            )
            execSQL(
                "INSERT INTO workflow_runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "workflow-run-v13", "workflow-v13", "MANUAL", "conversation-v13", null,
                    "COMPLETED", "完成", null, 101L, 102L, 103L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            SCHEDULED_TASK_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT scheduledTaskId, plannedAt, result FROM workflow_runs WHERE id = 'workflow-run-v13'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("scheduledTaskId")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("plannedAt")))
            assertEquals("完成", cursor.getString(cursor.getColumnIndexOrThrow("result")))
        }
        migrated.query("SELECT goal, sequence FROM workflow_step_definitions WHERE workflowId = 'workflow-v13'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("读取当前时间", cursor.getString(cursor.getColumnIndexOrThrow("goal")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("sequence")))
        }
        migrated.query("SELECT COUNT(*) AS taskCount FROM scheduled_tasks").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("taskCount")))
        }
        migrated.close()
    }

    @Test
    fun migrate14To17PreservesOneTimeTasksAndCreatesMultiStepDefinition() {
        migrationHelper.createDatabase(RECURRING_SCHEDULE_MIGRATION_DATABASE_NAME, 14).apply {
            execSQL(
                "INSERT INTO workflows VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("workflow-v14", "一次性工作流", "读取当前时间", 1, 100L, 100L),
            )
            execSQL(
                "INSERT INTO scheduled_tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "scheduled-task-v14", "workflow-v14", "ONE_TIME", "COMPLETED", 200L,
                    "work-request-v14", null, 201L, 202L, null, 100L, 202L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            RECURRING_SCHEDULE_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT type, scheduleId, status FROM scheduled_tasks WHERE id = 'scheduled-task-v14'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("ONE_TIME", cursor.getString(cursor.getColumnIndexOrThrow("type")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("scheduleId")))
            assertEquals("COMPLETED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        }
        migrated.query("SELECT COUNT(*) AS scheduleCount FROM workflow_schedules").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("scheduleCount")))
        }
        migrated.query("SELECT goal FROM workflow_step_definitions WHERE workflowId = 'workflow-v14'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("读取当前时间", cursor.getString(cursor.getColumnIndexOrThrow("goal")))
        }
        migrated.close()
    }

    @Test
    fun migrate15To17BackfillsSingleStepDefinitionAndPreservesHistoricalRun() {
        migrationHelper.createDatabase(MULTI_STEP_MIGRATION_DATABASE_NAME, 15).apply {
            execSQL(
                "INSERT INTO workflows VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("workflow-v15", "历史单步骤", "读取当前时间", 1, 100L, 110L),
            )
            execSQL(
                "INSERT INTO workflow_runs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "workflow-run-v15", "workflow-v15", "MANUAL", null, null, "conversation-v15", "agent-run-v15",
                    "COMPLETED", "当前时间 10:00", null, 120L, 121L, 122L,
                ),
            )
            execSQL(
                "INSERT INTO workflow_steps VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "workflow-step-v15", "workflow-run-v15", 1, "AGENT_RUN", "COMPLETED", "执行 Agent 目标",
                    "读取当前时间", "agent-run-v15", "当前时间 10:00", null, 120L, 121L, 122L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MULTI_STEP_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT goal, sequence, idempotencyKey FROM workflow_step_definitions WHERE workflowId = 'workflow-v15'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("读取当前时间", cursor.getString(cursor.getColumnIndexOrThrow("goal")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("sequence")))
            assertEquals("legacy:workflow-v15:1", cursor.getString(cursor.getColumnIndexOrThrow("idempotencyKey")))
        }
        migrated.query("SELECT status, result, inputSnapshot, outputSnapshot, reusedFromStepId FROM workflow_steps WHERE id = 'workflow-step-v15'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("COMPLETED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("当前时间 10:00", cursor.getString(cursor.getColumnIndexOrThrow("result")))
            assertEquals("读取当前时间", cursor.getString(cursor.getColumnIndexOrThrow("inputSnapshot")))
            assertEquals("当前时间 10:00", cursor.getString(cursor.getColumnIndexOrThrow("outputSnapshot")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("reusedFromStepId")))
        }
        migrated.query("SELECT status, result, retryOfWorkflowRunId FROM workflow_runs WHERE id = 'workflow-run-v15'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("COMPLETED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("当前时间 10:00", cursor.getString(cursor.getColumnIndexOrThrow("result")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("retryOfWorkflowRunId")))
        }
        migrated.close()
    }

    @Test
    fun migrate16To17PreservesExistingNoteWithNullIdempotencyKey() {
        migrationHelper.createDatabase(NOTE_IDEMPOTENCY_MIGRATION_DATABASE_NAME, 16).apply {
            execSQL(
                "INSERT INTO agent_notes VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("note-v16", "升级前笔记", "保留原始内容", 100L, 100L),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            NOTE_IDEMPOTENCY_MIGRATION_DATABASE_NAME,
            17,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT id, title, content, idempotencyKey FROM agent_notes WHERE id = 'note-v16'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("note-v16", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("升级前笔记", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("保留原始内容", cursor.getString(cursor.getColumnIndexOrThrow("content")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("idempotencyKey")))
        }
        migrated.close()
    }

    @Test
    fun migrate17To18PreservesExistingMemoryAndCreatesEmptyOperationLedger() {
        migrationHelper.createDatabase(MEMORY_IDEMPOTENCY_MIGRATION_DATABASE_NAME, 17).apply {
            execSQL(
                "INSERT INTO agent_memories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "memory-v17",
                    "升级前长期记忆",
                    "migration",
                    "Episode",
                    "conversation-v17",
                    "run-v17",
                    "历史来源",
                    0.8,
                    1,
                    100L,
                    100L,
                    0,
                    null,
                    null,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MEMORY_IDEMPOTENCY_MIGRATION_DATABASE_NAME,
            18,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT id, content FROM agent_memories WHERE id = 'memory-v17'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("memory-v17", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("升级前长期记忆", cursor.getString(cursor.getColumnIndexOrThrow("content")))
        }
        migrated.query("SELECT COUNT(*) FROM agent_memory_operations").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate18To19PreservesOperationWithoutInventingResultSnapshot() {
        migrationHelper.createDatabase(MEMORY_VERIFICATION_MIGRATION_DATABASE_NAME, 18).apply {
            execSQL(
                "INSERT INTO agent_memories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "memory-v18",
                    "升级前长期记忆",
                    "migration",
                    "Episode",
                    "conversation-v18",
                    "run-v18",
                    "历史来源",
                    0.8,
                    1,
                    100L,
                    100L,
                    0,
                    null,
                    null,
                ),
            )
            execSQL(
                "INSERT INTO agent_memory_operations VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("tool-call-v18", "memory-v18", "legacy-payload-hash", 101L),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MEMORY_VERIFICATION_MIGRATION_DATABASE_NAME,
            19,
            true,
            *XiaoLingDatabase.migrations(),
        )

        // long: v18 operation 没有提交结果快照，升级只能保留原证据并把 resultHash 留空；恢复策略据此继续 fail-closed，不能把当前可编辑记录冒充历史结果。
        migrated.query(
            "SELECT memoryId, payloadHash, resultHash FROM agent_memory_operations WHERE idempotencyKey = 'tool-call-v18'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("memory-v18", cursor.getString(cursor.getColumnIndexOrThrow("memoryId")))
            assertEquals("legacy-payload-hash", cursor.getString(cursor.getColumnIndexOrThrow("payloadHash")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("resultHash")))
        }
        migrated.close()
    }

    @Test
    fun migrate19To20KeepsLegacyToolEventsWithoutInventingLedgerRows() = runBlocking {
        migrationHelper.createDatabase(TOOL_LEDGER_MIGRATION_DATABASE_NAME, 19).apply {
            execSQL(
                """
                INSERT INTO agent_runs (
                    id, retryOfRunId, conversationId, userMessageId, goal, status,
                    result, errorMessage, createdAt, updatedAt, completedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "run-v19-tool-ledger",
                    null,
                    "conversation-v19",
                    "message-v19",
                    "恢复旧工具验证",
                    "VERIFYING",
                    null,
                    null,
                    100L,
                    102L,
                    null,
                ),
            )
            execSQL(
                "INSERT INTO run_events VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "event-v19-tool-call",
                    "run-v19-tool-ledger",
                    "tool.call.validated",
                    "工具调用已校验：notes.create",
                    """{"id":"tool-call-v19","toolName":"notes.create","risk":"REQUIRES_APPROVAL","arguments":{"title":"旧笔记","content":"旧结果"}}""",
                    101L,
                ),
            )
            execSQL(
                "INSERT INTO run_events VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "event-v19-tool-result",
                    "run-v19-tool-ledger",
                    "tool.result",
                    "工具执行成功：notes.create",
                    """{"toolName":"notes.create","content":"已创建旧笔记","durationMs":10,"success":true,"verified":true,"memoryIdsUsed":[],"toolCallId":"tool-call-v19","replaySafety":"IDEMPOTENT_BY_KEY"}""",
                    102L,
                ),
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TOOL_LEDGER_MIGRATION_DATABASE_NAME,
            20,
            true,
            *XiaoLingDatabase.migrations(),
        ).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, TOOL_LEDGER_MIGRATION_DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
            .also { openedDatabase = it }
        val repository = RoomAgentRunRepository(context, database)

        assertEquals(2, repository.snapshot("run-v19-tool-ledger").events.size)
        assertEquals(0, repository.toolLedger("run-v19-tool-ledger").calls.size)
        assertEquals(0, repository.toolLedger("run-v19-tool-ledger").results.size)

        repository.appendEvent(
            runId = "run-v19-tool-ledger",
            type = "tool.verify",
            message = "工具验证通过：notes.create",
            metadata = RunEventMetadata.ToolVerification(
                toolName = "notes.create",
                status = ToolVerificationStatus.PASSED,
                toolCallId = "tool-call-v19",
            ),
        )

        assertEquals(3, repository.snapshot("run-v19-tool-ledger").events.size)
        assertEquals(0, repository.toolLedger("run-v19-tool-ledger").results.size)
    }

    @Test
    fun migrate20To21CreatesEmptyAgentProfileCatalogWithoutInventingGlobalBinding() = runBlocking {
        migrationHelper.createDatabase(AGENT_PROFILE_MIGRATION_DATABASE_NAME, 20).apply {
            execSQL(
                "INSERT INTO providers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "provider-v20",
                    "历史 Provider",
                    "https://example.test/v1",
                    "iv",
                    "ciphertext",
                    "model-v20",
                    "[\"model-v20\"]",
                    "[\"model-v20\"]",
                    "2026-07-19 12:00:00",
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            AGENT_PROFILE_MIGRATION_DATABASE_NAME,
            21,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT COUNT(*) FROM agent_profiles").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM providers WHERE id = 'provider-v20'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate21To22BackfillsStableTextPartsWithoutInventingToolParts() = runBlocking {
        migrationHelper.createDatabase(MESSAGE_PARTS_MIGRATION_DATABASE_NAME, 21).apply {
            execSQL(
                "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("conversation-v21", "历史消息", "", null, null, null, 100L, 200L),
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, conversationId, role, text, createdAt, origin, verifiedAgentContext,
                    providerId, providerName, model, apiMode, streaming, requestUrl,
                    firstTokenLatencyMs, latencyMs, promptTokens, completionTokens, totalTokens,
                    finishReason, errorKind, errorMessage
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "message-v21-user", "conversation-v21", "user", "旧用户消息", 110L, "USER", null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                ),
            )
            execSQL(
                """
                INSERT INTO messages (
                    id, conversationId, role, text, createdAt, origin, verifiedAgentContext,
                    providerId, providerName, model, apiMode, streaming, requestUrl,
                    firstTokenLatencyMs, latencyMs, promptTokens, completionTokens, totalTokens,
                    finishReason, errorKind, errorMessage
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "message-v21-agent", "conversation-v21", "assistant", "旧 Agent 总结", 120L, "AGENT_RESULT",
                    "{\"runId\":\"run-v21\",\"toolName\":\"app.current_time\",\"arguments\":{},\"success\":true,\"verificationStatus\":\"READABLE_ONLY\",\"rawResult\":\"11:58\"}",
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MESSAGE_PARTS_MIGRATION_DATABASE_NAME,
            22,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT id, text FROM messages ORDER BY createdAt").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("message-v21-user", cursor.getString(0))
            assertEquals("旧用户消息", cursor.getString(1))
            assertEquals(true, cursor.moveToNext())
            assertEquals("message-v21-agent", cursor.getString(0))
            assertEquals("旧 Agent 总结", cursor.getString(1))
        }
        migrated.query("SELECT id, messageId, sequence, type, text FROM message_parts ORDER BY messageId").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("message-v21-agent-text", cursor.getString(0))
            assertEquals("message-v21-agent", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("TEXT", cursor.getString(3))
            assertEquals("旧 Agent 总结", cursor.getString(4))
            assertEquals(true, cursor.moveToNext())
            assertEquals("message-v21-user-text", cursor.getString(0))
            assertEquals("message-v21-user", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("TEXT", cursor.getString(3))
            assertEquals("旧用户消息", cursor.getString(4))
            assertEquals(false, cursor.moveToNext())
        }
        migrated.close()
    }

    @Test
    fun migrate22To23AddsEmptyReasoningProvenanceWithoutInventingParts() = runBlocking {
        migrationHelper.createDatabase(REASONING_PARTS_MIGRATION_DATABASE_NAME, 22).apply {
            execSQL(
                """
                INSERT INTO message_parts (
                    id, messageId, sequence, type, text, toolName,
                    argumentsJson, result, success, verificationStatus, memoryIdsJson
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "part-v22-text", "message-v22", 0, "TEXT", "旧正文",
                    null, null, null, null, null, null,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            REASONING_PARTS_MIGRATION_DATABASE_NAME,
            23,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT type, reasoningSource, providerItemId, summaryIndex FROM message_parts WHERE id = 'part-v22-text'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("TEXT", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }
        migrated.query("SELECT COUNT(*) FROM message_parts WHERE type = 'REASONING'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate23To24AddsEmptyImagePayloadWithoutInventingParts() = runBlocking {
        migrationHelper.createDatabase(IMAGE_PARTS_MIGRATION_DATABASE_NAME, 23).apply {
            execSQL(
                """
                INSERT INTO message_parts (
                    id, messageId, sequence, type, text, toolName, argumentsJson, result,
                    success, verificationStatus, memoryIdsJson, reasoningSource, providerItemId, summaryIndex
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "part-v23-text", "message-v23", 0, "TEXT", "旧消息", null, null, null,
                    null, null, null, null, null, null,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            IMAGE_PARTS_MIGRATION_DATABASE_NAME,
            24,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT type, mimeType, fileName, binaryData, imageDetail FROM message_parts WHERE id = 'part-v23-text'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("TEXT", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getBlob(3))
            assertNull(cursor.getString(4))
        }
        migrated.query("SELECT COUNT(*) FROM message_parts WHERE type = 'IMAGE'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate24To25AddsEmptyDocumentPayloadWithoutInventingParts() = runBlocking {
        migrationHelper.createDatabase(DOCUMENT_PARTS_MIGRATION_DATABASE_NAME, 24).apply {
            execSQL(
                """
                INSERT INTO message_parts (
                    id, messageId, sequence, type, text, toolName, argumentsJson, result,
                    success, verificationStatus, memoryIdsJson, reasoningSource, providerItemId, summaryIndex,
                    mimeType, fileName, binaryData, imageDetail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "part-v24-image", "message-v24", 0, "IMAGE", null, null, null, null,
                    null, null, null, null, null, null,
                    "image/png", "legacy.png", byteArrayOf(1, 2, 3), "AUTO",
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            DOCUMENT_PARTS_MIGRATION_DATABASE_NAME,
            25,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT type, documentExtractedText, documentPageCount, documentDetail FROM message_parts WHERE id = 'part-v24-image'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("IMAGE", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
        }
        migrated.query("SELECT COUNT(*) FROM message_parts WHERE type = 'DOCUMENT'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate25To26CreatesKnowledgeTablesWithoutInventingDocuments() = runBlocking {
        migrationHelper.createDatabase(KNOWLEDGE_MIGRATION_DATABASE_NAME, 25).apply {
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            KNOWLEDGE_MIGRATION_DATABASE_NAME,
            26,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(26, cursor.getInt(0))
        }
        listOf(
            "knowledge_documents",
            "knowledge_chunks",
            "knowledge_chunks_fts",
            "knowledge_retrievals",
        ).forEach { table ->
            migrated.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migrated.close()
    }

    @Test
    fun migrate26To27AddsEmptyKnowledgeReferencesWithoutInventingEvidence() = runBlocking {
        migrationHelper.createDatabase(KNOWLEDGE_REFERENCE_MIGRATION_DATABASE_NAME, 26).apply {
            execSQL(
                """
                INSERT INTO message_parts (
                    id, messageId, sequence, type, text, toolName, argumentsJson, result,
                    success, verificationStatus, memoryIdsJson, reasoningSource, providerItemId, summaryIndex,
                    mimeType, fileName, binaryData, imageDetail, documentExtractedText, documentPageCount, documentDetail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "part-v26-tool", "message-v26", 0, "TOOL", null, "knowledge.search", "{}", "旧知识结果",
                    1, "READABLE_ONLY", "[]", null, null, null,
                    null, null, null, null, null, null, null,
                ),
            )
            execSQL(
                """
                INSERT INTO agent_tool_results (
                    toolCallId, runId, eventId, toolName, content, success, errorMessage, durationMs,
                    executorVerified, verificationStatus, verifiedEventId, memoryIdsJson, replaySafety,
                    receiptToolCallId, receiptOperationId, receiptIdempotencyKey, receiptStatus, createdAt, verifiedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "tool-call-v26", "run-v26", "event-v26", "knowledge.search", "旧知识结果", 1, null, 5L,
                    null, null, null, "[]", "RESTART_REQUIRED",
                    null, null, null, null, 10L, null,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            KNOWLEDGE_REFERENCE_MIGRATION_DATABASE_NAME,
            27,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT knowledgeReferencesJson FROM message_parts WHERE id = 'part-v26-tool'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        migrated.query("SELECT knowledgeReferencesJson FROM agent_tool_results WHERE toolCallId = 'tool-call-v26'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migrate27To28AddsNullableWorkerStopReasonWithoutInventingHistory() {
        migrationHelper.createDatabase(WORKER_STOP_REASON_MIGRATION_DATABASE_NAME, 27).apply {
            execSQL(
                """
                INSERT INTO workflow_runs (
                    id, workflowId, trigger, scheduledTaskId, plannedAt, conversationId,
                    agentRunId, status, result, errorMessage, createdAt, startedAt, completedAt, retryOfWorkflowRunId
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "workflow-run-v27", "workflow-v27", "SCHEDULED", "scheduled-task-v27", 100L,
                    "conversation-v27", null, "FAILED", null, "历史失败", 100L, 100L, 200L, null,
                ),
            )
            execSQL(
                """
                INSERT INTO scheduled_tasks (
                    id, workflowId, type, scheduleId, status, plannedAt, workRequestId,
                    workflowRunId, actualStartedAt, completedAt, errorMessage, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "scheduled-task-v27", "workflow-v27", "ONE_TIME", null, "FAILED", 100L, null,
                    "workflow-run-v27", 100L, 200L, "历史失败", 100L, 200L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            WORKER_STOP_REASON_MIGRATION_DATABASE_NAME,
            28,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT workerStopReasonCode, workerStopReasonName FROM workflow_runs",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.query(
            "SELECT workerStopReasonCode, workerStopReasonName FROM scheduled_tasks",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun migrate28To29CreatesEmptyProcessExitLedgerWithoutInventingHistory() {
        migrationHelper.createDatabase(PROCESS_EXIT_MIGRATION_DATABASE_NAME, 28).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            PROCESS_EXIT_MIGRATION_DATABASE_NAME,
            29,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT COUNT(*) FROM process_exit_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate32To33CreatesEmptyAnonymousAnswerabilityLedgerWithoutInventingHistory() {
        migrationHelper.createDatabase(ANSWERABILITY_SHADOW_MIGRATION_DATABASE_NAME, 32).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            ANSWERABILITY_SHADOW_MIGRATION_DATABASE_NAME,
            33,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query("SELECT COUNT(*) FROM knowledge_answerability_shadow_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        val columns = buildSet {
            migrated.query("PRAGMA table_info(knowledge_answerability_shadow_observations)").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue("匿名账本不得保存消息 ID", "persistedMessageId" !in columns)
        assertTrue("匿名账本不得保存 Run ID", "sourceRunId" !in columns)
        assertTrue("匿名账本不得保存 Provider 身份", "providerId" !in columns)
        assertTrue("匿名账本不得保存模型名", "model" !in columns)
        assertTrue("匿名账本不得保存问题正文", "question" !in columns)
        assertTrue("匿名账本不得保存答案正文", "candidateText" !in columns)
        assertTrue("匿名账本不得保存接口地址", "baseUrl" !in columns)
        assertTrue("匿名账本不得保存密钥", "apiKey" !in columns)
        migrated.close()
    }

    @Test
    fun migrate29To30CreatesEmbeddingIndexAndKeepsLegacyRetrievalLexicalOnly() {
        migrationHelper.createDatabase(EMBEDDING_MIGRATION_DATABASE_NAME, 29).apply {
            execSQL(
                "INSERT INTO knowledge_retrievals VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("retrieval-v29", "旧检索", "[]", "[]", "conversation-v29", "run-v29", 100L),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            EMBEDDING_MIGRATION_DATABASE_NAME,
            30,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            "SELECT embeddingProviderId, embeddingModel, embeddingStatus FROM knowledge_retrievals WHERE id = 'retrieval-v29'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
            assertEquals("LEXICAL_ONLY", cursor.getString(2))
        }
        migrated.query("SELECT COUNT(*) FROM knowledge_chunk_embeddings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate30To31KeepsLegacyCalibrationDiagnosticsUnknown() {
        migrationHelper.createDatabase(EMBEDDING_CALIBRATION_MIGRATION_DATABASE_NAME, 30).apply {
            execSQL(
                """
                INSERT INTO knowledge_retrievals (
                    id, query, chunkIdsJson, documentIdsJson, sourceConversationId, sourceRunId,
                    embeddingProviderId, embeddingModel, embeddingStatus, createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "retrieval-v30",
                    "旧语义检索",
                    "[]",
                    "[]",
                    "conversation-v30",
                    "run-v30",
                    "provider-v30",
                    "embedding-v30",
                    "USED",
                    100L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            EMBEDDING_CALIBRATION_MIGRATION_DATABASE_NAME,
            31,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            """
            SELECT embeddingTopScore, embeddingSecondScore, embeddingScoreMargin, embeddingCandidateCount
            FROM knowledge_retrievals WHERE id = 'retrieval-v30'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        migrated.close()
    }

    @Test
    fun migrate31To32KeepsLegacyRelativeDiagnosticsUnknown() {
        migrationHelper.createDatabase(RELATIVE_DIAGNOSTICS_MIGRATION_DATABASE_NAME, 31).apply {
            execSQL(
                """
                INSERT INTO knowledge_retrievals (
                    id, query, chunkIdsJson, documentIdsJson, sourceConversationId, sourceRunId,
                    embeddingProviderId, embeddingModel, embeddingStatus,
                    embeddingTopScore, embeddingSecondScore, embeddingScoreMargin, embeddingCandidateCount,
                    createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "retrieval-v31",
                    "旧相对分布观测",
                    "[]",
                    "[]",
                    "conversation-v31",
                    "run-v31",
                    "provider-v31",
                    "embedding-v31",
                    "USED",
                    0.8,
                    0.7,
                    0.1,
                    10,
                    100L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            RELATIVE_DIAGNOSTICS_MIGRATION_DATABASE_NAME,
            32,
            true,
            *XiaoLingDatabase.migrations(),
        )

        migrated.query(
            """
            SELECT embeddingScoreMean, embeddingScoreStandardDeviation, embeddingTopScoreZScore
            FROM knowledge_retrievals WHERE id = 'retrieval-v31'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.close()
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
        private const val RETRY_LINK_MIGRATION_DATABASE_NAME = "xiaoling-retry-link-migration-test"
        private const val MEMORY_FTS_MIGRATION_DATABASE_NAME = "xiaoling-memory-fts-migration-test"
        private const val MEMORY_CANDIDATE_MIGRATION_DATABASE_NAME = "xiaoling-memory-candidate-migration-test"
        private const val SKILL_MIGRATION_DATABASE_NAME = "xiaoling-skill-migration-test"
        private const val WORKFLOW_MIGRATION_DATABASE_NAME = "xiaoling-workflow-migration-test"
        private const val SCHEDULED_TASK_MIGRATION_DATABASE_NAME = "xiaoling-scheduled-task-migration-test"
        private const val RECURRING_SCHEDULE_MIGRATION_DATABASE_NAME = "xiaoling-recurring-schedule-migration-test"
        private const val MULTI_STEP_MIGRATION_DATABASE_NAME = "xiaoling-multi-step-migration-test"
        private const val NOTE_IDEMPOTENCY_MIGRATION_DATABASE_NAME = "xiaoling-note-idempotency-migration-test"
        private const val MEMORY_IDEMPOTENCY_MIGRATION_DATABASE_NAME = "xiaoling-memory-idempotency-migration-test"
        private const val MEMORY_VERIFICATION_MIGRATION_DATABASE_NAME = "xiaoling-memory-verification-migration-test"
        private const val TOOL_LEDGER_MIGRATION_DATABASE_NAME = "xiaoling-tool-ledger-migration-test"
        private const val AGENT_PROFILE_MIGRATION_DATABASE_NAME = "xiaoling-agent-profile-migration-test"
        private const val MESSAGE_PARTS_MIGRATION_DATABASE_NAME = "xiaoling-message-parts-migration-test"
        private const val REASONING_PARTS_MIGRATION_DATABASE_NAME = "xiaoling-reasoning-parts-migration-test"
        private const val IMAGE_PARTS_MIGRATION_DATABASE_NAME = "xiaoling-image-parts-migration-test"
        private const val DOCUMENT_PARTS_MIGRATION_DATABASE_NAME = "xiaoling-document-parts-migration-test"
        private const val KNOWLEDGE_MIGRATION_DATABASE_NAME = "xiaoling-knowledge-migration-test"
        private const val KNOWLEDGE_REFERENCE_MIGRATION_DATABASE_NAME = "xiaoling-knowledge-reference-migration-test"
        private const val WORKER_STOP_REASON_MIGRATION_DATABASE_NAME = "xiaoling-worker-stop-reason-migration-test"
        private const val PROCESS_EXIT_MIGRATION_DATABASE_NAME = "xiaoling-process-exit-migration-test"
        private const val EMBEDDING_MIGRATION_DATABASE_NAME = "xiaoling-embedding-migration-test"
        private const val EMBEDDING_CALIBRATION_MIGRATION_DATABASE_NAME = "xiaoling-embedding-calibration-migration-test"
        private const val RELATIVE_DIAGNOSTICS_MIGRATION_DATABASE_NAME = "xiaoling-relative-diagnostics-migration-test"
        private const val ANSWERABILITY_SHADOW_MIGRATION_DATABASE_NAME = "xiaoling-answerability-shadow-migration-test"
    }
}
