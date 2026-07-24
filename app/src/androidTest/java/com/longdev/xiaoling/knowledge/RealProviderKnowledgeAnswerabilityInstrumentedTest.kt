package com.longdev.xiaoling.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 该探针只把 gpt-5.5 的受限 answerability 判断转成独立证据，不接入生产检索、Room、答案链路或 enforcement。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeAnswerabilityInstrumentedTest {
    @Test
    fun explicitJudgeCollectsCalibrationAndValidationEvidence() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val providerId = arguments.getString(ARG_PROVIDER_ID).orEmpty().trim()
        assumeTrue("未显式提供 answerability Judge Base URL，跳过第92阶段探针", baseUrl.isNotBlank())
        assumeTrue("未显式提供 answerability Judge API Key，跳过第92阶段探针", apiKey.isNotBlank())
        assumeTrue("未显式提供 answerability Judge 模型，跳过第92阶段探针", model.isNotBlank())
        assumeTrue("未显式提供 answerability Judge Provider ID，跳过第92阶段探针", providerId.isNotBlank())
        assertEquals(EXPECTED_PROVIDER_ID, providerId)
        assertEquals(EXPECTED_MODEL, model)

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            apiMode = ApiMode.RESPONSES,
            streamingEnabled = false,
            reasoningSummaryEnabled = false,
            temperature = 0.0,
            maxTokens = 220,
        )
        assertTrue("answerability Judge 模型未出现在 /models", client.fetchModels(config).any {
            it.equals(model, ignoreCase = true)
        })

        val judgeIdentity = KnowledgeAnswerabilityJudgeIdentity(
            providerId = providerId,
            model = model,
            configurationFingerprint = KnowledgeRelevanceIdentityFingerprint.forBaseUrl(baseUrl),
            promptVersion = PROMPT_VERSION,
        )
        val calibration = collectDataset(client, config, CALIBRATION_DATASET)
        val validation = collectDataset(client, config, VALIDATION_DATASET)
        val report = KnowledgeAnswerabilityPolicy.compare(
            calibrationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                judgeIdentity = judgeIdentity,
                datasetVersion = CALIBRATION_DATASET.version,
            ),
            validationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                judgeIdentity = judgeIdentity,
                datasetVersion = VALIDATION_DATASET.version,
            ),
            calibrationSamples = calibration.observations,
            validationSamples = validation.observations,
        )
        val criteria = KnowledgeAnswerabilityCriteria(
            minimumPositiveAcceptanceRate = MINIMUM_POSITIVE_ACCEPTANCE_RATE,
            minimumNearNegativeRejectionRate = MINIMUM_NEAR_NEGATIVE_REJECTION_RATE,
            minimumFarNegativeRejectionRate = MINIMUM_FAR_NEGATIVE_REJECTION_RATE,
            minimumDecisionStableRate = MINIMUM_DECISION_STABLE_RATE,
            minimumKnownDecisionRate = MINIMUM_KNOWN_DECISION_RATE,
        )
        val passingFeatureFamilies = report.validationEvaluations
            .filterValues { evaluation -> evaluation.meets(criteria) }
            .keys

        report.validationEvaluations.forEach { (featureSet, evaluation) ->
            val gate = report.calibrationGates.getValue(featureSet)
            println(
                "$FEATURE_METRICS_TAG ${JSONObject()
                    .put("featureSet", featureSet.name)
                    .put("minimumConfidence", gate.minimumConfidence ?: JSONObject.NULL)
                    .put("minimumEvidenceCoverage", gate.minimumEvidenceCoverage ?: JSONObject.NULL)
                    .put("calibrationBalancedAccuracy", gate.calibrationBalancedAccuracy)
                    .put("validationBalancedAccuracy", evaluation.balancedAccuracy)
                    .put("validationPositiveAcceptanceRate", evaluation.positiveAcceptanceRate)
                    .put("validationNearNegativeRejectionRate", evaluation.nearNegativeRejectionRate)
                    .put("validationFarNegativeRejectionRate", evaluation.farNegativeRejectionRate)
                    .put("validationDecisionStableRate", evaluation.decisionStableRate)
                    .put("validationKnownDecisionRate", evaluation.knownDecisionRate)
                    .put("validationUnknownRate", evaluation.unknownRate)
                    .put("meetsPreRegisteredCriteria", evaluation.meets(criteria))}",
            )
        }
        println(
            "$METRICS_TAG ${JSONObject()
                .put("providerId", providerId)
                .put("model", model)
                .put("configurationFingerprint", judgeIdentity.configurationFingerprint)
                .put("promptVersion", PROMPT_VERSION)
                .put("calibrationDatasetVersion", CALIBRATION_DATASET.version)
                .put("validationDatasetVersion", VALIDATION_DATASET.version)
                .put("calibrationObservations", calibration.observations.size)
                .put("validationObservations", validation.observations.size)
                .put("calibrationFailures", calibration.requestOrParseFailures)
                .put("validationFailures", validation.requestOrParseFailures)
                .put("passingFeatureFamilies", JSONArray(passingFeatureFamilies.map { it.name }))
                .put("productionEnforcementEnabled", PRODUCTION_ENFORCEMENT_ENABLED)}",
        )

        assertEquals(EXPECTED_OBSERVATIONS, calibration.observations.size)
        assertEquals(EXPECTED_OBSERVATIONS, validation.observations.size)
        assertEquals(0, calibration.requestOrParseFailures)
        assertEquals(0, validation.requestOrParseFailures)
        assertEquals(KnowledgeAnswerabilityFeatureSet.entries.toSet(), report.calibrationGates.keys)
        assertEquals(KnowledgeAnswerabilityFeatureSet.entries.toSet(), report.validationEvaluations.keys)
        report.validationEvaluations.values.forEach { evaluation ->
            assertTrue(evaluation.balancedAccuracy.isFinite())
            assertTrue(evaluation.decisionStableRate.isFinite())
            assertTrue(evaluation.knownDecisionRate.isFinite())
        }
        // long: 即使某个特征族通过预注册标准，也只能记录为独立证据；本阶段固定保持生产 enforcement 关闭。
        assertFalse("answerability 探针不能开启生产拒绝", PRODUCTION_ENFORCEMENT_ENABLED)
    }

    private suspend fun collectDataset(
        client: OpenAiCompatibleClient,
        config: ProviderRequestConfig,
        dataset: DatasetDefinition,
    ): DatasetObservation {
        val observations = mutableListOf<KnowledgeAnswerabilityObservation>()
        var requestOrParseFailures = 0
        dataset.cases.forEach { queryCase ->
            repeat(QUERY_RUNS) { runIndex ->
                val result = judgeWithRetry(client, config, queryCase)
                val observation = result.output?.let { output ->
                    KnowledgeAnswerabilityObservation.fromModelOutput(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        candidateText = queryCase.candidateText,
                        output = output,
                    )
                } ?: run {
                    requestOrParseFailures += 1
                    KnowledgeAnswerabilityObservation.unknown(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        reasonCode = "UNKNOWN_OUTPUT",
                    )
                }
                observations += observation
                // long: 只打印身份、标签和结构化指标，不打印完整候选正文、模型原文或鉴权信息，避免 Logcat 变成知识/密钥出口。
                println(
                    "$CASE_METRICS_TAG ${JSONObject()
                        .put("datasetVersion", dataset.version)
                        .put("caseId", queryCase.caseId)
                        .put("label", queryCase.label.name)
                        .put("runIndex", runIndex)
                        .put("verdict", observation.verdict.name)
                        .put("confidence", observation.confidence)
                        .put("evidenceQuoteCount", observation.evidenceQuoteCount)
                        .put("matchedEvidenceQuoteCount", observation.matchedEvidenceQuoteCount)
                        .put("evidenceCoverage", observation.evidenceCoverage)
                        .put("contradictionDetected", observation.contradictionDetected)
                        .put("reasonCode", observation.reasonCode)}",
                )
            }
        }
        return DatasetObservation(
            observations = observations,
            requestOrParseFailures = requestOrParseFailures,
        )
    }

    private suspend fun judgeWithRetry(
        client: OpenAiCompatibleClient,
        config: ProviderRequestConfig,
        queryCase: QueryCase,
    ): JudgeAttempt {
        repeat(MAX_ATTEMPTS - 1) {
            val result = runCatching { requestJudge(client, config, queryCase) }
            if (result.isSuccess) return JudgeAttempt(output = result.getOrNull())
        }
        return runCatching { requestJudge(client, config, queryCase) }
            .fold(
                onSuccess = { output -> JudgeAttempt(output = output) },
                onFailure = { error ->
                    // long: 重试只覆盖短暂网络/解析失败；最终仍以 UNKNOWN 进入策略，不能把错误改写成拒绝或接受。
                    println(
                        "$FAILURE_TAG ${JSONObject()
                            .put("caseId", queryCase.caseId)
                            .put("failureType", error::class.simpleName ?: "Unknown")}",
                    )
                    JudgeAttempt(output = null)
                },
            )
    }

    private suspend fun requestJudge(
        client: OpenAiCompatibleClient,
        config: ProviderRequestConfig,
        queryCase: QueryCase,
    ): KnowledgeAnswerabilityModelOutput {
        val response = client.sendMessage(
            config = config,
            messages = listOf(
                RequestMessage(role = "system", content = SYSTEM_PROMPT),
                RequestMessage(
                    role = "user",
                    content = """
                        QUESTION:
                        ${queryCase.question}

                        CANDIDATE DOCUMENT:
                        ${queryCase.candidateText}
                    """.trimIndent(),
                ),
            ),
        )
        return KnowledgeAnswerabilityResponseCodec.decode(response.responseText)
    }

    private data class JudgeAttempt(
        val output: KnowledgeAnswerabilityModelOutput?,
    )

    private data class QueryCase(
        val caseId: String,
        val label: KnowledgeRelevanceLabel,
        val question: String,
        val candidateText: String,
    )

    private data class DatasetDefinition(
        val version: String,
        val cases: List<QueryCase>,
    )

    private data class DatasetObservation(
        val observations: List<KnowledgeAnswerabilityObservation>,
        val requestOrParseFailures: Int,
    )

    private companion object {
        const val ARG_BASE_URL = "answerabilityProviderBaseUrl"
        const val ARG_API_KEY = "answerabilityProviderApiKey"
        const val ARG_MODEL = "answerabilityProviderModel"
        const val ARG_PROVIDER_ID = "answerabilityProviderId"
        const val EXPECTED_PROVIDER_ID = "redmi-answerability-judge-v1"
        const val EXPECTED_MODEL = "gpt-5.5"
        const val PROMPT_VERSION = "stage92-answerability-json-v1"
        const val METRICS_TAG = "XIAOLING_STAGE92_ANSWERABILITY"
        const val FEATURE_METRICS_TAG = "XIAOLING_STAGE92_ANSWERABILITY_FEATURE"
        const val CASE_METRICS_TAG = "XIAOLING_STAGE92_ANSWERABILITY_CASE"
        const val FAILURE_TAG = "XIAOLING_STAGE92_ANSWERABILITY_FAILURE"
        const val QUERY_RUNS = 2
        const val CASES_PER_LABEL = 2
        val EXPECTED_OBSERVATIONS = CASES_PER_LABEL * KnowledgeRelevanceLabel.entries.size * QUERY_RUNS
        const val MAX_ATTEMPTS = 2
        const val MINIMUM_POSITIVE_ACCEPTANCE_RATE = 0.90
        const val MINIMUM_NEAR_NEGATIVE_REJECTION_RATE = 0.80
        const val MINIMUM_FAR_NEGATIVE_REJECTION_RATE = 0.90
        const val MINIMUM_DECISION_STABLE_RATE = 1.0
        const val MINIMUM_KNOWN_DECISION_RATE = 0.90
        const val PRODUCTION_ENFORCEMENT_ENABLED = false

        val SYSTEM_PROMPT = """
            You are a strict evidence judge. Use only the candidate document, never outside knowledge.
            Decide whether the candidate directly answers every material part of the question.
            Return exactly one JSON object with exactly these keys:
            {"verdict":"ANSWERED|PARTIALLY_ANSWERED|NOT_ANSWERED|UNKNOWN","confidence":0.0,"evidence_quotes":["verbatim substring"],"contradiction_detected":false,"reason_code":"DIRECT_EVIDENCE"}
            ANSWERED requires enough direct information for the whole question and at least one verbatim quote.
            PARTIALLY_ANSWERED means only some requested facts are present.
            NOT_ANSWERED means the topic is related but the requested fact is absent.
            UNKNOWN is only for an unreadable or ambiguous candidate.
            evidence_quotes must be exact substrings copied from the candidate document; use [] for NOT_ANSWERED or UNKNOWN.
            reason_code must be uppercase letters, digits, or underscores only.
            Do not use markdown, comments, or any text before or after the JSON object.
        """.trimIndent()

        val CALIBRATION_DATASET = DatasetDefinition(
            version = "stage92-answerability-calibration-v1",
            cases = listOf(
                QueryCase(
                    "s92c-positive-bread",
                    KnowledgeRelevanceLabel.POSITIVE,
                    "How should bread yeast, dough temperature, and proofing be managed before shaping?",
                    "面包发酵需要控制酵母用量、面团温度和醒发时间，观察体积变化后再整形和烘烤。",
                ),
                QueryCase(
                    "s92c-positive-backup",
                    KnowledgeRelevanceLabel.POSITIVE,
                    "What backup pattern protects photos across media and locations, and how should it be checked?",
                    "照片备份采用三份副本、两种介质和一份异地保存，完成后随机打开文件核对完整性。",
                ),
                QueryCase(
                    "s92c-near-cultivar",
                    KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                    "Which wheat cultivar guarantees the tallest loaf at a fixed humidity?",
                    "面包发酵需要控制酵母用量、面团温度和醒发时间，观察体积变化后再整形和烘烤。",
                ),
                QueryCase(
                    "s92c-near-retention",
                    KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                    "Which storage provider guarantees permanent retention of every photo version?",
                    "照片备份采用三份副本、两种介质和一份异地保存，完成后随机打开文件核对完整性。",
                ),
                QueryCase(
                    "s92c-far-astronomy",
                    KnowledgeRelevanceLabel.FAR_NEGATIVE,
                    "How do astronomers measure the rotation of a distant galaxy?",
                    "面包发酵需要控制酵母用量、面团温度和醒发时间，观察体积变化后再整形和烘烤。",
                ),
                QueryCase(
                    "s92c-far-tax",
                    KnowledgeRelevanceLabel.FAR_NEGATIVE,
                    "How are corporate income tax provisions audited at year end?",
                    "照片备份采用三份副本、两种介质和一份异地保存，完成后随机打开文件核对完整性。",
                ),
            ),
        )

        val VALIDATION_DATASET = DatasetDefinition(
            version = "stage92-answerability-validation-v1",
            cases = listOf(
                QueryCase(
                    "s92v-positive-aquarium",
                    KnowledgeRelevanceLabel.POSITIVE,
                    "What precautions make a partial aquarium water change safer for fish and filter bacteria?",
                    "鱼缸换水先检测温度和水质，分批更换并避免一次改变过大，清洁过滤棉时保留有益菌环境。",
                ),
                QueryCase(
                    "s92v-positive-files",
                    KnowledgeRelevanceLabel.POSITIVE,
                    "How should important files be named so that search, sorting, and history recovery remain reliable?",
                    "文件命名包含稳定主题、日期和版本，统一分隔符与日期格式可以帮助搜索、排序和恢复历史文件。",
                ),
                QueryCase(
                    "s92v-near-filter",
                    KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                    "Which aquarium filter brand removes the greatest percentage of dissolved nitrate?",
                    "鱼缸换水先检测温度和水质，分批更换并避免一次改变过大，清洁过滤棉时保留有益菌环境。",
                ),
                QueryCase(
                    "s92v-near-format",
                    KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                    "Which file format guarantees permanent metadata preservation on every operating system?",
                    "文件命名包含稳定主题、日期和版本，统一分隔符与日期格式可以帮助搜索、排序和恢复历史文件。",
                ),
                QueryCase(
                    "s92v-far-geology",
                    KnowledgeRelevanceLabel.FAR_NEGATIVE,
                    "How do tectonic plates form volcanic island arcs?",
                    "鱼缸换水先检测温度和水质，分批更换并避免一次改变过大，清洁过滤棉时保留有益菌环境。",
                ),
                QueryCase(
                    "s92v-far-medicine",
                    KnowledgeRelevanceLabel.FAR_NEGATIVE,
                    "How does immune memory respond after a second exposure to an antigen?",
                    "文件命名包含稳定主题、日期和版本，统一分隔符与日期格式可以帮助搜索、排序和恢复历史文件。",
                ),
            ),
        )
    }
}
