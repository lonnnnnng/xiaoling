package com.longdev.xiaoling.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecisionPolicy
import com.longdev.xiaoling.automation.WorkflowDeviceActionEvidenceInput
import com.longdev.xiaoling.automation.WorkflowDeviceActionResolution
import com.longdev.xiaoling.automation.WorkflowDeviceActionResultCodec
import com.longdev.xiaoling.device.AndroidDeviceAccessibilityGateway
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationController
import com.longdev.xiaoling.device.DevicePrivacyProbeActivity
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import com.longdev.xiaoling.device.DeviceSnapshotFailure
import com.longdev.xiaoling.device.DeviceAccessibilityRuntime
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowDeviceActionApprovalPersistence
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class AgentE2eDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_E2E) return
        val operation = intent.getStringExtra(EXTRA_OPERATION)
        if (
            operation == OPERATION_WORKFLOW_TAP_REF ||
            operation == OPERATION_WORKFLOW_TYPE_TEXT ||
            operation == OPERATION_WORKFLOW_BACK ||
            operation == OPERATION_WORKFLOW_HOME
        ) {
            // long: 人工审批可能超过 BroadcastReceiver 的十秒窗口；Debug 验收任务由进程级 scope 承载，Receiver 立即返回以避免系统 ANR。
            debugScope.launch {
                runCatching {
                    when (operation) {
                        OPERATION_WORKFLOW_TYPE_TEXT -> runWorkflowTypeText(context.applicationContext)
                        OPERATION_WORKFLOW_BACK -> runWorkflowBack(context.applicationContext)
                        OPERATION_WORKFLOW_HOME -> runWorkflowHome(context.applicationContext)
                        else -> runWorkflowTapRef(context.applicationContext)
                    }
                }
                    .onFailure { error ->
                        Log.e(TAG, "agent-e2e success=false reason=${error::class.java.simpleName} message=${error.message}")
                    }
            }
            return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (intent.getStringExtra(EXTRA_OPERATION)) {
                    OPERATION_SETUP -> setup(appContext, intent)
                    OPERATION_STATUS -> reportStatus(appContext)
                    else -> Log.w(TAG, "agent-e2e success=false reason=unknown-operation")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "agent-e2e success=false reason=${error::class.java.simpleName} message=${error.message}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun setup(context: Context, intent: Intent) {
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val allowedTools = intent.getStringExtra(EXTRA_ALLOWED_TOOL)
            .orEmpty()
            .ifBlank { DEFAULT_ALLOWED_TOOL }
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        require(allowedTools.isNotEmpty()) { "Debug Agent 至少需要一个允许工具" }
        require(baseUrl.isNotBlank() && model.isNotBlank()) { "mock Provider 配置不完整" }
        val provider = ProviderProfile(
            id = PROVIDER_ID,
            name = "设备动作 E2E",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        ProviderRepository(context).save(listOf(provider), provider.id)
        val now = System.currentTimeMillis()
        val agentProfile = AgentProfileRecord(
            id = AGENT_PROFILE_ID,
            name = "设备打开应用 E2E",
            avatar = "D",
            providerId = provider.id,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "仅执行用户明确要求且当前 Profile 已授权的工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            // long: 后台 Workflow 会按运行来源移除设备动作；Debug 验收入口允许显式选择受控工具集，才能分别覆盖前台设备动作与后台多步骤调度链路。
            allowedToolNames = allowedTools,
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        RoomAgentProfileStore(context).apply {
            upsert(agentProfile)
            check(select(agentProfile.id)) { "无法选择 E2E Agent Profile" }
        }
        UiPreferenceStore(context).saveDeviceAgentEnabled(true)
        Log.i(TAG, "agent-e2e setup=true model=$model tools=${allowedTools.joinToString()}")
    }

    private suspend fun reportStatus(context: Context) {
        val detail = RoomAgentRunRepository(context).recentRunDetails(1).firstOrNull()
        if (detail == null) {
            Log.i(TAG, "agent-e2e status=NO_RUN")
            return
        }
        val result = detail.toolLedger.results.lastOrNull()
        val approval = detail.approvals.lastOrNull()
        Log.i(
            TAG,
            "agent-e2e run=${detail.snapshot.run.id} status=${detail.snapshot.run.status} " +
                "approval=${approval?.status} tool=${result?.toolName} success=${result?.success} " +
                "executorVerified=${result?.executorVerified} verification=${result?.verificationStatus}",
        )
    }

    private suspend fun runWorkflowTapRef(context: Context) {
        context.startActivity(
            Intent(context, DevicePrivacyProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = AndroidDeviceAccessibilityGateway(context),
        )
        awaitDeviceReady(controller)
        awaitProbeWindow(controller)
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val processSessionId = "process-redmi-workflow-device-action"
        val runRepository = RoomAgentRunRepository(context)
        val scriptedLlm = WorkflowTapRefE2eLlm()
        val runtime = MinimalAgentRuntime(
            ledger = runRepository,
            toolRegistry = registry,
            llm = scriptedLlm,
            approvalGate = WorkflowDeviceActionApprovalGate(
                conversationId = E2E_CONVERSATION_ID,
                userIntent = "点击当前页面的安全按钮",
                fallback = AutoApprovalGate(),
                persistence = RoomWorkflowDeviceActionApprovalPersistence(runRepository),
                overlayRequester = DeviceAccessibilityRuntime,
            ),
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = processSessionId,
        )
        Log.i(TAG, "workflow-device-action-overlay waiting=true")
        val summary = runtime.run(
            conversationId = E2E_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-device-action-${System.currentTimeMillis()}",
            goal = "点击安全按钮并确认页面变化",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-device-action",
                workflowStepId = "workflow-step-redmi-device-action",
                userIntent = "点击当前页面的安全按钮",
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow 设备动作 Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow 设备动作 Run 未完成：${detail.snapshot.run.status}"
        }
        val approval = detail.approvals.single { it.toolName == DEVICE_TAP_REF_TOOL_NAME }
        check(approval.status == ApprovalRequestStatus.APPROVED) {
            "Room 设备动作审批不是 APPROVED：${approval.status}"
        }
        val tapResult = detail.toolLedger.results.single { it.toolName == DEVICE_TAP_REF_TOOL_NAME }
        check(
            tapResult.success &&
                tapResult.executorVerified == true &&
                tapResult.verificationStatus == ToolVerificationStatus.PASSED,
        ) { "Tool Ledger 未保存通过验证的 tap_ref：$tapResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(tapResult.content)) {
            "Workflow tap_ref 没有返回严格白名单结果"
        }
        check(!tapResult.content.contains(scriptedLlm.snapshotId) && !tapResult.content.contains(scriptedLlm.ref)) {
            "Workflow tap_ref 结果泄露 snapshot/ref"
        }

        val postSnapshot = captureWhenReady(controller).snapshot
        val postTextObserved = postSnapshot.nodes.any { node -> node.text == "动作已完成" }
        check(postTextObserved) { "真实 Accessibility 点击后没有观察到动作完成文本" }
        Log.i(
            TAG,
            "workflow-device-action-e2e success=true action=${actionEvidence.action} " +
                "verified=${actionEvidence.verified} beforePackage=${actionEvidence.beforePackageName} " +
                "afterPackage=${actionEvidence.afterPackageName} afterNodes=${actionEvidence.afterNodeCount} " +
                "postText=$postTextObserved",
        )
    }

    private suspend fun runWorkflowTypeText(context: Context) {
        context.startActivity(
            Intent(context, DevicePrivacyProbeActivity::class.java)
                .putExtra(DevicePrivacyProbeActivity.EXTRA_MODE, DevicePrivacyProbeActivity.MODE_TYPE_TEXT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = AndroidDeviceAccessibilityGateway(context),
        )
        awaitDeviceReady(controller)
        awaitTypeTextProbeWindow(controller)
        // long: 专用 Probe 首次显示 EditText 时先等待焦点/IME 事件收敛，Runtime 随后会自己重新 snapshot；不得复用等待前的节点引用。
        delay(500)
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val processSessionId = "process-redmi-workflow-type-text"
        val runRepository = RoomAgentRunRepository(context)
        val scriptedLlm = WorkflowTypeTextE2eLlm()
        val runtime = MinimalAgentRuntime(
            ledger = runRepository,
            toolRegistry = registry,
            llm = scriptedLlm,
            approvalGate = WorkflowDeviceActionApprovalGate(
                conversationId = E2E_TYPE_TEXT_CONVERSATION_ID,
                userIntent = "在当前安全输入框输入 ${WORKFLOW_TYPE_TEXT_INPUT.length} 个字符",
                fallback = AutoApprovalGate(),
                persistence = RoomWorkflowDeviceActionApprovalPersistence(runRepository),
                overlayRequester = DeviceAccessibilityRuntime,
            ),
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = processSessionId,
        )
        Log.i(TAG, "workflow-type-text-overlay waiting=true")
        val summary = runtime.run(
            conversationId = E2E_TYPE_TEXT_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-type-text-${System.currentTimeMillis()}",
            goal = "在当前安全输入框输入普通文本并确认精确回读",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-type-text",
                workflowStepId = "workflow-step-redmi-type-text",
                userIntent = "在当前安全输入框输入 ${WORKFLOW_TYPE_TEXT_INPUT.length} 个字符",
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow type_text Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow type_text Run 未完成：${detail.snapshot.run.status}"
        }
        val approval = detail.approvals.single { it.toolName == DEVICE_TYPE_TEXT_TOOL_NAME }
        check(approval.status == ApprovalRequestStatus.APPROVED) {
            "Room type_text 审批不是 APPROVED：${approval.status}"
        }
        check(
            approval.arguments["text_length"] == WORKFLOW_TYPE_TEXT_INPUT.length.toString() &&
                approval.arguments["text_sha256"]?.length == 64 &&
                "text" !in approval.arguments &&
                !approval.toString().contains(WORKFLOW_TYPE_TEXT_INPUT),
        ) { "Room type_text 审批没有保持无原文边界：${approval.arguments.keys}" }
        val typeTextCall = detail.toolLedger.calls.single { it.toolName == DEVICE_TYPE_TEXT_TOOL_NAME }
        check(
            typeTextCall.arguments == approval.arguments &&
                "text" !in typeTextCall.arguments &&
                !typeTextCall.toString().contains(WORKFLOW_TYPE_TEXT_INPUT),
        ) { "Room type_text ToolCall 审计没有保持无原文边界：${typeTextCall.arguments.keys}" }
        val typeTextResult = detail.toolLedger.results.single { it.toolName == DEVICE_TYPE_TEXT_TOOL_NAME }
        check(
            typeTextResult.success &&
                typeTextResult.executorVerified == true &&
                typeTextResult.verificationStatus == ToolVerificationStatus.PASSED,
        ) { "Tool Ledger 未保存通过验证的 type_text：$typeTextResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(typeTextResult.content)) {
            "Workflow type_text 没有返回严格白名单结果"
        }
        check(
            !typeTextResult.content.contains(WORKFLOW_TYPE_TEXT_INPUT) &&
                !typeTextResult.content.contains(scriptedLlm.snapshotId) &&
                !typeTextResult.content.contains(scriptedLlm.ref)
        ) { "Workflow type_text 结果泄露原文或 snapshot/ref" }
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = listOf(
                WorkflowDeviceActionEvidenceInput(
                    runId = typeTextResult.runId,
                    toolName = typeTextResult.toolName,
                    content = typeTextResult.content,
                    success = typeTextResult.success,
                    executorVerified = typeTextResult.executorVerified,
                    verified = typeTextResult.verificationStatus == ToolVerificationStatus.PASSED,
                ),
            ),
        )
        val decision = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions?.singleOrNull()
            ?: error("Workflow type_text 未形成答案级本地判定：$resolution")
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        check(!prompt.contains(WORKFLOW_TYPE_TEXT_INPUT) && prompt.contains("输入内容未进入答案级证据")) {
            "Workflow type_text 答案级判定隐私摘要不完整"
        }

        val postSnapshot = captureWhenReady(controller).snapshot
        val exactReadBack = postSnapshot.nodes.singleOrNull { node ->
            node.hint == WORKFLOW_TYPE_TEXT_HINT && node.text == WORKFLOW_TYPE_TEXT_INPUT
        }
        check(exactReadBack != null) { "真实 Accessibility type_text 后没有在原安全输入框完成精确回读" }
        Log.i(
            TAG,
            "workflow-type-text-e2e success=true action=${actionEvidence.action} " +
                "verified=${actionEvidence.verified} approval=${approval.status} " +
                "answerDecision=${decision.status} exactReadBack=true afterNodes=${actionEvidence.afterNodeCount}",
        )
    }

    private suspend fun runWorkflowBack(context: Context) {
        context.startActivity(
            Intent(context, DevicePrivacyProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = AndroidDeviceAccessibilityGateway(context),
        )
        awaitDeviceReady(controller)
        awaitProbeWindow(controller)
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY,
            ),
        )
        // long: 应用详情以独立 document task 打开，使一次 back 必然退出系统设置并暴露下方的小灵验收页。
        awaitStablePackageWindow(controller, SYSTEM_SETTINGS_PACKAGE)

        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val runRepository = RoomAgentRunRepository(context)
        val scriptedLlm = WorkflowBackE2eLlm()
        val runtime = MinimalAgentRuntime(
            ledger = runRepository,
            toolRegistry = registry,
            llm = scriptedLlm,
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    error("SAFE device.back 不应请求 Room 或浮层审批")
                }
            },
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = "process-redmi-workflow-back",
        )
        val summary = runtime.run(
            conversationId = E2E_BACK_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-back-${System.currentTimeMillis()}",
            goal = "返回小灵的上一个页面并确认后置界面",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-back",
                workflowStepId = "workflow-step-redmi-back",
                userIntent = "从系统设置返回小灵页面",
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow back Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow back Run 未完成：${detail.snapshot.run.status}"
        }
        check(detail.approvals.none { it.toolName == DEVICE_BACK_TOOL_NAME }) {
            "SAFE device.back 不得创建 Room Approval：${detail.approvals.map { it.toolName }}"
        }
        val backCall = detail.toolLedger.calls.single { it.toolName == DEVICE_BACK_TOOL_NAME }
        check(backCall.arguments.isEmpty() && backCall.risk == ToolRisk.SAFE) {
            "Workflow back ToolCall 必须保持空参数与 SAFE 风险：$backCall"
        }
        val backResult = detail.toolLedger.results.single { it.toolName == DEVICE_BACK_TOOL_NAME }
        check(
            backResult.success &&
                backResult.executorVerified == true &&
                backResult.verificationStatus == ToolVerificationStatus.PASSED,
        ) { "Tool Ledger 未保存通过验证的 back：$backResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(backResult.content)) {
            "Workflow back 没有返回严格白名单结果"
        }
        check(
            actionEvidence.action == "back" &&
                actionEvidence.beforePackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence.afterPackageName == context.packageName &&
                actionEvidence.verified
        ) { "Workflow back 前后窗口或验证结果不符合预期：$actionEvidence" }
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = listOf(
                WorkflowDeviceActionEvidenceInput(
                    runId = backResult.runId,
                    toolName = backResult.toolName,
                    content = backResult.content,
                    success = backResult.success,
                    executorVerified = backResult.executorVerified,
                    verified = backResult.verificationStatus == ToolVerificationStatus.PASSED,
                ),
            ),
        )
        val decision = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions?.singleOrNull()
            ?: error("Workflow back 未形成答案级本地判定：$resolution")
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        check(prompt.contains("已执行并验证 返回") && prompt.contains("不确认用户最终业务目标")) {
            "Workflow back 答案级边界不完整"
        }
        val postSnapshot = captureWhenReady(controller).snapshot
        check(postSnapshot.packageName == context.packageName) {
            "真实 Accessibility back 后没有回到小灵页面：${postSnapshot.packageName}"
        }
        Log.i(
            TAG,
            "workflow-back-e2e success=true action=${actionEvidence.action} verified=${actionEvidence.verified} " +
                "approvals=${detail.approvals.size} beforePackage=${actionEvidence.beforePackageName} " +
                "afterPackage=${actionEvidence.afterPackageName} answerDecision=${decision.status}",
        )
    }

    private suspend fun runWorkflowHome(context: Context) {
        context.startActivity(
            Intent(context, DevicePrivacyProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val gateway = AndroidDeviceAccessibilityGateway(context)
        val controller = DeviceObservationController(
            agentEnabled = { true },
            gateway = gateway,
        )
        awaitDeviceReady(controller)
        awaitProbeWindow(controller)
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        awaitStablePackageWindow(controller, SYSTEM_SETTINGS_PACKAGE)

        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val runRepository = RoomAgentRunRepository(context)
        val runtime = MinimalAgentRuntime(
            ledger = runRepository,
            toolRegistry = registry,
            llm = WorkflowHomeE2eLlm(),
            approvalGate = object : ApprovalGate {
                override suspend fun requestApproval(
                    runId: String,
                    toolCall: ToolCall,
                    definition: ToolDefinition,
                ): ApprovalDecision {
                    error("SAFE device.home 不应请求 Room 或浮层审批")
                }
            },
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = "process-redmi-workflow-home",
        )
        val summary = runtime.run(
            conversationId = E2E_HOME_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-home-${System.currentTimeMillis()}",
            goal = "从系统设置返回 Android 桌面并确认 launcher 窗口",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-home",
                workflowStepId = "workflow-step-redmi-home",
                userIntent = "从系统设置返回 Android 桌面",
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow home Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow home Run 未完成：${detail.snapshot.run.status}"
        }
        check(detail.approvals.none { it.toolName == DEVICE_HOME_TOOL_NAME }) {
            "SAFE device.home 不得创建 Room Approval：${detail.approvals.map { it.toolName }}"
        }
        val homeCall = detail.toolLedger.calls.single { it.toolName == DEVICE_HOME_TOOL_NAME }
        check(homeCall.arguments.isEmpty() && homeCall.risk == ToolRisk.SAFE) {
            "Workflow home ToolCall 必须保持空参数与 SAFE 风险：$homeCall"
        }
        val homeResult = detail.toolLedger.results.single { it.toolName == DEVICE_HOME_TOOL_NAME }
        check(
            homeResult.success &&
                homeResult.executorVerified == true &&
                homeResult.verificationStatus == ToolVerificationStatus.PASSED,
        ) { "Tool Ledger 未保存通过验证的 home：$homeResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(homeResult.content)) {
            "Workflow home 没有返回严格白名单结果"
        }
        // long: Redmi 与其他设备的 launcher 包名可能不同，验收必须复用系统 CATEGORY_HOME 解析结果，不能把测试机桌面实现写死。
        check(
            actionEvidence.action == "home" &&
                actionEvidence.beforePackageName == SYSTEM_SETTINGS_PACKAGE &&
                gateway.isHomePackage(actionEvidence.afterPackageName) &&
                actionEvidence.verified
        ) { "Workflow home 前后窗口或 launcher 验证结果不符合预期：$actionEvidence" }
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = listOf(
                WorkflowDeviceActionEvidenceInput(
                    runId = homeResult.runId,
                    toolName = homeResult.toolName,
                    content = homeResult.content,
                    success = homeResult.success,
                    executorVerified = homeResult.executorVerified,
                    verified = homeResult.verificationStatus == ToolVerificationStatus.PASSED,
                ),
            ),
        )
        val decision = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions?.singleOrNull()
            ?: error("Workflow home 未形成答案级本地判定：$resolution")
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        check(prompt.contains("已执行并验证 返回桌面") && prompt.contains("不确认用户最终业务目标")) {
            "Workflow home 答案级边界不完整"
        }
        val postSnapshot = captureWhenReady(controller).snapshot
        check(gateway.isHomePackage(postSnapshot.packageName)) {
            "真实 Accessibility home 后没有进入 launcher：${postSnapshot.packageName}"
        }
        Log.i(
            TAG,
            "workflow-home-e2e success=true action=${actionEvidence.action} verified=${actionEvidence.verified} " +
                "approvals=${detail.approvals.size} beforePackage=${actionEvidence.beforePackageName} " +
                "afterPackage=${actionEvidence.afterPackageName} answerDecision=${decision.status}",
        )
    }

    private suspend fun awaitDeviceReady(controller: DeviceObservationController) {
        repeat(50) {
            if (controller.health() == DeviceAgentHealthState.READY) return
            delay(100)
        }
        error("Redmi 无障碍服务未进入 READY：${controller.health()}")
    }

    private suspend fun awaitProbeWindow(controller: DeviceObservationController) {
        repeat(50) {
            when (val capture = controller.capture()) {
                is DeviceSnapshotCapture.Success -> {
                    if (capture.snapshot.nodes.any { node -> node.text == "安全按钮" }) return
                }
                is DeviceSnapshotCapture.Failed -> {
                    if (capture.reason !in TRANSIENT_SNAPSHOT_FAILURES) error(capture.message)
                }
            }
            delay(100)
        }
        error("Probe 前台窗口在限定时间内没有出现安全按钮")
    }

    private suspend fun awaitTypeTextProbeWindow(controller: DeviceObservationController) {
        repeat(50) {
            when (val capture = controller.capture()) {
                is DeviceSnapshotCapture.Success -> {
                    if (capture.snapshot.nodes.any { node -> node.hint == WORKFLOW_TYPE_TEXT_HINT }) return
                }
                is DeviceSnapshotCapture.Failed -> {
                    if (capture.reason !in TRANSIENT_SNAPSHOT_FAILURES) error(capture.message)
                }
            }
            delay(100)
        }
        error("type_text Probe 前台窗口在限定时间内没有出现安全输入框")
    }

    private suspend fun awaitStablePackageWindow(controller: DeviceObservationController, packageName: String) {
        var stableGeneration: Long? = null
        var stableSamples = 0
        repeat(50) {
            when (val capture = controller.capture()) {
                is DeviceSnapshotCapture.Success -> {
                    if (capture.snapshot.packageName == packageName) {
                        val generation = capture.snapshot.windowGeneration
                        stableSamples = if (generation == stableGeneration) stableSamples + 1 else 1
                        stableGeneration = generation
                        // long: 系统设置首帧仍可能处于转场和内容刷新期；连续稳定后再让 Runtime 取动作前快照，避免 Debug tracer 使用过渡窗口代际。
                        if (stableSamples >= 3) return
                    } else {
                        stableGeneration = null
                        stableSamples = 0
                    }
                }
                is DeviceSnapshotCapture.Failed -> {
                    if (capture.reason !in TRANSIENT_SNAPSHOT_FAILURES) error(capture.message)
                    stableGeneration = null
                    stableSamples = 0
                }
            }
            delay(150)
        }
        error("限定时间内前台窗口未稳定：$packageName")
    }

    private suspend fun captureWhenReady(controller: DeviceObservationController): DeviceSnapshotCapture.Success {
        repeat(30) {
            when (val capture = controller.capture()) {
                is DeviceSnapshotCapture.Success -> return capture
                is DeviceSnapshotCapture.Failed -> {
                    if (capture.reason !in TRANSIENT_SNAPSHOT_FAILURES) error(capture.message)
                }
            }
            delay(100)
        }
        error("Workflow 设备动作后无法再次获取真实 Accessibility snapshot")
    }

    private class WorkflowTapRefE2eLlm : AgentLlm {
        lateinit var snapshotId: String
            private set
        lateinit var ref: String
            private set

        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
            val snapshot = tools.single { it.name == DEVICE_SNAPSHOT_E2E_TOOL_NAME }
            return ToolCall(name = snapshot.name, arguments = emptyMap(), risk = snapshot.risk)
        }

        override suspend fun proposeNextAction(
            goal: String,
            tools: List<ToolDefinition>,
            completedTools: List<AgentToolExecution>,
        ): AgentPlanDecision {
            if (completedTools.isEmpty()) {
                return AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
            }
            if (completedTools.size == 1) {
                val snapshotResult = completedTools.single().toolResult
                check(snapshotResult.success) { snapshotResult.content }
                val snapshotJson = JSONObject(snapshotResult.content)
                val nodes = snapshotJson.getJSONArray("nodes")
                val button = (0 until nodes.length())
                    .map(nodes::getJSONObject)
                    .single { node -> node.optString("text") == "安全按钮" }
                snapshotId = snapshotJson.getString("snapshot_id")
                ref = button.getString("ref")
                val tap = tools.single { it.name == DEVICE_TAP_REF_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = tap.name,
                        arguments = mapOf("snapshot_id" to snapshotId, "ref" to ref),
                        risk = tap.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 设备动作已完成真实执行与后置验证"
    }

    private class WorkflowTypeTextE2eLlm : AgentLlm {
        lateinit var snapshotId: String
            private set
        lateinit var ref: String
            private set

        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
            val snapshot = tools.single { it.name == DEVICE_SNAPSHOT_E2E_TOOL_NAME }
            return ToolCall(name = snapshot.name, arguments = emptyMap(), risk = snapshot.risk)
        }

        override suspend fun proposeNextAction(
            goal: String,
            tools: List<ToolDefinition>,
            completedTools: List<AgentToolExecution>,
        ): AgentPlanDecision {
            if (completedTools.isEmpty()) return AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
            if (completedTools.size == 1) {
                val snapshotResult = completedTools.single().toolResult
                check(snapshotResult.success) { snapshotResult.content }
                val snapshotJson = JSONObject(snapshotResult.content)
                val nodes = snapshotJson.getJSONArray("nodes")
                val input = (0 until nodes.length())
                    .map(nodes::getJSONObject)
                    .single { node ->
                        node.optString("hint") == WORKFLOW_TYPE_TEXT_HINT &&
                            node.optJSONArray("actions")?.let { actions ->
                                (0 until actions.length()).any { actions.getString(it) == "type_text" }
                            } == true
                    }
                snapshotId = snapshotJson.getString("snapshot_id")
                ref = input.getString("ref")
                val typeText = tools.single { it.name == DEVICE_TYPE_TEXT_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = typeText.name,
                        arguments = mapOf(
                            "snapshot_id" to snapshotId,
                            "ref" to ref,
                            "text" to WORKFLOW_TYPE_TEXT_INPUT,
                        ),
                        risk = typeText.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 文本输入已完成真实执行与精确回读"
    }

    private class WorkflowBackE2eLlm : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
            val snapshot = tools.single { it.name == DEVICE_SNAPSHOT_E2E_TOOL_NAME }
            return ToolCall(name = snapshot.name, arguments = emptyMap(), risk = snapshot.risk)
        }

        override suspend fun proposeNextAction(
            goal: String,
            tools: List<ToolDefinition>,
            completedTools: List<AgentToolExecution>,
        ): AgentPlanDecision {
            if (completedTools.isEmpty()) return AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
            if (completedTools.size == 1) {
                val snapshotResult = completedTools.single().toolResult
                check(snapshotResult.success) { snapshotResult.content }
                check(JSONObject(snapshotResult.content).getString("package") == SYSTEM_SETTINGS_PACKAGE) {
                    "Workflow back 动作前 snapshot 不属于系统设置"
                }
                val back = tools.single { it.name == DEVICE_BACK_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = back.name,
                        arguments = emptyMap(),
                        risk = back.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 返回动作已完成真实执行与后置观察"
    }

    private class WorkflowHomeE2eLlm : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
            val snapshot = tools.single { it.name == DEVICE_SNAPSHOT_E2E_TOOL_NAME }
            return ToolCall(name = snapshot.name, arguments = emptyMap(), risk = snapshot.risk)
        }

        override suspend fun proposeNextAction(
            goal: String,
            tools: List<ToolDefinition>,
            completedTools: List<AgentToolExecution>,
        ): AgentPlanDecision {
            if (completedTools.isEmpty()) return AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
            if (completedTools.size == 1) {
                val snapshotResult = completedTools.single().toolResult
                check(snapshotResult.success) { snapshotResult.content }
                check(JSONObject(snapshotResult.content).getString("package") == SYSTEM_SETTINGS_PACKAGE) {
                    "Workflow home 动作前 snapshot 不属于系统设置"
                }
                val home = tools.single { it.name == DEVICE_HOME_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = home.name,
                        arguments = emptyMap(),
                        risk = home.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 返回桌面动作已完成真实执行与 launcher 观察"
    }

    companion object {
        const val ACTION_E2E = "com.longdev.xiaoling.debug.AGENT_E2E"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ALLOWED_TOOL = "allowed_tool"
        const val OPERATION_SETUP = "setup"
        const val OPERATION_STATUS = "status"
        const val OPERATION_WORKFLOW_TAP_REF = "workflow_tap_ref"
        const val OPERATION_WORKFLOW_TYPE_TEXT = "workflow_type_text"
        const val OPERATION_WORKFLOW_BACK = "workflow_back"
        const val OPERATION_WORKFLOW_HOME = "workflow_home"
        private const val DEFAULT_ALLOWED_TOOL = "device.open_app"
        private const val PROVIDER_ID = "stage3-device-e2e-provider"
        private const val AGENT_PROFILE_ID = "stage3-device-e2e-profile"
        private const val E2E_CONVERSATION_ID = "conversation-redmi-workflow-device-action"
        private const val E2E_TYPE_TEXT_CONVERSATION_ID = "conversation-redmi-workflow-type-text"
        private const val E2E_BACK_CONVERSATION_ID = "conversation-redmi-workflow-back"
        private const val E2E_HOME_CONVERSATION_ID = "conversation-redmi-workflow-home"
        private const val DEVICE_SNAPSHOT_E2E_TOOL_NAME = "device.snapshot"
        private const val DEVICE_BACK_TOOL_NAME = "device.back"
        private const val DEVICE_HOME_TOOL_NAME = "device.home"
        private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
        private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
        private const val WORKFLOW_TYPE_TEXT_HINT = "Workflow 安全文本输入框"
        private const val WORKFLOW_TYPE_TEXT_INPUT = "stage117_safe_text"
        private const val TAG = "XiaoLingAgentE2e"
        private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val TRANSIENT_SNAPSHOT_FAILURES = setOf(
            DeviceSnapshotFailure.NO_ACTIVE_WINDOW,
            DeviceSnapshotFailure.WINDOW_CHANGED,
        )
    }
}
