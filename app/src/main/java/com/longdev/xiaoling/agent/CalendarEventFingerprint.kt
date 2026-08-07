package com.longdev.xiaoling.agent

import java.security.MessageDigest

object CalendarEventFingerprint {
    private const val PREFIX = "calendar-event-v1-"
    private val PATTERN = Regex("calendar-event-v1-[0-9a-f]{64}")

    fun create(event: CalendarEventDetailRecord): String {
        // long: 删除审批必须绑定 Provider 当前事件版本；长度前缀避免标题、时区与重复规则拼接后产生边界碰撞。
        val canonical = listOf(
            event.eventId.toString(),
            event.title,
            event.startAtMillis?.toString().orEmpty(),
            event.endAtMillis?.toString().orEmpty(),
            event.allDay.toString(),
            event.timeZoneId.orEmpty(),
            event.recurrenceRule.orEmpty(),
            event.recurrenceDates.orEmpty(),
            event.recurring.toString(),
        ).joinToString(separator = "") { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            "${bytes.size}:$value"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return PREFIX + digest
    }

    fun isValid(value: String): Boolean = PATTERN.matches(value)
}
