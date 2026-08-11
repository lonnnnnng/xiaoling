# 答案可回答性 Shadow 绑定、持久化与离线评测契约

## 第 251 阶段本地笔记导入知识库边界

`notes.search / notes.get` 的搜索词、候选集、原始笔记正文、临时冻结状态、Tool Message、审批、Run/Ledger 和真机验收数据都只属于当前前台 Agent 执行，不直接进入知识候选、Judge、Shadow measurement 或匿名账本。只有用户批准并由当前 Knowledge Store 回读验证成功的知识文档/chunks，才按既有 `knowledge.search` 路径成为后续可检索候选；本次导入结果附带的 `KnowledgeReference` 只绑定已提交文档的当前 revision/chunk，不把审批前笔记或模型文本伪装成知识证据。后台/隐式自动摄取、共享摄取和生产相关性绕过继续关闭，Shadow 默认关闭、`enforcementApplied=false`、Room v36 与生产拒绝边界保持不变。

## 第 250 阶段下一条系统日程边界（无变更）

`calendar.next_event` 的执行时刻、30 天窗口、Calendar Provider 行、稳定 event ID、occurrence 开始时刻、Tool Message、答案导航 target 与真机验收结果只属于当前 Agent Run 和前台权威事实查看，不进入知识候选、Embedding、Judge、Shadow measurement 或匿名账本。答案级“查看日程”只从应用生成的固定 ToolResult 外壳投影，并在点击后从当前 Provider 二次回读；它不是知识引用，也不改变 `enforcementApplied=false`。Shadow 默认关闭、Room v36 与生产相关性拒绝保持不变。

## 第 249 阶段知识引用原文定位边界

本阶段只消费既有 `knowledge.search` 已保留的 `KnowledgeReference` 身份，不改变候选生成、Embedding、融合排序、Judge、Shadow measurement、匿名账本或生产拒绝。点击引用后只在当前 Room 文档与 chunk 的同一事务快照中核对 revision、sequence 和 offset；定位成功显示当前原文，历史、停用、删除或漂移不生成新的候选、样本或替代引用。导航 Saver 只保存已有引用身份，不保存额外全文。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与生产相关性边界保持不变。

## 第 248 阶段真实日历提醒闭环边界（无变更）

自然语言日程请求、30 分钟提醒、临时 Profile/会话、Run/Approval/Tool Ledger、Calendar Provider 事件与 reminder、Activity 重建和 UiAutomation 节点都只属于当前 Agent 执行与验收，不进入知识候选、Embedding、Judge、Shadow measurement 或匿名账本。答案级“查看日程”只从可信持久化 Tool Message 投影，并在点击后读取当前 Provider；它不是知识引用，也不提升可回答性结论。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与生产相关性拒绝均保持不变。

## 第 247 阶段日历提醒写入边界（无变更）

`reminder_minutes_before`、Calendar Provider reminder 行、临时事件、ToolCall/回执和审批状态都只属于当前 Agent Run 与日历写入验证，不进入知识候选、Embedding、Judge、Shadow measurement 或匿名账本。ToolResult 仅在 Provider 回读验证通过后显示受限分钟值；答案级日程入口继续按稳定事件 ID 二次打开系统权威详情，不把 reminder 伪装成知识库引用。Shadow 默认关闭、`enforcementApplied=false`、Room v36 和生产相关性拒绝保持不变。

## 第 246 阶段联系人导航边界（无变更）

“查看联系人”入口只从可信 `contacts.get / PASSED` 的结构化 Tool Message 投影，不解析模型自由文本。点击产生的 contact ID/lookupKey 仅在当前前台动作内短暂存在，用于重新读取 Contacts Provider 并启动系统详情；二次读取失败时不生成答案事实、引用或 Shadow 样本。联系人姓名、电话、邮箱、lookupKey、系统 Provider 行、权限状态和合成验收数据继续排除在知识候选、Embedding、Judge、Shadow measurement 与匿名账本之外。Shadow 默认关闭、`enforcementApplied=false`、Room v36 和生产相关性拒绝保持不变。

## 第 245 阶段联系人只读边界（无变更）

联系人搜索词、稳定 contact ID、姓名、电话、邮箱、临时合成联系人、系统 Provider 行以及权限状态都不得进入知识候选、Embedding、Judge、Shadow measurement 或匿名账本。`contacts.search / contacts.get` 的 Tool Message 只属于当前 Agent Run；答案可回答性仍仅评估既有知识引用链，不把通讯录事实伪装成知识库来源。Shadow 默认开关、`enforcementApplied=false`、Room v36、相关性拒绝和候选身份绑定均保持不变；最终文档 corpus gate `1/1` 通过。

## 第 244 阶段语音草稿边界（无变更）

