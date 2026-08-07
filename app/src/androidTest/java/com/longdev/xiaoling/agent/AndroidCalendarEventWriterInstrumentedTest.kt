package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class AndroidCalendarEventWriterInstrumentedTest {
    @Test
    fun writableProviderCreatesReplaysVerifiesAndCleansExactEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权日历读写",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val suffix = System.currentTimeMillis().toString()
        val startAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        val request = CalendarEventWriteRequest(
            idempotencyKey = "stage175-$suffix",
            title = "__xiaoling_stage175_${suffix}__",
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.HOURS.toMillis(1),
            timeZoneId = ZoneId.systemDefault().id,
        )
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        var createdEventId: String? = null
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null

        try {
            val first = writer.createOrReadBack(request)
            assertTrue(
                "Redmi 必须存在可写日历并完成回读：$first；安全字段=${describeCalendarCapabilities(context)}",
                first is CalendarEventWriteResult.Committed,
            )
            first as CalendarEventWriteResult.Committed
            createdEventId = first.event.eventId
            if (!hadAppOwnedCalendar) {
                val appCalendarId = appOwnedCalendarId(context)
                if (appCalendarId != null && eventCalendarId(context, first.event.eventId) == appCalendarId) {
                    createdAppOwnedCalendarId = appCalendarId
                }
            }
            val replay = writer.createOrReadBack(request)
            val verified = writer.verifyCommitted(first.event.eventId, request)

            assertTrue(first.verified)
            assertTrue(replay is CalendarEventWriteResult.Committed && replay.verified && replay.event.reused)
            assertTrue(verified is CalendarEventWriteResult.Committed && verified.verified)
            assertEquals(first.event.eventId, (replay as CalendarEventWriteResult.Committed).event.eventId)
        } finally {
            // long: 真机探针只删除本次 Provider 返回的精确事件 ID；不按标题批量清理，避免触碰用户已有日程。
            createdEventId?.toLongOrNull()?.let { eventId ->
                val deleted = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
                assertEquals("真机探针必须精确清理本次创建的日程", 1, deleted)
            }
            createdAppOwnedCalendarId?.let { deleteAppOwnedCalendar(context, it) }
        }
    }

    @Test
    fun stableProviderEventIdLinksListSearchAndAuthoritativeDetail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权日历读写",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val suffix = System.currentTimeMillis().toString()
        val startAtMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)
        val request = CalendarEventWriteRequest(
            idempotencyKey = "stage181-$suffix",
            title = "__xiaoling_stage181_${suffix}__",
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.HOURS.toMillis(1),
            timeZoneId = ZoneId.systemDefault().id,
        )
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        val reader = AndroidCalendarEventReader(context.contentResolver)
        var createdEventId: String? = null
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null

        try {
            val created = writer.createOrReadBack(request)
            assertTrue("Redmi 必须先创建并回读临时事件：$created", created is CalendarEventWriteResult.Committed)
            created as CalendarEventWriteResult.Committed
            createdEventId = created.event.eventId
            val numericEventId = created.event.eventId.toLong()
            if (!hadAppOwnedCalendar) {
                val appCalendarId = appOwnedCalendarId(context)
                if (appCalendarId != null && eventCalendarId(context, created.event.eventId) == appCalendarId) {
                    createdAppOwnedCalendarId = appCalendarId
                }
            }

            val listResult = reader.listEvents(
                startAtMillis = System.currentTimeMillis(),
                endAtMillis = startAtMillis + TimeUnit.DAYS.toMillis(1),
                limit = 20,
            )
            val searchResult = reader.searchEvents(
                startAtMillis = System.currentTimeMillis(),
                endAtMillis = startAtMillis + TimeUnit.DAYS.toMillis(1),
                query = request.title,
                limit = 10,
            )
            val detailResult = reader.getEvent(numericEventId)

            assertTrue(listResult is CalendarEventReadResult.Success)
            assertTrue((listResult as CalendarEventReadResult.Success).events.any { it.eventId == numericEventId })
            assertTrue(searchResult is CalendarEventReadResult.Success)
            assertEquals(listOf(numericEventId), (searchResult as CalendarEventReadResult.Success).events.map { it.eventId })
            assertTrue(detailResult is CalendarEventDetailReadResult.Success)
            val detail = (detailResult as CalendarEventDetailReadResult.Success).event
            // long: 真实 Provider 验收只比较详情契约中的最小字段；测试从类型层面无法访问地点、描述、参与人、组织者或账户。
            assertEquals(numericEventId, detail.eventId)
            assertEquals(request.title, detail.title)
            assertEquals(request.startAtMillis, detail.startAtMillis)
            assertEquals(request.endAtMillis, detail.endAtMillis)
            assertEquals(request.timeZoneId, detail.timeZoneId)
            assertTrue(!detail.allDay)
            assertTrue(!detail.recurring)
        } finally {
            // long: 第181阶段探针仍只删除本轮 Provider 返回的精确事件 ID，不按标题或时间范围清理用户日程。
            createdEventId?.toLongOrNull()?.let { eventId ->
                val deleted = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
                assertEquals("真机探针必须精确清理本次创建的日程", 1, deleted)
            }
            createdAppOwnedCalendarId?.let { deleteAppOwnedCalendar(context, it) }
        }
    }

    private fun appOwnedCalendarId(context: android.content.Context): Long? {
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

    private fun eventCalendarId(context: android.content.Context, eventId: String): Long? {
        val id = eventId.toLongOrNull() ?: return null
        val cursor = context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.CALENDAR_ID),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)) else null
        }
    }

    private fun deleteAppOwnedCalendar(context: android.content.Context, calendarId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId).buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val deleted = context.contentResolver.delete(uri, null, null)
        assertEquals("真机探针必须清理本轮临时创建的小灵本地日历", 1, deleted)
    }

    private fun describeCalendarCapabilities(context: android.content.Context): String {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars._ID} ASC",
        ) ?: return "provider-unavailable"
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(projection.joinToString(prefix = "{", postfix = "}") { column ->
                        "$column=${it.getString(it.getColumnIndexOrThrow(column))}"
                    })
                }
            }.joinToString(prefix = "[", postfix = "]")
        }
    }

    private companion object {
        const val LOCAL_ACCOUNT_NAME = "com.longdev.xiaoling.local"
        const val LOCAL_CALENDAR_NAME = "xiaoling-local-calendar"
    }
}
