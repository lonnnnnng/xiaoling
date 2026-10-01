package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedAgentPlanDecision
import com.longdev.xiaoling.shared.agent.SharedToolCall
import com.longdev.xiaoling.shared.agent.SharedToolDefinition
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
