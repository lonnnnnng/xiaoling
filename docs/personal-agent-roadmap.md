# 小灵个人 Agent 路线图

## 横向工程：Agent 启动前校验协调迁出（完成）

普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批的会话、Profile、工具注册与 Provider 校验已从 `XiaoLingViewModel` 迁入独立 `AgentLaunchPreflightCoordinator`。普通 `/agent` 保持可创建新会话；其余入口先要求指定会话存在。普通、Workflow 与两类重试使用当前 Profile，恢复审批优先使用原 Run Profile 快照，旧 Run 无有效快照时才回退当前 Profile。校验成功后的 UI、附件、Room 和 Runtime 副作用仍留在 ViewModel，长 Workflow 继续使用入口冻结配置。

运行配置中的解密 API Key 只允许进程内传递，`ProviderRequestConfig.toString()` 已对 Base URL、API Key 和自定义 Header 做类型级脱敏。聚焦 JVM 为 Coordinator `10/10`、脱敏 `1/1`；强制完整门禁为 `140/140` tasks、JVM `656/656`、Lint `0 error / 50 warnings / 0 information` 和三类 APK，仅 Redmi 默认完整 `196` 条（`184 passed / 12 skipped / 0 failed`）、耗时 `48.8s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：Provider 模型同步协调迁出（完成）

Provider `/models` 请求、模型去重/回退、失败分型、批量顺序和完整快照提交已从 `XiaoLingViewModel` 迁入独立 `ProviderModelSyncCoordinator`。批量同步按输入顺序执行，普通失败继续下一项，取消立即终止；并行单项只在提交阶段通过 Mutex 串行。提交端以最新 Provider 快照和规范化身份拒绝删除、配置漂移或保存期间变化，保留最新名称与仍有效模型，Room 保存成功后才发布成功并修复空模型 Agent Profile。

聚焦 JVM `8/8`，完整门禁为 JVM `645/645`、Lint `0 error / 50 warnings` 和三类 APK；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.373s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：候选记忆协调迁出（完成）

候选记忆的有界列表、普通聊天/Agent Run 成功回合来源身份、采集和接受/拒绝已从 `XiaoLingViewModel` 迁入独立 `AgentMemoryCandidateCoordinator`。协调器以 typed outcome 区分无候选、缺失、锁忙和存储失败；同一候选 ID 的接受/拒绝不能并发，不同候选可并行，失败和取消都会释放 claim。关闭候选开关同时取消旧列表 Job，防止迟到 Room 结果重新填充界面。敏感过滤、去重、冲突和 transaction 继续由既有 Room Store/Manager 管理，成功回合入口与默认关闭语义不变。

聚焦 JVM `7/7`，强制完整门禁为 `140/140` tasks、JVM `637/637`、Lint `0 error / 50 warnings / 1 hint` 和三类 APK；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.633s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：恢复后 Agent 审批协调迁出（完成）

进程重建后重新展示的链尾审批已从 `XiaoLingViewModel` 迁入独立 `RecoveredAgentApprovalCoordinator`。批准和拒绝都会重新读取最新 Room detail，并复用 `AgentRunResumePolicy` 核验唯一 `PENDING` Approval、链尾 ToolCall、参数和已验证前缀；进程内一次性互斥阻止批准/拒绝交叉或重复进入，锁忙时以 `Busy` 保留另一会话的可重试卡片。批准前先恢复原 USER 附件，前置失败或决定落库前取消时恢复可重试卡片；拒绝由 Repository 单事务收敛 Approval、审批 Step 和原 Run，避免 `WAITING_APPROVAL + DENIED` 半状态。

协调器不拥有 Provider/Profile 选择、Compose、消息、Workflow 后续步骤或普通当前进程 waiter；`AgentApprovalDecisionCoordinator` 继续只管理当前进程 ticket/claim，Room v32 继续是共同事实源。聚焦 JVM `6/6` 与新增 Room 原子拒绝 instrumentation 契约已落地；强制本地 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 通过，仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。本轮不采集 Shadow 样本，不改变第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：Agent 审批决策协调迁出（完成）

`XiaoLingViewModel` 原先直接持有的全局 `pendingApprovalDecision` 已迁入纯内存 `AgentApprovalDecisionCoordinator`。每次审批使用独立 ticket，匹配当前 `requestId` 的首次按钮操作才能领取 claim；重复点击、过期 UI 和旧 claim 不再并发写 Room。Room 成功后才完成 waiter，异常会释放 claim 并恢复可重试审批卡片，Repository 返回 `null` 时取消 waiter；停止生成同时取消当前 ticket，使迟到持久化结果无法继续放行工具。旧 ticket 的完成、释放和清理均通过身份比较隔离，不会误伤后来注册的新审批。

协调器不写 Room、不判断工具风险、不维护 Run/Workflow、不投影 Compose，也不处理进程恢复后的审批；这些职责继续留在 Repository、Runtime 和 ViewModel 原边界。五轮 TDD 聚焦 `5/5`，完整 JVM `624/624`、Lint `0 error / 50 warnings / 1 hint`，Debug/Release/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)`、耗时 `48.776s` 通过；7 份长期文档语料为 `OK (1 test)`。本轮不产生 Shadow 样本，不扩大设备工具、Workflow/后台、精确定时或 Foreground Service 能力，第 101 项继续低频观察，第 102 项保持后置。

## 横向工程：会话级 Agent 运行态 Store 迁出（完成）

`XiaoLingViewModel` 原先维护的 Run/Approval 两张会话 Map 已迁入纯内存 `AgentConversationRuntimeStateStore`。Store 统一同会话替换、审批 `deciding` 更新、只清审批、删除会话整组清理、新建占位不恢复和启动明细重建后的目标会话投影；ViewModel 继续负责 Compose 状态、Room 审批决策、Run history 与真正 Agent 执行。该切片把运行态生命周期从 `4408` 行 ViewModel 中抽离后降至 `4404` 行，没有改变 Room v32、Provider、Runtime、工具审批/验证、Workflow 或设备后台门禁。

五轮 TDD 聚焦 `5/5`，完整 JVM `619/619`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)`、耗时 `50.018s` 通过。真机收尾保持 Provider/Profile 各 `1`、知识文档 `0`、默认 Profile `16` 个工具/`7` 个 Skill/记忆开启、Shadow 关闭。该横向工程不产生新 Shadow 样本，第 101 项继续低频观察，第 102 项仍后置。

更新后的 7 份长期文档已重新打包，并通过 Redmi 项目文档语料门禁 `OK (1 test)`。

## 第 101 项：answerability Shadow 持续观察（首个窗口完成，继续观察）

首个间隔真实使用窗口只在 Redmi 前台直接 `/agent` 中显式开启 Shadow，并只采集 `1` 条样本。当前 README 的 `Agent Run retryOfRunId` 词法查询命中 `3` 个候选，真实 Run 完成 `knowledge.search`，Judge 判定为直接回答；样本/完成/Judge 为 `1/1/1`，取消、异常、未知、跳过和旁路错误均为 `0`，成本为 `5009ms / 5002ms / 10150B / 2720+209=2929 Tokens`。

关闭开关并删除测试会话后，notice 有效 `1 -> 0`、裁剪 `0 -> 1`；临时知识文档与下载文件已删除，知识文档恢复 `0`。第 97 至 101 项已记录窗口人工合计为样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`；八次 Judge 均无自然网络、协议或认证失败。该合计不代表跨进程持久化，且本窗口使用词法兜底，不能作为 Embedding 质量证据。

当前没有明显成本异常或新的自然失败证据，不增加 Room Store、Schema、跨进程 notice 或 enforcement。第 101 项仍保持持续低频观察，不在同一窗口堆样本，也不提前进入第 102 项。同步文档后的强制门禁为 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK、仅 Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (195 tests)`。

## 横向工程：Agent Run 关联重试协调迁出（完成）

失败 Run 的关联重试已从 `XiaoLingViewModel` 迁入独立 `AgentRunRetryCoordinator`。协调器统一资格判断、副作用证据确认、确认时 canonical fingerprint 漂移复核、用户取消、原 USER 附件异步恢复和关联新 Run 请求；只输出 typed event 与包含原会话、目标、附件、`retryOfRunId` 的不可变启动数据，不写旧 Run，也不持有 Agent Runtime。ViewModel 继续负责 Compose 投影、原会话与 Agent Profile/Provider 校验、会话导航和真正调用 `sendAgentRun`。

新增聚焦 JVM `7/7`，完整 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)` 已通过。本轮不采集 Shadow 样本，不改变第 101 项“间隔真实使用窗口低频观察”，也不扩展设备工具、Workflow/后台、精确定时、Foreground Service 或远期能力。

## 第 100 阶段：Android 系统分享入口 v1（完成）

本阶段补齐个人 Agent 的移动端输入入口，而不是扩大执行权。Android 分享面板只允许单项纯文本或单张 PNG/JPEG/JPG/WEBP 图片进入新会话草稿；文本最多 20,000 字符，图片必须是单个 `content://` 并继续走现有 8 MB 与内容校验。`EXTRA_STREAM` 和 `ClipData` 重复同一 URI 时保持 Android 分享兼容，不同 URI 则按多图拒绝。分享内容永不自动发送，不触发普通聊天、`/agent`、工具、Workflow 或后台任务。

空闲编辑器可直接打开分享；已有本地草稿、附件或活动操作时显示“打开分享/忽略分享”，只有用户明确打开才替换。已有未决分享时保留第一个、明确忽略第二个并要求重试。冷启动在初始化后投影，热启动走 `onNewIntent`，Activity 重建不重复导入；来源只显示为不可信的外部分享，不使用 referrer 或外部 extra 归因与去重。

聚焦 JVM `7/7`、仅 Redmi `wsvwypiz7xwslvl7` 的定向 instrumentation `OK (4 tests)` 已通过；完整 JVM `607/607`、Lint `0 error`、APK、文档语料 `OK (1 test)` 和默认完整 `195` 条（`183 passed / 12 skipped`）通过。该入口保持为既有编辑器和附件校验的薄适配层，不建立第二套消息、附件、发送或 Agent Runtime。下一阶段仍不扩到 `ACTION_SEND_MULTIPLE`、任意文件、自动发送、分享后后台处理或跨应用自动执行；Shadow 继续低频旁路观察，其余远期能力继续按原顺序后置。

## 第 99 阶段：answerability shadow 首批低频观察（完成，持续旁路）

本阶段没有继续密集扩样本，而是在新的 Redmi 真实使用窗口内显式开启 Shadow，取得 `3` 条有效前台 Judge 样本：直接回答 `2`、部分回答 `1`。Judge 尝试 `3` 次、取消 `0`、异常 `0`；本批成本为耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。

首次宽英文问题连续四次未取得知识候选，Agent Run 因工具调用次数达到上限失败；因为没有成功答案和合格 Shadow 入口，tracker 保持 `0`，没有把它伪造成 Judge 失败。随后三条精确词法查询分别覆盖 Responses 文档限制、Workflow 范围/准点语义与普通聊天工具事实边界。当前窗口 Embedding Provider 不可用，候选通过词法兜底形成；本批只证明 answerability 旁路和词法候选链路，不证明 Embedding 质量。

关闭开关并删除四个测试会话后，notice 从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3`；临时知识文档与下载文件已删除，恢复知识文档 `0`、原会话 `1`。第 97 至 99 阶段记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次、直接回答 `4`、部分回答 `3`，仍没有自然网络、协议或认证 Judge 失败。该跨阶段合计来自书面证据，不是跨进程持久化。

本阶段继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。完整 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)` 通过。后续 Shadow 只在间隔开的真实使用窗口低频观察，不为凑数量连续采样；没有自然失败或明显成本异常前，不设计 Room Store、跨进程 notice 或生产拒绝。

## 第 98 阶段：answerability shadow Redmi 扩样本（完成）

本阶段在不修改生产实现的前提下，继续使用 Redmi 收集用户显式开启、同一进程、前台直接 `/agent` 的真实 Shadow 证据。累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次均无取消或异常；完成样本分布为直接回答 `2`、部分回答 `2`。累计成本为耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`。

新增三条有效 Judge 样本中，两条自然判为部分回答，一条判为直接回答；两条长词法 query 自然无候选并按跳过统计。另一次 Agent Run 自然 `BUDGET_EXHAUSTED`，由于没有进入 Shadow，不计作 Judge 失败、取消或用量。关闭开关并删除测试会话后，notice 从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`；测试知识文档恢复为 `0`，保留原会话 `1`。

当前已经观察到自然无候选和预算耗尽的入口隔离，但四次 Judge 本身全部成功，仍没有网络、协议或认证等自然 Judge 失败分布。完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 与默认完整 `OK (191 tests)` 通过。`store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 与 `productionEnforcementEnabled=false` 继续不变。下一阶段只继续低频积累真实成本和自然 Judge 失败证据，不增加 Room Store，也不开启 enforcement；设备工具进入 Workflow/后台自动化及其余远期能力继续后置。

## 第 97 阶段：answerability shadow 真实样本与进程内遥测（完成）

已在第 96 阶段默认关闭生产接线之上增加固定上限的进程内 tracker。它只累计样本终态、Judge attempt、延迟/TTFB、Prompt 字节、Tokens、usage attempt、失败分类和 notice 生命周期，不保存问题、答案、候选正文、引用、原始响应、消息 ID 或凭据；设置页明确说明重启清空。答案保存后、Judge 发出前再次检查开关，用户关闭后不再发送问题或候选正文；重试成功仍保留前序失败分布。

Redmi 真实前台 `/agent + knowledge.search` 已得到 `1` 条完成样本，Judge `1` 次，耗时 `8437ms`、TTFB `8428ms`、Prompt `8952B`、Tokens `2340/361/2701`，失败/取消/异常均为 `0`。答案先保存，UI 后置显示“本地知识包含直接回答”和 `知识引用 · 3`；删除测试会话后 notice 从有效 `1` 裁剪为 `0`。开启状态下普通聊天未增加样本；Workflow/后台仍在 caller 边界外。完整 JVM `600/600`、Lint `0 issue`、APK、Redmi 定向 `OK (1 test)` 和完整 `OK (191 tests)` 通过，验收后开关恢复关闭。

本阶段没有新增 Room Store/migration，没有开启 notice 跨进程恢复或 enforcement。下一阶段应先基于更多真实前台样本评审 Provider 成本与失败分布是否稳定，再决定是否值得设计最小化持久化；单条成功样本不足以开启生产拒绝。设备工具进入 Workflow/后台自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 96 阶段：answerability shadow 默认关闭的生产接线（完成）

本阶段完成生产 Judge adapter、冻结身份门禁、独立设置开关、答案保存后的异步 caller 和旁路 notice。Judge 固定使用第 92 阶段的 Responses 非流式协议、关闭 reasoning、`temperature=0 / topP=1 / maxTokens=220`，逐请求关闭全部 HTTP Debug 日志；实际 Provider identity 从当前配置的 ID、模型和 Base URL 指纹派生，必须与 `redmi-provider-compatibility / gpt-5.5 / configuration fingerprint / prompt version` 完全匹配，否则在请求前保持关闭。

前台直接 Agent 的答案先展示并进入正常会话保存，`AgentAnswerabilityShadowPublisher` 的 sibling Job 等待对应保存 Job 成功后才调用协调器。保存失败、旧保存被新快照取消、Workflow 来源或 Judge 失败只跳过 shadow，终败不发布 notice，也不进入 Agent 主失败分支。notice 只以进程内 `messageId` 映射注入知识引用区域，不改写消息、可信上下文或引用；会话删除或重载时裁剪悬空键。当前固定 `store=null / persistenceMode=NONE`，Room 仍为 v32，没有 migration。

完整 JVM `593/593`、Lint、Debug/AndroidTest APK、Redmi 默认完整 `OK (191 tests)`、显式生产 adapter `OK (1 test)` 和设置/偏好/notice `OK (3 tests)` 均通过。实际 Provider ID 下的 `12 + 12` calibration/validation 复验继续保持两类特征族通过，冻结阈值不回调。普通聊天、Workflow、后台 Worker、notice 持久化、Room shadow Store 与 enforcement 继续关闭。该阶段提出的真实前台样本后续已由第 97 阶段取得首条完成证据；最小化持久化仍需更多样本，且不得从旁路 notice 直接扩张为答案拒绝。

## 第 95 阶段：answerability shadow 真实测量协调（完成，生产未接入）

本阶段把第 94 阶段留下的“何时调用 Judge、失败如何收敛、是否持久化”冻结成纯 Kotlin 协调契约。离线 `KnowledgeAnswerabilityObservation` 继续保留人工 `label`，线上新增无标签 `KnowledgeAnswerabilityShadowMeasurement`；共享 `KnowledgeAnswerabilityAssessment` 和同一证据回查映射保证两条路径决策一致，但线上 measurement 不会被误计入 calibration/validation。

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 默认关闭且只接受前台直接 Agent。候选不完整或缺少冻结绑定时不调用 Judge；瞬时网络、限流、服务端和协议错误最多重试一次，认证、普通请求、身份、候选和未知错误不重试；取消原样传播。Judge identity 漂移保留 measurement 但 binding 未知。可选 Store 只保存指纹、幂等键、身份、尝试次数、状态、决策、失败分类和时间，Store 失败不会改变原答案、引用或 `enforcementApplied=false`。

新增协调器契约 `14/14`，完整 JVM `578/578`、Lint、Debug/AndroidTest APK 与仅 Redmi `OK (188 tests)` 通过。该阶段当时没有生产 Provider adapter、消息 caller 或答案引用 UI 接线；这些默认关闭的接线已由第 96 阶段完成。Room schema/store、普通聊天、Workflow、后台 Worker 和 enforcement 保持关闭。

## 第 94 阶段：真实消息流只读 answerability shadow 绑定（完成，生产未接入）

本阶段把第 93 阶段的展示 seam 与真实 Agent 消息中的可信知识检索证据连接起来，但仍保持只读。`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 从最近的成功 `knowledge.search` ToolResult 提取候选；失败、无引用、空正文、空 Run 和其他工具不能冒充候选，旧单工具消息继续兼容。`KnowledgeAnswerabilityShadowBindingPolicy` 绑定来源 Run、同一 Judge 的强类型 calibration/validation identity、观测和冻结 gate，只让第 92 阶段已通过的两类特征族进入消息 shadow。

