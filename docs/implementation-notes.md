# 当前实现说明

## 技术栈

- Kotlin
- Jetpack Compose
- OkHttp
- Room
- KSP
- Android Keystore
- Gradle Wrapper

包名：`com.longdev.xiaoling`

当前发布版本：`v0.1.9`（`versionCode 10`）

## 模块职责

| 模块 | 关键文件 | 职责 |
|---|---|---|
| App/UI | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | 「对话 / 设置」双入口、会话列表、消息输入、模型选择、模型提供方配置页面和轻量反馈。 |
| ViewModel | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | 维护页面状态、会话上下文、摘要压缩、模型同步、对话发送和配置保存。 |
| Network | `app/src/main/java/com/longdev/xiaoling/network/LlmProviderAdapter.kt`、`OpenAiCompatibleClient.kt` | Adapter 负责 OpenAI-compatible URL、payload 与响应协议；Client 负责 HTTP、鉴权 Header、取消、计时和 SSE 读取。 |
| URL | `app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | 将用户输入的 API 根地址归一化成 `/models`、`/chat/completions` 和 `/responses` 请求地址。 |
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、Conversation、Message、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory 和 AgentMemoryCandidate 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Repository seam、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | 最小 Agent Runtime、Run Ledger interface、真实低风险 Tool Registry、交互式审批 gate 和可审计运行链路。 |
| Prompt | `app/src/main/java/com/longdev/xiaoling/prompt/` | 三类可配置提示词的默认模板、最终 system prompt 组合和不可覆盖事实边界。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和主要流程集中在 `XiaoLingViewModel`：

- Provider 管理、模型同步、会话切换、发送请求、摘要生成、流式更新和错误提示由同一个 ViewModel 维护。
- `LlmProviderAdapter` 已成为模型协议边界，当前 `OpenAiCompatibleAdapter` 统一处理模型列表、Chat Completions、Responses API 请求与响应映射；`OpenAiCompatibleClient` 只保留 HTTP 传输、取消、计时和 SSE 读取。普通聊天和 Agent 仍复用同一 Client 与 Adapter 实例链路。
- Provider、会话、消息、最小 Agent Run、审批请求和长期记忆已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- Room compiler 已从 KAPT 切换到 KSP，`app/schemas/` 保存历史 v4、v6、v7、v8、v9、v10 与当前 v11 Schema；v4→v11 和 v9→v11 迁移测试通过正式 migration 列表和 DAO/Repository 回读验证用户数据。
- UI 以聊天消息为中心，已能在 `/agent` 消息下方显示当前 Run 时间线和最小审批卡片；设置页 Agent 任务中心可以筛选任务、查看完整工具结果/步骤/审批/事件，并对可重试终态创建关联的新 Run。后台任务入口仍未交付。

当前已经建立最小 domain、data、runtime 和 tool 边界。后续功能不应继续堆进 `sendMessage()`，应把仍留在 ViewModel 的上下文、网络和会话编排逐步迁入现有边界。

## 对话请求

用户在对话页输入消息并发送后：

1. 校验 `Base URL`、已启用模型和消息内容。
2. 根据当前接口模式请求 `POST <api-root>/chat/completions` 或 `POST <api-root>/responses`。
3. Chat Completions 模式发送 `model`、`messages`、`temperature`、`top_p`、`max_tokens` 和 `stream`。
4. Responses API 模式发送 `model`、结构化 `input` Item 数组、`temperature`、`top_p`、`max_output_tokens` 和 `stream`；除 system/user/assistant 消息外，Adapter 还支持通过同一 `call_id` 关联的 `function_call / function_call_output`。
5. 非流式响应从常见字段中提取文本。
6. SSE 流式响应读取 `data:` 行，聚合 Chat Completions `choices[].delta.content` 或 Responses `delta` 文本。
7. UI 以 30ms 节流刷新流式内容，完成或失败时强制 flush。
8. 最终消息携带结构化 `MessageMeta`，包括模型、接口模式、是否流式、请求地址、首字耗时、总耗时和错误信息。
9. 发送期间可以点击输入区右下角停止按钮，取消 ViewModel Job 和底层 OkHttp Call；流式迟到事件不会继续写入 UI。

## 最小 Agent 链路

当前提供一个最小 Agent 执行入口：在对话框输入 `/agent <目标>`。

这条链路使用当前选中的模型做工具规划和受限回复样式选择，使用 `XiaoLingToolRegistry` 执行应用内低风险工具，最终事实由 Runtime 根据真实工具记录渲染：

1. 创建 `AgentRun`，状态从 `QUEUED` 进入 `THINKING`。
2. 请求当前模型只返回工具调用 JSON，应用侧只接受已注册工具。
3. 进入 `tool.validate` 步骤，校验 JSON Schema、未知参数、可插拔业务规则、Android 权限、工具调用预算和重复调用风险。
4. SAFE 工具跳过交互审批并写入 `approval.skipped` 审计事件；非 SAFE 工具进入 `WAITING_APPROVAL`，先写入 `ApprovalRequest`，再在对话区显示审批卡片；用户批准后继续执行，用户拒绝后 Run 进入失败终态。
5. 执行工具，写入可读 `RunEvent.message` 和独立 typed metadata，包括工具名、参数、结果、耗时、成功状态和可选验证状态；`notes.create` 与 `memory.remember` 会在写入后回读验证，回读不一致时记录 `verified=false`，不会宣称完成。
6. 进入 `VERIFYING`，按工具定义检查“结果可读”或“Executor 已回读验证”；需要回读的工具不能用普通成功文本替代验证证据。
7. 模型根据用户提示偏好选择受限的详略和语气枚举，Runtime 使用真实工具字段渲染最终回复；样式选择超时、为空或返回非法内容时，使用确定性默认样式保留已完成结果。
8. 完成后将 Run 标记为 `COMPLETED`，并在对话区输出总结。

当前最小 Runtime 已具备以下运行约束：

- `AgentRuntimeOptions` 控制最大工具调用次数、模型/工具执行预算、模型步骤超时和工具步骤超时；用户阅读审批卡片的等待时间不消耗执行预算。
- `ToolDefinition` 统一声明输入类型、长度/范围/枚举、业务校验器、风险、确认策略、Android 权限、后台能力、超时和验证策略；风险与确认不信任模型声明。
- 模型提示使用 `object/properties/required/additionalProperties=false` JSON Schema；解析层先按原始 JSON primitive 拒绝错误类型和非 object `arguments`，再规范化到字符串 Map 供 Runtime 做长度/范围/枚举与业务校验，不自动补字段或接受未知字段。
- `ToolPermissionChecker` 默认 fail-closed；生产链路使用 `ContextCompat.checkSelfPermission` 检查定义中的 Android 权限，权限不足时在审批和 Executor 之前失败。
- Runtime 接收 `FOREGROUND / BACKGROUND` 执行来源；后台来源只能选择 `supportsBackground=true` 的工具，当前生产入口和全部首批工具均保持前台限定。
- Registry 初始化会拒绝重复工具名；`memory.remember` 已通过可插拔业务校验器限制标签数量和单标签长度。
- `AgentRunUseCase` 使用 reporting ledger 回读 Room 快照，ViewModel 将 `AgentRun / AgentStep / RunEvent` 渲染成当前对话内的运行时间线。
- 审批使用 suspend `ApprovalGate` 挂起等待 UI 决策；`ApprovalRequest` 独立记录待确认工具、风险、参数、过期策略、决定结果和决定原因。
- 当前交互审批不按固定倒计时主动过期，只有用户批准、拒绝、停止生成或应用启动恢复收敛时改变状态；`EXPIRED` 保留给后续明确截止时间的工具策略。
- 当前 ViewModel 会按 conversationId 缓存正在显示的 Run 时间线和审批卡片；仅切换会话/页面再返回不会丢失当前活跃卡片。
- 设置页「Agent 任务中心」从 Room 读取最近 50 条 Run，支持全部、处理中、可重试、已完成四档筛选；展开后可查看完整 ToolResult content/success/verified/duration、步骤、审批请求和事件。事件展示直接消费 Repository 解码后的 typed metadata，旧纯文本事件回退显示 `message`。
- `FAILED / CANCELLED / BUDGET_EXHAUSTED` 可重新运行。重试在来源会话追加新的 `/agent <goal>` 消息，使用当前 Provider/模型并创建带 `retryOfRunId` 的新 Run；旧 Run 的状态、结果、步骤和事件不修改。成功执行过非 SAFE 工具、恢复记录表明中断发生在 `EXECUTING/VERIFYING`，或 `tool.execute/tool.verify` 步骤以失败/取消结束时，UI 会先要求二次确认。
- 重试正式启动时 ViewModel 选中来源会话并发出一次性导航信号，根 UI 回到对话页；重新触发的写工具仍走正常审批，审批卡不会隐藏在任务中心后台。
- 应用启动时会检查上次遗留的非终态 Run，将它们收敛为 `CANCELLED`，并把待审批请求写成 `CANCELLED`，避免进程重建后出现无法继续的假活跃任务。
- 取消、失败、预算耗尽和超时都会写入终态；取消/失败落库使用不可取消清理块，避免 Run 卡在中间态。
- `RunEvent` 已使用独立 `metadataJson` 数据库列和 sealed `RunEventMetadata` variants；v6→v7 会把可解析的旧 JSON message 迁入 metadata 并生成可读摘要，普通文本事件保持原样；v7→v8 为 `AgentRun` 增加可空 `retryOfRunId`，旧 Run 初始化为无来源关联。
- 第一批生产工具包括 `app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`notes.create`、`memory.search` 和 `memory.remember`。SAFE 工具不打断用户审批，但仍写入 `approval.skipped` 审计事件；`notes.create` 和 `memory.remember` 会写入本地数据，必须经过应用侧审批和回读验证。

