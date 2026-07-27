package com.longdev.xiaoling.ui.processexit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.system.ProcessExitEvidenceKind
import com.longdev.xiaoling.system.ProcessExitObservation
import com.longdev.xiaoling.ui.CompactSection
import com.longdev.xiaoling.ui.PageTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ProcessExitObservationPage(
    state: ProcessExitObservationUiState,
    actions: ProcessExitObservationActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                    modifier = Modifier.size(18.dp),
                )
            }
            PageTitle("进程退出观察")
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = actions::refreshProcessExitObservations,
                enabled = !state.loading,
                modifier = Modifier.size(30.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新进程退出记录",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "process-exit-boundary") {
                CompactSection(title = "证据边界") {
                    Text(
                        text = EVIDENCE_BOUNDARY_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.error != null -> item(key = "process-exit-error") {
                    CompactSection(title = "读取失败") {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.loading && state.observations.isEmpty() -> item(key = "process-exit-loading") {
                    CompactSection(title = "系统记录") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Text("正在读取进程退出记录", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.observations.isEmpty() -> item(key = "process-exit-empty") {
                    CompactSection(title = "系统记录") {
                        Text(
                            text = "暂无进程退出记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(
                    count = state.observations.size,
                    key = { index -> state.observations[index].stableUiKey() },
                ) { index ->
                    ProcessExitObservationCard(state.observations[index])
                }
            }
        }
    }
}

@Composable
private fun ProcessExitObservationCard(observation: ProcessExitObservation) {
    val raw = observation.raw
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = observation.evidenceKind.toProcessExitEvidenceLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = observation.evidenceKind.toProcessExitEvidenceColor(),
            )
            Text(
                text = "${observation.reasonName} · PID ${raw.pid} · status ${raw.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = raw.processName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "退出 ${raw.timestamp.toFullTimeLabel()} · 首次观察 ${observation.observedAt.toFullTimeLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "importance ${raw.importance} · PSS ${raw.pssKb} KB · RSS ${raw.rssKb} KB · " +
                    if (observation.lowMemoryReportSupported) "支持直接 LMK 原因" else "不支持直接 LMK 原因",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ProcessExitObservation.stableUiKey(): String {
    val raw = raw
    // long: 列表身份沿用 Room 去重主键，刷新或重排不能把一条系统退出记录误认为另一条记录。
    return "${raw.timestamp}|${raw.pid}|${raw.reasonCode}|${raw.status}|${raw.processName}"
}

private fun ProcessExitEvidenceKind.toProcessExitEvidenceLabel(): String = when (this) {
    ProcessExitEvidenceKind.DIRECT_LOW_MEMORY -> "直接低内存回收证据"
    ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE -> "低内存回收候选"
    ProcessExitEvidenceKind.APP_FAILURE -> "应用故障"
    ProcessExitEvidenceKind.SYSTEM_RESOURCE -> "系统资源限制"
    ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE -> "受控退出或包维护"
    ProcessExitEvidenceKind.UNATTRIBUTED -> "未归因退出"
}

@Composable
private fun ProcessExitEvidenceKind.toProcessExitEvidenceColor(): Color = when (this) {
    ProcessExitEvidenceKind.DIRECT_LOW_MEMORY -> MaterialTheme.colorScheme.error
    ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE -> MaterialTheme.colorScheme.tertiary
    ProcessExitEvidenceKind.APP_FAILURE -> MaterialTheme.colorScheme.error
    ProcessExitEvidenceKind.SYSTEM_RESOURCE -> MaterialTheme.colorScheme.tertiary
    ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE -> MaterialTheme.colorScheme.primary
    ProcessExitEvidenceKind.UNATTRIBUTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val EVIDENCE_BOUNDARY_TEXT =
    "记录仅用于系统诊断，不关联 Agent Run、工作流或任务。受控退出和候选记录不能作为自然低内存回收结论。"
private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
