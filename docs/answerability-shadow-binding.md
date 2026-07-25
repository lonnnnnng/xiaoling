# 答案可回答性 shadow 绑定与测量协调契约

## 目的

第 94 阶段冻结“真实 Agent 消息中的哪一份知识证据可以进入 answerability shadow”的只读绑定；第 95 阶段补齐默认关闭的真实 Judge 测量协调、失败/重试和可选最小化持久化边界；第 96 阶段把生产 Provider adapter、答案保存后的异步 caller、设备偏好和旁路 notice 接入真实前台 Agent 消息流。三阶段都不把 shadow 结果当成生产答案决策，也不改变原答案或知识引用。

## 候选来源

`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 按执行列表从后向前寻找最近一条同时满足以下条件的执行：

- `toolName == "knowledge.search"`；
- `success == true`；
- `verificationStatus != FAILED`；
- `rawResult` 非空；
- `knowledgeReferences` 非空。

没有 `toolExecutions` 的旧消息会把顶层工具字段投影成一个执行项。候选保存来源 Run ID、原问题、原始检索正文和引用列表；空 Run、其他工具和不完整结果不会被猜测成知识证据。

## 离线观测与线上测量

- `KnowledgeAnswerabilityAssessment` 只定义 Judge 决策所需的共享字段和冻结门禁决策语义。
- `KnowledgeAnswerabilityObservation` 用于 calibration/validation，必须携带人工真值 `label`。
- `KnowledgeAnswerabilityShadowMeasurement` 用于真实 Agent Run，只携带 `sourceRunId`，不得伪造人工真值。
- 两者复用同一个候选原文证据匹配和字段映射；线上 measurement 不得进入离线标签统计。

## 冻结绑定

`KnowledgeAnswerabilityFrozenBinding` 必须锁定：

1. calibration/validation 均使用 `KnowledgeAnswerabilityDatasetIdentity`，并绑定同一 Judge provider/model/configuration fingerprint/prompt version；
2. 非空且互异的 calibration 与 validation dataset version；
3. 已冻结的 `KnowledgeAnswerabilityGate`。

消息 shadow 只允许已通过的 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`。覆盖率特征族尚未通过独立证据，因此即使其它输入完整也返回 `UNKNOWN`。

## 默认关闭的测量协调

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一入口：

1. `DISABLED` 直接返回 `SKIPPED / DISABLED`，不调用 Judge，也不持久化；
2. 非 `DIRECT_FOREGROUND` 来源返回 `SKIPPED / UNSUPPORTED_ORIGIN`；
3. 进入 Judge 前复制候选引用，保证首调、重试、绑定和持久化针对同一证据快照；
4. 候选不完整或缺少冻结绑定时不调用 Judge，直接形成保守未知绑定；
5. 成功响应先转换为无标签 measurement，再使用响应中的实际 Judge identity 完成绑定。

## 状态与失败语义

| 条件 | 状态 | 决策 | 用户提示 |
| --- | --- | --- | --- |
| identity、Run、measurement 和允许的 gate 全部一致 | `BOUND` | 按既有策略 `ACCEPT/REJECT/UNKNOWN` | 复用 shadow presentation |
| 覆盖率特征族 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| identity 漂移或缺失 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| measurement 缺失或 `sourceRunId` 不对应来源 Run | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| 来源 Run、候选正文或引用不完整 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| Judge 传输/协议终败，未形成 measurement | 观测 `UNKNOWN` | `UNKNOWN` | 不投影 notice |

绑定开始时会复制候选及引用并保留原顺序，调用方后续修改可变 List 不会改变同一绑定结果。没有 measurement 时 `observedAt=null`，不会伪造观测时间；Judge identity 漂移时 measurement 仍可保留，但绑定固定未知。协调器仍保留失败分类和未知绑定供旁路审计，生产 publisher 只投影 `COMPLETED` 观测的 notice，不把终败结果猜测成用户提示。结果始终 `enforcementApplied=false`。未知不会被转化成拒绝，也不会删除、替换或重排答案。

## 第 96 阶段生产接线

