package com.longdev.xiaoling.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFinalHoldoutCriteria
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFinalHoldoutPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureComparisonPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureComparisonReport
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureDatasetIdentity
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureEvaluation
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureSample
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureSet
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeatureVector
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceLabel
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRawTopScoreFrozenGate
import com.longdev.xiaoling.knowledge.KnowledgeSearchQualityCaseResult
import com.longdev.xiaoling.knowledge.KnowledgeSearchQualityPolicy
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeFeatureComparisonInstrumentedTest {
    @Test
    fun freshCalibrationSelectsFrozenFeatureFamiliesAndEvaluatesIndependentValidation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过特征比较", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过特征比较", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过特征比较", embeddingModel.isNotBlank())

        val calibration = collectDataset(
            dataset = CALIBRATION_DATASET,
            baseUrl = baseUrl,
            apiKey = apiKey,
            embeddingModel = embeddingModel,
        )
        val validation = collectDataset(
            dataset = VALIDATION_DATASET,
            baseUrl = baseUrl,
            apiKey = apiKey,
            embeddingModel = embeddingModel,
        )
        val report = KnowledgeRelevanceFeatureComparisonPolicy.compare(
            calibrationIdentity = KnowledgeRelevanceFeatureDatasetIdentity(
                providerId = PROVIDER_ID,
                model = embeddingModel,
                datasetVersion = CALIBRATION_DATASET.version,
            ),
            validationIdentity = KnowledgeRelevanceFeatureDatasetIdentity(
                providerId = PROVIDER_ID,
                model = embeddingModel,
                datasetVersion = VALIDATION_DATASET.version,
            ),
            calibrationSamples = calibration.samples,
            validationSamples = validation.samples,
        )

        val metrics = JSONObject()
            .put("providerId", PROVIDER_ID)
            .put("model", embeddingModel)
            .put("calibrationDatasetVersion", CALIBRATION_DATASET.version)
            .put("validationDatasetVersion", VALIDATION_DATASET.version)
            .put("calibrationObservations", calibration.samples.size)
            .put("validationObservations", validation.samples.size)
            .put("calibrationRecallAt5", calibration.recallAt5)
            .put("validationRecallAt5", validation.recallAt5)
            .put("featureFamilies", report.toJsonArray())
        println("$METRICS_TAG $metrics")

        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.calibrationGates.keys)
        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.validationEvaluations.keys)
        assertEquals(CALIBRATION_CASES_PER_LABEL * KnowledgeRelevanceLabel.entries.size * QUERY_RUNS, calibration.samples.size)
        assertEquals(VALIDATION_CASES_PER_LABEL * KnowledgeRelevanceLabel.entries.size * QUERY_RUNS, validation.samples.size)
        assertTrue("校准语料 Recall@5 过低", calibration.recallAt5 >= MINIMUM_RECALL_AT_5)
        assertTrue("验证语料 Recall@5 过低", validation.recallAt5 >= MINIMUM_RECALL_AT_5)
        KnowledgeRelevanceFeatureSet.entries.forEach { featureSet ->
            val gate = report.calibrationGates.getValue(featureSet)
            val evaluation = report.validationEvaluations.getValue(featureSet)
            assertEquals(featureSet.features.size, gate.thresholds.size)
            assertEquals(gate.thresholds, evaluation.thresholds)
            assertTrue(gate.calibrationBalancedAccuracy.isFinite())
            assertTrue(evaluation.balancedAccuracy.isFinite())
            assertTrue(evaluation.decisionStableRate.isFinite())
        }
        // long: 只有独立 validation 达到预注册标准的特征族才允许进入冻结候选，防止仅记录指标却放过整体失效。
        assertEquals(
            setOf(
                KnowledgeRelevanceFeatureSet.RAW_TOP_SCORE,
                KnowledgeRelevanceFeatureSet.RAW_TOP_SCORE_AND_MARGIN,
            ),
            report.validationEvaluations.filterValues { evaluation ->
                evaluation.meetsPreRegisteredCriteria()
            }.keys,
        )
    }

    @Test
    fun frozenRawTopScoreValidatesThirdUnseenFinalHoldoutWithoutRetuning() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过 final holdout", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过 final holdout", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过 final holdout", embeddingModel.isNotBlank())

        val holdout = collectDataset(
            dataset = FINAL_HOLDOUT_DATASET,
            baseUrl = baseUrl,
            apiKey = apiKey,
            embeddingModel = embeddingModel,
        )
        val frozenGate = KnowledgeRelevanceRawTopScoreFrozenGate(
            gateVersion = FINAL_GATE_VERSION,
            calibrationIdentity = KnowledgeRelevanceFeatureDatasetIdentity(
                providerId = PROVIDER_ID,
                model = embeddingModel,
                datasetVersion = CALIBRATION_DATASET.version,
            ),
            validationDatasetVersion = VALIDATION_DATASET.version,
            minimumRawTopScore = FROZEN_MINIMUM_RAW_TOP_SCORE,
        )
        val report = KnowledgeRelevanceFinalHoldoutPolicy.evaluate(
            frozenGate = frozenGate,
            holdoutIdentity = KnowledgeRelevanceFeatureDatasetIdentity(
                providerId = PROVIDER_ID,
                model = embeddingModel,
                datasetVersion = FINAL_HOLDOUT_DATASET.version,
            ),
            samples = holdout.samples,
            criteria = KnowledgeRelevanceFinalHoldoutCriteria(
                minimumPositiveAcceptanceRate = MINIMUM_POSITIVE_ACCEPTANCE_RATE,
                minimumNearNegativeRejectionRate = MINIMUM_NEAR_NEGATIVE_REJECTION_RATE,
                minimumFarNegativeRejectionRate = MINIMUM_FAR_NEGATIVE_REJECTION_RATE,
                minimumDecisionStableRate = MINIMUM_DECISION_STABLE_RATE,
            ),
        )
        val rankingGatePassed = holdout.recallAt1 >= MINIMUM_RECALL_AT_1 &&
            holdout.recallAt5 >= FINAL_MINIMUM_RECALL_AT_5 &&
            holdout.meanReciprocalRank >= MINIMUM_MRR &&
            holdout.rankingStableRate >= MINIMUM_RANKING_STABLE_RATE
        val metrics = JSONObject()
            .put("providerId", PROVIDER_ID)
            .put("model", embeddingModel)
            .put("gateVersion", frozenGate.gateVersion)
            .put("calibrationDatasetVersion", frozenGate.calibrationIdentity.datasetVersion)
            .put("validationDatasetVersion", frozenGate.validationDatasetVersion)
            .put("finalHoldoutDatasetVersion", report.holdoutIdentity.datasetVersion)
            .put("minimumRawTopScore", frozenGate.minimumRawTopScore)
            .put("observations", holdout.samples.size)
            .put("positiveAcceptanceRate", report.positiveAcceptanceRate)
            .put("nearNegativeRejectionRate", report.nearNegativeRejectionRate)
            .put("farNegativeRejectionRate", report.farNegativeRejectionRate)
            .put("decisionStableRate", report.decisionStableRate)
            .put("balancedAccuracy", report.balancedAccuracy)
            .put("recallAt1", holdout.recallAt1)
            .put("recallAt5", holdout.recallAt5)
            .put("meanReciprocalRank", holdout.meanReciprocalRank)
            .put("rankingStableRate", holdout.rankingStableRate)
            .put("relevanceGatePassed", report.passed)
            .put("rankingGatePassed", rankingGatePassed)
        println("$FINAL_METRICS_TAG $metrics")

        assertEquals(FINAL_CASES_PER_LABEL * KnowledgeRelevanceLabel.entries.size * QUERY_RUNS, holdout.samples.size)
        assertEquals(FROZEN_MINIMUM_RAW_TOP_SCORE, report.frozenGate.minimumRawTopScore, 0.0)
        assertTrue("final holdout 未通过冻结 raw top1 相关性门禁", report.passed)
        assertTrue("final holdout 未通过预注册排序质量门禁", rankingGatePassed)
    }

    private suspend fun collectDataset(
        dataset: DatasetDefinition,
        baseUrl: String,
        apiKey: String,
        embeddingModel: String,
    ): DatasetObservation {
        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = embeddingModel,
            providerId = PROVIDER_ID,
            embeddingModel = embeddingModel,
        )
        assertTrue(client.fetchModels(config).any { it.equals(embeddingModel, ignoreCase = true) })
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return try {
            val semanticStore = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = OpenAiKnowledgeEmbeddingProvider(PROVIDER_ID, config, client),
            )
            val lexicalStore = RoomKnowledgeDocumentStore(context, database)
            val documentIds = mutableMapOf<String, String>()
            // long: 两套语料分别使用独立内存 Room，避免 validation 查询看到 calibration 的向量或文档。
            dataset.corpus.forEach { entry ->
                val document = semanticStore.importUtf8Document(
                    displayName = entry.fileName,
                    mimeType = "text/markdown",
                    bytes = entry.text.toByteArray(Charsets.UTF_8),
                )
                documentIds[document.id] = entry.fileName
            }
            val observations = dataset.cases.flatMap { queryCase ->
                val lexicalHitCount = lexicalStore.search(queryCase.query, limit = QUALITY_LIMIT).hits.size
                assertEquals("${dataset.version} 查询不得被词法命中虚高", 0, lexicalHitCount)
                (0 until QUERY_RUNS).map { runIndex ->
                    val result = semanticStore.search(
                        query = queryCase.query,
                        limit = QUALITY_LIMIT,
                        sourceRunId = "${dataset.version}-${queryCase.caseId}-$runIndex",
                    )
                    val topScore = requireNotNull(result.retrieval.embeddingTopScore)
                    val secondScore = requireNotNull(result.retrieval.embeddingSecondScore)
                    val margin = requireNotNull(result.retrieval.embeddingScoreMargin)
                    val scoreMean = requireNotNull(result.retrieval.embeddingScoreMean)
                    val scoreStandardDeviation = requireNotNull(result.retrieval.embeddingScoreStandardDeviation)
                    val topScoreZScore = requireNotNull(result.retrieval.embeddingTopScoreZScore)
                    assertEquals(KnowledgeEmbeddingStatus.USED, result.retrieval.embeddingStatus)
                    assertEquals(PROVIDER_ID, result.retrieval.embeddingProviderId)
                    assertEquals(embeddingModel, result.retrieval.embeddingModel)
                    assertEquals(dataset.corpus.size, result.retrieval.embeddingCandidateCount)
                    assertTrue(topScore.isFinite())
                    assertTrue(secondScore.isFinite())
                    assertTrue(margin.isFinite() && margin >= 0.0)
                    assertTrue(scoreMean.isFinite())
                    assertTrue(scoreStandardDeviation.isFinite() && scoreStandardDeviation > 0.0)
                    assertTrue(topScoreZScore.isFinite() && topScoreZScore >= 0.0)
                    assertEquals(topScore - secondScore, margin, 0.0000001)
                    Observation(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        expectedFileName = queryCase.expectedFileName,
                        rankedFileNames = result.hits.map { hit ->
                            documentIds[hit.documentId] ?: hit.documentName
                        },
                        features = KnowledgeRelevanceFeatureVector(
                            rawTopScore = topScore,
                            scoreMargin = margin,
                            topScoreZScore = topScoreZScore,
                        ),
                    )
                }
            }
            val positiveQualityCases = dataset.cases
                .filter { it.label == KnowledgeRelevanceLabel.POSITIVE }
                .map { queryCase ->
                    KnowledgeSearchQualityCaseResult(
                        caseId = queryCase.caseId,
                        relevantDocumentIds = setOf(requireNotNull(queryCase.expectedFileName)),
                        rankedDocumentIdsByRun = observations.filter { it.caseId == queryCase.caseId }
                            .map { it.rankedFileNames },
                        limit = QUALITY_LIMIT,
                    )
                }
            val qualityAt5 = KnowledgeSearchQualityPolicy.evaluate(positiveQualityCases)
            val qualityAt1 = KnowledgeSearchQualityPolicy.evaluate(
                positiveQualityCases.map { qualityCase -> qualityCase.copy(limit = 1) },
            )
            DatasetObservation(
                samples = observations.map { observation ->
                    KnowledgeRelevanceFeatureSample(
                        caseId = observation.caseId,
                        label = observation.label,
                        features = observation.features,
                    )
                },
                recallAt1 = qualityAt1.meanRecallAtK,
                recallAt5 = qualityAt5.meanRecallAtK,
                meanReciprocalRank = qualityAt5.meanReciprocalRank,
                rankingStableRate = qualityAt5.stableRankingRate,
            )
        } finally {
            database.close()
        }
    }

    private fun KnowledgeRelevanceFeatureComparisonReport.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            KnowledgeRelevanceFeatureSet.entries.forEach { featureSet ->
                val gate = calibrationGates.getValue(featureSet)
                val validation = validationEvaluations.getValue(featureSet)
                array.put(
                    JSONObject()
                        .put("featureSet", featureSet.name)
                        .put("thresholds", gate.thresholds.toJson())
                        .put("calibrationBalancedAccuracy", gate.calibrationBalancedAccuracy)
                        .put("calibrationPositiveAcceptanceRate", gate.calibrationPositiveAcceptanceRate)
                        .put("calibrationNearNegativeRejectionRate", gate.calibrationNearNegativeRejectionRate)
                        .put("calibrationFarNegativeRejectionRate", gate.calibrationFarNegativeRejectionRate)
                        .put("validationBalancedAccuracy", validation.balancedAccuracy)
                        .put("validationPositiveAcceptanceRate", validation.positiveAcceptanceRate)
                        .put("validationNearNegativeRejectionRate", validation.nearNegativeRejectionRate)
                        .put("validationFarNegativeRejectionRate", validation.farNegativeRejectionRate)
                        .put("validationDecisionStableRate", validation.decisionStableRate)
                        .put("meetsPreRegisteredCriteria", validation.meetsPreRegisteredCriteria()),
                )
            }
        }

    private fun KnowledgeRelevanceFeatureEvaluation.meetsPreRegisteredCriteria(): Boolean =
        positiveAcceptanceRate >= MINIMUM_POSITIVE_ACCEPTANCE_RATE &&
            nearNegativeRejectionRate >= MINIMUM_NEAR_NEGATIVE_REJECTION_RATE &&
            farNegativeRejectionRate >= MINIMUM_FAR_NEGATIVE_REJECTION_RATE &&
            decisionStableRate >= MINIMUM_DECISION_STABLE_RATE

    private fun Map<com.longdev.xiaoling.knowledge.KnowledgeRelevanceFeature, Double>.toJson(): JSONObject =
        JSONObject().also { json -> forEach { (feature, threshold) -> json.put(feature.name, threshold) } }

    private data class CorpusEntry(val fileName: String, val text: String)

    private data class QueryCase(
        val caseId: String,
        val label: KnowledgeRelevanceLabel,
        val query: String,
        val expectedFileName: String? = null,
    )

    private data class DatasetDefinition(
        val version: String,
        val corpus: List<CorpusEntry>,
        val cases: List<QueryCase>,
    )

    private data class Observation(
        val caseId: String,
        val label: KnowledgeRelevanceLabel,
        val expectedFileName: String?,
        val rankedFileNames: List<String>,
        val features: KnowledgeRelevanceFeatureVector,
    )

    private data class DatasetObservation(
        val samples: List<KnowledgeRelevanceFeatureSample>,
        val recallAt1: Double,
        val recallAt5: Double,
        val meanReciprocalRank: Double,
        val rankingStableRate: Double,
    )

    private companion object {
        const val ARG_BASE_URL = "embeddingProviderBaseUrl"
        const val ARG_API_KEY = "embeddingProviderApiKey"
        const val ARG_MODEL = "embeddingProviderModel"
        const val PROVIDER_ID = "stage85-embedding-feature-comparison"
        const val METRICS_TAG = "XIAOLING_STAGE85_FEATURE_COMPARISON"
        const val FINAL_METRICS_TAG = "XIAOLING_STAGE86_FINAL_HOLDOUT"
        const val QUALITY_LIMIT = 5
        const val QUERY_RUNS = 2
        const val CALIBRATION_CASES_PER_LABEL = 10
        const val VALIDATION_CASES_PER_LABEL = 10
        const val FINAL_CASES_PER_LABEL = 10
        const val MINIMUM_RECALL_AT_5 = 0.8
        const val MINIMUM_RECALL_AT_1 = 0.90
        const val FINAL_MINIMUM_RECALL_AT_5 = 1.0
        const val MINIMUM_MRR = 0.90
        const val MINIMUM_RANKING_STABLE_RATE = 1.0
        const val MINIMUM_POSITIVE_ACCEPTANCE_RATE = 0.90
        const val MINIMUM_NEAR_NEGATIVE_REJECTION_RATE = 0.80
        const val MINIMUM_FAR_NEGATIVE_REJECTION_RATE = 0.90
        const val MINIMUM_DECISION_STABLE_RATE = 1.0
        const val FINAL_GATE_VERSION = "stage85-raw-top1-qwen-v1"
        const val FROZEN_MINIMUM_RAW_TOP_SCORE = 0.6416276358587735

        val CALIBRATION_DATASET = DatasetDefinition(
            version = "stage85-calibration-v1",
            corpus = listOf(
                CorpusEntry("c01-香草盆栽.md", "香草盆栽需要放在光照充足的位置，表层土壤干燥后再浇透。定期摘心可以促进分枝，盆底排水孔要保持通畅。"),
                CorpusEntry("c02-香草扦插.md", "香草扦插时剪取带节点的健康枝条，去掉下部叶片后插入湿润基质。保持散射光和稳定湿度，生根后再逐步增加光照。"),
                CorpusEntry("c03-木工打磨.md", "木工打磨应从较粗砂纸开始，顺着木纹逐级更换细砂纸。每次打磨后清除木屑，最后用侧光检查表面是否仍有划痕。"),
                CorpusEntry("c04-木器上漆.md", "木器上漆前先清洁并封闭木材表面，薄涂多层比一次厚涂更均匀。每层完全干燥后轻轻打磨，再继续涂下一层。"),
                CorpusEntry("c05-路由器重启.md", "家庭路由器出现短暂断网时，可以先记录指示灯状态并重启设备。恢复后检查固件版本、连接设备数量和日志，避免反复盲目重启。"),
                CorpusEntry("c06-无线信道.md", "无线网络拥堵时可扫描附近信道，选择干扰较少的频段和固定信道宽度。调整后在不同房间测试延迟和稳定性，而不是只看瞬时速率。"),
                CorpusEntry("c07-水彩调色.md", "水彩调色先在调色纸上测试颜色比例，控制水量比追求高饱和更重要。保留纸张白色区域，叠加透明薄层可以获得自然的明暗变化。"),
                CorpusEntry("c08-丙烯覆盖.md", "丙烯颜料覆盖深色底时应薄涂并等待干燥，再逐层增加不透明度。画笔含水量和颜料厚度会改变边缘清晰度，混色前要预留干燥时间。"),
                CorpusEntry("c09-阅读笔记.md", "阅读笔记可以记录作者主张、关键证据和自己的疑问。每章结束后用几句话复述逻辑，再把可行动的观点单独整理，避免只摘抄原文。"),
                CorpusEntry("c10-速读训练.md", "速读训练通过扩大视线范围和减少回看提高阅读速度，但不能牺牲理解。可以用定时短文练习，并在结束后回答内容问题检查效果。"),
                CorpusEntry("c11-磨刀角度.md", "磨刀时保持刀刃与磨石的角度稳定，先从粗磨修正缺口，再用细磨石去除毛刺。两侧交替轻压，最后用纸张测试是否形成连续锋刃。"),
                CorpusEntry("c12-刀具收纳.md", "厨房刀具应让刀刃与其他硬物分离，使用刀架或保护套固定位置。收纳前擦干水分，取放时握住刀柄并让刀尖朝向安全方向。"),
                CorpusEntry("c13-露营炉具.md", "露营炉具应放在平整、通风且远离可燃物的位置。点火前检查软管和接口，使用时保持锅具稳定，离开炉具前先关闭阀门。"),
                CorpusEntry("c14-燃料储存.md", "户外燃料要按说明放在阴凉通风处，远离火源和儿童。运输时固定容器并检查密封，使用完毕后记录余量，不把不同燃料混装。"),
                CorpusEntry("c15-雨伞防水.md", "雨伞使用后应完全撑开晾干，再收拢存放，避免湿布长期折叠发霉。伞骨变形时先清除异物，轻微弯曲可缓慢校正。"),
                CorpusEntry("c16-雨衣清洗.md", "雨衣清洗使用温和洗涤剂和冷水，避免强力揉搓防水涂层。清洗后悬挂阴干，不能用高温烘干破坏接缝和表面处理。"),
                CorpusEntry("c17-家庭打印.md", "家庭打印机长期不用时应保持喷头清洁并定期打印测试页。更换墨盒前核对型号，出现条纹时先运行设备自带的清洁和校准流程。"),
                CorpusEntry("c18-纸张选择.md", "打印纸要根据双面、照片或普通文档选择合适克重和涂层。纸张受潮会导致进纸歪斜，开封后应平放并密封保存。"),
                CorpusEntry("c19-拉伸恢复.md", "运动后拉伸以轻微牵拉感为限，保持呼吸自然，不应追求疼痛幅度。针对主要肌群分段进行，结束后补水并观察第二天的疲劳程度。"),
                CorpusEntry("c20-热身动作.md", "训练前热身从低强度活动开始，再加入关节活动和与主训练相似的动态动作。热身应逐步升高心率，不用长时间静态拉伸代替。"),
            ),
            cases = listOf(
                QueryCase("c-positive-herb-pot", KnowledgeRelevanceLabel.POSITIVE, "How should a potted herb be watered, lit, and pruned for branching?", "c01-香草盆栽.md"),
                QueryCase("c-positive-wood-sanding", KnowledgeRelevanceLabel.POSITIVE, "What sanding sequence and inspection method produces a smooth wooden surface?", "c03-木工打磨.md"),
                QueryCase("c-positive-router-restart", KnowledgeRelevanceLabel.POSITIVE, "What should be recorded and checked when restarting a home router after an outage?", "c05-路由器重启.md"),
                QueryCase("c-positive-watercolor", KnowledgeRelevanceLabel.POSITIVE, "How can water amount and transparent layers control watercolor mixing?", "c07-水彩调色.md"),
                QueryCase("c-positive-reading-notes", KnowledgeRelevanceLabel.POSITIVE, "What should useful reading notes capture beyond copied quotations?", "c09-阅读笔记.md"),
                QueryCase("c-positive-sharpening", KnowledgeRelevanceLabel.POSITIVE, "How should a knife angle and pressure be kept stable while sharpening?", "c11-磨刀角度.md"),
                QueryCase("c-positive-camping-stove", KnowledgeRelevanceLabel.POSITIVE, "Where should a camping stove be placed and what should be checked before lighting?", "c13-露营炉具.md"),
                QueryCase("c-positive-umbrella", KnowledgeRelevanceLabel.POSITIVE, "How should a wet umbrella be dried and stored to prevent mildew?", "c15-雨伞防水.md"),
                QueryCase("c-positive-printer", KnowledgeRelevanceLabel.POSITIVE, "How can a home printer be maintained when it is used infrequently?", "c17-家庭打印.md"),
                QueryCase("c-positive-stretching", KnowledgeRelevanceLabel.POSITIVE, "What limits and breathing habits make post-exercise stretching restorative?", "c19-拉伸恢复.md"),
                QueryCase("c-near-herb-hormone", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which rooting hormone concentration produces the fastest growth for culinary herb cuttings?"),
                QueryCase("c-near-wood-voc", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which wood finish has the lowest measured volatile-organic-compound emissions?"),
                QueryCase("c-near-wifi-chipset", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which router chipset supports the greatest number of simultaneous wireless clients?"),
                QueryCase("c-near-acrylic-pigment", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which acrylic pigment code has the highest certified lightfastness rating?"),
                QueryCase("c-near-reading-app", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which speed-reading application offers the most accurate subscription analytics?"),
                QueryCase("c-near-knife-alloy", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which steel alloy and heat treatment produce the longest-lasting kitchen knife edge?"),
                QueryCase("c-near-fuel-airline", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which airline regulations allow empty camping fuel containers in checked baggage?"),
                QueryCase("c-near-raincoat-rating", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What hydrostatic-head rating qualifies a raincoat membrane for alpine storms?"),
                QueryCase("c-near-paper-archive", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which archival certification proves that printer paper will remain acid-free for decades?"),
                QueryCase("c-near-warmup-calories", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "How many calories does a ten-minute dynamic warmup burn for different body weights?"),
                QueryCase("c-far-astronomy", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a telescope measure the composition of a distant exoplanet?"),
                QueryCase("c-far-geology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do tectonic plates create deep ocean trenches?"),
                QueryCase("c-far-music", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How did early recording studios synchronize multiple audio tracks?"),
                QueryCase("c-far-linguistics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do phonological changes spread between neighboring languages?"),
                QueryCase("c-far-medicine", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do vaccines train immune memory after exposure?"),
                QueryCase("c-far-archaeology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "What methods date pottery fragments found at an excavation site?"),
                QueryCase("c-far-aviation", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does an aircraft wing generate lift during a climb?"),
                QueryCase("c-far-ocean", KnowledgeRelevanceLabel.FAR_NEGATIVE, "What causes seasonal upwelling along a continental coastline?"),
                QueryCase("c-far-cryptography", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a public key exchange establish a shared secret?"),
                QueryCase("c-far-architecture", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do passive buildings reduce heat gain through their envelope?"),
            ),
        )

        val VALIDATION_DATASET = DatasetDefinition(
            version = "stage85-validation-v1",
            corpus = listOf(
                CorpusEntry("v01-衣物晾晒.md", "衣物晾晒前先按材质分开，厚重衣物翻面并留出通风间距。阴雨天使用除湿设备时不要把衣物贴近热源，高温可能损伤弹性纤维。"),
                CorpusEntry("v02-衣柜防潮.md", "衣柜防潮先减少贴墙摆放和过度塞满，潮湿季节可使用吸湿盒并定期更换。发现霉味时取出衣物通风，清洁干燥后再收纳。"),
                CorpusEntry("v03-书架固定.md", "高书架应使用防倾倒配件固定在承重墙上，重书放在下层并避免超过层板承重。安装后轻推检查稳定性，儿童活动区域尤其要留意。"),
                CorpusEntry("v04-家具搬运.md", "搬运家具前先测量门框和转角，拆下可拆部件并用软材料保护边角。多人抬运时统一口令，落地前确认通道没有脚绊物。"),
                CorpusEntry("v05-家庭照明.md", "家庭照明按阅读、通行和氛围分别布置，主要工作面应避免灯光直射眼睛。多盏灯分担亮度能减少阴影，开关位置要便于夜间找到。"),
                CorpusEntry("v06-灯泡色温.md", "灯泡色温选择取决于空间用途，工作区适合清晰的中性光，卧室可用更温暖的光线。更换前确认灯座规格、功率和调光兼容性。"),
                CorpusEntry("v07-电子表格备份.md", "重要电子表格按日期保留版本，并把副本同步到不同存储位置。备份后随机打开文件检查公式和附件，不能只依赖同步完成提示。"),
                CorpusEntry("v08-文件命名.md", "文件命名应包含稳定主题、日期和版本信息，避免使用会改变含义的模糊简称。统一分隔符和日期格式能让搜索与排序更加可靠。"),
                CorpusEntry("v09-面试准备.md", "面试准备包括梳理项目经历、练习用事实回答行为问题和了解岗位职责。提前测试摄像头、麦克风与网络，准备几个反问问题。"),
                CorpusEntry("v10-简历排版.md", "简历排版应让职位、时间和成果层级清晰，使用一致的字号与间距。删去无关细节并用量化结果表达贡献，导出后检查分页和字体。"),
                CorpusEntry("v11-阳台遮阳.md", "阳台遮阳可以结合外遮阳帘和植物，先观察一天内阳光角度再确定安装位置。固定件要考虑墙面材质，强风天气及时收起可动部件。"),
                CorpusEntry("v12-窗户通风.md", "窗户通风应根据室外空气质量和温度安排，短时形成对流比长时间开小缝更有效。下雨或室外污染升高时及时关闭并使用过滤设备。"),
                CorpusEntry("v13-玻璃清洁.md", "玻璃清洁先用清水去除浮尘，再用稀释清洁剂配合软布擦拭。沿同一方向收水并及时擦干边缘，避免在强烈阳光下留下水痕。"),
                CorpusEntry("v14-镜面防雾.md", "浴室镜面防雾可改善通风并减少热蒸汽停留，擦拭时使用不掉絮的布。需要长期效果时选择适合镜面的防雾产品并按说明补涂。"),
                CorpusEntry("v15-书籍装帧.md", "书籍装帧先按页序整理纸张，再选择适合厚度的装订方式。上胶或穿线时保持书脊平直，夹紧等待干燥，避免拉扯内页。"),
                CorpusEntry("v16-书页修复.md", "书页小裂口可从背面使用薄纸和适量中性胶修补，先清理灰尘并避免胶水渗透正面。修复后压平干燥，再检查翻页是否顺畅。"),
                CorpusEntry("v17-室内空气.md", "室内空气管理要先识别异味和粉尘来源，定时短时通风并保持过滤设备清洁。烹饪和清洁时增加局部排风，不能用香味掩盖污染。"),
                CorpusEntry("v18-空气净化器.md", "空气净化器应按房间面积选择合适风量，滤网达到更换提示时及时处理。摆放时留出进出风空间，夜间可以使用低噪声档位。"),
                CorpusEntry("v19-旅行清单.md", "旅行清单按证件、药品、电子设备和衣物分类，出发前逐项核对。把关键订单保存离线副本，并为天气变化准备一件备用外套。"),
                CorpusEntry("v20-行李收纳.md", "行李收纳把重物放在靠近轮子或背部的位置，易皱衣物卷起并用袋子分隔。随身包保留一天必需品，液体按规定密封。"),
            ),
            cases = listOf(
                QueryCase("v-positive-drying", KnowledgeRelevanceLabel.POSITIVE, "How should different fabrics be separated and ventilated while drying clothes?", "v01-衣物晾晒.md"),
                QueryCase("v-positive-bookshelf", KnowledgeRelevanceLabel.POSITIVE, "How should a tall bookshelf be secured and loaded to prevent tipping?", "v03-书架固定.md"),
                QueryCase("v-positive-lighting", KnowledgeRelevanceLabel.POSITIVE, "How should household lighting be distributed across work and passage areas?", "v05-家庭照明.md"),
                QueryCase("v-positive-spreadsheet", KnowledgeRelevanceLabel.POSITIVE, "How can important spreadsheets be backed up and checked beyond a sync notice?", "v07-电子表格备份.md"),
                QueryCase("v-positive-interview", KnowledgeRelevanceLabel.POSITIVE, "What should be prepared and tested before a job interview?", "v09-面试准备.md"),
                QueryCase("v-positive-shade", KnowledgeRelevanceLabel.POSITIVE, "How should balcony shade placement account for sun angles and wind?", "v11-阳台遮阳.md"),
                QueryCase("v-positive-glass", KnowledgeRelevanceLabel.POSITIVE, "What sequence cleans glass without leaving dust, streaks, or edge water marks?", "v13-玻璃清洁.md"),
                QueryCase("v-positive-binding", KnowledgeRelevanceLabel.POSITIVE, "How should pages be aligned and clamped when binding a book?", "v15-书籍装帧.md"),
                QueryCase("v-positive-air", KnowledgeRelevanceLabel.POSITIVE, "How should indoor air sources, ventilation, and local exhaust be managed?", "v17-室内空气.md"),
                QueryCase("v-positive-luggage", KnowledgeRelevanceLabel.POSITIVE, "Where should heavy items and daily essentials go when packing luggage?", "v20-行李收纳.md"),
                QueryCase("v-near-closet-fungicide", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which antifungal chemical concentration safely removes a specific mold species from a closet?"),
                QueryCase("v-near-moving-insurance", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which insurance policy covers damage caused by professional furniture movers?"),
                QueryCase("v-near-lighting-code", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which electrical code specifies the maximum number of lighting fixtures on one circuit?"),
                QueryCase("v-near-file-system", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What filename length and reserved-character limits apply to each desktop filesystem?"),
                QueryCase("v-near-resume-ats", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which applicant-tracking vendor ranks resume keywords with the highest accuracy?"),
                QueryCase("v-near-ventilation-code", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which building code mandates a precise outdoor-air exchange rate for apartments?"),
                QueryCase("v-near-mirror-coating", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which patented chemical coating provides the longest bathroom mirror anti-fog lifetime?"),
                QueryCase("v-near-page-standard", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which museum conservation standard approves adhesives for medieval paper repair?"),
                QueryCase("v-near-filter-test", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which laboratory test certifies a purifier filter's exact particle-capture efficiency?"),
                QueryCase("v-near-travel-visa", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which visa category permits remote employment during an extended international trip?"),
                QueryCase("v-far-astronomy", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How are stellar distances estimated with parallax measurements?"),
                QueryCase("v-far-chemistry", KnowledgeRelevanceLabel.FAR_NEGATIVE, "Why do catalysts change reaction rates without being consumed?"),
                QueryCase("v-far-history", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How did ancient trade routes shape the spread of writing systems?"),
                QueryCase("v-far-robotics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a robot arm calculate inverse kinematics for a target pose?"),
                QueryCase("v-far-ecology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do keystone species alter food-web stability in an ecosystem?"),
                QueryCase("v-far-finance", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a bond's duration respond to changes in interest rates?"),
                QueryCase("v-far-seismology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How are earthquake epicenters estimated from arrival-time differences?"),
                QueryCase("v-far-astronautics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "What delta-v budget is needed for a transfer between circular orbits?"),
                QueryCase("v-far-linguistics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do writing systems encode grammatical information in different languages?"),
                QueryCase("v-far-quantum", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does quantum entanglement change the statistics of correlated measurements?"),
            ),
        )

        // long: final holdout 在首次真实运行前固定全新主题与查询，只验证已冻结 raw top1，不允许根据结果替换样本或回调阈值。
        val FINAL_HOLDOUT_DATASET = DatasetDefinition(
            version = "stage86-final-holdout-v1",
            corpus = listOf(
                CorpusEntry("f01-酸种喂养.md", "酸种酵母在室温下应按固定比例补充面粉和水，观察体积、气泡与气味变化。达到峰值后及时使用或转入冷藏，容器要留出膨胀空间。"),
                CorpusEntry("f02-面包割包.md", "面包入炉前用锋利刀片快速割出切口，角度和深度应与面团张力配合。割包后立即入炉并提供初期蒸汽，避免表皮提前干硬。"),
                CorpusEntry("f03-自行车链条.md", "清洁自行车链条时先刷掉松散泥沙，再使用合适清洁剂去除旧油。完全干燥后逐节少量上油，并擦掉外侧多余润滑剂以减少粘尘。"),
                CorpusEntry("f04-轮胎气压.md", "自行车轮胎气压应结合胎宽、载重和路面调整，并在冷胎状态下测量。出发前检查胎壁、异物与慢漏气，不能只依赖手捏判断。"),
                CorpusEntry("f05-育苗播种.md", "室内育苗使用洁净疏松基质，按种子大小控制覆土并保持均匀湿润。出苗后及时增加光照和通风，避免高温积水导致幼苗徒长。"),
                CorpusEntry("f06-幼苗炼苗.md", "移栽前应逐日增加幼苗接触室外风和阳光的时间，同时减少过度浇水。遇到低温或强风先缩短暴露，叶片适应后再定植。"),
                CorpusEntry("f07-照片备份.md", "照片备份应至少保留本机以外的独立副本，并按日期或事件建立稳定目录。抽样打开原图核对可读性，重要相册还应验证文件数量和校验值。"),
                CorpusEntry("f08-照片元数据.md", "整理照片元数据时先保留原始拍摄时间，再补充地点和主题标签。批量修改前制作副本，导出后检查时区是否让照片顺序发生偏移。"),
                CorpusEntry("f09-陶坯干燥.md", "陶坯应在通风但无强风的位置缓慢干燥，厚薄差异大的部位可适当遮盖。定期翻面并检查接缝，过快失水容易产生翘曲和裂纹。"),
                CorpusEntry("f10-陶器施釉.md", "陶器施釉前清除坯体灰尘并搅匀釉浆，通过试片确认厚度和烧成效果。底部接触窑板的位置保持无釉，避免烧成时粘连。"),
                CorpusEntry("f11-茶叶储存.md", "茶叶储存要避光、密封并远离潮气和强烈气味。每次取茶使用干燥工具，分装小罐可以减少主包装反复接触空气。"),
                CorpusEntry("f12-茶汤冲泡.md", "冲泡茶叶时根据叶片类型调整水温、投茶量和浸泡时间。先用少量参数试泡并记录口感，连续冲泡时逐步延长时间。"),
                CorpusEntry("f13-钢笔清洗.md", "钢笔换墨或出水不畅时，用清水反复冲洗笔尖和供墨器，直到排水基本清澈。避免使用热水和强溶剂，完全晾干后再重新上墨。"),
                CorpusEntry("f14-墨水选择.md", "钢笔墨水应确认适用于日常书写笔，含颗粒或高防水配方需要更频繁清洁。不同墨水混用前先彻底洗笔，并观察纸张洇染和干燥时间。"),
                CorpusEntry("f15-猫包适应.md", "让猫适应外出包时先把包长期打开并放入熟悉垫子，用零食鼓励主动进入。逐步练习关门、提起和短距离移动，不在第一次训练就长途出门。"),
                CorpusEntry("f16-猫咪梳毛.md", "给猫梳毛应顺着毛发生长方向从短时段开始，遇到打结不要强拉。梳理时检查皮肤、耳后和腋下，猫明显紧张时暂停。"),
                CorpusEntry("f17-水瓶清洁.md", "重复使用的水瓶每天拆下瓶盖和密封圈清洗，用软刷覆盖瓶底和螺纹。冲净后分开放置并彻底风干，出现持续异味或裂纹时更换部件。"),
                CorpusEntry("f18-滤芯更换.md", "带滤芯水瓶应按使用量和厂家周期检查滤芯，流速明显下降或达到期限时更换。安装新滤芯前按说明冲洗，并记录启用日期。"),
                CorpusEntry("f19-站立办公.md", "站立办公时桌面高度应让肩膀放松、肘部自然弯曲，双脚稳定支撑。坐站交替并定时活动，不能用持续站立替代姿势变化。"),
                CorpusEntry("f20-显示器摆放.md", "显示器上缘接近视线高度，屏幕与眼睛保持舒适距离并避免窗户反光。键盘和鼠标靠近身体摆放，调整后观察颈部是否仍需前伸。"),
            ),
            cases = listOf(
                QueryCase("f-positive-starter", KnowledgeRelevanceLabel.POSITIVE, "How should a sourdough starter be fed, observed at peak activity, and stored?", "f01-酸种喂养.md"),
                QueryCase("f-positive-chain", KnowledgeRelevanceLabel.POSITIVE, "What sequence cleans, dries, and lubricates a bicycle chain without attracting excess dirt?", "f03-自行车链条.md"),
                QueryCase("f-positive-seedling", KnowledgeRelevanceLabel.POSITIVE, "How should indoor seed-starting moisture, covering, light, and ventilation be managed?", "f05-育苗播种.md"),
                QueryCase("f-positive-photo-backup", KnowledgeRelevanceLabel.POSITIVE, "How can photo backups be organized and verified beyond merely copying the files?", "f07-照片备份.md"),
                QueryCase("f-positive-clay", KnowledgeRelevanceLabel.POSITIVE, "How should a clay piece be dried slowly to prevent warping and cracks?", "f09-陶坯干燥.md"),
                QueryCase("f-positive-tea-storage", KnowledgeRelevanceLabel.POSITIVE, "How should tea leaves be protected from light, moisture, odors, and repeated air exposure?", "f11-茶叶储存.md"),
                QueryCase("f-positive-pen-cleaning", KnowledgeRelevanceLabel.POSITIVE, "How should a fountain pen be rinsed and dried when changing ink or fixing poor flow?", "f13-钢笔清洗.md"),
                QueryCase("f-positive-carrier", KnowledgeRelevanceLabel.POSITIVE, "How can a cat be gradually trained to enter and tolerate a travel carrier?", "f15-猫包适应.md"),
                QueryCase("f-positive-bottle", KnowledgeRelevanceLabel.POSITIVE, "Which bottle parts should be removed, brushed, rinsed, and dried during daily cleaning?", "f17-水瓶清洁.md"),
                QueryCase("f-positive-standing", KnowledgeRelevanceLabel.POSITIVE, "How should desk height, foot support, and sitting breaks be arranged for standing work?", "f19-站立办公.md"),
                QueryCase("f-near-yeast-species", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which wild yeast species and bacterial percentages dominate a laboratory-tested sourdough culture?"),
                QueryCase("f-near-chain-rating", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which bicycle chain lubricant has the lowest certified wear coefficient in an independent laboratory test?"),
                QueryCase("f-near-seed-hormone", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What exact plant-hormone concentration maximizes germination for a patented seed variety?"),
                QueryCase("f-near-cloud-guarantee", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which cloud photo provider contract guarantees a specific checksum durability percentage?"),
                QueryCase("f-near-glaze-chemistry", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What measured thermal-expansion coefficient prevents crazing for a specific ceramic glaze formula?"),
                QueryCase("f-near-tea-certificate", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which laboratory certificate proves the exact pesticide-residue level of a named tea harvest?"),
                QueryCase("f-near-nib-alloy", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which nib alloy and hardness rating produces the longest fountain-pen service life?"),
                QueryCase("f-near-airline-carrier", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What exact pet-carrier dimensions does each international airline permit in the cabin?"),
                QueryCase("f-near-filter-microplastic", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which certified test shows the precise microplastic removal rate of a bottle filter?"),
                QueryCase("f-near-ergonomic-standard", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which occupational standard mandates an exact standing-desk elbow angle for every worker?"),
                QueryCase("f-far-volcano", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do pressure and dissolved gases trigger an explosive volcanic eruption?"),
                QueryCase("f-far-satellite", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a satellite maintain attitude using reaction wheels?"),
                QueryCase("f-far-genetics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does meiotic recombination create new combinations of genetic variants?"),
                QueryCase("f-far-economics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do central-bank reserve requirements affect commercial lending?"),
                QueryCase("f-far-meteorology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "Why does wind shear influence the formation of severe thunderstorms?"),
                QueryCase("f-far-anthropology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do kinship systems organize inheritance across different societies?"),
                QueryCase("f-far-mathematics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "Why does a Fourier transform represent a signal with frequency components?"),
                QueryCase("f-far-marine", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do deep-sea organisms generate light through bioluminescence?"),
                QueryCase("f-far-law", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does appellate review differ from a new trial in civil procedure?"),
                QueryCase("f-far-computing", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a database query planner choose between an index scan and a table scan?"),
            ),
        )
    }
}
