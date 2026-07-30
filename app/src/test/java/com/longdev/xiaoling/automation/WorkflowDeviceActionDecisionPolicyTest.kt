package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDeviceActionDecisionPolicyTest {
    @Test
    fun verifiedTapRefProducesVersionedLocalDecisionWithoutRawReferenceData() {
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(content = validActionResult()),
            ),
        )

        val decision = (resolution as WorkflowDeviceActionResolution.Decided).decisions.single()
        assertEquals(WorkflowDeviceActionDecisionStatus.VERIFIED, decision.status)
        assertEquals("tap_ref", decision.action)
        assertEquals("com.example.before", decision.beforePackageName)
        assertEquals("com.example.after", decision.afterPackageName)
        assertEquals(WorkflowDeviceActionDecisionPolicy.RULE_VERSION, decision.ruleVersion)
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        assertTrue(prompt.contains("已执行并验证 tap_ref"))
        assertFalse(prompt.contains("snapshot-secret"))
        assertFalse(prompt.contains("ref-secret"))
        assertFalse(prompt.contains("\"nodes\""))
    }

    @Test
    fun missingExecutorVerificationTypedVerificationOrExpandedResultFailsClosed() {
        val executorUnverified = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(actionEvidence(executorVerified = false, content = validActionResult())),
        )
        assertEquals(
            WorkflowDeviceActionInsufficientReason.EXECUTOR_VERIFICATION_MISSING,
            (executorUnverified as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
        )

        val unverified = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(actionEvidence(verified = false, content = validActionResult())),
        )
        assertEquals(
            WorkflowDeviceActionInsufficientReason.VERIFICATION_MISSING,
            (unverified as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
        )

        val expanded = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    content = validActionResult().dropLast(1) + ",\"ref\":\"ref-secret\"}",
                ),
            ),
        )
        assertEquals(
            WorkflowDeviceActionInsufficientReason.MALFORMED_RESULT,
            (expanded as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
        )
    }

    @Test
    fun runWithoutTapRefIsNotApplicable() {
        assertEquals(
            WorkflowDeviceActionResolution.NotApplicable,
            WorkflowDeviceActionDecisionPolicy.evaluate(
                expectedAgentRunId = "agent-run-current",
                results = listOf(
                    WorkflowDeviceActionEvidenceInput(
                        runId = "agent-run-current",
                        toolName = "device.snapshot",
                        content = "{}",
                        success = true,
                        executorVerified = null,
                        verified = true,
                    ),
                ),
            ),
        )
    }

    private fun actionEvidence(
        executorVerified: Boolean? = true,
        verified: Boolean = true,
        content: String,
    ) = WorkflowDeviceActionEvidenceInput(
        runId = "agent-run-current",
        toolName = "device.tap_ref",
        content = content,
        success = true,
        executorVerified = executorVerified,
        verified = verified,
    )

    private fun validActionResult(): String = """
        {
          "ruleVersion":"workflow-device-action-result-v1",
          "safetyRuleVersion":"workflow-device-action-safety-v1",
          "action":"tap_ref",
          "beforePackageName":"com.example.before",
          "afterPackageName":"com.example.after",
          "afterNodeCount":4,
          "afterRedactedNodeCount":1,
          "afterTruncated":false,
          "afterObservedAt":2000,
          "verified":true
        }
    """.trimIndent()
}
