# 小灵个人 Agent 路线图

## 结论

小灵 `v0.1.10` 已具备可执行应用内任务的最小个人 Agent：普通聊天与 `/agent` 分流，Runtime 可取消、可限步、可确认、可验证并记录 Run、Step、Approval、Event 和 Memory；Agent Profile v1 已分离身份与能力，Room v27 已让 Text/Reasoning/Image/Document/Tool 和知识引用持久化恢复。长期记忆、声明式 Skill、1 至 8 步 Workflow、WorkManager 非精确定时、本地知识库、`knowledge.search`、答案级引用 UI，以及设备 Agent 观察与有限动作层均已交付。`device.snapshot / open_app / back / home / tap_ref / type_text / swipe` 具备独立默认关闭开关、Accessibility 四态健康检查、200 节点/4000 字符有界快照、30 秒 ref、页面 generation/路径/指纹失效、应用白名单、敏感输入拒绝、风险审批和动作后重新观察验证，仅开放给前台直接 `/agent`。首批只对小灵、系统计算器、时钟、设置和桌面完成 Redmi 验收，不承诺任意 App。多步骤 Run 已支持在第二次及后续工具审批处重建已验证前缀并继续原 Run；所有 ToolResult 与 `PASSED` 验证均已持久化时，也可不重放工具、不调用模型地完成原 Run 控制面收尾。不能原地恢复的 Run 现会把稳定处置码、策略原因、证据边界和建议动作冻结到 `run.recovered` 并在任务中心直接展示；Run 进入终态后，Step、Approval、Event 和 Tool Ledger 也同步冻结，迟到执行不能污染 `CANCELLED`。启动恢复先冻结旧候选，并排除当前进程真正 `RUNNING` 的 Worker 链；后台停止则先写入持久化 `STOP_REQUESTED` 栅栏，所以系统取消、即时 fallback、迟到 Worker 与进程重建都不能丢失或覆盖用户意图。即使 Agent Run 尚未关联，Worker 重入也优先读取该栅栏，把 Workflow、未完成步骤和 Task 收敛为取消。Workflow/Task 在同一事务原子结算，周期下一实例只在旧任务终态后物化。模型与工具段使用单调时钟共享累计执行预算。第 49 阶段已取得约 62.2 秒、8 步 SAFE 全部成功的正式 Worker 样本；最新 LMK probe 的 6 条退出均为受控 `FORCE STOP`，`REASON_LOW_MEMORY=0`，仍缺 Android 自主 LMK 证据。当前完整门禁为 404 条 JVM 与仅 Redmi 执行的 140 条 instrumentation。Embedding、设备 Workflow/后台自动化、精确定时与 Foreground Service 仍未交付。

第 43 阶段的同一 WorkRequest Redmi 冷启动重入已完成真实验收：旧 PID 在首步 Agent `THINKING` 时被受控强杀，新 PID 自动重入并按 Agent→Workflow→Task 收敛，没有创建第二个 Agent Run 或继续后续步骤。该样本使用 `run-as kill -9` fallback，不代表 Android 自主回收；该阶段当时的重点是更长/自然回收样本。第 46 阶段已进一步补充 Doze、受控内存和无压力对照，第 47 阶段解决了同一进程前台启动恢复与新 Worker 并发时的所有权隔离；当前仍缺自然 LMK。

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
- 多 Agent Profile 创建、编辑、选择和删除；每个 Profile 固定 Provider/模型、API 模式、系统提示词、上下文策略、工具/Skill 白名单和记忆开关。
- Text/Reasoning/Image/Document/Tool 消息 parts：历史/流式文本兼容、单附件用户图片/文档、供应商 summary 折叠展示、Tool 可信投影、前后台统一 Room 写入和同气泡证据展示。
- `AgentRun / AgentStep / ApprovalRequest / RunEvent / AgentMemory` 初始数据模型，以及 `/agent` 模型规划 + 应用内低风险工具链路。
- 最小 Agent Runtime 已具备工具调用预算、模型/工具步骤超时、整次 Run 超时、完整 Schema/业务规则/Android 权限校验、重复工具调用检测和结构化事件记录。
- 对话区已能显示当前 `/agent` Run 的最小时间线和审批卡片，批准后继续执行，拒绝后写入失败终态；交互审批当前不主动过期，审批请求已具备待确认状态和决定结果落库。
- 设置页已有 Agent 任务中心，可按全部/处理中/可重试/已完成筛选，查看完整工具结果、步骤、审批和结构化事件，并为失败、取消或预算耗尽任务创建关联的新 Run。
- 启动协调器已接入并通过真机进程重建验收：首个工具或任意已验证前缀之后的链尾待审批 Run、用户消息锚点和审批卡片可从 Room 重建；批准后 Runtime 从原审批步骤继续当前工具、验证和后续规划并写回同一 Run，前序工具不重放。

### 主要缺口

- 当前重试默认采用安全重新运行：旧 Run 保持不变，新 Run 关联 `retryOfRunId` 并重新走模型规划、工具审批和验证；`WAITING_APPROVAL` 原地恢复、两个白名单写工具的已提交结果只读验证，以及全部工具已验证后的本地收尾恢复已经接入。
- 旧模型协程、提交状态未知和验证事实不完整的通用工具执行栈仍不恢复。已交付例外都有完整持久化证据：待审批路径不重放已验证前缀，白名单写工具只读验证原 operation，全部 `PASSED` 路径只补控制面与本地总结。
- 第一批真实 Tool Registry 已统一声明 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、后台能力、超时和验证策略；生产权限检查器默认 fail-closed，Runtime 已按前台/后台来源执行能力门禁。
- 已有结构化长期记忆表、`memory.search / memory.remember`、FTS 检索、管理 UI、候选确认、敏感过滤、跨进程删除撤销、生命周期、时间衰减、引用审计、去重和冲突处理；更大数据量下的召回质量仍需持续验证。
- 已有 Room v27 知识文档、chunks、FTS4/中文兜底、检索审计、管理 UI、只读 Agent 工具、模型引用注入和答案引用呈现；Embedding 尚未接入。
- 已有内置与本地声明式 Skill 按需选取、严格导入校验、工具白名单和管理 UI；多步骤 Workflow 定义/编辑、前台与后台顺序执行、步骤快照、新 Run 重试、一次性和 Daily/Weekly 调度、通知和审批 blocked 状态已完成。
- AccessibilityService 观察与有限动作层已经交付，但设备工具仍没有 Workflow/后台执行、坐标/截图兜底或任意 App 通用能力。
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

