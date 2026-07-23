# 产品需求

## 第 78 阶段验证边界

Embedding 检索质量必须使用固定语料和稳定指标验收：排名先按文档 ID 去重再截取 K，正例计算 Recall@K 与 MRR，负例只在返回为空时计为正确，多次执行按完整排名一致性计算稳定率；空语料、单一正/负语料、K 外命中、负例误命中、排序漂移和非法用例都要有 JVM 契约。项目长期 `docs/` 作为固定语料，5 个正例与 1 个负例各执行两次，门禁为 Recall@5 `1.0`、MRR `>= 0.8`、负例准确率 `1.0`、稳定率 `1.0`。知识管理页必须显示本次检索实际使用的 Embedding 状态，Provider/模型为空时不得渲染多余分隔符，零命中仍保留审计与回退原因。真实 Provider 只在同步模型列表明确包含 Embedding 模型时调用 `/embeddings`；本轮 Redmi 兜底 Provider 同步成功但没有该模型，因此仅验证配置恢复与词法兜底，不宣称真实向量端点兼容。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个无显式参数的联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段验证边界

知识管理页必须为当前文档展示所有仍指向当前 revision 的 Embedding 索引摘要，包括 Provider、模型、维度和分块数；没有索引时明确提示词法兜底。启用文档可手动重建当前选中 Provider/模型的索引，升级前旧文档不得因缺少导入时向量而永久停留在 `NO_INDEX`。Provider 请求、数量和维度校验全部成功后才允许进入事务；事务内必须再次核对 document revision、enabled 和 chunk 身份，并且只替换当前 `providerId + model` 空间。切换 Provider/模型后既有空间不得被覆盖，重复重建某一空间不得删除其他空间；失败、超时、停用和并发替换不得先删除已有向量。替换正文继续清理全部旧 revision 空间，删除继续清理全部索引。Room 保持 v30；当前只支持前台单文档显式重建，不承诺 ANN、自动后台批量重建或规模化性能。完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和仅 Redmi `164/164` instrumentation 已通过。

## 第 76 阶段验证边界

知识库语义检索只在当前 Provider 的模型列表包含 Embedding 模型时启用。请求配置必须保留 Provider 身份和 Embedding 模型名；向量按 `providerId + model` 隔离，不能把不同 Provider 或模型的 BLOB 混用。Room v30 新增向量表并保留历史检索的 `LEXICAL_ONLY` 默认事实。索引建立最长 30 秒，查询向量最长 2 秒；Provider 不可用、超时、无索引或维度不一致时必须回退原 FTS4+LIKE，且每次检索审计记录最终状态。语义候选与 FTS/LIKE 以稳定 RRF 融合，结果交付前必须再次核对当前文档启用状态和 revision。当前只验证有限规模内存扫描，不承诺 ANN、后台增量建索引或任意 Provider 的 Embedding 兼容性；完整 JVM、Lint、Debug/AndroidTest APK 和仅 Redmi `158/158` instrumentation 已通过。

## 第 75 阶段验证边界

`/agent` 附件 v1 仅支持 Responses API。单条 USER 消息最多携带一种经既有 8 MB、MIME、签名、PDF/OpenXML 和 UTF-8 策略校验的 Image 或 Document；Chat Completions、Image+Document 混合、持久化重复附件、assistant/Tool 伪造附件必须 fail-closed。初次发送必须先把附件与正文作为 USER MessagePart 提交，再建立 Run；同一 Run 的每轮规划请求继续携带附件，模型总结请求、`VerifiedAgentContext`、ToolResult 和 Agent 输出不得携带附件。审批恢复和任务中心重试必须从已持久化 USER MessagePart 重建附件，重试创建新 Run 且旧 Run 不变；Workflow/后台 Agent 暂无附件入口。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过；图片/文档真实 E2E 的 `notes.create` 回执均为 `PASSED`，直接 `complete` 的无工具 Run 被运行时拒绝。

## 第 73 阶段验证边界

会话新建、历史选择和删除后的副作用顺序必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSelectionCoordinator`。协调器只组合现有 `ConversationSessionPolicy`、`ConversationPersistenceCoordinator` 与 `ConversationLoadCoordinator`：新建先取消旧加载再发布即时选择；删除先取消旧加载、标记版本化删除意图并发布运行态清理事件，再按计划即时选择或完整加载；只有当前加载代次失败时，必须先回滚该请求捕获的删除代次，再发布 Failed。旧失败不得清除同 ID 的新删除意图，删最后会话仍保留空占位，删除后有剩余会话仍加载 `updatedAt` 最新项。`ConversationLoadRequest` 不得继续承载持久化回滚意图；ViewModel 只负责 Agent Run/审批 Map、UI 投影和成功后的选择保存。Room v29、附件 BLOB、Provider 协议、UI、`/agent` 与 Workflow 不变。当前已验证聚焦 `4/4`、第 68 至 73 阶段会话组合 `30/30` 和完整 ViewModel 手工编译；后续标准门禁已补齐，完整 JVM `472/472`、Lint、Debug/AndroidTest APK 与仅 Redmi 执行的 instrumentation `153/153` 均通过。

## 第 72 阶段验证边界

新建会话与删除后的选择规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`。当前选中会话已经为空时必须幂等复用且不额外折叠其他占位；当前会话有内容时优先复用 `updatedAt` 最新空占位并折叠其余空占位，没有空占位才按同一注入时钟创建 `conversation-$now`。删除后有剩余会话必须返回 `Load` 计划并选择 `updatedAt` 最新项，删至空列表必须返回带新占位的 `Immediate` 计划。计划必须显式区分复用既有会话与新建占位，后者不得因时间戳 ID 碰撞恢复旧 Agent Run 或审批。取消加载、标记删除意图、清理运行态 Map、完整消息加载、失败回滚和选择保存继续留在 ViewModel；加载与持久化 coordinator 不重复实现选择规则。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `5/5`、完整 JVM `468/468`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 71 阶段验证边界

会话加载事件的纯 UI 投影必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadProjectionPolicy`。Loading 只开启消息加载并清除旧提示；Loaded 必须把请求会话、标题、摘要、完整当前消息、Agent Run、待审批状态和结果在同一次状态替换中切换，同时从所有非当前会话索引移除 Image/Document BLOB；Failed 只关闭加载并保留真实错误消息或稳定兜底。删除意图回滚必须继续发生在失败投影之前，成功后的选择保存和 Agent/审批映射读取继续留在 ViewModel，`ConversationLoadCoordinator` 的 Job/代次门禁不重复实现。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `463/463`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 70 阶段验证边界

异步会话加载 Job、取消与迟到结果隔离必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadCoordinator`。每次选择生成单调代次并取消旧加载，且必须先登记新 Job 再派发可重入的 Loading；底层 Room 查询或 loader 即使在取消后仍返回或失败，也只能由当前代次发出 Loaded/Failed，不能覆盖当前会话、删除回滚或错误提示。当前代次成功后 ViewModel 仍负责把完整消息和轻量会话列表原子投影到 Compose，并继续触发选择保存；当前代次失败时才允许回滚该次删除意图并显示读取失败。删除后的下一会话选择、空会话兜底、Compose 副作用、`ConversationRepository` 的显式删除事务与附件 BLOB 生命周期不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `4/4`、完整 JVM `460/460`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 69 阶段验证边界

