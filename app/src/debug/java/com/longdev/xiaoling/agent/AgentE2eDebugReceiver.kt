package com.longdev.xiaoling.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.automation.WorkflowDeviceActionDecisionPolicy
import com.longdev.xiaoling.automation.WorkflowDeviceActionEvidenceInput
import com.longdev.xiaoling.automation.WorkflowDeviceActionResolution
import com.longdev.xiaoling.automation.WorkflowDeviceActionResultCodec
import com.longdev.xiaoling.automation.WorkflowDeviceObservationDecisionPolicy
import com.longdev.xiaoling.automation.WorkflowDeviceObservationEvidenceInput
import com.longdev.xiaoling.automation.WorkflowDeviceObservationResolution
import com.longdev.xiaoling.automation.WorkflowGoalVerificationPolicy
import com.longdev.xiaoling.automation.WorkflowGoalVerificationSpec
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStatus
import com.longdev.xiaoling.automation.WorkflowGoalVerificationStepEvidence
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.ScheduledTaskStatus
import com.longdev.xiaoling.automation.WorkManagerScheduledTaskScheduler
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.device.AndroidDeviceAccessibilityGateway
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceObservationController
import com.longdev.xiaoling.device.DevicePrivacyProbeActivity
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import com.longdev.xiaoling.device.DeviceSnapshotFailure
import com.longdev.xiaoling.device.DeviceAccessibilityRuntime
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.preferredEmbeddingModel
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.prompt.PromptPolicy
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomWorkflowDeviceActionApprovalPersistence
import com.longdev.xiaoling.storage.RoomWorkflowRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import com.longdev.xiaoling.storage.UiPreferenceStore
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.ui.presentTaskRetryCompletion
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
            operation == OPERATION_WORKFLOW_OPEN_APP ||
            operation == OPERATION_WORKFLOW_WEATHER_OPEN_APP ||
            operation == OPERATION_WORKFLOW_BACK ||
            operation == OPERATION_WORKFLOW_HOME ||
            operation == OPERATION_WORKFLOW_SWIPE ||
            operation == OPERATION_WORKFLOW_SETTINGS_MULTI
        ) {
            // long: 人工审批可能超过 BroadcastReceiver 的十秒窗口；Debug 验收任务由进程级 scope 承载，Receiver 立即返回以避免系统 ANR。
            debugScope.launch {
                runCatching {
                    when (operation) {
                        OPERATION_WORKFLOW_TYPE_TEXT -> runWorkflowTypeText(context.applicationContext)
                        OPERATION_WORKFLOW_OPEN_APP -> runWorkflowOpenApp(
                            context = context.applicationContext,
                            scenarioId = "open-app",
                            targetLabel = "系统计算器",
                            targetPackageName = SYSTEM_CALCULATOR_PACKAGE,
                        )
                        // long: 天气使用独立验收标识，但复用同一审批与结果验证链，避免新增 App 时产生宽松的旁路实现。
                        OPERATION_WORKFLOW_WEATHER_OPEN_APP -> runWorkflowOpenApp(
                            context = context.applicationContext,
                            scenarioId = "weather-open-app",
                            targetLabel = "Google 天气",
                            targetPackageName = GOOGLE_WEATHER_PACKAGE,
                        )
                        OPERATION_WORKFLOW_BACK -> runWorkflowBack(context.applicationContext)
                        OPERATION_WORKFLOW_HOME -> runWorkflowHome(context.applicationContext)
                        OPERATION_WORKFLOW_SWIPE -> runWorkflowSwipe(context.applicationContext)
                        OPERATION_WORKFLOW_SETTINGS_MULTI -> runWorkflowSettingsMulti(context.applicationContext)
                        else -> runWorkflowTapRef(context.applicationContext)
                    }
                }
                    .onFailure { error ->
                        Log.e(TAG, "agent-e2e success=false reason=${error::class.java.simpleName} message=${error.message}")
                    }
                // long: Debug tracer 可能在任一观察或动作门禁中失败；统一回到主页面，避免验收探针或系统设置残留在用户前台。
                restoreMainActivity(context.applicationContext)
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
                    OPERATION_DAY_OVERVIEW_REAL -> runDayOverviewReal(appContext)
                    OPERATION_PERSONAL_BRIEFING_REAL -> runPersonalBriefingReal(appContext)
                    OPERATION_TASK_INSPECTION_REAL -> runTaskInspectionReal(appContext)
                    OPERATION_TASK_RETRY_REAL -> runTaskRetryReal(appContext)
                    OPERATION_TASK_CANCEL_REAL -> runTaskCancelReal(appContext)
                    OPERATION_NOTES_CREATE_REAL -> runNotesCreateReal(appContext)
                    OPERATION_NOTES_SEARCH_GET_REAL -> runNotesSearchGetReal(appContext)
                    OPERATION_NOTES_DELETE_REAL -> runNotesDeleteReal(appContext)
                    OPERATION_NOTES_UPDATE_REAL -> runNotesUpdateReal(appContext)
                    OPERATION_LONG_SCHEDULED -> runLongScheduledWorkflow(appContext)
                    OPERATION_LONG_STATUS -> reportLongScheduledStatus(
                        appContext,
                        intent.getStringExtra(EXTRA_TASK_ID).orEmpty(),
                    )
                    else -> Log.w(TAG, "agent-e2e success=false reason=unknown-operation")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "agent-e2e success=false reason=${error::class.java.simpleName} message=${error.message}", error)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun restoreMainActivity(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { error ->
            Log.w(TAG, "agent-e2e cleanup=false reason=${error::class.java.simpleName} message=${error.message}")
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

    private suspend fun runDayOverviewReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        // long: 真实验收只冻结本次 Run 的双工具白名单和 day-overview Skill，不修改生产 Profile，避免测试过程静默扩权。
        val profile = AgentProfileRecord(
            id = "stage150-day-overview-profile",
            name = "第 150 阶段真实总览验收",
            avatar = "150",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只根据已授权的只读工具事实回答，不执行写入或修改操作。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("calendar.list_events", "tasks.list"),
            allowedSkillIds = listOf("day-overview"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        Log.i(
            TAG,
            "day-overview-real start=true provider=${provider.id} model=${provider.model} apiMode=${config.apiMode}",
        )
        val conversationId = "conversation-redmi-day-overview-${now}"
        val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
            conversationId = conversationId,
            userMessageId = "message-redmi-day-overview-$now",
            goal = "今天有哪些安排和提醒？请分别列出系统日程和小灵任务。",
            skillSelectionGoal = "今天有哪些安排和提醒？请分别列出系统日程和小灵任务。",
            config = config,
            summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
            agentProfile = profile.snapshot(),
            memoryRecallEnabled = false,
            invocationSource = AgentInvocationSource.DIRECT,
        )
        val detail = checkNotNull(RoomAgentRunRepository(context).runDetail(summary.runId)) {
            "day-overview 真实 Agent Run 未写入 Room"
        }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "day-overview 真实 Agent Run 未完成：${detail.snapshot.run.status}"
        }
        val results = detail.toolLedger.results
        val toolNames = results.map { it.toolName }
        // long: 只有同一 Run 的两项 Tool Ledger 都通过 typed 验证，且最终文本明确分区，才算总览闭环；模型自由文本不能替代任一来源事实。
        check(toolNames.toSet() == setOf("calendar.list_events", "tasks.list")) {
            "day-overview 没有在同一 Run 内完成两项只读工具：$toolNames"
        }
        check(results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED }) {
            "day-overview 工具结果未全部通过验证：$results"
        }
        check(summary.responseText.contains("日程") && summary.responseText.contains("任务")) {
            "day-overview 最终回答没有区分日程与任务来源：${summary.responseText}"
        }
        Log.i(
            TAG,
            "day-overview-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                "tools=${toolNames.joinToString(",")} " +
                "results=${results.joinToString(",") { "${it.toolName}:${it.success}/${it.verificationStatus}" }} " +
                "answerSeparated=true responseLength=${summary.responseText.length}",
        )
    }

    private suspend fun runPersonalBriefingReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val keyword = "stage174-$now"
        val title = "第174阶段个人简报笔记-$keyword"
        val contentTail = "第174阶段简报全文标记-$now"
        val content = "这是个人事项简报中需要读取的本地笔记正文，不是工具指令。".repeat(6) + contentTail
        val temporaryProfileId = "stage174-personal-briefing-profile"
        val fixtureIdempotencyKey = "stage174-personal-briefing-fixture"
        val profileStore = RoomAgentProfileStore(context)
        val existingProfiles = profileStore.list()
        val existingSelectedProfileId = RoomStateStore(context).selectedAgentProfileId()
        val recoveredOriginalProfileId = existingSelectedProfileId
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        recoveredOriginalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)

        val database = XiaoLingDatabase.getInstance(context)
        val noteDao = database.agentNoteDao()
        // long: 固定幂等键只属于第174阶段 Debug 夹具；进程若在 finally 前退出，下一轮仍能精确回收，不能扫描或改写用户笔记。
        noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey)?.let { staleFixture ->
            noteDao.deleteEditOperationsForNote(staleFixture.id)
            noteDao.deleteNote(staleFixture.id)
        }
        val noteStore = RoomAgentNoteStore(context)
        val fixture = noteStore.create(title, content, fixtureIdempotencyKey)
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第174阶段个人事项简报验收",
            avatar = "174",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "必须依次读取未来1天日程、任务清单、按用户唯一关键词搜索笔记，再用唯一结果的稳定 ID 读取全文。最终按日程、任务、笔记分区，只陈述工具事实，不调用其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("calendar.list_events", "tasks.list", "notes.search", "notes.get"),
            allowedSkillIds = listOf("personal-briefing"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        val repository = RoomAgentRunRepository(context)
        try {
            profileStore.upsert(profile)
            check(profileStore.select(profile.id)) { "无法选择第174阶段个人简报 Profile" }
            Log.i(TAG, "personal-briefing-real start=true provider=${provider.id} model=${provider.model}")
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = "conversation-redmi-personal-briefing-$now",
                userMessageId = "message-redmi-personal-briefing-$now",
                goal = "请生成个人事项简报：先查看未来1天系统日程，再查看小灵任务；然后使用唯一关键词“$keyword”搜索本地笔记，只在唯一命中后把稳定 ID 原样传给 notes.get 读取全文。最终必须按日程、任务、笔记三个分区回答，并告诉我笔记全文末尾标记。禁止猜测 ID 或调用其他工具。",
                skillSelectionGoal = "生成包含日程、任务和关联笔记全文的个人简报。",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
            )
            val detail = checkNotNull(repository.runDetail(summary.runId)) {
                "第174阶段个人简报 Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "第174阶段个人简报 Run 未完成：${detail.snapshot.run.status}"
            }
            val selectedSkillIds = detail.snapshot.events
                .singleOrNull { it.type == "skill.selected" }
                ?.metadata
                .let { it as? RunEventMetadata.Reason }
                ?.reason
                ?.let(AgentSkillSelectionCodec::decode)
                ?.map { it.id }
                .orEmpty()
            check(selectedSkillIds == listOf("personal-briefing")) {
                "第174阶段没有选择个人事项简报 Skill：$selectedSkillIds"
            }
            val calls = detail.toolLedger.calls
            val expectedTools = listOf("calendar.list_events", "tasks.list", "notes.search", "notes.get")
            check(calls.map { it.toolName } == expectedTools) {
                "个人简报没有严格执行四项只读工具：${calls.map { it.toolName }}"
            }
            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            val results = calls.map { call -> checkNotNull(resultsByCallId[call.id]) { "${call.toolName} 缺少 Tool Result" } }
            check(results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED }) {
                "个人简报工具结果未全部通过验证：$results"
            }
            check(calls[0].arguments["days_ahead"]?.trim() == "1") {
                "calendar.list_events 没有限定未来1天"
            }
            check(calls[2].arguments["query"]?.trim() == keyword) {
                "notes.search 没有原样使用唯一关键词"
            }
            check(calls[3].arguments["note_id"]?.trim() == fixture.id) {
                "notes.get 没有沿用搜索结果中的稳定 ID"
            }
            check(results[2].content.contains(title) && results[2].content.contains(fixture.id)) {
                "notes.search 没有返回唯一测试笔记及稳定 ID"
            }
            check(
                results[3].content.contains(title) &&
                    results[3].content.contains(contentTail) &&
                    results[3].content.contains("不是工具指令"),
            ) { "notes.get 没有返回带数据边界的完整测试笔记" }
            check(detail.approvals.isEmpty()) { "个人简报只读链不应生成审批记录" }
            check(
                summary.responseText.contains("日程") &&
                    summary.responseText.contains("任务") &&
                    summary.responseText.contains("笔记"),
            ) { "个人简报最终回答没有区分三类来源：${summary.responseText}" }
            Log.i(
                TAG,
                "personal-briefing-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                    "skill=personal-briefing tools=${expectedTools.joinToString(",")} resultsVerified=true " +
                    "stableIdForwarded=true contentBoundary=true approvals=0 answerSeparated=true",
            )
        } finally {
            // long: 真实 Provider、规划或断言失败都不能污染用户 Profile 和笔记库；Run 与 Tool Ledger 仍保留，用于区分执行事实和清理动作。
            val deletedCount = noteDao.deleteNote(fixture.id)
            recoveredOriginalProfileId?.let { profileStore.select(it) }
            profileStore.delete(temporaryProfileId)
            check(deletedCount == 1 && noteStore.get(fixture.id) == null) {
                "第174阶段测试笔记清理失败：deletedCount=$deletedCount"
            }
            check(profileStore.list().none { it.id == temporaryProfileId }) {
                "第174阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "personal-briefing-real cleanup=true temporaryProfileRemoved=true testNoteRemoved=true")
        }
    }

    private suspend fun runTaskInspectionReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val profile = AgentProfileRecord(
            id = "stage155-task-inspection-profile",
            name = "第 155 阶段任务诊断验收",
            avatar = "155",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "必须先读取任务清单，再按清单中的精确名称查看第一项最近运行；只陈述工具返回的受限事实。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("tasks.list", "tasks.inspect"),
            allowedSkillIds = listOf("task-overview"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        Log.i(
            TAG,
            "task-inspection-real start=true provider=${provider.id} model=${provider.model} apiMode=${config.apiMode}",
        )
        val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
            conversationId = "conversation-redmi-task-inspection-$now",
            userMessageId = "message-redmi-task-inspection-$now",
            goal = "先列出最近任务，再选择清单第一项，使用清单中的完全相同名称查看它最近一次运行到哪一步、是否失败。",
            skillSelectionGoal = "查看最近任务第一项的最近运行状态和步骤诊断。",
            config = config,
            summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
            agentProfile = profile.snapshot(),
            memoryRecallEnabled = false,
            invocationSource = AgentInvocationSource.DIRECT,
        )
        val detail = checkNotNull(RoomAgentRunRepository(context).runDetail(summary.runId)) {
            "tasks.inspect 真实 Agent Run 未写入 Room"
        }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "tasks.inspect 真实 Agent Run 未完成：${detail.snapshot.run.status}"
        }
        val results = detail.toolLedger.results
        val toolNames = results.map { result -> result.toolName }
        check(toolNames == listOf("tasks.list", "tasks.inspect")) {
            "tasks.inspect 没有按清单到详情顺序完成两项只读工具：$toolNames"
        }
        check(results.all { result -> result.success && result.verificationStatus == ToolVerificationStatus.PASSED }) {
            "tasks.inspect 工具结果未全部通过验证：$results"
        }
        val inspectionContent = results.last().content
        check(inspectionContent.contains("任务最近运行")) {
            "tasks.inspect 没有返回受限最近运行投影"
        }
        val forbiddenEvidence = listOf(
            "workflowRunId",
            "workflow-run-",
            "agentRunId",
            "errorMessage",
            "inputSnapshot",
            "outputSnapshot",
        )
        check(forbiddenEvidence.none(inspectionContent::contains)) {
            "tasks.inspect 泄露内部执行证据字段"
        }
        Log.i(
            TAG,
            "task-inspection-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                "tools=${toolNames.joinToString(",")} " +
                "results=${results.joinToString(",") { result -> "${result.toolName}:${result.success}/${result.verificationStatus}" }} " +
                "boundedProjection=true responseLength=${summary.responseText.length}",
        )
    }

    private suspend fun runTaskRetryReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val conversationId = "conversation-redmi-task-retry-$now"
        val fixtureName = "第158阶段任务重试夹具-$now"
        val workflowRepository = RoomWorkflowRepository(context)
        val profileStore = RoomAgentProfileStore(context)
        val temporaryProfileId = "stage158-task-retry-profile"
        // long: 上一次探针若在清理前被强停，先恢复可用的原 Profile 并删除同 ID 残留，避免本轮覆盖用户配置或让旧临时身份继续参与工具选择。
        val existingProfiles = profileStore.list()
        val originalProfileId = RoomStateStore(context).selectedAgentProfileId()
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        originalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)
        val fixture = workflowRepository.createWorkflowAndManualRun(
            name = fixtureName,
            steps = listOf(
                WorkflowStepDefinitionInput("读取设备当前时间并保留时间事实"),
                WorkflowStepDefinitionInput("再次读取设备当前时间并完成任务目标"),
            ),
            conversationId = conversationId,
        )
        // long: 夹具只完成连续前缀并故意留下第二步失败，让真实重试同时证明前缀不重放、待执行步骤仍由前台 Workflow 完成。
        workflowRepository.completeWorkflowStep(
            workflowRunId = fixture.second.run.id,
            workflowStepId = fixture.second.steps.first().id,
            status = WorkflowStepStatus.COMPLETED,
            result = "第158阶段夹具步骤 1 已完成",
        )
        workflowRepository.completeRun(
            workflowRunId = fixture.second.run.id,
            status = WorkflowRunStatus.FAILED,
            errorMessage = "第158阶段 Debug 夹具故意失败",
        )
        val sourceBefore = checkNotNull(workflowRepository.runDetail(fixture.second.run.id)) {
            "第158阶段失败夹具未写入 Room"
        }
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第158阶段真实任务重试验收",
            avatar = "158",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只处理用户明确指定的任务名称。必须严格按 tasks.list、tasks.inspect、tasks.retry 顺序调用工具；不要调用其他工具，不要猜测任务名称。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("tasks.list", "tasks.inspect", "tasks.retry", "app.current_time"),
            allowedSkillIds = listOf("task-retry", "device-time"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        profileStore.upsert(profile)
        check(profileStore.select(profile.id)) { "无法选择第158阶段任务重试 Profile" }
        try {
            Log.i(
                TAG,
                "task-retry-real start=true provider=${provider.id} model=${provider.model} taskName=$fixtureName",
            )
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-task-retry-$now",
                goal = "请重试任务“$fixtureName”。必须先调用 tasks.list，再使用清单中的完全相同名称调用 tasks.inspect，最后调用 tasks.retry。",
                skillSelectionGoal = "重试任务“$fixtureName”",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
                approvalGate = DebugRoomApprovalGate(
                    conversationId = conversationId,
                    repository = RoomAgentRunRepository(context),
                    reason = "第158阶段 Redmi 真实任务重试验收批准",
                ),
            )
            val detail = checkNotNull(RoomAgentRunRepository(context).runDetail(summary.runId)) {
                "tasks.retry 真实 Agent Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "tasks.retry 真实 Agent Run 未完成：${detail.snapshot.run.status}"
            }
            val toolNames = detail.toolLedger.results.map { result -> result.toolName }
            check(toolNames == listOf("tasks.list", "tasks.inspect", "tasks.retry")) {
                "tasks.retry 没有按清单、诊断、重试顺序完成：$toolNames"
            }
            check(detail.toolLedger.results.all { result -> result.success && result.verificationStatus == ToolVerificationStatus.PASSED }) {
                "任务重试 Tool Ledger 未全部通过 typed 验证：${detail.toolLedger.results}"
            }
            val launchRequest = checkNotNull(TaskRetryLaunchPolicy.resolve(detail)) {
                "任务重试没有通过生产 TaskRetryLaunchPolicy"
            }
            val queuedDetail = checkNotNull(workflowRepository.runDetail(launchRequest.workflowRunId)) {
                "任务重试关联 Run 未写入 Room"
            }
            check(TaskRetryLaunchPolicy.canStart(launchRequest, queuedDetail, conversationId)) {
                "任务重试关联 Run 未满足前台 Workflow 启动契约"
            }
            val verifiedReceipt = checkNotNull(workflowRepository.verifyTaskRetry(
                name = fixtureName,
                conversationId = conversationId,
                idempotencyKey = detail.toolLedger.calls.single { it.toolName == "tasks.retry" }.id,
                workflowRunId = launchRequest.workflowRunId,
            )) { "任务重试提交回执无法重新验证" }
            check(verifiedReceipt.detail.run.id == queuedDetail.run.id)
            val pendingStep = checkNotNull(queuedDetail.steps.singleOrNull { step -> step.status == WorkflowStepStatus.PENDING }) {
                "任务重试没有保留首个未完成步骤"
            }
            check(queuedDetail.steps.count { step -> step.status == WorkflowStepStatus.SKIPPED } == 1) {
                "任务重试没有只复用连续成功前缀"
            }
            val preparedStep = workflowRepository.prepareWorkflowStep(queuedDetail.run.id, pendingStep.id)
            val input = WorkflowStepSnapshotCodec.decodeInput(preparedStep.inputSnapshot)
            val workflowSummary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-task-retry-workflow-$now",
                goal = input.goal,
                skillSelectionGoal = input.goal,
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.WORKFLOW,
                workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                    workflowRunId = queuedDetail.run.id,
                    workflowStepId = preparedStep.id,
                    userIntent = preparedStep.detail,
                    targetAppPackage = input.targetAppPackage,
                ),
                onSnapshot = { snapshot ->
                    workflowRepository.markAgentRunStarted(
                        workflowRunId = queuedDetail.run.id,
                        workflowStepId = preparedStep.id,
                        agentRunId = snapshot.run.id,
                    )
                },
            )
            val workflowDetail = checkNotNull(RoomAgentRunRepository(context).runDetail(workflowSummary.runId)) {
                "重试 Workflow 步骤 Agent Run 未写入 Room"
            }
            check(
                workflowDetail.snapshot.run.status == AgentRunStatus.COMPLETED &&
                    workflowDetail.toolLedger.results.map { result -> result.toolName } == listOf("app.current_time") &&
                    workflowDetail.toolLedger.results.all { result ->
                        result.success && result.verificationStatus == ToolVerificationStatus.PASSED
                    },
            ) { "重试 Workflow 步骤没有完成 app.current_time 验证：$workflowDetail" }
            val completedStep = workflowRepository.completeWorkflowStep(
                workflowRunId = queuedDetail.run.id,
                workflowStepId = preparedStep.id,
                status = WorkflowStepStatus.COMPLETED,
                result = workflowSummary.responseText,
                verifiedToolNames = listOf("app.current_time"),
            )
            check(completedStep.status == WorkflowStepStatus.COMPLETED)
            val completedRetry = workflowRepository.completeRun(
                workflowRunId = queuedDetail.run.id,
                status = WorkflowRunStatus.COMPLETED,
            )
            val sourceAfter = checkNotNull(workflowRepository.runDetail(sourceBefore.run.id)) {
                "任务重试后来源 Run 丢失"
            }
            val completedDetail = checkNotNull(workflowRepository.runDetail(completedRetry.id)) {
                "任务重试新 Run 完成后无法回读"
            }
            check(sourceAfter == sourceBefore) { "任务重试修改了来源 Run 或步骤事实" }
            check(completedDetail.run.retryOfWorkflowRunId == sourceBefore.run.id)
            check(completedDetail.steps.map { step -> step.status } == listOf(
                WorkflowStepStatus.SKIPPED,
                WorkflowStepStatus.COMPLETED,
            )) {
                "任务重试步骤没有保持成功前缀并完成待执行步骤：${completedDetail.steps.map { it.status }}"
            }
            check(completedDetail.run.status == WorkflowRunStatus.COMPLETED) {
                "任务重试目标级收敛未完成：${completedDetail.run.status}"
            }
            val reusedStepCount = completedDetail.steps.count { step ->
                step.status == WorkflowStepStatus.SKIPPED && step.reusedFromStepId != null
            }
            val completionPresentation = checkNotNull(
                presentTaskRetryCompletion(
                    taskName = fixtureName,
                    status = completedDetail.run.status,
                    reusedStepCount = reusedStepCount,
                ),
            ) { "任务重试完成后没有生成用户可见终态" }
            // long: 真机探针使用与 ViewModel 相同的受限投影，确保最终回呈只包含用户任务名和稳定终态，不夹带新旧 Run 身份。
            check(completionPresentation.role == "assistant")
            check(completionPresentation.text.contains("任务关联重试已完成"))
            check(completionPresentation.text.contains("旧运行记录保持不变"))
            check(!completionPresentation.text.contains(sourceAfter.run.id))
            check(!completionPresentation.text.contains(completedDetail.run.id))
            Log.i(
                TAG,
                "task-retry-real success=true agentRun=${summary.runId} tools=${toolNames.joinToString(",")} " +
                    "sourceRunStatus=${sourceAfter.run.status} retryRunStatus=${completedDetail.run.status} " +
                    "retryRunLinked=true reusedSteps=$reusedStepCount oldRunUnchanged=true " +
                    "foregroundWorkflow=true finalizationOnly=false completionVisible=true",
            )
        } finally {
            workflowRepository.setEnabled(fixture.first.id, false)
            originalProfileId?.let { profileStore.select(it) }
            profileStore.delete(profile.id)
            check(profileStore.list().none { it.id == profile.id }) {
                "第158阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "task-retry-real cleanup=true workflowDisabled=true temporaryProfileRemoved=true")
        }
    }

    private suspend fun runTaskCancelReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val conversationId = "conversation-redmi-task-cancel-$now"
        val fixtureName = "第160阶段任务取消夹具-$now"
        val workflowRepository = RoomWorkflowRepository(context)
        val profileStore = RoomAgentProfileStore(context)
        val temporaryProfileId = "stage160-task-cancel-profile"
        val existingProfiles = profileStore.list()
        val originalProfileId = RoomStateStore(context).selectedAgentProfileId()
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        originalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)
        val fixture = workflowRepository.createWorkflowAndOneTimeScheduledTask(
            name = fixtureName,
            steps = listOf(WorkflowStepDefinitionInput("读取当前提醒执行状态")),
            delayMinutes = 30,
        )
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第160阶段真实任务取消验收",
            avatar = "160",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只处理用户明确指定的计划任务。必须严格按 tasks.list、tasks.inspect、tasks.cancel 顺序调用工具；不要调用其他工具，不要猜测任务名称。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("tasks.list", "tasks.inspect", "tasks.cancel"),
            allowedSkillIds = listOf("task-cancel"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        profileStore.upsert(profile)
        check(profileStore.select(profile.id)) { "无法选择第160阶段任务取消 Profile" }
        try {
            Log.i(
                TAG,
                "task-cancel-real start=true provider=${provider.id} model=${provider.model} taskName=$fixtureName",
            )
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-task-cancel-$now",
                goal = "请取消任务“$fixtureName”。必须先调用 tasks.list，再使用清单中的完全相同名称调用 tasks.inspect，最后调用 tasks.cancel。",
                skillSelectionGoal = "取消任务“$fixtureName”",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
                approvalGate = DebugRoomApprovalGate(
                    conversationId = conversationId,
                    repository = RoomAgentRunRepository(context),
                    reason = "第160阶段 Redmi 真实任务取消验收批准",
                ),
            )
            val detail = checkNotNull(RoomAgentRunRepository(context).runDetail(summary.runId)) {
                "tasks.cancel 真实 Agent Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "tasks.cancel 真实 Agent Run 未完成：${detail.snapshot.run.status}"
            }
            val toolNames = detail.toolLedger.results.map { result -> result.toolName }
            check(toolNames == listOf("tasks.list", "tasks.inspect", "tasks.cancel")) {
                "tasks.cancel 没有按清单、诊断、取消顺序完成：$toolNames"
            }
            check(detail.toolLedger.results.all { result -> result.success && result.verificationStatus == ToolVerificationStatus.PASSED }) {
                "任务取消 Tool Ledger 未全部通过 typed 验证：${detail.toolLedger.results}"
            }
            val scheduledTask = checkNotNull(workflowRepository.getScheduledTask(fixture.second.id)) {
                "任务取消 ScheduledTask 未写入 Room"
            }
            check(scheduledTask.status == ScheduledTaskStatus.CANCELLED) {
                "任务取消没有形成 CANCELLED 栅栏：${scheduledTask.status}"
            }
            check(detail.toolLedger.results.last().content.contains("计划已取消")) {
                "任务取消结果没有返回稳定用户文案"
            }
            check(detail.toolLedger.results.none { result ->
                result.content.contains(fixture.second.id) || result.content.contains("workflow-run-")
            }) { "任务取消结果泄露内部任务或 Run ID" }
            Log.i(
                TAG,
                "task-cancel-real success=true agentRun=${summary.runId} tools=${toolNames.joinToString(",")} " +
                    "taskStatus=${scheduledTask.status} taskCancel=true oldRunUnchanged=true",
            )
        } finally {
            workflowRepository.setEnabled(fixture.first.id, false)
            originalProfileId?.let { profileStore.select(it) }
            profileStore.delete(profile.id)
            check(profileStore.list().none { it.id == profile.id }) {
                "第160阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "task-cancel-real cleanup=true workflowDisabled=true temporaryProfileRemoved=true")
        }
    }

    private suspend fun runNotesCreateReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val conversationId = "conversation-redmi-notes-create-$now"
        val title = "第152阶段真实笔记-$now"
        val content = "Redmi 真实 Agent notes.create 闭环验证-$now"
        val profileStore = RoomAgentProfileStore(context)
        val temporaryProfileId = "stage152-notes-create-profile"
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第152阶段本地笔记验收",
            avatar = "152",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只根据用户目标操作本机笔记；创建后必须以工具事实确认写入结果，不执行其他写入。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.search", "notes.create", "notes.list"),
            allowedSkillIds = listOf("local-notes"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        val noteStore = RoomAgentNoteStore(context)
        val repository = RoomAgentRunRepository(context)
        // long: 上一次 Debug 探针若在清理前被强停，下一次开始先回收仅由本阶段生成的前缀数据，避免残留影响唯一性断言。
        val existingProfiles = profileStore.list()
        val existingSelectedProfileId = RoomStateStore(context).selectedAgentProfileId()
        val recoveredOriginalProfileId = existingSelectedProfileId
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        recoveredOriginalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)
        val database = XiaoLingDatabase.getInstance(context)
        noteStore.list(10)
            .filter { it.title.startsWith("第152阶段真实笔记-") }
            .forEach { database.agentNoteDao().deleteNote(it.id) }
        profileStore.upsert(profile)
        check(profileStore.select(profile.id)) { "无法选择第152阶段笔记 Profile" }
        try {
            Log.i(TAG, "notes-create-real start=true provider=${provider.id} model=${provider.model}")
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-notes-create-$now",
                goal = "请创建一条本地笔记，标题为“$title”，正文为“$content”；写入后回读验证，并确认这条笔记可以按标题关键词搜索到。",
                skillSelectionGoal = "请把这件事记录成一条本机笔记，并在写入后核对。",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
                approvalGate = DebugRoomApprovalGate(conversationId, repository),
            )
            val detail = checkNotNull(repository.runDetail(summary.runId)) {
                "第152阶段 notes.create Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "第152阶段 notes.create Run 未完成：${detail.snapshot.run.status}"
            }
            val createResult = detail.toolLedger.results.single { it.toolName == "notes.create" }
            check(
                createResult.success &&
                    createResult.executorVerified == true &&
                    createResult.verificationStatus == ToolVerificationStatus.PASSED,
            ) { "notes.create 未形成通过的执行与验证事实：$createResult" }
            val createdNote = noteStore.search(title, 10).singleOrNull { it.title == title && it.content == content }
            check(createdNote != null) { "notes.search 未回读到刚创建的唯一测试笔记" }
            check(detail.approvals.single { it.toolName == "notes.create" }.status == ApprovalRequestStatus.APPROVED) {
                "notes.create Room 审批没有收敛为 APPROVED"
            }
            Log.i(
                TAG,
                "notes-create-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                    "approval=APPROVED tool=notes.create success=true executorVerified=true " +
                    "verification=PASSED searchVerified=true noteId=${createdNote.id}",
            )
            // long: Debug 探针只验证闭环，不把测试数据留在用户笔记库；Room Run/审批证据仍保留用于验收追溯。
            val deletedCount = database.agentNoteDao().deleteNote(createdNote.id)
            check(deletedCount == 1 && noteStore.search(title, 10).none { it.id == createdNote.id }) {
                "第152阶段测试笔记清理失败：deletedCount=$deletedCount"
            }
        } finally {
            recoveredOriginalProfileId?.let { profileStore.select(it) }
            profileStore.delete(temporaryProfileId)
            check(profileStore.list().none { it.id == temporaryProfileId }) {
                "第152阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "notes-create-real cleanup=true temporaryProfileRemoved=true testNoteRemoved=true")
        }
    }

    private suspend fun runNotesSearchGetReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val keyword = "stage171-$now"
        val title = "第171阶段真实笔记-$keyword"
        val contentTail = "第171阶段全文读取标记-$now"
        val content = "这是用于验证搜索预览不会替代全文读取的本地笔记。".repeat(8) + contentTail
        val profileStore = RoomAgentProfileStore(context)
        val temporaryProfileId = "stage171-notes-search-get-profile"
        val existingProfiles = profileStore.list()
        val existingSelectedProfileId = RoomStateStore(context).selectedAgentProfileId()
        val recoveredOriginalProfileId = existingSelectedProfileId
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        recoveredOriginalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)

        val noteStore = RoomAgentNoteStore(context)
        val database = XiaoLingDatabase.getInstance(context)
        // long: 上次探针若在 finally 前被系统终止，只回收带本阶段专属标题前缀的 Debug 夹具，不能触碰用户笔记或历史 Run 审计。
        noteStore.search("第171阶段真实笔记-", 10)
            .forEach { database.agentNoteDao().deleteNote(it.id) }
        val fixture = noteStore.create(
            title = title,
            content = content,
            idempotencyKey = "stage171-notes-search-get-fixture-$now",
        )
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第171阶段笔记全文读取验收",
            avatar = "171",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "必须先按用户给出的唯一关键词调用 notes.search，再把搜索结果中的稳定 ID 原样传给 notes.get；搜索预览不能代替全文读取，不猜测 ID，不调用其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            // long: local-note-detail 的完整声明还包含 notes.list；Profile 保留该只读能力供 Skill 完整冻结，但本探针仍严格断言实际只执行 search -> get。
            allowedToolNames = listOf("notes.list", "notes.search", "notes.get"),
            allowedSkillIds = listOf("local-note-detail"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        val repository = RoomAgentRunRepository(context)
        try {
            profileStore.upsert(profile)
            check(profileStore.select(profile.id)) { "无法选择第171阶段笔记 Profile" }
            Log.i(TAG, "notes-search-get-real start=true provider=${provider.id} model=${provider.model}")
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = "conversation-redmi-notes-search-get-$now",
                userMessageId = "message-redmi-notes-search-get-$now",
                goal = "请使用唯一关键词“$keyword”搜索本地笔记。搜索结果只作为定位线索；必须取得唯一结果的稳定 ID，再调用 notes.get 读取全文，并告诉我全文末尾的读取标记。禁止猜测 ID，禁止调用其他工具。",
                skillSelectionGoal = "先搜索本地笔记，再用搜索结果中的稳定 ID 读取这条笔记全文。",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
            )
            val detail = checkNotNull(repository.runDetail(summary.runId)) {
                "第171阶段 notes.search -> notes.get Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "第171阶段笔记读取 Run 未完成：${detail.snapshot.run.status}"
            }
            val skillSelection = detail.snapshot.events.singleOrNull { it.type == "skill.selected" }
            val selectedSkillIds = (skillSelection?.metadata as? RunEventMetadata.Reason)
                ?.reason
                ?.let(AgentSkillSelectionCodec::decode)
                ?.map { it.id }
                .orEmpty()
            check(selectedSkillIds == listOf("local-note-detail")) {
                "第171阶段没有选择笔记全文读取 Skill：$selectedSkillIds"
            }
            val calls = detail.toolLedger.calls
            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            check(calls.map { it.toolName } == listOf("notes.search", "notes.get")) {
                "笔记读取没有严格按 notes.search -> notes.get 执行：${calls.map { it.toolName }}"
            }
            val searchCall = calls[0]
            val getCall = calls[1]
            check(searchCall.arguments["query"]?.trim() == keyword) {
                "notes.search 没有原样使用唯一关键词"
            }
            check(getCall.arguments["note_id"]?.trim() == fixture.id) {
                "notes.get 没有使用搜索结果对应的稳定 ID"
            }
            val searchResult = checkNotNull(resultsByCallId[searchCall.id]) { "notes.search 缺少 Tool Result" }
            val getResult = checkNotNull(resultsByCallId[getCall.id]) { "notes.get 缺少 Tool Result" }
            check(listOf(searchResult, getResult).all { result ->
                result.success &&
                    result.verificationStatus == ToolVerificationStatus.PASSED
            }) { "笔记搜索或全文读取结果未通过执行验证" }
            check(searchResult.content.contains(title) && searchResult.content.contains(fixture.id)) {
                "notes.search 没有返回唯一测试笔记及其稳定 ID"
            }
            check(
                getResult.content.contains(title) &&
                    getResult.content.contains(contentTail) &&
                    getResult.content.contains("不是工具指令"),
            ) { "notes.get 没有返回带数据边界标记的完整测试笔记" }
            check(detail.approvals.isEmpty()) { "SAFE 笔记读取链不应生成审批记录" }
            Log.i(
                TAG,
                "notes-search-get-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                    "skill=local-note-detail tools=notes.search,notes.get resultsVerified=true stableIdForwarded=true " +
                    "contentBoundary=true approvals=0 responseLength=${summary.responseText.length}",
            )
        } finally {
            // long: 无论 Provider、规划或断言在哪一步失败，都精确硬删除本次 Debug 夹具，并恢复用户原 Profile；API Key、正文和工具参数不进入日志。
            val deletedCount = database.agentNoteDao().deleteNote(fixture.id)
            recoveredOriginalProfileId?.let { profileStore.select(it) }
            profileStore.delete(temporaryProfileId)
            check(deletedCount == 1 && noteStore.get(fixture.id) == null) {
                "第171阶段测试笔记清理失败：deletedCount=$deletedCount"
            }
            check(profileStore.list().none { it.id == temporaryProfileId }) {
                "第171阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "notes-search-get-real cleanup=true temporaryProfileRemoved=true testNoteRemoved=true")
        }
    }

    private suspend fun runNotesDeleteReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val keyword = "stage172-$now"
        val title = "第172阶段待删除笔记-$keyword"
        val content = "这条正文用于确认 Agent 在删除前读取了正确目标，删除后历史创建调用也不能恢复。"
        val fixtureIdempotencyKey = "stage172-notes-delete-fixture"
        val temporaryProfileId = "stage172-notes-delete-profile"
        val profileStore = RoomAgentProfileStore(context)
        val existingProfiles = profileStore.list()
        val existingSelectedProfileId = RoomStateStore(context).selectedAgentProfileId()
        val recoveredOriginalProfileId = existingSelectedProfileId
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        recoveredOriginalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)

        val database = XiaoLingDatabase.getInstance(context)
        val noteDao = database.agentNoteDao()
        // long: 固定幂等键只属于第172阶段 Debug 夹具；上次进程若在 finally 前退出，可以精确回收活动笔记或隐藏 tombstone，不扫描和误删用户笔记。
        noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey)
            ?.let { staleFixture ->
                noteDao.deleteEditOperationsForNote(staleFixture.id)
                noteDao.deleteNote(staleFixture.id)
            }
        val noteStore = RoomAgentNoteStore(context)
        val fixture = noteStore.create(title, content, fixtureIdempotencyKey)
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第172阶段笔记删除验收",
            avatar = "172",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只有用户明确要求删除时才操作。必须先按唯一关键词搜索，再读取唯一结果全文核对稳定 ID，最后只删除这一个 ID；不得猜测 ID、跳过读取或调用其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.list", "notes.search", "notes.get", "notes.delete"),
            allowedSkillIds = listOf("local-note-delete"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        val repository = RoomAgentRunRepository(context)
        try {
            profileStore.upsert(profile)
            check(profileStore.select(profile.id)) { "无法选择第172阶段笔记删除 Profile" }
            Log.i(TAG, "notes-delete-real start=true provider=${provider.id} model=${provider.model}")
            val conversationId = "conversation-redmi-notes-delete-$now"
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-notes-delete-$now",
                goal = "请使用唯一关键词“$keyword”搜索本地笔记，读取唯一结果的全文确认目标，然后删除同一个稳定 ID。删除前必须请求确认；禁止猜测 ID、跳过全文读取或操作其他笔记。",
                skillSelectionGoal = "找到并删除标题匹配的这条笔记",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
                approvalGate = DebugRoomApprovalGate(
                    conversationId = conversationId,
                    repository = repository,
                    reason = "第172阶段 Redmi 真实笔记删除验收批准",
                ),
            )
            val detail = checkNotNull(repository.runDetail(summary.runId)) {
                "第172阶段 notes.delete Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "第172阶段笔记删除 Run 未完成：${detail.snapshot.run.status}"
            }
            val selectedSkillIds = detail.snapshot.events
                .singleOrNull { it.type == "skill.selected" }
                ?.metadata
                .let { it as? RunEventMetadata.Reason }
                ?.reason
                ?.let(AgentSkillSelectionCodec::decode)
                ?.map { it.id }
                .orEmpty()
            check(selectedSkillIds == listOf("local-note-delete")) {
                "第172阶段没有选择笔记删除 Skill：$selectedSkillIds"
            }
            val calls = detail.toolLedger.calls
            check(calls.map { it.toolName } == listOf("notes.search", "notes.get", "notes.delete")) {
                "笔记删除没有严格按 search -> get -> delete 执行：${calls.map { it.toolName }}"
            }
            val searchCall = calls[0]
            val getCall = calls[1]
            val deleteCall = calls[2]
            check(searchCall.arguments["query"]?.trim() == keyword) { "notes.search 没有原样使用唯一关键词" }
            check(getCall.arguments["note_id"]?.trim() == fixture.id) { "notes.get 没有读取搜索结果对应的稳定 ID" }
            check(deleteCall.arguments["note_id"]?.trim() == fixture.id) { "notes.delete 没有删除已核对的同一稳定 ID" }
            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            val orderedResults = calls.map { call -> checkNotNull(resultsByCallId[call.id]) { "${call.toolName} 缺少 Tool Result" } }
            check(orderedResults.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED }) {
                "笔记删除链存在未通过验证的工具结果"
            }
            val deleteResult = orderedResults.last()
            check(deleteResult.executorVerified == true && deleteResult.executionReceipt?.operationId == fixture.id) {
                "notes.delete 缺少 Executor 验证或稳定删除回执"
            }
            check(
                detail.approvals.singleOrNull { it.toolName == "notes.delete" }?.status == ApprovalRequestStatus.APPROVED,
            ) { "notes.delete Room 审批没有收敛为 APPROVED" }
            val tombstone = checkNotNull(noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey)) {
                "notes.delete 没有保留防历史重放的 tombstone"
            }
            check(tombstone.id == fixture.id && tombstone.title.isEmpty() && tombstone.content.isEmpty()) {
                "notes.delete tombstone 未清空正文或目标漂移"
            }
            check(noteStore.get(fixture.id) == null && noteStore.search(keyword, 10).isEmpty()) {
                "notes.delete 后当前 Store 仍可读取测试笔记"
            }
            val replayFailure = runCatching {
                noteStore.create(title, content, fixtureIdempotencyKey)
            }.exceptionOrNull()
            check(replayFailure is AgentNoteDeletedException) {
                "历史 notes.create 重放未被 tombstone 拒绝"
            }
            Log.i(
                TAG,
                "notes-delete-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                    "skill=local-note-delete tools=notes.search,notes.get,notes.delete approval=APPROVED " +
                    "resultsVerified=true stableIdBound=true tombstone=true historicalCreateBlocked=true",
            )
        } finally {
            noteDao.deleteEditOperationsForNote(fixture.id)
            val deletedCount = noteDao.deleteNote(fixture.id)
            recoveredOriginalProfileId?.let { profileStore.select(it) }
            profileStore.delete(temporaryProfileId)
            check(deletedCount == 1 && noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey) == null) {
                "第172阶段测试笔记清理失败：deletedCount=$deletedCount"
            }
            check(profileStore.list().none { it.id == temporaryProfileId }) {
                "第172阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "notes-delete-real cleanup=true temporaryProfileRemoved=true testNoteRemoved=true")
        }
    }

    private suspend fun runNotesUpdateReal(context: Context) {
        val storedProvider = ProviderRepository(context).load()
        val provider = storedProvider.profiles.firstOrNull { it.id == storedProvider.selectedProfileId }
            ?: error("没有已选择的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "当前 Provider 配置不完整"
        }
        val now = System.currentTimeMillis()
        val keyword = "stage173-$now"
        val originalTitle = "第173阶段待编辑笔记-$keyword"
        val originalContent = "第一版正文用于确认 Agent 读取的是当前 revision。"
        val updatedTitle = "第173阶段已编辑笔记-$keyword"
        val updatedContent = "第二版正文用于确认 revision 条件更新、回读验证和历史创建重放拒绝。"
        val fixtureIdempotencyKey = "stage173-notes-update-fixture"
        val temporaryProfileId = "stage173-notes-update-profile"
        val profileStore = RoomAgentProfileStore(context)
        val existingProfiles = profileStore.list()
        val existingSelectedProfileId = RoomStateStore(context).selectedAgentProfileId()
        val recoveredOriginalProfileId = existingSelectedProfileId
            ?.takeIf { it != temporaryProfileId }
            ?: existingProfiles.firstOrNull { it.id != temporaryProfileId }?.id
        recoveredOriginalProfileId?.let { profileStore.select(it) }
        profileStore.delete(temporaryProfileId)

        val database = XiaoLingDatabase.getInstance(context)
        val noteDao = database.agentNoteDao()
        // long: 固定幂等键只定位第173阶段夹具；进程若在 finally 前退出，下一次运行会同时清除旧正文、tombstone 和编辑 operation 的目标记录，不扫描用户笔记。
        noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey)
            ?.let { staleFixture ->
                noteDao.deleteEditOperationsForNote(staleFixture.id)
                noteDao.deleteNote(staleFixture.id)
            }
        val noteStore = RoomAgentNoteStore(context)
        val fixture = noteStore.create(originalTitle, originalContent, fixtureIdempotencyKey)
        check(fixture.revision == 1L) { "第173阶段夹具初始 revision 不是 1" }
        val profile = AgentProfileRecord(
            id = temporaryProfileId,
            name = "第173阶段笔记编辑验收",
            avatar = "173",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只有用户明确要求编辑时才操作。必须先按唯一关键词搜索，再读取唯一结果全文、稳定 ID 和 revision，最后只以同一 ID 和 revision 提交完整的新标题与正文；不得猜测版本、跳过读取或调用其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.list", "notes.search", "notes.get", "notes.update"),
            allowedSkillIds = listOf("local-note-update"),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val config = ProviderRequestConfig(
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = provider.model.trim(),
            providerId = provider.id,
            userAgent = UiPreferenceStore(context).loadUserAgent(),
            apiMode = profile.apiMode,
            streamingEnabled = false,
            embeddingModel = provider.preferredEmbeddingModel(),
        )
        val repository = RoomAgentRunRepository(context)
        try {
            profileStore.upsert(profile)
            check(profileStore.select(profile.id)) { "无法选择第173阶段笔记编辑 Profile" }
            Log.i(TAG, "notes-update-real start=true provider=${provider.id} model=${provider.model}")
            val conversationId = "conversation-redmi-notes-update-$now"
            val summary = AgentRunUseCase(context, OpenAiCompatibleClient()).run(
                conversationId = conversationId,
                userMessageId = "message-redmi-notes-update-$now",
                goal = "请使用唯一关键词“$keyword”搜索本地笔记，读取唯一结果的全文、稳定 ID 和 revision，然后把完整标题改为“$updatedTitle”，完整正文改为“$updatedContent”。编辑前必须请求确认；禁止猜测 ID、revision、跳过全文读取或操作其他笔记。",
                skillSelectionGoal = "把这条笔记的正文更新为新内容",
                config = config,
                summarySystemPrompt = PromptPolicy.agentSummarySystemPrompt(UiPreferenceStore(context).loadPromptSettings()),
                agentProfile = profile.snapshot(),
                memoryRecallEnabled = false,
                invocationSource = AgentInvocationSource.DIRECT,
                approvalGate = DebugRoomApprovalGate(
                    conversationId = conversationId,
                    repository = repository,
                    reason = "第173阶段 Redmi 真实笔记编辑验收批准",
                ),
            )
            val detail = checkNotNull(repository.runDetail(summary.runId)) {
                "第173阶段 notes.update Run 未写入 Room"
            }
            check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
                "第173阶段笔记编辑 Run 未完成：${detail.snapshot.run.status}"
            }
            val selectedSkillIds = detail.snapshot.events
                .singleOrNull { it.type == "skill.selected" }
                ?.metadata
                .let { it as? RunEventMetadata.Reason }
                ?.reason
                ?.let(AgentSkillSelectionCodec::decode)
                ?.map { it.id }
                .orEmpty()
            check(selectedSkillIds == listOf("local-note-update")) {
                "第173阶段没有选择笔记编辑 Skill：$selectedSkillIds"
            }
            val calls = detail.toolLedger.calls
            check(calls.map { it.toolName } == listOf("notes.search", "notes.get", "notes.update")) {
                "笔记编辑没有严格按 search -> get -> update 执行：${calls.map { it.toolName }}"
            }
            val searchCall = calls[0]
            val getCall = calls[1]
            val updateCall = calls[2]
            check(searchCall.arguments["query"]?.trim() == keyword) { "notes.search 没有原样使用唯一关键词" }
            check(getCall.arguments["note_id"]?.trim() == fixture.id) { "notes.get 没有读取搜索结果对应的稳定 ID" }
            check(updateCall.arguments["note_id"]?.trim() == fixture.id) { "notes.update 没有编辑已核对的同一稳定 ID" }
            check(updateCall.arguments["expected_revision"]?.trim() == "1") { "notes.update 没有使用 notes.get 返回的 revision" }
            check(updateCall.arguments["title"]?.trim() == updatedTitle) { "notes.update 新标题漂移" }
            check(updateCall.arguments["content"]?.trim() == updatedContent) { "notes.update 新正文漂移" }
            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            val orderedResults = calls.map { call -> checkNotNull(resultsByCallId[call.id]) { "${call.toolName} 缺少 Tool Result" } }
            check(orderedResults.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED }) {
                "笔记编辑链存在未通过验证的工具结果"
            }
            val updateResult = orderedResults.last()
            check(updateResult.executorVerified == true && updateResult.executionReceipt?.operationId == fixture.id) {
                "notes.update 缺少 Executor 验证或稳定编辑回执"
            }
            check(
                detail.approvals.singleOrNull { it.toolName == "notes.update" }?.status == ApprovalRequestStatus.APPROVED,
            ) { "notes.update Room 审批没有收敛为 APPROVED" }
            val updated = checkNotNull(noteStore.get(fixture.id)) { "notes.update 后笔记不可读" }
            check(updated.title == updatedTitle && updated.content == updatedContent && updated.revision == 2L) {
                "notes.update 没有形成 revision=2 的完整新内容"
            }
            val updateRequest = AgentNoteUpdateRequest(
                noteId = fixture.id,
                title = updatedTitle,
                content = updatedContent,
                expectedRevision = 1L,
            )
            check(
                noteStore.verifyUpdateOperation(updateCall.id, fixture.id, updateRequest) is AgentNoteUpdateVerification.Verified,
            ) { "notes.update operation 账本无法恢复验证" }
            val replayFailure = runCatching {
                noteStore.create(originalTitle, originalContent, fixtureIdempotencyKey)
            }.exceptionOrNull()
            check(replayFailure is AgentNoteIdempotencyConflictException) {
                "历史 notes.create 重放没有被编辑后的载荷漂移拒绝"
            }
            Log.i(
                TAG,
                "notes-update-real success=true run=${summary.runId} status=${detail.snapshot.run.status} " +
                    "skill=local-note-update tools=notes.search,notes.get,notes.update approval=APPROVED " +
                    "resultsVerified=true stableIdBound=true expectedRevision=1 resultRevision=2 operationVerified=true historicalCreateBlocked=true",
            )
        } finally {
            noteDao.deleteEditOperationsForNote(fixture.id)
            val deletedCount = noteDao.deleteNote(fixture.id)
            recoveredOriginalProfileId?.let { profileStore.select(it) }
            profileStore.delete(temporaryProfileId)
            check(deletedCount == 1 && noteDao.getNoteByIdempotencyKey(fixtureIdempotencyKey) == null) {
                "第173阶段测试笔记清理失败：deletedCount=$deletedCount"
            }
            check(profileStore.list().none { it.id == temporaryProfileId }) {
                "第173阶段临时 Profile 清理失败"
            }
            Log.i(TAG, "notes-update-real cleanup=true temporaryProfileRemoved=true testNoteRemoved=true")
        }
    }

    private class DebugRoomApprovalGate(
        private val conversationId: String,
        private val repository: RoomAgentRunRepository,
        private val reason: String = "第152阶段 Redmi 真实闭环验收批准",
    ) : ApprovalGate {
        override suspend fun requestApproval(
            runId: String,
            toolCall: ToolCall,
            definition: ToolDefinition,
        ): ApprovalDecision {
            val request = repository.createApprovalRequest(conversationId, runId, toolCall, definition)
            val decided = repository.decideApprovalRequest(
                requestId = request.id,
                status = ApprovalRequestStatus.APPROVED,
                reason = reason,
            )
            check(decided?.status == ApprovalRequestStatus.APPROVED) { "Debug 工具审批无法持久化批准" }
            return ApprovalDecision(approved = true, reason = decided.decisionReason.orEmpty())
        }
    }

    private suspend fun runLongScheduledWorkflow(context: Context) {
        val now = System.currentTimeMillis()
        restoreLongDebugProfile(context)
        val storedProviders = ProviderRepository(context).load()
        val provider = storedProviders.profiles.firstOrNull { profile ->
            profile.id == storedProviders.selectedProfileId
        } ?: error("没有可用于长任务探针的 Provider")
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank()) {
            "长任务探针 Provider 配置不完整"
        }
        val profileStore = RoomAgentProfileStore(context)
        val originalProfileId = RoomStateStore(context).selectedAgentProfileId()
        context.getSharedPreferences(LONG_DEBUG_STATE, Context.MODE_PRIVATE).edit()
            .putString(LONG_DEBUG_ORIGINAL_PROFILE_ID, originalProfileId)
            .apply()
        // long: 后台运行必须拥有明确且最小的工具面；临时 Profile 只用于本轮真实样本，结束后由状态查询恢复用户原配置。
        val probeProfile = AgentProfileRecord(
            id = LONG_DEBUG_PROFILE_ID,
            name = "第151阶段长任务探针",
            avatar = "151",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只读取当前时间并返回事实，不执行其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.current_time"),
            allowedSkillIds = emptyList(),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(probeProfile)
        check(profileStore.select(probeProfile.id)) { "无法选择长任务探针 Profile" }
        val repository = RoomWorkflowRepository(context)
        val steps = (1..8).map {
            WorkflowStepDefinitionInput(
                goal = "读取当前手机本地时间，并在本步骤返回时间事实；只能调用 app.current_time。",
            )
        }
        // long: 第 151 阶段只通过正式的 Room + WorkManager 入口制造真实多步骤样本，不能在 Debug 旁路中另起一套后台 Runtime。
        val (workflow, task) = repository.createWorkflowAndOneTimeScheduledTask(
            name = "第151阶段长任务观测-$now",
            steps = steps,
            delayMinutes = 1,
        )
        val workRequestId = WorkManagerScheduledTaskScheduler(context).enqueue(task)
        repository.attachWorkRequest(task.id, workRequestId)
        Log.i(
            TAG,
            "workflow-long-scheduled created=true workflowId=${workflow.id} taskId=${task.id} " +
                "workRequestId=$workRequestId plannedAt=${task.plannedAt} steps=${steps.size}",
        )
    }

    private suspend fun reportLongScheduledStatus(context: Context, taskId: String) {
        require(taskId.isNotBlank()) { "长任务状态查询缺少 taskId" }
        val repository = RoomWorkflowRepository(context)
        val task = repository.getScheduledTask(taskId)
        if (task == null) {
            Log.i(TAG, "workflow-long-status taskId=$taskId status=NOT_FOUND")
            return
        }
        val detail = task.workflowRunId?.let { repository.runDetail(it) }
        val steps = detail?.steps.orEmpty()
        val counts = steps.groupingBy { it.status }.eachCount()
        val startedAt = detail?.run?.startedAt ?: task.actualStartedAt
        val completedAt = detail?.run?.completedAt ?: task.completedAt
        val durationMs = if (startedAt != null && completedAt != null) {
            (completedAt - startedAt).coerceAtLeast(0L)
        } else {
            null
        }
        Log.i(
            TAG,
            "workflow-long-status taskId=$taskId status=${task.status} workflowRunId=${task.workflowRunId} " +
                "runStatus=${detail?.run?.status} steps=${steps.size} counts=$counts " +
                "startedAt=$startedAt completedAt=$completedAt durationMs=$durationMs " +
                "stopReason=${detail?.run?.workerStopReasonName} taskError=${task.errorMessage} " +
                "runError=${detail?.run?.errorMessage} stepErrors=${steps.mapNotNull { it.errorMessage }}",
        )
        if (task.status in setOf(ScheduledTaskStatus.COMPLETED, ScheduledTaskStatus.FAILED, ScheduledTaskStatus.BLOCKED, ScheduledTaskStatus.CANCELLED)) {
            // long: 探针 Workflow 只保留历史证据，不应继续出现在可再次执行的生产任务集合中。
            repository.setEnabled(task.workflowId, false)
            restoreLongDebugProfile(context)
        }
    }

    private suspend fun restoreLongDebugProfile(context: Context) {
        val state = context.getSharedPreferences(LONG_DEBUG_STATE, Context.MODE_PRIVATE)
        if (!state.contains(LONG_DEBUG_ORIGINAL_PROFILE_ID)) return
        val profileStore = RoomAgentProfileStore(context)
        val originalId = state.getString(LONG_DEBUG_ORIGINAL_PROFILE_ID, null)
        if (originalId != null) profileStore.select(originalId)
        profileStore.delete(LONG_DEBUG_PROFILE_ID)
        state.edit().clear().apply()
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
                userIntent = "点击当前页面的测试按钮",
                targetAppPackage = context.packageName,
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
            goal = "点击测试按钮并确认页面变化",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-device-action",
                workflowStepId = "workflow-step-redmi-device-action",
                userIntent = "点击当前页面的测试按钮",
                targetAppPackage = context.packageName,
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
                targetAppPackage = context.packageName,
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
                targetAppPackage = context.packageName,
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

    private suspend fun runWorkflowSwipe(context: Context) {
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
        awaitStableScrollablePackageWindow(controller, SYSTEM_SETTINGS_PACKAGE)

        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val runRepository = RoomAgentRunRepository(context)
        val scriptedLlm = WorkflowSwipeE2eLlm()
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
                    error("SAFE device.swipe 不应请求 Room 或浮层审批")
                }
            },
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = "process-redmi-workflow-swipe",
        )
        val summary = runtime.run(
            conversationId = E2E_SWIPE_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-swipe-${System.currentTimeMillis()}",
            goal = "在系统设置应用详情页向上滚动并确认同窗内容变化",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-swipe",
                workflowStepId = "workflow-step-redmi-swipe",
                userIntent = "在系统设置应用详情页向上滚动",
                targetAppPackage = SYSTEM_SETTINGS_PACKAGE,
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow swipe Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow swipe Run 未完成：${detail.snapshot.run.status}"
        }
        check(detail.approvals.none { it.toolName == DEVICE_SWIPE_TOOL_NAME }) {
            "SAFE device.swipe 不得创建 Room Approval：${detail.approvals.map { it.toolName }}"
        }
        val swipeCall = detail.toolLedger.calls.single { it.toolName == DEVICE_SWIPE_TOOL_NAME }
        check(
            swipeCall.arguments == mapOf(
                "snapshot_id" to scriptedLlm.snapshotId,
                "ref" to scriptedLlm.ref,
                "direction" to WORKFLOW_SWIPE_DIRECTION,
            ) && swipeCall.risk == ToolRisk.SAFE
        ) { "Workflow swipe ToolCall 必须保持精确引用参数与 SAFE 风险：$swipeCall" }
        val swipeResult = detail.toolLedger.results.single { it.toolName == DEVICE_SWIPE_TOOL_NAME }
        check(
            swipeResult.success &&
                swipeResult.executorVerified == true &&
                swipeResult.verificationStatus == ToolVerificationStatus.PASSED,
        ) { "Tool Ledger 未保存通过专属滚动验证的 swipe：$swipeResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(swipeResult.content)) {
            "Workflow swipe 没有返回严格白名单结果"
        }
        check(
            actionEvidence.action == "swipe" &&
                actionEvidence.beforePackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence.afterPackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence.verified
        ) { "Workflow swipe 前后窗口或验证结果不符合预期：$actionEvidence" }
        check(
            !swipeResult.content.contains(scriptedLlm.snapshotId) &&
                !swipeResult.content.contains(scriptedLlm.ref) &&
                !HMAC_HEX_PATTERN.containsMatchIn(swipeResult.content)
        ) { "Workflow swipe Result 泄露 snapshot/ref 或滚动 HMAC" }
        // long: 生产开放验收必须继续穿过答案级本地判定，避免只证明 Executor 成功却没有证明 Room 可消费的脱敏摘要。
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = listOf(
                WorkflowDeviceActionEvidenceInput(
                    runId = swipeResult.runId,
                    toolName = swipeResult.toolName,
                    content = swipeResult.content,
                    success = swipeResult.success,
                    executorVerified = swipeResult.executorVerified,
                    verified = swipeResult.verificationStatus == ToolVerificationStatus.PASSED,
                ),
            ),
        )
        val decision = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions?.singleOrNull()
            ?: error("Workflow swipe 未形成答案级本地判定：$resolution")
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        check(
            decision.action == "swipe" &&
                prompt.contains("已执行并验证 滚动") &&
                !prompt.contains(scriptedLlm.snapshotId) &&
                !prompt.contains(scriptedLlm.ref) &&
                !HMAC_HEX_PATTERN.containsMatchIn(prompt)
        ) { "Workflow swipe 答案级判定没有保持脱敏滚动摘要：$prompt" }
        val postSnapshot = captureWhenReady(controller).snapshot
        check(postSnapshot.packageName == SYSTEM_SETTINGS_PACKAGE) {
            "真实 Accessibility swipe 后离开系统设置：${postSnapshot.packageName}"
        }
        Log.i(
            TAG,
            "workflow-swipe-e2e success=true action=${actionEvidence.action} verified=${actionEvidence.verified} " +
                "approvals=${detail.approvals.size} registryCompletion=PASSED " +
                "answerDecision=${decision.status} " +
                "beforePackage=${actionEvidence.beforePackageName} afterPackage=${actionEvidence.afterPackageName} " +
                "privacySafe=true afterNodes=${actionEvidence.afterNodeCount}",
        )
    }

    private suspend fun runWorkflowSettingsMulti(context: Context) {
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
        awaitStableScrollablePackageWindow(controller, SYSTEM_SETTINGS_PACKAGE)

        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            deviceController = controller,
        )
        val runRepository = RoomAgentRunRepository(context)
        val scriptedLlm = WorkflowSettingsMultiE2eLlm()
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
                    error("限定应用连续任务中的 SAFE swipe/back 不应请求审批")
                }
            },
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = "process-redmi-workflow-settings-multi",
        )
        val summary = runtime.run(
            conversationId = E2E_SETTINGS_MULTI_CONVERSATION_ID,
            userMessageId = "message-redmi-workflow-settings-multi-${System.currentTimeMillis()}",
            goal = "在系统设置应用详情页滚动后重新观察，再返回小灵",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-settings-multi",
                workflowStepId = "workflow-step-redmi-settings-multi",
                userIntent = "在限定的系统设置应用内滚动、重新观察并返回",
                targetAppPackage = SYSTEM_SETTINGS_PACKAGE,
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) {
            "限定应用连续动作 Run 未写入 Room"
        }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "限定应用连续动作 Run 未完成：${detail.snapshot.run.status}"
        }
        check(detail.approvals.isEmpty()) {
            "SAFE 连续动作不得创建审批：${detail.approvals.map { it.toolName }}"
        }
        check(
            detail.toolLedger.calls.map { it.toolName } == listOf(
                DEVICE_SNAPSHOT_E2E_TOOL_NAME,
                DEVICE_SWIPE_TOOL_NAME,
                DEVICE_SNAPSHOT_E2E_TOOL_NAME,
                DEVICE_BACK_TOOL_NAME,
            ),
        ) { "限定应用连续动作 ToolCall 顺序错误：${detail.toolLedger.calls.map { it.toolName }}" }
        val swipeCall = detail.toolLedger.calls.single { it.toolName == DEVICE_SWIPE_TOOL_NAME }
        val backCall = detail.toolLedger.calls.single { it.toolName == DEVICE_BACK_TOOL_NAME }
        check(
            swipeCall.arguments == mapOf(
                "snapshot_id" to scriptedLlm.firstSnapshotId,
                "ref" to scriptedLlm.firstRef,
                "direction" to WORKFLOW_SWIPE_DIRECTION,
            ) && swipeCall.risk == ToolRisk.SAFE &&
                backCall.arguments.isEmpty() && backCall.risk == ToolRisk.SAFE
        ) { "限定应用连续动作没有保持新鲜引用与 SAFE 参数边界" }
        check(scriptedLlm.firstSnapshotId != scriptedLlm.secondSnapshotId) {
            "页面变化后复用了旧 snapshot，连续动作必须重新观察"
        }
        val actionResults = detail.toolLedger.results.filter {
            it.toolName == DEVICE_SWIPE_TOOL_NAME || it.toolName == DEVICE_BACK_TOOL_NAME
        }
        check(
            actionResults.size == 2 && actionResults.all { result ->
                result.success &&
                    result.executorVerified == true &&
                    result.verificationStatus == ToolVerificationStatus.PASSED
            },
        ) { "连续动作 Tool Ledger 缺少两项通过的 typed 验证：$actionResults" }
        val actionEvidence = actionResults.map { result ->
            checkNotNull(WorkflowDeviceActionResultCodec.decode(result.content)) {
                "连续动作结果不符合白名单 codec：${result.toolName}"
            }
        }
        check(
            actionEvidence[0].action == "swipe" &&
                actionEvidence[0].beforePackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence[0].afterPackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence[1].action == "back" &&
                actionEvidence[1].beforePackageName == SYSTEM_SETTINGS_PACKAGE &&
                actionEvidence[1].afterPackageName == context.packageName
        ) { "连续动作前后应用证据与限定 App 边界不一致：$actionEvidence" }
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = actionResults.map { result ->
                WorkflowDeviceActionEvidenceInput(
                    runId = result.runId,
                    toolName = result.toolName,
                    content = result.content,
                    success = result.success,
                    executorVerified = result.executorVerified,
                    verified = result.verificationStatus == ToolVerificationStatus.PASSED,
                )
            },
        )
        val decisions = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions
            ?.takeIf { it.map { decision -> decision.action } == listOf("swipe", "back") }
            ?: error("连续动作未形成有序答案级本地判定：$resolution")
        val verifiedResults = detail.toolLedger.results.filter { result ->
            result.success && result.verificationStatus == ToolVerificationStatus.PASSED
        }
        val observationResolution = WorkflowDeviceObservationDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = verifiedResults.map { result ->
                WorkflowDeviceObservationEvidenceInput(
                    runId = result.runId,
                    toolName = result.toolName,
                    content = result.content,
                    success = result.success,
                    verified = true,
                    durationMs = result.durationMs,
                )
            },
        )
        val observationDecisions = (observationResolution as? WorkflowDeviceObservationResolution.Decided)
            ?.decisions
            ?.takeIf { it.size == 2 }
            ?: error("连续动作没有形成两次独立设备观察判定：$observationResolution")
        val goalDecision = WorkflowGoalVerificationPolicy.evaluate(
            sourceGoal = "在系统设置应用详情页滚动后重新观察，再返回小灵",
            spec = WorkflowGoalVerificationSpec(
                requiredToolNames = listOf(DEVICE_SWIPE_TOOL_NAME, DEVICE_BACK_TOOL_NAME),
                expectedFinalPackageName = context.packageName,
            ),
            steps = listOf(
                WorkflowGoalVerificationStepEvidence(
                    status = WorkflowStepStatus.COMPLETED,
                    verifiedToolNames = verifiedResults.map { result -> result.toolName },
                    deviceObservationDecisions = observationDecisions,
                    deviceActionDecisions = decisions,
                ),
            ),
        )
        check(
            goalDecision.status == WorkflowGoalVerificationStatus.VERIFIED &&
                goalDecision.matchedRequiredToolNames == listOf(DEVICE_SWIPE_TOOL_NAME, DEVICE_BACK_TOOL_NAME) &&
                goalDecision.actualFinalPackageName == context.packageName
        ) { "连续动作没有形成 VERIFIED 目标级结论：$goalDecision" }
        val persistedPayload = actionResults.joinToString { it.content }
        val renderedGoalDecision = goalDecision.renderForUser()
        check(
            !persistedPayload.contains(scriptedLlm.firstSnapshotId) &&
                !persistedPayload.contains(scriptedLlm.secondSnapshotId) &&
                !persistedPayload.contains(scriptedLlm.firstRef) &&
                !HMAC_HEX_PATTERN.containsMatchIn(persistedPayload) &&
                !renderedGoalDecision.contains(scriptedLlm.firstSnapshotId) &&
                !renderedGoalDecision.contains(scriptedLlm.secondSnapshotId) &&
                !renderedGoalDecision.contains(scriptedLlm.firstRef) &&
                !HMAC_HEX_PATTERN.containsMatchIn(renderedGoalDecision)
        ) { "连续动作持久结果泄露 snapshot/ref 或滚动 HMAC" }
        val postSnapshot = captureWhenReady(controller).snapshot
        check(postSnapshot.packageName == context.packageName) {
            "连续动作 back 后没有回到小灵页面：${postSnapshot.packageName}"
        }
        Log.i(
            TAG,
            "workflow-settings-multi-e2e success=true actions=${decisions.joinToString { it.action }} " +
                "verified=${actionResults.size}/2 approvals=0 freshSnapshots=true " +
                "targetPackage=$SYSTEM_SETTINGS_PACKAGE finalPackage=${postSnapshot.packageName} " +
                "goalDecision=${goalDecision.status} privacySafe=true",
        )
    }

    private suspend fun runWorkflowOpenApp(
        context: Context,
        scenarioId: String,
        targetLabel: String,
        targetPackageName: String,
    ) {
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
        // long: Probe 首帧可见时 Accessibility 活动根与窗口列表仍可能短暂不同步；连续稳定后再创建审批，避免把启动竞态误判为浮层不可用。
        awaitStablePackageWindow(controller, context.packageName)
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
            llm = WorkflowOpenAppE2eLlm(
                expectedBeforePackageName = context.packageName,
                targetPackageName = targetPackageName,
            ),
            approvalGate = WorkflowDeviceActionApprovalGate(
                conversationId = "conversation-redmi-workflow-$scenarioId",
                userIntent = "打开允许列表中的$targetLabel",
                targetAppPackage = targetPackageName,
                fallback = AutoApprovalGate(),
                persistence = RoomWorkflowDeviceActionApprovalPersistence(runRepository),
                overlayRequester = DeviceAccessibilityRuntime,
            ),
            permissionChecker = AndroidToolPermissionChecker(context),
            processSessionId = "process-redmi-workflow-$scenarioId",
        )
        Log.i(TAG, "workflow-$scenarioId-overlay waiting=true")
        val summary = runtime.run(
            conversationId = "conversation-redmi-workflow-$scenarioId",
            userMessageId = "message-redmi-workflow-$scenarioId-${System.currentTimeMillis()}",
            goal = "打开${targetLabel}并确认前台目标包名",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            memoryRecallEnabled = false,
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-redmi-$scenarioId",
                workflowStepId = "workflow-step-redmi-$scenarioId",
                userIntent = "打开允许列表中的$targetLabel",
                targetAppPackage = targetPackageName,
            ),
        )
        val detail = checkNotNull(runRepository.runDetail(summary.runId)) { "真实 Workflow open_app Run 未写入 Room" }
        check(detail.snapshot.run.status == AgentRunStatus.COMPLETED) {
            "真实 Workflow open_app Run 未完成：${detail.snapshot.run.status}"
        }
        val approval = detail.approvals.single { it.toolName == DEVICE_OPEN_APP_TOOL_NAME }
        check(
            approval.status == ApprovalRequestStatus.APPROVED &&
                approval.arguments == mapOf("package_name" to targetPackageName)
        ) { "Room open_app 审批未绑定目标包名或未批准：$approval" }
        val openAppCall = detail.toolLedger.calls.single { it.toolName == DEVICE_OPEN_APP_TOOL_NAME }
        check(
            openAppCall.arguments == approval.arguments &&
                openAppCall.risk == ToolRisk.REQUIRES_APPROVAL
        ) { "Workflow open_app ToolCall 没有保持逐包审批身份：$openAppCall" }
        val openAppResult = detail.toolLedger.results.single { it.toolName == DEVICE_OPEN_APP_TOOL_NAME }
        check(
            openAppResult.success &&
                openAppResult.executorVerified == true &&
                openAppResult.verificationStatus == ToolVerificationStatus.PASSED
        ) { "Tool Ledger 未保存通过验证的 open_app：$openAppResult" }
        val actionEvidence = checkNotNull(WorkflowDeviceActionResultCodec.decode(openAppResult.content)) {
            "Workflow open_app 没有返回严格白名单结果"
        }
        // long: 打开应用的完成事实必须同时证明动作前仍是本应用、动作后精确命中获批包名，不能只凭 launchApp 返回成功。
        check(
            actionEvidence.action == "open_app" &&
                actionEvidence.beforePackageName == context.packageName &&
                actionEvidence.afterPackageName == targetPackageName &&
                actionEvidence.verified
        ) { "Workflow open_app 前后窗口或目标包验证不符合预期：$actionEvidence" }
        val resolution = WorkflowDeviceActionDecisionPolicy.evaluate(
            expectedAgentRunId = summary.runId,
            results = listOf(
                WorkflowDeviceActionEvidenceInput(
                    runId = openAppResult.runId,
                    toolName = openAppResult.toolName,
                    content = openAppResult.content,
                    success = openAppResult.success,
                    executorVerified = openAppResult.executorVerified,
                    verified = openAppResult.verificationStatus == ToolVerificationStatus.PASSED,
                    expectedOpenAppPackageName = openAppCall.arguments["package_name"],
                ),
            ),
        )
        val decision = (resolution as? WorkflowDeviceActionResolution.Decided)?.decisions?.singleOrNull()
            ?: error("Workflow open_app 未形成答案级本地判定：$resolution")
        val prompt = WorkflowDeviceActionDecisionPolicy.renderForPrompt(listOf(decision))
        check(
            prompt.contains("已执行并验证 打开应用") &&
                prompt.contains("本次打开应用不产生可复用节点引用") &&
                !prompt.contains("节点引用已经失效")
        ) { "Workflow open_app 答案级边界不完整" }
        val postSnapshot = captureWhenReady(controller).snapshot
        check(postSnapshot.packageName == targetPackageName) {
            "真实 Accessibility open_app 后没有停留在$targetLabel：${postSnapshot.packageName}"
        }
        Log.i(
            TAG,
            "workflow-$scenarioId-e2e success=true action=${actionEvidence.action} " +
                "verified=${actionEvidence.verified} approval=${approval.status} " +
                "executorVerified=${openAppResult.executorVerified} verification=${openAppResult.verificationStatus} " +
                "beforePackage=${actionEvidence.beforePackageName} afterPackage=${actionEvidence.afterPackageName} " +
                "answerDecision=${decision.status}",
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
                targetAppPackage = SYSTEM_SETTINGS_PACKAGE,
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
                targetAppPackage = SYSTEM_SETTINGS_PACKAGE,
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
                    if (capture.snapshot.nodes.any { node -> node.text == "测试按钮" }) return
                }
                is DeviceSnapshotCapture.Failed -> {
                    if (capture.reason !in TRANSIENT_SNAPSHOT_FAILURES) error(capture.message)
                }
            }
            delay(100)
        }
        error("Probe 前台窗口在限定时间内没有出现测试按钮")
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

    private suspend fun awaitStableScrollablePackageWindow(
        controller: DeviceObservationController,
        packageName: String,
    ) {
        var stableGeneration: Long? = null
        var stableSamples = 0
        repeat(50) {
            when (val capture = controller.capture()) {
                is DeviceSnapshotCapture.Success -> {
                    val snapshot = capture.snapshot
                    val hasScrollableReference = snapshot.nodes.any { node ->
                        node.ref != null && DeviceNodeAction.SWIPE in node.actions
                    }
                    if (snapshot.packageName == packageName && hasScrollableReference) {
                        stableSamples = if (snapshot.windowGeneration == stableGeneration) stableSamples + 1 else 1
                        stableGeneration = snapshot.windowGeneration
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
        error("限定时间内没有稳定的可滚动窗口：$packageName")
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
                    .single { node -> node.optString("text") == "测试按钮" }
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

    private class WorkflowSwipeE2eLlm : AgentLlm {
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
                check(snapshotJson.getString("package") == SYSTEM_SETTINGS_PACKAGE) {
                    "Workflow swipe 动作前 snapshot 不属于系统设置"
                }
                val nodes = snapshotJson.getJSONArray("nodes")
                val target = (0 until nodes.length())
                    .map(nodes::getJSONObject)
                    .filter { node ->
                        node.optString("ref").isNotBlank() &&
                            node.optJSONArray("actions")?.let { actions ->
                                (0 until actions.length()).any { actions.getString(it) == "swipe" }
                            } == true
                    }
                    // long: 系统页面可能同时暴露嵌套滚动容器；优先最大可见区域，避免把短横向区域误当主列表。
                    .maxByOrNull { node ->
                        val bounds = node.getJSONArray("bounds")
                        val width = (bounds.getInt(2) - bounds.getInt(0)).coerceAtLeast(0)
                        val height = (bounds.getInt(3) - bounds.getInt(1)).coerceAtLeast(0)
                        width.toLong() * height
                    }
                    ?: error("系统设置 snapshot 没有可执行 swipe 的节点引用")
                snapshotId = snapshotJson.getString("snapshot_id")
                ref = target.getString("ref")
                val swipe = tools.single { it.name == DEVICE_SWIPE_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = swipe.name,
                        arguments = mapOf(
                            "snapshot_id" to snapshotId,
                            "ref" to ref,
                            "direction" to WORKFLOW_SWIPE_DIRECTION,
                        ),
                        risk = swipe.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 滚动已完成真实同窗内容与方向验证"
    }

    private class WorkflowSettingsMultiE2eLlm : AgentLlm {
        lateinit var firstSnapshotId: String
            private set
        lateinit var firstRef: String
            private set
        lateinit var secondSnapshotId: String
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
                check(snapshotJson.getString("package") == SYSTEM_SETTINGS_PACKAGE) {
                    "连续动作首次 snapshot 不属于限定的系统设置应用"
                }
                val nodes = snapshotJson.getJSONArray("nodes")
                val target = (0 until nodes.length())
                    .map(nodes::getJSONObject)
                    .filter { node ->
                        node.optString("ref").isNotBlank() &&
                            node.optJSONArray("actions")?.let { actions ->
                                (0 until actions.length()).any { actions.getString(it) == "swipe" }
                            } == true
                    }
                    .maxByOrNull { node ->
                        val bounds = node.getJSONArray("bounds")
                        val width = (bounds.getInt(2) - bounds.getInt(0)).coerceAtLeast(0)
                        val height = (bounds.getInt(3) - bounds.getInt(1)).coerceAtLeast(0)
                        width.toLong() * height
                    }
                    ?: error("连续动作首次 snapshot 没有可滚动节点")
                firstSnapshotId = snapshotJson.getString("snapshot_id")
                firstRef = target.getString("ref")
                val swipe = tools.single { it.name == DEVICE_SWIPE_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = swipe.name,
                        arguments = mapOf(
                            "snapshot_id" to firstSnapshotId,
                            "ref" to firstRef,
                            "direction" to WORKFLOW_SWIPE_DIRECTION,
                        ),
                        risk = swipe.risk,
                    ),
                )
            }
            if (completedTools.size == 2) {
                return AgentPlanDecision.CallTool(proposeToolCall(goal, tools))
            }
            if (completedTools.size == 3) {
                val secondSnapshotResult = completedTools.last().toolResult
                check(secondSnapshotResult.success) { secondSnapshotResult.content }
                val secondSnapshot = JSONObject(secondSnapshotResult.content)
                check(secondSnapshot.getString("package") == SYSTEM_SETTINGS_PACKAGE) {
                    "连续动作第二次 snapshot 已离开限定的系统设置应用"
                }
                secondSnapshotId = secondSnapshot.getString("snapshot_id")
                check(secondSnapshotId != firstSnapshotId) {
                    "滚动后必须重新观察并生成新的 snapshot"
                }
                // long: back 只接受空参数；第二次观察只证明当前窗口仍属于目标 App，不从首次滚动结果恢复或复用任何 ref。
                val back = tools.single { it.name == DEVICE_BACK_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(name = back.name, arguments = emptyMap(), risk = back.risk),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 已在限定系统设置应用中连续完成滚动、重新观察与返回"
    }

    private class WorkflowOpenAppE2eLlm(
        private val expectedBeforePackageName: String,
        private val targetPackageName: String,
    ) : AgentLlm {
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
                check(JSONObject(snapshotResult.content).getString("package") == expectedBeforePackageName) {
                    "Workflow open_app 动作前 snapshot 不属于小灵验收页"
                }
                val openApp = tools.single { it.name == DEVICE_OPEN_APP_TOOL_NAME }
                return AgentPlanDecision.CallTool(
                    ToolCall(
                        name = openApp.name,
                        arguments = mapOf("package_name" to targetPackageName),
                        risk = openApp.risk,
                    ),
                )
            }
            return AgentPlanDecision.Complete
        }

        override suspend fun summarize(
            goal: String,
            toolCall: ToolCall,
            toolResult: ToolExecutionResult,
        ): String = "Workflow 打开应用动作已完成真实执行与目标包验证"
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
        const val OPERATION_DAY_OVERVIEW_REAL = "day_overview_real"
        const val OPERATION_PERSONAL_BRIEFING_REAL = "personal_briefing_real"
        const val OPERATION_TASK_INSPECTION_REAL = "task_inspection_real"
        const val OPERATION_TASK_RETRY_REAL = "task_retry_real"
        const val OPERATION_TASK_CANCEL_REAL = "task_cancel_real"
        const val OPERATION_NOTES_CREATE_REAL = "notes_create_real"
        const val OPERATION_NOTES_SEARCH_GET_REAL = "notes_search_get_real"
        const val OPERATION_NOTES_DELETE_REAL = "notes_delete_real"
        const val OPERATION_NOTES_UPDATE_REAL = "notes_update_real"
        const val OPERATION_LONG_SCHEDULED = "workflow_long_scheduled"
        const val OPERATION_LONG_STATUS = "workflow_long_status"
        const val OPERATION_WORKFLOW_TAP_REF = "workflow_tap_ref"
        const val OPERATION_WORKFLOW_TYPE_TEXT = "workflow_type_text"
        const val OPERATION_WORKFLOW_OPEN_APP = "workflow_open_app"
        const val OPERATION_WORKFLOW_WEATHER_OPEN_APP = "workflow_weather_open_app"
        const val OPERATION_WORKFLOW_BACK = "workflow_back"
        const val OPERATION_WORKFLOW_HOME = "workflow_home"
        const val OPERATION_WORKFLOW_SWIPE = "workflow_swipe"
        const val OPERATION_WORKFLOW_SETTINGS_MULTI = "workflow_settings_multi"
        private const val DEFAULT_ALLOWED_TOOL = "device.open_app"
        const val EXTRA_TASK_ID = "task_id"
        private const val LONG_DEBUG_PROFILE_ID = "stage151-long-workflow-profile"
        private const val LONG_DEBUG_STATE = "stage151-long-workflow-state"
        private const val LONG_DEBUG_ORIGINAL_PROFILE_ID = "original_profile_id"
        private const val PROVIDER_ID = "stage3-device-e2e-provider"
        private const val AGENT_PROFILE_ID = "stage3-device-e2e-profile"
        private const val E2E_CONVERSATION_ID = "conversation-redmi-workflow-device-action"
        private const val E2E_TYPE_TEXT_CONVERSATION_ID = "conversation-redmi-workflow-type-text"
        private const val E2E_BACK_CONVERSATION_ID = "conversation-redmi-workflow-back"
        private const val E2E_HOME_CONVERSATION_ID = "conversation-redmi-workflow-home"
        private const val E2E_SWIPE_CONVERSATION_ID = "conversation-redmi-workflow-swipe"
        private const val E2E_SETTINGS_MULTI_CONVERSATION_ID = "conversation-redmi-workflow-settings-multi"
        private const val DEVICE_SNAPSHOT_E2E_TOOL_NAME = "device.snapshot"
        private const val DEVICE_BACK_TOOL_NAME = "device.back"
        private const val DEVICE_HOME_TOOL_NAME = "device.home"
        private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
        private const val DEVICE_SWIPE_TOOL_NAME = "device.swipe"
        private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEM_CALCULATOR_PACKAGE = "com.android.calculator2"
        private const val GOOGLE_WEATHER_PACKAGE = "com.google.android.apps.weather"
        private const val WORKFLOW_TYPE_TEXT_HINT = "Workflow 安全文本输入框"
        private const val WORKFLOW_TYPE_TEXT_INPUT = "stage117_safe_text"
        private const val WORKFLOW_SWIPE_DIRECTION = "up"
        private const val TAG = "XiaoLingAgentE2e"
        private val HMAC_HEX_PATTERN = Regex("(?i)\\b[0-9a-f]{64}\\b")
        private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val TRANSIENT_SNAPSHOT_FAILURES = setOf(
            DeviceSnapshotFailure.NO_ACTIVE_WINDOW,
            DeviceSnapshotFailure.WINDOW_CHANGED,
        )
    }
}
