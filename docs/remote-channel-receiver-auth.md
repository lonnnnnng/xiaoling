# Remote Channel 接收端认证与本地授权

更新日期：2026-10-17（北京时间）。第 285 阶段第七片，开发版本保持 `0.1.22 (23)`、Room v40；未发布。

## 已实现范围

本片提供接收端 P-256 签名协议、Android Keystore 签名器及本地发送方授权入口。`tools/remote_channel_receiver_auth.py` 的 `AuthenticatedSender` 在验证接收公钥、许可和完整请求后调用原持久队列；nonce 消费与队列操作在同一 SQLite 事务提交。

第七片只提供核心和隔离门禁。第八片已将显式创建/导出、来源认证标记、认证 POST 与后台许可绑定接入生产客户端，见[接收公钥登记与认证 HTTPS](remote-channel-receiver-https.md)。旧来源保留兼容协议，必须由用户显式创建接收身份才启用认证；HTTP listener、稳定服务端适配与部署尚未提供。

本协议是应用自定义二进制签名协议，不是 RFC 9421 HTTP Message Signatures 的实现。未来适配层仍须按实际 method/path/query/body 映射完整逻辑请求、拒绝额外字段，并从可信服务配置取得登记地址摘要；不能直接相信请求自报的 endpointSha256。TLS、请求限流及部署验收不能由本片的离线签名测试替代。

## 信任与授权绑定

`receiverId` 继续是路由标识。身份凭据是接收端 P-256 私钥，登记清单只含 SPKI 公钥和 SHA-256 指纹：

| 登记字段 | 规则 |
| --- | --- |
| authVersion | JSON integer 1，拒绝 bool/float/字符串 |
| channelId / senderId / keyId | 精确匹配发送队列 metadata 的原发送方身份 |
| senderFingerprintSha256 | 精确匹配原发送公钥指纹，不能只绑定名称 |
| endpointSha256 | 登记来源完整 URL UTF-8 的 SHA-256，小写 64 hex |
| receiverId | 32 位小写 hex，绑定原加密 batchState 的 receiver |
| publicKeySpkiBase64 | 规范 X.509 SPKI 标准 Base64，仅接受 P-256；不含私钥 |
| receiverFingerprintSha256 | 规范 SPKI DER 的 SHA-256，小写 64 hex |

发送方本地管理员通过独立可信渠道核对接收指纹，再显式 `authorize --fingerprint`。授权时可同事务建立对应路由，原有裸 register 不授予认证权限；清单也不能自行授权。离线夹具的指纹来自受控合成身份，用于测试协议，不能计为真实用户核对。

同一个 receiver 的公钥、发送方指纹和 endpoint 不能替换；换钥或来源变化需要新的 receiver 及显式授权流程，当前没有生产迁移 UI。重复授权只恢复完全相同的绑定，不清 nonce 历史。`revoke` 提交禁用位，保留排队正文、收据、游标和 nonce；后续新请求和缓存重试都重新核对许可。只授权该队列的 pull/ack，不授予 Agent、工具执行、通知或后台访问许可。

## 签名请求与字节格式

入口只接受顶层 `request / proof` 两字段。Python 原始 JSON 解析拒绝重复 key、非标准常量、未知字段和超出 64 KiB 的输入。Android 生成器接受并冻结结构化 JSONObject，未提供任意原始 JSON 的导入入口。

pull request 字段为 `protocolVersion / operation / channelId / senderId / keyId / receiverId / requestCursor`；operation 固定 pull，cursor 可为 JSON null。ACK request 完全沿用[批次协议](remote-channel-batch-v1.md)的完整 outbox 请求，包括有序 messageIds，operation 固定 ack。

requestDigestSha256 对以下二进制字节求 SHA-256；所有整数均 big-endian，字符串 field 为 32 位 UTF-8 字节长度加内容，nullable field 的 null 为 `-1`：

1. `field("xiaoling-receiver-request-v1")`，随后 32 位 protocolVersion 1。
2. `channelId / senderId / keyId / operation / receiverId` 五个 field。
3. pull：nullable requestCursor。
4. ack：batchId、batchDigestSha256 两个 field，nullable nextCursor，32 位 messageIds 数量，随后按原顺序写每个 messageId field。

