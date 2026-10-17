package com.longdev.xiaoling.agent

import android.content.Context
import com.longdev.xiaoling.data.ApiKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class McpTransportKind(val wireName: String) {
    STREAMABLE_HTTP("streamable_http"),
    SSE("sse"),
    STDIO("stdio"),
    ;

    companion object {
        fun parse(raw: String): McpTransportKind = entries.firstOrNull { it.wireName == raw.trim().lowercase() }
            ?: throw IllegalArgumentException("未知 MCP transport：$raw")
    }
}

data class McpTransportSpec(
    val kind: McpTransportKind,
    val command: String? = null,
    val args: List<String> = emptyList(),
)

/**
 * long: 先把远程、SSE 和 stdio 的声明分类，再由执行层决定是否支持，避免把 command 字段
 * 悄悄拼进远程 URL 或在尚未建立权限边界时启动本地进程。
 */
object McpTransportConfigParser {
    private const val MAX_COMMAND_CHARS = 512
    private const val MAX_ARGUMENTS = 64
    private const val MAX_ARGUMENT_CHARS = 1_024

    fun parse(json: JSONObject): McpTransportSpec {
        val transportValue = json.optString("transport").trim()
        val typeValue = json.optString("type").trim()
        if (transportValue.isNotBlank() && typeValue.isNotBlank()) {
            require(transportValue.equals(typeValue, ignoreCase = true)) {
                "MCP transport 与 type 不一致"
            }
        }
        val rawKind = transportValue.ifBlank { typeValue }
        val kind = if (rawKind.isBlank()) McpTransportKind.STREAMABLE_HTTP else McpTransportKind.parse(rawKind)
        val command = if (json.has("command")) json.optString("command") else null
        val args = json.optJSONArray("args")?.let(::parseArgs) ?: emptyList()
        val spec = McpTransportSpec(kind = kind, command = command, args = args)
        validate(spec)
        return spec
    }

    fun fromConfig(config: McpServerConfig): McpTransportSpec = McpTransportSpec(
        kind = config.transport,
        command = config.command,
        args = config.args,
    ).also(::validate)

    fun writeTo(json: JSONObject, spec: McpTransportSpec) {
        validate(spec)
        json.put("transport", spec.kind.wireName)
        if (spec.kind == McpTransportKind.STDIO) {
            json.put("command", spec.command)
            json.put("args", JSONArray(spec.args))
        }
    }

    fun validate(spec: McpTransportSpec) {
        when (spec.kind) {
            McpTransportKind.STREAMABLE_HTTP,
            McpTransportKind.SSE,
            -> require(spec.command == null && spec.args.isEmpty()) {
                "MCP 远程 transport 不能配置 command 或 args"
            }

            McpTransportKind.STDIO -> {
                val command = spec.command?.trim()
                require(!command.isNullOrBlank()) { "MCP stdio 必须配置 command" }
                require(command.length <= MAX_COMMAND_CHARS) { "MCP stdio command 过长" }
                require(command.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                    "MCP stdio command 包含非法控制字符"
                }
                require(spec.args.size <= MAX_ARGUMENTS) { "MCP stdio args 过多" }
                require(spec.args.all { it.length <= MAX_ARGUMENT_CHARS }) { "MCP stdio 参数过长" }
                require(spec.args.none { arg -> arg.any { it == '\u0000' || it == '\r' || it == '\n' } }) {
                    "MCP stdio 参数包含非法控制字符"
                }
            }
        }
    }

    private fun parseArgs(array: JSONArray): List<String> {
        require(array.length() <= MAX_ARGUMENTS) { "MCP stdio args 过多" }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.opt(index)
                require(value is String) { "MCP stdio args 必须是字符串" }
                add(value)
            }
        }
    }
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val bearerToken: String = "",
    val enabled: Boolean = true,
    /**
     * null 表示旧配置尚未建立工具白名单，保持“已发现工具均可调用”的兼容行为；
     * 非 null 表示用户已经保存过明确的工具级允许集合，空集合代表全部停用。
     */
    val enabledToolNames: Set<String>? = null,
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    val command: String? = null,
    val args: List<String> = emptyList(),
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
)

data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String?,
)

data class McpResourceContent(
    val uri: String,
    val mimeType: String?,
    val text: String?,
    val blobBase64: String?,
)

data class McpPromptArgument(
    val name: String,
    val description: String,
    val required: Boolean,
)

data class McpPromptDescriptor(
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>,
)

data class McpPromptMessage(
    val role: String,
    val contentJson: String,
)

data class McpPromptResult(
    val description: String?,
    val messages: List<McpPromptMessage>,
)

interface McpServerStore {
    fun list(): List<McpServerConfig>
    fun get(id: String): McpServerConfig?
    fun upsert(config: McpServerConfig): McpServerConfig
    fun delete(id: String): Boolean
}

object DisabledMcpServerStore : McpServerStore {
    override fun list(): List<McpServerConfig> = emptyList()
    override fun get(id: String): McpServerConfig? = null
    override fun upsert(config: McpServerConfig): McpServerConfig = error("MCP Server 存储尚未初始化")
    override fun delete(id: String): Boolean = false
}

