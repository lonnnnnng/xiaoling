# MCP 统一会话注册表与 SSE

本片对应路线图第 286 阶段。第 285 阶段按原定 Remote Channel 功能收口，稳定部署、容量回收、自然周期/Doze/LMK/系统重启、真实通知及 Release 验收独立追踪，不阻塞 MCP。

第 291 阶段第三片已将 descriptor 和有界参数 validator 固化为[MCP 共享 schema](kmp-mcp-schema.md)，Android 继续使用原 JSON 兼容端口。注册表、tools/list page、HTTP/SSE、白名单/审批与原 schema 文本不迁移；严格 raw codec 与平台 parser 的差异见新契约。

## 会话归属与发布

- `XiaoLingApplication.mcpSessionRegistry` 是进程内生产入口。Application 启动不连接服务器；设置页和每个 `AgentRunUseCase` 使用不同 UUID owner，注册表按 `(owner, serverId)` 保存传输实例，最多 64 个。
- 同一 owner/server 的并发发现共用初始化 Mutex。完成 `initialize → notifications/initialized → tools/list` 全部分页与 schema JSON 检查后才原子发布 READY。无 tools capability 的服务器可以发布空工具目录，Resources/Prompts 仍按协商能力检查。
- 配置、Token、transport、白名单变化及停用/删除先移除所有相关 Entry，并永久撤销底层传输，再同步保存配置。保存失败冻结该 server；下一次成功保存/删除才解除冻结。空 Token 的编辑保留语义及工具开关读改写都在同一锁内执行。
- Run 切换、取消和结束只撤销自己的 owner，不影响设置页发现。ViewModel 清除时释放其 Run/settings owner。被撤销的传输 `retire()` 后不能重新握手；新 Entry 必须由 factory 建立新实例。底层 `close/reset` 的兼容重置入口不能用于注册表撤销。
- 握手、目录发布、执行前后均复核 Entry 身份和当前配置。旧 Call 或 SSE 的迟到结果、错误及关闭回调不能撤销新连接或复活旧目录。设置页另用 UI generation/Job 取消阻止旧发现结果覆盖编辑、停用或删除。

## 目录与调用权限

READY 缓存最多 5 分钟。断流、目录变化通知、session 404、取消、协议错误或过期后，目录不可用；下一次显式操作创建新 Entry 并重新握手、同步工具 schema。没有后台自动重连或自动执行。

设置页发现列表用于配置预览；注册表的 `availableTools` 只返回当前 READY 且白名单允许的工具。Agent 仍必须在当前 Run 显式执行 `mcp.list_tools / list_resources / list_prompts`，才能调用对应项目。`mcp.list_tools` 的模型可见正文过滤停用工具；工具调用逐次检查最新白名单、当前 schema 和 Run 的已发现 descriptor。资源读取与 Prompt 获取重新核对当前目录及参数声明。

配置与目录指纹使用长度编码后的 SHA-256，包含凭据和所有配置字段，但 key 不保存明文 Token，不采用可碰撞的 `String.hashCode()`。Token 使用原有 Android Keystore 加密存储，页面不回显。

## 两种远程传输

Streamable HTTP 请求版本为 `2025-03-26`，legacy SSE 为 `2024-11-05`；只接受这两个协商版本。配置持久化兼容旧缺省 Streamable HTTP。设置页可以选择 Streamable HTTP/SSE，编辑已有 stdio 声明保留 transport/command/args，执行仍关闭。

legacy SSE 顺序为 GET 事件流、等待 `endpoint` 事件、向 endpoint POST JSON-RPC、从 `message` 事件匹配字符串 id。pending 在 POST 前登记，避免回复早于 HTTP ACK 时丢失结果。只接受同 scheme/host/port endpoint，拒绝 URL 账号、fragment、首尾空白和控制字符；使用既有 HTTPS 公网/DNS及 loopback 调试边界，禁止重定向和跨源 Bearer 转发。

事件读取支持 BOM、注释、LF/CRLF/CR 和多行 data；严格 UTF-8，单行/事件最多 512 KiB，半个事件在 EOF 时不发布。Streamable HTTP 的 SSE POST 回复还限制累计 512 KiB，匹配响应后立即返回，不等待流 EOF。legacy 持续流按事件限流，最多 32 个 pending、15 秒 endpoint 等待、60 秒请求/空闲读取、5 分钟单流寿命。

## 取消、错误与副作用

取消绑定覆盖响应头后的正文/SSE读取；`Call.cancel()` 中断网络，Response 由读取线程关闭，避免并发关闭 Source。断流使全部 pending 失败，并清除 handshake/cache。Streamable HTTP session 404 不在同一次调用内重试；下一次显式发现再初始化。

