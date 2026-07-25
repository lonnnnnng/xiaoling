package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityFrozenBinding
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowCandidate
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationMode
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOrigin
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOutcome
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationRequest
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistenceMode
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
) {
    suspend fun publish(
        request: AgentAnswerabilityShadowPublishRequest,
        awaitAnswerPersistence: suspend () -> Boolean,
    ) {
        if (request.mode == KnowledgeAnswerabilityShadowObservationMode.DISABLED) return
        val candidate = request.candidate ?: return

        val persisted = try {
            awaitAnswerPersistence()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // long: 最终答案持久化失败时只放弃 shadow 请求；已经展示并完成的 Agent Run 不能被旁路保存异常改判失败。
            false
        }
        if (!persisted) return

        val outcome = try {
            observe(
                KnowledgeAnswerabilityShadowObservationRequest(
                    persistedMessageId = request.persistedMessageId,
                    candidate = candidate,
                    frozenBinding = request.frozenBinding,
                    mode = request.mode,
                    origin = request.origin,
                    persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.NONE,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // long: Provider adapter 或协调器意外失败时不生成猜测 notice，更不能触碰已经发布的答案和引用。
            return
        }
        // long: Judge 终败、候选无效或冻结绑定缺失只代表本次旁路没有形成真实测量；这些 UNKNOWN 终态不能制造用户提示。
        if (outcome.status != KnowledgeAnswerabilityShadowObservationStatus.COMPLETED) return
        outcome.binding?.notice?.let { notice ->
            publishNotice(request.persistedMessageId, notice)
        }
    }
}
