package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
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
import java.time.ZoneOffset
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
 * long: 第 236 阶段验证外部分享只把明确标题和唯一日期变成全天日程草稿；发送、逐次审批、UTC Provider 回读和精确清理继续沿用生产链。
 */
@RunWith(AndroidJUnit4::class)
class Stage236SharedTextAllDayCalendarEventInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun incompleteAllDaySharedTextCannotCreateDraftOrStartAgentRun() = runBlocking {
        requireManualRedmiRun()
        val repository = RoomAgentRunRepository(context)
        val baseline = repository.recentRunDetails(1).firstOrNull()
        val sharedText = "标题：stage236_missing_date_${System.nanoTime()}"
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
                viewModel.createAgentAllDayCalendarEventDraftFromSharedText()
                // long: Activity 会快速消费一次性配置提示；同步读取拒绝后的状态，验证模型调用和 Run 创建尚未发生。
                rejected = viewModel.uiState
            }
            assertEquals(sharedText, rejected.prompt)
            assertEquals(false, rejected.result?.success)
            assertTrue(rejected.result?.message?.contains("yyyy-MM-dd") == true)
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
        println("STAGE236_MISSING_FIELD rejectedBeforeSend=true runCreated=false oldRunUnchanged=true")
    }

    @Test
    fun sharedTextAllDayCalendarCompletesWithApprovalProviderReadBackAndNavigationIdentity() = runBlocking {
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
        val title = "stage236_share_all_day_$now"
        val date = LocalDate.now(ZoneOffset.UTC).plusDays(3).toString()
        val sharedText = """
            标题：$title
            日期：$date
        """.trimIndent()
        val profileId = "stage236-share-all-day-$now"
        val conversationId = "conversation-stage236-share-all-day-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = PROFILE_NAME,
            avatar = "236",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For any user request beginning with '/agent 使用 calendar.create_all_day_event', select only the calendar-create-all-day Skill and call calendar.create_all_day_event exactly once. Use the supplied title and canonical date values exactly, wait for explicit approval, and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf(TOOL_NAME),
            allowedSkillIds = listOf("calendar-create-all-day"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第236阶段临时 Profile", profileStore.select(profileId))
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
            .putString(KEY_DATE, date)
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
                        .createAgentAllDayCalendarEventDraftFromSharedText()
                }
                val converted = scenario.awaitState { current ->
                    current.prompt.startsWith("/agent 使用 calendar.create_all_day_event") &&
                        current.prompt.contains("title：$title") &&
                        current.prompt.contains("date：$date") &&
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
            assertNotNull("没有找到第236阶段真实分享全天日程 Run", detail)
            requireNotNull(detail)
            state.edit().putString(KEY_RUN_ID, detail.snapshot.run.id).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val expectedArguments = mapOf(
                "title" to title,
                "date" to date,
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
                "全天日程创建回执缺少稳定 Calendar Provider 事件 ID"
            }
            state.edit().putLong(KEY_EVENT_ID, eventId).commit()
            val stableEventId = "calendar-$eventId"
            assertEquals("已创建并验证全天日程：$title · 日期=$date · id=$stableEventId", result.content)

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
            assertTrue("当前 Calendar Provider 必须能按稳定 ID 回读全天日程", providerDetail is CalendarEventDetailReadResult.Success)
            providerDetail as CalendarEventDetailReadResult.Success
            assertAllDayCalendarEventMatches(providerDetail.event, title, date)

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                val unchanged = runRepository.runDetail(baselineRunId)
                assertNotNull("旧 Run 在分享全天日程验收后丢失", unchanged)
                assertEquals(baselineDigest, requireNotNull(unchanged).stableDigest())
            }
            println(
                "STAGE236_SHARED_ALL_DAY runId=${detail.snapshot.run.id} eventId=$stableEventId " +
                    "approval=APPROVED verification=PASSED receipt=COMMITTED providerReadBack=true " +
                    "navigationIdentity=true oldRunUnchanged=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
        }
    }

    private fun requireManualRedmiRun() {
        assumeTrue(
            "第236阶段真实模型验收只在显式 stage236RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第236阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
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
        val date = state.getString(KEY_DATE, null)
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
        val eventCalendarId = if (eventId != null && title != null && date != null) {
            // long: 清理只接受本轮 COMMITTED 回执的稳定 ID，并在删除前核对标题与 UTC 全天边界；不按标题或日期搜索用户日程。
            deleteFixtureEvent(eventId, title, date)
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
            assertTrue("拒绝删除不属于第236阶段的临时 Profile", storedProfileId.startsWith("stage236-share-all-day-"))
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
        date: String,
    ): Long? {
        val current = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
        if (current is CalendarEventDetailReadResult.NotFound) return null
        assertTrue("待清理日程必须仍可按稳定 ID 回读", current is CalendarEventDetailReadResult.Success)
        current as CalendarEventDetailReadResult.Success
        assertAllDayCalendarEventMatches(current.event, title, date)
        val calendarId = eventCalendarId(eventId)
        assertEquals(
            "必须精确删除第236阶段创建的日程",
            1,
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            ),
        )
        assertTrue(AndroidCalendarEventReader(context.contentResolver).getEvent(eventId) is CalendarEventDetailReadResult.NotFound)
        return calendarId
    }

    private fun assertAllDayCalendarEventMatches(
        event: CalendarEventDetailRecord,
        title: String,
        date: String,
    ) {
        val startAtMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(title, event.title)
        assertEquals(startAtMillis, event.startAtMillis)
        assertEquals(LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), event.endAtMillis)
        assertTrue(event.allDay)
        assertEquals("UTC", event.timeZoneId)
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
            "Timed out waiting for Stage236 state: " +
                "promptLength=${latest.prompt.length}, sharedDraftImported=${latest.sharedDraftImported}, " +
                "personalTaskMode=${latest.personalTaskMode}, sendingMessage=${latest.sendingMessage}, " +
                "approvalPresent=${latest.pendingAgentApproval != null}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private companion object {
        const val TOOL_NAME = "calendar.create_all_day_event"
        const val PROFILE_NAME = "第236阶段分享全天日程验收"
        const val ARG_REAL_RUN = "stage236RealRun"
        const val ARG_RESTORE_PROVIDER = "stage236RestoreProvider"
        const val ARG_FALLBACK_BASE_URL = "stage236FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage236FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage236FallbackModel"
        const val STATE_PREFERENCES = "stage236_shared_text_all_day_calendar"
        const val ROOM_STATE_PREFERENCES = "xiaoling_room_state"
        const val ROOM_STATE_AGENT_PROFILE_ID = "selected_agent_profile_id"
        const val ROOM_STATE_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_TITLE = "title"
        const val KEY_DATE = "date"
        const val KEY_RUN_ID = "run_id"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_HAD_APP_OWNED_CALENDAR = "had_app_owned_calendar"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val LOCAL_ACCOUNT_NAME = "com.longdev.xiaoling.local"
        const val LOCAL_CALENDAR_NAME = "xiaoling-local-calendar"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
