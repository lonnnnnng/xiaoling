package com.longdev.xiaoling.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 真实 WorkManager 复用同名 KEEP 工作项，验证 Room 绑定的 ID 必须来自系统现有队列，而不是本次未入队的 request。
 */
@RunWith(AndroidJUnit4::class)
class ScheduledTaskSchedulerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun keepReusesExistingWorkRequestId() = runBlocking {
        val task = ScheduledTaskRecord(
            id = "scheduler-keep-${UUID.randomUUID()}",
            workflowId = "workflow-scheduler-keep",
            type = ScheduledTaskType.ONE_TIME,
            scheduleId = null,
            status = ScheduledTaskStatus.SCHEDULED,
            plannedAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1),
            workRequestId = null,
            workflowRunId = null,
            actualStartedAt = null,
            completedAt = null,
            errorMessage = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val scheduler = WorkManagerScheduledTaskScheduler(context)
        val workManager = WorkManager.getInstance(context)
        try {
            val firstId = scheduler.enqueue(task)
            val secondId = scheduler.enqueue(task)
            assertEquals(firstId, secondId)
            val actual = workManager.getWorkInfosForUniqueWork(
                WorkManagerScheduledTaskScheduler.uniqueWorkName(task.id),
            ).get().singleOrNull()
            assertNotNull(actual)
            assertEquals(firstId, actual?.id?.toString())
        } finally {
            scheduler.cancel(task.id)
        }
    }
}
