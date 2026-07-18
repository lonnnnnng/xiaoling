# `reference-apps` 个人 Agent 实现分析

审计日期：2026-07-16（北京时间）

本文负责保存参考项目分类、源码证据和借鉴判断。正式实施顺序、里程碑和验收标准以 [小灵个人 Agent 路线图](personal-agent-roadmap.md) 为准。

## 1. 结论先行

`reference-apps` 下共识别出 56 个独立 Git 仓库。它们并不都是“个人 Agent”：25 个主要是普通 AI 聊天客户端或 Chat SDK，9 个主要解决离线/本地模型推理，13 个属于个人 Agent 或 Agent 平台，7 个属于设备 Agent/手机自动化，1 个是独立 Agent 框架，另有 1 个是非 Agent 业务样本。

对小灵最重要的结论不是“把所有参考功能都做一遍”，而是按下面的顺序把聊天客户端演进为可控的个人 Agent：

1. **先建立可审计的 Agent 运行时**：结构化消息、工具调用、步骤状态、取消、超时、循环检测、上下文压缩、运行记录。
2. **再建立安全边界**：工具注册表声明风险，默认拒绝副作用操作，按次/本次任务/永久授权分级，执行后必须验证。
3. **再建立个人化能力**：Agent 配置、长期记忆、记忆来源和敏感信息过滤、分享入口、语音输入。
4. **再建立持续工作能力**：任务账本、定时任务、通知、失败/待确认聚合页、断点恢复。
5. **最后扩展设备控制和生态**：优先 Intent/deep link 和显式系统 API，再做可选的 Accessibility；MCP、Skills、远程 Gateway、本地模型、多 Agent 都应后置。

最值得组合借鉴的不是某一个项目，而是：

- `openclaw` 的运行时分层、Session/Task、工具策略和沙箱边界。
- `meow-agent` 的模块注册、风险元数据、确认、执行后验证和持久任务账本。
- `hermes-android` 的移动端 Agent 操作面：WebSocket 事件、分级审批、通知和“Needs you”。
- `rikkahub` 的 Android 原生 Assistant、消息 parts、工具审批与生成循环。
- `Operit` 的 Android 工具生态、WorkManager 工作流和混合记忆检索，但不能照搬其高权限入口和平台体量。
- `X-OmniClaw`/`mobilerun` 的观察、规划、动作、验证闭环以及坐标/快照一致性保护。
- `ZeroAI` 的循环预算、工具观测、敏感信息过滤和调度可靠性；其 Kotlin+Rust/UniFFI 不是小灵现阶段的必要复杂度。
- `OGAM` 的本地模型资源预算和工具路由经验，只适合小灵未来做离线推理时参考。

## 2. 审计范围与证据边界

### 2.1 阅读方式

- 全量盘点 56 个独立仓库的 README、docs 入口、构建文件和核心源码入口。
- 深读真正具有 Agent 闭环的代表项目：`openclaw`、`Operit`、`ZeroAI`、`hermes-android`、`rikkahub`、`X-OmniClaw`、`mobilerun`、`meow-agent`，并结合 `skales`、`OGAM` 的架构和核心模块做横向判断。
- 补充关注 `Agora`、`memex`、`opencyvis-phone`、`vFlow`、`AndroidMCPAgent` 和已归档的 `ZeroClaw-Android`，用于覆盖本地推理、个人记忆、设备执行、工作流底座和反面案例。
- 结论优先依据仓库内 README、设计文档和源码，不以项目宣传语单独判定 Agent 能力。

### 2.2 特殊工作区状态

`reference-apps/X-OmniClaw` 当前有 499 个 staged deletions，但其 `HEAD` 与 `origin/main` 一致。本次没有恢复或修改这些变更；该项目的 README 和源码证据通过 `git show HEAD:<path>`、`git ls-tree -r --name-only HEAD` 从 Git 对象读取。

### 2.3 判定标准

本报告把“个人 Agent”定义为至少具备以下能力中的大部分，而不是仅仅支持 system prompt 或 function calling：

| 维度 | 判断问题 |
|---|---|
| 目标与规划 | 能否把用户目标拆成步骤或子目标？ |
| 工具执行 | 能否通过受控工具产生真实外部效果？ |
| 闭环验证 | 动作后是否重新观察或校验结果？ |
| 运行控制 | 是否有取消、超时、步数预算、循环检测和错误恢复？ |
| 权限边界 | 风险是否由代码定义，副作用是否需要用户确认？ |
| 任务连续性 | 是否能持久化任务状态、后台运行、定时触发或断点恢复？ |
| 个人化 | 是否有可管理、可删除、可追溯的长期记忆和 Agent 配置？ |
| 可观测性 | 用户能否看到步骤、工具、结果、失败原因和资源消耗？ |

## 3. 56 个参考仓库分类

分类以主要产品形态为准；部分项目具有交叉能力。

