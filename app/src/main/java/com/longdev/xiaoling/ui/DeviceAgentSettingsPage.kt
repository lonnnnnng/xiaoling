package com.longdev.xiaoling.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotNode

@Composable
internal fun DeviceAgentSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeviceAgentSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DeviceAgentSettingsContent(
        state = viewModel.uiState,
        onEnabledChanged = viewModel::setEnabled,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRefresh = viewModel::refresh,
        onCaptureSnapshot = viewModel::captureSnapshot,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun DeviceAgentSettingsContent(
    state: DeviceAgentSettingsUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRefresh: () -> Unit,
    onCaptureSnapshot: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
                }
                Text(
                    text = "设备 Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = !state.capturing, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新设备 Agent 状态", modifier = Modifier.size(18.dp))
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("启用设备 Agent", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "系统无障碍授权和本应用开关必须同时有效；有限动作仅由前台 Agent 按审批和后置验证执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = onEnabledChanged,
                            modifier = Modifier.semantics { contentDescription = "启用设备 Agent" },
                        )
                    }
                    DeviceAgentHealthBand(state.health)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("无障碍设置", modifier = Modifier.padding(start = 6.dp))
                        }
                        Button(
                            onClick = onCaptureSnapshot,
                            enabled = state.health == DeviceAgentHealthState.READY && !state.capturing,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.capturing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text("读取当前界面", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.snapshot?.let { snapshot ->
            item { DeviceSnapshotSummary(snapshot) }
            items(snapshot.nodes, key = DeviceSnapshotNode::index) { node ->
                DeviceSnapshotNodeRow(node)
            }
        }
    }
}

@Composable
private fun DeviceAgentHealthBand(health: DeviceAgentHealthState) {
    val (text, color) = when (health) {
        DeviceAgentHealthState.AGENT_DISABLED -> "设备 Agent 已关闭" to MaterialTheme.colorScheme.outline
        DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED ->
            "无障碍服务未授权或授权已失效" to MaterialTheme.colorScheme.error
        DeviceAgentHealthState.SERVICE_DISCONNECTED ->
            "已授权，服务尚未连接" to MaterialTheme.colorScheme.tertiary
        DeviceAgentHealthState.READY ->
            "服务正常，可读取当前界面" to MaterialTheme.colorScheme.primary
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun DeviceSnapshotSummary(snapshot: DeviceSnapshot) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最近快照", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(snapshot.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            snapshot.windowTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "${snapshot.nodes.size} 个节点 · ${snapshot.redactedNodeCount} 个已脱敏${if (snapshot.truncated) " · 已截断" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${snapshot.snapshotId} · ref 在 ${snapshot.expiresAt - snapshot.capturedAt} ms 内有效",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DeviceSnapshotNodeRow(node: DeviceSnapshotNode) {
    val label = if (node.redacted) {
        "已脱敏 · ${node.role}"
    } else {
        listOfNotNull(node.ref, node.role, node.text ?: node.description ?: node.hint).joinToString(" · ")
    }
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "[${node.bounds.left}, ${node.bounds.top}, ${node.bounds.right}, ${node.bounds.bottom}]" +
                    node.actions.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") { it.name.lowercase() }.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
