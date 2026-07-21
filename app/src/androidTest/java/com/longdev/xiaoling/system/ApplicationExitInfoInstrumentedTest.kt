package com.longdev.xiaoling.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.OsConstants
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationExitInfoInstrumentedTest {
    @Test
    fun reportsLowMemoryKillSupportAndHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "lmk-probe supported=false reason=api-below-30")
            return
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val lowMemoryReasonSupported = ActivityManager.isLowMemoryKillReportSupported()
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        val lowMemoryExits = exits.filter { it.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
        val fallbackSigkillCandidates = exits.filter {
            !lowMemoryReasonSupported &&
                it.reason == ApplicationExitInfo.REASON_SIGNALED &&
                it.status == OsConstants.SIGKILL
        }

        // long: 只有 reason=LOW_MEMORY 才是直接 LMK 证据；不支持原因报告时的 SIGKILL 仅保留为候选，不能覆盖本轮人工 force-stop 的已知事实。
        Log.i(
            TAG,
            "lmk-probe supported=$lowMemoryReasonSupported exits=${exits.size} " +
                "lowMemory=${lowMemoryExits.size} fallbackSigkillCandidates=${fallbackSigkillCandidates.size}",
        )
        exits.forEachIndexed { index, exit ->
            Log.i(
                TAG,
                "exit[$index] timestamp=${exit.timestamp} reason=${exit.reason} status=${exit.status} " +
                    "importance=${exit.importance} pss=${exit.pss} rss=${exit.rss} " +
                    "process=${exit.processName} description=${exit.description.orEmpty()}",
            )
        }

        assertTrue("历史退出数量不应超过请求上限", exits.size <= MAX_EXIT_REASONS)
    }

    private companion object {
        const val MAX_EXIT_REASONS = 30
        const val TAG = "XiaoLingLmkProbe"
    }
}
