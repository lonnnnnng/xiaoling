package com.longdev.xiaoling.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class McpConnectionInfo(val protocolVersion: String, val capabilities: Set<String>)

interface McpClient {
    suspend fun connect(server: McpServerConfig): McpConnectionInfo
    fun isConnected(server: McpServerConfig): Boolean
    suspend fun listTools(server: McpServerConfig): List<McpToolDescriptor>
    suspend fun callTool(server: McpServerConfig, name: String, arguments: JSONObject, knownTools: List<McpToolDescriptor>? = null): String
    suspend fun listResources(server: McpServerConfig): List<McpResourceDescriptor>
    suspend fun readResource(server: McpServerConfig, uri: String): List<McpResourceContent>
    suspend fun listPrompts(server: McpServerConfig): List<McpPromptDescriptor>
    suspend fun getPrompt(server: McpServerConfig, name: String, arguments: Map<String, String> = emptyMap()): McpPromptResult
    fun close()
    /** long: 注册表丢弃实例时永久撤销；实现不能让迟到调用在 close 后重新建连。 */
    fun retire() = close()
}

enum class McpConnectionState { DISCONNECTED, CONNECTING, READY }

/** long: 进程内统一管理所有 owner；Run 结束只撤销本 owner，配置变化则撤销该服务器的全部 owner。 */
class McpSessionRegistry(
    val store: McpServerStore,
    private val factory: () -> McpClient = { StreamableHttpMcpClient() },
) {
    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, String>, Entry>()
    private val blockedServers = mutableSetOf<String>()

    private class Entry(val config: McpServerConfig, val client: McpClient) {
        val initialization = Mutex()
        var info: McpConnectionInfo? = null
        var tools: List<McpToolDescriptor> = emptyList()
        var readyAt = 0L
    }

    fun client(owner: String): McpClient {
        require(owner.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "MCP owner 无效" }
        return OwnedClient(owner)
    }

    fun upsert(config: McpServerConfig): McpServerConfig = synchronized(lock) {
        val frozen = freeze(config)
        McpServerPolicy.validate(frozen)
        // long: 先撤销代次并关闭旧 Call/SSE，再保存新配置；写失败保持冻结，不能继续用用户刚撤销的连接。
        invalidateServer(frozen.id)
        blockedServers += frozen.id
        store.upsert(frozen).also { blockedServers -= frozen.id }
    }

    fun upsertPreservingToken(config: McpServerConfig): McpServerConfig = synchronized(lock) {
        // long: 空 Token 的保留语义也在同一把锁内读取，避免编辑保存覆盖另一入口刚更新的凭据。
        upsert(config.copy(bearerToken = config.bearerToken.ifBlank { store.get(config.id)?.bearerToken.orEmpty() }))
    }

    fun updateToolAccess(id: String, discoveredNames: Set<String>, name: String, enabled: Boolean): McpServerConfig = synchronized(lock) {
        val current = store.get(id) ?: error("未找到已配置的 MCP Server")
        val names = McpToolAccessPolicy.updatedEnabledToolNames(current, discoveredNames, name, enabled)
        upsert(current.copy(enabledToolNames = names))
    }

    fun setEnabled(id: String, enabled: Boolean): McpServerConfig = synchronized(lock) {
        upsert((store.get(id) ?: error("未找到已配置的 MCP Server")).copy(enabled = enabled))
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        invalidateServer(id)
        blockedServers += id
        store.delete(id).also { blockedServers -= id }
    }

    fun disconnect(owner: String) = synchronized(lock) {
        entries.keys.filter { it.first == owner }.forEach { key -> entries.remove(key)?.client?.retire() }
    }

    fun close() = synchronized(lock) {
        val clients = entries.values.map { it.client }
        entries.clear()
        clients.forEach(McpClient::retire)
    }

    fun state(owner: String, serverId: String): McpConnectionState = synchronized(lock) {
        val entry = entries[owner to serverId] ?: return@synchronized McpConnectionState.DISCONNECTED
        if (!isCurrent(owner, entry)) McpConnectionState.DISCONNECTED
        else if (entry.info == null) McpConnectionState.CONNECTING
        else if (!isReady(entry)) McpConnectionState.DISCONNECTED else McpConnectionState.READY
    }

    fun availableTools(owner: String, serverId: String): List<McpToolDescriptor> = synchronized(lock) {
        val entry = entries[owner to serverId] ?: return@synchronized emptyList()
        if (!isReady(entry) || !isCurrent(owner, entry)) emptyList()
        else entry.tools.filter { McpToolAccessPolicy.isEnabled(entry.config, it.name) }
    }

    private fun invalidateServer(id: String) {
        entries.keys.filter { it.second == id }.forEach { key -> entries.remove(key)?.client?.retire() }
    }

    private fun freeze(config: McpServerConfig) = config.copy(enabledToolNames = config.enabledToolNames?.toSet(), args = config.args.toList())

    private fun isReady(entry: Entry) = entry.info != null && entry.client.isConnected(entry.config) &&
        System.currentTimeMillis() - entry.readyAt < McpPolicy.TOOL_CACHE_TTL_MILLIS

    private fun isCurrent(owner: String, entry: Entry): Boolean =
        entries[owner to entry.config.id] === entry && entry.config.id !in blockedServers && store.get(entry.config.id) == entry.config && entry.config.enabled

    private fun checkCurrent(owner: String, entry: Entry) {
        check(isCurrent(owner, entry)) { "MCP 配置或连接已变化，请重新发现目录" }
    }

    private fun entry(owner: String, config: McpServerConfig): Entry = synchronized(lock) {
        val frozen = freeze(config)
        require(frozen.enabled && frozen.id !in blockedServers && store.get(frozen.id) == frozen) { "MCP Server 已停用或配置已变化" }
        val key = owner to frozen.id
        entries[key]?.let { existing ->
            if (existing.config == frozen && (existing.info == null ||
                    existing.client.isConnected(frozen) && System.currentTimeMillis() - existing.readyAt < McpPolicy.TOOL_CACHE_TTL_MILLIS)) return@synchronized existing
            entries.remove(key)
            existing.client.retire()
        }
        require(entries.size < 64) { "MCP 活动会话超过限制" }
        Entry(frozen, factory()).also { entries[key] = it }
    }

    private suspend fun ready(owner: String, entry: Entry) = entry.initialization.withLock {
        synchronized(lock) { checkCurrent(owner, entry) }
        if (entry.info == null) {
            val info = entry.client.connect(entry.config)
            val tools = if ("tools" in info.capabilities) entry.client.listTools(entry.config) else emptyList()
            McpServerPolicy.validateToolNames(tools.mapTo(linkedSetOf()) { it.name })
            require(tools.size <= McpPolicy.MAX_CATALOG_ITEMS) { "MCP 工具目录超过限制" }
            tools.forEach { JSONObject(it.inputSchemaJson) }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                // long: 握手与 schema 分页都完成后才原子发布；旧配置的迟到响应不能重建已撤销的目录。
                checkCurrent(owner, entry)
                check(entry.client.isConnected(entry.config)) { "MCP 会话已断开" }
                entry.tools = tools.toList()
                entry.readyAt = System.currentTimeMillis()
                entry.info = info
            }
        }
    }

    private suspend fun <T> use(owner: String, config: McpServerConfig, action: suspend (Entry) -> T): T {
        val entry = entry(owner, config)
        return try {
            ready(owner, entry)
            synchronized(lock) { checkCurrent(owner, entry); check(isReady(entry)) { "MCP 会话已断开或过期" } }
            val result = action(entry)
            currentCoroutineContext().ensureActive()
            synchronized(lock) { checkCurrent(owner, entry); check(isReady(entry)) { "MCP 会话已断开或过期" } }
            result
        } catch (error: Exception) {
            synchronized(lock) {
                if (entries[owner to config.id] === entry) entries.remove(owner to config.id)
                entry.client.retire()
            }
            throw error
        }
    }

    private inner class OwnedClient(private val owner: String) : McpClient {
        override suspend fun connect(server: McpServerConfig) = use(owner, server) { it.info!! }
        override fun isConnected(server: McpServerConfig) = synchronized(lock) {
            val entry = entries[owner to server.id]
            // long: 同一个 id 的新配置已连接，也不能把旧凭据或旧 transport 的连接查询判成可用。
            entry != null && entry.config == freeze(server) && isCurrent(owner, entry) && isReady(entry)
        }
        override suspend fun listTools(server: McpServerConfig) = use(owner, server) { it.tools }
        override suspend fun callTool(server: McpServerConfig, name: String, arguments: JSONObject, knownTools: List<McpToolDescriptor>?): String = use(owner, server) { entry ->
            require(McpToolAccessPolicy.isEnabled(entry.config, name)) { "MCP 工具已停用" }
            val descriptor = entry.tools.firstOrNull { it.name == name } ?: error("MCP 工具未在当前目录发现")
            require(knownTools == null || knownTools.firstOrNull { it.name == name } == descriptor) { "MCP 工具 schema 已变化，请重新发现目录" }
            entry.client.callTool(entry.config, name, arguments, entry.tools)
        }
        override suspend fun listResources(server: McpServerConfig) = use(owner, server) { it.client.listResources(it.config) }
        override suspend fun readResource(server: McpServerConfig, uri: String) = use(owner, server) {
            require(it.client.listResources(it.config).any { resource -> resource.uri == uri }) { "MCP 资源未在当前目录发现" }
            it.client.readResource(it.config, uri)
        }
        override suspend fun listPrompts(server: McpServerConfig) = use(owner, server) { it.client.listPrompts(it.config) }
        override suspend fun getPrompt(server: McpServerConfig, name: String, arguments: Map<String, String>) = use(owner, server) {
            val prompt = it.client.listPrompts(it.config).firstOrNull { prompt -> prompt.name == name } ?: error("MCP Prompt 未在当前目录发现")
            require(arguments.keys.all { key -> prompt.arguments.any { argument -> argument.name == key } } &&
                prompt.arguments.filter { argument -> argument.required }.all { argument -> argument.name in arguments }) { "MCP Prompt 参数与当前目录不符" }
            it.client.getPrompt(it.config, name, arguments)
        }
        override fun close() = disconnect(owner)
    }
}
