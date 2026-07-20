package com.longdev.xiaoling.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

class XiaoLingAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceAccessibilityRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // long: ref 必须绑定一次稳定观察；任意窗口内容事件都会推进 generation，后续动作不能沿用页面变化前的节点路径。
        DeviceAccessibilityRuntime.onWindowChanged()
    }

    override fun onInterrupt() {
        DeviceAccessibilityRuntime.onWindowChanged()
    }

    override fun onDestroy() {
        DeviceAccessibilityRuntime.detach(this)
        super.onDestroy()
    }

    internal fun captureRawWindow(generation: Long): RawDeviceWindow? {
        val root = rootInActiveWindow ?: return null
        val budget = RawCaptureBudget()
        val rootNode = root.toRawNode(emptyList(), budget) ?: return null
        val title = windows.firstOrNull { it.id == root.windowId }?.title?.toString()
        return RawDeviceWindow(
            packageName = root.packageName?.toString().orEmpty(),
            windowTitle = title,
            windowId = root.windowId,
            generation = generation,
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

    internal fun performReferencedNodeAction(
        expectedWindowGeneration: Long,
        nodePath: List<Int>,
        expectedFingerprint: String,
        action: DeviceNodeAction,
        text: String?,
        direction: DeviceScrollDirection?,
    ): RawDeviceActionResult {
        if (DeviceAccessibilityRuntime.currentGeneration() != expectedWindowGeneration) {
            return RawDeviceActionResult.WindowChanged
        }
        var node = rootInActiveWindow ?: return RawDeviceActionResult.NodeNotFound
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
        return if (performed) RawDeviceActionResult.Performed else RawDeviceActionResult.Failed
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
    }
}

internal object DeviceAccessibilityRuntime {
    private val generation = AtomicLong(0L)

    @Volatile
    private var service: XiaoLingAccessibilityService? = null

    fun attach(instance: XiaoLingAccessibilityService) {
        service = instance
        generation.incrementAndGet()
    }

    fun detach(instance: XiaoLingAccessibilityService) {
        if (service === instance) service = null
        generation.incrementAndGet()
    }

    fun onWindowChanged() {
        generation.incrementAndGet()
    }

    fun isConnected(): Boolean = service != null

    fun currentGeneration(): Long = generation.get()

    fun captureRawWindow(): RawDeviceWindow? {
        val currentService = service ?: return null
        return currentService.captureRawWindow(generation.get())
    }

    fun performGlobalAction(action: DeviceGlobalAction): Boolean {
        val currentService = service ?: return false
        return currentService.performGlobalDeviceAction(action)
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
