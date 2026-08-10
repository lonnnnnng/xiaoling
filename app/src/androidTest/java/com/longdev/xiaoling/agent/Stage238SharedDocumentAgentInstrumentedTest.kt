package com.longdev.xiaoling.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
 * long: 第 238 阶段验证分享文档不会自行进入 Agent；只有用户明确改写并发送 /agent 后，附件事实才可参与 Responses 规划和受审批工具执行。
 */
@RunWith(AndroidJUnit4::class)
class Stage238SharedDocumentAgentInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedMarkdownEntersAgentOnlyAfterExplicitSendAndCreatesVerifiedNote() = runBlocking {
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
        val runRepository = RoomAgentRunRepository(context)
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "stage238-shared-document-$now"
        val conversationId = "conversation-stage238-shared-document-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第238阶段分享文档验收",
            avatar = "238",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For this controlled acceptance run, read the attached Markdown document. " +
                "Use exactly one notes.create call. Copy the first-level heading without '#' exactly into title, " +
                "and copy the two labeled body lines into content without inventing values. " +
                "Wait for explicit approval, then complete after the verified tool result.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.create"),
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第238阶段临时 Profile", profileStore.select(profileId))
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

        val documentTitle = "stage238_document_${System.nanoTime()}"
        val documentMarker = "stage238_marker_${System.nanoTime()}"
        val documentConclusion = "shared_document_agent_verified"
        val documentBody = """
            # $documentTitle

            验收码：$documentMarker
            结论：$documentConclusion
        """.trimIndent()
        val documentUri = createTestMarkdown(documentBody)
        state.edit()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .putString(KEY_DOCUMENT_URI, documentUri.toString())
            .commit()

        try {
            val caption = "请先审阅这个 Markdown 文档"
            val command = "/agent 阅读当前附件中的 Markdown 文档，只调用 notes.create 一次：" +
                "使用一级标题作为 title，并把“验收码”和“结论”两行完整写入 content。"
            assertFalse("测试命令不能泄露文档标题", command.contains(documentTitle))
            assertFalse("测试命令不能泄露文档验收码", command.contains(documentMarker))

            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, caption)
                    putExtra(Intent.EXTRA_STREAM, documentUri)
                    clipData = android.content.ClipData.newUri(
                        context.contentResolver,
                        "stage238-shared-document",
                        documentUri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            try {
                val imported = scenario.awaitState { current ->
                    current.prompt == caption &&
                        current.pendingDocument?.extractedText == documentBody &&
                        current.sharedDraftImported &&
                        !current.attachingDocument &&
                        !current.loadingConversationMessages
                }
                assertNull(imported.activeAgentRun)
                assertFalse(imported.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(command)
                }
                val edited = scenario.awaitState { current ->
                    current.prompt == command && current.pendingDocument != null && !current.sharedDraftImported
                }
                assertNull(edited.activeAgentRun)
                assertFalse(edited.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val waiting = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingAgentApproval?.toolName == "notes.create" &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                val pendingApproval = requireNotNull(waiting.pendingAgentApproval)
                assertEquals(documentTitle, pendingApproval.arguments["title"])
                assertTrue(pendingApproval.arguments["content"].orEmpty().contains(documentMarker))
                assertTrue(pendingApproval.arguments["content"].orEmpty().contains(documentConclusion))

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

            val detail = runRepository.recentRunDetails(30).firstOrNull { candidate ->
                candidate.snapshot.run.conversationId == conversationId
            }
            assertNotNull("没有找到第238阶段分享文档 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            assertEquals(command.removePrefix("/agent").trim(), detail.snapshot.run.goal)

            val call = detail.toolLedger.calls.single()
            assertEquals("notes.create", call.toolName)
            assertEquals(documentTitle, call.arguments["title"])
            assertTrue(call.arguments["content"].orEmpty().contains(documentMarker))
            assertTrue(call.arguments["content"].orEmpty().contains(documentConclusion))
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)

            val noteId = requireNotNull(result.executionReceipt?.operationId)
            state.edit().putString(KEY_NOTE_ID, noteId).commit()
            val note = RoomAgentNoteStore(context).get(noteId)
            assertNotNull("notes.create 回执对应的文档笔记无法回读", note)
            requireNotNull(note)
            assertEquals(documentTitle, note.title)
            assertTrue(note.content.contains(documentMarker))
            assertTrue(note.content.contains(documentConclusion))

            val messages = MessageRepository(database).loadConversation(conversationId)
            val userMessage = messages.single { message -> message.id == detail.snapshot.run.userMessageId }
            assertEquals(command, userMessage.text)
            val documentPart = userMessage.parts.filterIsInstance<MessagePart.Document>().single()
            assertEquals(documentBody, documentPart.attachment.extractedText)
            assertTrue(documentPart.attachment.fileName.endsWith(".md"))
            val toolPart = messages
                .flatMap { message -> message.parts }
                .filterIsInstance<MessagePart.Tool>()
                .single { part -> part.toolName == "notes.create" }
            assertEquals(call.arguments, toolPart.arguments)
            assertTrue(toolPart.success)
            assertEquals(MessageToolVerificationStatus.VERIFIED, toolPart.verificationStatus)

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (baselineRunId != null && baselineDigest != null) {
                val unchanged = runRepository.runDetail(baselineRunId)
                assertNotNull("旧 Run 在分享文档验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            cleanupPreviousFixture(state, database, profileStore, roomState)
            assertNull("第238阶段临时笔记清理失败", RoomAgentNoteStore(context).get(noteId))
            assertFalse("第238阶段临时 Profile 清理失败", profileStore.list().any { it.id == profileId })
            assertNull("第238阶段临时会话清理失败", database.conversationDao().getConversation(conversationId))
            assertEquals(originalProfileId, roomState.selectedAgentProfileId())
            assertEquals(originalConversationId, roomState.selectedConversationId())
            assertNotNull("第238阶段 Run 审计不应随夹具清理删除", runRepository.runDetail(detail.snapshot.run.id))
            context.contentResolver.query(documentUri, arrayOf(MediaStore.MediaColumns._ID), null, null, null).use { cursor ->
                assertFalse("第238阶段临时 MediaStore 文档清理失败", cursor?.moveToFirst() == true)
            }
            assertTrue("第238阶段夹具状态应在清理后清空", state.all.isEmpty())
            println(
                "STAGE238_SHARED_DOCUMENT_AGENT runId=${detail.snapshot.run.id} noteId=$noteId " +
                    "approval=APPROVED verification=PASSED documentPersisted=true storeReadBack=true " +
                    "oldRunUnchanged=true cleanupVerified=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第238阶段真实模型验收只在显式 stage238RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第238阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        // long: 只有显式 Redmi 验收参数才能恢复 Keystore Provider；生产入口不读取测试参数，日志也不输出凭据。
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
        // long: 清理只接受临时会话中 COMMITTED notes.create 的稳定 operation ID，不能按模型生成的标题或正文模糊删除用户笔记。
        noteId?.let { RoomAgentNoteStore(context).delete(it) }
        state.getString(KEY_DOCUMENT_URI, null)?.let { value ->
            runCatching { context.contentResolver.delete(Uri.parse(value), null, null) }
        }
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

    private fun createTestMarkdown(body: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xiaoling-stage238-${System.nanoTime()}.md")
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiaoLingTest")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Stage238 MediaStore document")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to write Stage238 MediaStore document")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
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
            "Timed out waiting for Stage238 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "pendingDocument=${latest.pendingDocument != null}, attachingDocument=${latest.attachingDocument}, " +
                "sendingMessage=${latest.sendingMessage}, approvalPresent=${latest.pendingAgentApproval != null}, " +
                "runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val ARG_REAL_RUN = "stage238RealRun"
        const val ARG_RESTORE_PROVIDER = "stage238RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage238FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage238FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage238FallbackModel"
        const val STATE_PREFERENCES = "stage238_shared_document_agent"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_DOCUMENT_URI = "document_uri"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
