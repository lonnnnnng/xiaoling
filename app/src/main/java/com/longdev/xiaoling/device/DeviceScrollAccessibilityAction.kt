package com.longdev.xiaoling.device

import android.view.accessibility.AccessibilityNodeInfo

internal fun DeviceScrollDirection.accessibilityScrollActionId(): Int = when (this) {
    DeviceScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
    DeviceScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
    // long: swipe 方向指可见内容的屏幕位移；Android 左滚减小 scrollX 会让内容右移，因此横向动作必须反向映射。
    DeviceScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
    DeviceScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
}
