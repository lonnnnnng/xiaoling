# 小灵个人 Agent 路线图

## 结论

小灵在 `v0.1.9` 之后的当前 `main` 已具备可执行应用内任务的最小个人 Agent：普通聊天与 `/agent` 分流，Runtime 可取消、可限步、可确认、可验证并记录 Run、Step、Approval、Event 和 Memory；长期记忆与声明式 Skill 已可管理，Workflow Ledger 已支持 1 至 8 步编辑、顺序前台/后台执行、步骤快照和新 Run 重试，以及 WorkManager 一次性和 Daily/Weekly 非精确定时。多步骤前台/后台与审批恢复已完成真实模型真机验收，任务中心已展示基于持久化审计的 Run 质量、模型 usage/TTFB/Prompt、失败分布和执行回执；网络响应中断、执行/验证中进程终止和 Android 权限运行中撤销均已进入确定性故障注入。执行回执/幂等证据 contract 已建立，`notes.create` 已完成首个 `COMMITTED + IDEMPOTENT_BY_KEY` 存储层垂直切片；验证阶段恢复尚未接入，因此仍不通用原地续跑旧执行栈。

参考项目中最值得学习的不是工具数量，而是以下工程原则：

- `meow-agent`：工具风险元数据、权限策略、后置验证、运行事件和 Workflow Ledger。
- `Operit`：Android 原生工具体系、MCP/Skill、记忆空间和多种移动端能力组合。
- `X-OmniClaw`：统一设备工具、界面观察后执行、按需工具路由、Markdown 记忆与索引、定时自动化。
- `openclaw`：Channel、Gateway、Session、Skill 和自动化边界，以及对长时间运行 Agent 的工程化拆分。
- `mobilerun`：面向移动 UI 的观察、动作和多步任务执行模型。
- `RikkaHub`、`PocketPal AI`、`OGAM`：Provider/模型能力、结构化消息、工具事件流、本地模型和 RAG 的产品化经验。

完整证据见 [参考项目分析](reference-apps-analysis.md)。

## 当前基础与主要缺口

### 已有基础

- OpenAI-compatible Provider 管理和模型同步。
- Chat Completions / Responses API。
- SSE 流式输出和 30ms UI 节流。
- 多会话、摘要压缩、Room 本地持久化。
- Provider、模型、接口模式、流式和耗时等消息元数据。
- Android Keystore 密钥保护和网络错误分类。
- 请求取消和停止生成。
- `AgentRun / AgentStep / ApprovalRequest / RunEvent / AgentMemory` 初始数据模型，以及 `/agent` 模型规划 + 应用内低风险工具链路。
- 最小 Agent Runtime 已具备工具调用预算、模型/工具步骤超时、整次 Run 超时、完整 Schema/业务规则/Android 权限校验、重复工具调用检测和结构化事件记录。
- 对话区已能显示当前 `/agent` Run 的最小时间线和审批卡片，批准后继续执行，拒绝后写入失败终态；交互审批当前不主动过期，审批请求已具备待确认状态和决定结果落库。
- 设置页已有 Agent 任务中心，可按全部/处理中/可重试/已完成筛选，查看完整工具结果、步骤、审批和结构化事件，并为失败、取消或预算耗尽任务创建关联的新 Run。
- 启动协调器已接入并通过真机进程重建验收：首个工具执行前的待审批 Run、用户消息锚点和审批卡片可从 Room 重建；批准后 Runtime 从原审批步骤继续执行工具、验证和后续多步规划并写回同一 Run。已经执行任意工具后再中断，包括第二步等待审批，仍必须安全重新运行。

### 主要缺口

