package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowGoalVerificationPolicyTest {
    @Test
    fun matchingVerifiedToolsAndFinalObservationVerifyTheUserGoal() {
        val decision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "在系统设置中向上滚动后返回小灵",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("device.swipe", "device.back"),
                expectedFinalPackageName = "com.longdev.xiaoling",
            ),
            steps = listOf(
                step(
                    verifiedToolNames = listOf("device.snapshot", "device.swipe", "device.snapshot", "device.back"),
                    finalPackageName = "com.longdev.xiaoling",
                ),
            ),
        )

        assertEquals(WorkflowGoalVerificationStatus.VERIFIED, decision.status)
        assertEquals(listOf("device.swipe", "device.back"), decision.matchedRequiredToolNames)
        assertEquals("com.longdev.xiaoling", decision.actualFinalPackageName)
        assertTrue(decision.renderForUser().contains("任务目标已验证完成"))
        assertFalse(decision.renderForUser().contains("模型"))
    }

    @Test
    fun missingOrOutOfOrderRequiredToolsOnlyProducePartialProgress() {
        val decision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "先滚动再返回",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("device.swipe", "device.back"),
            ),
            steps = listOf(step(verifiedToolNames = listOf("device.back", "device.swipe"))),
        )

        assertEquals(WorkflowGoalVerificationStatus.PARTIAL, decision.status)
        assertEquals(
            WorkflowGoalVerificationReason.REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER,
            decision.reason,
        )
        assertEquals(listOf("device.swipe"), decision.matchedRequiredToolNames)
    }

    @Test
    fun mismatchedLatestPackageOnlyProducesPartialProgress() {
        val decision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "返回小灵",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf("device.back"),
                expectedFinalPackageName = "com.longdev.xiaoling",
            ),
            steps = listOf(
                step(
                    verifiedToolNames = listOf("device.back"),
                    finalPackageName = "com.android.settings",
                ),
            ),
        )

        assertEquals(WorkflowGoalVerificationStatus.PARTIAL, decision.status)
        assertEquals(WorkflowGoalVerificationReason.FINAL_PACKAGE_MISMATCH, decision.reason)
        assertEquals("com.android.settings", decision.actualFinalPackageName)
    }

    @Test
    fun noCompletedStepToolOrObservationIsIncomplete() {
        val decision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "读取当前时间",
            spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
            steps = listOf(
                WorkflowGoalVerificationStepEvidence(
                    status = WorkflowStepStatus.PENDING,
                    verifiedToolNames = emptyList(),
                    deviceObservationDecisions = emptyList(),
                    deviceActionDecisions = emptyList(),
                ),
            ),
        )

        assertEquals(WorkflowGoalVerificationStatus.INCOMPLETE, decision.status)
        assertEquals(WorkflowGoalVerificationReason.NO_VERIFIED_PROGRESS, decision.reason)
    }

    @Test
    fun skippedStepWithoutFrozenToolEvidenceCannotBecomeVerified() {
        val decision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "读取时间后生成回顾",
            spec = WorkflowGoalVerificationSpec(requiredToolNames = listOf("app.current_time")),
            steps = listOf(
                WorkflowGoalVerificationStepEvidence(
                    status = WorkflowStepStatus.SKIPPED,
                    verifiedToolNames = emptyList(),
                    deviceObservationDecisions = emptyList(),
                    deviceActionDecisions = emptyList(),
                ),
                step(verifiedToolNames = listOf("app.current_time")),
            ),
        )

        assertEquals(WorkflowGoalVerificationStatus.PARTIAL, decision.status)
        assertEquals(WorkflowGoalVerificationReason.STEP_INCOMPLETE, decision.reason)
        assertEquals(1, decision.completedStepCount)
    }

    @Test
    fun inconsistentPersistedDecisionFailsClosed() {
        val raw = WorkflowGoalVerificationDecisionCodec.encode(
            WorkflowGoalVerificationDecision(
                sourceGoal = "读取当前时间",
                status = WorkflowGoalVerificationStatus.VERIFIED,
                reason = WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED,
                requiredToolNames = listOf("app.current_time"),
                matchedRequiredToolNames = emptyList(),
                expectedFinalPackageName = null,
                actualFinalPackageName = null,
                completedStepCount = 1,
                totalStepCount = 1,
            ),
        )

        assertNull(WorkflowGoalVerificationDecisionCodec.decode(raw))
    }

    private fun step(
        verifiedToolNames: List<String>,
        finalPackageName: String? = null,
    ) = WorkflowGoalVerificationStepEvidence(
        status = WorkflowStepStatus.COMPLETED,
        verifiedToolNames = verifiedToolNames,
        deviceObservationDecisions = finalPackageName?.let { packageName ->
            listOf(
                WorkflowDeviceObservationDecision(
                    status = WorkflowDeviceObservationDecisionStatus.REVIEWABLE,
                    packageName = packageName,
                    nodeCount = 4,
                    redactedNodeCount = 0,
                    truncated = false,
                    capturedAt = 3_000L,
                ),
            )
        }.orEmpty(),
        deviceActionDecisions = emptyList(),
    )
}
