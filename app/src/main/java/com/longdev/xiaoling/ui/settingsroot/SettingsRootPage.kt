package com.longdev.xiaoling.ui.settingsroot

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.ui.PageTitle
import com.longdev.xiaoling.ui.ThemeModeSelector

@Composable
internal fun SettingsRootPage(
    state: SettingsRootUiState,
    actions: SettingsRootActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageTitle("设置")
            ThemeModeSelector(
                themeMode = state.themeMode,
                onThemeModeChanged = actions::updateThemeMode,
            )
        }

        // long: 标题和主题入口属于页面导航上下文，只让设置卡片区滚动，避免用户滚到底后失去当前页面定位。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsEntryCard(
                title = "模型提供方管理",
                subtitle = if (state.providerCount == 0) {
                    "还没有模型提供方"
                } else {
                    "已配置 ${state.providerCount} 个提供方 · 可对话模型 ${state.chatModelCount} 个"
                },
                onClick = actions::openProviderManagement,
            )

            // long: 网络请求与其他设置项保持“入口卡片 -> 独立子页”的导航层级，避免在设置列表里出现唯一可直接编辑的行。
            SettingsEntryCard(
                title = "网络请求",
                subtitle = "配置模型接口请求使用的 User-Agent",
                icon = Icons.Default.CloudDownload,
                onClick = actions::openNetworkRequest,
            )

            SettingsEntryCard(
                title = "提示词设置",
                subtitle = "普通对话 · 会话摘要 / 记忆 · Agent 回复总结",
                icon = Icons.Default.Tune,
                onClick = actions::openPromptSettings,
            )

            SettingsEntryCard(
                title = "Agent Profiles",
                subtitle = state.selectedAgentProfileName
                    ?.let {
                        "当前：$it · ${state.selectedAgentProfileModel.orEmpty().ifBlank { "未配置模型" }} · " +
                            "${state.agentProfileCount} 个 Profile"
                    }
                    ?: "配置 Agent 身份、模型、工具、Skill 和记忆边界",
                icon = Icons.Default.Tune,
                onClick = actions::openAgentProfileManagement,
            )

            SettingsEntryCard(
                title = "设备 Agent",
                subtitle = "独立开关、无障碍观察和有限前台动作",
                icon = Icons.Default.Visibility,
                onClick = actions::openDeviceAgent,
            )

            SettingsEntryCard(
                title = "日历访问",
                subtitle = "授权前台 Agent 只读近期日程",
                icon = Icons.Default.DateRange,
                onClick = actions::openCalendarAccess,
            )

            SettingsEntryCard(
                title = "答案可回答性 Shadow",
                subtitle = if (state.answerabilityShadowEnabled) {
                    "已开启；仅匹配冻结 Judge 身份的前台 /agent 答案会异步观测"
                } else {
                    "默认关闭；答案保存后异步生成只读观察提示"
                },
                icon = Icons.Default.Visibility,
                onClick = actions::openAnswerabilityShadow,
            )

            SettingsEntryCard(
                title = "长期记忆",
                subtitle = "搜索、编辑、禁用、删除并查看来源",
                icon = Icons.Default.Memory,
                onClick = actions::openMemoryManagement,
            )

            SettingsEntryCard(
                title = "本地笔记",
                subtitle = "浏览并搜索 Agent 已保存到本机的笔记",
                icon = Icons.Default.Description,
                onClick = actions::openLocalNoteManagement,
            )

            SettingsEntryCard(
                title = "知识库",
                subtitle = "导入文档，管理启停、替换与本地检索预览",
                icon = Icons.Default.Description,
                onClick = actions::openKnowledgeManagement,
            )

            // long: 灰度控制面独立于知识库内容管理，用户可以查看身份与撤销状态，但不能在此页绕过正式证据直接开启生产拒绝。
            SettingsEntryCard(
                title = "相关性灰度控制面",
                subtitle = "查看 Provider 身份、shadow 状态与撤销资格",
                icon = Icons.Default.Visibility,
                onClick = actions::openKnowledgeRelevanceRollout,
            )

            SettingsEntryCard(
                title = "Agent Skills",
                subtitle = if (state.skillCount == 0) {
                    "管理内置与本地 Skill"
                } else {
                    "${state.enabledSkillCount} 个启用 · ${state.localSkillCount} 个本地"
                },
                icon = Icons.Default.Settings,
                onClick = actions::openSkillManagement,
                testTag = "settings-entry-agent-skills",
            )

            SettingsEntryCard(
                title = "工作流",
                subtitle = if (state.workflowCount == 0) {
                    "保存可重复的 Agent 目标并查看执行记录"
                } else {
                    "${state.enabledWorkflowCount} 个启用 · ${state.runningWorkflowCount} 个运行中"
                },
                icon = Icons.Default.PlayArrow,
                onClick = actions::openWorkflowManagement,
            )

            SettingsEntryCard(
                title = "Agent 任务中心",
                subtitle = if (state.agentRunCount == 0) {
                    "查看最近 Agent Run 的步骤、审批和事件"
                } else {
                    "最近 ${state.agentRunCount} 条 · ${state.completedAgentRunCount} 条已完成"
                },
                onClick = actions::openAgentRunHistory,
            )

            SettingsEntryCard(
                title = "进程退出观察",
                subtitle = if (state.processExitObservationCount == 0) {
                    "只读查看最近 30 条 Android 系统退出证据"
                } else {
                    "已记录 ${state.processExitObservationCount} 条 · 不关联 Agent Run 或工作流"
                },
                icon = Icons.Default.Memory,
                onClick = actions::openProcessExitObservations,
            )

            SettingsEntryCard(
                title = "数据备份与恢复",
                subtitle = if (state.backupBusy) {
                    "正在处理备份..."
                } else {
                    "导出或恢复 Room 数据；API Key 依赖当前设备 Keystore"
                },
                icon = Icons.Default.Save,
                onClick = actions::exportBackup,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = actions::exportBackup,
                            enabled = !state.backupBusy,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "导出备份", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = actions::importBackup,
                            enabled = !state.backupBusy,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = "恢复备份", modifier = Modifier.size(18.dp))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Default.Memory,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
                ?: Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
