package com.longdev.xiaoling.device

import android.accessibilityservice.AccessibilityService
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.longdev.xiaoling.storage.UiPreferenceStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class XiaoLingAccessibilityService : AccessibilityService() {
    private val approvalCoordinator = DeviceActionApprovalOverlayCoordinator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val approvalPreferences by lazy { UiPreferenceStore(this) }
    private var activeApprovalOverlay: ActiveApprovalOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceAccessibilityRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val activeRootWindowId = rootInActiveWindow?.windowId ?: UNKNOWN_WINDOW_ID
        val windowSnapshots = captureAccessibilityWindows()
        // long: 外来窗口触发 fail-closed 后，只有后续系统窗口事件确认自有 overlay 已消失，才能把取消决定返回 Runtime。
        confirmPendingOverlayRemoval(windowSnapshots)
        val observation = approvalCoordinator.observeWindows(
            eventWindowId = event.windowId,
            eventType = event.eventType,
            activeRootWindowId = activeRootWindowId,
            windows = windowSnapshots,
            eventBelongsToOverlayHost = event.packageName?.toString() == packageName,
        )
        DeviceAccessibilityRuntime.onAccessibilityEvent(
            windowId = event.windowId,
            eventType = event.eventType,
            activeRootWindowId = activeRootWindowId,
            suppressInvalidation = observation.suppressGenerationInvalidation,
        )
        handleApprovalObservation(observation)
        val automatic = activeApprovalOverlay?.takeIf { it.autoApprove }
        if (automatic != null && windowSnapshots.any { it.ownedApprovalOverlay }) {
            if (approvalPreferences.loadDeviceActionAutoApprovalEnabled()) {
                // long: 仅在系统已确认自有浮层附着于稳定目标窗口后代替用户点击；随后仍等待浮层安全移除，窗口漂移继续拒绝动作。
                recordApprovalDecision(automatic.token, approved = true, autoApproved = true)
            } else {
                // long: 用户在待执行期间撤销授权时立即取消本次请求，不能让隐藏守护窗口无限等待或沿用旧开关状态。
                val cancelled = approvalCoordinator.cancel(automatic.token)
                removeApprovalView(automatic.token)
                if (cancelled != null) completeApproval(automatic.token, cancelled)
            }
        }
    }

    override fun onInterrupt() {
        applyLifecycleDecision(
            DeviceAccessibilityServiceLifecyclePolicy.decide(
                DeviceAccessibilityServiceLifecycleEvent.FEEDBACK_INTERRUPTED,
            ),
        )
    }

    override fun onDestroy() {
        applyLifecycleDecision(
            DeviceAccessibilityServiceLifecyclePolicy.decide(
                DeviceAccessibilityServiceLifecycleEvent.SERVICE_DESTROYED,
            ),
        )
        super.onDestroy()
    }

    private fun applyLifecycleDecision(decision: DeviceAccessibilityServiceLifecycleDecision) {
        if (decision.disconnectApproval) disconnectApprovalOverlay()
        if (decision.detachRuntime) DeviceAccessibilityRuntime.detach(this)
        if (decision.invalidateActiveWindow) DeviceAccessibilityRuntime.onWindowChanged()
    }

    internal fun captureRawWindow(): RawDeviceWindow? {
        val root = rootInActiveWindow ?: return null
        val budget = RawCaptureBudget()
        val rootNode = root.toRawNode(emptyList(), budget) ?: return null
        val title = windows.firstOrNull { it.id == root.windowId }?.title?.toString()
        return RawDeviceWindow(
            packageName = root.packageName?.toString().orEmpty(),
            windowTitle = title,
            windowId = root.windowId,
            generation = DeviceAccessibilityRuntime.markCapturedWindow(root.windowId),
            root = rootNode,
            truncated = budget.truncated,
        )
    }

    internal fun performGlobalDeviceAction(action: DeviceGlobalAction): Boolean {
        val androidAction = when (action) {
            DeviceGlobalAction.BACK -> GLOBAL_ACTION_BACK
            DeviceGlobalAction.HOME -> GLOBAL_ACTION_HOME
        }
        return performGlobalAction(androidAction)
    }

    internal suspend fun requestApprovalOverlay(
        request: DeviceActionApprovalOverlayRequest,
    ): DeviceActionApprovalOverlayDecision {
        return withContext(Dispatchers.Main.immediate) {
            if (request.autoApprove && !approvalPreferences.loadDeviceActionAutoApprovalEnabled()) {
                return@withContext overlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.CANCELLED,
                    "设备动作免逐次审批授权已撤销",
                )
            }
            // long: Room 写入 PENDING 审批后，Compose 会刷新运行卡；先等窗口与 generation 连续稳定，避免把应用自身的一次性刷新误判成审批期间的外来页面漂移。
            val baseline = awaitStableApprovalTarget()
            if (baseline == null) return@withContext overlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED,
                    "审批前目标页面持续变化，无法建立安全基线",
                )
            val started = approvalCoordinator.begin(
                targetWindowId = baseline.targetWindowId,
                windows = baseline.windows,
            )
            if (started is DeviceActionApprovalOverlayStart.Rejected) {
                return@withContext started.decision
            }
            val token = (started as DeviceActionApprovalOverlayStart.Started).token
            suspendCancellableCoroutine { continuation ->
                val windowManager = getSystemService(WindowManager::class.java)
                // long: 已显式授权时仍创建由 Accessibility 追踪的极小守护窗口，但不显示逐次批准按钮；窗口变化会沿用同一 fail-closed 结算。
                val root = if (request.autoApprove) {
                    View(this@XiaoLingAccessibilityService)
                } else {
                    createApprovalOverlayView(request, token)
                }
                val params = WindowManager.LayoutParams(
                    if (request.autoApprove) 1 else WindowManager.LayoutParams.MATCH_PARENT,
                    if (request.autoApprove) 1 else WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                    y = navigationBarInset(windowManager)
                    title = DEVICE_ACTION_APPROVAL_OVERLAY_TITLE
                }
                activeApprovalOverlay = ActiveApprovalOverlay(
                    token = token,
                    windowManager = windowManager,
                    view = root,
                    continuation = continuation,
                    autoApprove = request.autoApprove,
                )
                runCatching {
                    windowManager.addView(root, params)
                }.onSuccess {
                    approvalCoordinator.recordOverlayAdded(token)
                    if (request.autoApprove) {
                        // long: Redmi 对 1×1 的隐藏守护浮层不一定再发送 Accessibility 事件；附着成功后直接排队批准，仍由统一的移除确认和窗口集合守护决定最终结果。
                        mainHandler.post {
                            if (approvalPreferences.loadDeviceActionAutoApprovalEnabled()) {
                                recordApprovalDecision(token, approved = true, autoApproved = true)
                            } else {
                                val cancelled = approvalCoordinator.cancel(token)
                                removeApprovalView(token)
                                if (cancelled != null) completeApproval(token, cancelled)
                            }
                        }
                    }
                }.onFailure {
                    val decision = approvalCoordinator.overlayAddFailed(token)
                        ?: overlayDecision(
                            DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE,
                            "系统拒绝显示设备动作审批浮层",
                        )
                    completeApproval(token, decision)
                }
                continuation.invokeOnCancellation {
                    mainHandler.post {
                        cancelApproval(token)
                    }
                }
            }
        }
    }

    private suspend fun awaitStableApprovalTarget(): DeviceActionApprovalTargetSnapshot? {
        val stabilizer = DeviceActionApprovalBaselineStabilizer()
        repeat(APPROVAL_BASELINE_MAX_SAMPLES) { sampleIndex ->
            val rootWindowId = rootInActiveWindow?.windowId ?: return null
            val snapshot = DeviceActionApprovalTargetSnapshot(
                targetWindowId = rootWindowId,
                generation = DeviceAccessibilityRuntime.generationForWindow(rootWindowId),
                windows = captureAccessibilityWindows(),
            )
            stabilizer.sample(snapshot)?.let { return it }
            if (sampleIndex < APPROVAL_BASELINE_MAX_SAMPLES - 1) {
                delay(APPROVAL_BASELINE_SAMPLE_DELAY_MS)
            }
        }
        return null
    }

    private fun createApprovalOverlayView(
        request: DeviceActionApprovalOverlayRequest,
        token: Long,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 31, 38))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
            addView(TextView(context).apply {
                text = "小灵设备动作审批"
                setTextColor(Color.WHITE)
                textSize = 17f
            })
            addView(TextView(context).apply {
                text = request.userIntent
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 3
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(context).apply {
                // long: 浮层只展示用户意图和工具语义；snapshot ID、ref 与原始参数仍留在内部审批身份中，避免技术标识暴露到屏幕或辅助工具。
                text = "${request.toolDescription} · ${request.toolName}"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(context).apply {
                // long: 文本输入只告知字符数，不把原文、指纹或节点引用放到可截图、UIAutomator 或其他 Accessibility 服务可读的界面中。
                text = request.actionSummary
                setTextColor(Color.LTGRAY)
                textSize = 12f
                maxLines = 1
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val rejectButton = Button(this).apply {
            text = "拒绝"
            isAllCaps = false
            setOnClickListener { recordApprovalDecision(token, approved = false) }
        }
        val approveButton = Button(this).apply {
            text = "批准执行"
            isAllCaps = false
            setOnClickListener { recordApprovalDecision(token, approved = true) }
        }
        actions.addView(rejectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(approveButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        return root
    }

    private fun recordApprovalDecision(token: Long, approved: Boolean, autoApproved: Boolean = false) {
        if (!approvalCoordinator.recordUserDecision(token, approved, autoApproved)) return
        // long: 人工点击或显式设置授权作出决定后都先移除 overlay，再等待窗口集合恢复；批准不能抢跑到旧 ref 的 generation 校验之前。
        removeApprovalView(token)
        scheduleOverlayDetachTimeout(token)
    }

    private fun handleApprovalObservation(observation: DeviceActionApprovalOverlayObservation) {
        val completion = observation.completion
        if (observation.removeOverlay && completion != null) {
            val active = activeApprovalOverlay ?: return
            if (!active.viewAttached) {
                completeApproval(active.token, completion)
                return
            }
            active.pendingCompletion = completion
            removeApprovalView(active.token)
            scheduleOverlayDetachTimeout(active.token)
            return
        }
        observation.settleToken?.let(::scheduleOverlaySettlement)
        if (completion != null) {
            val token = activeApprovalOverlay?.token ?: return
            completeApproval(token, completion)
        }
    }

    private fun scheduleOverlaySettlement(token: Long) {
        val active = activeApprovalOverlay?.takeIf { it.token == token } ?: return
        if (active.settleConfirmation != null) return
        val confirmation = Runnable {
            val current = activeApprovalOverlay?.takeIf { it.token == token } ?: return@Runnable
            val decision = approvalCoordinator.settleDetachedOverlay(
                token = token,
                activeRootWindowId = rootInActiveWindow?.windowId ?: UNKNOWN_WINDOW_ID,
                windows = captureAccessibilityWindows(),
            ) ?: return@Runnable
            completeApproval(current.token, decision)
        }
        active.settleConfirmation = confirmation
        mainHandler.postDelayed(confirmation, OVERLAY_SETTLE_DELAY_MS)
    }

    private fun confirmPendingOverlayRemoval(windows: Set<DeviceAccessibilityWindowSnapshot>) {
        val active = activeApprovalOverlay ?: return
        val completion = active.pendingCompletion ?: return
        if (windows.any { it.ownedApprovalOverlay }) return
        completeApproval(active.token, completion)
    }

    private fun captureAccessibilityWindows(): Set<DeviceAccessibilityWindowSnapshot> {
        return windows
            .asSequence()
            // long: Redmi 在搜索输入框获得焦点后会让输入法窗口参与多轮动画和内容刷新；它不是用户切换目标应用，纳入审批基线会让免逐次授权长期等不到稳定窗口。
            // 目标应用窗口、系统权限窗口以及小灵自有 overlay 仍保留在守护集合中，真正的前台页面漂移继续 fail-closed。
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapTo(linkedSetOf()) { window ->
                DeviceAccessibilityWindowSnapshot(
                    id = window.id,
                    ownedApprovalOverlay = window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        window.title?.toString() == DEVICE_ACTION_APPROVAL_OVERLAY_TITLE,
                )
            }
    }

    private fun navigationBarInset(windowManager: WindowManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    .bottom
            }.getOrDefault(0)
        }
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return resourceId.takeIf { it != 0 }?.let(resources::getDimensionPixelSize) ?: 0
    }

    private fun removeApprovalView(token: Long) {
        val active = activeApprovalOverlay?.takeIf { it.token == token } ?: return
        if (!active.viewAttached) return
        runCatching {
            active.windowManager.removeViewImmediate(active.view)
        }.onSuccess {
            active.viewAttached = false
        }
    }

    private fun scheduleOverlayDetachTimeout(token: Long) {
        val active = activeApprovalOverlay?.takeIf { it.token == token } ?: return
        active.detachTimeout?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            val current = activeApprovalOverlay?.takeIf { it.token == token } ?: return@Runnable
            val decision = approvalCoordinator.detachTimedOut(token)
                ?: overlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE,
                    "无法确认审批浮层已安全移除",
                )
            completeApproval(current.token, decision)
        }
        active.detachTimeout = timeout
        mainHandler.postDelayed(timeout, OVERLAY_DETACH_TIMEOUT_MS)
    }

    private fun cancelApproval(token: Long) {
        val active = activeApprovalOverlay?.takeIf { it.token == token } ?: return
        approvalCoordinator.cancel(token)
        removeApprovalView(token)
        clearApproval(active)
    }

    private fun disconnectApprovalOverlay() {
        val active = activeApprovalOverlay ?: return
        val decision = approvalCoordinator.disconnect()
            ?: overlayDecision(
                DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED,
                "无障碍服务已断开，设备动作审批已取消",
            )
        removeApprovalView(active.token)
        completeApproval(active.token, decision)
    }

    private fun completeApproval(token: Long, decision: DeviceActionApprovalOverlayDecision) {
        val active = activeApprovalOverlay?.takeIf { it.token == token } ?: return
        clearApproval(active)
        if (active.continuation.isActive) active.continuation.resume(decision)
    }

    private fun clearApproval(active: ActiveApprovalOverlay) {
        active.detachTimeout?.let(mainHandler::removeCallbacks)
        active.settleConfirmation?.let(mainHandler::removeCallbacks)
        activeApprovalOverlay = null
    }

    private fun overlayDecision(
        kind: DeviceActionApprovalOverlayDecisionKind,
        reason: String,
    ) = DeviceActionApprovalOverlayDecision(kind, reason)

    internal fun performReferencedNodeAction(
        expectedWindowGeneration: Long,
        nodePath: List<Int>,
        expectedFingerprint: String,
        action: DeviceNodeAction,
        text: String?,
        direction: DeviceScrollDirection?,
    ): RawDeviceActionResult {
        var node = rootInActiveWindow ?: return RawDeviceActionResult.NodeNotFound
        if (DeviceAccessibilityRuntime.generationForWindow(node.windowId) != expectedWindowGeneration) {
            return RawDeviceActionResult.WindowChanged
        }
        nodePath.forEach { index ->
            node = node.getChild(index) ?: return RawDeviceActionResult.NodeNotFound
        }
        val bounds = Rect().also(node::getBoundsInScreen).let { DeviceBounds(it.left, it.top, it.right, it.bottom) }
        val fingerprint = DeviceNodeFingerprint.compute(
            className = node.className?.toString().orEmpty(),
            bounds = bounds,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hintText = node.hintText?.toString(),
        )
        if (fingerprint != expectedFingerprint) return RawDeviceActionResult.NodeChanged
        if (!node.isEnabled || node.isPassword) return RawDeviceActionResult.ActionNotSupported

        val performed = when (action) {
            DeviceNodeAction.TAP -> {
                if (!node.supportsAction(AccessibilityNodeInfo.ACTION_CLICK)) return RawDeviceActionResult.ActionNotSupported
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            DeviceNodeAction.TYPE_TEXT -> {
                val editTextRole = node.className?.toString().orEmpty().substringAfterLast('.').equals("EditText", ignoreCase = true)
                if ((!node.supportsAction(AccessibilityNodeInfo.ACTION_SET_TEXT) && !node.isEditable && !editTextRole) || text == null) {
                    return RawDeviceActionResult.ActionNotSupported
                }
                if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            DeviceNodeAction.SWIPE -> {
                if (direction == null) return RawDeviceActionResult.ActionNotSupported
                val scrollAction = when (direction) {
                    DeviceScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
                    DeviceScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                    DeviceScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    DeviceScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                }
                if (!node.supportsAction(scrollAction)) return RawDeviceActionResult.ActionNotSupported
                node.performAction(scrollAction)
            }
        }
        val result = if (performed) RawDeviceActionResult.Performed else RawDeviceActionResult.Failed
        return result
    }

    private fun AccessibilityNodeInfo.toRawNode(
        path: List<Int>,
        budget: RawCaptureBudget,
    ): RawDeviceNode? {
        if (budget.count >= MAX_RAW_NODES || path.size > MAX_RAW_DEPTH) {
            budget.truncated = true
            return null
        }
        budget.count += 1
        val rect = Rect()
        getBoundsInScreen(rect)
        val children = buildList {
            for (index in 0 until childCount) {
                val child = getChild(index) ?: continue
                child.toRawNode(path + index, budget)?.let(::add)
                if (budget.count >= MAX_RAW_NODES) {
                    budget.truncated = true
                    break
                }
            }
        }
        val nodeClassName = className?.toString().orEmpty()
        return RawDeviceNode(
            className = nodeClassName,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            hintText = hintText?.toString(),
            bounds = DeviceBounds(rect.left, rect.top, rect.right, rect.bottom),
            visibleToUser = isVisibleToUser,
            enabled = isEnabled,
            password = isPassword,
            clickable = isClickable || supportsAction(AccessibilityNodeInfo.ACTION_CLICK),
            editable = isEditable ||
                supportsAction(AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                nodeClassName.substringAfterLast('.').equals("EditText", ignoreCase = true),
            scrollable = isScrollable || actionList.any { action ->
                action.id in setOf(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                )
            },
            checkable = isCheckable,
            checked = isChecked,
            selected = isSelected,
            nodePath = path,
            children = children,
        )
    }

    private class RawCaptureBudget(
        var count: Int = 0,
        var truncated: Boolean = false,
    )

    private fun AccessibilityNodeInfo.supportsAction(actionId: Int): Boolean {
        return actionList.any { action -> action.id == actionId }
    }

    companion object {
        private const val MAX_RAW_NODES = 1_000
        private const val MAX_RAW_DEPTH = 30
        private const val UNKNOWN_WINDOW_ID = -1
        private const val OVERLAY_DETACH_TIMEOUT_MS = 1_500L
        private const val OVERLAY_SETTLE_DELAY_MS = 100L
        private const val APPROVAL_BASELINE_SAMPLE_DELAY_MS = 100L
        private const val APPROVAL_BASELINE_MAX_SAMPLES = 4
    }

    private data class ActiveApprovalOverlay(
        val token: Long,
        val windowManager: WindowManager,
        val view: View,
        val continuation: CancellableContinuation<DeviceActionApprovalOverlayDecision>,
        val autoApprove: Boolean,
        var viewAttached: Boolean = true,
        var pendingCompletion: DeviceActionApprovalOverlayDecision? = null,
        var detachTimeout: Runnable? = null,
        var settleConfirmation: Runnable? = null,
    )
}

internal object DeviceAccessibilityReferenceInvalidationPolicy {
    fun invalidates(eventType: Int): Boolean = eventType in INVALIDATING_EVENT_TYPES

    private val INVALIDATING_EVENT_TYPES = setOf(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
    )
}

internal class DeviceWindowGenerationTracker {
    private val sequence = AtomicLong(0L)
    private val generations = mutableMapOf<Int, Long>()
    private var activeWindowId: Int = UNKNOWN_WINDOW_ID

    @Synchronized
    fun reset() {
        generations.clear()
        activeWindowId = UNKNOWN_WINDOW_ID
        // long: 服务重连后所有旧 ref 都必须失效；推进序列可确保新窗口第一次分配的 generation 不会复用旧进程值。
        sequence.incrementAndGet()
    }

    @Synchronized
    fun onAccessibilityEvent(
        windowId: Int,
        eventType: Int,
        activeRootWindowId: Int? = null,
        suppressInvalidation: Boolean = false,
    ) {
        if (activeRootWindowId != null && activeRootWindowId >= 0) {
            // long: Accessibility overlay 会发送自己的 WINDOW_STATE_CHANGED，但 rootInActiveWindow 仍指向底层业务窗口；generation 必须跟随真实活动根窗口。
            activeWindowId = activeRootWindowId
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && windowId >= 0) {
            activeWindowId = windowId
        }
        if (!DeviceAccessibilityReferenceInvalidationPolicy.invalidates(eventType)) return
        if (suppressInvalidation) return
        val targetWindowId = windowId.takeIf { it >= 0 }
            ?: activeWindowId.takeIf { it >= 0 }
            ?: return
        val nextGeneration = sequence.incrementAndGet()
        generations[targetWindowId] = nextGeneration
    }

    @Synchronized
    fun invalidateActiveWindow() {
        val targetWindowId = activeWindowId.takeIf { it >= 0 } ?: return
        generations[targetWindowId] = sequence.incrementAndGet()
    }

    @Synchronized
    fun markCapturedWindow(windowId: Int): Long {
        require(windowId >= 0) { "Accessibility windowId 不能为空" }
        activeWindowId = windowId
        return generationForWindowLocked(windowId)
    }

    @Synchronized
    fun generationForWindow(windowId: Int): Long {
        require(windowId >= 0) { "Accessibility windowId 不能为空" }
        return generationForWindowLocked(windowId)
    }

    @Synchronized
    fun currentGeneration(): Long {
        return activeWindowId.takeIf { it >= 0 }
            ?.let(::generationForWindowLocked)
            ?: sequence.get()
    }

    private fun generationForWindowLocked(windowId: Int): Long {
        return generations.getOrPut(windowId) { sequence.incrementAndGet() }
    }

    private companion object {
        const val UNKNOWN_WINDOW_ID = -1
    }
}

internal object DeviceAccessibilityRuntime : DeviceActionApprovalOverlayRequester {
    private val generationTracker = DeviceWindowGenerationTracker()

    @Volatile
    private var service: XiaoLingAccessibilityService? = null

    fun attach(instance: XiaoLingAccessibilityService) {
        service = instance
        generationTracker.reset()
    }

    fun detach(instance: XiaoLingAccessibilityService) {
        if (service === instance) service = null
        generationTracker.reset()
    }

    fun onWindowChanged() {
        generationTracker.invalidateActiveWindow()
    }

    fun onAccessibilityEvent(
        windowId: Int,
        eventType: Int,
        activeRootWindowId: Int? = null,
        suppressInvalidation: Boolean = false,
    ) {
        generationTracker.onAccessibilityEvent(
            windowId = windowId,
            eventType = eventType,
            activeRootWindowId = activeRootWindowId,
            suppressInvalidation = suppressInvalidation,
        )
    }

    fun isConnected(): Boolean = service != null

    fun currentGeneration(): Long = generationTracker.currentGeneration()

    fun markCapturedWindow(windowId: Int): Long = generationTracker.markCapturedWindow(windowId)

    fun generationForWindow(windowId: Int): Long = generationTracker.generationForWindow(windowId)

    fun captureRawWindow(): RawDeviceWindow? {
        val currentService = service ?: return null
        return currentService.captureRawWindow()
    }

    fun performGlobalAction(action: DeviceGlobalAction): Boolean {
        val currentService = service ?: return false
        return currentService.performGlobalDeviceAction(action)
    }

    override suspend fun request(
        request: DeviceActionApprovalOverlayRequest,
    ): DeviceActionApprovalOverlayDecision {
        val currentService = service
            ?: return DeviceActionApprovalOverlayDecision(
                DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED,
                "无障碍服务尚未连接，设备动作审批已取消",
            )
        return currentService.requestApprovalOverlay(request)
    }

    fun performNodeAction(
        expectedWindowGeneration: Long,
        nodePath: List<Int>,
        expectedFingerprint: String,
        action: DeviceNodeAction,
        text: String?,
        direction: DeviceScrollDirection?,
    ): RawDeviceActionResult {
        val currentService = service ?: return RawDeviceActionResult.Failed
        return currentService.performReferencedNodeAction(
            expectedWindowGeneration = expectedWindowGeneration,
            nodePath = nodePath,
            expectedFingerprint = expectedFingerprint,
            action = action,
            text = text,
            direction = direction,
        )
    }
}
