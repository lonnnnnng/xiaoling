# Remote Channel 批次协议 v1

第 285 阶段第二片的开发源码实现本协议，正式 Release 仍为 `v0.1.22`。已有九字段单条来源继续兼容；本协议不启动 Agent，不自动打开草稿，不授予工具权限。当前仅由用户在设置页点“同步收件”触发，后台调度与通知留待后续切片。

## 登记与 GET

网络目标只来自用户为已启用 P-256 配对登记的 HTTPS URL。GET 与 ACK POST 使用**同一个登记地址**，响应不能返回或指定 ACK URL。禁止账号密码、查询参数、片段、IP literal、localhost、私网/保留 DNS、代理、Cookie、重定向；沿用 20 秒总超时、HTTP 200、JSON MIME 和解压后 64 KiB 上限。

每个 `channelId / senderId / keyId / 公钥 SHA-256 指纹 + URL SHA-256` 对应独立接收状态。首次主动同步在加密收件箱中生成 32 位小写十六进制 `receiverId`，用于服务端按接收端隔离 cursor/receipt 队列；它不是用户认证凭据，不能代表用户身份或执行授权。

GET 附带 `X-Xiaoling-Protocol: 1`、`X-Xiaoling-Receiver: <receiverId>`。有本地游标时附带 `X-Xiaoling-Cursor: <UTF-8 游标的无 padding Base64URL>`，初次请求省略该头。游标最多 1,024 字符，不能空白或含 NUL；它不能被用来拼 URL、命令或权限。

没有 `protocolVersion` 的完整九字段响应按旧单条协议处理，不产生 ACK。来源已经提交过批次游标后拒绝降级到单条协议。

## 批次响应

响应必须恰好包含以下字段，不能有隐式默认值、类型转换或扩展字段：

| 字段 | 类型与约束 |
| --- | --- |
| `protocolVersion` | integer，固定为 `1` |
| `channelId / senderId / keyId` | string，精确匹配当前已启用配对身份 |
| `receiverId` | string，精确匹配本地接收端 ID |
| `batchId` | 1 至 200 字符的 `[A-Za-z0-9][A-Za-z0-9._-]*`，发送方同一接收队列内应唯一 |
| `requestCursor` | string 或 null，必须等于此次请求的本地游标 |
| `nextCursor` | 非空 string，作为成功提交后的下一游标；非空批次必须与 requestCursor 不同 |
| `issuedAtMillis` | integer，正 Unix 毫秒；过去 5 分钟至未来 30 秒 |
| `expiresAtMillis` | integer，大于 issuedAtMillis，差值最多 5 分钟；入箱时尚未过期 |
| `messages` | array，0 至 8 个已有九字段 envelope；messageId 和 nonce 在批次内均唯一 |
| `signature` | P-256 `SHA256withECDSA` DER 签名的 Base64URL，最多 128 字符 |

空批次可保持同一个 nextCursor，表示当前没有新消息；客户端仍提交 receipt 并确认 ACK，不伪造正文或 Accepted draft。所有内部消息也须满足原有身份、签名、五分钟时间窗和 nonce 规则；批次外层签名不能替代逐条验签。

## 签名与 digest

所有整数为大端。`field(s)` 是 32 位 UTF-8 字节长度和内容；nullable field 为 null 时只写有符号 32 位 `-1`。batch 签名字节依次为：

1. `field("xiaoling-batch-v1")`、32 位整数 `1`。
2. `channelId、senderId、keyId、receiverId、batchId` 五个 field。
3. `requestCursor、nextCursor` 两个 nullable field。
4. `issuedAtMillis、expiresAtMillis` 两个 64 位整数，随后写 32 位 messages 数量。
5. 对每条消息写 32 位原有 `RemoteChannelPolicy.canonicalBytes(envelope)` 长度、该字节内容，再写 `field(envelope.signature)`。

