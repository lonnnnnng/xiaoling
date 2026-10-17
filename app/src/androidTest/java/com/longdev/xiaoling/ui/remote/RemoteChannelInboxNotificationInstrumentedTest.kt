package com.longdev.xiaoling.ui.remote

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.remote.AndroidRemoteChannelInboxNotifier
import com.longdev.xiaoling.remote.RemoteChannelInboxNavigationIntent
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteChannelInboxNotificationInstrumentedTest {
    @Test fun notificationOnlyOpensInboxWithOneShotPrivateTokenAndNoMessagePayload() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("系统通知被用户关闭时不代为改动权限", manager.areNotificationsEnabled())
        val launchIntent = Intent(context, MainActivity::class.java)
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        val identity = "instrumentation-notification-${UUID.randomUUID()}"
        val notifier = AndroidRemoteChannelInboxNotifier(context)
        try {
            assertTrue(notifier.show(identity))
            val item = manager.activeNotifications.single { it.tag == identity }
            assertEquals("有远程消息待确认", item.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("点击查看待确认列表；打开后仍需你确认", item.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(item.notification.extras.toString().contains("batch-message"))
            assertEquals(Notification.VISIBILITY_PRIVATE, item.notification.visibility)
            item.notification.contentIntent.send()
            // long: 通知更新 Intent 后 ActivityScenario 会过滤生命周期事件；从真实可见窗口验收，避免测试规则的暂停状态遮蔽已完成的导航。
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val deadline = SystemClock.uptimeMillis() + 15_000
            var visible = false
            do {
                val root = automation.rootInActiveWindow
                visible = root?.packageName == context.packageName && root.containsText("远端身份配对") && root.containsText("待确认消息")
                if (!visible) SystemClock.sleep(100)
            } while (!visible && SystemClock.uptimeMillis() < deadline)
            assertTrue("通知没有打开真实待确认列表", visible)
            scenario.onActivity { assertFalse(it.intent.hasExtra(RemoteChannelInboxNavigationIntent.TOKEN)) }
            assertTrue(runCatching { item.notification.contentIntent.send() }.exceptionOrNull() is PendingIntent.CanceledException)
        } finally {
            notifier.cancel(identity)
            // long: 仅测试收尾恢复启动 Intent，让 ActivityScenario 识别销毁事件，不改变生产通知处理路径。
            scenario.onActivity { it.intent = launchIntent }
            scenario.close()
        }
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (text?.toString()?.contains(expected) == true) return true
        return (0 until childCount).any { getChild(it)?.containsText(expected) == true }
    }
}
