package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduledTaskPolicyTest {
    @Test
    fun oneTimeDelayProducesPlannedTimestamp() {
        assertEquals(1_060_000L, ScheduledTaskPolicy.plannedAt(now = 1_000_000L, delayMinutes = 1))
        assertEquals(
            1_000_000L + 7L * 24 * 60 * 60_000,
            ScheduledTaskPolicy.plannedAt(now = 1_000_000L, delayMinutes = ScheduledTaskPolicy.MAX_DELAY_MINUTES),
        )
    }

    @Test
    fun delayOutsideFirstSliceBoundsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ScheduledTaskPolicy.plannedAt(now = 1_000_000L, delayMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduledTaskPolicy.plannedAt(
                now = 1_000_000L,
                delayMinutes = ScheduledTaskPolicy.MAX_DELAY_MINUTES + 1,
            )
        }
    }
}
