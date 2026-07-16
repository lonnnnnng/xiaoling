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
    ],
    version = 2,
    exportSchema = false,
)
abstract class XiaoLingDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentRunDao(): AgentRunDao

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
                    .addMigrations(MIGRATION_1_2)
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
    }
}
