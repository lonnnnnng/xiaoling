package com.longdev.xiaoling.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPeerCandidate

@Composable
internal fun RemoteChannelPairingPage(
    registry: RemoteChannelPairingRegistry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var channelId by remember { mutableStateOf("") }
    var senderId by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var sourceLabel by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var confirmedFingerprint by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<RemoteChannelPeerCandidate?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<RemoteChannelPeerCandidate?>(null) }
    var revision by remember { mutableStateOf(0) }
    val loaded = remember(registry, revision) { runCatching { registry.list() } }
    val peers = loaded.getOrNull().orEmpty()
    val stateFailure = loaded.exceptionOrNull()?.message

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("remote-pairing-page"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
            }
            Text("远端身份配对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "从可信来源取得发送方公钥，并在另一可信渠道核对 SHA-256 指纹。配对后默认停用；启用只允许验签，不会开启网络收件或自动运行 Agent。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stateFailure != null) {
            Text("配对状态不可用，入站验签与变更已冻结：$stateFailure",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("remote-pairing-state-error"))
        }
        OutlinedTextField(channelId, { channelId = it.take(200); candidate = null },
            label = { Text("Channel ID") }, modifier = Modifier.fillMaxWidth().testTag("remote-pairing-channel"),
            enabled = stateFailure == null)
        OutlinedTextField(senderId, { senderId = it.take(200); candidate = null },
            label = { Text("发送方 ID") }, modifier = Modifier.fillMaxWidth().testTag("remote-pairing-sender"),
            enabled = stateFailure == null)
        OutlinedTextField(keyId, { keyId = it.take(64); candidate = null },
            label = { Text("keyId") }, modifier = Modifier.fillMaxWidth().testTag("remote-pairing-key-id"),
            enabled = stateFailure == null)
        OutlinedTextField(sourceLabel, { sourceLabel = it.take(100); candidate = null },
            label = { Text("来源名称") }, modifier = Modifier.fillMaxWidth().testTag("remote-pairing-source"),
            enabled = stateFailure == null)
        OutlinedTextField(publicKey, { publicKey = it.take(2_048); candidate = null },
            label = { Text("X.509 SPKI 公钥 Base64 或 PEM") },
            modifier = Modifier.fillMaxWidth().testTag("remote-pairing-public-key"),
            minLines = 3, maxLines = 6, enabled = stateFailure == null)
        Button(
            onClick = {
                candidate = runCatching {
                    RemoteChannelPairingPolicy.prepare(channelId, senderId, keyId, sourceLabel, publicKey)
                }.onFailure { notice = it.message ?: "公钥无效" }.getOrNull()
                if (candidate != null) {
                    confirmedFingerprint = ""
                    notice = "请通过另一可信渠道核对完整指纹，再输入确认"
                }
            },
            enabled = stateFailure == null && publicKey.isNotBlank(),
            modifier = Modifier.testTag("remote-pairing-preview"),
        ) { Text("预览指纹") }
        candidate?.let { prepared ->
            // long: 输入任一身份或公钥字段时撤销预览，避免把旧指纹确认应用到新的公钥材料。
            Text("SHA-256：${prepared.fingerprintSha256.chunked(4).joinToString(" ")}",
                modifier = Modifier.testTag("remote-pairing-fingerprint"))
            OutlinedTextField(confirmedFingerprint, { confirmedFingerprint = it.take(95) },
                label = { Text("输入可信渠道提供的完整 SHA-256 指纹") },
                modifier = Modifier.fillMaxWidth().testTag("remote-pairing-confirm-fingerprint"),
                enabled = stateFailure == null)
            Button(
                onClick = {
                    val result = runCatching { registry.confirm(prepared, confirmedFingerprint) }
                    notice = result.fold(
                        onSuccess = { "已保存 ${it.candidate.senderId}/${it.candidate.keyId}，默认停用" },
                        onFailure = { it.message ?: "配对保存失败" },
                    )
                    if (result.isSuccess) candidate = null
                    revision += 1
                },
                enabled = stateFailure == null &&
                    RemoteChannelPairingPolicy.matchesFingerprint(prepared, confirmedFingerprint),
                modifier = Modifier.testTag("remote-pairing-confirm"),
            ) { Text("确认并保存") }
        }
        notice?.let { Text(it, modifier = Modifier.testTag("remote-pairing-notice")) }

        Text("已配对身份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (peers.isEmpty()) Text("尚无远端配对身份", modifier = Modifier.testTag("remote-pairing-empty"))
        peers.forEach { peer ->
            val identity = peer.candidate
            val tag = "${identity.channelId}-${identity.senderId}-${identity.keyId}"
            Card(modifier = Modifier.fillMaxWidth().testTag("remote-peer-$tag")) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(identity.sourceLabel, fontWeight = FontWeight.SemiBold)
                    Text("${identity.channelId} / ${identity.senderId} / ${identity.keyId}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256：${identity.fingerprintSha256.chunked(4).joinToString(" ")}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("允许验签", modifier = Modifier.weight(1f))
                        Switch(
                            checked = peer.enabled,
                            onCheckedChange = { enabled ->
                                notice = runCatching {
                                    registry.setEnabled(identity.channelId, identity.senderId, identity.keyId, enabled)
                                }.fold(
                                    onSuccess = { if (it) "已${if (enabled) "启用" else "停用"}验签" else "身份不存在" },
                                    onFailure = { it.message ?: "启停失败" },
                                )
                                revision += 1
                            },
                            enabled = stateFailure == null,
                            modifier = Modifier.testTag("remote-peer-enabled-$tag"),
                        )
                    }
                    TextButton(onClick = { revoke = identity }, enabled = stateFailure == null,
                        modifier = Modifier.testTag("remote-peer-revoke-$tag")) { Text("撤销配对") }
                }
            }
        }
    }

    revoke?.let { identity ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("撤销远端身份？") },
            text = { Text("将移除 ${identity.channelId}/${identity.senderId}/${identity.keyId} 的公钥；之后使用此 keyId 的消息会被拒绝。") },
            confirmButton = {
                TextButton(onClick = {
                    notice = runCatching {
                        registry.revoke(identity.channelId, identity.senderId, identity.keyId)
                    }.fold(onSuccess = { if (it) "配对已撤销" else "身份不存在" },
                        onFailure = { it.message ?: "撤销失败" })
                    revoke = null
                    revision += 1
                }, modifier = Modifier.testTag("remote-pairing-confirm-revoke")) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("取消") } },
        )
    }
}
