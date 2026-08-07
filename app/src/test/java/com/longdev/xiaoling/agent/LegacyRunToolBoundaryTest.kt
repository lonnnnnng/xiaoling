package com.longdev.xiaoling.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRunToolBoundaryTest {
    @Test
    fun legacyRunToolSnapshotDoesNotIncludeNewKnowledgeTool() {
        assertTrue("memory.search" in LEGACY_RUN_TOOL_NAMES)
        assertTrue("notes.get" in LEGACY_RUN_TOOL_NAMES)
        assertTrue("notes.create" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("notes.delete" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("notes.update" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("memory.get" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("knowledge.search" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("calendar.delete_event" in LEGACY_RUN_TOOL_NAMES)
        assertFalse("calendar.update_event" in LEGACY_RUN_TOOL_NAMES)
    }

    @Test
    fun legacyRunRegistryCannotDiscoverOrResolveNewKnowledgeTool() {
        val registry = legacyRunToolRegistry(
            object : ToolRegistry {
                private val tools = LEGACY_RUN_TOOL_NAMES.plus("knowledge.search").map { name ->
                    ToolDefinition(name, "test $name", ToolRisk.SAFE)
                }

                override fun availableTools(): List<ToolDefinition> = tools
                override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }
                override suspend fun execute(call: ToolCall): ToolExecutionResult =
                    ToolExecutionResult(success = true, content = call.name)
            },
        )

        assertFalse(registry.availableTools().any { it.name == "knowledge.search" })
        assertFalse(registry.definition("knowledge.search") != null)
    }
}