proof 精确包含 authVersion、上述三项发送方身份、operation、senderFingerprintSha256、endpointSha256、receiverId、receiverFingerprintSha256、issuedAtMillis、expiresAtMillis、nonce、requestDigestSha256、signature。P-256 / SHA256withECDSA 签名以下字节，DER 签名以无 padding Base64URL 编码：

1. `field("xiaoling-receiver-auth-v1")`，随后 32 位 authVersion 1。
2. 顺序写十个 field：channelId、senderId、keyId、operation、senderFingerprintSha256、endpointSha256、receiverId、receiverFingerprintSha256、nonce、requestDigestSha256。
3. 64 位 issuedAtMillis、expiresAtMillis。signature 自身不进入签名字节。

nonce 为 32 位小写 hex，默认 UUID 随机值。有效期最多 60 秒，允许签发时间比服务端当前时间提前最多 5 秒；请求到期即拒绝。等待写锁及验签后、响应生成和提交前重新读时钟复核，有效期不能靠旧 now 绕过；签名响应期间到期则回滚队列及 nonce。客户端不修改系统时钟，也不把一次签名永久复用。

## 事务与重试

认证扩展和原队列在同一 `queue.sqlite3`：显式 init 增加 `receiver_auth_metadata / receiver_authorizations / receiver_auth_requests`，authVersion 为 1，原 queue schema/user_version 仍为 1。没有静默迁移，缺表或损坏不初始化空授权；部分表存在也禁止再次 init。文件权限、密钥/SPKI 校验、DELETE/EXTRA/fullfsync 与原队列相同，见[本地发送队列](remote-channel-sender-queue.md)。

每次请求在 BEGIN IMMEDIATE 内重新读许可、验接收签名、复核时效，再调用原 `_pull / _acknowledge` 和保存 nonce 收据引用；COMMIT 成功才交付响应。签名、队列拒绝或 COMMIT 失败都回滚 nonce 和队列。撤销与请求按同一写事务串行：撤销先提交则请求拒绝，请求先提交则保留已提交结果，不宣称取消已完成的操作。

同 receiver/nonce 的完全相同签名内容在有效期内按收据引用重试，JSON 字段排列和 ECDSA 随机签名值不改变逻辑请求；同 nonce 改 payload、operation 或时间返回 AUTH_NONCE_CONFLICT。nonce 历史跨主机进程重建保留，撤销/重新授权不重置。

nonce 表只存 proof 摘要和无正文收据引用。未 ACK 的相同 GET 返回原签名快照；ACK 已逻辑清除 batch_json 后返回 AUTH_REPLAY_COMPLETED，不用额外明文响应缓存复活正文。相同 ACK 返回原签名回复，旧 ACK 不推进后来批次。proof 过期后客户端应以新 nonce/时间重签同一 outbox 请求，原队列继续保障 ACK 幂等。

授权最多 32 个 receiver，nonce 记录全局最多 4096 条，无自动淘汰；满额时新签名请求（包括新 nonce 的 ACK 重试）返回 AUTH_CAPACITY，已记录且仍有效的同请求可以重试。这是有界本地核心，长期保留、配额回收和容量恢复尚未实现，不适合据此宣称可长期运营。原队列的 messageId/receipt 上限继续生效，主机 pending 正文仍为明文、ACK 仅逻辑删除。

## Android 私钥生命周期

接收端使用独立 P-256 KeyPairGenerator/AndroidKeyStore，PURPOSE_SIGN、secp256r1、DIGEST_SHA256。alias 绑定发送方身份/指纹、endpoint hash 和 receiver；与原 HMAC 密钥及收件 AES 密钥分开。

只有显式 `create(peer, source, state)` 生成身份，已有 alias 时拒绝覆盖。`export` 仅导出原 SPKI；`sign` 核对原公钥并使用不可导出的 PrivateKey 引用。缺钥、换钥或原授权不一致均拒绝，签名/导出不会生成替代身份。显式重新创建新公钥也不能突破发送方同 receiver 不可换钥的规则。

