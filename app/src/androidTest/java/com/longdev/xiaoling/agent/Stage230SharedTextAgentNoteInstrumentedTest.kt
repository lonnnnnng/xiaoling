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
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingViewModel
import com.longdev.xiaoling.ui.XiaoLingUiState
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
 * long: 第 230 阶段只在显式参数下运行真实模型；系统分享先变成可编辑 Agent 草稿，发送和 notes.create 审批仍由用户动作分别触发。
 */
@RunWith(AndroidJUnit4::class)
class Stage230SharedTextAgentNoteInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedTextAgentNoteCompletesWithApprovalAndStoreReadBack() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        cleanupPreviousFixture(state, database, profileStore, roomState)
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
        val baselineRun = RoomAgentRunRepository(context).recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage230-share-note-$now"
        val conversationId = "conversation-stage230-share-note-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第230阶段分享笔记验收",
            avatar = "230",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For any user request beginning with '/agent 使用 notes.create', use only the local-notes Skill. Create one concise note from the supplied shared text, wait for explicit approval before notes.create, and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.list", "notes.search", "notes.create"),
            allowedSkillIds = listOf("local-notes"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第230阶段临时 Profile", profileStore.select(profileId))
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
        state.edit()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        try {
            val sharedText = "stage230-share-${System.nanoTime()}\n这段正文必须原样保留。"
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
                assertNull(imported.activeAgentRun)
                assertFalse(imported.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[XiaoLingViewModel::class.java]
                    viewModel.createAgentNoteDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt.startsWith("/agent 使用 notes.create") && !current.sharedDraftImported
                }
                assertTrue(converted.prompt.contains(sharedText))
                assertNull(converted.activeAgentRun)

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val waiting = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingAgentApproval?.toolName == "notes.create" &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                assertEquals("notes.create", waiting.pendingAgentApproval?.toolName)
                assertFalse(waiting.chatMessages.none { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].approvePendingAgentTool()
                }
                val completed = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)
            } finally {
                scenario.close()
            }

            val repository = RoomAgentRunRepository(context)
            val detail = repository.recentRunDetails(30).firstOrNull { candidate ->
                candidate.snapshot.run.conversationId == conversationId
            }
            assertNotNull("没有找到第230阶段真实分享 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val call = detail.toolLedger.calls.single { it.toolName == "notes.create" }
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            val noteId = requireNotNull(result.executionReceipt?.operationId)
            state.edit().putString(KEY_NOTE_ID, noteId).commit()
            val note = RoomAgentNoteStore(context).get(noteId)
            assertNotNull("notes.create 回执对应的笔记无法回读", note)
            assertTrue(requireNotNull(note).content.contains(sharedText))

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (baselineRunId != null && baselineDigest != null) {
                val unchanged = repository.runDetail(baselineRunId)
                assertNotNull("旧 Run 在分享验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            println("STAGE230_SHARED_NOTE runId=${detail.snapshot.run.id} noteId=$noteId approval=APPROVED verification=PASSED storeReadBack=true")
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第230阶段真实模型验收只在显式 stage230RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第230阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        // long: 仅为真机 instrumentation 的显式恢复参数写入 Keystore 加密 Provider；生产代码不读取测试参数，也不把凭据写入日志。
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
    ) {
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val recordedRun = state.getString(KEY_RUN_ID, null)?.let { runId ->
            RoomAgentRunRepository(context).runDetail(runId)
        }
        val fixtureRun = recordedRun ?: conversationId?.let { targetConversationId ->
            RoomAgentRunRepository(context).recentRunDetails(30).firstOrNull { detail ->
                detail.snapshot.run.conversationId == targetConversationId
            }
        }
        val noteId = state.getString(KEY_NOTE_ID, null) ?: fixtureRun?.committedNoteId()
        // long: 即使真实 Run 在后置断言前失败，也只从该临时会话的 COMMITTED 回执恢复稳定 note ID，避免按标题或正文模糊删除用户笔记。
        noteId?.let { RoomAgentNoteStore(context).delete(it) }
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

    private fun AgentRunDetailRecord.committedNoteId(): String? {
        val callsById = toolLedger.calls.associateBy { call -> call.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == "notes.create" &&
                result.success &&
                result.executionReceipt?.status == ToolExecutionReceiptStatus.COMMITTED
        }?.executionReceipt?.operationId
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
            "Timed out waiting for Stage230 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "personalTaskMode=${latest.personalTaskMode}, sendingMessage=${latest.sendingMessage}, " +
                "approvalPresent=${latest.pendingAgentApproval != null}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val ARG_REAL_RUN = "stage230RealRun"
        const val ARG_RESTORE_PROVIDER = "stage230RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage230FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage230FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage230FallbackModel"
        const val STATE_PREFERENCES = "stage230_shared_text_agent_note"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_NOTE_ID = "note_id"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
