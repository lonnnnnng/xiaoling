package com.longdev.endpointtester.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.endpointtester.model.ApiMode
import com.longdev.endpointtester.model.ProviderProfile
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
@Composable
fun EndpointTesterScreen(viewModel: EndpointTesterViewModel = viewModel()) {
    val state = viewModel.uiState
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val transientResult = state.result?.takeUnless { it.shouldStayInline() }
    val isProviderEditor = selectedTab == 1 && state.manageDraft != null
    var lastRootBackAt by remember { mutableStateOf(0L) }
    var centerNotice by remember { mutableStateOf<CenterNotice?>(null) }

    BackHandler(enabled = isProviderEditor) {
        viewModel.closeProviderEditor()
    }

    BackHandler(enabled = !isProviderEditor) {
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
            // long: 管理页保存、删除、获取模型等反馈只需要告知结果，不应该占用底部操作区，也不应该阻断用户继续点击页面。
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
                if (!isProviderEditor) {
                    CompactBottomTabBar(
                        selectedTab = selectedTab,
                        onSelected = { selectedTab = it },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            if (selectedTab == 0) {
                TestPage(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                ManagePage(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding),
                )
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
            .height(38.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, top = 1.dp, end = 44.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTabItem(
                selected = selectedTab == 0,
                label = "测试",
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp)) },
                onClick = { onSelected(0) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(18.dp))
            CompactTabItem(
                selected = selectedTab == 1,
                label = "管理",
                icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp)) },
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
            .height(30.dp)
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
private fun TestPage(
    state: TesterUiState,
    viewModel: EndpointTesterViewModel,
    modifier: Modifier = Modifier,
) {
    val chatListState = rememberLazyListState()
    val lastChatItemIndex = state.chatMessages.size
    val lastChatMessage = state.chatMessages.lastOrNull()

    LaunchedEffect(
        lastChatItemIndex,
        lastChatMessage?.text?.length,
        lastChatMessage?.meta?.firstTokenLatencyMs,
        lastChatMessage?.meta?.latencyMs,
    ) {
        if (lastChatItemIndex > 0) {
            // long: 长回复的耗时信息位于消息 item 底部，只滚到最后一条 item 顶部时可能仍被挡住；滚到尾部锚点保证元数据可见。
            chatListState.scrollToItem(lastChatItemIndex)
        }
    }
    val waitingForModelStart = state.testingModel && state.chatMessages.lastOrNull()
        ?.takeIf { it.role == "assistant" }
        ?.text
        .isNullOrBlank()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TestHeader(
                state = state,
                onConversationSelected = viewModel::selectConversation,
                onNewConversation = viewModel::openNewConversation,
                onDeleteConversation = viewModel::deleteCurrentConversation,
            )
            ModelSelectionBar(
                state = state,
                onProviderSelected = viewModel::selectProfile,
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                text = "选择模型后输入消息开始测试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(count = state.chatMessages.size) { index ->
                        ChatBubble(
                            message = state.chatMessages[index],
                            onReuseUserMessage = viewModel::updatePrompt,
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            }

            MessageInputBar(
                state = state,
                prompt = state.prompt,
                testingModel = state.testingModel,
                enabled = state.enabledModels.isNotEmpty(),
                onModelSelected = viewModel::updateModel,
                onResponsesChanged = viewModel::updateResponsesEnabled,
                onStreamingChanged = viewModel::updateStreamingEnabled,
                onPromptChange = viewModel::updatePrompt,
                onSend = viewModel::testModel,
            )
        }

        if (waitingForModelStart) {
            ModelWaitingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TestHeader(
    state: TesterUiState,
    onConversationSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageTitle("测试")
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
    state: TesterUiState,
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
                    text = "请先到管理页获取上游模型并勾选可测试模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    state: TesterUiState,
    prompt: String,
    testingModel: Boolean,
    enabled: Boolean,
    onModelSelected: (String) -> Unit,
    onResponsesChanged: (Boolean) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
) {
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
                enabled = !testingModel,
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
                enabled = !testingModel && enabled,
                onModelSelected = onModelSelected,
                onResponsesChanged = onResponsesChanged,
                onStreamingChanged = onStreamingChanged,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 52.dp),
            )
            Button(
                onClick = onSend,
                enabled = !testingModel && enabled,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.outline,
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "发送", modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun InputOptionRow(
    state: TesterUiState,
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
        CompactCheckOption(
            text = "Resp",
            checked = state.apiMode == ApiMode.RESPONSES,
            enabled = enabled,
            onCheckedChange = onResponsesChanged,
        )
        CompactCheckOption(
            text = "流式",
            checked = state.streamingEnabled,
            enabled = enabled,
            onCheckedChange = onStreamingChanged,
        )
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
private fun ManagePage(
    state: TesterUiState,
    viewModel: EndpointTesterViewModel,
    modifier: Modifier = Modifier,
) {
    state.manageDraft?.let { draft ->
        ProviderEditorPage(
            draft = draft,
            result = state.result,
            viewModel = viewModel,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ManageHeader(
            syncing = state.syncingAllProfiles,
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

private const val FULL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

@Composable
private fun ManageHeader(
    syncing: Boolean,
    onSyncAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageTitle("管理")
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

@Composable
private fun ProviderEditorPage(
    draft: ProviderEditDraft,
    result: OperationResult?,
    viewModel: EndpointTesterViewModel,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::importDraftFromQr)
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
                            text = "获取成功后可以勾选允许在测试页使用的模型。",
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
private fun ProviderDropdown(state: TesterUiState, onSelected: (String) -> Unit) {
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
    val containerColor = when {
        isUser -> Color(0xFFDCEBFF)
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        else -> Color(0xFFEAF7EE)
    }
    val contentColor = when {
        isUser -> Color(0xFF173B70)
        isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> Color(0xFF1F3D2B)
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(7.dp),
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
                        color = contentColor.copy(alpha = 0.52f),
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

    StreamingMarkdownText(
        markdown = message.text,
        contentColor = contentColor,
    )
}

@Composable
private fun StreamingMarkdownText(
    markdown: String,
    contentColor: Color,
) {
    // long: 模型输出会覆盖表格、链接、引用、嵌套列表等常见 Markdown；继续维护自研解析器会让每一种语法都变成补丁，这里交给 GFM 渲染库统一处理。
    Markdown(
        content = markdown.ifBlank { " " },
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
            table = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
            inlineCode = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        components = markdownComponents(
            codeBlock = highlightedCodeBlock,
            codeFence = highlightedCodeFence,
        ),
        loading = { Box(it) },
        error = {
            Text(
                text = markdown,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = it,
            )
        },
    )
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
            result.endpoint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun OperationResult.shouldStayInline(): Boolean = endpoint != null || latencyMs != null
