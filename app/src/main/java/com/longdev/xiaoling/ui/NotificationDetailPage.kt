package com.longdev.xiaoling.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AgentNotificationRecord
import com.longdev.xiaoling.agent.NotificationPersonalTaskPolicy
import com.longdev.xiaoling.agent.NotificationReadResult
import com.longdev.xiaoling.notification.AndroidNotificationReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal sealed interface NotificationDetailLoadState {
    data object Loading : NotificationDetailLoadState
    data class Content(val notification: AgentNotificationRecord) : NotificationDetailLoadState
    data class Error(val message: String) : NotificationDetailLoadState
}

@Composable
internal fun NotificationDetailPage(
    target: NotificationNavigationTarget?,
    onBack: () -> Unit,
    taskDraftInProgress: Boolean,
    onCreatePersonalTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loadState by produceState<NotificationDetailLoadState>(
        initialValue = NotificationDetailLoadState.Loading,
        key1 = target,
    ) {
        val notificationId = target?.notificationId
        if (notificationId == null) {
            value = NotificationDetailLoadState.Error("通知目标无效，无法读取当前详情")
            return@produceState
        }
        // long: 点击答案级入口时重新检查授权、监听连接和内存快照，撤权或消失必须拒绝历史结果。
        value = when (val result = AndroidNotificationReader(context).get(notificationId)) {
            NotificationReadResult.AccessNotGranted -> NotificationDetailLoadState.Error("通知访问权限已撤销，请返回通知访问设置重新授权")
            NotificationReadResult.ListenerDisconnected -> NotificationDetailLoadState.Error("通知监听服务未连接，请返回通知访问设置刷新")
            NotificationReadResult.NotFound -> NotificationDetailLoadState.Error("当前通知已消失或不可读取")
            is NotificationReadResult.Success -> NotificationDetailLoadState.Content(result.notification)
        }
    }
    NotificationDetailContent(
        state = loadState,
        onBack = onBack,
        taskDraftInProgress = taskDraftInProgress,
        onCreatePersonalTask = onCreatePersonalTask,
        modifier = modifier,
    )
}

@Composable
internal fun NotificationDetailContent(
    state: NotificationDetailLoadState,
    onBack: () -> Unit,
    taskDraftInProgress: Boolean = false,
    onCreatePersonalTask: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
                }
                Text("通知详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        when (state) {
            NotificationDetailLoadState.Loading -> item { NotificationDetailBoundaryCard("正在从当前通知监听服务重新读取详情…") }
            is NotificationDetailLoadState.Error -> item { NotificationDetailBoundaryCard(state.message) }
            is NotificationDetailLoadState.Content -> {
                if (NotificationPersonalTaskPolicy.canCreateDraft(state.notification)) {
                    item {
                        Button(
                            onClick = { onCreatePersonalTask(state.notification.id) },
                            enabled = !taskDraftInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (taskDraftInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.AddTask, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = if (taskDraftInProgress) "正在准备任务草稿" else "转为任务",
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                    }
                }
                item { NotificationCard(state.notification) }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: AgentNotificationRecord) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(notification.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            NotificationField("时间", formatter.format(Instant.ofEpochMilli(notification.postedAt)))
            NotificationField("包名", notification.packageName)
            NotificationField("通知 ID", notification.id)
            NotificationField("标题", notification.title ?: if (notification.contentHidden) "已隐藏敏感或私密内容" else "无")
            NotificationField("正文", notification.content ?: if (notification.contentHidden) "已隐藏敏感或私密内容" else "无可读取文本")
            Text(
                "以上内容来自当前 NotificationListener 的只读回读；通知动作、回复和历史镜像均未开放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationField(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.size(width = 52.dp, height = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NotificationDetailBoundaryCard(message: String) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
