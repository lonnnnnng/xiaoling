package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * long: 第 221 阶段把夹具准备、真实前台人工审批和事后审计拆开；测试代码不能代替模型规划、审批点击或删除执行。
 */
@RunWith(AndroidJUnit4::class)
class Stage221MemoryDeleteUiInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareUniqueMemoryAndMinimalProfile() = runBlocking {
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val memoryStore = RoomAgentMemoryStore(context)
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }

        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider Base URL 为空", provider.baseUrl.isNotBlank())
        assertTrue("Redmi 当前 Provider API Key 为空", provider.apiKey.isNotBlank())
        assertTrue("Redmi 当前 Provider 模型为空", provider.model.isNotBlank())

        cleanupPreviousFixture(memoryStore, state.getString(KEY_MEMORY_ID, null))
        val originalProfileId = roomState.selectedAgentProfileId()
            ?.takeIf { selected -> selected != PROFILE_ID }
            ?: profileStore.list().firstOrNull { it.id != PROFILE_ID }?.id
        val originalConversationId = roomState.selectedConversationId()
        originalProfileId?.let { profileStore.select(it) }
        profileStore.delete(PROFILE_ID)

        val now = System.currentTimeMillis()
        val marker = "stage221_delete_marker_$now"
        val fixture = memoryStore.remember(
            content = "$marker user explicitly requested this temporary memory be forgotten",
            tags = "stage221",
            type = "Episode",
            source = AgentMemorySource(
                conversationId = null,
                runId = null,
                summary = "第 221 阶段 Redmi 人工删除夹具",
            ),
            confidence = 1.0,
        )
        val profile = AgentProfileRecord(
            id = PROFILE_ID,
            name = "第221阶段记忆删除验收",
            avatar = "221",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For a deletion request, call memory.search with the exact marker and limit 5. If and only if there is one result, call memory.get with its exact memory_id, then call memory.delete with the same memory_id. Wait for user approval and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("memory.search", "memory.get", "memory.delete"),
            allowedSkillIds = listOf("personal-memory-delete"),
            memoryEnabled = true,
            createdAt = now,
            updatedAt = now,
        )

        profileStore.upsert(profile)
        assertTrue("无法选择第 221 阶段最小 Profile", profileStore.select(PROFILE_ID))
        state.edit()
            .clear()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_MARKER, marker)
            .putString(KEY_MEMORY_ID, fixture.id)
            .commit()

        assertEquals(fixture.id, memoryStore.search(marker, 10, enabledOnly = true).single().id)
        println(
            "STAGE221_PREPARED marker=$marker memoryId=${fixture.id} " +
                "profile=$PROFILE_ID providerReady=true",
        )
    }

    @Test
    fun auditCompletedRunAndCurrentAbsence() = runBlocking {
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val marker = requireNotNull(state.getString(KEY_MARKER, null)) { "缺少第 221 阶段 marker" }
        val memoryId = requireNotNull(state.getString(KEY_MEMORY_ID, null)) { "缺少第 221 阶段 memory ID" }
        val repository = RoomAgentRunRepository(context)
        val detail = repository.recentRunDetails(30).firstOrNull { candidate ->
            candidate.toolLedger.calls.any { call ->
                call.toolName == "memory.search" && call.arguments["query"]?.trim() == marker
            }
        }

        assertNotNull("未找到第 221 阶段真实前台 Run", detail)
        requireNotNull(detail)
        state.edit()
            .putString(KEY_RUN_ID, detail.snapshot.run.id)
            .putString(KEY_CONVERSATION_ID, detail.snapshot.run.conversationId)
            .commit()
        assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
        val selectedProfile = detail.snapshot.events
            .singleOrNull { it.type == AgentEventTypes.PROFILE_SELECTED }
            ?.metadata
            .let { it as? RunEventMetadata.AgentProfileSelection }
            ?.profile
        assertEquals(PROFILE_ID, selectedProfile?.id)
        assertEquals(listOf("personal-memory-delete"), selectedProfile?.allowedSkillIds)

        val calls = detail.toolLedger.calls
        assertEquals(listOf("memory.search", "memory.get", "memory.delete"), calls.map { it.toolName })
        assertEquals(marker, calls[0].arguments["query"]?.trim())
        assertEquals(memoryId, calls[1].arguments["memory_id"]?.trim())
        assertEquals(memoryId, calls[2].arguments["memory_id"]?.trim())
        val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
        val orderedResults = calls.map { call -> requireNotNull(resultsByCallId[call.id]) }
        assertTrue(orderedResults.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
        assertEquals(listOf(memoryId), orderedResults[0].memoryIdsUsed)
        assertEquals(listOf(memoryId), orderedResults[1].memoryIdsUsed)
        assertEquals(true, orderedResults[2].executorVerified)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, orderedResults[2].executionReceipt?.status)
        assertEquals(memoryId, orderedResults[2].executionReceipt?.operationId)
        assertEquals(
            ApprovalRequestStatus.APPROVED,
            detail.approvals.single { it.toolName == "memory.delete" }.status,
        )

        val memoryStore = RoomAgentMemoryStore(context)
        assertNull(memoryStore.get(memoryId))
        assertTrue(memoryStore.search(marker, 10, enabledOnly = true).isEmpty())
        println(
            "STAGE221_AUDITED runId=${detail.snapshot.run.id} conversationId=${detail.snapshot.run.conversationId} " +
                "status=COMPLETED tools=memory.search,memory.get,memory.delete approval=APPROVED " +
                "verification=PASSED receipt=COMMITTED currentAbsent=true",
        )
    }

    @Test
    fun cleanupFixtureProfileAndConversationPreservingRun() = runBlocking {
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val marker = requireNotNull(state.getString(KEY_MARKER, null)) { "缺少第 221 阶段 marker" }
        val memoryId = requireNotNull(state.getString(KEY_MEMORY_ID, null)) { "缺少第 221 阶段 memory ID" }
        val runId = requireNotNull(state.getString(KEY_RUN_ID, null)) { "缺少第 221 阶段 Run ID" }
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第 221 阶段会话 ID" }
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val database = XiaoLingDatabase.getInstance(context)
        val memoryStore = RoomAgentMemoryStore(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)

        cleanupPreviousFixture(memoryStore, memoryId)
        originalProfileId?.let { assertTrue("恢复原 Agent Profile 失败", profileStore.select(it)) }
        profileStore.delete(PROFILE_ID)
        // long: 真实前台 Run 可能复用原空会话；清理只删除临时会话，不能把用户已有的空会话身份一并抹掉。
        if (conversationId != originalConversationId) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
        }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        // long: 删除夹具后保留 operation 与 Run 审计，但撤销快照必须精确移除，避免用户在验收结束后把临时记忆恢复回来。
        File(context.filesDir, MEMORY_DELETE_UNDO_FILE).delete()

        assertNull(memoryStore.get(memoryId))
        assertTrue(memoryStore.search(marker, 10, enabledOnly = false).isEmpty())
        assertTrue(profileStore.list().none { it.id == PROFILE_ID })
        val cleanedConversation = database.conversationDao().getConversation(conversationId)
        if (conversationId == originalConversationId) {
            assertNotNull(cleanedConversation)
            assertTrue(database.conversationDao().getMessagesByConversationId(conversationId).isEmpty())
        } else {
            assertNull(cleanedConversation)
        }
        assertNotNull(RoomAgentRunRepository(context).runDetail(runId))
        state.edit().clear().commit()
        println(
            "STAGE221_CLEANUP runId=$runId runPreserved=true temporaryProfileRemoved=true " +
                "conversationCleaned=${conversationId != originalConversationId} fixtureAbsent=true " +
                "undoRemoved=true originalProfileRestored=true",
        )
    }

    @Test
    fun repairOriginalConversationBoundaryAndVerifyRun() = runBlocking {
        val database = XiaoLingDatabase.getInstance(context)
        val originalConversationId = "conversation-1786204146694"
        val runId = "run-73b6e1ca-2b73-4a39-a517-e2461afa5c43"
        val existing = database.conversationDao().getConversation(originalConversationId)
        if (existing == null) {
            val createdAt = originalConversationId.substringAfter("conversation-").toLongOrNull()
                ?: error("第 221 阶段原会话 ID 缺少可恢复创建时间")
            database.conversationDao().insertConversations(
                listOf(
                    ConversationEntity(
                        id = originalConversationId,
                        title = "新会话",
                        summary = "",
                        summaryUntilMessageId = null,
                        summaryUpdatedAt = null,
                        summaryModel = null,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                    ),
                ),
            )
        }
        RoomStateStore(context).saveSelectedConversationId(originalConversationId)

        val restored = database.conversationDao().getConversation(originalConversationId)
        assertNotNull("第 221 阶段原空会话未恢复", restored)
        assertEquals("新会话", restored?.title)
        assertTrue("恢复的原会话不应带入验收消息", database.conversationDao().getMessagesByConversationId(originalConversationId).isEmpty())
        val run = RoomAgentRunRepository(context).runDetail(runId)
        assertNotNull("第 221 阶段真实 Run 审计丢失", run)
        assertEquals(AgentRunStatus.COMPLETED, run?.snapshot?.run?.status)
        assertEquals(originalConversationId, RoomStateStore(context).selectedConversationId())
        println("STAGE221_REPAIRED conversationId=$originalConversationId runId=$runId runPreserved=true emptyConversation=true")
    }

    private suspend fun cleanupPreviousFixture(memoryStore: RoomAgentMemoryStore, memoryId: String?) {
        if (memoryId.isNullOrBlank()) return
        memoryStore.delete(memoryId)
        File(context.filesDir, MEMORY_DELETE_UNDO_FILE).delete()
    }

    private companion object {
        const val PROFILE_ID = "stage221-memory-delete-profile"
        const val STATE_PREFERENCES = "stage221_memory_delete_ui"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_MARKER = "marker"
        const val KEY_MEMORY_ID = "memory_id"
        const val KEY_RUN_ID = "run_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val MEMORY_DELETE_UNDO_FILE = "agent-memory-delete-undo.json"
    }
}
