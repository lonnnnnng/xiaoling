package com.longdev.xiaoling

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.longdev.xiaoling.share.AndroidShareIntentReader
import com.longdev.xiaoling.share.SharedDraftImport
import com.longdev.xiaoling.ui.XiaoLingApp
import com.longdev.xiaoling.ui.XiaoLingViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: XiaoLingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // long: 系统重建会复用原始 Intent；只有首次创建才导入，且不信任外部应用可写入的 extra 作为去重凭据。
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        setContent {
            // long: Android 已提供系统启动画面，首帧直接进入应用，避免重复品牌页额外阻塞启动流程。
            XiaoLingApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        val result = AndroidShareIntentReader.read(intent)
        if (result is SharedDraftImport.Ignored) return
        viewModel.acceptSharedDraft(result)
    }
}
