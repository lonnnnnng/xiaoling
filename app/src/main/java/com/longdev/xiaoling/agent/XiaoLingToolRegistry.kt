package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceActionCapture
import com.longdev.xiaoling.device.DeviceActionCodec
import com.longdev.xiaoling.device.DeviceActionFailure
import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.device.DeviceController
import com.longdev.xiaoling.device.DeviceScrollDirection
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
) : ToolRegistry, AgentRunContextAwareToolRegistry {
    private var runContext: AgentToolExecutionContext? = null

    internal fun withKnowledgeStore(store: KnowledgeDocumentStore): XiaoLingToolRegistry = XiaoLingToolRegistry(
        clock = clock,
        conversationStore = conversationStore,
        noteStore = noteStore,
        memoryStore = memoryStore,
        knowledgeStore = store,
        deviceController = deviceController,
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
        runContext = context
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
        if (!deviceToolsAllowed(context)) {
            // long: 设备能力仍限定前台直接对话；Workflow、后台、未启用或缺少 Run Context 时全部从模型工具面移除，执行层还会再次校验。
            available = available.filterNot { it.name in DEVICE_TOOL_NAMES }
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
            DEVICE_SNAPSHOT_TOOL_NAME -> snapshotDevice()
            DEVICE_OPEN_APP_TOOL_NAME -> executeDeviceAction {
                deviceController.openApp(call.arguments["package_name"].orEmpty())
            }
            DEVICE_BACK_TOOL_NAME -> executeDeviceAction(deviceController::back)
            DEVICE_HOME_TOOL_NAME -> executeDeviceAction(deviceController::home)
            DEVICE_TAP_REF_TOOL_NAME -> executeDeviceAction {
                deviceController.tap(call.arguments["snapshot_id"].orEmpty(), call.arguments["ref"].orEmpty())
            }
            DEVICE_TYPE_TEXT_TOOL_NAME -> executeDeviceAction {
                deviceController.typeText(
                    snapshotId = call.arguments["snapshot_id"].orEmpty(),
                    ref = call.arguments["ref"].orEmpty(),
                    text = call.arguments["text"].orEmpty(),
                )
            }
            DEVICE_SWIPE_TOOL_NAME -> executeDeviceAction {
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

    private suspend fun snapshotDevice(): ToolExecutionResult {
        deviceToolContextError()?.let { return it }
        return when (val capture = deviceController.capture()) {
            is DeviceSnapshotCapture.Success -> ToolExecutionResult(
                success = true,
                content = DeviceSnapshotCodec.encode(capture.snapshot),
            )
            is DeviceSnapshotCapture.Failed -> ToolExecutionResult(
                success = false,
                content = capture.message,
            )
        }
    }

    private suspend fun executeDeviceAction(block: suspend () -> DeviceActionCapture): ToolExecutionResult {
        deviceToolContextError()?.let { return it }
        val capture = block()
        return when (capture) {
            is DeviceActionCapture.Success -> ToolExecutionResult(
                success = true,
                content = DeviceActionCodec.encode(capture.outcome),
                verified = capture.outcome.verified,
            )
            is DeviceActionCapture.Failed -> ToolExecutionResult(
                success = false,
                content = "${capture.reason}: ${capture.message}",
            )
        }
    }

    private fun deviceToolContextError(): ToolExecutionResult? {
        val context = runContext
            ?: return ToolExecutionResult(success = false, content = "设备工具缺少当前 Agent Run 上下文")
        if (context.invocationSource != AgentInvocationSource.DIRECT) {
            return ToolExecutionResult(success = false, content = "设备工具尚未开放给 Workflow，请使用前台直接 /agent 对话")
        }
        if (context.executionOrigin != AgentExecutionOrigin.FOREGROUND) {
            return ToolExecutionResult(success = false, content = "设备工具仅允许前台直接执行")
        }
        if (!deviceController.isAgentEnabled()) {
            return ToolExecutionResult(success = false, content = "设备 Agent 尚未启用，请先在设置中明确开启")
        }
        return null
    }

    private fun deviceToolsAllowed(context: AgentToolExecutionContext?): Boolean {
        return context?.invocationSource == AgentInvocationSource.DIRECT &&
            context.executionOrigin == AgentExecutionOrigin.FOREGROUND &&
            deviceController.isAgentEnabled()
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
    override fun isAgentEnabled(): Boolean = false

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
private const val DEVICE_OPEN_APP_TOOL_NAME = "device.open_app"
private const val DEVICE_BACK_TOOL_NAME = "device.back"
private const val DEVICE_HOME_TOOL_NAME = "device.home"
private const val DEVICE_TAP_REF_TOOL_NAME = "device.tap_ref"
private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
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