覆盖率特征族、Judge identity 漂移、缺观测、缺冻结绑定、观测 Run 不匹配和候选证据不完整均保持 `UNKNOWN`；候选引用在绑定时冻结快照，无观测时不伪造观测时间，引用顺序/身份与原答案不变，结果固定 `enforcementApplied=false`。本阶段不调用 Provider、不写 Room、不接入 `KnowledgeReferencesContent`、不改变普通聊天或 Workflow，也不打开生产拒绝。聚焦 JVM `11/11`，完整 JVM `564/564`，Lint、Debug/AndroidTest APK 通过；仅 Redmi `wsvwypiz7xwslvl7` 的真实套件为 `OK (188 tests)`，没有使用 Pixel_9。

第 94 阶段当时留下的 Judge 生成、失败/重试和可选持久化问题已由第 95 阶段冻结；第 96 阶段已补齐默认关闭的 Provider/caller/UI 接线。Room schema/store、生产 enforcement、设备 Workflow/后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续按路线图后置。

## 第 93 阶段：答案可回答性 shadow 呈现（实现与 Redmi 验收完成，生产未接入）

本阶段先补齐“如果 Judge 以后产生结果，用户应该看到什么”的纯展示契约，但不提前接入生产答案链路。`KnowledgeAnswerabilityShadowPresentationPolicy` 将 `ACCEPT / REJECT / UNKNOWN` 映射为直接回答、部分回答、未回答、矛盾、证据无法回查、低于冻结门禁和未知提示；输入引用始终原样保留，固定 `enforcementApplied=false`。`KnowledgeReferencesContent` 只增加默认 `null` 的可选参数，现有生产调用行为不变。

离线门禁与真机 UI 验收均已完成：既有 answerability 策略 `7/7` 与新增 shadow 呈现 `5/5` 合计 `12/12`；`KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已随 Redmi 默认完整 `OK (188 tests)` 通过，确认提示与原引用共存。没有连接或启动 Pixel_9。

第 93 阶段当时留下的真实消息流只读绑定、Judge 生成时机、持久化和失败语义已由第 94、95 阶段冻结。第 92 阶段仍只有两类特征族通过，覆盖率特征族未通过；因此后续生产接线仍不得修改答案、移除引用、升级生产相关性身份或开启 `productionEnforcement`。

## 第 92 阶段：答案可回答性策略（实现与真实 Provider shadow 验收完成）

本阶段先把第 91 阶段暴露的“同主题不等于真正回答”问题收敛成可审计的独立策略，而不继续调检索分数。实现已完成：严格 JSON 协议、固定 verdict、候选原文 quote 匹配、矛盾/部分回答拒绝、`UNKNOWN` 保守决策，以及三类特征族的 calibration/validation 身份隔离。`KnowledgeAnswerabilityPolicyTest` 离线 `7/7` 通过；Lint、Debug APK 和 AndroidTest APK 构建成功。第 92 阶段当时生产检索、Room、答案引用 UI、普通聊天和 enforcement 尚未读取该策略；第 96 阶段仅增加默认关闭的前台直接 Agent 旁路，`productionEnforcementEnabled=false`。

真实验收已只在 Redmi `wsvwypiz7xwslvl7` 完成：以 `redmi-provider-compatibility / gpt-5.5` 运行显式 calibration/validation 探针，各取得 `12` 条观测，网络/解析失败均为 `0`，最新身份校正复验 `OK (1 test)`、耗时 `94.154s`。`VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE` 通过，覆盖率特征族未通过；重复采集不用于回调已冻结门禁。主 APK、Activity、Provider/Profile 和设备状态已恢复，没有连接或启动 Pixel_9。

第 92 阶段当时规划的 shadow 呈现、只读绑定和 Judge 协调已由第 93 至 95 阶段完成；未通过的覆盖率特征族仍被排除，Judge 失败继续收敛为未知。本轮证据不升级生产 `VERIFIED`、不进入相关性 final holdout，也不允许拒绝执行。设备工具仍不得进入 Workflow/后台自动化，精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 91 阶段：跨主题平移不变特征探针否决

已完成新的跨主题归一化设计、纯 Kotlin 契约和 Redmi 三轮真实证据。`KnowledgeRelevanceCrossTopicNormalizationPolicy` 只使用已有审计字段构造 `top1 - 候选均值` 与 `margin / 候选标准差`，比较两个单特征和组合共三族；候选标准差接近零或输入非有限时直接拒绝。正式身份、配置指纹和互异 calibration/validation 版本继续强绑定，validation 不参与选阈值，生产 Room、Store、答案链路与 enforcement 不变。

`stage91-cross-topic-calibration-v1 / validation-v1` 各 12 篇新主题文档、三桶各 4 条查询并重复 2 次，共 `24 + 24` 条观测；Recall@5 均为 `1.0`，三轮 Redmi 均 `OK (1 test)` 且通过族为 `0`。`top1-均值` 与组合在 validation 上正例接纳 `1.0`、近负例拒绝 `0.75`、远负例拒绝 `1.0`、稳定率 `1.0`；`margin/标准差` 更弱，仅为 `0.75 / 0.25 / 1.0 / 1.0`。因此平移不变性虽然解决了本轮正例保留，却没有解决“同主题但语料未回答”的近负例误接纳。

本阶段明确否决继续围绕同一检索分数微调。下一阶段若继续相关性路线，应先设计 answerability/重排证据，让系统判断候选文档是否真正包含问题答案，再重新注册 calibration/validation；新的独立证据通过前，不进入 final holdout、不升级 `VERIFIED`、不接入生产答案路径。完整本地门禁为 JVM `541/541`、Lint、Debug/AndroidTest APK；Redmi 默认全量 JUnit XML 已完成，为 `186` 条（`176 passed / 10 skipped / 0 failed`）。

## 第 90 阶段：正式相关性 calibration/validation 预注册门禁否决

已完成正式 Provider 身份下的独立 calibration/validation 证据采集，但结论是质量门禁否决，不是生产通过。`KnowledgeRelevanceProductionCalibrationPolicy` 绑定 Provider、模型、配置指纹和互异 `datasetVersion`，只从 calibration 冻结七类特征族阈值，再在 validation 原样评估。Redmi `wsvwypiz7xwslvl7` 使用 `redmi-production-embedding-v1 / Qwen/Qwen3-Embedding-0.6B`，配置指纹 `2f22bfe3b9db92555f493c173116c58970490ece7fa90b8c7bf156aa7456dbf6`，两套数据各 24 条观测、Recall@5 均为 `1.0`；最新 raw top1 validation 正例接纳 `0.75`，近/远负例拒绝 `1.0`，七类特征族通过数 `0`，重复运行的正例接纳在 `0.625–0.75` 之间，说明跨主题绝对分数漂移仍未解决。

显式真实校准测试已改为“预期门禁否决即测试成功”，Redmi `OK (1 test)`；没有降低标准、没有用 validation 回调阈值、没有升级 `VERIFIED`、没有进入 final holdout，`productionEnforcementEnabled=false`。新增模型漂移 JVM 回归；完整本地 JVM `535/535`，默认 Redmi instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`）。

下一阶段不应把正确排序误读为相关性门禁通过，也不应继续围绕同一绝对阈值调参；应先决定是否建立新的跨主题归一化特征/标注设计并重新注册数据，或保持生产拒绝关闭继续积累证据。答案级知识引用、生产 Store、`knowledge.search`、普通聊天和 Workflow 继续不读取控制面。

## 第 89 阶段：生产身份绑定与相关性灰度控制面

已完成身份状态机、持久化控制面和 Redmi 真实候选探针，但仍未启用生产拒绝。正式身份区分 `UNBOUND / CANDIDATE / VERIFIED / REVOKED`；真实 `/models + /embeddings` 只能证明端点、模型与向量协议可用，因此当前只绑定 `CANDIDATE`。Base URL 仅保存 SHA-256 配置指纹，不保存原始端点或密钥。升级为 `VERIFIED` 必须同时匹配冻结 gate、Provider、模型、配置指纹、独立数据集身份和明确通过的 final holdout 证据。

灰度控制面现在把“用户请求开启”与“生产身份已验证”分开：只有 `VERIFIED` 身份、gate、Provider、模型、证据版本和配置指纹全部一致时才可能解析为 `ENFORCE`，任何漂移都回到 `SHADOW`。设置页只展示身份、证据、当前 `SHADOW` 和撤销入口；撤销清除执行资格并把绑定标为 `REVOKED`，没有直接绑定、升级或绕过证据开启生产拒绝的入口。完整 JVM `532/532`、Lint、Debug/AndroidTest APK 和仅 Redmi 默认 instrumentation `184` 条（`176 passed / 8 skipped / 0 failed`）通过；真实身份探针 `1/1` 得到 `2 × 1024` 有限向量并保持 `CANDIDATE`。

第 90 阶段已在同一正式 Provider、模型和配置指纹下完成版本化 calibration/validation，但七类特征族全部被预注册标准否决。不能复用 validation 调参、降低正例标准或直接进入 final holdout；只有新的跨主题归一化/数据设计在重新注册后通过，才评审把 control-plane resolution、生产 decision 和 UX presentation 接入答案级知识路径。完成前 `RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台行为保持不变。

## 第 88 阶段：相关性降级、引用一致性与身份灰度契约

已完成契约实现、审查修复和 Redmi 完整门禁，但仍未启用生产拒绝。检索 hit 现在能区分 `LEXICAL / SEMANTIC` 来源；未来低分 enforcement 只能删除 semantic-only，词法和语义重叠命中继续保留，引用始终从最终候选生成。用户侧固定三种解释：“已降级为关键词匹配”“未找到足够可靠的本地知识”“相关性检查暂未应用”；来源未知、决策矛盾或 shadow 删除指令全部 fail-open。零引用时 UI 也能独立显示解释，但生产消息流尚未传入该提示。

灰度偏好默认关闭，并绑定 gate 版本、Provider 与模型；缺项、版本漂移或身份漂移自动回到 shadow，撤销清除四项资格。双轴审查进一步补齐了 calibration/validation datasetVersion 完整且不同的冻结身份约束。Stage 87+88 聚焦 JVM `16/16`、完整 JVM `522/522`、Lint、APK 和仅 Redmi instrumentation `180` 条（`173 passed / 7 skipped / 0 failed`）通过。首次完整套件的 20 个 Compose 失败由 Redmi dream/keyguard 引起，唤醒后的失败批次与保持唤醒的完整复验均全绿。

第 90 阶段已在正式身份下完成 calibration/validation，但七类特征族均未通过预注册标准；不能把该结果升级为生产身份或 final holdout。默认仍关闭，必须先重新注册跨主题归一化/数据设计并获得独立通过证据，才评审把 rollout resolution、生产 decision 和 UX presentation 串入答案级知识路径。完成这些前，`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台行为保持不变。

## 第 87 阶段：生产相关性拒绝设计评审

已完成纯策略设计与真实项目回归，但尚未把拒绝接入生产检索。`KnowledgeRelevanceProductionDesignPolicy` 复用 Stage 86 冻结 gate，要求 Provider/模型身份一致；高于 raw top1 下限的语义结果保持，低于下限只计划移除语义候选并保留词法兜底。开关默认关闭，关闭时只产生 shadow 判断；非语义状态、身份漂移、缺失或非有限分数全部 fail-open。策略没有被 `RoomKnowledgeDocumentStore.search()` 调用，不改变 Room v32、UI、检索排序或历史审计。

聚焦 JVM `5/5`、完整 JVM `511/511`、Lint、Debug/AndroidTest APK 和仅 Redmi 默认 instrumentation `178` 条（`171 passed / 7 skipped / 0 failed`）通过。正式应用已恢复 Room v32、兜底 Provider、设备 Agent 开关和 Accessibility 服务。下一阶段再完成用户可见“语义候选被降级/词法兜底”的文案与引用行为、灰度/回滚开关和真实接入前的评审，不直接开启生产拒绝。

## 第 86 阶段：冻结 raw top1 的第三套 final holdout

已完成实现与最终 Redmi 复验。新增 `KnowledgeRelevanceRawTopScoreFrozenGate` 与 `KnowledgeRelevanceFinalHoldoutPolicy`，冻结 `stage85-raw-top1-qwen-v1`、Stage 85 calibration/validation 完整身份和 raw top1 `0.6416276358587735`。策略只应用冻结 raw top1，强制第三套数据身份、Provider/模型一致、三桶与有限值完整，失败后不搜索新阈值。

`stage86-final-holdout-v1` 在运行前固定 20 篇全新成对主题文档，三桶各 10 条英文查询、每条重复 2 次；同时预注册正例/近负例/远负例/决策稳定和 Recall@1/5、MRR、排序稳定门禁。预注册后的首次有效 Redmi 运行耗时 `63.077s`；补齐 validation 身份校验并同步重建 Debug/Test APK 后，最终复验耗时 `67.018s`，60 条观测得到正例接纳 `0.90`、近/远负例拒绝 `1.0`、决策稳定 `1.0`、balanced accuracy `0.9667`，Recall@1/5、MRR、排序稳定均为 `1.0`，通过。中间 ABI 不一致和一次检索空分数回归未计入门禁。下一阶段进入生产相关性拒绝设计评审，但本阶段不直接改生产 Room v32、检索、UI 或 Provider 配置；生产拒绝继续关闭直至设计、回退与用户可见行为另行验收。

## 第 85 阶段：Embedding 特征族独立 calibration/validation

已完成实现与 Redmi 验证。新增 `KnowledgeRelevanceFeatureComparisonPolicy`，预注册比较 raw top1、margin、top1 z-score 及四种组合。每个特征族只从 calibration 观测点选择 gate；validation 原样应用冻结 gate。策略强制 Provider/模型一致、数据集版本不同、三桶完整、数值有限和 case 标签稳定。生产 Room v32、cosine+RRF、FTS4+LIKE 与无拒绝边界保持不变。

全新 `stage85-calibration-v1` 与 `stage85-validation-v1` 分别使用独立内存 Room、20 篇成对主题文档、三桶各 10 条查询和每条 2 次重复，共 `60 + 60` 条有效观测；Recall@5 均为 `1.0`。raw top1 gate `0.6416276358587735` 与 raw+margin gate `0.6416276358587735 + 0.021738810541493292` 的 validation 指标完全相同：正例接纳 `0.90`、近/远负例拒绝 `1.0`、稳定率 `1.0`、balanced accuracy `0.9667`。单 margin、单 z 和含 z 的组合没有更优；首轮把 companion 可直接回答的问题误标为近负例的数据草案已废弃，不进入证据或阈值。

下一阶段按简约原则冻结 raw top1 候选及其 Provider/模型/calibration 身份，用第三套全新 final holdout 预注册验证；若再次出现正例接纳不足，候选必须被否决，不能回调。本阶段完整门禁为 JVM `502/502`、Lint、Debug/AndroidTest APK 和仅 Redmi `177/177` instrumentation；6 个显式联网用例默认 skipped。生产相关性拒绝继续关闭。

## 第 84 阶段：Embedding 查询内相对分布 shadow 观测

已完成实现与 Redmi 验证。新增纯 Kotlin `KnowledgeRelevanceRelativeDiagnosticsPolicy`，从同次查询当前有界语义索引候选池的全部有效 cosine 候选计算均值、总体标准差和 top1 z-score；整体分数平移不改变 z-score，单候选或零方差保持未知。生产 Store 在 top-K 截断前计算这些值，Room v32 持久化三个可空字段，v31 历史记录保持 `null`；知识管理页继续以“校准观测”展示，不改变召回或拒绝行为。

Redmi v31→v32 迁移、Room 写入回读与 UI 聚焦 `3/3` 通过，真实 Provider 小型语义链 `1/1` 通过。对已退休 Stage 83 holdout 的一次 shadow 观测只验证新字段：正例 z-score `2.929–3.722`、近负例 `2.226–3.232`、远负例 `1.579–2.879`。正例与近负例区间重叠，说明查询内标准化缓解绝对分数平移，但 z-score 本身仍不足以形成生产门禁；本轮没有搜索阈值，也没有把 Stage 83 holdout 变成校准数据。

完整门禁为 JVM `499/499`、Lint、Debug/AndroidTest APK 和仅 Redmi `176/176` instrumentation 通过；5 个显式联网用例默认 skipped。Room v32、cosine+RRF、FTS4+LIKE 与无生产拒绝边界保持不变。下一阶段建立全新版本的 calibration/validation 数据，预注册比较 raw top1、margin、z-score 及组合候选；Stage 83 holdout 继续封存，只有新的独立证据通过后才讨论最终 holdout。

## 第 83 阶段：冻结候选门禁的独立 holdout 验证

已完成实现与 Redmi 三轮独立验证，结论是第 82 阶段候选被否决。新增 `KnowledgeRelevanceDatasetIdentity`、`KnowledgeRelevanceFrozenGate`、`KnowledgeRelevanceHoldoutCriteria`、`KnowledgeRelevanceHoldoutReport` 与 `KnowledgeRelevanceHoldoutPolicy`，强制 Provider/模型一致、校准集与 holdout 版本分离、三桶完整、数值有限和 case 标签稳定。holdout 只应用冻结 top1 `0.6735426515268672` 与 margin `0.0178535973263384`，没有候选搜索或生产配置写入。

