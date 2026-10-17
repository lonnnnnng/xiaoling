package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: 同一权限向量同时运行于 JVM 和 Redmi；测试密钥只在内存生成。 */
internal class PluginExecutionFixture(manifest: AgentPluginManifest = AgentPluginSigningFixture.manifest(),
    val store: PluginExecutionStore = PluginExecutionStore()) {
    val key = AgentPluginSigningFixture.key()
    val signed = AgentPluginSigningFixture.sign(manifest, key)
    val confirmation = AgentPluginSigning.verify(signed)
    val plugins = AgentPluginRegistry(store)
    val foreground = AgentPluginForegroundState()
    val owner = Any()
    init { plugins.installSigned(signed, confirmation); plugins.setEnabled(manifest.id, true); foreground.resumed(owner) }
    fun selection() = AgentPluginExecutionSelection.capture(plugins, foreground, signed.manifest.id, confirmation.manifestSha256)
    fun gateway(delegate: ToolRegistry = PluginExecutionTools(), selection: AgentPluginExecutionSelection = selection(),
        allowed: Set<String> = setOf("app.current_time", "memory.search", "notes.list", "notes.create"),
        origin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND, source: AgentInvocationSource = AgentInvocationSource.DIRECT,
        external: Boolean = false, parent: String? = null) =
        AgentPluginToolRegistry(delegate, plugins, foreground, selection, allowed, origin, source, external, parent)
    fun upgrade() { plugins.installSigned(AgentPluginSigningFixture.sign(signed.manifest.copy(version = "2.0.0"), key)) }
}

internal class PluginExecutionStore : AgentPluginStateStore {
    var state: String? = null
    var fail = false
    override fun loadState() = state
    override fun saveState(state: String) { check(!fail) { "fixture write failed" }; this.state = state }
}

internal class PluginExecutionTools : ToolRegistry, AgentRunContextAwareToolRegistry,
    AgentRunLifecycleAwareToolRegistry, AgentToolExecutionLifecycleAwareToolRegistry {
    var definitions = listOf(ToolDefinition("app.current_time", "读取时间", ToolRisk.SAFE),
        ToolDefinition("memory.search", "读取记忆", ToolRisk.SAFE,
            inputSchema = listOf(ToolInputField("query", "检索词", required = true))),
        ToolDefinition("notes.list", "读取笔记", ToolRisk.SAFE),
        ToolDefinition("notes.create", "创建笔记", ToolRisk.SAFE))
    var staticDefinitions: List<ToolDefinition>? = null
    var hidden = emptySet<String>()
    var calls = 0
    var before = 0
    var after = 0
    var finished = false
    var executeBlock: suspend () -> ToolExecutionResult = { ToolExecutionResult(true, "隔离事实", verified = true) }
    override fun availableTools() = definitions.filter { it.name !in hidden }
    override fun definition(name: String) = availableTools().firstOrNull { it.name == name }
    override fun registeredTools() = staticDefinitions ?: definitions
    override fun registeredDefinition(name: String) = registeredTools().firstOrNull { it.name == name }
    override fun bindRunContext(context: AgentToolExecutionContext) = Unit
    override suspend fun execute(call: ToolCall): ToolExecutionResult { calls++; return executeBlock() }
    override fun beforeToolExecution(call: ToolCall, approval: AgentToolApprovalEvidence?) { before++ }
    override fun afterToolVerification(call: ToolCall, result: ToolExecutionResult) { after++ }
    override fun onRunFinished(runId: String) { finished = true }
}

internal fun AgentPluginToolRegistry.bindFixture() = bindRunContext(AgentToolExecutionContext("conversation", "message", "run", "隔离目标"))
internal fun pluginTimeCall() = ToolCall(name = "app.current_time", arguments = emptyMap(), risk = ToolRisk.SAFE)

abstract class AgentPluginExecutionContractTests {
    @Test fun freezesSignedProfileAciIntersectionAndAuditsOnlyDigests() {
        val f = PluginExecutionFixture(AgentPluginSigningFixture.manifest().copy(capabilities = setOf("app.current_time", "memory.search", "notes.create")))
        val g = f.gateway(allowed = setOf("app.current_time", "notes.create"))
        assertEquals(listOf("app.current_time"), g.availableTools().map { it.name })
        assertTrue(g.catalog.entries.all { AgentToolCatalogOrigin.PLUGIN in it.origins })
        assertEquals(10_000L, g.definition("app.current_time")!!.timeoutMs)
        val audit = JSONObject(g.evidenceJson())
        assertEquals(f.confirmation.manifestSha256, audit.getString("manifestSha256"))
        assertEquals(f.confirmation.publicKeySha256, audit.getString("publicKeySha256"))
        assertFalse(g.evidenceJson().contains(f.signed.manifest.description))
    }

