package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun catalogMergesSourcesWithStableOrderingAndFingerprint() {
        val definition = ToolDefinition(
            name = "notes.list",
            description = "列出笔记",
            risk = ToolRisk.SAFE,
        )
        val other = ToolDefinition(
            name = "app.current_time",
            description = "读取当前时间",
            risk = ToolRisk.SAFE,
        )

        val catalog = AgentToolCatalog.fromCandidates(
            listOf(
                AgentToolCatalogCandidate(definition, AgentToolCatalogOrigin.SKILL),
                AgentToolCatalogCandidate(other, AgentToolCatalogOrigin.NATIVE),
                AgentToolCatalogCandidate(definition, AgentToolCatalogOrigin.MCP),
            ),
        )
        val reordered = AgentToolCatalog.fromCandidates(
            listOf(
                AgentToolCatalogCandidate(definition, AgentToolCatalogOrigin.MCP),
                AgentToolCatalogCandidate(other, AgentToolCatalogOrigin.NATIVE),
                AgentToolCatalogCandidate(definition, AgentToolCatalogOrigin.SKILL),
            ),
        )

        assertEquals(listOf("app.current_time", "notes.list"), catalog.entries.map { it.definition.name })
        val notesEntry = catalog.entries.single { it.definition.name == "notes.list" }
        assertEquals(setOf(AgentToolCatalogOrigin.SKILL, AgentToolCatalogOrigin.MCP), notesEntry.origins)
        assertEquals(catalog.fingerprint, reordered.fingerprint)
        val sharedCatalog = catalog.toSharedCatalog()
        assertEquals(catalog.entries.map { it.definition.name }, sharedCatalog.entries.map { it.definition.name })
        assertNotEquals("", sharedCatalog.fingerprint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun catalogRejectsSameNameDefinitionDriftAcrossSources() {
        AgentToolCatalog.fromCandidates(
            listOf(
                AgentToolCatalogCandidate(
                    ToolDefinition("notes.list", "列出笔记", ToolRisk.SAFE),
                    AgentToolCatalogOrigin.NATIVE,
                ),
                AgentToolCatalogCandidate(
                    ToolDefinition("notes.list", "不同描述", ToolRisk.SAFE),
                    AgentToolCatalogOrigin.MCP,
                ),
            ),
        )
    }

    @Test
    fun registryExposesCatalogAndIndependentExecutor() = runTest {
        val registry = FakeToolRegistry()
        val catalog = registry.toolCatalog()
        assertTrue(catalog.contains("fake.echo"))
        assertNotEquals("", catalog.fingerprint)

        val result = registry.executor().execute(
            ToolCall(
                name = "fake.echo",
                arguments = mapOf("goal" to "catalog"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        assertTrue(result.success)
        assertTrue(result.content.contains("catalog"))
    }
}
