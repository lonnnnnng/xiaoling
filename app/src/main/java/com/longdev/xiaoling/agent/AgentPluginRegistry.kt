package com.longdev.xiaoling.agent

import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 插件声明映射宿主只读能力，不加载外部代码。
 *
 * long: 签名声明经前台网关映射现有宿主工具；外部代码执行器须等独立沙箱和真机门禁完成后再接入。
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
    val apiVersion: Int = AgentPluginPolicy.SUPPORTED_API_VERSION,
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
    val signedManifest: AgentPluginSignedManifest? = null,
    val highestSignedManifest: AgentPluginSignedManifest? = null,
    val history: List<AgentPluginSignedManifest> = emptyList(),
    val removed: Boolean = false,
)

sealed interface AgentPluginInstallResult {
    data class Installed(val plugin: InstalledAgentPlugin) : AgentPluginInstallResult
    data class AlreadyInstalled(val plugin: InstalledAgentPlugin) : AgentPluginInstallResult
    data class Rejected(val reason: String) : AgentPluginInstallResult
}

object AgentPluginPolicy {
    const val SUPPORTED_API_VERSION: Int = 1
    private val VERSION = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    private val ID = Regex("^[a-z][a-z0-9._-]{0,99}$")
    private const val MAX_ID_LENGTH = 100
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private val COMMIT = Regex("^[0-9a-f]{7,64}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun validate(manifest: AgentPluginManifest) {
        require(manifest.apiVersion == SUPPORTED_API_VERSION) {
            "插件 API 版本不受支持：${manifest.apiVersion}，当前仅支持 $SUPPORTED_API_VERSION"
        }
        require(manifest.id.length in 1..MAX_ID_LENGTH && ID.matches(manifest.id)) { "插件 ID 格式无效" }
        require(manifest.version.length <= 32 && VERSION.matches(manifest.version) && versionParts(manifest.version) != null) { "插件版本必须是 x.y.z" }
        require(manifest.name.isNotBlank() && manifest.name.length <= MAX_NAME_LENGTH) { "插件名称长度无效" }
        require(manifest.description.isNotBlank() && manifest.description.length <= MAX_DESCRIPTION_LENGTH) { "插件描述长度无效" }
        require(manifest.publisher.isNotBlank() && manifest.publisher.length <= 200) { "插件发布者长度无效" }
        require(manifest.capabilities.size <= 64 && manifest.capabilities.all { it.isNotBlank() && it.length <= 120 }) {
            "插件能力数量或 ID 长度无效"
        }
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
    private val registryIdentity = UUID.randomUUID().toString()
    private val revisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val executionChanges = revisions.asStateFlow()

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
                    restored.forEach { plugins[it.manifest.id] = immutable(it) }
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
        return runCatching {
            // long: 内存 Registry 也执行容量和签名账本校验，避免无持久化的调用者绕开相同边界。
            val encoded = AgentPluginJsonCodec.encode(candidate)
            stateStore?.saveState(encoded)
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
        if (existing?.signedManifest != null) return AgentPluginInstallResult.Rejected("已确认签名的插件不能被无签名声明替换")
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
        val installed = immutable(InstalledAgentPlugin(manifest = manifest, enabled = false))
        val candidate = plugins.toMutableMap().apply { put(manifest.id, installed) }
        invalidateExecution(manifest.id)
        if (!persist(candidate.values)) {
            return AgentPluginInstallResult.Rejected("插件状态无法持久化")
        }
        plugins[manifest.id] = installed
        return AgentPluginInstallResult.Installed(installed)
    }

    @Synchronized
    fun list(): List<InstalledAgentPlugin> = plugins.values.filterNot { it.removed }.sortedBy { it.manifest.id }

    @Synchronized
    fun get(id: String): InstalledAgentPlugin? = plugins[id]?.takeUnless { it.removed }

    @Synchronized
    fun executionBinding(id: String, expectedManifestSha256: String): AgentPluginExecutionBinding {
        require(!restoreBlocked && persistenceFailureReason == null) { "插件账本不可用，执行已冻结" }
        val plugin = requireNotNull(get(id)) { "插件未安装" }
        require(plugin.enabled && AgentPluginPermission.READ_ONLY_CAPABILITIES in plugin.manifest.permissions) { "插件未启用或未声明只读能力" }
        val signed = requireNotNull(plugin.signedManifest) { "无签名声明只能预览" }
        val confirmation = AgentPluginSigning.verify(signed)
        require(confirmation.manifestSha256 == expectedManifestSha256) { "插件版本已变化，请重新选择" }
        return AgentPluginExecutionBinding(plugin.manifest, confirmation, registryIdentity, revisions.value[id] ?: 0L)
    }

    @Synchronized
    fun isExecutionCurrent(binding: AgentPluginExecutionBinding): Boolean = runCatching {
        binding.registryIdentity == registryIdentity && binding.revision == (revisions.value[binding.manifest.id] ?: 0L) &&
            executionBinding(binding.manifest.id, binding.confirmation.manifestSha256).let {
                it.manifest == binding.manifest && it.confirmation == binding.confirmation
            }
    }.getOrDefault(false)

    private fun invalidateExecution(id: String) {
        // long: 先撤销旧进程许可再尝试写盘；写失败也不能让正在运行的旧任务继续，重新启用只签发新许可。
        revisions.value = Collections.unmodifiableMap(revisions.value + (id to ((revisions.value[id] ?: 0L) + 1L)))
    }

    @Synchronized
    fun installSigned(
        input: AgentPluginSignedManifest,
        confirmation: AgentPluginPublisherConfirmation? = null,
    ): AgentPluginInstallResult {
        if (restoreBlocked) return AgentPluginInstallResult.Rejected("插件历史状态损坏，安装已冻结")
        val signed = immutable(input)
        val verified = runCatching { AgentPluginSigning.verify(signed) }
            .getOrElse { return AgentPluginInstallResult.Rejected(it.message ?: "插件签名无效") }
        val existing = plugins[signed.manifest.id]
        val previous = existing?.signedManifest
        if (previous == null) {
            // long: envelope 自带公钥只能证明持有人签署；首装或旧声明转签名必须由前台确认具体内容与指纹。
            if (confirmation != verified) return AgentPluginInstallResult.Rejected("请先确认本次 manifest 与发布者公钥指纹")
            if (existing != null) {
                val compare = AgentPluginPolicy.compareVersions(signed.manifest.version, existing.manifest.version)
                if (compare < 0 || signed.manifest.publisher != existing.manifest.publisher ||
                    (compare == 0 && signed.manifest != existing.manifest)) {
                    return AgentPluginInstallResult.Rejected("签名声明与旧插件身份或版本不一致")
                }
            }
        } else {
            if (previous.publicKeySpkiBase64 != signed.publicKeySpkiBase64 ||
                previous.manifest.publisher != signed.manifest.publisher) {
                return AgentPluginInstallResult.Rejected("插件发布者公钥或名称不能隐式更换")
            }
            if (!existing.removed && signed.manifest == existing.manifest) {
                // long: ECDSA 的随机签名字节不作为版本身份；同内容重新签署保持当前启停状态。
                return AgentPluginInstallResult.AlreadyInstalled(existing)
            }
            val highest = requireNotNull(existing.highestSignedManifest)
            val compare = AgentPluginPolicy.compareVersions(signed.manifest.version, highest.manifest.version)
            if (compare < 0) return AgentPluginInstallResult.Rejected("旧版本只能从已保存历史显式回滚")
            if (compare == 0 && signed.manifest != highest.manifest) {
                return AgentPluginInstallResult.Rejected("相同版本的插件 manifest 不一致")
            }
        }
        val highest = existing?.highestSignedManifest?.takeIf {
            AgentPluginPolicy.compareVersions(it.manifest.version, signed.manifest.version) >= 0
        } ?: signed
        val installed = immutable(InstalledAgentPlugin(
            manifest = signed.manifest, signedManifest = signed, highestSignedManifest = highest,
            history = retainedHistory(existing, signed),
        ))
        return if (replace(installed)) AgentPluginInstallResult.Installed(installed)
        else AgentPluginInstallResult.Rejected("插件状态无法持久化")
    }

    @Synchronized
    fun rollbackVersions(id: String): List<String> = get(id)?.let { plugin ->
        (plugin.history + listOfNotNull(plugin.highestSignedManifest))
            .map { it.manifest.version }.distinct()
            .filter { AgentPluginPolicy.compareVersions(it, plugin.manifest.version) < 0 }
            .sortedWith { a, b -> AgentPluginPolicy.compareVersions(b, a) }
    }.orEmpty()

    @Synchronized
    fun rollback(id: String, version: String, expectedCurrentManifest: AgentPluginManifest? = null): AgentPluginInstallResult {
        if (restoreBlocked) return AgentPluginInstallResult.Rejected("插件历史状态损坏，回滚已冻结")
        val current = get(id) ?: return AgentPluginInstallResult.Rejected("插件未安装")
        if (expectedCurrentManifest != null && current.manifest != expectedCurrentManifest) {
            return AgentPluginInstallResult.Rejected("插件当前版本已变化，请重新选择回滚")
        }
        if (version !in rollbackVersions(id)) return AgentPluginInstallResult.Rejected("该版本不在可回滚历史中")
        val target = (current.history + listOfNotNull(current.highestSignedManifest)).first { it.manifest.version == version }
        // long: 回滚只使用本机已确认身份下的保存版本，每次重新验签并停用；最高版本锚点仍保留。
        val updated = immutable(current.copy(manifest = target.manifest, enabled = false, signedManifest = target,
            history = retainedHistory(current, target)))
        return if (replace(updated)) AgentPluginInstallResult.Installed(updated)
        else AgentPluginInstallResult.Rejected("插件回滚状态无法持久化")
    }

    private fun retainedHistory(previous: InstalledAgentPlugin?, target: AgentPluginSignedManifest): List<AgentPluginSignedManifest> =
        (previous?.history.orEmpty() + listOfNotNull(previous?.signedManifest, previous?.highestSignedManifest))
            .filter { it.manifest.version != target.manifest.version }.distinctBy { it.manifest.version }
            .sortedWith { a, b -> AgentPluginPolicy.compareVersions(b.manifest.version, a.manifest.version) }
            .take(AgentPluginSigning.MAX_HISTORY)

    private fun replace(updated: InstalledAgentPlugin): Boolean {
        val owned = immutable(updated)
        val candidate = plugins.toMutableMap().apply { put(owned.manifest.id, owned) }
        invalidateExecution(owned.manifest.id)
        if (!persist(candidate.values)) return false
        plugins[owned.manifest.id] = owned
        return true
    }

    private fun immutable(manifest: AgentPluginManifest): AgentPluginManifest = manifest.copy(
        capabilities = Collections.unmodifiableSet(LinkedHashSet(manifest.capabilities)),
        permissions = Collections.unmodifiableSet(LinkedHashSet(manifest.permissions)),
    )

    private fun immutable(signed: AgentPluginSignedManifest): AgentPluginSignedManifest =
        signed.copy(manifest = immutable(signed.manifest))

    private fun immutable(plugin: InstalledAgentPlugin): InstalledAgentPlugin = plugin.copy(
        // long: Kotlin Set/List 只是只读接口；不可修改且无输入别名的集合保证调用者不能绕过验签和写盘改账本。
        manifest = immutable(plugin.manifest), signedManifest = plugin.signedManifest?.let(::immutable),
        highestSignedManifest = plugin.highestSignedManifest?.let(::immutable),
        history = Collections.unmodifiableList(plugin.history.map(::immutable)),
    )

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): InstalledAgentPlugin? {
        if (restoreBlocked) return null
        val current = get(id) ?: return null
        if (current.enabled == enabled) return current
        val updated = current.copy(enabled = enabled)
        val candidate = plugins.toMutableMap().apply { put(id, updated) }
        invalidateExecution(id)
        if (!persist(candidate.values)) return null
        return updated.also { plugins[id] = it }
    }

    @Synchronized
    fun uninstall(id: String, expectedCurrentManifest: AgentPluginManifest? = null): Boolean {
        if (restoreBlocked) return false
        val current = get(id) ?: return false
        if (expectedCurrentManifest != null && current.manifest != expectedCurrentManifest) return false
        // long: 签名插件移除后留下停用身份和最高版本，防止卸载重装绕过公钥绑定及降版门禁。
        val candidate = plugins.toMutableMap().apply {
            if (current.signedManifest != null) put(id, current.copy(enabled = false, removed = true)) else remove(id)
        }
        invalidateExecution(id)
        if (!persist(candidate.values)) return false
        plugins.clear()
        plugins.putAll(candidate)
        return true
    }
}
