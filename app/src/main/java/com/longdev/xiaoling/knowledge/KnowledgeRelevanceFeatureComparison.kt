package com.longdev.xiaoling.knowledge

enum class KnowledgeRelevanceFeature {
    RAW_TOP_SCORE,
    SCORE_MARGIN,
    TOP_SCORE_Z_SCORE,
}

enum class KnowledgeRelevanceFeatureSet(
    val features: List<KnowledgeRelevanceFeature>,
) {
    RAW_TOP_SCORE(listOf(KnowledgeRelevanceFeature.RAW_TOP_SCORE)),
    SCORE_MARGIN(listOf(KnowledgeRelevanceFeature.SCORE_MARGIN)),
    TOP_SCORE_Z_SCORE(listOf(KnowledgeRelevanceFeature.TOP_SCORE_Z_SCORE)),
    RAW_TOP_SCORE_AND_MARGIN(
        listOf(
            KnowledgeRelevanceFeature.RAW_TOP_SCORE,
            KnowledgeRelevanceFeature.SCORE_MARGIN,
        ),
    ),
    RAW_TOP_SCORE_AND_Z_SCORE(
        listOf(
            KnowledgeRelevanceFeature.RAW_TOP_SCORE,
            KnowledgeRelevanceFeature.TOP_SCORE_Z_SCORE,
        ),
    ),
    MARGIN_AND_Z_SCORE(
        listOf(
            KnowledgeRelevanceFeature.SCORE_MARGIN,
            KnowledgeRelevanceFeature.TOP_SCORE_Z_SCORE,
        ),
    ),
    RAW_TOP_SCORE_MARGIN_AND_Z_SCORE(
        listOf(
            KnowledgeRelevanceFeature.RAW_TOP_SCORE,
            KnowledgeRelevanceFeature.SCORE_MARGIN,
            KnowledgeRelevanceFeature.TOP_SCORE_Z_SCORE,
        ),
    ),
}

data class KnowledgeRelevanceFeatureVector(
    val rawTopScore: Double,
    val scoreMargin: Double,
    val topScoreZScore: Double,
) {
    fun value(feature: KnowledgeRelevanceFeature): Double = when (feature) {
        KnowledgeRelevanceFeature.RAW_TOP_SCORE -> rawTopScore
        KnowledgeRelevanceFeature.SCORE_MARGIN -> scoreMargin
        KnowledgeRelevanceFeature.TOP_SCORE_Z_SCORE -> topScoreZScore
    }
}

data class KnowledgeRelevanceFeatureSample(
    val caseId: String,
    val label: KnowledgeRelevanceLabel,
    val features: KnowledgeRelevanceFeatureVector,
)

data class KnowledgeRelevanceFeatureDatasetIdentity(
    val providerId: String,
    val model: String,
    val datasetVersion: String,
)

data class KnowledgeRelevanceFeatureEvaluation(
    val featureSet: KnowledgeRelevanceFeatureSet,
    val thresholds: Map<KnowledgeRelevanceFeature, Double>,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val decisionStableRate: Double,
    val balancedAccuracy: Double,
)

data class KnowledgeRelevanceFeatureGate(
    val featureSet: KnowledgeRelevanceFeatureSet,
    val thresholds: Map<KnowledgeRelevanceFeature, Double>,
    val calibrationPositiveAcceptanceRate: Double,
    val calibrationNearNegativeRejectionRate: Double,
    val calibrationFarNegativeRejectionRate: Double,
    val calibrationDecisionStableRate: Double,
    val calibrationBalancedAccuracy: Double,
) {
    fun accepts(features: KnowledgeRelevanceFeatureVector): Boolean = featureSet.features.all { feature ->
        features.value(feature) >= thresholds.getValue(feature)
    }
}

data class KnowledgeRelevanceFeatureComparisonReport(
    val calibrationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    val validationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    val calibrationGates: Map<KnowledgeRelevanceFeatureSet, KnowledgeRelevanceFeatureGate>,
    val validationEvaluations: Map<KnowledgeRelevanceFeatureSet, KnowledgeRelevanceFeatureEvaluation>,
)

object KnowledgeRelevanceFeatureComparisonPolicy {
    fun compare(
        calibrationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        validationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        calibrationSamples: List<KnowledgeRelevanceFeatureSample>,
        validationSamples: List<KnowledgeRelevanceFeatureSample>,
    ): KnowledgeRelevanceFeatureComparisonReport {
        validateIdentities(calibrationIdentity, validationIdentity)
        val calibrationGates = selectCalibrationGates(calibrationSamples)
        return KnowledgeRelevanceFeatureComparisonReport(
            calibrationIdentity = calibrationIdentity,
            validationIdentity = validationIdentity,
            calibrationGates = calibrationGates,
            validationEvaluations = evaluateFrozenGates(calibrationGates, validationSamples),
        )
    }

