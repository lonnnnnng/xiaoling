# Remote Channel 认证 HTTP 服务适配

更新日期：2026-10-17（北京时间）。第 285 阶段第九片，开发版本保持 `0.1.22 (23)`、Room v40；未发布。

## 服务边界

`tools/remote_channel_http.py` 提供 Python 3.12+ 标准库实现的窄 HTTP/1.1 入口，只监听 `127.0.0.1`。外层受控代理必须提供 TLS 和固定公网 HTTPS 地址。启动校验原仓库外队列、发送密钥和认证扩展，缺表/缺钥/损坏拒绝，不创建状态、不授予许可、不更换身份。

网络唯一业务入口是 `AuthenticatedSender.execute`；没有裸 GET、裸 ACK、注册、authorize/revoke/enqueue/init、健康状态或正文查询路由。主机管理员继续通过[本地 CLI](remote-channel-receiver-auth.md)核对公开指纹、授权及撤销。认证只授权收件/ACK，不能运行 Agent、发通知或打开后台许可。

完整 HTTPS endpoint 从启动参数取得并计算 UTF-8 SHA-256。当前服务只接受小写 DNS 域名、默认 HTTPS 443、ASCII 字母/数字/下划线/连字符组成的绝对路径；必须逐字规范，拒绝账号、query、fragment、端口、百分号编码、点段或空路径。此范围比手机客户端 URL 规则更窄；超范围地址在启动时拒绝，不自行改写清单。手机清单和本地授权必须绑定同一字符串。

## HTTP 映射与定界

只接受 `POST <固定原始路径> HTTP/1.1`，Host 必须精确为启动配置的域名。拒绝绝对形式 target、query、路径变化、缺 Host、所有重复头、折行、冒号前空格及控制字符。Forwarded、X-Forwarded-*、CF-* 等代理头不用于重建 endpoint、授权或限流身份；代理需保留原 Host。

认证标记为 `X-Xiaoling-Receiver-Auth: 1`。拒绝旧 `X-Xiaoling-Receiver / X-Xiaoling-Cursor`，拒绝 Transfer-Encoding、Expect、Content-Encoding；只接受单个十进制 Content-Length 和 application/json（可带 charset=utf-8）。这是一份要求已知长度的应用入口契约，不是通用 HTTP 服务器；代理不能给它转为 chunked。

头最多 8 KiB/48 项，正文最多 64 KiB，严格 UTF-8。复用原 JSON parser 拒绝重复 key、NaN/Infinity，再规范编码以拒绝 JSON 转义的孤立 surrogate；request/proof 精确字段、操作和类型先校验，最终验签、授权、地址摘要与时效均由核心重核。每个连接只处理一个请求，所有响应为 JSON、no-store、nosniff 和 Connection: close。超出的已读尾字节拒绝，之后未读字节不进入另一个请求。

## 有界执行与恢复

默认最多 4 个连接处理线程、内核 backlog 16；忙碌连接尽力返回固定 503 后关闭，不排无限任务。全局令牌桶容量 60，每秒补 1，慢连接也消耗令牌；不用可伪造的代理客户端 IP。该全局限制只保护本地资源，不能替代公网代理限流、流量防护或多用户配额。429 当前被手机既有策略归为普通 4xx，需要显式重试，没有新增自动重试策略。

接收头、正文各最多 5 秒累计等待，整请求最多 15 秒（包含响应写入）。每次 socket 操作按剩余单调时钟预算设 timeout，不因连续小片段重置。每个合法请求用独立 Python 进程调用原核心 CLI；超过整请求预算终止该进程组并回收，避免多个 SQLite/OpenSSL 局部 timeout 累加突破总时限。限制不改变认证 proof 的 60 秒有效期。

COMMIT 成功后才返回原签名批次/ACK。写 socket 失败、核心进程超时或终止可能发生在提交之后，服务不能断言该请求回滚。重启后同 nonce/同请求在有效期内重试，或手机用新 nonce 重签原 durable ACK，仍由原持久收据保障幂等；已 ACK 的旧 pull 返回 AUTH_REPLAY_COMPLETED，不恢复正文副本。

