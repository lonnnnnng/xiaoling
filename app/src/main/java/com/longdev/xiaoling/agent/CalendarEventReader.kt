package com.longdev.xiaoling.agent

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CalendarEventRecord(
    val eventId: Long,
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val allDay: Boolean,
)

data class CalendarEventDetailRecord(
    val eventId: Long,
    val title: String,
    val startAtMillis: Long?,
    val endAtMillis: Long?,
    val allDay: Boolean,
    val timeZoneId: String?,
    val recurring: Boolean,
    val recurrenceRule: String? = null,
    val recurrenceDates: String? = null,
    val reminderMinutesBefore: Int? = null,
    val reminderCount: Int = 0,
)

sealed interface CalendarEventReadResult {
    data class Success(val events: List<CalendarEventRecord>) : CalendarEventReadResult
    data object PermissionDenied : CalendarEventReadResult
    data object ProviderUnavailable : CalendarEventReadResult
    data object Failed : CalendarEventReadResult
}

sealed interface CalendarEventDetailReadResult {
    data class Success(val event: CalendarEventDetailRecord) : CalendarEventDetailReadResult
    data object NotFound : CalendarEventDetailReadResult
    data object PermissionDenied : CalendarEventDetailReadResult
    data object ProviderUnavailable : CalendarEventDetailReadResult
    data object Failed : CalendarEventDetailReadResult
}

fun interface CalendarEventReader {
    suspend fun listEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        limit: Int,
    ): CalendarEventReadResult

    suspend fun searchEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        query: String,
        limit: Int,
    ): CalendarEventReadResult {
        // long: Provider 只返回最小日程字段；关键词匹配在内存中完成，避免把描述、地点或账户字段带入 Agent。
        return when (val result = listEvents(startAtMillis, endAtMillis, MAX_SEARCH_CANDIDATE_COUNT)) {
            is CalendarEventReadResult.Success -> CalendarEventReadResult.Success(
                result.events
                    .asSequence()
                    .filter { it.title.contains(query, ignoreCase = true) }
                    .take(limit)
                    .toList(),
            )
            else -> result
        }
    }

    suspend fun getEvent(eventId: Long): CalendarEventDetailReadResult =
        CalendarEventDetailReadResult.ProviderUnavailable

    companion object {
        const val MAX_SEARCH_CANDIDATE_COUNT: Int = 200
    }
}

object UnavailableCalendarEventReader : CalendarEventReader {
    override suspend fun listEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        limit: Int,
    ): CalendarEventReadResult = CalendarEventReadResult.ProviderUnavailable

    override suspend fun searchEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        query: String,
        limit: Int,
    ): CalendarEventReadResult = super<CalendarEventReader>.searchEvents(startAtMillis, endAtMillis, query, limit)
}

