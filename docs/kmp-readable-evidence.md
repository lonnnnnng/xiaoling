# KMP 可读工具证据与 JSON codec

第 291 阶段第一片，第 7 点的协议固化起点。当时 `AgentRuntimeContract.VERSION=4` 在工具结果末尾新增可空 `readableEvidence`；旧调用方默认仍为 null。第五片[完整结果 codec](kmp-tool-result.md)已升为 v5。Room 保持 v44，发布版本保持 `0.1.22 (23)`。本片没有 iOS App 或平台 actual。

## 共享边界

commonMain 持有 `SharedToolReadableEvidenceKind / SharedToolReadableEvidence / SharedBrowserDomAction / SharedBrowserActionReceipt` 和 `SharedToolReadableEvidenceCodec`。Android 原类型名通过 typealias 引用同一模型，原 `ToolReadableEvidenceCodec` 保留 JSONObject/String 接口。RunEvent、VerifiedContext 和 Room Tool Ledger 的字段名与嵌入位置不变，旧持久正文不重写。

`ToolExecutionResult.toSharedAgentResult(expectedToolCallId)` 投影可读证据，并与 typed verification、执行 receipt 一起核对当前调用。任一 ID 错配返回失败、`verified=false / VERIFICATION_TOOL_CALL_MISMATCH`，不携带旧页面证据。可读证据不生成审批许可、PASSED 或 COMMITTED；Android 当前 Registry/Runtime 仍负责真实来源、动作目标、审批、Profile 与前台门禁。

## Wire 与兼容

顶层固定写入 `kind / toolCallId / snapshotId / contentHash / sourceRef`，可选 `browserAction`。保持旧格式没有顶层 schemaVersion；未知 kind 拒绝。旧证据没有动作仍可读取，顶层额外字段保持读取兼容，重新编码仅输出已知字段。

动作固定 `schemaVersion=1`，且必须恰好包含 `schemaVersion / actionId / toolCallId / action / sessionId / inputSnapshotId / outputSnapshotId / ref / inputContentHash` 九字段。未知版本、动作、缺失或额外字段、显式 null、错误类型及调用/输出快照错配拒绝整份证据，不能降级成普通页面。

字段字符串不再采用 org.json 的 optString 类型强转；仅接受非空白 JSON string。schemaVersion 仅接受整数词法 `1`，字符串 `"1"`、小数 `1.0`、指数 `1e0` 均拒绝。此前宽松解析的 JSON 方言不是兼容格式。

模型校验保持原 Android 约束：页面 snapshot 1–64 字符、sourceRef 1–512 字符且不含 query/fragment/账号分隔符、内容 SHA-256 为 64 位小写 hex；动作 ID 为 32 位 hex，session/前后 snapshot 为 16 位 hex且快照不同，ref 1–128 字符，动作调用 ID 最多 256 字符，输入 SHA-256 为 64 位小写 hex。hash 由 Android reader 按原页面规范计算，shared 只检查字段格式，不计算摘要或独立证明内容真实性。

shared encoder 要求输出能由同一 codec 完整回读，直接模型的超限、孤立 surrogate 或空白身份在写入前拒绝；按固定字段顺序输出紧凑 JSON，以 common golden 向量约束 Android/iOS 的输出。Android encoder 继续将同样字段交给原 JSONObject 输出样式；持久 wire 兼容基于解码字段和值，不承诺不同 JSON 实现的字节顺序相同。context snapshot 的原始正文、长度前缀和 SHA-256 不在本片迁移范围。

## 严格 JSON 与容量

`StrictJsonObjectSyntax` 从现有 Android 扫描器下沉 commonMain，Android `StrictJsonObject` 仍先扫描再返回 JSONObject。shared codec 扫描后使用 `kotlinx-serialization-json:1.9.0` 的 JSON tree，不需要 serializer 编译插件。

- 输入最多 1,000,000 UTF-16 code units，嵌套深度沿原扫描器保持最多 32；这个边界不是 1 MiB UTF-8。
- 必须是完整标准 JSON object，拒绝尾随正文、多个对象、注释、单引号、未加引号键和尾逗号。
- 在建树前按解码后的键名拒绝重复键，包括转义后重复及嵌套对象，避免后一个身份覆盖前一个身份。
- 字符串拒绝未转义控制字符和孤立 UTF-16 surrogate；有效 emoji/中文和标准转义保留，避免 UTF-8 持久化替换身份文本。

调用方已持有 JSONObject 时，原始输入的重复键或方言信息已被该解析器丢失；兼容入口转成 String 后仅能校验当前对象。没有把完整 RunEvent/VerifiedContext/Room 外层解码改成严格原文解析，也不能声称这三个入口的所有外层字段已实现重复键拒绝。

## 能力矩阵与验收

| 能力 | Android | iOS |
| --- | --- | --- |
| Agent contract、目录、Run session、可读证据模型与 codec | shared 实现，生产证据入口接入 | commonMain 可编译、Simulator contract/codec 测试；验证结果见报告 |
| context snapshot DTO/codec/摘要规范字节 | 第二片接入 shared；原 encoder/Room/SHA-256 保留 | common 编译与 Simulator codec 测试；尚无存储/SHA actual |
| MCP descriptor/有界 validator/raw codec | 第三片 shared 算法，Android 原 JSON 兼容端口 | common 编译与 Simulator 测试；parser 边界见[MCP 契约](kmp-mcp-schema.md) |
| Remote Channel DTO/wire/canonical bytes | 第四片 shared codec，平台验签/入箱/许可保留 | common 编译与 Simulator；无 crypto/storage actual |
| 完整 Tool result DTO/v1 codec | 第五片 shared 私有帧及 Android 双向入口；模型投影去除幂等键 | common 编译与 Simulator；无平台执行 actual |
| Provider/HTTP、MCP session/transport、Remote Channel 验签与入箱 | 现有 Android 实现 | 尚无 actual |
| Workspace、Browser reader、Room、前台 Agent 产品入口 | 现有 Android 实现与相应门禁 | 尚无 actual 或 iOS App |
| Phone Use、Root/Shizuku、后台虚拟屏 | 按既有 Android 权限边界 | 不承诺能力对齐 |

同一 common suite 在 shared Android Debug/Release 与 iOS Simulator 执行固定旧 wire、动作 golden、Unicode/转义/顺序兼容、错误类型/版本/绑定、损坏拒绝和容量向量。App JVM 与 Redmi 共用 adapter 契约，浏览器专项复核 Runtime/审批/Room reopen/取消。构建和设备实际结果以[验证报告](verification-report.md)为准，证据目录 `outputs/verification/stage291-kmp-codec/`。

第二/三片已继续固化[context snapshot codec](kmp-context-snapshot.md)和[MCP schema codec](kmp-mcp-schema.md)；第四片已固化[Remote Channel wire](kmp-remote-channel-wire.md)，第五片已补齐[完整 Tool result codec](kmp-tool-result.md)；下一切片进入 iOS 本地 workspace、HTTP、只读浏览器和前台 loop。第 7 点或 iOS 产品能力尚未整体收口。

官方正文：[JSON tree 解析 API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json/parse-to-json-element.html)、[serialization 1.9.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0)。版本固定 1.9.0，上游要求 Kotlin 2.2，本项目 Kotlin 2.3.20 的实际兼容以本轮编译与测试为证据。