    @Test fun unsignedDisabledMissingPermissionAndStaleDigestCannotSelect() {
        val registry = AgentPluginRegistry()
        val manifest = AgentPluginSigningFixture.manifest()
        registry.install(manifest); registry.setEnabled(manifest.id, true)
        assertTrue(runCatching { registry.executionBinding(manifest.id, "bad") }.isFailure)
        val f = PluginExecutionFixture()
        assertTrue(runCatching { f.plugins.executionBinding(manifest.id, "bad") }.isFailure)
        f.plugins.setEnabled(manifest.id, false)
        assertTrue(runCatching { f.selection() }.isFailure)
        val missing = PluginExecutionFixture(manifest.copy(permissions = emptySet()))
        assertTrue(runCatching { missing.selection() }.isFailure)
    }

    @Test fun backgroundWorkflowExternalInputChildAndEmptyIntersectionAreRejected() {
        val f = PluginExecutionFixture()
        assertTrue(runCatching { f.gateway(origin = AgentExecutionOrigin.BACKGROUND) }.isFailure)
        assertTrue(runCatching { f.gateway(source = AgentInvocationSource.WORKFLOW) }.isFailure)
        assertTrue(runCatching { f.gateway(external = true) }.isFailure)
        assertTrue(runCatching { f.gateway(parent = "parent") }.isFailure)
        assertTrue(runCatching { f.gateway(allowed = emptySet()) }.isFailure)
    }

    @Test fun selectionIsSingleUseAndBoundToOneRunAndRegistry() {
        val f = PluginExecutionFixture(); val selection = f.selection(); val g = f.gateway(selection = selection)
        assertTrue(runCatching { f.gateway(selection = selection) }.isFailure)
        g.bindFixture()
        assertTrue(runCatching { g.bindRunContext(AgentToolExecutionContext("conversation", "message", "other", "隔离目标")) }.isFailure)
        assertFalse(AgentPluginRegistry(f.store).isExecutionCurrent(selection.binding))
    }

    @Test fun restoredEnabledPluginAllowsNewSelectionWithZeroLongRevision() {
        val f = PluginExecutionFixture(); val restored = AgentPluginRegistry(f.store)
        val selection = AgentPluginExecutionSelection.capture(restored, f.foreground, "signed.read", f.confirmation.manifestSha256)
        assertEquals(0L, selection.binding.revision)
        assertTrue(restored.isExecutionCurrent(selection.binding))
        val g = AgentPluginToolRegistry(PluginExecutionTools(), restored, f.foreground, selection, setOf("app.current_time"))
        assertEquals(listOf("app.current_time"), g.availableTools().map { it.name })
    }

