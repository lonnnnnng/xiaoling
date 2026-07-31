package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.WorkflowDeviceActionApprovalEvidence
import com.longdev.xiaoling.automation.WorkflowDeviceActionAuthorization
import com.longdev.xiaoling.automation.WorkflowDeviceActionCompletionEvidence
import com.longdev.xiaoling.automation.WorkflowDeviceActionExecutionEvidence
import com.longdev.xiaoling.automation.WorkflowDeviceActionIdentity
import com.longdev.xiaoling.automation.WorkflowDeviceActionObservationEvidence
import com.longdev.xiaoling.automation.WorkflowDeviceActionPostObservationEvidence
import com.longdev.xiaoling.automation.WorkflowDeviceActionResultCodec
import com.longdev.xiaoling.automation.WorkflowDeviceActionSafetyDecision
import com.longdev.xiaoling.automation.WorkflowDeviceActionSafetyPolicy
import com.longdev.xiaoling.automation.WorkflowTypeTextCompletionEvidence
import com.longdev.xiaoling.automation.WorkflowTypeTextExecutionEvidence
import com.longdev.xiaoling.automation.WorkflowTypeTextTargetEvidence
import com.longdev.xiaoling.device.DeviceActionCapture
import com.longdev.xiaoling.device.DeviceActionCodec
import com.longdev.xiaoling.device.DeviceActionFailure
import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceController
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceReferenceInspection
import com.longdev.xiaoling.device.DeviceScrollDirection
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import com.longdev.xiaoling.device.DeviceSnapshotCodec
import com.longdev.xiaoling.device.DeviceSnapshotFailure
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeReference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class XiaoLingToolRegistry(
    private val clock: AgentClock,
    private val conversationStore: AgentConversationStore,
    private val noteStore: AgentNoteStore,
    private val memoryStore: AgentMemoryStore,
    private val knowledgeStore: KnowledgeDocumentStore,
    private val deviceController: DeviceController = DisabledDeviceController,
    workflowDeviceActionToolNames: Set<String> = DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES,
) : ToolRegistry, AgentRunContextAwareToolRegistry, AgentToolExecutionLifecycleAwareToolRegistry {
    private var runContext: AgentToolExecutionContext? = null
    private var pendingWorkflowSnapshot: WorkflowSnapshotCandidate? = null
    private var verifiedWorkflowSnapshot: WorkflowSnapshotCandidate? = null
    private var pendingWorkflowAction: WorkflowActionAuthorizationState? = null
    private var executedWorkflowAction: WorkflowExecutedActionState? = null
    // long: Workflow 生产动作面只包含逐项完成安全证据的 open_app/back/home/tap_ref/type_text；其他已注册动作不能借构造注入扩大权限。
    private val workflowDeviceActionToolNames = workflowDeviceActionToolNames.toSet().also { toolNames ->
        val unsupported = toolNames - SUPPORTED_WORKFLOW_DEVICE_ACTION_TOOL_NAMES
        require(unsupported.isEmpty()) {
            "Workflow Registry 测试动作集合包含未开放工具：${unsupported.sorted().joinToString()}"
        }
    }
    private val workflowDeviceActionSafetyPolicy = WorkflowDeviceActionSafetyPolicy(
        enabledToolNames = this.workflowDeviceActionToolNames,
    )

    internal fun withKnowledgeStore(store: KnowledgeDocumentStore): XiaoLingToolRegistry = XiaoLingToolRegistry(
        clock = clock,
        conversationStore = conversationStore,
        noteStore = noteStore,
        memoryStore = memoryStore,
        knowledgeStore = store,
        deviceController = deviceController,
        workflowDeviceActionToolNames = workflowDeviceActionToolNames,
    )

    private val tools = listOf(
        ToolDefinition(
            name = "app.current_time",
            description = "读取手机本地当前时间和时区，用于需要时间上下文的任务。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.list_conversations",
            description = "列出最近的本地会话标题、消息数和更新时间，用于帮助用户回到历史对话。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "app.search_conversations",
            description = "按关键词搜索本地会话标题、摘要和消息内容，用于查找旧会话。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.list",
            description = "列出最近创建的本地笔记。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.search",
            description = "按关键词搜索本地笔记标题和正文。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "搜索关键词。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "notes.create",
            description = "创建一条本地笔记；写入前需要用户确认，写入后会回读验证。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField(
                    name = "title",
                    description = "笔记标题。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "content",
                    description = "笔记正文。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 20_000,
                ),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            notCommittedReplayPolicy = ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL,
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "memory.search",
            description = "检索本机长期记忆，帮助回答用户偏好、历史事实或长期备注。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "检索关键词；为空时返回最近记忆。",
                    required = false,
                    type = ToolInputType.STRING,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回条数，默认 5，最大 10。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 10.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "memory.remember",
            description = "把用户明确希望长期保留的偏好、事实或备注写入本机长期记忆；写入前必须经过用户审批。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            notCommittedReplayPolicy = ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL,
            inputSchema = listOf(
                ToolInputField(
                    name = "note",
                    description = "要长期保留的具体事实或偏好，必须来自用户当前明确表达的内容。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 2_000,
                ),
                ToolInputField(
                    name = "type",
                    description = "记忆类型：Preference、ProfileFact、Episode 或 Procedure。",
                    required = false,
                    type = ToolInputType.STRING,
                    enumValues = setOf("Preference", "ProfileFact", "Episode", "Procedure"),
                ),
                ToolInputField(
                    name = "tags",
                    description = "可选标签，多个标签用逗号分隔。",
                    required = false,
                    type = ToolInputType.STRING,
                    maxLength = 500,
                ),
            ),
            businessValidators = listOf(
                ToolBusinessValidator { arguments ->
                    val tags = arguments["tags"].orEmpty()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    when {
                        tags.size > 10 -> listOf("长期记忆标签不能超过 10 个")
                        tags.any { it.length > 50 } -> listOf("长期记忆单个标签不能超过 50 个字符")
                        else -> emptyList()
                    }
                },
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = "knowledge.search",
            description = "检索用户已导入并启用的本地知识文档，返回正文片段及可审计的文档版本和 chunk 引用。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = true),
            inputSchema = listOf(
                ToolInputField(
                    name = "query",
                    description = "知识库检索关键词。",
                    required = true,
                    type = ToolInputType.STRING,
                    minLength = 1,
                    maxLength = 200,
                ),
                ToolInputField(
                    name = "limit",
                    description = "返回片段数，默认 3，最大 5。",
                    required = false,
                    type = ToolInputType.INTEGER,
                    minimum = 1.0,
                    maximum = 5.0,
                ),
            ),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = DEVICE_SNAPSHOT_TOOL_NAME,
            description = "读取当前前台窗口的有界、脱敏可访问节点快照，并为可操作节点生成 30 秒有效的引用；当前不会执行任何动作。",
            risk = ToolRisk.SAFE,
            permissionPolicy = ToolPermissionPolicy(supportsBackground = false),
            timeoutMs = 5_000,
        ),
        ToolDefinition(
            name = DEVICE_OPEN_APP_TOOL_NAME,
            description = "打开首批允许列表中的 Android 应用；需要用户确认，打开后重新观察并核对前台包名。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(
                ToolInputField(
                    name = "package_name",
                    description = "目标应用包名，仅允许小灵、系统计算器、时钟和系统设置。",
                    required = true,
                    enumValues = DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES,
                ),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 10_000,
        ),
        ToolDefinition(
            name = DEVICE_BACK_TOOL_NAME,
            description = "执行 Android 返回操作，并在操作后重新观察当前界面。",
            risk = ToolRisk.SAFE,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 10_000,
        ),
        ToolDefinition(
            name = DEVICE_HOME_TOOL_NAME,
            description = "返回 Android 桌面，并在操作后确认当前窗口属于桌面应用。",
            risk = ToolRisk.SAFE,
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 10_000,
        ),
        ToolDefinition(
            name = DEVICE_TAP_REF_TOOL_NAME,
            description = "点击最近一次 snapshot 中仍有效的节点引用；需要用户确认，页面变化或 ref 过期时拒绝。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = referenceInputSchema(),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 10_000,
        ),
        ToolDefinition(
            name = DEVICE_TYPE_TEXT_TOOL_NAME,
            description = "向最近一次 snapshot 中仍有效的普通文本框输入非敏感文本；需要用户确认，并在输入后回读验证。",
            risk = ToolRisk.REQUIRES_APPROVAL,
            inputSchema = referenceInputSchema() + ToolInputField(
                name = "text",
                description = "要输入的非敏感文本；不允许密码、验证码、密钥、账号或身份信息。",
                required = true,
                minLength = 1,
                maxLength = DeviceActionPolicy.MAX_TEXT_LENGTH,
            ),
            businessValidators = listOf(
                ToolBusinessValidator { arguments ->
                    DeviceActionPolicy().validateTextInput(arguments["text"].orEmpty())
                        ?.let(::listOf)
                        .orEmpty()
                },
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            validateBeforeAudit = true,
            timeoutMs = 10_000,
        ),
        ToolDefinition(
            name = DEVICE_SWIPE_TOOL_NAME,
            description = "在最近一次 snapshot 中仍有效的可滚动节点上执行一个方向滚动，并重新观察验证。",
            risk = ToolRisk.SAFE,
            inputSchema = referenceInputSchema() + ToolInputField(
                name = "direction",
                description = "滚动方向：up、down、left 或 right。",
                required = true,
                enumValues = setOf("up", "down", "left", "right"),
            ),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED,
            timeoutMs = 10_000,
        ),
    )

    init {
        ToolRegistryContract.requireValid(tools)
    }

    override fun bindRunContext(context: AgentToolExecutionContext) {
        if (runContext?.runId != context.runId) {
            // long: Workflow 的短期 snapshot、逐动作审批和执行结果只属于当前 Agent Run；切换 Run 时必须全部作废，关联重试不能继承旧 ref。
            clearWorkflowDeviceActionState()
        }
        runContext = context
    }

    override fun beforeToolExecution(call: ToolCall, approval: AgentToolApprovalEvidence?) {
        val context = runContext ?: return
        if (
            context.invocationSource != AgentInvocationSource.WORKFLOW ||
            call.name !in workflowDeviceActionToolNames
        ) {
            return
        }
        val workflowContext = context.workflowDeviceActionContext
            ?: throw IllegalStateException("Workflow 设备动作缺少 Workflow Run 与 Step 上下文")
        val snapshot = verifiedWorkflowSnapshot
            ?.takeIf { it.agentRunId == context.runId }
            ?: throw IllegalStateException("Workflow 设备动作缺少当前 Run 已验证的 device.snapshot")
        // long: 打开应用和系统导航只核对观察后的窗口代次；只有节点动作才解析短期 ref，避免把无节点参数的动作误绑定到空引用。
        val inspection = if (call.name in WORKFLOW_REFERENCE_ACTION_TOOL_NAMES) {
            deviceController.inspectReference(
                snapshotId = call.arguments["snapshot_id"].orEmpty(),
                ref = call.arguments["ref"].orEmpty(),
            )
        } else {
            DeviceReferenceInspection(
                currentWindowGeneration = deviceController.currentWindowGeneration(),
                matched = false,
            )
        }
        val identity = WorkflowDeviceActionIdentity(
            workflowRunId = workflowContext.workflowRunId,
            workflowStepId = workflowContext.workflowStepId,
            agentRunId = context.runId,
            toolCallId = call.id,
            toolName = call.name,
            arguments = call.arguments.toMap(),
        )
        val decision = workflowDeviceActionSafetyPolicy.assessExecution(
            WorkflowDeviceActionExecutionEvidence(
                identity = identity,
                userIntent = workflowContext.userIntent,
                invocationSource = context.invocationSource,
                executionOrigin = context.executionOrigin,
                currentProcessSessionId = context.processSessionId,
                observation = WorkflowDeviceActionObservationEvidence(
                    agentRunId = snapshot.agentRunId,
                    toolCallId = snapshot.toolCallId,
                    toolName = DEVICE_SNAPSHOT_TOOL_NAME,
                    snapshotId = snapshot.snapshot.snapshotId,
                    capturedAt = snapshot.snapshot.capturedAt,
                    expiresAt = snapshot.snapshot.expiresAt,
                    windowGeneration = snapshot.snapshot.windowGeneration,
                    verified = true,
                ),
                approval = approval?.let {
                    WorkflowDeviceActionApprovalEvidence(
                        agentRunId = context.runId,
                        toolCallId = call.id,
                        toolName = call.name,
                        arguments = call.arguments.toMap(),
                        approved = it.approved,
                        decidedAt = it.decidedAt,
                        decisionProcessSessionId = it.processSessionId,
                    )
                },
                // long: SAFE 系统导航不接受审批时间作为授权凭据，始终以实际执行时钟核对 30 秒 snapshot 窗口；需要审批的动作才冻结到用户决定时刻。
                nowMillis = if (call.name in SAFE_WORKFLOW_NAVIGATION_TOOL_NAMES) {
                    clock.nowMillis()
                } else {
                    approval?.decidedAt ?: clock.nowMillis()
                },
                currentWindowGeneration = inspection.currentWindowGeneration,
                liveReferenceMatched = inspection.matched,
                typeText = if (call.name == DEVICE_TYPE_TEXT_TOOL_NAME) {
                    WorkflowTypeTextExecutionEvidence(
                        target = inspection.target?.let { target ->
                            WorkflowTypeTextTargetEvidence(
                                enabled = target.enabled,
                                editable = target.editable,
                                redacted = target.redacted,
                                supportsTypeText = DeviceNodeAction.TYPE_TEXT in target.actions,
                            )
                        },
                    )
                } else {
                    null
                },
            ),
        )
        val authorization = (decision as? WorkflowDeviceActionSafetyDecision.Allowed)?.authorization
            ?: throw IllegalStateException((decision as WorkflowDeviceActionSafetyDecision.Denied).message)
        pendingWorkflowAction = WorkflowActionAuthorizationState(
            call = call.copy(arguments = call.arguments.toMap()),
            identity = identity,
            authorization = authorization,
            beforeSnapshot = snapshot.snapshot,
        )
        executedWorkflowAction = null
    }

    override fun afterToolVerification(call: ToolCall, result: ToolExecutionResult) {
        val context = runContext ?: return
        if (context.invocationSource != AgentInvocationSource.WORKFLOW) return
        when (call.name) {
            DEVICE_SNAPSHOT_TOOL_NAME -> {
                val candidate = pendingWorkflowSnapshot
                    ?.takeIf {
                        it.agentRunId == context.runId &&
                            it.toolCallId == call.id &&
                            result.success &&
                            result.content == DeviceSnapshotCodec.encode(it.snapshot)
                    }
                    ?: throw IllegalStateException("Workflow device.snapshot 验证结果与当前候选观察不一致")
                verifiedWorkflowSnapshot = candidate
                pendingWorkflowSnapshot = null
                pendingWorkflowAction = null
                executedWorkflowAction = null
            }
            in workflowDeviceActionToolNames -> verifyCompletedWorkflowAction(call, result, context)
        }
    }

    override fun availableTools(): List<ToolDefinition> {
        return availableToolsFor(runContext)
    }

    fun availableToolsFor(context: AgentToolExecutionContext?): List<ToolDefinition> {
        var available = tools
        if (context?.memoryRecallEnabled == false) {
            // long: 关闭单次记忆召回时从规划器工具清单移除 memory.search，避免模型先提出调用再由执行器拒绝造成误导性审计。
            available = available.filterNot { it.name == "memory.search" }
        }
        if (!deviceSnapshotAllowed(context)) {
            // long: 前台 Workflow 只获得脱敏观察能力；后台、未启用或缺少 Run Context 时连 snapshot 也不进入模型工具面。
            available = available.filterNot { it.name == DEVICE_SNAPSHOT_TOOL_NAME }
        }
        if (!directDeviceActionsAllowed(context)) {
            // long: 生产 Workflow 只放行已闭环的 open_app/back/home/tap_ref/type_text；其他已注册设备工具仍必须从规划器清单移除，不能因直接 `/agent` 已可用而连带扩权。
            available = available.filterNot { definition ->
                definition.name in DEVICE_ACTION_TOOL_NAMES &&
                    !workflowDeviceActionAllowed(context, definition.name)
            }
        }
        return available
    }

    fun registeredTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        return when (call.name) {
            "app.current_time" -> currentTime()
            "app.list_conversations" -> listConversations(call)
            "app.search_conversations" -> searchConversations(call)
            "notes.list" -> listNotes(call)
            "notes.search" -> searchNotes(call)
            "notes.create" -> createNote(call)
            "memory.search" -> searchMemory(call)
            "memory.remember" -> remember(call)
            "knowledge.search" -> searchKnowledge(call)
            DEVICE_SNAPSHOT_TOOL_NAME -> snapshotDevice(call)
            DEVICE_OPEN_APP_TOOL_NAME -> executeDeviceAction(call) {
                deviceController.openApp(call.arguments["package_name"].orEmpty())
            }
            DEVICE_BACK_TOOL_NAME -> executeDeviceAction(call, deviceController::back)
            DEVICE_HOME_TOOL_NAME -> executeDeviceAction(call, deviceController::home)
            DEVICE_TAP_REF_TOOL_NAME -> executeDeviceAction(call) {
                deviceController.tap(call.arguments["snapshot_id"].orEmpty(), call.arguments["ref"].orEmpty())
            }
            DEVICE_TYPE_TEXT_TOOL_NAME -> executeDeviceAction(call) {
                deviceController.typeText(
                    snapshotId = call.arguments["snapshot_id"].orEmpty(),
                    ref = call.arguments["ref"].orEmpty(),
                    text = call.arguments["text"].orEmpty(),
                )
            }
            DEVICE_SWIPE_TOOL_NAME -> executeDeviceAction(call) {
                deviceController.swipe(
                    snapshotId = call.arguments["snapshot_id"].orEmpty(),
                    ref = call.arguments["ref"].orEmpty(),
                    direction = DeviceScrollDirection.valueOf(call.arguments["direction"].orEmpty().uppercase()),
                )
            }
            else -> ToolExecutionResult(success = false, content = "未知工具：${call.name}")
        }
    }

    override suspend fun verifyCommittedEffect(
        call: ToolCall,
        receipt: ToolExecutionReceipt,
    ): ToolExecutionResult? {
        return when (call.name) {
            "notes.create" -> verifyCommittedNote(call, receipt)
            "memory.remember" -> verifyCommittedMemory(call, receipt)
            else -> null
        }
    }

    override fun supportsCommittedEffectVerification(toolName: String): Boolean {
        // long: 只有具备 operation 账本和结果快照的写工具才进入验证阶段恢复；能力白名单与幂等声明分离，避免未来仅修改 replaySafety 就扩大恢复范围。
        return toolName == "notes.create" || toolName == "memory.remember"
    }

    private fun currentTime(): ToolExecutionResult {
        return ToolExecutionResult(
            success = true,
            content = "当前时间：${clock.formattedNow()} · 时区：${clock.zoneId()}",
        )
    }

    private suspend fun snapshotDevice(call: ToolCall): ToolExecutionResult {
        deviceSnapshotContextError()?.let { return it }
        return when (val capture = deviceController.capture()) {
            is DeviceSnapshotCapture.Success -> {
                if (runContext?.invocationSource == AgentInvocationSource.WORKFLOW) {
                    pendingWorkflowSnapshot = WorkflowSnapshotCandidate(
                        agentRunId = requireNotNull(runContext).runId,
                        toolCallId = call.id,
                        snapshot = capture.snapshot,
                    )
                    verifiedWorkflowSnapshot = null
                    pendingWorkflowAction = null
                    executedWorkflowAction = null
                }
                ToolExecutionResult(
                    success = true,
                    content = DeviceSnapshotCodec.encode(capture.snapshot),
                )
            }
            is DeviceSnapshotCapture.Failed -> ToolExecutionResult(
                success = false,
                content = capture.message,
            )
        }
    }

    private suspend fun executeDeviceAction(
        call: ToolCall,
        block: suspend () -> DeviceActionCapture,
    ): ToolExecutionResult {
        deviceActionContextError(call.name)?.let { return it }
        val workflowState = if (runContext?.invocationSource == AgentInvocationSource.WORKFLOW) {
            pendingWorkflowAction
                ?.takeIf { it.call.id == call.id && it.call.name == call.name && it.call.arguments == call.arguments }
                ?: return ToolExecutionResult(success = false, content = "Workflow 设备动作缺少当前 ToolCall 的实时安全授权")
        } else {
            null
        }
        // long: 授权在越过 Executor 边界前即从待执行槽移除；失败、取消或重复调用都必须重新观察并重新审批。
        pendingWorkflowAction = null
        val capture = block()
        return when (capture) {
            is DeviceActionCapture.Success -> {
                if (workflowState != null) {
                    executedWorkflowAction = WorkflowExecutedActionState(
                        call = call.copy(arguments = call.arguments.toMap()),
                        identity = workflowState.identity,
                        authorization = workflowState.authorization,
                        beforeSnapshot = workflowState.beforeSnapshot,
                        outcome = capture.outcome,
                    )
                    ToolExecutionResult(
                        success = true,
                        content = WorkflowDeviceActionResultCodec.encode(
                            action = call.name.removePrefix("device."),
                            beforeSnapshot = workflowState.beforeSnapshot,
                            afterSnapshot = capture.outcome.afterSnapshot,
                            verified = capture.outcome.verified,
                        ),
                        verified = capture.outcome.verified,
                    )
                } else {
                    ToolExecutionResult(
                        success = true,
                        content = DeviceActionCodec.encode(capture.outcome),
                        verified = capture.outcome.verified,
                    )
                }
            }
            is DeviceActionCapture.Failed -> ToolExecutionResult(
                success = false,
                content = "${capture.reason}: ${capture.message}",
            )
        }
    }

    private fun deviceSnapshotContextError(): ToolExecutionResult? {
        val context = runContext
            ?: return ToolExecutionResult(success = false, content = "device.snapshot 缺少当前 Agent Run 上下文")
        if (context.invocationSource !in DEVICE_SNAPSHOT_INVOCATION_SOURCES) {
            return ToolExecutionResult(success = false, content = "device.snapshot 不允许当前调用来源")
        }
        if (context.executionOrigin != AgentExecutionOrigin.FOREGROUND) {
            return ToolExecutionResult(success = false, content = "device.snapshot 仅允许用户在前台执行")
        }
        deviceHealthContextError()?.let { return it }
        return null
    }

    private fun deviceActionContextError(toolName: String): ToolExecutionResult? {
        val context = runContext
            ?: return ToolExecutionResult(success = false, content = "设备工具缺少当前 Agent Run 上下文")
        if (context.executionOrigin != AgentExecutionOrigin.FOREGROUND) {
            return ToolExecutionResult(success = false, content = "设备工具仅允许用户在前台执行")
        }
        if (
            context.invocationSource != AgentInvocationSource.DIRECT &&
            !(context.invocationSource == AgentInvocationSource.WORKFLOW && toolName in workflowDeviceActionToolNames)
        ) {
            return ToolExecutionResult(success = false, content = "该设备动作尚未开放给 Workflow，请使用前台直接 /agent 对话")
        }
        deviceHealthContextError()?.let { return it }
        return null
    }

    private fun deviceSnapshotAllowed(context: AgentToolExecutionContext?): Boolean {
        return context?.invocationSource in DEVICE_SNAPSHOT_INVOCATION_SOURCES &&
            context?.executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            deviceController.health() == DeviceAgentHealthState.READY
    }

    private fun directDeviceActionsAllowed(context: AgentToolExecutionContext?): Boolean {
        return context?.invocationSource == AgentInvocationSource.DIRECT &&
            context.executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            deviceController.health() == DeviceAgentHealthState.READY
    }

    private fun workflowDeviceActionAllowed(
        context: AgentToolExecutionContext?,
        toolName: String,
    ): Boolean {
        return toolName in workflowDeviceActionToolNames &&
            context?.invocationSource == AgentInvocationSource.WORKFLOW &&
            context.executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            context.processSessionId.isNotBlank() &&
            context.workflowDeviceActionContext != null &&
            deviceController.health() == DeviceAgentHealthState.READY
    }

    private fun verifyCompletedWorkflowAction(
        call: ToolCall,
        result: ToolExecutionResult,
        context: AgentToolExecutionContext,
    ) {
        val executed = executedWorkflowAction
            ?.takeIf { it.call.id == call.id && it.call.name == call.name && it.call.arguments == call.arguments }
            ?: throw IllegalStateException("Workflow 设备动作缺少当前动作执行证据")
        val decoded = WorkflowDeviceActionResultCodec.decode(result.content)
            ?.takeIf { it.action == call.name.removePrefix("device.") }
            ?: throw IllegalStateException("Workflow 设备动作结果不符合白名单动作规则")
        val decision = workflowDeviceActionSafetyPolicy.assessCompletion(
            WorkflowDeviceActionCompletionEvidence(
                // long: type_text 的通用授权会移除原文；完成校验必须使用执行前仅驻留内存的原始 identity 才能重新核对文本指纹与精确回读。
                identity = executed.identity,
                authorization = executed.authorization,
                resultAgentRunId = context.runId,
                resultToolCallId = call.id,
                resultToolName = call.name,
                success = result.success,
                executorVerified = result.verified == true && decoded.verified,
                verificationPassed = true,
                actionCompletedAt = executed.outcome.afterSnapshot.capturedAt,
                afterPackageName = decoded.afterPackageName,
                afterObservation = WorkflowDeviceActionPostObservationEvidence(
                    agentRunId = context.runId,
                    actionToolCallId = call.id,
                    snapshotId = executed.outcome.afterSnapshot.snapshotId,
                    observedAt = decoded.afterObservedAt,
                    windowGeneration = executed.outcome.afterSnapshot.windowGeneration,
                    verified = decoded.verified,
                ),
                cancelled = false,
                typeText = if (call.name == DEVICE_TYPE_TEXT_TOOL_NAME) {
                    WorkflowTypeTextCompletionEvidence(
                        resultAgentRunId = context.runId,
                        resultToolCallId = call.id,
                        resultToolName = call.name,
                        actionCompletedAt = executed.outcome.afterSnapshot.capturedAt,
                        observedAt = decoded.afterObservedAt,
                        executorVerified = result.verified == true && decoded.verified,
                        verificationPassed = true,
                        afterObservationVerified = decoded.verified,
                        readBackText = executed.outcome.typeTextReadBack?.text,
                    )
                } else {
                    null
                },
            ),
        )
        clearWorkflowDeviceActionState()
        if (decision is WorkflowDeviceActionSafetyDecision.Denied) {
            throw IllegalStateException(decision.message)
        }
    }

    private fun clearWorkflowDeviceActionState() {
        pendingWorkflowSnapshot = null
        verifiedWorkflowSnapshot = null
        pendingWorkflowAction = null
        executedWorkflowAction = null
    }

    private data class WorkflowSnapshotCandidate(
        val agentRunId: String,
        val toolCallId: String,
        val snapshot: DeviceSnapshot,
    )

    private data class WorkflowActionAuthorizationState(
        val call: ToolCall,
        val identity: WorkflowDeviceActionIdentity,
        val authorization: WorkflowDeviceActionAuthorization,
        val beforeSnapshot: DeviceSnapshot,
    )

    private data class WorkflowExecutedActionState(
        val call: ToolCall,
        val identity: WorkflowDeviceActionIdentity,
        val authorization: WorkflowDeviceActionAuthorization,
        val beforeSnapshot: DeviceSnapshot,
        val outcome: com.longdev.xiaoling.device.DeviceActionOutcome,
    )

    private fun deviceHealthContextError(): ToolExecutionResult? {
        // long: 规划清单和 Executor 必须消费同一健康状态，避免模型在无障碍未授权或服务断连时看到实际上不可执行的设备工具。
        return when (deviceController.health()) {
            DeviceAgentHealthState.AGENT_DISABLED -> ToolExecutionResult(
                success = false,
                content = "设备 Agent 尚未启用，请先在设置中明确开启",
            )
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED -> ToolExecutionResult(
                success = false,
                content = "无障碍服务未授权或授权已失效，请前往系统设置重新确认",
            )
            DeviceAgentHealthState.SERVICE_DISCONNECTED -> ToolExecutionResult(
                success = false,
                content = "无障碍服务已授权但尚未连接，请稍后刷新或重新启用服务",
            )
            DeviceAgentHealthState.READY -> null
        }
    }

    private suspend fun listConversations(call: ToolCall): ToolExecutionResult {
        val conversations = conversationStore.list(call.limit())
        return ToolExecutionResult(success = true, content = conversations.toConversationText("最近会话"))
    }

    private suspend fun searchConversations(call: ToolCall): ToolExecutionResult {
        val query = call.arguments["query"].orEmpty().trim()
        if (query.isBlank()) return ToolExecutionResult(success = false, content = "会话搜索关键词不能为空")
        val conversations = conversationStore.search(query = query, limit = call.limit())
        return ToolExecutionResult(success = true, content = conversations.toConversationText("匹配会话"))
    }

    private suspend fun listNotes(call: ToolCall): ToolExecutionResult {
        val notes = noteStore.list(call.limit())
        return ToolExecutionResult(success = true, content = notes.toNoteText("最近笔记"))
    }

    private suspend fun searchNotes(call: ToolCall): ToolExecutionResult {
        val query = call.arguments["query"].orEmpty().trim()
        if (query.isBlank()) return ToolExecutionResult(success = false, content = "笔记搜索关键词不能为空")
        val notes = noteStore.search(query = query, limit = call.limit())
        return ToolExecutionResult(success = true, content = notes.toNoteText("匹配笔记"))
    }

    private suspend fun createNote(call: ToolCall): ToolExecutionResult {
        val title = call.arguments["title"].orEmpty().trim()
        val content = call.arguments["content"].orEmpty().trim()
        if (title.isBlank() || content.isBlank()) {
            return ToolExecutionResult(success = false, content = "笔记标题和正文不能为空")
        }
        // long: notes.create 是第一批带本地写入副作用的工具，必须由 Runtime 先走审批；写入后立即回读，避免只凭 insert 成功就向用户宣称任务完成。
        val created = noteStore.create(title = title, content = content, idempotencyKey = call.id)
        // long: noteStore 用 ToolCall ID 原子去重；进程在写入后中断时，同一调用重放会返回原 note ID，不会创建第二条笔记。
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = created.id,
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val verified = noteStore.get(created.id)
            ?.takeIf { it.title == title && it.content == content }
        return if (verified == null) {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "笔记已写入但回读验证失败，不能确认创建成功：$title",
                executionReceipt = receipt,
            )
        } else {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = "已创建并验证笔记：${created.title}\n${created.content}",
                executionReceipt = receipt,
            )
        }
    }

    private suspend fun verifyCommittedNote(
        call: ToolCall,
        receipt: ToolExecutionReceipt,
    ): ToolExecutionResult {
        val title = call.arguments["title"].orEmpty().trim()
        val content = call.arguments["content"].orEmpty().trim()
        val receiptMatchesCall = receipt.toolCallId == call.id &&
            receipt.idempotencyKey == call.id &&
            receipt.status == ToolExecutionReceiptStatus.COMMITTED
        val stored = receipt.operationId
            .takeIf { receiptMatchesCall && title.isNotBlank() && content.isNotBlank() }
            ?.let { noteStore.get(it) }
        // long: 验证阶段恢复只按回执中的 operation ID 回读已有笔记，不调用 create；载荷、调用 ID 或幂等键漂移时必须 fail-closed。
        return if (stored?.title == title && stored.content == content) {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = "已创建并验证笔记：${stored.title}\n${stored.content}",
                executionReceipt = receipt,
            )
        } else {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "已提交笔记与持久化工具证据不一致，不能恢复验证",
                executionReceipt = receipt,
            )
        }
    }

    private suspend fun remember(call: ToolCall): ToolExecutionResult {
        val request = memoryWriteRequest(call)
        if (request.content.isBlank()) return ToolExecutionResult(success = false, content = "长期记忆内容不能为空")
        // long: 长期记忆写入要保存来源和启用状态，后续用户才能追问“为什么记住这件事”，并在管理页禁用或删除。
        val record = memoryStore.remember(
            content = request.content,
            tags = request.tags,
            type = request.type,
            source = request.source,
            confidence = request.confidence,
            idempotencyKey = call.id,
        )
        // long: memoryStore 用独立 operation 映射把 ToolCall ID 绑定到原始载荷；同键重放返回原 memory ID，载荷漂移则在写入前失败。
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = record.id,
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val verified = memoryStore.get(record.id)?.takeIf { stored ->
            stored.content == record.content &&
                stored.tags == record.tags &&
                stored.type == record.type &&
                stored.sourceConversationId == record.sourceConversationId &&
                stored.sourceRunId == record.sourceRunId &&
                stored.enabled
        }
        return if (verified == null) {
            ToolExecutionResult(
                success = false,
                verified = false,
                content = "长期记忆已写入但回读验证失败，不能确认保存成功：${record.content}",
                executionReceipt = receipt,
            )
        } else {
            ToolExecutionResult(
                success = true,
                verified = true,
                content = memorySuccessContent(record),
                executionReceipt = receipt,
            )
        }
    }

    private suspend fun verifyCommittedMemory(
        call: ToolCall,
        receipt: ToolExecutionReceipt,
    ): ToolExecutionResult {
        val receiptMatchesCall = receipt.toolCallId == call.id &&
            receipt.idempotencyKey == call.id &&
            receipt.status == ToolExecutionReceiptStatus.COMMITTED
        if (!receiptMatchesCall) {
            return failedMemoryVerification(receipt, AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH)
        }
        val request = memoryWriteRequest(call)
        if (request.content.isBlank()) {
            return failedMemoryVerification(receipt, AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH)
        }
        // long: 恢复只按历史 ToolCall、Run Context 和 operation ID 校验原结果，不调用 remember；用户编辑、禁用、过期或删除后都不能沿用旧成功结论。
        return when (
            val verification = memoryStore.verifyRememberedOperation(
                idempotencyKey = call.id,
                memoryId = receipt.operationId,
                request = request,
                nowMillis = clock.nowMillis(),
            )
        ) {
            is AgentMemoryOperationVerification.Verified -> {
                val memory = verification.memory
                ToolExecutionResult(
                    success = true,
                    verified = true,
                    content = memorySuccessContent(memory),
                    executionReceipt = receipt,
                )
            }
            is AgentMemoryOperationVerification.Failed -> failedMemoryVerification(receipt, verification.reason)
        }
    }

    private fun memoryWriteRequest(call: ToolCall): AgentMemoryWriteRequest {
        val context = runContext
        return AgentMemoryWriteRequest(
            content = call.arguments["note"].orEmpty().trim(),
            tags = call.arguments["tags"].orEmpty().trim(),
            type = call.arguments["type"].orEmpty().trim().ifBlank { "Episode" },
            source = context?.let {
                AgentMemorySource(
                    conversationId = it.conversationId,
                    runId = it.runId,
                    summary = "由 /agent Run 写入（来源 Run 可查看）",
                )
            } ?: AgentMemorySource(conversationId = null, runId = null, summary = "来源未知"),
            confidence = 0.8,
        )
    }

    private fun memorySuccessContent(memory: AgentMemoryRecord): String {
        val tagText = memory.tags.takeIf { it.isNotBlank() }?.let { " · 标签：$it" }.orEmpty()
        return "已保存并验证长期记忆：${memory.content} · 类型：${memory.type}$tagText · 来源：${memory.sourceSummary}"
    }

    private fun failedMemoryVerification(
        receipt: ToolExecutionReceipt,
        reason: AgentMemoryOperationVerificationFailure,
    ): ToolExecutionResult {
        // long: 历史证据或当前记忆状态不再满足只读验证条件时，旧 Run 必须保持失败；这里把原因和新 Run 建议绑定，避免新增错误码时漏配任一字段。
        val recoveryFailure = when (reason) {
            AgentMemoryOperationVerificationFailure.OPERATION_NOT_FOUND ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "找不到原记忆 operation",
                    suggestedAction = "请创建新 Run 重新保存这条记忆，原 operation 证据已不存在。",
                )
            AgentMemoryOperationVerificationFailure.EVIDENCE_INCOMPLETE ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "历史 operation 缺少结果快照",
                    suggestedAction = "历史版本缺少结果快照，请创建新 Run 重新保存并建立完整证据。",
                )
            AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "原写入参数与持久化证据不一致",
                    suggestedAction = "请创建新 Run 重新确认保存内容，不要继续使用当前旧 Run。",
                )
            AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "工具回执与原 operation 不一致",
                    suggestedAction = "请创建新 Run 重新确认保存内容，不要继续使用当前旧 Run。",
                )
            AgentMemoryOperationVerificationFailure.MEMORY_NOT_FOUND ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "原长期记忆已删除",
                    suggestedAction = "如仍需保留该事实，请创建新 Run 重新保存。",
                )
            AgentMemoryOperationVerificationFailure.MEMORY_CHANGED ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "原长期记忆业务字段已修改",
                    suggestedAction = "请保留当前编辑结果，并创建新 Run 重新确认是否需要保存。",
                )
            AgentMemoryOperationVerificationFailure.MEMORY_DISABLED ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "原长期记忆已禁用",
                    suggestedAction = "请先在长期记忆管理中启用该记忆，再创建新 Run 重试。",
                )
            AgentMemoryOperationVerificationFailure.MEMORY_EXPIRED ->
                ToolRecoveryFailure(
                    code = reason.name,
                    reason = "原长期记忆已过期",
                    suggestedAction = "请先在长期记忆管理中更新过期时间，再创建新 Run 重试。",
                )
        }
        return ToolExecutionResult(
            success = false,
            verified = false,
            content = "长期记忆恢复验证失败：${recoveryFailure.code}（${recoveryFailure.reason}）",
            executionReceipt = receipt,
            recoveryFailure = recoveryFailure,
        )
    }

    private suspend fun searchMemory(call: ToolCall): ToolExecutionResult {
        if (runContext?.memoryRecallEnabled == false) {
            return ToolExecutionResult(success = true, content = "本次 Run 已关闭长期记忆召回。")
        }
        val memories = memoryStore.search(
            query = call.arguments["query"].orEmpty().trim(),
            limit = call.limit(),
            enabledOnly = true,
        )
        if (memories.isEmpty()) return ToolExecutionResult(success = true, content = "未找到匹配长期记忆。")
        return ToolExecutionResult(
            success = true,
            memoryIdsUsed = memories.map { it.id },
            content = memories.joinToString(separator = "\n", prefix = "长期记忆：\n") { memory ->
                val tags = memory.tags.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
                "- $tags${memory.content} · 类型：${memory.type} · 来源：${memory.sourceSummary}"
            },
        )
    }

    private suspend fun searchKnowledge(call: ToolCall): ToolExecutionResult {
        val query = call.arguments["query"].orEmpty().trim()
        if (query.isBlank()) return ToolExecutionResult(success = false, content = "知识库检索关键词不能为空")
        val context = runContext
        val search = knowledgeStore.search(
            query = query,
            limit = call.knowledgeLimit(),
            sourceConversationId = context?.conversationId,
            sourceRunId = context?.runId,
        )
        if (search.hits.isEmpty()) {
            return ToolExecutionResult(success = true, content = "未找到匹配的本地知识片段。")
        }
        // long: 模型可读取正文片段，但审计链只信任 Store 返回的稳定身份；文档替换后 revision 和 chunk ID 同时变化，旧引用不会被新 Run 复用。
        val references = search.hits.map { hit ->
            KnowledgeReference(
                retrievalId = search.retrieval.id,
                documentId = hit.documentId,
                documentName = hit.documentName,
                documentRevision = hit.documentRevision,
                chunkId = hit.chunkId,
                chunkSequence = hit.sequence,
                startOffset = hit.startOffset,
                endOffset = hit.endOffset,
            )
        }
        return ToolExecutionResult(
            success = true,
            knowledgeReferences = references,
            content = search.hits.joinToString(separator = "\n", prefix = "本地知识检索结果：\n") { hit ->
                "- ${hit.documentName} · revision=${hit.documentRevision} · chunk=${hit.sequence} · offset=${hit.startOffset}-${hit.endOffset}\n  ${hit.text}"
            },
        )
    }

    private fun ToolCall.limit(): Int {
        return arguments["limit"]
            ?.toIntOrNull()
            ?.let { min(max(it, 1), 10) }
            ?: 5
    }

    private fun ToolCall.knowledgeLimit(): Int {
        return arguments["limit"]
            ?.toIntOrNull()
            ?.let { min(max(it, 1), 5) }
            ?: 3
    }

    private fun List<AgentConversationRecord>.toConversationText(title: String): String {
        if (isEmpty()) return "$title：无"
        return joinToString(separator = "\n", prefix = "$title：\n") { conversation ->
            "- ${conversation.title} · ${conversation.messageCount} 条消息 · id=${conversation.id}"
        }
    }

    private fun List<AgentNoteRecord>.toNoteText(title: String): String {
        if (isEmpty()) return "$title：无"
        return joinToString(separator = "\n", prefix = "$title：\n") { note ->
            "- ${note.title} · id=${note.id}\n  ${note.content.take(120)}"
        }
    }
}

