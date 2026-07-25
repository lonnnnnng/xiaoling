package com.longdev.xiaoling.knowledge

import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

enum class KnowledgeAnswerabilityShadowObservationMode {
    DISABLED,
    SHADOW,
}

enum class KnowledgeAnswerabilityShadowObservationOrigin {
    DIRECT_FOREGROUND,
    NON_DIRECT,
}

enum class KnowledgeAnswerabilityShadowPersistenceMode {
    NONE,
    OPTIONAL,
}

enum class KnowledgeAnswerabilityShadowObservationStatus {
    SKIPPED,
    COMPLETED,
    UNKNOWN,
}

enum class KnowledgeAnswerabilityShadowSkipReason {
    DISABLED,
    UNSUPPORTED_ORIGIN,
}

enum class KnowledgeAnswerabilityShadowPersistenceStatus {
    NOT_REQUESTED,
    PERSISTED,
    FAILED,
}

enum class KnowledgeAnswerabilityJudgeFailureKind {
    TRANSIENT_NETWORK,
    RATE_LIMIT,
    SERVER,
    PROTOCOL,
    AUTHENTICATION,
    CLIENT_REQUEST,
    IDENTITY,
    MODEL,
    INVALID_CANDIDATE,
    UNEXPECTED,
}

class KnowledgeAnswerabilityJudgeFailure(
    val kind: KnowledgeAnswerabilityJudgeFailureKind,
    cause: Throwable? = null,
    val telemetry: KnowledgeAnswerabilityShadowAttemptTelemetry? = null,
) : RuntimeException(kind.name, cause)

data class KnowledgeAnswerabilityJudgeRequest(
    val sourceRunId: String,
    val persistedMessageId: String,
    val question: String,
    val candidateText: String,
    val references: List<KnowledgeReference>,
    val expectedIdentity: KnowledgeAnswerabilityJudgeIdentity,
)

data class KnowledgeAnswerabilityJudgeResponse(
    val identity: KnowledgeAnswerabilityJudgeIdentity,
    val output: KnowledgeAnswerabilityModelOutput,
    val telemetry: KnowledgeAnswerabilityShadowAttemptTelemetry? = null,
)

fun interface KnowledgeAnswerabilityJudgePort {
    suspend fun judge(request: KnowledgeAnswerabilityJudgeRequest): KnowledgeAnswerabilityJudgeResponse
}

fun interface KnowledgeAnswerabilityShadowObservationStore {
    suspend fun persist(record: KnowledgeAnswerabilityShadowObservationRecord)
}

data class KnowledgeAnswerabilityShadowObservationRequest(
    val persistedMessageId: String,
    val candidate: KnowledgeAnswerabilityShadowCandidate,
    val frozenBinding: KnowledgeAnswerabilityFrozenBinding?,
    val mode: KnowledgeAnswerabilityShadowObservationMode =
        KnowledgeAnswerabilityShadowObservationMode.DISABLED,
    val origin: KnowledgeAnswerabilityShadowObservationOrigin,
    val persistenceMode: KnowledgeAnswerabilityShadowPersistenceMode,
) {
    init {
        require(persistedMessageId.isNotBlank()) { "answerability shadow 消息 ID 不能为空" }
    }
}

/**
 * long: shadow 存储只保留复现与审计所需的分类信息和不可逆指纹，候选正文、模型原始响应及引用正文都不会落库。
 */
data class KnowledgeAnswerabilityShadowObservationRecord(
    val sourceRunId: String,
    val persistedMessageId: String,
    val candidateFingerprint: String,
    val idempotencyKey: String,
    val judgeIdentity: KnowledgeAnswerabilityJudgeIdentity?,
    val attemptCount: Int,
    val observationStatus: KnowledgeAnswerabilityShadowObservationStatus,
    val bindingStatus: KnowledgeAnswerabilityShadowBindingStatus?,
    val bindingReason: KnowledgeAnswerabilityShadowBindingReason?,
    val decision: KnowledgeAnswerabilityDecision,
    val failureKind: KnowledgeAnswerabilityJudgeFailureKind?,
    val recordedAt: Long,
)

