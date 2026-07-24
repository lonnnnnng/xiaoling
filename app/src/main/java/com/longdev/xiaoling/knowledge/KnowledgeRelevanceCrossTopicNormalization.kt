package com.longdev.xiaoling.knowledge

enum class KnowledgeRelevanceNormalizedFeature {
    TOP_SCORE_MEAN_GAP,
    MARGIN_OVER_STANDARD_DEVIATION,
}

enum class KnowledgeRelevanceCrossTopicFeatureSet(
    val features: List<KnowledgeRelevanceNormalizedFeature>,
) {
    TOP_SCORE_MEAN_GAP(listOf(KnowledgeRelevanceNormalizedFeature.TOP_SCORE_MEAN_GAP)),
    MARGIN_OVER_STANDARD_DEVIATION(
        listOf(KnowledgeRelevanceNormalizedFeature.MARGIN_OVER_STANDARD_DEVIATION),
    ),
    TOP_SCORE_MEAN_GAP_AND_MARGIN_OVER_STANDARD_DEVIATION(
        listOf(
            KnowledgeRelevanceNormalizedFeature.TOP_SCORE_MEAN_GAP,
            KnowledgeRelevanceNormalizedFeature.MARGIN_OVER_STANDARD_DEVIATION,
        ),
    ),
}

/**
 * long: 这组特征只描述同次候选分布中的相对差异，不把 Provider 的绝对 cosine 分数直接当成跨主题事实。
 */
data class KnowledgeRelevanceCrossTopicFeatureVector(
    val topScoreMeanGap: Double,
    val marginOverStandardDeviation: Double,
) {
    init {
        require(topScoreMeanGap.isFinite()) { "跨主题归一化 top1-均值差必须是有限值" }
        require(marginOverStandardDeviation.isFinite()) { "跨主题归一化 margin/标准差必须是有限值" }
    }

    fun value(feature: KnowledgeRelevanceNormalizedFeature): Double = when (feature) {
        KnowledgeRelevanceNormalizedFeature.TOP_SCORE_MEAN_GAP -> topScoreMeanGap
        KnowledgeRelevanceNormalizedFeature.MARGIN_OVER_STANDARD_DEVIATION -> marginOverStandardDeviation
    }

    companion object {
        fun fromCandidateDistribution(
            topScore: Double,
            scoreMean: Double,
            scoreMargin: Double,
            scoreStandardDeviation: Double,
        ): KnowledgeRelevanceCrossTopicFeatureVector {
            require(topScore.isFinite() && scoreMean.isFinite()) {
                "跨主题归一化 top1 与候选均值必须是有限值"
            }
            require(scoreMargin.isFinite() && scoreMargin >= 0.0) {
                "跨主题归一化 margin 必须是非负有限值"
            }
            // long: 接近零的候选标准差没有稳定的缩放意义；直接拒绝比生成巨大比值并误当成强相关更安全。
            require(scoreStandardDeviation.isFinite() && scoreStandardDeviation > MINIMUM_STANDARD_DEVIATION) {
                "跨主题归一化候选标准差必须大于数值容差"
            }
            return KnowledgeRelevanceCrossTopicFeatureVector(
                topScoreMeanGap = topScore - scoreMean,
                marginOverStandardDeviation = scoreMargin / scoreStandardDeviation,
            )
        }

        private const val MINIMUM_STANDARD_DEVIATION = 1e-12
    }
}

data class KnowledgeRelevanceCrossTopicFeatureSample(
    val caseId: String,
    val label: KnowledgeRelevanceLabel,
    val features: KnowledgeRelevanceCrossTopicFeatureVector,
) {
    init {
        require(caseId.isNotBlank()) { "跨主题归一化用例 ID 不能为空" }
    }
}

data class KnowledgeRelevanceCrossTopicFeatureEvaluation(
    val featureSet: KnowledgeRelevanceCrossTopicFeatureSet,
    val thresholds: Map<KnowledgeRelevanceNormalizedFeature, Double>,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val decisionStableRate: Double,
    val balancedAccuracy: Double,
)

