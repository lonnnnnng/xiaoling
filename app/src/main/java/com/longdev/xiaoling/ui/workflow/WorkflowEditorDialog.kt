package com.longdev.xiaoling.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.automation.*

internal enum class WorkflowCompletionCriterion(val label: String, val tools: List<String>) {
    CURRENT_TIME("读取当前时间", listOf("app.current_time")),
    APP_INFO("读取小灵版本", listOf("app.get_info")),
    OBSERVE("观察设备页面", listOf("device.snapshot")),
    OPEN_APP("打开限定应用并复查页面", listOf("device.snapshot", "device.open_app", "device.snapshot")),
    TAP("点击控件并复查页面", listOf("device.snapshot", "device.tap_ref", "device.snapshot")),
    TYPE_TEXT("输入公开文本并复查页面", listOf("device.snapshot", "device.type_text", "device.snapshot")),
    SCROLL("滚动页面并复查", listOf("device.snapshot", "device.swipe", "device.snapshot")),
}

internal data class WorkflowEditorStepDraft(val goal: String, val criterion: WorkflowCompletionCriterion? = null)

internal val workflowEditorApplications = linkedMapOf(
    "com.longdev.xiaoling" to "小灵",
    "com.android.calculator2" to "计算器（系统）",
    "com.google.android.calculator" to "计算器（Google）",
    "com.android.deskclock" to "时钟（系统）",
    "com.google.android.deskclock" to "时钟（Google）",
    "com.android.settings" to "系统设置",
    "com.google.android.apps.weather" to "天气（Google）",
)

