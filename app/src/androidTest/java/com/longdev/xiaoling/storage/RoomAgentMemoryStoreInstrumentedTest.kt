package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
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

        val deletedSnapshot = store.delete(chinese.id)
        assertEquals(chinese.id, deletedSnapshot?.id)
        assertNull(database.agentMemoryDao().getMemory(chinese.id))
        assertFalse(store.list("信息密度", AgentMemoryFilter.ALL).any { it.id == chinese.id })
        assertNull(store.delete(chinese.id))

        val restored = store.restore(checkNotNull(deletedSnapshot))
        assertEquals(chinese.id, restored.id)
        assertTrue(store.list("信息密度", AgentMemoryFilter.ALL).any { it.id == chinese.id })
    }

    @Test
    fun candidatesRequireConfirmationAndKeepSensitiveRawValuesOutOfDatabase() = runBlocking {
        val pending = store.createCandidate(
            userText = "我喜欢紧凑的界面布局",
            source = AgentMemorySource("conversation-candidate", "run-candidate", "用户明确表达偏好"),
        )

        assertEquals(AgentMemoryCandidateStatus.PENDING, pending?.status)
        assertFalse(store.search("紧凑", 10).any())
        val repeatedPending = store.createCandidate(
            userText = "我喜欢紧凑的界面布局",
            source = AgentMemorySource("conversation-candidate", "run-candidate-2", "同一事实再次出现"),
        )
        assertEquals(pending?.id, repeatedPending?.id)
        val accepted = store.acceptCandidate(checkNotNull(pending).id)
        assertEquals(AgentMemoryCandidateStatus.ACCEPTED, accepted?.status)
        assertTrue(store.search("紧凑", 10).any())

        val blocked = store.createCandidate(
            userText = "请记住我的 API Key 是 sk-do-not-store-this-value",
            source = AgentMemorySource("conversation-secret", null, "包含敏感值的用户陈述"),
        )
        assertEquals(AgentMemoryCandidateStatus.BLOCKED_SENSITIVE, blocked?.status)
        assertEquals("", blocked?.content)
        assertFalse(
            store.listCandidates(limit = 20)
                .any { it.content.contains("sk-do-not-store-this-value") || it.sourceSummary.contains("sk-do-not-store-this-value") },
        )
    }

    @Test
    fun directRememberBlocksSensitiveContentAndReusesDuplicateMemory() = runBlocking {
        val source = AgentMemorySource("conversation-direct", "run-direct", "用户批准写入")
        val first = store.remember("User prefers compact dashboards", "ui", "Preference", source, 0.9)
        val duplicate = store.remember(" User prefers compact dashboards. ", "ui", "Preference", source, 0.9)

        assertEquals(first.id, duplicate.id)
        val error = runCatching {
            store.remember("我的密码是 MySecret!2026", "account", "ProfileFact", source, 0.9)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(error?.message.orEmpty().contains("MySecret!2026"))
        assertFalse(store.list("MySecret", AgentMemoryFilter.ALL).any())

        val tagged = runCatching {
            store.remember("用户喜欢紧凑界面", "ghp_1234567890abcdef", "Preference", source, 0.9)
        }.exceptionOrNull()
        assertTrue(tagged is IllegalArgumentException)
        assertFalse(store.list("ghp_1234567890abcdef", AgentMemoryFilter.ALL).any())

        val edited = runCatching {
            store.update(
                first.id,
                AgentMemoryUpdate(
                    content = first.content,
                    tags = "AIza1234567890abcdef",
                    type = first.type,
                    confidence = first.confidence,
                ),
            )
        }.exceptionOrNull()
        assertTrue(edited is IllegalArgumentException)
    }
}