会话保存 Job、Room 写入串行化和显式删除意图必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationPersistenceCoordinator`。快速连续保存只保留最新待提交快照；旧事务若已进入不可取消提交区，最新快照必须等待并最后写入。普通聊天发送必须先取消并等待旧保存，再捕获当前删除意图并通过同一单写者提交用户消息与附件，成功后才能准备上下文和请求模型。删除 ID 只有在包含该代意图的事务成功后才能确认；失败、取消或事务期间同 ID 被重新标记时必须保留新意图，读取失败只回滚该次切换捕获的删除代次，不能清除同 ID 的后续删除意图。`ConversationRepository` 的显式删除事务、后台 Workflow 并发保护和附件 BLOB 保留逻辑不变；异步会话加载、删除后的 UI 切换/失败提示和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `8/8`、完整 JVM `456/456`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 68 阶段验证边界

会话状态投影的纯规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`：第一条 `role=user` 消息正文生成标题并在 trim 后限制 18 字符，正文空白时保持“新会话”且不向后寻找下一条用户消息；全部真实会话必须保留，多个空占位只保留 preferred 或最新一个；更新既有会话必须保留 `createdAt`、推进 `updatedAt`，默认继承摘要边界、更新时间与摘要模型；非当前会话的迟到更新只能修改会话列表，不能污染当前 UI；blank ID 使用同一次注入时钟生成稳定 ID，并沿用既有非当前隔离语义。异步 Room 加载、保存 Job、删除事务和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `6/6`、完整 JVM `448/448`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 67 阶段验证边界

普通聊天的发送前持久化、请求上下文准备、模型网络调用、流式增量和成功/取消/失败终态必须由独立 `ConversationSendCoordinator` 按稳定顺序编排。ViewModel 只负责入口校验、Compose 状态投影、30ms 流式节流和发送 Job 生命周期，不得复制第二套网络 try/catch 状态机。用户停止时必须先用最近已准备上下文收敛部分 assistant，再继续传播 `CancellationException` 以取消底层请求；Room 或网络普通异常必须发出带最近可证明上下文的失败事件，持久化失败时不得继续准备上下文或调用模型。Room v29、Provider 协议、消息格式、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `442/442`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 66 阶段验证边界

普通聊天的请求上下文准备必须从 `XiaoLingViewModel` 迁入可独立测试的应用组件。该组件统一决定失败/取消 assistant 是否进入上下文、知识引用失效时是否移除历史 Agent 消息并废弃旧摘要、最近 16 条窗口、窗口外可信 Agent 结果上限、摘要增量边界与复用元数据，以及 Responses 用户附件是否进入最近窗口。ViewModel 只提供当前提示词设置、Room 知识引用核验和摘要网络实现；不能复制第二套上下文规则。知识核验或摘要调用的协程取消必须传播，普通异常才允许保守移除知识或使用本地摘要兜底。Room Schema、请求协议、摘要长度和 UI 不变。聚焦 JVM `8/8`、完整 JVM `439/439`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 65 阶段验证边界

用户必须能在应用内只读查看最近 30 条进程退出观察，不依赖 ADB。页面刷新只能查询 Room v29 已有记录，不能再次调用平台采集，也不能给记录增加 Agent Run、Workflow 或 ScheduledTask 关联。六类稳定证据必须明确区分，尤其不能把 `LOW_MEMORY_CANDIDATE`、`CONTROLLED_OR_MAINTENANCE` 或 `UNATTRIBUTED` 呈现为自然 LMK。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；真实受控 `force-stop` 在页面显示为 `USER_REQUESTED / 受控退出或包维护`，刷新前后数据库均为 1 条。该只读控制面不改变普通 WorkManager、fail-closed 恢复、设备工具前台限制与 Foreground Service 后置策略。

## 第 64 阶段验证边界

Android 11+ 的系统进程退出事实已进入独立、有限、隐私安全的 Room v29 账本：前台启动与后台 Worker 冷启动均可补采，Worker 必须先登记当前进程所有权。退出记录不得凭时间邻近关联 Task/Run，不保存 description、trace 或进程状态摘要，稳定去重后最多保留 30 条。只有 `REASON_LOW_MEMORY` 是直接 LMK 证据；设备无法直接报告 LMK 时的 `REASON_SIGNALED + SIGKILL` 只能标记为候选，用户/应用取消和包维护必须保持受控分类。旁路采集失败不能阻断主流程，但不得吞掉协程取消。Redmi 聚焦 `5/5`、完整 instrumentation `149/149`、JVM `431/431` 通过；受控 `force-stop` 的正式记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 63 阶段验证边界

Redmi Android 14 已用真实 WorkManager 验证应用取消运行中 Worker 会报告 `CANCELLED_BY_APP(1)`，并由生产停止原因策略映射为稳定分类。用户可见停止仍以先落库的 `STOP_REQUESTED` 和用户原因作为业务事实；随后 WorkManager 的应用取消码只说明执行机制，不得覆盖用户意图或写入独立系统停止原因。完整 Redmi instrumentation `145/145`、JVM `424/424` 通过。本阶段不属于自然 LMK、系统配额或超时证据，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 62 阶段验证边界

后台 Worker 的系统停止原因已纳入可审计执行结果：Android 12+ 的 WorkManager 停止码映射为稳定分类，按同一 Room 事务写入 ScheduledTask 与 WorkflowRun，并在任务中心展示；旧 Android、`NOT_STOPPED` 和未知码保持通用或未知结论，不把历史自由文本反推成具体原因。Room v27→v28 迁移只新增可空字段。Redmi 完整 instrumentation `143/143`、JVM `424/424` 通过；本阶段未取得自然停止样本，不改变普通 WorkManager、fail-closed 恢复和 Foreground Service 后置策略。

## 第 61 阶段验证边界

Redmi 熄屏状态下（`mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`），JobScheduler 冷启动生产 Worker，在计划时间后 `159.479s` 启动，并完成 `244.236s` 的 8 步复合 SAFE Workflow。8 个 AgentRun、32/32 ToolResult 与验证事件全部成功；每个 Run 的 `consumedMs` 预算快照无回退，最大值 `18.283s–44.856s`，无模型失败。该结果扩大了普通 WorkManager 在熄屏场景的可信度，但不承诺自然系统回收、任意长度存活或 Foreground Service 必要性。

