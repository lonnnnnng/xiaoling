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
| App/UI | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | 「对话 / 设置」双入口、会话列表、消息输入、普通聊天模型选择、Agent Profile 与模型提供方管理页面。 |
| ViewModel | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | 维护页面状态、会话上下文、摘要压缩、模型同步、普通对话发送、Agent Profile 选择和前台 Workflow 编排。 |
| Network | `app/src/main/java/com/longdev/xiaoling/network/LlmProviderAdapter.kt`、`OpenAiCompatibleClient.kt` | Adapter 负责 OpenAI-compatible URL、payload 与响应协议；Client 负责 HTTP、鉴权 Header、取消、计时和 SSE 读取。 |
| URL | `app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | 将用户输入的 API 根地址归一化成 `/models`、`/chat/completions` 和 `/responses` 请求地址。 |
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、AgentProfile、Conversation、Message/MessagePart、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory 和 AgentMemoryCandidate 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Conversation/Message Repository、Agent Profile Store、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | Agent Profile 策略、最小 Agent Runtime、Run Ledger interface、真实低风险 Tool Registry、交互式审批 gate 和可审计运行链路。 |
| Automation | `app/src/main/java/com/longdev/xiaoling/automation/`、`storage/RoomWorkflowRepository.kt` | Workflow/ScheduledTask 状态、周期规则、Room Ledger、前台手动触发、WorkManager 非精确调度、后台执行和结果通知。 |
| Prompt | `app/src/main/java/com/longdev/xiaoling/prompt/` | 三类可配置提示词的默认模板、最终 system prompt 组合和不可覆盖事实边界。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和主要流程集中在 `XiaoLingViewModel`：

- Provider 管理、模型同步、会话切换、发送请求、摘要生成、流式更新和错误提示由同一个 ViewModel 维护。
- `LlmProviderAdapter` 已成为模型协议边界，当前 `OpenAiCompatibleAdapter` 统一处理模型列表、Chat Completions、Responses API 请求与响应映射；`OpenAiCompatibleClient` 只保留 HTTP 传输、取消、计时和 SSE 读取。普通聊天和 Agent 仍复用同一 Client 与 Adapter 实例链路。
- Provider、Agent Profile、会话、消息、最小 Agent Run、审批请求、独立 ToolCall/ToolResult、长期记忆、声明式 Skill 和 Workflow Ledger 已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- Room compiler 已从 KAPT 切换到 KSP，`app/schemas/` 保存历史 v4、v6-v24 与当前 v25 Schema；迁移测试源码覆盖 v4→v25、v19→v20、v20→v21、v21→v22、v22→v23、v23→v24、v24→v25 和全新 v25 建库。
- UI 以聊天消息为中心，已能在 `/agent` 消息下方显示当前 Run 时间线和最小审批卡片；设置页 Agent 任务中心可以筛选任务、按调用查看 Ledger-first 四阶段工具明细、完整结果/步骤/审批/事件和双源一致性告警，并对可重试终态创建关联的新 Run。工作流页支持 1 至 8 步创建/编辑/排序、一次/每日/每周计划、定义与运行快照展开、来源 Run 标识和新 Run 重试。

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

- `MessagePart.Text / Reasoning / Image / Document / Tool` 是当前结构化消息模型。Image 保存文件名、规范 MIME、原始字节和 `AUTO` detail；Document 保存原始字节、受预算约束的 UTF-8 提取文本或 PDF 页数，以及 `AUTO` detail，DOCX/PPTX/XLSX 则保存经本地 ZIP/OPC 结构校验的原始包；Reasoning 保存稳定 part ID、`PROVIDER_SUMMARY` 来源、供应商 item ID、summary index 和摘要正文；Tool 继续保存工具名、排序参数、结果、成功状态、验证状态和记忆引用。
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

- `AgentRuntimeOptions` 默认把单个 Run 限制为最多 4 次工具调用，并控制模型/工具执行预算、模型步骤超时和工具步骤超时；用户阅读审批卡片的等待时间不消耗执行预算。
- `ToolDefinition` 统一声明输入类型、长度/范围/枚举、业务校验器、风险、确认策略、Android 权限、后台能力、超时和验证策略；风险与确认不信任模型声明。
- `AgentProfileRecord / AgentProfileSnapshot` 固定名称、标识、Provider、模型、API 模式、系统提示词、上下文策略、工具白名单、Skill 白名单和记忆开关。设置页支持新增、编辑、选择和删除；至少保留一个 Profile，Provider 删除或模型停用前会检查 Agent 绑定关系。
- `ProfileScopedToolRegistry` 在 `availableTools()`、`definition()`、`execute()` 和已提交结果验证四个入口强制 Profile 工具白名单；`SkillScopedToolRegistry` 只能在此基础上继续取交集。Profile 系统提示词被明确包裹并声明只能调整表达与授权能力内偏好，不能修改协议、安全规则或执行事实。
- `/agent` 单次记忆开关与 Profile 记忆开关取交集；Profile 关闭记忆时单次 Run 不能重新打开。前台 Workflow 一次执行固定同一 Profile，后台 Worker 在一次执行开始时读取并缓存同一 Profile，避免步骤间配置漂移。
- 模型提示使用 `object/properties/required/additionalProperties=false` JSON Schema；解析层先按原始 JSON primitive 拒绝错误类型和非 object `arguments`，再规范化到字符串 Map 供 Runtime 做长度/范围/枚举与业务校验，不自动补字段或接受未知字段。
- `ToolPermissionChecker` 默认 fail-closed；生产链路使用 `ContextCompat.checkSelfPermission` 在参数校验、审批结束后执行前和工具返回后验证前三个检查点读取定义中的 Android 权限。审批期间撤权不会创建 `tool.execute`，工具执行期间撤权会保留成功结果审计但拒绝验证与总结。
- `ToolExecutionReceipt` 位于现有执行 seam：Executor 可返回 ToolCall ID、业务 operation ID、可选幂等键和提交状态，Runtime 在成功 `tool.result` 落库前校验回执必须属于当前 ToolCall。回执与执行时 `ToolReplaySafety` 声明快照随 typed metadata 持久化，并在任务中心事件中显示调用、操作、状态、重放声明和“幂等证明已记录/未记录”，原始幂等键不直接展示；旧事件没有快照时按 `RESTART_REQUIRED` 解码。
- Room v20 的 `agent_tool_calls` 以 ToolCall ID 为主键，保存 Run、工具、风险、排序后的参数，以及 proposed/validated RunEvent 锚点；`agent_tool_results` 以 ToolCall ID 为主键，保存结果事件、正文、显式错误、耗时、Executor 验证、最终验证、记忆引用、重放声明和拆列后的执行回执。`RoomAgentRunRepository.toolLedger(runId)` 提供单 Run 查询，`recentRunDetails()` 通过 `getToolCallsForRuns / getToolResultsForRuns` 批量加载最近 Run，避免任务中心 N+1 查询。
- `RoomAgentRunRepository.appendEvent()` 在同一 Room 事务中先写 RunEvent，再按 typed metadata 双写工具账本。相同 ToolCall 的 Run、工具、风险或参数漂移会回滚整个事务；`tool.verify` 通过新增的可选 ToolCall ID 精确更新结果。任务中心、受限恢复和失败 Run 重试副作用判断对账本非空的新 Run 使用 Ledger-first，并以 typed RunEvent 核对身份、字段、派生错误、时间、锚点和顺序；部分缺失或漂移在展示层显示审计告警，在安全策略中 fail-safe。v19 迁移后账本为空的旧 Run 继续回退 typed RunEvent，缺少 ToolCall ID 的结果/验证独立显示为“关联未知”，不按工具名猜测归属。三条消费路径共享 `AgentToolLedgerConsistencyPolicy`，避免双源规则漂移；Run 质量和模型遥测没有等价 Tool Ledger 字段，继续读取 Step 与 `llm.*` typed event。
- `AgentRunRecoveryEvidencePolicy` 为受限恢复提供独立证据读取：v20 非空账本按 proposed 事件锚点重建调用顺序，要求调用与结果一一对应，并核对 proposed→validated→result→verified 的身份、字段、时间和顺序；任何部分账本、额外事件或双源漂移均返回 `Invalid`，不得退回事件路径。账本完全为空时才进入旧 typed event fallback；旧验证缺少 ToolCall ID 时按原结果顺序匹配，保持历史恢复结论。`ToolExecutionRecoveryEvidencePolicy` 随后继续校验执行时与当前定义均为 `IDEMPOTENT_BY_KEY`、结果成功、回执 `COMMITTED` 且幂等键完整。`AgentRunResumePolicy` 只恢复最后一个尚无验证终态的完整结果，不恢复旧规划协程、通用执行栈或 Workflow 后续步骤。
- Runtime 接收 `FOREGROUND / BACKGROUND` 执行来源；后台来源只能执行 `supportsBackground=true` 的工具。当前仅当前时间、会话查询、笔记查询和长期记忆查询这 6 个 SAFE 只读工具开放后台；`notes.create / memory.remember` 在后台规划到审批步骤时直接进入 `BLOCKED`，不会调用审批 Gate。
- Registry 初始化会拒绝重复工具名；`memory.remember` 已通过可插拔业务校验器限制标签数量和单标签长度。
- `AgentRunUseCase` 使用 reporting ledger 回读 Room 快照，ViewModel 将 `AgentRun / AgentStep / RunEvent` 渲染成当前对话内的运行时间线。
- 审批使用 suspend `ApprovalGate` 挂起等待 UI 决策；`ApprovalRequest` 独立记录待确认工具、风险、参数、过期策略、决定结果和决定原因。
- 当前交互审批不按固定倒计时主动过期，只有用户批准、拒绝、停止生成或应用启动恢复收敛时改变状态；`EXPIRED` 保留给后续明确截止时间的工具策略。
- 当前 ViewModel 会按 conversationId 缓存正在显示的 Run 时间线和审批卡片；仅切换会话/页面再返回不会丢失当前活跃卡片。
- 设置页「Agent 任务中心」从 Room 读取最近 50 条 Run，支持全部、处理中、可重试、已完成四档筛选；展开后按 ToolCall 展示 proposed、validated、result、verified 四阶段和完整 content/success/verified/duration。数据源明确标注为“独立工具账本”或“旧 Run 事件兼容”，双源不一致显示稳定告警码；原事件时间线、步骤和审批请求仍完整保留。事件展示直接消费 Repository 解码后的 typed metadata，旧纯文本事件回退显示 `message`。最新 `run.recovery_failed` 会额外显示在详情顶部的错误状态带，并在事件区保留完整工具名、错误码、原因和建议。
- `AgentRunMetricsPolicy` 只根据持久化 Run、Step、Approval 和 typed RunEvent 汇总指标，不依赖页面瞬时状态：单 Run 统计创建到终态的耗时、模型/工具/审批次数，并从 `llm.request.completed` 聚合模型总耗时、平均 TTFB、Prompt 字节和 Token usage；历史汇总只用终态 Run 计算成功率、平均耗时和失败分布，活动 Run 不进入质量分母。任务中心的汇总带、列表卡和详情区使用同一纯呈现函数，避免三处口径漂移。
- Agent 规划和总结固定使用非流式请求。网络层在首个响应 body 字节实际可读后记录 TTFB，以最终 JSON 请求体的 UTF-8 字节数记录 Prompt 规模，并兼容 Chat Completions 的 `prompt_tokens / completion_tokens` 与 Responses 的 `input_tokens / output_tokens`。上游缺失 usage 时字段保持 `null`；规划 JSON 解析失败时，已经返回的请求遥测仍先写入 RunEvent，再收敛 Run 失败。
- `FAILED / CANCELLED / BUDGET_EXHAUSTED` 可重新运行。重试在来源会话追加新的 `/agent <goal>` 消息，使用当前选中的 Agent Profile 创建带 `retryOfRunId` 的新 Run；旧 Run 的 Profile 快照、状态、结果、步骤和事件不修改。非空账本中的非 SAFE 调用只要 `result.success=true`，或回执状态为 `COMMITTED / UNKNOWN`，UI 就先要求二次确认；账本异常也按可能已有副作用处理。明确失败且回执为 `NOT_COMMITTED`、或只完成 proposed/validated 尚未执行时，不单独增加确认。账本全空的旧 Run 才使用 typed event 成功结果回退；恢复记录表明中断发生在 `EXECUTING/VERIFYING`，或 `tool.execute/tool.verify` 步骤以失败/取消结束时仍按原规则确认。
- 待审批恢复和 `notes.create / memory.remember` 已提交结果恢复读取原 Run 的 `agent.profile.selected` 快照并重新构造 Profile/Skill 双层 Registry。历史 Run 没有该事件时走旧兼容路径；重复、无法解析、包含未注册工具或 Skill 超出 Profile 工具面的审计均拒绝恢复。
- 重试正式启动时 ViewModel 选中来源会话并发出一次性导航信号，根 UI 回到对话页；重新触发的写工具仍走正常审批，审批卡不会隐藏在任务中心后台。
- 应用启动时会保留尚未执行任何工具的 `WAITING_APPROVAL` Run；批准后先执行持久化的首个工具，再携带其已验证结果继续同一 Run 的多步规划。已经进入任意工具执行/验证步骤的多步 Run 默认会安全收敛为 `CANCELLED`，其所有 `PENDING/RUNNING` Step 同步改为 `CANCELLED`。受限例外是最后一个 `notes.create` 或 `memory.remember` 已落库完整 `COMMITTED + IDEMPOTENT_BY_KEY` 结果且尚未验证：启动时补齐原 execution Step，按 operation ID 只读回读业务记录，写入 `tool.verify` 和 `recovery.summarize`，再以本地可信总结完成原 Run；前序已通过 `tool.verify` 的工具事实会按执行顺序一并重建到总结和可信上下文。该路径不调用写入方法；若它属于 Workflow，启动对账先跳过该候选，待恢复成功写回当前步骤输出后再把剩余 Workflow 收敛为 `FAILED`，后续通过关联新 Run 复用成功前缀。
- 取消、失败、预算耗尽和超时都会写入终态；取消/失败落库使用不可取消清理块，避免 Run 卡在中间态。
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
- 旧单步骤兼容入口收到同一 Agent Run 的重复快照回调时，优先命中已经关联该 Run 的步骤，再执行幂等状态刷新；不会因为步骤已经进入 `RUNNING` 就错误关联到后续步骤。
- `BLOCKED / FAILED / CANCELLED` Workflow Run 可创建新 Run 重试。新 Run 通过 `retryOfWorkflowRunId` 关联来源，把连续成功前缀标为 `SKIPPED` 并记录 `reusedFromStepId`，首个未完成步骤及后续步骤恢复为 `PENDING`；旧 Run 不修改。
- 应用启动时先按原策略恢复/关闭 Agent Run，再对账活动 Workflow Run：可恢复的 `WAITING_APPROVAL` 保持运行中；批准并完成当前步骤后继续同一 Workflow Run 的下一步骤。若进程重建时当前 Agent 已完成但后续步骤尚未启动，则先保留当前输出，再把旧 Run 收敛为失败，用户通过新 Run 重试复用成功前缀，绝不自动重放可能有副作用的步骤。
- Room v14 新增结构化 `ScheduledTask`，Room v15 新增唯一 `workflow_schedules` 规则，Room v16 新增 Workflow 步骤定义与步骤快照，Room v17 为 `agent_notes.idempotencyKey` 增加可空唯一索引，Room v18 新增 `agent_memory_operations` 幂等操作账本，Room v19 为 operation 增加可空 `resultHash`，Room v20 新增 `agent_tool_calls / agent_tool_results`；v18 记忆 operation 和 v19 RunEvent 均不补造缺失证据。
- 工作流页可创建 1 分钟至 7 天的一次性计划并取消尚未执行的计划。`OneTimeWorkRequest.setInitialDelay` 配合联网约束和唯一工作名提供非精确调度；产品文案明确系统可能延迟，不承诺准点。
- Daily/Weekly 规则保存本地墙上时间、`ZoneId` 和可选周几。实现不使用 `PeriodicWorkRequest`：规则只维护一个未来 OneTime 实例；实例进入任意终态后，按规则时区计算并物化下一未来实例，每次实例均使用新的 ScheduledTask、WorkRequest、Workflow Run 和 Agent Run ID。
- 同一 Workflow 最多一个周期规则。替换规则在 Room 事务内取消旧待执行实例并创建新实例，再同步取消旧唯一工作；停用规则或 Workflow 会清空 `nextTaskId / nextPlannedAt` 并取消 WorkManager。周期实例不暴露一次性任务取消入口，避免留下仍会继续生成下一实例的启用规则。
- 启动恢复会先把无法重建执行栈的 RUNNING ScheduledTask 按关联 Workflow Run 终态收敛，再为仍启用的规则物化一个未来实例；已物化但尚未关联 WorkRequest 的实例只补入队，不补跑错过的历史周期，也不复制旧 Agent Run。
- Worker 使用同一 `AgentRunUseCase`，但强制传入 `AgentExecutionOrigin.BACKGROUND`。SAFE 后台工具可完成原有校验与验证；需要审批的工具写入 Agent/Workflow/ScheduledTask `BLOCKED` 终态并通知用户以前台新 Run 重试，绝不等待前台审批卡或继承临时授权。
- Android 8+ 使用稳定通知 Channel；Android 13+ 从用户创建计划的操作中请求 `POST_NOTIFICATIONS`。完成、失败、阻断和系统取消都会写入 Ledger；通知被拒绝时不影响任务终态。
- 当前没有 AlarmManager、精确闹钟权限或 Foreground Service；WorkManager 业务结果也不使用系统自动重试，避免复制可能已经执行过的 Agent Run。2026-07-19 的真实后台三步骤从 `02:06:11` 到 `02:06:42` 完成，约 31 秒，当前继续使用普通 WorkManager 即可；Foreground Service 仅在后续长任务证据表明需要持续运行通知和停止入口时引入。

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
- 数据库当前版本为 v26，启用 `exportSchema`；`XiaoLingDatabaseMigrationInstrumentedTest` 覆盖正式 v4→v26 的关键增量和全新 v26 建库。旧 Workflow、笔记、记忆、工具账本、Profile 和 MessagePart 的历史字段按各阶段保守迁移，v25→v26 只创建空知识库表，不猜造文档或检索记录。
- 旧消息迁移后统一得到 `origin=LEGACY`，`verifiedAgentContext` 默认为 `null`；v7 旧 Run 的 `retryOfRunId` 初始化为 `null`，v8 旧记忆的 `pinned=false` 并在迁移时回填 FTS，v9 正式记忆不会被倒推成候选，v10 旧记忆的生命周期字段保持空值，v11 升级后 Skill 表为空并由应用启动同步内置定义。
- AgentMemory 保存内容、标签、类型、来源会话、来源 Run、来源摘要、置信度、启用/置顶状态、可空过期时间、最近引用时间和时间戳；`AgentMemoryStore` 只向工具暴露写入与检索，`AgentMemoryManager` 独立提供 UI 管理能力。
- 记忆检索优先使用 Room FTS4 `unicode61` 做英文/标签前缀召回，并用 `LIKE` 兜底中文和任意子串；启用记忆会排除明确过期项，命中后回写 `lastReferencedAt`。结果按置顶、置信度和按类型配置的半衰期排序，衰减只影响排序，不修改正文或删除记录。
- 设置页「长期记忆」支持候选开关与确认、搜索、启用状态筛选、编辑、置顶、启停、删除确认、当前会话撤销和来源审计；来源会话与来源 Run 存在时可直接跳转。
- 设置页「数据备份与恢复」通过 Android SAF 导出/导入 ZIP；备份包含 Room 主库和 schema/app manifest，导入先校验 manifest 与真实 SQLite `user_version`，再关闭 Room、保留 `.pre-restore` 安全副本并替换数据库，完成后必须重启应用。
- 备份不导出 API Key 明文；Provider 表中的密文仍依赖当前 Android Keystore，跨设备或密钥丢失时不能仅凭数据库恢复凭据。未来可增加不含凭据的 Provider 元数据迁移向导。
- 长期记忆的引用审计目前落在 Agent Run 的 ToolResult 和 VerifiedAgentContext；删除或禁用记忆后新 Run 不会产生对应 ID，历史 Run 保留原始审计快照，不回写旧事件。
- `xiaoling` 和 `xiaoling_conversations` SharedPreferences 只作为旧数据迁移来源；迁移成功后不会反复恢复旧数据。
- 主题、候选记忆开关、三类提示词和 User-Agent 偏好保存在 `xiaoling_ui` SharedPreferences；UA 保存时移除换行并限制长度，空白值恢复默认配置。
- API Key 只以 AES-GCM 密文落盘，密钥材料保存在 Android Keystore。

## 本地知识库与 RAG 数据基础

- Room v26 新增 `knowledge_documents / knowledge_chunks / knowledge_chunks_fts / knowledge_retrievals`。规范全文和 chunks 都保存在主数据库中，因此现有数据库 ZIP 备份自然包含知识库数据，不依赖外部 URI 或旁路文件。
- `KnowledgeTextPolicy` 第一版只处理 TXT、Markdown、JSON 和 CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符。导入会移除 BOM、统一 CRLF/CR 为 LF、拒绝空白与 `NUL`，并对规范全文计算 SHA-256；`parserVersion=1` 明确冻结当前解析语义。
- 分块默认上限 1600 字符、重叠 200 字符，优先在后半窗口的段落分隔处结束；没有合适段落边界时才硬切。每块保存 `[startOffset, endOffset)`，正文必须等于规范全文对应子串，并修正 UTF-16 高低代理项边界。
- chunk ID 包含文档 ID、revision、sequence 和内容哈希前缀。替换始终递增 revision，并在单个 Room 事务内更新文档、删除旧 FTS/chunks、插入新 chunks/FTS；注入新 chunk 插入失败的真机测试确认全文、revision、旧 chunks 与旧索引会一起回滚。
- 检索优先执行 FTS4 `unicode61` 前缀查询，同时执行转义 `% / _ / \\` 的多词 `LIKE` AND 查询作为中文和字面子串兜底；结果按 chunk ID 去重并限制最多 20 条。每次调用，包括空命中，都会记录 query、实际 chunk/document ID、来源会话、来源 Run 和时间。
- 禁用只保留数据并立即退出检索；删除在事务内清理 FTS、chunks 和文档。第一版没有知识库管理 UI、Agent 工具、模型上下文注入、答案引用呈现或 Embedding，后续接入时必须继续使用本阶段的 chunk/revision 引用和审计契约。

## 日志

- debug 包默认开启 HTTP 调试日志：`BuildConfig.XIAOLING_HTTP_LOGS_ENABLED = true`。
- release 包默认关闭 HTTP 调试日志。
- 日志会对 Authorization 和包含 key 的 Header 做脱敏。
- 网络层把连接建立失败，以及带明确 EOF、connection reset、broken pipe 或 stream reset 标记的响应中断归类为 `CONNECTION`；其他 `ProtocolException` 归类为 `RESPONSE`，无法识别的 I/O 仍为 `UNKNOWN`，避免扩大后续自动重试范围。

## 当前限制

- 暂不提供云同步和账号体系。
- 尚未内置外部真实工具调用、MCP 和手机自动化执行；当前真实工具限于时间、会话检索、本机笔记和本机长期记忆。
- 暂不提供 Provider 模板市场。
- 更换 `applicationId` 后，旧版本本地数据不会自动迁移。
- Responses Adapter 已支持文本、用户图片/文档、`function_call / function_call_output` typed Items 和可选 Reasoning summary；Room/Compose 已完成 Text/Reasoning/Image/Document/Tool parts 垂直切片，DOCX/PPTX/XLSX 已完成结构校验与真实模型直传。当前 Agent Runtime 仍使用提示词 JSON 做最多 4 步的顺序工具规划，尚未直接使用上游原生函数调用循环；附件暂不进入 `/agent`。超过 8 MB 或跨文档资料已具备严格文本全文、分块、FTS/中文兜底和引用审计的数据基础，但尚无管理 UI、Agent 工具、模型引用注入或答案引用呈现。
- `/agent` 目前只接入第一批应用内低风险工具；任务中心已支持失败终态安全重新运行。进程重建后的恢复边界策略已经落地：仍处于 `WAITING_APPROVAL`、存在 `PENDING` 审批且尚未出现工具执行/验证记录的 Run 可原地恢复；执行/验证中间态默认必须安全重新运行，仅 `notes.create` 与 `memory.remember` 的完整已提交证据可进入受限只读验证。
- 当前模型请求审计不保存 Prompt 正文，也不估算价格；只保存最终请求体字节、计时和上游明确返回的 Token usage。流式普通对话仍沿用消息级首 Token 指标，Agent 非流式请求使用 TTFB，两者不混算。
- 启动协调器已保留 `APPROVAL_WAIT` Run 并把待审批请求重建到当前会话；发起 `/agent` 后会先持久化用户消息，旧数据缺少消息锚点时再依据 Run 的 `userMessageId / goal / createdAt` 补回。执行/验证中 Agent Run 默认与活动 Step 一致安全收敛，只有具有完整历史证据的 `notes.create` 与 `memory.remember` 会恢复只读验证和本地总结。多步骤 Workflow、步骤快照、安全重试、真实后台执行和审批后继续下一步骤均已完成真机验收；其他写工具和后台通用执行栈断点续跑仍不开放，Foreground Service 暂无真实耗时依据支持引入。
- 恢复测试同时覆盖审批恢复同 Run 完成、两个白名单写工具的已提交结果不调用写入方法而完成验证恢复、恢复工具失败写入原 Run `FAILED`、其他执行/验证中 Run 与 Step 一致取消，以及失败后安全重试必须二次确认；Room instrumentation 覆盖关闭并重开磁盘数据库后保留验证候选，真实 Registry 测试覆盖按 operation ID 回读且不新增笔记或记忆。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
