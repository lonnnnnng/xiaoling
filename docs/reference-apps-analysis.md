# `reference-apps` 个人 Agent 实现分析

`v0.1.15` 是当前发布对照基线：在 `v0.1.14` 的恢复、Shadow 和六项前台 Workflow 设备工具基线上，新增 `swipe` 完整证据/生产链与自然语言个人任务的严格计划和确认边界。发布没有借机复制参考项目的任意 App、后台设备控制、多 Agent 或远程 Channel；本轮按用户要求只构建 Release APK，没有执行额外发布验证。

第 127 至 132 阶段不再按单个原语横向扩权，而采用参考项目中更接近真实产品价值的“目标级垂直切片”。第 127 阶段已经完成自然语言目标、可确认有界计划和既有执行链复用；第 128 至 132 阶段继续完成限定 App 多动作、目标级验证、记忆/知识/应用内提醒、关联恢复和 Redmi 里程碑。这样既保留 `meow-agent` 的风险元数据与后置验证、`X-OmniClaw` 的观察后执行和按需路由、`openclaw` 的任务/Channel 分层，也避免在主链跑通前复制任意 App、后台设备控制或多 Agent 的攻击面。第 132 阶段完成后，才集中评估体验、性能和高级生态。

第 127 阶段采用“计划与执行分离、确认后复用既有 Ledger”的参考原则：模型只生成严格 JSON Schema 的任务名和 1 至 8 步目标，客户端再次进行不信任解析；确认弹层明确显示风险和能力边界，确认前不写任何执行事实。确认后不是把计划交给新的自由执行器，而是在 Room 单事务创建普通 Workflow、Run 和步骤快照，再进入既有 Agent Runtime、逐动作审批和后置验证。Redmi 真实链中首个 Runtime 模型规划超时保留失败 Run，同一 Workflow 的第二个手动 Run 独立完成 `app.current_time`，验证了 `meow-agent` 式不可变 Ledger 与 `openclaw` 式任务层/执行层分离，而没有扩大工具面。

当前计划上下文只包含用户目标和 Profile 工具白名单，长期记忆与本地知识正文仍按路线图留给第 130 阶段。这里借鉴的是任务/执行分层与审计边界，不把参考项目的上下文路由能力误记为已经交付。

第 126 阶段继续采用参考实现中“能力进入生产白名单前必须有完整可验证证据链”的原则。`device.swipe` 只有在第 122 至 125 阶段已经完成纯安全策略、执行期 HMAC evidence、完成态内存交接、Redmi 限定动作和答案级脱敏投影后，才加入前台手动 Workflow 的生产默认 Registry。它继续是 SAFE 零审批动作，仍要求同 Run snapshot/ref、30 秒 TTL、当前 generation、同窗内容变化、共同匿名锚点方向主位移、Executor/typed 验证和动作后观察；Room/UI 不保存方向、viewport/HMAC、snapshot/ref、节点正文或坐标。聚焦 Registry `36/36`、六个相邻测试类 `101/101`、Debug/AndroidTest APK 和仅 Redmi `wsvwypiz7xwslvl7` 的真实生产 `snapshot -> swipe` 均通过，日志为 `approvals=0 / registryCompletion=PASSED / answerDecision=VERIFIED / privacySafe=true`；更新后的项目文档语料首轮/最终单项均为 `OK (1 test)`，耗时 `2.307s / 2.3s`。前台 Workflow 现精确开放七项，后台/定时设备自动化、任意 App、坐标与截图继续关闭。

`v0.1.14` 是上一发布对照基线：它在 `v0.1.13` 的结构与启动基线上完成通用执行恢复矩阵、Room v33 answerability Shadow 跨进程匿名账本与单次采样窗口，以及前台 Workflow `snapshot / open_app / back / home / tap_ref / type_text` 生产闭环。这个版本继续采用“纯决策前置、宿主副作用后置、持久化事实优先、敏感配置类型级脱敏”的工程原则：提交状态未知不自动重放，尚未提交的白名单写工具只在用户确认后创建关联新 Run，持久化失败事实只原子结算，不恢复旧 Executor、模型协程或 Workflow 后续步骤；`type_text` 原文不进入持久化审计。当时发布门禁为 JVM `837/837`、Lint `0 error / 56 warnings / 0 information`、三类 APK、Release lintVital、zipalign、v2 正式单签名和仅 Redmi `OK (271 tests)`。

第 125 阶段继续采用参考实现中“执行期强证据与持久审计摘要分层”的原则。`swipe` 的同窗、同目标、内容变化与方向位移仍在 Controller/Registry 当前执行链以 viewport/HMAC 证明；只在 Executor 验证和 typed `PASSED` 后，通用 `action=swipe` 摘要才能从 Tool Ledger 重建为答案级 Decision。Room 不升级 schema，Workflow output 不保存方向、viewport、HMAC 或 snapshot/ref；UI 只显示“滚动”与通用后置摘要，并把历史引用标为不可复用。该分层避免了把高权限临时证据变成可长期关联的数据，也没有为 SAFE swipe 伪造审批卡。聚焦 JVM、Debug/AndroidTest APK 和仅 Redmi 的 Room + Compose `OK (2 tests)` 通过；生产默认工具面仍保持六项，待下一独立切片再开放并验收真实生产 Workflow。

第 124 阶段继续采用参考实现中“高权限动作证据不经通用持久格式回流”的原则，把 Controller 已生成的前后 viewport 仅在 Registry 当前执行态交给完成策略。Registry 先把 `beforeSnapshotId` 及前后 viewport 的包名/window/generation 与本次授权 snapshot 和真实后置 snapshot 逐项绑定，错串证据不进入专属策略；通用 Result codec 只识别无节点正文的 `swipe` 摘要，不携带 HMAC。Debug tracer 用真实 Runtime/Room Ledger 在 Redmi 的系统设置应用详情页完成 `snapshot -> swipe(up)`，结果为 `verified=true / approvals=0 / registryCompletion=PASSED / privacySafe=true`。聚焦 JVM `91/91`、Debug APK 和 Redmi 限定页通过，但生产默认集合、DecisionPolicy、Room/UI 与后台自动化继续关闭；这符合“先证明一个受控页面，再单独评审生产接线”，而不是由单个真机样本推断任意 App 可用。

第 123 阶段把第 122 阶段的纯 swipe 契约下沉到真实 Controller evidence seam，但仍保持“先证明、后开放”。每个 Controller 实例使用随机 HMAC 密钥，结构化身份不含 bounds、generation、snapshot/ref 或节点明文；只选当前滚动目标的未脱敏语义后代，锚点身份绑定该目标，重复语义位置全部丢弃。当前 snapshot、ref 与 viewport 共用内存生命周期并在 Run 切换时撤销，inspection 在同一生命周期锁内完成一致性核对并在锁外复读 window generation，页面在证据构造期间变化时 fail-closed。Controller 和 Workflow 复用同一方向验证器，直接滚动从 generation-only 收紧为同窗内容变化与共同锚点方向主位移。Registry 只在显式测试集合接收动作前 viewport，生产默认集合和当时的 Result codec 没有开放 swipe；完整 evidence 不进入 Room、日志、Workflow output 或 UI。聚焦 JVM `89/89` 通过，第 124 阶段随后完成 Registry 完成态内存交接和 Redmi 限定 App 真实滚动。

第 122 阶段延续参考实现中“先冻结可证明的后置条件，再接执行宿主”的做法，为前台 Workflow `device.swipe` 增加独立纯策略，而没有因为直接 `/agent` 已能滚动就复制其 generation-only 成功判断。策略要求当前可滚动且未脱敏目标、同应用/同 window/同目标、generation 前进、可见匿名内容变化，以及至少一个共同锚点按请求方向发生不小于 `8px` 的主位移；任一达到阈值的共同锚点若反向或横向占优，整体 fail-closed，不能由另一个正确锚点掩盖，四个方向分别覆盖。`swipe` 按既有 ToolDefinition 保持 SAFE 零审批，但同 Run/ToolCall、snapshot/ref、TTL 与 Executor/typed/后置观察仍是硬门禁。专属授权只保存方向和动作前 viewport SHA-256 摘要；完整锚点只允许驻留当前执行链。聚焦 JVM `55/55` 通过，生产 Workflow 仍精确为六项，Controller、Result、Room、UI 和 Redmi 真实滚动留给后续独立 evidence seam，后台设备自动化继续关闭。

发布后的有界对话框簇收尾继续采用“功能拥有业务状态、应用根拥有平台协调”的边界。Agent/Workflow 重试、长期记忆编辑/删除和本地 Skill 删除进入对应 UI module 的 contract、projection 与 dialog host，但仍由 `XiaoLingContent` 全局挂载以跨 pane 保持待确认状态；备份恢复、Android 文件选择器、全局通知和跨页面导航没有被抽成参数型 wrapper。`XiaoLingApp.kt` 由 `1,103` 行降到 `817` 行后触发停止条件，后续不继续按行数拆分，而转向通用执行恢复。本地 JVM/Lint/三类 APK/Release lintVital 已通过；仅 Redmi `wsvwypiz7xwslvl7` 的新增对话框聚焦测试为 `OK (7 tests)`、测试耗时 `9.247s`，默认完整 instrumentation 为 `OK (229 tests)`、测试耗时 `89.151s`，最终文档重新打包后的项目语料单项为 `OK (1 test)`。在线模拟器未被使用。

通用执行恢复矩阵首个切片采用成熟 Agent 常见的“运行状态不是副作用事实”原则：`EXECUTING` 或 `tool.call.validated` 都不能单独证明 Executor 已启动，必须同时核对持久化 `TOOL_EXECUTE` 步骤和 ToolResult。真实执行步骤缺结果时冻结 `COMMIT_UNKNOWN` 并保持 `RESTART_REQUIRED`；proposed-only 或执行步骤尚未落库时，恢复处置保持证据无效、重试证据保持 `NOT_COMMITTED`。这避免了两类常见错误：把进程中断窗口误报为已提交，以及因无结果就自动重放写工具。legacy typed event 也只有在稳定 ToolCall ID、唯一链尾和执行步骤同时成立时进入提交未知；所有分支都保留旧 Run，不恢复旧协程或补造结果。完整 JVM `683/683`、Lint、三类 APK、Release lintVital 与仅 Redmi `OK (231 tests)` 已通过，最终文档语料为 `OK (1 test)`。

最新横向工程继续采用成熟 Agent 中“启动前决策纯化、宿主副作用后置”的原则。`AgentLaunchPreflightCoordinator` 统一五个启动入口的会话、Profile、工具注册与 Provider 校验，但不拥有导航、确认弹层、附件、Room 或 Runtime。普通 `/agent` 保留可创建会话的轻入口语义，需要原上下文的 Workflow、重试与恢复则先校验会话；恢复审批优先复用原 Run Profile 快照，避免新选择悄然改写旧 Run 的恢复身份。

协调器只冻结校验时刻的运行配置，长 Workflow 不被改成逐步骤重校验。解密 API Key 仍只在进程内可达，`ProviderRequestConfig` 的字符串表示主动脱敏 Base URL、API Key 与自定义 Header，降低 URL userinfo/query、异常或日志误用的暴露面。聚焦 JVM `10/10 + 1/1`、完整 JVM `656/656`、Lint `0 error / 50 warnings`、三类 APK、仅 Redmi 默认完整 `196` 条（`184 passed / 12 skipped / 0 failed`）与最终文档语料 `OK (1 test)` 通过。这个拆分提高的是启动一致性和可测试性，不构成设备后台自动化、任意 App 扩权、Shadow 持久化或第 102 项能力。

最新横向工程继续采用成熟 Agent 中“网络获取与配置提交分离、持久化成功才发布成功”的原则。`ProviderModelSyncCoordinator` 统一 `/models` URL 校验、请求规范化、模型去重/回退、失败分型和批量顺序；不同单项可并行获取网络结果，但完整 Provider 快照只在提交阶段互斥。提交端重新核对最新 Provider 身份，删除、配置漂移或保存期间变化都拒绝迟到结果，名称和仍有效模型保留用户最新选择，Room 保存完成后才修复空模型 Agent Profile。ViewModel 因此只保留 busy、逐项结果和弹窗投影，不再复制模型合并与保存规则。

聚焦 JVM `8/8`、完整 JVM `645/645`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK、仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.373s` 与最终文档语料 `OK (1 test)` 已通过。这个拆分提升的是 Provider 配置一致性，不改变 Agent Runtime、Room v32、Shadow、第 101/102 项或设备 Workflow/后台能力。

最新横向工程继续采用成熟 Agent 中“应用服务编排、Store 持有业务事实、UI 只投影结果”的原则。`AgentMemoryCandidateCoordinator` 统一候选列表、普通聊天/Agent Run 来源身份、采集与接受/拒绝 typed outcome，只为同一候选 ID 持有短生命周期 claim；不同候选不互相阻塞，失败和取消都释放 claim。敏感过滤、规范化去重、同主题冲突和 transaction 仍由既有 Room Store/Manager 负责，没有复制第二套记忆治理规则。关闭功能时取消 ViewModel 的旧列表 Job，解决的是 UI 生命周期竞态，不改变用户数据。

聚焦 JVM `7/7`、完整 JVM `637/637`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK、仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.633s` 与最终文档语料 `OK (1 test)` 已通过。这个拆分降低 ViewModel 对记忆 Store 的直接编排，但不构成自动写入长期记忆、Shadow 持久化、设备 Workflow/后台扩权或第 102 项能力。

