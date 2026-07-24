# 验证报告

验证日期：2026-07-24（北京时间）

## 2026-07-24 冻结 raw top1 的 final holdout 预注册（第 86 阶段）

- 冻结身份：门禁 `stage85-raw-top1-qwen-v1`，calibration `stage85-calibration-v1`，validation `stage85-validation-v1`，raw top1 下限 `0.6416276358587735`；真实运行时模型必须与 Stage 85 一致。
- 未见语料：`stage86-final-holdout-v1` 已在运行前固定 20 篇全新成对主题文档，正/近负/远负各 10 条英文查询，每条重复 2 次。近负例是两篇 companion 文档均未覆盖的具体事实；不复用 Stage 82/83/85 数据。
- 预注册标准：正例接纳 `>=0.90`、近负例拒绝 `>=0.80`、远负例拒绝 `>=0.90`、决策稳定 `1.0`；Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定 `1.0`。
- 预运行门禁：纯 Kotlin `4/4` 与 AndroidTest APK 编译通过。此处尚无真实 Provider 指标；下一步只在 Redmi 执行一次有效采集，运行后不得更换语料、阈值或标准。
- 生产边界：Room v32、cosine+RRF、FTS4+LIKE、UI 和相关性拒绝均未修改。final holdout 通过也只进入生产设计评审，不直接上线。

## 2026-07-24 Embedding 特征族独立 calibration/validation（第 85 阶段）

- 纯 Kotlin 契约：新增 7 个固定特征族，calibration gate 只枚举 calibration 观测点，validation 只应用冻结阈值。身份门禁要求 Provider/模型一致、数据集版本不同；输入门禁要求三桶完整、数值有限和 case 标签稳定。聚焦 JVM `3/3` 通过。
- 数据隔离：`stage85-calibration-v1` 与 `stage85-validation-v1` 分别使用独立内存 Room，各 20 篇全新成对主题文档，正/近负/远负各 10 条英文查询，每条重复 2 次；Stage 82 calibration 与 Stage 83 holdout 均未参与。首轮错误地把 companion 可直接回答的问题标为近负例，已废弃且不记录为有效实验；修正后近负例固定为同主题但语料未覆盖的具体事实。
- Redmi 有效运行：只在 `wsvwypiz7xwslvl7` 执行，`1/1` 通过，耗时 `132.872s`；两套均得到 60 条有效观测，Recall@5 均为 `1.0`。配置从未跟踪 `AGENTS.md` 内部读取，未输出 Base URL 或 API Key。
- 通过候选：raw top1 阈值 `0.6416276358587735` 与 raw+margin 阈值 `0.6416276358587735 / 0.021738810541493292` 的 calibration balanced accuracy 均为 `1.0`；validation 均为正例接纳 `0.90`、近负例拒绝 `1.0`、远负例拒绝 `1.0`、稳定率 `1.0`、balanced accuracy `0.9667`，满足预注册标准。
- 未通过候选：单 margin、单 z、margin+z 的 validation balanced accuracy 均为 `0.80`，近负例拒绝只有 `0.50`；raw+z 和 raw+margin+z 的正例接纳只有 `0.80`。z-score 没有改善本轮跨集泛化。
- 结论：raw+margin 与 raw top1 没有产生不同 validation 决策，下一阶段按简约原则只冻结 raw top1 及其身份，用第三套全新 final holdout 验证。当前不写生产配置、不修改 Room v32/cosine+RRF/词法兜底，也不启用拒绝。
- 完整门禁：JVM XML `502/502`、0 failed、0 skipped；Lint、Debug APK 和 AndroidTest APK 成功。仅在 Redmi 运行默认完整 instrumentation，`177/177`、0 failed，6 个显式联网用例缺参按设计 skipped；未启动或操作 Pixel_9/其他模拟器。

## 2026-07-24 Embedding 查询内相对分布 shadow 观测（第 84 阶段）

- 纯 Kotlin 契约：`KnowledgeRelevanceRelativeDiagnosticsPolicyTest` 共 4 条，覆盖总体均值/标准差/top1 z-score、候选整体平移不变、单候选与零方差保持未知、空列表和非有限值拒绝。z-score 只描述首位候选在同次查询候选分布中的相对位置。
- 生产审计：`RoomKnowledgeDocumentStore` 使用现有 2000 行上限内、top-K 截断前的全部有效 cosine 候选计算均值、总体标准差和 top1 z-score，检索 limit 不改变观测；相对值只写入 `KnowledgeRetrievalRecord`，不进入 RRF、排序、拒绝或回退。
- Room v32：v31→v32 新增三个可空 REAL 列，历史检索保持 `null`。Redmi 迁移、三候选写入回读和知识管理 UI `3/3` 通过；受控分数 `1.0 / 0.8 / 0.0` 的均值 `0.6`、标准差 `0.4320493799`、top1 z `0.9258200998` 均按总体分布计算并持久化。
- 真实 Provider：只使用 Redmi `wsvwypiz7xwslvl7` 显式运行生产语义链，模型同步、两篇文档索引、跨语言首位命中、相对指标有限值和显式重建 `1/1` 通过。配置从未跟踪 `AGENTS.md` 内部读取，未输出 Base URL 或 API Key。
- 退休 holdout shadow：复用 `stage83-holdout-v1` 只确认新字段链路，不计算或搜索阈值。60 个观测中正例 z-score `2.9291557475–3.7221077216`、近负例 `2.2256271150–3.2324269685`、远负例 `1.5791078091–2.8788825475`；正例与近负例仍重叠。旧冻结门禁继续以正例接纳率 `0.80` 失败，结论没有被改写。
- 边界：本阶段不把 z-score 单独或与 raw top1/margin 组合成生产门禁，不把退休 holdout 变成校准集。下一阶段必须使用全新版本的 calibration/validation 数据预注册比较特征，之后才可能冻结候选并准备另一个未见 final holdout。
- 完整门禁：JVM XML `499/499`、0 failed、0 skipped；Lint、Debug APK 和 AndroidTest APK 成功。Debug APK 为 `22,944,378` 字节、SHA-256 `98f6e620bda4a88c0c14ecdfb2103a0a1e0ba08d58b875be5762f5ebb03da2a8`。文档更新后仅在 Redmi 运行默认完整 instrumentation，`176/176`、0 failed，5 个显式联网用例缺参按设计 skipped；未启动或操作 Pixel_9/其他模拟器。

## 2026-07-24 冻结候选门禁独立 holdout（第 83 阶段）

- 冻结身份：模型 `Qwen/Qwen3-Embedding-0.6B`，门禁 `stage82-qwen-v1`，校准集 `stage82-calibration-v1`，holdout `stage83-holdout-v1`；top1 下限 `0.6735426515268672`，margin 下限 `0.0178535973263384`。这些值在运行 holdout 前固定，三轮后没有修改。
- 预注册标准：正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`；Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定率 `1.0`。
- 策略与 JVM：新增 holdout 身份、冻结门禁、标准和报告模型；4 条 `KnowledgeRelevanceHoldoutPolicyTest` 覆盖成功评估、Provider/模型/数据集漂移、非法阈值/标准/样本，以及 holdout 失败后不得用自身样本重新调参。完整 JVM XML 为 `495/495`、0 failed、0 skipped。
- 独立语料：20 篇全新中文成对主题文档，不复用 Stage 82 主题；正例、近负例、远负例各 10 条英文查询，每条重复 2 次。纯词法命中均为 0，每次语义检索均有 20 个候选；20 行 1024 维 Float32 向量共 `81,920` 字节。
- Redmi 三轮：只使用 `wsvwypiz7xwslvl7`，覆盖安装当前 Debug/Test APK，并从外部启动三个独立 instrumentation 进程。三轮均完成 60 个观测，正例接纳率 `0.80`、近负例拒绝率 `1.0`、远负例拒绝率 `1.0`、决策稳定率 `1.0`；Recall@1/5、MRR 和排序稳定率均为 `1.0`。三轮都在“独立 holdout 未通过冻结相关性门禁”断言处失败，真实否决结果稳定。
- 失败定位：手冲咖啡正例 top1 `0.6169345095`、margin `0.0880494333`；缝纫机张力正例 top1 `0.6661466830`、margin `0.1218896937`。两者重复运行分数一致、目标文档仍排第一，但 top1 低于冻结下限。失败来自绝对分数跨主题泛化不足，不是排名、margin、Provider、向量维度或重复稳定性异常。
- 性能与资源：三轮索引耗时 `14.237 / 14.696 / 14.655s`；查询中位数 `0.757 / 0.800 / 0.779s`，P95 `0.816 / 0.836 / 0.814s`；检索后 PSS `202,753 / 202,684 / 202,935 KB`。这些数据只作当前设备与 Provider 观测。
- 决策：第 82 阶段冻结候选不具备进入生产相关性拒绝设计的资格。不得根据 `stage83-holdout-v1` 降低当前阈值或标准；后续必须建立新版本 calibration/validation/holdout 或新的归一化特征，并再次使用未见数据。生产 Room v31、cosine+RRF、FTS4+LIKE 词法兜底和 shadow 审计保持不变。
- 默认离线门禁：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 通过；Lint、Debug APK 和 AndroidTest APK 均成功。Debug APK 为 `22,944,378` 字节、SHA-256 `3294e1f13cf0b782f3ec3b804663aa05070676212f6dd8daaa364a8d1da7f8c6`。文档更新后仅在 Redmi 运行默认完整 instrumentation，`175/175`、0 failed，5 个显式联网用例缺参按设计 skipped；未启动或操作 Pixel_9/其他模拟器。

## 2026-07-24 Embedding 相关性扩样与 shadow 候选门禁（第 82 阶段）

- 纯 Kotlin 策略：`KnowledgeRelevanceCalibrationPolicyTest` 覆盖可分数据的分位数与完美候选、重叠数据不得伪造完美门禁，以及缺桶、NaN、同 case ID 标签漂移拒绝。策略使用 nearest-rank，并以正例接纳率、近负例拒绝率、远负例拒绝率三桶等权计算 balanced accuracy。
- 真实语料：原 10 篇文档各增加 1 篇同主题干扰文档，共 20 篇；正例、近负例、远负例各 10 条英文查询，每条在同一进程重复 2 次。词法命中全部为 0，每次语义检索有 20 个有效候选；20 行 1024 维 Float32 向量共 `81,920` 字节。
- Redmi 三轮：只使用 `wsvwypiz7xwslvl7`，从外部启动三次独立 instrumentation 进程，每轮 `1/1` 通过、60 个观测，共 180 个观测。三轮 Recall@1、Recall@5、MRR、重复排序稳定率均为 `1.0`；索引耗时 `14.712–15.332s`，查询中位数 `0.797–0.807s`、P95 `0.824–0.866s`。
- 分桶范围：正例 top1 P05/P50/P95 分别为 `0.6735–0.6741 / 0.7138–0.7145 / 0.7670–0.7678`；近负例为 `0.3463–0.3469 / 0.5144–0.5152 / 0.6063–0.6073`；远负例为 `0.3250–0.3250 / 0.3564–0.3569 / 0.4079–0.4083`。margin 的桶内分布仍有交叠，因此候选继续使用 top1 与 margin 组合。
- Shadow 候选：三轮最优 minimumTopScore 为 `0.6735–0.6741`，minimumScoreMargin 为 `0.0179–0.0184`，正例接纳率、近负例拒绝率、远负例拒绝率和同集 balanced accuracy 均为 `1.0`。该结果在同一数据集上选参和回测，未经过冻结阈值 holdout，不构成生产拒绝证据。
- 资源观测：PSS 基线 `174,039–174,241 KB`，索引后 `186,833–186,962 KB`，检索后 `202,124–202,359 KB`；Java heap 基线约 `5.88–5.92 MB`，索引后约 `4.89–4.91 MB`，检索后约 `27.28–27.37 MB`。网络耗时与内存继续只作当前设备/Provider 观测，不设绝对门禁。
- 决策：第 83 阶段先冻结 Provider/模型专属候选和数据集版本，使用不参与调参的独立 holdout 预注册正例保留率与两类负例拒绝率。生产 Room v31、cosine+RRF、词法兜底和“无相关性拒绝”保持不变。
- 完整门禁：JVM `491/491`、0 failed；Lint、Debug APK 和 AndroidTest APK 均成功。Debug APK 为 `22,927,994` 字节、SHA-256 `4ea3ba2ef068f98c1dc8319d0d8c630b8c90999bc987cd9449ca4315564d7610`；AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用哈希。只使用 Redmi `wsvwypiz7xwslvl7` 运行完整 instrumentation，`174/174`、0 failed，4 个真实 Provider 用例无显式参数时按设计 skipped；未启动或操作 Pixel_9/其他模拟器。

## 2026-07-24 Embedding 相关性 shadow 诊断与首轮校准（第 81 阶段）

- 生产审计：`KnowledgeRetrievalRecord` 和 Room v31 的 `knowledge_retrievals` 新增可空 top1、top2、margin、有效候选数字段。v30→v31 迁移 `1/1` 通过，旧记录四项均为 `null`；受控三候选检索的 `1.0 / 0.8 / 0.2 / 3` 写入与回读 `1/1` 通过。Provider 未执行/不可用保持未知，无索引或已有索引不可比较记录候选数 `0`。
- 用户可见诊断：知识管理审计卡在已有 Embedding 状态和 Provider/模型下显示“校准观测”，分数固定 4 位小数；历史/纯词法记录无空占位，单候选不伪造第二名或 margin。Redmi Compose `6/6` 通过。
- 真实校准：只在显式传入本地未跟踪配置时联网，使用内存 Room 和第 80 阶段同一 10 篇中文文档。正例、近负例、远负例各 2 条，6 条英文查询的词法命中均为 0、有效语义候选均为 10；用例输出 Provider/模型分桶 JSON，不输出 Base URL 或 API Key，最终复验 `1/1` 在 `15.799s` 内通过。
- 实测分布：正例 top1 `0.6806–0.7130`、margin `0.2743–0.2828`；近负例 top1 `0.6502–0.6854`、margin `0.1311–0.2114`；远负例 top1 `0.3704–0.4083`、margin `0.0274–0.0507`。两条正例均首位命中标注文档；近负例首两位落在预期的相邻主题，远负例仍返回 5 个近邻。
- 决策边界：当前数据说明绝对分数与 margin 组合具有区分潜力，但每桶只有 2 条，不能据此发布全局或跨模型阈值。生产 cosine 排名、RRF 和词法兜底保持不变，不新增拒绝状态；下一阶段先扩大分桶和 Provider/模型样本，再评估仅作用于纯语义候选的组合门禁。
- 完整门禁：JVM `488/488`、Lint `0` 项、Debug APK `22,927,994` 字节，Debug/AndroidTest APK 均构建成功；只使用 Redmi `wsvwypiz7xwslvl7` 运行文档更新后的完整 instrumentation，`174/174`、0 failed。默认无联网参数时 4 个真实 Provider 用例按设计 skipped；未连接、启动或操作 Pixel_9/其他模拟器。

## 2026-07-23 真实 Embedding 有界语料基线（第 80 阶段）

- 新增 `RealProviderKnowledgeScaleInstrumentedTest`，只在显式提供本地未跟踪配置时联网。测试直接组合生产 `OpenAiCompatibleClient`、`OpenAiKnowledgeEmbeddingProvider`、`RoomKnowledgeDocumentStore` 和 `KnowledgeSearchQualityPolicy`，仅写内存 Room，不写正式文档、向量或检索审计。
- 固定语料：10 篇中文单主题短文、10 个 chunk；5 个英文正例与文档正文没有词法交集，纯词法 Store 全部零命中，真实语义 Store 每例运行两次且均为 `USED`。
- 三轮 Redmi `wsvwypiz7xwslvl7` 真实联网验收使用三次独立 instrumentation 进程启动，均 `1/1` 通过；每轮 Recall@5 `1.0`、MRR `1.0`、重复排序稳定率 `1.0`。每轮索引耗时分别为 `8.881s / 7.935s / 10.039s`，中位数 `8.881s`；每轮 10 次查询的中位数为 `0.811s / 0.836s / 1.100s`，P95 为 `1.016s / 1.148s / 1.496s`。
- 向量与资源：每轮 10 行、1024 维、Float32 BLOB `40,960` 字节，与理论字节数一致；SQLite 内存页 `593,920` 字节。检索后 PSS 相对各轮基线增加 `7,358 / 15,941 / 15,694 KB`；该数据同时包含测试、HTTP/TLS、SQLite 与运行时分配，不作生产内存硬阈值。
- 负例边界：与语料无关的珊瑚产卵问题三轮均返回 5 个近邻。这符合当前无相似度阈值的排名实现，但不等同负例检索正确；下一阶段优先采集正负相似度分布并设计可校准拒绝策略。
- 决策：当前 10 行向量的线性 cosine 扫描没有性能证据支持 ANN；10 篇前台导入也不支持立即引入后台批量索引。这不是规模化结案，文档/chunk 数量明显增长后需重新测量。
- 最终门禁：完整 JVM `488/488`、Lint、Debug/AndroidTest APK 通过。Redmi 默认完整 instrumentation 的 JUnit XML 记录 `171` 个用例、`168` passed、`3` skipped、`0` failed；三个跳过项均是缺少显式公网参数时关闭的 Provider 验收。未连接、启动或操作 Pixel_9/其他模拟器。

## 2026-07-23 真实 Provider 语义检索端到端（第 79 阶段）

- 配置核验：本地未跟踪 `AGENTS.md` 的 Embedding Base URL 已修正为 OpenAI 兼容 `/v1` 根路径；API Key 与模型名完整。配置文件继续由 `.gitignore` 排除，本文不记录 URL、Key 或其他凭据。
- 协议实测：仅 Redmi `wsvwypiz7xwslvl7` 显式运行 `ProviderEmbeddingCompatibilityInstrumentedTest`，模型列表包含指定 Embedding 模型，`/embeddings` 返回两条非空、维度一致且只含有限值的向量，`1/1` 通过。
- 语义链实测：新增 `RealProviderKnowledgeSearchInstrumentedTest`，直接使用生产 `OpenAiCompatibleClient`、`OpenAiKnowledgeEmbeddingProvider` 与 `RoomKnowledgeDocumentStore`，内存 Room 隔离正式数据。英文查询在纯词法 Store 中 0 命中并记录 `LEXICAL_ONLY`；真实向量 Store 首位命中中文“专注方法”文档并记录 `USED`，Provider/模型身份、最终 chunk IDs、索引非零维度/分块数和显式重建均通过，`1/1`，总耗时约 `5.947s`。
- 数据边界：真实语义 E2E 不调用 `ProviderRepository.save()`，不切换正式聊天 Provider，不写手机正式知识文档、向量或检索审计；测试结束关闭内存数据库。协议冒烟后已使用本地兜底配置恢复聊天 Provider。
- 默认套件：两个真实联网测试都要求显式 instrumentation 参数；无参数时按设计 skipped。最终 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 记录 `170` 个用例、`168` passed、`2` skipped、`0` failed，跳过项正是 `ProviderEmbeddingCompatibilityInstrumentedTest` 与 `RealProviderKnowledgeSearchInstrumentedTest`。未连接、启动或操作 Pixel_9/其他模拟器。
- 当前证据证明该 Provider 与生产小批次请求、Float32 校验、Room 索引、cosine/RRF、检索审计和显式重建兼容；尚不代表大语料性能、任意 Provider 兼容、ANN 或后台批量索引已验证。

## 2026-07-23 Embedding 检索质量与兼容诊断（第 78 阶段）

- 新增纯 Kotlin `KnowledgeSearchQualityPolicy`；排名按文档 ID 去重后截取 K，输出 Recall@K、MRR、负例准确率和重复排序稳定率。JVM 覆盖空语料、单一正例、K 外命中、负例误命中、排序漂移及非法输入。
- AndroidTest 继续把 5 份核心长期 `docs/` 打包为固定语料；5 个正例、1 个负例各执行两次，Recall@5 `1.0`、MRR `>= 0.8`、负例准确率 `1.0`、稳定率 `1.0`，并保留既有首位命中断言。
- 知识管理页检索审计新增 `USED / LEXICAL_ONLY / NO_INDEX / PROVIDER_UNAVAILABLE / DIMENSION_MISMATCH` 路径说明；Provider/模型为空时不显示多余分隔符，零命中仍显示审计与回退原因。Redmi 聚焦 Compose `6/6` 通过。
- 协议级兼容：MockWebServer 继续验证 `/v1/embeddings`、Bearer、默认/自定义 User-Agent、自定义 Header、模型字段、响应 index 恢复和异常维度拒绝。
- 真实 Provider：只使用 Redmi `wsvwypiz7xwslvl7`，从未跟踪的本地配置显式运行兼容测试；`/models` 同步成功并把 Provider 恢复到应用。同步列表没有可识别的 Embedding 模型，测试在能力门禁处跳过 `/embeddings`。已验证的是 Provider 可用、模型同步和词法兜底，不是上游真实向量兼容。
- JVM：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest --rerun-tasks --stacktrace --console=plain`，`488/488`、0 failed、0 skipped。
- 静态与 APK：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain`，通过。
- Redmi 完整 instrumentation：`ANDROID_SERIAL=wsvwypiz7xwslvl7 JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:connectedDebugAndroidTest --rerun-tasks --stacktrace --console=plain`，JUnit XML 记录 `169` 个用例、`168` passed、`1` skipped、`0` failed；唯一跳过项是无显式公网参数时默认关闭的 Provider 冒烟。未连接、启动或操作 Pixel_9/其他模拟器。
- 本阶段当时不宣称任意 Provider 的 Embedding 兼容、ANN、自动后台批量重建或规模化召回/性能；第 79 阶段已使用独立真实 Embedding Provider 补齐单一兼容 Provider 的完整语义路径，其他边界不变。

## 2026-07-23 Embedding 索引生命周期（第 77 阶段）

- 生产实现新增前台单文档 `rebuildEmbeddings()`、索引摘要查询和知识详情重建入口；旧文档可在 revision 不变时补建当前 Provider/模型索引，详情显示 Provider、模型、维度与分块数。
- Room 写入在 Provider 请求和响应校验成功后执行，事务内再次核对 revision、enabled 和 chunk 身份；只删除当前 `documentId + providerId + model` 空间。Provider/模型切换后索引并存，失败、超时、停用与并发替换不删除已有向量；正文替换和删除仍清理全部旧空间。Schema 保持 v30。
- JVM：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest --rerun-tasks --stacktrace --console=plain`，`483/483` 通过。
- 静态与 APK：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain`，通过。
- 真机：仅设备 `wsvwypiz7xwslvl7`（Redmi Note 8 Pro，Android 14 / API 34）执行完整 instrumentation，`164/164`，0 skipped、0 failed。定向知识 Store `17/17`、ViewModel/Compose `9/9` 先行通过；未连接、启动或操作 Pixel_9/其他模拟器。
- 关键覆盖：旧文档显式补建且 revision 不变、A/B Provider 空间共存、重复重建 A 不删除 B、Provider 失败/超时/无效响应保留旧索引、停用拒绝、并发 revision 漂移拒绝写入、摘要维度/分块数，以及 UI 无索引提示、索引详情和重建入口。
- 当前仍是有限规模内存 cosine；不宣称 ANN、自动后台批量重建、任意 Provider 兼容或规模化性能。

## 2026-07-23 Embedding 检索 v1（第 76 阶段）

- 生产实现新增 Provider `/embeddings` 请求、Float32 little-endian 编解码、Provider 身份隔离、RRF 融合和词法回退；Room Schema 从 29 升到 30。v29→v30 MigrationTestHelper 验证旧检索记录保留且 `embeddingStatus=LEXICAL_ONLY`，新向量表为空，不凭历史正文补造向量。
- JVM：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest --rerun-tasks --stacktrace --console=plain`，通过；包含网络、Embedding 核心和 Agent Profile Provider 身份测试。
- 静态与 APK：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain`，通过。
- 真机：设备 `wsvwypiz7xwslvl7`（Redmi Note 8 Pro，Android 14 / API 34）执行 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew :app:connectedDebugAndroidTest --stacktrace --console=plain`，共 `158/158`，0 skipped、0 failed。定向知识存储 `14/14`、数据库迁移 `25/25` 也已先行通过；未连接、启动或操作 Pixel_9/其他模拟器。
- 关键覆盖：语义-only 命中、语义与词法重叠去重、Provider 失败和无索引回退、查询维度不符、替换/删除清理旧向量、检索审计 chunk IDs 与最终 hits 一致、最终 enabled/revision 复核。
- 本阶段仍不等同规模化 ANN 性能验证，不引入设备 Workflow、后台设备自动化、Foreground Service 或精确定时。

## 环境

- macOS 原生环境 + zsh
- Android 真机：`wsvwypiz7xwslvl7`
- 设备型号：Redmi Note 8 Pro
- Android：14 / API 34
- App 包名：`com.longdev.xiaoling`
- App 展示名：小灵

## 2026-07-23 `/agent` 附件输入 v1（第 75 阶段）

- Responses 规划请求支持 USER 单一 Image 或 Document，附件沿用既有校验与 Data URL 映射；每轮规划复用同一附件，summary 请求不携带附件。
- 初次 Agent 发送先提交含附件的 USER MessagePart，再建立 Run；进程重建审批恢复和任务中心重试从 Room 原 USER 消息重建，重试复制到新 USER 消息并保持旧 Run 不变。
- Chat Completions、混合附件、assistant/Tool 伪造附件和 `VerifiedAgentContext`/Tool part 进入附件均 fail-closed；Workflow/后台 Agent 无附件入口。
- 聚焦 JVM 新增持久化重复/混合附件拒绝覆盖；完整 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 通过，JVM `477/477`、0 失败、0 跳过，Lint、Debug APK 和 AndroidTest APK 均成功。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 执行 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew :app:connectedDebugAndroidTest --stacktrace --console=plain`，完整 instrumentation `153/153`、0 失败、0 跳过；没有连接、启动或操作 Pixel_9/其他模拟器。
- 真实 `/agent` E2E：图片 Run `run-e2c23f3d-c7f9-41cc-9964-e0364741727e` 调用 `notes.create`，创建并回读 `IMAGE_STAGE75_E2E`，ToolResult 为 `PASSED`；文档 Run `run-9e66e0eb-7684-4d92-8e5d-cfd3ec044d10` 调用 `notes.create`，创建并回读 `DOC_STAGE75_E2E`，ToolResult 为 `PASSED`。负向 Run `run-9f4c1380-60de-4998-b689-65d570812431` 在模型直接返回 `complete` 且未调用工具时，被 Runtime 以“模型未执行任何工具就结束了 Agent Run”拒绝。
- instrumentation 后测试包无残留；正式 Debug APK 覆盖安装并冷启动到 `com.longdev.xiaoling/.MainActivity`，前台 PID `29016`，Room `user_version=29`，crash buffer 无小灵记录。当前 Debug APK 为 `22878842` 字节，SHA-256 `bd8f7bb6d170c91acf5a60b75337be0893eb70cbef8149caca9cbed4d71e456`；AndroidTest APK 为 `1850202` 字节，SHA-256 `d64fdbf627b6d15e86b7e36c437a5443c8be5f4ffa85be2848002ba49f94b3b2`。

## 2026-07-23 网络请求设置页交互统一（第 74 阶段）

- 设置根页“网络请求”已改为与其他设置项一致的入口卡片，点击后进入独立页面，不再允许在根页行内编辑 User-Agent。独立页面输入区默认至少 5 行，右下角提供复制和清空按钮，标题区保留恢复默认操作；空值时复制和清空禁用。
- 新增 `NetworkRequestSettingsContentInstrumentedTest`，覆盖复制当前值、清空、空值操作禁用、重新输入、恢复默认和返回设置。新增单项在 Redmi 上 `1/1` 通过，完整 instrumentation 为 `153/153`、0 跳过、0 失败；本阶段未连接或操作 Pixel_9/其他模拟器。
- Redmi 真实 UI 验收确认根页入口样式统一且可进入独立页面；输入区域 UI bounds 为 `[47,332][1033,656]`，复制和清空按钮位于右下角，没有重叠或截断。截图与 UI tree 分别保存在本机 `/tmp/xiaoling-network-request.png` 和 `/tmp/xiaoling-network-request.xml`。
- 标准 Gradle 门禁完整执行成功：JVM `472/472`、0 跳过、0 失败，Lint、Debug APK 和 AndroidTest APK 均构建成功。instrumentation 后测试包不存在，当前 Debug APK 覆盖安装并以 `LaunchState: COLD` 启动到 `com.longdev.xiaoling/.MainActivity`；Room `user_version=29`，正式包进程存在且 crash buffer 为空。

## 2026-07-23 小灵 v0.1.11 发布

发布构建与产物：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --rerun-tasks --stacktrace --console=plain` 通过；JVM `472/472`、Lint、Debug/AndroidTest APK 和 Release APK 均成功，Release `lintVital` 同时通过。
- `aapt dump badging` 确认包名 `com.longdev.xiaoling`、`versionName=0.1.11`、`versionCode=12`、`minSdk=26`、`targetSdk=36`。
- `apksigner verify --verbose --print-certs` 确认 v2 签名有效，单一签名者证书 SHA-256 为 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9`。
- 发布 APK：`outputs/release/xiaoling-v0.1.11.apk`，SHA-256：`f5a389d6326010fe7ae996a2df2824b9d3d07978269e21ca2f7b931118370559`；同目录已生成 `.sha256` 校验文件。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不在文档自身记录其哈希。

GitHub Release：

- Release：[小灵 v0.1.11](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.11)
- APK：[xiaoling-v0.1.11.apk](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.11/xiaoling-v0.1.11.apk)
- SHA-256：[xiaoling-v0.1.11.apk.sha256](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.11/xiaoling-v0.1.11.apk.sha256)

## 2026-07-23 会话选择与删除副作用协调迁出（第 73 阶段）

- 新增 `ConversationSelectionCoordinator` 和稳定 `DeletionStarted / Immediate / Load` 事件，组合既有 Session Policy、Persistence Coordinator 与 Load Coordinator。删除路径固定为取消旧加载、标记删除代次、先清理已删除会话运行态、再即时选择或完整加载；当前加载失败先回滚捕获的删除代次，再发布 Failed。
- `ConversationLoadRequest` 不再携带 `rollbackDeletionIntentOnFailure`，加载协调器只负责 Job/代次和 Loaded/Failed 隔离。`XiaoLingViewModel` 统一消费选择事件，只读取/清理 Agent Run 与审批 Map、执行纯 UI 投影并在 Immediate/Loaded 后保存，从 4121 行降到 4087 行。
- 新增四条聚焦用例，覆盖失败发布前回滚、旧失败不清理同 ID 新删除意图、删最后会话先清理再即时选择，以及新建会话取消迟到加载。使用 Kotlin 2.3.20 编译相关生产源码与六个会话测试类后，聚焦 `4/4`、第 68 至 73 阶段组合 `30/30` 通过；另以 Android 36 和既有依赖 classpath 手工编译完整 `XiaoLingViewModel.kt` 通过。
- 标准命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 通过；JVM XML 汇总 `472/472`、0 失败、0 跳过，Lint、Debug APK 和 AndroidTest APK 均成功。Debug APK 为 `22862454` 字节、SHA-256 `f41970c23c21f566c7dea38955ecad78412e8e6e61c61ac4f9d2bd7aff3a8fcf`，AndroidTest APK 为 `1843430` 字节、SHA-256 `f420e30615bdb3d204b7ced4e0ddf45e8daea857a0666721a3aa98a33d822851`。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 执行 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew :app:connectedDebugAndroidTest --stacktrace --console=plain`，完整 instrumentation 为 `152/152`、0 跳过、0 失败，用时 `49s`；本轮没有连接或操作 Pixel_9/其他模拟器。
- instrumentation 后测试包不存在（卸载命令返回 `DELETE_FAILED_INTERNAL_ERROR`，表示设备上本来没有残留测试包），正式 Debug APK 覆盖安装成功并冷启动到 `com.longdev.xiaoling/.MainActivity`，前台 PID `11873`，Room `user_version=29`，设备仅保留正式包，crash buffer 为空。

## 2026-07-23 会话新建与删除选择规则迁出（第 72 阶段）

- `ConversationSessionPolicy` 新增纯 `Immediate / Load` 计划，固定当前空会话幂等复用、最新空占位折叠、新占位创建、删除后最新会话选择和删空兜底。新占位通过 `restoreRuntimeState=false` 明确清空 Agent Run/审批。
- 一轮有效 Red/Green 建立选择计划 seam；`ConversationSelectionPolicyTest` 五条覆盖三类新建路径和两类删除路径，聚焦结果 `5/5`。第 68 至 72 阶段状态/选择/保存/加载/投影组合 `26/26` 通过。
- `XiaoLingViewModel` 从 4178 行降到 4121 行；取消加载、删除意图、运行态 Map、完整消息加载、失败回滚和选择保存顺序不变。Room v29、Repository、协议、UI、`/agent` 与 Workflow 行为不变。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --console=plain` 通过；JVM XML 汇总 `468/468`、0 跳过、0 失败。
- 仅使用 Redmi `wsvwypiz7xwslvl7`。第一次完整 instrumentation 为 `150/152`：两条相邻 Compose 测试在启动时报告 `No compose hierarchies found in the app`，两条单项重试均 `1/1` 通过；卸载并重装测试包后第二次完整运行 `152/152`、0 跳过、0 失败，用时 `36.841s`。没有使用或连接模拟器。
- 最终卸载测试包、重新安装正式 Debug APK并冷启动。`MainActivity` 前台 PID `7526`，Room `user_version=29`，设备仅保留 `com.longdev.xiaoling` 正式包，测试包不存在，crash buffer 为空。

## 2026-07-23 会话加载 UI 投影规则迁出（第 71 阶段）

- 新增纯 Kotlin `ConversationLoadProjectionPolicy`，统一 Loading 清理旧结果、Loaded 原子选择会话和 Failed 错误收敛。非当前会话索引剥离 Image/Document BLOB，当前可见会话在同一次状态替换中注入完整消息与附件。
- 一轮有效 Red/Green 先确认缺少独立投影 seam；`ConversationLoadProjectionPolicyTest` 三条覆盖 Loading、Loaded、Failed，以及 Image/Document 轻量化、当前二进制完整保留、异常消息与 fallback。聚焦结果 `3/3`。
- `XiaoLingViewModel` 从 4200 行降到 4178 行；删除意图仍先回滚再投影失败，Loaded 仍读取目标会话的 Agent Run/审批并在投影后保存选择。`ConversationLoadCoordinator`、Repository、Room v29、协议、UI、`/agent` 与 Workflow 行为不变。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --console=plain` 通过；JVM XML 汇总 `463/463`、0 跳过、0 失败。仅向 Redmi `wsvwypiz7xwslvl7` 安装测试 APK 并执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败，用时 `37.469s`；在线的 `emulator-5554` 未接收任何 ADB 命令。
- instrumentation 后卸载测试包、重新安装正式 Debug APK并冷启动。最终 `MainActivity` 前台 PID `29344`，Room `user_version=29`，设备仅保留 `com.longdev.xiaoling` 正式包，测试包不存在，crash buffer 为空。

