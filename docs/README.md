# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

第 74 阶段完成网络请求设置页交互统一：根设置页的“网络请求”改为与其他设置项一致的入口卡片，点击进入独立子页；User-Agent 编辑区默认至少 5 行，右下角提供复制和清空，并保留恢复默认。新增 `NetworkRequestSettingsContentInstrumentedTest`，Redmi 单项 `1/1`、完整 instrumentation `153/153`，真实 UI 已确认入口、导航、输入区高度和按钮布局。第 73 阶段的会话选择与删除副作用协调器仍保持“取消旧加载 → 标记删除代次 → 清理运行态 → 即时选择或完整加载”的顺序；当前标准门禁为 JVM `472/472`、Lint、Debug/AndroidTest 构建和仅 Redmi 执行的 `153/153` instrumentation。

第 72 阶段完成会话新建与删除选择规则迁出：`ConversationSessionPolicy` 新增纯 `Immediate / Load` 选择计划，统一复用当前空会话、选择并折叠最新空占位、创建稳定新占位、删除后选择最新剩余会话和删空兜底。计划显式区分复用既有会话与新建占位，只有前者恢复 Agent Run/审批状态；ViewModel 继续负责取消加载、删除意图、Map 清理、完整消息加载和选择保存。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 行为不变；新增聚焦 JVM `5/5`，完整 JVM `468/468`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 71 阶段完成会话加载 UI 投影规则迁出：新增纯 Kotlin `ConversationLoadProjectionPolicy`，统一 Loading 清理旧提示、Loaded 原子切换会话和 Failed 错误收敛。非当前会话索引同时移除 Image/Document BLOB，当前可见会话仍在同一次状态替换中注入完整消息与附件；ViewModel 继续负责删除意图回滚、Agent Run/审批映射读取和成功后的选择保存。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 行为不变；新增聚焦 JVM `3/3`，完整 JVM `463/463`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 70 阶段完成异步会话加载协调迁出：新增纯 Kotlin `ConversationLoadCoordinator`，统一 latest-load Job、单调选择代次和 Loading/Loaded/Failed 稳定事件。底层 Room 查询在取消后仍迟到返回或抛错时，旧选择不会覆盖当前会话、删除回滚或提示；Loading 回调重入选择也不会覆盖最新 Job。ViewModel 继续负责附件轻量化后的原子 UI 投影、删除后的下一会话选择、回滚和保存。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 行为不变；四轮 TDD 后新增聚焦 JVM `4/4`，完整 JVM `460/460`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 69 阶段完成会话保存协调迁出：新增纯 Kotlin `ConversationPersistenceCoordinator`，统一 latest-save Job、Room 单写者串行、发送前等待旧保存，以及显式删除 ID 的代次化确认与回滚。旧保存即使已进入不可取消提交区，最新快照也会等待并最后写入；删除事务失败、同 ID 在事务期间重新标记或旧读取失败回调晚到时，都不会误清除新删除意图。`XiaoLingViewModel` 不再持有会话保存 Job 或待删除集合，从 4189 行降到 4183 行；异步会话加载、删除后的 UI 切换/失败回滚和 Compose 副作用仍留在 ViewModel。Room 仍为 v29，附件 BLOB 保护、Provider 协议、UI、`/agent` 与 Workflow 行为不变；八轮 TDD 后新增聚焦 JVM `8/8`，完整 JVM `456/456`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 68 阶段完成会话状态投影规则迁出：新增纯 Kotlin `ConversationSessionPolicy`，统一第一条 `role=user` 消息标题（正文空白时保持“新会话”）、重复空会话折叠、会话时间戳、摘要元数据继承、blank ID 生成和非当前会话更新隔离。`XiaoLingViewModel` 删除 83 行对应私有实现，从 4272 行降到 4189 行；异步 Room 加载、保存 Job、删除事务与 Compose 副作用仍留在 ViewModel。Room 仍为 v29，Provider 协议、UI、`/agent` 和 Workflow 行为不变；六轮 TDD 后新增聚焦 JVM `6/6`，完整 JVM `448/448`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 67 阶段完成普通聊天网络发送编排迁出：新增 `ConversationSendCoordinator`，以稳定事件统一“发送前 Room 快照持久化 → 请求上下文准备 → 模型请求 → 流式增量 → 成功/取消/失败收敛”的顺序。`XiaoLingViewModel.sendMessage()` 从约 190 行收敛到约 104 行，只保留入口校验、用户输入投影、旧保存 Job 协调和发送 Job 生命周期；Compose 状态、30ms 流式节流及最终消息投影仍留在 ViewModel，因此不宣称总文件继续变小。取消会先发出携带最近已准备上下文的终态事件，再继续传播 `CancellationException` 触发 OkHttp 取消。Room 仍为 v29，Provider 协议、UI、`/agent` 和 Workflow 边界不变；新增聚焦 JVM `3/3`，完整 JVM `442/442`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 66 阶段完成普通聊天请求上下文准备的第一轮 ViewModel 瘦身：新增独立 `ConversationRequestContextPreparer`，统一负责可进入上下文的消息筛选、知识引用生命周期核验、旧摘要失效/复用、最近 16 条窗口、增量摘要边界、窗口外最多 8 条可信 Agent 结果，以及 Responses 最近窗口的用户附件投影。ViewModel 只注入 Room 引用核验、摘要网络调用和当前提示词设置，文件从 4439 行降到 4224 行。知识核验与摘要生成期间的 `CancellationException` 现在继续传播，不再被空引用或本地摘要兜底吞掉。Room 仍为 v29，请求协议和 UI 行为不变；新增聚焦 JVM `8/8`，完整 JVM `439/439`、Redmi instrumentation `152/152`、Lint 和构建均通过。

