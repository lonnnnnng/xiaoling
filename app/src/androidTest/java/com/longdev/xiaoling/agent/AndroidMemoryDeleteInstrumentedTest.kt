package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 该探针使用隔离 Room 验证生产 Registry 的记忆删除链，证明稳定 ID、提交账本和当前不可见回读一致，不接触用户数据库。
 */
@RunWith(AndroidJUnit4::class)
class AndroidMemoryDeleteInstrumentedTest {
    @Test
    fun foregroundRegistryDeletesOnlySearchedAndConfirmedMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val memoryStore = RoomAgentMemoryStore(context, database)
        try {
            val fixture = memoryStore.remember(
                content = "stage220 Redmi 长期记忆删除夹具",
                tags = "stage220",
                type = "Episode",
                source = AgentMemorySource(
                    conversationId = "conversation-stage220",
                    runId = "run-stage220-source",
                    summary = "第 220 阶段隔离夹具",
                ),
                confidence = 0.8,
            )
            val ambiguousFixture = memoryStore.remember(
                content = "stage220 Redmi 长期记忆删除夹具另一条",
                tags = "stage220",
                type = "Episode",
                source = AgentMemorySource(
                    conversationId = "conversation-stage220",
                    runId = "run-stage220-source-ambiguous",
                    summary = "第 220 阶段多结果夹具",
                ),
                confidence = 0.8,
            )
            val registry = XiaoLingToolRegistry(
                clock = SystemAgentClock(),
                conversationStore = RoomAgentConversationStore(context, database),
                noteStore = RoomAgentNoteStore(context, database),
                memoryStore = memoryStore,
                knowledgeStore = RoomKnowledgeDocumentStore(context, database),
            ).also {
                it.bindRunContext(
                    AgentToolExecutionContext(
                        conversationId = "conversation-stage220",
                        userMessageId = "message-stage220",
                        runId = "run-stage220-delete",
                        goal = "删除 stage220 Redmi 长期记忆删除夹具",
                        executionOrigin = AgentExecutionOrigin.FOREGROUND,
                        invocationSource = AgentInvocationSource.DIRECT,
                    ),
                )
            }
            val deleteCall = ToolCall(
                id = "tool-call-stage220-memory-delete",
                name = "memory.delete",
                arguments = mapOf("memory_id" to fixture.id),
                risk = ToolRisk.REQUIRES_APPROVAL,
            )

            val bypassed = registry.execute(deleteCall)
            assertFalse(bypassed.success)
            assertNotNull(memoryStore.get(fixture.id))

            val truncatedSearch = registry.execute(
                ToolCall(
                    name = "memory.search",
                    arguments = mapOf("query" to "stage220 Redmi", "limit" to "1"),
                    risk = ToolRisk.SAFE,
                ),
            )
            val detail = registry.execute(
                ToolCall(
                    name = "memory.get",
                    arguments = mapOf("memory_id" to fixture.id),
                    risk = ToolRisk.SAFE,
                ),
            )
            assertEquals(1, truncatedSearch.memoryIdsUsed.size)
            assertTrue(detail.success)
            assertFalse(registry.execute(deleteCall).success)
            assertNotNull(memoryStore.get(fixture.id))
            memoryStore.delete(ambiguousFixture.id)

            val search = registry.execute(
                ToolCall(
                    name = "memory.search",
                    arguments = mapOf("query" to "stage220 Redmi", "limit" to "1"),
                    risk = ToolRisk.SAFE,
                ),
            )
            val uniqueDetail = registry.execute(
                ToolCall(
                    name = "memory.get",
                    arguments = mapOf("memory_id" to fixture.id),
                    risk = ToolRisk.SAFE,
                ),
            )
            val deleted = registry.execute(deleteCall)
            val receipt = checkNotNull(deleted.executionReceipt)
            val recovered = registry.verifyCommittedEffect(deleteCall, receipt)

            assertTrue(search.success)
            assertEquals(listOf(fixture.id), search.memoryIdsUsed)
            assertTrue(uniqueDetail.success)
            assertEquals(listOf(fixture.id), uniqueDetail.memoryIdsUsed)
            assertTrue(deleted.success)
            assertEquals(true, deleted.verified)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, receipt.status)
            assertEquals(deleteCall.id, receipt.toolCallId)
            assertEquals(deleteCall.id, receipt.idempotencyKey)
            assertEquals(fixture.id, receipt.operationId)
            assertNull(memoryStore.get(fixture.id))
            assertEquals(true, recovered?.success)
            assertEquals(true, recovered?.verified)

            val undo = checkNotNull(memoryStore.latestDeleted())
            memoryStore.restore(undo)
            assertEquals(fixture.id, memoryStore.get(fixture.id)?.id)
            assertEquals(false, registry.verifyCommittedEffect(deleteCall, receipt)?.success)
            println("STAGE220_MEMORY_DELETE committed=true verified=true restoredFixture=true")
        } finally {
            database.close()
        }
    }
}