    @Test fun dynamicRiskApprovalAndDefinitionDriftCannotExecute() = runBlocking {
        listOf<(ToolDefinition) -> ToolDefinition>({ it.copy(risk = ToolRisk.REQUIRES_APPROVAL, approvalPolicy = ToolApprovalPolicy.REQUIRE_CONFIRMATION) },
            { it.copy(approvalPolicy = ToolApprovalPolicy.REQUIRE_CONFIRMATION) }, { it.copy(description = "改变语义") }).forEach { change ->
            val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); val g = f.gateway(tools); g.bindFixture()
            tools.definitions = tools.definitions.map { if (it.name == "app.current_time") change(it) else it }
            assertNull(g.definition("app.current_time"))
            assertTrue(runCatching { g.execute(pluginTimeCall()) }.isFailure)
            assertEquals(0, tools.calls)
        }
        val tools = PluginExecutionTools(); tools.staticDefinitions = tools.definitions.map {
            it.copy(risk = ToolRisk.REQUIRES_APPROVAL, approvalPolicy = ToolApprovalPolicy.REQUIRE_CONFIRMATION) }
        assertTrue(runCatching { PluginExecutionFixture().gateway(tools) }.isFailure)
    }

    @Test fun newlyAvailableToolsDoNotExpandFrozenCatalog() {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); tools.hidden = setOf("memory.search")
        val g = f.gateway(tools)
        tools.hidden = emptySet()
        assertEquals(listOf("app.current_time"), g.availableTools().map { it.name })
    }

    @Test fun observedDynamicHideAndDefinitionDriftRemainRevokedAfterRestoration() {
        val tools = PluginExecutionTools(); val g = PluginExecutionFixture().gateway(tools)
        tools.hidden = setOf("app.current_time"); assertNull(g.definition("app.current_time"))
        tools.hidden = emptySet(); assertNull(g.definition("app.current_time"))
        val original = tools.definitions
        tools.definitions = original.map { if (it.name == "memory.search") it.copy(description = "漂移") else it }
        assertNull(g.definition("memory.search"))
        tools.definitions = original; assertNull(g.definition("memory.search"))
    }

    @Test fun validatesArgumentsAndRunBindingBeforeReading() = runBlocking {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); val g = f.gateway(tools)
        assertTrue(runCatching { g.execute(pluginTimeCall()) }.isFailure)
        g.bindFixture()
        assertTrue(runCatching { g.execute(ToolCall(name = "memory.search", arguments = emptyMap(), risk = ToolRisk.SAFE)) }.isFailure)
        assertEquals(0, tools.calls)
    }

    @Test fun forwardsLifecycleAndRejectsReuseAfterFinish() = runBlocking {
        val tools = PluginExecutionTools(); val g = PluginExecutionFixture().gateway(tools); g.bindFixture()
        val call = pluginTimeCall(); g.beforeToolExecution(call, null)
        val result = g.execute(call); g.afterToolVerification(call, result); g.onRunFinished("run")
        assertEquals(1, tools.before); assertEquals(1, tools.after); assertTrue(tools.finished)
        assertTrue(runCatching { g.execute(call) }.exceptionOrNull() is CancellationException)
    }

    @Test fun disableEnablePauseResumeUpgradeRollbackAndRemoveNeverReviveOldSelection() {
        val f = PluginExecutionFixture(); val selected = f.selection()
        f.plugins.setEnabled("signed.read", false); f.plugins.setEnabled("signed.read", true)
        assertFalse(f.plugins.isExecutionCurrent(selected.binding))
        val foreground = f.foreground.capture(); f.foreground.paused(f.owner); f.foreground.resumed(f.owner)
        assertFalse(f.foreground.isCurrent(foreground))
        val old = f.selection(); f.upgrade()
        assertFalse(f.plugins.isExecutionCurrent(old.binding))
        f.plugins.setEnabled("signed.read", true); val newer = f.plugins.executionBinding("signed.read",
            AgentPluginSigning.verify(f.plugins.get("signed.read")!!.signedManifest!!).manifestSha256)
        f.plugins.rollback("signed.read", "1.0.0"); f.plugins.setEnabled("signed.read", true)
        assertFalse(f.plugins.isExecutionCurrent(newer))
        val rolled = f.selection(); f.plugins.uninstall("signed.read")
        assertFalse(f.plugins.isExecutionCurrent(rolled.binding))
    }

    @Test fun failedWriteRevokesAndIdempotentInstallKeepsSelection() {
        val f = PluginExecutionFixture(); val selected = f.selection()
        f.plugins.installSigned(AgentPluginSigningFixture.sign(f.signed.manifest, f.key))
        f.plugins.setEnabled("signed.read", true)
        assertTrue(f.plugins.isExecutionCurrent(selected.binding))
        val before = f.store.state; f.store.fail = true; f.plugins.setEnabled("signed.read", false)
        assertEquals(before, f.store.state); assertFalse(f.plugins.isExecutionCurrent(selected.binding))
        assertTrue(runCatching { f.selection() }.isFailure)
    }

    @Test fun outputLimitTimeoutAndLateRevocationRejectResults() = runBlocking {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); val g = f.gateway(tools); g.bindFixture()
        tools.executeBlock = { ToolExecutionResult(true, "x".repeat(20_001)) }
        assertTrue(runCatching { g.execute(pluginTimeCall()) }.isFailure)
        tools.executeBlock = { f.plugins.setEnabled("signed.read", false); ToolExecutionResult(true, "迟到成功") }
        assertTrue(runCatching { g.execute(pluginTimeCall()) }.exceptionOrNull() is CancellationException)
        val timed = PluginExecutionTools(); timed.definitions = timed.definitions.map { it.copy(timeoutMs = 20) }
        timed.executeBlock = { delay(200); ToolExecutionResult(true, "超时成功") }
        val timeout = PluginExecutionFixture().gateway(timed); timeout.bindFixture()
        assertTrue(runCatching { timeout.execute(pluginTimeCall()) }.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test fun wholeRunWatcherCancelsModelWaitEvenAfterFastAba() = runBlocking {
        val f = PluginExecutionFixture(); val g = f.gateway(); val waiting = CompletableDeferred<Unit>()
        val job = async { runCatching { g.guardRun { waiting.complete(Unit); awaitCancellation() } } }
        waiting.await(); f.plugins.setEnabled("signed.read", false); f.plugins.setEnabled("signed.read", true)
        assertTrue(withTimeout(2_000) { job.await() }.exceptionOrNull() is CancellationException)
        val next = f.gateway(); val wait = CompletableDeferred<Unit>()
        val foregroundJob = async { runCatching { next.guardRun { wait.complete(Unit); awaitCancellation() } } }
        wait.await(); f.foreground.paused(f.owner); f.foreground.resumed(f.owner)
        assertTrue(withTimeout(2_000) { foregroundJob.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun successfulGuardDoesNotTurnClosedRuntimeIntoCancellation() = runBlocking {
        val g = PluginExecutionFixture().gateway(); g.bindFixture()
        assertEquals("done", g.guardRun { g.onRunFinished("run"); "done" })
    }
}
