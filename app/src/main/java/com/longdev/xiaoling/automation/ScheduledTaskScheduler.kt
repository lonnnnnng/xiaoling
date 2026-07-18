package com.longdev.xiaoling.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

interface ScheduledTaskScheduler {
    suspend fun enqueue(task: ScheduledTaskRecord): String
    suspend fun cancel(taskId: String)
}

class WorkManagerScheduledTaskScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) : ScheduledTaskScheduler {
    override suspend fun enqueue(task: ScheduledTaskRecord): String {
        val delayMillis = (task.plannedAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledWorkflowWorker>()
            .setInputData(workDataOf(ScheduledWorkflowWorker.INPUT_TASK_ID to task.id))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(taskTag(task.id))
            .addTag(WORKFLOW_SCHEDULE_TAG)
            .build()
        // long: 一个 ScheduledTask 只允许存在一个系统工作项；KEEP 配合 Room 状态检查，防止页面重复点击或进程重建导致同一计划执行两次。
        workManager.enqueueUniqueWork(uniqueWorkName(task.id), ExistingWorkPolicy.KEEP, request).result.get()
        return request.id.toString()
    }

    override suspend fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(taskId)).result.get()
    }

    companion object {
        const val WORKFLOW_SCHEDULE_TAG = "xiaoling-workflow-schedule"

        fun uniqueWorkName(taskId: String): String = "xiaoling-scheduled-task-$taskId"

        private fun taskTag(taskId: String): String = "xiaoling-scheduled-task-tag-$taskId"
    }
}
