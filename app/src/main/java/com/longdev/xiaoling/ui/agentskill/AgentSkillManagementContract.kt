package com.longdev.xiaoling.ui.agentskill

import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentSkillSelectionCodec
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.ToolDefinition

interface AgentSkillManagementActions {
    fun refreshSkills()

    fun refreshSkillAudits()

    fun requestSkillImport()

    fun setSkillEnabled(skillId: String, enabled: Boolean)

    fun requestLocalSkillDelete(skillId: String)
}

internal data class AgentSkillManagementUiState(
    val skills: List<AgentSkillManagementItemUiState> = emptyList(),
    val loading: Boolean = false,
    val importing: Boolean = false,
    val loadingAudits: Boolean = false,
    val error: String? = null,
    val auditError: String? = null,
    val pendingLocalSkillDelete: AgentSkillRecord? = null,
    val deletingLocalSkill: Boolean = false,
)

internal data class AgentSkillManagementItemUiState(
    val skill: AgentSkillRecord,
    val toggleEnabled: Boolean,
    val showDelete: Boolean,
    val deleteEnabled: Boolean,
    val dependencies: List<AgentSkillDependencyUiState>,
    val runAudits: List<AgentSkillRunAuditUiState>,
)

internal data class AgentSkillDependencyUiState(
    val name: String,
    val available: Boolean,
)

internal data class AgentSkillRunAuditUiState(
    val runId: String,
    val status: AgentRunStatus,
    val selectedVersion: Int?,
)

internal object AgentSkillManagementProjection {
    fun project(
        skills: List<AgentSkillRecord>,
        loading: Boolean,
        importing: Boolean,
        mutatingSkillIds: Set<String>,
        registeredTools: List<ToolDefinition>,
        runHistory: List<AgentRunDetailRecord>,
        loadingAudits: Boolean,
        auditError: String?,
        error: String?,
        pendingLocalSkillDelete: AgentSkillRecord? = null,
    ): AgentSkillManagementUiState {
        val toolsByName = registeredTools.associateBy(ToolDefinition::name)
        val auditsBySkillId = projectRunAudits(runHistory)
        // long: Skill 列表会在导入、启停和刷新后重排或替换对象，所有操作状态必须按稳定 Skill ID 绑定到最新记录。
        return AgentSkillManagementUiState(
            skills = skills.map { skill ->
                val mutating = skill.definition.id in mutatingSkillIds
                val local = skill.definition.source == AgentSkillSource.LOCAL
                AgentSkillManagementItemUiState(
                    skill = skill,
                    toggleEnabled = !mutating,
                    // long: 内置 Skill 是随应用审核发布的能力边界，只向本地导入项显示删除入口，真正删除仍由持久化层再次校验来源。
                    showDelete = local,
                    deleteEnabled = local && !mutating,
                    dependencies = skill.definition.toolNames.sorted().map { toolName ->
                        AgentSkillDependencyUiState(
                            name = toolName,
                            available = toolName in toolsByName,
                        )
                    },
                    runAudits = auditsBySkillId[skill.definition.id].orEmpty(),
                )
            },
            loading = loading,
            importing = importing,
            loadingAudits = loadingAudits,
            error = error,
            auditError = auditError,
            pendingLocalSkillDelete = pendingLocalSkillDelete,
            deletingLocalSkill = pendingLocalSkillDelete?.definition?.id
                ?.let(mutatingSkillIds::contains) == true,
        )
    }

    private fun projectRunAudits(
        runHistory: List<AgentRunDetailRecord>,
    ): Map<String, List<AgentSkillRunAuditUiState>> {
        val audits = linkedMapOf<String, MutableList<AgentSkillRunAuditUiState>>()
        runHistory.sortedByDescending { detail -> detail.snapshot.run.createdAt }.forEach { detail ->
            val selectedSkills = detail.snapshot.events.asSequence()
                .filter { event -> event.type == SKILL_SELECTED_EVENT_TYPE }
                .mapNotNull { event -> (event.metadata as? RunEventMetadata.Reason)?.reason }
                .flatMap { encoded ->
                    // long: 旧 Run 的审计文本可能来自早期版本或损坏数据；管理页只忽略无法证明的引用，不能因一条历史记录阻断整个 Skill 列表。
                    runCatching { AgentSkillSelectionCodec.decode(encoded) }
                        .getOrDefault(emptyList())
                        .asSequence()
                }
                .distinctBy { reference -> reference.id }
                .toList()
            selectedSkills.forEach { reference ->
                val skillAudits = audits.getOrPut(reference.id) { mutableListOf() }
                if (skillAudits.size < MAX_RECENT_SKILL_AUDITS) {
                    skillAudits += AgentSkillRunAuditUiState(
                        runId = detail.snapshot.run.id,
                        status = detail.snapshot.run.status,
                        selectedVersion = reference.version,
                    )
                }
            }
        }
        return audits
    }

    private const val SKILL_SELECTED_EVENT_TYPE = "skill.selected"
    private const val MAX_RECENT_SKILL_AUDITS = 3
}
