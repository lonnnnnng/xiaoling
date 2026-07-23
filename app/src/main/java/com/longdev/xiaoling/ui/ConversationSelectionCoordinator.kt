package com.longdev.xiaoling.ui

import kotlinx.coroutines.Job

internal sealed interface ConversationSelectionEvent {
    data class DeletionStarted(
        val conversationId: String,
    ) : ConversationSelectionEvent

    data class Immediate(
        val selection: ConversationSelectionPlan.Immediate,
    ) : ConversationSelectionEvent

    data class Load(
        val event: ConversationLoadEvent,
    ) : ConversationSelectionEvent
}

internal class ConversationSelectionCoordinator(
    private val persistenceCoordinator: ConversationPersistenceCoordinator,
    private val loadCoordinator: ConversationLoadCoordinator,
) {
    fun openNew(
        state: XiaoLingUiState,
        onEvent: (ConversationSelectionEvent) -> Unit,
    ) {
        loadCoordinator.cancelPendingLoad()
        onEvent(ConversationSelectionEvent.Immediate(state.planOpenNewConversation()))
    }

    fun select(
        state: XiaoLingUiState,
        conversationId: String,
        onEvent: (ConversationSelectionEvent) -> Unit,
    ): Job? {
        val conversation = state.conversations.firstOrNull { it.id == conversationId } ?: return null
        return loadSelection(
            conversation = conversation,
            conversations = state.conversations,
            result = null,
            deletionIntentToRollback = null,
            onEvent = onEvent,
        )
    }

    fun deleteCurrent(
        state: XiaoLingUiState,
        onEvent: (ConversationSelectionEvent) -> Unit,
    ): Job? {
        loadCoordinator.cancelPendingLoad()
        val currentId = state.selectedConversationId
        val deletionIntent = persistenceCoordinator.markConversationDeleted(currentId)
        // long: 运行态清理必须发生在任何新会话投影或 Loading 之前，避免已删除会话的审批卡片短暂挂到替代会话上。
        onEvent(ConversationSelectionEvent.DeletionStarted(currentId))
        return when (val selection = state.planCurrentConversationDeletion()) {
            is ConversationSelectionPlan.Immediate -> {
                onEvent(ConversationSelectionEvent.Immediate(selection))
                null
            }

            is ConversationSelectionPlan.Load -> loadSelection(
                conversation = selection.conversation,
                conversations = selection.conversations,
                result = OperationResult(true, "已删除", "当前会话已删除"),
                deletionIntentToRollback = deletionIntent,
                onEvent = onEvent,
            )
        }
    }

    private fun loadSelection(
        conversation: ConversationSession,
        conversations: List<ConversationSession>,
        result: OperationResult?,
        deletionIntentToRollback: ConversationDeletionIntent?,
        onEvent: (ConversationSelectionEvent) -> Unit,
    ): Job {
        val request = ConversationLoadRequest(
            conversation = conversation,
            conversations = conversations,
            result = result,
        )
        return loadCoordinator.load(request) { event ->
            if (event is ConversationLoadEvent.Failed) {
                // long: 只有当前加载代次会到达这里；先回滚本次捕获的删除代次，再发布失败状态，保证后续保存不会误删原会话。
                deletionIntentToRollback?.let(persistenceCoordinator::rollbackConversationDeletion)
            }
            onEvent(ConversationSelectionEvent.Load(event))
        }
    }
}
