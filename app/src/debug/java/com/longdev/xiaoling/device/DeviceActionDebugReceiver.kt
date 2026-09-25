package com.longdev.xiaoling.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DeviceActionDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val preferences = UiPreferenceStore(appContext)
            val controller = DeviceObservationComponents.controller(appContext)
            val previousEnabled = preferences.loadDeviceAgentEnabled()
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().ifBlank { "manual" }
            try {
                preferences.saveDeviceAgentEnabled(true)
                val result = execute(controller, intent)
                when (result) {
                    is DeviceActionCapture.Success -> Log.i(
                        TAG,
                        "action request=$requestId name=${result.outcome.action} success=true " +
                            "verified=${result.outcome.verified} package=${result.outcome.afterSnapshot.packageName} " +
                            "nodes=${result.outcome.afterSnapshot.nodes.size} " +
                            "refs=${result.outcome.afterSnapshot.nodes.count { it.ref != null }} " +
                            "redacted=${result.outcome.afterSnapshot.redactedNodeCount} " +
                            "truncated=${result.outcome.afterSnapshot.truncated}",
                    )
                    is DeviceActionCapture.Failed -> Log.w(
                        TAG,
                        "action request=$requestId success=false reason=${result.reason} message=${result.message}",
                    )
                }
            } finally {
                preferences.saveDeviceAgentEnabled(previousEnabled)
                controller.clearReferences()
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun execute(controller: DeviceObservationController, intent: Intent): DeviceActionCapture {
        return when (intent.getStringExtra(EXTRA_ACTION).orEmpty()) {
            ACTION_OPEN_APP -> controller.openApp(intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty())
            ACTION_BACK -> controller.back()
            ACTION_HOME -> controller.home()
            ACTION_TAP -> withExplicitOrTarget(controller, intent, DeviceNodeAction.TAP) { snapshotId, ref ->
                controller.tap(snapshotId, ref)
            }
            ACTION_TYPE_TEXT -> withExplicitOrTarget(controller, intent, DeviceNodeAction.TYPE_TEXT) { snapshotId, ref ->
                val text = intent.getStringExtra(EXTRA_TEXT_BASE64)?.let { encoded ->
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                } ?: intent.getStringExtra(EXTRA_TEXT).orEmpty()
                controller.typeText(snapshotId, ref, text)
            }
            ACTION_SWIPE -> {
                val direction = runCatching {
                    DeviceScrollDirection.valueOf(intent.getStringExtra(EXTRA_DIRECTION).orEmpty().uppercase())
                }.getOrNull() ?: return DeviceActionCapture.Failed(
                    DeviceActionFailure.ACTION_NOT_SUPPORTED,
                    "滚动方向必须是 up、down、left 或 right",
                )
                withExplicitOrTarget(controller, intent, DeviceNodeAction.SWIPE) { snapshotId, ref ->
                    controller.swipe(snapshotId, ref, direction)
                }
            }
            else -> DeviceActionCapture.Failed(DeviceActionFailure.ACTION_NOT_SUPPORTED, "未知 Debug 设备动作")
        }
    }

    private suspend fun withTarget(
        controller: DeviceObservationController,
        intent: Intent,
        requiredAction: DeviceNodeAction,
        block: suspend (snapshotId: String, ref: String) -> DeviceActionCapture,
    ): DeviceActionCapture {
        val capture = controller.capture()
        if (capture is DeviceSnapshotCapture.Failed) {
            return DeviceActionCapture.Failed(DeviceActionFailure.POST_ACTION_OBSERVATION_FAILED, capture.message)
        }
        capture as DeviceSnapshotCapture.Success
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty().trim()
        fun DeviceSnapshotNode.matchesTarget(): Boolean {
            return listOf(text, description, hint).filterNotNull().any { it == target }
        }
        fun DeviceSnapshotNode.isDescendantOf(ancestorIndex: Int): Boolean {
            var parent = parentIndex
            while (parent != null) {
                if (parent == ancestorIndex) return true
                parent = capture.snapshot.nodes.getOrNull(parent)?.parentIndex
            }
            return false
        }
        val node = capture.snapshot.nodes
            .filter { node ->
                requiredAction in node.actions && node.ref != null && (
                    target.isBlank() || node.matchesTarget() || capture.snapshot.nodes.any { child ->
                        child.isDescendantOf(node.index) && child.matchesTarget()
                    }
                    )
            }
            .maxByOrNull(DeviceSnapshotNode::depth)
            ?: return DeviceActionCapture.Failed(
            DeviceActionFailure.REFERENCE_NOT_FOUND,
            "当前快照没有匹配目标且支持 ${requiredAction.name.lowercase()} 的节点",
        )
        return block(capture.snapshot.snapshotId, requireNotNull(node.ref))
    }

    private suspend fun withExplicitOrTarget(
        controller: DeviceObservationController,
        intent: Intent,
        requiredAction: DeviceNodeAction,
        block: suspend (snapshotId: String, ref: String) -> DeviceActionCapture,
    ): DeviceActionCapture {
        val snapshotId = intent.getStringExtra(EXTRA_SNAPSHOT_ID).orEmpty().trim()
        val ref = intent.getStringExtra(EXTRA_REF).orEmpty().trim()
        if (snapshotId.isBlank() && ref.isNotBlank()) {
            val capture = controller.capture()
            if (capture is DeviceSnapshotCapture.Failed) {
                return DeviceActionCapture.Failed(DeviceActionFailure.POST_ACTION_OBSERVATION_FAILED, capture.message)
            }
            capture as DeviceSnapshotCapture.Success
            val currentNode = capture.snapshot.nodes.firstOrNull { node ->
                node.ref == ref && requiredAction in node.actions
            } ?: return DeviceActionCapture.Failed(
                DeviceActionFailure.REFERENCE_NOT_FOUND,
                "当前 snapshot 没有带有显式 ref 的可执行节点",
            )
            // long: 诊断调用只携带稳定短 ref 时，仍以本次 capture 的 snapshot 身份交给生产 Controller 做完整生命周期校验。
            return block(capture.snapshot.snapshotId, requireNotNull(currentNode.ref))
        }
        if (snapshotId.isNotBlank() || ref.isNotBlank()) {
            if (snapshotId.isBlank() || ref.isBlank()) {
                return DeviceActionCapture.Failed(
                    DeviceActionFailure.REFERENCE_NOT_FOUND,
                    "显式设备引用必须同时提供 snapshot_id 和 ref",
                )
            }
            // long: Debug 清理允许复用日志中刚刚取得的精确短期引用，但仍由生产 Controller 校验 snapshot 生命周期、窗口代次和动作类型。
            return block(snapshotId, ref)
        }
        return withTarget(controller, intent, requiredAction, block)
    }

    companion object {
        const val ACTION_EXECUTE = "com.longdev.xiaoling.debug.EXECUTE_DEVICE_ACTION"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_ACTION = "device_action"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TEXT_BASE64 = "text_base64"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_SNAPSHOT_ID = "snapshot_id"
        const val EXTRA_REF = "ref"
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_BACK = "back"
        const val ACTION_HOME = "home"
        const val ACTION_TAP = "tap_ref"
        const val ACTION_TYPE_TEXT = "type_text"
        const val ACTION_SWIPE = "swipe"
        private const val TAG = "XiaoLingDeviceAction"
    }
}
