package com.longdev.xiaoling.knowledge

data class KnowledgeSearchQualityCaseResult(
    val caseId: String,
    val relevantDocumentIds: Set<String>,
    val rankedDocumentIdsByRun: List<List<String>>,
    val limit: Int,
) {
    init {
        require(caseId.isNotBlank()) { "检索质量用例 ID 不能为空" }
        require(rankedDocumentIdsByRun.isNotEmpty()) { "检索质量用例至少需要一次结果" }
        require(limit > 0) { "检索质量用例 limit 必须大于 0" }
        require(relevantDocumentIds.all(String::isNotBlank)) { "检索质量相关文档 ID 不能为空" }
    }
}

data class KnowledgeSearchQualityReport(
    val positiveCaseCount: Int,
    val negativeCaseCount: Int,
    val meanRecallAtK: Double,
    val meanReciprocalRank: Double,
    val negativeAccuracy: Double,
    val stableRankingRate: Double,
)

object KnowledgeSearchQualityPolicy {
    fun evaluate(cases: List<KnowledgeSearchQualityCaseResult>): KnowledgeSearchQualityReport {
        if (cases.isEmpty()) {
            return KnowledgeSearchQualityReport(
                positiveCaseCount = 0,
                negativeCaseCount = 0,
                meanRecallAtK = 0.0,
                meanReciprocalRank = 0.0,
                negativeAccuracy = 0.0,
                stableRankingRate = 0.0,
            )
        }
        val positive = cases.filter { it.relevantDocumentIds.isNotEmpty() }
        val negative = cases.filter { it.relevantDocumentIds.isEmpty() }
        val recall = positive.sumOf { result ->
            val ranking = normalizeRanking(result.rankedDocumentIdsByRun.first(), result.limit)
            ranking.count(result.relevantDocumentIds::contains).toDouble() / result.relevantDocumentIds.size
        }
        val reciprocalRank = positive.sumOf { result ->
            val ranking = normalizeRanking(result.rankedDocumentIdsByRun.first(), result.limit)
            val rank = ranking.indexOfFirst(result.relevantDocumentIds::contains)
            if (rank < 0) 0.0 else 1.0 / (rank + 1)
        }
        val negativeHits = negative.count { result ->
            normalizeRanking(result.rankedDocumentIdsByRun.first(), result.limit).isEmpty()
        }
        val stable = cases.count { result ->
            val rankings = result.rankedDocumentIdsByRun.map { normalizeRanking(it, result.limit) }
            rankings.drop(1).all { it == rankings.first() }
        }
        // long: 质量报告只汇总固定语料的可重复事实，不把 Provider 延迟或网络瞬时状态混进离线排序指标。
        return KnowledgeSearchQualityReport(
            positiveCaseCount = positive.size,
            negativeCaseCount = negative.size,
            meanRecallAtK = if (positive.isEmpty()) 0.0 else recall / positive.size,
            meanReciprocalRank = if (positive.isEmpty()) 0.0 else reciprocalRank / positive.size,
            negativeAccuracy = if (negative.isEmpty()) 0.0 else negativeHits.toDouble() / negative.size,
            stableRankingRate = stable.toDouble() / cases.size,
        )
    }

    private fun normalizeRanking(documentIds: List<String>, limit: Int): List<String> =
        documentIds.distinct().take(limit)
}
