package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

/**
 * 答案级系统日程导航的安全投影。
 *
 * 日程标题和详情属于外部 Provider 数据，工具结果中的稳定 ID 只能提出查看目标；
 * 入口必须来自应用生成的固定外壳，详情页还会按当前 Provider 二次读取，避免把旧摘要当成事实。
 * 作者：long
 */
internal fun MessagePart.Tool.calendarEventIdForNavigation(): String? {
    if (!success || verificationStatus == MessageToolVerificationStatus.FAILED) return null

    return when (toolName) {
        CALENDAR_LIST_TOOL_NAME -> {
            val daysAhead = arguments.validCalendarDaysAndLimit() ?: return null
            trustedCalendarListId(result, "未来 $daysAhead 天日程（1）")
        }

        CALENDAR_SEARCH_TOOL_NAME -> {
            val search = arguments.validCalendarSearchArguments() ?: return null
            trustedCalendarListId(
                result = result,
                expectedHeading = "未来 ${search.daysAhead} 天匹配“${search.query.toCalendarTitle()}”的日程（1）",
            )
        }

        CALENDAR_GET_TOOL_NAME -> {
            if (arguments.keys != setOf(CALENDAR_EVENT_ID_ARGUMENT)) return null
            val requestedId = CalendarNavigationPolicy.normalizeId(arguments[CALENDAR_EVENT_ID_ARGUMENT].orEmpty())
                ?: return null
            if (result.lineSequence().firstOrNull() != CALENDAR_DETAIL_HEADING) return null
            val detailId = CALENDAR_DETAIL_ID_PATTERN.findAll(result).singleOrNull()?.groupValues?.get(1)
                ?.let(CalendarNavigationPolicy::normalizeId)
                ?: return null
            detailId.takeIf { it == requestedId && result.countStableCalendarIds() == 1 }
        }

        else -> null
    }
}

private fun trustedCalendarListId(result: String, expectedHeading: String): String? {
    val firstLine = result.lineSequence().firstOrNull().orEmpty()
    if (firstLine != expectedHeading) return null
    val entry = CALENDAR_RESULT_ENTRY_PATTERN.findAll(result).singleOrNull() ?: return null
    val id = CalendarNavigationPolicy.normalizeId(entry.groupValues[1]) ?: return null
    // long: 多条日程、正文伪造 ID 或标题换行都会破坏唯一目标，宁可不提供入口也不猜测事件。
    return id.takeIf { result.countStableCalendarIds() == 1 }
}

private fun String.countStableCalendarIds(): Int = CALENDAR_EVENT_ID_PATTERN.findAll(this).count()

private fun Map<String, String>.validCalendarDaysAndLimit(): Int? {
    if (keys.any { key -> key != CALENDAR_DAYS_ARGUMENT && key != CALENDAR_LIMIT_ARGUMENT }) return null
    val rawDays = get(CALENDAR_DAYS_ARGUMENT)
    val days = rawDays?.toIntOrNull() ?: if (rawDays == null) DEFAULT_CALENDAR_DAYS else return null
    if (days !in 1..30) return null
    val rawLimit = get(CALENDAR_LIMIT_ARGUMENT)
    if (rawLimit != null && rawLimit.toIntOrNull()?.let { limit -> limit in 1..20 } != true) return null
    return days
}

private fun Map<String, String>.validCalendarSearchArguments(): CalendarSearchNavigationArguments? {
    if (keys.any { key -> key != CALENDAR_QUERY_ARGUMENT && key != CALENDAR_DAYS_ARGUMENT && key != CALENDAR_LIMIT_ARGUMENT }) {
        return null
    }
    val query = get(CALENDAR_QUERY_ARGUMENT)
        ?.takeIf { value ->
            value.length <= 100 && value.none { character -> character == '\n' || character == '\r' }
        }
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val rawDays = get(CALENDAR_DAYS_ARGUMENT)
    val days = rawDays?.toIntOrNull() ?: if (rawDays == null) DEFAULT_CALENDAR_DAYS else return null
    if (days !in 1..30) return null
    val rawLimit = get(CALENDAR_LIMIT_ARGUMENT)
    if (rawLimit != null && rawLimit.toIntOrNull()?.let { limit -> limit in 1..20 } != true) return null
    return CalendarSearchNavigationArguments(query = query, daysAhead = days)
}

private fun String.toCalendarTitle(): String = trim()
    .replace(Regex("\\s+"), " ")
    .take(200)
    .ifBlank { "未命名日程" }

private data class CalendarSearchNavigationArguments(
    val query: String,
    val daysAhead: Int,
)

internal object CalendarNavigationPolicy {
    private const val MAX_ID_LENGTH = 28

    fun normalizeId(raw: String): String? {
        val value = raw.trim()
        if (value.length > MAX_ID_LENGTH || !CALENDAR_EVENT_ID_PATTERN.matches(value)) return null
        val numericId = value.removePrefix(CALENDAR_EVENT_ID_PREFIX).toLongOrNull()?.takeIf { it > 0L }
            ?: return null
        return value.takeIf { it == "$CALENDAR_EVENT_ID_PREFIX$numericId" }
    }

    fun numericId(raw: String): Long? = normalizeId(raw)
        ?.removePrefix(CALENDAR_EVENT_ID_PREFIX)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

private const val CALENDAR_LIST_TOOL_NAME = "calendar.list_events"
private const val CALENDAR_SEARCH_TOOL_NAME = "calendar.search_events"
private const val CALENDAR_GET_TOOL_NAME = "calendar.get"
private const val CALENDAR_DAYS_ARGUMENT = "days_ahead"
private const val CALENDAR_QUERY_ARGUMENT = "query"
private const val CALENDAR_LIMIT_ARGUMENT = "limit"
private const val CALENDAR_EVENT_ID_ARGUMENT = "event_id"
private const val CALENDAR_EVENT_ID_PREFIX = "calendar-"
private const val DEFAULT_CALENDAR_DAYS = 7
private const val CALENDAR_DETAIL_HEADING = "日程详情："
private val CALENDAR_EVENT_ID_PATTERN = Regex("calendar-[1-9][0-9]{0,18}")
private val CALENDAR_RESULT_ENTRY_PATTERN = Regex(
    pattern = "(?m)^1\\. .* · id=(calendar-[1-9][0-9]{0,18})$",
)
private val CALENDAR_DETAIL_ID_PATTERN = Regex(
    pattern = "(?m)^ID：($CALENDAR_EVENT_ID_PREFIX[1-9][0-9]{0,18})$",
)
