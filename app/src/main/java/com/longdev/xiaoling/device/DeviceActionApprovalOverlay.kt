package com.longdev.xiaoling.device

import android.view.accessibility.AccessibilityEvent

internal const val DEVICE_ACTION_APPROVAL_OVERLAY_TITLE = "XiaoLingDeviceActionApproval"

data class DeviceActionApprovalOverlayRequest(
    val approvalRequestId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val userIntent: String,
    val toolDescription: String,
) {
    init {
        require(approvalRequestId.isNotBlank()) { "设备动作审批请求 ID 不能为空" }
        require(runId.isNotBlank()) { "设备动作审批 Run ID 不能为空" }
        require(toolCallId.isNotBlank()) { "设备动作审批 ToolCall ID 不能为空" }
        require(toolName.isNotBlank()) { "设备动作审批工具名不能为空" }
        require(userIntent.isNotBlank()) { "设备动作审批必须展示用户步骤意图" }
        require(toolDescription.isNotBlank()) { "设备动作审批工具说明不能为空" }
    }
}

enum class DeviceActionApprovalOverlayDecisionKind {
    APPROVED,
    DENIED,
    CANCELLED,
    SERVICE_DISCONNECTED,
    BUSY,
    WINDOW_CHANGED,
    OVERLAY_UNAVAILABLE,
}

data class DeviceActionApprovalOverlayDecision(
    val kind: DeviceActionApprovalOverlayDecisionKind,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "设备动作审批决定原因不能为空" }
    }

    val approved: Boolean
        get() = kind == DeviceActionApprovalOverlayDecisionKind.APPROVED
}

fun interface DeviceActionApprovalOverlayRequester {
    suspend fun request(request: DeviceActionApprovalOverlayRequest): DeviceActionApprovalOverlayDecision
}

data class DeviceAccessibilityWindowSnapshot(
    val id: Int,
    val ownedApprovalOverlay: Boolean,
)

sealed interface DeviceActionApprovalOverlayStart {
    data class Started(val token: Long) : DeviceActionApprovalOverlayStart
    data class Rejected(val decision: DeviceActionApprovalOverlayDecision) : DeviceActionApprovalOverlayStart
}

data class DeviceActionApprovalOverlayObservation(
    val suppressGenerationInvalidation: Boolean = false,
    val removeOverlay: Boolean = false,
    val completion: DeviceActionApprovalOverlayDecision? = null,
)

internal class DeviceActionApprovalOverlayCoordinator {
    private var nextToken = 0L
    private var active: ActiveRequest? = null

    @Synchronized
    fun begin(
        targetWindowId: Int,
        windows: Set<DeviceAccessibilityWindowSnapshot>,
    ): DeviceActionApprovalOverlayStart {
        if (active != null) {
            return DeviceActionApprovalOverlayStart.Rejected(
                decision(
                    DeviceActionApprovalOverlayDecisionKind.BUSY,
                    "已有设备动作审批正在显示，本次请求已取消",
                ),
            )
        }
        if (targetWindowId < 0 || windows.none { it.id == targetWindowId } || windows.any { it.ownedApprovalOverlay }) {
            return DeviceActionApprovalOverlayStart.Rejected(
                decision(
                    DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE,
                    "当前窗口状态不允许显示设备动作审批",
                ),
            )
        }
        val token = ++nextToken
        active = ActiveRequest(
            token = token,
            targetWindowId = targetWindowId,
            baselineWindows = windows.toSet(),
        )
        return DeviceActionApprovalOverlayStart.Started(token)
    }

    @Synchronized
    fun recordUserDecision(token: Long, approved: Boolean): Boolean {
        val current = active?.takeIf { it.token == token && it.pendingDecision == null } ?: return false
        current.pendingDecision = if (approved) {
            decision(DeviceActionApprovalOverlayDecisionKind.APPROVED, "用户已在设备动作审批浮层批准")
        } else {
            decision(DeviceActionApprovalOverlayDecisionKind.DENIED, "用户已在设备动作审批浮层拒绝")
        }
        return true
    }

    @Synchronized
    fun recordOverlayAdded(token: Long): Boolean {
        val current = active?.takeIf { it.token == token } ?: return false
        current.overlayViewAdded = true
        return true
    }

