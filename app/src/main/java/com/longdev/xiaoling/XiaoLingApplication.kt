package com.longdev.xiaoling

import android.app.Application
import androidx.work.Configuration
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore

class XiaoLingApplication : Application(), Configuration.Provider {
    /**
     * 应用级注册表让插件启停状态跨 Activity 和进程重建保持一致。
     *
     * long: 注册表仍只加载声明，不执行外部代码；损坏快照会 fail-closed 成空能力面。
     */
    val agentPluginRegistry: AgentPluginRegistry by lazy {
        AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(this))
    }

    override val workManagerConfiguration: Configuration
        // long: WorkManager 自动初始化已从进程启动阶段移除；保留默认配置，让现有定时任务在首次访问时按官方入口安全初始化。
        get() = Configuration.Builder().build()
}
