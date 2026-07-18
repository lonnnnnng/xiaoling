package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

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

    @Test
    fun dailyScheduleUsesNextWallClockTimeInSelectedZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val morning = ZonedDateTime.of(2026, 7, 18, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val afterTarget = ZonedDateTime.of(2026, 7, 18, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(
            ZonedDateTime.of(2026, 7, 18, 9, 30, 0, 0, zone).toInstant().toEpochMilli(),
            WorkflowSchedulePolicy.nextPlannedAt(morning, WorkflowScheduleType.DAILY, 9 * 60 + 30, null, zone.id),
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 19, 9, 30, 0, 0, zone).toInstant().toEpochMilli(),
            WorkflowSchedulePolicy.nextPlannedAt(afterTarget, WorkflowScheduleType.DAILY, 9 * 60 + 30, null, zone.id),
        )
    }

    @Test
    fun weeklyScheduleMovesToSelectedWeekdayWithoutBackfillingMissedRuns() {
        val zone = ZoneId.of("Asia/Shanghai")
        val saturday = ZonedDateTime.of(2026, 7, 18, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(
            ZonedDateTime.of(2026, 7, 19, 9, 0, 0, 0, zone).toInstant().toEpochMilli(),
            WorkflowSchedulePolicy.nextPlannedAt(saturday, WorkflowScheduleType.WEEKLY, 9 * 60, 7, zone.id),
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 25, 7, 0, 0, 0, zone).toInstant().toEpochMilli(),
            WorkflowSchedulePolicy.nextPlannedAt(saturday, WorkflowScheduleType.WEEKLY, 7 * 60, 6, zone.id),
        )
    }

    @Test
    fun recurringScheduleRejectsInvalidClockAndWeeklyDay() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowSchedulePolicy.nextPlannedAt(1L, WorkflowScheduleType.DAILY, 24 * 60, null, "Asia/Shanghai")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowSchedulePolicy.nextPlannedAt(1L, WorkflowScheduleType.WEEKLY, 8 * 60, null, "Asia/Shanghai")
        }
    }
}
