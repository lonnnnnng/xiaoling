package com.longdev.xiaoling.ui.settingsroot

import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ProviderProfile

internal interface SettingsRootActions {
    fun updateThemeMode(value: AppThemeMode)

    fun openProviderManagement()
    fun openNetworkRequest()
    fun openPromptSettings()
    fun openAgentProfileManagement()
    fun openDeviceAgent()
    fun openAnswerabilityShadow()
    fun openMemoryManagement()
    fun openKnowledgeManagement()
    fun openKnowledgeRelevanceRollout()
    fun openSkillManagement()
    fun openWorkflowManagement()
    fun openAgentRunHistory()
    fun openProcessExitObservations()
    fun exportBackup()
    fun importBackup()
}

internal data class SettingsRootUiState(
    val themeMode: AppThemeMode,
    val providerCount: Int,
    val chatModelCount: Int,
    val selectedAgentProfileName: String?,
    val selectedAgentProfileModel: String?,
    val agentProfileCount: Int,
    val answerabilityShadowEnabled: Boolean,
    val skillCount: Int,
    val enabledSkillCount: Int,
    val localSkillCount: Int,
    val workflowCount: Int,
    val enabledWorkflowCount: Int,
    val runningWorkflowCount: Int,
    val agentRunCount: Int,
    val completedAgentRunCount: Int,
    val processExitObservationCount: Int,
    val backupBusy: Boolean,
)

internal object SettingsRootProjection {
    fun project(
        themeMode: AppThemeMode,
        providers: List<ProviderProfile>,
        agentProfiles: List<AgentProfileRecord>,
        selectedAgentProfileId: String,
        answerabilityShadowEnabled: Boolean,
        skills: List<AgentSkillRecord>,
        workflows: List<WorkflowRecord>,
        workflowRunStatuses: List<WorkflowRunStatus>,
        agentRunStatuses: List<AgentRunStatus>,
        processExitObservationCount: Int,
        backupBusy: Boolean,
    ): SettingsRootUiState {
        // long: 设置根页只按稳定 ID 解析当前 Agent 身份，并把业务列表压缩成摘要，避免页面继续依赖整份应用状态。
        val selectedAgentProfile = agentProfiles.firstOrNull { it.id == selectedAgentProfileId }
        return SettingsRootUiState(
            themeMode = themeMode,
            providerCount = providers.size,
            chatModelCount = providers.sumOf { it.enabledModels.size },
            selectedAgentProfileName = selectedAgentProfile?.name,
            selectedAgentProfileModel = selectedAgentProfile?.model,
            agentProfileCount = agentProfiles.size,
            answerabilityShadowEnabled = answerabilityShadowEnabled,
            skillCount = skills.size,
            enabledSkillCount = skills.count(AgentSkillRecord::enabled),
            localSkillCount = skills.count { it.definition.source == AgentSkillSource.LOCAL },
            workflowCount = workflows.size,
            enabledWorkflowCount = workflows.count(WorkflowRecord::enabled),
            runningWorkflowCount = workflowRunStatuses.count { it == WorkflowRunStatus.RUNNING },
            agentRunCount = agentRunStatuses.size,
            completedAgentRunCount = agentRunStatuses.count { it == AgentRunStatus.COMPLETED },
            processExitObservationCount = processExitObservationCount,
            backupBusy = backupBusy,
        )
    }
}
