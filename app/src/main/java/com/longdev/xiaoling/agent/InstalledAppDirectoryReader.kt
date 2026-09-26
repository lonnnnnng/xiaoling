package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator
import java.util.Locale

enum class InstalledAppCapability {
    WEATHER,
    CLOCK,
    CALCULATOR,
    SETTINGS,
    UNKNOWN,
}

data class InstalledAppRecord(
    val appName: String,
    val packageName: String,
    val capability: InstalledAppCapability,
)

data class InstalledAppDirectory(
    val apps: List<InstalledAppRecord>,
    val truncated: Boolean,
)

sealed interface InstalledAppDirectoryReadResult {
    data class Success(val directory: InstalledAppDirectory) : InstalledAppDirectoryReadResult
    data object Unavailable : InstalledAppDirectoryReadResult
    data object Failed : InstalledAppDirectoryReadResult
}

fun interface InstalledAppDirectoryReader {
    suspend fun read(): InstalledAppDirectoryReadResult
}

object UnavailableInstalledAppDirectoryReader : InstalledAppDirectoryReader {
    override suspend fun read(): InstalledAppDirectoryReadResult = InstalledAppDirectoryReadResult.Unavailable
}

internal data class InstalledAppCandidateInput(
    val appName: String,
    val packageName: String,
)

/**
 * long: 发现层只把可启动应用投影成能力候选，发现结果不能直接扩大设备动作白名单；去重、排序和输出字段在这里冻结，避免 OEM PackageManager 顺序污染模型输入。
 */
object InstalledAppDirectoryPolicy {
    const val MAX_APPS = 200

    internal fun normalize(inputs: List<InstalledAppCandidateInput>): InstalledAppDirectory {
        val appNameCollator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        val normalized = inputs
            .mapNotNull { input ->
                val packageName = input.packageName
                    .replace(Regex("[\\r\\n\\t]+"), " ")
                    .trim()
                    .take(200)
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val appName = input.appName
                    .replace(Regex("[\\r\\n\\t]+"), " ")
                    .trim()
                    .take(120)
                    .ifBlank { packageName }
                InstalledAppRecord(
                    appName = appName,
                    packageName = packageName,
                    capability = classify(appName, packageName),
                )
            }
            .distinctBy(InstalledAppRecord::packageName)
            .sortedWith(
                Comparator { left: InstalledAppRecord, right: InstalledAppRecord ->
                    val appNameOrder = appNameCollator.compare(left.appName, right.appName)
                    if (appNameOrder != 0) {
                        appNameOrder
                    } else {
                        left.packageName.compareTo(right.packageName)
                    }
                },
            )
        return InstalledAppDirectory(
            apps = normalized.take(MAX_APPS),
            truncated = normalized.size > MAX_APPS,
        )
    }

    private fun classify(appName: String, packageName: String): InstalledAppCapability {
        val searchable = "$appName $packageName".lowercase()
        return when {
            listOf("天气", "weather", "forecast", "meteo").any(searchable::contains) ->
                InstalledAppCapability.WEATHER
            listOf("时钟", "clock", "alarm").any(searchable::contains) ->
                InstalledAppCapability.CLOCK
            listOf("计算器", "calculator", "calc").any(searchable::contains) ->
                InstalledAppCapability.CALCULATOR
            listOf("设置", "settings", "setting").any(searchable::contains) ->
                InstalledAppCapability.SETTINGS
            else -> InstalledAppCapability.UNKNOWN
        }
    }
}

class AndroidInstalledAppDirectoryReader(context: Context) : InstalledAppDirectoryReader {
    private val appContext = context.applicationContext

    override suspend fun read(): InstalledAppDirectoryReadResult = withContext(Dispatchers.IO) {
        try {
            val packageManager = appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val candidates = packageManager
                .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .mapNotNull { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val packageName = activityInfo.packageName?.trim().orEmpty()
                    if (packageName.isBlank()) return@mapNotNull null
                    val appName = resolveInfo.loadLabel(packageManager).toString()
                    InstalledAppCandidateInput(appName = appName, packageName = packageName)
                }
            InstalledAppDirectoryReadResult.Success(InstalledAppDirectoryPolicy.normalize(candidates))
        } catch (_: RuntimeException) {
            // long: OEM 包管理器或包可见性策略异常时不把不完整目录当成“全部已安装应用”，直接 fail-closed。
            InstalledAppDirectoryReadResult.Failed
        }
    }
}

internal object InstalledAppDirectoryResultCodec {
    fun encode(directory: InstalledAppDirectory): String = buildJsonObject {
        put("source", "launcher_directory")
        put("count", directory.apps.size)
        put("truncated", directory.truncated)
        put(
            "apps",
            buildJsonArray {
                directory.apps.forEach { app ->
                    add(
                        buildJsonObject {
                            put("name", app.appName)
                            put("package", app.packageName)
                            put("capability", app.capability.name.lowercase())
                        },
                    )
                }
            },
        )
    }.toString()
}
