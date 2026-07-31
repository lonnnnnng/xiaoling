package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentExecutionOrigin
import com.longdev.xiaoling.agent.AgentInvocationSource
import com.longdev.xiaoling.device.DeviceScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowSwipeSafetyPolicyTest {
    private val policy = WorkflowSwipeSafetyPolicy()

    @Test
    fun executionRejectsMalformedArgumentsUnsupportedTargetAndInvalidViewport() {
        val identity = validIdentity()
        val evidence = validExecutionEvidence()

        listOf(
            identity.copy(arguments = identity.arguments - "direction"),
            identity.copy(arguments = identity.arguments + ("unexpected" to "value")),
            identity.copy(arguments = identity.arguments + ("snapshot_id" to "")),
            identity.copy(arguments = identity.arguments + ("ref" to "")),
            identity.copy(arguments = identity.arguments + ("direction" to "diagonal")),
        ).forEach { malformed ->
            assertDenied(
                WorkflowSwipeSafetyFailure.ARGUMENTS_INVALID,
                policy.assessExecution(malformed, evidence),
            )
        }

        assertDenied(
            WorkflowSwipeSafetyFailure.TARGET_MISSING,
            policy.assessExecution(identity, evidence.copy(target = null)),
        )
        assertDenied(
            WorkflowSwipeSafetyFailure.TARGET_NOT_AVAILABLE,
            policy.assessExecution(identity, evidence.copy(target = validTarget().copy(enabled = false))),
        )
        assertDenied(
            WorkflowSwipeSafetyFailure.TARGET_REDACTED,
            policy.assessExecution(identity, evidence.copy(target = validTarget().copy(redacted = true))),
        )
        assertDenied(
            WorkflowSwipeSafetyFailure.TARGET_ACTION_UNAVAILABLE,
            policy.assessExecution(identity, evidence.copy(target = validTarget().copy(supportsSwipe = false))),
        )

        listOf(
            validViewport().copy(packageName = ""),
            validViewport().copy(windowId = -1),
            validViewport().copy(windowGeneration = -1L),
            validViewport().copy(targetFingerprint = fingerprint('e')),
            validViewport().copy(anchors = validViewport().anchors.take(1)),
            validViewport().copy(anchors = validViewport().anchors + validViewport().anchors.first()),
        ).forEach { invalidViewport ->
            assertDenied(
                WorkflowSwipeSafetyFailure.VIEWPORT_INVALID,
                policy.assessExecution(identity, evidence.copy(beforeViewport = invalidViewport)),
            )
        }

        assertTrue(policy.assessExecution(identity, evidence) is WorkflowSwipeSafetyDecision.Allowed)
    }

    @Test
    fun completionRequiresSameWindowChangedContentAndRequestedDirection() {
        val identity = validIdentity()
        val authorization = (
            policy.assessExecution(identity, validExecutionEvidence()) as WorkflowSwipeSafetyDecision.Allowed
        ).authorization
        val valid = validCompletionEvidence()

        assertDenied(
            WorkflowSwipeSafetyFailure.AUTHORIZATION_MISSING,
            policy.assessCompletion(identity, null, valid),
        )
        assertDenied(
            WorkflowSwipeSafetyFailure.AUTHORIZATION_MISMATCH,
            policy.assessCompletion(
                identity.copy(arguments = identity.arguments + ("direction" to "down")),
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
                WorkflowSwipeSafetyFailure.RESULT_MISMATCH,
                policy.assessCompletion(identity, authorization, mismatched),
            )
        }
        listOf(
            valid.copy(executorVerified = false),
            valid.copy(verificationPassed = false),
            valid.copy(afterObservationVerified = false),
            valid.copy(observedAt = valid.actionCompletedAt - 1L),
        ).forEach { incomplete ->
            assertDenied(
                WorkflowSwipeSafetyFailure.POST_VERIFICATION_MISSING,
                policy.assessCompletion(identity, authorization, incomplete),
            )
        }

        val after = valid.afterViewport
        listOf(
            after.copy(packageName = "com.android.calculator2"),
            after.copy(windowId = 43),
            after.copy(windowGeneration = valid.beforeViewport.windowGeneration),
            after.copy(targetFingerprint = fingerprint('e')),
        ).forEach { drifted ->
            assertDenied(
                WorkflowSwipeSafetyFailure.WINDOW_MISMATCH,
                policy.assessCompletion(identity, authorization, valid.copy(afterViewport = drifted)),
            )
        }
        assertDenied(
            WorkflowSwipeSafetyFailure.CONTENT_UNCHANGED,
            policy.assessCompletion(
                identity,
                authorization,
                valid.copy(
                    afterViewport = after.copy(
                        anchors = valid.beforeViewport.anchors.map { anchor ->
                            anchor.copy(centerY = anchor.centerY - 400)
                        },
                    ),
                ),
            ),
        )
        assertDenied(
            WorkflowSwipeSafetyFailure.DIRECTION_NOT_VERIFIED,
            policy.assessCompletion(
                identity,
                authorization,
                valid.copy(
                    afterViewport = after.copy(
                        anchors = listOf(
                            WorkflowSwipeVisibleAnchor(fingerprint('b'), centerX = 500, centerY = 1_100),
                            WorkflowSwipeVisibleAnchor(fingerprint('c'), centerX = 500, centerY = 1_500),
                            WorkflowSwipeVisibleAnchor(fingerprint('e'), centerX = 500, centerY = 1_900),
                        ),
                    ),
                ),
            ),
        )
        // long: 一个正确锚点不能掩盖同一 viewport 中达到阈值的反向锚点，混合证据必须整体 fail-closed。
        assertDenied(
            WorkflowSwipeSafetyFailure.DIRECTION_NOT_VERIFIED,
            policy.assessCompletion(
                identity,
                authorization,
                valid.copy(
                    afterViewport = after.copy(
                        anchors = listOf(
                            WorkflowSwipeVisibleAnchor(fingerprint('b'), centerX = 500, centerY = 700),
                            WorkflowSwipeVisibleAnchor(fingerprint('c'), centerX = 500, centerY = 1_300),
                            WorkflowSwipeVisibleAnchor(fingerprint('e'), centerX = 500, centerY = 1_900),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            policy.assessCompletion(identity, authorization, valid) is WorkflowSwipeSafetyDecision.Allowed,
        )
    }

    @Test
    fun genericDeviceSafetyPolicyRequiresDedicatedSwipeEvidenceAndKeepsSwipeSafe() {
        val generic = WorkflowDeviceActionSafetyPolicy(enabledToolNames = setOf("device.swipe"))
        val execution = validGenericExecutionEvidence(swipe = null)

        assertDeviceDenied(
            WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
            generic.assessExecution(execution),
        )
        val allowed = generic.assessExecution(execution.copy(swipe = validExecutionEvidence()))
        assertTrue(allowed is WorkflowDeviceActionSafetyDecision.Allowed)
        val authorization = (allowed as WorkflowDeviceActionSafetyDecision.Allowed).authorization
        assertEquals(WorkflowDeviceActionApprovalMode.SAFE_NO_APPROVAL, authorization.approvalMode)
        assertTrue(authorization.swipeAuthorization != null)

        val completion = validGenericCompletionEvidence(authorization, swipe = null)
        assertDeviceDenied(
            WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
            generic.assessCompletion(completion),
        )
        assertTrue(
            generic.assessCompletion(
                completion.copy(swipe = validCompletionEvidence()),
            ) is WorkflowDeviceActionSafetyDecision.Allowed,
        )
    }

    @Test
    fun authorizationIsMinimizedAndAllDirectionsRequireDominantAnonymousAnchorMovement() {
        val directions = listOf("up", "down", "left", "right")

        directions.forEach { direction ->
            val identity = validIdentity(direction)
            val authorization = (
                policy.assessExecution(identity, validExecutionEvidence()) as WorkflowSwipeSafetyDecision.Allowed
            ).authorization
            val completion = validCompletionEvidence(direction)

            assertEquals(DeviceScrollDirection.valueOf(direction.uppercase()), authorization.direction)
            assertTrue(authorization.beforeViewportFingerprint.matches(Regex("[0-9a-f]{64}")))
            assertFalse(authorization.toString().contains("com.android.settings"))
            assertFalse(authorization.toString().contains("snapshot-current"))
            assertFalse(authorization.toString().contains("ref-scroll-container"))
            assertFalse(authorization.toString().contains(fingerprint('a')))
            assertTrue(
                policy.assessCompletion(identity, authorization, completion) is WorkflowSwipeSafetyDecision.Allowed,
            )
        }
    }

    private fun validIdentity(direction: String = "up") = WorkflowDeviceActionIdentity(
        workflowRunId = "workflow-run-current",
        workflowStepId = "workflow-step-current",
        agentRunId = "agent-run-current",
        toolCallId = "tool-call-current",
        toolName = "device.swipe",
        arguments = mapOf(
            "snapshot_id" to "snapshot-current",
            "ref" to "ref-scroll-container",
            "direction" to direction,
        ),
    )

    private fun validTarget() = WorkflowSwipeTargetEvidence(
        enabled = true,
        redacted = false,
        supportsSwipe = true,
        targetFingerprint = fingerprint('d'),
    )

    private fun validExecutionEvidence() = WorkflowSwipeExecutionEvidence(
        target = validTarget(),
        beforeViewport = validViewport(),
    )

    private fun validViewport() = WorkflowSwipeViewportEvidence(
        packageName = "com.android.settings",
        windowId = 42,
        windowGeneration = 7L,
        targetFingerprint = fingerprint('d'),
        anchors = listOf(
            WorkflowSwipeVisibleAnchor(fingerprint('a'), centerX = 500, centerY = 400),
            WorkflowSwipeVisibleAnchor(fingerprint('b'), centerX = 500, centerY = 800),
            WorkflowSwipeVisibleAnchor(fingerprint('c'), centerX = 500, centerY = 1_200),
        ),
    )

    private fun validCompletionEvidence(direction: String = "up") = WorkflowSwipeCompletionEvidence(
        resultAgentRunId = "agent-run-current",
        resultToolCallId = "tool-call-current",
        resultToolName = "device.swipe",
        actionCompletedAt = 2_400L,
        observedAt = 2_500L,
        executorVerified = true,
        verificationPassed = true,
        afterObservationVerified = true,
        beforeViewport = validViewport(),
        afterViewport = afterViewport(direction),
    )

    private fun afterViewport(direction: String): WorkflowSwipeViewportEvidence {
        val positions = when (direction) {
            "up" -> listOf(500 to 300, 500 to 700, 500 to 1_100)
            "down" -> listOf(500 to 1_300, 500 to 1_700, 500 to 2_100)
            "left" -> listOf(100 to 800, 100 to 1_200, 100 to 1_600)
            "right" -> listOf(900 to 800, 900 to 1_200, 900 to 1_600)
            else -> error("unsupported test direction: $direction")
        }
        return validViewport().copy(
            windowGeneration = 8L,
            anchors = listOf('b', 'c', 'e').zip(positions).map { (fingerprintValue, position) ->
                WorkflowSwipeVisibleAnchor(
                    fingerprint = fingerprint(fingerprintValue),
                    centerX = position.first,
                    centerY = position.second,
                )
            },
        )
    }

    private fun validGenericExecutionEvidence(
        swipe: WorkflowSwipeExecutionEvidence?,
    ) = WorkflowDeviceActionExecutionEvidence(
        identity = validIdentity(),
        userIntent = "向上滚动当前系统设置列表",
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
        approval = null,
        nowMillis = 2_100L,
        currentWindowGeneration = 7L,
        liveReferenceMatched = true,
        swipe = swipe,
    )

    private fun validGenericCompletionEvidence(
        authorization: WorkflowDeviceActionAuthorization,
        swipe: WorkflowSwipeCompletionEvidence?,
    ) = WorkflowDeviceActionCompletionEvidence(
        identity = validIdentity(),
        authorization = authorization,
        resultAgentRunId = "agent-run-current",
        resultToolCallId = "tool-call-current",
        resultToolName = "device.swipe",
        success = true,
        executorVerified = true,
        verificationPassed = true,
        actionCompletedAt = 2_400L,
        afterObservation = WorkflowDeviceActionPostObservationEvidence(
            agentRunId = "agent-run-current",
            actionToolCallId = "tool-call-current",
            snapshotId = "snapshot-after-action",
            observedAt = 2_500L,
            windowGeneration = 8L,
            verified = true,
        ),
        cancelled = false,
        swipe = swipe,
    )

    private fun fingerprint(value: Char): String = value.toString().repeat(64)

    private fun assertDenied(
        expected: WorkflowSwipeSafetyFailure,
        decision: WorkflowSwipeSafetyDecision,
    ) {
        assertTrue(decision is WorkflowSwipeSafetyDecision.Denied)
        assertEquals(expected, (decision as WorkflowSwipeSafetyDecision.Denied).reason)
    }

    private fun assertDeviceDenied(
        expected: WorkflowDeviceActionSafetyFailure,
        decision: WorkflowDeviceActionSafetyDecision,
    ) {
        assertTrue(decision is WorkflowDeviceActionSafetyDecision.Denied)
        assertEquals(expected, (decision as WorkflowDeviceActionSafetyDecision.Denied).reason)
    }
}
