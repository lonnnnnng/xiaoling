# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.9`；文档内容已同步到当前 `main` 工作区的 Room v20、候选记忆治理、待审批 Run 恢复、最多 4 步顺序工具闭环、声明式 Skill 管理，以及支持 1 至 8 步定义、步骤快照、可审计重试的一次性与 Daily/Weekly Workflow 实现。2026-07-19 已完成多步骤 Workflow 真机验收、Run 请求遥测与故障注入、执行回执/幂等证据 contract，以及 `notes.create` 与 `memory.remember` 的受限验证阶段恢复。八类 `memory.remember` 恢复失败已通过 `run.recovery_failed` typed event 保存稳定错误码、原因和建议动作。v20 新增独立 `agent_tool_calls / agent_tool_results`，从新产生的 typed RunEvent 原子双写调用、结果、回执、错误、耗时和验证状态；任务中心对有账本的新 Run 使用 Ledger-first 明细并展示 proposed→validated→result→verified，旧 Run 无账本时保守回退 typed RunEvent，缺少 ToolCall ID 的旧结果明确显示“关联未知”。`AgentRunResumePolicy` 继续读取 RunEvent，通用执行栈与 Workflow 后续步骤仍不恢复。

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
- 项目根目录的 `AGENTS.md` 仅保存本机代理指令并排除 Git 跟踪；不得把其中的凭据复制到文档、日志或提交记录。
- 临时调研产物放在仓库外或未跟踪的 `outputs/`，确认结论后再合并进上述长期文档。
