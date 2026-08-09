package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import java.security.MessageDigest
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 226 阶段只验证用户明确发送 Skill 草稿后才会建立真实 Run；测试停在审批卡，
 * 不代替用户点击批准，后续 audit/cleanup 只读取 Room 和精确清理本轮笔记。
 */
@RunWith(AndroidJUnit4::class)
class Stage226SkillSendUiInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun prepareMinimalProfileAndDedicatedConversation() = runBlocking {
        requireManualRedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val state = fixtureState()
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        cleanupPreviousFixture(state, database, profileStore, roomState)

        val provider = ProviderRepository(context).load().profiles.firstOrNull { profile ->
            profile.id == ProviderRepository(context).load().selectedProfileId
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
        val profileId = "stage226-skill-send-$now"
        val conversationId = "conversation-stage226-skill-send-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第226阶段 Skill 发送验收",
            avatar = "226",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the exact user request '把这件事记成笔记', use only the local-notes Skill. Create one concise note from the request, wait for explicit user approval before notes.create, and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.list", "notes.search", "notes.create"),
            allowedSkillIds = listOf("local-notes"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第 226 阶段最小 Profile", profileStore.select(profileId))
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
        println("STAGE226_PREPARED profileId=$profileId conversationId=$conversationId providerReady=true")
    }

    @Test
    fun sendSkillDraftAndStopAtApproval() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val profileId = requireNotNull(state.getString(KEY_PROFILE_ID, null)) { "缺少第 226 阶段 Profile" }
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第 226 阶段会话" }
        val profile = RoomAgentProfileStore(context).list().singleOrNull { it.id == profileId }
        assertNotNull("第 226 阶段 Profile 不存在", profile)
        requireNotNull(profile)
        assertEquals(listOf("local-notes"), profile.allowedSkillIds)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("bottom_tab_settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("bottom_tab_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("settings-entry-agent-skills").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings-entry-agent-skills").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("agent-skill-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("agent-skill-list", useUnmergedTree = true)
            .performScrollToNode(hasTestTag("agent-skill-item:local-notes"))
        composeRule.onNodeWithTag("agent-skill-item:local-notes", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        // long: 第二条示例是明确的本机笔记请求，避免第一条“搜索笔记”绕过写入审批路径。
        composeRule.onNodeWithTag("agent-skill-try:local-notes:1", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("conversation-prompt-input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000) {
            composeRule.onAllNodesWithText("批准执行", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("批准并继续", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val pending = RoomAgentRunRepository(context).recentRunDetails(10).firstOrNull { detail ->
            detail.snapshot.run.conversationId == conversationId && detail.snapshot.run.status == AgentRunStatus.WAITING_APPROVAL
        }
        assertNotNull("发送草稿后应停在真实审批状态", pending)
        println("STAGE226_SENT_PENDING_APPROVAL profileId=$profileId conversationId=$conversationId userConfirmedSend=true approvalPending=true")
        if (InstrumentationRegistry.getArguments().getString(ARG_HOLD_FOR_APPROVAL) == "true") {
            // long: 外部前台确认需要看到真实审批卡；仅显式分段运行时保留 Activity，普通 instrumentation 不被无故拖慢。
            SystemClock.sleep(90_000)
        }
    }

    @Test
    fun auditApprovedRunAndExactCleanup() = runBlocking {
        requireManualRedmiRun()
        val state = fixtureState()
        val profileId = requireNotNull(state.getString(KEY_PROFILE_ID, null)) { "缺少第 226 阶段 Profile" }
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第 226 阶段会话" }
        val repository = RoomAgentRunRepository(context)
        val detail = repository.recentRunDetails(30).firstOrNull { candidate ->
            candidate.snapshot.run.conversationId == conversationId && candidate.snapshot.run.status == AgentRunStatus.COMPLETED
        }
        assertNotNull("未找到第 226 阶段已批准真实 Run", detail)
        requireNotNull(detail)
        assertEquals(profileId, detail.snapshot.events.single { it.type == AgentEventTypes.PROFILE_SELECTED }
            .metadata.let { it as RunEventMetadata.AgentProfileSelection }.profile.id)
        val selection = detail.snapshot.events.single { it.type == "skill.selected" }
            .metadata.let { it as RunEventMetadata.Reason }.reason
        assertTrue("Run 必须审计 local-notes Skill 选择", selection.contains("local-notes@"))
        val call = detail.toolLedger.calls.single { it.toolName == "notes.create" }
        val result = detail.toolLedger.results.single { it.toolCallId == call.id }
        assertTrue(result.success)
        assertEquals(true, result.executorVerified)
        assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
        assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
        val noteId = requireNotNull(result.executionReceipt?.operationId) { "notes.create 缺少稳定 note ID" }
        val toolPart = MessageRepository(XiaoLingDatabase.getInstance(context))
            .loadConversation(conversationId)
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool>()
            .single { it.toolName == "notes.create" }
        assertEquals(call.arguments, toolPart.arguments)
        assertEquals(result.content, toolPart.result)
        assertEquals(MessageToolVerificationStatus.VERIFIED, toolPart.verificationStatus)
        state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).putString(KEY_NOTE_ID, noteId).commit()
        println("STAGE226_AUDITED runId=${detail.snapshot.run.id} profileId=$profileId conversationId=$conversationId skillSelected=local-notes approval=APPROVED verification=PASSED receipt=COMMITTED")

        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val profileStore = RoomAgentProfileStore(context)
        val database = XiaoLingDatabase.getInstance(context)
        val roomState = RoomStateStore(context)
        assertTrue("本轮笔记必须按回执精确删除", RoomAgentNoteStore(context).delete(noteId))
        originalProfileId?.let { assertTrue("恢复原 Agent Profile 失败", profileStore.select(it)) }
        assertTrue(profileStore.delete(profileId))
        database.withTransaction {
            MessageRepository(database).deleteByConversationIds(listOf(conversationId))
            database.conversationDao().deleteConversations(listOf(conversationId))
        }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        persistSelectedState(originalProfileId, originalConversationId)
        assertNotNull("新 Run 审计必须保留", repository.runDetail(detail.snapshot.run.id))
        assertTrue(profileStore.list().none { it.id == profileId })
        assertTrue(database.conversationDao().getConversation(conversationId) == null)
        state.edit().clear().commit()
        println("STAGE226_CLEANUP runId=${detail.snapshot.run.id} runPreserved=true noteRemoved=true temporaryProfileRemoved=true conversationRemoved=true")
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第 226 阶段只允许显式 stage226Manual=true 分段运行",
            InstrumentationRegistry.getArguments().getString(ARG_MANUAL_RUN) == "true",
        )
        assertEquals("第 226 阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        roomState: RoomStateStore,
    ) {
        state.getString(KEY_NOTE_ID, null)?.let { noteId -> RoomAgentNoteStore(context).delete(noteId) }
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
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

    private companion object {
        const val ARG_MANUAL_RUN = "stage226Manual"
        const val ARG_HOLD_FOR_APPROVAL = "stage226HoldForApproval"
        const val STATE_PREFERENCES = "stage226_skill_send_ui"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_NOTE_ID = "note_id"
    }
}