## 2026-07-23 异步会话加载协调迁出（第 70 阶段）

- 新增纯 Kotlin `ConversationLoadCoordinator`，统一 latest-load Job、单调选择代次和 Loading/Loaded/Failed 事件。底层查询即使在取消后仍迟到成功或失败，旧代次不会覆盖新选择、删除回滚或失败提示。
- 四轮 TDD 固定迟到成功隔离、迟到失败隔离、显式取消后的迟到结果隔离，以及 Loading 回调重入后仍取消最新 Job。聚焦 `ConversationLoadCoordinatorTest` 为 `4/4`，与第 66 至 69 阶段上下文/发送/状态/保存测试组合通过。
- ViewModel 不再持有会话加载 Job 或直接编排 Room 加载 try/catch；它仍负责完整消息与轻量会话的原子 UI 投影、删除后选择下一会话、删除意图回滚和选择保存。`ConversationRepository`、附件 BLOB、Room v29、协议、UI、`/agent` 与 Workflow 行为不变。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --console=plain` 通过；JVM XML 汇总 `460/460`、0 跳过、0 失败。仅使用 Redmi `wsvwypiz7xwslvl7` 手动安装测试 APK 并执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。
- instrumentation 后执行 `adb install -r` 重新安装正式 Debug APK并冷启动。最终 `MainActivity` 处于前台且应用 PID 存在，Room `user_version=29`，设备仅保留 `com.longdev.xiaoling` 正式包，测试包不存在，crash buffer 为空。

## 2026-07-23 会话保存协调迁出（第 69 阶段）

- 新增纯 Kotlin `ConversationPersistenceCoordinator`，统一 latest-save Job、Room 单写者、发送前等待旧保存，以及显式删除 ID 的代次确认与读取失败回滚。旧事务即使进入不可取消提交区，最新快照也会等待并最后写入；同 ID 在旧事务期间重新标记时不会被旧提交误确认。
- 八轮 TDD 固定旧快照取消、不可取消提交后的最新写入、发送前等待、删除成功确认、失败保留、回滚、同 ID 重标记和旧回滚保护。聚焦 `ConversationPersistenceCoordinatorTest` 为 `8/8`，与第 66 至 68 阶段上下文/发送/状态测试组合通过。
- `XiaoLingViewModel` 不再持有会话保存 Job、待删除集合和保存私有实现，从固定点 `d29a17f` 的 4189 行降到 4183 行。异步会话加载、删除后的 UI 切换/失败提示和 Compose 副作用仍留在 ViewModel；`ConversationRepository`、附件 BLOB、Room v29、协议、UI、`/agent` 与 Workflow 行为不变。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --console=plain` 通过；JVM XML 汇总 `456/456`、0 跳过、0 失败。仅使用 Redmi `wsvwypiz7xwslvl7` 手动安装测试 APK 并执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。
- instrumentation 后执行 `adb install -r` 重新安装正式 Debug APK并冷启动。最终 `MainActivity` 处于前台且应用 PID 存在，Room `user_version=29`，设备仅保留 `com.longdev.xiaoling` 正式包，测试包不存在，crash buffer 为空。

## 2026-07-22 会话状态投影规则迁出（第 68 阶段）

- 新增纯 Kotlin `ConversationSessionPolicy`，统一第一条 `role=user` 消息生成标题（trim 后最多 18 字符；正文空白时保持“新会话”且不向后跳过）、多个空会话只保留 preferred/最新占位、既有会话保留 `createdAt` 并推进 `updatedAt`、摘要边界/更新时间/模型默认继承、blank ID 按注入时钟生成，以及非当前会话迟到更新不污染当前 UI。
- 六轮 TDD 有效 Red/Green 分别固定标题、空占位、已选会话时间与可见状态、非当前隔离、blank ID 既有语义和摘要元数据继承。新增聚焦 `ConversationSessionPolicyTest` 为 `6/6`；与 `ConversationSendCoordinatorTest / StreamingMessagePresentationTest` 的组合回归通过。
- `XiaoLingViewModel` 删除四段对应私有规则，共减少 83 行，从固定点 `9b5edc1` 的 4272 行降到 4189 行。异步 Room 加载、保存 Job、删除事务和 Compose 副作用仍留在 ViewModel；Room v29、Provider 协议、消息结构、UI、`/agent` 与 Workflow 行为不变。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过；JVM XML 汇总 `448/448`、0 跳过、0 失败。仅使用 Redmi `wsvwypiz7xwslvl7` 手动安装测试 APK 并执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。
- instrumentation 后执行 `adb install -r` 重新安装正式 Debug APK并冷启动。最终 `MainActivity` 处于前台且应用 PID 存在，Room `user_version=29`，设备仅保留 `com.longdev.xiaoling` 正式包，测试包不存在，crash buffer 为空。

## 2026-07-22 普通聊天网络发送编排迁出（第 67 阶段）

- 新增纯 Kotlin `ConversationSendCoordinator`，按“Room 快照持久化 → 上下文准备 → 模型请求 → 流式增量 → 终态事件”顺序执行，并以 `SnapshotPersisted / ContextPrepared / StreamDelta / Completed / Cancelled / Failed` 形成单一状态机 seam。
- TDD 三轮有效 Red 依次确认：缺少协调器 seam；网络取消虽传播但没有携带已准备上下文的 `Cancelled`；发送前 Room 异常直接逃逸且没有阻止后续调用。最终聚焦新增 JVM `3/3`，完整 JVM XML 汇总 `442/442`、0 跳过、0 失败。
- `XiaoLingViewModel.sendMessage()` 从约 190 行收敛到约 104 行，ViewModel 保留入口校验、Compose 状态投影、30ms 流式节流与 Job 生命周期。取消事件完成 UI 收敛后继续传播 `CancellationException`，所以底层 OkHttp 请求仍真实取消；普通异常保持原错误分类和部分正文“不完整”语义。
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过；仅使用 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。
- instrumentation 后重新安装正式 Debug APK并冷启动；最终 `MainActivity` 前台 PID `18078`，Room schema 29，仅 `com.longdev.xiaoling` 正式包存在，crash buffer 为空。Room、协议、UI、`/agent`、Workflow 及 Foreground Service 后置边界未改变。

## 2026-07-22 普通聊天上下文准备迁出 ViewModel（第 66 阶段）

- 新增 `ConversationRequestContextPreparer`，通过注入 seam 独立覆盖短会话直通、长会话只压缩最近 16 条之前的增量、摘要边界复用、失效知识消息/摘要清理和摘要取消传播；原 `CurrentKnowledgeContextTest` 继续保护知识投影。
- TDD 七轮有效 Red 分别暴露缺少新 seam、长会话保护、重复摘要调用、旧知识摘要未失效、取消被本地摘要吞掉、摘要边界 ID 丢失后仍复用旧摘要，以及边界超前导致反向增量区间；Responses 最近窗口附件另以特征测试锁定。最终聚焦新增 JVM `8/8`，完整 JVM XML 汇总 `439/439`、0 跳过、0 失败。
- `XiaoLingViewModel` 从 4439 行降为 4224 行，只装配知识 Store、摘要网络调用和提示词设置；Room Schema 保持 v29，请求协议、消息持久化、UI 和 Agent/Workflow 边界不变。
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过；仅使用 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。正式 Debug APK 已重新安装并回到 `MainActivity` 前台，测试包已清理。

## 2026-07-22 进程退出观察只读诊断 UI（第 65 阶段）

实现与边界：

- 设置页新增“进程退出观察”入口和独立子页；ViewModel 刷新只调用 `RoomProcessExitObservationStore.latest()`，不调用 `collect()`，所以打开页面或点击刷新不会改变系统观察样本。刷新 Job 被替换时继续传播协程取消。
- 页面只展示 Room v29 已有稳定字段，以中文标签区分 `DIRECT_LOW_MEMORY / LOW_MEMORY_CANDIDATE / APP_FAILURE / SYSTEM_RESOURCE / CONTROLLED_OR_MAINTENANCE / UNATTRIBUTED`。页面固定说明记录不关联 Agent Run、工作流或任务，候选与受控退出不能作为自然 LMK。
- 本阶段不增加 Schema、Task/Run 外键、自动恢复、Foreground Service 或设备 Workflow/后台自动化能力。

自动化与 Redmi 结果：

- TDD 先得到缺少 Composable 和缺少记录标签的两次预期失败，再补最小实现。Redmi 聚焦 Compose 测试 `3/3` 通过，覆盖空态、证据边界、刷新回调、直接 LMK/候选/受控退出差异，以及全部六类证据的稳定中文标签。
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过，JVM 保持 `431/431`；仅使用 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation，结果 `152/152`、0 跳过、0 失败。
- 正式 Debug APK 冷启动后执行一次明确的 `am force-stop com.longdev.xiaoling`，重新启动后 Room 为 1 条 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`。真实设置入口和详情页视觉验收通过；页面点击刷新后数据库仍为 1 条，证明只读刷新没有触发新采集。
- 完整 `152/152` 结束后重新安装正式 Debug APK；最终 `MainActivity` 保持前台，PID `12951`，正式数据库 schema 29、退出账本 0 条，crash buffer 为空；设备仅保留 `com.longdev.xiaoling` 正式包，测试包已清理。最终空账本不覆盖前述受控样本的页面验收结论。

当前结论：用户现在可以不依赖 ADB 查看进程退出证据，但页面不提供因果归因。当前唯一正式样本仍是受控 `force-stop`，不是自然 LMK，不改变普通 WorkManager、fail-closed 恢复和 Foreground Service 后置结论。

## 2026-07-22 Android 进程退出观察账本（第 64 阶段）

实现与证据边界：

- Android 11+ 通过生产 `AndroidProcessExitObservationSource` 读取本应用 `ApplicationExitInfo`；前台 ViewModel 在启动恢复快照前补采，`ScheduledWorkflowWorker` 在登记当前进程 Task 所有权后、执行 Repository/Workflow 前补采。旁路采集普通异常不阻断主流程，`CancellationException` 必须继续传播。
- Room v28→v29 新建独立 `process_exit_observations` 表，保存 timestamp、processName、pid、reason/status、importance、PSS/RSS、LMK 报告能力、稳定分类和首次观察时间；不含 Task/Run 外键，不保存 description、trace 或进程状态摘要。重复历史以稳定 ID 去重并在同一事务裁剪到最新 30 条，迁移不发明历史记录。
- 只有 `REASON_LOW_MEMORY` 分类为 `DIRECT_LOW_MEMORY`；设备不支持直接 LMK 原因报告时，`REASON_SIGNALED + SIGKILL` 仅分类为 `LOW_MEMORY_CANDIDATE`。用户停止、应用取消、权限/包状态变化固定归为 `CONTROLLED_OR_MAINTENANCE`，不得凭时间邻近关联某个 Task/Run。

自动化与 Redmi 结果：

- 聚焦策略单测和 AndroidTest 编译通过；新增取消安全回归证明普通诊断异常被隔离、协程取消继续传播。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 执行 v28→v29 迁移、Room 去重/30 条裁剪和真实 `ApplicationExitInfo` 来源，聚焦 `5/5` 通过。完整 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew :app:connectedDebugAndroidTest --console=plain` 为 `149/149`、0 跳过、0 失败；没有启动、连接或操作 Pixel/其他模拟器。
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 通过；Gradle XML 汇总为 JVM `431/431`，Lint、Debug APK 和 AndroidTest APK 均成功。
- 正式 Debug APK 冷启动后只读回查 `PRAGMA user_version=29`，初始退出账本为 0。执行一次明确的 `am force-stop com.longdev.xiaoling` 后重新冷启动，账本为 1 条 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`；没有直接 LMK 或候选 LMK。
- 完整 instrumentation 结束后已卸载测试包并重新安装正式 Debug APK；最终冷启动 `MainActivity` 成功，前台 PID `5557`，crash buffer 为空。正式数据库 `PRAGMA user_version=29`、退出观察表为 0 条，设备仅保留 `com.longdev.xiaoling` 正式包。重新安装后的空账本是最终设备状态，不覆盖上一条受控 `force-stop` 的阶段验收记录。

当前结论：本阶段证明生产采集、分类、去重、裁剪和迁移链可用，并且受控停止不会伪装成自然回收。它没有取得 Android 自主 LMK、系统配额、超时或自然进程回收证据，不支持预先引入 Foreground Service、恢复无法证明的旧执行栈，或把设备工具开放到 Workflow/后台自动化。

## 2026-07-22 Redmi 真实 WorkManager 应用取消原因（第 63 阶段）

验证目标与边界：

- 在 Redmi Android 14 上入队真实 `CoroutineWorker`，等待 Worker 已运行后通过 WorkManager `cancelWorkById()` 取消；不注入停止码、不使用强杀、Doze、trim-memory 或模型请求。
- Worker 在 `CancellationException` 出口读取真实 `stopReason`，再调用生产 `ScheduledWorkerStopReasonPolicy`。结果为 `code=1 / name=CANCELLED_BY_APP`。
- Room 聚焦契约先写入用户 `STOP_REQUESTED`，再模拟同一真实机制码到达结算入口；ScheduledTask 与 WorkflowRun 均为 `CANCELLED`，用户停止原因保持一致，`workerStopReasonCode/Name` 均为空，证明机制码没有覆盖业务意图。

验证结果：

- `assembleDebugAndroidTest` 通过；Redmi 聚焦两条测试 `2/2` 通过。
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过；JVM 保持 `424/424`。
- 仅在 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation，结果 `145/145`、0 跳过、0 失败；测试期间临时保持 USB 亮屏，命令退出时恢复原设置。没有启动、连接或操作 Pixel_9/其他模拟器。
- 测试包已卸载；正式 Debug APK 重新安装并启动到 `com.longdev.xiaoling/.MainActivity`，PID `518`，crash buffer 为空。正式数据库 `PRAGMA user_version=28`，计数为 `providers=1`、`agent_profiles=1`、`workflows=0`、`scheduled_tasks=0`、`workflow_runs=0`、`agent_tool_results=0`。

证据结论：本阶段证明真实“应用取消”停止码可读，并验证用户停止栅栏优先级；它不是 Android 自主 LMK、系统配额、运行超时或自然回收样本，不能据此引入 Foreground Service 或扩大旧执行栈恢复。

## 2026-07-22 Redmi 后台 Worker 停止原因审计（第 62 阶段）

实现与边界：

- `ScheduledWorkflowWorker` 在 Android 12+ 的取消路径读取 WorkManager `getStopReason()`；稳定映射只保存停止码、分类名和标准化中文消息，不保存模型请求、任务参数或设备隐私。Android 12 以下、`STOP_REASON_NOT_STOPPED` 和无法识别的码保持保守结论。
- Room 升级到 v28：`workflow_runs` 与 `scheduled_tasks` 各新增可空 `workerStopReasonCode`、`workerStopReasonName`。v27→v28 迁移只加空列，不从历史 `errorMessage` 补造停止原因；生产结算在同一事务将相同原因写入 Task 与 WorkflowRun，终态和 `STOP_REQUESTED` 栅栏优先保留已持久化事实。
- 任务中心的调度实例和 Workflow Run 详情显示标准化停止分类。本阶段不改变旧执行栈 fail-closed 恢复、不把 WorkManager 停止转换为 `Result.retry`，也没有引入 Foreground Service。

验证结果：

- 本地：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；JVM 测试 `424/424`。
- Redmi 聚焦：迁移测试与停止原因双表持久化 `OK (2 tests)`。完整 instrumentation 在 Redmi 保持唤醒后 `OK (143 tests)`；首轮屏幕休眠导致的 6 条 Compose 挂载失败已全部复跑通过，不计为代码失败。
- Redmi 收尾：测试包 `com.longdev.xiaoling.test` 已卸载；正式 `MainActivity` 前台运行，PID `28929`，crash buffer 为空。正式数据库 `PRAGMA user_version=28`，核心计数为 `providers=1`、`agent_profiles=1`、`workflows=0`、`scheduled_tasks=0`、`workflow_runs=0`、`agent_tool_results=0`。

证据边界：本阶段通过确定性 `QUOTA(10)` 映射和 Room 原子持久化测试验证数据链路，没有人为制造或宣称自然 Android 系统停止样本；Foreground Service、精确定时与后台自动化仍按路线图等待真实证据。

## 构建验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleRelease --stacktrace --console=plain
```

结果：

```text
BUILD SUCCESSFUL
```

## 初始 Room Schema 与迁移自动化验证（历史基线）

本节保留早期 v10 迁移取证；当前 Room v29 和完整回归证据见文首最新阶段记录。

Schema 生成方式：

- Room compiler 使用 KSP `2.3.7`，通过 Room Gradle Plugin `2.8.4` 的 `schemaDirectory` 导出到 `app/schemas/`。
- v4 Schema 从固定历史提交 `425717b` 的数据库实体生成，v6 保存 RunEvent metadata 改造前结构，v7 保存重试关联改造前结构，v8 保存 Memory FTS 改造前结构，v9 保存候选表引入前结构，v10 由当前源码生成。
- 历史中数据库版本曾直接从 v4 跳到 v6，因此没有可复现的独立 v5 源码快照；自动化测试仍按正式 migrations 顺序执行到 `MIGRATION_9_10`。

单测试命令：

```zsh
adb -s wsvwypiz7xwslvl7 shell am instrument -w -r -e class com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

```text
OK (6 tests)
```

已验证：

- 带真实旧数据的 v4 数据库通过正式 migrations 升级到 v10，Room 最终 Schema 校验通过。
- Provider、会话、用户/assistant 消息、Agent Run、Step、审批、Run Event、笔记和长期记忆均可通过 DAO 回读。
- v4 旧消息迁移后 `origin=LEGACY`，`verifiedAgentContext=null`。
- v6 中保存在 `RunEvent.message` 的旧 JSON object 会迁入 `metadataJson`，原 message 改为事件可读摘要；普通文本 message 不会被误标为结构化 metadata。
- v7 旧 Run 升级到 v8 后，状态、结果和错误保持不变，`retryOfRunId` 初始化为 `null`。
- v8 旧记忆升级到 v9 后，`pinned` 初始化为 `false`，FTS 索引立即可检索，无需再次编辑。
- v9 正式记忆升级到 v10 后保持原内容和 FTS，新增候选表为空，不会要求用户重复确认历史记忆。
- 全新 v10 内存数据库可以创建、打开并执行 DAO 查询。
- Room 2.8.4 需要 kotlinx serialization 1.8.1；工程已统一该现有传递依赖版本，避免 KSP Schema 导出和 `room-testing` 运行时接口不一致。

完整回归命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s wsvwypiz7xwslvl7 shell am instrument -w -r com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

```text
101 JVM tests passed
OK (15 Android tests)
BUILD SUCCESSFUL
```

补充说明：首次加入 `lintDebug` 时发现相机权限缺少可选硬件声明；已增加 `android.hardware.camera` 且 `required=false`，重跑完整命令后 lint 通过。

## Provider Adapter 与 Responses 结构化历史验证

实现依据：OpenAI 官方迁移文档确认 Responses API 的 `input` 可以直接接收消息列表，简单文本消息可复用 `role/content` 结构；system 或 developer guidance 也可以使用兼容消息 Item。参考：[Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses)。

单测试命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.network.OpenAiCompatibleAdapterTest
```

已验证：

- Responses 请求的 `input` 是 JSON 数组，system/user/assistant 的角色和正文逐条保留，不再拼接成单一字符串。
- Chat Completions 继续使用 `/chat/completions`、`messages` 和 `max_tokens`，不会混入 Responses 字段。
- `LlmProviderAdapter` 负责 Provider URL、payload 和响应映射，`OpenAiCompatibleClient` 负责 HTTP、取消、计时和 SSE 读取。
- `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` 完整回归通过：70 条 JVM 单元测试、7 条 Redmi Note 8 Pro 真机测试全部通过，lint 与 debug 构建成功。

## RunEvent metadata 与 Responses 函数 Items 验证

实现依据：OpenAI 官方 Function calling 文档规定 Responses 的函数调用 Item 使用 `type=function_call`、`name`、JSON 字符串 `arguments` 和 `call_id`；执行结果使用 `type=function_call_output`、同一 `call_id` 与 `output`。参考：[Function calling](https://developers.openai.com/api/docs/guides/function-calling)。

定向验证命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.agent.MinimalAgentRuntimeTest --tests com.longdev.xiaoling.network.OpenAiCompatibleAdapterTest --tests com.longdev.xiaoling.ui.AgentRunEventPresentationTest
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.storage.RoomAgentRunRepositoryInstrumentedTest
```

已验证：

- ToolCall、ToolResult、审批、失败与恢复事件通过 sealed `RunEventMetadata` variants 暴露合法字段组合，新事件的 `message` 只保存可读摘要。
- Room v7 引入独立 `metadataJson` 列，当前 v10 中 metadata 可以通过 Repository round-trip，v6 旧 JSON event 可迁移且特殊字符不丢失。
- 任务中心 UI 直接读取 typed metadata，不再解析数据库 JSON；纯文本历史事件继续回退显示原文。
- Responses `input` 可同时包含消息、`function_call` 和 `function_call_output`，调用与结果通过相同 `call_id` 关联。

## Agent 任务中心与安全重新运行验证

定向命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.agent.AgentTaskRetryPolicyTest --console=plain
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest#migrate7To8PreservesLegacyRunAndInitializesRetryLink,com.longdev.xiaoling.storage.RoomAgentRunRepositoryInstrumentedTest#retryCreatesLinkedRunWithoutChangingSourceRun --console=plain
```

已验证：

- `FAILED / CANCELLED / BUDGET_EXHAUSTED` 可重试，其他状态不可重试。
- 已成功执行非 SAFE 工具、启动恢复记录表明中断发生在 `EXECUTING/VERIFYING`，或 `tool.execute/tool.verify` 步骤以失败/取消结束时，重试策略要求二次确认。
- 重试创建带 `retryOfRunId` 的新 Run；来源 Run 的状态、结果、步骤和事件快照完全不变。
- Room v7→v8 迁移为旧 Run 初始化空关联，同时保留原状态、结果和错误。
- Redmi Note 8 Pro 上覆盖安装 Debug 包成功，保留应用数据；v8 启动正常，crash buffer 为空。
- UI 自动化通过 UI tree 派生坐标进入「设置 → Agent 任务中心」；1080×2340 视口下，全部/处理中/可重试/已完成筛选与空状态完整显示，无文字或控件重叠。
- 使用真机已有 `gpt-5.5` Provider 在独立测试会话发起 `/agent remember test preference permanently__；模型提出 `memory.remember` 后，第一次审批被拒绝，来源 Run 正确进入 `FAILED`。
- 任务中心展开来源 Run 后可查看步骤、审批参数和结构化事件；点击“重试”立即创建新 Run，新 Run 显示来源 Run ID，来源卡片显示“重试中”并禁用重复点击。
- 新 Run 再次进入 `WAITING_APPROVAL`，说明写入工具没有继承旧审批；第二次审批也被拒绝，最终没有写入长期记忆，且没有遗留待处理任务。
- 审查修复后再次从任务中心点击重试，应用自动切回来源会话；新 `/agent` 消息、实时步骤和审批卡均在当前屏幕可见。第三次写入审批同样被拒绝，任务收敛且 crash buffer 为空。

完整回归结果：

- `testDebugUnitTest`：77 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：9 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

当前验证边界：

- 二次确认弹窗只在“已成功执行非 SAFE 工具”或“中断发生在 EXECUTING/VERIFYING”时出现；本轮真实 Provider 流程在工具执行前拒绝审批，因此该分支由策略单元测试覆盖，未在真机制造真实副作用后再验证。

## 长期记忆管理与 FTS 验证

定向测试覆盖：

- FTS4 英文/标签前缀查询和双引号转义。
- 中文多词与任意子串的 `LIKE` 兜底召回，`%`、`_` 和反斜杠按字面搜索，不会扩大匹配范围。
- 置顶优先、启用状态筛选，以及禁用后不再参与 `memory.search`。
- 编辑内容时保留来源会话/Run；主表与 FTS 索引在新增、编辑和删除事务中保持一致。
- v8→v9 迁移保留旧记忆并立即回填 FTS 索引。

真机 UI 已验证：

- Redmi Note 8 Pro 横屏下，空状态、记忆列表、英文搜索和“已禁用”筛选无文字或控件重叠。
- 从“已禁用”筛选中重新启用记忆后，该条目立即移出当前列表。
- 内容编辑保存、删除二次确认和来源审计信息正常；测试数据通过页面删除后，`agent_memories` 与 `agent_memories_fts` 均为空。

本阶段完整回归结果：

- `testDebugUnitTest`：79 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：11 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

## 候选记忆、敏感过滤与删除撤销验证

定向测试覆盖：

- 明确偏好生成 `PENDING` 候选，普通问答不生成候选；候选确认前不进入正式记忆和 FTS。
- API Key（含 `sk-`、GitHub、Google、AWS 常见前缀）、token、密码、银行卡、身份证和手机号固定样例全部进入 `BLOCKED_SENSITIVE`，候选 content、normalized content、标签和来源摘要不包含原值。
- 忽略空格、标点和大小写后相同的事实标记为 `DUPLICATE`；`memory.remember` 直接写入也复用旧记忆 ID，不产生重复行。
- 同类型、同主题但内容不同的事实标记为 `CONFLICT` 并关联旧记忆；确认时另存新记录，不覆盖旧记录。
- 删除前原子保存最近一次完整快照并立即移除主表与 FTS；撤销后在同一事务中恢复主表、来源、置顶、生命周期字段和 FTS。
- 新增跨 Store 实例测试模拟进程重建：删除后新实例可读取撤销快照并恢复；若快照已写但 Room 正式记忆仍存在，则清理陈旧快照而不重复提供撤销；损坏快照会被删除且不阻断记忆管理。
- v9→v10 迁移保留已确认记忆并创建空候选表；全新 v10 数据库可打开。

真机验证：

- 使用 `adb install -r` 覆盖安装 debug 与 androidTest APK，再直接调用 `AndroidJUnitRunner`，未执行会清除设备配置的 Gradle connected 流程。
- 完整真机套件 `OK (15 tests)`；执行后 Provider 记录、选中模型和 Keystore 密文仍存在。
- Redmi Note 8 Pro 横屏下，「长期记忆」页候选开关默认显示“已关闭”；开关、搜索、筛选和空状态完整显示，无重叠或截断。
- 实际数据库 `PRAGMA user_version=10`，应用覆盖安装、启动和 Room 迁移正常。

本阶段完整回归结果：

- `testDebugUnitTest`：99 条 JVM 单元测试通过。
- 手动 `AndroidJUnitRunner`：15 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 通过。

## 记忆引用审计与单次召回关闭验证

本轮 JVM 定向测试覆盖：

- `memory.search` 返回真实命中的 `memoryIdsUsed`；关闭记忆召回时不访问 Store，规划器工具清单移除 `memory.search`。
- `RunEventMetadata.ToolResult` 和 `VerifiedAgentContext` 的 memory ID 编解码；旧 JSON 缺少字段时兼容为空列表。
- `memory.recall.disabled` 事件在单次 Run 写入，关闭只影响读取，不绕过 `memory.remember` 的审批链路。
- 任务中心工具结果展示实际使用的 memory ID，历史事件和普通工具结果保持原有展示。

本轮验证结果：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug`：`105` 条 JVM 测试通过。
- `memoryIdsUsed` 只来自执行器返回的真实记录，不接受模型总结自由文本伪造；未执行真机 Agent 请求，Provider 上游状态不影响本轮本地契约验证。

## 进程重建恢复边界验证

本轮新增 `AgentRunResumePolicy`，以持久化 Run 快照、审批列表、步骤和事件判断恢复方式：

- `WAITING_APPROVAL` 且存在 `PENDING` 审批、没有 `tool.execute` / `tool.verify` 步骤或结果时，评估为 `APPROVAL_WAIT`，允许后续接入原 Run 审批恢复。
- 已出现工具执行或验证记录，或 Run 处于其他状态时，评估为 `RESTART_REQUIRED`，必须创建新 Run，旧 Run 不修改。
- 已新增 JVM 单元测试覆盖上述三条边界；本环境 Gradle 仍被沙箱阻止，未能重新运行测试。
- 本轮先落地确定性恢复策略；启动协调器对执行/验证中 Run 仍采用收敛为 `CANCELLED` 的保守实现，待审批 Run 可继续进入原 Run 执行入口。

本轮进一步接入启动协调器：

- Room 中符合 `APPROVAL_WAIT` 的 Run 会在启动后重建到对应会话和审批卡片；批准后从原审批步骤继续执行同一 Run，不创建 `retryOfRunId`。
- 已进入工具执行/验证阶段的 Run 仍由启动收敛逻辑关闭；新增 instrumentation 测试覆盖待审批 Run 保持 `WAITING_APPROVAL`、审批保持 `PENDING` 和恢复事件 typed metadata。
- 当前环境仍无法运行 Gradle，因此新增 JVM/Room/UI 编译和真机进程重建验证未执行。

本轮新增原 Run 执行入口：

- `MinimalAgentRuntime.resumeApprovedRun` 只接受 `APPROVAL_WAIT` 评估结果，使用持久化审批中的工具名和参数，不重新调用模型规划。
- 工具执行、后置验证、模型总结、`RunEvent` 和最终 `COMPLETED/FAILED` 状态均写回原 Run；新增 JVM 测试验证 Run ID 不变且不产生 `llm.plan` 步骤。
- 新增 JVM 测试验证恢复工具失败写入原 Run `FAILED`，且重试策略要求二次确认；新增 Room instrumentation 测试用新 Repository 实例模拟组件重建，串起审批重建、批准、工具执行、验证和同 Run `COMPLETED`。
- 已验证 Gradle 8.13 分发包可在临时 `GRADLE_USER_HOME` 启动，但进入构建仍因 `FileLockContentionHandler` 创建本地 Socket 被沙箱拒绝；新增测试尚未实际运行，APK 和真机进程重建验证也未执行，不能把该入口报告为设备验收通过。

## 内置 Skill 按需加载验证边界

本轮新增四类内置声明式 Skill、稳定关键词选择、最多 3 个 Skill 限制、工具白名单包装和 `skill.selected` RunEvent：

- 单元测试覆盖多意图目标的稳定选择、未命中时保留原工具集、Skill 不能引用未注册工具，以及执行层拒绝越过 Skill 白名单。
- `AgentRunUseCase` 已把选中 Skill 的指令和收窄后的工具定义传给规划器；工具风险、审批、权限和验证仍取自原 `ToolDefinition`。
- 当前环境的 Gradle 与 ADB 都因本地 Socket 权限被沙箱阻止，新增测试、APK 构建和真机行为尚未执行；本轮不包含 Skill 导入、管理 UI、多步工具循环或后台执行。

## 记忆过期与时间衰减验证边界

本轮实现新增 Room v10→v11 迁移、可空 `expiresAt` / `lastReferencedAt`、过期检索过滤、引用时间回写、置顶保护和按类型半衰期排序，并在长期记忆管理页提供永久、30 天、90 天和 1 年策略。

当前环境的 Gradle Wrapper 和直接 Gradle 均被沙箱阻止启动本地 `FileLockContentionHandler` Socket，关键错误为 `java.net.SocketException: Operation not permitted`。因此本轮新增 JVM、Room instrumentation、lint 和 APK 构建尚未能在当前环境重新执行；`git diff --check` 和 schema JSON 结构检查已通过。未运行会清理设备数据的 connected instrumentation。

外部服务边界：

- 本机兜底 Provider 的模型列表与鉴权已验证成功；按本机指令选择 `gpt-5.5` 后，真实对话请求到达服务端但返回 `HTTP 503 · 无可用账号`。同端点小范围候选探测也返回 503/429/403，因此当前未取得真实回复成功证据，该结果不归因于应用实现。

## 数据备份与恢复验证

定向测试覆盖：

- ZIP manifest 与 Room 主库字节可往返恢复，manifest 保存 schema/app 版本和 Keystore 依赖标记。
- 未来 schema 在写入目标数据库前被拒绝；导入真实 SQLite 前再次校验 `PRAGMA user_version`。
- 导出前执行 WAL checkpoint；恢复前关闭 Room，保留 `.pre-restore` 安全副本，清理 `-wal/-shm` 并提示重启。
- API Key 不进入 manifest 或明文导出；Provider 表中的密文只能在原设备 Keystore 仍存在时解密。

真机验证：

- Redmi Note 8 Pro 横屏/竖屏下设置页显示「数据备份与恢复」，导出按钮进入系统 Create Document，默认文件名为 `xiaoling-backup-*.zip`。
- 选择保存位置后显示“备份已导出”；Open Document 导入器可打开，实际替换恢复本轮仅完成确认前的文件选择验证，未覆盖当前设备数据库。

本阶段完整回归结果：

- `testDebugUnitTest`：101 条 JVM 单元测试通过。
- `lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 通过。

## Tool Schema 与权限策略验证

定向测试覆盖：

- 输入 Schema 支持字符串、整数、数值和布尔逻辑类型，以及必填、长度、数值范围和枚举约束；未知参数默认拒绝。
- 模型可见 Schema 使用 `object/properties/required/additionalProperties=false`，不再依赖自然语言描述猜测类型。
- 可插拔业务校验器已用于 `memory.remember` 标签数量和单标签长度限制；Schema 失败时不会继续运行业务规则。
- 非 SAFE 工具不能把确认策略降级为 `NONE`；`notes.create` 与 `memory.remember` 要求 Executor 回读验证，只有普通成功文本时 Run 在 `tool.verify` 失败。
- Registry 初始化拒绝重复工具名；当前 8 个生产工具均声明 5 秒超时、确认/验证策略、空 Android 权限集合和 `supportsBackground=false`。
- Runtime 在审批和执行前检查 Android 权限；检查器未注入时默认 fail-closed，模型把风险伪报为 SAFE 也不能绕过定义侧风险和权限。
- 模型返回的 integer/boolean JSON primitive 能通过参数解析进入逻辑类型校验；数组形式 `arguments`、字符串形式 integer、对象形式 STRING 和超出 Long 范围的整数均被解析层拒绝，非 STRING 字段不能声明字符串枚举。
- 前台限定工具在 `BACKGROUND` 来源下于审批前失败。
- 最终确定性回复按实际确认和验证策略渲染，不再根据风险或固定“结果可读”文案推断。

真机验证：

- `AndroidToolPermissionChecker` 在 Redmi Note 8 Pro 上把 manifest 中已授予的 `INTERNET` 识别为可用，并继续拒绝未声明权限。
- 当前生产工具均为应用内能力，不触发 Android 运行时权限弹窗；真实系统工具的授权、撤销和后台策略仍需随对应工具单独做真机验收。

本阶段完整回归结果：

- `testDebugUnitTest`：94 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：12 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

## 签名验证

执行命令：

```zsh
/Users/long/Library/Android/sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

结果：

```text
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=XiaoLing, OU=XiaoLing, O=Long, L=Shanghai, ST=Shanghai, C=CN
Signer #1 certificate SHA-256 digest: 5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9
```

说明：

- 从 `v0.1.8` 起应用使用 `com.longdev.xiaoling`；从旧 `applicationId` 升级时，Android 会把它视为新应用。
- 本机 release keystore 为小灵专用证书，`v0.1.10`、`v0.1.9` 与 `v0.1.8` 使用同一签名证书。

## APK 元数据

执行命令：

```zsh
/Users/long/Library/Android/sdk/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/release/app-release.apk
```

关键结果：

```text
package: name='com.longdev.xiaoling' versionCode='10' versionName='0.1.9'
application-label:'小灵'
```

## 历史 GitHub Release（v0.1.9）

- Release：[小灵 v0.1.9](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.9)
- 标签：`v0.1.9`
- 发布提交：`3059b4c53d4c063aaf929352e14cea040bb56287`
- APK：[xiaoling-v0.1.9.apk](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.9/xiaoling-v0.1.9.apk)
- 远端资产状态：APK 和 SHA-256 文件均为 `uploaded`，Release 不是 draft 或 prerelease。
- 远端 APK digest：`sha256:b8a8c77e6e1f83543d3bd775ffda83615e7f06ad846b0c0f83cf9a1ac778c5b8`

## 真机安装与启动

### 当前 main debug 安装

验证基线：`433d43b` 之后的长期记忆管理工作区

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew installDebug --console=plain
adb -s wsvwypiz7xwslvl7 shell monkey -p com.longdev.xiaoling -c android.intent.category.LAUNCHER 1
```

结果：

```text
Installed on 1 device.
Events injected: 1
```

已确认：

- APK 包名为 `com.longdev.xiaoling`，`versionName=0.1.9`，`versionCode=10`。
- instrumentation 回归后重新执行 `installDebug`，本次没有执行卸载或清数据命令。
- `topResumedActivity` 为 `com.longdev.xiaoling/.MainActivity`，应用已在 Redmi Note 8 Pro 前台。
- 应用进程存活，启动后的 crash buffer 为空。

### v0.1.9 release 覆盖安装历史

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r outputs/release/xiaoling-v0.1.9.apk
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

结果：

```text
Performing Streamed Install
adb: failed to install outputs/release/xiaoling-v0.1.9.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.longdev.xiaoling signatures do not match newer version; ignoring!]
Starting: Intent { cmp=com.longdev.xiaoling/.MainActivity }
```

前台 Activity：

```text
com.longdev.xiaoling/com.longdev.xiaoling.MainActivity
```

已确认：

- 设备上已有同包名但不同签名的安装包，系统拒绝覆盖安装 release APK；未执行卸载或清数据。
- 已有安装包的对话页可启动。
- 本次未在该真机上覆盖安装 release 包；如需从 debug 签名切换到 release 签名，需要用户确认后卸载旧包或换干净设备验证。

## 日志检查

启动期间 logcat 未命中应用崩溃、`FATAL EXCEPTION`、`AndroidRuntime` 或 ANR。

## 提示词设置增量验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:installDebug --console=plain
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

已验证：

- 提示词策略单元测试通过，覆盖普通对话、会话摘要和 Agent 总结的不可覆盖边界。
- 审查修复回归测试通过：用户正文伪造可信标记仍只会落入 JSON `content` 字段；超长普通 assistant 回复和重复兜底摘要均保留非证据标签；Agent 模型只能选择有限展示枚举，合法选择可改变详略和语气，非法自由文本不能增加或展示虚构工具事实；`VerifiedAgentContext` 编解码可无损往返；旧 assistant 消息按普通回复保守迁移。
- debug 源集编译、APK 构建和真机覆盖安装成功。
- Room 数据库先从 v4 升级到 v5，再从 v5 升级到 v6；两次覆盖安装后应用均正常启动，消息来源和独立 Agent 审计上下文列未触发 Room、SQLite 或 Migration 异常。
- 应用进程存活，任务栈包含 `com.longdev.xiaoling/.MainActivity`，crash buffer 为空。

未完成验证：

- 真机停留在 keyguard，`uiautomator` 仍只能读取锁屏节点；未输入用户凭据，因此提示词设置页的点击、预览和重启持久化流程尚未完成可视验证。

## 产物

| 文件 | 说明 |
|---|---|
| `../app/build/outputs/apk/release/app-release.apk` | release 包，已通过正式签名验证。 |
| `../outputs/release/xiaoling-v0.1.9.apk` | GitHub Release 上传用 APK，SHA-256：`b8a8c77e6e1f83543d3bd775ffda83615e7f06ad846b0c0f83cf9a1ac778c5b8`。 |
| `../outputs/release/xiaoling-v0.1.9.apk.sha256` | APK SHA-256 校验文件。 |

## 清理状态

- 2026-07-19 完整 instrumentation 按测试框架语义重置了应用数据；随后已重新安装最新 Debug APK，从未跟踪的本机配置恢复 Provider，获取 6 个上游模型，并用指定模型完成真实 `OK` 冒烟响应。
- 审批恢复验收创建的临时长期记忆已通过管理 UI 删除，数据库确认残留数为 0。
- `outputs/` 目录不纳入版本控制。

## 2026-07-18 待审批 Run 真机进程重建验收

环境与构建：

- 设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro；应用 `com.longdev.xiaoling`，`versionName=0.1.9`，`versionCode=10`。
- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug --console=plain`，121 项 Debug 单元测试通过。
- Debug APK SHA-256：`1c85b53715c1ffd5d519513a1b8b74e0c5665d70bfb2c53426fb45d525dfe899`。
- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装；未卸载、未清数据、未运行 instrumentation 测试，Provider、历史会话和 Keystore 凭据保持可用。

