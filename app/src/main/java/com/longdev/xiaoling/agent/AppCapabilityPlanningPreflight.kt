package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceActionPolicy

sealed interface AppCapabilityPlanningPreflightResult {
    data class Available(
        val allowedAppPackages: List<String>,
        val availableApps: List<InstalledAppRecord> = emptyList(),
    ) : AppCapabilityPlanningPreflightResult

    data class Unavailable(val reason: String) : AppCapabilityPlanningPreflightResult
}

/**
 * long: 规划阶段只能使用“当前确实可启动”且“执行层已经登记”的应用交集；发现层不能反向扩大设备动作白名单。
 */
object AppCapabilityPlanningPreflight {
    fun resolve(
        directoryResult: InstalledAppDirectoryReadResult,
        registeredPackages: Set<String> = DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES,
    ): AppCapabilityPlanningPreflightResult {
        val normalizedRegisteredPackages = registeredPackages
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
            .intersect(DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES)
        if (normalizedRegisteredPackages.isEmpty()) {
            return AppCapabilityPlanningPreflightResult.Unavailable("设备应用登记白名单为空")
        }
        val directory = when (directoryResult) {
            InstalledAppDirectoryReadResult.Unavailable ->
                return AppCapabilityPlanningPreflightResult.Unavailable("当前可启动应用目录不可用")
            InstalledAppDirectoryReadResult.Failed ->
                return AppCapabilityPlanningPreflightResult.Unavailable("读取当前可启动应用目录失败")
            is InstalledAppDirectoryReadResult.Success -> directoryResult.directory
        }
        val discoveredPackages = directory.apps.map(InstalledAppRecord::packageName).toSet()
        val allowedAppPackages = normalizedRegisteredPackages
            .intersect(discoveredPackages)
            .sorted()
        return if (allowedAppPackages.isEmpty()) {
            AppCapabilityPlanningPreflightResult.Unavailable("当前没有已发现且已登记的可启动设备应用")
        } else {
            AppCapabilityPlanningPreflightResult.Available(
                allowedAppPackages = allowedAppPackages,
                availableApps = directory.apps
                    .asSequence()
                    .filter { app -> app.packageName in allowedAppPackages }
                    .distinctBy(InstalledAppRecord::packageName)
                    .sortedWith(
                        compareBy<InstalledAppRecord> { app -> app.capability.name }
                            .thenBy(InstalledAppRecord::appName)
                            .thenBy(InstalledAppRecord::packageName),
                    )
                    .toList(),
            )
        }
    }

    fun validateTargetPackage(
        packageName: String?,
        allowedAppPackages: Set<String>,
    ): Boolean {
        return packageName == null || packageName in allowedAppPackages
    }
}