private object DisabledDeviceController : DeviceController {
    override fun health(): DeviceAgentHealthState = DeviceAgentHealthState.AGENT_DISABLED

    // long: 设备 Agent 关闭时不存在可授权的活动窗口，返回负代次让所有非 ref 动作在安全策略前保持不可执行。
    override fun currentWindowGeneration(): Long = -1L

    override suspend fun capture(): DeviceSnapshotCapture {
        return DeviceSnapshotCapture.Failed(
            reason = DeviceSnapshotFailure.AGENT_DISABLED,
            message = "设备 Agent 尚未启用，请先在设置中明确开启",
        )
    }

    override suspend fun openApp(packageName: String): DeviceActionCapture = disabledAction()

    override suspend fun back(): DeviceActionCapture = disabledAction()

    override suspend fun home(): DeviceActionCapture = disabledAction()

    override suspend fun tap(snapshotId: String, ref: String): DeviceActionCapture = disabledAction()

    override suspend fun typeText(snapshotId: String, ref: String, text: String): DeviceActionCapture = disabledAction()

    override suspend fun swipe(
        snapshotId: String,
        ref: String,
        direction: DeviceScrollDirection,
    ): DeviceActionCapture = disabledAction()

    private fun disabledAction(): DeviceActionCapture.Failed {
        return DeviceActionCapture.Failed(
            reason = DeviceActionFailure.AGENT_DISABLED,
            message = "设备 Agent 尚未启用，请先在设置中明确开启",
        )
    }
}

