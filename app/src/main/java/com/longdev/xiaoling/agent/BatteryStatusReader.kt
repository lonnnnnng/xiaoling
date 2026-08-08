package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BatteryStatusRecord(
    val levelPercent: Int,
    val charging: Boolean,
    val powerSource: BatteryPowerSource,
)

enum class BatteryPowerSource {
    NONE,
    AC,
    USB,
    WIRELESS,
    DOCK,
    UNKNOWN,
}

sealed interface BatteryStatusReadResult {
    data class Success(val status: BatteryStatusRecord) : BatteryStatusReadResult
    data object Unavailable : BatteryStatusReadResult
    data object Failed : BatteryStatusReadResult
}

fun interface BatteryStatusReader {
    suspend fun read(): BatteryStatusReadResult
}

object UnavailableBatteryStatusReader : BatteryStatusReader {
    override suspend fun read(): BatteryStatusReadResult = BatteryStatusReadResult.Unavailable
}

class AndroidBatteryStatusReader(context: Context) : BatteryStatusReader {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    override suspend fun read(): BatteryStatusReadResult = withContext(Dispatchers.IO) {
        try {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@withContext BatteryStatusReadResult.Unavailable
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return@withContext BatteryStatusReadResult.Failed
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            BatteryStatusReadResult.Success(
                BatteryStatusRecord(
                    levelPercent = (level * 100 / scale).coerceIn(0, 100),
                    charging = charging,
                    powerSource = if (charging) plugged.toPowerSource() else BatteryPowerSource.NONE,
                ),
            )
        } catch (_: RuntimeException) {
            // long: OEM 电池广播异常不能把半份电量或供电来源升级成可信 Agent 事实。
            BatteryStatusReadResult.Failed
        }
    }

    private fun Int.toPowerSource(): BatteryPowerSource = when {
        and(BatteryManager.BATTERY_PLUGGED_AC) != 0 -> BatteryPowerSource.AC
        and(BatteryManager.BATTERY_PLUGGED_USB) != 0 -> BatteryPowerSource.USB
        and(BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 -> BatteryPowerSource.WIRELESS
        // long: 部分 OEM 会返回扩展的底座类型，无法识别时保持未知而不是猜测为 USB。
        this != 0 -> BatteryPowerSource.DOCK
        else -> BatteryPowerSource.UNKNOWN
    }
}

internal object BatteryStatusResultCodec {
    fun encode(status: BatteryStatusRecord): String = buildString {
        appendLine("电量：${status.levelPercent.coerceIn(0, 100)}%")
        appendLine("充电状态：${if (status.charging) "正在充电" else "未充电"}")
        append("供电方式：${status.powerSource.label()}")
    }

    private fun BatteryPowerSource.label(): String = when (this) {
        BatteryPowerSource.NONE -> "未连接电源"
        BatteryPowerSource.AC -> "交流电"
        BatteryPowerSource.USB -> "USB"
        BatteryPowerSource.WIRELESS -> "无线充电"
        BatteryPowerSource.DOCK -> "底座或其他电源"
        BatteryPowerSource.UNKNOWN -> "未知"
    }
}
