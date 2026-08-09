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
    val allDay: Boolean = false,
)

data class CalendarEventWriteRecord(
    val eventId: String,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val timeZoneId: String,
    val allDay: Boolean,
    val reused: Boolean,
)

enum class CalendarEventDeleteScope(val wireName: String) {
    EVENT("event"),
    SERIES("series"),
    OCCURRENCE("occurrence"),
    ;

    companion object {
        fun fromWireName(value: String): CalendarEventDeleteScope? = entries.firstOrNull { it.wireName == value }
    }
}

enum class CalendarEventUpdateScope(val wireName: String) {
    EVENT("event"),
    SERIES("series"),
    OCCURRENCE("occurrence"),
    ;

    companion object {
        fun fromWireName(value: String): CalendarEventUpdateScope? = entries.firstOrNull { it.wireName == value }
    }
}

data class CalendarEventUpdateRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val expectedFingerprint: String,
    val scope: CalendarEventUpdateScope,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val timeZoneId: String,
)

data class CalendarEventUpdateRecord(
    val eventId: Long,
    val scope: CalendarEventUpdateScope,
    val fingerprint: String,
    val reused: Boolean,
)

sealed interface CalendarEventUpdateResult {
    data class Committed(
        val update: CalendarEventUpdateRecord,
        val verified: Boolean,
    ) : CalendarEventUpdateResult

    data object NotFound : CalendarEventUpdateResult
    data object ScopeMismatch : CalendarEventUpdateResult
    data object SeriesUnsupported : CalendarEventUpdateResult
    data object OccurrenceUnsupported : CalendarEventUpdateResult
    data object AllDayUnsupported : CalendarEventUpdateResult
    data object FingerprintMismatch : CalendarEventUpdateResult
    data object NoChanges : CalendarEventUpdateResult
    data object PermissionDenied : CalendarEventUpdateResult
    data object ProviderUnavailable : CalendarEventUpdateResult
    data object Failed : CalendarEventUpdateResult
}

data class CalendarEventDeleteRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val expectedFingerprint: String,
    val scope: CalendarEventDeleteScope,
)

data class CalendarEventDeleteRecord(
    val eventId: Long,
    val scope: CalendarEventDeleteScope,
    val reused: Boolean,
)

sealed interface CalendarEventDeleteResult {
    data class Committed(
        val deletion: CalendarEventDeleteRecord,
        val verified: Boolean,
    ) : CalendarEventDeleteResult

    data object NotFound : CalendarEventDeleteResult
    data object ScopeMismatch : CalendarEventDeleteResult
    data object OccurrenceUnsupported : CalendarEventDeleteResult
    data object FingerprintMismatch : CalendarEventDeleteResult
    data object PermissionDenied : CalendarEventDeleteResult
    data object ProviderUnavailable : CalendarEventDeleteResult
    data object Failed : CalendarEventDeleteResult
}

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

    suspend fun updateOrReadBack(request: CalendarEventUpdateRequest): CalendarEventUpdateResult =
        CalendarEventUpdateResult.ProviderUnavailable

    suspend fun verifyUpdateCommitted(
        eventId: String,
        request: CalendarEventUpdateRequest,
    ): CalendarEventUpdateResult = CalendarEventUpdateResult.ProviderUnavailable

    suspend fun deleteOrReadBack(request: CalendarEventDeleteRequest): CalendarEventDeleteResult =
        CalendarEventDeleteResult.ProviderUnavailable

    suspend fun verifyDeleteCommitted(
        eventId: String,
        request: CalendarEventDeleteRequest,
    ): CalendarEventDeleteResult = CalendarEventDeleteResult.ProviderUnavailable
}

object UnavailableCalendarEventWriter : CalendarEventWriter {
    override suspend fun createOrReadBack(request: CalendarEventWriteRequest): CalendarEventWriteResult =
        CalendarEventWriteResult.ProviderUnavailable

    override suspend fun verifyCommitted(
        eventId: String,
        request: CalendarEventWriteRequest,
    ): CalendarEventWriteResult = CalendarEventWriteResult.ProviderUnavailable

    override suspend fun updateOrReadBack(request: CalendarEventUpdateRequest): CalendarEventUpdateResult =
        CalendarEventUpdateResult.ProviderUnavailable

    override suspend fun verifyUpdateCommitted(
        eventId: String,
        request: CalendarEventUpdateRequest,
    ): CalendarEventUpdateResult = CalendarEventUpdateResult.ProviderUnavailable

    override suspend fun deleteOrReadBack(request: CalendarEventDeleteRequest): CalendarEventDeleteResult =
        CalendarEventDeleteResult.ProviderUnavailable

