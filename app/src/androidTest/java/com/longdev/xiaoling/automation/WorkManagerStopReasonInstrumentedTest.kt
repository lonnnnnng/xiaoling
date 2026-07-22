package com.longdev.xiaoling.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerStopReasonInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun cancellingRunningWorkReportsStableCancelledByAppReason() = runBlocking {
        val preferenceName = "$PROBE_PREFERENCES_PREFIX-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<WorkManagerStopReasonProbeWorker>()
            .setInputData(workDataOf(KEY_PROBE_PREFERENCES_NAME to preferenceName))
            .build()

        try {
            workManager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            withTimeout(15_000) {
                while (!preferences.getBoolean(KEY_STARTED, false)) delay(50)
            }

            workManager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
            withTimeout(15_000) {
                while (!preferences.contains(KEY_STOP_REASON_CODE)) delay(50)
            }

            val code = preferences.getInt(KEY_STOP_REASON_CODE, Int.MIN_VALUE)
            assertEquals(1, code)
            assertEquals("CANCELLED_BY_APP", preferences.getString(KEY_STOP_REASON_NAME, null))
            assertTrue(preferences.getBoolean(KEY_CANCELLED, false))
        } finally {
            runCatching { workManager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS) }
            val started = preferences.getBoolean(KEY_STARTED, false)
            val cancellationRecorded = !started || withTimeoutOrNull(5_000) {
                while (!preferences.getBoolean(KEY_CANCELLED, false)) delay(50)
                true
            } == true
            // long: 每个 WorkRequest 使用独立偏好文件；只有取消回调已经落盘时才删除，超时则保留孤立诊断文件，避免迟到 Worker 重新污染后续测试。
            if (cancellationRecorded) context.deleteSharedPreferences(preferenceName)
        }
    }
}

class WorkManagerStopReasonProbeWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val preferenceName = requireNotNull(inputData.getString(KEY_PROBE_PREFERENCES_NAME)) {
            "停止原因探针缺少隔离存储名称"
        }
        val preferences = applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(KEY_STARTED, true).commit()
        return try {
            awaitCancellation()
        } catch (error: CancellationException) {
            // long: 真实 WorkManager 必须先写入停止码再取消 Worker 协程；这里同步落盘，验证生产 Worker 的取消出口读取到的是平台事实而不是测试注入值。
            val reason = ScheduledWorkerStopReasonPolicy.fromWorkManagerCode(stopReason)
            preferences.edit()
                .putInt(KEY_STOP_REASON_CODE, stopReason)
                .putString(KEY_STOP_REASON_NAME, reason?.name)
                .putBoolean(KEY_CANCELLED, true)
                .commit()
            throw error
        }
    }
}

private const val PROBE_PREFERENCES_PREFIX = "work_manager_stop_reason_probe"
private const val KEY_PROBE_PREFERENCES_NAME = "probe_preferences_name"
private const val KEY_STARTED = "started"
private const val KEY_STOP_REASON_CODE = "stop_reason_code"
private const val KEY_STOP_REASON_NAME = "stop_reason_name"
private const val KEY_CANCELLED = "cancelled"