| 主分类 | 数量 | 项目 |
|---|---:|---|
| 普通 AI 聊天客户端 / Chat SDK | 25 | `AetherisAI`、`AndroidGPT`、`ChatGPT-Android-App`、`ChatGPT-basic-android-client`、`ChibiChat`、`Librechat-Mobile`、`MaterialChat`、`OllamaMobile`、`OpenAI-Client-Android`、`Taiga`、`aiyo`、`android-ai-chat-sdk`、`arcade_ai`、`chatAir`、`chatgpt-android`、`chatgpt_android`、`compose-chatgpt-kotlin-android-chatbot`、`conduit`、`eChat`、`gpt_mobile`、`kelivo`、`ollama-app`、`quock`、`reins`、`sample-mobile-ai-assistant` |
| 离线 / 本地模型客户端 | 9 | `ChatterUI`、`OfflineLLM`、`Ollama-CCP-Android`、`OllamaTalk`、`OllamaTest`、`SmolChat-Android`、`maid`、`nexa-android`、`ollama-android` |
| 个人 Agent / Agent 平台 | 13 | `Agora`、`ClaraVerse`、`OGAM`、`Operit`、`ZeroAI`、`gemini-android-app`、`hermes-android`、`memex`、`meow-agent`、`openclaw`、`pocketpal-ai`、`rikkahub`、`skales` |
| 设备 Agent / 手机自动化 | 7 | `AndroidMCPAgent`、`X-OmniClaw`、`ZeroClaw-Android`、`bizclaw`、`gpt-assistant-android`、`opencyvis-phone`、`vFlow` |
| Agent 框架 | 1 | `mobilerun` |
| 非 Agent 业务样本 | 1 | `youshu` |

需要避免的误判：

- `Librechat-Mobile`、`kelivo` 等可以连接 MCP 或 Assistant，但主要 Agent 运行时在服务端或仍以聊天客户端为主。
- `vFlow` 不是 AI Agent，却是很有价值的 Android 工作流执行底座。
- `mobilerun` 是运行在电脑/服务器侧、通过 Portal/ADB 控制设备的框架，不是手机里的个人 Agent 产品。
- `youshu` 有 AI 入口，但主要是家庭物品管理业务，不应混入 Agent 能力统计。

## 4. 代表项目源码审计

### 4.1 `openclaw`：完整个人 Agent 平台，适合学边界，不适合照搬体量

**已验证事实**

- README 将产品定义为运行在个人设备上的 always-on assistant，Gateway 负责 sessions、channels、tools 和 events；Android 端是可配对的 node/companion，而不是独立承载全部大脑。
- `packages/agent-core/src/agent-loop.ts` 的核心循环会持续处理 LLM tool calls、steering messages 和 follow-up messages，并把 `agent_start`、`turn_start`、`message_update`、`turn_end`、`agent_end` 作为结构化事件发出。
- `src/agents/tool-policy-pipeline.ts` 按 profile、provider、agent、group、sender 多层过滤工具。
- 沙箱文档明确区分 Gateway 与工具执行，并支持 `off/non-main/all`、按 agent/session/shared 隔离、只读/读写工作区和默认无网络容器。
- 自动化不只等于 cron：项目区分 heartbeat、cron 和持久 background tasks，并保留任务生命周期和维护/恢复语义。

**值得小灵借鉴**

- 把 `AgentRun`、`Turn`、`Step`、`ToolCall`、`ToolResult`、`Task` 设计成正式领域对象，而不是继续把运行信息拼在消息 footer 中。
- 工具权限应通过多层策略合并：全局默认、Agent 配置、会话临时授权、触发来源（前台用户/后台任务）共同决定最终可用工具。
- 前台聊天、后台任务、定时任务使用同一运行时，但采用不同默认权限和记忆可见范围。
- 所有运行都应可取消、可恢复、可观察，并在结束时有明确终态。

**不应照搬**

- 多通道 Gateway、多 Agent 路由、Docker/SSH 沙箱和桌面节点是平台级能力，会把小灵当前的 Android 单体 App 直接推向分布式系统。
- OpenClaw 主会话默认在 host 上执行工具的信任模型不适合 Android 消费级 App；小灵必须默认最小权限。

### 4.2 `Operit`：Android 端能力最丰富，但高权限和复杂生态风险最高

**已验证事实**

- README 列出 Ubuntu 24、本地模型、40+ 工具、MCP/Skill/ToolPkg、工作流、定时任务、Tasker、无障碍/ADB/Root 自动化和记忆系统。
- `ToolExecutionManager.kt` 负责工具解析、参数校验、角色卡工具白名单、工具拦截、权限检查、只读工具并行和串行执行。
- `ToolPermissionSystem.kt` 的工具权限是 `ALLOW/ASK/FORBID`，默认 `ASK`；按工具持久化，询问超时 60 秒并默认拒绝。
- `WorkflowScheduler.kt` 使用 WorkManager 支持 interval、specific time 和简化 cron；`Workflow.kt` 将 trigger、execute、condition、logic、extract 建成节点图。
- 记忆使用 ObjectBox、关键词/Jieba、向量、关系边和 RRF 类融合检索；自动保存前会裁剪工具结果，并跳过无价值对话。
- ToolPkg 是带 manifest、JS/TS 入口、资源、UI、工作流模板和工作区模板的 ZIP 插件格式。

**值得小灵借鉴**

- `ToolDefinition + ToolExecutor + permission check + lifecycle hook` 的分层。
- 只读、互不依赖的工具可以并行；需要授权或有副作用的工具必须串行。
- 工作流模型采用可序列化节点和连接，执行统计单独保存。
- 记忆检索不能只做向量相似度，应组合关键词、语义、标签、时间和来源。

**不应照搬**

