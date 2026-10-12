package com.longdev.xiaoling.agent

/**
 * 插件能力投影的审计结果。
 *
 * long: 插件 manifest 只能声明能力；通过交集门禁的名称也只用于设置页预览，
 * 不接入 ACI 调用或工具执行。被排除的名称仍保留，方便解释“声明过但没有执行权”。
 */
data class AgentPluginCapabilityProjection(
    val pluginId: String,
    val enabled: Boolean,
    val readOnlyPermissionDeclared: Boolean,
    val declaredCapabilities: Set<String>,
    val effectiveReadOnlyCapabilities: Set<String>,
    val excludedCapabilities: Set<String>,
)

object AgentPluginCapabilityProjector {
    /**
     * 计算单个插件在当前工具上下文中符合 ACI 只读发现条件的能力。
     *
     * long: 静态注册表防止 manifest 指向未知工具；当前 availableTools 与 definition
     * 共同收紧动态可用性，避免 Workflow/Run 隐藏工具后仍被插件预览为可用。
     */
    fun project(
        plugin: InstalledAgentPlugin,
        registry: ToolRegistry,
        allowedToolNames: Set<String>,
    ): AgentPluginCapabilityProjection = project(
        plugin = plugin,
        registeredTools = registry.registeredTools(),
        availableTools = registry.availableTools()
            .filter { registry.definition(it.name)?.risk == ToolRisk.SAFE },
        allowedToolNames = allowedToolNames,
    )

    /**
     * 设置页用静态注册表做预览；实际 Run 应传入其动态可用目录，不把预览当成执行授权。
     */
    fun project(
        plugin: InstalledAgentPlugin,
        registeredTools: List<ToolDefinition>,
        availableTools: List<ToolDefinition>,
        allowedToolNames: Set<String>,
    ): AgentPluginCapabilityProjection {
        val declared = plugin.manifest.capabilities.toSet()
        val registeredSafeNames = registeredTools
            .asSequence()
            .filter { it.risk == ToolRisk.SAFE }
            .map(ToolDefinition::name)
            .toSet()
        val availableSafeNames = availableTools
            .asSequence()
            .filter { it.risk == ToolRisk.SAFE }
            .map(ToolDefinition::name)
            .toSet()
        val readOnlyPermission = AgentPluginPermission.READ_ONLY_CAPABILITIES in plugin.manifest.permissions
        val validManifest = runCatching { AgentPluginPolicy.validate(plugin.manifest) }.isSuccess

        // long: 投影调用者即使绕开持久 Registry 构造对象，旧 API 或外部代码模式也不能获得只读能力面。
        val effective = if (plugin.enabled && readOnlyPermission && validManifest) {
            declared.asSequence()
                .filter { it in AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES }
                .filter { it in allowedToolNames }
                .filter { it in registeredSafeNames && it in availableSafeNames }
                .sorted()
                .toCollection(linkedSetOf())
        } else {
            linkedSetOf()
        }

        return AgentPluginCapabilityProjection(
            pluginId = plugin.manifest.id,
            enabled = plugin.enabled,
            readOnlyPermissionDeclared = readOnlyPermission,
            declaredCapabilities = declared,
            effectiveReadOnlyCapabilities = effective,
            excludedCapabilities = declared - effective,
        )
    }

    /**
     * 批量投影已安装插件，保留停用插件和空交集，避免调用方把“没有结果”误读成“没有声明”。
     */
    fun projectAll(
        plugins: Collection<InstalledAgentPlugin>,
        registry: ToolRegistry,
        allowedToolNames: Set<String>,
    ): List<AgentPluginCapabilityProjection> = plugins
        .map { plugin -> project(plugin, registry, allowedToolNames) }
        .sortedBy(AgentPluginCapabilityProjection::pluginId)

    fun projectAll(
        plugins: Collection<InstalledAgentPlugin>,
        registeredTools: List<ToolDefinition>,
        availableTools: List<ToolDefinition>,
        allowedToolNames: Set<String>,
    ): List<AgentPluginCapabilityProjection> = plugins
        .map { plugin -> project(plugin, registeredTools, availableTools, allowedToolNames) }
        .sortedBy(AgentPluginCapabilityProjection::pluginId)
}