第 65 阶段完成进程退出观察的只读诊断 UI：设置页新增“进程退出观察”入口，页面只调用 Room Store 的 `latest()` 读取最近 30 条既有记录，不触发新的 `ApplicationExitInfo` 采集。六类稳定证据使用不同中文标签展示，并保留 reason、PID/status、进程名、退出/首次观察时间、importance、PSS/RSS 和设备 LMK 报告能力；页面固定说明记录不关联 Agent Run、工作流或任务，候选与受控退出不能作为自然 LMK。Room 保持 v29。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431`、Lint 和构建均通过；真实页面显示受控 `force-stop` 为 `USER_REQUESTED / 受控退出或包维护`，点击刷新后数据库仍为 1 条，证明查看页面没有改变观察样本。

第 64 阶段完成 Android 进程退出观察账本：应用前台启动和生产 `ScheduledWorkflowWorker` 冷启动都会读取 Android 11+ `ApplicationExitInfo`，但后台 Worker 必须先登记当前进程执行所有权，避免破坏启动恢复隔离。Room v29 新增独立 `process_exit_observations` 表，不关联 Task/Run，不保存 description、trace 或进程状态摘要，以稳定退出身份去重并只保留最新 30 条。只有系统明确报告 `LOW_MEMORY` 才归为直接 LMK；设备不支持直接报告时的 `SIGNALED + SIGKILL` 仅为候选，用户停止、应用取消和安装/包维护固定归为受控或维护退出。旁路采集失败不阻断聊天、恢复或 Workflow，但协程取消必须继续传播。Redmi 聚焦 `5/5`、完整 instrumentation `149/149`、JVM `431/431`、Lint 和构建均通过；正式 schema 29 在一次受控 `force-stop` 后记录 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，没有伪造自然 LMK，因此继续使用普通 WorkManager，不引入 Foreground Service。

第 63 阶段完成 Redmi 真实应用取消证据：Android 14 的实际 WorkManager 在已运行 Worker 被应用取消时返回 `CANCELLED_BY_APP(1)`，测试直接读取 `CoroutineWorker.stopReason` 并经生产 `ScheduledWorkerStopReasonPolicy` 归一化；Room 契约同时证明用户先点击“停止运行”形成的 `STOP_REQUESTED` 栅栏仍保留用户原因，不会被随后到达的 `CANCELLED_BY_APP` 机制码覆盖或伪装成独立系统停止。聚焦 `2/2`、完整 Redmi instrumentation `145/145`、JVM `424/424`、Lint 和构建均通过。测试包已卸载，正式应用前台 PID `518`、crash buffer 为空；数据库保持 `schema=28 / providers=1 / agent_profiles=1 / workflows=0 / scheduled_tasks=0 / workflow_runs=0 / agent_tool_results=0`。本阶段是受控应用取消，不是自然 LMK、配额、超时或系统回收证据，因此继续使用普通 WorkManager，不引入 Foreground Service。

第 62 阶段完成后台 Worker 停止原因审计：生产 `ScheduledWorkflowWorker` 在 Android 12+ 读取 WorkManager `getStopReason()`，通过隐私安全的稳定 `code + name` 映射保存到 Room v28 的 ScheduledTask 与 WorkflowRun，并在任务中心展示标准化原因；`NOT_STOPPED`、旧 Android 或未知码不会被猜测成具体系统原因。Room v27→v28 迁移只新增可空列，不为历史记录补造证据。JVM 门禁为 `424/424`，Redmi `wsvwypiz7xwslvl7` 完整 instrumentation 为 `143/143`，新增迁移与 `QUOTA(10)` 双表原子持久化用例通过；测试包已卸载，正式数据库为 `schema=28 / providers=1 / agent_profiles=1 / workflows=0 / scheduled_tasks=0 / workflow_runs=0 / agent_tool_results=0`，正式应用已回到前台且 crash buffer 为空。本阶段没有取得自然系统停止样本，也不据此引入 Foreground Service。

第 58 阶段早期阻断样本：真实后台 Workflow Probe 曾在 Redmi 触发生产 WorkManager，但两次均在约 4 至 6 秒于上游 TLS 握手阶段失败；Task/Workflow/Agent 均按失败链收敛，只有 1 个 Agent Run，预算快照保持单调，未复制 Run。Redmi 自带 `curl` 对同一端点也得到 `BoringSSL SSL_ERROR_SYSCALL`，Mac 侧同端点可完成 TLS 并返回 HTTP 401，因此当时证据指向 Redmi 网络路径或上游 TLS 兼容问题，而不是应用内 OkHttp 配置。该失败样本的临时 Probe、测试包和取证数据已清理；网络恢复后的成功复验见下一段。

第 58 阶段网络恢复复验已完成：同一 Redmi 生产 WorkManager 8 步 SAFE Workflow 在 `92.667s` 内完成，Task、Workflow 和 8 个 Agent Run 全部 `COMPLETED`，8 个 ToolResult 均 `success=true / PASSED`，每个 Run 的预算快照单调，只有一个 Workflow Run，没有复制执行；历史退出 `lowMemoryExits=0`，仍未取得 Android 自主 LMK。Probe 与数据已清理，当前下一步仍不引入 Foreground Service，继续以更长耗时或真实系统回收证据驱动判断。

第 59 阶段已完成更长真实后台成功复验：Redmi 正式 WorkManager 在 `229.416s` 内完成 8 步复合 SAFE Workflow，单一 ScheduledTask 只关联一个 WorkflowRun，8 个 AgentRun 全部 `COMPLETED`；每步依次调用 3 个只读工具，共 24/24 ToolResult 为 `success=true / PASSED`，记录 72 条预算更新、24 条 `tool.verify`，`llmFailureKinds=[]`。本轮 `ApplicationExitInfo` 为 `supported=true / exits=14 / lowMemory=0 / fallbackSigkillCandidates=0`，退出均为 instrumentation/安装停止，仍没有 Android 自主 LMK。临时 Probe、测试包和 Stage 59 数据已清理，普通 WorkManager 当前已有约 229 秒成功证据，仍不预先引入 Foreground Service。

第 60 阶段补齐真实后台冷启动证据：入队 Probe 在 `0.255s` 后退出且原 PID 消失，JobScheduler 随后冷启动新 PID `25825`，生产 Worker 以同一持久化 WorkRequest/ScheduledTask/WorkflowRun 在 `204.977s` 内完成 8 步、32 次只读工具调用。8 个 AgentRun、32/32 ToolResult 和 32 条 `tool.verify` 全部成功；每个 Run 有 11 条预算快照，`consumedMs` 最大值 `18.431s–26.779s` 且回退次数均为 0，`llmFailures=0`。`ApplicationExitInfo` 为 `supported=true / exits=16 / lowMemory=0`，仍没有自然 LMK。Probe、测试包和取证数据已清理，普通 WorkManager 继续保留。

第 61 阶段完成熄屏真实后台验收：Probe 在 `0.275s` 后退出、原 PID 消失，设备保持 `mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`；JobScheduler 延迟 `159.479s` 后冷启动 PID `26797`，同一 WorkRequest/ScheduledTask/WorkflowRun 在熄屏状态下完成 `244.236s` 的 8 步、32 次只读工具链。8 个 AgentRun、32/32 ToolResult 和 `tool.verify` 全部成功；每个 Run 11 条预算快照，`consumedMs` 最大值 `18.283s–44.856s`，回退次数均为 0，`llmFailures=0`。LMK 为 `supported=true / exits=16 / lowMemory=0`，仍无自然回收或 Foreground Service 依据。

当前发布基线：`v0.1.11`；文档内容已同步到 Room v29、消息 parts、Agent Profile、长期记忆、1 至 8 步 Workflow、本地知识库、答案级知识引用、设备 Agent 观察与有限动作层、网络请求独立设置页、进程退出观察账本，以及已迁出 ViewModel 的普通聊天上下文准备、网络发送编排、会话状态投影、保存、加载和选择/删除协调。当前恢复边界保持 fail-closed：提交未知、验证事实不完整和旧模型协程不会原地恢复；确认后只创建关联新 Run。当前门禁为 JVM `472/472` 与仅 Redmi 执行的 `153/153` instrumentation；Embedding、设备 Workflow/后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

第 46 阶段完成 Redmi 长任务与系统策略取证：强制 Doze 会延后同一 WorkRequest，退出 Doze 后任务只创建一个 Workflow/Agent Run；8 步真实模型 Workflow 在约 28.5 秒内于第二步重复调用检测处安全失败。`send-trim-memory` 与退出 Doze 样本均观察到短时 `connection closed`，但无压力对照也出现启动恢复竞态，因此不建立内存压力或 Doze 与连接关闭的因果关系。该竞态曾让 ScheduledTask/Workflow 保持 `CANCELLED` 而迟到协程把 AgentRun 改成 `COMPLETED`；现已在 Room DAO 用原子非终态条件更新冻结 AgentRun 终态，并增加 Redmi 回归。仍缺 Android 自主 LMK 样本，不提前引入 Foreground Service，也不恢复旧 Executor 或 Workflow 后续步骤。

第 47 阶段完成当前进程 Worker 所有权与启动恢复隔离：Worker 在构造 Repository、重入对账或 claim 之前用计数型进程注册表登记 Task；ViewModel 在同一互斥边界冻结启动候选，快照期间新 Worker 必须等待，已登记 Worker 的 ScheduledTask、WorkflowRun 和 AgentRun 从旧候选中排除。Redmi Room 测试确认旧链按原策略收敛，当前链保持活动、可继续完成且不创建第二个 Run。实现不依赖墙上时间，不新增 Room owner token 或 Schema，也不扩大旧执行栈恢复能力。

第 48 阶段完成后台运行中停止和长成功样本：工作流页为 `RUNNING` ScheduledTask 提供“停止运行”，先取消目标 WorkRequest 并等待 Worker 正常收敛，超时或系统取消异常时按 Task→Workflow→Agent 持久化链兜底；Agent 尚未关联的缺链窗口也会关闭 Task/Workflow，`SCHEDULED→RUNNING` 抢占竞态会自动升级为运行中停止。Run、Step、Approval、Event 和 Tool Ledger 在终态后一并冻结，迟到 HTTP/模型/审批结果不能覆盖 `CANCELLED`。Redmi 真实停止样本约 32.6 秒；另一个三步 SAFE Workflow 依次执行 `app.current_time`、`app.list_conversations(limit=3)`、`notes.list(limit=3)`，约 21.8 秒完成。设备支持 LMK 原因报告，历史 11 条退出记录中 `REASON_LOW_MEMORY=0`，因此仍没有 Android 自主 LMK 样本，不提前引入 Foreground Service。

第 49 阶段取得更长的正式 Worker 成功证据：Redmi 上同一 ScheduledTask/WorkRequest/Workflow Run 顺序完成 8 个 SAFE 步骤，总耗时约 62.2 秒；8 个 Agent Run 均为 `COMPLETED`，工具结果全部 `success=true / PASSED`，没有系统重试或复制 Run。先行样本运行约 49 秒，在第 6 步因模型没有调用 `memory.search` 而安全失败，后两步正确取消，同样没有复制执行。最新 LMK probe 显示 `supported=true`、6 条历史退出全部是本轮 instrumentation `FORCE STOP`、`REASON_LOW_MEMORY=0`；仍未取得 Android 自主 LMK，不引入 Foreground Service。

第 50 阶段完成停止异常后的持久化重对账：Workflow 仍活动的 `RUNNING` 任务收到停止请求时，先在 Room 原子写入非终态 `STOP_REQUESTED`，再取消 WorkManager；即使系统取消和即时 fallback 同时异常，停止意图也不会丢失。Worker 重入、启动恢复和停止兜底都识别该栅栏，当前进程注册表只保护真正 `RUNNING` 的链；Workflow/Task 在同一 Room transaction 重新读取栅栏并原子结算，迟到成功只能收敛为 `Workflow=CANCELLED / Task=CANCELLED`，也不会追加成功会话结果。停止发生在 Agent Run 尚未关联的窗口时，Workflow 重对账先读取其唯一 ScheduledTask 关联并按 `STOP_REQUESTED` 取消整条链，不进入“关联 Agent 缺失即失败”的通用恢复分支。停止 fallback 同样通过原子 API 一次结算 Workflow/Task；若接管前 Workflow 已有持久终态，则直接把活动 Task 映射到该事实，不再被通用停止栅栏改写。若 Workflow 在停止事务前已经持久化终态，则停止请求不伪造新栅栏，而是把半结算 Task 对账到该既有终态。周期任务在停止请求对账完成前不物化下一实例。实现不复制 Run、不恢复旧执行栈、不引入 Foreground Service，`STOP_REQUESTED` 复用现有 TEXT 状态列，Room v27 Schema 不变。

第 51 阶段开始完善验证事实不完整时的恢复证据：v19 及更早 event fallback 仍允许读取带稳定 ToolCall ID 的 typed 结果和验证，但 `tool.verify` 缺少 ID 时固定返回无效，不按工具名或顺序伪造关联；重试证据因此保守进入 `EVIDENCE_INCOMPLETE`。Redmi `ApplicationExitInfo` 基线为 `supported=true / exits=2 / lowMemory=0 / fallbackSigkillCandidates=0`，两条退出分别来自 instrumentation 启动的 `FORCE STOP` 与安装包，不是自主 LMK，仍不引入 Foreground Service。

第 52 阶段完成恢复证据指纹：启动收敛在写入 `run.recovered` 前对工具账本与非恢复 typed event 计算 canonical SHA-256，并将摘要写入 `retryEvidenceFingerprint`；确认弹窗同时冻结证据码和指纹，提交前二次计算，新增合法 ToolCall、替换参数/回执或验证事件漂移即使分类码仍为 `COMMIT_UNKNOWN` 也拒绝旧确认并升级为 `EVIDENCE_INCOMPLETE`。旧 Recovery 已带证据码但缺少指纹时按证据不完整处理，不恢复旧执行栈。新增 2 条 JVM 指纹漂移测试；完整门禁为 408 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 53 阶段完成持久化边界故障注入：`AgentRuntimeFaultInjector` 现在可以分别在 ToolResult 事件写入后、预算快照写入后和 `tool.verify` 事件写入后模拟进程消失。Result 已落库但预算快照缺失时，`AgentRunResumePolicy` 固定返回 `EXECUTION_BUDGET_INVALID`，不把已提交回执当成可原地恢复；验证事件已落库但验证 Step 尚未收尾时，只恢复控制面，不重复 Executor、ToolResult 或 `tool.verify`。新增 Runtime JVM 契约，完整门禁为 409 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 54 阶段完善模型/网络异常边界：规划请求出现带 telemetry 的响应异常时，先追加失败请求遥测再写入已消耗执行预算；没有统一 telemetry 的网络/网关异常也会冻结预算后进入失败终态。总结请求网络失败不再把已经成功验证的工具 Run 改判为失败，而是持久化失败预算、写入 fallback 事件并使用本地可信回复完成 Run。Receipt 回读失败仍保留 typed `RecoveryFailure`、`COMMIT_UNKNOWN` 和需确认重试，不重放旧写入。完整门禁为 411 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 55 阶段新增 `llm.request.failed` typed 事件：Runtime 将 `ApiFailure` 的鉴权、地址、限流、模型、超时、DNS、TLS、连接、响应和未知错误映射为稳定 `AgentLlmFailureKind`，未知未来枚举降级为 `UNKNOWN`。规划与总结网络异常都保留预算审计，流式断流沿用同一连接错误分类；任务事件区展示阶段、错误码和原因，不保存请求正文。完整门禁为 413 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 56 阶段完成部分流式 delta 的用户可见收敛：普通对话在已经显示部分正文后发生断流时，保留正文但把 assistant 消息写为 `finishReason=failed` 并展示“内容不完整”，同时追加独立失败气泡；用户取消同样进入终态，不再显示“接收中”。失败/取消的部分 assistant 不进入下一轮模型请求或会话摘要，避免残缺结论被再次放大。新增真实 socket 断流、消息终态和上下文资格测试；完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 57 阶段完成取消时的预算写回收敛：Runtime 在新 Run、审批恢复和受限恢复的取消出口统一进入 `NonCancellable`，先追加最新单调执行预算快照，再取消活动 Step、写入 `run.cancelled` 并冻结 Run。后台长任务在模型或工具 finally 已累计时间后被 WorkManager/用户停止时，不再只留下旧预算快照。新增确定性 37ms 取消预算与事件顺序契约；完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。

第 43 阶段历史补充：Redmi 完成一次同一 WorkRequest 的真实 Worker 冷启动重入。旧 PID 在首步 Agent `THINKING` 时被强制终止，新 PID 自动重入并在 `3360ms` 内按 Agent→Workflow→Task 收敛；关联 Agent Run 仍为 1，后续 6 步未执行。由于 instrumentation 前台身份使 `am kill` 无效，本次使用 `run-as kill -9` fallback，因此不把它写成 Android 自主回收。该阶段当时的后续重点是更长/自然系统回收样本和通用未知提交处置；当前进度以第 57 阶段段落为准。

第 44 阶段新增任务中心“需确认”队列：只聚合已结束、可重试且 `AgentTaskRetryPolicy` 判定必须确认的 Run；卡片继续展示统一证据分类、原因和建议，确认提交前继续校验证据码。稳定确认后仍只创建带 `retryOfRunId` 的新 Run，旧 Run、旧模型协程和旧 Executor 均不恢复。该阶段门禁为 394 条 JVM 与仅 Redmi 执行的 127 条 instrumentation。

第 45 阶段新增结构化恢复处置：`AgentRunResumePolicy` 的每个 `RESTART_REQUIRED` 分支都必须携带稳定处置码，启动收敛把恢复类型、策略原因、证据边界和建议动作与重试证据一起写入 `run.recovered` metadata。任务卡、详情顶部和事件区读取同一历史快照；旧事件缺字段时不按当前策略补造。该阶段不增加原地恢复能力，旧 Run、旧模型协程、旧 Executor 和 Workflow 后续步骤仍保持不变。

## 推荐阅读顺序

1. [产品需求](requirements.md)：小灵的产品定位、目标用户、能力边界和质量要求。
2. [个人 Agent 路线图](personal-agent-roadmap.md)：后续功能、技术架构、里程碑、验收标准和暂缓项。
3. [参考项目分析](reference-apps-analysis.md)：`reference-apps` 全量分类、重点项目实现证据和可借鉴结论。
4. [当前实现说明](implementation-notes.md)：现有代码真实具备的能力、模块边界和当前技术债。
5. [验证报告](verification-report.md)：构建、签名、安装和真机启动证据。
6. [本地 Skill 示例](examples/daily-review.skill.json)：可通过系统文件选择器直接导入的 `schemaVersion=1` JSON。

## 维护规则

- 产品方向变化时更新 `requirements.md`，不要新增新的需求快照文件。
- 功能优先级和技术阶段变化时更新 `personal-agent-roadmap.md`，不要按日期复制路线图。
- 新增参考项目时更新 `reference-apps-analysis.md`，结论必须附本地代码或项目文档路径。
- 当前实现发生变化时同步更新 `implementation-notes.md`。
- 每次发布或重要真机验证后更新 `verification-report.md`。
- 历史阶段记录保留当时事实，但必须使用“该阶段当时的边界”等明确措辞；当前状态只以各文档顶部总结和最新阶段为准。
- 项目根目录的 `AGENTS.md` 仅保存本机代理指令并排除 Git 跟踪；不得把其中的凭据复制到文档、日志或提交记录。
- 临时调研产物放在仓库外或未跟踪的 `outputs/`，确认结论后再合并进上述长期文档。
