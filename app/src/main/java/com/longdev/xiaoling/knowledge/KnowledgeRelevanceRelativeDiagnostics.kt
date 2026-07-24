package com.longdev.xiaoling.knowledge

import kotlin.math.sqrt

data class KnowledgeRelevanceRelativeDiagnostics(
    val scoreMean: Double,
    val scoreStandardDeviation: Double,
    val topScoreZScore: Double?,
)

object KnowledgeRelevanceRelativeDiagnosticsPolicy {
    fun evaluate(scores: List<Double>): KnowledgeRelevanceRelativeDiagnostics {
        require(scores.isNotEmpty()) { "相对相关性观测必须包含候选分数" }
        require(scores.all(Double::isFinite)) { "相对相关性候选分数必须是有限值" }

        val scoreMean = scores.average()
        val variance = scores.sumOf { score ->
            val deviation = score - scoreMean
            deviation * deviation
        } / scores.size
        val scoreStandardDeviation = sqrt(variance)
        // long: 绝对 cosine 可能随查询主题整体平移，z-score 只描述首位候选相对同次候选分布的位置；零方差时没有可证明的相对区分度。
        val topScoreZScore = if (scores.size >= 2 && scoreStandardDeviation > MINIMUM_STANDARD_DEVIATION) {
            (scores.max() - scoreMean) / scoreStandardDeviation
        } else {
            null
        }
        return KnowledgeRelevanceRelativeDiagnostics(
            scoreMean = scoreMean,
            scoreStandardDeviation = scoreStandardDeviation,
            topScoreZScore = topScoreZScore,
        )
    }

    private const val MINIMUM_STANDARD_DEVIATION = 1e-12
}
