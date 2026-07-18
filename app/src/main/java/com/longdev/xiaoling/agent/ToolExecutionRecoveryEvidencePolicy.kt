package com.longdev.xiaoling.agent

data class ToolExecutionRecoveryAssessment(
    val canReuseCommittedEffect: Boolean,
    val reason: String,
)

object ToolExecutionRecoveryEvidencePolicy {
    fun assess(
        definition: ToolDefinition,
        result: RunEventMetadata.ToolResult,
    ): ToolExecutionRecoveryAssessment {
        if (definition.name != result.toolName) {
            return insufficient("执行回执与工具定义不匹配")
        }
        if (definition.replaySafety != ToolReplaySafety.IDEMPOTENT_BY_KEY) {
            return insufficient("工具未声明按幂等键安全重放")
        }
        if (!result.success) {
            return insufficient("工具结果未成功")
        }
        if (result.toolCallId.isNullOrBlank()) {
            return insufficient("工具结果缺少持久化调用 ID")
        }
        val receipt = result.executionReceipt
            ?: return insufficient("工具结果缺少持久化执行回执")
        if (receipt.toolCallId != result.toolCallId) {
            return insufficient("执行回执不属于当前工具调用")
        }
        if (receipt.status != ToolExecutionReceiptStatus.COMMITTED) {
            return insufficient("执行回执未确认副作用已提交")
        }
        if (receipt.idempotencyKey.isNullOrBlank()) {
            return insufficient("执行回执缺少幂等键")
        }
        // long: 这里只证明已提交副作用可由同一幂等键识别，不代表旧协程或验证栈可以恢复；真正续跑仍由 Run 恢复策略单独决定。
        return ToolExecutionRecoveryAssessment(
            canReuseCommittedEffect = true,
            reason = "工具声明幂等且持久化执行回执完整",
        )
    }

    private fun insufficient(reason: String) = ToolExecutionRecoveryAssessment(
        canReuseCommittedEffect = false,
        reason = reason,
    )
}