private const val DEVICE_SNAPSHOT_TOOL_NAME = "device.snapshot"
internal const val DEVICE_OPEN_APP_TOOL_NAME = "device.open_app"
private const val DEVICE_BACK_TOOL_NAME = "device.back"
private const val DEVICE_HOME_TOOL_NAME = "device.home"
internal const val DEVICE_TAP_REF_TOOL_NAME = "device.tap_ref"
private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
private val DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES = setOf(
    DEVICE_OPEN_APP_TOOL_NAME,
    DEVICE_BACK_TOOL_NAME,
    DEVICE_HOME_TOOL_NAME,
    DEVICE_TAP_REF_TOOL_NAME,
    DEVICE_TYPE_TEXT_TOOL_NAME,
)
private val SUPPORTED_WORKFLOW_DEVICE_ACTION_TOOL_NAMES = setOf(
    DEVICE_OPEN_APP_TOOL_NAME,
    DEVICE_BACK_TOOL_NAME,
    DEVICE_HOME_TOOL_NAME,
    DEVICE_TAP_REF_TOOL_NAME,
    DEVICE_TYPE_TEXT_TOOL_NAME,
)
private val SAFE_WORKFLOW_NAVIGATION_TOOL_NAMES = setOf(DEVICE_BACK_TOOL_NAME, DEVICE_HOME_TOOL_NAME)
private val WORKFLOW_REFERENCE_ACTION_TOOL_NAMES = setOf(
    DEVICE_TAP_REF_TOOL_NAME,
    DEVICE_TYPE_TEXT_TOOL_NAME,
    DEVICE_SWIPE_TOOL_NAME,
)
private const val DEVICE_SWIPE_TOOL_NAME = "device.swipe"

