package com.longdev.xiaoling.automation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.longdev.xiaoling.R

class ScheduledTaskNotifier(
    private val context: Context,
) {
    private val navigationTokenStore = ScheduledTaskResultNavigationTokenStore(context.applicationContext)

    fun notify(workflowName: String, task: ScheduledTaskRecord, detail: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        createChannel(manager)
        if (!canNotify(manager)) return false
        val title = when (task.status) {
            ScheduledTaskStatus.COMPLETED -> "工作流已完成 · $workflowName"
            ScheduledTaskStatus.BLOCKED -> "工作流需要你处理 · $workflowName"
            else -> "工作流执行失败 · $workflowName"
        }
        val pendingIntent = ScheduledTaskResultNavigationPolicy.targetFor(task)
            ?.let(navigationTokenStore::issue)
            ?.let { token ->
                ScheduledTaskResultNavigationIntent.create(context, token)?.let { intent ->
                    PendingIntent.getActivity(
                        context,
                        token.hashCode(),
                        intent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_xiaoling_notification)
            .setContentTitle(title)
            .setContentText(detail.take(180))
            .setStyle(Notification.BigTextStyle().bigText(detail.take(600)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply {
                // long: 私有令牌无法同步落盘时仍保留结果通知，但不提供可点击跳转；安全失败不能退化为携带裸 workflowId 的外部 Intent。
                pendingIntent?.let(::setContentIntent)
            }
            .build()
        manager.notify(task.id.hashCode(), notification)
        return true
    }

    private fun canNotify(manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "工作流结果",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "定时工作流的完成、失败和待处理结果"
        }
        // long: Channel 可以重复注册，但首次创建后的打扰级别由用户控制；应用只保持稳定 ID，不在后台偷偷重置用户选择。
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "workflow_results"
    }
}
