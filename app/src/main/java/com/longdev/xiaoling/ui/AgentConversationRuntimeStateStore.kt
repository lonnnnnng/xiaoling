package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.toSharedRunSnapshot
import com.longdev.xiaoling.shared.agent.SharedAgentRunSession
import com.longdev.xiaoling.shared.agent.SharedAgentRunSnapshot
import com.longdev.xiaoling.shared.agent.SharedAgentRunState

internal data class AgentConversationRuntimeState(
    val activeRun: AgentRunSnapshot? = null,
    val pendingApproval: AgentApprovalUiState? = null,
    val sharedSessionSnapshot: SharedAgentRunSnapshot? = null,
)

internal class AgentConversationRuntimeStateStore {
    private val states = mutableMapOf<String, AgentConversationRuntimeState>()

    fun rememberRun(snapshot: AgentRunSnapshot) {
        val conversationId = snapshot.run.conversationId
        // long: Run 卡片属于创建它的会话；按会话替换可避免后台 Run 更新时覆盖用户正在查看的另一个会话。
        val current = stateFor(conversationId)
        val sharedSessionSnapshot = snapshot.toSharedRunSnapshotOrNull()
        states[conversationId] = current.copy(
            activeRun = snapshot,
            sharedSessionSnapshot = sharedSessionSnapshot,
            pendingApproval = current.pendingApproval.takeUnless { sharedSessionSnapshot?.isCancellationState() == true },
        )
    }

    fun attachRecoveredRun(snapshot: AgentRunSnapshot): Boolean {
        val sharedSnapshot = snapshot.toSharedRunSnapshotOrNull() ?: return false
        // long: Activity 重建只接受能按 Room 账本恢复为连续 shared Session 的 Run；事件混链或序号投影失败时不展示审批入口。
        runCatching { SharedAgentRunSession.restore(sharedSnapshot) }.getOrNull() ?: return false
        val conversationId = snapshot.run.conversationId
        val current = stateFor(conversationId)
        states[conversationId] = current.copy(
            activeRun = snapshot,
            sharedSessionSnapshot = sharedSnapshot,
            pendingApproval = current.pendingApproval.takeUnless { sharedSnapshot.isCancellationState() },
        )
        return true
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

    private fun AgentRunSnapshot.toSharedRunSnapshotOrNull(): SharedAgentRunSnapshot? =
        runCatching { toSharedRunSnapshot() }.getOrNull()

    private fun SharedAgentRunSnapshot.isCancellationState(): Boolean =
        state == SharedAgentRunState.CANCEL_REQUESTED ||
            state == SharedAgentRunState.CANCELLED
}
