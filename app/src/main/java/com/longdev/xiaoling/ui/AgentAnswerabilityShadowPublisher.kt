package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityFrozenBinding
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowCandidate
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationMode
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOrigin
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOutcome
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationRequest
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSkipReason
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistenceMode
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleEvent
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowSampleKind
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeFailureKind
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import kotlinx.coroutines.CancellationException

internal data class AgentAnswerabilityShadowPublishRequest(
    val persistedMessageId: String,
    val candidate: KnowledgeAnswerabilityShadowCandidate?,
    val frozenBinding: KnowledgeAnswerabilityFrozenBinding?,
    val mode: KnowledgeAnswerabilityShadowObservationMode = KnowledgeAnswerabilityShadowObservationMode.DISABLED,
    val origin: KnowledgeAnswerabilityShadowObservationOrigin,
) {
    init {
        require(persistedMessageId.isNotBlank()) { "Agent answerability shadow 消息 ID 不能为空" }
    }
}

/**
 * long: Agent 答案发布器只在最终消息已经成功落库后启动旁路观测；它不持有或改写消息，因此 Room/Judge 失败都无法反向改变答案与引用。
 */
internal class AgentAnswerabilityShadowPublisher(
    private val observe: suspend (KnowledgeAnswerabilityShadowObservationRequest) ->
        KnowledgeAnswerabilityShadowObservationOutcome,
    private val publishNotice: (messageId: String, notice: KnowledgeAnswerabilityUserNotice) -> Unit,
    private val publishSample: (KnowledgeAnswerabilityShadowSampleEvent) -> Unit = {},
) {
    suspend fun publish(
        request: AgentAnswerabilityShadowPublishRequest,
        awaitAnswerPersistence: suspend () -> Boolean,
        isStillEnabled: () -> Boolean = { true },
    ) {
        if (request.mode == KnowledgeAnswerabilityShadowObservationMode.DISABLED) {
            recordSample(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.DISABLED))
            return
        }
        val candidate = request.candidate ?: run {
            recordSample(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.CANDIDATE_MISSING))
            return
        }

        val persisted = try {
            awaitAnswerPersistence()
        } catch (error: CancellationException) {
            recordSample(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.CANCELLED))
            throw error
        } catch (_: Exception) {
            // long: 最终答案持久化失败时只放弃 shadow 请求；已经展示并完成的 Agent Run 不能被旁路保存异常改判失败。
            false
        }
        if (!persisted) {
            recordSample(
                KnowledgeAnswerabilityShadowSampleEvent(
                    KnowledgeAnswerabilityShadowSampleKind.ANSWER_PERSISTENCE_FAILED,
                ),
            )
            return
        }
        if (!isStillEnabled()) {
            // long: 用户在 Judge 真正发出前关闭开关即撤销本次旁路授权；答案已保存，但不得继续发送问题和候选正文。
            recordSample(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.CANCELLED))
            return
        }

        val outcome = try {
            observe(
                KnowledgeAnswerabilityShadowObservationRequest(
                    persistedMessageId = request.persistedMessageId,
                    candidate = candidate,
                    frozenBinding = request.frozenBinding,
                    mode = request.mode,
                    origin = request.origin,
                    persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
                ),
            )
        } catch (error: CancellationException) {
            recordSample(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.CANCELLED))
            throw error
        } catch (_: Exception) {
            // long: Provider adapter 或协调器意外失败时不生成猜测 notice，更不能触碰已经发布的答案和引用。
            recordSample(
                KnowledgeAnswerabilityShadowSampleEvent(
                    kind = KnowledgeAnswerabilityShadowSampleKind.UNEXPECTED,
                    failureKind = KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED,
                ),
            )
            return
        }
        recordSample(
            KnowledgeAnswerabilityShadowSampleEvent(
                kind = when (outcome.status) {
                    KnowledgeAnswerabilityShadowObservationStatus.COMPLETED -> KnowledgeAnswerabilityShadowSampleKind.COMPLETED
                    KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN -> KnowledgeAnswerabilityShadowSampleKind.UNKNOWN
                    KnowledgeAnswerabilityShadowObservationStatus.SKIPPED -> when (outcome.skipReason) {
                        KnowledgeAnswerabilityShadowSkipReason.DISABLED -> KnowledgeAnswerabilityShadowSampleKind.DISABLED
                        KnowledgeAnswerabilityShadowSkipReason.UNSUPPORTED_ORIGIN -> KnowledgeAnswerabilityShadowSampleKind.UNSUPPORTED_ORIGIN
                        null -> KnowledgeAnswerabilityShadowSampleKind.UNEXPECTED
                    }
                },
                outcome = outcome,
            ),
        )
        // long: Judge 终败、候选无效或冻结绑定缺失只代表本次旁路没有形成真实测量；这些 UNKNOWN 终态不能制造用户提示。
        if (outcome.status != KnowledgeAnswerabilityShadowObservationStatus.COMPLETED) return
        outcome.binding?.notice?.let { notice ->
            publishNotice(request.persistedMessageId, notice)
        }
    }

    private fun recordSample(event: KnowledgeAnswerabilityShadowSampleEvent) {
        // long: 遥测 sink 也是旁路能力；即使设置页状态更新失败，也不能中断 Judge 或影响已经保存的答案。
        runCatching { publishSample(event) }
    }
}
