package com.longdev.xiaoling

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.agent.AgentPluginForegroundState
import com.longdev.xiaoling.agent.AndroidMcpServerStore
import com.longdev.xiaoling.agent.McpSessionRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullCheckpointRegistry
import com.longdev.xiaoling.agent.AndroidKeystoreRemoteChannelReceiverSigner
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullSourceStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullCheckpointStateStore
import com.longdev.xiaoling.agent.RemoteChannelBackgroundRegistry
import com.longdev.xiaoling.agent.RemoteChannelBackgroundController
import com.longdev.xiaoling.agent.RemoteChannelBackgroundSynchronizer
import com.longdev.xiaoling.agent.RemoteChannelBackgroundStartupOutcome
import com.longdev.xiaoling.agent.RemoteChannelBackgroundStartupRecovery
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelBackgroundStateStore
import com.longdev.xiaoling.remote.AndroidRemoteChannelInboxNotifier
import com.longdev.xiaoling.remote.WorkManagerRemoteChannelScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class XiaoLingApplication : Application(), Configuration.Provider {
    /** long: 所有前台 Run 和设置入口共享配置撤销机制；应用启动只建注册表，不主动连接 MCP。 */
    val mcpSessionRegistry: McpSessionRegistry by lazy { McpSessionRegistry(AndroidMcpServerStore(this)) }
    val agentPluginForeground = AgentPluginForegroundState()
    private val remoteChannelRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var remoteChannelStartupRecovery: Deferred<RemoteChannelBackgroundStartupOutcome>
        private set

    override fun onCreate() {
        super.onCreate()
        // long: 许可落盘后、系统入队前退出的间隙不能依赖用户再次打开配对页；启动异步补同代任务，主线程不等待对账，也不恢复 Agent。
        remoteChannelStartupRecovery = remoteChannelRecoveryScope.async {
            RemoteChannelBackgroundStartupRecovery.reconcile({ remoteChannelBackgroundRegistry }) { remoteChannelBackgroundController }
                .also { outcome ->
                    // long: 日志只记录固定结果，不输出账本异常、来源地址或身份；失败后的原设置仍可从配对页修复。
                    if (outcome == RemoteChannelBackgroundStartupOutcome.UNAVAILABLE) Log.w("RemoteChannelRecovery", "后台收件启动对账未完成")
                }
        }
    }

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
            receiverIdentity = AndroidKeystoreRemoteChannelReceiverSigner(),
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
        // long: 自动初始化器继续移除；收件空账本不主动访问 WorkManager，已保存后台设置的异步对账才按需初始化。
        get() = Configuration.Builder().build()
}
