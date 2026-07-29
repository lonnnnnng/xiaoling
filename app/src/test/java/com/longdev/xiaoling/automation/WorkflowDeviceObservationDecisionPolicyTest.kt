package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDeviceObservationDecisionPolicyTest {
    @Test
    fun verifiedCompleteSnapshotProducesReviewableLocalDecision() {
        val resolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-1",
            results = listOf(result(content = snapshot(redactedNodeCount = 0, truncated = false))),
        ) as WorkflowDeviceObservationResolution.Decided

        val decision = resolution.decisions.single()
        assertEquals(WorkflowDeviceObservationDecisionStatus.REVIEWABLE, decision.status)
        assertEquals("com.example.notes", decision.packageName)
        assertEquals(2, decision.nodeCount)
        assertEquals(WorkflowDeviceObservationDecisionPolicy.RULE_VERSION, decision.ruleVersion)

        val promptEvidence = WorkflowDeviceObservationDecisionPolicy.renderForPrompt(resolution.decisions)
        assertTrue(promptEvidence.contains("com.example.notes"))
        assertTrue(promptEvidence.contains("仅确认采集时的应用包名与快照摘要"))
        assertFalse(promptEvidence.contains("snapshot-secret"))
        assertFalse(promptEvidence.contains("ref-secret"))
        assertFalse(promptEvidence.contains("银行卡密码"))
    }

    @Test
    fun redactedOrTruncatedSnapshotProducesLimitedDecision() {
        val resolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-1",
            results = listOf(result(content = snapshot(redactedNodeCount = 1, truncated = true))),
        ) as WorkflowDeviceObservationResolution.Decided

        val decision = resolution.decisions.single()
        assertEquals(WorkflowDeviceObservationDecisionStatus.LIMITED, decision.status)
        assertEquals(1, decision.redactedNodeCount)
        assertTrue(decision.truncated)
        assertTrue(WorkflowDeviceObservationDecisionPolicy.renderForPrompt(resolution.decisions).contains("有限可复核"))
    }

    @Test
    fun missingDeviceObservationDoesNotChangeOrdinaryWorkflowOutput() {
        val resolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-1",
            results = listOf(result(toolName = "knowledge.search", content = "知识结果")),
        )

        assertEquals(WorkflowDeviceObservationResolution.NotApplicable, resolution)
    }

    @Test
    fun mismatchedFailedUnverifiedOrMalformedEvidenceFailsClosed() {
        val cases = listOf(
            result(runId = "agent-run-other", content = snapshot()),
            result(success = false, content = snapshot()),
            result(verified = false, content = snapshot()),
            result(content = "{\"package\":\"com.example.notes\",\"nodes\":[]}"),
        )

        val reasons = cases.map { evidence ->
            val resolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
                expectedAgentRunId = "agent-run-1",
                results = listOf(evidence),
            ) as WorkflowDeviceObservationResolution.InsufficientEvidence
            resolution.reason
        }

        assertEquals(
            listOf(
                WorkflowDeviceObservationInsufficientReason.RUN_ID_MISMATCH,
                WorkflowDeviceObservationInsufficientReason.EXECUTION_FAILED,
                WorkflowDeviceObservationInsufficientReason.VERIFICATION_MISSING,
                WorkflowDeviceObservationInsufficientReason.MALFORMED_SNAPSHOT,
            ),
            reasons,
        )
    }

    private fun result(
        runId: String = "agent-run-1",
        toolName: String = "device.snapshot",
        content: String,
        success: Boolean = true,
        verified: Boolean = true,
    ) = WorkflowDeviceObservationEvidenceInput(
        runId = runId,
        toolName = toolName,
        content = content,
        success = success,
        verified = verified,
        durationMs = 193L,
    )

    private fun snapshot(
        redactedNodeCount: Int = 0,
        truncated: Boolean = false,
    ): String = """
        {
          "snapshot_id":"snapshot-secret",
          "package":"com.example.notes",
          "window_title":"私人笔记",
          "window_id":7,
          "window_generation":8,
          "captured_at":1700000000000,
          "expires_at":1700000005000,
          "redacted_node_count":$redactedNodeCount,
          "truncated":$truncated,
          "nodes":[
            {"index":0,"depth":0,"role":"button","text":"公开按钮","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":false,"ref":"ref-secret","actions":["tap"]},
            {"index":1,"parent_index":0,"depth":1,"role":"text","text":"银行卡密码","bounds":[0,0,100,100],"enabled":true,"selected":false,"redacted":${redactedNodeCount > 0},"actions":[]}
          ]
        }
    """.trimIndent()
}