    fun selectCalibrationGates(
        calibrationSamples: List<KnowledgeRelevanceFeatureSample>,
    ): Map<KnowledgeRelevanceFeatureSet, KnowledgeRelevanceFeatureGate> {
        validateSamples(calibrationSamples, "校准")
        return KnowledgeRelevanceFeatureSet.entries.associateWith { featureSet ->
            selectGate(featureSet, calibrationSamples)
        }
    }

    fun evaluateFrozenGates(
        gates: Map<KnowledgeRelevanceFeatureSet, KnowledgeRelevanceFeatureGate>,
        validationSamples: List<KnowledgeRelevanceFeatureSample>,
    ): Map<KnowledgeRelevanceFeatureSet, KnowledgeRelevanceFeatureEvaluation> {
        validateSamples(validationSamples, "验证")
        require(gates.keys == KnowledgeRelevanceFeatureSet.entries.toSet()) {
            "冻结特征门禁必须覆盖全部预注册特征族"
        }
        return KnowledgeRelevanceFeatureSet.entries.associateWith { featureSet ->
            evaluate(featureSet, gates.getValue(featureSet).thresholds, validationSamples)
        }
    }

    private fun selectGate(
        featureSet: KnowledgeRelevanceFeatureSet,
        samples: List<KnowledgeRelevanceFeatureSample>,
    ): KnowledgeRelevanceFeatureGate {
        // long: 阈值只从校准集真实观测点的笛卡尔积中选择，验证集不能参与回调或重新选参。
        val candidates = thresholdCombinations(featureSet, samples).map { thresholds ->
            evaluate(featureSet, thresholds, samples)
        }
        val best = candidates.maxWithOrNull(::compareCalibrationCandidates)
            ?: error("特征门禁候选不能为空")
        return KnowledgeRelevanceFeatureGate(
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
        featureSet: KnowledgeRelevanceFeatureSet,
        samples: List<KnowledgeRelevanceFeatureSample>,
    ): List<Map<KnowledgeRelevanceFeature, Double>> {
        fun expand(
            index: Int,
            thresholds: LinkedHashMap<KnowledgeRelevanceFeature, Double>,
        ): List<Map<KnowledgeRelevanceFeature, Double>> {
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
        featureSet: KnowledgeRelevanceFeatureSet,
        thresholds: Map<KnowledgeRelevanceFeature, Double>,
        samples: List<KnowledgeRelevanceFeatureSample>,
    ): KnowledgeRelevanceFeatureEvaluation {
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
        // long: 三个标签桶等权，避免远负例数量或重复运行次数掩盖正例误拒和近负例误接纳。
        val balancedAccuracy = (
            positiveAcceptanceRate + nearNegativeRejectionRate + farNegativeRejectionRate
            ) / KnowledgeRelevanceLabel.entries.size
        return KnowledgeRelevanceFeatureEvaluation(
            featureSet = featureSet,
            thresholds = thresholds,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            decisionStableRate = decisionStableRate,
            balancedAccuracy = balancedAccuracy,
        )
    }

    private fun compareCalibrationCandidates(
        left: KnowledgeRelevanceFeatureEvaluation,
        right: KnowledgeRelevanceFeatureEvaluation,
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
        // 同分时优先较高阈值，保持与旧 calibration policy 一致且结果可复现。
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
        samples: List<KnowledgeRelevanceFeatureSample>,
        phase: String,
    ) {
        require(samples.isNotEmpty()) { "$phase 特征比较样本不能为空" }
        require(samples.all { it.caseId.isNotBlank() }) { "$phase 特征比较用例 ID 不能为空" }
        require(samples.all { sample ->
            KnowledgeRelevanceFeature.values().all { sample.features.value(it).isFinite() }
        }) { "$phase 特征比较分数必须是有限值" }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "$phase 特征比较必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) { "同一特征比较用例不能跨标签" }
    }

    private fun validateIdentities(
        calibrationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        validationIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
    ) {
        val identities = listOf(calibrationIdentity, validationIdentity)
        require(identities.all { it.providerId.isNotBlank() && it.model.isNotBlank() && it.datasetVersion.isNotBlank() }) {
            "特征比较数据集身份不能为空"
        }
        // long: validation 只验证同一 Provider/模型下由 calibration 冻结的 gate；版本相同则不是独立证据。
        require(calibrationIdentity.providerId == validationIdentity.providerId) {
            "特征比较 calibration 与 validation Provider 必须一致"
        }
        require(calibrationIdentity.model == validationIdentity.model) {
            "特征比较 calibration 与 validation 模型必须一致"
        }
        require(calibrationIdentity.datasetVersion != validationIdentity.datasetVersion) {
            "特征比较 validation 不能复用 calibration 数据集"
        }
    }
}