当前状态：请求取消、停止生成、Room 迁移、Schema 导出、v4→v27 迁移测试、Text/Reasoning/Image/Document/Tool 消息 parts、KnowledgeReference、Repository、Responses API 结构化文本/附件历史、函数 typed Items、可选 Reasoning summary、`LlmProviderAdapter` 和面向用户的 Room ZIP 备份/恢复已完成；ViewModel 继续瘦身仍待完成。

### 要做什么

- 已完成：给当前请求增加明确的取消能力和“停止生成”按钮。
- 已完成：Responses API 改为结构化消息数组，保留 system/user/assistant 边界。
- 已完成：抽出 `LlmProviderAdapter`，由 `OpenAiCompatibleAdapter` 负责 URL、payload 和响应协议映射。
- 已完成：Responses 输入支持 `function_call / function_call_output` typed Items，并使用 `call_id` 关联调用和结果。
- 部分完成：`ProviderRepository` 和 `ConversationRepository` 已落地，聊天上下文仍需继续迁出 ViewModel。
- 已完成：引入 Room，并为现有 Provider、Conversation、Message 数据实现一次性迁移。
- 已完成：启用 Room Schema 导出，并为带旧数据的 v4→v27 migration 链、event metadata、Run 重试、Memory/Knowledge FTS、候选表、生命周期、Skill、Workflow、调度、多步骤快照、笔记幂等键、记忆 operation ledger/结果快照、独立工具账本、Agent Profile、MessagePart 和知识引用提供自动化测试。
- 已完成：增加面向用户的数据库 ZIP 备份与恢复能力；恢复前校验 schema，替换前保留 `.pre-restore`，并明确 Keystore 密文不可跨设备解密。
- 待完成：继续迁出 ViewModel 中的上下文、网络和运行编排，使其只负责 UI 状态编排。

### 验收标准

- 原有 Provider 和会话升级后不丢失。
- 流式和非流式请求都能立即取消，不再追加内容。
- Chat Completions 与 Responses API 回归测试通过。
- 进程重建后可以正确恢复会话，但不会把未完成请求当成功。

## 里程碑 1：最小可用 Agent Runtime（最小闭环已交付）

目标：完成“判断是否需要工具 -> 调用工具 -> 获取结果 -> 继续推理 -> 输出最终答案”的受控闭环。

当前状态：`/agent` 最多 4 步的顺序工具闭环、单调累计执行预算、超时、取消、逐步审批、后置验证、多工具可信上下文、Run 时间线、RunEvent typed metadata、独立 ToolCall/ToolResult Room Ledger、可操作任务中心、安全重新运行和第一批应用内工具已完成；链尾待审批恢复可从任意已验证前缀继续，并恢复已消耗调用数、累计执行时间与循环指纹。所有成功 ToolResult 和 `PASSED` 验证已经落库时，原 Run 还可补齐最后验证 Step 并用本地可信总结收尾。其他执行/验证中断仍采用 Run/活动 Step 一致取消和关联新 Run 重试。独立账本承接 v20 新事件的原子双写，任务中心、三类恢复与失败 Run 重试副作用判断均已切换为 Ledger-first；账本异常时重试 fail-safe 要求确认，账本完全为空的旧 Run 保守回退 typed RunEvent。并行调用与提交状态未知、验证事实不完整的通用原地断点恢复继续关闭。

### 核心数据模型

- `AgentProfile`：已交付名称、标识、system prompt、Provider/模型、API 模式、上下文策略、工具/Skill 白名单和记忆开关；新 Run 冻结完整快照。
- `AgentRun`：目标、来源、状态、开始/结束时间、当前步骤、最终结果。
- `RunEvent`：状态变化、模型决策、工具调用、工具结果、确认、错误。
- `ToolDefinition`：名称、描述、输入 Schema、风险、权限、确认和验证规则。
- `ToolCall` / `ToolResult`：参数、结果、错误、耗时、重试和验证状态。v20 已独立落表并与 typed RunEvent 原子双写；任务中心、受限恢复和重试副作用判断对新 Run 优先读取账本，事件仅核对锚点与字段，旧 Run 在账本全空时回退事件。
- `ApprovalRequest`：待确认动作、风险说明、过期策略和用户决定。当前每个非 SAFE 工具步骤独立审批且不主动过期；只要所有前序工具均已成功验证、链尾 ToolCall 尚未执行且 Approval 与账本完全匹配，首步或后续审批都允许原 Run 恢复。

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

当前状态：已交付会话检索、本机笔记、长期记忆、设备时间和本地知识库五类内置声明式 Skill，以及版本化本地 JSON 导入、严格静态校验、Room 持久化、启停和删除管理。规则按目标稳定选择最多 3 个已启用 Skill，工具白名单只能缩小已注册工具面并写入 Run 审计；顺序多步 Runtime 可以在多个已选 Skill 的工具并集中逐步执行。

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

当前状态：已交付 `Workflow / WorkflowStepDefinition / WorkflowRun / WorkflowStep / WorkflowSchedule / ScheduledTask` Room Ledger、1 至 8 步创建/编辑、前台与后台顺序执行、步骤级输入/输出快照和幂等键、失败新 Run 重试、一次性与 Daily/Weekly 计划、结果通知和后台 blocked 审批；前台三步骤重试、定义编辑冻结历史、后台三步骤与审批恢复继续下一步骤均已通过真机。第 47 阶段加入进程内 Worker 注册表和启动恢复候选快照；第 48 阶段为 `RUNNING` 实例加入可见停止和定向兜底；第 50 阶段进一步让停止意图先持久化为 `STOP_REQUESTED`，并让 Worker 重入、启动恢复、迟到结算和周期物化共享同一取消栅栏。后台执行栈断点续跑和精确定时仍待评估，当前证据仍不需要 Foreground Service。

### 要做什么

- 已完成：`Workflow`、`WorkflowStepDefinition`、`WorkflowRun`、`WorkflowStep`、`WorkflowSchedule` 与一次性/周期 `ScheduledTask` 数据表及关联字段。
- 已完成第一版：WorkManager 负责带联网约束的一次性可延迟任务；Daily/Weekly 规则每次物化一个未来 OneTime 实例，确需准确时间时再评估 AlarmManager 和精确闹钟权限。
- 暂不引入：真实 8 步 SAFE 后台 Run 已在约 62.2 秒全部成功，真实运行中停止样本约 32.6 秒；进程内 Worker 所有权、可见停止与 `STOP_REQUESTED` 持久化重对账均已完成。设备虽支持 LMK 原因报告，但当前历史记录中没有 `REASON_LOW_MEMORY`。只有超过 WorkManager 适用边界或任务对用户足够重要时，才使用 WorkManager 的 long-running worker/Foreground Service 支持。
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

