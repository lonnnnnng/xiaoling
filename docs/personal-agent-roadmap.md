# 小灵个人 Agent 路线图

## 结论

小灵当前已经具备可靠的模型接入和多会话底座，但还不是可执行任务的个人 Agent。下一阶段最重要的不是立即增加大量手机工具，而是建立一套可取消、可确认、可验证、可恢复的 Agent Runtime，并把数据从聊天消息模型升级为 Run、Step、Tool Call、Approval 和 Memory 等明确实体。

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
- `AgentRun / AgentStep / ApprovalRequest / RunEvent` 初始数据模型，以及 `/agent` 模型规划 + fake tool 演示链路。
- 最小 Agent Runtime 已具备工具调用预算、模型/工具步骤超时、整次 Run 超时、必填参数校验、重复工具调用检测和结构化事件记录。
- 对话区已能显示当前 `/agent` Run 的最小时间线和审批卡片，批准后继续执行，拒绝后写入失败终态；审批请求已具备有效期和决定结果落库。
- 设置页已有最小 Agent 运行记录入口，可查看最近 Run、步骤、审批请求和结构化事件。

### 主要缺口

- 还没有真实 LLM tool call、完整任务中心、进程重建后的运行恢复、失败重试和完整工具结果视图。
- 还没有真实 Tool Registry、完整 JSON Schema、权限策略和可插拔后置验证策略。
- 没有独立长期记忆，当前摘要只服务单个会话上下文。
- 没有 Skill、Workflow、后台调度和执行日志。
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

## 里程碑 0：稳定现有聊天底座

目标：在引入 Agent 前，让现有请求和数据结构具备扩展条件。

### 要做什么

- 给当前请求增加明确的取消能力和“停止生成”按钮。
- Responses API 改为结构化消息输入，保留 system/user/assistant 边界。
- 抽出 `LlmProviderAdapter`，先实现现有 OpenAI-compatible Adapter。
- 抽出 `ChatRepository`、`ProviderRepository` 和 `ConversationRepository`。
- 引入 Room，并为现有 Provider、Conversation、Message 数据编写一次性迁移。
- 增加数据库导出/备份，迁移失败时保留原 SharedPreferences 数据。
- 把 1196 行 ViewModel 中的存储、上下文和网络逻辑迁出，ViewModel 只负责 UI 状态编排。

### 验收标准

- 原有 Provider 和会话升级后不丢失。
- 流式和非流式请求都能立即取消，不再追加内容。
- Chat Completions 与 Responses API 回归测试通过。
- 进程重建后可以正确恢复会话，但不会把未完成请求当成功。

## 里程碑 1：最小可用 Agent Runtime

目标：完成“判断是否需要工具 -> 调用工具 -> 获取结果 -> 继续推理 -> 输出最终答案”的受控闭环。

### 核心数据模型

- `AgentProfile`：名称、system prompt、默认 Provider/模型、启用的 Skill。
- `AgentRun`：目标、来源、状态、开始/结束时间、当前步骤、最终结果。
- `RunEvent`：状态变化、模型决策、工具调用、工具结果、确认、错误。
- `ToolDefinition`：名称、描述、输入 Schema、风险、权限、确认和验证规则。
- `ToolCall` / `ToolResult`：参数、结果、错误、耗时、重试和验证状态。
- `ApprovalRequest`：待确认动作、风险说明、有效期和用户决定。当前已落地最小 Room 表和只读运行记录页，后续还需要接入完整任务中心和进程重建后的恢复策略。

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
- 连续重复同一工具和相同参数时触发循环检测。当前单工具链路已接入检测 seam。
- 模型只能看到当前允许的少量工具，不在每轮注入全部 Tool Schema。
- 工具参数先做 JSON Schema 和业务校验，再进入 Executor。当前先支持必填参数校验，完整类型和业务校验仍待补齐。
- 风险和确认要求取自 `ToolDefinition`，忽略模型自己声明的风险级别。
- Run 可取消；取消后不再接受迟到的流式事件或工具结果。
- 所有状态变化写入 `RunEvent`，UI 显示简洁任务时间线。当前已在对话流里显示最小时间线，并在设置页提供只读运行记录和结构化事件展示；后续需要升级为可操作任务视图。

### 第一批工具

先做可验证、低风险、应用内部工具：

- `app.current_time`
- `app.list_conversations`
- `app.search_conversations`
- `notes.list`
- `notes.search`
- `notes.create`，执行前确认，执行后重新读取验证
- `memory.search`，在里程碑 2 接入真实长期记忆

暂不做任意文件写入、Shell、应用安装、发送消息和系统设置修改。

### 验收标准

- 模型可通过工具完成“查找旧会话”和“创建一条笔记”。
- 用户能看到工具名称、关键参数、执行结果和验证状态。
- 拒绝确认后 Run 正确结束或改走其他方案。
- 工具返回成功但后置读取不一致时，结果标记为“未验证”，不得宣称完成。
- 状态机、循环检测、取消和确认都有确定性测试。

## 里程碑 2：长期记忆

目标：把“会话摘要”与“跨会话个人记忆”分开，让记忆可见、可控、可追溯。

### 记忆类型

- `Preference`：稳定偏好，例如语言、常用格式和习惯。
- `ProfileFact`：用户明确提供的个人信息。
- `Episode`：重要任务和事件结果。
- `Procedure`：经过验证的重复操作方法。

### 实现顺序