全新 20 篇成对主题语料包含三桶各 10 条英文查询、每条重复 2 次。Redmi 三个独立进程均得到正例接纳率 `0.80`、近负例拒绝率 `1.0`、远负例拒绝率 `1.0`、决策稳定率 `1.0`；Recall@1、Recall@5、MRR 和排序稳定率也均为 `1.0`。手冲咖啡正例 top1 约 `0.6169`，缝纫机张力正例约 `0.6661`，两者都稳定低于冻结下限。索引耗时 `14.237–14.696s`，查询中位数 `0.757–0.800s`、P95 `0.814–0.836s`，20 行 1024 维向量共 `81,920` 字节。

这说明排序质量全对不等于拒绝门禁可用：冻结候选会稳定误拒 `20%` 正例，未达到预注册 `90%` 正例保留率。因此本阶段实施完成，但候选没有进入生产拒绝设计的资格；不得使用已见 holdout 回调当前阈值。Room v31、cosine+RRF、词法兜底和 shadow 审计保持不变。下一阶段应建立新版本的嵌套 calibration/validation/holdout 方案或可跨主题归一化的门禁特征，再用未见数据重新验证；在新的独立证据通过前，不实现生产相关性拒绝。完整离线门禁为 JVM `495/495`、Lint、Debug/AndroidTest APK 和仅 Redmi `175/175` instrumentation 通过；5 个显式联网用例默认 skipped，三轮显式 holdout 按预注册断言失败并作为否决证据保留。

## 第 82 阶段：Embedding 相关性校准扩样与 shadow 候选门禁

已完成实现与 Redmi 三轮真实校准。新增纯 Kotlin `KnowledgeRelevanceCalibrationPolicy`，对三个业务桶输出 top1/margin 的 P05/P50/P95，并从观测值组合中选出同集 balanced accuracy 最优的 `minimumTopScore + minimumScoreMargin` 候选。语料由 10 篇孤立主题扩为 20 篇成对主题，正例、近负例、远负例各 10 条、每条重复 2 次；三次独立 instrumentation 共 180 个观测，Recall@1、Recall@5、MRR、重复排序稳定率和同集 shadow balanced accuracy 三轮均为 `1.0`。20 行 1024 维 Float32 向量共 `81,920` 字节，索引耗时 `14.712–15.332s`，查询中位数 `0.797–0.807s`、P95 `0.824–0.866s`；检索后 PSS 为 `202,124–202,359 KB`。

三轮候选 top1 下限为 `0.6735–0.6741`，margin 下限为 `0.0179–0.0184`；正例 top1 P05 `0.6735–0.6741`，近负例 top1 P95 `0.6063–0.6073`，当前固定语料存在稳定间隔。但这仍是同一小样本上的选择与回测，不代表未知问题、其他语料或其他 Provider/模型。第 83 阶段应先冻结 Provider/模型专属候选与版本化校准身份，再加入完全不参与调参的 holdout 正例/近负例/远负例；只有 holdout 的正例保留率、两类负例拒绝率、排序质量和失败回退同时满足预先定义的门禁，才进入生产拒绝设计。最终 JVM `491/491`、Lint、Debug/AndroidTest APK 和仅 Redmi `174/174` instrumentation 已通过；ANN、后台批量重建、设备 Workflow/后台自动化及后续生态能力继续后置。

## 第 81 阶段：Embedding 相关性 shadow 诊断与首轮校准

已完成实现和 Redmi 验收。Room v31 为检索审计保存可空的 top1、top2、margin 和有效候选数，v30 历史记录保持未知；知识管理页显示“校准观测”，但不改变生产召回。真实 Provider 在第 80 阶段同一 10 篇语料上运行正例、近负例、远负例各 2 条，三类 top1 区间为 `0.6806–0.7130 / 0.6502–0.6854 / 0.3704–0.4083`，margin 区间为 `0.2743–0.2828 / 0.1311–0.2114 / 0.0274–0.0507`；全部查询均有 10 个有效候选且词法零命中，真实校准 `1/1` 通过。该结果说明分数与 margin 的组合值得继续验证，但每桶 2 条不能支撑跨 Provider/模型阈值。第 82 阶段应先扩大标注查询与干扰文档、重复采集各分桶，再决定只作用于纯语义候选的“分桶绝对下限 + margin + 词法兜底”；当前不启用生产拒绝。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 和仅 Redmi `174/174` instrumentation 通过。ANN、后台批量重建、设备 Workflow/后台自动化及后续生态能力继续后置。

## 第 80 阶段：真实 Embedding 有界语料基线

已完成实现和 Redmi 三轮验收。新增的显式联网 instrumentation 在内存 Room 导入 10 篇中文单主题文档，用 5 个词法零命中的英文问题各重复检索两次。三轮 Recall@5、MRR 和重复排序稳定率均为 `1.0`；10 行 1024 维 Float32 向量共 `40,960` 字节，SQLite 内存页总量 `593,920` 字节。索引耗时为 `7.935–10.039s`，中位数 `8.881s`；每轮 10 次查询的中位数为 `0.811–1.100s`，P95 为 `1.016–1.496s`；检索后 PSS 较基线增加 `7,358–15,941 KB`。无关珊瑚产卵问题每轮仍返回 5 个近邻，证明当前 cosine+RRF 只做排名而不做相关性拒绝。这一边界比 ANN 或后台批量索引更紧迫，下一阶段应先使用固定正负语料建立可校准的相似度分布与拒绝策略，不得凭单一经验阈值破坏跨模型兼容。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `171` 个用例，`168` passed、`3` skipped、`0` failed。设备 Workflow/后台自动化和后续生态能力继续后置。

## 第 79 阶段：真实 Provider 语义检索端到端

已完成实现和 Redmi 验收。显式联网测试直接复用生产 Embedding 适配器与 Room Store，使用内存数据库隔离正式数据；真实 Provider 的模型同步与双输入向量协议 `1/1` 通过，完整语义链 `1/1` 在约 `5.947s` 内完成。英文问题在词法路径零命中，真实向量路径首位命中中文目标文档，检索审计为 `USED`，Provider/模型、chunk IDs、索引摘要和显式重建均可核对。默认完整套件缺少显式参数时继续跳过联网测试，因此 CI/本地门禁不依赖公网。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `170` 个用例，`168` passed、`2` skipped、`0` failed。下一阶段应扩展到更大但有界的真实语料，记录索引耗时、查询耗时、向量行数、Recall/MRR 与内存边界，再根据证据决定是否需要 ANN 或后台批量索引；设备 Workflow/后台自动化和后续生态能力继续后置。

## 第 78 阶段：Embedding 检索质量与兼容诊断

已完成并通过收尾门禁。新增独立质量评测，把文档级去重后的 Recall@5、MRR、负例准确率和重复排序稳定率变成可重复契约；5 份核心长期文档作为黄金语料，5 个正例与 1 个负例各运行两次，门禁满足 `1.0 / >=0.8 / 1.0 / 1.0`。知识管理页现在显示本次检索实际走过的语义融合、仅词法、无索引、Provider 不可用或维度不匹配路径，并在可用时标出 Provider/模型。该阶段使用的聊天兜底 Provider 没有 Embedding 模型，所以当时诚实停在词法兜底；第 79 阶段已使用独立真实 Embedding Provider 补齐协议与完整语义链。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个显式联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段：Embedding 索引生命周期

已完成并通过收尾门禁。知识管理页可查看当前 revision 下各 Provider/模型索引的维度和分块数，并可对启用文档显式重建当前选中 Provider/模型。升级前旧文档不再只能永久使用 `NO_INDEX`；Provider/模型切换后多个索引空间共存，重复重建某一空间不会覆盖其他空间。写入遵循“先请求和校验、后事务替换”，事务内再次核对 revision、enabled 和 chunk 身份；异常、超时、停用或并发替换保留已有索引及词法能力。正文替换继续清理所有旧 revision 空间，删除继续清理全部索引。Room 保持 v30。完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和仅 Redmi `164/164` instrumentation 通过。ANN、自动后台批量重建、设备 Workflow/后台自动化、精确定时、Foreground Service 及后续生态能力仍按既定顺序后置。

## 第 76 阶段：Embedding 检索 v1

已完成并通过收尾门禁。Provider 只有在已同步模型列表包含 Embedding 模型时才启用语义索引；`ProviderRequestConfig` 固定 Provider 身份和可选 Embedding 模型，`/embeddings` 请求沿用鉴权、User-Agent、自定义 Header 和 URL 规范化。Room 从 v29 升到 v30，新增按 `providerId + model` 隔离的 `knowledge_chunk_embeddings`，并为 `knowledge_retrievals` 保存 Provider、模型和稳定状态。向量采用 little-endian Float32 BLOB，导入/替换索引有 30 秒总时限，查询有 2 秒边界；异常、超时、无索引和维度漂移回退 FTS4+LIKE。语义、FTS、LIKE 使用稳定 RRF 融合，最终读取再次核对 enabled/revision，替换和删除不保留旧 revision 向量。新增 JVM、Room 迁移和知识存储 instrumentation；该阶段完整 JVM、Lint、Debug/AndroidTest APK 及仅 Redmi `158/158` instrumentation 通过。第 77 阶段已在此基础上补齐显式重建和多索引空间共存。

## 第 75 阶段：`/agent` 附件输入 v1

已完成并通过收尾门禁。前台直接 `/agent` 在 Responses 模式支持单条 USER Image 或 Document；同一 Run 的每轮规划请求保留附件，summary、VerifiedAgentContext、ToolResult、Tool part 和 Agent 输出不接收附件。Chat Completions、混合附件、持久化重复附件和非 USER 伪造来源在请求前 fail-closed。初次发送先提交 Room USER MessagePart 再建立 Run；审批恢复与任务中心重试从 Room 原消息重建，重试复制到新 USER 消息和新 Run，旧 Run 不变。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过；图片 Run `run-e2c23f3d-c7f9-41cc-9964-e0364741727e`、文档 Run `run-9e66e0eb-7684-4d92-8e5d-cfd3ec044d10` 的创建/回读验证均为 `PASSED`，无工具直接 `complete` 的 Run `run-9f4c1380-60de-4998-b689-65d570812431` 被 fail-closed。Workflow/后台 Agent 仍无附件入口；Embedding、设备后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 73 阶段：会话选择与删除副作用协调迁出 ViewModel

已完成并通过标准门禁。新增 `ConversationSelectionCoordinator`，只组合既有 Session Policy、Persistence Coordinator 与 Load Coordinator，统一新建、历史选择和删除切换的副作用顺序；删除失败由协调器在发布 Failed 前按捕获代次回滚，`ConversationLoadRequest` 不再了解持久化意图。ViewModel 只清理/读取 Agent Run 与审批 Map、投影 UI 并保存成功选择，从 4121 行降到 4087 行。聚焦 `4/4`、第 68 至 73 阶段组合 `30/30` 和完整 ViewModel Kotlin 2.3.20 手工编译通过；后续原生完整 JVM、Lint、APK、仅 Redmi instrumentation、正式包复装与 Room/crash 检查也已补齐，门禁基线为 JVM `472/472`、Redmi `153/153`。

## 第 72 阶段：会话新建与删除选择规则迁出 ViewModel

已完成。`ConversationSessionPolicy` 新增纯 `ConversationSelectionPlan.Immediate / Load`：当前空会话幂等复用且不改变其他空占位；已有内容时复用并折叠最新空占位；无空占位时按注入时钟创建稳定新会话；删除后选择最新剩余会话并进入加载，删至空列表则立即创建占位。`restoreRuntimeState` 明确区分复用与新建，防止新占位因时间戳 ID 碰撞恢复旧 Agent Run/审批。ViewModel 从 4178 行降到 4121 行，只保留取消、删除意图、运行态 Map、加载、回滚和保存。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `5/5`，完整 JVM `468/468`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 71 阶段：会话加载 UI 投影规则迁出 ViewModel