- `ToolExecutionResult` 和 `RunEventMetadata.ToolResult` 会携带实际命中的 `memoryIdsUsed`；任务中心直接展示这些 ID，旧事件没有该字段时按空列表兼容解码。最终 `VerifiedAgentContext` 也保存同一组 ID，后续会话不会把“模型声称用过记忆”当成审计证据。
- 对话输入区在 `/agent` 命令下提供单次「记忆」开关。关闭后，当前 Run 的规划器工具清单移除 `memory.search`，执行层再次拒绝读取并写入 `memory.recall.disabled` 事件；`memory.remember` 仍需用户审批且不受该开关影响，发送后开关自动恢复开启。

该链路的价值是先把 Run、Step、Event、审批、执行、验证、长期记忆和终态跑通，为后续更多真实工具和后台任务提供可测试 seam。

## 会话上下文

- 当前会话内的用户消息和模型回复会作为上下文参与下一轮请求。
- 普通对话每次固定注入不可覆盖 system 边界；用户原文、自定义模板、普通 assistant 回复和会话摘要都不能触发工具能力例外。
- 消息通过 `MessageOrigin` 区分普通 assistant 与应用 Agent 回复，Runtime 审计使用 `VerifiedAgentContext` 领域类型，只在 Room / JSON 存储边界序列化。Agent 总结模型只能选择 `compact / detailed` 和 `neutral / friendly / formal`；Runtime 使用真实工具字段渲染回复，非法配置改用确定性默认样式。
- 普通聊天历史和摘要转录由 `JSONObject / JSONArray` 生成外层来源结构，消息正文只进入转义后的 `content` 字段；用户或模型正文复述 `runtime_audit` / `application_agent_audit` 不能升级可信身份。
- v5 数据库迁移补消息来源，v6 迁移补 Agent 审计上下文，v7 迁移补 RunEvent metadata，v8 迁移补重试来源关联，v9 迁移补 Memory 置顶字段和 FTS 索引，v10 增加独立候选记忆表；旧消息设为 `LEGACY`，历史 assistant 按普通回复保守恢复，不推断旧 Agent 事实。
- 超出最近消息窗口的 Agent 结果最多保留 8 条结构化记录继续参与上下文，避免可信来源在压缩成普通摘要后丢失；这不代表当前轮次执行了新工具。
- 会话数量和消息内容保存在本地。
- 当历史消息超过最近窗口时，较早内容会压缩成摘要，并作为 system 上下文放入后续请求。
- 摘要 system prompt 可以追加用户模板，但禁止把普通 assistant 的工具声称写成已确认事实。
- 摘要失败时使用本地兜底摘要；兜底逻辑先截断单条正文再拼接来源标签，避免超长普通回复截掉“非工具证据”身份。

