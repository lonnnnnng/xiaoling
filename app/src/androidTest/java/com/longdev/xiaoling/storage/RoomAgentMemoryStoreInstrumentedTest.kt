package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryUpdate
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
class RoomAgentMemoryStoreInstrumentedTest {
    private lateinit var database: XiaoLingDatabase
    private lateinit var store: RoomAgentMemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomAgentMemoryStore(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun memoryManagementKeepsFtsStateAndSourceAuditConsistent() = runBlocking {
        val compact = store.remember(
            content = "User prefers compact dashboards",
            tags = "ui compact",
            type = "Preference",
            source = AgentMemorySource(
                conversationId = "conversation-1",
                runId = "run-1",
                summary = "用户明确表达界面偏好",
            ),
            confidence = 0.9,
        )
        val chinese = store.remember(
            content = "用户喜欢紧凑界面",
            tags = "界面",
            type = "Preference",
            source = AgentMemorySource(
                conversationId = "conversation-2",
                runId = "run-2",
                summary = "来自第二个会话",
            ),
            confidence = 0.8,
        )
        val literalWildcard = store.remember(
            content = "Coverage is 100%_verified",
            tags = "quality",
            type = "ProjectFact",
            source = AgentMemorySource(
                conversationId = "conversation-3",
                runId = "run-3",
                summary = "来自第三个会话",
            ),
            confidence = 0.7,
        )

        assertEquals("conversation-1", store.get(compact.id)?.sourceConversationId)
        assertEquals("run-1", store.get(compact.id)?.sourceRunId)
        assertEquals(listOf(compact.id), store.list("comp", AgentMemoryFilter.ALL).map { it.id })
        assertEquals(listOf(chinese.id), store.list("紧凑", AgentMemoryFilter.ALL).map { it.id })
        assertEquals(listOf(chinese.id), store.list("紧凑 界面", AgentMemoryFilter.ALL).map { it.id })
        assertEquals(listOf(literalWildcard.id), store.list("%_", AgentMemoryFilter.ALL).map { it.id })

        val pinned = store.setPinned(chinese.id, true)
        assertEquals(true, pinned?.pinned)
        assertEquals(chinese.id, store.list("", AgentMemoryFilter.ALL).first().id)

        val disabled = store.setEnabled(compact.id, false)
        assertEquals(false, disabled?.enabled)
        assertFalse(store.search("compact", limit = 10, enabledOnly = true).any { it.id == compact.id })
        assertEquals(listOf(compact.id), store.list("compact", AgentMemoryFilter.DISABLED).map { it.id })

        val updated = store.update(
            memoryId = chinese.id,
            update = AgentMemoryUpdate(
                content = "用户喜欢信息密度高的界面",
                tags = "界面 密度",
                type = "ProfileFact",
                confidence = 0.95,
            ),
        )
        assertEquals("ProfileFact", updated?.type)
        assertEquals("conversation-2", updated?.sourceConversationId)
        assertEquals("run-2", updated?.sourceRunId)
        assertTrue(store.list("信息密度", AgentMemoryFilter.ALL).any { it.id == chinese.id })
        assertFalse(store.list("紧凑界面", AgentMemoryFilter.ALL).any { it.id == chinese.id })

        assertTrue(store.delete(chinese.id))
        assertNull(database.agentMemoryDao().getMemory(chinese.id))
        assertFalse(store.list("信息密度", AgentMemoryFilter.ALL).any { it.id == chinese.id })
        assertFalse(store.delete(chinese.id))
    }
}