class AndroidMcpServerStore(
    context: Context,
    private val cipher: ApiKeyCipher = ApiKeyCipher(),
) : McpServerStore {
    private val preferences = context.applicationContext.getSharedPreferences("xiaoling_mcp", Context.MODE_PRIVATE)

    @Synchronized override fun list(): List<McpServerConfig> = decodeList(preferences.getString(KEY_SERVERS, "[]").orEmpty())

    override fun get(id: String): McpServerConfig? = list().firstOrNull { it.id == id }

    @Synchronized override fun upsert(config: McpServerConfig): McpServerConfig {
        McpServerPolicy.validate(config)
        val next = list().filterNot { it.id == config.id } + config
        check(preferences.edit().putString(KEY_SERVERS, encodeList(next)).commit()) { "MCP 配置保存失败" }
        return config
    }

    @Synchronized override fun delete(id: String): Boolean {
        val next = list().filterNot { it.id == id }
        if (next.size == list().size) return false
        check(preferences.edit().putString(KEY_SERVERS, encodeList(next)).commit()) { "MCP 配置删除失败" }
        return true
    }

    private fun encodeList(configs: List<McpServerConfig>): String = JSONArray().apply {
        configs.sortedBy { it.id }.forEach { config ->
            val encrypted = cipher.encrypt(config.bearerToken)
            put(JSONObject()
                .put("id", config.id)
                .put("name", config.name)
                .put("url", config.url)
                .put("token_iv", encrypted.iv)
                .put("token_ciphertext", encrypted.ciphertext)
                .put("enabled", config.enabled)
                .apply {
                    McpTransportConfigParser.writeTo(this, McpTransportConfigParser.fromConfig(config))
                    config.enabledToolNames?.let { names ->
                        put("enabled_tools", JSONArray(names.sorted()))
                    }
                })
        }
    }.toString()

    private fun decodeList(raw: String): List<McpServerConfig> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val transport = McpTransportConfigParser.parse(item)
                add(McpServerConfig(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    url = item.getString("url"),
                    bearerToken = cipher.decrypt(item.optString("token_iv"), item.optString("token_ciphertext")),
                    enabled = item.optBoolean("enabled", true),
                    transport = transport.kind,
                    command = transport.command,
                    args = transport.args,
                    enabledToolNames = item.optJSONArray("enabled_tools")?.let { tools ->
                        buildSet {
                            for (toolIndex in 0 until tools.length()) {
                                tools.optString(toolIndex).trim().takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    },
                ))
            }
        }
    }.getOrDefault(emptyList())

    private companion object { const val KEY_SERVERS = "servers" }
}

object McpServerPolicy {
    private val toolNamePattern = Regex("[A-Za-z0-9._:-]{1,120}")

    fun validate(config: McpServerConfig) {
        require(config.id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}"))) { "MCP server id 无效" }
        require(config.name.trim().length in 1..100) { "MCP server 名称无效" }
        McpTransportConfigParser.fromConfig(config)
        when (config.transport) {
            McpTransportKind.STREAMABLE_HTTP,
            McpTransportKind.SSE,
            -> validateRemoteUrl(config.url)

            McpTransportKind.STDIO -> require(config.url.isBlank()) {
                "MCP stdio 不接受远程 URL"
            }
        }
        require(config.bearerToken.length <= 4_096) { "MCP Token 过长" }
        require(config.bearerToken.none(Char::isISOControl)) { "MCP Token 包含非法控制字符" }
        config.enabledToolNames?.let(::validateToolNames)
    }

    private fun validateRemoteUrl(raw: String) {
        require(raw == raw.trim() && raw.none(Char::isISOControl)) { "MCP 地址包含非法空白或控制字符" }
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("MCP 地址无效")
        require(url.scheme == "https" || isLoopbackHost(url.host)) {
            "MCP 默认要求 HTTPS；本机调试只允许 loopback"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "MCP 地址不能包含账号、密码或 fragment"
        }
    }

    fun validateToolNames(names: Set<String>) {
        require(names.size <= 1_000) { "MCP 工具白名单过大" }
        require(names.all { toolNamePattern.matches(it) }) { "MCP 工具白名单包含无效名称" }
    }

    fun validateResolvedHost(url: okhttp3.HttpUrl) {
        val addresses = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrElse {
            throw IllegalArgumentException("MCP 地址无法解析")
        }
        require(addresses.isNotEmpty()) { "MCP 地址无法解析" }
        if (url.scheme == "https") {
            require(addresses.none(::isPrivateAddress)) { "MCP HTTPS 地址不能指向本机或私有网络" }
        } else {
            require(addresses.all(InetAddress::isLoopbackAddress)) { "MCP 明文地址只允许 loopback" }
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.hostAddress == "0.0.0.0" || address.hostAddress == "::"
    }
}

/**
 * long: 工具级白名单的兼容语义集中在这里，设置页、调用路径和测试共用同一套规则，
 * 避免某个入口把“旧配置默认允许”误实现成“空集合全部拒绝”。
 */
object McpToolAccessPolicy {
    fun isEnabled(server: McpServerConfig, toolName: String): Boolean =
        server.enabledToolNames?.contains(toolName) ?: true

