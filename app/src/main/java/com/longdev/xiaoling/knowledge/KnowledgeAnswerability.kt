package com.longdev.xiaoling.knowledge

import org.json.JSONObject
import java.util.Locale

/**
 * long: 模型只能在受限枚举内描述候选文档是否真正回答问题；无法确定时保留 UNKNOWN，不能把未知当成拒绝或接受。
 */
enum class KnowledgeAnswerabilityVerdict {
    ANSWERED,
    PARTIALLY_ANSWERED,
    NOT_ANSWERED,
    UNKNOWN,
}

enum class KnowledgeAnswerabilityDecision {
    ACCEPT,
    REJECT,
    UNKNOWN,
}

enum class KnowledgeAnswerabilityFeatureSet(
    val usesConfidence: Boolean,
    val usesEvidenceCoverage: Boolean,
) {
    VERDICT_AND_EXACT_EVIDENCE(
        usesConfidence = false,
        usesEvidenceCoverage = false,
    ),
    VERDICT_EVIDENCE_AND_CONFIDENCE(
        usesConfidence = true,
        usesEvidenceCoverage = false,
    ),
    VERDICT_EVIDENCE_CONFIDENCE_AND_COVERAGE(
        usesConfidence = true,
        usesEvidenceCoverage = true,
    ),
}

data class KnowledgeAnswerabilityModelOutput(
    val verdict: KnowledgeAnswerabilityVerdict,
    val confidence: Double,
    val evidenceQuotes: List<String>,
    val contradictionDetected: Boolean,
    val reasonCode: String,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "answerability 置信度必须是 0 到 1 之间的有限值"
        }
        require(evidenceQuotes.size <= MAX_EVIDENCE_QUOTES) {
            "answerability 证据片段数量超过上限"
        }
        require(evidenceQuotes.all { quote ->
            val normalized = quote.trim()
            normalized.isNotBlank() && normalized.length <= MAX_EVIDENCE_QUOTE_LENGTH
        }) { "answerability 证据片段不能为空或过长" }
        require(reasonCode.matches(REASON_CODE_PATTERN)) {
            "answerability reasonCode 格式无效"
        }
    }

    companion object {
        const val MAX_EVIDENCE_QUOTES = 8
        const val MAX_EVIDENCE_QUOTE_LENGTH = 500
        private val REASON_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

/**
 * long: 这是模型输出的唯一入口。严格要求顶层 JSON 和字段集合，避免把模型附带的解释文字误当成可审计证据。
 */
object KnowledgeAnswerabilityResponseCodec {
    private val REQUIRED_KEYS = setOf(
        "verdict",
        "confidence",
        "evidence_quotes",
        "contradiction_detected",
        "reason_code",
    )

    fun decode(raw: String): KnowledgeAnswerabilityModelOutput {
        val trimmed = raw.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "answerability 响应必须是单个 JSON 对象"
        }
        val json = JSONObject(trimmed)
        val keys = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        require(keys == REQUIRED_KEYS) { "answerability 响应字段集合不符合固定协议" }

        val verdict = runCatching {
            KnowledgeAnswerabilityVerdict.valueOf(
                json.getString("verdict").trim().uppercase(Locale.ROOT),
            )
        }.getOrElse { error("answerability verdict 不在固定枚举内") }
        val confidence = json.getDouble("confidence")
        val quotesJson = json.getJSONArray("evidence_quotes")
        val quotes = buildList(quotesJson.length()) {
            repeat(quotesJson.length()) { index ->
                add(quotesJson.getString(index).trim())
            }
        }
        val contradictionDetected = json.getBoolean("contradiction_detected")
        val reasonCode = json.getString("reason_code").trim().uppercase(Locale.ROOT)

        // long: 语义与证据字段必须互相一致；不一致时直接停止，不能依赖后续阈值“修正”模型协议错误。
        when (verdict) {
            KnowledgeAnswerabilityVerdict.ANSWERED -> require(quotes.isNotEmpty()) {
                "ANSWERED 响应必须提供原文证据片段"
            }
            KnowledgeAnswerabilityVerdict.NOT_ANSWERED,
            KnowledgeAnswerabilityVerdict.UNKNOWN,
            -> require(quotes.isEmpty()) {
                "NOT_ANSWERED/UNKNOWN 响应不能携带证据片段"
            }
            KnowledgeAnswerabilityVerdict.PARTIALLY_ANSWERED -> Unit
        }
        return KnowledgeAnswerabilityModelOutput(
            verdict = verdict,
            confidence = confidence,
            evidenceQuotes = quotes,
            contradictionDetected = contradictionDetected,
            reasonCode = reasonCode,
        )
    }
}