class AndroidCalendarEventReader(
    private val contentResolver: ContentResolver,
) : CalendarEventReader {
    override suspend fun searchEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        query: String,
        limit: Int,
    ): CalendarEventReadResult = super<CalendarEventReader>.searchEvents(startAtMillis, endAtMillis, query, limit)

    override suspend fun listEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        limit: Int,
    ): CalendarEventReadResult = withContext(Dispatchers.IO) {
        try {
            // long: 日历 Provider 查询只投影标题、起止时间和全天标记；地点、描述、参与人、组织者及账户字段不会离开系统 Provider。
            val cursor = CalendarContract.Instances.query(
                contentResolver,
                PROJECTION,
                startAtMillis,
                endAtMillis,
            ) ?: return@withContext CalendarEventReadResult.ProviderUnavailable
            cursor.use {
                val eventIdColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleColumn = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayColumn = it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val events = buildList {
                    while (it.moveToNext()) {
                        add(
                            CalendarEventRecord(
                                eventId = it.getLong(eventIdColumn),
                                title = it.getString(titleColumn).orEmpty(),
                                startAtMillis = it.getLong(startColumn),
                                endAtMillis = it.getLong(endColumn),
                                allDay = it.getInt(allDayColumn) != 0,
                            ),
                        )
                    }
                }
                    .sortedWith(compareBy(CalendarEventRecord::startAtMillis, CalendarEventRecord::endAtMillis))
                    .take(limit)
                CalendarEventReadResult.Success(events)
            }
        } catch (_: SecurityException) {
            // long: 用户可以在权限检查和 Provider 查询之间撤销授权；这里再次 fail-closed，不能把竞态包装成空日程。
            CalendarEventReadResult.PermissionDenied
        } catch (_: RuntimeException) {
            CalendarEventReadResult.Failed
        }
    }

    override suspend fun getEvent(eventId: Long): CalendarEventDetailReadResult = withContext(Dispatchers.IO) {
        try {
            // long: 详情读取只按 Instances 已返回的 Events._ID 回读当前权威行，并限制投影字段，避免稳定身份扩大为地点、描述或账户读取能力。
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val cursor = contentResolver.query(
                eventUri,
                DETAIL_PROJECTION,
                null,
                null,
                null,
            ) ?: return@withContext CalendarEventDetailReadResult.ProviderUnavailable
            cursor.use {
                if (!it.moveToFirst()) return@withContext CalendarEventDetailReadResult.NotFound
                val deletedColumn = it.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
                if (it.getInt(deletedColumn) != 0) return@withContext CalendarEventDetailReadResult.NotFound
                val idColumn = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val startColumn = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val endColumn = it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val titleColumn = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val allDayColumn = it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val timeZoneColumn = it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)
                val recurrenceColumn = it.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
                val recurrenceDateColumn = it.getColumnIndexOrThrow(CalendarContract.Events.RDATE)
                val reminder = readReminderSummary(eventId)
                CalendarEventDetailReadResult.Success(
                    CalendarEventDetailRecord(
                        eventId = it.getLong(idColumn),
                        title = it.getString(titleColumn).orEmpty(),
                        startAtMillis = it.getNullableLong(startColumn),
                        endAtMillis = it.getNullableLong(endColumn),
                        allDay = it.getInt(allDayColumn) != 0,
                        timeZoneId = it.getString(timeZoneColumn)?.takeIf(String::isNotBlank),
                        recurring = !it.getString(recurrenceColumn).isNullOrBlank() ||
                            !it.getString(recurrenceDateColumn).isNullOrBlank(),
                        recurrenceRule = it.getString(recurrenceColumn)?.takeIf(String::isNotBlank),
                        recurrenceDates = it.getString(recurrenceDateColumn)?.takeIf(String::isNotBlank),
                        reminderMinutesBefore = reminder.minutesBefore,
                        reminderCount = reminder.count,
                    ),
                )
            }
        } catch (_: SecurityException) {
            // long: 权限可能在稳定 ID 产生后被撤销；详情读取必须把竞态视为拒绝，而不是把旧摘要冒充当前详情。
            CalendarEventDetailReadResult.PermissionDenied
        } catch (_: RuntimeException) {
            CalendarEventDetailReadResult.Failed
        }
    }

    private fun readReminderSummary(eventId: Long): CalendarEventReminderSummary {
        val cursor = contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            REMINDER_PROJECTION,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders._ID} ASC",
        ) ?: return CalendarEventReminderSummary(minutesBefore = null, count = -1)
        return cursor.use {
            var count = 0
            var singleAlertMinutes: Int? = null
            while (it.moveToNext()) {
                count += 1
                val minutes = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES))
                val method = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Reminders.METHOD))
                singleAlertMinutes = if (count == 1 && method == CalendarContract.Reminders.METHOD_ALERT && minutes >= 0) {
                    minutes
                } else {
                    null
                }
            }
            CalendarEventReminderSummary(singleAlertMinutes.takeIf { count == 1 }, count)
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY,
        )
        val DETAIL_PROJECTION = arrayOf(
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
        val REMINDER_PROJECTION = arrayOf(
            CalendarContract.Reminders._ID,
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD,
        )
    }
}

private data class CalendarEventReminderSummary(
    val minutesBefore: Int?,
    val count: Int,
)

private fun android.database.Cursor.getNullableLong(columnIndex: Int): Long? =
    if (isNull(columnIndex)) null else getLong(columnIndex)
