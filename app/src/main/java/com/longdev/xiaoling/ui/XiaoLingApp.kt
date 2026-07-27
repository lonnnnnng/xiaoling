package com.longdev.xiaoling.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.ui.navigation.XiaoLingAppTab
import com.longdev.xiaoling.ui.navigation.XiaoLingBottomTabBar
import com.longdev.xiaoling.ui.navigation.XiaoLingExternalNavigationTarget
import com.longdev.xiaoling.ui.navigation.XiaoLingNavigationEffect
import com.longdev.xiaoling.ui.navigation.XiaoLingSettingsPane as SettingsPane
import com.longdev.xiaoling.ui.navigation.rememberXiaoLingNavigationController
import com.longdev.xiaoling.ui.agenttask.AgentTaskCenterPage
import com.longdev.xiaoling.ui.agenttask.AgentTaskCenterProjection
import com.longdev.xiaoling.ui.agenttask.AgentTaskCenterUiState
import com.longdev.xiaoling.ui.agentprofile.AgentProfileManagementPage
import com.longdev.xiaoling.ui.agentprofile.AgentProfileManagementProjection
import com.longdev.xiaoling.ui.agentprofile.AgentProfileManagementUiState
import com.longdev.xiaoling.ui.agentskill.AgentSkillManagementActions
import com.longdev.xiaoling.ui.agentskill.AgentSkillManagementPage
import com.longdev.xiaoling.ui.agentskill.AgentSkillManagementProjection
import com.longdev.xiaoling.ui.agentskill.AgentSkillManagementUiState
import com.longdev.xiaoling.ui.conversation.ConversationActions
import com.longdev.xiaoling.ui.conversation.ConversationPage
import com.longdev.xiaoling.ui.conversation.ConversationProjection
import com.longdev.xiaoling.ui.conversation.ConversationUiState
import com.longdev.xiaoling.ui.memory.MemoryManagementPage
import com.longdev.xiaoling.ui.memory.MemoryManagementProjection
import com.longdev.xiaoling.ui.memory.MemoryManagementUiState
import com.longdev.xiaoling.ui.networksettings.NetworkRequestSettingsPage
import com.longdev.xiaoling.ui.networksettings.NetworkRequestSettingsUiState
import com.longdev.xiaoling.ui.provider.ProviderManagementPage
import com.longdev.xiaoling.ui.provider.ProviderManagementProjection
import com.longdev.xiaoling.ui.provider.ProviderManagementUiState
import com.longdev.xiaoling.ui.promptsettings.PromptSettingsPage
import com.longdev.xiaoling.ui.processexit.ProcessExitObservationPage
import com.longdev.xiaoling.ui.processexit.ProcessExitObservationUiState
import com.longdev.xiaoling.ui.settingsroot.SettingsRootActions
import com.longdev.xiaoling.ui.settingsroot.SettingsRootPage
import com.longdev.xiaoling.ui.settingsroot.SettingsRootProjection
import com.longdev.xiaoling.ui.settingsroot.SettingsRootUiState
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import com.longdev.xiaoling.ui.workflow.WorkflowManagementPage
import com.longdev.xiaoling.ui.workflow.WorkflowManagementProjection
import com.longdev.xiaoling.ui.workflow.WorkflowManagementUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun XiaoLingApp(viewModel: XiaoLingViewModel = viewModel()) {
    val state = viewModel.uiState
    LaunchedEffect(viewModel) {
        // long: 先让 Compose 交付可见首帧并结束系统 Splash，再启动 Room、Agent 与 Workflow 恢复；恢复仍只启动一次并继续处理冷启动分享队列。
        withFrameNanos { }
        viewModel.initialize()
    }
    XiaoLingTheme(themeMode = state.themeMode) {
        XiaoLingContent(
            state = state,
            viewModel = viewModel,
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun XiaoLingContent(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
) {
    val navigation = rememberXiaoLingNavigationController()
    val context = LocalContext.current
    var pendingBackupRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingBackupRestoreUri = uri }
    val importSkillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importSkill) }
    val attachImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::attachImage) }
    val attachDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::attachDocument) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val transientResult = state.result?.takeUnless { it.shouldStayInline() }
    val isProviderEditor = navigation.tab == XiaoLingAppTab.SETTINGS && state.manageDraft != null
    val hideBottomBar = navigation.hidesBottomBar(providerEditorOpen = isProviderEditor)
    var centerNotice by remember { mutableStateOf<CenterNotice?>(null) }
    val conversationActions = remember(
        viewModel,
        attachImageLauncher,
        attachDocumentLauncher,
        navigation,
    ) {
        object : ConversationActions {
            override fun selectConversation(conversationId: String) = viewModel.selectConversation(conversationId)

            override fun openNewConversation() = viewModel.openNewConversation()

            override fun deleteCurrentConversation() = viewModel.deleteCurrentConversation()

            override fun updateThemeMode(value: AppThemeMode) = viewModel.updateThemeMode(value)

            override fun selectProvider(profileId: String) = viewModel.selectProfile(profileId)

            override fun updateModel(value: String) = viewModel.updateModel(value)

            override fun updateResponsesEnabled(value: Boolean) = viewModel.updateResponsesEnabled(value)

            override fun updateStreamingEnabled(value: Boolean) = viewModel.updateStreamingEnabled(value)

            override fun updateReasoningSummaryEnabled(value: Boolean) {
                viewModel.updateReasoningSummaryEnabled(value)
            }

            override fun updateAgentMemoryRecallEnabled(value: Boolean) {
                viewModel.updateAgentMemoryRecallEnabled(value)
            }

            override fun selectAgentProfile(profileId: String) = viewModel.selectAgentProfile(profileId)

            override fun updatePrompt(value: String) = viewModel.updatePrompt(value)

            override fun removePendingImage() = viewModel.removePendingImage()

            override fun removePendingDocument() = viewModel.removePendingDocument()

            override fun openPendingSharedDraft() = viewModel.openPendingSharedDraft()

            override fun discardPendingSharedDraft() = viewModel.discardPendingSharedDraft()

            override fun sendMessage() = viewModel.sendMessage()

            override fun stopGenerating() = viewModel.stopGenerating()

            override fun approvePendingAgentTool() = viewModel.approvePendingAgentTool()

            override fun rejectPendingAgentTool() = viewModel.rejectPendingAgentTool()

            override fun refreshKnowledgeReferenceStatuses(references: List<KnowledgeReference>) {
                viewModel.refreshKnowledgeReferenceStatuses(references)
            }

            override fun requestImageAttachment() {
                attachImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            }

            override fun requestDocumentAttachment() {
                attachDocumentLauncher.launch(DocumentAttachmentPolicy.pickerMimeTypes())
            }

            override fun openKnowledgeDocument(documentId: String) {
                navigation.openKnowledgeDocument(documentId)
            }
        }
    }

    LaunchedEffect(state.agentRetryNavigationConversationId) {
        val conversationId = state.agentRetryNavigationConversationId ?: return@LaunchedEffect
        // long: 任务中心可以重试任意历史会话；新 Run 启动后必须回到来源对话，用户才能看到重新触发的审批卡和实时步骤。
        navigation.routeExternal(XiaoLingExternalNavigationTarget.AGENT_RETRY)
        viewModel.consumeAgentRetryNavigation()
    }

    LaunchedEffect(state.workflowNavigationConversationId) {
        state.workflowNavigationConversationId ?: return@LaunchedEffect
        navigation.routeExternal(XiaoLingExternalNavigationTarget.WORKFLOW)
        viewModel.consumeWorkflowNavigation()
    }

    LaunchedEffect(state.memorySourceConversationNavigationId) {
        state.memorySourceConversationNavigationId ?: return@LaunchedEffect
        navigation.routeExternal(XiaoLingExternalNavigationTarget.MEMORY_CONVERSATION)
        viewModel.consumeMemorySourceConversationNavigation()
    }

    LaunchedEffect(state.memorySourceRunNavigationId) {
        state.memorySourceRunNavigationId ?: return@LaunchedEffect
        navigation.routeExternal(XiaoLingExternalNavigationTarget.MEMORY_RUN)
        viewModel.consumeMemorySourceRunNavigation()
    }

    LaunchedEffect(state.sharedDraftNavigationVersion) {
        if (state.sharedDraftNavigationVersion <= 0L) return@LaunchedEffect
        navigation.routeExternal(XiaoLingExternalNavigationTarget.SHARED_DRAFT)
    }

    BackHandler {
        when (navigation.back(
            providerEditorOpen = isProviderEditor,
            nowMillis = System.currentTimeMillis(),
        )) {
            XiaoLingNavigationEffect.CLOSE_PROVIDER_EDITOR -> viewModel.closeProviderEditor()
            XiaoLingNavigationEffect.SHOW_EXIT_NOTICE -> centerNotice = CenterNotice("再返回一次退出应用")
            XiaoLingNavigationEffect.FINISH_ACTIVITY -> (context as? Activity)?.finish()
            null -> Unit
        }
    }

    LaunchedEffect(transientResult) {
        transientResult?.let { result ->
            // long: 设置页保存、删除、获取模型等反馈只需要告知结果，不应该占用底部操作区，也不应该阻断用户继续点击页面。
            centerNotice = CenterNotice(
                text = "${result.title}：${result.message}",
                success = result.success,
            )
            viewModel.clearResult()
        }
    }

    LaunchedEffect(centerNotice?.id) {
        val notice = centerNotice ?: return@LaunchedEffect
        delay(1_450)
        if (centerNotice?.id == notice.id) {
            centerNotice = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (!hideBottomBar) {
                    XiaoLingBottomTabBar(
                        selectedTab = navigation.tab,
                        onSelected = navigation::selectTab,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ConversationPage(
                    state = state.toConversationUiState(),
                    actions = conversationActions,
                    visible = navigation.tab == XiaoLingAppTab.CONVERSATION,
                    modifier = Modifier.matchParentSize(),
                )

                if (navigation.tab == XiaoLingAppTab.SETTINGS) {
                    SettingsPage(
                        state = state,
                        viewModel = viewModel,
                        pane = navigation.settingsPane,
                        onOpenProviderManagement = { navigation.openSettingsPane(SettingsPane.PROVIDER_MANAGEMENT) },
                        onOpenNetworkRequest = { navigation.openSettingsPane(SettingsPane.NETWORK_REQUEST) },
                        onOpenPromptSettings = { navigation.openSettingsPane(SettingsPane.PROMPT_SETTINGS) },
                        onOpenAgentProfileManagement = { navigation.openSettingsPane(SettingsPane.AGENT_PROFILE_MANAGEMENT) },
                        onOpenDeviceAgent = { navigation.openSettingsPane(SettingsPane.DEVICE_AGENT) },
                        onOpenAnswerabilityShadow = { navigation.openSettingsPane(SettingsPane.ANSWERABILITY_SHADOW) },
                        onOpenMemoryManagement = {
                            navigation.openSettingsPane(SettingsPane.MEMORY_MANAGEMENT)
                        },
                        onOpenKnowledgeManagement = {
                            navigation.openSettingsPane(
                                pane = SettingsPane.KNOWLEDGE_MANAGEMENT,
                                requestedKnowledgeDocumentId = null,
                            )
                        },
                        onOpenKnowledgeRelevanceRollout = {
                            navigation.openSettingsPane(SettingsPane.KNOWLEDGE_RELEVANCE_ROLLOUT)
                        },
                        onOpenSkillManagement = {
                            navigation.openSettingsPane(SettingsPane.SKILL_MANAGEMENT)
                        },
                        onOpenWorkflowManagement = {
                            viewModel.refreshWorkflows()
                            navigation.openSettingsPane(SettingsPane.WORKFLOW_MANAGEMENT)
                        },
                        onOpenAgentRunHistory = {
                            navigation.openSettingsPane(SettingsPane.AGENT_RUN_HISTORY)
                        },
                        onOpenProcessExitObservations = {
                            viewModel.refreshProcessExitObservations()
                            navigation.openSettingsPane(SettingsPane.PROCESS_EXIT_OBSERVATIONS)
                        },
                        onExportBackup = { exportBackupLauncher.launch(viewModel.defaultBackupFileName()) },
                        onImportBackup = { importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        onImportSkill = { importSkillLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        requestedKnowledgeDocumentId = navigation.requestedKnowledgeDocumentId,
                        onBackToSettings = {
                            navigation.openSettingsPane(
                                pane = SettingsPane.ROOT,
                                requestedKnowledgeDocumentId = null,
                            )
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }

        centerNotice?.let {
            CenterNoticePopup(
                notice = it,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    state.pendingAgentRetryConfirmation?.let { pending ->
        AgentRetryConfirmationDialog(
            pending = pending,
            onConfirm = viewModel::confirmAgentRunRetry,
            onDismiss = viewModel::cancelAgentRunRetry,
        )
    }
    state.pendingWorkflowRetryConfirmation?.let { pending ->
        WorkflowRetryConfirmationDialog(
            pending = pending,
            onConfirm = viewModel::confirmWorkflowRunRetry,
            onDismiss = viewModel::cancelWorkflowRunRetry,
        )
    }
    pendingBackupRestoreUri?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingBackupRestoreUri = null },
            title = { Text("恢复本地备份") },
            text = { Text("这会替换当前 Room 数据库，并要求退出后重新打开应用。Provider API Key 只能在原设备 Keystore 仍存在时解密。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingBackupRestoreUri = null
                        viewModel.restoreBackup(uri)
                    },
                    enabled = !state.backupBusy,
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackupRestoreUri = null }) { Text("取消") }
            },
        )
    }
    state.editingMemory?.let { draft ->
        AgentMemoryEditDialog(
            draft = draft,
            saving = draft.id in state.mutatingMemoryIds,
            viewModel = viewModel,
        )
    }
    state.pendingMemoryDelete?.let { memory ->
        AgentMemoryDeleteDialog(
            memory = memory,
            deleting = memory.id in state.mutatingMemoryIds,
            onConfirm = viewModel::confirmMemoryDelete,
            onDismiss = viewModel::cancelMemoryDelete,
        )
    }
    state.pendingLocalSkillDelete?.let { skill ->
        LocalSkillDeleteDialog(
            skill = skill,
            deleting = skill.definition.id in state.mutatingSkillIds,
            onConfirm = viewModel::confirmLocalSkillDelete,
            onDismiss = viewModel::cancelLocalSkillDelete,
        )
    }
}

private val agentMemoryTypes = listOf("Preference", "ProfileFact", "Episode", "Procedure")

private data class CenterNotice(
    val text: String,
    val success: Boolean = true,
    val id: Long = System.nanoTime(),
)

@Composable
private fun CenterNoticePopup(
    notice: CenterNotice,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (notice.success) {
        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
    }
    val contentColor = if (notice.success) {
        MaterialTheme.colorScheme.inverseOnSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        modifier = modifier
            .padding(horizontal = 36.dp),
    ) {
        // long: 轻提示只承担状态反馈，不绑定 clickable 或 pointerInput，避免提示出现时拦截页面点击。
        Text(
            text = notice.text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun AgentRetryConfirmationDialog(
    pending: AgentRetryConfirmationUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认重新运行",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = pending.goal,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val evidence = presentAgentTaskRetryEvidence(pending.evidenceCode)
                Text(
                    text = "${evidence.label} · ${evidence.code.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${evidence.detail} ${evidence.suggestedAction} 写入工具仍需重新审批。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("创建新 Run")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun WorkflowRetryConfirmationDialog(
    pending: WorkflowRetryConfirmationUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认重试工作流", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pending.workflowName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "将从步骤 ${pending.retryFromSequence} 重新执行，复用前 ${pending.reusedStepCount} 个已完成步骤。新 Run 会保留来源 Run ID，旧 Run 和历史快照不会修改。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "待重试步骤可能已产生部分外部副作用；写入工具仍会重新请求审批。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("创建新 Run")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun AgentMemoryEditDialog(
    draft: AgentMemoryEditUiState,
    saving: Boolean,
    viewModel: XiaoLingViewModel,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) viewModel.cancelMemoryEdit() },
        title = {
            Text(
                text = "编辑长期记忆",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = draft.content,
                    onValueChange = viewModel::updateMemoryEditContent,
                    label = { Text("记忆内容") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = draft.tags,
                    onValueChange = viewModel::updateMemoryEditTags,
                    label = { Text("标签") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("类型", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    agentMemoryTypes.forEach { type ->
                        val selected = type == draft.type
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !saving) { viewModel.updateMemoryEditType(type) },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 9.dp),
                            ) {
                                Text(type, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(
                    text = "置信度 " + (draft.confidence * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = draft.confidence.toFloat(),
                    onValueChange = { viewModel.updateMemoryEditConfidence(it.toDouble()) },
                    enabled = !saving,
                    valueRange = 0f..1f,
                    steps = 19,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::saveMemoryEdit,
                enabled = !saving && draft.content.isNotBlank(),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelMemoryEdit, enabled = !saving) {
                Text("取消")
            }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun AgentMemoryDeleteDialog(
    memory: AgentMemoryRecord,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除长期记忆", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "删除后，该记忆及其检索索引会立即移除，之后不再参与 Agent 检索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        },
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
internal fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 1.dp),
    )
}

@Composable
internal fun ThemeModeSelector(
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = shape,
            modifier = Modifier
                .height(26.dp)
                .clip(shape)
                .clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = themeMode.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "切换主题", modifier = Modifier.size(13.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(AppThemeMode.DARK, AppThemeMode.LIGHT, AppThemeMode.SYSTEM).forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onThemeModeChanged(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun XiaoLingUiState.toConversationUiState(): ConversationUiState {
    return ConversationProjection.project(
        themeMode = themeMode,
        profiles = profiles,
        profileName = profileName,
        model = model,
        enabledModels = enabledModels,
        prompt = prompt,
        sendingMessage = sendingMessage,
        apiMode = apiMode,
        streamingEnabled = streamingEnabled,
        reasoningSummaryEnabled = reasoningSummaryEnabled,
        agentMemoryRecallEnabled = agentMemoryRecallEnabled,
        agentProfiles = agentProfiles,
        selectedAgentProfileId = selectedAgentProfileId,
        pendingImage = pendingImage,
        pendingDocument = pendingDocument,
        pendingSharedDraft = pendingSharedDraft,
        sharedDraftImported = sharedDraftImported,
        attachingImage = attachingImage,
        attachingDocument = attachingDocument,
        loadingConversationMessages = loadingConversationMessages,
        chatMessages = chatMessages,
        knowledgeReferenceStatuses = knowledgeReferenceStatuses,
        failedKnowledgeReferenceStatuses = failedKnowledgeReferenceStatuses,
        answerabilityNotices = answerabilityNotices,
        conversations = conversations,
        selectedConversationId = selectedConversationId,
        conversationTitle = conversationTitle,
        activeAgentRun = activeAgentRun,
        pendingAgentApproval = pendingAgentApproval,
    )
}

private fun XiaoLingUiState.toWorkflowManagementUiState(): WorkflowManagementUiState {
    // long: 应用壳只在模块边界投影 Workflow 字段，页面不再感知整份全局状态，也不会自行关联 Run 与调度记录。
    return WorkflowManagementProjection.project(
        loading = loadingWorkflows,
        error = workflowError,
        workflows = workflows,
        runs = workflowRuns,
        scheduledTasks = scheduledTasks,
        schedules = workflowSchedules,
        mutatingWorkflowIds = mutatingWorkflowIds,
        mutatingScheduledTaskIds = mutatingScheduledTaskIds,
        mutatingWorkflowScheduleIds = mutatingWorkflowScheduleIds,
        schedulingWorkflowId = schedulingWorkflowId,
        runningWorkflowId = runningWorkflowId,
        sendingMessage = sendingMessage,
    )
}

private fun XiaoLingUiState.toAgentTaskCenterUiState(): AgentTaskCenterUiState {
    // long: 任务中心只接收 Run 历史和两个稳定操作状态，确认弹层与跨会话导航继续由应用壳消费，避免页面生命周期接管全局流程。
    return AgentTaskCenterProjection.project(
        loading = loadingAgentRunHistory,
        error = agentRunHistoryError,
        history = agentRunHistory,
        selectedRunId = selectedAgentRunId,
        retryingRunId = retryingAgentRunId,
    )
}

private fun XiaoLingUiState.toMemoryManagementUiState(): MemoryManagementUiState {
    // long: 长期记忆页面只接收列表、候选和稳定操作状态；编辑/删除弹窗及来源导航信号仍由应用壳持有，避免页面生命周期接管跨域流程。
    return MemoryManagementProjection.project(
        loading = loadingMemories,
        error = memoryError,
        memories = memories,
        candidatesEnabled = memoryCandidatesEnabled,
        loadingCandidates = loadingMemoryCandidates,
        candidates = memoryCandidates,
        searchQuery = memorySearchQuery,
        filter = memoryFilter,
        selectedMemoryId = selectedMemoryId,
        mutatingMemoryIds = mutatingMemoryIds,
        mutatingCandidateIds = mutatingMemoryCandidateIds,
        deletedMemoryForUndo = deletedMemoryForUndo,
    )
}

private fun XiaoLingUiState.toProviderManagementUiState(): ProviderManagementUiState {
    // long: Provider 页面只接收列表、编辑草稿和同步状态；全局返回优先级与底栏显隐继续由应用壳统一处理。
    return ProviderManagementProjection.project(
        profiles = profiles,
        selectedProfileId = selectedProfileId,
        syncingProfileIds = syncingProfileIds,
        syncingAllProfiles = syncingAllProfiles,
        batchSyncResults = batchSyncResults,
        draft = manageDraft,
        result = result,
    )
}

private fun XiaoLingUiState.toAgentProfileManagementUiState(): AgentProfileManagementUiState {
    // long: Agent Profile 页面只接收配置列表和编辑所需资源；返回、底栏与全局保存结果仍由应用壳统一处理。
    return AgentProfileManagementProjection.project(
        profiles = agentProfiles,
        providers = profiles,
        selectedProfileId = selectedAgentProfileId,
        mutatingProfileIds = mutatingAgentProfileIds,
        error = agentProfileError,
        tools = registeredAgentTools,
        skills = skills,
    )
}

private fun XiaoLingUiState.toAgentSkillManagementUiState(): AgentSkillManagementUiState {
    // long: Skill 页面只接收依赖与 Run 审计的只读投影；系统文件选择、删除确认及全局结果提示仍由应用壳持有。
    return AgentSkillManagementProjection.project(
        skills = skills,
        loading = loadingSkills,
        importing = importingSkill,
        mutatingSkillIds = mutatingSkillIds,
        registeredTools = registeredAgentTools,
        runHistory = agentRunHistory,
        loadingAudits = loadingAgentRunHistory,
        auditError = agentRunHistoryError,
        error = skillError,
    )
}

private fun XiaoLingUiState.toProcessExitObservationUiState(): ProcessExitObservationUiState {
    // long: 诊断页面只消费独立退出账本的只读状态，不接收 Agent、Workflow 或 Task 字段，避免 UI 通过时间邻近制造不存在的关联。
    return ProcessExitObservationUiState(
        observations = processExitObservations,
        loading = loadingProcessExitObservations,
        error = processExitObservationError,
    )
}

private fun XiaoLingUiState.toNetworkRequestSettingsUiState(): NetworkRequestSettingsUiState {
    return NetworkRequestSettingsUiState(userAgent = userAgent)
}

private fun XiaoLingUiState.toSettingsRootUiState(): SettingsRootUiState {
    return SettingsRootProjection.project(
        themeMode = themeMode,
        providers = profiles,
        agentProfiles = agentProfiles,
        selectedAgentProfileId = selectedAgentProfileId,
        answerabilityShadowEnabled = answerabilityShadowEnabled,
        skills = skills,
        workflows = workflows,
        workflowRunStatuses = workflowRuns.map { it.run.status },
        agentRunStatuses = agentRunHistory.map { it.snapshot.run.status },
        processExitObservationCount = processExitObservations.size,
        backupBusy = backupBusy,
    )
}

@Composable
private fun SettingsPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    pane: SettingsPane,
    onOpenProviderManagement: () -> Unit,
    onOpenNetworkRequest: () -> Unit,
    onOpenPromptSettings: () -> Unit,
    onOpenAgentProfileManagement: () -> Unit,
    onOpenDeviceAgent: () -> Unit,
    onOpenAnswerabilityShadow: () -> Unit,
    onOpenMemoryManagement: () -> Unit,
    onOpenKnowledgeManagement: () -> Unit,
    onOpenKnowledgeRelevanceRollout: () -> Unit,
    onOpenSkillManagement: () -> Unit,
    onOpenWorkflowManagement: () -> Unit,
    onOpenAgentRunHistory: () -> Unit,
    onOpenProcessExitObservations: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportSkill: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    requestedKnowledgeDocumentId: String?,
    onBackToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocker = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // long: 设置页覆盖在常驻对话页之上，空白区域也要吃掉点击，避免触发底层对话页的输入框和消息长按。
            .clickable(interactionSource = blocker, indication = null) {},
    ) {
        when {
            state.manageDraft != null || pane == SettingsPane.PROVIDER_MANAGEMENT -> ProviderManagementPage(
                state = state.toProviderManagementUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.NETWORK_REQUEST -> NetworkRequestSettingsPage(
                state = state.toNetworkRequestSettingsUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.PROMPT_SETTINGS -> PromptSettingsPage(
                settings = state.promptSettings,
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.AGENT_PROFILE_MANAGEMENT -> AgentProfileManagementPage(
                state = state.toAgentProfileManagementUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.DEVICE_AGENT -> DeviceAgentSettingsPage(
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.ANSWERABILITY_SHADOW -> AnswerabilityShadowSettingsContent(
                enabled = state.answerabilityShadowEnabled,
                sampleSummary = state.answerabilityShadowSampleSummary,
                onEnabledChanged = viewModel::updateAnswerabilityShadowEnabled,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.MEMORY_MANAGEMENT -> MemoryManagementPage(
                state = state.toMemoryManagementUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.KNOWLEDGE_MANAGEMENT -> KnowledgeManagementPage(
                onBack = onBackToSettings,
                preferredDocumentId = requestedKnowledgeDocumentId,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.KNOWLEDGE_RELEVANCE_ROLLOUT -> KnowledgeRelevanceRolloutSettingsPage(
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.SKILL_MANAGEMENT -> AgentSkillManagementPage(
                state = state.toAgentSkillManagementUiState(),
                actions = object : AgentSkillManagementActions {
                    override fun refreshSkills() = viewModel.refreshSkills()

                    override fun refreshSkillAudits() = viewModel.refreshAgentRunHistory()

                    override fun requestSkillImport() {
                        // long: 页面只声明导入意图，Android 文件选择器的生命周期仍由应用宿主持有，避免 UI module 捕获 Activity launcher。
                        onImportSkill()
                    }

                    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
                        viewModel.setSkillEnabled(skillId, enabled)
                    }

                    override fun requestLocalSkillDelete(skillId: String) {
                        viewModel.requestLocalSkillDelete(skillId)
                    }
                },
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.WORKFLOW_MANAGEMENT -> WorkflowManagementPage(
                state = state.toWorkflowManagementUiState(),
                actions = viewModel,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.AGENT_RUN_HISTORY -> AgentTaskCenterPage(
                state = state.toAgentTaskCenterUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.PROCESS_EXIT_OBSERVATIONS -> ProcessExitObservationPage(
                state = state.toProcessExitObservationUiState(),
                actions = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            else -> SettingsRootPage(
                state = state.toSettingsRootUiState(),
                actions = object : SettingsRootActions {
                    override fun updateThemeMode(value: AppThemeMode) = viewModel.updateThemeMode(value)

                    override fun openProviderManagement() = onOpenProviderManagement()
                    override fun openNetworkRequest() = onOpenNetworkRequest()
                    override fun openPromptSettings() = onOpenPromptSettings()
                    override fun openAgentProfileManagement() = onOpenAgentProfileManagement()
                    override fun openDeviceAgent() = onOpenDeviceAgent()
                    override fun openAnswerabilityShadow() = onOpenAnswerabilityShadow()
                    override fun openMemoryManagement() = onOpenMemoryManagement()
                    override fun openKnowledgeManagement() = onOpenKnowledgeManagement()
                    override fun openKnowledgeRelevanceRollout() = onOpenKnowledgeRelevanceRollout()
                    override fun openSkillManagement() = onOpenSkillManagement()
                    override fun openWorkflowManagement() = onOpenWorkflowManagement()
                    override fun openAgentRunHistory() = onOpenAgentRunHistory()
                    override fun openProcessExitObservations() = onOpenProcessExitObservations()
                    override fun exportBackup() = onExportBackup()
                    override fun importBackup() = onImportBackup()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun LocalSkillDeleteDialog(
    skill: AgentSkillRecord,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除本地 Skill", style = MaterialTheme.typography.titleSmall) },
        text = { Text(skill.definition.name, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(if (deleting) "删除中" else "删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        },
    )
}

private fun OperationResult.shouldStayInline(): Boolean = requestUrl != null || latencyMs != null