data class KnowledgeAnswerabilityEvidenceMatch(
    val quoteCount: Int,
    val matchedQuoteCount: Int,
    val matchedCharacterCount: Int,
    val candidateCharacterCount: Int,
) {
    init {
        require(quoteCount >= 0) { "answerability 证据片段总数不能为负" }
        require(matchedQuoteCount in 0..quoteCount) { "answerability 匹配证据数量无效" }
        require(matchedCharacterCount >= 0) { "answerability 匹配字符数不能为负" }
        require(candidateCharacterCount >= 0) { "answerability 候选字符数不能为负" }
        require(matchedCharacterCount <= candidateCharacterCount) {
            "answerability 匹配字符数不能超过候选正文长度"
        }
    }

    val coverage: Double
        get() = if (candidateCharacterCount == 0) 0.0 else {
            matchedCharacterCount.toDouble() / candidateCharacterCount
        }
}

/**
 * long: 模型给出的 quote 必须回到候选正文做匹配；只接受原文片段，避免模型凭空生成“证据”。
 */
object KnowledgeAnswerabilityEvidenceMatcher {
    fun match(candidateText: String, quotes: List<String>): KnowledgeAnswerabilityEvidenceMatch {
        val normalizedCandidate = normalize(candidateText)
        if (normalizedCandidate.isBlank() || quotes.isEmpty()) {
            return KnowledgeAnswerabilityEvidenceMatch(
                quoteCount = quotes.distinct().size,
                matchedQuoteCount = 0,
                matchedCharacterCount = 0,
                candidateCharacterCount = normalizedCandidate.length,
            )
        }
        val intervals = mutableListOf<IntRange>()
        var matchedQuoteCount = 0
        quotes.map(::normalize).filter(String::isNotBlank).distinct().forEach { quote ->
            val start = normalizedCandidate.indexOf(quote)
            if (start >= 0) {
                matchedQuoteCount += 1
                intervals += start until (start + quote.length)
            }
        }
        val mergedLength = intervals
            .sortedBy { it.first }
            .fold(mutableListOf<IntRange>()) { merged, interval ->
                val previous = merged.lastOrNull()
                if (previous != null && interval.first <= previous.last + 1) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, interval.last)
                } else {
                    merged += interval
                }
                merged
            }
            .sumOf { range -> range.last - range.first + 1 }
        return KnowledgeAnswerabilityEvidenceMatch(
            quoteCount = quotes.map(::normalize).filter(String::isNotBlank).distinct().size,
            matchedQuoteCount = matchedQuoteCount,
            matchedCharacterCount = mergedLength,
            candidateCharacterCount = normalizedCandidate.length,
        )
    }

    private fun normalize(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
}

/**
 * long: 校准样本和线上 shadow measurement 共享同一套决策语义，但只有离线校准样本允许携带人工真值标签。
 */
interface KnowledgeAnswerabilityAssessment {
    val verdict: KnowledgeAnswerabilityVerdict
    val confidence: Double
    val evidenceQuoteCount: Int
    val matchedEvidenceQuoteCount: Int
    val evidenceCoverage: Double
    val contradictionDetected: Boolean
    val reasonCode: String

