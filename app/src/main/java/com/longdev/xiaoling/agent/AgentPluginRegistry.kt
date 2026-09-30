package com.longdev.xiaoling.agent

/**
 * 插件系统第一版只管理声明，不加载外部代码。
 *
 * long: manifest、权限和版本状态先可审计，任意插件执行器必须等到独立沙箱和真机门禁完成后再接入。
 */
enum class AgentPluginPermission {
    READ_ONLY_CAPABILITIES,
    NETWORK,
    WORKSPACE,
    TERMINAL,
    MCP,
    DEVICE_ACTION,
}

enum class AgentPluginExecutionMode {
    DECLARATIVE_MANIFEST_ONLY,
    EXTERNAL_CODE,
}

data class AgentPluginManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val publisher: String,
    val capabilities: Set<String> = emptySet(),
    val permissions: Set<AgentPluginPermission> = emptySet(),
    val executionMode: AgentPluginExecutionMode = AgentPluginExecutionMode.DECLARATIVE_MANIFEST_ONLY,
    val source: AgentPluginSource? = null,
)

data class AgentPluginSource(
    val url: String,
    val commit: String,
    val contentSha256: String,
)

data class InstalledAgentPlugin(
    val manifest: AgentPluginManifest,
    val enabled: Boolean = false,
)

sealed interface AgentPluginInstallResult {
    data class Installed(val plugin: InstalledAgentPlugin) : AgentPluginInstallResult
    data class AlreadyInstalled(val plugin: InstalledAgentPlugin) : AgentPluginInstallResult
    data class Rejected(val reason: String) : AgentPluginInstallResult
}

object AgentPluginPolicy {
    private val VERSION = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    private val ID = Regex("^[a-z][a-z0-9._-]{0,99}$")
    private const val MAX_ID_LENGTH = 100
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private val COMMIT = Regex("^[0-9a-f]{7,64}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun validate(manifest: AgentPluginManifest) {
        require(manifest.id.length in 1..MAX_ID_LENGTH && ID.matches(manifest.id)) { "插件 ID 格式无效" }
        require(VERSION.matches(manifest.version) && versionParts(manifest.version) != null) { "插件版本必须是 x.y.z" }
        require(manifest.name.trim().length in 1..MAX_NAME_LENGTH) { "插件名称长度无效" }
        require(manifest.description.trim().length in 1..MAX_DESCRIPTION_LENGTH) { "插件描述长度无效" }
        require(manifest.publisher.isNotBlank()) { "插件发布者不能为空" }
        require(manifest.capabilities.none { it.isBlank() }) { "插件能力 ID 不能为空" }
        require(manifest.executionMode == AgentPluginExecutionMode.DECLARATIVE_MANIFEST_ONLY) {
            "插件执行模式未被当前版本允许"
        }
        manifest.source?.let { source ->
            require(source.url.startsWith("https://")) { "插件来源必须使用 HTTPS" }
            require(source.url.length <= 2_000) { "插件来源 URL 过长" }
            require(COMMIT.matches(source.commit)) { "插件来源 commit 必须是 7 到 64 位小写 SHA-1/SHA-256" }
            require(SHA256.matches(source.contentSha256)) { "插件内容指纹必须是 64 位小写 SHA-256" }
        }
    }

    fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
            ?: error("插件版本无效：$left")
        val rightParts = versionParts(right)
            ?: error("插件版本无效：$right")
        return leftParts.zip(rightParts).firstOrNull { (l, r) -> l != r }
            ?.let { (l, r) -> l.compareTo(r) }
            ?: 0
    }

    private fun versionParts(version: String): List<Int>? = VERSION.matchEntire(version)
        ?.groupValues
        ?.drop(1)
        ?.map { it.toIntOrNull() ?: return null }
}

class AgentPluginRegistry(
    private val stateStore: AgentPluginStateStore? = null,
) {
    private val plugins = linkedMapOf<String, InstalledAgentPlugin>()
    private var persistenceFailureReason: String? = null
    private var restoreBlocked = false

    init {
        restore()
    }

    @Synchronized
    fun persistenceFailureReason(): String? = persistenceFailureReason

    private fun restore() {
        val store = stateStore ?: return
        val loaded = runCatching { store.loadState() }
        if (loaded.isFailure) {
            restoreBlocked = true
            persistenceFailureReason = loaded.exceptionOrNull()?.message ?: "插件状态读取失败"
            return
        }
        val raw = loaded.getOrNull() ?: return
        runCatching { AgentPluginJsonCodec.decode(raw) }
            .onSuccess { restored ->
                synchronized(this) {
                    plugins.clear()
                    restored.forEach { plugins[it.manifest.id] = it }
                    persistenceFailureReason = null
                }
            }
            .onFailure {
                // long: 损坏的旧账本可能藏有更高版本或不同来源；冻结变更，避免新安装覆盖它后绕过回退与指纹校验。
                restoreBlocked = true
                persistenceFailureReason = it.message ?: "插件状态校验失败"
            }
    }

    private fun persist(candidate: Collection<InstalledAgentPlugin>): Boolean {
        val store = stateStore ?: return true
        return runCatching {
            store.saveState(AgentPluginJsonCodec.encode(candidate))
            persistenceFailureReason = null
        }.onFailure {
            persistenceFailureReason = it.message ?: "插件状态写入失败"
        }.isSuccess
    }

    @Synchronized
    fun install(manifest: AgentPluginManifest): AgentPluginInstallResult {
        if (restoreBlocked) return AgentPluginInstallResult.Rejected("插件历史状态损坏，安装已冻结")
        runCatching { AgentPluginPolicy.validate(manifest) }
            .onFailure { return AgentPluginInstallResult.Rejected(it.message ?: "插件 manifest 无效") }
        val existing = plugins[manifest.id]
        if (existing != null) {
            val comparison = AgentPluginPolicy.compareVersions(manifest.version, existing.manifest.version)
            if (comparison < 0) return AgentPluginInstallResult.Rejected("插件版本不能回退")
            if (comparison == 0) {
                return if (manifest == existing.manifest) {
                    AgentPluginInstallResult.AlreadyInstalled(existing)
                } else {
                    AgentPluginInstallResult.Rejected("相同版本的插件 manifest 不一致")
                }
            }
        }
        // long: 安装或升级默认保持停用，避免新 manifest 在没有用户确认时改变可用能力面。
        val installed = InstalledAgentPlugin(manifest = manifest, enabled = false)
        val candidate = plugins.toMutableMap().apply { put(manifest.id, installed) }
        if (!persist(candidate.values)) {
            return AgentPluginInstallResult.Rejected("插件状态无法持久化")
        }
        plugins[manifest.id] = installed
        return AgentPluginInstallResult.Installed(installed)
    }

    @Synchronized
    fun list(): List<InstalledAgentPlugin> = plugins.values.sortedBy { it.manifest.id }

    @Synchronized
    fun get(id: String): InstalledAgentPlugin? = plugins[id]

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): InstalledAgentPlugin? {
        if (restoreBlocked) return null
        val current = plugins[id] ?: return null
        val updated = current.copy(enabled = enabled)
        val candidate = plugins.toMutableMap().apply { put(id, updated) }
        if (!persist(candidate.values)) return null
        return updated.also { plugins[id] = it }
    }

    @Synchronized
    fun uninstall(id: String): Boolean {
        if (restoreBlocked) return false
        if (id !in plugins) return false
        val candidate = plugins.toMutableMap().apply { remove(id) }
        if (!persist(candidate.values)) return false
        plugins.remove(id)
        return true
    }
}
