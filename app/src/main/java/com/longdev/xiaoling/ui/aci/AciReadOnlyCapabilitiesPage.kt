package com.longdev.xiaoling.ui.aci

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.AciCapabilityDescriptor
import com.longdev.xiaoling.agent.AciPolicy
import com.longdev.xiaoling.agent.AgentPluginCapabilityProjector
import com.longdev.xiaoling.agent.InstalledAgentPlugin
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk

internal fun projectAciReadOnlyCapabilities(
    registeredTools: List<ToolDefinition>,
    allowedToolNames: Set<String>,
): List<AciCapabilityDescriptor> {
    // long: 设置页只投影已注册且属于固定 SAFE 白名单的工具，避免页面绕过 Profile 和运行时桥接自行扩展 ACI 能力。
    return registeredTools
        .asSequence()
        .filter { it.name in AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES }
        .filter { it.risk == ToolRisk.SAFE }
        .filter { it.name in allowedToolNames }
        .distinctBy(ToolDefinition::name)
        .map { tool ->
            AciCapabilityDescriptor(
                id = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
            )
        }
        .sortedBy(AciCapabilityDescriptor::id)
        .toList()
}

@Composable
internal fun AciReadOnlyCapabilitiesPage(
    selectedProfileName: String?,
    registeredTools: List<ToolDefinition>,
    allowedToolNames: Set<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    installedPlugins: List<InstalledAgentPlugin> = emptyList(),
    pluginStateUnavailable: Boolean = false,
) {
    val capabilities = projectAciReadOnlyCapabilities(registeredTools, allowedToolNames)
    // long: 设置页只有静态 ToolRegistry 定义；插件区只展示此时的交集预览，真正的 Run 仍需重新检查动态目录和 Profile。
    val pluginProjections = AgentPluginCapabilityProjector.projectAll(
        plugins = installedPlugins,
        registeredTools = registeredTools,
        availableTools = registeredTools,
        allowedToolNames = allowedToolNames,
    )
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("aci-read-only-capabilities"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回设置",
                )
            }
            Text("ACI 只读能力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("当前 Profile", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(selectedProfileName ?: "未选择 Profile", modifier = Modifier.testTag("aci-selected-profile"))
                Text(
                    "这里只读展示前台 direct 调用可以发现的 SAFE 能力；不会在设置页执行工具，也不会授予后台、Workflow 或远程入口权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (capabilities.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().testTag("aci-empty")) {
                Text(
                    "当前 Profile 没有可发现的 ACI 只读能力。请在 Agent Profiles 中检查 SAFE 工具白名单。",
                    modifier = Modifier.padding(10.dp),
                )
            }
        } else {
            capabilities.forEach { capability ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("aci-capability-${capability.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(capability.id, fontWeight = FontWeight.SemiBold)
                            Text(capability.description, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text("SAFE · 当前 Profile 已授权", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Text("插件能力声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "以下仅是声明与当前 Profile、已注册 SAFE 工具的静态交集预览；不会安装执行器、扩充工具目录或授权 ACI、MCP、终端及设备动作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pluginStateUnavailable) {
            Text(
                "插件状态无法读取，能力预览已关闭。",
                modifier = Modifier.testTag("aci-plugin-state-unavailable"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (pluginProjections.isEmpty()) {
            Text(
                "尚无已安装的声明式插件。",
                modifier = Modifier.testTag("aci-plugin-empty"),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            pluginProjections.forEach { plugin ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("aci-plugin-${plugin.pluginId}"),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(plugin.pluginId, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (plugin.enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            if (plugin.readOnlyPermissionDeclared) "已声明只读能力权限" else "未声明只读能力权限",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "符合只读交集：${plugin.effectiveReadOnlyCapabilities.sorted().joinToString().ifEmpty { "无" }}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("aci-plugin-effective-${plugin.pluginId}"),
                        )
                        Text(
                            "仅保留声明：${plugin.excludedCapabilities.sorted().joinToString().ifEmpty { "无" }}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("aci-plugin-excluded-${plugin.pluginId}"),
                        )
                    }
                }
            }
        }
    }
}