系统识别 Activity 返回的文本只存在于 Conversation 可编辑草稿；用户显式发送前没有消息、Run、Provider 请求、知识检索、Judge 或 Shadow measurement。应用不保存原始音频、不把识别候选写入匿名账本，也不建立后台语音摄取。用户后续发送时，文本与普通手工输入走同一既有答案可回答性边界，不获得特殊来源权重或绕过生产开关。Room v36、Shadow Store、`enforcementApplied=false`、相关性拒绝和候选身份绑定均保持不变；最终文档 corpus gate 为 `1/1`（`3.077s`）。

## 第 243 阶段图片 Agent 理解边界（无变更）

本阶段只验证真实 PNG 在用户明确发送 `/agent` 后进入既有 Responses 图片规划、`notes.create` 审批与 Store 回读。图片 URI、原始 PNG BLOB、像素内动态值、临时笔记/Profile/会话、Run/Approval/Tool Ledger 和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。文档 corpus 首次为 `1/1`（`2.979s`），审查后的最终文本 gate 也为 `1/1`（`3.067s`）。

## 第 242 阶段 XLSX Agent 理解边界（无变更）

本阶段只验证 `extractedText=null / pageCount=null` 的真实 XLSX 在用户明确发送 `/agent` 后进入既有 Responses 文件规划、`notes.create` 审批与 Store 回读。XLSX URI、原始 ZIP/OPC BLOB、`xl/worksheets/sheet1.xml` 动态单元格值、临时笔记/Profile/会话、Run/Approval/Tool Ledger、桌面参考夹具和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。

## 第 241 阶段 PPTX Agent 理解边界（无变更）

本阶段只验证 `extractedText=null / pageCount=null` 的真实 PPTX 在用户明确发送 `/agent` 后进入既有 Responses 文件规划、`notes.create` 审批与 Store 回读。PPTX URI、原始 ZIP/OPC BLOB、`ppt/slides/slide1.xml` 动态值、临时笔记/Profile/会话、Run/Approval/Tool Ledger、桌面参考夹具和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。

## 第 240 阶段 DOCX Agent 理解边界（无变更）

本阶段只验证 `extractedText=null / pageCount=null` 的真实 DOCX 在用户明确发送 `/agent` 后进入既有 Responses 文件规划、`notes.create` 审批与 Store 回读。DOCX URI、原始 ZIP/OPC BLOB、`word/document.xml` 动态值、临时笔记/Profile/会话、Run/Approval/Tool Ledger 和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。

## 第 239 阶段 PDF Agent 理解边界（无变更）

本阶段只验证 `extractedText=null` 的真实 PDF 在用户明确发送 `/agent` 后进入既有 Responses 文件规划、`notes.create` 审批与 Store 回读。PDF URI、原始 BLOB、页内动态值、临时笔记/Profile/会话、Run/Approval/Tool Ledger 和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。

## 第 238 阶段分享文档 Agent 理解边界（无变更）

本阶段只验证分享 Markdown 在用户明确发送 `/agent` 后进入既有 Responses 附件规划、`notes.create` 审批与 Store 回读。文档 URI、原始/提取正文、动态标题、验收码、临时笔记/Profile/会话、Run/Approval/Tool Ledger 和 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 真实 `1/1` 没有产生 Shadow 样本。

## 第 237 阶段系统分享单文档边界（无变更）

本阶段只把单份受支持文档和可选说明投影到既有可编辑附件草稿；文档 URI、文件名、MIME、提取正文、分享说明和读取失败信息均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 聚焦 `5/5` 没有发送消息、创建 Run 或产生 Shadow 样本。

## 第 236 阶段系统分享全天日程边界（无变更）

本阶段只把用户显式选择且标题/日期契约完整的 `text/plain` 分享改写为可编辑 `/agent calendar.create_all_day_event` 草稿；分享正文、日期、临时 Profile/会话、Run/Approval/Tool Ledger、事件 ID 与 runner 参数均不得进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 入口 `4/4` 与真实 Provider `2/2` 没有产生 Shadow 样本。

## 第 235 阶段系统分享日程边界（无变更）

本阶段只把用户显式选择且四字段完整的 `text/plain` 分享改写为可编辑 `/agent calendar.create_event` 草稿，并在用户发送、逐次审批、Calendar Provider 回读和可信 MessagePart 投影后提供答案级日程入口。分享原文、结构化时间、事件 ID、临时 Profile/会话、Run/Approval/Tool Ledger 与 runner 参数均不进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 入口 `4/4` 与真实 Provider `2/2` 没有产生 Shadow 样本。

## 第 234 阶段系统分享长期记忆边界（无变更）

本阶段只把用户显式选择的 `text/plain` 分享改写为可编辑 `/agent memory.remember` 草稿，并在用户发送、逐次审批、Room 回读和可信 MessagePart 投影后提供答案级记忆入口。分享正文、临时记忆、Profile/会话、Run/Approval/Tool Ledger、runner 参数和记忆导航 ID 均不进入知识候选、Judge 输入、Shadow measurement、匿名账本或 Provider 相关性请求，也不触发额外 Shadow 调用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定保持不变；Redmi 入口 `3/3` 与真实 Provider `1/1` 没有产生 Shadow 样本。

