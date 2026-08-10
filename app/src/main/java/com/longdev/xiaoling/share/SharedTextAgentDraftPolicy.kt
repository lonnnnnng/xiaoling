package com.longdev.xiaoling.share

import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId

internal object SharedTextAgentDraftPolicy {
    fun createNoteDraft(sharedText: String): String? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        // long: 外部分享只有在用户点击“保存为笔记”后才升级为 Agent 草稿，避免系统 Intent 自动触发发送或写入。
        return "/agent 使用 notes.create 将以下分享文本保存为一条本机笔记。请根据正文生成简洁标题，并完整保留正文内容：\n\n$normalizedText"
    }

    fun createMemoryDraft(sharedText: String): String? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        // long: 长期记忆会影响后续会话的个性化上下文，因此外部文本必须先变成可编辑草稿，再由用户发送并逐次批准写入。
        return "/agent 使用 memory.remember 将以下分享文本保存为一条长期记忆。请完整保留正文，不补充或推断未提供的事实；只在用户批准后写入，并选择最合适的记忆类型与少量标签：\n\n$normalizedText"
    }

    fun createCalendarEventDraft(sharedText: String): String? {
        val fields = parseCalendarEventFields(sharedText) ?: return null
        // long: 分享日程只投影已明确提供且彼此一致的四个字段；模型不能从正文猜日期、时长或时区，正式写入仍由发送和逐次审批分别触发。
        return buildString {
            appendLine("/agent 使用 calendar.create_event 创建一条一次性非全天系统日程。只能使用以下四个明确参数，不得补充、改写或推断；发送后仍需逐次审批，审批通过后必须由当前 Calendar Provider 回读验证：")
            appendLine("title：${fields.title}")
            appendLine("start_at：${fields.startAt}")
            appendLine("end_at：${fields.endAt}")
            append("time_zone：${fields.timeZone}")
        }
    }

    fun createAllDayCalendarEventDraft(sharedText: String): String? {
        val fields = parseAllDayCalendarEventFields(sharedText) ?: return null
        // long: 全天日程只消费明确标题和唯一日期；不从定时字段推导日期，避免用户原本的具体时间被静默丢失。
        return buildString {
            appendLine("/agent 使用 calendar.create_all_day_event 创建一条一次性单日全天系统日程。只能使用以下两个明确参数，不得补充、改写或推断；发送后仍需逐次审批，审批通过后必须由当前 Calendar Provider 回读验证：")
            appendLine("title：${fields.title}")
            append("date：${fields.date}")
        }
    }

    private fun parseAllDayCalendarEventFields(sharedText: String): AllDayCalendarEventDraftFields? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        val values = linkedMapOf<AllDayCalendarEventField, String>()
        normalizedText.lineSequence().forEach { line ->
            val match = ALL_DAY_CALENDAR_FIELD_LINE.matchEntire(line)
            if (match != null) {
                val field = AllDayCalendarEventField.fromLabel(match.groupValues[1]) ?: return null
                val value = match.groupValues[2].trim()
                if (value.isBlank() || values.put(field, value) != null) return null
                return@forEach
            }
            // long: 分享中出现开始、结束或时区意味着用户描述的是定时事件；全天入口必须拒绝，不能只截取标题后猜成全天。
            if (CALENDAR_FIELD_LINE.matches(line)) return null
        }
        if (values.keys != AllDayCalendarEventField.entries.toSet()) return null
        val title = values.getValue(AllDayCalendarEventField.TITLE)
        val rawDate = values.getValue(AllDayCalendarEventField.DATE)
        if (title.length !in 1..MAX_CALENDAR_TITLE_LENGTH) return null
        val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return null
        if (date.toString() != rawDate) return null
        return AllDayCalendarEventDraftFields(title = title, date = rawDate)
    }

    private fun parseCalendarEventFields(sharedText: String): CalendarEventDraftFields? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        val values = linkedMapOf<CalendarEventField, String>()
        normalizedText.lineSequence().forEach { line ->
            val match = CALENDAR_FIELD_LINE.matchEntire(line) ?: return@forEach
            val field = CalendarEventField.fromLabel(match.groupValues[1]) ?: return null
            val value = match.groupValues[2].trim()
            // long: 同一字段出现两次即存在多个候选值；即使内容相同也拒绝，避免分享正文中的重复模板被静默折叠。
            if (value.isBlank() || values.put(field, value) != null) return null
        }
        if (values.keys != CalendarEventField.entries.toSet()) return null

        val title = values.getValue(CalendarEventField.TITLE)
        val startText = values.getValue(CalendarEventField.START_AT)
        val endText = values.getValue(CalendarEventField.END_AT)
        val timeZone = values.getValue(CalendarEventField.TIME_ZONE)
        if (title.length !in 1..MAX_CALENDAR_TITLE_LENGTH) return null
        val start = runCatching { OffsetDateTime.parse(startText) }.getOrNull() ?: return null
        val end = runCatching { OffsetDateTime.parse(endText) }.getOrNull() ?: return null
        val zone = runCatching { ZoneId.of(timeZone) }.getOrNull()
            ?.takeIf { timeZone in ZoneId.getAvailableZoneIds() }
            ?: return null
        if (!end.toInstant().isAfter(start.toInstant())) return null
        if (zone.rules.getOffset(start.toInstant()) != start.offset) return null
        if (zone.rules.getOffset(end.toInstant()) != end.offset) return null
        return CalendarEventDraftFields(
            title = title,
            startAt = startText,
            endAt = endText,
            timeZone = timeZone,
        )
    }

    private data class CalendarEventDraftFields(
        val title: String,
        val startAt: String,
        val endAt: String,
        val timeZone: String,
    )

    private data class AllDayCalendarEventDraftFields(
        val title: String,
        val date: String,
    )

    private enum class CalendarEventField {
        TITLE,
        START_AT,
        END_AT,
        TIME_ZONE;

        companion object {
            fun fromLabel(label: String): CalendarEventField? = when (label.lowercase()) {
                "标题", "title" -> TITLE
                "开始", "开始时间", "start", "start_at" -> START_AT
                "结束", "结束时间", "end", "end_at" -> END_AT
                "时区", "timezone", "time_zone" -> TIME_ZONE
                else -> null
            }
        }
    }

    private enum class AllDayCalendarEventField {
        TITLE,
        DATE;

        companion object {
            fun fromLabel(label: String): AllDayCalendarEventField? = when (label.lowercase()) {
                "标题", "title" -> TITLE
                "日期", "全天日期", "date" -> DATE
                else -> null
            }
        }
    }

    private const val MAX_CALENDAR_TITLE_LENGTH = 200
    private val CALENDAR_FIELD_LINE = Regex(
        pattern = """^\s*(标题|开始(?:时间)?|结束(?:时间)?|时区|title|start(?:_at)?|end(?:_at)?|time_?zone)\s*[:：=]\s*(.*?)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val ALL_DAY_CALENDAR_FIELD_LINE = Regex(
        pattern = """^\s*(标题|全天日期|日期|title|date)\s*[:：=]\s*(.*?)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
}
