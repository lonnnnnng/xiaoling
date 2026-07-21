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

当前发布版本：`v0.1.10`（`versionCode 11`）

## 模块职责

| 模块 | 关键文件 | 职责 |
|---|---|---|
| App/UI | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | 「对话 / 设置」双入口、会话列表、消息输入、普通聊天模型选择、Agent Profile 与模型提供方管理页面。 |
| ViewModel | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | 维护页面状态、会话上下文、摘要压缩、模型同步、普通对话发送、Agent Profile 选择和前台 Workflow 编排。 |
| Network | `app/src/main/java/com/longdev/xiaoling/network/LlmProviderAdapter.kt`、`OpenAiCompatibleClient.kt` | Adapter 负责 OpenAI-compatible URL、payload 与响应协议；Client 负责 HTTP、鉴权 Header、取消、计时和 SSE 读取。 |
| URL | `app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | 将用户输入的 API 根地址归一化成 `/models`、`/chat/completions` 和 `/responses` 请求地址。 |
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、AgentProfile、Conversation、Message/MessagePart、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory 和 AgentMemoryCandidate 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Conversation/Message Repository、Agent Profile Store、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | Agent Profile 策略、最小 Agent Runtime、Run Ledger interface、真实低风险 Tool Registry、交互式审批 gate 和可审计运行链路。 |
| Device | `app/src/main/java/com/longdev/xiaoling/device/` | Accessibility 观察与有限动作、授权/连接健康检查、有界脱敏 snapshot、短生命周期节点引用、隐私/应用白名单、动作后验证和前台直接 Agent 门禁。 |
| Automation | `app/src/main/java/com/longdev/xiaoling/automation/`、`storage/RoomWorkflowRepository.kt` | Workflow/ScheduledTask 状态、周期规则、Room Ledger、前台手动触发、WorkManager 非精确调度、后台执行、进程内 Worker 所有权、启动恢复候选隔离、`STOP_REQUESTED` 持久化停止与结果通知。 |
| Prompt | `app/src/main/java/com/longdev/xiaoling/prompt/` | 三类可配置提示词的默认模板、最终 system prompt 组合和不可覆盖事实边界。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和主要流程集中在 `XiaoLingViewModel`：

- Provider 管理、模型同步、会话切换、发送请求、摘要生成、流式更新和错误提示由同一个 ViewModel 维护。
- `LlmProviderAdapter` 已成为模型协议边界，当前 `OpenAiCompatibleAdapter` 统一处理模型列表、Chat Completions、Responses API 请求与响应映射；`OpenAiCompatibleClient` 只保留 HTTP 传输、取消、计时和 SSE 读取。普通聊天和 Agent 仍复用同一 Client 与 Adapter 实例链路。
- Provider、Agent Profile、会话、消息、最小 Agent Run、审批请求、独立 ToolCall/ToolResult、长期记忆、声明式 Skill 和 Workflow Ledger 已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- Room compiler 已从 KAPT 切换到 KSP，`app/schemas/` 保存历史 v4、v6-v27 Schema；迁移测试覆盖 v4→v27、各关键增量迁移和全新 v27 建库。
- UI 以聊天消息为中心，已能在 `/agent` 消息下方显示当前 Run 时间线和最小审批卡片；设置页 Agent 任务中心可以筛选任务、按调用查看 Ledger-first 四阶段工具明细、完整结果/步骤/审批/事件和双源一致性告警，并对可重试终态创建关联的新 Run。工作流页支持 1 至 8 步创建/编辑/排序、一次/每日/每周计划、定义与运行快照展开、来源 Run 标识和新 Run 重试。
- `WAITING_APPROVAL` Run 可从任意已验证工具前缀恢复链尾审批；所有 ToolResult 与 `PASSED` 验证均已落库时，可补齐最后验证 Step 并用本地可信总结完成原 Run。提交状态未知、验证事实不完整和旧模型协程仍保持 fail-closed。

当前已经建立最小 domain、data、runtime 和 tool 边界。后续功能不应继续堆进 `sendMessage()`，应把仍留在 ViewModel 的上下文、网络和会话编排逐步迁入现有边界。

## 对话请求

用户在对话页输入消息并发送后：

1. 校验 `Base URL`、已启用模型和消息内容。
2. 从设备级网络偏好读取 `User-Agent`；默认模拟指定 Codex Desktop 版本，用户可在设置页修改或恢复默认。模型列表、Chat Completions、Responses 和后台 Agent 共用同一 Header 构造入口。
3. 根据当前接口模式请求 `POST <api-root>/chat/completions` 或 `POST <api-root>/responses`。
4. Chat Completions 模式发送 `model`、`messages`、`temperature`、`top_p`、`max_tokens` 和 `stream`。
5. Responses API 模式发送 `model`、结构化 `input` Item 数组、`temperature`、`top_p`、`max_output_tokens` 和 `stream`；USER Image/Document part 分别映射为 `input_text + input_image/input_file`，附件以 Data URL 发送，PDF 使用 `detail=auto`。Adapter 还支持通过同一 `call_id` 关联的 `function_call / function_call_output`。当前 OpenAI-compatible 兼容边界下，Chat Completions 遇到附件会在请求构造阶段明确拒绝。
6. 非流式响应从常见字段中提取文本。
7. SSE 流式响应读取 `data:` 行，聚合 Chat Completions `choices[].delta.content` 或 Responses `delta` 文本。
8. UI 以 30ms 节流刷新流式内容，完成或失败时强制 flush。
9. 最终消息携带结构化 `MessageMeta`，包括模型、接口模式、是否流式、请求地址、首字耗时、总耗时和错误信息。
10. 发送期间可以点击输入区右下角停止按钮，取消 ViewModel Job 和底层 OkHttp Call；流式迟到事件不会继续写入 UI。

## 消息 parts

- `MessagePart.Text / Reasoning / Image / Document / Tool` 是当前结构化消息模型。Image 保存文件名、规范 MIME、原始字节和 `AUTO` detail；Document 保存原始字节、受预算约束的 UTF-8 提取文本或 PDF 页数，以及 `AUTO` detail，DOCX/PPTX/XLSX 则保存经本地 ZIP/OPC 结构校验的原始包；Reasoning 保存稳定 part ID、`PROVIDER_SUMMARY` 来源、供应商 item ID、summary index 和摘要正文；Tool 保存工具名、排序参数、结果、成功状态、验证状态、记忆引用和知识引用。
- Room v23 为 `message_parts` 增加可空 `reasoningSource / providerItemId / summaryIndex`。v22→v23 不回填 Reasoning；历史 Text/Tool 三列保持空，避免从旧正文或工具审计猜造模型过程。
- Room v24 增加可空 `mimeType / fileName / binaryData / imageDetail`。v23→v24 不补造历史 Image；图片字节与消息在同一事务写入 BLOB，数据库 ZIP 备份自然包含附件，不依赖长期 URI 权限。
- Room v25 增加可空 `documentExtractedText / documentPageCount / documentDetail`，Document 复用附件 MIME、文件名和 BLOB。v24→v25 不补造历史 Document；原始文件和提取文本在同一事务保存。
- `ImageAttachmentReader` 从系统 URI 有界读取最多 8 MB，先检查可用文件大小，再校验允许 MIME、PNG/JPEG/WEBP 文件签名和 Android 解码尺寸。进入 `ImageAttachment` 时复制字节，选择器授权随后即可失效。
- `DocumentAttachmentReader` 同样以 8 MB 有界读取，支持 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX、XLSX。文件扩展名、规范 MIME、`%PDF-` 与 ZIP 签名在领域策略统一解析：`.pdf` 即使被 DocumentsProvider 错报为 `text/plain` 也必须进入 `PdfRenderer`，OpenXML 只接受匹配 MIME、空 MIME 或通用 ZIP/二进制 MIME，PDF/富文档内容与扩展名冲突时拒绝。PDF 复制到私有临时文件并由 `PdfRenderer` 验证真实页数，最多 50 页；文本使用严格 UTF-8 解码并限制 200,000 字符。`OpenXmlDocumentPolicy` 先解析中央目录并逐条核对 local header、文件名、加密位、磁盘号、ZIP64 extra 和实际数据范围，再以固定缓冲区流式解压核对条目集合、CRC 和真实展开量；条目最多 4,096 个，声明及实际展开总量都不得超过 64 MB，并要求非空 `[Content_Types].xml` 与 `word/document.xml`、`ppt/presentation.xml` 或 `xl/workbook.xml`。进入 `DocumentAttachment` 时复制原始字节，文本类同时保存规范提取文本。
- 普通对话的“推理”开关默认关闭并持久化到设备偏好。开启后只有 Responses payload 加入 `reasoning.summary=auto`；非流式解析 `output[].type=reasoning` 的 `summary_text`，流式按 `response.reasoning_summary_text.delta/done` 聚合。`ProviderMessagePartPolicy` 去重来源身份并固定 Reasoning 在 Text 前。
- 原始 `reasoning_text` 不进入最终正文或消息 parts；Chat Completions 非标准 `reasoning/reasoning_content` 也不读取。debug 包记录请求、响应或 SSE 时先通过 `NetworkDebugLogSanitizer` 递归脱敏图片 Data URL、`file_data`、生成图片结果、原始/加密推理字段；带敏感标记但无法解析的 payload 整体失败关闭。官方协议依据：[File inputs](https://developers.openai.com/api/docs/guides/file-inputs)、[Images and vision](https://developers.openai.com/api/docs/guides/images-vision)、[Reasoning guide](https://developers.openai.com/api/docs/guides/reasoning) 与 [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create/)。
- `AgentMessagePartPolicy` 同时核对 `MessageOrigin.AGENT_RESULT`、`VerifiedAgentContext` 和已存 parts。普通 assistant 可以保留供应商 Reasoning，但不能生成 Tool；Agent 结果忽略 Reasoning，只按可信上下文投影 Tool，内容漂移时 fail-closed 回退。
- `MessageRepository` 是前台会话和后台 Workflow 的统一写入口，在同一事务内写 message 与 parts；覆盖同一消息前先删除旧 parts，避免缩短后的消息残留孤立 Tool 行。Image/Document BLOB 只在加载当前会话时读取，非当前会话使用轻量 parts；轻量快照回写时 Repository 会保留数据库中未加载的附件 BLOB。发送普通对话前必须等待用户消息与附件事务提交，切换会话则先完成全部 parts 读取再原子替换界面状态。`ConversationRepository.save()` 对普通前台快照只做增量 upsert，不根据快照差集删除；ViewModel 把用户明确删除的会话 ID 保留到事务成功后再清除，Repository 也会在事务前过滤删除集合，因此保存任务取消或失败不会丢失删除意图，陈旧快照不能复活已删会话，也不会误删后台刚创建的独立会话。旧 SharedPreferences 会话进入 Room 时也自动获得 Text part。
- Compose 在同一消息气泡内按顺序渲染 Image、Document、Reasoning、Text 和 Tool；附件按钮使用图片/文档菜单，待发送文档显示文件名、大小、页数或字符数并可移除，历史 Document 保留同样元数据。Reasoning 默认折叠并显示“供应商提供”，Tool 继续使用非嵌套证据区。

## 最小 Agent 链路

当前提供一个最小 Agent 执行入口：在对话框输入 `/agent <目标>`。

这条链路使用当前选中的 Agent Profile 固定 Provider、模型、API 模式、角色提示和能力白名单；`XiaoLingToolRegistry` 只在 Profile 授权范围内执行应用内工具，最终事实由 Runtime 根据真实工具记录渲染：

1. 创建 `AgentRun`，写入唯一 `agent.profile.selected` typed event 冻结完整 Profile 快照，状态从 `QUEUED` 进入 `THINKING`。
2. 请求当前模型只返回 `action=tool` 或 `action=complete` JSON；规划器每轮只选择一个已注册工具，并接收前面已经执行和验证的结构化结果。兼容模型若把同一个已声明工具名同时写入 `action` 与 `tool`，解析器只在两者完全一致时归一化为工具调用；未知动作或不一致工具名仍拒绝。
3. 进入 `tool.validate` 步骤，校验 JSON Schema、未知参数、可插拔业务规则、Android 权限、工具调用预算和重复调用风险。
4. SAFE 工具跳过交互审批并写入 `approval.skipped` 审计事件；非 SAFE 工具进入 `WAITING_APPROVAL`，先写入 `ApprovalRequest`，再在对话区显示审批卡片；用户批准后继续执行，用户拒绝后 Run 进入失败终态。审批结束后会再次读取 Android 权限，防止用户等待期间从系统设置撤权后仍执行工具。
5. 执行工具，写入可读 `RunEvent.message` 和独立 typed metadata，包括工具名、参数、结果、耗时、成功状态和可选验证状态；`notes.create` 与 `memory.remember` 会在写入后回读验证，回读不一致时记录 `verified=false`，不会宣称完成。
6. 进入 `VERIFYING` 后先第三次读取 Android 权限，再按工具定义检查“结果可读”或“Executor 已回读验证”；工具执行期间撤权时保留已经发生的 `tool.result`，执行步骤保持 `COMPLETED`，验证步骤和 Run 进入失败终态，不能把结果宣称为已验证完成。
7. 工具验证后重新进入 `THINKING`；模型可继续选择下一工具，应用重复步骤 3-6，最多执行 4 次。相同工具和参数重复出现时按循环风险终止。
8. 模型返回 `complete` 后，根据用户提示偏好选择受限的详略和语气枚举；Runtime 使用全部真实工具结果渲染最终回复，样式选择超时、为空或非法时使用确定性兜底。
9. 完成后将 Run 标记为 `COMPLETED`，并在对话区输出包含全部工具结果的总结。

当前最小 Runtime 已具备以下运行约束：

- `AgentRuntimeOptions` 默认把单个 Run 限制为最多 4 次工具调用，并控制模型/工具执行预算、模型步骤超时和工具步骤超时。`AgentExecutionBudget` 使用与工具 duration 相同的单调时钟累计规划、工具和总结执行段；用户阅读审批卡片的等待时间不消耗执行预算。剩余 Run 预算小于或等于 Step 上限时固定归因 Run timeout，否则归因 Step timeout；调用方的外部 `TimeoutCancellationException` 仍按取消收敛，不伪装成预算耗尽。
- `ToolDefinition` 统一声明输入类型、长度/范围/枚举、业务校验器、风险、确认策略、Android 权限、后台能力、超时和验证策略；风险与确认不信任模型声明。
- `AgentProfileRecord / AgentProfileSnapshot` 固定名称、标识、Provider、模型、API 模式、系统提示词、上下文策略、工具白名单、Skill 白名单和记忆开关。设置页支持新增、编辑、选择和删除；至少保留一个 Profile，Provider 删除或模型停用前会检查 Agent 绑定关系。
- `ProfileScopedToolRegistry` 在 `availableTools()`、`definition()`、`execute()` 和已提交结果验证四个入口强制 Profile 工具白名单；`SkillScopedToolRegistry` 只能在此基础上继续取交集。Profile 系统提示词被明确包裹并声明只能调整表达与授权能力内偏好，不能修改协议、安全规则或执行事实。
- `/agent` 单次记忆开关与 Profile 记忆开关取交集；Profile 关闭记忆时单次 Run 不能重新打开。前台 Workflow 一次执行固定同一 Profile，后台 Worker 在一次执行开始时读取并缓存同一 Profile，避免步骤间配置漂移。
- 模型提示使用 `object/properties/required/additionalProperties=false` JSON Schema；解析层先按原始 JSON primitive 拒绝错误类型和非 object `arguments`，再规范化到字符串 Map 供 Runtime 做长度/范围/枚举与业务校验，不自动补字段或接受未知字段。
- `ToolPermissionChecker` 默认 fail-closed；生产链路使用 `ContextCompat.checkSelfPermission` 在参数校验、审批结束后执行前和工具返回后验证前三个检查点读取定义中的 Android 权限。审批期间撤权不会创建 `tool.execute`，工具执行期间撤权会保留成功结果审计但拒绝验证与总结。
- `ToolExecutionReceipt` 位于现有执行 seam：Executor 可返回 ToolCall ID、业务 operation ID、可选幂等键和提交状态，Runtime 在成功 `tool.result` 落库前校验回执必须属于当前 ToolCall。回执与执行时 `ToolReplaySafety` 声明快照随 typed metadata 持久化，并在任务中心事件中显示调用、操作、状态、重放声明和“幂等证明已记录/未记录”，原始幂等键不直接展示；旧事件没有快照时按 `RESTART_REQUIRED` 解码。
- Room v20 的 `agent_tool_calls` 以 ToolCall ID 为主键，保存 Run、工具、风险、排序后的参数，以及 proposed/validated RunEvent 锚点；`agent_tool_results` 以 ToolCall ID 为主键，保存结果事件、正文、显式错误、耗时、Executor 验证、最终验证、记忆引用、重放声明和拆列后的执行回执。`RoomAgentRunRepository.toolLedger(runId)` 提供单 Run 查询，`recentRunDetails()` 通过 `getToolCallsForRuns / getToolResultsForRuns` 批量加载最近 Run，避免任务中心 N+1 查询。
- `RoomAgentRunRepository.appendEvent()` 在同一 Room 事务中先写 RunEvent，再按 typed metadata 双写工具账本。相同 ToolCall 的 Run、工具、风险或参数漂移会回滚整个事务；`tool.verify` 通过新增的可选 ToolCall ID 精确更新结果。Run 进入终态后，外部 Runtime 的迟到 Event 和 Tool Ledger 双写直接拒绝，只有 Repository 自己的最终 `run.status` 审计可显式放行；迟到 Step 追加会失败，Step 更新与一次性 Approval 决定会被忽略。任务中心、受限恢复和失败 Run 重试副作用判断对账本非空的新 Run 使用 Ledger-first，并以 typed RunEvent 核对身份、字段、派生错误、时间、锚点和顺序；部分缺失或漂移在展示层显示审计告警，在安全策略中 fail-safe。v19 迁移后账本为空的旧 Run 继续回退 typed RunEvent，缺少 ToolCall ID 的结果/验证独立显示为“关联未知”，不按工具名猜测归属。三条消费路径共享 `AgentToolLedgerConsistencyPolicy`，避免双源规则漂移；Run 质量和模型遥测没有等价 Tool Ledger 字段，继续读取 Step 与 `llm.*` typed event。
- `AgentRunRecoveryEvidencePolicy` 为恢复提供独立证据读取：v20 非空账本按 proposed 事件锚点重建调用顺序，要求调用与结果一一对应，并核对 proposed→validated→result→verified 的身份、字段、时间和顺序；任何部分账本、额外事件或双源漂移均返回 `Invalid`，不得退回事件路径。账本完全为空时才进入旧 typed event fallback；旧验证缺少 ToolCall ID 时返回 `Invalid`，由恢复/重试策略升级为 `EVIDENCE_INCOMPLETE`，不按工具名或事件顺序猜配。`ToolExecutionRecoveryEvidencePolicy` 继续为 `notes.create / memory.remember` 校验执行时与当前定义均为 `IDEMPOTENT_BY_KEY`、结果成功、回执 `COMMITTED` 且幂等键完整。`AgentRunResumePolicy` 还允许所有结果成功、所有验证 `PASSED` 且 Step/Ledger/Event/Profile 完全一致的 `VERIFYING` Run 恢复本地收尾；该路径不调用工具或模型，不恢复旧规划协程、提交状态未知的执行栈或 Workflow 后续步骤。
- `AgentExecutionBudgetEvidencePolicy` 读取 `run.execution_budget.updated` typed event。新 Run 先写 `0 / total`，每个成功模型/工具段后写累计快照；恢复使用最后快照构造同一总额与已消耗预算。首条非零、结构缺失、数值越界、同 Run 总额漂移、累计回退，或最后 ToolResult 晚于最后预算快照均返回 `Invalid` 并由恢复策略要求新 Run；最后一条规则关闭“工具结果已提交、预算事件尚未跟上”时的进程终止窗口。完全没有快照的升级前 Run 从当前默认总额的零值兼容起点开始，并在继续恢复前先持久化该起点，后续再次中断不再重复清零。预算事件在任务中心展示已消耗、总预算和剩余时间。
- Runtime 接收 `FOREGROUND / BACKGROUND` 执行来源；后台来源只能执行 `supportsBackground=true` 的工具。当前仅当前时间、会话查询、笔记查询和长期记忆查询这 6 个 SAFE 只读工具开放后台；`notes.create / memory.remember` 在后台规划到审批步骤时直接进入 `BLOCKED`，不会调用审批 Gate。
- Registry 初始化会拒绝重复工具名；`memory.remember` 已通过可插拔业务校验器限制标签数量和单标签长度。
- `AgentRunUseCase` 使用 reporting ledger 回读 Room 快照，ViewModel 将 `AgentRun / AgentStep / RunEvent` 渲染成当前对话内的运行时间线。
- 审批使用 suspend `ApprovalGate` 挂起等待 UI 决策；`ApprovalRequest` 独立记录待确认工具、风险、参数、过期策略、决定结果和决定原因。
- 当前交互审批不按固定倒计时主动过期，只有用户批准、拒绝、停止生成或应用启动恢复收敛时改变状态；`EXPIRED` 保留给后续明确截止时间的工具策略。
- 当前 ViewModel 会按 conversationId 缓存正在显示的 Run 时间线和审批卡片；仅切换会话/页面再返回不会丢失当前活跃卡片。
- 设置页「Agent 任务中心」从 Room 读取最近 50 条 Run，支持全部、需确认、处理中、可重试、已完成五档筛选；展开后按 ToolCall 展示 proposed、validated、result、verified 四阶段和完整 content/success/verified/duration。数据源明确标注为“独立工具账本”或“旧 Run 事件兼容”，双源不一致显示稳定告警码；原事件时间线、步骤和审批请求仍完整保留。事件展示直接消费 Repository 解码后的 typed metadata，旧纯文本事件回退显示 `message`。最新 `run.recovery_failed` 会额外显示在详情顶部的错误状态带；不可原地恢复的最新 `run.recovered` 还会在任务卡与详情顶部显示恢复类型、稳定处置码、策略原因、证据边界和建议动作。
- `AgentRunMetricsPolicy` 只根据持久化 Run、Step、Approval 和 typed RunEvent 汇总指标，不依赖页面瞬时状态：单 Run 统计创建到终态的耗时、模型/工具/审批次数，并从 `llm.request.completed` 聚合模型总耗时、平均 TTFB、Prompt 字节和 Token usage；历史汇总只用终态 Run 计算成功率、平均耗时和失败分布，活动 Run 不进入质量分母。任务中心的汇总带、列表卡和详情区使用同一纯呈现函数，避免三处口径漂移。
- Agent 规划和总结固定使用非流式请求。网络层在首个响应 body 字节实际可读后记录 TTFB，以最终 JSON 请求体的 UTF-8 字节数记录 Prompt 规模，并兼容 Chat Completions 的 `prompt_tokens / completion_tokens` 与 Responses 的 `input_tokens / output_tokens`。上游缺失 usage 时字段保持 `null`；规划 JSON 解析失败时，已经返回的请求遥测仍先写入 RunEvent，再收敛 Run 失败。
- `FAILED / CANCELLED / BUDGET_EXHAUSTED` 可重新运行。重试在来源会话追加新的 `/agent <goal>` 消息，使用当前选中的 Agent Profile 创建带 `retryOfRunId` 的新 Run；旧 Run 的 Profile 快照、状态、结果、步骤和事件不修改。非空账本中的非 SAFE 调用只要 `result.success=true`，或回执状态为 `COMMITTED / UNKNOWN`，UI 就先要求二次确认；账本异常也按可能已有副作用处理。明确失败且回执为 `NOT_COMMITTED`、或只完成 proposed/validated 尚未执行时，不单独增加确认。账本全空的旧 Run 才使用 typed event 成功结果回退；恢复记录表明中断发生在 `EXECUTING/VERIFYING`，或 `tool.execute/tool.verify` 步骤以失败/取消结束时仍按原规则确认。
- `AgentTaskRetryPolicy.assessEvidence()` 将账本、旧 typed event、回执状态和执行中断统一投影为 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE`。任务中心卡片直接显示分类码、稳定原因和建议动作；高风险或不完整证据的确认弹窗继续显示完整边界。确认提交时重新评估当前 Run：状态不可重试时关闭弹窗，证据码变化时更新弹窗并拒绝本次旧确认，只有分类稳定后才继续；分类不会改变“旧 Run 不修改、只创建关联新 Run”的边界。
- 启动 `closeInterruptedRuns()` 在取消步骤和审批前按原始 Run 状态冻结重试证据与恢复处置，并写入 `RunEventMetadata.Recovery`。`EXECUTING/VERIFYING` 无结果按 `COMMIT_UNKNOWN`，纯 THINKING 且无副作用按 `NOT_COMMITTED`，无效 Ledger 按 `EVIDENCE_INCOMPLETE`；`AgentRunResumePolicy` 的所有 `RESTART_REQUIRED` 构造都必须携带 `AgentRunRestartDispositionCode`、策略原因、证据边界和建议动作。可原地恢复的审批/已提交验证候选不写取消证据。重试时使用快照还原收敛前中断边界，再重新评估当前 Ledger；启动清理把原 `PENDING` 步骤写成 `CANCELLED` 不会被误判成副作用中断，Ledger 分类真正漂移时仍升级为 `EVIDENCE_INCOMPLETE`。AgentRun 状态更新由 DAO 原子限定为“当前仍非终态”，子账本写入再核对所属 Run 与 Approval 当前状态；启动恢复或用户停止写入的 `CANCELLED` 及错误证据不能被迟到模型、工具、Step 或审批覆盖，拒绝的后到写入也不追加虚假事件。新增字段只进入 metadata JSON，Room Schema 不变；旧事件缺字段按空值继续使用原推导路径，未知未来恢复类型降级为 `RESTART_REQUIRED`，未知处置码降级为恢复证据无效。
- `ScheduledWorkflowReentryCoordinator` 在 Worker 重入时先检查当前 ScheduledTask 是否仍为 `RUNNING`；只有该状态才按 `ScheduledTask -> WorkflowRun -> AgentRun` 关联链定向关闭旧执行栈，Agent、Workflow、ScheduledTask 按顺序收敛后才发送结果通知。普通 `SCHEDULED` 任务继续走正常 claim；重入不恢复旧模型协程、不继续 Workflow 后续步骤，也不返回 `Result.retry`。按 ID 的 Agent/Workflow/Task 对账入口保证其他前台 Run 不受影响，周期下一实例仍只在旧任务进入终态后物化。
- `ScheduledWorkflowProcessExecutionRegistry` 在 Worker 构造 Repository、重入对账和 claim 之前登记 Task ID，并用引用计数容纳同 ID 的重叠调用。`StartupRecoveryCoordinator` 在同一互斥边界读取当前注册集合并冻结活动 AgentRun、WorkflowRun 和 RUNNING ScheduledTask；快照完成前新 Worker 不能注册或访问 Room。`RoomWorkflowRepository.startupRecoveryCandidates()` 在事务内沿当前 Task→Workflow→Agent/Step 关联链生成排除集合，ViewModel 的审批恢复、已提交/已验证恢复、旧 Agent 关闭和 Workflow/Task 对账之后只消费冻结 ID。实现不依赖墙上时间，不增加 Room owner token 或 Schema。
- 待审批恢复和 `notes.create / memory.remember` 已提交结果恢复读取原 Run 的 `agent.profile.selected` 快照并重新构造 Profile/Skill 双层 Registry。历史 Run 没有该事件时走旧兼容路径；重复、无法解析、包含未注册工具或 Skill 超出 Profile 工具面的审计均拒绝恢复。
- 重试正式启动时 ViewModel 选中来源会话并发出一次性导航信号，根 UI 回到对话页；重新触发的写工具仍走正常审批，审批卡不会隐藏在任务中心后台。
- 应用启动时会保留尚未执行任何工具的 `WAITING_APPROVAL` Run；批准后先执行持久化的首个工具，再携带其已验证结果继续同一 Run 的多步规划。已经进入工具执行/验证步骤的多步 Run 默认会安全收敛为 `CANCELLED`，其所有 `PENDING/RUNNING` Step 同步改为 `CANCELLED`。第一个受限例外是最后一个 `notes.create` 或 `memory.remember` 已落库完整 `COMMITTED + IDEMPOTENT_BY_KEY` 结果且尚未验证：启动时补齐原 execution Step，按 operation ID 只读回读业务记录，写入 `tool.verify` 和 `recovery.summarize`，再以本地可信总结完成原 Run。第二个例外适用于通用工具：Run 已在 `VERIFYING`，全部 ToolResult 成功、全部 `tool.verify` 为 `PASSED` 且最后验证 Step 只差控制面收尾时，恢复入口重建 `completedTools`、调用数和指纹，最多把该 Step 更新为 `COMPLETED`，随后直接复用 `completeRecoveredRun()`。两条路径都不恢复旧模型协程；前者不重复调用写工具，后者完全不触碰 Executor/LLM 或追加验证事实。若属于 Workflow，启动对账先保留候选，恢复后写回当前步骤输出并把剩余 Workflow 收敛为 `FAILED`，后续通过关联新 Run 复用成功前缀。
- 取消、失败、预算耗尽和超时都会写入终态；取消/失败落库使用不可取消清理块，避免 Run 卡在中间态。预算内部的 Step/Run timeout 转换为 `AgentTimeoutException`，调用方主动取消或外层超时保持协程取消语义并写入 `CANCELLED`。
- `RunEvent` 已使用独立 `metadataJson` 数据库列和 sealed `RunEventMetadata` variants；v6→v7 会把可解析的旧 JSON message 迁入 metadata 并生成可读摘要，普通文本事件保持原样；v7→v8 为 `AgentRun` 增加可空 `retryOfRunId`，旧 Run 初始化为无来源关联。
- 第一批生产工具包括 `app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`notes.create`、`memory.search` 和 `memory.remember`。SAFE 工具不打断用户审批，但仍写入 `approval.skipped` 审计事件；`notes.create` 和 `memory.remember` 会写入本地数据，必须经过应用侧审批和回读验证。
- `notes.create / memory.remember` 在存储层返回真实记录 ID 后写入 `COMMITTED` 执行回执；回读失败仍保留 operation ID。两者都使用 ToolCall ID 并声明 `IDEMPOTENT_BY_KEY`。笔记直接由唯一索引绑定载荷；长期记忆因为可编辑、可删除且有语义去重，使用独立 `agent_memory_operations` 主键映射保存 memory ID、原始载荷 SHA-256 和提交结果业务快照 SHA-256。同键同载荷只返回原 operation，同键载荷漂移在写入前抛出冲突；映射目标被删除时明确失败，不重新创建。
- `ToolRegistry.supportsCommittedEffectVerification()` 把“存储幂等”与“允许启动恢复”分开。生产 Registry 仅为 `notes.create` 和 `memory.remember` 返回 true。记忆恢复从持久化 Run Context 重建来源请求，并按 operation 校验 payload、ToolCall、memory ID、内容、标签、类型、来源和置信度；置顶、引用时间和尚未到期的未来过期时间不影响业务快照。禁用、过期、删除、业务字段编辑或缺少 v19 结果哈希时返回稳定失败原因并由 Runtime fail-closed。Registry 把八类失败映射为 `ToolRecoveryFailure`，Runtime 通过 `run.recovery_failed` 的 `RunEventMetadata.RecoveryFailure` 独立保存工具、错误码、原因和建议；普通恢复异常仍沿用 `run.failed`。
- `AgentRunUseCase` 通过 Room `AgentSkillCatalog` 合并内置和本地声明式 Skill，按目标关键词或触发示例稳定选择最多 3 个已启用项，并通过 `SkillScopedToolRegistry` 只向规划器暴露 Skill 声明的已注册工具；未命中 Skill 时保留原工具集。Skill 选择写入 `skill.selected` RunEvent，包含 `id@version` 审计引用，不能修改工具风险、审批、权限或验证策略。
- 设置页「Agent Skills」使用系统文件选择器导入 UTF-8 JSON。当前格式固定为 `schemaVersion=1`、`source=local`，最多 64 KiB；解析器拒绝未知字段和未注册工具，并要求文件声明的最高风险与 Android 权限和真实 `ToolDefinition` 完全一致。本地 Skill 不能覆盖内置 ID，同 ID 更新必须提升版本；用户可以启停全部 Skill，只能删除本地 Skill。可导入示例见 [`docs/examples/daily-review.skill.json`](examples/daily-review.skill.json)。
- 审批恢复不重新按当前目标选择 Skill：先读取原 Run 的 `skill.selected` ID/版本，停用不影响该 Run；本地 Skill 被删除或版本发生变化时，恢复在批准决定写入前失败并要求创建新 Run，避免等待期间工具白名单或指令漂移。

## Workflow Ledger

- 设置页「工作流」可保存和编辑 1 至 8 个顺序 Agent 目标、启停定义、手动执行并展开查看定义与全部运行快照；同一工作流存在 `QUEUED / RUNNING` Run 时，Repository 在事务内拒绝重复启动和编辑。
- Room v16 将未来定义保存到 `workflow_step_definitions`。每次创建 Run 时原子物化独立 `WorkflowStep`，冻结定义步骤 ID、顺序、幂等键、目标和输入快照；后续定义编辑只影响未来 Run，历史 Run 不回写。
- 前台和 WorkManager 后台都按步骤顺序创建独立 Agent Run。每一步启动前把连续成功前缀的已验证输出冻结进输入快照，完成后写入输出快照；后续步骤通过 `WorkflowStepPromptPolicy` 接收这些输出，同时继续独立执行 Schema、权限、风险、审批和验证。
- 普通 Workflow 输出继续兼容旧纯文本快照；执行过 `knowledge.search` 的输出改用带 schema 版本的 JSON 保存正文、是否需要当前知识证据、引用数组和原始引用数量。准备步骤与真正关联 Agent Run 时都会调用 `retainCurrentReferences()` 重新核对完整引用集合，引用缺失、损坏、禁用、替换或删除时只从新输入投影中移除正文，不回写来源 Run；最终结果和任务中心展示统一通过 codec 读取正文，避免把快照 JSON 当作用户结果。
- 旧单步骤兼容入口收到同一 Agent Run 的重复快照回调时，优先命中已经关联该 Run 的步骤，再执行幂等状态刷新；不会因为步骤已经进入 `RUNNING` 就错误关联到后续步骤。
- `BLOCKED / FAILED / CANCELLED` Workflow Run 可创建新 Run 重试。新 Run 通过 `retryOfWorkflowRunId` 关联来源，把连续成功前缀标为 `SKIPPED` 并记录 `reusedFromStepId`，首个未完成步骤及后续步骤恢复为 `PENDING`；旧 Run 不修改。
- 应用启动时先冻结旧进程恢复候选并排除当前进程已注册 Worker 链，再按原策略恢复/关闭候选 Agent Run，最后只对账候选 Workflow Run：可恢复的 `WAITING_APPROVAL` 保持运行中；批准并完成当前步骤后继续同一 Workflow Run 的下一步骤。若进程重建时当前 Agent 已完成但后续步骤尚未启动，则先保留当前输出，再把旧 Run 收敛为失败，用户通过新 Run 重试复用成功前缀，绝不自动重放可能有副作用的步骤。
- `ScheduledWorkflowOrchestrator` 在步骤持久化返回后、更新内存步骤列表和启动下一步骤前提供专用故障注入 seam。模拟进程终止会直接重新抛出，不进入普通 `FAILED/CANCELLED` 结算；生产使用 no-op 实现。启动对账因此读取到“第一步 `COMPLETED`、后续步骤 `PENDING`、Workflow Run 仍活动”的真实中间状态，旧 Run 随后失败关闭，关联新 Run 通过 `reusedFromStepId` 复用成功前缀。
- Room v14 新增结构化 `ScheduledTask`，Room v15 新增唯一 `workflow_schedules` 规则，Room v16 新增 Workflow 步骤定义与步骤快照，Room v17 为 `agent_notes.idempotencyKey` 增加可空唯一索引，Room v18 新增 `agent_memory_operations` 幂等操作账本，Room v19 为 operation 增加可空 `resultHash`，Room v20 新增 `agent_tool_calls / agent_tool_results`；v18 记忆 operation 和 v19 RunEvent 均不补造缺失证据。
- 工作流页可创建 1 分钟至 7 天的一次性计划并取消尚未执行的计划。`OneTimeWorkRequest.setInitialDelay` 配合联网约束和唯一工作名提供非精确调度；产品文案明确系统可能延迟，不承诺准点。
- Daily/Weekly 规则保存本地墙上时间、`ZoneId` 和可选周几。实现不使用 `PeriodicWorkRequest`：规则只维护一个未来 OneTime 实例；实例进入任意终态后，按规则时区计算并物化下一未来实例，每次实例均使用新的 ScheduledTask、WorkRequest、Workflow Run 和 Agent Run ID。
- 同一 Workflow 最多一个周期规则。替换规则在 Room 事务内取消旧待执行实例并创建新实例，再同步取消旧唯一工作；停用规则或 Workflow 会清空 `nextTaskId / nextPlannedAt` 并取消 WorkManager。周期实例不暴露一次性任务取消入口，避免留下仍会继续生成下一实例的启用规则。
- 启动恢复会先冻结旧候选并排除当前进程真正 `RUNNING` 的 Worker 链；候选同时包括 `RUNNING / STOP_REQUESTED` ScheduledTask，后者即使仍在进程注册表中也必须继续取消。旧任务按关联 Workflow Run 或持久停止意图收敛后，才为仍启用的规则物化一个未来实例；已物化但尚未关联 WorkRequest 的实例只补入队，不补跑错过的历史周期，也不复制旧 Agent Run。
- Worker 使用同一 `AgentRunUseCase`，但强制传入 `AgentExecutionOrigin.BACKGROUND`。SAFE 后台工具可完成原有校验与验证；需要审批的工具写入 Agent/Workflow/ScheduledTask `BLOCKED` 终态并通知用户以前台新 Run 重试，绝不等待前台审批卡或继承临时授权。
- Android 8+ 使用稳定通知 Channel；Android 13+ 从用户创建计划的操作中请求 `POST_NOTIFICATIONS`。完成、失败、阻断和系统取消都会写入 Ledger；通知被拒绝时不影响任务终态。
- 当前没有 AlarmManager、精确闹钟权限或 Foreground Service；WorkManager 业务结果也不使用系统自动重试，避免复制可能已经执行过的 Agent Run。2026-07-22 的正式 8 步 SAFE Workflow 已在约 62.2 秒全部完成，运行中停止样本约 32.6 秒；此前 8 步探针在约 28.5 秒时于第二步重复工具调用检测处安全失败。强制 Doze 明确延后了任务，退出 Doze 与 `send-trim-memory` 样本均出现短时 `connection closed`，但这些受控样本不能证明因果或 Android 自主 LMK。当前进程 Worker 所有权隔离和用户可见停止均已完成，但都不提高系统存活率。当前继续使用普通 WorkManager；只有真实任务持续时间、重要性或自然系统回收证据表明必要时才使用 `setForeground()`，由 WorkManager 代管前台服务。

- `ToolExecutionResult` 和 `RunEventMetadata.ToolResult` 会携带实际命中的 `memoryIdsUsed`；任务中心直接展示这些 ID，旧事件没有该字段时按空列表兼容解码。最终 `VerifiedAgentContext.toolExecutions` 按执行顺序保存全部工具、参数、结果、验证状态和记忆 ID，顶层单工具字段继续映射最后一步以兼容旧消息；Android 持久化显式使用 JSON 数组，并兼容旧的字符串化数组。
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

- Provider、会话、消息、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory、AgentSkill、AgentProfile、ToolCall/ToolResult、Workflow、WorkflowStepDefinition、WorkflowRun、WorkflowStep、WorkflowSchedule、ScheduledTask、KnowledgeDocument/Chunk 和检索审计保存在 Room 数据库 `xiaoling.db`。
- 数据库当前版本为 v27，启用 `exportSchema`；`XiaoLingDatabaseMigrationInstrumentedTest` 覆盖正式 v4→v27 的关键增量和全新 v27 建库。v25→v26 只创建空知识库表；v26→v27 为 ToolResult 与 MessagePart 增加默认 `[]` 的知识引用列，不从旧正文或历史 JSON 猜造引用。
- 旧消息迁移后统一得到 `origin=LEGACY`，`verifiedAgentContext` 默认为 `null`；v7 旧 Run 的 `retryOfRunId` 初始化为 `null`，v8 旧记忆的 `pinned=false` 并在迁移时回填 FTS，v9 正式记忆不会被倒推成候选，v10 旧记忆的生命周期字段保持空值，v11 升级后 Skill 表为空并由应用启动同步内置定义。
- AgentMemory 保存内容、标签、类型、来源会话、来源 Run、来源摘要、置信度、启用/置顶状态、可空过期时间、最近引用时间和时间戳；`AgentMemoryStore` 只向工具暴露写入与检索，`AgentMemoryManager` 独立提供 UI 管理能力。
- 记忆检索优先使用 Room FTS4 `unicode61` 做英文/标签前缀召回，并用 `LIKE` 兜底中文和任意子串；启用记忆会排除明确过期项，命中后回写 `lastReferencedAt`。结果按置顶、置信度和按类型配置的半衰期排序，衰减只影响排序，不修改正文或删除记录。
- 设置页「长期记忆」支持候选开关与确认、搜索、启用状态筛选、编辑、置顶、启停、删除确认、当前会话撤销和来源审计；来源会话与来源 Run 存在时可直接跳转。
- 设置页「数据备份与恢复」通过 Android SAF 导出/导入 ZIP；备份包含 Room 主库和 schema/app manifest，导入先校验 manifest 与真实 SQLite `user_version`，再关闭 Room、保留 `.pre-restore` 安全副本并替换数据库，完成后必须重启应用。
- 备份不导出 API Key 明文；Provider 表中的密文仍依赖当前 Android Keystore，跨设备或密钥丢失时不能仅凭数据库恢复凭据。未来可增加不含凭据的 Provider 元数据迁移向导。
- 长期记忆的引用审计目前落在 Agent Run 的 ToolResult 和 VerifiedAgentContext；删除或禁用记忆后新 Run 不会产生对应 ID，历史 Run 保留原始审计快照，不回写旧事件。
- `xiaoling` 和 `xiaoling_conversations` SharedPreferences 只作为旧数据迁移来源；迁移成功后不会反复恢复旧数据。
- 主题、候选记忆开关、三类提示词、User-Agent 和设备 Agent 独立开关保存在 `xiaoling_ui` SharedPreferences；设备 Agent 首次安装/升级默认关闭，UA 保存时移除换行并限制长度，空白值恢复默认配置。
- API Key 只以 AES-GCM 密文落盘，密钥材料保存在 Android Keystore。

## 本地知识库与 RAG 数据基础

- Room v26 新增 `knowledge_documents / knowledge_chunks / knowledge_chunks_fts / knowledge_retrievals`；Room v27 把 `KnowledgeReference` 写入 Tool Ledger 和 Tool MessagePart。规范全文和 chunks 都保存在主数据库中，因此现有数据库 ZIP 备份自然包含知识库数据，不依赖外部 URI 或旁路文件。
- `KnowledgeTextPolicy` 第一版只处理 TXT、Markdown、JSON 和 CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符。导入会移除 BOM、统一 CRLF/CR 为 LF、拒绝空白与 `NUL`，并对规范全文计算 SHA-256；`parserVersion=1` 明确冻结当前解析语义。
- 分块默认上限 1600 字符、重叠 200 字符，优先在后半窗口的段落分隔处结束；没有合适段落边界时才硬切。每块保存 `[startOffset, endOffset)`，正文必须等于规范全文对应子串，并修正 UTF-16 高低代理项边界。
- chunk ID 包含文档 ID、revision、sequence 和内容哈希前缀。替换始终递增 revision，并在单个 Room 事务内更新文档、删除旧 FTS/chunks、插入新 chunks/FTS；注入新 chunk 插入失败的真机测试确认全文、revision、旧 chunks 与旧索引会一起回滚。
- 检索优先执行 FTS4 `unicode61` 前缀查询，同时执行转义 `% / _ / \\` 的多词 `LIKE` AND 查询作为中文和字面子串兜底；结果按 chunk ID 去重并限制最多 20 条。每次调用，包括空命中，都会记录 query、实际 chunk/document ID、来源会话、来源 Run 和时间。
- 设置页「知识库」使用独立 `KnowledgeManagementViewModel`，支持 SAF 导入、刷新、轻量摘要列表、详情、启停、替换、删除和显式检索预览。`KnowledgeDocumentReader` 即使遇到 DocumentsProvider 隐瞒大小也会流式执行 64 MB 上限；列表使用 projection + chunk count，不读取规范全文。
- 详情通过独立 SQL projection 读取有界前缀，再按最多 4,000 个 UTF-16 单元二次收紧且不切断代理对；同时保留完整字节数、字符数、SHA-256、revision、parser 和截断标记，避免最大 64 MB 全文进入 Compose 状态。快速选择会取消旧详情和列表刷新 Job；替换、禁用和删除会立即隐藏旧详情、取消在途检索并清空旧 chunk/retrieval 引用，提交成功后的刷新异常不会误报为提交失败。
- `knowledge.search` 作为独立 SAFE ToolDefinition 接入 Registry，`query` 为 1 至 200 字符，`limit` 默认 3、最大 5，支持后台执行；内置 `local-knowledge` Skill 只缩小到该工具。Store 写入 conversation/run 来源检索审计，结果同时返回可读片段与 retrieval/document/revision/chunk/offset 引用。
- 引用从 ToolExecutionResult 贯穿 RunEvent、独立 Tool Ledger、VerifiedAgentContext、MessagePart、规划历史和任务中心。`KnowledgeReferenceCodec` 对整段或单条畸形 JSON 容错，坏项不再作为可信证据，但不会阻断消息或 Run 加载。
- 禁用、替换或删除后，Room 中历史 Run/消息审计保持不变；普通对话准备上下文时会按当前 enabled/revision/chunk/sequence/offset/name 核验引用。任一引用失效时整条知识 Agent 消息退出请求，可能包含旧片段的已存摘要同时废弃并从过滤后的消息重建，避免仅清空 ID 后仍把旧正文送入模型。
- Workflow 前序输出沿用相同生命周期边界：前台、后台与进程恢复完成步骤时都把真实 `VerifiedAgentContext`/Tool Ledger 引用写入版本化输出快照；重试复制旧快照但不改写来源，下一步骤使用前再次核验，失效正文不会进入新 Run。
- Agent 回复使用独立、默认折叠的答案引用区域，只从 `effectiveParts()` 中可信 Tool part 的结构化引用投影，不扫描模型自由文本。展开后展示文档名、revision、chunk 和半开 offset 区间；Room 通过文档摘要与引用 chunk 的 projection 核验状态，不读取最大 64 MB 全文，并按最多 900 个绑定参数分批查询，避免长会话超过 SQLite 上限。精确匹配标记“当前有效”，当前启用文档 revision 更高标记“历史版本”，停用状态优先标记“当前不可用”，删除或 chunk 边界漂移也标记“当前不可用”；文档仍存在时整行可跳转知识库详情，已删除时关闭跳转。核验异常显示“暂无法核验”，会话切换或新一轮核验取消旧 Job 时保留协程取消语义，旧任务不会覆盖新状态。
- 新工具不会自动加入旧 Profile/Skill；缺少 Profile 审计的历史 Run 使用知识工具上线前的固定工具集合，审批恢复后的后续规划也不能发现 `knowledge.search`。Embedding 继续后置。

## 设备 Agent 观察与有限动作层

- `XiaoLingAccessibilityService` 声明 `canRetrieveWindowContent=true`，显式关闭坐标手势和截图能力，并设置 `isAccessibilityTool=false`；服务不导出，只能由系统通过 `BIND_ACCESSIBILITY_SERVICE` 绑定。事件只推进窗口 generation；执行层只使用 `performGlobalAction` 和节点 `ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_*`。
- 设置页「设备 Agent」提供默认关闭的独立开关、系统 Accessibility 设置入口、四态健康检查和只读快照预览。关闭开关会立即清除 ref；应用开关和系统授权必须同时有效。
- `DeviceSnapshotPolicy` 把原始节点树收紧到最多 200 个可见有效节点和 4,000 个字符，文本预算不切断 UTF-16 代理对。只有当前启用、可操作且未脱敏的节点获得 ref；禁用节点、只读文本和敏感节点没有 ref。
- ref 由 `DeviceNodeReferenceStore` 绑定 snapshot ID、窗口 generation、节点路径、指纹和 30 秒到期时间。新快照替换旧快照；页面变化、过期、引用不存在、开关关闭、捕获失败或隐私拦截都明确失效，不存在坐标回退。
- 密码/密码提示、验证码、API Key、Bearer/Access Token、带空格或连字符的手机号/银行卡、身份证和邮箱节点会清空正文、动作与 ref。支付/收银台/高敏身份验证窗口以及已知密码管理器、Authenticator、钱包/银行类包名整窗拒绝，不把包名或节点正文写入工具结果。
- `device.snapshot` 是 SAFE、非后台工具；`device.open_app / tap_ref / type_text` 要求逐步审批，`device.back / home / swipe` 为 SAFE。`open_app` 只接受 manifest queries 与业务策略共同限定的小灵、系统计算器、时钟和系统设置；`type_text` 最多 500 字符，并在 Tool 参数审计前拒绝密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱。
- 节点动作执行前再次核对 snapshot/ref/generation/path/fingerprint/action；动作后等待窗口短暂稳定并重新 capture。首次启动系统权限页可能短暂没有 `rootInActiveWindow`，只对 `NO_ACTIVE_WINDOW / WINDOW_CHANGED` 做最多 6 次、每次 100 ms 的有界重试；隐私拒绝、授权失效和服务断连不重试。`open_app` 核对前台包名，`home` 核对桌面包名，`type_text` 回读文本，其他动作要求可观察的窗口 generation 变化，未得到证据时返回 `verified=false`。
- Registry 只有在前台直接 `/agent`、独立开关开启且 Profile/Skill 允许时才暴露全部设备工具；Workflow、后台和关闭状态在规划器工具面与 Executor 两层拒绝。`device-observation` 保持只读，新增 `device-control` 才引用动作工具；既有 Profile/Skill 不自动扩权。
- `app/src/debug` 提供仅 Debug 包可用的快照、动作和真实 Agent 诊断广播与隐私探针；Release manifest 不包含这些入口。该 Redmi ROM 在 instrumentation 生命周期后会清空无障碍授权，因此完整 instrumentation 结束后恢复系统服务，再用 Debug-only 入口完成真实服务与动作 E2E。
- Redmi 首批验收覆盖计算器 `open_app + tap_ref`、设置 `swipe + tap_ref + type_text`、敏感输入拒绝、`back / home` 和时钟启动；真实 `gpt-5.5 + Responses` `/agent` Run 完成 `device.open_app` 的模型规划、应用侧审批、执行、后置验证、Tool Ledger 和最终总结。当前仍不支持坐标点击、截图、任意 App、设备 Workflow 或后台设备自动化。

## 日志

- debug 包默认开启 HTTP 调试日志：`BuildConfig.XIAOLING_HTTP_LOGS_ENABLED = true`。
- release 包默认关闭 HTTP 调试日志。
- 日志会对 Authorization 和包含 key 的 Header 做脱敏。
- 网络层把连接建立失败，以及带明确 EOF、connection reset、broken pipe 或 stream reset 标记的响应中断归类为 `CONNECTION`；其他 `ProtocolException` 归类为 `RESPONSE`，无法识别的 I/O 仍为 `UNKNOWN`，避免扩大后续自动重试范围。

## 当前限制

- 暂不提供云同步和账号体系。
- 尚未内置 MCP 和外部远程工具。动作型手机自动化已交付限定范围的 `device.open_app / back / home / tap_ref / type_text / swipe`，只允许前台直接 `/agent`，仅承诺小灵、系统计算器、时钟、设置和桌面的首批 Redmi 验收，不承诺任意 App、Workflow 或后台设备自动化。
- 暂不提供 Provider 模板市场。
- 更换 `applicationId` 后，旧版本本地数据不会自动迁移。
- Responses Adapter 已支持文本、用户图片/文档、`function_call / function_call_output` typed Items 和可选 Reasoning summary；Room/Compose 已完成 Text/Reasoning/Image/Document/Tool parts 垂直切片，DOCX/PPTX/XLSX 已完成结构校验与真实模型直传。当前 Agent Runtime 仍使用提示词 JSON 做最多 4 步的顺序工具规划，尚未直接使用上游原生函数调用循环；附件暂不进入 `/agent`。超过 8 MB 或跨文档资料已经具备严格文本全文、分块、FTS/中文兜底、管理 UI、`knowledge.search`、结构化引用、答案级引用呈现和模型上下文失效过滤；剩余差距是 Embedding 和更大真实资料集的召回质量验证。
- `/agent` 目前接入第一批应用内工具、知识检索和限定设备工具；任务中心已支持失败终态安全重新运行。进程重建后的恢复边界策略已经落地：链尾待审批 Run 可从任意已验证前缀原地恢复；`notes.create / memory.remember` 的完整已提交证据可进入受限只读验证；所有工具结果与 `PASSED` 验证完整落库后可恢复本地收尾。旧 typed 验证事件缺少 `toolCallId` 时固定判为关联未知，不按工具名或顺序猜配。提交状态未知、验证事实不完整和旧模型协程仍必须安全重新运行。
- 当前模型请求审计不保存 Prompt 正文，也不估算价格；只保存最终请求体字节、计时和上游明确返回的 Token usage。流式普通对话仍沿用消息级首 Token 指标，Agent 非流式请求使用 TTFB，两者不混算。
- 启动协调器已保留 `APPROVAL_WAIT` Run 并把待审批请求重建到当前会话；发起 `/agent` 后会先持久化用户消息，旧数据缺少消息锚点时再依据 Run 的 `userMessageId / goal / createdAt` 补回。审批恢复会从 Ledger/Event 重建前序可信工具、调用额度和循环指纹，批准后只执行链尾 ToolCall；执行/验证中 Agent Run 默认与活动 Step 一致安全收敛，只有两个白名单写工具的只读验证或全部工具已经 `PASSED` 的控制面收尾可以完成原 Run。多步骤 Workflow、步骤快照、安全重试、真实后台执行和审批后继续下一步骤均已完成真机验收；后台通用执行栈断点续跑仍不开放，Foreground Service 暂无真实耗时依据支持引入。
- 恢复测试覆盖首步与第二次审批同 Run 完成、前序工具不重放、最终可信上下文保留完整工具链、工具调用预算和累计时间预算均不因重启清零、两个白名单写工具的已提交结果不调用写入方法而完成验证恢复、`tool.verify` 落库后与验证 Step 完成后两个终止点不重复 ToolResult/验证、恢复工具失败写入原 Run `FAILED`、旧验证缺少 ToolCall ID 时拒绝顺序猜配、Workflow 步骤落库后的进程终止与下一步骤不重复启动、Worker 重入按 ID 定向关闭关联 Agent/Workflow/Task 且不影响无关 Agent、启动恢复快照期间新 Worker 等待、旧链收敛而当前进程链保持并完成且不新增 Run、用户停止定向收敛目标链、迟到 Step/Event/Approval 不污染终态、其他执行/验证中 Run 与 Step 一致取消、稳定重试证据分类、结构化恢复处置、确认前二次评估，以及失败后安全重试必须二次确认。Room instrumentation 覆盖关闭并重开磁盘数据库后保留第二次审批与已验证前缀、Workflow 完成前缀和关联新 Run 重试；当前门禁为 420 条 JVM 与仅 Redmi 执行的 141 条 instrumentation。

## 任务中心需确认队列

- `AgentTaskFilterPolicy` 统一管理任务中心筛选语义；新增 `NEEDS_CONFIRMATION`，只匹配 `AgentTaskRetryEligibility.Retryable(requiresConfirmation=true)`。普通直接重试继续留在“可重试”，`WAITING_APPROVAL` 等活动 Run 继续留在“处理中”。
- Compose 筛选条显示“全部 / 需确认 / 处理中 / 可重试 / 已完成”。“需确认”卡片复用现有证据分类、原因、建议动作和确认弹窗，没有新增另一套 UNKNOWN/COMMITTED 判断。
- 确认提交仍由 ViewModel 重新读取 Run 并调用 `canConfirmRetry()`；证据码漂移会要求重新确认，稳定后只创建带 `retryOfRunId` 的新 Run。该队列不恢复旧模型协程、不调用旧 Executor，也不继续 Workflow 后续步骤。

## 结构化恢复处置

- `AgentRunRestartDispositionCode` 把不可原地恢复原因稳定分类为 Run 状态、Profile 证据、执行预算、审批边界、恢复证据、Profile 能力、步骤证据、只读验证能力、工具定义和已提交副作用证据十类。`AgentRunResumeAssessment` 构造约束保证 `RESTART_REQUIRED` 必须且只能携带处置对象。
- `closeInterruptedRuns()` 在修改活动 Step、审批与 Run 终态前评估原始详情，并在同一 `run.recovered` metadata 中冻结 `resumeKind / restartDispositionCode / policyReason / evidenceBoundary / suggestedAction / retryEvidenceCode`。新增字段不改变 Room Schema；旧事件缺字段保持空，未来未知枚举保守降级。
- 任务中心从最新历史 Recovery 事件生成纯呈现模型，在任务卡和详情顶部展示处置状态带，事件列表展示同一字段。缺少完整结构化字段的旧事件不调用当前策略补造，因此升级不会重写旧 Run 的历史判断。
- 所有处置建议固定保留旧 Run 与既有审计，在既有重试确认门禁后创建带 `retryOfRunId` 的新 Run；不调用旧 Executor、不恢复旧模型协程，也不继续 Workflow 后续步骤。

## Redmi Worker 冷启动重入证据

- Redmi 上的 7 步 SAFE Workflow 在首步 Agent `THINKING` 时终止旧 PID。instrumentation 前台身份使 `am kill` 无效，因此使用 `run-as kill -9` fallback；约 `0.2s` 后 JobScheduler 以新 PID 冷启动同一 `workSpecId` 和 generation。
- 新 Worker 进入 `ScheduledWorkflowReentryCoordinator`，没有重新 claim，也没有创建第二个 Agent Run。Room 最终仅有 1 个关联 Agent Run，Task/Workflow/首步 Agent 均为 `CANCELLED`，其余 6 步未执行，工具调用和 ToolResult 都为 0。
- ScheduledTask 从 `actualStartedAt=06:05:03` 到 `completedAt=06:05:06` 共 `3360ms`。该证据确认真实 WorkRequest 重入链路可用，但受控 `kill -9` 不代表 Android 自主回收；通用未知提交恢复、Workflow 后缀续跑、Doze/内存压力和更长任务仍保持现有边界。

## Redmi 长任务与系统策略证据

- 8 步 SAFE Workflow 的首次 Agent 成功执行 `app.current_time`，第二步模型重复同一调用后由循环保护安全失败；从 Worker 启动到终态约 28.5 秒。该样本说明当前普通 WorkManager 可以承载这一量级的真实模型链路，但不是 8 步全部成功样本。
- 强制 Doze 后，同一 WorkRequest 在 20 秒观察窗内保持 `SCHEDULED`，没有 WorkflowRun、`actualStartedAt` 或应用 PID；通过设备 motion 退出 Doze 后才启动。Android 官方文档同样说明 Doze 会限制网络并延后 jobs/WorkManager，因此产品继续采用非精确定时语义。
- 退出 Doze 后的任务在约 889ms 以 `connection closed` 失败；运行中发送 `RUNNING_CRITICAL` trim-memory 的任务约 944ms 同样失败，但 PID 不变，前后 PSS/RSS 没有形成“压力导致回收”的证据。两者均只有一个 Workflow/Agent Run，没有 `Result.retry` 或复制 Run，且不能把连接关闭归因于 Doze 或 trim-memory。
- 无压力对照只创建一个 WorkRequest、WorkflowRun 和 AgentRun，但前台启动恢复与新 Worker 并发：ScheduledTask/Workflow 被收敛为 `CANCELLED` 后，旧执行协程仍返回并把 AgentRun 写成 `COMPLETED`。修复后 `AgentRunDao.updateRunStatusIfActive()` 用单条 SQL 保证终态不可覆盖，Repository 只有更新成功才追加状态事件；Redmi 新增测试覆盖“恢复先取消、旧执行后完成”的顺序。
- `force-idle`、`am kill`、`run-as kill -9` 和 `send-trim-memory` 都是受控命令，不代表 Android 自主 LMK。当前证据仍不支持提前引入 Foreground Service，也不改变旧模型协程、未知提交执行栈和 Workflow 后续步骤不原地恢复的边界。

## 当前进程 Worker 所有权与启动恢复隔离

- Worker 先在进程级注册表登记 Task ID，再构造执行器和访问 Room；同 ID 并发使用引用计数，任一调用结束都不会提前移除其他执行所有权。
- 启动恢复持有同一互斥边界冻结候选。已登记 Task 对应的 WorkflowRun 和 AgentRun/WorkflowStep 关联一并排除；快照开始后才启动的 Worker 必须等快照完成，因此不会进入本次旧候选。
- ViewModel 后续三类 Agent 恢复、不可恢复 Agent 关闭、Workflow 对账和 ScheduledTask 对账只处理冻结 ID，不在每一步重新扫描全库。旧链继续按原 fail-closed 策略收敛，当前进程 Worker 链不受影响。
- Redmi Room 测试在同库构造旧链与当前链，确认旧 Agent/Workflow/Task 进入 `CANCELLED`，当前链保持活动并随后完成，Agent Run 数量不增加。完整门禁为 397 条 JVM、130 条仅 Redmi instrumentation、Lint、Debug 与 AndroidTest 构建通过。
- 该隔离不使用墙上时间，不升级 Room v27，不新增持久 owner token，也不恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤；这是第 47 阶段当时的边界，第 48 阶段已补齐可见停止入口，Android 自主 LMK 与 Foreground Service 仍需独立证据。

## 后台运行中停止与长成功样本

- 工作流页只对一次性 `RUNNING` ScheduledTask 展示“停止运行”。同一协调器在操作时重新读取 Room：若任务仍为 `SCHEDULED`，先事务取消本地门禁再取消 WorkRequest；若 Worker 已抢占为 `RUNNING`，同一次点击自动进入运行中停止，不依赖过期 UI 快照。运行中任务会先原子写为 `STOP_REQUESTED`，UI 显示中性的“停止中”并隐藏停止按钮。
- 持久化停止栅栏写入后才调用 WorkManager 取消目标 WorkRequest，并在有界窗口等待 Worker 通过正常协程取消关闭 Agent/Workflow/Task。仍未收敛或系统取消接口抛异常时，`ScheduledWorkflowStopFallbackCoordinator` 沿当前 Task→Workflow→Agent 关联按 ID 取消；Agent 尚未写入关联的窗口仍会关闭 Workflow 与 Task。系统取消与即时 fallback 同时失败时不再抛出并丢失意图，`STOP_REQUESTED` 保留到下次启动对账。重复停止返回既有状态，不创建新 Run，也不影响无关前台/后台 Run。
- Redmi 真实停止 Task `scheduled-task-82faa2d4-a5a6-42f4-85ee-fa91b36d8c1d`，目标 WorkManager 被 `stopAndCancelWork`，Task、Workflow、Agent 与三条 Workflow Step 均保持 `CANCELLED`；从启动到停止约 32.6 秒。迟到 HTTP 200 返回后，Run、Step、Approval、Event 和 Tool Ledger 的终态门禁阻止旧执行覆盖或追加成功事实。
- Redmi 三步 SAFE 成功 Task `scheduled-task-fc8229b4-5ff7-4794-b269-e94b35601445` 依次执行 `app.current_time`、`app.list_conversations(limit=3)`、`notes.list(limit=3)`，三个 Agent Run 分别约 7.2、7.1、7.0 秒，Workflow 总耗时约 21.8 秒，Task/Workflow/三条 Step 均为 `COMPLETED`。
- `ActivityManager.isLowMemoryKillReportSupported()` 在 Redmi 返回 true；查询到 11 条历史退出记录，但 `REASON_LOW_MEMORY=0`。这些记录来自 instrumentation、force-stop 或安装等受控退出，不能作为 Android 自主 LMK；当前仍不引入 Foreground Service。完整门禁为 402 条 JVM、134 条仅 Redmi instrumentation、Lint、Debug 与 AndroidTest 构建通过。

## Redmi 62.2 秒八步成功样本

- 一次性诊断探针只通过正式 Repository 创建 8 步 Workflow、ScheduledTask 和 WorkRequest，随后退出；模型请求、步骤推进、Tool Ledger、通知和最终结算完全由生产 `ScheduledWorkflowWorker` 执行。探针源码在取证后删除，不进入提交。
- 成功 Task `scheduled-task-b7cae61a-e311-42bc-98a7-f8d601a9be59`、WorkRequest `ec200f45-ed0d-4b78-9fd6-4cbcc2dd25fd`、Workflow Run `workflow-run-fc647164-1faf-4b5f-853a-16ae14565340` 从 `02:28:26` 运行到 `02:29:28`，总耗时约 62.2 秒。Task/Workflow/8 条 Workflow Step 均为 `COMPLETED`，只存在一个关联 Workflow Run。
- 8 个 Agent Run 分别约 7.5、7.4、7.0、9.0、8.6、6.4、7.9、7.3 秒，依次执行 `app.current_time`、`app.list_conversations`、`notes.list`、`app.search_conversations`、`notes.search`、`app.current_time`、`app.list_conversations`、`notes.list`；每个 ToolResult 均为 `success=true / verificationStatus=PASSED`。
- 先行 Task `scheduled-task-fc435736-8c3f-4898-b353-4c2aefe014fd` 运行约 49 秒，前 5 步成功，第 6 步因模型未调用 `memory.search` 而 `FAILED`，后两步按定义进入 `CANCELLED`。失败链同样只有一个 Workflow Run，没有 `Result.retry` 或复制执行；这说明模型遵循工具目标仍是长任务成功率的一部分，不能只由 WorkManager 存活证明。
- 样本后 LMK probe 为 `supported=true / exits=6 / lowMemory=0 / fallbackSigkillCandidates=0`。6 条历史退出全部明确标记为 instrumentation 启停产生的 `USER REQUESTED / FORCE STOP`，没有 Android 自主 LMK。62.2 秒成功样本仍在普通 WorkManager 适用范围内，不引入 Foreground Service，不开放设备工具到 Workflow/后台。

## 持久化停止请求与原子重对账

- `ScheduledTaskStatus.STOP_REQUESTED` 是唯一新增的持久中间态，直接存入既有 `scheduled_tasks.status` TEXT 列；终态集合和 Room v27 Schema 均不改变。`requestScheduledTaskStop()` 在 Room transaction 中只允许 Workflow 仍活动的 `RUNNING→STOP_REQUESTED`，重复请求幂等并保留首次停止原因；若关联 Workflow 已先进入终态，则停止已经来晚，事务直接把半结算 Task 映射到该终态，不写入无法覆盖历史事实的伪栅栏。
- `ScheduledWorkflowReentryCoordinator`、`ScheduledWorkflowStopFallbackCoordinator` 和启动恢复扫描都接受 `RUNNING / STOP_REQUESTED`。进程所有权只排除仍正常 `RUNNING` 的链；停止请求已撤销 Worker 继续执行资格，因此即使 Task ID 仍登记在进程注册表，启动恢复也会收敛其 Agent、Workflow 和 Task，且不创建第二个 Run。Workflow 对账会通过唯一 `workflowRunId` 关联先读取 ScheduledTask；若 Agent Run 尚未创建或关联但 Task 已是 `STOP_REQUESTED`，直接取消 Run 和全部未完成步骤，不使用“关联 Agent 缺失”失败语义。停止入口、重入与停止 fallback 共用 `ScheduledTaskPolicy.requiresExecutionReconciliation()`，Worker 通知通过 `isUnsettled()` 使用同一状态分类，避免后续新增中间态时各路径解释漂移。
- `completeScheduledWorkflowStep()` 在同一 Room transaction 中先校验 Task↔Workflow 关联和停止栅栏，再一起提交步骤终态与 `AGENT_RESULT` 会话消息。停止已经落库时抛出取消，步骤和消息都不写入；停止事务只能发生在该原子提交之前或之后，不能插入两次写入之间留下迟到成功消息。
- `settleScheduledWorkflowRun()` 在同一 Room transaction 中重新读取 Task、关联 Workflow Run 与停止栅栏。既有 Workflow 终态优先映射到仍活动的 Task，保持历史终态不可改写；只有 Workflow 仍活动且 Task 为 `STOP_REQUESTED` 时才固定取消。该既有终态映射在事务内直接更新 Task，不再经过通用 `finishScheduledTask()` 的停止栅栏二次改写。停止 fallback 先定向关闭 Agent，再调用该原子 API 同时收敛 Workflow/Task；只有 Workflow 尚未建立时才单独关闭 Task。旧版 fallback、重入或进程终止留下的半结算状态因此不会被迟到 Worker 写成相反结果。结算结果与本轮 outcome 不一致时不追加本轮会话消息，通知也读取持久状态，避免取消/失败链显示成功。
- `finishScheduledTask()` 继续作为其他结算入口的最后栅栏：`STOP_REQUESTED` 只能进入 `CANCELLED`。该中间态不属于终态，Daily/Weekly 规则不会在旧实例完成重对账前物化下一实例。阶段 50 完整门禁为 405 条 JVM、141 条仅 Redmi instrumentation，Lint、Debug 与 AndroidTest 构建通过。
- 本阶段只保证停止意图跨异常和进程重建可见，并关闭 Workflow/Task 终态的 TOCTOU；它不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤，不复制 Run，也不撤销停止前已经提交到外部系统的副作用。现有 62.2 秒样本仍不支持引入 Foreground Service。

## 旧验证事件关联未知与 LMK 基线

- `AgentRunRecoveryEvidencePolicy` 的 event fallback 继续要求 ToolResult 携带稳定 ToolCall ID。`tool.verify` 也必须以 ID 唯一匹配同名调用；缺少 ID 时返回恢复证据无效，由恢复/重试策略保守映射为 `EVIDENCE_INCOMPLETE`，不再按工具名和事件顺序猜配。带完整 ID 的旧 Run 仍保持原有恢复能力，Room v27 Schema 不变。
- TDD 先把旧“同名调用按顺序回退”测试改为 fail-closed 契约，第一轮 Red 在 `Invalid` 断言处失败；随后新增重试证据测试，第二轮 Red 证明独立 legacy 分支仍会返回普通确认分类。最终 `AgentRunRecoveryEvidencePolicy` 与 `AgentTaskRetryEvidencePolicy` 都拒绝缺失 ID，相关恢复、重试、Resume Policy 与 Runtime 测试通过。完整门禁为 406 条 JVM、141 条仅 Redmi instrumentation，Lint、Debug 与 AndroidTest 构建通过。
- Redmi 定向 `ApplicationExitInfoInstrumentedTest` 为 `OK (1 test)`，日志为 `supported=true / exits=2 / lowMemory=0 / fallbackSigkillCandidates=0`。两条退出分别是启动 instrumentation 的 `reason=10 FORCE STOP` 与安装包的 `reason=16`，没有自主 LMK；不据此引入 Foreground Service。
- 第 52 阶段已完成 `AgentTaskRetryEvidenceFingerprint`：它对工具调用/结果账本和非 `run.recovered` typed event 做长度前缀规范化并计算 SHA-256。启动收敛在 Step/Approval 改写前将摘要与证据码写入 `run.recovered.retryEvidenceFingerprint`；`AgentRetryConfirmationUiState` 保存打开弹窗时的摘要，确认前重新计算并同时核对分类码。新增合法 ToolCall、替换参数/Receipt 或验证事件时，即使分类仍是 `COMMIT_UNKNOWN` 也返回 `EVIDENCE_INCOMPLETE` 并拒绝旧确认；摘要一致时保持原确认路径。已带证据码但缺少历史指纹的 Recovery 事件不再被当作可验证快照，Room v27 Schema 不变。
- 第 53 阶段新增 `AgentRuntimeFaultInjector` 的三段边界：ToolResult 事件写入后、执行预算快照写入后、`tool.verify` 事件写入后。实际 Runtime 测试证明第一段缺少预算后续快照时由 `AgentRunResumePolicy` 返回 `EXECUTION_BUDGET_INVALID`，不能把已提交回执升级成原地恢复；第三段验证事实已经存在但 Step 尚未收尾时，`resumeVerifiedToolRun()` 只补 Step/Run/本地总结，不重复 Executor、ToolResult 或 `tool.verify`。生产默认注入器仍是 no-op，Room v27 Schema 不变。
- 第 54 阶段把模型异常也纳入预算审计：规划阶段的 `AgentLlmResponseException` 先写失败 telemetry 再写预算快照，其他网络/网关异常至少写冻结后的预算快照；总结阶段的网络异常不再让已验证工具事实进入 FAILED，而是记录 fallback 事件并生成本地可信回复。Receipt 回读失败继续通过 `RecoveryFailure` typed event 暴露稳定错误码/建议动作，重试证据保持 `COMMIT_UNKNOWN` 并要求确认，不重放旧写入。完整 JVM 覆盖为 411 条，Room v27 Schema 不变。
- 第 55 阶段新增 `AgentLlmFailureKind` 与 `RunEventMetadata.LlmFailure`。`MinimalAgentRuntime` 将 `ApiFailure.kind` 映射为稳定的鉴权、地址、限流、模型、超时、DNS、TLS、连接、响应或未知错误，写入 `llm.request.failed`；`AgentLlmResponseException` 缺少网络分类时按 `RESPONSE`，普通未知异常按 `UNKNOWN`。Codec 对未来枚举 fail-closed 到 `UNKNOWN`，任务事件区只显示阶段、错误码和原因，不展示请求正文。Room v27 Schema 不变。完整门禁为 413 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。
- 第 56 阶段完成普通对话部分流式 delta 的收敛：收到正文后断流会保留已见文本，给 assistant 写入 `finishReason=failed`、错误分类和原因，并追加独立错误消息；取消同样结束“接收中”状态。失败/取消的部分 assistant 被排除出下一轮请求与摘要，避免残缺正文成为新的模型事实。新增真实 socket 断流、失败消息状态和上下文资格测试；完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。
- 下一恢复证据切片是后台长任务中的预算写回竞态和自然系统回收；仍不恢复无法证明的旧执行栈。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