## 第 60 阶段验证边界

Redmi 的一次性入队 Probe 在 `0.255s` 后退出，原应用 PID 随 instrumentation 结束消失；JobScheduler 随后冷启动新 PID，由生产 Worker 独立完成 `204.977s` 的 8 步复合 SAFE Workflow。WorkRequest、ScheduledTask 和单一 WorkflowRun 关联完整，8 个 AgentRun、32/32 ToolResult 与 32 条验证事件全部成功；每个 Run 的 11 条 `consumedMs` 预算快照无回退，`llmFailures=0`。这证明普通 WorkManager 可以承载当前规模的真实冷启动后台链，不证明 Android 会保证任意长任务存活，也没有新增自然 LMK 或 Foreground Service 需求证据。

## 第 59 阶段验证边界

Redmi `wsvwypiz7xwslvl7` 的正式 WorkManager 已完成 `229.416s` 的 8 步复合 SAFE Workflow：每步依次调用 3 个应用内只读工具，24/24 ToolResult 均成功并通过验证，Task/Workflow/8 个 AgentRun 全部完成，单一 ScheduledTask 未复制执行；记录 72 条预算更新、24 条 `tool.verify`，没有 `llm.request.failed`。`ApplicationExitInfo` 为 `supported=true / lowMemory=0`，14 条历史退出均为 instrumentation 或安装停止，仍没有 Android 自主 LMK。该样本扩大了普通 WorkManager 的真实耗时证据，但不承诺任意长度的系统存活，也不触发 Foreground Service 预先引入。

## 第 58 阶段验证边界

第 58 阶段早期两次真实后台 Workflow 因 Redmi TLS 握手失败在约 4 至 6 秒收敛，设备自带 `curl` 可独立复现 `BoringSSL SSL_ERROR_SYSCALL`；这两次不是成功任务耗时，也不是 Android 自主回收证据。系统没有通过关闭证书校验或预先引入 Foreground Service 绕过该问题，网络恢复后的成功样本见下一段。

网络恢复后第 58 阶段已取得一条成功长任务样本：普通 WorkManager 在 Redmi 上以 `92.667s` 完成 8 步 SAFE Workflow，8 个 Agent Run 和工具验证全部成功，预算快照单调且未复制 Run；历史退出中 `lowMemoryExits=0`，因此仍不能宣称 Android 自主 LMK 或据此预先引入 Foreground Service。前述 TLS 失败仍保留为网络阻断样本。

## 产品定位

小灵是一款运行在 Android 手机上的个人 Agent。它不是单纯的模型聊天客户端，也不是默认拥有手机全部权限的自动化脚本。它应当先理解用户目标，再在明确授权的能力范围内调用工具、记录过程、验证结果，并把控制权留给用户。

## 核心用户价值

- 一个长期可用的个人入口：对话、记忆、任务和工具在同一应用内协作。
- 一个可控的执行者：每次操作可解释、可停止、可确认、可追溯。
- 一个开放的模型客户端：支持用户自己的 OpenAI-compatible 服务，不绑定单一模型厂商。
- 一个逐步扩展的移动 Agent：先做应用内安全工具，再扩展日程、通知、文件和跨应用自动化。

## 产品原则

1. **聊天与执行分流**：普通问答走快速路径；只有需要工具的任务才进入 Agent Runtime。
2. **能力来自注册表**：模型只能调用代码注册且当前启用的工具，不能自行声明权限或执行任意命令。
3. **风险由代码决定**：工具风险、Android 权限、确认要求和结果验证规则由应用定义，不能信任模型给出的风险等级。
4. **动作不等于完成**：写入、发送、删除、修改等操作必须通过工具结果或重新读取状态验证。
5. **记忆可见可控**：用户可以查看、编辑、删除和禁用长期记忆；记忆必须保留来源和更新时间。
6. **本地优先**：会话、运行记录、记忆和配置默认保存在设备本地；密钥继续使用 Android Keystore 保护。
7. **渐进授权**：首次使用不要求一次性开放全部权限，只有启用具体能力时才请求对应权限。

## 当前已交付能力