    fun updatedEnabledToolNames(
        server: McpServerConfig,
        discoveredToolNames: Set<String>,
        toolName: String,
        enabled: Boolean,
    ): Set<String> {
        require(toolName in discoveredToolNames) { "工具未在最近一次发现结果中：$toolName" }
        val current = server.enabledToolNames ?: discoveredToolNames
        val next = if (enabled) current + toolName else current - toolName
        McpServerPolicy.validateToolNames(next)
        return next
    }
}

class StreamableHttpMcpClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) : McpClient {
    // long: 即使注入测试或定制客户端，也不允许重定向和网络层自动重发已提交的工具调用。
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val toolCache = ConcurrentHashMap<String, CachedTools>()
    private val sseConnections = ConcurrentHashMap<String, LegacySseMcpConnection>()
    private val lifecycleLock = Any()
    private val activeCalls = LinkedHashSet<Call>()
    private var lifecycleGeneration = 0L
    private var retired = false

    override fun retire() {
        synchronized(lifecycleLock) { retired = true }
        close()
    }

    /**
     * long: 传输实例只属于注册表中的一个 owner/server；先撤销代次再取消请求，旧响应不能复活连接或目录。
     */
    override fun close() {
        val (calls, streams) = synchronized(lifecycleLock) {
            lifecycleGeneration += 1
            val calls = activeCalls.toList().also { activeCalls.clear() }
            val streams = sseConnections.values.toList().also { sseConnections.clear() }
            sessions.clear()
            sessionLocks.clear()
            toolCache.clear()
            calls to streams
        }
        calls.forEach(Call::cancel)
        streams.forEach(LegacySseMcpConnection::close)
    }

    fun reset() = close()

    override suspend fun connect(server: McpServerConfig): McpConnectionInfo = withContext(Dispatchers.IO) {
        ensureInitialized(server)
        val session = sessions[sessionKey(server)] ?: error("MCP 会话已断开")
        McpConnectionInfo(session.protocolVersion, session.capabilities.mapTo(linkedSetOf()) { it.protocolName })
    }

    override fun isConnected(server: McpServerConfig): Boolean = synchronized(lifecycleLock) {
        val key = sessionKey(server)
        !retired && sessions.containsKey(key) && (server.transport != McpTransportKind.SSE || sseConnections[key]?.isOpen == true)
    }

    override suspend fun listTools(server: McpServerConfig): List<McpToolDescriptor> = withContext(Dispatchers.IO) {
        // long: 工具目录缓存不能绕过最新的地址解析检查；DNS 变化后即使目录仍在 TTL 内，也必须重新确认目标不是私网或回环地址。
        McpServerPolicy.validate(server)
        requireRemoteTransport(server)
        val serverUrl = server.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("MCP 地址无效")
        McpServerPolicy.validateResolvedHost(serverUrl)
        ensureInitialized(server)
        val generation = currentLifecycleGeneration()
        val key = sessionKey(server)
        val cached = toolCache[key]
        if (cached != null && cached.expiresAtMillis > System.currentTimeMillis()) return@withContext cached.tools
        val descriptors = linkedMapOf<String, McpToolDescriptor>()
        var cursor: String? = null
        var pageCount = 0
        while (true) {
            require(++pageCount <= McpPolicy.MAX_TOOL_LIST_PAGES) { "MCP 工具目录分页超过限制" }
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val page = request(server, "tools/list", params) { result -> parseToolPage(result) }
            page.tools.forEach { descriptor -> descriptors[descriptor.name] = descriptor }
            require(descriptors.size <= McpPolicy.MAX_CATALOG_ITEMS) { "MCP 工具目录超过限制" }
            cursor = page.nextCursor?.takeIf(String::isNotBlank)
            if (cursor == null) break
        }
        val result = descriptors.values.toList()
        currentCoroutineContext().ensureActive()
        synchronized(lifecycleLock) {
            check(generation == lifecycleGeneration && isConnected(server)) { "MCP 连接已变化" }
            toolCache[key] = CachedTools(result, System.currentTimeMillis() + McpPolicy.TOOL_CACHE_TTL_MILLIS)
        }
        result
    }

    override suspend fun callTool(
        server: McpServerConfig,
        name: String,
        arguments: JSONObject,
        knownTools: List<McpToolDescriptor>?,
    ): String {
        val descriptor = (knownTools ?: listTools(server)).firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("MCP 工具未在最近目录中发现：$name")
        McpJsonSchemaValidator.validate(descriptor.inputSchemaJson, arguments)
        return request(server, "tools/call", JSONObject().put("name", name).put("arguments", arguments)) { result ->
            result.toString().take(McpPolicy.MAX_RESULT_CHARS)
        }
    }

    override suspend fun listResources(server: McpServerConfig): List<McpResourceDescriptor> {
        requireCapability(server, McpServerCapability.RESOURCES)
        val resources = listPages(server, "resources/list", ::parseResourcePage)
        require(resources.map { it.uri }.distinct().size == resources.size) { "MCP 资源目录包含重复 URI" }
        return resources
    }

