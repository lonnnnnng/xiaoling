package com.longdev.xiaoling.agent

import android.content.Context
import com.longdev.xiaoling.XiaoLingApplication
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart

class AgentRunUseCase(
    context: Context,
    private val client: OpenAiCompatibleClient,
    mcpSessionRegistry: McpSessionRegistry? = null,
    private val baseLedger: RoomAgentRunRepository = RoomAgentRunRepository(context),
    pluginRegistry: AgentPluginRegistry? = null,
    pluginForeground: AgentPluginForegroundState? = null,
    skillStore: AgentSkillStore? = null,
) {
    private val appContext = context.applicationContext
    private val plugins = pluginRegistry ?: (appContext as? XiaoLingApplication)?.agentPluginRegistry ?: AgentPluginRegistry()
    private val pluginForegroundState = pluginForeground ?: (appContext as? XiaoLingApplication)?.agentPluginForeground
        ?: AgentPluginForegroundState()
    // long: 验收可注入独立配置账本，生产未传入时仍共用 Application 注册表，不让测试改动用户的 MCP 配置。
    private val mcpSessions = mcpSessionRegistry ?: (appContext as? XiaoLingApplication)?.mcpSessionRegistry
        ?: McpSessionRegistry(AndroidMcpServerStore(appContext))
    private val mcpServerStore = mcpSessions.store
    private val mcpClient = mcpSessions.client("run-${java.util.UUID.randomUUID()}")
    private val settingsMcpClient = mcpSessions.client("settings-${java.util.UUID.randomUUID()}")
    // long: 无 Profile 审计的历史 Run 只能继续使用知识工具上线前的集合，避免恢复时因新注册工具扩大旧能力面。
    private val legacyRunToolNames = LEGACY_RUN_TOOL_NAMES
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
    private val sharedSkillStore = skillStore ?: RoomAgentSkillStore(context.applicationContext)
    private val skillCatalog = AgentSkillCatalog(
        // long: 验收可隔离 Skill 同步写入；生产仍使用既有 Room store。
        store = sharedSkillStore,
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
        parentRunId: String? = null,
        memoryRecallEnabled: Boolean = true,
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
        workflowDeviceActionContext: WorkflowDeviceActionRunContext? = null,
        userAttachments: MessageAttachmentSelection = MessageAttachmentSelection(),
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
        pluginSelection: AgentPluginExecutionSelection? = null,
        externalInput: Boolean = false,
        childRunId: String? = null,
    ): AgentRunSummary {
        AgentProfilePolicy.validateRunnable(agentProfile)
        require(config.model == agentProfile.model) { "Agent Profile 模型快照与请求配置不一致" }
        require(config.providerId == agentProfile.providerId) { "Agent Profile 提供方快照与请求配置不一致" }
        require(parentRunId == null || childRunId != null) { "child 必须使用已持久登记的槽位" }
        retryOfRunId?.let { source -> baseLedger.runDetail(source)?.let { detail ->
            AgentPluginExecutionPolicy.requireFreshSelection(detail)
            AgentChildLifecyclePolicy.requireFreshInvocation(detail)
        } }
        val childCheckpoint = childRunId?.let { id ->
            require(config.apiMode == agentProfile.apiMode) { "child 请求协议与冻结 Profile 不一致" }
            require(pluginSelection == null && retryOfRunId == null && !externalInput && !memoryRecallEnabled &&
                executionOrigin == AgentExecutionOrigin.FOREGROUND && invocationSource == AgentInvocationSource.DIRECT &&
                userAttachments.image == null && userAttachments.document == null)
            val checkpoint = baseLedger.readOnlyChildCheckpoint(id)
            AgentChildLifecyclePolicy.requireCurrentOwner(checkpoint)
            val frozen = baseLedger.snapshot(id).run
            require(agentProfile.allowedToolNames.all { name -> toolRegistry.registeredDefinition(name)?.let {
                it.risk == ToolRisk.SAFE && it.approvalPolicy == ToolApprovalPolicy.NONE
            } == true }) { "child 工具不再属于 SAFE/NONE" }
            require(checkpoint.parentRunId == parentRunId && checkpoint.profile == agentProfile &&
                frozen.goal == goal && frozen.conversationId == conversationId && frozen.userMessageId == userMessageId) {
                "child 请求与冻结槽位不一致"
            }
            checkpoint
        }
        require(pluginSelection == null || retryOfRunId == null) { "插件任务必须显式创建新 Run" }
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
        // long: 插件只读范围不隐式启用知识 Embedding 网络请求；模型规划仍使用用户当前 Profile 配置。
        val runToolRegistry = if (pluginSelection != null || childCheckpoint != null) toolRegistry else toolRegistryFor(agentProfile.providerId, config)
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
        val pluginToolRegistry = pluginSelection?.let { selection ->
            require(userAttachments.image == null && userAttachments.document == null) { "插件任务暂不接受附件" }
            AgentPluginToolRegistry(profileToolRegistry, plugins, pluginForegroundState, selection,
                agentProfile.allowedToolNames.toSet(), executionOrigin, invocationSource, externalInput, parentRunId)
        }
        val skillSelectionToolNames = if (
            invocationSource == AgentInvocationSource.WORKFLOW &&
            executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            workflowDeviceActionContext != null
        ) {
            // long: 前台 Workflow 的 Skill 先按用户冻结的 Profile 能力分类；瞬时 Accessibility 健康只影响实际可见工具，不能让 device-control 消失后退化成全工具面。
            agentProfile.allowedToolNames.toSet()
        } else {
            pluginToolRegistry?.registeredTools()?.mapTo(linkedSetOf(), ToolDefinition::name)
                ?: agentProfile.allowedToolNames.filterTo(linkedSetOf(), availableToolNames::contains)
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
        val scopedToolRegistry = SkillScopedToolRegistry(pluginToolRegistry ?: profileToolRegistry, selectedSkills)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
            pluginEvidence = pluginToolRegistry?.evidenceJson(),
            invocationEvidence = AgentChildLifecyclePolicy.invocation(executionOrigin, invocationSource, externalInput),
            childRunId = childRunId,
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
            options = childCheckpoint?.options ?: if (pluginToolRegistry != null) AgentRuntimeOptions(maxToolCalls = 3, runTimeoutMs = 60_000,
                toolStepTimeoutMs = AgentPluginExecutionPolicy.MAX_TOOL_TIMEOUT_MS) else runtimeOptionsFor(agentProfile, invocationSource),
        )
        suspend fun executeRun() = runtime.run(
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
            parentRunId = parentRunId,
        )
        return when {
            pluginToolRegistry != null -> pluginToolRegistry.guardRun { executeRun() }
            childCheckpoint != null -> guardChildParent(childCheckpoint.parentRunId) { executeRun() }
            else -> executeRun()
        }
    }

    private suspend fun <T> guardChildParent(parentRunId: String, block: suspend () -> T): T = coroutineScope {
        val runJob = currentCoroutineContext().job
        // long: 直接使用已登记槽位的执行入口也要监听父取消，不能只靠外层批次协程关闭正在等待模型的 socket。
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val parent = baseLedger.snapshot(parentRunId).run
                if (parent.status.isTerminal || parent.cancelRequestedAt != null) {
                    runJob.cancel(CancellationException("父 Run 已结束或请求取消"))
                    return@launch
                }
                delay(100)
            }
        }
        try { block() } finally { watcher.cancel() }
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
            .filter { it in AgentChildLifecyclePolicy.toolNames }
            .filter { toolRegistry.registeredDefinition(it)?.risk == ToolRisk.SAFE }
            .filter { toolRegistry.registeredDefinition(it)?.approvalPolicy == ToolApprovalPolicy.NONE }
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
        val childSpecs = goals.mapIndexed { index, goal ->
            ReadOnlyChildAgentSpec(id = "child-${index + 1}", goal = goal)
        }
        MultiAgentPolicy.validateChildren(childSpecs)
        require(config.providerId == agentProfile.providerId && config.model == agentProfile.model && config.apiMode == agentProfile.apiMode)
        var checkpoint: AgentChildCheckpoint? = null
        try {
            // long: 即使调用方在提交瞬间取消，也先拿到已提交槽位，再由 finally 关闭未开始的 child。
            checkpoint = withContext(NonCancellable) {
                baseLedger.prepareReadOnlyChildRuns(parentRunId, conversationId, userMessageId, childSpecs, agentProfile, childProfile)
            }
            val frozen = requireNotNull(checkpoint)
            return coroutineScope {
                val batchJob = currentCoroutineContext().job
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    while (true) {
                        val parent = baseLedger.snapshot(parentRunId).run
                        if (parent.status.isTerminal || parent.cancelRequestedAt != null) {
                            batchJob.cancel(CancellationException("父 Run 已结束或请求取消"))
                            return@launch
                        }
                        delay(100)
                    }
                }
                try {
                    MultiAgentCoordinator().run(MultiAgentParentContext(parentRunId,
                        AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.DIRECT), childSpecs) { child ->
                        val slot = frozen.slots.single { it.id == child.id }
                        var childUseCase: AgentRunUseCase? = null
                        try {
                            // long: 每个 child 有独立 Registry/MCP owner，但共用同一注入账本；验收和生产都不能偷偷换到默认数据库。
                            childUseCase = AgentRunUseCase(appContext, client, mcpSessions, baseLedger,
                                plugins, pluginForegroundState, sharedSkillStore)
                            childUseCase.run(conversationId, "$userMessageId.${child.id}", child.goal,
                                config = config, summarySystemPrompt = summarySystemPrompt, agentProfile = frozen.profile,
                                memoryRecallEnabled = false, parentRunId = parentRunId, childRunId = slot.runId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            // long: Runtime 超时已有 BUDGET_EXHAUSTED 终态，不能在父返回值中重写成普通 FAILED；初始化失败才补结算。
                            withContext(NonCancellable) {
                                baseLedger.updateRunStatus(slot.runId, AgentRunStatus.FAILED, errorMessage = "child 初始化或执行失败")
                            }
                        } finally {
                            childUseCase?.closeMcpOwners()
                        }
                        val run = baseLedger.snapshot(slot.runId).run
                        ReadOnlyChildAgentResult(child.id, slot.runId, run.status,
                            run.result?.take(AgentChildLifecyclePolicy.MAX_RESULT_CHARS), run.errorMessage?.take(500))
                    }
                } finally { watcher.cancel() }
            }
        } finally {
            // long: 父协程取消、构造失败或返回中断均关闭所有未完成槽位；成功 child 和其 Tool Ledger 保持原终态。
            withContext(NonCancellable) {
                checkpoint?.slots?.forEach { baseLedger.cancelActiveRun(it.runId, "只读 child 批次已结束") }
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
        AgentPluginExecutionPolicy.requireFreshSelection(latestSource)
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
    ): AgentRunSummary = baseLedger.withRecoveryExecution(detail.snapshot.run.id.also {
        // long: 取锁前绑定用户确认的 Run，不能拿 A 的配置/锁去批准 B，随后再把 B 的当前证据当作同一 Run 使用。
        require(approval.runId == it) { "审批请求不属于当前 Agent Run" }
    }) { freshDetail ->
        val detail = freshDetail
        AgentPluginExecutionPolicy.requireFreshSelection(detail)
        AgentChildLifecyclePolicy.requireFreshInvocation(detail)
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
        val approvedDetail = baseLedger.approveRecoveredRequest(approval, approvalReason)
            ?: error("审批或上下文已变化，不能继续原 Run")
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
        runtime.resumeApprovedRun(
            detail = approvedDetail,
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
    ): AgentRunSummary = baseLedger.withRecoveryExecution(detail.snapshot.run.id) { freshDetail ->
        val detail = freshDetail
        AgentPluginExecutionPolicy.requireFreshSelection(detail)
        AgentChildLifecyclePolicy.requireFreshInvocation(detail)
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
        runtime.resumeCommittedToolRun(detail)
    }

    suspend fun resumeVerifiedToolRun(
        detail: AgentRunDetailRecord,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary = baseLedger.withRecoveryExecution(detail.snapshot.run.id) { freshDetail ->
        val detail = freshDetail
        AgentPluginExecutionPolicy.requireFreshSelection(detail)
        AgentChildLifecyclePolicy.requireFreshInvocation(detail)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            llm = RecoveryOnlyAgentLlm,
            permissionChecker = permissionChecker,
        )
        runtime.resumeVerifiedToolRun(detail)
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
        return mcpSessions.upsertPreservingToken(config)
    }

    suspend fun setMcpServerEnabled(id: String, enabled: Boolean): McpServerConfig = mcpSessions.setEnabled(id, enabled)

    suspend fun discoverMcpTools(serverId: String): List<McpToolDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        return settingsMcpClient.listTools(server)
    }

    suspend fun listMcpResources(serverId: String): List<McpResourceDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return settingsMcpClient.listResources(server)
    }

    suspend fun readMcpResource(serverId: String, uri: String): List<McpResourceContent> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return settingsMcpClient.readResource(server, uri)
    }

    suspend fun listMcpPrompts(serverId: String): List<McpPromptDescriptor> {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return settingsMcpClient.listPrompts(server)
    }

    suspend fun getMcpPrompt(
        serverId: String,
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): McpPromptResult {
        val server = mcpServerStore.get(serverId) ?: throw IllegalArgumentException("未找到已配置的 MCP Server")
        require(server.enabled) { "MCP Server 已停用" }
        return settingsMcpClient.getPrompt(server, name, arguments)
    }

    suspend fun setMcpToolEnabled(
        serverId: String,
        toolName: String,
        enabled: Boolean,
        discoveredTools: List<McpToolDescriptor>,
    ): McpServerConfig {
        val discoveredNames = discoveredTools.mapTo(linkedSetOf(), McpToolDescriptor::name)
        return mcpSessions.updateToolAccess(serverId, discoveredNames, toolName, enabled)
    }

    suspend fun deleteMcpServer(id: String): Boolean = mcpSessions.delete(id)

    fun closeMcpOwners() { mcpClient.close(); settingsMcpClient.close() }
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
    private val pluginEvidence: String? = null,
    private val invocationEvidence: String? = null,
    private val childRunId: String? = null,
) : AgentRunLedger {
    private val stepRunIds = mutableMapOf<String, String>()

    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
    ): AgentRunRecord {
        if (pluginEvidence != null) return createPluginRecord(conversationId, userMessageId, goal)
        return createRecord(conversationId, userMessageId, goal, retryOfRunId, null)
    }

    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
        parentRunId: String?,
    ): AgentRunRecord {
        if (pluginEvidence != null) return createPluginRecord(conversationId, userMessageId, goal)
        return createRecord(conversationId, userMessageId, goal, retryOfRunId, parentRunId)
    }

    private suspend fun createRecord(conversationId: String, userMessageId: String, goal: String,
        retryOfRunId: String?, parentRunId: String?): AgentRunRecord {
        var created: AgentRunRecord? = null
        try {
            withContext(NonCancellable) {
                created = when {
                    childRunId != null -> (delegate as RoomAgentRunRepository).claimReadOnlyChildRun(childRunId)
                    invocationEvidence != null -> (delegate as RoomAgentRunRepository).createInvokedRun(
                        conversationId, userMessageId, goal, retryOfRunId, parentRunId, invocationEvidence)
                    else -> delegate.createRun(conversationId, userMessageId, goal, retryOfRunId, parentRunId)
                }
            }
            return requireNotNull(created).also { emit(it.id) }
        } catch (error: Throwable) {
            // long: Runtime 拿到 Run 之前的取消与回调失败也要结算，避免一次性槽位已领取却永久停在 QUEUED。
            created?.let { run -> withContext(NonCancellable) {
                if (error is CancellationException) (delegate as? RoomAgentRunRepository)?.cancelActiveRun(run.id, "Run 初始化取消")
                else delegate.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = "Run 初始化失败")
            } }
            throw error
        }
    }

    private suspend fun createPluginRecord(conversationId: String, userMessageId: String, goal: String): AgentRunRecord {
        var created: AgentRunRecord? = null
        try {
            withContext(NonCancellable) {
                created = (delegate as RoomAgentRunRepository).createPluginRun(conversationId, userMessageId, goal, requireNotNull(pluginEvidence))
            }
            val run = requireNotNull(created)
            emit(run.id)
            return run
        } catch (error: Throwable) {
            // long: Runtime 尚未拿到 Run 时也要结算初始化失败；否则许可撤销或 UI 回调异常会遗留 QUEUED 记录。
            created?.let { run -> withContext(NonCancellable) {
                val status = if (error is CancellationException) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED
                delegate.appendEvent(run.id, "plugin.execution.initialization_failed", "插件 Run 初始化未完成",
                    RunEventMetadata.Reason("插件 Run 初始化未完成"))
                delegate.updateRunStatus(run.id, status, errorMessage = "插件 Run 初始化未完成")
            } }
            throw error
        }
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

    override suspend fun appendContextSnapshot(runId: String, stepId: String, input: AgentContextInput) =
        delegate.appendContextSnapshot(runId, stepId, input)

    override suspend fun contextTranscript(runId: String): List<AgentContextSnapshotRecord> = delegate.contextTranscript(runId)

    override suspend fun appendCompressedContextSnapshot(runId: String, stepId: String, input: AgentContextInput,
        candidate: AgentContextCompressionCandidate) = delegate.appendCompressedContextSnapshot(runId, stepId, input, candidate)

    override suspend fun contextCompressions(runId: String): List<AgentContextCompressionRecord> = delegate.contextCompressions(runId)

    private suspend fun emit(runId: String) {
        // long: 运行时间线依赖 Room 里的真实审计记录，而不是 UI 自己猜状态；每次落库后回读快照，保证界面展示和可追溯数据一致。
        onSnapshot(delegate.snapshot(runId))
    }
}
