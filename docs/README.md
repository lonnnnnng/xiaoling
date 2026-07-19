# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.9`；文档内容已同步到当前 `main` 工作区的 Room v21、Agent Profile v1、候选记忆治理、待审批 Run 恢复、最多 4 步顺序工具闭环、声明式 Skill 管理，以及支持 1 至 8 步定义、步骤快照、可审计重试的一次性与 Daily/Weekly Workflow 实现。2026-07-19 已完成多步骤 Workflow、Tool Ledger、受限恢复、失败 Run 重试和 Agent Profile 的 Redmi 真机验收。Agent Profile 可冻结 Provider、模型、API 模式、角色提示、上下文策略、工具/Skill 白名单和记忆开关；新 Run 写入唯一 `agent.profile.selected` 快照，审批恢复和已提交结果恢复继续使用原 Run 快照，重复、损坏或越权审计均 fail-closed。v20 引入的独立 `agent_tool_calls / agent_tool_results` 仍是任务中心、受限恢复和失败 Run 重试副作用判断的 Ledger-first 事实源；账本完全为空的旧 Run 才回退 typed RunEvent。Run 质量与模型遥测继续使用没有等价工具账本字段的 Step/LLM typed event 口径，通用执行栈、旧模型协程与 Workflow 后续步骤仍不恢复。

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
