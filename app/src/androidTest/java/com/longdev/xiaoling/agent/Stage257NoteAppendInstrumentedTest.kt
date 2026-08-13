package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
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
 * long: 第 257 阶段从真实自然语言目标出发，经可见审批向唯一 Room 笔记追加内容，并从答案入口回看当前权威正文。
 */
@RunWith(AndroidJUnit4::class)
class Stage257NoteAppendInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageUniqueNoteAppendCompletesThroughVisibleApprovalAndCurrentRoomUi() = runBlocking {
        requireStage257RedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val noteStore = RoomAgentNoteStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val state = fixtureState()
        cleanupPreviousFixture(state, database, profileStore, noteStore, roomState, runRepository)

        val providerRepository = ProviderRepository(context)
        val originalProviderSnapshot = providerRepository.load()
        overrideProviderBaseUrlIfRequested(providerRepository, originalProviderSnapshot)
        val providerSnapshot = providerRepository.load()
        val provider = providerSnapshot.profiles.firstOrNull { it.id == providerSnapshot.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue(
            "Redmi 当前 Provider 配置不完整",
            provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank(),
        )
        assertTrue("当前模型没有在 Provider 中启用", provider.model in provider.enabledModels)

        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val keyword = "stage257_note_append_$now"
        val noteTitle = "第257阶段追加验收 $keyword"
        val originalContent = "第257阶段原始正文，必须逐字符保持不变。"
        val appendedContent = "追加进展：$keyword 已完成真实前台审批。"
        val expectedContent = "$originalContent\n$appendedContent"
        val note = noteStore.create(
            title = noteTitle,
            content = originalContent,
            idempotencyKey = "stage257-note-fixture-$now",
        )
        val profileId = "stage257-note-append-$now"
        val conversationId = "conversation-stage257-note-append-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = PROFILE_NAME,
            avatar = "257",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = buildString {
                append("For the Stage257 request, select only local-note-append. ")
                append("Call exactly notes.search, then notes.get, then notes.append. ")
                append("Use the exact notes.search query '$keyword'. ")
                append("If and only if there is one result, copy its note_id and revision unchanged. ")
                append("Pass exactly this new content to notes.append: '$appendedContent'. ")
                append("Never include or rewrite the existing body, wait for visible approval, and call no other tool.")
            },
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = TOOL_SEQUENCE,
            allowedSkillIds = listOf(SKILL_ID),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第257阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_NOTE_ID, note.id)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        val prompt = "/agent 请找到标题中唯一包含关键词 $keyword 的本地笔记，并只在原正文末尾追加：$appendedContent"
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
                clickVisibleNode(description = "发送", timeoutMs = 10_000L)

                val waiting = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.id != previousRunId &&
                        current.pendingAgentApproval?.conversationId == conversationId &&
                        current.pendingAgentApproval.toolName == APPEND_TOOL &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                val pendingArguments = requireNotNull(waiting.pendingAgentApproval).arguments
                assertEquals(note.id, pendingArguments["note_id"])
                assertEquals(note.revision.toString(), pendingArguments["expected_revision"])
                assertEquals(appendedContent, pendingArguments["content"])
                clickVisibleNode(text = "批准执行", alternateText = "批准并继续", timeoutMs = 15_000L)

                val completed = scenario.awaitState(timeoutMs = 240_000L) { current ->
                    current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null &&
                        !current.sendingMessage &&
                        current.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool -> tool.toolName == APPEND_TOOL }
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

                val detail = runRepository.recentRunDetails(30)
                    .firstOrNull { it.snapshot.run.conversationId == conversationId }
                assertNotNull("没有找到第257阶段真实笔记追加 Run", detail)
                requireNotNull(detail)
                completedRunId = detail.snapshot.run.id
                state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
                assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
                assertEquals(TOOL_SEQUENCE, detail.toolLedger.calls.map { it.toolName })

