package com.longdev.xiaoling.ui

import android.app.Activity
import android.Manifest
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.xiaoling.agent.AgentCommand
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemoryRecord
import com.longdev.xiaoling.agent.AgentMemoryDecayPolicy
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunMetricsPolicy
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.AgentSkillValidationStatus
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.system.ProcessExitEvidenceKind
import com.longdev.xiaoling.system.ProcessExitObservation
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
import com.longdev.xiaoling.ui.memory.MemoryManagementPage
import com.longdev.xiaoling.ui.memory.MemoryManagementProjection
import com.longdev.xiaoling.ui.memory.MemoryManagementUiState
import com.longdev.xiaoling.ui.provider.ProviderManagementPage
import com.longdev.xiaoling.ui.provider.ProviderManagementProjection
import com.longdev.xiaoling.ui.provider.ProviderManagementUiState
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import com.longdev.xiaoling.ui.theme.LocalChatBubblePalette
import com.longdev.xiaoling.ui.workflow.WorkflowManagementPage
import com.longdev.xiaoling.ui.workflow.WorkflowManagementProjection
import com.longdev.xiaoling.ui.workflow.WorkflowManagementUiState
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
@Composable
fun XiaoLingApp(viewModel: XiaoLingViewModel = viewModel()) {
    val state = viewModel.uiState
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
    val chatListState = rememberLazyListState()
    val chatScrollState = remember(chatListState) { ChatScrollState(chatListState) }
    var centerNotice by remember { mutableStateOf<CenterNotice?>(null) }

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
                    state = state,
                    viewModel = viewModel,
                    chatScrollState = chatScrollState,
                    visible = navigation.tab == XiaoLingAppTab.CONVERSATION,
                    onAttachImage = {
                        attachImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                    },
                    onAttachDocument = {
                        attachDocumentLauncher.launch(DocumentAttachmentPolicy.pickerMimeTypes())
                    },
                    onOpenKnowledgeDocument = { documentId ->
                        navigation.openKnowledgeDocument(documentId)
                    },
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
                            viewModel.refreshSkills()
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
private fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 1.dp),
    )
}