已完成。新增纯 Kotlin `ConversationLoadProjectionPolicy`，统一 Loading 清理旧结果、Loaded 原子选择会话与 Failed 错误收敛；非当前会话索引同时剥离 Image/Document BLOB，当前可见会话仍注入完整消息与附件。ViewModel 从 4200 行降到 4178 行，只保留删除意图回滚、Agent Run/审批映射读取、成功后的选择保存和其他副作用；`ConversationLoadCoordinator` 的 Job/代次边界不变。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`，完整 JVM `463/463`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 70 阶段：异步会话加载协调迁出 ViewModel

已完成。新增纯 Kotlin `ConversationLoadCoordinator`，统一 latest-load Job、单调选择代次和 Loading/Loaded/Failed 事件。取消是协作式的，因此旧 Room 查询或 loader 在取消后仍返回/失败时只会被代次门禁丢弃，不能覆盖当前会话、删除回滚或失败提示；新 Job 先登记再派发 Loading，因此回调重入选择也不会丢失最新 Job。ViewModel 继续负责会话选择、附件轻量化后的原子 Compose 投影、当前删除意图回滚和选择保存，不以总行数变化宣称 UI 编排已经迁出。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。四轮 TDD 后聚焦 JVM `4/4`，完整 JVM `460/460`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 69 阶段：会话保存协调迁出 ViewModel

已完成。新增纯 Kotlin `ConversationPersistenceCoordinator`，统一 latest-save Job、Room 单写者串行、发送前等待旧保存，以及显式删除 ID 的代次化确认与回滚。旧保存即使已进入不可取消提交区，最新快照也会等待并最后写入；事务失败、取消、同 ID 在提交期间重新标记或旧读取失败回调晚到时不清除新删除意图。`XiaoLingViewModel` 不再持有会话保存 Job 和待删除集合，从 4189 行降到 4183 行；异步会话加载、删除后的 UI 切换/失败回滚与 Compose 副作用仍保留。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。八轮 TDD 后聚焦 JVM `8/8`，完整 JVM `456/456`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 68 阶段：会话状态投影规则迁出 ViewModel

已完成。新增纯 Kotlin `ConversationSessionPolicy`，统一第一条 `role=user` 消息标题（正文空白时保持“新会话”）、重复空会话折叠、既有会话时间戳、摘要元数据默认继承、blank ID 生成，以及非当前会话迟到更新与当前 UI 的隔离。`XiaoLingViewModel` 删除 83 行对应私有实现，从 4272 行降到 4189 行；异步 Room 加载、保存 Job、删除事务与 Compose 副作用仍留在 ViewModel。Room v29、Provider 协议、UI、`/agent` 与 Workflow 行为不变。六轮 TDD 后聚焦 JVM `6/6`，完整 JVM `448/448`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 67 阶段：普通聊天网络发送编排迁出 ViewModel

已完成。新增纯 Kotlin `ConversationSendCoordinator`，用稳定事件统一发送前 Room 快照持久化、上下文准备、模型请求、流式增量和成功/取消/失败终态。`XiaoLingViewModel.sendMessage()` 从约 190 行收敛到约 104 行，只保留入口校验、用户输入投影、旧保存 Job 协调和发送 Job 生命周期；Compose 状态、30ms 流式节流与最终消息投影继续留在 ViewModel，因此不宣称总文件继续缩小。取消事件携带最近已准备上下文并在 UI 收敛后继续传播 `CancellationException`；持久化失败不调用 preparer 或模型。Room v29、协议、UI、`/agent` 和 Workflow 不变。新增聚焦 JVM `3/3`，完整 JVM `442/442`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 66 阶段：普通聊天请求上下文准备迁出 ViewModel

已完成。新增可独立测试的 `ConversationRequestContextPreparer`，统一负责消息上下文资格、知识引用生命周期、旧摘要失效/复用、最近 16 条窗口、增量摘要、窗口外最多 8 条可信 Agent 结果和 Responses 用户附件请求投影。`XiaoLingViewModel` 只注入知识 Store、摘要网络调用与当前提示词设置，从 4439 行降到 4224 行；知识核验和摘要生成的协程取消现在继续传播。Room v29、协议与 UI 不变。新增聚焦 JVM `8/8`，完整 JVM `439/439`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 65 阶段：进程退出观察只读诊断 UI

已完成。设置页可以只读查看 Room v29 最近 30 条进程退出记录，刷新只调用 `latest()`，不会再次采集或改变样本。页面以不同中文标签区分直接 LMK、LMK 候选、应用故障、系统资源限制、受控/维护和未归因退出，并展示稳定数值字段；固定声明不关联 Agent Run、工作流或任务，候选和受控退出不能作为自然 LMK。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；真实受控 `force-stop` 显示为 `USER_REQUESTED / 受控退出或包维护`，刷新前后账本均为 1 条。Room 仍为 v29，Foreground Service、旧执行栈恢复和设备后台自动化边界不变。

## 第 64 阶段：Android 进程退出观察账本

已完成。Android 11+ `ApplicationExitInfo` 由前台启动和生产 Worker 冷启动旁路采集；Worker 先登记当前进程所有权，再读取退出历史。Room v29 使用无 Task/Run 外键的独立表保存稳定数值字段，以稳定身份去重并裁剪到最新 30 条，不保存 description、trace 或进程状态摘要。分类只把 `REASON_LOW_MEMORY` 作为直接 LMK；设备不支持直接报告时的 `SIGNALED + SIGKILL` 仅是候选，应用/用户取消和包维护保持受控分类。普通采集异常不阻断主流程，协程取消继续传播。JVM `431/431`、Redmi 聚焦 `5/5`、完整 instrumentation `149/149` 通过；正式 schema 29 的受控 `force-stop` 被记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不构成自然 LMK，也不改变 Foreground Service 后置策略。

## 第 63 阶段：真实应用取消原因与用户停止优先级

已完成。Redmi Android 14 的真实 WorkManager 在运行中 Worker 被应用取消时返回 `CANCELLED_BY_APP(1)`，生产停止原因策略可稳定识别；用户停止路径则继续以先落库的 `STOP_REQUESTED` 和用户原因作为权威事实，后到的应用取消码不会覆盖或伪造独立系统停止原因。聚焦 `2/2`、完整 Redmi instrumentation `145/145`、JVM `424/424` 通过。本阶段不属于自然 LMK、配额或超时，不改变普通 WorkManager 与 Foreground Service 后置结论。

## 第 62 阶段：后台停止原因可观测性

已完成。`ScheduledWorkflowWorker` 在 Android 12+ 读取 WorkManager 停止码，使用隐私安全的稳定 `code + name` 分类，系统停止时把原因透传到 Workflow 终态并在同一事务写入 ScheduledTask/WorkflowRun；任务中心展示该分类。Room v27→v28 迁移不为旧记录发明停止原因，旧 Android、`NOT_STOPPED` 与未知码保持保守结论。JVM `424/424`、Redmi `143/143` 通过；没有自然系统停止证据，因此不引入 Foreground Service，也不扩大旧执行栈恢复能力。

## 结论

第 58 阶段早期真实后台长任务样本曾因 Redmi TLS 握手失败而在约 4 至 6 秒收敛；Task/Workflow/Agent 正常收敛且预算单调、没有复制 Run，设备 `curl` 独立复现同一失败。网络恢复后的成功复验见下一段。

网络恢复后已补齐成功样本：同一 Redmi 生产 Worker 的 8 步 SAFE Workflow 用时 `92.667s`，8 个 Agent Run、8 个步骤和全部工具验证均成功，预算快照单调且只有一个 Workflow Run；历史退出仍为 `lowMemoryExits=0`，没有 Android 自主 LMK。该耗时仍由普通 WorkManager 稳定承载，不提前引入 Foreground Service；设备工具继续不进入 Workflow/后台自动化。

第 59 阶段又完成更长真实后台样本：Redmi 正式 WorkManager 在 `229.416s` 内完成 8 步复合 SAFE Workflow；8 个 AgentRun 全部完成，每步依次调用 3 个只读工具，24/24 ToolResult 与验证事件通过，72 条预算更新、`llmFailureKinds=[]`。同轮 LMK 取证为 `supported=true / lowMemory=0`，14 条历史退出均可归因于 instrumentation 或安装停止。普通 WorkManager 已有约 229 秒成功证据，仍不引入 Foreground Service；本阶段没有新增自然回收或数值预算单调结论。

第 60 阶段把运行身份从“instrumentation 持续等待终态”收紧为真实后台冷启动：Probe 在 `0.255s` 后退出、原 PID 消失，JobScheduler 冷启动新 PID `25825`，同一持久化 WorkRequest/ScheduledTask/WorkflowRun 在 `204.977s` 内完成 8 步与 32 次只读工具调用。8 个 Run 的预算快照均无回退，32/32 工具回执和验证通过，`lowMemory=0`。这增加了普通 WorkManager 的后台可信度，但没有自然 LMK、后台中断恢复或 Foreground Service 必要性证据。

第 61 阶段在 Redmi 熄屏状态继续验证：Probe 退出后原 PID 消失，JobScheduler 延迟 `159.479s` 冷启动 PID `26797`，屏幕持续 `Asleep` 期间同一 WorkRequest/ScheduledTask/WorkflowRun 完成 `244.236s` 的 8 步、32 次只读工具调用。8 个 Run 的预算快照无回退，最大约 `44.856s`，32/32 工具回执和验证通过，`lowMemory=0`。这是当前最接近真实用户离开应用场景的成功样本，仍不等同自然 LMK 或 Foreground Service 需求。

小灵 `v0.1.11` 已具备可执行应用内任务的最小个人 Agent：普通聊天与 `/agent` 分流，Runtime 可取消、可限步、可确认、可验证并记录 Run、Step、Approval、Event 和 Memory；Agent Profile v1 已分离身份与能力，Room v32 已让 Text/Reasoning/Image/Document/Tool、知识引用、Embedding 检索/显式索引生命周期/相关性 shadow 观测、后台停止原因和独立进程退出观察持久化。长期记忆、声明式 Skill、1 至 8 步 Workflow、WorkManager 非精确定时、本地知识库、`knowledge.search`、答案级引用 UI，以及设备 Agent 观察与有限动作层均已交付。`device.snapshot / open_app / back / home / tap_ref / type_text / swipe` 具备独立默认关闭开关、Accessibility 四态健康检查、200 节点/4000 字符有界快照、30 秒 ref、页面 generation/路径/指纹失效、应用白名单、敏感输入拒绝、风险审批和动作后重新观察验证，仅开放给前台直接 `/agent`。首批只对小灵、系统计算器、时钟、设置和桌面完成 Redmi 验收，不承诺任意 App。网络请求设置现采用独立页面，User-Agent 输入区默认至少 5 行并提供复制、清空和恢复默认。多步骤 Run 已支持在第二次及后续工具审批处重建已验证前缀并继续原 Run；所有 ToolResult 与 `PASSED` 验证均已持久化时，也可不重放工具、不调用模型地完成原 Run 控制面收尾。不能原地恢复的 Run 现会把稳定处置码、策略原因、证据边界和建议动作冻结到 `run.recovered` 并在任务中心直接展示；旧验证事件缺少 ToolCall ID 时不再按工具名或顺序猜配，固定判为关联未知。Run 进入终态后，Step、Approval、Event 和 Tool Ledger 也同步冻结，迟到执行不能污染 `CANCELLED`。启动恢复先冻结旧候选，并排除当前进程真正 `RUNNING` 的 Worker 链；后台停止则先写入持久化 `STOP_REQUESTED` 栅栏，所以系统取消、即时 fallback、迟到 Worker 与进程重建都不能丢失或覆盖用户意图。即使 Agent Run 尚未关联，Worker 重入也优先读取该栅栏，把 Workflow、未完成步骤和 Task 收敛为取消。Workflow/Task 在同一事务原子结算，周期下一实例只在旧任务终态后物化。模型与工具段使用单调时钟共享累计执行预算。第 59 阶段已取得约 229.416 秒复合 SAFE 后台成功样本；Room v32 继续只把系统退出事实保存在独立账本，不凭时间邻近关联旧 Run；第 65 阶段已提供不触发采集的只读诊断 UI；第 66 至 73 阶段把普通聊天上下文准备、网络发送编排、会话状态/选择投影、保存、加载协调、加载 UI 投影和选择/删除副作用顺序迁出 ViewModel，最新横向工程又迁出 Agent Run 关联重试、会话级 Run/Approval Store、当前进程审批 waiter、恢复后审批、候选记忆与 Provider 模型同步协调。第 74 阶段完成网络请求独立设置页，第 75 阶段完成 Responses 附件输入，第 76 至 96 阶段完成 Embedding 检索/索引/质量证据、answerability 策略与默认关闭生产旁路，第 97 至 99 阶段形成有界真实 Shadow 样本，第 100 阶段完成 Android 单项系统分享草稿入口。当前本地标准门禁为完整 JVM `645/645`、Lint `0 error / 50 warnings` 和 Debug/Release/AndroidTest APK；本轮仅 Redmi 默认完整为 `OK (196 tests)`、耗时 `49.373s`，最终文档语料为 `OK (1 test)`。相关性生产拒绝与 answerability enforcement 继续关闭；Shadow 只在间隔开的真实使用窗口低频观察，设备 Workflow/后台自动化、精确定时与 Foreground Service 仍未交付。

第 43 阶段的同一 WorkRequest Redmi 冷启动重入已完成真实验收：旧 PID 在首步 Agent `THINKING` 时被受控强杀，新 PID 自动重入并按 Agent→Workflow→Task 收敛，没有创建第二个 Agent Run 或继续后续步骤。该样本使用 `run-as kill -9` fallback，不代表 Android 自主回收；该阶段当时的重点是更长/自然回收样本。第 46 阶段已进一步补充 Doze、受控内存和无压力对照，第 47 阶段解决了同一进程前台启动恢复与新 Worker 并发时的所有权隔离；当前仍缺自然 LMK。

参考项目中最值得学习的不是工具数量，而是以下工程原则：

- `meow-agent`：工具风险元数据、权限策略、后置验证、运行事件和 Workflow Ledger。
- `Operit`：Android 原生工具体系、MCP/Skill、记忆空间和多种移动端能力组合。
- `X-OmniClaw`：统一设备工具、界面观察后执行、按需工具路由、Markdown 记忆与索引、定时自动化。
- `openclaw`：Channel、Gateway、Session、Skill 和自动化边界，以及对长时间运行 Agent 的工程化拆分。
- `mobilerun`：面向移动 UI 的观察、动作和多步任务执行模型。
- `RikkaHub`、`PocketPal AI`、`OGAM`：Provider/模型能力、结构化消息、工具事件流、本地模型和 RAG 的产品化经验。

完整证据见 [参考项目分析](reference-apps-analysis.md)。

## 当前基础与主要缺口

### 已有基础

- OpenAI-compatible Provider 管理和模型同步。
- Chat Completions / Responses API。
- SSE 流式输出和 30ms UI 节流。
- 多会话、摘要压缩、Room 本地持久化。
- Provider、模型、接口模式、流式和耗时等消息元数据。
- Android Keystore 密钥保护和网络错误分类。
- 请求取消和停止生成。
- 多 Agent Profile 创建、编辑、选择和删除；每个 Profile 固定 Provider/模型、API 模式、系统提示词、上下文策略、工具/Skill 白名单和记忆开关。
- Text/Reasoning/Image/Document/Tool 消息 parts：历史/流式文本兼容、单附件用户图片/文档、供应商 summary 折叠展示、Tool 可信投影、前后台统一 Room 写入和同气泡证据展示。
- `AgentRun / AgentStep / ApprovalRequest / RunEvent / AgentMemory` 初始数据模型，以及 `/agent` 模型规划 + 应用内低风险工具链路。
- 最小 Agent Runtime 已具备工具调用预算、模型/工具步骤超时、整次 Run 超时、完整 Schema/业务规则/Android 权限校验、重复工具调用检测和结构化事件记录。
- 对话区已能显示当前 `/agent` Run 的最小时间线和审批卡片，批准后继续执行，拒绝后写入失败终态；交互审批当前不主动过期，审批请求已具备待确认状态和决定结果落库。
- 设置页已有 Agent 任务中心，可按全部/处理中/可重试/已完成筛选，查看完整工具结果、步骤、审批和结构化事件，并为失败、取消或预算耗尽任务创建关联的新 Run。
- 启动协调器已接入并通过真机进程重建验收：首个工具或任意已验证前缀之后的链尾待审批 Run、用户消息锚点和审批卡片可从 Room 重建；批准后 Runtime 从原审批步骤继续当前工具、验证和后续规划并写回同一 Run，前序工具不重放。

### 主要缺口

- 当前重试默认采用安全重新运行：旧 Run 保持不变，新 Run 关联 `retryOfRunId` 并重新走模型规划、工具审批和验证；`WAITING_APPROVAL` 原地恢复、两个白名单写工具的已提交结果只读验证，以及全部工具已验证后的本地收尾恢复已经接入。
- 旧模型协程、提交状态未知和验证事实不完整的通用工具执行栈仍不恢复。已交付例外都有完整持久化证据：待审批路径不重放已验证前缀，白名单写工具只读验证原 operation，全部 `PASSED` 路径只补控制面与本地总结。
- 第一批真实 Tool Registry 已统一声明 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、后台能力、超时和验证策略；生产权限检查器默认 fail-closed，Runtime 已按前台/后台来源执行能力门禁。
- 已有结构化长期记忆表、`memory.search / memory.remember`、FTS 检索、管理 UI、候选确认、敏感过滤、跨进程删除撤销、生命周期、时间衰减、引用审计、去重和冲突处理；更大数据量下的召回质量仍需持续验证。
- 已有 Room v31 知识文档、chunks、FTS4/LIKE/Embedding、带相关性 shadow 字段的检索审计、管理 UI、只读 Agent 工具、模型引用注入和答案引用呈现；第 82 阶段已完成扩样校准，生产拒绝、规模化 ANN 与更大语料泛化仍需验证。
- 已有内置与本地声明式 Skill 按需选取、严格导入校验、工具白名单和管理 UI；多步骤 Workflow 定义/编辑、前台与后台顺序执行、步骤快照、新 Run 重试、一次性和 Daily/Weekly 调度、通知和审批 blocked 状态已完成。
- AccessibilityService 观察与有限动作层已经交付，但设备工具仍没有 Workflow/后台执行、坐标/截图兜底或任意 App 通用能力。
- ViewModel 仍然过重；第 66 至 73 阶段已迁出普通聊天上下文准备、网络发送状态机、会话纯状态/选择投影、保存协调、加载协调、加载 UI 投影和会话选择/删除副作用顺序；最新横向工程又迁出 Agent Run 关联重试、会话级 Run/Approval Store、当前进程审批 waiter、恢复后审批协调、候选记忆和 Provider 模型同步编排。Compose 副作用、Workflow 等其他编排仍需继续拆分。

## 目标架构

```text
Compose UI
  |-- Chat
  |-- Agent Run Timeline / Approval Card
  |-- Memory / Skills / Tasks / Settings
  |
Application services
  |-- ConversationRequestContextPreparer
  |-- ConversationSendCoordinator
  |-- ConversationSessionPolicy
  |-- ConversationPersistenceCoordinator
  |-- ConversationLoadCoordinator
  |-- ConversationLoadProjectionPolicy
  |-- ConversationSelectionCoordinator
  |-- AgentRunRetryCoordinator
  |-- AgentConversationRuntimeStateStore
  |-- AgentApprovalDecisionCoordinator
  |-- RecoveredAgentApprovalCoordinator
  |-- AgentMemoryCandidateCoordinator
  |-- ProviderModelSyncCoordinator
  |-- AgentService
  |-- WorkflowService
  |
Agent Runtime
  |-- Intent Router: direct chat or agent run
  |-- Bounded Tool Loop
  |-- Tool Policy / Approval / Verification
  |-- Cancellation / Resume / Event Log
  |
Capability layer
  |-- ToolRegistry
  |-- SkillRegistry
  |-- MemoryRetriever
  |-- DeviceController
  |
Data layer
  |-- Room: conversations, runs, steps, memories, skills, tasks
  |-- Android Keystore: provider secrets
  `-- WorkManager / AlarmManager: scheduled execution
```

建议逐步拆出以下包：

```text
com.longdev.xiaoling.domain.agent
com.longdev.xiaoling.domain.tool
com.longdev.xiaoling.domain.memory
com.longdev.xiaoling.data.db
com.longdev.xiaoling.data.repository
com.longdev.xiaoling.llm
com.longdev.xiaoling.agent.runtime
com.longdev.xiaoling.agent.tools
com.longdev.xiaoling.agent.skills
com.longdev.xiaoling.automation
com.longdev.xiaoling.device
com.longdev.xiaoling.ui.agent
```

不必立刻拆成多个 Gradle Module，但代码依赖方向必须先固定，避免 UI、网络、存储和工具互相直接调用。

## 里程碑 0：稳定现有聊天底座（部分完成）

目标：在引入 Agent 前，让现有请求和数据结构具备扩展条件。

当前状态：请求取消、停止生成、Room 迁移、Schema 导出、v4→v32 迁移测试、Text/Reasoning/Image/Document/Tool 消息 parts、KnowledgeReference、独立进程退出观察、Repository、Responses API 结构化文本/附件历史、函数 typed Items、可选 Reasoning summary、`LlmProviderAdapter`、普通聊天上下文 Preparer、发送 Coordinator、会话状态/选择 Policy、保存 Coordinator、加载 Coordinator、加载投影 Policy、选择 Coordinator、Agent Run 重试 Coordinator、会话级 Agent 运行态 Store、当前进程审批决策 Coordinator、恢复后审批 Coordinator、候选记忆 Coordinator、Provider 模型同步 Coordinator 和面向用户的 Room ZIP 备份/恢复已完成；ViewModel 继续瘦身仍待完成。

### 要做什么

- 已完成：给当前请求增加明确的取消能力和“停止生成”按钮。
- 已完成：Responses API 改为结构化消息数组，保留 system/user/assistant 边界。
- 已完成：抽出 `LlmProviderAdapter`，由 `OpenAiCompatibleAdapter` 负责 URL、payload 和响应协议映射。
- 已完成：Responses 输入支持 `function_call / function_call_output` typed Items，并使用 `call_id` 关联调用和结果。
- 部分完成：`ProviderRepository`、`ConversationRepository`、`ConversationRequestContextPreparer`、`ConversationSendCoordinator`、`ConversationSessionPolicy`、`ConversationPersistenceCoordinator`、`ConversationLoadCoordinator`、`ConversationLoadProjectionPolicy`、`ConversationSelectionCoordinator`、`AgentRunRetryCoordinator`、`AgentConversationRuntimeStateStore`、`AgentApprovalDecisionCoordinator`、`RecoveredAgentApprovalCoordinator`、`AgentMemoryCandidateCoordinator` 与 `ProviderModelSyncCoordinator` 已落地；普通聊天上下文资格、知识生命周期、窗口、摘要准备、网络发送状态机、会话纯状态/选择投影、保存/加载/选择协调、Agent Run 重试资格/确认复核/附件准备、会话级 Run/Approval 生命周期、当前进程审批 waiter、恢复后审批、候选记忆与 Provider 模型同步编排已经迁出 ViewModel，Compose 副作用与其他 Agent/Workflow 编排仍需继续迁出。
- 已完成：引入 Room，并为现有 Provider、Conversation、Message 数据实现一次性迁移。
- 已完成：启用 Room Schema 导出，并为带旧数据的 v4→v32 migration 链、event metadata、Run 重试、Memory/Knowledge FTS、候选表、生命周期、Skill、Workflow、调度、多步骤快照、笔记幂等键、记忆 operation ledger/结果快照、独立工具账本、Agent Profile、MessagePart、知识引用和进程退出观察提供自动化测试。
- 已完成：增加面向用户的数据库 ZIP 备份与恢复能力；恢复前校验 schema，替换前保留 `.pre-restore`，并明确 Keystore 密文不可跨设备解密。
- 部分完成：普通聊天上下文准备、网络发送状态机、会话纯状态/选择投影、保存协调、加载协调、加载 UI 投影、删除失败回滚顺序、Agent Run 关联重试、会话级 Run/Approval Store、当前进程审批 waiter、恢复后审批、候选记忆与 Provider 模型同步协调已迁出；继续收敛 Compose 副作用与其他编排，使 ViewModel 更接近只负责 UI 状态投影和宿主副作用。