## 提示词设置

设置页二级入口「提示词设置」提供三类设备级模板：

- 普通对话：控制日常回答风格；工具执行和长期记忆声称边界由应用固定追加。
- 会话摘要 / 记忆：控制长会话压缩侧重点；事实来源边界由应用固定追加。
- Agent 回复总结：控制工具执行后的汇报详略和语气；模型只选择有限样式枚举，真实工具调用与结果由 Runtime 填充。

三类模板均支持独立开关、即时保存、恢复默认和最终 system prompt 预览。Agent 工具规划、工具风险、审批和安全策略仍由应用内部控制，不向用户开放覆盖。

## 候选记忆与治理

- 候选功能默认关闭，用户在「长期记忆」页主动开启后才会处理后续成功结束的普通对话或 Agent Run。
- 确定性规则只从明确偏好和个人事实陈述生成 `PENDING` 候选；普通问答不生成。候选保留来源会话和可选来源 Run，但不会进入正式记忆或 Agent 检索。
- API Key（含 `sk-`、GitHub、Google、AWS 等常见前缀）、token、密码、银行卡、身份证和手机号命中后记录 `BLOCKED_SENSITIVE`；正文、标签和来源摘要均只保存类别和固定提示，原文与规范化内容不落库。
- 规范化相同的正式记忆标记为 `DUPLICATE`，不会重复写入；同类型、同主题但内容不同的候选标记为 `CONFLICT`，保留旧记忆。用户可明确选择另存为新记忆，不会覆盖旧记录。
- `memory.remember` 与候选确认共用敏感过滤和去重入口，避免工具绕过治理规则。删除正式记忆前会把最近一次完整快照写入应用私有原子文件，再在 Room transaction 中删除主表和 FTS；应用重启后仍可撤销并完整恢复来源、置顶、生命周期和索引字段。