真实流程：

- 输入 `/agent remember process rebuild acceptance marker 20260718-1038`，模型选择 `memory.remember`。
- Run：`run-b6dc92d7-19b8-4b25-a189-a34d4e815297`；Approval：`approval-f8669cc6-1244-4820-ac06-3ff5e4388b5a`。
- 审批前数据库为 `WAITING_APPROVAL / PENDING / retryOfRunId=NULL`；执行 `am force-stop` 后 PID 消失，Run 和审批状态保持不变。
- 首次重启发现 Run 已恢复但用户消息尚未持久化，导致审批卡片没有 UI 锚点；修复为发送 Agent 后立即保存消息，并在恢复旧数据时按 `userMessageId / goal / createdAt` 补回缺失锚点。
- 覆盖安装修复包后，UI 显示“进程重建后待恢复”和“批准并继续”；批准后原 Run 依次进入 `EXECUTING / VERIFYING / THINKING / COMPLETED`。
- 原 Run 的步骤仍只有一次 `llm.plan`，恢复阶段没有新增规划事件或新 Run；同目标 Run 数量为 1，`retryOfRunId` 仍为 `NULL`。
- 工具执行和回读验证均通过；记忆 `memory-fd3b356d-e8cc-434e-a9c9-0cd35374de26` 已写入，`sourceRunId` 指向原 Run，内容为 `process rebuild acceptance marker 20260718-1038`。
- 最终 UI 显示 Agent 已完成，crash buffer 为空。

## 2026-07-18 顺序多步 Agent 真机验收

环境与构建：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug --console=plain`，审查修复后的最终版本有 128 项 Debug 单元测试通过。
- 最终 Debug APK SHA-256：`b83390ed8966bffcc16b1dfbaa39a1aadb2a2842637a9a838890c270cf85f710`。
- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装；未卸载、未清数据、未运行 instrumentation 测试，应用冷启动成功且 crash buffer 为空。

真实流程：

- Run `run-e055f8b5-572e-4068-ae14-1ddacc8ace8d`：目标要求先读取当前时间再列出最近会话；同一 Run 依次执行 `app.current_time -> app.list_conversations`，两次参数校验、SAFE 审批跳过、工具执行和后置验证均完成，第三次 `llm.plan` 返回完成决策。
- Run `run-713e0101-cb53-431c-a388-a98a0885f9b0`：反向要求先列会话再读时间；同一 Run 依次执行 `app.list_conversations -> app.current_time` 后完成，证明生产规划器按目标和已验证历史选择下一步，不是固定工具脚本。
- 两个 Run 都只创建一个 `AgentRun`，最终为 `COMPLETED`；UI 时间线显示 10 个步骤，最终回复按步骤包含两个真实工具结果。
- 最终消息的 `VerifiedAgentContext.toolExecutions` 按真实执行顺序保存两个工具、参数、结果和验证状态；顶层旧字段映射最后一步。
- 首次真机持久化发现 Android `org.json` 将 Kotlin 字符串列表写成字符串 `"[]"`；最终修复版显式构造 `JSONArray` 并兼容读取旧字符串化数组。第二个 Run 的数据库原文确认顶层和两个工具步骤的 `memoryIdsUsed` 均为 JSON 数组 `[]`。
- 审查修复后的最终 APK 新建 Run `run-c8c6abdd-2c06-48dd-8b16-efeb08dc53be`，再次完成 `app.current_time -> app.list_conversations -> complete`；数据库确认 10 个步骤全部完成，最终可信上下文包含两个有序工具执行项。
- Run `run-0c6c64be-1605-4733-9de3-d7e759dde7ae` 在首步 `memory.remember` 审批前强制停止进程；冷启动后 UI 重建“进程重建后待恢复”审批卡片，批准后同一 Run 继续 `memory.remember -> app.current_time -> complete`，11 个步骤全部完成且未创建替代 Run。
- 另一 Run 在 `memory.search` 已完成、`memory.remember` 等待审批时强制停止进程，启动协调器按安全策略把原 Run 和审批分别收敛为 `CANCELLED`，验证“已有任意工具执行记录则不原地恢复”。本轮写入的恢复测试记忆已通过长期记忆管理 UI 删除，主表与 FTS 索引均确认无残留。
- 双轴审查发现首步审批恢复曾使用默认 `memoryRecallEnabled=true`；最终修复版改为从原 Run 的 `memory.recall.disabled` 持久化事件还原开关，并由单元测试确认恢复后的 Tool Registry Context 仍为关闭召回，避免后续步骤重新暴露 `memory.search`。
- 最终修复 APK 再次通过真机进程重建验收：Run `run-cb007d20-4a6b-4f26-b1c2-8b5183658719` 在关闭单次记忆召回后停在首步 `memory.remember` 审批；冷启动批准后，同一 Run 完成 `memory.remember -> app.current_time -> complete`。恢复后的实际规划请求工具清单只含 `app.current_time` 和 `memory.remember`，不含 `memory.search`；测试记忆已通过管理 UI 删除，主表与 FTS 均无残留。

边界：

- 首版仅支持顺序执行，不支持并行工具调用。
- 任一步仍独立执行 Schema、权限、风险、审批和验证策略；前一步批准不能放宽后续工具。
- 进程重建只允许尚未执行任何工具的首个 `WAITING_APPROVAL` 边界原地继续；已有工具执行/验证记录的多步 Run 仍安全收敛并通过关联新 Run 重试。

## 2026-07-18 本地 Agent Skill 导入与管理验证

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；136 项 Debug 单元测试通过，失败数为 0。
- `AgentSkillDocumentCodecTest` 覆盖合法 v1 JSON、未知可执行字段、空触发词和 UTF-8 64 KiB 字节上限；`AgentSkillsTest` 覆盖本地导入、关键词/触发示例选择、停用、升版保持停用、选择审计版本恢复和禁止覆盖内置 ID；`RunEventMetadataCodecTest` 覆盖 `skill.selected` 的 Room 编解码。
- AndroidTest APK 编译通过；迁移测试已扩展到 v4→v12、v9→v12、v11→v12 和全新 v12 建库，`RoomAgentSkillStoreInstrumentedTest` 覆盖内置定义升级后保留用户停用决定。本轮为保护设备 Keystore API Key，没有执行 instrumentation。
- Debug APK SHA-256：`0d077db6c40e97e1e61dfc87090f8a52d5f6dfd09ce1c78a1436d76723575402`。

真机验证：

- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装成功，未卸载、未清数据；安装后为 `versionName=0.1.9`、`versionCode=10`。
- 应用启动触发主库升级；只读取非敏感结构信息确认 `PRAGMA user_version=12`、`agent_skills` 表和 `index_agent_skills_source_enabled_updatedAt` 索引存在，初始本地表记录数为 0；crash buffer 为空。
- 手机停留在系统锁屏，ADB 无法关闭锁屏。因此本轮尚未完成「设置 -> Agent Skills」中 4 个内置 Skill 展示、导入 [`examples/daily-review.skill.json`](examples/daily-review.skill.json)、启停、`skill.selected` RunEvent 和删除后无残留的可视验收；不以单元测试替代这部分真机结论。

安全边界：

- 本地 Skill 只允许 `schemaVersion=1` 声明式 JSON，不执行脚本；字段白名单、工具注册、最高风险和 Android 权限必须全部匹配后才能写入 Room。
- 本地 Skill 不能覆盖内置 ID，同 ID 更新必须提高版本；启停立即影响后续 Skill 选择，删除只允许 `source=LOCAL`。
- 新 Run 的 `skill.selected` 事件记录 `id@version`；恢复审批时只接受原版本仍存在的定义，Skill 在等待期间被删除或升版不会扩大工具面。
- 兼容旧 Run 时，无版本的 Skill 审计只允许解析为内置 Skill；本地 Skill 缺少版本记录时 fail-closed，并要求创建新 Run。

## 2026-07-18 Workflow Ledger 与前台手动执行验证

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；139 项 Debug 单元测试通过，失败数为 0。
- `WorkflowDefinitionPolicyTest` 覆盖合法定义、空名称、超长目标和 Agent→Workflow 共享终态映射；`RoomWorkflowRepositoryInstrumentedTest` 已编译覆盖手动 Run/Step 原子创建、事务内拒绝重复活动 Run、重复 Agent 快照幂等关联、完成结果，以及进程重建时保留审批等待并收敛已取消 Agent Run。
- `XiaoLingDatabaseMigrationInstrumentedTest` 已编译覆盖 v4→v13、v9→v13、v12→v13 与全新 v13 建库。为保护设备 Keystore API Key，本轮没有执行 instrumentation。
- Debug APK SHA-256：`66a3c3e94164fd5f6bc552d16a753caecf1cadb3f5d1d2bff4083afbb7e2a798`。

真机验证：

- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装成功，未卸载、未清数据；安装后仍为 `versionName=0.1.9`、`versionCode=10`。
- 首次在 Activity 刚启动时抢先读取数据库仍得到 v12，并出现 `no such table: workflows`；等待 Room 完成打开后重试，确认 `PRAGMA user_version=13`，`workflows / workflow_runs / workflow_steps` 三张表均存在且初始记录数为 0。
- Activity 正常显示，Room Schema 校验日志完成，crash buffer 为空。
- 手机仍停留在系统锁屏，`uiautomator` 只能读取 `com.android.systemui`。因此本轮尚未完成设置页新建/启停工作流、手动运行跳回会话、SAFE 完成、审批拒绝/批准以及 Ledger 结果展示的可视验收。

边界：

- 当前每个 Workflow 固定为一个 `AGENT_RUN` 步骤，只支持 `MANUAL` 前台触发；同一 Workflow 有未完成 Run 时拒绝重复启动。
- 重复活动 Run 的保护位于 Room 创建事务内；UI 展开工作流可查看已加载的多次历史 Run。恢复前置校验失败且 Agent 仍在等待审批时，不会提前把 Workflow 标为失败。
- 工作流不创建新的工具授权层，所有工具继续执行现有 Schema、权限、风险、审批和后置验证策略。
- 该阶段当时没有引入 WorkManager、定时规则、通知、Foreground Service 或后台审批；这些能力中的 WorkManager、通知和后台审批已在后续阶段基于现有 Ledger 接入。

## 2026-07-18 一次性非精确定时工作流自动化验证

官方依据：

- Android WorkManager 官方文档确认 `OneTimeWorkRequest.setInitialDelay` 只保证任务在最小延迟后具备执行资格，实际时间仍受系统优化与约束影响；实现和 UI 均不承诺准点。
- WorkManager 使用唯一工作名和 `ExistingWorkPolicy.KEEP` 防止同一 ScheduledTask 重复入队，并要求联网后才执行 Agent 请求。
- Android 8.0+ 通知使用稳定 Channel；Android 13+ 从用户创建计划的操作中请求 `POST_NOTIFICATIONS`，通知被拒绝不改变 Room 中的业务终态。

来源：

- <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>
- <https://developer.android.com/reference/kotlin/androidx/work/WorkManager>
- <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- <https://developer.android.com/develop/ui/views/notifications/channels>

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；150 项 Debug 单元测试通过，失败数为 0。
- `AgentToolCallParser` 新增兼容测试：当模型把同一个已声明工具名同时写入 `action` 与 `tool` 时归一化为工具调用；两者不一致时仍拒绝，后续继续执行注册表、Schema、风险、后台能力和审批门禁。
- `MinimalAgentRuntimeTest` 覆盖后台 SAFE 工具仅在显式 `supportsBackground=true` 时执行，以及需审批工具在调用 Gate/Executor 前进入 Agent `BLOCKED`。
- `ScheduledWorkflowOrchestratorTest` 覆盖完成、失败、blocked、系统取消和领取拒绝；确认各业务终态先写入 Ledger，再发送对应通知，取消在 `NonCancellable` 中收敛后继续向 WorkManager 传播。
- `RoomWorkflowRepositoryInstrumentedTest` 已编译覆盖一次性 ScheduledTask 创建、WorkRequest 关联、原子领取、计划/实际时间、Workflow/Agent Run 关联和 blocked 终态。
- `XiaoLingDatabaseMigrationInstrumentedTest` 已编译覆盖 v4→v14、v9→v14、v12→v14、v13→v14 与全新 v14 建库；Room 导出的 `14.json` 包含 `scheduled_tasks` 及 `workflow_runs.scheduledTaskId / plannedAt`。
- 为保护真机 Keystore 中的 Provider API Key，本轮仍未执行 instrumentation；AndroidTest 只完成源码编译和 APK 组装。

该阶段当时的边界：

- 第一版只支持 1 分钟至 7 天的一次性非精确计划，不支持 Daily/Weekly、AlarmManager、精确闹钟权限或 Foreground Service。
- SAFE 后台白名单仅包含当前时间、会话查询、笔记查询和长期记忆查询；`notes.create / memory.remember` 等需审批工具不会继承前台授权，而是写入 Agent/Workflow/ScheduledTask `BLOCKED` 并提示用户以前台新 Run 重试。
- WorkManager 业务结果不使用系统自动重试，避免复制可能已经执行过的 Agent Run；触发前进程回收后的冷启动执行已验证，后台执行中的断点续跑仍未实现。

真机覆盖安装与结构验证：

- 实现提交 `ed8d7a5 实现一次性后台工作流调度` 已推送到 `origin/main`。
- 最新 Debug APK SHA-256：`1d485174f3dd508528e811f418dc7185c83f8839943a6708fd74eb2dffe13394`。
- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装成功；未卸载、未清数据、未运行 instrumentation。
- 安装后仍为 `versionName=0.1.9`、`versionCode=10`；应用进程 PID `7420`，`com.longdev.xiaoling/.MainActivity` 已进入 resumed 状态，crash buffer 为空。
- 只读取非敏感结构信息确认 `PRAGMA user_version=14`；`workflows / workflow_runs / workflow_steps / scheduled_tasks` 四张表存在，`workflow_runs` 含 `plannedAt / scheduledTaskId`，初始 `scheduled_tasks` 记录数为 0。
- 合并后的 Manifest 已注册 `androidx.startup.InitializationProvider` 和 WorkManager `SystemJobService`。

真机一次性调度与通知验收：

- 用户解锁手机后，从「设置 → 工作流」创建 `SAFE_time_test` 与 `BLOCKED_note_test`，并通过应用触发的 Android 14 系统弹窗授予 `POST_NOTIFICATIONS`；没有使用 shell 强改权限。
- 首条 SAFE 计划在权限授予前因上游 `HTTP 503` 收敛为 `FAILED`；第二条计划 `20:50:04.940` 入队，`20:50:05.022` 实际启动，偏差 `82ms`，`20:50:15.385` 完成。ScheduledTask、Workflow Run、Workflow Step、Agent Run 和 WorkRequest 全部关联，工具结果为 `app.current_time`，第一条失败 Run 保持不变。
- 成功通知真实写入 `workflow_results` Channel，标题为「工作流已完成 · SAFE_time_test」，正文包含受验证的当前时间结果；后续上游 `HTTP 502` 失败通知标题为「工作流执行失败 · BLOCKED_note_test」。
- 在另一条已入队计划触发前退到桌面并执行 `am kill com.longdev.xiaoling`，确认旧 PID 消失；WorkManager 随后由 `SystemJobService` 冷启动 PID `9845`，创建并收敛新的 Ledger。该次因上游返回 `HTTP 401` 进入 `FAILED`，但 Authorization 仍存在且没有 Keystore 解密错误，证明进程回收未破坏密钥或丢失计划。
- 更新后的外部模型独立健康探测返回 HTTP 200。该模型曾把合法工具名重复写入 `action/tool`，新增受限归一化后覆盖安装 Debug 包，保留 Provider、工作流、通知权限和 Room v14 数据。
- 最终 blocked 计划 `21:48:33.823` 入队，`21:48:34.016` 实际启动，偏差 `193ms`，`21:48:39.374` 收敛。Agent Run、Workflow Run、Workflow Step 和 ScheduledTask 均为 `BLOCKED`，错误为「后台任务需要用户确认工具：notes.create」。
- blocked Run 的 `ApprovalRequest` 数量为 `0`，目标正文 `blocked_background_test_20260718` 的笔记数量为 `0`，证明后台没有等待前台审批、没有继承临时授权且没有执行写入。
- blocked 通知真实显示在锁屏通知区，标题为「工作流需要你处理 · BLOCKED_note_test」，正文提示打开应用以前台重试。
- 全程未卸载、未清数据、未执行 instrumentation；最终 crash buffer 为空。Daily/Weekly、精确定时、Foreground Service 和后台执行中的断点续跑仍未验收。

## 2026-07-18 Daily/Weekly 周期工作流验证

设计与自动化验证：

- 周期规则没有直接使用 `PeriodicWorkRequest`。每条规则只物化一个 `OneTimeWorkRequest`，每次执行保留独立 ScheduledTask/Workflow Run/Agent Run 终态，再按保存的 `ZoneId` 计算下一个未来墙上时间；该策略继续使用 WorkManager 非精确定时语义。
- Room 升级到 v15，新增 `workflow_schedules` 和 `scheduled_tasks.scheduleId`；导出的 `15.json` 已生成。迁移测试源码覆盖 v14→v15，Repository instrumentation 源码覆盖规则创建、替换、停用和下一实例物化。
- `WorkflowSchedulePolicy` 单元测试覆盖 Daily/Weekly 下一触发时间、已过时间推进一个周期、时区和非法字段；Repository/启动协调逻辑保证只补一个未来实例，不补跑历史周期，也不恢复旧执行栈。
- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；153 项 Debug 单元测试通过，lint、Debug APK 和 AndroidTest APK 均组装成功。为保护真机 Keystore API Key，没有执行 instrumentation。
- Debug APK SHA-256：`f19fa0d8ab26409f2b8b9ff9c12bc263fb98f0d056ea6a6e639d67c8353389e9`。使用 `adb install -r` 覆盖安装到 `wsvwypiz7xwslvl7`，未卸载、未清数据；`versionName=0.1.9`、`versionCode=10`，`POST_NOTIFICATIONS` 保持授权。
- 真机迁移确认 `PRAGMA user_version=15`，`workflow_schedules` 表和 `scheduled_tasks.scheduleId` 列存在，应用启动后 crash buffer 为空。

Daily 执行与下一实例：

- 在 `SAFE_time_test` 上通过 UI 创建 `每日 22:55 · Asia/Shanghai`。规则 ID 为 `workflow-schedule-b9d65489-1e78-4b8b-953f-1b3e1ba3428e`，首个 ScheduledTask 为 `scheduled-task-8520ed09-9ba1-49cd-9b4b-6facea510ace`，WorkRequest 为 `f68bb610-bd87-4ea5-9cf0-3357ad27cc1a`。
- 首个实例计划时间 `2026-07-18 22:55:00`，实际同秒启动，`22:55:09` 完成。Workflow Run `workflow-run-d473feb7-c1de-4ef1-bbb6-a8fce4601347` 与 Agent Run `run-3f9aafff-8d09-4fe1-a268-0821515e7656` 均为 `COMPLETED`，真实工具结果为 `app.current_time` 返回 `2026-07-18 22:55:02 · Asia/Shanghai`。
- 终态后自动生成 `scheduled-task-877916e0-f5f9-4b9e-b7c3-c0c90a724079`，计划时间为次日 `2026-07-19 22:55:00`，WorkRequest 为 `46501ad7-deeb-4b90-9f61-bdba028e7377`；Task 和 WorkRequest ID 均与首个实例不同。

规则替换与停用：

- 将规则替换为 `每周一 22:55` 后，7 月 19 日旧实例进入 `CANCELLED`，错误摘要为「周期规则已更新」；WorkManager 对应 WorkSpec 状态为 `CANCELLED(5)`。
- 同一规则只保留一个新未来实例 `scheduled-task-efb7a081-409a-4d0e-9b56-e63d4fb2a1c4`，计划时间为 `2026-07-20 22:55:00`，WorkRequest `145379b7-89a1-45d1-8d6a-fb44a67a9194` 在 WorkManager 中为 `ENQUEUED(0)`。
- 通过页面“停用周期计划”后，Room 中规则 `enabled=0` 且清空 `nextTaskId / nextPlannedAt`；新周实例变为 `CANCELLED`，WorkManager 同一 WorkRequest 同步为 `CANCELLED(5)`，没有生成额外实例。
- 1080×2340 真机页面已检查创建弹窗、周期摘要和展开历史；每日/每周信息、时区、取消原因、Workflow/Agent 结果均完整显示，没有文字、按钮或卡片重叠。最终 crash buffer 为空。

该阶段当时的边界：

- 本轮真机确认了外部模型可完成真实后台 SAFE 请求，但没有再次执行触发前杀进程的周期实例；周期启动恢复由单元测试、已编译的 Repository instrumentation 源码和此前一次性任务冷启动真机证据覆盖。
- Daily/Weekly 仍不是精确定时，不使用 AlarmManager 或 Foreground Service；后台执行中断后不恢复旧 Agent 执行栈，旧 Run 保持可审计终态并只生成未来周期实例。

## 2026-07-18 User-Agent 配置验证

- `ProviderRequestConfig` 新增设备级 `userAgent`，默认值为 `Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`；设置页可编辑并恢复默认，空白值自动回退。
- `OpenAiCompatibleClient` 的统一 Request Builder 对模型列表、Chat Completions、Responses、前台 Agent 和 WorkManager 后台 Agent 写入同一 `User-Agent` Header。
- `OpenAiCompatibleClientTest` 使用 MockWebServer 读取真实收到的请求 Header，已验证自定义值原样发送和空白配置回退默认值；测试不访问外部服务。

## 2026-07-18 多步骤 Workflow、步骤快照与安全重试验证

实现范围：

- Room 升级到 v16，新增 `workflow_step_definitions`、`workflow_runs.retryOfWorkflowRunId`，以及 Workflow Step 的定义 ID、幂等键、输入/输出快照和复用来源字段。
- Workflow 支持 1 至 8 个顺序 Agent 步骤；设置页可创建、编辑、增删和排序步骤，活动 Run 存在时 Repository 拒绝编辑。历史 Run 使用创建时物化的步骤快照，不受后续定义变化影响。
- 前台和 WorkManager 后台均逐步创建独立 Agent Run，后续步骤只接收已落库的连续成功前缀输出；每一步继续使用现有 Schema、权限、审批和后置验证链路。
- `BLOCKED / FAILED / CANCELLED` Run 可创建新 Run 重试。连续成功前缀在新 Run 中标记为 `SKIPPED` 并保留 `reusedFromStepId`，首个未完成步骤及后续步骤重新执行；旧 Run 保持不变。
- 进程重建时若当前 Agent 已完成但后续步骤尚未启动，先保存当前输出，再把旧 Workflow Run 收敛为失败；重试可复用该完成前缀。恢复审批完成当前步骤后，继续同一 Workflow Run 的后续步骤，不留下永久 `RUNNING` 状态。

本地自动化验证：

- `WorkflowDefinitionPolicyTest`、`WorkflowStepExecutionPolicyTest` 和 `ScheduledWorkflowOrchestratorTest` 覆盖步骤数量、顺序门禁、输入快照、前序输出提示词、重试资格/二次确认和后台多步骤执行。
- `RoomWorkflowRepositoryInstrumentedTest` 源码覆盖定义物化、编辑冻结历史 Run、活动 Run 拒绝编辑、完成前缀复用、来源 Run 不变、恢复终态聚合和进程重建后的失败收敛。
- `XiaoLingDatabaseMigrationInstrumentedTest` 源码覆盖 v4/v9/v12/v13/v14→v16、v15→v16 和全新 v16 建库；`app/schemas/.../16.json` 已生成并通过 JSON 结构检查。
- 为保护真机 Android Keystore 中的 Provider API Key，本轮未执行 instrumentation；AndroidTest 源码编译和 APK 组装均通过。

执行命令：

```zsh
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug assembleDebugAndroidTest
```

结果：

```text
164 JVM tests passed
lintDebug passed
assembleDebug passed
assembleDebugAndroidTest passed
BUILD SUCCESSFUL
```

真机覆盖安装与 UI 验收：

- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装成功，未卸载、未清数据；Provider、会话、历史 Workflow 和 Keystore 配置保持存在。
- 应用启动后 PID 存活，`com.longdev.xiaoling/.MainActivity` 为前台 Activity，crash buffer 为空。
- 只读确认 `PRAGMA user_version=16`，`workflow_step_definitions` 表存在；v15 的两个历史 Workflow 均回填单步骤定义，旧 Run 状态和结果仍可展开查看。
- 通过 UI tree 派生坐标进入「设置 -> 工作流」：编辑弹窗可增加第二步，步骤上移/下移/删除状态正确，空步骤时保存按钮禁用；取消后没有修改原定义。
- 展开历史卡片可见步骤定义、Run ID、目标/错误快照和重试入口。点击已启动过的 blocked Run 后显示二次确认，明确“新 Run 保留来源 ID、旧 Run 不修改、写工具重新审批”；取消后数据库中 `retryOfWorkflowRunId IS NOT NULL` 的记录数仍为 0。
- 本轮没有确认真实重试，也没有创建或运行多步骤模型任务；前台/后台顺序执行、成功前缀复用和审批恢复继续下一步骤仍以 JVM 测试、已编译 instrumentation 源码和 Room v16 结构验证为依据。

Debug APK SHA-256：`4bf39cd1d69bc03c120ecb0f22cc0339bf8ac9d70d8ec7f3dec126178319cdaa`。

## 2026-07-19 多步骤 Workflow 完整真机验收

环境与自动化回归：

- 设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro，Android 14。
- `testDebugUnitTest`：165 条 JVM 测试通过。
- `connectedDebugAndroidTest`：38 条真机 instrumentation 全部通过；同时通过 `lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest`。
- 本轮修复了旧单步骤 Workflow 兼容入口重复关联同一 Agent Run 时的幂等问题，并让 `RunEventMetadata.ToolResult.memoryIdsUsed` 在 Android `org.json` 中显式编码为 `JSONArray`，读取端兼容早期字符串化数组。

前台失败与安全重试：

- 来源 Run `workflow-run-984dcb6f-b10f-4e88-90b4-73cd04fd573d` 使用不兼容响应失败，最终保持 `FAILED`，后续两个步骤保持 `CANCELLED`。
- 重试 Run `workflow-run-85981ac4-a060-44ca-8f3b-619336a4d455` 的 `retryOfWorkflowRunId` 正确指向来源 Run，三个步骤分别创建独立 Agent Run 并全部 `COMPLETED`。
- 第 1 步调用 `app.current_time`，第 2 步调用 `app.list_conversations`，第 3 步再次调用 `app.current_time`；第 2、3 步输入快照逐项包含连续成功前缀输出。
- 完成后把未来定义第 1 步改为带 `FUTURE_EDIT` 标记，已完成 Run 的 `detail`、`inputSnapshot` 和 `outputSnapshot` 均保持原值，确认历史快照冻结成立。

真实后台 WorkManager：

- 一次性任务 `scheduled-task-d72e18e6-e02d-4aa2-b3dd-957d6e739f74` 在应用位于桌面后台时由系统调度启动。
- Workflow Run `workflow-run-38a1b2a2-b445-4b1b-b00a-0498ac3cec47` 于 `02:06:11` 开始、`02:06:42` 完成；三个 SAFE 步骤及其独立 Agent Run 全部 `COMPLETED`，总耗时约 31 秒。
- 当前耗时没有形成引入 Foreground Service 的证据；继续使用普通 WorkManager，后续只有在长任务或持续可见停止入口成为真实需求时再评估。

审批后继续下一步骤：

- Workflow Run `workflow-run-992e234c-baeb-4259-9481-55a59168e2b0` 的第 1 步选择 `memory.remember` 并进入待审批，第 2 步保持 `PENDING`。
- Approval `approval-fcbd9d77-4e5e-4c10-bd19-a4f57fd9b852` 批准后，第 1 步完成，系统自动创建第 2 个 Agent Run 执行 `app.current_time`，最终两个步骤和 Workflow Run 均为 `COMPLETED`。
- 临时记忆 `QA_APPROVAL_RESUME_20260719` 已通过长期记忆页面删除，数据库确认无残留。

instrumentation 后可用性恢复：

- 测试完成后重新安装最新 Debug APK，从未跟踪的本机配置恢复 Base URL 和 API Key；文档、日志和提交均未包含凭据。
- 上游模型列表成功返回 6 项，配置指定模型完成真实普通对话请求，应用显示精确回复 `OK`。

## 2026-07-19 Agent Run 指标与网络故障注入验证

实现范围：

- 新增 `AgentRunMetricsPolicy`，从持久化 `AgentRunDetailRecord` 计算单 Run 耗时、模型调用、工具调用和审批次数；历史成功率与平均耗时只使用终态 Run，活动 Run 不进入质量分母。
- 任务中心在当前筛选范围展示 Run 数、成功率、平均耗时、非成功数、模型调用和工具调用；列表卡与展开详情展示同一口径的单 Run 指标。
- `OpenAiCompatibleClientTest` 使用 MockWebServer 在 HTTP 200 响应体中途主动断开，确认明确的流中断从 `UNKNOWN` 改为 `CONNECTION`；分类器测试同时确认非法 HTTP 协议仍为 `RESPONSE`，无法识别的 I/O 仍为 `UNKNOWN`。
- 现有确定性测试继续覆盖用户取消、模型步骤超时、整次 Run 超时、工具执行超时，以及同一 Agent Run 重复回调时的 Workflow 幂等关联。

自动化结果：

- `testDebugUnitTest`：175 条 JVM 测试通过。
- `lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 38 条，合计 76 条全部通过。
- 双轴复核确认共享终态判断和 LLM 步骤类型已贯通 Runtime、Repository 与指标消费端；网络分类边界收窄后，`CONNECTION / RESPONSE / UNKNOWN` 均有确定性测试。复核修复后重新执行上述完整构建和 instrumentation，结果保持通过。

