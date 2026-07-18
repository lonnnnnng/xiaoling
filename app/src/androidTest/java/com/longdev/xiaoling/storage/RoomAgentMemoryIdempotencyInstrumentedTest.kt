package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentMemoryIdempotencyConflictException
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentMemoryIdempotencyInstrumentedTest {
    private lateinit var context: Context
    private var database: XiaoLingDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun sameToolCallReturnsOriginalMemoryAfterDatabaseReopenAndRejectsPayloadDrift() = runBlocking {
        val source = AgentMemorySource("conversation-memory", "run-memory", "用户确认保存")
        var store = openStore()
        val first = store.remember(
            content = "用户喜欢紧凑界面",
            tags = "ui",
            type = "Preference",
            source = source,
            confidence = 0.8,
            idempotencyKey = "tool-call-memory-recovery",
        )

        database?.close()
        database = null
        store = openStore()

        val replay = store.remember(
            content = "用户喜欢紧凑界面",
            tags = "ui",
            type = "Preference",
            source = source,
            confidence = 0.8,
            idempotencyKey = "tool-call-memory-recovery",
        )
        val conflict = runCatching {
            store.remember(
                content = "用户喜欢宽松界面",
                tags = "ui",
                type = "Preference",
                source = source,
                confidence = 0.8,
                idempotencyKey = "tool-call-memory-recovery",
            )
        }.exceptionOrNull()

        assertEquals(first.id, replay.id)
        assertEquals(listOf(first.id), store.list("", com.longdev.xiaoling.agent.AgentMemoryFilter.ALL).map { it.id })
        assertTrue(conflict is AgentMemoryIdempotencyConflictException)

        store.delete(first.id)
        val deletedTargetReplay = runCatching {
            store.remember(
                content = "用户喜欢紧凑界面",
                tags = "ui",
                type = "Preference",
                source = source,
                confidence = 0.8,
                idempotencyKey = "tool-call-memory-recovery",
            )
        }.exceptionOrNull()

        assertTrue(deletedTargetReplay is IllegalStateException)
        assertTrue(deletedTargetReplay?.message.orEmpty().contains("已不存在"))
        assertTrue(store.list("", com.longdev.xiaoling.agent.AgentMemoryFilter.ALL).isEmpty())
    }

    private fun openStore(): RoomAgentMemoryStore {
        val opened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        database = opened
        return RoomAgentMemoryStore(context, opened)
    }

    companion object {
        private const val DATABASE_NAME = "xiaoling-memory-idempotency-instrumented-test.db"
    }
}
