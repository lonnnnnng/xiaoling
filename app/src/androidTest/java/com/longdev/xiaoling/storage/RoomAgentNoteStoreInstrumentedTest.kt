package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentNoteDeletedException
import com.longdev.xiaoling.agent.AgentNoteIdempotencyConflictException
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentNoteStoreInstrumentedTest {
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
    fun sameToolCallReturnsOriginalNoteAfterDatabaseReopenAndRejectsPayloadDrift() = runBlocking {
        var store = openStore()
        val first = store.create(
            title = "进程恢复笔记",
            content = "写入后进程终止也不能重复创建。",
            idempotencyKey = "tool-call-note-recovery",
        )

        database?.close()
        database = null
        store = openStore()

        val replay = store.create(
            title = "进程恢复笔记",
            content = "写入后进程终止也不能重复创建。",
            idempotencyKey = "tool-call-note-recovery",
        )
        val conflict = runCatching {
            store.create(
                title = "被篡改标题",
                content = "写入后进程终止也不能重复创建。",
                idempotencyKey = "tool-call-note-recovery",
            )
        }.exceptionOrNull()

        assertEquals(first.id, replay.id)
        assertEquals(listOf(first.id), store.list(10).map { it.id })
        assertTrue(conflict is AgentNoteIdempotencyConflictException)
    }

    @Test
    fun listSearchAndGetServeTheReadOnlyManagementPage() = runBlocking {
        val store = openStore()
        val first = store.create(
            title = "第一条本地笔记",
            content = "用于验证最近列表。",
            idempotencyKey = "tool-call-note-management-first",
        )
        val second = store.create(
            title = "第二条本地笔记",
            content = "正文包含唯一检索词：海棠。",
            idempotencyKey = "tool-call-note-management-second",
        )

        assertEquals(setOf(first.id, second.id), store.list(10).map { it.id }.toSet())
        assertEquals(listOf(second.id), store.search("海棠", 10).map { it.id })
        assertEquals(second, store.get(second.id))
    }

    @Test
    fun userDeleteClearsContentAndPreventsHistoricalToolReplay() = runBlocking {
        val store = openStore()
        val target = store.create(
            title = "需要撤回的笔记",
            content = "这段用户内容必须被清除。",
            idempotencyKey = "tool-call-note-user-delete",
        )
        val retained = store.create(
            title = "保留的笔记",
            content = "删除不能影响其他内容。",
            idempotencyKey = "tool-call-note-retained",
        )

        assertTrue(store.delete(target.id))
        assertNull(store.get(target.id))
        assertEquals(listOf(retained.id), store.list(10).map { it.id })
        assertTrue(store.search("用户内容", 10).isEmpty())
        assertFalse(store.delete(target.id))

        val tombstone = checkNotNull(
            checkNotNull(database).agentNoteDao()
                .getNoteByIdempotencyKey("tool-call-note-user-delete"),
        )
        assertEquals("", tombstone.title)
        assertEquals("", tombstone.content)
        val replayError = runCatching {
            store.create(
                title = "需要撤回的笔记",
                content = "这段用户内容必须被清除。",
                idempotencyKey = "tool-call-note-user-delete",
            )
        }.exceptionOrNull()
        assertTrue(replayError is AgentNoteDeletedException)
    }

    @Test
    fun debugProbeCleanupDeletesOnlyTheRequestedNote() = runBlocking {
        val store = openStore()
        val target = store.create(
            title = "第152阶段测试笔记",
            content = "验收完成后应删除。",
            idempotencyKey = "tool-call-stage152-cleanup",
        )
        val retained = store.create(
            title = "用户笔记",
            content = "Debug 清理不能影响其他笔记。",
            idempotencyKey = "tool-call-user-note",
        )

        val deletedCount = checkNotNull(database).agentNoteDao().deleteNote(target.id)

        assertEquals(1, deletedCount)
        assertEquals(listOf(retained.id), store.list(10).map { it.id })
    }

    private fun openStore(): RoomAgentNoteStore {
        val opened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        database = opened
        return RoomAgentNoteStore(context, opened)
    }

    companion object {
        private const val DATABASE_NAME = "xiaoling-note-store-instrumented-test.db"
    }
}
