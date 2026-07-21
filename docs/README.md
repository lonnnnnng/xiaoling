# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.10`；文档内容已同步到 Room v27、消息 parts、Agent Profile、长期记忆、1 至 8 步 Workflow、本地知识库、答案级知识引用，以及设备 Agent 观察与有限动作层。当前恢复边界保持 fail-closed：提交未知、验证事实不完整和旧模型协程不会原地恢复；确认后只创建关联新 Run。任务中心现支持“需确认”筛选，并把不能原地恢复的策略原因、稳定处置码、证据边界和下一步动作作为 typed `run.recovered` 快照直接展示。当前门禁为 395 条 JVM 与仅 Redmi 执行的 128 条 instrumentation；设备工具仍只开放给前台直接 `/agent`，Workflow/后台自动化、Embedding、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

本阶段新增 Workflow 步骤结果落库后的进程终止对账、重试证据可见性、启动证据快照和 Worker 重入收敛：不可原地恢复的活动 Run 会在收敛前把证据码写入 typed `run.recovered`，Worker 重入只按当前 ScheduledTask 关联链定向关闭旧 Agent/Workflow/Task，不扫描其他 Run，也不使用 `Result.retry` 复制 Agent Run。后续仍重新核对 Ledger，分类漂移升级为 `EVIDENCE_INCOMPLETE`。任务中心直接展示稳定分类、原因和建议动作；不改变 `COMMIT_UNKNOWN`/`EVIDENCE_INCOMPLETE` 的确认门禁，不恢复旧 Executor。设备工具仍不进入 Workflow 或后台自动化，下一阶段继续做真实系统回收位置和更长 Worker 任务的 Redmi 验收。

最新状态补充：Redmi 已完成一次同一 WorkRequest 的真实 Worker 冷启动重入。旧 PID 在首步 Agent `THINKING` 时被强制终止，新 PID 自动重入并在 `3360ms` 内按 Agent→Workflow→Task 收敛；关联 Agent Run 仍为 1，后续 6 步未执行。由于 instrumentation 前台身份使 `am kill` 无效，本次使用 `run-as kill -9` fallback，因此不把它写成 Android 自主回收。后续重点是更长/自然系统回收样本和通用未知提交处置，设备工具、Foreground Service 与精确定时边界不变。

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