### 验收标准

- 原有 Provider 和会话升级后不丢失。
- 流式和非流式请求都能立即取消，不再追加内容。
- Chat Completions 与 Responses API 回归测试通过。
- 进程重建后可以正确恢复会话，但不会把未完成请求当成功。

## 里程碑 1：最小可用 Agent Runtime（最小闭环已交付）

目标：完成“判断是否需要工具 -> 调用工具 -> 获取结果 -> 继续推理 -> 输出最终答案”的受控闭环。

当前状态：`/agent` 最多 4 步的顺序工具闭环、单调累计执行预算、超时、取消、逐步审批、后置验证、多工具可信上下文、Run 时间线、RunEvent typed metadata、独立 ToolCall/ToolResult Room Ledger、可操作任务中心、安全重新运行和第一批应用内工具已完成；链尾待审批恢复可从任意已验证前缀继续，并恢复已消耗调用数、累计执行时间与循环指纹。所有成功 ToolResult 和 `PASSED` 验证已经落库时，原 Run 还可补齐最后验证 Step 并用本地可信总结收尾。其他执行/验证中断仍采用 Run/活动 Step 一致取消和关联新 Run 重试。独立账本承接 v20 新事件的原子双写，任务中心、三类恢复与失败 Run 重试副作用判断均已切换为 Ledger-first；账本异常时重试 fail-safe 要求确认，账本完全为空的旧 Run 保守回退 typed RunEvent。并行调用与提交状态未知、验证事实不完整的通用原地断点恢复继续关闭。

### 核心数据模型

- `AgentProfile`：已交付名称、标识、system prompt、Provider/模型、API 模式、上下文策略、工具/Skill 白名单和记忆开关；新 Run 冻结完整快照。
- `AgentRun`：目标、来源、状态、开始/结束时间、当前步骤、最终结果。
- `RunEvent`：状态变化、模型决策、工具调用、工具结果、确认、错误。
- `ToolDefinition`：名称、描述、输入 Schema、风险、权限、确认和验证规则。
- `ToolCall` / `ToolResult`：参数、结果、错误、耗时、重试和验证状态。v20 已独立落表并与 typed RunEvent 原子双写；任务中心、受限恢复和重试副作用判断对新 Run 优先读取账本，事件仅核对锚点与字段，旧 Run 在账本全空时回退事件。
- `ApprovalRequest`：待确认动作、风险说明、过期策略和用户决定。当前每个非 SAFE 工具步骤独立审批且不主动过期；只要所有前序工具均已成功验证、链尾 ToolCall 尚未执行且 Approval 与账本完全匹配，首步或后续审批都允许原 Run 恢复。

### 运行状态

第一版保持简单，不照搬多阶段重型规划器：

```text
idle -> deciding -> waiting_model -> waiting_approval
     -> executing_tool -> verifying -> waiting_model
     -> completed / failed / cancelled
```

### 必须实现的运行约束

- 普通问答走 direct chat fast path。
- 最大工具步数、单步超时和整次 Run 超时均由应用配置。当前最小 Runtime 已有初版配置。
- 连续重复同一工具和相同参数时触发循环检测；顺序多步循环复用同一 Run 级指纹集合和工具调用预算。
- 模型只能看到当前允许的少量工具，不在每轮注入全部 Tool Schema。
- 已完成：工具参数先做 JSON Schema、未知参数、业务规则和 Android 权限校验，再进入审批与 Executor。
- 风险和确认要求取自 `ToolDefinition`，忽略模型自己声明的风险级别。
- Run 可取消；取消后不再接受迟到的流式事件或工具结果。
- 所有状态变化写入 `RunEvent`，UI 显示简洁任务时间线。当前已在对话流里显示最小时间线，并在设置页提供可筛选、可重试的任务中心；ToolResult 完整正文、成功/验证状态和耗时均可查看。

### 第一批工具

先做可验证、低风险、应用内部工具：

- `app.current_time`
- `app.list_conversations`
- `app.search_conversations`
- `notes.list`
- `notes.search`
- `notes.create`，执行前确认，执行后重新读取验证
- `memory.search` / `memory.remember`，以及长期记忆管理 UI、FTS、启停/删除、来源审计、候选确认、敏感过滤、去重/冲突和当前会话删除撤销。

暂不做任意文件写入、Shell、应用安装、发送消息和系统设置修改。

### 验收标准

- 模型可通过工具完成“查找旧会话”和“创建一条笔记”。
- 用户能看到工具名称、关键参数、执行结果和验证状态。
- 拒绝确认后 Run 正确结束或改走其他方案。
- 工具返回成功但后置读取不一致时，结果标记为“未验证”，不得宣称完成。
- 状态机、循环检测、取消和确认都有确定性测试。

## 里程碑 2：长期记忆（候选治理闭环已完成）

目标：把“会话摘要”与“跨会话个人记忆”分开，让记忆可见、可控、可追溯。

当前状态：Room 结构、来源审计、`memory.search / memory.remember`、FTS4 + 中文兜底、管理 UI、默认关闭的候选生成、敏感阻断、去重/冲突、跨进程删除撤销、实际引用 ID 审计、单次召回关闭、可空过期策略和时间衰减排序已完成。

### 记忆类型

- `Preference`：稳定偏好，例如语言、常用格式和习惯。
- `ProfileFact`：用户明确提供的个人信息。
- `Episode`：重要任务和事件结果。
- `Procedure`：经过验证的重复操作方法。

### 实现顺序

1. 已完成：Room 保存结构化 Memory，包含来源会话/Run、原文摘要、类型、置信度、更新时间、启用和置顶状态。
2. 已完成：提供记忆管理页，支持搜索、查看/跳转来源、编辑、置顶、禁用和删除确认。
3. 已完成：成功轮次结束后只从明确陈述生成“候选记忆”，由确定性规则过滤；候选功能默认关闭，敏感内容只保存类别和固定提示。列表、来源身份、采集和接受/拒绝由独立协调器编排，同一候选 ID 的并发决定被拒绝，关闭开关会取消迟到列表读取。
4. 已完成：第一版使用 Room FTS4，并为中文和任意子串保留 `LIKE` 兜底；验证更大数据集召回质量后再考虑 Embedding 和向量索引。
5. 已完成：将检索结果以有限条目注入 Agent 工具结果，并在 `ToolResult`、任务中心和 `VerifiedAgentContext` 记录本轮实际使用的 memory ID；旧事件按空列表兼容。
6. 已完成：对话输入区的 `/agent` 单次「记忆」开关；关闭后从规划器工具清单移除 `memory.search`，执行层保留二次保护并写入关闭召回审计事件，发送后自动恢复默认开启。
7. 已完成：规范化去重和同主题冲突标记，不直接覆盖旧事实；过期字段默认为空，管理页可选择永久、30 天、90 天或 1 年，启用检索排除过期项，置顶项不参与时间衰减。

### 验收标准

- 用户可以回答“你为什么记住这件事”，并跳转到来源。
- 删除或禁用的记忆不再被检索。
- 同一事实不会无限重复写入。
- API Key、token、密码、银行卡、身份证和手机号命中后不保存原值。
- 删除后立即退出检索；最近一次删除在应用重启后仍可撤销，并恢复主表、来源字段、生命周期字段和 FTS。
- 记忆检索失败不影响普通聊天和工具执行。

## 里程碑 3：Skill 与能力按需加载

目标：把可复用任务知识从系统提示词中移出，并避免工具数量增长后 Prompt 膨胀。

当前状态：已交付会话检索、本机笔记、长期记忆、设备时间和本地知识库五类内置声明式 Skill，以及版本化本地 JSON 导入、严格静态校验、Room 持久化、启停和删除管理。规则按目标稳定选择最多 3 个已启用 Skill，工具白名单只能缩小已注册工具面并写入 Run 审计；顺序多步 Runtime 可以在多个已选 Skill 的工具并集中逐步执行。

### Skill 结构

每个 Skill 至少包含：

- `id`、名称、版本和说明。
- 触发描述和示例任务。
- 依赖的工具列表。
- 所需 Android 权限和风险等级。
- 执行步骤、失败恢复和完成标准。
- 来源、校验状态和是否启用。

### 实现策略

- 内置 Skill 当前由 Kotlin 稳定定义并在启动时同步到 Room；本地 Skill 使用 `schemaVersion=1` JSON，后续如需将内置定义迁为 assets 文件必须保持同一验证契约。
- 先通过轻量分类器或规则选择 1-3 个 Skill，再只加载其指令和工具。
- Skill 不能直接获得未注册工具，也不能降低工具风险或绕过确认。
- 已完成：第一版只允许导入本地声明式 JSON Skill，不执行任意代码；未知字段、未注册工具、风险或权限不一致均拒绝导入。
- “从成功任务生成 Skill”放到后期，生成后必须经过用户审核和静态校验。

### 首批 Skill

- 会话检索与总结。
- 笔记整理。
- 每日回顾。
- Provider 健康检查。
- 失败请求诊断。

## 里程碑 4：任务与自动化

目标：让用户保存可重复任务，并能查看每次执行结果。

当前状态：已交付 `Workflow / WorkflowStepDefinition / WorkflowRun / WorkflowStep / WorkflowSchedule / ScheduledTask` Room Ledger、1 至 8 步创建/编辑、前台与后台顺序执行、步骤级输入/输出快照和幂等键、失败新 Run 重试、一次性与 Daily/Weekly 计划、结果通知和后台 blocked 审批；前台三步骤重试、定义编辑冻结历史、后台三步骤与审批恢复继续下一步骤均已通过真机。第 47 阶段加入进程内 Worker 注册表和启动恢复候选快照；第 48 阶段为 `RUNNING` 实例加入可见停止和定向兜底；第 50 阶段进一步让停止意图先持久化为 `STOP_REQUESTED`，并让 Worker 重入、启动恢复、迟到结算和周期物化共享同一取消栅栏。后台执行栈断点续跑和精确定时仍待评估，当前证据仍不需要 Foreground Service。

### 要做什么

- 已完成：`Workflow`、`WorkflowStepDefinition`、`WorkflowRun`、`WorkflowStep`、`WorkflowSchedule` 与一次性/周期 `ScheduledTask` 数据表及关联字段。
- 已完成第一版：WorkManager 负责带联网约束的一次性可延迟任务；Daily/Weekly 规则每次物化一个未来 OneTime 实例，确需准确时间时再评估 AlarmManager 和精确闹钟权限。
- 暂不引入：真实 8 步复合 SAFE 后台 Run 已在约 229.416 秒全部成功，真实运行中停止样本约 32.6 秒；进程内 Worker 所有权、可见停止与 `STOP_REQUESTED` 持久化重对账均已完成。设备虽支持 LMK 原因报告，但最新 14 条历史退出仍没有 `REASON_LOW_MEMORY`。只有超过 WorkManager 适用边界或任务对用户足够重要时，才使用 WorkManager 的 long-running worker/Foreground Service 支持。
- 已完成：每次执行保存计划/实际时间、步骤定义快照、输入/输出、重试来源、结果和失败原因。
- 已完成：步骤使用稳定幂等键；重试只复用连续成功前缀，旧 Run 保持不变，已启动失败步骤需要二次确认。
- 已完成：后台任务遇到需要用户确认的敏感操作时进入 blocked 状态，不得静默执行。

### 第一批自动化

- 每日/每周生成会话回顾。
- 定时提醒并附带上下文。
- 定时检查指定 Provider 是否可用。
- 定时整理候选记忆，结果等待用户确认。

## 里程碑 5：Android 设备 Agent

目标：在独立开关和明确权限下，完成有限、可观察、可验证的跨应用操作。

当前状态：观察与有限动作层已完成。应用开关默认关闭，健康检查区分关闭、未授权、服务断连和 READY；全部设备工具仅在前台直接 `/agent` 暴露，Workflow/后台双层拒绝。结构化快照、节点/文本预算、30 秒 ref、窗口 generation/路径/指纹失效、敏感节点脱敏、高敏窗口/隐私应用整窗拒绝、首批应用白名单、敏感输入拒绝、必要审批和动作后重新观察验证均已通过 Redmi 验收。Service 使用标准节点动作与系统返回/主页，不具备坐标手势或截图能力。

### 技术方案

- 使用 AccessibilityService 获取可访问节点树和执行标准动作。
- 为一次观察生成短生命周期的节点引用，页面变化后引用失效。
- 点击、输入和滚动只按短生命周期节点引用执行；当前不提供坐标兜底。
- 截图和视觉模型继续后置，不能成为当前节点校验或隐私过滤的绕过路径。
- 每个改变业务状态的动作后重新观察，不能仅凭点击成功返回判断完成。
- 增加 Accessibility 健康检查、权限失效提示和稳定态恢复。

### 第一批设备工具

- 已完成：`device.snapshot`
- 已完成：`device.open_app`
- 已完成：`device.back`
- 已完成：`device.home`
- 已完成：`device.tap_ref`
- 已完成：`device.type_text`
- 已完成：`device.swipe`

### 安全边界

- 默认关闭，需要单独启用设备 Agent。
- 支付、下单、删除、发送、发布、授权和系统设置修改必须再次确认。
- 密码框、验证码、支付页面和隐私应用默认不读取或记录内容。
- 工具结果按隐私级别控制落盘，release 日志不保存原始敏感内容；当前不采集截图。
- 第一阶段只支持少量已验证应用和流程，不承诺任意 App 通用自动化。

## 里程碑 6：高级能力

以下能力在前述基础稳定后再进入：

- 文件附件、图片、富文档直传和 `/agent` Responses 附件输入已完成；语音输入与 TTS 仍待实现。
- 文档解析、知识库管理、RAG 检索、Agent 接入、答案级引用 UI、Embedding v1、显式索引重建和多 Provider/模型空间共存已完成；规模化 ANN、自动后台批量重建与召回质量验证仍待实现。
- MCP Client 与远程工具，但必须增加 Server 信任、工具审核和网络权限策略。
- 通知摘要、日历、联系人和系统分享入口。
- 多 Agent 分工、远程 Channel、跨设备同步。
- 手机端本地模型和模型下载管理。

## 横向工程任务

这些任务不属于单个功能，但必须贯穿所有里程碑：

- 已建立 Room Schema 导出、migration 测试，以及面向用户的数据库 ZIP 备份与恢复工具。
- 已建立脱敏网络/运行日志和稳定 `runId / stepId / toolCallId` 审计身份；新增设备或远程工具时继续沿用该边界。
- 已为 Agent Runtime 提供假的 LLM 和 Tool Executor，覆盖确定性状态机、取消、超时、预算和恢复测试。
- 已建立工具契约测试，持续校验 Schema、风险、权限、确认、后台能力和验证信息不能缺失。
- 已完成当前可审计性能指标：任务中心展示 Run 总耗时、终态成功率、平均耗时、模型/工具/审批次数、模型总耗时、平均 TTFB、最终 JSON Prompt 字节、上游 Token usage 覆盖率和失败终态分布；未返回 usage 的请求不补零，Prompt 正文不重复落库。
- 对低能力模型做回归，减少多阶段 LLM 调用和超长工具提示词。
- 已完成当前故障注入基线：用户取消、模型/工具/整次 Run 超时、网络响应中断、Workflow 重复回调、执行/验证中进程终止，以及审批期间和工具执行期间 Android 权限撤销均有确定性测试；`tool.verify` 落库后和验证 Step 完成后两个终止点均确认恢复不重复工具或验证事实；真机外部 `pm revoke` 同时确认系统会直接终止应用进程。启动恢复候选快照还覆盖快照期间新 Worker 等待，以及旧链收敛、当前进程链保持并完成且不复制 Run。停止故障注入覆盖 WorkManager 与即时 fallback 同时失败、当前进程所有权仍登记、迟到成功结算和周期下一实例门禁，均以持久化 `STOP_REQUESTED` 收敛。Redmi 另有强制 Doze、trim-memory 和无压力对照，但这些受控命令不冒充自然 LMK 或连接失败因果证据。
- 每个涉及 Android 系统能力的里程碑都必须在真机验证，不以单元测试替代。

## 优先级清单

