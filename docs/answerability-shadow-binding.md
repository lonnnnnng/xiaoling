# 答案可回答性 shadow 绑定契约

## 目的

第 94 阶段只冻结“真实 Agent 消息中的哪一份知识证据可以进入 answerability shadow”这一只读边界。它把已有 `VerifiedAgentContext` 与第 92 阶段的 Judge 观测、第 93 阶段的用户提示连接起来，但不把 shadow 结果当成生产答案决策。

## 候选来源

`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 按执行列表从后向前寻找最近一条同时满足以下条件的执行：

- `toolName == "knowledge.search"`；
- `success == true`；
- `verificationStatus != FAILED`；
- `rawResult` 非空；
- `knowledgeReferences` 非空。

没有 `toolExecutions` 的旧消息会把顶层工具字段投影成一个执行项。候选保存来源 Run ID、原问题、原始检索正文和引用列表；空 Run、其他工具和不完整结果不会被猜测成知识证据。

## 冻结绑定

`KnowledgeAnswerabilityFrozenBinding` 必须锁定：

1. calibration/validation 均使用 `KnowledgeAnswerabilityDatasetIdentity`，并绑定同一 Judge provider/model/configuration fingerprint/prompt version；
2. 非空且互异的 calibration 与 validation dataset version；
3. 已冻结的 `KnowledgeAnswerabilityGate`。

消息 shadow 只允许已通过的 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`。覆盖率特征族尚未通过独立证据，因此即使其它输入完整也返回 `UNKNOWN`。

## 状态与失败语义

| 条件 | 状态 | 决策 | 用户提示 |
| --- | --- | --- | --- |
| identity、Run、观测和允许的 gate 全部一致 | `BOUND` | 按既有策略 `ACCEPT/REJECT/UNKNOWN` | 复用 shadow presentation |
| 覆盖率特征族 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| identity 漂移或缺失 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| 观测缺失或 `caseId` 不对应来源 Run | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| 来源 Run、候选正文或引用不完整 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |

绑定开始时会复制候选及引用并保留原顺序，调用方后续修改可变 List 不会改变同一绑定结果。没有观测时 `observedAt=null`，不会伪造观测时间；已有观测的时间继续保留。结果固定 `enforcementApplied=false`。未知不会被转化成拒绝，也不会删除、替换或重排答案。

## 当前不做的事

本阶段不调用 Judge Provider、不写 Room、不扩展消息 schema、不接入 `KnowledgeReferencesContent`，也不改变普通聊天、Workflow、后台 Worker 或生产 enforcement。下一阶段需要先确定观测生成时机、重试和持久化边界，再讨论默认关闭的 UI 接线。
