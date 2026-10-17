# Agent 上下文与进程恢复

第 287 阶段第四片，属于第 3 点 Context/Memory。前序 [Memory CAS](memory-cas.md)、[输入快照](agent-context-snapshot.md)和[提取压缩](agent-context-compression.md)提供持久证据，本片将这些证据接入已有恢复入口。后续为有界后台整理，整个第 3 点尚未收口；实际验收见[验证报告](verification-report.md)。

## 恢复所读的证据

`RoomAgentRunRepository.loadDetail` 在同一 Room 事务回读最新 Run、Step、Event、Approval、完整 Tool Ledger、输入帧和压缩来源，再给出 `LEGACY_UNCAPTURED / VALIDATED / INVALID`。这不是新的执行授权：原 Profile、审批、预算、提交回执和恢复策略仍须分别通过。

每次新输入捕获同时写入 `context.snapshot.captured` 身份锚点，只保存版本、sequence、Step ID、输入摘要和压缩版本，不保存正文或鉴权。输入、来源与锚点共事务提交，锚点插入失败整笔回滚；同 Step 相同输入重试不增加锚点或序号。首次新格式锚点之后的帧必须逐一对应，缺尾帧、删光帧、重复或损坏锚点不能降级成没有快照的旧版本。

校验沿用快照的大小/序号/Step/codec/摘要、压缩来源的一一对应/算法/派生及实时工具参数、完整正文、PASSED 和引用检查。普通输入中的已验证工具 ID 必须仍有同 Run 的成功 PASSED 账本记录。省略投影、附件计数和压缩片段仍按原契约解释，不能变成完整原文、附件或旧模型响应。

`INVALID` 优先返回 `CONTEXT_EVIDENCE_INVALID`，不产生可执行恢复候选；启动收敛原子关闭旧活动 Step/待审批/Run，保留上下文、来源、工具事实和固定处置。字段类型、JSON、摘要或关联损坏不会把正文/解析异常展示给用户；协程取消与数据库 IO 故障继续传播，不伪造业务损坏或清空证据。

## 兼容与执行边界

Room 保持 v43，发布版本保持 `0.1.22 (23)`，不迁移或重写旧 Run。旧版本没有快照且没有锚点时沿用原恢复资格；已有合法快照但没有新锚点时仍校验存储内容。旧版本从未保存锚点，无法检测其全部快照与来源同时被删除；这项历史限制不以猜造 marker 修复。SHA-256 与锚点用于事故损坏检测，不抵抗能同时改写数据库全部证据的写者。

恢复候选分类、marker、状态变更和最终回读共用一个 Room 事务，并重新核对持久取消请求。marker 幂等不等于执行互斥：三个生产 UseCase 恢复入口按 Run 使用进程级互斥，取锁后回读最新证据，再继续原 Runtime。等待者取消或执行异常均释放引用，最后一个使用者离开后回收锁；不同 Run 不互相等待。这不是跨多个同时存活 Android 进程的租约。

审批恢复取锁前核对确认与 detail 的 Run ID；准备 Profile/Skill 后，在批准同一事务再次核对上下文、取消状态、恢复资格和用户确认的完整 ApprovalRequest。变化或错配时不消费确认。批准后的新死亡窗口仍按原提交未知/新 Run 策略处理，不把旧批准变成无限执行许可。

已提交工具仅恢复原支持的只读回查，全部 PASSED 工具仅补控制面和完整 Tool Ledger 的本地总结；不重放已执行工具，也不调用模型重做已完成事实。审批恢复执行原链尾批准动作后继续原规划路径，按当前受限目录与完整已验证工具事实构造新输入，不发送旧快照副本。THINKING 中断、只有请求输入而没有可信响应、短生命周期输入及提交未知继续关闭旧 Run，按原重试门禁创建关联新 Run。

## 验收方式

单进程 Room 测试使用 UUID 数据库，覆盖损坏、帧删除、来源漂移、取消、重复恢复、审批错配和锚点写入故障。主机 `tools/verify_agent_context_process_recovery.py` 只使用 Redmi `wsvwypiz7xwslvl7`，以同一 UUID 数据库执行 seed/restore/verify 三轮独立 instrumentation，并核对各轮 PID 不同。

seed 用生产 adapter/Runtime/Room 与手机 loopback 固定响应生成上下文、压缩来源和工具事实，在持久边界注入执行栈终止；restore 用新进程的生产 UseCase/Runtime 处理有效验证边界、损坏来源、全部输入丢失、取消和模型请求半写入；verify 再次重启核对终态和证据，全部断言通过后只删除本轮 UUID 数据库。失败保留日志及夹具，不静默清理。

此处的 `force-stop` 和故障注入是受控进程验收，不代表自然 LMK、Doze、系统重启或真实模型恢复质量。应用数据、权限与正式配置保留；未增加后台 Agent 重启、自动模型重发或后台记忆整理。
