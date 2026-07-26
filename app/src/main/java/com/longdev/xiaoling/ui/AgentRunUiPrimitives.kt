package com.longdev.xiaoling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepStatus

@Composable
internal fun AgentStatusChip(status: AgentRunStatus) {
    // long: 对话时间线和任务中心共享同一状态色与中文终态文案，避免同一个 Run 在两个入口呈现成不同业务结论。
    val color = when (status) {
        AgentRunStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AgentRunStatus.FAILED,
        AgentRunStatus.BUDGET_EXHAUSTED -> MaterialTheme.colorScheme.error
        AgentRunStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
        AgentRunStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = status.toUiLabel(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun AgentStepRow(
    status: AgentStepStatus,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentStepStatusIcon(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = status.toUiLabel(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = status.toUiColor(),
        )
    }
}

@Composable
private fun AgentStepStatusIcon(status: AgentStepStatus) {
    Box(
        modifier = Modifier.size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            AgentStepStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            AgentStepStatus.COMPLETED -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            AgentStepStatus.FAILED -> Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            AgentStepStatus.BLOCKED -> Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            AgentStepStatus.CANCELLED -> Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            AgentStepStatus.PENDING -> Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun AgentStepStatus.toUiColor(): Color {
    return when (this) {
        AgentStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AgentStepStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentStepStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
        AgentStepStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        AgentStepStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        AgentStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun AgentRunStatus.toUiLabel(): String {
    return when (this) {
        AgentRunStatus.QUEUED -> "排队"
        AgentRunStatus.THINKING -> "思考中"
        AgentRunStatus.WAITING_APPROVAL -> "待确认"
        AgentRunStatus.EXECUTING -> "执行中"
        AgentRunStatus.VERIFYING -> "验证中"
        AgentRunStatus.BLOCKED -> "待处理"
        AgentRunStatus.COMPLETED -> "已完成"
        AgentRunStatus.FAILED -> "失败"
        AgentRunStatus.CANCELLED -> "已取消"
        AgentRunStatus.BUDGET_EXHAUSTED -> "预算耗尽"
    }
}

internal fun AgentStepStatus.toUiLabel(): String {
    return when (this) {
        AgentStepStatus.PENDING -> "待处理"
        AgentStepStatus.RUNNING -> "进行中"
        AgentStepStatus.BLOCKED -> "待处理"
        AgentStepStatus.COMPLETED -> "完成"
        AgentStepStatus.FAILED -> "失败"
        AgentStepStatus.CANCELLED -> "取消"
    }
}