- Root、ADB、Shizuku/无障碍、完整 Ubuntu、可执行 JS 插件市场同时进入产品，会形成过大的攻击面和测试矩阵。
- 工具市场和脚本运行时需要签名、来源信任、能力清单、版本迁移和沙箱；小灵第一阶段不应开放第三方可执行插件。
- WorkManager 的周期任务最小 15 分钟，简化 cron 也无法保证精确触发，产品文案不能承诺“准点执行”。

### 4.3 `ZeroAI`：运行时工程化很强，但项目自己明确仍处实验和加固期

**已验证事实**

- README 明确其为 Kotlin + Rust + UniFFI 的 Android Agent，使用长驻前台服务，Rust Core 负责 tools、memory、config、runtime、channels 和 gateway。
- 项目状态注明 experimental、主要面向近期 Pixel 硬件、大量代码由 AI 辅助生成且仍在审计加固，不能直接视为生产安全基线。
- Rust 工具循环有最大迭代数、共享父/子 Agent 预算、取消、上下文预裁剪、孤立 tool result 修复、循环检测、工具结果截断和耗尽后的无工具总结。
- `tool_execution.rs` 统一记录工具开始/结束、参数、结果、耗时和错误；只在无审批工具时并行执行。
- Android 记忆写入先走启发式提取，再走敏感信息过滤，最后存储；低电量时降级为只读。
- 调度器支持启动时补跑逾期任务、并发上限、重试退避、按 Agent 安全策略执行和结果持久化。

**值得小灵借鉴**

- Agent 循环必须有硬上限、取消信号、共享预算、重复输出熔断和“已做什么/还剩什么”的优雅收尾。
- 观测、脱敏和错误结构应该包在工具执行器外层，避免每个工具重复实现。
- 记忆写入应先做低成本筛选与敏感信息阻断；后台任务不应读取普通聊天记忆，除非用户明确允许。
- 电量、网络和后台限制应成为调度策略输入。

**不应照搬**

- 小灵当前是纯 Kotlin 小型 App，引入 Rust、UniFFI、双语言构建和复杂 FFI 只会显著增加调试、发布和崩溃定位成本。
- HMAC 工具回执能证明“某段代码生成了回执”，不能证明外部世界动作真实成功；小灵应优先做可读审计日志和业务后置校验。
- 子 Agent、24 能力脚本沙箱和大量渠道在单 Agent 闭环稳定前没有必要。

### 4.4 `hermes-android`：最值得借鉴的移动 Agent 操作面

**已验证事实**

- Android App 是 Hermes Gateway 的原生客户端，不在手机内运行 Hermes Core。
- `HermesGatewayClient.kt` 用 WebSocket RPC + event stream，具备 readiness gate、请求关联、连接代次、防重复 socket、指数退避和手动重连。
- `GatewayConnectionService.kt` 用前台服务保持连接，并只在 App 不在前台时发布通知；单个异常事件不会终止事件收集。
- 审批分为 `STANDARD/ELEVATED`：普通风险允许 once/session/always，高风险禁止 always；通知中的高风险操作不提供直接允许，只允许 Deny/Open。
- `NeedsYou.kt` 把失败或逾期的定时任务聚合为需要用户处理的条目。
- 分享入口把 Android `ACTION_SEND` 文本或图片转为 Agent 输入；cron 编辑器把常见日程建模为 Hourly/Daily/Weekly/Monthly，复杂表达式再退回 Advanced。

**值得小灵借鉴**

- 首页优先展示“需要你处理、正在运行、最近完成”，而不是永远落到聊天页。
- 权限动作提供“仅这次/本次任务/始终允许/拒绝”，高风险不允许永久授权。
- 通知只暴露低风险快捷动作，高风险必须回到 App 查看参数后确认。
- Android 分享、快捷入口和通知比“再做一个功能目录”更符合移动端使用方式。

**不应照搬**

- 远程 Gateway 依赖意味着离线不可用、需要长连接认证和协议兼容；小灵第一阶段应以内置运行时为主，未来再把 Gateway 做成可选执行后端。
- 前台服务并不等于无限后台能力；Android 版本对 service type 和运行时长的限制必须实机验证。

### 4.5 `rikkahub`：从聊天客户端平滑演进 Agent 的最佳 Android 参考

**已验证事实**

- `Assistant.kt` 将模型、system prompt、上下文、记忆、近期会话、MCP、本地工具、web search、workspace、skills 和 prompt injection 集中在 Assistant 配置中。
- `Conversation.kt` 使用 `MessageNode` 保存同一位置的多个候选消息与选中索引，支持分支/重生成；会话还能保存 workspace cwd。
- `GenerationHandler.kt` 最多循环 256 step：生成回复，检测 tool parts，需要审批时暂停，批准/拒绝后恢复，执行工具，把结果写回同一个结构化 message part，再继续模型调用。
- `ToolApprovalState` 明确区分 Auto、Pending、Approved、Denied、Answered；工具 part 同时保存 input、output 和 approval state。

**值得小灵借鉴**

- 在小灵现有 Provider/Conversation 基础上新增 `AgentProfile`，先只承载模型、system prompt、上下文策略、记忆开关、允许工具，不要立即复制所有高级字段。
- 消息内容从单一 text 升级为 parts：Text、Reasoning、Tool、Image/Document；这比另建一套无法融入对话的运行日志更自然。
- 工具等待审批是可持久化状态，用户批准后应从原步骤恢复，而不是重新发送整条消息。

