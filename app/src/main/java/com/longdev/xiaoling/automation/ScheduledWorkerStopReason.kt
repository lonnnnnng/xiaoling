package com.longdev.xiaoling.automation

/**
 * long: WorkManager 把 Android JobScheduler 的停止码原样传给 Worker；只保存稳定码和分类名，避免把
 * 系统日志、任务参数或用户内容带入长期执行记录。
 */
internal data class ScheduledWorkerStopReason(
    val code: Int,
    val name: String,
    val message: String,
)

internal object ScheduledWorkerStopReasonPolicy {
    private const val STOP_REASON_NOT_STOPPED = -256
    private const val STOP_REASON_UNKNOWN = -512
    private const val STOP_REASON_FOREGROUND_SERVICE_TIMEOUT = -128

    fun fromWorkManagerCode(code: Int): ScheduledWorkerStopReason? {
        return when (code) {
            // long: 这个值表示 Worker 尚未进入停止流程，不能在取消结算里伪造一条系统停止证据。
            STOP_REASON_NOT_STOPPED -> null
            1 -> reason(code, "CANCELLED_BY_APP", "后台工作流已由应用停止")
            2 -> reason(code, "PREEMPT", "后台工作流被系统抢占")
            3 -> reason(code, "TIMEOUT", "后台工作流超过系统允许的运行时长")
            4 -> reason(code, "DEVICE_STATE", "设备状态变化导致后台工作流停止")
            5 -> reason(code, "CONSTRAINT_BATTERY_NOT_LOW", "电量条件变化导致后台工作流停止")
            6 -> reason(code, "CONSTRAINT_CHARGING", "充电条件变化导致后台工作流停止")
            7 -> reason(code, "CONSTRAINT_CONNECTIVITY", "网络条件变化导致后台工作流停止")
            8 -> reason(code, "CONSTRAINT_DEVICE_IDLE", "设备空闲条件变化导致后台工作流停止")
            9 -> reason(code, "CONSTRAINT_STORAGE_NOT_LOW", "存储空间条件变化导致后台工作流停止")
            10 -> reason(code, "QUOTA", "系统后台配额限制停止了本次工作流")
            11 -> reason(code, "BACKGROUND_RESTRICTION", "系统后台限制停止了本次工作流")
            12 -> reason(code, "APP_STANDBY", "应用待机策略停止了本次后台工作流")
            13 -> reason(code, "USER", "用户停止了本次后台工作流")
            14 -> reason(code, "SYSTEM_PROCESSING", "系统后台处理策略停止了本次工作流")
            15 -> reason(code, "ESTIMATED_APP_LAUNCH_TIME_CHANGED", "系统调整应用启动时机后停止了本次工作流")
            16 -> reason(code, "TIMEOUT_ABANDONED", "后台工作流超时且已被系统放弃")
            STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> reason(code, "FOREGROUND_SERVICE_TIMEOUT", "前台服务超时停止了本次工作流")
            STOP_REASON_UNKNOWN -> reason(code, "UNKNOWN", "系统停止了本次后台工作流，但未提供明确原因")
            else -> reason(code, "UNRECOGNIZED", "系统停止了本次后台工作流，停止原因未被当前版本识别")
        }
    }

    private fun reason(code: Int, name: String, message: String) = ScheduledWorkerStopReason(
        code = code,
        name = name,
        message = message,
    )
}
