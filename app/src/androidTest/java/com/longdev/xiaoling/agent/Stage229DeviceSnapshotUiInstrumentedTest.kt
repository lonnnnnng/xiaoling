package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.device.DeviceSnapshotCodec
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 真实应用壳内完成“打开受限系统应用 -> 用户审批 -> 读取脱敏快照”的主进程闭环；
 * 测试只允许两个工具，验证后按临时身份清理业务数据并保留 Run 审计。
 */
@RunWith(AndroidJUnit4::class)
class Stage229DeviceSnapshotUiInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareMinimalProfileAndDedicatedConversation() = runBlocking {
        requireManualRedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val provider = ProviderRepository(context).load().profiles.firstOrNull { profile ->
            profile.id == ProviderRepository(context).load().selectedProfileId
        }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider Base URL 为空", provider.baseUrl.isNotBlank())
        assertTrue("Redmi 当前 Provider API Key 为空", provider.apiKey.isNotBlank())
        assertTrue("Redmi 当前 Provider 模型为空", provider.model.isNotBlank())

        val state = fixtureState()
        val previousProfileId = state.getString(KEY_PROFILE_ID, null)
        val previousConversationId = state.getString(KEY_CONVERSATION_ID, null)
        previousProfileId?.let { profileStore.delete(it) }
        previousConversationId?.let { id ->
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(id))
                database.conversationDao().deleteConversations(listOf(id))
            }
        }
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null) ?: roomState.selectedAgentProfileId()
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null) ?: roomState.selectedConversationId()
        originalProfileId?.let { profileStore.select(it) }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        val baselineRun = RoomAgentRunRepository(context).recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage229-device-snapshot-$now"
        val conversationId = "conversation-stage229-device-snapshot-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第229阶段设备观察验收",
            avatar = "229",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "严格只使用 device.open_app 打开系统设置，再使用 device.snapshot 观察当前公开页面。device.open_app 必须等待用户审批；不得调用 tap_ref、type_text、swipe、back 或 home，不得读取密码、验证码、支付或隐私窗口内容，不得泄露 Provider 配置。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("device.open_app", "device.snapshot"),
            allowedSkillIds = listOf("device-control"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )

        profileStore.upsert(profile)
        assertTrue("无法选择第229阶段临时 Profile", profileStore.select(profileId))
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
        println("STAGE229_PREPARED profileId=$profileId conversationId=$conversationId providerReady=true")
    }

    @Test
    fun auditCompletedForegroundRunAndCleanup() = runBlocking {
        requireManualRedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val state = fixtureState()
        val profileId = requireNotNull(state.getString(KEY_PROFILE_ID, null)) { "缺少第229阶段 Profile" }
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第229阶段会话" }
        val provider = ProviderRepository(context).load().profiles.firstOrNull { profile ->
            profile.id == ProviderRepository(context).load().selectedProfileId
        }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider Base URL 为空", provider.baseUrl.isNotBlank())
        assertTrue("Redmi 当前 Provider API Key 为空", provider.apiKey.isNotBlank())
        assertTrue("Redmi 当前 Provider 模型为空", provider.model.isNotBlank())

        try {
            val repository = RoomAgentRunRepository(context)
            val detail = repository.recentRunDetails(30).firstOrNull { candidate ->
                candidate.snapshot.run.conversationId == conversationId &&
                    candidate.toolLedger.calls.map { call -> call.toolName } ==
                    listOf("device.open_app", "device.snapshot")
            }
            assertNotNull("未找到第229阶段真实前台设备观察 Run", detail)
            requireNotNull(detail)
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            assertEquals(conversationId, detail.snapshot.run.conversationId)
            val selectedProfile = detail.snapshot.events
                .singleOrNull { event -> event.type == AgentEventTypes.PROFILE_SELECTED }
                ?.metadata
                .let { metadata -> metadata as? RunEventMetadata.AgentProfileSelection }
                ?.profile
            assertEquals(profileId, selectedProfile?.id)
            assertEquals(listOf("device.open_app", "device.snapshot"), selectedProfile?.allowedToolNames)
            assertEquals(listOf("device-control"), selectedProfile?.allowedSkillIds)
            assertEquals(listOf("device.open_app", "device.snapshot"), detail.toolLedger.calls.map { it.toolName })
            assertEquals(listOf("device.open_app", "device.snapshot"), detail.toolLedger.results.map { it.toolName })
            val openCall = detail.toolLedger.calls.single { it.toolName == "device.open_app" }
            assertEquals(mapOf("package_name" to "com.android.settings"), openCall.arguments)
            val openResult = detail.toolLedger.results.single { it.toolCallId == openCall.id }
            assertTrue(openResult.success)
            assertEquals(true, openResult.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, openResult.verificationStatus)
            val snapshotResult = detail.toolLedger.results.single { it.toolName == "device.snapshot" }
            assertTrue(snapshotResult.success)
            assertEquals(ToolVerificationStatus.PASSED, snapshotResult.verificationStatus)
            val snapshotSummary = requireNotNull(DeviceSnapshotCodec.decodeSummary(snapshotResult.content))
            assertEquals("com.android.settings", snapshotSummary.packageName)
            assertTrue(snapshotSummary.nodeCount in 0..200)
            assertTrue(snapshotSummary.redactedNodeCount in 0..snapshotSummary.nodeCount)
            assertTrue(snapshotResult.content.length <= 20_000)
            assertEquals(1, detail.approvals.size)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single().status)
            assertEquals(openCall.arguments, detail.approvals.single().arguments)
            assertFalse(snapshotResult.content.contains(provider.apiKey))
            assertFalse(snapshotResult.content.contains(provider.baseUrl))
            assertFalse(detail.snapshot.run.result.orEmpty().contains(provider.apiKey))
            assertFalse(detail.snapshot.run.result.orEmpty().contains(provider.baseUrl))
            assertFalse(detail.snapshot.run.result.orEmpty().contains("device.tap_ref"))
            assertFalse(detail.snapshot.run.result.orEmpty().contains("device.type_text"))
            assertFalse(detail.snapshot.run.result.orEmpty().contains("device.swipe"))

            val messageTools = MessageRepository(database)
                .loadConversation(conversationId)
                .flatMap { message -> message.parts }
                .filterIsInstance<MessagePart.Tool>()
            assertEquals(listOf("device.open_app", "device.snapshot"), messageTools.map { part -> part.toolName })
            assertTrue(messageTools.all { part -> part.success })
            // long: 设备动作需要 Executor 回读验证，只读快照只证明结果可读；消息层必须保留两类证据强度，不能统一升级为 VERIFIED。
            assertEquals(
                listOf(
                    MessageToolVerificationStatus.VERIFIED,
                    MessageToolVerificationStatus.READABLE_ONLY,
                ),
                messageTools.map { part -> part.verificationStatus },
            )
            assertEquals(openCall.arguments, messageTools.first().arguments)
            assertEquals(openResult.content, messageTools.first().result)
            assertEquals(snapshotResult.content, messageTools.last().result)

            val baselineId = requireNotNull(state.getString(KEY_BASELINE_RUN_ID, null)) { "缺少第229阶段旧 Run baseline" }
            val baselineDigest = requireNotNull(state.getString(KEY_BASELINE_RUN_DIGEST, null)) { "缺少第229阶段旧 Run 摘要" }
            val currentBaseline = requireNotNull(repository.runDetail(baselineId)) { "第229阶段旧 Run 已丢失：$baselineId" }
            assertEquals("第229阶段不得改写旧 Run", baselineDigest, currentBaseline.stableDigest())
            println(
                "STAGE229_AUDITED runId=${detail.snapshot.run.id} conversationId=$conversationId " +
                    "tools=${detail.toolLedger.results.map { it.toolName }} approval=APPROVED " +
                    "snapshotPackage=${snapshotSummary.packageName} nodes=${snapshotSummary.nodeCount} " +
                    "redacted=${snapshotSummary.redactedNodeCount} privacySafe=true oldRunUnchanged=true",
            )
        } finally {
            val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
            val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
            originalProfileId?.let { profileStore.select(it) }
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
            assertTrue(profileStore.delete(profileId))
            originalConversationId?.let(roomState::saveSelectedConversationId)
            persistSelectedState(originalProfileId, originalConversationId)
            state.edit().clear().commit()
            Unit
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第229阶段只允许显式 stage229Manual=true 分段运行",
            InstrumentationRegistry.getArguments().getString(ARG_MANUAL_RUN) == "true",
        )
        assertEquals("第229阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val ARG_MANUAL_RUN = "stage229Manual"
        const val STATE_PREFERENCES = "stage229_device_snapshot_ui"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
    }
}
