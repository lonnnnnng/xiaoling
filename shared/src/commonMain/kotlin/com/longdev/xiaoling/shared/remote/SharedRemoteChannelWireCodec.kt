package com.longdev.xiaoling.shared.remote

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlinx.serialization.json.*

/** long: v1 wire 与签名字节共用 common 实现；解析成功不替代平台验签、时效、许可、去重或原子入箱。 */
object SharedRemoteChannelWireCodec {
    const val VERSION = 1
    const val MAX_JSON_BYTES = 64 * 1024
    const val MAX_MESSAGES = 8
    const val MAX_CURSOR_CHARS = 1024
    const val MAX_BATCH_AGE_MILLIS = 300_000L
    const val PROOF_TTL_MILLIS = 60_000L
    private val peerKeys = setOf("channelId", "senderId", "keyId")
    private val messageKeys = setOf("channelId", "messageId", "senderId", "conversationKey", "text", "receivedAtMillis", "nonce", "keyId", "signature")
    private val batchKeys = peerKeys + setOf("protocolVersion", "receiverId", "batchId", "requestCursor", "nextCursor", "issuedAtMillis", "expiresAtMillis", "messages", "signature")
    private val pullKeys = peerKeys + setOf("protocolVersion", "operation", "receiverId", "requestCursor")
    private val ackKeys = peerKeys + setOf("protocolVersion", "operation", "receiverId", "batchId", "batchDigestSha256", "nextCursor")
    private val proofKeys = peerKeys + setOf("authVersion", "operation", "senderFingerprintSha256", "endpointSha256", "receiverId", "receiverFingerprintSha256", "issuedAtMillis", "expiresAtMillis", "nonce", "requestDigestSha256", "signature")
    private val manifestKeys = peerKeys + setOf("authVersion", "senderFingerprintSha256", "endpointSha256", "receiverId", "publicKeySpkiBase64", "receiverFingerprintSha256")
    private val id = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    private val keyId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val receiver = Regex("[0-9a-f]{32}")
    private val hash = Regex("[0-9a-f]{64}")

    fun normalizeText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trim()

    fun decodeEnvelope(raw: String): SharedRemoteChannelEnvelope = decode(raw) { envelope(it) }
    fun encodeEnvelope(value: SharedRemoteChannelEnvelope): String = encode(envelopeJson(value))

    fun decodeResponse(raw: String): SharedRemoteChannelPullResponse = decode(raw) { value ->
        if ("protocolVersion" !in value) SharedRemoteChannelPullResponse.Single(envelope(value))
        else {
            require(value.keys == batchKeys); value.version("protocolVersion")
            val messages = value["messages"] as JsonArray
            require(messages.size <= MAX_MESSAGES)
            SharedRemoteChannelPullResponse.Batch(SharedRemoteChannelSignedBatch(
                value.text("channelId"), value.text("senderId"), value.text("keyId"), value.text("receiverId"),
                value.text("batchId"), value.nullableText("requestCursor"), value.nullableText("nextCursor"),
                value.integer("issuedAtMillis"), value.integer("expiresAtMillis"), messages.map { envelope(it as JsonObject) },
                value.text("signature"),
            ).also(::validateBatch))
        }
    }

    fun encodeBatch(value: SharedRemoteChannelSignedBatch): String {
        validateBatch(value)
        return encode(buildJsonObject {
            put("protocolVersion", VERSION); peer(value.channelId, value.senderId, value.keyId)
            put("receiverId", value.receiverId); put("batchId", value.batchId)
            nullable("requestCursor", value.requestCursor); nullable("nextCursor", value.nextCursor)
            put("issuedAtMillis", value.issuedAtMillis); put("expiresAtMillis", value.expiresAtMillis)
            put("messages", JsonArray(value.messages.map(::envelopeJson))); put("signature", value.signature)
        })
    }

    fun validateBatch(value: SharedRemoteChannelSignedBatch) {
        identity(value.channelId, 200); identity(value.senderId, 200)
        require(keyId.matches(value.keyId) && receiver.matches(value.receiverId) && id.matches(value.batchId)) { "批次身份无效" }
        batchCursor(value.requestCursor); batchCursor(value.nextCursor)
        require(value.nextCursor != null) { "批次下一游标不能为空" }
        require(value.issuedAtMillis > 0 && value.expiresAtMillis > value.issuedAtMillis &&
            value.expiresAtMillis - value.issuedAtMillis <= MAX_BATCH_AGE_MILLIS) { "批次有效期无效" }
        require(value.messages.size <= MAX_MESSAGES && value.messages.map { it.messageId.trim() }.distinct().size == value.messages.size &&
            value.messages.map { it.nonce?.trim() }.distinct().size == value.messages.size) { "批次消息身份重复或超限" }
        require(value.messages.isEmpty() || value.nextCursor != value.requestCursor) { "非空批次必须推进游标" }
        require(value.signature.length in 1..128) { "批次签名无效" }
    }

