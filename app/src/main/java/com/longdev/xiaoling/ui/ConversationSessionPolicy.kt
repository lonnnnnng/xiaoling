package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunSnapshot

internal fun List<ChatMessage>.firstUserTitle(): String {
    // long: 会话标题只看第一条 role=user 消息；其正文为空时保持“新会话”，不能跳过它用后续输入改写历史命名语义。
    return firstOrNull { it.role == "user" }
        ?.text
        ?.trim()
        ?.take(CONVERSATION_TITLE_MAX_CHARS)
        ?.ifBlank { null }
        ?: NEW_CONVERSATION_TITLE
}

internal fun List<ConversationSession>.collapseDuplicateEmptyConversations(
    preferredId: String,
): List<ConversationSession> {
    val realConversations = filter { it.messages.isNotEmpty() }
    val emptyConversations = filter { it.messages.isEmpty() }
    val keptEmptyConversation = emptyConversations
        .firstOrNull { it.id == preferredId }
        ?: emptyConversations.maxByOrNull { it.updatedAt }
    // long: 空白会话只是“准备输入”的占位，不承载业务记录；只保留当前占位或最新占位，不能影响任何已有消息的真实会话。
    return if (keptEmptyConversation == null) {
        realConversations
    } else {
        realConversations + keptEmptyConversation
    }
}

internal sealed interface ConversationSelectionPlan {
    val conversations: List<ConversationSession>
    val conversation: ConversationSession

    data class Immediate(
        override val conversations: List<ConversationSession>,
        override val conversation: ConversationSession,
        val restoreRuntimeState: Boolean,
    ) : ConversationSelectionPlan

    data class Load(
        override val conversations: List<ConversationSession>,
        override val conversation: ConversationSession,
    ) : ConversationSelectionPlan
}

internal fun XiaoLingUiState.planOpenNewConversation(
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): ConversationSelectionPlan.Immediate {
    val lightweightConversations = conversations.map(ConversationSession::withoutBinaryPayloads)
    val current = lightweightConversations.firstOrNull { it.id == selectedConversationId }
    if (current != null && current.messages.isEmpty()) {
        // long: 当前已经是输入占位时只复用它，不擅自折叠用户仍可见的其他空会话，保持原有“新建”按钮幂等语义。
        return ConversationSelectionPlan.Immediate(
            conversations = lightweightConversations,
            conversation = current,
            restoreRuntimeState = true,
        )
    }

    val reusableEmptyConversation = lightweightConversations
        .filter { it.messages.isEmpty() }
        .maxByOrNull { it.updatedAt }
    if (reusableEmptyConversation != null) {
        // long: 从已有内容会话切到新输入时只保留最新空占位，避免重复草稿出现在会话列表中。
        return ConversationSelectionPlan.Immediate(
            conversations = lightweightConversations
                .collapseDuplicateEmptyConversations(reusableEmptyConversation.id),
            conversation = reusableEmptyConversation,
            restoreRuntimeState = true,
        )
    }

    val now = currentTimeMillis()
    val created = newEmptyConversation(now)
    return ConversationSelectionPlan.Immediate(
        conversations = lightweightConversations + created,
        conversation = created,
        restoreRuntimeState = false,
    )
}

internal fun XiaoLingUiState.planCurrentConversationDeletion(
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): ConversationSelectionPlan {
    val remaining = conversations.filterNot { it.id == selectedConversationId }
    if (remaining.isNotEmpty()) {
        // long: 删除当前会话后必须从剩余历史中选择 updatedAt 最新的一项，再由 ViewModel 触发完整消息加载。
        return ConversationSelectionPlan.Load(
            conversations = remaining,
            conversation = remaining.maxBy { it.updatedAt },
        )
    }

    val now = currentTimeMillis()
    val replacement = newEmptyConversation(now)
    // long: 删除最后一个会话仍需留下可输入的本地占位，不能让 Compose 进入无选中会话状态。
    return ConversationSelectionPlan.Immediate(
        conversations = listOf(replacement),
        conversation = replacement,
        restoreRuntimeState = false,
    )
}