- `OpenAiKnowledgeAnswerabilityJudge` 固定使用 Responses、非流式、关闭 reasoning summary、`temperature=0`、`topP=1` 和 `maxTokens=220`；adapter 单次只发一个请求，不自行重试，响应只通过严格 codec 解码。
- Judge identity 从实际配置的 Provider ID、模型和 Base URL 指纹派生，不复制调用方的期望 identity。生产冻结身份为 `redmi-provider-compatibility / gpt-5.5 / 03c4b0dbea6451654f254df8ad45e640b25ea4496596b63f82cceb190c51cf6d / stage92-answerability-json-v1`；用户开关和实际身份必须同时满足，任何漂移都在请求前保持关闭。
- Judge 请求携带用户问题和知识候选，Debug 构建也逐请求设置 `httpDebugLoggingEnabled=false`，关闭请求、响应和流事件的全部 HTTP Debug 日志；鉴权、User-Agent 和兼容 Header 继续复用统一 Provider client。
- `AgentAnswerabilityShadowPublisher` 只接收前台直接 Agent。答案先进入 UI，并启动正常会话保存；旁路 sibling Job 等待对应保存 Job 成功后再调用协调器。保存失败、保存被新快照取消、Workflow 来源或 Judge 最终失败都不会进入 Agent 主失败分支，终败不发布 notice。
- notice 使用 `messageId -> KnowledgeAnswerabilityUserNotice` 的进程内映射投影到 `KnowledgeReferencesContent`。它不写回 `ChatMessage`、`VerifiedAgentContext`、`MessagePart` 或知识引用；会话删除或重载时裁剪悬空消息键，进程重建后自然清空。

## 重试与取消

- 瞬时网络、限流、服务端和协议失败允许一次受控重试，总尝试次数最多两次。
- 认证、普通请求、身份、候选和未知异常不重试。
- 重试分类封装在协调器内部，调用方不得叠加外层重试扩大请求次数。
- `CancellationException` 原样传播；取消不生成 `UNKNOWN`，也不产生 shadow 持久化记录。

## 可选持久化与隐私

`KnowledgeAnswerabilityShadowObservationStore` 是可选端口。记录只包含：

- 来源 Run 和已持久化消息 ID；
- 对来源 Run、问题、候选正文和稳定引用身份计算的 SHA-256 指纹；
- 消息、Run、候选指纹和 Judge identity 共同计算的幂等键；
- Judge identity、尝试次数、测量/绑定状态、决策、失败分类和记录时间。

记录不得保存候选正文、模型原始响应或引用正文。Store 普通失败只把 `persistenceStatus` 标记为 `FAILED`，不改变绑定或原答案；Store 取消继续传播。

第 96 阶段生产接线显式使用 `store=null / persistenceMode=NONE`。因此当前不创建 shadow Room 表、不升级 Room Schema，也不把 notice 或 Judge 原始响应写入持久化层。

## 当前不做的事

生产 Provider adapter、答案保存后的 caller、默认关闭的设置入口和 `KnowledgeReferencesContent` notice 已接入。当前仍不做 Room schema/store、notice 跨进程恢复、普通聊天、Workflow、后台 Worker 或生产 enforcement；也不因 Judge 结论删除引用、改写答案或改变检索。后续若要持久化或执行拒绝，必须重新评审隐私、生命周期、身份和独立验收，不得从当前旁路 UI 自动扩权。

## 验收

- `KnowledgeAnswerabilityShadowObservationCoordinatorTest`：`14/14`；
- 第 96 阶段新增 adapter、身份门禁、publisher、notice 投影、偏好与 Compose 契约；
- 完整 JVM：`593/593`；
- Lint、Debug APK、AndroidTest APK：通过；
- Redmi `wsvwypiz7xwslvl7` 默认完整 `AndroidJUnitRunner`：`OK (191 tests)`；
- 显式生产 adapter Provider 探针：`OK (1 test)`；设置/偏好/notice 定向组合：`OK (3 tests)`；
- 实际 Provider ID 下的 calibration/validation 复验：`12 + 12` 条、失败 `0 + 0`、两类既有特征族继续通过；冻结 `minimumConfidence=0.85` 未使用重复采集回调；
- Pixel_9 和其他模拟器未参与本阶段验证。
