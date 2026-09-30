package com.longdev.xiaoling.agent

/**
 * 插件注册表只依赖原子字符串快照，避免把 Android 存储实现带进 JVM 策略测试。
 *
 * long: 状态写入失败时调用方必须保留旧快照，不能先修改内存再让进程重启后出现分叉状态。
 */
interface AgentPluginStateStore {
    fun loadState(): String?

    fun saveState(state: String)
}
