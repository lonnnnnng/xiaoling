# 多 Agent child 持久生命周期

第 289 阶段两片已完成前台只读多 Agent 实现基线：第一片保存 child 控制面事实，第二片接入任务中心显式入口、父结果消费与完成策略。Room 保持 v44，发布版本保持 `0.1.22 (23)`。页面动作、生产 UseCase/Room、真实 Activity 生命周期及真实提供方分别验收；完整产品点击到模型的联合专项仍独立追踪。

## 来源与授权

父 Run 必须尚未结束、没有取消请求、没有 parentRunId，具有唯一 `run.invocation.selected` 和唯一冻结 Profile，来源审计绑定当前进程代次。来源审计必须为 `FOREGROUND + DIRECT + externalInput=false`，旧 Run 缺审计时不能凭默认参数补授权；插件 Run 不能派生。调用方的 Profile 必须等于父 Run 快照，provider/model/apiMode 必须匹配。

child 只保留父 Profile 与固定 13 项基础只读能力的交集，再核对注册定义为 `SAFE/NONE`。清空 Skill、关闭自动记忆召回，不继承插件、附件、MCP、设备动作、写能力或递归派生。知识工具使用无 Embedding provider 的宿主 store。当前测试只开放 `app.current_time`，没有读取正式 notes/memory/knowledge 内容。

## checkpoint 与原子派生

`prepareReadOnlyChildRuns` 只允许一个父 Run 登记一批、每批 1–2 个 child。父 Profile 的身份、模型和系统提示不能被 child 改写。目标限 2000 字符，每个目标保存到独立 Run；批次 schema v1 保存父 ID、进程代次、slot→Run ID、只读 Profile 与预算，JSON 限 32768 字符。

全部 child Run、`run.created`、父 `multi_agent.batch.prepared`、`multi_agent.children.expected`、子 `multi_agent.child.prepared` 和双向关联同一事务提交。第二个 child 插入失败会回滚第一个 child 与父计数。重复登记整批拒绝，不新增 Run。

`claimReadOnlyChildRun` 在事务内回读父子 checkpoint，检查父/批次仍属于当前进程、child 为 QUEUED/无取消/无领取标记，然后追加唯一 `multi_agent.child.claimed`。这是单次领取证据，不能恢复旧进程的执行栈。公开 child 执行入口还核对目标、会话、消息、父 ID、Profile 与请求协议，普通 `run(parentRunId=...)` 不能创建未登记 child。

状态直接沿用原 Run：QUEUED 表示已登记未开始，领取事件区分是否已取得槽位；运行中使用原 THINKING/EXECUTING/VERIFYING，结算沿 COMPLETED/FAILED/CANCELLED/BUDGET_EXHAUSTED。Profile、预算快照、输入快照和 Tool Ledger 继续由原 Runtime 写入。

## 预算、取消与结果

每个 child 最多 3 次工具调用，执行时间取父最新可信持久预算余额与 60 秒的较小值，模型步骤不超过 20 秒、工具步骤不超过 10 秒；并发最多两个，批次工具调用总上界为 6。预算是独立 Run 上限，当前没有父/child 精确共享扣账、令牌/费用预算或持久父 deadline；不能把父快照解释成实时剩余时长。

父取消请求、父终态及进程恢复关闭父任务时，事务内取消所有带 child 标记的活动派生 Run。普通 retry 也有 parentRunId，取消只按 child 标记筛选，不能误伤 retry 或已完成 child。活动步骤、审批和终态同事务关闭，迟到状态、事件和工具账本写入仍受原终态门禁。

批次与公开 child 执行入口都监听父状态，包括模型 socket 等待；取消异常继续向上传播。创建、领取和回调阶段未能交给 Runtime 的记录也会补结算，批次 finally 关闭所有未完成槽位。取消与工具超时仍遵守协作式语义，不声称能硬中断任意阻塞宿主代码。

普通子失败不取消成功兄弟。返回值绑定 child ID/Run ID，并从 Room 取真实终态：Runtime 超时维持 BUDGET_EXHAUSTED，不折叠成 FAILED。结果文本限 20000 UTF-16 字符，错误摘要限 500 字符；原 Run 的完整事实仍保留。任务中心沿父期望数与实际 child 状态显示 COMPLETE/PARTIAL/FAILED/UNKNOWN。

