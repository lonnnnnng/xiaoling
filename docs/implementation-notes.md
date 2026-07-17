# 当前实现说明

## 技术栈

- Kotlin
- Jetpack Compose
- OkHttp
- Room
- Android Keystore
- Gradle Wrapper

包名：`com.longdev.xiaoling`

## 模块职责

| 模块 | 关键文件 | 职责 |
|---|---|---|
| App/UI | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | 「对话 / 设置」双入口、会话列表、消息输入、模型选择、模型提供方配置页面和轻量反馈。 |
| ViewModel | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | 维护页面状态、会话上下文、摘要压缩、模型同步、对话发送和配置保存。 |
| Network | `app/src/main/java/com/longdev/xiaoling/network/OpenAiCompatibleClient.kt` | 构造 OpenAI-compatible 请求，处理 Chat Completions、Responses API、SSE 流式输出和错误分类。 |
| URL | `app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | 将用户输入的 API 根地址归一化成 `/models`、`/chat/completions` 和 `/responses` 请求地址。 |
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、Conversation、Message、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote 和 AgentMemory 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Repository seam、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | 最小 Agent Runtime、Run Ledger interface、真实低风险 Tool Registry、交互式审批 gate 和可审计运行链路。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和主要流程集中在 `XiaoLingViewModel`：

- Provider 管理、模型同步、会话切换、发送请求、摘要生成、流式更新和错误提示由同一个 ViewModel 维护。
- `OpenAiCompatibleClient` 直接承担模型列表、两种生成接口、SSE 和日志，没有独立 Provider Adapter 或 Agent Engine。
- Provider、会话、消息、最小 Agent Run、审批请求和长期记忆已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- UI 以聊天消息为中心，已能在 `/agent` 消息下方显示当前 Run 时间线和最小审批卡片；设置页已有最小 Agent 运行记录入口，可以查看历史 Run、步骤、审批和事件，但还没有完整任务中心、运行恢复和后台任务入口。

因此，后续 Agent 功能不能继续堆进 `sendMessage()`。应先建立 domain、data、runtime 和 tool 边界，再逐步迁移现有聊天链路。

## 对话请求

用户在对话页输入消息并发送后：

1. 校验 `Base URL`、已启用模型和消息内容。
2. 根据当前接口模式请求 `POST <api-root>/chat/completions` 或 `POST <api-root>/responses`。
3. Chat Completions 模式发送 `model`、`messages`、`temperature`、`top_p`、`max_tokens` 和 `stream`。
4. Responses API 模式发送 `model`、`input`、`temperature`、`top_p`、`max_output_tokens` 和 `stream`。
5. 非流式响应从常见字段中提取文本。
6. SSE 流式响应读取 `data:` 行，聚合 Chat Completions `choices[].delta.content` 或 Responses `delta` 文本。
7. UI 以 30ms 节流刷新流式内容，完成或失败时强制 flush。
8. 最终消息携带结构化 `MessageMeta`，包括模型、接口模式、是否流式、请求地址、首字耗时、总耗时和错误信息。
9. 发送期间可以点击输入区右下角停止按钮，取消 ViewModel Job 和底层 OkHttp Call；流式迟到事件不会继续写入 UI。

## 最小 Agent 链路

当前提供一个最小 Agent 验证入口：在对话框输入 `/agent <目标>`。

这条链路使用当前选中的模型做工具规划和最终总结，使用 `XiaoLingToolRegistry` 执行应用内低风险工具：

1. 创建 `AgentRun`，状态从 `QUEUED` 进入 `THINKING`。
2. 请求当前模型只返回工具调用 JSON，应用侧只接受已注册工具。
3. 进入 `tool.validate` 步骤，校验工具必填参数、工具调用预算和重复调用风险。
4. SAFE 工具跳过交互审批并写入 `approval.skipped` 审计事件；非 SAFE 工具进入 `WAITING_APPROVAL`，先写入 `ApprovalRequest`，再在对话区显示审批卡片；用户批准后继续执行，用户拒绝后 Run 进入失败终态。
5. 执行工具，写入结构化 `RunEvent`，包括工具名、参数、结果、耗时、成功状态和可选验证状态；`notes.create` 会在写入后回读验证，回读不一致时记录 `verified=false`，不会宣称完成。
6. 进入 `VERIFYING`，检查工具结果可读。
7. 将工具结果回传当前模型生成最终总结。
8. 完成后将 Run 标记为 `COMPLETED`，并在对话区输出总结。

当前最小 Runtime 已具备以下运行约束：

- `AgentRuntimeOptions` 控制最大工具调用次数、整次 Run 超时、模型步骤超时和工具步骤超时。
- 工具风险等级、必填参数和超时时间来自应用侧 `ToolDefinition`，不信任模型自己声明的风险。
- 模型工具调用解析只保留模型返回的原始参数，不自动补齐必填字段；缺参必须由 `tool.validate` 写入失败终态，便于后续审计模型决策质量。
- `AgentRunUseCase` 使用 reporting ledger 回读 Room 快照，ViewModel 将 `AgentRun / AgentStep / RunEvent` 渲染成当前对话内的运行时间线。
- 审批使用 suspend `ApprovalGate` 挂起等待 UI 决策；`ApprovalRequest` 独立记录待确认工具、风险、参数、有效期、决定结果和决定原因。
- 停止生成会取消等待并把当前审批请求写成 `CANCELLED`；超过审批有效期会写成 `EXPIRED`，不会继续执行工具。
- 当前 ViewModel 会按 conversationId 缓存正在显示的 Run 时间线和审批卡片；仅切换会话/页面再返回不会丢失当前活跃卡片。
- 设置页「Agent 运行记录」从 Room 读取最近 50 条 Run，展开后可查看步骤、审批请求和事件；事件里的工具调用、工具结果、审批和失败原因会先按 JSON 结构化展示，再对非 JSON 文本回退原文。该页面只做历史审计，不负责恢复、重试或后台执行。
- 应用启动时会检查上次遗留的非终态 Run，将它们收敛为 `CANCELLED`，并把待审批请求写成 `CANCELLED`，避免进程重建后出现无法继续的假活跃任务。
- 取消、失败、预算耗尽和超时都会写入终态；取消/失败落库使用不可取消清理块，避免 Run 卡在中间态。
- `RunEvent` 目前仍复用 `message` 字段保存 JSON 字符串，运行记录页已做展示层解析，后续可以升级为独立 metadata 字段。
- 第一批生产工具包括 `app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`notes.create`、`memory.search` 和 `memory.remember`。SAFE 工具不打断用户审批，但仍写入 `approval.skipped` 审计事件；`notes.create` 和 `memory.remember` 会写入本地数据，必须经过应用侧审批。

