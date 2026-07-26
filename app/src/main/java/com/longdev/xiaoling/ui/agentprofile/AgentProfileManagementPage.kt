package com.longdev.xiaoling.ui.agentprofile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.ui.CompactTextField

internal fun agentProfileItemTag(profileId: String): String = "agent-profile-item:$profileId"

internal fun agentProfileEditTag(profileId: String): String = "agent-profile-edit:$profileId"

internal fun agentProfileDeleteTag(profileId: String): String = "agent-profile-delete:$profileId"

internal fun agentProfileNameFieldTag(): String = "agent-profile-name-field"

internal fun agentProfileAvatarFieldTag(): String = "agent-profile-avatar-field"

internal fun agentProfileProviderSelectorTag(): String = "agent-profile-provider-selector"

internal fun agentProfileApiModeTag(apiMode: ApiMode): String = "agent-profile-api-mode:${apiMode.name}"

internal fun agentProfileModelSelectorTag(): String = "agent-profile-model-selector"

internal fun agentProfileSystemPromptFieldTag(): String = "agent-profile-system-prompt-field"

internal fun agentProfileMemorySwitchTag(): String = "agent-profile-memory-switch"

internal fun agentProfileToolRowTag(toolName: String): String = "agent-profile-tool-row:$toolName"

internal fun agentProfileSkillRowTag(skillId: String): String = "agent-profile-skill-row:$skillId"

internal fun agentProfileSaveTag(): String = "agent-profile-save"

