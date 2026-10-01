package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentPlanDecision
import com.longdev.xiaoling.shared.agent.SharedToolCall
import com.longdev.xiaoling.shared.agent.SharedToolDefinition
import com.longdev.xiaoling.shared.agent.SharedToolReceiptStatus
import com.longdev.xiaoling.shared.agent.SharedToolVerificationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAgentAdaptersTest {
    @Test
    fun toolContractsRoundTripRiskAndApprovalFromAndroidDefinition() {
        val definition = ToolDefinition(
            name = "fake.echo",
            description = "回显",
            risk = ToolRisk.REQUIRES_APPROVAL,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = false),
        )
        val call = ToolCall(
            id = "call-1",
            name = definition.name,
            arguments = mapOf("goal" to "hello"),
            risk = definition.risk,
        )

        assertEquals(definition.name, definition.toSharedAgentDefinition().name)
        assertTrue(definition.toSharedAgentDefinition().requiresApproval)
        assertEquals(call.arguments, call.toSharedAgentCall().arguments)
        assertEquals(definition.risk, call.toSharedAgentCall().toAndroidToolCall(definition).risk)
    }

    @Test
    fun executorRechecksCurrentRegistryBeforeCallingAndroidTool() = runTest {
        val registry = FakeToolRegistry()
        val executor = AndroidSharedToolExecutor(registry)
        val result = executor.execute(
            SharedToolCall("call-1", "fake.echo", mapOf("goal" to "hello")),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("hello"))
        assertFalse(executor.execute(SharedToolCall("call-2", "unknown", emptyMap())).success)
    }

    @Test
    fun executorProjectsReceiptVerifiedAndTypedVerificationWithoutIdempotencyKey() = runTest {
        val delegate = FakeToolRegistry()
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = delegate.availableTools()

            override fun definition(name: String): ToolDefinition? = delegate.definition(name)

            override suspend fun execute(call: ToolCall): ToolExecutionResult = ToolExecutionResult(
                success = true,
                content = "已提交",
                verified = true,
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "operation-1",
                    idempotencyKey = "opaque-idempotency-key",
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
                verificationEvidence = ToolVerificationEvidence(
                    status = ToolVerificationStatus.PASSED,
                    toolCallId = call.id,
                    reasonCode = "READ_BACK_MATCHED",
                ),
            )
        }

        val result = AndroidSharedToolExecutor(registry).execute(
            SharedToolCall("call-1", "fake.echo", mapOf("goal" to "证据")),
        )

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals("operation-1", result.executionReceipt?.operationId)
        assertEquals(SharedToolReceiptStatus.COMMITTED, result.executionReceipt?.status)
        assertEquals(SharedToolVerificationStatus.PASSED, result.verification?.status)
        assertEquals("READ_BACK_MATCHED", result.verification?.reasonCode)
    }

    @Test
    fun executorRejectsTypedVerificationBoundToAnotherToolCall() = runTest {
        val delegate = FakeToolRegistry()
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = delegate.availableTools()

            override fun definition(name: String): ToolDefinition? = delegate.definition(name)

            override suspend fun execute(call: ToolCall): ToolExecutionResult = ToolExecutionResult(
                success = true,
                content = "不应被信任",
                verified = true,
                verificationEvidence = ToolVerificationEvidence(
                    status = ToolVerificationStatus.PASSED,
                    toolCallId = "other-call",
                    reasonCode = "WRONG_CALL",
                ),
            )
        }

        val result = AndroidSharedToolExecutor(registry).execute(
            SharedToolCall("call-1", "fake.echo", mapOf("goal" to "错配")),
        )

        assertFalse(result.success)
        assertEquals(false, result.verified)
        assertEquals(SharedToolVerificationStatus.FAILED, result.verification?.status)
        assertEquals("VERIFICATION_TOOL_CALL_MISMATCH", result.verification?.reasonCode)
    }

    @Test
    fun executorRejectsReceiptBoundToAnotherToolCall() = runTest {
        val delegate = FakeToolRegistry()
        val registry = object : ToolRegistry {
            override fun availableTools(): List<ToolDefinition> = delegate.availableTools()

            override fun definition(name: String): ToolDefinition? = delegate.definition(name)

            override suspend fun execute(call: ToolCall): ToolExecutionResult = ToolExecutionResult(
                success = true,
                content = "不应被信任",
                executionReceipt = ToolExecutionReceipt(
                    toolCallId = "other-call",
                    operationId = "operation-2",
                    idempotencyKey = null,
                    status = ToolExecutionReceiptStatus.COMMITTED,
                ),
            )
        }

        val result = AndroidSharedToolExecutor(registry).execute(
            SharedToolCall("call-1", "fake.echo", mapOf("goal" to "错配回执")),
        )

        assertFalse(result.success)
        assertEquals(false, result.verified)
        assertEquals("VERIFICATION_TOOL_CALL_MISMATCH", result.verification?.reasonCode)
    }

    @Test
    fun registryCatalogProjectsToSharedSnapshotWithoutGrantingExecution() {
        val sharedCatalog = FakeToolRegistry().toSharedToolCatalog()

        assertEquals(listOf("fake.echo"), sharedCatalog.entries.map { it.definition.name })
        assertEquals(setOf("native"), sharedCatalog.entries.single().sources)
        assertTrue(sharedCatalog.fingerprint.isNotBlank())
    }

    @Test
    fun approvalAdapterRejectsDriftedSharedDefinitionAndMapsPlanDecision() = runTest {
        val registry = FakeToolRegistry()
        val definition = checkNotNull(registry.definition("fake.echo"))
        val gate = AndroidSharedApprovalGate(AutoApprovalGate(), registry::definition)
        val sharedDefinition = definition.toSharedAgentDefinition()
        val call = SharedToolCall("call-1", "fake.echo", mapOf("goal" to "hello"))

        assertTrue(gate.requestApproval("run-1", call, sharedDefinition))
        assertFalse(
            gate.requestApproval(
                "run-1",
                call,
                sharedDefinition.copy(supportsBackground = true),
            ),
        )
        val androidDecision = SharedAgentPlanDecision.CallTool(call)
            .toAndroidPlanDecision(registry::definition)
        assertEquals("fake.echo", (androidDecision as AgentPlanDecision.CallTool).toolCall.name)
    }
}