@Composable
internal fun WorkflowEditorDialog(
    workflow: WorkflowItemUiState?,
    onConfirm: (WorkflowEditorInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(workflow?.id) { mutableStateOf(workflow?.name.orEmpty()) }
    var target by remember(workflow?.id) { mutableStateOf(workflow?.targetAppPackage) }
    var verify by remember(workflow?.id) { mutableStateOf(workflow?.goalVerificationContract != null) }
    var completionGoal by remember(workflow?.id) { mutableStateOf(workflow?.goalVerificationContract?.sourceGoal.orEmpty()) }
    var finalApp by remember(workflow?.id) { mutableStateOf(workflow?.goalVerificationContract?.spec?.expectedFinalPackageName != null) }
    var confirmed by remember(workflow?.id) { mutableStateOf(false) }
    var steps by remember(workflow?.id) {
        val goals = workflow?.stepGoals?.ifEmpty { listOf(workflow.primaryGoal) } ?: listOf("")
        val spec = workflow?.goalVerificationContract?.spec
        val groups = spec?.requiredToolNamesByStep?.ifEmpty {
            if (goals.size == 1) listOf(spec.requiredToolNames) else emptyList()
        }.orEmpty()
        mutableStateOf(goals.mapIndexed { index, goal ->
            WorkflowEditorStepDraft(goal, WorkflowCompletionCriterion.entries.singleOrNull { it.tools == groups.getOrNull(index) })
        })
    }
    val proposed = runCatching {
        val goals = steps.map { it.goal.trim() }
        WorkflowDefinitionPolicy.validate(name.trim(), goals.map(::WorkflowStepDefinitionInput))
        val contract = if (verify) {
            require(completionGoal.isNotBlank()) { "请填写工作流完成目标" }
            val groups = steps.map { requireNotNull(it.criterion) { "请为每一步选择完成标准" }.tools }
            WorkflowGoalVerificationContract(completionGoal.trim(), WorkflowGoalVerificationSpec(
                groups.flatten(), if (finalApp) requireNotNull(target) { "请先选择限定应用" } else null, groups,
            ))
        } else null
        val confirmation = WorkflowDefinitionConfirmation(target, contract)
        confirmation.validate(goals.size)
        WorkflowEditorInput(name.trim(), goals, confirmation)
    }.getOrNull()
    // long: 保存就是确认此刻可见的应用与标准；任何目标、顺序或边界变化都撤销勾选，不能沿用旧确认。
    val needsConfirmation = target != null || verify || workflow?.targetAppPackage != null || workflow?.goalVerificationContract != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (workflow == null) "新建工作流" else "编辑工作流", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkflowCompactTextField(name, { name = it; confirmed = false }, "名称", placeholder = "例如：每日回顾", singleLine = true,
                    modifier = Modifier.testTag("workflow-editor-name"))
                WorkflowEditorChoice(
                    "限定应用：${workflowEditorApplications[target] ?: "不限定"}", "workflow-editor-app",
                    listOf(null to "不限定（设备动作不可用）") + workflowEditorApplications.map { it.key to it.value },
                ) { target = it; if (it == null) finalApp = false; confirmed = false }
                Text("设备动作只在限定应用范围内执行，执行前仍需按动作风险确认。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(verify, { verify = it; confirmed = false }, Modifier.testTag("workflow-editor-verify"))
                    Text("核验完成标准", style = MaterialTheme.typography.bodySmall)
                }
                if (verify) {
                    WorkflowCompactTextField(completionGoal, { completionGoal = it; confirmed = false }, "工作流完成目标",
                        modifier = Modifier.testTag("workflow-editor-completion-goal"), minLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(finalApp, { finalApp = it; confirmed = false }, Modifier.testTag("workflow-editor-final-app"), enabled = target != null)
                        Text("完成时仍在限定应用", style = MaterialTheme.typography.bodySmall)
                    }
                    if (steps.any { it.criterion == null }) Text("请为每一步选择完成标准；已有其他标准需在此重新确认。", style = MaterialTheme.typography.bodySmall)
                }
                steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("步骤 ${index + 1}", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        IconButton({ steps = steps.toMutableList().also { it.add(index - 1, it.removeAt(index)) }; confirmed = false },
                            Modifier.size(28.dp), enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, "步骤上移", Modifier.size(15.dp))
                        }
                        IconButton({ steps = steps.toMutableList().also { it.add(index + 1, it.removeAt(index)) }; confirmed = false },
                            Modifier.size(28.dp), enabled = index < steps.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, "步骤下移", Modifier.size(15.dp))
                        }
                        IconButton({ steps = steps.toMutableList().also { it.removeAt(index) }; confirmed = false },
                            Modifier.size(28.dp), enabled = steps.size > 1) {
                            Icon(Icons.Default.Delete, "删除步骤", Modifier.size(15.dp))
                        }
                    }
                    WorkflowCompactTextField(step.goal, { value ->
                        steps = steps.toMutableList().also { it[index] = step.copy(goal = value) }; confirmed = false
                    }, "Agent 目标", placeholder = "描述这个步骤要完成的目标", minLines = 2,
                        modifier = Modifier.testTag("workflow-editor-step-$index"))
                    if (verify) WorkflowEditorChoice(
                        "完成标准：${step.criterion?.label ?: "请选择"}", "workflow-editor-criterion-$index",
                        WorkflowCompletionCriterion.entries.map { it to it.label },
                    ) { criterion ->
                        steps = steps.toMutableList().also { it[index] = step.copy(criterion = criterion) }; confirmed = false
                    }
                }
                OutlinedButton({ steps = steps + WorkflowEditorStepDraft(""); confirmed = false },
                    Modifier.fillMaxWidth(), enabled = steps.size < WorkflowDefinitionPolicy.MAX_STEPS) { Text("添加步骤") }
                if (needsConfirmation) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }, Modifier.testTag("workflow-editor-confirm-boundary"))
                    Text("我确认当前限定应用与完成标准", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(requireNotNull(proposed)) },
            Modifier.testTag("workflow-editor-save"), enabled = proposed != null && (!needsConfirmation || confirmed)) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun <T> WorkflowEditorChoice(label: String, tag: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().testTag(tag)) { Text(label, style = MaterialTheme.typography.bodySmall) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}
