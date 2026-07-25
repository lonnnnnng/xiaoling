package com.longdev.xiaoling.knowledge

/**
 * long: 只有来自同一 Agent Run 的可信知识检索结果才能成为 answerability Judge 的 shadow 候选；引用顺序是审计证据的一部分。
 */
data class KnowledgeAnswerabilityShadowCandidate(
    val sourceRunId: String,
    val question: String,
    val candidateText: String,
    val references: List<KnowledgeReference>,
)

/**
 * long: 冻结绑定把 Judge 身份、互异数据集和已经评审过的门禁锁在一起，避免消息流误用临时校准结果。
 */
data class KnowledgeAnswerabilityFrozenBinding(
    val calibrationIdentity: KnowledgeAnswerabilityDatasetIdentity,
    val validationIdentity: KnowledgeAnswerabilityDatasetIdentity,
    val gate: KnowledgeAnswerabilityGate,
) {
    init {
        require(calibrationIdentity.judgeIdentity == validationIdentity.judgeIdentity) {
            "answerability shadow calibration 与 validation 的 Judge 身份必须一致"
        }
        require(calibrationIdentity.datasetVersion != validationIdentity.datasetVersion) {
            "answerability shadow calibration 与 validation 数据集必须不同"
        }
    }

    val judgeIdentity: KnowledgeAnswerabilityJudgeIdentity
        get() = calibrationIdentity.judgeIdentity

    val calibrationDatasetVersion: String
        get() = calibrationIdentity.datasetVersion

    val validationDatasetVersion: String
        get() = validationIdentity.datasetVersion

    val featureSet: KnowledgeAnswerabilityFeatureSet
        get() = gate.featureSet
}

enum class KnowledgeAnswerabilityShadowBindingStatus {
    BOUND,
    UNKNOWN,
}

enum class KnowledgeAnswerabilityShadowBindingReason {
    BOUND,
    OBSERVATION_UNKNOWN,
    MISSING_OBSERVATION,
    MISSING_FROZEN_BINDING,
    MISSING_JUDGE_IDENTITY,
    JUDGE_IDENTITY_MISMATCH,
    OBSERVATION_CASE_MISMATCH,
    UNSUPPORTED_FEATURE_SET,
    INVALID_CANDIDATE,
}

/**
 * long: 绑定结果只携带解释所需的 shadow 事实；enforcementApplied 固定为 false，任何结果都不能改变原答案或知识引用。
 */
data class KnowledgeAnswerabilityShadowBinding(
    val candidate: KnowledgeAnswerabilityShadowCandidate,
    val status: KnowledgeAnswerabilityShadowBindingStatus,
    val reason: KnowledgeAnswerabilityShadowBindingReason,
    val observation: KnowledgeAnswerabilityObservation?,
    val decision: KnowledgeAnswerabilityDecision,
    val references: List<KnowledgeReference>,
    val notice: KnowledgeAnswerabilityUserNotice,
    val observedAt: Long?,
) {
    val enforcementApplied: Boolean = false
}

/**
 * long: 真实 Agent 消息只把已经存在的知识证据绑定到只读 shadow；绑定过程不发起 Provider 请求，也不改变答案或引用。
 */
object KnowledgeAnswerabilityShadowBindingPolicy {
    private val MESSAGE_FEATURE_SETS = setOf(
        KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
        KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
    )

    fun bind(
        candidate: KnowledgeAnswerabilityShadowCandidate,
        actualJudgeIdentity: KnowledgeAnswerabilityJudgeIdentity?,
        frozenBinding: KnowledgeAnswerabilityFrozenBinding?,
        observation: KnowledgeAnswerabilityObservation?,
        observedAt: Long,
    ): KnowledgeAnswerabilityShadowBinding {
        // long: 绑定开始时冻结候选和引用，避免调用方后续修改可变 List 导致同一审计结果前后不一致。
        val candidateSnapshot = candidate.copy(references = candidate.references.toList())
        val references = candidateSnapshot.references

        fun unknown(
            reason: KnowledgeAnswerabilityShadowBindingReason,
            detail: String,
        ): KnowledgeAnswerabilityShadowBinding {
            val notice = KnowledgeAnswerabilityShadowPresentationPolicy.present(
                references = references,
                observation = null,
                gate = null,
            ).notice.copy(detail = detail)
            return KnowledgeAnswerabilityShadowBinding(
                candidate = candidateSnapshot,
                status = KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN,
                reason = reason,
                observation = observation,
                decision = KnowledgeAnswerabilityDecision.UNKNOWN,
                references = references,
                notice = notice,
                observedAt = observation?.let { observedAt },
            )
        }

        if (
            candidateSnapshot.sourceRunId.isBlank() ||
            candidateSnapshot.question.isBlank() ||
            candidateSnapshot.candidateText.isBlank() ||
            references.isEmpty()
        ) {
            return unknown(
                reason = KnowledgeAnswerabilityShadowBindingReason.INVALID_CANDIDATE,
                detail = "候选知识证据不完整，当前答案和知识引用保持不变。",
            )
        }
        val frozen = frozenBinding ?: return unknown(
            reason = KnowledgeAnswerabilityShadowBindingReason.MISSING_FROZEN_BINDING,
            detail = "尚未获得完整的可回答性绑定，当前答案和知识引用保持不变。",
        )
        if (frozen.featureSet !in MESSAGE_FEATURE_SETS) {
            return unknown(
                reason = KnowledgeAnswerabilityShadowBindingReason.UNSUPPORTED_FEATURE_SET,
                detail = "当前冻结特征族尚未获准进入消息 shadow，当前答案和知识引用保持不变。",
            )
        }
        if (actualJudgeIdentity == null) {
            return unknown(
                reason = KnowledgeAnswerabilityShadowBindingReason.MISSING_JUDGE_IDENTITY,
                detail = "缺少 Judge 身份，当前答案和知识引用保持不变。",
            )
        }
        if (actualJudgeIdentity != frozen.judgeIdentity) {
            return unknown(
                reason = KnowledgeAnswerabilityShadowBindingReason.JUDGE_IDENTITY_MISMATCH,
                detail = "Judge 身份发生变化，当前答案和知识引用保持不变。",
            )
        }
        val actualObservation = observation ?: return unknown(
            reason = KnowledgeAnswerabilityShadowBindingReason.MISSING_OBSERVATION,
            detail = "尚未获得可回答性观测，当前答案和知识引用保持不变。",
        )
        if (actualObservation.caseId != candidateSnapshot.sourceRunId) {
            return unknown(
                reason = KnowledgeAnswerabilityShadowBindingReason.OBSERVATION_CASE_MISMATCH,
                detail = "可回答性观测未能对应当前 Agent Run，当前答案和知识引用保持不变。",
            )
        }

        val presented = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = references,
            observation = actualObservation,
            gate = frozen.gate,
        )
        return KnowledgeAnswerabilityShadowBinding(
            candidate = candidateSnapshot,
            status = KnowledgeAnswerabilityShadowBindingStatus.BOUND,
            reason = if (presented.decision == KnowledgeAnswerabilityDecision.UNKNOWN) {
                KnowledgeAnswerabilityShadowBindingReason.OBSERVATION_UNKNOWN
            } else {
                KnowledgeAnswerabilityShadowBindingReason.BOUND
            },
            observation = actualObservation,
            decision = presented.decision,
            references = references,
            notice = presented.notice,
            observedAt = observedAt,
        )
    }
}
