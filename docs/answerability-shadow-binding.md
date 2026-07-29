# 答案可回答性 Shadow 绑定、持久化与离线评测契约

## 当前边界

answerability Shadow 只观察用户显式开启后的前台直接 `/agent` 答案。每次显式开启最多授权一轮观测：候选存在、答案成功保存且调用前仍开启时，Publisher 先关闭并持久化开关，再进入观测协调器；候选缺失、答案保存失败或用户提前撤销不消费本次窗口。候选必须来自同一 Run 中最近一次成功、验证未失败且带稳定引用的 `knowledge.search`；冻结 Judge 身份必须与当前 Provider 配置完全一致。普通聊天、Workflow、后台 Worker、候选缺失、身份漂移或答案保存失败都不得请求 Judge 或写入匿名账本。

当前源码和 Redmi 开发数据使用 Room v33。生产请求通过 `KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL` 写入 `knowledge_answerability_shadow_observations`；旧阶段的 `store=null / persistenceMode=NONE` 只属于第 96 至 101 阶段的历史事实，不再代表当前实现。Shadow 默认关闭，notice 仍只存在于当前进程，`enforcementApplied=false`，production enforcement 继续关闭。

第 102 阶段只冻结版本化离线评测导出类型，没有接入 JSON codec、UI 或 SAF 出口。第 103 阶段在 Redmi 形成 Room v33 的第一条间隔真实记录；第 104 阶段在完整清理和进程重启后形成第二条短间隔记录，并修复冷启动摘要被默认零值覆盖的问题。第 105 阶段把持续开关收紧为单次显式采样窗口；第 106 阶段只把匿名账本的最早/最新时间与跨度投影到设置页。当前两条记录仍不足以作为 calibration/validation 数据，也不能据此启用生产拒绝。

## 候选来源

`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 按执行列表从后向前寻找最近一条同时满足以下条件的执行：

- `toolName == "knowledge.search"`；
- `success == true`；
- `verificationStatus != FAILED`；
- `rawResult` 非空；
- `knowledgeReferences` 非空。

没有 `toolExecutions` 的旧消息会把顶层工具字段投影成一个执行项。候选在进程内携带来源 Run、原问题、原始检索结果和引用列表，用于 Judge 和冻结绑定；这些内容不得进入 v33 匿名账本。空 Run、其他工具和不完整结果不会被猜测成知识证据。

## 离线观测与线上测量

- `KnowledgeAnswerabilityAssessment` 定义 Judge 决策所需的共享字段和冻结门禁语义。
- `KnowledgeAnswerabilityObservation` 用于 calibration/validation，必须携带人工真值 `label`。
- `KnowledgeAnswerabilityShadowMeasurement` 用于真实 Agent Run，只携带 `sourceRunId`，不得伪造人工真值。
- 离线 observation 与线上 measurement 复用候选证据匹配和字段映射，但线上数据不得直接进入离线标签统计。

## 冻结绑定

`KnowledgeAnswerabilityFrozenBinding` 必须锁定：

1. calibration/validation 使用同一 Judge provider、model、configuration fingerprint 和 prompt version；
2. calibration 与 validation 的 dataset version 非空且互异；
3. `KnowledgeAnswerabilityGate` 已冻结。

消息 Shadow 只允许已通过独立证据的 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`。覆盖率特征族尚未通过；身份漂移、measurement 缺失、来源 Run 不匹配或候选不完整都固定返回 `UNKNOWN`。

绑定开始时复制候选和引用并保留原顺序，调用方后续修改可变 List 不会改变同一结果。没有 measurement 时 `observedAt=null`；Judge 身份漂移时 measurement 可以保留，但绑定继续为未知。任何结果都不得删除、替换或重排答案与引用。

## 生产测量流程

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一入口：

1. `DISABLED` 返回 `SKIPPED / DISABLED`，不调用 Judge，也不持久化；
2. 非 `DIRECT_FOREGROUND` 返回 `SKIPPED / UNSUPPORTED_ORIGIN`；
3. 进入 Judge 前冻结候选引用，保证首调、重试、绑定和持久化基于同一证据快照；
4. 候选或冻结绑定不完整时不调用 Judge，形成保守未知结果；
5. 成功响应转换为无标签 measurement，并使用实际 Judge identity 完成绑定；
6. 生产 Publisher 在答案保存后使用 `OPTIONAL` 请求，Store 失败只降低旁路审计完整度，不改变答案、引用、Run 终态或主失败路径。

