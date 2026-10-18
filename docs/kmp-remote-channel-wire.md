# KMP Remote Channel v1 wire 与签名字节

第 291 阶段第四片，继续第 7 点协议固化。`shared/commonMain/remote` 提供 message、batch、ACK、receiver request/proof 与公开 manifest 的 DTO、严格 raw JSON codec 和 canonical bytes；Android 原 envelope、signed batch、authorization 为 typealias。shared Agent contract v4、Room v44、发布版本 `0.1.22 (23)` 保持。

## Wire 与签名域

沿用[九字段消息](remote-channel-signed-import.md)、[批次/ACK](remote-channel-batch-v1.md)和[接收认证](remote-channel-receiver-auth.md)，没有增加版本字段或更换签名格式：

| 载荷 | 保留的 canonical 规则 |
| --- | --- |
| message | 大端 int32 字段数 8；channel/message/sender/conversation trim，正文 CRLF/CR→LF 后 trim，时间十进制字符串，nonce/keyId trim、null→空串；每字段 int32 UTF-8 长度+字节；signature 不参与 |
| batch | field `xiaoling-batch-v1` + int32 1；五身份、两个 nullable cursor、两个 int64 时间、int32 数量；按原消息顺序绑定 canonical body 长度/字节及每条 signature；外层 signature 不参与 |
| ACK response | field `xiaoling-ack-v1` + int32 1；六项身份/收据摘要及 nullable nextCursor；不含 messageIds |
| receiver request | field `xiaoling-receiver-request-v1` + int32 1；身份/operation/receiver；pull 绑定 nullable cursor，ack 绑定 batch/digest/cursor 与有序 messageIds |
| receiver proof | field `xiaoling-receiver-auth-v1` + int32 1；十字符串及 int64 issued/expires；signature 不参与 |

字符串长度以 UTF-8 bytes 计；所有整数 big-endian；nullable 的 null 是 int32 `-1`，与空字符串不同。batch/request/proof 外层字段不 trim。独立 Python 发送方提供六组固定 hex golden，测试使用中文、emoji、NBSP、CRLF、null 和超 int32 的毫秒时间；随机 ECDSA signature 不作为稳定 golden。

## 输入、兼容与平台边界

所有 raw decoder 先检查最多 65,536 UTF-16 单元，再执行严格 UTF-8 的 64 KiB 字节预算、严格 JSON 扫描、对象树与 exact 字段/类型校验。拒绝重复解码键、方言、尾随正文、孤立 surrogate、浮点/指数/字符串整数、超 Long 数值、缺字段和未知字段；语法深度最多 32。固定格式异常不携带正文或底层 cause。encoder 同样检查最终 UTF-8 wire 和转义后的 Unicode。

合法 v1 字节保持兼容；原 JSONObject 可能容忍的畸形原文现在拒绝。message decoder 仍只检查九字段结构，不替代原正文 20,000 字符、nonce 16–256、时效和收件拒绝分类；可空认证字段只保留原内存/HMAC 构造兼容，签名 JSON 要求字符串。

batch 仍最多 8 条、五分钟有效期、非空批次推进 cursor、消息 ID/nonce 去重。批次和裸 ACK cursor 允许非空首尾空白；receiver 认证 request 仍拒绝该 cursor。共享裸 ACK encoder 独立核对身份、digest、游标及 ≤8 个唯一 messageId，不能借兼容规则放宽其他字段。proof 仍沿历史 200 字符 keyId 身份规则，request keyId 仍使用 64 字符格式。

Android raw message/batch/ACK 回复直接使用共享 decoder。已有 JSONObject request/proof/manifest 兼容方法只能核对当前序列化对象，不能追溯它解析前的重复键。Android manifest encoder、signedRequest 的 JSONObject 输出样式及加密状态 encoder 保留；shared encoder 输出紧凑 JSON，不承诺 JSON 原文排列/转义字节相同。canonical 方法没有代行所有入站业务校验，平台必须按原流程校验后验签。

裸 ACK 的 `decodeAckRequest / encodeAckRequest` 保留批次游标规则；`decodeRequest / encodeRequest` 用于认证 request，继续执行严格游标规则。typealias 保持源码字段和 equality，但运行时类名变为 shared 类，不承诺独立二进制 SDK 或反射类名兼容。

结构化 manifest/request/proof adapter 在 JSONObject 重序列化前检查版本/时间的原 Int/Long 类型，避免 serializer 把 `1.0` 改写成 `1` 后绕过门禁。signedRequest 保留原先“先冻结 JSONObject 再校验/摘要”的调用顺序，不追溯冻结前已丢失的数字词法。

shared 不实现 SHA-256、ECDSA/HMAC、Base64 DER/SPKI 验证、Keystore、时钟、网络、配对或权限。manifest 只验证结构/标识/摘要格式，Android decodeManifest 后仍核对 P-256、规范 SPKI 与真实指纹；signed request 只核对 request/proof 身份及操作，真实 digest/签名/许可由平台验证。解析成功不表示已认证。

Android 原 peer/receiver/cursor/时效、逐消息验签、去重、加密入箱、原子 outbox、ACK 恢复、撤销复核、后台许可及前台用户确认保持。ACK 只表示安全入箱，不生成 Agent 执行许可。持久状态、密钥 alias 和历史数据不迁移、不重写。

## 能力矩阵与验收

| 能力 | Android | iOS |
| --- | --- | --- |
| Remote DTO/严格 raw codec/canonical bytes | 生产适配与 JVM/Redmi 门禁 | 同一 common suite、Simulator 与 arm64 编译 |
| 密码学认证、密文入箱、后台/通知、网络与产品 UI | 原实现与权限边界 | 尚无 actual 或产品入口 |

固定 common 向量覆盖六组签名字节向量、结构/类型/Unicode、64 KiB 精确 ASCII/多字节/转义预算、批次/ACK/proof 限额和历史游标差异。JVM/Redmi 以原 DataOutputStream/JCA 独立参考核对适配；Python/OpenSSL 与生产客户端验签互通。

Redmi 三进程门禁只使用 UUID preferences、真实 Keystore 和仓库外合成发送队列，离线交换生产 pull/batch/ACK/proof；验证重试、恢复、吊销和重新授权，恢复成功后清本轮夹具并断言缺钥拒绝。没有 DNS/HTTP/TLS、真实 Provider、自然 Doze/LMK/系统重启或正式 Release 验证。实际计数、构建及保护审计见[验证报告](verification-report.md)，证据目录 `outputs/verification/stage291-kmp-remote-wire/`。

下一片为完整 Tool result codec，随后 iOS actual。Remote Channel 稳定部署与长期验收仍是独立待办，第 7 点尚未整体收口。
