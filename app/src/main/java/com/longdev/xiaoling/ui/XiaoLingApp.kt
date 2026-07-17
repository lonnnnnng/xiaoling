package com.longdev.xiaoling.ui

import android.app.Activity
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import com.longdev.xiaoling.ui.theme.LocalChatBubblePalette
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import java.util.Calendar
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
    var selectedTab by remember { mutableIntStateOf(0) }
    var settingsPane by remember { mutableStateOf(SettingsPane.ROOT) }
    val context = LocalContext.current
    val transientResult = state.result?.takeUnless { it.shouldStayInline() }
    val isProviderEditor = selectedTab == 1 && state.manageDraft != null
    val isSettingsSubPage = selectedTab == 1 && settingsPane != SettingsPane.ROOT
    val hideBottomBar = isProviderEditor || isSettingsSubPage
    val chatListState = rememberLazyListState()
    val chatScrollState = remember(chatListState) { ChatScrollState(chatListState) }
    var lastRootBackAt by remember { mutableStateOf(0L) }
    var centerNotice by remember { mutableStateOf<CenterNotice?>(null) }

    BackHandler(enabled = isProviderEditor) {
        viewModel.closeProviderEditor()
    }

    BackHandler(enabled = !isProviderEditor && isSettingsSubPage) {
        settingsPane = SettingsPane.ROOT
    }

    BackHandler(enabled = !isProviderEditor && !isSettingsSubPage) {
        val now = System.currentTimeMillis()
        if (now - lastRootBackAt < 2_000) {
            (context as? Activity)?.finish()
        } else {
            lastRootBackAt = now
            centerNotice = CenterNotice("再返回一次退出应用")
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
                    CompactBottomTabBar(
                        selectedTab = selectedTab,
                        onSelected = { selectedTab = it },
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
                    visible = selectedTab == 0,
                    modifier = Modifier.matchParentSize(),
                )

                if (selectedTab == 1) {
                    SettingsPage(
                        state = state,
                        viewModel = viewModel,
                        pane = settingsPane,
                        onOpenProviderManagement = { settingsPane = SettingsPane.PROVIDER_MANAGEMENT },
                        onOpenPromptSettings = { settingsPane = SettingsPane.PROMPT_SETTINGS },
                        onOpenAgentRunHistory = {
                            viewModel.refreshAgentRunHistory()
                            settingsPane = SettingsPane.AGENT_RUN_HISTORY
                        },
                        onBackToSettings = { settingsPane = SettingsPane.ROOT },
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
}

private enum class SettingsPane {
    ROOT,
    PROVIDER_MANAGEMENT,
    PROMPT_SETTINGS,
    AGENT_RUN_HISTORY,
}

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
private fun CompactBottomTabBar(
    selectedTab: Int,
    onSelected: (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 42.dp, top = 4.dp, end = 42.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTabItem(
                selected = selectedTab == 0,
                label = "对话",
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp)) },
                onClick = { onSelected(0) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(18.dp))
            CompactTabItem(
                selected = selectedTab == 1,
                label = "设置",
                icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(12.dp)) },
                onClick = { onSelected(1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CompactTabItem(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
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
    modifier: Modifier = Modifier,
) {
    val chatListState = chatScrollState.listState
    val scrollScope = rememberCoroutineScope()
    val lastChatItemIndex = state.chatMessages.size
    val lastChatMessage = state.chatMessages.lastOrNull()
    val autoScrollKey = state.chatAutoScrollKey()
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
                onPromptChange = viewModel::updatePrompt,
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
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canRunAgent = AgentCommand.matches(prompt)
    val canSend = !sendingMessage && prompt.isNotBlank() && (enabled || canRunAgent)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
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
            InputOptionRow(
                state = state,
                enabled = !sendingMessage && enabled,
                onModelSelected = onModelSelected,
                onResponsesChanged = onResponsesChanged,
                onStreamingChanged = onStreamingChanged,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 52.dp),
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
private fun AgentStatusChip(status: AgentRunStatus) {
    val color = when (status) {
        AgentRunStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AgentRunStatus.FAILED,
        AgentRunStatus.BUDGET_EXHAUSTED -> MaterialTheme.colorScheme.error
        AgentRunStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = status.toUiLabel(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
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
                        text = "等待确认 · ${approval.riskLabel}",
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
                    Text("批准执行", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
                }
            }
            Text(
                text = approval.toolDescription,
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
private fun AgentStepRow(
    status: AgentStepStatus,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentStepStatusIcon(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = status.toUiLabel(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            color = status.toUiColor(),
        )
    }
}

@Composable
private fun AgentStepStatusIcon(status: AgentStepStatus) {
    Box(
        modifier = Modifier.size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            AgentStepStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            AgentStepStatus.COMPLETED -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            AgentStepStatus.FAILED -> Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            AgentStepStatus.CANCELLED -> Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            AgentStepStatus.PENDING -> Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun AgentStepStatus.toUiColor(): Color {
    return when (this) {
        AgentStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AgentStepStatus.FAILED -> MaterialTheme.colorScheme.error
        AgentStepStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        AgentStepStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        AgentStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun AgentRunStatus.toUiLabel(): String {
    return when (this) {
        AgentRunStatus.QUEUED -> "排队"
        AgentRunStatus.THINKING -> "思考中"
        AgentRunStatus.WAITING_APPROVAL -> "待确认"
        AgentRunStatus.EXECUTING -> "执行中"
        AgentRunStatus.VERIFYING -> "验证中"
        AgentRunStatus.COMPLETED -> "已完成"
        AgentRunStatus.FAILED -> "失败"
        AgentRunStatus.CANCELLED -> "已取消"
        AgentRunStatus.BUDGET_EXHAUSTED -> "预算耗尽"
    }
}

private fun AgentStepStatus.toUiLabel(): String {
    return when (this) {
        AgentStepStatus.PENDING -> "待处理"
        AgentStepStatus.RUNNING -> "进行中"
        AgentStepStatus.COMPLETED -> "完成"
        AgentStepStatus.FAILED -> "失败"
        AgentStepStatus.CANCELLED -> "取消"
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
    modifier: Modifier = Modifier,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        Box(modifier = Modifier.widthIn(max = 164.dp)) {
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

private fun String.toFullSyncTimeLabel(): String {
    val value = trim()
    if (value.isBlank()) return "未同步"
    if (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(value)) return value
    if (Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(value)) {
        return "${Calendar.getInstance().get(Calendar.YEAR)}-$value:00"
    }
    return value
}

private fun ChatMessage.footerLabel(): String? {
    return when (role) {
        "user" -> createdAt.toFullTimeLabel()
        "assistant" -> assistantFooterLabel() ?: createdAt.toFullTimeLabel()
        "error" -> "请求失败 · ${createdAt.toFullTimeLabel()}"
        else -> createdAt.toFullTimeLabel()
    }
}

private fun ChatMessage.assistantFooterLabel(): String? {
    val messageMeta = meta ?: return null
    val latency = messageMeta.latencyMs
    val firstTokenLatency = messageMeta.firstTokenLatencyMs
    return when {
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
    onOpenPromptSettings: () -> Unit,
    onOpenAgentRunHistory: () -> Unit,
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
            state.manageDraft != null -> ProviderEditorPage(
                draft = state.manageDraft,
                result = state.result,
                viewModel = viewModel,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.PROVIDER_MANAGEMENT -> ProviderManagementPage(
                state = state,
                viewModel = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.PROMPT_SETTINGS -> PromptSettingsPage(
                state = state,
                viewModel = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            pane == SettingsPane.AGENT_RUN_HISTORY -> AgentRunHistoryPage(
                state = state,
                viewModel = viewModel,
                onBack = onBackToSettings,
                modifier = Modifier.matchParentSize(),
            )
            else -> SettingsRootPage(
                state = state,
                onThemeModeChanged = viewModel::updateThemeMode,
                onOpenProviderManagement = onOpenProviderManagement,
                onOpenPromptSettings = onOpenPromptSettings,
                onOpenAgentRunHistory = onOpenAgentRunHistory,
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
    onOpenPromptSettings: () -> Unit,
    onOpenAgentRunHistory: () -> Unit,
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

        SettingsEntryCard(
            title = "提示词设置",
            subtitle = "普通对话 · 会话摘要 / 记忆 · Agent 回复总结",
            icon = Icons.Default.Tune,
            onClick = onOpenPromptSettings,
        )

        SettingsEntryCard(
            title = "Agent 运行记录",
            subtitle = if (state.agentRunHistory.isEmpty()) {
                "查看最近 Agent Run 的步骤、审批和事件"
            } else {
                "最近 ${state.agentRunHistory.size} 条 · ${state.agentRunHistory.count { it.snapshot.run.status == AgentRunStatus.COMPLETED }} 条已完成"
            },
            onClick = onOpenAgentRunHistory,
        )
    }
}

@Composable
private fun SettingsEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Default.Memory,
    onClick: () -> Unit,
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
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
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
private fun AgentRunHistoryPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        if (state.agentRunHistory.isEmpty() && !state.loadingAgentRunHistory) {
            viewModel.refreshAgentRunHistory()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentRunHistoryHeader(
            loading = state.loadingAgentRunHistory,
            onBack = onBack,
            onRefresh = viewModel::refreshAgentRunHistory,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.agentRunHistoryError != null -> item {
                    CompactSection(title = "读取失败") {
                        Text(
                            text = state.agentRunHistoryError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                state.loadingAgentRunHistory && state.agentRunHistory.isEmpty() -> item {
                    CompactSection(title = "Agent Run") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Text(
                                text = "正在读取运行记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.agentRunHistory.isEmpty() -> item {
                    CompactSection(title = "Agent Run") {
                        Text(
                            text = "还没有 Agent 运行记录。可以在对话框输入 /agent <目标> 触发一次演示任务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> items(
                    count = state.agentRunHistory.size,
                    key = { index -> state.agentRunHistory[index].snapshot.run.id },
                ) { index ->
                    val detail = state.agentRunHistory[index]
                    val selected = detail.snapshot.run.id == state.selectedAgentRunId
                    AgentRunHistoryItemCard(
                        detail = detail,
                        selected = selected,
                        onClick = { viewModel.selectAgentRun(detail.snapshot.run.id) },
                    )
                    if (selected) {
                        AgentRunDetailPanel(detail)
                    }
                }
            }
        }
    }
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
        PageTitle("Agent 运行记录")
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.size(30.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "刷新运行记录", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AgentRunHistoryItemCard(
    detail: AgentRunDetailRecord,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val run = detail.snapshot.run
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
                run.completedAt?.let {
                    Text(
                        text = "完成 ${it.toFullTimeLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentRunDetailPanel(detail: AgentRunDetailRecord) {
    val snapshot = detail.snapshot
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
            snapshot.run.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            AgentRunDetailSection("步骤") {
                snapshot.steps.forEach { step ->
                    AgentStepRow(status = step.status, title = step.title, detail = step.detail)
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
    val presentation = remember(event.type, event.message) {
        presentAgentRunEvent(event.type, event.message)
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
private fun ProviderManagementPage(
    state: XiaoLingUiState,
    viewModel: XiaoLingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ProviderManagementHeader(
            syncing = state.syncingAllProfiles,
            onBack = onBack,
            onSyncAll = viewModel::syncAllProviders,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.profiles.isEmpty()) {
                    item {
                        CompactSection(title = "模型提供方") {
                            Text(
                                text = "还没有模型提供方，点击右下角新增。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(
                        count = state.profiles.size,
                        key = { index -> state.profiles[index].id },
                    ) { index ->
                        ProviderListItem(
                            profile = state.profiles[index],
                            selected = state.profiles[index].id == state.selectedProfileId,
                            syncing = state.syncingAllProfiles || state.profiles[index].id in state.syncingProfileIds,
                            syncResult = state.batchSyncResults[state.profiles[index].id],
                            onSync = { viewModel.syncProviderModels(state.profiles[index].id) },
                            onEdit = { viewModel.openEditProvider(state.profiles[index].id) },
                            onDelete = { viewModel.deleteProvider(state.profiles[index].id) },
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::openNewProvider,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增模型提供方", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProviderManagementHeader(
    syncing: Boolean,
    onBack: () -> Unit,
    onSyncAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
        }
        PageTitle("模型提供方管理")
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onSyncAll,
            enabled = !syncing,
            modifier = Modifier.size(30.dp),
        ) {
            if (syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.6.dp,
                )
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "批量同步", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

@Composable
private fun ProviderEditorPage(
    draft: ProviderEditDraft,
    result: OperationResult?,
    viewModel: XiaoLingViewModel,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::importDraftFromQr)
    }
    var base64DialogVisible by remember { mutableStateOf(false) }

    if (base64DialogVisible) {
        Base64DecodeDialog(
            onDismiss = { base64DialogVisible = false },
            onCopyPlainText = { plainText -> clipboardManager.setText(AnnotatedString(plainText)) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Button(
                    onClick = viewModel::saveDraftProvider,
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .height(40.dp),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 9.dp, top = 2.dp, end = 9.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = viewModel::closeProviderEditor, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = if (draft.id == null) "新增模型提供方" else "编辑模型提供方",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("扫描 baseUrl,apiKey 二维码")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(true),
                            )
                        },
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码导入", modifier = Modifier.size(16.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.importDraftFromClipboard(clipboardManager.getText()?.text.orEmpty())
                        },
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "从剪切板导入", modifier = Modifier.size(16.dp))
                    }
                    OutlinedButton(
                        onClick = { base64DialogVisible = true },
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Text(
                            text = "64",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }

            item {
                CompactSection(title = "基础信息") {
                    UnderlineTextField(
                        value = draft.name,
                        onValueChange = viewModel::updateDraftName,
                        label = "名称（不填则使用 URL）",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(3.dp))
                    UnderlineTextField(
                        value = draft.baseUrl,
                        onValueChange = viewModel::updateDraftBaseUrl,
                        label = "URL",
                        placeholder = "https://api.example.com/v1",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingLabelAction = {
                            LabelCopyButton(
                                enabled = draft.baseUrl.isNotBlank(),
                                contentDescription = "复制 URL",
                                onClick = { clipboardManager.setText(AnnotatedString(draft.baseUrl)) },
                            )
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    UnderlineTextField(
                        value = draft.apiKey,
                        onValueChange = viewModel::updateDraftApiKey,
                        label = "API Key",
                        singleLine = true,
                        trailingLabelAction = {
                            LabelCopyButton(
                                enabled = draft.apiKey.isNotBlank(),
                                contentDescription = "复制 API Key",
                                onClick = { clipboardManager.setText(AnnotatedString(draft.apiKey)) },
                            )
                        },
                    )
                }
            }

            item {
                CompactSection(
                    title = "上游模型",
                    action = {
                        OutlinedButton(
                            onClick = viewModel::fetchDraftModels,
                            enabled = !draft.loadingModels,
                            shape = RoundedCornerShape(7.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            if (draft.loadingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 1.6.dp,
                                )
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("获取", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                ) {
                    if (draft.upstreamModels.isEmpty()) {
                        Text(
                            text = "获取成功后可以勾选允许在对话页使用的模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "已勾选 ${draft.enabledModels.size} / ${draft.upstreamModels.size} 个模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            draft.upstreamModels.forEach { model ->
                                ModelCheckRow(
                                    model = model,
                                    checked = model in draft.enabledModels,
                                    onCheckedChange = { checked -> viewModel.toggleDraftModel(model, checked) },
                                )
                            }
                        }
                    }
                }
            }

            result?.takeIf { it.shouldStayInline() }?.let { item { ResultPanel(it) } }

            item {
                Spacer(Modifier.height(56.dp))
            }
        }
    }
}

@Composable
private fun ProviderListItem(
    profile: ProviderProfile,
    selected: Boolean,
    syncing: Boolean,
    syncResult: String?,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("共 ${profile.enabledModels.size} 个模型")
                        syncResult?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (syncResult == "同步失败") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onSync, enabled = !syncing, modifier = Modifier.size(28.dp)) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = "同步模型", modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    text = profile.lastSyncedAt.toFullSyncTimeLabel(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ModelCheckRow(
    model: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(28.dp),
        )
        Text(model, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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

@Composable
private fun UnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingLabelAction: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            trailingLabelAction?.let {
                Spacer(Modifier.width(3.dp))
                it()
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(1.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 28.dp else 58.dp),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 3.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    innerTextField()
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LabelCopyButton(
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        Icons.Default.ContentCopy,
        contentDescription = contentDescription,
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(2.dp),
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        },
    )
}

@Composable
private fun Base64DecodeDialog(
    onDismiss: () -> Unit,
    onCopyPlainText: (String) -> Unit,
) {
    var encodedText by remember { mutableStateOf("") }
    val decodedResult = remember(encodedText) { decodeBase64PlainText(encodedText) }
    val decodedText = decodedResult.getOrNull().orEmpty()
    val decodeError = decodedResult.exceptionOrNull()?.message.orEmpty()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Base64 解码",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = encodedText,
                    onValueChange = { encodedText = it },
                    label = { Text("Base64 密文", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 4,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = when {
                        encodedText.isBlank() -> ""
                        decodeError.isNotBlank() -> decodeError
                        else -> decodedText
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("明文", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = if (decodeError.isNotBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                    minLines = 4,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCopyPlainText(decodedText) },
                enabled = decodedText.isNotBlank() && decodeError.isBlank(),
            ) {
                Text("复制明文", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

private fun decodeBase64PlainText(raw: String): Result<String> {
    if (raw.isBlank()) return Result.success("")
    val compact = raw.filterNot { it.isWhitespace() }
    val flagCandidates = listOf(
        Base64.DEFAULT,
        Base64.NO_WRAP,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        Base64.URL_SAFE,
    )
    flagCandidates.forEach { flags ->
        runCatching {
            // long: Provider 二维码和第三方后台常见密文可能带换行、无 padding 或 URL-safe 字符，这里逐个兼容，避免用户手动改密文格式。
            String(Base64.decode(compact, flags), Charsets.UTF_8)
        }.onSuccess { return Result.success(it) }
    }
    return Result.failure(IllegalArgumentException("Base64 解码失败，请检查密文格式"))
}

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    androidx.compose.material3.OutlinedTextField(
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
        trailingIcon = trailingIcon,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 38.dp else 60.dp),
    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
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
                MessageBodyText(
                    message = message,
                    contentColor = contentColor,
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
private fun MessageBodyText(
    message: ChatMessage,
    contentColor: Color,
) {
    if (message.role == "user") {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    if (message.isStreamingInProgress()) {
        // long: 流式增量会让 Markdown AST 在“半截标题、半截表格、半截代码块”之间频繁变化，实时交给 Markdown 组件会反复重排闪烁；流式中先稳定展示文本，完成后再完整渲染 Markdown。
        Text(
            text = normalizeModelMarkdown(message.text),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
        return
    }

    StreamingMarkdownText(
        markdown = message.text,
        contentColor = contentColor,
    )
}

private fun ChatMessage.isStreamingInProgress(): Boolean {
    val messageMeta = meta ?: return false
    return role == "assistant" && messageMeta.streaming == true && messageMeta.latencyMs == null
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

@Composable
private fun ResultPanel(result: OperationResult) {
    val containerColor = if (result.success) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (result.success) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(result.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                result.latencyMs?.let {
                    Text("总 ${it} ms", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(result.message, style = MaterialTheme.typography.bodySmall)
            result.requestUrl?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun OperationResult.shouldStayInline(): Boolean = requestUrl != null || latencyMs != null