| 优先级 | 工作项 | 当前状态 | 原因 |
|---|---|---|---|
| P0 | 请求取消、结构化 Responses 输入、Provider Adapter | 已完成，包括用户 Image/Document、函数调用与结果 typed Items、可选 Reasoning summary | 后续 Agent 循环的基础协议 |
| P0 | Room、Repository、迁移测试和导出 | Room/Repository、普通聊天上下文 Preparer、发送 Coordinator、会话状态 Policy 与保存 Coordinator、Schema 导出、v4→v32、event metadata、Memory/Knowledge FTS、Embedding/相关性 shadow 审计、Tool Ledger、Agent Profile、MessagePart、知识引用、进程退出观察和用户 ZIP 备份/恢复已完成 | 保证升级和本地数据可恢复 |
| P0 | AgentRun 状态机、事件日志、取消与恢复 | 最小状态机、事件、取消、安全重新运行、进程终止、运行中撤权、多步骤审批等待恢复、两个白名单写工具受限验证，以及全部工具 `PASSED` 后的本地收尾恢复已完成；提交状态未知与验证事实不完整的执行栈仍 fail-closed | 决定任务是否可靠、可观察 |
| P0 | Tool Registry、Schema、风险、确认和验证 | 已完成完整类型/约束/枚举、业务校验器、风险/确认、Android 权限、前后台来源门禁、超时、回读验证策略和重复名称启动校验 | 决定执行边界和安全性 |
| P1 | 应用内低风险工具和任务时间线 UI | 第一批工具、对话时间线、任务中心、完整工具结果、失败重试及 Run/历史运行指标已完成 | 已形成第一条端到端 Agent 链路 |
| P1 | 长期记忆管理与 FTS 检索 | 管理 UI、FTS、中文兜底、来源审计、候选确认、敏感过滤、去重/冲突、跨进程删除撤销、引用 ID 审计、单次召回关闭、过期策略和时间衰减已完成 | 形成个人化和跨会话连续性 |
| P1 | Skill 按需加载 | 内置与本地声明式 Skill、版本化 JSON、严格导入校验、Room Catalog、规则选择、工具白名单、启停/删除管理和 Run 审计已完成 | 控制 Prompt 和工具面增长 |
| P1 | Agent Profile v1 | 多 Profile 管理、固定 Provider/模型/协议、角色提示、上下文策略、工具/Skill 白名单、记忆硬边界和 Run 快照恢复已完成 | 把 Agent 身份与普通聊天配置分离 |
| P1 | 结构化消息 parts | Text/Reasoning/Image/Document/Tool 持久化、旧 text 回填、供应商摘要折叠展示、可信 Tool 投影、用户附件选择/预览/请求/备份和 Compose 展示已完成 | 让聊天内容、用户附件、供应商摘要与工具执行事实进入同一可恢复消息模型 |
| P1 | Workflow Ledger 与后台调度 | 多步骤定义/编辑、前后台顺序执行、步骤快照、新 Run 重试、一次性与 Daily/Weekly WorkManager、SAFE/blocked/通知和规则替换/停用已完成；进程内 Worker 所有权、启动恢复隔离、运行中可见停止、`STOP_REQUESTED` 持久化栅栏和 Workflow/Task 原子结算已完成，执行中断仍按 fail-closed 收敛；已有 229.416 秒八步复合只读成功与 32.6 秒停止样本，仍缺自然 LMK，Foreground Service 暂无引入依据 | 支持持续任务且可追溯 |
| P2 | Accessibility 设备工具 | 观察、有限动作、审批、操作后验证和少量指定 App Redmi E2E 已完成；Workflow/后台与任意 App 继续关闭 | 扩展到真正移动端执行，风险较高 |
| P2 | 附件、视觉、语音和 RAG | 单张用户 Image、PDF/UTF-8 Document 与 DOCX/PPTX/XLSX 直传、`/agent` Responses 附件输入，以及 RAG 数据、管理 UI、`knowledge.search`、引用审计、模型上下文投影、答案引用 UI、Embedding v1、显式索引重建、相关性扩样校准、answerability shadow 协调、生产 adapter、保存后 caller、设置开关、进程内 notice 和有界真实样本遥测已完成；Room shadow Store、notice 跨进程恢复、生产拒绝、语音、ANN 与自动后台批量重建未完成 | 提升输入输出能力 |
| P3 | MCP、远程 Channel、多 Agent、本地模型 | 暂缓 | 生态价值高，但复杂度和攻击面更大 |

## 明确不照搬的做法

- 不照搬多阶段 Analyze/Reflect/Plan/Review 全部依赖 LLM 的重型流程；先用单循环和确定性状态机。
- 不把所有工具 Schema、数据库结构和 Skill 全量注入每次请求。
- 不允许模型决定工具风险或确认策略。
- 不把“工具返回 success”直接等同于任务完成。
- 不以任意 Shell 作为移动 Agent 的通用工具。
- 不在缺少 Run Ledger、取消和恢复前上线后台自动化。
- 不在缺少权限隔离和工具审核前开放 Skill 市场或 MCP Server 任意接入。

## 建议的下一项开发

基于 `v0.1.11` 当前状态，下一批实际代码任务建议拆为：

1. 已完成：`WAITING_APPROVAL` 可在任意已验证前缀后恢复原 Run。恢复要求唯一待审批请求与链尾 ToolCall 完全匹配，前序结果全部成功并 `PASSED`，步骤、Ledger 与 typed event 一致；Runtime 重建可信前缀、工具调用预算和循环指纹，不重放前序工具。磁盘 Room 关闭重开与 Redmi 124 条完整 instrumentation 已通过。
2. 已完成跨进程删除撤销；后续后台任务必须复用原子快照与 Room 状态核对边界。
3. 已完成本地 Skill 文件格式、导入校验与启停/管理 UI。
4. 已完成：不依赖调度器的 `Workflow / WorkflowRun / WorkflowStep` Ledger 和前台手动执行闭环。
5. 已完成结构化 `ScheduledTask`、WorkManager 一次性非精确调度、计划/实际时间、结果通知和后台 blocked 审批。
6. 已完成：真机一次性 SAFE/blocked、完成/失败/blocked 通知，以及触发前进程回收后的 WorkManager 冷启动执行验证。
7. 已完成 Daily/Weekly 周期规则：每次触发创建独立 ScheduledTask/Workflow Run，规则替换和停用同步取消 WorkManager，周期触发不复用前台审批等待。
8. 已完成多步骤 Workflow 定义、编辑、步骤级幂等键、输入/输出快照和安全新 Run 重试；后台中断继续收敛旧 Run，不在没有副作用证明时原地续跑。
9. 已完成多步骤前台/后台真实模型真机验收：编辑只影响未来定义，审批后继续下一步骤，失败来源 Run 保持不变，新 Run 正确关联来源并重新执行未完成步骤。
10. 已完成第一批 Run 性能指标和故障注入：任务中心展示总耗时、终态成功率、平均耗时、模型/工具/审批次数；网络响应中断归类为连接失败，取消、超时和重复回调测试保持通过。
11. 已完成请求级审计：规划/总结成功后写入 usage、TTFB、最终 JSON Prompt 字节；规划语义解析失败仍保留已返回遥测；任务中心展示 Token 覆盖率和失败终态分布。
12. 已完成执行/验证中进程终止和 Android 权限运行中撤销故障注入：审批后执行前和工具返回后验证前都会复检权限；进程重建默认把旧 Run 与活动 Step 一致取消，只有显式白名单工具的完整历史证据可以进入受限恢复。
13. 已完成：建立持久化 `ToolExecutionReceipt`、执行时 `ToolReplaySafety` 声明快照和纯证据判定 module；回执绑定 ToolCall，错配时 Runtime fail-closed，旧事件默认不可重放，当前定义升级不能放宽历史证据，任务中心不显示原始幂等键。
14. 已完成：`notes.create` 使用 ToolCall ID 作为可审计的存储层唯一幂等键，同键同载荷在数据库重开后仍返回同一 operation ID，同键载荷漂移被拒绝；工具已声明 `IDEMPOTENT_BY_KEY`。Room v17 迁移保留旧笔记并把其幂等键留空，Pixel_9 与 Redmi 各 42 条 instrumentation 通过。
15. 已完成：仅针对具有完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据的 `notes.create` 开放“验证阶段恢复”。从持久化 ToolResult 唯一还原 ToolCall，按 operation ID 回读原笔记，补齐 `tool.verify` 和本地 `recovery.summarize`；多工具 Run 会从历史验证事件重建成功前缀，Workflow 启动对账会保留候选直到当前步骤输出落库。确定性进程中断、Room 重建和真实 Registry 不重复写入均已覆盖。旧模型协程、其他工具执行栈和 Workflow 后续步骤仍不恢复。
16. 已完成：`memory.remember` 使用独立 Room operation ledger，以 ToolCall ID 主键绑定原始载荷哈希和 memory ID；数据库重开后同键同载荷复用原 operation，载荷漂移被拒绝。工具已声明 `IDEMPOTENT_BY_KEY`。
17. 已完成：Room v19 为记忆 operation 增加提交结果业务快照哈希，Registry 从持久化 Run Context 重建原请求并开放 `verifyCommittedEffect()`。未修改、启用、未过期的记忆验证成功；内容、标签、类型、来源或置信度编辑返回 `MEMORY_CHANGED`，禁用返回 `MEMORY_DISABLED`，过期返回 `MEMORY_EXPIRED`，删除返回 `MEMORY_NOT_FOUND`，删除后按原快照撤销恢复可再次成功。置顶、引用时间和未来过期时间不影响验证；v18 历史 operation 因缺少结果快照保持 `EVIDENCE_INCOMPLETE`。冷启动恢复不再次调用 `remember()`，原 operation ID 和回执保持不变。
18. 已完成：`memory.remember` 的八类只读恢复失败通过 `run.recovery_failed` typed event 保存稳定错误码、原因和建议动作；任务中心详情顶部直接显示恢复处理状态带，事件区保留完整字段。所有建议都要求创建新 Run，旧 Run 保持 `FAILED`。生产 Registry 当前只有 `notes.create` 与 `memory.remember` 两个写工具，不为套用模式虚构第三个写工具；通用执行栈、旧模型协程和 Workflow 后续步骤继续 fail-closed。
19. 已完成：Room v20 新增 `agent_tool_calls / agent_tool_results`，`appendEvent()` 在同一事务内按 typed metadata 双写参数、proposed/validated 锚点、结果、显式错误、耗时、Executor/最终验证、记忆引用、重放声明和执行回执。ToolCall 身份或参数漂移整笔回滚；Repository 重建后可按 Run 查询。v19 旧 Run 保留 event-only，不补造关联，验证阶段恢复仍可追加事件；恢复策略未切换到新表。
20. 已完成：任务中心对 v20 新 Run 使用 Tool Ledger-first 明细，Repository 批量加载调用/结果，UI 以调用为单位展示 proposed→validated→result→verified 状态；没有 Ledger 但存在 typed 工具事件的旧 Run 自动回退。缺少 ToolCall ID 的旧结果/验证显示“关联未知”，不伪造调用关联；账本与事件的身份、字段、锚点或孤立记录异常显示一致性告警。`AgentRunResumePolicy`、重试和指标继续读取 RunEvent，恢复证据切换留待独立阶段。
21. 已完成：`AgentRunRecoveryEvidencePolicy` 将 `notes.create / memory.remember` 的受限验证恢复切换为 Ledger-first。v20 非空账本要求每个调用恰好一个结果，按 proposed 锚点重建顺序，并核对 proposed→validated→result→verified 的身份、字段、派生错误、时间和事件顺序；部分账本、重复身份、额外事件或双源漂移均 fail-closed。账本完全为空的旧 Run 才回退 typed event，缺少 ToolCall ID 的历史验证返回关联未知并升级为 `EVIDENCE_INCOMPLETE`，不按结果顺序猜配。多步骤只重建已验证前缀并恢复最后一个尚无验证终态的已提交结果；白名单、旧模型协程、通用执行栈和 Workflow 后续步骤边界不变。
22. 已完成：失败 Run 的重试副作用判断改为 Ledger-first，并复用 `AgentToolLedgerConsistencyPolicy` 的完整链路检查。非 SAFE 调用只要结果成功，或回执为 `COMMITTED / UNKNOWN`，就要求二次确认；异常账本同样 fail-safe，明确 `NOT_COMMITTED` 的失败结果和仅 validated 尚未执行的调用不额外抬高门禁。账本全空的旧 Run 保留 typed event 回退，旧 Run 本身仍不修改。Run 质量和模型遥测继续使用 Step/LLM typed event，因为 Tool Ledger 没有等价耗时、TTFB、Prompt 与 usage 字段，不为追求形式统一而改变统计口径。
23. 已完成：Agent Profile v1 使用 Room v21 `agent_profiles` 保存名称、标识、Provider/模型、API 模式、系统提示词、当前会话上下文策略、工具/Skill 白名单和记忆开关。新 Run 写入唯一 `agent.profile.selected` 快照；Profile Registry 是工具执行硬边界，Skill 只能继续缩小。审批恢复和已提交结果恢复固定原 Run 快照，重复、损坏或越权审计 fail-closed；前台/后台 Workflow 单次执行固定同一 Profile。设置页已完成新增、编辑、选择、删除和 Provider 绑定保护，真实 `Time Agent + gpt-5.5 + Responses + app.current_time` 已在 Redmi 完成端到端验收。
24. 已完成：Room v22 新增 `message_parts`，Text/Tool 使用稳定 ID 与 sequence。v21→v22 只回填旧 Text；新 Agent 结果依据 `AGENT_RESULT + VerifiedAgentContext` 生成 Tool，普通聊天无法伪造。`MessageRepository` 统一前后台原子写入，前台快照增量 upsert 且只按显式 ID 删除会话，Compose 同气泡展示 Text 与 Tool 证据。242 条 JVM、仅 Redmi 执行的 73 条 instrumentation 和真实 `gpt-5.5 + app.current_time` Run 均通过；交错写测试确认旧前台快照不会删除后台追加消息或新建会话，数据库确认最新消息为 sequence 0 Text + sequence 1 Tool。
25. 已完成：Room v23 为 Reasoning part 增加来源、供应商 item ID 和 summary index。普通对话仅在 Responses 模式且用户显式开启时发送 `reasoning.summary=auto`；非流式和 SSE 流式只接收供应商 `summary_text`，按来源身份去重并固定在 Text 前，Compose 默认折叠并标注“供应商提供”。原始 `reasoning_text`、Chat Completions 非标准 `reasoning_content` 和 Agent 结果中的 Reasoning 均不能进入正文、parts 或 `VerifiedAgentContext`；debug 响应与 SSE 日志也做结构化脱敏，无法解析的可疑内容失败关闭。256 条 JVM、仅 Redmi 执行的 77 条 instrumentation 均通过；`gpt-5.5` 非流式和流式真实请求都返回 Reasoning summary，Room 回查均为 sequence 0 Reasoning + sequence 1 Text，应用最终保持 Redmi 前台且 crash buffer 为空。
26. 已完成：Room v24 为 Image part 增加 MIME、文件名、BLOB 和 detail。系统选择器单次接收 PNG/JPEG/WEBP，读取上限 8 MB，并核对声明大小、MIME、文件签名和可解码性；进入消息后不再依赖 URI。Responses 把近期 USER Image 映射为 `input_image` Data URL，Chat Completions 在发送前明确拒绝，`/agent` 仅在 Responses 规划请求接收；Agent 信任策略只允许 USER 保留 Image，不能提升为 Tool 或 `VerifiedAgentContext`。Compose 支持待发送缩略图、移除和历史图片，debug 日志脱敏图片 Base64、`file_data`、生成图片结果与 `encrypted_content`。图片 BLOB 按当前会话加载，轻量快照保留未加载 BLOB；发送前等待 Room 事务，切换会话原子更新，显式删除过滤阻止陈旧快照复活。267 条 JVM、仅 Redmi 执行的 85 条 instrumentation 均通过；真实 `gpt-5.5` 图片轮次返回 `IMAGE_OK`，Room v24 回读确认 PNG BLOB 持久化。
27. 已完成：Room v25 增加 Document part 的提取文本、PDF 页数和 detail，并复用附件 MIME、文件名与 BLOB。Document v1 单次接收 PDF、TXT、Markdown、JSON、CSV，最大 8 MB；PDF 签名与扩展名在领域策略交叉校验，DocumentsProvider 错报 MIME 也不能绕过 `PdfRenderer` 和最多 50 页预算，文本严格使用 UTF-8 并限制 200,000 字符。原始文件与受限提取文本同事务保存，附件 BLOB 按当前会话加载，轻量快照保留未加载 Image/Document。Responses 映射为 `input_file` Data URL，PDF 使用 `detail=auto`；Chat Completions 明确拒绝，`/agent` 仅在 Responses 规划请求接收，USER-only 信任边界不变。Compose 附件菜单、待发送元数据/移除和历史 Document 展示已完成。281 条 JVM、仅 Redmi 执行的 92 条 instrumentation 均通过；真实 `gpt-5.5` Markdown 轮次在 4.33 秒返回 `DOC_STAGE27_OK`，Room v25 回读确认 67 字节 BLOB 与 67 字符提取文本持久化。
28. 已完成：Document part 在不升级 Room 的前提下扩展 DOCX、PPTX、XLSX。`OpenXmlDocumentPolicy` 解析 ZIP 中央目录并逐条核对 local header、文件名、加密位、磁盘号、ZIP64 extra 与实际数据范围，再以固定缓冲区流式核对条目集合、CRC 和真实展开量；加密、分卷、ZIP64、超过 4,096 条目、声明或实际展开总量超过 64 MB、扩展名/MIME/结构不一致均在进入消息前拒绝。系统选择器、Room BLOB、轻量快照、Responses `input_file`、Compose 元数据和 USER-only 信任边界继续复用第 27 阶段契约。284 条 JVM、仅 Redmi 执行的 93 条正式 instrumentation 均通过；一次性真机 E2E 使用设备现有 `gpt-5.5 + Responses` 在 4800 ms 返回 `RICH_DOC_STAGE28_OK`，日志确认 DOCX `file_data`、Authorization 与加密推理内容均脱敏。
29. 已完成：Room v26 新增知识文档、chunks、FTS4 和检索审计。严格 UTF-8 导入规范换行、拒绝空白/NUL，并按规范全文计算 SHA-256；确定性分块优先段落边界、保留有限重叠和精确 offset，不切断 UTF-16 代理对。替换在同一事务递增 revision 并全量更新 chunks/FTS，失败注入确认整笔回滚；禁用/删除立即退出检索，旧 chunk ID 随 revision 失效。该阶段 291 条 JVM、仅 Redmi 执行的 98 条 instrumentation 均通过；Redmi 主库升级为 v26，原 Provider 保留。该阶段当时尚未接入管理 UI、Agent 工具和模型引用注入，后续第 30、31 阶段已经补齐。
30. 已完成：设置页新增知识库管理 UI，使用 SAF 有界读取、轻量摘要 projection 和最多 4,000 个 UTF-16 单元且不切断代理对的详情预览，支持导入、列表、详情、启停、替换、删除和带 retrieval ID/chunk offset 的检索预览。独立 ViewModel 串行化变更、取消旧详情/刷新/检索、变更开始即隐藏失效详情，并区分存储提交失败与提交后的刷新失败。291 条 JVM、仅 Redmi 执行的 106 条 instrumentation 均通过；真实 UI 验证 revision 1→2、停用/删除 0 命中、旧词失效、新词命中和 r1/r2 审计引用分离，最终主库知识表清空且 Provider 保留。