data class KnowledgeRelevanceCrossTopicFeatureGate(
    val featureSet: KnowledgeRelevanceCrossTopicFeatureSet,
    val thresholds: Map<KnowledgeRelevanceNormalizedFeature, Double>,
    val calibrationPositiveAcceptanceRate: Double,
    val calibrationNearNegativeRejectionRate: Double,
    val calibrationFarNegativeRejectionRate: Double,
    val calibrationDecisionStableRate: Double,
    val calibrationBalancedAccuracy: Double,
) {
    fun accepts(features: KnowledgeRelevanceCrossTopicFeatureVector): Boolean =
        featureSet.features.all { feature -> features.value(feature) >= thresholds.getValue(feature) }
}

data class KnowledgeRelevanceCrossTopicNormalizationReport(
    val productionIdentity: KnowledgeRelevanceProductionIdentity,
    val calibrationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
    val validationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
    val calibrationGates: Map<KnowledgeRelevanceCrossTopicFeatureSet, KnowledgeRelevanceCrossTopicFeatureGate>,
    val validationEvaluations: Map<KnowledgeRelevanceCrossTopicFeatureSet, KnowledgeRelevanceCrossTopicFeatureEvaluation>,
)

/**
 * long: 第91阶段只建立新的跨主题特征证据。阈值从 calibration 观测点选择，validation 不能回调阈值，也不能进入生产拒绝。
 */
object KnowledgeRelevanceCrossTopicNormalizationPolicy {
    fun compare(
        productionIdentity: KnowledgeRelevanceProductionIdentity,
        calibrationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
        validationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
        calibrationSamples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
        validationSamples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): KnowledgeRelevanceCrossTopicNormalizationReport {
        validateIdentity(productionIdentity, calibrationIdentity, validationIdentity)
        val calibrationGates = selectCalibrationGates(calibrationSamples)
        return KnowledgeRelevanceCrossTopicNormalizationReport(
            productionIdentity = productionIdentity,
            calibrationIdentity = calibrationIdentity,
            validationIdentity = validationIdentity,
            calibrationGates = calibrationGates,
            validationEvaluations = evaluateFrozenGates(calibrationGates, validationSamples),
        )
    }

    fun selectCalibrationGates(
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): Map<KnowledgeRelevanceCrossTopicFeatureSet, KnowledgeRelevanceCrossTopicFeatureGate> {
        validateSamples(samples, "校准")
        return KnowledgeRelevanceCrossTopicFeatureSet.entries.associateWith { featureSet ->
            selectGate(featureSet, samples)
        }
    }

    fun evaluateFrozenGates(
        gates: Map<KnowledgeRelevanceCrossTopicFeatureSet, KnowledgeRelevanceCrossTopicFeatureGate>,
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): Map<KnowledgeRelevanceCrossTopicFeatureSet, KnowledgeRelevanceCrossTopicFeatureEvaluation> {
        validateSamples(samples, "验证")
        require(gates.keys == KnowledgeRelevanceCrossTopicFeatureSet.entries.toSet()) {
            "跨主题归一化冻结门禁必须覆盖全部特征族"
        }
        return KnowledgeRelevanceCrossTopicFeatureSet.entries.associateWith { featureSet ->
            evaluate(featureSet, gates.getValue(featureSet).thresholds, samples)
        }
    }

    private fun selectGate(
        featureSet: KnowledgeRelevanceCrossTopicFeatureSet,
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): KnowledgeRelevanceCrossTopicFeatureGate {
        // long: 只从 calibration 已观测的相对特征点组合阈值，避免跨主题探针在样本外插值制造虚假的门禁提升。
        val candidates = thresholdCombinations(featureSet, samples).map { thresholds ->
            evaluate(featureSet, thresholds, samples)
        }
        val best = candidates.maxWithOrNull(::compareCandidates)
            ?: error("跨主题归一化门禁候选不能为空")
        return KnowledgeRelevanceCrossTopicFeatureGate(
            featureSet = featureSet,
            thresholds = best.thresholds,
            calibrationPositiveAcceptanceRate = best.positiveAcceptanceRate,
            calibrationNearNegativeRejectionRate = best.nearNegativeRejectionRate,
            calibrationFarNegativeRejectionRate = best.farNegativeRejectionRate,
            calibrationDecisionStableRate = best.decisionStableRate,
            calibrationBalancedAccuracy = best.balancedAccuracy,
        )
    }

