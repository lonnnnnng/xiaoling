package com.longdev.xiaoling

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.longdev.xiaoling.automation.ScheduledTaskResultNavigationIntent
import com.longdev.xiaoling.automation.ScheduledTaskResultNavigationTokenStore
import com.longdev.xiaoling.share.AndroidShareIntentReader
import com.longdev.xiaoling.share.SharedDraftImport
import com.longdev.xiaoling.ui.XiaoLingApp
import com.longdev.xiaoling.ui.XiaoLingViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: XiaoLingViewModel by viewModels()
    private val scheduledTaskNavigationStore by lazy {
        ScheduledTaskResultNavigationTokenStore(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // long: 系统重建会复用原始分享 Intent，因此分享只在首次创建导入；通知点击即使恢复了旧 Activity 也必须重新校验一次性令牌。
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        handleScheduledTaskResultIntent(intent)
        setContent {
            // long: Android 已提供系统启动画面，首帧直接进入应用，避免重复品牌页额外阻塞启动流程。
            XiaoLingApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        handleShareIntent(intent)
        handleScheduledTaskResultIntent(intent)
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
