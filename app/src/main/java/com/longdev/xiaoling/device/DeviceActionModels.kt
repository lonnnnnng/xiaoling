package com.longdev.xiaoling.device

enum class DeviceGlobalAction {
    BACK,
    HOME,
}

enum class DeviceScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

enum class DeviceActionFailure {
    AGENT_DISABLED,
    ACCESSIBILITY_NOT_AUTHORIZED,
    SERVICE_DISCONNECTED,
    APP_NOT_ALLOWED,
    APP_NOT_AVAILABLE,
    SNAPSHOT_NOT_FOUND,
    REFERENCE_NOT_FOUND,
    REFERENCE_EXPIRED,
    WINDOW_CHANGED,
    NODE_NOT_FOUND,
    NODE_CHANGED,
    ACTION_NOT_SUPPORTED,
    ACTION_FAILED,
    SENSITIVE_INPUT,
    POST_ACTION_OBSERVATION_FAILED,
}

data class DeviceActionOutcome(
    val action: String,
    val beforeSnapshotId: String?,
    val afterSnapshot: DeviceSnapshot,
    val verified: Boolean,
    val message: String,
    val typeTextReadBack: DeviceTypeTextReadBack? = null,
)

data class DeviceTypeTextReadBack(
    val nodePath: List<Int>,
    val text: String?,
)

sealed interface DeviceActionCapture {
    data class Success(val outcome: DeviceActionOutcome) : DeviceActionCapture

    data class Failed(
        val reason: DeviceActionFailure,
        val message: String,
    ) : DeviceActionCapture
}

sealed interface RawDeviceActionResult {
    data object Performed : RawDeviceActionResult
    data object WindowChanged : RawDeviceActionResult
    data object NodeNotFound : RawDeviceActionResult
    data object NodeChanged : RawDeviceActionResult
    data object ActionNotSupported : RawDeviceActionResult
    data object Failed : RawDeviceActionResult
}

data class DeviceReferenceTargetInspection(
    val enabled: Boolean,
    val editable: Boolean,
    val redacted: Boolean,
    val actions: Set<DeviceNodeAction>,
)

data class DeviceReferenceInspection(
    val currentWindowGeneration: Long,
    val matched: Boolean,
    val target: DeviceReferenceTargetInspection? = null,
)

interface DeviceController : DeviceSnapshotProvider {
    // long: open_app/back/home 不消费节点引用，但仍必须读取真实窗口代次参与动作前门禁；实现方需显式提供来源，禁止用空 snapshot/ref 形成隐式协议。
    fun currentWindowGeneration(): Long

    fun inspectReference(snapshotId: String, ref: String): DeviceReferenceInspection =
        DeviceReferenceInspection(currentWindowGeneration = -1L, matched = false)

    suspend fun openApp(packageName: String): DeviceActionCapture
    suspend fun back(): DeviceActionCapture
    suspend fun home(): DeviceActionCapture
    suspend fun tap(snapshotId: String, ref: String): DeviceActionCapture
    suspend fun typeText(snapshotId: String, ref: String, text: String): DeviceActionCapture
    suspend fun swipe(snapshotId: String, ref: String, direction: DeviceScrollDirection): DeviceActionCapture
}
