package com.longdev.xiaoling.ui.agenttask

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunMetricsPolicy
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.ui.AgentRunEventField
import com.longdev.xiaoling.ui.AgentRunRestartDispositionPresentation
import com.longdev.xiaoling.ui.AgentStatusChip
import com.longdev.xiaoling.ui.AgentStepRow
import com.longdev.xiaoling.ui.AgentTaskFilter
import com.longdev.xiaoling.ui.AgentToolCallPresentation
import com.longdev.xiaoling.ui.AgentToolDetailSource
import com.longdev.xiaoling.ui.AgentToolLedgerIssue
import com.longdev.xiaoling.ui.AgentToolStageState
import com.longdev.xiaoling.ui.latestRestartDispositionPresentation
import com.longdev.xiaoling.ui.matches
import com.longdev.xiaoling.ui.presentAgentRunEvent
import com.longdev.xiaoling.ui.presentAgentRunHistoryMetrics
import com.longdev.xiaoling.ui.presentAgentRunLlmMetrics
import com.longdev.xiaoling.ui.presentAgentRunMetrics
import com.longdev.xiaoling.ui.presentAgentTaskRetryEvidence
import com.longdev.xiaoling.ui.presentAgentToolLedger
import com.longdev.xiaoling.ui.toKnowledgeAuditText
import com.longdev.xiaoling.ui.toUiLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AgentTaskCenterPage(
    state: AgentTaskCenterUiState,
    actions: AgentTaskCenterActions,
    onBack: () -> Unit,
    initialFilter: AgentTaskFilter = AgentTaskFilter.ALL,
    modifier: Modifier = Modifier,
) {
    var taskFilter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var pendingNavigationRunId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (state.runs.isEmpty() && !state.loading) {
            actions.refreshAgentRunHistory()
        }
    }
    val filteredRuns = remember(state.runs, taskFilter) {
        state.runs.filter { item -> item.detail.matches(taskFilter) }
    }
    LaunchedEffect(pendingNavigationRunId, filteredRuns, state.runs) {
        val targetRunId = pendingNavigationRunId ?: return@LaunchedEffect
        if (state.runs.count { it.detail.snapshot.run.id == targetRunId } != 1) {
            pendingNavigationRunId = null
            return@LaunchedEffect
        }
        val targetIndex = filteredRuns.indexOfFirst { it.detail.snapshot.run.id == targetRunId }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex + 1)
            pendingNavigationRunId = null
        }
    }

    val navigateToRelatedRun: (String) -> Unit = { targetRunId ->
        // long: 关联导航只信任当前从 Room 投影出的唯一 Run；先恢复“全部”筛选再选中，避免目标被状态筛选隐藏时出现无反馈跳转。
        if (state.runs.count { it.detail.snapshot.run.id == targetRunId } == 1) {
            taskFilter = AgentTaskFilter.ALL
            pendingNavigationRunId = targetRunId
            actions.selectAgentRun(targetRunId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentRunHistoryHeader(
            loading = state.loading,
            onBack = onBack,
            onRefresh = actions::refreshAgentRunHistory,
        )
        AgentTaskFilterBar(
            selected = taskFilter,
            onSelected = { taskFilter = it },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.error != null -> item {
                    AgentTaskCenterSection(title = "读取失败") {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.loading && state.runs.isEmpty() -> item {
                    AgentTaskCenterSection(title = "Agent Run") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Text(
                                text = "正在读取 Agent 任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.runs.isEmpty() -> item {
                    AgentTaskCenterSection(title = "Agent Run") {
                        Text(
                            text = "还没有 Agent 任务。可以在对话框输入 /agent <目标> 触发一次任务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                filteredRuns.isEmpty() -> item {
                    val interruptedFilter = taskFilter == AgentTaskFilter.INTERRUPTED
                    AgentTaskCenterSection(title = if (interruptedFilter) "已中断" else "当前筛选") {
                        Text(
                            text = if (interruptedFilter) {
                                "当前没有失败或已取消的 Agent Run；恢复入口不会重放工具。"
                            } else {
                                "没有符合条件的 Agent 任务"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (interruptedFilter && state.runs.isNotEmpty()) {
                            TextButton(
                                onClick = { taskFilter = AgentTaskFilter.ALL },
                                modifier = Modifier.testTag("agent-task-show-all"),
                            ) {
                                Text("显示全部")
                            }
                        }
                    }
                }
                else -> {
                    item(key = "agent-run-metrics") {
                        AgentRunHistoryMetricsSummary(filteredRuns.map(AgentTaskCenterRunUiState::detail))
                    }
                    items(
                        count = filteredRuns.size,
                        key = { index -> filteredRuns[index].detail.snapshot.run.id },
                    ) { index ->
                        val item = filteredRuns[index]
                        val runId = item.detail.snapshot.run.id
                        AgentRunHistoryItemCard(
                            detail = item.detail,
                            selected = item.selected,
                            retrying = item.retrying,
                            onClick = { actions.selectAgentRun(runId) },
                            onRetry = { actions.requestAgentRunRetry(runId) },
                        )
                        if (item.selected) {
                            AgentRunDetailPanel(
                                item = item,
                                onNavigateToRun = navigateToRelatedRun,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRunHistoryMetricsSummary(details: List<AgentRunDetailRecord>) {
    val presentation = presentAgentRunHistoryMetrics(
        AgentRunMetricsPolicy.summarizeHistory(details, nowMs = System.currentTimeMillis()),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = presentation.headline,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = presentation.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        presentation.telemetry?.let { telemetry ->
            Text(
                text = telemetry,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = presentation.failureDistribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AgentTaskFilterBar(
    selected: AgentTaskFilter,
    onSelected: (AgentTaskFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AgentTaskFilter.entries.forEach { filter ->
            val active = filter == selected
            Surface(
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelected(filter) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(filter.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun AgentRunDetailRecord.matches(filter: AgentTaskFilter): Boolean {
    return filter.matches(
        status = snapshot.run.status,
        retryEligibility = AgentTaskRetryPolicy.evaluate(this),
    )
}

@Composable
private fun AgentRunHistoryHeader(
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
        }
        AgentTaskCenterPageTitle("Agent 任务中心")
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.size(30.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "刷新 Agent 任务", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AgentRunHistoryItemCard(
    detail: AgentRunDetailRecord,
    selected: Boolean,
    retrying: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val run = detail.snapshot.run
    val metrics = AgentRunMetricsPolicy.summarizeRun(detail, nowMs = System.currentTimeMillis())
    val retryEligibility = AgentTaskRetryPolicy.evaluate(detail)
    val restartDisposition = detail.latestRestartDispositionPresentation()
    val retryEvidence = presentAgentTaskRetryEvidence(
        AgentTaskRetryPolicy.assessEvidence(detail).code,
        restartRequired = restartDisposition != null,
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-task-run-${run.id}")
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.36f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(9.dp),
            )
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                AgentStatusChip(run.status)
                Text(
                    text = run.goal.ifBlank { "未命名任务" },
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = run.updatedAt.toFullTimeLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${detail.snapshot.steps.size} 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${detail.approvals.size} 次审批",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                run.completedAt?.let {
                    Text(
                        text = "完成 ${it.toFullTimeLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
                if (retryEligibility is AgentTaskRetryEligibility.Retryable) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !retrying,
                        contentPadding = PaddingValues(horizontal = 7.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        if (retrying) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                        } else {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = if (restartDisposition != null) "创建关联新 Run" else "重试 Agent Run",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when {
                                retrying && restartDisposition != null -> "创建中"
                                retrying -> "重试中"
                                restartDisposition != null -> "创建新 Run"
                                else -> "重试"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (retryEligibility is AgentTaskRetryEligibility.Retryable) {
                Text(
                    text = "${retryEvidence.label} · ${retryEvidence.code.name}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = if (retryEligibility.requiresConfirmation) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = retryEvidence.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "建议：${retryEvidence.suggestedAction}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = if (retryEligibility.requiresConfirmation) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            restartDisposition?.let { disposition ->
                AgentRunRestartDispositionGuidance(disposition)
            }
            Text(
                text = presentAgentRunMetrics(metrics),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            presentAgentRunLlmMetrics(metrics)?.let { telemetry ->
                Text(
                    text = telemetry,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentRunDetailPanel(
    item: AgentTaskCenterRunUiState,
    onNavigateToRun: (String) -> Unit,
) {
    val detail = item.detail
    val snapshot = detail.snapshot
    val metrics = AgentRunMetricsPolicy.summarizeRun(detail, nowMs = System.currentTimeMillis())
    val recoveryFailure = snapshot.events.asReversed().firstNotNullOfOrNull { event ->
        event.metadata as? RunEventMetadata.RecoveryFailure
    }
    val restartDisposition = detail.latestRestartDispositionPresentation()
    val toolPresentation = presentAgentToolLedger(detail)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = "Run ID：${snapshot.run.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            snapshot.run.retryOfRunId?.let { sourceRunId ->
                AgentRelatedRunLink(
                    prefix = "来源 Run",
                    runId = sourceRunId,
                    actionLabel = "查看来源 Run",
                    navigationRunId = item.sourceRunNavigationId,
                    onNavigateToRun = onNavigateToRun,
                )
            }
            item.linkedRetryRunNavigationId?.let { linkedRunId ->
                AgentRelatedRunLink(
                    prefix = "关联 Run",
                    runId = linkedRunId,
                    actionLabel = "查看关联 Run",
                    navigationRunId = linkedRunId,
                    onNavigateToRun = onNavigateToRun,
                )
            }
            snapshot.run.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            recoveryFailure?.let { failure ->
                AgentRecoveryFailureGuidance(failure)
            }
            restartDisposition?.let { disposition ->
                AgentRunRestartDispositionGuidance(disposition)
            }

            AgentRunDetailSection("运行指标") {
                Text(
                    text = presentAgentRunMetrics(metrics),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                presentAgentRunLlmMetrics(metrics)?.let { telemetry ->
                    Text(
                        text = telemetry,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AgentRunDetailSection("步骤") {
                snapshot.steps.forEach { step ->
                    AgentStepRow(status = step.status, title = step.title, detail = step.detail)
                }
            }

            if (toolPresentation.calls.isNotEmpty()) {
                AgentRunDetailSection("工具调用") {
                    Text(
                        text = when (toolPresentation.source) {
                            AgentToolDetailSource.LEDGER -> "数据源：独立工具账本"
                            AgentToolDetailSource.EVENT_FALLBACK -> "数据源：旧 Run 事件兼容"
                            AgentToolDetailSource.NONE -> "数据源：无工具记录"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    toolPresentation.issues.forEach { issue ->
                        AgentToolLedgerIssueRow(issue)
                    }
                    toolPresentation.calls.forEach { call ->
                        AgentToolCallRow(call)
                    }
                }
            }

            if (detail.approvals.isNotEmpty()) {
                AgentRunDetailSection("审批") {
                    detail.approvals.forEach { approval ->
                        ApprovalRequestRecordRow(approval)
                    }
                }
            }

            if (snapshot.events.isNotEmpty()) {
                AgentRunDetailSection("事件") {
                    snapshot.events.forEach { event ->
                        AgentRunEventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRelatedRunLink(
    prefix: String,
    runId: String,
    actionLabel: String,
    navigationRunId: String?,
    onNavigateToRun: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$prefix：$runId",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (navigationRunId != null) {
            TextButton(
                onClick = { onNavigateToRun(navigationRunId) },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .height(26.dp)
                    .testTag("agent-task-related-run-$navigationRunId"),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text(
                text = "当前历史不可用",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentRecoveryFailureGuidance(failure: RunEventMetadata.RecoveryFailure) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "恢复处理 · ${failure.toolName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = failure.code,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = failure.reason,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "建议：${failure.suggestedAction}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun AgentRunRestartDispositionGuidance(
    disposition: AgentRunRestartDispositionPresentation,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "恢复处置 · ${disposition.kind}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = disposition.code,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = disposition.reason,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "证据边界：${disposition.evidenceBoundary}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "建议：${disposition.suggestedAction}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun AgentRunDetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
private fun AgentToolLedgerIssueRow(issue: AgentToolLedgerIssue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "账本一致性告警 · ${issue.code}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = issue.message,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AgentToolCallRow(call: AgentToolCallPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = call.toolName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = call.risk?.toUiLabel() ?: "历史记录",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "调用：${call.id ?: "关联未知"} · ${call.createdAt.toFullTimeLabel()}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AgentToolStage("proposed", call.proposed, Modifier.weight(1f))
            AgentToolStage("validated", call.validated, Modifier.weight(1f))
            AgentToolStage("result", call.result, Modifier.weight(1f))
            AgentToolStage("verified", call.verified, Modifier.weight(1f))
        }
        if (call.arguments.isNotEmpty()) {
            Text(
                text = "参数：${call.arguments.entries.joinToString(" · ") { "${it.key}=${it.value}" }}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Executor 验证：" + when (call.executorVerified) {
                true -> "是"
                false -> "否"
                null -> "未提供"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        call.executionReceipt?.let { receipt ->
            Text(
                text = buildString {
                    append("操作：${receipt.operationId} · 回执：${receipt.status.name}")
                    call.replaySafety?.let { append(" · 重放：${it.name}") }
                    append(" · 幂等证明：${if (receipt.idempotencyKey.isNullOrBlank()) "未记录" else "已记录"}")
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (call.memoryIdsUsed.isNotEmpty()) {
            Text(
                text = "本次使用记忆：${call.memoryIdsUsed.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        call.knowledgeReferences.toKnowledgeAuditText()?.let { references ->
            Text(
                text = "本次知识引用：\n$references",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        call.resultContent?.let { content ->
            Text(
                text = content.ifBlank { "(空结果)" },
                style = MaterialTheme.typography.bodySmall,
                color = if (call.result == AgentToolStageState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        call.errorMessage?.takeIf { it.isNotBlank() && it != call.resultContent }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        call.durationMs?.let { durationMs ->
            Text(
                text = "执行耗时：${durationMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun AgentToolStage(
    label: String,
    state: AgentToolStageState,
    modifier: Modifier = Modifier,
) {
    val (icon, color) = when (state) {
        AgentToolStageState.COMPLETE -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        AgentToolStageState.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        AgentToolStageState.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ApprovalRequestRecordRow(approval: ApprovalRequestRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = approval.status.toUiLabel(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = approval.status.toUiColor(),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${approval.toolName} · ${approval.risk.toUiLabel()}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = (approval.decidedAt ?: approval.createdAt).toFullTimeLabel(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
        approval.decisionReason?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (approval.arguments.isNotEmpty()) {
            Text(
                text = approval.arguments.entries.joinToString(" · ") { "${it.key}=${it.value}" },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun AgentRunEventRow(event: RunEventRecord) {
    val presentation = remember(event.type, event.message, event.metadata) {
        presentAgentRunEvent(event.type, event.message, event.metadata)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = event.type,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = event.createdAt.toFullTimeLabel(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
        Text(
            text = presentation.summary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        presentation.fields.forEach { field ->
            AgentRunEventFieldRow(field)
        }
        presentation.rawFallback?.takeIf { it.isNotBlank() }?.let { raw ->
            Text(
                text = raw,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentRunEventFieldRow(field: AgentRunEventField) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Text(
            text = field.value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AgentTaskCenterPageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 1.dp),
    )
}

@Composable
private fun AgentTaskCenterSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.padding(7.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            content()
        }
    }
}

private fun ApprovalRequestStatus.toUiLabel(): String {
    return when (this) {
        ApprovalRequestStatus.PENDING -> "待确认"
        ApprovalRequestStatus.APPROVED -> "已批准"
        ApprovalRequestStatus.DENIED -> "已拒绝"
        ApprovalRequestStatus.EXPIRED -> "已过期"
        ApprovalRequestStatus.CANCELLED -> "已取消"
    }
}

@Composable
private fun ApprovalRequestStatus.toUiColor(): Color {
    return when (this) {
        ApprovalRequestStatus.APPROVED -> MaterialTheme.colorScheme.primary
        ApprovalRequestStatus.DENIED,
        ApprovalRequestStatus.EXPIRED -> MaterialTheme.colorScheme.error
        ApprovalRequestStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        ApprovalRequestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
}

private fun ToolRisk.toUiLabel(): String {
    return when (this) {
        ToolRisk.SAFE -> "低风险"
        ToolRisk.REQUIRES_APPROVAL -> "需确认"
        ToolRisk.DANGEROUS -> "高风险"
    }
}

private fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(AGENT_TASK_CENTER_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val AGENT_TASK_CENTER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
