package com.longdev.xiaoling.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DeviceSnapshotDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURE) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val preferences = UiPreferenceStore(appContext)
            val controller = DeviceObservationComponents.controller(appContext)
            val previousEnabled = preferences.loadDeviceAgentEnabled()
            val keepReferences = intent.getBooleanExtra(EXTRA_KEEP_REFERENCES, false)
            try {
                preferences.saveDeviceAgentEnabled(true)
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().ifBlank { "manual" }
                when (val capture = controller.capture()) {
                    is DeviceSnapshotCapture.Success -> {
                        if (intent.getBooleanExtra(EXTRA_PERSIST, false)) {
                            val safeRequestId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                            File(appContext.cacheDir, "device-snapshot-$safeRequestId.json")
                                .writeText(DeviceSnapshotCodec.encode(capture.snapshot))
                        }
                        if (intent.getBooleanExtra(EXTRA_DUMP_NODES, false)) {
                            // long: 仅在显式 Debug 诊断时输出已通过隐私过滤的可见节点，帮助确认前台任务是否具备稳定语义引用；不输出脱敏节点或原始窗口树。
                            capture.snapshot.nodes
                                .asSequence()
                                .filter { !it.redacted && (it.text.orEmpty().isNotBlank() || it.description.orEmpty().isNotBlank() || it.hint.orEmpty().isNotBlank()) }
                                .take(80)
                                .forEach { node ->
                                    Log.i(
                                        TAG,
                                        "snapshot request=$requestId node index=${node.index} ref=${node.ref.orEmpty()} " +
                                            "role=${node.role} text=${node.text.orEmpty()} description=${node.description.orEmpty()} hint=${node.hint.orEmpty()} " +
                                            "enabled=${node.enabled} checked=${node.checked} selected=${node.selected} " +
                                            "actions=${node.actions.joinToString(",")}",
                                    )
                                }
                        }
                        Log.i(
                            TAG,
                            "snapshot request=$requestId success=true snapshotId=${capture.snapshot.snapshotId} " +
                                "package=${capture.snapshot.packageName} " +
                                "nodes=${capture.snapshot.nodes.size} refs=${capture.references.size} " +
                                "redacted=${capture.snapshot.redactedNodeCount} truncated=${capture.snapshot.truncated}",
                        )
                    }
                    is DeviceSnapshotCapture.Failed -> {
                        Log.w(TAG, "snapshot request=$requestId success=false reason=${capture.reason} message=${capture.message}")
                    }
                }
            } finally {
                preferences.saveDeviceAgentEnabled(previousEnabled)
                if (!keepReferences) controller.clearReferences()
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.longdev.xiaoling.debug.CAPTURE_DEVICE_SNAPSHOT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PERSIST = "persist"
        const val EXTRA_DUMP_NODES = "dump_nodes"
        const val EXTRA_KEEP_REFERENCES = "keep_refs"
        private const val TAG = "XiaoLingDeviceSnapshot"
    }
}
