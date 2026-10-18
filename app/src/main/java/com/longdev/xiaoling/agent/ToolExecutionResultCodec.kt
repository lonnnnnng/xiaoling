package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.*

/** long: 新私有结果交换入口，不替换 RunEvent/VerifiedContext/Room 历史 wire，也不授予恢复或审批权限。 */
object ToolExecutionResultCodec {
    fun encode(toolCallId: String, result: ToolExecutionResult): String =
        SharedToolExecutionResultCodec.encode(SharedToolResultFrame(toolCallId, result.toPrivateSharedResult()))

    fun decode(raw: String, expectedToolCallId: String): ToolExecutionResult =
        SharedToolExecutionResultCodec.decode(raw, expectedToolCallId).result.toPrivateAndroidResult()
}

// long: 只有私有交换帧需要保留幂等键；公开 Agent 投影在 SharedAgentAdapters 中显式移除，不能直接复用完整帧。
internal fun ToolExecutionResult.toPrivateSharedResult(): SharedToolExecutionResult = SharedToolExecutionResult(
    success = success, content = content, verified = verified,
    memoryIdsUsed = memoryIdsUsed, knowledgeReferences = knowledgeReferences, recoveryFailure = recoveryFailure,
    executionReceipt = executionReceipt?.let {
        SharedToolExecutionReceipt(it.toolCallId, it.operationId, when (it.status) {
            ToolExecutionReceiptStatus.COMMITTED -> SharedToolReceiptStatus.COMMITTED
            ToolExecutionReceiptStatus.NOT_COMMITTED -> SharedToolReceiptStatus.NOT_COMMITTED
            ToolExecutionReceiptStatus.UNKNOWN -> SharedToolReceiptStatus.UNKNOWN
        }, it.idempotencyKey)
    },
    verification = verificationEvidence?.let {
        SharedToolVerificationEvidence(when (it.status) {
            ToolVerificationStatus.PASSED -> SharedToolVerificationStatus.PASSED
            ToolVerificationStatus.FAILED -> SharedToolVerificationStatus.FAILED
        }, it.toolCallId, it.reasonCode)
    },
    readableEvidence = readableEvidence,
)

internal fun SharedToolExecutionResult.toPrivateAndroidResult(): ToolExecutionResult = ToolExecutionResult(
    success = success, content = content, verified = verified,
    memoryIdsUsed = memoryIdsUsed, knowledgeReferences = knowledgeReferences, recoveryFailure = recoveryFailure,
    executionReceipt = executionReceipt?.let {
        ToolExecutionReceipt(it.toolCallId, it.operationId, it.idempotencyKey, when (it.status) {
            SharedToolReceiptStatus.COMMITTED -> ToolExecutionReceiptStatus.COMMITTED
            SharedToolReceiptStatus.NOT_COMMITTED -> ToolExecutionReceiptStatus.NOT_COMMITTED
            SharedToolReceiptStatus.UNKNOWN -> ToolExecutionReceiptStatus.UNKNOWN
        })
    },
    verificationEvidence = verification?.let {
        ToolVerificationEvidence(when (it.status) {
            SharedToolVerificationStatus.PASSED -> ToolVerificationStatus.PASSED
            SharedToolVerificationStatus.FAILED -> ToolVerificationStatus.FAILED
        }, it.toolCallId, it.reasonCode)
    },
    readableEvidence = readableEvidence,
)
