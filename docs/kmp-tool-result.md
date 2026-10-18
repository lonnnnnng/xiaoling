# KMP 完整 Tool result 协议与 codec

第 291 阶段第五片，继续第 7 点协议固化。`AgentRuntimeContract.VERSION=5` 补齐结果引用、恢复失败和私有回执幂等键；新增完整结果 schema v1。Room v44、发布版本 `0.1.22 (23)` 保持。本片没有 iOS actual 或 iOS App；第六片已继续提供[iOS 本地 workspace](ios-workspace.md)平台实现，下一片 HTTP client，随后只读浏览器和真实前台 loop。

## 模型与交换边界

commonMain 的 `SharedToolExecutionResult` 保留原六个参数顺序，在末尾增加默认空的 `memoryIdsUsed / knowledgeReferences` 和默认 null 的 `recoveryFailure`。`SharedToolExecutionReceipt` 保留原三个参数顺序，在末尾增加默认 null 的 `idempotencyKey`。`SharedKnowledgeReference / SharedToolRecoveryFailure` 共用原 Android 字段、校验及中文错误文案；Android 原类型名通过 typealias 使用它们。源码和手工 JSON 兼容，不承诺旧 JVM 类名或外部已编译消费者的二进制 ABI。

`SharedToolResultFrame(toolCallId, result)` 只冻结单次工具执行结果，不含 Run、toolName、duration、replaySafety、审批、child checkpoint、模型响应或 content 内部业务 schema。`SharedToolExecutionResultCodec` 与 Android `ToolExecutionResultCodec` 提供完整私有帧双向交换入口。当前 Android 原 Executor/Registry、RunEvent、可信上下文及 Room 持久路径继续使用原接口；没有把历史外层格式重写成新帧。

完整私有帧保留回执幂等键，不能直接送模型、消息或可信上下文。`toSharedAgentResult(expectedToolCallId)` 和 direct/Workflow/child 的 shared execution 投影去除 `idempotencyKey`，同时保留实际 memory IDs、完整知识引用和恢复失败。任一非空 typed 身份、receipt 身份或 readable 身份错配，投影失败并移除旧正文、引用、恢复失败、receipt/readable。现有生产投影调用均提供当前 call ID；无参投影只做类型转换及幂等键脱敏，不能证明调用绑定。

## v1 wire

顶层恰好为 `schemaVersion / toolCallId / result`。版本只接受 JSON 整数词法 `1`；调用 ID 为非空白 string，最多 256 UTF-16 code units。decode 必须由调用方传入 `expectedToolCallId` 并精确相等，不能从输入自行信任预期身份。

result 必须恰好包含以下九个字段。所有可空字段都必须出现并显式写 null；空列表不能省略。shared encoder 按下表顺序输出紧凑 JSON，固定 literal golden 约束字节与值。

| 字段 | v1 约束 |
| --- | --- |
| success | JSON boolean，不接受字符串或数字强转 |
| content | JSON string，可为空，不解析其中的工具业务格式 |
| verified | JSON boolean 或 null |
| memoryIdsUsed | 最多 256 个非空白 string，保留顺序及重复项 |
| knowledgeReferences | 最多 256 个完整引用对象，保留顺序及重复项 |
| executionReceipt | null 或恰好含 toolCallId、operationId、idempotencyKey、status 的对象 |
| verificationEvidence | null 或恰好含 status、toolCallId、reasonCode 的对象 |
| recoveryFailure | null 或恰好含 code、reason、suggestedAction 的对象 |
| readableEvidence | null 或[可读证据子协议](kmp-readable-evidence.md)对象 |

receipt 的 toolCallId/operationId 非空白，幂等键为 null 或非空白 string，status 为 `COMMITTED / NOT_COMMITTED / UNKNOWN`。typed status 为 `PASSED / FAILED`，toolCallId/reasonCode 为 null 或非空白 string。receipt、非空 typed ID 和 readable ID 必须属于外层调用；历史 typed 证据缺少 ID 仍保存 null，不能猜造身份。