                val callsByTool = detail.toolLedger.calls.associateBy { it.toolName }
                assertEquals(keyword, requireNotNull(callsByTool[SEARCH_TOOL]).arguments["query"])
                assertEquals(note.id, requireNotNull(callsByTool[GET_TOOL]).arguments["note_id"])
                val appendCall = requireNotNull(callsByTool[APPEND_TOOL])
                assertEquals(note.id, appendCall.arguments["note_id"])
                assertEquals(note.revision.toString(), appendCall.arguments["expected_revision"])
                assertEquals(appendedContent, appendCall.arguments["content"])

                val resultsByTool = detail.toolLedger.results.associateBy { it.toolName }
                TOOL_SEQUENCE.forEach { toolName ->
                    assertTrue("$toolName 没有成功", requireNotNull(resultsByTool[toolName]).success)
                    assertEquals(ToolVerificationStatus.PASSED, requireNotNull(resultsByTool[toolName]).verificationStatus)
                }
                val appendResult = requireNotNull(resultsByTool[APPEND_TOOL])
                assertEquals(true, appendResult.executorVerified)
                assertEquals(ToolExecutionReceiptStatus.COMMITTED, appendResult.executionReceipt?.status)
                assertEquals(note.id, appendResult.executionReceipt?.operationId)
                assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == appendCall.id }.status)

                val current = requireNotNull(noteStore.get(note.id))
                assertEquals(noteTitle, current.title)
                assertEquals(expectedContent, current.content)
                assertEquals(note.revision + 1L, current.revision)

                val messageTool = MessageRepository(database)
                    .loadConversation(conversationId)
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Tool>()
                    .single { it.toolName == APPEND_TOOL }
                assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
                assertEquals(note.id, messageTool.arguments["note_id"])
                assertEquals(appendedContent, messageTool.arguments["content"])

                // long: 重建 Activity 后只能从 Room 持久化 Tool part 恢复“查看笔记”；点击后页面再次读取当前 Note Store，不回放历史 Tool 正文。
                scenario.recreate()
                scenario.awaitState(timeoutMs = 20_000L) { currentState ->
                    !currentState.loadingConversationMessages &&
                        currentState.selectedConversationId == conversationId &&
                        currentState.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool ->
                                tool.toolName == APPEND_TOOL &&
                                    tool.verificationStatus == MessageToolVerificationStatus.VERIFIED
                            }
                }
                clickVisibleNode(text = "查看笔记", timeoutMs = 20_000L, scrollForward = true)
                assertTrue("笔记详情没有显示稳定标题", awaitVisibleText(noteTitle, 20_000L))
                assertTrue("笔记详情没有显示当前完整正文", awaitVisibleText(expectedContent, 20_000L))
                assertTrue("笔记详情没有显示追加后的 revision", awaitVisibleText("版本 ${note.revision + 1L}", 10_000L))

                val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
                val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
                if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                    assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
                }
            } finally {
                scenario.close()
            }
        } finally {
            cleanupPreviousFixture(state, database, profileStore, noteStore, roomState, runRepository)
            if (InstrumentationRegistry.getArguments().getString(ARG_RESTORE_PROVIDER_AFTER_RUN) == "true") {
                // long: 本机反向代理只服务本次 Redmi 验收；无论链路成功或失败都恢复用户原 Provider，避免 localhost 残留到日常使用。
                providerRepository.save(originalProviderSnapshot.profiles, originalProviderSnapshot.selectedProfileId)
            }
        }

        val runId = requireNotNull(completedRunId)
        assertNotNull("清理临时数据后必须保留第257阶段 Run 审计", runRepository.runDetail(runId))
        assertNull("临时笔记清理后仍可见", noteStore.get(note.id))
        assertFalse(profileStore.list().any { it.id == profileId })
        assertNull(database.conversationDao().getConversation(conversationId))
        println(
            "STAGE257_NOTE_APPEND runId=$runId noteId=${note.id} tools=${TOOL_SEQUENCE.joinToString("->")} " +
                "approvalUiClicked=true approval=APPROVED verification=PASSED receipt=COMMITTED " +
                "currentRoomVerified=true answerNavigation=true activityRecreated=true cleanupVerified=true " +
                "runAuditPreserved=true oldRunUnchanged=true",
        )
    }

    private fun requireStage257RedmiRun() {
        assumeTrue(
            "第257阶段真实模型验收只在显式 stage257RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第257阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private suspend fun overrideProviderBaseUrlIfRequested(
        repository: ProviderRepository,
        snapshot: com.longdev.xiaoling.storage.StoredProfiles,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        val temporaryBaseUrl = arguments.getString(ARG_TEMPORARY_BASE_URL)?.trim().orEmpty()
        val temporaryModel = arguments.getString(ARG_TEMPORARY_MODEL)?.trim().orEmpty()
        if (temporaryBaseUrl.isEmpty() && temporaryModel.isEmpty()) return
        val updated = snapshot.profiles.map { profile ->
            if (profile.id != snapshot.selectedProfileId) {
                profile
            } else {
                profile.copy(
                    baseUrl = temporaryBaseUrl.ifEmpty { profile.baseUrl },
                    model = temporaryModel.ifEmpty { profile.model },
                    availableModels = temporaryModel.takeIf(String::isNotEmpty)?.let(::listOf) ?: profile.availableModels,
                    enabledModels = temporaryModel.takeIf(String::isNotEmpty)?.let(::listOf) ?: profile.enabledModels,
                )
            }
        }
        // long: 临时网络绕行可同步选择已探测通过的模型；API Key 继续使用 Redmi Keystore 当前值，测试参数和主机日志均不携带密钥。
        repository.save(updated, snapshot.selectedProfileId)
        val loaded = repository.load().profiles.single { it.id == snapshot.selectedProfileId }
        if (temporaryBaseUrl.isNotEmpty()) assertEquals(temporaryBaseUrl, loaded.baseUrl)
        if (temporaryModel.isNotEmpty()) assertEquals(temporaryModel, loaded.model)
    }

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        noteStore: RoomAgentNoteStore,
        roomState: RoomStateStore,
        runRepository: RoomAgentRunRepository,
    ) {
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val recordedRun = state.getString(KEY_RUN_ID, null)?.let { runRepository.runDetail(it) }
        val fixtureRun = recordedRun ?: conversationId?.let { targetConversationId ->
            runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == targetConversationId }
        }
        val noteId = state.getString(KEY_NOTE_ID, null) ?: fixtureRun?.committedAppendedNoteId()

        noteId?.let { storedNoteId ->
            assertTrue("拒绝清理不属于第257阶段的临时笔记", storedNoteId.startsWith("note-"))
            fixtureRun?.committedAppendedNoteId()?.let { committedNoteId ->
                assertEquals(storedNoteId, committedNoteId)
            }
            if (noteStore.get(storedNoteId) != null) {
                // long: 追加回执指向原笔记稳定 ID；夹具清理只按该身份做 tombstone，绝不按标题或正文搜索删除用户数据。
                assertTrue("无法精确清理第257阶段临时笔记", noteStore.delete(storedNoteId))
            }
            assertNull(noteStore.get(storedNoteId))
        }

        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { storedProfileId ->
            assertTrue("拒绝删除不属于第257阶段的临时 Profile", storedProfileId.startsWith("stage257-note-append-"))
            profileStore.delete(storedProfileId)
        }
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

    private fun AgentRunDetailRecord.committedAppendedNoteId(): String? {
        val callsById = toolLedger.calls.associateBy { it.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == APPEND_TOOL &&
                result.success &&
                result.executionReceipt?.status == ToolExecutionReceiptStatus.COMMITTED
        }?.executionReceipt?.operationId
    }

    private fun AgentRunDetailRecord.assertCommittedAppendedNote(noteId: String) {
        assertEquals(noteId, committedAppendedNoteId())
    }

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        context.getSharedPreferences(ROOM_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (profileId == null) remove(ROOM_STATE_AGENT_PROFILE_ID) else putString(ROOM_STATE_AGENT_PROFILE_ID, profileId)
                if (conversationId == null) remove(ROOM_STATE_CONVERSATION_ID) else putString(ROOM_STATE_CONVERSATION_ID, conversationId)
            }
            .commit()
    }

    private fun clickVisibleNode(
        text: String? = null,
        alternateText: String? = null,
        description: String? = null,
        timeoutMs: Long,
        scrollForward: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextScrollAt = 0L
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            val node = root?.findNode(text, alternateText, description)
            if (node != null && node.clickSelfOrAncestor()) {
                instrumentation.waitForIdleSync()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now >= nextScrollAt && root?.scrollForward() == true) {
                instrumentation.waitForIdleSync()
                nextScrollAt = now + 600L
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("没有找到或无法点击可见节点：${text ?: description}\n${automation.rootInActiveWindow?.describeVisibleTree().orEmpty()}")
    }

    private fun awaitVisibleText(expected: String, timeoutMs: Long): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            if (root?.containsText(expected) == true) return true
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun AccessibilityNodeInfo.findNode(
        expectedText: String?,
        alternateText: String?,
        expectedDescription: String?,
    ): AccessibilityNodeInfo? {
        val nodeText = text?.toString()
        val nodeDescription = contentDescription?.toString()
        if (
            isVisibleToUser && (
                (expectedText != null && nodeText == expectedText) ||
                    (alternateText != null && nodeText == alternateText) ||
                    (expectedDescription != null && nodeDescription == expectedDescription)
                )
        ) return this
        repeat(childCount) { index ->
            getChild(index)?.findNode(expectedText, alternateText, expectedDescription)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (
            isVisibleToUser &&
            (text?.toString()?.contains(expected) == true || contentDescription?.toString()?.contains(expected) == true)
        ) return true
        repeat(childCount) { index ->
            if (getChild(index)?.containsText(expected) == true) return true
        }
        return false
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
        repeat(childCount) { index ->
            if (getChild(index)?.scrollForward() == true) return true
        }
        return false
    }

    private fun AccessibilityNodeInfo.describeVisibleTree(limit: Int = 80): String {
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (lines.size >= limit) return
            val nodeText = node.text?.toString()?.take(120).orEmpty()
            val nodeDescription = node.contentDescription?.toString()?.take(120).orEmpty()
            if (nodeText.isNotBlank() || nodeDescription.isNotBlank() || node.isClickable || node.isScrollable) {
                lines += "${"  ".repeat(depth.coerceAtMost(8))}text=$nodeText description=$nodeDescription clickable=${node.isClickable} scrollable=${node.isScrollable}"
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> visit(child, depth + 1) }
            }
        }
        visit(this, 0)
        return lines.joinToString("\n")
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
            val run = latest.activeAgentRun?.run
            if (run?.status?.isTerminal == true && run.status != AgentRunStatus.COMPLETED) {
                throw AssertionError(
                    "Stage257 Run 在等待目标状态前终止：status=${run.status}, error=${run.errorMessage}",
                )
            }
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError(
            "Timed out waiting for Stage257 state: selectedConversation=${latest.selectedConversationId}, " +
                "selectedProfile=${latest.selectedAgentProfileId}, sendingMessage=${latest.sendingMessage}, " +
                "approval=${latest.pendingAgentApproval?.toolName}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val SEARCH_TOOL = "notes.search"
        const val GET_TOOL = "notes.get"
        const val APPEND_TOOL = "notes.append"
        val TOOL_SEQUENCE = listOf(SEARCH_TOOL, GET_TOOL, APPEND_TOOL)
        const val SKILL_ID = "local-note-append"
        const val PROFILE_NAME = "第257阶段笔记追加验收"
        const val ARG_REAL_RUN = "stage257RealRun"
        const val ARG_TEMPORARY_BASE_URL = "stage257TemporaryBaseUrl"
        const val ARG_TEMPORARY_MODEL = "stage257TemporaryModel"
        const val ARG_RESTORE_PROVIDER_AFTER_RUN = "stage257RestoreProviderAfterRun"
        const val STATE_PREFERENCES = "stage257_note_append"
        const val ROOM_STATE_PREFERENCES = "xiaoling_room_state"
        const val ROOM_STATE_AGENT_PROFILE_ID = "selected_agent_profile_id"
        const val ROOM_STATE_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_RUN_ID = "run_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val STATE_TIMEOUT_MS = 15_000L
        const val STATE_POLL_MS = 100L
    }
}