    fun decision(
        featureSet: KnowledgeAnswerabilityFeatureSet,
        minimumConfidence: Double?,
        minimumEvidenceCoverage: Double?,
    ): KnowledgeAnswerabilityDecision {
        if (!confidence.isFinite() || !evidenceCoverage.isFinite()) {
            return KnowledgeAnswerabilityDecision.UNKNOWN
        }
        if (verdict == KnowledgeAnswerabilityVerdict.UNKNOWN) {
            return KnowledgeAnswerabilityDecision.UNKNOWN
        }
        // long: 只有完整回答、无矛盾且所有原文证据都能在候选中找到时才允许接受；部分回答必须回到人工/后续路径。
        if (verdict != KnowledgeAnswerabilityVerdict.ANSWERED || contradictionDetected) {
            return KnowledgeAnswerabilityDecision.REJECT
        }
        if (evidenceQuoteCount <= 0 || matchedEvidenceQuoteCount != evidenceQuoteCount) {
            return KnowledgeAnswerabilityDecision.REJECT
        }
        if (featureSet.usesConfidence) {
            if (minimumConfidence == null) return KnowledgeAnswerabilityDecision.UNKNOWN
            if (confidence < minimumConfidence) return KnowledgeAnswerabilityDecision.REJECT
        }
        if (featureSet.usesEvidenceCoverage) {
            if (minimumEvidenceCoverage == null) return KnowledgeAnswerabilityDecision.UNKNOWN
            if (evidenceCoverage < minimumEvidenceCoverage) return KnowledgeAnswerabilityDecision.REJECT
        }
        return KnowledgeAnswerabilityDecision.ACCEPT
    }
}

data class KnowledgeAnswerabilityObservation(
    val caseId: String,
    val label: KnowledgeRelevanceLabel,
    override val verdict: KnowledgeAnswerabilityVerdict,
    override val confidence: Double,
    override val evidenceQuoteCount: Int,
    override val matchedEvidenceQuoteCount: Int,
    override val evidenceCoverage: Double,
    override val contradictionDetected: Boolean,
    override val reasonCode: String,
) : KnowledgeAnswerabilityAssessment {
    init {
        require(caseId.isNotBlank()) { "answerability 用例 ID 不能为空" }
        validateKnowledgeAnswerabilityAssessment(
            confidence = confidence,
            evidenceQuoteCount = evidenceQuoteCount,
            matchedEvidenceQuoteCount = matchedEvidenceQuoteCount,
            evidenceCoverage = evidenceCoverage,
            reasonCode = reasonCode,
        )
    }

    companion object {
        fun unknown(
            caseId: String,
            label: KnowledgeRelevanceLabel,
            reasonCode: String = "UNKNOWN_OUTPUT",
        ): KnowledgeAnswerabilityObservation = KnowledgeAnswerabilityObservation(
            caseId = caseId,
            label = label,
            verdict = KnowledgeAnswerabilityVerdict.UNKNOWN,
            confidence = 0.0,
            evidenceQuoteCount = 0,
            matchedEvidenceQuoteCount = 0,
            evidenceCoverage = 0.0,
            contradictionDetected = false,
            reasonCode = reasonCode,
        )

        fun fromModelOutput(
            caseId: String,
            label: KnowledgeRelevanceLabel,
            candidateText: String,
            output: KnowledgeAnswerabilityModelOutput,
        ): KnowledgeAnswerabilityObservation {
            val fields = knowledgeAnswerabilityAssessmentFields(
                candidateText = candidateText,
                output = output,
            )
            return KnowledgeAnswerabilityObservation(
                caseId = caseId,
                label = label,
                verdict = fields.verdict,
                confidence = fields.confidence,
                evidenceQuoteCount = fields.evidenceQuoteCount,
                matchedEvidenceQuoteCount = fields.matchedEvidenceQuoteCount,
                evidenceCoverage = fields.evidenceCoverage,
                contradictionDetected = fields.contradictionDetected,
                reasonCode = fields.reasonCode,
            )
        }
    }
}

/**
 * long: 线上 Judge 只记录来自真实 Run 的无标签测量，避免把未知人工真值伪装成生产观测事实。
 */
