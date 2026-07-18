package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.ScheduledTaskStatus
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

    @Test
    fun oneTimeTaskCreatesClaimLinksRunAndSettlesBlocked() = runBlocking {
        val workflow = repository.createWorkflow("定时笔记", "创建一条笔记")
        val task = repository.createOneTimeScheduledTask(workflow.id, delayMinutes = 1)
        repository.attachWorkRequest(task.id, "work-request-1")

        val claim = repository.claimScheduledRun(task.id)!!
        repository.markAgentRunStarted(claim.run.run.id, "agent-run-scheduled-1")
        repository.completeRun(claim.run.run.id, WorkflowRunStatus.BLOCKED, errorMessage = "需要用户确认")
        repository.finishScheduledTask(task.id, ScheduledTaskStatus.BLOCKED, "需要用户确认")

        val storedTask = repository.listScheduledTasks().single()
        val storedRun = repository.recentRunDetails().single()
        assertEquals(ScheduledTaskStatus.BLOCKED, storedTask.status)
        assertEquals(task.plannedAt, storedRun.run.plannedAt)
        assertEquals(task.id, storedRun.run.scheduledTaskId)
        assertEquals(storedRun.run.id, storedTask.workflowRunId)
        assertTrue(storedTask.actualStartedAt != null)
        assertEquals("agent-run-scheduled-1", storedRun.run.agentRunId)
        assertEquals(WorkflowRunStatus.BLOCKED, storedRun.run.status)
        assertEquals(WorkflowStepStatus.BLOCKED, storedRun.steps.single().status)
    }

    @Test
    fun recurringScheduleReplacesPendingTaskAndMaterializesOnlyOneNextOccurrence() = runBlocking {
        val workflow = repository.createWorkflow("周期回顾", "读取当前时间")
        val daily = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.DAILY,
            hour = 9,
            minute = 30,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
        )
        val weekly = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.WEEKLY,
            hour = 10,
            minute = 0,
            dayOfWeek = 1,
            zoneId = "Asia/Shanghai",
        )

        assertEquals(daily.schedule.id, weekly.schedule.id)
        assertEquals(daily.task.id, weekly.replacedTaskId)
        assertEquals(ScheduledTaskStatus.CANCELLED, repository.getScheduledTask(daily.task.id)?.status)
        assertEquals(WorkflowScheduleType.WEEKLY, repository.listWorkflowSchedules().single().type)

        repository.finishScheduledTask(weekly.task.id, ScheduledTaskStatus.COMPLETED)
        val next = repository.materializeNextOccurrence(weekly.task.id)!!
        assertEquals(weekly.schedule.id, next.scheduleId)
        assertTrue(next.plannedAt > weekly.task.plannedAt)
        assertNull(repository.materializeNextOccurrence(weekly.task.id))
        assertEquals(next.id, repository.listWorkflowSchedules().single().nextTaskId)
    }

    @Test
    fun interruptedRecurringTaskSettlesFromWorkflowRunBeforeCreatingFutureOccurrence() = runBlocking {
        val workflow = repository.createWorkflow("中断周期", "读取当前时间")
        val plan = repository.createOrReplaceWorkflowSchedule(
            workflow.id,
            WorkflowScheduleType.DAILY,
            hour = 9,
            minute = 0,
            dayOfWeek = null,
            zoneId = "Asia/Shanghai",
        )
        val claim = repository.claimScheduledRun(plan.task.id)!!
        repository.completeRun(claim.run.run.id, WorkflowRunStatus.FAILED, errorMessage = "应用重启后取消旧执行")

        assertEquals(1, repository.reconcileInterruptedScheduledTasks())
        assertEquals(ScheduledTaskStatus.FAILED, repository.getScheduledTask(plan.task.id)?.status)
        val recovered = repository.reconcileWorkflowSchedules().single()
        assertEquals(plan.schedule.id, recovered.scheduleId)
        assertTrue(recovered.plannedAt > plan.task.plannedAt)
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
