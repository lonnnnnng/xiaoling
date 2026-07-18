package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

@Database(
    entities = [
        ProviderEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AgentRunEntity::class,
        AgentStepEntity::class,
        ApprovalRequestEntity::class,
        RunEventEntity::class,
        AgentMemoryEntity::class,
        AgentMemoryFtsEntity::class,
        AgentMemoryCandidateEntity::class,
        AgentNoteEntity::class,
        AgentSkillEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        WorkflowStepEntity::class,
        ScheduledTaskEntity::class,
        WorkflowScheduleEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class XiaoLingDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun agentNoteDao(): AgentNoteDao
    abstract fun agentSkillDao(): AgentSkillDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        const val CURRENT_VERSION = 15
        const val DATABASE_NAME = "xiaoling.db"

        @Volatile
        private var instance: XiaoLingDatabase? = null

        fun getInstance(context: Context): XiaoLingDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    XiaoLingDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(*migrations())
                    // long: 这是小灵首次引入 Room 的数据库；后续表结构变化必须补 Migration，不能再丢弃用户会话和运行记录。
                    .build()
                    .also { instance = it }
            }
        }

        fun resetInstanceForRestore() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 审批请求必须独立成表，不能只藏在 RunEvent 文本里；这样用户确认动作才有过期策略、决定结果和后续恢复依据。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `approval_requests` (
                        `id` TEXT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `toolCallId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `toolDescription` TEXT NOT NULL,
                        `risk` TEXT NOT NULL,
                        `argumentsJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `decisionReason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `decidedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_approval_requests_conversationId_status_createdAt` ON `approval_requests` (`conversationId`, `status`, `createdAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_approval_requests_runId_createdAt` ON `approval_requests` (`runId`, `createdAt`)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 长期记忆必须独立落库，不能混在普通聊天消息里；这样后续才能让用户按记忆维度查看、撤销和审计 Agent 复用过的个人事实。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_memories` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `type` TEXT NOT NULL DEFAULT 'Episode',
                        `sourceConversationId` TEXT,
                        `sourceRunId` TEXT,
                        `sourceSummary` TEXT NOT NULL DEFAULT '',
                        `confidence` REAL NOT NULL DEFAULT 0.8,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_memories_createdAt` ON `agent_memories` (`createdAt`)",
                )
                createAgentNotesTable(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 开发阶段曾短暂存在只含 content/tags 的 v3 记忆表；v4 补齐来源、类型和启用状态，保证本机调试数据也能走到正式可审计结构。
                addColumnIfMissing(db, "agent_memories", "type", "TEXT NOT NULL DEFAULT 'Episode'")
                addColumnIfMissing(db, "agent_memories", "sourceConversationId", "TEXT")
                addColumnIfMissing(db, "agent_memories", "sourceRunId", "TEXT")
                addColumnIfMissing(db, "agent_memories", "sourceSummary", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "agent_memories", "confidence", "REAL NOT NULL DEFAULT 0.8")
                addColumnIfMissing(db, "agent_memories", "enabled", "INTEGER NOT NULL DEFAULT 1")
                createAgentNotesTable(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 消息来源决定普通回复能否作为工具事实使用；旧数据统一标为 LEGACY，再由业务层按角色保守降级，避免升级后错误放大历史幻觉。
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'LEGACY'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: Agent 模型总结与 Runtime 审计事实必须分开保存；可信上下文只读取该列中的确定性调用记录，不信任模型自由文本。
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `verifiedAgentContext` TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `run_events` ADD COLUMN `metadataJson` TEXT")
                val legacyEvents = buildList {
                    db.query("SELECT `id`, `type`, `message` FROM `run_events`").use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow("id")
                        val typeIndex = cursor.getColumnIndexOrThrow("type")
                        val messageIndex = cursor.getColumnIndexOrThrow("message")
                        while (cursor.moveToNext()) {
                            val message = cursor.getString(messageIndex)
                            val metadata = runCatching { JSONObject(message) }.getOrNull()
                            if (metadata != null) {
                                val type = cursor.getString(typeIndex)
                                add(
                                    LegacyRunEventMigration(
                                        id = cursor.getString(idIndex),
                                        metadataJson = message,
                                        readableMessage = metadata.toReadableRunEventMessage(type),
                                    ),
                                )
                            }
                        }
                    }
                }
                // long: v6 把工具参数和结果编码在 message；仅迁移可解析的 JSON object，并把 message 改成可读摘要，普通状态文本保持原样。
                legacyEvents.forEach { event ->
                    db.execSQL(
                        "UPDATE `run_events` SET `message` = ?, `metadataJson` = ? WHERE `id` = ?",
                        arrayOf<Any?>(event.readableMessage, event.metadataJson, event.id),
                    )
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 重试必须创建新 Run 并保留来源 Run；旧数据没有来源关联，新增可空列即可保持全部历史状态、结果和审计事件不变。
                db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `retryOfRunId` TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `agent_memories_fts`
                    USING FTS4(
                        `memoryId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sourceSummary` TEXT NOT NULL,
                        tokenize=unicode61
                    )
                    """.trimIndent(),
                )
                // long: 旧记忆升级后必须立即可搜索；迁移一次性回填索引，后续新增、编辑、启停和删除由 Repository 事务同步主表与 FTS。
                db.execSQL(
                    """
                    INSERT INTO `agent_memories_fts` (`memoryId`, `content`, `tags`, `type`, `sourceSummary`)
                    SELECT `id`, `content`, `tags`, `type`, `sourceSummary` FROM `agent_memories`
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 候选与正式记忆分表，升级后旧记忆继续保持已确认语义；新候选只有用户确认后才会进入 agent_memories 和 FTS。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_memory_candidates` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `normalizedContent` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `topicKey` TEXT NOT NULL,
                        `sourceConversationId` TEXT,
                        `sourceRunId` TEXT,
                        `sourceSummary` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `status` TEXT NOT NULL,
                        `sensitiveCategory` TEXT,
                        `relatedMemoryId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_memory_candidates_status_createdAt` ON `agent_memory_candidates` (`status`, `createdAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_memory_candidates_normalizedContent` ON `agent_memory_candidates` (`normalizedContent`)",
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 过期和最近引用都是可空字段，旧记忆默认永久保留且不伪造引用时间，保证升级不会静默改变用户事实的生命周期。
                db.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `expiresAt` INTEGER")
                db.execSQL("ALTER TABLE `agent_memories` ADD COLUMN `lastReferencedAt` INTEGER")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: Skill 定义进入 Room 后，用户启停和本地导入才能随数据库备份恢复；表中只保存声明式文本，不保存脚本或任意可执行代码。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_skills` (
                        `id` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `toolNamesJson` TEXT NOT NULL,
                        `keywordsJson` TEXT NOT NULL,
                        `triggerExamplesJson` TEXT NOT NULL,
                        `requiredAndroidPermissionsJson` TEXT NOT NULL,
                        `declaredRisk` TEXT NOT NULL,
                        `failureRecovery` TEXT NOT NULL,
                        `completionCriteria` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `importedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_skills_source_enabled_updatedAt` ON `agent_skills` (`source`, `enabled`, `updatedAt`)",
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 工作流定义、每次运行和步骤必须独立落库；先建立手动执行 Ledger，后续调度器只能追加触发来源，不能另建一套不可审计状态。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workflows` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflows_enabled_updatedAt` ON `workflows` (`enabled`, `updatedAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workflow_runs` (
                        `id` TEXT NOT NULL,
                        `workflowId` TEXT NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `agentRunId` TEXT,
                        `status` TEXT NOT NULL,
                        `result` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_createdAt` ON `workflow_runs` (`workflowId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_runs_status_createdAt` ON `workflow_runs` (`status`, `createdAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workflow_runs_agentRunId` ON `workflow_runs` (`agentRunId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workflow_steps` (
                        `id` TEXT NOT NULL,
                        `workflowRunId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        `agentRunId` TEXT,
                        `result` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workflow_steps_workflowRunId_sequence` ON `workflow_steps` (`workflowRunId`, `sequence`)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workflow_runs` ADD COLUMN `scheduledTaskId` TEXT")
                db.execSQL("ALTER TABLE `workflow_runs` ADD COLUMN `plannedAt` INTEGER")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workflow_runs_scheduledTaskId` ON `workflow_runs` (`scheduledTaskId`)")
                // long: 一次性计划先独立保存系统 WorkRequest 与业务 Run 的关联，进程重建后仍能区分“已入队、已启动、已阻断”和最终结果。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
                        `id` TEXT NOT NULL,
                        `workflowId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `plannedAt` INTEGER NOT NULL,
                        `workRequestId` TEXT,
                        `workflowRunId` TEXT,
                        `actualStartedAt` INTEGER,
                        `completedAt` INTEGER,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_workflowId_plannedAt` ON `scheduled_tasks` (`workflowId`, `plannedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_status_plannedAt` ON `scheduled_tasks` (`status`, `plannedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_tasks_workRequestId` ON `scheduled_tasks` (`workRequestId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_tasks_workflowRunId` ON `scheduled_tasks` (`workflowRunId`)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scheduled_tasks` ADD COLUMN `scheduleId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_scheduleId_plannedAt` ON `scheduled_tasks` (`scheduleId`, `plannedAt`)")
                // long: 周期定义与每次执行实例分表保存；规则只指向下一次物化任务，历史 ScheduledTask 和 Workflow Run 永久保留各自结果。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workflow_schedules` (
                        `id` TEXT NOT NULL,
                        `workflowId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `timeOfDayMinutes` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER,
                        `zoneId` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `nextTaskId` TEXT,
                        `nextPlannedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workflow_schedules_workflowId` ON `workflow_schedules` (`workflowId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_schedules_enabled_nextPlannedAt` ON `workflow_schedules` (`enabled`, `nextPlannedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workflow_schedules_nextTaskId` ON `workflow_schedules` (`nextTaskId`)")
            }
        }

        fun migrations(): Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        )

        private fun createAgentNotesTable(db: SupportSQLiteDatabase) {
            // long: 笔记是第一批可验证本地写入工具，独立成表后 notes.create 可以写入再回读验证，而不是把笔记混入普通聊天消息。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_notes` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_notes_createdAt` ON `agent_notes` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_notes_updatedAt` ON `agent_notes` (`updatedAt`)")
        }

        private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            val exists = db.query("PRAGMA table_info(`$table`)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        private fun JSONObject.toReadableRunEventMessage(type: String): String {
            val toolName = optString("toolName")
                .ifBlank { optString("name") }
                .ifBlank { optString("tool") }
            val toolSuffix = toolName.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            val reason = optString("reason").ifBlank { optString("decisionReason") }
            return when (type) {
                "tool.call.proposed" -> "模型提出工具调用$toolSuffix"
                "tool.call.validated" -> "工具调用已校验$toolSuffix"
                "tool.result" -> when {
                    has("success") && optBoolean("success") -> "工具执行成功$toolSuffix"
                    has("success") -> "工具执行失败$toolSuffix"
                    else -> "工具执行结果$toolSuffix"
                }
                "tool.verify" -> "工具验证完成$toolSuffix"
                "approval.requested" -> "等待审批$toolSuffix"
                "approval.request_decided" -> "审批状态已更新$toolSuffix"
                "approval.granted" -> "工具审批通过$toolSuffix"
                "approval.denied" -> "工具审批拒绝$toolSuffix"
                "approval.skipped" -> "跳过工具审批$toolSuffix"
                "run.recovered",
                "run.failed",
                "run.timeout",
                "run.cancelled",
                "run.budget_exhausted",
                "llm.summarize.fallback" -> reason.ifBlank { "已迁移事件：$type" }
                else -> "已迁移事件：$type"
            }
        }

        private data class LegacyRunEventMigration(
            val id: String,
            val metadataJson: String,
            val readableMessage: String,
        )
    }
}
