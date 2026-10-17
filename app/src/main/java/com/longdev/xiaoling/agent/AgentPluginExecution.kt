package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class AgentPluginForegroundSnapshot(val revision: Long = 0, val resumed: Boolean = false)

/** long: 前台许可由 MainActivity 的 resumed 生命周期签发；暂停后即使返回前台，旧许可也永久失效。 */
class AgentPluginForegroundState {
    private val mutableState = MutableStateFlow(AgentPluginForegroundSnapshot())
    val state = mutableState.asStateFlow()
    private var owner: Any? = null

    @Synchronized fun resumed(activity: Any) {
        if (owner === activity && state.value.resumed) return
        owner = activity
        mutableState.value = AgentPluginForegroundSnapshot(state.value.revision + 1, true)
    }

    @Synchronized fun paused(activity: Any) {
        if (owner !== activity) return
        owner = null
        mutableState.value = AgentPluginForegroundSnapshot(state.value.revision + 1, false)
    }

    fun capture(): Long = state.value.let { require(it.resumed) { "插件任务只允许在应用前台发起" }; it.revision }
    fun isCurrent(revision: Long): Boolean = state.value.let { it.resumed && it.revision == revision }
}

class AgentPluginExecutionBinding internal constructor(
    val manifest: AgentPluginManifest,
    val confirmation: AgentPluginPublisherConfirmation,
    val registryIdentity: String,
    val revision: Long,
) {
    private var claimed = false
    @Synchronized internal fun claim() {
        check(!claimed) { "插件选择只能用于一个新 Run" }
        claimed = true
    }
}

data class AgentPluginExecutionSelection(
    val binding: AgentPluginExecutionBinding,
    val foregroundRevision: Long,
) {
    companion object {
        fun capture(registry: AgentPluginRegistry, foreground: AgentPluginForegroundState,
            id: String, expectedManifestSha256: String): AgentPluginExecutionSelection =
            AgentPluginExecutionSelection(registry.executionBinding(id, expectedManifestSha256), foreground.capture())
    }
}

object AgentPluginExecutionPolicy {
    const val SELECTION_EVENT = "plugin.execution.selected"
    const val MAX_TOOL_TIMEOUT_MS = 10_000L

    fun isPluginRun(detail: AgentRunDetailRecord): Boolean =
        detail.snapshot.events.any { it.type == SELECTION_EVENT }

    fun requireFreshSelection(detail: AgentRunDetailRecord) {
        require(!isPluginRun(detail)) {
            "插件任务不能继承旧前台许可，请从插件页重新显式发起"
        }
    }
}

