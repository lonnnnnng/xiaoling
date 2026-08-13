package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 253 阶段冻结高频“记住一条个人事实”的完整前台主链：模型规划、可见审批、当前 Room 回读和答案级记忆查看。
 */
@RunWith(AndroidJUnit4::class)
class Stage253MemoryRememberInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageRememberCompletesThroughVisibleApprovalCurrentMemoryAndCleanup() = runBlocking {
        requireStage253RedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val memoryStore = RoomAgentMemoryStore(context, database)
        val runRepository = RoomAgentRunRepository(context)
        val state = fixtureState()
        cleanupPreviousFixture(state, database, profileStore, roomState, memoryStore)
        restoreProviderFromRunnerArgsIfRequested()

        val providerSnapshot = ProviderRepository(context).load()
        val provider = providerSnapshot.profiles.firstOrNull { it.id == providerSnapshot.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider 配置不完整", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank())
        assertTrue("当前模型没有在 Provider 中启用", provider.model in provider.enabledModels)

        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val marker = "stage253_memory_$now"
        val content = "我偏好把每天的个人任务整理成短清单，验收标记 $marker。"
        val profileId = "stage253-memory-$now"
        val conversationId = "conversation-stage253-memory-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第253阶段长期记忆验收",
            avatar = "253",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the Stage253 request, select only personal-memory and call memory.remember exactly once. Preserve the user's fact without adding facts, wait for visible approval, and call no other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("memory.remember"),
            allowedSkillIds = listOf("personal-memory"),
            memoryEnabled = true,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第253阶段临时 Profile", profileStore.select(profileId))
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

        val prompt = "/agent 请记住以下个人偏好，并在批准后只写入一条长期记忆：$content"
        var completedRunId: String? = null
        var memoryId: String? = null
        try {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                val ready = scenario.awaitState { current ->
                    !current.loadingConversationMessages &&
                        current.selectedConversationId == conversationId &&
                        current.selectedAgentProfileId == profileId
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(prompt)
                }
                val previousRunId = ready.activeAgentRun?.run?.id
                clickVisibleNode(description = "发送", timeoutMs = 15_000L)
                val waiting = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.id != previousRunId &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL &&
                        current.pendingAgentApproval?.toolName == "memory.remember"
                }
                assertEquals("memory.remember", waiting.pendingAgentApproval?.toolName)
                clickVisibleNode(text = "批准执行", alternateText = "批准并继续", timeoutMs = 15_000L)

                val completed = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null &&
                        !current.sendingMessage &&
                        current.chatMessages.flatMap { it.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { it.toolName == "memory.remember" && it.memoryIdsUsed.size == 1 }
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)
            } finally {
                scenario.close()
            }

            val detail = runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
            assertNotNull("没有找到第253阶段真实记忆 Run", detail)
            requireNotNull(detail)
            completedRunId = detail.snapshot.run.id
            state.edit().putString(KEY_RUN_ID, completedRunId).commit()
            assertEquals(listOf("memory.remember"), detail.toolLedger.calls.map { it.toolName })
            val call = detail.toolLedger.calls.single()
            assertEquals("Preference", call.arguments["type"])
            assertEquals(content.normalizedWhitespace(), call.arguments["note"]?.normalizedWhitespace())
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            memoryId = requireNotNull(result.executionReceipt?.operationId)
            assertEquals(listOf(memoryId), result.memoryIdsUsed)
            state.edit().putString(KEY_MEMORY_ID, memoryId).commit()

            val stored = memoryStore.get(memoryId)
            assertNotNull("COMMITTED 回执对应的长期记忆无法回读", stored)
            assertEquals(content.normalizedWhitespace(), requireNotNull(stored).content.normalizedWhitespace())
            assertTrue(stored.enabled)
            val messageTool = MessageRepository(database).loadConversation(conversationId)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
                .single { it.toolName == "memory.remember" }
            assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
            assertEquals(memoryId, messageTool.memoryIdsUsed.single())

