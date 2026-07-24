package com.longdev.xiaoling.knowledge

enum class KnowledgeRelevanceRolloutMode {
    SHADOW,
    ENFORCE,
}

enum class KnowledgeRelevanceRolloutReason {
    DISABLED_BY_USER,
    MATCHING_FROZEN_GATE,
    STALE_GATE_VERSION,
    IDENTITY_MISMATCH,
    INCOMPLETE_PREFERENCE,
    PRODUCTION_IDENTITY_UNVERIFIED,
    IDENTITY_BINDING_MISMATCH,
}

data class KnowledgeRelevanceRolloutPreference(
    val enforcementEnabled: Boolean = false,
    val gateVersion: String? = null,
    val providerId: String? = null,
    val model: String? = null,
    val identityEvidenceVersion: String? = null,
    val configurationFingerprint: String? = null,
)

data class KnowledgeRelevanceRolloutResolution(
    val mode: KnowledgeRelevanceRolloutMode,
    val reason: KnowledgeRelevanceRolloutReason,
    val enforcementEnabled: Boolean,
    val activeGateVersion: String,
)

/**
 * long: 灰度资格必须与冻结 gate 的 Provider、模型和版本同时匹配；任一身份漂移都回到 shadow，避免旧开关意外作用到新模型或新阈值。
 */
object KnowledgeRelevanceRolloutPolicy {
    fun resolve(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        preference: KnowledgeRelevanceRolloutPreference,
    ): KnowledgeRelevanceRolloutResolution {
        validateGate(frozenGate)
        if (!preference.enforcementEnabled) {
            return shadow(frozenGate, KnowledgeRelevanceRolloutReason.DISABLED_BY_USER)
        }
        val gateVersion = preference.gateVersion
        val providerId = preference.providerId
        val model = preference.model
        if (gateVersion.isNullOrBlank() || providerId.isNullOrBlank() || model.isNullOrBlank()) {
            return shadow(frozenGate, KnowledgeRelevanceRolloutReason.INCOMPLETE_PREFERENCE)
        }
        if (gateVersion != frozenGate.gateVersion) {
            // long: gate 更新后旧偏好不能自动继承执行权限，必须重新完成当前 gate 的显式灰度确认。
            return shadow(frozenGate, KnowledgeRelevanceRolloutReason.STALE_GATE_VERSION)
        }
        val identity = frozenGate.calibrationIdentity
        if (providerId != identity.providerId || model != identity.model) {
            return shadow(frozenGate, KnowledgeRelevanceRolloutReason.IDENTITY_MISMATCH)
        }
        return KnowledgeRelevanceRolloutResolution(
            mode = KnowledgeRelevanceRolloutMode.ENFORCE,
            reason = KnowledgeRelevanceRolloutReason.MATCHING_FROZEN_GATE,
            enforcementEnabled = true,
            activeGateVersion = frozenGate.gateVersion,
        )
    }

    fun rollback(preference: KnowledgeRelevanceRolloutPreference): KnowledgeRelevanceRolloutPreference {
        // long: 撤销只清除未来执行资格，不删除历史检索或改变已展示的答案，保证回滚是可逆的配置动作。
        return preference.copy(
            enforcementEnabled = false,
            gateVersion = null,
            providerId = null,
            model = null,
            identityEvidenceVersion = null,
            configurationFingerprint = null,
        )
    }

    private fun shadow(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        reason: KnowledgeRelevanceRolloutReason,
    ) = KnowledgeRelevanceRolloutResolution(
        mode = KnowledgeRelevanceRolloutMode.SHADOW,
        reason = reason,
        enforcementEnabled = false,
        activeGateVersion = frozenGate.gateVersion,
    )

