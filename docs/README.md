# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.9`；文档内容已同步到当前 `main` 工作区的 Room v22、Text/Tool 消息 parts、Agent Profile v1、候选记忆治理、待审批 Run 恢复、最多 4 步顺序工具闭环、声明式 Skill 管理，以及支持 1 至 8 步定义、步骤快照、可审计重试的一次性与 Daily/Weekly Workflow 实现。2026-07-19 已完成多步骤 Workflow、Tool Ledger、受限恢复、失败 Run 重试、Agent Profile 和消息 parts 的 Redmi 真机验收。v22 `message_parts` 为每条消息保存稳定 ID、顺序和 Text/Tool 结构；旧 `messages.text` 继续作为兼容投影，SQL 迁移只回填 Text，不解析历史 JSON 猜造 Tool。新 Agent 结果只有在 `MessageOrigin.AGENT_RESULT + VerifiedAgentContext` 一致时才生成 Tool part，普通聊天不能把自由文本或伪造上下文提升为工具事实。前台会话快照只增量 upsert，用户删除通过显式会话 ID 持久化，后台 Workflow 新建或追加的消息与 parts 不会被旧前台快照清除。Reasoning/Image/Document parts 仍待后续阶段。

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
