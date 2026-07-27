package com.longdev.xiaoling

import android.app.Application
import androidx.work.Configuration

class XiaoLingApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        // long: WorkManager 自动初始化已从进程启动阶段移除；保留默认配置，让现有定时任务在首次访问时按官方入口安全初始化。
        get() = Configuration.Builder().build()
}