    fun decodeRequest(raw: String): SharedRemoteChannelRequest = decode(raw, ::request)
    fun encodeRequest(value: SharedRemoteChannelRequest): String = encode(requestJson(value))
    fun decodeAckRequest(raw: String): SharedRemoteChannelRequest.Ack = decode(raw, ::bareAck)
    /** long: 兼容裸 ACK 沿用批次游标规则；认证 request 另走更严格的 requestCursor，不扩大旧认证入口。 */
    fun encodeAckRequest(binding: SharedRemoteChannelAckBinding, messageIds: List<String>): String {
        validateAckBinding(binding); validateMessageIds(messageIds)
        return encode(buildJsonObject { ack(binding, "ack"); put("messageIds", JsonArray(messageIds.map(::JsonPrimitive))) })
    }
    fun decodeAcknowledgement(raw: String): SharedRemoteChannelAcknowledgement = decode(raw) {
        require(it.keys == ackKeys + "signature"); it.version("protocolVersion")
        require(it.text("operation") == "acknowledged")
        SharedRemoteChannelAcknowledgement(binding(it).also(::validateAckBinding), it.text("signature"))
    }
    fun encodeAcknowledgement(value: SharedRemoteChannelAcknowledgement): String {
        validateAckBinding(value.binding)
        return encode(buildJsonObject { ack(value.binding, "acknowledged"); put("signature", value.signature) })
    }

    fun decodeProof(raw: String): SharedRemoteChannelReceiverProof = decode(raw, ::proof)
    fun encodeProof(value: SharedRemoteChannelReceiverProof): String = encode(proofJson(value))
    fun decodeSignedRequest(raw: String): SharedRemoteChannelSignedRequest = decode(raw) {
        require(it.keys == setOf("request", "proof"))
        SharedRemoteChannelSignedRequest(request(it["request"] as JsonObject), proof(it["proof"] as JsonObject)).also(::validateBinding)
    }
    fun encodeSignedRequest(value: SharedRemoteChannelSignedRequest): String {
        validateBinding(value)
        return encode(buildJsonObject { put("request", requestJson(value.request)); put("proof", proofJson(value.proof)) })
    }

    fun decodeManifest(raw: String): SharedRemoteChannelReceiverAuthorization = decode(raw) {
        require(it.keys == manifestKeys); it.version("authVersion")
        SharedRemoteChannelReceiverAuthorization(it.text("channelId"), it.text("senderId"), it.text("keyId"),
            it.text("senderFingerprintSha256"), it.text("endpointSha256"), it.text("receiverId"),
            it.text("publicKeySpkiBase64"), it.text("receiverFingerprintSha256")).also(::validateManifest)
    }
    fun encodeManifest(value: SharedRemoteChannelReceiverAuthorization): String {
        validateManifest(value)
        return encode(buildJsonObject {
            put("authVersion", VERSION); peer(value.channelId, value.senderId, value.keyId)
            put("senderFingerprintSha256", value.senderFingerprintSha256); put("endpointSha256", value.endpointSha256)
            put("receiverId", value.receiverId); put("publicKeySpkiBase64", value.publicKeySpkiBase64)
            put("receiverFingerprintSha256", value.receiverFingerprintSha256)
        })
    }

    /** long: 消息只规范化原八字段；时间仍是十进制文本，不能改成 batch/proof 的 int64。 */
    fun canonicalEnvelopeBytes(value: SharedRemoteChannelEnvelope): ByteArray = WireBytes().apply {
        int(8)
        listOf(value.channelId.trim(), value.messageId.trim(), value.senderId.trim(), value.conversationKey.trim(),
            normalizeText(value.text), value.receivedAtMillis.toString(), value.nonce?.trim().orEmpty(), value.keyId?.trim().orEmpty())
            .forEach(::field)
    }.result()

