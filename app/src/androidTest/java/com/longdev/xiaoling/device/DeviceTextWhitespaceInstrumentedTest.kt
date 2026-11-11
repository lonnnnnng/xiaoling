package com.longdev.xiaoling.device

import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DeviceTextWhitespaceInstrumentedTest {
    @Test fun nativeMultilineInputUsesExactTransientReadBackWithoutCodecDisclosure() = runBlocking {
        check(android.os.Build.DEVICE == "lmi") { "本项目真机验收仅允许指定 POCO" }
        val automation = requireNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = AndroidDeviceAccessibilityGateway(context)
        // long: instrumentation 重建宿主后只重新绑定已在系统列表中的本应用服务，不新增其他权限或覆盖其他服务选择。
        com.longdev.xiaoling.agent.AndroidDeviceLiveInstrumentedTest().rebindPreviouslyAuthorizedService(context, automation, native, 301)
        val nonce = UUID.randomUUID().toString()
        val intent = Intent(context, DevicePrivacyProbeActivity::class.java)
            .putExtra(DevicePrivacyProbeActivity.EXTRA_LIVE_NONCE, nonce)
            .putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_TYPE_TEXT)
        ActivityScenario.launch<DevicePrivacyProbeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                (root.getChildAt(1) as EditText).apply { isSingleLine = false; setText("ready") }
            }
            val controller = DeviceObservationController({ true }, native, actionPolicy = DeviceActionPolicy(setOf(context.packageName)))
            withTimeout(15_000) { while (controller.health() != DeviceAgentHealthState.READY) delay(100) }
            for (text in listOf(" a  b ", "第一段\n第二段\t末尾  ")) {
                val captured = withTimeout(10_000) {
                    var result = controller.capture()
                    while (result !is DeviceSnapshotCapture.Success) { delay(100); result = controller.capture() }
                    result as DeviceSnapshotCapture.Success
                }
                val target = captured.references.single { DeviceNodeAction.TYPE_TEXT in it.actions }
                // long: 只对本轮公开 Probe 的编辑框发送固定非敏感测试文本；不读取或输入用户应用，不调用模型或持久化原文。
                val result = controller.typeText(captured.snapshot.snapshotId, target.ref, text)
                assertTrue(result.toString(), result is DeviceActionCapture.Success)
                val outcome = (result as DeviceActionCapture.Success).outcome
                assertTrue(outcome.message, outcome.verified)
                assertEquals(text, outcome.typeTextReadBack?.text)
                assertFalse(DeviceActionCodec.encode(outcome).contains(text.trim().substringBefore('\n')))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout
                    assertEquals(text, (root.getChildAt(1) as EditText).text.toString())
                }
            }
            controller.clearReferences()
        }
    }
}
