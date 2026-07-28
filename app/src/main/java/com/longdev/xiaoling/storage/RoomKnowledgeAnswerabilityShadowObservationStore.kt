package com.longdev.xiaoling.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import com.longdev.xiaoling.data.KnowledgeAnswerabilityShadowObservationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityDecision
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeFailureKind
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeIdentity
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBindingStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationLedger
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationRecord
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class RoomKnowledgeAnswerabilityShadowObservationStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context.applicationContext),
) : KnowledgeAnswerabilityShadowObservationLedger {
    private val dao = database.knowledgeAnswerabilityShadowObservationDao()

    override suspend fun persist(record: KnowledgeAnswerabilityShadowObservationRecord) {
        // long: 指纹字段是唯一可能承接候选身份的入口，落库前强制校验摘要形状，防止调用方误把问题或答案正文直接写进匿名账本。
        require(record.idempotencyKey.matches(SHA_256_PATTERN)) { "answerability shadow 幂等键必须是 SHA-256" }
        require(record.candidateFingerprint.matches(SHA_256_PATTERN)) { "answerability shadow 候选指纹必须是 SHA-256" }
        database.withTransaction {
            // long: 幂等键是唯一主键，重复发布同一消息只保留首次观测；同一事务裁剪旧样本，避免旁路质量账本无限增长。
            dao.insert(record.toEntity())
            dao.pruneToLatest(KnowledgeAnswerabilityShadowPersistentSummary.MAX_RETAINED_OBSERVATIONS)
        }
    }

    override suspend fun summary(): KnowledgeAnswerabilityShadowPersistentSummary {
        return dao.getAll().toPersistentSummary()
    }

    private fun KnowledgeAnswerabilityShadowObservationRecord.toEntity(): KnowledgeAnswerabilityShadowObservationEntity {
        val failures = telemetry.failureCounts.toMutableMap()
        if (failureKind != null && failures[failureKind] == null) {
            // long: 候选校验或意外异常可能没有 Provider attempt 遥测；最终稳定失败分类仍要记一次，不能让跨进程分布静默漏样本。
            failures[failureKind] = 1
        }
        return KnowledgeAnswerabilityShadowObservationEntity(
            idempotencyKey = idempotencyKey,
            candidateFingerprint = candidateFingerprint,
            judgeFingerprint = judgeIdentity?.toPrivacyFingerprint(),
            attemptCount = attemptCount,
            observationStatus = observationStatus.name,
            bindingStatus = bindingStatus?.name,
            bindingReason = bindingReason?.name,
            decision = decision.name,
            failureKind = failureKind?.name,
            latencyMs = telemetry.latencyMs,
            firstByteLatencyMs = telemetry.firstByteLatencyMs,
            promptBytes = telemetry.promptBytes,
            inputTokens = telemetry.inputTokens,
            outputTokens = telemetry.outputTokens,
            totalTokens = telemetry.totalTokens,
            usageSamples = telemetry.usageSamples,
            transientNetworkFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK),
            rateLimitFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.RATE_LIMIT),
            serverFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.SERVER),
            protocolFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL),
            authenticationFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION),
            clientRequestFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST),
            identityFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.IDENTITY),
            modelFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.MODEL),
            invalidCandidateFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE),
            unexpectedFailureCount = failures.count(KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED),
            recordedAt = recordedAt,
        )
    }

    private fun List<KnowledgeAnswerabilityShadowObservationEntity>.toPersistentSummary(): KnowledgeAnswerabilityShadowPersistentSummary {
        val failures = KnowledgeAnswerabilityJudgeFailureKind.entries.associateWith { kind ->
            fold(0) { total, entity -> cappedAdd(total, entity.failureCount(kind)) }
        }.filterValues { it > 0 }
        return KnowledgeAnswerabilityShadowPersistentSummary(
            observationCount = size.coerceAtMost(Int.MAX_VALUE),
            judgeIdentityCount = mapNotNull { it.judgeFingerprint }.distinct().size,
            completedCount = countStatus(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED),
            unknownCount = countStatus(KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN),
            boundCount = countBinding(KnowledgeAnswerabilityShadowBindingStatus.BOUND),
            bindingUnknownCount = countBinding(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN),
            acceptCount = countDecision(KnowledgeAnswerabilityDecision.ACCEPT),
            rejectCount = countDecision(KnowledgeAnswerabilityDecision.REJECT),
            undecidedCount = countDecision(KnowledgeAnswerabilityDecision.UNKNOWN),
            judgeAttemptCount = fold(0) { total, entity -> cappedAdd(total, entity.attemptCount) },
            latencyMs = sumNullable { it.latencyMs },
            firstByteLatencyMs = sumNullable { it.firstByteLatencyMs },
            promptBytes = sumNullable { it.promptBytes },
            inputTokens = sumNullable { it.inputTokens },
            outputTokens = sumNullable { it.outputTokens },
            totalTokens = sumNullable { it.totalTokens },
            usageSampleCount = fold(0) { total, entity -> cappedAdd(total, entity.usageSamples) },
            failureCounts = failures,
            oldestRecordedAt = firstOrNull()?.recordedAt,
            latestRecordedAt = lastOrNull()?.recordedAt,
        )
    }

    private fun Map<KnowledgeAnswerabilityJudgeFailureKind, Int>.count(kind: KnowledgeAnswerabilityJudgeFailureKind): Int =
        get(kind)?.coerceAtLeast(0) ?: 0

    private fun KnowledgeAnswerabilityJudgeIdentity.toPrivacyFingerprint(): String {
        // long: 校准必须能发现 Judge 配置漂移，但 Provider ID、模型和 prompt 版本都不能原样落库；长度前缀避免不同字段拼接产生同形输入。
        val canonical = buildString {
            listOf(providerId, model, configurationFingerprint, promptVersion).forEach { value ->
                append(value.length)
                append(':')
                append(value)
                append('|')
            }
        }
        // long: Judge 配置字段可枚举，必须用 Keystore 内不可导出的安装级密钥做 HMAC；数据库单独泄露时不能反查或跨安装关联 Provider/模型组合。
        val bytes = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(getOrCreateJudgeFingerprintKey())
            doFinal(canonical.toByteArray(Charsets.UTF_8))
        }
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }

    private fun getOrCreateJudgeFingerprintKey(): SecretKey = synchronized(JUDGE_FINGERPRINT_KEY_LOCK) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(JUDGE_FINGERPRINT_KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(JUDGE_FINGERPRINT_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun List<KnowledgeAnswerabilityShadowObservationEntity>.countStatus(
        status: KnowledgeAnswerabilityShadowObservationStatus,
    ): Int = count { it.observationStatus == status.name }

    private fun List<KnowledgeAnswerabilityShadowObservationEntity>.countBinding(
        status: KnowledgeAnswerabilityShadowBindingStatus,
    ): Int = count { it.bindingStatus == status.name }

    private fun List<KnowledgeAnswerabilityShadowObservationEntity>.countDecision(
        decision: KnowledgeAnswerabilityDecision,
    ): Int = count { it.decision == decision.name }

    private fun List<KnowledgeAnswerabilityShadowObservationEntity>.sumNullable(
        selector: (KnowledgeAnswerabilityShadowObservationEntity) -> Long?,
    ): Long? {
        var found = false
        var total = 0L
        forEach { entity ->
            selector(entity)?.let { value ->
                found = true
                total = cappedAdd(total, value)
            }
        }
        return total.takeIf { found }
    }

    private fun KnowledgeAnswerabilityShadowObservationEntity.failureCount(
        kind: KnowledgeAnswerabilityJudgeFailureKind,
    ): Int = when (kind) {
        KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK -> transientNetworkFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.RATE_LIMIT -> rateLimitFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.SERVER -> serverFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL -> protocolFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION -> authenticationFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST -> clientRequestFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.IDENTITY -> identityFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.MODEL -> modelFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE -> invalidCandidateFailureCount
        KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED -> unexpectedFailureCount
    }

    private fun cappedAdd(left: Int, right: Int): Int {
        val safeRight = right.coerceAtLeast(0)
        return if (Int.MAX_VALUE - left < safeRight) Int.MAX_VALUE else left + safeRight
    }

    private fun cappedAdd(left: Long, right: Long): Long {
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - left < safeRight) Long.MAX_VALUE else left + safeRight
    }

    private companion object {
        val SHA_256_PATTERN = Regex("^[0-9a-f]{64}$")
        const val JUDGE_FINGERPRINT_KEY_ALIAS = "xiaoling_answerability_shadow_judge_fingerprint"
        val JUDGE_FINGERPRINT_KEY_LOCK = Any()
    }
}