1. Room 保存结构化 Memory，包含来源会话/Run、原文摘要、类型、置信度、更新时间和启用状态。
2. 提供记忆管理页：搜索、查看来源、编辑、置顶、禁用和删除。
3. 每轮结束后只生成“候选记忆”，由确定性规则过滤；敏感内容默认不自动写入。
4. 第一版检索使用 Room FTS，验证召回质量后再考虑 Embedding 和向量索引。
5. 将检索结果以有限条目注入上下文，记录本轮实际使用了哪些记忆。
6. 增加去重、过期和冲突处理，不直接覆盖旧事实。

### 验收标准

- 用户可以回答“你为什么记住这件事”，并跳转到来源。
- 删除或禁用的记忆不再被检索。
- 同一事实不会无限重复写入。
- 记忆检索失败不影响普通聊天和工具执行。

## 里程碑 3：Skill 与能力按需加载

目标：把可复用任务知识从系统提示词中移出，并避免工具数量增长后 Prompt 膨胀。

### Skill 结构

每个 Skill 至少包含：

- `id`、名称、版本和说明。
- 触发描述和示例任务。
- 依赖的工具列表。
- 所需 Android 权限和风险等级。
- 执行步骤、失败恢复和完成标准。
- 来源、校验状态和是否启用。

### 实现策略

- 内置 Skill 由应用随包提供，使用稳定的 YAML/JSON 元数据加 Markdown 指令。
- 先通过轻量分类器或规则选择 1-3 个 Skill，再只加载其指令和工具。
- Skill 不能直接获得未注册工具，也不能降低工具风险或绕过确认。
- 第一版只允许导入本地文本 Skill，不执行任意代码。
- “从成功任务生成 Skill”放到后期，生成后必须经过用户审核和静态校验。

### 首批 Skill

- 会话检索与总结。
- 笔记整理。
- 每日回顾。
- Provider 健康检查。
- 失败请求诊断。

## 里程碑 4：任务与自动化

目标：让用户保存可重复任务，并能查看每次执行结果。

### 要做什么

- `ScheduledTask`、`Workflow`、`WorkflowRun`、`WorkflowStep` 数据表。
- WorkManager 负责可延迟任务，确需准确时间时再评估 AlarmManager 和精确闹钟权限。
- 执行时才启动 Foreground Service，并展示明确的运行通知和停止入口。
- 每次执行保存 Ledger：计划时间、实际时间、步骤、重试、结果和失败原因。
- 工作流步骤必须幂等；重试前检查上一步是否已经产生结果。
- 后台任务遇到需要用户确认的敏感操作时进入 blocked 状态，不得静默执行。

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

- 建立 Room migration 测试和数据库导出工具。
- 建立脱敏结构化日志，统一 `runId`、`stepId`、`toolCallId`。
- 为 Agent Runtime 提供假的 LLM 和 Tool Executor，做确定性状态机测试。
- 建立工具契约测试：Schema、风险、权限、确认和验证信息不能缺失。
- 增加性能指标：首字时间、总耗时、模型调用次数、工具调用次数、Prompt 大小和失败率。
- 对低能力模型做回归，减少多阶段 LLM 调用和超长工具提示词。
- 为进程终止、网络断开、权限撤销、工具超时和重复回调增加故障注入测试。
- 每个涉及 Android 系统能力的里程碑都必须在真机验证，不以单元测试替代。

## 优先级清单

| 优先级 | 工作项 | 原因 |
|---|---|---|
| P0 | 请求取消、结构化 Responses 输入、Provider Adapter | 后续 Agent 循环的基础协议 |
| P0 | Room 与 Repository 迁移 | SharedPreferences 无法承载 Run/Tool/Memory 关系 |
| P0 | AgentRun 状态机、事件日志和取消 | 决定任务是否可靠、可观察 |
| P0 | Tool Registry、Schema、风险、确认和验证 | 决定执行边界和安全性 |
| P1 | 应用内低风险工具和任务时间线 UI | 形成第一条端到端 Agent 链路 |
| P1 | 长期记忆管理与 FTS 检索 | 形成个人化和跨会话连续性 |
| P1 | Skill 按需加载 | 控制 Prompt 和工具面增长 |
| P1 | Workflow Ledger 与后台调度 | 支持持续任务且可追溯 |
| P2 | Accessibility 设备工具 | 扩展到真正移动端执行，风险较高 |
| P2 | 附件、视觉、语音和 RAG | 提升输入输出能力 |
| P3 | MCP、远程 Channel、多 Agent、本地模型 | 生态价值高，但复杂度和攻击面更大 |

## 明确不照搬的做法

- 不照搬多阶段 Analyze/Reflect/Plan/Review 全部依赖 LLM 的重型流程；先用单循环和确定性状态机。
- 不把所有工具 Schema、数据库结构和 Skill 全量注入每次请求。
- 不允许模型决定工具风险或确认策略。
- 不把“工具返回 success”直接等同于任务完成。
- 不以任意 Shell 作为移动 Agent 的通用工具。
- 不在缺少 Run Ledger、取消和恢复前上线后台自动化。
- 不在缺少权限隔离和工具审核前开放 Skill 市场或 MCP Server 任意接入。

## 建议的下一项开发

从里程碑 0 开始，第一批实际代码任务建议拆为：

1. 继续补 Room migration 测试和数据库导出/备份。
2. 抽出 `LlmProviderAdapter` 和现有 OpenAI-compatible 实现。
3. 将当前只读 Agent 运行记录升级为完整任务中心，支持完整工具结果视图、失败重试和恢复。
4. 把 `RunEvent.message` 中的 JSON 字符串迁移为独立 metadata 字段。
5. 接入 `app.current_time` 与 `notes.create`，跑通确认和后置验证。

完成这六项后，小灵才真正拥有可继续扩展的个人 Agent 骨架。
