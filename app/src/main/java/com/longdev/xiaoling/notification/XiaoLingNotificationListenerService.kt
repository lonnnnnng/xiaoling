package com.longdev.xiaoling.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.longdev.xiaoling.agent.AgentNotificationRecord
import com.longdev.xiaoling.agent.NotificationPrivacyPolicy
import com.longdev.xiaoling.agent.NotificationReadResult
import com.longdev.xiaoling.agent.NotificationReader
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XiaoLingNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        synchronized(lock) {
            connectedService = this
            refreshLocked(activeNotifications.orEmpty())
        }
    }

    override fun onListenerDisconnected() {
        synchronized(lock) {
            if (connectedService === this) connectedService = null
            notificationsById = emptyMap()
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(lock) {
            notificationsById = notificationsById + (stableId(sbn.key) to sbn.toRecord(this))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(lock) {
            notificationsById = notificationsById - stableId(sbn.key)
        }
    }

    private fun refreshLocked(notifications: Array<out StatusBarNotification>) {
        // long: 当前通知只保存在监听服务进程内；服务断开立即清空，历史 Agent 消息不能把已经消失的通知重新变成当前事实。
        notificationsById = notifications.associate { notification ->
            stableId(notification.key) to notification.toRecord(this)
        }
    }

    companion object {
        private val lock = Any()
        private var connectedService: XiaoLingNotificationListenerService? = null
        private var notificationsById: Map<String, AgentNotificationRecord> = emptyMap()

        internal fun isConnected(): Boolean = synchronized(lock) { connectedService != null }

        internal fun snapshot(limit: Int): List<AgentNotificationRecord> = synchronized(lock) {
            notificationsById.values.sortedByDescending(AgentNotificationRecord::postedAt).take(limit.coerceIn(1, 10))
        }

        internal fun find(id: String): AgentNotificationRecord? = synchronized(lock) { notificationsById[id] }

        private fun stableId(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return "notification-${digest.joinToString("") { byte -> "%02x".format(byte) }}"
        }

        private fun StatusBarNotification.toRecord(context: Context): AgentNotificationRecord {
            val shouldHide = notification.visibility != Notification.VISIBILITY_PUBLIC ||
                notification.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_MESSAGE)
            val rawTitle = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
            val rawContent = notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: notification.extras?.getCharSequence(Notification.EXTRA_TEXT)
            val (sanitizedTitle, sanitizedContent) = if (shouldHide) {
                null to null
            } else {
                NotificationPrivacyPolicy.sanitizeNotification(rawTitle, rawContent)
            }
            val sensitiveContentDetected = !shouldHide &&
                ((rawTitle != null || rawContent != null) && sanitizedTitle == null && sanitizedContent == null)
            // long: 标题与正文属于同一通知隐私边界；任一字段命中验证码或凭据规则时整体隐藏，不能保留另一字段形成部分泄露。
            val title = sanitizedTitle.takeUnless { sensitiveContentDetected }
            val content = sanitizedContent.takeUnless { sensitiveContentDetected }
            val appLabel = runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
            return AgentNotificationRecord(
                id = stableId(key),
                appName = NotificationPrivacyPolicy.sanitize(appLabel) ?: "未知应用",
                packageName = packageName,
                postedAt = postTime,
                title = title,
                content = content,
                contentHidden = shouldHide || sensitiveContentDetected,
            )
        }
    }
}

class AndroidNotificationReader(private val context: Context) : NotificationReader {
    override fun accessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun connected(): Boolean = XiaoLingNotificationListenerService.isConnected()

    override suspend fun list(limit: Int): List<AgentNotificationRecord> = withContext(Dispatchers.Default) {
        if (!accessGranted() || !connected()) emptyList() else XiaoLingNotificationListenerService.snapshot(limit)
    }

    override suspend fun get(notificationId: String): NotificationReadResult = withContext(Dispatchers.Default) {
        when {
            !accessGranted() -> NotificationReadResult.AccessNotGranted
            !connected() -> NotificationReadResult.ListenerDisconnected
            else -> XiaoLingNotificationListenerService.find(notificationId)
                ?.let(NotificationReadResult::Success)
                ?: NotificationReadResult.NotFound
        }
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, XiaoLingNotificationListenerService::class.java)
    }
}
