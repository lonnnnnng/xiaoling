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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPeerCandidate
import com.longdev.xiaoling.agent.RemoteChannelDraft
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelInboxStatus
import com.longdev.xiaoling.agent.RemoteChannelReceiveResult
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullSyncResult
import com.longdev.xiaoling.agent.RemoteChannelSignedEnvelopeCodec
import com.longdev.xiaoling.agent.RemoteChannelBackgroundController
import com.longdev.xiaoling.agent.RemoteChannelBackgroundOutcome
import com.longdev.xiaoling.agent.RemoteChannelReceiverAuthProtocol
import org.json.JSONObject
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteChannelPairingPage(
    registry: RemoteChannelPairingRegistry,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    inboxGateway: RemoteChannelInboxGateway? = null,
    pullSources: RemoteChannelPullSourceRegistry? = null,
    pullService: RemoteChannelPullService? = null,
    backgroundController: RemoteChannelBackgroundController? = null,
    onRequestNotificationPermission: () -> Unit = {},
    refreshRequest: String? = null,
    onOpenPending: suspend (RemoteChannelDraft) -> Boolean = { false },
    onIgnorePending: suspend (RemoteChannelDraft) -> Boolean = { false },
) {
    val scope = rememberCoroutineScope()
    var channelId by remember { mutableStateOf("") }
    var senderId by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var sourceLabel by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var confirmedFingerprint by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<RemoteChannelPeerCandidate?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var inboxNotice by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<RemoteChannelPeerCandidate?>(null) }
    var revision by remember { mutableStateOf(0) }
    var inboxRevision by remember { mutableStateOf(0) }
    var inboxStatus by remember { mutableStateOf<RemoteChannelInboxStatus?>(null) }
    var inboxBusy by remember { mutableStateOf(false) }
    var signedEnvelopeJson by remember { mutableStateOf("") }
    var sourceBusy by remember { mutableStateOf(false) }
    var receiverCreate by remember { mutableStateOf<RemoteChannelPeerCandidate?>(null) }
    // long: 文件选择期间 Activity 可重建；只保存公开清单，返回后仍须从当前账本重新核对，不能保存私钥或复用旧授权。
    var pendingReceiverExport by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val receiverExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingReceiverExport
        pendingReceiverExport = null
        if (uri != null && pending != null && pullService != null) {
            sourceBusy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    // long: 文件选择器可能停留很久；写入前重新导出同一公钥，不能把已移除或改变的来源当作当前登记。
                    val expected = RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(pending))
                    val peer = registry.list().singleOrNull { it.candidate.channelId == expected.channelId &&
                        it.candidate.senderId == expected.senderId && it.candidate.keyId == expected.keyId &&
                        it.candidate.fingerprintSha256 == expected.senderFingerprintSha256 }?.candidate ?: error("配对身份已变化")
                    val current = pullService.exportReceiver(peer)
                    require(current == expected) { "接收登记已变化，请重新导出" }
                    val bytes = RemoteChannelReceiverAuthProtocol.manifest(current).toByteArray(Charsets.UTF_8)
                    (context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开导出文件")).use { it.write(bytes) }
                } }
                notice = if (result.isSuccess) "接收公钥清单已导出；请让发送方管理员独立核对指纹并授权" else "接收公钥清单导出失败"
                sourceBusy = false
                revision += 1
            }
        } else if (uri != null) notice = "原导出登记不可用，请重新导出"
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { revision += 1; inboxRevision += 1 }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshRequest) {
        // long: 通知点击可能复用已停留在收件页的 Activity，必须重读加密快照，不能继续显示后台入箱前的空列表。
        if (refreshRequest != null) { revision += 1; inboxRevision += 1 }
    }
    val loaded = remember(registry, revision) { runCatching { registry.list() } }
    val peers = loaded.getOrNull().orEmpty()
    val stateFailure = loaded.exceptionOrNull()?.message
    val sourcesLoaded = remember(pullSources, revision) { runCatching { pullSources?.list().orEmpty() } }
    val sources = sourcesLoaded.getOrNull().orEmpty()
    val sourceFailure = sourcesLoaded.exceptionOrNull()?.message
    LaunchedEffect(backgroundController) {
        if (backgroundController != null) {
            val result = withContext(Dispatchers.IO) { runCatching { backgroundController.reconcile() } }
            if (result.isFailure) notice = "后台任务对账失败：${result.exceptionOrNull()?.message}"
            revision += 1
        }
    }
    fun performSourceAction(block: suspend () -> String, onSuccess: () -> Unit = {}) {
        sourceBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            notice = result.getOrElse { it.message ?: "后台收件设置失败" }
            sourceBusy = false
            revision += 1
            if (result.isSuccess) onSuccess()
        }
    }
    LaunchedEffect(inboxGateway, inboxRevision) {
        inboxStatus = inboxGateway?.let { gateway ->
            withContext(Dispatchers.IO) { gateway.status() }
        }
    }

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
            "从可信来源取得发送方公钥，并在另一可信渠道核对 SHA-256 指纹。配对后默认停用；启用只允许验签。HTTPS 地址和后台收件需要分别登记、启用；收件不会自动运行 Agent。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (stateFailure != null) {
            Text("配对状态不可用，入站验签与变更已冻结：$stateFailure",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("remote-pairing-state-error"))
        }
        if (sourceFailure != null) {
            Text("HTTPS 来源账本不可用，已冻结登记与拉取：$sourceFailure",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("remote-source-state-error"))
        }
        if (inboxGateway != null) {
            Text("待确认消息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "仅接受已配对且启用的公钥签名消息。手动导入需要完整九字段 JSON；打开后仍须在会话页确认，不会自动发送。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = signedEnvelopeJson,
                onValueChange = { signedEnvelopeJson = it.take(RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES) },
                label = { Text("已签名消息 JSON") },
                modifier = Modifier.fillMaxWidth().testTag("remote-inbox-import-json"),
                minLines = 2,
                maxLines = 5,
                enabled = !inboxBusy && stateFailure == null && inboxStatus !is RemoteChannelInboxStatus.Unavailable,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val gateway = inboxGateway
                        val raw = signedEnvelopeJson
                        inboxBusy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    gateway.receive(RemoteChannelSignedEnvelopeCodec.decode(raw))
                                }
                            }
                            inboxNotice = result.fold(
                                onSuccess = { received -> when (received) {
                                    is RemoteChannelReceiveResult.Accepted -> {
                                        signedEnvelopeJson = ""
                                        "签名消息已进入待确认收件箱"
                                    }
                                    is RemoteChannelReceiveResult.Rejected -> "签名消息已拒绝：${received.reason}"
                                } },
                                onFailure = { it.message ?: "签名消息格式无效" },
                            )
                            inboxBusy = false
                            inboxRevision += 1
                        }
                    },
                    enabled = !inboxBusy && stateFailure == null && signedEnvelopeJson.isNotBlank() &&
                        inboxStatus is RemoteChannelInboxStatus.Ready,
                    modifier = Modifier.testTag("remote-inbox-import"),
                ) { Text("导入签名消息") }
                TextButton(
                    onClick = { inboxRevision += 1 },
                    enabled = !inboxBusy,
                    modifier = Modifier.testTag("remote-inbox-refresh"),
                ) { Text("刷新列表") }
            }
            inboxNotice?.let { Text(it, modifier = Modifier.testTag("remote-inbox-notice")) }
            when (val current = inboxStatus) {
                null -> Text("正在读取加密收件箱")
                RemoteChannelInboxStatus.Unavailable -> Text(
                    "加密收件箱不可用；已冻结导入和待确认操作，请检查本机密钥或存储。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("remote-inbox-unavailable"),
                )
                is RemoteChannelInboxStatus.Ready -> {
                    if (current.pending.isEmpty()) {
                        Text("暂无待确认消息", modifier = Modifier.testTag("remote-inbox-empty"))
                    }
                    current.pending.forEach { pending ->
                        val draft = pending.draft
                        val tag = "${draft.channelId}-${draft.senderId}-${draft.messageId}"
                        Card(modifier = Modifier.fillMaxWidth().testTag("remote-inbox-item-$tag")) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${draft.channelId} / ${draft.senderId}", fontWeight = FontWeight.SemiBold)
                                Text(draft.payload.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            inboxBusy = true
                                            scope.launch {
                                                val opened = onOpenPending(draft)
                                                inboxNotice = if (opened) "已转到会话页，请确认打开草稿" else "尚未打开，请刷新列表或先处理当前草稿"
                                                inboxBusy = false
                                                inboxRevision += 1
                                            }
                                        },
                                        enabled = !inboxBusy,
                                        modifier = Modifier.testTag("remote-inbox-open-$tag"),
                                    ) { Text("打开") }
                                    TextButton(
                                        onClick = {
                                            inboxBusy = true
                                            scope.launch {
                                                val discarded = onIgnorePending(draft)
                                                inboxNotice = if (discarded) "已忽略，正文已从加密收件箱移除" else "未提交忽略，请刷新列表查看消息状态"
                                                inboxBusy = false
                                                inboxRevision += 1
                                            }
                                        },
                                        enabled = !inboxBusy,
                                        modifier = Modifier.testTag("remote-inbox-ignore-$tag"),
                                    ) { Text("忽略") }
                                }
                            }
                        }
                    }
                    RemoteChannelInboxAuditSection(current.auditEntries)
                }
            }
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
            val savedSource = sources.firstOrNull { it.channelId == identity.channelId &&
                it.senderId == identity.senderId && it.keyId == identity.keyId &&
                it.fingerprintSha256 == identity.fingerprintSha256 }
            var sourceUrl by remember(tag, savedSource?.url) { mutableStateOf(savedSource?.url.orEmpty()) }
            val backgroundLoaded = remember(backgroundController, revision, tag) { runCatching { backgroundController?.entry(identity) } }
            val background = backgroundLoaded.getOrNull()
            Card(modifier = Modifier.fillMaxWidth().testTag("remote-peer-$tag")) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(identity.sourceLabel, fontWeight = FontWeight.SemiBold)
                    Text("${identity.channelId} / ${identity.senderId} / ${identity.keyId}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256：${identity.fingerprintSha256.chunked(4).joinToString(" ")}",
                        style = MaterialTheme.typography.bodySmall)
                    if (pullSources != null && pullService != null) {
                        // long: 每个地址只与当前公钥指纹绑定；失效或轮换后需重新保存，拉取不能从消息正文派生 URL。
                        OutlinedTextField(
                            value = sourceUrl,
                            onValueChange = { sourceUrl = it.take(com.longdev.xiaoling.agent.RemoteChannelPullPolicy.MAX_URL_CHARS) },
                            label = { Text("此身份的 HTTPS 收件地址") },
                            modifier = Modifier.fillMaxWidth().testTag("remote-source-url-$tag"),
                            enabled = !sourceBusy && sourceFailure == null && stateFailure == null,
                            singleLine = true,
                        )
                        Text("地址会保存在本机配置中，请勿把令牌写入 URL 路径。支持单条签名消息或有界签名批次；同步不会自动运行 Agent。",
                            style = MaterialTheme.typography.bodySmall)
                        if (savedSource != null) {
                            val auth = savedSource.receiverAuthorization
                            Text(if (!savedSource.receiverAuthRequired) "接收请求：兼容模式" else if (auth == null)
                                "接收认证尚未完成，同步已冻结；尚无私钥可继续创建，已有私钥请导出原公钥" else
                                "接收请求已启用认证；发送方仍须独立核对公钥指纹并授权", modifier = Modifier.testTag("remote-receiver-status-$tag"),
                                style = MaterialTheme.typography.bodySmall)
                            auth?.let { Text("接收公钥 SHA-256：${it.receiverFingerprintSha256.chunked(4).joinToString(" ")}",
                                modifier = Modifier.testTag("remote-receiver-fingerprint-$tag"), style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { receiverCreate = identity },
                                    enabled = !sourceBusy && !inboxBusy && savedSource.receiverAuthorization == null && stateFailure == null && sourceFailure == null,
                                    modifier = Modifier.testTag("remote-receiver-create-$tag")) { Text(if (savedSource.receiverAuthRequired) "继续创建接收公钥" else "创建接收公钥") }
                                TextButton(onClick = {
                                    sourceBusy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { pullService.exportReceiver(identity) } }
                                        sourceBusy = false
                                        revision += 1
                                        result.fold(onSuccess = { auth ->
                                            pendingReceiverExport = RemoteChannelReceiverAuthProtocol.manifest(auth)
                                            receiverExportLauncher.launch("xiaoling-receiver-${auth.receiverId}.json")
                                        }, onFailure = { notice = it.message ?: "接收公钥不可用" })
                                    }
                                }, enabled = !sourceBusy && !inboxBusy && savedSource.receiverAuthRequired && stateFailure == null && sourceFailure == null,
                                    modifier = Modifier.testTag("remote-receiver-export-$tag")) { Text("导出公钥清单") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    performSourceAction({
                                        val normalized = com.longdev.xiaoling.agent.RemoteChannelPullPolicy.endpoint(sourceUrl).toString()
                                        // long: 改地址先撤销旧后台许可；新 URL 必须由用户重新启用，不能自动继承旧收件授权。
                                        if (normalized != savedSource?.url) backgroundController?.setEnabled(identity, false)
                                        pullSources.save(identity, sourceUrl)
                                        "HTTPS 收件来源已登记；新地址的后台收件默认关闭"
                                    })
                                },
                                enabled = !sourceBusy && sourceFailure == null && stateFailure == null && sourceUrl.isNotBlank(),
                                modifier = Modifier.testTag("remote-source-save-$tag"),
                            ) { Text("登记来源") }
                            TextButton(
                                onClick = {
                                    sourceBusy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { pullService.synchronize(identity) } }
                                        notice = result.fold(
                                            onSuccess = { synced -> when (synced) {
                                                is RemoteChannelPullSyncResult.Batch -> "本次入箱 ${synced.acceptedCount} 条，重复 ${synced.duplicateCount} 条；" +
                                                    if (synced.ackPending) "送达回执待补发，可稍后再次同步" else "送达回执已确认"
                                                is RemoteChannelPullSyncResult.Single -> when (val received = synced.result) {
                                                is RemoteChannelReceiveResult.Accepted -> "签名消息已进入待确认收件箱"
                                                is RemoteChannelReceiveResult.Rejected -> "HTTPS 消息已拒绝：${received.reason}"
                                                }
                                            } },
                                            onFailure = { it.message ?: "HTTPS 收件失败" },
                                        )
                                        sourceBusy = false
                                        inboxRevision += 1
                                    }
                                },
                                enabled = !sourceBusy && !inboxBusy && peer.enabled && savedSource != null &&
                                    (!savedSource.receiverAuthRequired || savedSource.receiverAuthorization != null) &&
                                    sourceFailure == null && stateFailure == null &&
                                    inboxStatus is RemoteChannelInboxStatus.Ready,
                                modifier = Modifier.testTag("remote-source-pull-$tag"),
                            ) { Text("同步收件") }
                            TextButton(
                                onClick = {
                                    performSourceAction({
                                        backgroundController?.setEnabled(identity, false)
                                        if (pullSources.remove(identity)) "HTTPS 来源已移除" else "没有已登记来源"
                                    })
                                },
                                enabled = !sourceBusy && savedSource != null && sourceFailure == null,
                                modifier = Modifier.testTag("remote-source-remove-$tag"),
                            ) { Text("移除来源") }
                        }
                        if (backgroundController != null) {
                            if (backgroundLoaded.isFailure) Text("后台收件账本不可用，设置与收件已冻结",
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("remote-background-state-error-$tag"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("后台收件", modifier = Modifier.weight(1f))
                                Switch(checked = background?.enabled == true, onCheckedChange = { enabled ->
                                    performSourceAction({
                                        backgroundController.setEnabled(identity, enabled)
                                        if (enabled) "后台收件已启用；新消息仍需前台确认" else "后台收件已停用"
                                    })
                                }, enabled = !sourceBusy && backgroundLoaded.isSuccess && peer.enabled && savedSource != null &&
                                    (!savedSource.receiverAuthRequired || savedSource.receiverAuthorization != null) && sourceFailure == null && stateFailure == null,
                                    modifier = Modifier.testTag("remote-background-enabled-$tag"))
                            }
                            Text("联网时定期检查此地址，间隔至少 15 分钟，系统可能延后。停用验签、撤销配对或更改地址会停止后台收件。",
                                style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("送达通知", modifier = Modifier.weight(1f))
                                Switch(checked = background?.notificationsEnabled == true, onCheckedChange = { enabled ->
                                    performSourceAction({
                                        backgroundController.setNotifications(identity, enabled)
                                        if (enabled) "送达通知已开启；系统拒绝通知权限时仍保留待确认消息" else "送达通知已关闭"
                                    }, onSuccess = { if (enabled) onRequestNotificationPermission() })
                                }, enabled = !sourceBusy && background?.enabled == true && peer.enabled && backgroundLoaded.isSuccess,
                                    modifier = Modifier.testTag("remote-background-notifications-$tag"))
                            }
                            Text("通知不显示消息正文，只打开待确认列表。未授予系统通知权限时不会发送通知。", style = MaterialTheme.typography.bodySmall)
                            background?.lastOutcome?.let { outcome ->
                                val detail = when (outcome) {
                                    RemoteChannelBackgroundOutcome.SYNCED -> "最近同步已完成"
                                    RemoteChannelBackgroundOutcome.ACK_PENDING -> "已入箱，送达回执待补发"
                                    RemoteChannelBackgroundOutcome.DEFERRED -> "正在等待重试间隔"
                                    RemoteChannelBackgroundOutcome.RETRYABLE_ERROR -> "网络暂不可用，将有界重试"
                                    RemoteChannelBackgroundOutcome.REJECTED -> "收件被拒绝，请检查来源或存储"
                                    RemoteChannelBackgroundOutcome.INVALIDATED -> "来源或配对已变化，请重新启用"
                                    RemoteChannelBackgroundOutcome.DISABLED -> "后台收件已停用"
                                }
                                Text(detail, modifier = Modifier.testTag("remote-background-outcome-$tag"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("允许验签", modifier = Modifier.weight(1f))
                        Switch(
                            checked = peer.enabled,
                            onCheckedChange = { enabled ->
                                performSourceAction({
                                    val cancelled = if (!enabled) runCatching { backgroundController?.setEnabled(identity, false) } else null
                                    val changed = registry.setEnabled(identity.channelId, identity.senderId, identity.keyId, enabled)
                                    if (cancelled?.isFailure == true) "验签已停用；后台任务取消失败，后续消息仍会被拒绝"
                                    else if (changed) "已${if (enabled) "启用" else "停用"}验签" else "身份不存在"
                                })
                            },
                            enabled = stateFailure == null && !sourceBusy,
                            modifier = Modifier.testTag("remote-peer-enabled-$tag"),
                        )
                    }
                    TextButton(onClick = { revoke = identity }, enabled = stateFailure == null,
                        modifier = Modifier.testTag("remote-peer-revoke-$tag")) { Text("撤销配对") }
                }
            }
        }
    }

    receiverCreate?.let { identity ->
        AlertDialog(onDismissRequest = { receiverCreate = null }, title = { Text("创建接收签名身份？") },
            text = { Text("将为此地址创建不可导出的私钥，并永久启用接收请求认证。后台收件会停用，旧服务须支持认证批次；导出公钥后还需发送方管理员独立核对指纹并授权。中断前已有私钥时拒绝覆盖，请改为导出原公钥。已登记身份缺钥或认证失败时同步停止，更换地址或密钥需要新的 keyId。") },
            confirmButton = { TextButton(onClick = {
                receiverCreate = null
                performSourceAction({
                    backgroundController?.setEnabled(identity, false)
                    pullService?.createReceiver(identity) ?: error("接收服务不可用")
                    "接收公钥已创建；请导出清单并由发送方管理员核对、授权。后台收件需另行启用"
                })
            }, modifier = Modifier.testTag("remote-receiver-confirm-create")) { Text("创建并启用认证") } },
            dismissButton = { TextButton(onClick = { receiverCreate = null }) { Text("取消") } })
    }

    revoke?.let { identity ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("撤销远端身份？") },
            text = { Text("将移除 ${identity.channelId}/${identity.senderId}/${identity.keyId} 的公钥；之后使用此 keyId 的消息会被拒绝。") },
            confirmButton = {
                TextButton(onClick = {
                    // long: 撤销先移除这把公钥登记的拉取地址，避免同一身份重新配对后沿用旧来源；即使来源账本不可用，验签撤销仍优先执行。
                    performSourceAction({
                        val cancellation = runCatching { backgroundController?.setEnabled(identity, false) }
                        val sourceRemoval = runCatching { pullSources?.remove(identity) }
                        if (registry.revoke(identity.channelId, identity.senderId, identity.keyId)) {
                            if (sourceRemoval.isFailure || cancellation.isFailure) "配对已撤销；来源或任务取消失败，后续消息仍会被拒绝" else "配对已撤销"
                        } else "身份不存在"
                    })
                    revoke = null
                }, modifier = Modifier.testTag("remote-pairing-confirm-revoke")) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("取消") } },
        )
    }
}
