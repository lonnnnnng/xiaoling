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
import com.longdev.xiaoling.agent.AgentPluginSignedManifest
import com.longdev.xiaoling.agent.AgentPluginSigning
import com.longdev.xiaoling.agent.InstalledAgentPlugin
import com.longdev.xiaoling.agent.AciPolicy
import com.longdev.xiaoling.agent.AgentPluginPermission
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.ToolApprovalPolicy

@Composable
internal fun AgentPluginManagementPage(
    registry: AgentPluginRegistry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selectedProfileName: String? = null,
    registeredTools: List<ToolDefinition> = emptyList(),
    allowedToolNames: Set<String> = emptySet(),
    executionAvailable: Boolean = false,
    onRunReadOnly: (String, String, String) -> Boolean = { _, _, _ -> false },
) {
    var rawManifest by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<InstalledAgentPlugin?>(null) }
    var pendingSigned by remember { mutableStateOf<AgentPluginSignedManifest?>(null) }
    var pendingRollback by remember { mutableStateOf<Pair<InstalledAgentPlugin, String>?>(null) }
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
            Text("Agent 插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "签名插件启用后，可填写目标并显式运行宿主只读能力。离开前台或停用会撤销本轮许可；不加载插件代码。无签名声明仅供预览。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stateFailure != null) {
            Text("插件状态不可用，变更已冻结：$stateFailure", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agent-plugin-state-error"))
        }
        OutlinedTextField(
            value = rawManifest,
            onValueChange = { if (it.length <= 24 * 1024) rawManifest = it },
            label = { Text("粘贴 manifest 或签名 envelope JSON") },
            supportingText = { Text("无签名声明仅供预览；签名 envelope 校验后需确认发布者公钥，签名不证明发布者的真实身份。") },
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
        Button(
            onClick = {
                pendingSigned = runCatching { AgentPluginJsonCodec.decodeSignedManifest(rawManifest) }
                    .getOrElse { notice = it.message ?: "插件签名无效"; return@Button }
            },
            enabled = stateFailure == null && rawManifest.isNotBlank(),
            modifier = Modifier.testTag("agent-plugin-verify-signature"),
        ) { Text("校验签名并确认公钥") }
        notice?.let { Text(it, modifier = Modifier.testTag("agent-plugin-notice")) }

        Text("已安装声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (plugins.isEmpty()) {
            Text("尚无已安装插件", modifier = Modifier.testTag("agent-plugin-empty"))
        }
        plugins.forEach { plugin ->
            val manifest = plugin.manifest
            var goal by remember(manifest) { mutableStateOf("") }
            val confirmation = plugin.signedManifest?.let { AgentPluginSigning.verify(it) }
            val safeNames = registeredTools.filter { it.name in manifest.capabilities &&
                it.name in allowedToolNames && it.name in AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES &&
                it.risk == ToolRisk.SAFE && it.approvalPolicy == ToolApprovalPolicy.NONE }.map { it.name }.sorted()
            val canRun = stateFailure == null && plugin.enabled && confirmation != null &&
                AgentPluginPermission.READ_ONLY_CAPABILITIES in manifest.permissions && safeNames.isNotEmpty() &&
                selectedProfileName != null && executionAvailable
            Card(modifier = Modifier.fillMaxWidth().testTag("agent-plugin-${manifest.id}")) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${manifest.name} · ${manifest.version}", fontWeight = FontWeight.SemiBold)
                    Text(manifest.id, style = MaterialTheme.typography.bodySmall)
                    Text(if (plugin.signedManifest == null) "无签名声明 · 仅预览" else "签名已校验 · 已确认公钥",
                        style = MaterialTheme.typography.bodySmall)
                    confirmation?.let {
                        Text("公钥 SHA-256：${it.publicKeySha256}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("最高确认版本：${plugin.highestSignedManifest?.manifest?.version}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("声明能力：${manifest.capabilities.sorted().joinToString().ifEmpty { "无" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("声明权限：${manifest.permissions.map { it.name }.sorted().joinToString().ifEmpty { "无" }}",
                        style = MaterialTheme.typography.bodySmall)
                    if (confirmation != null) {
                        Text("当前 Agent：${selectedProfileName ?: "未选择"} · 只读能力：${safeNames.joinToString().ifEmpty { "无交集" }}",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = goal, onValueChange = { if (it.length <= 2_000) goal = it },
                            label = { Text("只读任务目标") }, enabled = canRun,
                            supportingText = { Text("最多 3 次工具调用、60 秒；能力仍受当前可用状态与 Agent 记忆开关约束。当前会话将保存结果。") },
                            modifier = Modifier.fillMaxWidth().testTag("agent-plugin-goal-${manifest.id}"))
                        Button(onClick = {
                            // long: 页面冻结的摘要随点击传入执行层；升级或停用后旧页面不能批准替换后的内容。
                            if (!onRunReadOnly(manifest.id, goal.trim(), confirmation.manifestSha256)) {
                                notice = "任务未启动，请检查当前 Agent、前台状态和插件版本"
                                revision += 1
                            }
                        }, enabled = canRun && goal.isNotBlank(),
                            modifier = Modifier.testTag("agent-plugin-run-${manifest.id}")) { Text("运行只读任务") }
                    }
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
                        onClick = { pendingDelete = plugin },
                        enabled = stateFailure == null,
                        modifier = Modifier.testTag("agent-plugin-delete-${manifest.id}"),
                    ) { Text("移除声明") }
                    registry.rollbackVersions(manifest.id).forEach { version ->
                        TextButton(
                            onClick = { pendingRollback = plugin to version }, enabled = stateFailure == null,
                            modifier = Modifier.testTag("agent-plugin-rollback-${manifest.id}-$version"),
                        ) { Text("回滚到 $version") }
                    }
                }
            }
        }
    }

    pendingDelete?.let { plugin ->
        val id = plugin.manifest.id
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除插件声明？") },
            text = { Text("将移除 $id 的已安装声明。签名插件保留已确认公钥、最高版本和有界历史，以约束重装。") },
            confirmButton = {
                TextButton(onClick = {
                    notice = if (registry.uninstall(id, plugin.manifest)) "$id 已移除" else "$id 移除失败或版本已变化"
                    pendingDelete = null
                    revision += 1
                }, modifier = Modifier.testTag("agent-plugin-confirm-delete")) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    pendingSigned?.let { signed ->
        val confirmation = remember(signed) { AgentPluginSigning.verify(signed) }
        AlertDialog(
            onDismissRequest = { pendingSigned = null },
            title = { Text("确认发布者公钥") },
            text = { Text("${signed.manifest.id} · ${signed.manifest.version}\n发布者声明：${signed.manifest.publisher}\n公钥 SHA-256：${confirmation.publicKeySha256}\nmanifest SHA-256：${confirmation.manifestSha256}\n请通过可信渠道核对公钥指纹。确认仅绑定此公钥与声明，安装后默认停用，仍不执行插件代码。",
                modifier = Modifier.testTag("agent-plugin-publisher-confirmation")) },
            confirmButton = {
                TextButton(onClick = {
                    // long: 使用打开对话框时冻结的 envelope，确认后 Registry 再验签，输入变动不会替换批准对象。
                    notice = when (val result = registry.installSigned(signed, confirmation)) {
                        is AgentPluginInstallResult.Installed -> "已安装 ${result.plugin.manifest.id}，默认停用"
                        is AgentPluginInstallResult.AlreadyInstalled -> "${result.plugin.manifest.id} 已是相同版本"
                        is AgentPluginInstallResult.Rejected -> result.reason
                    }
                    pendingSigned = null
                    revision += 1
                }, enabled = stateFailure == null, modifier = Modifier.testTag("agent-plugin-confirm-publisher")) { Text("确认公钥并安装") }
            },
            dismissButton = { TextButton(onClick = { pendingSigned = null }, modifier = Modifier.testTag("agent-plugin-cancel-publisher")) { Text("取消") } },
        )
    }

    pendingRollback?.let { (plugin, version) ->
        AlertDialog(
            onDismissRequest = { pendingRollback = null }, title = { Text("回滚插件版本？") },
            text = { Text("${plugin.manifest.id} 从 ${plugin.manifest.version} 回滚到已保存的 $version；回滚后默认停用，最高版本与公钥绑定继续保留。") },
            confirmButton = {
                TextButton(onClick = {
                    notice = when (val result = registry.rollback(plugin.manifest.id, version, plugin.manifest)) {
                        is AgentPluginInstallResult.Installed -> "已回滚到 $version，默认停用"
                        is AgentPluginInstallResult.Rejected -> result.reason
                        is AgentPluginInstallResult.AlreadyInstalled -> "已是该版本"
                    }
                    pendingRollback = null
                    revision += 1
                }, enabled = stateFailure == null, modifier = Modifier.testTag("agent-plugin-confirm-rollback")) { Text("回滚") }
            },
            dismissButton = { TextButton(onClick = { pendingRollback = null }) { Text("取消") } },
        )
    }
}
