package com.longdev.xiaoling.ui.agentskill

import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillManagementProjectionTest {
    @Test
    fun bindsMutationAndDeleteCapabilitiesToStableSkillId() {
        val builtIn = skill("built-in", AgentSkillSource.BUILT_IN)
        val local = skill("local", AgentSkillSource.LOCAL)

        val state = AgentSkillManagementProjection.project(
            skills = listOf(builtIn, local),
            loading = false,
            importing = false,
            mutatingSkillIds = setOf("local"),
            registeredTools = emptyList(),
            runHistory = emptyList(),
            loadingAudits = false,
            auditError = null,
            error = null,
            pendingLocalSkillDelete = local,
        )

        val builtInItem = state.skills.single { it.skill.definition.id == "built-in" }
        val localItem = state.skills.single { it.skill.definition.id == "local" }
        assertFalse(builtInItem.showDelete)
        assertTrue(builtInItem.toggleEnabled)
        assertTrue(localItem.showDelete)
        assertFalse(localItem.deleteEnabled)
        assertFalse(localItem.toggleEnabled)
        assertEquals(local, state.pendingLocalSkillDelete)
        assertTrue(state.deletingLocalSkill)
    }

    @Test
    fun keepsStableStateAfterListReorderAndRecordReplacement() {
        val original = listOf(
            skill("built-in", AgentSkillSource.BUILT_IN),
            skill("local", AgentSkillSource.LOCAL),
        )
        val reordered = listOf(
            original[1].copy(definition = original[1].definition.copy(name = "替换后的本地 Skill")),
            original[0],
        )

        val state = AgentSkillManagementProjection.project(
            skills = reordered,
            loading = true,
            importing = true,
            mutatingSkillIds = setOf("local"),
            registeredTools = emptyList(),
            runHistory = emptyList(),
            loadingAudits = false,
            auditError = null,
            error = "读取失败",
        )

        assertEquals(listOf("local", "built-in"), state.skills.map { it.skill.definition.id })
        assertEquals("替换后的本地 Skill", state.skills.first().skill.definition.name)
        assertTrue(state.loading)
        assertTrue(state.importing)
        assertEquals("读取失败", state.error)
    }

    @Test
    fun projectsToolDependenciesAndRecentRunAuditsWithoutTrustingMalformedEvents() {
        val local = skill(
            id = "local",
            source = AgentSkillSource.LOCAL,
            toolNames = setOf("available.tool", "missing.tool"),
        )
        val state = AgentSkillManagementProjection.project(
            skills = listOf(local),
            loading = false,
            importing = false,
            mutatingSkillIds = emptySet(),
            registeredTools = listOf(
                ToolDefinition(
                    name = "available.tool",
                    description = "可用工具",
                    risk = ToolRisk.SAFE,
                ),
            ),
            runHistory = listOf(
                runDetail("new-run", AgentRunStatus.COMPLETED, 200L, "local@3,other@1"),
                runDetail("broken-run", AgentRunStatus.FAILED, 150L, "local@broken"),
                runDetail("old-run", AgentRunStatus.CANCELLED, 100L, "local"),
            ),
            loadingAudits = true,
            auditError = "审计读取失败",
            error = null,
        )

        val item = state.skills.single()
        assertEquals(
            listOf("available.tool" to true, "missing.tool" to false),
            item.dependencies.map { dependency -> dependency.name to dependency.available },
        )
        assertEquals(
            listOf(
                Triple("new-run", 3, AgentRunStatus.COMPLETED),
                Triple("old-run", null, AgentRunStatus.CANCELLED),
            ),
            item.runAudits.map { audit -> Triple(audit.runId, audit.selectedVersion, audit.status) },
        )
        assertTrue(state.loadingAudits)
        assertEquals("审计读取失败", state.auditError)
    }

    private fun skill(
        id: String,
        source: AgentSkillSource,
        toolNames: Set<String> = setOf("app.current_time"),
    ) = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            name = id,
            description = "$id description",
            instructions = "$id instructions",
            toolNames = toolNames,
            keywords = setOf(id),
            declaredRisk = ToolRisk.SAFE,
            source = source,
        ),
        enabled = true,
        importedAt = 1L,
        updatedAt = 1L,
    )

    private fun runDetail(
        runId: String,
        status: AgentRunStatus,
        createdAt: Long,
        selectedSkills: String,
    ) = AgentRunDetailRecord(
        snapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = runId,
                conversationId = "conversation",
                userMessageId = "message",
                goal = "goal",
                status = status,
                result = null,
                errorMessage = null,
                createdAt = createdAt,
                updatedAt = createdAt,
                completedAt = createdAt,
            ),
            steps = emptyList(),
            events = listOf(
                RunEventRecord(
                    id = "event-$runId",
                    runId = runId,
                    type = "skill.selected",
                    message = "selected",
                    createdAt = createdAt,
                    metadata = RunEventMetadata.Reason(selectedSkills),
                ),
            ),
        ),
        approvals = emptyList(),
    )
}
