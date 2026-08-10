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
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import com.longdev.xiaoling.ui.memoryIdForNavigation
import java.io.File
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
 * long: 第 234 阶段验证外部分享只能先生成长期记忆草稿；正式 memory.remember 仍需用户发送、逐次审批和当前 Room 回读。
 */
@RunWith(AndroidJUnit4::class)
class Stage234SharedTextAgentMemoryInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedTextMemoryCompletesWithApprovalStoreReadBackAndNavigationIdentity() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val memoryStore = RoomAgentMemoryStore(context, database)
        cleanupPreviousFixture(state, database, profileStore, roomState, memoryStore)
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
        val profileId = "stage234-share-memory-$now"
        val conversationId = "conversation-stage234-share-memory-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第234阶段分享记忆验收",
            avatar = "234",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For any user request beginning with '/agent 使用 memory.remember', select only the personal-memory Skill and call memory.remember exactly once. Preserve the supplied shared text exactly as note, wait for explicit approval, and do not call memory.search or any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("memory.search", "memory.remember"),
            allowedSkillIds = listOf("personal-memory"),
            memoryEnabled = true,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第234阶段临时 Profile", profileStore.select(profileId))
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
            val sharedText = "stage234-share-${System.nanoTime()}\n我偏好回答先给结论。"
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
                    viewModel.updateAgentMemoryRecallEnabled(true)
                    viewModel.createAgentMemoryDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt.startsWith("/agent 使用 memory.remember") &&
                        current.prompt.contains(sharedText) &&
                        !current.sharedDraftImported &&
                        !current.personalTaskMode
                }
                assertNull(converted.activeAgentRun)
                assertFalse(converted.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val waiting = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingAgentApproval?.toolName == TOOL_NAME &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                assertEquals(TOOL_NAME, waiting.pendingAgentApproval?.toolName)
                assertTrue(waiting.chatMessages.any { message -> message.role == "user" })

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
            assertNotNull("没有找到第234阶段真实分享 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val calls = detail.toolLedger.calls
            assertEquals("分享记忆只允许一次写入调用", listOf(TOOL_NAME), calls.map { it.toolName })
            val call = calls.single()
            // long: 模型可以把自然段换行规范为空格，但必须保留全部文字信息；比较规范空白后的正文可拒绝删字、改写或补充事实。
            assertEquals(sharedText.normalizedWhitespace(), call.arguments["note"]?.normalizedWhitespace())
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            val memoryId = requireNotNull(result.executionReceipt?.operationId)
            assertEquals(listOf(memoryId), result.memoryIdsUsed)
            state.edit().putString(KEY_MEMORY_ID, memoryId).commit()
            val memory = memoryStore.get(memoryId)
            assertNotNull("memory.remember 回执对应的长期记忆无法回读", memory)
            assertEquals(sharedText.normalizedWhitespace(), requireNotNull(memory).content.normalizedWhitespace())

            val messageTool = MessageRepository(database)
                .loadConversation(conversationId)
                .flatMap { message -> message.parts }
                .filterIsInstance<MessagePart.Tool>()
                .single { part -> part.toolName == TOOL_NAME }
            assertTrue(messageTool.success)
            assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
            assertEquals(call.arguments, messageTool.arguments)
            assertEquals(result.content, messageTool.result)
            assertEquals(memoryId, messageTool.memoryIdForNavigation())

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (baselineRunId != null && baselineDigest != null) {
                val unchanged = repository.runDetail(baselineRunId)
                assertNotNull("旧 Run 在分享记忆验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            println(
                "STAGE234_SHARED_MEMORY runId=${detail.snapshot.run.id} memoryId=$memoryId " +
                    "approval=APPROVED verification=PASSED storeReadBack=true navigationIdentity=true oldRunUnchanged=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState, memoryStore)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第234阶段真实模型验收只在显式 stage234RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第234阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        // long: 兜底凭据只通过显式 instrumentation 参数写入设备 Keystore，生产分享路径不读取参数，日志也不输出密钥。
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
        memoryStore: RoomAgentMemoryStore,
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
        val memoryId = state.getString(KEY_MEMORY_ID, null) ?: fixtureRun?.committedMemoryId()
        // long: 清理只使用本轮 COMMITTED 回执里的稳定 ID；不按正文搜索，避免碰触用户已有长期记忆。
        memoryId?.let { memoryStore.delete(it) }
        File(context.filesDir, MEMORY_DELETE_UNDO_FILE).delete()
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

    private fun AgentRunDetailRecord.committedMemoryId(): String? {
        val callsById = toolLedger.calls.associateBy { call -> call.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == TOOL_NAME &&
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

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")

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
            "Timed out waiting for Stage234 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "personalTaskMode=${latest.personalTaskMode}, sendingMessage=${latest.sendingMessage}, " +
                "approvalPresent=${latest.pendingAgentApproval != null}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val TOOL_NAME = "memory.remember"
        const val ARG_REAL_RUN = "stage234RealRun"
        const val ARG_RESTORE_PROVIDER = "stage234RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage234FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage234FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage234FallbackModel"
        const val STATE_PREFERENCES = "stage234_shared_text_agent_memory"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_MEMORY_ID = "memory_id"
        const val MEMORY_DELETE_UNDO_FILE = "agent-memory-delete-undo.json"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
