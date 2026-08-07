package com.longdev.xiaoling.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun CalendarAccessSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var readPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var writePermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val readPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        readPermissionGranted = granted
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        readPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        writePermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // long: 用户可能在系统权限页撤销或恢复授权，返回应用时必须重新读取真实状态，不能沿用旧 Compose 快照。
                readPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
                writePermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CalendarAccessSettingsContent(
        readPermissionGranted = readPermissionGranted,
        writePermissionGranted = writePermissionGranted,
        onRequestReadPermission = {
            // long: 日历属于敏感个人数据，只有用户在独立设置页主动点击后才触发系统授权，不从工具执行或后台任务中弹窗。
            readPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        },
        onRequestWritePermission = {
            // long: 创建权限与只读能力分开申请；用户仅查看日程时不必授予写权限，写工具也不能在执行中自行拉起权限弹窗。
            writePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        },
        onOpenSystemSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun CalendarAccessSettingsContent(
    readPermissionGranted: Boolean,
    writePermissionGranted: Boolean,
    onRequestReadPermission: () -> Unit,
    onRequestWritePermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回设置",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "日历访问",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = if (readPermissionGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (readPermissionGranted) "日历权限已授权" else "日历权限未授权",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (readPermissionGranted) {
                                    "前台 Agent 可以按 Profile 与 Skill 白名单读取近期日程。"
                                } else {
                                    "未授权时 calendar.list_events 会保持不可执行。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!readPermissionGranted) {
                        Button(
                            onClick = onRequestReadPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("授权只读日历")
                        }
                    }
                    Text(
                        text = if (writePermissionGranted) "日程创建权限已授权" else "日程创建权限未授权",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (writePermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!writePermissionGranted || !readPermissionGranted) {
                        Button(
                            onClick = onRequestWritePermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("授权创建日程")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("打开系统权限设置", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        item {
            CalendarAccessBoundaryCard()
        }
    }
}

@Composable
private fun CalendarAccessBoundaryCard() {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("访问范围", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "仅在前台 Agent 中读取未来 1–30 天内的近期日程，每次最多 20 条。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "只返回标题、开始时间、结束时间和全天标记。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "只读工具不会读取地点、描述、参与人或账户。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "创建日程仅支持一次性非全天事件，每次都需前台审批，写入后回读验证。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "系统没有可写日历时，会创建仅属于小灵的本地日历，不接入或暴露账户信息。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "不会修改或删除已有日程；创建工具不会在后台自动执行。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "授权后仍需在 Agent Profile 中显式启用对应工具，并启用 calendar-overview 或 calendar-create Skill。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
