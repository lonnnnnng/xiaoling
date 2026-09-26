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

class DeviceAutoApprovalDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROBE) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val preferences = UiPreferenceStore(context.applicationContext)
            val agentWasEnabled = preferences.loadDeviceAgentEnabled()
            val grantWasEnabled = preferences.loadDeviceActionAutoApprovalEnabled()
            try {
                preferences.saveDeviceAgentEnabled(true)
                preferences.saveDeviceActionAutoApprovalEnabled(true)
                val approved = DeviceAccessibilityRuntime.request(probeRequest("approved"))
                preferences.saveDeviceActionAutoApprovalEnabled(false)
                val revoked = DeviceAccessibilityRuntime.request(probeRequest("revoked"))
                // long: Debug 探针只输出决定类型与授权来源，不执行设备动作、不记录页面内容；生产仍由 Gate 绑定 ToolCall 和 Room 审计。
                Log.i(TAG, "approved=${approved.kind} source=${approved.reason} revoked=${revoked.kind} revokeReason=${revoked.reason}")
            } catch (error: Throwable) {
                Log.e(TAG, "probe_failed=${error::class.java.simpleName} message=${error.message}")
            } finally {
                preferences.saveDeviceActionAutoApprovalEnabled(false)
                preferences.saveDeviceAgentEnabled(agentWasEnabled)
                if (agentWasEnabled && grantWasEnabled) preferences.saveDeviceActionAutoApprovalEnabled(true)
                pending.finish()
                scope.cancel()
            }
        }
    }

    private fun probeRequest(suffix: String) = DeviceActionApprovalOverlayRequest(
        approvalRequestId = "debug-auto-$suffix",
        runId = "debug-auto-run",
        toolCallId = "debug-auto-call-$suffix",
        toolName = "device.open_app",
        userIntent = "验证设备动作免逐次审批",
        toolDescription = "仅验证窗口守护",
        actionSummary = "不执行设备动作",
        autoApprove = true,
    )

    private companion object {
        const val ACTION_PROBE = "com.longdev.xiaoling.debug.PROBE_DEVICE_AUTO_APPROVAL"
        const val TAG = "XiaoLingAutoApproval"
    }
}
