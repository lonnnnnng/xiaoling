package com.longdev.xiaoling.ui.networksettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.ui.CompactSection
import com.longdev.xiaoling.ui.CompactTextField
import com.longdev.xiaoling.ui.PageTitle

@Suppress("DEPRECATION")
@Composable
internal fun NetworkRequestSettingsPage(
    state: NetworkRequestSettingsUiState,
    actions: NetworkRequestSettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    NetworkRequestSettingsContent(
        state = state,
        actions = actions,
        onCopyUserAgent = { clipboardManager.setText(AnnotatedString(it)) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun NetworkRequestSettingsContent(
    state: NetworkRequestSettingsUiState,
    actions: NetworkRequestSettingsActions,
    onCopyUserAgent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val userAgent = state.userAgent
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
                IconButton(onClick = actions::resetUserAgent, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Restore, contentDescription = "恢复默认 User-Agent", modifier = Modifier.size(16.dp))
                }
            },
        ) {
            CompactTextField(
                value = userAgent,
                onValueChange = actions::updateUserAgent,
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
                    onClick = { actions.updateUserAgent("") },
                    enabled = userAgent.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "清空 User-Agent", modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}
