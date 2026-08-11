package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarNextEventPolicyTest {
    @Test
    fun selectsOnlyUniqueEarliestOccurrenceStrictlyAfterNow() {
        val result = selectNextCalendarEvent(
            events = listOf(
                event(1L, startAtMillis = 900L),
                event(2L, startAtMillis = 1_000L),
                event(3L, startAtMillis = 1_200L),
                event(4L, startAtMillis = 1_100L),
            ),
            nowMillis = 1_000L,
        )

        assertEquals(4L, (result as CalendarNextEventReadResult.Success).event.eventId)
    }

    @Test
    fun sameEarliestStartTimeIsAmbiguousWithoutTieBreaking() {
        val result = selectNextCalendarEvent(
            events = listOf(
                event(1L, startAtMillis = 1_100L, endAtMillis = 1_200L),
                event(2L, startAtMillis = 1_100L, endAtMillis = 1_300L),
                event(3L, startAtMillis = 1_200L),
            ),
            nowMillis = 1_000L,
        )

        assertEquals(2, (result as CalendarNextEventReadResult.AmbiguousStartTime).occurrenceCount)
    }

    @Test
    fun noFutureOccurrenceReturnsExplicitEmptyResult() {
        val result = selectNextCalendarEvent(
            events = listOf(event(1L, 900L), event(2L, 1_000L)),
            nowMillis = 1_000L,
        )

        assertTrue(result === CalendarNextEventReadResult.NoUpcomingEvent)
    }

    private fun event(
        id: Long,
        startAtMillis: Long,
        endAtMillis: Long = startAtMillis + 100L,
    ) = CalendarEventRecord(
        eventId = id,
        title = "日程$id",
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        allDay = false,
    )
}
