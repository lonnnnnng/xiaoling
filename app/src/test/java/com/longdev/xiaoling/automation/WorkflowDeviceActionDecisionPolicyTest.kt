package com.longdev.xiaoling.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDeviceActionDecisionPolicyTest {
    @Test
    fun verifiedOpenAppProducesTargetPackageEvidenceWithoutReusableNodeReference() {
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    toolName = "device.open_app",
                    expectedOpenAppPackageName = "com.android.calculator2",
                    content = validActionResult(
                        action = "open_app",
                        afterPackageName = "com.android.calculator2",
                    ),
                ),
            ),
        )

        val decision = (resolution as WorkflowDeviceActionResolution.Decided).decisions.single()
        assertEquals("open_app", decision.action)
        assertEquals("com.android.calculator2", decision.afterPackageName)
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        assertTrue(prompt.contains("已执行并验证 打开应用"))
        assertTrue(prompt.contains("本次打开应用不产生可复用节点引用"))
        assertTrue(prompt.contains("后续设备动作必须重新观察并按各自风险规则执行"))
        assertFalse(prompt.contains("节点引用已经失效，后续动作必须重新观察和审批"))
    }

    @Test
    fun openAppFailsClosedWhenApprovedTargetIsMissingOrDiffersFromResult() {
        listOf(null, "com.android.settings").forEach { expectedPackageName ->
            val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
                expectedAgentRunId = "agent-run-current",
                results = listOf(
                    actionEvidence(
                        toolName = "device.open_app",
                        expectedOpenAppPackageName = expectedPackageName,
                        content = validActionResult(
                            action = "open_app",
                            afterPackageName = "com.android.calculator2",
                        ),
                    ),
                ),
            )

            assertEquals(
                WorkflowDeviceActionInsufficientReason.MALFORMED_RESULT,
                (resolution as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
            )
        }
    }

    @Test
    fun verifiedHomeProducesLauncherNavigationEvidenceWithoutReusableNodeReference() {
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    toolName = "device.home",
                    content = validActionResult(action = "home"),
                ),
            ),
        )

        val decision = (resolution as WorkflowDeviceActionResolution.Decided).decisions.single()
        assertEquals("home", decision.action)
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        assertTrue(prompt.contains("已执行并验证 返回桌面"))
        assertTrue(prompt.contains("本次返回桌面不产生可复用节点引用"))
        assertTrue(prompt.contains("后续设备动作必须重新观察并按各自风险规则执行"))
        assertFalse(prompt.contains("节点引用已经失效，后续动作必须重新观察和审批"))
    }

    @Test
    fun verifiedBackProducesAnswerEvidenceForOnlyTheCurrentNavigationAction() {
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    toolName = "device.back",
                    content = validActionResult(action = "back"),
                ),
            ),
        )

        val decision = (resolution as WorkflowDeviceActionResolution.Decided).decisions.single()
        assertEquals("back", decision.action)
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        assertTrue(prompt.contains("已执行并验证 返回"))
        assertTrue(prompt.contains("仅确认当前设备动作和后置观察已验证"))
        assertTrue(prompt.contains("不确认用户最终业务目标"))
        assertTrue(prompt.contains("本次返回不产生可复用节点引用"))
        assertTrue(prompt.contains("按各自风险规则执行"))
        assertFalse(prompt.contains("节点引用已经失效，后续动作必须重新观察和审批"))
    }

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

        val backDisguisedAsTap = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = "agent-run-current",
            results = listOf(
                actionEvidence(
                    toolName = "device.back",
                    content = validActionResult(action = "tap_ref"),
                ),
            ),
        )
        assertEquals(
            WorkflowDeviceActionInsufficientReason.MALFORMED_RESULT,
            (backDisguisedAsTap as WorkflowDeviceActionResolution.InsufficientEvidence).reason,
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
        expectedOpenAppPackageName: String? = null,
        content: String,
    ) = WorkflowDeviceActionEvidenceInput(
        runId = "agent-run-current",
        toolName = toolName,
        content = content,
        success = true,
        executorVerified = executorVerified,
        verified = verified,
        expectedOpenAppPackageName = expectedOpenAppPackageName,
    )

    private fun validActionResult(
        action: String = "tap_ref",
        afterPackageName: String = "com.example.after",
    ): String = """
        {
          "ruleVersion":"workflow-device-action-result-v1",
          "safetyRuleVersion":"workflow-device-action-safety-v1",
          "action":"$action",
          "beforePackageName":"com.example.before",
          "afterPackageName":"$afterPackageName",
          "afterNodeCount":4,
          "afterRedactedNodeCount":1,
          "afterTruncated":false,
          "afterObservedAt":2000,
          "verified":true
        }
    """.trimIndent()
}
