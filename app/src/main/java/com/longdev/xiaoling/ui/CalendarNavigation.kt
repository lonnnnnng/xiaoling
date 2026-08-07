package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.CalendarEventFingerprint
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

        CALENDAR_CREATE_TOOL_NAME -> {
            if (verificationStatus != MessageToolVerificationStatus.VERIFIED) return null
            val requestedTitle = arguments.validCalendarCreateArguments() ?: return null
            val match = CALENDAR_CREATE_RESULT_PATTERN.matchEntire(result) ?: return null
            if (match.groupValues[1] != requestedTitle) return null
            val id = CalendarNavigationPolicy.normalizeId(match.groupValues[2]) ?: return null
            // long: 创建副作用只有在应用回读并给出唯一稳定 ID 后才允许继续查看当前 Provider 事实。
            id.takeIf { result.countStableCalendarIds() == 1 }
        }

        CALENDAR_UPDATE_TOOL_NAME -> {
            if (verificationStatus != MessageToolVerificationStatus.VERIFIED) return null
            val requestedId = arguments.validCalendarUpdateArguments() ?: return null
            val match = CALENDAR_UPDATE_RESULT_PATTERN.matchEntire(result) ?: return null
            val resultId = CalendarNavigationPolicy.normalizeId(match.groupValues[1]) ?: return null
            val currentFingerprint = match.groupValues[2]
            if (
                resultId != requestedId ||
                !CalendarEventFingerprint.isValid(currentFingerprint) ||
                currentFingerprint == arguments[CALENDAR_EXPECTED_FINGERPRINT_ARGUMENT]
            ) return null
            resultId.takeIf { result.countStableCalendarIds() == 1 }
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

private fun Map<String, String>.validCalendarCreateArguments(): String? {
    if (keys != setOf(CALENDAR_TITLE_ARGUMENT, CALENDAR_START_ARGUMENT, CALENDAR_END_ARGUMENT, CALENDAR_TIME_ZONE_ARGUMENT)) {
        return null
    }
    val title = get(CALENDAR_TITLE_ARGUMENT)
        ?.trim()
        ?.takeIf { value -> value.length in 1..200 && value.none { it == '\n' || it == '\r' } }
        ?.toCalendarTitle()
        ?.takeIf { value -> value.isNotBlank() && " · id=" !in value }
        ?: return null
    if (!hasCalendarTimeArgument(CALENDAR_START_ARGUMENT) || !hasCalendarTimeArgument(CALENDAR_END_ARGUMENT)) return null
    if (!hasCalendarTimeZoneArgument()) return null
    return title
}

private fun Map<String, String>.validCalendarUpdateArguments(): String? {
    if (
        keys != setOf(
            CALENDAR_EVENT_ID_ARGUMENT,
            CALENDAR_EXPECTED_FINGERPRINT_ARGUMENT,
            CALENDAR_SCOPE_ARGUMENT,
            CALENDAR_TITLE_ARGUMENT,
            CALENDAR_START_ARGUMENT,
            CALENDAR_END_ARGUMENT,
            CALENDAR_TIME_ZONE_ARGUMENT,
        )
    ) return null
    val eventId = CalendarNavigationPolicy.normalizeId(get(CALENDAR_EVENT_ID_ARGUMENT).orEmpty()) ?: return null
    if (get(CALENDAR_SCOPE_ARGUMENT) != CALENDAR_EVENT_SCOPE) return null
    if (!CalendarEventFingerprint.isValid(get(CALENDAR_EXPECTED_FINGERPRINT_ARGUMENT).orEmpty())) return null
    val title = get(CALENDAR_TITLE_ARGUMENT)
        ?.trim()
        ?.takeIf { value -> value.length in 1..200 && value.none { it == '\n' || it == '\r' } }
        ?: return null
    if (title.toCalendarTitle().isBlank() || !hasCalendarTimeArgument(CALENDAR_START_ARGUMENT) || !hasCalendarTimeArgument(CALENDAR_END_ARGUMENT)) {
        return null
    }
    if (!hasCalendarTimeZoneArgument()) return null
    return eventId
}

private fun Map<String, String>.hasCalendarTimeArgument(name: String): Boolean =
    (get(name)?.takeIf { value -> value.length in 20..40 && value.none { it == '\n' || it == '\r' } } != null)

private fun Map<String, String>.hasCalendarTimeZoneArgument(): Boolean =
    (get(CALENDAR_TIME_ZONE_ARGUMENT)?.takeIf { value -> value.length in 1..100 && value.none { it == '\n' || it == '\r' } } != null)

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
private const val CALENDAR_CREATE_TOOL_NAME = "calendar.create_event"
private const val CALENDAR_UPDATE_TOOL_NAME = "calendar.update_event"
private const val CALENDAR_DAYS_ARGUMENT = "days_ahead"
private const val CALENDAR_QUERY_ARGUMENT = "query"
private const val CALENDAR_LIMIT_ARGUMENT = "limit"
private const val CALENDAR_EVENT_ID_ARGUMENT = "event_id"
private const val CALENDAR_EXPECTED_FINGERPRINT_ARGUMENT = "expected_fingerprint"
private const val CALENDAR_SCOPE_ARGUMENT = "scope"
private const val CALENDAR_TITLE_ARGUMENT = "title"
private const val CALENDAR_START_ARGUMENT = "start_at"
private const val CALENDAR_END_ARGUMENT = "end_at"
private const val CALENDAR_TIME_ZONE_ARGUMENT = "time_zone"
private const val CALENDAR_EVENT_SCOPE = "event"
private const val CALENDAR_EVENT_ID_PREFIX = "calendar-"
private const val DEFAULT_CALENDAR_DAYS = 7
private const val CALENDAR_DETAIL_HEADING = "日程详情："
private val CALENDAR_EVENT_ID_PATTERN = Regex("calendar-[1-9][0-9]{0,18}")
private val CALENDAR_CREATE_RESULT_PATTERN = Regex(
    "已创建并验证日程：(.+) · id=($CALENDAR_EVENT_ID_PREFIX[1-9][0-9]{0,18})",
)
private val CALENDAR_UPDATE_RESULT_PATTERN = Regex(
    "已修改并验证日程：($CALENDAR_EVENT_ID_PREFIX[1-9][0-9]{0,18})\\n当前事件指纹：(calendar-event-v1-[0-9a-f]{64})",
)
private val CALENDAR_RESULT_ENTRY_PATTERN = Regex(
    pattern = "(?m)^1\\. .* · id=(calendar-[1-9][0-9]{0,18})$",
)
private val CALENDAR_DETAIL_ID_PATTERN = Regex(
    pattern = "(?m)^ID：($CALENDAR_EVENT_ID_PREFIX[1-9][0-9]{0,18})$",
)
