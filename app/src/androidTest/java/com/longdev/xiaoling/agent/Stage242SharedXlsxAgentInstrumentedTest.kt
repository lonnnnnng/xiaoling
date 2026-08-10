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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
 * long: 第 242 阶段使用本地不提取正文的真实 XLSX，验证只有用户明确发送 /agent 后，二进制附件事实才可参与 Responses 规划和受审批工具执行。
 */
@RunWith(AndroidJUnit4::class)
class Stage242SharedXlsxAgentInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedXlsxEntersAgentOnlyAfterExplicitSendAndCreatesVerifiedNote() = runBlocking {
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
        val profileId = "stage242-shared-xlsx-$now"
        val conversationId = "conversation-stage242-shared-xlsx-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第242阶段分享 XLSX 验收",
            avatar = "242",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For this controlled acceptance run, read the attached XLSX file. " +
                "Use exactly one notes.create call. Copy the cell value in the 'TITLE' row exactly into title, " +
                "and copy the 'ACCEPTANCE_CODE' and 'CONCLUSION' row values into content without inventing values. " +
                "Wait for explicit approval, then complete after the verified tool result.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.create"),
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第242阶段临时 Profile", profileStore.select(profileId))
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

        val documentTitle = "stage242_xlsx_${System.nanoTime()}"
        val documentMarker = "stage242_code_${System.nanoTime()}"
        val documentConclusion = "xlsx_binary_agent_verified"
        val xlsxLines = listOf(
            "TITLE: $documentTitle",
            "ACCEPTANCE_CODE: $documentMarker",
            "CONCLUSION: $documentConclusion",
        )
        assertFalse("临时 Profile 不能泄露 XLSX 标题", profile.systemPrompt.contains(documentTitle))
        assertFalse("临时 Profile 不能泄露 XLSX 验收码", profile.systemPrompt.contains(documentMarker))
        assertFalse("临时 Profile 不能泄露 XLSX 结论", profile.systemPrompt.contains(documentConclusion))
        val documentUri = createTestXlsx(xlsxLines)
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
            val caption = "请先审阅这个 XLSX 文档"
            val command = "/agent 阅读当前附件中的 XLSX，只调用 notes.create 一次：" +
                "使用 TITLE 行的单元格值作为 title，并把 ACCEPTANCE_CODE 和 CONCLUSION 行的值写入 content。"
            assertFalse("测试命令不能泄露文档标题", command.contains(documentTitle))
            assertFalse("测试命令不能泄露文档验收码", command.contains(documentMarker))
            assertFalse("测试命令不能泄露文档结论", command.contains(documentConclusion))

            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = XLSX_MIME_TYPE
                    putExtra(Intent.EXTRA_TEXT, caption)
                    putExtra(Intent.EXTRA_STREAM, documentUri)
                    clipData = android.content.ClipData.newUri(
                        context.contentResolver,
                        "stage242-shared-xlsx",
                        documentUri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            try {
                val imported = scenario.awaitState { current ->
                    val document = current.pendingDocument
                    current.prompt == caption &&
                        document?.mimeType == XLSX_MIME_TYPE &&
                        document.pageCount == null &&
                        document.extractedText == null &&
                        current.sharedDraftImported &&
                        !current.attachingDocument &&
                        !current.loadingConversationMessages
                }
                val importedDocument = requireNotNull(imported.pendingDocument)
                assertTrue(importedDocument.copyData().copyOfRange(0, 4).contentEquals(ZIP_LOCAL_HEADER_SIGNATURE))
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
            assertNotNull("没有找到第242阶段分享文档 Run", detail)
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
            assertEquals(XLSX_MIME_TYPE, documentPart.attachment.mimeType)
            assertNull(documentPart.attachment.pageCount)
            assertNull(documentPart.attachment.extractedText)
            assertTrue(
                documentPart.attachment.copyData().copyOfRange(0, 4).contentEquals(ZIP_LOCAL_HEADER_SIGNATURE),
            )
            assertTrue(documentPart.attachment.fileName.endsWith(".xlsx"))
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
            assertNull("第242阶段临时笔记清理失败", RoomAgentNoteStore(context).get(noteId))
            assertFalse("第242阶段临时 Profile 清理失败", profileStore.list().any { it.id == profileId })
            assertNull("第242阶段临时会话清理失败", database.conversationDao().getConversation(conversationId))
            assertEquals(originalProfileId, roomState.selectedAgentProfileId())
            assertEquals(originalConversationId, roomState.selectedConversationId())
            assertNotNull("第242阶段 Run 审计不应随夹具清理删除", runRepository.runDetail(detail.snapshot.run.id))
            context.contentResolver.query(documentUri, arrayOf(MediaStore.MediaColumns._ID), null, null, null).use { cursor ->
                assertFalse("第242阶段临时 MediaStore 文档清理失败", cursor?.moveToFirst() == true)
            }
            assertTrue("第242阶段夹具状态应在清理后清空", state.all.isEmpty())
            println(
                "STAGE242_SHARED_XLSX_AGENT runId=${detail.snapshot.run.id} noteId=$noteId " +
                    "approval=APPROVED verification=PASSED xlsxPersisted=true storeReadBack=true " +
                    "oldRunUnchanged=true cleanupVerified=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第242阶段真实模型验收只在显式 stage242RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第242阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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

    private fun createTestXlsx(lines: List<String>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xiaoling-stage242-${System.nanoTime()}.xlsx")
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiaoLingTest")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Stage242 MediaStore XLSX")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.writeUtf8Entry("[Content_Types].xml", CONTENT_TYPES_XML)
                    zip.writeUtf8Entry("_rels/.rels", ROOT_RELATIONSHIPS_XML)
                    zip.writeUtf8Entry("xl/workbook.xml", WORKBOOK_XML)
                    zip.writeUtf8Entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELATIONSHIPS_XML)
                    zip.writeUtf8Entry("xl/worksheets/sheet1.xml", createWorksheetXml(lines))
                }
            } ?: error("Unable to write Stage242 MediaStore XLSX")
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

    private fun createWorksheetXml(lines: List<String>): String {
        val rows = listOf("Field" to "Value") + lines.map { line ->
            line.substringBefore(':') to line.substringAfter(':').trim()
        }
        val sheetRows = rows.mapIndexed { index, (field, value) ->
            val rowNumber = index + 1
            "<row r=\"$rowNumber\">" +
                "<c r=\"A$rowNumber\" t=\"inlineStr\"><is><t>${field.escapeXml()}</t></is></c>" +
                "<c r=\"B$rowNumber\" t=\"inlineStr\"><is><t>${value.escapeXml()}</t></is></c>" +
                "</row>"
        }.joinToString(separator = "")
        // long: 动态验收值只写入经 Artifact Tool 导入和渲染验证的 sheet1.xml；其余 OPC 部件保持静态，避免 prompt、Profile 或文件名泄露答案。
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<dimension ref=\"A1:B4\"/><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/>" +
            "</sheetViews><sheetFormatPr defaultRowHeight=\"22\"/><cols>" +
            "<col min=\"1\" max=\"1\" width=\"22\" customWidth=\"1\"/>" +
            "<col min=\"2\" max=\"2\" width=\"32\" customWidth=\"1\"/>" +
            "</cols><sheetData>$sheetRows</sheetData>" +
            "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" " +
            "header=\"0.3\" footer=\"0.3\"/></worksheet>"
    }

    private fun ZipOutputStream.writeUtf8Entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun String.escapeXml(): String = buildString(length) {
        this@escapeXml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
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
            "Timed out waiting for Stage242 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "pendingDocument=${latest.pendingDocument != null}, attachingDocument=${latest.attachingDocument}, " +
                "sendingMessage=${latest.sendingMessage}, approvalPresent=${latest.pendingAgentApproval != null}, " +
                "runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val ARG_REAL_RUN = "stage242RealRun"
        const val ARG_RESTORE_PROVIDER = "stage242RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage242FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage242FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage242FallbackModel"
        const val XLSX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val STATE_PREFERENCES = "stage242_shared_xlsx_agent"
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
        val ZIP_LOCAL_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val CONTENT_TYPES_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>"
        const val ROOT_RELATIONSHIPS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"
        const val WORKBOOK_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Agent Facts\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        const val WORKBOOK_RELATIONSHIPS_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet1.xml\"/></Relationships>"
    }
}
