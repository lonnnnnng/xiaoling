package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceBounds
import com.longdev.xiaoling.device.DeviceActionCapture
import com.longdev.xiaoling.device.DeviceActionFailure
import com.longdev.xiaoling.device.DeviceActionOutcome
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceController
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceReferenceInspection
import com.longdev.xiaoling.device.DeviceReferenceTargetInspection
import com.longdev.xiaoling.device.DeviceScrollDirection
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import com.longdev.xiaoling.device.DeviceSnapshotNode
import com.longdev.xiaoling.device.DeviceSwipeViewportEvidence
import com.longdev.xiaoling.device.DeviceSwipeVerificationEvidence
import com.longdev.xiaoling.device.DeviceSwipeVisibleAnchor
import com.longdev.xiaoling.device.DeviceTypeTextReadBack
import com.longdev.xiaoling.knowledge.KnowledgeChunkRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentRecord
import com.longdev.xiaoling.knowledge.KnowledgeDocumentStore
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.knowledge.KnowledgeSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoLingToolRegistryTest {
    @Test
    fun productionForegroundWorkflowExposesOnlyVerifiedDeviceActionSlices() {
        val registry = productionRegistry(deviceController = FakeDeviceController(enabled = true))
        registry.bindRunContext(workflowDeviceContext(userIntent = "在当前安全输入框输入普通文本"))

        assertEquals(
            setOf(
                "device.snapshot",
                "device.open_app",
                "device.back",
                "device.home",
                "device.tap_ref",
                "device.type_text",
                "device.swipe",
            ),
            registry.availableTools()
                .filter { it.name.startsWith("device.") }
                .mapTo(linkedSetOf(), ToolDefinition::name),
        )
    }

    @Test
    fun productionWorkflowBackCompletesFromFreshSnapshotWithoutApproval() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "返回上一个系统设置页面"))
        val snapshotCall = ToolCall(
            id = "tool-call-back-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val backCall = ToolCall(
            id = "tool-call-back-action",
            name = "device.back",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )

        registry.beforeToolExecution(backCall, approval = null)
        val result = registry.execute(backCall)

        assertEquals(ToolApprovalPolicy.NONE, registry.definition("device.back")?.approvalPolicy)
        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("\"action\":\"back\""))
        registry.afterToolVerification(backCall, result)
        assertEquals(listOf("back"), provider.actions)
    }

    @Test
    fun productionWorkflowHomeCompletesFromFreshSnapshotWithoutApproval() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "返回 Android 桌面"))
        val snapshotCall = ToolCall(
            id = "tool-call-home-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val homeCall = ToolCall(
            id = "tool-call-home-action",
            name = "device.home",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )

        registry.beforeToolExecution(homeCall, approval = null)
        val result = registry.execute(homeCall)

        assertEquals(ToolApprovalPolicy.NONE, registry.definition("device.home")?.approvalPolicy)
        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("\"action\":\"home\""))
        assertTrue(result.content.contains("\"afterPackageName\":\"com.android.launcher3\""))
        registry.afterToolVerification(homeCall, result)
        assertEquals(listOf("home"), provider.actions)
    }

    @Test
    fun productionWorkflowOpenAppRequiresApprovalAndVerifiesWhitelistedTarget() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(
            workflowDeviceContext(
                userIntent = "打开系统计算器",
                targetAppPackage = "com.android.calculator2",
            ),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-open-app-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val openAppCall = ToolCall(
            id = "tool-call-open-app-action",
            name = "device.open_app",
            arguments = mapOf("package_name" to "com.android.calculator2"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val missingApproval = runCatching {
            registry.beforeToolExecution(openAppCall, approval = null)
        }.exceptionOrNull()
        assertTrue(missingApproval is IllegalStateException)
        assertTrue(missingApproval?.message.orEmpty().contains("缺少独立用户审批"))

        registry.beforeToolExecution(
            openAppCall,
            AgentToolApprovalEvidence(
                approved = true,
                decidedAt = 1_500L,
                processSessionId = "process-workflow",
            ),
        )
        val result = registry.execute(openAppCall)

        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, registry.definition("device.open_app")?.approvalPolicy)
        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("\"action\":\"open_app\""))
        assertTrue(result.content.contains("\"afterPackageName\":\"com.android.calculator2\""))
        registry.afterToolVerification(openAppCall, result)
        assertEquals(listOf("open_app:com.android.calculator2"), provider.actions)
        assertEquals(0, provider.referenceInspectionCount)
    }

    @Test
    fun productionWorkflowBackDoesNotUseApprovalTimeToExtendSnapshotTtl() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 31_001L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "返回上一个页面"))
        val snapshotCall = ToolCall(
            id = "tool-call-back-expired-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val backCall = ToolCall(
            id = "tool-call-back-expired-action",
            name = "device.back",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )

        val failure = runCatching {
            registry.beforeToolExecution(
                backCall,
                // long: 非法调用方即使传入旧审批时间，也不能把 SAFE back 的执行时钟拨回有效窗口。
                AgentToolApprovalEvidence(
                    approved = true,
                    decidedAt = 1_500L,
                    processSessionId = "process-workflow",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("已过期"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun productionWorkflowHomeDoesNotUseApprovalTimeToExtendSnapshotTtl() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 31_001L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "返回 Android 桌面"))
        val snapshotCall = ToolCall(
            id = "tool-call-home-expired-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val homeCall = ToolCall(
            id = "tool-call-home-expired-action",
            name = "device.home",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )

        val failure = runCatching {
            registry.beforeToolExecution(
                homeCall,
                // long: 非法调用方传入的旧审批时间不能延长 SAFE home 的 snapshot 生命周期。
                AgentToolApprovalEvidence(
                    approved = true,
                    decidedAt = 1_500L,
                    processSessionId = "process-workflow",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("已过期"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun registryExposesFirstInternalAgentTools() {
        val registry = testRegistry()

        val tools = registry.availableTools().associateBy { it.name }

        assertEquals(
            setOf(
                "app.current_time",
                "app.list_conversations",
                "app.search_conversations",
                "notes.list",
                "notes.search",
                "notes.create",
                "memory.search",
                "memory.remember",
                "knowledge.search",
            ),
            tools.keys,
        )
        assertEquals(ToolRisk.SAFE, tools.getValue("app.current_time").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.search_conversations").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.create").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("memory.remember").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("knowledge.search").risk)
        assertNotNull(tools.getValue("notes.create").inputSchema.singleOrNull { it.name == "title" && it.required })
        assertNotNull(tools.getValue("memory.remember").inputSchema.singleOrNull { it.name == "note" && it.required })
        assertNotNull(tools.getValue("knowledge.search").inputSchema.singleOrNull { it.name == "query" && it.required })
    }

    @Test
    fun productionToolsDeclareCompleteSchemaAndFailClosedPolicies() {
        val tools = testRegistry().availableTools().associateBy { it.name }
        val limitFields = listOf(
            "app.list_conversations",
            "app.search_conversations",
            "notes.list",
            "notes.search",
            "memory.search",
        ).map { name -> tools.getValue(name).inputSchema.single { it.name == "limit" } }

        assertTrue(tools.values.all { it.timeoutMs == 5_000L })
        assertTrue(tools.values.all { it.permissionPolicy.requiredAndroidPermissions.isEmpty() })
        val backgroundTools = tools.values
            .filter { it.permissionPolicy.supportsBackground }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "app.current_time",
                "app.list_conversations",
                "app.search_conversations",
                "notes.list",
                "notes.search",
                "memory.search",
                "knowledge.search",
            ),
            backgroundTools,
        )
        assertTrue(tools.values.filter { it.risk != ToolRisk.SAFE }.none { it.permissionPolicy.supportsBackground })
        assertTrue(limitFields.all {
            it.type == ToolInputType.INTEGER && it.minimum == 1.0 && it.maximum == 10.0
        })
        assertEquals(
            5.0,
            tools.getValue("knowledge.search").inputSchema.single { it.name == "limit" }.maximum,
        )
        assertEquals(ToolApprovalPolicy.NONE, tools.getValue("notes.search").approvalPolicy)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, tools.getValue("notes.create").approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.create").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.create").replaySafety)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("memory.remember").replaySafety)
        val deviceSnapshot = testRegistry().registeredTools().single { it.name == "device.snapshot" }
        assertEquals(ToolRisk.SAFE, deviceSnapshot.risk)
        assertFalse(deviceSnapshot.permissionPolicy.supportsBackground)
        val deviceActions = testRegistry().registeredTools().filter { it.name.startsWith("device.") && it.name != "device.snapshot" }
        assertEquals(
            setOf("device.open_app", "device.back", "device.home", "device.tap_ref", "device.type_text", "device.swipe"),
            deviceActions.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        assertTrue(deviceActions.all { !it.permissionPolicy.supportsBackground })
        assertTrue(deviceActions.all { it.verificationPolicy == ToolVerificationPolicy.EXECUTOR_VERIFIED })
        assertEquals(ToolRisk.REQUIRES_APPROVAL, deviceActions.single { it.name == "device.open_app" }.risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, deviceActions.single { it.name == "device.tap_ref" }.risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, deviceActions.single { it.name == "device.type_text" }.risk)
        assertEquals(ToolRisk.SAFE, deviceActions.single { it.name == "device.back" }.risk)
        assertEquals(ToolRisk.SAFE, deviceActions.single { it.name == "device.home" }.risk)
        assertEquals(ToolRisk.SAFE, deviceActions.single { it.name == "device.swipe" }.risk)
        assertTrue(deviceActions.single { it.name == "device.type_text" }.validateBeforeAudit)
        assertTrue(
            deviceActions.single { it.name == "device.back" }
                .validateArguments(mapOf("steps" to "2"))
                .errors.any { it.contains("未在 Schema 中声明") },
        )
        assertTrue(
            deviceActions.single { it.name == "device.home" }
                .validateArguments(mapOf("package_name" to "com.android.launcher3"))
                .errors.any { it.contains("未在 Schema 中声明") },
        )
        assertTrue(
            deviceActions.single { it.name == "device.type_text" }
                .validateArguments(mapOf("snapshot_id" to "snapshot-1", "ref" to "r1", "text" to "sk-abcdefghijklmnop"))
                .errors.any { it.contains("不允许输入") },
        )
        assertTrue(testRegistry().supportsCommittedEffectVerification("notes.create"))
        assertTrue(testRegistry().supportsCommittedEffectVerification("memory.remember"))
        assertEquals(
            setOf("Preference", "ProfileFact", "Episode", "Procedure"),
            tools.getValue("memory.remember").inputSchema.single { it.name == "type" }.enumValues,
        )

        val invalidTags = tools.getValue("memory.remember").validateArguments(
            mapOf(
                "note" to "用户喜欢紧凑界面",
                "tags" to (1..11).joinToString(",") { "tag$it" },
            ),
        )
        assertTrue(invalidTags.errors.contains("长期记忆标签不能超过 10 个"))
    }

    @Test
    fun foregroundWorkflowCanObserveAndUseOnlyApprovedTapRefWhenOptedIn() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = testRegistry(deviceController = provider)
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-device",
                userMessageId = "message-device",
                runId = "run-device",
                goal = "读取当前界面",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        assertEquals(
            setOf("device.snapshot", "device.open_app", "device.back", "device.home", "device.tap_ref", "device.type_text", "device.swipe"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val result = registry.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE))
        assertTrue(result.success)
        assertTrue(result.content.contains("snapshot-direct"))
        assertTrue(result.content.contains("继续"))
        assertEquals(1, provider.captureCount)

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-workflow",
                userMessageId = "message-workflow",
                runId = "run-workflow",
                goal = "Workflow 点击继续按钮",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.WORKFLOW,
                processSessionId = "process-workflow",
                workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                    workflowRunId = "workflow-run-current",
                    workflowStepId = "workflow-step-current",
                    userIntent = "点击当前页面的继续按钮",
                    targetAppPackage = "com.android.settings",
                ),
            ),
        )
        assertEquals(
            setOf("device.snapshot", "device.tap_ref"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-workflow-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val workflowResult = registry.execute(snapshotCall)
        assertTrue(workflowResult.success)
        assertTrue(workflowResult.content.contains("snapshot-direct"))
        registry.afterToolVerification(snapshotCall, workflowResult)
        val tapCall = ToolCall(
            id = "tool-call-workflow-tap",
            name = "device.tap_ref",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        registry.beforeToolExecution(
            tapCall,
            AgentToolApprovalEvidence(
                approved = true,
                decidedAt = 1_500L,
                processSessionId = "process-workflow",
            ),
        )
        val tapResult = registry.execute(tapCall)
        assertTrue(tapResult.success)
        assertEquals(true, tapResult.verified)
        assertTrue(tapResult.content.contains("workflow-device-action-result-v1"))
        assertFalse(tapResult.content.contains("\"nodes\""))
        assertFalse(tapResult.content.contains("\"ref\""))
        assertFalse(tapResult.content.contains("\"snapshot_id\""))
        registry.afterToolVerification(tapCall, tapResult)
        assertEquals(listOf("tap:snapshot-direct:r1"), provider.actions)
        val workflowTypeTextResult = registry.execute(
            ToolCall(
                name = "device.type_text",
                arguments = mapOf(
                    "snapshot_id" to "snapshot-direct",
                    "ref" to "r1",
                    "text" to "Workflow must remain closed",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        assertFalse(workflowTypeTextResult.success)
        assertTrue(workflowTypeTextResult.content.contains("尚未开放给 Workflow"))
        assertEquals(listOf("tap:snapshot-direct:r1"), provider.actions)
        val workflowActionResult = registry.execute(
            ToolCall(
                name = "device.open_app",
                arguments = mapOf("package_name" to "com.android.calculator2"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        assertFalse(workflowActionResult.success)
        assertTrue(workflowActionResult.content.contains("Workflow"))
        assertEquals(2, provider.captureCount)
        assertEquals(listOf("tap:snapshot-direct:r1"), provider.actions)

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-background",
                userMessageId = "message-background",
                runId = "run-background",
                goal = "后台读取界面",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
                invocationSource = AgentInvocationSource.WORKFLOW,
            ),
        )
        assertTrue(registry.availableTools().none { it.name.startsWith("device.") })
        val backgroundResult = registry.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE))
        assertFalse(backgroundResult.success)
        assertTrue(backgroundResult.content.contains("前台"))
        val backgroundActionResult = registry.execute(ToolCall(name = "device.back", arguments = emptyMap(), risk = ToolRisk.SAFE))
        assertFalse(backgroundActionResult.success)
        assertTrue(backgroundActionResult.content.contains("前台"))
        assertEquals(2, provider.captureCount)
        assertEquals(listOf("tap:snapshot-direct:r1"), provider.actions)
    }

    @Test
    fun testOnlyWorkflowTypeTextCompletesWithEditableTargetAndExactReadBack() = runTest {
        val text = "Workflow safe text"
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = true,
                redacted = false,
                actions = setOf(DeviceNodeAction.TYPE_TEXT),
            ),
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.tap_ref", "device.type_text"),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "在当前搜索框输入安全文本"))
        assertEquals(
            setOf("device.snapshot", "device.tap_ref", "device.type_text"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-type-text-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)
        val typeTextCall = ToolCall(
            id = "tool-call-type-text-action",
            name = "device.type_text",
            arguments = mapOf(
                "snapshot_id" to "snapshot-direct",
                "ref" to "r1",
                "text" to text,
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        registry.beforeToolExecution(
            typeTextCall,
            AgentToolApprovalEvidence(
                approved = true,
                decidedAt = 1_500L,
                processSessionId = "process-workflow",
            ),
        )

        val result = registry.execute(typeTextCall)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("\"action\":\"type_text\""))
        assertFalse(result.content.contains(text))
        assertFalse(result.content.contains("\"snapshot_id\""))
        assertFalse(result.content.contains("\"ref\""))
        registry.afterToolVerification(typeTextCall, result)
        assertEquals(listOf("type_text:snapshot-direct:r1"), provider.actions)
    }

    @Test
    fun testOnlyWorkflowTypeTextRejectsNonEditableTargetBeforeDeviceAction() = runTest {
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.TYPE_TEXT),
            ),
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.tap_ref", "device.type_text"),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "在当前搜索框输入安全文本"))
        val snapshotCall = ToolCall(
            id = "tool-call-type-text-denied-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val typeTextCall = ToolCall(
            id = "tool-call-type-text-denied-action",
            name = "device.type_text",
            arguments = mapOf(
                "snapshot_id" to "snapshot-direct",
                "ref" to "r1",
                "text" to "Workflow safe text",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val failure = runCatching {
            registry.beforeToolExecution(
                typeTextCall,
                AgentToolApprovalEvidence(
                    approved = true,
                    decidedAt = 1_500L,
                    processSessionId = "process-workflow",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("可编辑"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun testOnlyWorkflowSwipeAcceptsOpaqueCurrentViewportWithoutApproval() = runTest {
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.SWIPE),
            ),
            swipeViewport = DeviceSwipeViewportEvidence(
                packageName = "com.android.settings",
                windowId = 1,
                windowGeneration = 2L,
                targetFingerprint = "a".repeat(64),
                anchors = listOf(
                    DeviceSwipeVisibleAnchor("b".repeat(64), centerX = 50, centerY = 100),
                    DeviceSwipeVisibleAnchor("c".repeat(64), centerX = 50, centerY = 200),
                ),
            ),
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.swipe"),
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "向上滚动当前设置列表"))
        assertEquals(
            setOf("device.snapshot", "device.swipe"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val swipeCall = ToolCall(
            id = "tool-call-swipe-action",
            name = "device.swipe",
            arguments = mapOf(
                "snapshot_id" to "snapshot-direct",
                "ref" to "r1",
                "direction" to "up",
            ),
            risk = ToolRisk.SAFE,
        )

        registry.beforeToolExecution(swipeCall, approval = null)

        assertEquals(1, provider.referenceInspectionCount)
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun productionWorkflowSwipeCompletesWithTransientControllerEvidenceWithoutApproval() = runTest {
        val swipeEvidence = successfulSwipeEvidence()
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.SWIPE),
            ),
            swipeViewport = swipeEvidence.beforeViewport,
            swipeOutcomeEvidence = swipeEvidence,
        )
        val registry = productionRegistry(
            deviceController = provider,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "向上滚动当前设置列表"))
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-completion-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val swipeCall = ToolCall(
            id = "tool-call-swipe-completion-action",
            name = "device.swipe",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1", "direction" to "up"),
            risk = ToolRisk.SAFE,
        )

        registry.beforeToolExecution(swipeCall, approval = null)
        val result = registry.execute(swipeCall)
        registry.afterToolVerification(swipeCall, result)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals(listOf("swipe:snapshot-direct:r1:UP"), provider.actions)
        listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64))
            .forEach { fingerprint -> assertFalse(result.content.contains(fingerprint)) }
    }

    @Test
    fun testOnlyWorkflowSwipeRejectsEvidenceThatDoesNotMatchActionSnapshots() = runTest {
        val swipeEvidence = successfulSwipeEvidence()
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.SWIPE),
            ),
            swipeViewport = swipeEvidence.beforeViewport,
            swipeOutcomeEvidence = swipeEvidence,
            swipeAfterSnapshotWindowId = 99,
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.swipe"),
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "向上滚动当前设置列表"))
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-mismatch-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val swipeCall = ToolCall(
            id = "tool-call-swipe-mismatch-action",
            name = "device.swipe",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1", "direction" to "up"),
            risk = ToolRisk.SAFE,
        )
        registry.beforeToolExecution(swipeCall, approval = null)
        val result = registry.execute(swipeCall)

        val failure = runCatching { registry.afterToolVerification(swipeCall, result) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("滚动后置证据"))
    }

    @Test
    fun testOnlyWorkflowSwipeRejectsMissingCurrentViewportBeforeDeviceAction() = runTest {
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.SWIPE),
            ),
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.swipe"),
            clock = FakeAgentClock(nowMillis = 1_500L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "向上滚动当前设置列表"))
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-missing-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val swipeCall = ToolCall(
            id = "tool-call-swipe-missing-action",
            name = "device.swipe",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1", "direction" to "up"),
            risk = ToolRisk.SAFE,
        )

        val failure = runCatching { registry.beforeToolExecution(swipeCall, approval = null) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("viewport"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun safeWorkflowSwipeUsesExecutionClockInsteadOfInjectedApprovalTime() = runTest {
        val provider = FakeDeviceController(
            enabled = true,
            referenceTarget = DeviceReferenceTargetInspection(
                enabled = true,
                editable = false,
                redacted = false,
                actions = setOf(DeviceNodeAction.SWIPE),
            ),
            swipeViewport = DeviceSwipeViewportEvidence(
                packageName = "com.android.settings",
                windowId = 1,
                windowGeneration = 2L,
                targetFingerprint = "a".repeat(64),
                anchors = listOf(
                    DeviceSwipeVisibleAnchor("b".repeat(64), centerX = 50, centerY = 100),
                    DeviceSwipeVisibleAnchor("c".repeat(64), centerX = 50, centerY = 200),
                ),
            ),
        )
        val registry = testRegistry(
            deviceController = provider,
            workflowDeviceActionToolNames = setOf("device.swipe"),
            clock = FakeAgentClock(nowMillis = 31_000L),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "向上滚动当前设置列表"))
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-expired-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        val swipeCall = ToolCall(
            id = "tool-call-swipe-expired-action",
            name = "device.swipe",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1", "direction" to "up"),
            risk = ToolRisk.SAFE,
        )

        val failure = runCatching {
            registry.beforeToolExecution(
                swipeCall,
                AgentToolApprovalEvidence(
                    approved = true,
                    decidedAt = 1_500L,
                    processSessionId = "process-workflow",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("过期"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun workflowTestActionSeamRejectsNonActionTools() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            testRegistry(workflowDeviceActionToolNames = setOf("device.tap_ref", "device.snapshot"))
        }

        assertTrue(error.message.orEmpty().contains("device.snapshot"))
    }

    @Test
    fun switchingWorkflowRunInvalidatesPreviouslyVerifiedSnapshotAndReference() = runTest {
        val provider = FakeDeviceController(enabled = true)
        val registry = testRegistry(deviceController = provider)
        fun workflowContext(runId: String) = AgentToolExecutionContext(
            conversationId = "conversation-workflow",
            userMessageId = "message-$runId",
            runId = runId,
            goal = "Workflow 点击继续按钮",
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            processSessionId = "process-workflow",
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-current",
                workflowStepId = "workflow-step-current",
                userIntent = "点击当前页面的继续按钮",
                targetAppPackage = "com.android.settings",
            ),
        )
        registry.bindRunContext(workflowContext("run-old"))
        val snapshotCall = ToolCall(
            id = "tool-call-old-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val snapshotResult = registry.execute(snapshotCall)
        registry.afterToolVerification(snapshotCall, snapshotResult)

        registry.bindRunContext(workflowContext("run-new"))
        val oldReferenceCall = ToolCall(
            id = "tool-call-new-tap",
            name = "device.tap_ref",
            arguments = mapOf("snapshot_id" to "snapshot-direct", "ref" to "r1"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val failure = runCatching {
            registry.beforeToolExecution(
                oldReferenceCall,
                AgentToolApprovalEvidence(
                    approved = true,
                    decidedAt = 1_500L,
                    processSessionId = "process-workflow",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("当前 Run 已验证"))
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun switchingAgentRunRevokesControllerReferencesAndTransientSwipeViewport() {
        val provider = FakeDeviceController(enabled = true)
        val registry = testRegistry(deviceController = provider)
        val first = workflowDeviceContext(userIntent = "观察并滚动当前列表")

        registry.bindRunContext(first)
        registry.bindRunContext(first)

        assertEquals(0, provider.clearReferencesCount)

        registry.bindRunContext(first.copy(runId = "run-workflow-next"))

        assertEquals(1, provider.clearReferencesCount)
    }

    @Test
    fun disabledDeviceAgentCannotExposeOrExecuteSnapshot() = runTest {
        val provider = FakeDeviceController(enabled = false)
        val registry = testRegistry(deviceController = provider)
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-device-disabled",
                userMessageId = "message-device-disabled",
                runId = "run-device-disabled",
                goal = "读取当前界面",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        assertTrue(registry.availableTools().none { it.name.startsWith("device.") })
        val result = registry.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE))
        assertFalse(result.success)
        assertTrue(result.content.contains("尚未启用"))
        assertEquals(0, provider.captureCount)
    }

    @Test
    fun unavailableAccessibilityCannotExposeOrExecuteDeviceTools() = runTest {
        listOf(
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED to "未授权",
            DeviceAgentHealthState.SERVICE_DISCONNECTED to "尚未连接",
        ).forEach { (healthState, expectedMessage) ->
            val provider = FakeDeviceController(enabled = true, healthState = healthState)
            val registry = testRegistry(deviceController = provider)
            registry.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-device-${healthState.name.lowercase()}",
                    userMessageId = "message-device-${healthState.name.lowercase()}",
                    runId = "run-device-${healthState.name.lowercase()}",
                    goal = "读取当前界面",
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.WORKFLOW,
                ),
            )

            assertTrue(registry.availableTools().none { it.name.startsWith("device.") })
            val result = registry.execute(ToolCall(name = "device.snapshot", arguments = emptyMap(), risk = ToolRisk.SAFE))
            assertFalse(result.success)
            assertTrue(result.content.contains(expectedMessage))
            assertEquals(0, provider.captureCount)
        }
    }

    @Test
    fun directForegroundDeviceActionReturnsVerifiedAfterSnapshot() = runTest {
        val controller = FakeDeviceController(enabled = true)
        val registry = testRegistry(deviceController = controller)
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-device-action",
                userMessageId = "message-device-action",
                runId = "run-device-action",
                goal = "打开计算器",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val result = registry.execute(
            ToolCall(
                name = "device.open_app",
                arguments = mapOf("package_name" to "com.android.calculator2"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("com.android.calculator2"))
        assertEquals(listOf("open_app:com.android.calculator2"), controller.actions)
    }

    @Test
    fun currentTimeToolReturnsStableLocalTimeSnapshot() = runTest {
        val result = testRegistry().execute(ToolCall(name = "app.current_time", arguments = emptyMap(), risk = ToolRisk.SAFE))

        assertTrue(result.success)
        assertEquals("当前时间：2026-07-17 08:30:45 · 时区：Asia/Shanghai", result.content)
    }

    @Test
    fun knowledgeSearchReturnsStableReferencesBoundToCurrentRun() = runTest {
        val knowledgeStore = InMemoryKnowledgeDocumentStore()
        val registry = testRegistry(knowledgeStore = knowledgeStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-knowledge",
                    userMessageId = "message-knowledge",
                    runId = "run-knowledge",
                    goal = "从知识库查找发布门禁",
                ),
            )
        }

        val result = registry.execute(
            ToolCall(
                name = "knowledge.search",
                arguments = mapOf("query" to "发布门禁", "limit" to "2"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("发布检查清单.md"))
        assertTrue(result.content.contains("必须在 Redmi 真机完成验收"))
        assertEquals("发布门禁", knowledgeStore.lastQuery)
        assertEquals(2, knowledgeStore.lastLimit)
        assertEquals("conversation-knowledge", knowledgeStore.lastConversationId)
        assertEquals("run-knowledge", knowledgeStore.lastRunId)
        assertEquals(1, result.knowledgeReferences.size)
        val reference = result.knowledgeReferences.single()
        assertEquals("knowledge-retrieval-1", reference.retrievalId)
        assertEquals("document-release", reference.documentId)
        assertEquals("发布检查清单.md", reference.documentName)
        assertEquals(3, reference.documentRevision)
        assertEquals("chunk-release-r3-0", reference.chunkId)
        assertEquals(0, reference.chunkSequence)
        assertEquals(12, reference.startOffset)
        assertEquals(38, reference.endOffset)
    }

    @Test
    fun conversationSearchFindsOldConversation() = runTest {
        val result = testRegistry().execute(
            ToolCall(
                name = "app.search_conversations",
                arguments = mapOf("query" to "表格"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("匹配会话"))
        assertTrue(result.content.contains("Markdown 渲染排查"))
        assertTrue(result.content.contains("conversation-markdown"))
    }

    @Test
    fun notesCreateWritesAndVerifiesByReadingBack() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)

        val result = registry.execute(
            ToolCall(
                id = "tool-call-note-1",
                name = "notes.create",
                arguments = mapOf("title" to "发布检查", "content" to "发布前确认 release 签名和 SHA-256。"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("已创建并验证笔记：发布检查"))
        assertEquals("发布检查", noteStore.records.single().title)
        assertEquals(true, result.verified)
        assertEquals(
            ToolExecutionReceipt(
                toolCallId = "tool-call-note-1",
                operationId = "note-1",
                idempotencyKey = "tool-call-note-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
            result.executionReceipt,
        )
    }

    @Test
    fun notesCreateRepeatedToolCallReturnsSameOperationId() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-idempotent",
            name = "notes.create",
            arguments = mapOf("title" to "幂等笔记", "content" to "同一个 ToolCall 只能创建一次。"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val first = registry.execute(call)
        val replay = registry.execute(call)

        assertEquals(1, noteStore.records.size)
        assertEquals(first.executionReceipt?.operationId, replay.executionReceipt?.operationId)
        assertEquals(call.id, first.executionReceipt?.idempotencyKey)
        assertEquals(call.id, replay.executionReceipt?.idempotencyKey)
    }

    @Test
    fun notesCreateRejectsPayloadDriftForExistingToolCall() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val original = ToolCall(
            id = "tool-call-note-conflict",
            name = "notes.create",
            arguments = mapOf("title" to "原始标题", "content" to "原始正文"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        registry.execute(original)

        val error = runCatching {
            registry.execute(original.copy(arguments = mapOf("title" to "被篡改标题", "content" to "原始正文")))
        }.exceptionOrNull()

        assertTrue(error is AgentNoteIdempotencyConflictException)
        assertEquals(1, noteStore.records.size)
        assertEquals("原始标题", noteStore.records.single().title)
        assertEquals("原始正文", noteStore.records.single().content)
    }

    @Test
    fun notesCreateCommittedEffectVerificationReadsOriginalOperationWithoutCreatingAgain() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-readback-recovery",
            name = "notes.create",
            arguments = mapOf("title" to "恢复回读", "content" to "只读验证原 operation"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val created = registry.execute(call)
        val receipt = requireNotNull(created.executionReceipt)

        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertEquals(1, noteStore.records.size)
        assertEquals(created, recovered)
        assertEquals(receipt.operationId, recovered?.executionReceipt?.operationId)
    }

    @Test
    fun notesCreateMarksResultUnverifiedWhenReadBackFails() = runTest {
        val registry = testRegistry(
            noteStore = object : InMemoryAgentNoteStore() {
                override suspend fun get(id: String): AgentNoteRecord? = null
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "notes.create",
                arguments = mapOf("title" to "未验证笔记", "content" to "这条笔记回读失败。"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertEquals(false, result.success)
        assertEquals(false, result.verified)
        assertTrue(result.content.contains("回读验证失败"))
    }

    @Test
    fun memoryRememberPersistsSourceAndSearchOnlyReturnsEnabledMemory() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    runId = "run-1",
                    goal = "记住用户偏好",
                ),
            )
        }

        val remember = registry.execute(
            ToolCall(
                id = "tool-call-memory-1",
                name = "memory.remember",
                arguments = mapOf(
                    "note" to "用户喜欢紧凑、明亮但不刺眼的 Android UI",
                    "type" to "Preference",
                    "tags" to "ui,preference",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        memoryStore.records += AgentMemoryRecord(
            id = "disabled-memory",
            content = "不应该被检索的禁用记忆",
            tags = "hidden",
            type = "Episode",
            sourceConversationId = "conversation-old",
            sourceRunId = null,
            sourceSummary = "手工禁用",
            confidence = 0.8,
            enabled = false,
            createdAt = 1,
            updatedAt = 1,
        )
        val search = registry.execute(
            ToolCall(
                name = "memory.search",
                arguments = mapOf("query" to "Android UI"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(remember.success)
        assertEquals(true, remember.verified)
        assertTrue(remember.content.contains("来源：由 /agent Run 写入（来源 Run 可查看）"))
        assertFalse(remember.content.contains("记住用户偏好"))
        assertEquals(
            ToolExecutionReceipt(
                toolCallId = "tool-call-memory-1",
                operationId = "memory-1",
                idempotencyKey = "tool-call-memory-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
            remember.executionReceipt,
        )
        assertTrue(search.success)
        assertTrue(search.content.contains("用户喜欢紧凑、明亮但不刺眼的 Android UI"))
        assertTrue(search.content.contains("Preference"))
        assertTrue(!search.content.contains("不应该被检索"))
        assertEquals(listOf("memory-1"), search.memoryIdsUsed)
    }

    @Test
    fun memoryRememberReusesSameToolCallAndRejectsPayloadDrift() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore)
        val call = ToolCall(
            id = "tool-call-memory-idempotent",
            name = "memory.remember",
            arguments = mapOf("note" to "用户喜欢紧凑界面", "type" to "Preference"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val first = registry.execute(call)
        val replay = registry.execute(call)
        val conflict = runCatching {
            registry.execute(call.copy(arguments = call.arguments + ("note" to "用户喜欢宽松界面")))
        }.exceptionOrNull()

        assertEquals(first.executionReceipt?.operationId, replay.executionReceipt?.operationId)
        assertEquals(call.id, replay.executionReceipt?.idempotencyKey)
        assertEquals(1, memoryStore.records.size)
        assertTrue(conflict is AgentMemoryIdempotencyConflictException)
    }

    @Test
    fun memoryRememberCommittedEffectVerificationReadsOriginalOperationWithoutRememberingAgain() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-memory-recovery",
                    userMessageId = "message-memory-recovery",
                    runId = "run-memory-recovery",
                    goal = "恢复长期记忆验证",
                ),
            )
        }
        val call = ToolCall(
            id = "tool-call-memory-recovery",
            name = "memory.remember",
            arguments = mapOf(
                "note" to "用户喜欢紧凑界面",
                "type" to "Preference",
                "tags" to "ui,preference",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val created = registry.execute(call)
        val receipt = requireNotNull(created.executionReceipt)

        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertEquals(1, memoryStore.rememberCallCount)
        assertEquals(1, memoryStore.verificationCallCount)
        assertEquals(created, recovered)
        assertEquals(receipt, recovered?.executionReceipt)
    }

    @Test
    fun memoryRememberRecoveryFailuresExposeStableSuggestedActions() = runTest {
        val expectedSuggestions = mapOf(
            AgentMemoryOperationVerificationFailure.OPERATION_NOT_FOUND to "重新保存",
            AgentMemoryOperationVerificationFailure.EVIDENCE_INCOMPLETE to "历史版本",
            AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH to "重新确认",
            AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH to "重新确认",
            AgentMemoryOperationVerificationFailure.MEMORY_NOT_FOUND to "重新保存",
            AgentMemoryOperationVerificationFailure.MEMORY_CHANGED to "当前编辑结果",
            AgentMemoryOperationVerificationFailure.MEMORY_DISABLED to "启用该记忆",
            AgentMemoryOperationVerificationFailure.MEMORY_EXPIRED to "更新过期时间",
        )
        val call = ToolCall(
            id = "tool-call-memory-failure",
            name = "memory.remember",
            arguments = mapOf("note" to "用户喜欢紧凑界面"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "memory-failure",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )

        expectedSuggestions.forEach { (reason, expectedSuggestion) ->
            val registry = testRegistry(
                memoryStore = object : InMemoryAgentMemoryStore() {
                    override suspend fun verifyRememberedOperation(
                        idempotencyKey: String,
                        memoryId: String,
                        request: AgentMemoryWriteRequest,
                        nowMillis: Long,
                    ): AgentMemoryOperationVerification = AgentMemoryOperationVerification.Failed(reason)
                },
            )

            val result = requireNotNull(registry.verifyCommittedEffect(call, receipt))

            assertEquals(reason.name, result.recoveryFailure?.code)
            assertTrue(result.recoveryFailure?.reason.orEmpty().isNotBlank())
            assertTrue(result.recoveryFailure?.suggestedAction.orEmpty().contains(expectedSuggestion))
            assertTrue(result.recoveryFailure?.suggestedAction.orEmpty().contains("新 Run"))
        }
    }

    @Test
    fun disabledMemoryRecallHidesSearchToolAndDoesNotReadStore() = runTest {
        val memoryStore = InMemoryAgentMemoryStore()
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    runId = "run-1",
                    goal = "不使用记忆",
                    memoryRecallEnabled = false,
                ),
            )
        }

        assertTrue(registry.availableTools().none { it.name == "memory.search" })
        val result = registry.execute(
            ToolCall(name = "memory.search", arguments = mapOf("query" to "Android"), risk = ToolRisk.SAFE),
        )
        assertTrue(result.success)
        assertTrue(result.memoryIdsUsed.isEmpty())
        assertTrue(result.content.contains("关闭长期记忆召回"))
        assertTrue(memoryStore.searchQueries.isEmpty())
    }

    @Test
    fun memoryRememberMarksResultUnverifiedWhenReadBackFails() = runTest {
        val registry = testRegistry(
            memoryStore = object : InMemoryAgentMemoryStore() {
                override suspend fun get(memoryId: String): AgentMemoryRecord? = null
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "memory.remember",
                arguments = mapOf("note" to "需要回读确认的偏好"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertEquals(false, result.success)
        assertEquals(false, result.verified)
        assertTrue(result.content.contains("回读验证失败"))
    }

    private fun testRegistry(
        conversationStore: AgentConversationStore = InMemoryAgentConversationStore(),
        noteStore: InMemoryAgentNoteStore = InMemoryAgentNoteStore(),
        memoryStore: InMemoryAgentMemoryStore = InMemoryAgentMemoryStore(),
        knowledgeStore: KnowledgeDocumentStore = InMemoryKnowledgeDocumentStore(),
        deviceController: DeviceController = FakeDeviceController(enabled = false),
        workflowDeviceActionToolNames: Set<String> = setOf("device.tap_ref"),
        clock: AgentClock = FakeAgentClock(),
    ): XiaoLingToolRegistry {
        return XiaoLingToolRegistry(
            clock = clock,
            conversationStore = conversationStore,
            noteStore = noteStore,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
            deviceController = deviceController,
            workflowDeviceActionToolNames = workflowDeviceActionToolNames,
        )
    }

    private fun productionRegistry(
        deviceController: DeviceController,
        clock: AgentClock = FakeAgentClock(),
    ): XiaoLingToolRegistry {
        return XiaoLingToolRegistry(
            clock = clock,
            conversationStore = InMemoryAgentConversationStore(),
            noteStore = InMemoryAgentNoteStore(),
            memoryStore = InMemoryAgentMemoryStore(),
            knowledgeStore = InMemoryKnowledgeDocumentStore(),
            deviceController = deviceController,
        )
    }

    private fun successfulSwipeEvidence(): DeviceSwipeVerificationEvidence {
        val beforeViewport = DeviceSwipeViewportEvidence(
            packageName = "com.android.settings",
            windowId = 1,
            windowGeneration = 2L,
            targetFingerprint = "a".repeat(64),
            anchors = listOf(
                DeviceSwipeVisibleAnchor("b".repeat(64), centerX = 50, centerY = 100),
                DeviceSwipeVisibleAnchor("c".repeat(64), centerX = 50, centerY = 200),
                DeviceSwipeVisibleAnchor("d".repeat(64), centerX = 50, centerY = 300),
            ),
        )
        return DeviceSwipeVerificationEvidence(
            beforeViewport = beforeViewport,
            afterViewport = beforeViewport.copy(
                windowGeneration = 3L,
                anchors = listOf(
                    DeviceSwipeVisibleAnchor("b".repeat(64), centerX = 50, centerY = 0),
                    DeviceSwipeVisibleAnchor("c".repeat(64), centerX = 50, centerY = 100),
                    DeviceSwipeVisibleAnchor("e".repeat(64), centerX = 50, centerY = 200),
                ),
            ),
        )
    }

    private fun workflowDeviceContext(
        userIntent: String,
        targetAppPackage: String = "com.android.settings",
    ): AgentToolExecutionContext {
        return AgentToolExecutionContext(
            conversationId = "conversation-workflow",
            userMessageId = "message-workflow",
            runId = "run-workflow",
            goal = userIntent,
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
            invocationSource = AgentInvocationSource.WORKFLOW,
            processSessionId = "process-workflow",
            workflowDeviceActionContext = WorkflowDeviceActionRunContext(
                workflowRunId = "workflow-run-current",
                workflowStepId = "workflow-step-current",
                userIntent = userIntent,
                targetAppPackage = targetAppPackage,
            ),
        )
    }
}

private class FakeDeviceController(
    enabled: Boolean,
    private val healthState: DeviceAgentHealthState = if (enabled) {
        DeviceAgentHealthState.READY
    } else {
        DeviceAgentHealthState.AGENT_DISABLED
    },
    private val referenceTarget: DeviceReferenceTargetInspection = DeviceReferenceTargetInspection(
        enabled = true,
        editable = false,
        redacted = false,
        actions = setOf(DeviceNodeAction.TAP),
    ),
    private val swipeViewport: DeviceSwipeViewportEvidence? = null,
    private val swipeOutcomeEvidence: DeviceSwipeVerificationEvidence? = null,
    private val swipeAfterSnapshotWindowId: Int? = null,
) : DeviceController {
    var captureCount: Int = 0
    var referenceInspectionCount: Int = 0
    var clearReferencesCount: Int = 0
    val actions = mutableListOf<String>()

    override fun health(): DeviceAgentHealthState = healthState

    override fun currentWindowGeneration(): Long = 2L

    override fun inspectReference(snapshotId: String, ref: String): DeviceReferenceInspection {
        referenceInspectionCount += 1
        return DeviceReferenceInspection(
            currentWindowGeneration = 2L,
            matched = snapshotId == "snapshot-direct" && ref == "r1",
            target = referenceTarget.takeIf { snapshotId == "snapshot-direct" && ref == "r1" },
            swipeViewport = swipeViewport.takeIf { snapshotId == "snapshot-direct" && ref == "r1" },
        )
    }

    override fun clearReferences() {
        clearReferencesCount += 1
    }

    override suspend fun capture(): DeviceSnapshotCapture {
        captureCount += 1
        return DeviceSnapshotCapture.Success(
            snapshot = DeviceSnapshot(
                snapshotId = "snapshot-direct",
                packageName = "com.android.settings",
                windowTitle = "首页",
                windowId = 1,
                windowGeneration = 2L,
                capturedAt = 1_000L,
                expiresAt = 31_000L,
                nodes = listOf(
                    DeviceSnapshotNode(
                        index = 0,
                        parentIndex = null,
                        depth = 0,
                        role = "button",
                        text = "继续",
                        description = null,
                        hint = null,
                        bounds = DeviceBounds(0, 0, 100, 60),
                        enabled = true,
                        checked = null,
                        selected = false,
                        redacted = false,
                        ref = "r1",
                        actions = referenceTarget.actions,
                    ),
                ),
                redactedNodeCount = 0,
                truncated = false,
            ),
            references = emptyList(),
        )
    }

    override suspend fun openApp(packageName: String): DeviceActionCapture {
        actions += "open_app:$packageName"
        return successfulAction("open_app", packageName)
    }

    override suspend fun back(): DeviceActionCapture {
        actions += "back"
        return successfulAction("back")
    }

    override suspend fun home(): DeviceActionCapture {
        actions += "home"
        return successfulAction("home", "com.android.launcher3")
    }

    override suspend fun tap(snapshotId: String, ref: String): DeviceActionCapture {
        actions += "tap:$snapshotId:$ref"
        return successfulAction("tap")
    }

    override suspend fun typeText(snapshotId: String, ref: String, text: String): DeviceActionCapture {
        actions += "type_text:$snapshotId:$ref"
        return successfulAction(
            action = "type_text",
            typeTextReadBack = DeviceTypeTextReadBack(nodePath = listOf(0), text = text),
        )
    }

    override suspend fun swipe(
        snapshotId: String,
        ref: String,
        direction: DeviceScrollDirection,
    ): DeviceActionCapture {
        actions += "swipe:$snapshotId:$ref:${direction.name}"
        val afterViewport = swipeOutcomeEvidence?.afterViewport
        return successfulAction(
            action = "swipe",
            afterSnapshot = snapshot(
                packageName = afterViewport?.packageName ?: "com.android.settings",
                windowId = swipeAfterSnapshotWindowId ?: afterViewport?.windowId ?: 2,
                windowGeneration = afterViewport?.windowGeneration ?: 3L,
            ),
            swipeEvidence = swipeOutcomeEvidence,
        )
    }

    private fun successfulAction(
        action: String,
        packageName: String = "com.android.settings",
        typeTextReadBack: DeviceTypeTextReadBack? = null,
        afterSnapshot: DeviceSnapshot = snapshot(packageName),
        swipeEvidence: DeviceSwipeVerificationEvidence? = null,
    ): DeviceActionCapture.Success {
        return DeviceActionCapture.Success(
            DeviceActionOutcome(
                action = action,
                beforeSnapshotId = "snapshot-direct",
                afterSnapshot = afterSnapshot,
                verified = true,
                message = "verified",
                typeTextReadBack = typeTextReadBack,
                swipeEvidence = swipeEvidence,
            ),
        )
    }

    private fun snapshot(
        packageName: String,
        windowId: Int = 2,
        windowGeneration: Long = 3L,
    ): DeviceSnapshot = DeviceSnapshot(
        snapshotId = "snapshot-after",
        packageName = packageName,
        windowTitle = "结果页",
        windowId = windowId,
        windowGeneration = windowGeneration,
        capturedAt = 2_000L,
        expiresAt = 32_000L,
        nodes = emptyList(),
        redactedNodeCount = 0,
        truncated = false,
    )

    private fun failedAction(): DeviceActionCapture.Failed {
        return DeviceActionCapture.Failed(DeviceActionFailure.ACTION_FAILED, "fake action unavailable")
    }
}

private class InMemoryKnowledgeDocumentStore : KnowledgeDocumentStore {
    var lastQuery: String? = null
    var lastLimit: Int? = null
    var lastConversationId: String? = null
    var lastRunId: String? = null

    override suspend fun search(
        query: String,
        limit: Int,
        sourceConversationId: String?,
        sourceRunId: String?,
    ): KnowledgeSearchResult {
        lastQuery = query
        lastLimit = limit
        lastConversationId = sourceConversationId
        lastRunId = sourceRunId
        return KnowledgeSearchResult(
            hits = listOf(
                KnowledgeSearchHit(
                    chunkId = "chunk-release-r3-0",
                    documentId = "document-release",
                    documentRevision = 3,
                    documentName = "发布检查清单.md",
                    sequence = 0,
                    startOffset = 12,
                    endOffset = 38,
                    text = "必须在 Redmi 真机完成验收。",
                ),
            ).take(limit),
            retrieval = KnowledgeRetrievalRecord(
                id = "knowledge-retrieval-1",
                query = query,
                chunkIds = listOf("chunk-release-r3-0"),
                documentIds = listOf("document-release"),
                sourceConversationId = sourceConversationId,
                sourceRunId = sourceRunId,
                createdAt = 1_784_252_245_000,
            ),
        )
    }

    override suspend fun importUtf8Document(displayName: String, mimeType: String, bytes: ByteArray): KnowledgeDocumentRecord =
        error("测试不支持导入")

    override suspend fun replaceUtf8Document(
        documentId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): KnowledgeDocumentRecord = error("测试不支持替换")

    override suspend fun getDocument(documentId: String): KnowledgeDocumentRecord? = null
    override suspend fun listDocuments(): List<KnowledgeDocumentSummary> = emptyList()
    override suspend fun getDocumentDetail(documentId: String): KnowledgeDocumentDetail? = null
    override suspend fun getChunks(documentId: String): List<KnowledgeChunkRecord> = emptyList()
    override suspend fun retainCurrentReferences(references: List<KnowledgeReference>): List<KnowledgeReference> = references
    override suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalRecord> = emptyList()
    override suspend fun setEnabled(documentId: String, enabled: Boolean): KnowledgeDocumentRecord? = null
    override suspend fun delete(documentId: String): Boolean = false
}

private class FakeAgentClock(
    private val nowMillis: Long = 1_784_252_245_000,
) : AgentClock {
    override fun nowMillis(): Long = nowMillis
    override fun formattedNow(): String = "2026-07-17 08:30:45"
    override fun zoneId(): String = "Asia/Shanghai"
}

private class InMemoryAgentConversationStore : AgentConversationStore {
    private val conversations = listOf(
        AgentConversationRecord(
            id = "conversation-markdown",
            title = "Markdown 渲染排查",
            summary = "处理表格、引用和图片渲染。",
            messageCount = 12,
            updatedAt = 10,
        ),
        AgentConversationRecord(
            id = "conversation-release",
            title = "Release 发布",
            summary = "构建正式签名 APK。",
            messageCount = 8,
            updatedAt = 9,
        ),
    )

    override suspend fun list(limit: Int): List<AgentConversationRecord> = conversations.take(limit)

    override suspend fun search(query: String, limit: Int): List<AgentConversationRecord> {
        return conversations
            .filter { it.title.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true) }
            .take(limit)
    }
}

private open class InMemoryAgentNoteStore : AgentNoteStore {
    val records = mutableListOf<AgentNoteRecord>()
    private val recordsByIdempotencyKey = mutableMapOf<String, AgentNoteRecord>()

    override suspend fun list(limit: Int): List<AgentNoteRecord> = records.take(limit)

    override suspend fun search(query: String, limit: Int): List<AgentNoteRecord> {
        return records
            .filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun create(title: String, content: String, idempotencyKey: String): AgentNoteRecord {
        recordsByIdempotencyKey[idempotencyKey]?.let { existing ->
            if (existing.title != title || existing.content != content) {
                throw AgentNoteIdempotencyConflictException()
            }
            return existing
        }
        return AgentNoteRecord(
            id = "note-${records.size + 1}",
            title = title,
            content = content,
            createdAt = 1_784_252_245_000 + records.size,
            updatedAt = 1_784_252_245_000 + records.size,
        ).also {
            records += it
            recordsByIdempotencyKey[idempotencyKey] = it
        }
    }

    open override suspend fun get(id: String): AgentNoteRecord? = records.firstOrNull { it.id == id }
}

private open class InMemoryAgentMemoryStore : AgentMemoryStore {
    val records = mutableListOf<AgentMemoryRecord>()
    val searchQueries = mutableListOf<String>()
    var rememberCallCount = 0
    var verificationCallCount = 0
    private val recordsByIdempotencyKey = mutableMapOf<String, AgentMemoryRecord>()
    private val requestsByIdempotencyKey = mutableMapOf<String, AgentMemoryWriteRequest>()

    override suspend fun remember(
        content: String,
        tags: String,
        type: String,
        source: AgentMemorySource,
        confidence: Double,
        idempotencyKey: String?,
    ): AgentMemoryRecord {
        rememberCallCount += 1
        idempotencyKey?.let { key ->
            recordsByIdempotencyKey[key]?.let { existing ->
                if (
                    existing.content != content || existing.tags != tags || existing.type != type ||
                    existing.sourceConversationId != source.conversationId || existing.sourceRunId != source.runId ||
                    existing.sourceSummary != source.summary || existing.confidence != confidence
                ) {
                    throw AgentMemoryIdempotencyConflictException()
                }
                return existing
            }
        }
        val record = AgentMemoryRecord(
            id = "memory-${records.size + 1}",
            content = content,
            tags = tags,
            type = type,
            sourceConversationId = source.conversationId,
            sourceRunId = source.runId,
            sourceSummary = source.summary,
            confidence = confidence,
            enabled = true,
            createdAt = 1_784_252_245_000 + records.size,
            updatedAt = 1_784_252_245_000 + records.size,
        )
        records += record
        idempotencyKey?.let {
            recordsByIdempotencyKey[it] = record
            requestsByIdempotencyKey[it] = AgentMemoryWriteRequest(content, tags, type, source, confidence)
        }
        return record
    }

    override suspend fun verifyRememberedOperation(
        idempotencyKey: String,
        memoryId: String,
        request: AgentMemoryWriteRequest,
        nowMillis: Long,
    ): AgentMemoryOperationVerification {
        verificationCallCount += 1
        val originalRequest = requestsByIdempotencyKey[idempotencyKey]
            ?: return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.OPERATION_NOT_FOUND)
        if (originalRequest != request) {
            return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.PAYLOAD_MISMATCH)
        }
        val memory = recordsByIdempotencyKey[idempotencyKey]
            ?.takeIf { it.id == memoryId }
            ?: return AgentMemoryOperationVerification.Failed(AgentMemoryOperationVerificationFailure.OPERATION_MISMATCH)
        return AgentMemoryOperationVerification.Verified(memory)
    }

    open override suspend fun get(memoryId: String): AgentMemoryRecord? = records.firstOrNull { it.id == memoryId }

    override suspend fun search(query: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryRecord> {
        searchQueries += query
        val normalized = query.trim()
        return records
            .filter { !enabledOnly || it.enabled }
            .filter { record ->
                normalized.isBlank() ||
                    record.content.contains(normalized, ignoreCase = true) ||
                    record.tags.contains(normalized, ignoreCase = true) ||
                    record.sourceSummary.contains(normalized, ignoreCase = true)
            }
            .take(limit)
    }
}