真实模型与 UI：

- instrumentation 后在 Redmi Note 8 Pro 重新安装最新 Debug APK，并从未跟踪的本机配置恢复 Provider；凭据未进入文档、日志或提交。
- `gpt-5.4-mini` 真实执行 `/agent Read current time and return verified result`，Run `run-8880e351-0c00-4a91-b6cb-06a48f2e0410` 调用 `app.current_time` 并进入 `COMPLETED`。
- 任务中心实测显示 `1 个 Run · 成功率 100% · 平均 13.28s` 和 `终态 1 · 非成功 0 · 模型 3 · 工具 1`；单 Run 卡片与详情均显示 `耗时 13.28s · 模型 3 · 工具 1 · 审批 0`。
- 1080×2340 UI tree 与截图确认筛选栏、汇总带、Run 卡和展开详情没有文字或控件重叠；`com.longdev.xiaoling/.MainActivity` 保持前台，crash buffer 为空，后续仍需在更长历史列表上持续观察汇总性能。
- 最终 Debug APK SHA-256：`c167e36f02f7a7a26d4b8f245857e2f9f59a7160cac484df8d48d9278bc6f6b1`。

## 2026-07-19 Agent 请求遥测与失败分布验证

实现与口径：

- `ModelResponseResult` 记录最终 JSON 请求体 UTF-8 字节数、总耗时、首个响应 body 字节实际可读时的 TTFB，以及上游明确返回的 Token usage；Chat Completions 和 Responses 字段名统一映射，缺失 usage 保持空值。
- `OpenAiAgentLlm` 把规划和总结请求遥测交给 Runtime，Runtime 以 `llm.request.completed` typed metadata 持久化阶段、模型、耗时、TTFB、Prompt 字节和 Token。规划 JSON 或工具语义解析失败时，已经返回的遥测先落库，随后 Run 再收敛为 `FAILED`。
- 任务中心的汇总带、Run 卡和详情区使用同一指标策略展示模型总耗时、平均 TTFB、Prompt 字节、Token 总量与 usage 覆盖率；失败分布只统计 `FAILED / CANCELLED / BUDGET_EXHAUSTED / BLOCKED` 终态，活动 Run 不进入分母。

自动化结果：

- TDD 覆盖网络请求体字节、TTFB、Chat usage 解析、typed metadata 往返、规划解析失败仍保留遥测、Run/历史聚合、Token 覆盖率、失败分布和 UI 呈现；原始 `ServerSocket` 测试先发送响应头、延迟 200ms 再发送 body，确认 TTFB 必须包含 body 延迟。
- `testDebugUnitTest`：182 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 38 条，合计 76 条全部通过。
- Standards/Spec 双轴审查指出并验证修复两项问题：TTFB 不能在响应头返回时记录；规划语义解析失败不能丢失上游已返回 usage。修复后完整构建与两设备 instrumentation 重新通过。

真实模型与 UI：

- instrumentation 后重新安装最新 Debug APK，从未跟踪的本机配置恢复 Provider 并成功获取 6 个模型；凭据未进入文档、日志或提交。
- `gpt-5.4-mini` 真实执行 `/agent Read current time and return telemetry result`，Run `run-1c833310-12c8-4dda-ad8e-dc2c7915475b` 调用 `app.current_time` 并进入 `COMPLETED`。
- Room 回读确认 3 条 `llm.request.completed`：模型总耗时 `8274ms`、平均 TTFB `2755ms`、Prompt `4066B`、输入 Token `805`、输出 Token `215`、总 Token `1020`。
- TTFB 文案和单调时钟修复包覆盖安装后，真实执行 `/agent Read current time with final TTFB`；Run `run-4dd3b467-7825-4b3f-8ef4-d7f66dbb1405` 进入 `COMPLETED`，3 条请求事件共计模型耗时 `10735ms`、平均 TTFB `3576ms`、Prompt `4042B`、总 Token `1171`。
- 任务中心最终实测显示 `2 个 Run · 成功率 100% · 平均 11.06s`、`模型耗时 19.01s · TTFB 3.17s · Prompt 7.9KB · Token 2191（6/6）` 和 `失败分布 无`；汇总、Run 卡、详情及 typed Event 字段一致。
- 1080×2340 UI tree 与截图确认新增四行汇总和两行 Run 指标没有重叠；`com.longdev.xiaoling/.MainActivity` 保持前台，crash buffer 为空。
- 最终 Debug APK SHA-256：`cbbf2d16ae64a2f46b8ea901a3478a24ad7e00fbc32bbe6298389fb979a34976`。

## 2026-07-19 执行中断与 Android 权限撤销验证

实现与恢复决策：

- Runtime 在参数校验、审批结束后执行前、工具返回后验证前三个检查点读取 Android 权限。审批期间撤权时不创建 `tool.execute` 且不调用 Executor；工具执行期间撤权时保留成功 `tool.result`，执行步骤为 `COMPLETED`，验证步骤和 Run 为 `FAILED`，重试要求二次确认。
- 启动恢复会把不可原地恢复的旧 Run 收敛为 `CANCELLED`，并把该 Run 下所有 `PENDING/RUNNING` Step 同步收敛为 `CANCELLED`；`run.recovered` 保留中断前状态。新 Run 通过 `retryOfRunId` 关联来源，旧 Run、Step 和事件保持不变。
- 本轮中断恢复决策时还没有持久化执行回执或幂等副作用证明，因此 `EXECUTING/VERIFYING` 不原地恢复旧执行栈。后续阶段虽已建立回执 contract，但生产工具仍没有幂等键；Foreground Service 只提高存活概率，不改变此安全边界。

自动化与系统故障注入：

- `testDebugUnitTest`：184 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 39 条，合计 78 条全部通过。
- 定向 Room instrumentation 覆盖 `EXECUTING/VERIFYING` 进程重建：旧 Run 和活动 Step 均进入 `CANCELLED`，重试策略返回 `requiresConfirmation=true`，创建关联新 Run 后来源快照不变。
- 模拟器先授予 CAMERA，再用系统 `pm revoke` 从应用外部撤权；应用 PID `9479` 随即消失，确认系统会终止目标进程。权限状态回读为未授权，随后应用冷启动成功，crash buffer 为空。
- 未保留调用 `UiAutomation.revokeRuntimePermission` 的自撤权 instrumentation：系统终止测试目标进程后测试框架只能报告 `shortMsg=Process crashed`，无法继续断言应用恢复状态；确定性 Runtime 测试与外部 `pm revoke` 分别覆盖业务边界和真实系统行为。

真实模型与任务中心：

- instrumentation 后覆盖安装最新 Debug APK 并恢复未跟踪的本机 Provider，成功获取 6 个模型；凭据未进入文档、日志或提交。
- 首个 Chat Completions Run `run-d97266b5-8b0b-4f54-997b-4488ef628f1b` 因上游响应不含 `choices[0].message.content` 进入 `FAILED`，只创建 1 个失败的 `llm.plan` Step，工具未执行。旧 Run 保持原样，并在「可重试」筛选中显示重试入口。
- 切换到 Responses API 与 `gpt-5.5` 后，新 Run `run-270704e9-048d-42a5-a8fc-d8a9518a01f7` 执行 `/agent Read current time after fault boundary retry`，依次完成规划、参数校验、`app.current_time` 执行、验证、完成判断和总结共 6 步，最终为 `COMPLETED`。
- 成功 Run 的 3 条模型请求共计 `9915ms`、Prompt `4061B`、总 Token `942`，工具结果为 `当前时间：2026-07-19 04:11:28 · 时区：Asia/Shanghai`。任务中心实测显示 `2 个 Run · 成功率 50% · 平均 7.60s`、`模型耗时 9.92s · TTFB 3.30s · Prompt 4.0KB · Token 942（3/4）` 和 `失败分布 失败 1`；可重试筛选单独显示旧失败 Run。
- `com.longdev.xiaoling/.MainActivity` 保持前台，最终 crash buffer 为空。Debug APK SHA-256：`8c94c9ff037e19076f6cf477304b72fe1cb94e303f688149997e1833fa35d357`。

## 2026-07-19 执行回执与幂等证据 contract 验证

实现边界：

- `ToolExecutionReceipt` 记录 ToolCall ID、业务 operation ID、可选幂等键和 `COMMITTED / NOT_COMMITTED / UNKNOWN` 状态，并把执行时 `ToolReplaySafety` 声明快照嵌入 `tool.result` typed metadata；旧事件没有回执时继续按 `null` 解码，没有重放快照时默认 `RESTART_REQUIRED`。
- Runtime 在写入成功 `tool.result` 前校验回执必须属于当前 ToolCall，错配回执使执行步骤和 Run fail-closed，且不会落库为成功结果。
- `ToolExecutionRecoveryEvidencePolicy` 只有在执行时快照和当前定义都声明 `IDEMPOTENT_BY_KEY`、结果成功、ToolCall 身份一致、回执为 `COMMITTED` 且幂等键存在时，才判定已提交副作用可复用；应用升级后的当前定义不能放宽历史证据。该判定不等于恢复旧协程，也尚未接入 `AgentRunResumePolicy`。
- `notes.create / memory.remember` 使用真实 note/memory ID 记录 `COMMITTED` 回执；回读失败仍保留 operation ID。当前存储层没有按 ToolCall 去重，幂等键为 `null`，两项工具继续保持默认 `RESTART_REQUIRED`。
- 任务中心 typed Event 显示调用 ID、operation ID、回执状态及“幂等证明已记录/未记录”，不展示原始幂等键。

TDD 与自动化结果：

- Red/Green 覆盖回执 JSON 往返、旧事件兼容、回执跨 ToolCall 错配拒绝、幂等工具完整证据判定、两类真实写工具 operation ID、任务中心脱敏呈现，以及 Runtime 在成功事件落库前拒绝错配回执。
- `testDebugUnitTest`：190 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- 新增 Room instrumentation 验证 Android `org.json` 与 Room 快照能完整往返嵌套执行回执。最终代码在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 40 条，合计 80 条全部通过。
- 最终 APK 覆盖安装后，真机 Provider 仍为 1 条，`com.longdev.xiaoling/.MainActivity` 为前台 Activity，crash buffer 为空；未创建临时真实笔记或记忆，避免为可由确定性测试覆盖的 contract 验收污染用户数据。
- Debug APK SHA-256：`7a2ea0e569715f11b5bd0848727b4598c286190c9f4a143dac670d3d66491b26`。

## 2026-07-19 `notes.create` 存储层幂等验证

实现与恢复边界：

- `AgentNoteStore.create` 强制接收幂等键，`notes.create` 使用当前 `ToolCall.id`；`agent_notes.idempotencyKey` 由 Room v17 可空唯一索引约束。
- 同键同标题/正文在 Room 数据库重开后仍返回原笔记和同一 operation ID；同键不同载荷抛出幂等冲突，不覆盖也不新建记录。
- `notes.create` 的执行回执现在包含 ToolCall 幂等键并声明 `IDEMPOTENT_BY_KEY`；`memory.remember` 仍保持 `RESTART_REQUIRED`。
- 本阶段不接入 `AgentRunResumePolicy`；完整幂等证据只证明已提交副作用可识别，不代表通用旧协程、验证栈或其他工具可原地恢复。
- v16→v17 迁移保留旧笔记内容并把其幂等键保持为 `NULL`；新 v17 Schema 已导出。

自动化结果：

- `testDebugUnitTest`：192 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过，完整 Gradle 构建成功。
- `XiaoLingToolRegistryTest` 覆盖重复 ToolCall 返回同一 operation ID、载荷漂移拒绝，以及 `notes.create / memory.remember` 的重放声明边界。
- Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 42 条 instrumentation，合计 84 条全部通过。其中迁移类 12 条，新增 Room 笔记幂等定向测试 1 条。

最终设备状态：

- instrumentation 后重新覆盖安装最新 Debug APK，Pixel_9 和 Redmi 均冷启动成功，`com.longdev.xiaoling/.MainActivity` 为前台 Activity。
- Redmi 只读回读确认 `PRAGMA user_version=17`、Provider 仍为 1 条、`index_agent_notes_idempotencyKey` 存在；最终 crash buffer 为空。
- Debug APK SHA-256：`528e7e79f0b973420a1c5b9180f7bc46f9143ca0b6e15c6969541b979a657036`。

## 2026-07-19 `notes.create` 验证阶段恢复

实现边界：

- `AgentRunResumePolicy` 新增 `COMMITTED_TOOL_VERIFICATION`。只有 `EXECUTING / VERIFYING` Run 的工具执行 Step 与 ToolResult 一一对应、最后一个结果尚无 `tool.verify`、ToolCall 可唯一还原，且历史快照与当前定义均为 `IDEMPOTENT_BY_KEY`、回执为 `COMMITTED` 并带完整幂等键时，才允许原 Run 恢复。
- `ToolRegistry.verifyCommittedEffect()` 是只读恢复入口；默认返回不支持。生产实现仅为 `notes.create` 按 receipt `operationId` 回读笔记，并核对 ToolCall ID、幂等键、状态、标题和正文，不调用 `create()`，不会产生第二条笔记。
- Runtime 补齐原 execution Step 后只执行权限复检、operation 回读和后置验证，写入 `tool.verify`；随后使用本地可信事实写入 `recovery.summarize` 和 `run.recovery_summary`，不调用模型，也不恢复旧规划协程。
- 若该 Agent Run 属于多步骤 Workflow，只保存当前步骤的恢复输出并把剩余 Workflow 收敛为 `FAILED`；后续仍创建关联新 Run 并复用成功前缀。其他工具、证据不完整的旧事件和通用执行栈继续 fail-closed。
- 双轴审查修复了两项启动一致性问题：多工具 Run 从前序 `tool.result + tool.verify` 重建按顺序排列的可信事实；Workflow 启动对账显式跳过已筛选的恢复候选，避免在 operation 回读前提前把当前步骤判为失败。

TDD、自动化与真实恢复：

- Red/Green 覆盖完整证据判定、Runtime 不调用 `execute()` 的只读恢复、真实 Registry 按 operation ID 回读且不新增笔记、`tool.result` 落库后的确定性进程终止、Room Repository 重建保留候选，以及 `run.recovery_summary` typed metadata 往返。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；198 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机各执行 44 条 instrumentation，合计 88 条全部通过。
- Redmi 真实强停/冷启动验收从 `EXECUTING + RUNNING tool.execute + COMMITTED tool.result` 恢复为 `COMPLETED`；原 Run 新增 `tool.verify` 和 `recovery.summarize`，`tool.result` 仍只有 1 条，同幂等键笔记仍只有 1 条，并生成可信 `AGENT_RESULT`。临时 Run、Step、Event、Note、Conversation 和 Message 均已清理。

最终设备状态：

- instrumentation 后向 Pixel_9 与 Redmi 重新覆盖安装最终 Debug APK，两台设备均冷启动到 `com.longdev.xiaoling/.MainActivity`，crash buffer 为空。
- Redmi 回读 `PRAGMA user_version=17`、Provider 为 1 条、临时笔记为 0 条；使用未跟踪的本机兜底配置重新获取 6 个模型并选择 `gpt-5.4-mini`。
- 真实普通对话冒烟请求返回 HTTP 200 和 `OK`；请求使用设置项中的默认 User-Agent，Authorization 日志保持脱敏。
- Debug APK SHA-256：`aaf9cdeb087b0349d72ec4777526b26355ed06401a098874a5c53e373c6e2852`。

## 2026-07-19 `memory.remember` ToolCall 级存储幂等验证

实现与安全边界：

- Room v18 新增 `agent_memory_operations`：`idempotencyKey` 为主键，映射 memory ID、原始请求载荷 SHA-256 和创建时间。操作映射与正式记忆/FTS 写入位于同一事务；旧记忆迁移后不伪造 ToolCall 来源。
- `memory.remember` 使用 ToolCall ID 调用 Store，同键同载荷在数据库重开后返回原 memory operation，同键载荷漂移在写入前抛出冲突。独立 operation ledger 可承受记忆后续编辑和语义去重；目标被删除时明确失败，不重新创建第二条记忆。
- 工具回执现在包含 ToolCall 幂等键并声明 `IDEMPOTENT_BY_KEY`。`ToolRegistry.supportsCommittedEffectVerification()` 独立表达只读恢复能力，生产 Registry 仍只对白名单 `notes.create` 开放；`memory.remember` 继续 `RESTART_REQUIRED`。

TDD 与自动化：

- Red/Green 覆盖 Registry 回执、同 ToolCall 重放、载荷漂移拒绝、数据库重开、v17→v18 迁移、旧记忆保留，以及“有幂等证据但无只读验证能力”仍不得进入原 Run 恢复。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；200 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机各执行 46 条 instrumentation，合计 92 条全部通过。
- instrumentation 后向两台设备覆盖安装最终 Debug APK；Redmi 回读 `PRAGMA user_version=18`、Provider 为 1 条、临时笔记/记忆/记忆 operation 均为 0，重新同步 6 个模型并选择 `gpt-5.4-mini`。真实普通对话请求返回 HTTP 200 和 `OK`，默认 User-Agent 正确且 Authorization 日志保持脱敏。
- 最终 Debug APK SHA-256：`b989507989b101796a4fb79e4a4daaa70fa9bebc9f0e608b9dc22da071a025ab`。

## 2026-07-19 `memory.remember` 验证阶段恢复

实现与安全边界：

- Room v19 为 `agent_memory_operations` 增加可空 `resultHash`。新 operation 原子保存内容、标签、类型、来源和置信度的提交结果快照哈希；v18→v19 只新增空列，不为历史 operation 伪造结果证据。
- `AgentMemoryStore.verifyRememberedOperation()` 按 ToolCall 幂等键、回执 memory ID、原请求载荷和当前记录做只读验证。未修改、启用且未过期时成功；业务字段编辑、禁用、过期、删除分别返回 `MEMORY_CHANGED`、`MEMORY_DISABLED`、`MEMORY_EXPIRED`、`MEMORY_NOT_FOUND`，缺少 v19 结果快照返回 `EVIDENCE_INCOMPLETE`。
- 置顶、引用时间和尚未到期的未来过期时间不属于提交业务快照，不阻止恢复；删除后使用原撤销快照恢复全部业务字段时可再次验证成功。
- `XiaoLingToolRegistry` 为 `memory.remember` 开放受限 `verifyCommittedEffect()`，从持久化 Run Context 重建原来源请求；恢复不调用 `remember()`，原 operation ID、幂等键和执行回执保持不变。Runtime 仍只恢复最后一项已提交结果的后置验证和本地总结，不恢复旧模型协程、通用执行栈或 Workflow 后续步骤。

TDD、自动化与真机可用性：

- Registry Red/Green 证明只读恢复调用验证接口且 `remember()` 总调用数保持为 1；Room 测试覆盖数据库重开、载荷漂移、内容/标签/类型/来源/置信度字段矩阵、置顶/引用时间/未来过期时间例外、删除撤销、v18 缺证据 fail-closed，以及 `tool.result` 落库后关闭并重开磁盘 Room 的组件重建恢复。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；201 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机分别执行完整 54 条 instrumentation，合计 108 条全部通过。
- instrumentation 后覆盖安装最终 Debug APK，Redmi 冷启动到 `com.longdev.xiaoling/.MainActivity`；原 Provider 与 `gpt-5.4-mini` 仍可用。最终真实普通对话 `Reply only OK final recovery smoke` 在 4.83 秒返回 HTTP 200 和 `OK`，默认 User-Agent 正确，Authorization 日志保持脱敏，crash buffer 为空。
- 以 `08a4002` 为固定点的 Standards/Spec 双轴审查均已完成；修复注释理由、重复成功文案、文档残留、完整字段矩阵和磁盘 Room 冷启动覆盖后，两轴最终均为 0 项 finding。
- 最终 Debug APK SHA-256：`c3b8c5cee6d7a7fcf9ad00428247611980526eb621deb5ace01c7edbfb3468e9`。

## 2026-07-19 `memory.remember` 恢复失败产品呈现

实现与边界：

- `ToolRecoveryFailure` 统一携带稳定错误码、用户可读原因和建议动作；`XiaoLingToolRegistry` 覆盖 `OPERATION_NOT_FOUND / EVIDENCE_INCOMPLETE / PAYLOAD_MISMATCH / OPERATION_MISMATCH / MEMORY_NOT_FOUND / MEMORY_CHANGED / MEMORY_DISABLED / MEMORY_EXPIRED` 八类只读恢复失败。
- Runtime 只把结构化恢复失败写成 `run.recovery_failed + RunEventMetadata.RecoveryFailure`；普通恢复异常仍写 `run.failed`。失败 Run 和活动 Step 收敛为 `FAILED`，不会继续旧 Run、旧模型协程或 Workflow 后续步骤。
- 任务中心详情顶部显示最新恢复处理状态带，事件列表继续展示工具名、错误码、原因和建议。所有建议均明确要求修复记忆状态后创建新 Run，旧 Run 保持不变。
- 生产 Registry 当时没有第三个适合推广“提交快照 + 只读 probe”的写工具，因此该阶段没有虚构工具或放宽恢复白名单；后续阶段已转向独立 ToolCall/ToolResult Room Ledger。

自动化与双机验收：

- JVM 测试覆盖结构化失败从 Registry 到 Runtime、JSON 往返、旧事件兼容、八类建议映射和任务中心呈现；Room instrumentation 覆盖 typed event 持久化往返。完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；205 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机分别执行完整 55 条 instrumentation，合计 110 条全部通过。
- Redmi 临时构造 `MEMORY_DISABLED` 失败 Run，任务中心顶部状态带与事件字段均可见；UI tree 和截图确认无截断、重叠或横向溢出。临时 Run、Step、Event 已从生产库清理，查询结果为 0；应用最终 force-stop，Provider 数据未修改。
- 以 `3004528` 为固定点执行 Standards/Spec 双轴审查。初审发现失败原因与建议使用两次 `when`，且缺少贴近实现的业务理由注释；修复为单次 `ToolRecoveryFailure` 穷尽映射并增加 `// long:` 注释后，两轴复审均为 0 项 finding。
- 审查后向 Redmi 覆盖安装最终 APK 并冷启动，真实普通对话 `Reply only OK stage18 postreview smoke` 在 2.93 秒返回 HTTP 200 和 `OK`；默认 User-Agent 正确，Authorization 日志保持脱敏，crash buffer 为空，`com.longdev.xiaoling/.MainActivity` 保持前台。
- 最终 Debug APK SHA-256：`c2880108f943eabd09f82fab93a1b9a2646e77da9330d61616de4354775a8f29`。

## 2026-07-19 独立 ToolCall/ToolResult Room Ledger

实现与迁移边界：

- Room v20 新增 `agent_tool_calls / agent_tool_results`。调用表以 ToolCall ID 为主键，保存 Run、工具、风险、排序参数和 proposed/validated 事件锚点；结果表以 ToolCall ID 为主键，保存结果事件、正文、显式错误、耗时、Executor/最终验证、记忆引用、重放声明和拆列执行回执。
- `RoomAgentRunRepository.appendEvent()` 在同一 Room 事务中写 RunEvent 与 Ledger；ToolCall 的 Run、工具、风险或参数漂移会回滚整笔事务。`tool.verify` metadata 增加可选 ToolCall ID，v20 新结果可精确更新验证状态，旧事件缺少字段时继续兼容。
- v19→v20 只创建空表，不从可能缺失 ToolCall ID 的历史事件补造关联。旧 Run 仍通过 RunEvent 读取和恢复；旧验证阶段恢复可以继续追加 `tool.verify`，不会因 Ledger 为空失败。当前 `AgentRunResumePolicy` 和任务中心仍读取 RunEvent，没有扩大通用恢复能力。

TDD 与自动化：

- Red/Green 覆盖 Repository 重建后的完整调用/结果/验证查询，以及 v19 旧 Run 迁移后追加验证；补充失败结果显式错误、ToolCall 参数漂移事务回滚和 `tool.verify` 新旧 metadata 兼容。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；206 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机分别执行完整 64 条 instrumentation，合计 128 条全部通过。
- 以 `c81d3a8` 为固定点执行 Standards/Spec 双轴审查。初审发现未知 ToolCall 结果会静默退化为 event-only，`tool.verify` 未校验 Run/工具身份，且关键绑定规则缺少贴近实现的业务注释；后续复审又发现新写 ToolResult/Verification 可缺少 ToolCall ID。修复后 null/未知结果、null 验证、跨 Run 验证和错工具验证均整笔回滚，只有同一 Run 的历史 Call/Result 事件可进入 v19 fallback；最终 Standards/Spec 复审均为 0 项 finding。
- instrumentation 后向 Redmi 覆盖安装最终 APK，恢复 1 个 Provider、同步并启用 6 个模型，选择 `gpt-5.4-mini`。真实普通对话 `Reply with exactly: OK` 在 4.18 秒返回 HTTP 200 和 `OK`；默认 User-Agent 正确，Authorization 日志保持脱敏，crash buffer 为空，`com.longdev.xiaoling/.MainActivity` 保持前台。
- Redmi 只读回读确认 `PRAGMA user_version=20`、Provider 为 1 条；本轮未创建 Agent Run，`agent_tool_calls / agent_tool_results` 均为 0 条，符合普通对话不写工具账本的边界。
- 最终 Debug APK SHA-256：`dd924a63cb388213cbf643e1526f3ebd73121c4648933a0f6a9fd7a1120e87b1`。

## 2026-07-19 任务中心 Tool Ledger-first 明细

实现与兼容边界：

- `AgentRunDetailRecord` 现在携带独立工具账本；`recentRunDetails()` 通过批量 DAO 一次读取最近 Run 的 ToolCall/ToolResult，再按 `runId` 分组，单 Run 详情使用同一账本模型。
- 任务中心有账本时使用 Ledger-first，并按调用展示 proposed、validated、result、verified 四阶段；typed RunEvent 只用于双源身份、字段和事件锚点核对。孤立结果、部分缺失或字段漂移显示稳定一致性告警，不自动修补数据库。
- 账本全空且存在 typed 工具事件时才进入旧 Run fallback。缺少 ToolCall ID 的旧 ToolResult/ToolVerification 保留为“关联未知”的独立条目，不按工具名、时间顺序或合成 ID 伪造关联；普通无工具 Run 保持空详情。
- `AgentRunResumePolicy`、重试策略和指标没有切换到新账本，旧 Run 状态与恢复结论保持不变。

TDD、审查与 Redmi 真机验收：

- 212 条 JVM 测试通过；新增策略测试覆盖 Ledger-first、旧事件回退、无身份事件保守展示、双源字段漂移、事件结果缺账本、孤立结果和无工具 Run。完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Redmi Note 8 Pro Android 14 真机执行完整 65 条 instrumentation，全部通过；新增 Room 测试确认最近多个 Run 的调用和结果账本分别装配到对应详情。
- 以 `d3655b9` 为固定点执行 Standards/Spec 双轴审查。初审发现关键投影缺少贴近实现的 `// long:` 业务注释，以及旧无身份结果按同工具最近调用归组会伪造关联；修复并增加回归测试后，两轴复审均为 0 项 finding。
- instrumentation 后向 Redmi 覆盖安装最终 APK，恢复 1 个 Provider、同步并启用 6 个模型。`gpt-5.4-mini` 普通对话在 2.84 秒返回 HTTP 200 和精确 `OK`；该模型的 Agent Chat Completions 规划返回格式不兼容并正确收敛为失败，旧 Run 保持不变。切换 `gpt-5.5` 后真实 `/agent current time stage20 gpt55` 在 10.30 秒完成。
- 真机 Room 回读确认 `PRAGMA user_version=20`，成功 Run 的 `app.current_time` 账本 proposed/validated/result/verified 锚点齐全、结果成功且最终验证为 `PASSED`。任务中心显示“数据源：独立工具账本”和四阶段完成，无一致性告警；UI tree 与截图确认无文字重叠或横向溢出，crash buffer 为空。
- 本项目后续 Android 安装、instrumentation、UI 自动化和真实模型验收只使用 Redmi 真机 `wsvwypiz7xwslvl7`，除非用户另行明确修改该约束。
- 最终 Debug APK SHA-256：`ceeb4930cfc767a5a31322cebc6b9eb7c9ddb223f91d42a71a2dba09e4192fa8`。

## 2026-07-19 受限恢复 Ledger-first 证据源

实现与兼容边界：

- 新增 `AgentRunRecoveryEvidencePolicy`：v20 Run 只要存在独立工具账本，就以 Ledger 作为恢复事实源；typed RunEvent 仅核对 proposed、validated、result、verified 原子双写锚点。每个调用必须恰好对应一个结果，身份、字段、派生错误、时间、事件顺序、额外事件或重复 ID 任一异常均 fail-closed，不回退旧事件推断。
- 恢复序列按 proposed 事件锚点排序，不依赖 ToolResult 查询返回顺序；多步骤只重建已验证成功前缀，并把最后一个验证状态为空的已提交结果交给只读验证。`notes.create / memory.remember` 白名单、`COMMITTED + IDEMPOTENT_BY_KEY` 证据判定、旧模型协程、通用执行栈和 Workflow 后续步骤边界均未扩大。
- 账本完全为空的 v19 及更早 Run 继续使用 typed event fallback。阶段 2026-07-19 当时的旧实现曾让缺少 ToolCall ID 的 `tool.verify` 按原结果顺序配对；第 51 阶段已改为关联未知并 fail-closed，不再让同名多步调用被猜配。
- 新增共享 `AgentToolLedgerConsistency` 比较器，任务中心告警与恢复策略共同核对调用、结果和验证字段，避免两套双源规则继续漂移。