/** long: 仅把已确认声明映射到宿主固定 SAFE 能力，完整执行/验证仍交给原 Runtime，绝不加载插件代码。 */
class AgentPluginToolRegistry(
    private val delegate: ToolRegistry,
    private val plugins: AgentPluginRegistry,
    private val foreground: AgentPluginForegroundState,
    val selection: AgentPluginExecutionSelection,
    allowedToolNames: Set<String>,
    executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
    invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
    externalInput: Boolean = false,
    parentRunId: String? = null,
) : ToolRegistry, AgentRunContextAwareToolRegistry, AgentRunLifecycleAwareToolRegistry,
    AgentToolExecutionLifecycleAwareToolRegistry {
    private var runId: String? = null
    @Volatile private var closed = false
    private val nativeDefinitions: Map<String, ToolDefinition>
    private val nativeFingerprints: Map<String, String>
    private val revokedTools = mutableSetOf<String>()
    private val exposedDefinitions: Map<String, ToolDefinition>
    val catalog: AgentToolCatalog

    init {
        require(executionOrigin == AgentExecutionOrigin.FOREGROUND && invocationSource == AgentInvocationSource.DIRECT &&
            !externalInput && parentRunId == null) { "插件任务只允许本机前台显式调用，不接受共享草稿、Workflow、后台或子 Agent" }
        checkCurrent()
        val available = delegate.availableTools().map { it.name }.toSet()
        nativeDefinitions = selection.binding.manifest.capabilities.sorted().mapNotNull { name ->
            if (name !in AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES || name !in allowedToolNames || name !in available) return@mapNotNull null
            val registered = delegate.registeredDefinition(name) ?: return@mapNotNull null
            val current = delegate.definition(name) ?: return@mapNotNull null
            if (registered.risk != ToolRisk.SAFE || current.risk != ToolRisk.SAFE ||
                registered.approvalPolicy != ToolApprovalPolicy.NONE || current.approvalPolicy != ToolApprovalPolicy.NONE ||
                ToolDefinitionRecoveryContract.snapshot(registered).definitionFingerprint !=
                ToolDefinitionRecoveryContract.snapshot(current).definitionFingerprint) return@mapNotNull null
            name to current
        }.toMap()
        require(nativeDefinitions.isNotEmpty()) { "签名插件与当前 Profile 没有可执行的 SAFE 能力交集" }
        nativeFingerprints = nativeDefinitions.mapValues { ToolDefinitionRecoveryContract.snapshot(it.value).definitionFingerprint }
        exposedDefinitions = nativeDefinitions.mapValues { (_, definition) ->
            definition.copy(timeoutMs = minOf(definition.timeoutMs ?: AgentPluginExecutionPolicy.MAX_TOOL_TIMEOUT_MS,
                AgentPluginExecutionPolicy.MAX_TOOL_TIMEOUT_MS))
        }
        catalog = AgentToolCatalog.fromDefinitions(exposedDefinitions.values.toList(), AgentToolCatalogOrigin.PLUGIN)
        selection.binding.claim()
    }

    private fun checkCurrent() {
        if (closed || !plugins.isExecutionCurrent(selection.binding) || !foreground.isCurrent(selection.foregroundRevision)) {
            throw CancellationException("插件前台许可已撤销")
        }
    }

    @Synchronized private fun currentDefinition(name: String): ToolDefinition? {
        if (name !in nativeDefinitions || name in revokedTools) return null
        val current = delegate.definition(name)
        val registered = delegate.registeredDefinition(name)
        val valid = current != null && registered != null && name in delegate.availableTools().map { it.name } &&
            current.risk == ToolRisk.SAFE && registered.risk == ToolRisk.SAFE &&
            registered.approvalPolicy == ToolApprovalPolicy.NONE && current.approvalPolicy == ToolApprovalPolicy.NONE &&
            ToolDefinitionRecoveryContract.snapshot(registered).definitionFingerprint == nativeFingerprints[name] &&
            ToolDefinitionRecoveryContract.snapshot(current).definitionFingerprint ==
                nativeFingerprints[name]
        // long: 本轮已观察到的隐藏、风险升高或定义漂移永久收窄能力；状态恢复只能在新显式选择中生效。
        if (!valid) revokedTools += name
        return current.takeIf { valid }
    }

    override fun availableTools(): List<ToolDefinition> {
        checkCurrent()
        // long: 启动时冻结的能力只能收窄；新启用能力或动态定义变化不能进入已授权 Run。
        return exposedDefinitions.values.filter { currentDefinition(it.name) != null }
    }
    override fun definition(name: String): ToolDefinition? {
        checkCurrent()
        return exposedDefinitions[name]?.takeIf { currentDefinition(name) != null }
    }
    override fun registeredTools() = exposedDefinitions.values.toList()
    override fun registeredDefinition(name: String) = exposedDefinitions[name]
    override fun toolCatalog() = AgentToolCatalog.fromDefinitions(availableTools(), AgentToolCatalogOrigin.PLUGIN)
    override fun registeredToolCatalog() = catalog

    override fun bindRunContext(context: AgentToolExecutionContext) {
        require(!closed && context.runId.isNotBlank() && context.executionOrigin == AgentExecutionOrigin.FOREGROUND && context.invocationSource == AgentInvocationSource.DIRECT)
        require(runId == null || runId == context.runId) { "插件许可不能跨 Run 复用" }
        runId = context.runId
        (delegate as? AgentRunContextAwareToolRegistry)?.bindRunContext(context)
    }

    private fun checkCall(call: ToolCall) {
        checkCurrent()
        require(runId != null) { "插件调用尚未绑定真实 Run" }
        require(call.risk == ToolRisk.SAFE && currentDefinition(call.name) != null) { "插件能力未授权或定义已变化" }
        val errors = requireNotNull(exposedDefinitions[call.name]).validateArguments(call.arguments).errors
        require(errors.isEmpty()) { errors.joinToString("；") }
    }

    override fun beforeToolExecution(call: ToolCall, approval: AgentToolApprovalEvidence?) {
        checkCall(call)
        (delegate as? AgentToolExecutionLifecycleAwareToolRegistry)?.beforeToolExecution(call, approval)
    }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        checkCall(call)
        val result = withTimeout(requireNotNull(exposedDefinitions[call.name]).timeoutMs!!) { delegate.execute(call) }
        currentCoroutineContext().ensureActive()
        // long: 执行期间停用或离开前台后，迟到结果不能作为成功事实返回；已发生的只读读取不作撤回承诺。
        checkCall(call)
        require(result.content.length <= AciPolicy.MAX_RESULT_CHARS) { "插件只读结果超过 20000 字符" }
        return result
    }

    override fun afterToolVerification(call: ToolCall, result: ToolExecutionResult) {
        checkCall(call)
        (delegate as? AgentToolExecutionLifecycleAwareToolRegistry)?.afterToolVerification(call, result)
    }

    override fun onRunFinished(runId: String) {
        if (this.runId != runId) return
        closed = true
        (delegate as? AgentRunLifecycleAwareToolRegistry)?.onRunFinished(runId)
    }

    fun evidenceJson(): String = JSONObject().put("schemaVersion", 1)
        .put("pluginId", selection.binding.manifest.id).put("version", selection.binding.manifest.version)
        .put("manifestSha256", selection.binding.confirmation.manifestSha256)
        .put("publicKeySha256", selection.binding.confirmation.publicKeySha256)
        .put("catalogFingerprint", catalog.fingerprint)
        .put("capabilities", JSONArray(exposedDefinitions.keys.sorted())).toString()

    suspend fun <T> guardRun(block: suspend () -> T): T = coroutineScope {
        checkCurrent()
        val runJob = currentCoroutineContext().job
        // long: 监听整轮，包括模型等待；失败写盘、停用再启用和暂停再恢复都不能复活旧许可。
        val pluginWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            plugins.executionChanges.first { !plugins.isExecutionCurrent(selection.binding) }
            if (!closed) runJob.cancel(CancellationException("插件签名许可已撤销"))
        }
        val foregroundWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            foreground.state.first { !foreground.isCurrent(selection.foregroundRevision) }
            if (!closed) runJob.cancel(CancellationException("应用已离开插件授权前台"))
        }
        // long: Runtime 已落下终态后由其 finally 关闭许可；不能再把已完成事实改写成调用层的撤销失败。
        try { block() }
        finally { pluginWatcher.cancel(); foregroundWatcher.cancel() }
    }
}