**不应照搬**

- 256 步对手机 Agent 过高；小灵首版建议默认 8 步、硬上限 16 步。
- proot Linux workspace、MCP OAuth、复杂 prompt injection 和角色卡不是首版个人 Agent 必需项。

### 4.6 `X-OmniClaw`：设备 Agent 闭环最完整，但包含大量设备/应用特化

**已验证事实**

- README 明确采用 Observation → Reasoning → Execution，并在系统层扩展为 perceive → plan → act → verify。
- `AgentLoop.kt` 仅保留 Android bridge，核心循环由 Chaquopy Python 实现；默认最大 40 步、LLM 180 秒、普通工具 30 秒，并将工具路由决策发到进度流。
- `DeviceTool.kt` 统一 snapshot、screenshot、act、open、status 和 clipboard；动作依赖最新 snapshot 的 ref，变更页面后使 ref 失效，并提示再次 snapshot 验证。
- 项目包含循环检测、广告误点保护、坐标和 ref 校验、Accessibility tree + screenshot/VLM 双轨和定时任务前台服务。
- 图片记忆在落盘前对身份证、手机号、银行卡、邮箱和高风险关键词做脱敏/分级。

**值得小灵借鉴**

- 设备控制必须是“观察 → 原子动作 → 再观察 → 校验”，不能让模型连续输出一串盲点坐标。
- 屏幕元素引用必须绑定快照并有过期规则；坐标体系不一致时应拒绝动作，而不是猜测换算。
- 快照、动作、验证、工具路由、进度事件应彼此分离。
- 记忆采集先做隐私过滤，图库/通知等高敏数据必须单独授权。

**不应照搬**

- README 要求 Accessibility、Overlay、Screen capture、Photos、All files、Camera、Microphone 七类权限，且设备工具支持 root 打开未导出 Activity；这不适合作为小灵默认权限面。
- 配置写入 `/sdcard/.xomniclaw/xomniclaw.json` 不适合保存 API Key；小灵应继续使用 Keystore 加密。
- `DeviceTool.kt` 中存在剪映、飞书等硬编码修正。小灵不能把具体 App 的坐标/文案修复堆进通用执行器，应通过版本化 skill/app adapter 隔离。
- Kotlin + Python/Chaquopy 双运行时不是小灵当前必要结构。

### 4.7 `mobilerun`：优秀的设备 Agent 框架，不是小灵的产品模板

**已验证事实**

- 框架运行在电脑/服务端，通过 Portal、ADB 或 iOS Portal 控制设备。
- `MobileAgent` 的快速模式直接使用 FastAgent，推理模式使用 Manager 规划 + Executor 动作。
- Manager 每步重新规划和检查终止，Executor 记录 action、summary、outcome、error，再返回 Manager；连续错误会触发重新规划标记。
- 状态模型同时保存当前/前一次 UI、截图、包名、计划、子目标、动作历史、错误、消息和结束状态。
- 坐标动作检查 screenshot-only 坐标范围和当前 coordinate contract；契约丢失时拒绝点击，避免错误坐标落到真实设备。
- 支持轨迹、Langfuse/Phoenix、宏录制、结构化输出、自定义工具和 MCP。

**值得小灵借鉴**

- 将复杂任务拆成 Manager/Executor 是有效模式，但首版可在同一模型中以“Plan step”和“Act step”逻辑分层，不需要两套模型。
- 每个设备动作记录 before snapshot、action、after snapshot、outcome，形成可回放 trajectory。
- 坐标契约、屏幕尺寸和快照 ID 必须作为执行前置条件。
- 可复现轨迹适合未来做 Android Agent 回归测试。

**不应照搬**

- ADB/Portal/电脑端 Python 框架无法直接嵌入小灵 Android App。
- 公开或上传完整截图轨迹存在隐私风险，遥测必须默认关闭并做脱敏。

### 4.8 `meow-agent`：最贴近小灵目标的产品与安全架构参考

**已验证事实**

- 产品目标是 Android 个人 companion，明确声明 capability-scoped、permission-aware、verification-first、local-first 和 user-controlled。
- Runtime 采用 Analyze → Reflect → Plan → Execute → Verify → Review → Verbalize；可以根据置信度跳过 Reflect/Plan，降低简单任务成本。
- ModulePlugin 自注册工具；`ToolDefinition` 由注册表声明 risk、requiresConfirmation、operation、target、postconditions 和 verificationProbe，风险不接受模型自报。
- ToolRouter 在 dispatch 前执行权限和确认；敏感动作停车等待用户确认，批准后恢复。
- `TaskLedger` 持久化目标树、完成条件、历史结果、当前步骤、待确认工具和状态，App 重启后可恢复。
- `RecoveryCoordinator` 默认最多两次恢复，结构性失败直接放弃，相同工具/参数/原因连续失败不再重试。
- Provider Key 存在 secure storage，SQLite 只保存引用；项目还提供工具权限覆盖测试。

**值得小灵借鉴**

- 这是小灵首个 Agent 运行时最适合参考的骨架：工具注册表、权限策略、确认管理器、执行后验证、任务账本和有界恢复。
- 简单请求走 direct/fast path，复杂请求再进入计划阶段，避免所有聊天都付出多次 LLM 调用。
- 模块默认关闭，模块开关和 Android 权限是两层独立门禁；应有测试保证每个工具都绑定权限规则，防止 fail-open。
- 任务账本和长期记忆必须分开：账本服务一项任务，记忆服务用户长期信息。

