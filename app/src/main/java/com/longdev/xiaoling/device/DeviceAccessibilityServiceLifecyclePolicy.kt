package com.longdev.xiaoling.device

internal enum class DeviceAccessibilityServiceLifecycleEvent {
    FEEDBACK_INTERRUPTED,
    SERVICE_DESTROYED,
}

internal data class DeviceAccessibilityServiceLifecycleDecision(
    val disconnectApproval: Boolean,
    val detachRuntime: Boolean,
    val invalidateActiveWindow: Boolean,
)

internal object DeviceAccessibilityServiceLifecyclePolicy {
    fun decide(event: DeviceAccessibilityServiceLifecycleEvent): DeviceAccessibilityServiceLifecycleDecision {
        return when (event) {
            DeviceAccessibilityServiceLifecycleEvent.FEEDBACK_INTERRUPTED -> {
                // long: Android 的 onInterrupt 只表示当前无障碍反馈被打断；服务仍可能保持 Bound，不能据此撤销正在等待用户决定的审批。
                DeviceAccessibilityServiceLifecycleDecision(
                    disconnectApproval = false,
                    detachRuntime = false,
                    invalidateActiveWindow = true,
                )
            }

            DeviceAccessibilityServiceLifecycleEvent.SERVICE_DESTROYED -> {
                DeviceAccessibilityServiceLifecycleDecision(
                    disconnectApproval = true,
                    detachRuntime = true,
                    invalidateActiveWindow = false,
                )
            }
        }
    }
}
