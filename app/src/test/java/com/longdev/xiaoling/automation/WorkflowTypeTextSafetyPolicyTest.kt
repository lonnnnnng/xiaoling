package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentExecutionOrigin
import com.longdev.xiaoling.agent.AgentInvocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowTypeTextSafetyPolicyTest {
    private val policy = WorkflowTypeTextSafetyPolicy()

    @Test
    fun executionRejectsMalformedSensitiveOrUnsupportedTypeText() {
        val validIdentity = validIdentity()
        val validEvidence = validExecutionEvidence()

        listOf(
            validIdentity.copy(arguments = validIdentity.arguments - "text"),
            validIdentity.copy(arguments = validIdentity.arguments + ("unexpected" to "value")),
            validIdentity.copy(arguments = validIdentity.arguments + ("snapshot_id" to "")),
            validIdentity.copy(arguments = validIdentity.arguments + ("ref" to "")),
        ).forEach { identity ->
            assertDenied(
                WorkflowTypeTextSafetyFailure.ARGUMENTS_INVALID,
                policy.assessExecution(identity, validEvidence),
            )
        }

        listOf(
            "",
            "sk-abcdefghijklmnop123456",
            "13800138000",
            "user@example.com",
            "abc\u0000def",
            "a".repeat(501),
        ).forEach { text ->
            assertDenied(
                WorkflowTypeTextSafetyFailure.TEXT_REJECTED,
                policy.assessExecution(validIdentity(text), validEvidence),
            )
        }

        assertDenied(
            WorkflowTypeTextSafetyFailure.TARGET_MISSING,
            policy.assessExecution(validIdentity, validEvidence.copy(target = null)),
        )
        listOf(
            validTarget().copy(enabled = false),
            validTarget().copy(editable = false),
        ).forEach { target ->
            assertDenied(
                WorkflowTypeTextSafetyFailure.TARGET_NOT_EDITABLE,
                policy.assessExecution(validIdentity, validEvidence.copy(target = target)),
            )
        }
        assertDenied(
            WorkflowTypeTextSafetyFailure.TARGET_REDACTED,
            policy.assessExecution(validIdentity, validEvidence.copy(target = validTarget().copy(redacted = true))),
        )
        assertDenied(
            WorkflowTypeTextSafetyFailure.TARGET_ACTION_UNAVAILABLE,
            policy.assessExecution(
                validIdentity,
                validEvidence.copy(target = validTarget().copy(supportsTypeText = false)),
            ),
        )
    }

    @Test
    fun executionReturnsMinimizedAuthorizationWithoutRawText() {
        val text = "XiaoLing stage 115"
        val decision = policy.assessExecution(validIdentity(text), validExecutionEvidence())

        assertTrue(decision is WorkflowTypeTextSafetyDecision.Allowed)
        val authorization = (decision as WorkflowTypeTextSafetyDecision.Allowed).authorization
        assertEquals("workflow-type-text-safety-v1", authorization.ruleVersion)
        assertEquals(text.length, authorization.textLength)
        assertTrue(authorization.textFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertFalse(authorization.toString().contains(text))
        assertFalse(authorization.toString().contains("snapshot-current"))
        assertFalse(authorization.toString().contains("ref-current"))
    }

    @Test
    fun completionRequiresSameCallVerifiedPostObservationAndExactReadBack() {
        val identity = validIdentity()
        val authorization = (
            policy.assessExecution(identity, validExecutionEvidence()) as WorkflowTypeTextSafetyDecision.Allowed
        ).authorization
        val valid = validCompletionEvidence()

        assertDenied(
            WorkflowTypeTextSafetyFailure.AUTHORIZATION_MISSING,
            policy.assessCompletion(identity, null, valid),
        )
        assertDenied(
            WorkflowTypeTextSafetyFailure.AUTHORIZATION_MISMATCH,
            policy.assessCompletion(
                identity.copy(arguments = identity.arguments + ("text" to "changed text")),
                authorization,
                valid,
            ),
        )
        listOf(
            valid.copy(resultAgentRunId = "agent-run-old"),
            valid.copy(resultToolCallId = "tool-call-old"),
            valid.copy(resultToolName = "device.tap_ref"),
        ).forEach { mismatched ->
            assertDenied(
                WorkflowTypeTextSafetyFailure.RESULT_MISMATCH,
                policy.assessCompletion(identity, authorization, mismatched),
            )
        }
        listOf(
            valid.copy(executorVerified = false),
            valid.copy(verificationPassed = false),
            valid.copy(afterObservationVerified = false),
            valid.copy(observedAt = valid.actionCompletedAt - 1L),
            valid.copy(readBackText = null),
        ).forEach { incomplete ->
            assertDenied(
                WorkflowTypeTextSafetyFailure.POST_VERIFICATION_MISSING,
                policy.assessCompletion(identity, authorization, incomplete),
            )
        }
        assertDenied(
            WorkflowTypeTextSafetyFailure.READ_BACK_MISMATCH,
            policy.assessCompletion(identity, authorization, valid.copy(readBackText = "different text")),
        )
        assertTrue(policy.assessCompletion(identity, authorization, valid) is WorkflowTypeTextSafetyDecision.Allowed)
    }

    @Test
    fun genericDeviceSafetyPolicyCannotEnableTypeTextWithoutDedicatedEvidence() {
        val generic = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.type_text"))
        val valid = validGenericExecutionEvidence(typeText = null)

        assertDeviceDenied(
            WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
            generic.assessExecution(valid),
        )
        val allowed = generic.assessExecution(valid.copy(typeText = validExecutionEvidence()))
        assertTrue(allowed is WorkflowDeviceActionSafetyDecision.Allowed)
        val authorization = (allowed as WorkflowDeviceActionSafetyDecision.Allowed).authorization
        assertEquals(mapOf("snapshot_id" to "snapshot-current", "ref" to "ref-current"), authorization.identity.arguments)
        assertTrue(authorization.typeTextAuthorization != null)
        assertFalse(authorization.toString().contains("XiaoLing stage 115"))

        val completion = validGenericCompletionEvidence(authorization, typeText = null)
        assertDeviceDenied(
            WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
            generic.assessCompletion(completion),
        )
        assertTrue(
            generic.assessCompletion(
                completion.copy(typeText = validCompletionEvidence()),
            ) is WorkflowDeviceActionSafetyDecision.Allowed,
        )
    }

    private fun validIdentity(text: String = "XiaoLing stage 115") = WorkflowDeviceActionIdentity(
        workflowRunId = "workflow-run-current",
        workflowStepId = "workflow-step-current",
        agentRunId = "agent-run-current",
        toolCallId = "tool-call-current",
        toolName = "device.type_text",
        arguments = mapOf(
            "snapshot_id" to "snapshot-current",
            "ref" to "ref-current",
            "text" to text,
        ),
    )

    private fun validTarget() = WorkflowTypeTextTargetEvidence(
        enabled = true,
        editable = true,
        redacted = false,
        supportsTypeText = true,
    )

    private fun validExecutionEvidence() = WorkflowTypeTextExecutionEvidence(target = validTarget())

    private fun validCompletionEvidence() = WorkflowTypeTextCompletionEvidence(
        resultAgentRunId = "agent-run-current",
        resultToolCallId = "tool-call-current",
        resultToolName = "device.type_text",
        actionCompletedAt = 2_400L,
        observedAt = 2_500L,
        executorVerified = true,
        verificationPassed = true,
        afterObservationVerified = true,
        readBackText = "XiaoLing stage 115",
    )

    private fun validGenericExecutionEvidence(
        typeText: WorkflowTypeTextExecutionEvidence?,
    ): WorkflowDeviceActionExecutionEvidence {
        val identity = validIdentity()
        return WorkflowDeviceActionExecutionEvidence(
            identity = identity,
            userIntent = "在当前普通文本框输入一段非敏感测试文本",
            targetAppPackage = "com.android.settings",
            beforePackageName = "com.android.settings",
            invocationSource = AgentInvocationSource.WORKFLOW,
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            currentProcessSessionId = "process-session-current",
            observation = WorkflowDeviceActionObservationEvidence(
                agentRunId = identity.agentRunId,
                toolCallId = "tool-call-snapshot",
                toolName = "device.snapshot",
                snapshotId = "snapshot-current",
                capturedAt = 1_000L,
                expiresAt = 31_000L,
                windowGeneration = 7L,
                verified = true,
            ),
            approval = WorkflowDeviceActionApprovalEvidence(
                agentRunId = identity.agentRunId,
                toolCallId = identity.toolCallId,
                toolName = identity.toolName,
                arguments = identity.arguments,
                approved = true,
                decidedAt = 2_000L,
                decisionProcessSessionId = "process-session-current",
            ),
            nowMillis = 2_100L,
            currentWindowGeneration = 7L,
            liveReferenceMatched = true,
            typeText = typeText,
        )
    }

    private fun validGenericCompletionEvidence(
        authorization: WorkflowDeviceActionAuthorization,
        typeText: WorkflowTypeTextCompletionEvidence?,
    ): WorkflowDeviceActionCompletionEvidence {
        val identity = validIdentity()
        return WorkflowDeviceActionCompletionEvidence(
            identity = identity,
            authorization = authorization,
            targetAppPackage = "com.android.settings",
            resultAgentRunId = identity.agentRunId,
            resultToolCallId = identity.toolCallId,
            resultToolName = identity.toolName,
            success = true,
            executorVerified = true,
            verificationPassed = true,
            actionCompletedAt = 2_400L,
            afterPackageName = "com.android.settings",
            afterObservation = WorkflowDeviceActionPostObservationEvidence(
                agentRunId = identity.agentRunId,
                actionToolCallId = identity.toolCallId,
                snapshotId = "snapshot-after-action",
                observedAt = 2_500L,
                windowGeneration = 8L,
                verified = true,
            ),
            cancelled = false,
            typeText = typeText,
        )
    }

    private fun assertDenied(
        expected: WorkflowTypeTextSafetyFailure,
        decision: WorkflowTypeTextSafetyDecision,
    ) {
        assertTrue(decision is WorkflowTypeTextSafetyDecision.Denied)
        assertEquals(expected, (decision as WorkflowTypeTextSafetyDecision.Denied).reason)
    }

    private fun assertDeviceDenied(
        expected: WorkflowDeviceActionSafetyFailure,
        decision: WorkflowDeviceActionSafetyDecision,
    ) {
        assertTrue(decision is WorkflowDeviceActionSafetyDecision.Denied)
        assertEquals(expected, (decision as WorkflowDeviceActionSafetyDecision.Denied).reason)
    }
}