核心已有 32 receiver、4096 nonce/receipt 等容量限制且无长期回收，主机 pending 明文和逻辑删除边界保持不变。全局令牌只驻内存，服务重启会重置；身份、游标、收据和 nonce 在 SQLite 保留。

## 固定 HTTP 错误

错误正文恰为 `{"error":"固定码"}`，不回显请求、签名、密钥、底层异常或主机路径。成功只返回原签名协议 JSON。未知核心失败转为 SERVICE_UNAVAILABLE/503。

| 状态 | 主要原因 |
| --- | --- |
| 400 | framing、非法 JSON、INVALID_REQUEST、Host 拒绝 |
| 403 | AUTH_REJECTED、AUTH_EXPIRED、缺认证标记 |
| 404 / 405 | 非固定 target / 非 POST |
| 408 | 接收阶段累计超时 |
| 409 | nonce 冲突、已 ACK 重放、ACK_REQUIRED、cursor/ACK 不匹配 |
| 413 / 415 / 431 | 正文超限 / MIME 错误 / 头超限 |
| 429 | 全局速率超限 |
| 503 | 连接容量、处理超时、持久容量或状态/签名不可用 |

AUTH_REJECTED 是核心统一拒绝码，部分 OpenSSL 验证设施错误也会包装为此码；403 不能单独证明调用者签名错误。入口没有公开诊断路由，也不向客户端返回内部错误详情。

## 启动与验收

已有队列和授权扩展按前两片显式创建、授权后启动：

```zsh
python3 tools/remote_channel_http.py \
  --state-dir /absolute/path/outside-repository/sender-state \
  --endpoint https://sender.example/batch --port 8765
```

不要把本地明文端口直接转发为公开 TCP 服务。代理必须终止可信 TLS、保留固定 Host/path/长度并设置自身限流；稳定域名、代理配置、服务管理、备份、轮换和长期容量运营均未由本片交付。

Python socket 门禁覆盖定界、严格 JSON、慢请求、连接/速率上限、独立进程超时释放锁、持久重试和提交后响应故障。Redmi `RemoteChannelAuthenticatedNetworkInstrumentedTest` 分 create/receive/revoked/restore 四个独立进程，使用默认 RemoteChannelHttpsPuller，无拦截器/DNS/TLS 注入。主机合成适配器仅在认证 ACK 已提交后返回 503，手机保留原 outbox；主机账本审计与后续进程的精确收据断言共同证明恢复。恢复断言通过后再测缺钥和清 UUID 夹具；恢复失败不清夹具，清理阶段失败可能留下部分已清状态。

复现公网门禁前，分别安装源码 Debug 主 APK 与 AndroidTest APK，然后显式运行 `python3 tools/verify_remote_channel_authenticated_https.py --cloudflared /absolute/path/cloudflared --output /absolute/path/verification-directory`。输出目录必须为空，避免覆盖上轮失败证据。脚本只允许上述 Redmi/SDK 36，以独立 UUID 状态运行；合成发送私钥及 SQLite 留在仓库外临时目录，不自动删除。它通过进程句柄关闭自己的服务/tunnel，失败保留手机隔离状态及审计，不能用于正式发送队列。

真实公网证据来自临时 Quick Tunnel，只承载仓库外合成消息和公开授权清单；结束时停止本轮自己的服务/tunnel，不复用为稳定生产服务。完整结果见[验证报告](verification-report.md)。正式 Release、自然 Doze/LMK/系统重启及稳定部署仍待后续验收。

## 官方正文依据

2026-10-17 经 smart-search 抓取，保存 `outputs/verification/stage285-authenticated-http/`：

- [RFC 9112 §3.2、§5.1–5.2、§6.3](https://www.rfc-editor.org/rfc/rfc9112.html)：Host 缺失/重复应拒绝，冒号前空白及折行必须严格处理，正文定界歧义会造成请求走私风险。本入口使用更窄的单 Content-Length 契约。
- [Python socket timeout](https://docs.python.org/3/library/socket.html#socket.socket.settimeout)：settimeout 约束后续单次阻塞操作，本实现另外维护累计单调 deadline。
- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)：临时域名每次变化，无 uptime guarantee，适用于测试；稳定域名与生产流量需另建受管理 Tunnel。
