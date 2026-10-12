# Remote Channel 签名消息收件

当前前台来源是“设置 → 远端身份配对”中的手动 JSON 导入，或用户为已配对公钥登记地址后主动 HTTPS 拉取一条。应用不会监听网络、读取 Telegram/Webhook、后台轮询或自动启动 Agent。发送方需要通过独立可信渠道交付公钥指纹；用户核对完整 SHA-256 指纹并显式启用对应 `channelId / senderId / keyId` 后，才能接收消息。

## HTTPS 单条响应

- 每个已配对身份可登记一条 HTTPS URL，地址持久绑定 Channel、发送方、keyId 和当前公钥指纹；轮换身份须重新登记。地址不允许账号密码、查询参数、片段、IP literal 或 localhost。生产客户端不使用代理、Cookie 或自动跳转，DNS 在实际建连时拒绝本机、私网和保留地址。
- 地址按本机配置保存，不应在 URL 路径中放置令牌；此协议不支持认证头、Cookie 或带凭据的 URL。
- 用户点“拉取一条”时，客户端发起无认证 GET，期望 HTTP 200、`Content-Type: application/json`，响应体为下述**单个**九字段 JSON，解压后不得超过 64 KiB。HTTP 非 200、其他类型、超限、格式错误、身份不匹配和验签失败都不会入箱。
- 服务端每次交付时需要让 `receivedAtMillis` 落在五分钟签名窗口内；同一 `messageId` 或 `nonce` 被重复返回时会被本机拒绝。当前没有分页、批量、游标或 ACK；这是主动读取接口，不提供离线送达保证。

## JSON envelope

导入内容必须恰好有以下九个字段，不能有附件或额外字段，UTF-8 JSON 总大小不超过 64 KiB：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `channelId` | string | 与已启用配对一致的渠道 ID |
| `messageId` | string | 该发送方在该渠道内唯一的消息 ID |
| `senderId` | string | 与已启用配对一致的发送方 ID |
| `conversationKey` | string | 发送方的会话标签；不会直接打开既有本地会话 |
| `text` | string | 纯文本正文，不超过 20,000 字符 |
| `receivedAtMillis` | integer | 签名时间，Unix 毫秒；当前接受过去 5 分钟至未来 30 秒 |
| `nonce` | string | 每条消息独立的随机标识，16 至 256 字符 |
| `keyId` | string | 与已启用配对一致的公钥版本 ID |
| `signature` | string | P-256 `SHA256withECDSA` 的 DER 签名字节，以 Base64URL 编码 |

签名字节格式由 `RemoteChannelPolicy.canonicalBytes()` 固定：先写大端 32 位整数 `8`，再按 `channelId、messageId、senderId、conversationKey、text、receivedAtMillis、nonce、keyId` 的顺序，对每个字段写大端 32 位 UTF-8 字节长度和内容。身份、nonce、keyId 在签名前去首尾空白；正文把 CRLF/CR 改为 LF 后去首尾空白。签名字段本身不参与签名。公钥使用 P-256 X.509 SPKI 的 Base64 或 PEM；配对页显示的指纹是规范化 SPKI 字节的 SHA-256。

验签、时间窗、allowlist、`messageId` 和 `nonce` 去重都通过后，正文与去重身份才同次提交到本机加密收件箱。未读正文最多 8 条、保留 24 小时。用户在列表点“打开”只进入会话确认卡，确认前中断仍可从列表恢复；在确认卡点“打开”才进入可编辑草稿，在确认卡点“忽略”则丢弃正文。两种决定均先提交持久删除，旧消息 ID 和 nonce 仍保留在有界去重窗口中。配对之后停用或撤销会拒绝新入站，已验签入箱的旧消息仍在列表中等待用户处理。