    fun canonicalBatchBytes(value: SharedRemoteChannelSignedBatch): ByteArray = bytes("xiaoling-batch-v1") {
        listOf(value.channelId, value.senderId, value.keyId, value.receiverId, value.batchId).forEach(::field)
        nullable(value.requestCursor); nullable(value.nextCursor)
        long(value.issuedAtMillis); long(value.expiresAtMillis); int(value.messages.size)
        value.messages.forEach { message ->
            val body = canonicalEnvelopeBytes(message); int(body.size); raw(body); field(message.signature.orEmpty())
        }
    }

    /** long: 发送方 ACK 回执域不含消息列表；列表由 receiver request 的独立签名域绑定，两个协议不能混合。 */
    fun canonicalAckBytes(value: SharedRemoteChannelAckBinding): ByteArray = bytes("xiaoling-ack-v1") {
        listOf(value.channelId, value.senderId, value.keyId, value.receiverId, value.batchId, value.batchDigestSha256).forEach(::field)
        nullable(value.nextCursor)
    }

    fun canonicalRequestBytes(value: SharedRemoteChannelRequest): ByteArray {
        validateRequest(value)
        return bytes("xiaoling-receiver-request-v1") {
            when (value) {
                is SharedRemoteChannelRequest.Pull -> {
                    listOf(value.channelId, value.senderId, value.keyId, "pull", value.receiverId).forEach(::field)
                    nullable(value.requestCursor)
                }
                is SharedRemoteChannelRequest.Ack -> {
                    val b = value.binding
                    listOf(b.channelId, b.senderId, b.keyId, "ack", b.receiverId, b.batchId, b.batchDigestSha256).forEach(::field)
                    nullable(b.nextCursor); int(value.messageIds.size); value.messageIds.forEach(::field)
                }
            }
        }
    }

    fun canonicalProofBytes(value: SharedRemoteChannelReceiverProof): ByteArray {
        validateProof(value)
        return bytes("xiaoling-receiver-auth-v1") {
            listOf(value.channelId, value.senderId, value.keyId, value.operation, value.senderFingerprintSha256,
                value.endpointSha256, value.receiverId, value.receiverFingerprintSha256, value.nonce, value.requestDigestSha256).forEach(::field)
            long(value.issuedAtMillis); long(value.expiresAtMillis)
        }
    }

