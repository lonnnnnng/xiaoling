# 当前实现说明

## 第 181 阶段：系统日程稳定身份与权威详情读取（完成）

- `CalendarEventRecord` 新增数值 `eventId`，`AndroidCalendarEventReader` 的 Instances 最小投影加入 `EVENT_ID`。Registry 统一格式化为 `calendar-<Events._ID>`，列表序号仍只用于展示，不参与后续身份解析。
- `CalendarEventReader` 在保留 `listEvents` 唯一抽象方法和既有 lambda 兼容性的同时，增加默认 `getEvent(eventId)`；生产实现通过 `ContentUris.withAppendedId(Events.CONTENT_URI, eventId)` 单条查询，检查 `_ID / DELETED`，并把空行、权限竞态、Provider 不可用和运行异常映射为独立 fail-closed 结果。
- 详情 projection 只有 `_ID / TITLE / DTSTART / DTEND / ALL_DAY / EVENT_TIMEZONE / RRULE / RDATE / DELETED`。`CalendarEventDetailRecord` 不建模地点、描述、参与人、组织者或账户；可空起止时间不会被猜测，RRULE 或 RDATE 任一存在即标记为重复。
- `XiaoLingToolRegistry` 新增前台 SAFE `calendar.get(event_id)`，Schema 与执行入口共同要求规范 `calendar-[1-9][0-9]{0,18}` 且数值可表示为正 `Long`；成功结果只输出稳定 ID 与最小详情。该工具没有审批、回执、重放或 committed-effect verification，也未加入 Legacy 工具集合。
- `calendar-detail` Skill 精确持有 `calendar.search_events / calendar.get`，指令要求唯一匹配后原样传递稳定 ID。聚焦 JVM `XiaoLingToolRegistryTest 63/63 + AgentSkillsTest 26/26`、Debug/AndroidTest APK 与 Redmi Provider 单项通过；测试用临时事件和必要时新建的本地日历均在 `finally` 精确清理。

## 第 180 阶段：答案级长期记忆导航（完成）

- 新增 `MemoryNavigation.memoryIdForNavigation()`，只解析可信 `MessagePart.Tool`。`memory.get` 同时核对严格 `{memory_id}`、结果首行和唯一 `memoryIdsUsed`；`memory.search` 核对可选 `query/limit`、结果头、唯一应用条目与同一稳定 ID。失败、错工具、多结果、非法参数和结果错配均返回空。
- `ConversationActions`、`ConversationPage` 与 Tool 卡增加 `openMemory(memoryId)` 回调和“查看记忆”按钮。按钮只接收解析器返回的 ID；普通模型自由文本仍会在 `AgentMessagePartPolicy` 边界被排除，不能进入 Tool part。
- `XiaoLingViewModel.refreshMemoriesAndResolveNavigation()` 取消旧加载后，在 IO 线程先以 ID `get` 当前 Room，再用 ALL/空查询重建最多 200 条管理列表。目标被置顶并设为 `selectedMemoryId` 后才调用页面导航；不存在时不调用回调，并移除缓存目标、对应选中态和正文。协程取消继续向上传播，不被错误 UI 当作读取失败。
- `MemoryManagementPage` 使用稳定 `LazyListState`，按撤销提示、候选标题/空态/条目和正式记忆标题计算目标索引；候选加载状态也进入 effect key，确保加载中转为空态后重新定位。
- 聚焦 JVM 四个相邻测试类、Debug/AndroidTest APK、Redmi Tool 卡 `1/1`、真实 Room 导航 `1/1` 和文档 corpus `1/1` 通过。真实 Room 测试创建临时记忆，验证存在时置顶选中、删除后阻断且无缓存正文，并在 `finally` 清理。未运行真实 Provider、完整 JVM、Lint、全量 instrumentation 或 Release。

## 第 179 阶段：真实 Provider 长期记忆详情闭环（完成）

- Debug Receiver 新增 `memory_search_get_real`。探针读取手机当前 Provider，创建带专属来源的唯一记忆夹具和只允许 `memory.search / memory.get`、`personal-memory-detail` 的临时 Profile，再通过正式 `AgentRunUseCase + OpenAiCompatibleClient + RoomAgentRunRepository` 执行。
- 验收从 `skill.selected` 解码 Skill ID，并从 Tool Ledger 核对调用顺序、原样关键词、稳定 ID 参数、两项 typed `PASSED`、同一 `memoryIdsUsed`、详情正文边界和零审批；日志不输出 API Key、记忆正文或工具参数。
- 夹具创建已纳入 `try/finally`；历史清理通过管理查询覆盖 ALL 状态，并同时校验专属来源。删除在 Room transaction 中先清 FTS 再删主记录；各清理步骤独立执行，单项失败不会阻断原 Profile 恢复。
- 最终 Redmi Run `run-0b54ba01-5fc2-49bc-95dc-92ab5afd80b6` 严格完成 `memory.search -> memory.get`。覆盖安装后立即触发的首次 Run `run-94e7a078-acb4-4c9b-a317-fb9f9dacc054` 因启动恢复竞态提前终止，但 `finally` 已清理；稳定进程复验成功。
- 第 178 阶段聚焦 JVM `87/87` 冻结通过，Debug/AndroidTest APK 构建成功，更新后的 Redmi 文档 corpus `1/1` 通过。未运行完整 JVM、Lint、全量 instrumentation 或 Release；生产代码、Room v36 和发布基线未改变。

## 第 178 阶段：按稳定 ID 读取长期记忆详情（完成，已于第 179 阶段验证）

- `XiaoLingToolRegistry` 新增 `memory.get(memory_id)`：Schema 要求 43 字符 `memory-UUID`，业务校验与执行入口复用同一格式；执行后只接受 `enabled=true` 且未过期的当前 Store 记录，不存在、禁用或过期统一返回“未找到可用的长期记忆”。
- `memory.search` 保持原有全文、类型和来源输出，并追加每条记录的稳定 ID；`memory.get` 成功结果返回同一 ID、当前正文、类型、标签和来源，同时通过 `memoryIdsUsed` 进入 ToolResult 审计。
- 单次召回关闭策略改为同时过滤 `memory.search / memory.get`；两个执行分支也在读取 Store 前返回关闭提示，避免模型绕过工具清单按 ID 探测。两项工具均保持 SAFE、支持现有后台只读边界。
- 新增独立 SAFE `personal-memory-detail` Skill，要求先 search、唯一命中后再原样传 ID；原 `personal-memory` 工具集合仍为 `memory.search / memory.remember`，`LEGACY_RUN_TOOL_NAMES` 也不加入新工具，因此旧 Profile/Run 不自动获得详情读取能力。
- TDD 首轮 `87` 个聚焦测试中 `7` 个按预期失败；最小实现后同组 `87/87` 通过，`:app:assembleDebug` 成功。Standards 与 Spec 双轴审查均为 0 项；未运行完整 JVM、Lint、AndroidTest、Redmi 或 Release。

## 第 177 阶段：周期计划真实使用与可信答案闭环（完成）

- 新增 `TaskScheduleControlCompletionPresentation` 与共享可信解析策略。策略只接受唯一 `tasks.pause / tasks.resume` execution、`success=true`、typed `VERIFIED`、严格 `{name}`、单行 1 至 100 字符任务名，以及与工具动作一致的应用生成首行；暂停、重复暂停、恢复和重复恢复分别投影为稳定无内部 ID 的会话终态。
- `XiaoLingViewModel` 只在普通前台 Agent 使用上述可信终态；结果与 Agent 摘要写入同一会话快照，并触发既有 `refreshWorkflows()` 重新读取 Workflow、Run、ScheduledTask 与周期规则。Workflow 内调用、失败、未验证、模型伪造或重复控制 execution 不生成终态也不触发刷新。
- `TaskInspectionNavigation` 复用同一可信解析策略，让暂停/恢复 Tool part 使用既有“查看任务”入口。入口只携带任务名；应用壳仍先刷新 Room，再以当前唯一精确名称解析 Workflow ID，删除、重命名、缺失或同名时降级为通用列表。
- Debug-only `task_schedule_control_real` 通过生产 `RoomWorkflowRepository + WorkManagerScheduledTaskScheduler + AgentRunUseCase` 创建真实 DAILY 夹具，使用临时 `task-schedule-control` Profile 运行暂停和恢复双 Run，并核对审批、typed Tool Ledger、规则/Task/WorkRequest 状态、旧 Task 不变和唯一未来实例。`finally` 取消残留工作、停用夹具并恢复 Profile。
- TDD 红灯因新 presentation/refresh seam 尚不存在而编译失败；最小实现与共享策略收口后，`TaskScheduleControlCompletionPresentationTest 4/4 + TaskScheduleControlWorkflowRefreshPolicyTest 2/2 + TaskInspectionNavigationTest 7/7 = 13/13` 通过，Debug/AndroidTest APK 构建成功。Redmi 真实 Provider 双 Run 与文档 corpus `1/1` 通过并完成清理；未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。

## 第 176 阶段：应用内周期计划暂停/恢复（完成）

- `XiaoLingToolRegistry` 新增 `tasks.pause / tasks.resume`，使用 `{name}` Schema、`REQUIRES_APPROVAL / EXECUTOR_VERIFIED / IDEMPOTENT_BY_KEY`，并在工具清单、definition 和执行入口三处限制为前台直接 Agent。独立 `task-schedule-control` Skill 不修改既有任务 Skill。
- `RoomWorkflowRepository.pauseWorkflowSchedule()` 在事务内核对 schedule、workflow、next Task 身份和活动状态；仅 `SCHEDULED` 实例进入 `CANCELLED`，`RUNNING / STOP_REQUESTED` 保持原执行链。规则行改为 `enabled=false` 并清空未来指针，Worker 完成后因规则停用不会继续物化。
- `resumeWorkflowSchedule()` 要求暂停规则没有残留指针，按原 DAILY/WEEKLY 墙上时间和时区从当前时间计算下一实例，复用 schedule ID。Store 通过 `ScheduledTaskScheduler` 入队、关联并回读 WorkRequest；重复恢复只有在现有活动 Task 与系统关联完整时才返回已恢复。
- `rollbackWorkflowScheduleResume()` 处理 Room 提交后系统入队/关联失败：只有规则仍精确指向本次新 Task 时才把 Task 标为失败并把规则恢复为暂停，避免覆盖并发暂停或留下假活跃状态。规则指针缺失、终态、跨规则/Workflow 或无 WorkRequest 均 fail-closed。
- `AgentTaskRecord/InspectionRecord` 增加独立周期计划启停投影，避免把工作流启用误解为周期仍活跃；内部 Schedule/Task/Run/WorkRequest ID 不进入工具文本。Room Schema 保持 v36。
- TDD 聚焦 JVM 为 `AgentSkillsTest 24/24 + XiaoLingToolRegistryTest 58/58 = 82/82`；Debug/AndroidTest APK 构建成功。仅 Redmi 定向运行 `RoomAgentTaskStoreInstrumentedTest`，首次夹具因未把 Run 从 QUEUED 推进到 RUNNING 得到 `12/13`，修正夹具后为 `13/13`；更新后文档 corpus gate 为 `1/1`。

## 第 175 阶段：受控系统日程创建（完成）

- 新增 `CalendarEventWriter` seam 与 `AndroidCalendarEventWriter`。写入前查询可贡献且保存事件的日历；无目标时以 `ACCOUNT_TYPE_LOCAL` 创建固定身份的本地“小灵”日历，不把账户字段送入 ToolResult。
- `calendar.create_event` 只接收标题、带 UTC 偏移的 ISO-8601 起止时间和 IANA 时区，业务校验同时拒绝时间倒置与时区偏移漂移。工具为 `REQUIRES_APPROVAL + EXECUTOR_VERIFIED + IDEMPOTENT_BY_KEY + CONTROLLED_SAME_CALL`，只支持前台。
- ToolCall ID 通过 `CUSTOM_APP_PACKAGE + CUSTOM_APP_URI` 落入事件，解决 Provider 写入后进程中断的精确恢复；同一键载荷不一致返回冲突。首次写入和已提交恢复都按事件 ID 回读标题、起止时间、时区及标记，不能按标题/时间猜测已提交。
- 日历设置页保留独立只读按钮，并增加创建授权按钮；创建按钮申请读写权限，因为选择目标日历和回读验证都需要读权限。旧 Profile、历史 Run 和原只读 Skill 不自动扩权；Manifest 仅新增 `WRITE_CALENDAR`，Room Schema 不变。
- TDD 聚焦 JVM 为 `XiaoLingToolRegistryTest 56/56 + AgentSkillsTest 23/23 + LegacyRunToolBoundaryTest 2/2 + ToolExecutionRecoveryEvidencePolicyTest 3/3 = 84/84`；Debug/AndroidTest APK 构建成功。Redmi 空日历表首先稳定返回 `NoWritableCalendar`，加入官方 LOCAL 日历兜底后，真实创建、同调用重放、提交回执验证、精确事件/临时日历清理和权限页共 `3/3` 通过；最终文档 corpus gate `1/1` 通过。

## 第 174 阶段：个人事项简报（完成）

- `BuiltInAgentSkillRegistry` 新增 `personal-briefing`，声明工具集固定为 `calendar.list_events / tasks.list / notes.search / notes.get`，required permission 继续是 `READ_CALENDAR`，risk 为 SAFE。组合关键词只针对“个人简报 + 相关笔记”目标增强评分，普通 `day-overview` 仍只有日历和任务两项工具。
- 现有 `AgentSkillCatalog` 和 `SkillScopedToolRegistry` 继续负责 Profile/Skill 显式白名单与实际工具面收窄；新 Skill 没有 Registry 执行分支、Room 表、审批或后台旁路。单 Run 最多四次调用的既有 Runtime 上限保持不变。
- Skill 指令要求先读取日历与任务，再以用户明确关键词搜索笔记并用唯一稳定 ID 读取全文；失败恢复按来源分区，不能把搜索预览、模型文本或历史对话提升为完整笔记事实。
- Debug-only `personal_briefing_real` 使用固定幂等键创建可精确清理的唯一笔记夹具和临时 Profile，从 `skill.selected`、Tool Ledger、参数、typed 结果与最终回答核对四工具顺序、未来 1 天窗口、关键词、稳定 ID、全文尾标、数据边界、零审批和三类来源分区。finally 恢复原 Profile 并硬删除夹具，API Key 和正文不进入日志。

## 第 173 阶段：版本化本地笔记编辑闭环（完成）

- `AgentNoteRecord`、Room `agent_notes` 和所有 list/search/get 输出增加 `revision`。`MIGRATION_35_36` 为旧行补 `revision=1`，并新增 `agent_note_edit_operations`；Schema 36 已导出，迁移测试同时核对旧正文、版本默认值和空 operation 账本。
- `AgentNoteDao.updateNoteIfRevisionMatches()` 以 `id + expectedRevision + 非 tombstone` 条件更新标题、正文、时间并原子递增版本。`RoomAgentNoteStore.update()` 在同一事务回读结果并写 operation；请求和结果采用长度前缀 canonical SHA-256，避免字段拼接歧义。
- 成功提交的 operation 以 ToolCall ID 为幂等键。同一 call/载荷回放只验证并返回原结果，载荷、note ID、revision 或当前内容漂移均拒绝；标题和正文均未变化时返回未编辑且不伪造 operation。已提交恢复调用 `verifyUpdateOperation()` 只读核对 operation 与当前笔记哈希，不再次执行 UPDATE。tombstone 删除也递增 revision，不能通过旧 revision 编辑复活。
- `XiaoLingToolRegistry` 新增仅前台、`REQUIRES_APPROVAL / EXECUTOR_VERIFIED` 的 `notes.update`，要求完整 `note_id / expected_revision / title / content`，并使用受控同调用重放。独立 `local-note-update` Skill 固定“定位唯一目标 -> 读取全文和 revision -> 审批更新”链路，旧 Profile、Skill 和 legacy Run 工具集合保持不变。
- `LocalNoteManagementViewModel/Page` 增加版本化编辑状态、五行正文编辑器、保存/取消和冲突后最新版本回载。Debug-only `notes_update_real` 从 Room Tool Ledger 核对 `notes.search -> notes.get -> notes.update`、审批、稳定 ID、revision `1 -> 2`、operation 验证和历史创建阻断，并在 finally 精确清理夹具与临时 Profile。

## 第 172 阶段：Agent 受控删除本地笔记（完成）

- `XiaoLingToolRegistry` 新增仅前台、`REQUIRES_APPROVAL / EXECUTOR_VERIFIED` 的 `notes.delete(note_id)`。Schema 与业务校验共同要求标准 41 字符 `note-UUID`，执行前还从当前 Store 确认目标存在；不存在与 tombstone 统一失败。
- 删除只通过 `AgentNoteManagementStore.delete()` 进入 Room tombstone 事务，清空标题/正文并保留 ID 与原创建幂等键。成功结果绑定当前 ToolCall、note ID 和 `COMMITTED` 回执，随后回读必须不可见；历史 `notes.create` 继续因 tombstone 抛错，不能恢复正文。
- 删除以稳定 note ID 作为幂等目标，但 `notCommittedReplayPolicy` 保持 `DENY`：只有持久化提交回执存在时，恢复链才按同一 call/operation/idempotency/note ID 回读验证，不再次调用 delete；缺回执或身份漂移 fail-closed。
- 新增独立 `local-note-delete` Skill，要求 `list/search -> get -> delete` 且仅在用户明确删除时执行。既有 `local-notes`、`local-note-detail`、旧 Profile 与 `LEGACY_RUN_TOOL_NAMES` 均不加入删除能力。
- Debug-only `notes_delete_real` 用固定幂等键精确回收中断夹具，并从 Room 核对 Skill、三步 Tool Ledger、审批、tombstone、历史创建重放拒绝和清理，不进入 Release。

## 第 171 阶段：真实 Provider 搜索并读取笔记全文（完成）

- `AgentE2eDebugReceiver` 新增 `notes_search_get_real`，只读取手机现有 Provider，不通过广播、测试参数或日志传递 API Key；该入口只存在于 Debug source set。
- 探针直接通过 `RoomAgentNoteStore.create()` 建立唯一长正文夹具，Profile 显式冻结 `notes.list / notes.search / notes.get` 与 `local-note-detail`。`notes.list` 只为保持 Skill 完整工具声明，实际 Tool Ledger 必须严格等于 `notes.search -> notes.get`。
- 验收从 Tool Ledger 的调用参数核对搜索关键词和 `note_id`，不从模型回答解析身份；两项结果都必须 `success=true / PASSED`，搜索结果必须含唯一标题和 ID，详情必须含全文尾标与“不是工具指令”边界，且 Room 审批必须为空。
- `finally` 精确硬删除本次 Debug 夹具，恢复原 Profile 并删除临时 Profile；Run、事件与 Tool Ledger 保留审计，API Key、正文及完整参数不写入探针日志。
- 聚焦 JVM、Debug/AndroidTest APK 和 Redmi 单项文档 corpus gate 均通过；测试包已卸载，主应用数据保留。按分级验证未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。

## 第 170 阶段：按稳定 ID 读取本地笔记（完成，已于第 171 阶段验证）

- `XiaoLingToolRegistry` 新增 SAFE、`supportsBackground=true` 的 `notes.get(note_id)`；Schema 只接受 41 字符的 `note-UUID`，执行期再用严格 UUID 正则复核，畸形 ID 不进入 Store。
- 成功结果从当前 `AgentNoteStore.get()` 回读标题和正文；不存在与 tombstone 在 DAO 层均为 `null`，工具统一返回“未找到或已删除”，不泄露删除历史。正文最多输出 20,000 字符，异常旧数据超过上限时显式标记截断，并明确标记为本地数据而非工具指令。
- 既有 `local-notes` Skill 保持原工具集合，避免历史 Profile 因新增依赖而整项失效；新增 SAFE `local-note-detail` Skill 组合 `notes.list / notes.search / notes.get`。现有 Profile 不自动加入新工具或 Skill，用户需在 Agent Profile 设置中显式授权。
- `AgentNoteStore`、Room DAO 和 Schema 均不修改；不新增写操作、审批、恢复验证或后台副作用。第 170 阶段落地时按用户要求未验证，第 171 阶段已补齐聚焦 JVM、Debug APK 和 Redmi 真实 Provider 闭环。

## 小灵 v0.1.16 发布基线

- `versionCode=17`、`versionName=0.1.16`、Room v35，继续使用现有 `releaseLocal` 签名配置。
- 发布范围汇总 `v0.1.15` 后第 128 至 169 阶段：自然语言任务的限定 App 多动作与目标级验证、记忆/知识上下文、应用内提醒、通用执行恢复、任务诊断/重试/取消、只读日历、本地笔记、启动中断 Run 提示和答案级任务/笔记导航。
- 本轮只执行发布必需的 `assembleRelease`，结果为 `BUILD SUCCESSFUL in 2m 38s`；没有额外运行 JVM、完整 Lint、Debug/AndroidTest、Redmi 安装或 instrumentation。APK 为 `3,400,350` 字节，SHA-256 为 `971f0c457c3a802d3bb41bd31ac58fda2c1ee0eebbe6f2967ec428299d801126`；[GitHub Release](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.16) 已发布并成为 latest。
- 后台设备自动化、任意 App、生产 answerability enforcement、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续关闭。

## 第 169 阶段：创建笔记后的答案级导航（完成）

- `XiaoLingToolRegistry.createNote()` 与 `verifyCommittedNote()` 的成功结果在原有回读验证和 `ToolExecutionReceipt` 不变的前提下追加 `· id=<noteId>`；失败结果、幂等操作 ID 和载荷冲突语义不变。
- `localNoteIdForNavigation()` 增加 `notes.create` 分支：只接受 `title/content` 完整参数、标题无换行、typed `VERIFIED`、结果首行的标题精确绑定和全文唯一合法 note ID；不会从正文或模型文本猜测对象。
- 既有 Conversation 回调和一次性 `requestedLocalNoteId` 导航目标复用第168阶段实现，点击后仍由 `LocalNoteManagementViewModel`/Store 当前回读，不把 Tool result 当作详情事实。
- 本阶段不修改 MessagePart/Room Schema、审批权限、恢复策略或后台执行能力。

## 第 168 阶段：答案级本地笔记导航（完成）

- 新增 `MessagePart.Tool.localNoteIdForNavigation()` 纯策略：严格限定 `notes.list / notes.search`、成功、`verificationStatus != FAILED`、参数键/范围、`最近笔记：` 或 `匹配笔记：` 首行，以及唯一 `note-UUID` 条目行；结果正文中的任意普通 `id=`、多条结果和空结果均 fail-closed。
- `ConversationActions`、`ConversationPage` 和 `ToolMessagePartContent` 增加“查看笔记”回调；应用壳通过 `XiaoLingNavigationCoordinator/Controller` 保存一次性 `requestedLocalNoteId`，切换到 `LOCAL_NOTE_MANAGEMENT` 时传入 `preferredNoteId`。
- `LocalNoteManagementPage` 以 `LaunchedEffect(preferredNoteId)` 调用既有 `LocalNoteManagementViewModel.selectNote()`，详情仍由 `AgentNoteManagementStore.get()` 当前事实回读，不复用 Tool part 摘要；返回设置时清空导航目标，Activity Saver 只保留一次性目标。
- 本阶段不改消息持久化模型、Room Schema、工具权限、写工具、后台执行或任意 App 边界。

## 第 167 阶段：中断筛选空状态与历史回退（完成）

- `AgentTaskCenterPage` 在 `INTERRUPTED` 筛选为空时显示“没有失败或已取消的 Agent Run”及不会重放工具的稳定说明；存在其他历史时才显示 `agent-task-show-all` 回退按钮。
- 回退只修改页面本地筛选为 `ALL`，不刷新、不执行、不修改 Room；其他筛选仍复用原有泛化空状态。
- `AgentTaskFilterPolicyTest` 增加显式 `ALL` 回退边界；本阶段不触碰 Runtime、Repository 或任务操作权限。

## 第 166 阶段：恢复入口聚焦中断 Run（完成）

- `AgentTaskFilter.INTERRUPTED` 只匹配 `FAILED / CANCELLED`，不依赖重试资格，因此即使某个失败 Run 不可重试，也能在恢复复盘入口中看到。
- `XiaoLingContent` 保存任务中心的初始筛选：启动恢复动作使用 `INTERRUPTED`，设置根手动进入使用 `ALL`；`AgentTaskCenterPage` 只在入口筛选变化时初始化本地筛选，用户后续切换不被刷新覆盖。
- `AgentTaskFilterPolicyTest` 增加失败、取消和执行中反例；本阶段不触碰 Runtime、Repository、Run 状态或后台调度。

## 第 165 阶段：启动恢复提示直达任务中心（完成）

- `OperationResult` 增加可选 `OperationResultAction`；启动恢复策略仅为实际收敛到 `FAILED / CANCELLED` 的提示设置任务中心动作，其他结果保持默认 `null`。
- `XiaoLingApp` 将该动作投影到 `CenterNotice`。按钮只在动作存在时渲染；点击执行 `refreshAgentRunHistory()`、打开 `SettingsPane.AGENT_RUN_HISTORY` 并清除一次性提示，自动消失仍不会导航。
- `StartupRunRecoveryNoticePolicyTest` 增加失败/取消动作断言；本阶段不改变恢复、执行、权限、Room Schema 或后台行为。

## 第 164 阶段：启动中断 Run 用户提示（完成）

- 新增 `settleStartupInterruptedRuns()`，保持 `closeInterruptedRuns()` 为唯一结算入口；只有返回正数后才按冻结候选 ID 逐一回读 `AgentRunRecord`，避免根据启动前 `EXECUTING / VERIFYING` 中间态猜测结果。
- `projectStartupRunRecoveryNotice()` 只统计 `FAILED / CANCELLED`，合并为一个 `OperationResult`；标题和正文不接收目标、Run ID、原始错误或工具正文，并明确旧工具不会重放。
- `XiaoLingViewModel.initialize()` 使用结构化 `StartupAgentRecoveryState` 同时保留三类可恢复 Run 与一次性提示；提示随初始化 UI 状态发布，仍由现有 `CenterNotice + clearResult()` 消费。
- `StartupRunRecoveryNoticePolicyTest 5/5` 通过红绿循环覆盖失败隐私、取消分类、“先关闭、后回读”、无实际收敛和非终态回读五类边界；Debug/AndroidTest APK 与 Redmi 文档 corpus gate `1/1` 通过，未修改 Room、Runtime、Workflow 或设备工具语义。

## 第 163 阶段：取消结果后的任务快照刷新（完成）

- 新增 `shouldRefreshWorkflowsAfterTaskCancel()` 纯策略，复用 `TaskCancelCompletionPresentation` 的可信门禁；只有唯一成功且 typed `VERIFIED` 的应用生成取消结果才返回 `true`。
- `XiaoLingViewModel.sendAgentRun()` 在普通 Agent 会话状态更新并发起保存后调用该策略并触发 `refreshWorkflows()`，刷新内容包含 Workflow、ScheduledTask、周期规则、Run 详情和设备证据投影。Workflow 来源不重复刷新，任务重试路径保持独立。
- 新增 `TaskCancelWorkflowRefreshPolicyTest 4/4`，固定可信取消刷新、未验证、模型伪造和重复 execution 四类反例；连同既有取消终态投影合计 JVM `8/8`。Debug/AndroidTest APK 与 Redmi 文档 corpus gate `1/1` 通过，不新增 Room Schema、工具权限或后台执行。

## 第 162 阶段：取消结果任务中心导航（完成）

- 扩展 `TaskInspectionNavigation.inspectedTaskNameForNavigation()`：保留 `tasks.inspect` 的“任务最近运行”首行门禁，并新增 `tasks.cancel` 的 typed `VERIFIED`、精确任务名前缀和四类稳定取消文案门禁。
- `ConversationMessageContent` 无需新增按钮或导航协议，可信取消 Tool part 自动复用已有“查看任务”按钮；`XiaoLingViewModel.refreshWorkflowsAndResolveInspectedTask()` 继续在当前 Room 快照上按唯一名称解析，内部 ID 不进入历史消息。
- 新增 JVM 正反例：可信计划取消导航、模型样式结果、换行参数注入和未验证状态均覆盖；`TaskInspectionNavigationTest` 共 `5/5` 通过。

## 第 161 阶段：受控任务取消会话终态（完成）

- 新增纯 Kotlin `TaskCancelCompletionPresentation`。它从 `VerifiedAgentContext` 兼容读取多工具/旧单工具投影，要求唯一 `tasks.cancel`、成功、`AgentVerificationStatus.VERIFIED`，再按应用生成的四类稳定结果前缀生成受限文案。
- 任务名在显示前归一化空白并限制 100 字符；投影不回显原始取消结果、内部 ID 或模型文本。未知结果、重复取消 execution、`READABLE_ONLY / FAILED` 直接返回 null。
- `XiaoLingViewModel.sendAgentRun()` 仅在非 Workflow 的普通 Agent Run 中调用该投影，并将结果消息与 assistant 消息一起写入同一会话快照；Workflow 内不会追加任务取消终态。
- 测试覆盖计划取消、后台停止、停止请求、未验证/未知文案、重复调用和单行任务名：`TaskCancelCompletionPresentationTest 4/4`；既有 `TaskRetryCompletionPresentationTest 4/4` 保持通过，`:app:assembleDebug` 成功。

## 第 160 阶段：受控任务取消（完成）

- `AgentModels.kt` 新增 `AgentTaskCancelRecord`、`AgentTaskCancelOutcome`、`AgentTaskCancelResult`，并扩展 `AgentTaskStore.cancel(name, ...)`；取消结果只投影任务名、状态和稳定结果，不携带内部 ID。
- `RoomAgentTaskStore` 按精确名称查询 Workflow 与 ScheduledTask，只有唯一活动实例才继续；同名或多实例 fail-closed。`SCHEDULED / RUNNING / STOP_REQUESTED` 分别复用 `ScheduledWorkflowStopCoordinator` 的取消路径，重复请求读取持久化状态返回 `AlreadyCancelled`。
- `XiaoLingToolRegistry` 注册 `tasks.cancel`：`REQUIRES_APPROVAL`、`EXECUTOR_VERIFIED`、`IDEMPOTENT_BY_KEY`、`supportsBackground=false`，仅前台直接 Agent 可见；`AgentSkills` 保持 `task-cancel` 与 `task-retry` 独立。
- Debug-only `AgentE2eDebugReceiver` 新增 `task_cancel_real` 夹具和严格 Provider 序列，使用正式审批、Room、Tool Ledger 与 StopCoordinator，最终断言 `ScheduledTaskStatus.CANCELLED`、旧 Run 不变并停用夹具/删除临时 Profile。
- 验证：`XiaoLingToolRegistryTest`、`AgentSkillsTest` 聚焦 JVM 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 成功；Redmi `RoomAgentTaskStoreInstrumentedTest` `OK (9 tests)`；真实 Provider 日志 `task-cancel-real success=true ... taskStatus=CANCELLED taskCancel=true oldRunUnchanged=true`。未执行完整 JVM、全量 Lint、Release 或默认完整 instrumentation；测试 APK 未安装。

## 第 159 阶段：受控任务重试用户可见终态（完成）

- 新增纯函数 `presentTaskRetryCompletion()`，只接受用户任务名、`WorkflowRunStatus` 和复用步骤数。它为 `COMPLETED / BLOCKED / FAILED / CANCELLED` 生成稳定文案，对 `QUEUED / RUNNING` 返回空，不接收内部 ID 或原始错误。
- `XiaoLingViewModel.startCommittedTaskRetryIfPresent()` 继续在可信 Room Tool Ledger、typed `PASSED`、提交回执和 `TaskRetryLaunchPolicy` 全部通过后接管关联 Run；完成、取消和失败分支都使用 Repository 已收敛状态调用同一投影，并在原会话追加 `AGENT_RESULT` 消息。
- 复用步骤数只统计带 `reusedFromStepId` 的 `SKIPPED` 步骤。成功摘要明确旧运行不变；阻塞、失败和取消说明不会恢复或重放旧执行栈并引导任务中心。原始异常只留在本地 Workflow 审计和 `workflowError`，不进入最终摘要。
- Debug-only `task_retry_real` 复用第 158 阶段真实 Provider 夹具，在新 Run 完成后调用同一生产投影，断言完成文案、旧 Run 不变以及新旧 Run ID 均未泄漏；最终日志新增 `completionVisible=true`。
- `TaskRetryCompletionPresentationTest 4/4 + TaskRetryLaunchPolicyTest 2/2 + XiaoLingToolRegistryTest 44/44 = 50/50`，Debug APK 构建成功。Redmi 真实链再次通过并清理临时 Profile/夹具；AndroidTest APK 仅为文档 corpus gate 构建，未运行默认完整 instrumentation、完整 JVM、全量 Lint 或 Release。

## 第 158 阶段：真实 Provider 受控任务重试闭环（完成）

- `AgentE2eDebugReceiver` 新增 `task_retry_real`，读取当前 Provider 后创建带两个已完成步骤、最终故意失败的 Debug-only Workflow 夹具；夹具不调用生产工具制造副作用，完成后仅停用并保留审计。
- 探针使用正式 `AgentRunUseCase`、`XiaoLingToolRegistry`、`RoomAgentRunRepository` 和临时 `task-retry` Profile，让真实模型严格完成 `tasks.list -> tasks.inspect -> tasks.retry`。`DebugRoomApprovalGate` 将 `tasks.retry` 审批写入 Room 并返回批准。
- 真实 Agent Run 完成后，探针调用生产 `TaskRetryLaunchPolicy.resolve/canStart`，再从 `RoomWorkflowRepository` 回读关联新 Run；新 Run 只把首个成功前缀标为 `SKIPPED`，随后用真实 Provider 执行第二个 `app.current_time` 步骤，再由 Repository 完成目标级收敛，不重放成功前缀。
- `AgentRunUseCase` 在构造 Profile-scoped Registry 前按当前 invocation context 裁剪冻结工具白名单；`tasks.retry` 在 Workflow context 被隐藏时不会再被误判为 Profile 引用了未注册工具，关联 Workflow 可以继续使用其他已授权工具。
- 结束时断言来源 Run 与步骤快照完全不变、`retryOfWorkflowRunId` 正确关联、关联 Run 最终完成，并恢复原 Profile、删除临时 Profile、停用夹具 Workflow。首次运行暴露的 Workflow Profile 隐藏工具接线问题已修复，最终 Redmi 运行成功。
- 定向 JVM、Debug/AndroidTest APK 和 Redmi 文档 corpus gate `1/1` 通过；没有运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 157 阶段：受控任务重试（完成）

- `AgentSkillCatalog` 新增独立 `task-retry` Skill，工具集合为 `tasks.list / tasks.inspect / tasks.retry`；`tasks.retry` 在 `XiaoLingToolRegistry` 中声明为 `REQUIRES_APPROVAL`、`EXECUTOR_VERIFIED`、`IDEMPOTENT_BY_KEY`，只允许前台直接 Agent。
- `RoomAgentTaskStore.retry()` 委托 `RoomWorkflowRepository.retryLatestRunByTaskName()`，事务内精确匹配任务并读取当前最新 Run。确定性 Run ID 由 `tasks.retry:<ToolCall ID>` 派生；重复 ToolCall 只接受仍为最新 `QUEUED` 且步骤全为 `SKIPPED / PENDING` 的提交，否则稳定拒绝。
- 重试策略复用 `WorkflowRunRetryPolicy`。新 Run 继承成功前缀为 `SKIPPED`，记录 `reusedFromStepId`；其余步骤为 `PENDING`，并保留来源 Run 关联。来源 Run、步骤、结果和副作用不修改；失败正文仅在本地审计保留。
- `AgentRunUseCase` 在计算可用工具和创建 Profile/Skill scoped registry 前显式绑定当前 planning context，避免跨 Run 复用的 Registry 残留 Workflow 状态；`XiaoLingViewModel` 从 Room 回读完整 Tool Ledger 与 Workflow detail 后才执行一次前台接管。
- `TaskRetryLaunchPolicy` 只接受 `COMPLETED` Agent Run、单一可信重试调用、成功 typed 验证和一致提交回执；启动条件还要求同会话、`QUEUED`、无 Agent 关联、存在步骤且只含 `SKIPPED / PENDING`。重复启动由进程内 Run ID 集合抑制。
- JVM 四个聚焦类共 `130/130`，Debug/AndroidTest APK 构建成功；Redmi `RoomAgentTaskStoreInstrumentedTest` 全类 `8/8` 通过。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 127 至 159 阶段实现顺序（主线完成，真实任务打磨继续）

## 第 156 阶段：任务诊断答案级导航（完成）

- 新增 `TaskInspectionNavigation.kt`，集中实现可信入口判定与当前 Workflow 唯一名称解析。Tool part 必须为成功且未验证失败的 `tasks.inspect`，参数键严格等于 `{name}`，结果首行为“任务最近运行”；任务名 trim 后需非空且最多 100 字符。
- `ConversationMessageContent` 只在上述条件满足时展示带图标的“查看任务”按钮；`ConversationActions.openInspectedTask(name)` 只传递名称。普通模型文本无法绕过 `AgentMessagePartPolicy` 生成 Tool part，点击路径不调用 `sendMessage()` 或任何任务写工具。
- `XiaoLingApp` 调用 `refreshWorkflowsAndResolveInspectedTask()`，等待 ViewModel 从 Room 重新加载完整 Workflow UI 数据后，再对该最新列表做精确名称 `singleOrNull` 解析。唯一命中时将 ID 作为一次性定位目标打开 `WORKFLOW_MANAGEMENT`；删除、重命名、读取失败或同名时传入 `null` 并展示通用列表，不猜测历史内部 ID。
- `TaskInspectionNavigationTest` 覆盖可信成功、失败/验证失败、伪造结果头、额外参数、错误工具、精确命中、尾部空格、缺失和同名。`ConversationPageInstrumentedTest` 使用真实 `AGENT_RESULT + VerifiedAgentContext` 验证入口回调任务名称且 `sendCount=0`。
- 聚焦 JVM、`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均通过；只在 Redmi `wsvwypiz7xwslvl7` 运行 `ConversationPageInstrumentedTest`，结果 `OK (9 tests)`、`13.33s`。六份长期文档重打包后，文档 corpus 单项为 `OK (1 test)`、`2.777s`；未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 155 阶段：任务最近运行只读诊断（完成）

- `AgentTaskStore` 新增 `inspect(name)`，用 `Found / NotFound / Ambiguous` 显式处理精确名称命中。`AgentTaskInspectionRecord` 只保存任务公开摘要、最近 Run 的状态/触发/时间、稳定诊断枚举和步骤序号/状态，不携带任何内部 ID 或正文证据。
- `RoomAgentTaskStore` 复用 `listWorkflows / latestRunsForWorkflows / runDetail`。同名任务不选最新项；Run 存在但详情缺失时投影 `EVIDENCE_INCOMPLETE`，失败 Run 只按 BLOCKED、失败步骤、Worker stop 或无法分类收敛，不读取 `errorMessage / result / detail / inputSnapshot / outputSnapshot`。
- Registry 注册 SAFE、仅前台的 `tasks.inspect(name)`，格式化任务启停、最近状态、手动/计划触发、起止时间、诊断和步骤状态。`task-overview` Skill 扩为 `tasks.list + tasks.inspect`，提示模型先取清单中的精确名称，不猜测原始错误。
- Debug Receiver 新增 `task_inspection_real`，复用当前 Provider、正式 `AgentRunUseCase` 和只含两项工具的临时 Profile；入口只在 Debug 构建存在，不新增第二套 Runtime，也不修改生产 Profile。
- `XiaoLingToolRegistryTest 41/41 + AgentSkillsTest 16/16 = 57/57`；Debug/AndroidTest APK 成功。Redmi `RoomAgentTaskStoreInstrumentedTest 5/5`（`2.03s`）；真实 Run `run-91db12f3-7b7d-445f-bf19-3a4ef92be06e` 为 `COMPLETED`，`tasks.list -> tasks.inspect` 两项均 `success=true / PASSED`，内部证据字段检查通过。
- 本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；任务修改、取消、重试、后台诊断和原始错误开放继续关闭。

## 第 154 阶段：本地笔记受控删除（完成）

- 新增窄接口 `AgentNoteManagementStore : AgentNoteStore`，只为用户管理页增加 `delete(id)`；`XiaoLingToolRegistry` 仍只依赖原 `AgentNoteStore`，因此生产 Agent 工具面没有新增删除能力。
- `AgentNoteDao.tombstoneNote()` 在原行上把 title/content 清空并推进 `updatedAt`；`getNote/list/search` 统一排除 title/content 同时为空的行，`getNoteByIdempotencyKey` 仍能读取 tombstone。该方案不修改表结构或 Room v35。
- `RoomAgentNoteStore.delete()` 在单一 Room 事务中核对活动笔记、写入 tombstone 并通过活动查询回读不可见状态。`create()` 命中 tombstone 时抛出 `AgentNoteDeletedException`，从而保留历史 ToolCall 幂等边界且不恢复正文。原 DAO `deleteNote(id)` 仅保留给 Debug 临时测试数据清理。
- `LocalNoteManagementViewModel` 新增请求/取消/确认三段删除状态。确认前不调用 Store；提交后先从当前快照移除目标并刷新当前列表/搜索。刷新失败时成功 notice 保持，错误明确为“笔记已删除，但列表刷新失败”。
- 详情弹层新增删除图标入口，确认弹层展示目标标题和不可恢复边界；删除中禁用重复确认与取消。页面测试复用现有两条用例覆盖加载、详情、删除请求、二次确认和关闭。
- 独立复核修正了删除成功后刷新失败会清空其余笔记的回归：失败分支现在保留已移除目标后的快照，测试明确锁定剩余条目可见，并验证删除中确认/取消按钮禁用。
- `XiaoLingToolRegistryTest 40/40`、`compileDebugKotlin / compileDebugAndroidTestKotlin`、Debug/AndroidTest APK 通过；修正后 Redmi ViewModel `2/2`（`0.247s`）、页面 `2/2`（`5.111s`），真实 Room tombstone `1/1`（`0.369s`）。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 153 阶段：本地笔记只读管理入口（完成）

- `XiaoLingSettingsPane` 新增 `LOCAL_NOTE_MANAGEMENT`，设置根通过窄 `SettingsRootActions.openLocalNoteManagement()` 打开独立页面；通用返回逻辑继续把任意设置子页收敛到根页，不增加专用导航状态。
- 新增独立 `LocalNoteManagementViewModel`，生产默认注入 `RoomAgentNoteStore`。初始化读取最近 10 条；非空查询去除首尾空白后执行标题/正文搜索，清空查询恢复最近列表；点击条目再按稳定 ID 调用 `get()`，迟到详情只允许写回仍被选中的 ID。
- `LocalNoteManagementContent` 将标题、返回和刷新固定在列表滚动区外；正文列表只显示两行摘要，详情弹层显示完整正文、创建时间和更新时间。加载、空列表、空搜索结果和读取错误分别投影，不提供编辑、删除或创建控件。
- 读取数量继续沿用 `AgentNoteStore` 的 `1..10` 限制。这是已有 Agent 工具数据暴露边界，不因新增 UI 静默扩大；后续若需要分页，应作为独立设计处理。
- `LocalNoteManagementViewModelInstrumentedTest` 覆盖最近列表、去空格搜索、详情回读和清空恢复；页面测试覆盖搜索/刷新/返回 action 与只读详情；设置根测试覆盖新入口；`RoomAgentNoteStoreInstrumentedTest#listSearchAndGetServeTheReadOnlyManagementPage` 使用真实 Room 覆盖 list/search/get。
- 定向 JVM、`compileDebugKotlin / compileDebugAndroidTestKotlin`、Debug/AndroidTest APK 均通过；Redmi 上上述四组为 `1 + 2 + 5 + 1 = 9` 条全部通过。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 152 阶段：本地笔记写入闭环（完成）

- `AgentE2eDebugReceiver` 新增 `notes_create_real` Debug 入口，读取用户当前 Provider，创建只允许 `notes.search / notes.create / notes.list` 与 `local-notes` Skill 的临时 Profile，并通过正式 `AgentRunUseCase` 执行真实 Provider Agent Run；不创建第二套 Runtime。
- `DebugRoomApprovalGate` 复用 `RoomAgentRunRepository.createApprovalRequest / decideApprovalRequest`，把 `notes.create` 的审批请求和 `APPROVED` 决定写入同一 Run；生产 UI 审批链路不变，Debug gate 不进入 Release。
- 写入由生产 `XiaoLingToolRegistry.createNote()` 完成，继续使用 ToolCall ID 幂等键、Room 事务和 operation 回读。探针再通过 `RoomAgentNoteStore.search()` 按唯一标题核对正文，随后只删除本阶段测试笔记；临时 Profile 在 `finally` 中删除并恢复原 Profile，Run/审批保留审计。
- Redmi Run `run-66b689fb-6ff3-410f-a851-e0f91765047a` 为 `COMPLETED`；`notes.create` 为 `APPROVED / success=true / executorVerified=true / verification=PASSED`，`notes.search` 回读核对成功，清理日志为 `temporaryProfileRemoved=true / testNoteRemoved=true`。
- 为保证 Debug 清理不误删其他笔记，`AgentNoteDao.deleteNote(id)` 只按精确 ID 删除，并新增 Redmi 单项 `debugProbeCleanupDeletesOnlyTheRequestedNote`，结果 `OK (1 test)`。Room 版本仍为 v35，无新增迁移。
- 本阶段完成 `compileDebugKotlin`、`compileDebugAndroidTestKotlin`、`testDebugUnitTest` 定向类、`assembleDebug` 和 `assembleDebugAndroidTest`；未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。

## 第 151 阶段：真实 WorkManager 长任务、熄屏与受控中断恢复（完成）

- `AgentE2eDebugReceiver` 新增 `workflow_long_scheduled / workflow_long_status`。创建入口先恢复上次遗留的探针状态，再以当前有效 Provider 生成只授权 `app.current_time` 的临时 Profile，通过 `createWorkflowAndOneTimeScheduledTask()` 和 `WorkManagerScheduledTaskScheduler.enqueue()` 调度 8 步任务；状态入口只读正式 Room 事实，确认终态时停用 Workflow 并恢复原 Profile。清理明确依赖 Debug 状态查询，创建入口的预清理负责兜住上次未查询终态的残留；Debug 入口不进入 Release，也不持有第二套 Runtime。
- 普通后台样本为 Task `scheduled-task-1684ca82-dfb0-45e7-94a7-7a5908094a92` / Run `workflow-run-f20ecc64-e375-47ba-813d-8516297eb920`，Worker 耗时 `95816ms`；熄屏样本为 Task `scheduled-task-0d5a2c12-b952-40cf-b236-ab121ac06263` / Run `workflow-run-e9aa7e03-8557-451e-972c-af56de8051e0`，耗时 `91915ms`。两者均 `8/8 COMPLETED`；熄屏后半程持续 Dozing，PID 始终为 `8228`，`exit-info` 无新增记录。
- 首次探针 Task `scheduled-task-0e688878-5227-4fc9-8d6e-b986af0d0d18` / Run `workflow-run-07877d2d-4da1-484a-90db-28feba8d5c4c` 因原 Profile 没有注册 `app.current_time` 在 `10212ms` 后失败；失败历史和停用 Workflow 保留。临时最小 Profile 修正的是探针前置条件，不改变生产 Profile 白名单。
- 人工 `force-stop` 样本 Task `scheduled-task-0b0b35d7-e705-46f8-b235-71e786ba1bf1` / Run `workflow-run-b2f58179-839a-4687-ac68-2b2d02687089` 在旧 PID `8228` 至少进入执行后被终止，系统记录 `USER REQUESTED / FORCE STOP`；新进程 PID 为 `9134`。恢复后 Run/Task 均为 `CANCELLED`，步骤为 `4 COMPLETED + 4 CANCELLED`，不调用旧 Executor、模型协程或 Workflow 后续步骤。
- 该中断窗口中已存在 `app.current_time / success=true / PASSED` ToolResult，但 Agent 总结尚未完成。`RoomWorkflowRepository.completeByAgentRunId()` 旧逻辑会把它作为 `verifiedToolNames` 传给 `CANCELLED` 步骤，违反步骤快照约束并使 Worker 重入失败。现在只有 `WorkflowRunStatus.COMPLETED` 才传递已验证工具；其他终态传空列表，独立 Tool Ledger 保持不变。
- `workerReentryClosesOnlyLinkedAgentAndScheduledTaskWithoutCreatingNewRun` 增加成功 ToolResult 夹具，并断言关联 Agent、Workflow Run、当前 Step 与 ScheduledTask 均取消，不创建新 Run，且取消步骤 `outputSnapshot == null`。该单项只在 Redmi 运行，结果 `OK (1 test)`、耗时 `0.851s`。
- 本阶段 `testDebugUnitTest / assembleDebug / assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`；六份长期文档重新打包后，Redmi 文档 corpus 首轮为 `OK (1 test)`、耗时 `2.471s`。未运行 Lint、Release 或默认完整 instrumentation。约 92 至 96 秒和人工 `force-stop` 仍不能替代自然 LMK、主动断网或 5 至 10 分钟真实任务，因此 Foreground Service 继续后置。

## 第 150 阶段：今日安排与提醒总览 Skill（完成）

- `BuiltInAgentSkillRegistry` 新增 `day-overview`，只声明 `calendar.list_events` 与 `tasks.list` 两个 SAFE 只读工具，并要求 `READ_CALENDAR`；Skill 文案要求最终回复分别标明日程和小灵任务事实。
- 该切片不增加工具执行实现、权限、Room 或后台能力；既有 Profile/Skill 白名单仍在 `AgentRunUseCase` 和 `SkillScopedToolRegistry` 两层生效，旧 Profile 不会自动得到新组合能力。
- 聚焦 JVM `AgentSkillsTest 15/15 + XiaoLingToolRegistryTest 40/40` 通过，Debug APK 构建成功。仅使用 Redmi `wsvwypiz7xwslvl7` 的 Debug 验收入口读取已配置 Provider，在同一 Agent Run `run-535a90af-b45c-4b18-8574-0aa4c91e6268` 依次执行 `calendar.list_events`、`tasks.list`；两项 ToolResult 均 `success=true / PASSED`，最终回复通过“日程/任务”来源分区检查。
- 首次 `gpt-5.4-mini` 真实尝试因第二轮返回空工具名失败，Run 保持失败历史；按 `AGENTS.md` 兜底配置恢复后完成闭环。该调试入口只存在于 `debug` source set，不进入生产 APK。

## 第 149 阶段：系统日历标题关键词查找（完成）

- `CalendarEventReader.searchEvents()` 复用最小日历记录接口，在内存中按标题做不区分大小写匹配；搜索最多读取 200 条最小候选，再按用户 `limit` 截断，不引入地点、描述、参与人或账户字段。
- `XiaoLingToolRegistry` 新增 SAFE `calendar.search_events`，参数为 `query / days_ahead / limit`，声明 `READ_CALENDAR` 与 `supportsBackground=false`；结果格式与 `calendar.list_events` 共用，空结果明确说明没有匹配标题。
- 新增独立内置 `calendar-search` Skill，保持 `calendar-overview` 原样，避免既有 Profile 因新工具集合变化被静默扩权；日历写入、后台 Workflow 和静默权限请求继续关闭。
- 聚焦 `XiaoLingToolRegistryTest`、`AgentSkillsTest`、Debug/AndroidTest APK 已通过；Redmi `wsvwypiz7xwslvl7` 的 `AndroidCalendarEventReaderInstrumentedTest` 为 `OK (2 tests)`，覆盖真实 Provider 有界读取与不存在标题空结果。设备没有可安全创建的日程，因此不伪造标题匹配样本。本阶段未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。

## 第 148 阶段：系统日历只读能力（完成）

- `CalendarEventReader` 将系统 Calendar Provider 收敛为最小只读接口；生产实现通过 `CalendarContract.Instances` 在 `Dispatchers.IO` 查询有界时间窗，投影只包含标题、起止时间和全天标记，捕获权限撤销竞态并 fail-closed。
- `XiaoLingToolRegistry` 注册 SAFE `calendar.list_events`，严格校验 `days_ahead` 与 `limit`，声明 `READ_CALENDAR` 和 `supportsBackground=false`；`AgentRunUseCase` 注入 Android reader，测试使用 fake reader。`calendar-overview` Skill 只缩小工具面，不绕过 Profile 白名单。
- 设置根页新增“日历访问”独立入口和 `CalendarAccessSettingsPage`。用户主动点击后才调用 `RequestPermission(READ_CALENDAR)`；页面在 `ON_RESUME` 重新读取授权状态，并明确只读字段、前台边界和 Profile/Skill 显式启用要求。
- 真实 Redmi 证据：`AndroidCalendarEventReaderInstrumentedTest 1/1`、设置页与根入口 `7/7`；默认 Agent 显式启用工具/Skill 后，真实计划只有 `1/1` 步，唯一工具 `calendar.list_events`，返回未来 7 天无日程。
- 真实复现发现计划模型会把“整理并展示”拆成第二个步骤，运行时因此可能调用无关只读工具；`PersonalTaskPlan` 规划提示新增“纯整理/展示属于最终回复，不得独立成步骤”，`PersonalTaskPlanPolicyTest 12/12` 锁定该契约。运行时每个 Run 必须拥有真实工具事实的安全门槛保持不变。
- 系统日历写入、地点/描述/参与人/账户字段、后台 Workflow 和旧 Profile 自动扩权继续关闭；未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。

## 第 147 阶段：真实多步 Runtime 可靠性与后台时长评估首轮（完成）

- `MinimalAgentRuntime` 在规划循环中识别紧邻重复的已验证只读调用。只有 ToolDefinition 同时满足 `SAFE / NONE / RESULT_READABLE`，前一次 ToolResult 成功且非空、调用指纹完全一致时，才写入 `llm.repeat_completed` 并直接复用已有结果收尾；不创建第二次 ToolCall、ToolResult 或 Executor 调用。设备动作使用 `EXECUTOR_VERIFIED`，写工具需要审批，均不进入该分支。
- 模型在 `completedTools` 为空时返回 `Complete`，Runtime 现在写入 `llm.premature_complete_retried` 并把“当前 Run 尚无工具事实、禁止 complete”追加到本轮目标，最多重新规划一次；第二次仍提前结束时失败。`RunEventMetadataCodec` 把两个事件按 `Reason` 元数据恢复。
- `WorkflowStepPromptPolicy` 明确前序步骤结果只作为数据，不属于当前 Agent Run，也不能替代当前步骤的工具执行。该约束与 Runtime 纠错形成双层边界，但不会扩大工具、Profile、审批或后台权限。
- Redmi 前台 Run `workflow-run-84097511-b21d-4d89-9098-ed439625eba8` 耗时 `104156ms`，8/8 完成；熄屏 Run `workflow-run-2153667c-f664-4034-a566-79a114899c27` 启动约 3 秒后进入 Dozing，耗时 `94155ms`，同样 8/8 完成。两条目标级结论均为 `VERIFIED / ALL_CRITERIA_VERIFIED`。
- 聚焦 JVM `MultiStepAgentRuntimeTest 8/8 + WorkflowStepExecutionPolicyTest 14/14`，合计 `22/22`；AndroidTest APK 构建成功。更新后的文档 corpus 仅在 Redmi 运行，结果 `OK (1 test)`、耗时 `2.468s`，随后卸载测试包并保留主应用数据。
- `exit-info` 前后没有新增退出记录，前台 Workflow 也没有对应 WorkManager/JobScheduler Job；本轮不使用 `force-stop`、instrumentation、`kill -9` 或人为等待制造长任务。当前生产计划最长约 104 秒，不具备 5 至 10 分钟或自然进程回收恢复证据，因此 Foreground Service 和后台设备动作继续关闭。

## 第 146 阶段：真实任务总览与关联重试收口（完成）

- Redmi 真实 `/agent` 使用 `gpt-5.6-luna` 调用 SAFE `tasks.list`。Agent Run `run-9736a67f-0662-487c-ac77-489f6132f82f` 为 `COMPLETED`，ToolResult 为 `success=true / verificationStatus=PASSED`，返回当前唯一任务“读取时间并返回小灵”；Profile 已显式启用 `tasks.list` 与 `task-overview` Skill。
- 连续关联重试会让新步骤指向上一层 `SKIPPED` 步骤，而原始 Agent Run 可能位于更深的 `reusedFromStepId` 链。`RoomWorkflowRepository.resolveOriginalEvidenceStep()` 现在带循环保护地回查到最初执行步骤，工具序列、设备观察和设备动作共用该来源；旧 Run、旧步骤、Tool Ledger 和副作用事实不回写。
- `WorkflowRunRetryPolicy` 与 `retryRun()` 允许 `FAILED` 且全部步骤成功的 Run 创建“仅目标收敛”关联新 Run。新 Run 把全部步骤标为 `SKIPPED` 并复用旧输出，`executeForegroundWorkflow()` 不调用模型、不重放设备动作，只重新执行 Repository 的 Tool Ledger 净化和目标级判定；`CANCELLED` 或 `COMPLETED` Run 不获得该入口。
- Accessibility 生命周期按 Android 语义拆分：`onInterrupt()` 只表示反馈被打断，因此仅失效活动窗口引用；只有 `onDestroy()` 才取消审批并 detach Runtime。Redmi 验收确认 UIAutomator 会在审批期间临时销毁、约 1.6 秒后重建服务，因此审批浮层出现后不得调用 UIAutomator，改用固定批准坐标和非 Accessibility 前台观察，Run 收敛后再读取 UI。
- 真实设备动作链 Run `workflow-run-cdaaf42d-aa85-44ef-95a9-9ab972ed8f2d` 保留 `FAILED`，三步为“已复用 / 已完成 / 已完成”，实际包链为 `com.longdev.xiaoling -> com.google.android.deskclock -> com.longdev.xiaoling`。修复后的关联 Run `workflow-run-3e4b422e-d48e-4244-b21a-2668a980fe10` 以该 Run 为来源，三步全部“已复用”，最终 `3/3`、工具顺序 `app.current_time -> device.open_app -> device.back`、最终包 `com.longdev.xiaoling`，目标结论为 `VERIFIED / ALL_CRITERIA_VERIFIED`。
- 聚焦 JVM 生命周期/重试策略、Debug/AndroidTest APK 和 Redmi Room 单项通过；新增 Room 用例覆盖多级复用来源与全部成功步骤的仅收敛重试，最终 `OK (2 tests)`，更新后的文档 corpus 为 `OK (1 test)`。本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 145 阶段：OEM 时钟兼容与三步个人 Agent 闭环（完成）

- `DeviceActionPolicy.launchPackageCandidates()` 只为计算器和时钟生成“请求包优先、同族实现兜底”的候选；`areEquivalentAppPackages()` 由 Accessibility Gateway、Controller、Workflow Safety 和答案级 Decision 共用。该 seam 不推导任意 OEM 包，不改变白名单集合。
- `XiaoLingToolRegistry` 根据冻结步骤意图识别“返回小灵 / 回到小灵”，动作前只保留 `device.back`，首次规划仍只开放 `device.snapshot`。上下文相关 `definition()` 同步拒绝不可见设备动作，防止模型幻觉出的 `open_app` 绕过工具面进入审批。
- 聚焦 JVM 六组 `95/95`、完整 JVM `904/904` 与 Debug APK 通过。Redmi 真实 Run `workflow-run-e1b22a9e-28f9-468a-9046-a5830c0c4f7f` 三步全部完成，实际时钟为 `com.google.android.deskclock`，最终返回 `com.longdev.xiaoling`，Tool Ledger 六条结果均 `PASSED`，目标级 Decision 为 `VERIFIED / ALL_CRITERIA_VERIFIED`。本阶段未运行全量 Lint、AndroidTest APK、默认 instrumentation 或 Release。

## 第 144 阶段：任务/提醒只读总览（完成）

- `RoomAgentTaskStore` 把现有 `WorkflowRecord / WorkflowRunRecord / ScheduledTaskRecord / WorkflowScheduleRecord` 收敛为 `AgentTaskRecord`。DAO 按选中 Workflow 独立查询最新 Run，不使用全局最近 N 条窗口；一次性任务与周期 Schedule 并存时按 `plannedAt` 选最早下次发生项。
- `XiaoLingToolRegistry` 注册 SAFE `tasks.list`，参数只有 `limit=1..10`，输出名称、目标、启停、步骤数、最近 Run 状态、提醒类型与下次时间。`AgentTaskRecord` 本身不携带 Workflow ID，错误详情、步骤输出、Run/Task/Schedule ID 也不进入工具结果。
- 内置 `task-overview` Skill 只声明 `tasks.list`。生产 Registry 明确 `supportsBackground=false`；Profile 仍经 `allowedToolNames + allowedSkillIds` 双重筛选，既有 Profile 与历史 Run 不会因新增定义而静默扩权。用户需在 Profile 中显式启用该工具和 Skill。
- 本阶段只读应用内任务事实，不修改、取消或执行任务，不新增 Android 权限、Room Schema、系统日历、后台 Workflow 或新 Runtime。聚焦 JVM `48/48`、Debug/AndroidTest APK、Redmi `RoomAgentTaskStoreInstrumentedTest 3/3` 和更新后文档 corpus `1/1` 通过。

- 第 127 阶段已在现有 Agent/Workflow seam 上完成自然语言个人任务与可确认临时计划，没有创建第二套 Runtime 或新的宽权限工具面。
- 第 128 阶段已把七项前台设备工具组合为限定 App 多动作任务；第 129 阶段已增加目标级本地验证。单个工具的 `success`、模型自由文本或历史 ref 都不能替代最终目标证据。
- 第 130 阶段首个切片已在 `preparePersonalTaskPlan()` 调用结构化计划模型前接入 `PersonalTaskPlanContextPreparer`。它只为已获准来源并行调用现有 `RoomAgentMemoryStore.search()` 和 `RoomKnowledgeDocumentStore.search()`，随后由 `PersonalTaskPlanContextPolicy` 统一去空、去重并裁剪为每类 3 条、单条 800 字符。
- `PersonalTaskPlanPolicy.prepareRequest()` 继续保持 system/user 两条消息形状，在 system 边界中把记忆和知识冻结为不可信只读事实，在 user 内容中按来源分区。第 137 阶段让消息和 `PersonalTaskPlanContextUsage` 同源返回，确认 UI 只接收使用/省略计数与上下文字节，不接收上下文正文、retrieval ID 或 API Key。
- 上下文读取与模型调用仍属于同一个可取消的计划 Job；检索异常沿原失败路径停止计划，切换会话后的迟到结果继续被 request ID 和 conversation ID 拒绝。无命中时使用空上下文继续生成。
- `PersonalTaskPlan` 的严格 Schema 现要求 `schedule`，未明确要求提醒时固定为 `IMMEDIATE`；`ONCE` 只保存 1 至 10080 分钟延迟，`DAILY / WEEKLY` 保存时分、周几并使用系统时区。解析器只接受真正的 JSON 整数，数字字符串、小数、未归零字段和互相矛盾的模型输出直接拒绝。
- `createWorkflowAndOneTimeScheduledTask()` 与 `createWorkflowAndRecurringSchedule()` 在单事务内写入定义和首个调度实例，但不创建 Manual Run；Worker 到点 claim 时才建立 Scheduled Run。ViewModel 只有在用户确认后才调用这两个入口、WorkManager enqueue 和 attachWorkRequest，入队失败继续复用 `failScheduling()`。
- `PersonalTaskPlanDialog` 只显示规则与非精确语义；应用宿主复用通知权限 launcher。定时计划在解析期拒绝目标包、`device.*` 完成标准和设备最终应用，其余非 SAFE 工具继续由后台拒绝审批门收敛为待处理通知，不增加第二套 Runtime。WorkManager 入队或 Room 关联失败时会按任务 ID 撤销唯一工作，再把调度记录收敛为失败，避免孤立后台任务。
- 第 130 阶段复用现有长期记忆、本地知识、WorkManager 非精确定时和通知能力形成个人上下文与应用内提醒；创建、修改或取消提醒继续需要确定性参数校验和用户确认。
- 第 131 阶段只允许从已验证前缀创建关联新执行，不恢复无法证明的旧模型协程或 Executor，不改写旧 Run 和已提交副作用。
- 第 131 阶段已由现有 `WorkflowRunRetryPolicy`、`RoomWorkflowRepository.retryRun()` 和 Workflow 管理确认入口完成闭环：连续成功前缀映射为 `SKIPPED / reusedFromStepId`，首个未完成步骤及后续步骤重新执行；已启动失败步骤必须二次确认，来源 Run 通过 `retryOfWorkflowRunId` 保持只读关联。
- 第 132 阶段只用 Redmi 验收三条完整用户任务，并在里程碑末尾统一执行完整 JVM、Lint、Debug/AndroidTest APK 和默认 instrumentation；此前阶段只运行与改动直接相关的聚焦验证。Release 不作为默认阶段动作。
- 第 132 阶段最终完成完整 JVM `879/879`、Lint、Debug/AndroidTest APK、三条 Redmi 完整任务和默认 instrumentation `282/282`。`WorkflowDeviceActionDecisionPolicyTest` 与 AndroidTest 的有效夹具已同步生产 `workflow-device-action-safety-v2`，历史/拒绝夹具继续 fail-closed；Room 当前版本常量同步为 35。
- 第 133 阶段在既有 `XiaoLingViewModel -> ConversationProjection -> ConversationPage` seam 上增加 `GENERATING_PLAN / CREATING_TASK / CREATING_REMINDER`，不建立第二套计划状态机。专属进度覆盖普通模型等待提示，停止按钮按当前阶段给出明确语义。
- 第 134 阶段在 `PendingPersonalTaskPlanUiState` 增加轻量 `PersonalTaskPlanGenerationMetricsUiState`。`preparePersonalTaskPlan()` 只把当前单次 `ModelResponseResult` 的调用次数、耗时、TTFB、Prompt 字节数和可空 Token usage 映射到确认前 UI；不进入 Room、RunEvent、Workflow 或 Runtime metrics。格式化沿用已有紧凑时长/字节规则，缺失 usage 明确显示未知，不估算货币成本。
- 第 134 阶段的验证只覆盖计划遥测展示的 JVM、Debug/AndroidTest APK 和 Redmi 两个 Compose 类；本轮 `OK (10 tests)`（`13.583s`）。不重复完整 JVM、全量 Lint、默认完整 instrumentation、文档 corpus 或 Release。
- 第 135 阶段新增 `PersonalTaskTemplate` 纯 UI 数据和三个受控目标。`ConversationPage` 仅在任务模式显示模板菜单，点击后调用既有 `onPromptChange/updatePrompt`，不会触发 `sendMessage()`；模板没有 Room、ViewModel 持久状态或 Runtime 接线，计划与确认链完全复用现有入口。
- 第 135 阶段聚焦 `ConversationProjectionTest`、AndroidTest 编译和 Debug/AndroidTest APK；Redmi `ConversationPageInstrumentedTest` 最终为 `OK (6 tests)`（`9.751s`），更新后的文档 corpus 首轮/证据写回后复验均为 `OK (1 test)`（`2.459s / 2.616s`）。不重复完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 第 136 阶段把 Google 天气 `com.google.android.apps.weather` 作为唯一 App 兼容扩展。包名同时进入 `DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES`、Manifest queries 和 `device.open_app.package_name` enum；不申请 `QUERY_ALL_PACKAGES`，Chrome 及联系人、短信、文件、日历等高敏或边界过宽的候选继续关闭。
- `AgentE2eDebugReceiver` 保留原计算器入口，并把 `runWorkflowOpenApp()` / `WorkflowOpenAppE2eLlm` 参数化为场景 ID、目标文案和目标包。天气使用独立 `workflow_weather_open_app`，但复用同一 snapshot、Room Approval、Accessibility overlay、Tool Ledger、Executor/typed verification、答案级 Decision 和后置包名验证链，不建立宽松旁路。
- Redmi 真实天气链最终为 `APPROVED / executorVerified=true / PASSED / afterPackage=com.google.android.apps.weather / answerDecision=VERIFIED`，并由本地答案策略确认 `open_app` 不产生可复用节点引用。天气页面可能出现用户当前选择的粗粒度位置文本；这只属于用户主动发起的前台观察内容，不扩展为后台采集或新的持久化字段。
- 第 136 阶段聚焦 JVM `64/64`、Debug/AndroidTest APK 和 Redmi 包可见性/模板两个定向单项通过；更新后的文档 corpus 首轮/证据写回后复验均为 `OK (1 test)`（`2.409s / 2.606s`）。审批验收期间不能使用 `uiautomator dump`：它会启动 `UiAutomation` 并中断被测 Accessibility 服务；本轮改用 `dumpsys window` 检测自有 overlay 后立即点击，避免 30 秒 snapshot TTL 过期。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 137 阶段在既有每来源 3 条、单条 800 字符限制后增加 `MAX_CONTEXT_BYTES=8192`。选择器按记忆/知识交替顺序尝试，每次重绘真实标题、编号、文档名和正文后计算 UTF-8 字节；只有完整候选块仍在预算内才接受，后续较短条目仍可进入。知识正文与记忆完全相同时作为省略计数，不重复发送。
- `PendingPersonalTaskPlanUiState` 的使用数量、省略数量和 `contextBytes` 全部来自真正传给 `sendStructuredMessage()` 的 `PersonalTaskPlanRequest`，避免用检索原始数量冒充发送数量。确认弹层只在省略数大于 0 时显示“上下文精简”，并继续显示第 134 阶段真实 Prompt 遥测。
- 第 137 阶段聚焦 JVM `12/12`、Debug/AndroidTest APK、Redmi `PersonalTaskPlanDialogInstrumentedTest` `OK (5 tests)` 和显式 Provider 探针 `OK (1 test)`。真实模型样本使用上下文 `7,264B`、Prompt `11,190B`，记忆使用/省略 `2/1`、知识使用/省略 `1/2`，返回 1 步计划；最终手动 instrumentation 在成功后恢复并回读 Provider，测试包卸载后主应用保持前台。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 138 阶段在 `preparePersonalTaskPlan()` 的取消分支复用 `personalTaskPlanCancellationFailure(goal)`：只要 request ID 仍属于当前会话，就恢复目标、清理计划进度并写入可重试失败卡；会话切换/删除会先清空 request ID，因此旧取消不会污染新会话。该状态映射独立于执行中 Workflow/提醒取消，不改变已创建 Room 事实的收敛策略。
- 第 139 阶段新增 `capturePersonalTaskCommit` 作为确认后创建的统一提交边界。立即任务和提醒创建在 `NonCancellable` 区域内完成 Room 写入并先捕获 Workflow/Run 或调度身份，再回到可取消上下文检查 `ensureActive()`；取消发生在提交交接处时，已有事实进入既有 `CANCELLED`/调度撤销路径，不会误显示为“未创建”或生成重复任务。
- 第 139 阶段聚焦 JVM 为 `PersonalTaskCreationCommitTest 1/1 + PersonalTaskPlanCancellationTest 1/1`，Debug/AndroidTest APK 构建成功；没有运行完整 JVM、全量 Lint、Redmi instrumentation 或 Release。
- 第 140 阶段让 `PersonalTaskFailureUiState` 显式区分 `RETRY_PLAN / VIEW_WORKFLOW`。只有没有持久化任务事实的失败恢复目标并重新生成；立即任务或提醒已经提交后，停止/失败只生成指向工作流管理页的终态提示，不把目标重新放回可发送输入框，也不创建新的 Workflow。
- `ConversationActions.openWorkflowManagement()` 由应用宿主刷新当前 Workflow 列表并打开既有设置子页；Conversation UI 不持有导航控制器或 Room Repository。聚焦 JVM `9/9`、Debug/AndroidTest APK、仅 Redmi Compose `7/7`（`11.939s`）和文档 corpus `1/1`（`3.568s`）通过；未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 141 阶段新增 `PersonalTaskCompletionUiState` 和 `PersonalTaskCompletionPresentation`。`executeForegroundWorkflow()` 结束后返回 Repository 已完成的 Run；只有当前个人任务操作仍属于原会话时，立即任务才把其中持久化 `goalVerificationDecision` 映射为完成卡。`VERIFIED / PARTIAL / INCOMPLETE` 标题来自本地 Decision；没有 Contract 时只能显示普通完成。提醒只有 Room 与 WorkManager 关联都成功后才显示调度创建卡。

## 第 142 阶段：完成结果定向查看 Workflow（完成）

- `PersonalTaskCompletionUiState` 保留对应 `workflowId`。Conversation 完成卡点击“查看任务”时把该 ID 传给 `ConversationActions.openWorkflowManagement(workflowId)`；提交失败但已有任务事实的失败卡仍使用无目标 ID 的通用入口。
- 应用壳把目标 ID 写入 `XiaoLingNavigationState.requestedWorkflowId`，进入 Workflow 设置页时传给 `WorkflowManagementPage.preferredWorkflowId`；返回设置根页或离开子页时清理一次性目标，避免旧任务影响后续导航。
- Workflow 管理列表使用稳定的 `LazyListState`。工作流数据加载后按 ID 定位并滚动到目标项，目标项以 `initiallyExpanded` 自动展开；未找到目标时保持原列表行为。该逻辑只改变导航与展示，不修改 Room、Runtime、权限或执行契约。
- 聚焦 JVM `17/17`、Debug/AndroidTest APK、Redmi `ConversationPageInstrumentedTest + WorkflowManagementPageInstrumentedTest` `OK (18 tests)` 通过；本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 143 阶段：定向 Workflow 导航重建保存（完成）

- `XiaoLingNavigationStateSaver` 同时保存 `requestedKnowledgeDocumentId` 与 `requestedWorkflowId`。Activity 重建后仍能恢复一次性目标，Workflow 列表可以继续执行定向滚动/展开；没有目标时按空值恢复。
- Saver 仍不保存当前 Tab、设置子页或根页面返回时间，避免把暂态页面和退出手势带入重建后的新宿主；既有返回根页清理逻辑保持不变。
- 本阶段只修改导航状态保存边界，不改变 Room、Workflow/Run、Agent Runtime、审批、设备权限或后台执行。
- `ConversationProjection -> ConversationPage` 仍只把完成卡投影为“查看任务”入口；第 142 阶段由 `ConversationActions.openWorkflowManagement(workflowId)` 把目标交给应用壳，页面本身不持有导航控制器或 Repository。`updatePrompt()`、任务模式切换和下一次 `preparePersonalTaskPlan()` 会清除旧完成卡，已提交失败仍保持第 140 阶段的失败卡语义。第 141 阶段聚焦 JVM `13/13`、Debug/AndroidTest APK、仅 Redmi Compose `8/8` 和最终文档 corpus `1/1` 通过；未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 138 阶段聚焦 JVM `1/1`、Debug/AndroidTest APK 和 Redmi `ConversationPageInstrumentedTest` `OK (6 tests)`（`9.791s`）；未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- `PersonalTaskFailureUiState` 保存原目标、标题和具体错误；生成失败、创建失败或持久化完成前取消都会恢复目标。重试回调先用 `failure.goal` 同步输入，再走原 `sendMessage()`，避免输入框漂移改变任务意图。
- 确认后操作使用独立 `personalTaskOperationRequestId` 绑定计划和会话。会话切换先使旧代次失效再取消 Job；已经创建的 Run 由取消分支在不可取消 IO 区收敛，尚未创建时不追加伪执行消息。成功、失败与最终清理只有在请求 ID 和会话仍匹配时才更新可见 UI。
- 通知权限 launcher 以计划 ID 保存等待身份。权限返回前确认/返回按钮禁用；回调清理等待身份后只在当前待确认计划仍是同一 ID 时提交。权限结果本身不改变已确认调度语义，拒绝只意味着系统通知可能不可见。
- 聚焦 `ConversationProjectionTest`、AndroidTest 编译和 Debug/AndroidTest APK 通过；Redmi 解锁后，`ConversationPageInstrumentedTest + PersonalTaskPlanDialogInstrumentedTest` 最终为 `OK (9 tests)`（`12.418s`）。首轮锁屏运行统一没有 Compose 语义树，只作为设备状态诊断，不计入产品结果。
- 三条用户任务分别采用生产边界：真实 Room 记忆/知识检索后生成 ONCE 计划并原子创建任务、实际入队 WorkManager；真实 Accessibility 连续执行设置页滚动/重观察/返回并得到目标级 `VERIFIED`；失败 Run 创建关联新 Run 并只复用已验证前缀。测试 WorkManager 项已撤销，Accessibility 恢复原关闭状态。
- Redmi 当前 ROM 的计算器/时钟由 Google 包名提供；`DeviceActionPolicy` 与 Manifest queries 同时列出 AOSP/Google 两套精确包名，并单独列出 Google 天气。策略仍按包名逐项审批和后置验证，没有使用类别匹配或任意 App 可见性。
- 纯结构拆分、Shadow 扩样、截图/视觉、后台设备控制、任意 App、精确定时、MCP、系统日历、远程 Channel、多 Agent 和本地模型不抢占当前主线。

## 第 129 阶段：目标级本地验证与最终回答约束（完成）

- `PersonalTaskPlanPolicy` 的严格 Schema 新增 `verification.required_tool_names / expected_final_package`。解析器按当前 Profile 工具白名单校验必需工具，并继续把最终包限制在首批允许包；`PersonalTaskPlanDialog` 在执行前展示工具顺序和完成时应用。
- `WorkflowGoalVerificationContract` 保存用户原始目标和确认标准。`RoomWorkflowRepository.createWorkflowAndManualRun()` 在同一事务写入 Workflow Contract 和全部步骤输入快照；后续手动/定时 Run、步骤准备/启动与关联重试只复制冻结快照。非空持久 Contract 解码失败时阻止新 Run，不能静默退化为旧 Workflow。
- Room 升级至 v35，增加 nullable `workflows.goalVerificationContract` 与 `workflow_runs.goalVerificationDecision`；`MIGRATION_34_35` 保持旧记录为 `null`。旧 Workflow 完成后仍没有目标级 Decision，不能从历史 Run 成功状态补造 `VERIFIED`。
- 每个完成步骤的工具名只从同 Run `AgentToolResult` 中 `success=true / verificationStatus=PASSED` 的记录按顺序重建。步骤 output 只增加工具名列表，参数、原始结果、snapshot/ref、节点正文、坐标和 HMAC 继续留在既有 Ledger/Decision 边界；存储值与 Ledger 漂移时事务 fail-closed。
- `WorkflowGoalVerificationPolicy` 以子序列匹配必需工具，允许辅助 snapshot；最终应用取时间最新的观察或动作后 Decision。带冻结工具证据的 `COMPLETED/SKIPPED` 才计入已验证步骤，并输出 `VERIFIED / PARTIAL / INCOMPLETE`。`WorkflowGoalVerificationDecisionCodec` 会拒绝状态、原因、工具前缀、步骤计数或最终应用互相矛盾的记录；用户文案由本地 `renderForUser()` 生成。
- 聚焦 JVM `22/22`、Debug/AndroidTest APK 通过。仅 Redmi 的迁移、Repository Contract/Decision/损坏数据和确认弹层为 `OK (5 tests)`（`3.33s`）；真实 production Registry 多动作日志为 `goalDecision=VERIFIED / finalPackage=com.longdev.xiaoling / privacySafe=true`。文档语料测试会在失败时输出六条黄金查询的实际文档排名；验证报告查询不再绑定历史 `271 tests`，改用稳定职责词后 Redmi 首轮/写回后复验均为 `OK (1 test)`（`2.461s / 2.444s`）。本阶段未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Debug 设备动作 Probe 不再构造支付密码提示、登录密码输入框或硬编码假凭据，只保留中性测试按钮和独立普通文本输入夹具。`AgentE2eDebugReceiver` 在每个前台设备 tracer 结束后统一启动 `MainActivity`；Redmi 首轮观察失败和后续成功多动作复验都自动返回主页面。密码节点过滤继续由纯策略反例覆盖，不再依赖把真机停在密码页面。

## 第 128 阶段：限定 App 多动作连续执行（完成）

- `PersonalTaskPlan` 新增可空 `target_app_package`，非空时只能选择首批允许包；确认弹层展示限定应用。该包随 Workflow 定义、手动/定时 Run 和每个 `WorkflowStepInputSnapshot` 冻结，关联重试继续沿用原目标包，不能被后续配置漂移改写。
- Room 升级到 v34，在 `workflows` 增加 nullable `targetAppPackage`。`MIGRATION_33_34` 只增加列，旧 Workflow 保持 `null`；`34.json` 与迁移一致。这样旧数据不会被猜造成已经获准控制某个 App。
- `WorkflowDeviceActionSafetyPolicy` 升级为 `workflow-device-action-safety-v2`。`open_app` 只能打开冻结目标包；`tap_ref / type_text / swipe` 的动作前后包名必须等于目标包；`back / home` 可以作为从目标包起步的受控离开动作，但下一次非 `open_app` 动作仍会因包名不匹配拒绝。`WorkflowDeviceActionApprovalGate` 的 `open_app` 审批同样绑定目标包。
- `MinimalAgentRuntime` 保留全局重复 ToolCall 指纹，只为紧跟在已完成且已验证设备动作后的 `device.snapshot` 开窄例外；连续 snapshot、重复动作和未验证动作后的刷新仍拒绝。该边界让页面变化后必须取得新 snapshot/ref，而不会放宽重复副作用防护。
- Debug 真实 tracer 在同一 `MinimalAgentRuntime + RoomAgentRunRepository` Run 中执行 `snapshot -> swipe(up) -> snapshot -> back -> Complete`。两次动作均复用生产 Registry、安全策略、Executor/typed 验证和动作后观察；snapshot ID 必须更新，SAFE 动作不创建审批，持久结果不含节点正文或可复用 ref。
- TDD 首轮以 `AgentBudgetExceededException: 检测到重复工具调用：device.snapshot` 暴露刷新缺口；修复后八组聚焦 JVM `92/92`。Debug APK 构建成功；仅 Redmi 的 v33→v34 迁移、目标包持久化、计划确认弹层分别为 `OK (1 test)`，真实多动作日志为 `success=true / actions=swipe, back / verified=2/2 / approvals=0 / freshSnapshots=true / targetPackage=com.android.settings / finalPackage=com.longdev.xiaoling / privacySafe=true`。提交前 Standards/Spec 双轴复审在文档同步后无遗留实现 finding。
- 第 128 阶段本身没有实现目标级判定；该缺口已经由上面的第 129 阶段完成。任意 App、后台/定时设备控制、截图/视觉、坐标、精确定时或 Foreground Service 仍未开放。

## 小灵 v0.1.15 历史发布基线

- `versionName=0.1.15 / versionCode=16`，保持 `minSdk=26 / targetSdk=36`、Room v33 和既有 `releaseLocal` 签名配置。
- 发布范围为 `v0.1.14` 后第 122 至 127 阶段：`device.swipe` 的安全契约、执行期/完成态 evidence、答案级脱敏投影、生产默认接线与 Redmi 限定验收，以及自然语言个人任务、严格结构化计划、确认前零执行和确认后原子创建普通 Workflow/Run。
- 按用户“不要验证，直接发版”的明确要求，本轮只执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleRelease --console=plain`，结果为 `BUILD SUCCESSFUL in 1m 52s`。没有额外运行 JVM、完整 Lint、Debug/AndroidTest APK、签名/zipalign 复核、Redmi 安装或 instrumentation；既有第 126/127 阶段聚焦证据不记作本次发布门禁。
- 发布 APK 为 `outputs/release/xiaoling-v0.1.15.apk`，大小 `3,318,322` 字节，SHA-256 为 `a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`；同名 `.sha256` 使用标准双空格格式。
- 发布提交为 `b42defa06f02000b841ad7688e76edcf8bc8ce55`；annotated tag `v0.1.15` 的 tag object 为 `7ecfd5269a2822feffbbda88cad0f53964f89aac`，解引用到该提交。[GitHub Release](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.15) 已发布并成为 latest，非草稿、非预发布；APK 资产状态为 `uploaded`，远端大小和 digest 与本地产物一致。

## 第 127 阶段：自然语言个人任务与可确认计划（完成）

- `ConversationPage` 新增“对话 / 任务”分段模式。任务模式接受普通自然语言或兼容去除 `/agent` 前缀后的目标，禁止附件，并复用 `AgentLaunchPreflightCoordinator` 冻结当前会话、Profile、Provider、模型、API 模式和工具白名单。旧 `/agent` 直接执行行为保持不变。
- 本阶段计划请求只携带用户目标和 Profile 工具白名单，不注入长期记忆或本地知识正文；这两类上下文仍按路线图由第 130 阶段接入，避免把完整第 127 至 132 阶段的最终主链要求误记为第 127 阶段单独完成。
- `PersonalTaskPlanPolicy` 为任务名和 1 至 8 个步骤定义严格 JSON Schema；`OpenAiCompatibleAdapter` 分别把它映射到 Chat Completions `response_format.json_schema` 和 Responses `text.format`。结构化请求强制关闭流式与 reasoning summary；客户端解析继续校验根/步骤字段集合、JSON 尾随文本、类型、空值及既有 `WorkflowDefinitionPolicy`，避免兼容 Provider 忽略 Schema 后放大输入边界。
- `PendingPersonalTaskPlanUiState` 只保存可展示的目标、步骤、Agent/模型和工具边界；带 API Key 的 `AgentRuntimeSelection` 只留在 ViewModel 私有 `PendingPersonalTaskExecution`。弹层确认前不写消息、Workflow、Run 或工具账本；取消恢复原始目标，切换/删除会话会撤销在途请求或清理待确认快照。
- `RoomWorkflowRepository.createWorkflowAndManualRun()` 在单一 Room 事务中创建 Workflow、步骤定义、手动 Run 和全部步骤快照。确认后先写入原始任务消息，再调用既有 `executeForegroundWorkflow()`；确认计划作为普通 Workflow 保留，继续使用当前 Runtime、逐动作审批、验证、重试和 Room Ledger。
- 聚焦 JVM 四类合计 `34/34`，`assembleDebug / assembleDebugAndroidTest` 成功。Compose 与 Room 新增单项只在 Redmi `wsvwypiz7xwslvl7` 合并运行为 `OK (2 tests)`、耗时 `2.03s`。旧传递依赖 Espresso `3.5.0` 在当前 Android 上反射隐藏 `InputManager.getInstance()` 失败，显式升级 `espresso-core` 到 AndroidX 稳定版 `3.7.0` 后通过。
- 真实模型生成 `Read Current Time -> Determine the current system time` 单步计划并展示审批/工具边界。确认后原子创建 Workflow `workflow-baa42c6e-6723-4739-aa27-ec6ceb0b67ee`；首个 Run 在 Runtime 模型规划 `60000ms` 超时后保持 `FAILED / BUDGET_EXHAUSTED`，同一 Workflow 的第二个手动 Run 独立 `COMPLETED`，依次完成模型选择 `app.current_time`、参数校验、工具执行、后置验证、完成规划和安全总结，结果为 `2026-08-04 00:47:10 · Asia/Shanghai`。这证明旧 Run 不被重试覆盖。
- 七份长期文档重新打入 AndroidTest assets 后，仅在 Redmi 运行项目文档语料单项，首轮为 `OK (1 test)`、耗时 `2.453s`；写回首轮证据后的最终资产已以相同步骤复验通过。
- 本阶段按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation；没有开放后台设备控制、任意 App、新工具权限、精确定时或 Foreground Service。

## 第 126 阶段：`device.swipe` 生产默认 Registry 与 Redmi 真实链（完成）

- `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 已加入 `device.swipe`，前台手动 Workflow 生产工具面精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。构造参数仍只能取 `SUPPORTED_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 子集，其他已注册设备工具不能借注入扩大权限。
- `swipe` 继续属于 `SAFE_NO_APPROVAL` 和 ref 动作集合。Registry 在执行前仍要求同 Run 已验证 snapshot、30 秒 TTL、当前 window generation、实时 ref、启用且未脱敏的 `SWIPE` 目标和动作前匿名 viewport；执行后只接受与本次授权前后 snapshot 精确绑定、通过同窗内容变化和共同锚点方向验证的瞬态 evidence。Executor 验证、typed `PASSED`、动作后观察和严格 `device.swipe -> action=swipe` 答案级判定继续是硬门禁。
- Debug `workflow_swipe` tracer 改用默认生产 Registry，不再显式注入测试动作集合；同时从真实 Room Tool Ledger 重建 `WorkflowDeviceActionDecision` 并核对提示词只包含脱敏“已执行并验证 滚动”。Result 和答案层都断言不包含 snapshot/ref 或 64 位 HMAC；方向、viewport/HMAC、节点正文、坐标和可复用节点身份仍不进入 Room、Workflow output、日志或 UI。
- TDD 首轮生产工具面断言按预期失败；加入默认集合后 `XiaoLingToolRegistryTest` 为 `36/36`，六个相邻测试类合计 `101/101`，0 failure/error/skipped；`assembleDebug / assembleDebugAndroidTest` 成功。Debug 主包与 AndroidTest APK 只覆盖安装到 Redmi `wsvwypiz7xwslvl7`。
- Redmi 真实生产日志为 `workflow-swipe-e2e success=true action=swipe verified=true approvals=0 registryCompletion=PASSED answerDecision=VERIFIED beforePackage=com.android.settings afterPackage=com.android.settings privacySafe=true afterNodes=36`。七份长期文档重新打入 AndroidTest assets 后，仅在 Redmi 运行项目文档语料首轮/最终单项均为 `OK (1 test)`，耗时 `2.307s / 2.3s`。测试包已卸载，主应用最终为 `0.1.14 (15)` top resumed，Accessibility `Enabled / Bound / Crashed services:{}`，crash buffer 无小灵异常；未使用模拟器。
- 本阶段不新增 Room schema，不开放后台/定时设备自动化、恢复自动续跑、任意 App、截图或坐标。按分级验证未运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 小灵 v0.1.14 发布基线

- `versionName=0.1.14 / versionCode=15`，保持 `minSdk=26 / targetSdk=36`、Room v33 和既有本地正式签名证书。
- 发布提交为 `909ea4d52d857d2a913f2e550a5581bbdb37d3fe`；annotated tag `v0.1.14`（tag object `6107c92b39062f2527b753f4ed4cb13ec0ab7c6d`）解引用到该提交，[GitHub Release](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.14) 已发布且非草稿、非预发布。远端两项资产均为 `uploaded`，APK digest 与本地 SHA-256 一致。
- 发布范围为 `v0.1.13` 之后 31 个工程提交，并由本次版本与文档提交封版：通用执行恢复矩阵、提交状态未知分类、用户确认的受控安全重放、失败 ToolResult/typed 验证的原子终态结算、answerability Shadow 跨进程匿名账本与单次采样窗口，以及前台 Workflow `snapshot / open_app / back / home / tap_ref / type_text` 生产闭环。
- 恢复路径继续以 Room 持久化事实和幂等 marker 为准，不恢复旧 Executor、旧模型协程或 Workflow 后续步骤；旧 Run 保持不变。`swipe`、后台设备自动化、任意 App、JSON/SAF 和生产 answerability enforcement 继续关闭。
- 强制发布门禁为 Gradle `141/141` tasks（`4m 40s`）、JVM `837/837`、`0 failure / 0 error / 0 skipped`；Lint `0 error / 56 warnings / 0 information`；Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 和 v2 正式单签名通过。Release APK `3,301,938` 字节，SHA-256 `927579c852ab272a08bd82412821ea7779fb57363f67598660e50a1017e2fc6a`。
- 仅 Redmi `wsvwypiz7xwslvl7` 默认完整 instrumentation 为 `OK (271 tests)`、耗时 `121.242s`；显式 Provider/Embedding 参数缺失的联网探针按设计跳过，没有向 Pixel_9 或其他模拟器发送 ADB 命令。
- 最终 README/docs 重新打入 AndroidTest APK 后，只在 Redmi 运行项目文档语料单项为 `OK (1 test)`；黄金查询已同步到当前 `271 tests` 基线且没有放宽 6/6 召回要求。
- Redmi 原 Debug 包与正式证书不同，无损覆盖按预期返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；按项目授权卸载测试包与 Debug 主包后安装正式 Release，因此原应用数据已清除。最终冷启动 `610ms`，设备报告 `0.1.14 (15)`，`MainActivity` 为 top resumed、主进程存活，测试包不存在，Accessibility 为 `Enabled / Bound / Crashed services:{}`，`stay_on_while_plugged_in=15` 保持不变，清空后 crash buffer 为空。

## 第 125 阶段：`device.swipe` 答案级脱敏 Decision 与 Room/UI 投影（完成，生产未开放）

- `WorkflowDeviceActionDecisionPolicy` 的工具/动作映射新增 `device.swipe -> swipe`。只有同 Run `success=true`、`executorVerified=true`、typed `PASSED`、严格 Result codec 且 action 一致时才生成本地判定；伪装为 `tap_ref` 或额外字段继续 fail-closed。Registry 已在 typed `PASSED` 之前完成瞬态同窗方向 evidence 门禁，答案层不复制或持久该 evidence。
- `WorkflowStepSnapshotCodec` 的严格动作集合允许 `swipe`，继续复用既有 `workflow-step-output-v1` 与 `WorkflowDeviceActionDecision`；没有 Room schema 升级。持久字段只包含动作、前后包名、后置计数/截断/时间和规则版本，不包含方向、viewport、HMAC、snapshot/ref、节点正文或坐标。Repository 完成步骤、Run 收敛、下一步 previous output 与关联重试继续从 Tool Ledger 重建相同净化判定。
- Workflow UI 将动作显示为“滚动”，证据卡只投影前后包名、后置节点/脱敏/截断/时间和规则版本，并要求下一动作重新观察、按自身风险规则执行。`swipe` 继续是 SAFE 零审批，`WorkflowDeviceActionApprovalEvidencePolicy` 不新增该工具，因此 Room/Compose 不会伪造审批卡。
- TDD 首轮暴露 DecisionPolicy 对 swipe 返回 `NotApplicable`、UI 显示英文动作；第二轮又暴露 `WorkflowStepSnapshotCodec` 丢弃 swipe Decision。三个 JVM 测试类合计 `44/44`、`compileDebugAndroidTestKotlin`、Debug/AndroidTest APK 均通过。仅 Redmi `wsvwypiz7xwslvl7` 的 Room 纵向与 Compose 展示两个单项为 `OK (2 tests)`，耗时 `3.615s`。
- Debug 主包与测试包都无损覆盖成功。覆盖安装后 Accessibility 一度为 Enabled 但未 Bound，定向清除小灵服务的旧 crash 状态并重新开启后恢复 `Enabled / Bound / Crashed services:{}`；`MainActivity` 为 top resumed，版本 `0.1.14 (15)`，crash buffer 无小灵异常。按分级验证未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- 生产 `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 继续精确为 `{device.snapshot, device.open_app, device.back, device.home, device.tap_ref, device.type_text}`。下一阶段才单独评审并实施生产默认集合扩展，再用 Redmi 运行真实生产 Workflow `snapshot -> swipe`；后台/定时设备自动化继续关闭。

## 第 124 阶段：`device.swipe` Registry 完成态交接与 Redmi 限定验收（完成，生产未开放）

- `WorkflowDeviceActionResultCodec` 的动作白名单新增 `swipe`，但仍只编码既有通用摘要字段；snapshot/ref、目标/锚点 HMAC、节点正文、坐标和完整 viewport 没有进入 schema。答案级 `WorkflowDeviceActionDecisionPolicy`、Workflow output、Room 投影和 Compose 均未增加 swipe 分支。
- `XiaoLingToolRegistry.verifyCompletedWorkflowAction()` 从当前 `DeviceActionOutcome.swipeEvidence` 构造 `WorkflowSwipeCompletionEvidence`。构造前必须证明 outcome 的 `beforeSnapshotId` 等于已授权 snapshot，before viewport 的包名/window/generation 等于该 snapshot，after viewport 的同三项等于实际后置 snapshot；错配时返回 null，由既有 `WorkflowSwipeSafetyPolicy` 统一以“缺少专属同窗滚动后置证据”拒绝。
- TDD 首轮先暴露 Result codec 拒绝 `swipe`，随后暴露 Registry 没有完成态专属 evidence；成功链转绿后又新增后置 window 错配反例，确认旧实现会错误通过，再以 snapshot/evidence 绑定收紧。成功结果还断言通用 Result 不包含目标或锚点 HMAC。八组聚焦 JVM 最终为 `DeviceObservationControllerTest 12/12`、`DeviceActionControllerTest 8/8`、`DeviceActionCodecTest 1/1`、`DeviceSnapshotPolicyTest 9/9`、`WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowDeviceActionSafetyPolicyTest 17/17`、`WorkflowTypeTextSafetyPolicyTest 4/4`、`XiaoLingToolRegistryTest 36/36`，合计 `91/91`，0 failure/error/skipped；`compileDebugKotlin` 与 `assembleDebug` 通过。
- Debug-only `AgentE2eDebugReceiver` 新增 `workflow_swipe`。它打开系统设置的应用详情页，连续确认同一 generation 下存在可滚动 ref，再以显式测试集合构造 Registry，由确定性 LLM 运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 两步链。SAFE swipe 使用精确 `snapshot_id / ref / direction=up`，若出现任何审批会立即失败；完成后核对 Run、ToolCall、ToolResult、严格 codec、同包后置 snapshot 和 Result 隐私。
- 仅 Redmi `wsvwypiz7xwslvl7` 的真实日志为 `workflow-swipe-e2e success=true action=swipe verified=true approvals=0 registryCompletion=PASSED beforePackage=com.android.settings afterPackage=com.android.settings privacySafe=true afterNodes=24`。验收后小灵 `0.1.14 (15)` 已恢复为 top resumed，Accessibility 为 `Enabled / Bound / Crashed services:{}`，crash buffer 无本应用异常。没有使用或启动模拟器；按快速迭代分级未运行完整 JVM、Lint、AndroidTest APK、Release 或默认完整 instrumentation。
- 生产 `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 仍精确为 `{device.snapshot, device.open_app, device.back, device.home, device.tap_ref, device.type_text}`。本阶段只完成 test/debug seam 与首个限定 App 证明，不承诺任意 App，也不开放后台或定时设备工具；下一阶段先独立评审答案级脱敏判定、Room/Workflow output 和 UI 投影，再决定是否进入生产默认集合。

## 第 123 阶段：`device.swipe` Controller/Registry HMAC evidence seam（完成，生产未开放）

- 新增 `DeviceSwipeEvidencePolicy`。Controller 每实例默认使用 `SecureRandom` 生成 32-byte 密钥，测试可以注入固定 key；目标和语义锚点通过 `DataOutputStream` 长度前缀编码后执行 `HmacSHA256`。目标身份绑定应用、window、角色和节点路径，不包含 bounds/generation/snapshot/ref；锚点再绑定当前目标身份、应用、window、角色及已脱敏后的 text/description/hint/状态，不含位置，因而相同语义不能跨滚动目标关联。重复语义 HMAC 全部丢弃，只保留唯一锚点与 bounds 中心坐标。
- `DeviceObservationController` 在与 `DeviceNodeReferenceStore` 相同的锁内保留当前成功 `DeviceSnapshotCapture.Success`。capture 失败、动作失败、显式 `clearReferences()` 或 Registry 切换 Agent Run 时同时清理 ref 与 snapshot，避免下一 Run 读取旧 viewport。`inspectReference()` 在该锁内核对当前 snapshot/ref 并只为支持 `SWIPE` 的目标构造 `DeviceSwipeViewportEvidence`，锁外再读一次 window generation；证据构造期间页面变化时返回不匹配且不暴露 target/viewport。
- `swipe()` 在发送动作前冻结匿名 before viewport，动作后沿原节点路径重新定位滚动目标并生成 after viewport。`DeviceSwipeDirectionVerifier` 同时供设备层和 `WorkflowSwipeSafetyPolicy` 使用；完成必须满足同应用/window/目标、generation 前进、两侧至少两个唯一锚点、内容集合变化和共同锚点至少 `8px` 的方向占优位移，任一显著反向或横向占优锚点都会整体拒绝。`tap_ref` 仍保留原 generation 语义，不被本阶段连带修改。
- Registry 的 supported 测试集合允许显式注入 `device.swipe`，并把 `DeviceReferenceInspection.swipeViewport` 原样交给 `WorkflowSwipeSafetyPolicy`；生产默认集合未加入该工具。SAFE `back / home / swipe` 统一使用实际执行时钟核对 TTL，异常 approval 时间不能续期。第 123 阶段当时只接执行前 evidence seam，尚未调用当时仍拒绝 swipe 的 `WorkflowDeviceActionResultCodec`；第 124 阶段随后完成完成态交接。
- `DeviceActionOutcome.swipeEvidence` 与前后 viewport 只驻留当前执行链；`DeviceActionCodecTest` 固定通用结果不序列化锚点或目标指纹。第 123 阶段当时没有修改 Result codec、DecisionPolicy、Room、审批、Compose、答案级 UI 或后台自动化；第 124 阶段只让 Result codec 识别无 HMAC 的通用 swipe 摘要，其他边界继续不变。
- TDD 覆盖正向 HMAC 锚点、内容不变、反向位移、重复语义、不同实例密钥、跨滚动目标隔离、inspection 期间 generation 漂移、当前 inspection/clear、codec 隔离、Registry 缺 viewport、SAFE TTL 和 Run 切换。八组聚焦 JVM 为 `89/89`，0 failure/error/skipped。按快速迭代分级未运行完整 JVM、Lint、APK、Release、Redmi instrumentation 或真实滚动。

## 第 122 阶段：前台 Workflow `device.swipe` 专属安全契约（完成，生产未开放）

- 新增纯 Kotlin `WorkflowSwipeSafetyPolicy`，只接受精确 `snapshot_id / ref / direction`，方向限于 `up / down / left / right`。当前目标必须启用、未脱敏且声明 `SWIPE`；动作前 viewport 必须绑定包名、window ID、generation、目标指纹和至少两个去重的 64 位匿名锚点。
- `WorkflowDeviceActionSafetyPolicy` 为 execution/completion evidence 增加 swipe 专属分支。缺少目标、viewport、专属授权或后置滚动证据时统一返回 `SWIPE_POLICY_DENIED`；`device.swipe` 依据既有 `ToolRisk.SAFE` 归入 `SAFE_NO_APPROVAL`，但通用 snapshot/ref、30 秒 TTL、generation、同 Run/ToolCall、Executor/typed 验证和动作后观察门禁保持不变。
- 完成验证要求动作前后同应用、同 window、同一目标，generation 严格前进且可见匿名内容集合变化；至少一个共同锚点必须产生不小于 `8px`、与请求方向一致且主方向占优的位移。反向或横向占优、内容不变、目标/window 漂移、只有 generation 变化或只有 Android API 接收动作均不能收敛为成功，四个方向均有回归。
- `WorkflowSwipeAuthorization` 只保存规则版本、Run/ToolCall 身份、方向和动作前 viewport SHA-256 摘要，不保存包名、snapshot/ref、目标指纹、完整锚点或节点正文。完整 evidence 只允许在当前执行链中使用；下一阶段生成锚点时必须采用当前执行期 opaque/HMAC 身份，避免把节点正文的普通 SHA-256 变成可字典反查的长期标识。
- 第 122 阶段当时没有修改生产 `XiaoLingToolRegistry` 或 supported Workflow 集合，工具面保持 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`，Controller 也仍是 generation-only。第 123 阶段随后替换 Controller 验证并增加仅测试态 Registry 执行期 evidence seam，第 124 阶段再完成完成态纯内存交接与 Redmi 限定验收；生产默认集合、DecisionPolicy、Room、Approval、Compose 和 Workflow UI 仍未改变。
- TDD 最终为 `WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowDeviceActionSafetyPolicyTest 17/17`、`WorkflowTypeTextSafetyPolicyTest 4/4`、`XiaoLingToolRegistryTest 30/30`，合计 `55/55`，0 failure/error/skipped。按快速迭代分级没有运行完整 JVM、Lint、APK、Release、Redmi instrumentation 或真实动作；Redmi 上的正式 `v0.1.14` 保持不变。

## 第 121 阶段：前台 Workflow `device.open_app` 生产闭环（完成）

- `XiaoLingToolRegistry` 的生产前台 Workflow 默认动作集合改为 `{device.open_app, device.back, device.home, device.tap_ref, device.type_text}`，规划清单精确暴露 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。`open_app` 继续使用既有 `REQUIRES_APPROVAL` ToolDefinition，只允许唯一 `package_name`；`swipe`、后台/定时来源和恢复自动续跑仍在清单与 Executor 两层拒绝。
- `WorkflowDeviceActionSafetyPolicy` 不再把非 ref 动作等同于无目标动作。`open_app` 在策略层冻结四个首批白名单包，要求独立审批，并与当前 Workflow/Step/AgentRun/ToolCall、用户步骤意图、同 Run 已验证 snapshot、30 秒 TTL、window generation 和进程 session 绑定。`WorkflowDeviceActionApprovalGate` 再以相同唯一包名创建 Room Approval 与 Accessibility overlay，`DeviceActionPolicy` 在 Executor 层作第三次白名单核验。
- `DeviceController.currentWindowGeneration()` 为不使用节点 ref 的动作提供只读窗口身份。Registry 只对 `tap_ref / type_text` 执行 ref 检查；`open_app / back / home` 仍必须证明当前 generation 与动作前 snapshot 一致。动作后结果由 `workflow-device-action-result-v1` 保存前后包名和有限 snapshot 摘要，`open_app` 完成门禁会把后置包名与获批目标精确比较。
- `WorkflowDeviceActionDecisionPolicy`、`WorkflowStepSnapshotCodec`、Room 下一步/关联重试和 Workflow UI 支持“打开应用”。答案级重建会从同一 ToolCall 只提取唯一获批包名，并与严格结果中的 `afterPackageName` 再次比较；缺少目标、额外参数、非白名单目标或包名错配统一 fail-closed。成功证据不产生可复用节点引用，审批原始参数不进入 UI state；拒绝、取消和执行失败继续投影为稳定脱敏状态。
- Debug tracer 新增 `workflow_open_app`：从隐私探针连续等待稳定 window generation，执行 `snapshot -> open_app(com.android.calculator2)`，核对 Room Approval、Tool Ledger、严格 codec、答案级 Decision 和真实后置包名。首次人工查看浮层超过 30 秒后按设计拒绝“device.snapshot 已过期或时间证据不完整”；重新执行并及时批准后日志为 `workflow-open-app-e2e success=true action=open_app verified=true approval=APPROVED executorVerified=true verification=PASSED beforePackage=com.longdev.xiaoling afterPackage=com.android.calculator2 answerDecision=VERIFIED`。
- 六组聚焦 JVM 合计 `95/95`，`assembleDebug / assembleDebugAndroidTest` 成功。Compose `pageDisplaysVerifiedOpenAppTargetAndFollowUpBoundary` 与 Room `workflowPersistsVerifiedOpenAppDecisionForNextStepAndUi` 在 Redmi 上均为 `OK (1 test)`（`2.681s / 0.628s`）；同步后的文档 corpus 首轮为 `OK (1 test)`（`2.783s`），该证据写回后的冻结文本复验同样通过。独立审查先发现 SafetyPolicy 自身未校验参数/白名单，随后又发现答案级结果未绑定获批包名；两处都先补失败反例，再增加策略层、完成层和 Room 重建层核验。最终 Standards/Spec 双轴复核没有规格缺口，并把 `DeviceController.currentWindowGeneration()` 的空 snapshot/ref 默认委托改为实现方显式契约，同时修正生产工具注释。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 第 120 阶段：前台 Workflow `device.home` 生产闭环（完成）

- `XiaoLingToolRegistry` 的生产前台 Workflow 默认动作集合改为 `{device.back, device.home, device.tap_ref, device.type_text}`。规划清单精确暴露 `device.snapshot / device.back / device.home / device.tap_ref / device.type_text`；`open_app / swipe`、后台/定时来源和恢复自动续跑仍在清单与 Executor 两层拒绝。`home` 的 ToolDefinition 保持空 Schema、`ToolRisk.SAFE` 与 Executor 验证，额外参数在通用 Schema 和动作安全策略两层拒绝。
- `WorkflowDeviceActionSafetyPolicy` 把 `back / home` 精确归入 `SAFE_NO_APPROVAL` 导航集合。Registry 对两者始终使用 `clock.nowMillis()` 核对 30 秒 snapshot TTL，不接受审批决定时间延长授权；明确步骤意图、当前 Workflow Run/Step/Agent Run/ToolCall、同 Run 已验证 snapshot、window generation 和当前进程身份仍为硬门禁。
- `DeviceObservationController.home()` 继续使用标准全局 HOME 动作，并在动作内部采集后置 snapshot。`AndroidDeviceAccessibilityGateway.isHomePackage()` 通过系统 `ACTION_MAIN + CATEGORY_HOME` 动态解析 launcher；只有后置包匹配真实 launcher 才产生 `verified=true`，不写死 Redmi 的 `com.android.launcher3` 或其他厂商实现。
- `WorkflowDeviceActionResultCodec / DecisionPolicy / WorkflowStepSnapshotCodec` 的白名单精确加入 `home`。Room Repository、下一步输入、关联重试和 Workflow UI 只消费 `success=true / executorVerified=true / typed PASSED` 的严格结果；答案标签为“返回桌面”，并声明不产生可复用节点引用。审批证据仍只来自需要审批的 `tap_ref / type_text`，SAFE `home` 不产生伪 Approval。
- Debug tracer 从真实系统设置执行 `snapshot -> home`，逐项核对空参数、SAFE 风险、Room Approval 为 0、Tool Ledger Executor/typed 验证、动态 launcher 后置包和答案级 `VERIFIED`，日志为 `workflow-home-e2e success=true action=home verified=true approvals=0 beforePackage=com.android.settings afterPackage=com.android.launcher3 answerDecision=VERIFIED`。六组聚焦 JVM 合计 `87/87`、`assembleDebug / assembleDebugAndroidTest` 通过；仅 Redmi 的 Compose 与 Room 纵向单项分别为 `OK (1 test)`（`2.472s / 0.605s`）。
- 七份长期文档重新打入 AndroidTest assets 后，仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`；首轮为 `OK (1 test)`、`2.76s`，该证据写回后的最终复验同样通过。
- 本阶段按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。下一动作只从 `open_app / swipe` 中选择一个独立前台 Workflow 切片；后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 119 阶段：前台 Workflow `device.back` 生产闭环（完成）

- `XiaoLingToolRegistry` 的生产前台 Workflow 默认动作集合改为 `{device.back, device.tap_ref, device.type_text}`。规划清单精确暴露 `device.snapshot / device.back / device.tap_ref / device.type_text`；`open_app / home / swipe`、后台/定时来源和恢复自动续跑仍在清单与 Executor 两层拒绝。`back` 的 ToolDefinition 保持空 Schema、`ToolRisk.SAFE` 与 Executor 验证，额外参数在通用 Schema 校验阶段拒绝。
- `WorkflowDeviceActionSafetyPolicy` 为 `device.back` 使用 `SAFE_NO_APPROVAL`。执行仍绑定用户步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 已验证 snapshot、30 秒 TTL 和当前 window generation；完成仍要求同一 ToolCall 的成功结果、Executor 验证、typed `PASSED` 和后置 snapshot。Registry 对 `back` 始终读取当前执行时钟，异常调用方传入旧审批对象也不能延长 snapshot TTL；`tap_ref / type_text` 才使用审批决定时间冻结边界。
- `WorkflowDeviceActionDecisionPolicy` 与结果 codec 接受严格的 `device.back -> back` 白名单；`WorkflowStepSnapshotCodec`、下一步、关联重试、Room Repository 与 `WorkflowManagementProjection` 均可恢复和展示“返回”。Room Approval 查询/投影仍只覆盖 `tap_ref / type_text`，SAFE `back` 不创建审批卡或 Accessibility overlay。
- Debug tracer 新增 `workflow_back`：先等待系统应用详情页连续三次保持同一 window generation，再由真实 `MinimalAgentRuntime + RoomAgentRunRepository` 执行一次返回并核对后置包名。独立 document task 保证一次 Back 回到小灵；显式前台 Receiver 避免当前 Redmi ROM 对隐式广播投递不稳定。真实日志为 `workflow-back-e2e success=true action=back verified=true approvals=0 beforePackage=com.android.settings afterPackage=com.longdev.xiaoling answerDecision=VERIFIED`。
- 聚焦 JVM 覆盖 Registry、安全策略、答案判定、步骤执行和 Workflow 投影，全部通过；`compileDebugKotlin / compileDebugAndroidTestKotlin / assembleDebug / assembleDebugAndroidTest` 通过。仅 Redmi 的 Compose `pageDisplaysVerifiedBackAsSafeNavigationEvidence` 与 Room 纵向 `workflowPersistsVerifiedBackDecisionForNextStepAndUiWithoutApprovalEvidence` 均为 `OK (1 test)`，同步后的文档 corpus 首轮与最终复验均为 `OK (1 test)`（`2.733s / 2.725s`）。instrumentation 结束后主包被强制停止，Accessibility 一度列入 `Crashed services`；移除并原样恢复组件授权后重新进入 `Bound`，`Crashed services:{}`，未清主应用数据或凭据。
- 本阶段按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。第 120 阶段随后以相同 SAFE 导航边界完成 `home`；后续下一动作只从 `open_app / swipe` 中选择一个独立前台 Workflow 切片。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 118 阶段：统一直接 `/agent` 的 `type_text` 持久化隐私（完成）

- 新增通用 `DeviceTypeTextAuditPolicy`，替代 Workflow 专属审计投影。`MinimalAgentRuntime` 在 proposed/validated 事件、独立 ToolCall ledger 和最终可信执行上下文中复用同一安全参数；`WorkflowDeviceActionApprovalGate` 与直接 `/agent` Room Approval 也使用相同投影，持久字段统一为 `snapshot_id / ref / text_sha256 / text_length`。
- `RoomAgentRunRepository.createApprovalRequest()` 在写事务前再次净化 `device.type_text` 参数，成为所有审批调用方共享的最后一道持久化边界。即使未来调用方误传原始 ToolCall，Approval record 与 requested/decided 事件仍不能保存输入原文；非 `type_text` 工具保持原参数语义。
- 当前进程审批体验由内存 ToolCall 提供真实输入，但 `XiaoLingViewModel` 只有在 Room 请求与原 ToolCall 的 Run/ToolCall ID、工具名、风险和安全投影逐项一致时才创建审批卡。最终 Spec 复审把该一致性校验移动到 `AgentApprovalDecisionCoordinator.register()` 之前，避免漂移拒绝后遗留活动 ticket，并在同一 JVM 用例中固定 ID、工具名、风险和参数投影四类反例。任务中心、历史审批和会话重建只读取 Room 安全投影，不能通过文本指纹反推出输入。
- `VerifiedAgentContext` 原先仍从 Executor 原始 ToolCall 构造，随后会被 `AgentMessagePartPolicy` 投影到消息 Tool parts，形成独立持久化旁路。新增失败测试后，Runtime 改为用同一 `DeviceTypeTextAuditPolicy` 构造可信上下文；消息正文、Tool parts 和历史会话现在都只持有安全投影。
- `AgentRunResumePolicy` 新增 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE`。进程重建后的 `type_text` 待审批链尾不再恢复为 `APPROVAL_WAIT`，启动收敛会安全取消旧 Approval 与旧 Run，并要求用户新建 Run 重新输入；旧 Run 不重放、不修改为成功，也不从指纹恢复原文。
- 聚焦 JVM 最终为 `MinimalAgentRuntimeTest 66/66`、`AgentRunResumePolicyTest 57/57`、`WorkflowDeviceActionApprovalGateTest 6/6`、`AgentApprovalUiStateTest 1/1`，合计 `130/130`，0 failure/error/skipped；`assembleDebug / assembleDebugAndroidTest` 通过。Redmi `wsvwypiz7xwslvl7` 的直接 Approval 脱敏、进程重建收敛和既有 Workflow Approval 投影三个定向单项均为 `OK (1 test)`。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- 本阶段没有开放 `swipe / open_app / back / home`、后台或定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。下一阶段只从这些前台动作中选择一个独立 Workflow 切片，并单独完成风险、审批或 SAFE 依据、操作后验证、答案级证据和 Redmi 真实动作验收。

## 第 117 阶段：前台 Workflow `type_text` 生产闭环（完成）

- `WorkflowDeviceActionApprovalGate` 现在同时截获 `device.tap_ref / device.type_text`。文本输入原文只留在当前 ToolCall 内存；该阶段的 Workflow 审计投影让 `tool.call.proposed / validated`、ToolCall ledger、Room Approval 及 requested/decided 事件统一仅保存 `snapshot_id / ref / text_sha256 / text_length`，Accessibility 浮层只显示“输入 N 个字符，内容不展示”。持久化请求和最终决定仍逐字段核对身份，取消先在 `NonCancellable + IO` 收敛。第 118 阶段随后以通用 `DeviceTypeTextAuditPolicy` 替代专属投影，并把相同边界扩展到直接 `/agent` 与可信消息上下文。
- `XiaoLingToolRegistry` 的生产前台 Workflow 默认动作集合改为 `{device.tap_ref, device.type_text}`，规划清单和 Executor 继续共同拒绝 `open_app / back / home / swipe`、后台/定时设备工具与恢复自动续跑。文本动作沿用第 115/116 阶段的敏感文本、当前 ref 节点 evidence、最小指纹授权和原 `nodePath` 精确回读；结果摘要不含原文、snapshot ID、ref 或节点。
- `WorkflowDeviceActionDecisionPolicy` 按 `device.tap_ref -> tap_ref / device.type_text -> type_text` 严格匹配白名单结果；`WorkflowStepSnapshotCodec`、下一步输入、关联重试和管理页投影均可消费 `type_text` 本地判定。Compose 将动作显示为“输入文本（内容不展示）”，并固定声明“输入原文未保存到答案级证据”；输入原文、指纹、snapshot/ref 和模型转述不会进入答案级输出。
- Redmi 实测发现移除 Accessibility overlay 后会连续产生多条 `TYPE_WINDOWS_CHANGED`。Coordinator 现在保留活动请求 `100ms`：连续且活动根/窗口集合仍精确等于基线的 detach 事件继续抑制 generation 作废，并且只调度一次 settle；最终结算再次核对活动根和完整窗口集合。外来窗口、目标内容变化、服务断连、超时及结算时漂移继续 fail-closed；View 已移除后到达的失败决定可直接完成，不等待不存在的第二次 detach。
- Debug tracer 新增 `workflow_type_text`，使用独立非聚焦普通 EditText Probe 和真实 `MinimalAgentRuntime + RoomAgentRunRepository + WorkflowDeviceActionApprovalGate`。Redmi 日志为 `workflow-type-text-e2e success=true action=type_text verified=true approval=APPROVED answerDecision=VERIFIED exactReadBack=true afterNodes=2`；tap 对照链仍成功。审批显示期间运行 UIAutomator 会接管 Accessibility 并触发正确取消，因此真实浮层自动批准使用 WindowManager 标题轮询和 Redmi 坐标，不在浮层期间调用 UIAutomator。
- 固定点 `87067d4` 的 Standards/Spec 子代理均被本地 `http://localhost:8080/responses` 的 404 阻断；主线程双轴复核先补充 overlay settle 的活动根、最终窗口集合和外来窗口反例，提交前复核又发现原始 `text` 仍会进入 proposed/validated/ToolCall ledger。新增 Runtime TDD 回归后，Workflow 审计统一脱敏；第 117 阶段当时没有改变直接 `/agent`，该遗留边界已由第 118 阶段统一。受影响聚焦 JVM 最终为 Runtime 审计 `1/1`、Gate `6/6`、Registry `25/25`、动作判定 `4/4`、通用动作安全 `11/11`、文本安全 `4/4`、overlay coordinator `9/9`、Workflow 投影 `15/15`，合计 `75/75`；Debug/AndroidTest APK 通过。仅 Redmi 的 Compose、Room Approval 与 Room Workflow 纵向单项分别为 `OK (1 test)`，真实 tracer 同样验证 ToolCall ledger 无原文。本阶段未运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 第 116 阶段：前台 Workflow `type_text` evidence seam（完成，生产未开放）

- `DeviceNodeReference` 与 `DeviceNodeReferenceResolution.Current` 现在保存 enabled、editable、redacted 和动作集合；`DeviceSnapshotPolicy` 在生成 ref 时从同一原始节点显式冻结这些属性。`DeviceObservationController.inspectReference()` 只在 snapshot/ref、TTL 和当前 window generation 全部匹配时返回 `DeviceReferenceTargetInspection`，旧 ref、过期或页面漂移继续没有目标证据。
- `DeviceActionOutcome` 增加只驻留瞬态执行链的 `DeviceTypeTextReadBack`。输入动作后不再扫描所有节点的 text/description/hint；Controller 从新 capture 的 references 中唯一匹配原 `nodePath`，再按新 ref 找到目标 `DeviceSnapshotNode` 并读取 `text`。目标消失、路径不唯一、没有动作后 ref 或文本不精确相等时保持 `verified=false`，页面其他节点出现相同文本也不能误判。
- `XiaoLingToolRegistry` 增加默认仍为 `device.tap_ref` 的动作集合 seam，只有 JVM 测试显式加入 `device.type_text`。构造阶段现在把注入集合硬限制为 `{device.tap_ref, device.type_text}` 子集，`open_app / back / home / swipe` 即使属于已注册动作也会立即拒绝。执行前把 inspection target 转换为 `WorkflowTypeTextExecutionEvidence`；待执行/已执行状态额外保留原始 `WorkflowDeviceActionIdentity`，因为通用授权已按第 115 阶段规则移除 `text`。完成时从强类型 readback 构造 `WorkflowTypeTextCompletionEvidence`，重新核对同 Run/ToolCall、文本指纹和精确回读。
- `WorkflowDeviceActionResultCodec` 可以编码/解码不含原文、snapshot ID、ref 或节点的 `type_text` 白名单摘要。`WorkflowDeviceActionDecisionPolicy` 仍只接受工具名 `device.tap_ref` 且结果动作也必须为 `tap_ref`；新增反例固定伪装在 tap ToolResult 中的 `type_text` 摘要为 `MALFORMED_RESULT`，因此答案级 UI 没有提前扩权。
- TDD 首轮分别因 `DeviceReferenceTargetInspection / typeTextReadBack` 不存在和 Registry 缺少测试动作集合而 Red；相邻答案级判定在 codec 扩展后又以错误动作反例 Red。以 `1d7bb59` 为固定点的 Standards/Spec 子代理再次被本地 `/responses` 404 阻断，主线程复审发现测试集合可传入其他已知设备动作；补构造期拒绝与回归后无遗留 finding。最终聚焦 `DeviceObservationControllerTest 4/4`、`DeviceActionControllerTest 7/7`、引用存储 `2/2`、快照策略 `9/9`、文本策略 `2/2`、专属 Workflow 策略 `4/4`、通用 Workflow 动作策略 `11/11`、答案级判定 `3/3`、Registry `24/24` 和敏感参数预审计 `1/1`，合计 `67/67`；`assembleDebug / assembleDebugAndroidTest` 与 `git diff --check` 通过。
- Redmi 只使用 `wsvwypiz7xwslvl7`。默认 Debug APK 无损覆盖成功；更新后系统要求重新确认 Accessibility，首次 shell 标记启用虽已 Bound 但 `rootInActiveWindow` 为空，经过系统“关闭→允许”重新绑定后快照恢复为 `com.android.settings / nodes=26 / refs=11`。点击“搜索设置”进入 `com.android.settings.intelligence` 后，向真实搜索框输入 `stage116_exact_readback` 得到 `type_text success=true / verified=true`；随后伪密钥输入以 `SENSITIVE_INPUT` 拒绝，UIAutomator 回读仍为原安全文本。两个在线模拟器只出现在设备列表，没有收到定向命令。
- 文档 corpus 首轮为 `OK (1 test)`、耗时 `4.358s`。证据写回后的 Gradle `connectedDebugAndroidTest` 最终单项也通过，但该任务结束时卸载了主包与测试包并清除应用私有数据；这次不再沿用“未卸载或清数据”的旧描述。随后已重新安装默认 Debug，通过系统界面重新授权 Accessibility，使用未跟踪项目配置恢复 `gpt-5.6-luna` Provider 与仅含 `device.snapshot / device.tap_ref` 的 Agent Profile；外部 `/models` 与 `/responses` 均返回 `200`，主 Activity resumed、进程存活且 crash buffer 为空。
- 后续 corpus 改为手动 `install -r` 主 APK/测试 APK，再以 `am instrument` 运行单项；首轮为 `OK (1 test)`、耗时 `2.632s`。写回本节后的最终 assets 已以相同流程复验通过，并只卸载 `com.longdev.xiaoling.test`，保留主包、Provider/Profile、Keystore 与 Accessibility 授权。
- 第 116 阶段结束时，生产 Workflow 工具面仍精确为 `device.snapshot / device.tap_ref`；强行执行 `type_text` 的既有反例继续通过。该阶段没有修改 Room、审批 gate、Accessibility overlay、Workflow Repository、答案级动作 Compose、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。第 117 阶段随后已把 Room/overlay、无原文答案级证据和真实 Workflow 输入形成单一闭环，并仅将 `type_text` 加入生产默认动作集合。

## 第 115 阶段：前台 Workflow `type_text` 专属安全契约（完成，生产未开放）

- 新增 `WorkflowTypeTextSafetyPolicy` 纯策略 seam。执行入口要求工具名为 `device.type_text`、Run/Step/AgentRun/ToolCall 身份完整且参数键精确等于 `snapshot_id / ref / text`；文本继续调用 `DeviceActionPolicy.validateTextInput()`，目标证据必须同时满足 enabled、editable、非 redacted 和支持 `TYPE_TEXT`。
- `WorkflowTypeTextAuthorization` 只保存规则版本、Run/ToolCall 身份、SHA-256 文本指纹与字符长度，不保存输入原文、snapshot ID 或 ref。完成入口重新计算指纹并绑定同一身份，要求 Executor/typed/动作后观察均已验证、观察时间不早于动作完成且 `readBackText` 与获批文本精确相等。
- `WorkflowDeviceActionSafetyPolicy` 的 execution/completion evidence 增加可空专属证据，授权增加可空专属授权，失败原因增加 `TYPE_TEXT_POLICY_DENIED`。只要工具为 `type_text` 就必须委托专属策略；通用授权 identity 会移除 `text`、保留 `snapshot_id / ref`，非 `type_text` 行为与既有授权相等性保持不变。
- 生产 `XiaoLingToolRegistry` 仍只对白名单加入 `device.tap_ref`。新增强行执行 `device.type_text` 的 Workflow 反例，固定返回“尚未开放给 Workflow”且 Fake controller 动作列表不增加；没有修改 Room、Accessibility、Workflow Repository 或真实设备输入路径。
- TDD 首轮按预期在 `compileDebugUnitTestKotlin` 因专属类型不存在而 Red；实现后新策略 `4/4`，相邻 `WorkflowDeviceActionSafetyPolicyTest 11/11`、`DeviceActionPolicyTest 2/2`、`XiaoLingToolRegistryTest 21/21` 和敏感参数预审计 `1/1`，合计 `39/39`。首次 `--rerun-tasks` 同时完成 Debug 主代码和测试代码编译，`assembleDebug / assembleDebugAndroidTest` 通过。Standards/Spec 子代理因本地 `/responses` 404 无法产出，主线程以 `4c22efc` 为固定点完成双轴复审，未发现遗留 finding；`git diff --check` 通过。
- Redmi 首次覆盖因错误使用正式证书副本而明确失败为 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，没有卸载或清数据。只读拉取当前 `base.apk` 后确认设备安装包与默认 Debug APK 证书 SHA-256 同为 `6c8823ff4295d7b29e8e2c58b13c864f795b082255b67c80aa8cb783155e3899`；改用匹配证书的原始 Debug/Test APK 后无损覆盖成功。文档 corpus 单项为 `OK (1 test)`、首轮耗时 `2.658s`，写回本节后的最终 assets 已以相同步骤复验通过；两个在线模拟器只出现在设备列表，没有收到定向命令。
- 本阶段按快速迭代分级不运行完整 JVM、Lint、Release、默认完整 instrumentation 或真实输入动作。第 116 阶段随后已把节点结构证据与精确回读接到 Registry/Accessibility seam，并在生产白名单不变的前提下完成 Redmi 直接动作正反例。

## 第 114 阶段：Workflow 设备动作答案级证据 UI（完成）

- `RoomAgentRunRepository.approvalRequests()` 按 Workflow step 的 Agent Run ID 去重，并以 `ROOM_IN_QUERY_BATCH_SIZE=900` 分块调用既有 `getApprovalRequestsForRuns()`；返回 Map 同时保留空列表，避免 N+1 和 SQLite bind 上限问题，不增加 Room Schema 或复制审批数据。
- `XiaoLingViewModel.loadWorkflowUiData()` 在 IO 边界立即通过 `WorkflowDeviceActionApprovalEvidencePolicy` 把 `ApprovalRequestRecord` 收敛为 `runId / toolName / outcome`。原始 `arguments`、snapshot/ref、节点正文和 `decisionReason` 不进入 `XiaoLingUiState`；`WorkflowManagementProjection` 还会再次核对证据自身 `runId`，跨 Run 审批无法绑定到当前步骤。
- 成功动作只从既有 `WorkflowStepOutputSnapshot.deviceActionDecisions` 投影，Compose DTO 白名单为动作、前后应用、后置节点/脱敏/截断、观察时间和判定规则。页面明确说明该证据只确认 `tap_ref` 与后置观察，不确认最终业务目标；历史 ref 固定失效，后续必须重新观察和审批。
- 审批失败投影稳定区分 `USER_DENIED / CANCELLED / WINDOW_CHANGED / OVERLAY_UNAVAILABLE / SERVICE_DISCONNECTED / BUSY`。审批已为 `APPROVED` 但步骤因 window generation 或服务断连失败时，页面显示“未通过执行验证”；同一 Run 的早期取消与后续批准后失败会同时保留。成功本地判定存在时，以其作为步骤最终可信结果，不再混入早期失败尝试。
- 历史 step output、previous outputs、Run result 和 Run error 统一调用 `WorkflowDeviceActionDecisionPolicy.containsPotentialRawActionResult()`；命中原始动作结果版本或至少四个动作字段签名时整段替换为固定提示。该规则不把原始 JSON 复制到新 DTO、日志或文档。
- 固定点 `f300dfc` 的 Standards/Spec 双轴复审发现首版原因签名只覆盖“额外窗口/窗口集合变化”，遗漏活动页面切换、目标内容变化、多 overlay、overlay 身份漂移和“当前窗口状态不允许显示审批”；同时 `.ifEmpty` 链会让早期取消遮蔽批准后的执行验证失败。两项均已补测试并修复，复审后无遗留 finding。
- 最终聚焦 JVM 为 `WorkflowManagementProjectionTest 14/14`、动作判定 `3/3`、观察判定 `4/4`、步骤执行策略 `9/9`，合计 `30/30`；Debug/AndroidTest APK 通过，`git diff --check` 通过。仅 Redmi `wsvwypiz7xwslvl7` 定向页面 `OK (4 tests)`、批量审批读取 `OK (1 test)`、Room 动作判定到 UI 投影 `OK (1 test)`；更新后的文档 corpus 首次 `OK (1 test)`、耗时 `2.677s`，写回设备收尾后的最终复验同样通过。测试包已卸载，主应用恢复前台；Accessibility 保持关闭且 `Crashed services:{}`，crash buffer 无小灵 FATAL。未运行完整 JVM、Lint、Release 或默认完整 instrumentation，也未向在线模拟器发送定向命令。

## 第 113 阶段：前台 Workflow `tap_ref` 首个生产切片（完成）

- `WorkflowDeviceActionApprovalGate` 只截获 `device.tap_ref`。它用当前 Workflow 步骤意图创建 Room 审批请求，再调用 `DeviceActionApprovalOverlayRequester`；其他工具转交原 `interactiveAgentApprovalGate()`，直接 `/agent` 行为不变。`RoomWorkflowDeviceActionApprovalPersistence` 统一在 IO 调度调用现有 `RoomAgentRunRepository`，批准只有在落库后的身份、状态、原因和决定时间仍与原请求完全一致时才返回 Runtime；取消在 `NonCancellable` 区先收敛 Room 再传播。
- `XiaoLingAccessibilityService` 持有单个 `DeviceActionApprovalOverlayCoordinator`。正式窗口标题为 `XiaoLingDeviceActionApproval`，flags 只有 `NOT_FOCUSABLE / NOT_TOUCH_MODAL / SECURE`，Android 11+ 使用 `WindowMetrics`、旧版本使用系统 dimen 避开导航栏。浮层只展示用户意图、工具说明和工具名，不展示 `arguments`；`FLAG_SECURE` 继续让截图区域变黑、UIAutomator 不暴露内容。
- generation 跟随真实 `rootInActiveWindow`。协调器冻结显示前窗口集合，只接受“基线”或“基线 + 唯一标题/类型匹配的自有 overlay”；外来窗口、活动根切换、目标内容变化、滚动或 overlay 身份漂移返回 `WINDOW_CHANGED`。用户按钮只记录 pending 决定并移除 View，必须等 `TYPE_WINDOWS_CHANGED` 确认自有 overlay 消失后完成；1.5 秒内无法确认按 `OVERLAY_UNAVAILABLE` 拒绝。取消、`onInterrupt()`、`onDestroy()` 与服务断连都移除浮层并 fail-closed。
- `XiaoLingToolRegistry` 在切换 Agent Run 时清空已验证 Workflow snapshot 与动作授权；当前 Workflow 工具面只有 `device.snapshot / device.tap_ref`。tap 执行前绑定同 Run snapshot、实时 ref/fingerprint、当前 generation、当前进程审批和完整参数；执行后重新观察并生成严格白名单 `workflow-device-action-result-v1`。`WorkflowDeviceActionDecisionPolicy` 拒绝额外字段并把结果收窄为 `workflow-device-action-decision-v1`，`RoomWorkflowRepository` 将其写入既有 step output snapshot，下一步不接收原始 snapshot/ref/节点。
- Debug tracer 不再手工调用 Registry 或原型浮层，而是用真实 `MinimalAgentRuntime + RoomAgentRunRepository + WorkflowDeviceActionApprovalGate` 和确定性两步 LLM，产生完整 Run/Step/Event/Approval/Tool Ledger。Probe 启动后等待 snapshot 确实含“安全按钮”，不再依赖固定 400ms；动作必须让页面文本变为“动作已完成”。
- TDD 增加单请求/BUSY、批准/拒绝/取消/断连/移除超时、自有 overlay generation、外来窗口、快速点击竞态、切换 Run 后旧 snapshot/ref 失效、Room 决定身份漂移与协程取消收敛。聚焦集合 `45/45`，完整 Debug JVM `784/784`；`assembleDebug / assembleDebugAndroidTest` 通过，`git diff --check` 无问题且源码不存在任何 `PROTOTYPE` 或诊断 tag。
- 双轴复审进一步收紧两个边界：`DeviceActionApprovalOverlayRequest` 不再接收或保存原始参数，snapshot/ref 只存在于 Room 审批身份和 Executor 内部；`WorkflowDeviceActionDecisionPolicy` 除 typed `PASSED` 与严格结果 codec 外，还必须读取 Room Tool Ledger 的 `executorVerified=true`。复审后相关 JVM `108/108`、Debug/AndroidTest APK 通过；Redmi Room 正向投影与 `executorVerified=false` 反向拒绝为 `OK (2 tests)`（`0.775s`），最终文档 corpus 再次为 `OK (1 test)`。
- Redmi `wsvwypiz7xwslvl7` 上窗口 frame 为 `[0,1781]-[1080,2274]`，与 2340 高屏幕底部保留 66px 导航栏；服务 `Bound`、`Crashed services:{}`。首轮人工检查耗时约 34 秒，按 30 秒 TTL 正确拒绝；另一次 generation 已变化同样拒绝。随后诊断构建与删除诊断后的最终构建各成功一次；最终 Run `run-7f252a9c-711b-466f-bd5b-ff2a545605b7` 为 `COMPLETED / APPROVED / device.tap_ref / success=true / executorVerified=true / PASSED`，后置文本为 true。更新后的文档 corpus 在 Redmi 为 `OK (1 test)`、耗时 `2.596s`；测试包卸载后系统曾因 instrumentation 强停把服务列入 crashed，重置同一小灵组件后已恢复 `Bound / Crashed services:{}`，crash buffer 没有小灵 FATAL。未向 `emulator-5554` 发送定向命令，未运行 Lint、Release 或默认完整 instrumentation。

## 第 112 阶段：前台 Workflow 有限设备动作安全契约（完成，生产未开放）

- 新增纯 Kotlin `WorkflowDeviceActionSafetyPolicy`，只负责冻结生产接线前必须满足的证据契约，不直接依赖 Registry、Room、Accessibility 或 Workflow Repository。构造默认白名单为空，未知动作名在构造时拒绝；第 112 阶段模型工具面和 Executor 行为没有变化，前台 Workflow 当时仍只能使用 `device.snapshot`。第 113 阶段随后只将 `device.tap_ref` 接入该契约。
- 执行资格固定为 `WORKFLOW + FOREGROUND`，并绑定用户明确编写的步骤意图、完整 `workflowRunId / workflowStepId / agentRunId / toolCallId`、完整参数、同 Run 独立且已验证的 `device.snapshot`、30 秒有效期、当前 window generation 和实时 ref 匹配。节点动作仍要求 snapshot/ref 同时匹配；后台、直接对话借道、页面漂移、过期或畸形观察均拒绝。
- 每个动作审批必须绑定同一 Agent Run、ToolCall、工具名、完整参数、观察之后的决定时间和当前进程会话。旧 Run、关联重试、前一动作或进程重建前的审批不能复用；通过时只签发不可变的 `workflow-device-action-safety-v1` 授权快照。
- 完成判定必须消费同一身份与规则版本的授权，且结果属于同一 Run/ToolCall、执行成功、Executor 已验证、typed `tool.verify` 已通过并存在后置 snapshot。后置观察自身还要绑定当前 Agent Run 和动作 ToolCall，动作完成时间必须晚于审批、观察时间不得早于动作完成；Workflow 已取消后的迟到结果、旧授权、失败结果或任一后置证据缺失都不能收敛为完成。
- 双轴审查发现初版只检查 `expiresAt` 尚未到期、没有硬限制 30 秒上限，且后置 snapshot 使用三个裸字段、缺少 Run/ToolCall 与动作时序绑定。两轮 TDD 分别以行为失败和编译失败复现后，现已通过最大 TTL 常量与强类型 `WorkflowDeviceActionPostObservationEvidence` 修复。
- 最终聚焦结果为新策略 `11/11`、相邻 `WorkflowDeviceObservationDecisionPolicyTest` `4/4`、`XiaoLingToolRegistryTest` `20/20`，合计 `35/35`，`BUILD SUCCESSFUL in 26s`。本阶段按快速迭代分级没有运行完整 JVM、Lint、APK、Release、文档 corpus 或 Redmi instrumentation，也没有执行真实设备动作。

## 第 111 阶段：Workflow 设备观察真实双 Run 与输出净化（完成）

- 首轮真实 Workflow 暴露了两个只在运行态出现的缺口。首先，Debug Receiver 将 `app.current_time` 写入 Room Profile 后，已初始化的 `XiaoLingViewModel` 仍使用旧工具白名单，因此第二 Run 曾报“模型选择了未注册工具：app.current_time”。`XiaoLingToolRegistry` 实际已注册该工具，Debug Profile 的 `allowedSkillIds` 为空，所以该次不是 Skill 收窄或设备权限问题；重建应用进程后从 Room 重新加载 Profile 即恢复。正常 UI 保存 Profile 会同步更新 `uiState`，本阶段不为 Debug 旁路引入生产广播或扩权机制。
- `RoomWorkflowRepository.requireDeviceObservationDecisions()` 统一从同 Run 持久 Tool Ledger 投影本地判定。`RESULT_READABLE` 工具的 `executorVerified` 可为 `null`；只要 `verificationStatus=PASSED`、执行成功且快照可解码，就依然生成 `REVIEWABLE / LIMITED`。前台完成、恢复完成与关联重试不再使用进程内 `VerifiedAgentContext` 作为 Workflow 事实源。
- 第一次双 Run 成功后，只读检查又发现第一步 `outputSnapshot.text` 长 `6702`、仍包含 `snapshot_id`，而第二步 `previousOutputs[0]` 已正确收窄为 169 字符。根因是下一步准备时才替换模型正文，step 完成事务仍持久化 `summary.responseText`。现在 `completeWorkflowStep()` 对所有成功 Agent step 重新查询同 Run Ledger；存在合法 snapshot 时，同一事务内用 `renderForPrompt()` 替换 `result/outputSnapshot.text`，并核对调用方判定与 Ledger 一致。前台 Workflow 消息和 `completeScheduledWorkflowStep()` 后台会话均改用已持久化的净化文本。
- TDD 聚焦覆盖新 Workflow 输出/重试净化、未验证证据完成前拒绝、后台 step 与会话净化，旧实现为 `3` 失败。双轴审查随后发现 `completeRun()` 单步骤兼容分支仍会原样写入 step 与 Run result；现已统一回查 Ledger，并让单步骤和多步骤 Run 都只聚合净化 step。修复后仅 Redmi 定向为 `OK (5 tests)`、耗时 `1.21s`。`assembleDebug / assembleDebugAndroidTest` 成功，最终项目文档 corpus 为 `OK (1 test)`；按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- 最终真实 Workflow Run `workflow-run-0a2cc22f-1212-413c-8026-76576e009dce` 为 `COMPLETED`。观察 Run `run-8dc7eebb-a162-464a-86f2-ed00184db905` 只调用 `device.snapshot`，消费 Run `run-7f1ff488-7583-4961-942b-f6b33d6ee0a4` 只调用 `app.current_time`；两者均 `SAFE / success=1 / PASSED / executorVerified=NULL`，审批数 `0`。第一步 `result` 与第二步 `previousOutputs[0]` 均为 169 字符，`snapshot_id / nodes / ref` 均为 false。判定为 `LIMITED`，包名 `com.longdev.xiaoling`、节点 `49`、脱敏 `6`、未截断；Workflow 页面显示已验证、有限可复核、规则版本和 ref 过期。Accessibility 最终保持 Bound，`Crashed services:{}`。

## 第 110 阶段：Workflow 设备观察本地判定（完成）

- `WorkflowDeviceObservationDecisionPolicy` 是纯 Kotlin 单一入口。`evaluate()` 只接受同 Run 的 `device.snapshot`、成功结果、验证事实和 `DeviceSnapshotCodec.decodeSummary()` 白名单摘要；跨 Run、失败、未验证和畸形结构返回稳定 `InsufficientEvidence`。`REVIEWABLE / LIMITED` 只表达摘要是否完整可复核，规则版本固定为 `workflow-device-observation-v1`。
- `WorkflowStepOutputSnapshot` 在既有 `workflow-step-output-v1` JSON 中增加安全 `deviceObservationDecisions`，只保存规则版本、状态、包名、节点/脱敏数、截断和采集时间，不保存 snapshot/window 身份、节点正文、ref、bounds、actions 或原始 JSON。codec 对每个字段和规则版本严格校验，旧纯文本和无设备判定输出继续兼容。
- `RoomWorkflowRepository.currentPreviousOutputs()` 对每个成功前序步骤重新查询 Tool Ledger。普通步骤和知识证据路径保持原语义；设备步骤则用本地判定文本替换模型正文。关联重试通过 `reusedFromStepId` 回查来源步骤及其 `agentRunId`，并比较已保存判定与 Ledger 当前投影；来源缺失、证据未验证、结构损坏或漂移抛出带稳定原因码的 `WorkflowDeviceObservationEvidenceException`，事务不写入下一步 input snapshot。
- 前台 ViewModel、后台 Worker 和恢复完成路径都通过同一 policy 生成 output snapshot 判定。后台仍无法获得任何设备工具，因此正常返回 `NotApplicable`；这只是共享持久化契约，不开放后台设备观察。Workflow 页面继续只从 Ledger Projection 标记“已验证”，并新增本地判断、受限原因、规则版本与“只确认包名/摘要，不确认目标完成或动作授权”的范围说明。
- 聚焦 JVM 为 `19/19`，`compileDebugAndroidTestKotlin`、Debug/AndroidTest APK 构建通过。Redmi `wsvwypiz7xwslvl7` 定向 Room 数据链 `2` 条与 Compose 卡片 `1` 条合计 `OK (3 tests)`、耗时 `3.343s`；更新后的文档 corpus 首次/最终均为 `OK (1 test)`、耗时 `2.662s / 2.534s`。测试包已卸载，当前 Debug 主应用冷启动 `3.540s`，`0.1.13 (14)`、`MainActivity` resumed、PID 存活且 crash buffer 为空。在线模拟器仅出现在设备列表，没有收到安装、测试或其他定向 ADB 命令。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 第 109 阶段：Workflow 设备观察证据 UI（完成）

- `RoomAgentRunRepository.toolLedgers()` 复用既有批量 ToolCall/ToolResult DAO，按 Workflow steps 的 `agentRunId` 去重并以 `900` 个为一批读取分组，既避免 N+1，也不会在超长历史中超过 SQLite bind 参数上限；没有新增表、迁移或把 Ledger JSON 复制进 Workflow snapshot。`XiaoLingViewModel.loadWorkflowUiData()` 在 IO 边界通过 `WorkflowDeviceObservationProjection` 立即把 Ledger 收敛成白名单 DTO，根 `XiaoLingUiState` 不持有原始节点 JSON。
- `DeviceSnapshotCodec.decodeSummary()` 输出仍只含 package、节点数量、脱敏数量、截断状态和采集时间，但在投影前会核验当前 codec 的完整顶层形状、逐节点对象、必需字段、动作枚举与脱敏计数一致性。Projection 同时核验 AgentRun 身份、工具名、`success` 与 `PASSED`；失败、未验证、跨 Run、非 snapshot 和任意层级畸形都不生成“已验证”证据。窗口标题、snapshot ID、节点正文、hint、ref、bounds 和 actions 不进入 DTO。
- `WorkflowManagementPage` 在对应 step 下展示应用、节点/脱敏数、截断状态、采集时间、执行耗时与“节点引用已过期”提示。投影层同时识别旧 step output、previous outputs 和 Workflow Run result 中的 snapshot JSON 变体并整段替换；无 `snapshot_id`、camelCase 与再次转义形态同样 fail-closed，避免历史 Ledger 内容经模型总结再次回流 UI。
- 审查收尾后 `WorkflowManagementProjectionTest` 为 `7/7`、`DeviceSnapshotPolicyTest` 为 `9/9`，覆盖安全字段、跨 Run 绑定、失败/未验证/逐节点畸形拒绝、三处历史输出及旧形态脱敏；`compileDebugKotlin`、Debug/AndroidTest APK 构建通过。正式证书签署的 Debug/Test APK 仅覆盖 Redmi，Workflow 页面为 `OK (2 tests)`，更新后的文档 corpus 为 `OK (1 test)`。
- 真实 `stage108_snapshot` 历史 Run 首次复核显示证据卡 `com.longdev.xiaoling / 38 节点 / 脱敏 2 / 未截断 / 193ms`，同时暴露旧输出与 Run 结果含原始 JSON；修复后页面只显示两处脱敏提示与证据卡，层级搜索 `snapshot_id / window_title / bounds / actions / ref` 为 0 命中。最终测试包卸载，主应用 `0.1.13 (14)` 前台、进程存活、crash buffer 为空；设备动作、截图、坐标、视觉定位和后台设备工具均未开放。

## 第 108 阶段：前台 Workflow 只读设备观察（完成）

- `XiaoLingToolRegistry` 把设备观察与设备动作拆成两套门禁。`device.snapshot` 允许 `DIRECT / WORKFLOW + FOREGROUND`，动作工具仍只允许 `DIRECT + FOREGROUND`；后台、设备 Agent 未开启、Accessibility 未授权、服务断连或缺少 Run Context 时，两类工具都从规划清单移除并由 Executor 二次拒绝。`DeviceController.health()` 成为清单和执行层共享的无副作用健康 seam，既有 200 节点、4,000 字符、敏感字段脱敏、整窗隐私拒绝和 30 秒 ref 生命周期不变。
- 审批恢复新增显式 `invocationSource` 传递。`XiaoLingViewModel` 在 IO 调度通过 `RoomWorkflowRepository.isWorkflowAgentRun()` 查询持久化 WorkflowRun↔AgentRun 关联，`AgentRunUseCase` 与 `MinimalAgentRuntime` 将来源恢复到 `AgentToolExecutionContext`；因此进程重建后的 Workflow Run 不会使用默认 `DIRECT` 获得动作权限。
- JVM 聚焦 `88/88` 覆盖前台 Workflow 仅见 snapshot、强行动作失败且控制器无动作、后台 Workflow 全部拒绝、未授权/断连不暴露或执行设备工具，以及 Runtime 审批恢复保留 `WORKFLOW` 来源；`compileDebugKotlin`、`assembleDebug`、`assembleDebugAndroidTest` 均通过。Redmi 真实 Room 关联单项 `manualRunKeepsSingleIdempotentAgentStepAndCompletes` 为 `OK (1 test)`、耗时 `0.476s`；更新后的项目文档 corpus 单项同样为 `OK (1 test)`。
- 正常应用进程中临时开启 Accessibility 与设备 Agent 后，设置页真实 snapshot 为 `15` 个节点、ref `30000ms`。前台手动 Workflow `stage108_snapshot` 的 Workflow Run 与 Agent Run 均为 `COMPLETED`，总耗时 `18.868s`；唯一工具调用 `device.snapshot` 为 `SAFE / success=1 / PASSED / 193ms / 6128B`，其中 `redacted_node_count=2`，动作调用与审批请求均为 `0`，规划、校验、执行、验证、再规划和总结六步全部完成。
- 临时会话已删除；Workflow 因当前没有删除入口而禁用保留，设备 Agent、Accessibility 和测试包均恢复关闭/卸载。验收后误覆盖固定 Room v32 Release 时，应用明确因 `A migration from 33 to 32 was required but not found` 崩溃；随后以 `adb install -r` 恢复正式证书签署的当前 Room v33 Debug，没有卸载或清数据，最终 `0.1.13 (14)`、`MainActivity` resumed、PID 存活、crash buffer 为空。该问题是开发设备降级覆盖，不增加数据库降级迁移。
- 主线由等待 Shadow 样本切回个人 Agent 能力；Shadow 保持默认关闭并仅在真正分隔的真实窗口低频并行观察。设备动作进入 Workflow、全部后台设备工具、截图、坐标和任意 App 仍未开放。

## 知识质量工程：answerability Shadow 匿名跨进程账本（完成）

- `KnowledgeAnswerabilityShadowObservationCoordinator` 继续是唯一 Judge 入口；持久记录新增完整匿名 `telemetry`，但 `sourceRunId / persistedMessageId / judgeIdentity` 只存在于进程内协调契约。`AgentAnswerabilityShadowPublisher` 将已保存答案的观测请求从 `NONE` 切换为 `OPTIONAL`，ViewModel 只在既有显式开关、前台 direct `/agent` 与冻结身份门禁通过后注入真实 Room Store。
- Room Schema 升至 v33，新增 `knowledge_answerability_shadow_observations`。`idempotencyKey` 是唯一主键，`candidateFingerprint` 与幂等键落库前都必须匹配 64 位小写 SHA-256；Judge 配置只经 Android Keystore 内不可导出的安装级密钥生成 HMAC-SHA-256 匿名桶，Entity 不含消息/Run ID、原始 Judge 身份、问题、答案、引用、原始响应、URL 或凭据。`INSERT IGNORE + prune` 在同一事务执行，保留首次观测并把总量限制为 2,000 条。
- 数值遥测以可空列保存，失败枚举使用固定独立计数列而非正文或原始 JSON；聚合器沿用进程内 tracker 的语义，已知值饱和求和、全部未知保持 `null`。最终 `failureKind` 没有出现在 attempt telemetry 时补计一次，因此候选校验和意外异常不会从跨进程失败分布消失。
- `KnowledgeAnswerabilityShadowPersistentSummary` 与进程内 `KnowledgeAnswerabilityShadowSampleSummary` 分离。应用初始化和成功持久化后在 IO 调度读取账本，读取失败保留旧 UI 摘要；设置页新增“跨进程匿名摘要”，原本进程内 card 继续负责 notice 发布/有效/裁剪和保存/Store 失败。notice 的 `messageId` Map 不写 Room，重启后不恢复。
- v32→v33 migration 只创建空表和时间索引，不扫描或回填消息、Run、检索审计或第 97–101 阶段人工统计。旧版本备份继续通过 `CURRENT_VERSION=33` 迁移，未来版本备份仍按既有高版本拒绝策略处理。production enforcement、知识检索与排序、普通聊天、Workflow/后台、ANN 和自动索引重建均未改变。
- TDD 先以缺少 `telemetry` 的编译失败和 Publisher 仍请求 `NONE` 建立 red；第二轮以 Redmi 上最终异常失败分布为 `null` 建立 red，随后修正为稳定失败枚举；Judge 身份桶加入后，迁移、Store、失败分布和设置页聚焦组合为 `OK (5 tests)`。双轴审查发现公开配置的无盐摘要可枚举，并指出缺少 2,001 条裁剪边界；改用 Keystore HMAC 后 Store `4/4` 证明落库桶不等于公开 SHA-256，且第 2,001 条会删除最旧记录。最终完整本地 `141/141` tasks（`2m 38s`）、JVM `734/734`、Lint `0 error / 51 warnings`、三类 APK 与 Release lintVital 通过；Redmi 保持唤醒后的最终 JUnit XML 为 `248` 条（`236 passed / 12 skipped / 0 failed`），runner 最终打印 `260 tests`，耗时 `1m 51s`。更新后的项目文档首次 corpus gate 为 `OK (1 test)`（`2.505s`），写回审查修复与设备收尾后的最终复验也已通过；固定正式 `v0.1.13` 恢复后测试包不存在、保持唤醒还原为 `0`，crash buffer 为空。

## 第 107 阶段：第三条独立同日 Shadow 记录（完成）

- 当前源码 Debug 使用正式证书覆盖 Redmi 后保留 Room v33、Provider/Profile 和前两条匿名记录。临时导入 `docs/answerability-shadow-binding.md` 为 `xiaoling-stage107-shadow.md`，形成 revision `1`、`8` 个 chunks、`16.3 KB`；Embedding 未建立，真实 Agent 使用词法兜底。
- 首次较宽请求连续执行 4 次 `knowledge.search`，第五次在参数校验处触发工具预算上限，Run 收敛为 `BUDGET_EXHAUSTED`。没有成功答案，因此一次性开关继续保持开启，账本、attempt 和失败分桶均未变化。第二次使用已验证查询模式后只执行 1 次检索，Run `COMPLETED`，开关自动关闭，并新增 `COMPLETED / BOUND / ACCEPT` 记录。
- 第三条记录时间为北京时间 `2026-07-29 12:52:23.355`，距第二条 `4 小时 38 分 33.243 秒`；attempt `1`，耗时/TTFB `7288/7274ms`，Prompt `6664B`，Tokens `1715/314/2029`，usage `1`，失败全为 `0`。累计三条完成/绑定/接受 `3/3/3`、Judge 匿名桶 `1`、总跨度 `5 小时 24 分 46.689 秒`，仍只作为同日独立窗口。
- 真机首次展示暴露 Stage 106 夹具把带毫秒的真实时间截成整秒后误记为 `46 分钟 14 秒`。Room 精确差为 `46 分钟 13.446 秒`，页面按秒显示 `46 分钟 13 秒`；JVM 与 Compose 夹具改用真实 epoch millis，聚焦 JVM `3/3` 和 `assembleDebugAndroidTest` 通过。
- 应用 UI 删除临时会话和知识文档，精确删除下载文件。最终 documents/chunks/messages `0/0/0`、空壳会话 `1`、Agent Run `4`（完成 `3`、预算耗尽 `1`）、Shadow rows `3`、Provider/Profile `1/1`、Shadow `false`、失败合计 `0`，测试包不存在。同步后的 Redmi 项目文档 corpus 首次/最终单项均为 `OK (1 test)`、耗时 `2.687s / 2.606s`，测试包均随后卸载。没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release；JSON/SAF、校准和 production enforcement 继续关闭。

## 第 106 阶段：Shadow 时间窗口证据投影（完成）

- `projectAnswerabilityShadowWindowEvidence()` 只读取 `KnowledgeAnswerabilityShadowPersistentSummary.oldestRecordedAt / latestRecordedAt`，按设备本地时区生成最早/最新文本，并用实际毫秒差格式化天、小时、分钟和秒；缺失或逆序时间固定显示未知。
- 设置页在跨进程匿名摘要中展示时间范围与跨度，并明确“不自动判定为分隔窗口”。该投影不定义时间阈值，不调用 Judge、不写 Room，也不改变离线评测资格、JSON/SAF 或 production enforcement。
- TDD 使用第 103/104 阶段真实时间建立 Red，缺少投影函数时按预期编译失败；最小实现后经复核补齐单端缺失与时间逆序边界，聚焦 JVM `3/3`。既有 Compose instrumentation 同步改用“授权下一次答案可回答性 Shadow”语义并断言范围/跨度，`assembleDebugAndroidTest` 成功。没有安装 APK、连接 Redmi 或运行 instrumentation。

## 第 105 阶段：单次显式 Shadow 采样窗口（完成）

- `AgentAnswerabilityShadowPublisher.publish()` 新增必填的 `tryConsumeObservationWindow` seam，不提供默认放行。只有候选存在、答案持久化成功且原子消费返回 `true` 时才调用 `observe()`；候选缺失、保存失败、提前撤销或授权已被并发答案消费都会保守取消旁路。
- `AnswerabilityShadowObservationWindowGate` 在同一 `synchronized` 临界区读取开关并执行关闭写入；`XiaoLingViewModel` 通过该 Gate 调用既有 `updateAnswerabilityShadowEnabled(false)`，同步关闭 UI 状态并持久化偏好。观测已经开始后即使 Judge 返回未知、取消或异常，也不会留下持续授权。
- 设置页标题、辅助说明和无障碍语义改为“授权下一次”，并明确每次显式开启最多启动一轮观测。Room v33、Judge 重试、匿名账本、notice、Workflow/后台和 production enforcement 均不改变。
- TDD 先增加消费顺序、候选缺失和提前撤销测试，Red 阶段因缺少消费 seam 编译失败；双轴审查随后发现检查与关闭分离存在并发竞态，第二轮 Red 以 20 路并发消费者证明原子 Gate 尚不存在。最终 Publisher `10/10`、Gate 并发 `1/1`，聚焦 JVM 合计 `11/11`。本阶段按分级验证不运行完整 JVM、Lint、APK、Redmi instrumentation 或 Release，也不采集新的真实 Shadow 样本。

## 第 104 阶段：第二条真实 Shadow 样本与冷启动摘要修复（完成）

- Redmi `wsvwypiz7xwslvl7` 在第 103 阶段完整清理并重新启动进程后导入 `docs/answerability-shadow-binding.md`，形成 revision `1`、`5` 个 chunks、`11.4 KB`。Embedding Provider 在该窗口不可用，查询 `anonymous shadow calibration validation` 通过词法兜底命中 `1` 个 chunk；前台直接 `/agent` 完成知识检索、答案/引用保存和真实 Judge。
- 新记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7645/7632ms`，Prompt `3967B`，输入/输出/总 Tokens `905/372/1277`，usage `1`，十类失败计数均为 `0`，时间 `2026-07-29 08:13:50`（北京时间）。与首条累计为观测 `2`、Judge 身份桶 `1`、完成/绑定/接受 `2/2/2`、attempt `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`、usage `2`，全部失败分桶为 `0`。
- 两条记录只相隔约 `46` 分钟。本次只证明完整清理与进程重启后的独立短间隔链路仍可用，不作为长期低频窗口，不足以进入 JSON/SAF、显式授权评测集、独立阈值校准或 production enforcement；当前窗口不再继续采样，下一步等待真正分隔开的使用窗口。
- 冷启动复验发现数据库已有 `2` 条记录，但设置页跨进程摘要显示全零。根因是 `refreshAnswerabilityShadowPersistentSummary()` 先异步读取真实摘要，随后初始化完成时整表重建 `uiState` 只保留 Shadow 开关和进程内摘要，遗漏跨进程摘要并用默认零值覆盖。现在使用纯 `mergeAnswerabilityShadowInitializationState()` 保留三项运行态，新增 JVM 回归固定该合并边界；Redmi 冷启动后设置页显示观测 `2`、Judge 身份 `1`、完成 `2`、接受 `2`、Judge 尝试 `2`、累计耗时 `17308ms`、Tokens `4547`。
- 清理通过应用 UI 删除临时 Agent 会话正文和知识文档，并精确删除 Redmi 下载文件。最终停进程快照为 documents/chunks `0/0`、messages `0`、应用自动保留空壳会话 `1`、Agent Run `2` 且均为 `COMPLETED`、Shadow rows `2`、Provider/Profile `1/1`、Shadow 关闭、production enforcement 偏好不存在、测试包和临时下载文件不存在。删除会话没有删除旧 Run，符合旧 Run 保持不变的审计边界。
- 分级门禁为新增 `XiaoLingInitializationStateTest` 聚焦 JVM、Debug APK 和 AndroidTest APK 构建；三项均通过。正式证书签署的 Debug/Test APK 只覆盖安装到 Redmi，当前文档 corpus 前两轮均为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充设备收尾并重新构建 AndroidTest APK 后最终复验同样通过。测试包已卸载；主应用冷启动 `3385ms`，Activity resumed、PID 存活、crash buffer 为空。没有运行完整 JVM、Lint、默认完整 instrumentation、Release 或模拟器测试。

## 通用执行恢复矩阵：成功 ToolResult 缺 typed 验证结论闭环审计（完成）

- `AgentRunResumePolicy.assessCommittedToolVerification()` 的短路顺序固定为：先查当前工具定义，再由 `ToolExecutionRecoveryEvidencePolicy` 审计历史定义、成功结果、`COMMITTED` 回执和幂等键，最后查询当前工具是否开放只读恢复验证。定义缺失、回执损坏和能力未开放分别保留独立稳定处置码。
- 新增两条公共策略边界 JVM 回归：`definition=null + support=false` 返回 `TOOL_DEFINITION_UNAVAILABLE`；定义存在但 receipt 缺失且 `support=false` 返回 `COMMITTED_EFFECT_EVIDENCE_INVALID`。两条错误路径都断言 support 回调未被调用；既有证据完整但 `support=false` 用例继续返回 `COMMITTED_VERIFICATION_UNAVAILABLE`。
- 本轮没有新增 `AgentRunResumeKind`、恢复载荷、Repository 事务、Room Schema 或恢复工具白名单。缺 typed 验证结论时仍不得使用 `ToolResult.verified` 猜造状态，也不调用旧 Executor、LLM 或 Workflow；调整只让 fail-closed 原因忠实对应最早可证明的损坏边界。
- 强制本地门禁为 `141/141` tasks（`4m 15s`）、JVM `734/734`、Lint `0 error / 52 warnings`、Debug/AndroidTest/R8 Release APK 与 Release lintVital；仅 Redmi 默认完整为 `OK (243 tests)`、耗时 `95.348s`。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `954f71d5a90a6f2b63160490eab45ea67486b92f3fe8275ca7cb15498a4de6b5 / 4ecb44ae0a189cd956b9e4f12d5827d5d2477be981ea6ed371c71a0cf6ab3fae`；Release 通过 zipalign、v2 正式证书与单签名者校验。最终文档重新打包后已通过 Redmi corpus gate；正式 `v0.1.13` 已恢复，测试包已卸载、保持唤醒已还原为 `0`，crash buffer 无小灵相关异常。

## 通用执行恢复矩阵：持久化失败工具验证原子失败终态结算（完成）

- `ToolVerificationStatus` 新增 `FAILED`，`RunEventMetadata.ToolVerification` 增加可空 `reason`，Codec 完整往返新状态与原因。Runtime 在验证异常时先写 typed `tool.verify(FAILED, reason)` 和 Tool Ledger 验证锚点，再经过故障注入点抛出原异常；协程取消与进程终止模拟继续直接传播，其他可恢复验证异常只捕获 `Exception`，不吞掉 `Error` 或编程级故障。
- `AgentRunResumeKind` 新增 `PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT` 及只携带原 ToolCall、验证 Step 和稳定原因的恢复载荷。策略只接受 `VERIFYING`、完整 v20 Ledger、成功结果与完整 `PASSED` 前缀、唯一失败链尾、结果后 `Available` 预算、最后运行中的验证 Step、完整 typed Step/Event 身份、无待审批和无尾随事件；任何 Legacy、预算、原因、身份或状态漂移都返回稳定 `RESTART_REQUIRED`。
- `RoomAgentRunRepository.closeInterruptedRuns()` 在既有 `database.withTransaction` 内优先执行验证失败结算。事务重新读取 Run/Step，条件更新验证 Step 与 Run 为 `FAILED`，并写入 typed Recovery、`run.failed` 与 `run.status`；重复和双 Repository 并发只允许一次成功，强制 `run.failed` 插入失败时 Run、Step 和全部新事件整体回滚。
- 该路径不再次调用 Executor、验证器或 LLM，不追加第二条验证事实、不构造成功总结，也不继续 Workflow。Runtime 故障窗口测试证明 Executor 只执行一次；结算后的原 Run 保持稳定失败证据，旧 Run 不变。
- 双轴审查分别发现过宽 `Throwable` 捕获和预算 Legacy 可误入专用结算；修复为 `Exception` 与预算 `Available` 硬门槛，并增加完全无预算、缺少结果后预算的 fail-closed 测试。强制本地门禁为 `141/141` tasks（`3m 35s`）、JVM `732/732`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 与 Release lintVital。仅 Redmi 的并发、回滚与 Runtime 组合为 `OK (3 tests)`，默认完整为 `OK (243 tests)`、耗时 `96.162s`。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `1d39a89b3bd183253a1e217f3d32f9727cfa957bdcc6b2f884915c6251455fde / ffae97ee1406b667d93c7c9b436bafb50a73f8284d861595380c16415714fb36`；Release 通过 zipalign、v2 正式证书与单签名者校验。当前文档 corpus 首轮/中间复验为 `OK (1 test)`（`2.907s / 2.471s`），最终文本 gate 也已通过；正式 `v0.1.13` 已恢复，冷启动 `602ms`，版本、前台 Activity、PID、测试包卸载、保持唤醒和空 crash buffer 已核对。

## 通用执行恢复矩阵：持久化失败 ToolResult 原子失败终态结算（完成）

- `AgentRunResumeKind` 新增 `PERSISTED_TOOL_FAILURE_SETTLEMENT`，并由 `AgentPersistedToolFailureRecovery` 只携带事务结算需要的原 ToolCall、执行 Step 和稳定失败原因。失败 ToolResult、事件锚点与 Ledger 来源在策略资格评估内完成核验，不向 Repository 重复透传；`AgentRunResumeAssessment` 强制恢复类型与载荷同时出现，Repository 不会收到半份结算指令。
- `AgentRunResumePolicy.assessPersistedToolFailureSettlement()` 只在 `EXECUTING` 分支评估，并且只接受 v20 完整非空独立 Tool Ledger。前序执行必须全部成功且 `PASSED`；唯一链尾结果必须失败、错误非空且无验证，结果后恰有一份完整预算快照；执行/验证 Step 必须与账本一一对应，链尾执行 Step 是最后一个 Step 且仍为 `RUNNING`；Profile、审批、Run 终态字段与尾随事件也必须保持严格形状。event fallback、预算缺失、成功结果待验证和任何身份/步骤/尾部漂移都返回 `RESTART_REQUIRED` 或继续既有 fail-closed 分类。
- `RoomAgentRunRepository.closeInterruptedRuns()` 在原有 `database.withTransaction` 内优先执行失败结算。`settlePersistedToolFailure()` 先重新读取 Run 与执行 Step，要求状态仍为 `EXECUTING / RUNNING`，随后把 Step 置为 `FAILED`，条件更新 Run 为 `FAILED`，并写入 typed `run.recovered(PERSISTED_TOOL_FAILURE_SETTLEMENT)`、`run.failed` 与 `run.status`。状态并发漂移由 CAS/事务拒绝；任一审计事件插入失败会整体回滚。
- 该路径复现正常 Runtime 在失败 ToolResult 后的控制面终态，不调用 Executor、验证器或 LLM，不追加 ToolResult/`tool.verify`，不构造成功可信上下文，也不继续 Workflow。失败结算 marker 不改变既有证据指纹排除规则；结算后的 `run.failed` 进入稳定终态指纹，重复读取保持一致。
- TDD/JVM 已覆盖正例、前序/链尾/预算/Profile/审批/Step/Event/Run 漂移与 Codec round-trip；Room 真机用例已覆盖磁盘结算、Runtime 故障注入后 Executor 仅执行一次、双 Repository 并发只有一个成功者、重复进入无变更，以及 `run.failed` 强制插入失败时事务整体回滚。双轴审查发现并修复 Step sequence、typed 创建/完成事件身份未核验，以及恢复载荷包含 Repository 不消费字段的问题。强制本地门禁为 `141/141` tasks（`3m 1s`）、JVM `726/726`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 与 Release lintVital；仅 Redmi 默认完整为 `OK (240 tests)`、测试耗时 `93.258s`、墙钟 `95.73s`。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `75a62310b023d090eebb89b702f1276fba86b015bbec5a865c660620388a4b14 / 74f546b4f8c77f497ebd5eb5058e4ed850464bdfbbe99aeed14cd8283a655e9a`；Release 通过 zipalign、v2 正式证书和单签名者校验。更新后的文档 corpus 首次复验为 `OK (1 test)`、耗时 `2.644s`，写回完整门禁与设备收尾后的复验同为 `OK (1 test)`、耗时 `2.553s`；正式 `v0.1.13` 冷启动 `499ms`，设备版本、前台 Activity、PID、测试包卸载、保持唤醒还原和空 crash buffer 已核对。

## 通用执行恢复矩阵：已提交与已验证控制面幂等收尾（完成）

- `RunEventMetadata.StepCreated / StepStatus` 与 Codec 新增 typed Step 身份，保存 `stepId`、sequence、step type 与 from/to status；`AgentRunResumePolicy` 逐项核对最后验证 Step、恢复 marker、恢复总结 Step 和尾随事件。仅有字符串事件类型、错绑其他 Step、损坏 metadata、总结后业务事件或 `COMPLETED recovery.summarize` 缺 typed 总结事件都会返回 `RESTART_REQUIRED`。
- `RoomAgentRunRepository.ensureRecoveryMarker()` 在一个 Room transaction 中重新读取 Run 与完整事件流。同一边界只允许零或一条 marker；存在记录时必须同时匹配 `resumeKind / recoveryBoundaryKey / fromStatus / toStatus / reason / message`，先合法后冲突、重复、损坏或新旧格式半缺字段都拒绝。committed 恢复的状态 CAS、`run.status` 与 marker 同事务提交；各恢复入口随后重新读取 Room 并重新运行策略，不能用写入前快照继续。
- `closeInterruptedRuns()` 把活动 Step、PENDING Approval、typed Recovery、`CANCELLED` 状态和尾随 `run.status` 放入同一个 Room transaction；并发状态漂移由 CAS 拒绝，进程中断不能留下 Run 已终态但子账本或恢复结论只完成一半的可见状态。
- 全部工具已验证后的 Runtime 只使用持久化 ToolResult/Verification 构造本地可信总结。策略允许尚未创建总结、`RUNNING` 总结（typed 总结事件写入前或后）和总结 Step/Event 已完成但 Run 未终态三个控制面阶段重入；总结 Step 与 `run.recovery_summary` 在 Repository transaction 内 get-or-create，`updateStep()` 与终态 Run 写入继续幂等。两个协调器同时恢复同一磁盘边界时只保留一个总结 Step、一个总结事件和一个终态。
- 该实现不恢复旧 LLM 规划/总结协程，不调用 Executor、不追加第二条 ToolResult/`tool.verify`，也不继续 Workflow 后续步骤。旧 marker 只在确实同时缺少新边界键和恢复类型、状态与固定文案一致时兼容；任何可疑证据都 fail-closed。Room Schema 保持 v32。
- 验证覆盖 marker 冲突、typed Step 身份、三种总结崩溃尾部、已完成总结缺事件、尾随业务事件、状态 CAS/事务回滚、启动关闭原子收敛、重复与双协程并发恢复，以及旧 LLM/Executor 不可达。恢复聚焦 JVM `123/123`，完整 JVM `717/717`；强制 Gradle `141/141` tasks（`3m 14s`）、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 与 Release lintVital 通过。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `09c360e3a8429e72dd82bf32b21f398c6ae77fa7eb8d3e0dde4c979d223dc6ef / 5878510423499f3de1b1764376b24573abcc04c3d9440b94a97f000e48a14da8`；Release 通过 zipalign、v2 正式证书与单签名者校验。仅 Redmi Room 定向为 `OK (36 tests)`、耗时 `8.434s`。默认完整首轮在设备锁屏时产生 `59` 条 Activity/Compose 前台失败；失败单项解锁复跑为 `OK (1 test)`，保持唤醒后的完整套件为 `OK (237 tests)`、耗时 `93.062s`，证明首轮属于环境干扰。当前文档语料首次为 `OK (1 test)`、耗时 `2.447s`，最终文档重新打包后复验同为 `OK (1 test)`；随后恢复正式 `v0.1.13`、卸载测试包并还原保持唤醒设置。

## 通用执行恢复矩阵：尚未提交受控关联重试（完成）

- `AgentNotCommittedReplayQualificationPolicy.assessRecovered()` 只接受已经收敛为 `CANCELLED` 的旧 Run，并要求最后的恢复链恰好为 typed `run.recovered` 与一个无 metadata 的 `run.status=CANCELLED`；`fromStatus=EXECUTING`、`toStatus=CANCELLED`、`RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE`、`NOT_COMMITTED` 和冻结证据指纹必须同时稳定。它只构造不落库的收敛前视图复用完整资格策略，不修改旧 Run。
- `AgentRunRetryCoordinator` 在请求和确认时分别从 Room 读取最新 Detail，并强制展示 `NOT_COMMITTED_CONTROLLED_REPLAY` 专用确认；处置码、证据 code/fingerprint 或资格任一漂移都会拒绝或刷新确认。普通 `NOT_COMMITTED` 仍保持无需确认的既有重试语义。
- ViewModel 使用来源 Run 的 Profile 快照执行 preflight，不使用当前选中 Profile。`AgentRunUseCase.runControlledReplay()` 在创建新 Run 前第三次读取 Room，以生产 Registry 重新核对来源 Profile 和完整资格；随后用同一来源 Profile/Provider 构造 Profile-scoped Registry。Runtime 还会再次匹配恢复契约，形成确认后、创建前和执行前的连续 fail-closed 边界。
- `MinimalAgentRuntime.runControlledReplay()` 创建带 `retryOfRunId` 的新 Run和全新 ToolCall ID，持久化来源 Run、来源 ToolCall、新 ToolCall 与定义指纹；它不进入 LLM planning，仍通过正常审批门禁创建新审批，批准后只执行一次冻结调用并进入总结。旧 Run、旧 Tool Ledger、旧审批和旧 Executor 均不写入；Workflow 与后台入口没有接线。Room Schema 保持 v32。
- JVM 覆盖普通/受控确认分流、Room 双重读取、处置码和指纹漂移、收敛状态链、恢复后异常业务事件、当前定义漂移、来源 Profile、无模型规划、新 ToolCall、新审批和单次执行。双轴审查修复真实 Room 尾事件顺序、测试夹具强转和三处恢复 metadata 解析重复；最终完整 JVM 为 `707/707`。
- 强制本地门禁为 `141/141` tasks（`2m 39s`）、JVM `707/707`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 与 Release lintVital 全部通过。Debug/Release APK 为 `23,403,609 / 3,187,250` 字节，SHA-256 为 `f8595e8671da28b59b87fbe85b2732d481263f39c1df3b60d17e1df6276764e0 / 7593288da547e95782da1b45d7a7e660dbbcab6d8ffe77102dcf8022636c6a02`，Release 为 `0.1.13 (14)` 且通过 zipalign、v2 正式证书和单签名者校验。仅 Redmi 的真实磁盘纵向单项为 `OK (1 test)`、耗时 `1.573s`；专用 Compose 对话框为 `OK (1 test)`、耗时 `2.306s`；默认完整 instrumentation 为 `OK (235 tests)`、耗时 `92.954s`。当前文档第一次重新打包后的项目语料为 `OK (1 test)`、耗时 `2.405s`，写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`、耗时 `2.546s`。正式 Release 已无损恢复，冷启动 `532ms`，版本 `0.1.13 (14)`、`MainActivity` resumed、PID 存活、测试包卸载、保持唤醒关闭和空 crash buffer 均已核对。未向在线模拟器发送安装或测试命令。

## 通用执行恢复矩阵：尚未提交安全重放资格（完成）

- `ToolDefinition` 新增默认 `DENY` 的 `notCommittedReplayPolicy` 与正整数 `recoveryContractVersion`。只有同时声明 `ToolReplaySafety.IDEMPOTENT_BY_KEY`、`ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL` 和 `ToolApprovalPolicy.REQUIRE_CONFIRMATION` 的工具才能 opt-in；当前仅 `notes.create`、`memory.remember`。构造期约束阻止非幂等或无需审批工具误开资格。
- `ToolDefinitionRecoveryContract` 把 Schema/契约版本、名称、说明、风险、审批/验证/重放策略、审计时序、超时、后台能力、Android 权限、业务校验器数量与完整输入 Schema 做长度前缀规范化后计算 SHA-256。Runtime 在 `tool.call.proposed / validated` 事件冻结同一 `ToolDefinitionRecoverySnapshot`；Codec 对未知未来策略或损坏快照返回 `null`，历史 Run 不会被当前定义事后升级。业务校验器实现不可序列化，改变语义时必须同步递增契约版本。
- `AgentNotCommittedReplayQualificationPolicy` 只接受 `EXECUTING` 且 Tool Ledger 完整的 Run：链尾必须 validated、无 ToolResult 与 `TOOL_EXECUTE`，前序调用必须成功且验证通过；原 Profile 必须包含该工具，当前 Registry 指纹必须与 proposed/validated 快照一致。链尾还必须有唯一 `APPROVED` 审批，requested 必须原始为 `PENDING`，requested/decided 的名称、风险、参数和定义指纹一致，事件严格按 validated→requested→decided，最后一个审批 Step 已完成且之后无任何步骤。
- `RoomAgentRunRepository` 在创建审批时冻结当前定义指纹，决定审批时从唯一 requested 事件继承同一指纹，避免 Registry 后续变化重写用户批准语义。Room Schema 仍为 v32，不新增 migration。磁盘完全关闭并重开的 instrumentation 证明资格与启动收敛只依赖持久化 Profile、Tool Ledger、审批事件和当前 Registry，不依赖旧进程对象。
- 资格通过时 `AgentRunResumePolicy` 仍返回 `RESTART_REQUIRED`，只把处置码细分为 `NOT_COMMITTED_REPLAY_ELIGIBLE`。`closeInterruptedRuns()` 继续将旧 Run 与活动 Step 收敛为 `CANCELLED` 并写入 `run.recovered`；本阶段没有重放 ToolCall、调用旧 Executor、恢复模型协程、继续 Workflow 或原地修改旧 Run。该码只为后续用户控制的关联新 Run 入口提供持久化资格。
- TDD 新增 11 条 JVM，覆盖正例、当前定义/审批指纹/参数漂移、requested 非 `PENDING`、审批事件倒序、历史契约缺失、默认 `DENY`、执行步骤仍为 `COMMIT_UNKNOWN`、Codec round-trip 和未知策略 fail-closed；既有 Runtime 审计测试增加 proposed/validated 同契约断言。双轴审查发现并修复 decided 指纹、requested 状态和事件顺序三个缺口。
- 强制本地门禁为 Gradle `141/141` tasks（`3m 19s`）、JVM `694/694`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 与 v2 正式单签名。Debug/Release APK 为 `23,387,225 / 3,187,250` 字节，SHA-256 为 `9b298babd168842031ad5221b2b1c488d5bc7a2b2ee046efdad101ad9f468c97 / c861055daed1ff8cf3264439c1795ed4085fe17b2220fc1c405e67d963e1cbbe`。仅 Redmi 磁盘重开单项为 `OK (2 tests)`、耗时 `0.783s`；解锁并启用 USB 保持唤醒后的默认完整 instrumentation 为 `OK (233 tests)`、耗时 `90.924s`，随后已关闭保持唤醒。同步后的最终文档语料单项为 `OK (1 test)`。

## 通用执行恢复矩阵：提交状态未知（完成）

- 新增 `AgentRunRecoveryEvidenceAssessment.CommitUnknown` 与稳定 `AgentRunRestartDispositionCode.COMMIT_UNKNOWN`。恢复策略只在独立 Tool Ledger 可证明链尾 ToolCall 已 validated、`TOOL_EXECUTE` 步骤已经持久化且 ToolResult 缺失时返回 `RESTART_REQUIRED / COMMIT_UNKNOWN`；证据边界固定说明“无法证明副作用未发生”。
- `validated` 事件发生在审批和 Executor 之前，因此不能单独代表工具已执行。共享 `hasPersistedToolExecutionBoundary()` 要求执行步骤数量与调用链一致、前缀执行步骤完成、链尾执行步骤为 `RUNNING / COMPLETED`；仅 proposed、仅 validated 但无执行步骤或步骤链不一致均保留 `RECOVERY_EVIDENCE_INVALID`。v19 及更早的 typed-event fallback 也只有在唯一链尾 validated 调用和执行步骤可核对时才标记提交未知。
- 启动收敛前的重试证据复用同一执行边界。proposed-only 和“Run 状态已写成 EXECUTING、执行步骤尚未落库”的窗口固定为 `NOT_COMMITTED`，不会与同一 `run.recovered` 中的恢复处置冲突；真正缺结果的执行步骤仍为 `COMMIT_UNKNOWN` 并要求用户确认关联新 Run。旧 Run 和活动 Step 收敛为 `CANCELLED`，不恢复旧模型协程、不调用旧 Executor、不继续旧 Workflow，也不补造 ToolResult。
- TDD 新增 5 条 JVM，覆盖 validated+执行步骤缺结果、proposed-only、validated-only 无执行步骤、legacy typed-event 和重试证据一致性；Room 新增 2 条跨 Repository 实例测试，覆盖 `COMMIT_UNKNOWN` 与 `NOT_COMMITTED + RECOVERY_EVIDENCE_INVALID` 的持久化。双轴审查发现并修复了“validated 早于执行边界”和“重试证据只看 Run 状态”两处问题。
- 强制本地门禁为 Gradle `141/141` tasks（`3m 11s`）、JVM `683/683`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 与 v2 正式单签名。Debug/Release APK 为 `23,370,841 / 3,187,250` 字节，SHA-256 为 `d5470aa909bae8a93ff10bcb088ef9ce3b36bbec1da17caaf8ed3c001716b936 / cf0a2cc320bb7ebc6828850e271860ed775a5ebcdc65bc5e9be18e0c5b267dc3`。仅 Redmi `wsvwypiz7xwslvl7` 的两个 Room 单项分别为 `OK (1 test)`、耗时 `0.592s / 0.507s`；默认完整 instrumentation 为 `OK (231 tests)`、耗时 `90.302s`，最终文档语料为 `OK (1 test)`。未向在线模拟器发送安装或测试命令。
- 相邻的“尚未提交安全重放资格”已由上一节完成；它仍只签发关联新 Run 的未来资格，不允许任何旧 Run 原地重放。

## 功能对话框归属收口（横向结构工程完成）

- 本轮只迁移四组具备独立业务状态和动作边界的对话框：Agent Run 重试证据确认进入 `ui/agenttask`，Workflow Run 重试步骤复用确认进入 `ui/workflow`，长期记忆编辑/删除进入 `ui/memory`，本地 Skill 删除进入 `ui/agentskill`。对应草稿/确认类型现在定义在各自 contract，不再由功能模块反向依赖应用根 `ui` 包。
- `XiaoLingUiState` 继续保存跨页面存活的源状态，各模块 Projection 将待确认对象与按稳定 ID 推导的 busy 状态收进功能 `UiState`；`XiaoLingContent` 在页面内容之外全局挂载四个 dialog host，因此用户切换 pane 后待确认操作不会被页面生命周期静默丢弃。
- `XiaoLingViewModel` 继续拥有真实重试、记忆和 Skill 持久化副作用，但 Agent、Workflow 与 Memory 的确认/取消/编辑动作通过对应 actions interface 暴露；Skill 删除继续使用显式确认/取消回调，不为 Android 文件选择器制造新的全局接口。备份恢复仍留在根层，因为它同时拥有 `ActivityResultLauncher`、`Uri`、Room 替换、Keystore 提示和重启语义；`CenterNoticePopup` 与 `SettingsPage` composition root 同样保留。
- `XiaoLingApp.kt` 从启动收尾后的 `1,103` 行降到 `817` 行；新增四个 dialog host 共 `461` 行，既有页面与文案未改变。Projection JVM 直接覆盖待确认身份与 busy 推导；新增 7 条 Compose 测试覆盖证据/步骤文案、确认/取消、记忆字段编辑和忙碌禁用。
- 已验证：JVM `678/678`，`lintDebug`、Debug APK、AndroidTest APK、R8 Release APK 和 `lintVitalRelease` 通过。使用同一正式证书签署临时 Debug/Test APK 并无损覆盖到 Redmi `wsvwypiz7xwslvl7` 后，新增对话框聚焦测试为 `OK (7 tests)`、测试耗时 `9.247s`，默认完整 instrumentation 为 `OK (229 tests)`、测试耗时 `89.151s`；最终长期文档重新打包后的项目语料单项为 `OK (1 test)`。验收后已覆盖回正式 `v0.1.13` Release、卸载测试包并确认 `MainActivity` 前台、进程存活且 crash buffer 为空；没有向在线的 `emulator-5554` 发送安装或测试命令。
- 停止条件已经满足：不继续为了行数拆 `XiaoLingApp.kt` 或 `XiaoLingViewModel.kt`，也不迁移备份恢复和全局通知。下一主线转向通用执行恢复的持久化恢复矩阵，旧 Run 保持不变，提交状态未知继续 fail-closed。

## 设置根页垂直 UI module（横向结构工程）

- 新增 `ui/settingsroot` 垂直模块。`SettingsRootUiState` 只保留主题、当前 Agent Profile、Provider/模型、Shadow、Skill、Workflow、Agent Run、进程退出观察和备份忙状态的显示摘要；`SettingsRootActions` 只暴露主题切换、13 个设置子页入口及备份导入/导出动作。
- `SettingsRootProjection` 按稳定 Profile ID 绑定当前身份，并统一统计启用模型、Skill 来源、Workflow 运行态和 Agent Run 完成态。页面拥有原 14 项顺序、动态摘要、主题菜单及备份交互；`SettingsPage` 继续持有 pane 分派、Android launcher、跨页导航和真实副作用适配，不机械迁移 composition root。
- `XiaoLingApp.kt` 从 `1,317` 行降到 `1,097` 行，`SettingsRootContract.kt / SettingsRootPage.kt` 为 `88 / 264` 行。TDD 先后取得 projection 和 page/actions 未定义的编译 Red，再转绿并删除旧内嵌页面；双轴 review 的 Spec 轴无 finding，Standards 轴无硬性违规，两个结构性判断均属于保留显式 composition root 与窄 contract 的有意取舍。
- 强制本地门禁为 `140/140` tasks（`1m 58s`）、JVM `678/678`、Lint `0 error / 50 warnings`，Debug、AndroidTest、Release APK 和 Release lintVital 均通过；Release 通过 zipalign 与 v2 单签名。仅 Redmi `wsvwypiz7xwslvl7` 的页面 Compose 为 `OK (4 tests)`；默认完整 XML 为 `221` 条（`209 passed / 12 skipped / 0 failed`）、耗时 `85.834s`，控制台为 `Finished 233 tests`。Debug/Release APK 为 `23,387,174 / 16,032,726` 字节，SHA-256 为 `309faa26a77d42fccca4108e9849a474ca9ec53ba38e190570facfd82659f757 / cee1e20edd6ce0ae536e9331fa18729e1e793ac946ae6dde08da62734c7962cd`。
- 备份忙时两个图标继续禁用，但父卡点击仍触发导出的既有行为已由测试固定。Room v32、设置子页实现、Provider、Agent Runtime、Workflow、设备工具前台门禁、answerability Shadow 和第 101/102 项状态均未改变。

## 启动、Release Profile 与设置页滚动体验收尾

- Android 12+ 继续由 `Theme.XiaoLing` 的 `windowSplashScreen*` 展示系统 Splash Logo；`MainActivity` 不再经过 `XiaoLingLaunch`，首个 Compose 内容直接进入 `XiaoLingApp`。已删除 Compose 品牌页及其 `880ms` 固定等待、`260ms` Crossfade，避免系统和应用各显示一次启动图。
- 设置根页的标题和主题选择器保持在外层固定区域，只有设置卡片 Column 使用独立滚动状态。新增 `headerStaysVisibleWhenSettingsEntriesScrollToTheEnd`，以“滚到数据备份与恢复后标题和主题入口仍显示”固定公共行为；页面聚焦 Compose 为 `OK (5 tests)`。
- `XiaoLingViewModel` 把 Provider、会话、附件、知识库、网络客户端、Agent Runtime、Workflow、进程退出观察、WorkManager Scheduler 和备份等重型对象改为 `lazy`。`XiaoLingApp` 使用 `withFrameNanos` 先交付可见首帧，再调用只能启动一次的 `initialize()`；冷启动分享继续在初始化完成前排队，不丢失 Intent。
- Manifest 只移除 `androidx.work.WorkManagerInitializer`，保留其他 AndroidX Startup 组件。`XiaoLingApplication : Configuration.Provider` 提供默认 WorkManager 配置，使 `WorkManager.getInstance(context)` 在首次调度时按官方入口初始化，不改变既有 Worker、周期任务或恢复对账语义。
- Release 启用 `isMinifyEnabled=true`和 `proguard-android-optimize.txt`。Compose 依赖带有 Kotlin 2.4 metadata，根工程按 Android 官方兼容表从 `https://storage.googleapis.com/r8-releases/raw` 引入 R8 `9.1.29`；干净 Release 构建成功，原 metadata 警告消失，只剩两条 `Class file resource provider does not support async parsing` 性能能力提示。
- 新增 `baselineprofile` Android Test module 和 `BaselineProfileGenerator.startup`，仅采集真实冷启动，不主动访问设置、Agent 或 Workflow 页。生成命令必须显式限定 `ANDROID_SERIAL=wsvwypiz7xwslvl7`：`ANDROID_SERIAL=wsvwypiz7xwslvl7 JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:generateBaselineProfile --console=plain`。`baseline-prof.txt / startup-prof.txt` 各 `18,011` 行；R8 重写后 Release APK 内 `baseline.prof / baseline.profm` 为 `13,847 / 719` 字节，远低于官方 `1.5 MB` 上限。
- Redmi 上原 Debug 冷启动约 `3.4–3.7s`；最终 R8 Release 覆盖安装后首次 `am start -W` 为 `533ms`，强制 `speed-profile` 编译后三次为 `580 / 504 / 587ms`。这是 Redmi 当次设备与编译状态下的 TTID，不等同所有冷启动的固定 Splash 持续时间。Release 主界面、设置滚到底、前台 Activity、PID 和空 crash buffer 均已真机验证。
- PNG 分享测试在发起缺失图片分享前通过 `snapshotFlow` 监听瞬时 `OperationResult`，明确断言标题为“图片不可用”、`success=false`，并继续验证附件为空、不自动发送，静默丢弃附件不再能通过该回归。
- 最终本地门禁为 JVM `678/678`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 和 Release lintVital 成功。Release 为 `3,170,866` 字节，zipalign、v2 固定证书和单签名者通过，SHA-256 为 `6c28ac665471e4cddda4d58f0c36a79458cadb929bc3fe11c289113cf9ba004e`；Debug 为 `23,354,457` 字节，SHA-256 为 `7394d986be7a12d0b2b0b853d54f7af4ac438017a7f2ec28f843e816ce556c84`。仅 Redmi 默认完整 instrumentation 为 `OK (222 tests)`、耗时 `83.58s`；四份长期文档重新打入 AndroidTest APK 后，项目语料门禁为 `OK (1 test)`。Room v32、分享解析/附件校验业务、设置导航、Provider、Agent Runtime、Workflow、设备工具后台门禁、answerability Shadow 和第 101/102 项均未改变。

## 进程退出观察垂直 UI module（横向结构工程）

- 新增 `ui/processexit` 垂直模块。`ProcessExitObservationUiState` 只包含独立退出账本、加载态和读取错误；`ProcessExitObservationActions` 只暴露刷新；页面自己呈现六类证据、稳定数值、加载/失败/空态和“不关联 Agent Run、工作流或任务”的固定边界。
- 应用壳继续在进入页面前调用 `refreshProcessExitObservations()` 再导航，`XiaoLingViewModel` 只实现动作 interface。刷新仍取消旧 Job、在 IO 线程只调用 `RoomProcessExitObservationStore.latest()`，协程取消继续传播；前台/Worker 的平台 `collect()` 路径没有迁入页面。
- 列表 key 继续与 Room 去重身份一致，固定为 `timestamp|pid|reasonCode|status|processName`。直接 LMK、候选、应用故障、系统资源、受控维护和未归因分类仍由 `system` 层产生，页面不凭时间邻近关联 Agent、Workflow 或 Task。
- `XiaoLingApp.kt` 从 `1,582` 行降到 `1,404` 行，Contract/Page 为 `13 / 217` 行。TDD 先得到三个新 seam 未定义的编译 Red，再以最小 wrapper 转绿并完成迁移；双轴 review 的 Spec 轴无 finding，Standards 轴的文档缺口已在本节及其他三份长期文档修正。
- 强制本地门禁为 `140/140` tasks、JVM `677/677`、Lint `0 error / 50 warnings`，Debug、AndroidTest、Release APK 和 Release lintVital 均通过。仅 Redmi `wsvwypiz7xwslvl7` 的页面 Compose 为 `OK (4 tests)`；默认完整 XML 为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `80.011s`。最终文档语料单项为 `OK (1 test)`。Debug/Release APK 为 `23,354,347 / 16,016,342` 字节，SHA-256 为 `260620b0a6a3ebc0780f7f2c3eeecc3533297ff96ac5515caf14dea11466c265 / 2f919076cd17d58f05522a3a5162b5e80d8ae9086aec6a07ff4115db6328999f`。
- Room v32、退出采集和证据分类、Agent Runtime、Workflow、设备工具前台门禁、answerability Shadow、Foreground Service 和第 101/102 项状态均未改变。

## 网络请求设置垂直 UI module（横向结构工程）

- 新增 `ui/networksettings` 垂直模块。`NetworkRequestSettingsUiState` 只包含当前 User-Agent，`NetworkRequestSettingsActions` 只暴露更新与恢复默认；页面拥有五行编辑区、复制、清空和恢复默认，剪贴板副作用留在 page wrapper，不把 Android 平台对象传入 ViewModel。
- `SettingsPage` 只投影 `state.userAgent` 并传入 Actions/返回回调。`XiaoLingViewModel.updateUserAgent()` 继续去除 CR/LF、截断到 512 字符、即时更新 UI 并写入 `UiPreferenceStore`；清空后本次 UI 保持空字符串，而持久化层保存默认值，重启后恢复默认的既有时序没有改变。
- `XiaoLingApp.kt` 从 `1,404` 行降到 `1,317` 行，`NetworkRequestSettingsContract.kt / NetworkRequestSettingsPage.kt` 为 `11 / 116` 行。TDD 先以新 state/actions/page seam 未定义取得编译 Red，再迁入原 `NetworkRequestSettingsContent` 交互并转绿；双轴审查未发现规范或行为 finding。
- 强制本地门禁为 `140/140` tasks（`2m 26s`）、JVM `677/677`、Lint `0 error / 50 warnings`，Debug、AndroidTest、Release APK 和 Release lintVital 均通过；Release 通过 zipalign 与 v2 单签名。仅 Redmi `wsvwypiz7xwslvl7` 的页面 Compose 为 `OK (1 test)`；默认完整为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `78.642s`。最终文档语料单项为 `OK (1 test)`。Debug/Release APK 为 `23,370,731 / 16,016,342` 字节，SHA-256 为 `8e1d71862a6c6ec428834936bf607bdb15237fc9bfb5e4845e7473c7975034e9 / 0101fed9730bc2787f94471e553d7d75747b5aae3aaa5e5b7c5a1523efd51ccc`。
- 模型列表、Chat Completions、Responses 和后台 Agent 继续共用 `ProviderRequestConfig.userAgent` 的原 Header 构造；Room v32、Provider、Agent Runtime、Workflow、设备工具前台门禁、answerability Shadow 和第 101/102 项状态均未改变。后继设置根页已建立窄投影与 Actions 并迁出页面内容；`SettingsPage` composition root 继续留在应用壳。

## 应用导航宿主迁出（横向结构工程）

- 新增纯 Kotlin `XiaoLingNavigationCoordinator`，统一类型化 `CONVERSATION / SETTINGS` Tab、14 个设置目标、知识文档跳转、五类跨域导航和根页面双击返回。返回只产生 `CLOSE_PROVIDER_EDITOR / SHOW_EXIT_NOTICE / FINISH_ACTIVITY` effect，不直接持有 Activity 或 ViewModel。
- `XiaoLingNavigationController` 作为 Compose adapter 持有可观察状态，并保持旧保存语义：Activity 重建只保存知识文档目标，Tab、设置子页和根返回时间仍回到初始值。`XiaoLingContent` 只消费 controller interface，不再分别维护 `selectedTab / settingsPane / requestedKnowledgeDocumentId / lastRootBackAt`。
- 底栏及其稳定测试 tag 已迁入 `ui/navigation/XiaoLingBottomTabBar.kt`。Provider 编辑器仍优先消费返回；设置子页返回仍清除知识文档目标；Agent 重试、Workflow、记忆来源、运行历史和系统分享仍消费原 ViewModel 一次性导航信号。
- `XiaoLingApp.kt` 从归档提交基线的 `7,018` 行降到 `6,925` 行。新增 module 对外只暴露 controller 状态和少量导航动作；没有按页面制造透传 wrapper，也没有修改 Room、Provider、Agent Runtime、Workflow 或设备工具行为。
- `XiaoLingNavigationCoordinatorTest` 聚焦 JVM `6/6`，覆盖知识跳转、四类对话目标、记忆 Run、Provider 编辑器返回优先、设置子页返回和严格两秒退出窗口。Debug/AndroidTest APK 构建成功；仅在 Redmi `wsvwypiz7xwslvl7` 运行新增 MainActivity 导航单项与既有知识引用跨域 E2E，均为 `OK (1 test)`，未使用 Pixel_9。

## Workflow 管理垂直 UI module（横向结构工程）

- 新增 `ui/workflow` 垂直模块。`WorkflowManagementProjection` 在模块边界一次性按 `workflowId` 聚合定义、Run、ScheduledTask 和周期规则，统一推导 running/scheduling/mutating、编辑/启停/运行/调度资格、任务取消资格和 Run 重试资格；步骤输入/输出快照在投影层容错解码，Compose 不再自行解释 Ledger 结构。
- `WorkflowManagementPage` 只接收 `WorkflowManagementUiState`、`WorkflowManagementActions`、通知权限请求和返回回调，不再接收整份 `XiaoLingUiState` 或具体 `XiaoLingViewModel`。新建、编辑、调度和条目展开状态由模块局部持有；通知权限 launcher、设置导航、全局结果提示和 Workflow Run 重试确认覆盖层继续属于应用宿主。
- `XiaoLingViewModel` 实现 10 个动作组成的 `WorkflowManagementActions`，真实 Repository、WorkManager、Agent preflight 和运行副作用保持原实现。编辑器新增与 `WorkflowDefinitionPolicy` 一致的名称、步骤数和目标长度前置门禁，不改变持久化策略。
- `XiaoLingApp.kt` 从导航阶段的 `6,925` 行降到 `6,217` 行，Workflow 管理页、条目、调度/编辑弹窗和格式化 helper 共迁出约 700 行。该结果来自收口状态投影与动作面，不是仅移动私有 Composable 的透传拆分。
- `WorkflowManagementProjectionTest` 聚焦 JVM `2/2`；fake actions Compose 单项仅在 Redmi `wsvwypiz7xwslvl7` 为 `OK (1 test)`。强制完整本地门禁 `140/140` tasks、JVM `664/664`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；Redmi 默认完整 instrumentation 为 `OK (198 tests)`、耗时 `51.74s`，最终文档语料单项为 `OK (1 test)`。
- Room 保持 v32；Workflow Ledger、调度/停止/恢复语义、设备工具后台门禁、answerability Shadow 和第 101/102 项状态均未改变。后继 Agent 任务中心切片见下一节。

## Agent 任务中心垂直 UI module（横向结构工程）

- 新增 `ui/agenttask` 垂直模块。`AgentTaskCenterProjection` 在模块入口把 loading、error、Run history、selected Run ID 和 retrying Run ID 投影为窄 `AgentTaskCenterUiState`，并只按稳定 Run ID 绑定条目级 `selected / retrying`；页面筛选或列表位置变化不会把操作状态投影到其他任务。
- `AgentTaskCenterPage` 只接收 `AgentTaskCenterUiState`、`AgentTaskCenterActions` 和返回回调，不再接收整份 `XiaoLingUiState` 或具体 `XiaoLingViewModel`。模块自己持有“全部 / 需确认 / 处理中 / 可重试 / 已完成”筛选、首刷、历史指标、Run 卡片和选中详情，并统一呈现 Ledger-first 工具四阶段、双源一致性、步骤、审批、事件、知识审计、重试证据与结构化恢复处置。
- `AgentTaskCenterActions` 只暴露 `refreshAgentRunHistory / selectAgentRun / requestAgentRunRetry`。`XiaoLingViewModel` 继续实现真实 Room 读取、选择和 `AgentRunRetryCoordinator` 路由；应用宿主继续拥有设置导航、全局重试确认覆盖层和重试成功后回来源会话的一次性导航，避免页面接管跨会话生命周期。
- 对话 Run 时间线与任务中心共用 `AgentRunUiPrimitives.kt` 中的状态徽标、Step 行及 Run/Step 中文状态文案；共享的是业务呈现原语，不把任务中心私有卡片或详情反向暴露给聊天页面。
- `XiaoLingApp.kt` 从 Workflow 阶段的 `6,217` 行降到 `5,176` 行；任务中心页面、筛选、指标、卡片、详情、恢复诊断、Ledger/步骤/审批/事件呈现共迁出约 1,000 行。双轴 review 从 `d232bbc` 固定点发现设置入口仍提前刷新，现已移除该宿主调用，让空列表首刷真正由 `AgentTaskCenterPage` 持有；Standards 轴没有硬性违规。该边界来自 state projection、actions interface 和页面所有权，不是只移动私有 Composable 的透传拆分。
- 两轮 TDD 分别以缺少 `AgentTaskCenterProjection` 和 `AgentTaskCenterPage` 的编译失败建立 Red。Projection JVM `1/1`，仅 Redmi `wsvwypiz7xwslvl7` 的页面动作路由、筛选和恢复处置 Compose 为 `OK (3 tests)`。review 修复后强制完整本地门禁 `140/140` tasks、JVM `665/665`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；Redmi 默认完整 instrumentation 为 `OK (199 tests)`、耗时 `52.659s`，最终文档语料单项为 `OK (1 test)`。Debug APK `23,239,600` 字节、SHA-256 `f5210905d08774f6927a4a3ef59f36f7f18253b1678ff3a00da84b87c6bad8ee`；Release APK `15,967,190` 字节、SHA-256 `b36f50f8466db3254040eb5165cee549ae4e705a7b2322e5ab9caffce3bd3ba7`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录其自引用大小或哈希。
- Room 保持 v32；任务筛选、指标、重试资格、证据指纹、旧 Run 不变和关联新 Run 语义均未改变。本轮不采集 Shadow 样本，不进入第 102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。下一项横向结构工程优先迁出长期记忆管理垂直 UI；Provider 管理的扫码、返回和编辑器宿主耦合随后处理。

## 长期记忆管理垂直 UI module（横向结构工程）

- 新增 `ui/memory` 垂直模块。`MemoryManagementProjection` 在模块入口把 loading、error、正式记忆、候选开关与候选列表、搜索、筛选、删除撤销和操作中 ID 投影为窄 `MemoryManagementUiState`；候选只保留仍可决定的 `PENDING / CONFLICT`，记忆与候选的 selected/mutating 均按稳定 ID 绑定。
- `MemoryManagementPage` 只接收窄 UI state、15 项 `MemoryManagementActions` 和返回回调，不再接收整份 `XiaoLingUiState` 或具体 `XiaoLingViewModel`。模块拥有空列表首刷、候选开关、搜索、筛选、候选接受/拒绝、正式记忆列表、来源与召回审计、置顶/启停/过期、编辑/删除入口和跨进程删除撤销呈现。
- `XiaoLingViewModel` 实现动作 interface，继续负责真实 Room 读取与变更、`AgentMemoryCandidateCoordinator`、跨进程撤销和一次性导航信号。该阶段编辑/删除确认弹窗及来源会话/Run 导航 effect 属于应用宿主；后继收口已将弹窗迁入 `ui/memory` 并由宿主全局挂载，来源导航仍不交给页面生命周期。
- `XiaoLingApp.kt` 从 Agent 任务中心阶段的 `5,176` 行降到 `4,644` 行，迁出约 530 行长期记忆页面、卡片、筛选、审计与格式化实现。页面空列表首刷不再由设置入口提前触发；跨重组只刷新一次由专用 Compose 回归测试固定。双轴 review 从 `052f97f` 固定点执行；Spec 轴无 finding，Standards 轴指出页面仍重复解释已由 Projection 限定的候选状态，修复后标签、主按钮文案与冲突标记均由 projection 呈现模型产出，页面不再保留不可达状态分支。
- Projection JVM `1/1`，仅 Redmi `wsvwypiz7xwslvl7` 的动作路由与跨重组首刷 Compose 为 `OK (2 tests)`。强制完整本地门禁 `140/140` tasks、JVM `666/666`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；Redmi 默认完整 instrumentation 为 `OK (201 tests)`、耗时 `54.857s`，最终文档语料单项为 `OK (1 test)`。Debug APK `23,272,368` 字节、SHA-256 `f084cfaa35e6838daffff74e7ffbcbdc2a27c5ae53162046846b258098b650ab`；Release APK `15,983,574` 字节、SHA-256 `88d2fd4ba706b34d3410681748ad443328cae8d25e6c30948a3300ee89019666`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Room 保持 v32；候选采集/接受/拒绝协调、敏感过滤、规范化去重、同主题冲突、FTS、生命周期、来源审计和跨进程删除撤销语义均未改变。本轮不采集 Shadow，不进入第 102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。下一项横向结构工程为 Provider 管理垂直 UI。

## Provider 管理垂直 UI module（横向结构工程）

- 新增 `ui/provider` 垂直模块。`ProviderManagementProjection` 在模块入口按稳定 Provider ID 绑定 selected、单项/批量 syncing 和同步结果，批量同步期间统一投影全部条目为忙碌；编辑草稿优先，行内结果只保留带请求 URL 或耗时的网络结果。
- `ProviderManagementPage` 只接收窄 `ProviderManagementUiState`、14 项 `ProviderManagementActions` 和返回回调，不再接收整份 `XiaoLingUiState` 或具体 `XiaoLingViewModel`。列表、空态、批量/单项同步、新增/编辑/删除入口、二维码与剪切板导入、Base64 辅助、字段编辑、模型获取/勾选和保存入口均由模块拥有；平台扫码与剪切板通过可注入回调形成 Compose 测试 seam。
- `XiaoLingViewModel` 实现动作 interface，原有 Provider 保存、删除、`ProviderModelSyncCoordinator`、Agent Profile 修复和二维码解析实现保持不变。应用壳只投影 Provider 字段，继续统一处理 `manageDraft` 编辑器优先级、系统返回和底栏显隐；聊天页 `ProviderDropdown` 仍属于会话宿主。
- `XiaoLingApp.kt` 从长期记忆阶段的 `4,644` 行降到 `4,003` 行；`ProviderManagementPage.kt / ProviderManagementContract.kt` 分别为 `793 / 85` 行。双轴 review 从 `05a2f99` 固定点执行；Standards 轴无 finding，Spec 轴指出最终文档和真实宿主组合覆盖尚未完成，现已补齐 MainActivity 的编辑器返回/底栏回归并同步四份长期文档。
- Projection JVM `2/2`，仅 Redmi `wsvwypiz7xwslvl7` 的 Provider 列表动作与编辑器字段/平台回调 Compose 为 `OK (2 tests)`，真实宿主设置返回与 Provider 编辑器优先级/底栏显隐为 `OK (2 tests)`。强制完整本地门禁 `140/140` tasks、JVM `668/668`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；Redmi 默认完整 instrumentation 为 `OK (204 tests)`、耗时 `59.619s`，最终文档语料单项为 `OK (1 test)`。Debug APK `23,288,752` 字节、SHA-256 `c03cddc3a08824e3f92302ccd6caff1efa9a25c69a189d95b654e4273f583e66`；Release APK `15,983,574` 字节、SHA-256 `2b3b8c1952125c6a99e7cb2573a08b3ea62732639d628c7b3dc36bd8a1b86566`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Room 保持 v32；Provider 持久化、模型同步、删除约束、选中修复、Agent 启动前校验、扫码参数和跨页面返回语义均未改变。本轮不采集 Shadow，不进入第 102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。后继 Agent Profile 管理垂直 UI 见下一节。

## Agent Profile 管理垂直 UI module（横向结构工程）

- 新增 `ui/agentprofile` 垂直模块。`AgentProfileManagementProjection` 在模块入口按稳定 Profile ID 绑定 selected、mutating、deleteEnabled 和 providerModelValid；列表重排或 Room 返回替换后的新对象不会把编辑、保存或删除动作错绑到其他 Profile。Provider/模型失效只影响可运行性提示，不借机扩大工具、Skill、系统提示词或长期记忆边界。
- `AgentProfileManagementPage` 只接收窄 `AgentProfileManagementUiState`、三项 `AgentProfileManagementActions` 和返回回调，不再接收整份 `XiaoLingUiState` 或具体 `XiaoLingViewModel`。模块自己呈现 Profile 列表、新增/编辑/删除、Provider/模型选择、Chat Completions/Responses、长期记忆开关、工具与 Skill 双向依赖和字段长度门禁；编辑草稿按 Profile ID 持有。原页面复用的文本输入提升为共享 `CompactTextField`，其他宿主页面继续使用同一控件。
- `XiaoLingViewModel` 实现选择、保存和删除三项动作。保存入口仍重新核对 Provider、模型、注册工具、Skill 存在性及其依赖工具，防止旧草稿或非 UI 调用绕过页面约束后扩大能力；删除继续至少保留一个 Profile，Room 保存成功后才发布最终选择与结果。设置返回、底栏显隐、聊天页 Profile 下拉和全局错误提示继续属于应用宿主。
- `XiaoLingApp.kt` 从 Provider 阶段的 `4,003` 行降到 `3,631` 行；`AgentProfileManagementContract.kt / AgentProfileManagementPage.kt / CompactTextField.kt` 分别为 `104 / 610 / 46` 行。双轴 review 从 `8a12c90` 固定点执行：Standards 轴发现长期文档、业务不变量注释和 `configurationValid` 命名问题，已同步文档、补齐贴近实现的中文业务注释并改名为 `providerModelValid`；Spec 轴要求的列表重排、Profile 对象替换和稳定 ID 保存回归已补齐，无范围膨胀。
- Projection JVM `2/2`，仅 Redmi `wsvwypiz7xwslvl7` 的页面动作/编辑器 Compose 为 `OK (3 tests)`，真实 MainActivity 设置返回与底栏为 `OK (1 test)`。强制完整本地门禁 `140/140` tasks、JVM `670/670`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；完整门禁产出的 Debug/Release APK 为 `23,305,195 / 15,999,958` 字节，SHA-256 为 `9cce542e7e2e1bdb8c4801e7566942110e4e6713aa0dd5515e1079755c619fb8 / 39e69ece1c7d9da5afa235e054028ff37e2576034dac32978fe3ab06cf1fedf6`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录其自引用大小或哈希。
- Redmi 最终默认完整 `AndroidJUnitRunner` 的 Gradle 控制台为 `Finished 220 tests`、`BUILD SUCCESSFUL in 1m 22s`；JUnit XML 精确记录 `208` 条（`196 passed / 12 skipped / 0 failed`），耗时 `69.14s`，两种总数差异来自 skipped 统计口径。四份长期文档最终同步并重新构建 AndroidTest APK 后，项目文档语料单项为 `OK (1 test)`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。Room v32、Agent Runtime、Workflow、设备工具前台门禁、answerability Shadow 和第 101/102 项边界均未改变；后继 Agent Skill 管理见下一节。

## Agent Skill 管理垂直 UI module（横向结构工程）

- 新增 `ui/agentskill` 垂直模块。`AgentSkillManagementProjection` 按稳定 Skill ID 绑定启停/删除资格，以真实 Tool Registry 投影每项依赖的已注册/缺失状态，并通过正式 `AgentSkillSelectionCodec` 从最近 Run 的 `skill.selected` 事件生成最多三条版本与 Run 终态审计；格式损坏的旧事件只被忽略，不影响其他 Skill。
- `AgentSkillManagementPage` 只接收窄 `AgentSkillManagementUiState`、五项 `AgentSkillManagementActions` 和返回回调。页面持有空列表首刷、Skill/审计联合刷新和按稳定 ID 展开状态；设置入口不再提前刷新。宿主动作适配器把导入意图交给 Android 文件选择器，并路由 ViewModel 的 Room 刷新、启停和删除请求；该阶段本地 Skill 删除确认属于应用壳，后继收口已将对话框迁入 `ui/agentskill` 并继续由应用壳全局挂载。
- `XiaoLingApp.kt` 从 Agent Profile 阶段的 `3,631` 行降到 `3,497` 行；`AgentSkillManagementContract.kt / AgentSkillManagementPage.kt` 分别为 `126 / 295` 行。双轴 review 从 `adf00bd` 固定点执行：首轮 Standards 指出未消费的 mutating 原始字段，Spec 指出工具依赖和 Run 审计尚未进入 projection；复审继续发现导入请求绕过 Actions 与 ViewModel 审计刷新透传，均已修复。跨 unit/androidTest source set 的小型 fixture 重复因提取成本高于收益而保留。
- Projection JVM `3/3`，仅 Redmi `wsvwypiz7xwslvl7` 的页面动作/稳定展开 Compose 为 `OK (2 tests)`，真实 MainActivity 设置返回与底栏为 `OK (1 test)`。强制完整本地门禁 `140/140` tasks、JVM `673/673`、Lint `0 error / 50 warnings / 0 information`，Debug、AndroidTest、Release APK 和 Release lintVital 全部通过；Redmi 默认完整 instrumentation 为 `OK (211 tests)`、耗时 `70.952s`。Debug/Release APK 为 `23,321,579 / 15,999,958` 字节，SHA-256 为 `cbb7f0e00d7597d288502727fb18fac3db6d2989292451959fff2b459bf10289 / f9862caff455ad8385d7c3a69a152b16a593370c8b716251a4a94a2729a34885`；最终文档语料单项为 `OK (1 test)`。
- Room v32、Skill JSON 校验/持久化、Runtime 选择与审计写入、旧 Run、Agent Profile Skill 白名单、设备工具前台门禁、answerability Shadow 和第 101/102 项边界均未改变。下一轮先从 `XiaoLingApp.kt` 剩余 `3,497` 行重新盘点有完整状态/动作所有权的垂直簇，不按行数制造透传层。

## 会话主界面垂直 UI module（横向结构工程）

- 新增 `ui/conversation` 垂直模块。`ConversationProjection` 把会话标题/列表、Provider、消息、知识引用、Agent Run/审批和输入区状态收口为四组窄 UI state，并统一派生普通聊天与 `/agent` 的发送资格、附件资格、记忆开关、模型等待态及答案级知识引用去重。
- `ConversationPage` 只接收 `ConversationUiState`、单一 `ConversationActions` 和可见状态，不再读取整份 `XiaoLingUiState` 或具体 ViewModel。页面自己持有 LazyList/跟尾状态，保留切换会话归尾、Tab 往返保留阅读位置、用户离尾后不被流式增量强拉、流式 Markdown 完成后二次校准和“新内容”恢复跟随语义；消息组合、附件预览、SharedDraft、Run 时间线与审批卡同步迁入模块。
- 应用壳只做状态投影和动作适配。图片/文档 `OpenDocument`、URI 交给 ViewModel、答案引用跳转知识库等 Android 与跨页面副作用仍留在 `XiaoLingContent`；发送、停止、审批、Provider/模型、会话和草稿动作继续复用原 ViewModel 实现，没有改变 Room、网络或 Agent Runtime 边界。
- 真实 Agent Skill 阶段基线经复核为 `3,497` 行；本轮 `XiaoLingApp.kt` 降到 `1,796` 行。`ConversationContract.kt / ConversationPage.kt / ConversationMessageContent.kt` 分别为 `224 / 1,235 / 643` 行。双轴 review 未发现明确行为回归；已补普通聊天无模型禁发、附件/加载忙态、知识引用去重，以及图片/文档、发送/停止、SharedDraft 动作路由覆盖。
- TDD 先以缺少 `ConversationProjection`、再以缺少 `ConversationPage` 的编译失败建立 Red。Projection JVM 为 `4/4`；review 修复后强制本地 `140/140` tasks、完整 JVM `677/677`、Lint `0 error / 50 warnings`、Debug/AndroidTest/Release APK 和 Release lintVital 均通过。Debug/Release APK 分别为 `23,337,963 / 16,016,342` 字节，SHA-256 分别为 `61b5cb5b14b43c8e01fe07a9ea4067e918d8c6f8e3d98baab25bc1cee2bce1f6 / f537287d9a6ec10f2e3d7e8675fef6bf9690dda00b8994961336b7afd8c6b9d9`。只使用 Redmi `wsvwypiz7xwslvl7`，会话页面聚焦 Compose 为 `OK (3 tests)`，默认完整 instrumentation 为 `OK (214 tests)`、耗时 `74.329s`；最终四份长期文档重新打包后的项目语料单项为 `OK (1 test)`。未连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- Room v32、普通聊天与 `/agent` 发送、附件读取、知识引用、Run/审批、旧会话、Workflow、设备工具、answerability Shadow 和第 101/102 项边界均未改变。下一轮从宿主剩余 `1,796` 行重新盘点仍具完整状态、动作与测试 seam 的垂直簇。

## 提示词设置垂直 UI module（横向结构工程）

- 新增 `ui/promptsettings` 垂直模块。`PromptSettingsPage` 只接收六字段 `PromptSettings`、九项 `PromptSettingsActions` 和返回回调，不再读取整份 `XiaoLingUiState` 或具体 ViewModel；三类编辑器、互斥最终预览和页面局部展开状态由模块自己持有。
- `XiaoLingViewModel` 直接实现 Actions，输入仍在每次变更时通过既有 `updatePromptSettings` 同步更新 UI state 并写入 `UiPreferenceStore`；三项恢复动作只恢复对应模板，不改变开关或其他提示词。最终预览继续调用 `PromptPolicy` 组合安全尾部，不把编辑框原文误当成最终 system prompt。
- 三个设置页共用的 `CompactSection` 已提升为共享 UI 原语；`XiaoLingApp.kt` 从 `1,796` 行降到 `1,582` 行，`PromptSettingsContract.kt / PromptSettingsPage.kt / CompactSection.kt` 分别为 `21 / 222 / 64` 行。固定点 `817f29f` 审查指出长期文档待同步以及三组显式映射存在参数重复判断项；本节完成文档同步，三类固定映射则为防止普通摘要与 Agent 摘要动作交叉绑定而保留。
- TDD 先以缺少 `PromptSettingsPage / PromptSettingsActions` 的编译失败建立 Red；只在 Redmi `wsvwypiz7xwslvl7` 运行页面动作路由与最终预览 Compose，结果为 `OK (2 tests)`。强制本地 `140/140` tasks、完整 JVM `677/677`、Lint `0 error / 50 warnings`、Debug/AndroidTest/Release APK 和 Release lintVital 均通过。Debug/Release APK 分别为 `23,354,347 / 16,016,342` 字节，SHA-256 分别为 `194f25d3173f50d20fe8cbc3c11be1a73cdbd7738638218d3b3fc1758b9704cc / 78470c153f4a2477dec0dfb9c8377b9c55abd233435554f6b3ca54260ace4d66`。
- Redmi 默认完整 instrumentation 的 JUnit XML 为 `216` 条（`204 passed / 12 skipped / 0 failed`）、耗时 `79.503s`；Gradle 控制台结束时按 skipped 的另一统计口径显示 `Finished 228 tests`，完整任务耗时 `2m 14s`。四份长期文档重新打包后的项目语料单项为 `OK (1 test)`；未连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- Room v32、提示词持久化、普通聊天/摘要/Agent 总结策略、Provider、Agent Runtime、Workflow、设备工具、answerability Shadow 和第 101/102 项边界均未改变。下一轮从宿主剩余 `1,582` 行重新盘点完整垂直簇。

## Agent 启动前校验协调迁出（横向可靠性工程）

- 新增纯同步 `AgentLaunchPreflightCoordinator` 与强类型 Profile 来源、会话要求、`Ready / Rejected` 结果。需要原上下文的入口先校验会话，再依次校验 Profile 可运行性、未知工具和 Provider 请求配置；普通 `/agent` 使用可选会话，保留 `sendAgentRun()` 在空占位上创建会话的既有行为。
- 普通 `/agent`、Workflow 首次运行、Workflow Run 重试和 Agent Run 关联重试继续使用当前选中 Profile。恢复后审批优先传入原 Run 的 `AgentProfileSnapshot`；旧 Run 没有可解析快照时才回退当前 Profile。Provider 仍从当前保存配置解析，长 Workflow 继续复用入口冻结的运行配置，不增加执行前二次校验。
- `XiaoLingViewModel` 只把当前 UI 快照投影为 preflight request，并把拒绝消息投影到既有“配置不完整”结果。校验成功后的会话导航、确认弹层生命周期、附件读取、发送态、Room 写入、Runtime 调用和 Workflow 后续步骤仍留在原宿主边界。
- `ProviderRequestConfig` 含解密后的 API Key，只允许在当前进程启动链中传递。类型级 `toString()` 已固定将 Base URL、API Key 与全部自定义 Header 表示为 `<redacted>`，防止 URL userinfo/query、异常上下文或调试格式化隐式泄漏；该保护不改变请求字段、`copy()` 或相等性。
- `AgentLaunchPreflightCoordinatorTest` 聚焦 `10/10`，覆盖错误优先级、Profile/Provider/工具分型、普通空会话、历史快照、旧 Run 缺少快照时回退当前 Profile 和冻结请求配置；`ProviderRequestConfigTest` 以 red-green 证明直接字符串格式化不会展开凭据。完整 JVM 为 `656/656`、0 失败/错误/跳过。
- 强制完整门禁 `140/140` tasks 在 `2m 5s` 内完成，Lint 为 `0 error / 50 warnings / 0 information`，Debug、Release 与 AndroidTest APK 构建成功。Debug APK `23,190,389` 字节、SHA-256 `1633449fdfe317340da8b72e29e698262fde4cae381c8ccfb5706c4db34ffb52`；Release APK `15,950,806` 字节、SHA-256 `00a0170be4fe2ac8e794340f63319f5429df6c3aa9eacc9dbea6fc21ee832e46`。
- 仅在 Redmi `wsvwypiz7xwslvl7` 覆盖安装 Debug/Test APK 并运行默认完整 `AndroidJUnitRunner`，最终 `196` 条为 `184 passed / 12 skipped / 0 failed`、耗时 `48.8s`。测试用充电保持唤醒和屏保设置已恢复为 `15/1`，未连接或操作 Pixel_9。
- README 与 7 份 `docs/` 长期文档重新打入 AndroidTest assets 后，Redmi 项目文档语料单项最终为 `OK (1 test)`。
- Room 保持 v32；本次不修改 Agent Runtime、工具审批/验证、Workflow Ledger、设备后台门禁或 answerability Shadow，不采集 Shadow 样本，也不进入第 102 项、精确定时或 Foreground Service。

## Provider 模型同步协调迁出（横向可靠性工程）

- 新增 `ProviderModelSyncCoordinator` 与 `Invalid / Failed / Missing / Stale / Succeeded` 强类型结果。单项同步先校验 Base URL，再以 trim 后的 URL/API Key、空模型和当前 User-Agent 请求 `/models`；模型按上游顺序去重，优先保留仍有效的当前模型，否则回退第一项或空值，并延续现有行为把可用/启用列表更新为上游全集。
- `syncAll()` 严格按输入顺序逐项执行并即时发布 outcome。普通网络、协议或持久化失败已收敛为结果，因此继续下一项；`CancellationException` 不包装为失败，直接终止整批。多个单项请求仍可并行获取网络结果，只有 `commitProfile` 通过协调器内 `Mutex` 串行，避免完整 Provider 快照互相覆盖。
- ViewModel 提交端在落库前等待更早的 latest-save，重新从最新 `uiState.profiles` 查找 Provider，并按规范化 `/models` URL 与 trim 后 API Key 核对身份。名称使用最新值，当前模型仍在新全集时继续保留；Provider 删除返回 `Missing`，身份或保存期间快照漂移返回 `Stale` 并重新排队用户最新快照。只有 `ProviderRepository.save()` 完成后才投影成功并修复空模型 Agent Profile。
- `XiaoLingViewModel` 的单项/批量入口只管理 busy、逐项结果和弹窗，不再直接构造请求、合并模型或保存完整 Provider 列表。批量与单项互相排斥，取消通过 `finally` 清理忙碌态；`ApiFailure` 保留稳定失败标题与原始消息。
- `ProviderModelSyncCoordinatorTest` 聚焦 `8/8`，覆盖成功规范化与去重、无效 URL 网络前拒绝、Provider 失败、取消传播、身份漂移、删除、批量顺序/失败继续和并发提交串行。完整 JVM `645/645`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK 成功；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.373s`，最终文档语料 `OK (1 test)`。
- Room 保持 v32；本次不修改 Provider 协议、Agent Runtime、Workflow、设备后台门禁或 answerability Shadow，不采集 Shadow 样本，也不进入第 102 项、精确定时或 Foreground Service。

## 候选记忆协调迁出（横向可靠性工程）

- 新增 `AgentMemoryCandidateCoordinator` 和强类型 load/capture/decision outcome。有界列表固定走注入的 limit；普通聊天与 Agent Run 成功回合统一生成包含 `conversationId`、可空 `runId` 和稳定摘要的 `AgentMemorySource`，Store 返回 `null` 与存储异常分别映射为 `Ignored` 和 `Failed`。
- 接受与拒绝共用按候选 ID 隔离的 claim。同一 ID 的第二个决定立即返回 `Busy`，不同 ID 不互相阻塞；Room 异常和 `CancellationException` 都会在 `NonCancellable` 中释放 claim，取消仍原样传播。协调器只路由现有 `list/create/accept/reject`，敏感过滤、规范化、去重、冲突和 transaction 继续由 `RoomAgentMemoryStore`/Manager 负责。
- `XiaoLingViewModel` 只投影加载、错误、结果和刷新事件，不再直接编排候选 Store 操作。关闭候选开关会取消旧 `memoryCandidateLoadJob`、清空候选并结束 loading，避免迟到 Room 读取把已经关闭的页面重新填充；四条普通聊天/Agent 成功路径仍只在完整成功后采集候选，失败/取消回合不采集。
- `AgentMemoryCandidateCoordinatorTest` 聚焦 `7/7`，覆盖有界读取、两类来源身份、无候选/存储失败分型、接受/拒绝路由、Missing、同 ID Busy、不同 ID 并行及两类取消后重试。强制本地 `140/140` tasks 在 `2m 23s` 内通过，完整 JVM `637/637`、Lint `0 error / 50 warnings / 1 hint`，Debug/Release/AndroidTest APK 成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 执行默认完整 `AndroidJUnitRunner`，结果 `OK (196 tests)`、耗时 `49.633s`；最终文档语料单项为 `OK (1 test)`。Debug APK 为 `23,174,005` 字节、SHA-256 `4992185a39ae9844b171e51126dfbef2d97d2ce06d55edcf123bd85d5cb2007c`；Release APK 为 `15,934,422` 字节、SHA-256 `0cb3df07f601fe8cde4acb74346fd7c18eb47ffab55276e3cf4fab552fde5aab`。
- Room 保持 v32；本次不修改候选治理、Agent Runtime、Workflow、设备后台门禁或 answerability Shadow，不采集 Shadow 样本，也不进入第 102 项、精确定时或 Foreground Service。

## 恢复后 Agent 审批协调迁出（横向可靠性工程）

- 新增 `RecoveredAgentApprovalCoordinator` 与强类型 `RecoveredAgentApprovalOutcome`。`approve()` / `reject()` 每次都调用注入的 `loadRunDetail()` 读取最新 Room 明细，并使用 `AgentRunResumePolicy` 核对唯一链尾审批；进程内 `Mutex.tryLock()` 只允许一个恢复决定进入持久化或 Runtime。锁忙单独返回 `Busy`，ViewModel 恢复当前会话 `deciding=false` 卡片，不会误清仍合法的另一项审批。
- 批准先加载原 USER 消息附件，再把最新 detail、Approval 和附件交给 `AgentRunUseCase.resumeApprovedRun()`。附件或 Runtime 前置校验失败后，协调器重新读取 Room；证据仍为合法 `PENDING` 时返回 `StillPending`，ViewModel 恢复 `deciding=false` 卡片。用户停止发生在决定落库前时，ViewModel 也从 Room 恢复同一卡片；决定已消费或 Run 已终态时只收敛 Workflow，不重新开放审批。
- `RoomAgentRunRepository.rejectRecoveredApproval()` 在单个 `withTransaction` 中再次运行恢复策略，依次写 `DENIED`、活动审批 Step=`FAILED` 和 Run=`FAILED`；任何异常回滚全部写入。协调器只接受 Repository 返回的完整终态，`null` 固定映射为 stale，不再组合两个独立事务。
- `XiaoLingViewModel` 继续负责原会话/Profile/Provider 校验、Compose 投影、消息与记忆候选保存、Workflow 当前步骤结算和后续步骤前台执行；普通当前进程 waiter 继续由 `AgentApprovalDecisionCoordinator` 管理。两条审批边界共享 Room 事实，但不共享内存 ticket 或恢复职责。
- TDD 新增 `RecoveredAgentApprovalCoordinatorTest` `6/6`，覆盖合法恢复、证据漂移、附件失败、stale 拒绝、原子拒绝结果和并发时 `Busy` 保留可重试决定；`RoomAgentRunRepositoryInstrumentedTest` 增加 Approval→Step→Run 同事务顺序契约。强制本地 `140/140` tasks 通过，完整 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 成功；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。Debug APK 为 `23,157,621` 字节、SHA-256 `4579b5bc821bd721b77a76b3110b0451f852b9c8f84f528fa824efc8cc801e4f`；Release APK 为 `15,918,038` 字节、SHA-256 `8fb7d53170a7bff05218b0d4cced8a47dc550bb29bac7dea8278ec0b7e44c6ef`。
- Room 保持 v32；本次不修改 Agent Runtime 的工具执行、预算或验证语义，不采集 Shadow 样本，也不进入设备 Workflow/后台、精确定时、Foreground Service、第 102 项或远期能力。

## Agent 审批决策协调迁出（横向可靠性工程）

- 新增纯内存 `AgentApprovalDecisionCoordinator`、`AgentApprovalDecisionTicket` 和 `AgentApprovalDecisionClaim`。ticket 封装单个 `CompletableDeferred<ApprovalDecision>`；协调器只允许当前 `requestId` 领取一次 claim，并以对象身份验证完成、释放、取消和清理。
- 注册新审批会取消旧 waiter。Room 决策写入成功后，ViewModel 才调用 `complete()`；写入异常调用 `release()` 并把同一审批恢复为 `deciding=false`，用户可以重试。Repository 返回 `null` 时调用按 claim 校验的 `cancel()`，避免 Run 已结束或审批已处理后仍执行工具。
- `stopGenerating()` 改为取消协调器当前 ticket，再取消发送 Job；取消会让已领取 claim 失效。`awaitAgentApproval()` 的 `finally` 只清理自己的 ticket，Workflow retry、Workflow run 和 Agent Run 外层 `finally` 不再无身份地清空全局 waiter 或审批投影，因此旧 Run 收尾不能影响新审批。
- `XiaoLingViewModel` 不再直接持有 `pendingApprovalDecision`。Room 写入、Compose 投影、用户错误提示、恢复后审批、Run/Workflow 和真正 Runtime 仍留在原职责层；Room v32 与数据库 Schema 不变。由于新增明确的异常恢复和 `null` fail-closed 分支，ViewModel 当前为 `4416` 行；本次目标是生命周期单一归属，不以行数下降冒充完成度。
- `AgentApprovalDecisionCoordinatorTest` 五轮 red-green 为 `5/5`，覆盖一次性领取、失败释放后重试、新 ticket 取消旧 waiter、停止取消、过期 claim 隔离与无持久化决定时取消。与 `AgentConversationRuntimeStateStoreTest`、其他 Agent Coordinator 聚焦组合通过。
- 强制完整本地门禁为 JVM `624/624`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 1 hint`；Debug、Release、AndroidTest APK 全部成功。Debug APK 为 `23,157,621` 字节、SHA-256 `da159b14f94b810d7972e644110e553d87ee6b0eb5c013796c949915e69c3de8`，Release APK 为 `15,918,038` 字节、SHA-256 `df72abccf778d99c25ac5ef84f876849bb9ebf9571cef6806d6ae8872c162504`。
- Standards/Spec 双轴审查发现“已取消 ticket 仍可再次领取”和关键身份 guard 注释不足；现已让 `claim()` 拒绝已完成/取消 ticket、抽出当前 claim 身份判断并补齐贴近实现的 `long:` 中文业务注释，聚焦与完整门禁均在修复后重跑。
- 仅在 Redmi `wsvwypiz7xwslvl7` 执行默认完整 `AndroidJUnitRunner`，结果 `OK (195 tests)`、耗时 `48.776s`；7 份长期文档重新打包后的项目文档语料为 `OK (1 test)`。本轮不触发 Shadow，不改变第 101/102 项、设备后台门禁或远期路线。

## 会话级 Agent 运行态 Store 迁出（横向可靠性工程）

- 新增纯内存 `AgentConversationRuntimeStateStore` 与不可变 `AgentConversationRuntimeState` 投影，以 `conversationId` 为唯一归属保存最新 `AgentRunSnapshot` 和 `AgentApprovalUiState`。后台 Run 或审批更新不会覆盖用户正在查看的其他会话。
- Store 统一五类生命周期：同会话 Run 替换、审批进入 `deciding`、审批收敛但保留 Run、删除会话同时清理 Run/Approval，以及新建占位会话在返回空投影前清理可能复用的同 ID 旧状态但保留其他会话。启动恢复继续从 Room Run/Approval 明细重建 Store，再只投影当前选中会话。
- `XiaoLingViewModel` 删除两张裸 Map，并在会话选择、删除、Run snapshot 发布、审批记忆/清理和启动恢复处消费 Store；`CompletableDeferred`、审批 Repository 写入、Run history、Agent Runtime、Compose 状态和 Workflow 编排仍留在原边界。ViewModel 从 `4408` 行降至 `4404` 行。
- 五轮 TDD 的 `AgentConversationRuntimeStateStoreTest` 为 `5/5`；会话选择、选择策略和恢复消息组合聚焦测试通过。完整 JVM `619/619`，Lint `0 error / 50 warnings`，Debug、Release 与 AndroidTest APK 构建成功。
- 更新后的 7 份长期文档重新打包后，仅在 Redmi 执行项目文档语料门禁，结果 `OK (1 test)`。
- 仅在 Redmi `wsvwypiz7xwslvl7` 安装并执行默认完整 `AndroidJUnitRunner`，结果 `OK (195 tests)`、耗时 `50.018s`。收尾确认 Room v32、Provider/Profile 各 `1`、知识文档 `0`、默认 Profile 为 `16` 个工具/`7` 个 Skill/记忆开启、Shadow 关闭；测试包已卸载，主 Activity 前台且 crash buffer 无本应用异常。
- 本次不修改 Room Schema、Provider 协议、Agent Runtime、审批/验证语义、Workflow、设备后台门禁、Shadow Store 或 enforcement，也不采集或制造新 Shadow 样本。第 101 项继续低频观察，第 102 项保持后置。

## 第 101 项：answerability Shadow 首个持续观察窗口（验收完成，继续低频观察）

- 本窗口不修改生产代码、Room Schema、Provider 协议、Shadow Store 或 enforcement；只在 Redmi `wsvwypiz7xwslvl7` 的同一进程中由用户显式开启，并采集一条前台直接 `/agent` 真实样本。
- 当前 README 作为临时知识导入后形成 revision `1`、`8` 个 chunks、`17.6 KB`；查询 `Agent Run retryOfRunId` 返回 `3` 个候选，并明确显示 `Embedding：Provider 不可用，词法兜底`。真实 Run 完成 `knowledge.search`，答案显示“本地知识包含直接回答”和 `知识引用 · 3`。
- 本进程 tracker 为样本 `1`、完成 `1`、未知 `0`、跳过 `0`；Judge 尝试 `1`、取消 `0`、异常 `0`，答案保存失败、Shadow Store 失败、绑定未知及其他旁路错误均为 `0`。成本为耗时 `5009ms`、TTFB `5002ms`、Prompt `10150B`、输入/输出/总 Tokens `2720/209/2929`、usage attempts `1`。
- notice 发布 `1`。关闭 Shadow 并删除测试会话后，有效 notice `1 -> 0`、裁剪 `0 -> 1`，累计 tracker 与成本不回退；临时知识文档和 Redmi 下载文件均已删除，知识文档恢复为 `0`，偏好恢复 `answerability_shadow_enabled=false`。
- 第 97 至 101 项已记录窗口的书面人工合计为样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`；成本 `43846ms / 43777ms / 66995B / 17164+1822=18986 Tokens`。该合计不是跨进程 tracker 或 Room 数据。
- 八次 Judge 仍没有自然网络、协议或认证失败，也没有明显成本异常。当时继续保持 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；第 101 项不标记为永久完成，第 102 项在该窗口仍后置。
- 强制完整本地门禁为 JVM `614/614`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug 与 AndroidTest APK 构建成功。Debug APK 为 `23,141,237` 字节，SHA-256 `dc61bbec47e688ea19dea572e9dca5b5d04a4c7ed8a7f0c1efa4b328769f22ca`。Redmi 文档语料为 `OK (1 test)`；默认完整 `AndroidJUnitRunner` 为 `OK (195 tests)`、耗时 `49.158s`。

## 第 102 阶段：answerability 离线评测导出契约（完成）

- 冻结版本化 Kotlin `KnowledgeAnswerabilityExportEnvelope` sealed 契约：匿名 Shadow 与显式授权内容分别使用独立 envelope，不能通过同一对象混装 rows/cases。
- 匿名 Shadow envelope 不提供原始 Judge 或 dataset identity，只允许 v33 不可逆 fingerprint、状态/绑定/决策/失败枚举、失败分桶和保持 `null` 的未知成本 telemetry，`eligibleForCalibrationOrValidation()` 固定返回 false；显式内容 envelope 才携带完整 dataset identity，并要求正文、引用、label、assessment 与 case identity 一致及明确 `EXPLICIT_OFFLINE_EVALUATION` 授权。
- 本阶段未增加 production enforcement，也未接入 Workflow/后台、检索排序、答案路径、JSON/SAF 出口；下一步继续用 Room v33 新匿名账本积累间隔真实样本，样本足够后再评估导出出口。
- 修正后完整门禁为 JVM `736/736`、Lint `0 error / 51 warnings`、Debug/AndroidTest/Release APK 构建成功（Debug `23,685,840` bytes，SHA-256 `f0dc66a6300553511771aeb395fbd07d0b57e97f709c1cea566b78130bb89e2f`；AndroidTest `2,698,577` bytes，SHA-256 `79c9087384471b1dfaa217b171a488ed1aeb34427f72d8b904184d5db885168d`；Release `3,220,018` bytes，SHA-256 `bfcf4deee50861e51ac044e60c5b96590ea62f06b71494e651c9a83dd8d87f79`）。仅 Redmi 完整 instrumentation XML 为 `248` 条（`236 passed / 12 skipped / 0 failed`），runner 打印 `260 tests`；文档 corpus gate `1/1` 通过。首轮套件曾因设备 Activity 停在 `STOPPED` 造成 Compose 级联失败，唤醒/停止应用后重跑通过，业务代码未因该偶发状态改动。

## 第 103 阶段：Room v33 首个间隔真实 Shadow 样本（完成）

- 采样前只构建当前源码 Debug 与 AndroidTest APK，分别耗时 `11s / 7s`；两个 APK 使用既有正式证书在 `/tmp` 重新签名，以 `adb install -r` 无损覆盖 Redmi 上的正式签名应用并保留 Provider、Profile 与 Room 数据。没有执行完整 JVM、Lint、默认完整 instrumentation 或 Release 构建。
- Room 从 v32 成功迁移到 v33，初始 `knowledge_answerability_shadow_observations` 为 `0`。现有 Provider 为空占位后，只运行 `ProviderEmbeddingCompatibilityInstrumentedTest`，结果 `OK (1 test)`；它把 AGENTS 兜底配置加密保存为 `redmi-provider-compatibility`，Embedding 因该 Provider 没有 Embedding 模型按假设跳过。随后通过真实 Agent Profile UI 保存默认 Agent，绑定 `gpt-5.5`，保留既有 `16` 个工具和 `7` 个 Skills。
- 本窗口距第 101 阶段记录时间约 `69` 小时，且 v33 采样前匿名账本为 `0`。当前 README 以 `xiaoling-stage103-shadow.md` 导入后形成 revision `1`、`19` 个 chunks；Embedding 不可用，检索 `Agent Run retryOfRunId` 通过词法兜底命中 `5` 个 chunks。显式开启 Shadow 后发送 `/agent Call knowledge.search with query Agent Run retryOfRunId and explain the result.`，前台 Run 完成并保留本地知识引用。
- 停进程 Room 快照确认 Schema `33`，匿名账本恰好 `1` 条：`COMPLETED / BOUND / ACCEPT`，Judge identity 桶 `1`，attempt `1`，耗时/TTFB `9663/9655ms`，Prompt `10879B`，输入/输出/总 Tokens `2801/469/3270`，usage samples `1`；unknown、reject、undecided、binding unknown 与十类失败计数均为 `0`，记录时间为 `2026-07-29 07:27:36`（北京时间）。
- 通过应用内正式入口删除临时知识文档和 Agent 会话，知识文档/chunks 回到 `0`；精确删除 Redmi 下载文件并卸载 `com.longdev.xiaoling.test`。`answerability_shadow_enabled=false`，production enforcement 偏好不存在，Provider/Profile 均仍绑定 `gpt-5.5`。同步后的文档 corpus 单项在 Redmi 为 `OK (1 test)`、耗时 `1.988s`；再次停进程复核后，第 103 阶段当时账本仍为 `1`、知识数据仍为 `0`。当前源码 Debug 保留在设备上，避免以 Room v32 的发布 APK 降级覆盖 v33 数据库；最终冷启动 `3441ms`，`MainActivity` resumed，crash buffer 为空。
- 本阶段完成的是第一条跨进程真实样本，不把第 97 至 101 项人工统计回填或合并。第 104 阶段随后增加第二条短间隔记录，第 107 阶段又增加第三条独立同日记录；继续等待真正跨日或长期分隔的后续窗口，样本量不足前不实现 JSON codec、UI/SAF 出口或 production enforcement。

## Agent Run 关联重试协调迁出（横向可靠性工程）

- 新增 `AgentRunRetryCoordinator`、`AgentRunRetryEvent` 与 `AgentRunRetryLaunchRequest`。`request()` 统一忙碌、来源缺失、不可重试和副作用确认分支；`confirm()` 重新读取当前详情，并以 `AgentTaskRetryPolicy.canConfirmRetry()` 同时核对证据码和 canonical fingerprint。
- `ConfirmationRefreshed` 明确承接“分类相同但证据内容漂移”的二次确认；`Failed` 与 `Cancelled` 让拒绝、附件读取失败和用户取消都通过稳定事件返回，不再由 ViewModel 多处分支直接早退。
- `prepare()` 先发布 `RetryStarting`，再异步调用注入的附件读取函数；成功后只发布带 `/agent <原目标>`、原会话、原 USER 附件和 `retryOfRunId` 的 `RetryReady`。协调器没有 Agent Runtime、Room 写入或 Compose 状态权限，因此不能修改或续跑旧 Run。
- `XiaoLingViewModel` 注入 `ConversationRepository` 的 USER 消息附件读取，继续校验原会话与选中 Agent Profile/Provider、执行会话导航并消费 typed event；只有 `RetryReady` 才调用既有 `sendAgentRun()`。原有用户提示、确认刷新、任务中心选中与导航行为保持不变。
- `AgentRunRetryCoordinatorTest` 新增 `7/7`：直接准备、写工具确认、同码指纹漂移、附件恢复与旧 Run 不变、附件读取失败、三类请求拒绝、确认失败与取消。既有 `AgentTaskRetryPolicyTest` 组合复验通过。
- 强制完整本地门禁为 JVM `614/614`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug 与 AndroidTest APK 构建成功。最终 Debug APK 为 `23,141,237` 字节，SHA-256 `dc61bbec47e688ea19dea572e9dca5b5d04a4c7ed8a7f0c1efa4b328769f22ca`。仅在 Redmi `wsvwypiz7xwslvl7` 执行默认完整 `AndroidJUnitRunner`，结果 `OK (195 tests)`、耗时 `48.619s`。
- 本次是 ViewModel 横向瘦身，不新增阶段性产品能力，不修改 Room v32、Provider 协议、Agent Runtime、工具审批/验证语义、Workflow、设备工具、Shadow Store 或 enforcement。第 101 项仍只允许间隔真实使用窗口中的低频 Shadow 观察。

## 第 100 阶段：Android 系统分享入口 v1（实现与聚焦 Redmi 验收完成）

- `AndroidManifest.xml` 把 `MainActivity` 设为 `singleTop`，并以独立 `ACTION_SEND` filter 精确声明 `text/plain`、PNG、JPEG/JPG 和 WEBP；不声明多项分享、通配图片或文档 MIME。
- `SharedDraftParser` 把外部输入转换为 `SharedDraftImport.Accepted / Rejected / Ignored`。多项 `ClipData` 在进入 MIME 分支前统一拒绝，避免 `ACTION_SEND + text/plain` 绕过单项边界；文本统一换行、trim 且限制 20,000 字符，图片限制单项小写 `content://`。解析层只产生 `SharedDraftPayload`，不暴露任何发送动作。
- `AndroidShareIntentReader` 联合读取 action、MIME、文本、`EXTRA_STREAM` 与 `ClipData`。两处重复同一 URI 时按单图兼容，两处 URI 不同时把条目数投影为多图并由解析器拒绝；外部 referrer 与 extra 均不可信，因此不解析具体来源应用，也不读取外部“已处理”标记，所有来源提示统一为外部分享。
- `MainActivity` 首次创建时处理冷启动 Intent，`onNewIntent` 处理热启动；系统重建带 `savedInstanceState` 时不重复导入。`XiaoLingViewModel.acceptSharedDraft()` 在初始化完成前排队，避免 Room/Keystore 初始化状态覆盖分享草稿。
- `SharedDraftProjectionPolicy` 区分立即打开、确认替换和保留首个未决分享。编辑器有文本、附件或活动操作时必须由用户点击“打开分享”；已有未决分享时明确忽略新分享并要求来源应用重试，不建立无界队列。
- `openSharedDraft()` 先打开新会话，再写入文本并调用现有 `attachImage()`；图片继续经过 `ImageAttachmentReader` 的 8 MB、MIME、签名与解码校验。`sharedDraftImported` 只控制导入提示，用户编辑、移除图片、读取失败、切换会话或发送后立即清理。
- Compose 在输入区展示“来自外部应用的分享”冲突条和“已从外部分享导入”提示；提示不声称已验证具体来源，也不提供自动发送入口。
- 聚焦 JVM：`SharedDraftParserTest` 4 条、`SharedDraftProjectionPolicyTest` 3 条，共 `7/7`。Redmi 定向 instrumentation：Manifest 1 条、Activity 冷/热文本与 PNG 2 条、Compose 冲突提示 1 条，共 `OK (4 tests)`。Manifest 用例额外确认 `ACTION_SEND_MULTIPLE` 不可解析；Activity 用例覆盖文本多项 `ClipData`、双来源图片 URI 冲突、伪造内部 extra、重建防重复、图片读取失败、编辑/移除/切换会话后的导入提示清理和不产生 USER 消息。完整门禁为 JVM `607/607`、Lint `0 error`（`50 warnings / 1 hint`）、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `195` 条（`183 passed / 12 skipped`）。
- 本阶段不修改 Room Schema、Provider 协议、Agent Runtime、工具权限、Workflow、后台执行、精确定时、Foreground Service、MCP、多 Agent 或本地模型；第 99 阶段 Shadow 继续作为低频只读旁路。

## 第 99 阶段：answerability shadow 首批低频观察（验收完成，生产实现不变）

- 本阶段没有修改生产代码、Room schema、Shadow Store 或 enforcement，只在 Redmi 同一进程内显式开启后采集三个 `DIRECT_FOREGROUND` 真实样本；当前仍为 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false`、`productionEnforcementEnabled=false`。
- 首次宽英文问题让模型连续四次用过宽查询调用 `knowledge.search`，均无候选并以工具调用次数超过上限结束；因为没有成功答案和合格 Shadow 候选，tracker 仍为 `0`。该 Run 只证明入口隔离，不是 Judge 失败、取消或 `SKIPPED / NO_CANDIDATE` Shadow 样本。
- 随后使用已经由知识库检索预览确认命中的精确词法查询采样。三个完成样本中，Responses 文档格式/限制和普通聊天工具事实边界判为直接回答，一次性 Workflow 范围/准点语义加未提供的重试次数判为部分回答；notice 分布为直接回答 `2`、部分回答 `1`。
- 本批 tracker 为样本 `3`、完成 `3`、未知 `0`、跳过 `0`，Judge 尝试 `3`、取消 `0`、异常 `0`；答案保存失败、Shadow Store 失败、绑定未知均为 `0`。累计成本为耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、输入/输出/总 Tokens `4474/638/5112`、usage attempts `3`。
- 当前窗口的 Embedding Provider 不可用，知识检索明确降级为词法兜底；这不影响本批 answerability Judge 对冻结候选的旁路观察，但不能把本批当成 Embedding 质量证据。
- 关闭开关并删除四个测试会话后，notice 从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3`；临时知识文档及 Redmi 下载目录中的阶段文件已删除，恢复知识文档 `0`、原会话 `ping` `1`。累计 tracker 和成本未随会话删除回退。
- 第 97 至 99 阶段书面记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次、直接回答 `4`、部分回答 `3`，成本 `38837ms / 38775ms / 56845B / 14444+1613=16057 Tokens`。该合计由阶段证据相加，不是跨进程持久化；七次 Judge 均成功，仍没有自然网络、协议或认证失败。
- 完整本地门禁为 JVM `600/600`、Lint `0 error`、Debug APK 和 AndroidTest APK 构建成功；仅在 Redmi 执行更新后文档语料 `OK (1 test)` 和默认完整 instrumentation `OK (191 tests)`。Lint 报告保留 `49` 条 warning 与 `1` 条 hint，没有 error。

## 第 98 阶段：answerability shadow Redmi 扩样本（验收完成，生产实现不变）

- 本阶段没有修改生产代码、Room schema 或 Shadow Store，只在 Redmi 同一进程内由用户显式开启后扩充 `DIRECT_FOREGROUND` 真实样本；`store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false` 保持不变。
- 累计 tracker 为样本 `6`、完成 `4`、无候选跳过 `2`；Judge `4` 次、取消 `0`、异常 `0`，完成判定为直接回答 `2`、部分回答 `2`。三条新增有效 Judge 样本为部分回答 `2`、直接回答 `1`；两条过长词法 query 自然无候选，只进入跳过明细。
- 累计成本为耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、输入/输出/总 Tokens `9970/975/10945`、usage attempts `4`。另一次 Agent Run 自然进入 `BUDGET_EXHAUSTED`，因没有形成可用 Shadow 入口而不计入样本、attempt、失败或取消。
- notice 累计发布 `4`。关闭开关并删除测试会话后，从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`；测试 README 知识文档已删除，恢复为知识文档 `0`、保留原会话 `1`。累计样本和成本不会随会话删除回退。
- 当前四次 Judge 均成功，没有出现网络、协议、认证等自然 Judge 失败；无候选与预算耗尽不能作为持久化或 enforcement 的扩权依据。
- 完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK 构建通过；更新后的 5 份核心长期文档在 Redmi 语料门禁中为 `OK (1 test)`，默认完整 instrumentation 为 `OK (191 tests)`。仅使用 Redmi，没有连接或操作 Pixel_9。

## 第 97 阶段：answerability shadow 真实样本与进程内遥测（实现与 Redmi 验收完成）

- 新增 `KnowledgeAnswerabilityShadowSampleTracker` 与数值遥测模型，使用同步提交和饱和计数维持固定上限；只保留终态、attempt、延迟/TTFB、Prompt 字节、Tokens、usage attempt、失败枚举和 notice 数量，不持有问题、答案、候选正文、引用、原始响应、消息 ID 或凭据。
- `OpenAiKnowledgeAnswerabilityJudge` 为每次真实 Provider attempt 生成数值成本；HTTP 成功但协议失败仍保留 usage/延迟，网络失败保留已知等待时长和 Prompt 规模。协调器会累计“首轮失败、重试成功”的完整失败分布，并继续只重试既有可恢复类别；`MODEL` 与 `IDENTITY` 已拆分。
- `AgentAnswerabilityShadowPublisher` 区分答案保存失败、用户关闭/取消、候选缺失、来源不支持、协调器异常和真实 UNKNOWN。答案保存后、Judge 发出前再次读取用户开关；关闭后不再发送问题或候选正文。普通聊天、Workflow 和后台 Worker 仍不进入 caller。
- notice 发布、会话删除、会话重载及迟到 publish 的悬空裁剪都会校准 tracker；设置页提供可滚动的本进程摘要，明确重启清空与不写 Room。生产继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false`。
- 完整 JVM XML 为 `600/600`，Lint `0 issue`，Debug/AndroidTest APK 构建成功；仅在 Redmi `wsvwypiz7xwslvl7` 完成设置页 `OK (1 test)` 和默认完整 `OK (191 tests)`。真实 `/agent + knowledge.search` 形成 `1` 条完成样本，Judge 成本为 `8437ms / TTFB 8428ms / Prompt 8952B / Tokens 2340+361=2701`，失败、取消、异常均为 `0`；notice 在答案引用区可见并在删除会话后累计裁剪 `1`。开启状态下普通聊天未增加样本，验收后开关恢复关闭。

## 第 96 阶段：answerability shadow 默认关闭的生产接线（实现完成）

- 新增 `KnowledgeAnswerabilityProductionShadowBinding` 和 identity factory。冻结身份绑定 Redmi 当前真实 Provider ID `redmi-provider-compatibility / gpt-5.5 / 03c4b0d...cf6d / stage92-answerability-json-v1`；factory 只读取 `ProviderRequestConfig.providerId / model / Base URL fingerprint`，调用方不能注入逻辑别名。只有用户开关开启且实际 identity 完全一致时才进入 `SHADOW`，配置漂移在网络请求前 fail-closed。
- 新增 `OpenAiKnowledgeAnswerabilityJudge`。请求固定为 Responses、非流式、关闭 reasoning summary、`temperature=0`、`topP=1`、`maxTokens=220`，实际 identity 从配置的 Provider ID、模型和 Base URL 指纹派生，adapter 不自行重试。Judge 的问题和候选正文逐请求关闭全部 HTTP Debug 日志；5xx 可通过带 `statusCode` 的 `ApiFailure` 映射为可重试 `SERVER`。
- 新增默认关闭的独立设置页与 `UiPreferenceStore` 偏好。开关只授予前台直接 Agent 的 shadow 请求资格，不改变普通聊天、Workflow、后台 Worker 或生产 enforcement。
- 新增 `AgentAnswerabilityShadowPublisher`。Agent 答案先发布并启动正常保存，publisher 的 sibling Job 等待对应保存 Job 成功后才调用协调器；保存失败、保存被新快照取消或 Judge 终败时跳过且不发布 notice，不进入 Agent 主失败分支。Workflow 直接跳过。
- notice 通过 `AnswerabilityNoticeProjection` 以进程内 `messageId` 映射注入 `KnowledgeReferencesContent`；原消息、`VerifiedAgentContext`、MessagePart 和引用均不改写，会话删除或重载时裁剪悬空键。当前 `store=null / persistenceMode=NONE`，没有 Room migration，进程重建后 notice 自然清空。
- 新增 JVM、MockWebServer、偏好、Compose 和真实 Provider adapter 探针；完整 JVM XML 为 `593/593`，Lint、Debug/AndroidTest APK、Redmi 默认完整 `OK (191 tests)`、真实生产 adapter `OK (1 test)` 和设置/偏好/notice `OK (3 tests)` 均通过。使用实际 Provider ID 重跑第 92 阶段探针仍为 `12 + 12` 条、失败 `0 + 0`、两类特征族通过；本轮不采用重复采集得到的 `0.86` 候选阈值，冻结 `minimumConfidence=0.85` 不变。

## 第 95 阶段：answerability shadow 真实测量协调（实现与 Redmi 验收完成，生产未接入）

- `KnowledgeAnswerabilityAssessment` 抽取 Judge 决策共享字段；带人工真值的 `KnowledgeAnswerabilityObservation` 继续服务离线 calibration/validation，新 `KnowledgeAnswerabilityShadowMeasurement` 只绑定真实 `sourceRunId`，不携带 `label`。两种类型复用同一个原文证据匹配与字段映射，避免线上/离线逻辑漂移。
- 新增 `KnowledgeAnswerabilityShadowObservation.kt`。`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一入口，默认关闭；只允许 `DIRECT_FOREGROUND`，其他来源直接跳过。调用 Judge 前复制引用快照，候选不完整或缺少冻结绑定时不发网络请求。
- Judge 首次请求失败后，只有瞬时网络、限流、服务端和协议分类允许再尝试一次，总上限固定为两次；认证、普通请求、身份、候选和未知异常不重试。`CancellationException` 原样传播，不生成伪 `UNKNOWN` 或持久化记录。
- Judge 成功输出先转换为无标签 measurement，再与响应中的实际 identity 绑定。身份漂移会保留 measurement 与时间，但 binding 为 `UNKNOWN`；原答案、引用和 `enforcementApplied=false` 保持不变。
- 可选 Store 只保存候选 SHA-256 指纹、幂等键、Judge 身份、尝试次数、测量/绑定状态、决策、失败分类和时间，不保存候选正文、原始响应或引用正文。Store 失败只返回 `persistenceStatus=FAILED`，不改变 binding 或用户答案。
- 新增 `KnowledgeAnswerabilityShadowObservationCoordinatorTest` `14/14`，覆盖关闭、非直接来源、成功、瞬时重试、认证不重试、协议耗尽、取消、身份漂移、畸形候选和最小化持久化。完整 JVM 更新为 `578/578`，Lint、Debug/AndroidTest APK 和仅 Redmi `OK (188 tests)` 通过。
- 该阶段当时尚未实现生产 Judge Provider adapter、消息保存后的 caller 和答案引用 UI 接线；这些默认关闭的接线已由第 96 阶段完成。Room schema/store、普通聊天、Workflow、后台 Worker 和 production enforcement 仍未接入。

## 第 94 阶段：真实消息流 answerability shadow 绑定（实现与 Redmi 验收完成，生产未接入）

- 新增 `KnowledgeAnswerabilityShadowBinding.kt`。`KnowledgeAnswerabilityShadowCandidate` 保存来源 Run、原问题、候选检索正文和稳定引用；`KnowledgeAnswerabilityFrozenBinding` 直接持有 calibration/validation 的 `KnowledgeAnswerabilityDatasetIdentity`，构造时要求同一 Judge identity 且版本互异，再绑定冻结 gate，避免消息流误用临时校准结果。
- `VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 只检查 `knowledge.search`、成功状态、非失败验证状态、非空正文和非空引用，并从多步执行中取最近一条有效执行；旧消息没有 `toolExecutions` 时回退到顶层单工具字段，空 Run 不生成候选。`notes.search`、失败执行、无引用结果和空正文不能成为 Judge 候选。
- `KnowledgeAnswerabilityShadowBindingPolicy.bind(...)` 只允许第 92 阶段已通过的 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`；覆盖率特征族直接 `UNKNOWN`。Judge identity 不一致、空 Run、缺少 identity/冻结绑定/measurement、measurement 的 `sourceRunId` 不等于来源 Run、候选证据不完整时全部 `UNKNOWN`，不会抛错或把不确定性转为拒绝。
- 绑定开始时复制候选与引用列表并保留原顺序，避免外部可变 List 让同一审计结果漂移；没有 measurement 时 `observedAt=null`。结果复用 `KnowledgeAnswerabilityShadowPresentationPolicy` 生成提示，固定 `enforcementApplied=false`。即使 measurement 本身为 `UNKNOWN`，也只形成可审计的 `BOUND + UNKNOWN` shadow 结果，不改变答案或引用。
- 第 94 阶段没有调用 Provider、写 Room、修改消息 schema、接入 `KnowledgeReferencesContent`、改变普通聊天/Workflow 或启用 `productionEnforcement`；第 95 阶段补齐生成、失败/重试和可选持久化协调，第 96 阶段再完成默认关闭的生产 adapter、caller 与 notice 接线。
- `KnowledgeAnswerabilityShadowBindingPolicyTest` `7/7` 与 `VerifiedAgentContextAnswerabilityCandidateTest` `4/4`，覆盖 malformed candidate、引用快照、数据集 Judge 漂移、旧消息兼容和空 Run；完整 JVM XML 为 `564/564`、0 失败，Lint、Debug APK 和 AndroidTest APK 构建通过。只在 Redmi `wsvwypiz7xwslvl7` 执行真实 `AndroidJUnitRunner`，结果 `OK (188 tests)`；没有连接或操作 Pixel_9。

## 第 93 阶段：答案可回答性 shadow 呈现（实现与 Redmi 验收完成，生产未接入）

- 新增 `KnowledgeAnswerabilityShadowPresentation.kt`，只把第 92 阶段的 `KnowledgeAnswerabilityObservation + KnowledgeAnswerabilityGate` 翻译为用户可理解的观察提示。提示区分直接回答、部分回答、未回答、证据矛盾、证据无法回查、低于冻结门禁和未知；无观测或无门禁时同样保持未知。
- `KnowledgeAnswerabilityShadowPresentedResult` 对输入引用执行副本保留，并固定 `enforcementApplied=false`。该层没有删除引用、改写答案、写 Room、读取灰度控制面或授予生产执行资格，避免把离线 Judge 结果误当成已经上线的答案决策。
- `KnowledgeReferencesContent` 新增默认 `null` 的 `answerabilityNotice`，并抽取与既有相关性提示共用的展示组件。有提示但零引用时只显示解释，不显示“知识引用 · 0”；有引用时提示与折叠引用共存。第 93 阶段当时生产调用没有传入该参数；第 96 阶段仅由默认关闭的前台直接 Agent 旁路传入，普通聊天与后台链路仍不变。
- 新增 `KnowledgeAnswerabilityShadowPresentationPolicyTest` `5/5`，与既有 `KnowledgeAnswerabilityPolicyTest` `7/7` 通过独立 JUnit 合计 `12/12`；主代码、UnitTest 和 AndroidTest Kotlin 均成功编译。
- `KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已随 Redmi 默认完整套件通过，确认提示不会删除、替换或重排原引用；完整结果为 `OK (188 tests)`（`177 passed / 11 skipped / 0 failed`）、收尾基准耗时 `49.641s`。
- 长期文档同步后已重新构建 AndroidTest APK 并在同一 Redmi 复验。测试全程仅使用 `wsvwypiz7xwslvl7`，没有启动、连接或操作 Pixel_9；第 93 阶段的通用生产调用仍未传入 `answerabilityNotice`，第 96 阶段仅增加前台直接 Agent 的默认关闭旁路。

## 第 92 阶段：答案可回答性策略（实现与真实 Provider shadow 验收完成）

- 新增 `KnowledgeAnswerability.kt`，把模型输出限制为单个严格 JSON 对象和固定 verdict 枚举：`ANSWERED`、`PARTIALLY_ANSWERED`、`NOT_ANSWERED`、`UNKNOWN`。字段集合、数值范围、证据片段长度、reason code 和 verdict/证据组合均在解析入口校验；协议错误与语义矛盾直接 fail-closed。
- `ANSWERED` 必须携带候选正文中的原文片段。`KnowledgeAnswerabilityEvidenceMatcher` 先做有限空白归一化，再回到候选正文匹配、合并重叠区间并计算覆盖率；模型声称的、但候选正文不存在的 quote 不能被接受。`UNKNOWN` 只进入未知决策，不计作负例拒绝。
- `KnowledgeAnswerabilityObservation` 支持 `VERDICT_AND_EXACT_EVIDENCE`、`VERDICT_EVIDENCE_AND_CONFIDENCE`、`VERDICT_EVIDENCE_CONFIDENCE_AND_COVERAGE` 三类预注册特征族。校准阶段选择门禁，验证阶段只应用冻结门禁；Judge identity 必须一致、dataset version 必须互异、三桶标签完整且 case ID 不得跨标签。该阶段只冻结策略，不读取 Room、不修改检索、不接入答案链路或生产 enforcement。
- 新增 `KnowledgeAnswerabilityPolicyTest` 覆盖严格 JSON、证据匹配、模型幻造 quote、校准/验证隔离、UNKNOWN 计分、身份漂移和部分/矛盾回答拒绝，共 `7/7`。`RealProviderKnowledgeAnswerabilityInstrumentedTest` 预注册两套各 6 用例、每例 2 次，共 `12 + 12` 条观测；显式参数名为 `answerabilityProviderBaseUrl`、`answerabilityProviderApiKey`、`answerabilityProviderModel`、`answerabilityProviderId`，每次请求最多一次重试，最终失败进入 `UNKNOWN`。
- 真实 Redmi 探针使用 `redmi-provider-compatibility / gpt-5.5` 完成 calibration/validation 各 `12` 条观测，网络与解析失败均为 `0`，最新身份校正复验 `RealProviderKnowledgeAnswerabilityInstrumentedTest` 为 `OK (1 test)`、耗时 `94.154s`。`VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE` 达标，覆盖率特征族未通过。
- 默认完整 Redmi instrumentation 为 `OK (188 tests)`、0 失败。收尾后已恢复兜底 Provider、6 个可用模型和默认 `gpt-5.5` Profile，普通聊天 `ping -> pong` 为 `2.44s`；`MainActivity` 前台、crash buffer 为空，设备 Agent 保持默认关闭/未授权。
- `productionEnforcementEnabled=false`，当前生产 Room、检索、消息和答案路径仍不读取本策略；第 92 阶段当时留下的展示、只读绑定与协调评审已由第 93 至 95 阶段完成，但两类通过特征仍不能扩张为生产拒绝资格。

## 第 91 阶段：跨主题平移不变特征探针否决

- 新增 `KnowledgeRelevanceCrossTopicNormalizationPolicy`，预注册 `top1 - 候选均值`、`margin / 候选标准差` 和两者组合三类特征族。`fromCandidateDistribution()` 统一从已有审计字段构造特征，并拒绝非有限 top1/均值/margin、负 margin 以及不高于 `1e-12` 的候选标准差，避免零方差制造巨大比值。
- 正式 calibration/validation 继续绑定生产 Provider、模型、配置指纹和不同 `datasetVersion`。阈值只从 calibration 的真实观测点组合选择，validation 只应用冻结阈值；该实现不改 Room v32、生产 Store、`knowledge.search`、普通聊天、Workflow、答案路径或 enforcement。
- Redmi `wsvwypiz7xwslvl7` 使用 `stage91-cross-topic-calibration-v1 / stage91-cross-topic-validation-v1` 两套全新主题语料，各 12 篇文档、正/近负/远负各 4 条查询并重复 2 次，共 `24 + 24` 条观测，Recall@5 均为 `1.0`。三次有效运行均 `OK (1 test)`，查询中位数约为 calibration `711–780ms`、validation `734–780ms`，通过族始终为 `0`。
- `TOP_SCORE_MEAN_GAP` 的 calibration 阈值约 `0.2904–0.2906`；validation 正例接纳 `1.0`、近负例拒绝 `0.75`、远负例拒绝 `1.0`、稳定率 `1.0`、balanced accuracy `0.9167`。组合族得到相同决策；`MARGIN_OVER_STANDARD_DEVIATION` 只有正例接纳 `0.75`、近负例拒绝 `0.25`、远负例拒绝 `1.0`、balanced accuracy `0.6667`。
- 结论：平移不变特征修复了第 90 阶段的正例误拒方向，但会把同主题且语料未覆盖的问题当作相关，因此仍被预注册近负例标准否决。不得用 validation 回调阈值或降低 `0.80` 近负例标准；不进入 final holdout，不升级 `VERIFIED`，`productionEnforcementEnabled=false`。下一步若继续相关性工作，应先设计能判断“文档是否真正回答问题”的 answerability/重排证据，而不是继续调同一批检索分数。
- 完整本地门禁为 JVM `541/541`、Lint、Debug/AndroidTest APK；Debug APK 为 `23,026,298` 字节，SHA-256 `808b4b7372f717bbee1cd4ebe8962a769872d289df7c5a6d039bc0e68c0c93be`。唤醒并退出 Redmi dream/keyguard 后，默认全量 JUnit XML 为 `186` 条（`176 passed / 10 skipped / 0 failed`）；10 个显式联网用例无参数按设计 skipped。测试包已卸载，主 APK、兜底 Provider 和默认 Agent Profile 已恢复，系统亮屏/屏保设置已还原；Device Agent 与 Accessibility 仍保持新安装后的 opt-in/未授权状态，没有把测试自动授权冒充用户授权。

## 第 90 阶段：正式相关性 calibration/validation 预注册门禁否决

- 新增 `KnowledgeRelevanceProductionDatasetIdentity` 与 `KnowledgeRelevanceProductionCalibrationPolicy`。正式 calibration/validation 必须同时绑定生产 Provider ID、模型、配置指纹，且 `datasetVersion` 不得复用；比较阶段继续复用既有七类特征族，只从 calibration 冻结阈值，再原样评估独立 validation，不把 validation 结果回调为新阈值。
- 显式 Redmi 测试使用 `redmi-production-embedding-v1 / Qwen/Qwen3-Embedding-0.6B`，配置指纹为 `2f22bfe3b9db92555f493c173116c58970490ece7fa90b8c7bf156aa7456dbf6`，`stage90-formal-calibration-v1` 与 `stage90-formal-validation-v1` 各 24 条观测，Recall@5 均为 `1.0`。最新一次 raw top1 calibration 阈值为 `0.7111779316353192`，validation 正例接纳率 `0.75`、近/远负例拒绝 `1.0`、决策稳定 `1.0`；另一次重复取证正例接纳率为 `0.625`，两次七类特征族均无一通过预注册标准。
- 测试把“没有特征族通过”编码为预期质量门禁否决，显式 Redmi instrumentation `OK (1 test)`；`productionEnforcementEnabled=false`，不会升级为 `VERIFIED`，不会进入 final holdout，也不修改 Room、生产 Store、`knowledge.search`、普通聊天、Workflow 或 enforcement。新增 JVM 模型漂移回归，确认模型身份变化在比较前 fail-closed。
- 本地完整门禁为 JVM `535/535`、Lint、Debug/AndroidTest APK；默认仅 Redmi `wsvwypiz7xwslvl7` 的 instrumentation XML 为 `185` 条（`176 passed / 9 skipped / 0 failed`）。
- 结论：本 Provider 的排序 Recall 仍好，但跨主题正例分数漂移使相关性接纳不足；不得为让 gate 通过而降低预注册标准、使用 validation 调参或把结果解释为生产可用。当前正式身份继续为 `CANDIDATE`，答案路径保持 `SHADOW`。

## 第 89 阶段：生产身份绑定与相关性灰度控制面

- 新增 `KnowledgeRelevanceProductionIdentity` 及 `UNBOUND / CANDIDATE / VERIFIED / REVOKED` 状态。真实 Provider 探针必须同时证明 Provider ID、模型、模型列表、向量数量和维度有效；协议可用只生成 `CANDIDATE`，不能据此伪造 `VERIFIED`。
- 生产身份只保存 Provider ID、模型和配置指纹。`KnowledgeRelevanceIdentityFingerprint` 对规范化 Base URL 计算 SHA-256，偏好、Room 与日志不保存原始 Base URL 或 API Key；配置端点漂移会得到不同指纹并失去执行资格。
- `promoteVerified()` 要求候选身份与冻结 gate 的 calibration/validation 身份、全新 holdout 身份和证据中的 Provider/模型完全一致，gate 版本、配置指纹和证据版本完整，final holdout 明确通过，且 holdout 不复用 calibration/validation 数据集。任一身份或证据漂移都拒绝升级。
- `KnowledgeRelevanceRolloutPreference` 新增证据版本与配置指纹；`KnowledgeRelevanceRolloutControlPlane` 先执行原 gate/Provider/模型校验，再要求身份为 `VERIFIED`、证据与配置指纹匹配。候选、撤销、过期 gate、身份漂移或偏好不完整全部解析为 `SHADOW`。
- `UiPreferenceStore` 独立保存执行资格和身份绑定。`rollbackKnowledgeRelevanceRollout()` 只清除未来执行资格；设置页撤销动作另行把身份标记为 `REVOKED`，保留最小审计但不允许旧授权复活。
- 设置根页新增「相关性灰度控制面」入口。独立页面展示身份状态、Provider、模型、配置指纹、gate、证据、holdout 和当前固定 `SHADOW`，并明确生产答案路径尚未接入；页面没有绑定、升级或直接开启 enforcement 的入口。
- JVM 新增身份策略 `6` 条和控制面 `4` 条，完整 JVM 为 `532/532`。仅 Redmi 默认完整 instrumentation 为 `184` 条、`176 passed / 8 skipped / 0 failed`；新增 UI `2/2`、偏好存储 `4/4` 通过。显式真实身份探针 `1/1` 返回两条 `1024` 维有限向量，状态严格为 `CANDIDATE`。
- 当前正式 Provider 身份与 Stage 85/86 实验 Provider ID 不同；第 90 阶段已在该正式身份下完成独立 calibration/validation，但七类特征族均未达到预注册标准，因此候选仍不能升级或进入 final holdout。生产 Store、`knowledge.search`、普通聊天和 Workflow 继续不读取控制面，后续应先决定新的跨主题归一化/数据设计，再重新注册证据，不得回调本阶段阈值。

## 第 88 阶段：相关性降级、引用一致性与身份灰度契约

- `KnowledgeSearchHit` 新增 `matchChannels`，`RoomKnowledgeDocumentStore` 在既有融合结果组装时标记 `LEXICAL / SEMANTIC`：语义-only、词法-only 和两者重叠都有明确来源。来源集合只描述同一次融合输入，不进入 RRF、排序、召回、Room Schema 或历史审计。
- 新增纯 Kotlin `KnowledgeRelevanceUserExperiencePolicy`。未来 enforcement 低分时，`DROP_SEMANTIC_KEEP_LEXICAL` 只保留含 `LEXICAL` 的候选，因此 lexical-only 与重叠命中继续存在；`DROP_SEMANTIC_NO_LEXICAL` 返回空候选。引用统一由最终 hits 生成，不能把已移除的 semantic-only chunk 继续交给模型或 UI。
- 用户提示标题固定为“已降级为关键词匹配”“未找到足够可靠的本地知识”“相关性检查暂未应用”。候选来源缺失、决策与来源矛盾，或 `enforcementEnabled=false` 的 shadow 快照携带删除 disposition 时全部 fail-open，并保留当前 hits 与引用。
- `KnowledgeReferencesContent` 新增默认 `null` 的 `relevanceNotice`，所以既有调用行为不变；有提示但零引用时仍渲染解释，不显示虚假的“知识引用 · 0”。该参数当前尚未由生产消息流传入。
- 新增 `KnowledgeRelevanceRolloutPolicy` 和 `KnowledgeRelevanceRolloutPreference`。偏好默认关闭；只有 gate 版本、Provider、模型都与冻结身份一致时才解析为 `ENFORCE`，缺项、版本过期或身份漂移自动回到 `SHADOW`。结构不完整、calibration/validation 复用同一数据集或阈值非有限的冻结 gate 直接拒绝；rollback 清除执行位、gate、Provider 与模型四项资格。
- `UiPreferenceStore` 已能持久化上述灰度偏好，但当前 ViewModel、`RoomKnowledgeDocumentStore.search()`、`knowledge.search` 和 Workflow 都不读取它。Stage 85/86 中的实验 Provider ID 不是正式生产身份，接入前必须以真实 Provider/模型重新绑定并复验，不能把本阶段契约解释为已上线。
- Standards/Spec 双轴审查发现 rollout gate 校验弱于第 87 阶段：原实现没有拒绝空 datasetVersion 或 calibration/validation 数据集复用。补齐校验与回归后，Stage 87+88 聚焦 JVM `16/16`、完整 JVM `522/522`、Lint、Debug/AndroidTest APK 均通过。
- 仅在 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation。首次长套件因设备进入 dream/keyguard，使后段 20 个既有 Compose 用例统一报 `No compose hierarchies found in the app`；唤醒后该失败集合 `20/20` 通过，临时保持唤醒后的完整 JUnit XML 为 `180` 条、`173 passed / 7 skipped / 0 failed`。临时系统设置已恢复，未连接或操作 Pixel_9。
- 最终 Debug APK 为 `22,977,146` 字节，SHA-256 `f20896c7a1bb8cfb6b5ff4c560352ddcb3ae56045aade26e17b09b4f4cb332c6`。正式应用恢复为 Room v32、`gpt-5.5` Provider、7 个设备工具 Profile、默认 User-Agent、设备 Agent 开关和 Accessibility Enabled/Bound；真实 `/responses` 冒烟通过，测试包与 crash buffer 无残留。

## 第 87 阶段：生产相关性拒绝设计评审边界

- 新增纯 Kotlin `KnowledgeRelevanceProductionDesignPolicy`，复用 Stage 86 冻结的 `KnowledgeRelevanceRawTopScoreFrozenGate`，要求 calibration/validation Provider、模型和数据集身份完整且一致；策略构造时拒绝空身份、数据集复用和非有限阈值。
- `USED` 语义检索且身份一致、top1 达到冻结下限时保留当前结果；低于下限时只生成“移除语义候选”的计划，若已有词法命中则明确保留词法兜底。开关关闭时始终只返回 `KEEP_CURRENT_RESULTS`，同时记录是否会触发拒绝的 shadow 判断。
- `LEXICAL_ONLY`、无索引、Provider 不可用、维度不匹配、Provider/模型漂移、缺失分数和非有限分数全部 fail-open，保留现有结果；策略不调用 `RoomKnowledgeDocumentStore.search()`，不写 Room、不改变 UI、不新增生产拒绝状态。
- 新增 5 条 JVM 契约，覆盖关闭开关、低分词法兜底、高分保留、非语义/身份漂移、未知/非法分数和冻结 gate 校验。聚焦测试 `5/5` 通过。
- 完整门禁为 JVM `511/511`、Lint、Debug/AndroidTest APK 和仅 Redmi 默认 instrumentation `178` 条（`171 passed / 7 skipped / 0 failed`）。收尾后正式应用 Room `user_version=32`、Provider 模型 `gpt-5.5`、设备 Agent 开关开启，Redmi Accessibility Enabled/Bound、`Crashed services` 为空，主 Activity 在前台。

## 第 86 阶段预注册实现边界

- 新增纯 Kotlin `KnowledgeRelevanceFinalHoldoutPolicy`，冻结 gate 版本、Stage 85 calibration/validation 完整身份和 raw top1 下限；final holdout 必须使用同 Provider/模型的第三个 datasetVersion。策略只读取 `rawTopScore`，不调用 Stage 85 候选搜索，也不使用 margin 或 z-score。
- 4 条 JVM 契约覆盖成功评估、Provider/模型/两套已见数据复用拒绝、非法身份/标准/样本，以及失败后冻结阈值保持不变。聚焦测试 `4/4` 通过。
- `RealProviderKnowledgeFeatureComparisonInstrumentedTest` 新增默认跳过的 final holdout 用例；全新 `stage86-final-holdout-v1` 固定 20 篇成对主题语料、三桶各 10 条查询和每条 2 次重复，并复用生产 Embedding 适配器与独立内存 Room。采集同时输出拒绝指标、Recall@1/5、MRR 和排序稳定率。
- Redmi final holdout 首次有效运行 `1/1` 为 `63.077s`；补齐 validation Provider/模型身份校验并同步重建 Debug/Test APK 后，最终复验 `1/1` 为 `67.018s`。最终 60 条观测的正例接纳 `0.90`、近/远负例拒绝 `1.0`、决策稳定 `1.0`、balanced accuracy `0.9667`，Recall@1/5、MRR、排序稳定均为 `1.0`。中间 ABI 不一致和一次检索空分数回归未计入证据，也未用于调参；结论只冻结为当前 Provider/模型下的评审证据，不修改生产 `RoomKnowledgeDocumentStore`、Room v32、UI、Provider 配置或相关性拒绝。

## 第 85 阶段实现与验证边界

- 新增 `KnowledgeRelevanceFeatureComparisonPolicy`，固定 7 个特征族并以统一 feature vector 表达 raw top1、margin 和 top1 z-score。每个特征族只从 calibration 真实观测值的笛卡尔积搜索，按三桶等权 balanced accuracy、正例接纳、两类负例拒绝、稳定率和阈值顺序确定可复现 gate。
- `compare()` 强制 calibration/validation Provider 与模型一致、datasetVersion 不同；两侧都要求三桶完整、case ID 非空且不跨标签、全部特征有限。validation 只调用 `evaluateFrozenGates()`，不会参与 calibration gate 搜索。3 条 JVM 测试覆盖全部 7 个特征族、冻结阈值不回调，以及缺桶、非有限值、标签漂移和数据集复用拒绝。
- 新增显式联网 `RealProviderKnowledgeFeatureComparisonInstrumentedTest`。`stage85-calibration-v1` 与 `stage85-validation-v1` 各自建立独立内存 Room，分别导入 20 篇全新成对主题语料；每套三桶各 10 条查询、每条重复 2 次。首轮草案曾把 companion 文档可直接回答的问题误标为近负例，已在形成阶段证据前废弃；有效版本改为同主题但语料未覆盖的具体事实。
- Redmi 有效运行 `1/1`，耗时 `132.872s`，两套各 60 条观测且 Recall@5 均为 `1.0`。raw top1 与 raw+margin validation 均为正例接纳 `0.90`、近/远负例拒绝 `1.0`、稳定率 `1.0`、balanced accuracy `0.9667`；raw+margin 没有增益，下一阶段优先冻结 raw top1 `0.6416276358587735`。
- 本阶段不修改生产检索、Room v32、UI 或 Provider 配置，也不复用 Stage 83 holdout。完整门禁为 JVM `502/502`、Lint、Debug/AndroidTest APK 和仅 Redmi `177/177` instrumentation；默认 6 个显式联网用例按设计 skipped。

## 第 84 阶段实现与验证边界

- 新增 `KnowledgeRelevanceRelativeDiagnosticsPolicy` 与结果模型。输入必须非空且全部有限；均值和总体标准差使用当前输入候选池的全部分数，top1 z-score 只在至少两个候选且标准差高于数值容差时生成。4 条 JVM 测试覆盖准确计算、整体平移不变、单候选/零方差和非法输入。
- `RoomKnowledgeDocumentStore.loadSemanticCandidates()` 遵守既有 2000 行语义索引上限，并在 top-K 截断前对当前有界候选池的全部有效 cosine 分数计算相对观测，检索 limit 不改变同一查询的均值、标准差或 z-score。该数据只进入审计，不参与 `KnowledgeSearchFusionPolicy`、排序、enabled/revision 复核或回退决策。
- `KnowledgeRetrievalRecord` 与 `KnowledgeRetrievalEntity` 新增 `embeddingScoreMean`、`embeddingScoreStandardDeviation`、`embeddingTopScoreZScore`。Room v31→v32 只增加三个可空 REAL 列；历史检索缺少完整候选分布，迁移保持 `null`。Provider 未执行、失败、无索引或维度不匹配同样不伪造相对指标。
- 知识管理页把均值、标准差和 top1 z 追加到既有“校准观测”，仍不使用通过/拒绝文案。Redmi 迁移、生产 Room 写入回读和 Compose 展示 `3/3` 通过；真实 Provider 语义链 `1/1` 通过。
- 已退休 Stage 83 holdout 的单次 shadow 观测仅确认字段链路：正例 z-score `2.929–3.722`、近负例 `2.226–3.232`、远负例 `1.579–2.879`。正例与近负例仍有重叠，本阶段没有计算候选阈值，也不改变旧门禁被否决的结论。
- 完整门禁为 JVM `499/499`、Lint、Debug/AndroidTest APK 和仅 Redmi `176/176` instrumentation 通过；默认 5 个显式联网用例按设计 skipped。Debug APK 为 `22,944,378` 字节、SHA-256 `98f6e620bda4a88c0c14ecdfb2103a0a1e0ba08d58b875be5762f5ebb03da2a8`；AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用哈希。

## 第 83 阶段实现与验证边界

- `KnowledgeRelevanceCalibration.kt` 新增 holdout 数据集身份、冻结门禁、预注册标准、报告与评估策略。策略先校验 Provider/模型/版本、有限阈值、0 到 1 的比例、三桶完整性和 case 标签稳定性；holdout 版本不得等于校准版本。
- 决策严格使用传入的冻结 top1 与 margin，不调用第 82 阶段候选搜索，也不返回 holdout 最优阈值。报告只包含正例接纳率、近/远负例拒绝率、重复决策稳定率和总门禁结果；4 条 JVM 测试覆盖通过、身份漂移、非法输入和“失败后不得自身调参”。
- `RealProviderKnowledgeScaleInstrumentedTest` 新增全新 20 篇成对主题语料和三桶各 10 条查询，每条重复 2 次。测试固定模型、门禁版本、校准/holdout 版本、阈值及预注册标准；每次观测输出短 JSON，汇总输出质量、耗时、向量和内存指标，不输出 Base URL 或 API Key。
- Redmi 三个独立进程均完成 60 个观测，排序质量门禁通过，但相关性门禁失败：正例接纳率 `0.80`，低于 `0.90`；近/远负例拒绝率、决策稳定率、Recall@1/5、MRR 和排序稳定率均为 `1.0`。手冲咖啡与缝纫机张力正例 top1 约 `0.6169 / 0.6661`，低于冻结 `0.6735426515268672`；margin 均满足下限，因此误拒来自绝对分数跨主题漂移。
- 三轮索引耗时 `14.237–14.696s`，查询中位数 `0.757–0.800s`、P95 `0.814–0.836s`；20 行 1024 维 Float32 向量共 `81,920` 字节，检索后 PSS `202,684–202,935 KB`。这些观测不作为阈值回调依据。
- 本阶段不修改 `RoomKnowledgeDocumentStore.search()`、Room Schema、配置或 UI。第 82 阶段候选被否决，生产继续使用 Room v31、cosine+RRF、FTS4+LIKE 和 shadow 审计；新方案必须使用新版本和新的独立验证数据。
- 完整离线门禁为 JVM `495/495`、Lint、Debug/AndroidTest APK 和仅 Redmi `175/175` instrumentation 通过；默认 5 个显式联网用例按设计 skipped。三轮显式 holdout 均因预注册正例接纳率断言失败，和默认离线回归结果分别记录。

## 第 82 阶段实现与验证边界

- 新增纯 Kotlin `KnowledgeRelevanceCalibrationPolicy`、标签、样本、分桶分布、候选门禁与报告模型。输入先校验三桶完整性、有限值、非空 case ID 和 case 标签稳定性；分位数使用 nearest-rank。门禁遍历已观测 top1/margin 的笛卡尔积，以三桶等权 balanced accuracy 选优，并按正例接纳率、近/远负例拒绝率和阈值固定顺序消除同分漂移。
- `RealProviderKnowledgeScaleInstrumentedTest` 的校准语料扩为原 10 篇加 10 篇同主题干扰文档；三桶各 10 条英文查询，每条重复两次。每个观测单独输出短 JSON，汇总再输出指标，避免 Android 单条 Logcat 长度截断；日志只包含测试 Provider ID、模型、用例和指标，不输出 Base URL/API Key。
- 校准汇总新增 top1/margin P05/P50/P95、Recall@1/5、MRR、排序稳定率、索引/查询耗时、候选数、向量行数/维度/字节、PSS、Java heap 与 shadow 候选门禁。正例质量按标注文档名计算；近负例和远负例只进入候选拒绝率观测，不伪造生产拒绝状态。
- Redmi 三次独立进程均 `1/1` 通过，共 180 个观测。20 个候选、20 行 1024 维向量和 `81,920` 字节三轮一致；Recall@1/5、MRR、稳定率与同集 balanced accuracy 均为 `1.0`。候选 top1/margin 下限分别为 `0.6735–0.6741 / 0.0179–0.0184`，查询中位数 `0.797–0.807s`、P95 `0.824–0.866s`。
- 该策略不被生产 `RoomKnowledgeDocumentStore.search()` 调用，也没有 Room migration、配置写入或 UI 门禁。第 83 阶段先冻结候选并执行独立 holdout；完成前继续使用 Room v31、cosine+RRF 和词法兜底。
- 完整门禁为 JVM `491/491`、Lint、Debug/AndroidTest APK 和仅 Redmi `174/174` instrumentation 通过；默认套件 4 个显式联网用例按设计 skipped。Debug APK 为 `22,927,994` 字节、SHA-256 `4ea3ba2ef068f98c1dc8319d0d8c630b8c90999bc987cd9449ca4315564d7610`；AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不在文档中记录自引用哈希。

## 第 81 阶段实现与验证边界

- `KnowledgeRetrievalRecord` 与 `knowledge_retrievals` 新增 `embeddingTopScore`、`embeddingSecondScore`、`embeddingScoreMargin`、`embeddingCandidateCount`。Room v30→v31 Migration 只增加可空列；旧记录四项均为 `null`，不从历史 chunk IDs 或当前索引反推分数。
- `RoomKnowledgeDocumentStore.loadSemanticCandidates()` 仍按当前 Provider/模型扫描有界索引并稳定排序；现在同时保存有效候选总数和前两名分数，只有第二名存在时才保存 margin。索引为空或全部向量不可比较记录候选数 `0`；Provider 未执行或不可用保持未知。最终 top-K、RRF、enabled/revision 复核和词法兜底行为不变。
- 知识管理页在原 Embedding 状态与身份下新增“校准观测”行，分数固定显示 4 位小数；单候选不伪造 top2/margin，历史和纯词法记录不显示空占位。该 UI 不使用“通过”或“拒绝”文案。
- `RealProviderKnowledgeScaleInstrumentedTest` 复用 10 篇固定中文语料，新增正例、近负例、远负例各 2 条的显式联网校准；输出按 `providerId + model` 隔离的 JSON 观测与分桶最小/最大值，不输出配置或密钥，也不把经验分数设为断言。Redmi 真实 top1 为 `0.6806–0.7130 / 0.6502–0.6854 / 0.3704–0.4083`，margin 为 `0.2743–0.2828 / 0.1311–0.2114 / 0.0274–0.0507`，校准 `1/1` 通过。
- 聚焦 Redmi 验证：审计持久化 `1/1`、v30→v31 迁移 `1/1`、知识管理 Compose `6/6`。最终 JVM `488/488`、Lint、Debug/AndroidTest APK 和 Redmi 完整 instrumentation `174/174` 通过；默认套件的 4 个真实 Provider 用例按设计跳过。当前不新增生产拒绝状态、ANN 或后台批量索引。

## 第 80 阶段实现与验证边界

- 新增 `RealProviderKnowledgeScaleInstrumentedTest`，显式传入 Base URL、API Key 和 Embedding 模型时才联网；默认套件缺参按设计 skipped。测试直接复用生产客户端、Embedding Provider、Room Store、cosine/RRF 与质量策略，不保存或打印真实配置。
- 语料固定为 10 篇中文单主题短文；5 个英文正例在同一数据库的纯词法 Store 中零命中，真实语义 Store 各执行两次并要求 `USED`。质量硬门禁为 Recall@5 `>=0.8`、MRR `>=0.7`、稳定率 `>=0.8`；三轮 Redmi 实测三项均为 `1.0`。
- 向量统计直接读取内存 Room：10 个 chunk 对应 10 行、1024 维向量，原始 Float32 BLOB 共 `40,960` 字节，与 `rows * dimensions * 4` 完全一致；SQLite 页总量为 `593,920` 字节。Schema 保持 v30，没有修改生产 DAO 或正式数据。
- `SystemClock.elapsedRealtimeNanos()` 记录索引和查询端到端耗时，`Debug.getPss()` 与 Runtime heap 记录进程边界。三轮索引 `7.935–10.039s`（中位数 `8.881s`）；每轮查询中位数 `0.811–1.100s`，P95 `1.016–1.496s`；检索后 PSS 增量 `7,358–15,941 KB`。这些值受 Provider、TLS、网络和 GC 影响，只记录而不作硬阈值。
- 无关珊瑚产卵问题三轮均返回 5 个近邻；当前索引还没有可证明的相似度拒绝线。下一阶段应先用正负配对语料采集分布并建立可迁移策略，不直接固定单一经验阈值。本规模下向量扫描和前台导入仍可用，不引入 ANN 或后台批量索引。
- 最终完整 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 记录 `171` 个用例、`168` passed、`3` 个显式联网用例按设计 skipped、`0` failed。

## 第 79 阶段实现与验证边界

- 新增 `RealProviderKnowledgeSearchInstrumentedTest`，以 instrumentation 参数接收真实 Embedding Provider；缺少任一参数即 `assume` 跳过，默认完整套件保持离线可重复。
- 测试直接使用生产 `OpenAiCompatibleClient`、`OpenAiKnowledgeEmbeddingProvider` 和 `RoomKnowledgeDocumentStore`，不复制 URL、批处理、向量校验、cosine、RRF 或审计实现。内存 Room 在 `finally` 中关闭，不保存正式文档、向量或检索记录，也不切换手机聊天 Provider。
- 固定语料包含中文专注方法和面包制作文档；英文查询在无 Embedding Provider 的同一数据库中零命中并记录 `LEXICAL_ONLY`，接入真实 Provider 后首位命中专注文档并记录 `USED`。断言同时覆盖 Provider/模型身份、最终 chunk IDs、索引非零维度/分块数，以及显式重建后 revision 保持 1。
- Redmi 真实协议冒烟 `1/1` 和真实 Room 语义链 `1/1` 均通过；语义链总耗时约 `5.947s`。测试使用独立真实 Embedding Provider，聊天兜底 Provider 随后恢复；Room 保持 v30，生产代码和正式数据库未改变。
- 最终完整 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 记录 `170` 个用例、`168` passed、`2` 个显式联网用例按设计 skipped、`0` failed，不把公网稳定性变成默认合并门禁。

## 第 78 阶段实现与验证边界

- 新增纯 Kotlin `KnowledgeSearchQualityPolicy`，输入按用例保存相关文档集合、重复运行排名与 K；评测先按文档 ID 去重再截断，输出正/负用例数、平均 Recall@K、MRR、负例准确率和完整排名稳定率。指标不混入 Provider 延迟或网络瞬时状态。
- `RoomKnowledgeDocumentStoreInstrumentedTest` 继续复用打包到 AndroidTest assets 的 5 份核心长期文档；5 个固定正例和 1 个不存在词负例各检索两次，门禁满足 Recall@5 `1.0`、MRR `>= 0.8`、负例准确率 `1.0`、稳定率 `1.0`。同一文档的多个 chunk 不会虚增 Recall。
- 知识管理页在检索审计中显示 `LEXICAL_ONLY / USED / NO_INDEX / PROVIDER_UNAVAILABLE / DIMENSION_MISMATCH` 对应中文路径；只有非空身份才追加 Provider/模型。Compose 覆盖全部回退状态、零命中审计和空身份无多余分隔符。
- 新增显式参数驱动的 `ProviderEmbeddingCompatibilityInstrumentedTest`：默认完整套件不访问公网；真实验收先同步模型并恢复 Provider 配置，只在列表明确包含 Embedding 模型时请求两个向量并校验数量、非空、维度一致和有限值。
- Redmi 兜底 Provider 的 `/models` 已真实同步成功并恢复到应用，但列表没有可识别的 Embedding 模型，因此该单项在模型能力门禁处跳过 `/embeddings`；当前真实设备只能证明 Provider 可用与词法兜底，不能证明上游向量端点兼容。
- 质量策略聚焦 JVM `5/5`、知识管理 Compose `6/6` 通过；完整 JVM `488/488`、Lint 和 Debug/AndroidTest APK 通过。只在 Redmi `wsvwypiz7xwslvl7` 执行完整 instrumentation，共 `169` 个用例：`168` passed、`1` 个无显式参数的联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段实现与验证边界

- `KnowledgeDocumentStore` 新增 `rebuildEmbeddings()` 与 `getEmbeddingIndexes()`；旧文档可以在不修改正文和 revision 的前提下补建当前 Provider/模型索引，管理页同时展示 Provider、模型、维度与分块数。
- 重建先读取同一事务内的文档与 chunks，再执行最长 30 秒的 Provider 请求。数量校验成功后构造向量行，写事务内重新核对 revision、enabled 和 chunk ID；只有全部一致才按 `documentId + providerId + model` 定向删除并写入。Provider 异常、超时、停用和并发替换均返回稳定状态，不删除已有向量。
- `knowledge_chunk_embeddings` 原主键已经是 `chunkId + providerId + model`，因此无需升级 Room：不同 Provider/模型空间可共存，重复重建一个空间不影响其他空间；正文替换和文档删除仍全量清理旧 revision/全部空间。
- `KnowledgeManagementViewModel` 复用文档 mutation 串行门禁，重建前取消在途检索，完成后重载详情与索引摘要；Compose 使用 Refresh 图标入口，停用文档禁用重建，无索引时明确显示 FTS4+LIKE 兜底。
- Redmi 定向知识 Store `17/17`、ViewModel/Compose `9/9` 通过；完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和 Redmi `164/164` instrumentation 全部通过，0 skipped、0 failed。未连接、启动或操作 Pixel_9。
- 当前未实现 ANN、自动后台批量重建和规模化性能验证；显式重建仍是前台单文档操作，不进入 Workflow 或后台设备自动化。

## 第 76 阶段实现与验证边界

- `ProviderProfile.preferredEmbeddingModel()` 只从已同步模型列表选择明确的 Embedding 模型；Agent Profile 与知识管理分别使用 Profile 对应或当前选中 Provider，并把 `providerId` 写入请求配置，避免向量审计身份漂移。
- `OpenAiCompatibleClient.createEmbeddings()` 调用规范化后的 `/embeddings`，复用 Bearer、User-Agent、自定义 Header 和现有错误分类；响应按 `index` 恢复输入顺序，并拒绝数量、维度、重复索引和非有限值异常。`KnowledgeEmbeddingVectorCodec` 统一 little-endian Float32，`KnowledgeSearchFusionPolicy` 在有语义候选时使用稳定 RRF，没有语义候选时保留旧 FTS+LIKE 顺序。
- Room v29→v30 创建 `knowledge_chunk_embeddings`（主键为 `chunkId + providerId + model`，附带 revision、维度、BLOB 和创建时间），并为 `knowledge_retrievals` 增加 embedding Provider、模型和状态列；历史记录默认 `LEXICAL_ONLY`，迁移不补造向量。
- `RoomKnowledgeDocumentStore` 在导入/替换提交正文、chunks 和 FTS 后尝试建立向量，索引总时限 30 秒；查询向量 2 秒超时。失败、超时、无索引或维度不符只记录稳定状态并回退词法检索。语义候选与词法候选最终组装时再次核对文档 enabled/revision；替换/删除同步清理旧 revision 向量。
- 新增 `KnowledgeEmbeddingTest`、网络协议测试、v29→v30 MigrationTestHelper 和 Room 存储 instrumentation，覆盖 Float32 编解码、cosine、RRF、语义-only、重叠去重、Provider 失败、无索引、维度不符、替换/删除清理和历史审计默认值。完整 JVM、Lint、Debug/AndroidTest APK 以及仅 Redmi `158/158` instrumentation 通过。
- 第 77 阶段已补齐前台单文档显式重建和多 Provider/模型索引空间共存；规模化 ANN/向量数据库、自动后台批量重建和设备 Workflow/后台自动化仍未实现。

## 技术栈

- Kotlin
- Jetpack Compose
- OkHttp
- Room
- KSP
- Android Keystore
- Gradle Wrapper

包名：`com.longdev.xiaoling`

当前发布版本：`v0.1.16`（`versionCode 17`，Room v35）

## 第 75 阶段实现与验证边界

- `MessageAttachmentSelection` 将 `/agent` 附件从统一拒绝改为 Responses-only：单条 USER 消息最多一种 Image 或 Document；Chat Completions、Image+Document 混合和错误调用方在进入请求前 fail-closed。
- `XiaoLingViewModel.sendAgentRun()` 为 USER 消息写入稳定的 Image/Document + Text parts，先等待同一快照的 Room BLOB 事务提交，再创建 Agent Run，并在发送后清空待发送附件。
- `OpenAiAgentLlm` 只把可信 USER 附件加入每一轮 Responses 规划请求；总结请求不接收附件。`AgentMessagePartPolicy` 继续把附件限制在 USER 消息，不进入 `VerifiedAgentContext`、Tool part 或 Agent 输出。
- 进程重建后的审批恢复和任务中心重试从 Room 原 USER 消息重建附件；重试复制附件到新 USER 消息和新 Run，旧 Run 保持不变。Workflow/后台 Agent 没有新增附件入口。
- 聚焦 JVM 覆盖 Image/Document 规划请求、每轮复用、总结隔离、Chat/mixed 协议门禁、持久化重复附件拒绝和 assistant 伪造 part 隔离；完整 JVM `477/477`、Lint、Debug/AndroidTest APK 与仅 Redmi `153/153` instrumentation 均通过。真实图片 Run `run-e2c23f3d-c7f9-41cc-9964-e0364741727e`、文档 Run `run-9e66e0eb-7684-4d92-8e5d-cfd3ec044d10` 的 `notes.create` ToolResult 均为 `PASSED` 并完成创建/回读；直接 `complete` 的 Run `run-9f4c1380-60de-4998-b689-65d570812431` 被运行时以“模型未执行任何工具就结束了 Agent Run”拒绝。

## 第 74 阶段实现与验证边界

- 设置根页的“网络请求”改为与模型提供方、提示词等设置一致的 `SettingsEntryCard`，点击后进入独立的 `NETWORK_REQUEST` 子页，不再在根页直接编辑 User-Agent。
- 独立页面复用紧凑设置区和文本输入组件，User-Agent 编辑区固定至少 5 行；右下角提供复制和清空图标按钮，标题区继续提供恢复默认操作。空值时复制与清空禁用，清空仍经现有偏好保存规则回退默认 User-Agent。
- 新增 `NetworkRequestSettingsContentInstrumentedTest`，覆盖复制、清空、空值禁用、重新输入、恢复默认和返回设置。仅使用 Redmi `wsvwypiz7xwslvl7` 完成新增单项 `1/1`、完整 instrumentation `153/153`，并在真实设置页确认入口样式、独立导航、5 行高度和右下角操作布局；未连接或操作模拟器。

## 第 73 阶段实现与验证边界

- 新增 `ConversationSelectionCoordinator`，以稳定 `DeletionStarted / Immediate / Load` 事件组合既有 Session Policy、Persistence Coordinator 与 Load Coordinator；不依赖 Android Context、Compose runtime 或 Repository 实现。
- 新建入口先取消旧加载再发布即时选择；删除入口先取消旧加载、标记版本化删除意图并要求宿主清理运行态，再即时选择或启动完整加载。当前加载失败时协调器先按捕获代次回滚，再发布 Failed；迟到旧失败仍由 Load Coordinator 代次门禁丢弃。
- `ConversationLoadRequest` 删除 `rollbackDeletionIntentOnFailure`，加载层不再承载持久化补偿细节。ViewModel 统一消费协调器事件，只读取/清理 Agent Run 与审批 Map、调用纯投影并在 Immediate/Loaded 后保存选择，从 4121 行降到 4087 行。
- 四条聚焦测试覆盖失败发布前回滚、旧失败不清理同 ID 新删除意图、删最后会话先清理再即时选择，以及新建会话取消迟到加载。聚焦 `4/4`、第 68 至 73 阶段会话组合 `30/30`、完整 ViewModel Kotlin 2.3.20 手工编译通过；后续标准 Gradle 门禁已通过，完整 JVM `472/472`、Redmi instrumentation `152/152`、Lint、Debug APK 与 AndroidTest APK 均成功。Room v29、Provider 协议、UI、`/agent` 与 Workflow 设计边界未改变。

## 第 72 阶段实现与验证边界

- `ConversationSessionPolicy` 新增 `ConversationSelectionPlan.Immediate / Load`、`planOpenNewConversation()`、`planCurrentConversationDeletion()` 和即时状态投影。策略使用可注入时钟，不依赖 Android Context、Room DAO 或协程。
- 新建入口保持三条原行为：当前已选空会话幂等复用且不折叠其他占位；当前有内容时复用 `updatedAt` 最新空占位并折叠其余空占位；没有空占位时创建 `conversation-$now`。删除后有剩余会话选择最新项并交给加载 coordinator，删至空列表立即创建新占位。
- `restoreRuntimeState` 只允许复用既有空会话时回填 Agent Run/审批；新建占位始终清空运行态，避免时间戳 ID 碰撞挂接旧状态。ViewModel 从 4178 行降到 4121 行，仍按原顺序取消加载、标记删除意图、清理运行态 Map、加载/回滚并保存。
- 一轮有效 Red/Green 建立选择计划 seam；五条聚焦 JVM 覆盖当前空会话、最新空占位、新建占位、删除后加载最新会话和删空兜底。聚焦 `5/5`、完整 JVM `468/468`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过；Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。

## 第 71 阶段实现与验证边界

- 新增 `ConversationLoadProjectionPolicy`，把 Loading、Loaded、Failed 三类事件的纯 `XiaoLingUiState` 投影迁出 ViewModel；策略不依赖 Android Context、Room DAO 或 Compose runtime。
- Loaded 先把请求中的所有会话转换为轻量索引，再只为当前目标会话注入完整 `event.messages`，因此非当前 Image/Document BLOB 不驻留在列表快照，当前可见附件仍原子就绪。Loading 清除旧结果，Failed 保留异常消息或稳定兜底。
- `XiaoLingViewModel` 从 4200 行降到 4178 行；删除意图仍在 Failed 投影前回滚，Agent Run/审批映射仍由 ViewModel 读取，Loaded 投影后仍触发选择保存。`ConversationLoadCoordinator` 的 Job/代次门禁、Repository 删除事务和附件持久化未改变。
- 一轮有效 Red/Green 建立独立 seam；三条聚焦 JVM 覆盖 Loading、Loaded 原子切换、Image/Document 轻量化、当前附件完整保留、错误消息与 fallback。聚焦 `3/3`、完整 JVM `463/463`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过；Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。

## 第 70 阶段实现与验证边界

- 新增 `ConversationLoadCoordinator`，以 `viewModelScope` 承接 latest-load Job 和单调选择代次，并通过 `Loading / Loaded / Failed` 事件交还 ViewModel 投影。
- 取消底层查询后仍可能返回或抛错，因此 Loaded/Failed 都先核对当前代次；旧选择不会覆盖当前会话、删除回滚或读取失败提示。
- `XiaoLingViewModel` 删除会话加载 Job 与直接 Room try/catch；仍负责会话/附件轻量化后的原子 UI 更新、删除后选择下一会话、失败回滚和保存。`ConversationRepository` 删除事务、后台 Workflow 并发保护与附件 BLOB 生命周期未改变。
- 四轮 TDD 覆盖迟到成功、迟到失败、显式取消后的旧回调，以及 Loading 回调重入时最新 Job 的登记与取消。聚焦 JVM `4/4`、完整 JVM `460/460`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过；Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。

## 第 69 阶段实现与验证边界

- 新增 `ConversationPersistenceCoordinator`，以 `viewModelScope` 承接 latest-save Job，以 `Mutex` 形成 Room 单写者；旧保存被取消但已进入不可取消提交区时，最新快照等待其退出后再写，确保最终持久化状态不被旧快照覆盖。
- 显式删除意图使用单调代次。快照只确认自己捕获且提交期间未重新标记的代次；事务失败、取消或同 ID 重新删除时继续保留，异步读取失败则在下一快照前回滚。普通聊天发送先取消并等待旧保存，再捕获当前删除代次并通过同一单写者提交用户消息与附件。
- `XiaoLingViewModel` 删除会话保存 Job、待删除集合和保存私有实现，从 4189 行降到 4183 行；异步会话加载、删除后的 UI 切换/失败提示、Compose 事件投影和流式节流仍保留。`ConversationRepository` 的显式删除事务、后台 Workflow 并发保护和 `MessageRepository` 附件 BLOB 保留逻辑未改变。
- 八轮 TDD 覆盖旧待提交快照取消、不可取消提交后最新写入、发送前等待、删除成功确认、失败保留、读取失败回滚、同 ID 重标记代次和旧回滚保护。聚焦 JVM `8/8`、完整 JVM `456/456`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过；Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。

## 第 68 阶段实现与验证边界

- 新增 `ConversationSessionPolicy`，以同包纯 Kotlin 扩展函数统一第一条 `role=user` 消息标题（正文空白时保持“新会话”且不向后跳过）、preferred/最新空会话折叠、会话时间戳、摘要元数据默认继承、blank ID 生成和非当前会话更新隔离；时钟可注入，策略不依赖 Android Context、Room DAO 或 Compose runtime。
- `XiaoLingViewModel` 删除 `withUpdatedCurrentConversation / withUpdatedConversation / firstUserTitle / collapseDuplicateEmptyConversations` 四段私有实现，共减少 83 行，从 4272 行降到 4189 行。异步 Room 加载、保存 Job、删除事务与 Compose 副作用仍保留在 ViewModel，不以本阶段行数变化宣称会话编排已经完全迁出。
- 六轮 TDD 分别固定标题截断、空占位保留、已选会话时间与可见状态、非当前会话隔离、blank ID 和摘要元数据继承。新增聚焦 JVM `6/6`；完整 JVM `448/448`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过。
- instrumentation 后重新安装正式 Debug APK并冷启动；`MainActivity` 处于前台且应用 PID 存在，Room schema 29，仅 `com.longdev.xiaoling` 正式包存在，crash buffer 为空。Provider 协议、消息结构、Compose UI、`/agent` Runtime 与 Workflow 均未改变。

## 第 67 阶段实现与验证边界

- 新增 `ConversationSendCoordinator` 及 `ConversationSendRequest / ConversationSendEvent`，用窄函数依赖统一普通聊天的 Room 快照持久化、上下文准备、网络请求、流式增量和终态事件顺序。协调器不依赖 Android Context、Compose 状态或 Room DAO。
- 用户停止会先发出携带最近 `PreparedRequestContext` 的 `Cancelled`，再在 `finally` 语义下继续抛出原始 `CancellationException`；普通异常发出 `Failed` 并保留故障发生前最后可证明的摘要边界。持久化失败不会继续调用 preparer 或模型。
- `XiaoLingViewModel.sendMessage()` 从约 190 行收敛到约 104 行，只保留入口校验、用户消息投影、旧保存 Job 取消和发送 Job 生命周期；`handleConversationSendEvent()` 继续负责 Compose 状态、30ms 流式节流、最终消息与持久化触发。由于显式事件投影仍在 ViewModel，总文件为 4268 行，本阶段不宣称文件继续缩小。
- TDD 三轮有效 Red 分别暴露缺少协调器 seam、取消时缺少终态事件、发送前 Room 异常直接逃逸。新增聚焦 JVM `3/3`，完整 JVM `442/442`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过。测试后正式 Debug APK 冷启动成功，`MainActivity` 前台 PID `18078`、schema 29、仅正式包存在且 crash buffer 为空。
- Room Schema、Provider 协议、消息结构、Compose UI、`/agent` Runtime 与 Workflow 均未改变；Embedding、设备后台自动化、精确定时和 Foreground Service 继续后置。

## 第 66 阶段实现与验证边界

- 新增 `ConversationRequestContextPreparer`，通过可注入的知识引用核验、摘要生成和时钟形成纯应用服务 seam；它统一处理上下文资格、知识消息投影、摘要失效/复用、最近 16 条窗口、窗口外最多 8 条可信 Agent 结果、增量摘要和 Responses 用户附件映射。
- `XiaoLingViewModel` 删除原有 `prepareRequestContext / buildRequestMessages / messagesNeedingCompression / generateConversationSummary` 等私有编排，只保留 Room、网络 Client 和当前提示词设置的依赖装配；文件从 4439 行降到 4224 行。Room v29、Provider 协议、消息结构和 Compose UI 均未改变。
- 知识引用核验与摘要生成的普通异常继续沿用保守兜底；`CancellationException` 明确重新抛出，停止生成不会被空引用集合或本地摘要误当作成功上下文继续发送。
- TDD 依次得到缺少 preparer、长会话不支持、重复摘要、旧知识摘要未失效、取消被吞、摘要边界丢失后仍复用，以及边界超前导致反向区间七类有效 Red；Responses 最近窗口附件另有特征测试。新增聚焦 JVM `8/8`，完整 JVM `439/439`、Redmi instrumentation `152/152`、Lint、Debug 与 AndroidTest 构建通过。

## 第 65 阶段实现与验证边界

- `XiaoLingUiState` 保存只读加载状态、最近观察和读取错误；`refreshProcessExitObservations()` 只在 IO 线程调用 `RoomProcessExitObservationStore.latest()`，刷新 Job 被替换时继续传播 `CancellationException`。用户查看或刷新页面不会调用 `collect()`，因此不会改变平台观察样本。
- 设置页新增独立“进程退出观察”入口和子页。页面展示六类稳定中文证据标签、reason、PID/status、processName、退出/首次观察时间、importance、PSS/RSS 与 LMK 报告能力，并固定显示“不关联 Agent Run、工作流或任务”的证据边界；Room Schema 保持 v29。
- Compose 测试覆盖空态、边界说明、只读刷新回调，以及全部六类证据的稳定中文标签和直接 LMK/候选/受控退出差异展示。Redmi 聚焦 `3/3`、完整 instrumentation `152/152`、JVM `431/431`、Lint、Debug 与 AndroidTest 构建通过。
- Redmi 正式 UI 手工验收实测 1 条 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，页面刷新后数据库仍为 1 条。随后完整 instrumentation 结束并重新安装正式 Debug APK；最终 `MainActivity` 前台 PID `12951`、schema 29、退出账本 0 条、crash buffer 为空，设备仅保留正式包。最终空账本不覆盖前述受控样本的页面验收结论。

## 第 64 阶段实现与验证边界

- `AndroidProcessExitObservationSource` 在 Android 11+ 读取本应用 `ApplicationExitInfo` 历史，最多请求 30 条，只映射 timestamp、processName、pid、reason/status、importance、PSS/RSS 等稳定数值字段；不读取或持久化 description、trace 和状态摘要。
- `ProcessExitObservationPolicy` 只把 `REASON_LOW_MEMORY` 分类为 `DIRECT_LOW_MEMORY`；当 `isLowMemoryKillReportSupported()` 为 false 时，`REASON_SIGNALED + SIGKILL` 分类为 `LOW_MEMORY_CANDIDATE`，其他应用失败、系统资源、用户/包维护和未知原因分别保留稳定证据族，不能凭时间邻近关联 Task/Run。
- `RoomProcessExitObservationStore` 以 `${timestamp}|${pid}|${reasonCode}|${status}|${processName}` 去重，在同一事务内插入并裁剪到最新 30 条；API 不支持时返回空观察，不发明历史。`collectProcessExitObservationsBestEffort` 只吞普通异常，协程取消继续传播。
- Room v28→v29 只创建空的 `process_exit_observations` 表及 timestamp/evidenceKind 复合索引；表不含 Task/Run 外键，历史退出不会被迁移猜造为某次执行的原因。ViewModel 前台启动在恢复快照前采集，Worker 在 `ScheduledWorkflowProcessExecutionRegistry` 登记 Task 后、构造执行器前采集，观察失败不阻断主流程。
- 聚焦 Redmi `5/5`、完整 Redmi instrumentation `149/149`、JVM `431/431`、Lint、Debug 与 AndroidTest 构建通过。正式 Debug DB 实测 `PRAGMA user_version=29`；受控 `force-stop` 后冷启动记录 1 条 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，没有直接 LMK 或候选 LMK 证据。

## 第 63 阶段实现与验证边界

- `WorkManagerStopReasonInstrumentedTest` 在 Redmi Android 14 入队真实 `CoroutineWorker`，等待进入运行态后调用 `cancelWorkById()`；Worker 的取消出口同步读取 `stopReason`，经生产 `ScheduledWorkerStopReasonPolicy` 得到 `CANCELLED_BY_APP(1)`。测试只使用专用 SharedPreferences 通信并在结束时清理，不依赖模型或正式 Workflow 数据。
- `RoomWorkflowRepositoryInstrumentedTest` 新增用户停止优先级契约：`STOP_REQUESTED` 已保存用户原因后，即使结算收到 `CANCELLED_BY_APP(1)`，ScheduledTask 与 WorkflowRun 仍以同一事务收敛为取消、保留用户原因，并且停止原因 code/name 维持空值。
- 聚焦 Redmi `2/2`、完整 Redmi instrumentation `145/145`、JVM `424/424`、Lint、Debug 与 AndroidTest 构建通过。该样本是明确的应用取消，不是自然 Android 回收，也不支持引入 Foreground Service。
- 测试后确认测试包不存在，重新安装并启动正式 Debug APK；`MainActivity` 前台 PID `518`，crash buffer 为空，正式数据库为 `schema=28 / providers=1 / agent_profiles=1 / workflows=0 / scheduled_tasks=0 / workflow_runs=0 / agent_tool_results=0`。

## 第 62 阶段实现与验证边界

- `ScheduledWorkflowWorker` 在 Android 12+ 的取消收敛路径读取 WorkManager `getStopReason()`；`ScheduledWorkerStopReasonPolicy` 将 JobScheduler 停止码归一为稳定 `code/name/message`，只保留系统分类，不记录请求正文或设备隐私。
- `WorkflowRunEntity` 与 `ScheduledTaskEntity` 在 Room v28 新增可空 `workerStopReasonCode/workerStopReasonName`；`MIGRATION_27_28` 只补空列。`RoomWorkflowRepository.settleScheduledWorkflowRun()` 在同一事务中把相同原因写入两张表，已终态和 `STOP_REQUESTED` 栅栏仍优先保留既有事实。
- Workflow 详情和调度实例展示标准化系统停止原因；缺少可靠原因时继续显示通用停止文案，不将未知码升级为具体故障推断。
- `ScheduledWorkerStopReasonPolicyTest`、Orchestrator 取消透传测试、Room 迁移/双表持久化 Redmi 测试已加入。JVM `424/424`、完整 Redmi instrumentation `143/143`、Lint、Debug 与 AndroidTest 构建通过；测试包已卸载，正式应用前台运行，正式数据库 `schema=28` 且 Provider/Profile 保留、Workflow 数据为零。

## 第 61 阶段验证边界

- 熄屏前入队 Probe 在 `0.275s` 内结束，原 instrumentation PID 消失；Redmi 保持 `mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`，没有强制 Doze 或内存压力。
- JobScheduler 在计划时间后延迟 `159.479s` 冷启动 PID `26797`，生产 `ScheduledWorkflowWorker` 使用同一持久化 WorkRequest/ScheduledTask/WorkflowRun，在熄屏状态下执行 `244.236s`；8 个 WorkflowStep/AgentRun 全部 `COMPLETED`，32/32 ToolResult 和 `tool.verify` 全部 `PASSED`，没有复制 Run 或 `llm.request.failed`。
- 每个 AgentRun 有 11 条预算快照，`consumedMs` 最大值 `18.283s–44.856s`，8 个 Run 的回退次数均为 0；Workflow Step 耗时约 `21.710s–48.743s`。`ApplicationExitInfo` 为 `supported=true / exits=16 / lowMemory=0 / fallbackSigkillCandidates=0`。
- Stage 61 数据、临时 Probe、测试包和 SQLite 备份已定向清理；正式 Provider/Profile 保留，设备已唤醒并重新启动正式 Debug APK。

## 第 60 阶段验证边界

- 一次性 Probe 只创建 8 步 Workflow、持久化 WorkRequest ID 并入队，在 `0.255s` 后结束；instrumentation 结束后原进程消失，JobScheduler 冷启动新 PID `25825`，生产 `ScheduledWorkflowWorker` 在没有前台 Activity 或测试轮询协程驻留的情况下执行完整任务。
- WorkRequest `25c90656-4eef-4936-b1fc-3a28ba0310fd`、ScheduledTask `scheduled-task-f598a2b8-d851-437f-af3f-1f6cbe52fb7a` 和 WorkflowRun `workflow-run-01d2a483-f452-4073-b3c3-e67b19f0210a` 保持单一关联；生产耗时 `204.977s`，8 个 WorkflowStep/AgentRun 全部 `COMPLETED`，32/32 ToolResult 为 `success=true / PASSED`，没有复制 Run 或模型失败。
- 每个 AgentRun 有 11 条预算快照，`consumedMs` 从 0 单调增长，最大值 `18.431s–26.779s`，8 个 Run 的回退次数均为 0。32 条 `tool.verify` 与 32 个 ToolResult 一一对应，`llmFailures=0`。
- `ApplicationExitInfo` 为 `supported=true / exits=16 / lowMemory=0 / fallbackSigkillCandidates=0`；新增退出来自 instrumentation 入队/取证，后台 Worker 本身未被系统回收。临时 Probe、测试包、两次 Stage 60 样本和备份均已清理，正式 Provider/Profile 保留。

## 第 59 阶段验证边界

- 在 Redmi `wsvwypiz7xwslvl7` 使用正式 Provider/Profile、生产 `RoomWorkflowRepository`、`ScheduledWorkflowWorker` 和 WorkManager 创建 8 步复合 SAFE Workflow；一次性任务按产品规则延迟 1 分钟后启动，WorkManager 实际执行耗时 `229.416s`。
- Task、WorkflowRun、8 个 WorkflowStep 和 8 个 AgentRun 全部 `COMPLETED`，单一 ScheduledTask 没有复制 WorkflowRun；每步依次执行 3 个只读应用工具，共 24 个 ToolResult 全部 `success=true / verificationStatus=PASSED`，RunEvent 含 72 条预算更新和 24 条 `tool.verify`，没有 `llm.request.failed`。
- `ApplicationExitInfo` 复验为 `supported=true / exits=14 / lowMemory=0 / fallbackSigkillCandidates=0`，历史退出均为 instrumentation 完成或安装停止，不构成 Android 自主 LMK 证据。临时 Probe、测试包、Stage 59 Workflow/Task/Run/Step/Tool 数据均已定向清理；正式 Provider/Profile 保留，正式 Debug APK 已重新启动到 Redmi。
- 本阶段只把预算更新事件数量作为新增证据；临时日志探针未正确解析 `consumedMs` 数值，因此不把本轮包装成新的数值单调结论，继续沿用生产 Runtime 的预算审计和既有门禁。

## 第 58 阶段验证边界

- 生产 `ScheduledWorkflowWorker` 在 Redmi 真机上被真实触发并执行 8 步 SAFE Workflow；两次样本约 4 至 6 秒失败，Task/Workflow/Agent 均进入失败终态，只有 1 个 Agent Run，后续步骤取消，预算事件单调且没有复制 Run。
- 两次失败均记录为 `llmFailureKinds=TLS`、`agentErrors=connection closed`。Redmi 自带 `curl` 直接访问同一 HTTPS 端点，在 TCP 建连后于 TLS ClientHello 阶段得到 `BoringSSL SSL_ERROR_SYSCALL`；Mac 侧同端点可完成 TLS 并返回 HTTP 401。故障边界暂指向设备网络路径或上游 TLS 兼容，不修改 OkHttp 安全策略、不关闭证书校验、不把失败写成模型长任务证据。
- 临时 Probe 含本机兜底凭据，已从源码删除；早期两次 TLS Probe 创建的 Provider/Profile/Workflow/ScheduledTask/Run/会话数据已定向清理，正式 Debug APK 已恢复到 Redmi。网络恢复后的成功长任务复验见下方，设备工具仍不进入 Workflow/后台自动化，Foreground Service 继续证据驱动后置。

网络恢复后的同阶段复验：生产 WorkManager 在 `92.667s` 内完成同一 8 步 SAFE Workflow；8 个 Agent Run 和 8 个步骤均为 `COMPLETED`，工具依次覆盖 `app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`memory.search` 后再次读取时间与会话列表，8/8 ToolResult 为 `success=true / PASSED`。每个 Agent Run 的预算快照单调，`llmFailureKinds=[]`，单 ScheduledTask 只关联一个 Workflow Run；历史退出的 `lowMemoryExits=0`，不能宣称自然 LMK。Probe 和所有取证数据已清理，当前耗时仍不支持预先引入 Foreground Service。

## 模块职责

| 模块 | 关键文件 | 职责 |
|---|---|---|
| App/UI | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | 各 feature 的 Compose 组合与全局 overlay 挂载、Android 文件选择器、跨页面导航、备份恢复、全局通知和来源导航 effect。 |
| Network settings | `app/src/main/java/com/longdev/xiaoling/ui/networksettings/` | User-Agent 的窄 UI state/actions、五行编辑器、复制/清空/恢复默认和页面内剪贴板适配。 |
| App navigation | `app/src/main/java/com/longdev/xiaoling/ui/navigation/` | 类型化 Tab/设置目标、知识文档与外部事件路由、返回优先级、Compose 状态保存 adapter 和底栏渲染。 |
| Conversation UI | `app/src/main/java/com/longdev/xiaoling/ui/conversation/` | 会话页窄状态投影、滚动跟尾、Provider/模型选择、消息与知识引用组合、附件/SharedDraft、Agent Run/审批、输入区及单一 actions interface。 |
| Prompt settings UI | `app/src/main/java/com/longdev/xiaoling/ui/promptsettings/` | 三类设备级提示词编辑、互斥最终预览和九项窄 actions interface；只接收 `PromptSettings`，不依赖整份应用状态或具体 ViewModel。 |
| Process exit UI | `app/src/main/java/com/longdev/xiaoling/ui/processexit/` | 独立退出账本的只读状态、六类证据呈现、稳定 Room 同源 key、加载/失败/空态和单项刷新 actions interface；平台采集、Room 读取和导航仍在宿主边界。 |
| Workflow UI | `app/src/main/java/com/longdev/xiaoling/ui/workflow/` | Workflow 管理状态投影、操作资格、局部编辑/调度状态、动作 interface、重试步骤复用确认，以及定义、Run 和调度账本的 Compose 呈现。 |
| Agent task center UI | `app/src/main/java/com/longdev/xiaoling/ui/agenttask/` | Agent Run 历史投影、稳定 selected/retrying 绑定、局部筛选、指标、卡片/详情、Ledger 一致性、恢复处置、步骤/审批/事件呈现、重试证据确认及对应 actions interface。 |
| Memory management UI | `app/src/main/java/com/longdev/xiaoling/ui/memory/` | 正式记忆与可操作候选投影、稳定 selected/mutating 绑定、首刷、搜索/筛选、来源与召回审计、生命周期操作、编辑/删除对话框、删除撤销呈现及窄 actions interface。 |
| Provider management UI | `app/src/main/java/com/longdev/xiaoling/ui/provider/` | Provider 列表、编辑草稿、稳定 selected/syncing/result 投影、扫码/剪切板/Base64 导入辅助、模型勾选和窄 actions interface。 |
| Agent Profile management UI | `app/src/main/java/com/longdev/xiaoling/ui/agentprofile/` | Profile 列表、稳定 selected/mutating 及 Provider/模型有效性投影、编辑草稿、工具/Skill 白名单依赖和三项窄 actions interface。 |
| Agent Skill management UI | `app/src/main/java/com/longdev/xiaoling/ui/agentskill/` | Skill 稳定操作态、工具依赖可用性、最近 Run 选择审计、局部首刷/展开状态、五项窄 actions interface 和本地 Skill 删除确认。 |
| Shared Agent Run UI | `app/src/main/java/com/longdev/xiaoling/ui/AgentRunUiPrimitives.kt` | 对话时间线与任务中心共用的 Run 状态徽标、Step 行和中文状态文案，保证同一运行事实在不同入口保持一致。 |
| ViewModel | `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | 维护页面状态、模型同步结果、普通对话与会话选择事件投影、Agent Profile 选择/保存/删除和前台 Workflow 编排；继续提供任务中心、长期记忆、Provider、Agent Profile 与 Agent Skill 管理所需的 Room/协调器副作用。上下文、网络发送、会话纯状态投影、保存/加载/选择协调、Agent 会话运行态、审批、关联重试、候选记忆和 Provider 模型同步业务编排已迁入独立组件。 |
| Conversation context | `app/src/main/java/com/longdev/xiaoling/ui/ConversationRequestContextPreparer.kt` | 普通聊天上下文资格、知识生命周期核验、最近窗口、增量摘要、可信 Agent 历史与 Responses 用户附件请求投影。 |
| Conversation send | `app/src/main/java/com/longdev/xiaoling/ui/ConversationSendCoordinator.kt` | 普通聊天发送前持久化、上下文准备、网络请求、流式增量和成功/取消/失败事件的稳定编排。 |
| Conversation session | `app/src/main/java/com/longdev/xiaoling/ui/ConversationSessionPolicy.kt` | 会话标题、空占位折叠、创建/更新时间、摘要元数据继承、blank ID、非当前更新隔离，以及新建/删除后的纯选择计划与即时状态投影。 |
| Conversation persistence | `app/src/main/java/com/longdev/xiaoling/ui/ConversationPersistenceCoordinator.kt` | latest-save Job、Room 单写者、发送前旧保存等待，以及显式删除意图的代次确认和失败回滚。 |
| Conversation load | `app/src/main/java/com/longdev/xiaoling/ui/ConversationLoadCoordinator.kt` | latest-load Job、选择代次、可重入 Loading 与迟到 Loaded/Failed 隔离。 |
| Conversation load projection | `app/src/main/java/com/longdev/xiaoling/ui/ConversationLoadProjectionPolicy.kt` | Loading/Loaded/Failed 的纯 UI 状态投影、非当前附件轻量化和当前完整消息原子切换。 |
| Conversation selection | `app/src/main/java/com/longdev/xiaoling/ui/ConversationSelectionCoordinator.kt` | 新建/选择/删除的副作用顺序、版本化删除失败回滚和稳定选择事件发布。 |
| Memory candidate | `app/src/main/java/com/longdev/xiaoling/ui/AgentMemoryCandidateCoordinator.kt` | 候选记忆有界读取、成功回合来源身份、采集与接受/拒绝 typed outcome，以及按候选 ID 隔离的并发 claim 和取消清理。 |
| Provider model sync | `app/src/main/java/com/longdev/xiaoling/ui/ProviderModelSyncCoordinator.kt` | `/models` 请求规范化、模型去重/回退、批量顺序、失败分型，以及完整 Provider 快照的互斥提交。 |
| Network | `app/src/main/java/com/longdev/xiaoling/network/LlmProviderAdapter.kt`、`OpenAiCompatibleClient.kt` | Adapter 负责 OpenAI-compatible URL、payload 与响应协议；Client 负责 HTTP、鉴权 Header、取消、计时和 SSE 读取。 |
| URL | `app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | 将用户输入的 API 根地址归一化成 `/models`、`/chat/completions` 和 `/responses` 请求地址。 |
| Data | `app/src/main/java/com/longdev/xiaoling/data/` | Room 数据库、Provider、AgentProfile、Conversation、Message/MessagePart、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory 和 AgentMemoryCandidate 表。 |
| Storage | `app/src/main/java/com/longdev/xiaoling/storage/` | Conversation/Message Repository、Agent Profile Store、旧 SharedPreferences 一次性迁移、UI 偏好和 API Key 加密。 |
| Agent | `app/src/main/java/com/longdev/xiaoling/agent/` | Agent Profile 策略、最小 Agent Runtime、Run Ledger interface、真实低风险 Tool Registry、交互式审批 gate 和可审计运行链路。 |
| Device | `app/src/main/java/com/longdev/xiaoling/device/` | Accessibility 观察与有限动作、授权/连接健康检查、有界脱敏 snapshot、短生命周期节点引用、隐私/应用白名单、动作后验证和前台直接 Agent 门禁。 |
| System | `app/src/main/java/com/longdev/xiaoling/system/` | ApplicationExitInfo 旁路观察、LMK/受控退出分类、30 条有界 Room 账本和取消安全的启动/Worker 采集。 |
| Automation | `app/src/main/java/com/longdev/xiaoling/automation/`、`storage/RoomWorkflowRepository.kt` | Workflow/ScheduledTask 状态、周期规则、Room Ledger、前台手动触发、WorkManager 非精确调度、后台执行、进程内 Worker 所有权、启动恢复候选隔离、`STOP_REQUESTED` 持久化停止与结果通知。 |
| Prompt | `app/src/main/java/com/longdev/xiaoling/prompt/` | 三类可配置提示词的默认模板、最终 system prompt 组合和不可覆盖事实边界；设置 UI 由 `ui/promptsettings` 消费该领域模型。 |
| Markdown | `app/src/main/java/com/longdev/xiaoling/ui/MarkdownTableParser.kt` | 补充表格边框渲染，并配合 Markdown renderer 处理常见模型输出。 |

## 当前架构边界

当前工程仍是单一 Android `app` 模块，业务状态和多项流程仍集中在 `XiaoLingViewModel`，但应用导航状态/返回语义、Workflow 管理、Agent 任务中心、长期记忆、Provider、Agent Profile、Agent Skill、会话主界面、提示词设置、进程退出观察和网络请求设置的呈现与动作面、四组功能对话框，以及普通聊天上下文准备、网络发送状态机、会话纯状态/选择投影、保存协调、加载协调、加载 UI 投影、选择/删除副作用顺序、Agent Run 关联重试、会话级 Agent 运行态、当前进程审批 waiter、恢复后审批协调与候选记忆 Store 编排已经迁出：

- Provider 管理页面已由 `ProviderManagementProjection`、`ProviderManagementActions` 和专用 Compose page 隔离宿主；ViewModel 只实现原有持久化、同步和结果副作用。Compose 发送/选择事件投影、流式节流和错误提示仍由 ViewModel 维护；Provider 模型同步的网络、合并、批量顺序和提交互斥已由 `ProviderModelSyncCoordinator` 编排。候选记忆的有界读取、稳定来源采集和接受/拒绝由 `AgentMemoryCandidateCoordinator` 编排，ViewModel 只投影事件并管理页面 Job。会话级 Run/Approval 运行态由 `AgentConversationRuntimeStateStore` 统一保存和投影，当前进程审批 ticket/claim 由 `AgentApprovalDecisionCoordinator` 管理，进程恢复后的链尾审批重新核验、附件准备、互斥决定与强类型结果由 `RecoveredAgentApprovalCoordinator` 管理，拒绝通过 `RoomAgentRunRepository.rejectRecoveredApproval()` 原子收敛。上下文筛选、摘要窗口和请求消息构造由 `ConversationRequestContextPreparer` 统一负责，Room 持久化→上下文准备→网络→终态事件由 `ConversationSendCoordinator` 统一负责，标题/空占位/时间戳/摘要元数据/非当前更新及新建/删除选择计划由 `ConversationSessionPolicy` 统一投影，latest-save/单写者/显式删除意图由 `ConversationPersistenceCoordinator` 协调，latest-load/选择代次与 Loading/Loaded/Failed UI 投影分别由 `ConversationLoadCoordinator` 和 `ConversationLoadProjectionPolicy` 负责，新建/选择/删除顺序与失败回滚由 `ConversationSelectionCoordinator` 组合，失败 Run 的关联重试由 `AgentRunRetryCoordinator` 负责。
- `LlmProviderAdapter` 已成为模型协议边界，当前 `OpenAiCompatibleAdapter` 统一处理模型列表、Chat Completions、Responses API 请求与响应映射；`OpenAiCompatibleClient` 只保留 HTTP 传输、取消、计时和 SSE 读取。普通聊天和 Agent 仍复用同一 Client 与 Adapter 实例链路。
- Provider、Agent Profile、会话、消息、最小 Agent Run、审批请求、独立 ToolCall/ToolResult、长期记忆、声明式 Skill 和 Workflow Ledger 已经迁入 Room；旧 SharedPreferences 只在首次升级时迁入一次。
- Room compiler 已从 KAPT 切换到 KSP，`app/schemas/` 保存历史 v4、v6-v32 Schema；迁移测试覆盖 v4→v32、各关键增量迁移和全新 v32 建库。
- UI 以聊天消息为中心，已能在 `/agent` 消息下方显示当前 Run 时间线和最小审批卡片；设置页 Agent 任务中心可以筛选任务、按调用查看 Ledger-first 四阶段工具明细、完整结果/步骤/审批/事件和双源一致性告警，并对可重试终态创建关联的新 Run。工作流页支持 1 至 8 步创建/编辑/排序、一次/每日/每周计划、定义与运行快照展开、来源 Run 标识和新 Run 重试。
- Workflow 页面通过专用投影一次性关联定义、Run、ScheduledTask 与周期规则，并通过 `WorkflowManagementActions` 调用 ViewModel；Compose 不再读取整份 `XiaoLingUiState`、具体 ViewModel 或 `WorkflowStepSnapshotCodec`。
- Agent 任务中心通过 `AgentTaskCenterProjection` 和 `AgentTaskCenterActions` 隔离宿主；模块拥有筛选、指标、卡片/详情、恢复诊断和重试证据确认，应用壳只负责全局挂载 dialog host 与跨会话导航。
- 长期记忆管理通过 `MemoryManagementProjection` 和 `MemoryManagementActions` 隔离宿主；模块呈现列表、候选、搜索、筛选、来源/召回审计、生命周期操作、编辑/删除弹窗和撤销，并持有空列表首刷。来源会话/Run 导航 effect 仍保留在宿主。
- Provider 管理通过 `ProviderManagementProjection` 和 `ProviderManagementActions` 隔离宿主；页面自己呈现列表、编辑草稿、扫码/剪切板/Base64 辅助、模型选择和网络结果。编辑器返回优先级、底栏显隐与聊天 Provider 下拉仍保留在宿主。
- Agent Profile 管理通过 `AgentProfileManagementProjection`、`AgentProfileManagementActions` 和专用 Compose page 隔离宿主；页面自己呈现 Profile 列表、Provider/模型状态、编辑草稿、Chat/Responses、记忆开关以及工具/Skill 双向依赖。ViewModel 仍在保存入口重新校验 Provider、模型、注册工具和 Skill 依赖，防止绕过 UI 的旧草稿扩大能力；设置返回、底栏显隐与全局结果提示继续由宿主统一处理。
- Agent Skill 管理通过 `AgentSkillManagementProjection`、`AgentSkillManagementActions` 和专用 Compose page 隔离宿主；模块呈现列表、工具依赖可用性、最近 Run 选择审计、本地删除确认并持有首刷/展开状态。Android 文件选择器和真实持久化副作用继续由宿主与 ViewModel 负责。
- 会话主界面通过 `ConversationProjection`、`ConversationActions` 和专用 Compose page 隔离宿主；页面自己持有滚动状态并组合消息、知识引用、附件、SharedDraft、Run/审批和输入区。Android picker、URI 读取与知识库跨页导航继续由应用壳执行。
- 提示词设置通过 `PromptSettings`、`PromptSettingsActions` 和专用 Compose page 隔离宿主；页面持有三类互斥最终预览，ViewModel 继续负责输入即保存与逐项恢复默认，`PromptPolicy` 的不可覆盖安全尾部不变。
- 进程退出观察通过 `ProcessExitObservationUiState`、`ProcessExitObservationActions` 和专用 Compose page 隔离宿主；页面只呈现独立退出账本，宿主继续保持进入前只读刷新，ViewModel 继续持有 `latest()` IO Job，平台采集不会由查看页面触发。
- 网络请求设置通过 `NetworkRequestSettingsUiState`、`NetworkRequestSettingsActions` 和专用 Compose page 隔离宿主；页面持有 User-Agent 编辑、复制、清空、恢复默认和剪贴板适配，ViewModel 继续负责规范化与即时持久化。
- 设置根页通过 `SettingsRootProjection`、`SettingsRootUiState`、`SettingsRootActions` 和专用 Compose page 隔离宿主；页面只呈现主题、14 项入口和业务摘要，pane 分派、Android launcher、导航及真实副作用继续由 `SettingsPage` composition root 负责。
- `WAITING_APPROVAL` Run 可从任意已验证工具前缀恢复链尾审批；所有 ToolResult 与 `PASSED` 验证均已落库时，可补齐最后验证 Step 并用本地可信总结完成原 Run；严格持久化失败 ToolResult 或 typed 失败验证可在不重放工具的前提下原子结算对应 Step/Run 为 `FAILED`。提交状态未知、成功结果尚无 typed 验证结论、event-only、预算缺失和其他证据漂移仍保持 fail-closed，旧模型协程始终不恢复。

当前已经建立最小 domain、data、runtime 和 tool 边界。后续功能不应继续堆进 `sendMessage()`；第 66 至 73 阶段已迁出普通聊天上下文准备、网络发送状态机、会话纯状态/选择投影、保存协调、加载协调、加载 UI 投影与选择/删除副作用顺序，后续横向工程又迁出 Agent Run 关联重试、会话级 Run/Approval 运行态、当前进程审批 waiter、恢复后审批、候选记忆、Provider 模型同步协调、应用导航宿主、十一个业务页面和四组功能对话框。`SettingsPage` 仍是承接导航、Android launcher 和跨模块适配的 composition root；宿主当前 `817` 行并达到停止条件，不再机械搬运 composition root。通用执行恢复闭环审计和 answerability Shadow 匿名跨进程持久化均已完成，下一主线是新账本真实样本与离线评测契约。

## 对话请求

用户在对话页输入消息并发送后：

1. 校验 `Base URL`、已启用模型和消息内容。
2. 从设备级网络偏好读取 `User-Agent`；默认模拟指定 Codex Desktop 版本。设置根页只展示统一的“网络请求”入口卡片，独立子页提供至少 5 行的编辑区以及复制、清空和恢复默认操作。模型列表、Chat Completions、Responses 和后台 Agent 共用同一 Header 构造入口。
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

- 候选列表、成功回合来源构造和接受/拒绝操作统一经过 `AgentMemoryCandidateCoordinator`；同一候选 ID 不能并发决定，取消或失败后可重试，不同候选仍可并行。关闭开关会取消旧列表 Job，迟到 Room 结果不能重新填充界面。
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

- Provider、会话、消息、AgentRun、AgentStep、ApprovalRequest、RunEvent、AgentNote、AgentMemory、AgentSkill、AgentProfile、ToolCall/ToolResult、Workflow、WorkflowStepDefinition、WorkflowRun、WorkflowStep、WorkflowSchedule、ScheduledTask、独立 ProcessExitObservation、KnowledgeDocument/Chunk 和检索审计保存在 Room 数据库 `xiaoling.db`。
- 数据库当前版本为 v32，启用 `exportSchema`；`XiaoLingDatabaseMigrationInstrumentedTest` 覆盖正式 v4→v32 的关键增量和全新 v32 建库。v25→v26 只创建空知识库表；v26→v27 为 ToolResult 与 MessagePart 增加默认 `[]` 的知识引用列；v28→v29 只创建空进程退出观察表；v29→v30 增加按 Provider/模型隔离的 Embedding 索引与检索身份；v30→v31 增加 top1/top2/margin/候选数 shadow 字段；v31→v32 增加候选均值、总体标准差和 top1 z-score，所有迁移都不从旧正文、历史 JSON、当前向量或退出时间邻近关系猜造事实。
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
- 新工具不会自动加入旧 Profile/Skill；缺少 Profile 审计的历史 Run 使用知识工具上线前的固定工具集合，审批恢复后的后续规划也不能发现 `knowledge.search`。Embedding v1 已接入；规模化 ANN、后台增量索引和其他后置能力仍不扩大旧 Run 工具边界。

## 设备 Agent 观察与有限动作层

- `XiaoLingAccessibilityService` 声明 `canRetrieveWindowContent=true`，显式关闭坐标手势和截图能力，并设置 `isAccessibilityTool=false`；服务不导出，只能由系统通过 `BIND_ACCESSIBILITY_SERVICE` 绑定。事件只推进窗口 generation；执行层只使用 `performGlobalAction` 和节点 `ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_*`。
- 设置页「设备 Agent」提供默认关闭的独立开关、系统 Accessibility 设置入口、四态健康检查和只读快照预览。关闭开关会立即清除 ref；应用开关和系统授权必须同时有效。
- `DeviceSnapshotPolicy` 把原始节点树收紧到最多 200 个可见有效节点和 4,000 个字符，文本预算不切断 UTF-16 代理对。只有当前启用、可操作且未脱敏的节点获得 ref；禁用节点、只读文本和敏感节点没有 ref。
- ref 由 `DeviceNodeReferenceStore` 绑定 snapshot ID、窗口 generation、节点路径、指纹和 30 秒到期时间。新快照替换旧快照；页面变化、过期、引用不存在、开关关闭、捕获失败或隐私拦截都明确失效，不存在坐标回退。
- 密码/密码提示、验证码、API Key、Bearer/Access Token、带空格或连字符的手机号/银行卡、身份证和邮箱节点会清空正文、动作与 ref。支付/收银台/高敏身份验证窗口以及已知密码管理器、Authenticator、钱包/银行类包名整窗拒绝，不把包名或节点正文写入工具结果。
- 前台直接 `/agent` 中，`device.snapshot` 是 SAFE、非后台工具；`device.open_app / tap_ref / type_text` 要求逐步审批，`device.back / home / swipe` 为 SAFE。`open_app` 只接受 manifest queries 与业务策略共同限定的小灵、系统计算器、时钟、系统设置和 Google 天气；`type_text` 最多 500 字符，并在 Tool 参数审计前拒绝密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱。Workflow 另按下条精确白名单逐项开放，不能从直接 `/agent` 风险标记推导权限。
- 节点动作执行前再次核对 snapshot/ref/generation/path/fingerprint/action；动作后等待窗口短暂稳定并重新 capture。首次启动系统权限页可能短暂没有 `rootInActiveWindow`，只对 `NO_ACTIVE_WINDOW / WINDOW_CHANGED` 做最多 6 次、每次 100 ms 的有界重试；隐私拒绝、授权失效和服务断连不重试。`open_app` 核对前台包名或显式 AOSP/Google 等价应用族，启动时仍优先请求包，只在无入口时尝试同族白名单包；`home` 核对桌面包名；`type_text` 只按动作前原 `nodePath` 在新 references 中定位目标并读取该节点 `text`，其他节点的同文、description 或 hint 不能替代精确回读；其他动作要求可观察的窗口 generation 变化，未得到证据时返回 `verified=false`。
- Registry 在前台直接 `/agent`、独立开关开启且 Profile/Skill 允许时暴露完整限定设备工具；前台手动 Workflow 当前精确暴露 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`，并会按步骤意图进一步收窄：“返回小灵 / 回到小灵”在新鲜 snapshot 后只开放 `device.back`。打开应用、点击与文本输入必须经过同 Run 已验证观察、Room 独立审批、Accessibility 安全浮层和动作后 Executor/typed 验证；`open_app` 不使用节点 ref，但必须保持动作前 generation，且请求包或显式同族包经过三层白名单、完成门禁和答案级重建绑定。`back / home / swipe` 为零审批 SAFE 动作，但同样要求当前 snapshot、30 秒 TTL、generation 与完整后置验证，`home` 还要求动态 launcher 匹配，`swipe` 还要求实时 ref、匿名 viewport 和同窗方向证据。文本输入还要求当前 ref 可编辑且未脱敏、敏感文本预审计、最小指纹授权和原 `nodePath` 精确回读；原文只驻留当前 ToolCall，Workflow 与直接 `/agent` 的持久路径统一只使用指纹和长度。后台/定时 Workflow 和关闭状态仍在规划器工具面与 Executor 两层拒绝。`device-observation` 保持只读，`device-control` 才引用动作工具；既有 Profile/Skill 不自动扩权。
- `app/src/debug` 提供仅 Debug 包可用的快照、动作和真实 Agent 诊断广播与隐私探针；Release manifest 不包含这些入口。该 Redmi ROM 在 instrumentation 生命周期后会清空无障碍授权，因此完整 instrumentation 结束后恢复系统服务，再用 Debug-only 入口完成真实服务与动作 E2E。
- Redmi 首批验收覆盖计算器 `open_app + tap_ref`、设置 `swipe + tap_ref + type_text`、敏感输入拒绝、`back / home`、时钟启动和 Google 天气打开/观察；真实 `gpt-5.5 + Responses` 前台直接 `/agent` Run 完成 `device.open_app` 的模型规划、应用侧审批、执行、后置验证、Tool Ledger 和最终总结。第 113 阶段完成前台 Workflow `snapshot -> tap_ref` 的真实 Room/overlay/Tool Ledger 闭环，第 117 阶段完成 `snapshot -> type_text` 的 `APPROVED / PASSED / VERIFIED / exactReadBack=true` 闭环，第 119/120 阶段依次完成 `snapshot -> back / home` 的 `SAFE / approvals=0 / PASSED / VERIFIED` 闭环，第 121 阶段完成 `snapshot -> open_app(com.android.calculator2)` 的逐包 `APPROVED / PASSED / VERIFIED` 闭环，第 136 阶段用同一生产链验证 `open_app(com.google.android.apps.weather)`；第 145 阶段真实三步 Run 使用 `gpt-5.6-luna` 依次完成 `app.current_time`、`open_app(com.android.deskclock -> com.google.android.deskclock)` 和 `device.back`，最终 `goalVerificationDecision=VERIFIED` 并返回小灵。当前仍不支持坐标点击、截图、任意 App 或后台设备自动化。

## 日志

- debug 包默认开启 HTTP 调试日志：`BuildConfig.XIAOLING_HTTP_LOGS_ENABLED = true`。
- release 包默认关闭 HTTP 调试日志。
- 日志会对 Authorization 和包含 key 的 Header 做脱敏。
- 网络层把连接建立失败，以及带明确 EOF、connection reset、broken pipe 或 stream reset 标记的响应中断归类为 `CONNECTION`；其他 `ProtocolException` 归类为 `RESPONSE`，无法识别的 I/O 仍为 `UNKNOWN`，避免扩大后续自动重试范围。

## 当前限制

- 暂不提供云同步和账号体系。
- 尚未内置 MCP 和外部远程工具。动作型手机自动化已向前台直接 `/agent` 交付限定范围的 `device.open_app / back / home / tap_ref / type_text / swipe`，仅承诺小灵、系统计算器、时钟、设置、Google 天气和桌面的首批 Redmi 验收，不承诺任意 App；前台手动 Workflow 当前交付同 Run `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe` 及答案级动作证据 UI。文本输入具备专属策略、当前 ref 节点证据、绑定原路径的精确回读和跨入口无原文持久化；`open_app` 逐包审批并绑定后置包名，`back / home / swipe` 为零审批 SAFE 动作，但都要求当前观察和完整后置验证，`home` 还要求动态 launcher 匹配，`swipe` 还要求同窗匿名锚点方向证据。全部后台自动化仍不进入 Workflow 生产。
- 暂不提供 Provider 模板市场。
- 更换 `applicationId` 后，旧版本本地数据不会自动迁移。
- Responses Adapter 已支持文本、用户图片/文档、`function_call / function_call_output` typed Items 和可选 Reasoning summary；Room/Compose 已完成 Text/Reasoning/Image/Document/Tool parts 垂直切片，DOCX/PPTX/XLSX 已完成结构校验与真实模型直传。当前 Agent Runtime 仍使用提示词 JSON 做最多 4 步的顺序工具规划，尚未直接使用上游原生函数调用循环；第 75 阶段起附件已进入前台 `/agent` 的 Responses 规划请求，但总结、可信执行事实和 Agent 输出继续隔离，持久化重复/混合附件直接拒绝。超过 8 MB 或跨文档资料已经具备严格文本全文、分块、FTS/中文兜底、管理 UI、`knowledge.search`、结构化引用、答案级引用呈现和模型上下文失效过滤；Embedding 已完成有限规模 cosine+RRF、显式重建和固定语料质量门禁，剩余差距是具备 Embedding 模型的真实 Provider 兼容验收、ANN 与更大真实资料集的规模化召回/性能验证。
- `/agent` 目前接入第一批应用内工具、知识检索和限定设备工具；任务中心已支持失败终态安全重新运行。进程重建后的恢复边界策略已经落地：链尾待审批 Run 可从任意已验证前缀原地恢复；`notes.create / memory.remember` 的完整已提交证据可进入受限只读验证；所有工具结果与 `PASSED` 验证完整落库后可恢复本地收尾。两类控制面恢复现已具备唯一 marker、typed Step 身份、事务收敛、重新读取复核和并发幂等。旧 typed 验证事件缺少 `toolCallId` 时固定判为关联未知，不按工具名或顺序猜配。提交状态未知、验证事实仍不完整和旧模型协程继续安全重新运行或 fail-closed。
- 当前模型请求审计不保存 Prompt 正文，也不估算价格；只保存最终请求体字节、计时和上游明确返回的 Token usage。流式普通对话仍沿用消息级首 Token 指标，Agent 非流式请求使用 TTFB，两者不混算。
- 启动协调器已保留 `APPROVAL_WAIT` Run 并把待审批请求重建到当前会话；发起 `/agent` 后会先持久化用户消息，旧数据缺少消息锚点时再依据 Run 的 `userMessageId / goal / createdAt` 补回。审批恢复会从 Ledger/Event 重建前序可信工具、调用额度和循环指纹，批准后只执行链尾 ToolCall；执行/验证中 Agent Run 默认与活动 Step 一致安全收敛，只有两个白名单写工具的只读验证或全部工具已经 `PASSED` 的控制面收尾可以完成原 Run。两类例外写 marker 后都会重新读取 Room，marker/状态和启动关闭事务边界已统一，全部验证后的总结尾部可以重复或并发重入但不会复制 Step/Event。多步骤 Workflow、步骤快照、安全重试、真实后台执行和审批后继续下一步骤均已完成真机验收；后台通用执行栈断点续跑仍不开放，Foreground Service 暂无真实耗时依据支持引入。
- 恢复测试覆盖首步与第二次审批同 Run 完成、前序工具不重放、最终可信上下文保留完整工具链、工具调用预算和累计时间预算均不因重启清零、两个白名单写工具的已提交结果不调用写入方法而完成验证恢复、`tool.verify` 落库后与验证 Step 完成后两个终止点不重复 ToolResult/验证、恢复工具失败写入原 Run `FAILED`、旧验证缺少 ToolCall ID 时拒绝顺序猜配、Workflow 步骤落库后的进程终止与下一步骤不重复启动、Worker 重入按 ID 定向关闭关联 Agent/Workflow/Task 且不影响无关 Agent、启动恢复快照期间新 Worker 等待、旧链收敛而当前进程链保持并完成且不新增 Run、用户停止定向收敛目标链、迟到 Step/Event/Approval 不污染终态、其他执行/验证中 Run 与 Step 一致取消、稳定重试证据分类、结构化恢复处置、确认前二次评估，以及失败后安全重试必须二次确认。Room instrumentation 覆盖关闭并重开磁盘数据库后保留第二次审批与已验证前缀、Workflow 完成前缀和关联新 Run 重试；该阶段门禁为 472 条 JVM 与仅 Redmi 执行的 153 条 instrumentation；进程退出观察的受控记录不代表自然 LMK，只读诊断页也不会触发新采集。

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
- 第 57 阶段完成取消时的预算写回收敛：Runtime 在新 Run、审批恢复和受限恢复的取消出口统一使用 `NonCancellable`，先持久化最新单调预算，再取消活动 Step、追加 `run.cancelled` 并冻结 Run。模型或工具 `finally` 已累计的执行时间不会因后台停止丢失；确定性测试验证取消前 `37ms` 快照可被 `AgentExecutionBudgetEvidencePolicy` 读取，预算事件先于取消终态。完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation（0 跳过、0 失败）。
- 第 59 阶段已取得约 229 秒复合只读后台成功链；下一恢复证据切片继续观察更长真实任务中的预算快照与系统回收组合行为、以及 Android 自主 LMK，仍不恢复无法证明的旧执行栈。

未来架构与迁移顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