- 多 Provider、上游模型同步和模型选择。
- 模型请求 User-Agent 可在设置页按设备自定义；“网络请求”在设置根页与其他设置项保持相同的入口卡片样式，点击后进入独立页面编辑，不在根页行内修改。编辑区默认至少显示 5 行，右下角提供复制和清空操作，并保留恢复默认入口；默认值为 `Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`，空白配置自动回退默认值，并统一用于模型列表、普通对话、Agent 和后台 Workflow 请求。
- Chat Completions、Responses API 和 SSE 流式输出；Responses 输入支持保留 system/user/assistant 边界的消息，以及通过 `call_id` 关联的 `function_call / function_call_output` typed Items。普通对话可显式开启供应商推理摘要；只有 Responses 请求会发送 `reasoning.summary=auto`，关闭时和 Chat Completions 均不发送。
- Text/Reasoning/Image/Document/Tool 消息 parts：Room 独立保存 part ID、消息内顺序、类型、正文、供应商摘要来源与 item 身份、附件 MIME/文件名/BLOB/detail、文档提取文本/PDF 页数、工具参数、结果、成功状态、验证状态和记忆引用。Image/Document 仅允许 USER 来源，且单条消息最多携带一种附件。图片支持 PNG/JPEG/WEBP；Document 支持 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX、XLSX，单文件最大 8 MB。PDF 必须由平台解析且最多 50 页；文本必须是有效 UTF-8 且最多 200,000 字符；DOCX/PPTX/XLSX 必须是未加密、非分卷、非 ZIP64 的 ZIP/OPC 包，条目不超过 4,096 个、声明及实际流式展开总量都不超过 64 MB，并包含真实可读的 `[Content_Types].xml` 与对应 Word/PowerPoint/Excel 根入口。OpenXML 只接受匹配格式的 MIME、空 MIME 或通用 ZIP/二进制 MIME。Responses 分别映射为 `input_image` / `input_file` Data URL；Chat Completions 明确拒绝附件，`/agent` 仅允许 Responses USER 单一附件并只送入规划请求。Reasoning 只接受 Responses `reasoning.summary[].summary_text`，不读取或展示原始 `reasoning_text/reasoning_content/encrypted_content`；附件 Base64 与原始/加密推理在 debug 日志中必须脱敏。Agent Tool part 必须由应用可信上下文投影，Image/Document/Reasoning 均不进入 `VerifiedAgentContext`、不能生成或替代 Tool。附件 BLOB 只为当前会话加载，切换会话必须在完整 parts 就绪后原子更新界面；轻量快照保存不得清空未加载 BLOB，网络请求必须等待用户消息与附件事务提交。前台快照保存只能增量 upsert，用户删除必须显式传递会话 ID，并在事务前过滤删除集合，以保留并发后台 Workflow 证据且防止陈旧快照复活已删会话。
- 本地知识库、管理 UI、Agent 检索与答案引用：第一版只导入 TXT、Markdown、JSON 和 CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符，统一移除 BOM、规范换行、拒绝空白文档与二进制空字符，并对规范全文计算 SHA-256。文档身份与 revision 分离；确定性分块优先在段落边界结束，默认每块 1600 字符、固定最多 200 字符重叠，offset 指向 Room 内规范全文且不得切断 UTF-16 代理对。Room v30 保存全文、chunks、FTS4/LIKE 索引、Embedding 向量、检索审计和 Tool/Message 知识引用；检索同时保留中文与字面通配符安全的 `LIKE` 兜底。设置页支持 SAF 导入、轻量摘要列表、详情、启停、替换、删除、显式检索预览和当前 Provider/模型的 Embedding 重建；列表不得读取完整正文，详情只从 SQLite 投影并安全截取最多 4,000 个 UTF-16 单元。`knowledge.search` 必须是 SAFE、支持后台、`query` 1 至 200 字符、`limit` 默认 3 且最大 5，并把 conversation/run/retrieval/document/revision/chunk/offset 身份写入审计。Agent 回复下方的独立引用区域只允许从结构化 MessagePart/VerifiedAgentContext 投影，不解析模型自由文本；默认折叠，展开后展示文档名、revision、chunk 和 `[startOffset, endOffset)`，文档仍存在时可跳转知识库详情。引用状态查询必须分批控制 SQLite 绑定参数数量，取消的旧核验任务不得把新状态覆盖为失败；无法核验时显示独立未知状态，不得误报为引用失效。替换必须在同一事务内递增 revision、更新 parser/hash、删除旧 chunks/FTS 并生成新 chunk ID；禁用、替换或删除后立即退出检索。历史 Run/消息审计不回写，启用的新 revision 标记“历史版本”，停用、删除或证据漂移标记“当前不可用”，且失效知识消息和可能包含旧片段的摘要不得再次进入模型上下文。Embedding 检索、显式单文档重建和多 Provider/模型索引空间共存已接入；规模化 ANN、自动后台批量重建继续后置。
- 设备 Agent 观察与有限动作层：应用内独立开关默认关闭，并与系统 Accessibility 授权分别生效；健康状态必须区分应用关闭、未授权、已授权但服务断连和可用。`device.snapshot / open_app / back / home / tap_ref / type_text / swipe` 仅允许前台直接 `/agent`，Workflow、后台执行、开关关闭和缺少 Run Context 时既不进入模型工具清单，也在 Executor 再次拒绝。快照最多返回 200 个可见有效节点和 4,000 个文本字符，文本截断不得切断 UTF-16 代理对；可操作且未脱敏节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、节点路径和指纹，页面变化、过期、关闭开关或任一失败后立即失效，不回退坐标。`open_app / tap_ref / type_text` 必须审批，`back / home / swipe` 按 SAFE 执行；打开应用仅允许小灵、系统计算器、时钟和系统设置，输入限制为 500 字符并拒绝密码、验证码、密钥、账号及身份信息。每次动作后必须重新 snapshot，瞬时空窗口或读取期页面变化只允许短时有界重试，最终只有 `verified=true` 才能通过 Executor 验证。密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref；支付/高敏身份验证窗口及已知密码管理器、Authenticator、钱包/银行应用整窗拒绝。AccessibilityService 只使用标准节点动作与系统返回/主页，不执行坐标手势或截图；Release 不包含可外部触发的诊断 Receiver/探针 Activity。既有 Profile/Skill 不因新工具自动扩权。
- 多会话、本地保存、会话摘要压缩和 Markdown 渲染；普通聊天上下文筛选、知识生命周期核验、最近窗口、增量摘要与请求消息构造已统一迁入 `ConversationRequestContextPreparer`，网络发送顺序由 `ConversationSendCoordinator` 编排，会话标题、空占位折叠、时间戳、摘要元数据继承、非当前更新隔离、新建会话和删除后选择计划由 `ConversationSessionPolicy` 统一投影，latest-save Job、Room 单写者和显式删除意图由 `ConversationPersistenceCoordinator` 协调，异步加载 Job/代次与加载 UI 投影分别由 `ConversationLoadCoordinator` 和 `ConversationLoadProjectionPolicy` 承担，选择/删除副作用顺序由 `ConversationSelectionCoordinator` 组合。ViewModel 继续负责依赖注入、运行态 Map、UI 投影、成功保存和其他 Compose 副作用。
- Provider、模型、请求模式、流式状态、首字耗时和总耗时等消息元数据。
- API Key 的 Android Keystore + AES-GCM 加密存储。
- 常见网络、鉴权、限流、模型和响应解析错误分类。
- `/agent <目标>` 顺序多步执行入口，以及 `AgentRun / AgentStep / ApprovalRequest / RunEvent` 可审计运行链路。
- Agent Profile v1：用户可以创建、编辑、选择和删除多个 Agent；每个 Profile 固定名称、标识、Provider、模型、Chat Completions/Responses 模式、系统提示词、当前会话上下文策略、工具白名单、Skill 白名单和长期记忆开关。至少保留一个 Profile，仍被 Agent 使用的 Provider 或模型不能直接删除/停用。
- 新 Run 必须写入唯一 `agent.profile.selected` typed event 并冻结完整 Profile 快照。Profile 系统提示词只能调整表达方式和授权工具内的任务偏好，不能覆盖 JSON 协议、工具白名单、风险、审批、Android 权限、后置验证和可信事实边界；Profile 工具白名单在 Registry 执行层强制生效，Skill 只能继续缩小工具面。
- 有界 Agent Runtime：同一 Run 最多 4 次工具调用，模型每轮返回继续调用或完成；具备模型与工具超时、整次 Run 超时、取消、完整输入 Schema/业务规则、重复调用检测，以及参数校验时、审批结束后执行前、工具返回后验证前的 Android 权限复检。任一检查点权限缺失都必须 fail-closed。AgentRun 一旦进入任一终态，迟到的模型、HTTP、工具或恢复写入不得覆盖状态、结果和错误证据，也不得新增/改写 Step、覆盖一次性 Approval 决定，或追加 RunEvent/Tool Ledger 成功事实。兼容模型把同一个工具名同时写入 `action/tool` 时可以归一化，不一致动作仍拒绝。
- 模型规划、工具和模型总结必须使用同一单调执行时钟累计 Run 预算；Step 剩余时间小于或等于单步上限时归因 Run timeout，否则归因 Step timeout。审批等待不消耗执行预算。新 Run 从零值快照开始，每个成功执行段后以 typed RunEvent 持久化累计值；审批及受限恢复必须继承原 Run 的总额和已消耗值。旧 Run 可从零值兼容起点继续，但快照缺失结构、越界、总额漂移、累计回退，或 ToolResult 已落库而后续预算快照缺失时必须拒绝恢复。
- 应用侧 Tool Registry 统一声明字符串/整数/数值/布尔类型、长度/范围/枚举、风险、确认、Android 权限、后台能力、超时和验证策略；Runtime 按前台/后台来源执行能力门禁，模型不能增加未知参数、修改工具风险或自行增加执行事实。
- 工具执行证据使用 `ToolExecutionReceipt` 记录 ToolCall ID、业务 operation ID、可选幂等键和提交状态，并把执行时的 `ToolReplaySafety` 声明快照随 `tool.result` typed metadata 持久化。Executor 提供的回执必须绑定当前 ToolCall；错配时 Runtime fail-closed。只有执行时快照和当前工具定义都显式声明 `IDEMPOTENT_BY_KEY`、回执状态为 `COMMITTED`、ToolCall 身份一致且幂等键存在时，证据策略才可判定已提交副作用可复用；旧事件缺少快照时默认 `RESTART_REQUIRED`。`notes.create` 使用 ToolCall ID 作为存储层唯一幂等键；`memory.remember` 使用独立 operation ledger 保存载荷与结果快照。两者同键同载荷返回原 operation，同键不同载荷必须拒绝且不覆盖旧数据。
- ToolCall/ToolResult 使用独立 Room Ledger 保存稳定调用 ID、参数、proposed/validated 事件锚点、结果、显式错误、耗时、Executor 回读状态、最终验证状态、记忆引用、重放声明和执行回执。RunEvent 与 Ledger 必须在同一事务写入；同一 ToolCall 身份或参数漂移时整笔回滚。迁移期 RunEvent 继续作为时间线事实源，v19 旧 Run 不根据可能缺失身份的历史事件补造 Ledger，也不因 Ledger 为空失去原有恢复能力。
- 第一批应用内工具：当前时间、会话列表与检索、本机笔记列表/检索/创建、长期记忆检索/写入，以及只读本地知识库检索。
- 声明式 Skill 按需加载与管理：内置和本地 Skill 统一进入 Room Catalog；本地 `schemaVersion=1` JSON 经过字段白名单、工具注册表、风险与 Android 权限一致性校验后才可导入，设置页支持查看、启停和删除本地 Skill。Run 审计固定所选 Skill 的 ID/版本，审批恢复不得因期间停用、删除或升版而扩大工具面。
- 多步骤 Workflow Ledger：用户可保存、编辑、启停和运行包含 1 至 8 个顺序 Agent 步骤的工作流；活动 Run 存在时禁止编辑，历史 Run 保留创建时的步骤定义、输入/输出快照、幂等键、触发来源、会话、关联 Agent Run、结果和失败原因。手动运行复用现有前台审批与验证链路，后台调度按相同顺序执行且不会绕过审批门禁；前台三步骤、后台三步骤和审批后继续下一步骤均已通过真实模型真机验收。
- Workflow 安全重试：`BLOCKED / FAILED / CANCELLED` Run 可创建带 `retryOfWorkflowRunId` 的新 Run；只复用来源 Run 连续成功前缀的输出，首个未完成步骤及后续步骤重新执行。已启动过的失败步骤重试前必须二次确认，旧 Run 和步骤快照保持不变；真机已确认来源失败 Run 不变、新 Run 正确关联来源且定义编辑不回写历史快照。
- Workflow 知识证据边界：涉及 `knowledge.search` 的步骤输出必须把正文和结构化引用作为同一版本化快照保存。前台、后台、审批恢复和关联新 Run 重试在准备下一步骤时，都必须重新核对当前文档启用状态、revision、名称、chunk sequence 和 offset；引用缺失、畸形或任一引用失效时不得把该步骤正文写入新 Agent Run 目标，但来源 Run 和步骤快照必须原样保留供审计。
- 一次性非精确定时：用户可为已启用 Workflow 创建或取消 1 分钟至 7 天的一次性计划；`ScheduledTask` 记录计划时间、实际启动时间、WorkRequest 和关联 Workflow Run。`RUNNING` 实例必须提供用户可见停止入口：Workflow 仍活动时先在 Room 原子写入持久中间态 `STOP_REQUESTED`，再取消目标 WorkRequest，并按 Task→Workflow→Agent 持久化链定向收敛。系统取消异常、即时 fallback 失败、Worker 未及时收敛和 Agent 尚未关联都不能丢失停止意图；`SCHEDULED→RUNNING` 抢占不能让同一次点击只取消调度而遗漏执行链。停止必须幂等，不影响其他 Run，不创建替代 Run；`STOP_REQUESTED` 下的迟到成功只能收敛为取消，Workflow/Task 终态必须在同一事务重新读取栅栏后原子结算，停止 fallback 也不得分两次写入这两个终态。若 Workflow 在停止事务前已经终态，必须保留该历史终态并在同一事务把半结算 Task 映射到一致状态，不得让通用 `STOP_REQUESTED→CANCELLED` 栅栏再次改写该映射、写入伪停止栅栏或覆盖历史结果。后台只允许显式开放的 SAFE 只读工具，需审批工具进入 `BLOCKED` 并通知用户以前台新 Run 继续；真机已验证触发前进程被回收后由 WorkManager 冷启动执行并收敛 Ledger。
- Daily/Weekly 周期规则：用户可按当前系统时区保存每日或每周墙上时间；每次只物化一个独立的 OneTime `ScheduledTask`，终态后再生成下一未来实例，不补跑错过的历史周期。`STOP_REQUESTED` 不是终态，旧实例完成停止重对账前不得物化下一实例。替换规则会取消旧待执行实例，停用规则或 Workflow 会同步取消系统队列；每次触发仍建立独立 Workflow/Agent Run，旧 Run 和历史实例保持不变。
- Workflow 进程终止对账：步骤结果已经事务落库而下一步骤尚未启动时，进程终止不得被当作普通业务失败通知或 WorkManager 自动重试；启动对账须保留已完成步骤和输出，旧 Run 收敛为 `FAILED`，用户创建关联新 Run 后只复用连续成功前缀，不能自动继续旧 Workflow 或复制 Agent Run。
- 设置页长期记忆管理：FTS4 + 中文子串兜底搜索、全部/启用/禁用筛选、内容/标签/类型/置信度编辑、置顶、启停、删除确认、跨进程撤销和来源会话/Run 跳转；禁用或删除后不再参与 Agent 检索。
- 默认关闭的候选记忆：成功轮次结束后只从明确陈述生成候选，由用户确认或忽略；API Key、token、密码、银行卡、身份证和手机号只记录敏感类别，不保存原值。
- 记忆治理：规范化相同事实复用旧记忆，同类型同主题的不同事实标记冲突并保留旧记录；`memory.remember` 同样执行敏感过滤和去重。
- 记忆引用审计：`memory.search` 的实际命中 ID 必须进入 RunEvent 和已验证 Agent 上下文；`/agent` 单次可关闭记忆召回，关闭后不能访问 `memory.search`。
- 对话内 Run 时间线和审批卡片，以及设置页 Agent 任务中心；任务中心支持全部/需确认/处理中/可重试/已完成筛选、完整 ToolResult、步骤、审批、结构化事件和失败任务重试。“需确认”只包含已结束、可重试且副作用证据要求确认的 Run，不混入仍在等待审批的活动 Run；确认时必须重新核对证据码，稳定后只创建关联新 Run。v20 新 Run 有独立工具账本时必须按调用优先展示账本中的 proposed→validated→result→verified，typed RunEvent 只用于一致性核对；账本完全为空且存在 typed 工具事件的旧 Run 才回退事件。旧结果或验证缺少 ToolCall ID 时必须标为“关联未知”，不得按工具名或时间顺序伪造调用关联。双源字段、身份或事件锚点不一致时必须显示审计告警，但不得自动修补旧 Run 或改变恢复结论。`memory.remember` 恢复失败必须展示稳定错误码、具体原因和建议动作；建议动作只能引导修复记忆状态后创建新 Run，不能暗示旧 Run 会继续。当前筛选范围会基于持久化审计数据展示 Run 数、终态成功率、平均耗时、非成功数、模型/工具调用数、模型总耗时、平均 TTFB、Prompt 字节、上游 Token usage 覆盖率和失败终态分布；单 Run 使用同一持久化口径。活动 Run 不进入质量或失败分母，上游未返回 usage 时必须显示未返回，不能补零。
- 失败、取消和预算耗尽 Run 可创建新 Run 重新执行；新 Run 通过 `retryOfRunId` 关联来源，旧 Run 保持不变。v20 非空工具账本必须作为副作用判断事实源：非 SAFE 调用只要结果成功，或执行回执为 `COMMITTED / UNKNOWN`，重试前都必须二次确认；账本缺锚点、字段漂移、事件链不完整等异常同样保守要求确认，不得回退旧事件。账本完全为空的旧 Run 才沿用 typed event 成功结果判断；仅停在 proposed/validated 且尚未执行的调用不因此增加确认。恢复事件或失败步骤表明中断发生在 `EXECUTING/VERIFYING` 时仍必须确认。重试启动后必须进入来源会话，使重新触发的审批对用户可见。
- 重试门禁必须把副作用证据归一化为稳定分类：`NO_SIDE_EFFECT`、`NOT_COMMITTED`、`COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED`、`COMMITTED_VERIFIED` 和 `EVIDENCE_INCOMPLETE`。任务卡、确认弹窗和确认前二次校验共享同一分类；`COMMIT_UNKNOWN`、已提交或证据不完整必须确认，不能自动恢复旧 Run 或重放工具。分类只是新 Run 重试指导，不代表旧 Run 已被恢复。
- 任务中心必须直接显示当前重试证据的分类、原因和建议动作；卡片与确认弹窗使用同一评估结果，不能只显示会被截断的分类码而隐藏提交未知或证据不完整的处理边界。
- 应用启动关闭不可原地恢复的活动 Run 时，必须在修改步骤/审批终态前计算副作用证据分类，并把分类和 Ledger/Event canonical fingerprint 写入 typed `run.recovered` 事件。后续重试仍需重新核对当前 Ledger/Event 指纹；启动清理把未执行的 `PENDING` 步骤统一改成 `CANCELLED` 时不得据此虚构副作用中断，当前分类或指纹与启动快照真正不一致时必须升级为 `EVIDENCE_INCOMPLETE`，不能让旧快照掩盖账本漂移。
- ToolResult 事件、执行预算快照和 `tool.verify` 事件之间的持久化边界必须可独立审计：Result 已落库但后续预算快照缺失时必须拒绝原地恢复并归类为执行预算证据无效；验证事件已落库但验证 Step 尚未收尾时只能补齐控制面，不得重复调用 Executor、ToolResult 或验证事件。生产故障注入默认为 no-op，不能改变正常执行顺序。
- 模型规划异常必须先保存可用的失败 telemetry 和已消耗预算，再进入失败终态；无 telemetry 的网络/网关异常至少保存预算快照。模型总结异常不能覆盖已经成功验证的工具事实，必须写入 fallback 审计并使用本地可信回复完成 Run；Receipt 回读失败必须保留稳定 `RecoveryFailure`、`COMMIT_UNKNOWN` 和二次确认重试边界。
- 规划和总结阶段的模型异常必须统一追加 typed `llm.request.failed`，并把上游错误映射为稳定的 `AUTHENTICATION / REQUEST_URL / RATE_LIMIT / MODEL / TIMEOUT / DNS / TLS / CONNECTION / RESPONSE / UNKNOWN` 分类；流式连接中断归入 `CONNECTION`，无法识别的当前异常和未来枚举都保守降级为 `UNKNOWN`。事件只展示阶段、错误码和脱敏原因，不保存请求正文。
- Runtime 因用户停止、WorkManager 取消或系统协程取消而收敛 Run 时，必须在 `NonCancellable` 中先持久化当前单调执行预算，再写取消 Step、`run.cancelled` 事件和 Run 终态；取消前模型/工具 finally 已累计的时间不能停留在旧快照，预算事件必须排在取消终态之前。
- 普通对话流式输出已经产生部分 delta 后发生断流或取消时，必须保留用户已见正文但写入明确的失败/取消终态，UI 不得继续显示“接收中”，也不能把该部分 assistant 作为完整回复再次进入后续模型请求或会话摘要；没有收到 delta 时不得凭空创建 assistant 正文。
- `AgentRunResumePolicy` 返回 `RESTART_REQUIRED` 时必须同时给出稳定处置码、具体策略原因、可证明的证据边界和下一步动作，不能只依赖可变中文文案。启动收敛必须把该处置与重试证据共同冻结到 typed `run.recovered`；任务卡、详情顶部和事件区读取同一历史快照。旧事件缺少处置字段时不得用当前版本策略回填或猜造，未知未来枚举必须保守降级。所有建议只允许保留旧 Run 并创建关联新 Run，不能暗示恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- WorkManager 再次拉起已处于 `RUNNING / STOP_REQUESTED` 的 ScheduledTask 时，必须按 `ScheduledTask -> WorkflowRun -> AgentRun` 关联链定向收敛旧实例，不能重新 claim、重新创建 Agent Run 或返回 `Result.retry` 复制可能已执行的副作用；Agent、Workflow、ScheduledTask 按顺序进入终态后才允许物化周期下一实例，无关前台 Run 不得受影响。`STOP_REQUESTED` 必须固定按用户停止收敛为取消，不能依据迟到 Workflow 成功改写；停止发生在 Workflow 已认领但 Agent Run 尚未关联的窗口时，也必须依据 Workflow→ScheduledTask 的持久关联取消 Run、未完成步骤和 Task，不能以“关联 Agent 缺失”写成失败。该链路已在 Redmi 完成同一 WorkRequest 的受控冷启动重入、强制 Doze 延迟、trim-memory、无压力对照和持久停止恢复；每个样本都只创建一个 Workflow/Agent Run。`run-as kill -9`、`force-idle` 与 `send-trim-memory` 不得表述为 Android 自主回收或连接关闭的因果证明。前台启动恢复与新 Worker 并发时，AgentRun 终态必须以原子条件更新保护，不能出现 Task/Workflow 已取消而 AgentRun 被迟到结果改成完成。
- 同一进程内，ScheduledWorkflowWorker 必须在任何 Room claim、重入对账或状态修改前登记 Task 执行所有权。应用启动恢复必须在同一互斥边界冻结旧 AgentRun、WorkflowRun 和 `RUNNING / STOP_REQUESTED` ScheduledTask 候选，并沿 Task→Workflow→Agent/Step 关联排除当前进程真正 `RUNNING` 的 Worker；已经写入 `STOP_REQUESTED` 的链不得再被进程所有权排除。快照期间新 Worker 必须等待，快照后的执行不得进入旧候选。后续 Agent 恢复/关闭和 Workflow/Task 对账只能消费冻结 ID，不能重新全库扫描误伤新执行。该能力不得依赖墙上时间、不得为当前版本新增持久 owner token 或 Room Schema，也不得借此恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。
- 系统进程退出观察必须是 Task/Run 之外的独立诊断账本。不得仅凭退出时间接近某次执行就建立因果关联；只保存 Android 稳定数值字段与应用侧稳定分类，description、trace 和进程状态摘要不得进入持久化或测试日志。只有 `REASON_LOW_MEMORY` 可作为直接 LMK，`SIGKILL` fallback 必须同时满足设备不支持直接报告且仍只能作为候选；用户停止、应用取消、权限/包变更不得冒充自然回收。重复历史稳定去重并最多保留 30 条；旁路采集异常不能阻断前台恢复或后台 Workflow，但协程取消必须传播。
- Room v29 本地保存 Provider、Agent Profile、会话、消息及 MessagePart、Agent Run、审批、独立工具调用/结果、笔记、长期记忆、候选记忆、记忆操作映射、Skill、Workflow、WorkflowStepDefinition、WorkflowSchedule、ScheduledTask、独立进程退出观察，以及知识文档全文、chunks、FTS 和检索审计；RunEvent 使用独立 typed metadata 保存时间线事实。v25→v26 只创建空知识库表，v26→v27 为 `agent_tool_results` 与 `message_parts` 增加默认 `[]` 的知识引用 JSON，v27→v28 为 `workflow_runs` 与 `scheduled_tasks` 各新增可空后台 Worker 停止原因码/名称列，v28→v29 只创建空退出观察表；升级不从旧正文、URI、`verifiedAgentContext`、工具记录、旧错误文案或历史时间邻近关系猜造知识引用、系统停止原因或 Task/Run 归因。v4→v29、各增量迁移和全新 v29 建库已有 Schema 与迁移测试保护。
- 普通对话、会话摘要 / 记忆、Agent 回复总结三类独立提示词设置，支持开关、即时保存、恢复默认和最终 system prompt 预览。
- 用户可通过 Android 系统文件选择器导出或恢复本地 Room ZIP 备份；恢复必须先校验版本并明确提示重启，API Key 密文不能脱离当前 Keystore 直接恢复。
- `MessageOrigin` 与 `VerifiedAgentContext` 可信来源边界：普通聊天、用户正文和模型自由文本不能伪造工具执行事实。
- 恢复边界已明确：`WAITING_APPROVAL` Run 在恰好一个 `PENDING` Approval 与最后一个已校验但无结果的 ToolCall 完全匹配、所有前序 ToolCall 均成功且 `PASSED`、执行/验证 Step 数量与顺序一致时，可以保留原 Run 等待用户决定；首步和第二次及后续审批使用同一证据规则。发起消息会先持久化，旧数据缺少消息锚点时按 Run 重建。执行/验证中的 Run 默认必须创建新 Run 安全重新运行，但有两个严格例外：最后一个 `notes.create` 或 `memory.remember` 同时具备完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据、工具白名单能力且尚无 `tool.verify` 时，可在原 Run 恢复只读后置验证；Run 已处于 `VERIFYING`，所有 ToolResult 均成功、所有 `tool.verify` 均为 `PASSED`，且 Step、Ledger、typed RunEvent 与原 Agent Profile 完全一致时，可在原 Run 恢复控制面收尾。v20 Run 只要存在独立工具账本，恢复必须 Ledger-first 并用 typed RunEvent 核对身份、字段、派生错误与事件顺序；任一缺失、重复或漂移都不得回退旧事件推断，账本完全为空的旧 Run 才保留严格 typed event 身份语义。旧验证事件缺少 ToolCall ID 时必须返回关联未知并升级为证据不完整，不得以工具名或事件顺序配对。
- 应用重启后会把可恢复审批重新显示到对应会话；符合证据条件的 `notes.create` 或 `memory.remember` 只读回读原 operation、写入唯一一条 `tool.verify` 并用本地可信总结完成原 Run，不调用写入方法。所有验证事实均已落库的通用工具恢复不会调用 Executor 或 LLM，也不会追加第二条 `tool.verify`；最后验证 Step 尚为 `RUNNING` 时只补为 `COMPLETED`，随后重建全部可信工具上下文并生成本地总结。两种恢复都不恢复旧模型协程，也不执行 Workflow 后续步骤；关联 Workflow 只保存当前步骤输出，剩余步骤要求创建关联新 Run。记忆恢复还必须保证记录仍启用、未过期且业务字段与提交结果快照一致；`OPERATION_NOT_FOUND / EVIDENCE_INCOMPLETE / PAYLOAD_MISMATCH / OPERATION_MISMATCH / MEMORY_NOT_FOUND / MEMORY_CHANGED / MEMORY_DISABLED / MEMORY_EXPIRED` 必须以独立 typed event 持久化。其他执行/验证中 Run 仍直接安全收敛并通过关联新 Run 重试；旧 Run 及其所有 `PENDING/RUNNING` Step 必须一致进入 `CANCELLED`。
- 审批恢复和已提交结果恢复必须使用原 Run 的 Agent Profile 快照，而不是当前选中的 Profile。缺少 Profile 审计的历史 Run 只能使用知识工具上线前的固定工具集合；新 Run 出现重复、损坏、引用未注册工具或 Skill 越权的 Profile 审计时必须拒绝恢复，不能回退当前 Profile 或当前 Registry 扩大能力。既有 Profile 和 Skill 也不得因注册新工具自动扩权。
- 应用重启后可恢复的链尾审批批准后，会从持久化审批步骤继续同一 Run；前序已验证工具不会重放，`completedTools`、已消耗工具调用数和重复调用指纹会从持久化证据重建，再执行当前 ToolCall、后续规划和最终总结。第一步已经执行后在第二次或后续审批处中断现已支持原 Run 恢复；若当前工具已经进入执行/验证阶段，则按两个受限恢复例外或安全新 Run 边界处理，提交状态未知时不得猜测执行结果。

