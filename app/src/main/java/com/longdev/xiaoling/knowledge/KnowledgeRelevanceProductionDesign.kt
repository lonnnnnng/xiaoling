package com.longdev.xiaoling.knowledge

enum class KnowledgeRelevanceProductionDisposition {
    KEEP_CURRENT_RESULTS,
    DROP_SEMANTIC_KEEP_LEXICAL,
    DROP_SEMANTIC_NO_LEXICAL,
}

enum class KnowledgeRelevanceProductionReason {
    GATE_DISABLED,
    ACCEPTED_ABOVE_FROZEN_THRESHOLD,
    BELOW_FROZEN_THRESHOLD,
    NON_SEMANTIC_RESULT,
    IDENTITY_MISMATCH,
    SCORE_UNKNOWN,
    SCORE_NON_FINITE,
}

data class KnowledgeRelevanceProductionDecision(
    val disposition: KnowledgeRelevanceProductionDisposition,
    val reason: KnowledgeRelevanceProductionReason,
    val enforcementEnabled: Boolean,
    val semanticRejectionWouldApply: Boolean,
    val preserveLexicalFallback: Boolean,
)

/**
 * 第 87 阶段只描述生产拒绝的可审计边界，不接入检索执行链。
 *
 * long: 通过 final holdout 只获得评审资格；在用户可见回退、灰度和撤销方案验收前，生产必须保持开关关闭，且未知事实始终 fail-open。
 */
class KnowledgeRelevanceProductionDesignPolicy(
    private val frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
    private val enforcementEnabled: Boolean = false,
) {
    init {
        validateFrozenGate(frozenGate)
    }

    fun evaluate(
        retrieval: KnowledgeRetrievalRecord,
        lexicalHitCount: Int,
    ): KnowledgeRelevanceProductionDecision {
        require(lexicalHitCount >= 0) { "生产相关性设计的词法命中数不能小于 0" }
        if (retrieval.embeddingStatus != KnowledgeEmbeddingStatus.USED) {
            // long: 没有可比较的语义候选时不能把词法命中误判成语义通过或拒绝，现有词法结果必须原样保留。
            return decision(
                disposition = KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS,
                reason = KnowledgeRelevanceProductionReason.NON_SEMANTIC_RESULT,
                semanticRejectionWouldApply = false,
                preserveLexicalFallback = true,
            )
        }
        val expectedIdentity = frozenGate.calibrationIdentity
        if (retrieval.embeddingProviderId != expectedIdentity.providerId ||
            retrieval.embeddingModel != expectedIdentity.model
        ) {
            // long: Provider 或模型漂移时冻结阈值没有可证明的适用范围；宁可保留候选，也不能静默扩大拒绝面。
            return decision(
                disposition = KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS,
                reason = KnowledgeRelevanceProductionReason.IDENTITY_MISMATCH,
                semanticRejectionWouldApply = false,
                preserveLexicalFallback = true,
            )
        }
        val topScore = retrieval.embeddingTopScore
            ?: return decision(
                disposition = KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS,
                reason = KnowledgeRelevanceProductionReason.SCORE_UNKNOWN,
                semanticRejectionWouldApply = false,
                preserveLexicalFallback = true,
            )
        if (!topScore.isFinite()) {
            return decision(
                disposition = KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS,
                reason = KnowledgeRelevanceProductionReason.SCORE_NON_FINITE,
                semanticRejectionWouldApply = false,
                preserveLexicalFallback = true,
            )
        }
        if (topScore >= frozenGate.minimumRawTopScore) {
            return decision(
                disposition = KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS,
                reason = KnowledgeRelevanceProductionReason.ACCEPTED_ABOVE_FROZEN_THRESHOLD,
                semanticRejectionWouldApply = false,
                preserveLexicalFallback = false,
            )
        }
        // long: 低于冻结下限时只计划移除语义候选；若有词法命中，必须把词法结果交给上层，避免把“语义不确定”扩大成“知识为空”。
        val disposition = if (enforcementEnabled) {
            if (lexicalHitCount > 0) {
                KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL
            } else {
                KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL
            }
        } else {
            KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS
        }
        return decision(
            disposition = disposition,
            reason = if (enforcementEnabled) {
                KnowledgeRelevanceProductionReason.BELOW_FROZEN_THRESHOLD
            } else {
                KnowledgeRelevanceProductionReason.GATE_DISABLED
            },
            semanticRejectionWouldApply = true,
            preserveLexicalFallback = lexicalHitCount > 0,
        )
    }

    private fun decision(
        disposition: KnowledgeRelevanceProductionDisposition,
        reason: KnowledgeRelevanceProductionReason,
        semanticRejectionWouldApply: Boolean,
        preserveLexicalFallback: Boolean,
    ) = KnowledgeRelevanceProductionDecision(
        disposition = disposition,
        reason = reason,
        enforcementEnabled = enforcementEnabled,
        semanticRejectionWouldApply = semanticRejectionWouldApply,
        preserveLexicalFallback = preserveLexicalFallback,
    )

    private companion object {
        fun validateFrozenGate(gate: KnowledgeRelevanceRawTopScoreFrozenGate) {
            require(gate.gateVersion.isNotBlank()) { "生产相关性设计门禁版本不能为空" }
            val calibration = gate.calibrationIdentity
            val validation = gate.validationIdentity
            require(calibration.providerId.isNotBlank() && calibration.model.isNotBlank() && calibration.datasetVersion.isNotBlank()) {
                "生产相关性设计 calibration 身份不能为空"
            }
            require(validation.providerId.isNotBlank() && validation.model.isNotBlank() && validation.datasetVersion.isNotBlank()) {
                "生产相关性设计 validation 身份不能为空"
            }
            require(validation.providerId == calibration.providerId && validation.model == calibration.model) {
                "生产相关性设计 calibration 与 validation Provider/模型必须一致"
            }
            require(validation.datasetVersion != calibration.datasetVersion) {
                "生产相关性设计 calibration 与 validation 数据集必须不同"
            }
            require(gate.minimumRawTopScore.isFinite()) {
                "生产相关性设计 raw top1 阈值必须是有限值"
            }
        }
    }
}