data class KnowledgeAnswerabilityShadowMeasurement(
    val sourceRunId: String,
    override val verdict: KnowledgeAnswerabilityVerdict,
    override val confidence: Double,
    override val evidenceQuoteCount: Int,
    override val matchedEvidenceQuoteCount: Int,
    override val evidenceCoverage: Double,
    override val contradictionDetected: Boolean,
    override val reasonCode: String,
) : KnowledgeAnswerabilityAssessment {
    init {
        require(sourceRunId.isNotBlank()) { "answerability shadow 来源 Run ID 不能为空" }
        validateKnowledgeAnswerabilityAssessment(
            confidence = confidence,
            evidenceQuoteCount = evidenceQuoteCount,
            matchedEvidenceQuoteCount = matchedEvidenceQuoteCount,
            evidenceCoverage = evidenceCoverage,
            reasonCode = reasonCode,
        )
    }

    companion object {
        fun fromModelOutput(
            sourceRunId: String,
            candidateText: String,
            output: KnowledgeAnswerabilityModelOutput,
        ): KnowledgeAnswerabilityShadowMeasurement {
            val fields = knowledgeAnswerabilityAssessmentFields(
                candidateText = candidateText,
                output = output,
            )
            return KnowledgeAnswerabilityShadowMeasurement(
                sourceRunId = sourceRunId,
                verdict = fields.verdict,
                confidence = fields.confidence,
                evidenceQuoteCount = fields.evidenceQuoteCount,
                matchedEvidenceQuoteCount = fields.matchedEvidenceQuoteCount,
                evidenceCoverage = fields.evidenceCoverage,
                contradictionDetected = fields.contradictionDetected,
                reasonCode = fields.reasonCode,
            )
        }
    }
}

private data class KnowledgeAnswerabilityAssessmentFields(
    val verdict: KnowledgeAnswerabilityVerdict,
    val confidence: Double,
    val evidenceQuoteCount: Int,
    val matchedEvidenceQuoteCount: Int,
    val evidenceCoverage: Double,
    val contradictionDetected: Boolean,
    val reasonCode: String,
)

/**
 * long: 离线校准和线上 shadow 必须用完全相同的证据回查结果，避免两条路径因重复映射而产生决策漂移。
 */
private fun knowledgeAnswerabilityAssessmentFields(
    candidateText: String,
    output: KnowledgeAnswerabilityModelOutput,
): KnowledgeAnswerabilityAssessmentFields {
    val match = KnowledgeAnswerabilityEvidenceMatcher.match(
        candidateText = candidateText,
        quotes = output.evidenceQuotes,
    )
    return KnowledgeAnswerabilityAssessmentFields(
        verdict = output.verdict,
        confidence = output.confidence,
        evidenceQuoteCount = match.quoteCount,
        matchedEvidenceQuoteCount = match.matchedQuoteCount,
        evidenceCoverage = match.coverage.coerceIn(0.0, 1.0),
        contradictionDetected = output.contradictionDetected,
        reasonCode = output.reasonCode,
    )
}

private val KNOWLEDGE_ANSWERABILITY_REASON_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

private fun validateKnowledgeAnswerabilityAssessment(
    confidence: Double,
    evidenceQuoteCount: Int,
    matchedEvidenceQuoteCount: Int,
    evidenceCoverage: Double,
    reasonCode: String,
) {
    require(confidence.isFinite() && confidence in 0.0..1.0) {
        "answerability 评估置信度必须是 0 到 1 之间的有限值"
    }
    require(evidenceQuoteCount >= 0) { "answerability 评估证据片段数量不能为负" }
    require(matchedEvidenceQuoteCount in 0..evidenceQuoteCount) {
        "answerability 评估匹配证据数量无效"
    }
    require(evidenceCoverage.isFinite() && evidenceCoverage in 0.0..1.0) {
        "answerability 评估证据覆盖率必须是 0 到 1 之间的有限值"
    }
    require(reasonCode.matches(KNOWLEDGE_ANSWERABILITY_REASON_CODE_PATTERN)) {
        "answerability 评估 reasonCode 无效"
    }
}

data class KnowledgeAnswerabilityJudgeIdentity(
    val providerId: String,
    val model: String,
    val configurationFingerprint: String,
    val promptVersion: String,
) {
    init {
        require(providerId.isNotBlank()) { "answerability judge Provider ID 不能为空" }
        require(model.isNotBlank()) { "answerability judge 模型不能为空" }
        require(configurationFingerprint.isNotBlank()) { "answerability judge 配置指纹不能为空" }
        require(promptVersion.isNotBlank()) { "answerability judge prompt 版本不能为空" }
    }
}

