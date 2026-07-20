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
                        Log.i(
                            TAG,
                            "snapshot request=$requestId success=true package=${capture.snapshot.packageName} " +
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
                controller.clearReferences()
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_CAPTURE = "com.longdev.xiaoling.debug.CAPTURE_DEVICE_SNAPSHOT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PERSIST = "persist"
        private const val TAG = "XiaoLingDeviceSnapshot"
    }
}
