package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.InstalledAppDirectoryReadResult
import com.longdev.xiaoling.device.DeviceAgentHealthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class PersonalTaskEnvironment(
    val directory: InstalledAppDirectoryReadResult? = null,
    val health: DeviceAgentHealthState? = null,
)

internal class PersonalTaskEnvironmentCoordinator(
    private val scope: CoroutineScope,
    private val readEnvironment: suspend () -> PersonalTaskEnvironment,
) {
    private var generation = 0L
    private var latestJob: Job? = null

    fun refresh(onResult: (PersonalTaskEnvironment) -> Unit): Job {
        val requestedGeneration = ++generation
        latestJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val environment = try {
                readEnvironment().also { currentCoroutineContext().ensureActive() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // long: 目录查询失败只表示当前无法确认能力，不能把异常正文或旧目录投影成新的可运行许可。
                PersonalTaskEnvironment(directory = InstalledAppDirectoryReadResult.Failed)
            }
            if (generation == requestedGeneration) onResult(environment)
        }
        latestJob = job
        // long: 菜单重新打开或从系统授权页返回时先清空旧诊断；底层查询即使不响应取消，也只有当前代次可以更新界面。
        onResult(PersonalTaskEnvironment())
        job.start()
        return job
    }

    fun invalidate(onResult: (PersonalTaskEnvironment) -> Unit) {
        generation += 1L
        latestJob?.cancel()
        latestJob = null
        onResult(PersonalTaskEnvironment())
    }
}
