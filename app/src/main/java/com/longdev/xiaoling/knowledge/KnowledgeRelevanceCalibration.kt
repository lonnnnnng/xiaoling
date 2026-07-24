package com.longdev.xiaoling.knowledge

import kotlin.math.ceil

enum class KnowledgeRelevanceLabel {
    POSITIVE,
    NEAR_NEGATIVE,
    FAR_NEGATIVE,
}

data class KnowledgeRelevanceCalibrationSample(
    val caseId: String,
    val label: KnowledgeRelevanceLabel,
    val topScore: Double,
    val scoreMargin: Double,
)

data class KnowledgeRelevanceDistribution(
    val p05: Double,
    val p50: Double,
    val p95: Double,
)

data class KnowledgeRelevanceBucketReport(
    val label: KnowledgeRelevanceLabel,
    val sampleCount: Int,
    val uniqueCaseCount: Int,
    val topScore: KnowledgeRelevanceDistribution,
    val scoreMargin: KnowledgeRelevanceDistribution,
)

data class KnowledgeRelevanceCandidateGate(
    val minimumTopScore: Double,
    val minimumScoreMargin: Double,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val balancedAccuracy: Double,
)

data class KnowledgeRelevanceCalibrationReport(
    val buckets: Map<KnowledgeRelevanceLabel, KnowledgeRelevanceBucketReport>,
    val candidateGate: KnowledgeRelevanceCandidateGate,
) {
    fun bucket(label: KnowledgeRelevanceLabel): KnowledgeRelevanceBucketReport = buckets.getValue(label)
}

object KnowledgeRelevanceCalibrationPolicy {
    fun evaluate(samples: List<KnowledgeRelevanceCalibrationSample>): KnowledgeRelevanceCalibrationReport {
        validateSamples(samples)
        val buckets = KnowledgeRelevanceLabel.entries.associateWith { label ->
            val bucketSamples = samples.filter { it.label == label }
            KnowledgeRelevanceBucketReport(
                label = label,
                sampleCount = bucketSamples.size,
                uniqueCaseCount = bucketSamples.map { it.caseId }.distinct().size,
                topScore = distribution(bucketSamples.map { it.topScore }),
                scoreMargin = distribution(bucketSamples.map { it.scoreMargin }),
            )
        }
        return KnowledgeRelevanceCalibrationReport(
            buckets = buckets,
            candidateGate = selectCandidateGate(samples),
        )
    }

    private fun validateSamples(samples: List<KnowledgeRelevanceCalibrationSample>) {
        require(samples.isNotEmpty()) { "相关性校准样本不能为空" }
        require(samples.all { it.caseId.isNotBlank() }) { "相关性校准用例 ID 不能为空" }
        require(samples.all { it.topScore.isFinite() && it.scoreMargin.isFinite() }) {
            "相关性校准分数必须是有限值"
        }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "相关性校准必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) {
            "同一相关性校准用例不能跨标签"
        }
    }

    private fun distribution(values: List<Double>): KnowledgeRelevanceDistribution {
        val sorted = values.sorted()
        return KnowledgeRelevanceDistribution(
            p05 = nearestRank(sorted, 0.05),
            p50 = nearestRank(sorted, 0.50),
            p95 = nearestRank(sorted, 0.95),
        )
    }

    private fun nearestRank(sorted: List<Double>, percentile: Double): Double {
        val oneBasedRank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[oneBasedRank - 1]
    }

    private fun selectCandidateGate(samples: List<KnowledgeRelevanceCalibrationSample>): KnowledgeRelevanceCandidateGate {
        // long: 阈值只从真实观测点组合中选择，报告用于 shadow 校准，不能在样本外插出看似更优的生产门禁。
        val candidates = samples.map { it.topScore }.distinct().flatMap { minimumTopScore ->
            samples.map { it.scoreMargin }.distinct().map { minimumScoreMargin ->
                evaluateCandidate(samples, minimumTopScore, minimumScoreMargin)
            }
        }
        return candidates.maxWithOrNull(
            compareBy<KnowledgeRelevanceCandidateGate> { it.balancedAccuracy }
                .thenBy { it.positiveAcceptanceRate }
                .thenBy { it.nearNegativeRejectionRate }
                .thenBy { it.farNegativeRejectionRate }
                .thenBy { it.minimumTopScore }
                .thenBy { it.minimumScoreMargin },
        ) ?: error("相关性校准候选门禁不能为空")
    }

    private fun evaluateCandidate(
        samples: List<KnowledgeRelevanceCalibrationSample>,
        minimumTopScore: Double,
        minimumScoreMargin: Double,
    ): KnowledgeRelevanceCandidateGate {
        val positive = samples.filter { it.label == KnowledgeRelevanceLabel.POSITIVE }
        val nearNegative = samples.filter { it.label == KnowledgeRelevanceLabel.NEAR_NEGATIVE }
        val farNegative = samples.filter { it.label == KnowledgeRelevanceLabel.FAR_NEGATIVE }
        val accepts: (KnowledgeRelevanceCalibrationSample) -> Boolean = {
            it.topScore >= minimumTopScore && it.scoreMargin >= minimumScoreMargin
        }
        val positiveAcceptanceRate = positive.count(accepts).toDouble() / positive.size
        val nearNegativeRejectionRate = nearNegative.count { !accepts(it) }.toDouble() / nearNegative.size
        val farNegativeRejectionRate = farNegative.count { !accepts(it) }.toDouble() / farNegative.size
        // long: 三个业务桶等权，避免远负例数量较多时掩盖近负例误接纳或正例被拒绝的问题。
        val balancedAccuracy = (
            positiveAcceptanceRate +
                nearNegativeRejectionRate +
                farNegativeRejectionRate
            ) / KnowledgeRelevanceLabel.entries.size
        return KnowledgeRelevanceCandidateGate(
            minimumTopScore = minimumTopScore,
            minimumScoreMargin = minimumScoreMargin,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            balancedAccuracy = balancedAccuracy,
        )
    }
}