## 第 233 阶段提醒结果导航边界（无变更）

本阶段新增的 token、workflowId、scheduledTaskId、workflowRunId、签发/过期时间、PendingIntent action、Activity 冷/热导航、一次性导航版本和页面高亮只属于应用内结果路由安全状态，不进入知识候选、Judge 输入、Shadow measurement、匿名账本、Provider 请求或答案级知识引用。Shadow 默认关闭、`enforcementApplied=false`、Room v36、生产相关性拒绝和既有候选身份绑定规则保持不变；Redmi 最终组合 `6/6` 已通过，没有产生 Shadow 样本或生产拒绝变化。

## 第 232 阶段一次性提醒边界（无变更）

本阶段只把已确认的系统分享个人任务接入既有一次性 WorkManager 调度、后台 Workflow、`app.current_time` Tool Ledger、目标级验证和结果通知。分享原文、计划、ScheduledTask、WorkRequest、后台会话、Workflow/Agent Run、通知正文与 runner 参数均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 231 阶段系统分享个人任务边界（无变更）

本阶段只把用户显式选择的 `text/plain` 分享切换为个人任务编辑态，并在后续计划确认后复用既有 Workflow、`app.current_time` Tool Ledger 与目标级验证。分享原文、草稿转换、待确认计划、临时 Profile/会话、Workflow/Agent Run 和 runner 恢复参数均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 230 阶段系统分享 Agent 草稿边界（无变更）

本阶段只把用户显式选择的 `text/plain` 分享改写为可编辑 `/agent notes.create` 草稿，并在正式发送、审批和 Store 回读后按既有可信执行记录展示结果。分享原文、临时笔记、Provider 恢复参数、Run/Approval/Tool Ledger 和草稿转换动作均不进入知识候选、Judge、Shadow measurement 或匿名账本，不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 229 阶段设备观察真实前台闭环边界（无变更）

本阶段只在 Redmi 真实前台验证既有 `device.open_app -> device.snapshot` 的自然语言规划、逐次审批、操作后回读、消息投影和旧 Run 不变；系统设置节点摘要、审批、Run/Tool Ledger 与临时会话不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Snapshot 继续按 `READABLE_ONLY` 投影，设备动作按 `VERIFIED` 投影；Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 228 阶段设备 Agent 健康边界（无变更）

本阶段只新增前台只读设备健康摘要；四态状态、Provider 调用、Run/Tool Ledger 和临时会话不进入知识候选、Judge、Shadow measurement 或匿名账本，不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 227 阶段进程重启审批恢复边界（无变更）

本阶段只验证持久化 `WAITING_APPROVAL` 在进程重启后恢复到原会话并完成同一工具审批；恢复 UI、Run、Approval、Tool Ledger 和临时数据清理不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 226 阶段 Skill 草稿发送与审批边界（无变更）

本阶段只在 Redmi 真实前台验证用户明确发送 Skill 草稿、`local-notes` Skill 选择、`notes.create` 审批和提交回执；临时笔记与会话清理不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Run、Approval 和 Tool Ledger 仅作为执行审计保留；Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 225 阶段 Skill 试用真实壳边界（无变更）

Redmi 真实应用壳只把当前 Profile 已授权 SAFE Skill 的示例预填为 `/agent ...` 草稿；选中 Profile、会话、消息和最近 Run 摘要均保持不变，没有产生答案、知识候选、Judge、Shadow measurement 或匿名账本，也不触发生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 224 阶段 Skill 直接试用边界（无变更）

本阶段只把当前 Skill 自有示例投影为可审阅的 `/agent` 对话草稿；点击不会发送消息、调用模型、创建 Run、执行工具或产生答案，因此不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发生产拒绝。Skill/Profile/工具状态漂移时 fail-closed，Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 223 阶段全天日程真实前台闭环边界（无变更）

本阶段只在 Redmi 真实前台链路验证 `calendar.create_all_day_event` 的自然语言规划、人工审批、Tool Ledger、答案级当前 Provider 查看与精确清理；日程标题、日期、稳定事件 ID、审批和回执均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。旧 Run 完整详情保持不变，Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 222 阶段全天日程创建边界（无变更）

本阶段的 `calendar.create_all_day_event` 参数、审批、Provider 写入/回读、稳定事件 ID 与答案级当前详情入口不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 221 阶段长期记忆删除 UI 边界（无变更）

本阶段的 `memory.search / memory.get / memory.delete` 真实前台审批、Tool Ledger、删除回执和长期记忆当前不可见状态不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。清理仅作用于临时记忆、Profile、消息和撤销文件；Run 审计与恢复后的空会话身份保持不变。Shadow 默认关闭、`enforcementApplied=false`、Room v36 与既有候选绑定规则保持不变。

