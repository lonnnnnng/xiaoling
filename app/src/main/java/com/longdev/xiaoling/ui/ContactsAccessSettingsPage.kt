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
import androidx.compose.material.icons.filled.Person
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
internal fun ContactsAccessSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // long: 系统设置可能撤销联系人授权，返回应用时必须重新读取真实权限，不能沿用旧页面状态继续展示可用。
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ContactsAccessSettingsContent(
        permissionGranted = permissionGranted,
        onRequestPermission = {
            // long: 通讯录属于敏感个人数据，授权只能由用户在独立设置页主动触发，Agent 工具与后台任务均不能弹出权限框。
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
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
internal fun ContactsAccessSettingsContent(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
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
                    text = "联系人访问",
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
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = if (permissionGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (permissionGranted) "联系人权限已授权" else "联系人权限未授权",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (permissionGranted) {
                                    "前台 Agent 可以按 Profile 与 Skill 白名单查询系统联系人。"
                                } else {
                                    "未授权时 contacts.search 与 contacts.get 保持不可执行。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!permissionGranted) {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("授权只读联系人")
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
            ContactsAccessBoundaryCard()
        }
    }
}

@Composable
private fun ContactsAccessBoundaryCard() {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("访问范围", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("只接受用户明确给出的姓名、电话号码或邮箱片段，每次最多返回 10 个候选。", style = MaterialTheme.typography.bodySmall)
            Text("搜索摘要只返回姓名、匹配类型和稳定 ID，不返回具体号码或邮箱。", style = MaterialTheme.typography.bodySmall)
            Text("只有唯一候选确认后，详情读取才返回姓名、电话号码和邮箱。", style = MaterialTheme.typography.bodySmall)
            Text("不会读取地址、公司、生日、备注、头像、群组或账户信息。", style = MaterialTheme.typography.bodySmall)
            Text("当前只读，不会创建、修改或删除联系人，也不会自动拨号、发短信或发送邮件。", style = MaterialTheme.typography.bodySmall)
            Text(
                "授权后仍需在 Agent Profile 中显式启用 contacts.search、contacts.get 和 contacts-lookup Skill。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
