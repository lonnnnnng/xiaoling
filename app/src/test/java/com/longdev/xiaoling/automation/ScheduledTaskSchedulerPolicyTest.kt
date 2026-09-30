package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledTaskSchedulerPolicyTest {
    @Test
    fun keepReusesExistingUnfinishedWorkIdInsteadOfRequestedId() {
        val actual = selectEnqueuedWorkRequestId(
            workInfos = listOf(
                ScheduledWorkInfo(id = "existing-work", isFinished = false),
            ),
            requestedId = "new-request-never-enqueued",
        )

        assertEquals("existing-work", actual)
    }

    @Test
    fun finishedExistingWorkIsUsedWhenNoUnfinishedWorkRemains() {
        val actual = selectEnqueuedWorkRequestId(
            workInfos = listOf(
                ScheduledWorkInfo(id = "finished-work", isFinished = true),
            ),
            requestedId = "new-request",
        )

        assertEquals("finished-work", actual)
    }

    @Test
    fun emptyWorkManagerReadFallsBackToRequestedId() {
        val actual = selectEnqueuedWorkRequestId(emptyList(), "requested-work")

        assertEquals("requested-work", actual)
    }
}
