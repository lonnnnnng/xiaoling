package com.longdev.xiaoling.knowledge

data class KnowledgeRelevanceRawTopScoreFrozenGate(
    val gateVersion: String,
    val calibrationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    val validationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    val minimumRawTopScore: Double,
)

data class KnowledgeRelevanceFinalHoldoutCriteria(
    val minimumPositiveAcceptanceRate: Double,
    val minimumNearNegativeRejectionRate: Double,
    val minimumFarNegativeRejectionRate: Double,
    val minimumDecisionStableRate: Double,
)

data class KnowledgeRelevanceFinalHoldoutReport(
    val frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
    val holdoutIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val decisionStableRate: Double,
    val balancedAccuracy: Double,
    val passed: Boolean,
)

object KnowledgeRelevanceFinalHoldoutPolicy {
    fun evaluate(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        holdoutIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        samples: List<KnowledgeRelevanceFeatureSample>,
        criteria: KnowledgeRelevanceFinalHoldoutCriteria,
    ): KnowledgeRelevanceFinalHoldoutReport {
        validateInputs(frozenGate, holdoutIdentity, samples, criteria)
        require(holdoutIdentity.providerId == frozenGate.calibrationIdentity.providerId) {
            "final holdout Provider 与冻结门禁不一致"
        }
        require(holdoutIdentity.model == frozenGate.calibrationIdentity.model) {
            "final holdout 模型与冻结门禁不一致"
        }
        require(holdoutIdentity.datasetVersion != frozenGate.calibrationIdentity.datasetVersion) {
            "final holdout 不能复用 calibration 数据集"
        }
        require(holdoutIdentity.datasetVersion != frozenGate.validationIdentity.datasetVersion) {
            "final holdout 不能复用 validation 数据集"
        }

        // long: 最终留出集只应用 Stage 85 已冻结的 raw top1 阈值，不搜索留出集观测值，也不重新引入 margin 或 z-score。
        val decisions = samples.map { sample ->
            sample to (sample.features.rawTopScore >= frozenGate.minimumRawTopScore)
        }
        val positive = decisions.filter { it.first.label == KnowledgeRelevanceLabel.POSITIVE }
        val nearNegative = decisions.filter { it.first.label == KnowledgeRelevanceLabel.NEAR_NEGATIVE }
        val farNegative = decisions.filter { it.first.label == KnowledgeRelevanceLabel.FAR_NEGATIVE }
        val positiveAcceptanceRate = positive.count { it.second }.toDouble() / positive.size
        val nearNegativeRejectionRate = nearNegative.count { !it.second }.toDouble() / nearNegative.size
        val farNegativeRejectionRate = farNegative.count { !it.second }.toDouble() / farNegative.size
        val decisionsByCase = decisions.groupBy { it.first.caseId }
        val decisionStableRate = decisionsByCase.values.count { caseDecisions ->
            caseDecisions.map { it.second }.distinct().size == 1
        }.toDouble() / decisionsByCase.size
        val balancedAccuracy = (
            positiveAcceptanceRate + nearNegativeRejectionRate + farNegativeRejectionRate
            ) / KnowledgeRelevanceLabel.entries.size
        // long: 是否通过只由预注册四项标准决定；失败时报告保留原阈值，调用方不得从 holdout 反向选择新门禁。
        val passed = positiveAcceptanceRate >= criteria.minimumPositiveAcceptanceRate &&
            nearNegativeRejectionRate >= criteria.minimumNearNegativeRejectionRate &&
            farNegativeRejectionRate >= criteria.minimumFarNegativeRejectionRate &&
            decisionStableRate >= criteria.minimumDecisionStableRate
        return KnowledgeRelevanceFinalHoldoutReport(
            frozenGate = frozenGate,
            holdoutIdentity = holdoutIdentity,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            decisionStableRate = decisionStableRate,
            balancedAccuracy = balancedAccuracy,
            passed = passed,
        )
    }

    private fun validateInputs(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        holdoutIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        samples: List<KnowledgeRelevanceFeatureSample>,
        criteria: KnowledgeRelevanceFinalHoldoutCriteria,
    ) {
        val identities = listOf(frozenGate.calibrationIdentity, holdoutIdentity)
        require(identities.all { identity ->
            identity.providerId.isNotBlank() && identity.model.isNotBlank() && identity.datasetVersion.isNotBlank()
        }) { "final holdout 数据集身份不能为空" }
        require(frozenGate.gateVersion.isNotBlank()) { "final holdout 冻结门禁版本不能为空" }
        val validationIdentity = frozenGate.validationIdentity
        // long: calibration、validation 和 final holdout 必须在同一 Provider/模型上形成连续证据，不能只凭版本字符串掩盖模型漂移。
        require(validationIdentity.providerId.isNotBlank() &&
            validationIdentity.model.isNotBlank() &&
            validationIdentity.datasetVersion.isNotBlank()
        ) { "final holdout validation 数据集身份不能为空" }
        require(validationIdentity.providerId == frozenGate.calibrationIdentity.providerId) {
            "冻结门禁 calibration 与 validation Provider 必须一致"
        }
        require(validationIdentity.model == frozenGate.calibrationIdentity.model) {
            "冻结门禁 calibration 与 validation 模型必须一致"
        }
        require(validationIdentity.datasetVersion != frozenGate.calibrationIdentity.datasetVersion) {
            "冻结门禁的 calibration 与 validation 数据集必须不同"
        }
        require(frozenGate.minimumRawTopScore.isFinite()) { "final holdout raw top1 阈值必须是有限值" }
        val criteriaValues = listOf(
            criteria.minimumPositiveAcceptanceRate,
            criteria.minimumNearNegativeRejectionRate,
            criteria.minimumFarNegativeRejectionRate,
            criteria.minimumDecisionStableRate,
        )
        require(criteriaValues.all { it.isFinite() && it in 0.0..1.0 }) {
            "final holdout 预注册比例必须在 0 到 1 之间"
        }
        require(samples.isNotEmpty()) { "final holdout 样本不能为空" }
        require(samples.all { it.caseId.isNotBlank() }) { "final holdout 用例 ID 不能为空" }
        require(samples.all { sample ->
            KnowledgeRelevanceFeature.entries.all { feature -> sample.features.value(feature).isFinite() }
        }) { "final holdout 分数必须是有限值" }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "final holdout 必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) { "同一 final holdout 用例不能跨标签" }
    }
}
