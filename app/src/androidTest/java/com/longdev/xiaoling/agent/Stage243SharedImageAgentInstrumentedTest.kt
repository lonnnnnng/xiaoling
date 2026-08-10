package com.longdev.xiaoling.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
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
 * long: 第 243 阶段把动态事实只绘制进真实 PNG 像素，验证系统分享不会自动执行，只有用户明确发送 /agent 后视觉输入才可进入受审批工具链。
 */
@RunWith(AndroidJUnit4::class)
class Stage243SharedImageAgentInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedImageEntersAgentOnlyAfterExplicitSendAndCreatesVerifiedNote() = runBlocking {
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

        val originalProfileId = requireNotNull(roomState.selectedAgentProfileId()) {
            "Redmi 当前没有明确选中的 Agent Profile，拒绝推断恢复目标"
        }
        val originalConversationId = requireNotNull(roomState.selectedConversationId()) {
            "Redmi 当前没有明确选中的会话，拒绝推断恢复目标"
        }
        val runRepository = RoomAgentRunRepository(context)
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "$PROFILE_ID_PREFIX$now"
        val conversationId = "$CONVERSATION_ID_PREFIX$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第243阶段分享图片验收",
            avatar = "243",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For this controlled acceptance run, inspect the attached image. " +
                "It contains exactly three labeled rows: TITLE, ACCEPTANCE_CODE, and CONCLUSION. " +
                "Use exactly one notes.create call. Copy the TITLE value exactly into title, and copy the " +
                "ACCEPTANCE_CODE and CONCLUSION values exactly into content. Do not infer values from the " +
                "filename or prompt. Wait for explicit approval, then complete after the verified tool result.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.create"),
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        // long: 原选择和临时主键先于任何夹具写入落盘；后续 Profile、会话或 MediaStore 任一步失败，都只能按本轮稳定 ID 恢复。
        state.edit()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()
        profileStore.upsert(profile)
        assertTrue("无法选择第243阶段临时 Profile", profileStore.select(profileId))
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

        val token = randomVisualToken()
        val imageTitle = "VISUAL NOTE $token"
        val imageMarker = "ACCEPT $token"
        val imageConclusion = "IMAGE VERIFIED $token"
        assertFalse("临时 Profile 不能泄露图片标题", profile.systemPrompt.contains(imageTitle))
        assertFalse("临时 Profile 不能泄露图片验收码", profile.systemPrompt.contains(imageMarker))
        assertFalse("临时 Profile 不能泄露图片结论", profile.systemPrompt.contains(imageConclusion))
        val image = createTestPng(
            title = imageTitle,
            acceptanceCode = imageMarker,
            conclusion = imageConclusion,
        )
        state.edit()
            .putString(KEY_IMAGE_URI, image.uri.toString())
            .commit()

        try {
            val caption = "请先审阅这张图片"
            val command = "/agent 读取当前图片中 TITLE、ACCEPTANCE_CODE 和 CONCLUSION 三行。" +
                "只调用 notes.create 一次：TITLE 对应值作为 title，其余两项按原样写入 content。"
            listOf(imageTitle, imageMarker, imageConclusion).forEach { protectedValue ->
                assertFalse("测试说明不能泄露图片动态事实", caption.contains(protectedValue))
                assertFalse("测试命令不能泄露图片动态事实", command.contains(protectedValue))
            }

            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = IMAGE_MIME_TYPE
                    putExtra(Intent.EXTRA_TEXT, caption)
                    putExtra(Intent.EXTRA_STREAM, image.uri)
                    clipData = android.content.ClipData.newUri(
                        context.contentResolver,
                        "stage243-shared-image",
                        image.uri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            try {
                val imported = scenario.awaitState { current ->
                    current.prompt == caption &&
                        current.pendingImage?.mimeType == IMAGE_MIME_TYPE &&
                        current.sharedDraftImported &&
                        !current.attachingImage &&
                        !current.loadingConversationMessages
                }
                val importedImage = requireNotNull(imported.pendingImage)
                assertTrue(importedImage.copyData().contentEquals(image.bytes))
                assertTrue(importedImage.fileName.endsWith(".png"))
                listOf(imageTitle, imageMarker, imageConclusion).forEach { protectedValue ->
                    assertFalse("图片文件名不能泄露动态事实", importedImage.fileName.contains(protectedValue))
                }
                assertNull(imported.activeAgentRun)
                assertFalse(imported.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(command)
                }
                val edited = scenario.awaitState { current ->
                    current.prompt == command && current.pendingImage != null && !current.sharedDraftImported
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
                assertEquals(imageTitle, pendingApproval.arguments["title"])
                assertTrue(pendingApproval.arguments["content"].orEmpty().contains(imageMarker))
                assertTrue(pendingApproval.arguments["content"].orEmpty().contains(imageConclusion))

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
            assertNotNull("没有找到第243阶段分享图片 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            assertEquals(command.removePrefix("/agent").trim(), detail.snapshot.run.goal)

            val call = detail.toolLedger.calls.single()
            assertEquals("notes.create", call.toolName)
            assertEquals(imageTitle, call.arguments["title"])
            assertTrue(call.arguments["content"].orEmpty().contains(imageMarker))
            assertTrue(call.arguments["content"].orEmpty().contains(imageConclusion))
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)

            val noteId = requireNotNull(result.executionReceipt?.operationId)
            state.edit().putString(KEY_NOTE_ID, noteId).commit()
            val note = RoomAgentNoteStore(context).get(noteId)
            assertNotNull("notes.create 回执对应的图片笔记无法回读", note)
            requireNotNull(note)
            assertEquals(imageTitle, note.title)
            assertTrue(note.content.contains(imageMarker))
            assertTrue(note.content.contains(imageConclusion))

            val messages = MessageRepository(database).loadConversation(conversationId)
            val userMessage = messages.single { message -> message.id == detail.snapshot.run.userMessageId }
            assertEquals(command, userMessage.text)
            val imagePart = userMessage.parts.filterIsInstance<MessagePart.Image>().single()
            assertEquals(IMAGE_MIME_TYPE, imagePart.attachment.mimeType)
            assertTrue(imagePart.attachment.fileName.endsWith(".png"))
            assertTrue(imagePart.attachment.copyData().contentEquals(image.bytes))
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
                assertNotNull("旧 Run 在分享图片验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            cleanupPreviousFixture(state, database, profileStore, roomState)
            assertNull("第243阶段临时笔记清理失败", RoomAgentNoteStore(context).get(noteId))
            assertFalse("第243阶段临时 Profile 清理失败", profileStore.list().any { it.id == profileId })
            assertNull("第243阶段临时会话清理失败", database.conversationDao().getConversation(conversationId))
            assertEquals(originalProfileId, roomState.selectedAgentProfileId())
            assertEquals(originalConversationId, roomState.selectedConversationId())
            assertNotNull("第243阶段 Run 审计不应随夹具清理删除", runRepository.runDetail(detail.snapshot.run.id))
            context.contentResolver.query(image.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null).use { cursor ->
                assertFalse("第243阶段临时 MediaStore 图片清理失败", cursor?.moveToFirst() == true)
            }
            assertTrue("第243阶段夹具状态应在清理后清空", state.all.isEmpty())
            println(
                "STAGE243_SHARED_IMAGE_AGENT runId=${detail.snapshot.run.id} noteId=$noteId " +
                    "approval=APPROVED verification=PASSED imagePersisted=true storeReadBack=true " +
                    "oldRunUnchanged=true cleanupVerified=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第243阶段真实模型验收只在显式 stage243RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第243阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        if (state.all.isEmpty()) return
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
        // long: 清理只接受临时会话中 COMMITTED notes.create 的稳定 operation ID，不能按模型识别出的标题或正文模糊删除用户笔记。
        noteId?.let { RoomAgentNoteStore(context).delete(it) }
        state.getString(KEY_IMAGE_URI, null)?.let { value ->
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

    private fun createTestPng(
        title: String,
        acceptanceCode: String,
        conclusion: String,
    ): TestImage {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.WHITE)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = Color.rgb(16, 44, 84)
        canvas.drawRoundRect(RectF(40f, 40f, IMAGE_WIDTH - 40f, IMAGE_HEIGHT - 40f), 28f, 28f, paint)

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.rgb(16, 44, 84)
        paint.textSize = 62f
        canvas.drawText("XIAOLING VISUAL FACT CARD", 110f, 145f, paint)

        drawFactRow(canvas, paint, top = 230f, label = "TITLE", value = title)
        drawFactRow(canvas, paint, top = 500f, label = "ACCEPTANCE_CODE", value = acceptanceCode)
        drawFactRow(canvas, paint, top = 770f, label = "CONCLUSION", value = conclusion)

        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode Stage243 PNG" }
            output.toByteArray()
        }
        bitmap.recycle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "xiaoling-stage243-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, IMAGE_MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/XiaoLingTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Stage243 MediaStore PNG")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                ?: error("Unable to write Stage243 MediaStore PNG")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return TestImage(uri = uri, bytes = bytes)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun drawFactRow(
        canvas: Canvas,
        paint: Paint,
        top: Float,
        label: String,
        value: String,
    ) {
        paint.color = Color.rgb(232, 240, 252)
        canvas.drawRoundRect(RectF(90f, top, IMAGE_WIDTH - 90f, top + 210f), 24f, 24f, paint)
        paint.color = Color.rgb(16, 44, 84)
        paint.textSize = 44f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(label, 125f, top + 72f, paint)
        paint.color = Color.BLACK
        paint.textSize = 64f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // long: 标签和值分成两行并使用高对比大字号，减少真机上传压缩或模型视觉识别时把标签字符混入业务值的风险。
        canvas.drawText(value, 125f, top + 162f, paint)
    }

    private fun randomVisualToken(): String {
        val random = SecureRandom()
        return buildString(VISUAL_TOKEN_LENGTH) {
            repeat(VISUAL_TOKEN_LENGTH) {
                append(VISUAL_TOKEN_ALPHABET[random.nextInt(VISUAL_TOKEN_ALPHABET.length)])
            }
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
            "Timed out waiting for Stage243 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "pendingImage=${latest.pendingImage != null}, attachingImage=${latest.attachingImage}, " +
                "sendingMessage=${latest.sendingMessage}, approvalPresent=${latest.pendingAgentApproval != null}, " +
                "runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private data class TestImage(
        val uri: Uri,
        val bytes: ByteArray,
    )

    private companion object {
        const val ARG_REAL_RUN = "stage243RealRun"
        const val ARG_RESTORE_PROVIDER = "stage243RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage243FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage243FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage243FallbackModel"
        const val IMAGE_MIME_TYPE = "image/png"
        const val PROFILE_ID_PREFIX = "stage243-shared-image-"
        const val CONVERSATION_ID_PREFIX = "conversation-stage243-shared-image-"
        const val STATE_PREFERENCES = "stage243_shared_image_agent"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_IMAGE_URI = "image_uri"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
        const val IMAGE_WIDTH = 1800
        const val IMAGE_HEIGHT = 1100
        const val VISUAL_TOKEN_LENGTH = 6
        const val VISUAL_TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}