`batchDigestSha256` 是上述完整字节的 SHA-256 小写十六进制；外层 batch signature 本身不参与 digest。消息 canonical 格式仍为八字段长度前缀格式，详见[原签名协议](remote-channel-signed-import.md)。独立参考实现位于 `tools/remote_channel_https_fixture.py`。

## 原子提交与 ACK outbox

客户端先冻结当前已登记来源并验证 receiverId、requestCursor、有效期及批次外层签名，再在隔离内存事务中逐条验签、去重和检查全局 8 条待确认容量。任一失败整批回滚，cursor 不前进，不产生 ACK；nonce 被其他 messageId 使用过也必须整批拒绝。只有已经持久存在消息去重身份的重复 messageId 才计为重复接收。

正文、去重身份、新 cursor、`batchId / batchDigestSha256 / nextCursor / messageIds` receipt 同次写入一份 AES-GCM 加密快照。写失败保留原快照；最多 32 个来源状态，满容量拒绝扩容，不淘汰旧 outbox。收件箱明文 schema 从 v1 升级至 v2，旧正文、去重身份与 Keystore 密钥保持不变，新增的 batchStates 初始为空。

每个来源最多一个待 ACK 批次。GET 成功提交后才 POST ACK；ACK 请求/回执失败或取消保留 outbox，下次主动同步先补同一个 receipt，成功后本次调用结束，不再 GET。正常调用最多一次 GET 和一次 POST；恢复调用最多一次 POST。服务端对同一接收端、batchId、digest 和 cursor 的 ACK 必须幂等，响应丢失后能重试，且不能因为 ACK 重复而跳过下一批。

用户打开、忽略或正文超过既有 24 小时保留期，只处理正文；已经安全入箱的 receipt 与 cursor 保留。ACK 只表示本机安全入箱，不代表用户打开、确认、执行成功，也不是接收端身份的密码学证明；服务端不能据此执行任务或把其他接收端的队列当作已送达。生产发送方的认证、稳定部署与多接收端策略仍需单独验收。

## ACK 请求与签名回执

POST JSON 请求恰好包含 `protocolVersion=1`、`operation="ack"`、`channelId / senderId / keyId / receiverId`、`batchId`、`batchDigestSha256`、`nextCursor`、`messageIds`。messageIds 为整批按顺序已安全入箱或持久去重的 ID 列表，空批次为空列表。请求不包含正文、私钥或工具参数。

服务器返回 HTTP 200 JSON，字段集合为请求去掉 messageIds、operation 改成 `"acknowledged"`，再加 `signature`。客户端逐字段精确匹配原 receipt，签名使用已配对公钥，签名字节为：

1. `field("xiaoling-ack-v1")`、32 位整数 `1`。
2. `channelId、senderId、keyId、receiverId、batchId、batchDigestSha256` 六个 field。
3. nullable `nextCursor` field。

只有签名确认、来源/配对复核和本地 outbox 清除 commit 都成功，才能展示“送达回执已确认”。迟到回执只能清除完全匹配的旧 receipt；来源 URL 或配对变化会阻断 ACK，新 URL 不继承旧 cursor/outbox，也不会向旧 URL 自动补发。

## 重试与验证

网络、超时、HTTP 5xx 沿用第 285 阶段第一片的持久指数退避，从 30 秒至 1 小时；取消不记故障。4xx、JSON、身份、签名、游标和过期拒绝只记录错误，修复后允许用户再次同步，不能清空 outbox 或跳过批次。当前没有自动重试调度。

JVM 拦截器与 MockWebServer 验证事务、重放、nonce、容量、并发、取消、ACK 丢失及 schema 兼容；Redmi 公网测试通过独立 Python/OpenSSL 合成发送方验证 DNS/TLS 和跨实现签名。Quick Tunnel 只提供临时测试地址，[官方文档](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/) 不承诺 uptime。受控 force-stop/重建证据与自然 LMK、真实生产发送方分开记录，详见[验证报告](verification-report.md)。