31. 已完成：新增 SAFE、后台可用的 `knowledge.search` 和内置 `local-knowledge` Skill。`query` 必填 1 至 200 字符，`limit` 默认 3、最大 5；ToolResult、RunEvent、独立 Tool Ledger、VerifiedAgentContext、MessagePart、规划历史、Workflow 输出和任务中心统一保存稳定 retrieval/document/revision/chunk/offset 引用。Room v27 为 ToolResult/MessagePart 增加默认空引用列，不猜造旧证据。失效引用会让整条历史知识消息、可能污染的旧摘要和 Workflow 前序正文退出新模型请求，历史审计不回写；旧 Profile 不自动扩权，无 Profile 审计的历史 Run 固定在知识工具上线前的工具集合。309 条 JVM、仅 Redmi 执行的 113 条 instrumentation 通过；五份真实项目长期文档 corpus 的多词重排查询、top-1 和负例门禁全部通过。真实 `Time Agent + gpt-5.5` 已选择 `knowledge.search`，Run、Ledger、retrieval 和 MessagePart 引用一致。

32. 已完成：Agent 回复新增独立、默认折叠的答案引用区域，只从可信 `MessagePart.Tool`/`VerifiedAgentContext` 投影，不解析模型自由文本。展开后展示文档名、revision、chunk 和半开 offset 区间；Room 使用文档摘要与引用 chunk 的 projection 判定“当前有效 / 历史版本 / 当前不可用”，按最多 900 个 SQLite 参数分批核验，取消旧 Job 不回写失败状态，停用状态优先于历史 revision。文档仍存在时可跳转知识库详情，删除后关闭跳转。320 条 JVM、仅 Redmi 执行的 118 条 instrumentation 均通过；真实 `MainActivity` E2E 覆盖当前引用跳转、替换后的历史标记、跳转当前 revision，以及删除后的不可用状态和清理。Embedding 继续后置。

33. 已完成：设备 Agent 只读观察层。新增默认关闭的独立开关、系统 Accessibility 入口、四态健康检查、只读预览、`device.snapshot` 和内置 `device-observation` Skill。快照最多 200 个节点/4000 字符，文本不切断 UTF-16 代理对；可操作非敏感节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、路径和指纹，任一失败或页面变化立即撤销。敏感字段脱敏，高敏窗口与隐私应用整窗拒绝；Service 不具备手势或截图能力。工具仅在前台直接 `/agent` 暴露，Workflow、后台和关闭状态双层拒绝，旧 Profile/Skill 不自动扩权。337 条 JVM、仅 Redmi 执行的 122 条 instrumentation 均通过；真实 Service 在 instrumentation 外验证主界面 `nodes=27 / refs=8`、敏感探针 `redacted=2 / refs=1`、支付探针 `SENSITIVE_WINDOW`，最终独立开关关闭、系统服务绑定、主界面前台且 crash buffer 为空。

34. 已完成：设备 Agent 有限动作层。新增 `device.open_app / back / home / tap_ref / type_text / swipe` 和 `device-control` Skill；打开应用、点击和输入要求审批，返回、主页和节点滚动为 SAFE。应用白名单只含小灵、系统计算器、时钟和系统设置；输入在审计前拒绝敏感值。节点动作再次核对 snapshot/ref/generation/path/fingerprint，动作后重新 capture 并按包名、桌面、回读文本或 generation 变化验证；首次启动权限页的瞬时空窗口通过只针对窗口过渡的 6×100 ms 有界重试收敛。348 条 JVM、仅 Redmi 执行的 123 条 instrumentation 均通过。Redmi 真实动作覆盖计算器打开/点击、设置滚动/搜索/输入、敏感输入拒绝、返回/主页和时钟启动；真实 `gpt-5.5 + Responses` Run `run-13bcfa28-346f-4a71-b98b-5b44cf28bd92` 完成模型规划、`device.open_app` 审批、动作后验证、Tool Ledger 和最终总结，状态 `COMPLETED`、审批 `APPROVED`、Executor 验证 `PASSED`。首批验收不扩展到任意 App。

35. 已完成：多步骤审批等待恢复。`AgentRunResumePolicy` 只接受一个 `PENDING` Approval 与最后一个已校验、无 ToolResult 的 ToolCall 完全一致，所有前序调用均有成功结果和 `PASSED` 验证，执行/验证/审批 Step 与 Ledger/Event 严格对应。恢复后重建 `completedTools`、已执行调用数和调用指纹，批准当前工具后继续原 Run 的后续规划；前序工具不会重放，预算与重复调用检测不会因重启清零。354 条 JVM 与仅 Redmi 执行的 124 条 instrumentation 全部通过；新增磁盘 Room 测试真实关闭并重开数据库，确认原 Run ID、第一步已验证前缀、第二次审批和审批 Step 保持。

36. 已完成：所有工具结果与验证事实已持久化后的原 Run 收尾恢复。`AgentRunResumePolicy` 新增 `VERIFIED_TOOL_COMPLETION`，只接受 `VERIFYING`、无待审批、每个结果成功且每个验证均为 `PASSED`、执行/验证 Step 一一对应、最后验证 Step 为 `RUNNING/COMPLETED`、其后没有新 Step，并要求 Ledger/Event/Profile 一致。恢复重建全部可信工具、调用数和指纹；若需要只补齐最后验证 Step，再生成本地可信总结，不调用 Executor/LLM、不追加第二条 `tool.verify`、不续跑 Workflow。两个确定性终止点、磁盘 Room 重开、358 条 JVM 和仅 Redmi 执行的 125 条 instrumentation 全部通过。

37. 已完成：`AgentExecutionBudget` 改用可注入单调时钟，规划、工具和总结段共享同一累计 Run 预算，工具 duration 使用同一时钟。新 Run 和每个成功执行段写入 `run.execution_budget.updated` typed 快照；审批及受限恢复继承原 Run 的 total/consumed，旧 Run 先建立零值兼容起点，缺 metadata、越界、总额漂移、累计回退，或最后 ToolResult 晚于最后预算快照时拒绝原地恢复。Step 上限小于剩余预算时报告 Step timeout，二者相等或剩余更少时报告 Run timeout；审批等待不计入，调用方外部超时仍按取消收敛。多段累计、精确边界、恢复剩余 20ms、工具结果/预算崩溃窗口、旧 Run 起点、codec/UI 和损坏证据测试均已覆盖；374 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

38. 已完成：重试前副作用证据分类。`AgentTaskRetryPolicy.assessEvidence()` 统一读取独立 Tool Ledger、旧 typed RunEvent、Receipt 状态和执行/验证中断，输出 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE`。任务中心卡片和确认弹窗展示稳定分类码、原因和建议；确认提交前重新读取当前 Run，状态不可重试时关闭弹窗，证据码变化时更新弹窗并停止本次旧确认，只有分类稳定后才继续。该阶段没有扩大原地恢复能力，仍禁止恢复旧模型协程、调用旧 Executor 或把 UNKNOWN 当作未提交；所有重试继续创建关联新 Run，旧 Run 保持不变。381 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

39. 已完成：Workflow 步骤落库后的进程终止与启动对账。`ScheduledWorkflowOrchestrator` 在 `completeWorkflowStep()` 成功返回后、下一步骤启动前提供专用故障注入 seam；模拟进程终止直接退出，不触发普通失败结算、通知或 `Result.retry`。JVM 测试确认第一步只执行一次、输出已保存、第二步仍为 `PENDING` 且没有结算；Room 测试再确认启动 `reconcileInterruptedRuns()` 会保留完成前缀并关闭旧 Run，`retryRun()` 创建关联新 Run，将前缀标为 `SKIPPED` 并设置 `reusedFromStepId`，只从首个未完成步骤继续。生产保持 no-op 注入，不自动恢复旧 Workflow 或复制 Agent Run。382 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 13 条定向 Workflow instrumentation 全部通过。

40. 已完成：通用重试证据可见性。任务中心卡片现在直接显示 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE` 的稳定分类、原因和建议动作；确认弹窗与卡片仍复用同一证据评估，确认提交前继续校验证据码。该切片只改善恢复处置的可解释性，不改变 `COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED` 和 `EVIDENCE_INCOMPLETE` 的确认门禁，不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。383 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

41. 已完成：启动恢复证据快照。不可原地恢复的活动 Run 在步骤/审批收敛前按原始状态计算重试证据，并写入 typed `run.recovered.retryEvidenceCode`；执行/验证中无结果固定为 `COMMIT_UNKNOWN`，纯思考中断且无副作用为 `NOT_COMMITTED`，Ledger 漂移为 `EVIDENCE_INCOMPLETE`。任务中心和确认前仍重新计算当前证据；带快照的 Run 使用快照还原收敛前中断边界，避免把启动清理产生的 `PENDING -> CANCELLED` 误判成副作用，当前 Ledger 真正漂移时仍升级为 `EVIDENCE_INCOMPLETE`。旧 Recovery 事件缺字段继续兼容，可原地恢复候选不写取消证据；该阶段不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。388 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

42. 已完成：Worker 冷启动重入收敛。`ScheduledWorkflowReentryCoordinator` 只拦截仍为 `RUNNING` 的 ScheduledTask，沿当前 Task→WorkflowRun→AgentRun 关联链按 ID 定向关闭旧执行栈，再按 Agent→Workflow→Task 顺序完成对账；普通 `SCHEDULED` 任务不改变 claim/执行路径，不使用 `Result.retry`，不恢复旧模型协程或 Workflow 后续步骤。无关前台 Agent 保持不变，周期下一实例仍等待旧任务进入终态后再物化。391 条 JVM、Lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 126 条 instrumentation 全部通过；该阶段当时只完成确定性 Room/协调器重入验收，真实系统强杀 Worker 的耗时与回收位置留待第 43 阶段。

第 42 阶段当时的下一阶段边界：继续在 Redmi 验收真实较长 Worker 任务的系统回收位置、WorkRequest 重入和持久化耗时；提交状态未知或验证事实不完整仍只安全收敛并引导关联新 Run。旧模型协程和 Workflow 后续步骤仍不原地恢复；设备工具继续禁止进入 Workflow 或后台自动化。

43. 已完成：Redmi 真实 Worker 冷启动重入。临时 instrumentation 在 Redmi `wsvwypiz7xwslvl7` 创建 7 步 SAFE Workflow 并保持目标进程存活；`WorkRequest=0d9aa2a5-ff1b-4a04-ad74-5d3c7bdf76db` 于 `06:05:03` 启动，`06:05:05` 首个 Agent Run 处于 `THINKING`。`am kill` 因 instrumentation 前台身份未终止 PID `25755`，立即使用 `run-as ... kill -9` fallback；约 `0.2s` 后新 PID `26092` 冷启动同一 WorkRequest。重入只收敛关联的 Agent/Workflow/ScheduledTask，后 6 步未启动，关联 Agent Run 数量仍为 1，工具调用/结果为 0，实际耗时 `3360ms`。该受控强杀不等同 Android 自主回收，也不扩大为通用原地恢复。

44. 已完成：任务中心“需确认”队列。新增 `AgentTaskFilterPolicy` 和 `NEEDS_CONFIRMATION` 筛选，只聚合已结束、可重试且必须确认副作用证据的 Run；提交未知、已提交未验证/已验证和证据不完整沿用现有卡片说明与确认弹窗。确认提交前重新核对证据码，稳定后只创建关联新 Run，旧 Run 保持不变。新增 3 条 JVM 筛选策略测试和 1 条 Redmi Compose instrumentation，完整门禁为 394 条 JVM、127 条 Redmi instrumentation。

45. 已完成：不可原地恢复 Run 的结构化处置。`AgentRunResumePolicy` 的 `RESTART_REQUIRED` 现在由构造约束强制携带稳定 `AgentRunRestartDispositionCode`，覆盖 Run 状态、Profile、预算、审批边界、恢复证据、步骤对应、工具定义和已提交副作用证明等类别；每类同时给出具体策略原因、证据边界和只创建关联新 Run 的建议。`closeInterruptedRuns()` 在改变 Step/Approval 前完成评估，并把恢复类型、处置码、策略原因、边界、建议和重试证据一起写入 typed `run.recovered`。任务卡、详情顶部与事件区读取同一历史快照，旧事件不补造，未知未来枚举 fail-closed。完整门禁为 395 条 JVM、128 条仅 Redmi instrumentation。

46. 已完成：Redmi 长任务与系统策略证据。8 步 SAFE Workflow 的首步成功，第二步重复 `app.current_time` 被循环保护安全终止，Worker 总耗时约 28.5 秒；强制 Doze 在 20 秒观察窗内保持同一任务 `SCHEDULED`，退出后只创建一个 Workflow/Agent Run。退出 Doze 与 `RUNNING_CRITICAL` trim-memory 样本均快速出现 `connection closed`，但无压力对照暴露的是前台启动恢复与新 Worker 的状态竞态，因此不建立因果。竞态曾使 ScheduledTask/Workflow 为 `CANCELLED`、AgentRun 被迟到协程覆盖为 `COMPLETED`；现已用 DAO 原子非终态更新冻结 AgentRun 终态，并在 Redmi 增加回归。`force-idle`、`kill -9` 和 trim-memory 均不等于 Android 自主 LMK。

47. 已完成：当前进程 Worker 所有权与启动恢复隔离。`ScheduledWorkflowWorker` 在任何 Repository 构造、重入对账和 claim 前以引用计数注册 Task；`StartupRecoveryCoordinator` 在同一互斥边界冻结活动 AgentRun、WorkflowRun 和 RUNNING ScheduledTask，快照期间新 Worker 等待，已注册 Task 对应的 Workflow/Agent 链从旧候选中排除。ViewModel 后续审批恢复、受限验证恢复、关闭旧 Agent、Workflow 对账和 ScheduledTask 对账全部只消费该候选快照。纯 Kotlin 测试覆盖快照后的 Worker 不进入旧候选；Redmi Room 测试确认旧链收敛，当前链保持活动并可完成，Agent Run 数不增加。实现不使用墙上时间，不新增 owner token/Schema，不恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。完整门禁为 397 条 JVM、130 条仅 Redmi instrumentation。

48. 已完成：后台 `RUNNING` Workflow 可见停止。停止入口先取消目标 WorkRequest 并等待 Worker 正常写入终态，超出有界窗口或系统取消异常时按 Task→Workflow→Agent 持久化链兜底；Agent 尚未关联时仍关闭 Task/Workflow，`SCHEDULED→RUNNING` 抢占会升级为运行中停止。取消只影响目标链且幂等；Run 终态后 Step、Approval、Event 和 Tool Ledger 一并冻结，迟到 HTTP、模型和审批结果不能覆盖或污染 `CANCELLED`。Redmi 真实停止样本约 32.6 秒；另一个三步 SAFE Workflow 依次执行当前时间、会话列表和笔记列表，约 21.8 秒全部完成。LMK probe 显示报告能力可用、历史退出 11 条、`REASON_LOW_MEMORY=0`，不构成自主 LMK 样本。完整门禁为 402 条 JVM、134 条仅 Redmi instrumentation。

49. 已完成：Redmi 正式 8 步 SAFE 后台 Workflow 全成功样本。Task `scheduled-task-b7cae61a-e311-42bc-98a7-f8d601a9be59` 只关联一个 WorkRequest 和一个 Workflow Run，8 个 Agent Run 顺序执行当前时间、会话列表/检索和笔记列表/检索，全部 `COMPLETED` 且 ToolResult 为 `success=true / PASSED`，总耗时约 62.2 秒。先行样本约 49 秒时因模型未调用第 6 步 `memory.search` 安全失败，后两步取消且没有复制 Run。最新 LMK probe 为 `supported=true / exits=6 / lowMemory=0`，6 条均是本轮 instrumentation `FORCE STOP`；生产代码未改变，完整门禁继续为 402 条 JVM、134 条仅 Redmi instrumentation。

50. 已完成：停止异常后的持久化重对账。Workflow 仍活动时，运行中停止先把 ScheduledTask 原子写为 `STOP_REQUESTED`，再请求 WorkManager 取消；系统取消与即时 fallback 同时失败时仍保留停止意图。若 Workflow 在停止事务前已经终态，则停止请求不改写历史终态，直接把半结算 Task 对账到该状态。Worker 重入、启动恢复和停止兜底都识别中间态，当前进程所有权只排除真正 `RUNNING` 的链；Agent Run 尚未关联时，Workflow 对账也会先读取唯一关联 Task 的停止栅栏，将 Run 和未完成步骤收敛为 `CANCELLED`，不会误记为关联缺失失败。停止 fallback 在定向关闭 Agent 后也使用同一原子 API 结算 Workflow/Task；若接管前 Workflow 已有终态，则在事务内直接映射到活动 Task，不再被通用停止栅栏改成矛盾终态。最终 Workflow/Task 在同一 Room transaction 重新读取栅栏与既有 Workflow 终态并原子收敛；步骤完成与成功消息也共享同一停止栅栏，迟到成功不能写成 `COMPLETED` 或追加到会话，周期下一实例不会在旧任务终态前物化。该状态复用既有 TEXT 列，Room v27 Schema 不变；完整门禁为 405 条 JVM、141 条仅 Redmi instrumentation。