data class KnowledgeRelevanceDatasetIdentity(
    val providerId: String,
    val model: String,
    val datasetVersion: String,
)

data class KnowledgeRelevanceFrozenGate(
    val gateVersion: String,
    val calibrationIdentity: KnowledgeRelevanceDatasetIdentity,
    val minimumTopScore: Double,
    val minimumScoreMargin: Double,
)

data class KnowledgeRelevanceHoldoutCriteria(
    val minimumPositiveAcceptanceRate: Double,
    val minimumNearNegativeRejectionRate: Double,
    val minimumFarNegativeRejectionRate: Double,
    val minimumDecisionStableRate: Double,
)

data class KnowledgeRelevanceHoldoutReport(
    val frozenGate: KnowledgeRelevanceFrozenGate,
    val holdoutIdentity: KnowledgeRelevanceDatasetIdentity,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val decisionStableRate: Double,
    val passed: Boolean,
)

object KnowledgeRelevanceHoldoutPolicy {
    fun evaluate(
        frozenGate: KnowledgeRelevanceFrozenGate,
        holdoutIdentity: KnowledgeRelevanceDatasetIdentity,
        samples: List<KnowledgeRelevanceCalibrationSample>,
        criteria: KnowledgeRelevanceHoldoutCriteria,
    ): KnowledgeRelevanceHoldoutReport {
        validateInputs(frozenGate, holdoutIdentity, samples, criteria)
        // long: holdout 只能验证原 Provider/模型下的冻结门禁，身份漂移必须建立新校准，不能复用旧阈值制造跨模型结论。
        require(holdoutIdentity.providerId == frozenGate.calibrationIdentity.providerId) {
            "holdout Provider 与冻结门禁不一致"
        }
        require(holdoutIdentity.model == frozenGate.calibrationIdentity.model) {
            "holdout 模型与冻结门禁不一致"
        }
        require(holdoutIdentity.datasetVersion != frozenGate.calibrationIdentity.datasetVersion) {
            "holdout 不能复用生成冻结门禁的校准数据集"
        }
        val decisions = samples.map { sample ->
            sample to (
                sample.topScore >= frozenGate.minimumTopScore &&
                    sample.scoreMargin >= frozenGate.minimumScoreMargin
                )
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
        // long: holdout 只应用外部冻结门禁，不调用候选搜索；是否通过完全由预注册标准和观测结果决定。
        val passed = positiveAcceptanceRate >= criteria.minimumPositiveAcceptanceRate &&
            nearNegativeRejectionRate >= criteria.minimumNearNegativeRejectionRate &&
            farNegativeRejectionRate >= criteria.minimumFarNegativeRejectionRate &&
            decisionStableRate >= criteria.minimumDecisionStableRate
        return KnowledgeRelevanceHoldoutReport(
            frozenGate = frozenGate,
            holdoutIdentity = holdoutIdentity,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            decisionStableRate = decisionStableRate,
            passed = passed,
        )
    }

    private fun validateInputs(
        frozenGate: KnowledgeRelevanceFrozenGate,
        holdoutIdentity: KnowledgeRelevanceDatasetIdentity,
        samples: List<KnowledgeRelevanceCalibrationSample>,
        criteria: KnowledgeRelevanceHoldoutCriteria,
    ) {
        val identities = listOf(frozenGate.calibrationIdentity, holdoutIdentity)
        require(identities.all { it.providerId.isNotBlank() && it.model.isNotBlank() && it.datasetVersion.isNotBlank() }) {
            "相关性数据集身份不能为空"
        }
        require(frozenGate.gateVersion.isNotBlank()) { "冻结门禁版本不能为空" }
        require(frozenGate.minimumTopScore.isFinite() && frozenGate.minimumScoreMargin.isFinite()) {
            "冻结门禁阈值必须是有限值"
        }
        val criteriaValues = listOf(
            criteria.minimumPositiveAcceptanceRate,
            criteria.minimumNearNegativeRejectionRate,
            criteria.minimumFarNegativeRejectionRate,
            criteria.minimumDecisionStableRate,
        )
        require(criteriaValues.all { it.isFinite() && it in 0.0..1.0 }) {
            "holdout 预注册比例必须在 0 到 1 之间"
        }
        require(samples.isNotEmpty()) { "holdout 样本不能为空" }
        require(samples.all { it.caseId.isNotBlank() }) { "holdout 用例 ID 不能为空" }
        require(samples.all { it.topScore.isFinite() && it.scoreMargin.isFinite() }) {
            "holdout 分数必须是有限值"
        }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "holdout 必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) {
            "同一 holdout 用例不能跨标签"
        }
    }
}
