package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentRunRepositoryInstrumentedTest {
    private lateinit var database: XiaoLingDatabase
    private lateinit var repository: RoomAgentRunRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAgentRunRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingApprovalRequestsDoesNotExpireLegacyTimestampOrWriteDecisionEvent() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-legacy",
            userMessageId = "message-1",
            goal = "等待用户确认",
        )
        repository.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        database.agentRunDao().upsertApprovalRequest(
            ApprovalRequestEntity(
                id = "approval-legacy-expired-field",
                runId = run.id,
                conversationId = "conversation-legacy",
                toolCallId = "tool-call-1",
                toolName = "memory.remember",
                toolDescription = "写入长期记忆",
                risk = "REQUIRES_APPROVAL",
                argumentsJson = """{"note":"user likes compact ui"}""",
                status = ApprovalRequestStatus.PENDING.name,
                decisionReason = null,
                createdAt = 1L,
                expiresAt = 1L,
                decidedAt = null,
            ),
        )
        val eventsBeforeRead = database.agentRunDao().getEvents(run.id)

        val pending = repository.pendingApprovalRequests("conversation-legacy")
        val stored = database.agentRunDao().getApprovalRequest("approval-legacy-expired-field")
        val eventsAfterRead = database.agentRunDao().getEvents(run.id)

        assertEquals(listOf("approval-legacy-expired-field"), pending.map { it.id })
        assertEquals(ApprovalRequestStatus.PENDING.name, stored?.status)
        assertEquals(eventsBeforeRead.size, eventsAfterRead.size)
        assertFalse(eventsAfterRead.any { it.type == "approval.request_decided" })
    }
}
