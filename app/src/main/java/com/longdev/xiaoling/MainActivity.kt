package com.longdev.xiaoling

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.longdev.xiaoling.remote.RemoteChannelInboxNavigationIntent
import com.longdev.xiaoling.remote.RemoteChannelInboxNavigationStore
import com.longdev.xiaoling.automation.ScheduledTaskResultNavigationIntent
import com.longdev.xiaoling.automation.ScheduledTaskResultNavigationTokenStore
import com.longdev.xiaoling.share.AndroidShareIntentReader
import com.longdev.xiaoling.share.SharedDraftImport
import com.longdev.xiaoling.ui.XiaoLingApp
import com.longdev.xiaoling.ui.XiaoLingViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: XiaoLingViewModel by viewModels()
    private var remoteInboxNavigationRequest by mutableStateOf<String?>(null)
    private val scheduledTaskNavigationStore by lazy {
        ScheduledTaskResultNavigationTokenStore(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // long: Debug 生命周期验收只测真实 Activity 回调，不初始化正式 ViewModel；Release 始终进入正常界面。
        if (BuildConfig.DEBUG && intent.getBooleanExtra("com.longdev.xiaoling.DEBUG_PLUGIN_LIFECYCLE_PROBE", false)) return
        // long: 系统重建会复用原始分享 Intent，因此分享只在首次创建导入；通知点击即使恢复了旧 Activity 也必须重新校验一次性令牌。
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        handleScheduledTaskResultIntent(intent)
        handleRemoteInboxIntent(intent)
        setContent {
            // long: Android 已提供系统启动画面，首帧直接进入应用，避免重复品牌页额外阻塞启动流程。
            XiaoLingApp(viewModel, remoteInboxNavigationRequest)
        }
        if (savedInstanceState != null) {
            // long: 配置重建可能复用同一个 ViewModel；显式从 Room 重新绑定待审批 Run，避免旧内存状态掩盖持久化审批事实。
            viewModel.onActivityRecreated()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as XiaoLingApplication).agentPluginForeground.resumed(this)
    }

    override fun onPause() {
        // long: 插件许可要求实际 resumed；系统浮层、切后台或配置重建均撤销旧轮次，返回前台必须重新显式发起。
        (application as XiaoLingApplication).agentPluginForeground.paused(this)
        super.onPause()
    }

    override fun onDestroy() {
        (application as XiaoLingApplication).agentPluginForeground.paused(this)
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent) {
        handleShareIntent(intent)
        handleScheduledTaskResultIntent(intent)
        handleRemoteInboxIntent(intent)
    }

    private fun handleRemoteInboxIntent(intent: Intent) {
        val token = RemoteChannelInboxNavigationIntent.read(intent) ?: return
        if (RemoteChannelInboxNavigationStore(applicationContext).consume(token)) remoteInboxNavigationRequest = token
        intent.removeExtra(RemoteChannelInboxNavigationIntent.TOKEN)
    }

    private fun handleScheduledTaskResultIntent(intent: Intent) {
        val token = ScheduledTaskResultNavigationIntent.readToken(intent) ?: return
        val target = scheduledTaskNavigationStore.consume(token) ?: return
        viewModel.acceptScheduledTaskResultNavigation(target)
    }

    private fun handleShareIntent(intent: Intent) {
        val result = AndroidShareIntentReader.read(intent)
        if (result is SharedDraftImport.Ignored) return
        viewModel.acceptSharedDraft(result)
    }
}
