package com.longdev.xiaoling.ui.agentprofile

import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile

interface AgentProfileManagementActions {
    fun selectAgentProfile(profileId: String)

    fun saveAgentProfile(draft: AgentProfileEditDraft)

    fun deleteAgentProfile(profileId: String)
}

data class AgentProfileEditDraft(
    val id: String?,
    val name: String,
    val avatar: String,
    val providerId: String,
    val model: String,
    val apiMode: ApiMode,
    val systemPrompt: String,
    val memoryEnabled: Boolean,
    val allowedToolNames: Set<String>,
    val allowedSkillIds: Set<String>,
)

internal data class AgentProfileManagementUiState(
    val profiles: List<AgentProfileManagementItemUiState> = emptyList(),
    val providers: List<ProviderProfile> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val enabledSkills: List<AgentSkillRecord> = emptyList(),
    val error: String? = null,
)

internal data class AgentProfileManagementItemUiState(
    val profile: AgentProfileRecord,
    val providerName: String,
    val selected: Boolean,
    val mutating: Boolean,
    val deleteEnabled: Boolean,
    val providerModelValid: Boolean,
)

internal object AgentProfileManagementProjection {
    fun project(
        profiles: List<AgentProfileRecord>,
        providers: List<ProviderProfile>,
        selectedProfileId: String,
        mutatingProfileIds: Set<String>,
        error: String?,
        tools: List<ToolDefinition>,
        skills: List<AgentSkillRecord>,
    ): AgentProfileManagementUiState {
        val providersById = providers.associateBy(ProviderProfile::id)
        // long: Profile 列表可能在异步保存后重排；选中、变更和配置有效性必须始终按稳定 ID 与当前 Provider 快照绑定。
        return AgentProfileManagementUiState(
            profiles = profiles.map { profile ->
                val provider = providersById[profile.providerId]
                val mutating = profile.id in mutatingProfileIds
                AgentProfileManagementItemUiState(
                    profile = profile,
                    providerName = provider?.name ?: "Provider 已缺失",
                    selected = profile.id == selectedProfileId,
                    mutating = mutating,
                    // long: 至少保留一个 Profile，避免删除操作让 Agent 入口失去可选身份并破坏既有运行前校验。
                    deleteEnabled = profiles.size > 1 && !mutating,
                    providerModelValid = provider != null && profile.model in provider.enabledModels,
                )
            },
            providers = providers,
            tools = tools,
            enabledSkills = skills.filter(AgentSkillRecord::enabled),
            error = error,
        )
    }

    fun createDraft(
        profile: AgentProfileRecord?,
        state: AgentProfileManagementUiState,
    ): AgentProfileEditDraft {
        val initialProvider = state.providers.firstOrNull { it.id == profile?.providerId }
            ?: state.providers.firstOrNull()
        // long: 新建 Profile 默认继承当前已注册工具和已启用 Skill，保证首次保存即可运行；持久化边界仍会重新校验注册表与依赖关系。
        val defaultToolNames = state.tools.mapTo(linkedSetOf(), ToolDefinition::name)
        val defaultSkillIds = state.enabledSkills.mapTo(linkedSetOf()) { it.definition.id }
        // long: Provider 或模型失效时只修复运行配置选择；已有工具、Skill、提示词和记忆边界必须原样保留，避免编辑一次就扩大 Agent 权限。
        return AgentProfileEditDraft(
            id = profile?.id,
            name = profile?.name.orEmpty(),
            avatar = profile?.avatar.orEmpty(),
            providerId = initialProvider?.id.orEmpty(),
            model = profile?.model?.takeIf { it in initialProvider?.enabledModels.orEmpty() }
                ?: initialProvider?.enabledModels?.firstOrNull().orEmpty(),
            apiMode = profile?.apiMode ?: ApiMode.CHAT_COMPLETIONS,
            systemPrompt = profile?.systemPrompt.orEmpty(),
            memoryEnabled = profile?.memoryEnabled ?: true,
            allowedToolNames = profile?.allowedToolNames?.toSet() ?: defaultToolNames,
            allowedSkillIds = profile?.allowedSkillIds?.toSet() ?: defaultSkillIds,
        )
    }
}