data class KnowledgeAnswerabilityDatasetIdentity(
    val judgeIdentity: KnowledgeAnswerabilityJudgeIdentity,
    val datasetVersion: String,
) {
    init {
        require(datasetVersion.isNotBlank()) { "answerability 数据集版本不能为空" }
    }
}

data class KnowledgeAnswerabilityEvaluation(
    val featureSet: KnowledgeAnswerabilityFeatureSet,
    val minimumConfidence: Double?,
    val minimumEvidenceCoverage: Double?,
    val positiveAcceptanceRate: Double,
    val nearNegativeRejectionRate: Double,
    val farNegativeRejectionRate: Double,
    val decisionStableRate: Double,
    val knownDecisionRate: Double,
    val unknownRate: Double,
    val balancedAccuracy: Double,
) {
    fun meets(criteria: KnowledgeAnswerabilityCriteria): Boolean =
        positiveAcceptanceRate >= criteria.minimumPositiveAcceptanceRate &&
            nearNegativeRejectionRate >= criteria.minimumNearNegativeRejectionRate &&
            farNegativeRejectionRate >= criteria.minimumFarNegativeRejectionRate &&
            decisionStableRate >= criteria.minimumDecisionStableRate &&
            knownDecisionRate >= criteria.minimumKnownDecisionRate
}

data class KnowledgeAnswerabilityGate(
    val featureSet: KnowledgeAnswerabilityFeatureSet,
    val minimumConfidence: Double?,
    val minimumEvidenceCoverage: Double?,
    val calibrationPositiveAcceptanceRate: Double,
    val calibrationNearNegativeRejectionRate: Double,
    val calibrationFarNegativeRejectionRate: Double,
    val calibrationDecisionStableRate: Double,
    val calibrationKnownDecisionRate: Double,
    val calibrationUnknownRate: Double,
    val calibrationBalancedAccuracy: Double,
) {
    init {
        require((minimumConfidence != null) == featureSet.usesConfidence) {
            "answerability 门禁置信度阈值与特征族不一致"
        }
        require((minimumEvidenceCoverage != null) == featureSet.usesEvidenceCoverage) {
            "answerability 门禁证据覆盖阈值与特征族不一致"
        }
        listOfNotNull(minimumConfidence, minimumEvidenceCoverage).forEach { threshold ->
            require(threshold.isFinite() && threshold in 0.0..1.0) {
                "answerability 门禁阈值必须在 0 到 1 之间"
            }
        }
    }

    fun accepts(assessment: KnowledgeAnswerabilityAssessment): Boolean =
        assessment.decision(featureSet, minimumConfidence, minimumEvidenceCoverage) ==
            KnowledgeAnswerabilityDecision.ACCEPT
}

data class KnowledgeAnswerabilityCriteria(
    val minimumPositiveAcceptanceRate: Double,
    val minimumNearNegativeRejectionRate: Double,
    val minimumFarNegativeRejectionRate: Double,
    val minimumDecisionStableRate: Double,
    val minimumKnownDecisionRate: Double,
) {
    init {
        listOf(
            minimumPositiveAcceptanceRate,
            minimumNearNegativeRejectionRate,
            minimumFarNegativeRejectionRate,
            minimumDecisionStableRate,
            minimumKnownDecisionRate,
        ).forEach { value ->
            require(value.isFinite() && value in 0.0..1.0) {
                "answerability 预注册比例必须在 0 到 1 之间"
            }
        }
    }
}

data class KnowledgeAnswerabilityReport(
    val calibrationIdentity: KnowledgeAnswerabilityDatasetIdentity,
    val validationIdentity: KnowledgeAnswerabilityDatasetIdentity,
    val calibrationGates: Map<KnowledgeAnswerabilityFeatureSet, KnowledgeAnswerabilityGate>,
    val validationEvaluations: Map<KnowledgeAnswerabilityFeatureSet, KnowledgeAnswerabilityEvaluation>,
)

/**
 * long: 第92阶段只冻结 answerability 证据阈值并验证独立数据；它不读取 Room、不修改召回，也不生成生产 enforcement 决策。
 */
