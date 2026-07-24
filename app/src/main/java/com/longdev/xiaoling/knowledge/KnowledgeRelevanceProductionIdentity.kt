package com.longdev.xiaoling.knowledge

import java.security.MessageDigest

enum class KnowledgeRelevanceProductionIdentityStatus {
    UNBOUND,
    CANDIDATE,
    VERIFIED,
    REVOKED,
}

enum class KnowledgeRelevanceProductionIdentityReason {
    UNBOUND,
    CANDIDATE_BOUND,
    VERIFIED,
    MISSING_PROVIDER_ID,
    MISSING_MODEL,
    MISSING_CONFIGURATION_FINGERPRINT,
    MODEL_NOT_ADVERTISED,
    INVALID_EMBEDDING_PROBE,
    PROVIDER_MISMATCH,
    MODEL_MISMATCH,
    GATE_MISMATCH,
    DATASET_MISMATCH,
    DATASET_REUSED,
    EVIDENCE_MISSING,
    EVIDENCE_FAILED,
    EVIDENCE_IDENTITY_MISMATCH,
}

/**
 * 只保存不含密钥的 Provider 配置身份；原始 Base URL 不进入 Room 或偏好，避免控制面变成配置泄露面。
 */
data class KnowledgeRelevanceProductionIdentity(
    val providerId: String,
    val model: String,
    val configurationFingerprint: String,
) {
    init {
        require(providerId.isNotBlank()) { "相关性生产身份 Provider ID 不能为空" }
        require(model.isNotBlank()) { "相关性生产身份模型不能为空" }
        require(configurationFingerprint.isNotBlank()) { "相关性生产身份配置指纹不能为空" }
    }
}

data class KnowledgeRelevanceProviderProbe(
    val providerId: String,
    val model: String,
    val configurationFingerprint: String,
    val advertisedModels: List<String>,
    val vectorCount: Int,
    val vectorDimensions: Int,
)

data class KnowledgeRelevanceProductionVerificationEvidence(
    val evidenceVersion: String,
    val gateVersion: String,
    val providerId: String,
    val model: String,
    val configurationFingerprint: String,
    val holdoutDatasetVersion: String,
    val finalHoldoutPassed: Boolean,
)

data class KnowledgeRelevanceProductionIdentityBinding(
    val status: KnowledgeRelevanceProductionIdentityStatus = KnowledgeRelevanceProductionIdentityStatus.UNBOUND,
    val identity: KnowledgeRelevanceProductionIdentity? = null,
    val gateVersion: String? = null,
    val evidenceVersion: String? = null,
    val holdoutDatasetVersion: String? = null,
)

data class KnowledgeRelevanceProductionIdentityResult(
    val accepted: Boolean,
    val reason: KnowledgeRelevanceProductionIdentityReason,
    val binding: KnowledgeRelevanceProductionIdentityBinding,
)

/**
 * long: 第 89 阶段先把真实 Provider 绑定为候选或已验证身份；只有完成同一身份的 final holdout 证据后才允许升级为 VERIFIED，避免把协议可用误当成相关性门禁可用。
 */
