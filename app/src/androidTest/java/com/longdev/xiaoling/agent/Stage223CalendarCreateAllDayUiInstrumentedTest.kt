package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
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
 * long: 第 223 阶段把临时会话准备、真实前台人工审批、权威 Provider 审计和精确清理拆开，
 * 保证测试代码不会代替模型规划、用户审批点击或答案级页面查看。
 */
@RunWith(AndroidJUnit4::class)
class Stage223CalendarCreateAllDayUiInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareMinimalProfileAndDedicatedConversation() = runBlocking {
        requireManualRedmiRun()
        assertCalendarPermissions()
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }

        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue("Redmi 当前 Provider Base URL 为空", provider.baseUrl.isNotBlank())
        assertTrue("Redmi 当前 Provider API Key 为空", provider.apiKey.isNotBlank())
        assertTrue("Redmi 当前 Provider 模型为空", provider.model.isNotBlank())

        cleanupPreviousFixture(state, database, profileStore, roomState)
        val originalProfileId = roomState.selectedAgentProfileId()
            ?.takeIf { selected -> !selected.startsWith("$PROFILE_ID_PREFIX-") }
            ?: profileStore.list().firstOrNull { profile -> !profile.id.startsWith("$PROFILE_ID_PREFIX-") }?.id
        val originalConversationId = roomState.selectedConversationId()
        val baselineRun = runRepository.recentRunDetails(1).firstOrNull()
        val now = System.currentTimeMillis()
        val profileId = "$PROFILE_ID_PREFIX-$now"
        val title = "stage223_all_day_$now"
        val date = LocalDate.now(ZoneOffset.UTC).plusDays(6).toString()
        val conversationId = "conversation-stage223-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = PROFILE_NAME,
            avatar = "223",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For an explicit single-day all-day calendar request, call calendar.create_all_day_event exactly once with the exact title and canonical yyyy-MM-dd date. Wait for user approval and do not call any other tool.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("calendar.create_all_day_event"),
            allowedSkillIds = listOf("calendar-create-all-day"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )

        profileStore.upsert(profile)
        assertTrue("无法选择第 223 阶段最小 Profile", profileStore.select(profileId))
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
            .clear()
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

        assertEquals(profileId, roomState.selectedAgentProfileId())
        assertEquals(conversationId, roomState.selectedConversationId())
        println(
            "STAGE223_PREPARED title=$title date=$date conversationId=$conversationId " +
                "profile=$profileId providerReady=true baselineRun=${baselineRun?.snapshot?.run?.id ?: "none"}",
        )
    }

    @Test
    fun auditCompletedRunLedgerNavigationAndProviderDetail() = runBlocking {
        requireManualRedmiRun()
        assertCalendarPermissions()
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val title = requireNotNull(state.getString(KEY_TITLE, null)) { "缺少第 223 阶段标题" }
        val date = requireNotNull(state.getString(KEY_DATE, null)) { "缺少第 223 阶段日期" }
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第 223 阶段会话" }
        val profileId = requireNotNull(state.getString(KEY_PROFILE_ID, null)) { "缺少第 223 阶段 Profile" }
        val database = XiaoLingDatabase.getInstance(context)
        val repository = RoomAgentRunRepository(context)
        val detail = repository.recentRunDetails(30).firstOrNull { candidate ->
            candidate.toolLedger.calls.any { call ->
                call.toolName == TOOL_NAME &&
                    call.arguments == mapOf("title" to title, "date" to date)
            }
        }

        assertNotNull("未找到第 223 阶段真实前台 Run", detail)
        requireNotNull(detail)
        assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
        assertEquals(conversationId, detail.snapshot.run.conversationId)
        val selectedProfile = detail.snapshot.events
            .singleOrNull { it.type == AgentEventTypes.PROFILE_SELECTED }
            ?.metadata
            .let { it as? RunEventMetadata.AgentProfileSelection }
            ?.profile
        assertEquals(profileId, selectedProfile?.id)
        assertEquals(listOf("calendar-create-all-day"), selectedProfile?.allowedSkillIds)

        val call = detail.toolLedger.calls.single()
        assertEquals(TOOL_NAME, call.toolName)
        assertEquals(mapOf("title" to title, "date" to date), call.arguments)
        val result = detail.toolLedger.results.single()
        assertEquals(call.id, result.toolCallId)
        assertTrue(result.success)
        assertEquals(true, result.executorVerified)
        assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
        val eventId = requireNotNull(result.executionReceipt?.operationId?.toLongOrNull()) {
            "全天日程结果缺少稳定 Provider 事件 ID"
        }
        val stableId = "calendar-$eventId"
        assertEquals(
            "已创建并验证全天日程：$title · 日期=$date · id=$stableId",
            result.content,
        )
        assertEquals(
            ApprovalRequestStatus.APPROVED,
            detail.approvals.single { it.toolName == TOOL_NAME }.status,
        )

        val messageTool = MessageRepository(database)
            .loadConversation(conversationId)
            .flatMap { message -> message.parts }
            .filterIsInstance<MessagePart.Tool>()
            .single { part -> part.toolName == TOOL_NAME }
        assertTrue(messageTool.success)
        assertEquals(MessageToolVerificationStatus.VERIFIED, messageTool.verificationStatus)
        assertEquals(call.arguments, messageTool.arguments)
        assertEquals(result.content, messageTool.result)
        assertEquals(stableId, messageTool.calendarEventIdForNavigation())

        val providerDetail = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
        assertTrue("当前 Calendar Provider 必须能按稳定 ID 回读全天日程", providerDetail is CalendarEventDetailReadResult.Success)
        providerDetail as CalendarEventDetailReadResult.Success
        val startAtMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(title, providerDetail.event.title)
        assertEquals(startAtMillis, providerDetail.event.startAtMillis)
        assertEquals(LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), providerDetail.event.endAtMillis)
        assertTrue(providerDetail.event.allDay)
        assertEquals("UTC", providerDetail.event.timeZoneId)
        assertFalse(providerDetail.event.recurring)

        val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
        val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
        if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
            val currentBaseline = repository.runDetail(baselineRunId)
            assertNotNull("第 223 阶段执行后旧 Run 不应消失", currentBaseline)
            // long: 对旧 Run 的完整详情做摘要比较，覆盖 Step、Approval、Event 和 Tool Ledger，避免只看终态漏掉历史被改写。
            assertEquals("第 223 阶段不得改写旧 Run", baselineDigest, requireNotNull(currentBaseline).stableDigest())
        }

        state.edit()
            .putString(KEY_RUN_ID, detail.snapshot.run.id)
            .putLong(KEY_EVENT_ID, eventId)
            .commit()
        println(
            "STAGE223_AUDITED runId=${detail.snapshot.run.id} conversationId=$conversationId toolCallId=${call.id} " +
                "eventId=$stableId status=COMPLETED approval=APPROVED verification=PASSED receipt=COMMITTED " +
                "navigationBound=true providerCurrent=true oldRunUnchanged=true",
        )
    }

    @Test
    fun cleanupExactEventConversationAndProfilePreservingRun() = runBlocking {
        requireManualRedmiRun()
        val state = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        val runId = requireNotNull(state.getString(KEY_RUN_ID, null)) { "缺少第 223 阶段 Run ID" }
        val eventId = state.getLong(KEY_EVENT_ID, -1L).takeIf { it > 0L }
            ?: error("缺少第 223 阶段事件 ID")
        val conversationId = requireNotNull(state.getString(KEY_CONVERSATION_ID, null)) { "缺少第 223 阶段会话" }
        val profileId = requireNotNull(state.getString(KEY_PROFILE_ID, null)) { "缺少第 223 阶段 Profile" }
        val title = requireNotNull(state.getString(KEY_TITLE, null)) { "缺少第 223 阶段标题" }
        val date = requireNotNull(state.getString(KEY_DATE, null)) { "缺少第 223 阶段日期" }
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        val hadAppOwnedCalendar = state.getBoolean(KEY_HAD_APP_OWNED_CALENDAR, true)
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        // long: 即使持久化 ID 陈旧，也必须先核对本轮随机标题和全天日期边界；身份漂移时宁可保留夹具并失败，也不能误删用户日程。
        val eventCalendarId = deleteFixtureEvent(eventId, title, date, requireExisting = true)
        if (!hadAppOwnedCalendar) {
            appOwnedCalendarId()
                ?.takeIf { calendarId -> calendarId == eventCalendarId }
                ?.let(::deleteAppOwnedCalendar)
        }

        originalProfileId?.let { assertTrue("恢复原 Agent Profile 失败", profileStore.select(it)) }
        deleteFixtureProfile(profileStore, profileId)
        database.withTransaction {
            MessageRepository(database).deleteByConversationIds(listOf(conversationId))
            database.conversationDao().deleteConversations(listOf(conversationId))
        }
        originalConversationId?.let(roomState::saveSelectedConversationId)
        persistSelectedState(originalProfileId, originalConversationId)

        assertTrue(AndroidCalendarEventReader(context.contentResolver).getEvent(eventId) is CalendarEventDetailReadResult.NotFound)
        assertTrue(profileStore.list().none { it.id == profileId })
        assertNull(database.conversationDao().getConversation(conversationId))
        assertNotNull("真实 Run、Approval 与 Tool Ledger 审计必须保留", RoomAgentRunRepository(context).runDetail(runId))
        state.edit().clear().commit()
        println(
            "STAGE223_CLEANUP runId=$runId runPreserved=true eventId=calendar-$eventId exactEventRemoved=true " +
                "temporaryProfileRemoved=true conversationRemoved=true originalProfileRestored=${originalProfileId != null}",
        )
    }

    private fun assertCalendarPermissions() {
        assertEquals(
            "请先在小灵的“日历访问”页面授权读取日历",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR),
        )
        assertEquals(
            "请先在小灵的“日历访问”页面授权写入日历",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR),
        )
    }

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        roomState: RoomStateStore,
    ) {
        val previousConversationId = state.getString(KEY_CONVERSATION_ID, null)
        val previousEventId = state.getLong(KEY_EVENT_ID, -1L).takeIf { it > 0L }
        val previousTitle = state.getString(KEY_TITLE, null)
        val previousDate = state.getString(KEY_DATE, null)
        val previousProfileId = state.getString(KEY_PROFILE_ID, null)
        val previousOriginalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val previousOriginalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        if (previousEventId != null && previousTitle != null && previousDate != null) {
            deleteFixtureEvent(previousEventId, previousTitle, previousDate, requireExisting = false)
        }
        previousOriginalProfileId?.let { profileStore.select(it) }
        previousProfileId?.let { deleteFixtureProfile(profileStore, it) }
        if (!previousConversationId.isNullOrBlank()) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(previousConversationId))
                database.conversationDao().deleteConversations(listOf(previousConversationId))
            }
        }
        previousOriginalConversationId?.let(roomState::saveSelectedConversationId)
        persistSelectedState(previousOriginalProfileId, previousOriginalConversationId)
        state.edit().clear().commit()
    }

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        // long: 分段 instrumentation 结束后进程会立即退出；同步刷盘保证下一次主应用冷启动读取到验收 Profile/会话，而不是 apply 尚未落盘的旧选择。
        context.getSharedPreferences(ROOM_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (profileId == null) remove(ROOM_STATE_AGENT_PROFILE_ID) else putString(ROOM_STATE_AGENT_PROFILE_ID, profileId)
                if (conversationId == null) remove(ROOM_STATE_CONVERSATION_ID) else putString(ROOM_STATE_CONVERSATION_ID, conversationId)
            }
            .commit()
    }

    private fun requireManualRedmiRun() {
        val manualRun = InstrumentationRegistry.getArguments().getString(ARG_MANUAL_RUN) == "true"
        assumeTrue("第 223 阶段夹具只允许通过显式 stage223Manual=true 分段运行", manualRun)
        assertEquals("第 223 阶段 Android 验收只允许 Redmi Note 8 Pro", EXPECTED_DEVICE, Build.DEVICE)
    }

    private suspend fun deleteFixtureEvent(
        eventId: Long,
        title: String,
        date: String,
        requireExisting: Boolean,
    ): Long? {
        val current = AndroidCalendarEventReader(context.contentResolver).getEvent(eventId)
        if (current is CalendarEventDetailReadResult.NotFound && !requireExisting) return null
        assertTrue("待清理日程必须仍由当前 Provider 按稳定 ID 回读", current is CalendarEventDetailReadResult.Success)
        current as CalendarEventDetailReadResult.Success
        val startAtMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("待清理日程标题已漂移，拒绝删除", title, current.event.title)
        assertEquals("待清理日程开始日期已漂移，拒绝删除", startAtMillis, current.event.startAtMillis)
        assertEquals(
            "待清理日程结束日期已漂移，拒绝删除",
            LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            current.event.endAtMillis,
        )
        assertTrue("待清理日程已不再是全天事件，拒绝删除", current.event.allDay)
        assertEquals("待清理日程时区已漂移，拒绝删除", "UTC", current.event.timeZoneId)
        assertFalse("待清理日程已变成重复事件，拒绝删除", current.event.recurring)
        val calendarId = eventCalendarId(eventId)
        assertEquals(
            "必须精确删除第 223 阶段创建的全天日程",
            1,
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            ),
        )
        return calendarId
    }

    private suspend fun deleteFixtureProfile(profileStore: RoomAgentProfileStore, profileId: String) {
        assertTrue("拒绝删除不属于第 223 阶段夹具的 Profile", profileId.startsWith("$PROFILE_ID_PREFIX-"))
        val profile = profileStore.list().singleOrNull { it.id == profileId } ?: return
        assertEquals("拒绝删除名称漂移的第 223 阶段 Profile", PROFILE_NAME, profile.name)
        profileStore.delete(profileId)
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

    private fun AgentRunDetailRecord.stableDigest(): String {
        val bytes = toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PROFILE_ID_PREFIX = "stage223-calendar-all-day-profile"
        const val PROFILE_NAME = "第223阶段全天日程验收"
        const val EXPECTED_DEVICE = "begonia"
        const val ARG_MANUAL_RUN = "stage223Manual"
        const val TOOL_NAME = "calendar.create_all_day_event"
        const val STATE_PREFERENCES = "stage223_calendar_all_day_ui"
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
    }
}