## 显式父任务与结果消费

任务中心“新建只读多 Agent”接受 1–2 个本机文本目标，单目标最多 2000 字符。拒绝当前活动操作、个人任务确认、重试确认、共享/远端草稿、附件及附件读取。确认时冻结当前 Provider/Profile，密钥仅在进程启动链短暂传递。新批次建立独立会话，保留当前聊天身份与输入框；父 ID 独立保存，切换会话后仍能停止。

`AgentChildBatchSelection` 绑定真实 MainActivity resumed 代次和状态实例，只能 claim 一次。实际暂停取消整批模型/工具等待，返回前台不能复用旧选择。Room 同事务创建父 Run、来源审计、`multi_agent.foreground.selected`、Profile、初始预算及 THINKING；选择 marker 在创建 child 前也能阻止旧父原地恢复、重试或受控重放。父身份提交后通过不可取消的独立通知交给 UI，即使首次快照前取消仍能权威回读。

新父批次用单调时间限制整批等待至 60 秒，记录等待预算；child 保留第一片独立执行上限。父等待时长与并发 child 执行预算是两种度量，不能相加冒充精确共享扣账。父等待超时为 BUDGET_EXHAUSTED，未完成 child 关闭，已完成 child 保留。

`settleReadOnlyChildParent` 在 Room 事务内核对父来源、选择目标、checkpoint、槽位身份/lineage、每 child 真实终态/完成时间，再消费有界结果与 PASSED 工具数量。`multi_agent.results.consumed` schema v1 保存父 ID、slot/Run ID、目标、状态、完成时间、结果/错误与验证数量；编码最多 300000 字符以容纳 JSON 转义。汇总为确定性逐项报告，不新增父模型请求。

只有全部 child COMPLETED 时父任务 COMPLETED；部分失败、取消、超时或全部失败时父任务 FAILED 并保留逐项结果。活动 child、损坏/错配 checkpoint 不得消费。消费 marker 与父终态共事务，写失败整批回滚，重复结算只返回同一份冻结快照。事务回读前后检查前台代次及原调用 Job，最后一次检查作为结算决策边界；持久取消与消费由同一 Room 事务串行化。终态已提交后的暂停或迟到取消不会改写原终态。

UI 结束统一回读父 ID 对应 Room 事实，等待可传播失败的会话保存事务后才宣告结果已保存；旧刷新代次不能覆盖这次终态。停止先持久取消，再取消当时捕获的 Job，异步旧停止请求不能误取消新任务。没有自动记忆候选、影子网络请求、额外附件或聊天草稿消费。

## 恢复边界

启动恢复按批次或 child 标记拒绝单 Run 原地恢复、普通重试及受控重放，标记正文损坏也不能降级到普通原生 Run。未完成父/child 关闭，已完成 child、原 Tool Ledger 和上下文保持不变。即使启动恢复尚未执行，新进程也拒绝领取旧进程未领取的槽位、拒绝用旧进程父来源创建新批次。用户须创建新的前台根任务后登记新批次；旧 slot 不重领、旧工具不重放。

真实独立进程验收使用严格 UUID 数据库和指定 Redmi，受控 force-stop 分开记录 PID。这与自然 LMK、Doze、系统重启或完整 Activity 联合验收不同，详细证据见[验证报告](verification-report.md)。

## 验证与独立待办

第二片 JVM 43 项、Redmi 定向 34 项和真实 `gpt-6-luna` 1 项通过。真实模型项使用真实 Activity 前台 gate、UUID Run/Skill 账本和仅时间工具，两个 child 各一次原生读取、typed PASSED，父结果消费幂等。Compose 项验证页面/弹窗与 Fake actions 路由，未冒充真实 ViewModel 从产品点击到模型的完整联合；Debug 探针不初始化正式 ViewModel，Application 启动对账仍沿生产路径。

第 5 点两片只读实现基线收口，下一主线为 Workspace/Browser/GenUI。精确共享预算扣账、令牌/费用预算、完整产品界面联合、自然 LMK/Doze/系统重启、后台/可写 child 和正式 Release 保持独立待办。