当前状态：观察与有限动作层已完成。应用开关默认关闭，健康检查区分关闭、未授权、服务断连和 READY；全部设备工具仅在前台直接 `/agent` 暴露，Workflow/后台双层拒绝。结构化快照、节点/文本预算、30 秒 ref、窗口 generation/路径/指纹失效、敏感节点脱敏、高敏窗口/隐私应用整窗拒绝、首批应用白名单、敏感输入拒绝、必要审批和动作后重新观察验证均已通过 Redmi 验收。Service 使用标准节点动作与系统返回/主页，不具备坐标手势或截图能力。

### 技术方案

- 使用 AccessibilityService 获取可访问节点树和执行标准动作。
- 为一次观察生成短生命周期的节点引用，页面变化后引用失效。
- 点击、输入和滚动只按短生命周期节点引用执行；当前不提供坐标兜底。
- 截图和视觉模型继续后置，不能成为当前节点校验或隐私过滤的绕过路径。
- 每个改变业务状态的动作后重新观察，不能仅凭点击成功返回判断完成。
- 增加 Accessibility 健康检查、权限失效提示和稳定态恢复。

### 第一批设备工具

- 已完成：`device.snapshot`
- 已完成：`device.open_app`
- 已完成：`device.back`
- 已完成：`device.home`
- 已完成：`device.tap_ref`
- 已完成：`device.type_text`
- 已完成：`device.swipe`

### 安全边界

- 默认关闭，需要单独启用设备 Agent。
- 支付、下单、删除、发送、发布、授权和系统设置修改必须再次确认。
- 密码框、验证码、支付页面和隐私应用默认不读取或记录内容。
- 工具结果按隐私级别控制落盘，release 日志不保存原始敏感内容；当前不采集截图。
- 第一阶段只支持少量已验证应用和流程，不承诺任意 App 通用自动化。

## 里程碑 6：高级能力

以下能力在前述基础稳定后再进入：

- 文件附件、图片与富文档直传基础已完成；`/agent` 附件输入、语音输入与 TTS 仍待实现。
- 文档解析、知识库管理、RAG 检索、Agent 接入和答案级引用 UI 已完成；Embedding 与规模化召回质量验证仍待实现。
- MCP Client 与远程工具，但必须增加 Server 信任、工具审核和网络权限策略。
- 通知摘要、日历、联系人和系统分享入口。
- 多 Agent 分工、远程 Channel、跨设备同步。
- 手机端本地模型和模型下载管理。

## 横向工程任务

这些任务不属于单个功能，但必须贯穿所有里程碑：

- 已建立 Room Schema 导出、migration 测试，以及面向用户的数据库 ZIP 备份与恢复工具。
- 已建立脱敏网络/运行日志和稳定 `runId / stepId / toolCallId` 审计身份；新增设备或远程工具时继续沿用该边界。
- 已为 Agent Runtime 提供假的 LLM 和 Tool Executor，覆盖确定性状态机、取消、超时、预算和恢复测试。
- 已建立工具契约测试，持续校验 Schema、风险、权限、确认、后台能力和验证信息不能缺失。
- 已完成当前可审计性能指标：任务中心展示 Run 总耗时、终态成功率、平均耗时、模型/工具/审批次数、模型总耗时、平均 TTFB、最终 JSON Prompt 字节、上游 Token usage 覆盖率和失败终态分布；未返回 usage 的请求不补零，Prompt 正文不重复落库。
- 对低能力模型做回归，减少多阶段 LLM 调用和超长工具提示词。
- 已完成当前故障注入基线：用户取消、模型/工具/整次 Run 超时、网络响应中断、Workflow 重复回调、执行/验证中进程终止，以及审批期间和工具执行期间 Android 权限撤销均有确定性测试；`tool.verify` 落库后和验证 Step 完成后两个终止点均确认恢复不重复工具或验证事实；真机外部 `pm revoke` 同时确认系统会直接终止应用进程。启动恢复候选快照还覆盖快照期间新 Worker 等待，以及旧链收敛、当前进程链保持并完成且不复制 Run。停止故障注入覆盖 WorkManager 与即时 fallback 同时失败、当前进程所有权仍登记、迟到成功结算和周期下一实例门禁，均以持久化 `STOP_REQUESTED` 收敛。Redmi 另有强制 Doze、trim-memory 和无压力对照，但这些受控命令不冒充自然 LMK 或连接失败因果证据。
- 每个涉及 Android 系统能力的里程碑都必须在真机验证，不以单元测试替代。

## 优先级清单

