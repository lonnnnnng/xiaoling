package com.longdev.xiaoling.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.longdev.xiaoling.agent.MemoryMaintenancePolicy
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomMemoryMaintenanceStore
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.TimeUnit

class MemoryMaintenanceWorker @JvmOverloads constructor(
    context: Context,
    parameters: WorkerParameters,
    private val store: RoomMemoryMaintenanceStore = RoomMemoryMaintenanceStore(XiaoLingDatabase.getInstance(context)),
    private val enabled: () -> Boolean = { UiPreferenceStore(context).loadMemoryCandidatesEnabled() },
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(REQUEST_ID) ?: return Result.failure()
        if (runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not()) return Result.failure()
        try {
            withTimeout(MemoryMaintenancePolicy.EXECUTION_TIMEOUT_MILLIS) {
                // long: 冷启动只访问本地 Room 和候选开关；不构造 Runtime、Provider、MCP 或设备执行器。
                repeat(MemoryMaintenancePolicy.MAX_RUNS * 3) {
                    if (!enabled()) {
                        store.cancel(requestId)
                        return@withTimeout
                    }
                    if (store.deliverOne(requestId)) return@repeat
                    if (store.scanOne(requestId)) return@repeat
                    store.complete(requestId)
                    return@withTimeout
                }
                error("整理单次操作预算已耗尽")
            }
            return Result.success()
        } catch (timeout: TimeoutCancellationException) {
            // long: 只处理本 Worker 的有界超时；上层取消同样使用超时异常时，必须继续传播系统取消。
            currentCoroutineContext().ensureActive()
            if (runAttemptCount < 2) return Result.retry()
            store.fail(requestId)
            return Result.failure()
        } catch (cancellation: CancellationException) {
            // long: 系统取消保留已提交的游标/outbox，后续显式请求或同 WorkRequest 重入可继续；不把取消伪装成业务失败。
            throw cancellation
        } catch (error: Exception) {
            // long: 重试只重读已持久化前缀，不重写候选决议；限次后保留 FAILED 供用户显式重试。
            if (runAttemptCount < 2) return Result.retry()
            store.fail(requestId)
            return Result.failure()
        }
    }

    companion object { const val REQUEST_ID = "memory-maintenance-request" }
}

class MemoryMaintenanceScheduler(private val context: Context) {
    suspend fun enqueue(requestId: String): String {
        require(UUID.fromString(requestId).toString() == requestId)
        val manager = WorkManager.getInstance(context.applicationContext)
        val work = OneTimeWorkRequestBuilder<MemoryMaintenanceWorker>()
            .setInputData(workDataOf(MemoryMaintenanceWorker.REQUEST_ID to requestId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(uniqueName(requestId), ExistingWorkPolicy.KEEP, work).result.get()
        return requireNotNull(manager.getWorkInfosForUniqueWork(uniqueName(requestId)).get()
            .lastOrNull()?.id).toString()
    }

    fun cancel(requestId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(requestId)).result.get()
    }

    companion object { fun uniqueName(requestId: String) = "xiaoling-memory-maintenance-$requestId" }
}
