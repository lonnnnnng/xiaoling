package com.longdev.xiaoling.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        // long: 只采集用户真实冷启动会触发的路径；不主动打开设置、Agent 或 Workflow 页，避免把非启动交互扩大到启动 DEX。
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val PACKAGE_NAME = "com.longdev.xiaoling"
    }
}