| 优先级 | 工作项 | 当前状态 | 原因 |
|---|---|---|---|
| P0 | 请求取消、结构化 Responses 输入、Provider Adapter | 已完成，包括用户 Image/Document、函数调用与结果 typed Items、可选 Reasoning summary | 后续 Agent 循环的基础协议 |
| P0 | Room、Repository、迁移测试和导出 | Room/Repository、Schema 导出、v4→v27、event metadata、Memory/Knowledge FTS、Tool Ledger、Agent Profile、MessagePart、知识引用审计和用户 ZIP 备份/恢复已完成 | 保证升级和本地数据可恢复 |
| P0 | AgentRun 状态机、事件日志、取消与恢复 | 最小状态机、事件、取消、安全重新运行、进程终止、运行中撤权、多步骤审批等待恢复、两个白名单写工具受限验证，以及全部工具 `PASSED` 后的本地收尾恢复已完成；提交状态未知与验证事实不完整的执行栈仍 fail-closed | 决定任务是否可靠、可观察 |
| P0 | Tool Registry、Schema、风险、确认和验证 | 已完成完整类型/约束/枚举、业务校验器、风险/确认、Android 权限、前后台来源门禁、超时、回读验证策略和重复名称启动校验 | 决定执行边界和安全性 |
| P1 | 应用内低风险工具和任务时间线 UI | 第一批工具、对话时间线、任务中心、完整工具结果、失败重试及 Run/历史运行指标已完成 | 已形成第一条端到端 Agent 链路 |
| P1 | 长期记忆管理与 FTS 检索 | 管理 UI、FTS、中文兜底、来源审计、候选确认、敏感过滤、去重/冲突、跨进程删除撤销、引用 ID 审计、单次召回关闭、过期策略和时间衰减已完成 | 形成个人化和跨会话连续性 |
| P1 | Skill 按需加载 | 内置与本地声明式 Skill、版本化 JSON、严格导入校验、Room Catalog、规则选择、工具白名单、启停/删除管理和 Run 审计已完成 | 控制 Prompt 和工具面增长 |
| P1 | Agent Profile v1 | 多 Profile 管理、固定 Provider/模型/协议、角色提示、上下文策略、工具/Skill 白名单、记忆硬边界和 Run 快照恢复已完成 | 把 Agent 身份与普通聊天配置分离 |
| P1 | 结构化消息 parts | Text/Reasoning/Image/Document/Tool 持久化、旧 text 回填、供应商摘要折叠展示、可信 Tool 投影、用户附件选择/预览/请求/备份和 Compose 展示已完成 | 让聊天内容、用户附件、供应商摘要与工具执行事实进入同一可恢复消息模型 |
| P1 | Workflow Ledger 与后台调度 | 多步骤定义/编辑、前后台顺序执行、步骤快照、新 Run 重试、一次性与 Daily/Weekly WorkManager、SAFE/blocked/通知和规则替换/停用已完成；进程内 Worker 所有权、启动恢复隔离、运行中可见停止、`STOP_REQUESTED` 持久化栅栏和 Workflow/Task 原子结算已完成，执行中断仍按 fail-closed 收敛；已有 62.2 秒八步成功与 32.6 秒停止样本，仍缺自然 LMK，Foreground Service 暂无引入依据 | 支持持续任务且可追溯 |
| P2 | Accessibility 设备工具 | 观察、有限动作、审批、操作后验证和少量指定 App Redmi E2E 已完成；Workflow/后台与任意 App 继续关闭 | 扩展到真正移动端执行，风险较高 |
| P2 | 附件、视觉、语音和 RAG | 单张用户 Image、PDF/UTF-8 Document 与 DOCX/PPTX/XLSX 直传，以及 RAG 数据、管理 UI、`knowledge.search`、引用审计、模型上下文投影和答案引用 UI 已完成；Embedding、`/agent` 附件和语音未完成 | 提升输入输出能力 |
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

基于 `v0.1.10` 当前状态，下一批实际代码任务建议拆为：