object KnowledgeAnswerabilityPolicy {
    fun compare(
        calibrationIdentity: KnowledgeAnswerabilityDatasetIdentity,
        validationIdentity: KnowledgeAnswerabilityDatasetIdentity,
        calibrationSamples: List<KnowledgeAnswerabilityObservation>,
        validationSamples: List<KnowledgeAnswerabilityObservation>,
    ): KnowledgeAnswerabilityReport {
        validateIdentities(calibrationIdentity, validationIdentity)
        val gates = selectCalibrationGates(calibrationSamples)
        return KnowledgeAnswerabilityReport(
            calibrationIdentity = calibrationIdentity,
            validationIdentity = validationIdentity,
            calibrationGates = gates,
            validationEvaluations = evaluateFrozenGates(gates, validationSamples),
        )
    }

    fun selectCalibrationGates(
        samples: List<KnowledgeAnswerabilityObservation>,
    ): Map<KnowledgeAnswerabilityFeatureSet, KnowledgeAnswerabilityGate> {
        validateSamples(samples, "校准")
        return KnowledgeAnswerabilityFeatureSet.entries.associateWith { featureSet ->
            val evaluations = thresholdCandidates(featureSet, samples).map { thresholds ->
                evaluate(featureSet, thresholds.first, thresholds.second, samples)
            }
            val best = evaluations.maxWithOrNull(::compareCandidates)
                ?: error("answerability 门禁候选不能为空")
            KnowledgeAnswerabilityGate(
                featureSet = featureSet,
                minimumConfidence = best.minimumConfidence,
                minimumEvidenceCoverage = best.minimumEvidenceCoverage,
                calibrationPositiveAcceptanceRate = best.positiveAcceptanceRate,
                calibrationNearNegativeRejectionRate = best.nearNegativeRejectionRate,
                calibrationFarNegativeRejectionRate = best.farNegativeRejectionRate,
                calibrationDecisionStableRate = best.decisionStableRate,
                calibrationKnownDecisionRate = best.knownDecisionRate,
                calibrationUnknownRate = best.unknownRate,
                calibrationBalancedAccuracy = best.balancedAccuracy,
            )
        }
    }

    fun evaluateFrozenGates(
        gates: Map<KnowledgeAnswerabilityFeatureSet, KnowledgeAnswerabilityGate>,
        samples: List<KnowledgeAnswerabilityObservation>,
    ): Map<KnowledgeAnswerabilityFeatureSet, KnowledgeAnswerabilityEvaluation> {
        validateSamples(samples, "验证")
        require(gates.keys == KnowledgeAnswerabilityFeatureSet.entries.toSet()) {
            "answerability 冻结门禁必须覆盖全部特征族"
        }
        return KnowledgeAnswerabilityFeatureSet.entries.associateWith { featureSet ->
            val gate = gates.getValue(featureSet)
            evaluate(featureSet, gate.minimumConfidence, gate.minimumEvidenceCoverage, samples)
        }
    }

    private fun thresholdCandidates(
        featureSet: KnowledgeAnswerabilityFeatureSet,
        samples: List<KnowledgeAnswerabilityObservation>,
    ): List<Pair<Double?, Double?>> {
        val confidenceThresholds = if (featureSet.usesConfidence) {
            samples.map { it.confidence }.distinct().sorted().map { it as Double? }
        } else {
            listOf(null)
        }
        val coverageThresholds = if (featureSet.usesEvidenceCoverage) {
            samples.map { it.evidenceCoverage }.distinct().sorted().map { it as Double? }
        } else {
            listOf(null)
        }
        return confidenceThresholds.flatMap { confidence ->
            coverageThresholds.map { coverage -> confidence to coverage }
        }
    }