    override suspend fun readResource(server: McpServerConfig, uri: String): List<McpResourceContent> {
        requireCapability(server, McpServerCapability.RESOURCES)
        McpPolicy.validateResourceUri(uri)
        return request(server, "resources/read", JSONObject().put("uri", uri)) { result ->
            val contents = result.optJSONArray("contents") ?: JSONArray()
            require(contents.length() <= McpPolicy.MAX_CATALOG_ITEMS) { "MCP 资源内容数量超过限制" }
            buildList(contents.length()) {
                for (index in 0 until contents.length()) {
                    val item = contents.getJSONObject(index)
                    val itemUri = item.optString("uri").trim()
                    McpPolicy.validateResourceUri(itemUri)
                    val mimeType = item.optString("mimeType").takeIf(String::isNotBlank)
                    val text = item.opt("text")
                    val blob = item.opt("blob")
                    require((text == null || text === JSONObject.NULL) xor (blob == null || blob === JSONObject.NULL)) {
                        "MCP 资源内容必须且只能包含 text 或 blob"
                    }
                    val textValue = text?.takeUnless { it === JSONObject.NULL }?.let {
                        require(it is String) { "MCP 资源 text 必须是字符串" }
                        require(it.length <= McpPolicy.MAX_RESOURCE_CONTENT_CHARS) { "MCP 资源 text 超过大小限制" }
                        it
                    }
                    val blobValue = blob?.takeUnless { it === JSONObject.NULL }?.let {
                        require(it is String) { "MCP 资源 blob 必须是 Base64 字符串" }
                        require(it.length <= McpPolicy.MAX_RESOURCE_CONTENT_CHARS) { "MCP 资源 blob 超过大小限制" }
                        runCatching { java.util.Base64.getDecoder().decode(it) }
                            .getOrElse { throw IllegalArgumentException("MCP 资源 blob 不是有效 Base64", it) }
                        it
                    }
                    add(McpResourceContent(itemUri, mimeType, textValue, blobValue))
                }
            }
        }
    }

    override suspend fun listPrompts(server: McpServerConfig): List<McpPromptDescriptor> {
        requireCapability(server, McpServerCapability.PROMPTS)
        val prompts = listPages(server, "prompts/list", ::parsePromptPage)
        require(prompts.map { it.name }.distinct().size == prompts.size) { "MCP Prompt 目录包含重复名称" }
        return prompts
    }

    override suspend fun getPrompt(
        server: McpServerConfig,
        name: String,
        arguments: Map<String, String>,
    ): McpPromptResult {
        requireCapability(server, McpServerCapability.PROMPTS)
        McpPolicy.validatePromptName(name)
        require(arguments.size <= McpPolicy.MAX_PROMPT_ARGUMENTS) { "MCP Prompt 参数数量超过限制" }
        val params = JSONObject().put("name", name)
        if (arguments.isNotEmpty()) {
            params.put("arguments", JSONObject().apply {
                arguments.toSortedMap().forEach { (key, value) ->
                    McpPolicy.validatePromptArgument(key, value)
                    put(key, value)
                }
            })
        }
        return request(server, "prompts/get", params) { result ->
            val messages = result.optJSONArray("messages") ?: JSONArray()
            require(messages.length() <= McpPolicy.MAX_CATALOG_ITEMS) { "MCP Prompt 消息数量超过限制" }
            McpPromptResult(
                description = result.optString("description").takeIf(String::isNotBlank),
                messages = buildList(messages.length()) {
                    for (index in 0 until messages.length()) {
                        val item = messages.getJSONObject(index)
                        val role = item.optString("role").trim()
                        require(role == "user" || role == "assistant") { "MCP Prompt role 无效" }
                        val content = item.opt("content")
                        require(content != null && content !== JSONObject.NULL) { "MCP Prompt 消息缺少 content" }
                        val contentJson = content.toString()
                        require(contentJson.toByteArray(Charsets.UTF_8).size <= McpPolicy.MAX_PROMPT_CONTENT_BYTES) {
                            "MCP Prompt content 超过大小限制"
                        }
                        add(McpPromptMessage(role, contentJson))
                    }
                },
            )
        }
    }

    private suspend fun <T> listPages(
        server: McpServerConfig,
        method: String,
        parse: (JSONObject) -> McpPage<T>,
    ): List<T> {
        val result = ArrayList<T>()
        var cursor: String? = null
        var pageCount = 0
        while (true) {
            require(++pageCount <= McpPolicy.MAX_CATALOG_PAGES) { "MCP $method 分页超过限制" }
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val page = request(server, method, params, parse)
            result += page.items
            require(result.size <= McpPolicy.MAX_CATALOG_ITEMS) { "MCP $method 返回条目超过限制" }
            cursor = page.nextCursor?.takeIf(String::isNotBlank)
            if (cursor == null) return result
        }
    }

    private suspend fun requireCapability(server: McpServerConfig, capability: McpServerCapability) {
        ensureInitialized(server)
        val session = sessions[sessionKey(server)]
        require(session?.capabilities?.contains(capability) == true) {
            "MCP Server 未声明 ${capability.protocolName} 能力"
        }
    }