1. 已完成：`WAITING_APPROVAL` 可在任意已验证前缀后恢复原 Run。恢复要求唯一待审批请求与链尾 ToolCall 完全匹配，前序结果全部成功并 `PASSED`，步骤、Ledger 与 typed event 一致；Runtime 重建可信前缀、工具调用预算和循环指纹，不重放前序工具。磁盘 Room 关闭重开与 Redmi 124 条完整 instrumentation 已通过。
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
12. 已完成执行/验证中进程终止和 Android 权限运行中撤销故障注入：审批后执行前和工具返回后验证前都会复检权限；进程重建默认把旧 Run 与活动 Step 一致取消，只有显式白名单工具的完整历史证据可以进入受限恢复。
13. 已完成：建立持久化 `ToolExecutionReceipt`、执行时 `ToolReplaySafety` 声明快照和纯证据判定 module；回执绑定 ToolCall，错配时 Runtime fail-closed，旧事件默认不可重放，当前定义升级不能放宽历史证据，任务中心不显示原始幂等键。
14. 已完成：`notes.create` 使用 ToolCall ID 作为可审计的存储层唯一幂等键，同键同载荷在数据库重开后仍返回同一 operation ID，同键载荷漂移被拒绝；工具已声明 `IDEMPOTENT_BY_KEY`。Room v17 迁移保留旧笔记并把其幂等键留空，Pixel_9 与 Redmi 各 42 条 instrumentation 通过。
15. 已完成：仅针对具有完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据的 `notes.create` 开放“验证阶段恢复”。从持久化 ToolResult 唯一还原 ToolCall，按 operation ID 回读原笔记，补齐 `tool.verify` 和本地 `recovery.summarize`；多工具 Run 会从历史验证事件重建成功前缀，Workflow 启动对账会保留候选直到当前步骤输出落库。确定性进程中断、Room 重建和真实 Registry 不重复写入均已覆盖。旧模型协程、其他工具执行栈和 Workflow 后续步骤仍不恢复。
16. 已完成：`memory.remember` 使用独立 Room operation ledger，以 ToolCall ID 主键绑定原始载荷哈希和 memory ID；数据库重开后同键同载荷复用原 operation，载荷漂移被拒绝。工具已声明 `IDEMPOTENT_BY_KEY`。
17. 已完成：Room v19 为记忆 operation 增加提交结果业务快照哈希，Registry 从持久化 Run Context 重建原请求并开放 `verifyCommittedEffect()`。未修改、启用、未过期的记忆验证成功；内容、标签、类型、来源或置信度编辑返回 `MEMORY_CHANGED`，禁用返回 `MEMORY_DISABLED`，过期返回 `MEMORY_EXPIRED`，删除返回 `MEMORY_NOT_FOUND`，删除后按原快照撤销恢复可再次成功。置顶、引用时间和未来过期时间不影响验证；v18 历史 operation 因缺少结果快照保持 `EVIDENCE_INCOMPLETE`。冷启动恢复不再次调用 `remember()`，原 operation ID 和回执保持不变。
18. 已完成：`memory.remember` 的八类只读恢复失败通过 `run.recovery_failed` typed event 保存稳定错误码、原因和建议动作；任务中心详情顶部直接显示恢复处理状态带，事件区保留完整字段。所有建议都要求创建新 Run，旧 Run 保持 `FAILED`。生产 Registry 当前只有 `notes.create` 与 `memory.remember` 两个写工具，不为套用模式虚构第三个写工具；通用执行栈、旧模型协程和 Workflow 后续步骤继续 fail-closed。
19. 已完成：Room v20 新增 `agent_tool_calls / agent_tool_results`，`appendEvent()` 在同一事务内按 typed metadata 双写参数、proposed/validated 锚点、结果、显式错误、耗时、Executor/最终验证、记忆引用、重放声明和执行回执。ToolCall 身份或参数漂移整笔回滚；Repository 重建后可按 Run 查询。v19 旧 Run 保留 event-only，不补造关联，验证阶段恢复仍可追加事件；恢复策略未切换到新表。
20. 已完成：任务中心对 v20 新 Run 使用 Tool Ledger-first 明细，Repository 批量加载调用/结果，UI 以调用为单位展示 proposed→validated→result→verified 状态；没有 Ledger 但存在 typed 工具事件的旧 Run 自动回退。缺少 ToolCall ID 的旧结果/验证显示“关联未知”，不伪造调用关联；账本与事件的身份、字段、锚点或孤立记录异常显示一致性告警。`AgentRunResumePolicy`、重试和指标继续读取 RunEvent，恢复证据切换留待独立阶段。
21. 已完成：`AgentRunRecoveryEvidencePolicy` 将 `notes.create / memory.remember` 的受限验证恢复切换为 Ledger-first。v20 非空账本要求每个调用恰好一个结果，按 proposed 锚点重建顺序，并核对 proposed→validated→result→verified 的身份、字段、派生错误、时间和事件顺序；部分账本、重复身份、额外事件或双源漂移均 fail-closed。账本完全为空的旧 Run 才回退 typed event，缺少 ToolCall ID 的历史验证保持原结果顺序。多步骤只重建已验证前缀并恢复最后一个尚无验证终态的已提交结果；白名单、旧模型协程、通用执行栈和 Workflow 后续步骤边界不变。
22. 已完成：失败 Run 的重试副作用判断改为 Ledger-first，并复用 `AgentToolLedgerConsistencyPolicy` 的完整链路检查。非 SAFE 调用只要结果成功，或回执为 `COMMITTED / UNKNOWN`，就要求二次确认；异常账本同样 fail-safe，明确 `NOT_COMMITTED` 的失败结果和仅 validated 尚未执行的调用不额外抬高门禁。账本全空的旧 Run 保留 typed event 回退，旧 Run 本身仍不修改。Run 质量和模型遥测继续使用 Step/LLM typed event，因为 Tool Ledger 没有等价耗时、TTFB、Prompt 与 usage 字段，不为追求形式统一而改变统计口径。
23. 已完成：Agent Profile v1 使用 Room v21 `agent_profiles` 保存名称、标识、Provider/模型、API 模式、系统提示词、当前会话上下文策略、工具/Skill 白名单和记忆开关。新 Run 写入唯一 `agent.profile.selected` 快照；Profile Registry 是工具执行硬边界，Skill 只能继续缩小。审批恢复和已提交结果恢复固定原 Run 快照，重复、损坏或越权审计 fail-closed；前台/后台 Workflow 单次执行固定同一 Profile。设置页已完成新增、编辑、选择、删除和 Provider 绑定保护，真实 `Time Agent + gpt-5.5 + Responses + app.current_time` 已在 Redmi 完成端到端验收。
24. 已完成：Room v22 新增 `message_parts`，Text/Tool 使用稳定 ID 与 sequence。v21→v22 只回填旧 Text；新 Agent 结果依据 `AGENT_RESULT + VerifiedAgentContext` 生成 Tool，普通聊天无法伪造。`MessageRepository` 统一前后台原子写入，前台快照增量 upsert 且只按显式 ID 删除会话，Compose 同气泡展示 Text 与 Tool 证据。242 条 JVM、仅 Redmi 执行的 73 条 instrumentation 和真实 `gpt-5.5 + app.current_time` Run 均通过；交错写测试确认旧前台快照不会删除后台追加消息或新建会话，数据库确认最新消息为 sequence 0 Text + sequence 1 Tool。
25. 已完成：Room v23 为 Reasoning part 增加来源、供应商 item ID 和 summary index。普通对话仅在 Responses 模式且用户显式开启时发送 `reasoning.summary=auto`；非流式和 SSE 流式只接收供应商 `summary_text`，按来源身份去重并固定在 Text 前，Compose 默认折叠并标注“供应商提供”。原始 `reasoning_text`、Chat Completions 非标准 `reasoning_content` 和 Agent 结果中的 Reasoning 均不能进入正文、parts 或 `VerifiedAgentContext`；debug 响应与 SSE 日志也做结构化脱敏，无法解析的可疑内容失败关闭。256 条 JVM、仅 Redmi 执行的 77 条 instrumentation 均通过；`gpt-5.5` 非流式和流式真实请求都返回 Reasoning summary，Room 回查均为 sequence 0 Reasoning + sequence 1 Text，应用最终保持 Redmi 前台且 crash buffer 为空。
26. 已完成：Room v24 为 Image part 增加 MIME、文件名、BLOB 和 detail。系统选择器单次接收 PNG/JPEG/WEBP，读取上限 8 MB，并核对声明大小、MIME、文件签名和可解码性；进入消息后不再依赖 URI。Responses 把近期 USER Image 映射为 `input_image` Data URL，Chat Completions 与 `/agent` 在发送前明确拒绝；Agent 信任策略只允许 USER 保留 Image，不能提升为 Tool 或 `VerifiedAgentContext`。Compose 支持待发送缩略图、移除和历史图片，debug 日志脱敏图片 Base64、`file_data`、生成图片结果与 `encrypted_content`。图片 BLOB 按当前会话加载，轻量快照保留未加载 BLOB；发送前等待 Room 事务，切换会话原子更新，显式删除过滤阻止陈旧快照复活。267 条 JVM、仅 Redmi 执行的 85 条 instrumentation 均通过；真实 `gpt-5.5` 图片轮次返回 `IMAGE_OK`，Room v24 回读确认 PNG BLOB 持久化。
27. 已完成：Room v25 增加 Document part 的提取文本、PDF 页数和 detail，并复用附件 MIME、文件名与 BLOB。Document v1 单次接收 PDF、TXT、Markdown、JSON、CSV，最大 8 MB；PDF 签名与扩展名在领域策略交叉校验，DocumentsProvider 错报 MIME 也不能绕过 `PdfRenderer` 和最多 50 页预算，文本严格使用 UTF-8 并限制 200,000 字符。原始文件与受限提取文本同事务保存，附件 BLOB 按当前会话加载，轻量快照保留未加载 Image/Document。Responses 映射为 `input_file` Data URL，PDF 使用 `detail=auto`；Chat Completions 与 `/agent` 明确拒绝，USER-only 信任边界不变。Compose 附件菜单、待发送元数据/移除和历史 Document 展示已完成。281 条 JVM、仅 Redmi 执行的 92 条 instrumentation 均通过；真实 `gpt-5.5` Markdown 轮次在 4.33 秒返回 `DOC_STAGE27_OK`，Room v25 回读确认 67 字节 BLOB 与 67 字符提取文本持久化。
28. 已完成：Document part 在不升级 Room 的前提下扩展 DOCX、PPTX、XLSX。`OpenXmlDocumentPolicy` 解析 ZIP 中央目录并逐条核对 local header、文件名、加密位、磁盘号、ZIP64 extra 与实际数据范围，再以固定缓冲区流式核对条目集合、CRC 和真实展开量；加密、分卷、ZIP64、超过 4,096 条目、声明或实际展开总量超过 64 MB、扩展名/MIME/结构不一致均在进入消息前拒绝。系统选择器、Room BLOB、轻量快照、Responses `input_file`、Compose 元数据和 USER-only 信任边界继续复用第 27 阶段契约。284 条 JVM、仅 Redmi 执行的 93 条正式 instrumentation 均通过；一次性真机 E2E 使用设备现有 `gpt-5.5 + Responses` 在 4800 ms 返回 `RICH_DOC_STAGE28_OK`，日志确认 DOCX `file_data`、Authorization 与加密推理内容均脱敏。
29. 已完成：Room v26 新增知识文档、chunks、FTS4 和检索审计。严格 UTF-8 导入规范换行、拒绝空白/NUL，并按规范全文计算 SHA-256；确定性分块优先段落边界、保留有限重叠和精确 offset，不切断 UTF-16 代理对。替换在同一事务递增 revision 并全量更新 chunks/FTS，失败注入确认整笔回滚；禁用/删除立即退出检索，旧 chunk ID 随 revision 失效。该阶段 291 条 JVM、仅 Redmi 执行的 98 条 instrumentation 均通过；Redmi 主库升级为 v26，原 Provider 保留。该阶段当时尚未接入管理 UI、Agent 工具和模型引用注入，后续第 30、31 阶段已经补齐。
30. 已完成：设置页新增知识库管理 UI，使用 SAF 有界读取、轻量摘要 projection 和最多 4,000 个 UTF-16 单元且不切断代理对的详情预览，支持导入、列表、详情、启停、替换、删除和带 retrieval ID/chunk offset 的检索预览。独立 ViewModel 串行化变更、取消旧详情/刷新/检索、变更开始即隐藏失效详情，并区分存储提交失败与提交后的刷新失败。291 条 JVM、仅 Redmi 执行的 106 条 instrumentation 均通过；真实 UI 验证 revision 1→2、停用/删除 0 命中、旧词失效、新词命中和 r1/r2 审计引用分离，最终主库知识表清空且 Provider 保留。

