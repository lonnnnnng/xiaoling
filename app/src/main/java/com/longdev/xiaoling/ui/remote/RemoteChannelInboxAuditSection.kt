package com.longdev.xiaoling.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.RemoteChannelInboxAuditEntry
import com.longdev.xiaoling.agent.RemoteChannelInboxAuditPolicy
import com.longdev.xiaoling.agent.RemoteChannelInboxDisposition
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RemoteChannelInboxAuditSection(entries: List<RemoteChannelInboxAuditEntry>) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("remote-inbox-audit-toggle")) {
        Text(if (expanded) "收起处理记录（${entries.size}）" else "处理记录（${entries.size}）")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("remote-inbox-audit-history")) {
            Text("本机保留最近 ${RemoteChannelInboxAuditPolicy.MAX_ENTRIES} 条处理记录，不保留正文。这里只显示最近 10 条。", style = MaterialTheme.typography.bodySmall)
            if (entries.isEmpty()) Text("暂无处理记录")
            // long: 历史只读且不提供重新打开动作，防止已忽略或过期的正文被处理记录重新带回草稿。
            entries.takeLast(10).asReversed().forEach { entry ->
                val label = when (entry.disposition) {
                    RemoteChannelInboxDisposition.OPENED -> "已确认打开草稿"
                    RemoteChannelInboxDisposition.IGNORED -> "已忽略"
                    RemoteChannelInboxDisposition.EXPIRED -> "正文已过期"
                }
                Text("$label · ${entry.messageIdentitySha256.take(12)}", modifier = Modifier.testTag("remote-inbox-audit-${entry.disposition.name}"))
                val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                Text("入箱 ${formatter.format(Date(entry.acceptedAtMillis))}；记录 ${formatter.format(Date(entry.recordedAtMillis))}", style = MaterialTheme.typography.bodySmall)
            }
            Text("确认打开不代表已发送或执行；过期时间是本机检测并保存记录的时间。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
