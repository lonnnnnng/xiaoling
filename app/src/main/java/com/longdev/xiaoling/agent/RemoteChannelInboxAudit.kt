package com.longdev.xiaoling.agent

import java.security.MessageDigest
import java.util.UUID

enum class RemoteChannelInboxDisposition { OPENED, IGNORED, EXPIRED }

/** long: 处理记录只持有消息身份摘要和本机时间，不延长正文、nonce 或远端会话标签的保留期。 */
data class RemoteChannelInboxAuditEntry(
    val messageIdentitySha256: String,
    val acceptedAtMillis: Long,
    val recordedAtMillis: Long,
    val disposition: RemoteChannelInboxDisposition,
    val recordId: String = UUID.randomUUID().toString(),
)

object RemoteChannelInboxAuditPolicy {
    const val MAX_ENTRIES = 128
    private val HASH = Regex("[0-9a-f]{64}")

    fun identityHash(draft: RemoteChannelDraft): String = MessageDigest.getInstance("SHA-256")
        .digest(RemoteChannelPolicy.dedupeKey(draft).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun record(pending: RemoteChannelPendingDraft, disposition: RemoteChannelInboxDisposition, now: Long) =
        RemoteChannelInboxAuditEntry(identityHash(pending.draft), pending.acceptedAtMillis, now, disposition)

    fun validate(entries: List<RemoteChannelInboxAuditEntry>) {
        require(entries.size <= MAX_ENTRIES) { "远程处理记录过多" }
        require(entries.all { HASH.matches(it.messageIdentitySha256) && it.acceptedAtMillis > 0 && it.recordedAtMillis > 0 &&
            it.recordId.length == 36 && runCatching { UUID.fromString(it.recordId).toString() == it.recordId }.getOrDefault(false) }) {
            "远程处理记录无效"
        }
        require(entries.map { it.recordId }.distinct().size == entries.size) {
            "远程处理记录重复"
        }
    }

    fun append(previous: List<RemoteChannelInboxAuditEntry>, entries: List<RemoteChannelInboxAuditEntry>): List<RemoteChannelInboxAuditEntry> {
        // long: 重复记录 ID 不改写已提交结果；独立随机 ID 避免时钟回拨或旧去重窗口滚动后把不同次处理合并。
        // long: 容量满只滚动旧审计，不淘汰正文、去重身份或待 ACK 回执。
        return (previous + entries).distinctBy { it.recordId }
            .takeLast(MAX_ENTRIES).also(::validate)
    }

    fun expire(snapshot: RemoteChannelInboxSnapshot, now: Long): RemoteChannelInboxSnapshot {
        // long: 使用本机入箱时间计算 24 小时；时钟回拨保留正文，避免减法溢出或把发送方时间当作保留期起点。
        val expired = snapshot.pendingDrafts.filter { now >= it.acceptedAtMillis &&
            now - it.acceptedAtMillis >= RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS }
        if (expired.isEmpty()) return snapshot
        return snapshot.copy(pendingDrafts = snapshot.pendingDrafts - expired.toSet(),
            auditEntries = append(snapshot.auditEntries, expired.map { record(it, RemoteChannelInboxDisposition.EXPIRED, now) }))
    }
}
