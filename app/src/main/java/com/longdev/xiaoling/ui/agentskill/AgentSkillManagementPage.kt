package com.longdev.xiaoling.ui.agentskill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AgentSkillValidationStatus
import com.longdev.xiaoling.ui.AgentStatusChip

internal fun agentSkillItemTag(skillId: String): String = "agent-skill-item:$skillId"

internal fun agentSkillToggleTag(skillId: String): String = "agent-skill-toggle:$skillId"

internal fun agentSkillDeleteTag(skillId: String): String = "agent-skill-delete:$skillId"

internal fun agentSkillTryExampleTag(skillId: String, index: Int): String = "agent-skill-try:$skillId:$index"

@Composable
internal fun AgentSkillManagementPage(
    state: AgentSkillManagementUiState,
    actions: AgentSkillManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        // long: 页面拥有首次加载，设置入口只负责导航；重组不能重复触发 Room 刷新或覆盖正在进行的导入结果。
        if (state.skills.isEmpty() && !state.loading) actions.refreshSkills()
        if (state.skills.none { item -> item.runAudits.isNotEmpty() } && !state.loadingAudits) {
            actions.refreshSkillAudits()
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentSkillManagementHeader(
            loading = state.loading || state.loadingAudits,
            importing = state.importing,
            onRefresh = {
                actions.refreshSkills()
                actions.refreshSkillAudits()
            },
            onImportSkill = actions::requestSkillImport,
            onBack = onBack,
        )

        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.auditError?.takeIf { auditError -> auditError != state.error }?.let { auditError ->
            Text(auditError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("agent-skill-list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            when {
                state.loading && state.skills.isEmpty() -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.6.dp)
                    }
                }
                state.skills.isEmpty() -> item {
                    Text(
                        "没有可用 Skill",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                else -> items(
                    items = state.skills,
                    key = { item -> item.skill.definition.id },
                ) { item ->
                    AgentSkillItem(
                        item = item,
                        onEnabledChange = { enabled ->
                            actions.setSkillEnabled(item.skill.definition.id, enabled)
                        },
                        onDelete = { actions.requestLocalSkillDelete(item.skill.definition.id) },
                        onTryExample = { example -> actions.trySkill(item.skill.definition.id, example) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSkillManagementHeader(
    loading: Boolean,
    importing: Boolean,
    onRefresh: () -> Unit,
    onImportSkill: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
        }
        Text(
            "Agent Skills",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.size(30.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "刷新 Skill", modifier = Modifier.size(18.dp))
            }
        }
        OutlinedButton(
            onClick = onImportSkill,
            enabled = !importing,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 9.dp),
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
            } else {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入 JSON", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AgentSkillItem(
    item: AgentSkillManagementItemUiState,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTryExample: (String) -> Unit,
) {
    val skillId = item.skill.definition.id
    // long: 展开状态绑定稳定 Skill ID；刷新返回新对象或列表重排时，详情不能跳到另一个 Skill。
    var expanded by remember(skillId) { mutableStateOf(false) }
    val definition = item.skill.definition
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(agentSkillItemTag(skillId))
            .clip(RoundedCornerShape(7.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(definition.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${when (item.skill.validationStatus) {
                            AgentSkillValidationStatus.TRUSTED_BUILT_IN -> "内置可信"
                            AgentSkillValidationStatus.VALIDATED_LOCAL -> "本地已校验"
                        }} · v${definition.version} · ${definition.declaredRisk.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.showDelete) {
                    IconButton(
                        onClick = onDelete,
                        enabled = item.deleteEnabled,
                        modifier = Modifier.size(30.dp).testTag(agentSkillDeleteTag(skillId)),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除本地 Skill", modifier = Modifier.size(17.dp))
                    }
                }
                Switch(
                    checked = item.skill.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = item.toggleEnabled,
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .testTag(agentSkillToggleTag(skillId)),
                )
            }
            Text(definition.description, style = MaterialTheme.typography.bodySmall)
            val availableDependencyCount = item.dependencies.count(AgentSkillDependencyUiState::available)
            Text(
                "工具依赖：$availableDependencyCount/${item.dependencies.size} 已注册",
                style = MaterialTheme.typography.labelSmall,
                color = if (availableDependencyCount == item.dependencies.size) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                item.dependencies.forEach { dependency ->
                    Text(
                        "依赖工具：${dependency.name} · ${if (dependency.available) "已注册" else "缺失"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dependency.available) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Text("触发词：${definition.keywords.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("执行：${definition.instructions}", style = MaterialTheme.typography.bodySmall)
                Text("完成：${definition.completionCriteria.ifBlank { "由工具结果验证" }}", style = MaterialTheme.typography.bodySmall)
                Text("失败：${definition.failureRecovery.ifBlank { "停止并报告失败步骤" }}", style = MaterialTheme.typography.bodySmall)
                if (definition.requiredAndroidPermissions.isNotEmpty()) {
                    Text(
                        "Android 权限：${definition.requiredAndroidPermissions.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val tryExamples = definition.triggerExamples
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(3)
                if (tryExamples.isNotEmpty()) {
                    Text("试用示例", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    tryExamples.forEachIndexed { index, example ->
                        OutlinedButton(
                            onClick = { onTryExample(example) },
                            enabled = item.tryEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(agentSkillTryExampleTag(skillId, index)),
                        ) {
                            Text(example, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        text = item.tryDisabledReason ?: "点击后只填入对话输入框，不会自动发送或执行",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.tryEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (item.runAudits.isEmpty()) {
                    Text(
                        "最近 Run：暂无使用记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    item.runAudits.forEach { audit ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "最近 Run：${audit.runId} · ${audit.selectedVersion?.let { version -> "v$version" } ?: "旧版"}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            AgentStatusChip(audit.status)
                        }
                    }
                }
            }
        }
    }
}
