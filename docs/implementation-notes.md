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
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、Conversation、Message、AgentRun、AgentStep 和 RunEvent 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Repository seam、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | 最小 Agent Runtime、Run Ledger interface、fake Tool Registry 和可审计运行链路。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和主要流程集中在 `XiaoLingViewModel`：

- Provider 管理、模型同步、会话切换、发送请求、摘要生成、流式更新和错误提示由同一个 ViewModel 维护。
- `OpenAiCompatibleClient` 直接承担模型列表、两种生成接口、SSE 和日志，没有独立 Provider Adapter 或 Agent Engine。
- Provider、会话、消息和最小 Agent Run 已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- UI 以聊天消息为中心，还没有独立的任务时间线、确认卡片、工具结果展开、运行恢复和后台任务入口。

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

这条链路使用当前选中的模型做工具规划和最终总结，使用 fake Tool Registry 验证 Agent Runtime 基础设施：

1. 创建 `AgentRun`，状态从 `QUEUED` 进入 `THINKING`。
2. 请求当前模型只返回工具调用 JSON，应用侧只接受已注册工具。
3. 进入 `WAITING_APPROVAL`，由应用侧记录自动审批事件；真实高风险工具后续必须接用户确认。
4. 执行 fake tool，写入 `AgentStep` 和 `RunEvent`。
5. 进入 `VERIFYING`，检查工具结果可读。
6. 将工具结果回传当前模型生成最终总结。
7. 完成后将 Run 标记为 `COMPLETED`，并在对话区输出总结。

该链路的价值是先把 Run、Step、Event、审批、执行、验证和终态跑通，为后续真实工具、记忆和后台任务提供可测试 seam。

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

- Provider、会话、消息、AgentRun、AgentStep 和 RunEvent 保存在 Room 数据库 `xiaoling.db`。
- `xiaoling` 和 `xiaoling_conversations` SharedPreferences 只作为旧数据迁移来源；迁移成功后不会反复恢复旧数据。
- UI 偏好保存在 `xiaoling_ui` SharedPreferences。
- API Key 只以 AES-GCM 密文落盘，密钥材料保存在 Android Keystore。

## 日志

- debug 包默认开启 HTTP 调试日志：`BuildConfig.XIAOLING_HTTP_LOGS_ENABLED = true`。
- release 包默认关闭 HTTP 调试日志。
- 日志会对 Authorization 和包含 key 的 Header 做脱敏。

## 当前限制

- 暂不提供云同步和账号体系。
- 尚未内置真实工具调用、MCP、长期记忆和手机自动化执行。
- 暂不提供 Provider 模板市场。
- 更换 `applicationId` 后，旧版本本地数据不会自动迁移。
- Responses API 的历史消息当前被拼接为单一字符串，未来工具调用和多模态需要结构化输入。
- `/agent` 目前只接入 fake tool，还没有真实业务工具、交互式审批卡片和运行时间线 UI。
- 运行恢复、后台任务、长期记忆、Skill 和真实工具仍需按路线图继续补齐。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
