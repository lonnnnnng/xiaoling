package com.longdev.xiaoling.ui.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.McpServerConfig

internal interface McpServerManagementActions {
    fun refreshServers()
    fun discoverTools(serverId: String)
    fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean)
    fun saveServer(config: McpServerConfig)
    fun setServerEnabled(id: String, enabled: Boolean)
    fun requestDelete(id: String)
    fun cancelDelete()
    fun confirmDelete()
}

internal data class McpServerManagementUiState(
    val servers: List<McpServerConfig> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val mutatingIds: Set<String> = emptySet(),
    val toolsByServer: Map<String, List<com.longdev.xiaoling.agent.McpToolDescriptor>> = emptyMap(),
    val loadingToolServerIds: Set<String> = emptySet(),
    val mutatingToolKeys: Set<String> = emptySet(),
    val error: String? = null,
    val pendingDelete: McpServerConfig? = null,
)

@Composable
internal fun McpServerManagementPage(
    state: McpServerManagementUiState,
    actions: McpServerManagementActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editor by remember { mutableStateOf<McpServerEditorState?>(null) }
    LaunchedEffect(Unit) {
        if (state.servers.isEmpty() && !state.loading) actions.refreshServers()
    }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", modifier = Modifier.size(18.dp))
            }
            Text("MCP Servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = actions::refreshServers, enabled = !state.loading, modifier = Modifier.size(30.dp)) {
                if (state.loading) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.6.dp)
                else Icon(Icons.Default.CloudDownload, contentDescription = "刷新 MCP Server", modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = { editor = McpServerEditorState.new() },
                enabled = !state.saving,
                contentPadding = PaddingValues(horizontal = 9.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "仅前台直接 Agent 使用 MCP。HTTPS 服务器默认要求公开地址；本机调试只允许 loopback。Token 只显示配置状态，不回显密文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("mcp-server-list"),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            if (state.loading && state.servers.isEmpty()) {
                item { CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(18.dp), strokeWidth = 1.6.dp) }
            } else if (state.servers.isEmpty()) {
                item { Text("尚未配置 MCP Server", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
            } else {
                items(state.servers, key = { it.id }) { server ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("mcp-server-item:${server.id}"),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(server.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${server.id} · ${server.url}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (server.bearerToken.isBlank()) "未配置 Token" else "Bearer Token 已配置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { editor = McpServerEditorState.from(server) }, enabled = server.id !in state.mutatingIds, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑 MCP Server", modifier = Modifier.size(17.dp))
                                }
                                IconButton(onClick = { actions.requestDelete(server.id) }, enabled = server.id !in state.mutatingIds, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除 MCP Server", modifier = Modifier.size(17.dp))
                                }
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { actions.setServerEnabled(server.id, it) },
                                    enabled = server.id !in state.mutatingIds,
                                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { actions.discoverTools(server.id) },
                                    enabled = server.id !in state.loadingToolServerIds && server.id !in state.mutatingIds,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    if (server.id in state.loadingToolServerIds) {
                                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text("发现工具", style = MaterialTheme.typography.labelSmall)
                                }
                                val discoveredTools = state.toolsByServer[server.id]
                                if (discoveredTools != null) {
                                    Text(
                                        if (server.enabledToolNames == null) {
                                            "${discoveredTools.size} 个工具 · 旧配置默认允许"
                                        } else {
                                            "${server.enabledToolNames.size}/${discoveredTools.size} 个工具已启用"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            state.toolsByServer[server.id]?.let { tools ->
                                if (tools.isEmpty()) {
                                    Text(
                                        "服务器没有返回工具",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        tools.forEach { tool ->
                                            val toolKey = "${server.id}::${tool.name}"
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(tool.name, style = MaterialTheme.typography.labelMedium)
                                                    if (tool.description.isNotBlank()) {
                                                        Text(
                                                            tool.description,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 2,
                                                        )
                                                    }
                                                }
                                                Switch(
                                                    checked = server.enabledToolNames?.contains(tool.name) ?: true,
                                                    onCheckedChange = { enabled -> actions.setToolEnabled(server.id, tool.name, enabled) },
                                                    enabled = toolKey !in state.mutatingToolKeys && server.id !in state.mutatingIds,
                                                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editor?.let { draft ->
        AlertDialog(
            onDismissRequest = { if (!state.saving) editor = null },
            title = { Text(if (draft.editing) "编辑 MCP Server" else "新增 MCP Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextField(draft.id, { draft.id = it }, label = { Text("ID（小写字母、数字、._-）") }, enabled = !draft.editing && !state.saving, singleLine = true)
                    TextField(draft.name, { draft.name = it }, label = { Text("名称") }, enabled = !state.saving, singleLine = true)
                    TextField(draft.url, { draft.url = it }, label = { Text("Streamable HTTP URL") }, enabled = !state.saving, singleLine = true)
                    TextField(draft.token, { draft.token = it }, label = { Text("Bearer Token（编辑时留空表示保留）") }, enabled = !state.saving, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        editor = null
                        actions.saveServer(draft.toConfig())
                    },
                    enabled = draft.id.isNotBlank() && draft.name.isNotBlank() && draft.url.isNotBlank() && !state.saving,
                ) { Text("保存") }
            },
            dismissButton = { OutlinedButton(onClick = { editor = null }, enabled = !state.saving) { Text("取消") } },
        )
    }
    state.pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = actions::cancelDelete,
            title = { Text("删除 MCP Server？") },
            text = { Text("将删除 ${server.name}（${server.id}）的本地配置和加密 Token。") },
            confirmButton = { Button(onClick = actions::confirmDelete) { Text("删除") } },
            dismissButton = { OutlinedButton(onClick = actions::cancelDelete) { Text("取消") } },
        )
    }
}

private class McpServerEditorState(
    initialId: String,
    initialName: String,
    initialUrl: String,
    initialToken: String,
    var enabled: Boolean,
    val editing: Boolean,
) {
    var id by mutableStateOf(initialId)
    var name by mutableStateOf(initialName)
    var url by mutableStateOf(initialUrl)
    var token by mutableStateOf(initialToken)

    fun toConfig(): McpServerConfig = McpServerConfig(
        id = id.trim(),
        name = name.trim(),
        url = url.trim(),
        bearerToken = token,
        enabled = enabled,
        enabledToolNames = enabledToolNames,
    )

    private var enabledToolNames: Set<String>? = null

    companion object {
        fun new() = McpServerEditorState("", "", "", "", enabled = true, editing = false)
        fun from(server: McpServerConfig) = McpServerEditorState(server.id, server.name, server.url, "", server.enabled, editing = true).also {
            it.enabledToolNames = server.enabledToolNames
        }
    }
}