## 第 211 阶段真实历史会话读取 UI 边界（无变更）

本阶段的 `app.search_conversations / app.get_conversation`、当前会话排除和答案级“查看会话”只读取 Room 会话身份及用户/助手正文；搜索结果、历史正文、Run 后正文变化和 UI 导航均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 210 阶段真实日程删除 UI 边界（无变更）

本阶段只在 Redmi 的真实前台 Provider 链中验证 `calendar.delete_event` 的审批、指纹条件删除、提交回执、当前不可见和历史入口二次读取；事件标题、时间、指纹、删除结果与 NotFound 页面均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 209 阶段真实日程修改 UI 边界（无变更）

本阶段只在 Redmi 的真实前台 Provider 链中验证 `calendar.update_event` 的审批、指纹条件更新、提交回执和答案级当前日程查看；三条 Run 的工具结果、日程标题/时间和 Provider 指纹均不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 203 阶段真实长期记忆会话投影边界（无变更）

本阶段只在 Redmi 的 Debug-only 真实 Provider 探针中把已验证的 `memory.remember` 结果短暂投影到专属 Room 会话，再重建并检查可信 Tool part 与稳定记忆导航；临时会话和测试记忆均精确清理。该投影不读取或改写知识候选、答案引用、Judge 身份、Shadow measurement 或匿名账本，不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 202 阶段真实 Provider 长期记忆写入边界（无变更）

本阶段只在 Redmi 真机的 Debug-only 探针中验证真实 `memory.remember` 的审批、提交回执和当前 Room 回读；测试 note、Provider 凭据和内部参数不进入知识候选、Judge、Shadow measurement 或匿名账本，也不触发额外 Shadow 模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 201 阶段长期记忆写入导航边界（无变更）

本阶段只把已通过 Executor 验证的 `memory.remember` 回执投影为当前记忆查看入口，并在点击后回到 Room 二次读取；不会把记忆正文送入知识候选、Judge、Shadow measurement 或匿名账本，也不会触发额外模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 200 阶段本地笔记导航边界（无变更）

本阶段只把已通过应用结果契约校验的本地笔记详情/编辑结果投影为查看入口，并在点击后回到当前本地 Note Store 二次读取；不会把笔记正文送入知识候选、Judge、Shadow measurement 或匿名账本，也不会触发额外模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 199 阶段日程写入结果导航边界（无变更）

本阶段只让已通过应用执行验证的日程创建/修改结果携带稳定查看目标，并复用当前 Calendar Provider 详情页；不会把写入结果送入知识候选、Judge、Shadow measurement 或匿名账本，也不会触发额外模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和既有候选绑定规则保持不变。

## 第 198 阶段系统日程导航边界（无变更）

本阶段只把可信日程 Tool 结果投影为查看入口，并在独立页面按当前 Calendar Provider 二次读取；不会读取或改写知识候选、答案引用、Judge 身份、Shadow measurement 或匿名账本，也不会触发答案观测、额外模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和现有候选绑定规则保持不变。

## 第 197 阶段历史会话导航边界（无变更）

本阶段只把可信历史会话 Tool 结果投影为前台查看入口，并在点击前重读当前 Room；不会读取或改写答案引用、Judge 身份、Shadow measurement 或匿名账本，也不会触发答案观测、模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和现有候选绑定规则保持不变。

## 第 196 阶段历史会话详情边界（无变更）

本阶段的 `app.get_conversation` 只读取当前会话的用户/助手历史文本，不读取知识候选、答案引用、Judge 身份或 Shadow 账本，也不会触发答案观测、模型调用或生产拒绝。详情内容有界并标记为本地资料；Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和现有候选绑定规则均保持不变。

## 第 195 阶段 Agent Profile 边界（无变更）

本阶段的 `agent.get_profile` 只读取当前前台直接 Run 的短生命周期 Profile 状态，不读取知识候选、答案引用、Judge 身份或 Shadow 账本，也不会触发答案观测、模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和现有候选绑定规则均保持不变。

## 第 194 阶段应用信息边界（无变更）

本阶段的 `app.get_info` 只读当前安装包元数据，不读取知识文档、答案引用、Judge 身份或 Shadow 账本，也不会触发答案观测、模型调用或生产拒绝。Shadow 默认关闭、`enforcementApplied=false`、Room v33 匿名账本和现有候选绑定规则均保持不变。

## 第 193 阶段关联导航边界（无变更）

本阶段只为任务中心提供当前历史内的关联 Run 查看入口，不读取或改写答案级知识引用、Shadow measurement、Judge 绑定或匿名账本。任务中心导航不触发 `/agent` 答案观测、知识检索、模型调用或生产拒绝；Shadow 默认关闭、`enforcementApplied=false` 与现有 Room v33 证据边界保持不变。

## 当前边界

