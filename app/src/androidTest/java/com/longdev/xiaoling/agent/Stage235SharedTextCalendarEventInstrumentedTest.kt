package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
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
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import com.longdev.xiaoling.ui.calendarEventIdForNavigation
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * long: 第 235 阶段验证外部分享只把明确的四字段日程变成可编辑草稿；发送、逐次审批、Provider 回读和精确清理继续沿用生产链。
 */
@RunWith(AndroidJUnit4::class)
class Stage235SharedTextCalendarEventInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun incompleteSharedTextCannotCreateDraftOrStartAgentRun() = runBlocking {
        requireManualRedmiRun()
        val repository = RoomAgentRunRepository(context)
        val baseline = repository.recentRunDetails(1).firstOrNull()
        val sharedText = """
            标题：stage235_missing_zone_${System.nanoTime()}
            开始：2026-08-12T09:00:00+08:00
            结束：2026-08-12T09:30:00+08:00
        """.trimIndent()
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sharedText)
            },
        )
        try {
            scenario.awaitState { current -> current.prompt == sharedText && current.sharedDraftImported }
            var rejected = XiaoLingUiState()
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[XiaoLingViewModel::class.java]
                viewModel.createAgentCalendarEventDraftFromSharedText()
                // long: Activity 会快速消费一次性配置提示；同步读取拒绝后的状态，验证模型调用和 Run 创建尚未发生。
                rejected = viewModel.uiState
            }
            assertEquals(sharedText, rejected.prompt)
            assertEquals(false, rejected.result?.success)
            assertTrue(rejected.result?.message?.contains("IANA 时区") == true)
            assertTrue(rejected.sharedDraftImported)
            assertNull(rejected.activeAgentRun)
            assertFalse(rejected.sendingMessage)
            assertFalse(rejected.chatMessages.any { message -> message.role == "user" })
        } finally {
            scenario.close()
        }

        val latest = repository.recentRunDetails(1).firstOrNull()
        assertEquals("缺字段样本不得创建 Agent Run", baseline?.snapshot?.run?.id, latest?.snapshot?.run?.id)
        if (baseline != null && latest != null) {
            assertEquals("缺字段样本不得改写旧 Run", baseline.stableDigest(), latest.stableDigest())
        }
        println("STAGE235_MISSING_FIELD rejectedBeforeSend=true runCreated=false oldRunUnchanged=true")
    }

    @Test
    fun sharedTextCalendarCompletesWithApprovalProviderReadBackAndNavigationIdentity() = runBlocking {
        requireManualRedmiRun()
        assertCalendarPermissions()
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
        val zone = ZoneId.of(TIME_ZONE)
        val start = LocalDate.now(zone).plusDays(2).atTime(LocalTime.of(9, 0)).atZone(zone).toOffsetDateTime()
        val end = start.plusMinutes(30)
        val title = "stage235_share_calendar_$now"
        val startAt = start.format(OFFSET_DATE_TIME_FORMATTER)
        val endAt = end.format(OFFSET_DATE_TIME_FORMATTER)
        val sharedText = """
            标题：$title
            开始：$startAt
            结束：$endAt
            时区：$TIME_ZONE
        """.trimIndent()
        val profileId = "stage235-share-calendar-$now"
        val conversationId = "conversation-stage235-share-calendar-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = PROFILE_NAME,
            avatar = "235",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For any user request beginning with '/agent 使用 calendar.create_event', select only the calendar-create Skill and call calendar.create_event exactly once. Use the supplied title, start_at, end_at, and time_zone values exactly, wait for explicit approval, and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf(TOOL_NAME),
            allowedSkillIds = listOf("calendar-create"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第235阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_TITLE, title)
            .putString(KEY_START_AT, startAt)
            .putString(KEY_END_AT, endAt)
            .putString(KEY_TIME_ZONE, TIME_ZONE)
            .putBoolean(KEY_HAD_APP_OWNED_CALENDAR, appOwnedCalendarId() != null)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        try {
            val scenario = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                },
            )
            try {
                val imported = scenario.awaitState { current ->
                    current.prompt == sharedText && current.sharedDraftImported && !current.loadingConversationMessages
                }
                assertNull(imported.activeAgentRun)
                assertFalse(imported.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java]
                        .createAgentCalendarEventDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt.startsWith("/agent 使用 calendar.create_event") &&
                        current.prompt.contains("title：$title") &&
                        current.prompt.contains("start_at：$startAt") &&
                        current.prompt.contains("end_at：$endAt") &&
                        current.prompt.contains("time_zone：$TIME_ZONE") &&
                        !current.sharedDraftImported &&
                        !current.personalTaskMode
                }
                assertNull(converted.activeAgentRun)
                assertFalse(converted.chatMessages.any { message -> message.role == "user" })

                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].sendMessage()
                }
                val waiting = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingAgentApproval?.toolName == TOOL_NAME &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                assertEquals(TOOL_NAME, waiting.pendingAgentApproval?.toolName)

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
            assertNotNull("没有找到第235阶段真实分享日程 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val expectedArguments = mapOf(
                "title" to title,
                "start_at" to startAt,
                "end_at" to endAt,
                "time_zone" to TIME_ZONE,
            )
            val call = detail.toolLedger.calls.single()
            assertEquals(TOOL_NAME, call.toolName)
            assertEquals(expectedArguments, call.arguments)
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            val eventId = requireNotNull(result.executionReceipt?.operationId?.toLongOrNull()) {
                "日程创建回执缺少稳定 Calendar Provider 事件 ID"
            }
            state.edit().putLong(KEY_EVENT_ID, eventId).commit()
            val stableEventId = "calendar-$eventId"
            assertEquals("已创建并验证日程：$title · id=$stableEventId", result.content)

            val messageTool = MessageRepository(database)
                .loadConversation(conversationId)
                .flatMap { message -> message.parts }
                .filterIsInstance<MessagePart.Tool>()
                .single { part -> part.toolName == TOOL_NAME }
            assertTrue(messageTool.success)
            assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
            assertEquals(expectedArguments, messageTool.arguments)
            assertEquals(result.content, messageTool.result)
            assertEquals(stableEventId, messageTool.calendarEventIdForNavigation())

            val providerDetail = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
            assertTrue("当前 Calendar Provider 必须能按稳定 ID 回读日程", providerDetail is CalendarEventDetailReadResult.Success)
            providerDetail as CalendarEventDetailReadResult.Success
            assertCalendarEventMatches(providerDetail.event, title, startAt, endAt, TIME_ZONE)

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                val unchanged = runRepository.runDetail(baselineRunId)
                assertNotNull("旧 Run 在分享日程验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            println(
                "STAGE235_SHARED_CALENDAR runId=${detail.snapshot.run.id} eventId=$stableEventId " +
                    "approval=APPROVED verification=PASSED receipt=COMMITTED providerReadBack=true " +
                    "navigationIdentity=true oldRunUnchanged=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    @Test
    fun stage248NaturalLanguageReminderCompletesThroughVisibleApprovalAndDetailUi() = runBlocking {
        requireStage248RedmiRun()
        assertCalendarPermissions()
        val state = fixtureState()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        cleanupPreviousFixture(state, database, profileStore, roomState)
        restoreProviderFromRunnerArgsIfRequested()

        val providerSnapshot = ProviderRepository(context).load()
        val provider = providerSnapshot.profiles.firstOrNull { it.id == providerSnapshot.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider 配置不完整", provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank())

        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        val runRepository = RoomAgentRunRepository(context)
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val zone = ZoneId.of(TIME_ZONE)
        val start = LocalDate.now(zone).plusDays(2).atTime(LocalTime.of(10, 30)).atZone(zone).toOffsetDateTime()
        val end = start.plusMinutes(45)
        val title = "stage248_calendar_reminder_$now"
        val startAt = start.format(OFFSET_DATE_TIME_FORMATTER)
        val endAt = end.format(OFFSET_DATE_TIME_FORMATTER)
        val profileId = "stage248-calendar-reminder-$now"
        val conversationId = "conversation-stage248-calendar-reminder-$now"
        val prompt = "/agent 请创建标题为$title、开始时间$startAt、结束时间$endAt、时区${TIME_ZONE}的一次性非全天系统日程，并设置提前${STAGE248_REMINDER_MINUTES}分钟的一个提醒。"
        val profile = AgentProfileRecord(
            id = profileId,
            name = STAGE248_PROFILE_NAME,
            avatar = "248",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the user's stage248 request, select only calendar-create and call calendar.create_event exactly once. Copy title, start_at, end_at, time_zone, and reminder_minutes_before exactly from the user. reminder_minutes_before must be ${STAGE248_REMINDER_MINUTES}. Wait for visible user approval and call no other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf(TOOL_NAME),
            allowedSkillIds = listOf("calendar-create"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第248阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_TITLE, title)
            .putString(KEY_START_AT, startAt)
            .putString(KEY_END_AT, endAt)
            .putString(KEY_TIME_ZONE, TIME_ZONE)
            .putInt(KEY_REMINDER_MINUTES, STAGE248_REMINDER_MINUTES)
            .putBoolean(KEY_HAD_APP_OWNED_CALENDAR, appOwnedCalendarId() != null)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        try {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                scenario.awaitState { current -> !current.loadingConversationMessages }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(prompt)
                }
                clickVisibleNode(description = "发送", timeoutMs = 10_000L)
                val waiting = scenario.awaitState(timeoutMs = 120_000L) { current ->
                    current.pendingAgentApproval?.toolName == TOOL_NAME &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL
                }
                assertEquals(
                    STAGE248_REMINDER_MINUTES.toString(),
                    waiting.pendingAgentApproval?.arguments?.get("reminder_minutes_before"),
                )
                clickVisibleNode(text = "批准执行", alternateText = "批准并继续", timeoutMs = 15_000L)
                val completed = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED &&
                        current.pendingAgentApproval == null &&
                        current.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool -> tool.toolName == TOOL_NAME && tool.calendarEventIdForNavigation() != null }
                }
                assertEquals(AgentRunStatus.COMPLETED, completed.activeAgentRun?.run?.status)
                val navigationTool = MessageRepository(database)
                    .loadConversation(conversationId)
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Tool>()
                    .single { it.toolName == TOOL_NAME }
                assertNotNull("完成后的答案消息没有可信日程导航身份", navigationTool.calendarEventIdForNavigation())
                // long: Redmi 的 UiAutomation 会保留审批前 Compose 节点；重建页面后从 Room 重新投影同一会话，也顺带验证答案级入口能跨页面重建恢复。
                scenario.recreate()
                scenario.awaitState(timeoutMs = 15_000L) { current ->
                    !current.loadingConversationMessages &&
                        current.selectedConversationId == conversationId &&
                        current.chatMessages
                            .flatMap { message -> message.effectiveParts() }
                            .filterIsInstance<MessagePart.Tool>()
                            .any { tool -> tool.toolName == TOOL_NAME && tool.calendarEventIdForNavigation() != null }
                }
                // long: 工具结果可能比 Redmi 当前视口更高；只有安全投影已确认导航身份后才滚动会话，避免把“按钮未生成”误判为“按钮在屏幕外”。
                clickVisibleNode(description = "查看日程", timeoutMs = 15_000L, scrollForward = true)
                assertTrue("答案级日程页没有显示当前 Provider 的提醒", awaitVisibleText("提前${STAGE248_REMINDER_MINUTES}分钟", 15_000L))
                assertTrue("答案级日程页没有显示本轮标题", awaitVisibleText(title, 5_000L))
            } finally {
                scenario.close()
            }

            val detail = runRepository.recentRunDetails(30).firstOrNull { it.snapshot.run.conversationId == conversationId }
            assertNotNull("没有找到第248阶段真实提醒日程 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val expectedArguments = mapOf(
                "title" to title,
                "start_at" to startAt,
                "end_at" to endAt,
                "time_zone" to TIME_ZONE,
                "reminder_minutes_before" to STAGE248_REMINDER_MINUTES.toString(),
            )
            val call = detail.toolLedger.calls.single()
            assertEquals(TOOL_NAME, call.toolName)
            assertEquals(expectedArguments, call.arguments)
            val result = detail.toolLedger.results.single { it.toolCallId == call.id }
            assertTrue(result.success)
            assertEquals(true, result.executorVerified)
            assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
            assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
            assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single { it.toolCallId == call.id }.status)
            val eventId = requireNotNull(result.executionReceipt?.operationId?.toLongOrNull())
            state.edit().putLong(KEY_EVENT_ID, eventId).commit()
            val stableEventId = "calendar-$eventId"
            assertEquals(
                "已创建并验证日程：$title · 提醒=提前${STAGE248_REMINDER_MINUTES}分钟 · id=$stableEventId",
                result.content,
            )

            val messageTool = MessageRepository(database)
                .loadConversation(conversationId)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
                .single { it.toolName == TOOL_NAME }
            assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
            assertEquals(expectedArguments, messageTool.arguments)
            assertEquals(stableEventId, messageTool.calendarEventIdForNavigation())

            val providerDetail = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
            assertTrue(providerDetail is CalendarEventDetailReadResult.Success)
            providerDetail as CalendarEventDetailReadResult.Success
            assertCalendarEventMatches(providerDetail.event, title, startAt, endAt, TIME_ZONE)
            assertEquals(STAGE248_REMINDER_MINUTES, providerDetail.event.reminderMinutesBefore)
            assertEquals(1, providerDetail.event.reminderCount)

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
            }
            println(
                "STAGE248_CALENDAR_REMINDER runId=${detail.snapshot.run.id} eventId=$stableEventId " +
                    "approvalUiClicked=true detailUiOpened=true reminderMinutes=$STAGE248_REMINDER_MINUTES " +
                    "verification=PASSED receipt=COMMITTED providerReadBack=true navigationIdentity=true oldRunUnchanged=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第235阶段真实模型验收只在显式 stage235RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第235阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun requireStage248RedmiRun() {
        assumeTrue(
            "第248阶段真实模型验收只在显式 stage248RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_STAGE248_REAL_RUN) == "true",
        )
        assertEquals("第248阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun assertCalendarPermissions() {
        assertEquals(
            "请先授权小灵读取日历",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR),
        )
        assertEquals(
            "请先授权小灵写入日历",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR),
        )
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
        // long: 兜底凭据只通过显式 instrumentation 参数写入设备 Keystore；生产分享路径不会读取参数或记录密钥。
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
        val title = state.getString(KEY_TITLE, null)
        val startAt = state.getString(KEY_START_AT, null)
        val endAt = state.getString(KEY_END_AT, null)
        val timeZone = state.getString(KEY_TIME_ZONE, null)
        val reminderMinutes = state.getInt(KEY_REMINDER_MINUTES, -1).takeIf { it >= 0 }
        val recordedRun = state.getString(KEY_RUN_ID, null)?.let { runId ->
            RoomAgentRunRepository(context).runDetail(runId)
        }
        val fixtureRun = recordedRun ?: conversationId?.let { targetConversationId ->
            RoomAgentRunRepository(context).recentRunDetails(30).firstOrNull { detail ->
                detail.snapshot.run.conversationId == targetConversationId
            }
        }
        val eventId = state.getLong(KEY_EVENT_ID, -1L).takeIf { it > 0L }
            ?: fixtureRun?.committedCalendarEventId()
        val eventCalendarId = if (eventId != null && title != null && startAt != null && endAt != null && timeZone != null) {
            // long: 清理只接受本轮 COMMITTED 回执的稳定 ID，并在删除前核对四字段；不按标题或时间范围搜索用户日程。
            deleteFixtureEvent(eventId, title, startAt, endAt, timeZone, reminderMinutes)
        } else {
            null
        }
        if (!state.getBoolean(KEY_HAD_APP_OWNED_CALENDAR, true)) {
            appOwnedCalendarId()
                ?.takeIf { calendarId -> calendarId == eventCalendarId }
                ?.let(::deleteAppOwnedCalendar)
        }
        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { storedProfileId ->
            assertTrue(
                "拒绝删除不属于日程验收的临时 Profile",
                storedProfileId.startsWith("stage235-share-calendar-") || storedProfileId.startsWith("stage248-calendar-reminder-"),
            )
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

    private suspend fun deleteFixtureEvent(
        eventId: Long,
        title: String,
        startAt: String,
        endAt: String,
        timeZone: String,
        reminderMinutes: Int? = null,
    ): Long? {
        val current = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
        if (current is CalendarEventDetailReadResult.NotFound) return null
        assertTrue("待清理日程必须仍可按稳定 ID 回读", current is CalendarEventDetailReadResult.Success)
        current as CalendarEventDetailReadResult.Success
        assertCalendarEventMatches(current.event, title, startAt, endAt, timeZone)
        if (reminderMinutes != null) {
            assertEquals("待清理日程提醒分钟已漂移，拒绝删除", reminderMinutes, current.event.reminderMinutesBefore)
            assertEquals("待清理日程提醒数量已漂移，拒绝删除", 1, current.event.reminderCount)
        }
        val calendarId = eventCalendarId(eventId)
        assertEquals(
            "必须精确删除第235阶段创建的日程",
            1,
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            ),
        )
        assertTrue(AndroidCalendarEventReader(context.contentResolver).getEvent(eventId) is CalendarEventDetailReadResult.NotFound)
        assertTrue(eventReminders(eventId).isEmpty())
        return calendarId
    }

    private fun eventReminders(eventId: Long): List<Pair<Int, Int>> {
        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders._ID} ASC",
        ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)) to
                            it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.METHOD)),
                    )
                }
            }
        }
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
            // long: 审批会在同一 Compose Window 内替换时间线与答案内容；Redmi 的 UiAutomation 可能继续返回审批前缓存，查找前刷新才能证明点击的是当前屏幕节点。
            root?.refresh()
            val node = root?.findNode(text, alternateText, description)
            if (node != null && node.clickSelfOrAncestor()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            if (scrollForward && root?.scrollBackward() == true) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now >= nextGestureAt) {
                swipeConversationBackward()
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
            if (automation.rootInActiveWindow?.containsText(expected) == true) return true
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

    private fun AccessibilityNodeInfo.scrollBackward(): Boolean {
        if (isScrollable && performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        repeat(childCount) { index ->
            if (getChild(index)?.scrollBackward() == true) return true
        }
        return false
    }

    private fun swipeConversationBackward() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.32f
        val endY = metrics.heightPixels * 0.78f
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

    private fun assertCalendarEventMatches(
        event: CalendarEventDetailRecord,
        title: String,
        startAt: String,
        endAt: String,
        timeZone: String,
    ) {
        assertEquals(title, event.title)
        assertEquals(java.time.OffsetDateTime.parse(startAt).toInstant().toEpochMilli(), event.startAtMillis)
        assertEquals(java.time.OffsetDateTime.parse(endAt).toInstant().toEpochMilli(), event.endAtMillis)
        assertFalse(event.allDay)
        assertEquals(timeZone, event.timeZoneId)
        assertFalse(event.recurring)
    }

    private fun AgentRunDetailRecord.committedCalendarEventId(): Long? {
        val callsById = toolLedger.calls.associateBy { call -> call.id }
        return toolLedger.results.singleOrNull { result ->
            callsById[result.toolCallId]?.toolName == TOOL_NAME &&
                result.success &&
                result.executionReceipt?.status == ToolExecutionReceiptStatus.COMMITTED
        }?.executionReceipt?.operationId?.toLongOrNull()
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

    private fun appOwnedCalendarId(): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.NAME}=?",
            arrayOf(LOCAL_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_CALENDAR_NAME),
            null,
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)) else null
        }
    }

    private fun eventCalendarId(eventId: Long): Long? {
        val cursor = context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)) else null
        }
    }

    private fun deleteAppOwnedCalendar(calendarId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        assertEquals("必须清理本轮临时创建的小灵本地日历", 1, context.contentResolver.delete(uri, null, null))
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
            "Timed out waiting for Stage235 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "personalTaskMode=${latest.personalTaskMode}, sendingMessage=${latest.sendingMessage}, " +
                "approvalPresent=${latest.pendingAgentApproval != null}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val TOOL_NAME = "calendar.create_event"
        const val PROFILE_NAME = "第235阶段分享日程验收"
        const val TIME_ZONE = "Asia/Shanghai"
        const val ARG_REAL_RUN = "stage235RealRun"
        const val ARG_RESTORE_PROVIDER = "stage235RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage235FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage235FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage235FallbackModel"
        const val ARG_STAGE248_REAL_RUN = "stage248RealRun"
        const val STAGE248_PROFILE_NAME = "第248阶段日程提醒验收"
        const val STAGE248_REMINDER_MINUTES = 30
        const val STATE_PREFERENCES = "stage235_shared_text_calendar"
        const val ROOM_STATE_PREFERENCES = "xiaoling_room_state"
        const val ROOM_STATE_AGENT_PROFILE_ID = "selected_agent_profile_id"
        const val ROOM_STATE_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_TITLE = "title"
        const val KEY_START_AT = "start_at"
        const val KEY_END_AT = "end_at"
        const val KEY_TIME_ZONE = "time_zone"
        const val KEY_REMINDER_MINUTES = "reminder_minutes"
        const val KEY_RUN_ID = "run_id"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_HAD_APP_OWNED_CALENDAR = "had_app_owned_calendar"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val LOCAL_ACCOUNT_NAME = "com.longdev.xiaoling.local"
        const val LOCAL_CALENDAR_NAME = "xiaoling-local-calendar"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
        val OFFSET_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
