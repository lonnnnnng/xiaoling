package com.longdev.xiaoling

import android.app.Application
import androidx.work.Configuration
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullSourceStateStore

class XiaoLingApplication : Application(), Configuration.Provider {
    /**
     * 应用级注册表让插件启停状态跨 Activity 和进程重建保持一致。
     *
     * long: 注册表仍只加载声明，不执行外部代码；损坏快照会 fail-closed 成空能力面。
     */
    val agentPluginRegistry: AgentPluginRegistry by lazy {
        AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(this))
    }

    /** long: 配对账本由应用进程统一持有，验签时仍重新读取磁盘，确保停用和撤销即时生效。 */
    val remoteChannelPairingRegistry: RemoteChannelPairingRegistry by lazy {
        RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(this))
    }

    /** long: 手动签名消息与恢复列表共用一份加密去重快照；验签器逐次检查当前配对状态。 */
    val remoteChannelInboxGateway: RemoteChannelInboxGateway by lazy {
        RemoteChannelInboxGateway(
            KeystoreRemoteChannelInboxStateStore(this),
            remoteChannelPairingRegistry,
        )
    }

    /** long: HTTPS 来源固定在已核对公钥身份下，网络拉取结果仍只进入现有加密待确认箱。 */
    val remoteChannelPullSourceRegistry: RemoteChannelPullSourceRegistry by lazy {
        RemoteChannelPullSourceRegistry(SharedPreferencesRemoteChannelPullSourceStateStore(this), remoteChannelPairingRegistry)
    }

    val remoteChannelPullService: RemoteChannelPullService by lazy {
        RemoteChannelPullService(remoteChannelPairingRegistry, remoteChannelPullSourceRegistry, remoteChannelInboxGateway)
    }

    override val workManagerConfiguration: Configuration
        // long: WorkManager 自动初始化已从进程启动阶段移除；保留默认配置，让现有定时任务在首次访问时按官方入口安全初始化。
        get() = Configuration.Builder().build()
}