TDD、审查与 Redmi 验收：

- 221 条 JVM 测试通过；新增覆盖完整 Ledger、旧事件回退、旧同名多步无 ID 验证、验证/时间/错误字段漂移、额外结果事件、部分账本缺结果、乱序 ToolResult 与两步已验证前缀恢复。完整 JVM 套件使用 `--rerun-tasks` 重新执行通过。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew lintDebug assembleDebug assembleDebugAndroidTest --console=plain` 通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 65 条 instrumentation，0 跳过、0 失败；两个进程重建恢复测试均断言恢复证据源为 `LEDGER`，且不会重复调用笔记写入或记忆写入。
- 以 `440ef12` 为固定基线执行 Standards/Spec 双轴审查。初审发现结果列表顺序依赖、双源时间/错误字段漏检、旧同名调用回退收紧和展示/恢复比较重复；修复并补回归测试后，两轴复核均为 0 项 finding。
- instrumentation 后重新安装最终 Debug APK，从未跟踪的本机兜底配置恢复 1 个 Provider、同步并启用 6 个模型，选择 `gpt-5.5`。真实 `/agent Use app.current_time tool and tell me the current time` 在 Redmi 上完成模型规划、参数校验、工具执行、后置验证、二次规划和最终总结，Run 状态为 `已完成`，crash buffer 为空。
- 最终 Debug APK SHA-256：`4940a6bf9c5497e0e2828f0e1237e9d0c484ebc077f21a4174b861a9a00ed566`。

## 2026-07-19 失败 Run 重试 Ledger-first 副作用判断

实现与兼容边界：

- `AgentTaskRetryEvidencePolicy` 对 v20 非空工具账本使用 Ledger-first 副作用判断，并复用 `AgentToolLedgerConsistencyPolicy` 核对 proposed、validated、result、verified 的身份、字段、时间、锚点和顺序。异常账本按可能已有副作用处理，重试必须二次确认，不回退旧事件推断。
- 非 SAFE 调用只要 `result.success=true`，或执行回执为 `COMMITTED / UNKNOWN`，就要求二次确认；`success=false + COMMITTED/UNKNOWN` 仍不能假设外部动作没有发生。明确失败且回执为 `NOT_COMMITTED`、或链尾只停在 proposed/validated 尚未执行时，不因账本本身增加确认。
- 账本完全为空的旧 Run 继续使用 typed RunEvent 成功结果回退。原有 Step 中断、`run.recovered` 和 `EXECUTING/VERIFYING` 门禁保持不变，新 Run 仍通过 `retryOfRunId` 关联来源，旧 Run 不修改。
- `AgentRunMetricsPolicy` 继续使用 Step 与 `llm.request.completed` typed event，因为独立工具账本没有模型耗时、TTFB、Prompt 字节和 usage 等价字段；本阶段没有为形式统一改变指标口径。

自动化、审查与 Redmi 真机验收：

- 228 条 JVM 测试通过，0 失败、0 跳过；新增覆盖非 SAFE 成功、SAFE 成功、账本缺结果/未锚定验证 fail-safe、Ledger/Event 风险漂移、仅 validated 未执行、`success=false + COMMITTED/UNKNOWN` 及旧 Run event fallback。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew lintDebug assembleDebug assembleDebugAndroidTest --console=plain` 通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 66 条 instrumentation，0 跳过、0 失败；新增 Room 场景确认 `success=false + COMMITTED` 的非 SAFE 工具仍要求重试确认。在线的 Android 模拟器未参与本阶段验证。
- 以 `908857a` 为固定基线执行 Standards/Spec 双轴审查。修复 `success=false + COMMITTED` 漏判、重复账本校验、文档同步和关键安全规则缺少贴近实现的 `// long:` 业务理由后，除已修复注释项外没有剩余高置信 finding。
- instrumentation 后向 Redmi 覆盖安装最终 Debug APK，从未跟踪的本机兜底配置恢复 Provider，成功同步并启用 6 个模型，选择 `gpt-5.5` 与 Responses API。真实 `/agent Use app.current_time tool and tell me the current time for stage22 retry ledger verification` 在 8 秒内完成，Run `run-eec4bbb0-72d4-4f08-a78f-33a71e3906cb` 状态为 `COMPLETED`。
- Redmi Room 只读回查确认 `app.current_time` 的 ToolCall 为 `SAFE`，proposed/validated/result/verified 四个事件锚点齐全，结果成功且验证为 `PASSED`；crash buffer 为空，验证后应用已重新冷启动到 `com.longdev.xiaoling/.MainActivity`。
- 最终 Debug APK SHA-256：`fbbbceab49685b1554428efa9d8a6b9a2af188c1983c07eed4afbe30f25414f1`。

## 2026-07-19 Agent Profile v1

实现与安全边界：

- Room v21 新增 `agent_profiles`，保存名称、标识、Provider、模型、API 模式、系统提示词、当前会话上下文策略、工具白名单、Skill 白名单和长期记忆开关。v20→v21 迁移只建空表，不从全局 Provider 偏好伪造 Agent 身份；默认 Agent 在 Provider、模型、工具和 Skill 完成加载后创建。
- 新 Run 写入且只允许写入一条 `agent.profile.selected` typed event，metadata 冻结完整 Profile 快照。Profile 系统提示词进入规划与总结请求，但明确不能覆盖 JSON 协议、工具白名单、风险、审批、Android 权限、验证和可信事实边界。
- `ProfileScopedToolRegistry` 在工具发现、定义读取、执行和已提交结果验证入口强制白名单；Skill 只能继续缩小工具面。Profile 关闭记忆后，单次 `/agent` 开关不能重新开启。
- 待审批恢复和 `notes.create / memory.remember` 已提交结果恢复使用原 Run Profile 快照；重复、损坏、未注册工具或 Skill 越权审计 fail-closed。失败 Run 重试创建新 Run 并使用当前选中的 Profile，旧 Run 快照与历史记录保持不变。
- 设置页「Agent Profiles」支持新增、编辑、选择和删除；至少保留一个 Profile。Provider 删除和模型停用会阻止破坏仍绑定的 Agent。前台 Workflow 一次执行固定同一 Profile，后台 Worker 一次执行缓存同一 Profile。

自动化与构建：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过。
- 237 条 JVM 测试通过，0 失败、0 跳过；新增 Profile 校验、运行配置、工具/Skill 白名单、记忆硬边界、typed metadata、恢复审计和 Workflow 固定 Profile 覆盖。
- `lintDebug`、Debug APK 和 AndroidTest APK 构建成功；Room v21 Schema 已生成。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 68 条 instrumentation，0 失败、0 跳过。在线 Android 模拟器未参与本阶段安装、测试或验收。

Redmi 真实模型与数据库验收：

- 覆盖安装最终 Debug APK 后，Provider 同步 6 个模型；创建并选择 `Time Agent`，固定 `gpt-5.5 + RESPONSES`。输入区在 `/agent` 模式显示 Profile 标识与记忆开关，无文字重叠或横向溢出。
- 真实 `/agent Use app.current_time tool and tell me the current time for AgentProfile stage23` 在约 10 秒内完成。Run `run-43d56319-e94b-4e31-b2ff-8461a6907480` 为 `COMPLETED`，依次完成规划、参数校验、工具执行、后置验证、二次规划和总结；结果为 `2026-07-19 11:31:11 · Asia/Shanghai`。
- Redmi Room 只读回查确认 `PRAGMA user_version=21`、`agent_profiles=2`，最新 Run 恰好一条 `agent.profile.selected`，快照为 `Time Agent / gpt-5.5 / RESPONSES`。`app.current_time` 的 proposed、validated、result、verified 四个事件锚点完整，结果成功且 `verificationStatus=PASSED`。
- crash buffer 为空；只读导出后应用已重新启动到 `com.longdev.xiaoling/.MainActivity`。最终 Debug APK SHA-256：`9fb3f4128327f8055e5f7e2212d0ba24f6290cf6b23bd596f59bfb2ab8d98bba`。

## 2026-07-19 Text/Tool 消息 parts

实现与兼容边界：

- Room v22 新增 `message_parts`，以稳定 `id` 保存 `messageId / sequence / type`；Text 保存正文，Tool 保存工具名、参数 JSON、结果、成功状态、验证状态和记忆引用。`messages.text` 继续作为旧版本、搜索、摘要和导出兼容投影。
- v21→v22 为每条历史消息生成 `${messageId}-text`，不解析旧 `verifiedAgentContext` 猜造 Tool。旧 Agent 消息在读取时可以由可信上下文安全投影 Tool，只有后续正常保存时才写入结构化行。
- `AgentMessagePartPolicy` 要求 Tool part 同时满足 `MessageOrigin.AGENT_RESULT` 与可解码 `VerifiedAgentContext`；普通 assistant 即使声称执行工具也只能产生 Text。已存 parts 与可信投影逐项一致时保留稳定 ID，漂移时回退可信投影。
- `MessageRepository` 统一前台会话与后台 Workflow 的 message/parts 原子写入；覆盖同消息 ID 前清除旧 parts。前台快照只增量 upsert，用户删除通过显式会话 ID 传递并保留到事务成功，避免旧快照清除后台刚追加的消息或新建会话。Compose 在同一气泡中按 sequence 展示 Text 和非嵌套 Tool 证据区。

自动化与 Redmi 回归：

- 强制重跑 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks`，构建成功；242 条 JVM 测试通过，0 失败、0 错误、0 跳过。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 73 条 instrumentation，0 失败。新增覆盖 v21→v22 Text 回填、Text/Tool 磁盘数据库重开往返、后台 Agent 结果同时写入两类 parts，以及“前台旧快照→后台同会话追加/新建独立会话→前台保存”的两类交错写；后台消息和 Tool part 均保留，只有显式删除 ID 被清理。
- 在线 Android 模拟器未参与本阶段安装、测试或验收。crash buffer 为空。

真实模型、UI 与数据库验收：

- `Time Agent + gpt-5.5 + RESPONSES` 执行 `/agent Validate current time for message parts stage24 using app.current_time`，Run `run-67b60545-1b02-4898-9aee-994767fa6690` 在约 10 秒内进入 `COMPLETED`，工具结果为 `2026-07-19 12:14:01 · Asia/Shanghai`。
- UI tree 与 Redmi 截图确认同一 assistant 气泡包含 Text 总结、`工具 · app.current_time`、`结果可读` 和结果正文，无文字重叠或横向溢出。历史 Stage 23 Agent 消息也能通过旧可信上下文兼容显示 Tool part。
- Redmi Room 只读回查确认 `PRAGMA user_version=22`。最新 assistant 消息存在 sequence 0 `TEXT` 与 sequence 1 `TOOL`；Tool 行为 `app.current_time / success=1 / verificationStatus=READABLE_ONLY`。当时全库共 4 个 Text part、2 个 Tool part。
- 最终 Debug APK SHA-256：`f5b28ffec4709a1f2e4c2ce811cffc7190076f60921ddac80fcbbe97db850015`。最终包覆盖安装后，`com.longdev.xiaoling/.MainActivity` 为 Redmi 前台 Activity，进程存活且 crash buffer 为空。

## 2026-07-19 Reasoning 消息 part

实现与协议边界：

- Room v23 为 `message_parts` 增加可空 `reasoningSource / providerItemId / summaryIndex`。v22→v23 不创建新 part，既有 Text/Tool 行三列保持空；全新 v23 Schema 和迁移均有自动化保护。
- 普通对话新增默认关闭、设备持久化的“推理”开关。仅当用户开启且 API 模式为 Responses 时，请求体发送 `reasoning.summary=auto`；会话压缩、Chat Completions 和关闭状态均不发送。
- 非流式只解析 `output[].type=reasoning` 下的 `summary[].type=summary_text`；SSE 只聚合 `response.reasoning_summary_text.delta/done`，done 完整摘要覆盖本地 delta。`ProviderMessagePartPolicy` 按供应商 item ID 与 summary index 去重，生成稳定消息内 ID，并固定 Reasoning 在 Text 前。
- `parseResponsesText()`、流式 content part 和 output item 路径只接受 `output_text`。原始 `reasoning_text`、非标准 `reasoning_content` 和 Agent 结果中的 Reasoning 不进入正文、parts 或 `VerifiedAgentContext`，不能生成或替代 Tool 事实。
- debug 响应和 SSE 日志通过 `NetworkDebugLogSanitizer` 递归脱敏原始推理字段；带推理标记但无法解析的 payload 整体失败关闭。供应商可展示的 `summary_text` 保留用于协议排查。
- Compose 在同一 assistant 气泡内默认折叠 Reasoning，显示“推理摘要 / 供应商提供”；展开后按 Markdown 渲染，正文继续独立展示。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 完整强制执行通过，88 个 Gradle task 全部重新执行。
- 256 条 JVM 测试通过，0 失败、0 错误、0 跳过。新增覆盖请求开关、非流式/流式协议、原始推理拒绝、消息 part 去重与顺序、Agent 可信边界和日志脱敏；日志测试同时覆盖直接 `type=reasoning` 内容和嵌套 `reasoning` 对象的失败关闭。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 直接安装 Debug/Test APK，并运行 `am instrument` 完整 77 条 instrumentation，0 失败。新增覆盖 v22→v23 迁移、Reasoning/Text 磁盘重开往返、偏好默认关闭与恢复、默认折叠和展开/收起。
- adb 列表中虽然存在在线模拟器，但本阶段所有安装、instrumentation、UI、截图、数据库和真实模型命令均显式指定 Redmi 串号，没有向模拟器发送验证命令。

Redmi 真实模型、UI、日志与数据库验收：

- 选择 `gpt-5.5 + RESPONSES` 并开启推理。非流式问题在 5.36 秒返回 `FINAL=1776`，供应商摘要为 `Verifying final answer equals 1776`；SSE 问题首字 4.10 秒、总耗时 4.42 秒，返回 `STREAM=2091`，收到 1 个 summary delta 和 1 个 done。
- 两次请求日志均确认默认 User-Agent 正确、Authorization 为 `***MASKED***`、请求包含 `reasoning.summary=auto`；SSE 日志没有 `reasoning_text/reasoning_content` 原始字段。
- Redmi UI tree 与截图确认输入区“流式 / Resp / 推理 / gpt-5.5”无重叠或横向溢出；Reasoning 默认折叠，展开后可见供应商摘要，最终正文始终独立显示。重启后已保存的推理偏好在重新切换 Responses 时恢复为开启。
- Room 只读回查确认 `PRAGMA user_version=23`。最新非流式与流式 assistant 消息均为 sequence 0 `REASONING / PROVIDER_SUMMARY` 和 sequence 1 `TEXT`，item ID 与 summary index 完整；两条消息 `VerifiedAgentContext` 均为空，Tool part 数量为 0。
- 最终 Debug APK SHA-256：`1d745770a92bff5ff4ceb1bb7ad8e0b68ac25abf27cf114991ecc5d61e2f725e`。`com.longdev.xiaoling/.MainActivity` 保持 Redmi 前台，进程存活，crash buffer 为空。

## 2026-07-19 用户图片 MessagePart

实现与安全边界：

- Room v24 为 `message_parts` 增加可空 `mimeType / fileName / binaryData / imageDetail`。v23→v24 只加列，不创建历史 Image；全新 v24 Schema、迁移链和磁盘数据库重开往返均有自动化保护。
- 系统文件选择器单次接收 PNG/JPEG/WEBP。`ImageAttachmentReader` 以 8 MB 为硬上限有界读取，校验声明大小、MIME、文件签名与 Android 解码尺寸；进入消息后复制字节并写入 Room BLOB，不依赖长期 URI 权限，数据库备份自然包含附件。
- Responses 将 USER Image 映射为 `input_text + input_image`，`image_url` 使用 Data URL，detail 为 `auto`。Chat Completions 明确拒绝图片；第 75 阶段起 `/agent` 仅在 Responses 规划请求接收 USER 单一图片，普通聊天历史图片仍只在 Responses 最近上下文窗口内回传。
- `AgentMessagePartPolicy` 只为 `MessageOrigin.USER` 保留 Image，并继续剔除伪造 Reasoning/Tool。普通 assistant、Agent 结果、摘要和 `VerifiedAgentContext` 均不能接收图片或把模型视觉描述升级为工具事实。
- Compose 输入区支持读取状态、缩略图、文件名/大小、移除按钮；消息气泡使用采样解码展示历史图片。请求/响应日志脱敏图片 Data URL、`file_data`、生成图片结果、原始推理和 `encrypted_content`。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 强制重跑通过，88 个 Gradle task 全部执行；267 条 JVM 测试，0 失败、0 错误、0 跳过。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 直接安装最终 Debug/Test APK，并运行完整 85 条 instrumentation，0 失败、0 跳过。新增覆盖 v23→v24、Image/Text 磁盘重开、真实 URI PNG/损坏/超限读取、按会话加载 BLOB、轻量快照保留未加载图片、显式删除防陈旧快照复活和 Compose 图片节点；Reasoning 展开测试等待异步 Markdown 语义节点，消除加载时序误报。
- adb 列表中存在在线模拟器，但本阶段所有安装、instrumentation、文件选择、截图、数据库和真实模型命令均显式指定 Redmi 串号，模拟器未参与验证。
- Redmi UI 通过系统 DocumentsUI 选择 `lingce-stage26.png` 后，输入区显示 83 KB PNG 缩略图、文件名与移除按钮，模型/模式/发送控件无重叠或横向溢出。

真实模型、日志与数据库验收：

- 一次性、不提交的 instrumentation 验收驱动真实 `XiaoLingViewModel` 完成“生成红色 PNG → URI 读取 → Responses → 当前设备 Provider `gpt-5.5` → 会话保存 → Room 回读”，5.942 秒完成并返回 `IMAGE_OK`。
- `XiaoLingHttp` 请求日志确认 `input_image.image_url=data:image/png;base64,***REDACTED***`、Authorization 为 `***MASKED***`、默认 User-Agent 正确；最终响应文本 `IMAGE_OK` 保留，供应商 `encrypted_content` 已纳入正式 sanitizer 回归。
- Redmi 主库只读回查确认 `PRAGMA user_version=24`；最新 Image 行为 `image/png / real-image-e2e.png / 883 bytes / AUTO`，证明图片字节与用户消息已持久化。
- 最终 Debug APK SHA-256：`dd58890936ed02990ed208586f61072f27a8932b18fcdd9d86d204f878364df4`。覆盖安装后应用以冷启动进入 `com.longdev.xiaoling/.MainActivity`，进程存活，Provider 保留 1 条，crash buffer 为空。

## 2026-07-19 用户文档 MessagePart

实现与安全边界：

- Room v25 为 `message_parts` 增加可空 `documentExtractedText / documentPageCount / documentDetail`，Document 复用 `mimeType / fileName / binaryData`。v24→v25 只加列，不创建历史 Document；全新 v25 Schema、迁移链和磁盘数据库重开往返均有自动化保护。
- 系统附件菜单可选择图片或文档，单条 USER 消息最多携带一种附件。Document v1 支持 PDF、TXT、Markdown、JSON、CSV，文件最大 8 MB；PDF 复制到应用私有临时文件并用 `PdfRenderer` 验证，最多 50 页；文本严格按 UTF-8 解码，最多 200,000 字符并拒绝二进制空字符。
- 原始文件 BLOB 与受限提取文本/页数在同一事务写入 Room，不依赖长期 URI 权限。Image/Document BLOB 只为当前会话加载，轻量快照保留未加载附件；网络请求前等待用户消息和 BLOB 事务完成。
- Responses 将 USER Document 映射为 `input_text + input_file`，`filename` 保留清理后的文件名，`file_data` 使用 Data URL，PDF detail 为 `auto`。Chat Completions 明确拒绝附件；第 75 阶段起 `/agent` 仅在 Responses 规划请求接收 USER 单一文档，普通 assistant、Agent 结果、摘要和 `VerifiedAgentContext` 不能接收 Document 或把模型提取内容升级为工具事实。
- Compose 输入区附件菜单、文档名称/大小/页数或字符数、移除按钮和历史 Document 展示均已完成；请求日志继续递归脱敏 `file_data`、Authorization、图片 Data URL、原始/加密推理字段。
- 双轴代码审查补充关闭 PDF 错误 MIME 绕过：PDF 签名与扩展名由 `DocumentAttachmentPolicy` 统一判定，`.pdf` 被错报为 `text/plain` 时仍强制走 `PdfRenderer`，PDF 内容伪装为文本或与非 PDF 扩展名冲突时拒绝。待发送 Image/Document 的互斥、接口模式校验和稳定 part 顺序也已迁入 `MessageAttachmentSelection`，不再继续扩张 `sendMessage()` 的附件业务分支。

官方协议核验：

- 通过 OpenAI Developer Docs MCP 与本机 `smart-search fetch https://developers.openai.com/api/docs/guides/file-inputs` 双重核验。官方 Responses 示例使用 `type=input_file`、`filename`、`file_data`，Base64 Data URL 可直接作为文件输入；官方单文件上限为 50 MB，但本项目按移动端内存和上下文成本主动收紧为 8 MB。
- 官方说明 PDF 会把提取文本和页图一起加入上下文，detail 可为 `auto/low/high`；本项目 v1 固定 `auto`，并先执行 50 页本地预算。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 强制重跑通过，88 个 Gradle task 全部执行；281 条 JVM 测试，0 失败、0 错误、0 跳过。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 直接安装最终 Debug/Test APK，并运行完整 92 条 instrumentation，0 失败、0 跳过。新增覆盖有效/损坏/超限 UTF-8 文本、真实 PDF 解析和页数预算、v24→v25、Document/Text 磁盘重开、Image/Document BLOB 按会话加载与轻量快照保留，以及 Compose 文档元数据节点。
- 本阶段所有安装、instrumentation、DocumentsUI、UI tree、真实模型、数据库和日志命令均显式指定 Redmi 串号；在线模拟器未参与验证。

真实模型、UI、日志与数据库验收：

- 将 67 字节 `lingce-stage27.md` 放入 Redmi Downloads，通过附件菜单和系统 DocumentsUI 选择后，输入区显示 `lingce-stage27.md / 1 KB / 67 字符` 与移除按钮，模型/模式/发送控件无重叠。临时 Downloads 源文件已清理，消息仍能从 Room BLOB 恢复。
- Chat Completions 下首次发送被应用正确阻止并提示切换 Responses。切换 `gpt-5.5 + Responses` 后，真实请求在 4.33 秒返回精确 `DOC_STAGE27_OK`；历史消息气泡同时显示文档 MIME、大小和字符预算。
- `XiaoLingHttp` 日志确认 `file_data`、Authorization 均脱敏，默认 User-Agent 正确，最终响应文本保留。Redmi 主库只读回查为 `PRAGMA user_version=25`，Provider 1 条，最新 Document 行为 `text/markdown / lingce-stage27.md / 67 bytes / 67 chars / AUTO`。
- 最终 Debug APK SHA-256：`53c7fdb9641e3bdb3a06531b97078b4edce70606fa30da6315e072d26fd87572`。最终包冷启动到 `com.longdev.xiaoling/.MainActivity`，进程存活，crash buffer 为空。

## 2026-07-19 OpenXML 富文档直传

实现与安全边界：

- Document part 在 Room v25 原有列上增加 DOCX、PPTX、XLSX MIME，不新增表或迁移；原始 OpenXML 包继续作为 BLOB 随消息、备份和当前会话加载，轻量快照和 USER-only 信任边界不变。
- `OpenXmlDocumentPolicy` 解析 ZIP 中央目录并逐条核对 local header、文件名、加密位、磁盘号、ZIP64 extra 与数据范围，再以固定缓冲区流式解压核对条目集合、CRC 和真实展开量。DOCX/PPTX/XLSX 分别要求非空 `[Content_Types].xml` 与 `word/document.xml / ppt/presentation.xml / xl/workbook.xml`；加密、分卷、ZIP64、超过 4,096 条目、声明或实际展开总量超过 64 MB、扩展名/MIME/根入口不一致均拒绝。
- 系统文件选择器增加三种 OpenXML MIME；Responses 继续使用 `input_file + filename + file_data`；Chat Completions 仍拒绝附件，`/agent` 的 Responses-only USER 规划附件边界由第 75 阶段补齐。大于 8 MB 或需要跨文档检索的内容仍不进入直传；本阶段当时尚未实现 RAG，后续 Room v26 数据基础见下节。

官方协议与自动化：

- `smart-search fetch https://developers.openai.com/api/docs/guides/file-inputs --format markdown` 的官方正文确认 Responses 接受 DOCX、PPTX、XLSX，非 PDF 富文档只提取文本，电子表格另走 spreadsheet augmentation；官方建议大文件使用 File Search。本项目继续采用更保守的 8 MB 移动端预算。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 强制重跑通过，88 个 Gradle task 全部执行；284 条 JVM 测试，0 失败、0 错误、0 跳过。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 安装最终 Debug/Test APK，并运行完整 93 条正式 instrumentation，`OK (93 tests)`，0 失败、0 跳过；新增真机覆盖有效 DOCX 从 URI 读取、结构校验且不进入 UTF-8 文本路径。在线模拟器未参与。

真实模型与日志：

- 一次性、不提交的 Redmi instrumentation 使用设备现有 Provider 和 Keystore 配置，将结构完整的最小 DOCX 发送给 `gpt-5.5 + Responses`。最终实现复测耗时 4800 ms，精确返回 `RICH_DOC_STAGE28_OK`；一次性测试随后删除，不进入正式 Test APK。
- `XiaoLingHttp` 请求日志显示 `filename=lingce-stage28.docx`、`file_data=***REDACTED***`、Authorization=`***MASKED***`、默认 User-Agent 正确；响应中的 `encrypted_content` 与 reasoning context 同样脱敏，固定结果文本保留。
- 最终 Debug APK SHA-256：`8d6a60f84f1c1f8e1002a785ae96cd5b36c83dded86fb420cd615656f3a3f641`。最终包在 Redmi 冷启动进入 `com.longdev.xiaoling/.MainActivity`，进程存活，crash buffer 为空。

## 2026-07-19 本地知识库与 RAG 数据基础

实现与数据契约：

- Room v26 新增 `knowledge_documents / knowledge_chunks / knowledge_chunks_fts / knowledge_retrievals`，并导出正式 `26.json` Schema。v25→v26 只创建空知识库表，不从旧消息、附件或模型文本猜造知识文档。
- 第一版只导入 TXT、Markdown、JSON、CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符；移除 BOM、规范 CRLF/CR、拒绝空白和 `NUL`，对规范全文保存 SHA-256、字节数、字符数和 `parserVersion=1`。
- 确定性分块默认 1600 字符、200 字符重叠，优先段落边界并保存精确 `[startOffset, endOffset)`；没有段落边界时硬切，同时避免切断 UTF-16 代理对。chunk ID 包含文档 ID、revision、sequence 和内容哈希前缀。
- 检索合并 FTS4 `unicode61` 前缀结果与转义通配符的中文/字面 `LIKE` AND 兜底；每次检索，包括空命中，保存 query、实际 chunk/document ID、来源会话/Run 和时间。
- 文档替换在同一事务更新全文/hash/parser/revision、删除旧 chunks/FTS 并插入全新引用；SQLite trigger 故障注入确认新 chunk 插入失败时全文、revision、旧 chunks 和旧 FTS 一起回滚。禁用后保留数据但立即退出检索，删除同时清理 FTS、chunks 和文档。