object KnowledgeRelevanceProductionIdentityPolicy {
    fun bindCandidate(probe: KnowledgeRelevanceProviderProbe): KnowledgeRelevanceProductionIdentityResult {
        val providerId = probe.providerId.trim()
        if (providerId.isBlank()) return rejected(KnowledgeRelevanceProductionIdentityReason.MISSING_PROVIDER_ID)
        val model = probe.model.trim()
        if (model.isBlank()) return rejected(KnowledgeRelevanceProductionIdentityReason.MISSING_MODEL)
        val fingerprint = probe.configurationFingerprint.trim()
        if (fingerprint.isBlank()) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.MISSING_CONFIGURATION_FINGERPRINT)
        }
        if (probe.vectorCount <= 0 || probe.vectorDimensions <= 0) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.INVALID_EMBEDDING_PROBE)
        }
        if (probe.advertisedModels.none { it.trim().equals(model, ignoreCase = true) }) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.MODEL_NOT_ADVERTISED)
        }
        return KnowledgeRelevanceProductionIdentityResult(
            accepted = true,
            reason = KnowledgeRelevanceProductionIdentityReason.CANDIDATE_BOUND,
            binding = KnowledgeRelevanceProductionIdentityBinding(
                status = KnowledgeRelevanceProductionIdentityStatus.CANDIDATE,
                identity = KnowledgeRelevanceProductionIdentity(
                    providerId = providerId,
                    model = model,
                    configurationFingerprint = fingerprint,
                ),
            ),
        )
    }

    fun promoteVerified(
        candidate: KnowledgeRelevanceProductionIdentityBinding,
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        holdoutIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        evidence: KnowledgeRelevanceProductionVerificationEvidence,
    ): KnowledgeRelevanceProductionIdentityResult {
        val identity = candidate.identity
            ?: return rejected(KnowledgeRelevanceProductionIdentityReason.UNBOUND, candidate)
        if (candidate.status != KnowledgeRelevanceProductionIdentityStatus.CANDIDATE) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.UNBOUND, candidate)
        }
        if (evidence.evidenceVersion.isBlank() || evidence.holdoutDatasetVersion.isBlank()) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.EVIDENCE_MISSING, candidate)
        }
        if (!evidence.finalHoldoutPassed) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.EVIDENCE_FAILED, candidate)
        }
        if (identity.providerId != frozenGate.calibrationIdentity.providerId ||
            identity.providerId != evidence.providerId ||
            identity.providerId != holdoutIdentity.providerId
        ) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.PROVIDER_MISMATCH, candidate)
        }
        if (identity.model != frozenGate.calibrationIdentity.model ||
            identity.model != evidence.model ||
            identity.model != holdoutIdentity.model
        ) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.MODEL_MISMATCH, candidate)
        }
        if (evidence.gateVersion != frozenGate.gateVersion) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.GATE_MISMATCH, candidate)
        }
        if (identity.configurationFingerprint != evidence.configurationFingerprint) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.EVIDENCE_IDENTITY_MISMATCH, candidate)
        }
        if (holdoutIdentity.datasetVersion == frozenGate.calibrationIdentity.datasetVersion ||
            holdoutIdentity.datasetVersion == frozenGate.validationIdentity.datasetVersion ||
            evidence.holdoutDatasetVersion != holdoutIdentity.datasetVersion
        ) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.DATASET_REUSED, candidate)
        }
        if (frozenGate.calibrationIdentity.datasetVersion.isBlank() ||
            frozenGate.validationIdentity.datasetVersion.isBlank() ||
            frozenGate.calibrationIdentity.providerId != frozenGate.validationIdentity.providerId ||
            frozenGate.calibrationIdentity.model != frozenGate.validationIdentity.model ||
            frozenGate.calibrationIdentity.datasetVersion == frozenGate.validationIdentity.datasetVersion
        ) {
            return rejected(KnowledgeRelevanceProductionIdentityReason.DATASET_MISMATCH, candidate)
        }
        return KnowledgeRelevanceProductionIdentityResult(
            accepted = true,
            reason = KnowledgeRelevanceProductionIdentityReason.VERIFIED,
            binding = candidate.copy(
                status = KnowledgeRelevanceProductionIdentityStatus.VERIFIED,
                gateVersion = frozenGate.gateVersion,
                evidenceVersion = evidence.evidenceVersion,
                holdoutDatasetVersion = holdoutIdentity.datasetVersion,
            ),
        )
    }

    fun revoke(binding: KnowledgeRelevanceProductionIdentityBinding): KnowledgeRelevanceProductionIdentityBinding {
        // long: 撤销保留身份和证据指针供审计，但控制面会把 REVOKED 当作不可执行，避免删除历史后误认为从未绑定。
        return binding.copy(status = KnowledgeRelevanceProductionIdentityStatus.REVOKED)
    }

    fun unbound(): KnowledgeRelevanceProductionIdentityBinding = KnowledgeRelevanceProductionIdentityBinding()

    private fun rejected(
        reason: KnowledgeRelevanceProductionIdentityReason,
        binding: KnowledgeRelevanceProductionIdentityBinding = unbound(),
    ) = KnowledgeRelevanceProductionIdentityResult(
        accepted = false,
        reason = reason,
        binding = binding,
    )
}

object KnowledgeRelevanceIdentityFingerprint {
    fun forBaseUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/').takeIf(String::isNotBlank)
            ?: error("相关性生产身份 Base URL 不能为空")
        // long: 只用规范化端点的 SHA-256 参与身份漂移判断，偏好和日志不会暴露真实 URL 或鉴权信息。
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
