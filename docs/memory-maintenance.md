# 有界后台记忆整理

第 287 阶段第五片，延续 [Memory CAS](memory-cas.md)、[输入快照](agent-context-snapshot.md)、[压缩](agent-context-compression.md)和[恢复](agent-context-recovery.md)。本片提供一次性、本地的整理建议；具体运行结果与边界见[验证报告](verification-report.md)。

## 用户入口与来源

候选记忆仍默认关闭。开启后，记忆管理页提供“整理已完成任务”和“停止整理”；没有周期任务、启动自动扫描或模型调用。刷新/重新进入页面只修复用户已经请求的 RUNNING 工作项并恢复状态，不创建新的整理授权。关闭候选开关先请求持久停止，Worker 冷启动也复核当前开关。

每个请求冻结近 7 天时间窗口，最多涉及 20 个已完成主 Run，包括前次留下的 outbox。SQL 以 `(completedAt, id)` 稳定顺序推进，只选 COMPLETED、无 parent、无持久取消的 Run。消息必须属于同一会话、role=user、origin=USER，没有图片/文档，UTF-8 最多 8 KiB。旧 LEGACY、缺失或超长输入保守跳过；不截断原话再提取。

整理只读取原始用户陈述，去掉 Direct 命令前缀后复用 `AgentMemoryCandidatePolicy`，提取明确偏好或资料陈述。目标、模型回答、Tool Ledger、上下文快照和压缩片段不会进入候选。敏感内容仅保留类别、空正文与固定摘要，原话只参与来源 hash，不复制到 outbox。

后台不全库比较正式事实，建议保持待确认；保存前需由用户核对重复或冲突。用户接受仍走原有候选接受入口，去重并创建新记忆，不覆盖旧事实。已有 ACCEPTED/REJECTED 等候选决议原样复用，不能复活为 PENDING。

## 游标、outbox 与故障

Room v44 新建单一 checkpoint 和 outbox，迁移不改旧事实、版本、候选决议或冻结上下文；新增 Run 完成索引支持有界 keyset 读取。每次扫描一条来源，outbox 插入、预算扣减和游标推进同事务，失败不跨过该条来源。提取不产生建议也占一次扫描预算。

outbox 绑定创建请求、Run/消息/会话/完成时间、来源 hash、候选字段和 payload hash。回放先核对当前请求、预算、窗口和原话，再重新计算建议；损坏拒绝，不修复正文。来源变化/删除记为 SOURCE_CHANGED，旧建议超过新请求窗口记为 OUTSIDE_WINDOW，不再生成候选。旧待投递项占新请求预算，已在当前扫描阶段计数的项不重复扣减。

候选插入与 DELIVERED 回执同事务；写候选后回执失败整笔回滚。按规范化内容或敏感类别/会话查找已有候选，保留其原决定；无已有候选时以 Run hash 生成固定 ID，使用 ABORT 插入。原 Run、正式 Memory、FTS、operation、治理与撤销文件均不写入。

outbox 总计最多 1024 行，含已投递和固定处置记录；满容量拒绝新增且不推进该条游标，不自动删除旧记录。候选列表仍沿用原有呈现限额；本片没有全历史回填、自动合并/删除、索引修复或容量回收入口。

## 调度与取消

每请求一个 OneTime WorkRequest，唯一名绑定 requestId，KEEP 防止重复点击/页面重建重复入队。只用本地 Room，无联网约束；每轮最多 60 次本地操作、15 秒，瞬时故障最多 3 次尝试，最终 FAILED 保留已提交进度。固定窗口和 20 Run 预算跨尝试持久保留；不会因重试扩大窗口。

停止命令与页面观察使用不同 Job；刷新不能撤销尚未提交的停止栅栏。停止先在 Room 将同请求置 CANCELLED，再取消系统工作项；后台每个事务回读请求状态，迟到旧 Worker 不得扫描/投递。停止失败且未证明栅栏成功时保留停止入口。系统协程取消传播，已提交前缀/outbox保留；后续显式请求按当前窗口回放。Room 与 WorkManager 不是跨存储原子提交，checkpoint 已保存但入队失败时由页面重新进入/刷新按同请求补入队。

## 验证边界与官方依据

UUID Room、双连接、故障 trigger 与生产 Worker 的注入测试验证本地执行；三轮独立 instrumentation 验证真正新 PID 的 outbox 恢复和幂等，不把数据库关闭重开称为进程重启。只有成功验证全部持久事实后才删除该三轮 UUID 夹具；正式配置/数据保留。

一次性受控验收不代表自然 JobScheduler 调度、LMK、Doze、系统重启、任意规模耗时或语义记忆质量。当前依赖 Room 2.8.4、WorkManager 2.11.2，发布版本继续 `0.1.22 (23)`。

- [WorkManager unique work 与取消](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)：KEEP 复用未完成唯一任务；取消正在运行的 Worker 是协作式停止。
- [Room withTransaction](https://developer.android.com/reference/androidx/room/RoomDatabaseKt)：异常或协程取消使事务不成功，事务排队执行。正文保存在忽略的本轮证据目录。