**不应照搬**

- 多阶段每阶段都调用 LLM 会显著增加延迟和成本；小灵第一版应使用单循环、按需生成简短 plan。
- Flutter 架构不能直接迁移到 Kotlin/Compose，借鉴领域模型和状态机即可。
- “所有动作都需确认”的产品文案与源码中的 safe/sensitive-lite 自动执行存在粒度差异；小灵应在 UI 清晰呈现实际策略。

### 4.9 `skales`：适合参考 autonomous UX，不适合直接迁移

**已验证事实**

- Electron/Next.js 项目包含 goals/tasks、autopilot、自主 runner、message queue、approval store、killswitch、memory retrieval、skill dispatcher，以及 browser/email/calendar/computer-use 等 actions。
- 项目把“停止所有自主执行”的 killswitch 和审批存储作为独立模块，而不是藏在聊天状态中。

**值得小灵借鉴**

- 后台自主任务必须有全局暂停、单任务取消和明显的运行状态。
- 自主任务 UI 应围绕 goal、next action、blocked reason、last result，而不是只显示聊天记录。

**不应照搬**

- 桌面 Electron 的常驻能力、文件权限和浏览器控制与 Android 生命周期差异很大。

### 4.10 `OGAM`：离线模型资源管理和工具路由的未来参考

**已验证事实**

- 项目是 React Native + 原生推理的 local-first AI suite，包含本地/远程 provider、模型下载、模型驻留、内存预算、RAG、MCP、工具注册和 generation tool loop。
- 源码把工具调用结果建成带 success/error/duration 的结构；工具循环、上下文压缩、embedding 工具路由和模型内存驻留是独立服务。
- 项目 docs 主动记录真实设备 OOM、工具解析、停止不生效、MCP 首次路由延迟和测试漏跑等缺口。

**值得小灵借鉴**

- 若未来做本地模型，必须先有机型分级、内存预算、单一权威的模型适配判断、下载恢复和真实设备测试矩阵。
- 工具过多时先做确定性 shortlist，再考虑 embedding 路由，不能把全部 schema 永远塞入上下文。
- 把已知缺口和实机失败作为一等文档资产，比只记录成功路径更有价值。

**不应照搬**

- 小灵当前没有本地推理需求，不应为了“离线 Agent”立即引入 llama.cpp/LiteRT、模型下载、GPU/NPU 和多模型驻留。

## 5. 对小灵当前状态的判断

截至 `v0.1.9`，小灵已经具备可靠聊天底座和可执行应用内任务的最小 Agent 闭环：

- 多 Provider、模型发现和启用列表。
- Chat Completions / Responses API，以及保留 system/user/assistant 边界的消息和通过 `call_id` 关联的函数调用/结果 typed Items。
- SSE 流式输出与 30ms UI 节流。
- 多轮会话、本地保存和摘要压缩。
- Markdown、错误分类、结构化消息元数据。
- API Key 使用 Android Keystore + AES-GCM。
- `/agent` 与普通聊天分流，具备 `AgentRun / AgentStep / ApprovalRequest / RunEvent`、运行预算、超时、取消和终态收敛。
- 应用侧 `ToolRegistry`、风险分级、交互审批和执行后验证，以及当前时间、会话检索、本机笔记和长期记忆工具。
- Tool Registry 已统一完整 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、前后台来源门禁、超时和回读验证策略；重复工具名启动失败，权限检查默认 fail-closed。
- 对话 Run 时间线、审批卡片和设置页 Agent 任务中心；任务中心支持状态筛选、完整 ToolResult 和失败终态安全重新运行。
- `MessageOrigin / VerifiedAgentContext` 可信来源边界和三类独立提示词设置。
- Workflow Ledger、一次性 WorkManager 非精确定时、计划/实际时间、结果通知，以及后台审批 `BLOCKED` 终态。
- `LlmProviderAdapter / OpenAiCompatibleAdapter` 协议边界，HTTP 传输与 Provider 请求/响应映射已分离。
- ToolCall、ToolResult、审批和恢复事件使用独立 `RunEventMetadata`，运行记录 UI 不再解析 message JSON。
- 设置页长期记忆管理支持 FTS4 + 中文子串兜底搜索、状态筛选、编辑、置顶、启停、删除确认和来源会话/Run 跳转；禁用或删除后不再参与 Agent 检索。
- Room v4、v6-v14 Schema 导出；迁移测试源码覆盖历史 Provider、会话、Run、审批、记忆、Skill、Workflow 与 ScheduledTask 演进。当前 v14 用独立 `scheduled_tasks` 和 Workflow Run 关联字段保存后台计划账本。

现有关键实现位于：

- `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt`
- `app/src/main/java/com/longdev/xiaoling/network/OpenAiCompatibleClient.kt`
- `app/src/main/java/com/longdev/xiaoling/agent/MinimalAgentRuntime.kt`
- `app/src/main/java/com/longdev/xiaoling/agent/XiaoLingToolRegistry.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/RoomAgentRunRepository.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/RoomAgentMemoryStore.kt`
- `app/src/main/java/com/longdev/xiaoling/prompt/PromptPolicy.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/SecureConfigStore.kt`