生成器未要求每次生物识别/用户在场，证明的是应用持有私钥，不证明用户刚刚批准请求。现有配对启停、后台许可和前台草稿确认仍独立约束产品流程。`privateKey.encoded == null` 与实际签名互通已在 Redmi 验证；没有检查硬件安全级别、StrongBox 或远程 attestation，不能宣称这些能力。

## 本地 CLI

已有仓库外队列由本地管理员显式增加认证扩展；旧 `remote_channel_sender.py` 保留本地管理能力，不能作为未认证网络 fallback。下面的清单是接收端导出的公开数据，fingerprint 必须独立核对，endpoint 摘要来自可信配置：

```zsh
queue_directory="$HOME/xiaoling-sender-example-20261017"
python3 tools/remote_channel_receiver_auth.py --state-dir "$queue_directory" init
python3 tools/remote_channel_receiver_auth.py --state-dir "$queue_directory" authorize \
  --fingerprint '<独立核对的64位小写接收公钥指纹>' < /absolute/path/receiver-public-manifest.json
python3 tools/remote_channel_receiver_auth.py --state-dir "$queue_directory" execute \
  --endpoint-sha256 '<可信配置的64位来源地址摘要>' < /absolute/path/signed-receiver-request.json
python3 tools/remote_channel_receiver_auth.py --state-dir "$queue_directory" revoke \
  --receiver-id '<已登记的32位小写receiverId>'
```

成功输出原签名批次/ACK 或管理结果 JSON，退出码 0；失败仅输出固定 `receiver auth error: <CODE>`，退出码 2，BrokenPipe 为 1。不回显正文、私钥或底层异常。AUTH_REJECTED 表示身份/许可/签名/摘要拒绝，AUTH_EXPIRED 表示时间拒绝，AUTH_BINDING_CONFLICT 表示不可替换原绑定，AUTH_STATE_UNAVAILABLE 表示认证账本不可用；原队列固定错误继续传播。不能删库、跳过认证或自动换钥恢复错误。

## 验证和下一片

Python 合计 35 项（原队列 19、认证 16），JVM 聚焦 97 项（含认证 7）；覆盖未授权、错误指纹/换钥/地址、签名错配、nonce 冲突/重试、时效与验签/响应签名期间到期、撤销与同绑定再授权、并发、容量、损坏及签名/COMMIT 回滚。独立 Python/OpenSSL 与生产 Kotlin 实际互通。

Redmi `wsvwypiz7xwslvl7 / begonia`、SDK 36 三轮显式 instrumentation 分别 OK (1 test)，PID `14620 → 14666 → 14699`。真实 Keystore 私钥在新进程导出同一公钥、签生产 pull/ACK，主机验签、缓存重试、撤销拒绝和重新授权；手机新进程验签回复/清 outbox，保留两条 pending 正文。成功后只清 UUID preferences/AES/P-256 alias，断言缺钥 sign/export 拒绝且无重建；恢复流程失败不触发清理。批次和请求都通过 instrumentation 参数离线转交，没有 DNS/HTTP/TLS。

第七片证据见[验证报告](verification-report.md)与忽略目录 `outputs/verification/stage285-receiver-auth/`。第八片补产品入口与认证 HTTPS 客户端；下一片推进服务端 HTTP 映射和稳定部署，长期保留和自然系统恢复分别验收，第 285 阶段仍进行中。

## 官方正文依据

访问于 2026-10-17，经本机 smart-search 抓取并保存正文：

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：密钥材料不进入应用进程，应用通过不可导出的引用执行密码学操作；不等于所有设备都有同样的硬件安全级别。
- [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)：官方 P-256 签名示例使用 EC、AndroidKeyStore、PURPOSE_SIGN、secp256r1 与 SHA-256 digest。
- [RFC 9421 §7.2.2](https://www.rfc-editor.org/rfc/rfc9421.html#section-7.2.2)：签名覆盖足够消息组件、nonce、created/expires 有助于限制重放；本片仅借鉴这些约束，没有实现其 HTTP 字段协议。
