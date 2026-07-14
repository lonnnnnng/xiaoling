package com.longdev.endpointtester.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun EndpointTesterScreen(viewModel: EndpointTesterViewModel = viewModel()) {
    val state = viewModel.uiState
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val transientResult = state.result?.takeUnless { it.shouldStayInline() }
    val isProviderEditor = selectedTab == 1 && state.manageDraft != null
    var lastRootBackAt by remember { mutableStateOf(0L) }

    BackHandler(enabled = isProviderEditor) {
        viewModel.closeProviderEditor()
    }

    BackHandler(enabled = !isProviderEditor) {
        val now = System.currentTimeMillis()
        if (now - lastRootBackAt < 2_000) {
            (context as? Activity)?.finish()
        } else {
            lastRootBackAt = now
            scope.launch {
                snackbarHostState.showSnackbar("再返回一次退出应用")
            }
        }
    }

    LaunchedEffect(transientResult) {
        transientResult?.let { result ->
            // long: 保存、删除、校验和扫码导入都属于一次性操作反馈，显示后立即清理，避免回到管理列表时反复看到旧提示。
            snackbarHostState.showSnackbar("${result.title}：${result.message}")
            viewModel.clearResult()
        }
    }

    Scaffold(
        bottomBar = {
            if (!isProviderEditor) {
                CompactBottomTabBar(
                    selectedTab = selectedTab,
                    onSelected = { selectedTab = it },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                .padding(horizontal = 44.dp, vertical = 3.dp),
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
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(30.dp)
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

    LaunchedEffect(state.chatMessages.size) {
        if (lastChatItemIndex > 0) {
            chatListState.animateScrollToItem(lastChatItemIndex - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PageTitle("测试")
            ModelSelectionBar(
                state = state,
                onProviderSelected = viewModel::selectProfile,
                onModelSelected = viewModel::updateModel,
                onApiModeSelected = viewModel::updateApiMode,
                onStreamingChanged = viewModel::updateStreamingEnabled,
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
                    contentPadding = PaddingValues(vertical = 6.dp),
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
                        ChatBubble(state.chatMessages[index])
                    }
                }
            }

            MessageInputBar(
                prompt = state.prompt,
                testingModel = state.testingModel,
                enabled = state.enabledModels.isNotEmpty(),
                onPromptChange = viewModel::updatePrompt,
                onSend = viewModel::testModel,
            )
        }

        if (state.testingModel) {
            ModelWaitingIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ModelSelectionBar(
    state: TesterUiState,
    onProviderSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onApiModeSelected: (ApiMode) -> Unit,
    onStreamingChanged: (Boolean) -> Unit,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ProviderDropdown(state, onSelected = onProviderSelected)
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = state.enabledModels.isNotEmpty(),
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                    ) {
                        Text(
                            text = state.model.ifBlank { "没有已勾选模型" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "选择模型", modifier = Modifier.size(15.dp))
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
            if (state.enabledModels.isEmpty()) {
                Text(
                    text = "请先到管理页获取上游模型并勾选可测试模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    ApiModeButton(
                        text = "Chat",
                        selected = state.apiMode == ApiMode.CHAT_COMPLETIONS,
                        onClick = { onApiModeSelected(ApiMode.CHAT_COMPLETIONS) },
                        modifier = Modifier.weight(1f),
                    )
                    ApiModeButton(
                        text = "Responses",
                        selected = state.apiMode == ApiMode.RESPONSES,
                        onClick = { onApiModeSelected(ApiMode.RESPONSES) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "流式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = state.streamingEnabled,
                        onCheckedChange = onStreamingChanged,
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    prompt: String,
    testingModel: Boolean,
    enabled: Boolean,
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
                    .padding(end = 48.dp, bottom = 4.dp),
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
                Icon(Icons.Default.PlayArrow, contentDescription = "发送", modifier = Modifier.size(18.dp))
            }
        }
    }
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
        PageTitle("管理")
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
                            onEdit = { viewModel.openEditProvider(state.profiles[index].id) },
                            onDelete = { viewModel.deleteProvider(state.profiles[index].id) },
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = viewModel::openNewProvider,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增模型提供方")
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
            contentPadding = PaddingValues(9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    )
                    Spacer(Modifier.height(3.dp))
                    UnderlineTextField(
                        value = draft.apiKey,
                        onValueChange = viewModel::updateDraftApiKey,
                        label = "API Key",
                        singleLine = true,
                    )
                }
            }

            item {
                CompactSection(title = "上游模型") {
                    OutlinedButton(
                        onClick = viewModel::fetchDraftModels,
                        enabled = !draft.loadingModels,
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    ) {
                        if (draft.loadingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("获取上游模型", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))
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
                    text = "共 ${profile.enabledModels.size} 个模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
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
private fun CompactSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.padding(7.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
            )
            message.footer?.let { footer ->
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
