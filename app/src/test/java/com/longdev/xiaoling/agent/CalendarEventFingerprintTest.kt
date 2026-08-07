package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventFingerprintTest {
    @Test
    fun fingerprintIsVersionedDeterministicAndBindsMutableDeleteFields() {
        val event = CalendarEventDetailRecord(
            eventId = 42L,
            title = "项目评审",
            startAtMillis = 1_000L,
            endAtMillis = 2_000L,
            allDay = false,
            timeZoneId = "Asia/Shanghai",
            recurring = false,
        )

        val fingerprint = CalendarEventFingerprint.create(event)

        assertEquals(fingerprint, CalendarEventFingerprint.create(event.copy()))
        assertTrue(CalendarEventFingerprint.isValid(fingerprint))
        assertNotEquals(fingerprint, CalendarEventFingerprint.create(event.copy(title = "项目复盘")))
        assertNotEquals(fingerprint, CalendarEventFingerprint.create(event.copy(startAtMillis = 1_001L)))
        assertNotEquals(fingerprint, CalendarEventFingerprint.create(event.copy(recurring = true)))
    }
}
