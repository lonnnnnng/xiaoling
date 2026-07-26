package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.ApprovalDecision
import kotlinx.coroutines.CompletableDeferred

internal class AgentApprovalDecisionTicket internal constructor(
    val requestId: String,
    val conversationId: String,
    private val decision: CompletableDeferred<ApprovalDecision> = CompletableDeferred(),
) {
    val isCancelled: Boolean
        get() = decision.isCancelled

    val isCompleted: Boolean
        get() = decision.isCompleted

    suspend fun awaitDecision(): ApprovalDecision = decision.await()

    internal fun complete(value: ApprovalDecision): Boolean = decision.complete(value)

    internal fun cancel() {
        decision.cancel()
    }
}

internal class AgentApprovalDecisionClaim internal constructor(
    internal val ticket: AgentApprovalDecisionTicket,
)

internal class AgentApprovalDecisionCoordinator {
    private var activeTicket: AgentApprovalDecisionTicket? = null
    private var activeClaim: AgentApprovalDecisionClaim? = null

    fun register(
        requestId: String,
        conversationId: String,
    ): AgentApprovalDecisionTicket {
        // long: 新 Run 注册审批时先取消旧等待；旧协程随后收尾只能处理自己的 ticket，不能继续占用全局安全闸口。
        activeTicket?.cancel()
        return AgentApprovalDecisionTicket(
            requestId = requestId,
            conversationId = conversationId,
        ).also { ticket ->
            activeTicket = ticket
            activeClaim = null
        }
    }

    fun claim(requestId: String): AgentApprovalDecisionClaim? {
        val ticket = activeTicket ?: return null
        if (ticket.requestId != requestId || ticket.isCompleted || activeClaim != null) return null
        // long: 一次审批只允许一个 Room 写入者领取决策权，防止批准和拒绝在按钮禁用前同时提交。
        return AgentApprovalDecisionClaim(ticket).also { claim ->
            activeClaim = claim
        }
    }

    fun complete(
        claim: AgentApprovalDecisionClaim,
        decision: ApprovalDecision,
    ): Boolean {
        // long: Room 成功只能完成发起该写入的当前 claim；停止或新审批注册后到达的旧结果必须被丢弃。
        if (!isCurrentClaim(claim)) return false
        activeClaim = null
        activeTicket = null
        return claim.ticket.complete(decision)
    }

    fun release(claim: AgentApprovalDecisionClaim): Boolean {
        if (!isCurrentClaim(claim)) return false
        // long: 审批状态写入失败只撤销本次领取，不结束等待；用户可在同一安全闸口重新提交决定。
        activeClaim = null
        return true
    }

    fun cancel(claim: AgentApprovalDecisionClaim): Boolean {
        if (!isCurrentClaim(claim)) return false
        // long: Room 已表明审批不再可决定时必须取消等待，不能把未持久化的批准交给 Runtime 执行真实工具。
        claim.ticket.cancel()
        activeClaim = null
        return true
    }

    fun clear(ticket: AgentApprovalDecisionTicket): Boolean {
        // long: waiter 的 finally 只清理自己注册的审批，防止旧 Run 收尾把后来 Run 的安全闸口一起移除。
        if (activeTicket !== ticket) return false
        ticket.cancel()
        activeClaim = null
        activeTicket = null
        return true
    }

    fun cancelActive(): Boolean {
        val ticket = activeTicket ?: return false
        if (ticket.isCompleted) return false
        // long: 用户停止生成后立刻让审批等待和已领取决定失效，迟到的 Room 返回不能再次放行工具执行。
        ticket.cancel()
        activeClaim = null
        return true
    }

    private fun isCurrentClaim(claim: AgentApprovalDecisionClaim): Boolean {
        return activeTicket === claim.ticket && activeClaim === claim
    }
}
