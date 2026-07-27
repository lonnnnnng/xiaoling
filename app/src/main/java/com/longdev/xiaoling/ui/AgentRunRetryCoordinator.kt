package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualification
import com.longdev.xiaoling.agent.AgentNotCommittedReplayQualificationAssessment
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRestartDispositionCode
import com.longdev.xiaoling.agent.AgentTaskRetryEligibility
import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.latestRecoveryMetadata
import com.longdev.xiaoling.model.MessageAttachmentSelection
import com.longdev.xiaoling.ui.agenttask.AgentRetryConfirmationKind
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
    val controlledReplayQualification: AgentNotCommittedReplayQualification? = null,
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
        val controlledReplayQualification: AgentNotCommittedReplayQualification? = null,
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
    private val loadRunDetail: suspend (String) -> AgentRunDetailRecord?,
    private val requalifyNotCommittedReplay: (AgentRunDetailRecord) ->
        AgentNotCommittedReplayQualificationAssessment,
    private val loadSourceAttachments: suspend (AgentRunDetailRecord) -> MessageAttachmentSelection,
) {
    fun request(
        runId: String,
        busy: Boolean,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ): Job = scope.launch {
        if (busy) {
            onEvent(AgentRunRetryEvent.Failed(runId, "当前已有任务正在执行，请等待结束后再重试"))
            return@launch
        }
        val detail = loadRunDetailSafely(runId, "找不到要重试的 Agent Run，请刷新任务中心", onEvent)
            ?: return@launch
        when (val eligibility = AgentTaskRetryPolicy.evaluate(detail)) {
            AgentTaskRetryEligibility.NotRetryable -> onEvent(
                AgentRunRetryEvent.Failed(runId, "当前状态不支持重试"),
            )
            is AgentTaskRetryEligibility.Retryable -> {
                val dispositionCode = detail.latestRestartDispositionCode()
                if (dispositionCode == AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE) {
                    when (val assessment = requalifyNotCommittedReplay(detail)) {
                        is AgentNotCommittedReplayQualificationAssessment.Ineligible -> onEvent(
                            AgentRunRetryEvent.Failed(
                                runId,
                                "受控重放资格已失效：${assessment.reason}",
                            ),
                        )
                        is AgentNotCommittedReplayQualificationAssessment.Eligible -> {
                            val evidence = AgentTaskRetryPolicy.assessEvidence(detail)
                            // long: 资格重核只允许打开一次“创建受控关联 Run”的确认，不能把用户确认当成新 Run 内工具审批。
                            onEvent(
                                AgentRunRetryEvent.ConfirmationRequired(
                                    AgentRetryConfirmationUiState(
                                        runId = runId,
                                        goal = detail.snapshot.run.goal,
                                        evidenceCode = evidence.code,
                                        evidenceFingerprint = evidence.fingerprint,
                                        kind = AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY,
                                        expectedRestartDispositionCode = dispositionCode,
                                    ),
                                ),
                            )
                        }
                    }
                    return@launch
                }
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
        onEvent: (AgentRunRetryEvent) -> Unit,
    ): Job = scope.launch {
        val detail = loadRunDetailSafely(
            pending.runId,
            "来源 Agent Run 已不存在，请刷新任务中心",
            onEvent,
        ) ?: return@launch
        if (AgentTaskRetryPolicy.evaluate(detail) is AgentTaskRetryEligibility.NotRetryable) {
            onEvent(AgentRunRetryEvent.Failed(pending.runId, "当前状态已变化，请刷新任务中心"))
            return@launch
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
            return@launch
        }
        if (pending.kind == AgentRetryConfirmationKind.NOT_COMMITTED_CONTROLLED_REPLAY) {
            if (
                pending.expectedRestartDispositionCode !=
                AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE ||
                detail.latestRestartDispositionCode() != pending.expectedRestartDispositionCode
            ) {
                onEvent(AgentRunRetryEvent.Failed(pending.runId, "受控重放资格已变化，请重新发起重试"))
                return@launch
            }
            when (val assessment = requalifyNotCommittedReplay(detail)) {
                is AgentNotCommittedReplayQualificationAssessment.Ineligible -> onEvent(
                    AgentRunRetryEvent.Failed(
                        pending.runId,
                        "受控重放资格已失效：${assessment.reason}",
                    ),
                )
                is AgentNotCommittedReplayQualificationAssessment.Eligible -> onEvent(
                    AgentRunRetryEvent.PreparationRequired(
                        detail = detail,
                        controlledReplayQualification = assessment.qualification,
                    ),
                )
            }
            return@launch
        }
        onEvent(AgentRunRetryEvent.PreparationRequired(detail))
    }

    private suspend fun loadRunDetailSafely(
        runId: String,
        missingMessage: String,
        onEvent: (AgentRunRetryEvent) -> Unit,
    ): AgentRunDetailRecord? {
        return try {
            loadRunDetail(runId) ?: run {
                onEvent(AgentRunRetryEvent.Failed(runId, missingMessage))
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onEvent(AgentRunRetryEvent.Failed(runId, error.message ?: "读取 Agent Run 失败"))
            null
        }
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
        controlledReplayQualification: AgentNotCommittedReplayQualification? = null,
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
                        controlledReplayQualification = controlledReplayQualification,
                    ),
                ),
            )
        }
    }
}

private fun AgentRunDetailRecord.latestRestartDispositionCode(): AgentRunRestartDispositionCode? {
    return latestRecoveryMetadata()?.restartDisposition?.code
}
