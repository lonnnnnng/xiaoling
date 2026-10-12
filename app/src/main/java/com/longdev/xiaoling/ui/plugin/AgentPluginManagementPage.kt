package com.longdev.xiaoling.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AgentPluginInstallResult
import com.longdev.xiaoling.agent.AgentPluginJsonCodec
import com.longdev.xiaoling.agent.AgentPluginRegistry

@Composable
internal fun AgentPluginManagementPage(
    registry: AgentPluginRegistry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rawManifest by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    // long: Registry 在成功写盘后才更新内存；显式操作递增 revision 后才重新读取账本，避免输入内容被当作已安装状态。
    val plugins = remember(registry, revision) { registry.list() }
    val stateFailure = remember(registry, revision) { registry.persistenceFailureReason() }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("agent-plugin-management"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
            }
            Text("Agent 插件声明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "只安装声明式 manifest。启用仅影响只读能力预览，不执行插件代码，也不授予 MCP、终端、网络或设备动作权限。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stateFailure != null) {
            Text("插件状态不可用，变更已冻结：$stateFailure", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agent-plugin-state-error"))
        }
        OutlinedTextField(
            value = rawManifest,
            onValueChange = { if (it.length <= 16 * 1024) rawManifest = it },
            label = { Text("粘贴单个 manifest JSON") },
            supportingText = { Text("填写 id、version、name、description、publisher、capabilities、permissions、executionMode、source；apiVersion 缺省为 1，source 可为 null。") },
            modifier = Modifier.fillMaxWidth().testTag("agent-plugin-manifest-input"),
            minLines = 5,
            maxLines = 9,
            enabled = stateFailure == null,
        )
        Button(
            onClick = {
                val manifest = runCatching { AgentPluginJsonCodec.decodeManifest(rawManifest) }
                    .getOrElse { error ->
                        notice = error.message ?: "插件 manifest 无效"
                        return@Button
                    }
                // long: 安装和升级均由 Registry 做版本、来源指纹及写盘门禁；成功后仍保持默认停用。
                notice = when (val result = registry.install(manifest)) {
                    is AgentPluginInstallResult.Installed -> "已安装 ${result.plugin.manifest.id}，默认停用"
                    is AgentPluginInstallResult.AlreadyInstalled -> "${result.plugin.manifest.id} 已是相同版本"
                    is AgentPluginInstallResult.Rejected -> result.reason
                }
                revision += 1
            },
            enabled = stateFailure == null && rawManifest.isNotBlank(),
            modifier = Modifier.testTag("agent-plugin-install"),
        ) { Text("校验并安装") }
        notice?.let { Text(it, modifier = Modifier.testTag("agent-plugin-notice")) }

        Text("已安装声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (plugins.isEmpty()) {
            Text("尚无已安装插件", modifier = Modifier.testTag("agent-plugin-empty"))
        }
        plugins.forEach { plugin ->
            val manifest = plugin.manifest
            Card(modifier = Modifier.fillMaxWidth().testTag("agent-plugin-${manifest.id}")) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${manifest.name} · ${manifest.version}", fontWeight = FontWeight.SemiBold)
                    Text(manifest.id, style = MaterialTheme.typography.bodySmall)
                    Text("声明能力：${manifest.capabilities.sorted().joinToString().ifEmpty { "无" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("声明权限：${manifest.permissions.map { it.name }.sorted().joinToString().ifEmpty { "无" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用声明", modifier = Modifier.weight(1f))
                        Switch(
                            checked = plugin.enabled,
                            onCheckedChange = { enabled ->
                                notice = if (registry.setEnabled(manifest.id, enabled) != null) {
                                    "${manifest.id} 已${if (enabled) "启用" else "停用"}声明"
                                } else {
                                    "${manifest.id} 状态写入失败"
                                }
                                revision += 1
                            },
                            enabled = stateFailure == null,
                            modifier = Modifier.testTag("agent-plugin-enabled-${manifest.id}"),
                        )
                    }
                    TextButton(
                        onClick = { deleteId = manifest.id },
                        enabled = stateFailure == null,
                        modifier = Modifier.testTag("agent-plugin-delete-${manifest.id}"),
                    ) { Text("移除声明") }
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("移除插件声明？") },
            text = { Text("将移除 $id 的本地 manifest 和启停状态。") },
            confirmButton = {
                TextButton(onClick = {
                    notice = if (registry.uninstall(id)) "$id 已移除" else "$id 移除失败"
                    deleteId = null
                    revision += 1
                }, modifier = Modifier.testTag("agent-plugin-confirm-delete")) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
        )
    }
}
