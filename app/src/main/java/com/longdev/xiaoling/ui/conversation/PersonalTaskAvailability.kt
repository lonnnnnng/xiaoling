package com.longdev.xiaoling.ui.conversation

import com.longdev.xiaoling.agent.AppCapabilityPlanningPreflight
import com.longdev.xiaoling.agent.AppCapabilityPlanningPreflightResult
import com.longdev.xiaoling.agent.InstalledAppDirectoryReadResult
import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.ui.AgentLaunchConversationRequirement
import com.longdev.xiaoling.ui.AgentLaunchPreflightCoordinator
import com.longdev.xiaoling.ui.AgentLaunchPreflightOutcome
import com.longdev.xiaoling.ui.AgentLaunchPreflightRejection
import com.longdev.xiaoling.ui.AgentLaunchPreflightRequest
import com.longdev.xiaoling.ui.AgentLaunchProfileSource
import com.longdev.xiaoling.ui.PersonalTaskEnvironment
import com.longdev.xiaoling.ui.XiaoLingUiState

internal enum class PersonalTaskAvailabilityStatus(val label: String) {
    CHECKING("检查中"),
    READY("可运行"),
    NEEDS_CONFIGURATION("需要配置"),
    UNSUPPORTED("当前不支持"),
    UNCONFIRMED("尚未确认"),
}

internal enum class PersonalTaskConfigurationTarget(val label: String) {
    PROVIDER("模型提供方"),
    AGENT_PROFILE("Agent 配置"),
    DEVICE_AGENT("设备 Agent"),
}

internal data class PersonalTaskAvailability(
    val templateId: String,
    val status: PersonalTaskAvailabilityStatus = PersonalTaskAvailabilityStatus.CHECKING,
    val reason: String = "正在检查本机条件",
    val configurationTarget: PersonalTaskConfigurationTarget? = null,
)

internal fun XiaoLingUiState.personalTaskPreflightRequest() = AgentLaunchPreflightRequest(
    profileSource = AgentLaunchProfileSource.Selected(selectedAgentProfileId),
    agentProfiles = agentProfiles,
    providers = profiles,
    registeredToolNames = registeredAgentTools.mapTo(hashSetOf()) { it.name },
    userAgent = userAgent,
    conversationRequirement = AgentLaunchConversationRequirement.Optional,
    existingConversationIds = emptySet(),
)

internal object PersonalTaskAvailabilityPolicy {
    fun evaluate(
        template: PersonalTaskTemplate,
        request: AgentLaunchPreflightRequest,
        environment: PersonalTaskEnvironment,
    ): PersonalTaskAvailability {
        val preflight = AgentLaunchPreflightCoordinator().evaluate(request)
        if (preflight is AgentLaunchPreflightOutcome.Rejected) {
            val providerMissing = request.providers.none { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.enabledModels.isNotEmpty() }
            val target = if (providerMissing || preflight.reason is AgentLaunchPreflightRejection.InvalidProvider) {
                PersonalTaskConfigurationTarget.PROVIDER
            } else {
                PersonalTaskConfigurationTarget.AGENT_PROFILE
            }
            return PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.NEEDS_CONFIGURATION,
                if (providerMissing) "请先配置模型提供方并启用可用模型" else preflight.reason.message, target)
        }
        // long: 复用执行入口的配置校验后只投影有限原因；包含解密密钥的 RuntimeSelection 不能进入诊断 UI、日志或持久化状态。
        val allowedTools = (preflight as AgentLaunchPreflightOutcome.Ready).runtimeSelection.profile.allowedToolNames.toSet()
        val missingTools = template.requiredToolNames - allowedTools.intersect(request.registeredToolNames)
        if (missingTools.isNotEmpty()) {
            return PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.NEEDS_CONFIGURATION,
                "当前 Agent 尚未启用此任务所需能力，请前往 Agent 配置检查工具权限",
                PersonalTaskConfigurationTarget.AGENT_PROFILE)
        }
        val directory = environment.directory ?: return PersonalTaskAvailability(template.id)
        if (directory !is InstalledAppDirectoryReadResult.Success) {
            return PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.UNCONFIRMED,
                "暂时无法读取可启动应用，请刷新后重试")
        }
        val available = (AppCapabilityPlanningPreflight.resolve(directory) as? AppCapabilityPlanningPreflightResult.Available)
            ?.allowedAppPackages.orEmpty()
        // long: 模板只接受已登记的等价包族，不凭名称或能力关键词猜目标；目录截断时不能把未出现误报为未安装。
        if (DeviceActionPolicy.launchPackageCandidates(template.targetPackage).none { it in available }) {
            return if (directory.directory.truncated) {
                PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.UNCONFIRMED,
                    "应用目录不完整，尚未确认此任务的目标应用")
            } else {
                PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.UNSUPPORTED,
                    "当前设备没有此任务已登记的目标应用")
            }
        }
        val healthReason = when (environment.health) {
            null -> return PersonalTaskAvailability(template.id)
            DeviceAgentHealthState.AGENT_DISABLED -> "请先开启设备 Agent"
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED -> "请在系统设置中授权小灵无障碍服务"
            DeviceAgentHealthState.SERVICE_DISCONNECTED -> "无障碍服务尚未连接，请检查设备 Agent 状态"
            DeviceAgentHealthState.READY -> null
        }
        return if (healthReason != null) {
            PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.NEEDS_CONFIGURATION,
                healthReason, PersonalTaskConfigurationTarget.DEVICE_AGENT)
        } else {
            PersonalTaskAvailability(template.id, PersonalTaskAvailabilityStatus.READY,
                "本机条件已就绪，生成计划后仍需确认")
        }
    }

    fun evaluateAll(request: AgentLaunchPreflightRequest, environment: PersonalTaskEnvironment) =
        personalTaskTemplates.map { evaluate(it, request, environment) }
}
