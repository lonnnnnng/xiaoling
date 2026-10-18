package com.longdev.xiaoling.agent

import com.longdev.xiaoling.genui.GenUiActionPolicy
import com.longdev.xiaoling.genui.GenUiActionSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

object GenUiExecutionPolicy {
    const val SELECTION_EVENT = "genui.action.selected"
    fun isGenUiRun(detail: AgentRunDetailRecord) = detail.snapshot.events.any { it.type == SELECTION_EVENT }
    fun requireFreshSelection(detail: AgentRunDetailRecord) {
        require(!isGenUiRun(detail)) { "卡片任务不能恢复或重试旧引用，请生成新卡片并重新选择" }
    }
}

/** long: 卡片声明只选择一个宿主 SAFE 能力；提升审批策略而不扩大宿主权限，执行前后都核对原目录与真实 resumed 代次。 */
class GenUiActionToolRegistry(
    private val delegate: ToolRegistry,
    private val selection: GenUiActionSelection,
    private val foreground: AgentPluginForegroundState,
    private val foregroundRevision: Long,
) : ToolRegistry, AgentRunContextAwareToolRegistry, AgentRunLifecycleAwareToolRegistry, AgentToolExecutionLifecycleAwareToolRegistry {
    private var runId: String? = null
    @Volatile private var closed = false
    private var approvedCallId: String? = null
    private var executed = false
    private val native = requireNotNull(delegate.definition(selection.tool))
    private val fingerprint = ToolDefinitionRecoveryContract.snapshot(native).definitionFingerprint
    private val exposed = native.copy(approvalPolicy = ToolApprovalPolicy.REQUIRE_CONFIRMATION,
        timeoutMs = minOf(native.timeoutMs ?: 5_000L, 5_000L))

    init {
        GenUiActionPolicy.validateArguments(selection.tool, selection.arguments)
        require(native.name in GenUiActionPolicy.TOOLS && native.risk == ToolRisk.SAFE && native.approvalPolicy == ToolApprovalPolicy.NONE)
        require(native.validateArguments(selection.arguments).errors.isEmpty()) { "卡片参数不符合宿主契约" }
        checkCurrent(); selection.claim()
    }
    private fun checkCurrent() {
        if (closed || !selection.isCurrent() || !foreground.isCurrent(foregroundRevision)) throw CancellationException("卡片前台引用已撤销或过期")
        val current = delegate.definition(native.name); val registered = delegate.registeredDefinition(native.name)
        require(current != null && registered != null && native.name in delegate.availableTools().map { it.name } &&
            ToolDefinitionRecoveryContract.snapshot(current).definitionFingerprint == fingerprint &&
            ToolDefinitionRecoveryContract.snapshot(registered).definitionFingerprint == fingerprint) { "卡片宿主能力已隐藏或变化" }
    }
    override fun availableTools(): List<ToolDefinition> { checkCurrent(); return listOf(exposed) }
    override fun definition(name: String): ToolDefinition? { checkCurrent(); return exposed.takeIf { it.name == name } }
    override fun registeredTools() = listOf(exposed)
    override fun registeredDefinition(name: String) = exposed.takeIf { it.name == name }
    override fun bindRunContext(context: AgentToolExecutionContext) {
        checkCurrent()
        require(context.conversationId == selection.conversationId && context.executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            context.invocationSource == AgentInvocationSource.DIRECT && !context.memoryRecallEnabled && context.workflowDeviceActionContext == null)
        require(runId == null || runId == context.runId) { "卡片许可不能跨 Run 复用" }
        runId = context.runId; (delegate as? AgentRunContextAwareToolRegistry)?.bindRunContext(context)
    }
    private fun checkCall(call: ToolCall) {
        checkCurrent()
        require(runId != null && call.name == selection.tool && call.arguments == selection.arguments && call.risk == ToolRisk.SAFE)
    }
    override fun beforeToolExecution(call: ToolCall, approval: AgentToolApprovalEvidence?) {
        checkCall(call)
        require(approval?.approved == true && approvedCallId == null && !executed) { "卡片必须逐次前台批准" }
        approvedCallId = call.id
        (delegate as? AgentToolExecutionLifecycleAwareToolRegistry)?.beforeToolExecution(call, approval)
    }
    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        checkCall(call); require(call.id == approvedCallId && !executed) { "卡片动作不能重复执行或跳过审批" }; executed = true
        val result = withTimeout(exposed.timeoutMs!!) { delegate.execute(call) }
        currentCoroutineContext().ensureActive(); checkCall(call)
        require(result.content.length <= AciPolicy.MAX_RESULT_CHARS)
        return result
    }
    override fun afterToolVerification(call: ToolCall, result: ToolExecutionResult) {
        checkCall(call); require(executed && call.id == approvedCallId)
        (delegate as? AgentToolExecutionLifecycleAwareToolRegistry)?.afterToolVerification(call, result)
    }
    override fun onRunFinished(runId: String) {
        if (this.runId != runId) return
        closed = true; (delegate as? AgentRunLifecycleAwareToolRegistry)?.onRunFinished(runId)
    }
    fun evidenceJson(): String = JSONObject().put("schemaVersion", 1).put("conversationId", selection.conversationId)
        .put("messageId", selection.messageId).put("messageSha256", selection.messageSha256)
        .put("documentId", selection.documentId).put("revision", selection.revision).put("actionId", selection.actionId)
        .put("tool", selection.tool).put("argumentSha256", GenUiActionPolicy.hash(selection.arguments.toSortedMap().toString()))
        .put("definitionFingerprint", fingerprint).toString()
    fun fixedModel(): AgentLlm = object : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) =
            ToolCall(name = selection.tool, arguments = selection.arguments, risk = ToolRisk.SAFE)
        override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) =
            """{"style":"compact","tone":"neutral"}"""
    }
    suspend fun <T> guardRun(block: suspend () -> T): T = coroutineScope {
        checkCurrent(); val job = currentCoroutineContext().job
        val lifecycle = launch(start = CoroutineStart.UNDISPATCHED) {
            foreground.state.first { !foreground.isCurrent(foregroundRevision) }
            if (!closed) job.cancel(CancellationException("应用已离开卡片授权前台"))
        }
        val expiry = launch {
            while (!closed) { delay(100); if (!selection.isCurrent()) { job.cancel(CancellationException("卡片引用已过期")); break } }
        }
        try { block() } finally { lifecycle.cancel(); expiry.cancel() }
    }
}
