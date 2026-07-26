package com.longdev.xiaoling.ui.provider

import android.util.Base64
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.ui.OperationResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Calendar

@Suppress("DEPRECATION")
@Composable
internal fun ProviderManagementPage(
    state: ProviderManagementUiState,
    actions: ProviderManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(actions::importDraftFromQr)
    }
    val draft = state.draft
    if (draft == null) {
        ProviderListContent(
            state = state,
            actions = actions,
            onBack = onBack,
            modifier = modifier,
        )
    } else {
        ProviderEditorContent(
            draft = draft,
            inlineResult = state.inlineResult,
            actions = actions,
            onBack = actions::closeProviderEditor,
            onScanRequested = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("扫描 baseUrl,apiKey 二维码")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            onImportFromClipboard = {
                actions.importDraftFromClipboard(clipboardManager.getText()?.text.orEmpty())
            },
            onCopyText = { value -> clipboardManager.setText(AnnotatedString(value)) },
            modifier = modifier,
        )
    }
}

@Composable
private fun ProviderListContent(
    state: ProviderManagementUiState,
    actions: ProviderManagementActions,
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
            onSyncAll = actions::syncAllProviders,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.profiles.isEmpty()) {
                    item {
                        ProviderSection(title = "模型提供方") {
                            Text(
                                text = "还没有模型提供方，点击右下角新增。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(
                        items = state.profiles,
                        key = { item -> item.profile.id },
                    ) { item ->
                        ProviderListItem(
                            item = item,
                            onSync = { actions.syncProviderModels(item.profile.id) },
                            onEdit = { actions.openEditProvider(item.profile.id) },
                            onDelete = { actions.deleteProvider(item.profile.id) },
                        )
                    }
                }
            }

            Button(
                onClick = actions::openNewProvider,
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
        Text(
            text = "模型提供方管理",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onSyncAll,
            enabled = !syncing,
            modifier = Modifier.size(30.dp),
        ) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
            } else {
                Icon(Icons.Default.CloudDownload, contentDescription = "批量同步", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun ProviderEditorContent(
    draft: ProviderEditDraft,
    inlineResult: OperationResult?,
    actions: ProviderManagementActions,
    onBack: () -> Unit,
    onScanRequested: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var base64DialogVisible by remember { mutableStateOf(false) }

    if (base64DialogVisible) {
        Base64DecodeDialog(
            onDismiss = { base64DialogVisible = false },
            onCopyPlainText = onCopyText,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Button(
                    onClick = actions::saveDraftProvider,
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
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = if (draft.id == null) "新增模型提供方" else "编辑模型提供方",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = onScanRequested,
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码导入", modifier = Modifier.size(16.dp))
                    }
                    OutlinedButton(
                        onClick = onImportFromClipboard,
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
                ProviderSection(title = "基础信息") {
                    ProviderUnderlineTextField(
                        value = draft.name,
                        onValueChange = actions::updateDraftName,
                        label = "名称（不填则使用 URL）",
                        singleLine = true,
                        modifier = Modifier.testTag(PROVIDER_NAME_FIELD_TAG),
                    )
                    Spacer(Modifier.height(3.dp))
                    ProviderUnderlineTextField(
                        value = draft.baseUrl,
                        onValueChange = actions::updateDraftBaseUrl,
                        label = "URL",
                        placeholder = "https://api.example.com/v1",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingLabelAction = {
                            LabelCopyButton(
                                enabled = draft.baseUrl.isNotBlank(),
                                contentDescription = "复制 URL",
                                onClick = { onCopyText(draft.baseUrl) },
                            )
                        },
                        modifier = Modifier.testTag(PROVIDER_URL_FIELD_TAG),
                    )
                    Spacer(Modifier.height(3.dp))
                    ProviderUnderlineTextField(
                        value = draft.apiKey,
                        onValueChange = actions::updateDraftApiKey,
                        label = "API Key",
                        singleLine = true,
                        trailingLabelAction = {
                            LabelCopyButton(
                                enabled = draft.apiKey.isNotBlank(),
                                contentDescription = "复制 API Key",
                                onClick = { onCopyText(draft.apiKey) },
                            )
                        },
                        modifier = Modifier.testTag(PROVIDER_API_KEY_FIELD_TAG),
                    )
                }
            }

            item {
                ProviderSection(
                    title = "上游模型",
                    action = {
                        OutlinedButton(
                            onClick = actions::fetchDraftModels,
                            enabled = !draft.loadingModels,
                            shape = RoundedCornerShape(7.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            if (draft.loadingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.6.dp)
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
                                    onCheckedChange = { checked -> actions.toggleDraftModel(model, checked) },
                                )
                            }
                        }
                    }
                }
            }

            inlineResult?.let { result -> item { ProviderOperationResultPanel(result) } }

            item { Spacer(Modifier.height(56.dp)) }
        }
    }
}

@Composable
private fun ProviderListItem(
    item: ProviderManagementItemUiState,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val profile = item.profile
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
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
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("共 ${profile.enabledModels.size} 个模型")
                        item.syncResult?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.syncResult == "同步失败") {
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
                    IconButton(onClick = onSync, enabled = !item.syncing, modifier = Modifier.size(28.dp)) {
                        if (item.syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
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
            modifier = Modifier
                .size(28.dp)
                .testTag(providerModelCheckboxTag(model)),
        )
        Text(model, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProviderSection(
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
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (action == null) Modifier.weight(1f) else Modifier,
                )
                action?.invoke()
                if (action != null) Spacer(Modifier.weight(1f))
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            content()
        }
    }
}

@Composable
private fun ProviderUnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingLabelAction: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
            minLines = 1,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = modifier
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

    AlertDialog(
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
                OutlinedTextField(
                    value = encodedText,
                    onValueChange = { encodedText = it },
                    label = { Text("Base64 密文", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 4,
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
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
private fun ProviderOperationResultPanel(result: OperationResult) {
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
                result.latencyMs?.let { latency ->
                    Text("总 $latency ms", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(result.message, style = MaterialTheme.typography.bodySmall)
            result.requestUrl?.let { requestUrl ->
                Text(requestUrl, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
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

internal const val PROVIDER_NAME_FIELD_TAG = "provider-name-field"
internal const val PROVIDER_URL_FIELD_TAG = "provider-url-field"
internal const val PROVIDER_API_KEY_FIELD_TAG = "provider-api-key-field"
internal fun providerModelCheckboxTag(model: String): String = "provider-model-checkbox-$model"