`OpenAiKnowledgeAnswerabilityJudge` 固定使用 Responses 非流式请求，关闭 reasoning summary，使用 `temperature=0`、`topP=1` 和 `maxTokens=220`。adapter 单次只发一个请求，不自行叠加重试；响应只通过严格 codec 解码。请求复用统一 Provider 鉴权、可配置 User-Agent 和兼容 Header，并逐请求关闭 HTTP Debug 请求、响应与流事件日志。

`AgentAnswerabilityShadowPublisher` 只接收前台直接 Agent。答案先进入 UI 并启动正常会话保存；旁路 Job 等待对应保存成功，再通过 `tryConsumeObservationWindow` 原子检查并消费一次性授权，只有成功关闭并持久化开关的调用才能进入协调器。无候选、保存失败、提前撤销或授权已被并发答案消费时不会请求 Judge；协调器开始后的取消、未知或异常仍保持开关关闭。所有旁路终态都不会进入 Agent 主失败分支。

## 状态、失败与重试

| 条件 | 状态 | 决策 | 用户提示 |
| --- | --- | --- | --- |
| identity、Run、measurement 和允许的 gate 一致 | `BOUND` | `ACCEPT / REJECT / UNKNOWN` | 复用 Shadow 呈现 |
| 覆盖率特征族 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| identity 漂移或缺失 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| measurement 缺失或来源 Run 不一致 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| 来源 Run、候选正文或引用不完整 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| Judge 传输或协议终败 | 观测 `UNKNOWN` | `UNKNOWN` | 不投影 notice |

- 瞬时网络、限流、服务端和协议失败允许一次受控重试，总尝试次数最多两次。
- 认证、普通请求、身份、候选和未知异常不重试。
- 重试分类封装在协调器内部，调用方不得增加外层重试。
- `CancellationException` 原样传播；取消不生成 `UNKNOWN`，也不产生持久记录。
- HTTP 成功但协议失败仍保留已经产生的 usage 和时延；重试成功仍保留前序失败分桶。

## Room v33 匿名账本

`RoomKnowledgeAnswerabilityShadowObservationStore` 只接受 64 位小写 SHA-256 的 `idempotencyKey` 与 `candidateFingerprint`。幂等键作为主键，重复发布只保留首次观测；插入与裁剪位于同一 Room transaction，按 `recordedAt` 最多保留最新 2,000 条。

表只允许保存：

- SHA-256 幂等键与候选摘要；
- Android Keystore 安装级不可导出密钥生成的 Judge HMAC-SHA-256 匿名桶；
- attempt、观测状态、绑定状态/原因、决策与稳定失败分类；
- 可空的延迟、TTFB、Prompt 字节、输入/输出/总 Tokens、usage 次数；
- 十类独立失败计数和记录时间。

表不得保存消息 ID、Run ID、问题、答案、候选正文、引用正文或身份、原始 Judge 响应、Provider ID、模型、Base URL、API Key 或其他凭据。`sourceRunId`、`persistedMessageId` 和完整 Judge identity 只用于进程内协调和生成不可逆摘要，不进入 Entity。公开配置的无盐 SHA-256 不能作为 Judge 桶；数据库单独泄露时不得反查或跨安装关联 Provider/模型组合。

未知数值必须保持 `null`，不能伪造为 `0`。最终稳定 `failureKind` 没有出现在 attempt telemetry 时补计一次，避免候选校验或意外异常从跨进程失败分布消失。Store 普通失败返回 `persistenceStatus=FAILED`，不改变绑定或原答案；Store 取消继续传播。

v32→v33 migration 只创建空表和时间索引，不扫描或回填历史消息、Run、检索审计或第 97 至 101 阶段人工统计。旧版本备份按既有迁移链进入 v33；未来高版本仍按既有拒绝策略处理。

## 进程内摘要与 notice

`KnowledgeAnswerabilityShadowSampleTracker` 使用固定上限的饱和计数，只累计样本终态、Judge attempt、延迟/TTFB、Prompt 字节、Tokens、usage attempt、稳定失败枚举和 notice 生命周期。它不持有问题、答案、候选正文、引用、原始响应、消息 ID、Base URL 或凭据，进程重启后清空。