internal fun XiaoLingUiState.withImmediateConversationSelection(
    selection: ConversationSelectionPlan.Immediate,
    activeAgentRun: AgentRunSnapshot?,
    pendingAgentApproval: AgentApprovalUiState?,
): XiaoLingUiState {
    val conversation = selection.conversation
    return copy(
        conversations = selection.conversations,
        selectedConversationId = conversation.id,
        conversationTitle = conversation.title,
        conversationSummary = conversation.summary,
        chatMessages = conversation.messages,
        activeAgentRun = activeAgentRun,
        pendingAgentApproval = pendingAgentApproval,
        loadingConversationMessages = false,
        result = null,
    ).pruneAnswerabilityNotices()
}

internal fun XiaoLingUiState.withUpdatedConversation(
    conversationId: String,
    messages: List<ChatMessage>,
    summary: String,
    summaryUntilMessageId: String? = conversations.firstOrNull { it.id == conversationId }?.summaryUntilMessageId,
    summaryUpdatedAt: Long? = conversations.firstOrNull { it.id == conversationId }?.summaryUpdatedAt,
    summaryModel: String? = conversations.firstOrNull { it.id == conversationId }?.summaryModel,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): XiaoLingUiState {
    val now = currentTimeMillis()
    val currentId = conversationId.ifBlank { "conversation-$now" }
    val current = conversations.firstOrNull { it.id == currentId }
    val title = current?.title
        ?.takeUnless { it == NEW_CONVERSATION_TITLE && messages.any { message -> message.role == "user" } }
        ?: messages.firstUserTitle()
    // long: 已存在会话的创建时间属于历史身份，后续消息和摘要更新只能推进 updatedAt，不能把会话伪装成刚创建。
    val updatedConversation = ConversationSession(
        id = currentId,
        title = title,
        summary = summary,
        summaryUntilMessageId = summaryUntilMessageId,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryModel = summaryModel,
        messages = messages,
        createdAt = current?.createdAt ?: now,
        updatedAt = now,
    )
    val updatedConversations = if (conversations.any { it.id == currentId }) {
        conversations.map { if (it.id == currentId) updatedConversation else it }
    } else {
        conversations + updatedConversation
    }.collapseDuplicateEmptyConversations(selectedConversationId.ifBlank { currentId })
    // long: Agent Run 可能在用户切到其他会话后才完成；目标会话应持久更新，但当前屏幕的会话身份和消息绝不能被迟到结果替换。
    if (currentId != selectedConversationId) {
        return copy(conversations = updatedConversations).pruneAnswerabilityNotices()
    }
    return copy(
        conversations = updatedConversations,
        selectedConversationId = currentId,
        conversationTitle = title,
        conversationSummary = summary,
        chatMessages = messages,
    ).pruneAnswerabilityNotices()
}

internal fun XiaoLingUiState.withUpdatedCurrentConversation(
    messages: List<ChatMessage>,
    summary: String,
    summaryUntilMessageId: String? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryUntilMessageId,
    summaryUpdatedAt: Long? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryUpdatedAt,
    summaryModel: String? = conversations.firstOrNull { it.id == selectedConversationId }?.summaryModel,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): XiaoLingUiState {
    // long: 普通聊天增量更新通常不改变摘要边界；默认继承当前会话元数据，只有 preparer 返回新边界时调用方才显式覆盖。
    return withUpdatedConversation(
        conversationId = selectedConversationId,
        messages = messages,
        summary = summary,
        summaryUntilMessageId = summaryUntilMessageId,
        summaryUpdatedAt = summaryUpdatedAt,
        summaryModel = summaryModel,
        currentTimeMillis = currentTimeMillis,
    )
}

private const val CONVERSATION_TITLE_MAX_CHARS = 18
private const val NEW_CONVERSATION_TITLE = "新会话"

private fun newEmptyConversation(now: Long): ConversationSession = ConversationSession(
    id = "conversation-$now",
    title = NEW_CONVERSATION_TITLE,
    summary = "",
    summaryUntilMessageId = null,
    summaryUpdatedAt = null,
    summaryModel = null,
    messages = emptyList(),
    createdAt = now,
    updatedAt = now,
)
