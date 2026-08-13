package com.longdev.xiaoling.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.notification.AndroidNotificationReader
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationReaderInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun currentNotificationsExposeOrdinaryTextHideVerificationCodeAndDisappearAfterRemoval() = runBlocking {
        assumeTrue("通知读取真机验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val reader = AndroidNotificationReader(context)
        assumeTrue("请先在系统通知访问设置中允许小灵", reader.accessGranted())
        waitUntil("NotificationListenerService 未连接") { reader.connected() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assumeTrue(
                "请先允许小灵发送测试通知",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }

        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val channelId = "notification-reader-test"
        manager.createNotificationChannel(
            NotificationChannel(channelId, "通知读取测试", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val marker = UUID.randomUUID().toString().take(8)
        val ordinaryNotificationId = marker.hashCode()
        val sensitiveNotificationId = ordinaryNotificationId + 1
        try {
            manager.notify(
                ordinaryNotificationId,
                Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("阶段258普通通知 $marker")
                    .setContentText("项目评审将在 14:00 开始")
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build(),
            )
            manager.notify(
                sensitiveNotificationId,
                Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("登录验证码 123456")
                    .setContentText("请勿向任何人泄露验证码")
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build(),
            )

            val current = waitForNotifications(reader) { records ->
                records.any { it.title?.contains(marker) == true } &&
                    records.any { it.packageName == context.packageName && it.contentHidden }
            }
            val ordinary = current.single { it.title?.contains(marker) == true }
            val sensitive = current.first { it.packageName == context.packageName && it.contentHidden }
            assertEquals(context.packageName, ordinary.packageName)
            assertEquals("项目评审将在 14:00 开始", ordinary.content)
            assertFalse(ordinary.contentHidden)
            assertNull(sensitive.title)
            assertNull(sensitive.content)

            val detail = reader.get(ordinary.id)
            assertTrue(detail is NotificationReadResult.Success)
            assertEquals(ordinary.id, (detail as NotificationReadResult.Success).notification.id)

            manager.cancel(ordinaryNotificationId)
            waitUntil("通知撤销后仍可从当前监听状态读取") {
                reader.get(ordinary.id) == NotificationReadResult.NotFound
            }
            assertNull(reader.list(10).firstOrNull { it.id == ordinary.id })
        } finally {
            manager.cancel(ordinaryNotificationId)
            manager.cancel(sensitiveNotificationId)
            manager.deleteNotificationChannel(channelId)
        }
    }

    private suspend fun waitForNotifications(
        reader: NotificationReader,
        predicate: (List<AgentNotificationRecord>) -> Boolean,
    ): List<AgentNotificationRecord> {
        var latest = emptyList<AgentNotificationRecord>()
        waitUntil("没有收到阶段258测试通知") {
            latest = reader.list(10)
            predicate(latest)
        }
        assertNotNull(latest)
        return latest
    }

    private suspend fun waitUntil(message: String, timeoutMs: Long = 10_000L, predicate: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(100L)
        }
        throw AssertionError(message)
    }
}
