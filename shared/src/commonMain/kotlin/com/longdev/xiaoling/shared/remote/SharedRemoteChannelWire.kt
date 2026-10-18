package com.longdev.xiaoling.shared.remote

/** long: 共享九字段载荷不携带执行许可；可空认证字段仅保留原内存/HMAC 调用兼容，签名 wire 仍要求字符串。 */
data class SharedRemoteChannelEnvelope(
    val channelId: String, val messageId: String, val senderId: String, val conversationKey: String,
    val text: String, val receivedAtMillis: Long, val nonce: String? = null,
    val keyId: String? = null, val signature: String? = null,
)

data class SharedRemoteChannelSignedBatch(
    val channelId: String, val senderId: String, val keyId: String, val receiverId: String,
    val batchId: String, val requestCursor: String?, val nextCursor: String?,
    val issuedAtMillis: Long, val expiresAtMillis: Long,
    val messages: List<SharedRemoteChannelEnvelope>, val signature: String,
)

sealed interface SharedRemoteChannelPullResponse {
    data class Single(val envelope: SharedRemoteChannelEnvelope) : SharedRemoteChannelPullResponse
    data class Batch(val batch: SharedRemoteChannelSignedBatch) : SharedRemoteChannelPullResponse
}

/** long: ACK 只绑定已安全入箱的收据；messageIds 在接收请求签名中保序，不能推导用户确认或执行成功。 */
data class SharedRemoteChannelAckBinding(
    val channelId: String, val senderId: String, val keyId: String, val receiverId: String,
    val batchId: String, val batchDigestSha256: String, val nextCursor: String?,
)

sealed interface SharedRemoteChannelRequest {
    data class Pull(val channelId: String, val senderId: String, val keyId: String, val receiverId: String,
                    val requestCursor: String?) : SharedRemoteChannelRequest
    data class Ack(val binding: SharedRemoteChannelAckBinding, val messageIds: List<String>) : SharedRemoteChannelRequest
}

data class SharedRemoteChannelAcknowledgement(val binding: SharedRemoteChannelAckBinding, val signature: String)

data class SharedRemoteChannelReceiverProof(
    val channelId: String, val senderId: String, val keyId: String, val operation: String,
    val senderFingerprintSha256: String, val endpointSha256: String, val receiverId: String,
    val receiverFingerprintSha256: String, val issuedAtMillis: Long, val expiresAtMillis: Long,
    val nonce: String, val requestDigestSha256: String, val signature: String,
)

data class SharedRemoteChannelSignedRequest(val request: SharedRemoteChannelRequest, val proof: SharedRemoteChannelReceiverProof)

/** long: 清单仅保存公开登记数据；SPKI 曲线、指纹、公钥许可与私钥操作必须由平台再次核验。 */
data class SharedRemoteChannelReceiverAuthorization(
    val channelId: String, val senderId: String, val keyId: String, val senderFingerprintSha256: String,
    val endpointSha256: String, val receiverId: String, val publicKeySpkiBase64: String,
    val receiverFingerprintSha256: String,
)
