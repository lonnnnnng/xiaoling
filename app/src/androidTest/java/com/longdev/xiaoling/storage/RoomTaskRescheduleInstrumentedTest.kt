package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentTaskRescheduleRequest
import com.longdev.xiaoling.agent.AgentTaskRescheduleResult
import com.longdev.xiaoling.automation.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTaskRescheduleInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).allowMainThreadQueries().build()
    private val repository = RoomWorkflowRepository(context, database)
    private val scheduler = Scheduler()
    private val stop = ScheduledWorkflowStopCoordinator(
        loadTask = repository::getScheduledTask,
        cancelPendingTask = repository::cancelScheduledTask,
        requestScheduledTaskStop = { repository.requestScheduledTaskStop(it, "测试停止") },
        cancelSystemWork = {}, waitForWorkerSettlement = {}, reconcileUnsettledTask = { false }, settlementChecks = 1,
    )
    private val store = RoomAgentTaskStore(context, repository, stop, scheduler)

    @After fun close() = database.close()

    @Test fun rescheduleReplacesOnlyPendingInstanceAndPreservesHistoricalRun() = runBlocking {
        val (workflow, task) = fixture()
        val history = repository.createManualRun(workflow.id, "history-conversation")
        repository.completeRun(history.run.id, WorkflowRunStatus.FAILED, errorMessage = "历史失败")
        val historyBefore = repository.runDetail(history.run.id)
        val request = request()
        val result = store.reschedule(request, "call") as AgentTaskRescheduleResult.Committed
        assertTrue(result.verified)
        assertEquals(historyBefore, repository.runDetail(history.run.id))
        val old = repository.getScheduledTask(task.id)!!
        assertEquals(ScheduledTaskStatus.CANCELLED, old.status)
        assertEquals(task.plannedAt, old.plannedAt)
        assertNull(old.workflowRunId)
        val current = repository.getScheduledTask(result.taskId)!!
        assertEquals(request.plannedAtMillis, current.plannedAt)
        assertEquals("work-${current.id}", current.workRequestId)
        assertEquals(ScheduledTaskStatus.SCHEDULED, current.status)
        assertEquals(1, repository.listScheduledTasks().count { it.status == ScheduledTaskStatus.SCHEDULED })
        assertEquals(listOf(task.id), scheduler.cancelled)
        assertNull(repository.claimScheduledRun(task.id))
        assertTrue(store.reschedule(request, "repeated-call") is AgentTaskRescheduleResult.Rejected)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test fun enqueueFailureLeavesOriginalScheduleByteForByteUnchanged() = runBlocking {
        val (_, task) = fixture()
        val original = repository.getScheduledTask(task.id)
        scheduler.beforeEnqueue = { error("系统入队失败") }
        assertTrue(store.reschedule(request(), "call") is AgentTaskRescheduleResult.Rejected)
        assertEquals(original, repository.getScheduledTask(task.id))
        assertEquals(1, repository.listScheduledTasks().size)
        assertFalse(task.id in scheduler.cancelled)
        assertEquals(scheduler.enqueued, scheduler.cancelled)
    }

    @Test fun driftWhilePreparingSystemWorkRejectsOldApproval() = runBlocking {
        val (_, task) = fixture()
        val request = request()
        scheduler.beforeEnqueue = {
            val old = database.workflowDao().getScheduledTask(task.id)!!
            database.workflowDao().upsertScheduledTask(old.copy(plannedAt = old.plannedAt + 60_000, updatedAt = old.updatedAt + 1))
        }
        assertTrue(store.reschedule(request, "call") is AgentTaskRescheduleResult.Rejected)
        assertEquals(task.plannedAt + 60_000, repository.getScheduledTask(task.id)?.plannedAt)
        assertEquals(ScheduledTaskStatus.SCHEDULED, repository.getScheduledTask(task.id)?.status)
        assertEquals(1, repository.listScheduledTasks().size)
        assertEquals(scheduler.enqueued, scheduler.cancelled)
    }

    @Test fun workerClaimWinsWithoutBeingCancelledByReschedule() = runBlocking {
        val (_, task) = fixture()
        val request = request()
        scheduler.beforeEnqueue = { assertNotNull(repository.claimScheduledRun(task.id)) }
        assertTrue(store.reschedule(request, "call") is AgentTaskRescheduleResult.Rejected)
        assertEquals(ScheduledTaskStatus.RUNNING, repository.getScheduledTask(task.id)?.status)
        assertNotNull(repository.getScheduledTask(task.id)?.workflowRunId)
        assertEquals(1, repository.listScheduledTasks().size)
        assertFalse(task.id in scheduler.cancelled)
    }

    @Test fun ambiguousOrMultipleInstancesAndRecurringRulesCannotBeRescheduled() = runBlocking {
        val (workflow, _) = fixture()
        val request = request()
        repository.createOneTimeScheduledTask(workflow.id, 40)
        assertNull(repository.oneTimeScheduleForReschedule("喝水提醒"))
        assertTrue(store.reschedule(request, "call") is AgentTaskRescheduleResult.Rejected)
        repository.createWorkflow("喝水提醒", "同名任务")
        assertNull(repository.oneTimeScheduleForReschedule("喝水提醒"))
        val recurring = repository.createWorkflow("周期提醒", "读时间")
        repository.createOneTimeScheduledTask(recurring.id, 30).let { repository.attachWorkRequest(it.id, "work-recurring") }
        repository.createOrReplaceWorkflowSchedule(recurring.id, WorkflowScheduleType.DAILY, 12, 0, null)
        assertNull(repository.oneTimeScheduleForReschedule("周期提醒"))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test fun cancellationBeforeCommitPreservesOriginalAndCancelsOnlyPreparedWork() = runBlocking {
        val (_, task) = fixture()
        val original = repository.getScheduledTask(task.id)
        scheduler.beforeEnqueue = { throw CancellationException("测试取消") }
        val failure = runCatching { store.reschedule(request(), "call") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(original, repository.getScheduledTask(task.id))
        assertEquals(scheduler.enqueued, scheduler.cancelled)
        assertFalse(task.id in scheduler.cancelled)
    }

    private suspend fun fixture(): Pair<WorkflowRecord, ScheduledTaskRecord> {
        val pair = repository.createWorkflowAndOneTimeScheduledTask("喝水提醒", listOf(WorkflowStepDefinitionInput("读取当前时间")), 30)
        repository.attachWorkRequest(pair.second.id, "work-original")
        return pair
    }

    private suspend fun request(): AgentTaskRescheduleRequest {
        val schedule = repository.oneTimeScheduleForReschedule("喝水提醒")!!
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return AgentTaskRescheduleRequest("喝水提醒", time(schedule.plannedAt), time(schedule.plannedAt + 3_600_000), "Asia/Shanghai", schedule.token)
    }

    private class Scheduler : ScheduledTaskScheduler {
        val enqueued = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var beforeEnqueue: suspend () -> Unit = {}
        override suspend fun enqueue(task: ScheduledTaskRecord): String {
            enqueued += task.id
            beforeEnqueue()
            return "work-${task.id}"
        }
        override suspend fun cancel(taskId: String) { cancelled += taskId }
    }
}
