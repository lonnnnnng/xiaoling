# 签名插件 SAFE 前台执行

第 288 阶段第二片，2026-10-18。本片将已确认的声明映射到宿主只读工具，形成第 4 点 Skill/插件的原定实现基线。Room 保持 v44，发布版本仍为 `0.1.22 (23)`。签名、来源与版本账本见[签名契约](agent-plugin-signing.md)，实际证据见[验证报告](verification-report.md)。

## 显式入口与能力

设置 → Agent 插件：签名已确认、启用且声明 `READ_ONLY_CAPABILITIES` 的插件可填写最多 2000 字符的目标，点击“运行只读任务”后回到当前会话。按钮使用当前 Agent Profile。安装、启用或打开页面均不自动执行；manifest 的 description、publisher、source 不作为模型指令，不下载 source、不加载代码。无签名声明仅供预览。

选择绑定页面显示版本的 manifest SHA-256、公钥 SHA-256、注册表进程身份、插件许可代次和 MainActivity 前台代次。后端重新验签并核对摘要；页面打开后发生升级或停用，旧点击不能授权新内容。一次选择只可创建一个新 Run。

执行目录为以下交集：

1. 已启用、签名有效且具备只读权限的 manifest capabilities。
2. 宿主固定 13 项 `AciPolicy.DEFAULT_READ_ONLY_CAPABILITY_NAMES`。
3. 当前 Profile 的 allowedToolNames。
4. 宿主静态与当前可用定义，风险均为 SAFE，审批策略均为 NONE，定义指纹一致。

固定名单包括时间/应用信息/电量/网络/存储、会话列表与搜索、笔记列表/搜索/读取、记忆搜索/读取、知识搜索。Profile 的记忆开关仍约束记忆工具。未满足动态可用性、权限、定义或 Profile 交集的能力不能执行。界面展示静态交集，启动和每次调用重新核对实际可用性。

宿主定义及目录在启动时冻结，新增能力不能进入旧 Run；已观察到的隐藏、风险升高或定义变化在本轮永久撤销。未被观察到的宿主瞬时定义 ABA 不具备独立 change flow 证据；这些固定宿主定义本片不提供热加载。业务 validator 语义变化仍须递增宿主 `recoveryContractVersion`，指纹不能识别同数量函数的内部实现变化。

## 生命周期、取消与审计

MainActivity 的真实 `onResume/onPause/onDestroy` 维护前台许可。仅传入 `FOREGROUND` 参数不能授权。暂停再恢复、停用再启用均递增代次，旧许可永不复活；升级、回滚、移除和失败写盘同样先撤销。整轮观察者覆盖工具执行和模型等待，失效即取消；运行结束后关闭许可并转发原 Registry 的清理。

执行经 `ProfileScopedToolRegistry → AgentPluginToolRegistry → SkillScopedToolRegistry → MinimalAgentRuntime`，保留参数校验、权限检查、预算、工具账本和 typed verification。Skill 只能进一步收窄插件工具面。禁止共享/远端草稿、附件、Workflow、后台、child、关联重试进入插件执行。

每 Run 最多 3 次工具调用、60 秒，单工具最多 10 秒，结果最多 20000 UTF-16 字符。超时是协作式取消，不是进程沙箱；不能承诺阻塞宿主函数被强行中断。调用后复核许可，超长或撤销后的迟到结果不能形成成功返回或 PASSED。已经发生的只读读取不能撤回。模型客户端的取消观察者在取消开始时关闭 OkHttp socket，覆盖响应头、正文和流式等待。

Run 与 `plugin.execution.selected` 标记在同一个 Room 事务中创建。标记保存 schemaVersion、插件 ID/版本、manifest/publicKey 摘要、目录指纹与实际能力集合，元数据沿既有 Reason codec 回读；不含凭据、私钥或 manifest 描述。标记插入失败时 Run 与 `run.created` 一并回滚。初始化回调或真实 Run context 绑定失败也进入失败/取消终态，避免遗留 QUEUED。

插件任务使用原生无 Embedding 的知识 store，正常规划/总结仍使用当前 Profile 模型。会话与账本正常保存；结束后不自动生成记忆候选或发起 answerability shadow 请求。

## 恢复

进程重建不恢复旧许可、旧规划协程或旧工具执行。只要存在插件选择事件，即使其 metadata 损坏，也按 `RESTART_REQUIRED / PLUGIN_FOREGROUND_SELECTION_EXPIRED` 关闭中断任务。普通重试、受控重放、审批恢复、已提交验证及已验证收尾入口均拒绝继承；用户从插件页重新选择后创建新 Run。旧 Tool Ledger/PASSED 仅保留历史事实。

三进程验收先停在原生时间工具已验证的边界，再在新进程关闭旧 Run，最后重新显式选择运行并核对旧终态/账本不变。受控 force-stop 不能作为自然 LMK、Doze 或系统重启证据。

## 独立后续

原定签名 manifest、版本回滚、SAFE 前台宿主扩展的实现基线完成后，主线按[路线图](personal-agent-roadmap.md)进入第 5 点多 Agent。以下继续独立追踪：完整产品界面点击到真实模型的联合专项、长期自然系统恢复、正式 Release 验收、发布者密钥轮换/永久撤销、热加载定义代次，以及外部代码沙箱、插件市场和后台执行。它们不由本片只读网关隐式开放。

官方正文已通过 smart-search 抓取（2026-10-18）：[Activity 生命周期](https://developer.android.com/guide/components/activities/activity-lifecycle)、[Kotlin 取消与超时](https://kotlinlang.org/docs/cancellation-and-timeouts.html)。实际兼容性以 JVM/Redmi 证据为准。