RPC POST 使用 `isOneShot() = true` 请求体，同时关闭网络自动重试。即使服务器在执行后返回 `503 + Retry-After: 0`，也不能让 OkHttp follow-up 自动重发工具调用。客户端取消只证明本地请求已中断，不证明远端副作用撤销；不自动重放 `tools/call`，也未实现协议级 `notifications/cancelled` 或远端回滚。

服务器主动请求只能得到“不支持方法”的协议回复，不能调用本地工具、模型或 Agent。目录变化通知使连接失效，由下一次显式操作恢复目录。

## 验证与后置范围

JVM 与 Redmi 共用真实 loopback socket/分块 SSE 夹具，采用默认生产 OkHttp、合成 Token、内存配置。验证握手与六个 API、分页、owner 隔离、READY 发布、取消、断流/schema 恢复、配置撤销、hash 碰撞、目录通知和 503 不重放。另有 JVM UTF-8/事件/endpoint/JSON-RPC、HTTP body 取消、404 和旧代次错误门禁。实际执行结果及失败修复记录见[验证报告](verification-report.md)。

loopback 证据与外部服务、真实模型联合验收分别记录。OAuth、stdio 沙箱、Tasks、后台 MCP 及正式 Release 后置。本片不改 Room v40 或发布版本 `0.1.22 (23)`。

## 第 286 阶段真实网络与模型验收

`McpLiveAcceptanceInstrumentedTest` 使用隔离内存 Store 和默认生产 OkHttp；`AgentRunUseCase` 仅增加可选注册表注入，正式调用仍取 Application 单例。Profile 只允许 `mcp.list_tools / mcp.call` 和内置 MCP Skill，不启用长期记忆；只读调用由本轮限定 server/tool 的审批门显式批准。Run、步骤、审批、事件和 Tool Ledger 走生产 Runtime 与 Room。

- Context7 使用 `https://mcp.context7.com/mcp`，Streamable HTTP 协商 `2025-03-26`。发现 `resolve-library-id / query-docs` 后仅解析公开 Kotlin 文档库，核对文档库 ID、确切参数及发现验证先于调用的事件顺序；不执行 `query-docs`。匿名与 Bearer 两种验收必须分别显式选择，认证失败不自动降级。
- 真实 `gpt-6-luna` 通过生产 Agent Run 验证 legacy SSE 成功、取消、停用三种场景。成功只产生一次远端 echo，结果以 `PASSED` 和原参数回读；Run 结束后外部 settings owner 仍连接。
- 取消先持久化 Room 请求，再取消执行协程；断言 `CANCELLED`、无活动步骤、无 `mcp.call` 结果及完成/失败覆盖。断流后的迟到投递尝试经过 300 ms 观察，完整 snapshot/ledger 未变且调用计数仍为 1。此证据不代表只写 Room 请求就能中断挂起 SSE，不证明客户端收到了断开的字节或远端回滚。
- 停用场景以空白名单开始，观察实际发送给模型的全部请求体，只记录请求数及停用名称是否出现；正文/密钥不落日志。证明目录过滤与零远端调用，执行器的拒绝及调用中撤销由已有契约门禁验证。

`roomVerified=true` 表示当前 `RESULT_READABLE` 策略通过及证据已持久化，不表示另一次远端业务回读。公网 Context7 是 Streamable HTTP；legacy SSE 的服务端仍是 Redmi loopback 夹具，真实模型参与不能把它升级为外部 SSE 服务证据。

### 复现与凭据边界

先分别覆盖安装 Debug 主 APK 与 AndroidTest APK，再运行：

```zsh
python3 tools/verify_mcp_live_acceptance.py \
  --output outputs/verification/stage286-live-rerun
```

脚本仅允许 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`；Provider 从未跟踪的根 `AGENTS.md` 读取，Context7 key 从本机 Opencode 配置读取。保持原 `enabled=false`，只在测试内存启用。凭据经新建的 ADB forward 和设备单次 loopback socket 输入，不放入源码、APK、命令参数、共享文件或正式偏好；关闭 Provider HTTP 日志，主机日志先脱敏。每个单项必须有配置 ACK、`OK (1 test)` 和断言后的 evidence，跳过不能计为通过；失败、超时及转发撤销异常均保留报告，仅撤销自己的转发。

`--context7-auth anonymous` 是另一轮显式匿名验收，默认始终为 `bearer`。输出目录必须为空；报告核对原 Opencode 文件前后 SHA-256，外部改动也会使该轮整体门禁失败。实际执行结果、初次失效 key 的失败及用户更新后的结果见[验证报告](verification-report.md)。认证依据为[Context7 官方客户端配置](https://context7.com/docs/resources/all-clients)。

协议依据：[legacy transports](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)、[Streamable HTTP transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)、[lifecycle](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle)。官方正文经 smart-search 保存于忽略的 `outputs/verification/stage286-mcp/`。
