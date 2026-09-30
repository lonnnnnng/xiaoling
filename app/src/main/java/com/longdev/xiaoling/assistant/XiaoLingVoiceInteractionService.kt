package com.longdev.xiaoling.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import com.longdev.xiaoling.MainActivity

/**
 * 系统数字助理的最小入口。
 *
 * long: 系统会话只负责承接系统助手触发并打开现有对话页；本切片不读取屏幕内容、不自动录音，也不绕过用户点击发送消息。
 */
class XiaoLingVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), 0)
    }

    companion object {
        @Volatile
        private var activeService: XiaoLingVoiceInteractionService? = null
    }
}

class XiaoLingVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession =
        XiaoLingVoiceInteractionSession(this)
}

private class XiaoLingVoiceInteractionSession(
    context: android.content.Context,
) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        // long: 不复制系统会话窗口，避免出现一个没有输入能力的空浮层；实际交互仍由 Compose 对话页承载。
        setUiEnabled(false)
    }

    override fun onCreateContentView(): View = View(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        hide()
    }

    override fun onBackPressed() {
        hide()
    }
}
