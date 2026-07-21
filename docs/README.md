# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.10`；文档内容已同步到 Room v27、消息 parts、Agent Profile、长期记忆、1 至 8 步 Workflow、本地知识库、答案级知识引用，以及设备 Agent 观察与有限动作层。当前恢复边界保持 fail-closed：提交未知、验证事实不完整和旧模型协程不会原地恢复；确认后只创建关联新 Run。任务中心现支持“需确认”筛选，并把不能原地恢复的策略原因、稳定处置码、证据边界和下一步动作作为 typed `run.recovered` 快照直接展示。启动恢复先冻结旧 AgentRun/WorkflowRun/ScheduledTask 候选，并排除当前进程真正 `RUNNING` 的 Worker 链；用户停止会先把任务原子写为 `STOP_REQUESTED`，因此系统取消与即时 fallback 同时失败后，启动恢复仍能越过旧进程所有权并继续收敛。即使停止发生在 Worker 认领任务后、Agent Run 关联前，重入也会优先按停止栅栏取消 Workflow、未完成步骤和 Task，不会误记为执行失败。当前门禁为 404 条 JVM 与仅 Redmi 执行的 140 条 instrumentation；设备工具仍只开放给前台直接 `/agent`，Workflow/后台自动化、Embedding、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

第 46 阶段完成 Redmi 长任务与系统策略取证：强制 Doze 会延后同一 WorkRequest，退出 Doze 后任务只创建一个 Workflow/Agent Run；8 步真实模型 Workflow 在约 28.5 秒内于第二步重复调用检测处安全失败。`send-trim-memory` 与退出 Doze 样本均观察到短时 `connection closed`，但无压力对照也出现启动恢复竞态，因此不建立内存压力或 Doze 与连接关闭的因果关系。该竞态曾让 ScheduledTask/Workflow 保持 `CANCELLED` 而迟到协程把 AgentRun 改成 `COMPLETED`；现已在 Room DAO 用原子非终态条件更新冻结 AgentRun 终态，并增加 Redmi 回归。仍缺 Android 自主 LMK 样本，不提前引入 Foreground Service，也不恢复旧 Executor 或 Workflow 后续步骤。

第 47 阶段完成当前进程 Worker 所有权与启动恢复隔离：Worker 在构造 Repository、重入对账或 claim 之前用计数型进程注册表登记 Task；ViewModel 在同一互斥边界冻结启动候选，快照期间新 Worker 必须等待，已登记 Worker 的 ScheduledTask、WorkflowRun 和 AgentRun 从旧候选中排除。Redmi Room 测试确认旧链按原策略收敛，当前链保持活动、可继续完成且不创建第二个 Run。实现不依赖墙上时间，不新增 Room owner token 或 Schema，也不扩大旧执行栈恢复能力。

第 48 阶段完成后台运行中停止和长成功样本：工作流页为 `RUNNING` ScheduledTask 提供“停止运行”，先取消目标 WorkRequest 并等待 Worker 正常收敛，超时或系统取消异常时按 Task→Workflow→Agent 持久化链兜底；Agent 尚未关联的缺链窗口也会关闭 Task/Workflow，`SCHEDULED→RUNNING` 抢占竞态会自动升级为运行中停止。Run、Step、Approval、Event 和 Tool Ledger 在终态后一并冻结，迟到 HTTP/模型/审批结果不能覆盖 `CANCELLED`。Redmi 真实停止样本约 32.6 秒；另一个三步 SAFE Workflow 依次执行 `app.current_time`、`app.list_conversations(limit=3)`、`notes.list(limit=3)`，约 21.8 秒完成。设备支持 LMK 原因报告，历史 11 条退出记录中 `REASON_LOW_MEMORY=0`，因此仍没有 Android 自主 LMK 样本，不提前引入 Foreground Service。

第 49 阶段取得更长的正式 Worker 成功证据：Redmi 上同一 ScheduledTask/WorkRequest/Workflow Run 顺序完成 8 个 SAFE 步骤，总耗时约 62.2 秒；8 个 Agent Run 均为 `COMPLETED`，工具结果全部 `success=true / PASSED`，没有系统重试或复制 Run。先行样本运行约 49 秒，在第 6 步因模型没有调用 `memory.search` 而安全失败，后两步正确取消，同样没有复制执行。最新 LMK probe 显示 `supported=true`、6 条历史退出全部是本轮 instrumentation `FORCE STOP`、`REASON_LOW_MEMORY=0`；仍未取得 Android 自主 LMK，不引入 Foreground Service。

第 50 阶段完成停止异常后的持久化重对账：Workflow 仍活动的 `RUNNING` 任务收到停止请求时，先在 Room 原子写入非终态 `STOP_REQUESTED`，再取消 WorkManager；即使系统取消和即时 fallback 同时异常，停止意图也不会丢失。Worker 重入、启动恢复和停止兜底都识别该栅栏，当前进程注册表只保护真正 `RUNNING` 的链；Workflow/Task 在同一 Room transaction 重新读取栅栏并原子结算，迟到成功只能收敛为 `Workflow=CANCELLED / Task=CANCELLED`，也不会追加成功会话结果。停止发生在 Agent Run 尚未关联的窗口时，Workflow 重对账先读取其唯一 ScheduledTask 关联并按 `STOP_REQUESTED` 取消整条链，不进入“关联 Agent 缺失即失败”的通用恢复分支。若 Workflow 在停止事务前已经持久化终态，则停止请求不伪造新栅栏，而是把半结算 Task 对账到该既有终态。周期任务在停止请求对账完成前不物化下一实例。实现不复制 Run、不恢复旧执行栈、不引入 Foreground Service，`STOP_REQUESTED` 复用现有 TEXT 状态列，Room v27 Schema 不变。

第 43 阶段历史补充：Redmi 完成一次同一 WorkRequest 的真实 Worker 冷启动重入。旧 PID 在首步 Agent `THINKING` 时被强制终止，新 PID 自动重入并在 `3360ms` 内按 Agent→Workflow→Task 收敛；关联 Agent Run 仍为 1，后续 6 步未执行。由于 instrumentation 前台身份使 `am kill` 无效，本次使用 `run-as kill -9` fallback，因此不把它写成 Android 自主回收。该阶段当时的后续重点是更长/自然系统回收样本和通用未知提交处置；当前进度以第 50 阶段段落为准。

第 44 阶段新增任务中心“需确认”队列：只聚合已结束、可重试且 `AgentTaskRetryPolicy` 判定必须确认的 Run；卡片继续展示统一证据分类、原因和建议，确认提交前继续校验证据码。稳定确认后仍只创建带 `retryOfRunId` 的新 Run，旧 Run、旧模型协程和旧 Executor 均不恢复。当前门禁为 394 条 JVM 与仅 Redmi 执行的 127 条 instrumentation。

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