当前仍未交付规模化 ANN 和自动后台批量 Embedding 重建，以及提交状态未知或验证事实未落库时的通用执行栈原地恢复、并行工具调用、后台 Workflow 执行栈断点续跑、精确定时和 Foreground Service。多步骤审批等待恢复、“全部验证通过后的控制面收尾恢复”、跨模型/工具段累计预算、当前进程 Worker 启动恢复隔离、后台运行中可见停止、`STOP_REQUESTED` 持久化异常重对账、旧验证事件缺少 ToolCall ID 时的 fail-closed 证据降级、Ledger/Event 指纹漂移拒绝，以及 Result/预算/验证三段持久化边界故障注入、模型异常预算审计和总结本地兜底已经交付，但都不等同于恢复旧模型协程或任意执行栈。设备 Agent 的 Accessibility 授权、健康检查、`device.snapshot`、短生命周期节点引用、隐私过滤、有限动作、风险审批、操作后重新观察和结果验证已交付，并已在 Redmi 上限定小灵、系统计算器、时钟、设置与桌面完成首批验收；不承诺任意 App。通用执行恢复和长任务可靠性完成前，设备工具不得进入 Workflow 或后台自动化。多步骤 Workflow 与非精确调度已完成真机验收；当前约 229.416 秒八步复合只读成功和 32.6 秒停止样本尚无引入 Foreground Service 的依据。Room v29 已开始有界观察 Android 进程退出事实；Redmi 支持 LMK 原因报告，本阶段受控 `force-stop` 只产生 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，仍没有 Android 自主 LMK。数据库恢复已交付，但跨设备 Provider 密文恢复仍受 Android Keystore 限制。

