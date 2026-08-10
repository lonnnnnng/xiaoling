package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class AndroidCalendarEventWriterInstrumentedTest {
    @Test
    fun redmiProviderAtomicallyCreatesReplaysAndVerifiesSingleAlertReminder() = runBlocking {
        assumeTrue("第 247 阶段日历提醒验收只允许 Redmi begonia", Build.DEVICE == "begonia")
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
            idempotencyKey = "stage247-reminder-$suffix",
            title = "__xiaoling_stage247_reminder_${suffix}__",
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.HOURS.toMillis(1),
            timeZoneId = ZoneId.systemDefault().id,
            reminderMinutesBefore = 30,
        )
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null
        var createdEventId: String? = null

        try {
            val first = writer.createOrReadBack(request)
            assertTrue("Redmi 必须原子创建并回读事件与提醒：$first", first is CalendarEventWriteResult.Committed)
            first as CalendarEventWriteResult.Committed
            createdEventId = first.event.eventId
            if (!hadAppOwnedCalendar) {
                val appCalendarId = appOwnedCalendarId(context)
                if (appCalendarId != null && eventCalendarId(context, first.event.eventId) == appCalendarId) {
                    createdAppOwnedCalendarId = appCalendarId
                }
            }

            val replay = writer.createOrReadBack(request)
            val recovered = writer.verifyCommitted(first.event.eventId, request)
            val reminders = eventReminders(context, first.event.eventId)

            assertTrue(first.verified)
            assertEquals(30, first.event.reminderMinutesBefore)
            assertEquals(1, first.event.reminderCount)
            assertEquals(listOf(30 to CalendarContract.Reminders.METHOD_ALERT), reminders)
            assertTrue(replay is CalendarEventWriteResult.Committed && replay.verified && replay.event.reused)
            assertTrue(recovered is CalendarEventWriteResult.Committed && recovered.verified && recovered.event.reused)
        } finally {
            // long: 提醒测试只按本轮 Provider 返回的事件 ID 清理；关联 reminder 由 Provider 级联删除，不能扫描用户其他日程。
            createdEventId?.toLongOrNull()?.let { eventId ->
                val deleted = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
                assertEquals("真机探针必须精确清理本次带提醒日程", 1, deleted)
                assertTrue(eventReminders(context, eventId.toString()).isEmpty())
            }
            createdAppOwnedCalendarId?.let { deleteAppOwnedCalendar(context, it) }
        }
    }

    @Test
    fun writableProviderCreatesReplaysAndVerifiesSingleDayAllDayEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权日历读写",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val suffix = System.currentTimeMillis().toString()
        val date = LocalDate.now(ZoneOffset.UTC).plusDays(4)
        val startAtMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val request = CalendarEventWriteRequest(
            idempotencyKey = "stage222-all-day-$suffix",
            title = "__xiaoling_stage222_all_day_${suffix}__",
            startAtMillis = startAtMillis,
            endAtMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            timeZoneId = "UTC",
            allDay = true,
        )
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        val reader = AndroidCalendarEventReader(context.contentResolver)
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null
        var createdEventId: String? = null

        try {
            val first = writer.createOrReadBack(request)
            assertTrue("Redmi 必须创建并回读单日全天事件：$first", first is CalendarEventWriteResult.Committed)
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
            val detail = reader.getEvent(first.event.eventId.toLong())

            assertTrue(first.verified && first.event.allDay)
            assertTrue(replay is CalendarEventWriteResult.Committed && replay.verified && replay.event.reused)
            assertTrue(verified is CalendarEventWriteResult.Committed && verified.verified)
            assertTrue(detail is CalendarEventDetailReadResult.Success)
            detail as CalendarEventDetailReadResult.Success
            assertTrue(detail.event.allDay)
            assertFalse(detail.event.recurring)
            assertEquals(startAtMillis, detail.event.startAtMillis)
            assertEquals(startAtMillis + TimeUnit.DAYS.toMillis(1), detail.event.endAtMillis)
            assertEquals("UTC", detail.event.timeZoneId)
        } finally {
            // long: 全天日程探针仍只按本次 Provider 返回的事件 ID 清理，不能按日期或标题波及用户同日安排。
            createdEventId?.toLongOrNull()?.let { eventId ->
                val deleted = context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
                assertEquals("真机探针必须精确清理本次全天日程", 1, deleted)
            }
            createdAppOwnedCalendarId?.let { deleteAppOwnedCalendar(context, it) }
        }
    }

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

    @Test
    fun conditionalDeleteRejectsDriftAndCommittedRecoveryOnlyReadsProvider() = runBlocking {
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
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        val reader = AndroidCalendarEventReader(context.contentResolver)
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null
        var firstEventId: Long? = null
        var driftEventId: Long? = null

        try {
            suspend fun create(title: String, marker: String): Long {
                val result = writer.createOrReadBack(
                    CalendarEventWriteRequest(
                        idempotencyKey = marker,
                        title = title,
                        startAtMillis = startAtMillis,
                        endAtMillis = startAtMillis + TimeUnit.HOURS.toMillis(1),
                        timeZoneId = ZoneId.systemDefault().id,
                    ),
                )
                assertTrue("Redmi 必须先创建并回读临时事件：$result", result is CalendarEventWriteResult.Committed)
                return (result as CalendarEventWriteResult.Committed).event.eventId.toLong()
            }

            firstEventId = create("__xiaoling_stage183_delete_$suffix", "stage183-delete-$suffix")
            if (!hadAppOwnedCalendar) {
                val appCalendarId = appOwnedCalendarId(context)
                if (appCalendarId != null && eventCalendarId(context, firstEventId.toString()) == appCalendarId) {
                    createdAppOwnedCalendarId = appCalendarId
                }
            }
            val firstDetail = reader.getEvent(firstEventId)
            assertTrue(firstDetail is CalendarEventDetailReadResult.Success)
            val firstEvent = (firstDetail as CalendarEventDetailReadResult.Success).event
            val deleteRequest = CalendarEventDeleteRequest(
                idempotencyKey = "tool-call-stage183-delete-$suffix",
                eventId = firstEventId,
                expectedFingerprint = CalendarEventFingerprint.create(firstEvent),
                scope = CalendarEventDeleteScope.EVENT,
            )

            val deleted = writer.deleteOrReadBack(deleteRequest)
            val recovered = writer.verifyDeleteCommitted("calendar-$firstEventId", deleteRequest)
            val repeatedWithoutReceipt = writer.deleteOrReadBack(deleteRequest)

            assertTrue(deleted is CalendarEventDeleteResult.Committed && deleted.verified)
            assertTrue(recovered is CalendarEventDeleteResult.Committed && recovered.verified)
            assertTrue(repeatedWithoutReceipt is CalendarEventDeleteResult.NotFound)
            assertTrue(reader.getEvent(firstEventId) is CalendarEventDetailReadResult.NotFound)
            firstEventId = null

            driftEventId = create("__xiaoling_stage183_drift_$suffix", "stage183-drift-$suffix")
            val driftDetail = reader.getEvent(driftEventId)
            assertTrue(driftDetail is CalendarEventDetailReadResult.Success)
            val staleEvent = (driftDetail as CalendarEventDetailReadResult.Success).event
            val updatedRows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, driftEventId),
                ContentValues().apply { put(CalendarContract.Events.TITLE, "__xiaoling_stage183_changed_$suffix") },
                null,
                null,
            )
            assertEquals(1, updatedRows)

            val rejected = writer.deleteOrReadBack(
                CalendarEventDeleteRequest(
                    idempotencyKey = "tool-call-stage183-drift-$suffix",
                    eventId = driftEventId,
                    expectedFingerprint = CalendarEventFingerprint.create(staleEvent),
                    scope = CalendarEventDeleteScope.EVENT,
                ),
            )

            assertTrue(rejected is CalendarEventDeleteResult.FingerprintMismatch)
            assertFalse(reader.getEvent(driftEventId) is CalendarEventDetailReadResult.NotFound)
        } finally {
            // long: 漂移反例必须保留目标供断言，收尾再按 Provider 返回 ID 精确删除；不按标题或时间范围清理用户日程。
            listOfNotNull(firstEventId, driftEventId).forEach { eventId ->
                context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
            }
            createdAppOwnedCalendarId?.let { deleteAppOwnedCalendar(context, it) }
        }
    }

    @Test
    fun conditionalUpdateVerifiesNewFingerprintAndCommittedRecoveryOnlyReadsProvider() = runBlocking {
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
        val writer = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
        val reader = AndroidCalendarEventReader(context.contentResolver)
        val hadAppOwnedCalendar = appOwnedCalendarId(context) != null
        var createdAppOwnedCalendarId: Long? = null
        var updatedEventId: Long? = null
        var driftEventId: Long? = null

        try {
            suspend fun create(title: String, marker: String): Long {
                val result = writer.createOrReadBack(
                    CalendarEventWriteRequest(
                        idempotencyKey = marker,
                        title = title,
                        startAtMillis = startAtMillis,
                        endAtMillis = startAtMillis + TimeUnit.HOURS.toMillis(1),
                        timeZoneId = ZoneId.systemDefault().id,
                    ),
                )
                assertTrue("Redmi 必须先创建并回读临时事件：$result", result is CalendarEventWriteResult.Committed)
                return (result as CalendarEventWriteResult.Committed).event.eventId.toLong()
            }

            updatedEventId = create("__xiaoling_stage185_update_$suffix", "stage185-update-$suffix")
            if (!hadAppOwnedCalendar) {
                val appCalendarId = appOwnedCalendarId(context)
                if (appCalendarId != null && eventCalendarId(context, updatedEventId.toString()) == appCalendarId) {
                    createdAppOwnedCalendarId = appCalendarId
                }
            }
            val originalDetail = reader.getEvent(updatedEventId)
            assertTrue(originalDetail is CalendarEventDetailReadResult.Success)
            val originalEvent = (originalDetail as CalendarEventDetailReadResult.Success).event
            val updatedStart = startAtMillis + TimeUnit.HOURS.toMillis(3)
            val updateRequest = CalendarEventUpdateRequest(
                idempotencyKey = "tool-call-stage185-update-$suffix",
                eventId = updatedEventId,
                expectedFingerprint = CalendarEventFingerprint.create(originalEvent),
                scope = CalendarEventUpdateScope.EVENT,
                title = "__xiaoling_stage185_updated_$suffix",
                startAtMillis = updatedStart,
                endAtMillis = updatedStart + TimeUnit.HOURS.toMillis(2),
                timeZoneId = "UTC",
            )

            val updated = writer.updateOrReadBack(updateRequest)
            // long: 重新创建 Provider 写入器模拟进程/Registry 重建；恢复只能按稳定事件 ID 只读回查，不能依赖旧实例内存或再次 UPDATE。
            val restartedWriter = AndroidCalendarEventWriter(context.contentResolver, context.packageName)
            val recovered = restartedWriter.verifyUpdateCommitted("calendar-$updatedEventId", updateRequest)
            val repeatedWithoutReceipt = restartedWriter.updateOrReadBack(updateRequest)
            val detailAfterUpdate = reader.getEvent(updatedEventId)

            assertTrue(updated is CalendarEventUpdateResult.Committed && updated.verified)
            assertTrue(recovered is CalendarEventUpdateResult.Committed && recovered.verified)
            assertTrue(repeatedWithoutReceipt is CalendarEventUpdateResult.FingerprintMismatch)
            assertTrue(detailAfterUpdate is CalendarEventDetailReadResult.Success)
            val current = (detailAfterUpdate as CalendarEventDetailReadResult.Success).event
            assertEquals(updateRequest.title, current.title)
            assertEquals(updateRequest.startAtMillis, current.startAtMillis)
            assertEquals(updateRequest.endAtMillis, current.endAtMillis)
            assertEquals(updateRequest.timeZoneId, current.timeZoneId)
            assertEquals(
                CalendarEventFingerprint.create(current),
                (updated as CalendarEventUpdateResult.Committed).update.fingerprint,
            )

            driftEventId = create("__xiaoling_stage185_drift_$suffix", "stage185-drift-$suffix")
            val driftDetail = reader.getEvent(driftEventId)
            assertTrue(driftDetail is CalendarEventDetailReadResult.Success)
            val staleEvent = (driftDetail as CalendarEventDetailReadResult.Success).event
            val changedRows = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, driftEventId),
                ContentValues().apply { put(CalendarContract.Events.TITLE, "__xiaoling_stage185_external_$suffix") },
                null,
                null,
            )
            assertEquals(1, changedRows)

            val rejected = writer.updateOrReadBack(
                CalendarEventUpdateRequest(
                    idempotencyKey = "tool-call-stage185-drift-$suffix",
                    eventId = driftEventId,
                    expectedFingerprint = CalendarEventFingerprint.create(staleEvent),
                    scope = CalendarEventUpdateScope.EVENT,
                    title = "__xiaoling_stage185_should_not_apply_$suffix",
                    startAtMillis = updatedStart,
                    endAtMillis = updatedStart + TimeUnit.HOURS.toMillis(1),
                    timeZoneId = "UTC",
                ),
            )

            assertTrue(rejected is CalendarEventUpdateResult.FingerprintMismatch)
            val driftCurrent = reader.getEvent(driftEventId)
            assertTrue(driftCurrent is CalendarEventDetailReadResult.Success)
            assertEquals(
                "__xiaoling_stage185_external_$suffix",
                (driftCurrent as CalendarEventDetailReadResult.Success).event.title,
            )
        } finally {
            // long: 修改成功与漂移拒绝都会保留事件；收尾只按本轮 Provider ID 精确删除，不扫描标题或用户日程。
            listOfNotNull(updatedEventId, driftEventId).forEach { eventId ->
                context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null,
                )
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

    private fun eventReminders(context: android.content.Context, eventId: String): List<Pair<Int, Int>> {
        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId),
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