answerability Shadow 只观察用户显式开启后的前台直接 `/agent` 答案。每次显式开启最多授权一轮观测：候选存在、答案成功保存且调用前仍开启时，Publisher 先关闭并持久化开关，再进入观测协调器；候选缺失、答案保存失败或用户提前撤销不消费本次窗口。候选必须来自同一 Run 中最近一次成功、验证未失败且带稳定引用的 `knowledge.search`；冻结 Judge 身份必须与当前 Provider 配置完全一致。普通聊天、Workflow、后台 Worker、候选缺失、身份漂移或答案保存失败都不得请求 Judge 或写入匿名账本。

`v0.1.15` 源码与正式产物继续使用 Room v33；本次发布未安装到 Redmi，因此设备上仍是发布前开发数据状态，不能写成已经升级验收。生产请求通过 `KnowledgeAnswerabilityShadowPersistenceMode.OPTIONAL` 写入 `knowledge_answerability_shadow_observations`；旧阶段的 `store=null / persistenceMode=NONE` 只属于第 96 至 101 阶段的历史事实，不再代表当前实现。Shadow 默认关闭，notice 仍只存在于当前进程，`enforcementApplied=false`，production enforcement 继续关闭。

第 102 阶段只冻结版本化离线评测导出类型，没有接入 JSON codec、UI 或 SAF 出口。第 103 阶段在 Redmi 形成 Room v33 的第一条间隔真实记录；第 104 阶段在完整清理和进程重启后形成第二条短间隔记录，并修复冷启动摘要被默认零值覆盖的问题。第 105 阶段把持续开关收紧为单次显式采样窗口；第 106 阶段只把匿名账本的最早/最新时间与跨度投影到设置页；第 107 阶段形成第三条独立同日记录。当前三条记录仍不足以作为 calibration/validation 数据，也不能据此启用生产拒绝。

## 候选来源

`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 按执行列表从后向前寻找最近一条同时满足以下条件的执行：

- `toolName == "knowledge.search"`；
- `success == true`；
- `verificationStatus != FAILED`；
- `rawResult` 非空；
- `knowledgeReferences` 非空。

没有 `toolExecutions` 的旧消息会把顶层工具字段投影成一个执行项。候选在进程内携带来源 Run、原问题、原始检索结果和引用列表，用于 Judge 和冻结绑定；这些内容不得进入 v33 匿名账本。空 Run、其他工具和不完整结果不会被猜测成知识证据。

## 离线观测与线上测量

- `KnowledgeAnswerabilityAssessment` 定义 Judge 决策所需的共享字段和冻结门禁语义。
- `KnowledgeAnswerabilityObservation` 用于 calibration/validation，必须携带人工真值 `label`。
- `KnowledgeAnswerabilityShadowMeasurement` 用于真实 Agent Run，只携带 `sourceRunId`，不得伪造人工真值。
- 离线 observation 与线上 measurement 复用候选证据匹配和字段映射，但线上数据不得直接进入离线标签统计。

## 冻结绑定

`KnowledgeAnswerabilityFrozenBinding` 必须锁定：

1. calibration/validation 使用同一 Judge provider、model、configuration fingerprint 和 prompt version；
2. calibration 与 validation 的 dataset version 非空且互异；
3. `KnowledgeAnswerabilityGate` 已冻结。

消息 Shadow 只允许已通过独立证据的 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`。覆盖率特征族尚未通过；身份漂移、measurement 缺失、来源 Run 不匹配或候选不完整都固定返回 `UNKNOWN`。

绑定开始时复制候选和引用并保留原顺序，调用方后续修改可变 List 不会改变同一结果。没有 measurement 时 `observedAt=null`；Judge 身份漂移时 measurement 可以保留，但绑定继续为未知。任何结果都不得删除、替换或重排答案与引用。

## 生产测量流程

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一入口：

1. `DISABLED` 返回 `SKIPPED / DISABLED`，不调用 Judge，也不持久化；
2. 非 `DIRECT_FOREGROUND` 返回 `SKIPPED / UNSUPPORTED_ORIGIN`；
3. 进入 Judge 前冻结候选引用，保证首调、重试、绑定和持久化基于同一证据快照；
4. 候选或冻结绑定不完整时不调用 Judge，形成保守未知结果；
5. 成功响应转换为无标签 measurement，并使用实际 Judge identity 完成绑定；
6. 生产 Publisher 在答案保存后使用 `OPTIONAL` 请求，Store 失败只降低旁路审计完整度，不改变答案、引用、Run 终态或主失败路径。

`OpenAiKnowledgeAnswerabilityJudge` 固定使用 Responses 非流式请求，关闭 reasoning summary，使用 `temperature=0`、`topP=1` 和 `maxTokens=220`。adapter 单次只发一个请求，不自行叠加重试；响应只通过严格 codec 解码。请求复用统一 Provider 鉴权、可配置 User-Agent 和兼容 Header，并逐请求关闭 HTTP Debug 请求、响应与流事件日志。

