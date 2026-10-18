# KMP context snapshot 协议与 codec

第 291 阶段第二片，继续第 7 点跨平台协议固化。第一片为[共享可读证据](kmp-readable-evidence.md)。本片将输入 DTO、严格 JSON codec、容量常量与记录摘要的规范字节下沉 commonMain；Android 保留原持久 encoder 和 SHA-256。shared contract 仍为 v4，context schema 仍为 v1，Room v44、发布版本 `0.1.22 (23)` 不变。

## 输入与 wire

`SharedAgentLlmPhase / SharedAgentContextProjection / SharedAgentContextMessage / SharedAgentContextInput` 由 commonMain 持有，Android 原类型名通过 typealias 引用。输入只表达实际模型 adapter 的 system/user 文本、模型与 Provider 身份、API 模式、已验证 ToolCall IDs、投影与附件计数；没有鉴权字段、附件字节或模型响应。

shared encoder 按下列顺序输出紧凑 JSON，common golden 固定字段和值：

| 字段 | v1 约束 |
| --- | --- |
| schemaVersion | JSON 整数词法 `1` |
| phase | `PLAN / SUMMARIZE` |
| model | 非空白，最多 200 UTF-16 code units |
| providerId | 显式 null 或非空白 string，不能缺失 |
| apiMode | `CHAT_COMPLETIONS / RESPONSES` |
| projection | `EXACT_TEXT / DEVICE_INPUT_REDACTED` |
| imageCount / documentCount | JSON 整数词法 `0 / 1`，合计最多 1；附件只允许 RESPONSES |
| verifiedToolCallIds | 最多 32 个唯一非空白 string，每项最多 200 UTF-16 code units |
| messages | 恰好两条，依次为 system/user；每条只有 role/content，正文必须是 string，可为空 |

顶层必须恰好为上述十字段。未知字段、版本或枚举、错误类型、重复键（含转义后相同键）、非标准 JSON、尾随正文、整数的字符串/小数/指数/负零表示均拒绝。标准 JSON 空白可读取，但不参与重写历史。`StrictJsonObjectSyntax` 在建树前扫描，嵌套深度最多 32。

`DEVICE_INPUT_REDACTED` 的省略规则、Tool Ledger 关联与附件权限仍由 Android adapter/Room 执行；共享字段不能独立证明正文已脱敏、工具已验证或具备恢复授权。原[输入快照](agent-context-snapshot.md)、[提取压缩](agent-context-compression.md)和[恢复契约](agent-context-recovery.md)继续生效。

## 容量、Unicode 与 Android 兼容

单帧 JSON 最多 `1,048,576` UTF-8 bytes；每 Run 的 64 帧/16 MiB 和最多 32 个工具身份常量共享，累计限额与存储事务仍在 Android。扫描器字符预算显式传单帧上限，避免第一片默认的 1,000,000 字符误拒绝合法 ASCII 帧。编码采用严格 UTF-8，孤立 surrogate 拒绝，不能用替换字符改变身份或正文。

Android encoder 保留原 JSONObject 的字段构造顺序和转义样式；decoder 先使用 shared，再沿旧 encoder 检查重编码容量。shared 紧凑 JSON 在限额内，不保证 Android 旧转义后也在限额内。例如大量 `</` 的紧凑帧达到 1 MiB 时，Android 原 encoder 的额外转义仍会导致拒绝，不能为接收另一种编码放宽原 Room 边界。

shared decode 格式错误统一返回不含正文或 cause 的 `IllegalArgumentException("上下文快照格式无效")`，使 Room 将确定格式损坏分类为 `INVALID`。这不改变数据库 IO 与协程取消传播。正常旧 encoder 输出可读取；此前 JSONObject 宽松解析的方言、重复键和整数强转不是兼容承诺。

同 Step 幂等依赖原 `payloadJson` 字符串逐字相等。带合法空白的旧帧可读取，但新 encoder 输出不同原文时不能覆盖同 Step。读取不 trim、不解析后回写，不重算历史摘要，也不静默修复损坏记录。

## 摘要规范字节

每个字段先严格编码为 UTF-8，前置四字节大端长度，再按顺序连接。序号与压缩版本使用十进制文本，均须大于零；Run/Step 身份须非空白。

| 记录 | 字段顺序 |
| --- | --- |
| 普通帧 | `xiaoling-agent-context-v1 / runId / stepId / sequence / 原 payloadJson` |
| 压缩帧 | `xiaoling-agent-context-compressed-v1 / runId / stepId / sequence / compressionVersion / 原 payloadJson` |

`recordHashBytes` 只生成规范字节；Android `MessageDigest(SHA-256)` 消费它并保留原 lowercase hex。摘要始终绑定原 payload，包括空白、转义和字段顺序。压缩来源自身的摘要、来源/派生关联和恢复锚点仍沿原 Android 实现；没有迁移 Room 或新增 iOS SHA actual。

独立 Python golden 固定 `run-中文 / step😀 / 17 / {"raw":"中😀"}\n`，普通摘要为 `0b0fccba5f8244dfc35e412fbe9bbfc8c72ba040494732def8646af790fbba66`，压缩版本 3 摘要为 `6e251b026c7cb372e038431f396568c5a057a385e776dec6ba59782bf89acad4`。Java DataOutputStream 参考实现同时核对旧长度前缀，避免新函数生成旧夹具后自证兼容。

## 平台与验证边界

| 能力 | Android | iOS |
| --- | --- | --- |
| context DTO、JSON codec、严格 UTF-8、摘要规范字节 | shared 实现，生产原接口接入 | commonMain 编译及 Simulator codec 测试 |
| 原持久 encoder、SHA-256、Room/压缩/恢复 | 原实现，旧原文保持 | 尚无 actual |
| Provider 请求、前台 Agent 产品入口 | 原 Android 实现 | 尚无 HTTP actual 或 iOS App |

common suite 覆盖 golden、固定形状、整数词法、重复键、Unicode、精确 1 MiB、摘要 domain/长度前缀与原文绑定。共享 JVM/Redmi adapter 夹具比较旧 JSONObject 输出、独立摘要及转义容量。UUID Room 直接植入旧格式帧，验证读取、同 Step 幂等、空白不重写，以及有效原文 hash 下重复键为 INVALID、恢复 action 为零。

既有快照/压缩/恢复与迁移专项复验存储门禁；新旧帧测试及计数以[验证报告](verification-report.md)为准。闭库重开、loopback 模型响应和迁移夹具不代表自然 LMK 或真实模型恢复质量；iOS codec 测试不代表 iOS 产品运行时。下一片继续 MCP schema，随后 Remote Channel wire、完整 Tool result codec，再补 iOS actual。

UTF-8 官方依据：[Kotlin encodeToByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/encode-to-byte-array.html) 的 `throwOnInvalidSequence` 严格模式；JSON tree 沿第一片已核实的 kotlinx-serialization-json 1.9.0。