`KnowledgeAnswerabilityShadowPersistentSummary` 与进程内摘要分离。设置页分别展示跨进程匿名累计和当前进程样本/notice；跨进程读取失败保留上一次 UI 摘要。notice 使用 `messageId -> KnowledgeAnswerabilityUserNotice` 的进程内 Map，不写回消息、可信上下文或 Room；会话删除或重载时裁剪悬空键，进程重建后自然清空。

冷启动中，跨进程摘要读取可能先于 Profile、会话和 Workflow 初始化完成。初始化整表状态重建必须通过 `mergeAnswerabilityShadowInitializationState()` 保留已经加载的跨进程摘要、当前开关和进程内摘要，不能让 `XiaoLingUiState` 默认零值覆盖真实 Room 累计。该合并不写 Room，也不恢复 notice。

## 第 102 阶段离线导出类型

`KnowledgeAnswerabilityExportEnvelope` 是版本化 sealed 契约，当前 `schemaVersion=1`：

- `KnowledgeAnswerabilityShadowExportEnvelope` 的来源固定为 `ROOM_V33_ANONYMOUS_LEDGER`，只携带非空 `KnowledgeAnswerabilityShadowExportRow`。row 保留不可逆指纹、状态/绑定/决策/失败枚举、失败分桶、可空成本和记录时间，不提供原始 Judge 或 dataset identity；`eligibleForCalibrationOrValidation()` 固定返回 `false`。
- `KnowledgeAnswerabilityAuthorizedContentExportEnvelope` 的来源固定为 `EXPLICIT_USER_AUTHORIZED_CASES`，必须携带显式离线评测授权、完整 dataset identity、正文、引用、人工 label 和 assessment；`eligibleForCalibrationOrValidation()` 返回 `true`。

两种 envelope 不能在同一对象中混装匿名 rows 与授权 cases。当前只冻结内存类型和校验规则，没有 JSON codec、文件格式、UI 或 SAF 导入导出入口，也没有自动把匿名账本升级为授权评测集的路径。

## 第 103 阶段首条 v33 真实样本

Redmi `wsvwypiz7xwslvl7` 在与第 101 阶段相隔约 69 小时的独立窗口中，从空 v33 账本开始采样。当前 README 临时导入后形成 19 个 chunks；Embedding 不可用时，查询 `Agent Run retryOfRunId` 通过词法兜底命中 5 个 chunks。显式开启 Shadow 后，前台直接 `/agent` 完成 `knowledge.search`、答案和引用保存，并触发一次真实 Judge。

停进程证据为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `9663/9655ms`，Prompt `10879B`，输入/输出/总 Tokens `2801/469/3270`，usage samples `1`；所有失败分桶为 `0`，记录时间为 `2026-07-29 07:27:36`（北京时间）。清理后知识文档/chunks 为 `0/0`，匿名账本保持 `1`，Shadow 为关闭，production enforcement 偏好不存在。

这条证据只证明当前 Provider、网络、候选和 v33 Store 下的单次旁路链路可用，不与第 97 至 101 阶段人工合计混算，也不足以进入 JSON/SAF、独立阈值校准或生产拒绝。

## 第 104 阶段第二条 v33 真实样本

第 103 阶段完整清理并重启进程后，Redmi 导入本文形成 revision `1`、`5` 个 chunks、`11.4 KB`。Embedding 不可用时，查询 `anonymous shadow calibration validation` 通过词法兜底命中 `1` 个 chunk；前台直接 `/agent` 完成 `knowledge.search`、答案和引用保存，并触发真实 Judge。

