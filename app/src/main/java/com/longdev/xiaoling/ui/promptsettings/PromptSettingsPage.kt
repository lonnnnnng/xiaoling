package com.longdev.xiaoling.ui.promptsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.prompt.PromptSettings
import com.longdev.xiaoling.ui.CompactSection
import com.longdev.xiaoling.ui.CompactTextField
import com.longdev.xiaoling.ui.PageTitle

private enum class PromptPreviewSection(val testTagPrefix: String) {
    CHAT("chat"),
    SUMMARY("summary"),
    AGENT_SUMMARY("agent-summary"),
}

@Composable
internal fun PromptSettingsPage(
    settings: PromptSettings,
    actions: PromptSettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewSection by remember { mutableStateOf<PromptPreviewSection?>(null) }

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
            PageTitle("提示词设置")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = PromptPreviewSection.CHAT) {
                PromptEditorSection(
                    section = PromptPreviewSection.CHAT,
                    title = "普通对话",
                    enabled = settings.chatPromptEnabled,
                    prompt = settings.chatPrompt,
                    preview = PromptPolicy.chatSystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.CHAT,
                    onEnabledChanged = actions::updateChatPromptEnabled,
                    onPromptChanged = actions::updateChatPrompt,
                    onRestore = actions::restoreChatPrompt,
                    onTogglePreview = {
                        // long: 同一时刻只展开一类最终提示词，避免长模板同时占满设置页并混淆当前编辑对象。
                        previewSection = previewSection.toggled(PromptPreviewSection.CHAT)
                    },
                )
            }
            item(key = PromptPreviewSection.SUMMARY) {
                PromptEditorSection(
                    section = PromptPreviewSection.SUMMARY,
                    title = "会话摘要 / 记忆",
                    enabled = settings.summaryPromptEnabled,
                    prompt = settings.summaryPrompt,
                    preview = PromptPolicy.summarySystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.SUMMARY,
                    onEnabledChanged = actions::updateSummaryPromptEnabled,
                    onPromptChanged = actions::updateSummaryPrompt,
                    onRestore = actions::restoreSummaryPrompt,
                    onTogglePreview = {
                        previewSection = previewSection.toggled(PromptPreviewSection.SUMMARY)
                    },
                )
            }
            item(key = PromptPreviewSection.AGENT_SUMMARY) {
                PromptEditorSection(
                    section = PromptPreviewSection.AGENT_SUMMARY,
                    title = "Agent 回复总结",
                    enabled = settings.agentSummaryPromptEnabled,
                    prompt = settings.agentSummaryPrompt,
                    preview = PromptPolicy.agentSummarySystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.AGENT_SUMMARY,
                    onEnabledChanged = actions::updateAgentSummaryPromptEnabled,
                    onPromptChanged = actions::updateAgentSummaryPrompt,
                    onRestore = actions::restoreAgentSummaryPrompt,
                    onTogglePreview = {
                        previewSection = previewSection.toggled(PromptPreviewSection.AGENT_SUMMARY)
                    },
                )
            }
        }
    }
}

private fun PromptPreviewSection?.toggled(section: PromptPreviewSection): PromptPreviewSection? {
    return if (this == section) null else section
}

@Composable
private fun PromptEditorSection(
    section: PromptPreviewSection,
    title: String,
    enabled: Boolean,
    prompt: String,
    preview: String,
    previewVisible: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onPromptChanged: (String) -> Unit,
    onRestore: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    val tagPrefix = "prompt-settings-${section.testTagPrefix}"
    CompactSection(
        title = title,
        action = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                modifier = Modifier.testTag("$tagPrefix-enabled"),
            )
        },
    ) {
        CompactTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            label = "自定义模板",
            minLines = 4,
            modifier = Modifier.testTag("$tagPrefix-text"),
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OutlinedButton(
                onClick = onRestore,
                shape = RoundedCornerShape(7.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag("$tagPrefix-restore"),
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("恢复默认", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onTogglePreview,
                shape = RoundedCornerShape(7.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag("$tagPrefix-preview"),
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (previewVisible) "收起预览" else "最终提示词", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (previewVisible) {
            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(8.dp),
            ) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