    private fun envelope(value: JsonObject): SharedRemoteChannelEnvelope {
        require(value.keys == messageKeys)
        // long: decoder 只检查 wire 结构，正文/nonce/时效的收件拒绝原因仍由原平台策略逐条决定。
        return SharedRemoteChannelEnvelope(value.text("channelId"), value.text("messageId"), value.text("senderId"),
            value.text("conversationKey"), value.text("text"), value.integer("receivedAtMillis"), value.text("nonce"),
            value.text("keyId"), value.text("signature"))
    }
    private fun envelopeJson(value: SharedRemoteChannelEnvelope): JsonObject = buildJsonObject {
        put("channelId", value.channelId); put("messageId", value.messageId); put("senderId", value.senderId)
        put("conversationKey", value.conversationKey); put("text", value.text); put("receivedAtMillis", value.receivedAtMillis)
        put("nonce", requireNotNull(value.nonce)); put("keyId", requireNotNull(value.keyId)); put("signature", requireNotNull(value.signature))
    }
    private fun request(value: JsonObject): SharedRemoteChannelRequest {
        value.version("protocolVersion")
        return when (value.text("operation")) {
            "pull" -> {
                require(value.keys == pullKeys)
                SharedRemoteChannelRequest.Pull(value.text("channelId"), value.text("senderId"), value.text("keyId"),
                    value.text("receiverId"), value.nullableText("requestCursor"))
            }
            "ack" -> {
                bareAck(value)
            }
            else -> throw IllegalArgumentException("接收请求操作无效")
        }.also(::validateRequest)
    }
    private fun bareAck(value: JsonObject): SharedRemoteChannelRequest.Ack {
        require(value.keys == ackKeys + "messageIds"); value.version("protocolVersion")
        require(value.text("operation") == "ack")
        val ids = value["messageIds"] as JsonArray; require(ids.size <= MAX_MESSAGES)
        val messages = ids.map { (it as JsonPrimitive).also { require(it.isString) }.content }
        validateMessageIds(messages)
        return SharedRemoteChannelRequest.Ack(binding(value).also(::validateAckBinding), messages)
    }
    private fun requestJson(value: SharedRemoteChannelRequest): JsonObject {
        validateRequest(value)
        return buildJsonObject { when (value) {
            is SharedRemoteChannelRequest.Pull -> {
                put("protocolVersion", VERSION); put("operation", "pull"); peer(value.channelId, value.senderId, value.keyId)
                put("receiverId", value.receiverId); nullable("requestCursor", value.requestCursor)
            }
            is SharedRemoteChannelRequest.Ack -> {
                ack(value.binding, "ack"); put("messageIds", JsonArray(value.messageIds.map(::JsonPrimitive)))
            }
        } }
    }
    private fun binding(value: JsonObject) = SharedRemoteChannelAckBinding(value.text("channelId"), value.text("senderId"),
        value.text("keyId"), value.text("receiverId"), value.text("batchId"), value.text("batchDigestSha256"), value.nullableText("nextCursor"))
    private fun JsonObjectBuilder.ack(value: SharedRemoteChannelAckBinding, operation: String) {
        put("protocolVersion", VERSION); put("operation", operation); peer(value.channelId, value.senderId, value.keyId)
        put("receiverId", value.receiverId); put("batchId", value.batchId); put("batchDigestSha256", value.batchDigestSha256)
        nullable("nextCursor", value.nextCursor)
    }
    private fun proof(value: JsonObject): SharedRemoteChannelReceiverProof {
        require(value.keys == proofKeys); value.version("authVersion")
        return SharedRemoteChannelReceiverProof(value.text("channelId"), value.text("senderId"), value.text("keyId"), value.text("operation"),
            value.text("senderFingerprintSha256"), value.text("endpointSha256"), value.text("receiverId"), value.text("receiverFingerprintSha256"),
            value.integer("issuedAtMillis"), value.integer("expiresAtMillis"), value.text("nonce"), value.text("requestDigestSha256"),
            value.text("signature")).also(::validateProof)
    }
    private fun proofJson(value: SharedRemoteChannelReceiverProof): JsonObject {
        validateProof(value)
        return buildJsonObject {
            put("authVersion", VERSION); peer(value.channelId, value.senderId, value.keyId); put("operation", value.operation)
            put("senderFingerprintSha256", value.senderFingerprintSha256); put("endpointSha256", value.endpointSha256)
            put("receiverId", value.receiverId); put("receiverFingerprintSha256", value.receiverFingerprintSha256)
            put("issuedAtMillis", value.issuedAtMillis); put("expiresAtMillis", value.expiresAtMillis)
            put("nonce", value.nonce); put("requestDigestSha256", value.requestDigestSha256); put("signature", value.signature)
        }
    }
    private fun validateRequest(value: SharedRemoteChannelRequest) {
        when (value) {
            is SharedRemoteChannelRequest.Pull -> {
                requestIdentity(value.channelId, value.senderId, value.keyId, value.receiverId); requestCursor(value.requestCursor)
            }
            is SharedRemoteChannelRequest.Ack -> {
                val b = value.binding; requestIdentity(b.channelId, b.senderId, b.keyId, b.receiverId)
                require(id.matches(b.batchId) && hash.matches(b.batchDigestSha256)); requestCursor(b.nextCursor)
                validateMessageIds(value.messageIds)
            }
        }
    }
    private fun validateAckBinding(value: SharedRemoteChannelAckBinding) {
        requestIdentity(value.channelId, value.senderId, value.keyId, value.receiverId)
        require(id.matches(value.batchId) && hash.matches(value.batchDigestSha256)); batchCursor(value.nextCursor)
    }
    private fun validateMessageIds(values: List<String>) {
        require(values.size <= MAX_MESSAGES && values.distinct().size == values.size)
        values.forEach { identity(it, 200) }
    }
    private fun requestIdentity(channel: String, sender: String, key: String, route: String) {
        identity(channel, 200); identity(sender, 200); identity(key, 64)
        require(keyId.matches(key) && receiver.matches(route))
    }
    private fun validateProof(value: SharedRemoteChannelReceiverProof) {
        require(value.operation in setOf("pull", "ack"))
        // long: proof 历史身份规则与 request 不同，keyId 仍是 200 字符身份；不在 codec 迁移时偷偷改签名协议。
        listOf(value.channelId, value.senderId, value.keyId).forEach { identity(it, 200) }
        listOf(value.senderFingerprintSha256, value.endpointSha256, value.receiverFingerprintSha256, value.requestDigestSha256)
            .forEach { require(hash.matches(it)) }
        require(receiver.matches(value.receiverId) && receiver.matches(value.nonce))
        require(value.issuedAtMillis > 0 && value.expiresAtMillis > value.issuedAtMillis && value.expiresAtMillis - value.issuedAtMillis <= PROOF_TTL_MILLIS)
    }
    private fun validateBinding(value: SharedRemoteChannelSignedRequest) {
        val r = value.request; val p = value.proof
        val fields = when (r) {
            is SharedRemoteChannelRequest.Pull -> listOf(r.channelId, r.senderId, r.keyId, r.receiverId, "pull")
            is SharedRemoteChannelRequest.Ack -> r.binding.let { listOf(it.channelId, it.senderId, it.keyId, it.receiverId, "ack") }
        }
        require(fields == listOf(p.channelId, p.senderId, p.keyId, p.receiverId, p.operation)) { "接收请求与 proof 身份不匹配" }
        // long: shared 不计算 SHA-256/验签；digest 与实际请求内容的密码学绑定必须由平台验证，不能因字段相同宣称已认证。
    }
    private fun validateManifest(value: SharedRemoteChannelReceiverAuthorization) {
        requestIdentity(value.channelId, value.senderId, value.keyId, value.receiverId)
        listOf(value.senderFingerprintSha256, value.endpointSha256, value.receiverFingerprintSha256).forEach { require(hash.matches(it)) }
        require(value.publicKeySpkiBase64.isNotEmpty())
    }
    private fun identity(value: String, max: Int) { require(value.isNotEmpty() && value == value.trim() && value.length <= max && '\u0000' !in value) }
    private fun batchCursor(value: String?) { require(value == null || (value.isNotBlank() && value.length <= MAX_CURSOR_CHARS && '\u0000' !in value)) }
    private fun requestCursor(value: String?) { if (value != null) identity(value, MAX_CURSOR_CHARS) }
    private fun JsonObjectBuilder.peer(channel: String, sender: String, key: String) { put("channelId", channel); put("senderId", sender); put("keyId", key) }
    private fun JsonObjectBuilder.nullable(name: String, value: String?) { put(name, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObject.text(name: String): String = (this[name] as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.nullableText(name: String): String? { require(name in this); return if (this[name] == JsonNull) null else text(name) }
    private fun JsonObject.integer(name: String): Long {
        val value = this[name] as JsonPrimitive
        require(!value.isString && Regex("-?(0|[1-9][0-9]*)").matches(value.content))
        return value.content.toLong()
    }
    private fun JsonObject.version(name: String) { require(integer(name) == VERSION.toLong()) }
    private inline fun <T> decode(raw: String, body: (JsonObject) -> T): T = try {
        // long: UTF-8 字节数不会小于有效文本的 UTF-16 长度，先拒绝巨大原文再分配，避免门禁自身无界占用。
        require(raw.length <= MAX_JSON_BYTES)
        require(utf8(raw).size <= MAX_JSON_BYTES); StrictJsonObjectSyntax.validate(raw, MAX_JSON_BYTES)
        body(Json.parseToJsonElement(raw) as JsonObject)
    } catch (_: Exception) { throw IllegalArgumentException("Remote Channel wire 格式无效") }
    private fun encode(value: JsonObject): String = value.toString().also {
        require(utf8(it).size <= MAX_JSON_BYTES) { "Remote Channel wire 过大" }
        // long: 转义后的孤立 surrogate 也会形成无效签名文本，encoder 必须和 raw decoder 使用同一语法门禁。
        StrictJsonObjectSyntax.validate(it, MAX_JSON_BYTES)
    }
    private fun utf8(value: String): ByteArray = try { value.encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw IllegalArgumentException("Remote Channel 文本包含无效 Unicode") }
    private fun bytes(domain: String, body: WireBytes.() -> Unit): ByteArray = WireBytes().apply { field(domain); int(VERSION); body() }.result()
    private class WireBytes {
        private val chunks = mutableListOf<ByteArray>()
        private var size = 0L
        fun raw(value: ByteArray) { size += value.size; require(size <= Int.MAX_VALUE); chunks += value }
        fun int(value: Int) = raw(ByteArray(4) { (value ushr (24 - it * 8)).toByte() })
        fun long(value: Long) = raw(ByteArray(8) { (value ushr (56 - it * 8)).toByte() })
        fun field(value: String) { val raw = utf8(value); int(raw.size); raw(raw) }
        fun nullable(value: String?) { if (value == null) int(-1) else field(value) }
        fun result(): ByteArray = ByteArray(size.toInt()).also { result ->
            var offset = 0; chunks.forEach { it.copyInto(result, offset); offset += it.size }
        }
    }
}