最新横向工程继续采用成熟 Agent 中“恢复决定以持久化事实为准、UI 只投影结果”的原则。`RecoveredAgentApprovalCoordinator` 不相信进程重建前的内存卡片，每次决定都重新加载 Room detail 并通过既有恢复策略核验链尾证据；一次性互斥阻止批准/拒绝交叉，并以 `Busy` 区分“另一项决定处理中”和真正的 stale 证据，避免误清仍合法的审批卡片。批准在消费审批前恢复原 USER 附件，失败仍可保留可重试卡片；拒绝由 Repository 在一个事务内写入 Approval、审批 Step 和 Run 终态。这避免复制第二套 Runtime、风险策略或恢复判断，也避免只更新顶层 Run 的半状态。

该边界与当前进程的 `AgentApprovalDecisionCoordinator` 分离：后者拥有 ticket/claim/waiter，前者只协调重建后的 Room Run；ViewModel 仍负责 Provider/Profile、Compose、消息和 Workflow 后续步骤。聚焦 JVM `6/6`、完整本地 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint` 与三类 APK 已通过，仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。这个拆分不构成设备工具扩权、后台执行栈恢复、Shadow 持久化或第 102 项能力。

最新横向工程继续采用成熟 Agent 中“审批事实持久化”和“进程内等待协调”分离的原则。`AgentApprovalDecisionCoordinator` 只管理 ticket、一次性 claim 与 `CompletableDeferred`：重复点击不能并发写入，失败可释放后重试，停止或 Repository 无可决定记录时取消 waiter，旧 ticket 不能完成或清理新审批。Room 继续保存审批事实，ViewModel 继续投影 Compose 并执行 Repository 副作用，Runtime 只消费已经持久化成功的决定；没有复制第二套风险策略、Run/Workflow 状态或恢复逻辑。

五轮 TDD 聚焦 `5/5`，完整 JVM `624/624`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)`、耗时 `48.776s` 通过；7 份长期文档语料为 `OK (1 test)`。这个拆分提高审批并发与失败边界的确定性，但不构成设备工具扩权、Workflow/后台接线、Shadow 持久化或第 102 项能力。

最新横向工程继续采用参考实现中“按会话保存运行态、UI 只投影当前会话”的原则，但没有把完整 Agent Runtime 复制进 ViewModel。纯内存 `AgentConversationRuntimeStateStore` 统一 Run/Approval 的替换、审批局部清理、删除会话整组清理、新建占位隔离与启动恢复投影；ViewModel 仍负责 Room、Compose 和执行宿主副作用。这样后台 Run 更新不会串到当前会话，审批收敛也不会误删 Run，同时没有新增跨进程状态或第二套审批事实源。

五轮 TDD 聚焦 `5/5`，完整 JVM `619/619`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK 与仅 Redmi 默认完整 `OK (195 tests)`、耗时 `50.018s` 通过。这个拆分只降低 ViewModel 的运行态耦合，不构成设备工具进入 Workflow/后台、任意 App 控制、Shadow 持久化或第 102 项扩权。

第 101 项首个间隔真实使用窗口继续验证成熟 Agent 的 Shadow 观测应当“低频、旁路、可撤销”。Redmi 同一前台进程只采集 `1` 条真实 `/agent` 样本；本地知识以词法兜底命中 `Agent Run retryOfRunId` 的 `3` 个候选，Run 完成 `knowledge.search`，Judge 判定为直接回答。窗口成本为耗时 `5009ms`、TTFB `5002ms`、Prompt `10150B`、Tokens `2720/209/2929`，取消、异常和旁路错误均为 `0`。关闭开关并删除会话后 notice 有效 `1 -> 0`、裁剪 `0 -> 1`，临时知识文档和下载文件也已删除；这证明进程内观测生命周期可收敛，但不是 Embedding 质量或跨进程持久化证据。

第 97 至 101 项已记录窗口人工合计样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次形成直接回答 `5`、部分回答 `3`；累计成本 `43846ms / 43777ms / 66995B / 17164+1822=18986 Tokens`。八次 Judge 均未出现自然网络、协议或认证失败，也没有明显成本异常。该证据继续支持保持 `store=null / persistenceMode=NONE`、Room v32 和两层 enforcement 关闭，而不是提前引入 Room Store、跨进程 notice 或第 102 项能力。同步文档后的强制门禁为 JVM `614/614`、Lint `0 error / 50 warnings`、APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (195 tests)`。

最近完成的横向可靠性工程继续采用参考项目中“UI 只投影、应用服务编排、Runtime 执行”的分层原则。`AgentRunRetryCoordinator` 统一重试资格、副作用证据确认与漂移复核、附件恢复和关联新 Run 请求；ViewModel 继续负责会话/Profile/Provider 宿主副作用，Agent Runtime 继续负责真正执行。协调器只产生 typed event 和带 `retryOfRunId` 的不可变请求，不写旧 Run、不持有 Runtime，也不复制审批或工具策略。聚焦 JVM `7/7`、完整 JVM `614/614`、Lint `0 error / 50 warnings`、APK 构建与仅 Redmi 默认完整 `OK (195 tests)` 已通过。这个拆分提高失败恢复的确定性，但不构成设备后台自动化、任意 App 或远程工具扩权。

第 100 阶段把 Android 系统分享实现为 Intent 到既有编辑器/附件校验的薄适配层，而不是复制一套消息或 Agent Runtime。Manifest 精确声明单项 `text/plain`、PNG、JPEG/JPG、WEBP，并明确拒绝 `ACTION_SEND_MULTIPLE`；文本有 20,000 字符上限，图片只接受单个 `content://` 并复用既有 8 MB、MIME、签名和解码校验。Intent 适配层会合并判断 `EXTRA_STREAM` 与 `ClipData`：相同 URI 兼容，不同 URI 按多图拒绝。外部内容只生成用户可编辑的新会话草稿，永不自动发送；本地草稿冲突由用户明确打开或忽略，已有未决分享时拒绝新分享。冷/热启动与 Activity 重建分别处理，外部 referrer 和 extra 不用于可信来源归因或内部去重。聚焦 JVM `7/7`、Redmi `OK (4 tests)`，完整 JVM `607/607`、Lint、APK、文档语料和默认完整 `195` 条（`183 passed / 12 skipped`）已通过。该取舍延续成熟 Agent 的单一事实源原则：入口可以增加，但发送、审批、工具和后台边界不能因入口变化而复制或放宽。

第 99 阶段把 answerability Shadow 从同一窗口扩样本转为间隔真实使用窗口中的低频观察。Redmi 同一进程新增 `3` 条有效 Judge 样本，直接回答 `2`、部分回答 `1`，累计耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`，取消、异常和旁路错误均为 `0`。首次宽英文问题连续四次没有知识候选并使 Agent Run 达到工具调用次数上限，但没有成功答案和合格 Shadow 入口，tracker 保持 `0`，不能记作 Judge 失败、取消、跳过或 usage。当前窗口 Embedding Provider 不可用，有效候选来自词法兜底，因此本批只验证 answerability 旁路与词法候选链路，不能外推为 Embedding 质量证据。

第 97 至 99 阶段书面记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次形成直接回答 `4`、部分回答 `3`；七次 Judge 均未出现自然网络、协议或认证失败。该合计来自阶段报告相加，不是跨进程持久化 tracker。关闭开关并删除四个测试会话后，本批 notice 有效 `3 -> 0`、裁剪 `0 -> 3`，临时知识文档和下载文件均已删除。完整 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)` 通过。当前证据不支持增加 Room Store、Schema、跨进程 notice 或 enforcement；后续只在间隔开的真实使用窗口继续观察自然失败或明显成本异常，不为凑数量在同一窗口密集采样。

第 98 阶段进一步验证成熟 Agent 的 eligibility 与 Run 终态必须分离：自然 `BUDGET_EXHAUSTED` Run 没有满足 Shadow 入口时，不会被包装成 Shadow 失败；知识检索没有候选时只记 `SKIPPED / NO_CANDIDATE`，不伪造 Judge `UNKNOWN`、取消或 usage。Redmi 同一进程累计样本 `6`、完成 `4`、无候选跳过 `2`，四次 Judge 形成直接回答 `2`、部分回答 `2`，累计耗时 `23100ms`、TTFB `23067ms`、Tokens `10945`，Judge 取消和异常均为 `0`。关闭开关并删除测试会话后，notice 有效 `4 -> 1`、裁剪 `0 -> 3`，测试知识文档恢复为 `0`；该生命周期仍完全在进程内，不需要 Room Store。当前四次 Judge 均成功，尚未形成网络、协议或认证等自然 Judge 失败分布，因此 `store=null / persistenceMode=NONE`、Room v32 和两层 enforcement 关闭继续是正确边界。

第 97 阶段继续落实成熟 Agent 的“先旁路观测、再用证据决定是否持久化或执行”：固定上限的进程内 tracker 只累计 Judge 成本、失败分类和 notice 生命周期，不保存问题、答案、候选正文、引用、原始响应或凭据；答案保存后、Judge 发出前再次检查开关。Redmi 首条真实前台样本完成，notice 随测试会话删除被裁剪，普通聊天未增加样本。第 96 阶段的生产 adapter、默认关闭开关、答案保存后 caller 和 `messageId` notice 保持不变；当前仍为 `store=null / persistenceMode=NONE`、无 Room migration、无 enforcement。

第 95 阶段进一步落实成熟 Agent 常见的“离线评测数据与线上遥测分型”：`KnowledgeAnswerabilityObservation` 继续携带人工标签用于 calibration/validation，真实 Run 只生成无标签 `KnowledgeAnswerabilityShadowMeasurement`，两者共享 `KnowledgeAnswerabilityAssessment` 和同一原文证据回查。这样线上 Judge 结果不会被误写成人工真值，也不会污染离线质量统计。

真实测量由默认关闭的 `KnowledgeAnswerabilityShadowObservationCoordinator` 统一编排，只允许前台直接 Agent；候选不完整和缺少冻结绑定不会请求 Provider。瞬时网络、限流、服务端与协议错误最多重试一次，取消透传；身份漂移保留 measurement 但绑定未知。可选 Store 只保存 SHA-256 指纹、幂等键和结构化分类，普通失败不影响答案。第 95 阶段完整 JVM `578/578`、Lint、APK 与仅 Redmi `OK (188 tests)` 通过；第 96 阶段已补生产 adapter/caller/UI，Room Store 与 enforcement 仍未接线。

第 94 阶段先把真实 Agent 消息中可进入 shadow 的证据限定为最近一条可信 `knowledge.search`，并冻结 Judge identity、数据集版本、门禁、Run 和引用快照。第 95 阶段把其输入从带人工标签的 observation 收紧为线上无标签 measurement；覆盖率特征族、身份漂移、测量缺失、Run 不匹配和候选不完整继续保守为 `UNKNOWN`，原答案和引用保持不变。

第 93 阶段进一步采用成熟 Agent 常见的“判定与呈现分离”：`KnowledgeAnswerabilityShadowPresentationPolicy` 不重新访问模型或检索，只把冻结门禁下的观察结果转换为用户提示。直接回答、部分回答、未回答、矛盾、证据无法回查、低于门禁和未知都有稳定状态；输入引用保持不变，`enforcementApplied=false`。Redmi 真实 Judge 证据已经取得；第 93 阶段当时生产消息流尚未绑定该提示，第 96 阶段已将默认关闭的旁路接入前台直接 Agent，因此真机证据仍不会被误写成生产拒绝决策。

展示 seam 通过新增 JVM `5/5`，与第 92 阶段策略合计 `12/12`；Compose 用例已随 Redmi 默认完整 `OK (188 tests)` 通过。真实 `gpt-5.5` 探针的 calibration/validation 各 `12` 条、网络/解析失败均为 `0`；verdict/原文证据与置信度两类特征族通过，覆盖率特征族未通过。第 93 阶段当时尚未把 notice 传入生产消息流；第 96 阶段已完成默认关闭的旁路接线，但引用过滤、答案改写、生产身份升级和 enforcement 继续关闭。

第 92 阶段把参考项目中常见的“检索排序与答案可验证性分层”进一步落成独立 Kotlin 策略。模型只能返回固定 JSON verdict 和候选原文 quote，系统必须把 quote 重新匹配到候选正文；解析失败、矛盾、部分回答和无法确认均不会被升级为可接受答案。三类特征族分别使用 verdict/原文证据、置信度和证据覆盖率，calibration 与 validation 绑定同一 Judge 身份但使用互异数据集，避免用验证集回调门禁。第 92 阶段该实现只提供 shadow 证据，尚未接入生产检索、Room 或答案展示；后续第 93–96 阶段只增加默认关闭的前台旁路，不改变上述生产拒绝边界。

这延续了第 91 阶段的判断：成熟 Agent 不能把“相似”直接当成“回答了问题”，也不能把模型自报的引用当作事实。真实 Provider/Redmi `12 + 12` 观测已经完成，并证明两类较简单特征族可达到预注册标准；覆盖率特征族未通过，且该探针仍与生产答案链路隔离，因此 `productionEnforcementEnabled=false`。