该链路的价值是先把 Run、Step、Event、审批、执行、验证、长期记忆和终态跑通，为后续更多真实工具和后台任务提供可测试 seam。

## 会话上下文

- 当前会话内的用户消息和模型回复会作为上下文参与下一轮请求。
- 会话数量和消息内容保存在本地。
- 当历史消息超过最近窗口时，较早内容会压缩成摘要，并作为 system 上下文放入后续请求。
- 摘要失败时使用本地兜底摘要，保证主对话链路不中断。

## Provider 管理

设置页二级入口「模型提供方管理」负责：

- 新增、编辑、删除模型提供方。
- 通过二维码、剪切板和 Base64 解码辅助导入配置。
- 请求 `GET <api-root>/models` 获取上游模型列表。
- 手动勾选允许在对话页使用的模型。
- 单个同步或批量同步模型列表。

## 本地存储

- Provider、会话、消息、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote 和 AgentMemory 保存在 Room 数据库 `xiaoling.db`。
- AgentMemory 当前保存内容、标签、类型、来源会话、来源 Run、来源摘要、置信度、启用状态和时间戳；记忆管理 UI、FTS 和撤销入口仍在后续里程碑。
- `xiaoling` 和 `xiaoling_conversations` SharedPreferences 只作为旧数据迁移来源；迁移成功后不会反复恢复旧数据。
- UI 偏好保存在 `xiaoling_ui` SharedPreferences。
- API Key 只以 AES-GCM 密文落盘，密钥材料保存在 Android Keystore。

## 日志

- debug 包默认开启 HTTP 调试日志：`BuildConfig.XIAOLING_HTTP_LOGS_ENABLED = true`。
- release 包默认关闭 HTTP 调试日志。
- 日志会对 Authorization 和包含 key 的 Header 做脱敏。

## 当前限制

- 暂不提供云同步和账号体系。
- 尚未内置外部真实工具调用、MCP 和手机自动化执行；当前真实工具限于时间、会话检索、本机笔记和本机长期记忆。
- 暂不提供 Provider 模板市场。
- 更换 `applicationId` 后，旧版本本地数据不会自动迁移。
- Responses API 的历史消息当前被拼接为单一字符串，未来工具调用和多模态需要结构化输入。
- `/agent` 目前只接入第一批应用内低风险工具，运行记录页仍是只读历史审计；进程重建后会收敛中间态，但还没有继续执行和失败重试。
- 工具 Schema 目前只覆盖必填字符串参数，还没有完整 JSON Schema、类型校验和业务校验器。
- 运行恢复、后台任务、长期记忆管理 UI、Skill 和更多真实工具仍需按路线图继续补齐。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
