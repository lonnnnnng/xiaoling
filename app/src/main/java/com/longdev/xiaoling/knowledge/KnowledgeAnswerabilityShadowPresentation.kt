package com.longdev.xiaoling.knowledge

enum class KnowledgeAnswerabilityUserState {
    DIRECTLY_ANSWERED,
    PARTIALLY_ANSWERED,
    NOT_ANSWERED,
    CONTRADICTORY,
    EVIDENCE_MISMATCH,
    BELOW_FROZEN_GATE,
    UNKNOWN,
}

data class KnowledgeAnswerabilityUserNotice(
    val state: KnowledgeAnswerabilityUserState,
    val title: String,
    val detail: String,
)

data class KnowledgeAnswerabilityShadowPresentedResult(
    val references: List<KnowledgeReference>,
    val decision: KnowledgeAnswerabilityDecision,
    val notice: KnowledgeAnswerabilityUserNotice,
) {
    // long: 第 93 阶段的结果只能解释观测，不能被误当成已经改变答案或引用的生产执行结果。
    val enforcementApplied: Boolean = false
}

/**
 * long: 真实 Provider 证据和生产接入尚未完成时，只把 answerability 观测翻译成用户可理解的 shadow 提示；输入引用始终原样保留。
 */
object KnowledgeAnswerabilityShadowPresentationPolicy {
    fun present(
        references: List<KnowledgeReference>,
        observation: KnowledgeAnswerabilityObservation?,
        gate: KnowledgeAnswerabilityGate?,
    ): KnowledgeAnswerabilityShadowPresentedResult {
        val retainedReferences = references.toList()
        if (observation == null) {
            return unknown(
                references = retainedReferences,
                detail = "尚未获得可回答性观测，当前答案和知识引用保持不变。",
            )
        }
        if (gate == null) {
            return unknown(
                references = retainedReferences,
                detail = "尚未冻结可用的可回答性门禁，当前答案和知识引用保持不变。",
            )
        }

        val decision = observation.decision(
            featureSet = gate.featureSet,
            minimumConfidence = gate.minimumConfidence,
            minimumEvidenceCoverage = gate.minimumEvidenceCoverage,
        )
        val notice = when (decision) {
            KnowledgeAnswerabilityDecision.ACCEPT -> KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.DIRECTLY_ANSWERED,
                title = "本地知识包含直接回答",
                detail = "候选文档提供了可回查的原文依据；当前仅作观察提示，不改变答案或引用。",
            )
            KnowledgeAnswerabilityDecision.UNKNOWN -> KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.UNKNOWN,
                title = "答案可回答性尚未确认",
                detail = "Judge 未能形成稳定结论，当前答案和知识引用保持不变。",
            )
            KnowledgeAnswerabilityDecision.REJECT -> rejectedNotice(observation)
        }
        return KnowledgeAnswerabilityShadowPresentedResult(
            references = retainedReferences,
            decision = decision,
            notice = notice,
        )
    }

    private fun rejectedNotice(
        observation: KnowledgeAnswerabilityObservation,
    ): KnowledgeAnswerabilityUserNotice {
        if (observation.contradictionDetected) {
            return KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.CONTRADICTORY,
                title = "本地知识证据存在矛盾",
                detail = "候选内容与问题所需事实存在冲突；当前仅记录观察结果，不自动修改答案。",
            )
        }
        return when (observation.verdict) {
            KnowledgeAnswerabilityVerdict.PARTIALLY_ANSWERED -> KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.PARTIALLY_ANSWERED,
                title = "本地知识仅覆盖部分问题",
                detail = "候选文档只回答了部分要点；当前仍保留原引用，不据此删除答案。",
            )
            KnowledgeAnswerabilityVerdict.NOT_ANSWERED -> KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.NOT_ANSWERED,
                title = "本地知识未直接回答问题",
                detail = "候选文档主题可能相关，但没有覆盖所问事实；当前仅记录观察结果。",
            )
            KnowledgeAnswerabilityVerdict.ANSWERED -> {
                if (observation.evidenceQuoteCount <= 0 ||
                    observation.matchedEvidenceQuoteCount != observation.evidenceQuoteCount
                ) {
                    KnowledgeAnswerabilityUserNotice(
                        state = KnowledgeAnswerabilityUserState.EVIDENCE_MISMATCH,
                        title = "答案证据无法回查",
                        detail = "模型给出的证据片段未能完整匹配候选原文，不能把该判断视为已回答。",
                    )
                } else {
                    KnowledgeAnswerabilityUserNotice(
                        state = KnowledgeAnswerabilityUserState.BELOW_FROZEN_GATE,
                        title = "答案证据强度不足",
                        detail = "完整回答声明未达到冻结的置信度或覆盖率门禁；当前仅作观察提示。",
                    )
                }
            }
            KnowledgeAnswerabilityVerdict.UNKNOWN -> KnowledgeAnswerabilityUserNotice(
                state = KnowledgeAnswerabilityUserState.UNKNOWN,
                title = "答案可回答性尚未确认",
                detail = "Judge 返回未知结果，当前答案和知识引用保持不变。",
            )
        }
    }

    private fun unknown(
        references: List<KnowledgeReference>,
        detail: String,
    ) = KnowledgeAnswerabilityShadowPresentedResult(
        references = references,
        decision = KnowledgeAnswerabilityDecision.UNKNOWN,
        notice = KnowledgeAnswerabilityUserNotice(
            state = KnowledgeAnswerabilityUserState.UNKNOWN,
            title = "答案可回答性尚未确认",
            detail = detail,
        ),
    )
}
