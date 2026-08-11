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
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeTextPolicy
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
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
 * long: 第 252 阶段通过真实前台对话和屏幕审批，把唯一 Room 笔记导入知识库并从答案引用回看当前原文。
 */
@RunWith(AndroidJUnit4::class)
class Stage252NoteKnowledgeImportInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageNoteImportCompletesThroughVisibleApprovalAndCurrentSourceUi() = runBlocking {
        requireStage252RedmiRun()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val noteStore = RoomAgentNoteStore(context)
        val knowledgeStore = RoomKnowledgeDocumentStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val state = fixtureState()
        cleanupPreviousFixture(state, database, profileStore, noteStore, knowledgeStore, roomState, runRepository)

        val providerSnapshot = ProviderRepository(context).load()
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
        val keyword = "stage252_note_import_$now"
        val noteTitle = "第252阶段知识导入验收 $keyword"
        val noteContent = "这是第252阶段唯一知识正文。事实标识：$keyword。审批后只允许把当前笔记导入本地知识库。"
        val note = noteStore.create(
            title = noteTitle,
            content = noteContent,
            idempotencyKey = "stage252-note-fixture-$now",
        )
        val contentHash = KnowledgeTextPolicy.decodeUtf8(noteContent.toByteArray(Charsets.UTF_8)).contentHash
        val profileId = "stage252-note-import-$now"
        val conversationId = "conversation-stage252-note-import-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = PROFILE_NAME,
            avatar = "252",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = buildString {
                append("For the stage252 request, select only local-note-knowledge-import. ")
                append("Call exactly notes.search, then notes.get, then knowledge.import_from_note. ")
                append("Use the exact notes.search query '$keyword'. ")
                append("Copy the unique note_id, revision, and lowercase content hash from notes.get without changes. ")
                append("Wait for visible user approval before importing and call no other tool.")
            },
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = TOOL_SEQUENCE,
            allowedSkillIds = listOf(SKILL_ID),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第252阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_NOTE_TITLE, noteTitle)
            .putString(KEY_KEYWORD, keyword)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        val prompt = "/agent 请把标题或正文中唯一包含关键词 $keyword 的本地笔记导入知识库。严格执行搜索、读取详情和审批导入，不要处理其他笔记。"
        var completedRunId: String? = null
        var committedDocumentId: String? = null
        try {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                scenario.awaitState { current ->
                    !current.loadingConversationMessages &&
                        current.selectedConversationId == conversationId &&
                        current.selectedAgentProfileId == profileId
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(prompt)
                }
                clickVisibleNode(description = "发送", timeoutMs = 10_000L)

                val waiting = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.pendingAgentApproval?.toolName == IMPORT_TOOL &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                val pendingArguments = requireNotNull(waiting.pendingAgentApproval).arguments
                assertEquals(note.id, pendingArguments["note_id"])
                assertEquals(note.revision.toString(), pendingArguments["expected_revision"])
                assertEquals(contentHash, pendingArguments["expected_content_hash"])
                clickVisibleNode(text = "批准执行", alternateText = "批准并继续", timeoutMs = 15_000L)

                val completed = scenario.awaitState(timeoutMs = 240_000L) { current ->
                    current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null &&
                        !current.sendingMessage &&
                        current.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool -> tool.toolName == IMPORT_TOOL && tool.knowledgeReferences.size == 1 }
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)

                val detail = runRepository.recentRunDetails(30)
                    .firstOrNull { it.snapshot.run.conversationId == conversationId }
                assertNotNull("没有找到第252阶段真实笔记导入 Run", detail)
                requireNotNull(detail)
                completedRunId = detail.snapshot.run.id
                state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
                assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
                assertEquals(TOOL_SEQUENCE, detail.toolLedger.calls.map { it.toolName })

                val callsByTool = detail.toolLedger.calls.associateBy { it.toolName }
                assertEquals(keyword, requireNotNull(callsByTool[SEARCH_TOOL]).arguments["query"])
                assertEquals(note.id, requireNotNull(callsByTool[GET_TOOL]).arguments["note_id"])
                val importCall = requireNotNull(callsByTool[IMPORT_TOOL])
                assertEquals(note.id, importCall.arguments["note_id"])
                assertEquals(note.revision.toString(), importCall.arguments["expected_revision"])
                assertEquals(contentHash, importCall.arguments["expected_content_hash"])

                val resultsByTool = detail.toolLedger.results.associateBy { it.toolName }
                TOOL_SEQUENCE.forEach { toolName ->
                    assertTrue("$toolName 没有成功", requireNotNull(resultsByTool[toolName]).success)
                }
                val importResult = requireNotNull(resultsByTool[IMPORT_TOOL])
                assertEquals(true, importResult.executorVerified)
                assertEquals(ToolVerificationStatus.PASSED, importResult.verificationStatus)
                assertEquals(ToolExecutionReceiptStatus.COMMITTED, importResult.executionReceipt?.status)
                assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == importCall.id }.status)
                val documentId = requireNotNull(importResult.executionReceipt?.operationId)
                committedDocumentId = documentId
                state.edit().putString(KEY_DOCUMENT_ID, documentId).commit()

                val reference = importResult.knowledgeReferences.single()
                assertEquals(documentId, reference.documentId)
                val document = knowledgeStore.getDocument(documentId)
                assertNotNull("COMMITTED 回执指向的知识文档不存在", document)
                requireNotNull(document)
                assertEquals(noteContent, document.normalizedText)
                assertEquals(contentHash, document.contentHash)
                assertEquals(1, document.revision)
                assertTrue(knowledgeStore.getChunks(documentId).isNotEmpty())
                assertEquals(
                    KnowledgeReferenceAvailability.CURRENT,
                    knowledgeStore.inspectReferences(listOf(reference)).single().availability,
                )

                val messageTool = MessageRepository(database)
                    .loadConversation(conversationId)
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Tool>()
                    .single { it.toolName == IMPORT_TOOL }
                assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
                assertEquals(listOf(reference), messageTool.knowledgeReferences)

                // long: 审批后重建 Activity，答案引用必须从 Room 重新投影；随后所有原文核验都经可见节点点击进入知识库。
                scenario.recreate()
                scenario.awaitState(timeoutMs = 20_000L) { current ->
                    !current.loadingConversationMessages &&
                        current.selectedConversationId == conversationId &&
                        current.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool -> tool.toolName == IMPORT_TOOL && tool.knowledgeReferences == listOf(reference) } &&
                        current.knowledgeReferenceStatuses[reference]?.availability == KnowledgeReferenceAvailability.CURRENT
                }
                clickVisibleNode(text = "知识引用 · 1", timeoutMs = 20_000L, scrollForward = true)
                clickVisibleNode(
                    description = "打开知识原文 ${reference.documentName}",
                    timeoutMs = 20_000L,
                    scrollForward = true,
                )
                assertTrue("知识页没有显示当前引用原文", awaitVisibleText("当前引用原文", 20_000L))
                assertTrue("知识页没有显示第252阶段唯一正文", awaitVisibleText(keyword, 10_000L))

                val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
                val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
                if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                    assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
                }
            } finally {
                scenario.close()
            }
        } finally {
            cleanupPreviousFixture(state, database, profileStore, noteStore, knowledgeStore, roomState, runRepository)
        }

        val runId = requireNotNull(completedRunId)
        val documentId = requireNotNull(committedDocumentId)
        assertNotNull("清理临时数据后必须保留第252阶段 Run 审计", runRepository.runDetail(runId))
        assertNull("临时笔记清理后仍可见", noteStore.get(note.id))
        assertNull("临时知识文档清理后仍存在", knowledgeStore.getDocument(documentId))
        assertTrue("临时知识 chunks 清理后仍存在", knowledgeStore.getChunks(documentId).isEmpty())
        assertFalse(profileStore.list().any { it.id == profileId })
        assertNull(database.conversationDao().getConversation(conversationId))
        println(
            "STAGE252_NOTE_KNOWLEDGE_IMPORT runId=$runId documentId=$documentId " +
                "tools=${TOOL_SEQUENCE.joinToString("->")} approvalUiClicked=true approval=APPROVED " +
                "verification=PASSED receipt=COMMITTED answerReference=true currentSourceUi=true " +
                "cleanupVerified=true runAuditPreserved=true oldRunUnchanged=true",
        )
    }

    private fun requireStage252RedmiRun() {
        assumeTrue(
            "第252阶段真实模型验收只在显式 stage252RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第252阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        noteStore: RoomAgentNoteStore,
        knowledgeStore: RoomKnowledgeDocumentStore,
        roomState: RoomStateStore,
        runRepository: RoomAgentRunRepository,
    ) {
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val noteId = state.getString(KEY_NOTE_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val recordedRun = state.getString(KEY_RUN_ID, null)?.let { runRepository.runDetail(it) }
        val fixtureRun = recordedRun ?: conversationId?.let { targetConversationId ->
            runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == targetConversationId }
        }
        val committedDocumentId = state.getString(KEY_DOCUMENT_ID, null)
            ?: fixtureRun?.committedKnowledgeDocumentId()

        // long: 知识清理只接受当前 Run 的 COMMITTED 回执 ID；删除 Store 会在同一事务清理 FTS、embedding、chunks 和 document。
        committedDocumentId?.let { documentId ->
            fixtureRun?.assertCommittedKnowledgeDocument(documentId)
            if (knowledgeStore.getDocument(documentId) != null) {
                assertTrue("无法精确清理第252阶段知识文档", knowledgeStore.delete(documentId))
            }
            assertNull(knowledgeStore.getDocument(documentId))
            assertTrue(knowledgeStore.getChunks(documentId).isEmpty())
        }
        noteId?.let { storedNoteId ->
            assertTrue("拒绝清理不属于第252阶段的临时笔记", storedNoteId.startsWith("note-"))
            if (noteStore.get(storedNoteId) != null) {
                // long: 临时笔记走生产 tombstone 删除，历史调用不能把已撤回正文重新导入；列表和详情均不再暴露正文。
                assertTrue("无法清理第252阶段临时笔记", noteStore.delete(storedNoteId))
            }
            assertNull(noteStore.get(storedNoteId))
        }

        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { storedProfileId ->
            assertTrue("拒绝删除不属于第252阶段的临时 Profile", storedProfileId.startsWith("stage252-note-import-"))
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

    private fun AgentRunDetailRecord.committedKnowledgeDocumentId(): String? {
        val callsById = toolLedger.calls.associateBy { it.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == IMPORT_TOOL &&
                result.success &&
                result.executionReceipt?.status == ToolExecutionReceiptStatus.COMMITTED
        }?.executionReceipt?.operationId
    }

    private fun AgentRunDetailRecord.assertCommittedKnowledgeDocument(documentId: String) {
        assertEquals(documentId, committedKnowledgeDocumentId())
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
                // long: 引用展开会改变最后一条消息的高度；每轮只推进一次，避免 Accessibility action 与手势叠加后越过目标行。
                if (root?.scrollForward() == true) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                } else {
                    swipeConversationForward()
                }
                nextGestureAt = now + 600L
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        val visibleTree = automation.rootInActiveWindow?.describeVisibleTree().orEmpty()
        throw AssertionError("没有找到或无法点击可见节点：${text ?: description}\n当前可见节点：\n$visibleTree")
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
            (expectedText != null && nodeText == expectedText) ||
            (alternateText != null && nodeText == alternateText) ||
            (expectedDescription != null && nodeDescription == expectedDescription)
        ) return this
        repeat(childCount) { index ->
            getChild(index)?.findNode(expectedText, alternateText, expectedDescription)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (text?.toString()?.contains(expected) == true || contentDescription?.toString()?.contains(expected) == true) return true
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

    private fun AccessibilityNodeInfo.scrollForward(): Boolean {
        if (isScrollable && performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        repeat(childCount) { index ->
            if (getChild(index)?.scrollForward() == true) return true
        }
        return false
    }

    private fun swipeConversationForward() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.78f
        val endY = metrics.heightPixels * 0.32f
        val downTime = SystemClock.uptimeMillis()
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0), true)
        repeat(6) { index ->
            val eventTime = downTime + (index + 1) * 20L
            val progress = (index + 1) / 7f
            val y = startY + (endY - startY) * progress
            automation.injectInputEvent(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0), true)
        }
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 160L, MotionEvent.ACTION_UP, x, endY, 0), true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
            "Timed out waiting for Stage252 state: " +
                "selectedConversation=${latest.selectedConversationId}, selectedProfile=${latest.selectedAgentProfileId}, " +
                "sendingMessage=${latest.sendingMessage}, approval=${latest.pendingAgentApproval?.toolName}, " +
                "runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val SEARCH_TOOL = "notes.search"
        const val GET_TOOL = "notes.get"
        const val IMPORT_TOOL = "knowledge.import_from_note"
        val TOOL_SEQUENCE = listOf(SEARCH_TOOL, GET_TOOL, IMPORT_TOOL)
        const val SKILL_ID = "local-note-knowledge-import"
        const val PROFILE_NAME = "第252阶段笔记知识导入验收"
        const val ARG_REAL_RUN = "stage252RealRun"
        const val STATE_PREFERENCES = "stage252_note_knowledge_import"
        const val ROOM_STATE_PREFERENCES = "xiaoling_room_state"
        const val ROOM_STATE_AGENT_PROFILE_ID = "selected_agent_profile_id"
        const val ROOM_STATE_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_NOTE_TITLE = "note_title"
        const val KEY_KEYWORD = "keyword"
        const val KEY_RUN_ID = "run_id"
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val STATE_TIMEOUT_MS = 15_000L
        const val STATE_POLL_MS = 100L
    }
}
