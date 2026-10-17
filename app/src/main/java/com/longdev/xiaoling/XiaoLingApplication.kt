package com.longdev.xiaoling

import android.app.Application
import androidx.work.Configuration
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullCheckpointRegistry
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullSourceStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullCheckpointStateStore
import com.longdev.xiaoling.agent.RemoteChannelBackgroundRegistry
import com.longdev.xiaoling.agent.RemoteChannelBackgroundController
import com.longdev.xiaoling.agent.RemoteChannelBackgroundSynchronizer
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelBackgroundStateStore
import com.longdev.xiaoling.remote.AndroidRemoteChannelInboxNotifier
import com.longdev.xiaoling.remote.WorkManagerRemoteChannelScheduler

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
        RemoteChannelPullService(
            remoteChannelPairingRegistry,
            remoteChannelPullSourceRegistry,
            remoteChannelInboxGateway,
            checkpoints = remoteChannelPullCheckpointRegistry,
        )
    }

    /** long: 网络失败后的退避和待 ACK 身份跨进程重建保留；正文仍只在独立 Keystore 收件箱中保存。 */
    val remoteChannelPullCheckpointRegistry: RemoteChannelPullCheckpointRegistry by lazy {
        RemoteChannelPullCheckpointRegistry(SharedPreferencesRemoteChannelPullCheckpointStateStore(this))
    }

    val remoteChannelBackgroundRegistry by lazy { RemoteChannelBackgroundRegistry(SharedPreferencesRemoteChannelBackgroundStateStore(this)) }
    private val remoteChannelInboxNotifier by lazy { AndroidRemoteChannelInboxNotifier(this) }
    val remoteChannelBackgroundController by lazy {
        RemoteChannelBackgroundController(remoteChannelBackgroundRegistry, remoteChannelPairingRegistry, remoteChannelPullSourceRegistry,
            WorkManagerRemoteChannelScheduler(this), remoteChannelInboxNotifier)
    }
    val remoteChannelBackgroundSynchronizer by lazy {
        // long: Worker 只构建 Remote Channel 对象图，不加载 Agent/Room 执行运行时，也不触发模型 API。
        RemoteChannelBackgroundSynchronizer(remoteChannelBackgroundRegistry, remoteChannelPairingRegistry, remoteChannelPullSourceRegistry,
            remoteChannelInboxGateway, { peer, allowed -> remoteChannelPullService.synchronize(peer, allowed) }, remoteChannelInboxNotifier, remoteChannelPullCheckpointRegistry)
    }

    override val workManagerConfiguration: Configuration
        // long: WorkManager 自动初始化已从进程启动阶段移除；保留默认配置，让现有定时任务在首次访问时按官方入口安全初始化。
        get() = Configuration.Builder().build()
}