第 91 阶段进一步验证了成熟检索系统中的一个边界：仅把相似度改写成相对分数，不等于获得了 answerability。`top1-候选均值` 对整体分数平移不敏感，`margin/候选标准差` 还消除了正比例缩放；两者都只从已有审计构造，不引入新的生产依赖。Redmi 两套全新数据各 24 条、Recall@5 均为 `1.0`，但三轮均没有特征族达到预注册标准。最优的 `top1-均值` 与组合保留全部正例并拒绝全部远负例，却只能拒绝 `75%` 近负例；这证明“目标文档与问题同主题”仍可能被误当成“文档包含答案”。小灵因此没有照搬服务端常见的 score normalization 后直接开闸，而是保持 `productionEnforcementEnabled=false`，下一步转向 answerability/重排证据或继续关闭生产拒绝。

第 90 阶段把参考项目常见的“质量门禁否决必须是可审计结果”落到正式 Provider：`KnowledgeRelevanceProductionCalibrationPolicy` 绑定 Provider、模型、配置指纹和互异 calibration/validation 数据集，只从 calibration 冻结七类特征族阈值，再在 validation 原样评估。Redmi 两套各 24 条观测的 Recall@5 均为 `1.0`，但最新 raw top1 正例接纳率 `0.75`（重复取证范围 `0.625–0.75`），七类特征族无一通过预注册相关性标准。显式测试把这个否决作为成功验收，保持 `productionEnforcementEnabled=false`；没有降低阈值、没有使用 validation 调参、没有升级 `VERIFIED` 或进入 final holdout。这里再次说明成熟系统中的排序正确不等于相关性接纳通过，跨主题绝对分数漂移仍需新的归一化/数据设计才能继续。

第 89 阶段把成熟系统常见的“生产身份证明”和“用户开关”进一步拆开：真实端点探针只建立 `CANDIDATE`，不会把协议成功误当成检索质量通过；`VERIFIED` 必须绑定同一 Provider、模型、配置指纹、冻结 gate 和独立 final-holdout 证据。灰度控制面只有在全部身份与证据一致时才允许 `ENFORCE`，其余情况统一 `SHADOW`；撤销保留最小审计并移除执行资格。Base URL 只保存 SHA-256 指纹，设置页也没有绕过证据直接开闸的入口。Redmi 探针得到 `2 × 1024` 有限向量并停在 `CANDIDATE`，完整 JVM `532/532`、Lint、APK 和仅 Redmi `176 passed / 8 skipped / 0 failed` 通过。第 90 阶段已在正式身份下重建 calibration/validation，但七类特征族均被预注册标准否决；不得照搬实验 Provider ID、服务端全局开关或 validation 回调阈值，final holdout 继续后置。

第 88 阶段进一步落实成熟检索系统常见的“检索证据、执行决策和用户呈现分层”：Store 只标记词法/语义来源，纯策略决定是否移除 semantic-only，展示层再从最终候选生成引用和固定解释；任何来源未知、身份漂移或 shadow 异常都 fail-open。灰度资格同时绑定 gate 版本、Provider、模型和独立 calibration/validation 数据身份，撤销会清除全部资格，不让旧授权随同名模型复活。实现没有照搬服务端全局阈值或直接开关生产拒绝，Stage 85/86 的实验 Provider ID 也明确不能直接进入正式配置。完整 JVM `522/522`、Lint、APK 与仅 Redmi `173 passed / 7 skipped / 0 failed` 通过；下一阶段先重绑定真实生产身份和受控灰度，再讨论答案路径接入。

第 87 阶段把参考项目常见的“检索评分与执行策略分离”落实为独立纯 Kotlin 设计：`KnowledgeRelevanceProductionDesignPolicy` 只读取既有审计事实，绑定冻结 gate 的 Provider/模型身份；高分保持当前语义结果，低分只规划语义移除并保留词法兜底，未知或身份漂移则 fail-open。默认开关关闭时不会改变任何检索结果，策略没有接入 Room Store，也没有新增生产拒绝状态。JVM `511/511`、Lint、APK 与仅 Redmi `171 passed / 7 skipped / 0 failed` 通过；下一步应先完成用户可见回退、灰度和撤销契约，再决定是否接入。

第 86 阶段把成熟检索评测中的“final holdout 必须在读取结果前冻结”落实为独立 commit，而不是直接追加一次可反复修改的联网测试。门禁只保留 Stage 85 更简单的 raw top1 `0.6416276358587735`，并冻结 calibration/validation 完整身份；第三套 `stage86-final-holdout-v1` 的 20 篇全新文档和三桶各 10 条查询在运行前固定。策略禁止从 final holdout 搜索阈值或重新引入 margin/z-score，完整排序与拒绝标准也已预注册。Redmi 首次有效观测耗时 `63.077s`，在补齐 validation 身份校验并同步重建 APK 后最终复验耗时 `67.018s`；60 条最终观测通过全部门禁，证明这个 Provider/模型下的原始 top1 候选尚未在第三套数据上失效。中间 ABI/空分数回归未被当作有效证据，也没有回调阈值；生产拒绝仍关闭，通过只获得进入设计评审的资格，不能被误解为可以直接上线。

第 85 阶段把成熟检索系统常用的 calibration/validation 分离落到移动端真实 Provider，但没有把“多特征”本身当作更可靠。7 个预注册特征族各自在全新 calibration 集选 gate，再在全新 validation 集原样评估；raw top1 与 raw+margin 都达到正例 `0.90`、近/远负例 `1.0`、稳定 `1.0`，而 margin、z-score 或含 z 的组合没有更好。两者 validation 决策完全相同，因此继续增加 margin 只会增加过拟合维度；下一阶段优先冻结更简单的 raw top1，并用第三套未见 holdout 决定是否仍应否决。Stage 83 holdout 保持封存，生产拒绝保持关闭。完整 JVM `502/502`、Lint、APK 和仅 Redmi `177/177` instrumentation 通过，6 个联网用例默认 skipped。

第 84 阶段借鉴成熟检索系统“原始分数之外还要保存查询内分布”的做法，但没有照搬成线上置信度。Room v32 为每次真实语义检索保存当前有界语义索引候选池中全部候选的均值、总体标准差和 top1 z-score，历史记录不回填；UI 继续标为 shadow 观测。退休 Stage 83 holdout 的单次诊断显示正例 z-score `2.929–3.722`、近负例 `2.226–3.232`、远负例 `1.579–2.879`，近负例仍与正例重叠。因此查询内标准化是下一轮实验特征，不是生产门禁；不能像部分服务端方案那样直接发布一个 z-score 阈值。完整 JVM `499/499`、Lint、APK 和仅 Redmi `176/176` instrumentation 通过，5 个联网用例默认 skipped。

第 83 阶段验证了参考项目中更关键的一条原则：门禁需要真正独立的未见数据，而且失败结果必须阻止上线。第 82 阶段冻结 top1/margin 候选后，全新 20 篇成对主题、三桶各 10 条查询在 Redmi 三个独立进程中复验；Recall@1/5、MRR、排序稳定率和两类负例拒绝率均为 `1.0`，但正例接纳率只有 `0.80`，低于预注册 `0.90`。两个正例仍正确排第一，却因跨主题绝对分数低于冻结阈值被拒绝，说明服务端常见的原始 cosine 单阈值不能直接照搬到当前多主题移动端语料。小灵因此没有把候选接入生产，也没有用 holdout 回调阈值。下一轮若继续，必须建立新的版本化 calibration/validation/holdout 或归一化特征设计；ANN、后台批量重建和生产拒绝继续后置。完整离线门禁为 JVM `495/495`、Lint、APK 和仅 Redmi `175/175` instrumentation 通过，5 个联网用例默认 skipped；三轮显式 holdout 失败作为否决证据保留。

第 82 阶段进一步落实参考项目中“检索门禁必须以可重复校准集和离线指标驱动”的做法，但保留移动端本地知识库的证据边界。20 篇成对主题语料、三桶各 10 条查询、每条重复 2 次并在 Redmi 三个独立进程运行；三轮 Recall@1/5、MRR、排序稳定率和同集 shadow balanced accuracy 均为 `1.0`。纯 Kotlin 策略固定输出 top1/margin 分位数和可复现候选组合，生产检索未接入阈值。由于候选仍在同一数据集上选择并评估，本阶段没有照搬服务端项目常见的单阈值配置；下一阶段先冻结 Provider/模型专属候选并以独立 holdout 证明泛化，再讨论仅拒绝纯语义候选且保留词法兜底。20 行 1024 维向量和约 0.8 秒查询仍不支持提前引入 ANN 或后台批量重建。最终 JVM `491/491`、Lint、APK 和仅 Redmi `174/174` instrumentation 通过，4 个联网用例默认 skipped。

第 81 阶段把参考项目强调的“相关性门禁必须先可观察、再按模型校准”落到生产审计和 Redmi 真实 Provider。Room v31 保存 top1、top2、margin 与候选数，管理页明确标为 shadow 校准观测；历史未知值不回填，生产 cosine+RRF 不拒绝候选。固定 10 篇语料的正例、近负例、远负例各 2 条形成首轮分层：top1 为 `0.6806–0.7130 / 0.6502–0.6854 / 0.3704–0.4083`，margin 为 `0.2743–0.2828 / 0.1311–0.2114 / 0.0274–0.0507`。这支持继续研究绝对下限与 margin 的组合，但样本量不足以照搬为跨 Provider/模型阈值；下一阶段先扩充标注分桶，再考虑只拒绝纯语义候选并保留词法兜底。完整 JVM `488/488`、Lint、APK 和仅 Redmi `174/174` instrumentation 通过；ANN、后台批量重建与规模化性能继续后置。

第 80 阶段把参考项目强调的“质量、性能和资源证据应共享同一固定语料”落到 Redmi 真实 Provider。10 篇中文文档、5 个跨语言查询各重复两次，三轮 Recall@5/MRR/排序稳定率均为 `1.0`；10 行 1024 维向量仅 `40,960` 原始字节，索引中位数 `8.881s`，三轮查询中位数的中值 `0.836s`，每轮 P95 为 `1.016–1.496s`，检索后 PSS 增量 `7,358–15,941 KB`。这些数据不支持为 10 行索引引入 ANN 或后台批处理；更重要的是无关查询三轮均返回 5 个近邻，因此下一步应先为语义排名建立可校准的相关性拒绝证据。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `171` 个用例，`168` passed、`3` skipped、`0` failed。

第 79 阶段把参考项目强调的“真实集成证据必须跨过协议层并到达检索结果”落到 Redmi：显式联网测试直接复用生产 OpenAI 兼容客户端、Embedding 适配器、Room 索引和检索审计。英文查询在纯词法路径零命中，真实向量路径首位命中中文目标文档并记录 `USED`；索引身份、维度、分块、chunk IDs 和显式重建均可核对。真实协议与语义链各 `1/1` 通过，完整语义链约 `5.947s`；测试使用内存 Room，不污染正式知识库，默认套件也不依赖公网。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `170` 个用例，`168` passed、`2` skipped、`0` failed。下一步依据更大有界语料的质量、耗时和内存数据决定 ANN/后台批量索引，而不是因已有单文档成功样本提前引入复杂基础设施。

第 78 阶段把参考项目强调的“检索质量必须可复现、实际回退必须可观察、能力探测不能靠猜测”落到固定语料与管理 UI：新增文档级去重的 Recall@5/MRR/负例准确率/重复排序稳定率门禁，知识审计显示五种 Embedding 实际路径，并只在 Provider 模型列表明确包含 Embedding 模型时执行真实向量请求。该阶段的聊天兜底 Provider 没有可识别的 Embedding 模型，因此当时只取得真实词法兜底证据；第 79 阶段已由独立真实 Embedding Provider 补齐协议和语义链。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个显式联网冒烟按设计 skipped、`0` failed。

第 77 阶段把参考项目强调的“索引身份可见、重建失败不破坏旧能力、模型切换不混用空间”落到知识管理页和 Room Store：旧文档可显式补建，详情显示 Provider/模型/维度/分块数，写事务只替换当前 `providerId + model`，其他空间继续共存；Provider 失败、超时、停用和 revision 竞态不删除已有索引。Room 保持 v30，完整 JVM `483/483`、Lint、APK 和仅 Redmi `164/164` instrumentation 通过。ANN、自动后台批量重建与规模化性能继续后置。

第 76 阶段已把参考项目强调的“关键词与语义融合、Provider 能力隔离、检索结果可审计”落到本地知识库：Room v30 保存按 Provider/Embedding 模型隔离的 Float32 向量，FTS4+LIKE 与语义候选使用稳定 RRF，失败/超时/无索引/维度漂移保留词法回退并记录状态，最终交付前再次核对文档 enabled/revision。完整 JVM、Lint、APK 和仅 Redmi `158/158` instrumentation 通过。该阶段当时仍是有限规模内存扫描，不承诺 ANN、后台增量建索引或多 Provider 并行索引。

审计日期：2026-07-16（北京时间）

本文负责保存参考项目分类、源码证据和借鉴判断。正式实施顺序、里程碑和验收标准以 [小灵个人 Agent 路线图](personal-agent-roadmap.md) 为准。

