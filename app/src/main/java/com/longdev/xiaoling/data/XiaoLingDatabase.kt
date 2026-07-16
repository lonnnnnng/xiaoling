package com.longdev.xiaoling.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AgentRunEntity::class,
        AgentStepEntity::class,
        RunEventEntity::class,
    ],
    version = 1,
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
                    // long: 这是小灵首次引入 Room 的数据库；后续表结构变化必须补 Migration，不能再丢弃用户会话和运行记录。
                    .build()
                    .also { instance = it }
            }
        }
    }
}
