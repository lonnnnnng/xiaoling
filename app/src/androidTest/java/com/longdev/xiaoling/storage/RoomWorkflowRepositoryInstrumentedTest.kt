package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun multiStepDefinitionMaterializesOrderedImmutableRunSnapshots() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "多步骤回顾",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("列出最近会话"),
                WorkflowStepDefinitionInput("生成最终回顾"),
            ),
        )

        assertEquals(listOf(1, 2, 3), workflow.steps.map { it.sequence })
        assertEquals(3, workflow.steps.map { it.idempotencyKey }.distinct().size)

        val run = repository.createManualRun(workflow.id, "conversation-multi")

        assertEquals(listOf(1, 2, 3), run.steps.map { it.sequence })
        assertEquals(workflow.steps.map { it.id }, run.steps.map { it.definitionStepId })
        assertEquals(workflow.steps.map { it.idempotencyKey }, run.steps.map { it.idempotencyKey })
        assertEquals(
            workflow.steps.map { it.goal },
            run.steps.map { WorkflowStepSnapshotCodec.decodeInput(it.inputSnapshot).goal },
        )
    }

    @Test
    fun editingDefinitionOnlyChangesFutureRunsAndRejectsActiveRun() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "编辑前",
            steps = listOf(
                WorkflowStepDefinitionInput("旧步骤一"),
                WorkflowStepDefinitionInput("旧步骤二"),
            ),
        )
        val historicalRun = repository.createManualRun(workflow.id, "conversation-edit")
        repository.completeRun(historicalRun.run.id, WorkflowRunStatus.CANCELLED, errorMessage = "测试结束旧 Run")

        val updated = repository.updateWorkflow(
            workflowId = workflow.id,
            name = "编辑后",
            steps = listOf(
                WorkflowStepDefinitionInput("新步骤一"),
                WorkflowStepDefinitionInput("新步骤二"),
                WorkflowStepDefinitionInput("新步骤三"),
            ),
        )
        val futureRun = repository.createManualRun(workflow.id, "conversation-edit")

        assertEquals("编辑后", updated.name)
        assertEquals(listOf("旧步骤一", "旧步骤二"), repository.runDetail(historicalRun.run.id)!!.steps.map { it.detail })
        assertEquals(listOf("新步骤一", "新步骤二", "新步骤三"), futureRun.steps.map { it.detail })

        val activeEditError = runCatching {
            repository.updateWorkflow(
                workflowId = workflow.id,
                name = "不应保存",
                steps = listOf(WorkflowStepDefinitionInput("活动 Run 期间编辑")),
            )
        }.exceptionOrNull()
        assertTrue(activeEditError is IllegalArgumentException)
    }

    @Test
    fun retryReusesCompletedStepsAndKeepsSourceRunUnchanged() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "可重试工作流",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("读取最近会话"),
                WorkflowStepDefinitionInput("生成回顾"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-retry")
        val first = source.steps[0]
        val second = source.steps[1]
        repository.markAgentRunStarted(source.run.id, first.id, "agent-run-retry-1")
        repository.completeWorkflowStep(
            source.run.id,
            first.id,
            WorkflowStepStatus.COMPLETED,
            result = "时间读取完成",
        )
        repository.markAgentRunStarted(source.run.id, second.id, "agent-run-retry-2")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "第二步失败")

        val retried = repository.retryRun(source.run.id, "conversation-retry")
        val storedSource = repository.runDetail(source.run.id)!!

        assertNotEquals(source.run.id, retried.run.id)
        assertEquals(source.run.id, retried.run.retryOfWorkflowRunId)
        assertEquals(
            listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.PENDING, WorkflowStepStatus.PENDING),
            retried.steps.map { it.status },
        )
        assertEquals(first.id, retried.steps.first().reusedFromStepId)
        assertEquals("时间读取完成", retried.steps.first().outputSnapshot)
        assertEquals(WorkflowRunStatus.FAILED, storedSource.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, storedSource.steps[0].status)
        assertEquals(WorkflowStepStatus.FAILED, storedSource.steps[1].status)
    }

    @Test
    fun completionByRecoveredAgentRunAggregatesAllStepOutputs() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "恢复后聚合",
            steps = listOf(
                WorkflowStepDefinitionInput("步骤一"),
                WorkflowStepDefinitionInput("步骤二"),
            ),
        )
        val created = repository.createManualRun(workflow.id, "conversation-aggregate")
        repository.markAgentRunStarted(created.run.id, created.steps[0].id, "agent-run-aggregate-1")
        repository.completeByAgentRunId(
            agentRunId = "agent-run-aggregate-1",
            status = WorkflowRunStatus.COMPLETED,
            result = "结果一",
        )
        repository.markAgentRunStarted(created.run.id, created.steps[1].id, "agent-run-aggregate-2")

        val completed = repository.completeByAgentRunId(
            agentRunId = "agent-run-aggregate-2",
            status = WorkflowRunStatus.COMPLETED,
            result = "结果二",
        )!!

        assertEquals(WorkflowRunStatus.COMPLETED, completed.status)
        assertEquals("结果一\n\n结果二", completed.result)
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
    fun reconcileKeepsCommittedToolRecoveryCandidateRunning() = runBlocking {
        val workflow = repository.createWorkflow("恢复笔记验证", "创建并验证笔记")
        val created = repository.createManualRun(workflow.id, "conversation-tool-recovery")
        val agentRunId = "agent-run-tool-recovery"
        repository.markAgentRunStarted(created.run.id, agentRunId)
        database.agentRunDao().upsertRun(agentRun(agentRunId, AgentRunStatus.VERIFYING))

        assertEquals(0, repository.reconcileInterruptedRuns(setOf(agentRunId)))

        val preserved = repository.runDetail(created.run.id)!!
        assertEquals(WorkflowRunStatus.RUNNING, preserved.run.status)
        assertEquals(WorkflowStepStatus.RUNNING, preserved.steps.single().status)
        assertEquals(agentRunId, preserved.run.agentRunId)
    }

    @Test
    fun reconcileKeepsCompletedPrefixAndClosesInterruptedMultiStepRun() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "中断后可重试",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("生成回顾"),
            ),
        )
        val created = repository.createManualRun(workflow.id, "conversation-reconcile")
        val firstStep = created.steps.first()
        repository.markAgentRunStarted(created.run.id, firstStep.id, "agent-run-completed-prefix")
        database.agentRunDao().upsertRun(
            agentRun(
                id = "agent-run-completed-prefix",
                status = AgentRunStatus.COMPLETED,
                result = "当前时间为 09:30",
            ),
        )

        assertEquals(1, repository.reconcileInterruptedRuns())

        val interrupted = repository.runDetail(created.run.id)!!
        assertEquals(WorkflowRunStatus.FAILED, interrupted.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, interrupted.steps[0].status)
        assertEquals("当前时间为 09:30", interrupted.steps[0].outputSnapshot)
        assertEquals(WorkflowStepStatus.CANCELLED, interrupted.steps[1].status)

        val retried = repository.retryRun(created.run.id, "conversation-reconcile")
        assertEquals(WorkflowStepStatus.SKIPPED, retried.steps[0].status)
        assertEquals(firstStep.id, retried.steps[0].reusedFromStepId)
        assertEquals(WorkflowStepStatus.PENDING, retried.steps[1].status)
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

    private fun agentRun(
        id: String,
        status: AgentRunStatus,
        result: String? = null,
    ) = AgentRunEntity(
        id = id,
        retryOfRunId = null,
        conversationId = "conversation-2",
        userMessageId = "message-2",
        goal = "创建一条笔记",
        status = status.name,
        result = result,
        errorMessage = if (status == AgentRunStatus.CANCELLED) "应用重启后取消" else null,
        createdAt = 1L,
        updatedAt = 2L,
        completedAt = if (status == AgentRunStatus.CANCELLED) 2L else null,
    )
}
