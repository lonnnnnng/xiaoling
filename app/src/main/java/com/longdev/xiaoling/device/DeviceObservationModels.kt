package com.longdev.xiaoling.device

data class DeviceBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class RawDeviceNode(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val bounds: DeviceBounds,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val nodePath: List<Int>,
    val children: List<RawDeviceNode>,
)

data class RawDeviceWindow(
    val packageName: String,
    val windowTitle: String?,
    val windowId: Int,
    val generation: Long,
    val root: RawDeviceNode,
    val truncated: Boolean = false,
)

enum class DeviceNodeAction {
    TAP,
    TYPE_TEXT,
    SWIPE,
}

data class DeviceSnapshotNode(
    val index: Int,
    val parentIndex: Int?,
    val depth: Int,
    val role: String,
    val text: String?,
    val description: String?,
    val hint: String?,
    val bounds: DeviceBounds,
    val enabled: Boolean,
    val checked: Boolean?,
    val selected: Boolean,
    val redacted: Boolean,
    val ref: String?,
    val actions: Set<DeviceNodeAction>,
)

data class DeviceSnapshot(
    val snapshotId: String,
    val packageName: String,
    val windowTitle: String?,
    val windowId: Int,
    val windowGeneration: Long,
    val capturedAt: Long,
    val expiresAt: Long,
    val nodes: List<DeviceSnapshotNode>,
    val redactedNodeCount: Int,
    val truncated: Boolean,
)

data class DeviceNodeReference(
    val ref: String,
    val nodePath: List<Int>,
    val fingerprint: String,
    val actions: Set<DeviceNodeAction>,
    val enabled: Boolean = true,
    val editable: Boolean = DeviceNodeAction.TYPE_TEXT in actions,
    val redacted: Boolean = false,
)

enum class DeviceSnapshotBlockReason {
    PRIVATE_APPLICATION,
    SENSITIVE_WINDOW,
}

sealed interface DeviceSnapshotAssessment {
    data class Available(
        val snapshot: DeviceSnapshot,
        val references: List<DeviceNodeReference>,
    ) : DeviceSnapshotAssessment

    data class Blocked(
        val reason: DeviceSnapshotBlockReason,
        val message: String,
    ) : DeviceSnapshotAssessment
}

enum class DeviceAgentHealthState {
    AGENT_DISABLED,
    ACCESSIBILITY_NOT_AUTHORIZED,
    SERVICE_DISCONNECTED,
    READY,
}

enum class DeviceSnapshotFailure {
    AGENT_DISABLED,
    ACCESSIBILITY_NOT_AUTHORIZED,
    SERVICE_DISCONNECTED,
    NO_ACTIVE_WINDOW,
    WINDOW_CHANGED,
    PRIVATE_APPLICATION,
    SENSITIVE_WINDOW,
}

sealed interface DeviceSnapshotCapture {
    data class Success(
        val snapshot: DeviceSnapshot,
        val references: List<DeviceNodeReference>,
    ) : DeviceSnapshotCapture

    data class Failed(
        val reason: DeviceSnapshotFailure,
        val message: String,
    ) : DeviceSnapshotCapture
}

object DeviceAgentHealthPolicy {
    fun evaluate(
        agentEnabled: Boolean,
        serviceAuthorized: Boolean,
        serviceConnected: Boolean,
    ): DeviceAgentHealthState {
        return when {
            !agentEnabled -> DeviceAgentHealthState.AGENT_DISABLED
            !serviceAuthorized -> DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED
            !serviceConnected -> DeviceAgentHealthState.SERVICE_DISCONNECTED
            else -> DeviceAgentHealthState.READY
        }
    }
}
