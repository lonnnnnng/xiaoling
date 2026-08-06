package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentTaskInspectionResult
import com.longdev.xiaoling.agent.AgentTaskRunDiagnosis
import com.longdev.xiaoling.agent.AgentTaskRetryResult
import com.longdev.xiaoling.agent.AgentTaskRetryVerificationResult
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.data.WorkflowRunEntity
import com.longdev.xiaoling.data.WorkflowStepEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentTaskStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomWorkflowRepository(context, database)
    private val store = RoomAgentTaskStore(context, repository)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun listProjectsCurrentWorkflowAndScheduledTaskWithoutInternalEvidence() = runBlocking {
        val (workflow, scheduledTask) = repository.createWorkflowAndOneTimeScheduledTask(
            name = "整理明日计划",
            steps = listOf(
                WorkflowStepDefinitionInput("读取现有任务"),
                WorkflowStepDefinitionInput("形成明日计划"),
            ),
            delayMinutes = 30,
        )

        val task = store.list(limit = 5).single()

        assertEquals("整理明日计划", task.name)
        assertEquals("读取现有任务", task.goal)
        assertEquals(2, task.stepCount)
        assertEquals(ScheduledTaskType.ONE_TIME.name, task.scheduleType)
        assertEquals(scheduledTask.plannedAt, task.nextPlannedAt)
        assertNotNull(task.updatedAt)
    }

    @Test
    fun listKeepsLatestStatusWhenOtherWorkflowsHaveMoreThanTwoHundredRuns() = runBlocking {
        val target = repository.createWorkflow("重点任务", "完成个人 Agent 主线")
        val distractor = repository.createWorkflow("历史任务", "生成大量历史 Run")
        val now = System.currentTimeMillis()
        database.workflowDao().upsertRun(
            workflowRun(
                id = "target-run",
                workflowId = target.id,
                status = WorkflowRunStatus.COMPLETED,
                createdAt = now,
            ),
        )
        repeat(201) { index ->
            database.workflowDao().upsertRun(
                workflowRun(
                    id = "distractor-run-$index",
                    workflowId = distractor.id,
                    status = WorkflowRunStatus.FAILED,
                    createdAt = now + index + 1,
                ),
            )
        }

        val task = store.list(limit = 10).first { record -> record.name == "重点任务" }

        assertEquals(WorkflowRunStatus.COMPLETED.name, task.latestRunStatus)
    }

    @Test
    fun listUsesEarliestPlannedOccurrenceWhenOneTimeAndRecurringSchedulesCoexist() = runBlocking {
        val (workflow, oneTimeTask) = repository.createWorkflowAndOneTimeScheduledTask(
            name = "并存计划",
            steps = listOf(WorkflowStepDefinitionInput("执行最早提醒")),
            delayMinutes = 1,
        )
        val recurringTime = ZonedDateTime.now().plusHours(2)
        repository.createOrReplaceWorkflowSchedule(
            workflowId = workflow.id,
            type = WorkflowScheduleType.DAILY,
            hour = recurringTime.hour,
            minute = recurringTime.minute,
            dayOfWeek = null,
        )

        val task = store.list(limit = 10).first { record -> record.name == "并存计划" }

        assertEquals(ScheduledTaskType.ONE_TIME.name, task.scheduleType)
        assertEquals(oneTimeTask.plannedAt, task.nextPlannedAt)
    }

    @Test
    fun inspectProjectsLatestFailedRunWithoutRawErrorOrInternalIds() = runBlocking {
        val workflow = repository.createWorkflow("每日回顾", "总结今天完成的工作")
        val now = System.currentTimeMillis()
        database.workflowDao().upsertRun(
            workflowRun(
                id = "private-workflow-run-id",
                workflowId = workflow.id,
                status = WorkflowRunStatus.FAILED,
                createdAt = now,
                errorMessage = "raw provider error with secret arguments",
            ),
        )
        database.workflowDao().upsertStep(workflowStep("private-step-1", now, 1, WorkflowStepStatus.COMPLETED))
        database.workflowDao().upsertStep(workflowStep("private-step-2", now, 2, WorkflowStepStatus.FAILED))

        val result = store.inspect("每日回顾") as AgentTaskInspectionResult.Found

        assertEquals("每日回顾", result.task.name)
        assertEquals(WorkflowRunStatus.FAILED.name, result.task.latestRunStatus)
        assertEquals(AgentTaskRunDiagnosis.STEP_FAILED, result.task.diagnosis)
        assertEquals(listOf("COMPLETED", "FAILED"), result.task.steps.map { step -> step.status })
        assertEquals(listOf(1, 2), result.task.steps.map { step -> step.sequence })
    }

    @Test
    fun inspectRejectsAmbiguousNamesAndDoesNotGuessMissingTask() = runBlocking {
        repository.createWorkflow("同名任务", "第一个任务")
        repository.createWorkflow("同名任务", "第二个任务")

        assertEquals(2, (store.inspect("同名任务") as AgentTaskInspectionResult.Ambiguous).matchCount)
        assertEquals(AgentTaskInspectionResult.NotFound, store.inspect("不存在的任务"))
    }

    @Test
    fun retryCreatesOneLinkedRunByToolCallAndNeverFallsBackToOlderFailure() = runBlocking {
        val workflow = repository.createWorkflow(
            name = "每日回顾",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("生成每日回顾"),
            ),
        )
        val source = repository.createManualRun(workflow.id, "conversation-source")
        repository.markAgentRunStarted(source.run.id, source.steps[0].id, "agent-run-completed")
        repository.completeWorkflowStep(
            source.run.id,
            source.steps[0].id,
            WorkflowStepStatus.COMPLETED,
            result = "时间读取完成",
        )
        repository.markAgentRunStarted(source.run.id, source.steps[1].id, "agent-run-failed")
        repository.completeRun(source.run.id, WorkflowRunStatus.FAILED, errorMessage = "private provider error")

        val first = store.retry(
            name = " 每日回顾 ",
            conversationId = "conversation-retry",
            idempotencyKey = "tool-call-task-retry",
        ) as AgentTaskRetryResult.Queued
        val repeated = store.retry(
            name = "每日回顾",
            conversationId = "conversation-retry",
            idempotencyKey = "tool-call-task-retry",
        ) as AgentTaskRetryResult.Queued
        val differentCall = store.retry(
            name = "每日回顾",
            conversationId = "conversation-retry",
            idempotencyKey = "tool-call-task-retry-second",
        )
        val driftedConversation = store.retry(
            name = "每日回顾",
            conversationId = "conversation-drifted",
            idempotencyKey = "tool-call-task-retry",
        )

        val retried = repository.runDetail(first.retry.workflowRunId)!!
        val unchangedSource = repository.runDetail(source.run.id)!!
        assertEquals(source.run.id, retried.run.retryOfWorkflowRunId)
        assertEquals(first.retry.workflowRunId, repeated.retry.workflowRunId)
        assertEquals(false, first.retry.alreadyQueued)
        assertEquals(true, repeated.retry.alreadyQueued)
        assertEquals(1, first.retry.reusedStepCount)
        assertEquals(
            listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.PENDING),
            retried.steps.map { step -> step.status },
        )
        assertEquals(WorkflowRunStatus.FAILED, unchangedSource.run.status)
        assertEquals(WorkflowStepStatus.COMPLETED, unchangedSource.steps[0].status)
        assertEquals(WorkflowStepStatus.FAILED, unchangedSource.steps[1].status)
        assertEquals(true, differentCall is AgentTaskRetryResult.Rejected)
        assertEquals(AgentTaskRetryResult.IdempotencyConflict, driftedConversation)
        assertEquals(
            true,
            store.verifyRetry(
                name = "每日回顾",
                conversationId = "conversation-retry",
                idempotencyKey = "tool-call-task-retry",
                workflowRunId = first.retry.workflowRunId,
            ) is AgentTaskRetryVerificationResult.Verified,
        )
        assertEquals(
            AgentTaskRetryVerificationResult.Failed,
            store.verifyRetry(
                name = "每日回顾",
                conversationId = "conversation-drifted",
                idempotencyKey = "tool-call-task-retry",
                workflowRunId = first.retry.workflowRunId,
            ),
        )

        repository.markAgentRunStarted(
            first.retry.workflowRunId,
            retried.steps.first { step -> step.status == WorkflowStepStatus.PENDING }.id,
            "agent-run-retry-started",
        )
        assertEquals(
            true,
            store.retry(
                name = "每日回顾",
                conversationId = "conversation-retry",
                idempotencyKey = "tool-call-task-retry",
            ) is AgentTaskRetryResult.Rejected,
        )
    }

    @Test
    fun retryRejectsCompletedLatestRunInsteadOfFallingBackToOlderFailure() = runBlocking {
        val workflow = repository.createWorkflow("完成态任务", "读取当前时间")
        val failed = repository.createManualRun(workflow.id, "conversation-failed")
        repository.markAgentRunStarted(failed.run.id, failed.steps.single().id, "agent-run-failed-old")
        repository.completeRun(failed.run.id, WorkflowRunStatus.FAILED, errorMessage = "old private error")
        val completed = repository.createManualRun(workflow.id, "conversation-completed")
        repository.markAgentRunStarted(completed.run.id, completed.steps.single().id, "agent-run-completed-latest")
        repository.completeWorkflowStep(
            completed.run.id,
            completed.steps.single().id,
            WorkflowStepStatus.COMPLETED,
            result = "时间读取完成",
        )
        repository.completeRun(completed.run.id, WorkflowRunStatus.COMPLETED, result = "已完成")

        val result = store.retry("完成态任务", "conversation-retry", "tool-call-completed-latest")

        assertEquals(true, result is AgentTaskRetryResult.Rejected)
        assertEquals(completed.run.id, repository.latestRunsForWorkflows(listOf(workflow.id)).single().id)
    }

    @Test
    fun retryRejectsDisabledTaskAndMissingStepEvidence() = runBlocking {
        val disabled = repository.createWorkflow("停用任务", "读取当前时间")
        val disabledRun = repository.createManualRun(disabled.id, "conversation-disabled")
        repository.completeRun(disabledRun.run.id, WorkflowRunStatus.FAILED, errorMessage = "private error")
        repository.setEnabled(disabled.id, false)
        assertEquals(
            true,
            store.retry("停用任务", "conversation-retry", "tool-call-disabled") is AgentTaskRetryResult.Rejected,
        )

        val missingSteps = repository.createWorkflow("缺少步骤证据", "读取当前时间")
        database.workflowDao().upsertRun(
            workflowRun(
                id = "run-without-steps",
                workflowId = missingSteps.id,
                status = WorkflowRunStatus.FAILED,
                createdAt = System.currentTimeMillis(),
            ),
        )
        assertEquals(
            true,
            store.retry("缺少步骤证据", "conversation-retry", "tool-call-missing-steps") is AgentTaskRetryResult.Rejected,
        )
    }

    private fun workflowRun(
        id: String,
        workflowId: String,
        status: WorkflowRunStatus,
        createdAt: Long,
        errorMessage: String? = null,
    ) = WorkflowRunEntity(
        id = id,
        workflowId = workflowId,
        trigger = WorkflowTrigger.MANUAL.name,
        scheduledTaskId = null,
        plannedAt = null,
        conversationId = "conversation-$id",
        agentRunId = null,
        status = status.name,
        result = null,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = createdAt,
        completedAt = createdAt,
        retryOfWorkflowRunId = null,
    )

    private fun workflowStep(
        id: String,
        createdAt: Long,
        sequence: Int,
        status: WorkflowStepStatus,
    ) = WorkflowStepEntity(
        id = id,
        workflowRunId = "private-workflow-run-id",
        sequence = sequence,
        type = "AGENT_RUN",
        status = status.name,
        title = "步骤 $sequence",
        detail = "sensitive step input $sequence",
        agentRunId = null,
        result = "sensitive result $sequence",
        errorMessage = "sensitive raw error $sequence",
        createdAt = createdAt,
        startedAt = createdAt,
        completedAt = createdAt,
        definitionStepId = null,
        idempotencyKey = "private-idempotency-$sequence",
        inputSnapshot = "sensitive input snapshot $sequence",
        outputSnapshot = "sensitive output snapshot $sequence",
        reusedFromStepId = null,
    )
}
