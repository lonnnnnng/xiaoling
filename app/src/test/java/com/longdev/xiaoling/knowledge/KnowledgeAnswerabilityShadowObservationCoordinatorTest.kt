package com.longdev.xiaoling.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeAnswerabilityShadowObservationCoordinatorTest {
    @Test
    fun requestDefaultsToDisabledWithoutCallingJudge() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                judgeResponse()
            },
        )

        val outcome = coordinator.observe(
            KnowledgeAnswerabilityShadowObservationRequest(
                persistedMessageId = "message-shadow-observation",
                candidate = candidate(),
                frozenBinding = frozenBinding(),
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.NONE,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.SKIPPED, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowSkipReason.DISABLED, outcome.skipReason)
        assertEquals(0, judgeCalls)
    }

    @Test
    fun optionalPersistenceWithoutStoreDoesNotChangeSuccessfulBinding() = runTest {
        val coordinator = KnowledgeAnswerabilityShadowObservationCoordinator(
            judgePort = KnowledgeAnswerabilityJudgePort { judgeResponse() },
            clock = { 1_234L },
        )

        val outcome = coordinator.observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, outcome.status)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, outcome.binding?.decision)
        assertEquals(KnowledgeAnswerabilityShadowPersistenceStatus.FAILED, outcome.persistenceStatus)
    }

    @Test
    fun disabledModeSkipsJudgeAndPersistence() = runTest {
        var judgeCalls = 0
        val records = mutableListOf<KnowledgeAnswerabilityShadowObservationRecord>()
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                judgeResponse()
            },
            persist = records::add,
        )

        val outcome = coordinator.observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.DISABLED,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.SKIPPED, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowSkipReason.DISABLED, outcome.skipReason)
        assertEquals(0, outcome.attemptCount)
        assertEquals(0, judgeCalls)
        assertTrue(records.isEmpty())
    }

    @Test
    fun nonDirectOriginCannotEnterForegroundMessageShadow() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                judgeResponse()
            },
        )

        val outcome = coordinator.observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.NON_DIRECT,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.SKIPPED, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowSkipReason.UNSUPPORTED_ORIGIN, outcome.skipReason)
        assertEquals(0, judgeCalls)
    }

    @Test
    fun successfulJudgeCreatesBoundMeasurementWithoutChangingEnforcement() = runTest {
        val coordinator = coordinator(judge = { judgeResponse() })

        val outcome = coordinator.observe(
            request(mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, outcome.status)
        assertEquals(1, outcome.attemptCount)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, outcome.binding?.decision)
        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.BOUND, outcome.binding?.status)
        assertEquals(1_234L, outcome.binding?.observedAt)
        assertEquals("run-shadow-observation", outcome.binding?.measurement?.sourceRunId)
        assertFalse(outcome.binding?.enforcementApplied ?: true)
        assertEquals(KnowledgeAnswerabilityShadowPersistenceStatus.NOT_REQUESTED, outcome.persistenceStatus)
    }

    @Test
    fun transientFailureRetriesOnceThenUsesSuccessfulMeasurement() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                if (judgeCalls == 1) {
                    throw KnowledgeAnswerabilityJudgeFailure(
                        KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK,
                        telemetry = KnowledgeAnswerabilityShadowAttemptTelemetry(
                            latencyMs = 80L,
                            promptBytes = 300L,
                        ),
                    )
                }
                judgeResponse(
                    telemetry = KnowledgeAnswerabilityShadowAttemptTelemetry(
                        latencyMs = 120L,
                        promptBytes = 300L,
                        totalTokens = 24L,
                    ),
                )
            },
        )

        val outcome = coordinator.observe(
            request(mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW),
        )

        assertEquals(2, judgeCalls)
        assertEquals(2, outcome.attemptCount)
        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, outcome.status)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, outcome.binding?.decision)
        assertEquals(2, outcome.telemetry.attempts)
        assertEquals(200L, outcome.telemetry.latencyMs)
        assertEquals(600L, outcome.telemetry.promptBytes)
        assertEquals(24L, outcome.telemetry.totalTokens)
        assertEquals(
            1,
            outcome.telemetry.failureCounts[KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK],
        )
    }

    @Test
    fun authenticationFailureDoesNotRetryAndRemainsUnknown() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                throw KnowledgeAnswerabilityJudgeFailure(
                    KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION,
                )
            },
        )

        val outcome = coordinator.observe(
            request(mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW),
        )

        assertEquals(1, judgeCalls)
        assertEquals(1, outcome.attemptCount)
        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN, outcome.status)
        assertEquals(KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION, outcome.failureKind)
        assertEquals(KnowledgeAnswerabilityShadowBindingReason.MISSING_MEASUREMENT, outcome.binding?.reason)
        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, outcome.binding?.decision)
        assertNull(outcome.binding?.observedAt)
    }

    @Test
    fun protocolFailureExhaustsExactlyTwoAttempts() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                throw KnowledgeAnswerabilityJudgeFailure(
                    KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL,
                )
            },
        )

        val outcome = coordinator.observe(
            request(mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW),
        )

        assertEquals(2, judgeCalls)
        assertEquals(2, outcome.attemptCount)
        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN, outcome.status)
        assertEquals(KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL, outcome.failureKind)
    }

    @Test
    fun cancellationPropagatesWithoutInventingOutcomeOrPersistence() = runTest {
        val records = mutableListOf<KnowledgeAnswerabilityShadowObservationRecord>()
        val coordinator = coordinator(
            judge = { throw CancellationException("页面已离开") },
            persist = records::add,
        )

        try {
            coordinator.observe(
                request(
                    mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                    persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
                ),
            )
            fail("取消必须继续传播")
        } catch (error: CancellationException) {
            assertEquals("页面已离开", error.message)
        }

        assertTrue(records.isEmpty())
    }

    @Test
    fun judgeIdentityDriftKeepsMeasurementButBindingIsUnknown() = runTest {
        val coordinator = coordinator(
            judge = {
                judgeResponse(identity = judgeIdentity().copy(model = "other-model"))
            },
        )

        val outcome = coordinator.observe(
            request(mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN, outcome.binding?.status)
        assertEquals(
            KnowledgeAnswerabilityShadowBindingReason.JUDGE_IDENTITY_MISMATCH,
            outcome.binding?.reason,
        )
        assertEquals(KnowledgeAnswerabilityVerdict.ANSWERED, outcome.binding?.measurement?.verdict)
        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, outcome.binding?.decision)
    }

    @Test
    fun malformedCandidateDoesNotCallJudgeAndReturnsInvalidCandidateBinding() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                judgeResponse()
            },
        )

        val outcome = coordinator.observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                candidate = candidate().copy(candidateText = ""),
            ),
        )

        assertEquals(0, judgeCalls)
        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingReason.INVALID_CANDIDATE, outcome.binding?.reason)
        assertEquals(KnowledgeAnswerabilityJudgeFailureKind.INVALID_CANDIDATE, outcome.failureKind)
    }

    @Test
    fun missingFrozenBindingDoesNotCallJudgeAndRemainsUnknown() = runTest {
        var judgeCalls = 0
        val coordinator = coordinator(
            judge = {
                judgeCalls += 1
                judgeResponse()
            },
        )

        val outcome = coordinator.observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                binding = null,
            ),
        )

        assertEquals(0, judgeCalls)
        assertEquals(0, outcome.attemptCount)
        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN, outcome.status)
        assertEquals(
            KnowledgeAnswerabilityShadowBindingReason.MISSING_FROZEN_BINDING,
            outcome.binding?.reason,
        )
    }

    @Test
    fun persistenceCancellationPropagatesWithoutChangingItToFailure() = runTest {
        val coordinator = coordinator(
            judge = { judgeResponse() },
            persist = { throw CancellationException("Store 已取消") },
        )

        try {
            coordinator.observe(
                request(
                    mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                    persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
                ),
            )
            fail("Store 取消必须继续传播")
        } catch (error: CancellationException) {
            assertEquals("Store 已取消", error.message)
        }
    }

    @Test
    fun optionalPersistenceStoresOnlyFingerprintAndFailureDoesNotChangeBinding() = runTest {
        val records = mutableListOf<KnowledgeAnswerabilityShadowObservationRecord>()
        val stored = coordinator(
            judge = { judgeResponse() },
            persist = records::add,
        ).observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowPersistenceStatus.PERSISTED, stored.persistenceStatus)
        assertEquals(1, records.size)
        assertEquals("run-shadow-observation", records.single().sourceRunId)
        assertEquals(64, records.single().candidateFingerprint.length)
        assertEquals(64, records.single().idempotencyKey.length)
        assertFalse(records.single().candidateFingerprint.contains(candidate().candidateText))
        assertEquals(stored.telemetry, records.single().telemetry)

        val failedPersistence = coordinator(
            judge = { judgeResponse() },
            persist = { throw IllegalStateException("Room 暂不可用") },
        ).observe(
            request(
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, failedPersistence.status)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, failedPersistence.binding?.decision)
        assertEquals(KnowledgeAnswerabilityShadowPersistenceStatus.FAILED, failedPersistence.persistenceStatus)
    }

    private fun coordinator(
        judge: suspend (KnowledgeAnswerabilityJudgeRequest) -> KnowledgeAnswerabilityJudgeResponse,
        persist: suspend (KnowledgeAnswerabilityShadowObservationRecord) -> Unit = {},
    ) = KnowledgeAnswerabilityShadowObservationCoordinator(
        judgePort = KnowledgeAnswerabilityJudgePort(judge),
        store = KnowledgeAnswerabilityShadowObservationStore(persist),
        clock = { 1_234L },
    )

    private fun request(
        mode: KnowledgeAnswerabilityShadowObservationMode = KnowledgeAnswerabilityShadowObservationMode.DISABLED,
        origin: KnowledgeAnswerabilityShadowObservationOrigin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
        persistenceMode: KnowledgeAnswerabilityShadowPersistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.NONE,
        candidate: KnowledgeAnswerabilityShadowCandidate = candidate(),
        binding: KnowledgeAnswerabilityFrozenBinding? = frozenBinding(),
    ) = KnowledgeAnswerabilityShadowObservationRequest(
        persistedMessageId = "message-shadow-observation",
        candidate = candidate,
        frozenBinding = binding,
        mode = mode,
        origin = origin,
        persistenceMode = persistenceMode,
    )

    private fun judgeResponse(
        identity: KnowledgeAnswerabilityJudgeIdentity = judgeIdentity(),
        telemetry: KnowledgeAnswerabilityShadowAttemptTelemetry? = null,
    ) = KnowledgeAnswerabilityJudgeResponse(
        identity = identity,
        output = KnowledgeAnswerabilityModelOutput(
            verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
            confidence = 0.96,
            evidenceQuotes = listOf("番茄工作法把专注时间分成二十五分钟"),
            contradictionDetected = false,
            reasonCode = "DIRECT_EVIDENCE",
        ),
        telemetry = telemetry,
    )

    private fun candidate() = KnowledgeAnswerabilityShadowCandidate(
        sourceRunId = "run-shadow-observation",
        question = "番茄工作法如何划分专注时间？",
        candidateText = "番茄工作法把专注时间分成二十五分钟，并在每轮后安排短休息。",
        references = listOf(
            KnowledgeReference(
                retrievalId = "retrieval-shadow-observation",
                documentId = "document-shadow-observation",
                documentName = "focus.md",
                documentRevision = 1,
                chunkId = "chunk-shadow-observation",
                chunkSequence = 0,
                startOffset = 0,
                endOffset = 32,
            ),
        ),
    )

    private fun frozenBinding() = KnowledgeAnswerabilityFrozenBinding(
        calibrationIdentity = KnowledgeAnswerabilityDatasetIdentity(
            judgeIdentity = judgeIdentity(),
            datasetVersion = "stage92-calibration-v1",
        ),
        validationIdentity = KnowledgeAnswerabilityDatasetIdentity(
            judgeIdentity = judgeIdentity(),
            datasetVersion = "stage92-validation-v1",
        ),
        gate = KnowledgeAnswerabilityGate(
            featureSet = KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
            minimumConfidence = null,
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
        providerId = "redmi-answerability-judge-v1",
        model = "gpt-5.5",
        configurationFingerprint = "fingerprint-v1",
        promptVersion = "stage92-answerability-json-v1",
    )
}
