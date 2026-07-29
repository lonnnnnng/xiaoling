package com.longdev.xiaoling.ui

/**
 * long: Shadow 显式窗口的检查与关闭必须处在同一临界区，避免多个答案并发完成时共享同一份用户授权。
 */
internal class AnswerabilityShadowObservationWindowGate {
    private val lock = Any()

    fun tryConsume(
        isEnabled: () -> Boolean,
        consume: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!isEnabled()) return@synchronized false
        consume()
        true
    }
}
