# Android 恢复完成约束与持续输入隐私

第 299 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`，真实模型 `gpt-6-luna`，同签名 Debug `0.1.22 (23)`；Room schema、发布版本不变，iOS 产品暂停。

## 恢复完成标准

生产 UseCase 的审批、已提交结果回读、已验证结果收尾三个入口，在恢复锁内从同一 Room 读取 Workflow Run/Step 与冻结输入。全量关联拒绝重复 Step、孤立 Run header、来源冲突、会话错配、停止的 Workflow/Step、坏序号、坏契约和分组数量不符。当前定义、Profile 和目标文字都不能重建或替换原完成标准。显式契约字段不是对象时拒绝解码；合法老快照保留空分组兼容语义。

上下文损坏在审批消费或 Executor 之前拒绝，并关闭原 Run 的活动 Step 与 Run，保留未消费的原审批和历史工具事实。Runtime 同时核对持久来源，不能用默认 Direct 参数降级已记录的 Workflow；三个恢复 state 保留冻结工具顺序。本地恢复总结与新鲜完成共用完成检查，缺动作/后置观察即失败，不恢复模型规划或重放动作。审批恢复继续原预算与循环指纹，已使用的完成纠错额度也从原事件还原。

## 持续显示保护

原下一次完全匹配哈希投影改为本 Run 的持续布尔保护。`type_text` 越过 Executor 之前开启，故输入后回读失败或取消也不撤销；此后 `device.snapshot` 和 Direct 动作的内嵌 `after_snapshot` 均省略 window_title/text/description/hint。拼接、空白规范化、局部显示和多次输入不依赖原文字匹配。Controller 原快照、精确回读、ref、actions、bounds 与 Workflow candidate 的实际输出比较保持；同 Run 重绑不能清除保护。

恢复从原 Run 的输入 ToolCall 审计还原保护，不持久化新增明文或指纹集合。审批前已记录但未执行的输入也保守触发保护。Run 结束/切换清除；**不是跨 Run 或所有派生工具的全局隐私**。输入后模型不能继续按文字标签找目标，这是保守省略显示文字的功能代价；已知 ref、动作和位置仍可用。公开验收 marker 仍存在目标正文，工具输出私密不等于全部 Room/上下文无正文。

## 验收口径与证据

受控 Room 验收通过生产恢复 UseCase 读取冻结契约；真实模型在随机 nonce 公开 Probe 上完成 snapshot → type_text → snapshot，逐次审批和 native 输入各一次。另两次同进程观察检查持续保护，两次重开 Room 后的 Registry 观察检查保护状态还原。这四次补充观察不计入模型工具账本，后两次不冒充生产恢复模型续跑。自然 LMK、Doze、系统重启仍需独立验收。

真实输入 `live-initial` 同轮通过，主机耗时 41.43s，USB 配置 ACK 与 JUnit `OK (1 test)` 都满足。Run `run-76e09c8c-14b8-4c77-9eaa-36f39ad5037d` 的三次模型工具调用均成功/PASSED，原始编辑框精确回读和拼接 TextView 同时存在，工具输出无四类显示字段；隔离 Tool Ledger 关闭重开相同。逐次审批是受控 Gate，不能替代第 293 阶段生产浮层验收。

Room 专项为 `OK (3 tests)`：已验证事实数为 1/2 时分别因缺动作/后置观察失败，事实数为 3 时完成，同一工具账本保持不变；重复/孤立关联拒绝；坏输入快照通过生产 UseCase 使 Run 与活动审批 Step FAILED，原审批保持 PENDING、没有工具结果。这里使用受控公开工具事实，不记为自然进程死亡或真实设置动作恢复。

定向 JVM 221/221、完整 App JVM 1628/1628，均零失败/错误/跳过；主机门禁 12/12，Debug、AndroidTest 与 Debug Lint 通过。回归还覆盖已用纠错额度、默认恢复参数降级拒绝、committed 只读回读的完整/缺项链、输入后回读失败/取消和 Direct 动作内嵌快照旁路。

证据保存在 `outputs/verification/stage299-recovery-privacy/`，不覆盖前阶段成功或失败。初轮旧 Workflow 夹具缺上下文、漏传 Store 的编译失败、夹具五参数委托错误以及被终止的旧失败构建日志均保留；后者没有完成 Lint，不能作为通过轮。完整汇总、制品审计和最终授权收尾见本阶段 `verification-summary.json`。

首次最终偏好回读值完全一致，但新增了空 `xiaoling_ui.xml`；原日志保留。收尾只在本轮基线明确原文件缺失、当前偏好为空、文件字节匹配本次回读且 Room 值未变时撤销该临时文件；后续验收夹具也按原文件存在性收尾。该修正只影响测试清理，不改变已验收的生产 APK 或输入动作。

普通编辑器限定应用/目标契约、复杂任务/任意 App、跨 Run 正文隐私、自然系统恢复、Remote 稳定部署与真实通知、签名 Release 继续独立。仅中文本地提交，不 push/tag/Release，保留 refs。

相关：[分步完成与输入隐私](android-workflow-completion-acceptance.md)、[方向与边界](android-directional-scroll-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