实施状态同步至 2026-07-25：本文提出的 AgentProfile v1 已在 Room v21 落地，Text/Tool 消息 parts 已在 Room v22 落地，供应商 Reasoning summary 已在 Room v23 落地，用户 Image part 已在 Room v24 落地，Document v1 已在 Room v25 落地，知识文档/chunks/FTS/检索审计数据基础已在 Room v26 落地，`knowledge.search` 与引用持久化已在 Room v27 落地，后台 Worker 停止原因审计已在 Room v28 落地，独立 Android 进程退出观察已在 Room v29 落地，Embedding 检索、显式索引生命周期和固定语料质量诊断在 Room v30 落地，相关性绝对分数 shadow 诊断在 Room v31 落地，查询内相对分布 shadow 观测在 Room v32 落地；第 82 至 86 阶段依次完成扩样校准、失败 holdout、相对特征、独立 calibration/validation 和最终 holdout，第 87 阶段完成生产拒绝设计，第 88 阶段完成来源标记、降级/引用一致性与身份灰度契约，第 89 阶段完成生产身份状态机、真实候选探针和显式灰度控制面，第 90 阶段完成正式身份 calibration/validation 并得到门禁否决，第 91 阶段验证平移不变分数仍会误接纳近负例，第 92 阶段完成严格 answerability 策略及 `12 + 12` 条真实 `gpt-5.5` shadow 观测，第 93 阶段完成保持引用不变的 shadow 呈现 seam 和 Redmi UI 验收，第 94 阶段完成可信消息候选与冻结绑定，第 95 阶段完成离线 observation/线上 measurement 分型和 Judge 协调，第 96 阶段完成默认关闭的生产 adapter、答案保存后异步 caller、设置开关和进程内 notice，第 97 阶段完成有界进程内成本/失败/notice 遥测和首条真实前台样本；两类特征族通过、覆盖率特征族未通过，Room Store、拒绝与 enforcement 继续关闭。答案引用 UI、设备 Agent 只读观察、首批有限动作、任务中心需确认队列、结构化恢复处置、Redmi 长任务/Doze/受控内存证据、AgentRun 终态原子保护，以及 `STOP_REQUESTED` 持久化停止重对账也已完成。第 58 阶段完成后台 Worker 的 TLS 失败与网络恢复取证，第 59 阶段完成 `229.416s` 的 8 步复合只读成功链，第 60 阶段完成冷启动成功链，第 61 阶段又完成熄屏状态下 `244.236s` 的 8 步成功链；32/32 工具回执通过、预算无回退且单一 Workflow Run。Stage 64 开始按隐私安全、无 Task/Run 归因的有界账本观察平台退出，Stage 65 又补齐不触发采集的只读诊断 UI；当前受控样本仍不是自然 LMK，不引入 Foreground Service。参考项目审计日期仍保持原始取证时间。

第 75 阶段把参考项目强调的“输入事实与执行事实分离”落到 `/agent` 附件：Responses 规划请求可携带经校验的 USER Image/Document，summary、VerifiedAgentContext、Tool part 与 Agent 输出继续隔离附件；审批恢复与任务中心重试从 Room USER MessagePart 重建并复制到新 Run，Chat/mixed/持久化重复附件/伪造来源保持 fail-closed。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过，图片与文档真实 E2E 的工具回执均为 `PASSED`；Workflow/后台 Agent 暂无附件入口。该段是第 75 阶段当时边界；Embedding 已在第 76 至 78 阶段补齐有限规模检索、显式重建和质量诊断，设备后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

第 62 阶段补齐了后台停止原因的可观测性：Android 12+ WorkManager 停止码以隐私安全的稳定分类写入 ScheduledTask 与 WorkflowRun，并由任务中心展示；旧 Android、未停止值、未知码和历史记录均不被猜测。Redmi `143/143` instrumentation 与 JVM `424/424` 通过；本阶段没有自然系统停止样本，不将该能力解释为更强的后台存活保证，也不提前引入 Foreground Service。

第 63 阶段补齐真实应用取消路径：Redmi Android 14 的 WorkManager 实际返回 `CANCELLED_BY_APP(1)`，证明第 62 阶段读取链能取得平台事实；业务侧仍让先落库的 `STOP_REQUESTED` 用户意图优先，后到机制码不得覆盖。完整 Redmi instrumentation 更新为 `145/145`。该受控样本不等同自然 LMK、配额或超时，路线仍保持普通 WorkManager 和 Foreground Service 后置。

第 64 阶段把 Android 11+ `ApplicationExitInfo` 变成生产旁路观察：前台和 Worker 冷启动均可采集，Room v29 只保存 30 条稳定数值记录，不关联旧 Task/Run，也不保存 description、trace 或状态摘要。只有 `LOW_MEMORY` 是直接 LMK；直接报告不受支持时的 `SIGNALED + SIGKILL` 仅为候选，用户/应用/包维护退出保持受控分类。JVM `431/431`、Redmi `149/149` 通过；受控 `force-stop` 实测为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不构成自然回收。

