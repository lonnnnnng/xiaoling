# 文档索引

本目录只保留当前有效、需要持续维护的文档。历史检索清单和重复比较报告已经合并到统一的参考项目分析，不再按日期散落保存。

当前发布基线：`v0.1.10`；文档内容已同步到 Room v27、Text/Reasoning/Image/Document/Tool 消息 parts、Agent Profile v1、候选记忆治理、多步骤待审批 Run 恢复、最多 4 步顺序工具闭环、声明式 Skill、1 至 8 步 Workflow、本地知识库、答案级知识引用，以及设备 Agent 观察与有限动作层。2026-07-19 至 2026-07-21 已完成多步骤 Workflow、Tool Ledger、受限恢复、失败 Run 重试、Agent Profile、消息 parts、本地知识库数据/管理 UI、`knowledge.search`、答案级引用 UI、首批设备工具、“前序工具已验证、链尾工具待审批”的原 Run 恢复、“所有工具结果与 `PASSED` 验证均已持久化、仅控制面尚未收尾”的原 Run 恢复，以及单调累计执行预算。执行预算只累计模型与工具段，审批等待不计入；每段完成后以 typed RunEvent 持久化累计快照，审批及受限恢复继续使用原 Run 剩余预算，损坏或晚于预算快照落库的 ToolResult 证据 fail-closed。最后一种恢复只补齐最后验证 Step 并生成本地可信总结，不重放工具、不追加第二条验证、不调用模型，也不续跑 Workflow 后续步骤；提交状态未知、验证未落库和旧模型协程仍保持 fail-closed。设备 Agent 默认关闭，要求独立应用开关和系统 Accessibility 授权，具备四态健康检查、200 节点/4000 字符有界快照、30 秒节点 ref、页面 generation 失效、隐私过滤、应用白名单、敏感输入拒绝和动作后重新观察验证；仅开放给前台直接 `/agent`，Workflow、后台、旧 Profile 和未启用状态不会获得能力。AccessibilityService 只使用标准节点动作与系统返回/主页，不执行坐标手势或截图；支付/高敏窗口与已知隐私应用整窗拒绝，敏感节点不返回正文、动作或 ref。当前门禁为 374 条 JVM 测试和仅 Redmi 执行的 125 条 instrumentation；真实服务另通过 instrumentation 外诊断入口验证普通页面、敏感字段、支付窗口、计算器、设置、时钟和系统桌面。真实 `gpt-5.5 + Responses` Run 已完成 `device.open_app` 的模型规划、审批、执行、验证和总结。下一阶段继续记录更长真实任务的耗时、系统回收和恢复证据；完成通用执行恢复与长任务可靠性前，设备工具仍不进入 Workflow 或后台自动化。Embedding、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

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