    private fun parseToolPage(result: JSONObject): McpToolPage {
        val tools = result.optJSONArray("tools") ?: JSONArray()
        val descriptors = buildList {
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                val name = tool.optString("name").trim()
                if (name.isBlank()) continue
                val inputSchema = tool.opt("inputSchema")
                add(McpToolDescriptor(
                    name = name,
                    description = tool.optString("description").take(2_000),
                    // long: 缺少 schema 时按空 object 兼容；服务端显式返回数组或标量时保留原文，让调用前校验失败而不是静默放行。
                    inputSchemaJson = when {
                        inputSchema == null || inputSchema === JSONObject.NULL -> "{\"type\":\"object\"}"
                        else -> inputSchema.toString()
                    },
                    readOnlyHint = tool.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true,
                    destructiveHint = tool.optJSONObject("annotations")?.optBoolean("destructiveHint", false) == true,
                ))
            }
        }
        return McpToolPage(tools = descriptors, nextCursor = result.optString("nextCursor").takeIf(String::isNotBlank))
    }

    private fun parseResourcePage(result: JSONObject): McpPage<McpResourceDescriptor> {
        val resources = result.optJSONArray("resources") ?: JSONArray()
        return McpPage(
            items = buildList(resources.length()) {
                for (index in 0 until resources.length()) {
                    val resource = resources.getJSONObject(index)
                    val uri = resource.optString("uri").trim()
                    McpPolicy.validateResourceUri(uri)
                    val name = resource.optString("name").trim()
                    require(name.isNotBlank() && name.length <= McpPolicy.MAX_PROMPT_NAME_CHARS) {
                        "MCP 资源名称无效"
                    }
                    add(McpResourceDescriptor(
                        uri = uri,
                        name = name,
                        description = resource.optString("description").take(McpPolicy.MAX_DESCRIPTION_CHARS),
                        mimeType = resource.optString("mimeType").takeIf(String::isNotBlank),
                    ))
                }
            },
            nextCursor = result.optString("nextCursor").takeIf(String::isNotBlank),
        )
    }

    private fun parsePromptPage(result: JSONObject): McpPage<McpPromptDescriptor> {
        val prompts = result.optJSONArray("prompts") ?: JSONArray()
        return McpPage(
            items = buildList(prompts.length()) {
                for (index in 0 until prompts.length()) {
                    val prompt = prompts.getJSONObject(index)
                    val name = prompt.optString("name").trim()
                    McpPolicy.validatePromptName(name)
                    val arguments = prompt.optJSONArray("arguments") ?: JSONArray()
                    require(arguments.length() <= McpPolicy.MAX_PROMPT_ARGUMENTS) { "MCP Prompt 参数数量超过限制" }
                    val argumentNames = linkedSetOf<String>()
                    add(McpPromptDescriptor(
                        name = name,
                        description = prompt.optString("description").take(McpPolicy.MAX_DESCRIPTION_CHARS),
                        arguments = buildList(arguments.length()) {
                            for (argumentIndex in 0 until arguments.length()) {
                                val argument = arguments.getJSONObject(argumentIndex)
                                val argumentName = argument.optString("name").trim()
                                McpPolicy.validatePromptArgument(argumentName, "")
                                require(argumentNames.add(argumentName)) { "MCP Prompt 参数名称重复：$argumentName" }
                                add(McpPromptArgument(
                                    name = argumentName,
                                    description = argument.optString("description").take(McpPolicy.MAX_DESCRIPTION_CHARS),
                                    required = argument.optBoolean("required", false),
                                ))
                            }
                        },
                    ))
                }
            },
            nextCursor = result.optString("nextCursor").takeIf(String::isNotBlank),
        )
    }

    private suspend fun <T> request(
        server: McpServerConfig,
        method: String,
        params: JSONObject,
        parse: (JSONObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        McpServerPolicy.validate(server)
        val generation = currentLifecycleGeneration()
        ensureInitialized(server)
        val response = postRpc(server, method, params, includeId = true, expectedGeneration = generation)
        response.payload.optJSONObject("error")?.let { error ->
            throw IllegalStateException("MCP ${method} 失败：${error.optString("message", error.toString())}")
        }
        parse(response.payload.optJSONObject("result") ?: JSONObject())
    }

    private suspend fun ensureInitialized(server: McpServerConfig) {
        McpServerPolicy.validate(server)
        requireRemoteTransport(server)
        val key = sessionKey(server)
        synchronized(lifecycleLock) { check(!retired) { "MCP 传输实例已撤销" }; if (sessions.containsKey(key)) return }
        val generation = currentLifecycleGeneration()
        val lock = sessionLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            synchronized(lifecycleLock) { check(!retired && generation == lifecycleGeneration) { "MCP 连接已变化或撤销" } }
            if (sessions.containsKey(key)) return
            try {
                if (server.transport == McpTransportKind.SSE) {
                    lateinit var stream: LegacySseMcpConnection
                    stream = LegacySseMcpConnection(server, client) {
                        synchronized(lifecycleLock) {
                            if (sseConnections[key] === stream) invalidate(server)
                        }
                    }
                    synchronized(lifecycleLock) {
                        check(!retired && generation == lifecycleGeneration) { "MCP 连接已变化或撤销" }
                        sseConnections[key] = stream
                    }
                    try { stream.start() } catch (error: Exception) { stream.close(); throw error }
                }
                val response = postRpc(
                    server = server,
                    method = "initialize",
                    params = JSONObject()
                        .put("protocolVersion", if (server.transport == McpTransportKind.SSE) McpPolicy.LEGACY_PROTOCOL_VERSION else McpPolicy.PROTOCOL_VERSION)
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "XiaoLing").put("version", "0.1")),
                    includeId = true,
                    protocolVersion = null,
                    expectedGeneration = generation,
                )
                response.payload.optJSONObject("error")?.let { error ->
                    throw IllegalStateException("MCP 初始化失败：${error.optString("message", error.toString())}")
                }
                val result = response.payload.optJSONObject("result") ?: throw IllegalStateException("MCP 初始化缺少 result")
                val negotiatedVersion = result.optString("protocolVersion").ifBlank { McpPolicy.PROTOCOL_VERSION }
                require(negotiatedVersion in setOf(McpPolicy.PROTOCOL_VERSION, McpPolicy.LEGACY_PROTOCOL_VERSION)) { "MCP 协议版本不支持" }
                val capabilities = buildSet {
                    val capabilityObject = result.optJSONObject("capabilities") ?: JSONObject()
                    if (capabilityObject.has("tools")) add(McpServerCapability.TOOLS)
                    if (capabilityObject.has("resources")) add(McpServerCapability.RESOURCES)
                    if (capabilityObject.has("prompts")) add(McpServerCapability.PROMPTS)
                }
                postRpc(
                    server = server,
                    method = "notifications/initialized",
                    params = JSONObject(),
                    includeId = false,
                    sessionId = response.sessionId,
                    protocolVersion = negotiatedVersion,
                    allowEmptyResponse = true,
                    expectedGeneration = generation,
                )
                currentCoroutineContext().ensureActive()
                synchronized(lifecycleLock) {
                    check(!retired && generation == lifecycleGeneration && (server.transport != McpTransportKind.SSE || sseConnections[key]?.isOpen == true)) { "MCP 连接已关闭" }
                    sessions[key] = McpSession(response.sessionId, negotiatedVersion, capabilities)
                }
            } catch (error: Exception) {
                invalidate(server, generation)
                throw error
            }
        }
    }

    private fun requireRemoteTransport(server: McpServerConfig) {
        require(server.transport != McpTransportKind.STDIO) {
            "MCP stdio 等待进程沙箱和生命周期机制"
        }
    }

    private fun sessionKey(server: McpServerConfig): String =
        mcpServerBinding(server)

    private fun invalidate(server: McpServerConfig, expectedGeneration: Long? = null) {
        val key = sessionKey(server)
        synchronized(lifecycleLock) {
            if (expectedGeneration != null && expectedGeneration != lifecycleGeneration) return
            sessions.remove(key)
            toolCache.remove(key)
            sseConnections.remove(key)?.close()
        }
    }

    private suspend fun postRpc(
        server: McpServerConfig,
        method: String,
        params: JSONObject,
        includeId: Boolean,
        sessionId: String? = sessions[sessionKey(server)]?.sessionId,
        protocolVersion: String? = sessions[sessionKey(server)]?.protocolVersion,
        allowEmptyResponse: Boolean = false,
        expectedGeneration: Long = currentLifecycleGeneration(),
    ): RpcResponse {
        McpServerPolicy.validate(server)
        requireRemoteTransport(server)
        val requestId = UUID.randomUUID().toString().takeIf { includeId }
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        if (requestId != null) payload.put("id", requestId)
        if (server.transport == McpTransportKind.SSE) {
            val stream = synchronized(lifecycleLock) {
                check(!retired && expectedGeneration == lifecycleGeneration) { "MCP 连接已变化或撤销" }
                sseConnections[sessionKey(server)] ?: throw IOException("MCP SSE 会话未连接")
            }
            val reply = stream.request(payload)
            synchronized(lifecycleLock) { check(expectedGeneration == lifecycleGeneration && sseConnections[sessionKey(server)] === stream) { "MCP 连接已变化" } }
            return RpcResponse(reply, null)
        }
        val url = server.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("MCP 地址无效")
        McpServerPolicy.validateResolvedHost(url)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                if (protocolVersion != null) header("Mcp-Protocol-Version", protocolVersion)
                if (sessionId != null) header("Mcp-Session-Id", sessionId)
                if (server.bearerToken.isNotBlank()) header("Authorization", "Bearer ${server.bearerToken}")
            }
            .post(mcpOneShotBody(payload.toString()))
            .build()
        val call = client.newCall(request)
        val generation = synchronized(lifecycleLock) {
            check(!retired && expectedGeneration == lifecycleGeneration) { "MCP 连接已变化或撤销" }
            activeCalls += call
            lifecycleGeneration
        }
        return try {
            executeRpcCall(call, requestId, sessionId, allowEmptyResponse, generation)
        } catch (error: Exception) {
            invalidate(server, generation)
            currentCoroutineContext().ensureActive()
            if (error is IOException) throw IOException("MCP 请求失败：${error.message ?: "网络错误"}", error)
            throw error
        } finally {
            synchronized(lifecycleLock) { activeCalls.remove(call) }
        }
    }

    private suspend fun executeRpcCall(call: Call, requestId: String?, sessionId: String?, allowEmpty: Boolean, generation: Long): RpcResponse = suspendCancellableCoroutine { continuation ->
        // long: 取消绑定覆盖整个 body/SSE 读取阶段，不能在响应头到达后提前解绑，留下无法中断的半开正文。
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (response.code == 404 && sessionId != null) throw IOException("MCP session 已过期，请重新连接")
                        require(response.isSuccessful) { "MCP 请求失败：HTTP ${response.code}" }
                        val returnedId = response.header("Mcp-Session-Id") ?: sessionId
                        require(sessionId == null || returnedId == sessionId) { "MCP session 标识已变化" }
                        val source = response.body?.source()
                        val json = if (response.header("Content-Type").orEmpty().substringBefore(';').trim() == "text/event-stream") {
                            val reader = McpSseEventReader(source ?: throw IOException("MCP SSE 正文为空"), McpPolicy.MAX_RESPONSE_BYTES.toLong())
                            var matched: JSONObject? = null
                            while (matched == null) {
                                val event = reader.next() ?: throw IOException("MCP SSE 在响应前断开")
                                if (event.type == "message" && event.data.isNotBlank()) {
                                    val value = JSONObject(event.data)
                                    if (requestId == null || value.opt("id") == requestId) matched = value
                                }
                            }
                            matched
                        } else {
                            val body = source?.let { readLimited(it, McpPolicy.MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                            require(body.size <= McpPolicy.MAX_RESPONSE_BYTES) { "MCP 响应超过大小限制" }
                            val text = decodeMcpUtf8(body).trim()
                            if (text.isBlank() && allowEmpty) JSONObject() else parseRpcBody(text, requestId)
                        }
                        if (requestId != null) validateMcpRpcResponse(json, requestId)
                        synchronized(lifecycleLock) { check(generation == lifecycleGeneration) { "MCP 连接已关闭" } }
                        RpcResponse(json, returnedId)
                    }
                    continuation.resumeWith(Result.success(result))
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        })
    }

    private fun currentLifecycleGeneration(): Long = synchronized(lifecycleLock) {
        check(!retired) { "MCP 传输实例已撤销" }
        lifecycleGeneration
    }

    internal fun parseRpcBody(body: String, expectedId: String? = null): JSONObject {
        val normalized = body.trim()
        if (normalized.startsWith("{")) return JSONObject(normalized)
        val events = normalized.split(Regex("\\n\\s*\\n"))
            .mapNotNull { event ->
                val data = event.lineSequence()
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trimStart() }
                    .trim()
                data.takeIf(String::isNotBlank)?.let(::JSONObject)
            }
        return events.firstOrNull { expectedId == null || it.optString("id") == expectedId }
            ?: throw IllegalArgumentException("MCP 返回不是匹配的 JSON-RPC 或 SSE data")
    }

    private data class RpcResponse(val payload: JSONObject, val sessionId: String?)
    private data class McpSession(
        val sessionId: String?,
        val protocolVersion: String,
        val capabilities: Set<McpServerCapability>,
    )
    private data class McpToolPage(val tools: List<McpToolDescriptor>, val nextCursor: String?)
    private data class McpPage<T>(val items: List<T>, val nextCursor: String?)
    private data class CachedTools(val tools: List<McpToolDescriptor>, val expiresAtMillis: Long)

    private fun readLimited(source: BufferedSource, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() <= maxBytes) {
            val count = source.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

private enum class McpServerCapability(val protocolName: String) {
    TOOLS("tools"),
    RESOURCES("resources"),
    PROMPTS("prompts"),
}

object McpPolicy {
    // long: 与当前主流 Streamable HTTP MCP Server 的握手版本保持一致，避免把较新的版本号误当成服务器能力声明。
    const val PROTOCOL_VERSION = "2025-03-26"
    const val LEGACY_PROTOCOL_VERSION = "2024-11-05"
    const val MAX_RESULT_CHARS = 20_000
    const val MAX_ARGUMENT_BYTES = 32 * 1024
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_TOOL_LIST_PAGES = 20
    const val MAX_CATALOG_PAGES = 20
    const val MAX_CATALOG_ITEMS = 1_000
    const val MAX_RESOURCE_CONTENT_CHARS = 200_000
    const val MAX_PROMPT_CONTENT_BYTES = 128 * 1024
    const val MAX_PROMPT_ARGUMENTS = 100
    const val MAX_PROMPT_NAME_CHARS = 120
    const val MAX_PROMPT_ARGUMENT_CHARS = 4_096
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val TOOL_CACHE_TTL_MILLIS = 5 * 60 * 1_000L
    const val RUN_CATALOG_TTL_MILLIS = 5 * 60 * 1_000L

    fun validateResourceUri(uri: String) {
        require(uri.isNotBlank() && uri.length <= 2_048) { "MCP 资源 URI 无效" }
        require(uri.none { it == '\r' || it == '\n' }) { "MCP 资源 URI 不能包含换行" }
    }

    fun validatePromptName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_PROMPT_NAME_CHARS) { "MCP Prompt 名称无效" }
        require(name.none { it == '\r' || it == '\n' }) { "MCP Prompt 名称不能包含换行" }
    }

    fun validatePromptArgument(name: String, value: String) {
        require(name.isNotBlank() && name.length <= MAX_PROMPT_NAME_CHARS) { "MCP Prompt 参数名无效" }
        require(name.none { it == '\r' || it == '\n' }) { "MCP Prompt 参数名不能包含换行" }
        require(value.length <= MAX_PROMPT_ARGUMENT_CHARS) { "MCP Prompt 参数过长" }
    }
}

