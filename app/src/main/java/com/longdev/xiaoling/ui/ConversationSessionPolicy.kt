package com.longdev.xiaoling.ui

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
        return copy(conversations = updatedConversations)
    }
    return copy(
        conversations = updatedConversations,
        selectedConversationId = currentId,
        conversationTitle = title,
        conversationSummary = summary,
        chatMessages = messages,
    )
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