自动化与 Redmi 真机验证：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug` 通过；XML 汇总为 291 条 JVM 测试，0 失败、0 错误、0 跳过。新增 7 条覆盖严格 UTF-8/规范 hash、空白/NUL 拒绝、段落/硬边界、代理对、FTS 查询转义和 `LIKE` 字面通配符。
- `assembleDebugAndroidTest` 通过。仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 安装 Debug/Test APK，并直接运行完整 instrumentation：`OK (98 tests)`。新增 5 条覆盖 v25→v26、真实磁盘关闭重开、检索审计、替换引用失效、禁用/删除、中文/通配符和事务回滚。
- `adb devices -l` 同时显示在线模拟器，但所有安装、instrumentation、应用启动、数据库和日志命令均显式指定 Redmi 串号；没有向模拟器发送验证命令。
- 最终应用冷启动成功，`com.longdev.xiaoling/.MainActivity` 保持 Redmi 前台，crash buffer 为空。主库只读回查为 `PRAGMA user_version=26`，知识主表/FTS 辅助表齐全，原 Provider 保留 1 条。
- 最终 Debug APK SHA-256：`4da1f1a27598fe0291e3c70d0ccbf526a7205dbea4426b876b31caf750d31dd0`。

该阶段当时的边界：

- 该阶段没有知识库管理 UI、`knowledge.search` Agent 工具、模型上下文注入、答案引用呈现或 Embedding，因此不宣称完整 RAG 问答已完成。管理 UI 和检索预览在下一节完成。

## 2026-07-19 知识库管理 UI 与检索预览

实现与状态边界：

- 设置页新增「知识库」入口和独立 `KnowledgeManagementViewModel`，支持 SAF 导入、刷新、列表、详情、启停、替换、删除和显式检索预览；没有继续扩张主 `XiaoLingViewModel`。
- `KnowledgeDocumentReader` 保留文件名、声明 MIME 和原始字节，并在 DocumentsProvider 隐瞒大小时逐块执行 64 MB 上限。列表使用 Room projection + chunk count，不读取规范全文；详情通过 SQL `substr` 读取有界前缀，再按最多 4,000 个 UTF-16 单元安全截断且不切断代理对，避免最大 64 MB 正文进入 Compose 状态。
- 文档变更全局串行；快速切换会取消旧详情和列表刷新 Job，替换、禁用和删除会立即隐藏旧详情、取消在途检索并清空旧 chunk/retrieval 引用。busy 状态保持到提交后的快照重载结束；存储提交与后续快照刷新使用独立错误边界，提交成功不会被刷新异常误报为操作失败。
- 检索预览显示命中数、retrieval ID、实际 query、document/chunk 数、revision、chunk sequence 和 `[startOffset, endOffset)`；0 命中也展示审计，明确区分“已执行无结果”和“尚未检索”。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；XML 汇总为 291 条 JVM 测试，0 失败、0 错误、0 跳过，Lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 安装最终 Debug/Test APK，并直接运行完整 instrumentation：`OK (106 tests)`，0 失败。新增 8 条覆盖未知长度 64 MB 有界读取、轻量列表/UTF-16 有界详情 projection、代理对边界、Compose 详情/检索审计、旧详情与刷新乱序、变更期间旧检索失效和提交后刷新错误语义。
- 在线模拟器虽然出现在 `adb devices -l`，但所有安装、instrumentation、DocumentsUI、UI 自动化、截图、数据库和日志命令均显式指定 Redmi 串号；没有向模拟器发送验证命令。

真实 UI、数据库与冷启动验收：

- Redmi 主应用数据库中的临时 `lingce-stage30.md` 先以 `Room` 检索命中 1 个 chunk，并显示 retrieval ID 与 `offset 0..1477`；停用后同词检索得到 `0 chunks / 0 documents`，重新启用后旧结果立即清空。
- 通过 DocumentsUI 替换为 `lingce-stage30-replacement.md` 后，详情更新为 `revision 2 / parser 1 / 1 个分块 / 172 B`。旧词 `Room` 无命中，新词 `REPLACEMENT_STAGE30_OK` 命中 revision 2 的 `offset 0..172`；数据库审计分别保留 r1、停用/替换后的空命中和 r2 引用。
- 通过删除确认清理全文、chunks 和 FTS 后，页面显示 `文档 0 / 还没有知识文档`，再次检索新词得到 0 命中审计。Downloads 中两个临时 Markdown 和 UI dump 均已清理。
- 主库只读回查为 `PRAGMA user_version=26`、Provider 1 条、`knowledge_documents / knowledge_chunks / knowledge_chunks_fts` 均为 0、`knowledge_retrievals` 为 5；历史 r1/r2 chunk ID 只保留在审计 JSON 中，没有活动索引行。
- 最终 Debug APK SHA-256：`925f9c9760f620b3f1b4b1708fcf44bb65739858626be01d660e752bbbfaef3a`。应用冷启动进入 `com.longdev.xiaoling/.MainActivity`，稳定主界面和知识库空状态均完成截图检查，无横向越界或控件重叠，进程保持前台且 crash buffer 为空。

该阶段当时的边界：

- 该阶段当时只完成管理 UI，`knowledge.search` 尚不是 Agent Tool，检索结果也未注入模型上下文或答案引用；这些能力已在后续第 31、32 阶段按 Registry、Profile/Skill 白名单和 Run/ToolResult 审计边界补齐。

## 2026-07-20 `knowledge.search` 与 Room v27 引用链

实现与安全边界：

- 新增 SAFE、后台可用的 `knowledge.search`，`query` 必填 1 至 200 字符，`limit` 默认 3、最大 5；内置 `local-knowledge` Skill 只授权该工具。Store 将 conversation ID、Run ID 写入检索审计。
- `KnowledgeReference` 固定保存 retrieval/document/name/revision/chunk/sequence/offset，并贯穿 ToolExecutionResult、RunEvent、独立 Tool Ledger、VerifiedAgentContext、MessagePart、规划历史和任务中心。Room v27 为 ToolResult 与 MessagePart 新增默认 `[]` 列，v26→v27 不猜造旧引用。
- 禁用、替换或删除后，历史 Run、Ledger 和消息审计不回写；新模型请求会核对当前 enabled/revision/chunk/name/sequence/offset。任一知识引用失效时整条历史 Agent 知识消息退出请求，旧会话摘要同时废弃并从过滤后的消息重建。
- 既有 Profile/Skill 不自动增加 `knowledge.search`。缺少 Profile 审计的历史 Run 使用知识工具上线前的固定工具集合，审批恢复后的后续规划也不能因当前 Registry 增长而扩权。
- `KnowledgeReferenceCodec` 对整段和单条畸形 JSON 容错；坏引用只退出可信证据，不阻断消息或 Run 加载。该阶段当时独立答案引用 UI 与 Embedding 仍未交付；答案引用 UI 已在后续第 32 阶段完成。
- Workflow 只在涉及知识检索时把输出保存为 `workflow-step-output-v1` 结构化快照；普通旧纯文本快照继续兼容。前台、后台和进程恢复均写入真实引用，准备下一步和关联 Agent Run 时重新核对完整引用集合；重试复制来源快照但不改写来源 Run，引用失效后前序正文不会进入新 Run。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 通过；309 条 JVM 测试，0 失败、0 错误，Lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 覆盖安装 Debug/Test APK，并运行完整 instrumentation：`OK (113 tests)`。ADB 同时可见模拟器，但没有向模拟器发送安装、启动、测试、日志或数据库命令。
- 新增真机覆盖 v26→v27、Run/Message 引用往返、Ledger/Event 漂移 fail-closed、禁用/替换/删除后的引用投影失效、Workflow 有效引用透传与重试失效过滤、畸形 JSON 容错，以及直接打包五份真实长期文档的 corpus；自然改写、多词分隔、`limit=1` 首位结果和确定性负例门禁均通过。

真实模型与最终设备状态：

- `Time Agent + gpt-5.5` 真实规划选择 `knowledge.search`，参数为 `query=STAGE31_KNOWLEDGE_TOKEN_7319, limit=5`；Run `run-76ddee5b-6a3f-4528-ba6c-d4c430706384` 与 retrieval `knowledge-retrieval-fb602ac2-c220-492f-8072-724d43475add` 返回唯一 token、Redmi 真机约束和 `revision=1 / chunk=0 / offset=0-190`。
- RunEvent、独立 Tool Ledger、检索审计和 MessagePart 引用完全一致。临时文档已从应用知识库删除，历史引用保留；当前主库知识文档/chunk/FTS 为 0。
- 最终 `v0.1.10` Debug APK SHA-256：`8af1430c75794825dab6d2a1365af0341c22d57965c988d7d0ea4bc28d334d63`。AndroidTest APK 会把五份持续维护的 `docs/` 文档作为真实 corpus 打包，因此不在文档自身记录递归变化的 Test APK 哈希。最终应用在 Redmi 冷启动进入 `com.longdev.xiaoling/.MainActivity`，进程存活，crash buffer 为空。

## 2026-07-20 小灵 v0.1.10 发布

发布构建与产物：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug assembleRelease --rerun-tasks --stacktrace --console=plain` 通过，Release `lintVital` 同时通过。
- `aapt dump badging` 确认包名 `com.longdev.xiaoling`、`versionName=0.1.10`、`versionCode=11`、`minSdk=26`、`targetSdk=36`。
- `apksigner verify --verbose --print-certs` 确认 v2 签名有效，单一签名者证书 SHA-256 仍为 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9`。
- 发布 APK：`outputs/release/xiaoling-v0.1.10.apk`，SHA-256：`a7e71b2a2582152de5c954f4e124e6f02f15a86df7d8537b6502d2f9187f726d`；同目录生成 `.sha256` 校验文件。

Redmi 最终状态：

- instrumentation 后使用最终 Debug APK 从 `0.1.9` 无损覆盖到 `0.1.10`，没有卸载或清数据；`dumpsys package` 确认 `versionName=0.1.10 / versionCode=11`。
- `com.longdev.xiaoling/.MainActivity` 为 `topResumedActivity`，进程存活，清空后重新采集的 crash buffer 为空。
- 设备当前安装的是 debug 签名包；没有为切换正式签名而卸载应用。Release APK 已完成本地签名、元数据与哈希验证，避免因发布验收清除手机上的 Provider、会话和 Keystore 数据。

GitHub Release：

- Release：[小灵 v0.1.10](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.10)
- APK：[xiaoling-v0.1.10.apk](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.10/xiaoling-v0.1.10.apk)
- SHA-256：[xiaoling-v0.1.10.apk.sha256](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.10/xiaoling-v0.1.10.apk.sha256)

## 2026-07-20 答案级知识引用 UI

实现与可信边界：

- Agent 回复新增独立、默认折叠的「知识引用」区域，展开后展示文档名、revision、chunk sequence 和 `[startOffset, endOffset)`；多条引用按结构化身份去重并保持消息内顺序。
- 引用只能从 `MessagePart.Tool` / `VerifiedAgentContext` 的可信投影读取。普通助手正文即使包含 document、revision、chunk 或 offset 字样，也不会生成引用 UI。
- `KnowledgeReferenceStatus` 将引用分为 `CURRENT / HISTORICAL / UNAVAILABLE`。精确匹配当前启用文档和 chunk 时显示「当前有效」；启用文档 revision 已增加时显示「历史版本」及当前文档名/revision；停用优先显示「当前不可用」，删除、名称或 chunk 边界漂移同样不可用。
- Room 使用仅包含文档元数据的 summary projection 和引用涉及的 chunk 查询完成状态核验，不读取最大 64 MB 规范全文；文档与 chunk ID 均按最多 900 个参数分批，1005 条不同引用的真机回归不会超过 SQLite 绑定上限。文档仍存在时整条引用可跳转知识库详情；已删除时关闭跳转，避免把当前文档或不存在的内容伪装成旧 revision 原文。
- 对话页每次重新可见时重新核验当前会话引用，知识库停用、替换或删除后不会短暂沿用离开页面前的「当前有效」标签。切换会话或触发新核验会取消旧 Job，`CancellationException` 继续向上抛出，旧任务不会把新状态覆盖为「暂无法核验」。状态只进入瞬时 UI state，不改写历史 MessagePart、RunEvent、Tool Ledger 或检索审计。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false --console=plain` 通过；320 条 JVM 测试、0 失败、0 错误、0 跳过，Lint、Debug APK 和 AndroidTest APK 均构建成功。
- 手动安装两个 APK 后，仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 运行完整 instrumentation：`OK (118 tests)`，0 失败、0 跳过；没有启动或操作模拟器。
- 单元测试覆盖当前、替换、替换后停用、删除和 chunk 边界漂移状态，以及普通模型正文不能伪造引用。Room instrumentation 真实执行导入、停用、替换、删除和 1005 条不同引用的分批核验；Compose 测试覆盖默认折叠、半开 offset 元数据、历史标签与引用行点击身份；知识库 ViewModel 测试覆盖指定文档导航。
- 真实 `MainActivity` E2E 通过正式 Repository 写入临时 Agent 知识回答：先验证「当前有效」引用展开并跳转 revision 1 详情，再替换为 revision 2 并回到对话确认「历史版本」，随后从旧引用跳转当前文档；删除文档后确认「当前不可用 / 文档已删除」且跳转入口消失。临时会话和文档在测试结束后自动清理。

最终设备状态：

- 全量 instrumentation 会重装测试目标，因此使用不提交的一次性 instrumentation 设置器，通过正式 `ProviderRepository` 和 Android Keystore 链恢复项目 `AGENTS.md` 中的兜底 Provider；设置器源码随后删除，凭据未进入 Git、文档或最终 AndroidTest APK。
- Redmi 主库只读核对为 1 个 Provider、模型 `gpt-5.4-mini`、非空 Base URL 和非空 API Key 密文。应用真实普通对话返回固定 token `FINAL_MODEL_OK_20260720`，证明 Keystore 解密、模型请求和 UI 回显可用；临时会话随后删除。
- 最终 Debug APK SHA-256：`2943f0a2fdf1c5aff5ff03d029de24c0ebc7423c02be7e3454bd7d6fd8d035ac`。`versionName=0.1.10 / versionCode=11`，应用在 Redmi 的 `MainActivity` 前台保持 `RESUMED`，crash buffer 为空。

该阶段当时的下一阶段边界：

- 进入设备 Agent 只读观察层：Accessibility 授权说明、服务健康检查、结构化 snapshot、短生命周期节点引用和隐私过滤。
- 该阶段不加入 `tap_ref / type_text / swipe` 等动作，不接入 Workflow 或后台自动化。只读层完成 Redmi 验收后，再实现 `open_app / back / home / tap_ref / type_text / swipe`、风险审批和操作后重新观察验证，并只对少量指定 App 做端到端验收；通用执行恢复和长任务可靠性完成前，设备工具不得进入 Workflow 或后台自动化。

## 2026-07-20 设备 Agent 只读观察层

实现与安全边界：

- 新增默认关闭的设备 Agent 独立开关、系统 Accessibility 设置入口和四态健康检查：`AGENT_DISABLED / ACCESSIBILITY_NOT_AUTHORIZED / SERVICE_DISCONNECTED / READY`。关闭开关立即清除 ref，系统授权不会自动打开应用能力。
- `XiaoLingAccessibilityService` 非导出，只允许系统通过 `BIND_ACCESSIBILITY_SERVICE` 绑定；配置明确 `canRetrieveWindowContent=true`、`canPerformGestures=false`、`canTakeScreenshot=false`、`isAccessibilityTool=false`。当前没有点击、输入、滑动、截图或坐标兜底。
- `device.snapshot` 为 SAFE、非后台 Tool，内置 `device-observation` Skill 只引用该工具。工具只有在前台直接 `/agent`、应用独立开关开启且 Profile/Skill 允许时进入规划器工具清单；Workflow、后台、关闭状态和缺少 Run Context 均在 Executor 再次拒绝。既有 Profile/Skill 不自动扩权。
- 快照最多 200 个可见有效节点、4,000 个文本字符，UTF-16 文本预算不切断代理对；可操作、启用且未脱敏的节点获得 30 秒 ref。ref 绑定 snapshot ID、窗口 generation、节点路径和指纹，新快照替换旧快照，页面变化、过期、失败、隐私拦截或关闭开关后立即失效，不回退坐标。
- 密码/密码提示、验证码、API Key、Bearer/Access Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref；常见空格/连字符格式同样识别。支付/收银台/高敏身份验证窗口以及已知密码管理器、Authenticator、钱包/银行包名整窗拒绝。
- `app/src/debug` 提供仅 Debug 包存在的诊断广播和隐私探针；Release manifest 不包含这些入口。诊断日志只记录 request ID、包名、节点/ref/脱敏计数、截断状态或稳定失败原因，不记录节点正文。

自动化与构建：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false --console=plain` 通过；337 条 JVM 测试、0 失败、0 错误，Lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅连接 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`，运行完整 instrumentation：`OK (122 tests)`，0 失败、0 跳过；没有启动或操作模拟器。
- JVM 覆盖健康状态、节点/文本预算、UTF-16 边界、密码/验证码/Token/格式化手机号与银行卡脱敏、隐私应用/支付窗口拒绝、禁用节点无 ref、ref 到期/页面变化/失败撤销，以及 `device.snapshot` 的前台直接、Workflow、后台和关闭状态双层门禁。
- Instrumentation 覆盖 Service manifest 与读取/手势/截图能力声明、独立开关默认关闭和持久化、设置 UI 的关闭/READY 状态、只读按钮与脱敏预览；动作控件明确不存在。

Redmi 真实 AccessibilityService 验收：

- 该 Redmi ROM 会在 instrumentation 生命周期结束后清空无障碍授权，因此真实 Service E2E 不放入 instrumentation；测试结束后恢复用户已授权的服务，再使用 Debug-only 诊断广播验收。`dumpsys accessibility` 显示 `Bound services` 与 `Enabled services` 均包含 `XiaoLingAccessibilityService`，capabilities 为读取窗口内容且没有手势/截图能力。
- 小灵主界面：`success=true package=com.longdev.xiaoling nodes=27 refs=8 redacted=0 truncated=false`。
- 敏感字段探针：`success=true nodes=3 refs=1 redacted=2 truncated=false`，API Key 样式文本与密码字段均没有正文/ref。
- 支付窗口探针：`success=false reason=SENSITIVE_WINDOW`，未返回节点内容。
- 全量 instrumentation 后重新安装当前 Debug APK并恢复系统授权；首次安装没有设备开关偏好文件，代码与 instrumentation 均确认默认值为 `false`。最终 `MainActivity` 为 `topResumedActivity`，服务已绑定，crash buffer 为空。

该阶段当时的下一阶段边界：

- 先实现 `open_app / back / home`，再实现 `tap_ref / type_text / swipe`；每个动作同步完成风险/隐私分级、必要审批、ref/generation 再校验、动作后重新 snapshot 和业务结果验证。
- 第一批只对少量明确指定 App 做 Redmi 前台端到端验收，暂不承诺任意 App。通用执行恢复和长任务可靠性完成前，设备工具不进入 Workflow 或后台自动化；精确定时与 Foreground Service 继续依据真实耗时决定。

## 2026-07-20 设备 Agent 有限动作与 Redmi E2E

实现与安全边界：

- 新增 `device.open_app / back / home / tap_ref / type_text / swipe` 和内置 `device-control` Skill。`open_app / tap_ref / type_text` 为 `REQUIRES_APPROVAL`，返回、主页与节点滚动为 SAFE；全部动作使用 `EXECUTOR_VERIFIED`，没有坐标或截图兜底。
- `open_app` 只允许小灵、系统计算器、时钟和系统设置，manifest 只声明对应 package queries，不申请 `QUERY_ALL_PACKAGES`。输入最多 500 字符，空白、控制字符、密码/验证码提示、API Key、Bearer Token、手机号、身份证、银行卡和邮箱在 Tool 参数审计前拒绝。
- 节点动作执行前重新核对 snapshot ID、30 秒 ref、窗口 generation、节点路径、指纹和节点动作集合；任一漂移均 fail-closed。动作后重新 snapshot：打开应用核对前台包名，主页核对 Launcher，输入回读正文，返回/点击/滚动要求观察到窗口 generation 变化；证据不足返回 `verified=false`。
- 首次启动应用或系统权限页切换可能短暂没有 `rootInActiveWindow`。后置观察只对 `NO_ACTIVE_WINDOW / WINDOW_CHANGED` 做最多 6 次、每次 100 ms 的有界重试；隐私拒绝、授权失效和服务断连不重试。该策略来自 Redmi 时钟首次启动权限页的真实失败，不扩大 ref 或隐私边界。
- 全部设备工具继续只对前台直接 `/agent` 暴露；Workflow、后台、开关关闭和缺少 Run Context 时从规划器工具面移除并由 Executor 再次拒绝。旧 Profile/Skill 不自动扩权，设备工具仍不进入 Workflow 或后台自动化。

自动化、构建与 Redmi 回归：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks -Pkotlin.incremental=false --stacktrace --console=plain` 全量执行通过；348 条 JVM 测试、0 失败、0 错误，Lint、Debug APK 和 AndroidTest APK 均构建成功。
- 仅在 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 安装两个 APK并运行完整 instrumentation：`OK (123 tests)`，0 失败、0 跳过；没有启动、连接或操作模拟器。
- JVM 覆盖动作风险/Schema、前台/Workflow/后台双层门禁、应用白名单、敏感输入、ref/generation/path/fingerprint、动作不支持、后置验证失败、无可观察变化和瞬时空窗口重试。Instrumentation 覆盖标准节点动作所需 manifest、无坐标手势/截图能力、首批 package queries、默认关闭偏好和设置 UI。

Redmi 真实动作验收：

- 只读层最终复验：小灵主界面 `success=true package=com.longdev.xiaoling nodes=30 refs=12 redacted=0 truncated=false`，系统服务处于 Enabled 与 Bound 状态。
- 计算器：`open_app` 返回 `verified=true / package=com.android.calculator2 / nodes=35 / refs=33`；按 snapshot 中目标 `7` 执行 `tap_ref` 后返回 `verified=true / nodes=36 / refs=33`，后置快照回读到结果文本 `7`。
- 系统导航：从计算器 `back` 后重新观察到小灵并验证通过；`home` 后重新观察到 `com.android.launcher3 / nodes=21 / refs=20` 并验证通过。
- 设置：`open_app` 返回 `verified=true / nodes=29 / refs=11`；列表 `swipe(up)` 返回 `verified=true`；按子节点文字「搜索设置」解析父级 ref 并点击后进入 `com.android.settings.intelligence`，向搜索框输入 `stage34_verified` 后回读相同文本并验证通过。再次尝试输入虚构 `sk-` 样式值时返回 `SENSITIVE_INPUT`，没有执行 Accessibility 输入动作。
- 时钟：首次启动出现系统权限 Activity，暴露一次瞬时 `NO_ACTIVE_WINDOW`；安全返回后观察到 `com.android.deskclock / nodes=21 / refs=13`，再次 `open_app` 验证通过。随后补入后置观察有界重试及确定性回归测试。

真实模型、审批与 Tool Ledger：

- Debug-only E2E Profile 只用于真机验收并固定项目兜底模型。首次用 Chat Completions 验证时，模型成功规划 `device.open_app`、进入 `WAITING_APPROVAL`、获得 `APPROVED` 并完成工具执行与验证，但工具成功后的第二次规划请求在 60 秒超时，Run `run-6419aeca-d4ae-4839-8d7c-e6f2e09ab2cf` 诚实收敛为 `BUDGET_EXHAUSTED`；该结果不计为完整闭环通过。
- 切换为该 Provider 已稳定验收的 Responses 模式后，Run `run-13bcfa28-346f-4a71-b98b-5b44cf28bd92` 完成模型规划、`device.open_app` 待审批、用户从前台审批卡片批准、计算器真实启动、动作后 snapshot、Tool Ledger 和最终总结。最终为 `status=COMPLETED / approval=APPROVED / tool=device.open_app / success=true / executorVerified=true / verification=PASSED`。
- 最终 Debug APK SHA-256 为 `f04bb1658d37680585bcfd2913b4d1a4794613dd0f597ba549ad94014b368877`，已覆盖安装到 Redmi；`versionName=0.1.10 / versionCode=11`，小灵 `MainActivity` 位于前台，无障碍服务 Enabled/Bound，最终快照为 `nodes=32 / refs=14 / redacted=1 / truncated=false`，crash buffer 为空。验收 Profile 保持设备 Agent 开启，便于用户直接查看；Release manifest 不包含三个 Debug Receiver 或隐私探针。

该阶段当时的下一阶段边界：

- 后续“执行任意工具后的审批等待”已在下一节完成；旧模型协程、执行/验证中的通用工具执行栈和更长真实任务的进程回收/重试可靠性仍继续推进。完成前设备工具继续禁止进入 Workflow 或后台自动化。
- 当前 31 秒后台 Workflow 仍没有引入 Foreground Service 的依据；精确定时继续后置。MCP、日历/通知、远程 Channel、多 Agent 和本地模型保持最后推进。

## 2026-07-20 多步骤审批等待恢复

实现与证据边界：

- `AgentRunResumePolicy` 现支持首步以及第二次、后续工具审批的原 Run 恢复，但只接受一个 `PENDING` Approval 与最后一个已校验、尚无 ToolResult 的 ToolCall 在 ID、工具名、参数和风险上完全一致。
- 所有前序 ToolCall 必须有成功 ToolResult 和 `PASSED` 验证，`tool.execute / tool.verify / approval` Step 数量、状态和链尾位置必须一致。v20 新 Run 以独立 Tool Ledger 为事实源并用 typed RunEvent 核对；账本异常直接拒绝，账本完全为空的旧 Run 才按严格事件顺序回退。
- `resumeApprovedRun()` 从持久化证据重建 `completedTools`、已执行工具调用数和调用指纹。批准后只执行当前链尾工具，不重放前序工具；后续规划与最终 `VerifiedAgentContext` 同时包含恢复前和恢复后的可信工具结果。工具预算与重复调用检测不会因进程重建清零。
- 当前切片不恢复旧模型协程，也不放宽执行/验证中断边界。非白名单工具已经产生结果后仍安全收敛并创建关联新 Run；`notes.create / memory.remember` 保持现有已提交结果只读验证例外。

自动化与 Redmi 验证：

- `./gradlew testDebugUnitTest` 全量通过：354 条 JVM 测试，0 失败、0 错误。新增覆盖第二次审批恢复不重跑第一工具、最终可信上下文包含两步、调用预算与重复调用检测不重置，以及 Approval 漂移、前序未验证和账本异常 fail-closed。
- `./gradlew assembleDebugAndroidTest` 通过。
- 仅连接并使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`。定向运行 `RoomAgentRunRepositoryInstrumentedTest#secondApprovalAndVerifiedPrefixSurviveDiskRoomReopen` 通过；测试真实关闭并重开磁盘 Room，确认原 Run ID、第一步已验证前缀、第二次审批、链尾 ToolCall 和审批 Step 完整恢复，`closeInterruptedRuns()` 不会误取消。
- `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest` 完整回归通过：124 条 instrumentation，0 失败、0 错误、0 跳过。没有启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 结束后测试框架移除了目标应用；已重新安装当前 Debug APK并冷启动。随后使用不提交的一次性 instrumentation 设置器按项目 `AGENTS.md` 恢复兜底 Provider，真实 `/models` 请求返回非空列表，Repository 再读取确认模型为 `gpt-5.5`、Base URL/API Key 非空且 Keystore 解密成功；设置器源码与测试 APK随后删除，凭据未进入 Git 或长期文档。
- Redmi 最终设备 Agent 应用开关为开启，系统 AccessibilityService 同时处于 Enabled 与 Bound，`Crashed services` 为空；`MainActivity` 为 `topResumedActivity`，crash buffer 没有本应用崩溃。

该阶段当时的下一阶段边界：

- 继续处理工具已经进入执行/验证后的通用恢复与更长任务进程回收可靠性，优先区分“尚未提交”“提交状态未知”“已提交可只读核验”三类边界。
- 设备工具继续不进入 Workflow 或后台自动化；Foreground Service 与精确定时仍依据更长真实任务耗时和系统回收证据决定。

## 2026-07-21 已验证工具结果恢复

实现与安全边界：

- `AgentRunResumePolicy` 新增 `VERIFIED_TOOL_COMPLETION`。仅 `VERIFYING`、没有待审批请求、所有 ToolResult 成功、所有 `tool.verify` 为 `PASSED`、执行/验证 Step 数量与状态一一对应、最后验证 Step 为 `RUNNING / COMPLETED` 且其后没有新 Step 时允许原 Run 收尾；Ledger、typed RunEvent 与原 Agent Profile 任一缺失或漂移都 `RESTART_REQUIRED`。
- `resumeVerifiedToolRun()` 从持久化证据重建 `completedTools`、已执行工具调用数和调用指纹。最后验证 Step 仍为 `RUNNING` 时只补为 `COMPLETED`，随后复用本地可信总结完成原 Run；不调用 Executor 或 LLM，不追加第二条 ToolResult/`tool.verify`，也不恢复旧模型协程。
- Workflow 启动对账会保留该候选，恢复后只写回当前步骤输出；如仍有后续步骤，旧 Workflow 收敛为 `FAILED` 并要求创建关联新 Run，不把局部验证终态扩大成后台执行栈续跑。提交状态未知、验证事实不完整和其他执行中断继续 fail-closed。

自动化与 Redmi 验证：

- 两个确定性故障注入分别在 `tool.verify=PASSED` 落库后、验证 Step 更新为 `COMPLETED` 后终止进程。恢复后 Executor 仍只执行 1 次，ToolResult 和 `tool.verify` 各只有 1 条，原 Run 进入 `COMPLETED`，`RECOVERY_SUMMARY` 与完整 `VerifiedAgentContext` 均已生成。
- `./gradlew testDebugUnitTest` 全量通过：358 条 JVM 测试，0 失败、0 错误；`lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 均通过。
- 仅连接并使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`。定向磁盘 Room 测试 `processRestartCompletesFullyVerifiedRunWithoutAppendingDuplicateToolFacts` 通过；完整 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest` 回归通过：125 条 instrumentation，0 失败、0 错误、0 跳过。先前因设备休眠出现的 Compose 层级缺失，在唤醒解锁后隔离复跑 2/2 通过；没有启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后重新安装当前 Debug APK。使用不提交的一次性 instrumentation 设置器通过正式 `ProviderRepository` 与 Android Keystore 恢复兜底 Provider，进程退出后再次从 Room/Keystore 读取并真实请求 `/models`，1.149 秒返回非空列表；设置器源码已删除、测试包已卸载，最终 AndroidTest APK 已重建，凭据未进入 Git、文档或标准测试产物。
- Redmi 最终 `device_agent_enabled=true`，默认 User-Agent 已持久化；AccessibilityService 处于 Enabled 与 Bound，`accessibility_enabled=1`，`Crashed services:{}`。`MainActivity` 为 `topResumedActivity`，应用进程存在，crash buffer 为空。

该阶段当时的下一阶段边界：

- 为 `AgentExecutionBudget` 引入可注入单调时钟，覆盖多模型/工具段累计预算、Step timeout 与 Run timeout 的精确边界，并继续记录更长真实任务的耗时与进程回收证据。
- 提交状态未知、验证事实不完整、旧模型协程和 Workflow 后续步骤仍不原地恢复；设备工具继续不进入 Workflow 或后台自动化。Foreground Service 与精确定时保持证据驱动，不预先引入。

## 2026-07-21 单调累计执行预算与恢复边界

实现与安全边界：

- `AgentExecutionBudget` 使用可注入 `MonotonicClock`，生产实现读取 `System.nanoTime()`；规划、工具与总结执行段共享同一累计预算，工具 duration 使用相同时钟。系统时间校准不会制造负耗时或返还预算，审批等待不进入累计区间。
- 每个新 Run 先写入 `run.execution_budget.updated = 0 / total`，每个成功模型或工具段后更新 typed 快照。审批恢复、已提交结果恢复和已验证结果恢复读取最后快照，并以原 total/consumed 构造预算；升级前旧 Run 先写零值兼容起点，后续再次中断不再清零。缺结构化 metadata、首条非零、数值越界、同 Run 总额漂移、累计回退，或最后 ToolResult 晚于最后预算快照均由 `AgentExecutionBudgetEvidencePolicy` fail-closed，并使恢复策略要求新 Run；最后一条门禁覆盖结果已提交、预算事件尚未落库时的进程终止窗口。
- 当剩余 Run 预算小于或等于 Step 上限时，超时固定归因 `Agent Run`；只有 Step 上限严格更小时才归因具体 Step。调用方外层 `withTimeout` 或主动取消不转换成预算耗尽，Run 与活动 Step 按 `CANCELLED` 收敛。
- 任务中心为预算事件展示已消耗、总预算和剩余时间；Room 继续复用 RunEvent 的 typed metadata 列，无需 Schema migration。

自动化与 Redmi 验证：

- `AgentExecutionBudgetTest` 覆盖 100ms 内多个模型/工具段累计、Step timeout 优先和 remaining 等于 Step 上限时 Run timeout；`MultiStepAgentRuntimeTest` 证明两次规划各 20ms、两次工具各 30ms 正好耗尽总预算，第三次规划与总结均不会调用。
- 审批测试证明 2000ms 用户等待不消耗 500ms 执行预算；恢复测试从持久化 `80 / 100ms` 继续，第二工具只获得剩余 20ms，前序工具不重放。证据策略、codec、任务中心展示、旧 Run 起点和调用方外部超时另有确定性覆盖。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：374 条 JVM，0 失败、0 错误；lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain`：125 条 instrumentation，0 失败、0 错误、0 跳过；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后已重新安装 Debug APK，并通过正式 Repository/Keystore 恢复项目兜底 Provider、默认 User-Agent 与设备 Agent 开关；真实 `/models` 返回非空列表。AccessibilityService 最终处于 Enabled 与 Bound，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空；临时设置器及测试包已清理，凭据未进入 Git、文档或标准测试产物。

该阶段当时的下一阶段边界：

- 继续记录更长真实任务的总耗时、系统回收点和恢复证据，优先完善提交状态未知或验证事实不完整时的通用执行恢复策略。
- 旧模型协程和 Workflow 后续步骤仍不原地恢复；设备工具继续不进入 Workflow 或后台自动化。精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 2026-07-21 重试副作用证据分类

实现与安全边界：

- `AgentTaskRetryPolicy.assessEvidence()` 统一读取非空 Tool Ledger；账本为空时严格回退旧 typed RunEvent；账本损坏、锚点漂移或双源不一致输出 `EVIDENCE_INCOMPLETE`，不会退回更宽松的旧事件猜测。
- 重试证据分类固定为 `NO_SIDE_EFFECT`、`NOT_COMMITTED`、`COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED`、`COMMITTED_VERIFIED` 和 `EVIDENCE_INCOMPLETE`。明确 `NOT_COMMITTED` 或无高风险副作用证据可以直接创建关联新 Run；提交未知、已提交或证据不完整必须确认。任何分类都不允许恢复旧模型协程、调用旧 Executor 或自动重放工具。
- 任务中心卡片显示分类码与中文解释；高风险确认弹窗显示具体证据、建议动作和“旧 Run 保持不变”边界。确认按钮提交时重新读取当前 Run：状态不可重试时关闭弹窗，证据码变化时更新弹窗并停止本次旧确认，只有当前证据码与弹窗一致且 Run 仍可重试才继续。

自动化验证：

- JVM 覆盖 SAFE 与非 SAFE、明确 `NOT_COMMITTED`、`UNKNOWN`、`COMMITTED` 未验证、账本漂移、执行中断、确认前证据码变化和 UI 文案映射；现有重试布尔门禁保持通过。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：381 条 JVM，0 失败、0 错误；lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7` 执行完整 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain`：125 条 instrumentation，0 失败、0 错误、0 跳过；未启动、连接或操作 Pixel 模拟器。

该阶段当时的下一阶段边界：

- 继续以确定性故障注入验证更长任务在步骤落库、系统回收和 Worker 重入时的对账；仍不使用 `Result.retry` 复制可能已执行的 Agent Run，也不引入 Foreground Service。

## 2026-07-21 Workflow 步骤落库后进程终止对账

实现与安全边界：

- `ScheduledWorkflowOrchestrator` 在 `completeWorkflowStep()` 成功返回后、更新内存步骤列表和启动下一步骤前调用专用 `ScheduledWorkflowFaultInjector`；生产默认实现为 no-op。
- 测试用进程终止异常会直接重新抛出，不进入普通 `FAILED/CANCELLED` 结算，也不调用结果通知。旧 Workflow 保留第一步已持久化输出、后续步骤未启动的中间 Ledger，交由应用下次启动对账。
- `reconcileInterruptedRuns()` 将旧 Workflow 收敛为 `FAILED`，不自动继续旧模型协程或后续步骤；用户调用 `retryRun()` 创建关联新 Run，连续成功前缀标为 `SKIPPED` 并设置 `reusedFromStepId`，首个未完成步骤保持 `PENDING`。WorkManager 仍返回业务已记账语义，不使用 `Result.retry` 复制可能执行过的 Agent Run。

自动化验证：

