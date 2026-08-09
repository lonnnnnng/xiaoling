package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageStatusRecord(
    val totalBytes: Long,
    val availableBytes: Long,
)

sealed interface StorageStatusReadResult {
    data class Success(val status: StorageStatusRecord) : StorageStatusReadResult
    data object Unavailable : StorageStatusReadResult
    data object Failed : StorageStatusReadResult
}

fun interface StorageStatusReader {
    suspend fun read(): StorageStatusReadResult
}

object UnavailableStorageStatusReader : StorageStatusReader {
    override suspend fun read(): StorageStatusReadResult = StorageStatusReadResult.Unavailable
}

class AndroidStorageStatusReader(context: Context) : StorageStatusReader {
    private val appContext = context.applicationContext

    override suspend fun read(): StorageStatusReadResult = withContext(Dispatchers.IO) {
        try {
            val stats = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stats.totalBytes
            val availableBytes = stats.availableBytes.coerceAtLeast(0L).coerceAtMost(totalBytes)
            if (totalBytes <= 0L) {
                StorageStatusReadResult.Failed
            } else {
                StorageStatusReadResult.Success(
                    StorageStatusRecord(
                        totalBytes = totalBytes,
                        availableBytes = availableBytes,
                    ),
                )
            }
        } catch (_: RuntimeException) {
            // long: OEM 文件系统统计异常时不把路径或猜测的空间数值投影为可信 Agent 事实。
            StorageStatusReadResult.Failed
        }
    }
}

internal object StorageStatusResultCodec {
    fun encode(status: StorageStatusRecord): String {
        val total = status.totalBytes.coerceAtLeast(1L)
        val available = status.availableBytes.coerceIn(0L, total)
        val usedPercent = ((total - available) * 100.0 / total).coerceIn(0.0, 100.0)
        return buildString {
            appendLine("存储总量：${formatGigabytes(total)}")
            appendLine("可用空间：${formatGigabytes(available)}")
            append("已使用：${String.format(Locale.US, "%.1f", usedPercent)}%")
        }
    }

    private fun formatGigabytes(bytes: Long): String {
        val gigabytes = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "${String.format(Locale.US, "%.1f", gigabytes)} GB"
    }
}
