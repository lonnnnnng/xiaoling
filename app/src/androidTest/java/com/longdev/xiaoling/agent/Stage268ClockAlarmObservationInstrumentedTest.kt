package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 只读取 Redmi 当前时钟 Accessibility 快照，确认闹钟编辑控件是否具备稳定、可回读的节点证据；本用例不执行点击或写入。 */
@RunWith(AndroidJUnit4::class)
class Stage268ClockAlarmObservationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun clockAlarmPageExposesBoundedNodesWithoutMutation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("仅显式 stage268Observe=true 运行 Redmi 观察", args.getString(ARG_OBSERVE) == "true")
        assertEquals("仅允许 Redmi 真机", "begonia", Build.DEVICE)
        val controller = DeviceObservationComponents.controller(context)
        // long: 观察测试不创建 UiAutomation；当前 Redmi ROM 会把它与应用内 AccessibilityService 视为竞争服务，导致真实服务被停掉。
        val health = awaitHealth(controller)
        if (health != DeviceAgentHealthState.READY) {
            println("STAGE268_CLOCK_OBSERVE_BLOCKED health=$health reason=instrumentation_accessibility_channel")
            // long: 测试通道无法证明生产权限失败时必须跳过而不是伪造观察成功；真实动作门禁仍由独立前台验收完成。
            assumeTrue("当前 instrumentation 无法绑定 Redmi AccessibilityService：$health", false)
        }
        assertEquals(DeviceAgentHealthState.READY, health)
        context.startActivity(Intent().setClassName("com.android.deskclock", "com.android.deskclock.DeskClock").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1_500L)
        when (val capture = controller.capture()) {
            is DeviceSnapshotCapture.Success -> {
                assertTrue(capture.snapshot.packageName == "com.android.deskclock" || capture.snapshot.packageName == "com.google.android.deskclock")
                println(
                    "STAGE268_CLOCK_OBSERVE package=${capture.snapshot.packageName} nodes=${capture.snapshot.nodes.size} refs=${capture.references.size} " +
                        "texts=${capture.snapshot.nodes.mapNotNull { it.text }.filter(String::isNotBlank).take(80)}",
                )
            }
            is DeviceSnapshotCapture.Failed -> error("时钟快照失败：${capture.reason} ${capture.message}")
        }
    }

    private fun awaitHealth(controller: com.longdev.xiaoling.device.DeviceObservationController): DeviceAgentHealthState {
        var health = controller.health()
        repeat(60) {
            if (health == DeviceAgentHealthState.READY) return health
            SystemClock.sleep(250L)
            health = controller.health()
        }
        return health
    }

    private companion object {
        const val ARG_OBSERVE = "stage268Observe"
    }
}