31. 已完成：新增 SAFE、后台可用的 `knowledge.search` 和内置 `local-knowledge` Skill。`query` 必填 1 至 200 字符，`limit` 默认 3、最大 5；ToolResult、RunEvent、独立 Tool Ledger、VerifiedAgentContext、MessagePart、规划历史、Workflow 输出和任务中心统一保存稳定 retrieval/document/revision/chunk/offset 引用。Room v27 为 ToolResult/MessagePart 增加默认空引用列，不猜造旧证据。失效引用会让整条历史知识消息、可能污染的旧摘要和 Workflow 前序正文退出新模型请求，历史审计不回写；旧 Profile 不自动扩权，无 Profile 审计的历史 Run 固定在知识工具上线前的工具集合。309 条 JVM、仅 Redmi 执行的 113 条 instrumentation 通过；五份真实项目长期文档 corpus 的多词重排查询、top-1 和负例门禁全部通过。真实 `Time Agent + gpt-5.5` 已选择 `knowledge.search`，Run、Ledger、retrieval 和 MessagePart 引用一致。

32. 已完成：Agent 回复新增独立、默认折叠的答案引用区域，只从可信 `MessagePart.Tool`/`VerifiedAgentContext` 投影，不解析模型自由文本。展开后展示文档名、revision、chunk 和半开 offset 区间；Room 使用文档摘要与引用 chunk 的 projection 判定“当前有效 / 历史版本 / 当前不可用”，按最多 900 个 SQLite 参数分批核验，取消旧 Job 不回写失败状态，停用状态优先于历史 revision。文档仍存在时可跳转知识库详情，删除后关闭跳转。320 条 JVM、仅 Redmi 执行的 118 条 instrumentation 均通过；真实 `MainActivity` E2E 覆盖当前引用跳转、替换后的历史标记、跳转当前 revision，以及删除后的不可用状态和清理。Embedding 继续后置。

33. 已完成：设备 Agent 只读观察层。新增默认关闭的独立开关、系统 Accessibility 入口、四态健康检查、只读预览、`device.snapshot` 和内置 `device-observation` Skill。快照最多 200 个节点/4000 字符，文本不切断 UTF-16 代理对；可操作非敏感节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、路径和指纹，任一失败或页面变化立即撤销。敏感字段脱敏，高敏窗口与隐私应用整窗拒绝；Service 不具备手势或截图能力。工具仅在前台直接 `/agent` 暴露，Workflow、后台和关闭状态双层拒绝，旧 Profile/Skill 不自动扩权。337 条 JVM、仅 Redmi 执行的 122 条 instrumentation 均通过；真实 Service 在 instrumentation 外验证主界面 `nodes=27 / refs=8`、敏感探针 `redacted=2 / refs=1`、支付探针 `SENSITIVE_WINDOW`，最终独立开关关闭、系统服务绑定、主界面前台且 crash buffer 为空。

34. 已完成：设备 Agent 有限动作层。新增 `device.open_app / back / home / tap_ref / type_text / swipe` 和 `device-control` Skill；打开应用、点击和输入要求审批，返回、主页和节点滚动为 SAFE。应用白名单只含小灵、系统计算器、时钟和系统设置；输入在审计前拒绝敏感值。节点动作再次核对 snapshot/ref/generation/path/fingerprint，动作后重新 capture 并按包名、桌面、回读文本或 generation 变化验证；首次启动权限页的瞬时空窗口通过只针对窗口过渡的 6×100 ms 有界重试收敛。348 条 JVM、仅 Redmi 执行的 123 条 instrumentation 均通过。Redmi 真实动作覆盖计算器打开/点击、设置滚动/搜索/输入、敏感输入拒绝、返回/主页和时钟启动；真实 `gpt-5.5 + Responses` Run `run-13bcfa28-346f-4a71-b98b-5b44cf28bd92` 完成模型规划、`device.open_app` 审批、动作后验证、Tool Ledger 和最终总结，状态 `COMPLETED`、审批 `APPROVED`、Executor 验证 `PASSED`。首批验收不扩展到任意 App。

35. 已完成：多步骤审批等待恢复。`AgentRunResumePolicy` 只接受一个 `PENDING` Approval 与最后一个已校验、无 ToolResult 的 ToolCall 完全一致，所有前序调用均有成功结果和 `PASSED` 验证，执行/验证/审批 Step 与 Ledger/Event 严格对应。恢复后重建 `completedTools`、已执行调用数和调用指纹，批准当前工具后继续原 Run 的后续规划；前序工具不会重放，预算与重复调用检测不会因重启清零。354 条 JVM 与仅 Redmi 执行的 124 条 instrumentation 全部通过；新增磁盘 Room 测试真实关闭并重开数据库，确认原 Run ID、第一步已验证前缀、第二次审批和审批 Step 保持。

