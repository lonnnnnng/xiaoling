package com.longdev.xiaoling.agent

import android.Manifest
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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoLingToolRegistryTest {
    @Test
    fun productionForegroundWorkflowExposesActionsOnlyAfterVerifiedSnapshot() = runTest {
        val registry = productionRegistry(deviceController = FakeDeviceController(enabled = true))
        registry.bindRunContext(workflowDeviceContext(userIntent = "在当前安全输入框输入普通文本"))

        assertEquals(
            setOf("device.snapshot"),
            registry.availableTools()
                .filter { it.name.startsWith("device.") }
                .mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-production-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))

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
    fun productionWorkflowReturnToXiaolingExposesOnlyBackAfterVerifiedSnapshot() = runTest {
        val registry = productionRegistry(deviceController = FakeDeviceController(enabled = true))
        registry.bindRunContext(
            workflowDeviceContext(
                userIntent = "返回小灵应用",
                targetAppPackage = "com.android.deskclock",
            ),
        )

        assertEquals(
            setOf("device.snapshot"),
            registry.availableTools()
                .filter { it.name.startsWith("device.") }
                .mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-return-xiaoling-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))

        assertEquals(
            setOf("device.snapshot", "device.back"),
            registry.availableTools()
                .filter { it.name.startsWith("device.") }
                .mapTo(linkedSetOf(), ToolDefinition::name),
        )
        assertEquals(null, registry.definition("device.open_app"))
        val rejected = runCatching {
            registry.beforeToolExecution(
                ToolCall(
                    id = "tool-call-return-xiaoling-open-app",
                    name = "device.open_app",
                    arguments = mapOf("package_name" to "com.longdev.xiaoling"),
                    risk = ToolRisk.REQUIRES_APPROVAL,
                ),
                approval = null,
            )
        }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertTrue(rejected?.message.orEmpty().contains("步骤意图不允许"))
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
                windowGuarded = true,
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

        val tools = registry.registeredTools().associateBy { it.name }

        assertTrue(
            tools.keys.containsAll(
                setOf(
                "app.current_time",
                "app.get_info",
                "app.get_battery",
                "app.get_connectivity",
                "app.get_storage",
                "agent.get_profile",
                "app.list_conversations",
                "app.search_conversations",
                "app.get_conversation",
                "calendar.list_events",
                "calendar.next_event",
                "calendar.search_events",
                "calendar.get",
                "contacts.search",
                "contacts.get",
                "calendar.update_event",
                "calendar.delete_event",
                "tasks.list",
                "tasks.inspect",
                "tasks.retry",
                "tasks.pause",
                "tasks.resume",
                "notes.list",
                "notes.search",
                "notes.get",
                "notes.create",
                "notes.update",
                "notes.delete",
                "memory.search",
                "memory.get",
                "memory.delete",
                "memory.remember",
                "knowledge.search",
                ),
            ),
        )
        assertEquals(ToolRisk.SAFE, tools.getValue("app.current_time").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.get_info").risk)
        assertEquals(emptyList<String>(), tools.getValue("app.get_info").inputSchema)
        assertTrue(tools.getValue("app.get_info").permissionPolicy.supportsBackground)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.get_battery").risk)
        assertEquals(emptyList<String>(), tools.getValue("app.get_battery").inputSchema)
        assertFalse(tools.getValue("app.get_battery").permissionPolicy.supportsBackground)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.get_connectivity").risk)
        assertEquals(emptyList<String>(), tools.getValue("app.get_connectivity").inputSchema)
        assertFalse(tools.getValue("app.get_connectivity").permissionPolicy.supportsBackground)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.get_storage").risk)
        assertEquals(emptyList<String>(), tools.getValue("app.get_storage").inputSchema)
        assertFalse(tools.getValue("app.get_storage").permissionPolicy.supportsBackground)
        assertEquals(ToolRisk.SAFE, tools.getValue("agent.get_profile").risk)
        assertEquals(emptyList<String>(), tools.getValue("agent.get_profile").inputSchema)
        assertFalse(tools.getValue("agent.get_profile").permissionPolicy.supportsBackground)
        assertEquals(
            listOf("conversation_id"),
            tools.getValue("app.get_conversation").inputSchema.map { it.name },
        )
        assertEquals(ToolRisk.SAFE, tools.getValue("app.get_conversation").risk)
        assertFalse(tools.getValue("app.get_conversation").permissionPolicy.supportsBackground)
        assertEquals(ToolRisk.SAFE, tools.getValue("app.search_conversations").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("calendar.list_events").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("calendar.next_event").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("calendar.search_events").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("calendar.get").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("contacts.search").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("contacts.get").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("calendar.update_event").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("calendar.delete_event").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("tasks.list").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("tasks.inspect").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("tasks.retry").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("tasks.pause").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("tasks.resume").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.create").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.update").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("notes.delete").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("memory.remember").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("memory.get").risk)
        assertEquals(ToolRisk.REQUIRES_APPROVAL, tools.getValue("memory.delete").risk)
        assertEquals(ToolRisk.SAFE, tools.getValue("knowledge.search").risk)
        assertFalse(registry.availableTools().any { tool -> tool.name == "tasks.retry" })
        assertFalse(registry.availableTools().any { tool -> tool.name in setOf("tasks.pause", "tasks.resume") })
        assertNotNull(tools.getValue("notes.create").inputSchema.singleOrNull { it.name == "title" && it.required })
        assertEquals(listOf("note_id"), tools.getValue("notes.get").inputSchema.map { it.name })
        assertEquals(ToolRisk.SAFE, tools.getValue("notes.get").risk)
        assertEquals(listOf("note_id"), tools.getValue("notes.delete").inputSchema.map { it.name })
        assertEquals(
            listOf("note_id", "expected_revision", "title", "content"),
            tools.getValue("notes.update").inputSchema.map { it.name },
        )
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.update").verificationPolicy)
        assertEquals(ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL, tools.getValue("notes.update").notCommittedReplayPolicy)
        assertFalse(tools.getValue("notes.update").permissionPolicy.supportsBackground)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.delete").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.delete").replaySafety)
        assertFalse(tools.getValue("notes.delete").permissionPolicy.supportsBackground)
        assertNotNull(tools.getValue("calendar.list_events").inputSchema.singleOrNull { it.name == "days_ahead" })
        assertEquals(emptyList<String>(), tools.getValue("calendar.next_event").inputSchema)
        assertTrue(tools.getValue("calendar.next_event").validateArguments(emptyMap()).errors.isEmpty())
        assertTrue(tools.getValue("calendar.next_event").validateArguments(mapOf("limit" to "1")).errors.isNotEmpty())
        assertNotNull(tools.getValue("calendar.search_events").inputSchema.singleOrNull { it.name == "query" && it.required })
        assertEquals(listOf("event_id"), tools.getValue("calendar.get").inputSchema.map { it.name })
        assertTrue(tools.getValue("calendar.get").validateArguments(mapOf("event_id" to "calendar-1")).errors.isEmpty())
        assertEquals(listOf("query", "limit"), tools.getValue("contacts.search").inputSchema.map { it.name })
        assertEquals(listOf("contact_id"), tools.getValue("contacts.get").inputSchema.map { it.name })
        assertTrue(tools.getValue("contacts.get").validateArguments(mapOf("contact_id" to "contact-1")).errors.isEmpty())
        assertFalse(tools.getValue("contacts.search").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("contacts.get").permissionPolicy.supportsBackground)
        assertEquals(
            listOf("event_id", "expected_fingerprint", "scope", "title", "start_at", "end_at", "time_zone"),
            tools.getValue("calendar.update_event").inputSchema.map { it.name },
        )
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, tools.getValue("calendar.update_event").replaySafety)
        assertEquals(ToolNotCommittedReplayPolicy.DENY, tools.getValue("calendar.update_event").notCommittedReplayPolicy)
        assertFalse(tools.getValue("calendar.update_event").permissionPolicy.supportsBackground)
        assertEquals(
            listOf("event_id", "expected_fingerprint", "scope"),
            tools.getValue("calendar.delete_event").inputSchema.map { it.name },
        )
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, tools.getValue("calendar.delete_event").replaySafety)
        assertEquals(ToolNotCommittedReplayPolicy.DENY, tools.getValue("calendar.delete_event").notCommittedReplayPolicy)
        assertFalse(tools.getValue("calendar.delete_event").permissionPolicy.supportsBackground)
        assertTrue(
            tools.getValue("calendar.get")
                .validateArguments(mapOf("event_id" to "calendar-9223372036854775808"))
                .errors
                .isNotEmpty(),
        )
        assertNotNull(tools.getValue("tasks.inspect").inputSchema.singleOrNull { it.name == "name" && it.required })
        assertEquals(
            listOf("name"),
            tools.getValue("tasks.retry").inputSchema.map { field -> field.name },
        )
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("tasks.retry").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("tasks.retry").replaySafety)
        assertFalse(tools.getValue("tasks.retry").permissionPolicy.supportsBackground)
        listOf("tasks.pause", "tasks.resume").forEach { toolName ->
            assertEquals(listOf("name"), tools.getValue(toolName).inputSchema.map { field -> field.name })
            assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue(toolName).verificationPolicy)
            assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue(toolName).replaySafety)
            assertFalse(tools.getValue(toolName).permissionPolicy.supportsBackground)
        }
        assertNotNull(tools.getValue("memory.remember").inputSchema.singleOrNull { it.name == "note" && it.required })
        assertEquals(listOf("memory_id"), tools.getValue("memory.get").inputSchema.map { it.name })
        assertEquals(listOf("memory_id"), tools.getValue("memory.delete").inputSchema.map { it.name })
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("memory.delete").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("memory.delete").replaySafety)
        assertEquals(ToolNotCommittedReplayPolicy.DENY, tools.getValue("memory.delete").notCommittedReplayPolicy)
        assertFalse(tools.getValue("memory.delete").permissionPolicy.supportsBackground)
        assertNotNull(tools.getValue("knowledge.search").inputSchema.singleOrNull { it.name == "query" && it.required })
    }

    @Test
    fun taskRetryIsOnlyAvailableToDirectForegroundAgent() {
        val registry = testRegistry()

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "重试每日回顾任务",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNotNull(registry.definition("tasks.retry"))
        assertTrue(registry.availableTools().any { tool -> tool.name == "tasks.retry" })

        registry.bindRunContext(workflowDeviceContext(userIntent = "重试每日回顾任务"))
        assertNull(registry.definition("tasks.retry"))
        assertFalse(registry.availableTools().any { tool -> tool.name == "tasks.retry" })

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-background",
                userMessageId = "message-background",
                runId = "run-background",
                goal = "重试每日回顾任务",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNull(registry.definition("tasks.retry"))
        assertFalse(registry.availableTools().any { tool -> tool.name == "tasks.retry" })
    }

    @Test
    fun taskScheduleControlIsOnlyAvailableToDirectForegroundAgent() {
        val registry = testRegistry()

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "暂停每日回顾提醒",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNotNull(registry.definition("tasks.pause"))
        assertNotNull(registry.definition("tasks.resume"))
        assertTrue(registry.availableTools().any { tool -> tool.name == "tasks.pause" })
        assertTrue(registry.availableTools().any { tool -> tool.name == "tasks.resume" })

        registry.bindRunContext(workflowDeviceContext(userIntent = "暂停每日回顾提醒"))
        assertNull(registry.definition("tasks.pause"))
        assertNull(registry.definition("tasks.resume"))

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-background",
                userMessageId = "message-background",
                runId = "run-background",
                goal = "恢复每日回顾提醒",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertFalse(registry.availableTools().any { tool -> tool.name in setOf("tasks.pause", "tasks.resume") })
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
            "tasks.list",
        ).map { name -> tools.getValue(name).inputSchema.single { it.name == "limit" } }

        assertTrue(tools.values.all { it.timeoutMs == 5_000L })
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR),
            tools.getValue("calendar.list_events").permissionPolicy.requiredAndroidPermissions,
        )
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR),
            tools.getValue("calendar.next_event").permissionPolicy.requiredAndroidPermissions,
        )
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR),
            tools.getValue("calendar.search_events").permissionPolicy.requiredAndroidPermissions,
        )
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR),
            tools.getValue("calendar.get").permissionPolicy.requiredAndroidPermissions,
        )
        assertEquals(
            setOf(Manifest.permission.READ_CONTACTS),
            tools.getValue("contacts.search").permissionPolicy.requiredAndroidPermissions,
        )
        assertEquals(
            setOf(Manifest.permission.READ_CONTACTS),
            tools.getValue("contacts.get").permissionPolicy.requiredAndroidPermissions,
        )
        assertTrue(
            tools.values
                .filterNot { it.name.startsWith("calendar.") || it.name.startsWith("contacts.") }
                .all { it.permissionPolicy.requiredAndroidPermissions.isEmpty() },
        )
        assertFalse(tools.getValue("calendar.list_events").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("calendar.next_event").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("calendar.search_events").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("calendar.get").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("calendar.create_event").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("contacts.search").permissionPolicy.supportsBackground)
        assertFalse(tools.getValue("contacts.get").permissionPolicy.supportsBackground)
        val backgroundTools = tools.values
            .filter { it.permissionPolicy.supportsBackground }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "app.current_time",
                "app.get_info",
                "app.list_conversations",
                "app.search_conversations",
                "notes.list",
                "notes.search",
                "notes.get",
                "memory.search",
                "memory.get",
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
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, tools.getValue("notes.update").approvalPolicy)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, tools.getValue("notes.delete").approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.create").verificationPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.update").verificationPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, tools.getValue("notes.delete").verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.create").replaySafety)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.update").replaySafety)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, tools.getValue("notes.delete").replaySafety)
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
        val openApp = deviceActions.single { it.name == "device.open_app" }
        assertEquals(ToolRisk.REQUIRES_APPROVAL, openApp.risk)
        val packageNameField = openApp.inputSchema.single { it.name == "package_name" }
        assertTrue(packageNameField.description.contains("Google 天气"))
        assertTrue(
            packageNameField.enumValues.contains("com.google.android.apps.weather"),
        )
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
        assertTrue(testRegistry().supportsCommittedEffectVerification("memory.delete"))
        assertFalse(testRegistry().supportsCommittedEffectVerification("calendar.get"))
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
    fun memoryDeleteIsOnlyAvailableToDirectForegroundAgent() {
        val registry = testRegistry()

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-memory-delete",
                userMessageId = "message-memory-delete",
                runId = "run-memory-delete",
                goal = "删除这条长期记忆",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNotNull(registry.definition("memory.delete"))
        assertTrue(registry.availableTools().any { it.name == "memory.delete" })

        registry.bindRunContext(workflowDeviceContext(userIntent = "删除这条长期记忆"))
        assertNull(registry.definition("memory.delete"))
        assertFalse(registry.availableTools().any { it.name == "memory.delete" })

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-memory-delete-background",
                userMessageId = "message-memory-delete-background",
                runId = "run-memory-delete-background",
                goal = "删除这条长期记忆",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNull(registry.definition("memory.delete"))
        assertFalse(registry.availableTools().any { it.name == "memory.delete" })
    }

    @Test
    fun memoryDeleteCommitsStableIdAndRecoveryOnlyVerifiesCurrentAbsence() = runTest {
        val memoryId = "memory-12345678-1234-1234-1234-123456789abc"
        val memoryStore = InMemoryAgentMemoryStore().apply {
            records += AgentMemoryRecord(
                id = memoryId,
                content = "用户喜欢紧凑界面",
                tags = "ui",
                type = "Preference",
                sourceConversationId = "conversation-memory-delete",
                sourceRunId = "run-memory-source",
                sourceSummary = "用户确认保存",
                confidence = 0.9,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            )
        }
        val registry = testRegistry(memoryStore = memoryStore).also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-memory-delete",
                    userMessageId = "message-memory-delete",
                    runId = "run-memory-delete",
                    goal = "删除这条长期记忆",
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.DIRECT,
                ),
            )
        }
        val call = ToolCall(
            id = "tool-call-memory-delete",
            name = "memory.delete",
            arguments = mapOf("memory_id" to memoryId),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val bypassed = registry.execute(call)
        assertFalse(bypassed.success)
        assertTrue(bypassed.content.contains("先搜索并读取详情"))
        assertEquals(0, memoryStore.deleteCallCount)

        val ambiguousMemoryId = "memory-12345678-1234-1234-1234-123456789def"
        memoryStore.records += memoryStore.records.single().copy(
            id = ambiguousMemoryId,
            content = "用户喜欢紧凑界面（另一条）",
        )
        val truncatedSearch = registry.execute(
            ToolCall(
                name = "memory.search",
                arguments = mapOf("query" to "紧凑界面", "limit" to "1"),
                risk = ToolRisk.SAFE,
            ),
        )
        val truncatedDetail = registry.execute(
            ToolCall(
                name = "memory.get",
                arguments = mapOf("memory_id" to memoryId),
                risk = ToolRisk.SAFE,
            ),
        )
        assertEquals(listOf(memoryId), truncatedSearch.memoryIdsUsed)
        assertTrue(truncatedDetail.success)
        assertFalse(registry.execute(call).success)
        assertEquals(0, memoryStore.deleteCallCount)
        memoryStore.records.removeAll { it.id == ambiguousMemoryId }

        val search = registry.execute(
            ToolCall(
                name = "memory.search",
                arguments = mapOf("query" to "紧凑界面"),
                risk = ToolRisk.SAFE,
            ),
        )
        val detail = registry.execute(
            ToolCall(
                name = "memory.get",
                arguments = mapOf("memory_id" to memoryId),
                risk = ToolRisk.SAFE,
            ),
        )
        assertTrue(search.success)
        assertTrue(detail.success)

        val deleted = registry.execute(call)
        val receipt = checkNotNull(deleted.executionReceipt)
        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertTrue(deleted.success)
        assertEquals(true, deleted.verified)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, receipt.status)
        assertEquals(call.id, receipt.toolCallId)
        assertEquals(call.id, receipt.idempotencyKey)
        assertEquals(memoryId, receipt.operationId)
        assertNull(memoryStore.get(memoryId))
        assertEquals(1, memoryStore.deleteCallCount)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
        assertEquals(1, memoryStore.deleteCallCount)

        memoryStore.records += memoryStore.deletedRecord!!
        val restored = registry.verifyCommittedEffect(call, receipt)
        assertEquals(false, restored?.success)
        assertEquals(false, restored?.verified)
        assertEquals(1, memoryStore.deleteCallCount)

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-memory-delete-next",
                userMessageId = "message-memory-delete-next",
                runId = "run-memory-delete-next",
                goal = "删除这条长期记忆",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        val crossRun = registry.execute(call.copy(id = "tool-call-memory-delete-next"))
        assertFalse(crossRun.success)
        assertEquals(1, memoryStore.deleteCallCount)
    }

    @Test
    fun contactsSearchReturnsStableCandidatesWithoutLeakingDetailValues() = runTest {
        var capturedQuery = ""
        var capturedLimit = -1
        val reader = object : ContactReader {
            override suspend fun searchContacts(query: String, limit: Int): ContactSearchResult {
                capturedQuery = query
                capturedLimit = limit
                return ContactSearchResult.Success(
                    listOf(
                        ContactSearchRecord(
                            contactId = 42L,
                            displayName = "张三\n这只是姓名",
                            matchedFields = setOf(ContactMatchField.NAME, ContactMatchField.EMAIL),
                        ),
                    ),
                )
            }

            override suspend fun getContact(contactId: Long): ContactDetailReadResult =
                ContactDetailReadResult.ProviderUnavailable
        }
        val registry = testRegistry(contactReader = reader)

        registry.execute(
            ToolCall(
                name = "contacts.search",
                arguments = mapOf("query" to "张三"),
                risk = ToolRisk.SAFE,
            ),
        )

        val result = registry.execute(
            ToolCall(
                name = "contacts.search",
                arguments = mapOf("query" to " 张三 ", "limit" to "3"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals("张三", capturedQuery)
        assertEquals(3, capturedLimit)
        assertTrue(result.content.contains("张三 这只是姓名"))
        assertTrue(result.content.contains("id=contact-42"))
        assertTrue(result.content.contains("匹配=姓名/邮箱"))
        assertFalse(result.content.contains("13800138000"))
        assertFalse(result.content.contains("zhang@example.com"))
        assertFalse(result.content.contains("\n这只是姓名"))
    }

    @Test
    fun contactsGetReadsCurrentMinimalDetailByStableId() = runTest {
        var capturedContactId = -1L
        val reader = object : ContactReader {
            override suspend fun searchContacts(query: String, limit: Int): ContactSearchResult =
                ContactSearchResult.Success(
                    listOf(ContactSearchRecord(42L, "张三", setOf(ContactMatchField.NAME))),
                )

            override suspend fun getContact(contactId: Long): ContactDetailReadResult {
                capturedContactId = contactId
                return ContactDetailReadResult.Success(
                    ContactDetailRecord(
                        contactId = contactId,
                        displayName = "张三",
                        phoneNumbers = listOf("13800138000"),
                        emailAddresses = listOf("zhang@example.com"),
                    ),
                )
            }
        }
        val registry = testRegistry(contactReader = reader)

        registry.execute(
            ToolCall(
                name = "contacts.search",
                arguments = mapOf("query" to "张三"),
                risk = ToolRisk.SAFE,
            ),
        )

        val result = registry.execute(
            ToolCall(
                name = "contacts.get",
                arguments = mapOf("contact_id" to "contact-42"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(42L, capturedContactId)
        assertTrue(result.content.contains("ID：contact-42"))
        assertTrue(result.content.contains("姓名：张三"))
        assertTrue(result.content.contains("13800138000"))
        assertTrue(result.content.contains("zhang@example.com"))
        assertFalse(result.content.contains("地址"))
        assertFalse(result.content.contains("账户"))
    }

    @Test
    fun contactsGetRejectsInvalidOrStaleIdentityFailClosed() = runTest {
        var readCount = 0
        val reader = object : ContactReader {
            override suspend fun searchContacts(query: String, limit: Int): ContactSearchResult =
                ContactSearchResult.Success(
                    listOf(ContactSearchRecord(42L, "张三", setOf(ContactMatchField.NAME))),
                )

            override suspend fun getContact(contactId: Long): ContactDetailReadResult {
                readCount += 1
                return ContactDetailReadResult.NotFound
            }
        }
        val registry = testRegistry(contactReader = reader)

        listOf("42", "contact-0", "contact-01", "contact--1", "contact-9223372036854775808").forEach { id ->
            val result = registry.execute(
                ToolCall(
                    name = "contacts.get",
                    arguments = mapOf("contact_id" to id),
                    risk = ToolRisk.SAFE,
                ),
            )
            assertFalse(result.success)
            assertTrue(result.content.contains("ID 无效"))
        }
        assertEquals(0, readCount)

        val guessed = registry.execute(
            ToolCall(
                name = "contacts.get",
                arguments = mapOf("contact_id" to "contact-42"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertFalse(guessed.success)
        assertTrue(guessed.content.contains("最近一次搜索结果"))
        assertEquals(0, readCount)

        registry.execute(
            ToolCall(
                name = "contacts.search",
                arguments = mapOf("query" to "张三"),
                risk = ToolRisk.SAFE,
            ),
        )
        val stale = registry.execute(
            ToolCall(
                name = "contacts.get",
                arguments = mapOf("contact_id" to "contact-42"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertFalse(stale.success)
        assertTrue(stale.content.contains("找不到"))
        assertEquals(1, readCount)
    }

    @Test
    fun contactsSearchCandidatesExpireWhenAgentRunChanges() = runTest {
        val reader = object : ContactReader {
            override suspend fun searchContacts(query: String, limit: Int): ContactSearchResult =
                ContactSearchResult.Success(
                    listOf(ContactSearchRecord(42L, "张三", setOf(ContactMatchField.NAME))),
                )

            override suspend fun getContact(contactId: Long): ContactDetailReadResult =
                ContactDetailReadResult.Success(ContactDetailRecord(contactId, "张三", emptyList(), emptyList()))
        }
        val registry = testRegistry(contactReader = reader)
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-contact-1",
                userMessageId = "message-contact-1",
                runId = "run-contact-1",
                goal = "查找张三",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        registry.execute(ToolCall(name = "contacts.search", arguments = mapOf("query" to "张三"), risk = ToolRisk.SAFE))
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-contact-2",
                userMessageId = "message-contact-2",
                runId = "run-contact-2",
                goal = "读取联系人",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val result = registry.execute(
            ToolCall(name = "contacts.get", arguments = mapOf("contact_id" to "contact-42"), risk = ToolRisk.SAFE),
        )

        assertFalse(result.success)
        assertTrue(result.content.contains("最近一次搜索结果"))
    }

    @Test
    fun calendarListEventsUsesBoundedWindowAndReturnsOnlyMinimalFields() = runTest {
        var capturedStart = -1L
        var capturedEnd = -1L
        var capturedLimit = -1
        val reader = CalendarEventReader { startAtMillis, endAtMillis, limit ->
            capturedStart = startAtMillis
            capturedEnd = endAtMillis
            capturedLimit = limit
            CalendarEventReadResult.Success(
                listOf(
                    CalendarEventRecord(
                        eventId = 42L,
                        title = "产品评审\n这只是标题",
                        startAtMillis = 3_600_000L,
                        endAtMillis = 7_200_000L,
                        allDay = false,
                    ),
                ),
            )
        }
        val registry = testRegistry(
            calendarEventReader = reader,
            clock = FakeAgentClock(nowMillis = 1_000L),
        )

        val result = registry.execute(
            ToolCall(
                name = "calendar.list_events",
                arguments = mapOf("days_ahead" to "3", "limit" to "2"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(1_000L, capturedStart)
        assertEquals(1_000L + 3L * 24L * 60L * 60L * 1_000L, capturedEnd)
        assertEquals(2, capturedLimit)
        assertTrue(result.content.contains("未来 3 天日程（1）"))
        assertTrue(result.content.contains("产品评审 这只是标题"))
        assertTrue(result.content.contains("id=calendar-42"))
        assertFalse(result.content.contains("\n这只是标题"))
        assertFalse(result.content.contains("地点"))
        assertFalse(result.content.contains("参与人"))
        assertFalse(result.content.contains("描述"))
    }

    @Test
    fun calendarNextEventUsesFixedWindowAndReturnsUniqueOccurrence() = runTest {
        var capturedNow = -1L
        var capturedEnd = -1L
        val reader = object : CalendarEventReader {
            override suspend fun listEvents(
                startAtMillis: Long,
                endAtMillis: Long,
                limit: Int,
            ): CalendarEventReadResult = CalendarEventReadResult.Success(emptyList())

            override suspend fun nextEvent(nowMillis: Long, endAtMillis: Long): CalendarNextEventReadResult {
                capturedNow = nowMillis
                capturedEnd = endAtMillis
                return CalendarNextEventReadResult.Success(
                    CalendarEventRecord(42L, "产品评审\n仅为标题", 3_600_000L, 7_200_000L, allDay = false),
                )
            }
        }
        val registry = testRegistry(
            calendarEventReader = reader,
            clock = FakeAgentClock(nowMillis = 1_000L),
        )

        val result = registry.execute(ToolCall(name = "calendar.next_event", arguments = emptyMap(), risk = ToolRisk.SAFE))

        assertTrue(result.success)
        assertEquals(1_000L, capturedNow)
        assertEquals(1_000L + 30L * 24L * 60L * 60L * 1_000L, capturedEnd)
        assertTrue(result.content.startsWith("下一条系统日程："))
        assertTrue(result.content.contains("产品评审 仅为标题"))
        assertTrue(result.content.contains("id=calendar-42"))
        assertTrue(result.content.contains("实例身份：occurrence-v1-42-3600000"))
        assertTrue(result.content.endsWith("重复实例：否"))
    }

    @Test
    fun calendarNextEventReportsEmptyAmbiguousAndProviderFailuresWithoutGuessing() = runTest {
        suspend fun execute(next: CalendarNextEventReadResult): ToolExecutionResult {
            val reader = object : CalendarEventReader {
                override suspend fun listEvents(
                    startAtMillis: Long,
                    endAtMillis: Long,
                    limit: Int,
                ): CalendarEventReadResult = CalendarEventReadResult.Success(emptyList())

                override suspend fun nextEvent(nowMillis: Long, endAtMillis: Long): CalendarNextEventReadResult = next
            }
            return testRegistry(calendarEventReader = reader).execute(
                ToolCall(name = "calendar.next_event", arguments = emptyMap(), risk = ToolRisk.SAFE),
            )
        }

        val empty = execute(CalendarNextEventReadResult.NoUpcomingEvent)
        val ambiguous = execute(CalendarNextEventReadResult.AmbiguousStartTime(2))
        val denied = execute(CalendarNextEventReadResult.PermissionDenied)

        assertTrue(empty.success)
        assertTrue(empty.content.contains("没有尚未开始"))
        assertTrue(ambiguous.success)
        assertTrue(ambiguous.content.contains("无法唯一确定"))
        assertFalse(ambiguous.content.contains("calendar-"))
        assertFalse(denied.success)
        assertTrue(denied.content.contains("权限"))
    }

    @Test
    fun calendarSearchEventsFiltersMinimalTitlesWithoutExpandingPrivacyFields() = runTest {
        var capturedLimit = -1
        val reader = CalendarEventReader { _, _, limit ->
            capturedLimit = limit
            CalendarEventReadResult.Success(
                listOf(
                    CalendarEventRecord(42L, "产品评审", 3_600_000L, 7_200_000L, allDay = false),
                    CalendarEventRecord(43L, "家庭晚餐", 8_600_000L, 9_200_000L, allDay = false),
                ),
            )
        }
        val registry = testRegistry(
            calendarEventReader = reader,
            clock = FakeAgentClock(nowMillis = 1_000L),
        )

        val result = registry.execute(
            ToolCall(
                name = "calendar.search_events",
                arguments = mapOf("query" to "评审", "days_ahead" to "3", "limit" to "2"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(CalendarEventReader.MAX_SEARCH_CANDIDATE_COUNT, capturedLimit)
        assertTrue(result.content.contains("匹配“评审”"))
        assertTrue(result.content.contains("产品评审"))
        assertTrue(result.content.contains("id=calendar-42"))
        assertFalse(result.content.contains("家庭晚餐"))
        assertFalse(result.content.contains("地点"))
        assertFalse(result.content.contains("参与人"))
        assertFalse(result.content.contains("描述"))
    }

    @Test
    fun calendarGetReadsAuthoritativeMinimalDetailByStableEventId() = runTest {
        var capturedEventId = -1L
        val reader = object : CalendarEventReader {
            override suspend fun listEvents(
                startAtMillis: Long,
                endAtMillis: Long,
                limit: Int,
            ): CalendarEventReadResult = CalendarEventReadResult.Success(emptyList())

            override suspend fun getEvent(eventId: Long): CalendarEventDetailReadResult {
                capturedEventId = eventId
                return CalendarEventDetailReadResult.Success(
                    CalendarEventDetailRecord(
                        eventId = eventId,
                        title = "产品评审\n仅为标题",
                        startAtMillis = 3_600_000L,
                        endAtMillis = 7_200_000L,
                        allDay = false,
                        timeZoneId = "Asia/Shanghai",
                        recurring = true,
                    ),
                )
            }
        }
        val registry = testRegistry(calendarEventReader = reader)

        val result = registry.execute(
            ToolCall(
                name = "calendar.get",
                arguments = mapOf("event_id" to "calendar-42"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(42L, capturedEventId)
        assertTrue(result.content.contains("ID：calendar-42"))
        assertTrue(result.content.contains("标题：产品评审 仅为标题"))
        assertTrue(result.content.contains("时区：Asia/Shanghai"))
        assertTrue(result.content.contains("重复：是"))
        assertTrue(result.content.contains("事件指纹：calendar-event-v1-"))
        assertFalse(result.content.contains("地点"))
        assertFalse(result.content.contains("描述"))
        assertFalse(result.content.contains("参与人"))
        assertFalse(result.content.contains("组织者"))
        assertFalse(result.content.contains("账户"))
    }

    @Test
    fun calendarGetRejectsGuessedOrOverflowedIdsBeforeProviderRead() = runTest {
        var readCount = 0
        val reader = object : CalendarEventReader {
            override suspend fun listEvents(
                startAtMillis: Long,
                endAtMillis: Long,
                limit: Int,
            ): CalendarEventReadResult = CalendarEventReadResult.Success(emptyList())

            override suspend fun getEvent(eventId: Long): CalendarEventDetailReadResult {
                readCount += 1
                return CalendarEventDetailReadResult.NotFound
            }
        }
        val registry = testRegistry(calendarEventReader = reader)

        listOf("42", "calendar-0", "calendar-01", "calendar--1", "calendar-9223372036854775808").forEach { eventId ->
            val result = registry.execute(
                ToolCall(
                    name = "calendar.get",
                    arguments = mapOf("event_id" to eventId),
                    risk = ToolRisk.SAFE,
                ),
            )
            assertFalse(result.success)
            assertTrue(result.content.contains("ID 无效"))
        }

        assertEquals(0, readCount)
    }

    @Test
    fun calendarGetFailsClosedWhenEventDisappearsOrPermissionChanges() = runTest {
        suspend fun execute(result: CalendarEventDetailReadResult): ToolExecutionResult {
            val reader = object : CalendarEventReader {
                override suspend fun listEvents(
                    startAtMillis: Long,
                    endAtMillis: Long,
                    limit: Int,
                ): CalendarEventReadResult = CalendarEventReadResult.Success(emptyList())

                override suspend fun getEvent(eventId: Long): CalendarEventDetailReadResult = result
            }
            return testRegistry(calendarEventReader = reader).execute(
                ToolCall(
                    name = "calendar.get",
                    arguments = mapOf("event_id" to "calendar-42"),
                    risk = ToolRisk.SAFE,
                ),
            )
        }

        val missing = execute(CalendarEventDetailReadResult.NotFound)
        val denied = execute(CalendarEventDetailReadResult.PermissionDenied)

        assertFalse(missing.success)
        assertTrue(missing.content.contains("找不到"))
        assertFalse(denied.success)
        assertTrue(denied.content.contains("权限"))
    }

    @Test
    fun calendarCreateEventRequiresForegroundApprovalAndStableRecoveryContract() {
        val definition = testRegistry().registeredTools().single { it.name == "calendar.create_event" }

        assertEquals(ToolRisk.REQUIRES_APPROVAL, definition.risk)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, definition.approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, definition.verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, definition.replaySafety)
        assertEquals(ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL, definition.notCommittedReplayPolicy)
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            definition.permissionPolicy.requiredAndroidPermissions,
        )
        assertFalse(definition.permissionPolicy.supportsBackground)
        assertEquals(
            listOf("title", "start_at", "end_at", "time_zone", "reminder_minutes_before"),
            definition.inputSchema.map { it.name },
        )
        assertTrue(testRegistry().supportsCommittedEffectVerification("calendar.create_event"))
    }

    @Test
    fun calendarCreateAllDayEventUsesSingleDateAndStableRecoveryContract() = runTest {
        val writer = InMemoryCalendarEventWriter()
        val registry = testRegistry(calendarEventWriter = writer)
        val definition = registry.registeredTools().single { it.name == "calendar.create_all_day_event" }
        val call = ToolCall(
            id = "tool-call-calendar-all-day-1",
            name = "calendar.create_all_day_event",
            arguments = mapOf("title" to "项目纪念日", "date" to "2026-08-18"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)
        val request = writer.records.single().first
        val recovered = registry.verifyCommittedEffect(call, requireNotNull(result.executionReceipt))

        assertEquals(ToolRisk.REQUIRES_APPROVAL, definition.risk)
        assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, definition.approvalPolicy)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, definition.verificationPolicy)
        assertEquals(ToolReplaySafety.IDEMPOTENT_BY_KEY, definition.replaySafety)
        assertEquals(listOf("title", "date"), definition.inputSchema.map { it.name })
        assertTrue(request.allDay)
        assertEquals("UTC", request.timeZoneId)
        assertEquals(LocalDate.parse("2026-08-18").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), request.startAtMillis)
        assertEquals(request.startAtMillis + 86_400_000L, request.endAtMillis)
        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals(true, recovered?.verified)
    }

    @Test
    fun calendarCreateAllDayEventRejectsInvalidOrNonCanonicalDate() = runTest {
        val registry = testRegistry(calendarEventWriter = InMemoryCalendarEventWriter())

        val invalid = registry.execute(
            ToolCall(
                name = "calendar.create_all_day_event",
                arguments = mapOf("title" to "项目纪念日", "date" to "2026-2-3"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertFalse(invalid.success)
        assertTrue(invalid.content.contains("日期"))
    }

    @Test
    fun calendarDeleteEventIsOnlyAvailableToDirectForegroundAgent() {
        val registry = testRegistry()

        assertTrue(registry.availableToolsFor(null).none { it.name == "calendar.delete_event" })
        registry.bindRunContext(workflowDeviceContext(userIntent = "删除日程"))
        assertTrue(registry.availableTools().none { it.name == "calendar.delete_event" })
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-calendar-delete",
                userMessageId = "message-calendar-delete",
                runId = "run-calendar-delete",
                goal = "删除项目评审日程",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        assertNotNull(registry.definition("calendar.delete_event"))
    }

    @Test
    fun calendarDeleteEventExecutionFailsClosedWhenPlannerVisibilityIsBypassed() = runTest {
        val event = CalendarEventDetailRecord(
            eventId = 42L,
            title = "项目评审",
            startAtMillis = 1_000L,
            endAtMillis = 2_000L,
            allDay = false,
            timeZoneId = "Asia/Shanghai",
            recurring = false,
        )
        val writer = InMemoryCalendarEventWriter(deletableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        val call = ToolCall(
            name = "calendar.delete_event",
            arguments = mapOf(
                "event_id" to "calendar-42",
                "expected_fingerprint" to CalendarEventFingerprint.create(event),
                "scope" to "event",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val withoutContext = registry.execute(call)
        registry.bindRunContext(workflowDeviceContext(userIntent = "删除日程"))
        val workflow = registry.execute(call)

        assertFalse(withoutContext.success)
        assertFalse(workflow.success)
        assertTrue(withoutContext.content.contains("前台直接 Agent"))
        assertEquals(0, writer.deleteCount)
    }

    @Test
    fun calendarDeleteEventCommitsOnceAndRecoveryOnlyVerifiesAbsence() = runTest {
        val event = CalendarEventDetailRecord(
            eventId = 42L,
            title = "项目评审",
            startAtMillis = 1_000L,
            endAtMillis = 2_000L,
            allDay = false,
            timeZoneId = "Asia/Shanghai",
            recurring = false,
        )
        val writer = InMemoryCalendarEventWriter(deletableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        registry.bindRunContext(directCalendarDeleteContext())
        val call = ToolCall(
            id = "tool-call-calendar-delete-1",
            name = "calendar.delete_event",
            arguments = mapOf(
                "event_id" to "calendar-42",
                "expected_fingerprint" to CalendarEventFingerprint.create(event),
                "scope" to "event",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)
        val receipt = requireNotNull(result.executionReceipt)
        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals("calendar-42", receipt.operationId)
        assertEquals(1, writer.deleteCount)
        assertEquals(1, writer.verifyDeleteCount)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
        assertTrue(registry.supportsCommittedEffectVerification("calendar.delete_event"))
    }

    @Test
    fun calendarDeleteEventRejectsFingerprintDriftAndUnsupportedOccurrence() = runTest {
        val event = CalendarEventDetailRecord(
            eventId = 42L,
            title = "项目评审",
            startAtMillis = 1_000L,
            endAtMillis = 2_000L,
            allDay = false,
            timeZoneId = "Asia/Shanghai",
            recurring = false,
        )
        val writer = InMemoryCalendarEventWriter(deletableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        registry.bindRunContext(directCalendarDeleteContext())

        val drifted = registry.execute(
            ToolCall(
                name = "calendar.delete_event",
                arguments = mapOf(
                    "event_id" to "calendar-42",
                    "expected_fingerprint" to CalendarEventFingerprint.create(event.copy(title = "旧标题")),
                    "scope" to "event",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        val occurrence = registry.execute(
            ToolCall(
                name = "calendar.delete_event",
                arguments = mapOf(
                    "event_id" to "calendar-42",
                    "expected_fingerprint" to CalendarEventFingerprint.create(event),
                    "scope" to "occurrence",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertFalse(drifted.success)
        assertTrue(drifted.content.contains("已变化"))
        assertFalse(occurrence.success)
        assertTrue(occurrence.content.contains("单次 occurrence"))
        assertEquals(0, writer.deleteCount)
    }

    @Test
    fun calendarUpdateEventIsOnlyAvailableToDirectForegroundAgentAndCannotBeBypassed() = runTest {
        val event = calendarUpdateFixture()
        val writer = InMemoryCalendarEventWriter(updatableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        val call = calendarUpdateCall(event)

        assertTrue(registry.availableToolsFor(null).none { it.name == "calendar.update_event" })
        val withoutContext = registry.execute(call)
        registry.bindRunContext(workflowDeviceContext(userIntent = "修改日程"))
        assertTrue(registry.availableTools().none { it.name == "calendar.update_event" })
        val workflow = registry.execute(call)
        registry.bindRunContext(directCalendarUpdateContext())

        assertNotNull(registry.definition("calendar.update_event"))
        assertFalse(withoutContext.success)
        assertFalse(workflow.success)
        assertTrue(withoutContext.content.contains("前台直接 Agent"))
        assertEquals(0, writer.updateCount)
    }

    @Test
    fun calendarUpdateEventCommitsOnceAndRecoveryOnlyReadsUpdatedEvent() = runTest {
        val event = calendarUpdateFixture()
        val writer = InMemoryCalendarEventWriter(updatableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        registry.bindRunContext(directCalendarUpdateContext())
        val call = calendarUpdateCall(event)

        val result = registry.execute(call)
        val receipt = requireNotNull(result.executionReceipt)
        val recovered = registry.verifyCommittedEffect(call, receipt)
        val wrongScope = registry.verifyCommittedEffect(
            call.copy(arguments = call.arguments + ("scope" to "series")),
            receipt,
        )

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals("calendar-84", receipt.operationId)
        assertEquals(1, writer.updateCount)
        assertEquals(2, writer.verifyUpdateCount)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
        assertEquals(false, wrongScope?.success)
        assertTrue(registry.supportsCommittedEffectVerification("calendar.update_event"))
    }

    @Test
    fun calendarUpdateEventRecoveryContractDeniesInterruptedUncommittedReplay() {
        val registry = testRegistry()
        registry.bindRunContext(directCalendarUpdateContext())
        val definition = checkNotNull(registry.definition("calendar.update_event"))

        assertEquals(ToolReplaySafety.RESTART_REQUIRED, definition.replaySafety)
        assertEquals(ToolNotCommittedReplayPolicy.DENY, definition.notCommittedReplayPolicy)
        assertEquals(
            ToolNotCommittedReplayPolicy.DENY,
            ToolDefinitionRecoveryContract.snapshot(definition).notCommittedReplayPolicy,
        )
    }

    @Test
    fun calendarUpdateEventCommittedRecoveryRejectsWorkflowAndBackgroundContexts() = runTest {
        val event = calendarUpdateFixture()
        val writer = InMemoryCalendarEventWriter(updatableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        registry.bindRunContext(directCalendarUpdateContext())
        val call = calendarUpdateCall(event)
        val receipt = requireNotNull(registry.execute(call).executionReceipt)

        registry.bindRunContext(workflowDeviceContext(userIntent = "修改日程"))
        val workflowRecovery = registry.verifyCommittedEffect(call, receipt)
        registry.bindRunContext(
            directCalendarUpdateContext().copy(executionOrigin = AgentExecutionOrigin.BACKGROUND),
        )
        val backgroundRecovery = registry.verifyCommittedEffect(call, receipt)

        assertEquals(false, workflowRecovery?.success)
        assertEquals(false, backgroundRecovery?.success)
        assertEquals(1, writer.updateCount)
        assertEquals(0, writer.verifyUpdateCount)
    }

    @Test
    fun calendarUpdateEventRejectsFingerprintDriftSeriesAndOccurrence() = runTest {
        val event = calendarUpdateFixture()
        val writer = InMemoryCalendarEventWriter(updatableEvent = event)
        val registry = testRegistry(calendarEventWriter = writer)
        registry.bindRunContext(directCalendarUpdateContext())

        val drifted = registry.execute(
            calendarUpdateCall(event).copy(
                arguments = calendarUpdateCall(event).arguments +
                    ("expected_fingerprint" to CalendarEventFingerprint.create(event.copy(title = "旧标题"))),
            ),
        )
        val series = registry.execute(
            calendarUpdateCall(event).copy(arguments = calendarUpdateCall(event).arguments + ("scope" to "series")),
        )
        val occurrence = registry.execute(
            calendarUpdateCall(event).copy(arguments = calendarUpdateCall(event).arguments + ("scope" to "occurrence")),
        )

        assertFalse(drifted.success)
        assertTrue(drifted.content.contains("已变化"))
        assertFalse(series.success)
        assertTrue(series.content.contains("重复系列"))
        assertFalse(occurrence.success)
        assertTrue(occurrence.content.contains("单次 occurrence"))
        assertEquals(0, writer.updateCount)
    }

    @Test
    fun calendarCreateEventWritesOnceAndReturnsVerifiedCommittedReceipt() = runTest {
        val writer = InMemoryCalendarEventWriter()
        val registry = testRegistry(calendarEventWriter = writer)
        val call = ToolCall(
            id = "tool-call-calendar-create-1",
            name = "calendar.create_event",
            arguments = mapOf(
                "title" to "项目评审",
                "start_at" to "2026-08-08T09:00:00+08:00",
                "end_at" to "2026-08-08T10:00:00+08:00",
                "time_zone" to "Asia/Shanghai",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)
        val replay = registry.execute(call)
        val receipt = requireNotNull(result.executionReceipt)
        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals("已创建并验证日程：项目评审 · id=calendar-197", result.content)
        assertEquals(1, writer.records.size)
        assertEquals(result.executionReceipt, replay.executionReceipt)
        assertEquals(call.id, receipt.idempotencyKey)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, receipt.status)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
    }

    @Test
    fun calendarCreateEventWritesAndRecoversSingleExplicitReminder() = runTest {
        val writer = InMemoryCalendarEventWriter()
        val registry = testRegistry(calendarEventWriter = writer)
        val call = ToolCall(
            id = "tool-call-calendar-reminder-1",
            name = "calendar.create_event",
            arguments = mapOf(
                "title" to "复诊",
                "start_at" to "2026-08-08T09:00:00+08:00",
                "end_at" to "2026-08-08T10:00:00+08:00",
                "time_zone" to "Asia/Shanghai",
                "reminder_minutes_before" to "30",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)
        val recovered = registry.verifyCommittedEffect(call, requireNotNull(result.executionReceipt))

        assertTrue(result.success)
        assertEquals("已创建并验证日程：复诊 · 提醒=提前30分钟 · id=calendar-197", result.content)
        assertEquals(30, writer.records.single().first.reminderMinutesBefore)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
    }

    @Test
    fun calendarCreateEventRejectsNonCanonicalOrOutOfRangeReminder() {
        val definition = testRegistry().definition("calendar.create_event")!!
        val base = mapOf(
            "title" to "复诊",
            "start_at" to "2026-08-08T09:00:00+08:00",
            "end_at" to "2026-08-08T10:00:00+08:00",
            "time_zone" to "Asia/Shanghai",
        )

        listOf("-1", "030", "10081", "30.0").forEach { invalid ->
            val validation = definition.validateArguments(base + ("reminder_minutes_before" to invalid))
            assertFalse("提醒值 $invalid 必须被拒绝", validation.isValid)
        }
        assertTrue(definition.validateArguments(base + ("reminder_minutes_before" to "0")).isValid)
        assertTrue(definition.validateArguments(base + ("reminder_minutes_before" to "10080")).isValid)
    }

    @Test
    fun calendarCreateEventRejectsAmbiguousOrMismatchedTimeZoneBeforeExecution() {
        val definition = testRegistry().definition("calendar.create_event")!!

        val missingOffset = definition.validateArguments(
            mapOf(
                "title" to "项目评审",
                "start_at" to "2026-08-08T09:00:00",
                "end_at" to "2026-08-08T10:00:00",
                "time_zone" to "Asia/Shanghai",
            ),
        )
        val mismatchedZone = definition.validateArguments(
            mapOf(
                "title" to "项目评审",
                "start_at" to "2026-08-08T09:00:00Z",
                "end_at" to "2026-08-08T10:00:00Z",
                "time_zone" to "Asia/Shanghai",
            ),
        )
        val offsetOnlyZone = definition.validateArguments(
            mapOf(
                "title" to "项目评审",
                "start_at" to "2026-08-08T09:00:00+08:00",
                "end_at" to "2026-08-08T10:00:00+08:00",
                "time_zone" to "+08:00",
            ),
        )

        assertFalse(missingOffset.isValid)
        assertTrue(mismatchedZone.errors.any { it.contains("偏移与指定时区不一致") })
        assertTrue(offsetOnlyZone.errors.any { it.contains("IANA 时区") })
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
            setOf("device.snapshot"),
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
        assertEquals(
            setOf("device.snapshot", "device.tap_ref"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
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
            setOf("device.snapshot"),
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
        assertEquals(
            setOf("device.snapshot", "device.tap_ref", "device.type_text"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
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
            setOf("device.snapshot"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
        val snapshotCall = ToolCall(
            id = "tool-call-swipe-snapshot",
            name = "device.snapshot",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        registry.afterToolVerification(snapshotCall, registry.execute(snapshotCall))
        assertEquals(
            setOf("device.snapshot", "device.swipe"),
            registry.availableTools().filter { it.name.startsWith("device.") }.mapTo(linkedSetOf(), ToolDefinition::name),
        )
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
    fun foregroundHealthToolProjectsOnlyTheFourFiniteStates() = runTest {
        val expectedLabels = mapOf(
            DeviceAgentHealthState.AGENT_DISABLED to "未启用",
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED to "未授权",
            DeviceAgentHealthState.SERVICE_DISCONNECTED to "服务断连",
            DeviceAgentHealthState.READY to "READY",
        )

        expectedLabels.forEach { (healthState, label) ->
            val controller = FakeDeviceController(enabled = true, healthState = healthState)
            val registry = testRegistry(deviceController = controller)
            registry.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-health-${healthState.name}",
                    userMessageId = "message-health-${healthState.name}",
                    runId = "run-health-${healthState.name}",
                    goal = "检查设备 Agent 健康状态",
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.DIRECT,
                ),
            )

            val result = registry.execute(
                ToolCall(
                    name = "app.get_device_agent_health",
                    arguments = emptyMap(),
                    risk = ToolRisk.SAFE,
                ),
            )

            assertTrue(result.success)
            assertEquals("设备 Agent 健康状态：$label", result.content)
            // long: 健康探针只读取连接状态，不能因为查询健康而触发 snapshot 或设备动作。
            assertEquals(0, controller.captureCount)
            assertTrue(controller.actions.isEmpty())
        }
    }

    @Test
    fun healthToolRejectsArguments() = runTest {
        val controller = FakeDeviceController(enabled = true, healthState = DeviceAgentHealthState.READY)
        val registry = testRegistry(deviceController = controller)
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-health-args",
                userMessageId = "message-health-args",
                runId = "run-health-args",
                goal = "检查设备 Agent 健康状态",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val result = registry.execute(
            ToolCall(
                name = "app.get_device_agent_health",
                arguments = mapOf("window" to "current"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertFalse(result.success)
        assertTrue(result.content.contains("不接受参数"))
    }

    @Test
    fun healthToolIsVisibleOnlyToForegroundDirectAgent() {
        val registry = testRegistry(deviceController = FakeDeviceController(enabled = true))
        val contexts = listOf(
            AgentToolExecutionContext(
                conversationId = "conversation-health-direct",
                userMessageId = "message-health-direct",
                runId = "run-health-direct",
                goal = "检查设备 Agent 健康状态",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ) to true,
            workflowDeviceContext(userIntent = "检查设备 Agent 健康状态") to false,
            AgentToolExecutionContext(
                conversationId = "conversation-health-background",
                userMessageId = "message-health-background",
                runId = "run-health-background",
                goal = "检查设备 Agent 健康状态",
                executionOrigin = AgentExecutionOrigin.BACKGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ) to false,
            null to false,
        )

        contexts.forEach { (context, visible) ->
            registry.bindRunContext(context ?: return@forEach)
            assertEquals(visible, registry.availableTools().any { it.name == "app.get_device_agent_health" })
            assertEquals(visible, registry.definition("app.get_device_agent_health") != null)
        }
        assertFalse(registry.availableToolsFor(null).any { it.name == "app.get_device_agent_health" })
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
    fun appInfoToolReturnsOnlyAllowlistedInstalledMetadata() = runTest {
        val registry = testRegistry(
            appInfoReader = AppInfoReader {
                AppInfoReadResult.Success(
                    AppInfoRecord(
                        appName = "小灵",
                        packageName = "com.longdev.xiaoling",
                        versionName = "0.1.16",
                        versionCode = 17L,
                    ),
                )
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "app.get_info",
                arguments = emptyMap(),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(
            "应用名称：小灵\n包名：com.longdev.xiaoling\n版本名：0.1.16\n版本号：17",
            result.content,
        )
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("wsvwypiz7xwslvl7"))
    }

    @Test
    fun appInfoToolFailsClosedWhenReaderIsUnavailableOrArgumentsArePresent() = runTest {
        val registry = testRegistry(appInfoReader = UnavailableAppInfoReader)
        val unavailable = registry.execute(
            ToolCall(name = "app.get_info", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        val withArguments = registry.execute(
            ToolCall(
                name = "app.get_info",
                arguments = mapOf("package_name" to "com.example.other"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertFalse(unavailable.success)
        assertEquals("当前应用信息不可用", unavailable.content)
        assertFalse(withArguments.success)
        assertEquals("app.get_info 不接受参数", withArguments.content)
    }

    @Test
    fun batteryStatusToolReturnsOnlyCurrentPowerFactsAndRejectsArguments() = runTest {
        val registry = testRegistry(
            batteryStatusReader = BatteryStatusReader {
                BatteryStatusReadResult.Success(
                    BatteryStatusRecord(
                        levelPercent = 87,
                        charging = true,
                        powerSource = BatteryPowerSource.USB,
                    ),
                )
            },
        )

        val result = registry.execute(
            ToolCall(name = "app.get_battery", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        val withArguments = registry.execute(
            ToolCall(
                name = "app.get_battery",
                arguments = mapOf("device_id" to "secret-device"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals("电量：87%\n充电状态：正在充电\n供电方式：USB", result.content)
        assertFalse(result.content.contains("secret-device"))
        assertFalse(withArguments.success)
        assertEquals("app.get_battery 不接受参数", withArguments.content)
    }

    @Test
    fun connectivityStatusToolReturnsOnlyCurrentNetworkFactsAndRejectsArguments() = runTest {
        val registry = testRegistry(
            connectivityStatusReader = ConnectivityStatusReader {
                ConnectivityStatusReadResult.Success(
                    ConnectivityStatusRecord(
                        connected = true,
                        transport = ConnectivityTransport.WIFI,
                        internetValidated = true,
                    ),
                )
            },
        )

        val result = registry.execute(
            ToolCall(name = "app.get_connectivity", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        val withArguments = registry.execute(
            ToolCall(
                name = "app.get_connectivity",
                arguments = mapOf("ssid" to "secret-network"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals("网络状态：已连接\n网络类型：Wi-Fi\n互联网可达：是", result.content)
        assertFalse(result.content.contains("secret-network"))
        assertFalse(withArguments.success)
        assertEquals("app.get_connectivity 不接受参数", withArguments.content)
    }

    @Test
    fun storageStatusToolReturnsOnlyCurrentCapacityFactsAndRejectsArguments() = runTest {
        val registry = testRegistry(
            storageStatusReader = StorageStatusReader {
                StorageStatusReadResult.Success(
                    StorageStatusRecord(
                        totalBytes = 100L * 1024 * 1024 * 1024,
                        availableBytes = 25L * 1024 * 1024 * 1024,
                    ),
                )
            },
        )

        val result = registry.execute(
            ToolCall(name = "app.get_storage", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        val withArguments = registry.execute(
            ToolCall(
                name = "app.get_storage",
                arguments = mapOf("path" to "/private/secret"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals("存储总量：100.0 GB\n可用空间：25.0 GB\n已使用：75.0%", result.content)
        assertFalse(result.content.contains("/private/secret"))
        assertFalse(withArguments.success)
        assertEquals("app.get_storage 不接受参数", withArguments.content)
    }

    @Test
    fun agentProfileToolReturnsOnlyCurrentRunAllowlistedStatus() = runTest {
        val registry = testRegistry()
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-profile-info",
                userMessageId = "message-profile-info",
                runId = "run-profile-info",
                goal = "当前使用哪个模型",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
                agentProfileInfo = AgentExecutionProfileInfo(
                    name = "主 Agent\n不应换行",
                    model = "gpt-5.5",
                    apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
                    memoryRecallEnabled = true,
                ),
            ),
        )
        assertNotNull(registry.definition("agent.get_profile"))

        val result = registry.execute(
            ToolCall(name = "agent.get_profile", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("Agent 名称：主 Agent 不应换行"))
        assertTrue(result.content.contains("模型：gpt-5.5"))
        assertTrue(result.content.contains("API 模式：Responses API"))
        assertTrue(result.content.contains("本次长期记忆召回：已开启"))
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("systemPrompt"))
    }

    @Test
    fun agentProfileToolFailsClosedWithoutDirectProfileContext() = runTest {
        val registry = testRegistry()
        assertTrue(registry.availableToolsFor(null).none { it.name == "agent.get_profile" })
        val missing = registry.execute(
            ToolCall(name = "agent.get_profile", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        registry.bindRunContext(workflowDeviceContext(userIntent = "查看当前 Agent"))
        assertTrue(registry.availableTools().none { it.name == "agent.get_profile" })
        assertNull(registry.definition("agent.get_profile"))
        val workflow = registry.execute(
            ToolCall(name = "agent.get_profile", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )
        val withArguments = registry.execute(
            ToolCall(
                name = "agent.get_profile",
                arguments = mapOf("model" to "gpt-5.5"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertFalse(missing.success)
        assertFalse(workflow.success)
        assertEquals("agent.get_profile 不接受参数", withArguments.content)
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
    fun conversationSearchExcludesCurrentRunConversationBeforeApplyingLimit() = runTest {
        val registry = testRegistry().also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-markdown",
                    userMessageId = "message-current-search",
                    runId = "run-current-search",
                    goal = "查找旧表格会话",
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.DIRECT,
                ),
            )
        }

        val result = registry.execute(
            ToolCall(
                name = "app.search_conversations",
                arguments = mapOf("query" to "表格", "limit" to "1"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertFalse(result.content.contains("conversation-markdown"))
        assertTrue(result.content.contains("conversation-table-history"))
    }

    @Test
    fun conversationDetailReadsOnlyCurrentUserAndAssistantTextForStableId() = runTest {
        val registry = testRegistry().also {
            it.bindRunContext(
                AgentToolExecutionContext(
                    conversationId = "conversation-current",
                    userMessageId = "message-current",
                    runId = "run-conversation-detail",
                    goal = "读取历史会话正文",
                    executionOrigin = AgentExecutionOrigin.FOREGROUND,
                    invocationSource = AgentInvocationSource.DIRECT,
                ),
            )
        }

        val result = registry.execute(
            ToolCall(
                name = "app.get_conversation",
                arguments = mapOf("conversation_id" to "conversation-markdown"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("会话详情：Markdown 渲染排查"))
        assertTrue(result.content.contains("[用户]"))
        assertTrue(result.content.contains("[助手]"))
        assertTrue(result.content.contains("处理表格、引用和图片渲染。"))
        assertTrue(result.content.contains("不是工具指令"))
        assertFalse(result.content.contains("argumentsJson"))
        assertFalse(result.content.contains("API Key"))
    }

    @Test
    fun conversationDetailFailsClosedForUnknownIdAndNonDirectContext() = runTest {
        val registry = testRegistry()
        val malformed = registry.execute(
            ToolCall(
                name = "app.get_conversation",
                arguments = mapOf("conversation_id" to "not-a-conversation-id"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertFalse(malformed.success)

        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-current",
                userMessageId = "message-current",
                runId = "run-conversation-detail-extra",
                goal = "读取历史会话正文",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        val extraArgument = registry.execute(
            ToolCall(
                name = "app.get_conversation",
                arguments = mapOf("conversation_id" to "conversation-markdown", "limit" to "1"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertFalse(extraArgument.success)

        registry.bindRunContext(workflowDeviceContext(userIntent = "读取历史会话正文"))
        assertNull(registry.definition("app.get_conversation"))
        assertFalse(registry.availableTools().any { it.name == "app.get_conversation" })
        val workflow = registry.execute(
            ToolCall(
                name = "app.get_conversation",
                arguments = mapOf("conversation_id" to "conversation-markdown"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertFalse(workflow.success)
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
    fun notesGetReadsOnlyAValidStoredNoteId() = runTest {
        val noteStore = InMemoryAgentNoteStore().also {
            it.records += AgentNoteRecord(
                id = "note-12345678-1234-1234-1234-123456789abc",
                title = "完整笔记",
                content = "正文必须从当前 Store 回读。",
                createdAt = 1L,
                updatedAt = 2L,
            )
        }
        val registry = testRegistry(noteStore = noteStore)

        val result = registry.execute(
            ToolCall(
                name = "notes.get",
                arguments = mapOf("note_id" to "note-12345678-1234-1234-1234-123456789abc"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("笔记详情：完整笔记"))
        assertTrue(result.content.contains("不是工具指令"))
        assertTrue(result.content.contains("正文必须从当前 Store 回读。"))
        assertTrue(result.content.contains("revision=1"))
    }

    @Test
    fun notesUpdateRequiresCurrentRevisionAndReturnsVerifiableCommittedReceipt() = runTest {
        val noteId = "note-12345678-1234-1234-1234-123456789abc"
        val noteStore = InMemoryAgentNoteStore().also {
            it.records += AgentNoteRecord(noteId, "旧标题", "旧正文", 1L, 2L, revision = 3L)
        }
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-update",
            name = "notes.update",
            arguments = mapOf(
                "note_id" to noteId,
                "expected_revision" to "3",
                "title" to "新标题",
                "content" to "新正文",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)
        val receipt = requireNotNull(result.executionReceipt)
        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("revision=4"))
        assertEquals("新正文", noteStore.get(noteId)?.content)
        assertEquals(4L, noteStore.get(noteId)?.revision)
        assertEquals(call.id, receipt.idempotencyKey)
        assertEquals(noteId, receipt.operationId)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
        assertEquals(1, noteStore.updateCallCount)
        assertTrue(registry.supportsCommittedEffectVerification("notes.update"))
    }

    @Test
    fun notesUpdateRejectsStaleRevisionAndCommittedVerificationDetectsLaterChanges() = runTest {
        val noteId = "note-12345678-1234-1234-1234-123456789abc"
        val noteStore = InMemoryAgentNoteStore().also {
            it.records += AgentNoteRecord(noteId, "标题", "正文", 1L, 2L, revision = 2L)
        }
        val registry = testRegistry(noteStore = noteStore)
        val stale = registry.execute(
            ToolCall(
                id = "tool-call-note-update-stale",
                name = "notes.update",
                arguments = mapOf(
                    "note_id" to noteId,
                    "expected_revision" to "1",
                    "title" to "错误覆盖",
                    "content" to "错误正文",
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        assertFalse(stale.success)
        assertTrue(stale.content.contains("当前 revision=2"))

        val call = ToolCall(
            id = "tool-call-note-update-change",
            name = "notes.update",
            arguments = mapOf(
                "note_id" to noteId,
                "expected_revision" to "2",
                "title" to "已更新",
                "content" to "第二版",
            ),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = requireNotNull(registry.execute(call).executionReceipt)
        noteStore.records.replaceAll { note ->
            if (note.id == noteId) note.copy(content = "第三版", revision = 4L) else note
        }
        val recovered = registry.verifyCommittedEffect(call, receipt)

        assertEquals(false, recovered?.success)
        assertEquals(false, recovered?.verified)
        assertTrue(recovered?.content?.contains("再次变化") == true)
        assertEquals(2, noteStore.updateCallCount)
    }

    @Test
    fun notesGetRejectsMalformedAndMissingIdsWithoutProbingStore() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)

        val malformed = registry.execute(
            ToolCall(
                name = "notes.get",
                arguments = mapOf("note_id" to "note-1"),
                risk = ToolRisk.SAFE,
            ),
        )
        val missing = registry.execute(
            ToolCall(
                name = "notes.get",
                arguments = mapOf("note_id" to "note-12345678-1234-1234-1234-123456789abc"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertFalse(malformed.success)
        assertEquals("笔记 ID 格式无效", malformed.content)
        assertFalse(missing.success)
        assertEquals("未找到笔记或笔记已被删除", missing.content)
    }

    @Test
    fun notesDeleteRemovesOnlyTheStableTargetAndReturnsCommittedReceipt() = runTest {
        val noteId = "note-12345678-1234-1234-1234-123456789abc"
        val noteStore = InMemoryAgentNoteStore().also {
            it.records += AgentNoteRecord(
                id = noteId,
                title = "待删除笔记",
                content = "删除后必须从当前 Store 消失。",
                createdAt = 1L,
                updatedAt = 2L,
            )
        }
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-delete",
            name = "notes.delete",
            arguments = mapOf("note_id" to noteId),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )

        val result = registry.execute(call)

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("已删除并验证笔记：待删除笔记"))
        assertNull(noteStore.get(noteId))
        assertEquals(listOf(noteId), noteStore.deletedIds)
        assertEquals(
            ToolExecutionReceipt(
                toolCallId = call.id,
                operationId = noteId,
                idempotencyKey = noteId,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
            result.executionReceipt,
        )
    }

    @Test
    fun notesDeleteRejectsMalformedAndMissingIdsWithoutCallingDelete() = runTest {
        val noteStore = InMemoryAgentNoteStore()
        val registry = testRegistry(noteStore = noteStore)

        val malformed = registry.execute(
            ToolCall(
                name = "notes.delete",
                arguments = mapOf("note_id" to "note-1"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        val missing = registry.execute(
            ToolCall(
                name = "notes.delete",
                arguments = mapOf("note_id" to "note-12345678-1234-1234-1234-123456789abc"),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertFalse(malformed.success)
        assertEquals("笔记 ID 格式无效", malformed.content)
        assertFalse(missing.success)
        assertEquals("未找到笔记或笔记已被删除", missing.content)
        assertEquals(0, noteStore.deleteCallCount)
    }

    @Test
    fun notesDeleteCommittedEffectVerificationOnlyReadsTheBoundTarget() = runTest {
        val noteId = "note-12345678-1234-1234-1234-123456789abc"
        val noteStore = InMemoryAgentNoteStore().also {
            it.records += AgentNoteRecord(noteId, "恢复删除", "正文", 1L, 2L)
        }
        val registry = testRegistry(noteStore = noteStore)
        val call = ToolCall(
            id = "tool-call-note-delete-recovery",
            name = "notes.delete",
            arguments = mapOf("note_id" to noteId),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = requireNotNull(registry.execute(call).executionReceipt)

        val recovered = registry.verifyCommittedEffect(call, receipt)
        val drifted = registry.verifyCommittedEffect(
            call,
            receipt.copy(operationId = "note-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        )

        assertEquals(1, noteStore.deleteCallCount)
        assertEquals(true, recovered?.success)
        assertEquals(true, recovered?.verified)
        assertEquals(receipt, recovered?.executionReceipt)
        assertEquals(false, drifted?.success)
        assertEquals(false, drifted?.verified)
        assertTrue(registry.supportsCommittedEffectVerification("notes.delete"))
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
        assertEquals(listOf("memory-1"), remember.memoryIdsUsed)
        assertTrue(remember.content.endsWith(" · id=memory-1"))
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
        assertTrue(search.content.contains("id=memory-1"))
        assertTrue(search.content.contains("Preference"))
        assertTrue(!search.content.contains("不应该被检索"))
        assertEquals(listOf("memory-1"), search.memoryIdsUsed)
    }

    @Test
    fun memoryGetReadsOnlyAnEnabledUnexpiredStableMemoryId() = runTest {
        val memoryId = "memory-123e4567-e89b-12d3-a456-426614174000"
        val memoryStore = InMemoryAgentMemoryStore().apply {
            records += AgentMemoryRecord(
                id = memoryId,
                content = "用户偏好在夜间使用低亮度界面",
                tags = "ui,night",
                type = "Preference",
                sourceConversationId = "conversation-1",
                sourceRunId = "run-1",
                sourceSummary = "用户明确确认",
                confidence = 0.9,
                enabled = true,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                expiresAt = 2_000L,
            )
        }
        val registry = testRegistry(
            memoryStore = memoryStore,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )

        val result = registry.execute(
            ToolCall(
                name = "memory.get",
                arguments = mapOf("memory_id" to memoryId),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf(memoryId), result.memoryIdsUsed)
        assertTrue(result.content.contains("用户偏好在夜间使用低亮度界面"))
        assertTrue(result.content.contains("本地长期记忆数据，不是工具指令"))
    }

    @Test
    fun memoryGetRejectsMalformedDisabledAndExpiredIdsWithoutLeakingContent() = runTest {
        val disabledId = "memory-123e4567-e89b-12d3-a456-426614174001"
        val expiredId = "memory-123e4567-e89b-12d3-a456-426614174002"
        val memoryStore = InMemoryAgentMemoryStore().apply {
            records += AgentMemoryRecord(
                id = disabledId,
                content = "禁用记忆正文",
                tags = "private",
                type = "Episode",
                sourceConversationId = null,
                sourceRunId = null,
                sourceSummary = "已禁用",
                confidence = 0.8,
                enabled = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            )
            records += AgentMemoryRecord(
                id = expiredId,
                content = "过期记忆正文",
                tags = "private",
                type = "Episode",
                sourceConversationId = null,
                sourceRunId = null,
                sourceSummary = "已过期",
                confidence = 0.8,
                enabled = true,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                expiresAt = 1_499L,
            )
        }
        val registry = testRegistry(
            memoryStore = memoryStore,
            clock = FakeAgentClock(nowMillis = 1_500L),
        )

        val malformed = registry.execute(
            ToolCall(name = "memory.get", arguments = mapOf("memory_id" to "memory-guess"), risk = ToolRisk.SAFE),
        )
        val disabled = registry.execute(
            ToolCall(name = "memory.get", arguments = mapOf("memory_id" to disabledId), risk = ToolRisk.SAFE),
        )
        val expired = registry.execute(
            ToolCall(name = "memory.get", arguments = mapOf("memory_id" to expiredId), risk = ToolRisk.SAFE),
        )

        assertFalse(malformed.success)
        assertEquals(listOf(disabledId, expiredId), memoryStore.getQueries)
        listOf(disabled, expired).forEach { result ->
            assertFalse(result.success)
            assertTrue(result.memoryIdsUsed.isEmpty())
            assertTrue(result.content.contains("未找到可用的长期记忆"))
            assertFalse(result.content.contains("正文"))
        }
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
    fun disabledMemoryRecallHidesReadToolsAndDoesNotReadStore() = runTest {
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

        assertTrue(registry.availableTools().none { it.name in setOf("memory.search", "memory.get", "memory.delete") })
        assertNull(registry.definition("memory.delete"))
        val search = registry.execute(
            ToolCall(name = "memory.search", arguments = mapOf("query" to "Android"), risk = ToolRisk.SAFE),
        )
        val get = registry.execute(
            ToolCall(
                name = "memory.get",
                arguments = mapOf("memory_id" to "memory-123e4567-e89b-12d3-a456-426614174000"),
                risk = ToolRisk.SAFE,
            ),
        )
        assertTrue(search.success)
        assertTrue(search.memoryIdsUsed.isEmpty())
        assertTrue(search.content.contains("关闭长期记忆召回"))
        assertTrue(get.success)
        assertTrue(get.memoryIdsUsed.isEmpty())
        assertTrue(get.content.contains("关闭长期记忆召回"))
        assertTrue(memoryStore.searchQueries.isEmpty())
        assertTrue(memoryStore.getQueries.isEmpty())
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

    @Test
    fun taskListReturnsCurrentWorkflowSummariesWithoutInternalIds() = runTest {
        val registry = testRegistry(
            taskStore = object : AgentTaskStore {
                override suspend fun list(limit: Int): List<AgentTaskRecord> {
                    assertEquals(2, limit)
                    return listOf(
                        AgentTaskRecord(
                            name = "每日回顾",
                            goal = "总结今天完成的工作",
                            enabled = true,
                            stepCount = 2,
                            updatedAt = 1L,
                            latestRunStatus = "COMPLETED",
                            scheduleType = "DAILY",
                            nextPlannedAt = 1_784_252_245_000L,
                        ),
                        AgentTaskRecord(
                            name = "每周总结",
                            goal = "每周形成总结",
                            enabled = true,
                            stepCount = 1,
                            updatedAt = 2L,
                            latestRunStatus = null,
                            scheduleType = "WEEKLY",
                            nextPlannedAt = null,
                            recurringScheduleEnabled = false,
                        ),
                    )
                }

                override suspend fun inspect(name: String): AgentTaskInspectionResult {
                    return AgentTaskInspectionResult.NotFound
                }
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "tasks.list",
                arguments = mapOf("limit" to "2"),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("每日回顾 · 已启用 · 2 步 · 最近：已完成 · 每日提醒"))
        assertTrue(result.content.contains("下次：2026-07-17 09:37"))
        assertTrue(result.content.contains("目标：总结今天完成的工作"))
        assertTrue(result.content.contains("每周总结 · 已启用 · 1 步 · 每周提醒 · 周期计划：已暂停"))
        assertFalse(result.content.contains("workflow-private-id"))
    }

    @Test
    fun taskInspectReturnsOnlyBoundedRunDiagnosisWithoutInternalEvidence() = runTest {
        val registry = testRegistry(
            taskStore = object : AgentTaskStore {
                override suspend fun list(limit: Int): List<AgentTaskRecord> = emptyList()

                override suspend fun inspect(name: String): AgentTaskInspectionResult {
                    assertEquals("每日回顾", name)
                    return AgentTaskInspectionResult.Found(
                        AgentTaskInspectionRecord(
                            name = "每日回顾",
                            goal = "总结今天完成的工作",
                            enabled = true,
                            latestRunStatus = "FAILED",
                            latestRunTrigger = "SCHEDULED",
                            latestRunStartedAt = 1_784_252_245_000L,
                            latestRunCompletedAt = 1_784_252_305_000L,
                            diagnosis = AgentTaskRunDiagnosis.STEP_FAILED,
                            steps = listOf(
                                AgentTaskRunStepRecord(sequence = 1, status = "COMPLETED"),
                                AgentTaskRunStepRecord(sequence = 2, status = "FAILED"),
                            ),
                            recurringScheduleType = "DAILY",
                            recurringScheduleEnabled = false,
                            recurringNextPlannedAt = null,
                        ),
                    )
                }
            },
        )

        val result = registry.execute(
            ToolCall(
                name = "tasks.inspect",
                arguments = mapOf("name" to " 每日回顾 "),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        assertTrue(result.content.contains("任务：每日回顾 · 已启用"))
        assertTrue(result.content.contains("每日提醒：已暂停"))
        assertTrue(result.content.contains("最近运行：失败 · 计划运行"))
        assertTrue(result.content.contains("诊断：存在失败步骤"))
        assertTrue(result.content.contains("1. 已完成"))
        assertTrue(result.content.contains("2. 失败"))
        assertFalse(result.content.contains("workflow-private-id"))
        assertFalse(result.content.contains("raw-error"))
        assertFalse(result.content.contains("tool-arguments"))
    }

    @Test
    fun taskRetryReturnsCommittedVerifiedReceiptWithoutInternalEvidence() = runTest {
        val registry = testRegistry(
            taskStore = object : AgentTaskStore {
                override suspend fun list(limit: Int): List<AgentTaskRecord> = emptyList()

                override suspend fun inspect(name: String): AgentTaskInspectionResult =
                    AgentTaskInspectionResult.NotFound

                override suspend fun retry(
                    name: String,
                    conversationId: String,
                    idempotencyKey: String,
                ): AgentTaskRetryResult {
                    assertEquals("每日回顾", name)
                    assertEquals("conversation-direct", conversationId)
                    assertEquals("tool-call-task-retry", idempotencyKey)
                    return AgentTaskRetryResult.Queued(
                        AgentTaskRetryRecord(
                            name = "每日回顾",
                            workflowRunId = "workflow-run-private-id",
                            reusedStepCount = 1,
                            alreadyQueued = false,
                        ),
                    )
                }
            },
        )
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "重试每日回顾任务",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val result = registry.execute(
            ToolCall(
                id = "tool-call-task-retry",
                name = "tasks.retry",
                arguments = mapOf("name" to " 每日回顾 "),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("每日回顾"))
        assertTrue(result.content.contains("复用 1 个已完成步骤"))
        assertFalse(result.content.contains("workflow-run-private-id"))
        assertFalse(result.content.contains("raw-error"))
        assertEquals("workflow-run-private-id", result.executionReceipt?.operationId)
        assertEquals("tool-call-task-retry", result.executionReceipt?.idempotencyKey)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
    }

    @Test
    fun taskCancelReturnsStableVerifiedResultWithoutInternalIds() = runTest {
        val registry = testRegistry(
            taskStore = object : AgentTaskStore {
                override suspend fun list(limit: Int): List<AgentTaskRecord> = emptyList()

                override suspend fun inspect(name: String): AgentTaskInspectionResult = AgentTaskInspectionResult.NotFound

                override suspend fun cancel(
                    name: String,
                    conversationId: String,
                    idempotencyKey: String,
                ): AgentTaskCancelResult {
                    assertEquals("每日回顾", name)
                    assertEquals("conversation-direct", conversationId)
                    assertEquals("tool-call-task-cancel", idempotencyKey)
                    return AgentTaskCancelResult.Cancelled(
                        AgentTaskCancelRecord(
                            name = name,
                            status = "CANCELLED",
                            outcome = AgentTaskCancelOutcome.SCHEDULE_CANCELLED,
                            systemCancellationFailed = false,
                        ),
                    )
                }
            },
        )
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "取消每日回顾提醒",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val result = registry.execute(
            ToolCall(
                id = "tool-call-task-cancel",
                name = "tasks.cancel",
                arguments = mapOf("name" to " 每日回顾 "),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertTrue(result.content.contains("计划已取消"))
        assertTrue(result.content.contains("已取消"))
        assertFalse(result.content.contains("workflow-run-private-id"))
        assertFalse(result.content.contains("scheduled-task-private-id"))
    }

    @Test
    fun taskScheduleControlReturnsStableVerifiedResultsWithoutInternalIds() = runTest {
        val calls = mutableListOf<String>()
        val registry = testRegistry(
            taskStore = object : AgentTaskStore {
                override suspend fun list(limit: Int): List<AgentTaskRecord> = emptyList()

                override suspend fun inspect(name: String): AgentTaskInspectionResult = AgentTaskInspectionResult.NotFound

                override suspend fun pause(
                    name: String,
                    conversationId: String,
                    idempotencyKey: String,
                ): AgentTaskScheduleMutationResult {
                    calls += "pause:$name:$conversationId:$idempotencyKey"
                    return AgentTaskScheduleMutationResult.Changed(
                        AgentTaskScheduleMutationRecord(
                            name = name,
                            state = AgentTaskScheduleState.PAUSED,
                            scheduleType = "DAILY",
                            nextPlannedAt = null,
                            runningTaskUnaffected = true,
                            systemOperationFailed = false,
                        ),
                    )
                }

                override suspend fun resume(
                    name: String,
                    conversationId: String,
                    idempotencyKey: String,
                ): AgentTaskScheduleMutationResult {
                    calls += "resume:$name:$conversationId:$idempotencyKey"
                    return AgentTaskScheduleMutationResult.Changed(
                        AgentTaskScheduleMutationRecord(
                            name = name,
                            state = AgentTaskScheduleState.ACTIVE,
                            scheduleType = "DAILY",
                            nextPlannedAt = 1_784_252_245_000L,
                            runningTaskUnaffected = false,
                            systemOperationFailed = false,
                        ),
                    )
                }
            },
        )
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "暂停再恢复每日回顾提醒",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )

        val paused = registry.execute(
            ToolCall(
                id = "tool-call-task-pause",
                name = "tasks.pause",
                arguments = mapOf("name" to " 每日回顾 "),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )
        val resumed = registry.execute(
            ToolCall(
                id = "tool-call-task-resume",
                name = "tasks.resume",
                arguments = mapOf("name" to " 每日回顾 "),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        assertEquals(
            listOf(
                "pause:每日回顾:conversation-direct:tool-call-task-pause",
                "resume:每日回顾:conversation-direct:tool-call-task-resume",
            ),
            calls,
        )
        assertTrue(paused.success)
        assertEquals(true, paused.verified)
        assertTrue(paused.content.contains("周期计划已暂停"))
        assertTrue(paused.content.contains("正在运行的实例保持不变"))
        assertTrue(resumed.success)
        assertEquals(true, resumed.verified)
        assertTrue(resumed.content.contains("周期计划已恢复"))
        assertTrue(resumed.content.contains("下次：2026-07-17 09:37"))
        assertFalse(paused.content.contains("schedule-private-id"))
        assertFalse(resumed.content.contains("scheduled-task-private-id"))
    }

    @Test
    fun taskCancelIsHiddenOutsideForegroundDirectAgent() = runTest {
        val registry = testRegistry()
        assertTrue(registry.availableToolsFor(null).none { definition -> definition.name == "tasks.cancel" })
        registry.bindRunContext(workflowDeviceContext(userIntent = "取消后台任务"))
        assertTrue(registry.availableTools().none { definition -> definition.name == "tasks.cancel" })
        registry.bindRunContext(
            AgentToolExecutionContext(
                conversationId = "conversation-direct",
                userMessageId = "message-direct",
                runId = "run-direct",
                goal = "取消后台任务",
                executionOrigin = AgentExecutionOrigin.FOREGROUND,
                invocationSource = AgentInvocationSource.DIRECT,
            ),
        )
        assertNotNull(registry.definition("tasks.cancel"))
    }

    @Test
    fun directPlanningContextRebindsBeforeProfileToolLookup() = runTest {
        val registry = testRegistry()
        registry.bindRunContext(workflowDeviceContext(userIntent = "重试失败的每日回顾任务"))

        val directContext = AgentToolExecutionContext(
            conversationId = "conversation-direct",
            userMessageId = "message-direct",
            runId = "run-direct",
            goal = "重试失败的每日回顾任务",
            invocationSource = AgentInvocationSource.DIRECT,
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
        )
        // long: ProfileScopedToolRegistry 初始化会检查每个白名单定义；若不先绑定当前直接 Agent，上一个 Workflow Context 会错误隐藏 tasks.retry。
        registry.bindRunContext(directContext)
        val profileRegistry = ProfileScopedToolRegistry(registry, setOf("tasks.retry"))

        assertNotNull(profileRegistry.definition("tasks.retry"))
    }

    private fun testRegistry(
        conversationStore: AgentConversationStore = InMemoryAgentConversationStore(),
        taskStore: AgentTaskStore = EmptyTestAgentTaskStore,
        noteStore: InMemoryAgentNoteStore = InMemoryAgentNoteStore(),
        memoryStore: InMemoryAgentMemoryStore = InMemoryAgentMemoryStore(),
        knowledgeStore: KnowledgeDocumentStore = InMemoryKnowledgeDocumentStore(),
        calendarEventReader: CalendarEventReader = UnavailableCalendarEventReader,
        calendarEventWriter: CalendarEventWriter = UnavailableCalendarEventWriter,
        contactReader: ContactReader = UnavailableContactReader,
        appInfoReader: AppInfoReader = UnavailableAppInfoReader,
        batteryStatusReader: BatteryStatusReader = UnavailableBatteryStatusReader,
        connectivityStatusReader: ConnectivityStatusReader = UnavailableConnectivityStatusReader,
        storageStatusReader: StorageStatusReader = UnavailableStorageStatusReader,
        deviceController: DeviceController = FakeDeviceController(enabled = false),
        workflowDeviceActionToolNames: Set<String> = setOf("device.tap_ref"),
        clock: AgentClock = FakeAgentClock(),
    ): XiaoLingToolRegistry {
        return XiaoLingToolRegistry(
            clock = clock,
            conversationStore = conversationStore,
            taskStore = taskStore,
            noteStore = noteStore,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
            calendarEventReader = calendarEventReader,
            calendarEventWriter = calendarEventWriter,
            contactReader = contactReader,
            appInfoReader = appInfoReader,
            batteryStatusReader = batteryStatusReader,
            connectivityStatusReader = connectivityStatusReader,
            storageStatusReader = storageStatusReader,
            deviceController = deviceController,
            workflowDeviceActionToolNames = workflowDeviceActionToolNames,
        )
    }

    private fun directCalendarDeleteContext(): AgentToolExecutionContext = AgentToolExecutionContext(
        conversationId = "conversation-calendar-delete",
        userMessageId = "message-calendar-delete",
        runId = "run-calendar-delete",
        goal = "删除项目评审日程",
        executionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource = AgentInvocationSource.DIRECT,
    )

    private fun directCalendarUpdateContext(): AgentToolExecutionContext = AgentToolExecutionContext(
        conversationId = "conversation-calendar-update",
        userMessageId = "message-calendar-update",
        runId = "run-calendar-update",
        goal = "修改项目评审日程",
        executionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource = AgentInvocationSource.DIRECT,
    )

    private fun calendarUpdateFixture(): CalendarEventDetailRecord = CalendarEventDetailRecord(
        eventId = 84L,
        title = "项目评审",
        startAtMillis = 1_000L,
        endAtMillis = 2_000L,
        allDay = false,
        timeZoneId = "Asia/Shanghai",
        recurring = false,
    )

    private fun calendarUpdateCall(event: CalendarEventDetailRecord): ToolCall = ToolCall(
        id = "tool-call-calendar-update-1",
        name = "calendar.update_event",
        arguments = mapOf(
            "event_id" to "calendar-${event.eventId}",
            "expected_fingerprint" to CalendarEventFingerprint.create(event),
            "scope" to "event",
            "title" to "项目评审（已调整）",
            "start_at" to "2026-08-08T10:00:00+08:00",
            "end_at" to "2026-08-08T11:00:00+08:00",
            "time_zone" to "Asia/Shanghai",
        ),
        risk = ToolRisk.REQUIRES_APPROVAL,
    )

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

private object EmptyTestAgentTaskStore : AgentTaskStore {
    override suspend fun list(limit: Int): List<AgentTaskRecord> = emptyList()
    override suspend fun inspect(name: String): AgentTaskInspectionResult = AgentTaskInspectionResult.NotFound
}

private class InMemoryCalendarEventWriter(
    private var deletableEvent: CalendarEventDetailRecord? = null,
    private var updatableEvent: CalendarEventDetailRecord? = null,
) : CalendarEventWriter {
    val records = mutableListOf<Pair<CalendarEventWriteRequest, CalendarEventWriteRecord>>()
    var deleteCount: Int = 0
        private set
    var verifyDeleteCount: Int = 0
        private set
    var updateCount: Int = 0
        private set
    var verifyUpdateCount: Int = 0
        private set

    override suspend fun createOrReadBack(request: CalendarEventWriteRequest): CalendarEventWriteResult {
        records.firstOrNull { it.first.idempotencyKey == request.idempotencyKey }?.let { (existingRequest, record) ->
            return if (existingRequest == request) {
                CalendarEventWriteResult.Committed(record.copy(reused = true), verified = true)
            } else {
                CalendarEventWriteResult.Conflict
            }
        }
        val record = CalendarEventWriteRecord(
            eventId = (197 + records.size).toString(),
            title = request.title,
            startAtMillis = request.startAtMillis,
            endAtMillis = request.endAtMillis,
            timeZoneId = request.timeZoneId,
            allDay = request.allDay,
            reused = false,
            reminderMinutesBefore = request.reminderMinutesBefore,
            reminderCount = if (request.reminderMinutesBefore == null) 0 else 1,
        )
        records += request to record
        return CalendarEventWriteResult.Committed(record, verified = true)
    }

    override suspend fun verifyCommitted(
        eventId: String,
        request: CalendarEventWriteRequest,
    ): CalendarEventWriteResult {
        val record = records.firstOrNull { it.first == request && it.second.eventId == eventId }?.second
            ?: return CalendarEventWriteResult.Failed
        return CalendarEventWriteResult.Committed(record.copy(reused = true), verified = true)
    }

    override suspend fun deleteOrReadBack(request: CalendarEventDeleteRequest): CalendarEventDeleteResult {
        val current = deletableEvent ?: return CalendarEventDeleteResult.NotFound
        if (request.scope == CalendarEventDeleteScope.OCCURRENCE) return CalendarEventDeleteResult.OccurrenceUnsupported
        if (CalendarEventFingerprint.create(current) != request.expectedFingerprint) {
            return CalendarEventDeleteResult.FingerprintMismatch
        }
        if (current.recurring != (request.scope == CalendarEventDeleteScope.SERIES)) {
            return CalendarEventDeleteResult.ScopeMismatch
        }
        deleteCount += 1
        deletableEvent = null
        return CalendarEventDeleteResult.Committed(
            deletion = CalendarEventDeleteRecord(request.eventId, request.scope, reused = false),
            verified = true,
        )
    }

    override suspend fun verifyDeleteCommitted(
        eventId: String,
        request: CalendarEventDeleteRequest,
    ): CalendarEventDeleteResult {
        verifyDeleteCount += 1
        return if (eventId == "calendar-${request.eventId}" && deletableEvent == null) {
            CalendarEventDeleteResult.Committed(
                deletion = CalendarEventDeleteRecord(request.eventId, request.scope, reused = true),
                verified = true,
            )
        } else {
            CalendarEventDeleteResult.Failed
        }
    }

    override suspend fun updateOrReadBack(request: CalendarEventUpdateRequest): CalendarEventUpdateResult {
        val current = updatableEvent ?: return CalendarEventUpdateResult.NotFound
        if (request.scope == CalendarEventUpdateScope.OCCURRENCE) return CalendarEventUpdateResult.OccurrenceUnsupported
        if (request.scope == CalendarEventUpdateScope.SERIES) return CalendarEventUpdateResult.SeriesUnsupported
        if (CalendarEventFingerprint.create(current) != request.expectedFingerprint) {
            return CalendarEventUpdateResult.FingerprintMismatch
        }
        if (current.recurring) return CalendarEventUpdateResult.ScopeMismatch
        updateCount += 1
        val updated = current.copy(
            title = request.title,
            startAtMillis = request.startAtMillis,
            endAtMillis = request.endAtMillis,
            timeZoneId = request.timeZoneId,
        )
        updatableEvent = updated
        return CalendarEventUpdateResult.Committed(
            update = CalendarEventUpdateRecord(
                eventId = request.eventId,
                scope = request.scope,
                fingerprint = CalendarEventFingerprint.create(updated),
                reused = false,
            ),
            verified = true,
        )
    }

    override suspend fun verifyUpdateCommitted(
        eventId: String,
        request: CalendarEventUpdateRequest,
    ): CalendarEventUpdateResult {
        verifyUpdateCount += 1
        val current = updatableEvent
        return if (
            eventId == "calendar-${request.eventId}" &&
            request.scope == CalendarEventUpdateScope.EVENT &&
            current?.title == request.title &&
            current.startAtMillis == request.startAtMillis &&
            current.endAtMillis == request.endAtMillis &&
            current.timeZoneId == request.timeZoneId
        ) {
            CalendarEventUpdateResult.Committed(
                update = CalendarEventUpdateRecord(
                    eventId = request.eventId,
                    scope = request.scope,
                    fingerprint = CalendarEventFingerprint.create(current),
                    reused = true,
                ),
                verified = true,
            )
        } else {
            CalendarEventUpdateResult.Failed
        }
    }
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
        AgentConversationRecord(
            id = "conversation-table-history",
            title = "历史表格排查",
            summary = "检查旧表格格式。",
            messageCount = 4,
            updatedAt = 8,
        ),
    )

    override suspend fun list(limit: Int): List<AgentConversationRecord> = conversations.take(limit)

    override suspend fun search(
        query: String,
        limit: Int,
        excludeConversationId: String?,
    ): List<AgentConversationRecord> {
        return conversations
            .filterNot { it.id == excludeConversationId }
            .filter { it.title.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun get(conversationId: String): AgentConversationDetailRecord? {
        val conversation = conversations.firstOrNull { it.id == conversationId } ?: return null
        return AgentConversationDetailRecord(
            id = conversation.id,
            title = conversation.title,
            updatedAt = conversation.updatedAt,
            messages = listOf(
                AgentConversationMessageRecord(
                    role = AgentConversationMessageRole.USER,
                    text = "请检查 ${conversation.title}",
                    createdAt = 1,
                ),
                AgentConversationMessageRecord(
                    role = AgentConversationMessageRole.ASSISTANT,
                    text = conversation.summary,
                    createdAt = 2,
                ),
            ),
        )
    }
}

private open class InMemoryAgentNoteStore : AgentNoteManagementStore {
    val records = mutableListOf<AgentNoteRecord>()
    val deletedIds = mutableListOf<String>()
    var deleteCallCount = 0
    var updateCallCount = 0
    private val recordsByIdempotencyKey = mutableMapOf<String, AgentNoteRecord>()
    private val updateRequestsByIdempotencyKey = mutableMapOf<String, AgentNoteUpdateRequest>()
    private val updateResultsByIdempotencyKey = mutableMapOf<String, AgentNoteRecord>()

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

    override suspend fun delete(id: String): Boolean {
        deleteCallCount += 1
        val removed = records.removeAll { it.id == id }
        if (removed) deletedIds += id
        return removed
    }

    override suspend fun update(
        request: AgentNoteUpdateRequest,
        idempotencyKey: String,
    ): AgentNoteUpdateResult {
        updateCallCount += 1
        updateRequestsByIdempotencyKey[idempotencyKey]?.let { existingRequest ->
            if (existingRequest != request) throw AgentNoteUpdateIdempotencyConflictException()
            val expectedResult = checkNotNull(updateResultsByIdempotencyKey[idempotencyKey])
            val current = get(request.noteId) ?: return AgentNoteUpdateResult.NotFound
            return if (current == expectedResult) {
                AgentNoteUpdateResult.Updated(current)
            } else {
                AgentNoteUpdateResult.RevisionConflict(current)
            }
        }
        val current = get(request.noteId) ?: return AgentNoteUpdateResult.NotFound
        if (current.revision != request.expectedRevision) return AgentNoteUpdateResult.RevisionConflict(current)
        if (current.title == request.title && current.content == request.content) {
            return AgentNoteUpdateResult.Unchanged(current)
        }
        val updated = current.copy(
            title = request.title,
            content = request.content,
            updatedAt = current.updatedAt + 1L,
            revision = current.revision + 1L,
        )
        records[records.indexOfFirst { it.id == request.noteId }] = updated
        updateRequestsByIdempotencyKey[idempotencyKey] = request
        updateResultsByIdempotencyKey[idempotencyKey] = updated
        return AgentNoteUpdateResult.Updated(updated)
    }

    override suspend fun verifyUpdateOperation(
        idempotencyKey: String,
        noteId: String,
        request: AgentNoteUpdateRequest,
    ): AgentNoteUpdateVerification {
        val storedRequest = updateRequestsByIdempotencyKey[idempotencyKey]
            ?: return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.OPERATION_NOT_FOUND)
        if (storedRequest != request || storedRequest.noteId != noteId) {
            return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.PAYLOAD_MISMATCH)
        }
        val expectedResult = checkNotNull(updateResultsByIdempotencyKey[idempotencyKey])
        val current = get(noteId)
            ?: return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.NOTE_NOT_FOUND)
        return if (current == expectedResult) {
            AgentNoteUpdateVerification.Verified(current)
        } else {
            AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.NOTE_CHANGED)
        }
    }
}

private open class InMemoryAgentMemoryStore : AgentMemoryStore {
    val records = mutableListOf<AgentMemoryRecord>()
    val searchQueries = mutableListOf<String>()
    val getQueries = mutableListOf<String>()
    var rememberCallCount = 0
    var verificationCallCount = 0
    var deleteCallCount = 0
    var deletedRecord: AgentMemoryRecord? = null
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

    open override suspend fun get(memoryId: String): AgentMemoryRecord? {
        getQueries += memoryId
        return records.firstOrNull { it.id == memoryId }
    }

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

    override suspend fun deleteForAgent(memoryId: String, idempotencyKey: String): Boolean {
        deleteCallCount += 1
        val memory = records.firstOrNull { it.id == memoryId } ?: return false
        deletedRecord = memory
        records.remove(memory)
        return true
    }

    override suspend fun verifyDeletedOperation(
        idempotencyKey: String,
        memoryId: String,
    ): AgentMemoryDeleteOperationVerification {
        return if (deletedRecord?.id == memoryId && records.none { it.id == memoryId }) {
            AgentMemoryDeleteOperationVerification.Verified
        } else {
            AgentMemoryDeleteOperationVerification.Failed(AgentMemoryDeleteOperationVerificationFailure.MEMORY_STILL_EXISTS)
        }
    }
}
