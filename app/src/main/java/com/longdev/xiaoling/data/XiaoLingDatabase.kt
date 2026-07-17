package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        AgentNoteEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class XiaoLingDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun agentNoteDao(): AgentNoteDao

    companion object {
        @Volatile
        private var instance: XiaoLingDatabase? = null

        fun getInstance(context: Context): XiaoLingDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    XiaoLingDatabase::class.java,
                    "xiaoling.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // long: 这是小灵首次引入 Room 的数据库；后续表结构变化必须补 Migration，不能再丢弃用户会话和运行记录。
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // long: 审批请求必须独立成表，不能只藏在 RunEvent 文本里；这样用户确认动作才有有效期、决定结果和后续恢复依据。
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
    }
}