补充：`WAITING_APPROVAL` 的审批恢复已经可以在原 Run 上保留任意长度的已验证前缀，并继续链尾工具、验证、后续规划和总结；恢复同时继承持久化累计执行预算，不因进程重建获得新的总时长。`notes.create` 与 `memory.remember` 开放已提交但尚未验证结果的受限只读验证；所有工具都可在成功结果和 `PASSED` 验证已经完整持久化后恢复本地收尾。上述未交付项指提交状态未知、验证事实不完整的通用执行栈、Workflow 后续步骤断点续跑以及尚未完成的自动化能力。

长期记忆最近一次删除的撤销快照保存在应用私有原子文件中；启动时会与 Room 正式记录核对，陈旧或损坏快照不会复活未删除数据，也不会阻断应用启动。

当前实现详情见 [当前实现说明](implementation-notes.md)。

## 目标能力范围

### 第一层：可靠 Agent 基座

- 可取消、可限步、可恢复的 Agent Run。
- Tool Registry、结构化参数校验和统一 Tool Result。
- 运行时间线、错误事件、停止生成和失败重试。
- 工具级风险、确认、权限和验证策略。

### 第二层：个人数据与工作流

- 可管理的长期记忆和用户画像。
- 笔记、提醒、日历、文件等应用内或系统标准能力。
- 可保存、启停和查看历史的定时任务与工作流。
- 可按需加载的 Skill，不把全部工具定义塞入每次模型请求。
- Skill 只能引用已注册工具并缩小工具面，不能修改工具风险、审批、Android 权限和后置验证策略。

