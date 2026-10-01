package com.longdev.xiaoling.agent

import android.content.Context
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.model.MessageAttachmentSelection
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider
import com.longdev.xiaoling.notification.AndroidNotificationReader
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentSkillStore
import com.longdev.xiaoling.storage.RoomAgentTaskStore
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import kotlinx.coroutines.CancellationException

class AgentRunUseCase(
    context: Context,
    private val client: OpenAiCompatibleClient,
) {
    private val appContext = context.applicationContext
    private val mcpServerStore = AndroidMcpServerStore(appContext)
    private val mcpClient = StreamableHttpMcpClient()
    // long: 无 Profile 审计的历史 Run 只能继续使用知识工具上线前的集合，避免恢复时因新注册工具扩大旧能力面。
    private val legacyRunToolNames = LEGACY_RUN_TOOL_NAMES
    private val baseLedger = RoomAgentRunRepository(context)
    private val permissionChecker = AndroidToolPermissionChecker(context)
    private val toolRegistry = XiaoLingToolRegistry(
        clock = SystemAgentClock(),
        conversationStore = RoomAgentConversationStore(context.applicationContext),
        taskStore = RoomAgentTaskStore(context.applicationContext),
        noteStore = RoomAgentNoteStore(context.applicationContext),
        memoryStore = RoomAgentMemoryStore(context.applicationContext),
        knowledgeStore = RoomKnowledgeDocumentStore(context.applicationContext),
        calendarEventReader = AndroidCalendarEventReader(context.applicationContext.contentResolver),
        calendarEventWriter = AndroidCalendarEventWriter(
            contentResolver = context.applicationContext.contentResolver,
            packageName = context.applicationContext.packageName,
        ),
        contactReader = AndroidContactReader(context.applicationContext.contentResolver),
        contactDialer = AndroidContactDialer(context.applicationContext),
        appInfoReader = AndroidAppInfoReader(context.applicationContext),
        installedAppDirectoryReader = AndroidInstalledAppDirectoryReader(context.applicationContext),
        batteryStatusReader = AndroidBatteryStatusReader(context.applicationContext),
        connectivityStatusReader = AndroidConnectivityStatusReader(context.applicationContext),
        storageStatusReader = AndroidStorageStatusReader(context.applicationContext),
        notificationReader = AndroidNotificationReader(context.applicationContext),
        deviceController = DeviceObservationComponents.controller(context.applicationContext),
        browserPageReader = OkHttpBrowserPageReader(),
        workspaceSandbox = AndroidWorkspaceSandbox(
            context.applicationContext.filesDir.resolve("agent-workspace"),
        ),
        mcpServerStore = mcpServerStore,
        mcpClient = mcpClient,
    )
    private val skillCatalog = AgentSkillCatalog(
        store = RoomAgentSkillStore(context.applicationContext),
        registeredTools = toolRegistry::registeredTools,
    )

    suspend fun run(
        conversationId: String,
        userMessageId: String,
        goal: String,
        skillSelectionGoal: String = goal,
        requiredSkillIds: Set<String> = emptySet(),
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        agentProfile: AgentProfileSnapshot,
        retryOfRunId: String? = null,
        memoryRecallEnabled: Boolean = true,
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
        workflowDeviceActionContext: WorkflowDeviceActionRunContext? = null,
        userAttachments: MessageAttachmentSelection = MessageAttachmentSelection(),
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        AgentProfilePolicy.validateRunnable(agentProfile)
        require(config.model == agentProfile.model) { "Agent Profile 模型快照与请求配置不一致" }
        require(config.providerId == agentProfile.providerId) { "Agent Profile 提供方快照与请求配置不一致" }
        val invocationContext = AgentToolExecutionContext(
            conversationId = conversationId,
            userMessageId = userMessageId,
            runId = "planning-$userMessageId",
            goal = goal,
            memoryRecallEnabled = memoryRecallEnabled,
            executionOrigin = executionOrigin,
            invocationSource = invocationSource,
            workflowDeviceActionContext = workflowDeviceActionContext,
        )
        val runToolRegistry = toolRegistryFor(agentProfile.providerId, config)
        // long: 同一个 UseCase 会跨多个 Agent/Workflow Run 复用生产 Registry；先绑定当前 planning context，避免 Profile 初始化读取上一条 Workflow 的短期限制。
        (runToolRegistry as? AgentRunContextAwareToolRegistry)?.bindRunContext(invocationContext)
        val availableToolNames = runToolRegistry.availableToolsFor(
            context = invocationContext,
            enforceWorkflowSnapshotPrerequisite = false,
        )
            .mapTo(linkedSetOf(), ToolDefinition::name)
        val scopedToolNames = agentProfile.allowedToolNames
            .filterTo(linkedSetOf(), availableToolNames::contains)
        require(scopedToolNames.isNotEmpty()) { "当前执行上下文没有可用的 Profile 工具" }
        // long: 任务重试等直接 Agent 专属工具在 Workflow context 会从 Registry 隐藏；先裁剪冻结白名单，再创建 scoped registry，避免把“当前不可见”误判为 Profile 配置损坏。
        // long: Profile 白名单需要保留完整静态能力，Workflow 的步骤门禁会在动态工具面隐藏暂不可用动作；若只传 scopedToolNames，会把隐藏动作错误地变成 Skill 未注册。
        val profileToolRegistry = ProfileScopedToolRegistry(runToolRegistry, agentProfile.allowedToolNames)
        val skillSelectionToolNames = if (
            invocationSource == AgentInvocationSource.WORKFLOW &&
            executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            workflowDeviceActionContext != null
        ) {
            // long: 前台 Workflow 的 Skill 先按用户冻结的 Profile 能力分类；瞬时 Accessibility 健康只影响实际可见工具，不能让 device-control 消失后退化成全工具面。
            agentProfile.allowedToolNames.toSet()
        } else {
            agentProfile.allowedToolNames.filterTo(linkedSetOf(), availableToolNames::contains)
        }
        val selectedSkills = if (requiredSkillIds.isNotEmpty()) {
            // long: Workflow 的目标应用步骤已经由规划器确定能力类型，必须使用显式 Skill，避免“包名”等普通词把设备动作误路由到 app-info。
            skillCatalog.selectByIds(
                skillIds = requiredSkillIds,
                allowedSkillIds = agentProfile.allowedSkillIds.toSet(),
                allowedToolNames = skillSelectionToolNames,
            )
        } else {
            skillCatalog.select(
                goal = skillSelectionGoal,
                allowedSkillIds = agentProfile.allowedSkillIds.toSet(),
                allowedToolNames = skillSelectionToolNames,
            )
        }
        val scopedToolRegistry = SkillScopedToolRegistry(profileToolRegistry, selectedSkills)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = scopedToolRegistry,
            llm = OpenAiAgentLlm(
                client = client,
                config = config,
                summarySystemPrompt = summarySystemPrompt,
                selectedSkills = selectedSkills,
                agentProfile = agentProfile,
                userAttachments = userAttachments,
            ),
            approvalGate = approvalGate,
            permissionChecker = permissionChecker,
            // long: 直接 Agent 的设备动作闭环需要“打开/观察/动作/观察/收尾观察”等多个事实；只对 Profile 明确授权设备动作的前台直接 Run 提高上限，通用 Agent 与 Workflow 仍保持默认有界预算。
            options = runtimeOptionsFor(agentProfile, invocationSource),
        )
        return runtime.run(
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
            retryOfRunId = retryOfRunId,
            executionOrigin = executionOrigin,
            invocationSource = invocationSource,
            memoryRecallEnabled = memoryRecallEnabled,
            selectedSkills = selectedSkills,
            agentProfile = agentProfile,
            workflowDeviceActionContext = workflowDeviceActionContext,
        )
    }

    suspend fun runReadOnlyChildAgents(
        parentRunId: String,
        conversationId: String,
        userMessageId: String,
        goals: List<String>,
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        agentProfile: AgentProfileSnapshot,
    ): List<ReadOnlyChildAgentResult> {
        val allowedChildTools = agentProfile.allowedToolNames
            .filter { it in READ_ONLY_CHILD_AGENT_TOOL_NAMES }
            .filter { toolRegistry.registeredDefinition(it)?.risk == ToolRisk.SAFE }
            .distinct()
        require(allowedChildTools.isNotEmpty()) {
            "当前 Agent Profile 没有可供只读子 Agent 使用的 SAFE 工具"
        }
        val childProfile = agentProfile.copy(
            allowedToolNames = allowedChildTools,
            // long: 子 Agent 不继承 Skill 选择和长期记忆写入面，只读取父 Profile 已授权的基础事实工具。
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
        )
        val coordinator = MultiAgentCoordinator()
        return coordinator.run(
            parent = MultiAgentParentContext(
                parentRunId = parentRunId,
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
            children = goals.mapIndexed { index, goal ->
                ReadOnlyChildAgentSpec(id = "child-${index + 1}", goal = goal)
            },
        ) { child ->
            val childUseCase = AgentRunUseCase(appContext, client)
            var childRunId: String? = null
            var childLinkRecorded = false
            try {
                val summary = childUseCase.run(
                    conversationId = conversationId,
                    userMessageId = "$userMessageId.${child.id}",
                    goal = child.goal,
                    skillSelectionGoal = child.goal,
                    config = config,
                    summarySystemPrompt = summarySystemPrompt,
                    agentProfile = childProfile,
                    memoryRecallEnabled = false,
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.DIRECT,
                    approvalGate = AutoApprovalGate(),
                    onSnapshot = { snapshot ->
                        childRunId = snapshot.run.id
                        if (!childLinkRecorded) {
                            childLinkRecorded = true
                            // long: 关联事件必须写在子 Run 进入终态前；Room 对终态 Run 拒绝迟到事件，避免父子关系出现半条审计链。
                            baseLedger.appendEvent(
                                runId = snapshot.run.id,
                                type = "multi_agent.child.linked",
                                message = "只读子 Agent 已关联父 Run",
                                metadata = RunEventMetadata.Reason(parentRunId),
                            )
                            baseLedger.appendEvent(
                                runId = parentRunId,
                                type = "multi_agent.child.started",
                                message = "已启动只读子 Agent：${child.id}",
                                metadata = RunEventMetadata.Reason(snapshot.run.id),
                            )
                        }
                    },
                )
                baseLedger.appendEvent(
                    runId = parentRunId,
                    type = "multi_agent.child.completed",
                    message = "只读子 Agent 已完成：${child.id}",
                    metadata = RunEventMetadata.Reason(childRunId ?: summary.runId),
                )
                ReadOnlyChildAgentResult(
                    childId = child.id,
                    childRunId = childRunId ?: summary.runId,
                    status = summary.status,
                    responseText = summary.responseText,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                baseLedger.appendEvent(
                    runId = parentRunId,
                    type = "multi_agent.child.failed",
                    message = "只读子 Agent 失败：${child.id}",
                    metadata = RunEventMetadata.Reason(error.message ?: "子 Agent 执行失败"),
                )
                ReadOnlyChildAgentResult(
                    childId = child.id,
                    childRunId = childRunId,
                    status = AgentRunStatus.FAILED,
                    responseText = null,
                    errorMessage = error.message ?: "子 Agent 执行失败",
                )
            }
        }
    }

    suspend fun runControlledReplay(
        conversationId: String,
        userMessageId: String,
        goal: String,
        sourceRunId: String,
        expectedQualification: AgentNotCommittedReplayQualification,
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        agentProfile: AgentProfileSnapshot,
        memoryRecallEnabled: Boolean = true,
        userAttachments: MessageAttachmentSelection = MessageAttachmentSelection(),
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        AgentProfilePolicy.validateRunnable(agentProfile)
        require(config.model == agentProfile.model) { "来源 Agent Profile 模型与受控重放请求不一致" }
        require(config.providerId == agentProfile.providerId) { "来源 Agent Profile 提供方与受控重放请求不一致" }
        val latestSource = baseLedger.runDetail(sourceRunId)
            ?: error("来源 Agent Run 已不存在：$sourceRunId")
        val persistedProfile = when (val audit = latestSource.inspectAgentProfileAudit()) {
            AgentProfileAuditAssessment.Legacy -> error("来源 Agent Run 缺少 Agent Profile 快照")
            is AgentProfileAuditAssessment.Available -> audit.profile
            is AgentProfileAuditAssessment.Invalid -> error(audit.reason)
        }
        require(persistedProfile == agentProfile) { "来源 Agent Profile 快照已变化，请重新发起重试" }
        val currentQualification = when (val assessment = requalifyNotCommittedReplay(latestSource)) {
            is AgentNotCommittedReplayQualificationAssessment.Eligible -> assessment.qualification
            is AgentNotCommittedReplayQualificationAssessment.Ineligible -> {
                error("受控重放资格已失效：${assessment.reason}")
            }
        }
        require(currentQualification == expectedQualification) { "受控重放 ToolCall 或恢复契约已变化" }

        val runToolRegistry = toolRegistryFor(agentProfile.providerId, config)
        val profileToolRegistry = ProfileScopedToolRegistry(runToolRegistry, agentProfile.allowedToolNames)
        val ledger = ReportingAgentRunLedger(delegate = baseLedger, onSnapshot = onSnapshot)
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = profileToolRegistry,
            llm = OpenAiAgentLlm(
                client = client,
                config = config,
                summarySystemPrompt = summarySystemPrompt,
                selectedSkills = emptyList(),
                agentProfile = agentProfile,
                userAttachments = userAttachments,
            ),
            approvalGate = approvalGate,
            permissionChecker = permissionChecker,
            options = runtimeOptionsFor(agentProfile, AgentInvocationSource.DIRECT),
        )
        // long: UseCase 在创建新 Run 前重新读取 Room 并复核生产 Registry；任务中心确认只授权进入这条路径，工具本身仍由 Runtime 创建新审批。
        return runtime.runControlledReplay(
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
            sourceRunId = sourceRunId,
            qualification = currentQualification,
            memoryRecallEnabled = memoryRecallEnabled,
            agentProfile = agentProfile,
        )
    }

    suspend fun resumeApprovedRun(
        detail: AgentRunDetailRecord,
        approval: ApprovalRequestRecord,
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        userAttachments: MessageAttachmentSelection = MessageAttachmentSelection(),
        approvalReason: String,
        invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val selectionEvent = detail.snapshot.events.lastOrNull { it.type == "skill.selected" }
        val agentProfile = when (val assessment = detail.inspectAgentProfileAudit()) {
            AgentProfileAuditAssessment.Legacy -> null
            is AgentProfileAuditAssessment.Available -> assessment.profile
            is AgentProfileAuditAssessment.Invalid -> error(assessment.reason)
        }
        if (agentProfile != null) {
            require(config.model == agentProfile.model) { "原 Run 的 Agent Profile 模型与恢复请求不一致，请创建新 Run 重试" }
            require(config.providerId == agentProfile.providerId) { "原 Run 的 Agent Profile 提供方与恢复请求不一致，请创建新 Run 重试" }
        }
        val selectedSkills = if (selectionEvent == null) {
            emptyList()
        } else {
            val selection = (selectionEvent.metadata as? RunEventMetadata.Reason)?.reason
                ?: error("原 Run 的 Skill 选择审计无法读取，请创建新 Run 重试")
            // long: 审批等待期间用户可以停用、升级或删除 Skill；恢复必须固定原 Run 的 ID 与版本，不能重新分类后意外扩大工具白名单。
            skillCatalog.resolveSelection(AgentSkillSelectionCodec.decode(selection))
        }
        if (agentProfile != null) {
            require(selectedSkills.all { it.id in agentProfile.allowedSkillIds }) {
                "原 Run 的 Skill 超出 Agent Profile 白名单，请创建新 Run 重试"
            }
            require(selectedSkills.flatMap { it.toolNames }.all { it in agentProfile.allowedToolNames }) {
                "原 Run 的 Skill 工具超出 Agent Profile 白名单，请创建新 Run 重试"
            }
        }
        val runToolRegistry = agentProfile
            ?.let { toolRegistryFor(it.providerId, config) }
            ?: toolRegistry
        val profileToolRegistry = agentProfile
            ?.let { ProfileScopedToolRegistry(runToolRegistry, it.allowedToolNames) }
            ?: legacyRunToolRegistry(toolRegistry)
        val scopedToolRegistry = SkillScopedToolRegistry(profileToolRegistry, selectedSkills)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        // long: 批准决定先写入 Room，再进入同一 Run 的执行入口；应用崩溃时至少能区分“已批准但尚未执行”和“执行中断”。
        baseLedger.decideApprovalRequest(
            requestId = approval.id,
            status = ApprovalRequestStatus.APPROVED,
            reason = approvalReason,
        ) ?: error("审批请求不存在：${approval.id}")
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = scopedToolRegistry,
            llm = OpenAiAgentLlm(
                client = client,
                config = config,
                summarySystemPrompt = summarySystemPrompt,
                selectedSkills = selectedSkills,
                agentProfile = agentProfile,
                userAttachments = userAttachments,
            ),
            approvalGate = approvalGate,
            permissionChecker = permissionChecker,
            options = agentProfile?.let { runtimeOptionsFor(it, invocationSource) } ?: AgentRuntimeOptions(),
        )
        return runtime.resumeApprovedRun(
            detail = detail,
            approval = approval,
            approvalDecision = ApprovalDecision(approved = true, reason = approvalReason),
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = invocationSource,
        )
    }

    private fun toolRegistryFor(providerId: String, config: ProviderRequestConfig): XiaoLingToolRegistry {
        val provider = config.embeddingModel?.takeIf(String::isNotBlank)?.let {
            OpenAiKnowledgeEmbeddingProvider(providerId, config, client)
        } ?: return toolRegistry
        return toolRegistry.withKnowledgeStore(
            RoomKnowledgeDocumentStore(
                context = appContext,
                embeddingProvider = provider,
            ),
        )
    }

    private fun runtimeOptionsFor(
        profile: AgentProfileSnapshot,
        invocationSource: AgentInvocationSource,
    ): AgentRuntimeOptions {
        val directDeviceRun = invocationSource == AgentInvocationSource.DIRECT &&
            profile.allowedToolNames.any { it in DIRECT_DEVICE_ACTION_TOOL_NAMES }
        return if (directDeviceRun) {
            AgentRuntimeOptions(maxToolCalls = DIRECT_DEVICE_MAX_TOOL_CALLS)
        } else {
            AgentRuntimeOptions()
        }
    }

    suspend fun recoverCommittedToolRuns(runIds: Set<String>? = null): List<AgentRunDetailRecord> {
        return baseLedger.recoverCommittedToolRuns(
            toolRegistry::definition,
            toolRegistry::supportsCommittedEffectVerification,
            runIds,
        )
    }

    suspend fun recoverVerifiedToolRuns(runIds: Set<String>? = null): List<AgentRunDetailRecord> {
        return baseLedger.recoverVerifiedToolRuns(runIds)
    }

    suspend fun closeInterruptedRuns(runIds: Set<String>? = null): Int {
        return baseLedger.closeInterruptedRuns(
            toolRegistry::definition,
            toolRegistry::supportsCommittedEffectVerification,
            runIds,
        )
    }

    fun requalifyNotCommittedReplay(
        detail: AgentRunDetailRecord,
    ): AgentNotCommittedReplayQualificationAssessment {
        val sourceProfile = when (val assessment = detail.inspectAgentProfileAudit()) {
            AgentProfileAuditAssessment.Legacy -> null
            is AgentProfileAuditAssessment.Available -> assessment.profile
            is AgentProfileAuditAssessment.Invalid -> {
                return AgentNotCommittedReplayQualificationAssessment.Ineligible(assessment.reason)
            }
        }
        // long: 任务中心每次请求与确认都用生产 Registry 重新核对来源 Profile 和冻结定义，不能只相信启动时写入的资格码。
        return AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = detail,
            agentProfile = sourceProfile,
            definitionLookup = toolRegistry::definition,
        )
    }

    suspend fun closeInterruptedRunForWorkerReentry(runId: String): Boolean {
        // long: Worker 重入不能恢复旧协程或后台 Workflow 后缀；即使该 Run 具备前台只读恢复证据，也先冻结证据并关闭旧实例，由用户创建关联新 Run。
        return baseLedger.closeInterruptedRuns(
            definitionLookup = toolRegistry::definition,
            committedVerificationSupport = toolRegistry::supportsCommittedEffectVerification,
            runIds = setOf(runId),
            preserveResumableCandidates = false,
        ) > 0
    }

    suspend fun cancelActiveRunForScheduledTaskStop(runId: String): Boolean {
        // long: 用户停止后台任务只允许按 ScheduledTask 关联到的 Run ID 定向取消，不能扫描并影响同时运行的前台 Agent。
        return baseLedger.cancelActiveRun(runId, "用户停止后台工作流")
    }

    suspend fun resumeCommittedToolRun(
        detail: AgentRunDetailRecord,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val scopedToolRegistry = when (val assessment = detail.inspectAgentProfileAudit()) {
            AgentProfileAuditAssessment.Legacy -> legacyRunToolRegistry(toolRegistry)
            is AgentProfileAuditAssessment.Available -> ProfileScopedToolRegistry(toolRegistry, assessment.profile.allowedToolNames)
            is AgentProfileAuditAssessment.Invalid -> error(assessment.reason)
        }
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = scopedToolRegistry,
            llm = RecoveryOnlyAgentLlm,
            permissionChecker = permissionChecker,
        )
        return runtime.resumeCommittedToolRun(detail)
    }

    suspend fun resumeVerifiedToolRun(
        detail: AgentRunDetailRecord,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = RecoveryOnlyAgentLlm,
            permissionChecker = permissionChecker,
        )
        return runtime.resumeVerifiedToolRun(detail)
    }

    suspend fun listSkills(): List<AgentSkillRecord> = skillCatalog.list()

    fun registeredTools(): List<ToolDefinition> = toolRegistry.registeredTools()

    fun toolCatalog(): AgentToolCatalog = toolRegistry.toolCatalog()

    fun registeredToolCatalog(): AgentToolCatalog = toolRegistry.registeredToolCatalog()

    suspend fun importSkill(raw: String): AgentSkillRecord = skillCatalog.importDocument(raw)

    suspend fun importSkillFromGitHub(url: String): AgentSkillRecord = skillCatalog.importGitHub(url)

    suspend fun discoverGitHubSkills(url: String): List<GitHubSkillCandidate> = skillCatalog.discoverGitHub(url)

    suspend fun setSkillEnabled(skillId: String, enabled: Boolean): AgentSkillRecord? {
        return skillCatalog.setEnabled(skillId, enabled)
    }

    suspend fun deleteLocalSkill(skillId: String): Boolean = skillCatalog.deleteLocal(skillId)

    suspend fun listMcpServers(): List<McpServerConfig> = mcpServerStore.list()

    suspend fun upsertMcpServer(config: McpServerConfig): McpServerConfig {
        // long: 编辑 Server 时空 Token 表示保留已有密文，避免页面回显凭据，也避免用户只改名称或开关时意外清空认证。
        val existing = mcpServerStore.get(config.id)
        return mcpServerStore.upsert(
            config.copy(bearerToken = config.bearerToken.ifBlank { existing?.bearerToken.orEmpty() }),
        )
    }

    suspend fun discoverMcpTools(serverId: String): List<McpToolDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        return mcpClient.listTools(server)
    }

    suspend fun listMcpResources(serverId: String): List<McpResourceDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return mcpClient.listResources(server)
    }

    suspend fun readMcpResource(serverId: String, uri: String): List<McpResourceContent> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return mcpClient.readResource(server, uri)
    }

    suspend fun listMcpPrompts(serverId: String): List<McpPromptDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return mcpClient.listPrompts(server)
    }

    suspend fun getMcpPrompt(
        serverId: String,
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): McpPromptResult {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return mcpClient.getPrompt(server, name, arguments)
    }

    suspend fun setMcpToolEnabled(
        serverId: String,
        toolName: String,
        enabled: Boolean,
        discoveredTools: List<McpToolDescriptor>,
    ): McpServerConfig {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        val discoveredNames = discoveredTools.mapTo(linkedSetOf(), McpToolDescriptor::name)
        val nextNames = McpToolAccessPolicy.updatedEnabledToolNames(
            server = server,
            discoveredToolNames = discoveredNames,
            toolName = toolName,
            enabled = enabled,
        )
        return upsertMcpServer(server.copy(enabledToolNames = nextNames))
    }

    suspend fun deleteMcpServer(id: String): Boolean = mcpServerStore.delete(id)
}

