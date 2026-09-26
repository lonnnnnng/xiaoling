package com.longdev.xiaoling.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceActionAutoApprovalInstrumentedTest {
    @Test
    fun persistentGrantSettlesGuardedApprovalWithoutHumanTap() = runBlocking {
        assumeTrue(
            "仅显式 deviceAutoApprovalRealRun=true 时运行真实无障碍窗口测试",
            InstrumentationRegistry.getArguments().getString("deviceAutoApprovalRealRun") == "true",
        )
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = UiPreferenceStore(context)
        val agentWasEnabled = preferences.loadDeviceAgentEnabled()
        val grantWasEnabled = preferences.loadDeviceActionAutoApprovalEnabled()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            preferences.saveDeviceAgentEnabled(true)
            preferences.saveDeviceActionAutoApprovalEnabled(true)
            // long: 验收只使用真实无障碍服务和自有守护窗口，不替身模拟用户批准，才能证明设置授权确实消除了逐次点击。
            // long: Redmi instrumentation 启动前必须已连接该服务；测试本身不改写系统无障碍授权，避免影响用户启用的其他服务。
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            val deadline = SystemClock.uptimeMillis() + 15_000L
            while (!DeviceAccessibilityRuntime.isConnected() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100L)
            }
            assertTrue("小灵无障碍服务未连接", DeviceAccessibilityRuntime.isConnected())
            val decision = DeviceAccessibilityRuntime.request(
                DeviceActionApprovalOverlayRequest(
                    approvalRequestId = "auto-approval-test",
                    runId = "auto-approval-run",
                    toolCallId = "auto-approval-call",
                    toolName = "device.open_app",
                    userIntent = "验证免逐次审批设置",
                    toolDescription = "打开限定应用",
                    actionSummary = "只验证窗口守护，不执行设备动作",
                    autoApprove = true,
                ),
            )
            assertEquals(DeviceActionApprovalOverlayDecisionKind.APPROVED, decision.kind)
            assertTrue(decision.reason.contains("设置中授权"))
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            val revoked = DeviceAccessibilityRuntime.request(
                DeviceActionApprovalOverlayRequest(
                    approvalRequestId = "auto-approval-revoked",
                    runId = "auto-approval-run",
                    toolCallId = "auto-approval-revoked-call",
                    toolName = "device.open_app",
                    userIntent = "验证授权撤销",
                    toolDescription = "打开限定应用",
                    actionSummary = "撤销后不能自动批准",
                    autoApprove = true,
                ),
            )
            assertEquals(DeviceActionApprovalOverlayDecisionKind.CANCELLED, revoked.kind)
        } finally {
            scenario?.close()
            preferences.saveDeviceActionAutoApprovalEnabled(false)
            preferences.saveDeviceAgentEnabled(agentWasEnabled)
            if (agentWasEnabled && grantWasEnabled) preferences.saveDeviceActionAutoApprovalEnabled(true)
            automation.clearCache()
        }
    }
}