`AgentAnswerabilityShadowPublisher` 只接收前台直接 Agent。答案先进入 UI 并启动正常会话保存；旁路 Job 等待对应保存成功，再通过 `tryConsumeObservationWindow` 原子检查并消费一次性授权，只有成功关闭并持久化开关的调用才能进入协调器。无候选、保存失败、提前撤销或授权已被并发答案消费时不会请求 Judge；协调器开始后的取消、未知或异常仍保持开关关闭。所有旁路终态都不会进入 Agent 主失败分支。

## 状态、失败与重试

| 条件 | 状态 | 决策 | 用户提示 |
| --- | --- | --- | --- |
| identity、Run、measurement 和允许的 gate 一致 | `BOUND` | `ACCEPT / REJECT / UNKNOWN` | 复用 Shadow 呈现 |
| 覆盖率特征族 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| identity 漂移或缺失 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| measurement 缺失或来源 Run 不一致 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| 来源 Run、候选正文或引用不完整 | `UNKNOWN` | `UNKNOWN` | 尚未确认 |
| Judge 传输或协议终败 | 观测 `UNKNOWN` | `UNKNOWN` | 不投影 notice |

- 瞬时网络、限流、服务端和协议失败允许一次受控重试，总尝试次数最多两次。
- 认证、普通请求、身份、候选和未知异常不重试。
- 重试分类封装在协调器内部，调用方不得增加外层重试。
- `CancellationException` 原样传播；取消不生成 `UNKNOWN`，也不产生持久记录。
- HTTP 成功但协议失败仍保留已经产生的 usage 和时延；重试成功仍保留前序失败分桶。

## Room v33 匿名账本

`RoomKnowledgeAnswerabilityShadowObservationStore` 只接受 64 位小写 SHA-256 的 `idempotencyKey` 与 `candidateFingerprint`。幂等键作为主键，重复发布只保留首次观测；插入与裁剪位于同一 Room transaction，按 `recordedAt` 最多保留最新 2,000 条。

表只允许保存：

- SHA-256 幂等键与候选摘要；
- Android Keystore 安装级不可导出密钥生成的 Judge HMAC-SHA-256 匿名桶；
- attempt、观测状态、绑定状态/原因、决策与稳定失败分类；
- 可空的延迟、TTFB、Prompt 字节、输入/输出/总 Tokens、usage 次数；
- 十类独立失败计数和记录时间。

表不得保存消息 ID、Run ID、问题、答案、候选正文、引用正文或身份、原始 Judge 响应、Provider ID、模型、Base URL、API Key 或其他凭据。`sourceRunId`、`persistedMessageId` 和完整 Judge identity 只用于进程内协调和生成不可逆摘要，不进入 Entity。公开配置的无盐 SHA-256 不能作为 Judge 桶；数据库单独泄露时不得反查或跨安装关联 Provider/模型组合。

未知数值必须保持 `null`，不能伪造为 `0`。最终稳定 `failureKind` 没有出现在 attempt telemetry 时补计一次，避免候选校验或意外异常从跨进程失败分布消失。Store 普通失败返回 `persistenceStatus=FAILED`，不改变绑定或原答案；Store 取消继续传播。

v32→v33 migration 只创建空表和时间索引，不扫描或回填历史消息、Run、检索审计或第 97 至 101 阶段人工统计。旧版本备份按既有迁移链进入 v33；未来高版本仍按既有拒绝策略处理。

## 进程内摘要与 notice

`KnowledgeAnswerabilityShadowSampleTracker` 使用固定上限的饱和计数，只累计样本终态、Judge attempt、延迟/TTFB、Prompt 字节、Tokens、usage attempt、稳定失败枚举和 notice 生命周期。它不持有问题、答案、候选正文、引用、原始响应、消息 ID、Base URL 或凭据，进程重启后清空。

`KnowledgeAnswerabilityShadowPersistentSummary` 与进程内摘要分离。设置页分别展示跨进程匿名累计和当前进程样本/notice；跨进程读取失败保留上一次 UI 摘要。notice 使用 `messageId -> KnowledgeAnswerabilityUserNotice` 的进程内 Map，不写回消息、可信上下文或 Room；会话删除或重载时裁剪悬空键，进程重建后自然清空。

冷启动中，跨进程摘要读取可能先于 Profile、会话和 Workflow 初始化完成。初始化整表状态重建必须通过 `mergeAnswerabilityShadowInitializationState()` 保留已经加载的跨进程摘要、当前开关和进程内摘要，不能让 `XiaoLingUiState` 默认零值覆盖真实 Room 累计。该合并不写 Room，也不恢复 notice。

## 第 102 阶段离线导出类型

`KnowledgeAnswerabilityExportEnvelope` 是版本化 sealed 契约，当前 `schemaVersion=1`：

