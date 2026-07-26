package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunResumePolicy
import com.longdev.xiaoling.agent.AgentRunSummary
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.model.MessageAttachmentSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal sealed interface RecoveredAgentApprovalOutcome {
    data class Completed(
        val detail: AgentRunDetailRecord,
        val summary: AgentRunSummary,
    ) : RecoveredAgentApprovalOutcome

    data class Rejected(
        val detail: AgentRunDetailRecord,
    ) : RecoveredAgentApprovalOutcome

    data class StillPending(
        val detail: AgentRunDetailRecord,
        val message: String,
    ) : RecoveredAgentApprovalOutcome

    data class Busy(
        val message: String,
    ) : RecoveredAgentApprovalOutcome

    data class Stale(
        val message: String,
    ) : RecoveredAgentApprovalOutcome

    data class Failed(
        val detail: AgentRunDetailRecord?,
        val message: String,
    ) : RecoveredAgentApprovalOutcome
}

internal data class RecoveredApprovalRejection(
    val requestId: String,
    val runId: String,
    val reason: String,
)

internal class RecoveredAgentApprovalCoordinator(
    private val loadRunDetail: suspend (String) -> AgentRunDetailRecord?,
    private val loadSourceAttachments: suspend (AgentRunDetailRecord) -> MessageAttachmentSelection,
    private val rejectApproval: suspend (RecoveredApprovalRejection) -> AgentRunDetailRecord?,
) {
    private val decisionMutex = Mutex()

    suspend fun approve(
        pending: AgentApprovalUiState,
        resumeRun: suspend (
            AgentRunDetailRecord,
            ApprovalRequestRecord,
            MessageAttachmentSelection,
        ) -> AgentRunSummary,
    ): RecoveredAgentApprovalOutcome {
        if (!decisionMutex.tryLock()) {
            return RecoveredAgentApprovalOutcome.Busy("另一项恢复审批正在处理中")
        }
        return try {
            approveLocked(pending, resumeRun)
        } finally {
            decisionMutex.unlock()
        }
    }

    private suspend fun approveLocked(
        pending: AgentApprovalUiState,
        resumeRun: suspend (
            AgentRunDetailRecord,
            ApprovalRequestRecord,
            MessageAttachmentSelection,
        ) -> AgentRunSummary,
    ): RecoveredAgentApprovalOutcome {
        return try {
            val resolution = resolve(pending)
            if (resolution is Resolution.Stale) {
                return RecoveredAgentApprovalOutcome.Stale(resolution.message)
            }
            resolution as Resolution.Ready
            // long: 原消息附件必须在审批决定写入前完整恢复；读取失败时保留 PENDING，避免用户授权已消费但 Runtime 缺少原始输入。
            val attachments = loadSourceAttachments(resolution.detail)
            val summary = resumeRun(resolution.detail, resolution.approval, attachments)
            RecoveredAgentApprovalOutcome.Completed(resolution.detail, summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            classifyFailure(pending, error.message ?: "恢复 Agent Run 失败")
        }
    }

    suspend fun reject(pending: AgentApprovalUiState): RecoveredAgentApprovalOutcome {
        if (!decisionMutex.tryLock()) {
            return RecoveredAgentApprovalOutcome.Busy("另一项恢复审批正在处理中")
        }
        return try {
            rejectLocked(pending)
        } finally {
            decisionMutex.unlock()
        }
    }

    private suspend fun rejectLocked(pending: AgentApprovalUiState): RecoveredAgentApprovalOutcome {
        return try {
            val resolution = resolve(pending)
            if (resolution is Resolution.Stale) {
                return RecoveredAgentApprovalOutcome.Stale(resolution.message)
            }
            resolution as Resolution.Ready
            val reason = "用户拒绝恢复后的工具执行"
            // long: 协调器只接受 Repository 的原子终态；返回 null 代表证据已过期，不能在这里补写 Run 或猜测部分成功。
            val settled = rejectApproval(
                RecoveredApprovalRejection(
                    requestId = resolution.approval.id,
                    runId = resolution.detail.snapshot.run.id,
                    reason = reason,
                ),
            ) ?: return RecoveredAgentApprovalOutcome.Stale("审批请求已结束，请刷新任务中心")
            RecoveredAgentApprovalOutcome.Rejected(settled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            classifyFailure(pending, error.message ?: "拒绝审批失败")
        }
    }

    private suspend fun resolve(pending: AgentApprovalUiState): Resolution {
        if (!pending.restoredFromProcess) {
            return Resolution.Stale("当前审批不是进程恢复候选")
        }
        val detail = loadRunDetail(pending.runId)
            ?: return Resolution.Stale("找不到待恢复的 Agent Run，请刷新任务中心")
        return validate(pending, detail)
    }

    private fun validate(
        pending: AgentApprovalUiState,
        detail: AgentRunDetailRecord,
    ): Resolution {
        val run = detail.snapshot.run
        if (run.id != pending.runId || run.conversationId != pending.conversationId) {
            return Resolution.Stale("审批请求与原 Agent Run 不一致")
        }
        val assessment = AgentRunResumePolicy.assess(detail)
        if (assessment.kind != AgentRunResumeKind.APPROVAL_WAIT) {
            return Resolution.Stale(assessment.reason)
        }
        val recovery = assessment.approvalWait
            ?: return Resolution.Stale("待恢复审批缺少可验证证据")
        if (recovery.approvalRequestId != pending.requestId) {
            return Resolution.Stale("审批请求已变化，请刷新任务中心")
        }
        val approval = detail.approvals.singleOrNull { it.id == pending.requestId }
            ?: return Resolution.Stale("审批请求已处理，请刷新任务中心")
        if (
            approval.status != ApprovalRequestStatus.PENDING ||
            approval.runId != pending.runId ||
            approval.conversationId != pending.conversationId ||
            approval.toolCallId != pending.toolCallId ||
            approval.toolName != pending.toolName ||
            approval.arguments != pending.arguments
        ) {
            return Resolution.Stale("审批请求证据已变化，请刷新任务中心")
        }
        // long: UI 卡片只负责携带用户看到的身份；真正能否原地恢复由最新 Room detail 和恢复策略共同决定，不能信任进程重建前的内存快照。
        return Resolution.Ready(detail, approval)
    }

    private suspend fun classifyFailure(
        pending: AgentApprovalUiState,
        message: String,
    ): RecoveredAgentApprovalOutcome {
        val latest = try {
            loadRunDetail(pending.runId)
        } catch (_: Throwable) {
            null
        }
        if (latest != null && validate(pending, latest) is Resolution.Ready) {
            return RecoveredAgentApprovalOutcome.StillPending(latest, message)
        }
        return RecoveredAgentApprovalOutcome.Failed(latest, message)
    }

    private sealed interface Resolution {
        data class Ready(
            val detail: AgentRunDetailRecord,
            val approval: ApprovalRequestRecord,
        ) : Resolution

        data class Stale(
            val message: String,
        ) : Resolution
    }
}