    private fun evaluate(
        featureSet: KnowledgeAnswerabilityFeatureSet,
        minimumConfidence: Double?,
        minimumEvidenceCoverage: Double?,
        samples: List<KnowledgeAnswerabilityObservation>,
    ): KnowledgeAnswerabilityEvaluation {
        val decisions = samples.map { sample ->
            sample to sample.decision(featureSet, minimumConfidence, minimumEvidenceCoverage)
        }
        val positive = decisions.filter { it.first.label == KnowledgeRelevanceLabel.POSITIVE }
        val nearNegative = decisions.filter { it.first.label == KnowledgeRelevanceLabel.NEAR_NEGATIVE }
        val farNegative = decisions.filter { it.first.label == KnowledgeRelevanceLabel.FAR_NEGATIVE }
        val positiveAcceptanceRate = positive.count { it.second == KnowledgeAnswerabilityDecision.ACCEPT }
            .toRate(positive.size)
        val nearNegativeRejectionRate = nearNegative.count { it.second == KnowledgeAnswerabilityDecision.REJECT }
            .toRate(nearNegative.size)
        val farNegativeRejectionRate = farNegative.count { it.second == KnowledgeAnswerabilityDecision.REJECT }
            .toRate(farNegative.size)
        val knownDecisionRate = decisions.count { it.second != KnowledgeAnswerabilityDecision.UNKNOWN }
            .toRate(decisions.size)
        val unknownRate = 1.0 - knownDecisionRate
        val decisionsByCase = decisions.groupBy { it.first.caseId }
        val decisionStableRate = decisionsByCase.values.count { caseDecisions ->
            caseDecisions.map { it.second }.distinct().size == 1
        }.toRate(decisionsByCase.size)
        val balancedAccuracy = (
            positiveAcceptanceRate + nearNegativeRejectionRate + farNegativeRejectionRate
            ) / KnowledgeRelevanceLabel.entries.size
        return KnowledgeAnswerabilityEvaluation(
            featureSet = featureSet,
            minimumConfidence = minimumConfidence,
            minimumEvidenceCoverage = minimumEvidenceCoverage,
            positiveAcceptanceRate = positiveAcceptanceRate,
            nearNegativeRejectionRate = nearNegativeRejectionRate,
            farNegativeRejectionRate = farNegativeRejectionRate,
            decisionStableRate = decisionStableRate,
            knownDecisionRate = knownDecisionRate,
            unknownRate = unknownRate,
            balancedAccuracy = balancedAccuracy,
        )
    }

    private fun compareCandidates(
        left: KnowledgeAnswerabilityEvaluation,
        right: KnowledgeAnswerabilityEvaluation,
    ): Int {
        val primary = compareValuesBy(
            left,
            right,
            { it.balancedAccuracy },
            { it.positiveAcceptanceRate },
            { it.nearNegativeRejectionRate },
            { it.farNegativeRejectionRate },
            { it.knownDecisionRate },
            { it.decisionStableRate },
        )
        if (primary != 0) return primary
        val confidenceComparison = compareNullable(left.minimumConfidence, right.minimumConfidence)
        if (confidenceComparison != 0) return confidenceComparison
        return compareNullable(left.minimumEvidenceCoverage, right.minimumEvidenceCoverage)
    }

    private fun compareNullable(left: Double?, right: Double?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> compareValues(left, right)
    }

    private fun validateSamples(
        samples: List<KnowledgeAnswerabilityObservation>,
        phase: String,
    ) {
        require(samples.isNotEmpty()) { "$phase answerability 样本不能为空" }
        require(samples.all { sample ->
            sample.confidence.isFinite() && sample.evidenceCoverage.isFinite()
        }) { "$phase answerability 数值必须是有限值" }
        require(KnowledgeRelevanceLabel.entries.all { label -> samples.any { it.label == label } }) {
            "$phase answerability 必须同时包含正例、近负例和远负例"
        }
        require(samples.groupBy { it.caseId }.values.all { caseSamples ->
            caseSamples.map { it.label }.distinct().size == 1
        }) { "同一 answerability 用例不能跨标签" }
    }

    private fun validateIdentities(
        calibrationIdentity: KnowledgeAnswerabilityDatasetIdentity,
        validationIdentity: KnowledgeAnswerabilityDatasetIdentity,
    ) {
        require(calibrationIdentity.judgeIdentity == validationIdentity.judgeIdentity) {
            "answerability calibration 与 validation 的 judge 身份必须一致"
        }
        require(calibrationIdentity.datasetVersion != validationIdentity.datasetVersion) {
            "answerability calibration 与 validation 数据集必须不同"
        }
    }

    private fun Int.toRate(total: Int): Double = if (total == 0) 0.0 else toDouble() / total
}
