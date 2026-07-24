package com.longdev.xiaoling.knowledge

import android.os.SystemClock
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus.USED
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * long: 该用例只采集新的跨主题归一化 shadow 证据；它不写正式 Room、不启用生产拒绝，也不把 validation 结果回调为阈值。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeRelevanceCrossTopicNormalizationInstrumentedTest {
    @Test
    fun explicitProviderCollectsCrossTopicNormalizedEvidence() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val providerId = arguments.getString(ARG_PROVIDER_ID).orEmpty().trim()
        assumeTrue("未显式提供正式 Embedding Provider，跳过第91阶段归一化探针", baseUrl.isNotBlank())
        assumeTrue("未显式提供正式 Embedding API Key，跳过第91阶段归一化探针", apiKey.isNotBlank())
        assumeTrue("未显式提供正式 Embedding 模型，跳过第91阶段归一化探针", model.isNotBlank())
        assumeTrue("未显式提供正式 Embedding Provider ID，跳过第91阶段归一化探针", providerId.isNotBlank())

        assertEquals(PRODUCTION_PROVIDER_ID, providerId)
        assertEquals(PRODUCTION_MODEL, model)
        val productionIdentity = KnowledgeRelevanceProductionIdentity(
            providerId = providerId,
            model = model,
            configurationFingerprint = KnowledgeRelevanceIdentityFingerprint.forBaseUrl(baseUrl),
        )
        val calibration = collectDataset(
            dataset = CALIBRATION_DATASET,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
        )
        val validation = collectDataset(
            dataset = VALIDATION_DATASET,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
        )
        val report = KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
            productionIdentity = productionIdentity,
            calibrationIdentity = datasetIdentity(productionIdentity, CALIBRATION_DATASET.version),
            validationIdentity = datasetIdentity(productionIdentity, VALIDATION_DATASET.version),
            calibrationSamples = calibration.samples,
            validationSamples = validation.samples,
        )
        val passingFeatureFamilies = report.validationEvaluations
            .filterValues { evaluation -> evaluation.meetsPreRegisteredCriteria() }
            .keys

        report.validationEvaluations.forEach { (featureSet, evaluation) ->
            val gate = report.calibrationGates.getValue(featureSet)
            println(
                "$FEATURE_METRICS_TAG ${JSONObject()
                    .put("featureSet", featureSet.name)
                    .put("thresholds", gate.thresholds.toJson())
                    .put("calibrationBalancedAccuracy", gate.calibrationBalancedAccuracy)
                    .put("calibrationPositiveAcceptanceRate", gate.calibrationPositiveAcceptanceRate)
                    .put("calibrationNearNegativeRejectionRate", gate.calibrationNearNegativeRejectionRate)
                    .put("calibrationFarNegativeRejectionRate", gate.calibrationFarNegativeRejectionRate)
                    .put("validationBalancedAccuracy", evaluation.balancedAccuracy)
                    .put("validationPositiveAcceptanceRate", evaluation.positiveAcceptanceRate)
                    .put("validationNearNegativeRejectionRate", evaluation.nearNegativeRejectionRate)
                    .put("validationFarNegativeRejectionRate", evaluation.farNegativeRejectionRate)
                    .put("validationDecisionStableRate", evaluation.decisionStableRate)
                    .put("meetsPreRegisteredCriteria", evaluation.meetsPreRegisteredCriteria())}",
            )
        }
        println(
            "$METRICS_TAG ${JSONObject()
                .put("providerId", providerId)
                .put("model", model)
                .put("configurationFingerprint", productionIdentity.configurationFingerprint)
                .put("calibrationDatasetVersion", CALIBRATION_DATASET.version)
                .put("validationDatasetVersion", VALIDATION_DATASET.version)
                .put("calibrationObservations", calibration.samples.size)
                .put("validationObservations", validation.samples.size)
                .put("calibrationRecallAt5", calibration.recallAt5)
                .put("validationRecallAt5", validation.recallAt5)
                .put("calibrationIndexMillis", calibration.indexMillis)
                .put("validationIndexMillis", validation.indexMillis)
                .put("calibrationQueryMedianMillis", calibration.queryMedianMillis)
                .put("validationQueryMedianMillis", validation.queryMedianMillis)
                .put("passingFeatureFamilies", JSONArray(passingFeatureFamilies.map { it.name }))
                .put("productionEnforcementEnabled", false)}",
        )

        assertEquals(EXPECTED_OBSERVATIONS, calibration.samples.size)
        assertEquals(EXPECTED_OBSERVATIONS, validation.samples.size)
        assertEquals(
            KnowledgeRelevanceCrossTopicFeatureSet.entries.toSet(),
            report.calibrationGates.keys,
        )
        assertEquals(
            KnowledgeRelevanceCrossTopicFeatureSet.entries.toSet(),
            report.validationEvaluations.keys,
        )
        assertTrue("正式 calibration Recall@5 过低", calibration.recallAt5 >= MINIMUM_RECALL_AT_5)
        assertTrue("正式 validation Recall@5 过低", validation.recallAt5 >= MINIMUM_RECALL_AT_5)
        report.validationEvaluations.values.forEach { evaluation ->
            assertTrue(evaluation.balancedAccuracy.isFinite())
            assertTrue(evaluation.decisionStableRate.isFinite())
        }
        // long: 首次未调参证据显示相对特征仍会误接纳近负例；冻结否决能阻止后续把 0.75 的拒绝率解释成生产可用。
        assertTrue("跨主题归一化 validation 应稳定得到预注册门禁否决", passingFeatureFamilies.isEmpty())
        println(
            "$EXPECTED_GATE_REJECTION_TAG ${JSONObject()
                .put("providerId", providerId)
                .put("model", model)
                .put("passingFeatureFamilies", JSONArray(passingFeatureFamilies.map { it.name }))
                .put("productionEnforcementEnabled", false)}",
        )
    }

    private suspend fun collectDataset(
        dataset: DatasetDefinition,
        baseUrl: String,
        apiKey: String,
        model: String,
        providerId: String,
    ): DatasetObservation {
        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            embeddingModel = model,
        )
        assertTrue(client.fetchModels(config).any { it.equals(model, ignoreCase = true) })
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return try {
            val semanticStore = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = OpenAiKnowledgeEmbeddingProvider(providerId, config, client),
            )
            val lexicalStore = RoomKnowledgeDocumentStore(context, database)
            val documentNames = linkedMapOf<String, String>()
            val indexStartedAt = SystemClock.elapsedRealtimeNanos()
            dataset.corpus.forEach { entry ->
                val document = semanticStore.importUtf8Document(
                    displayName = entry.fileName,
                    mimeType = "text/markdown",
                    bytes = entry.text.toByteArray(Charsets.UTF_8),
                )
                documentNames[document.id] = entry.fileName
            }
            val indexMillis = elapsedMillisSince(indexStartedAt)
            val observations = dataset.cases.flatMap { queryCase ->
                assertEquals(
                    "${dataset.version} 查询不得被词法命中虚高",
                    0,
                    lexicalStore.search(queryCase.query, limit = QUALITY_LIMIT).hits.size,
                )
                (0 until QUERY_RUNS).map { runIndex ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    val result = semanticStore.search(
                        query = queryCase.query,
                        limit = QUALITY_LIMIT,
                        sourceRunId = "${dataset.version}-${queryCase.caseId}-$runIndex",
                    )
                    val queryMillis = elapsedMillisSince(startedAt)
                    val retrieval = result.retrieval
                    val topScore = requireNotNull(retrieval.embeddingTopScore)
                    val secondScore = requireNotNull(retrieval.embeddingSecondScore)
                    val margin = requireNotNull(retrieval.embeddingScoreMargin)
                    val scoreMean = requireNotNull(retrieval.embeddingScoreMean)
                    val scoreStandardDeviation = requireNotNull(retrieval.embeddingScoreStandardDeviation)
                    assertEquals(USED, retrieval.embeddingStatus)
                    assertEquals(providerId, retrieval.embeddingProviderId)
                    assertEquals(model, retrieval.embeddingModel)
                    assertEquals(dataset.corpus.size, retrieval.embeddingCandidateCount)
                    assertTrue(topScore.isFinite())
                    assertTrue(secondScore.isFinite())
                    assertTrue(margin.isFinite() && margin >= 0.0)
                    assertTrue(scoreMean.isFinite())
                    assertTrue(scoreStandardDeviation.isFinite() && scoreStandardDeviation > 0.0)
                    assertEquals(topScore - secondScore, margin, 0.0000001)
                    val observation = Observation(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        expectedFileName = queryCase.expectedFileName,
                        rankedFileNames = result.hits.map { hit -> documentNames[hit.documentId] ?: hit.documentName },
                        queryMillis = queryMillis,
                        features = KnowledgeRelevanceCrossTopicFeatureVector.fromCandidateDistribution(
                            topScore = topScore,
                            scoreMean = scoreMean,
                            scoreMargin = margin,
                            scoreStandardDeviation = scoreStandardDeviation,
                        ),
                    )
                    println(
                        "$CASE_METRICS_TAG ${JSONObject()
                            .put("datasetVersion", dataset.version)
                            .put("caseId", observation.caseId)
                            .put("label", observation.label.name)
                            .put("runIndex", runIndex)
                            .put("topScoreMeanGap", observation.features.topScoreMeanGap)
                            .put("marginOverStandardDeviation", observation.features.marginOverStandardDeviation)
                            .put("expectedFileName", observation.expectedFileName ?: JSONObject.NULL)
                            .put("topFileName", observation.rankedFileNames.firstOrNull() ?: JSONObject.NULL)}",
                    )
                    observation
                }
            }
            val positiveQuality = dataset.cases
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
            val quality = KnowledgeSearchQualityPolicy.evaluate(positiveQuality)
            val vectorStats = database.vectorStats(providerId, model)
            assertEquals(dataset.corpus.size.toLong(), vectorStats.rowCount)
            assertEquals(vectorStats.rowCount * vectorStats.dimensions * Float.SIZE_BYTES, vectorStats.vectorBytes)
            DatasetObservation(
                samples = observations.map { observation ->
                    KnowledgeRelevanceCrossTopicFeatureSample(
                        caseId = observation.caseId,
                        label = observation.label,
                        features = observation.features,
                    )
                },
                recallAt5 = quality.meanRecallAtK,
                queryMedianMillis = observations.map { it.queryMillis }.percentile(0.50),
                indexMillis = indexMillis,
            )
        } finally {
            database.close()
        }
    }

    private fun KnowledgeRelevanceCrossTopicFeatureEvaluation.meetsPreRegisteredCriteria(): Boolean =
        positiveAcceptanceRate >= MINIMUM_POSITIVE_ACCEPTANCE_RATE &&
            nearNegativeRejectionRate >= MINIMUM_NEAR_NEGATIVE_REJECTION_RATE &&
            farNegativeRejectionRate >= MINIMUM_FAR_NEGATIVE_REJECTION_RATE &&
            decisionStableRate >= MINIMUM_DECISION_STABLE_RATE

    private fun Map<KnowledgeRelevanceNormalizedFeature, Double>.toJson(): JSONObject =
        JSONObject().also { json -> forEach { (feature, threshold) -> json.put(feature.name, threshold) } }

    private fun XiaoLingDatabase.vectorStats(providerId: String, model: String): VectorStats {
        val query = SimpleSQLiteQuery(
            """
                SELECT COUNT(*), COALESCE(SUM(LENGTH(vectorBlob)), 0), COALESCE(MIN(dimensions), 0)
                FROM knowledge_chunk_embeddings
                WHERE providerId = ? AND model = ?
            """.trimIndent(),
            arrayOf(providerId, model),
        )
        return openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst()) { "无法读取跨主题归一化向量统计" }
            VectorStats(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND

    private fun List<Long>.percentile(fraction: Double): Long {
        val sorted = sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun datasetIdentity(
        productionIdentity: KnowledgeRelevanceProductionIdentity,
        version: String,
    ) = KnowledgeRelevanceProductionDatasetIdentity(
        providerId = productionIdentity.providerId,
        model = productionIdentity.model,
        configurationFingerprint = productionIdentity.configurationFingerprint,
        datasetVersion = version,
    )

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
        val queryMillis: Long,
        val features: KnowledgeRelevanceCrossTopicFeatureVector,
    )

    private data class DatasetObservation(
        val samples: List<KnowledgeRelevanceCrossTopicFeatureSample>,
        val recallAt5: Double,
        val queryMedianMillis: Long,
        val indexMillis: Long,
    )

    private data class VectorStats(
        val rowCount: Long,
        val vectorBytes: Long,
        val dimensions: Long,
    )

    private companion object {
        const val ARG_BASE_URL = "embeddingProviderBaseUrl"
        const val ARG_API_KEY = "embeddingProviderApiKey"
        const val ARG_MODEL = "embeddingProviderModel"
        const val ARG_PROVIDER_ID = "embeddingProviderId"
        const val PRODUCTION_PROVIDER_ID = "redmi-production-embedding-v1"
        const val PRODUCTION_MODEL = "Qwen/Qwen3-Embedding-0.6B"
        const val METRICS_TAG = "XIAOLING_STAGE91_CROSS_TOPIC_NORMALIZATION"
        const val FEATURE_METRICS_TAG = "XIAOLING_STAGE91_NORMALIZED_FEATURE"
        const val CASE_METRICS_TAG = "XIAOLING_STAGE91_NORMALIZED_CASE"
        const val EXPECTED_GATE_REJECTION_TAG = "XIAOLING_STAGE91_EXPECTED_GATE_REJECTION"
        const val QUALITY_LIMIT = 5
        const val QUERY_RUNS = 2
        const val CASES_PER_LABEL = 4
        val EXPECTED_OBSERVATIONS = CASES_PER_LABEL * KnowledgeRelevanceLabel.entries.size * QUERY_RUNS
        const val MINIMUM_RECALL_AT_5 = 0.80
        const val MINIMUM_POSITIVE_ACCEPTANCE_RATE = 0.90
        const val MINIMUM_NEAR_NEGATIVE_REJECTION_RATE = 0.80
        const val MINIMUM_FAR_NEGATIVE_REJECTION_RATE = 0.90
        const val MINIMUM_DECISION_STABLE_RATE = 1.0
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val CALIBRATION_DATASET = DatasetDefinition(
            version = "stage91-cross-topic-calibration-v1",
            corpus = listOf(
                CorpusEntry("s91c01-面包发酵.md", "面包发酵需要控制酵母用量、面团温度和醒发时间，观察体积变化后再整形和烘烤。"),
                CorpusEntry("s91c02-砂锅清洁.md", "砂锅清洁应先等待锅体降温，再用温水和软刷去除残留，避免骤冷和硬质刮擦造成裂纹。"),
                CorpusEntry("s91c03-室内香草.md", "室内香草种植需要明亮散射光、排水良好的盆土和适度浇水，修剪能促进分枝。"),
                CorpusEntry("s91c04-雨伞维护.md", "雨伞使用后应完全展开晾干，收纳前清除泥沙并检查伞骨，潮湿折叠容易产生霉斑。"),
                CorpusEntry("s91c05-羽毛球热身.md", "羽毛球热身先活动肩肘腕和膝踝，再进行短距离步伐与轻击球，逐渐提高强度。"),
                CorpusEntry("s91c06-旅行清单.md", "旅行清单应按证件、衣物、药品和电子设备分类，并在出发前逐项核对充电和备份。"),
                CorpusEntry("s91c07-咖啡研磨.md", "咖啡研磨粗细要配合冲煮方式，研磨后尽快使用，颗粒均匀有助于控制萃取速度。"),
                CorpusEntry("s91c08-木家具打蜡.md", "木家具打蜡前先除尘并确认表面干燥，薄涂均匀后等待固化，再用软布轻轻抛光。"),
                CorpusEntry("s91c09-阳台育苗.md", "阳台育苗要使用带排水孔的容器，幼苗出土后逐步增加光照，避免浇水过量。"),
                CorpusEntry("s91c10-耳机收纳.md", "耳机线材收纳时避免紧折和拉扯，使用松弛的环形绕法并保持插头干燥清洁。"),
                CorpusEntry("s91c11-手工缝补.md", "手工缝补先选择接近原布料的线和针，再从破损边缘内侧固定，针脚保持均匀并收紧线结。"),
                CorpusEntry("s91c12-晨间拉伸.md", "晨间拉伸以轻柔动态活动为主，逐步唤醒髋部、背部和肩部，出现疼痛应立即停止。"),
            ),
            cases = listOf(
                QueryCase("s91c-positive-bread", KnowledgeRelevanceLabel.POSITIVE, "How should yeast, dough temperature, and proofing be managed before shaping bread?", "s91c01-面包发酵.md"),
                QueryCase("s91c-positive-pot", KnowledgeRelevanceLabel.POSITIVE, "How can a clay cooking pot be cleaned without thermal shock or scratches?", "s91c02-砂锅清洁.md"),
                QueryCase("s91c-positive-herbs", KnowledgeRelevanceLabel.POSITIVE, "What light, soil drainage, watering, and pruning help indoor herbs branch?", "s91c03-室内香草.md"),
                QueryCase("s91c-positive-travel", KnowledgeRelevanceLabel.POSITIVE, "How should a travel packing checklist cover documents, medicines, charging, and backups?", "s91c06-旅行清单.md"),
                QueryCase("s91c-near-bread", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which wheat cultivar guarantees the tallest loaf at a fixed humidity?"),
                QueryCase("s91c-near-pot", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which glaze chemistry gives every clay pot the highest thermal shock rating?"),
                QueryCase("s91c-near-herbs", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which indoor herb variety has the most vitamin C per gram under artificial light?"),
                QueryCase("s91c-near-travel", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which airline has the strictest universal lithium battery carry-on rule?"),
                QueryCase("s91c-far-astronomy", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do astronomers measure the rotation of a distant galaxy?"),
                QueryCase("s91c-far-tax", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How are corporate income tax provisions audited at year end?"),
                QueryCase("s91c-far-phonetics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do vowel systems change across neighboring language families?"),
                QueryCase("s91c-far-aviation", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does an aircraft maintain lift during a steep turn?"),
            ),
        )

        val VALIDATION_DATASET = DatasetDefinition(
            version = "stage91-cross-topic-validation-v1",
            corpus = listOf(
                CorpusEntry("s91v01-酸奶制作.md", "酸奶制作要先加热牛奶再降温接种菌种，保持稳定温度发酵，凝固后冷藏保存。"),
                CorpusEntry("s91v02-砧板消毒.md", "砧板清洁后要彻底冲洗并晾干，生熟食材分开使用，定期用合适方式消毒并检查裂缝。"),
                CorpusEntry("s91v03-室内多肉.md", "室内多肉需要充足光照和疏松排水介质，浇水前确认盆土干燥，冬季减少频率。"),
                CorpusEntry("s91v04-窗帘清洗.md", "窗帘清洗前查看面料标签，拆除配件并按要求选择水温，晾晒时避免长时间暴晒变色。"),
                CorpusEntry("s91v05-乒乓球练习.md", "乒乓球练习先固定动作和落点，再逐步增加速度与旋转，短组重复后记录稳定性。"),
                CorpusEntry("s91v06-露营照明.md", "露营照明应准备主灯和备用电源，灯具放在安全位置，夜间收纳电池并防止受潮。"),
                CorpusEntry("s91v07-茶壶保养.md", "茶壶使用后倒空残液并清水冲洗，完全干燥后收纳，壶嘴和滤孔要定期检查。"),
                CorpusEntry("s91v08-皮鞋护理.md", "皮鞋护理先清除灰尘，再按材质薄涂护理剂，阴凉处自然干燥后用软刷抛光。"),
                CorpusEntry("s91v09-室内扦插.md", "室内扦插选择健康枝条和干净基质，保持通风与微湿，生根后再逐步增加光照。"),
                CorpusEntry("s91v10-键盘清洁.md", "键盘清洁前断开电源，倒置抖落碎屑，用软刷处理缝隙，液体不能直接倒入按键。"),
                CorpusEntry("s91v11-瑜伽呼吸.md", "瑜伽呼吸练习保持脊柱舒展，以舒适节奏吸气和呼气，头晕或不适时回到自然呼吸。"),
                CorpusEntry("s91v12-照片整理.md", "照片整理按日期和事件建立目录，保留原片并去除重复，完成后抽查文件能否正常打开。"),
            ),
            cases = listOf(
                QueryCase("s91v-positive-yogurt", KnowledgeRelevanceLabel.POSITIVE, "What heating, cooling, inoculation, and temperature steps make homemade yogurt set safely?", "s91v01-酸奶制作.md"),
                QueryCase("s91v-positive-board", KnowledgeRelevanceLabel.POSITIVE, "How should a cutting board be cleaned, dried, separated by use, and checked for cracks?", "s91v02-砧板消毒.md"),
                QueryCase("s91v-positive-camping", KnowledgeRelevanceLabel.POSITIVE, "How can camping lights, spare power, placement, and batteries be kept safe and dry?", "s91v06-露营照明.md"),
                QueryCase("s91v-positive-photos", KnowledgeRelevanceLabel.POSITIVE, "How should photos be organized by date while preserving originals and checking file integrity?", "s91v12-照片整理.md"),
                QueryCase("s91v-near-yogurt", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which probiotic strain produces the firmest yogurt regardless of milk composition?"),
                QueryCase("s91v-near-board", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which wood species is legally required for every commercial cutting board?"),
                QueryCase("s91v-near-camping", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which camping lantern has the longest runtime at subzero temperatures?"),
                QueryCase("s91v-near-photos", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which image format guarantees permanent metadata preservation across every operating system?"),
                QueryCase("s91v-far-music", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How did early musicians synchronize recordings before digital editing?"),
                QueryCase("s91v-far-geology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do tectonic plates form volcanic island arcs?"),
                QueryCase("s91v-far-medicine", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does immune memory respond after a second exposure to an antigen?"),
                QueryCase("s91v-far-architecture", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How can a building envelope reduce solar heat gain in summer?"),
            ),
        )
    }
}