- `KnowledgeAnswerabilityShadowExportEnvelope` 的来源固定为 `ROOM_V33_ANONYMOUS_LEDGER`，只携带非空 `KnowledgeAnswerabilityShadowExportRow`。row 保留不可逆指纹、状态/绑定/决策/失败枚举、失败分桶、可空成本和记录时间，不提供原始 Judge 或 dataset identity；`eligibleForCalibrationOrValidation()` 固定返回 `false`。
- `KnowledgeAnswerabilityAuthorizedContentExportEnvelope` 的来源固定为 `EXPLICIT_USER_AUTHORIZED_CASES`，必须携带显式离线评测授权、完整 dataset identity、正文、引用、人工 label 和 assessment；`eligibleForCalibrationOrValidation()` 返回 `true`。

两种 envelope 不能在同一对象中混装匿名 rows 与授权 cases。当前只冻结内存类型和校验规则，没有 JSON codec、文件格式、UI 或 SAF 导入导出入口，也没有自动把匿名账本升级为授权评测集的路径。

## 第 103 阶段首条 v33 真实样本

Redmi `wsvwypiz7xwslvl7` 在与第 101 阶段相隔约 69 小时的独立窗口中，从空 v33 账本开始采样。当前 README 临时导入后形成 19 个 chunks；Embedding 不可用时，查询 `Agent Run retryOfRunId` 通过词法兜底命中 5 个 chunks。显式开启 Shadow 后，前台直接 `/agent` 完成 `knowledge.search`、答案和引用保存，并触发一次真实 Judge。

停进程证据为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `9663/9655ms`，Prompt `10879B`，输入/输出/总 Tokens `2801/469/3270`，usage samples `1`；所有失败分桶为 `0`，记录时间为 `2026-07-29 07:27:36`（北京时间）。清理后知识文档/chunks 为 `0/0`，匿名账本保持 `1`，Shadow 为关闭，production enforcement 偏好不存在。

这条证据只证明当前 Provider、网络、候选和 v33 Store 下的单次旁路链路可用，不与第 97 至 101 阶段人工合计混算，也不足以进入 JSON/SAF、独立阈值校准或生产拒绝。

## 第 104 阶段第二条 v33 真实样本

第 103 阶段完整清理并重启进程后，Redmi 导入本文形成 revision `1`、`5` 个 chunks、`11.4 KB`。Embedding 不可用时，查询 `anonymous shadow calibration validation` 通过词法兜底命中 `1` 个 chunk；前台直接 `/agent` 完成 `knowledge.search`、答案和引用保存，并触发真实 Judge。

新增记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7645/7632ms`，Prompt `3967B`，输入/输出/总 Tokens `905/372/1277`，usage `1`，所有失败分桶为 `0`，记录时间为 `2026-07-29 08:13:50`（北京时间）。与首条累计为观测 `2`、Judge 身份桶 `1`、完成/绑定/接受 `2/2/2`、attempt `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`、usage `2`，所有失败分桶仍为 `0`。

两条记录只相隔约 `46` 分钟，本次只证明完整清理和进程重启后的独立短间隔链路，不视为长期分隔样本。数据库已有 `2` 条记录但设置页冷启动曾显示全零，现已修复初始化状态合并并由 JVM/Redmi 复验。清理后 documents/chunks 与 messages 为 `0`，两个 Agent Run 均保持 `COMPLETED`，Shadow 关闭，production enforcement 偏好、测试包和临时下载文件均不存在。当前窗口停止继续采样。

## 第 105 阶段单次显式采样窗口

第 103/104 阶段证明链路可用后，开关不再代表持续授权。Publisher 只有在候选存在且答案保存成功时才调用 `tryConsumeObservationWindow`；ViewModel 使用 `AnswerabilityShadowObservationWindowGate` 在同一临界区检查开关、同步关闭 UI 状态并写入 `UiPreferenceStore`。并发答案只有一条能消费同一授权并进入协调器，一次成功、未知、取消或异常观测都需要下一次新的用户显式开启。

候选缺失、答案保存失败、用户在调用前关闭或并发答案已经抢先消费时，不会误用同一采样窗口。本阶段用 `AgentAnswerabilityShadowPublisherTest` 覆盖消费顺序、候选缺失、保存失败和提前撤销，用 `AnswerabilityShadowObservationWindowGateTest` 的 20 路并发覆盖唯一消费者；聚焦 JVM 合计 `11/11`。没有新增 Room 行，也没有改变 Judge 重试、匿名账本、notice、离线评测资格或 production enforcement。

## 第 106 阶段时间窗口证据投影

`KnowledgeAnswerabilityShadowPersistentSummary` 已从 Room 聚合最早和最新 `recordedAt`，设置页现在通过纯 `projectAnswerabilityShadowWindowEvidence()` 按设备本地时区显示两端时间，并用实际毫秒差显示精确跨度。第 103/104 阶段两条记录对应北京时间 `2026-07-29 07:27:36 -> 08:13:50`，跨度 `46 分钟 13 秒`。

该投影在时间缺失或逆序时显示未知，不定义小时/天数阈值，也不返回 ready/eligible 状态。它只消除停进程查数据库的人工成本，不能把短间隔记录升级为长期分隔样本，不能触发 Judge、修改 Room、导出 JSON/SAF、进入 calibration/validation 或开启 production enforcement。聚焦 JVM `3/3` 覆盖正常跨度、单端缺失和时间逆序，AndroidTest APK 编译通过；未安装或运行设备测试。

## 第 107 阶段第三条独立同日记录

Redmi 在第二条记录后 `4 小时 38 分 33.243 秒` 开启新的显式窗口。临时导入本契约为 `xiaoling-stage107-shadow.md`，revision `1`、`8` 个 chunks，Embedding 未建立，检索使用词法兜底。首次较宽请求已经完成 4 次 `knowledge.search`，第五次参数校验因工具预算耗尽而让 Run 收敛为 `BUDGET_EXHAUSTED`；由于没有成功答案，Publisher 没有消费一次性授权，匿名账本仍为 `2`，这次失败不计入 Shadow attempt 或失败分桶。

随后使用已验证的 `anonymous shadow calibration validation` 查询，只执行 1 次 `knowledge.search` 并完成答案、引用保存和真实 Judge。新增记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7288/7274ms`，Prompt `6664B`，输入/输出/总 Tokens `1715/314/2029`，usage `1`，十类失败分桶全为 `0`，记录时间为北京时间 `2026-07-29 12:52:23.355`。三条累计为耗时/TTFB `24596/24561ms`、Prompt `21510B`、Tokens `5421/1155/6576`，Judge 匿名桶仍为 `1`。

