package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPluginCapabilityProjectionTest {
    @Test
    fun enabledPluginProjectsOnlySafeAvailableProfileIntersection() {
        val registry = ProjectionToolRegistry()
        val plugin = InstalledAgentPlugin(
            manifest = manifest(
                capabilities = setOf("app.current_time", "app.get_info", "mcp.call", "unknown.capability"),
                permissions = setOf(
                    AgentPluginPermission.READ_ONLY_CAPABILITIES,
                    AgentPluginPermission.MCP,
                ),
            ),
            enabled = true,
        )

        val projection = AgentPluginCapabilityProjector.project(
            plugin = plugin,
            registry = registry,
            allowedToolNames = setOf("app.current_time", "app.get_info", "mcp.call"),
        )

        assertEquals(setOf("app.current_time"), projection.effectiveReadOnlyCapabilities)
        assertEquals(
            setOf("app.get_info", "mcp.call", "unknown.capability"),
            projection.excludedCapabilities,
        )
    }

    @Test
    fun pluginWithoutReadOnlyPermissionNeverEntersAci() {
        val projection = AgentPluginCapabilityProjector.project(
            plugin = InstalledAgentPlugin(
                manifest = manifest(
                    capabilities = setOf("app.current_time"),
                    permissions = setOf(AgentPluginPermission.NETWORK),
                ),
                enabled = true,
            ),
            registry = ProjectionToolRegistry(),
            allowedToolNames = setOf("app.current_time"),
        )

        assertTrue(projection.effectiveReadOnlyCapabilities.isEmpty())
        assertEquals(setOf("app.current_time"), projection.excludedCapabilities)
    }

    @Test
    fun disabledPluginAndHiddenRunToolRemainDeclarationOnly() {
        val registry = ProjectionToolRegistry(availableNames = setOf("app.current_time"))
        val projection = AgentPluginCapabilityProjector.project(
            plugin = InstalledAgentPlugin(
                manifest = manifest(capabilities = setOf("app.current_time")),
                enabled = false,
            ),
            registry = registry,
            allowedToolNames = setOf("app.current_time"),
        )
        assertTrue(projection.effectiveReadOnlyCapabilities.isEmpty())

        val enabledButHidden = AgentPluginCapabilityProjector.project(
            plugin = projectionPlugin(enabled = true),
            registry = ProjectionToolRegistry(availableNames = emptySet()),
            allowedToolNames = setOf("app.current_time"),
        )
        assertTrue(enabledButHidden.effectiveReadOnlyCapabilities.isEmpty())
    }

    @Test
    fun projectionDoesNotExecutePluginOrGrantNonReadOnlyPermission() {
        val registry = ProjectionToolRegistry()
        val projection = AgentPluginCapabilityProjector.project(
            plugin = projectionPlugin(
                capabilities = setOf("app.current_time", "terminal.exec", "device.tap"),
                permissions = setOf(
                    AgentPluginPermission.READ_ONLY_CAPABILITIES,
                    AgentPluginPermission.TERMINAL,
                    AgentPluginPermission.DEVICE_ACTION,
                ),
                enabled = true,
            ),
            registry = registry,
            allowedToolNames = setOf("app.current_time", "terminal.exec", "device.tap"),
        )

        assertEquals(setOf("app.current_time"), projection.effectiveReadOnlyCapabilities)
        assertEquals(0, registry.executeCount)
    }

    @Test
    fun profileAndDynamicRegistryCanOnlyNarrowPluginPreview() {
        val definitions = listOf(ToolDefinition("app.current_time", "当前时间", ToolRisk.SAFE))
        val plugin = projectionPlugin(enabled = true)
        val profileDenied = AgentPluginCapabilityProjector.project(
            plugin,
            definitions,
            definitions,
            allowedToolNames = emptySet(),
        )
        val runHidden = AgentPluginCapabilityProjector.project(
            plugin,
            definitions,
            availableTools = emptyList(),
            allowedToolNames = setOf("app.current_time"),
        )
        val definitionHidden = AgentPluginCapabilityProjector.project(
            plugin = plugin,
            registry = ProjectionToolRegistry(dynamicDefinitionNames = emptySet()),
            allowedToolNames = setOf("app.current_time"),
        )
        assertTrue(profileDenied.effectiveReadOnlyCapabilities.isEmpty())
        assertTrue(runHidden.effectiveReadOnlyCapabilities.isEmpty())
        assertTrue(definitionHidden.effectiveReadOnlyCapabilities.isEmpty())
    }

    @Test
    fun invalidExecutableManifestDoesNotEnterProjectionEvenIfCallerBypassesRegistry() {
        val plugin = projectionPlugin(enabled = true).copy(
            manifest = manifest(capabilities = setOf("app.current_time"))
                .copy(executionMode = AgentPluginExecutionMode.EXTERNAL_CODE),
        )
        val projection = AgentPluginCapabilityProjector.project(
            plugin = plugin,
            registry = ProjectionToolRegistry(),
            allowedToolNames = setOf("app.current_time"),
        )
        assertTrue(projection.effectiveReadOnlyCapabilities.isEmpty())
        assertEquals(setOf("app.current_time"), projection.excludedCapabilities)
    }

    @Test
    fun projectAllKeepsDeterministicPluginOrder() {
        val registry = ProjectionToolRegistry()
        val result = AgentPluginCapabilityProjector.projectAll(
            plugins = listOf(
                projectionPlugin(id = "zeta", capabilities = setOf("app.current_time"), enabled = true),
                projectionPlugin(id = "alpha", capabilities = setOf("app.current_time"), enabled = true),
            ),
            registry = registry,
            allowedToolNames = setOf("app.current_time"),
        )
        assertEquals(listOf("alpha", "zeta"), result.map(AgentPluginCapabilityProjection::pluginId))
    }

    private fun manifest(
        id: String = "demo",
        capabilities: Set<String>,
        permissions: Set<AgentPluginPermission> = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
    ) = AgentPluginManifest(
        id = id,
        version = "1.0.0",
        name = "演示插件",
        description = "只声明能力，不加载代码",
        publisher = "long",
        capabilities = capabilities,
        permissions = permissions,
    )

    private fun projectionPlugin(
        id: String = "demo",
        capabilities: Set<String> = setOf("app.current_time"),
        permissions: Set<AgentPluginPermission> = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
        enabled: Boolean = false,
    ) = InstalledAgentPlugin(manifest(id, capabilities, permissions), enabled)

    private class ProjectionToolRegistry(
        private val availableNames: Set<String> = setOf("app.current_time", "app.get_info", "mcp.call"),
        private val dynamicDefinitionNames: Set<String> = availableNames,
    ) : ToolRegistry {
        private val definitions = listOf(
            ToolDefinition("app.current_time", "读取测试事实", ToolRisk.SAFE),
            ToolDefinition("app.get_info", "需要审批的测试能力", ToolRisk.REQUIRES_APPROVAL),
            ToolDefinition("mcp.call", "MCP 测试能力", ToolRisk.SAFE),
        )
        var executeCount: Int = 0
            private set

        override fun availableTools(): List<ToolDefinition> = definitions.filter { it.name in availableNames }

        override fun definition(name: String): ToolDefinition? = definitions.firstOrNull {
            it.name == name && it.name in dynamicDefinitionNames
        }

        override fun registeredTools(): List<ToolDefinition> = definitions

        override suspend fun execute(call: ToolCall): ToolExecutionResult {
            executeCount += 1
            return ToolExecutionResult(success = true, content = call.name)
        }
    }
}
