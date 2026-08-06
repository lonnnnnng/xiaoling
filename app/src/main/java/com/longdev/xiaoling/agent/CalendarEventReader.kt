package com.longdev.xiaoling.agent

import android.content.ContentResolver
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CalendarEventRecord(
    val title: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val allDay: Boolean,
)

sealed interface CalendarEventReadResult {
    data class Success(val events: List<CalendarEventRecord>) : CalendarEventReadResult
    data object PermissionDenied : CalendarEventReadResult
    data object ProviderUnavailable : CalendarEventReadResult
    data object Failed : CalendarEventReadResult
}

fun interface CalendarEventReader {
    suspend fun listEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        limit: Int,
    ): CalendarEventReadResult
}

object UnavailableCalendarEventReader : CalendarEventReader {
    override suspend fun listEvents(
        startAtMillis: Long,
        endAtMillis: Long,
        limit: Int,
    ): CalendarEventReadResult = CalendarEventReadResult.ProviderUnavailable
}

class AndroidCalendarEventReader(
    private val contentResolver: ContentResolver,
) : CalendarEventReader {
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
                val titleColumn = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayColumn = it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val events = buildList {
                    while (it.moveToNext()) {
                        add(
                            CalendarEventRecord(
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

    private companion object {
        val PROJECTION = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY,
        )
    }
}