### 第三层：移动端执行

- 在独立授权后使用 AccessibilityService 读取界面结构并执行有限操作。
- 以可定位节点为主，截图和坐标只作为兜底。
- 高风险动作前确认，动作后重新观察验证。
- 权限失效、页面变化、任务取消和系统回收后能够明确失败并恢复。

## 暂缓范围

- 账号体系、云同步和跨设备一致性。
- 任意 Shell、Root、ADB 或隐藏系统接口执行。
- 无确认的支付、下单、删除、发送消息和系统设置修改。
- 开放式 Skill 市场和未经审查的远程代码安装。
- 一开始就引入多 Agent 自主协作、完整 MCP 生态或端侧大模型管理。

## 质量要求

- 每个 Agent Run 都有稳定 ID、状态、步骤、事件、耗时和最终结果；终态写入后不可被任何迟到执行路径覆盖。
- App 被杀死或进程重建后，不得把运行中的任务误报为成功，也不得让 AgentRun 与关联 Workflow/ScheduledTask 形成互相矛盾的终态；同一进程刚启动且仍正常 `RUNNING` 的 Worker 不得被前台启动恢复当作旧进程遗留收敛。Workflow 仍活动且用户停止已经写入 `STOP_REQUESTED` 后，平台取消失败、迟到回调、进程所有权或应用重启都不得把 Workflow/Task 写成成功，也不得追加成功会话结果或提前物化周期下一实例；步骤终态与对应成功消息必须共享同一停止栅栏和事务边界。若 Workflow 已先进入终态而 Task 仍活动，后续原子结算必须保留该持久 Workflow 终态并映射到 Task，不得用来晚的停止或迟到 outcome 反向覆盖。
- 工具参数在执行前必须完成类型和业务校验。
- 敏感工具必须在应用侧确认，后台任务不得绕过确认策略。
- 工具报告成功后，关键变更必须有后置验证；无法验证时明确标为“未验证”。
- RunEvent 与独立工具账本双写必须原子完成；v20 非空账本在展示和受限恢复中均为事实源，事件只用于一致性核对，任一身份、字段、时间、顺序或基数漂移都必须 fail-closed。旧 Run 账本完全为空时保守回退到原事件，不得伪造 ToolCall 关联或改变历史恢复结论。
- 记忆写入必须可追溯到会话或任务；候选未经确认不得参与检索，敏感阻断不得保存原值，删除后不再参与检索。
- debug 日志可以诊断请求和工具过程，release 日志不得泄露密钥、完整隐私内容或敏感参数。
- 每个里程碑都需要单元测试、关键状态机测试和 Android 真机验证。

具体开发顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
