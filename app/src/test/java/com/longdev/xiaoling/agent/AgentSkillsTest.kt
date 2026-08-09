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
    fun builtInAppInfoSkillExposesOnlyTheReadOnlyAppInfoTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "请告诉我当前应用的包名和版本号",
            limit = 3,
        )

        val skill = selected.single { it.id == "app-info" }
        assertEquals(setOf("app.get_info"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不得要求或猜测 Provider"))
        assertTrue("app.get_info" !in BuiltInAgentSkillRegistry.all().single { it.id == "device-time" }.toolNames)
    }

    @Test
    fun builtInAgentProfileInfoSkillStaysDirectAndNonSensitive() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "当前使用的是哪个 Agent 和模型",
            limit = 3,
        )

        val skill = selected.single { it.id == "agent-profile-info" }
        assertEquals(setOf("agent.get_profile"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不得要求或猜测 Provider 地址"))
        assertTrue(skill.failureRecovery.contains("前台直接 Agent"))
    }

    @Test
    fun builtInBatteryStatusSkillExposesOnlyTheReadOnlyBatteryTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "当前手机还有多少电，是否正在充电",
            limit = 3,
        )

        val skill = selected.single { it.id == "battery-status" }
        assertEquals(setOf("app.get_battery"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不得要求或猜测设备标识"))
    }

    @Test
    fun builtInConnectivityStatusSkillExposesOnlyTheReadOnlyConnectivityTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "现在是否联网，当前使用什么网络",
            limit = 3,
        )

        val skill = selected.single { it.id == "connectivity-status" }
        assertEquals(setOf("app.get_connectivity"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不得要求或猜测网络名称"))
    }

    @Test
    fun builtInStorageStatusSkillExposesOnlyTheReadOnlyStorageTool() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "手机还剩多少存储空间，当前使用率是多少",
            limit = 3,
        )

        val skill = selected.single { it.id == "storage-status" }
        assertEquals(setOf("app.get_storage"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("不得要求或猜测文件名"))
    }

    @Test
    fun builtInConversationDetailSkillKeepsExistingRecallSkillStable() {
        val detail = BuiltInAgentSkillRegistry.all().single { it.id == "conversation-detail" }
        val existing = BuiltInAgentSkillRegistry.all().single { it.id == "conversation-recall" }

        assertEquals(
            setOf("app.list_conversations", "app.search_conversations", "app.get_conversation"),
            detail.toolNames,
        )
        assertEquals(ToolRisk.SAFE, detail.declaredRisk)
        assertTrue(detail.instructions.contains("不得猜测 ID"))
        assertTrue("app.get_conversation" !in existing.toolNames)
    }

    @Test
    fun builtInLocalNoteDetailSkillRequiresTheNewSafeReadTool() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "local-note-detail" }

        assertEquals(setOf("notes.list", "notes.search", "notes.get"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
    }

    @Test
    fun builtInMemoryDetailSkillKeepsExistingMemorySkillStable() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "personal-memory-detail" }
        val existing = BuiltInAgentSkillRegistry.all().single { it.id == "personal-memory" }

        assertEquals(setOf("memory.search", "memory.get"), skill.toolNames)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue("memory.get" !in existing.toolNames)
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
    fun builtInLocalNoteUpdateSkillRequiresReadRevisionAndKeepsExistingSkillsStable() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "把这条笔记的正文更新为新内容",
            limit = 3,
        )

        val skill = selected.single { it.id == "local-note-update" }
        assertEquals(setOf("notes.list", "notes.search", "notes.get", "notes.update"), skill.toolNames)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertTrue(skill.instructions.contains("revision"))
        assertTrue("notes.update" !in BuiltInAgentSkillRegistry.all().single { it.id == "local-notes" }.toolNames)
        assertTrue("notes.update" !in BuiltInAgentSkillRegistry.all().single { it.id == "local-note-detail" }.toolNames)
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
    fun builtInCalendarCreateSkillKeepsMutationSeparateAndRequiresCalendarReadWrite() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "calendar-create" }

        assertEquals(setOf("calendar.create_event"), skill.toolNames)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertEquals(
            setOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
            skill.requiredAndroidPermissions,
        )
        assertTrue(skill.instructions.contains("一次性非全天"))
        assertTrue("calendar.create_event" !in BuiltInAgentSkillRegistry.all().single { it.id == "calendar-overview" }.toolNames)
    }

    @Test
    fun builtInCalendarDeleteSkillRequiresFreshDetailAndKeepsOldSkillsStable() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "calendar-delete" }

        assertEquals(
            setOf("calendar.search_events", "calendar.get", "calendar.delete_event"),
            skill.toolNames,
        )
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertEquals(
            setOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
            skill.requiredAndroidPermissions,
        )
        assertTrue(skill.instructions.contains("expected_fingerprint"))
        assertTrue(skill.instructions.contains("occurrence"))
        assertTrue("calendar.delete_event" !in BuiltInAgentSkillRegistry.all().single { it.id == "calendar-detail" }.toolNames)
        assertTrue("calendar.delete_event" !in BuiltInAgentSkillRegistry.all().single { it.id == "calendar-create" }.toolNames)
    }

    @Test
    fun builtInCalendarUpdateSkillRequiresFreshDetailAndKeepsOldSkillsStable() {
        val skill = BuiltInAgentSkillRegistry.all().single { it.id == "calendar-update" }

        assertEquals(
            setOf("calendar.search_events", "calendar.get", "calendar.update_event"),
            skill.toolNames,
        )
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertEquals(
            setOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
            skill.requiredAndroidPermissions,
        )
        assertTrue(skill.instructions.contains("expected_fingerprint"))
        assertTrue(skill.instructions.contains("scope=event"))
        assertTrue(skill.instructions.contains("occurrence"))
        assertTrue("calendar.update_event" !in BuiltInAgentSkillRegistry.all().single { it.id == "calendar-detail" }.toolNames)
        assertTrue("calendar.update_event" !in BuiltInAgentSkillRegistry.all().single { it.id == "calendar-delete" }.toolNames)
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
    fun builtInCalendarDetailSkillSearchesBeforeReadingAuthoritativeDetail() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "查找产品评审日程并查看权威详情",
            limit = 3,
        )

        val skill = selected.single { it.id == "calendar-detail" }
        assertEquals(setOf("calendar.search_events", "calendar.get"), skill.toolNames)
        assertEquals(setOf("android.permission.READ_CALENDAR"), skill.requiredAndroidPermissions)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("先调用 calendar.search_events"))
        assertTrue(skill.instructions.contains("不得猜测"))
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
    fun builtInPersonalBriefingSkillCombinesCalendarTasksAndOneExplicitNoteWithoutExpandingDayOverview() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "生成个人简报，查看今天安排、提醒和项目代号相关笔记全文",
            limit = 3,
        )

        val skill = selected.single { it.id == "personal-briefing" }
        assertEquals("personal-briefing", selected.first().id)
        assertEquals(
            setOf("calendar.list_events", "tasks.list", "notes.search", "notes.get"),
            skill.toolNames,
        )
        assertEquals(setOf("android.permission.READ_CALENDAR"), skill.requiredAndroidPermissions)
        assertEquals(ToolRisk.SAFE, skill.declaredRisk)
        assertTrue(skill.instructions.contains("明确关键词"))
        assertTrue(skill.completionCriteria.contains("日程、任务和笔记"))
        assertEquals(
            setOf("calendar.list_events", "tasks.list"),
            BuiltInAgentSkillRegistry.all().single { it.id == "day-overview" }.toolNames,
        )
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
    fun builtInTaskScheduleControlSkillPausesAndResumesOnlyRecurringPlans() {
        val selected = BuiltInAgentSkillRegistry.select(
            goal = "暂停每日回顾提醒，明天再恢复",
            limit = 5,
        )

        val skill = selected.single { candidate -> candidate.id == "task-schedule-control" }
        assertEquals(
            setOf("tasks.list", "tasks.inspect", "tasks.pause", "tasks.resume"),
            skill.toolNames,
        )
        assertEquals(ToolRisk.REQUIRES_APPROVAL, skill.declaredRisk)
        assertTrue(skill.instructions.contains("周期计划"))
        assertTrue(skill.instructions.contains("不补跑"))
        assertTrue(skill.instructions.contains("不中断正在运行"))
        val cancelSkill = BuiltInAgentSkillRegistry.all().single { candidate -> candidate.id == "task-cancel" }
        assertEquals(setOf("tasks.list", "tasks.inspect", "tasks.cancel"), cancelSkill.toolNames)
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