最小闭环已经落地，但距离完整个人 Agent 仍有以下缺口：

| 缺口 | 当前影响 |
|---|---|
| 没有 AgentProfile | Provider/模型与“这个 Agent 是谁、能做什么”混在一起 |
| Runtime 已支持最多 4 步顺序工具循环，但不支持并行调用 | 可以根据上一步已验证结果继续选择工具；互不依赖的只读工具仍无法并行降低延迟 |
| ToolCall/ToolResult 已进入 RunEvent metadata，但还没有独立表 | 可审计展示已结构化，跨步骤查询、重放和恢复仍不方便 |
| 失败终态可安全重新运行，但没有原地恢复执行栈 | 进程重建后先收敛中间态，再由用户创建关联的新 Run；无法从原 ToolCall 位置继续 |
| 长期记忆治理已形成首版闭环，但召回质量仍需规模化验证 | 已有候选确认、敏感过滤、去重/冲突、跨进程删除撤销、过期策略、时间衰减、实际引用审计和单次召回关闭；更大数据量下仍需验证排序与中文召回质量 |
| 一次性后台账本已完成，但系统回收续跑与周期规则未完成 | 可追溯一次性任务；长任务断点和 Daily/Weekly 规则仍需设计 |
| 消息仍以单一文本为主 | Responses 已支持函数调用/结果 Items，但 Reasoning/Image/File 和持久化消息 parts 仍待实现 |

## 6. 建议目标架构

保持 Kotlin + Compose + OkHttp，不新增 Rust/Python/Flutter。当前已形成 `agent`、`data`、`storage` 和 `prompt` 最小边界；后续继续细分 domain/runtime/tools/approval/verification/memory/task，并复用现有 network 与 Keystore。

最小状态机为：`QUEUED -> THINKING -> WAITING_APPROVAL -> EXECUTING -> VERIFYING -> THINKING/COMPLETED`，并允许进入 `BLOCKED/FAILED/CANCELLED/BUDGET_EXHAUSTED`。后台规划到需审批工具时直接进入 `BLOCKED`，不进入交互审批等待。

关键规则：

- 默认 8 步，前台硬上限 16，后台使用更低上限。
- 同一工具和规范化参数连续重复两次警告、三次阻断。
- 工具超时、用户取消、进程重启都产生可解释终态。
- 工具结果进入模型前截断并脱敏，完整结果保存在本地审计表。
- `WAITING_APPROVAL` 持久化，重启后仍可批准或拒绝。
- `AgentTool` 只暴露代码注册的 `ToolDefinition` 与 `execute()`；definition 固定声明风险、Android 权限、超时、后台能力和验证器，模型不能降级这些字段。

## 7. 分阶段功能开发与迭代清单

### P0：Agent 运行时底座

目标：让小灵能安全、可观察地执行第一批只读工具，而不是直接做手机自动化。

当前状态：Room v14 Schema、迁移测试源码、RunEvent typed metadata、完整 Tool Registry 契约、AgentRuntime、审批/验证、确定性测试、任务中心、安全重新运行、长期记忆治理和一次性 Workflow 调度已完成；独立 ToolCall/ToolResult 表、消息 parts、AgentProfile 和原地断点恢复仍待完成。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| Room 存储 | 新建 Provider、Conversation、Message、AgentRun、AgentStep、ToolCall、Approval 表；从 SharedPreferences 一次性迁移 | 升级不丢现有 Provider/会话；迁移可重复且有单测 |
| 消息 parts | 把消息升级为 Text/Reasoning/Tool/Image/Document parts，保留旧 text 迁移 | 流式文本和工具步骤能在同一消息中恢复 |
| AgentProfile v1 | 字段只含 name、avatar、provider/model、systemPrompt、contextPolicy、allowedTools、memoryEnabled | 可创建多个 Agent，并为每个 Agent 选择不同模型与工具 |
| ToolRegistry | 工具定义、JSON Schema、风险、权限、超时、后台能力、验证器统一注册 | 未注册工具永远不能执行；重复名称启动时报错 |
| AgentRuntime v1 | LLM → tool call → permission → execute → tool result → LLM；支持取消、8 步预算、超时和重复检测 | 模拟工具链成功、失败、拒绝、取消、超时、预算耗尽均有自动化测试 |
| 可观测运行 UI | 展示当前步骤、工具名、参数摘要、结果摘要、耗时和停止按钮 | 用户能区分“模型正在想”和“工具正在做” |

首批应用内工具现已完成：

- `app.current_time`：当前时间/时区。
- `app.list_conversations` / `app.search_conversations`：只读列出和检索本地会话。
- `notes.list` / `notes.search` / `notes.create`：本机笔记读取与确认后写入、回读验证。
- `memory.search`：只读检索已授权记忆。
- `memory.remember`：确认后写入带来源的长期记忆。

仍待评估的后续工具包括受限 `web.fetch`、显式 `ask_user` 和应用信息读取。

### P1：个人化、记忆和移动入口

目标：小灵开始“认识用户”，但记忆必须透明可控。

