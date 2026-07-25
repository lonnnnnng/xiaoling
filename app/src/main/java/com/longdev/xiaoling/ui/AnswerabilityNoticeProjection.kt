package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice

internal fun XiaoLingUiState.withAnswerabilityNotice(
    persistedMessageId: String,
    notice: KnowledgeAnswerabilityUserNotice,
): XiaoLingUiState {
    val messageStillExists = conversations.any { conversation ->
        conversation.messages.any { message -> message.id == persistedMessageId }
    }
    if (!messageStillExists) return this
    // long: notice 是独立的消息级 UI 投影；这里只复制映射，答案正文、可信 Agent 上下文和引用快照都保持原对象与原顺序。
    return copy(
        answerabilityNotices = answerabilityNotices + (persistedMessageId to notice),
    ).pruneAnswerabilityNotices()
}

internal fun XiaoLingUiState.withoutAnswerabilityNoticesForConversation(
    conversationId: String,
): XiaoLingUiState {
    val deletedMessageIds = conversations
        .firstOrNull { it.id == conversationId }
        ?.messages
        ?.mapTo(hashSetOf(), ChatMessage::id)
        .orEmpty()
    if (deletedMessageIds.isEmpty()) return this
    // long: 会话删除一开始就撤销其消息级提示，避免异步加载替代会话期间继续持有已删除消息的旁路状态。
    return copy(
        answerabilityNotices = answerabilityNotices.filterKeys { it !in deletedMessageIds },
    )
}

internal fun XiaoLingUiState.pruneAnswerabilityNotices(): XiaoLingUiState {
    if (answerabilityNotices.isEmpty()) return this
    val liveMessageIds = conversations
        .asSequence()
        .flatMap { it.messages.asSequence() }
        .mapTo(hashSetOf(), ChatMessage::id)
    val retained = answerabilityNotices.filterKeys(liveMessageIds::contains)
    if (retained.size == answerabilityNotices.size) return this
    // long: notice 不持久化且只能依附仍存在的消息；会话重载、折叠或删除后必须裁剪悬空键，不能让进程内 Map 无限积累历史状态。
    return copy(answerabilityNotices = retained)
}
