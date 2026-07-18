package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkflowRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomWorkflowRepository(context, database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualRunKeepsSingleIdempotentAgentStepAndCompletes() = runBlocking {
        val workflow = repository.createWorkflow("每日回顾", "回顾最近会话")
        val created = repository.createManualRun(workflow.id, "conversation-1")

        assertEquals(WorkflowRunStatus.QUEUED, created.run.status)
        assertEquals(WorkflowStepStatus.PENDING, created.steps.single().status)
        assertNull(created.run.agentRunId)
        val duplicateError = runCatching {
            repository.createManualRun(workflow.id, "conversation-1")
        }.exceptionOrNull()
        assertTrue(duplicateError is IllegalArgumentException)

        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        repository.markAgentRunStarted(created.run.id, "agent-run-1")
        repository.completeRun(created.run.id, WorkflowRunStatus.COMPLETED, result = "回顾完成")

        val completed = repository.recentRunDetails().single()
        assertEquals(WorkflowRunStatus.COMPLETED, completed.run.status)
        assertEquals("agent-run-1", completed.run.agentRunId)
        assertEquals(1, completed.steps.size)
        assertEquals(WorkflowStepStatus.COMPLETED, completed.steps.single().status)
        assertEquals("回顾完成", completed.steps.single().result)
    }

    @Test
    fun reconcileKeepsApprovalWaitButClosesCancelledAgentRun() = runBlocking {
        val workflow = repository.createWorkflow("需确认任务", "创建一条笔记")
        val created = repository.createManualRun(workflow.id, "conversation-2")
        repository.markAgentRunStarted(created.run.id, "agent-run-2")
        database.agentRunDao().upsertRun(agentRun("agent-run-2", AgentRunStatus.WAITING_APPROVAL))

        assertEquals(0, repository.reconcileInterruptedRuns())
        assertEquals(WorkflowRunStatus.RUNNING, repository.recentRunDetails().single().run.status)

        database.agentRunDao().upsertRun(agentRun("agent-run-2", AgentRunStatus.CANCELLED))
        assertEquals(1, repository.reconcileInterruptedRuns())
        assertEquals(WorkflowRunStatus.CANCELLED, repository.recentRunDetails().single().run.status)
    }

    private fun agentRun(id: String, status: AgentRunStatus) = AgentRunEntity(
        id = id,
        retryOfRunId = null,
        conversationId = "conversation-2",
        userMessageId = "message-2",
        goal = "创建一条笔记",
        status = status.name,
        result = null,
        errorMessage = if (status == AgentRunStatus.CANCELLED) "应用重启后取消" else null,
        createdAt = 1L,
        updatedAt = 2L,
        completedAt = if (status == AgentRunStatus.CANCELLED) 2L else null,
    )
}