/**
 * long: MCP Server 提供的 schema 只约束远程工具参数，不会授予本地工具权限；调用前先拒绝缺字段、错误类型、越界值和未知字段，避免把模型生成的任意 JSON 原样转发到远端。
 */
internal object McpJsonSchemaValidator {
    private const val MAX_DEPTH = 8
    private const val MAX_PROPERTIES = 100
    private const val MAX_ARRAY_ITEMS = 100

    fun validate(schemaJson: String, value: JSONObject) {
        val schema = runCatching { JSONObject(schemaJson) }
            .getOrElse { throw IllegalArgumentException("MCP 工具 inputSchema 不是有效 JSON object", it) }
        validateValue(schema, value, "arguments", 0)
    }

    private fun validateValue(schema: JSONObject, value: Any?, path: String, depth: Int) {
        require(depth <= MAX_DEPTH) { "MCP 参数 schema 嵌套过深：$path" }
        if (schema.has("enum")) {
            val enumValues = schema.optJSONArray("enum") ?: throw IllegalArgumentException("MCP schema enum 无效：$path")
            require((0 until enumValues.length()).any { jsonEquals(enumValues.get(it), value) }) {
                "MCP 参数不在允许枚举中：$path"
            }
        }
        if (schema.has("allOf")) {
            val branches = schema.optJSONArray("allOf") ?: throw IllegalArgumentException("MCP schema allOf 无效：$path")
            for (index in 0 until branches.length()) {
                validateValue(branches.getJSONObject(index), value, path, depth + 1)
            }
        }
        if (schema.has("anyOf") || schema.has("oneOf")) {
            val key = if (schema.has("anyOf")) "anyOf" else "oneOf"
            val branches = schema.optJSONArray(key) ?: throw IllegalArgumentException("MCP schema $key 无效：$path")
            val matches = (0 until branches.length()).count { index ->
                runCatching { validateValue(branches.getJSONObject(index), value, path, depth + 1) }.isSuccess
            }
            require(if (key == "oneOf") matches == 1 else matches >= 1) {
                "MCP 参数不符合 schema $key：$path"
            }
            return
        }

        when (schema.optString("type").takeIf(String::isNotBlank)) {
            "object" -> validateObject(schema, value, path, depth)
            "array" -> validateArray(schema, value, path, depth)
            "string" -> validateString(schema, value, path)
            "number" -> validateNumber(schema, value, path, integer = false)
            "integer" -> validateNumber(schema, value, path, integer = true)
            "boolean" -> require(value is Boolean) { "MCP 参数必须是 boolean：$path" }
            "null" -> require(value == null || value === JSONObject.NULL) { "MCP 参数必须是 null：$path" }
            null -> Unit
            else -> throw IllegalArgumentException("MCP schema type 无效：$path")
        }
    }