36. 已完成：所有工具结果与验证事实已持久化后的原 Run 收尾恢复。`AgentRunResumePolicy` 新增 `VERIFIED_TOOL_COMPLETION`，只接受 `VERIFYING`、无待审批、每个结果成功且每个验证均为 `PASSED`、执行/验证 Step 一一对应、最后验证 Step 为 `RUNNING/COMPLETED`、其后没有新 Step，并要求 Ledger/Event/Profile 一致。恢复重建全部可信工具、调用数和指纹；若需要只补齐最后验证 Step，再生成本地可信总结，不调用 Executor/LLM、不追加第二条 `tool.verify`、不续跑 Workflow。两个确定性终止点、磁盘 Room 重开、358 条 JVM 和仅 Redmi 执行的 125 条 instrumentation 全部通过。

37. 已完成：`AgentExecutionBudget` 改用可注入单调时钟，规划、工具和总结段共享同一累计 Run 预算，工具 duration 使用同一时钟。新 Run 和每个成功执行段写入 `run.execution_budget.updated` typed 快照；审批及受限恢复继承原 Run 的 total/consumed，旧 Run 先建立零值兼容起点，缺 metadata、越界、总额漂移、累计回退，或最后 ToolResult 晚于最后预算快照时拒绝原地恢复。Step 上限小于剩余预算时报告 Step timeout，二者相等或剩余更少时报告 Run timeout；审批等待不计入，调用方外部超时仍按取消收敛。多段累计、精确边界、恢复剩余 20ms、工具结果/预算崩溃窗口、旧 Run 起点、codec/UI 和损坏证据测试均已覆盖；374 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

38. 已完成：重试前副作用证据分类。`AgentTaskRetryPolicy.assessEvidence()` 统一读取独立 Tool Ledger、旧 typed RunEvent、Receipt 状态和执行/验证中断，输出 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE`。任务中心卡片和确认弹窗展示稳定分类码、原因和建议；确认提交前重新读取当前 Run，状态不可重试时关闭弹窗，证据码变化时更新弹窗并停止本次旧确认，只有分类稳定后才继续。该阶段没有扩大原地恢复能力，仍禁止恢复旧模型协程、调用旧 Executor 或把 UNKNOWN 当作未提交；所有重试继续创建关联新 Run，旧 Run 保持不变。381 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

39. 已完成：Workflow 步骤落库后的进程终止与启动对账。`ScheduledWorkflowOrchestrator` 在 `completeWorkflowStep()` 成功返回后、下一步骤启动前提供专用故障注入 seam；模拟进程终止直接退出，不触发普通失败结算、通知或 `Result.retry`。JVM 测试确认第一步只执行一次、输出已保存、第二步仍为 `PENDING` 且没有结算；Room 测试再确认启动 `reconcileInterruptedRuns()` 会保留完成前缀并关闭旧 Run，`retryRun()` 创建关联新 Run，将前缀标为 `SKIPPED` 并设置 `reusedFromStepId`，只从首个未完成步骤继续。生产保持 no-op 注入，不自动恢复旧 Workflow 或复制 Agent Run。382 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 13 条定向 Workflow instrumentation 全部通过。

40. 已完成：通用重试证据可见性。任务中心卡片现在直接显示 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE` 的稳定分类、原因和建议动作；确认弹窗与卡片仍复用同一证据评估，确认提交前继续校验证据码。该切片只改善恢复处置的可解释性，不改变 `COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED` 和 `EVIDENCE_INCOMPLETE` 的确认门禁，不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。383 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

41. 已完成：启动恢复证据快照。不可原地恢复的活动 Run 在步骤/审批收敛前按原始状态计算重试证据，并写入 typed `run.recovered.retryEvidenceCode`；执行/验证中无结果固定为 `COMMIT_UNKNOWN`，纯思考中断且无副作用为 `NOT_COMMITTED`，Ledger 漂移为 `EVIDENCE_INCOMPLETE`。任务中心和确认前仍重新计算当前证据；带快照的 Run 使用快照还原收敛前中断边界，避免把启动清理产生的 `PENDING -> CANCELLED` 误判成副作用，当前 Ledger 真正漂移时仍升级为 `EVIDENCE_INCOMPLETE`。旧 Recovery 事件缺字段继续兼容，可原地恢复候选不写取消证据；该阶段不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。388 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

42. 已完成：Worker 冷启动重入收敛。`ScheduledWorkflowReentryCoordinator` 只拦截仍为 `RUNNING` 的 ScheduledTask，沿当前 Task→WorkflowRun→AgentRun 关联链按 ID 定向关闭旧执行栈，再按 Agent→Workflow→Task 顺序完成对账；普通 `SCHEDULED` 任务不改变 claim/执行路径，不使用 `Result.retry`，不恢复旧模型协程或 Workflow 后续步骤。无关前台 Agent 保持不变，周期下一实例仍等待旧任务进入终态后再物化。391 条 JVM、Lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 126 条 instrumentation 全部通过；该阶段当时只完成确定性 Room/协调器重入验收，真实系统强杀 Worker 的耗时与回收位置留待第 43 阶段。

第 42 阶段当时的下一阶段边界：继续在 Redmi 验收真实较长 Worker 任务的系统回收位置、WorkRequest 重入和持久化耗时；提交状态未知或验证事实不完整仍只安全收敛并引导关联新 Run。旧模型协程和 Workflow 后续步骤仍不原地恢复；设备工具继续禁止进入 Workflow 或后台自动化。

43. 已完成：Redmi 真实 Worker 冷启动重入。临时 instrumentation 在 Redmi `wsvwypiz7xwslvl7` 创建 7 步 SAFE Workflow 并保持目标进程存活；`WorkRequest=0d9aa2a5-ff1b-4a04-ad74-5d3c7bdf76db` 于 `06:05:03` 启动，`06:05:05` 首个 Agent Run 处于 `THINKING`。`am kill` 因 instrumentation 前台身份未终止 PID `25755`，立即使用 `run-as ... kill -9` fallback；约 `0.2s` 后新 PID `26092` 冷启动同一 WorkRequest。重入只收敛关联的 Agent/Workflow/ScheduledTask，后 6 步未启动，关联 Agent Run 数量仍为 1，工具调用/结果为 0，实际耗时 `3360ms`。该受控强杀不等同 Android 自主回收，也不扩大为通用原地恢复。