- 当前重试采用安全重新运行而不是原地续跑：旧 Run 保持不变，新 Run 关联 `retryOfRunId` 并重新走模型规划、工具审批和验证；`WAITING_APPROVAL` 的原地恢复已接入，执行/验证中 Run 仍不恢复旧执行栈。
- 首个工具执行前 `WAITING_APPROVAL` 的原地恢复已接入；执行任意工具后的审批等待，以及执行/验证中的旧协程、工具执行栈和验证栈仍不恢复。
- 第一批真实 Tool Registry 已统一声明 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、后台能力、超时和验证策略；生产权限检查器默认 fail-closed，Runtime 已按前台/后台来源执行能力门禁。
- 已有结构化长期记忆表、`memory.search / memory.remember`、FTS 检索、管理 UI、候选确认、敏感过滤、跨进程删除撤销、生命周期、时间衰减、引用审计、去重和冲突处理；更大数据量下的召回质量仍需持续验证。
- 已有内置与本地声明式 Skill 按需选取、严格导入校验、工具白名单和管理 UI；多步骤 Workflow 定义/编辑、前台与后台顺序执行、步骤快照、新 Run 重试、一次性和 Daily/Weekly 调度、通知和审批 blocked 状态已完成。
- 没有 AccessibilityService 或其他手机操作能力。
- ViewModel 仍然过重，后续需要继续迁出上下文、网络和运行编排逻辑。

## 目标架构

```text
Compose UI
  |-- Chat
  |-- Agent Run Timeline / Approval Card
  |-- Memory / Skills / Tasks / Settings
  |
Application services
  |-- ChatService
  |-- AgentService
  |-- WorkflowService
  |
Agent Runtime
  |-- Intent Router: direct chat or agent run
  |-- Bounded Tool Loop
  |-- Tool Policy / Approval / Verification
  |-- Cancellation / Resume / Event Log
  |
Capability layer
  |-- ToolRegistry
  |-- SkillRegistry
  |-- MemoryRetriever
  |-- DeviceController
  |
Data layer
  |-- Room: conversations, runs, steps, memories, skills, tasks
  |-- Android Keystore: provider secrets
  `-- WorkManager / AlarmManager: scheduled execution
```

建议逐步拆出以下包：

```text
com.longdev.xiaoling.domain.agent
com.longdev.xiaoling.domain.tool
com.longdev.xiaoling.domain.memory
com.longdev.xiaoling.data.db
com.longdev.xiaoling.data.repository
com.longdev.xiaoling.llm
com.longdev.xiaoling.agent.runtime
com.longdev.xiaoling.agent.tools
com.longdev.xiaoling.agent.skills
com.longdev.xiaoling.automation
com.longdev.xiaoling.device
com.longdev.xiaoling.ui.agent
```

不必立刻拆成多个 Gradle Module，但代码依赖方向必须先固定，避免 UI、网络、存储和工具互相直接调用。

## 里程碑 0：稳定现有聊天底座（部分完成）

目标：在引入 Agent 前，让现有请求和数据结构具备扩展条件。

当前状态：请求取消、停止生成、Room 迁移、Schema 导出、v4→v17 迁移测试源码、Repository、Responses API 结构化文本历史、函数 typed Items、`LlmProviderAdapter` 和面向用户的 Room ZIP 备份/恢复已完成；ViewModel 继续瘦身仍待完成。

### 要做什么

- 已完成：给当前请求增加明确的取消能力和“停止生成”按钮。
- 已完成：Responses API 改为结构化消息数组，保留 system/user/assistant 边界。
- 已完成：抽出 `LlmProviderAdapter`，由 `OpenAiCompatibleAdapter` 负责 URL、payload 和响应协议映射。
- 已完成：Responses 输入支持 `function_call / function_call_output` typed Items，并使用 `call_id` 关联调用和结果。
- 部分完成：`ProviderRepository` 和 `ConversationRepository` 已落地，聊天上下文仍需继续迁出 ViewModel。
- 已完成：引入 Room，并为现有 Provider、Conversation、Message 数据实现一次性迁移。
- 已完成：启用 Room Schema 导出，并为带旧数据的 v4→v17 migration 链、v6 JSON event metadata、v7 Run 重试关联、v8 Memory FTS、v9 候选表、v10 生命周期、v11 Skill、v12 Workflow、v13 ScheduledTask、v14 WorkflowSchedule、v15 多步骤 Workflow、v16 笔记幂等键迁移和全新 v17 建库提供自动化测试；2026-07-19 最新代码已在 Pixel_9 与 Redmi 各执行 42 条 instrumentation。
- 已完成：增加面向用户的数据库 ZIP 备份与恢复能力；恢复前校验 schema，替换前保留 `.pre-restore`，并明确 Keystore 密文不可跨设备解密。
- 待完成：继续迁出 ViewModel 中的上下文、网络和运行编排，使其只负责 UI 状态编排。

### 验收标准

- 原有 Provider 和会话升级后不丢失。
- 流式和非流式请求都能立即取消，不再追加内容。
- Chat Completions 与 Responses API 回归测试通过。
- 进程重建后可以正确恢复会话，但不会把未完成请求当成功。

## 里程碑 1：最小可用 Agent Runtime（最小闭环已交付）

目标：完成“判断是否需要工具 -> 调用工具 -> 获取结果 -> 继续推理 -> 输出最终答案”的受控闭环。

当前状态：`/agent` 最多 4 步的顺序工具闭环、运行预算、超时、取消、逐步审批、后置验证、多工具可信上下文、Run 时间线、RunEvent typed metadata、可操作任务中心、安全重新运行和第一批应用内工具已完成；执行/验证中断已明确采用 Run/活动 Step 一致取消和关联新 Run 重试。持久化执行回执 contract 已建立，`notes.create` 已具备第一个生产幂等副作用证明；独立 ToolCall/ToolResult 表与并行调用仍待完成，通用原地断点恢复继续关闭。

### 核心数据模型

- `AgentProfile`：名称、system prompt、默认 Provider/模型、启用的 Skill。
- `AgentRun`：目标、来源、状态、开始/结束时间、当前步骤、最终结果。
- `RunEvent`：状态变化、模型决策、工具调用、工具结果、确认、错误。
- `ToolDefinition`：名称、描述、输入 Schema、风险、权限、确认和验证规则。
- `ToolCall` / `ToolResult`：参数、结果、错误、耗时、重试和验证状态。当前每一步先进入 Step/Event 审计，独立数据表仍待后续评估。
- `ApprovalRequest`：待确认动作、风险说明、过期策略和用户决定。当前每个非 SAFE 工具步骤独立审批且不主动过期；只有首个工具执行前的待审批边界允许原 Run 恢复。

### 运行状态

第一版保持简单，不照搬多阶段重型规划器：

```text
idle -> deciding -> waiting_model -> waiting_approval
     -> executing_tool -> verifying -> waiting_model
     -> completed / failed / cancelled