最早到最新总跨度为 `5 小时 24 分 46.689 秒`，设置页按秒显示 `5 小时 24 分 46 秒`。本轮只证明同日分隔窗口的完整链路和“预算耗尽不消费授权”真实行为，不能声明已经取得长期分隔证据。清理后 documents/chunks/messages 为 `0/0/0`，空壳会话 `1`，Agent Run `4` 条保留审计（完成 `3`、预算耗尽 `1`），Shadow `false`，Provider/Profile `1/1`，测试包与临时下载文件不存在。JSON/SAF、显式授权评测集、独立阈值校准和 production enforcement 继续关闭。

## 当前不做的事

- 不把 Shadow 结论用于删除引用、改写答案、改变检索排序或拒绝生产回答。
- 不恢复跨进程 notice，不把普通聊天、Workflow 或后台 Worker 接入 Shadow。
- 不把匿名账本自动用于 calibration/validation，不自动生成显式授权数据。
- 不接入 JSON codec、UI/SAF 文件出口、远程上传或后台导出。
- 不因为少量成功样本开启 production enforcement；新增执行能力前必须重新评审隐私、生命周期、身份、阈值和独立验收。

## 当前验收基线

- Room v33 匿名账本实现：完整 JVM `734/734`，Lint `0 error / 51 warnings`，Debug、AndroidTest、R8 Release 与 lintVital 通过；
- Redmi Store 聚焦：HMAC 匿名桶、公开 SHA-256 不等于落库 HMAC、数据库重开、幂等写入、未知数值、稳定失败补计和第 2,001 条裁剪均通过；
- Redmi 完整 instrumentation XML：`248` 条（`236 passed / 12 skipped / 0 failed`），runner 打印 `260 tests`；
- 第 102 阶段导出契约：完整 JVM `736/736`，三类 APK、Lint、Redmi 完整 instrumentation 与文档 corpus gate 通过；
- 第 103 阶段按分级验证只执行 Debug/AndroidTest 构建、Provider 兼容单项、真实前台 Shadow 链和 Redmi 文档 corpus `OK (1 test)`；
- 第 104 阶段按分级验证执行聚焦 JVM、Debug/AndroidTest 构建、第二条真实前台 Shadow 链、冷启动设置页复验和 Redmi 文档 corpus 单项；当前 corpus 前两轮均为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充设备收尾并重新打包后的最终复验同样通过；
- 第 105 阶段按分级验证执行 Publisher `10/10` 与原子门禁 20 路并发 `1/1`，聚焦 JVM 合计 `11/11`；未新增真实样本，未运行完整 JVM、Lint、APK、Redmi instrumentation 或 Release；
- 第 106 阶段按分级验证执行时间投影 JVM `3/3` 与 AndroidTest APK 编译；未新增真实样本，未安装 APK、运行 Redmi instrumentation、完整 JVM、Lint 或 Release；
- 第 107 阶段只在 Redmi 执行第三个真实窗口、聚焦时间投影 JVM `3/3`、AndroidTest APK 编译和项目文档 corpus 单项；首次/最终 corpus 均为 `OK (1 test)`、耗时 `2.687s / 2.606s`。没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- Pixel_9 和其他模拟器未参与上述验证。