private val DEVICE_TOOL_NAMES = setOf(
    DEVICE_SNAPSHOT_TOOL_NAME,
    DEVICE_OPEN_APP_TOOL_NAME,
    DEVICE_BACK_TOOL_NAME,
    DEVICE_HOME_TOOL_NAME,
    DEVICE_TAP_REF_TOOL_NAME,
    DEVICE_TYPE_TEXT_TOOL_NAME,
    DEVICE_SWIPE_TOOL_NAME,
)

private val DEVICE_ACTION_TOOL_NAMES = DEVICE_TOOL_NAMES - DEVICE_SNAPSHOT_TOOL_NAME

private val DEVICE_SNAPSHOT_INVOCATION_SOURCES = setOf(
    AgentInvocationSource.DIRECT,
    AgentInvocationSource.WORKFLOW,
)

private fun referenceInputSchema(): List<ToolInputField> = listOf(
    ToolInputField(
        name = "snapshot_id",
        description = "最近一次 device.snapshot 返回的 snapshot_id。",
        required = true,
        minLength = 1,
        maxLength = 120,
    ),
    ToolInputField(
        name = "ref",
        description = "同一 snapshot 中节点的短生命周期 ref。",
        required = true,
        minLength = 2,
        maxLength = 20,
    ),
)

class SystemAgentClock(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AgentClock {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun formattedNow(): String = formatter.format(Instant.ofEpochMilli(nowMillis()))

    override fun zoneId(): String = zone.id
}
