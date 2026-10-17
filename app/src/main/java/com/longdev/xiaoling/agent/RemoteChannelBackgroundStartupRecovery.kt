package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException

enum class RemoteChannelBackgroundStartupOutcome { NOT_CONFIGURED, RECONCILED, UNAVAILABLE }

/** long: 进程启动只修复已经持久授权的系统任务，不创建许可、读取正文或直接执行同步。 */
object RemoteChannelBackgroundStartupRecovery {
    suspend fun reconcile(
        registry: () -> RemoteChannelBackgroundRegistry,
        controller: () -> RemoteChannelBackgroundController,
    ): RemoteChannelBackgroundStartupOutcome = try {
        // long: 没有后台设置时不构建调度器，保留普通冷启动的 WorkManager 按需初始化；停用记录仍需取消遗留任务。
        if (registry().list().isEmpty()) RemoteChannelBackgroundStartupOutcome.NOT_CONFIGURED
        else {
            controller().reconcile()
            RemoteChannelBackgroundStartupOutcome.RECONCILED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // long: 损坏或调度失败不能阻断应用启动，也不能清空原许可；配对页保留显式重试对账入口。
        RemoteChannelBackgroundStartupOutcome.UNAVAILABLE
    }
}
