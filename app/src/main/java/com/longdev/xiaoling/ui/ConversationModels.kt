package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentMessagePartPolicy
import com.longdev.xiaoling.agent.DeviceTypeTextAuditPolicy
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageAttachmentSelection
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.ImageAttachment
import java.util.UUID

internal fun newChatMessageId(): String = "message-${System.currentTimeMillis()}-${UUID.randomUUID()}"

private fun ToolRisk.toUiLabel(): String {
    return when (this) {
        ToolRisk.SAFE -> "低风险"
        ToolRisk.REQUIRES_APPROVAL -> "需确认"
        ToolRisk.DANGEROUS -> "高风险"
    }
}

data class ChatMessage(
    val role: String,
    val text: String,
    val id: String = newChatMessageId(),
    val createdAt: Long = System.currentTimeMillis(),
    val origin: MessageOrigin = MessageOrigin.fromStored(value = null, role = role),
    val verifiedAgentContext: VerifiedAgentContext? = null,
    val meta: MessageMeta? = null,
    val parts: List<MessagePart> = emptyList(),
) {
    fun effectiveParts(): List<MessagePart> = AgentMessagePartPolicy.resolve(
        messageId = id,
        text = text,
        origin = origin,
        verifiedContext = verifiedAgentContext,
        storedParts = parts,
    )
}

internal fun ChatMessage.imagesForRequest(apiMode: ApiMode): List<ImageAttachment> {
    if (apiMode != ApiMode.RESPONSES || origin != MessageOrigin.USER) return emptyList()
    return effectiveParts().filterIsInstance<MessagePart.Image>().map { it.attachment }
}

internal fun ChatMessage.documentsForRequest(apiMode: ApiMode): List<DocumentAttachment> {
    if (apiMode != ApiMode.RESPONSES || origin != MessageOrigin.USER) return emptyList()
    return effectiveParts().filterIsInstance<MessagePart.Document>().map { it.attachment }
}

internal fun ChatMessage.attachmentsForAgent(): MessageAttachmentSelection {
    if (origin != MessageOrigin.USER) return MessageAttachmentSelection()
    // long: 重试和审批恢复只能从 USER 的可信投影重建唯一附件；assistant、Tool 或损坏的重复附件不能重新进入规划请求。
    return when (val attachmentPart = effectiveParts().firstOrNull { it is MessagePart.Image || it is MessagePart.Document }) {
        is MessagePart.Image -> MessageAttachmentSelection(image = attachmentPart.attachment)
        is MessagePart.Document -> MessageAttachmentSelection(document = attachmentPart.attachment)
        else -> MessageAttachmentSelection()
    }
}

data class MessageMeta(
    val providerId: String? = null,
    val providerName: String? = null,
    val model: String? = null,
    val apiMode: ApiMode? = null,
    val streaming: Boolean? = null,
    val requestUrl: String? = null,
    val firstTokenLatencyMs: Long? = null,
    val latencyMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val finishReason: String? = null,
    val errorKind: String? = null,
    val errorMessage: String? = null,
)

internal fun ChatMessage.isEligibleForConversationContext(): Boolean {
    if (role == "user") return true
    if (role != "assistant") return false
    // long: 失败或取消的流式正文只是用户已经看到的局部输出，不能作为完整模型回复再次发送或进入摘要，否则残缺结论会在后续轮次被放大。
    return meta?.finishReason !in setOf("failed", "cancelled") &&
        !(meta?.streaming == true && meta.latencyMs == null && meta.finishReason == null)
}

internal fun List<ChatMessage>.withFailedStreamingGeneration(
    baseMeta: MessageMeta,
    errorKind: String,
    errorMessage: String,
): List<ChatMessage> {
    val last = lastOrNull()
    if (last?.role != "assistant") return this
    // long: delta 已经展示后发生断流时保留用户看到的部分正文，但必须把同一气泡收敛为失败终态，不能让它永久显示“接收中”或伪装成完整回复。
    return dropLast(1) + last.copy(
        meta = (last.meta ?: baseMeta).copy(
            finishReason = "failed",
            errorKind = errorKind,
            errorMessage = errorMessage,
        ),
    )
}

data class ConversationSession(
    val id: String,
    val title: String,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class OperationResultAction {
    OPEN_AGENT_RUN_HISTORY,
    OPEN_INTERRUPTED_AGENT_RUN_HISTORY,
}

data class OperationResult(
    val success: Boolean,
    val title: String,
    val message: String,
    val action: OperationResultAction? = null,
    val requestUrl: String? = null,
    val latencyMs: Long? = null,
    val firstTokenLatencyMs: Long? = null,
)

data class AgentApprovalUiState(
    val requestId: String,
    val runId: String,
    val conversationId: String,
    val toolCallId: String,
    val toolName: String,
    val toolDescription: String,
    val riskLabel: String,
    val arguments: Map<String, String>,
    val expiresAt: Long,
    val deciding: Boolean = false,
    val restoredFromProcess: Boolean = false,
) {
    companion object {
        fun from(request: ApprovalRequestRecord): AgentApprovalUiState {
            return create(request, request.arguments)
        }

        fun fromCurrentProcess(
            request: ApprovalRequestRecord,
            toolCall: ToolCall,
        ): AgentApprovalUiState {
            val persistedToolCall = DeviceTypeTextAuditPolicy.toolCallForPersistence(toolCall)
            // long: 当前进程审批卡可以显示未落盘原文，但必须先证明它与 Room 中的请求属于同一 ToolCall 和同一安全投影，不能把另一条调用的参数错配给用户确认。
            require(request.toolCallId == persistedToolCall.id) { "审批请求与当前 ToolCall ID 不一致" }
            require(request.toolName == persistedToolCall.name) { "审批请求与当前工具名称不一致" }
            require(request.risk == persistedToolCall.risk) { "审批请求与当前工具风险不一致" }
            require(request.arguments == persistedToolCall.arguments) { "审批请求与当前工具参数投影不一致" }
            return create(request, toolCall.arguments)
        }

        private fun create(
            request: ApprovalRequestRecord,
            visibleArguments: Map<String, String>,
        ): AgentApprovalUiState {
            return AgentApprovalUiState(
                requestId = request.id,
                runId = request.runId,
                conversationId = request.conversationId,
                toolCallId = request.toolCallId,
                toolName = request.toolName,
                toolDescription = request.toolDescription,
                riskLabel = request.risk.toUiLabel(),
                // long: 当前进程可用内存中的原始参数帮助用户核对真实副作用；默认值始终来自 Room 安全投影，历史记录和进程恢复不能重新获得未持久化原文。
                arguments = visibleArguments.toMap(),
                expiresAt = request.expiresAt,
            )
        }
    }
}