    override suspend fun verifyDeleteCommitted(
        eventId: String,
        request: CalendarEventDeleteRequest,
    ): CalendarEventDeleteResult = CalendarEventDeleteResult.ProviderUnavailable
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
                        // long: 全天事件按 Calendar Provider 契约使用 UTC 零点和排他的次日结束；标记必须参与回读验证，不能把定时事件误认成全天事件。
                        put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
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

    override suspend fun updateOrReadBack(request: CalendarEventUpdateRequest): CalendarEventUpdateResult =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                try {
                    when (request.scope) {
                        CalendarEventUpdateScope.SERIES -> return@withLock CalendarEventUpdateResult.SeriesUnsupported
                        CalendarEventUpdateScope.OCCURRENCE -> return@withLock CalendarEventUpdateResult.OccurrenceUnsupported
                        CalendarEventUpdateScope.EVENT -> Unit
                    }
                    val current = when (val snapshot = readDeleteSnapshot(request.eventId)) {
                        is CalendarEventDeleteSnapshot.Visible -> snapshot.event
                        CalendarEventDeleteSnapshot.DeletedOrMissing -> return@withLock CalendarEventUpdateResult.NotFound
                        CalendarEventDeleteSnapshot.ProviderUnavailable -> return@withLock CalendarEventUpdateResult.ProviderUnavailable
                    }
                    if (CalendarEventFingerprint.create(current) != request.expectedFingerprint) {
                        return@withLock CalendarEventUpdateResult.FingerprintMismatch
                    }
                    if (current.recurring) return@withLock CalendarEventUpdateResult.ScopeMismatch
                    if (current.allDay) return@withLock CalendarEventUpdateResult.AllDayUnsupported
                    if (current.matches(request)) return@withLock CalendarEventUpdateResult.NoChanges

                    // long: 只更新审批展示过的四个字段，并用旧快照作为 WHERE 条件；外部日历在审批期间改写任一详情时影响行数必须为零。
                    val conditionalUpdate = current.toConditionalDelete()
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.TITLE, request.title)
                        put(CalendarContract.Events.DTSTART, request.startAtMillis)
                        put(CalendarContract.Events.DTEND, request.endAtMillis)
                        put(CalendarContract.Events.EVENT_TIMEZONE, request.timeZoneId)
                    }
                    val updatedRows = contentResolver.update(
                        CalendarContract.Events.CONTENT_URI,
                        values,
                        conditionalUpdate.selection,
                        conditionalUpdate.arguments,
                    )
                    if (updatedRows != 1) return@withLock classifyRejectedUpdate(request)
                    when (val snapshot = readDeleteSnapshot(request.eventId)) {
                        is CalendarEventDeleteSnapshot.Visible -> {
                            val updated = snapshot.event
                            CalendarEventUpdateResult.Committed(
                                update = CalendarEventUpdateRecord(
                                    eventId = request.eventId,
                                    scope = request.scope,
                                    fingerprint = CalendarEventFingerprint.create(updated),
                                    reused = false,
                                ),
                                verified = updated.matches(request) && !updated.allDay && !updated.recurring,
                            )
                        }
                        CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventUpdateResult.Failed
                        CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventUpdateResult.Committed(
                            update = CalendarEventUpdateRecord(
                                eventId = request.eventId,
                                scope = request.scope,
                                fingerprint = "",
                                reused = false,
                            ),
                            verified = false,
                        )
                    }
                } catch (_: SecurityException) {
                    CalendarEventUpdateResult.PermissionDenied
                } catch (_: RuntimeException) {
                    CalendarEventUpdateResult.Failed
                }
            }
        }

    override suspend fun verifyUpdateCommitted(
        eventId: String,
        request: CalendarEventUpdateRequest,
    ): CalendarEventUpdateResult = withContext(Dispatchers.IO) {
        if (eventId != "calendar-${request.eventId}") return@withContext CalendarEventUpdateResult.Failed
        if (request.scope != CalendarEventUpdateScope.EVENT) return@withContext CalendarEventUpdateResult.Failed
        try {
            // long: 已提交恢复只按稳定 ID 回读审批后的目标字段，绝不再次 UPDATE；目标后续漂移时必须停止并保留当前 Provider 事实。
            when (val snapshot = readDeleteSnapshot(request.eventId)) {
                is CalendarEventDeleteSnapshot.Visible -> {
                    val current = snapshot.event
                    if (!current.matches(request) || current.allDay || current.recurring) {
                        CalendarEventUpdateResult.Failed
                    } else {
                        CalendarEventUpdateResult.Committed(
                            update = CalendarEventUpdateRecord(
                                eventId = request.eventId,
                                scope = request.scope,
                                fingerprint = CalendarEventFingerprint.create(current),
                                reused = true,
                            ),
                            verified = true,
                        )
                    }
                }
                CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventUpdateResult.NotFound
                CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventUpdateResult.ProviderUnavailable
            }
        } catch (_: SecurityException) {
            CalendarEventUpdateResult.PermissionDenied
        } catch (_: RuntimeException) {
            CalendarEventUpdateResult.Failed
        }
    }

    override suspend fun deleteOrReadBack(request: CalendarEventDeleteRequest): CalendarEventDeleteResult =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                try {
                    if (request.scope == CalendarEventDeleteScope.OCCURRENCE) {
                        return@withLock CalendarEventDeleteResult.OccurrenceUnsupported
                    }
                    val current = when (val snapshot = readDeleteSnapshot(request.eventId)) {
                        is CalendarEventDeleteSnapshot.Visible -> snapshot.event
                        CalendarEventDeleteSnapshot.DeletedOrMissing -> return@withLock CalendarEventDeleteResult.NotFound
                        CalendarEventDeleteSnapshot.ProviderUnavailable -> return@withLock CalendarEventDeleteResult.ProviderUnavailable
                    }
                    if (CalendarEventFingerprint.create(current) != request.expectedFingerprint) {
                        return@withLock CalendarEventDeleteResult.FingerprintMismatch
                    }
                    val scopeMatches = when (request.scope) {
                        CalendarEventDeleteScope.EVENT -> !current.recurring
                        CalendarEventDeleteScope.SERIES -> current.recurring
                        CalendarEventDeleteScope.OCCURRENCE -> false
                    }
                    if (!scopeMatches) return@withLock CalendarEventDeleteResult.ScopeMismatch

                    // long: Events._ID 删除会移除整条事件或整个重复系列；把审批时快照字段放入 selection，阻止外部日历在审批期间改写目标后仍被误删。
                    val conditionalDelete = current.toConditionalDelete()
                    val deletedRows = contentResolver.delete(
                        CalendarContract.Events.CONTENT_URI,
                        conditionalDelete.selection,
                        conditionalDelete.arguments,
                    )
                    if (deletedRows != 1) {
                        return@withLock classifyRejectedDelete(request)
                    }
                    when (readDeleteSnapshot(request.eventId)) {
                        CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventDeleteResult.Committed(
                            deletion = CalendarEventDeleteRecord(request.eventId, request.scope, reused = false),
                            verified = true,
                        )
                        is CalendarEventDeleteSnapshot.Visible -> CalendarEventDeleteResult.Failed
                        CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventDeleteResult.Committed(
                            deletion = CalendarEventDeleteRecord(request.eventId, request.scope, reused = false),
                            verified = false,
                        )
                    }
                } catch (_: SecurityException) {
                    CalendarEventDeleteResult.PermissionDenied
                } catch (_: RuntimeException) {
                    CalendarEventDeleteResult.Failed
                }
            }
        }

    override suspend fun verifyDeleteCommitted(
        eventId: String,
        request: CalendarEventDeleteRequest,
    ): CalendarEventDeleteResult = withContext(Dispatchers.IO) {
        if (eventId != "calendar-${request.eventId}") return@withContext CalendarEventDeleteResult.Failed
        try {
            // long: COMMITTED 恢复只能确认目标仍不可见，不能再次调用 delete；否则进程重建可能把后来复用的用户事件误删。
            when (readDeleteSnapshot(request.eventId)) {
                CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventDeleteResult.Committed(
                    deletion = CalendarEventDeleteRecord(request.eventId, request.scope, reused = true),
                    verified = true,
                )
                is CalendarEventDeleteSnapshot.Visible -> CalendarEventDeleteResult.Failed
                CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventDeleteResult.ProviderUnavailable
            }
        } catch (_: SecurityException) {
            CalendarEventDeleteResult.PermissionDenied
        } catch (_: RuntimeException) {
            CalendarEventDeleteResult.Failed
        }
    }

    private fun classifyRejectedDelete(request: CalendarEventDeleteRequest): CalendarEventDeleteResult {
        return when (val snapshot = readDeleteSnapshot(request.eventId)) {
            CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventDeleteResult.NotFound
            CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventDeleteResult.ProviderUnavailable
            is CalendarEventDeleteSnapshot.Visible -> {
                if (CalendarEventFingerprint.create(snapshot.event) != request.expectedFingerprint) {
                    CalendarEventDeleteResult.FingerprintMismatch
                } else {
                    CalendarEventDeleteResult.Failed
                }
            }
        }
    }

    private fun classifyRejectedUpdate(request: CalendarEventUpdateRequest): CalendarEventUpdateResult {
        return when (val snapshot = readDeleteSnapshot(request.eventId)) {
            CalendarEventDeleteSnapshot.DeletedOrMissing -> CalendarEventUpdateResult.NotFound
            CalendarEventDeleteSnapshot.ProviderUnavailable -> CalendarEventUpdateResult.ProviderUnavailable
            is CalendarEventDeleteSnapshot.Visible -> {
                if (CalendarEventFingerprint.create(snapshot.event) != request.expectedFingerprint) {
                    CalendarEventUpdateResult.FingerprintMismatch
                } else {
                    CalendarEventUpdateResult.Failed
                }
            }
        }
    }

    private fun readDeleteSnapshot(eventId: Long): CalendarEventDeleteSnapshot {
        val cursor = contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            DELETE_PROJECTION,
            null,
            null,
            null,
        ) ?: return CalendarEventDeleteSnapshot.ProviderUnavailable
        return cursor.use {
            if (!it.moveToFirst()) return@use CalendarEventDeleteSnapshot.DeletedOrMissing
            val deleted = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.DELETED)) != 0
            if (deleted) return@use CalendarEventDeleteSnapshot.DeletedOrMissing
            val recurrenceRule = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.RRULE))
                ?.takeIf(String::isNotBlank)
            val recurrenceDates = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.RDATE))
                ?.takeIf(String::isNotBlank)
            CalendarEventDeleteSnapshot.Visible(
                CalendarEventDetailRecord(
                    eventId = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                    title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)).orEmpty(),
                    startAtMillis = it.getNullableLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
                    endAtMillis = it.getNullableLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
                    allDay = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) != 0,
                    timeZoneId = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE))
                        ?.takeIf(String::isNotBlank),
                    recurring = recurrenceRule != null || recurrenceDates != null,
                    recurrenceRule = recurrenceRule,
                    recurrenceDates = recurrenceDates,
                ),
            )
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
            allDay = getInt(getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) != 0,
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
            CalendarContract.Events.ALL_DAY,
        )
        val MARKER_PROJECTION = arrayOf(
            CalendarContract.Events.CUSTOM_APP_PACKAGE,
            CalendarContract.Events.CUSTOM_APP_URI,
        )
        val DELETE_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.DELETED,
        )
    }
}

