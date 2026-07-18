package com.longdev.xiaoling.agent

enum class AgentMemoryCandidateStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    BLOCKED_SENSITIVE,
    DUPLICATE,
    CONFLICT,
}

enum class AgentMemorySensitiveCategory(val displayName: String) {
    API_KEY("API Key"),
    TOKEN("访问令牌"),
    PASSWORD("密码"),
    BANK_CARD("银行卡号"),
    NATIONAL_ID("身份证号"),
    PHONE_NUMBER("手机号"),
}

data class AgentMemoryCandidateDraft(
    val content: String,
    val normalizedContent: String,
    val type: String,
    val topicKey: String,
    val source: AgentMemorySource,
    val confidence: Double,
    val status: AgentMemoryCandidateStatus,
    val sensitiveCategory: AgentMemorySensitiveCategory? = null,
    val relatedMemoryId: String? = null,
    val displaySummary: String,
)

data class AgentMemoryCandidateRecord(
    val id: String,
    val content: String,
    val normalizedContent: String,
    val type: String,
    val topicKey: String,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val sourceSummary: String,
    val confidence: Double,
    val status: AgentMemoryCandidateStatus,
    val sensitiveCategory: AgentMemorySensitiveCategory?,
    val relatedMemoryId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

interface AgentMemoryCandidateManager {
    suspend fun createCandidate(userText: String, source: AgentMemorySource): AgentMemoryCandidateRecord?
    suspend fun listCandidates(limit: Int = 100): List<AgentMemoryCandidateRecord>
    suspend fun acceptCandidate(candidateId: String): AgentMemoryCandidateRecord?
    suspend fun rejectCandidate(candidateId: String): AgentMemoryCandidateRecord?
}

object AgentMemoryCandidatePolicy {
    private data class SensitiveRule(
        val category: AgentMemorySensitiveCategory,
        val pattern: Regex,
    )

    private val sensitiveRules = listOf(
        SensitiveRule(
            AgentMemorySensitiveCategory.API_KEY,
            Regex("(?i)(?:api[ _-]?key|apikey)\\s*(?:是|为|[:=])?\\s*[a-z0-9_-]{12,}|\\b(?:sk-|ghp_|gho_|github_pat_|xoxb-|xoxp-|AIza|AKIA)[a-z0-9_-]{12,}"),
        ),
        SensitiveRule(
            AgentMemorySensitiveCategory.TOKEN,
            Regex("(?i)(?:access[ _-]?token|token|令牌)\\s*(?:是|为|[:=])?\\s*[a-z0-9._-]{8,}"),
        ),
        SensitiveRule(
            AgentMemorySensitiveCategory.PASSWORD,
            Regex("(?i)(?:password|passwd|密码|口令)\\s*(?:是|为|[:=])?\\s*\\S{6,}"),
        ),
        SensitiveRule(
            AgentMemorySensitiveCategory.NATIONAL_ID,
            Regex("(?:身份证(?:号|号码)?|national[ _-]?id)\\s*(?:是|为|[:=])?\\s*\\d{17}[0-9Xx]"),
        ),
        SensitiveRule(
            AgentMemorySensitiveCategory.BANK_CARD,
            Regex("(?:银行卡(?:号|号码)?|bank[ _-]?card)\\s*(?:是|为|[:=])?\\s*\\d{16,19}"),
        ),
        SensitiveRule(
            AgentMemorySensitiveCategory.PHONE_NUMBER,
            Regex("(?:手机号|手机号码|电话号码|phone)\\s*(?:是|为|[:=])?\\s*1[3-9]\\d{9}", RegexOption.IGNORE_CASE),
        ),
    )

    fun evaluateTurn(
        userText: String,
        source: AgentMemorySource,
        existingMemories: List<AgentMemoryRecord>,
    ): AgentMemoryCandidateDraft? {
        val content = userText.trim().replace(Regex("\\s+"), " ")
        val type = extractType(content) ?: return null
        return assessContent(
            content = content,
            type = type,
            source = source,
            existingMemories = existingMemories,
        )
    }

    fun assessContent(
        content: String,
        type: String,
        source: AgentMemorySource,
        existingMemories: List<AgentMemoryRecord>,
    ): AgentMemoryCandidateDraft {
        val trimmedContent = content.trim().replace(Regex("\\s+"), " ")
        val sensitiveCategory = sensitiveRules.firstOrNull { it.pattern.containsMatchIn(trimmedContent) }?.category
        if (sensitiveCategory != null) {
            // long: 敏感候选只保留命中类别和固定提示；原始值、规范化值、来源摘要都不能落入候选记录，避免管理页或数据库审计反向泄露密钥。
            return AgentMemoryCandidateDraft(
                content = "",
                normalizedContent = "",
                type = type,
                topicKey = "",
                source = source.copy(summary = "检测到敏感内容，原文未保存"),
                confidence = 1.0,
                status = AgentMemoryCandidateStatus.BLOCKED_SENSITIVE,
                sensitiveCategory = sensitiveCategory,
                displaySummary = "检测到${sensitiveCategory.displayName}，未保存原文",
            )
        }

        // long: 先去除空格、标点和大小写差异，再用独立规范化文本判断同一事实，避免用户重复陈述不断产生新记忆。
        val normalized = normalizeContent(trimmedContent)
        val duplicate = existingMemories.firstOrNull { normalizeContent(it.content) == normalized }
        if (duplicate != null) {
            return draft(
                content = trimmedContent,
                normalized = normalized,
                type = type,
                source = source,
                status = AgentMemoryCandidateStatus.DUPLICATE,
                relatedMemoryId = duplicate.id,
            )
        }

        // long: 只有同类型且能识别同一主题时才标记冲突；不确定主题就保留候选，避免错误覆盖或误阻断用户事实。
        val topicKey = topicKey(trimmedContent, type)
        val conflict = topicKey.takeIf { it.isNotBlank() }?.let { key ->
            existingMemories.firstOrNull { memory ->
                memory.type == type && topicKey(memory.content, memory.type) == key
            }
        }
        return draft(
            content = trimmedContent,
            normalized = normalized,
            type = type,
            source = source,
            status = if (conflict == null) AgentMemoryCandidateStatus.PENDING else AgentMemoryCandidateStatus.CONFLICT,
            relatedMemoryId = conflict?.id,
        )
    }

    fun normalizeContent(content: String): String {
        return content.lowercase().filter(Char::isLetterOrDigit)
    }

    fun sensitiveCategoryIn(text: String): AgentMemorySensitiveCategory? {
        // long: 正式记忆的标签和来源摘要也可能携带用户粘贴的凭据；统一复用候选规则，避免只检查正文造成旁路落库。
        return sensitiveRules.firstOrNull { it.pattern.containsMatchIn(text) }?.category
    }

    private fun extractType(content: String): String? {
        val preference = Regex("(?:我(?:很)?(?:喜欢|偏好|习惯|希望|不喜欢|讨厌)|i\\s+(?:prefer|like|dislike))", RegexOption.IGNORE_CASE)
        if (preference.containsMatchIn(content)) return "Preference"
        val profileFact = Regex("(?:请记住|我是|我的.{1,24}?(?:是|为)|remember\\s+that)", RegexOption.IGNORE_CASE)
        return if (profileFact.containsMatchIn(content)) "ProfileFact" else null
    }

    private fun topicKey(content: String, type: String): String {
        val lower = content.lowercase()
        return when {
            Regex("界面|布局|ui|dashboard").containsMatchIn(lower) -> "ui"
            Regex("语言|中文|英文|english|chinese").containsMatchIn(lower) -> "language"
            Regex("回答|回复|输出|格式|response|answer|format").containsMatchIn(lower) -> "response_format"
            Regex("主题|深色|浅色|theme|dark|light").containsMatchIn(lower) -> "theme"
            type == "ProfileFact" -> Regex("我的(.{1,24}?)(?:是|为)")
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::normalizeContent)
                .orEmpty()
            else -> ""
        }
    }

    private fun draft(
        content: String,
        normalized: String,
        type: String,
        source: AgentMemorySource,
        status: AgentMemoryCandidateStatus,
        relatedMemoryId: String?,
    ): AgentMemoryCandidateDraft {
        return AgentMemoryCandidateDraft(
            content = content,
            normalizedContent = normalized,
            type = type,
            topicKey = topicKey(content, type),
            source = source,
            confidence = 0.9,
            status = status,
            relatedMemoryId = relatedMemoryId,
            displaySummary = content,
        )
    }
}