    private fun thresholdCombinations(
        featureSet: KnowledgeRelevanceCrossTopicFeatureSet,
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): List<Map<KnowledgeRelevanceNormalizedFeature, Double>> {
        fun expand(
            index: Int,
            thresholds: LinkedHashMap<KnowledgeRelevanceNormalizedFeature, Double>,
        ): List<Map<KnowledgeRelevanceNormalizedFeature, Double>> {
            if (index == featureSet.features.size) return listOf(thresholds.toMap())
            val feature = featureSet.features[index]
            return samples.map { sample -> sample.features.value(feature) }
                .distinct()
                .sorted()
                .flatMap { threshold ->
                    thresholds[feature] = threshold
                    expand(index + 1, thresholds)
                }
                .also { thresholds.remove(feature) }
        }
        return expand(0, linkedMapOf())
    }

    private fun evaluate(
        featureSet: KnowledgeRelevanceCrossTopicFeatureSet,
        thresholds: Map<KnowledgeRelevanceNormalizedFeature, Double>,
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
    ): KnowledgeRelevanceCrossTopicFeatureEvaluation {
        val decisions = samples.map { sample ->
            sample to featureSet.features.all { feature ->
                sample.features.value(feature) >= thresholds.getValue(feature)
            }
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
        // long: 三桶等权，防止重复运行次数或远负例规模掩盖跨主题正例误拒。
        val balancedAccuracy = (
            positiveAcceptanceRate + nearNegativeRejectionRate + farNegativeRejectionRate
            ) / KnowledgeRelevanceLabel.entries.size
        return KnowledgeRelevanceCrossTopicFeatureEvaluation(
            featureSet = featureSet,
            thresholds = thresholds,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            decisionStableRate = decisionStableRate,
            balancedAccuracy = balancedAccuracy,
        )
    }

    private fun compareCandidates(
        left: KnowledgeRelevanceCrossTopicFeatureEvaluation,
        right: KnowledgeRelevanceCrossTopicFeatureEvaluation,
    ): Int {
        val primary = compareValuesBy(
            left,
            right,
            { it.balancedAccuracy },
            { it.positiveAcceptanceRate },
            { it.nearNegativeRejectionRate },
            { it.farNegativeRejectionRate },
            { it.decisionStableRate },
        )
        if (primary != 0) return primary
        left.featureSet.features.forEach { feature ->
            val comparison = compareValues(
                left.thresholds.getValue(feature),
                right.thresholds.getValue(feature),
            )
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun validateSamples(
        samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
        phase: String,
    ) {
        require(samples.isNotEmpty()) { "$phase 跨主题归一化样本不能为空" }
        require(samples.all { sample ->
            KnowledgeRelevanceNormalizedFeature.entries.all { feature ->
                sample.features.value(feature).isFinite()
            }
        }) { "$phase 跨主题归一化特征必须是有限值" }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "$phase 跨主题归一化必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) { "同一跨主题归一化用例不能跨标签" }
    }

    private fun validateIdentity(
        productionIdentity: KnowledgeRelevanceProductionIdentity,
        calibrationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
        validationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
    ) {
        val identities = listOf(calibrationIdentity, validationIdentity)
        require(identities.all { identity ->
            identity.providerId == productionIdentity.providerId &&
                identity.model == productionIdentity.model &&
                identity.configurationFingerprint == productionIdentity.configurationFingerprint
        }) { "跨主题归一化证据的 Provider、模型或配置指纹与生产身份不一致" }
        require(calibrationIdentity.datasetVersion != validationIdentity.datasetVersion) {
            "跨主题归一化 calibration 与 validation 数据集必须不同"
        }
    }
}