44. 已完成：任务中心“需确认”队列。新增 `AgentTaskFilterPolicy` 和 `NEEDS_CONFIRMATION` 筛选，只聚合已结束、可重试且必须确认副作用证据的 Run；提交未知、已提交未验证/已验证和证据不完整沿用现有卡片说明与确认弹窗。确认提交前重新核对证据码，稳定后只创建关联新 Run，旧 Run 保持不变。新增 3 条 JVM 筛选策略测试和 1 条 Redmi Compose instrumentation，完整门禁为 394 条 JVM、127 条 Redmi instrumentation。

45. 已完成：不可原地恢复 Run 的结构化处置。`AgentRunResumePolicy` 的 `RESTART_REQUIRED` 现在由构造约束强制携带稳定 `AgentRunRestartDispositionCode`，覆盖 Run 状态、Profile、预算、审批边界、恢复证据、步骤对应、工具定义和已提交副作用证明等类别；每类同时给出具体策略原因、证据边界和只创建关联新 Run 的建议。`closeInterruptedRuns()` 在改变 Step/Approval 前完成评估，并把恢复类型、处置码、策略原因、边界、建议和重试证据一起写入 typed `run.recovered`。任务卡、详情顶部与事件区读取同一历史快照，旧事件不补造，未知未来枚举 fail-closed。完整门禁为 395 条 JVM、128 条仅 Redmi instrumentation。

46. 已完成：Redmi 长任务与系统策略证据。8 步 SAFE Workflow 的首步成功，第二步重复 `app.current_time` 被循环保护安全终止，Worker 总耗时约 28.5 秒；强制 Doze 在 20 秒观察窗内保持同一任务 `SCHEDULED`，退出后只创建一个 Workflow/Agent Run。退出 Doze 与 `RUNNING_CRITICAL` trim-memory 样本均快速出现 `connection closed`，但无压力对照暴露的是前台启动恢复与新 Worker 的状态竞态，因此不建立因果。竞态曾使 ScheduledTask/Workflow 为 `CANCELLED`、AgentRun 被迟到协程覆盖为 `COMPLETED`；现已用 DAO 原子非终态更新冻结 AgentRun 终态，并在 Redmi 增加回归。`force-idle`、`kill -9` 和 trim-memory 均不等于 Android 自主 LMK。

47. 已完成：当前进程 Worker 所有权与启动恢复隔离。`ScheduledWorkflowWorker` 在任何 Repository 构造、重入对账和 claim 前以引用计数注册 Task；`StartupRecoveryCoordinator` 在同一互斥边界冻结活动 AgentRun、WorkflowRun 和 RUNNING ScheduledTask，快照期间新 Worker 等待，已注册 Task 对应的 Workflow/Agent 链从旧候选中排除。ViewModel 后续审批恢复、受限验证恢复、关闭旧 Agent、Workflow 对账和 ScheduledTask 对账全部只消费该候选快照。纯 Kotlin 测试覆盖快照后的 Worker 不进入旧候选；Redmi Room 测试确认旧链收敛，当前链保持活动并可完成，Agent Run 数不增加。实现不使用墙上时间，不新增 owner token/Schema，不恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。完整门禁为 397 条 JVM、130 条仅 Redmi instrumentation。

48. 已完成：后台 `RUNNING` Workflow 可见停止。停止入口先取消目标 WorkRequest 并等待 Worker 正常写入终态，超出有界窗口或系统取消异常时按 Task→Workflow→Agent 持久化链兜底；Agent 尚未关联时仍关闭 Task/Workflow，`SCHEDULED→RUNNING` 抢占会升级为运行中停止。取消只影响目标链且幂等；Run 终态后 Step、Approval、Event 和 Tool Ledger 一并冻结，迟到 HTTP、模型和审批结果不能覆盖或污染 `CANCELLED`。Redmi 真实停止样本约 32.6 秒；另一个三步 SAFE Workflow 依次执行当前时间、会话列表和笔记列表，约 21.8 秒全部完成。LMK probe 显示报告能力可用、历史退出 11 条、`REASON_LOW_MEMORY=0`，不构成自主 LMK 样本。完整门禁为 402 条 JVM、134 条仅 Redmi instrumentation。

49. 已完成：Redmi 正式 8 步 SAFE 后台 Workflow 全成功样本。Task `scheduled-task-b7cae61a-e311-42bc-98a7-f8d601a9be59` 只关联一个 WorkRequest 和一个 Workflow Run，8 个 Agent Run 顺序执行当前时间、会话列表/检索和笔记列表/检索，全部 `COMPLETED` 且 ToolResult 为 `success=true / PASSED`，总耗时约 62.2 秒。先行样本约 49 秒时因模型未调用第 6 步 `memory.search` 安全失败，后两步取消且没有复制 Run。最新 LMK probe 为 `supported=true / exits=6 / lowMemory=0`，6 条均是本轮 instrumentation `FORCE STOP`；生产代码未改变，完整门禁继续为 402 条 JVM、134 条仅 Redmi instrumentation。

50. 已完成：停止异常后的持久化重对账。Workflow 仍活动时，运行中停止先把 ScheduledTask 原子写为 `STOP_REQUESTED`，再请求 WorkManager 取消；系统取消与即时 fallback 同时失败时仍保留停止意图。若 Workflow 在停止事务前已经终态，则停止请求不改写历史终态，直接把半结算 Task 对账到该状态。Worker 重入、启动恢复和停止兜底都识别中间态，当前进程所有权只排除真正 `RUNNING` 的链；Agent Run 尚未关联时，Workflow 对账也会先读取唯一关联 Task 的停止栅栏，将 Run 和未完成步骤收敛为 `CANCELLED`，不会误记为关联缺失失败。最终 Workflow/Task 在同一 Room transaction 重新读取栅栏与既有 Workflow 终态并原子收敛；步骤完成与成功消息也共享同一停止栅栏，迟到成功不能写成 `COMPLETED` 或追加到会话，周期下一实例不会在旧任务终态前物化。该状态复用既有 TEXT 列，Room v27 Schema 不变；完整门禁为 404 条 JVM、140 条仅 Redmi instrumentation。

下一阶段继续寻找 Android 自主 LMK，并完善提交未知或验证事实不完整时的通用恢复证据，但不尝试恢复无法证明的旧执行栈。Daily/Weekly 继续使用非精确定时语义并记录计划/实际时间。Foreground Service 只提高系统存活概率，不代表旧执行栈可以安全恢复；当前 62.2 秒样本仍不支持引入。设备工具继续禁止进入 Workflow 或后台自动化；精确定时、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。
