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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AndroidCalendarEventReader
import com.longdev.xiaoling.agent.CalendarEventDetailReadResult
import com.longdev.xiaoling.agent.CalendarEventDetailRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal sealed interface CalendarEventDetailLoadState {
    data object Loading : CalendarEventDetailLoadState
    data class Content(val event: CalendarEventDetailRecord) : CalendarEventDetailLoadState
    data class Error(val message: String) : CalendarEventDetailLoadState
}

@Composable
internal fun CalendarEventDetailPage(
    eventId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loadState by produceState<CalendarEventDetailLoadState>(
        initialValue = CalendarEventDetailLoadState.Loading,
        key1 = eventId,
    ) {
        val numericId = CalendarNavigationPolicy.numericId(eventId)
        if (numericId == null) {
            value = CalendarEventDetailLoadState.Error("日程目标无效，无法读取当前详情")
            return@produceState
        }
        // long: 答案卡只携带稳定事件 ID；页面每次进入都重新查询当前 Calendar Provider，删除或撤权后绝不展示历史 Tool 正文。
        value = when (val result = AndroidCalendarEventReader(context.contentResolver).getEvent(numericId)) {
            is CalendarEventDetailReadResult.Success -> CalendarEventDetailLoadState.Content(result.event)
            CalendarEventDetailReadResult.NotFound -> CalendarEventDetailLoadState.Error("当前日程已不存在或已被删除")
            CalendarEventDetailReadResult.PermissionDenied -> CalendarEventDetailLoadState.Error("日历读取权限已撤销，请返回日历访问设置重新授权")
            CalendarEventDetailReadResult.ProviderUnavailable -> CalendarEventDetailLoadState.Error("系统日历服务暂不可用")
            CalendarEventDetailReadResult.Failed -> CalendarEventDetailLoadState.Error("读取当前日程详情失败，请稍后重试")
        }
    }

    CalendarEventDetailContent(
        state = loadState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun CalendarEventDetailContent(
    state: CalendarEventDetailLoadState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回设置",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "日程详情",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when (state) {
            CalendarEventDetailLoadState.Loading -> item {
                CalendarEventDetailBoundaryCard("正在从当前系统日历重新读取详情…")
            }

            is CalendarEventDetailLoadState.Error -> item {
                CalendarEventDetailBoundaryCard(state.message)
            }

            is CalendarEventDetailLoadState.Content -> item {
                CalendarEventCard(state.event)
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: CalendarEventDetailRecord) {
    // long: 用户核对页继续沿用 Agent 的最小字段边界，只展示日程身份、时间与重复状态，不把 Provider 的地点、描述或账户扩到 UI。
    val safeTimeZone = event.timeZoneId
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(100)
        ?.ifBlank { null }
    val zone = runCatching { ZoneId.of(safeTimeZone ?: ZoneId.systemDefault().id) }
        .getOrDefault(ZoneId.systemDefault())
    val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    val allDayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))
    val start = event.startAtMillis?.let { millis ->
        if (event.allDay) allDayFormatter.format(Instant.ofEpochMilli(millis))
        else dateTimeFormatter.format(Instant.ofEpochMilli(millis))
    } ?: "系统日历未提供"
    val end = event.endAtMillis?.let { millis ->
        val displayMillis = if (event.allDay && event.startAtMillis != null) {
            maxOf(event.startAtMillis, millis - 1L)
        } else {
            millis
        }
        if (event.allDay) allDayFormatter.format(Instant.ofEpochMilli(displayMillis))
        else dateTimeFormatter.format(Instant.ofEpochMilli(displayMillis))
    } ?: "系统日历未提供"

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = event.title.trim().replace(Regex("\\s+"), " ").take(200).ifBlank { "无标题日程" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CalendarEventField("事件 ID", "calendar-${event.eventId}")
            CalendarEventField("开始", start)
            CalendarEventField("结束", end)
            CalendarEventField("全天", if (event.allDay) "是" else "否")
            CalendarEventField("时区", safeTimeZone ?: "系统日历未提供")
            CalendarEventField("重复", if (event.recurring) "是" else "否")
            Text(
                text = "以上内容来自当前系统 Calendar Provider 的只读回读，不包含地点、描述、参与人或账户信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarEventField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.size(width = 52.dp, height = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CalendarEventDetailBoundaryCard(message: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
