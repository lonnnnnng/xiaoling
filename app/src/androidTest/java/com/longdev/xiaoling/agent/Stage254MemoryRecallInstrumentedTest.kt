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
import java.io.File
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
 * long: 第 254 阶段冻结高频“回忆一项个人偏好”的只读主链，证明答案来自当前 Room 记忆而不是模型猜测。
 */
@RunWith(AndroidJUnit4::class)
class Stage254MemoryRecallInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageRecallUsesStableMemoryIdAndOpensCurrentRoomFactAfterRestart() = runBlocking {
        requireStage254RedmiRun()
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
        val marker = "stage254_recall_$now"
        val content = "我偏好在个人任务完成后立即记录可验证结果，唯一标记 $marker。"
        val profileId = "stage254-memory-$now"
        val conversationId = "conversation-stage254-memory-$now"
        val memory = memoryStore.remember(
            content = content,
            tags = "stage254 recall",
            type = "Preference",
            source = AgentMemorySource(
                conversationId = null,
                runId = null,
                summary = "第254阶段 Redmi 只读回忆夹具",
            ),
            confidence = 1.0,
        )
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第254阶段长期记忆回忆验收",
            avatar = "254",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the Stage254 request, select only personal-memory-detail. Call memory.search exactly once with the exact marker from the user. If and only if there is exactly one result, copy its stable memory ID unchanged into one memory.get call. Call no other tool and do not ask for approval.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("memory.search", "memory.get"),
            allowedSkillIds = listOf("personal-memory-detail"),
            memoryEnabled = true,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第254阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_MEMORY_ID, memory.id)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        val prompt = "/agent 你还记得带有唯一标记 $marker 的个人偏好吗？请先搜索，再读取唯一结果的完整详情后回答。"
        var completedRunId: String? = null
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
                val completed = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.id != previousRunId &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null &&
                        !current.sendingMessage &&
                        current.chatMessages.flatMap { it.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { it.toolName == "memory.get" && it.memoryIdsUsed == listOf(memory.id) }
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)
                assertNull("SAFE 长期记忆读取链不应出现待审批状态", completed.pendingAgentApproval)
            } finally {
                scenario.close()
            }

            val detail = runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
            assertNotNull("没有找到第254阶段真实记忆回忆 Run", detail)
            requireNotNull(detail)
            completedRunId = detail.snapshot.run.id
            state.edit().putString(KEY_RUN_ID, completedRunId).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val selectedProfile = detail.snapshot.events
                .singleOrNull { it.type == AgentEventTypes.PROFILE_SELECTED }
                ?.metadata
                .let { it as? RunEventMetadata.AgentProfileSelection }
                ?.profile
            assertEquals(profileId, selectedProfile?.id)
            assertEquals(listOf("personal-memory-detail"), selectedProfile?.allowedSkillIds)

            val calls = detail.toolLedger.calls
            assertEquals(listOf("memory.search", "memory.get"), calls.map { it.toolName })
            assertEquals(marker, calls[0].arguments["query"]?.trim())
            assertEquals(memory.id, calls[1].arguments["memory_id"]?.trim())
            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            val searchResult = requireNotNull(resultsByCallId[calls[0].id])
            val getResult = requireNotNull(resultsByCallId[calls[1].id])
            listOf(searchResult, getResult).forEach { result ->
                assertTrue(result.success)
                assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
                assertEquals(listOf(memory.id), result.memoryIdsUsed)
            }
            assertTrue(searchResult.content.contains(marker))
            assertTrue(searchResult.content.contains("id=${memory.id}"))
            assertTrue(getResult.content.contains(marker))
            assertTrue(getResult.content.contains(content.substringBefore("，唯一标记")))
            assertTrue("SAFE 长期记忆读取链必须保持零审批", detail.approvals.isEmpty())

            val messageTools = MessageRepository(database).loadConversation(conversationId)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
            assertEquals(listOf("memory.search", "memory.get"), messageTools.map { it.toolName })
            messageTools.forEach { tool ->
                assertEquals(MessageToolVerificationStatus.READABLE_ONLY, tool.verificationStatus)
                assertEquals(listOf(memory.id), tool.memoryIdsUsed)
            }

            val restarted = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                restarted.awaitState(timeoutMs = 20_000L) { current ->
                    !current.loadingConversationMessages && current.selectedConversationId == conversationId &&
                        current.chatMessages.flatMap { it.effectiveParts() }.filterIsInstance<MessagePart.Tool>()
                            .any { it.toolName == "memory.get" && it.memoryIdsUsed == listOf(memory.id) }
                }
                clickVisibleNode(text = "查看记忆", timeoutMs = 20_000L, scrollForward = true)
                val memoryPage = restarted.awaitState(timeoutMs = 20_000L) { current ->
                    current.selectedMemoryId == memory.id && current.memories.any { it.id == memory.id && it.content == content }
                }
                assertEquals(content, memoryPage.memories.single { it.id == memory.id }.content)
            } finally {
                restarted.close()
            }

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
            }
            println("STAGE254_MEMORY_RECALL runId=$completedRunId memoryId=${memory.id} tools=memory.search,memory.get approvals=0 verification=PASSED currentMemoryView=true oldRunUnchanged=true")
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState, memoryStore)
        }

        assertNotNull("第254阶段 Run 审计必须保留", completedRunId.let { runRepository.runDetail(it) })
        assertNull("临时长期记忆清理后仍可见", memoryStore.get(memory.id))
    }

    private fun requireStage254RedmiRun() {
        assumeTrue("第254阶段真实模型验收只在显式 stage254RealRun=true 下运行", InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true")
        assertEquals("第254阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        // long: 兜底凭据只经 instrumentation 参数进入设备 Keystore，测试报告与仓库始终不保存密钥原文。
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
        // long: 只按创建夹具时持久化的稳定 ID 清理，搜索正文不能成为删除用户真实记忆的依据。
        state.getString(KEY_MEMORY_ID, null)?.let { memoryStore.delete(it) }
        // long: 生产删除会保留一次撤销快照；验收夹具不属于用户数据，清理时必须同步移除，避免临时偏好正文滞留在设备文件中。
        File(context.filesDir, MEMORY_DELETE_UNDO_FILE).delete()
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

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit().apply {
            if (profileId == null) remove("selected_agent_profile_id") else putString("selected_agent_profile_id", profileId)
            if (conversationId == null) remove("selected_conversation_id") else putString("selected_conversation_id", conversationId)
        }.commit()
    }

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ActivityScenario<MainActivity>.awaitState(timeoutMs: Long = STATE_TIMEOUT_MS, predicate: (XiaoLingUiState) -> Boolean): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) return latest
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError("Timed out waiting for Stage254 state: selectedConversation=${latest.selectedConversationId}, approval=${latest.pendingAgentApproval?.toolName}, runStatus=${latest.activeAgentRun?.run?.status}, selectedMemory=${latest.selectedMemoryId}")
    }

    private fun clickVisibleNode(text: String? = null, description: String? = null, timeoutMs: Long, scrollForward: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextGestureAt = 0L
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            val node = root?.findNode(text, description)
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

    private fun AccessibilityNodeInfo.findNode(expectedText: String?, expectedDescription: String?): AccessibilityNodeInfo? {
        if (isVisibleToUser && ((expectedText != null && text?.toString() == expectedText) || (expectedDescription != null && contentDescription?.toString() == expectedDescription))) return this
        repeat(childCount) { index -> getChild(index)?.findNode(expectedText, expectedDescription)?.let { return it } }
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
        const val ARG_REAL_RUN = "stage254RealRun"
        const val ARG_RESTORE_PROVIDER = "stage254RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage254FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage254FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage254FallbackModel"
        const val STATE_PREFERENCES = "stage254_memory_recall"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_MEMORY_ID = "memory_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val MEMORY_DELETE_UNDO_FILE = "agent-memory-delete-undo.json"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
