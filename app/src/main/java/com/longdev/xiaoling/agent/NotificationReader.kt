package com.longdev.xiaoling.agent

data class AgentNotificationRecord(
    val id: String,
    val appName: String,
    val packageName: String,
    val postedAt: Long,
    val title: String?,
    val content: String?,
    val contentHidden: Boolean,
)

sealed interface NotificationReadResult {
    data class Success(val notification: AgentNotificationRecord) : NotificationReadResult
    data object AccessNotGranted : NotificationReadResult
    data object ListenerDisconnected : NotificationReadResult
    data object NotFound : NotificationReadResult
}

interface NotificationReader {
    fun accessGranted(): Boolean
    fun connected(): Boolean
    suspend fun list(limit: Int): List<AgentNotificationRecord>
    suspend fun get(notificationId: String): NotificationReadResult
}

object UnavailableNotificationReader : NotificationReader {
    override fun accessGranted(): Boolean = false
    override fun connected(): Boolean = false
    override suspend fun list(limit: Int): List<AgentNotificationRecord> = emptyList()
    override suspend fun get(notificationId: String): NotificationReadResult = NotificationReadResult.AccessNotGranted
}

internal object NotificationPrivacyPolicy {
    private val sensitivePatterns = listOf(
        Regex("(?i)(verification|security|login|auth|one[- ]?time|otp|验证码|校验码|动态码|登录码|安全码).{0,12}\\d{4,8}"),
        Regex("(?i)(password|passcode|pin|密码|口令|密钥|api[ _-]?key|token)"),
    )

    fun sanitize(value: CharSequence?): String? {
        val normalized = value
            ?.toString()
            ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(500)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return normalized.takeUnless { text -> sensitivePatterns.any { pattern -> pattern.containsMatchIn(text) } }
    }

    fun sanitizeNotification(title: CharSequence?, content: CharSequence?): Pair<String?, String?> {
        val sanitizedTitle = sanitize(title)
        val sanitizedContent = sanitize(content)
        val sensitive = (title != null && sanitizedTitle == null) || (content != null && sanitizedContent == null)
        return if (sensitive) null to null else sanitizedTitle to sanitizedContent
    }
}