当前状态：记忆表、工具读写、来源审计、FTS4 + 中文兜底检索、管理页、编辑、置顶、启停、候选生成与确认、敏感过滤、去重/冲突、跨进程删除撤销、过期策略、时间衰减、本轮引用审计和单次召回关闭已完成；更大数据量下的召回质量仍待验证。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| 长期记忆 v1 | 已完成管理与候选闭环；从用户明确陈述生成 preference/profile 候选，再由用户确认保存 | 每条记忆显示来源会话/Run 和时间，可编辑、置顶、禁用、删除；候选功能默认关闭 |
| 敏感过滤 | 已完成 API Key、token、密码、银行卡、身份证、手机号阻断，候选和 `memory.remember` 共用策略 | 固定敏感样例测试全部通过；命中时只保存类别和固定提示，不保存完整敏感值 |
| 记忆召回 | 已完成 FTS4 + 中文子串兜底、时间衰减、本轮引用审计和单次召回关闭；数据规模扩大后再评估 embedding | 删除、禁用或过期后不再召回；任务中心可查看实际使用的记忆 ID |
| 分享给小灵 | 支持 Android `ACTION_SEND` 文本、链接、图片，进入新任务草稿而非静默执行 | 分享后必须由用户确认发送；来源 App 和附件可见 |
| 语音输入 | 先做系统 SpeechRecognizer/录音转写到输入框，不自动执行 | 用户可编辑转写文本后再发送；权限拒绝可正常降级 |
| 快捷任务模板 | 用户保存 prompt + Agent + 默认参数，不保存高风险永久授权 | 模板执行前展示输入和将使用的 Agent/工具 |

### P2：任务账本、定时任务和“需要你处理”

目标：支持长任务和计划任务，但不夸大 Android 后台可靠性。

当前状态：Workflow/ScheduledTask Ledger、一次性非精确定时、取消、计划/实际时间和完成/失败/blocked 通知已交付；周期规则、系统回收续跑和聚合式“需要你处理”首页待完成。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| TaskLedger | 保存 goal、steps、currentStep、priorResults、pendingApproval、status、retryCount | App 被杀后重新打开可看到任务状态；待确认动作能继续处理 |
| Activity 首页 | 三段：需要你处理、运行中、最近完成；失败/逾期/待确认置顶 | 冷启动不会误显示旧聊天为正在运行 |
| 定时任务 v1 | 已完成一次性计划；下一步增加结构化 Daily/Weekly，WorkManager 继续承担非精确任务 | UI 明确“系统可能延迟”；展示预计下次运行和上次结果 |
| 后台安全策略 | 已完成只允许 `supportsBackground=true` 的 SAFE 只读工具且不继承前台授权 | 后台调用需审批工具时转为 BLOCKED 并通知用户 |
| 通知 | 完成/失败/待确认；低风险可快捷操作，高风险只允许打开 App/拒绝 | 通知 action 有单测；高风险不存在一键永久允许 |
| 有界恢复 | 网络/临时失败最多重试两次并退避；权限拒绝等结构性失败不重试 | 不出现无限重试；失败原因和已完成步骤可见 |

精确闹钟、锁屏亮屏和全屏 Intent 需要单独评估政策与权限，不能默认加入 v1。

### P3：有限设备 Agent

目标：从“可控系统动作”开始，而不是第一天就请求 Accessibility + Overlay + Root。

实施顺序：

1. **Intent/deep link 工具**：打开系统设置页、拨号盘、地图、浏览器、分享目标；只构造 Intent，不自动完成不可逆动作。
2. **系统 API 工具**：日历、通知读取、剪贴板等，每个能力独立模块和独立授权。
3. **动作预览模式**：参考 `AndroidMCPAgent`，模型先给计划，用户确认后逐步执行。
4. **可选 Accessibility 模块**：明确说明能力与隐私影响；默认关闭；只允许用户配置的 App 白名单。
5. **观察-动作-验证**：每次动作前后抓取新 snapshot；元素 ref 绑定 snapshot；过期/坐标契约失效则拒绝执行。
6. **可回放轨迹**：保存脱敏后的 before/action/after/outcome，用于失败复盘和回归测试。

P3 明确不做：Root、Shizuku、静默安装 APK、绕过未导出 Activity、所有文件访问、跨 App 密码/支付自动化。

### P4：生态与离线能力

这些能力只有在 P0-P3 稳定并有真实使用数据后再做：

| 能力 | 前置条件 | 建议方案 |
|---|---|---|
| MCP client | ToolRegistry/审批/审计稳定 | 先支持 remote HTTP MCP；逐 server/逐 tool 开关；工具风险不能全部默认为 safe |
| Skills | 有稳定工具和 prompt 装载边界 | 第一版只允许声明式 Markdown skill，不允许可执行脚本 |
| Workflow 编辑器 | TaskLedger/调度/节点执行稳定 | 参考 `vFlow`/Operit，先做 trigger + tool + condition 三类节点 |
| Remote Gateway | 内置运行时稳定且确有跨设备需求 | 抽象 `AgentBackend`，本地/远程共用事件协议和审批模型 |
| 本地模型 | 有明确离线需求和目标机型 | 先做小模型下载、内存预算、能力探测和真机矩阵，再接入 Agent |
| 多 Agent/委派 | 单 Agent 任务成功率和观测完善 | 子 Agent 只能获得父 Agent 权限子集，并共享总步数/成本预算 |

## 8. 安全与隐私基线

下列规则应在引入第一个工具时就落地，不能后补：