    private fun validateObject(schema: JSONObject, value: Any?, path: String, depth: Int) {
        require(value is JSONObject) { "MCP 参数必须是 object：$path" }
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val name = required.getString(index)
                require(value.has(name)) {
                    "MCP 参数缺少必填字段：$path.$name"
                }
            }
        }
        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            require(properties.length() <= MAX_PROPERTIES) { "MCP schema 属性过多：$path" }
            val names = properties.keys().asSequence().toSet()
            val keys = value.keys().asSequence().toList()
            if (schema.has("additionalProperties") && schema.optBoolean("additionalProperties", true).not()) {
                require(keys.all { it in names }) { "MCP 参数包含未声明字段：$path" }
            }
            for (name in names) {
                if (value.has(name)) {
                    validateValue(properties.getJSONObject(name), value.opt(name), "$path.$name", depth + 1)
                }
            }
        }
        checkNumericLimit(schema, "minProperties", value.length(), path)
        checkNumericLimit(schema, "maxProperties", value.length(), path, lowerBound = false)
    }

    private fun validateArray(schema: JSONObject, value: Any?, path: String, depth: Int) {
        require(value is JSONArray) { "MCP 参数必须是 array：$path" }
        require(value.length() <= MAX_ARRAY_ITEMS) { "MCP 参数数组过长：$path" }
        checkNumericLimit(schema, "minItems", value.length(), path)
        checkNumericLimit(schema, "maxItems", value.length(), path, lowerBound = false)
        schema.optJSONObject("items")?.let { itemSchema ->
            for (index in 0 until value.length()) {
                validateValue(itemSchema, value.get(index), "$path[$index]", depth + 1)
            }
        }
    }

    private fun validateString(schema: JSONObject, value: Any?, path: String) {
        require(value is String) { "MCP 参数必须是 string：$path" }
        checkNumericLimit(schema, "minLength", value.length, path)
        checkNumericLimit(schema, "maxLength", value.length, path, lowerBound = false)
        schema.optString("pattern").takeIf(String::isNotBlank)?.let { pattern ->
            val matches = runCatching { Regex(pattern).containsMatchIn(value) }.getOrElse {
                throw IllegalArgumentException("MCP schema pattern 无效：$path", it)
            }
            require(matches) { "MCP 参数不符合 pattern：$path" }
        }
    }

    private fun validateNumber(schema: JSONObject, value: Any?, path: String, integer: Boolean) {
        require(value is Number) { "MCP 参数必须是 number：$path" }
        val number = value.toDouble()
        require(number.isFinite()) { "MCP 参数不是有限数字：$path" }
        if (integer) require(number % 1.0 == 0.0) { "MCP 参数必须是 integer：$path" }
        schema.optDouble("minimum").takeUnless(Double::isNaN)?.let { require(number >= it) { "MCP 参数小于 minimum：$path" } }
        schema.optDouble("maximum").takeUnless(Double::isNaN)?.let { require(number <= it) { "MCP 参数大于 maximum：$path" } }
    }

    private fun checkNumericLimit(schema: JSONObject, key: String, actual: Int, path: String, lowerBound: Boolean = true) {
        if (!schema.has(key)) return
        val limit = schema.optInt(key, Int.MIN_VALUE)
        require(limit != Int.MIN_VALUE && if (lowerBound) actual >= limit else actual <= limit) {
            "MCP 参数违反 $key：$path"
        }
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = when {
        left === JSONObject.NULL && (right == null || right === JSONObject.NULL) -> true
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        left is JSONObject && right is JSONObject -> left.toString() == right.toString()
        left is JSONArray && right is JSONArray -> left.toString() == right.toString()
        else -> left == right
    }
}
