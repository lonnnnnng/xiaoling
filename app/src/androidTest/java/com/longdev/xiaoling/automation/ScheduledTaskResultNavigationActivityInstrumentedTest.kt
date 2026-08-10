package com.longdev.xiaoling.automation

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.WorkflowRunEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledTaskResultNavigationActivityInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun validTokenIsConsumedDuringColdActivityCreation() = runBlocking {
        resetNavigationState()
        val fixture = createFixture("cold")
        try {
            val token = requireNotNull(
                ScheduledTaskResultNavigationTokenStore(context).issue(fixture.target),
            )
            val intent = requireNotNull(ScheduledTaskResultNavigationIntent.create(context, token))

            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val state = scenario.awaitState {
                    it.scheduledTaskResultNavigationVersion == 1L &&
                        it.workflows.any { workflow -> workflow.id == fixture.workflowId }
                }
                assertEquals(1L, state.scheduledTaskResultNavigationVersion)
            }
        } finally {
            cleanupFixture(fixture)
            resetNavigationState()
        }
    }

    @Test
    fun notificationPendingIntentUsesWarmOnNewIntentAndIsOneShot() = runBlocking {
        resetNavigationState()
        requireNotificationCapability()
        val fixture = createFixture("warm")
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        try {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                val initialized = scenario.awaitState {
                    it.workflows.any { workflow -> workflow.id == fixture.workflowId }
                }
                val versionBefore = initialized.scheduledTaskResultNavigationVersion

                assertTrue(ScheduledTaskNotifier(context).notify("stage233", fixture.task, "导航验收"))
                val notification = waitForNotification(manager, fixture.task.id.hashCode())
                val pendingIntent = requireNotNull(notification.notification.contentIntent)
                pendingIntent.send()

                val navigated = scenario.awaitState {
                    it.scheduledTaskResultNavigationVersion > versionBefore
                }
                assertEquals(versionBefore + 1L, navigated.scheduledTaskResultNavigationVersion)
                assertTrue(runCatching { pendingIntent.send() }.exceptionOrNull() is PendingIntent.CanceledException)
                println(
                    "STAGE233_NOTIFICATION_NAVIGATION workflowId=${fixture.workflowId} " +
                        "taskId=${fixture.task.id} workflowRunId=${fixture.workflowRunId} " +
                        "navigationVersion=${navigated.scheduledTaskResultNavigationVersion}",
                )
            } finally {
                // long: PendingIntent 从 Application Context 发送后，MIUI 会漏报 ActivityScenario 期待的 DESTROYED 事件；真实 Activity 主动结束后不再调用 close()，避免框架等待 45 秒并把已通过的业务断言误记为失败。
                scenario.onActivity(MainActivity::finish)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        } finally {
            manager.cancel(fixture.task.id.hashCode())
            cleanupFixture(fixture)
            resetNavigationState()
        }
    }

    @Test
    fun forgedTokenFailsClosedOnExportedMainActivity() = runBlocking {
        resetNavigationState()
        val fixture = createFixture("forged")
        try {
            val forgedIntent = requireNotNull(
                ScheduledTaskResultNavigationIntent.create(
                    context = context,
                    token = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                ),
            )

            ActivityScenario.launch<MainActivity>(forgedIntent).use { scenario ->
                val state = scenario.awaitState {
                    it.workflows.any { workflow -> workflow.id == fixture.workflowId }
                }
                assertEquals(0L, state.scheduledTaskResultNavigationVersion)
            }
        } finally {
            cleanupFixture(fixture)
            resetNavigationState()
        }
    }

    @Test
    fun deletedWorkflowRunFailsClosedAfterTokenIssue() = runBlocking {
        resetNavigationState()
        val fixture = createFixture("deleted-run")
        try {
            val token = requireNotNull(
                ScheduledTaskResultNavigationTokenStore(context).issue(fixture.target),
            )
            deleteWorkflowRun(fixture.workflowRunId)
            val intent = requireNotNull(ScheduledTaskResultNavigationIntent.create(context, token))

            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val state = scenario.awaitState {
                    it.workflows.any { workflow -> workflow.id == fixture.workflowId }
                }
                assertEquals(0L, state.scheduledTaskResultNavigationVersion)
            }
        } finally {
            cleanupFixture(fixture)
            resetNavigationState()
        }
    }

    private suspend fun createFixture(label: String): Fixture {
        val repository = RoomWorkflowRepository(context)
        val database = XiaoLingDatabase.getInstance(context)
        val (workflow, scheduledTask) = repository.createWorkflowAndOneTimeScheduledTask(
            name = "stage233-$label-${System.nanoTime()}",
            steps = listOf(WorkflowStepDefinitionInput("读取当前时间")),
            delayMinutes = 1,
        )
        val now = System.currentTimeMillis()
        val workflowRunId = "workflow-run-stage233-${UUID.randomUUID()}"
        database.withTransaction {
            val dao = database.workflowDao()
            dao.upsertRun(
                WorkflowRunEntity(
                    id = workflowRunId,
                    workflowId = workflow.id,
                    trigger = WorkflowTrigger.SCHEDULED.name,
                    scheduledTaskId = scheduledTask.id,
                    plannedAt = scheduledTask.plannedAt,
                    conversationId = "conversation-stage233-$label",
                    agentRunId = null,
                    status = WorkflowRunStatus.COMPLETED.name,
                    result = "stage233-result-$label",
                    errorMessage = null,
                    createdAt = now,
                    startedAt = now,
                    completedAt = now,
                    retryOfWorkflowRunId = null,
                ),
            )
            val entity = requireNotNull(dao.getScheduledTask(scheduledTask.id))
            dao.upsertScheduledTask(
                entity.copy(
                    status = ScheduledTaskStatus.COMPLETED.name,
                    workflowRunId = workflowRunId,
                    actualStartedAt = now,
                    completedAt = now,
                    updatedAt = now,
                ),
            )
        }
        val task = requireNotNull(repository.getScheduledTask(scheduledTask.id))
        return Fixture(
            workflowId = workflow.id,
            task = task,
            workflowRunId = workflowRunId,
            target = requireNotNull(ScheduledTaskResultNavigationPolicy.targetFor(task)),
        )
    }

    private suspend fun cleanupFixture(fixture: Fixture) {
        val database = XiaoLingDatabase.getInstance(context)
        database.withTransaction {
            val writable = database.openHelper.writableDatabase
            val workflowRunArgs = arrayOf<Any>(fixture.workflowRunId)
            val workflowArgs = arrayOf<Any>(fixture.workflowId)
            val taskArgs = arrayOf<Any>(fixture.task.id)
            writable.execSQL("DELETE FROM workflow_steps WHERE workflowRunId = ?", workflowRunArgs)
            writable.execSQL("DELETE FROM workflow_runs WHERE id = ?", workflowRunArgs)
            writable.execSQL("DELETE FROM scheduled_tasks WHERE id = ?", taskArgs)
            writable.execSQL("DELETE FROM workflow_step_definitions WHERE workflowId = ?", workflowArgs)
            writable.execSQL("DELETE FROM workflows WHERE id = ?", workflowArgs)
        }
    }

    private suspend fun deleteWorkflowRun(workflowRunId: String) {
        val database = XiaoLingDatabase.getInstance(context)
        database.withTransaction {
            val writable = database.openHelper.writableDatabase
            val workflowRunArgs = arrayOf<Any>(workflowRunId)
            writable.execSQL("DELETE FROM workflow_steps WHERE workflowRunId = ?", workflowRunArgs)
            writable.execSQL("DELETE FROM workflow_runs WHERE id = ?", workflowRunArgs)
        }
    }

    private fun resetNavigationState() {
        context.deleteSharedPreferences("xiaoling_scheduled_task_navigation")
    }

    private fun requireNotificationCapability() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assumeTrue("Redmi 通知总开关未开启", manager.areNotificationsEnabled())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assumeTrue(
                "Redmi 尚未授予 POST_NOTIFICATIONS",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    private fun waitForNotification(
        manager: NotificationManager,
        notificationId: Int,
    ): android.service.notification.StatusBarNotification {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { notification -> notification.id == notificationId }
                ?.let { return it }
            Thread.sleep(50L)
        }
        throw AssertionError("未找到 stage233 工作流结果通知")
    }

    private fun ActivityScenario<MainActivity>.awaitState(
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + 20_000L
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                latest = ViewModelProvider(activity)[XiaoLingViewModel::class.java].uiState
            }
            if (predicate(latest)) return latest
            Thread.sleep(50L)
        }
        throw AssertionError(
            "等待 stage233 导航状态超时：version=${latest.scheduledTaskResultNavigationVersion}, " +
                "workflowCount=${latest.workflows.size}",
        )
    }

    private data class Fixture(
        val workflowId: String,
        val task: ScheduledTaskRecord,
        val workflowRunId: String,
        val target: ScheduledTaskResultNavigationTarget,
    )
}
