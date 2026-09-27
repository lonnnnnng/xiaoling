package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectAgentGoalVerificationPolicyTest {
    @Test
    fun allVerifiedDeviceActionsProduceVerifiedGoal() {
        val decision = DirectAgentGoalVerificationPolicy.evaluate(
            context(
                VerifiedToolExecution(
                    toolName = "device.open_app",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = actionResult("com.google.android.calculator", 12_000L),
                ),
                VerifiedToolExecution(
                    toolName = "device.tap_ref",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = actionResult("com.google.android.calculator", 12_100L),
                ),
                VerifiedToolExecution(
                    toolName = "device.type_text",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = partialActionResult("com.google.android.calculator", 12_200L),
                ),
            ),
        )

        requireNotNull(decision)
        assertEquals(DirectAgentGoalVerificationStatus.VERIFIED, decision.status)
        assertEquals(listOf("device.open_app", "device.tap_ref", "device.type_text"), decision.verifiedToolNames)
        assertEquals("com.google.android.calculator", decision.latestObservedPackageName)
        assertEquals(12_200L, decision.latestObservedAt)
    }

    @Test
    fun failedActionKeepsGoalPartialAndIgnoresNonDeviceTools() {
        val decision = DirectAgentGoalVerificationPolicy.evaluate(
            context(
                VerifiedToolExecution(
                    toolName = "device.open_app",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = actionResult("com.google.android.calculator", 12_000L),
                ),
                VerifiedToolExecution(
                    toolName = "device.tap_ref",
                    arguments = emptyMap(),
                    success = false,
                    verificationStatus = AgentVerificationStatus.FAILED,
                    rawResult = "动作失败",
                ),
                VerifiedToolExecution(
                    toolName = "app.current_time",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "当前时间",
                ),
            ),
        )

        requireNotNull(decision)
        assertEquals(DirectAgentGoalVerificationStatus.PARTIAL, decision.status)
        assertEquals(listOf("device.tap_ref"), decision.failedToolNames)
        assertEquals(2, decision.totalActionCount)
    }

    @Test
    fun laterOrdinaryToolObservationCannotReplaceLatestDeviceObservation() {
        val decision = DirectAgentGoalVerificationPolicy.evaluate(
            context(
                VerifiedToolExecution(
                    toolName = "device.open_app",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = actionResult("com.android.calculator2", 12_000L),
                ),
                VerifiedToolExecution(
                    toolName = "app.current_time",
                    arguments = emptyMap(),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = actionResult("com.android.settings", 99_000L),
                ),
            ),
        )

        requireNotNull(decision)
        assertEquals("com.android.calculator2", decision.latestObservedPackageName)
        assertEquals(12_000L, decision.latestObservedAt)
    }

    @Test
    fun ordinaryAgentRunDoesNotShowDeviceGoalCard() {
        assertNull(
            DirectAgentGoalVerificationPolicy.evaluate(
                context(
                    VerifiedToolExecution(
                        toolName = "app.current_time",
                        arguments = emptyMap(),
                        success = true,
                        verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                        rawResult = "当前时间",
                    ),
                ),
            ),
        )
    }

    private fun context(vararg executions: VerifiedToolExecution) = VerifiedAgentContext(
        runId = "run-280",
        toolName = executions.last().toolName,
        arguments = emptyMap(),
        success = executions.all(VerifiedToolExecution::success),
        verificationStatus = executions.last().verificationStatus,
        rawResult = executions.last().rawResult,
        toolExecutions = executions.toList(),
    )

    private fun actionResult(packageName: String, capturedAt: Long): String =
        """{"action":"tap","verified":true,"after_snapshot":{"snapshot_id":"snapshot-$capturedAt","package":"$packageName","window_id":1,"window_generation":2,"captured_at":$capturedAt,"expires_at":${capturedAt + 30_000},"redacted_node_count":0,"truncated":false,"nodes":[]}}"""

    private fun partialActionResult(packageName: String, capturedAt: Long): String =
        """{"action":"type_text","verified":true,"after_snapshot":{"package":"$packageName","window_id":1,"window_generation":2,"captured_at":$capturedAt,"redacted_node_count":1,"node_count":4,"truncated":false}}"""
}
