package com.longdev.xiaoling.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.ScheduledTaskRecord
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 232 阶段只在显式参数下等待真实 WorkManager；分享文本必须先转为任务并确认一次性计划，后台才可创建 Run。
 */
@RunWith(AndroidJUnit4::class)
class Stage232SharedTextOneTimeReminderInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedTextPersonalTaskCompletesThroughOneTimeWorkManagerReminder() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val workflowRepository = RoomWorkflowRepository(context)
        val scheduler = WorkManagerScheduledTaskScheduler(context)
        val notificationManager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        cleanupPreviousFixture(state, database, profileStore, roomState, workflowRepository, scheduler, notificationManager)
        requireNotificationCapability()
        restoreProviderFromRunnerArgsIfRequested()

        val providerSnapshot = ProviderRepository(context).load()
        val provider = providerSnapshot.profiles.firstOrNull { profile ->
            profile.id == providerSnapshot.selectedProfileId
        }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider Base URL 为空", provider.baseUrl.isNotBlank())
        assertTrue("Redmi 当前 Provider API Key 为空", provider.apiKey.isNotBlank())
        assertTrue("Redmi 当前 Provider 模型为空", provider.model.isNotBlank())

        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val agentRepository = RoomAgentRunRepository(context)
        val baselineAgentRun = agentRepository.recentRunDetails(1).firstOrNull()
        val baselineWorkflowRun = workflowRepository.recentRunDetails(1).firstOrNull()
        val baselineScheduledTask = workflowRepository.listScheduledTasks()
            .filter { task -> task.status in STABLE_SCHEDULED_TASK_STATUSES }
            .maxByOrNull { task -> task.updatedAt }
        val scheduledTaskIdsBefore = workflowRepository.listScheduledTasks().mapTo(mutableSetOf()) { task -> task.id }
        val now = System.currentTimeMillis()
        val profileId = "stage232-share-reminder-$now"
        val conversationId = "conversation-stage232-share-reminder-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第232阶段分享提醒验收",
            avatar = "232",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the confirmed one-time reminder, use only app.current_time exactly once after the scheduled Workflow starts. Summarize the verified time and explicitly state that the reminder fired.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.current_time"),
            allowedSkillIds = listOf("device-time"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        state.edit()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putString(KEY_BASELINE_AGENT_RUN_ID, baselineAgentRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_AGENT_RUN_DIGEST, baselineAgentRun?.stableDigest())
            .putString(KEY_BASELINE_WORKFLOW_RUN_ID, baselineWorkflowRun?.run?.id)
            .putString(KEY_BASELINE_WORKFLOW_RUN_DIGEST, baselineWorkflowRun?.stableDigest())
            .putString(KEY_BASELINE_SCHEDULED_TASK_ID, baselineScheduledTask?.id)
            .putString(KEY_BASELINE_SCHEDULED_TASK_DIGEST, baselineScheduledTask?.stableDigest())
            .commit()

        var completedWorkflowId: String? = null
        var completedTaskId: String? = null
        var scheduledConversationId: String? = null
        try {
            profileStore.upsert(profile)
            assertTrue("无法选择第232阶段临时 Profile", profileStore.select(profileId))
            database.conversationDao().insertConversations(
                listOf(
                    ConversationEntity(
                        id = conversationId,
                        title = "新会话",
                        summary = "",
                        summaryUntilMessageId = null,
                        summaryUpdatedAt = null,
                        summaryModel = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
            roomState.saveSelectedConversationId(conversationId)
            persistSelectedState(profileId, conversationId)

            val sharedText = "只创建一个 1 分钟后执行的单步个人提醒：到时读取当前设备时间，并在同一步明确告诉我第 232 阶段提醒已经触发。不要创建周期提醒，不要拆分步骤，不要使用其他工具。stage232-${System.nanoTime()}"
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                },
            ).use { scenario ->
                val imported = scenario.awaitState { current ->
                    current.prompt == sharedText && current.sharedDraftImported && !current.loadingConversationMessages
                }
                assertFalse(imported.personalTaskMode)
                assertNull(imported.pendingPersonalTaskPlan)
                assertNull(imported.activeAgentRun)

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].createPersonalTaskDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt == sharedText && current.personalTaskMode && !current.sharedDraftImported
                }
                assertFalse(converted.sendingMessage)
                assertNull(converted.pendingPersonalTaskPlan)

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val planned = scenario.awaitState(timeoutMs = PLAN_TIMEOUT_MS) { current ->
                    current.pendingPersonalTaskPlan?.sourceGoal == sharedText && !current.sendingMessage
                }.pendingPersonalTaskPlan
                assertNotNull("分享提醒没有生成待确认计划", planned)
                requireNotNull(planned)
                assertEquals("一次 · 确认后约 1 分钟", planned.reminderScheduleLabel)
                assertEquals(listOf("app.current_time"), planned.allowedToolNames)
                assertEquals(1, planned.steps.size)
                assertEquals(listOf("app.current_time"), planned.goalVerificationSpec?.requiredToolNames)
                assertNull(planned.targetAppPackage)
                assertEquals(scheduledTaskIdsBefore, workflowRepository.listScheduledTasks().mapTo(mutableSetOf()) { task -> task.id })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].confirmPendingPersonalTaskPlan()
                }
                val completion = scenario.awaitState(timeoutMs = REMINDER_CREATION_TIMEOUT_MS) { current ->
                    current.personalTaskCompletion?.title == "应用内提醒已创建" && !current.sendingMessage &&
                        current.personalTaskOperationPhase == null
                }.personalTaskCompletion
                assertNotNull("确认后没有形成提醒完成卡", completion)
                requireNotNull(completion)
                assertTrue(completion.message.contains("一次 · 确认后约 1 分钟"))
                assertTrue(completion.message.contains("系统可能延迟执行"))
                completedWorkflowId = completion.workflowId
                state.edit().putString(KEY_WORKFLOW_ID, completedWorkflowId).commit()
            }

            val workflowId = requireNotNull(completedWorkflowId)
            val workflow = workflowRepository.getWorkflow(workflowId)
            assertNotNull("第232阶段 Workflow 不存在", workflow)
            requireNotNull(workflow)
            assertEquals(1, workflow.steps.size)
            val createdTask = workflowRepository.listScheduledTasks().single { task ->
                task.workflowId == workflowId && task.id !in scheduledTaskIdsBefore
            }
            completedTaskId = createdTask.id
            state.edit().putString(KEY_TASK_ID, completedTaskId).commit()
            assertEquals(ScheduledTaskType.ONE_TIME, createdTask.type)
            assertEquals(ScheduledTaskStatus.SCHEDULED, createdTask.status)
            assertEquals(ONE_MINUTE_MS, createdTask.plannedAt - createdTask.createdAt)
            assertNotNull("一次性提醒没有关联 WorkRequest", createdTask.workRequestId)
            assertNull(createdTask.workflowRunId)

            val completedTask = awaitScheduledTaskCompletion(workflowRepository, createdTask.id)
            assertEquals(ScheduledTaskStatus.COMPLETED, completedTask.status)
            assertNotNull("一次性提醒完成后没有关联 Workflow Run", completedTask.workflowRunId)
            val workflowRun = requireNotNull(workflowRepository.runDetail(requireNotNull(completedTask.workflowRunId)))
            state.edit().putString(KEY_WORKFLOW_RUN_ID, workflowRun.run.id).commit()
            scheduledConversationId = workflowRun.run.conversationId
            state.edit().putString(KEY_SCHEDULED_CONVERSATION_ID, scheduledConversationId).commit()
            assertEquals(WorkflowTrigger.SCHEDULED, workflowRun.run.trigger)
            assertEquals(createdTask.plannedAt, workflowRun.run.plannedAt)
            assertEquals(WorkflowRunStatus.COMPLETED, workflowRun.run.status)
            assertEquals(WorkflowStepStatus.COMPLETED, workflowRun.steps.single().status)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, workflowRun.run.goalVerificationDecision?.status)

            val agentRunId = requireNotNull(workflowRun.steps.single().agentRunId)
            state.edit().putString(KEY_AGENT_RUN_ID, agentRunId).commit()
            val agentRun = requireNotNull(agentRepository.runDetail(agentRunId))
            assertEquals(AgentRunStatus.COMPLETED, agentRun.snapshot.run.status)
            assertEquals(listOf("app.current_time"), agentRun.toolLedger.calls.map { call -> call.toolName })
            val toolResult = agentRun.toolLedger.results.single()
            assertTrue(toolResult.success)
            assertEquals(ToolVerificationStatus.PASSED, toolResult.verificationStatus)
            assertTrue(toolResult.content.contains("当前时间："))
            assertTrue(toolResult.content.contains("时区："))
            assertTrue(agentRun.approvals.isEmpty())

            val notification = awaitResultNotification(notificationManager, createdTask.id.hashCode())
            val notificationTitle = notification.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
            assertTrue(notificationTitle.contains("工作流已完成"))
            assertTrue(notificationTitle.contains(workflow.name))

            state.getString(KEY_BASELINE_AGENT_RUN_ID, null)?.let { baselineId ->
                val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_AGENT_RUN_DIGEST, null))
                assertEquals(baselineDigest, requireNotNull(agentRepository.runDetail(baselineId)).stableDigest())
            }
            state.getString(KEY_BASELINE_WORKFLOW_RUN_ID, null)?.let { baselineId ->
                val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_WORKFLOW_RUN_DIGEST, null))
                assertEquals(baselineDigest, requireNotNull(workflowRepository.runDetail(baselineId)).stableDigest())
            }
            state.getString(KEY_BASELINE_SCHEDULED_TASK_ID, null)?.let { baselineId ->
                val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_SCHEDULED_TASK_DIGEST, null))
                assertEquals(baselineDigest, requireNotNull(workflowRepository.getScheduledTask(baselineId)).stableDigest())
            }
            println(
                "STAGE232_SHARED_REMINDER workflowId=$workflowId taskId=${createdTask.id} " +
                    "workflowRunId=${workflowRun.run.id} agentRunId=$agentRunId " +
                    "tool=app.current_time verification=PASSED goalDecision=VERIFIED notification=VISIBLE",
            )
        } finally {
            if (state.getString(KEY_TASK_ID, null) == null) {
                // long: 提醒可能已原子提交、但测试在写入夹具身份前失败；只从本轮新增 Task 恢复身份，避免遗留真实 WorkRequest。
                workflowRepository.listScheduledTasks()
                    .filter { task -> task.id !in scheduledTaskIdsBefore && task.createdAt >= now }
                    .minByOrNull { task -> task.createdAt }
                    ?.let { task ->
                        state.edit()
                            .putString(KEY_WORKFLOW_ID, task.workflowId)
                            .putString(KEY_TASK_ID, task.id)
                            .commit()
                    }
            }
            cleanupPreviousFixture(state, database, profileStore, roomState, workflowRepository, scheduler, notificationManager)
        }
        assertFalse(profileStore.list().any { candidate -> candidate.id == profileId })
        assertNull(database.conversationDao().getConversation(conversationId))
        assertNull(database.conversationDao().getConversation(requireNotNull(scheduledConversationId)))
        assertEquals(originalProfileId, roomState.selectedAgentProfileId())
        assertEquals(originalConversationId, roomState.selectedConversationId())
        assertFalse(requireNotNull(workflowRepository.getWorkflow(requireNotNull(completedWorkflowId))).enabled)
        assertEquals(ScheduledTaskStatus.COMPLETED, workflowRepository.getScheduledTask(requireNotNull(completedTaskId))?.status)
        assertTrue(state.all.isEmpty())
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第232阶段真实提醒验收只在显式 stage232RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第232阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun requireNotificationCapability() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertTrue("Redmi 已关闭小灵通知，无法验收提醒结果", manager.areNotificationsEnabled())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                "Redmi 尚未授予通知权限",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private suspend fun restoreProviderFromRunnerArgsIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(ARG_RESTORE_PROVIDER) != "true") return
        val baseUrl = requireNotNull(arguments.getString(ARG_FALLBACK_BASE_URL)?.takeIf { it.isNotBlank() })
        val apiKey = requireNotNull(arguments.getString(ARG_FALLBACK_API_KEY)?.takeIf { it.isNotBlank() })
        val model = requireNotNull(arguments.getString(ARG_FALLBACK_MODEL)?.takeIf { it.isNotBlank() })
        val repository = ProviderRepository(context)
        val current = repository.load()
        val existing = current.profiles.firstOrNull() ?: ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 真实提醒测试只在显式 runner 参数下恢复 Keystore Provider；生产分享和 WorkManager 链路都不能读取这些参数。
        repository.save(listOf(restored), restored.id)
        val loaded = repository.load().profiles.single()
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue(loaded.apiKey.isNotBlank())
    }

    private suspend fun awaitScheduledTaskCompletion(
        repository: RoomWorkflowRepository,
        taskId: String,
    ): ScheduledTaskRecord {
        val deadline = System.currentTimeMillis() + SCHEDULED_RUN_TIMEOUT_MS
        var latest = repository.getScheduledTask(taskId)
        while (System.currentTimeMillis() < deadline) {
            latest = repository.getScheduledTask(taskId)
            if (latest?.status in STABLE_SCHEDULED_TASK_STATUSES) return requireNotNull(latest)
            delay(SCHEDULED_TASK_POLL_MS)
        }
        throw AssertionError(
            "Timed out waiting for Stage232 ScheduledTask: taskId=$taskId, status=${latest?.status}, " +
                "workflowRunPresent=${latest?.workflowRunId != null}, workRequestPresent=${latest?.workRequestId != null}",
        )
    }

    private suspend fun awaitResultNotification(
        manager: NotificationManager,
        notificationId: Int,
    ): StatusBarNotification {
        val deadline = System.currentTimeMillis() + NOTIFICATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { notification -> notification.id == notificationId }?.let { return it }
            delay(NOTIFICATION_POLL_MS)
        }
        throw AssertionError("Timed out waiting for Stage232 result notification: id=$notificationId")
    }

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        roomState: RoomStateStore,
        workflowRepository: RoomWorkflowRepository,
        scheduler: WorkManagerScheduledTaskScheduler,
        notificationManager: NotificationManager,
    ) {
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val workflowId = state.getString(KEY_WORKFLOW_ID, null)
        val taskId = state.getString(KEY_TASK_ID, null) ?: workflowId?.let { targetWorkflowId ->
            workflowRepository.listScheduledTasks().firstOrNull { task -> task.workflowId == targetWorkflowId }?.id
        }
        taskId?.let { targetTaskId ->
            when (workflowRepository.getScheduledTask(targetTaskId)?.status) {
                ScheduledTaskStatus.SCHEDULED -> {
                    runCatching { scheduler.cancel(targetTaskId) }
                    workflowRepository.cancelScheduledTask(targetTaskId)
                }
                ScheduledTaskStatus.RUNNING -> {
                    workflowRepository.requestScheduledTaskStop(targetTaskId, "第232阶段验收清理")
                    runCatching { scheduler.cancel(targetTaskId) }
                    awaitTaskStoppedForCleanup(workflowRepository, targetTaskId)
                }
                ScheduledTaskStatus.STOP_REQUESTED -> {
                    runCatching { scheduler.cancel(targetTaskId) }
                    awaitTaskStoppedForCleanup(workflowRepository, targetTaskId)
                }
                else -> runCatching { scheduler.cancel(targetTaskId) }
            }
            notificationManager.cancel(targetTaskId.hashCode())
        }
        workflowId?.let { workflowRepository.setEnabled(it, false) }
        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { profileStore.delete(it) }

        val conversationIds = buildSet {
            conversationId?.let(::add)
            state.getString(KEY_SCHEDULED_CONVERSATION_ID, null)?.let(::add)
            workflowId?.let { targetWorkflowId ->
                workflowRepository.recentRunDetails(100)
                    .filter { detail -> detail.run.workflowId == targetWorkflowId }
                    .forEach { detail -> add(detail.run.conversationId) }
            }
        }.filter(String::isNotBlank)
        if (conversationIds.isNotEmpty()) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(conversationIds)
                database.conversationDao().deleteConversations(conversationIds)
            }
        }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        persistSelectedState(originalProfileId, originalConversationId)
        state.edit().clear().commit()
    }

    private suspend fun awaitTaskStoppedForCleanup(
        repository: RoomWorkflowRepository,
        taskId: String,
    ) {
        val deadline = System.currentTimeMillis() + CLEANUP_STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = repository.getScheduledTask(taskId)?.status ?: return
            if (status != ScheduledTaskStatus.RUNNING && status != ScheduledTaskStatus.STOP_REQUESTED) return
            delay(CLEANUP_STOP_POLL_MS)
        }
    }

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (profileId == null) remove("selected_agent_profile_id") else putString("selected_agent_profile_id", profileId)
                if (conversationId == null) remove("selected_conversation_id") else putString("selected_conversation_id", conversationId)
            }
            .commit()
    }

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ActivityScenario<MainActivity>.awaitState(
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                latest = ViewModelProvider(activity)[XiaoLingViewModel::class.java].uiState
            }
            if (predicate(latest)) return latest
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError(
            "Timed out waiting for Stage232 state: promptLength=${latest.prompt.length}, " +
                "sharedDraftImported=${latest.sharedDraftImported}, personalTaskMode=${latest.personalTaskMode}, " +
                "sendingMessage=${latest.sendingMessage}, planPresent=${latest.pendingPersonalTaskPlan != null}, " +
                "operationPhase=${latest.personalTaskOperationPhase}, completionPresent=${latest.personalTaskCompletion != null}",
        )
    }

    private companion object {
        const val ARG_REAL_RUN = "stage232RealRun"
        const val ARG_RESTORE_PROVIDER = "stage232RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage232FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage232FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage232FallbackModel"
        const val STATE_PREFERENCES = "stage232_shared_text_one_time_reminder"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_TASK_ID = "task_id"
        const val KEY_WORKFLOW_RUN_ID = "workflow_run_id"
        const val KEY_AGENT_RUN_ID = "agent_run_id"
        const val KEY_SCHEDULED_CONVERSATION_ID = "scheduled_conversation_id"
        const val KEY_BASELINE_AGENT_RUN_ID = "baseline_agent_run_id"
        const val KEY_BASELINE_AGENT_RUN_DIGEST = "baseline_agent_run_digest"
        const val KEY_BASELINE_WORKFLOW_RUN_ID = "baseline_workflow_run_id"
        const val KEY_BASELINE_WORKFLOW_RUN_DIGEST = "baseline_workflow_run_digest"
        const val KEY_BASELINE_SCHEDULED_TASK_ID = "baseline_scheduled_task_id"
        const val KEY_BASELINE_SCHEDULED_TASK_DIGEST = "baseline_scheduled_task_digest"
        const val STATE_TIMEOUT_MS = 20_000L
        const val PLAN_TIMEOUT_MS = 120_000L
        const val REMINDER_CREATION_TIMEOUT_MS = 30_000L
        const val SCHEDULED_RUN_TIMEOUT_MS = 240_000L
        const val NOTIFICATION_TIMEOUT_MS = 10_000L
        const val CLEANUP_STOP_TIMEOUT_MS = 10_000L
        const val STATE_POLL_MS = 100L
        const val SCHEDULED_TASK_POLL_MS = 500L
        const val NOTIFICATION_POLL_MS = 200L
        const val CLEANUP_STOP_POLL_MS = 250L
        const val ONE_MINUTE_MS = 60_000L
        val STABLE_SCHEDULED_TASK_STATUSES = setOf(
            ScheduledTaskStatus.COMPLETED,
            ScheduledTaskStatus.FAILED,
            ScheduledTaskStatus.BLOCKED,
            ScheduledTaskStatus.CANCELLED,
        )
    }
}