internal val LEGACY_RUN_TOOL_NAMES = setOf(
    "app.current_time",
    "app.list_conversations",
    "app.search_conversations",
    "notes.list",
    "notes.search",
    "notes.get",
    "notes.create",
    "memory.search",
    "memory.remember",
)

private const val DIRECT_DEVICE_MAX_TOOL_CALLS = 8
private val READ_ONLY_CHILD_AGENT_TOOL_NAMES = setOf(
    "app.current_time",
    "app.get_info",
    "app.get_battery",
    "app.get_connectivity",
    "app.get_storage",
    "app.list_conversations",
    "app.search_conversations",
    "notes.list",
    "notes.search",
    "notes.get",
    "memory.search",
    "memory.get",
    "knowledge.search",
)
private val DIRECT_DEVICE_ACTION_TOOL_NAMES = setOf(
    "device.open_app",
    "device.back",
    "device.home",
    "device.tap_ref",
    "device.type_text",
    "device.swipe",
)

internal fun legacyRunToolRegistry(delegate: ToolRegistry): ToolRegistry =
    ProfileScopedToolRegistry(delegate, LEGACY_RUN_TOOL_NAMES)

private object RecoveryOnlyAgentLlm : AgentLlm {
    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
        error("验证阶段恢复不允许重建旧规划请求")
    }

    override suspend fun summarize(
        goal: String,
        toolCall: ToolCall,
        toolResult: ToolExecutionResult,
    ): String = error("验证阶段恢复固定使用本地可信总结")
}

