package com.longdev.xiaoling.agent

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CalendarEventWriteRequest(
    val idempotencyKey: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val timeZoneId: String,
)

data class CalendarEventWriteRecord(
    val eventId: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val timeZoneId: String,
    val reused: Boolean,
)

sealed interface CalendarEventWriteResult {
    data class Committed(
        val event: CalendarEventWriteRecord,
        val verified: Boolean,
    ) : CalendarEventWriteResult

    data object PermissionDenied : CalendarEventWriteResult
    data object NoWritableCalendar : CalendarEventWriteResult
    data object ProviderUnavailable : CalendarEventWriteResult
    data object Conflict : CalendarEventWriteResult
    data object Failed : CalendarEventWriteResult
}

interface CalendarEventWriter {
    suspend fun createOrReadBack(request: CalendarEventWriteRequest): CalendarEventWriteResult

    suspend fun verifyCommitted(
        eventId: String,
        request: CalendarEventWriteRequest,
    ): CalendarEventWriteResult
}

object UnavailableCalendarEventWriter : CalendarEventWriter {
    override suspend fun createOrReadBack(request: CalendarEventWriteRequest): CalendarEventWriteResult =
        CalendarEventWriteResult.ProviderUnavailable

    override suspend fun verifyCommitted(
        eventId: String,
        request: CalendarEventWriteRequest,
    ): CalendarEventWriteResult = CalendarEventWriteResult.ProviderUnavailable
}

