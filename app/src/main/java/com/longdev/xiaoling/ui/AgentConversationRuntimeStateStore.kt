package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunSnapshot

internal data class AgentConversationRuntimeState(
    val activeRun: AgentRunSnapshot? = null,
    val pendingApproval: AgentApprovalUiState? = null,
)

internal class AgentConversationRuntimeStateStore {
    private val states = mutableMapOf<String, AgentConversationRuntimeState>()

    fun rememberRun(snapshot: AgentRunSnapshot) {
        val conversationId = snapshot.run.conversationId
        // long: Run 卡片属于创建它的会话；按会话替换可避免后台 Run 更新时覆盖用户正在查看的另一个会话。
        states[conversationId] = stateFor(conversationId).copy(activeRun = snapshot)
    }

    fun rememberApproval(approval: AgentApprovalUiState) {
        val conversationId = approval.conversationId
        // long: 审批的等待与决策状态必须和所属 Run 一起留在原会话，用户切换页面时只投影当前会话的安全闸口。
        states[conversationId] = stateFor(conversationId).copy(pendingApproval = approval)
    }

    fun clearApproval(conversationId: String) {
        val current = states[conversationId] ?: return
        states[conversationId] = current.copy(pendingApproval = null)
    }

    fun clearConversation(conversationId: String) {
        states.remove(conversationId)
    }

    fun stateForSelection(
        conversationId: String,
        restoreRuntimeState: Boolean,
    ): AgentConversationRuntimeState {
        if (!restoreRuntimeState) {
            // long: 新占位即使因时钟回拨复用了旧 ID，也必须先清掉同 ID 的 Run/审批，避免后续 Run 更新把旧审批重新带回界面。
            clearConversation(conversationId)
            return AgentConversationRuntimeState()
        }
        return stateFor(conversationId)
    }

    fun stateFor(conversationId: String): AgentConversationRuntimeState {
        return states[conversationId] ?: AgentConversationRuntimeState()
    }
}
