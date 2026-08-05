package com.longdev.xiaoling.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAccessibilityServiceLifecyclePolicyTest {
    @Test
    fun feedbackInterruptKeepsActiveApprovalAndRuntimeConnection() {
        assertEquals(
            DeviceAccessibilityServiceLifecycleDecision(
                disconnectApproval = false,
                detachRuntime = false,
                invalidateActiveWindow = true,
            ),
            DeviceAccessibilityServiceLifecyclePolicy.decide(
                DeviceAccessibilityServiceLifecycleEvent.FEEDBACK_INTERRUPTED,
            ),
        )
    }

    @Test
    fun serviceDestructionDisconnectsApprovalAndRuntime() {
        assertEquals(
            DeviceAccessibilityServiceLifecycleDecision(
                disconnectApproval = true,
                detachRuntime = true,
                invalidateActiveWindow = false,
            ),
            DeviceAccessibilityServiceLifecyclePolicy.decide(
                DeviceAccessibilityServiceLifecycleEvent.SERVICE_DESTROYED,
            ),
        )
    }
}
