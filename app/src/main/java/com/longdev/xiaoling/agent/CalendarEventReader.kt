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
