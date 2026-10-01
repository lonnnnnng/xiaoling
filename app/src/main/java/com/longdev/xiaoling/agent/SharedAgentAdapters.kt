package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentPlanDecision
import com.longdev.xiaoling.shared.agent.SharedAgentExecution
import com.longdev.xiaoling.shared.agent.SharedApprovalGate
import com.longdev.xiaoling.shared.agent.SharedAgentLlm
import com.longdev.xiaoling.shared.agent.SharedToolCall
import com.longdev.xiaoling.shared.agent.SharedToolDefinition
import com.longdev.xiaoling.shared.agent.SharedToolExecutionReceipt
import com.longdev.xiaoling.shared.agent.SharedToolExecutionResult
import com.longdev.xiaoling.shared.agent.SharedToolExecutor
import com.longdev.xiaoling.shared.agent.SharedToolReceiptStatus
import com.longdev.xiaoling.shared.agent.SharedToolVerificationEvidence
import com.longdev.xiaoling.shared.agent.SharedToolVerificationStatus

fun ToolDefinition.toSharedAgentDefinition(): SharedToolDefinition = SharedToolDefinition(
    name = name,
    description = description,
    requiresApproval = approvalPolicy == ToolApprovalPolicy.REQUIRE_CONFIRMATION,
    supportsBackground = permissionPolicy.supportsBackground,
)

fun ToolRegistry.toSharedToolCatalog() = toolCatalog().toSharedCatalog()

fun ToolCall.toSharedAgentCall(): SharedToolCall = SharedToolCall(
    id = id,
    name = name,
    arguments = arguments,
)

fun SharedToolCall.toAndroidToolCall(definition: ToolDefinition): ToolCall = ToolCall(
    id = id,
    name = name,
    arguments = arguments,
    risk = definition.risk,
)

fun SharedAgentPlanDecision.toAndroidPlanDecision(
    definitionLookup: (String) -> ToolDefinition?,
): AgentPlanDecision = when (this) {
    SharedAgentPlanDecision.Complete -> AgentPlanDecision.Complete
    is SharedAgentPlanDecision.CallTool -> {
        val definition = definitionLookup(toolCall.name)
            ?: error("共享 Agent 提出未注册工具：${toolCall.name}")
        AgentPlanDecision.CallTool(toolCall.toAndroidToolCall(definition))
    }
}

fun ToolExecutionResult.toSharedAgentResult(
    expectedToolCallId: String? = null,
): SharedToolExecutionResult {
    val evidence = verificationEvidence
    val receipt = executionReceipt
    val evidenceToolCallId = evidence?.toolCallId
    val receiptToolCallId = receipt?.toolCallId
    if (
        expectedToolCallId != null &&
        (
            evidenceToolCallId != null && evidenceToolCallId != expectedToolCallId ||
                receiptToolCallId != null && receiptToolCallId != expectedToolCallId
            )
    ) {
        // long: typed 验证和执行回执都必须绑定当前 ToolCall；ID 错配时拒绝整条结果，不能把别的调用的事实当成本次结果。
        return SharedToolExecutionResult(
            success = false,
            content = "工具验证证据与当前调用不匹配",
            verified = false,
            verification = SharedToolVerificationEvidence(
                status = SharedToolVerificationStatus.FAILED,
                toolCallId = evidenceToolCallId ?: receiptToolCallId,
                reasonCode = "VERIFICATION_TOOL_CALL_MISMATCH",
            ),
        )
    }
    return SharedToolExecutionResult(
        success = success,
        content = content,
        verified = verified,
        executionReceipt = receipt?.let { receipt ->
            SharedToolExecutionReceipt(
                toolCallId = receipt.toolCallId,
                operationId = receipt.operationId,
                status = when (receipt.status) {
                    ToolExecutionReceiptStatus.COMMITTED -> SharedToolReceiptStatus.COMMITTED
                    ToolExecutionReceiptStatus.NOT_COMMITTED -> SharedToolReceiptStatus.NOT_COMMITTED
                    ToolExecutionReceiptStatus.UNKNOWN -> SharedToolReceiptStatus.UNKNOWN
                },
            )
        },
        verification = evidence?.let {
            SharedToolVerificationEvidence(
                status = when (it.status) {
                    ToolVerificationStatus.PASSED -> SharedToolVerificationStatus.PASSED
                    ToolVerificationStatus.FAILED -> SharedToolVerificationStatus.FAILED
                },
                toolCallId = it.toolCallId,
                reasonCode = it.reasonCode,
            )
        },
    )
}

/**
 * long: direct、Workflow 和子 Agent 都从同一组已完成工具生成 shared execution；shared 结果只做跨入口投影，Room 账本仍由 Android Ledger 原子写入。
 */
fun List<AgentToolExecution>.toSharedAgentExecutions(): List<SharedAgentExecution> = map { execution ->
    SharedAgentExecution(
        toolCall = execution.toolCall.toSharedAgentCall(),
        result = execution.toolResult.toSharedAgentResult(expectedToolCallId = execution.toolCall.id),
    )
}

/**
 * long: shared runtime 的审批必须重新查 Android 当前工具定义，避免共享层快照绕过 Profile、Skill 或当前 Run 的动态门禁。
 */
class AndroidSharedApprovalGate(
    private val delegate: ApprovalGate,
    private val definitionLookup: (String) -> ToolDefinition?,
) : SharedApprovalGate {
    override suspend fun requestApproval(
        runId: String,
        toolCall: SharedToolCall,
        definition: SharedToolDefinition,
    ): Boolean {
        val androidDefinition = definitionLookup(toolCall.name) ?: return false
        if (androidDefinition.toSharedAgentDefinition() != definition) return false
        return delegate.requestApproval(
            runId = runId,
            toolCall = toolCall.toAndroidToolCall(androidDefinition),
            definition = androidDefinition,
        ).approved
    }
}

/**
 * long: 执行 adapter 不信任 shared runtime 携带的风险字段，执行前再次从 Android Registry 查定义并恢复原生 ToolCall。
 */
class AndroidSharedToolExecutor(
    private val registry: ToolRegistry,
) : SharedToolExecutor {
    override suspend fun execute(toolCall: SharedToolCall): SharedToolExecutionResult {
        val definition = registry.definition(toolCall.name)
            ?: return SharedToolExecutionResult(false, "共享 Agent 工具未注册：${toolCall.name}")
        // long: 独立 Executor 只负责调用当前 Registry 的执行端口；definition 仍在这里重新读取，避免目录快照变成权限授权。
        return registry.executor().execute(toolCall.toAndroidToolCall(definition))
            .toSharedAgentResult(expectedToolCallId = toolCall.id)
    }
}

/**
 * long: 该 adapter 只做类型和工具目录转换；模型网络、遥测和 Android 失败分类继续由现有 AgentLlm 实现负责。
 */
class AndroidSharedAgentLlm(
    private val delegate: SharedAgentLlm,
) : SharedAgentLlm by delegate