```

### 必须实现的运行约束

- 普通问答走 direct chat fast path。
- 最大工具步数、单步超时和整次 Run 超时均由应用配置。当前最小 Runtime 已有初版配置。
- 连续重复同一工具和相同参数时触发循环检测；顺序多步循环复用同一 Run 级指纹集合和工具调用预算。
- 模型只能看到当前允许的少量工具，不在每轮注入全部 Tool Schema。
- 已完成：工具参数先做 JSON Schema、未知参数、业务规则和 Android 权限校验，再进入审批与 Executor。
- 风险和确认要求取自 `ToolDefinition`，忽略模型自己声明的风险级别。
- Run 可取消；取消后不再接受迟到的流式事件或工具结果。
- 所有状态变化写入 `RunEvent`，UI 显示简洁任务时间线。当前已在对话流里显示最小时间线，并在设置页提供可筛选、可重试的任务中心；ToolResult 完整正文、成功/验证状态和耗时均可查看。

### 第一批工具

先做可验证、低风险、应用内部工具：

- `app.current_time`
- `app.list_conversations`
- `app.search_conversations`
- `notes.list`
- `notes.search`
- `notes.create`，执行前确认，执行后重新读取验证
- `memory.search` / `memory.remember`，以及长期记忆管理 UI、FTS、启停/删除、来源审计、候选确认、敏感过滤、去重/冲突和当前会话删除撤销。

暂不做任意文件写入、Shell、应用安装、发送消息和系统设置修改。

### 验收标准

- 模型可通过工具完成“查找旧会话”和“创建一条笔记”。
- 用户能看到工具名称、关键参数、执行结果和验证状态。
- 拒绝确认后 Run 正确结束或改走其他方案。
- 工具返回成功但后置读取不一致时，结果标记为“未验证”，不得宣称完成。
- 状态机、循环检测、取消和确认都有确定性测试。

## 里程碑 2：长期记忆（候选治理闭环已完成）

目标：把“会话摘要”与“跨会话个人记忆”分开，让记忆可见、可控、可追溯。

当前状态：Room 结构、来源审计、`memory.search / memory.remember`、FTS4 + 中文兜底、管理 UI、默认关闭的候选生成、敏感阻断、去重/冲突、跨进程删除撤销、实际引用 ID 审计、单次召回关闭、可空过期策略和时间衰减排序已完成。

### 记忆类型

- `Preference`：稳定偏好，例如语言、常用格式和习惯。
- `ProfileFact`：用户明确提供的个人信息。
- `Episode`：重要任务和事件结果。
- `Procedure`：经过验证的重复操作方法。

### 实现顺序

1. 已完成：Room 保存结构化 Memory，包含来源会话/Run、原文摘要、类型、置信度、更新时间、启用和置顶状态。
2. 已完成：提供记忆管理页，支持搜索、查看/跳转来源、编辑、置顶、禁用和删除确认。
3. 已完成：成功轮次结束后只从明确陈述生成“候选记忆”，由确定性规则过滤；候选功能默认关闭，敏感内容只保存类别和固定提示。
4. 已完成：第一版使用 Room FTS4，并为中文和任意子串保留 `LIKE` 兜底；验证更大数据集召回质量后再考虑 Embedding 和向量索引。
5. 已完成：将检索结果以有限条目注入 Agent 工具结果，并在 `ToolResult`、任务中心和 `VerifiedAgentContext` 记录本轮实际使用的 memory ID；旧事件按空列表兼容。
6. 已完成：对话输入区的 `/agent` 单次「记忆」开关；关闭后从规划器工具清单移除 `memory.search`，执行层保留二次保护并写入关闭召回审计事件，发送后自动恢复默认开启。
7. 已完成：规范化去重和同主题冲突标记，不直接覆盖旧事实；过期字段默认为空，管理页可选择永久、30 天、90 天或 1 年，启用检索排除过期项，置顶项不参与时间衰减。

### 验收标准

- 用户可以回答“你为什么记住这件事”，并跳转到来源。
- 删除或禁用的记忆不再被检索。
- 同一事实不会无限重复写入。
- API Key、token、密码、银行卡、身份证和手机号命中后不保存原值。
- 删除后立即退出检索；最近一次删除在应用重启后仍可撤销，并恢复主表、来源字段、生命周期字段和 FTS。
- 记忆检索失败不影响普通聊天和工具执行。

## 里程碑 3：Skill 与能力按需加载

目标：把可复用任务知识从系统提示词中移出，并避免工具数量增长后 Prompt 膨胀。

当前状态：已交付会话检索、本机笔记、长期记忆和设备时间四类内置声明式 Skill，以及版本化本地 JSON 导入、严格静态校验、Room 持久化、启停和删除管理。规则按目标稳定选择最多 3 个已启用 Skill，工具白名单只能缩小已注册工具面并写入 Run 审计；顺序多步 Runtime 可以在多个已选 Skill 的工具并集中逐步执行。

### Skill 结构

每个 Skill 至少包含：

- `id`、名称、版本和说明。
- 触发描述和示例任务。
- 依赖的工具列表。
- 所需 Android 权限和风险等级。
- 执行步骤、失败恢复和完成标准。
- 来源、校验状态和是否启用。

### 实现策略

- 内置 Skill 当前由 Kotlin 稳定定义并在启动时同步到 Room；本地 Skill 使用 `schemaVersion=1` JSON，后续如需将内置定义迁为 assets 文件必须保持同一验证契约。
- 先通过轻量分类器或规则选择 1-3 个 Skill，再只加载其指令和工具。
- Skill 不能直接获得未注册工具，也不能降低工具风险或绕过确认。
- 已完成：第一版只允许导入本地声明式 JSON Skill，不执行任意代码；未知字段、未注册工具、风险或权限不一致均拒绝导入。
- “从成功任务生成 Skill”放到后期，生成后必须经过用户审核和静态校验。

### 首批 Skill

- 会话检索与总结。
- 笔记整理。
- 每日回顾。
- Provider 健康检查。
- 失败请求诊断。

## 里程碑 4：任务与自动化

目标：让用户保存可重复任务，并能查看每次执行结果。

当前状态：已交付 `Workflow / WorkflowStepDefinition / WorkflowRun / WorkflowStep / WorkflowSchedule / ScheduledTask` Room Ledger、1 至 8 步创建/编辑、前台与后台顺序执行、步骤级输入/输出快照和幂等键、失败新 Run 重试、一次性与 Daily/Weekly 计划、结果通知和后台 blocked 审批；前台三步骤重试、定义编辑冻结历史、后台三步骤与审批恢复继续下一步骤均已通过真机。后台执行栈断点续跑和精确定时仍待评估，真实 31 秒后台任务暂不需要 Foreground Service。

### 要做什么

- 已完成：`Workflow`、`WorkflowStepDefinition`、`WorkflowRun`、`WorkflowStep`、`WorkflowSchedule` 与一次性/周期 `ScheduledTask` 数据表及关联字段。
- 已完成第一版：WorkManager 负责带联网约束的一次性可延迟任务；Daily/Weekly 规则每次物化一个未来 OneTime 实例，确需准确时间时再评估 AlarmManager 和精确闹钟权限。
- 暂不引入：真实三步骤后台 Run 约 31 秒完成，继续记录更长任务的总耗时和系统回收情况；只有超过 WorkManager 适用边界或需要持续可见停止入口时再启动 Foreground Service。
- 已完成：每次执行保存计划/实际时间、步骤定义快照、输入/输出、重试来源、结果和失败原因。
- 已完成：步骤使用稳定幂等键；重试只复用连续成功前缀，旧 Run 保持不变，已启动失败步骤需要二次确认。
- 已完成：后台任务遇到需要用户确认的敏感操作时进入 blocked 状态，不得静默执行。

### 第一批自动化

- 每日/每周生成会话回顾。
- 定时提醒并附带上下文。
- 定时检查指定 Provider 是否可用。
- 定时整理候选记忆，结果等待用户确认。

## 里程碑 5：Android 设备 Agent

目标：在独立开关和明确权限下，完成有限、可观察、可验证的跨应用操作。

### 技术方案

- 使用 AccessibilityService 获取可访问节点树和执行标准动作。
- 为一次观察生成短生命周期的节点引用，页面变化后引用失效。
- 优先按节点引用点击、输入和滚动；坐标点击只作为兜底。
- 截图和视觉模型作为无法通过节点树理解页面时的可选能力。
- 每个改变业务状态的动作后重新观察，不能仅凭点击成功返回判断完成。
- 增加 Accessibility 健康检查、权限失效提示和稳定态恢复。

### 第一批设备工具

- `device.snapshot`
- `device.open_app`
- `device.back`
- `device.home`
- `device.tap_ref`
- `device.type_text`
- `device.swipe`

### 安全边界

- 默认关闭，需要单独启用设备 Agent。
- 支付、下单、删除、发送、发布、授权和系统设置修改必须再次确认。
- 密码框、验证码、支付页面和隐私应用默认不读取或记录内容。
- 工具结果和截图按隐私级别控制落盘，release 日志不保存原始敏感内容。
- 第一阶段只支持少量已验证应用和流程，不承诺任意 App 通用自动化。

## 里程碑 6：高级能力

以下能力在前述基础稳定后再进入：

- 文件附件、图片理解、语音输入与 TTS。
- 文档解析和 RAG。
- MCP Client 与远程工具，但必须增加 Server 信任、工具审核和网络权限策略。
- 通知摘要、日历、联系人和系统分享入口。
- 多 Agent 分工、远程 Channel、跨设备同步。
- 手机端本地模型和模型下载管理。

## 横向工程任务

这些任务不属于单个功能，但必须贯穿所有里程碑：

- 已建立 Room Schema 导出和 migration 测试；继续补面向用户的数据库备份与恢复工具。
- 建立脱敏结构化日志，统一 `runId`、`stepId`、`toolCallId`。
- 为 Agent Runtime 提供假的 LLM 和 Tool Executor，做确定性状态机测试。
- 建立工具契约测试：Schema、风险、权限、确认和验证信息不能缺失。
- 已完成当前可审计性能指标：任务中心展示 Run 总耗时、终态成功率、平均耗时、模型/工具/审批次数、模型总耗时、平均 TTFB、最终 JSON Prompt 字节、上游 Token usage 覆盖率和失败终态分布；未返回 usage 的请求不补零，Prompt 正文不重复落库。
- 对低能力模型做回归，减少多阶段 LLM 调用和超长工具提示词。
- 已完成当前故障注入基线：用户取消、模型/工具/整次 Run 超时、网络响应中断、Workflow 重复回调、执行/验证中进程终止，以及审批期间和工具执行期间 Android 权限撤销均有确定性测试；真机外部 `pm revoke` 同时确认系统会直接终止应用进程。
- 每个涉及 Android 系统能力的里程碑都必须在真机验证，不以单元测试替代。

## 优先级清单

| 优先级 | 工作项 | 当前状态 | 原因 |
|---|---|---|---|
| P0 | 请求取消、结构化 Responses 输入、Provider Adapter | 已完成，包括函数调用与结果 typed Items；Reasoning/Image/File Items 后续扩展 | 后续 Agent 循环的基础协议 |
| P0 | Room、Repository、迁移测试和导出 | Room/Repository、Schema 导出、v4→v17、event metadata、重试关联、Memory FTS、候选表、生命周期、Skill/Workflow/ScheduledTask/WorkflowSchedule/WorkflowStepDefinition 表迁移、笔记幂等索引和用户 ZIP 备份/恢复已完成 | 保证升级和本地数据可恢复 |
| P0 | AgentRun 状态机、事件日志、取消与恢复 | 最小状态机、事件、取消、安全重新运行、进程终止和运行中撤权边界已完成；`notes.create` 已有首个完整幂等证明，正待验证阶段恢复设计 | 决定任务是否可靠、可观察 |
| P0 | Tool Registry、Schema、风险、确认和验证 | 已完成完整类型/约束/枚举、业务校验器、风险/确认、Android 权限、前后台来源门禁、超时、回读验证策略和重复名称启动校验 | 决定执行边界和安全性 |
| P1 | 应用内低风险工具和任务时间线 UI | 第一批工具、对话时间线、任务中心、完整工具结果、失败重试及 Run/历史运行指标已完成 | 已形成第一条端到端 Agent 链路 |
| P1 | 长期记忆管理与 FTS 检索 | 管理 UI、FTS、中文兜底、来源审计、候选确认、敏感过滤、去重/冲突、跨进程删除撤销、引用 ID 审计、单次召回关闭、过期策略和时间衰减已完成 | 形成个人化和跨会话连续性 |
| P1 | Skill 按需加载 | 内置与本地声明式 Skill、版本化 JSON、严格导入校验、Room Catalog、规则选择、工具白名单、启停/删除管理和 Run 审计已完成 | 控制 Prompt 和工具面增长 |
| P1 | Workflow Ledger 与后台调度 | 多步骤定义/编辑、前后台顺序执行、步骤快照、新 Run 重试、一次性与 Daily/Weekly WorkManager、SAFE/blocked/通知和规则替换/停用已完成；多步骤真实模型真机验收通过，执行中断按 fail-closed 收敛，Foreground Service 暂无引入依据 | 支持持续任务且可追溯 |
| P2 | Accessibility 设备工具 | 未开始 | 扩展到真正移动端执行，风险较高 |
| P2 | 附件、视觉、语音和 RAG | 未开始 | 提升输入输出能力 |
| P3 | MCP、远程 Channel、多 Agent、本地模型 | 暂缓 | 生态价值高，但复杂度和攻击面更大 |

## 明确不照搬的做法

- 不照搬多阶段 Analyze/Reflect/Plan/Review 全部依赖 LLM 的重型流程；先用单循环和确定性状态机。
- 不把所有工具 Schema、数据库结构和 Skill 全量注入每次请求。
- 不允许模型决定工具风险或确认策略。
- 不把“工具返回 success”直接等同于任务完成。
- 不以任意 Shell 作为移动 Agent 的通用工具。
- 不在缺少 Run Ledger、取消和恢复前上线后台自动化。
- 不在缺少权限隔离和工具审核前开放 Skill 市场或 MCP Server 任意接入。

## 建议的下一项开发

基于 `v0.1.9` 当前状态，下一批实际代码任务建议拆为：

1. `WAITING_APPROVAL` 原 Run 恢复已完成不清理 Keystore 的真机验收；继续保持执行/验证中 Run 创建关联新 Run 的安全边界。
2. 已完成跨进程删除撤销；后续后台任务必须复用原子快照与 Room 状态核对边界。
3. 已完成本地 Skill 文件格式、导入校验与启停/管理 UI。
4. 已完成：不依赖调度器的 `Workflow / WorkflowRun / WorkflowStep` Ledger 和前台手动执行闭环。
5. 已完成结构化 `ScheduledTask`、WorkManager 一次性非精确调度、计划/实际时间、结果通知和后台 blocked 审批。
6. 已完成：真机一次性 SAFE/blocked、完成/失败/blocked 通知，以及触发前进程回收后的 WorkManager 冷启动执行验证。
7. 已完成 Daily/Weekly 周期规则：每次触发创建独立 ScheduledTask/Workflow Run，规则替换和停用同步取消 WorkManager，周期触发不复用前台审批等待。
8. 已完成多步骤 Workflow 定义、编辑、步骤级幂等键、输入/输出快照和安全新 Run 重试；后台中断继续收敛旧 Run，不在没有副作用证明时原地续跑。
9. 已完成多步骤前台/后台真实模型真机验收：编辑只影响未来定义，审批后继续下一步骤，失败来源 Run 保持不变，新 Run 正确关联来源并重新执行未完成步骤。
10. 已完成第一批 Run 性能指标和故障注入：任务中心展示总耗时、终态成功率、平均耗时、模型/工具/审批次数；网络响应中断归类为连接失败，取消、超时和重复回调测试保持通过。
11. 已完成请求级审计：规划/总结成功后写入 usage、TTFB、最终 JSON Prompt 字节；规划语义解析失败仍保留已返回遥测；任务中心展示 Token 覆盖率和失败终态分布。
12. 已完成执行/验证中进程终止和 Android 权限运行中撤销故障注入：审批后执行前和工具返回后验证前都会复检权限；进程重建会把旧 Run 与活动 Step 一致取消。通用恢复路径继续要求二次确认并创建关联新 Run，不因单个工具新增幂等证明而放宽。
13. 已完成：建立持久化 `ToolExecutionReceipt`、执行时 `ToolReplaySafety` 声明快照和纯证据判定 module；回执绑定 ToolCall，错配时 Runtime fail-closed，旧事件默认不可重放，当前定义升级不能放宽历史证据，任务中心不显示原始幂等键。
14. 已完成：`notes.create` 使用 ToolCall ID 作为可审计的存储层唯一幂等键，同键同载荷在数据库重开后仍返回同一 operation ID，同键载荷漂移被拒绝；工具已声明 `IDEMPOTENT_BY_KEY`。Room v17 迁移保留旧笔记并把其幂等键留空，Pixel_9 与 Redmi 各 42 条 instrumentation 通过。
15. 下一步仅针对具有完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据的 `notes.create` 设计“验证阶段恢复”：从持久化 ToolResult 回读原 operation，重新执行后置验证和总结，不恢复通用旧协程或其他工具执行栈。接入 `AgentRunResumePolicy` 前先补执行完成后、验证落库前的确定性进程中断注入。

Daily/Weekly 继续使用非精确定时语义并记录每次计划/实际时间。多步骤 Workflow 已具备输入/输出快照、幂等键和重试策略；Foreground Service 只解决系统存活概率，不代表旧执行栈可以安全恢复。当前 31 秒真实后台任务不引入 Foreground Service，执行/验证中断仍保持 fail-closed 边界。
