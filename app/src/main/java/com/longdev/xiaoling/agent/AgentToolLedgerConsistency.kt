package com.longdev.xiaoling.agent

internal fun AgentToolCallRecord.matchesLedgerEvent(
    event: RunEventRecord?,
    expectedType: String,
): Boolean {
    val metadata = event?.metadata as? RunEventMetadata.ToolCall ?: return false
    val expectedCreatedAt = when (expectedType) {
        "tool.call.proposed" -> createdAt
        "tool.call.validated" -> validatedAt ?: return false
        else -> return false
    }
    return event.runId == runId &&
        event.type == expectedType &&
        event.createdAt == expectedCreatedAt &&
        metadata.id == id &&
        metadata.toolName == toolName &&
        metadata.risk == risk &&
        metadata.arguments == arguments
}

internal fun AgentToolResultRecord.matchesLedgerEvent(event: RunEventRecord?): Boolean {
    val metadata = event?.metadata as? RunEventMetadata.ToolResult ?: return false
    // long: errorMessage 是结果账本从成功标记和正文派生的审计字段；即使 typed event 没有独立错误列，也必须核对派生语义，避免损坏账本被恢复入口采信。
    val expectedErrorMessage = if (success) null else content
    return event.runId == runId &&
        event.type == "tool.result" &&
        event.createdAt == createdAt &&
        errorMessage == expectedErrorMessage &&
        metadata.toolCallId == toolCallId &&
        metadata.toolName == toolName &&
        metadata.content == content &&
        metadata.success == success &&
        metadata.durationMs == durationMs &&
        metadata.verified == executorVerified &&
        metadata.memoryIdsUsed == memoryIdsUsed &&
        metadata.replaySafety == replaySafety &&
        metadata.executionReceipt == executionReceipt
}

internal fun AgentToolResultRecord.matchesLedgerVerificationEvent(event: RunEventRecord?): Boolean {
    val status = verificationStatus ?: return false
    val eventId = verifiedEventId ?: return false
    val eventCreatedAt = verifiedAt ?: return false
    val metadata = event?.metadata as? RunEventMetadata.ToolVerification ?: return false
    return event.id == eventId &&
        event.runId == runId &&
        event.type == "tool.verify" &&
        event.createdAt == eventCreatedAt &&
        metadata.toolCallId == toolCallId &&
        metadata.toolName == toolName &&
        metadata.status == status
}