第 65 阶段补齐该观察账本的用户可见控制面：设置页只读展示最近 30 条既有记录，六类证据使用稳定中文标签，刷新只查询 Room、不触发采集，也不把记录归因给 Agent Run、工作流或任务。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152` 通过；受控 `USER_REQUESTED` 在页面与数据库一致，刷新前后计数不变。该可见性用于长期积累真实样本，不放宽 Foreground Service、恢复或设备后台边界。

第 66 阶段开始兑现参考项目共同强调的应用服务分层：普通聊天的上下文资格、知识生命周期、最近窗口、增量摘要和请求投影从 4439 行的 `XiaoLingViewModel` 迁入独立 `ConversationRequestContextPreparer`，ViewModel 只装配 Room、网络与提示词依赖。新增 JVM `8/8`，完整 JVM `439/439` 与 Redmi instrumentation `152/152` 通过；Schema、协议和 UI 不变。这一拆分为后续 Embedding 或 `/agent` 附件建立单一上下文入口，但本阶段不提前引入二者。

第 67 阶段继续落实应用服务分层：普通聊天的发送前持久化、上下文准备、模型请求、流式增量与成功/取消/失败状态机迁入 `ConversationSendCoordinator`。ViewModel 只把稳定事件投影为 Compose 状态并保留 30ms 节流和 Job 取消入口；取消事件发出后仍传播协程取消，持久化失败不会触发上游请求。新增 JVM `3/3`，完整 JVM `442/442` 与仅 Redmi 执行的 instrumentation `152/152` 通过；Room v29、Provider 协议、UI、Agent/Workflow 和后置能力不变。

第 68 阶段继续把参考项目的 Session/Application Service 边界落到可测试代码：新增 `ConversationSessionPolicy`，统一第一条 `role=user` 消息标题（空白保持“新会话”）、空占位折叠、会话时间戳、摘要元数据继承、blank ID 和非当前会话更新隔离。ViewModel 删除 83 行纯状态规则，从 4272 行降到 4189 行；异步 Room 加载、保存 Job、删除事务和 Compose 副作用仍留在原位。六轮 TDD 后新增 JVM `6/6`，完整 JVM `448/448` 与仅 Redmi 执行的 instrumentation `152/152` 通过；Room v29、协议、UI、Agent/Workflow 和后置能力不变。

第 69 阶段继续落实参考项目的单写者持久化边界：新增 `ConversationPersistenceCoordinator`，统一 latest-save Job、Room 串行写入、发送前等待，以及显式删除 ID 的代次确认/回滚。旧事务即使已进入不可取消提交区，最新快照仍最后写入；失败、取消、同 ID 重标记或旧失败回调晚到时不会丢失新删除意图。ViewModel 从 4189 行降到 4183 行，异步加载、删除 UI 和 Compose 副作用仍保留。新增 JVM `8/8`，完整 JVM `456/456` 与仅 Redmi instrumentation `152/152` 通过；Room v29、附件 BLOB、协议、UI、Agent/Workflow 和后置能力不变。

第 70 阶段补齐参考项目常见的 latest-request 边界：新增 `ConversationLoadCoordinator`，用单调选择代次和稳定事件隔离协作式取消后仍迟到的 Room 结果。旧选择无论迟到成功或失败都不会覆盖新会话、触发旧删除回滚或替换提示；新 Job 在可重入 Loading 前登记，回调触发新选择时仍保留正确生命周期。ViewModel 继续保留完整消息与轻量会话的原子 Compose 投影。新增 JVM `4/4`，完整 JVM `460/460` 与仅 Redmi instrumentation `152/152` 通过；Room v29、附件 BLOB、协议、UI、Agent/Workflow 和后置能力不变。

第 71 阶段继续把 Session/Application Service 的纯状态边界落到代码：新增 `ConversationLoadProjectionPolicy`，统一 Loading/Loaded/Failed 的 UI 投影，并把“非当前会话剥离 Image/Document BLOB、当前会话原子注入完整消息”固定为独立测试规则。删除回滚、Agent/审批映射和保存仍由 ViewModel 编排，不把副作用塞进 reducer。新增 JVM `3/3`，完整 JVM `463/463` 与仅 Redmi instrumentation `152/152` 通过；Room v29、附件 BLOB 生命周期、协议、UI、Agent/Workflow 和后置能力不变。

第 72 阶段继续完善参考项目强调的 Session 选择边界：`ConversationSessionPolicy` 以 `Immediate / Load` 计划统一新建会话、复用/折叠空占位、删除后选择最新会话和删空兜底；复用既有会话才允许恢复 Agent/审批状态，新占位始终清空。ViewModel 只执行取消、删除意图、Map、加载/回滚和保存副作用。新增 JVM `5/5`，完整 JVM `468/468` 与仅 Redmi instrumentation `152/152` 通过；Room v29、附件 BLOB 生命周期、协议、UI、Agent/Workflow 和后置能力不变。

第 73 阶段继续落实参考项目常见的 Application Service 组合边界：新增 `ConversationSelectionCoordinator`，不重复 Session、Persistence 或 Load 规则，只固定新建/选择/删除的副作用顺序，并把删除加载失败的版本化回滚从 ViewModel 与 Load Request 中迁出。ViewModel 只消费事件、维护 Agent/审批运行态 Map、投影 UI 和保存成功选择，从 4121 行降到 4087 行。聚焦 `4/4`、第 68 至 73 阶段组合 `30/30` 与完整 ViewModel 手工编译通过；后续标准 Gradle、Lint、APK 和 Redmi 门禁已补齐，该阶段完整基线为 JVM `472/472`、Redmi `153/153`。

## 1. 结论先行

`reference-apps` 下共识别出 56 个独立 Git 仓库。它们并不都是“个人 Agent”：25 个主要是普通 AI 聊天客户端或 Chat SDK，9 个主要解决离线/本地模型推理，13 个属于个人 Agent 或 Agent 平台，7 个属于设备 Agent/手机自动化，1 个是独立 Agent 框架，另有 1 个是非 Agent 业务样本。

对小灵最重要的结论不是“把所有参考功能都做一遍”，而是按下面的顺序把聊天客户端演进为可控的个人 Agent：

1. **先建立可审计的 Agent 运行时**：结构化消息、工具调用、步骤状态、取消、超时、循环检测、上下文压缩、运行记录。
2. **再建立安全边界**：工具注册表声明风险，默认拒绝副作用操作，按次/本次任务/永久授权分级，执行后必须验证。
3. **再建立个人化能力**：Agent 配置、长期记忆、记忆来源和敏感信息过滤、分享入口、语音输入。
4. **再建立持续工作能力**：任务账本、定时任务、通知、失败/待确认聚合页、断点恢复。
5. **最后扩展设备控制和生态**：设备 Agent 先做可选 Accessibility 的授权、健康检查和只读 snapshot，再加入有限动作、审批和操作后验证；日历/通知、MCP、远程 Gateway、本地模型和多 Agent 都继续后置。

最值得组合借鉴的不是某一个项目，而是：

- `openclaw` 的运行时分层、Session/Task、工具策略和沙箱边界。
- `meow-agent` 的模块注册、风险元数据、确认、执行后验证和持久任务账本。
- `hermes-android` 的移动端 Agent 操作面：WebSocket 事件、分级审批、通知和“Needs you”。
- `rikkahub` 的 Android 原生 Assistant、消息 parts、工具审批与生成循环。
- `Operit` 的 Android 工具生态、WorkManager 工作流和混合记忆检索，但不能照搬其高权限入口和平台体量。
- `X-OmniClaw`/`mobilerun` 的观察、规划、动作、验证闭环以及坐标/快照一致性保护。
- `ZeroAI` 的循环预算、工具观测、敏感信息过滤和调度可靠性；其 Kotlin+Rust/UniFFI 不是小灵现阶段的必要复杂度。
- `OGAM` 的本地模型资源预算和工具路由经验，只适合小灵未来做离线推理时参考。

## 2. 审计范围与证据边界

### 2.1 阅读方式

- 全量盘点 56 个独立仓库的 README、docs 入口、构建文件和核心源码入口。
- 深读真正具有 Agent 闭环的代表项目：`openclaw`、`Operit`、`ZeroAI`、`hermes-android`、`rikkahub`、`X-OmniClaw`、`mobilerun`、`meow-agent`，并结合 `skales`、`OGAM` 的架构和核心模块做横向判断。
- 补充关注 `Agora`、`memex`、`opencyvis-phone`、`vFlow`、`AndroidMCPAgent` 和已归档的 `ZeroClaw-Android`，用于覆盖本地推理、个人记忆、设备执行、工作流底座和反面案例。
- 结论优先依据仓库内 README、设计文档和源码，不以项目宣传语单独判定 Agent 能力。

### 2.2 特殊工作区状态

`reference-apps/X-OmniClaw` 当前有 499 个 staged deletions，但其 `HEAD` 与 `origin/main` 一致。本次没有恢复或修改这些变更；该项目的 README 和源码证据通过 `git show HEAD:<path>`、`git ls-tree -r --name-only HEAD` 从 Git 对象读取。

### 2.3 判定标准

本报告把“个人 Agent”定义为至少具备以下能力中的大部分，而不是仅仅支持 system prompt 或 function calling：

| 维度 | 判断问题 |
|---|---|
| 目标与规划 | 能否把用户目标拆成步骤或子目标？ |
| 工具执行 | 能否通过受控工具产生真实外部效果？ |
| 闭环验证 | 动作后是否重新观察或校验结果？ |
| 运行控制 | 是否有取消、超时、步数预算、循环检测和错误恢复？ |
| 权限边界 | 风险是否由代码定义，副作用是否需要用户确认？ |
| 任务连续性 | 是否能持久化任务状态、后台运行、定时触发或断点恢复？ |
| 个人化 | 是否有可管理、可删除、可追溯的长期记忆和 Agent 配置？ |
| 可观测性 | 用户能否看到步骤、工具、结果、失败原因和资源消耗？ |

## 3. 56 个参考仓库分类

分类以主要产品形态为准；部分项目具有交叉能力。

| 主分类 | 数量 | 项目 |
|---|---:|---|
| 普通 AI 聊天客户端 / Chat SDK | 25 | `AetherisAI`、`AndroidGPT`、`ChatGPT-Android-App`、`ChatGPT-basic-android-client`、`ChibiChat`、`Librechat-Mobile`、`MaterialChat`、`OllamaMobile`、`OpenAI-Client-Android`、`Taiga`、`aiyo`、`android-ai-chat-sdk`、`arcade_ai`、`chatAir`、`chatgpt-android`、`chatgpt_android`、`compose-chatgpt-kotlin-android-chatbot`、`conduit`、`eChat`、`gpt_mobile`、`kelivo`、`ollama-app`、`quock`、`reins`、`sample-mobile-ai-assistant` |
| 离线 / 本地模型客户端 | 9 | `ChatterUI`、`OfflineLLM`、`Ollama-CCP-Android`、`OllamaTalk`、`OllamaTest`、`SmolChat-Android`、`maid`、`nexa-android`、`ollama-android` |
| 个人 Agent / Agent 平台 | 13 | `Agora`、`ClaraVerse`、`OGAM`、`Operit`、`ZeroAI`、`gemini-android-app`、`hermes-android`、`memex`、`meow-agent`、`openclaw`、`pocketpal-ai`、`rikkahub`、`skales` |
| 设备 Agent / 手机自动化 | 7 | `AndroidMCPAgent`、`X-OmniClaw`、`ZeroClaw-Android`、`bizclaw`、`gpt-assistant-android`、`opencyvis-phone`、`vFlow` |
| Agent 框架 | 1 | `mobilerun` |
| 非 Agent 业务样本 | 1 | `youshu` |

需要避免的误判：

- `Librechat-Mobile`、`kelivo` 等可以连接 MCP 或 Assistant，但主要 Agent 运行时在服务端或仍以聊天客户端为主。
- `vFlow` 不是 AI Agent，却是很有价值的 Android 工作流执行底座。
- `mobilerun` 是运行在电脑/服务器侧、通过 Portal/ADB 控制设备的框架，不是手机里的个人 Agent 产品。
- `youshu` 有 AI 入口，但主要是家庭物品管理业务，不应混入 Agent 能力统计。

## 4. 代表项目源码审计

### 4.1 `openclaw`：完整个人 Agent 平台，适合学边界，不适合照搬体量

**已验证事实**

- README 将产品定义为运行在个人设备上的 always-on assistant，Gateway 负责 sessions、channels、tools 和 events；Android 端是可配对的 node/companion，而不是独立承载全部大脑。
- `packages/agent-core/src/agent-loop.ts` 的核心循环会持续处理 LLM tool calls、steering messages 和 follow-up messages，并把 `agent_start`、`turn_start`、`message_update`、`turn_end`、`agent_end` 作为结构化事件发出。
- `src/agents/tool-policy-pipeline.ts` 按 profile、provider、agent、group、sender 多层过滤工具。
- 沙箱文档明确区分 Gateway 与工具执行，并支持 `off/non-main/all`、按 agent/session/shared 隔离、只读/读写工作区和默认无网络容器。
- 自动化不只等于 cron：项目区分 heartbeat、cron 和持久 background tasks，并保留任务生命周期和维护/恢复语义。

**值得小灵借鉴**

- 把 `AgentRun`、`Turn`、`Step`、`ToolCall`、`ToolResult`、`Task` 设计成正式领域对象，而不是继续把运行信息拼在消息 footer 中。
- 工具权限应通过多层策略合并：全局默认、Agent 配置、会话临时授权、触发来源（前台用户/后台任务）共同决定最终可用工具。
- 前台聊天、后台任务、定时任务使用同一运行时，但采用不同默认权限和记忆可见范围。
- 所有运行都应可取消、可恢复、可观察，并在结束时有明确终态。

**不应照搬**

- 多通道 Gateway、多 Agent 路由、Docker/SSH 沙箱和桌面节点是平台级能力，会把小灵当前的 Android 单体 App 直接推向分布式系统。
- OpenClaw 主会话默认在 host 上执行工具的信任模型不适合 Android 消费级 App；小灵必须默认最小权限。

### 4.2 `Operit`：Android 端能力最丰富，但高权限和复杂生态风险最高

**已验证事实**

- README 列出 Ubuntu 24、本地模型、40+ 工具、MCP/Skill/ToolPkg、工作流、定时任务、Tasker、无障碍/ADB/Root 自动化和记忆系统。
- `ToolExecutionManager.kt` 负责工具解析、参数校验、角色卡工具白名单、工具拦截、权限检查、只读工具并行和串行执行。
- `ToolPermissionSystem.kt` 的工具权限是 `ALLOW/ASK/FORBID`，默认 `ASK`；按工具持久化，询问超时 60 秒并默认拒绝。
- `WorkflowScheduler.kt` 使用 WorkManager 支持 interval、specific time 和简化 cron；`Workflow.kt` 将 trigger、execute、condition、logic、extract 建成节点图。
- 记忆使用 ObjectBox、关键词/Jieba、向量、关系边和 RRF 类融合检索；自动保存前会裁剪工具结果，并跳过无价值对话。
- ToolPkg 是带 manifest、JS/TS 入口、资源、UI、工作流模板和工作区模板的 ZIP 插件格式。

**值得小灵借鉴**

- `ToolDefinition + ToolExecutor + permission check + lifecycle hook` 的分层。
- 只读、互不依赖的工具可以并行；需要授权或有副作用的工具必须串行。
- 工作流模型采用可序列化节点和连接，执行统计单独保存。
- 记忆检索不能只做向量相似度，应组合关键词、语义、标签、时间和来源。

**不应照搬**

- Root、ADB、Shizuku/无障碍、完整 Ubuntu、可执行 JS 插件市场同时进入产品，会形成过大的攻击面和测试矩阵。
- 工具市场和脚本运行时需要签名、来源信任、能力清单、版本迁移和沙箱；小灵第一阶段不应开放第三方可执行插件。
- WorkManager 的周期任务最小 15 分钟，简化 cron 也无法保证精确触发，产品文案不能承诺“准点执行”。

### 4.3 `ZeroAI`：运行时工程化很强，但项目自己明确仍处实验和加固期

**已验证事实**

- README 明确其为 Kotlin + Rust + UniFFI 的 Android Agent，使用长驻前台服务，Rust Core 负责 tools、memory、config、runtime、channels 和 gateway。
- 项目状态注明 experimental、主要面向近期 Pixel 硬件、大量代码由 AI 辅助生成且仍在审计加固，不能直接视为生产安全基线。
- Rust 工具循环有最大迭代数、共享父/子 Agent 预算、取消、上下文预裁剪、孤立 tool result 修复、循环检测、工具结果截断和耗尽后的无工具总结。
- `tool_execution.rs` 统一记录工具开始/结束、参数、结果、耗时和错误；只在无审批工具时并行执行。
- Android 记忆写入先走启发式提取，再走敏感信息过滤，最后存储；低电量时降级为只读。
- 调度器支持启动时补跑逾期任务、并发上限、重试退避、按 Agent 安全策略执行和结果持久化。

**值得小灵借鉴**

- Agent 循环必须有硬上限、取消信号、共享预算、重复输出熔断和“已做什么/还剩什么”的优雅收尾。
- 观测、脱敏和错误结构应该包在工具执行器外层，避免每个工具重复实现。
- 记忆写入应先做低成本筛选与敏感信息阻断；后台任务不应读取普通聊天记忆，除非用户明确允许。
- 电量、网络和后台限制应成为调度策略输入。

**不应照搬**

- 小灵当前是纯 Kotlin 小型 App，引入 Rust、UniFFI、双语言构建和复杂 FFI 只会显著增加调试、发布和崩溃定位成本。
- HMAC 工具回执能证明“某段代码生成了回执”，不能证明外部世界动作真实成功；小灵应优先做可读审计日志和业务后置校验。
- 子 Agent、24 能力脚本沙箱和大量渠道在单 Agent 闭环稳定前没有必要。

### 4.4 `hermes-android`：最值得借鉴的移动 Agent 操作面

**已验证事实**

- Android App 是 Hermes Gateway 的原生客户端，不在手机内运行 Hermes Core。
- `HermesGatewayClient.kt` 用 WebSocket RPC + event stream，具备 readiness gate、请求关联、连接代次、防重复 socket、指数退避和手动重连。
- `GatewayConnectionService.kt` 用前台服务保持连接，并只在 App 不在前台时发布通知；单个异常事件不会终止事件收集。
- 审批分为 `STANDARD/ELEVATED`：普通风险允许 once/session/always，高风险禁止 always；通知中的高风险操作不提供直接允许，只允许 Deny/Open。
- `NeedsYou.kt` 把失败或逾期的定时任务聚合为需要用户处理的条目。
- 分享入口把 Android `ACTION_SEND` 文本或图片转为 Agent 输入；cron 编辑器把常见日程建模为 Hourly/Daily/Weekly/Monthly，复杂表达式再退回 Advanced。

**值得小灵借鉴**

- 首页优先展示“需要你处理、正在运行、最近完成”，而不是永远落到聊天页。
- 权限动作提供“仅这次/本次任务/始终允许/拒绝”，高风险不允许永久授权。
- 通知只暴露低风险快捷动作，高风险必须回到 App 查看参数后确认。
- Android 分享、快捷入口和通知比“再做一个功能目录”更符合移动端使用方式。

**不应照搬**

- 远程 Gateway 依赖意味着离线不可用、需要长连接认证和协议兼容；小灵第一阶段应以内置运行时为主，未来再把 Gateway 做成可选执行后端。
- 前台服务并不等于无限后台能力；Android 版本对 service type 和运行时长的限制必须实机验证。

### 4.5 `rikkahub`：从聊天客户端平滑演进 Agent 的最佳 Android 参考

**已验证事实**

- `Assistant.kt` 将模型、system prompt、上下文、记忆、近期会话、MCP、本地工具、web search、workspace、skills 和 prompt injection 集中在 Assistant 配置中。
- `Conversation.kt` 使用 `MessageNode` 保存同一位置的多个候选消息与选中索引，支持分支/重生成；会话还能保存 workspace cwd。
- `GenerationHandler.kt` 最多循环 256 step：生成回复，检测 tool parts，需要审批时暂停，批准/拒绝后恢复，执行工具，把结果写回同一个结构化 message part，再继续模型调用。
- `ToolApprovalState` 明确区分 Auto、Pending、Approved、Denied、Answered；工具 part 同时保存 input、output 和 approval state。

**值得小灵借鉴**

- 在小灵现有 Provider/Conversation 基础上新增 `AgentProfile`，先只承载模型、system prompt、上下文策略、记忆开关、允许工具，不要立即复制所有高级字段。
- 消息内容从单一 text 升级为 parts：Text、Reasoning、Tool、Image/Document；这比另建一套无法融入对话的运行日志更自然。
- 工具等待审批是可持久化状态，用户批准后应从原步骤恢复，而不是重新发送整条消息。

**不应照搬**

- 256 步对手机 Agent 过高；早期分析曾建议默认 8 步、硬上限 16 步，`v0.1.10` 实际采用更保守的最多 4 次工具调用预算。
- proot Linux workspace、MCP OAuth、复杂 prompt injection 和角色卡不是首版个人 Agent 必需项。

### 4.6 `X-OmniClaw`：设备 Agent 闭环最完整，但包含大量设备/应用特化

**已验证事实**

- README 明确采用 Observation → Reasoning → Execution，并在系统层扩展为 perceive → plan → act → verify。
- `AgentLoop.kt` 仅保留 Android bridge，核心循环由 Chaquopy Python 实现；默认最大 40 步、LLM 180 秒、普通工具 30 秒，并将工具路由决策发到进度流。
- `DeviceTool.kt` 统一 snapshot、screenshot、act、open、status 和 clipboard；动作依赖最新 snapshot 的 ref，变更页面后使 ref 失效，并提示再次 snapshot 验证。
- 项目包含循环检测、广告误点保护、坐标和 ref 校验、Accessibility tree + screenshot/VLM 双轨和定时任务前台服务。
- 图片记忆在落盘前对身份证、手机号、银行卡、邮箱和高风险关键词做脱敏/分级。

**值得小灵借鉴**

- 设备控制必须是“观察 → 原子动作 → 再观察 → 校验”，不能让模型连续输出一串盲点坐标。
- 屏幕元素引用必须绑定快照并有过期规则；坐标体系不一致时应拒绝动作，而不是猜测换算。
- 快照、动作、验证、工具路由、进度事件应彼此分离。
- 记忆采集先做隐私过滤，图库/通知等高敏数据必须单独授权。

**不应照搬**

- README 要求 Accessibility、Overlay、Screen capture、Photos、All files、Camera、Microphone 七类权限，且设备工具支持 root 打开未导出 Activity；这不适合作为小灵默认权限面。
- 配置写入 `/sdcard/.xomniclaw/xomniclaw.json` 不适合保存 API Key；小灵应继续使用 Keystore 加密。
- `DeviceTool.kt` 中存在剪映、飞书等硬编码修正。小灵不能把具体 App 的坐标/文案修复堆进通用执行器，应通过版本化 skill/app adapter 隔离。
- Kotlin + Python/Chaquopy 双运行时不是小灵当前必要结构。

### 4.7 `mobilerun`：优秀的设备 Agent 框架，不是小灵的产品模板

**已验证事实**

- 框架运行在电脑/服务端，通过 Portal、ADB 或 iOS Portal 控制设备。
- `MobileAgent` 的快速模式直接使用 FastAgent，推理模式使用 Manager 规划 + Executor 动作。
- Manager 每步重新规划和检查终止，Executor 记录 action、summary、outcome、error，再返回 Manager；连续错误会触发重新规划标记。
- 状态模型同时保存当前/前一次 UI、截图、包名、计划、子目标、动作历史、错误、消息和结束状态。
- 坐标动作检查 screenshot-only 坐标范围和当前 coordinate contract；契约丢失时拒绝点击，避免错误坐标落到真实设备。
- 支持轨迹、Langfuse/Phoenix、宏录制、结构化输出、自定义工具和 MCP。

**值得小灵借鉴**

- 将复杂任务拆成 Manager/Executor 是有效模式，但首版可在同一模型中以“Plan step”和“Act step”逻辑分层，不需要两套模型。
- 每个设备动作记录 before snapshot、action、after snapshot、outcome，形成可回放 trajectory。
- 坐标契约、屏幕尺寸和快照 ID 必须作为执行前置条件。
- 可复现轨迹适合未来做 Android Agent 回归测试。

**不应照搬**

- ADB/Portal/电脑端 Python 框架无法直接嵌入小灵 Android App。
- 公开或上传完整截图轨迹存在隐私风险，遥测必须默认关闭并做脱敏。

### 4.8 `meow-agent`：最贴近小灵目标的产品与安全架构参考

**已验证事实**

- 产品目标是 Android 个人 companion，明确声明 capability-scoped、permission-aware、verification-first、local-first 和 user-controlled。
- Runtime 采用 Analyze → Reflect → Plan → Execute → Verify → Review → Verbalize；可以根据置信度跳过 Reflect/Plan，降低简单任务成本。
- ModulePlugin 自注册工具；`ToolDefinition` 由注册表声明 risk、requiresConfirmation、operation、target、postconditions 和 verificationProbe，风险不接受模型自报。
- ToolRouter 在 dispatch 前执行权限和确认；敏感动作停车等待用户确认，批准后恢复。
- `TaskLedger` 持久化目标树、完成条件、历史结果、当前步骤、待确认工具和状态，App 重启后可恢复。
- `RecoveryCoordinator` 默认最多两次恢复，结构性失败直接放弃，相同工具/参数/原因连续失败不再重试。
- Provider Key 存在 secure storage，SQLite 只保存引用；项目还提供工具权限覆盖测试。

**值得小灵借鉴**

- 这是小灵首个 Agent 运行时最适合参考的骨架：工具注册表、权限策略、确认管理器、执行后验证、任务账本和有界恢复。
- 简单请求走 direct/fast path，复杂请求再进入计划阶段，避免所有聊天都付出多次 LLM 调用。
- 模块默认关闭，模块开关和 Android 权限是两层独立门禁；应有测试保证每个工具都绑定权限规则，防止 fail-open。
- 任务账本和长期记忆必须分开：账本服务一项任务，记忆服务用户长期信息。

**不应照搬**

- 多阶段每阶段都调用 LLM 会显著增加延迟和成本；小灵第一版应使用单循环、按需生成简短 plan。
- Flutter 架构不能直接迁移到 Kotlin/Compose，借鉴领域模型和状态机即可。
- “所有动作都需确认”的产品文案与源码中的 safe/sensitive-lite 自动执行存在粒度差异；小灵应在 UI 清晰呈现实际策略。

### 4.9 `skales`：适合参考 autonomous UX，不适合直接迁移

**已验证事实**

- Electron/Next.js 项目包含 goals/tasks、autopilot、自主 runner、message queue、approval store、killswitch、memory retrieval、skill dispatcher，以及 browser/email/calendar/computer-use 等 actions。
- 项目把“停止所有自主执行”的 killswitch 和审批存储作为独立模块，而不是藏在聊天状态中。

**值得小灵借鉴**

- 后台自主任务必须有全局暂停、单任务取消和明显的运行状态。
- 自主任务 UI 应围绕 goal、next action、blocked reason、last result，而不是只显示聊天记录。

**不应照搬**

- 桌面 Electron 的常驻能力、文件权限和浏览器控制与 Android 生命周期差异很大。

### 4.10 `OGAM`：离线模型资源管理和工具路由的未来参考

**已验证事实**

- 项目是 React Native + 原生推理的 local-first AI suite，包含本地/远程 provider、模型下载、模型驻留、内存预算、RAG、MCP、工具注册和 generation tool loop。
- 源码把工具调用结果建成带 success/error/duration 的结构；工具循环、上下文压缩、embedding 工具路由和模型内存驻留是独立服务。
- 项目 docs 主动记录真实设备 OOM、工具解析、停止不生效、MCP 首次路由延迟和测试漏跑等缺口。

**值得小灵借鉴**

- 若未来做本地模型，必须先有机型分级、内存预算、单一权威的模型适配判断、下载恢复和真实设备测试矩阵。
- 工具过多时先做确定性 shortlist，再考虑 embedding 路由，不能把全部 schema 永远塞入上下文。
- 把已知缺口和实机失败作为一等文档资产，比只记录成功路径更有价值。

**不应照搬**

- 小灵当前没有本地推理需求，不应为了“离线 Agent”立即引入 llama.cpp/LiteRT、模型下载、GPU/NPU 和多模型驻留。

## 5. 对小灵当前状态的判断

截至 `v0.1.13`，小灵已经具备可靠聊天底座和可执行应用内任务的最小 Agent 闭环：

- 多 Provider、模型发现和启用列表。
- Chat Completions / Responses API，以及保留 system/user/assistant 边界的消息和通过 `call_id` 关联的函数调用/结果 typed Items。
- SSE 流式输出与 30ms UI 节流。
- 多轮会话、本地保存和摘要压缩。
- Android 系统分享入口 v1：单文本或单张受支持图片进入可编辑新会话草稿，冲突需显式确认，不自动发送，不信任外部来源标记。
- Markdown、错误分类、结构化消息元数据。
- API Key 使用 Android Keystore + AES-GCM。
- `/agent` 与普通聊天分流，具备 `AgentRun / AgentStep / ApprovalRequest / RunEvent`、运行预算、超时、取消和终态收敛。
- 应用侧 `ToolRegistry`、风险分级、交互审批和执行后验证，以及当前时间、会话检索、本机笔记、长期记忆和只读本地知识库工具。
- 设备 Agent 观察与有限动作层：默认关闭的独立开关、Accessibility 四态健康检查、`device.snapshot / open_app / back / home / tap_ref / type_text / swipe`、200 节点/4000 字符预算、30 秒 ref、页面 generation/路径/指纹失效、敏感节点脱敏、高敏窗口/隐私应用整窗拒绝、白名单与敏感输入策略；完整限定集合开放给前台直接 `/agent` 和前台手动 Workflow，后台执行继续关闭。
- Tool Registry 已统一完整 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、前后台来源门禁、超时和回读验证策略；重复工具名启动失败，权限检查默认 fail-closed。
- 执行回执已持久化 ToolCall、operation、提交状态和执行时重放声明；`notes.create` 与 `memory.remember` 均为生产 `IDEMPOTENT_BY_KEY` 工具。笔记使用 ToolCall ID 的 Room 唯一索引，记忆使用独立 operation ledger 和提交结果快照；载荷漂移会被拒绝。进程重建时这两个白名单工具可依据完整历史证据回读原 operation，补齐后置验证和本地总结；通用工具在所有成功结果与 `PASSED` 验证均已落库后，还可只恢复控制面收尾，不重放工具或调用模型。
- 对话 Run 时间线、审批卡片和设置页 Agent 任务中心；任务中心支持状态筛选、完整 ToolResult、失败终态安全重新运行，以及 `memory.remember` 恢复失败的稳定错误码、原因和新 Run 建议。
- `MessageOrigin / VerifiedAgentContext` 可信来源边界和三类独立提示词设置。
- Workflow Ledger、一次性 WorkManager 非精确定时、计划/实际时间、结果通知，以及后台审批 `BLOCKED` 终态。
- `LlmProviderAdapter / OpenAiCompatibleAdapter` 协议边界，HTTP 传输与 Provider 请求/响应映射已分离。
- ToolCall、ToolResult、审批和恢复事件使用独立 `RunEventMetadata`，运行记录 UI 不再解析 message JSON。
- Room v20 已新增独立 `agent_tool_calls / agent_tool_results`，由 Repository 与 typed RunEvent 原子双写并提供单 Run/批量查询；任务中心、两个白名单写工具的受限恢复和失败 Run 重试副作用判断对新 Run 使用 Ledger-first，旧 Run 在账本全空时保守回退 typed 事件。
- Room v21 已新增 `agent_profiles`；设置页可管理多个 Agent，新 Run 冻结 Profile typed event，工具/Skill 白名单和记忆开关在执行与恢复路径保持硬边界，前台/后台 Workflow 一次执行固定同一 Profile。
- Room v22 已新增 `message_parts`，Text 与 Tool 在同一消息中按 sequence 持久化；Tool part 由 `AGENT_RESULT + VerifiedAgentContext` 可信投影，普通聊天无法把文本声称升级为工具事实。前台会话和后台 Workflow 已统一走 MessageRepository 写入；普通前台快照只增量 upsert，显式删除 ID 与后台新建会话可以正确共存。
- Room v23 已为 Reasoning part 增加供应商 summary 来源、item ID 和 summary index；非流式与 SSE 流式 Responses 只接收 `summary_text`，Compose 默认折叠。原始思维链不进入正文、parts、debug 响应日志或可信 Agent 上下文。
- Room v24 已为 USER Image part 增加 MIME、文件名、BLOB 和 detail；系统选择器图片经过 8 MB 有界读取、签名和解码校验，Responses 使用 Data URL，Compose 支持预览/移除/历史展示。图片 BLOB 只为当前会话加载，轻量快照保留未加载 BLOB，请求等待 Room 提交，陈旧前台快照不能复活已删会话。Chat Completions 与可信工具上下文不接收 Image；`/agent` 仅在 Responses 规划请求接收 USER 单一 Image。
- Room v25 已为 USER Document part 增加受限提取文本、PDF 页数和 detail，并复用 MIME、文件名与 BLOB。Document 支持 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX、XLSX；8 MB、50 页、200,000 UTF-8 字符及 OpenXML 的 ZIP/OPC 根节点、加密、条目数和展开预算在进入消息前执行。Responses 使用 `input_file` Data URL，Compose 支持附件菜单、待发送/历史元数据和移除。Document 与 Image 互斥、按当前会话加载；Chat Completions 与可信工具上下文不接收 Document，`/agent` 仅在 Responses 规划请求接收 USER 单一 Document。
- 设置页长期记忆管理支持 FTS4 + 中文子串兜底搜索、状态筛选、编辑、置顶、启停、删除确认和来源会话/Run 跳转；禁用或删除后不再参与 Agent 检索。
- Room v4、v6-v29 Schema 已导出；迁移测试覆盖历史 Provider、会话、Run、审批、记忆、Skill、Workflow、调度、多步骤快照、笔记幂等索引、记忆 operation ledger、独立工具账本、Agent Profile、消息 parts、知识引用和进程退出观察演进。v26→v27 只增加默认空引用列，v28→v29 只创建空退出观察表；迁移不补造旧 operation、旧 Run、全局 Agent 身份、Tool、Reasoning、Image、Document、KnowledgeReference 或 Task/Run 退出归因。

现有关键实现位于：

- `app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt`
- `app/src/main/java/com/longdev/xiaoling/ui/ConversationRequestContextPreparer.kt`
- `app/src/main/java/com/longdev/xiaoling/ui/ConversationSendCoordinator.kt`
- `app/src/main/java/com/longdev/xiaoling/ui/ConversationSessionPolicy.kt`
- `app/src/main/java/com/longdev/xiaoling/ui/ConversationPersistenceCoordinator.kt`
- `app/src/main/java/com/longdev/xiaoling/ui/ConversationSelectionCoordinator.kt`
- `app/src/main/java/com/longdev/xiaoling/network/OpenAiCompatibleClient.kt`
- `app/src/main/java/com/longdev/xiaoling/agent/MinimalAgentRuntime.kt`
- `app/src/main/java/com/longdev/xiaoling/agent/XiaoLingToolRegistry.kt`
- `app/src/main/java/com/longdev/xiaoling/device/DeviceObservationController.kt`
- `app/src/main/java/com/longdev/xiaoling/device/DeviceSnapshotPolicy.kt`
- `app/src/main/java/com/longdev/xiaoling/device/XiaoLingAccessibilityService.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/RoomAgentRunRepository.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/RoomAgentMemoryStore.kt`
- `app/src/main/java/com/longdev/xiaoling/prompt/PromptPolicy.kt`
- `app/src/main/java/com/longdev/xiaoling/storage/SecureConfigStore.kt`

最小闭环已经落地，但距离完整个人 Agent 仍有以下缺口：

| 缺口 | 当前影响 |
|---|---|
| AgentProfile、Text/Reasoning/Image/Document/Tool parts 与答案引用 UI 已完成 | Agent 身份、模型、自然语言、用户附件、供应商摘要、工具事实和结构化知识引用已进入稳定边界；富文档直传、RAG Agent 接入、历史状态标记和知识库跳转均已完成 |
| Runtime 已支持最多 4 步顺序工具循环，但不支持并行调用 | 可以根据上一步已验证结果继续选择工具；互不依赖的只读工具仍无法并行降低延迟 |
| ToolCall/ToolResult 已独立落表，任务中心、恢复与重试判断已 Ledger-first | v20 新 Run 按调用展示四阶段，以账本恢复证据和副作用证据为准，事件只核对原子双写一致性；异常账本的重试保守要求确认，旧 Run 账本全空时回退 typed 事件。全部结果与验证已落库时可恢复本地收尾 |
| 提交状态未知或验证事实不完整的通用执行栈仍不原地恢复 | 进程重建后默认收敛中间态，再由用户创建关联新 Run；`notes.create / memory.remember` 可从完整已提交证据恢复受限只读验证，任意工具在全部 `PASSED` 后可恢复控制面收尾，但都不能继续旧规划或 Workflow 后续步骤 |
| 长期记忆治理已形成首版闭环，但召回质量仍需规模化验证 | 已有候选确认、敏感过滤、去重/冲突、跨进程删除撤销、过期策略、时间衰减、实际引用审计和单次召回关闭；更大数据量下仍需验证排序与中文召回质量 |
| 后台账本与周期规则已完成，但通用执行栈不续跑 | 一次性与 Daily/Weekly 非精确定时可追溯；步骤结果落库后的进程终止可启动对账并保留成功前缀，运行中停止先持久化 `STOP_REQUESTED` 并可跨系统取消/fallback 异常重对账；Room v32 继续沿用独立账本观察平台退出，受控样本不算自然 LMK，仍不支持提前引入 Foreground Service |
| PDF/UTF-8 与 DOCX/PPTX/XLSX 直传、RAG 基础、Embedding、Agent 接入和答案引用 UI 已完成 | 已具备文档身份、解析、分块、FTS/LIKE、有限规模 Embedding、管理 UI、结构化引用、历史/不可用标记和删除失效契约；尚缺 ANN/规模化检索质量验证 |
| 设备 Agent 观察与有限动作已完成 | 已能安全观察并在首批白名单 App 执行返回/主页、点击、普通输入和节点滚动，所有动作后重新观察验证；前台手动 Workflow 已有限开放 `snapshot / back / home / tap_ref / type_text`，后台自动化和任意 App 仍关闭 |

## 6. 建议目标架构

保持 Kotlin + Compose + OkHttp，不新增 Rust/Python/Flutter。当前已形成 `agent`、`data`、`storage`、`prompt` 和普通聊天 application service 最小边界；上下文准备、发送编排和会话纯状态投影已有独立实现，后续继续细分 domain/runtime/tools/approval/verification/memory/task，并复用现有 network 与 Keystore。

最小状态机为：`QUEUED -> THINKING -> WAITING_APPROVAL -> EXECUTING -> VERIFYING -> THINKING/COMPLETED`，并允许进入 `BLOCKED/FAILED/CANCELLED/BUDGET_EXHAUSTED`。后台规划到需审批工具时直接进入 `BLOCKED`，不进入交互审批等待。

关键规则：

- 当前前台和后台 Agent Run 均采用最多 4 次工具调用的有界预算；未来提高上限前必须重新验证延迟、成本和循环风险。
- 同一工具和规范化参数连续重复两次警告、三次阻断。
- 工具超时、用户取消、进程重启都产生可解释终态。
- 工具结果进入模型前截断并脱敏，完整结果保存在本地审计表。
- `WAITING_APPROVAL` 持久化，重启后仍可批准或拒绝。
- `AgentTool` 只暴露代码注册的 `ToolDefinition` 与 `execute()`；definition 固定声明风险、Android 权限、超时、后台能力和验证器，模型不能降级这些字段。

## 7. 分阶段功能开发与迭代清单

### P0：Agent 运行时底座

目标：让小灵能安全、可观察地执行第一批只读工具，而不是直接做手机自动化。

当前状态：Room v32 Schema、迁移测试、Agent Profile v1、Text/Reasoning/Image/Document/Tool 消息 parts、知识文档/chunks/FTS/LIKE/Embedding/检索审计与管理 UI、`knowledge.search`、答案引用 UI、独立进程退出观察、RunEvent typed metadata、独立 ToolCall/ToolResult Ledger、完整 Tool Registry 契约、AgentRuntime、审批/验证、任务中心、长期记忆治理和 Workflow 调度已完成。知识引用已贯穿规划历史、可信上下文、Workflow 输出和可展开引用区域；禁用、替换或删除后历史审计保留，UI 明确标记历史/不可用状态，失效消息、旧摘要和 Workflow 前序正文不再进入新模型请求。Embedding 当前采用有限规模内存 cosine + 稳定 RRF，已有固定语料质量门禁、实际检索路径诊断、top1/top2/margin 及候选均值/标准差/top1 z shadow 审计；answerability 已完成严格 Judge、真实 Provider shadow、提示/绑定、默认关闭的线上 measurement 协调，以及生产 adapter、答案保存后异步 caller、设置开关和进程内 notice。Provider 失败或模型列表没有 Embedding 模型时仍回退词法结果，生产相关性/answerability enforcement 与 Room shadow Store 尚未启用。旧 Profile 和无 Profile 审计的历史 Run 不因新工具自动扩权；所有验证事实落库后的控制面收尾已可恢复，其他执行/验证中断继续采用旧 Run/活动 Step 一致取消和关联新 Run 重试。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| Room 存储 | 新建 Provider、Conversation、Message、AgentRun、AgentStep、ToolCall、Approval 表；从 SharedPreferences 一次性迁移 | 升级不丢现有 Provider/会话；迁移可重复且有单测 |
| 消息 parts 与知识库 | Text/Reasoning/Image/Document/Tool 已完成独立 Room 表、用户附件和可信 Tool 投影；Room v32 已补知识全文/chunks/FTS/LIKE/Embedding、绝对与相对 shadow 分数审计、管理 UI、只读 Agent 检索、引用持久化、模型上下文失效过滤，以及默认关闭的 answerability measurement、生产 adapter、保存后 caller 和进程内 notice | 工具步骤和知识引用可恢复；替换后旧 chunk 引用不进入新模型上下文，历史审计不回写，原始思维链与 Agent 工具事实保持隔离；Room shadow Store、notice 跨进程恢复和 enforcement 仍未开放 |
| AgentProfile v1 | 已完成 name、avatar、provider/model、API mode、systemPrompt、contextPolicy、allowedTools、allowedSkills、memoryEnabled、Run 快照和恢复门禁 | 可创建多个 Agent，并为每个 Agent 选择不同模型与工具；Redmi 真实模型验收通过 |
| ToolRegistry | 工具定义、JSON Schema、风险、权限、超时、后台能力、验证器统一注册 | 未注册工具永远不能执行；重复名称启动时报错 |
| AgentRuntime v1 | LLM → tool call → permission → execute → tool result → LLM；支持取消、最多 4 次工具调用、超时和重复检测 | 模拟工具链成功、失败、拒绝、取消、超时、预算耗尽均有自动化测试 |
| 可观测运行 UI | 展示当前步骤、工具名、参数摘要、结果摘要、耗时和停止按钮 | 用户能区分“模型正在想”和“工具正在做” |

首批应用内工具现已完成：

- `app.current_time`：当前时间/时区。
- `app.list_conversations` / `app.search_conversations`：只读列出和检索本地会话。
- `notes.list` / `notes.search` / `notes.create`：本机笔记读取与确认后写入、回读验证。
- `memory.search`：只读检索已授权记忆。
- `memory.remember`：确认后写入带来源的长期记忆。
- `knowledge.search`：只读检索本地知识库并返回稳定 document/revision/chunk/offset 引用。

仍待评估的后续工具包括受限 `web.fetch`、显式 `ask_user` 和应用信息读取。

### P1：个人化、记忆和移动入口

目标：小灵开始“认识用户”，但记忆必须透明可控。

当前状态：记忆表、工具读写、来源审计、FTS4 + 中文兜底检索、管理页、编辑、置顶、启停、候选生成与确认、敏感过滤、去重/冲突、跨进程删除撤销、过期策略、时间衰减、本轮引用审计和单次召回关闭已完成；更大数据量下的召回质量仍待验证。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| 长期记忆 v1 | 已完成管理与候选闭环；从用户明确陈述生成 preference/profile 候选，再由用户确认保存 | 每条记忆显示来源会话/Run 和时间，可编辑、置顶、禁用、删除；候选功能默认关闭 |
| 敏感过滤 | 已完成 API Key、token、密码、银行卡、身份证、手机号阻断，候选和 `memory.remember` 共用策略 | 固定敏感样例测试全部通过；命中时只保存类别和固定提示，不保存完整敏感值 |
| 记忆召回 | 已完成 FTS4 + 中文子串兜底、时间衰减、本轮引用审计和单次召回关闭；数据规模扩大后再评估 embedding | 删除、禁用或过期后不再召回；任务中心可查看实际使用的记忆 ID |
| 分享给小灵 | 支持 Android `ACTION_SEND` 文本、链接、图片，进入新任务草稿而非静默执行 | 分享后必须由用户确认发送；来源 App 和附件可见 |
| 语音输入 | 先做系统 SpeechRecognizer/录音转写到输入框，不自动执行 | 用户可编辑转写文本后再发送；权限拒绝可正常降级 |
| 快捷任务模板 | 用户保存 prompt + Agent + 默认参数，不保存高风险永久授权 | 模板执行前展示输入和将使用的 Agent/工具 |

### P2：任务账本、定时任务和“需要你处理”

目标：支持长任务和计划任务，但不夸大 Android 后台可靠性。

当前状态：Workflow/ScheduledTask Ledger、1 至 8 步顺序执行、一次性及 Daily/Weekly 非精确定时、取消、计划/实际时间、完成/失败/blocked 通知和安全新 Run 重试已交付；任务中心新增“需确认”筛选，聚合提交未知、已提交或证据不完整且必须确认后才能创建关联新 Run 的终态任务。第 46 阶段记录强制 Doze 延迟、trim-memory、无压力对照并修复迟到协程覆盖 AgentRun 终态；第 47 阶段完成进程内 Worker 所有权和启动恢复候选隔离；第 48、50 阶段先后完成可见停止、终态子账本冻结和 `STOP_REQUESTED` 持久化异常重对账。它不是通用“需要你处理”首页，也不包含活动审批；自然 LMK、后台通用执行栈续跑、精确定时和跨任务聚合首页仍待完成。

| 要做什么 | 怎么做 | 验收标准 |
|---|---|---|
| TaskLedger | 保存 goal、steps、currentStep、priorResults、pendingApproval、status、retryCount | App 被杀后重新打开可看到任务状态；待确认动作能继续处理 |
| Activity 首页 | 三段：需要你处理、运行中、最近完成；失败/逾期/待确认置顶 | 冷启动不会误显示旧聊天为正在运行 |
| 定时任务 v1 | 一次性及 Daily/Weekly 已完成，WorkManager 继续承担非精确任务；精确定时另行评估 AlarmManager 与权限 | UI 明确“系统可能延迟”；展示预计下次运行和上次结果 |
| 后台安全策略 | 已完成只允许 `supportsBackground=true` 的 SAFE 只读工具且不继承前台授权 | 后台调用需审批工具时转为 BLOCKED 并通知用户 |
| 通知 | 完成/失败/待确认；低风险可快捷操作，高风险只允许打开 App/拒绝 | 通知 action 有单测；高风险不存在一键永久允许 |
| 有界恢复 | 网络/临时失败最多重试两次并退避；权限拒绝等结构性失败不重试 | 不出现无限重试；失败原因和已完成步骤可见 |

精确闹钟、锁屏亮屏和全屏 Intent 需要单独评估政策与权限，不能默认加入 v1。

### P3：有限设备 Agent

目标：先建立可解释的只读设备观察，再逐步开放可控系统动作；不请求 Overlay 或 Root。

当前状态：第 1 至 6 步已完成，并通过分阶段 JVM、仅 Redmi instrumentation 及 instrumentation 外真实 AccessibilityService/动作验收；前台直接 `/agent` 的完整限定设备工具集保持不变。前台手动 Workflow 又逐项完成 `snapshot / tap_ref / type_text / back / home / open_app / swipe`，其中 `open_app / tap_ref / type_text` 使用 Room/Accessibility 逐动作审批，`back / home / swipe` 为零审批 SAFE 动作。所有动作都要求当前观察和 Executor/typed 后置验证；`open_app` 在 SafetyPolicy、ApprovalGate 与 Executor 三层限制首批包名，完成门禁和答案级 Room 重建还会再次核对后置包名等于获批目标，`home` 必须匹配系统动态解析的 launcher，`swipe` 必须证明同窗内容变化和共同匿名锚点的请求方向主位移。所有 ToolResult 与 `PASSED` 验证均持久化后的原 Run 本地收尾恢复也已通过故障注入和磁盘 Room 重开测试。规划、工具与总结段共享单调累计 Run 预算，重试前统一呈现副作用证据分类，并在确认提交前同时校验证据码与 Ledger/Event 指纹不变；旧验证事件缺少 ToolCall ID 时固定标为关联未知，不再按工具名或顺序猜配。Worker 重入、Doze、trim-memory、无压力对照、终态竞态、当前进程所有权隔离、持久停止重对账和独立进程退出观察均已形成证据，但不等同 Android 自主回收。第 121 阶段 `open_app` 与第 126 阶段 `swipe` 的真实生产 tracer 均已通过；能力继续限定首批 App，全部后台设备自动化继续关闭。

长任务可靠性现已补充确定性断点、启动证据快照、Worker 重入收敛、进程内所有权隔离、持久化停止栅栏和 Redmi 系统策略样本：Workflow 第一步结果事务提交、第二步尚未启动时模拟进程终止，启动对账保留完成前缀并关闭旧 Run；不可恢复的 Agent Run 会在收敛前冻结重试证据码，Worker 重入只按当前 ScheduledTask 关联链定向关闭旧执行栈。前台初始化先冻结旧 Agent/Workflow/Task ID，并排除当前进程真正 `RUNNING` 的 Worker 链；`STOP_REQUESTED` 即使仍登记所有权也进入恢复，Workflow/Task 在同一事务读取栅栏并原子取消。停止发生在 Agent Run 关联前时，Workflow 恢复也优先读取关联 Task 的停止栅栏，取消未完成步骤而不生成失败终态或新 Run。停止 fallback 也改为一次事务结算 Workflow/Task，既有 Workflow 终态会直接修复半结算 Task，避免通用停止栅栏制造矛盾终态。Redmi 受控强杀样本在 `3360ms` 内只收敛关联链；强制 Doze 在 20 秒内保持任务未启动，8 步成功样本约 62.2 秒。退出 Doze 和 trim-memory 的 `connection closed` 仅为观察，不能归因；无压力对照暴露的 Task/Workflow `CANCELLED` 与 AgentRun `COMPLETED` 竞态已通过原子终态写入修复。上述命令均不等同 Android 自主回收，也不等同通用执行栈原地续跑；前台 Workflow 设备工具仅限 `snapshot / open_app / back / home / tap_ref / type_text`，后台权限继续关闭。

实施顺序：

1. **已完成：Accessibility 授权与健康检查**：明确说明能力和隐私影响，默认关闭；区分未授权、服务未连接、权限失效和服务正常。
2. **已完成：只读 snapshot**：输出有界、结构化、脱敏的可访问节点树；密码框、验证码、支付页和隐私应用默认不返回正文。
3. **已完成：短生命周期节点引用**：ref 绑定 snapshot 和窗口状态，页面变化、超时、失败或关闭开关后立即失效。
4. **已完成：有限动作工具**：前台直接 `/agent` 已加入 `open_app / back / home / tap_ref / type_text / swipe`；前台 Workflow 已逐项开放 `open_app / back / home / tap_ref / type_text`，`swipe` 仍等待更强的后置滚动验证。副作用动作按风险审批，应用与输入范围由确定性策略限制。
5. **已完成：观察-动作-验证**：每次动作后重新抓取 snapshot；不只凭 Android API 返回成功判断业务完成，也不把过期 ref 降级成坐标点击。
6. **已完成：限定 App 验收与可回放轨迹**：在 Redmi 上覆盖小灵、系统计算器、时钟、设置和桌面，记录脱敏 before/action/after/outcome；暂不承诺任意 App。

P3 明确不做：Root、Shizuku、静默安装 APK、绕过未导出 Activity、所有文件访问、跨 App 密码/支付自动化。

### P4：生态与离线能力

这些能力只有在 P0-P3 稳定并有真实使用数据后再做：

| 能力 | 前置条件 | 建议方案 |
|---|---|---|
| MCP client | ToolRegistry/审批/审计稳定 | 先支持 remote HTTP MCP；逐 server/逐 tool 开关；工具风险不能全部默认为 safe |
| Skills | 有稳定工具和 prompt 装载边界 | 版本化本地声明式 JSON Skill、严格校验和管理 UI 已完成；后续格式仍不得执行任意脚本 |
| Workflow 编辑器 | TaskLedger/调度/节点执行稳定 | 当前已支持 1 至 8 个顺序 Agent 步骤；未来如扩展图编辑器，再增加 trigger + tool + condition 节点 |
| Remote Gateway | 内置运行时稳定且确有跨设备需求 | 抽象 `AgentBackend`，本地/远程共用事件协议和审批模型 |
| 本地模型 | 有明确离线需求和目标机型 | 先做小模型下载、内存预算、能力探测和真机矩阵，再接入 Agent |
| 多 Agent/委派 | 单 Agent 任务成功率和观测完善 | 子 Agent 只能获得父 Agent 权限子集，并共享总步数/成本预算 |

## 8. 安全与隐私基线

下列规则应在引入第一个工具时就落地，不能后补：

1. 默认拒绝：未知工具、缺失权限规则、无法解析参数、风险未知时都不执行。
2. 风险由注册表定义：模型输出不能修改 `risk`、`requiresConfirmation` 或 `requiredPermissions`。
3. 授权有范围：once、task、always；危险工具不提供 always。
4. 触发来源隔离：前台用户、分享入口、通知、定时任务、远程入口使用不同默认策略。
5. 工具参数可见：确认页展示自然语言说明、目标对象和关键参数；敏感字段脱敏。
6. 执行后验证：成功返回只是候选成功；副作用工具必须有 probe 或提示“未验证”。
7. 记忆可追溯：保存来源、时间、Agent、敏感级别；支持删除、导出和关闭召回。
8. 后台不继承临时授权：前台点过一次允许，不代表 cron 可以重复执行。
9. 远程内容不可信：网页、消息、附件可能包含 prompt injection，不能改变工具策略。
10. 调试和遥测默认不上传原始 prompt、API Key、截图、联系人、通知和工具结果。

## 9. 不建议现在做的功能

- 不引入 Rust/UniFFI、Python/Chaquopy 或 Flutter 重写。
- 不做 Root/Shizuku/ADB 常驻控制和完整 Linux 环境。
- 不做第三方可执行脚本/ToolPkg 市场。
- 不默认请求 Accessibility、Overlay、All files、Camera、Microphone 全套权限。
- 不做全渠道机器人和多 Agent 编排。
- 不把所有工具 schema 永远塞入 prompt。
- 不用向量数据库替代清晰的记忆来源、确认和删除机制。
- 不承诺 Android 定时任务绝对准点或进程永久在线。
- 不把工具返回 `success=true` 当作任务完成证明。

## 10. 主要本地证据路径

以下路径均相对 `/Users/long/Documents/CodexProjects/endpoint-test/reference-apps/`。

| 项目 | 主要证据 |
|---|---|
| 全量分类 | 各仓库根 README；`Agora/docs/en/index.md`；`ClaraVerse/docs/ARCHITECTURE.md`；`opencyvis-phone/docs/architecture.md`；`vFlow/docs/vFlow_App_Architecture.md` |
| `openclaw` | `README.md`；`docs/agent-runtime-architecture.md`；`docs/gateway/sandboxing.md`；`packages/agent-core/src/agent-loop.ts`；`src/agents/tool-policy-pipeline.ts` |
| `Operit` | `README.md`；`docs/TOOLPKG_FORMAT_GUIDE.md`；`app/src/main/java/com/ai/assistance/operit/api/chat/enhance/ToolExecutionManager.kt`；`ui/permissions/ToolPermissionSystem.kt`；`core/workflow/WorkflowScheduler.kt`；`data/repository/MemoryRepository.kt` |
| `ZeroAI` | `README.md`；`zeroclaw/crates/zeroclaw-runtime/src/agent/loop_.rs`；`agent/tool_execution.rs`；`cron/scheduler.rs`；`app/src/main/java/com/zeroclaw/android/memory/MemoryExtractionPipeline.kt`；`SensitivityFilter.kt` |
| `hermes-android` | `README.md`；`docs/superpowers/specs/2026-07-10-tiered-approvals-design.md`；`data/network/HermesGatewayClient.kt`；`ui/chat/ApprovalTier.kt`；`notifications/GatewayConnectionService.kt`；`ui/activity/NeedsYou.kt` |
| `rikkahub` | `docs/references/chat-generation-pipeline.md`；`data/model/Assistant.kt`；`data/model/Conversation.kt`；`data/ai/GenerationHandler.kt`；`ai/.../ui/Message.kt` |
| `X-OmniClaw` | `README.md`；`agent/loop/AgentLoop.kt`；`agent/tools/device/DeviceTool.kt`；`agent/memory/gallery/ImageMemoryPrivacyFilter.kt`；均从 `HEAD` Git 对象读取，未恢复工作区删除 |
| `mobilerun` | `README.md`；`mobilerun/agent/droid/droid_agent.py`；`droid/state.py`；`manager/manager_agent.py`；`executor/executor_agent.py`；`utils/actions.py` |
| `meow-agent` | `README.md`；`ARCHITECTURE.md`；`MODULE.md`；`lib/services/agent_runtime/runtime_engine.dart`；`tool_router.dart`；`completion_verifier.dart`；`task_ledger.dart`；`recovery_coordinator.dart` |
| `skales` / `OGAM` | `skales/README.md`、`apps/web/src/lib/{autonomous-runner,approval-store,killswitch}.ts`；`OGAM/README.md`、`docs/ARCHITECTURE.md`、`docs/GAPS_BACKLOG.md`、`src/services/{generationToolLoop,contextCompaction,memoryBudget}.ts` |

## 11. 最终建议

小灵已经完成了此前建议的设备 Agent 观察与有限动作里程碑：

> 用户显式启用 Accessibility 后，小灵能报告服务健康状态，生成有界且脱敏的结构化 snapshot，为可操作节点分配短生命周期 ref；页面变化、权限失效、隐私页面或 ref 过期时明确拒绝继续。首批白名单 App 已开放带风险审批、敏感输入过滤和动作后验证的标准节点操作，不使用坐标、截图或任意 App 扩权。

下一版不应把系统分享 v1 扩成任意 Intent、任意文件、自动发送或后台处理，也不应跳到 MCP 或“任意控制手机”。第 97 至 101 项已记录窗口人工合计 Shadow 样本 `10`、有效 Judge `8`：直接回答 `5`、部分回答 `3`，另有两条无候选跳过；未进入 Shadow 的预算或工具步数耗尽没有冒充 Judge 失败。八次 Judge 均成功，因此当前证据仍不足以描述自然 Judge 失败分布，也不足以支持 Room Store 或 enforcement。第 99 阶段和第 101 项首个窗口的有效样本来自词法兜底，不能外推为 Embedding 质量证据。下一步只在间隔开的真实使用窗口低频观察真实 Provider 成本以及网络、协议、认证等自然失败；出现新证据或明显成本异常后，再独立评审最小化持久化，在隐私设计完成前不开启 enforcement。累计执行预算、Workflow 启动对账、需确认聚合、结构化安全处置、Worker 所有权、可见停止和 `STOP_REQUESTED` 栅栏均已完成；Redmi 已有约 229.416 秒复合只读成功样本，仍无自然 LMK，因此 Foreground Service 继续证据驱动。前台 Workflow 设备工具当前精确为 `snapshot / open_app / back / home / tap_ref / type_text / swipe`；第 126 阶段已完成生产默认集合接入和仅 Redmi 真实生产 Workflow 验收，后台自动化继续关闭。下一步应转向更高层个人 Agent 能力，不把单个限定页面的成功外推为任意 App；精确定时继续依据真实需求决定，日历/通知、MCP、远程 Channel、多 Agent 和本地模型保持最后推进。