- `ScheduledWorkflowOrchestratorTest` 新增确定性终止场景：第一步 Agent 只启动一次，结果持久化后终止，第二步未启动，且未调用 settle/notify；旧 Workflow 不在该测试中自动续跑。
- Redmi 定向执行 `RoomWorkflowRepositoryInstrumentedTest`：`OK (13 tests)`。新增断言确认对账前 Workflow Run 为 `RUNNING`、第一步为 `COMPLETED`、第二步为 `PENDING`；对账后旧 Run 为 `FAILED`，重试新 Run 的第一步为 `SKIPPED` 且引用来源步骤，第二步为 `PENDING`。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：382 条 JVM，0 失败、0 错误；lint、Debug APK 与 AndroidTest APK 均构建成功。
- 文档同步后重新构建 AndroidTest，并仅在 Redmi `wsvwypiz7xwslvl7` 手动安装 Debug/AndroidTest APK、执行完整 `AndroidJUnitRunner`：`OK (125 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后卸载测试包并覆盖安装、启动当前 Debug APK。主库 Provider 仍为 `gpt-5.5`，Base URL、启用模型和 Keystore 密文均存在；默认 User-Agent 与设备 Agent 开关保持正确。测试清除的系统 Accessibility 授权已恢复，服务最终处于 Enabled 与 Bound，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空。

该阶段当时的下一阶段边界：

- 继续采集更长真实任务的总耗时和系统回收位置，完善提交状态未知或验证事实不完整时的通用执行恢复；仍不原地恢复旧模型协程或 Workflow 后续步骤，不提前引入 Foreground Service、精确定时或设备 Workflow 权限。

## 2026-07-21 通用重试证据可见性

实现与安全边界：

- 任务中心卡片直接显示 `AgentTaskRetryEvidencePresentation` 的分类码、稳定原因和建议动作，不再只显示可能被截断的 `label · code` 单行摘要。
- 卡片与确认弹窗继续读取同一 `AgentTaskRetryPolicy.assessEvidence()` 结果；本阶段没有放宽 `COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED` 或 `EVIDENCE_INCOMPLETE` 的确认要求，也没有恢复旧模型协程、旧 Executor 或旧 Workflow。

自动化验证：

- `AgentTaskRetryEvidencePresentationTest` 新增全枚举覆盖，确认六类证据均有非空原因和下一动作；定向 JVM 测试通过。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：383 条 JVM，0 失败、0 错误；lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 手动安装 Debug/AndroidTest APK 并执行完整 `AndroidJUnitRunner`：`OK (125 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后已卸载测试包、覆盖安装并启动 Debug APK。Provider 仍为 `gpt-5.5`，Base URL 与 Keystore 密文存在；默认 User-Agent 和设备 Agent 开关保持正确，AccessibilityService 最终处于 Enabled 与 Bound，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空。
- Redmi 手动 UI 核验已进入「设置 → Agent 任务中心」，确认页面标题、全部/处理中/可重试/已完成筛选和空状态无重叠；当前设备没有可重试 Run，因此本轮未伪造具体证据卡片的截图或文案样本。

该阶段当时的下一阶段边界：

- 继续以确定性故障注入完善提交状态未知、验证事实不完整时的通用执行恢复证据；设备工具、精确定时和 Foreground Service 仍保持现有边界。

## 2026-07-21 启动恢复证据快照

实现与安全边界：

- `RunEventMetadata.Recovery` 新增可空 `retryEvidenceCode`，只写入 metadata JSON，不改变 Room 表结构；旧事件缺字段继续按空值兼容，存在但未来未知的枚举值保守归类为 `EVIDENCE_INCOMPLETE`。
- `closeInterruptedRuns()` 在修改步骤和审批状态前，根据原始 Run 状态与 Ledger 计算证据码。`EXECUTING/VERIFYING` 无结果为 `COMMIT_UNKNOWN`，无副作用的 THINKING 中断为 `NOT_COMMITTED`，Ledger 异常为 `EVIDENCE_INCOMPLETE`；可原地恢复的 Run 不写取消证据。
- `AgentTaskRetryPolicy` 仍实时核对当前 Ledger；带快照的 Run 使用快照还原收敛前中断边界，启动清理产生的 `PENDING -> CANCELLED` 不会被当成副作用中断，当前 Ledger 真正漂移时升级为 `EVIDENCE_INCOMPLETE`。任务中心事件明细会展示持久化重试证据码。

自动化验证：

- JVM 定向覆盖 Recovery metadata 全字段 round-trip、旧事件缺字段兼容、未知枚举 fail-closed、事件展示、持久化分类一致、`PENDING -> CANCELLED` 清理回归和漂移 fail-closed。
- Redmi 定向执行 `RoomAgentRunRepositoryInstrumentedTest`：`OK (26 tests)`；确认 THINKING=`NOT_COMMITTED`、EXECUTING/VERIFYING=`COMMIT_UNKNOWN`、审批原地恢复事件不带取消证据。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：388 条 JVM，0 失败、0 错误；88 个 Gradle task 全部执行，lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 手动安装 Debug/AndroidTest APK 并执行完整 `AndroidJUnitRunner`：`OK (125 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后已卸载测试包并重新覆盖安装、启动 Debug APK。Provider 为 `gpt-5.5`，Base URL、可用模型和 Keystore 解密后的 API Key 均存在；默认 User-Agent 与设备 Agent 开关正确。AccessibilityService 最终处于 Enabled 与 Bound，设备页显示“服务正常，可读取当前界面”，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空。

该阶段当时的下一阶段边界：

- 继续完善验证事实不完整时的通用恢复与更长任务回收证据；不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。

## 2026-07-21 Worker 冷启动重入收敛

实现与安全边界：

- 新增 `ScheduledWorkflowReentryCoordinator`。Worker 进入 `execute(taskId)` 时，只有当前 ScheduledTask 已是 `RUNNING` 才进入重入路径；沿 Task→WorkflowRun→AgentRun 关联链按 ID 定向收敛，Agent、Workflow、ScheduledTask 依次完成对账后再发送通知。
- 普通 `SCHEDULED` 任务不改变原有 claim、顺序执行和周期调度；重入不恢复旧模型协程、不继续 Workflow 后续步骤、不调用 `Result.retry`，周期下一实例只在旧任务进入终态后物化。按 ID 的 Repository 对账入口保证其他前台 Agent/Workflow 不受影响。

自动化验证：

- JVM 新增 `ScheduledWorkflowReentryCoordinatorTest`，覆盖普通 SCHEDULED 直通、关联 Agent 重入顺序和无关联 Run 的定向 Task 收敛；既有 `ScheduledWorkflowOrchestratorTest` 全部通过。
- Redmi 定向 Room 测试 `workerReentryClosesOnlyLinkedAgentAndScheduledTaskWithoutCreatingNewRun`：`OK (1 test)`；关联 Agent 进入 `CANCELLED`，Workflow/Task 按顺序收敛，无关 `THINKING` Agent 保持不变，Agent Run 数量不增加。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：391 条 JVM，0 失败、0 错误；88 个 Gradle task 全部执行，Lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 手动安装最终 Debug/AndroidTest APK 并执行完整 `AndroidJUnitRunner`：`OK (126 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后已卸载测试包并重新覆盖安装、启动 Debug APK。主界面显示 Provider“兜底配置”和 `gpt-5.5`；默认 User-Agent 与设备 Agent 开关保持正确，AccessibilityService 处于 Enabled 与 Bound，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空。

该阶段当时的下一阶段边界：

- 同一 WorkRequest 的 Redmi 冷启动重入已经完成一次真实验收；后续继续补充更长耗时和系统自主回收样本。通用提交状态未知/验证事实不完整的执行栈仍只安全收敛并引导关联新 Run，旧模型协程和 Workflow 后续步骤不原地恢复。

## 2026-07-21 Redmi 真实 Worker 冷启动重入

验收对象与触发方式：

- 仅使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`。临时 instrumentation 创建 7 步 SAFE `app.current_time` Workflow，并保持目标进程存活，避免测试结束清除 JobScheduler 条目；任务 `scheduled-task-8114453d-164a-4986-a782-3c21ad0034f1`、WorkRequest `0d9aa2a5-ff1b-4a04-ad74-5d3c7bdf76db`、Job ID `3`。
- 计划时间与实际启动均为 `2026-07-21 06:05:03`。`06:05:05` 采样到 `ScheduledTask=RUNNING`、Workflow Run `workflow-run-8707c39c-fb30-42e1-a8c2-82a6b3c2eab4`、Agent Run `run-e47f030d-e16e-4b02-9d35-2f6e16ff6da0` 且状态为 `THINKING`；旧 PID 为 `25755`。
- 先执行 `am kill com.longdev.xiaoling`，PID 未消失，因为该 PID 承载前台 instrumentation；按预先记录的 fallback 立即执行 `run-as com.longdev.xiaoling kill -9 25755`。这是真机上的受控强杀，不冒充 Android 系统自主回收证据。

重入结果：

- 旧 PID 在约 `0.2s` 内消失，新 PID `26092` 被 JobScheduler 用同一 WorkRequest 冷启动；日志显示同一 `workSpecId`/generation 的 `onStartJob`、Worker 重调度和再次 `ScheduledWorkflowWorker` 启动，没有创建第二个 Agent Run。
- 新 Worker 在 `2026-07-21 06:05:06` 完成定向重入对账：ScheduledTask、Workflow Run、首步 Agent Run 均为 `CANCELLED`，错误为“应用重启后终止上次未完成 Agent 任务”；后 6 个 WorkflowStep 均为 `CANCELLED`，未继续执行后续步骤。
- Room 只读核对：同一 WorkRequest 保持不变，`actualStartedAt=06:05:03`、`completedAt=06:05:06`、耗时 `3360ms`；关联 Agent Run 数量为 `1`，工具调用与 ToolResult 数量均为 `0`。`run.recovered` 事件记录了旧 Run 收敛，Agent→Workflow→Task 顺序与确定性协调器一致。

最终回归与设备恢复：

- 删除临时探针后执行 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain`：391 条 JVM 测试通过，88 个 Gradle task 全部执行，Lint、Debug 与 AndroidTest 构建成功。
- 验收完成后已用 `apply_patch` 删除临时文件 `app/src/androidTest/java/com/longdev/xiaoling/probe/WorkerReentryProbeInstrumentedTest.kt`；最终 AndroidTest APK 不包含该探针。
- 仅在 Redmi 手动安装最终 Debug/Test APK 并执行完整 `AndroidJUnitRunner`：`OK (126 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。随后卸载测试包、覆盖安装并启动最终 Debug APK。
- Redmi 主库保持 1 个 Provider，“兜底”模型为 `gpt-5.5`，Base URL、启用模型和 API Key 密文均存在；默认 User-Agent 与设备 Agent 开关正确。AccessibilityService 最终处于 Enabled 与 Bound，`Crashed services:{}`，`MainActivity` 前台，crash buffer 为空。
- 最终 Debug APK SHA-256：`b3d630614841f0d470924590205798bd3d85beed9a74ff7accd31290f8cd0383`。

证据边界：

- 本轮证明了同一 WorkRequest 在进程被强制终止后的冷启动重入和定向收敛；没有启动 MainActivity，因此没有触发前台启动对账，也没有触碰 Pixel 模拟器。
- `run-as kill -9` 是 instrumentation 前台占用导致 `am kill` 无法生效时的 fallback；仍缺少 Android 自主回收、Doze/内存压力和更长模型任务的独立样本。Foreground Service、精确定时和设备 Workflow/后台自动化不因本轮证据提前引入。

## 2026-07-21 任务中心需确认队列

实现与边界：

- 新增 `AgentTaskFilterPolicy` 和“需确认”筛选。它只匹配已进入可重试终态且 `requiresConfirmation=true` 的 Agent Run；普通直接重试仍在“可重试”，活动审批/执行任务仍在“处理中”。
- 筛选后的卡片继续展示 `COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE` 等现有证据分类、原因和建议动作；点击重试仍进入原确认弹窗，确认前重新核对证据码。
- 本阶段不改变恢复策略：证据稳定后只创建带 `retryOfRunId` 的新 Run，旧 Run 保持不变；不恢复旧模型协程、不调用旧 Executor、不继续 Workflow 后续步骤。

自动化与 Redmi 验证：

- 新增 3 条 `AgentTaskFilterPolicyTest`，覆盖需确认只包含确认型重试、可重试仍包含直接/确认两类，以及活动/完成筛选边界。
- 新增 `AgentTaskFilterBarInstrumentedTest`，在 Compose 中确认“全部 / 需确认 / 处理中 / 可重试 / 已完成”全部可见，点击“需确认”返回正确筛选枚举。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：394 条 JVM，88 个 Gradle task 全部执行，Lint、Debug 和 AndroidTest 构建成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 安装最终 Debug/Test APK 并执行完整 `AndroidJUnitRunner`：`OK (127 tests)`，0 失败；未启动、连接或操作 Pixel 模拟器。
- 完整 instrumentation 后卸载测试包、覆盖安装并启动 Debug APK；AccessibilityService 最终 Enabled/Bound，`Crashed services:{}`，`MainActivity` 前台，crash buffer 为空。

## 2026-07-21 结构化恢复处置

实现与安全边界：

- 新增 `AgentRunRestartDispositionCode` 与 `AgentRunRestartDisposition`。`AgentRunResumeAssessment` 在构造时要求 `RESTART_REQUIRED` 必须且只能携带结构化处置；现有拒绝分支按 Run 状态、Profile、预算、审批、恢复证据、步骤、只读验证能力、工具定义和提交证明分类，不再只有自然语言 `reason`。
- `RunEventMetadata.Recovery` 新增可空 `resumeKind / restartDispositionCode / policyReason / evidenceBoundary / suggestedAction`。Codec 对旧事件缺字段保持 `null`；未知恢复类型保守降级为 `RESTART_REQUIRED`，未知处置码降级为恢复证据无效。字段只进入既有 metadata JSON，不升级 Room Schema。
- `closeInterruptedRuns()` 在取消活动 Step/Approval 前完成 ResumePolicy 与重试证据评估，并把两类结果冻结到同一个 typed `run.recovered`。任务卡、详情顶部和事件区展示同一历史快照；旧事件缺少完整处置字段时不按当前版本策略回填。
- 建议动作始终要求保留旧 Run 与现有审计，按既有门禁创建关联新 Run；没有恢复旧模型协程、旧 Executor、未知提交执行栈或 Workflow 后续步骤。

自动化与 Redmi 验证：

- `AgentRunResumePolicyTest` 覆盖 Run 状态、重复 Profile、审批参数漂移和缺少只读验证能力的稳定处置码；`RunEventMetadataCodecTest` 覆盖完整 round-trip、旧事件兼容和未来未知枚举 fail-closed；`AgentRunEventPresentationTest` 覆盖处置字段与旧事件不补造。
- `RoomAgentRunRepositoryInstrumentedTest.interruptedRunRecoveryUsesTypedStatusMetadata` 在 Redmi 确认 `THINKING` Run 同时保存 `NOT_COMMITTED` 重试证据和 `RUN_STATE_NOT_RESUMABLE` 恢复处置；新增 Compose instrumentation 确认处置卡显示恢复类型、稳定码、具体原因、证据边界与下一步动作。
- `./gradlew testDebugUnitTest --rerun-tasks --console=plain` 通过：395 条 JVM，0 失败、0 错误。`./gradlew lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过，Lint、Debug APK 与 AndroidTest APK 均构建成功。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 执行完整 `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain`：128 条 instrumentation，0 失败、0 跳过；未启动、连接或操作 Pixel 模拟器。首次定向 Compose 执行因真机熄屏锁定出现 `No compose hierarchies found in the app`，唤醒并手动解锁后完整门禁通过；同一错误也在既有 Compose 对照测试复现，确认不是新 UI 行为失败。
- instrumentation 清空主应用配置后，按本地 `AGENTS.md` 兜底配置通过一次性测试恢复 Provider/Keystore、默认 User-Agent 与设备 Agent 开关；恢复测试源码随后删除并重新构建最终 AndroidTest APK。真实 `/models` 返回目标模型。最终仅保留主包，Provider 为“兜底配置”/`gpt-5.5`，API Key IV/密文存在，默认 UA 与设备 Agent 开关正确，AccessibilityService Enabled/Bound，`Crashed services:{}`，`MainActivity` 前台且 crash buffer 为空。

该阶段当时的下一阶段边界：

- 继续采集更长任务、Android 自主回收、Doze 与内存压力下的 Redmi 证据，再决定 Foreground Service 和长任务策略；提交状态未知或验证事实不完整的旧执行栈继续安全收敛，只允许关联新 Run。

## 2026-07-21 Redmi 长任务、Doze、受控内存与终态竞态

官方行为依据：

- Android 官方 [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) 说明 Doze 会限制后台网络和 CPU，并延后 jobs、syncs 与标准 alarms；设备唤醒、移动或接入电源后恢复正常活动。因此本阶段只把强制 Doze 用作调度延迟样本，不把 WorkManager 描述为精确定时。
- Android 官方 [Support for long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) 说明真正长时间或对用户重要的 Worker 可调用 `setForeground()`，由 WorkManager 代管 Foreground Service 并显示持续通知。当前样本不满足提前引入的证据门槛。

Redmi 真实样本：

- 仅使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`。8 步 SAFE Workflow `workflow-fea1c67c-74ef-40c9-a568-c0055302c44c` 对应 WorkRequest `32052ac8-59d9-424a-999e-b93be9caf0eb`；首步真实模型成功执行 `app.current_time`，第二步模型重复相同调用后由循环保护以“检测到重复工具调用：app.current_time”安全失败。任务实际运行约 28.5 秒，没有形成 8 步全部成功证据。
- 强制 Doze 样本 WorkRequest `9d5f4695-0c43-4c7e-a7f6-c6f1630aae04` 在 20 秒观察窗内保持 ScheduledTask=`SCHEDULED`，没有 WorkflowRun、`actualStartedAt` 或应用 PID。执行 `deviceidle motion` 退出后，同一 WorkRequest 才启动，并在约 889ms 以 `connection closed` 失败；只有一个 Workflow/Agent Run，没有 `Result.retry` 或复制 Run。
- 运行中 trim-memory 样本 WorkRequest `ec3c2a82-af6b-4116-b5c2-14bc85de23aa` 在 PID `11446` 上接收 `RUNNING_CRITICAL`。发送前 PSS/RSS 约 `98997 / 207816 KB`，发送后约 `103498 / 213304 KB`，PID 未变化；任务约 944ms 后以 `connection closed` 失败且只有一个 Workflow/Agent Run。PSS/RSS 没有显示进程被回收，不能把连接关闭归因于 trim-memory。
- 无压力对照 WorkRequest `1a4b7b8c-3db3-416f-b248-0f0cfa155722` 也只创建一个 WorkflowRun `workflow-run-877df69d-fa49-4384-be7b-4eb1d88a073d` 和一个 AgentRun `run-9eacb9e8-a320-4fec-97e9-a3d67c16f07a`。它没有复现 trim-memory 因果，而是暴露启动竞态：前台启动恢复先把 Task/Workflow/Agent 收敛为 `CANCELLED`，旧执行协程随后仍完成 `app.current_time`、验证和总结，并把 AgentRun 迟到写成 `COMPLETED`；Task/Workflow 保持 `CANCELLED`。

竞态修复与自动化：

- `AgentRunDao.updateRunStatusIfActive()` 使用单条条件 `UPDATE`：只有当前状态不在终态集合时才更新状态、结果、错误和完成时间。`RoomAgentRunRepository` 仅在受影响行数大于 0 时追加 `run.status`，消除先读后写窗口；Room Schema 版本保持 v27。
- TDD Red 在 Redmi 精确复现 `expected:<CANCELLED> but was:<COMPLETED>`；Green 后单测试 `terminalRunKeepsRecoveryOutcomeWhenOldExecutionCompletesLate` 为 `OK (1 test)`，`RoomAgentRunRepositoryInstrumentedTest` 整类为 `OK (27 tests)`。完整门禁为 395 条 JVM、Lint/Debug/AndroidTest 构建通过，以及仅 Redmi 执行的 129 条 instrumentation。
- 最终 AndroidTest APK 已在删除临时 probe 后重建；完整 instrumentation 为 `OK (129 tests)`。测试包与明确的 probe/测试偏好文件已清理。Redmi 主库仍有 1 个“兜底配置”Provider，模型为 `gpt-5.5`，Base URL、启用模型及 Keystore IV/密文均非空；设备 Agent 开关为开启，默认 User-Agent 未被自定义值覆盖。AccessibilityService 已按 user 0 恢复为 Enabled/Bound，`Crashed services:{}`；deviceidle 为 `ACTIVE`，`MainActivity` 前台，crash buffer 为空。

证据与产品边界：

- `dumpsys deviceidle force-idle`、`am kill`、`run-as kill -9` 和 `am send-trim-memory` 都是受控命令，不代表 Android 自主 LMK；本阶段也不能证明 Doze 或 trim-memory 导致 `connection closed`。
- 终态保护冻结持久化审计结论，但不宣称可以撤销已经在飞行中的网络请求或外部副作用。旧模型协程、未知提交执行栈和 Workflow 后续步骤仍不原地恢复；设备工具继续不进入 Workflow 或后台自动化。
- 当前约 28.5 至 31 秒真实任务样本仍由普通 WorkManager 承载。后续优先取得 Android 自主 LMK 与更长成功任务样本，再评估当前进程内执行所有权、可见停止入口和 Foreground Service；精确定时仍继续后置。

## 2026-07-21 当前进程 Worker 所有权与启动恢复隔离

实现与并发边界：

- 新增计数型 `ScheduledWorkflowProcessExecutionRegistry`。`ScheduledWorkflowWorker` 在构造 Repository、执行重入对账或 claim 之前登记 Task ID；同一 ID 的重叠调用不会因其中一个结束而提前清除所有权。
- `StartupRecoveryCoordinator` 在同一互斥边界读取当前进程 Task 集合并冻结活动 AgentRun、WorkflowRun 和 RUNNING ScheduledTask。快照持锁期间新 Worker 必须等待，快照后启动的 Worker 不会进入旧候选。
- `RoomWorkflowRepository.startupRecoveryCandidates()` 在 Room 事务内沿当前 Task→WorkflowRun→AgentRun/WorkflowStep 关联链构造排除集合。ViewModel 的待审批恢复、已提交结果恢复、全部验证结果收尾、不可恢复 Agent 关闭、Workflow 对账和 ScheduledTask 对账全部只消费冻结 ID，不在后续阶段重新扫描全库。
- 旧链继续按现有 fail-closed 策略进入终态；当前进程 Worker 链保持活动。实现不使用墙上时间，不新增持久 owner token 或 Room Schema，不恢复旧模型协程、提交未知执行栈或 Workflow 后续步骤，也不引入 Foreground Service。

TDD、自动化与 Redmi 验证：

- `StartupRecoveryCoordinatorTest` 覆盖“旧候选保留、当前 Worker 三层链排除”，以及恢复快照持锁期间新 Worker 不得提前进入执行块；快照结果不包含该新任务。
- `RoomWorkflowRepositoryInstrumentedTest.startupRecoveryClosesOldChainButKeepsCurrentProcessWorkerChain` 在同一内存 Room 构造旧执行链和当前进程 Worker 链。恢复后旧 Agent/Workflow/Task 均为 `CANCELLED`，当前链保持 `THINKING/RUNNING/RUNNING` 并可继续进入 `COMPLETED`；前后 Agent Run 数量不变，没有第二个 Run 或迟到终态覆盖。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过：397 条 JVM，Lint、Debug APK 与 AndroidTest APK 构建成功。双轴审查从 `59281ec` 固定点执行：Spec 0 findings；代码 Standards/Fowler smells 0 findings，文档同步问题已在本节及五份长期文档中解决。
- 仅使用 Redmi Note 8 Pro Android 14 真机 `wsvwypiz7xwslvl7`：新增 Room 单项为 `OK (1 test)`；最终完整 `AndroidJUnitRunner` 为 `OK (130 tests)`、0 失败。没有启动、连接或向 Pixel/模拟器发送 ADB 命令。
- 完整 instrumentation 后已卸载测试包，主应用数据保留。主库有 1 条 Provider，Base URL、模型、Keystore IV/密文均非空；启动日志无 `VERIFICATION_FAILED`、解密异常、HTTP 401、FATAL 或 ANR。设备 Agent 偏好为开启，AccessibilityService 已恢复 Enabled/Bound，`MainActivity` 前台。最终 Debug APK SHA-256：`a5f2fa9c139f85ee7124768f262d475e2812890ba30cc6d7d52d2102ee657e3d`。

当前证据边界：

- 本阶段证明的是“同一进程中，前台启动恢复不会取消已经登记或快照后启动的 Worker”，并继续保护真正属于旧进程的执行链；它不等于 Android 自主 LMK、旧执行栈原地续跑或 WorkManager 长任务存活保证。
- 第 47 阶段当时的后续计划是继续取得 Android 自主 LMK 与更长成功任务样本，并完善运行中取消和用户可见停止边界；其中停止边界已由下一节第 48 阶段完成。该阶段 28.5 至 31 秒样本仍不足以引入 Foreground Service；设备工具仍不进入 Workflow/后台自动化，精确定时与后续生态能力继续后置。

## 2026-07-22 后台运行中停止、终态子账本冻结与长成功样本

实现与并发边界：

- 工作流页为一次性 `RUNNING` ScheduledTask 展示“停止运行”。`ScheduledWorkflowStopCoordinator` 在操作时重新读取 Room：待执行实例先事务取消本地门禁；若 Worker 已在 `SCHEDULED→RUNNING` 竞态中完成 claim，同一次点击自动切换到运行中停止。
- 运行中停止先取消目标 WorkRequest，并在有界窗口等待 Worker 正常取消；未及时收敛或 WorkManager 取消接口异常时，`ScheduledWorkflowStopFallbackCoordinator` 沿当前 Task→Workflow→Agent 关联按 ID 取消。Agent 尚未关联时仍关闭 Workflow/Task；重复停止幂等，不创建新 Run，也不影响无关链。
- `RoomAgentRunRepository` 除使用 `updateRunStatusIfActive()` 冻结 Run 顶层终态外，还在同一事务边界核对所属 Run 与 Approval 当前状态。终态后迟到 Step 不能新增或改回 `RUNNING/COMPLETED`，一次性 Approval 不能由 `CANCELLED` 改回 `APPROVED/DENIED`，外部 Runtime 的迟到 Event/Tool Ledger 双写也不再落库；仅 Repository 自己的最终状态审计显式放行。

Redmi 真实样本：

- 停止样本 Task `scheduled-task-82faa2d4-a5a6-42f4-85ee-fa91b36d8c1d`、Workflow `workflow-run-a7310674-1fe4-4445-bc02-d59980023d88`、Agent `run-51ade32e-dbae-4eef-b782-80a7b384b6a0`。用户在真实 `RUNNING` 页面点击停止；WorkManager 日志显示目标 Work 被 `stopAndCancelWork`，Task/Workflow/Agent 与三条 Workflow Step 均为 `CANCELLED`。从启动到停止约 32.6 秒；迟到 HTTP 200 返回后仍保持 `CANCELLED`，没有覆盖终态或补写成功子账本。
- 成功样本 Task `scheduled-task-fc8229b4-5ff7-4794-b269-e94b35601445`、Workflow `workflow-run-09f6d77d-9218-4901-bc6f-72a70a68cc7d`。三个 Agent Run 依次执行 `app.current_time`、`app.list_conversations(limit=3)`、`notes.list(limit=3)`，分别约 7.2、7.1、7.0 秒；Workflow 总耗时约 21.8 秒，Task/Workflow/三条 Step 均为 `COMPLETED`。
- `ApplicationExitInfo` probe 确认 Redmi `isLowMemoryKillReportSupported() == true`，历史退出 11 条，`REASON_LOW_MEMORY=0`。这些退出均可由本轮 instrumentation、force-stop 或安装等受控动作解释；没有取得 Android 自主 LMK 证据。

自动化与门禁：

- `ScheduledWorkflowStopCoordinatorTest` 共 5 条，覆盖 Worker 正常收敛、超时 fallback、WorkManager 异常 fallback、待执行取消和 `SCHEDULED→RUNNING` 抢占升级。Room instrumentation 覆盖目标链定向取消、缺链 fallback、迟到 Step/Event/Approval 冻结及不影响无关 Run。
- 完整门禁为 402 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi `wsvwypiz7xwslvl7` 执行的 134 条 instrumentation；没有启动、连接或向 Pixel/模拟器发送 ADB 命令。

当前证据边界：

- 用户停止与终态冻结保证控制面不会被迟到结果改写，但不能撤销停止前已经提交到外部系统的副作用。未知提交仍按既有证据分类和关联新 Run 门禁处理，不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- 约 21.8 秒成功、28.5 秒安全失败和 32.6 秒停止样本仍由普通 WorkManager 承载。LMK 报告能力可用不等于已经发生自主 LMK；当前不引入 Foreground Service，设备工具仍不进入 Workflow/后台自动化，精确定时与后续生态能力继续后置。

## 2026-07-22 Redmi 62.2 秒八步 SAFE Workflow

取证方式与边界：

- 一次性 instrumentation 探针只调用正式 `RoomWorkflowRepository.createWorkflow()`、`createOneTimeScheduledTask()` 和 `WorkManagerScheduledTaskScheduler.enqueue()` 建立生产账本与 WorkRequest，随后退出；真实模型、工具、步骤推进与结算均由生产 `ScheduledWorkflowWorker` 完成。探针源码取证后删除并重建最终 AndroidTest APK，不进入提交。
- Provider/Profile 通过本机未跟踪兜底配置恢复；后台 Profile 只临时允许 `app.current_time / app.list_conversations / app.search_conversations / notes.list / notes.search / memory.search / knowledge.search` 这些应用内工具。没有向 Workflow 开放任何设备工具，也没有启动或连接 Pixel/模拟器。

先行失败样本：

- Task `scheduled-task-fc435736-8c3f-4898-b353-4c2aefe014fd`、WorkRequest `27e77446-d14a-4742-a35d-6f34bea9cf25`、Workflow Run `workflow-run-f324b486-0971-4a52-b9b8-5b000184c8c5` 从 `02:25:27` 运行到 `02:26:16`，约 49 秒。前 5 步依次成功，第 6 步因模型没有执行目标 `memory.search` 而 `FAILED`，第 7、8 步进入 `CANCELLED`。
- 该失败是 Agent 目标遵循失败，不是 Worker 回收：PID 保持，只有一个 Workflow Run，各已启动步骤各有一个 Agent Run，没有 `Result.retry` 或复制执行，Task/Workflow 均以明确失败原因收敛。

八步成功样本：

- Task `scheduled-task-b7cae61a-e311-42bc-98a7-f8d601a9be59`、WorkRequest `ec200f45-ed0d-4b78-9fd6-4cbcc2dd25fd`、Workflow Run `workflow-run-fc647164-1faf-4b5f-853a-16ae14565340` 在计划时间 `02:28:26` 启动，于 `02:29:28` 完成，Task 实际耗时 `62.2s`。Task、Workflow 和 8 条 Workflow Step 全部 `COMPLETED`，`scheduledTaskId` 只关联一个 Workflow Run。
- 8 个 Agent Run 耗时分别为 `7.5 / 7.4 / 7.0 / 9.0 / 8.6 / 6.4 / 7.9 / 7.3s`。工具顺序为 `app.current_time`、`app.list_conversations(limit=1)`、`notes.list(limit=1)`、`app.search_conversations(query=Stage49, limit=1)`、`notes.search(query=Stage49, limit=1)`、`app.current_time`、`app.list_conversations(limit=3)`、`notes.list(limit=3)`；8 个 ToolResult 均为 `success=true / verificationStatus=PASSED`。
- Worker 启动前后的应用 PID 均为 `27957`，本轮没有进程重建、系统重试或复制 Run。真实工作流页面显示同名最新实例“已完成”，前一个先行实例“失败”；截图证据保存在本机 `/tmp/lingce-stage49-success.png`。

LMK 与清理结果：

- 样本后的 `ApplicationExitInfoInstrumentedTest` 为 `OK (1 test)`，日志为 `supported=true exits=6 lowMemory=0 fallbackSigkillCandidates=0`。6 条退出全部是运行 setup/LMK instrumentation 前后产生的 `reason=10 USER REQUESTED / FORCE STOP`；没有 `REASON_LOW_MEMORY` 或不明 SIGKILL，不能声称取得 Android 自主 LMK。
- 第 49 阶段没有修改生产代码，阶段 48 的完整门禁基线仍为 402 条 JVM、134 条仅 Redmi instrumentation。一次性 setup probe 已删除，最终 AndroidTest APK 重新构建；测试包卸载后恢复真实 Provider/Keystore、默认 `device.open_app` Profile、Accessibility Enabled/Bound 和主应用前台。

当前结论：

- 普通 WorkManager 已在 Redmi 承载约 62.2 秒、8 个真实模型/SAFE 工具步骤的完整成功链，现有证据仍不支持提前引入 Foreground Service。它证明当前长度可完成，不代表系统会保证任意更长任务存活。
- 第 49 阶段当时的下一步是继续寻找 Android 自主 LMK，并完善系统取消或重对账异常后的持久化保障；其中持久化重对账已由下一节第 50 阶段完成。旧模型协程、提交未知执行栈和 Workflow 后续步骤继续 fail-closed，设备工具继续禁止进入 Workflow/后台自动化。

## 2026-07-22 `STOP_REQUESTED` 持久化停止与原子重对账

实现与安全边界：

- `ScheduledTaskStatus` 新增非终态 `STOP_REQUESTED`。Workflow 仍活动时，运行中停止先通过 `requestScheduledTaskStop()` 在 Room transaction 中完成 `RUNNING→STOP_REQUESTED`，再调用 WorkManager 取消；重复停止幂等。系统取消和即时 fallback 同时抛出时，协调器返回“停止请求已持久化”，不再让异常抹掉用户意图。停止 fallback 先定向关闭 Agent，再通过 `settleScheduledWorkflowRun()` 一次事务结算 Workflow/Task；只有 Workflow 尚未建立时才单独关闭 Task。若 Workflow 已先终态，事务直接把半结算 Task 对账到该状态，停止请求返回既有终态而不写栅栏；原子结算内部直接写入该映射，不再被通用停止栅栏二次改写。
- Worker 重入、停止 fallback 与启动恢复均扫描 `RUNNING / STOP_REQUESTED`。当前进程注册表只排除仍正常 `RUNNING` 的 Task→Workflow→Agent 链；已经写入停止请求的任务即使仍登记所有权，也会进入启动恢复并按 Agent→Workflow→Task 收敛，且不创建第二个 Run。若 Worker 已认领并建立 Workflow Run、但 Agent Run 尚未关联，Workflow 对账会先读取唯一关联 ScheduledTask 的停止栅栏，直接把 Workflow 与未完成步骤取消，再由 Task 对账收敛为取消；不会落入“关联 Agent 缺失”失败分支。
- `completeScheduledWorkflowStep()` 在同一 Room transaction 中先核对 Task↔Workflow 关联和停止栅栏，再一起提交步骤终态与 `AGENT_RESULT` 消息。停止已落库时抛出取消，步骤保持未完成且会话不出现迟到成功消息。`settleScheduledWorkflowRun()` 同样在一个 transaction 中重新读取栅栏、Task 和关联 Workflow；已有 Workflow 终态优先映射到 Task，只有 Workflow 仍活动时 `STOP_REQUESTED` 才固定取消，迟到的 `COMPLETED` 不能覆盖。持久状态与本轮 outcome 不一致时不追加消息或显示相反通知。
- `finishScheduledTask()` 同样保证 `STOP_REQUESTED` 只能进入 `CANCELLED`。该状态不是终态，Daily/Weekly 下一实例在旧任务完成对账前不会物化。实现复用既有 TEXT 状态列，没有 Room migration，正式 Schema 仍为 v27；不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤，不使用 `Result.retry`，不引入 Foreground Service。

定向与完整门禁：

- 最后一项 Task/Workflow 关联校验落地后，JVM 定向执行 `ScheduledWorkflowStopCoordinatorTest.stopKeepsDurableRequestWhenSystemCancellationAndFallbackFail` 与 `ScheduledWorkflowReentryCoordinatorTest.stopRequestedTaskReconcilesPersistedChainInsteadOfExecutingAgain`，2/2 通过。
- 仅在 Redmi `wsvwypiz7xwslvl7` 定向执行 `persistedStopRequestOverridesCurrentProcessOwnershipAndReconcilesOnStartup`、`lateWorkerCompletionCannotOverwritePersistedStopRequest`、`stopRequestedRecurringTaskDoesNotMaterializeNextOccurrenceBeforeReconciliation`，3/3 通过。双轴审查先后补充定位步骤消息竞态与半结算终态竞态；新增 `stopRequestedTaskRejectsLateStepResultBeforeConversationAppend`、`persistedCancelledWorkflowPreventsLateTaskCompletion`，后者还确认 Workflow 已先取消时来晚的停止请求直接返回 `CANCELLED`。最终审查又发现 Agent Run 尚未关联时通用重入会把停止写成失败；新增 `workerReentryPreservesStopIntentBeforeAgentRunIsLinked`，Red 阶段在 Redmi 得到 `expected:<CANCELLED> but was:<FAILED>`，修复后单测 1/1、相邻重入与持久停止 3/3 均通过，并确认未创建 Agent Run、Workflow/两条未完成步骤/Task 全部取消。状态集合重复维护的判断项随后收敛到 `ScheduledTaskPolicy`，新增 JVM 测试固定执行重对账与未结算状态分类。停止 fallback 半结算测试 `userStopFallbackPreservesWorkflowTerminalWhenTaskIsHalfSettled` 的 Red 阶段得到 `expected:<COMPLETED> but was:<CANCELLED>`；改为原子结算并修正既有终态映射后单测 1/1、相邻 fallback 3/3 通过。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过；Gradle XML 实际汇总为 405 条 JVM、0 失败、0 错误。`ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest` 启动并完成 141 条 instrumentation，0 跳过、0 失败；没有启动、连接或向 Pixel/模拟器发送 ADB 命令。

真机恢复状态：

- 完整 instrumentation 后目标应用被测试框架移除；已重新安装本轮通过门禁的 Debug APK，通过 Debug-only 正式 Repository/Keystore 入口从未跟踪 `AGENTS.md` 恢复兜底 Provider 与默认只允许 `device.open_app` 的 Profile。日志只核对模型已配置与工具白名单，不输出 Base URL 或 API Key。
- 测试包 `com.longdev.xiaoling.test` 已卸载。Redmi 的 AccessibilityService 最终为 Enabled 与 Bound，`Crashed services:{}`；`com.longdev.xiaoling/.MainActivity` 为 `topResumedActivity`。该 Redmi ROM 在 `force-stop` 后会再次清掉 Accessibility 授权，因此最终顺序为先启动主应用、再恢复服务，之后不再强停。

当前结论：

- 阶段 50 关闭的是“停止意图在平台取消/fallback 异常、迟到 Worker 与进程重建之间丢失”的窗口，以及 Workflow/Task 分两次结算的 TOCTOU；它不能撤销停止前已提交到外部系统的副作用，也不扩大旧执行栈恢复能力。
- 下一阶段继续寻找 Android 自主 LMK，并完善提交未知或验证事实不完整时的通用恢复证据。62.2 秒成功样本仍没有引入 Foreground Service 的依据；设备工具继续禁止进入 Workflow/后台自动化，精确定时与后续生态能力继续后置。

## 2026-07-22 旧验证事件关联未知与 LMK 基线

实现与恢复边界：

- `AgentRunRecoveryEvidencePolicy.readEventFallback()` 不再为缺少 `toolCallId` 的 `tool.verify` 按工具名或事件顺序寻找“第一个未验证结果”。结果/验证都必须用稳定 ID 唯一匹配 ToolCall；缺失时返回无效，上层恢复与重试统一保守为 `EVIDENCE_INCOMPLETE`。带完整 ID 的旧 typed event fallback、v20 Ledger-first、审批恢复和已提交工具只读验证保持原能力。
- TDD 把 `legacySameNameCallsWithoutVerificationIdsKeepSequentialFallback` 改为 `legacyVerificationWithoutToolCallIdFailsClosedInsteadOfGuessingByOrder`。第一轮 Red 失败在 `Invalid` 断言；审查发现重试策略另有 legacy 分支后，新增 `legacyVerificationWithoutToolCallIdIsEvidenceIncomplete`，第二轮 Red 显示当前仍为普通确认分类。最终恢复与重试两条入口都按缺失 ID fail-closed。一个 Runtime fixture 原本声明“已验证前序事实”但漏写验证 ID，已补齐同一 ToolCall ID，不放宽策略。

门禁与 Redmi 证据：

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过；Gradle XML 汇总为 406 条 JVM、0 失败、0 错误。`ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest` 完成 141 条 instrumentation，0 跳过、0 失败；没有启动、连接或向 Pixel/模拟器发送 ADB 命令。
- Redmi 定向 `ApplicationExitInfoInstrumentedTest` 为 `OK (1 test)`，日志为 `supported=true exits=2 lowMemory=0 fallbackSigkillCandidates=0`。`exit[0]` 是启动 instrumentation 的 `reason=10 / FORCE STOP`，`exit[1]` 是安装包的 `reason=16`；两者都不是 Android 自主 LMK，不能用于证明内存压力回收，也不支持引入 Foreground Service。
- 完整 instrumentation 后已重新安装 Debug APK，通过 Debug-only 正式入口恢复 Provider/Profile，默认只允许 `device.open_app`；测试包已卸载。AccessibilityService 为 Enabled 与 Bound，`Crashed services:{}`，主应用前台，crash buffer 为空。

当前结论：

- 第 51 阶段关闭了“旧验证缺少稳定调用身份却被顺序猜配成可恢复事实”的窗口，但不恢复提交未知、验证未落库或无法证明的旧执行栈。下一切片应冻结 Ledger/Event canonical fingerprint，并在确认重试前识别分类码相同的身份漂移。
- Android 自主 LMK 仍未取得。继续采用成对基线/后测的 `ApplicationExitInfo` 证据，不把 `kill -9`、force-stop、安装、instrumentation、Doze 或 trim-memory 写成自主 LMK；设备工具继续禁止进入 Workflow/后台自动化，Foreground Service、精确定时和后续生态能力继续后置。

## 2026-07-22 恢复证据指纹与同分类漂移拒绝

实现与恢复边界：

- 新增 `AgentTaskRetryEvidenceFingerprint`，对独立 ToolCall/ToolResult 账本及非 `run.recovered` typed event 做长度前缀 canonical 序列化并计算 SHA-256。启动收敛在 Step/Approval 改写前计算，随 `retryEvidenceCode` 一起写入 `run.recovered.retryEvidenceFingerprint`；不把本次恢复事件和收敛后的 Run/Step 状态纳入摘要，避免恢复动作自身造成假漂移。
- `AgentTaskRetryPolicy.assessEvidence()` 重新计算当前摘要。历史 Recovery 已带证据码但缺少摘要、摘要不同或分类码不同，均升级为 `EVIDENCE_INCOMPLETE`。`AgentRetryConfirmationUiState` 同时冻结打开弹窗时的分类码与摘要；提交前二次评估，任一变化都拒绝旧确认并要求用户重新确认。稳定确认仍只创建带 `retryOfRunId` 的关联新 Run，旧 Run、旧 Executor、旧模型协程和 Workflow 后续步骤保持不变。
- 新增 JVM 回归覆盖：同分类下新增合法 typed ToolCall/ToolResult、同分类下替换合法 Ledger/typed event Receipt 均拒绝旧确认；相同摘要继续允许原有确认路径。Codec 可空字段兼容旧 metadata，字段仍位于既有 JSON，Room v27 Schema 不变。

门禁与 Redmi 证据：

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon` 通过；Gradle XML 汇总为 408 条 JVM、0 失败、0 错误，Lint、Debug APK 和 AndroidTest APK 均通过。
- `adb devices -l` 仅列出 Redmi `wsvwypiz7xwslvl7`。`ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 在 Redmi Note 8 Pro Android 14 完成 141 条 instrumentation，0 跳过、0 失败；未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 52 阶段关闭了“证据分类码不变但调用身份、参数、回执或验证事件已经合法漂移”仍可沿用旧确认的窗口。下一恢复切片继续覆盖 Receipt 已持久化但验证事实未落库、模型/网络中断和结果回写竞态；无法证明的旧执行栈继续 fail-closed。
- Android 自主 LMK 仍未取得，当前 62.2 秒成功样本仍不足以引入 Foreground Service；设备工具继续禁止进入 Workflow/后台自动化，精确定时和后续生态能力保持后置。

## 2026-07-22 ToolResult、预算与验证三段故障注入

实现与恢复边界：

- `AgentRuntimeFaultInjector` 从单一回调扩展为三个默认 no-op 的持久化边界：`afterToolResultEventPersisted`、`afterToolResultPersisted` 和 `afterToolVerificationPersisted`。它们分别位于 ToolResult 事件写入后、执行预算快照写入后和 `tool.verify` 事件写入后；生产默认实现不改变正常执行顺序。
- 新增真实 Runtime JVM 契约：ToolResult 已带 `COMMITTED` Receipt 落库，但后续预算快照尚未写入时模拟进程终止。重载 Detail 后，`AgentExecutionBudgetEvidencePolicy` 识别“工具结果缺少后续执行预算快照”，`AgentRunResumePolicy` 固定返回 `RESTART_REQUIRED / EXECUTION_BUDGET_INVALID`，不会因为回执已提交就猜测剩余预算并原地恢复。
- 既有“`tool.verify` 已写入、验证 Step 尚未完成”测试改为直接使用 `afterToolVerificationPersisted`，不再依赖包装 Ledger 制造中断。恢复只补齐验证 Step、Run 终态和本地可信总结；Executor 执行次数保持 1，ToolResult 和 `tool.verify` 各保持 1 条。预算完整但验证尚未写入的白名单只读回查路径保持原能力，三种边界均不恢复旧模型协程或 Workflow 后续步骤。

门禁与 Redmi 证据：

- 首轮定向测试因测试工具声明为 `REQUIRES_APPROVAL`、未进入执行阶段而失败；改为 SAFE 测试工具后又暴露 Schema 未声明 `title/content`，补齐 Schema 后才命中新 seam。最终定向 `MinimalAgentRuntimeTest` 全部通过。
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon --console=plain` 通过；Gradle XML 汇总为 409 条 JVM、0 失败、0 错误，Lint、Debug APK 和 AndroidTest APK 均通过。
- `adb devices -l` 仅列出 Redmi `wsvwypiz7xwslvl7`。`ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 在 Redmi Note 8 Pro Android 14 完成 141 条 instrumentation，0 跳过、0 失败；未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 53 阶段把 Receipt/Result、执行预算和验证事实之间的竞态变成了可重复验证的持久化边界，并证明证据缺口保持 fail-closed；它没有扩大通用原地恢复范围。
- 下一切片继续覆盖模型/网络中断后的遥测与预算回写竞态、Receipt 回读验证失败的重试可见性，并继续寻找 Android 自主 LMK。Foreground Service、设备 Workflow/后台权限、精确定时和后续生态能力保持后置。

## 2026-07-22 模型异常预算审计与总结本地兜底

实现与恢复边界：

- `continuePlanning()` 捕获带 `AgentLlmResponseException` 的规划失败时，先追加 `llm.request.completed` telemetry，再写入“模型规划失败后的执行预算”；没有统一 telemetry 的网络/网关异常也会写入“模型规划异常后的执行预算”，然后由外层进入 FAILED。这样失败 Run 的成本和单调预算不会停在上一个成功快照。
- `completeRun()` 对总结阶段的超时、带 telemetry 异常和普通网络异常分别保留预算快照；总结异常只写 `llm.summarize.fallback` 并使用本地可信工具事实构造回复，不把已经成功的 ToolResult/`PASSED` 验证改判为失败。
- `committedMemoryRecoveryFailurePersistsStableReasonAndSuggestedAction` 增加重试边界断言：回读失败仍为 `COMMIT_UNKNOWN`，Run 可重试但 `requiresConfirmation=true`，typed `RecoveryFailure` 的错误码、原因和建议动作保持稳定，不调用旧写入 Executor。

门禁与 Redmi 证据：

- 新增规划网络异常 telemetry/预算顺序测试与总结网络失败本地兜底测试；完整 JVM XML 汇总为 411 条，0 失败、0 错误。`./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon --console=plain` 通过。
- `adb devices -l` 仅列出 Redmi `wsvwypiz7xwslvl7`。`ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 完成 141 条 instrumentation，0 跳过、0 失败；未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 54 阶段关闭了“模型异常后预算停留在旧快照”以及“总结网络失败覆盖已验证工具事实”的窗口；仍不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- 下一阶段继续覆盖流式模型断流、无 telemetry 上游错误分类、后台长任务预算回写竞态和自然系统回收；Android 自主 LMK、Foreground Service、精确定时及后续生态能力继续后置。

## 2026-07-22 模型上游错误稳定分类

实现与边界：

- 新增 typed `llm.request.failed`、`RunEventMetadata.LlmFailure` 和稳定 `AgentLlmFailureKind`。规划与总结异常会在预算审计后记录阶段、错误分类和脱敏原因，不保存请求正文。
- `ApiFailure` 被映射为鉴权、请求地址、限流、模型、超时、DNS、TLS、连接、响应和未知分类；流式意外结束、连接重置、broken pipe、stream reset 和 socket closed 统一按 `CONNECTION` 处理。缺少网络分类的 `AgentLlmResponseException` 按 `RESPONSE`，其他未知异常及未来枚举按 `UNKNOWN` fail-closed。
- 本阶段只稳定失败事实和用户可见事件，没有伪造网络 telemetry，也没有恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。

门禁与 Redmi 证据：

- 新增 Runtime、metadata codec 和事件展示契约；完整 JVM XML 汇总为 413 条，0 失败、0 错误。Lint、Debug APK 和 AndroidTest APK 构建通过。
- `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 完成 141 条 instrumentation，0 跳过、0 失败；仅使用 Redmi，未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 55 阶段已完成流式模型断流和无 telemetry 上游异常的稳定 typed 分类。下一阶段继续验证部分流式 delta 已经可见后的收敛、后台长任务预算写回竞态和自然系统回收；Android 自主 LMK、Foreground Service、精确定时及后续生态能力继续后置。

## 2026-07-22 部分流式 delta 断流的用户可见收敛

实现与边界：

- 普通对话收到部分 SSE delta 后发生连接中断时，网络层保留已经交付给 UI 的累计正文并按 `CONNECTION` 失败结束；ViewModel 把已有 assistant 消息写为 `finishReason=failed`，保留正文和失败原因，再追加独立失败气泡。
- UI 不再把 `streaming=true / latencyMs=null` 简单当作“接收中”：`failed` 和 `cancelled` 都是终态，部分失败正文展示“内容不完整”，用户取消展示“已停止”。没有收到 delta 时不创建 assistant 正文。
- `failed/cancelled` assistant 不参与下一轮普通对话请求或会话摘要，避免残缺内容成为新的模型事实；历史会话仍保留该气泡供用户查看。Agent 规划/总结继续保持非流式请求和第 55 阶段的 typed 失败审计边界。

门禁与 Redmi 证据：

- 新增真实 socket 不完整响应测试，确认已收到 delta 后仍归类 `CONNECTION`；新增消息终态、上下文资格和无 delta 不造正文测试。完整 JVM XML 汇总为 420 条，0 失败、0 错误、0 跳过；Lint、Debug APK 和 AndroidTest APK 构建通过。
- `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 在 Redmi Note 8 Pro Android 14 完成 141 条 instrumentation，0 跳过、0 失败；Debug APK 已重新安装并启动到 `com.longdev.xiaoling/.MainActivity`。未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 56 阶段关闭了“部分流式输出在断流后永久显示接收中”以及“残缺 assistant 进入下一轮上下文”的窗口。下一阶段继续覆盖后台长任务预算写回竞态和自然系统回收；Android 自主 LMK、Foreground Service、精确定时及后续生态能力继续后置。

## 2026-07-22 取消时的后台预算写回收敛

实现与边界：

- `MinimalAgentRuntime` 的新 Run、审批恢复、已提交结果回读恢复和已验证收尾恢复，统一通过 `settleCancelledRun()` 进入 `NonCancellable`。取消收敛先追加当前 `AgentExecutionBudget` 快照，再取消活动 Step、写入 `run.cancelled` 并冻结 Run。
- 模型或工具的 `finally` 已累计的单调耗时不会因用户停止、WorkManager 取消或系统协程取消丢失；预算事件严格排在取消终态之前。该修复只补齐持久化审计，不恢复旧模型协程、旧 Executor、未知提交执行栈或 Workflow 后续步骤。

门禁与 Redmi 证据：

- TDD 先把取消测试固定为 `37ms` 单调耗时，修复前只能读取初始 `0ms` 快照；修复后 `AgentExecutionBudgetEvidencePolicy` 能读到 `37ms`，且预算事件索引先于 `run.cancelled`。完整 JVM XML 汇总为 420 条，0 失败、0 错误、0 跳过；Lint、Debug APK 和 AndroidTest APK 构建通过。
- `ANDROID_SERIAL=wsvwypiz7xwslvl7 ./gradlew connectedDebugAndroidTest --console=plain --no-daemon` 在 Redmi Note 8 Pro Android 14 完成 141 条 instrumentation，0 跳过、0 失败；Debug APK 已重新安装并启动到 `com.longdev.xiaoling/.MainActivity`。未启动、连接或操作 Pixel_9/其他模拟器。

当前结论：

- 第 57 阶段关闭了“取消发生在预算累计之后但持久化仍停留旧快照”的窗口。下一阶段继续寻找 Android 自主 LMK，并在更长真实后台任务中观察预算快照与系统回收的组合行为；Foreground Service、精确定时及后续生态能力继续后置。

## 2026-07-22 Redmi 后台长任务 TLS 阻断取证（第 58 阶段）

目标与边界：

- 使用生产 `RoomWorkflowRepository`、`ScheduledWorkflowWorker` 和 WorkManager，在 Redmi `wsvwypiz7xwslvl7` 创建 8 步 SAFE Workflow，观察预算快照、Workflow/Task/Agent 终态和系统回收；不使用强杀、Doze、trim-memory 或其他伪造 LMK 证据。
- 临时 Probe 只用于取证，未进入正式源码或 Git；其中的本机兜底凭据已在测试后删除。Probe 创建的 Provider/Profile、2 个 Workflow、2 个失败 ScheduledTask、2 个 WorkflowRun、2 个 AgentRun 和对应会话/消息均已按 ID 定向删除。

真实结果：

- 两次执行均约 4 至 6 秒结束：`task=FAILED`、`workflow=FAILED`、8 步为首步 `FAILED` 加后续 `CANCELLED`，`agentRuns=1`、`agentStatuses=FAILED`、WorkManager `SUCCEEDED`、`budgetMonotonic=true`，模型失败分类为 `TLS`，错误为 `connection closed`。没有创建第二个 Agent Run，也没有把失败写成成功长任务。
- Mac 侧使用同一端点访问 `/v1/models` 能完成 TLS 并得到 HTTP `401 Unauthorized`。Redmi 自带 `curl` 直接访问同一 HTTPS URL，在 TCP 已连接后于 TLS ClientHello 阶段稳定返回 `BoringSSL SSL_ERROR_SYSCALL`；强制 TLS 1.2、TLS 1.3 和 HTTP/1.1 结果一致。该证据把故障边界收窄到 Redmi 当前网络路径或上游 TLS 兼容，不支持修改应用证书校验或 HTTP 安全策略。

清理与门禁：

- 已删除临时 `Stage58LongWorkflowProbeTest.kt`，卸载 `com.longdev.xiaoling.test` 后重新安装正式 Debug APK；完整 instrumentation 清空正式模型配置后，又通过一次性 Repository/Keystore Probe 恢复本机兜底 Provider 和默认 Agent Profile，并立即删除该 Probe、重建不含凭据的 AndroidTest APK、再次卸载测试包。Stage 58 Provider/Profile/Workflow/会话计数均为 0，`com.longdev.xiaoling/.MainActivity` 位于前台，进程存活，crash buffer 为空。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --stacktrace --console=plain` 通过；JVM、Lint、Debug APK 和 AndroidTest APK 门禁通过。仅在 Redmi 执行 `adb -s wsvwypiz7xwslvl7 shell am instrument -w -r com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner`，结果 `OK (141 tests)`。

当前结论：

- 第 58 阶段完成的是后台 Worker/TLS 失败边界和清理，不是更长真实成功任务或自然 LMK 验收。当前仍保留既有约 62.2 秒成功样本作为历史基线，但没有新增更长证据；不引入 Foreground Service，不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- 下一阶段先在 Redmi 网络路径恢复或切换到已验证可达的 Provider 后重做同一 8 步任务；只有取得更长成功耗时、系统回收或明确停止需求证据，才重新评估 Foreground Service。设备工具继续禁止进入 Workflow/后台自动化，精确定时及 MCP、远程 Channel、多 Agent、本地模型继续后置。

## 2026-07-22 Redmi 网络恢复后的 92.667 秒八步成功 Workflow（第 58 阶段复验）

执行与结果：

- 网络恢复后使用正式 Provider/Profile，通过生产 `ScheduledWorkflowWorker` 和 WorkManager 在 Redmi `wsvwypiz7xwslvl7` 重新执行 8 步 SAFE Workflow。WorkRequest `287d9de6-6eef-467c-a290-a4491f3ae4b1` 于约 `92.667s` 后 `SUCCEEDED`；ScheduledTask、WorkflowRun 和 8 个 WorkflowStep 全部 `COMPLETED`。
- 8 个 Agent Run 均为 `COMPLETED`，没有 `Result.retry` 或复制 Run。步骤依次完成 `app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`memory.search`、再次 `app.current_time` 和再次 `app.list_conversations`；8/8 ToolResult 为 `success=1 / verificationStatus=PASSED`。
- 每个 Agent Run 的执行预算快照均单调，预算最大值约为 `6.382s` 至 `17.369s`；RunEvent 账本包含 40 条预算更新、8 条 ToolCall、8 条 ToolResult、8 条 `tool.verify`，没有 `llm.request.failed`。

系统回收与清理：

- 该样本没有触发系统回收；`ApplicationExitInfo` 历史记录中 `lowMemoryExits=0`，新增退出仍是 instrumentation 完成后的 `USER REQUESTED / FORCE STOP`，不能写成 Android 自主 LMK。普通 WorkManager 在当前约 93 秒任务上稳定完成，当前没有引入 Foreground Service 的证据。
- Probe 源码、测试包、Workflow、ScheduledTask、WorkflowRun、8 个 AgentRun、会话消息和工具账本均已清理；正式 Provider/Profile 保留，正式 `MainActivity` 已重新启动，测试包已卸载，数据库中 Stage 58 记录为 0。

当前结论：

- 第 58 阶段现在同时具备“网络阻断失败样本”和“网络恢复后的 92.667 秒成功样本”。它证明当前长度的后台链路可完成，不证明任意更长任务的系统存活保证，也不证明自然 LMK。下一阶段继续以更长真实耗时、自然系统回收或明确用户停止需求决定 Foreground Service；设备工具仍不进入 Workflow/后台自动化，精确定时及后续生态能力继续后置。

## 2026-07-22 Redmi 229.416 秒复合只读后台成功 Workflow（第 59 阶段）

目标与边界：

- 只使用 Redmi `wsvwypiz7xwslvl7`，使用正式 Provider/Profile、`RoomWorkflowRepository`、`ScheduledWorkflowWorker` 和 WorkManager；一次性任务使用产品允许的最短 1 分钟延迟。Probe 只创建账本和 WorkRequest，模型请求、工具执行、验证和结算全部由生产 Worker 完成。
- Workflow 保持产品允许的 8 步上限；为延长真实耗时，每步明确依次调用 3 个应用内只读工具，不开放任何设备工具、写工具或审批动作。不使用强杀、Doze、trim-memory 或其他伪造 LMK 手段。

真实结果：

- Workflow `workflow-e64d25b2-6ac1-4eed-ab13-f1a630e98798`、ScheduledTask `scheduled-task-c7ea7af8-9673-408c-b626-439fbc558a19`、WorkRequest `67cd2409-eeb5-49c1-af68-8b8983d7daaf` 于 `10:33:33` 创建，约 1 分钟后启动；`10:37:22` 收敛，生产耗时 `229.416s`（Instrumentation 总耗时 `229.633s`）。Task/Workflow/8 个 WorkflowStep/8 个 AgentRun 全部 `COMPLETED`，没有 `Result.retry` 或复制 Run。
- 8 步共执行 24 个只读工具调用，24/24 ToolResult 为 `success=true / verificationStatus=PASSED`；RunEvent 包含 72 条 `run.execution_budget.updated`、24 条 `tool.verify`，`llmFailureKinds=[]`。本轮临时日志只统计预算事件数量，未把 `consumedMs` 数值解析结果作为新增单调性证据。

系统回收、清理与门禁：

- Redmi `ApplicationExitInfo` 定向测试通过，输出 `supported=true / exits=14 / lowMemory=0 / fallbackSigkillCandidates=0`；14 条历史退出均为 instrumentation 完成或安装停止，未取得 Android 自主 LMK。
- 测试结束后 Probe 内定向删除成功样本；随后又删除了前两次约束失败留下的空 Stage 59 Workflow。最终正式数据库核对为 `providers=1`、`agent_profiles=1`、`workflows=0`、`scheduled_tasks=0`、`workflow_runs=0`、`workflow_steps=0`、`agent_tool_results=0`；测试包已卸载，正式 Debug APK 已启动到 `com.longdev.xiaoling/.MainActivity`，进程存活。
- 删除 Probe 后执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks --console=plain` 通过：JVM、Lint、Debug APK 和 AndroidTest APK 全部成功。完整 instrumentation 本轮未重复执行，以避免再次清空正式 Provider/Profile；之前完整门禁仍为 JVM `420/420`、Redmi instrumentation `141/141`。

当前结论：

- 第 59 阶段把普通 WorkManager 的真实成功耗时基线从约 93 秒扩大到约 229 秒，但仍不能推导任意长度的系统存活保证，也没有自然 LMK 或明确的前台服务需求证据。因此暂不引入 Foreground Service，不恢复旧模型协程/旧 Executor，不把设备工具放入 Workflow 或后台自动化；下一阶段继续围绕更长真实耗时、自然系统回收或明确用户停止需求取证。

## 2026-07-22 Redmi JobScheduler 冷启动后台成功 Workflow（第 60 阶段）

目标与边界：

- 第 59 阶段的 Probe 在 instrumentation 进程内持续轮询终态，可能提高应用进程重要性。本阶段改为 Probe 只创建 Workflow/ScheduledTask、调用 Scheduler、持久化 WorkRequest ID 后立即返回；不在测试进程中等待模型或读取终态。
- Probe 结束后确认原应用 PID 消失，再由 JobScheduler 按计划冷启动生产 `ScheduledWorkflowWorker`。Workflow 使用产品上限 8 步，每步调用 4 个应用内只读工具；不开放设备工具或写工具，不使用强杀、Doze、trim-memory 或前台 Activity 保活。

首条诊断样本：

- WorkRequest `bccd7350-8877-4bc7-ab02-76030605b371` 在 Probe 结束后冷启动 PID `25414`，WorkflowRun `workflow-run-40afd0a8-1b8d-407f-bb04-c1ab4cbef092` 于 `206.521s` 后完成；8 个 AgentRun、32/32 ToolCall/ToolResult/`tool.verify` 全部成功，无模型失败。
- 每个 AgentRun 有 11 条预算快照，最大 `consumedMs` 为 `17.360s–37.101s`，8 个 Run 的预算回退次数均为 0。取证发现 Probe 漏掉 `attachWorkRequest()`，Room 的 ScheduledTask 没有保存 WorkRequest ID；该样本只作为诊断证据，清理后修正 Probe 并重做。

修正后的正式样本：

- 入队 Probe 在 `0.255s` 内通过并退出，原 PID 消失；ScheduledTask `scheduled-task-f598a2b8-d851-437f-af3f-1f6cbe52fb7a` 已持久化 WorkRequest `25c90656-4eef-4936-b1fc-3a28ba0310fd`。JobScheduler 冷启动 PID `25825`，创建唯一 WorkflowRun `workflow-run-01d2a483-f452-4073-b3c3-e67b19f0210a`。
- Task/WorkflowRun/8 个 WorkflowStep/8 个 AgentRun 全部 `COMPLETED`，生产执行耗时 `204.977s`；32/32 ToolCall、ToolResult 和 `tool.verify` 成功，32 个 ToolResult 均为 `success=true / verificationStatus=PASSED`，`llm.request.failed=0`，没有 `Result.retry` 或复制 WorkflowRun。
- 每个 AgentRun 有 11 条 `run.execution_budget.updated`，共 88 条；`consumedMs` 最大值为 `18.431s–26.779s`，8 个 Run 的回退次数均为 0。Workflow Step 分别耗时约 `22.502s–30.323s`。

系统回收、清理与门禁：

- `ApplicationExitInfo` 定向测试输出 `supported=true / exits=16 / lowMemory=0 / fallbackSigkillCandidates=0`；新增退出来自 instrumentation 入队与取证，后台 Worker 的 PID 在执行期间保持不变，没有 Android 自主 LMK。
- 两条 Stage 60 Workflow、ScheduledTask、WorkflowRun、AgentRun、会话消息、Tool Ledger、临时 SQLite 备份和 Probe 均已定向清理；清理后 Workflow/ScheduledTask/WorkflowRun/WorkflowStep/ToolResult 计数均为 0，正式 Provider/Profile 保留。
- 删除 Probe 后重新执行 JVM、Lint、Debug APK 和 AndroidTest APK 门禁，并重新安装/启动正式 Debug APK；完整 instrumentation 不重复执行，以避免清空正式 Provider/Profile，历史完整基线仍为 JVM `420/420`、仅 Redmi instrumentation `141/141`。

当前结论：

- 第 60 阶段证明普通 WorkManager 在 instrumentation 退出、无前台 Activity 驻留的冷启动场景下，可以用单一持久化 WorkRequest/Run 链完成约 205 秒、32 次只读工具调用，并保持预算单调和验证完整。它没有刷新第 59 阶段的 229 秒最长耗时，也没有触发自然 LMK，因此仍不引入 Foreground Service，不扩大旧执行栈恢复能力，设备工具继续禁止进入 Workflow/后台自动化。

## 2026-07-22 Redmi 熄屏冷启动后台成功 Workflow（第 61 阶段）

目标与边界：

- 在第 60 阶段“Probe 立即退出、JobScheduler 冷启动”的基础上，入队后主动让屏幕进入普通休眠，而不是强制 Doze。取证期间持续确认 `mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`，没有内存压力、强杀或前台 Activity 保活。
- Workflow 继续使用产品上限 8 步、每步 4 个应用内只读工具，并要求较完整的可信总结以增加真实模型耗时。设备工具、写工具和审批动作继续禁止进入后台 Workflow。

执行与结果：

- Probe 在 `0.275s` 内完成，创建 Workflow `workflow-2140ab42-a973-41b0-a13a-b1aa26afda69`、ScheduledTask `scheduled-task-abf8a1ce-ce3d-4c4b-974a-a0188defcac8` 和 WorkRequest `f62da1cc-185e-4733-8566-bc39d54c4e9b`，持久化关联后退出；原 PID 随 instrumentation 结束消失。
- ScheduledTask 计划时间为 `1784690482730`，JobScheduler 在熄屏状态下延迟 `159.479s` 冷启动 PID `26797`，创建唯一 WorkflowRun `workflow-run-96b632ac-4de8-46f5-9dad-c88c0ef3a612`。Worker 执行期间屏幕始终 `Asleep`、PID 保持不变。
- Task/WorkflowRun/8 个 WorkflowStep/8 个 AgentRun 全部 `COMPLETED`，生产执行耗时 `244.236s`；32/32 ToolCall、ToolResult 和 `tool.verify` 成功，ToolResult 全部 `success=true / verificationStatus=PASSED`，`llm.request.failed=0`，没有系统重试或复制 WorkflowRun。
- 每个 AgentRun 有 11 条预算快照，共 88 条；`consumedMs` 最大值 `18.283s–44.856s`，8 个 Run 的回退次数均为 0。Workflow Step 分别耗时约 `21.710s–48.743s`。

系统回收、清理与门禁：

- 熄屏状态下执行 `ApplicationExitInfo` 定向测试，结果 `supported=true / exits=16 / lowMemory=0 / fallbackSigkillCandidates=0`；新增退出均为 instrumentation 入队/取证，没有 Android 自主 LMK。
- Stage 61 Workflow、ScheduledTask、WorkflowRun、8 个 AgentRun、会话消息、Tool Ledger、临时 SQLite 备份和 Probe 均已定向清理；设备已唤醒，正式 Provider/Profile 保留，正式 Debug 应用恢复前台。
- 删除 Probe 后重新执行 JVM、Lint、Debug APK 和 AndroidTest APK 门禁，并卸载测试包、覆盖安装正式 Debug APK；完整 instrumentation 不重复执行，历史完整基线仍为 JVM `420/420`、仅 Redmi instrumentation `141/141`。

当前结论：

- 第 61 阶段把普通 WorkManager 的最长真实成功证据扩展到熄屏 `244.236s`，同时观察到非精确定时在熄屏下可能延迟约 159 秒启动。这支持继续使用普通 WorkManager 和展示计划/实际时间，不支持承诺准点执行，也没有自然 LMK 或 Foreground Service 必要性证据。旧执行栈继续 fail-closed，设备工具继续禁止进入 Workflow/后台自动化。
