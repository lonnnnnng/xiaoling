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

@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeRelevanceProductionCalibrationInstrumentedTest {
    @Test
    fun formalProductionIdentityBuildsEvidenceAndRejectsPromotionWhenNoFamilyPasses() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val providerId = arguments.getString(ARG_PROVIDER_ID).orEmpty().trim()
        assumeTrue("未显式提供正式 Embedding Provider，跳过第90阶段校准", baseUrl.isNotBlank())
        assumeTrue("未显式提供正式 Embedding API Key，跳过第90阶段校准", apiKey.isNotBlank())
        assumeTrue("未显式提供正式 Embedding 模型，跳过第90阶段校准", model.isNotBlank())
        assumeTrue("未显式提供正式 Embedding Provider ID，跳过第90阶段校准", providerId.isNotBlank())

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
        val report = KnowledgeRelevanceProductionCalibrationPolicy.compare(
            productionIdentity = productionIdentity,
            calibrationIdentity = datasetIdentity(productionIdentity, CALIBRATION_DATASET.version),
            validationIdentity = datasetIdentity(productionIdentity, VALIDATION_DATASET.version),
            calibrationSamples = calibration.samples,
            validationSamples = validation.samples,
        )
        val passingFeatureFamilies = report.featureComparison.validationEvaluations.filterValues { evaluation ->
            evaluation.meetsPreRegisteredCriteria()
        }.keys

        report.featureComparison.validationEvaluations.forEach { (featureSet, validationEvaluation) ->
            val gate = report.featureComparison.calibrationGates.getValue(featureSet)
            println(
                "$FEATURE_METRICS_TAG ${JSONObject()
                    .put("featureSet", featureSet.name)
                    .put("thresholds", gate.thresholds.toJson())
                    .put("calibrationBalancedAccuracy", gate.calibrationBalancedAccuracy)
                    .put("calibrationPositiveAcceptanceRate", gate.calibrationPositiveAcceptanceRate)
                    .put("calibrationNearNegativeRejectionRate", gate.calibrationNearNegativeRejectionRate)
                    .put("calibrationFarNegativeRejectionRate", gate.calibrationFarNegativeRejectionRate)
                    .put("validationBalancedAccuracy", validationEvaluation.balancedAccuracy)
                    .put("validationPositiveAcceptanceRate", validationEvaluation.positiveAcceptanceRate)
                    .put("validationNearNegativeRejectionRate", validationEvaluation.nearNegativeRejectionRate)
                    .put("validationFarNegativeRejectionRate", validationEvaluation.farNegativeRejectionRate)
                    .put("validationDecisionStableRate", validationEvaluation.decisionStableRate)
                    .put("meetsPreRegisteredCriteria", validationEvaluation.meetsPreRegisteredCriteria())}",
            )
        }
        val metrics = JSONObject()
            .put("providerId", providerId)
            .put("model", model)
            .put("configurationFingerprint", productionIdentity.configurationFingerprint)
            .put("calibrationDatasetVersion", CALIBRATION_DATASET.version)
            .put("validationDatasetVersion", VALIDATION_DATASET.version)
            .put("calibrationObservations", calibration.samples.size)
            .put("validationObservations", validation.samples.size)
            .put("calibrationRecallAt5", calibration.recallAt5)
            .put("validationRecallAt5", validation.recallAt5)
            .put("calibrationQueryMedianMillis", calibration.queryMedianMillis)
            .put("validationQueryMedianMillis", validation.queryMedianMillis)
            .put("featureFamilies", JSONArray(report.featureComparison.validationEvaluations.keys.map { it.name }))
            .put("passingFeatureFamilies", JSONArray(passingFeatureFamilies.map { it.name }))
            .put("productionEnforcementEnabled", false)
        println("$METRICS_TAG $metrics")

        assertEquals(EXPECTED_OBSERVATIONS, calibration.samples.size)
        assertEquals(EXPECTED_OBSERVATIONS, validation.samples.size)
        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.featureComparison.calibrationGates.keys)
        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.featureComparison.validationEvaluations.keys)
        assertTrue("正式 calibration Recall@5 过低", calibration.recallAt5 >= MINIMUM_RECALL_AT_5)
        assertTrue("正式 validation Recall@5 过低", validation.recallAt5 >= MINIMUM_RECALL_AT_5)
        report.featureComparison.validationEvaluations.values.forEach { evaluation ->
            assertTrue(evaluation.balancedAccuracy.isFinite())
            assertTrue(evaluation.decisionStableRate.isFinite())
        }
        // long: 正式校准的职责是诚实地冻结跨主题泛化结果；没有特征族通过时必须把否决作为成功验收事实，阻止误升级身份或生产门禁。
        assertTrue("正式 validation 应稳定得到预注册相关性门禁否决", passingFeatureFamilies.isEmpty())
        assertEquals(1.0, calibration.recallAt5, 0.000001)
        assertEquals(1.0, validation.recallAt5, 0.000001)
        println(
            "$EXPECTED_GATE_REJECTION_TAG ${JSONObject()
                .put("providerId", providerId)
                .put("model", model)
                .put("configurationFingerprint", productionIdentity.configurationFingerprint)
                .put("passingFeatureFamilies", JSONArray(passingFeatureFamilies.map { it.name }))
                .put("productionEnforcementEnabled", false)}",
        )
        // long: 本阶段只保存同一正式身份的独立证据，不能把 calibration/validation 报告直接解释成 VERIFIED 或 ENFORCE。
        assertTrue(productionIdentity.configurationFingerprint.matches(HEX_FINGERPRINT))
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
                    val zScore = requireNotNull(retrieval.embeddingTopScoreZScore)
                    assertEquals(USED, retrieval.embeddingStatus)
                    assertEquals(providerId, retrieval.embeddingProviderId)
                    assertEquals(model, retrieval.embeddingModel)
                    assertEquals(dataset.corpus.size, retrieval.embeddingCandidateCount)
                    assertTrue(topScore.isFinite())
                    assertTrue(secondScore.isFinite())
                    assertTrue(margin.isFinite() && margin >= 0.0)
                    assertTrue(zScore.isFinite() && zScore >= 0.0)
                    assertEquals(topScore - secondScore, margin, 0.0000001)
                    Observation(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        runIndex = runIndex,
                        expectedFileName = queryCase.expectedFileName,
                        rankedFileNames = result.hits.map { hit ->
                            documentNames[hit.documentId] ?: hit.documentName
                        },
                        queryMillis = queryMillis,
                        features = KnowledgeRelevanceFeatureVector(
                            rawTopScore = topScore,
                            scoreMargin = margin,
                            topScoreZScore = zScore,
                        ),
                    ).also { observation ->
                        println(
                            "$CASE_METRICS_TAG ${JSONObject()
                                .put("datasetVersion", dataset.version)
                                .put("caseId", observation.caseId)
                                .put("label", observation.label.name)
                                .put("runIndex", observation.runIndex)
                                .put("expectedFileName", observation.expectedFileName ?: JSONObject.NULL)
                                .put("topFileName", observation.rankedFileNames.firstOrNull() ?: JSONObject.NULL)
                                .put("rawTopScore", observation.features.rawTopScore)
                                .put("scoreMargin", observation.features.scoreMargin)
                                .put("topScoreZScore", observation.features.topScoreZScore)}",
                        )
                    }
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
                    KnowledgeRelevanceFeatureSample(
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

    private fun datasetIdentity(
        productionIdentity: KnowledgeRelevanceProductionIdentity,
        version: String,
    ) = KnowledgeRelevanceProductionDatasetIdentity(
        providerId = productionIdentity.providerId,
        model = productionIdentity.model,
        configurationFingerprint = productionIdentity.configurationFingerprint,
        datasetVersion = version,
    )

    private fun KnowledgeRelevanceFeatureEvaluation.meetsPreRegisteredCriteria(): Boolean =
        positiveAcceptanceRate >= MINIMUM_POSITIVE_ACCEPTANCE_RATE &&
            nearNegativeRejectionRate >= MINIMUM_NEAR_NEGATIVE_REJECTION_RATE &&
            farNegativeRejectionRate >= MINIMUM_FAR_NEGATIVE_REJECTION_RATE &&
            decisionStableRate >= MINIMUM_DECISION_STABLE_RATE

    private fun Map<KnowledgeRelevanceFeature, Double>.toJson(): JSONObject =
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
            check(cursor.moveToFirst()) { "无法读取正式 Provider 向量统计" }
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
        val runIndex: Int,
        val expectedFileName: String?,
        val rankedFileNames: List<String>,
        val queryMillis: Long,
        val features: KnowledgeRelevanceFeatureVector,
    )

    private data class DatasetObservation(
        val samples: List<KnowledgeRelevanceFeatureSample>,
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
        const val METRICS_TAG = "XIAOLING_STAGE90_FORMAL_CALIBRATION"
        const val FEATURE_METRICS_TAG = "XIAOLING_STAGE90_FORMAL_FEATURE"
        const val CASE_METRICS_TAG = "XIAOLING_STAGE90_FORMAL_CASE"
        const val EXPECTED_GATE_REJECTION_TAG = "XIAOLING_STAGE90_EXPECTED_GATE_REJECTION"
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
        val HEX_FINGERPRINT = Regex("[0-9a-f]{64}")

        val CALIBRATION_DATASET = DatasetDefinition(
            version = "stage90-formal-calibration-v1",
            corpus = listOf(
                CorpusEntry("s90c01-茶叶储存.md", "茶叶储存要避光、防潮并使用密封容器，开封后减少与空气接触，取用时保持工具干燥。"),
                CorpusEntry("s90c02-茶叶冲泡.md", "茶叶冲泡需要根据茶类调整水温和浸泡时间，先温杯，再分次注水，避免长时间浸泡造成苦涩。"),
                CorpusEntry("s90c03-自行车链条.md", "自行车链条保养应先清除旧油和砂粒，再在每个滚轴处少量上油，转动后擦去多余油膜。"),
                CorpusEntry("s90c04-自行车刹车.md", "自行车刹车检查包括刹车皮磨损、拉线张力和轮圈清洁，调整后应在低速处测试制动力。"),
                CorpusEntry("s90c05-堆肥管理.md", "家庭堆肥要保持适度湿润并定期翻动，让干湿材料和空气充分混合，出现异味时减少积水并增加干料。"),
                CorpusEntry("s90c06-种子育苗.md", "种子育苗使用疏松基质和浅播深度，发芽前保持湿润，出苗后逐步增加光照并避免积水。"),
                CorpusEntry("s90c07-照片备份.md", "照片备份可采用三份副本、两种介质和一份异地保存的规则，备份后应随机打开文件核对完整性。"),
                CorpusEntry("s90c08-照片白平衡.md", "照片白平衡应根据光源调整，拍摄灰卡或保留现场参考，后期统一校正能减少不同照片之间的色偏。"),
                CorpusEntry("s90c09-徒步分层.md", "徒步穿衣按基础层、保暖层和防风雨层分层，活动强度变化时及时增减，避免汗湿后失温。"),
                CorpusEntry("s90c10-徒步路线.md", "徒步路线规划要核对距离、爬升、天气和撤退点，把预计时间留出缓冲并让同行者知道返程计划。"),
                CorpusEntry("s90c11-桌面姿势.md", "桌面工作时显示器上沿接近视线高度，双脚落地，键盘和鼠标保持肩膀放松，长时间工作要安排起身间歇。"),
                CorpusEntry("s90c12-学习间歇.md", "学习间歇可以在短时专注后离开屏幕活动，记录下一步任务再休息，恢复后直接从明确位置继续。"),
            ),
            cases = listOf(
                QueryCase("s90c-positive-tea-storage", KnowledgeRelevanceLabel.POSITIVE, "How should loose tea be protected from light, moisture, and air after opening?", "s90c01-茶叶储存.md"),
                QueryCase("s90c-positive-bike-chain", KnowledgeRelevanceLabel.POSITIVE, "What sequence keeps a bicycle chain clean without leaving excess oil?", "s90c03-自行车链条.md"),
                QueryCase("s90c-positive-compost", KnowledgeRelevanceLabel.POSITIVE, "How can household compost moisture and airflow be managed to prevent odor?", "s90c05-堆肥管理.md"),
                QueryCase("s90c-positive-photo-backup", KnowledgeRelevanceLabel.POSITIVE, "What backup pattern protects photos across media and locations, and how should it be checked?", "s90c07-照片备份.md"),
                QueryCase("s90c-near-tea-caffeine", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which tea cultivar has the highest caffeine concentration after a long infusion?"),
                QueryCase("s90c-near-bike-alloy", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which bicycle chain alloy lasts the longest under winter road salt exposure?"),
                QueryCase("s90c-near-compost-ratio", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What exact carbon to nitrogen ratio guarantees the fastest composting speed?"),
                QueryCase("s90c-near-camera-sensor", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which camera sensor design produces the lowest read noise at high ISO?"),
                QueryCase("s90c-far-astronomy", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do astronomers estimate the mass of a distant exoplanet?"),
                QueryCase("s90c-far-tax", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How are value added tax invoices reconciled at the end of a quarter?"),
                QueryCase("s90c-far-linguistics", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do sound changes spread through neighboring language communities?"),
                QueryCase("s90c-far-architecture", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does a passive building reduce heat gain through its envelope?"),
            ),
        )

        val VALIDATION_DATASET = DatasetDefinition(
            version = "stage90-formal-validation-v1",
            corpus = listOf(
                CorpusEntry("s90v01-酸面团酵种.md", "酸面团酵种需要按固定比例补充面粉和水，观察体积和气泡变化，温度不同会改变发酵周期。"),
                CorpusEntry("s90v02-菜刀维护.md", "菜刀使用后应及时擦干，钝化时保持稳定角度在磨石上轻压，收纳时让刀刃与硬物分开。"),
                CorpusEntry("s90v03-鱼缸换水.md", "鱼缸换水先检测温度和水质，分批更换并避免一次改变过大，清洁过滤棉时保留有益菌环境。"),
                CorpusEntry("s90v04-鱼缸水草.md", "鱼缸水草需要合适光照和基质固定，修剪腐叶并观察藻类变化，施肥要从低量开始。"),
                CorpusEntry("s90v05-阳台遮阳.md", "阳台遮阳先观察一天内的日照方向，再选择帘体和固定件，强风时收起活动部件并检查连接处。"),
                CorpusEntry("s90v06-室内通风.md", "室内通风应结合室外空气质量，短时形成对流通常比长时间开小缝有效，污染升高时及时关闭。"),
                CorpusEntry("s90v07-表格备份.md", "重要表格按日期保存多个版本并放到不同存储位置，备份后随机打开检查公式和附件，不能只看同步提示。"),
                CorpusEntry("s90v08-文件命名.md", "文件命名包含稳定主题、日期和版本，统一分隔符与日期格式可以帮助搜索、排序和恢复历史文件。"),
                CorpusEntry("s90v09-吉他练习.md", "吉他练习先用慢速节拍器保证准确，再逐步增加速度，把难段拆开重复并记录当天最高稳定速度。"),
                CorpusEntry("s90v10-录音电平.md", "录音前设置合适输入电平并保留峰值余量，先监听底噪和削波，再决定是否调整麦克风距离。"),
                CorpusEntry("s90v11-室内植物虫害.md", "室内植物发现虫害时先隔离植株，检查叶背并清除明显虫体，再按说明使用温和处理并持续观察。"),
                CorpusEntry("s90v12-衣物晾晒.md", "衣物晾晒按材质分开，厚重衣物留出通风间距并翻面，阴雨天除湿时避免贴近高温热源。"),
            ),
            cases = listOf(
                QueryCase("s90v-positive-starter", KnowledgeRelevanceLabel.POSITIVE, "How should flour, water, temperature, and bubbles be observed when feeding a sourdough starter?", "s90v01-酸面团酵种.md"),
                QueryCase("s90v-positive-aquarium", KnowledgeRelevanceLabel.POSITIVE, "What precautions make a partial aquarium water change safer for fish and filter bacteria?", "s90v03-鱼缸换水.md"),
                QueryCase("s90v-positive-balcony", KnowledgeRelevanceLabel.POSITIVE, "How should balcony shade placement and hardware be chosen for changing sunlight and wind?", "s90v05-阳台遮阳.md"),
                QueryCase("s90v-positive-backup", KnowledgeRelevanceLabel.POSITIVE, "How can spreadsheet backups preserve versions and reveal broken formulas instead of trusting sync status?", "s90v07-表格备份.md"),
                QueryCase("s90v-near-starter", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which wild yeast strain gives a sourdough starter the highest rise at exactly 24 degrees?"),
                QueryCase("s90v-near-aquarium", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which aquarium filter brand removes the greatest percentage of dissolved nitrate?"),
                QueryCase("s90v-near-balcony", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "What wind load rating is legally required for every residential balcony shade?"),
                QueryCase("s90v-near-backup", KnowledgeRelevanceLabel.NEAR_NEGATIVE, "Which cloud provider guarantees the longest retention of spreadsheet formula history?"),
                QueryCase("s90v-far-music", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How did early recording studios synchronize multiple audio tracks?"),
                QueryCase("s90v-far-medicine", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do vaccines train immune memory after exposure?"),
                QueryCase("s90v-far-geology", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How do tectonic plates create deep ocean trenches?"),
                QueryCase("s90v-far-aviation", KnowledgeRelevanceLabel.FAR_NEGATIVE, "How does an aircraft wing generate lift during a climb?"),
            ),
        )
    }
}
