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
    fun builtInLocalNoteDetailSkillRequiresTheNewSafeReadTool() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "local-note-detail" }

        assertEquals(setOf("notes.list", "notes.search", "notes.get"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInLocalNoteDeleteSkillKeepsMutationSeparateFromReadAndCreateSkills() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "找到并删除标题匹配的这条笔记",
            limit = 3,
        )

        val skill = selected.single { it.id == "local-note-delete" }
        assertEquals(setOf("notes.list", "notes.search", "notes.get", "notes.delete"), skill.toolNames)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertTrue(skill.instructions.contains("只有用户明确要求删除"))
        assertTrue(BuiltInAgentSkillRegistry.all().single { it.id == "local-note-detail" }.declaredRisk == ToolRisk.SAFE)
        assertTrue("notes.delete" !in BuiltInAgentSkillRegistry.all().single { it.id == "local-notes" }.toolNames)
    }

    @Test
    fun builtInKnowledgeSkillSelectsOnlyReadOnlyKnowledgeTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "请从知识库检索发布文档中的真机验收要求",
            limit = 3,
        )

        val skill = selected.single { it.id == "local-knowledge" }
        assertEquals(setOf("knowledge.search"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInCalendarOverviewSkillUsesOnlyReadOnlyCalendarTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "查看我未来一周的日程安排",
            limit = 3,
        )

        val skill = selected.single { it.id == "calendar-overview" }
        assertEquals(setOf("calendar.list_events"), skill.toolNames)
        assertEquals(setOf("android.permission.READ_CALENDAR"), skill.requiredAndroidPermissions)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInCalendarSearchSkillUsesOnlyBoundedReadOnlySearchTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "查日程标题中的评审",
            limit = 3,
        )

        val skill = selected.single { it.id == "calendar-search" }
        assertEquals(setOf("calendar.search_events"), skill.toolNames)
        assertEquals(setOf("android.permission.READ_CALENDAR"), skill.requiredAndroidPermissions)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInDayOverviewSkillCombinesOnlyReadOnlyCalendarAndTaskFacts() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "今天有哪些安排和提醒",
            limit = 3,
        )

        val skill = selected.single { it.id == "day-overview" }
        assertEquals(setOf("calendar.list_events", "tasks.list"), skill.toolNames)
        assertEquals(setOf("android.permission.READ_CALENDAR"), skill.requiredAndroidPermissions)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInTaskOverviewSkillCanInspectOnlyReadOnlyRecentRunFacts() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "每日回顾任务为什么失败了",
            limit = 3,
        )

        val skill = selected.single { it.id == "task-overview" }
        assertEquals(setOf("tasks.list", "tasks.inspect"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不猜测原始错误"))
    }

    @Test
    fun builtInTaskRetrySkillAddsMutatingToolWithoutExpandingReadOnlyOverview() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "请重试失败的每日回顾任务",
            limit = 3,
        )

        val retrySkill = selected.single { skill -> skill.id == "task-retry" }
        assertEquals(setOf("tasks.list", "tasks.inspect", "tasks.retry"), retrySkill.toolNames)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, retrySkill.declaredRisk)
        assertTrue(retrySkill.instructions.contains("当前最新"))
        val overviewSkill = BuiltInAgentSkillRegistry.all().single { skill -> skill.id == "task-overview" }
        assertEquals(setOf("tasks.list", "tasks.inspect"), overviewSkill.toolNames)
        assertEquals(ToolRisk.SAFE, overviewSkill.declaredRisk)
    }

    @Test
    fun builtInTaskCancelSkillKeepsCancellationSeparateFromRetry() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "取消每日回顾提醒",
            limit = 5,
        )

        val cancelSkill = selected.single { skill -> skill.id == "task-cancel" }
        assertEquals(setOf("tasks.list", "tasks.inspect", "tasks.cancel"), cancelSkill.toolNames)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, cancelSkill.declaredRisk)
        assertTrue(cancelSkill.instructions.contains("只有用户明确要求"))
        assertTrue(cancelSkill.instructions.contains("不中断前台手动 Run"))
        val retrySkill = BuiltInAgentSkillRegistry.all().single { skill -> skill.id == "task-retry" }
        assertEquals(setOf("tasks.list", "tasks.inspect", "tasks.retry"), retrySkill.toolNames)
    }

    @Test
    fun builtInDeviceObservationSkillContainsOnlyReadOnlySnapshotTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "观察当前手机界面并告诉我有哪些可访问节点",
            limit = 3,
        )

        val skill = selected.single { it.id == "device-observation" }
        assertEquals(setOf("device.snapshot"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInDeviceControlSkillIncludesOnlyBoundedForegroundActions() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "打开应用后点击按钮并输入文字",
            limit = 3,
        )

        val skill = selected.single { it.id == "device-control" }
        assertEquals(
            setOf(
                "device.snapshot",
                "device.open_app",
                "device.back",
                "device.home",
                "device.tap_ref",
                "device.type_text",
                "device.swipe",
            ),
            skill.toolNames,
        )
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
    }

    @Test
    fun builtInDeviceControlSkillMatchesAllowedAppStepWording() {
        val goals = listOf(
            "打开系统时钟应用。",
            "打开系统计算器应用。",
            "打开系统设置。",
            "打开天气应用。",
            "返回小灵应用。",
        )

        goals.forEach { goal ->
            assertTrue(
                "$goal 应选择有限设备操作 Skill",
                BuiltInAgentSkillRegistry.select(goal).any { it.id == "device-control" },
            )
        }
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
    fun profileSkillAndToolAllowListsBothRestrictSelection() = runTest {
        val catalog = AgentSkillCatalog(
            store = TestAgentSkillStore(),
            registeredTools = { TestToolRegistry().availableTools() },
        )

        val selected = catalog.select(
            goal = "请查找之前的会话并记录成笔记",
            allowedSkillIds = setOf("conversation-recall", "local-notes"),
            allowedToolNames = setOf("app.list_conversations", "app.search_conversations"),
        )

        assertEquals(listOf("conversation-recall"), selected.map { it.id })
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
        val legacyLocalError = runCatching {
            catalog.resolveSelection(listOf(AgentSkillReference("custom-time", version = null)))
        }.exceptionOrNull()
        assertTrue(legacyLocalError is IllegalArgumentException)
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
