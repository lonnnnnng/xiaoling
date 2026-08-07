package com.longdev.xiaoling.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前安装应用的最小公开信息。
 *
 * 这里故意不建模 Provider、API Key、设备标识、安装来源或签名信息，避免一个只读工具
 * 因为“应用信息”这个宽泛名称把配置和设备隐私一起带入 Agent 结果。
 * 作者：long
 */
data class AppInfoRecord(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

sealed interface AppInfoReadResult {
    data class Success(val info: AppInfoRecord) : AppInfoReadResult
    data object Unavailable : AppInfoReadResult
    data object Failed : AppInfoReadResult
}

fun interface AppInfoReader {
    suspend fun read(): AppInfoReadResult
}

object UnavailableAppInfoReader : AppInfoReader {
    override suspend fun read(): AppInfoReadResult = AppInfoReadResult.Unavailable
}

class AndroidAppInfoReader(context: Context) : AppInfoReader {
    private val appContext = context.applicationContext

    override suspend fun read(): AppInfoReadResult = withContext(Dispatchers.IO) {
        try {
            val packageManager = appContext.packageManager
            val packageName = appContext.packageName
            val packageInfo = packageManager.readPackageInfo(packageName)
            val appName = packageInfo.applicationInfo
                ?.loadLabel(packageManager)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: packageName
            val versionName = packageInfo.versionName
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: "未设置"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            AppInfoReadResult.Success(
                AppInfoRecord(
                    appName = appName,
                    packageName = packageName,
                    versionName = versionName,
                    versionCode = versionCode,
                ),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            // long: 包名来自当前 Application；若系统包管理器在读取竞态中找不到它，宁可明确失败也不返回猜测版本。
            AppInfoReadResult.Failed
        } catch (_: RuntimeException) {
            // long: OEM PackageManager 异常不能把半份应用信息升级成可信结果，也不把异常正文带入 Agent。
            AppInfoReadResult.Failed
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.readPackageInfo(packageName: String) = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        getPackageInfo(packageName, 0)
    }
}

internal object AppInfoResultCodec {
    fun encode(info: AppInfoRecord): String = buildString {
        appendLine("应用名称：${sanitize(info.appName, fallback = "未命名应用")}")
        appendLine("包名：${sanitize(info.packageName, fallback = "未知")}")
        appendLine("版本名：${sanitize(info.versionName, fallback = "未设置")}")
        append("版本号：${info.versionCode.coerceAtLeast(0L)}")
    }

    private fun sanitize(value: String, fallback: String): String {
        return value
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(200)
            .ifBlank { fallback }
    }
}
