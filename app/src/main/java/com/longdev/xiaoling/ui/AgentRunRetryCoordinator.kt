package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.model.MessageAttachmentSelection
import com.longdev.xiaoling.ui.agenttask.AgentRetryConfirmationUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class AgentRunRetryLaunchRequest(
    val userMessage: String,
    val conversationId: String,
    val retryOfRunId: String,
    val attachments: MessageAttachmentSelection,
)

internal sealed interface AgentRunRetryEvent {
    data class ConfirmationRequired(
        val confirmation: AgentRetryConfirmationUiState,
    ) : AgentRunRetryEvent

    data class ConfirmationRefreshed(
        val confirmation: AgentRetryConfirmationUiState,
    ) : AgentRunRetryEvent

    data class PreparationRequired(
        val detail: AgentRunDetailRecord,
    ) : AgentRunRetryEvent

    data class RetryStarting(
        val runId: String,
        val conversationId: String,
    ) : AgentRunRetryEvent

    data class RetryReady(
        val request: AgentRunRetryLaunchRequest,
    ) : AgentRunRetryEvent

    data class Failed(
        val runId: String,
        val message: String,
    ) : AgentRunRetryEvent

    data class Cancelled(
        val runId: String,
    ) : AgentRunRetryEvent
}

internal class AgentRunRetryCoordinator(
    private val scope: CoroutineScope,
    private val loadSourceAttachments: suspend (AgentRunDetailRecord) -> MessageAttachmentSelection,
) {
    fun request(
        runId: String,
        runHistory: List<AgentRunDetailRecord>,
        busy: Boolean,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ) {
        if (busy) {
            onEvent(AgentRunRetryEvent.Failed(runId, "当前已有任务正在执行，请等待结束后再重试"))
            return
        }
        val detail = runHistory.firstOrNull { it.snapshot.run.id == runId }
        if (detail == null) {
            onEvent(AgentRunRetryEvent.Failed(runId, "找不到要重试的 Agent Run，请刷新任务中心"))
            return
        }
        when (val eligibility = AgentTaskRetryPolicy.evaluate(detail)) {
            AgentTaskRetryEligibility.NotRetryable -> {
                onEvent(AgentRunRetryEvent.Failed(runId, "当前状态不支持重试"))
            }
            is AgentTaskRetryEligibility.Retryable -> {
                if (eligibility.requiresConfirmation) {
                    val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
                    onEvent(
                        AgentRunRetryEvent.ConfirmationRequired(
                            AgentRetryConfirmationUiState(
                                runId = runId,
                                goal = detail.snapshot.run.goal,
                                evidenceCode = evidence.code,
                                evidenceFingerprint = evidence.fingerprint,
                            ),
                        ),
                    )
                } else {
                    onEvent(AgentRunRetryEvent.PreparationRequired(detail))
                }
            }
        }
    }

    fun confirm(
        pending: AgentRetryConfirmationUiState,
        runHistory: List<AgentRunDetailRecord>,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ) {
        val detail = runHistory.firstOrNull { it.snapshot.run.id == pending.runId }
        if (detail == null) {
            onEvent(AgentRunRetryEvent.Failed(pending.runId, "来源 Agent Run 已不存在，请刷新任务中心"))
            return
        }
        if (AgentTaskRetryPolicy.evaluate(detail) is AgentTaskRetryEligibility.NotRetryable) {
            onEvent(AgentRunRetryEvent.Failed(pending.runId, "当前状态已变化，请刷新任务中心"))
            return
        }
        if (!AgentTaskRetryPolicy.canConfirmRetry(
                expectedEvidenceCode = pending.evidenceCode,
                detail = detail,
                expectedEvidenceFingerprint = pending.evidenceFingerprint,
            )
        ) {
            val currentEvidence = AgentTaskRetryPolicy.assessEvidence(detail)
            // long: 用户确认只授权弹窗打开时看到的副作用证据；分类相同但账本内容漂移也必须刷新确认，不能把旧授权套到新证据上。
            onEvent(
                AgentRunRetryEvent.ConfirmationRefreshed(
                    pending.copy(
                        evidenceCode = currentEvidence.code,
                        evidenceFingerprint = currentEvidence.fingerprint,
                    ),
                ),
            )
            return
        }
        onEvent(AgentRunRetryEvent.PreparationRequired(detail))
    }

    fun cancel(
        runId: String,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ) {
        onEvent(AgentRunRetryEvent.Cancelled(runId))
    }

    fun prepare(
        detail: AgentRunDetailRecord,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ): Job {
        val sourceRun = detail.snapshot.run
        onEvent(
            AgentRunRetryEvent.RetryStarting(
                runId = sourceRun.id,
                conversationId = sourceRun.conversationId,
            ),
        )
        return scope.launch {
            val attachments = try {
                loadSourceAttachments(detail)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onEvent(
                    AgentRunRetryEvent.Failed(
                        runId = sourceRun.id,
                        message = error.message ?: "读取原任务附件失败",
                    ),
                )
                return@launch
            }
            // long: 新 Run 只接收不可变的来源身份和附件快照；协调器不写旧 Run，确保审计历史始终保留原失败终态。
            onEvent(
                AgentRunRetryEvent.RetryReady(
                    AgentRunRetryLaunchRequest(
                        userMessage = "/agent ${sourceRun.goal}",
                        conversationId = sourceRun.conversationId,
                        retryOfRunId = sourceRun.id,
                        attachments = attachments,
                    ),
                ),
            )
        }
    }
}