    @Synchronized
    fun observeWindows(
        eventWindowId: Int,
        eventType: Int,
        activeRootWindowId: Int,
        windows: Set<DeviceAccessibilityWindowSnapshot>,
    ): DeviceActionApprovalOverlayObservation {
        val current = active ?: return DeviceActionApprovalOverlayObservation()
        if (activeRootWindowId != current.targetWindowId) {
            return finishWindowChanged("审批期间活动页面已经切换")
        }
        if (
            eventWindowId == current.targetWindowId &&
            eventType in TARGET_CONTENT_INVALIDATING_EVENTS
        ) {
            return finishWindowChanged("审批期间目标页面内容已经变化")
        }

        val baseline = current.baselineWindows
        val baselineIds = baseline.mapTo(linkedSetOf(), DeviceAccessibilityWindowSnapshot::id)
        val newOwnedOverlays = windows.filter { it.ownedApprovalOverlay && it.id !in baselineIds }
        if (newOwnedOverlays.size > 1) {
            return finishWindowChanged("审批期间出现了多个无法区分的 Accessibility overlay")
        }
        val observedOverlay = newOwnedOverlays.singleOrNull()
        if (current.overlayWindowId == null && observedOverlay != null) {
            current.overlayWindowId = observedOverlay.id
        }
        if (
            observedOverlay != null &&
            current.overlayWindowId != null &&
            observedOverlay.id != current.overlayWindowId
        ) {
            return finishWindowChanged("审批期间 Accessibility overlay 身份发生变化")
        }

        val overlaySnapshot = current.overlayWindowId?.let { overlayId ->
            DeviceAccessibilityWindowSnapshot(overlayId, ownedApprovalOverlay = true)
        }
        val expectedWithOverlay = overlaySnapshot?.let { baseline + it }
        val baselineOnly = windows == baseline
        val ownedOverlayOnly = expectedWithOverlay != null && windows == expectedWithOverlay
        if (!baselineOnly && !ownedOverlayOnly) {
            return finishWindowChanged("审批期间出现了额外窗口或原窗口集合发生变化")
        }

        val pendingDecision = current.pendingDecision
        if (pendingDecision != null && baselineOnly) {
            val suppressDetachInvalidation = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                eventWindowId == current.targetWindowId &&
                (current.overlayWindowId != null || current.overlayViewAdded)
            active = null
            return DeviceActionApprovalOverlayObservation(
                suppressGenerationInvalidation = suppressDetachInvalidation,
                completion = pendingDecision,
            )
        }

        val suppress = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventWindowId == current.targetWindowId &&
            (
                current.overlayWindowId != null && ownedOverlayOnly ||
                    current.overlayViewAdded && pendingDecision != null && baselineOnly
            )
        return DeviceActionApprovalOverlayObservation(suppressGenerationInvalidation = suppress)
    }

    @Synchronized
    fun cancel(token: Long): DeviceActionApprovalOverlayDecision? {
        val current = active?.takeIf { it.token == token } ?: return null
        active = null
        return decision(DeviceActionApprovalOverlayDecisionKind.CANCELLED, "设备动作审批等待已取消")
    }

    @Synchronized
    fun disconnect(): DeviceActionApprovalOverlayDecision? {
        if (active == null) return null
        active = null
        return decision(DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED, "无障碍服务已断开，设备动作审批已取消")
    }

    @Synchronized
    fun detachTimedOut(token: Long): DeviceActionApprovalOverlayDecision? {
        active?.takeIf { it.token == token } ?: return null
        active = null
        return decision(DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE, "无法确认审批浮层已安全移除")
    }

    @Synchronized
    fun overlayAddFailed(token: Long): DeviceActionApprovalOverlayDecision? {
        active?.takeIf { it.token == token } ?: return null
        active = null
        return decision(DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE, "系统拒绝显示设备动作审批浮层")
    }

    private fun finishWindowChanged(reason: String): DeviceActionApprovalOverlayObservation {
        active = null
        return DeviceActionApprovalOverlayObservation(
            removeOverlay = true,
            completion = decision(DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED, reason),
        )
    }

    private fun decision(
        kind: DeviceActionApprovalOverlayDecisionKind,
        reason: String,
    ) = DeviceActionApprovalOverlayDecision(kind, reason)

    private data class ActiveRequest(
        val token: Long,
        val targetWindowId: Int,
        val baselineWindows: Set<DeviceAccessibilityWindowSnapshot>,
        var overlayViewAdded: Boolean = false,
        var overlayWindowId: Int? = null,
        var pendingDecision: DeviceActionApprovalOverlayDecision? = null,
    )

    private companion object {
        val TARGET_CONTENT_INVALIDATING_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )
    }
}