## Provider 管理

设置页二级入口「模型提供方管理」负责：

- 新增、编辑、删除模型提供方。
- 通过二维码、剪切板和 Base64 解码辅助导入配置。
- 二维码导入申请相机权限，但 Manifest 将相机声明为可选硬件；无相机设备仍可使用手动配置和其他功能。
- 请求 `GET <api-root>/models` 获取上游模型列表。
- 手动勾选允许在对话页使用的模型。
- 单个同步或批量同步模型列表。

## 本地存储

- Provider、会话、消息、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote 和 AgentMemory 保存在 Room 数据库 `xiaoling.db`。
- 数据库当前版本为 v11，启用 `exportSchema`；`XiaoLingDatabaseMigrationInstrumentedTest` 在 Android 真机执行正式 v4→v11 链路，并单独验证 v9→v11 保留正式记忆和 FTS、创建空候选表及新增可空生命周期字段。
- 旧消息迁移后统一得到 `origin=LEGACY`，`verifiedAgentContext` 默认为 `null`；v7 旧 Run 的 `retryOfRunId` 初始化为 `null`，v8 旧记忆的 `pinned=false` 并在迁移时回填 FTS，v9 正式记忆不会被倒推成候选，v10 旧记忆的生命周期字段保持空值。测试同时覆盖全新 v11 数据库创建和打开。
- AgentMemory 保存内容、标签、类型、来源会话、来源 Run、来源摘要、置信度、启用/置顶状态、可空过期时间、最近引用时间和时间戳；`AgentMemoryStore` 只向工具暴露写入与检索，`AgentMemoryManager` 独立提供 UI 管理能力。
- 记忆检索优先使用 Room FTS4 `unicode61` 做英文/标签前缀召回，并用 `LIKE` 兜底中文和任意子串；启用记忆会排除明确过期项，命中后回写 `lastReferencedAt`。结果按置顶、置信度和按类型配置的半衰期排序，衰减只影响排序，不修改正文或删除记录。
- 设置页「长期记忆」支持候选开关与确认、搜索、启用状态筛选、编辑、置顶、启停、删除确认、当前会话撤销和来源审计；来源会话与来源 Run 存在时可直接跳转。
- 设置页「数据备份与恢复」通过 Android SAF 导出/导入 ZIP；备份包含 Room 主库和 schema/app manifest，导入先校验 manifest 与真实 SQLite `user_version`，再关闭 Room、保留 `.pre-restore` 安全副本并替换数据库，完成后必须重启应用。
- 备份不导出 API Key 明文；Provider 表中的密文仍依赖当前 Android Keystore，跨设备或密钥丢失时不能仅凭数据库恢复凭据。未来可增加不含凭据的 Provider 元数据迁移向导。
- 长期记忆的引用审计目前落在 Agent Run 的 ToolResult 和 VerifiedAgentContext；删除或禁用记忆后新 Run 不会产生对应 ID，历史 Run 保留原始审计快照，不回写旧事件。
- `xiaoling` 和 `xiaoling_conversations` SharedPreferences 只作为旧数据迁移来源；迁移成功后不会反复恢复旧数据。
- 主题、候选记忆开关与三类提示词偏好保存在 `xiaoling_ui` SharedPreferences。
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
- Responses Adapter 已支持文本消息和 `function_call / function_call_output` typed Items；当前 Agent Runtime 仍使用提示词 JSON 规划单次工具调用，Reasoning/Image/File Items 与完整消息 parts 持久化仍待实现。
- `/agent` 目前只接入第一批应用内低风险工具；任务中心已支持失败终态安全重新运行。进程重建后的恢复边界策略已经落地：只有仍处于 `WAITING_APPROVAL`、存在 `PENDING` 审批且尚未出现工具执行/验证记录的 Run 可原地恢复，其余中间态必须安全重新运行。
- 启动协调器已保留 `APPROVAL_WAIT` Run 并把待审批请求重建到当前会话；恢复审批批准后，Runtime 使用持久化工具参数从原审批步骤继续执行、验证和总结，并把事件与终态写回原 Run。执行/验证中 Run 仍收敛为 `CANCELLED`；跨进程记忆删除撤销、记忆过期、时间衰减、单次召回关闭与实际引用 ID 审计已交付，后台任务、Skill 和更多真实工具仍需按路线图继续补齐。
- 恢复测试同时覆盖同 Run 完成、恢复工具失败写入原 Run `FAILED`，以及失败后安全重试必须二次确认；Room instrumentation 测试覆盖持久化审批重建、批准和原 Run 完成的数据链路。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
