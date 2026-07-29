package com.longdev.xiaoling.ui.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.automation.ScheduledTaskPolicy
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.ScheduledTaskType
import com.longdev.xiaoling.automation.WorkflowDefinitionPolicy
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowScheduleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun WorkflowManagementPage(
    state: WorkflowManagementUiState,
    actions: WorkflowManagementActions,
    onRequestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingWorkflow by remember { mutableStateOf<WorkflowItemUiState?>(null) }
    var schedulingWorkflowId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (state.items.isEmpty() && !state.loading) actions.refreshWorkflows()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
            }
            WorkflowPageTitle("工作流")
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = actions::refreshWorkflows,
                enabled = !state.loading,
                modifier = Modifier.size(30.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新工作流", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, contentDescription = "新建工作流", modifier = Modifier.size(18.dp))
            }
        }

        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            when {
                state.loading && state.items.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.6.dp)
                    }
                }
                state.items.isEmpty() -> item {
                    Text(
                        "还没有工作流",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                else -> items(
                    count = state.items.size,
                    key = { index -> state.items[index].id },
                ) { index ->
                    val workflow = state.items[index]
                    WorkflowItem(
                        state = workflow,
                        onEnabledChange = { enabled -> actions.setWorkflowEnabled(workflow.id, enabled) },
                        onEdit = { editingWorkflow = workflow },
                        onRun = { actions.runWorkflow(workflow.id) },
                        onRetryRun = actions::requestWorkflowRunRetry,
                        onSchedule = { schedulingWorkflowId = workflow.id },
                        onCancelScheduledTask = actions::cancelScheduledTask,
                        onCancelWorkflowSchedule = actions::cancelWorkflowSchedule,
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        WorkflowEditorDialog(
            workflow = null,
            onConfirm = { name, stepGoals ->
                actions.createWorkflow(name, stepGoals)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    editingWorkflow?.let { workflow ->
        WorkflowEditorDialog(
            workflow = workflow,
            onConfirm = { name, stepGoals ->
                actions.updateWorkflow(workflow.id, name, stepGoals)
                editingWorkflow = null
            },
            onDismiss = { editingWorkflow = null },
        )
    }
    schedulingWorkflowId?.let { workflowId ->
        val workflow = state.items.firstOrNull { it.id == workflowId }
        if (workflow != null) {
            WorkflowScheduleDialog(
                workflowName = workflow.name,
                existingSchedule = workflow.schedule,
                scheduling = workflow.scheduling,
                onConfirmOnce = { delayMinutes ->
                    // long: 调度动作可能转入后台，先把通知权限请求交给 Activity 宿主，模块本身不持有 Android launcher。
                    onRequestNotificationPermission()
                    actions.scheduleWorkflowOnce(workflowId, delayMinutes)
                    schedulingWorkflowId = null
                },
                onConfirmRecurring = { type, hour, minute, dayOfWeek ->
                    onRequestNotificationPermission()
                    actions.scheduleWorkflowRecurring(workflowId, type, hour, minute, dayOfWeek)
                    schedulingWorkflowId = null
                },
                onDismiss = { schedulingWorkflowId = null },
            )
        }
    }
}

@Composable
private fun WorkflowItem(
    state: WorkflowItemUiState,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onRetryRun: (String) -> Unit,
    onSchedule: () -> Unit,
    onCancelScheduledTask: (String) -> Unit,
    onCancelWorkflowSchedule: (String) -> Unit,
) {
    var expanded by remember(state.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workflow-item-${state.id}")
            .clip(RoundedCornerShape(7.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.latestRun?.let { "最近：${workflowStatusLabel(it.status.name)} · ${it.createdAt.toFullTimeLabel()}" }
                            ?: "尚未执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onEdit,
                    enabled = state.canEdit,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑工作流", modifier = Modifier.size(17.dp))
                }
                IconButton(
                    onClick = onRun,
                    enabled = state.canRun,
                    modifier = Modifier.size(30.dp),
                ) {
                    if (state.running) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "手动运行", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(
                    onClick = onSchedule,
                    enabled = state.canSchedule,
                    modifier = Modifier.size(30.dp),
                ) {
                    if (state.scheduling) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp)
                    } else {
                        Icon(Icons.Default.Schedule, contentDescription = "创建计划", modifier = Modifier.size(17.dp))
                    }
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = state.canToggleEnabled,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
            }
            Text(
                state.primaryGoal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("步骤定义", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                state.stepGoals.forEachIndexed { index, goal ->
                    Text("${index + 1}. $goal", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.schedule?.takeIf { it.enabled }?.let { schedule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        schedule.toWorkflowScheduleLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onCancelWorkflowSchedule(schedule.id) },
                        enabled = schedule.canCancel,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "停用周期计划", modifier = Modifier.size(16.dp))
                    }
                }
            }
            state.scheduledTasks.firstOrNull()?.let { task ->
                Text(
                    "计划：${task.plannedAt.toFullTimeLabel()} · ${task.status.toScheduledTaskStatusLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.status == ScheduledTaskStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (expanded && state.scheduledTasks.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("调度实例", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                state.scheduledTasks.forEach { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${if (task.type == ScheduledTaskType.RECURRING) "周期" else "一次"} · ${task.plannedAt.toFullTimeLabel()} · ${task.status.toScheduledTaskStatusLabel()}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            task.actualStartedAt?.let { actual ->
                                Text(
                                    "实际 ${actual.toFullTimeLabel()} · 偏差 ${formatScheduleDelay(actual - task.plannedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            task.errorMessage?.let { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (task.status == ScheduledTaskStatus.STOP_REQUESTED) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            task.workerStopReasonName?.let { name ->
                                Text(
                                    "系统停止原因：$name（${task.workerStopReasonCode ?: "未知"}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (task.canCancel) {
                            IconButton(
                                onClick = { onCancelScheduledTask(task.id) },
                                enabled = !task.mutating,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = if (task.status == ScheduledTaskStatus.RUNNING) {
                                        Icons.Default.StopCircle
                                    } else {
                                        Icons.Default.Close
                                    },
                                    contentDescription = if (task.status == ScheduledTaskStatus.RUNNING) {
                                        "停止运行"
                                    } else {
                                        "取消计划"
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (expanded && state.runs.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                state.runs.forEachIndexed { index, run ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${run.createdAt.toFullTimeLabel()} · ${workflowStatusLabel(run.status.name)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Run：${run.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            run.retryOfWorkflowRunId?.let { sourceRunId ->
                                Text("来源 Run：$sourceRunId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (run.canRetry) {
                            IconButton(
                                onClick = { onRetryRun(run.id) },
                                enabled = !state.running,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = "重试 Workflow Run", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    run.steps.forEach { step ->
                        Text(
                            "${step.sequence}. ${step.title} · ${step.statusLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("目标：${step.goal}", style = MaterialTheme.typography.labelSmall)
                        step.previousOutputs.takeIf { it.isNotEmpty() }?.let { outputs ->
                            Text(
                                "前序输入：${outputs.joinToString(separator = "\n")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        step.output?.let { output ->
                            Text(
                                "输出：$output",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        step.deviceObservations.forEachIndexed { observationIndex, observation ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("workflow-device-observation-${run.id}-${step.sequence}-$observationIndex"),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "设备观察 · ${observation.verificationLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "应用：${observation.packageName}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        "节点 ${observation.nodeCount} · 脱敏 ${observation.redactedNodeCount} · " +
                                            if (observation.truncated) "已截断" else "未截断",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "采集：${observation.capturedAt.toFullTimeLabel()} · 执行 ${observation.durationMs} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // long: 历史 Workflow 只用于复核当时的观察结果；持久化 ref 已脱离原窗口世代，任何后续动作都必须重新 snapshot。
                                    Text(
                                        "节点引用已过期，不可用于后续动作",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        step.reusedFromStepId?.let { sourceStepId ->
                            Text("复用步骤：$sourceStepId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    run.result?.takeIf { it.isNotBlank() }?.let { result ->
                        Text("结果：$result", style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    run.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            "失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    run.workerStopReasonName?.let { name ->
                        Text(
                            "系统停止原因：$name（${run.workerStopReasonCode ?: "未知"}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != state.runs.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowScheduleDialog(
    workflowName: String,
    existingSchedule: WorkflowScheduleUiState?,
    scheduling: Boolean,
    onConfirmOnce: (Int) -> Unit,
    onConfirmRecurring: (WorkflowScheduleType, Int, Int, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(workflowName, existingSchedule?.updatedAt) {
        mutableStateOf(
            when (existingSchedule?.type) {
                WorkflowScheduleType.DAILY -> WorkflowScheduleMode.DAILY
                WorkflowScheduleType.WEEKLY -> WorkflowScheduleMode.WEEKLY
                null -> WorkflowScheduleMode.ONE_TIME
            },
        )
    }
    var delayMinutes by remember(workflowName) { mutableStateOf("1") }
    var hour by remember(workflowName, existingSchedule?.updatedAt) {
        mutableStateOf(existingSchedule?.let { (it.timeOfDayMinutes / 60).toString().padStart(2, '0') } ?: "09")
    }
    var minute by remember(workflowName, existingSchedule?.updatedAt) {
        mutableStateOf(existingSchedule?.let { (it.timeOfDayMinutes % 60).toString().padStart(2, '0') } ?: "00")
    }
    var dayOfWeek by remember(workflowName, existingSchedule?.updatedAt) {
        mutableIntStateOf(existingSchedule?.dayOfWeek ?: 1)
    }
    val parsedDelay = delayMinutes.toIntOrNull()
    val parsedHour = hour.toIntOrNull()
    val parsedMinute = minute.toIntOrNull()
    val valid = when (mode) {
        WorkflowScheduleMode.ONE_TIME -> parsedDelay != null && parsedDelay in ScheduledTaskPolicy.MIN_DELAY_MINUTES..ScheduledTaskPolicy.MAX_DELAY_MINUTES
        WorkflowScheduleMode.DAILY,
        WorkflowScheduleMode.WEEKLY -> parsedHour != null && parsedHour in 0..23 && parsedMinute != null && parsedMinute in 0..59
    }
    AlertDialog(
        onDismissRequest = { if (!scheduling) onDismiss() },
        title = { Text("创建计划", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(workflowName, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WorkflowScheduleMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                when (mode) {
                    WorkflowScheduleMode.ONE_TIME -> WorkflowCompactTextField(
                        value = delayMinutes,
                        onValueChange = { value -> delayMinutes = value.filter(Char::isDigit).take(5) },
                        label = "延迟分钟",
                        placeholder = "${ScheduledTaskPolicy.MIN_DELAY_MINUTES} - ${ScheduledTaskPolicy.MAX_DELAY_MINUTES}",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    WorkflowScheduleMode.DAILY,
                    WorkflowScheduleMode.WEEKLY -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkflowCompactTextField(
                                value = hour,
                                onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                                label = "小时",
                                placeholder = "00 - 23",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            WorkflowCompactTextField(
                                value = minute,
                                onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                                label = "分钟",
                                placeholder = "00 - 59",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (mode == WorkflowScheduleMode.WEEKLY) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                (1..7).forEach { day ->
                                    FilterChip(
                                        selected = dayOfWeek == day,
                                        onClick = { dayOfWeek = day },
                                        label = { Text(day.toWeekdayLabel(), style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    when (mode) {
                        WorkflowScheduleMode.ONE_TIME -> "这是非精确定时，系统可能在计划时间后延迟执行。"
                        else -> "按当前系统时区保存；每次触发都会生成独立记录，系统可能延迟执行。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        WorkflowScheduleMode.ONE_TIME -> parsedDelay?.let(onConfirmOnce)
                        WorkflowScheduleMode.DAILY -> if (parsedHour != null && parsedMinute != null) {
                            onConfirmRecurring(WorkflowScheduleType.DAILY, parsedHour, parsedMinute, null)
                        }
                        WorkflowScheduleMode.WEEKLY -> if (parsedHour != null && parsedMinute != null) {
                            onConfirmRecurring(WorkflowScheduleType.WEEKLY, parsedHour, parsedMinute, dayOfWeek)
                        }
                    }
                },
                enabled = valid && !scheduling,
            ) { Text(if (scheduling) "创建中" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !scheduling) { Text("取消") } },
    )
}

private enum class WorkflowScheduleMode(val label: String) {
    ONE_TIME("一次"),
    DAILY("每日"),
    WEEKLY("每周"),
}

@Composable
private fun WorkflowEditorDialog(
    workflow: WorkflowItemUiState?,
    onConfirm: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(workflow?.id) { mutableStateOf(workflow?.name.orEmpty()) }
    var stepGoals by remember(workflow?.id) {
        mutableStateOf(workflow?.stepGoals?.ifEmpty { listOf(workflow.primaryGoal) } ?: listOf(""))
    }
    val normalizedGoals = stepGoals.map(String::trim)
    val valid = name.isNotBlank() &&
        name.length <= WorkflowDefinitionPolicy.MAX_NAME_LENGTH &&
        normalizedGoals.isNotEmpty() &&
        normalizedGoals.size <= WorkflowDefinitionPolicy.MAX_STEPS &&
        normalizedGoals.all { it.isNotBlank() && it.length <= WorkflowDefinitionPolicy.MAX_GOAL_LENGTH }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (workflow == null) "新建工作流" else "编辑工作流", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkflowCompactTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    placeholder = "例如：每日回顾",
                    singleLine = true,
                )
                stepGoals.forEachIndexed { index, goal ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("步骤 ${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                stepGoals = stepGoals.toMutableList().also { goals ->
                                    val current = goals.removeAt(index)
                                    goals.add(index - 1, current)
                                }
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Default.ArrowUpward, contentDescription = "步骤上移", modifier = Modifier.size(15.dp)) }
                        IconButton(
                            onClick = {
                                stepGoals = stepGoals.toMutableList().also { goals ->
                                    val current = goals.removeAt(index)
                                    goals.add(index + 1, current)
                                }
                            },
                            enabled = index < stepGoals.lastIndex,
                            modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Default.ArrowDownward, contentDescription = "步骤下移", modifier = Modifier.size(15.dp)) }
                        IconButton(
                            onClick = { stepGoals = stepGoals.toMutableList().also { it.removeAt(index) } },
                            enabled = stepGoals.size > 1,
                            modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Default.Delete, contentDescription = "删除步骤", modifier = Modifier.size(15.dp)) }
                    }
                    WorkflowCompactTextField(
                        value = goal,
                        onValueChange = { value ->
                            stepGoals = stepGoals.toMutableList().also { it[index] = value }
                        },
                        label = "Agent 目标",
                        placeholder = "描述这个步骤要完成的目标",
                        minLines = 2,
                    )
                }
                OutlinedButton(
                    onClick = { stepGoals = stepGoals + "" },
                    enabled = stepGoals.size < WorkflowDefinitionPolicy.MAX_STEPS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加步骤")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), normalizedGoals) },
                enabled = valid,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorkflowPageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 1.dp),
    )
}

@Composable
private fun WorkflowCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (placeholder.isNotBlank()) Text(placeholder, style = MaterialTheme.typography.bodySmall)
        },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(7.dp),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 38.dp else 60.dp),
    )
}

private fun WorkflowScheduleUiState.toWorkflowScheduleLabel(): String {
    val hour = (timeOfDayMinutes / 60).toString().padStart(2, '0')
    val minute = (timeOfDayMinutes % 60).toString().padStart(2, '0')
    val rule = when (type) {
        WorkflowScheduleType.DAILY -> "每日 $hour:$minute"
        WorkflowScheduleType.WEEKLY -> "每周${requireNotNull(dayOfWeek).toWeekdayLabel()} $hour:$minute"
    }
    return nextPlannedAt?.let { "$rule · 下次 ${it.toFullTimeLabel()} · $zoneId" } ?: "$rule · 已停用"
}

private fun Int.toWeekdayLabel(): String = when (this) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> toString()
}

private fun ScheduledTaskStatus.toScheduledTaskStatusLabel(): String = when (this) {
    ScheduledTaskStatus.SCHEDULED -> "等待系统调度"
    ScheduledTaskStatus.RUNNING -> "运行中"
    ScheduledTaskStatus.STOP_REQUESTED -> "停止中"
    ScheduledTaskStatus.BLOCKED -> "待处理"
    ScheduledTaskStatus.COMPLETED -> "已完成"
    ScheduledTaskStatus.FAILED -> "失败"
    ScheduledTaskStatus.CANCELLED -> "已取消"
}

private fun formatScheduleDelay(delayMillis: Long): String {
    val seconds = delayMillis / 1_000
    return when {
        seconds == 0L -> "0 秒"
        seconds > 0L -> "+${seconds} 秒"
        else -> "${seconds} 秒"
    }
}

private fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(WORKFLOW_FULL_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val WORKFLOW_FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
