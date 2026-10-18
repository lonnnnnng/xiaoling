package com.longdev.xiaoling.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import com.longdev.xiaoling.genui.GenUiActionLease
import com.longdev.xiaoling.genui.GenUiActionPolicy
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.genui.GenUiDocument
import com.longdev.xiaoling.genui.GenUiItem

@Composable
internal fun GenUiMessageContent(
    document: GenUiDocument,
    contentColor: androidx.compose.ui.graphics.Color,
    onAction: (String) -> Unit,
    draftKey: String = "",
    actionLease: GenUiActionLease? = null,
    controlsEnabled: Boolean = true,
    onHostAction: (String, Map<String, String>) -> Unit = { _, _ -> },
) {
    // long: 草稿不使用 rememberSaveable；重组保留当前编辑，换会话/正文或重建 Activity 时不会复用旧输入。
    val drafts = remember(draftKey, document) { mutableStateMapOf<String, String>() }
    var current by remember(actionLease) { mutableStateOf(actionLease != null && System.currentTimeMillis() < actionLease.expiresAt) }
    LaunchedEffect(actionLease) {
        actionLease?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); current = false }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            document.title?.let { title ->
                Text(
                    text = title,
                    color = contentColor,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            document.items.forEach { item ->
                when (item) {
                    is GenUiItem.Text -> Text(
                        text = item.text,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is GenUiItem.Button -> Button(
                        onClick = { onAction(item.action) },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.label)
                    }
                    is GenUiItem.Facts -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.entries.forEach { (label, value) -> Text("$label：$value", color = contentColor,
                            style = MaterialTheme.typography.bodyMedium) }
                    }
                    is GenUiItem.Input -> OutlinedTextField(value = drafts[item.id].orEmpty(),
                        onValueChange = { value -> if (value.length <= item.maxLength && value.none(Char::isISOControl)) drafts[item.id] = value },
                        label = { Text(item.label) }, singleLine = true,
                        enabled = controlsEnabled && current,
                        modifier = Modifier.fillMaxWidth().testTag("genui-input-${item.id}"))
                    is GenUiItem.Action -> {
                        val arguments = item.arguments + item.fields.mapValues { (_, field) -> drafts[field].orEmpty().trim() }
                        val valid = runCatching { GenUiActionPolicy.validateArguments(item.tool, arguments) }.isSuccess
                        Button(onClick = { onHostAction(item.id, drafts.toMap()) },
                            enabled = controlsEnabled && current && valid,
                            modifier = Modifier.fillMaxWidth().testTag("genui-action-${item.id}")) { Text(item.label) }
                    }
                }
            }
            if (document.version == 2 && document.items.any { it is GenUiItem.Action }) Text(
                when {
                    !current -> "卡片操作已失效，请生成新卡片。"
                    !controlsEnabled -> "当前操作结束后可申请确认。"
                    else -> "草稿仅留在本机内存；操作需逐次确认。会话条数为 1–10。"
                }, color = contentColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}
