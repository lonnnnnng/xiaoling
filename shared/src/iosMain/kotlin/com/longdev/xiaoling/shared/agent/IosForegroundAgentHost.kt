@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.browser.*
import com.longdev.xiaoling.shared.http.*
import com.longdev.xiaoling.shared.workspace.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.UIKit.*

/** long: 首个宿主绑定单窗口UIApplication生命周期；前台消失只撤销当前Run，重新激活不能自动续跑。 */
class IosForegroundAgentHost(
    private val workspaceRoot: String,
    pages: Map<String, String>,
    endpoint: String,
    model: String,
    apiKey: String,
    private val onEvent: (String) -> Unit,
) {
    private val config = SharedForegroundModelConfig(endpoint, model, apiKey)
    private val pages = pages.toMap().also { SharedBrowserPageRegistry.https(it) }
    private val lock = NSLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appActive = false
    private var closed = false
    private var current: Run? = null
    private var sequence = 0
    private var lastEvent = "{}"
    private val observers = mutableListOf<Any>()

    init {
        mainThread()
        appActive = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            locked { appActive = true }
        }
        for (name in listOf(UIApplicationWillResignActiveNotification, UIApplicationDidEnterBackgroundNotification)) {
            observers += center.addObserverForName(name, null, NSOperationQueue.mainQueue) {
                locked { appActive = false }; revoke("FOREGROUND_REVOKED")
            }
        }
    }

    fun start(goal: String): Boolean {
        mainThread()
        if (goal.isBlank() || goal.length > 4096) return false
        try { goal.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { return false }
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return false
        val run = locked {
            if (closed || current != null) return false
            appActive = true
            Run(NSUUID().UUIDString.lowercase()).also { current = it }
        }
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(90_000) {
                    checkOwner(run)
                    val files = IosLocalWorkspace(workspaceRoot)
                    var browser: IosReadOnlyBrowser? = null
                    var http: IosHttpClient? = null
                    val resources = try {
                        browser = IosReadOnlyBrowser(pages)
                        http = IosHttpClient(SharedHttpTargetPolicy.https(setOf(config.endpoint)))
                        Resources(files, browser, http)
                    } catch (error: Throwable) { files.close(); browser?.close(); http?.close(); throw error }
                    run.resources = resources
                    val owner = { owns(run) }
                    val workspace = SharedReadOnlyWorkspaceExecutor(files, owner)
                    val reader = SharedReadOnlyBrowserExecutor(resources.browser, owner)
                    val catalog = SharedToolCatalog.from(workspace.catalog.definitions + reader.catalog.definitions)
                    val executor = SharedToolExecutor { call ->
                        currentCoroutineContext().ensureActive(); checkOwner(run)
                        val result = if (call.name.startsWith("workspace.")) workspace.execute(call) else reader.execute(call)
                        currentCoroutineContext().ensureActive(); checkOwner(run)
                        run.history.record(call, result)
                        run.tools += call.name
                        emit(run, "RUNNING", "TOOL_RESULT")
                        result
                    }
                    val llm = SharedForegroundAgentLlm(config, resources.http, run.history,
                        { NSUUID().UUIDString.lowercase() }, {
                            checkOwner(run)
                            run.modelRequests++
                            emit(run, "RUNNING", "MODEL_REQUEST")
                        })
                    val gate = object : SharedApprovalGate {
                        override suspend fun requestApproval(runId: String, toolCall: SharedToolCall, definition: SharedToolDefinition) = false
                    }
                    val result = SharedAgentRuntime(llm, gate, executor, 8).run(run.id, goal, catalog)
                    currentCoroutineContext().ensureActive(); checkOwner(run)
                    emit(run, if (result.status == SharedAgentRunStatus.COMPLETED) "COMPLETED" else "FAILED", result.status.name, result.summary)
                }
            } catch (_: TimeoutCancellationException) {
                if (!run.revoked) emit(run, "FAILED", "RUN_TIMEOUT")
            } catch (_: CancellationException) {
                if (!run.revoked) emit(run, "CANCELLED", "CANCELLED")
            } catch (error: SharedForegroundModelException) {
                if (!run.revoked) emit(run, "FAILED", error.code)
            } catch (_: Throwable) {
                if (!run.revoked) emit(run, "FAILED", "HOST_FAILURE")
            } finally {
                run.resources?.close()
                locked { if (current === run) current = null }
            }
        }
        // long: lazy job在首个回调里被取消时不会进入finally；完成钩子仍须释放槽位，不能永久拒绝下一次显式Run。
        run.job!!.invokeOnCompletion { locked { if (current === run) current = null } }
        // long: 首次事件消费者抛错时lazy job尚未启动，必须主动取消以释放槽位，让下一次显式Run仍可开始。
        try { emit(run, "RUNNING", "STARTED") } catch (error: Throwable) { run.job!!.cancel(); throw error }
        run.job!!.start()
        return true
    }

    fun cancel() { mainThread(); revoke("USER_CANCELLED") }
    fun snapshot(): String = locked { lastEvent }
    fun close() {
        mainThread()
        if (locked { if (closed) true else { closed = true; false } }) return
        revoke("HOST_CLOSED")
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }; observers.clear()
        scope.cancel()
    }
    private fun revoke(reason: String) {
        mainThread()
        val run = locked { current?.takeUnless { it.revoked || it.terminal }?.also { it.revoked = true } } ?: return
        // long: 先撤销可读owner，再cancel真实请求；旧Run的finally或晚到模型响应不能向新Run发布结果。
        run.job?.cancel()
        run.resources?.close()
        emit(run, "CANCELLED", reason, allowRevoked = true)
    }
    private fun owns(run: Run): Boolean = locked { appActive && !closed && current === run && !run.revoked && !run.terminal }
    private fun checkOwner(run: Run) {
        mainThread()
        if (!owns(run) || UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) throw CancellationException("前台owner已撤销")
    }
    private fun emit(run: Run, status: String, reason: String, summary: String = "", allowRevoked: Boolean = false) {
        mainThread()
        if (!allowRevoked && !owns(run)) return
        if (status != "RUNNING") locked { if (run.terminal) return; run.terminal = true }
        val json = redact(buildJsonObject {
            put("sequence", ++sequence); put("run_id", run.id); put("status", status); put("reason", reason)
            put("model_requests", run.modelRequests); put("tools", JsonArray(run.tools.map(::JsonPrimitive)))
            put("frames", JsonArray(run.history.frames().map { Json.parseToJsonElement(it) }))
            put("summary", summary)
        }).toString()
        locked { lastEvent = json }
        onEvent(json)
    }
    // long: 在字段值序列化之前脱敏，凭据含引号或反斜杠时也不能因JSON转义而进入事件文件。
    private fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapKeys { it.key.replace(config.apiKey, "[REDACTED_SECRET]") }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.replace(config.apiKey, "[REDACTED_SECRET]")) else value
    }
    private fun mainThread() { check(NSThread.isMainThread) { "前台宿主必须由主线程调用" } }
    private inline fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }
    private class Run(val id: String) {
        var job: Job? = null
        var resources: Resources? = null
        var revoked = false
        var terminal = false
        var modelRequests = 0
        val history = SharedForegroundRunHistory()
        val tools = mutableListOf<String>()
    }
    private class Resources(val files: IosLocalWorkspace, val browser: IosReadOnlyBrowser, val http: IosHttpClient) {
        fun close() { http.close(); browser.close(); files.close() }
    }
}