知识引用恰好含 `retrievalId / documentId / documentName / documentRevision / chunkId / chunkSequence / startOffset / endOffset`。字符串身份非空白；revision > 0、sequence ≥ 0、start ≥ 0、end > start；整数只接受规范非负十进制词法且在 Int 范围内，字符串、小数、指数及负零拒绝。恢复失败三字段均为非空白 string。

新完整帧的 readable 顶层只允许五个固定字段和可选 browserAction；子协议的旧顶层扩展容忍只留在原入口。动作出现后必须完整通过原 v1 动作校验，显式 null、版本/调用/输出快照错配或损坏均拒绝整帧，不能降为普通页面。

## 容量与失败语义

整帧原文最多 `1,048,576` UTF-8 bytes，包含 JSON 空白及转义；这是新增交换 API 的限额，不限制既有 Executor、投影、RunEvent 或 Room 路径。调用/列表限额同样仅约束新帧。readable 子对象还沿原子 codec 的 1,000,000 UTF-16 code units 门禁。

严格 UTF-8 编码和原文 JSON 扫描先于建树，拒绝重复键（含转义后相同键）、孤立 surrogate、非标准 JSON、尾随正文及深度超过 32 的嵌套。未知/缺失字段、错误类型、坏引用或坏证据均拒绝整帧，不丢弃单项、不填默认成功。decode 统一抛出无正文、无 cause 的 `IllegalArgumentException("工具结果帧格式无效")`。encode 同样要求整帧能够无损回读，不能写入两端解释不同的结果。

codec 只保存事实，`success / verified / typed status / receipt status` 独立往返，包括矛盾组合；这不证明证据真实或允许执行。页面、动作、typed PASSED 和 COMMITTED 各自的 Runtime 当前调用/审批/来源/参数门禁，以及恢复定义指纹、账本、预算和幂等检查仍由 Android 原实现负责。新结果帧本身不包含 replaySafety，也不授予重放或原地恢复权限。

## 历史与验证

原 RunEvent 的 tool.result 外层保留 toolName/duration/replaySafety 和 receipt 幂等键，typed verification 与 recoveryFailure 仍由各自事件承载。旧 memory 字符串化数组、坏知识引用逐项丢弃和缺 replaySafety 时 `RESTART_REQUIRED` 保持。原 VerifiedContext 的可信 receipt 不含幂等键，旧 wire 和宽松历史嵌套行为保留。Room 分列与 schema 未迁移，旧记录未回写；新 codec 不把旧外层 wire 冒认为完整 v1 帧。

| 能力 | Android | iOS |
| --- | --- | --- |
| 完整结果 DTO、v1 私有帧、严格解码 | shared 实现，Android 双向入口及脱敏投影 | common 编译及 Simulator codec 测试 |
| 可读/context/MCP/Remote 协议与 codec | 前四片 shared，平台入口及历史格式保持 | common 编译及 Simulator 测试 |
| 产品来源/审批/恢复权限、Room/Provider | 原 Android 实现 | 尚无平台实现 |
| workspace 文件与 shared loop | 原 Android 实现 | 第六片原生文件/宿主新建/前台只读 Executor；scripted loop 联合，无真实 UIKit owner |
| HTTP/只读浏览器/前台 Agent 产品链 | 原 Android 实现 | 后续推进，不承诺 Phone Use/Root/Shizuku/后台对齐 |

common suite 覆盖独立 literal、全部字段/状态组合、旧 typed 无身份、浏览器动作、调用错配、损坏/类型/整数/Unicode、每类 256 条及 ASCII/中文精确 1 MiB。JVM/Redmi 共用 Android 契约比较独立 JSONObject oracle，复核私有帧与各入口脱敏、历史 RunEvent/可信上下文与容量隔离。Runtime/恢复和 Redmi Room 专项复验原安全边界；实际计数和构建/语料/保护结果见[验证报告](verification-report.md)，证据目录 `outputs/verification/stage291-kmp-tool-result/`。

本片不提供 iOS 产品运行时、真实模型/公网、自然 LMK/Doze/系统重启或正式 Release 证据。第 7 点仍进行中，前六点长期独立待办继续单独追踪。
