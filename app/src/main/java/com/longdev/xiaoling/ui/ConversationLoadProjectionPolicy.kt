package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.model.MessagePart

internal fun XiaoLingUiState.withConversationLoadEvent(
    event: ConversationLoadEvent,
    activeAgentRun: AgentRunSnapshot? = null,
    pendingAgentApproval: AgentApprovalUiState? = null,
): XiaoLingUiState {
    return when (event) {
        ConversationLoadEvent.Loading -> copy(
            loadingConversationMessages = true,
            result = null,
        )

        is ConversationLoadEvent.Loaded -> {
            val request = event.request
            // long: 非当前会话只保留轻量消息，当前会话必须在同一次状态替换中注入完整 BLOB，避免切换时先显示残缺附件或把旧 BLOB 重新写空。
            val lightweightConversations = request.conversations.map(ConversationSession::withoutBinaryPayloads)
            copy(
                conversations = lightweightConversations.map { item ->
                    if (item.id == request.conversation.id) item.copy(messages = event.messages) else item
                },
                selectedConversationId = request.conversation.id,
                conversationTitle = request.conversation.title,
                conversationSummary = request.conversation.summary,
                chatMessages = event.messages,
                activeAgentRun = activeAgentRun,
                pendingAgentApproval = pendingAgentApproval,
                loadingConversationMessages = false,
                result = request.result,
            )
        }

        is ConversationLoadEvent.Failed -> copy(
            loadingConversationMessages = false,
            result = OperationResult(
                success = false,
                title = "会话读取失败",
                message = event.error.message ?: "无法加载会话消息",
            ),
        )
    }
}

internal fun ConversationSession.withoutBinaryPayloads(): ConversationSession = copy(
    messages = messages.map { message ->
        message.copy(
            // long: 会话索引快照不得长期携带图片或文档字节；文本与可信工具证据继续保留，完整附件只属于当前可见会话。
            parts = message.effectiveParts().filterNot {
                it is MessagePart.Image || it is MessagePart.Document
            },
        )
    },
)