data class KnowledgeAnswerabilityShadowObservationOutcome(
    val status: KnowledgeAnswerabilityShadowObservationStatus,
    val skipReason: KnowledgeAnswerabilityShadowSkipReason? = null,
    val attemptCount: Int = 0,
    val failureKind: KnowledgeAnswerabilityJudgeFailureKind? = null,
    val binding: KnowledgeAnswerabilityShadowBinding? = null,
    val persistenceStatus: KnowledgeAnswerabilityShadowPersistenceStatus =
        KnowledgeAnswerabilityShadowPersistenceStatus.NOT_REQUESTED,
    val telemetry: KnowledgeAnswerabilityShadowTelemetry = KnowledgeAnswerabilityShadowTelemetry.EMPTY,
)

/**
 * long: 这是线上 answerability shadow 的唯一入口。它只在答案已发布后生成旁路观测，任何失败都不能阻塞或改写原答案。
 */
class KnowledgeAnswerabilityShadowObservationCoordinator(
    private val judgePort: KnowledgeAnswerabilityJudgePort,
    private val clock: () -> Long,
    private val store: KnowledgeAnswerabilityShadowObservationStore? = null,
) {
    suspend fun observe(
        request: KnowledgeAnswerabilityShadowObservationRequest,
    ): KnowledgeAnswerabilityShadowObservationOutcome {
        // long: shadow 必须由生产控制面显式开启；请求未声明模式时保持关闭，避免新增旁路能力后自动产生模型成本或用户提示。
        if (request.mode == KnowledgeAnswerabilityShadowObservationMode.DISABLED) {
            return KnowledgeAnswerabilityShadowObservationOutcome(
                status = KnowledgeAnswerabilityShadowObservationStatus.SKIPPED,
                skipReason = KnowledgeAnswerabilityShadowSkipReason.DISABLED,
            )
        }
        // long: 当前只允许用户刚触发的前台 Agent 答案进入 shadow，后台或间接来源不能继承前台 Judge 权限。
        if (request.origin != KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND) {
            return KnowledgeAnswerabilityShadowObservationOutcome(
                status = KnowledgeAnswerabilityShadowObservationStatus.SKIPPED,
                skipReason = KnowledgeAnswerabilityShadowSkipReason.UNSUPPORTED_ORIGIN,
            )
        }

        // long: 进入异步 Judge 前复制引用快照，保证重试、绑定和可选持久化都针对同一份已发布答案证据。
        val candidate = request.candidate.copy(references = request.candidate.references.toList())
        val frozenRequest = request.copy(candidate = candidate)
        // long: 候选证据不完整时发送 Judge 请求既无法形成可回查结论，也可能泄露与当前 Run 无法绑定的正文，因此直接保守收敛。
        if (!candidate.isValid()) {
            return unknownWithoutMeasurement(
                request = frozenRequest,
                judgeIdentity = request.frozenBinding?.judgeIdentity,
                failureKind = KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE,
            )
        }

        val frozenBinding = request.frozenBinding
        // long: 没有冻结 Judge 身份和门禁时无法解释线上测量，禁止先调用模型再用当前配置临时补造绑定。
        if (frozenBinding == null) {
            return unknownWithoutMeasurement(
                request = frozenRequest,
                judgeIdentity = null,
            )
        }

        val judgeRequest = KnowledgeAnswerabilityJudgeRequest(
            sourceRunId = candidate.sourceRunId,
            persistedMessageId = request.persistedMessageId,
            question = candidate.question,
            candidateText = candidate.candidateText,
            references = candidate.references,
            expectedIdentity = frozenBinding.judgeIdentity,
        )
        var attemptCount = 0
        var telemetry = KnowledgeAnswerabilityShadowTelemetry.EMPTY
        while (attemptCount < MAX_ATTEMPTS) {
            attemptCount += 1
            try {
                val response = judgePort.judge(judgeRequest)
                response.telemetry?.let { telemetry = telemetry.plus(it) }
                val measurement = KnowledgeAnswerabilityShadowMeasurement.fromModelOutput(
                    sourceRunId = candidate.sourceRunId,
                    candidateText = candidate.candidateText,
                    output = response.output,
                )
                val observedAt = clock()
                val binding = KnowledgeAnswerabilityShadowBindingPolicy.bind(
                    candidate = candidate,
                    actualJudgeIdentity = response.identity,
                    frozenBinding = frozenBinding,
                    measurement = measurement,
                    observedAt = observedAt,
                )
                return persistIfRequested(
                    request = frozenRequest,
                    outcome = KnowledgeAnswerabilityShadowObservationOutcome(
                        status = KnowledgeAnswerabilityShadowObservationStatus.COMPLETED,
                        attemptCount = attemptCount,
                        binding = binding,
                        telemetry = telemetry,
                    ),
                    judgeIdentity = response.identity,
                    recordedAt = observedAt,
                )
            } catch (error: CancellationException) {
                // long: 页面或上层任务取消意味着这次观测不存在，继续传播才能避免伪造 UNKNOWN 和残留持久化记录。
                throw error
            } catch (failure: KnowledgeAnswerabilityJudgeFailure) {
                failure.telemetry?.let { telemetry = telemetry.plus(it) }
                telemetry = telemetry.recordFailure(failure.kind)
                // long: 只重试可能自行恢复的传输、限流、服务端和协议故障；认证与请求错误继续请求只会放大成本和延迟。
                if (failure.kind.isRetryable() && attemptCount < MAX_ATTEMPTS) {
                    continue
                }
                return unknownWithoutMeasurement(
                    request = frozenRequest,
                    judgeIdentity = frozenBinding.judgeIdentity,
                    attemptCount = attemptCount,
                    failureKind = failure.kind,
                    telemetry = telemetry,
                )
            } catch (_: Exception) {
                return unknownWithoutMeasurement(
                    request = frozenRequest,
                    judgeIdentity = frozenBinding.judgeIdentity,
                    attemptCount = attemptCount,
                    failureKind = KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED,
                    telemetry = telemetry,
                )
            }
        }
        error("answerability shadow 尝试次数状态无效")
    }

    /**
     * long: 候选校验、绑定缺失和 Judge 终败都必须走同一未知收敛，避免不同分支对时间、绑定或隐私持久化产生不一致解释。
     */
    private suspend fun unknownWithoutMeasurement(
        request: KnowledgeAnswerabilityShadowObservationRequest,
        judgeIdentity: KnowledgeAnswerabilityJudgeIdentity?,
        attemptCount: Int = 0,
        failureKind: KnowledgeAnswerabilityJudgeFailureKind? = null,
        telemetry: KnowledgeAnswerabilityShadowTelemetry = KnowledgeAnswerabilityShadowTelemetry.EMPTY,
    ): KnowledgeAnswerabilityShadowObservationOutcome {
        val recordedAt = clock()
        val binding = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = request.candidate,
            actualJudgeIdentity = judgeIdentity,
            frozenBinding = request.frozenBinding,
            measurement = null,
            observedAt = recordedAt,
        )
        return persistIfRequested(
            request = request,
            outcome = KnowledgeAnswerabilityShadowObservationOutcome(
                status = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
                attemptCount = attemptCount,
                failureKind = failureKind,
                binding = binding,
                telemetry = telemetry,
            ),
            judgeIdentity = judgeIdentity,
            recordedAt = recordedAt,
        )
    }

    private suspend fun persistIfRequested(
        request: KnowledgeAnswerabilityShadowObservationRequest,
        outcome: KnowledgeAnswerabilityShadowObservationOutcome,
        judgeIdentity: KnowledgeAnswerabilityJudgeIdentity?,
        recordedAt: Long,
    ): KnowledgeAnswerabilityShadowObservationOutcome {
        if (request.persistenceMode == KnowledgeAnswerabilityShadowPersistenceMode.NONE) {
            return outcome
        }
        // long: 调用方可以只启用内存 shadow 而不提供 Store；请求持久化但没有适配器时要诚实标记失败，不能用 NoOp 伪装成已落库。
        val observationStore = store ?: return outcome.copy(
            persistenceStatus = KnowledgeAnswerabilityShadowPersistenceStatus.FAILED,
        )
        val candidateFingerprint = candidateFingerprint(request.candidate)
        val record = KnowledgeAnswerabilityShadowObservationRecord(
            sourceRunId = request.candidate.sourceRunId,
            persistedMessageId = request.persistedMessageId,
            candidateFingerprint = candidateFingerprint,
            idempotencyKey = idempotencyKey(
                request = request,
                candidateFingerprint = candidateFingerprint,
                judgeIdentity = judgeIdentity,
            ),
            judgeIdentity = judgeIdentity,
            attemptCount = outcome.attemptCount,
            observationStatus = outcome.status,
            bindingStatus = outcome.binding?.status,
            bindingReason = outcome.binding?.reason,
            decision = outcome.binding?.decision ?: KnowledgeAnswerabilityDecision.UNKNOWN,
            failureKind = outcome.failureKind,
            recordedAt = recordedAt,
        )
        return try {
            observationStore.persist(record)
            outcome.copy(persistenceStatus = KnowledgeAnswerabilityShadowPersistenceStatus.PERSISTED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // long: shadow Store 不可用只降低旁路审计完整度，不能反向改变已经形成的绑定或用户答案。
            outcome.copy(persistenceStatus = KnowledgeAnswerabilityShadowPersistenceStatus.FAILED)
        }
    }

    private fun KnowledgeAnswerabilityShadowCandidate.isValid(): Boolean =
        sourceRunId.isNotBlank() &&
            question.isNotBlank() &&
            candidateText.isNotBlank() &&
            references.isNotEmpty()

    // long: 重试分类集中在协调器内部，调用方只看到最终 outcome，不能自行叠加重试造成超过两次的 Provider 请求。
    private fun KnowledgeAnswerabilityJudgeFailureKind.isRetryable(): Boolean = when (this) {
        KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK,
        KnowledgeAnswerabilityJudgeFailureKind.RATE_LIMIT,
        KnowledgeAnswerabilityJudgeFailureKind.SERVER,
        KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL,
        -> true
        KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION,
        KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST,
        KnowledgeAnswerabilityJudgeFailureKind.IDENTITY,
        KnowledgeAnswerabilityJudgeFailureKind.MODEL,
        KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE,
        KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED,
        -> false
    }

    // long: 候选正文和引用身份只参与不可逆摘要，既能识别同一证据快照，也避免把敏感内容写入 shadow 记录。
    private fun candidateFingerprint(candidate: KnowledgeAnswerabilityShadowCandidate): String = sha256(
        buildString {
            appendCanonical(candidate.sourceRunId)
            appendCanonical(candidate.question)
            appendCanonical(candidate.candidateText)
            candidate.references.forEach { reference ->
                appendCanonical(reference.retrievalId)
                appendCanonical(reference.documentId)
                appendCanonical(reference.documentName)
                appendCanonical(reference.documentRevision.toString())
                appendCanonical(reference.chunkId)
                appendCanonical(reference.chunkSequence.toString())
                appendCanonical(reference.startOffset.toString())
                appendCanonical(reference.endOffset.toString())
            }
        },
    )

    // long: 消息、Run、候选摘要和 Judge 身份共同组成幂等键，身份漂移时不会覆盖原有 shadow 审计结果。
    private fun idempotencyKey(
        request: KnowledgeAnswerabilityShadowObservationRequest,
        candidateFingerprint: String,
        judgeIdentity: KnowledgeAnswerabilityJudgeIdentity?,
    ): String = sha256(
        buildString {
            appendCanonical(request.persistedMessageId)
            appendCanonical(request.candidate.sourceRunId)
            appendCanonical(candidateFingerprint)
            appendCanonical(judgeIdentity?.providerId.orEmpty())
            appendCanonical(judgeIdentity?.model.orEmpty())
            appendCanonical(judgeIdentity?.configurationFingerprint.orEmpty())
            appendCanonical(judgeIdentity?.promptVersion.orEmpty())
        },
    )

    private fun StringBuilder.appendCanonical(value: String) {
        append(value.length)
        append(':')
        append(value)
        append('|')
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
        }
    }

    private companion object {
        // long: 一次首调加一次受控重试是完整上限，终败后立即降级为不影响答案的 UNKNOWN。
        const val MAX_ATTEMPTS = 2
    }
}