1. 默认拒绝：未知工具、缺失权限规则、无法解析参数、风险未知时都不执行。
2. 风险由注册表定义：模型输出不能修改 `risk`、`requiresConfirmation` 或 `requiredPermissions`。
3. 授权有范围：once、task、always；危险工具不提供 always。
4. 触发来源隔离：前台用户、分享入口、通知、定时任务、远程入口使用不同默认策略。
5. 工具参数可见：确认页展示自然语言说明、目标对象和关键参数；敏感字段脱敏。
6. 执行后验证：成功返回只是候选成功；副作用工具必须有 probe 或提示“未验证”。
7. 记忆可追溯：保存来源、时间、Agent、敏感级别；支持删除、导出和关闭召回。
8. 后台不继承临时授权：前台点过一次允许，不代表 cron 可以重复执行。
9. 远程内容不可信：网页、消息、附件可能包含 prompt injection，不能改变工具策略。
10. 调试和遥测默认不上传原始 prompt、API Key、截图、联系人、通知和工具结果。

## 9. 不建议现在做的功能

- 不引入 Rust/UniFFI、Python/Chaquopy 或 Flutter 重写。
- 不做 Root/Shizuku/ADB 常驻控制和完整 Linux 环境。
- 不做第三方可执行脚本/ToolPkg 市场。
- 不默认请求 Accessibility、Overlay、All files、Camera、Microphone 全套权限。
- 不做全渠道机器人和多 Agent 编排。
- 不把所有工具 schema 永远塞入 prompt。
- 不用向量数据库替代清晰的记忆来源、确认和删除机制。
- 不承诺 Android 定时任务绝对准点或进程永久在线。
- 不把工具返回 `success=true` 当作任务完成证明。

## 10. 主要本地证据路径

以下路径均相对 `/Users/long/Documents/CodexProjects/endpoint-test/reference-apps/`。

| 项目 | 主要证据 |
|---|---|
| 全量分类 | 各仓库根 README；`Agora/docs/en/index.md`；`ClaraVerse/docs/ARCHITECTURE.md`；`opencyvis-phone/docs/architecture.md`；`vFlow/docs/vFlow_App_Architecture.md` |
| `openclaw` | `README.md`；`docs/agent-runtime-architecture.md`；`docs/gateway/sandboxing.md`；`packages/agent-core/src/agent-loop.ts`；`src/agents/tool-policy-pipeline.ts` |
| `Operit` | `README.md`；`docs/TOOLPKG_FORMAT_GUIDE.md`；`app/src/main/java/com/ai/assistance/operit/api/chat/enhance/ToolExecutionManager.kt`；`ui/permissions/ToolPermissionSystem.kt`；`core/workflow/WorkflowScheduler.kt`；`data/repository/MemoryRepository.kt` |
| `ZeroAI` | `README.md`；`zeroclaw/crates/zeroclaw-runtime/src/agent/loop_.rs`；`agent/tool_execution.rs`；`cron/scheduler.rs`；`app/src/main/java/com/zeroclaw/android/memory/MemoryExtractionPipeline.kt`；`SensitivityFilter.kt` |
| `hermes-android` | `README.md`；`docs/superpowers/specs/2026-07-10-tiered-approvals-design.md`；`data/network/HermesGatewayClient.kt`；`ui/chat/ApprovalTier.kt`；`notifications/GatewayConnectionService.kt`；`ui/activity/NeedsYou.kt` |
| `rikkahub` | `docs/references/chat-generation-pipeline.md`；`data/model/Assistant.kt`；`data/model/Conversation.kt`；`data/ai/GenerationHandler.kt`；`ai/.../ui/Message.kt` |
| `X-OmniClaw` | `README.md`；`agent/loop/AgentLoop.kt`；`agent/tools/device/DeviceTool.kt`；`agent/memory/gallery/ImageMemoryPrivacyFilter.kt`；均从 `HEAD` Git 对象读取，未恢复工作区删除 |
| `mobilerun` | `README.md`；`mobilerun/agent/droid/droid_agent.py`；`droid/state.py`；`manager/manager_agent.py`；`executor/executor_agent.py`；`utils/actions.py` |
| `meow-agent` | `README.md`；`ARCHITECTURE.md`；`MODULE.md`；`lib/services/agent_runtime/runtime_engine.dart`；`tool_router.dart`；`completion_verifier.dart`；`task_ledger.dart`；`recovery_coordinator.dart` |
| `skales` / `OGAM` | `skales/README.md`、`apps/web/src/lib/{autonomous-runner,approval-store,killswitch}.ts`；`OGAM/README.md`、`docs/ARCHITECTURE.md`、`docs/GAPS_BACKLOG.md`、`src/services/{generationToolLoop,contextCompaction,memoryBudget}.ts` |

## 11. 最终建议

小灵下一版不应以“接入 MCP”或“控制手机”为里程碑，而应以以下可验证结果为里程碑：

> 用户选择一个 Agent，发起一个需要 2-3 步的任务；小灵展示计划，调用只读工具，遇到副作用时暂停并解释风险，用户按范围授权后继续，执行后验证结果，最终保存完整可审计运行记录；App 被杀或网络中断后，任务仍能以明确状态恢复或结束。

当这条主链在单元测试、集成测试和真机上稳定后，小灵才真正从 AI 聊天客户端跨入个人 Agent；后续记忆、定时、设备操作、MCP 和 Skills 都可以沿同一套安全边界扩展，而不需要推翻重做。