@Composable
internal fun AgentProfileManagementPage(
    state: AgentProfileManagementUiState,
    actions: AgentProfileManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // long: 编辑目标只保存稳定 Profile ID，列表重排或 Room 返回新对象时仍从最新状态解析记录，避免操作误绑到旧引用。
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var creatingProfile by remember { mutableStateOf(false) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    val editingProfile = state.profiles.firstOrNull { it.profile.id == editingProfileId }
    val pendingDelete = state.profiles.firstOrNull { it.profile.id == pendingDeleteProfileId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentProfileManagementHeader(
            onBack = onBack,
            onCreate = { creatingProfile = true },
        )
        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(
                items = state.profiles,
                key = { item -> item.profile.id },
            ) { item ->
                AgentProfileListItem(
                    item = item,
                    onSelect = { actions.selectAgentProfile(item.profile.id) },
                    onEdit = { editingProfileId = item.profile.id },
                    onDelete = { pendingDeleteProfileId = item.profile.id },
                )
            }
        }
    }

    if (creatingProfile || editingProfile != null) {
        val profile = editingProfile?.profile
        AgentProfileEditorDialog(
            initialDraft = AgentProfileManagementProjection.createDraft(profile, state),
            providers = state.providers,
            tools = state.tools,
            skills = state.enabledSkills,
            saving = editingProfile?.mutating == true,
            onSave = { draft ->
                // long: 编辑器沿用保存即关闭的交互；异步失败回写管理页顶部 error，成功结果继续由应用壳统一提示。
                actions.saveAgentProfile(draft)
                creatingProfile = false
                editingProfileId = null
            },
            onDismiss = {
                creatingProfile = false
                editingProfileId = null
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfileId = null },
            title = { Text("删除 Agent Profile") },
            text = { Text(item.profile.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.deleteAgentProfile(item.profile.id)
                        pendingDeleteProfileId = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfileId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AgentProfileManagementHeader(
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回设置",
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = "Agent Profiles",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCreate, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Add, contentDescription = "新增 Agent Profile", modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun AgentProfileListItem(
    item: AgentProfileManagementItemUiState,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val profile = item.profile
    Surface(
        color = if (item.selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(
            1.dp,
            if (item.selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(agentProfileItemTag(profile.id))
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        profile.avatar.ifBlank { "A" }.take(AgentProfilePolicy.MAX_AVATAR_LENGTH),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.selected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "当前 Agent",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${item.providerName} · ${profile.model.ifBlank { "模型未配置" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.providerModelValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "工具 ${profile.allowedToolNames.size} · Skill ${profile.allowedSkillIds.size} · 记忆${if (profile.memoryEnabled) "开启" else "关闭"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onEdit,
                enabled = !item.mutating,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(agentProfileEditTag(profile.id)),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "编辑 Agent Profile", modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onDelete,
                enabled = item.deleteEnabled,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(agentProfileDeleteTag(profile.id)),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除 Agent Profile", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AgentProfileEditorDialog(
    initialDraft: AgentProfileEditDraft,
    providers: List<ProviderProfile>,
    tools: List<ToolDefinition>,
    skills: List<AgentSkillRecord>,
    saving: Boolean,
    onSave: (AgentProfileEditDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    // long: 只有切换目标 Profile 才重建草稿，同一 Profile 的外部状态刷新不能覆盖用户尚未保存的输入。
    var draft by remember(initialDraft.id) { mutableStateOf(initialDraft) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val selectedProvider = providers.firstOrNull { it.id == draft.providerId }
    val canSave = draft.name.isNotBlank() &&
        draft.providerId.isNotBlank() &&
        draft.model.isNotBlank() &&
        draft.allowedToolNames.isNotEmpty() &&
        !saving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新增 Agent Profile" else "编辑 Agent Profile") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                CompactTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(AgentProfilePolicy.MAX_NAME_LENGTH)) },
                    label = "名称",
                    singleLine = true,
                    modifier = Modifier.testTag(agentProfileNameFieldTag()),
                )
                CompactTextField(
                    value = draft.avatar,
                    onValueChange = { draft = draft.copy(avatar = it.take(AgentProfilePolicy.MAX_AVATAR_LENGTH)) },
                    label = "标识",
                    singleLine = true,
                    modifier = Modifier.testTag(agentProfileAvatarFieldTag()),
                )
                ProviderSelector(
                    selectedProviderName = selectedProvider?.name,
                    providers = providers,
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = it },
                    onProviderSelected = { provider ->
                        draft = draft.copy(
                            providerId = provider.id,
                            model = provider.enabledModels.firstOrNull().orEmpty(),
                        )
                        providerMenuExpanded = false
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiModeButton(
                        text = "Chat",
                        selected = draft.apiMode == ApiMode.CHAT_COMPLETIONS,
                        onClick = { draft = draft.copy(apiMode = ApiMode.CHAT_COMPLETIONS) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(agentProfileApiModeTag(ApiMode.CHAT_COMPLETIONS)),
                    )
                    ApiModeButton(
                        text = "Responses",
                        selected = draft.apiMode == ApiMode.RESPONSES,
                        onClick = { draft = draft.copy(apiMode = ApiMode.RESPONSES) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(agentProfileApiModeTag(ApiMode.RESPONSES)),
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = selectedProvider?.enabledModels?.isNotEmpty() == true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(agentProfileModelSelectorTag()),
                    ) {
                        Text(draft.model.ifBlank { "选择模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        selectedProvider?.enabledModels.orEmpty().forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    draft = draft.copy(model = model)
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                CompactTextField(
                    value = draft.systemPrompt,
                    onValueChange = {
                        draft = draft.copy(systemPrompt = it.take(AgentProfilePolicy.MAX_SYSTEM_PROMPT_LENGTH))
                    },
                    label = "系统提示词",
                    minLines = 4,
                    modifier = Modifier.testTag(agentProfileSystemPromptFieldTag()),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("长期记忆", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = draft.memoryEnabled,
                        onCheckedChange = { draft = draft.copy(memoryEnabled = it) },
                        modifier = Modifier.testTag(agentProfileMemorySwitchTag()),
                    )
                }
                ToolOptions(
                    tools = tools,
                    skills = skills,
                    draft = draft,
                    onDraftChanged = { draft = it },
                )
                SkillOptions(
                    skills = skills,
                    draft = draft,
                    onDraftChanged = { draft = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = canSave,
                modifier = Modifier.testTag(agentProfileSaveTag()),
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ProviderSelector(
    selectedProviderName: String?,
    providers: List<ProviderProfile>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onProviderSelected: (ProviderProfile) -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(agentProfileProviderSelectorTag()),
        ) {
            Text(selectedProviderName ?: "选择模型提供方", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name, style = MaterialTheme.typography.bodySmall) },
                    onClick = { onProviderSelected(provider) },
                )
            }
        }
    }
}

@Composable
private fun ToolOptions(
    tools: List<ToolDefinition>,
    skills: List<AgentSkillRecord>,
    draft: AgentProfileEditDraft,
    onDraftChanged: (AgentProfileEditDraft) -> Unit,
) {
    Text("允许工具", style = MaterialTheme.typography.labelMedium)
    tools.forEach { tool ->
        val checked = tool.name in draft.allowedToolNames
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(agentProfileToolRowTag(tool.name))
                .toggleable(
                    value = checked,
                    onValueChange = { enabled ->
                        val allowedTools = if (enabled) {
                            draft.allowedToolNames + tool.name
                        } else {
                            draft.allowedToolNames - tool.name
                        }
                        // long: 取消工具时同步撤销依赖它的 Skill，防止保存出无法满足工具授权的 Profile。
                        val allowedSkills = if (!enabled) {
                            draft.allowedSkillIds.filterTo(linkedSetOf()) { skillId ->
                                skills.firstOrNull { it.definition.id == skillId }
                                    ?.definition
                                    ?.toolNames
                                    ?.contains(tool.name) != true
                            }
                        } else {
                            draft.allowedSkillIds
                        }
                        onDraftChanged(
                            draft.copy(
                                allowedToolNames = allowedTools,
                                allowedSkillIds = allowedSkills,
                            ),
                        )
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column {
                Text(tool.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    tool.risk.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SkillOptions(
    skills: List<AgentSkillRecord>,
    draft: AgentProfileEditDraft,
    onDraftChanged: (AgentProfileEditDraft) -> Unit,
) {
    Text("允许 Skill", style = MaterialTheme.typography.labelMedium)
    skills.forEach { skill ->
        val checked = skill.definition.id in draft.allowedSkillIds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(agentProfileSkillRowTag(skill.definition.id))
                .toggleable(
                    value = checked,
                    onValueChange = { enabled ->
                        val allowedSkills = if (enabled) {
                            draft.allowedSkillIds + skill.definition.id
                        } else {
                            draft.allowedSkillIds - skill.definition.id
                        }
                        // long: 启用 Skill 时自动补齐依赖工具，使 UI 生成的草稿始终满足保存校验。
                        val allowedTools = if (enabled) {
                            draft.allowedToolNames + skill.definition.toolNames
                        } else {
                            draft.allowedToolNames
                        }
                        onDraftChanged(
                            draft.copy(
                                allowedToolNames = allowedTools,
                                allowedSkillIds = allowedSkills,
                            ),
                        )
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(skill.definition.name, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ApiModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonModifier = modifier.height(28.dp)
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = buttonModifier,
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = buttonModifier,
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
