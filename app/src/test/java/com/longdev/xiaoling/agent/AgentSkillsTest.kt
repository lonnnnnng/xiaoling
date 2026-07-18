package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillsTest {
    @Test
    fun builtInRegistrySelectsStableLimitedSkillsFromGoal() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "请查找之前的会话，把结果记录成笔记，并注明今天几点",
            limit = 3,
        )

        assertEquals(3, selected.size)
        assertEquals(listOf("conversation-recall", "device-time", "local-notes"), selected.map { it.id }.sorted())
        assertTrue(selected.none { it.id == "personal-memory" })
    }

    @Test
    fun triggerExampleCanSelectSkillWithoutExactKeyword() = runTest {
        val catalog = AgentSkillCatalog(
            store = TestAgentSkillStore(),
            registeredTools = { TestToolRegistry().availableTools() },
        )
        catalog.importDocument(dailyReviewSkillDocument())

        assertTrue(catalog.select("帮我回顾今天的会话").any { it.id == "daily-review" })
    }

    @Test
    fun scopedRegistryOnlyExposesToolsDeclaredBySelectedSkills() = runTest {
        val delegate = TestToolRegistry()
        val notesSkill = AgentSkillDefinition(
            id = "test-notes",
            name = "测试笔记",
            description = "只开放笔记工具",
            instructions = "仅使用笔记工具。",
            toolNames = setOf("notes.search", "notes.create"),
            keywords = setOf("笔记"),
        )
        val scoped = SkillScopedToolRegistry(delegate, listOf(notesSkill))

        assertEquals(listOf("notes.search", "notes.create"), scoped.availableTools().map { it.name })
        assertEquals(ToolRisk.REQUIRES_APPROVAL, scoped.definition("notes.create")?.risk)
        assertNull(scoped.definition("app.current_time"))
        val error = runCatching {
            scoped.execute(ToolCall(name = "app.current_time", arguments = emptyMap(), risk = ToolRisk.SAFE))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun noSelectedSkillKeepsExistingToolSurface() {
        val delegate = TestToolRegistry()
        val scoped = SkillScopedToolRegistry(delegate, emptyList())

        assertEquals(delegate.availableTools(), scoped.availableTools())
    }

    @Test
    fun skillCannotReferenceUnregisteredTool() {
        val invalid = AgentSkillDefinition(
            id = "invalid",
            name = "非法 Skill",
            description = "引用不存在工具",
            instructions = "不应加载。",
            toolNames = setOf("shell.execute"),
            keywords = setOf("shell"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SkillScopedToolRegistry(TestToolRegistry(), listOf(invalid))
        }
    }

    @Test
    fun importedSkillCanBeSelectedAndDisabledButCannotReplaceBuiltInSkill() = runTest {
        val store = TestAgentSkillStore()
        val catalog = AgentSkillCatalog(
            store = store,
            registeredTools = { TestToolRegistry().availableTools() },
        )

        val imported = catalog.importDocument(localSkillDocument("custom-time"))
        assertEquals(AgentSkillSource.LOCAL, imported.definition.source)
        assertEquals(listOf("custom-time"), catalog.select("运行自定义检查").map { it.id })

        catalog.setEnabled("custom-time", false)
        assertTrue(catalog.select("运行自定义检查").none { it.id == "custom-time" })
        val upgraded = catalog.importDocument(localSkillDocument("custom-time", version = 2))
        assertEquals(2, upgraded.definition.version)
        assertEquals(false, upgraded.enabled)
        val restored = catalog.resolveSelection(listOf(AgentSkillReference("custom-time", version = 2)))
        assertEquals(listOf("custom-time"), restored.map { it.id })
        val versionError = runCatching {
            catalog.resolveSelection(listOf(AgentSkillReference("custom-time", version = 1)))
        }.exceptionOrNull()
        assertTrue(versionError is IllegalArgumentException)
        val collision = runCatching {
            catalog.importDocument(localSkillDocument("device-time"))
        }.exceptionOrNull()
        assertTrue(collision is IllegalArgumentException)
    }

    @Test
    fun selectionAuditCodecReadsLegacyIdsAndVersionedIds() {
        assertEquals(
            listOf(AgentSkillReference("legacy-skill", null), AgentSkillReference("current-skill", 3)),
            AgentSkillSelectionCodec.decode("legacy-skill, current-skill@3"),
        )
    }

    private fun localSkillDocument(id: String, version: Int = 1) = """
        {
          "schemaVersion": 1,
          "id": "$id",
          "version": $version,
          "name": "自定义时间检查",
          "description": "读取设备时间。",
          "source": "local",
          "trigger": {"keywords": ["自定义检查"], "examples": ["运行自定义检查"]},
          "tools": ["app.current_time"],
          "requirements": {"androidPermissions": [], "risk": "SAFE"},
          "instructions": "读取设备当前时间。",
          "failureRecovery": "失败时停止。",
          "completionCriteria": "时间结果可读。"
        }
    """.trimIndent()

    private fun dailyReviewSkillDocument() = """
        {
          "schemaVersion": 1,
          "id": "daily-review",
          "version": 1,
          "name": "每日回顾",
          "description": "回顾最近会话。",
          "source": "local",
          "trigger": {"keywords": ["每日回顾"], "examples": ["帮我回顾今天的会话"]},
          "tools": ["app.current_time"],
          "requirements": {"androidPermissions": [], "risk": "SAFE"},
          "instructions": "读取时间并回顾会话。",
          "failureRecovery": "失败时停止。",
          "completionCriteria": "回顾结果可读。"
        }
    """.trimIndent()

    private class TestAgentSkillStore : AgentSkillStore {
        private val records = linkedMapOf<String, AgentSkillRecord>()

        override suspend fun synchronizeBuiltIns(definitions: List<AgentSkillDefinition>) {
            definitions.forEach { definition ->
                records.putIfAbsent(
                    definition.id,
                    AgentSkillRecord(definition, enabled = true, importedAt = 0L, updatedAt = 0L),
                )
            }
        }

        override suspend fun list(): List<AgentSkillRecord> = records.values.toList()

        override suspend fun upsert(record: AgentSkillRecord): AgentSkillRecord {
            records[record.definition.id] = record
            return record
        }

        override suspend fun setEnabled(skillId: String, enabled: Boolean): AgentSkillRecord? {
            val current = records[skillId] ?: return null
            return current.copy(enabled = enabled).also { records[skillId] = it }
        }

        override suspend fun deleteLocal(skillId: String): Boolean = records.remove(skillId) != null
    }

    private class TestToolRegistry : ToolRegistry {
        private val tools = listOf(
            ToolDefinition("app.current_time", "读取时间", ToolRisk.SAFE),
            ToolDefinition("notes.search", "搜索笔记", ToolRisk.SAFE),
            ToolDefinition("notes.create", "创建笔记", ToolRisk.REQUIRES_APPROVAL),
            ToolDefinition("memory.search", "搜索记忆", ToolRisk.SAFE),
        )

        override fun availableTools(): List<ToolDefinition> = tools

        override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

        override suspend fun execute(call: ToolCall): ToolExecutionResult {
            return ToolExecutionResult(success = true, content = call.name)
        }
    }
}