新增记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7645/7632ms`，Prompt `3967B`，输入/输出/总 Tokens `905/372/1277`，usage `1`，所有失败分桶为 `0`，记录时间为 `2026-07-29 08:13:50`（北京时间）。与首条累计为观测 `2`、Judge 身份桶 `1`、完成/绑定/接受 `2/2/2`、attempt `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`、usage `2`，所有失败分桶仍为 `0`。

两条记录只相隔约 `46` 分钟，本次只证明完整清理和进程重启后的独立短间隔链路，不视为长期分隔样本。数据库已有 `2` 条记录但设置页冷启动曾显示全零，现已修复初始化状态合并并由 JVM/Redmi 复验。清理后 documents/chunks 与 messages 为 `0`，两个 Agent Run 均保持 `COMPLETED`，Shadow 关闭，production enforcement 偏好、测试包和临时下载文件均不存在。当前窗口停止继续采样。

## 第 105 阶段单次显式采样窗口

第 103/104 阶段证明链路可用后，开关不再代表持续授权。Publisher 只有在候选存在且答案保存成功时才调用 `tryConsumeObservationWindow`；ViewModel 使用 `AnswerabilityShadowObservationWindowGate` 在同一临界区检查开关、同步关闭 UI 状态并写入 `UiPreferenceStore`。并发答案只有一条能消费同一授权并进入协调器，一次成功、未知、取消或异常观测都需要下一次新的用户显式开启。

候选缺失、答案保存失败、用户在调用前关闭或并发答案已经抢先消费时，不会误用同一采样窗口。本阶段用 `AgentAnswerabilityShadowPublisherTest` 覆盖消费顺序、候选缺失、保存失败和提前撤销，用 `AnswerabilityShadowObservationWindowGateTest` 的 20 路并发覆盖唯一消费者；聚焦 JVM 合计 `11/11`。没有新增 Room 行，也没有改变 Judge 重试、匿名账本、notice、离线评测资格或 production enforcement。

## 第 106 阶段时间窗口证据投影

`KnowledgeAnswerabilityShadowPersistentSummary` 已从 Room 聚合最早和最新 `recordedAt`，设置页现在通过纯 `projectAnswerabilityShadowWindowEvidence()` 按设备本地时区显示两端时间，并用实际毫秒差显示精确跨度。第 103/104 阶段两条记录对应北京时间 `2026-07-29 07:27:36 -> 08:13:50`，跨度 `46 分钟 14 秒`。

该投影在时间缺失或逆序时显示未知，不定义小时/天数阈值，也不返回 ready/eligible 状态。它只消除停进程查数据库的人工成本，不能把短间隔记录升级为长期分隔样本，不能触发 Judge、修改 Room、导出 JSON/SAF、进入 calibration/validation 或开启 production enforcement。聚焦 JVM `3/3` 覆盖正常跨度、单端缺失和时间逆序，AndroidTest APK 编译通过；未安装或运行设备测试。

## 当前不做的事

- 不把 Shadow 结论用于删除引用、改写答案、改变检索排序或拒绝生产回答。
- 不恢复跨进程 notice，不把普通聊天、Workflow 或后台 Worker 接入 Shadow。
- 不把匿名账本自动用于 calibration/validation，不自动生成显式授权数据。
- 不接入 JSON codec、UI/SAF 文件出口、远程上传或后台导出。
- 不因为少量成功样本开启 production enforcement；新增执行能力前必须重新评审隐私、生命周期、身份、阈值和独立验收。

## 当前验收基线

- Room v33 匿名账本实现：完整 JVM `734/734`，Lint `0 error / 51 warnings`，Debug、AndroidTest、R8 Release 与 lintVital 通过；
- Redmi Store 聚焦：HMAC 匿名桶、公开 SHA-256 不等于落库 HMAC、数据库重开、幂等写入、未知数值、稳定失败补计和第 2,001 条裁剪均通过；
- Redmi 完整 instrumentation XML：`248` 条（`236 passed / 12 skipped / 0 failed`），runner 打印 `260 tests`；
- 第 102 阶段导出契约：完整 JVM `736/736`，三类 APK、Lint、Redmi 完整 instrumentation 与文档 corpus gate 通过；
- 第 103 阶段按分级验证只执行 Debug/AndroidTest 构建、Provider 兼容单项、真实前台 Shadow 链和 Redmi 文档 corpus `OK (1 test)`；
- 第 104 阶段按分级验证执行聚焦 JVM、Debug/AndroidTest 构建、第二条真实前台 Shadow 链、冷启动设置页复验和 Redmi 文档 corpus 单项；当前 corpus 前两轮均为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充设备收尾并重新打包后的最终复验同样通过；
- 第 105 阶段按分级验证执行 Publisher `10/10` 与原子门禁 20 路并发 `1/1`，聚焦 JVM 合计 `11/11`；未新增真实样本，未运行完整 JVM、Lint、APK、Redmi instrumentation 或 Release；
- 第 106 阶段按分级验证执行时间投影 JVM `3/3` 与 AndroidTest APK 编译；未新增真实样本，未安装 APK、运行 Redmi instrumentation、完整 JVM、Lint 或 Release；
- Pixel_9 和其他模拟器未参与上述验证。
