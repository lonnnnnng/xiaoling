package com.longdev.xiaoling.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.ui.AgentApprovalUiState
import com.longdev.xiaoling.ui.AgentStatusChip
import com.longdev.xiaoling.ui.AgentStepRow
import com.longdev.xiaoling.ui.PageTitle
import com.longdev.xiaoling.ui.PersonalTaskCompletionUiState
import com.longdev.xiaoling.ui.PersonalTaskFailureAction
import com.longdev.xiaoling.ui.PersonalTaskFailureUiState
import com.longdev.xiaoling.ui.PersonalTaskOperationUiPhase
import com.longdev.xiaoling.ui.ThemeModeSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

private fun ConversationUiState.chatAutoScrollKey(): ChatAutoScrollKey {
    val lastMessage = messages.chatMessages.lastOrNull()
    return ChatAutoScrollKey(
        conversationId = conversationId,
        lastItemIndex = messages.chatMessages.size,
        lastMessageId = lastMessage?.id,
        lastMessageRole = lastMessage?.role,
        lastMessageTextLength = lastMessage?.text?.length,
        firstTokenLatencyMs = lastMessage?.meta?.firstTokenLatencyMs,
        latencyMs = lastMessage?.meta?.latencyMs,
        sendingMessage = composer.sendingMessage,
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
    return lastVisibleItem.index >= tailItemIndex - 1 &&
        distanceToViewportEnd >= -CHAT_TAIL_NEAR_THRESHOLD_PX
}

@Composable
internal fun ConversationPage(
    state: ConversationUiState,
    actions: ConversationActions,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val chatListState = rememberLazyListState()
    val chatScrollState = remember(chatListState) { ChatScrollState(chatListState) }
    val scrollScope = rememberCoroutineScope()
    val messages = state.messages
    val lastChatItemIndex = messages.chatMessages.size
    val lastChatMessage = messages.chatMessages.lastOrNull()
    val autoScrollKey = state.chatAutoScrollKey()
    val isAtChatTail by remember(lastChatItemIndex) {
        derivedStateOf { chatListState.isNearChatTail(lastChatItemIndex) }
    }

    LaunchedEffect(state.conversationId, visible) {
        if (!visible || chatScrollState.boundConversationId == state.conversationId) {
            return@LaunchedEffect
        }
        chatScrollState.boundConversationId = state.conversationId
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

    LaunchedEffect(state.conversationId, messages.displayedKnowledgeReferences, visible) {
        if (visible) {
            actions.refreshKnowledgeReferenceStatuses(messages.displayedKnowledgeReferences)
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
        if (!visible || chatScrollState.handledAutoScrollKey == autoScrollKey) {
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // long: 部分厂商系统不会稳定执行 adjustResize；这里在 Compose 层消费 IME inset，让键盘弹出时只压缩对话区域并把输入框顶到键盘上方。
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ConversationHeader(
                state = state.header,
                onConversationSelected = actions::selectConversation,
                onNewConversation = actions::openNewConversation,
                onDeleteConversation = actions::deleteCurrentConversation,
                onThemeModeChanged = actions::updateThemeMode,
            )
            ModelSelectionBar(
                state = state.provider,
                onProviderSelected = actions::selectProvider,
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
                        if (messages.chatMessages.isEmpty()) {
                            item {
                                Text(
                                    text = "选择模型后输入消息开始对话。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(count = messages.chatMessages.size) { index ->
                            val message = messages.chatMessages[index]
                            ChatBubble(
                                message = message,
                                knowledgeReferenceStatuses = messages.knowledgeReferenceStatuses,
                                failedKnowledgeReferenceStatuses = messages.failedKnowledgeReferenceStatuses,
                                answerabilityNotice = messages.answerabilityNotices[message.id],
                                onOpenKnowledgeDocument = actions::openKnowledgeDocument,
                                onReuseUserMessage = actions::updatePrompt,
                            )
                            if (messages.activeAgentRun?.run?.userMessageId == message.id) {
                                Spacer(modifier = Modifier.height(7.dp))
                                AgentRunTimelineCard(
                                    snapshot = messages.activeAgentRun,
                                    approval = messages.pendingAgentApproval?.takeIf { approval ->
                                        approval.runId == messages.activeAgentRun.run.id
                                    },
                                    onApprove = actions::approvePendingAgentTool,
                                    onReject = actions::rejectPendingAgentTool,
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(1.dp)) }
                    }
                }

                if (chatScrollState.showNewContentButton && messages.chatMessages.isNotEmpty()) {
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
                state = state.composer,
                onModelSelected = actions::updateModel,
                onResponsesChanged = actions::updateResponsesEnabled,
                onStreamingChanged = actions::updateStreamingEnabled,
                onReasoningSummaryChanged = actions::updateReasoningSummaryEnabled,
                onAgentMemoryRecallChanged = actions::updateAgentMemoryRecallEnabled,
                onAgentProfileSelected = actions::selectAgentProfile,
                onPersonalTaskModeChanged = actions::updatePersonalTaskMode,
                onPromptChange = actions::updatePrompt,
                onAttachImage = actions::requestImageAttachment,
                onAttachDocument = actions::requestDocumentAttachment,
                onRemovePendingImage = actions::removePendingImage,
                onRemovePendingDocument = actions::removePendingDocument,
                onOpenPendingSharedDraft = actions::openPendingSharedDraft,
                onDiscardPendingSharedDraft = actions::discardPendingSharedDraft,
                onSend = actions::sendMessage,
                onStop = actions::stopGenerating,
                onOpenWorkflowManagement = actions::openWorkflowManagement,
            )
        }

        val personalTaskOperationPhase = state.composer.personalTaskOperationPhase
        if (personalTaskOperationPhase != null) {
            PersonalTaskProgressIndicator(
                phase = personalTaskOperationPhase,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (messages.waitingForModelStart) {
            ModelWaitingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ConversationHeader(
    state: ConversationHeaderUiState,
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
            val shape = RoundedCornerShape(14.dp)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = shape,
                modifier = Modifier
                    .height(28.dp)
                    .widthIn(max = 150.dp)
                    .clip(shape)
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.title.ifBlank { "新会话" }.compactModelLabel(12),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "切换会话",
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.conversations.sortedByDescending { conversation -> conversation.updatedAt }
                    .forEach { conversation ->
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
    state: ConversationProviderUiState,
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
            if (!state.hasEnabledModels) {
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
private fun ProviderDropdown(
    state: ConversationProviderUiState,
    onSelected: (String) -> Unit,
) {
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
                text = state.selectedProfileName.ifBlank { "选择模型提供方" },
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
private fun MessageInputBar(
    state: ConversationComposerUiState,
    onModelSelected: (String) -> Unit,
    onResponsesChanged: (Boolean) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onReasoningSummaryChanged: (Boolean) -> Unit,
    onAgentMemoryRecallChanged: (Boolean) -> Unit,
    onAgentProfileSelected: (String) -> Unit,
    onPersonalTaskModeChanged: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onAttachImage: () -> Unit,
    onAttachDocument: () -> Unit,
    onRemovePendingImage: () -> Unit,
    onRemovePendingDocument: () -> Unit,
    onOpenPendingSharedDraft: () -> Unit,
    onDiscardPendingSharedDraft: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenWorkflowManagement: (String?) -> Unit,
) {
    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    val attaching = state.attachingImage || state.attachingDocument
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PersonalTaskModeSelector(
                selected = state.personalTaskMode,
                enabled = !state.sendingMessage && !state.awaitingPersonalTaskPlanConfirmation,
                onSelected = onPersonalTaskModeChanged,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp),
            )
            if (state.personalTaskMode) {
                PersonalTaskTemplateMenu(
                    enabled = state.controlsEnabled,
                    onSelected = onPromptChange,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            state.personalTaskFailure?.let { failure ->
                PersonalTaskFailureNotice(
                    failure = failure,
                    actionEnabled = failure.action == PersonalTaskFailureAction.VIEW_WORKFLOW || state.canSend,
                    onAction = {
                        when (failure.action) {
                            PersonalTaskFailureAction.RETRY_PLAN -> {
                                // long: 重试以失败快照中的原目标为准，避免输入框被其他状态回写后悄悄改变任务意图。
                                onPromptChange(failure.goal)
                                onSend()
                            }
                            PersonalTaskFailureAction.VIEW_WORKFLOW -> onOpenWorkflowManagement(null)
                        }
                    },
                )
            }
            state.personalTaskCompletion?.let { completion ->
                PersonalTaskCompletionNotice(
                    completion = completion,
                    onOpenWorkflow = { onOpenWorkflowManagement(completion.workflowId) },
                )
            }
            state.pendingSharedDraft?.let { payload ->
                SharedDraftPendingNotice(
                    payload = payload,
                    enabled = !state.sendingMessage && !attaching && !state.loadingConversationMessages,
                    onOpen = onOpenPendingSharedDraft,
                    onDiscard = onDiscardPendingSharedDraft,
                )
            }
            if (state.sharedDraftImported) SharedDraftSourceLabel()
            state.pendingImage?.let { attachment ->
                PendingImagePreview(
                    attachment = attachment,
                    enabled = !state.sendingMessage && !attaching,
                    onRemove = onRemovePendingImage,
                    modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp),
                )
            }
            state.pendingDocument?.let { attachment ->
                PendingDocumentPreview(
                    attachment = attachment,
                    enabled = !state.sendingMessage && !attaching,
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
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    enabled = !state.sendingMessage,
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 48.dp, bottom = 34.dp),
                    decorationBox = { innerTextField ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (state.prompt.isBlank()) {
                                Text(
                                    text = if (state.personalTaskMode) "描述要完成的任务" else "输入消息",
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
                    enabled = state.attachmentEnabled,
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
                    enabled = state.controlsEnabled,
                    onModelSelected = onModelSelected,
                    onResponsesChanged = onResponsesChanged,
                    onStreamingChanged = onStreamingChanged,
                    onReasoningSummaryChanged = onReasoningSummaryChanged,
                    onAgentMemoryRecallChanged = onAgentMemoryRecallChanged,
                    onAgentProfileSelected = onAgentProfileSelected,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 36.dp, end = 52.dp),
                )
                Button(
                    onClick = if (state.sendingMessage) onStop else onSend,
                    enabled = state.sendingMessage || state.canSend,
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
                        imageVector = if (state.sendingMessage) Icons.Default.Close else Icons.Default.ArrowUpward,
                        contentDescription = when (state.personalTaskOperationPhase) {
                            PersonalTaskOperationUiPhase.GENERATING_PLAN -> "停止生成任务计划"
                            PersonalTaskOperationUiPhase.CREATING_TASK -> "停止创建个人任务"
                            PersonalTaskOperationUiPhase.CREATING_REMINDER -> "停止创建应用内提醒"
                            null -> if (state.sendingMessage) "停止生成" else "发送"
                        },
                        modifier = Modifier.size(if (state.sendingMessage) 18.dp else 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalTaskModeSelector(
    selected: Boolean,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(false to "对话", true to "任务")
    SingleChoiceSegmentedButtonRow(modifier = modifier.widthIn(max = 176.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option.first,
                onClick = { onSelected(option.first) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = option.second,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
}

@Composable
private fun PersonalTaskTemplateMenu(
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(7.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .height(32.dp)
                .testTag("personal-task-template-menu"),
        ) {
            Text("常用任务", style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            personalTaskTemplates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.title, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelected(template.goal)
                        expanded = false
                    },
                    modifier = Modifier.testTag("personal-task-template-${template.id}"),
                )
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
            val budgetLabel = attachment.pageCount?.let { pageCount -> "$pageCount 页" }
                ?: attachment.characterCount?.let { characterCount -> "$characterCount 字符" }
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
                    Text(
                        "拒绝",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    )
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
                    text = approval.arguments.entries.joinToString(" · ") { entry ->
                        "${entry.key}=${entry.value}"
                    },
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
    state: ConversationComposerUiState,
    enabled: Boolean,
    onModelSelected: (String) -> Unit,
    onResponsesChanged: (Boolean) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onReasoningSummaryChanged: (Boolean) -> Unit,
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
        if (!state.agentCommand) {
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
        } else {
            val selectedAgent = state.agentProfiles.firstOrNull { profile ->
                profile.id == state.selectedAgentProfileId
            }
            CompactCheckOption(
                text = "记忆",
                checked = state.agentMemoryRecallEnabled,
                enabled = state.memoryOptionEnabled,
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
        if (!state.agentCommand) {
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
                            tint = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (modelEnabled) 0.82f else 0.38f,
                            ),
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

@Composable
private fun ModelWaitingIndicator(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, shadowElevation = 3.dp, modifier = modifier) {
        CircularProgressIndicator(
            modifier = Modifier.padding(12.dp).size(24.dp),
            strokeWidth = 2.2.dp,
        )
    }
}

@Composable
private fun PersonalTaskProgressIndicator(
    phase: PersonalTaskOperationUiPhase,
    modifier: Modifier = Modifier,
) {
    val label = when (phase) {
        PersonalTaskOperationUiPhase.GENERATING_PLAN -> "正在生成任务计划"
        PersonalTaskOperationUiPhase.CREATING_TASK -> "正在创建个人任务"
        PersonalTaskOperationUiPhase.CREATING_REMINDER -> "正在创建应用内提醒"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 3.dp,
        modifier = modifier.testTag("personal-task-progress"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PersonalTaskFailureNotice(
    failure: PersonalTaskFailureUiState,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f))
            .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = failure.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = failure.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier.testTag(
                if (failure.action == PersonalTaskFailureAction.RETRY_PLAN) {
                    "personal-task-retry"
                } else {
                    "personal-task-view-workflow"
                },
            ),
        ) {
            Icon(
                imageVector = if (failure.action == PersonalTaskFailureAction.RETRY_PLAN) {
                    Icons.Default.Refresh
                } else {
                    Icons.Default.Description
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (failure.action == PersonalTaskFailureAction.RETRY_PLAN) "重新生成" else "查看任务")
        }
    }
}

@Composable
private fun PersonalTaskCompletionNotice(
    completion: PersonalTaskCompletionUiState,
    onOpenWorkflow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f))
            .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = completion.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = completion.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onOpenWorkflow,
            modifier = Modifier.testTag("personal-task-view-completed-workflow"),
        ) {
            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("查看任务")
        }
    }
}

private fun String.compactModelLabel(maxChars: Int = 16): String {
    val value = trim()
    if (value.length <= maxChars) return value
    return value.take(maxChars - 1) + "…"
}

private const val CHAT_TAIL_NEAR_THRESHOLD_PX = 96
