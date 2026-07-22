package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledWorkerStopReasonPolicyTest {
    @Test
    fun mapsKnownAndroidStopCodesToStablePrivacySafeCategories() {
        val quota = ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(10)
        val timeout = ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(3)
        val abandoned = ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(16)

        assertEquals(ScheduledWorkerStopReason(10, "QUOTA", "系统后台配额限制停止了本次工作流"), quota)
        assertEquals("TIMEOUT", timeout?.name)
        assertEquals("TIMEOUT_ABANDONED", abandoned?.name)
    }

    @Test
    fun doesNotInventReasonBeforeWorkerHasStopped() {
        assertNull(ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(-256))
    }

    @Test
    fun retainsUnknownCodesAsTypedEvidenceInsteadOfDroppingThem() {
        val unknown = ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(9_999)!!

        assertEquals(9_999, unknown.code)
        assertEquals("UNRECOGNIZED", unknown.name)
    }
}
