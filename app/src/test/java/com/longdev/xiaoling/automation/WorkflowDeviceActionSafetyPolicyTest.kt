package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentExecutionOrigin
import com.longdev.xiaoling.agent.AgentInvocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowDeviceActionSafetyPolicyTest {
    @Test
    fun policyRejectsUnknownDeviceActionsAtConstruction() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.shell"))
        }

        assertTrue(error.message.orEmpty().contains("device.shell"))
    }

    @Test
    fun defaultPolicyKeepsAllWorkflowDeviceActionsClosed() {
        val decision = WorkflowDeviceActionSafetyPolicy().assessExecution(validExecutionEvidence())

        assertEquals(
            WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_NOT_ENABLED,
                "当前阶段未开放该 Workflow 设备动作",
            ),
            decision,
        )
    }

    @Test
    fun enabledActionStillRejectsDirectAndBackgroundContexts() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))

        assertEquals(
            WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.INVOCATION_SOURCE_DENIED,
                "有限设备动作只接受前台手动 Workflow 来源",
            ),
            policy.assessExecution(
                validExecutionEvidence().copy(invocationSource = AgentInvocationSource.DIRECT),
            ),
        )
        assertEquals(
            WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.BACKGROUND_DENIED,
                "后台或定时 Workflow 不得执行设备动作",
            ),
            policy.assessExecution(
                validExecutionEvidence().copy(executionOrigin = AgentExecutionOrigin.BACKGROUND),
            ),
        )
    }

    @Test
    fun enabledActionRequiresExplicitUserWorkflowIntent() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))

        assertEquals(
            WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.USER_INTENT_MISSING,
                "Workflow 设备动作缺少用户明确编写的步骤意图",
            ),
            policy.assessExecution(validExecutionEvidence().copy(userIntent = "  ")),
        )
    }

    @Test
    fun enabledReferenceActionRequiresCurrentVerifiedObservationFromSameRun() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val valid = validExecutionEvidence()
        val observation = requireNotNull(valid.observation)

        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_MISSING,
            policy.assessExecution(valid.copy(observation = null)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_RUN_MISMATCH,
            policy.assessExecution(valid.copy(observation = observation.copy(agentRunId = "agent-run-old"))),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_NOT_VERIFIED,
            policy.assessExecution(valid.copy(observation = observation.copy(verified = false))),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_EXPIRED,
            policy.assessExecution(valid.copy(nowMillis = observation.expiresAt)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.WINDOW_CHANGED,
            policy.assessExecution(valid.copy(currentWindowGeneration = observation.windowGeneration + 1)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.REFERENCE_MISMATCH,
            policy.assessExecution(
                valid.copy(
                    identity = valid.identity.copy(
                        arguments = valid.identity.arguments + ("snapshot_id" to "snapshot-old"),
                    ),
                ),
            ),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.REFERENCE_MISMATCH,
            policy.assessExecution(valid.copy(liveReferenceMatched = false)),
        )
    }

    @Test
    fun enabledActionRejectsObservationWhoseDeclaredLifetimeExceedsThirtySeconds() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val valid = validExecutionEvidence()
        val observation = requireNotNull(valid.observation)

        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_EXPIRED,
            policy.assessExecution(
                valid.copy(
                    observation = observation.copy(expiresAt = observation.capturedAt + 30_001L),
                ),
            ),
        )
    }

    @Test
    fun enabledActionRejectsMalformedOrSelfReferentialObservationIdentity() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val valid = validExecutionEvidence()
        val observation = requireNotNull(valid.observation)

        listOf(
            observation.copy(toolName = "app.current_time"),
            observation.copy(toolCallId = ""),
            observation.copy(toolCallId = valid.identity.toolCallId),
            observation.copy(snapshotId = ""),
            observation.copy(windowGeneration = -1L),
        ).forEach { malformed ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_INVALID,
                policy.assessExecution(valid.copy(observation = malformed)),
            )
        }
    }

    @Test
    fun enabledActionRequiresFreshApprovalBoundToTheExactToolCall() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val valid = validExecutionEvidence()
        val approval = requireNotNull(valid.approval)

        assertDenied(
            WorkflowDeviceActionSafetyFailure.APPROVAL_MISSING,
            policy.assessExecution(valid.copy(approval = null)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.APPROVAL_NOT_APPROVED,
            policy.assessExecution(valid.copy(approval = approval.copy(approved = false))),
        )
        listOf(
            approval.copy(agentRunId = "agent-run-old"),
            approval.copy(toolCallId = "tool-call-previous-action"),
            approval.copy(toolName = "device.swipe"),
            approval.copy(arguments = approval.arguments + ("ref" to "r2")),
        ).forEach { mismatched ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.APPROVAL_MISMATCH,
                policy.assessExecution(valid.copy(approval = mismatched)),
            )
        }
        assertDenied(
            WorkflowDeviceActionSafetyFailure.APPROVAL_SESSION_MISMATCH,
            policy.assessExecution(
                valid.copy(approval = approval.copy(decisionProcessSessionId = "process-session-before-restart")),
            ),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.APPROVAL_MISMATCH,
            policy.assessExecution(valid.copy(approval = approval.copy(decidedAt = 999L))),
        )
    }

    @Test
    fun enabledActionRequiresCompleteStableIdentityBeforeItCanBeAllowed() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val valid = validExecutionEvidence()
        val blankIdentities = listOf(
            valid.identity.copy(workflowRunId = ""),
            valid.identity.copy(workflowStepId = ""),
            valid.identity.copy(agentRunId = ""),
            valid.identity.copy(toolCallId = ""),
        )

        blankIdentities.forEach { identity ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.IDENTITY_INVALID,
                policy.assessExecution(valid.copy(identity = identity)),
            )
        }
        assertDenied(
            WorkflowDeviceActionSafetyFailure.IDENTITY_INVALID,
            policy.assessExecution(valid.copy(currentProcessSessionId = "")),
        )
        val allowed = policy.assessExecution(valid)
        assertTrue(allowed is WorkflowDeviceActionSafetyDecision.Allowed)
        allowed as WorkflowDeviceActionSafetyDecision.Allowed
        assertEquals(valid.identity, allowed.authorization.identity)
        assertEquals("workflow-device-action-safety-v1", allowed.authorization.ruleVersion)
    }

    @Test
    fun safeBackUsesFreshWorkflowObservationWithoutCreatingApproval() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.back"))
        val valid = validExecutionEvidence().copy(
            identity = validExecutionEvidence().identity.copy(
                toolName = "device.back",
                arguments = emptyMap(),
            ),
            userIntent = "返回上一个系统设置页面",
            approval = null,
            liveReferenceMatched = false,
        )

        val decision = policy.assessExecution(valid)

        assertTrue(decision is WorkflowDeviceActionSafetyDecision.Allowed)
        decision as WorkflowDeviceActionSafetyDecision.Allowed
        assertEquals(WorkflowDeviceActionApprovalMode.SAFE_NO_APPROVAL, decision.authorization.approvalMode)
        assertEquals(valid.identity, decision.authorization.identity)
    }

    @Test
    fun safeBackRejectsAnyArgumentsBeforeExecution() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.back"))
        val valid = validExecutionEvidence().copy(
            identity = validExecutionEvidence().identity.copy(
                toolName = "device.back",
                arguments = mapOf("steps" to "2"),
            ),
            userIntent = "返回上一个系统设置页面",
            approval = null,
            liveReferenceMatched = false,
        )

        assertDenied(
            WorkflowDeviceActionSafetyFailure.ACTION_ARGUMENTS_INVALID,
            policy.assessExecution(valid),
        )
    }

    @Test
    fun safeBackCompletionRejectsAuthorizationApprovalModeDrift() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.back"))
        val execution = validExecutionEvidence().copy(
            identity = validExecutionEvidence().identity.copy(
                toolName = "device.back",
                arguments = emptyMap(),
            ),
            userIntent = "返回上一个系统设置页面",
            approval = null,
            liveReferenceMatched = false,
        )
        val authorization = (
            policy.assessExecution(execution) as WorkflowDeviceActionSafetyDecision.Allowed
        ).authorization
        val completion = validCompletionEvidence(authorization).copy(
            identity = execution.identity,
            resultToolName = execution.identity.toolName,
        )

        assertDenied(
            WorkflowDeviceActionSafetyFailure.EXECUTION_AUTHORIZATION_MISMATCH,
            policy.assessCompletion(
                completion.copy(
                    authorization = authorization.copy(
                        approvalMode = WorkflowDeviceActionApprovalMode.REQUIRE_APPROVAL,
                    ),
                ),
            ),
        )
    }

    @Test
    fun safeBackOnlyBypassesApprovalAndKeepsAllOtherExecutionAndCompletionGuards() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.back"))
        val execution = validExecutionEvidence().copy(
            identity = validExecutionEvidence().identity.copy(
                toolName = "device.back",
                arguments = emptyMap(),
            ),
            userIntent = "返回上一个系统设置页面",
            approval = null,
            liveReferenceMatched = false,
        )
        val observation = requireNotNull(execution.observation)

        assertDenied(
            WorkflowDeviceActionSafetyFailure.BACKGROUND_DENIED,
            policy.assessExecution(execution.copy(executionOrigin = AgentExecutionOrigin.BACKGROUND)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.OBSERVATION_MISSING,
            policy.assessExecution(execution.copy(observation = null)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.WINDOW_CHANGED,
            policy.assessExecution(execution.copy(currentWindowGeneration = observation.windowGeneration + 1L)),
        )

        val authorization = (
            policy.assessExecution(execution) as WorkflowDeviceActionSafetyDecision.Allowed
        ).authorization
        val completion = validCompletionEvidence(authorization).copy(
            identity = execution.identity,
            resultToolName = execution.identity.toolName,
        )
        listOf(
            completion.copy(executorVerified = false),
            completion.copy(verificationPassed = false),
            completion.copy(afterObservation = null),
        ).forEach { incomplete ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.POST_ACTION_VERIFICATION_MISSING,
                policy.assessCompletion(incomplete),
            )
        }
    }

    @Test
    fun completionRequiresSameCallSuccessfulResultAndPostActionVerification() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val authorization = (
            policy.assessExecution(validExecutionEvidence()) as WorkflowDeviceActionSafetyDecision.Allowed
        ).authorization
        val valid = validCompletionEvidence(authorization)

        assertDenied(
            WorkflowDeviceActionSafetyFailure.EXECUTION_AUTHORIZATION_MISSING,
            policy.assessCompletion(valid.copy(authorization = null)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.EXECUTION_AUTHORIZATION_MISMATCH,
            policy.assessCompletion(
                valid.copy(
                    authorization = authorization.copy(
                        identity = authorization.identity.copy(toolCallId = "tool-call-before-retry"),
                    ),
                ),
            ),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.IDENTITY_INVALID,
            policy.assessCompletion(valid.copy(identity = valid.identity.copy(workflowStepId = ""))),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.ACTION_CANCELLED,
            policy.assessCompletion(valid.copy(cancelled = true)),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.ACTION_RESULT_MISMATCH,
            policy.assessCompletion(valid.copy(resultToolCallId = "tool-call-old")),
        )
        assertDenied(
            WorkflowDeviceActionSafetyFailure.ACTION_EXECUTION_FAILED,
            policy.assessCompletion(valid.copy(success = false)),
        )
        listOf(
            valid.copy(executorVerified = false),
            valid.copy(verificationPassed = false),
        ).forEach { incomplete ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.POST_ACTION_VERIFICATION_MISSING,
                policy.assessCompletion(incomplete),
            )
        }
        assertTrue(policy.assessCompletion(valid) is WorkflowDeviceActionSafetyDecision.Allowed)
    }

    @Test
    fun completionRequiresVerifiedPostActionObservationBoundToCurrentCall() {
        val policy = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.tap_ref"))
        val authorization = (
            policy.assessExecution(validExecutionEvidence()) as WorkflowDeviceActionSafetyDecision.Allowed
        ).authorization
        val valid = validCompletionEvidence(authorization)
        val afterObservation = requireNotNull(valid.afterObservation)

        listOf(
            afterObservation.copy(agentRunId = "agent-run-old"),
            afterObservation.copy(actionToolCallId = "tool-call-old"),
            afterObservation.copy(snapshotId = ""),
            afterObservation.copy(observedAt = valid.actionCompletedAt - 1L),
            afterObservation.copy(windowGeneration = -1L),
            afterObservation.copy(verified = false),
        ).forEach { invalidObservation ->
            assertDenied(
                WorkflowDeviceActionSafetyFailure.POST_ACTION_VERIFICATION_MISSING,
                policy.assessCompletion(valid.copy(afterObservation = invalidObservation)),
            )
        }
        assertDenied(
            WorkflowDeviceActionSafetyFailure.POST_ACTION_VERIFICATION_MISSING,
            policy.assessCompletion(valid.copy(actionCompletedAt = authorization.authorizedAt)),
        )
    }

    private fun assertDenied(
        reason: WorkflowDeviceActionSafetyFailure,
        decision: WorkflowDeviceActionSafetyDecision,
    ) {
        assertTrue(decision is WorkflowDeviceActionSafetyDecision.Denied)
        assertEquals(reason, (decision as WorkflowDeviceActionSafetyDecision.Denied).reason)
    }

    private fun validExecutionEvidence() = WorkflowDeviceActionExecutionEvidence(
        identity = WorkflowDeviceActionIdentity(
            workflowRunId = "workflow-run-current",
            workflowStepId = "workflow-step-current",
            agentRunId = "agent-run-current",
            toolCallId = "tool-call-current",
            toolName = "device.tap_ref",
            arguments = mapOf("snapshot_id" to "snapshot-current", "ref" to "r1"),
        ),
        userIntent = "点击计算器数字 1 按钮",
        invocationSource = AgentInvocationSource.WORKFLOW,
        executionOrigin = AgentExecutionOrigin.FOREGROUND,
        currentProcessSessionId = "process-session-current",
        observation = WorkflowDeviceActionObservationEvidence(
            agentRunId = "agent-run-current",
            toolCallId = "tool-call-snapshot",
            toolName = "device.snapshot",
            snapshotId = "snapshot-current",
            capturedAt = 1_000L,
            expiresAt = 31_000L,
            windowGeneration = 7L,
            verified = true,
        ),
        approval = WorkflowDeviceActionApprovalEvidence(
            agentRunId = "agent-run-current",
            toolCallId = "tool-call-current",
            toolName = "device.tap_ref",
            arguments = mapOf("snapshot_id" to "snapshot-current", "ref" to "r1"),
            approved = true,
            decidedAt = 2_000L,
            decisionProcessSessionId = "process-session-current",
        ),
        nowMillis = 2_100L,
        currentWindowGeneration = 7L,
        liveReferenceMatched = true,
    )

    private fun validCompletionEvidence(
        authorization: WorkflowDeviceActionAuthorization,
    ): WorkflowDeviceActionCompletionEvidence {
        val identity = validExecutionEvidence().identity
        return WorkflowDeviceActionCompletionEvidence(
            identity = identity,
            authorization = authorization,
            resultAgentRunId = identity.agentRunId,
            resultToolCallId = identity.toolCallId,
            resultToolName = identity.toolName,
            success = true,
            executorVerified = true,
            verificationPassed = true,
            actionCompletedAt = 2_400L,
            afterObservation = WorkflowDeviceActionPostObservationEvidence(
                agentRunId = identity.agentRunId,
                actionToolCallId = identity.toolCallId,
                snapshotId = "snapshot-after-action",
                observedAt = 2_500L,
                windowGeneration = 8L,
                verified = true,
            ),
            cancelled = false,
        )
    }
}
