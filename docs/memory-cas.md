# Memory CAS 与冲突处理

第 287 阶段第一片实现版本化 Memory，属于开发主线第 3 点 Context/Memory。context snapshot、压缩、进程恢复和后台整理仍按[路线图](personal-agent-roadmap.md)继续推进；本片不代表整个第 3 点收口。

## 版本与写入

Room v41 为每条正式记忆保存正整数 `version`。40→41 迁移为历史记忆补 `1`，保留正文、来源、治理字段、时间、FTS 和 operation；历史版本不是根据时间或编辑次数推算。

| 操作 | 版本行为 |
| --- | --- |
| 新建记忆 | 从 `1` 开始，使用 ABORT insert，不能覆盖已有身份 |
| 编辑正文、标签、类型、置信度 | 必须提交读取时的 `expectedVersion`，有效变更递增 `1` |
| 启用、置顶、过期策略 | 同样使用 CAS；有效变更递增 `1` |
| 同值写入 | 仍核验预期版本；相同业务与治理字段不改版本或更新时间 |
| 搜索引用时间、读取、重复 remember | 不递增版本；引用时间不会覆盖其他字段 |
| 删除 | 使用预期版本条件删除，不自动选择最新版本 |
| 撤销删除 | 仅允许最新持久快照、且目标身份不存在；恢复版本为删除快照版本加 `1` |

`AgentMemoryManager` 的编辑、治理与删除均要求显式预期版本。事务内先读取当前事实，再通过 SQL 的 `WHERE id = ? AND version = ?` 更新或删除。编辑的 `version = version + 1` 与正文、治理字段和独立 FTS 索引同事务提交；故障回滚后不能留下新版本或半份索引。版本达到 `Long.MAX_VALUE` 后拒绝修改与恢复。

不存在仍返回 `null`；版本不一致抛出 `AgentMemoryVersionConflictException(current)`，携带提交检查时的当前记录。调用方不能自动换版本重试旧操作。

## 管理页与 Agent

编辑弹窗冻结正文和版本。保存期间禁用正文、标签等输入；成功仅关闭仍与提交快照一致的草稿，其他治理操作不关闭编辑草稿。冲突保留原正文和旧版本，显示当前正文；“重新载入当前内容”是显式动作，随后用户才能重新编辑、保存。

管理页的启用、置顶、过期与删除确认均传递呈现或确认时的版本。删除冲突更新当前列表并解除 busy，不删除最新记录、不生成撤销记录。

前台 Direct Agent 的 `memory.delete` 沿用同 Run 唯一 `memory.search` → `memory.get` → 逐次审批链：搜索冻结版本，详情须仍为该版本，执行传递详情版本给 Store。执行冲突撤销旧候选并返回失败，无 COMMITTED 回执；重新决策必须重新搜索、读取和审批。禁用、过期记忆的当前正文不回传给 Agent；等待跨过自然过期时间，即使版本未变也拒绝删除。本片没有新增 Memory 写工具或后台整理入口。

## 撤销与历史账本

撤销文件的 `version` 表示文件格式，新的格式 `2` 独立保存 `memoryVersion`；格式 `1` 读取时业务版本为 `1`，与数据库迁移一致。仅最新持久快照整条匹配才能恢复，旧对象、伪造快照和现存同 ID 都不能覆盖。恢复递增版本，删除前的编辑草稿失效，避免恢复后复用旧版本。

AtomicFile 在 Room 删除前保存撤销快照；文件与 SQLite 是两个持久边界。`latestDeleted()` 在 Room 事务内读取当前存在性：目标仍存在则快照不可撤销；恢复失败保留快照，恢复提交后由该对账清除旧快照。这不是文件和数据库的跨存储原子提交，也没有自然进程回收证据。

新删除 operation 的 payload hash 绑定 ID 和预期版本，Room 保存 `expectedVersion`。历史 operation 的该字段为 NULL，只读验证保留旧 ID-only hash；缺版本的新删除请求拒绝，不重放历史写入。原 remember 的结果 hash 继续绑定业务字段，不因版本、置顶或引用时间变化而失效，启用与过期状态仍单独核验。

## 验收与边界

聚焦 JVM 覆盖 Agent 两个版本漂移窗口与治理隐藏正文。Redmi 测试使用独立 UUID 数据库/撤销文件验证双连接竞争、FTS 故障回滚、数据库关闭重开、旧文件兼容和历史迁移；生产 ViewModel 用例仅创建、精确清理 UUID 记忆，不调用正式撤销删除。本轮设备、命令和最终结果见[验证报告](verification-report.md)。

数据库重开证据不等同进程恢复。Registry 竞争测试不经过完整 Runtime 审批 UI。本片没有真实模型请求、后台整理、context snapshot、压缩、自然 LMK 或正式 Release 验收。

Room 迁移依据为[官方迁移文档](https://developer.android.com/training/data-storage/room/migrating-db-versions)，本轮正文抓取保存在忽略的 `outputs/verification/stage287-memory-cas/room-migrations.md`。
