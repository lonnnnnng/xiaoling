package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentProfileRuntimeConfigPolicy
import com.longdev.xiaoling.agent.AgentProfileSnapshot
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig

internal sealed interface AgentLaunchProfileSource {
    data class Selected(
        val selectedProfileId: String,
    ) : AgentLaunchProfileSource

    data class Snapshot(
        val profile: AgentProfileSnapshot,
    ) : AgentLaunchProfileSource

    companion object {
        fun forRecoveredRun(
            sourceProfile: AgentProfileSnapshot?,
            selectedProfileId: String,
        ): AgentLaunchProfileSource {
            // long: 新 Run 使用当前选择，恢复旧 Run 则优先保留原执行身份；仅旧审计缺失时兼容回退当前选择。
            return sourceProfile?.let(::Snapshot) ?: Selected(selectedProfileId)
        }
    }
}

internal sealed interface AgentLaunchConversationRequirement {
    data object Optional : AgentLaunchConversationRequirement

    data class Existing(
        val conversationId: String,
        val missingMessage: String,
    ) : AgentLaunchConversationRequirement
}

internal class AgentLaunchPreflightRequest(
    val profileSource: AgentLaunchProfileSource,
    val agentProfiles: List<AgentProfileRecord>,
    val providers: List<ProviderProfile>,
    val registeredToolNames: Set<String>,
    val userAgent: String,
    val conversationRequirement: AgentLaunchConversationRequirement,
    val existingConversationIds: Set<String>,
)

/**
 * long: 请求配置包含解密后的 API Key，只能在当前进程的启动链中短暂传递，不能写入日志、UI 状态或持久化事件。
 */
internal class AgentRuntimeSelection(
    val config: ProviderRequestConfig,
    val profile: AgentProfileSnapshot,
)

internal sealed interface AgentLaunchPreflightOutcome {
    data class Ready(
        val runtimeSelection: AgentRuntimeSelection,
        val conversationId: String?,
    ) : AgentLaunchPreflightOutcome

    data class Rejected(
        val reason: AgentLaunchPreflightRejection,
    ) : AgentLaunchPreflightOutcome
}

internal sealed interface AgentLaunchPreflightRejection {
    val message: String

    data class ConversationMissing(
        val conversationId: String,
        override val message: String,
    ) : AgentLaunchPreflightRejection

    data object ProfileMissing : AgentLaunchPreflightRejection {
        override val message: String = "请先在设置页创建并选择 Agent Profile"
    }

    data class InvalidProfile(
        override val message: String,
    ) : AgentLaunchPreflightRejection

    data class UnknownTools(
        val toolNames: List<String>,
    ) : AgentLaunchPreflightRejection {
        override val message: String = "Agent Profile 包含未注册工具：${toolNames.joinToString()}"
    }

    data class InvalidProvider(
        override val message: String,
    ) : AgentLaunchPreflightRejection
}

internal class AgentLaunchPreflightCoordinator {
    fun evaluate(request: AgentLaunchPreflightRequest): AgentLaunchPreflightOutcome {
        // long: 重试、恢复与 Workflow 必须先证明原会话仍存在；普通 /agent 保持可在空占位上创建会话的既有入口语义。
        val conversationId = when (val requirement = request.conversationRequirement) {
            AgentLaunchConversationRequirement.Optional -> null
            is AgentLaunchConversationRequirement.Existing -> {
                if (requirement.conversationId.isBlank() || requirement.conversationId !in request.existingConversationIds) {
                    return AgentLaunchPreflightOutcome.Rejected(
                        AgentLaunchPreflightRejection.ConversationMissing(
                            conversationId = requirement.conversationId,
                            message = requirement.missingMessage,
                        ),
                    )
                }
                requirement.conversationId
            }
        }

        val profile = when (val source = request.profileSource) {
            is AgentLaunchProfileSource.Selected -> request.agentProfiles
                .firstOrNull { it.id == source.selectedProfileId }
                ?.snapshot()
                ?: return AgentLaunchPreflightOutcome.Rejected(AgentLaunchPreflightRejection.ProfileMissing)

            is AgentLaunchProfileSource.Snapshot -> source.profile
        }
        runCatching { AgentProfilePolicy.validateRunnable(profile) }
            .onFailure { error ->
                return AgentLaunchPreflightOutcome.Rejected(
                    AgentLaunchPreflightRejection.InvalidProfile(
                        error.message ?: "Agent Profile 配置无效",
                    ),
                )
            }

        val unknownTools = profile.allowedToolNames
            .filter { it !in request.registeredToolNames }
            .sorted()
        if (unknownTools.isNotEmpty()) {
            return AgentLaunchPreflightOutcome.Rejected(
                AgentLaunchPreflightRejection.UnknownTools(unknownTools),
            )
        }

        val config = runCatching {
            AgentProfileRuntimeConfigPolicy.resolve(
                profile = profile,
                providers = request.providers,
                userAgent = request.userAgent,
            )
        }.getOrElse { error ->
            return AgentLaunchPreflightOutcome.Rejected(
                AgentLaunchPreflightRejection.InvalidProvider(
                    error.message ?: "Agent Profile 请求配置无效",
                ),
            )
        }
        return AgentLaunchPreflightOutcome.Ready(
            runtimeSelection = AgentRuntimeSelection(config = config, profile = profile),
            conversationId = conversationId,
        )
    }
}