class AndroidCalendarEventWriter(
    private val contentResolver: ContentResolver,
    private val packageName: String,
) : CalendarEventWriter {
    private val mutationMutex = Mutex()

    override suspend fun createOrReadBack(request: CalendarEventWriteRequest): CalendarEventWriteResult =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                try {
                    findByIdempotencyKey(request)?.let { existing ->
                        return@withLock if (existing.matches(request)) {
                            CalendarEventWriteResult.Committed(existing.copy(reused = true), verified = true)
                        } else {
                            CalendarEventWriteResult.Conflict
                        }
                    }
                    val calendarId = findWritableCalendarId() ?: ensureLocalCalendarId()
                        ?: return@withLock CalendarEventWriteResult.NoWritableCalendar
                    val marker = markerUri(request.idempotencyKey)
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.TITLE, request.title)
                        put(CalendarContract.Events.DTSTART, request.startAtMillis)
                        put(CalendarContract.Events.DTEND, request.endAtMillis)
                        put(CalendarContract.Events.EVENT_TIMEZONE, request.timeZoneId)
                        put(CalendarContract.Events.ALL_DAY, 0)
                        // long: Provider 没有应用侧唯一键；把 ToolCall 身份写入官方可写字段，进程中断后才能精确回读，避免按标题和时间猜测去重。
                        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
                        put(CalendarContract.Events.CUSTOM_APP_URI, marker)
                    }
                    val insertedUri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        ?: return@withLock CalendarEventWriteResult.ProviderUnavailable
                    val eventId = ContentUris.parseId(insertedUri).toString()
                    val inserted = readById(eventId)
                    CalendarEventWriteResult.Committed(
                        event = inserted ?: request.toRecord(eventId = eventId, reused = false),
                        verified = inserted?.matches(request) == true,
                    )
                } catch (_: SecurityException) {
                    CalendarEventWriteResult.PermissionDenied
                } catch (_: RuntimeException) {
                    CalendarEventWriteResult.Failed
                }
            }
        }

    override suspend fun verifyCommitted(
        eventId: String,
        request: CalendarEventWriteRequest,
    ): CalendarEventWriteResult = withContext(Dispatchers.IO) {
        try {
            val event = readById(eventId) ?: return@withContext CalendarEventWriteResult.Failed
            val markerMatches = eventId == event.eventId && markerBelongsToRequest(eventId, request.idempotencyKey)
            CalendarEventWriteResult.Committed(event.copy(reused = true), verified = markerMatches && event.matches(request))
        } catch (_: SecurityException) {
            CalendarEventWriteResult.PermissionDenied
        } catch (_: RuntimeException) {
            CalendarEventWriteResult.Failed
        }
    }

    private fun findWritableCalendarId(): Long? {
        val cursor = contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${CalendarContract.Calendars.SYNC_EVENTS}=1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.VISIBLE} DESC, ${BaseColumns._ID} ASC",
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID)) else null
        }
    }

    private fun ensureLocalCalendarId(): Long? {
        findAppOwnedLocalCalendarId()?.let { return it }
        val syncAdapterUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, LOCAL_CALENDAR_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(CalendarContract.Calendars.ALLOWED_REMINDERS, CalendarContract.Reminders.METHOD_ALERT.toString())
        }
        // long: 部分无 Google 服务的设备日历表为空；官方允许应用以 LOCAL sync-adapter 身份创建本地日历，确保经审批的事件仍有明确归属。
        val inserted = contentResolver.insert(syncAdapterUri, values) ?: return null
        return ContentUris.parseId(inserted)
    }

    private fun findAppOwnedLocalCalendarId(): Long? {
        val cursor = contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.NAME}=?",
            arrayOf(LOCAL_ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_CALENDAR_NAME),
            "${BaseColumns._ID} ASC",
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID)) else null
        }
    }

    private fun findByIdempotencyKey(request: CalendarEventWriteRequest): CalendarEventWriteRecord? {
        val marker = markerUri(request.idempotencyKey)
        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_PROJECTION,
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=?",
            arrayOf(packageName, marker),
            "${BaseColumns._ID} ASC",
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.toWriteRecord(reused = true) else null }
    }

    private fun readById(eventId: String): CalendarEventWriteRecord? {
        val id = eventId.toLongOrNull() ?: return null
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            EVENT_PROJECTION,
            null,
            null,
            null,
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.toWriteRecord(reused = false) else null }
    }

    private fun markerBelongsToRequest(eventId: String, idempotencyKey: String): Boolean {
        val id = eventId.toLongOrNull() ?: return false
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            MARKER_PROJECTION,
            null,
            null,
            null,
        ) ?: return false
        return cursor.use {
            if (!it.moveToFirst()) return@use false
            it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.CUSTOM_APP_PACKAGE)) == packageName &&
                it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.CUSTOM_APP_URI)) == markerUri(idempotencyKey)
        }
    }

    private fun android.database.Cursor.toWriteRecord(reused: Boolean): CalendarEventWriteRecord =
        CalendarEventWriteRecord(
            eventId = getLong(getColumnIndexOrThrow(BaseColumns._ID)).toString(),
            title = getString(getColumnIndexOrThrow(CalendarContract.Events.TITLE)).orEmpty(),
            startAtMillis = getLong(getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
            endAtMillis = getLong(getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
            timeZoneId = getString(getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)).orEmpty(),
            reused = reused,
        )

    private fun markerUri(idempotencyKey: String): String = Uri.Builder()
        .scheme(MARKER_SCHEME)
        .authority(MARKER_AUTHORITY)
        .appendPath(idempotencyKey)
        .build()
        .toString()

    private companion object {
        const val MARKER_SCHEME = "xiaoling"
        const val MARKER_AUTHORITY = "calendar-event"
        const val LOCAL_ACCOUNT_NAME = "com.longdev.xiaoling.local"
        const val LOCAL_CALENDAR_NAME = "xiaoling-local-calendar"
        const val LOCAL_CALENDAR_DISPLAY_NAME = "小灵"
        const val LOCAL_CALENDAR_COLOR = -12_382_984
        val CALENDAR_PROJECTION = arrayOf(
            BaseColumns._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val EVENT_PROJECTION = arrayOf(
            BaseColumns._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
        )
        val MARKER_PROJECTION = arrayOf(
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
    }
}

private fun CalendarEventWriteRecord.matches(request: CalendarEventWriteRequest): Boolean =
    title == request.title &&
        startAtMillis == request.startAtMillis &&
        endAtMillis == request.endAtMillis &&
        timeZoneId == request.timeZoneId

private fun CalendarEventWriteRequest.toRecord(eventId: String, reused: Boolean): CalendarEventWriteRecord =
    CalendarEventWriteRecord(
        eventId = eventId,
        title = title,
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        timeZoneId = timeZoneId,
        reused = reused,
    )
