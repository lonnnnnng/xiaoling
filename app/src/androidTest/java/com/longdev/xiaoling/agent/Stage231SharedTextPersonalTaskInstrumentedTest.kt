package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
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
 * long: 第 231 阶段只在显式参数下运行真实模型；系统分享先回普通草稿，用户转为任务、生成计划和确认后才创建 Workflow。
 */
@RunWith(AndroidJUnit4::class)
class Stage231SharedTextPersonalTaskInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedTextPersonalTaskCompletesThroughConfirmedPlanAndWorkflow() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val workflowRepository = RoomWorkflowRepository(context)
        cleanupPreviousFixture(state, database, profileStore, roomState, workflowRepository)
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
        val now = System.currentTimeMillis()
        val profileId = "stage231-share-task-$now"
        val conversationId = "conversation-stage231-share-task-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第231阶段分享任务验收",
            avatar = "231",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "Use only the device-time Skill and app.current_time. For the confirmed personal task, read the current device time exactly once and summarize only that verified result.",
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
            .commit()

        var completedWorkflowId: String? = null
        try {
            profileStore.upsert(profile)
            assertTrue("无法选择第231阶段临时 Profile", profileStore.select(profileId))
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

            val sharedText = "只创建一个立即执行的单步个人任务：读取并报告当前设备时间。不要安排提醒，也不要使用其他工具。stage231-${System.nanoTime()}"
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                },
            )
            try {
                val imported = scenario.awaitState { current ->
                    current.prompt == sharedText && current.sharedDraftImported && !current.loadingConversationMessages
                }
                assertFalse(imported.personalTaskMode)
                assertNull(imported.pendingPersonalTaskPlan)
                assertNull(imported.activeAgentRun)
                assertFalse(imported.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].createPersonalTaskDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt == sharedText && current.personalTaskMode && !current.sharedDraftImported
                }
                assertFalse(converted.sendingMessage)
                assertNull(converted.pendingPersonalTaskPlan)
                assertTrue(workflowRepository.recentRunDetails(50).none { detail ->
                    detail.run.conversationId == conversationId
                })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val planned = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingPersonalTaskPlan?.sourceGoal == sharedText && !current.sendingMessage
                }.pendingPersonalTaskPlan
                assertNotNull("分享任务没有生成待确认计划", planned)
                requireNotNull(planned)
                assertEquals(listOf("app.current_time"), planned.allowedToolNames)
                assertEquals(1, planned.steps.size)
                assertNull(planned.reminderScheduleLabel)
                assertEquals(listOf("app.current_time"), planned.goalVerificationSpec?.requiredToolNames)
                assertTrue(workflowRepository.recentRunDetails(50).none { detail ->
                    detail.run.conversationId == conversationId
                })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].confirmPendingPersonalTaskPlan()
                }
                val completion = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.personalTaskCompletion != null && !current.sendingMessage &&
                        current.personalTaskOperationPhase == null
                }.personalTaskCompletion
                assertNotNull("确认后的个人任务没有形成完成卡", completion)
                completedWorkflowId = requireNotNull(completion).workflowId
                state.edit().putString(KEY_WORKFLOW_ID, completedWorkflowId).commit()
            } finally {
                scenario.close()
            }

            val workflowId = requireNotNull(state.getString(KEY_WORKFLOW_ID, null))
            val workflow = workflowRepository.getWorkflow(workflowId)
            assertNotNull("第231阶段 Workflow 不存在", workflow)
            requireNotNull(workflow)
            assertEquals(1, workflow.steps.size)
            val workflowRun = workflowRepository.recentRunDetails(50).single { detail ->
                detail.run.workflowId == workflowId && detail.run.conversationId == conversationId
            }
            state.edit().putString(KEY_WORKFLOW_RUN_ID, workflowRun.run.id).commit()
            assertEquals(WorkflowRunStatus.COMPLETED, workflowRun.run.status)
            assertEquals(WorkflowStepStatus.COMPLETED, workflowRun.steps.single().status)
            assertEquals(WorkflowGoalVerificationStatus.VERIFIED, workflowRun.run.goalVerificationDecision?.status)

            val agentRunId = requireNotNull(workflowRun.steps.single().agentRunId)
            state.edit().putString(KEY_AGENT_RUN_ID, agentRunId).commit()
            val agentRun = agentRepository.runDetail(agentRunId)
            assertNotNull("第231阶段 Workflow 未关联 Agent Run", agentRun)
            requireNotNull(agentRun)
            assertEquals(AgentRunStatus.COMPLETED, agentRun.snapshot.run.status)
            assertEquals(listOf("app.current_time"), agentRun.toolLedger.calls.map { call -> call.toolName })
            val toolResult = agentRun.toolLedger.results.single()
            assertTrue(toolResult.success)
            assertEquals(ToolVerificationStatus.PASSED, toolResult.verificationStatus)
            assertTrue(toolResult.content.contains("当前时间："))
            assertTrue(toolResult.content.contains("时区："))
            assertTrue(agentRun.approvals.isEmpty())

            state.getString(KEY_BASELINE_AGENT_RUN_ID, null)?.let { baselineId ->
                val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_AGENT_RUN_DIGEST, null))
                assertEquals(baselineDigest, requireNotNull(agentRepository.runDetail(baselineId)).stableDigest())
            }
            state.getString(KEY_BASELINE_WORKFLOW_RUN_ID, null)?.let { baselineId ->
                val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_WORKFLOW_RUN_DIGEST, null))
                assertEquals(baselineDigest, requireNotNull(workflowRepository.runDetail(baselineId)).stableDigest())
            }
            println(
                "STAGE231_SHARED_TASK workflowId=$workflowId workflowRunId=${workflowRun.run.id} " +
                    "agentRunId=$agentRunId tool=app.current_time verification=PASSED goalDecision=VERIFIED",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState, workflowRepository)
        }
        assertFalse(profileStore.list().any { candidate -> candidate.id == profileId })
        assertNull(database.conversationDao().getConversation(conversationId))
        assertEquals(originalProfileId, roomState.selectedAgentProfileId())
        assertEquals(originalConversationId, roomState.selectedConversationId())
        assertFalse(requireNotNull(workflowRepository.getWorkflow(requireNotNull(completedWorkflowId))).enabled)
        assertTrue(state.all.isEmpty())
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第231阶段真实模型验收只在显式 stage231RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第231阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        // long: 测试只在显式 runner 参数下恢复当前 Keystore Provider；生产分享和个人任务代码都不能读取这些参数。
        repository.save(listOf(restored), restored.id)
        val loaded = repository.load().profiles.single()
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue(loaded.apiKey.isNotBlank())
    }

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        roomState: RoomStateStore,
        workflowRepository: RoomWorkflowRepository,
    ) {
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val workflowId = state.getString(KEY_WORKFLOW_ID, null) ?: conversationId?.let { targetConversationId ->
            workflowRepository.recentRunDetails(50).firstOrNull { detail ->
                detail.run.conversationId == targetConversationId
            }?.run?.workflowId
        }
        // long: Workflow/Run 审计保留，但夹具 Workflow 必须停用；后续应用启动不能把验收任务当成真实自动化继续运行。
        workflowId?.let { workflowRepository.setEnabled(it, false) }
        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { profileStore.delete(it) }
        if (!conversationId.isNullOrBlank()) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
        }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        persistSelectedState(originalProfileId, originalConversationId)
        state.edit().clear().commit()
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
            "Timed out waiting for Stage231 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "personalTaskMode=${latest.personalTaskMode}, sendingMessage=${latest.sendingMessage}, " +
                "planPresent=${latest.pendingPersonalTaskPlan != null}, operationPhase=${latest.personalTaskOperationPhase}, " +
                "completionPresent=${latest.personalTaskCompletion != null}",
        )
    }

    private companion object {
        const val ARG_REAL_RUN = "stage231RealRun"
        const val ARG_RESTORE_PROVIDER = "stage231RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage231FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage231FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage231FallbackModel"
        const val STATE_PREFERENCES = "stage231_shared_text_personal_task"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_WORKFLOW_RUN_ID = "workflow_run_id"
        const val KEY_AGENT_RUN_ID = "agent_run_id"
        const val KEY_BASELINE_AGENT_RUN_ID = "baseline_agent_run_id"
        const val KEY_BASELINE_AGENT_RUN_DIGEST = "baseline_agent_run_digest"
        const val KEY_BASELINE_WORKFLOW_RUN_ID = "baseline_workflow_run_id"
        const val KEY_BASELINE_WORKFLOW_RUN_DIGEST = "baseline_workflow_run_digest"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
