package com.longdev.xiaoling.knowledge

/**
 * long: 第 90 阶段把正式 Provider 的配置指纹带入 calibration/validation 身份，避免同一模型换端点后继续沿用旧实验结论。
 */
data class KnowledgeRelevanceProductionDatasetIdentity(
    val providerId: String,
    val model: String,
    val configurationFingerprint: String,
    val datasetVersion: String,
) {
    init {
        require(providerId.isNotBlank()) { "正式相关性数据集 Provider ID 不能为空" }
        require(model.isNotBlank()) { "正式相关性数据集模型不能为空" }
        require(configurationFingerprint.isNotBlank()) { "正式相关性数据集配置指纹不能为空" }
        require(datasetVersion.isNotBlank()) { "正式相关性数据集版本不能为空" }
    }

    fun asFeatureDatasetIdentity() = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = providerId,
        model = model,
        datasetVersion = datasetVersion,
    )
}

data class KnowledgeRelevanceProductionCalibrationReport(
    val productionIdentity: KnowledgeRelevanceProductionIdentity,
    val calibrationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
    val validationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
    val featureComparison: KnowledgeRelevanceFeatureComparisonReport,
)

/**
 * long: 正式身份的 calibration/validation 只建立证据，不冻结或启用生产门禁；两套数据必须同 Provider、模型、配置指纹且版本不同。
 */
object KnowledgeRelevanceProductionCalibrationPolicy {
    fun compare(
        productionIdentity: KnowledgeRelevanceProductionIdentity,
        calibrationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
        validationIdentity: KnowledgeRelevanceProductionDatasetIdentity,
        calibrationSamples: List<KnowledgeRelevanceFeatureSample>,
        validationSamples: List<KnowledgeRelevanceFeatureSample>,
    ): KnowledgeRelevanceProductionCalibrationReport {
        validateIdentity(productionIdentity, calibrationIdentity, validationIdentity)
        val featureComparison = KnowledgeRelevanceFeatureComparisonPolicy.compare(
            calibrationIdentity = calibrationIdentity.asFeatureDatasetIdentity(),
            validationIdentity = validationIdentity.asFeatureDatasetIdentity(),
            calibrationSamples = calibrationSamples,
            validationSamples = validationSamples,
        )
        return KnowledgeRelevanceProductionCalibrationReport(
            productionIdentity = productionIdentity,
            calibrationIdentity = calibrationIdentity,
            validationIdentity = validationIdentity,
            featureComparison = featureComparison,
        )
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
        }) {
            "正式相关性证据的 Provider、模型或配置指纹与生产身份不一致"
        }
        require(calibrationIdentity.datasetVersion != validationIdentity.datasetVersion) {
            "正式相关性 calibration 与 validation 数据集必须不同"
        }
    }
}