            val recreated = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                recreated.awaitState(timeoutMs = 20_000L) { current ->
                    !current.loadingConversationMessages && current.selectedConversationId == conversationId &&
                        current.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>()
                            .any { it.toolName == "memory.remember" && it.memoryIdsUsed == listOf(memoryId) }
                }
                clickVisibleNode(text = "查看记忆", timeoutMs = 20_000L, scrollForward = true)
                recreated.awaitState(timeoutMs = 20_000L) { current ->
                    current.selectedMemoryId == memoryId && current.memories.firstOrNull()?.id == memoryId
                }
            } finally {
                recreated.close()
            }

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
            }
            println("STAGE253_MEMORY_REMEMBER runId=$completedRunId memoryId=$memoryId approval=APPROVED verification=PASSED receipt=COMMITTED currentMemoryView=true oldRunUnchanged=true")
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState, memoryStore)
        }

        assertNotNull("第253阶段 Run 审计必须保留", completedRunId?.let { runRepository.runDetail(it) })
        assertNull("临时长期记忆清理后仍可见", memoryId?.let { memoryStore.get(it) })
    }

    private fun requireStage253RedmiRun() {
        assumeTrue("第253阶段真实模型验收只在显式 stage253RealRun=true 下运行", InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true")
        assertEquals("第253阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        val existing = current.profiles.firstOrNull() ?: com.longdev.xiaoling.model.ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 兜底凭据只通过显式 instrumentation 参数写入设备 Keystore；生产代码和测试日志不读取或输出密钥。
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
        val recordedRun = state.getString(KEY_RUN_ID, null)?.let { RoomAgentRunRepository(context).runDetail(it) }
        val fixtureRun = recordedRun ?: conversationId?.let { id -> RoomAgentRunRepository(context).recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == id } }
        val storedMemoryId = state.getString(KEY_MEMORY_ID, null) ?: fixtureRun?.committedMemoryId()
        // long: 只按本阶段成功回执绑定的稳定 ID 清理，避免正文搜索误触用户真实记忆。
        storedMemoryId?.let { memoryStore.delete(it) }
        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { profileStore.delete(it) }
        if (!conversationId.isNullOrBlank()) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
        }
        originalConversationId?.let { roomState.saveSelectedConversationId(it) }
        persistSelectedState(originalProfileId, originalConversationId)
        state.edit().clear().commit()
    }

    private fun AgentRunDetailRecord.committedMemoryId(): String? {
        val callsById = toolLedger.calls.associateBy { it.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == "memory.remember" && result.success &&
                result.executionReceipt?.status == ToolExecutionReceiptStatus.COMMITTED
        }?.executionReceipt?.operationId
    }

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit().apply {
            if (profileId == null) remove("selected_agent_profile_id") else putString("selected_agent_profile_id", profileId)
            if (conversationId == null) remove("selected_conversation_id") else putString("selected_conversation_id", conversationId)
        }.commit()
    }

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    private fun ActivityScenario<MainActivity>.awaitState(timeoutMs: Long = STATE_TIMEOUT_MS, predicate: (XiaoLingUiState) -> Boolean): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) return latest
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError("Timed out waiting for Stage253 state: selectedConversation=${latest.selectedConversationId}, approval=${latest.pendingAgentApproval?.toolName}, runStatus=${latest.activeAgentRun?.run?.status}, selectedMemory=${latest.selectedMemoryId}")
    }

    private fun clickVisibleNode(text: String? = null, alternateText: String? = null, description: String? = null, timeoutMs: Long, scrollForward: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextGestureAt = 0L
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            val node = root?.findNode(text, alternateText, description)
            if (node != null && node.clickSelfOrAncestor()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now >= nextGestureAt) {
                if (root?.scrollForward() == true) InstrumentationRegistry.getInstrumentation().waitForIdleSync() else swipeContentForward()
                nextGestureAt = now + 600L
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("没有找到可点击节点：${text ?: description}")
    }

    private fun AccessibilityNodeInfo.findNode(expectedText: String?, alternateText: String?, expectedDescription: String?): AccessibilityNodeInfo? {
        if (isVisibleToUser && ((expectedText != null && text?.toString() == expectedText) || (alternateText != null && text?.toString() == alternateText) || (expectedDescription != null && contentDescription?.toString() == expectedDescription))) return this
        repeat(childCount) { index -> getChild(index)?.findNode(expectedText, alternateText, expectedDescription)?.let { return it } }
        return null
    }

    private fun AccessibilityNodeInfo.clickSelfOrAncestor(): Boolean {
        var current: AccessibilityNodeInfo? = this
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.scrollForward(): Boolean {
        if (isScrollable && performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        repeat(childCount) { index -> if (getChild(index)?.scrollForward() == true) return true }
        return false
    }

    private fun swipeContentForward() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.78f
        val endY = metrics.heightPixels * 0.32f
        val downTime = SystemClock.uptimeMillis()
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0), true)
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 120L, MotionEvent.ACTION_MOVE, x, endY, 0), true)
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 160L, MotionEvent.ACTION_UP, x, endY, 0), true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        const val ARG_REAL_RUN = "stage253RealRun"
        const val ARG_RESTORE_PROVIDER = "stage253RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage253FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage253FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage253FallbackModel"
        const val STATE_PREFERENCES = "stage253_memory_remember"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_MEMORY_ID = "memory_id"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
