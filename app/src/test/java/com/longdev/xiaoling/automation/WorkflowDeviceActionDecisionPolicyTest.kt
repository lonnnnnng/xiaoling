package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDeviceActionDecisionPolicyTest {
    @Test
    fun verifiedTypeTextProducesAnswerEvidenceWithoutInputTextOrReferenceData() {
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    toolName = "device.type_text",
                    content = validActionResult(action = "type_text"),
                ),
            ),
        )

        val decision = (resolution as WorkflowDeviceActionResolution.Decided).decisions.single()
        assertEquals("type_text", decision.action)
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        assertTrue(prompt.contains("已执行并验证 type_text"))
        assertTrue(prompt.contains("输入内容未进入答案级证据"))
        assertFalse(prompt.contains("Workflow safe text"))
        assertFalse(prompt.contains("snapshot-secret"))
        assertFalse(prompt.contains("ref-secret"))
    }

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

        val wrongAction = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    content = validActionResult().replace("\"action\":\"tap_ref\"", "\"action\":\"type_text\""),
                ),
            ),
        )
        assertEquals(
            WorkflowDeviceActionInsufficientReason.MALFORMED_RESULT,
            (wrongAction as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
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
        toolName: String = "device.tap_ref",
        content: String,
    ) = WorkflowDeviceActionEvidenceInput(
        runId = "agent-run-current",
        toolName = toolName,
        content = content,
        success = true,
        executorVerified = executorVerified,
        verified = verified,
    )

    private fun validActionResult(action: String = "tap_ref"): String = """
        {
          "ruleVersion":"workflow-device-action-result-v1",
          "safetyRuleVersion":"workflow-device-action-safety-v1",
          "action":"$action",
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
