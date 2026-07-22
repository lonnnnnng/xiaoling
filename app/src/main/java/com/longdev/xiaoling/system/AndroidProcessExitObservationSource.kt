package com.longdev.xiaoling.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build

class AndroidProcessExitObservationSource(
    context: Context,
) : ProcessExitObservationSource {
    private val applicationContext = context.applicationContext

    override fun read(): ProcessExitObservationBatch {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ProcessExitObservationBatch(
                apiSupported = false,
                lowMemoryReportSupported = false,
                exits = emptyList(),
            )
        }

        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val lowMemoryReportSupported = ActivityManager.isLowMemoryKillReportSupported()
        val exits = activityManager.getHistoricalProcessExitReasons(
            applicationContext.packageName,
            0,
            MAX_STORED_PROCESS_EXIT_OBSERVATIONS,
        ).map { exit ->
            // long: 退出观察只保留 Android 稳定数值字段；description、trace 和状态摘要可能包含不稳定或敏感内容，不进入长期审计。
            RawProcessExitObservation(
                timestamp = exit.timestamp,
                processName = exit.processName,
                pid = exit.pid,
                reasonCode = exit.reason,
                status = exit.status,
                importance = exit.importance,
                pssKb = exit.pss,
                rssKb = exit.rss,
            )
        }
        return ProcessExitObservationBatch(
            apiSupported = true,
            lowMemoryReportSupported = lowMemoryReportSupported,
            exits = exits,
        )
    }
}