    private fun validateGate(gate: KnowledgeRelevanceRawTopScoreFrozenGate) {
        require(gate.gateVersion.isNotBlank()) { "相关性灰度 gate 版本不能为空" }
        require(gate.minimumRawTopScore.isFinite()) { "相关性灰度阈值必须是有限值" }
        val calibration = gate.calibrationIdentity
        val validation = gate.validationIdentity
        require(
            calibration.providerId.isNotBlank() &&
                calibration.model.isNotBlank() &&
                calibration.datasetVersion.isNotBlank()
        ) {
            "相关性灰度 calibration Provider/模型不能为空"
        }
        require(
            validation.providerId.isNotBlank() &&
                validation.model.isNotBlank() &&
                validation.datasetVersion.isNotBlank()
        ) {
            "相关性灰度 validation Provider/模型不能为空"
        }
        require(validation.providerId == calibration.providerId && validation.model == calibration.model) {
            "相关性灰度 calibration 与 validation Provider/模型必须一致"
        }
        // long: 同一数据集既调参又验收无法证明 gate 能泛化，灰度资格必须继续沿用冻结阶段的独立 validation 边界。
        require(validation.datasetVersion != calibration.datasetVersion) {
            "相关性灰度 calibration 与 validation 数据集必须不同"
        }
    }
}

data class KnowledgeRelevanceRolloutControlSnapshot(
    val resolution: KnowledgeRelevanceRolloutResolution,
    val bindingStatus: KnowledgeRelevanceProductionIdentityStatus,
    val bindingIdentity: KnowledgeRelevanceProductionIdentity?,
    val bindingEvidenceVersion: String?,
    val rollbackAvailable: Boolean,
)

/**
 * long: 生产控制面把“用户打开开关”和“当前真实 Provider 已完成正式身份验证”分开；候选或撤销身份永远只能显示 shadow，不能把实验资格带入答案路径。
 */
object KnowledgeRelevanceRolloutControlPlane {
    fun resolve(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        binding: KnowledgeRelevanceProductionIdentityBinding,
        preference: KnowledgeRelevanceRolloutPreference,
    ): KnowledgeRelevanceRolloutControlSnapshot {
        val baseResolution = KnowledgeRelevanceRolloutPolicy.resolve(frozenGate, preference)
        if (!preference.enforcementEnabled) {
            return snapshot(baseResolution, binding, rollbackAvailable = false)
        }
        val identity = binding.identity
        val expectedIdentity = frozenGate.calibrationIdentity
        val bindingInvalid = binding.status != KnowledgeRelevanceProductionIdentityStatus.VERIFIED ||
            identity == null
        if (bindingInvalid) {
            return snapshot(
                baseResolution.copy(
                    mode = KnowledgeRelevanceRolloutMode.SHADOW,
                    reason = KnowledgeRelevanceRolloutReason.PRODUCTION_IDENTITY_UNVERIFIED,
                    enforcementEnabled = false,
                ),
                binding,
                rollbackAvailable = true,
            )
        }
        val preferenceMatchesBinding = preference.identityEvidenceVersion == binding.evidenceVersion &&
            preference.configurationFingerprint == identity.configurationFingerprint
        val identityMatchesGate = identity.providerId == expectedIdentity.providerId &&
            identity.model == expectedIdentity.model &&
            binding.gateVersion == frozenGate.gateVersion
        if (!preferenceMatchesBinding || !identityMatchesGate) {
            return snapshot(
                baseResolution.copy(
                    mode = KnowledgeRelevanceRolloutMode.SHADOW,
                    reason = KnowledgeRelevanceRolloutReason.IDENTITY_BINDING_MISMATCH,
                    enforcementEnabled = false,
                ),
                binding,
                rollbackAvailable = true,
            )
        }
        return snapshot(baseResolution, binding, rollbackAvailable = true)
    }

    private fun snapshot(
        resolution: KnowledgeRelevanceRolloutResolution,
        binding: KnowledgeRelevanceProductionIdentityBinding,
        rollbackAvailable: Boolean,
    ) = KnowledgeRelevanceRolloutControlSnapshot(
        resolution = resolution,
        bindingStatus = binding.status,
        bindingIdentity = binding.identity,
        bindingEvidenceVersion = binding.evidenceVersion,
        rollbackAvailable = rollbackAvailable,
    )
}
