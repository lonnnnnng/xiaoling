package com.longdev.xiaoling.knowledge

import com.longdev.xiaoling.model.ProviderRequestConfig

/**
 * long: 生产 shadow 只能引用 Redmi 独立 calibration/validation 已通过的精确身份和门禁；这里不保存 Base URL 或密钥，只保留不可逆配置指纹。
 */
object KnowledgeAnswerabilityProductionShadowBinding {
    const val PROVIDER_ID = "redmi-provider-compatibility"
    const val MODEL = "gpt-5.5"
    const val CONFIGURATION_FINGERPRINT =
        "03c4b0dbea6451654f254df8ad45e640b25ea4496596b63f82cceb190c51cf6d"
    const val CALIBRATION_DATASET_VERSION = "stage92-answerability-calibration-v1"
    const val VALIDATION_DATASET_VERSION = "stage92-answerability-validation-v1"

    val frozenBinding = KnowledgeAnswerabilityFrozenBinding(
        calibrationIdentity = KnowledgeAnswerabilityDatasetIdentity(
            judgeIdentity = judgeIdentity(),
            datasetVersion = CALIBRATION_DATASET_VERSION,
        ),
        validationIdentity = KnowledgeAnswerabilityDatasetIdentity(
            judgeIdentity = judgeIdentity(),
            datasetVersion = VALIDATION_DATASET_VERSION,
        ),
        gate = KnowledgeAnswerabilityGate(
            featureSet = KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
            minimumConfidence = 0.85,
            minimumEvidenceCoverage = null,
            calibrationPositiveAcceptanceRate = 1.0,
            calibrationNearNegativeRejectionRate = 1.0,
            calibrationFarNegativeRejectionRate = 1.0,
            calibrationDecisionStableRate = 1.0,
            calibrationKnownDecisionRate = 1.0,
            calibrationUnknownRate = 0.0,
            calibrationBalancedAccuracy = 1.0,
        ),
    )

    private fun judgeIdentity() = KnowledgeAnswerabilityJudgeIdentity(
        providerId = PROVIDER_ID,
        model = MODEL,
        configurationFingerprint = CONFIGURATION_FINGERPRINT,
        promptVersion = KnowledgeAnswerabilityJudgeProtocol.PROMPT_VERSION,
    )
}

object KnowledgeAnswerabilityJudgeIdentityFactory {
    fun fromConfig(config: ProviderRequestConfig): KnowledgeAnswerabilityJudgeIdentity {
        val providerId = config.providerId?.trim().orEmpty()
        val model = config.model.trim()
        val baseUrl = config.baseUrl.trim()
        require(providerId.isNotBlank()) { "answerability Judge Provider ID 不能为空" }
        require(model.isNotBlank()) { "answerability Judge 模型不能为空" }
        require(baseUrl.isNotBlank()) { "answerability Judge Base URL 不能为空" }
        // long: 实际身份只能由本次请求的 Provider 配置生成，不能接受 caller 传入的逻辑别名或 expected identity，否则冻结门禁会被调用方自行满足。
        return KnowledgeAnswerabilityJudgeIdentity(
            providerId = providerId,
            model = model,
            configurationFingerprint = KnowledgeRelevanceIdentityFingerprint.forBaseUrl(baseUrl),
            promptVersion = KnowledgeAnswerabilityJudgeProtocol.PROMPT_VERSION,
        )
    }
}

object KnowledgeAnswerabilityShadowActivationPolicy {
    fun modeFor(
        userEnabled: Boolean,
        actualIdentity: KnowledgeAnswerabilityJudgeIdentity?,
        frozenBinding: KnowledgeAnswerabilityFrozenBinding,
    ): KnowledgeAnswerabilityShadowObservationMode {
        // long: 用户开关只表达参与 shadow 的意愿；任何模型、端点指纹或 Prompt 漂移都必须在发起网络请求前 fail-closed。
        return if (userEnabled && actualIdentity == frozenBinding.judgeIdentity) {
            KnowledgeAnswerabilityShadowObservationMode.SHADOW
        } else {
            KnowledgeAnswerabilityShadowObservationMode.DISABLED
        }
    }
}