private sealed interface CalendarEventDeleteSnapshot {
    data class Visible(val event: CalendarEventDetailRecord) : CalendarEventDeleteSnapshot
    data object DeletedOrMissing : CalendarEventDeleteSnapshot
    data object ProviderUnavailable : CalendarEventDeleteSnapshot
}

private data class CalendarEventConditionalDelete(
    val selection: String,
    val arguments: Array<String>,
)

private fun CalendarEventDetailRecord.toConditionalDelete(): CalendarEventConditionalDelete {
    val clauses = mutableListOf(
        "${CalendarContract.Events._ID}=?",
        "${CalendarContract.Events.DELETED}=0",
        "${CalendarContract.Events.ALL_DAY}=?",
    )
    val arguments = mutableListOf(eventId.toString(), if (allDay) "1" else "0")
    clauses.addNullableTextMatch(CalendarContract.Events.TITLE, title, arguments)
    clauses.addNullableLongMatch(CalendarContract.Events.DTSTART, startAtMillis, arguments)
    clauses.addNullableLongMatch(CalendarContract.Events.DTEND, endAtMillis, arguments)
    clauses.addNullableTextMatch(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId, arguments)
    clauses.addNullableTextMatch(CalendarContract.Events.RRULE, recurrenceRule, arguments)
    clauses.addNullableTextMatch(CalendarContract.Events.RDATE, recurrenceDates, arguments)
    return CalendarEventConditionalDelete(clauses.joinToString(" AND "), arguments.toTypedArray())
}

private fun MutableList<String>.addNullableTextMatch(
    column: String,
    value: String?,
    arguments: MutableList<String>,
) {
    if (value.isNullOrBlank()) {
        add("($column IS NULL OR $column='')")
    } else {
        add("$column=?")
        arguments += value
    }
}

private fun MutableList<String>.addNullableLongMatch(
    column: String,
    value: Long?,
    arguments: MutableList<String>,
) {
    if (value == null) {
        add("$column IS NULL")
    } else {
        add("$column=?")
        arguments += value.toString()
    }
}

private fun android.database.Cursor.getNullableLong(columnIndex: Int): Long? =
    if (isNull(columnIndex)) null else getLong(columnIndex)

private fun CalendarEventWriteRecord.matches(request: CalendarEventWriteRequest): Boolean =
    title == request.title &&
        startAtMillis == request.startAtMillis &&
        endAtMillis == request.endAtMillis &&
        timeZoneId == request.timeZoneId &&
        allDay == request.allDay

private fun CalendarEventDetailRecord.matches(request: CalendarEventUpdateRequest): Boolean =
    eventId == request.eventId &&
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
        allDay = allDay,
        reused = reused,
    )