private class ReportingAgentRunLedger(
    private val delegate: AgentRunLedger,
    private val onSnapshot: suspend (AgentRunSnapshot) -> Unit,
) : AgentRunLedger {
    private val stepRunIds = mutableMapOf<String, String>()

    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
    ): AgentRunRecord {
        val run = delegate.createRun(conversationId, userMessageId, goal, retryOfRunId)
        emit(run.id)
        return run
    }

    override suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String?, errorMessage: String?) {
        delegate.updateRunStatus(runId, status, result, errorMessage)
        emit(runId)
    }

    override suspend fun appendStep(
        runId: String,
        type: String,
        title: String,
        detail: String,
        status: AgentStepStatus,
    ): AgentStepRecord {
        val step = delegate.appendStep(runId, type, title, detail, status)
        stepRunIds[step.id] = runId
        emit(runId)
        return step
    }

    override suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String?) {
        delegate.updateStep(stepId, status, detail)
        stepRunIds[stepId]?.let { emit(it) }
    }

    override suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata?) {
        delegate.appendEvent(runId, type, message, metadata)
        emit(runId)
    }

    override suspend fun snapshot(runId: String): AgentRunSnapshot = delegate.snapshot(runId)

    private suspend fun emit(runId: String) {
        // long: 运行时间线依赖 Room 里的真实审计记录，而不是 UI 自己猜状态；每次落库后回读快照，保证界面展示和可追溯数据一致。
        onSnapshot(delegate.snapshot(runId))
    }
}