51. 已完成：旧验证事件缺少稳定调用身份时 fail-closed。v19 及更早 event fallback 仍允许带完整 ToolCall ID 的 typed 结果/验证进入恢复证据；`tool.verify` 缺少 ID 时不再按同名工具和事件顺序猜配，而是返回无效并由恢复/重试策略保守映射为 `EVIDENCE_INCOMPLETE`。带完整 ID 的前序验证事实与已提交工具只读恢复保持不变，Room v27 Schema 不变。Redmi `ApplicationExitInfo` 基线 `supported=true / exits=2 / lowMemory=0 / fallbackSigkillCandidates=0`，退出来自 instrumentation 与安装包，不是自主 LMK。完整门禁仍为 406 条 JVM、141 条仅 Redmi instrumentation。

52. 已完成：恢复证据 canonical fingerprint。`AgentTaskRetryEvidenceFingerprint` 对工具调用/结果账本与非恢复 typed event 进行长度前缀规范化并计算 SHA-256；启动收敛在修改 Step/Approval 前把摘要与证据码一起写入 `run.recovered.retryEvidenceFingerprint`。任务中心确认弹窗也冻结打开时的码和摘要，提交前重算；新增合法 ToolCall、替换参数或 Receipt、改变验证事件即使仍归类 `COMMIT_UNKNOWN` 也会拒绝旧确认并提示重新确认。指纹一致时原有确认路径不变，旧恢复事件只有证据码但缺少指纹时按 `EVIDENCE_INCOMPLETE` 处理。Room v27 Schema 不变，旧 Run 仍保持不变。完整门禁为 408 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

53. 已完成：ToolResult/预算/验证三段持久化边界。`AgentRuntimeFaultInjector` 分别暴露 Result 事件写入后、预算快照写入后和 `tool.verify` 事件写入后的进程终止 seam；Result 已落库但后续预算快照缺失时，`AgentRunResumePolicy` 固定以 `EXECUTION_BUDGET_INVALID` 拒绝原地恢复，不能因为 Receipt 为 `COMMITTED` 就猜测剩余执行预算。`tool.verify` 已落库但验证 Step 尚未收尾时，Runtime 只补控制面和本地可信总结，不重复 Executor、ToolResult 或验证事件。生产默认注入器保持 no-op，不扩大旧模型协程、旧 Executor 或 Workflow 后续步骤恢复。完整门禁为 409 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

54. 已完成：模型/网络异常预算审计与总结兜底。规划请求带 `AgentLlmResponseException` 时先持久化失败 telemetry，再写入本次已消耗的执行预算；没有统一 telemetry 的网络/网关异常也至少冻结预算后进入失败终态。总结请求网络失败不再把已成功验证的工具 Run 改判为失败，而是写入 fallback 事件、冻结预算并使用本地可信回复完成原 Run。Receipt 回读验证失败继续写入稳定 `RecoveryFailure`，旧写入事实保持 `COMMIT_UNKNOWN` 并要求确认后新 Run 重试，不调用旧 Executor。完整门禁为 411 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

55. 已完成：流式/上游错误 typed 分类。新增 `AgentLlmFailureKind` 与 `llm.request.failed` 事件，统一记录鉴权、地址、限流、模型、超时、DNS、TLS、连接、响应和未知错误；流式断流沿 `ApiFailureClassifier` 的 CONNECTION 语义进入同一事件，未知未来 kind 在 Codec 中降级为 `UNKNOWN`。任务事件区展示阶段、错误码和原因，不保存请求正文；规划/总结预算审计和本地兜底保持第 54 阶段边界。完整门禁为 413 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

56. 已完成：部分流式 delta 的用户可见收敛。普通对话已经显示部分正文后发生断流时，保留已见正文但把消息收敛为 `finishReason=failed` 并展示“内容不完整”，用户取消同样结束“接收中”状态；失败/取消的部分 assistant 不再进入后续请求和会话摘要。新增真实 socket 断流、消息终态和上下文资格测试，完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

57. 已完成：取消时的后台预算写回竞态。Runtime 在新 Run、审批恢复和受限恢复的取消出口统一进入 `NonCancellable`，先追加最新单调预算快照，再取消活动 Step、写入 `run.cancelled` 并冻结 Run；模型或工具 `finally` 已累计的时间因此不会被 WorkManager/用户停止丢失。确定性测试验证取消前 `37ms` 预算可恢复读取，预算事件严格先于取消终态；完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

60. 已完成：instrumentation 退出后的真实后台冷启动成功样本。入队 Probe 立即结束，JobScheduler 冷启动新 PID，生产 Worker 使用持久化 WorkRequest/ScheduledTask/WorkflowRun 完成 8 步、32 次只读工具调用；预算快照无回退、无复制 Run、无模型失败，耗时 `204.977s`。本轮仍无自然 LMK。
61. 已完成：熄屏冷启动长任务成功样本。计划时间后延迟 `159.479s` 冷启动，屏幕持续 `Asleep`，生产 Worker 完成 8 步、32 次只读工具调用，耗时 `244.236s`；预算快照无回退，未发生自然 LMK。
62. 已完成：后台 Worker 系统停止原因审计。Android 12+ 取消收敛读取 WorkManager `getStopReason()`，把 JobScheduler 停止码映射为隐私安全的稳定 `code + name`，并在同一 Room v28 事务写入 ScheduledTask/WorkflowRun；任务中心展示分类。旧 Android、`NOT_STOPPED` 与未知码维持保守结论，v27→v28 不为历史 Run 补造停止原因。JVM `424/424`、仅 Redmi instrumentation `143/143` 通过；确定性 `QUOTA(10)` 映射和双表持久化已验收，但尚无自然 Android 系统停止样本。
63. 已完成：真实 WorkManager 应用取消原因与用户停止优先级。Redmi Android 14 运行中 Worker 经 `cancelWorkById()` 后实际报告 `CANCELLED_BY_APP(1)`；生产策略映射通过。Room 契约确认 `STOP_REQUESTED` 已存在时保留用户原因并忽略后到机制码，Task/Workflow 同事务取消且不伪造系统停止字段。完整门禁为 JVM `424/424`、仅 Redmi instrumentation `145/145`。
64. 已完成：Android 进程退出观察账本。前台启动与生产 Worker 冷启动读取 Android 11+ `ApplicationExitInfo`，Worker 先登记当前进程所有权；Room v29 独立保存最多 30 条稳定数值记录，不关联 Task/Run，不保存 description、trace 或状态摘要。只有 `LOW_MEMORY` 为直接 LMK，报告能力缺失时的 `SIGNALED + SIGKILL` 仅为候选，用户/应用/包维护保持受控分类。旁路失败不阻断主流程，协程取消继续传播。JVM `431/431`、Redmi 聚焦 `5/5`、完整 instrumentation `149/149` 通过；受控 `force-stop` 被正确记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`。
65. 已完成：进程退出观察只读诊断 UI。设置页只调用 `latest()` 显示最近 30 条 Room v29 记录，以稳定中文标签区分直接 LMK、候选、故障、系统资源、受控维护和未归因证据；刷新不触发采集，不增加 Task/Run/Workflow 归因。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；受控 `force-stop` 页面显示与数据库一致，刷新前后计数不变。
66. 已完成：普通聊天请求上下文准备迁出 ViewModel。独立 preparer 统一消息资格、知识生命周期、摘要失效/复用、最近窗口、增量摘要、可信 Agent 历史和 Responses 附件投影；协程取消不再被兜底吞掉。ViewModel 减少 215 行，Room/协议/UI 不变；新增聚焦 JVM `8/8`、完整 JVM `439/439`、仅 Redmi instrumentation `152/152` 通过。

67. 已完成：普通聊天网络发送编排迁出 ViewModel。独立 coordinator 统一发送前 Room 快照、上下文准备、模型请求、流式增量和终态事件顺序；取消先收敛 UI 再继续传播，持久化失败不触发模型。`sendMessage()` 从约 190 行收敛到约 104 行，Compose 投影仍留在 ViewModel；Room/协议/UI/Agent/Workflow 不变。新增聚焦 JVM `3/3`、完整 JVM `442/442`、仅 Redmi instrumentation `152/152` 通过。

68. 已完成：会话状态投影规则迁出 ViewModel。独立 policy 统一第一条 `role=user` 消息标题（空白保持“新会话”）、重复空会话折叠、时间戳、摘要元数据继承、blank ID 和非当前更新隔离；ViewModel 从 4272 行降到 4189 行，异步 Room/Job/删除事务与 Compose 副作用仍保留。Room/协议/UI/Agent/Workflow 不变。六轮 TDD 后聚焦 JVM `6/6`、完整 JVM `448/448`、仅 Redmi instrumentation `152/152` 通过。

69. 已完成：会话保存协调迁出 ViewModel。独立 coordinator 统一 latest-save Job、Room 单写者、发送前等待和显式删除意图代次；旧事务不可取消时仍保证最新快照最后写入，失败、重标记或旧失败回调晚到时不误确认或回滚新删除。ViewModel 从 4189 行降到 4183 行，异步加载、删除 UI 与 Compose 副作用仍保留。Room/附件 BLOB/协议/UI/Agent/Workflow 不变。八轮 TDD 后聚焦 JVM `8/8`、完整 JVM `456/456`、仅 Redmi instrumentation `152/152` 通过。

70. 已完成：异步会话加载协调迁出 ViewModel。独立 coordinator 统一 latest-load Job、选择代次和稳定事件，底层查询在取消后迟到成功或失败均不会覆盖最新选择、删除回滚或失败提示；新 Job 先登记再派发 Loading，回调重入也保持最新 Job。ViewModel 继续投影完整消息/轻量会话、选择下一会话、删除回滚和保存；Room/附件 BLOB/协议/UI/Agent/Workflow 不变。四轮 TDD 后聚焦 JVM `4/4`、完整 JVM `460/460`、仅 Redmi instrumentation `152/152` 通过。

71. 已完成：会话加载 UI 投影规则迁出，统一 Loading/Loaded/Failed 与附件轻量化，完整 JVM `463/463`、Redmi `152/152` 通过。
72. 已完成：会话新建和删除后的选择规则迁出，完整 JVM `468/468`、Redmi `152/152` 通过。
73. 已完成：会话选择与删除副作用协调迁出，固定取消加载、删除代次、运行态清理和选择/加载顺序，完整 JVM `472/472`、Redmi `152/152` 通过。
74. 已完成：网络请求根设置改为独立入口页，User-Agent 输入区至少 5 行并提供复制/清空/恢复默认，Redmi 完整 `153/153` 通过。
75. 已完成：`/agent` Responses USER 单一 Image/Document 输入、审批恢复与关联新 Run 附件重建，完整 JVM `477/477`、Redmi `153/153` 通过。
76. 已完成：Room v30 Embedding 检索 v1，按 Provider/模型隔离 Float32 向量，语义与词法稳定融合并在失败时回退。
77. 已完成：当前 Provider/模型 Embedding 索引摘要与显式单文档重建，失败、停用和 revision 竞态不破坏旧索引。
78. 已完成：固定语料 Recall@5、MRR、负例准确率、重复排序稳定率与实际 Embedding 状态诊断。
79. 已完成：真实 Provider 语义检索端到端，英文查询在词法零命中时首位召回中文目标文档。
80. 已完成：10 篇真实 Embedding 有界语料三轮质量、耗时、向量与内存基线；无关查询仍返回近邻，确认相关性拒绝优先于 ANN。
81. 已完成：Room v31 保存 top1、top2、margin 和候选数 shadow 观测，知识管理页展示但生产检索不拒绝。
82. 已完成：20 篇成对语料、三桶各 10 条、每条重复两次并在 Redmi 三个独立进程校准；同集候选保持 shadow。下一阶段冻结 Provider/模型候选后用独立 holdout 验证，不直接上线生产拒绝。
83. 已完成验证并否决候选：冻结 Stage 82 的 Provider/模型、阈值和校准集身份，以全新 holdout 三轮复验；排序指标全为 `1.0`，但正例接纳率仅 `0.80`，低于预注册 `0.90`，因此不进入生产拒绝且不使用 holdout 回调阈值。
84. 已完成：Room v32 保存候选均值、总体标准差和 top1 z-score shadow 观测；退休 holdout 显示正例与近负例 z-score 仍重叠，不启用生产拒绝。

85. 已完成：两套全新正式身份 calibration/validation 的七类特征族比较，校准与验证数据各 24 条，Recall@5 均为 `1.0`，但七类特征族无一达到预注册相关性标准。
86. 已完成：正式门禁否决被编码为成功的 Redmi 显式验收（`OK (1 test)`），不降低阈值、不回调 validation、不升级 `VERIFIED` 或进入 final holdout。
87. 已完成：模型漂移身份回归，Provider、模型、配置指纹和数据集版本任一漂移均在比较前 fail-closed。
88. 已完成：完整本地门禁更新为 JVM `535/535`、Lint、Debug/AndroidTest APK；仅 Redmi 默认 instrumentation XML 为 `185` 条（`176 passed / 9 skipped / 0 failed`）。
89. 已完成：新的跨主题归一化策略只使用 `top1-均值` 与 `margin/标准差`，绑定正式身份和独立数据集，JVM 契约覆盖冻结阈值、身份漂移、缺桶、标签漂移与零方差拒绝。
90. 已完成：两套各 24 条 Redmi 观测的 Recall@5 均为 `1.0`，但三种归一化特征族通过数仍为 `0`；最优族近负例拒绝只有 `0.75`，稳定得到预注册门禁否决。
91. 已完成：生产身份仍为 `CANDIDATE`，生产相关性拒绝与答案路径接入保持关闭；不再调同一检索分数，转向 answerability/重排证据。
92. 已完成：严格答案可回答性策略、离线 `7/7` 契约和 Redmi `12 + 12` 真实 `gpt-5.5` shadow 观测；网络/解析失败为 `0`，两类特征族通过、覆盖率特征族未通过，生产 enforcement 继续关闭。
93. 已完成：答案可回答性 shadow 提示与引用共存契约新增 `5/5`，合计聚焦 JVM `12/12`；Redmi 默认完整 `OK (188 tests)` 已覆盖提示/引用共存 UI；该阶段当时生产消息流仍未绑定。
94. 已完成：真实消息流只读 answerability shadow 绑定；候选只来自可信 `knowledge.search`，强类型数据集身份、Run/观测一致性、引用快照和保守 `UNKNOWN` 契约均已验收；该阶段当时尚未接入生产消息流。
95. 已完成：线上无标签 measurement 与离线带标签 observation 分离；默认关闭、前台直接来源、两次有界尝试、取消透传、Judge 身份漂移、隐私指纹和 Store 失败隔离均已冻结，完整 JVM `578/578`。
96. 已完成：默认关闭的生产 Judge adapter、冻结身份门禁、答案保存后异步 caller、独立设置开关和进程内 `messageId` notice 已接入；固定 `store=null / persistenceMode=NONE`，保存失败/取消与 Judge 失败均旁路跳过，完整 JVM `593/593`。
97. 已完成：固定上限的进程内样本遥测、Judge 成本/重试失败分布和 notice 生命周期统计已接入；Redmi 真实前台样本 `1` 条完成，完整 JVM `600/600`、默认 instrumentation `OK (191 tests)`，Room Store 与 enforcement 继续关闭。
98. 已完成：Redmi 同一进程累计 Shadow 样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次形成直接回答 `2`、部分回答 `2`；关闭并删除测试会话后 notice 有效 `4 -> 1`、裁剪 `0 -> 3`，Room Store 与 enforcement 继续关闭。
99. 已完成：新的 Redmi 真实使用窗口新增 `3` 条有效 Judge 样本，直接回答 `2`、部分回答 `1`，无取消、异常或自然 Judge 失败；宽查询导致的无候选 Agent 失败未进入 Shadow，清理后 notice 有效 `3 -> 0`、裁剪 `0 -> 3`。
100. 已完成：Android 系统分享入口 v1。单文本/单图片只进入新会话草稿，冲突需用户确认，冷/热启动和重建行为明确；不自动发送、不信任来源、不扩展工具权或后台能力。
101. 持续观察：首个间隔真实使用窗口已新增 `1` 条直接回答，已记录窗口人工合计为样本 `10`、有效 Judge `8`；Shadow 继续保持低频旁路，不在同一窗口堆样本，只有出现自然网络/协议/认证失败或明显成本异常后，才重新评审最小化持久化。
102. 仍后置：多项/任意文件分享与后台自动处理、设备工具进入 Workflow/后台自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型。

后续若继续相关性工作，必须先重新注册能够区分“同主题”和“文档真正回答问题”的 answerability/重排设计，不能用第 90 或 91 阶段 validation 回调阈值或降低标准；在新的独立证据达到预注册标准前，生产拒绝与答案路径继续关闭。第 97 至 101 项已记录窗口人工合计 Shadow 样本 `10`、其中有效 Judge `8`：直接回答 `5`、部分回答 `3`，另有两条无候选跳过；没有自然 Judge 网络/协议/认证失败。无候选跳过、未进入 Shadow 的预算耗尽或工具步数耗尽不得用来扩权。该合计不是跨进程持久化，后续只在间隔开的真实使用窗口低频观察。同时只在真实使用中继续积累 Android 自主 LMK、系统配额、超时或自然回收记录，并以 Room v32 中自 v29 延续的独立账本及只读诊断页核对。没有新自然样本时不再增加模拟回收代码，不把 `force-stop`、应用取消、安装、instrumentation、Doze、trim-memory 或 `kill -9` 包装成自然系统证据。不尝试恢复无法证明的旧执行栈。Daily/Weekly 继续使用非精确定时语义并记录计划/实际时间。Foreground Service 只提高系统存活概率，不代表旧执行栈可以安全恢复；当前熄屏 244.236 秒样本和受控取消仍不支持预先引入。设备工具继续禁止进入 Workflow 或后台自动化；精确定时、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。
