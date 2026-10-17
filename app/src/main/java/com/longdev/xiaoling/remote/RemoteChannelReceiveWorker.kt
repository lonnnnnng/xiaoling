package com.longdev.xiaoling.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.agent.RemoteChannelBackgroundEntry
import com.longdev.xiaoling.agent.RemoteChannelBackgroundRegistry
import com.longdev.xiaoling.agent.RemoteChannelBackgroundScheduler
import com.longdev.xiaoling.agent.RemoteChannelBackgroundSynchronizer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkManagerRemoteChannelScheduler(context: Context, private val manager: WorkManager = WorkManager.getInstance(context.applicationContext)) : RemoteChannelBackgroundScheduler {
    override suspend fun schedule(entry: RemoteChannelBackgroundEntry) = withContext(Dispatchers.IO) {
        val request = PeriodicWorkRequestBuilder<RemoteChannelReceiveWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(RemoteChannelReceiveWorker.IDENTITY to entry.sourceIdentity, RemoteChannelReceiveWorker.GENERATION to entry.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag(entry.generation).build()
        // long: 同一公钥来源只保留一个周期任务；重新启用换代并替换旧任务，旧输入不能继续获得后台许可。
        manager.enqueueUniquePeriodicWork(workName(entry.sourceIdentity), ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request).result.get()
        Unit
    }

    override suspend fun ensureScheduled(entry: RemoteChannelBackgroundEntry) = withContext(Dispatchers.IO) {
        val active = manager.getWorkInfosForUniqueWork(workName(entry.sourceIdentity)).get()
            .any { !it.state.isFinished && entry.generation in it.tags }
        if (!active) schedule(entry)
    }

    override suspend fun cancel(identity: String) = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork(workName(identity)).result.get()
        Unit
    }

    companion object {
        const val TAG = "xiaoling-remote-channel-receive"
        fun workName(identity: String) = "$TAG-${RemoteChannelBackgroundRegistry.digest(identity)}"
    }
}

/** long: 后台入口不创建 ViewModel、模型请求或 Agent Run；停止 Worker 时协程取消直接传到有界 HTTPS 读取。 */
class RemoteChannelReceiveWorker @JvmOverloads constructor(
    context: Context,
    parameters: WorkerParameters,
    private val synchronizer: RemoteChannelBackgroundSynchronizer? = null,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val identity = inputData.getString(IDENTITY) ?: return Result.failure()
        val generation = inputData.getString(GENERATION) ?: return Result.failure()
        return try {
            val runner = synchronizer ?: (applicationContext as XiaoLingApplication).remoteChannelBackgroundSynchronizer
            val outcome = runner.run(identity, generation)
            if (RemoteChannelBackgroundSynchronizer.shouldRetry(outcome, runAttemptCount)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // long: 许可或存储损坏属于人工修复问题，不能把它伪装成网络故障而持续重试。
            Result.failure()
        }
    }

    companion object {
        const val IDENTITY = "remoteSourceIdentity"
        const val GENERATION = "remoteBackgroundGeneration"
    }
}
