package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.data.WorkflowRunEntity
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

    private fun workflowRun(
        id: String,
        workflowId: String,
        status: WorkflowRunStatus,
        createdAt: Long,
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
        errorMessage = null,
        createdAt = createdAt,
        startedAt = createdAt,
        completedAt = createdAt,
        retryOfWorkflowRunId = null,
    )
}
