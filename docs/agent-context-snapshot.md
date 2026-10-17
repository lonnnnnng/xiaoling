# Agent 输入序列与 context snapshot

第 287 阶段第二片，属于第 3 点 Context/Memory。第一片 [Memory CAS](memory-cas.md) 已完成，本片提供压缩的持久输入基线；第三片的来源/派生版本扩展见[提取压缩](agent-context-compression.md)，进程恢复和后台整理继续独立推进。[验证报告](verification-report.md)记录实际门禁与失败。

## 输入契约

当前生产 `OpenAiAgentLlm` 每轮构造 system/user 两条消息，规划 user 内含目标、动态工具目录、Skill 和已验证工具历史；总结 user 只有工具名、结果长度及样式提示。本片保存各次实际 adapter 文本输入，没有完整聊天往返 transcript，也不保存模型原始响应。

`proposeNextActionWithContext` / `summarizeWithContext` 在构造请求后、调用网络 client 前等待 Ledger 保存；随后发送同一份冻结消息。历史参数 Map 和知识引用 List 在等待前复制。旧 `AgentLlm` 实现默认桥接原 telemetry 接口，不支持捕获时不猜造 prompt。生产 `AgentRunUseCase` 的 Reporting Ledger 转发到 Room。

每帧绑定真实 Run、模型 Step 和从 1 连续递增的持久序号；序号在 Room 事务中分配，不采用时间戳或 shared 事件索引。快照表示请求意图，不能证明网络已经发送、响应已经收到或工具已经执行。

v1 codec 严格校验字段、版本、role 顺序、枚举、UTF-8、附件计数和唯一工具 ID。附件只保存 image/document 数量，不保存字节、文件路径；鉴权配置、URL 与 headers 不进入快照字段。文本输入本身会在 Room 明文保存，沿用应用私有数据库边界，没有增加专用加密或导出入口。

## 设备输入省略投影

历史包含 `device.type_text` 时，两条快照 message 的正文全部替换为固定省略标记，`projection=DEVICE_INPUT_REDACTED`。输入原文可经目标、system 或工具结果的任意层 JSON 回流，有限次数的字符串替换无法可靠脱敏，因此不保留局部正文。模型请求仍使用原文本；已有 Tool Ledger 保留输入指纹和长度，快照仅用 verified ToolCall IDs 关联。

无此历史时为 `EXACT_TEXT`，保存实际 adapter 文本。省略投影以及含附件的快照都不是完整重放输入；后续恢复或压缩不能把省略标记当作原始事实，也不能扩大审批和权限。

## 容量与完整性

| 边界 | v1 限额或行为 |
| --- | --- |
| 单帧 | 编码 JSON 最多 1 MiB UTF-8 |
| 每 Run | 最多 64 帧、16 MiB 编码正文 |
| 已验证工具身份 | 最多 32 个唯一 ToolCall ID |
| 附件 | 最多一个 image 或 document，仅 RESPONSES |
| 同 Step 重复保存 | 完全相同内容幂等；不同内容拒绝覆盖 |
| 超限或写入失败 | 拒绝整帧；保留旧前缀与序号，无截断或自动淘汰 |

写入前要求 Run 非终态且未请求取消，Step 为同 Run 的 RUNNING `llm.plan` 或 `llm.summarize`。输入中的工具 ID 必须与成功且 PASSED 的 ToolResult 集合一致；任何尚未验证的 ToolCall 都阻止下一次输入冻结。

`recordSha256` 使用 domain 与长度前缀绑定 Run、Step、序号和 payload。读取或追加时检查连续序号、字节、摘要、codec 和真实 Step 的 Run/phase；历史 Step 可以已经结束。损坏记录拒绝读取与追加，不默默修复或删除。SHA-256 用于事故损坏检测，不是防恶意数据库写者的签名。

## 失败与迁移

快照保存失败使用固定脱敏异常停止模型请求并让活动 Step/Run 失败。总结保存超时同样停止，冻结已消耗预算，不能进入展示样式兜底；输入已保存后的普通模型总结超时继续沿用原兜底。保存期间取消沿用 CANCELLED 结算，adapter 发送前再次检查协程状态。

Room 41→42 只创建空 `agent_context_snapshots` 表与索引，保留旧 Run/Step、Memory version 和恢复策略，不为历史 Run 猜造输入。复合主键 `(runId, sequence)`、唯一索引 `(runId, stepId)`，Run 显式删除仍级联删除快照。`AgentRunDao.upsertRun` 改用 `@Upsert`，避免同 ID 更新通过 REPLACE 删除父行而误删冻结输入。

本片没有自动快照重放、恢复资格扩张、压缩、自然 LMK 恢复或后台整理。关闭重开测试只证明持久化；loopback 验收只证明生产 adapter/Runtime/Room 的本地联动。

## 官方依据

- [Room Upsert](https://developer.android.com/reference/androidx/room/Upsert)：主键存在时更新，避免 REPLACE 的删除副作用；项目 Room 为 2.8.4。
- [Room ForeignKey](https://developer.android.com/reference/androidx/room/ForeignKey)：CASCADE 在父行删除时删除关联子行。
- [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)：迁移及导出 schema 由真机 MigrationTestHelper 验证。