@Composable
private fun ThemeModeSelector(
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

private const val CHAT_TAIL_NEAR_THRESHOLD_PX = 96

private data class ChatTailScrollSnapshot(
    val scrolling: Boolean,
    val nearTail: Boolean,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

private data class ChatAutoScrollKey(
    val conversationId: String,
    val lastItemIndex: Int,
    val lastMessageId: String?,
    val lastMessageRole: String?,
    val lastMessageTextLength: Int?,
    val firstTokenLatencyMs: Long?,
    val latencyMs: Long?,
    val sendingMessage: Boolean,
)

private fun XiaoLingUiState.chatAutoScrollKey(): ChatAutoScrollKey {
    val lastMessage = chatMessages.lastOrNull()
    return ChatAutoScrollKey(
        conversationId = selectedConversationId,
        lastItemIndex = chatMessages.size,
        lastMessageId = lastMessage?.id,
        lastMessageRole = lastMessage?.role,
        lastMessageTextLength = lastMessage?.text?.length,
        firstTokenLatencyMs = lastMessage?.meta?.firstTokenLatencyMs,
        latencyMs = lastMessage?.meta?.latencyMs,
        sendingMessage = sendingMessage,
    )
}

private class ChatScrollState(
    val listState: LazyListState,
) {
    var boundConversationId by mutableStateOf<String?>(null)
    var handledAutoScrollKey by mutableStateOf<ChatAutoScrollKey?>(null)
    var shouldFollowTail by mutableStateOf(true)
    var showNewContentButton by mutableStateOf(false)
    var programmaticScrollActive by mutableStateOf(false)
}

private fun LazyListState.isNearChatTail(tailItemIndex: Int): Boolean {
    if (tailItemIndex <= 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
    val distanceToViewportEnd = layoutInfo.viewportEndOffset - itemBottom
    return lastVisibleItem.index >= tailItemIndex - 1 && distanceToViewportEnd >= -CHAT_TAIL_NEAR_THRESHOLD_PX
}

@Composable
private fun ConversationPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    chatScrollState: ChatScrollState,
    visible: Boolean,
    onAttachImage: () -> Unit,
    onAttachDocument: () -> Unit,
    onOpenKnowledgeDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatListState = chatScrollState.listState
    val scrollScope = rememberCoroutineScope()
    val lastChatItemIndex = state.chatMessages.size
    val lastChatMessage = state.chatMessages.lastOrNull()
    val autoScrollKey = state.chatAutoScrollKey()
    val displayedKnowledgeReferences = state.chatMessages
        .flatMap(ChatMessage::knowledgeReferencesForDisplay)
        .distinct()
    val isAtChatTail by remember(lastChatItemIndex) {
        derivedStateOf { chatListState.isNearChatTail(lastChatItemIndex) }
    }

    LaunchedEffect(state.selectedConversationId, visible) {
        if (!visible) return@LaunchedEffect
        if (chatScrollState.boundConversationId == state.selectedConversationId) {
            return@LaunchedEffect
        }
        chatScrollState.boundConversationId = state.selectedConversationId
        chatScrollState.handledAutoScrollKey = null
        // long: 只有真正切换会话时才重置阅读位置；底部 Tab 来回切换只是临时离开对话页，必须保留用户离开前的滚动位置。
        chatScrollState.shouldFollowTail = true
        chatScrollState.showNewContentButton = false
        if (lastChatItemIndex > 0) {
            delay(24)
            chatScrollState.programmaticScrollActive = true
            try {
                chatListState.scrollToItem(lastChatItemIndex)
            } finally {
                chatScrollState.programmaticScrollActive = false
            }
        }
    }

    LaunchedEffect(state.selectedConversationId, displayedKnowledgeReferences, visible) {
        if (visible) {
            viewModel.refreshKnowledgeReferenceStatuses(displayedKnowledgeReferences)
        }
    }

    LaunchedEffect(chatListState, lastChatItemIndex, visible) {
        if (!visible) return@LaunchedEffect
        snapshotFlow {
            ChatTailScrollSnapshot(
                scrolling = chatListState.isScrollInProgress,
                nearTail = chatListState.isNearChatTail(lastChatItemIndex),
                firstVisibleItemIndex = chatListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = chatListState.firstVisibleItemScrollOffset,
            )
        }.collect { snapshot ->
            when {
                snapshot.nearTail -> {
                    chatScrollState.shouldFollowTail = true
                    chatScrollState.showNewContentButton = false
                }
                snapshot.scrolling && !chatScrollState.programmaticScrollActive -> {
                    chatScrollState.shouldFollowTail = false
                }
            }
        }
    }

    LaunchedEffect(autoScrollKey, visible) {
        if (!visible) return@LaunchedEffect
        if (chatScrollState.handledAutoScrollKey == autoScrollKey) {
            return@LaunchedEffect
        }
        chatScrollState.handledAutoScrollKey = autoScrollKey
        if (lastChatItemIndex > 0) {
            val forceScrollForUserMessage = lastChatMessage?.role == "user"
            val shouldAutoScroll = forceScrollForUserMessage || chatScrollState.shouldFollowTail || isAtChatTail
            if (shouldAutoScroll) {
                chatScrollState.shouldFollowTail = true
                chatScrollState.showNewContentButton = false
                delay(24)
                chatScrollState.programmaticScrollActive = true
                try {
                    chatListState.scrollToItem(lastChatItemIndex)
                } finally {
                    chatScrollState.programmaticScrollActive = false
                }
                // long: 流式结束会把普通文本切换成完整 Markdown 渲染，图片、表格和代码块完成测量后高度可能再次变化；第二次尾部校准只在用户仍选择跟随时执行，避免把正在翻历史的用户拉回底部。
                delay(if (lastChatMessage?.role == "assistant" && lastChatMessage.meta?.latencyMs != null) 180 else 64)
                if (chatScrollState.shouldFollowTail || chatListState.isNearChatTail(lastChatItemIndex)) {
                    chatScrollState.programmaticScrollActive = true
                    try {
                        chatListState.scrollToItem(lastChatItemIndex)
                    } finally {
                        chatScrollState.programmaticScrollActive = false
                    }
                }
            } else {
                chatScrollState.showNewContentButton = true
            }
        }
    }
    val waitingForModelStart = state.sendingMessage && state.chatMessages.lastOrNull()
        ?.takeIf { it.role == "assistant" }
        ?.text
        .isNullOrBlank() && state.pendingAgentApproval == null

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // long: 部分厂商系统不会稳定执行 adjustResize；这里在 Compose 层消费 IME inset，让键盘弹出时只压缩对话区域并把输入框顶到键盘上方。
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ConversationHeader(
                state = state,
                onConversationSelected = viewModel::selectConversation,
                onNewConversation = viewModel::openNewConversation,
                onDeleteConversation = viewModel::deleteCurrentConversation,
                onThemeModeChanged = viewModel::updateThemeMode,
            )
            ModelSelectionBar(
                state = state,
                onProviderSelected = viewModel::selectProfile,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                ) {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (state.chatMessages.isEmpty()) {
                            item {
                                Text(
                                    text = "选择模型后输入消息开始对话。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(count = state.chatMessages.size) { index ->
                            val message = state.chatMessages[index]
                            ChatBubble(
                                message = message,
                                knowledgeReferenceStatuses = state.knowledgeReferenceStatuses,
                                failedKnowledgeReferenceStatuses = state.failedKnowledgeReferenceStatuses,
                                answerabilityNotice = state.answerabilityNotices[message.id],
                                onOpenKnowledgeDocument = onOpenKnowledgeDocument,
                                onReuseUserMessage = viewModel::updatePrompt,
                            )
                            if (state.activeAgentRun?.run?.userMessageId == message.id) {
                                Spacer(modifier = Modifier.height(7.dp))
                                AgentRunTimelineCard(
                                    snapshot = state.activeAgentRun,
                                    approval = state.pendingAgentApproval?.takeIf {
                                        it.runId == state.activeAgentRun.run.id
                                    },
                                    onApprove = viewModel::approvePendingAgentTool,
                                    onReject = viewModel::rejectPendingAgentTool,
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(1.dp))
                        }
                    }
                }

                if (chatScrollState.showNewContentButton && state.chatMessages.isNotEmpty()) {
                    NewChatContentButton(
                        onClick = {
                            chatScrollState.shouldFollowTail = true
                            chatScrollState.showNewContentButton = false
                            scrollScope.launch {
                                chatScrollState.programmaticScrollActive = true
                                try {
                                    chatListState.animateScrollToItem(lastChatItemIndex)
                                } finally {
                                    chatScrollState.programmaticScrollActive = false
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                    )
                }
            }

            MessageInputBar(
                state = state,
                prompt = state.prompt,
                sendingMessage = state.sendingMessage,
                enabled = state.enabledModels.isNotEmpty(),
                onModelSelected = viewModel::updateModel,
                onResponsesChanged = viewModel::updateResponsesEnabled,
                onStreamingChanged = viewModel::updateStreamingEnabled,
                onReasoningSummaryChanged = viewModel::updateReasoningSummaryEnabled,
                onAgentMemoryRecallChanged = viewModel::updateAgentMemoryRecallEnabled,
                onAgentProfileSelected = viewModel::selectAgentProfile,
                onPromptChange = viewModel::updatePrompt,
                onAttachImage = onAttachImage,
                onAttachDocument = onAttachDocument,
                onRemovePendingImage = viewModel::removePendingImage,
                onRemovePendingDocument = viewModel::removePendingDocument,
                onOpenPendingSharedDraft = viewModel::openPendingSharedDraft,
                onDiscardPendingSharedDraft = viewModel::discardPendingSharedDraft,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGenerating,
            )
        }

        if (waitingForModelStart) {
            ModelWaitingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ConversationHeader(
    state: XiaoLingUiState,
    onConversationSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageTitle("对话")
        ThemeModeSelector(
            themeMode = state.themeMode,
            onThemeModeChanged = onThemeModeChanged,
        )
        Spacer(Modifier.weight(1f))
        Box {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(28.dp)
                    .widthIn(max = 150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.conversationTitle.ifBlank { "新会话" }.compactModelLabel(12),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "切换会话", modifier = Modifier.size(13.dp))
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                state.conversations.sortedByDescending { it.updatedAt }.forEach { conversation ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                conversation.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onConversationSelected(conversation.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = onNewConversation, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Add, contentDescription = "新建会话", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDeleteConversation, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "删除当前会话", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ModelSelectionBar(
    state: XiaoLingUiState,
    onProviderSelected: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProviderDropdown(state, onSelected = onProviderSelected)
            if (state.enabledModels.isEmpty()) {
                Text(
                    text = "请先到设置页获取上游模型并勾选可对话模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    state: XiaoLingUiState,
    prompt: String,
    sendingMessage: Boolean,
    enabled: Boolean,
    onModelSelected: (String) -> Unit,
    onResponsesChanged: (Boolean) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onReasoningSummaryChanged: (Boolean) -> Unit,
    onAgentMemoryRecallChanged: (Boolean) -> Unit,
    onAgentProfileSelected: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onAttachImage: () -> Unit,
    onAttachDocument: () -> Unit,
    onRemovePendingImage: () -> Unit,
    onRemovePendingDocument: () -> Unit,
    onOpenPendingSharedDraft: () -> Unit,
    onDiscardPendingSharedDraft: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canRunAgent = AgentCommand.matches(prompt)
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val attaching = state.attachingImage || state.attachingDocument
    val canSend = !sendingMessage && !attaching && !state.loadingConversationMessages &&
        prompt.isNotBlank() && (enabled || canRunAgent)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            state.pendingSharedDraft?.let { payload ->
                SharedDraftPendingNotice(
                    payload = payload,
                    enabled = !sendingMessage && !attaching && !state.loadingConversationMessages,
                    onOpen = onOpenPendingSharedDraft,
                    onDiscard = onDiscardPendingSharedDraft,
                )
            }
            if (state.sharedDraftImported) {
                SharedDraftSourceLabel()
            }
            state.pendingImage?.let { attachment ->
                PendingImagePreview(
                    attachment = attachment,
                    enabled = !sendingMessage && !attaching,
                    onRemove = onRemovePendingImage,
                    modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp),
                )
            }
            state.pendingDocument?.let { attachment ->
                PendingDocumentPreview(
                    attachment = attachment,
                    enabled = !sendingMessage && !attaching,
                    onRemove = onRemovePendingDocument,
                    modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 118.dp)
                    .padding(10.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    enabled = !sendingMessage,
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 48.dp, bottom = 34.dp),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (prompt.isBlank()) {
                                Text(
                                    text = "输入消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = { attachmentMenuExpanded = true },
                    enabled = !sendingMessage && !attaching && !state.loadingConversationMessages && enabled,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(32.dp),
                ) {
                    if (attaching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.8.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "添加附件",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = attachmentMenuExpanded,
                    onDismissRequest = { attachmentMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("图片", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
                        onClick = {
                            attachmentMenuExpanded = false
                            onAttachImage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("文档", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        onClick = {
                            attachmentMenuExpanded = false
                            onAttachDocument()
                        },
                    )
                }
                InputOptionRow(
                    state = state,
                    enabled = !sendingMessage && (enabled || canRunAgent),
                    onModelSelected = onModelSelected,
                    onResponsesChanged = onResponsesChanged,
                    onStreamingChanged = onStreamingChanged,
                    onReasoningSummaryChanged = onReasoningSummaryChanged,
                    agentCommand = canRunAgent,
                    onAgentMemoryRecallChanged = onAgentMemoryRecallChanged,
                    onAgentProfileSelected = onAgentProfileSelected,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 36.dp, end = 52.dp),
                )
                Button(
                    onClick = if (sendingMessage) onStop else onSend,
                    enabled = sendingMessage || canSend,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.outline,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp),
                ) {
                    Icon(
                        imageVector = if (sendingMessage) Icons.Default.Close else Icons.Default.ArrowUpward,
                        contentDescription = if (sendingMessage) "停止生成" else "发送",
                        modifier = Modifier.size(if (sendingMessage) 18.dp else 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SharedDraftPendingNotice(
    payload: SharedDraftPayload,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 10.dp, top = 5.dp, end = 4.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "来自外部应用的分享",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (payload.imageUri == null) "文本" else "图片",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onOpen, enabled = enabled) {
            Text("打开分享", style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onDiscard, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "忽略分享", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun SharedDraftSourceLabel() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "已从外部分享导入",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingImagePreview(
    attachment: ImageAttachment,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageAttachmentPreview(
            attachment = attachment,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${((attachment.byteSize + 1023) / 1024).coerceAtLeast(1)} KB · ${attachment.mimeType}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "移除图片", modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun PendingDocumentPreview(
    attachment: DocumentAttachment,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val budgetLabel = attachment.pageCount?.let { "$it 页" }
                ?: attachment.characterCount?.let { "$it 字符" }
                ?: "已验证"
            Text(
                text = "${formatAttachmentSize(attachment.byteSize)} · $budgetLabel",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "移除文档", modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun AgentRunTimelineCard(
    snapshot: AgentRunSnapshot,
    approval: AgentApprovalUiState?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "Agent 运行",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AgentStatusChip(snapshot.run.status)
                Spacer(Modifier.weight(1f))
                Text(
                    text = snapshot.run.updatedAt.toFullTimeLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }

            Text(
                text = "目标：${snapshot.run.goal}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            approval?.let {
                AgentApprovalCard(
                    approval = it,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                snapshot.steps.forEach { step ->
                    AgentStepRow(
                        status = step.status,
                        title = step.title,
                        detail = step.detail,
                    )
                }
            }

            snapshot.run.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AgentApprovalCard(
    approval: AgentApprovalUiState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (approval.restoredFromProcess) {
                            "进程重建后待恢复 · ${approval.riskLabel}"
                        } else {
                            "等待确认 · ${approval.riskLabel}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = approval.toolName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onReject,
                    enabled = !approval.deciding,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text("拒绝", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
                }
                Button(
                    onClick = onApprove,
                    enabled = !approval.deciding,
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text(
                        if (approval.restoredFromProcess) "批准并继续" else "批准执行",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    )
                }
            }
            Text(
                text = if (approval.restoredFromProcess) {
                    "将使用持久化工具参数，从原 Run 的审批步骤继续执行。"
                } else {
                    approval.toolDescription
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (approval.arguments.isNotEmpty()) {
                Text(
                    text = approval.arguments.entries.joinToString(" · ") { "${it.key}=${it.value}" },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewChatContentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text("新内容", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun InputOptionRow(
    state: XiaoLingUiState,
    enabled: Boolean,
    onModelSelected: (String) -> Unit,
    onResponsesChanged: (Boolean) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onReasoningSummaryChanged: (Boolean) -> Unit,
    agentCommand: Boolean,
    onAgentMemoryRecallChanged: (Boolean) -> Unit,
    onAgentProfileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var agentMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!agentCommand) {
            CompactCheckOption(
                text = "流式",
                checked = state.streamingEnabled,
                enabled = enabled,
                onCheckedChange = onStreamingChanged,
            )
            CompactCheckOption(
                text = "Resp",
                checked = state.apiMode == ApiMode.RESPONSES,
                enabled = enabled,
                onCheckedChange = onResponsesChanged,
            )
            if (state.apiMode == ApiMode.RESPONSES) {
                CompactCheckOption(
                    text = "推理",
                    checked = state.reasoningSummaryEnabled,
                    enabled = enabled,
                    onCheckedChange = onReasoningSummaryChanged,
                )
            }
        }
        if (agentCommand) {
            val selectedAgent = state.agentProfiles.firstOrNull { it.id == state.selectedAgentProfileId }
            CompactCheckOption(
                text = "记忆",
                checked = state.agentMemoryRecallEnabled,
                enabled = !state.sendingMessage && selectedAgent?.memoryEnabled == true,
                onCheckedChange = onAgentMemoryRecallChanged,
            )
            Box(modifier = Modifier.widthIn(max = 144.dp)) {
                val agentEnabled = !state.sendingMessage && state.agentProfiles.isNotEmpty()
                val shape = RoundedCornerShape(15.dp)
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = shape,
                    modifier = Modifier
                        .height(28.dp)
                        .clip(shape)
                        .clickable(enabled = agentEnabled) { agentMenuExpanded = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = selectedAgent?.avatar?.ifBlank { "A" } ?: "A",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        )
                        Text(
                            text = selectedAgent?.name?.compactModelLabel(12) ?: "选择 Agent",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DropdownMenu(
                    expanded = agentMenuExpanded,
                    onDismissRequest = { agentMenuExpanded = false },
                ) {
                    state.agentProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${profile.avatar.ifBlank { "A" }}  ${profile.name} · ${profile.model.ifBlank { "未配置模型" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {
                                onAgentProfileSelected(profile.id)
                                agentMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        if (!agentCommand) Box(modifier = Modifier.widthIn(max = 164.dp)) {
            val modelShape = RoundedCornerShape(15.dp)
            val modelEnabled = enabled && state.enabledModels.isNotEmpty()
            Surface(
                color = if (modelEnabled) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                },
                contentColor = if (modelEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = modelShape,
                modifier = Modifier
                    .height(28.dp)
                    .clip(modelShape)
                    .clickable(enabled = modelEnabled) { modelMenuExpanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = "切换模型",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (modelEnabled) 0.82f else 0.38f),
                    )
                    Text(
                        text = state.model.ifBlank { "选择模型" }.compactModelLabel(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                state.enabledModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onModelSelected(model)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCheckOption(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    val container = when {
        checked -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    }
    val content = when {
        checked -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

private fun String.compactModelLabel(maxChars: Int = 16): String {
    val value = trim()
    if (value.length <= maxChars) return value
    return value.take(maxChars - 1) + "…"
}

private fun ChatMessage.footerLabel(): String? {
    return when (role) {
        "user" -> createdAt.toFullTimeLabel()
        "assistant" -> assistantFooterLabel() ?: createdAt.toFullTimeLabel()
        "error" -> "请求失败 · ${createdAt.toFullTimeLabel()}"
        else -> createdAt.toFullTimeLabel()
    }
}

internal fun ChatMessage.assistantFooterLabel(): String? {
    val messageMeta = meta ?: return null
    val latency = messageMeta.latencyMs
    val firstTokenLatency = messageMeta.firstTokenLatencyMs
    return when {
        messageMeta.finishReason == "failed" ->
            "内容不完整 · ${messageMeta.errorKind ?: "请求失败"}"
        messageMeta.finishReason == "cancelled" ->
            "已停止${firstTokenLatency?.let { " · 首字 ${it.toSecondsText()}" }.orEmpty()}"
        messageMeta.streaming == true && firstTokenLatency != null && latency != null ->
            "首字 ${firstTokenLatency.toSecondsText()} · 耗时 ${latency.toSecondsText()}"
        messageMeta.streaming == true && latency != null ->
            "首字 - · 耗时 ${latency.toSecondsText()}"
        messageMeta.streaming == true && firstTokenLatency != null ->
            "首字 ${firstTokenLatency.toSecondsText()} · 接收中"
        latency != null ->
            "耗时 ${latency.toSecondsText()}"
        else -> null
    }
}

private fun Long.toSecondsText(): String = String.format(Locale.US, "%.2f s", this / 1000.0)

private fun Long.toFullTimeLabel(): String {
    return SimpleDateFormat(FULL_TIME_PATTERN, Locale.getDefault()).format(Date(this))
}

private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

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

@Composable
private fun ModelWaitingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(24.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
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
                userAgent = state.userAgent,
                onUserAgentChanged = viewModel::updateUserAgent,
                onResetUserAgent = viewModel::resetUserAgent,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.PROMPT_SETTINGS -> PromptSettingsPage(
                state = state,
                viewModel = viewModel,
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
                state = state,
                viewModel = viewModel,
                onImportSkill = onImportSkill,
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
            pane == SettingsPane.PROCESS_EXIT_OBSERVATIONS -> ProcessExitObservationContent(
                observations = state.processExitObservations,
                loading = state.loadingProcessExitObservations,
                error = state.processExitObservationError,
                onBack = onBackToSettings,
                onRefresh = viewModel::refreshProcessExitObservations,
                modifier = Modifier.matchParentSize(),
            )
            else -> SettingsRootPage(
                state = state,
                onThemeModeChanged = viewModel::updateThemeMode,
                onOpenProviderManagement = onOpenProviderManagement,
                onOpenNetworkRequest = onOpenNetworkRequest,
                onOpenPromptSettings = onOpenPromptSettings,
                onOpenAgentProfileManagement = onOpenAgentProfileManagement,
                onOpenDeviceAgent = onOpenDeviceAgent,
                onOpenAnswerabilityShadow = onOpenAnswerabilityShadow,
                onOpenMemoryManagement = onOpenMemoryManagement,
                onOpenKnowledgeManagement = onOpenKnowledgeManagement,
                onOpenKnowledgeRelevanceRollout = onOpenKnowledgeRelevanceRollout,
                onOpenSkillManagement = onOpenSkillManagement,
                onOpenWorkflowManagement = onOpenWorkflowManagement,
                onOpenAgentRunHistory = onOpenAgentRunHistory,
                onOpenProcessExitObservations = onOpenProcessExitObservations,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun SettingsRootPage(
    state: XiaoLingUiState,
    onThemeModeChanged: (AppThemeMode) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                onThemeModeChanged = onThemeModeChanged,
            )
        }

        SettingsEntryCard(
            title = "模型提供方管理",
            subtitle = if (state.profiles.isEmpty()) {
                "还没有模型提供方"
            } else {
                "已配置 ${state.profiles.size} 个提供方 · 可对话模型 ${state.profiles.sumOf { it.enabledModels.size }} 个"
            },
            onClick = onOpenProviderManagement,
        )

        // long: 网络请求与其他设置项保持“入口卡片 -> 独立子页”的导航层级，避免在设置列表里出现唯一可直接编辑的行。
        SettingsEntryCard(
            title = "网络请求",
            subtitle = "配置模型接口请求使用的 User-Agent",
            icon = Icons.Default.CloudDownload,
            onClick = onOpenNetworkRequest,
        )

        SettingsEntryCard(
            title = "提示词设置",
            subtitle = "普通对话 · 会话摘要 / 记忆 · Agent 回复总结",
            icon = Icons.Default.Tune,
            onClick = onOpenPromptSettings,
        )

        SettingsEntryCard(
            title = "Agent Profiles",
            subtitle = state.agentProfiles.firstOrNull { it.id == state.selectedAgentProfileId }
                ?.let { "当前：${it.name} · ${it.model.ifBlank { "未配置模型" }} · ${state.agentProfiles.size} 个 Profile" }
                ?: "配置 Agent 身份、模型、工具、Skill 和记忆边界",
            icon = Icons.Default.Tune,
            onClick = onOpenAgentProfileManagement,
        )

        SettingsEntryCard(
            title = "设备 Agent",
            subtitle = "独立开关、无障碍观察和有限前台动作",
            icon = Icons.Default.Visibility,
            onClick = onOpenDeviceAgent,
        )

        SettingsEntryCard(
            title = "答案可回答性 Shadow",
            subtitle = if (state.answerabilityShadowEnabled) {
                "已开启；仅匹配冻结 Judge 身份的前台 /agent 答案会异步观测"
            } else {
                "默认关闭；答案保存后异步生成只读观察提示"
            },
            icon = Icons.Default.Visibility,
            onClick = onOpenAnswerabilityShadow,
        )

        SettingsEntryCard(
            title = "长期记忆",
            subtitle = "搜索、编辑、禁用、删除并查看来源",
            icon = Icons.Default.Memory,
            onClick = onOpenMemoryManagement,
        )

        SettingsEntryCard(
            title = "知识库",
            subtitle = "导入文档，管理启停、替换与本地检索预览",
            icon = Icons.Default.Description,
            onClick = onOpenKnowledgeManagement,
        )

        // long: 灰度控制面独立于知识库内容管理，用户可以查看身份与撤销状态，但不能在此页绕过正式证据直接开启生产拒绝。
        SettingsEntryCard(
            title = "相关性灰度控制面",
            subtitle = "查看 Provider 身份、shadow 状态与撤销资格",
            icon = Icons.Default.Visibility,
            onClick = onOpenKnowledgeRelevanceRollout,
        )

        SettingsEntryCard(
            title = "Agent Skills",
            subtitle = if (state.skills.isEmpty()) {
                "管理内置与本地 Skill"
            } else {
                "${state.skills.count { it.enabled }} 个启用 · ${state.skills.count { it.definition.source == AgentSkillSource.LOCAL }} 个本地"
            },
            icon = Icons.Default.Settings,
            onClick = onOpenSkillManagement,
        )

        SettingsEntryCard(
            title = "工作流",
            subtitle = if (state.workflows.isEmpty()) {
                "保存可重复的 Agent 目标并查看执行记录"
            } else {
                "${state.workflows.count { it.enabled }} 个启用 · ${state.workflowRuns.count { it.run.status == WorkflowRunStatus.RUNNING }} 个运行中"
            },
            icon = Icons.Default.PlayArrow,
            onClick = onOpenWorkflowManagement,
        )

        SettingsEntryCard(
            title = "Agent 任务中心",
            subtitle = if (state.agentRunHistory.isEmpty()) {
                "查看最近 Agent Run 的步骤、审批和事件"
            } else {
                "最近 ${state.agentRunHistory.size} 条 · ${state.agentRunHistory.count { it.snapshot.run.status == AgentRunStatus.COMPLETED }} 条已完成"
            },
            onClick = onOpenAgentRunHistory,
        )

        SettingsEntryCard(
            title = "进程退出观察",
            subtitle = if (state.processExitObservations.isEmpty()) {
                "只读查看最近 30 条 Android 系统退出证据"
            } else {
                "已记录 ${state.processExitObservations.size} 条 · 不关联 Agent Run 或工作流"
            },
            icon = Icons.Default.Memory,
            onClick = onOpenProcessExitObservations,
        )

        SettingsEntryCard(
            title = "数据备份与恢复",
            subtitle = if (state.backupBusy) {
                "正在处理备份..."
            } else {
                "导出或恢复 Room 数据；API Key 依赖当前设备 Keystore"
            },
            icon = Icons.Default.Save,
            onClick = onExportBackup,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onExportBackup, enabled = !state.backupBusy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Save, contentDescription = "导出备份", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onImportBackup, enabled = !state.backupBusy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Restore, contentDescription = "恢复备份", modifier = Modifier.size(18.dp))
                    }
                }
            },
        )
    }
}

@Composable
private fun NetworkRequestSettingsPage(
    userAgent: String,
    onUserAgentChanged: (String) -> Unit,
    onResetUserAgent: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    NetworkRequestSettingsContent(
        userAgent = userAgent,
        onUserAgentChanged = onUserAgentChanged,
        onResetUserAgent = onResetUserAgent,
        onCopyUserAgent = { clipboardManager.setText(AnnotatedString(it)) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun NetworkRequestSettingsContent(
    userAgent: String,
    onUserAgentChanged: (String) -> Unit,
    onResetUserAgent: () -> Unit,
    onCopyUserAgent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
            }
            PageTitle("网络请求")
        }

        CompactSection(
            title = "User-Agent",
            action = {
                IconButton(onClick = onResetUserAgent, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Restore, contentDescription = "恢复默认 User-Agent", modifier = Modifier.size(16.dp))
                }
            },
        ) {
            CompactTextField(
                value = userAgent,
                onValueChange = onUserAgentChanged,
                label = "User-Agent",
                placeholder = ProviderRequestConfig.DEFAULT_USER_AGENT,
                minLines = 5,
                modifier = Modifier.testTag("network-request-user-agent"),
            )
            Spacer(Modifier.height(4.dp))
            // long: 复制和清空紧邻编辑区右下角，用户无需离开输入上下文即可复用或重置当前值。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = { onCopyUserAgent(userAgent) },
                    enabled = userAgent.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制 User-Agent", modifier = Modifier.size(17.dp))
                }
                IconButton(
                    onClick = { onUserAgentChanged("") },
                    enabled = userAgent.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "清空 User-Agent", modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun AgentSkillManagementPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    onImportSkill: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        if (state.skills.isEmpty() && !state.loadingSkills) viewModel.refreshSkills()
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
            PageTitle("Agent Skills")
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = viewModel::refreshSkills,
                enabled = !state.loadingSkills,
                modifier = Modifier.size(30.dp),
            ) {
                if (state.loadingSkills) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = "刷新 Skill", modifier = Modifier.size(18.dp))
                }
            }
            OutlinedButton(
                onClick = onImportSkill,
                enabled = !state.importingSkill,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 9.dp),
            ) {
                if (state.importingSkill) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入 JSON", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        state.skillError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            when {
                state.loadingSkills && state.skills.isEmpty() -> item {
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
                    count = state.skills.size,
                    key = { index -> state.skills[index].definition.id },
                ) { index ->
                    val skill = state.skills[index]
                    AgentSkillItem(
                        skill = skill,
                        mutating = skill.definition.id in state.mutatingSkillIds,
                        onEnabledChange = { enabled -> viewModel.setSkillEnabled(skill.definition.id, enabled) },
                        onDelete = { viewModel.requestLocalSkillDelete(skill.definition.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSkillItem(
    skill: AgentSkillRecord,
    mutating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(skill.definition.id) { mutableStateOf(false) }
    val definition = skill.definition
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .fillMaxWidth()
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
                        "${when (skill.validationStatus) {
                            AgentSkillValidationStatus.TRUSTED_BUILT_IN -> "内置可信"
                            AgentSkillValidationStatus.VALIDATED_LOCAL -> "本地已校验"
                        }} · v${definition.version} · ${definition.declaredRisk.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (definition.source == AgentSkillSource.LOCAL) {
                    IconButton(onClick = onDelete, enabled = !mutating, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除本地 Skill", modifier = Modifier.size(17.dp))
                    }
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !mutating,
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                )
            }
            Text(definition.description, style = MaterialTheme.typography.bodySmall)
            Text(
                definition.toolNames.joinToString(prefix = "工具："),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            }
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

@Composable
internal fun ProcessExitObservationContent(
    observations: List<ProcessExitObservation>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
                onClick = onRefresh,
                enabled = !loading,
                modifier = Modifier.size(30.dp),
            ) {
                if (loading) {
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
                        text = "记录仅用于系统诊断，不关联 Agent Run、工作流或任务。受控退出和候选记录不能作为自然低内存回收结论。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                error != null -> item(key = "process-exit-error") {
                    CompactSection(title = "读取失败") {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                loading && observations.isEmpty() -> item(key = "process-exit-loading") {
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
                observations.isEmpty() -> item(key = "process-exit-empty") {
                    CompactSection(title = "系统记录") {
                        Text(
                            text = "暂无进程退出记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(
                    count = observations.size,
                    key = { index -> observations[index].stableUiKey() },
                ) { index ->
                    ProcessExitObservationCard(observations[index])
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

@Composable
private fun SettingsEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Default.Memory,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .fillMaxWidth()
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

private enum class PromptPreviewSection {
    CHAT,
    SUMMARY,
    AGENT_SUMMARY,
}

@Composable
private fun PromptSettingsPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewSection by remember { mutableStateOf<PromptPreviewSection?>(null) }
    val settings = state.promptSettings

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
            item {
                PromptEditorSection(
                    title = "普通对话",
                    enabled = settings.chatPromptEnabled,
                    prompt = settings.chatPrompt,
                    preview = PromptPolicy.chatSystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.CHAT,
                    onEnabledChanged = viewModel::updateChatPromptEnabled,
                    onPromptChanged = viewModel::updateChatPrompt,
                    onRestore = viewModel::restoreChatPrompt,
                    onTogglePreview = {
                        previewSection = if (previewSection == PromptPreviewSection.CHAT) null else PromptPreviewSection.CHAT
                    },
                )
            }
            item {
                PromptEditorSection(
                    title = "会话摘要 / 记忆",
                    enabled = settings.summaryPromptEnabled,
                    prompt = settings.summaryPrompt,
                    preview = PromptPolicy.summarySystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.SUMMARY,
                    onEnabledChanged = viewModel::updateSummaryPromptEnabled,
                    onPromptChanged = viewModel::updateSummaryPrompt,
                    onRestore = viewModel::restoreSummaryPrompt,
                    onTogglePreview = {
                        previewSection = if (previewSection == PromptPreviewSection.SUMMARY) null else PromptPreviewSection.SUMMARY
                    },
                )
            }
            item {
                PromptEditorSection(
                    title = "Agent 回复总结",
                    enabled = settings.agentSummaryPromptEnabled,
                    prompt = settings.agentSummaryPrompt,
                    preview = PromptPolicy.agentSummarySystemPrompt(settings),
                    previewVisible = previewSection == PromptPreviewSection.AGENT_SUMMARY,
                    onEnabledChanged = viewModel::updateAgentSummaryPromptEnabled,
                    onPromptChanged = viewModel::updateAgentSummaryPrompt,
                    onRestore = viewModel::restoreAgentSummaryPrompt,
                    onTogglePreview = {
                        previewSection = if (previewSection == PromptPreviewSection.AGENT_SUMMARY) null else PromptPreviewSection.AGENT_SUMMARY
                    },
                )
            }
        }
    }
}

@Composable
private fun PromptEditorSection(
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
    CompactSection(
        title = title,
        action = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
        },
    ) {
        CompactTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            label = "自定义模板",
            minLines = 4,
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
                    .height(34.dp),
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
                    .height(34.dp),
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

@Composable
private fun ProviderDropdown(state: XiaoLingUiState, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(7.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
        ) {
            Text(
                text = state.profileName.ifBlank { "选择模型提供方" },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelected(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (action == null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    action()
                    Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    knowledgeReferenceStatuses: Map<KnowledgeReference, KnowledgeReferenceStatus>,
    failedKnowledgeReferenceStatuses: Set<KnowledgeReference>,
    answerabilityNotice: KnowledgeAnswerabilityUserNotice?,
    onOpenKnowledgeDocument: (String) -> Unit,
    onReuseUserMessage: (String) -> Unit,
) {
    val isUser = message.role == "user"
    val isError = message.role == "error"
    val clipboardManager = LocalClipboardManager.current
    var actionMenuExpanded by remember { mutableStateOf(false) }
    val palette = LocalChatBubblePalette.current
    val containerColor = when {
        isUser -> palette.userContainer
        isError -> palette.errorContainer
        else -> palette.assistantContainer
    }
    val contentColor = when {
        isUser -> palette.userContent
        isError -> palette.errorContent
        else -> palette.assistantContent
    }
    val borderColor = when {
        isUser -> palette.userBorder
        isError -> palette.errorBorder
        else -> palette.assistantBorder
    }
    val metaColor = when {
        isUser -> palette.userMeta
        isError -> palette.errorMeta
        else -> palette.assistantMeta
    }
    val bubbleShape = RoundedCornerShape(10.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = bubbleShape,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (isUser) actionMenuExpanded = true
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
                MessageBodyParts(
                    message = message,
                    contentColor = contentColor,
                )
                KnowledgeReferencesContent(
                    messageId = message.id,
                    references = message.knowledgeReferencesForDisplay(),
                    statuses = knowledgeReferenceStatuses,
                    failedReferences = failedKnowledgeReferenceStatuses,
                    answerabilityNotice = answerabilityNotice,
                    contentColor = contentColor,
                    onOpenDocument = onOpenKnowledgeDocument,
                )
                message.footerLabel()?.let { footer ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                        color = metaColor,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = actionMenuExpanded,
            onDismissRequest = { actionMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("复制", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.text))
                    actionMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("填入重发", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    onReuseUserMessage(message.text)
                    actionMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun MessageBodyParts(
    message: ChatMessage,
    contentColor: Color,
) {
    message.effectiveParts().forEachIndexed { index, part ->
        if (index > 0) Spacer(Modifier.height(7.dp))
        when (part) {
            is MessagePart.Text -> MessageTextPart(message, part.text, contentColor)
            is MessagePart.Reasoning -> ReasoningMessagePartContent(part, contentColor)
            is MessagePart.Image -> ImageMessagePartContent(part)
            is MessagePart.Document -> DocumentMessagePartContent(part, contentColor)
            is MessagePart.Tool -> ToolMessagePartContent(part, contentColor)
        }
    }
}

@Composable
internal fun ImageMessagePartContent(part: MessagePart.Image) {
    ImageAttachmentPreview(
        attachment = part.attachment,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
internal fun DocumentMessagePartContent(
    part: MessagePart.Document,
    contentColor: Color,
) {
    val attachment = part.attachment
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(contentColor.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val budgetLabel = attachment.pageCount?.let { "$it 页" }
                ?: attachment.characterCount?.let { "$it 字符" }
                ?: "已验证"
            Text(
                text = "${attachment.mimeType} · ${formatAttachmentSize(attachment.byteSize)} · $budgetLabel",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = contentColor.copy(alpha = 0.72f),
            )
        }
    }
}

private fun formatAttachmentSize(byteSize: Int): String {
    return if (byteSize >= 1024 * 1024) {
        "${"%.1f".format(Locale.US, byteSize / 1024.0 / 1024.0)} MB"
    } else {
        "${((byteSize + 1023) / 1024).coerceAtLeast(1)} KB"
    }
}

@Composable
private fun ImageAttachmentPreview(
    attachment: ImageAttachment,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(attachment) { decodeImagePreview(attachment.copyData(), maxDimension = 1_280) }
    if (bitmap == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text("图片无法显示", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = attachment.fileName,
        contentScale = contentScale,
        modifier = modifier,
    )
}

private fun decodeImagePreview(data: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    // long: Room 保存原图是为了请求和备份保真；界面只解码屏幕级缩略图，避免高分辨率附件按原始像素占用大量堆内存。
    return BitmapFactory.decodeByteArray(
        data,
        0,
        data.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

@Composable
internal fun ReasoningMessagePartContent(
    part: MessagePart.Reasoning,
    contentColor: Color,
) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    HorizontalDivider(color = contentColor.copy(alpha = 0.16f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(top = 7.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "推理摘要",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "供应商提供",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.68f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDropDown,
            contentDescription = if (expanded) "收起推理摘要" else "展开推理摘要",
            tint = contentColor.copy(alpha = 0.78f),
            modifier = Modifier.size(16.dp),
        )
    }
    if (expanded) {
        StreamingMarkdownText(
            markdown = part.text,
            contentColor = contentColor.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun MessageTextPart(
    message: ChatMessage,
    text: String,
    contentColor: Color,
) {
    if (message.role == "user") {
        Text(text = text, style = MaterialTheme.typography.bodySmall)
        return
    }
    if (message.isStreamingInProgress()) {
        // long: 流式增量会让 Markdown AST 在“半截标题、半截表格、半截代码块”之间频繁变化，实时交给 Markdown 组件会反复重排闪烁；流式中先稳定展示文本，完成后再完整渲染 Markdown。
        Text(
            text = normalizeModelMarkdown(text),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
        return
    }
    StreamingMarkdownText(markdown = text, contentColor = contentColor)
}

@Composable
private fun ToolMessagePartContent(
    part: MessagePart.Tool,
    contentColor: Color,
) {
    val presentation = part.toPresentation()
    HorizontalDivider(color = contentColor.copy(alpha = 0.16f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Memory,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "工具 · ${presentation.toolName}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = presentation.statusLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.78f),
        )
    }
    presentation.argumentsLabel?.let { arguments ->
        Text(
            text = "参数 · $arguments",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = contentColor.copy(alpha = 0.76f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        text = "结果 · ${presentation.result}",
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
        modifier = Modifier.padding(top = 4.dp),
    )
    presentation.memoryLabel?.let { memoryLabel ->
        Text(
            text = memoryLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = contentColor.copy(alpha = 0.76f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

internal fun ChatMessage.isStreamingInProgress(): Boolean {
    val messageMeta = meta ?: return false
    return role == "assistant" &&
        messageMeta.streaming == true &&
        messageMeta.latencyMs == null &&
        messageMeta.finishReason == null &&
        messageMeta.errorKind == null
}

@Composable
private fun StreamingMarkdownText(
    markdown: String,
    contentColor: Color,
) {
    val normalizedMarkdown = normalizeModelMarkdown(markdown)
    // long: 模型输出会覆盖表格、链接、引用、嵌套列表等常见 Markdown；继续维护自研解析器会让每一种语法都变成补丁，这里交给 GFM 渲染库统一处理。
    Markdown(
        content = normalizedMarkdown.ifBlank { " " },
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(
            text = contentColor,
            codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
            inlineCodeBackground = contentColor.copy(alpha = 0.10f),
            dividerColor = contentColor.copy(alpha = 0.18f),
            tableBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f),
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.bodySmall.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
            h2 = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            h3 = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
            h4 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            h5 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            h6 = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            text = MaterialTheme.typography.bodySmall,
            paragraph = MaterialTheme.typography.bodySmall,
            ordered = MaterialTheme.typography.bodySmall,
            bullet = MaterialTheme.typography.bodySmall,
            list = MaterialTheme.typography.bodySmall,
            textLink = TextLinkStyles(
                // long: Markdown 库默认链接使用 bodyLarge + Bold，Sources 这类引用列表会被放大成标题感；这里强制跟随正文小字号，只保留下划线表示可点击链接。
                style = SpanStyle(
                    color = contentColor,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.Normal,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            table = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
            inlineCode = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        components = markdownComponents(
            codeBlock = highlightedCodeBlock,
            codeFence = highlightedCodeFence,
            table = { model ->
                val tableStart = model.node.startOffset.coerceIn(0, model.content.length)
                val tableEnd = model.node.endOffset.coerceIn(tableStart, model.content.length)
                BorderedMarkdownTable(
                    rawTable = model.content.substring(tableStart, tableEnd),
                    contentColor = contentColor,
                )
            },
        ),
        loading = { Box(it) },
        error = {
            Text(
                text = normalizedMarkdown,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = it,
            )
        },
    )
}

@Composable
private fun BorderedMarkdownTable(
    rawTable: String,
    contentColor: Color,
) {
    val table = remember(rawTable) { parseMarkdownTableBlock(rawTable) }
    if (table == null) {
        Text(
            text = rawTable,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
        return
    }

    val borderColor = contentColor.copy(alpha = 0.20f)
    val tableShape = RoundedCornerShape(6.dp)
    val cellWidth = markdownTableCellWidth(table.columnCount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(tableShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .border(1.dp, borderColor, tableShape),
    ) {
        BorderedMarkdownTableRow(
            cells = table.headers,
            columnCount = table.columnCount,
            alignments = table.alignments,
            cellWidth = cellWidth,
            borderColor = borderColor,
            contentColor = contentColor,
            header = true,
        )
        table.rows.forEachIndexed { index, row ->
            BorderedMarkdownTableRow(
                cells = row,
                columnCount = table.columnCount,
                alignments = table.alignments,
                cellWidth = cellWidth,
                borderColor = borderColor,
                contentColor = contentColor,
                header = false,
                rowIndex = index,
            )
        }
    }
}

@Composable
private fun BorderedMarkdownTableRow(
    cells: List<String>,
    columnCount: Int,
    alignments: List<TextAlign>,
    cellWidth: Dp,
    borderColor: Color,
    contentColor: Color,
    header: Boolean,
    rowIndex: Int = 0,
) {
    Row {
        repeat(columnCount) { columnIndex ->
            val cellText = cells.getOrNull(columnIndex).orEmpty()
            val textAlign = alignments.getOrNull(columnIndex) ?: TextAlign.Start
            val cellBackground = when {
                header -> contentColor.copy(alpha = 0.08f)
                rowIndex % 2 == 1 -> contentColor.copy(alpha = 0.035f)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .width(cellWidth)
                    .heightIn(min = if (header) 34.dp else 32.dp)
                    .background(cellBackground)
                    .border(0.6.dp, borderColor)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                contentAlignment = when (textAlign) {
                    TextAlign.Center -> Alignment.Center
                    TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                },
            ) {
                // long: Markdown 表格在模型回复里主要承担结构化数据阅读，单元格边框和交替底色比装饰更重要；保持小字号和可横向滚动，避免多列表格挤压对话气泡。
                Text(
                    text = cellText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
                    ),
                    color = contentColor,
                )
            }
        }
    }
}

private fun markdownTableCellWidth(columnCount: Int): Dp = when {
    columnCount <= 2 -> 136.dp
    columnCount == 3 -> 108.dp
    else -> 92.dp
}

private fun OperationResult.shouldStayInline(): Boolean = requestUrl != null || latencyMs != null
