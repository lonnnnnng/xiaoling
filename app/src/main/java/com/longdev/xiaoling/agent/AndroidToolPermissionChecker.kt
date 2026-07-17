package com.longdev.xiaoling.agent

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class AndroidToolPermissionChecker(context: Context) : ToolPermissionChecker {
    private val applicationContext = context.applicationContext

    override fun missingPermissions(requiredPermissions: Set<String>): Set<String> {
        // long: 工具权限门禁只把系统明确授予的权限视为可用；未声明、已撤销或无法识别的权限都保留在缺失集合中，避免执行阶段 fail-open。
        return requiredPermissions.filterTo(linkedSetOf()) { permission ->
            ContextCompat.checkSelfPermission(applicationContext, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}
