# 小灵

「小灵」是一款 Android 端个人 Agent 应用。当前阶段先把个人 Agent 的基础底座做稳：多模型提供方配置、多会话上下文、Chat Completions / Responses API、Room 本地存储、可审计 Agent Run，以及基于 WorkManager 的一次性非精确定时工作流。

后续方向不是继续停留在“能不能连上模型”，而是逐步扩展成个人可长期使用的移动端 Agent：持续记忆、工具调用、移动端自动化、任务编排和更完整的个人工作流。

GitHub 仓库：[lonnnnnng/xiaoling](https://github.com/lonnnnnng/xiaoling)

最新版本：[小灵 v0.1.13](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.13)

## 当前定位

- 移动端个人 Agent 入口。
- OpenAI-compatible Provider 优先，不绑定单一服务商。
- 先保证模型接入、上下文、会话保存和输出渲染稳定，再扩展工具与自动化。
- 更换了新的 `applicationId`：`com.longdev.xiaoling`。Android 会把它视为新应用，旧版本本地数据不会自动迁移。

## 已有能力

- 对话页
  - 支持选择模型提供方和已启用模型。
  - 支持 Chat Completions 与 Responses API。
  - 支持 SSE 流式输出，展示首字耗时和总耗时。
  - 支持发送中停止生成，取消当前请求和底层 OkHttp Call。
  - 支持多会话本地保存、会话上下文和 LLM 摘要压缩。
  - Responses 模式支持从系统文件选择器附加单张 PNG/JPEG/WEBP 图片（最大 8 MB），发送前可预览或移除，历史消息可恢复显示；Chat Completions 和 `/agent` 会明确拒绝图片。
  - Responses 模式支持附加单个 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX 或 XLSX 文档；文件最大 8 MB，PDF 最多 50 页，UTF-8 文本最多 200,000 字符，OpenXML 富文档会校验 ZIP/OPC 结构与展开预算，原始文件随消息恢复。
  - 支持 Markdown 渲染，覆盖表格、代码块、列表、引用、链接和远程图片。
  - 对话记录有轻量的新内容提示，用户翻看历史时不会被强制拉回底部。
  - 支持 `/agent <目标>` 顺序多步 Agent 链路：当前模型可在同一 Run 内逐步选择最多 4 个工具或结束任务，应用侧对每一步独立校验、审批和验证，执行结果写入 `AgentRun / AgentStep / RunEvent`。
  - 已内置第一批应用内工具：`app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`memory.search`、`knowledge.search`，以及需要审批的 `notes.create` 和 `memory.remember`。
  - Agent Run 关联重试已由独立 `AgentRunRetryCoordinator` 编排：失败 Run 的资格判断、副作用证据确认与漂移复核、原 USER 附件恢复和关联新 Run 请求不再散落在 ViewModel。重试始终保留旧 Run 终态，以 `retryOfRunId` 创建新 Run；会话导航、Profile/Provider 校验和真正执行仍由 ViewModel/Agent Runtime 负责，不扩大工具或后台权限。
  - `NOT_COMMITTED_REPLAY_ELIGIBLE` 已接入用户控制的受控关联重试：请求与确认都重新读取 Room，使用来源 Profile 和当前 Registry 重核恢复资格；确认后创建带 `retryOfRunId` 的新 Run，冻结来源工具名称、风险、参数与恢复契约，同时生成新的 ToolCall ID。新 Run 不调用模型重新规划，仍重新发起独立工具审批，批准后只执行该调用一次并直接总结；旧 Run、旧 ToolCall、旧审批和旧 Executor 均保持不变，Workflow 与后台入口不开放该路径。
  - 进程恢复后的链尾审批已由独立 `RecoveredAgentApprovalCoordinator` 编排：每次决定都重新读取 Room detail 并复用 `AgentRunResumePolicy` 核验唯一链尾证据，批准前先恢复原 USER 附件，重复批准/拒绝由一次性互斥门禁拒绝。另一会话的恢复审批占用门禁时返回 `Busy`，当前 `PENDING` 卡片保持可重试；附件或前置能力失败且审批仍为 `PENDING` 时也会恢复卡片。拒绝在一个 Room 事务中原子收敛 Approval、审批 Step 与原 Run，避免半状态。普通前台审批仍由独立 `AgentApprovalDecisionCoordinator` 管理 waiter，两条边界不合并。
  - 候选记忆的列表、成功回合采集和接受/拒绝已由独立 `AgentMemoryCandidateCoordinator` 编排：普通聊天与 Agent Run 使用稳定来源身份，同一候选 ID 的并发决定返回 `Busy`，不同候选可以并行；失败和取消都会释放 claim。关闭候选开关会取消旧列表读取，避免迟到 Room 结果重新填充界面。敏感过滤、去重、冲突、事务与正式记忆检索继续由既有 Room Store/Manager 负责。
  - Provider 模型同步已由独立 `ProviderModelSyncCoordinator` 编排：单项与批量同步统一 URL 校验、请求规范化、模型去重与当前模型回退；批量严格按列表顺序执行，普通失败继续下一项，取消立即终止。网络请求可以并行，但完整 Provider 快照通过提交互斥串行落库；保存前后都会拒绝已删除或身份漂移的迟到结果，成功必须以 Room 持久化完成为准。ViewModel 只保留忙碌态、逐项结果和弹窗投影。
  - Agent 启动前校验已由独立 `AgentLaunchPreflightCoordinator` 编排：普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批统一执行会话、Profile、工具注册与 Provider 校验。普通 `/agent` 仍可在没有当前会话时创建会话；其余入口要求原会话存在。恢复审批优先使用原 Run 的 Profile 快照，旧 Run 没有有效快照时才回退当前选中 Profile；其他入口继续使用当前 Profile。校验只冻结本次进程内运行配置，不写 UI、Room 或日志；运行配置自身的字符串表示会脱敏 Base URL、API Key 与自定义 Header。
  - 个人 Agent 主线已重新启动。用户在前台手动运行 Workflow 时，可在设备 Agent 独立开关和 Accessibility 授权均有效、且 Profile/Skill 允许的前提下使用只读 `device.snapshot`；`open_app / back / home / tap_ref / type_text / swipe` 仍只允许前台直接 `/agent`，后台或定时 Workflow 看不到也不能执行任何设备工具。工具清单与 Executor 保持两层门禁，审批恢复会从 Room 中的 Workflow 关联还原原调用来源，不能因进程重建退化成直接对话权限。
  - 知识质量工程已完成 answerability Shadow 跨进程持久化的首个最小切片：显式开启且身份匹配的前台直接 `/agent` 答案仍在保存后旁路调用 Judge，观测结果改为 `OPTIONAL` 写入 Room v33 匿名账本。账本以 SHA-256 幂等键去重、最多保留 2,000 条，只保存候选摘要、Keystore 密钥生成的 Judge HMAC 匿名桶、状态枚举和数值遥测；不保存消息/Run ID、问题、答案、引用、原始响应、Provider/模型、URL 或凭据。设置页分开展示跨进程累计与当前进程 notice 生命周期；notice 不跨进程恢复，生产 enforcement 继续关闭。
  - 第 102 阶段已冻结版本化离线评测导出契约：匿名 Shadow 观测与显式授权内容案例使用不能混装的强类型 envelope。匿名证据只携带 v33 不可逆 fingerprint、枚举、失败分桶和可空成本，不能用于 calibration/validation；显式内容案例才允许携带授权、数据集身份、正文、引用与人工评估。本阶段没有增加 JSON/SAF 出口或生产 enforcement。
  - 第 107 阶段在 Redmi 形成第三条 Room v33 真实 Shadow 记录。一次较宽的请求连续执行 4 次 `knowledge.search` 后以 `BUDGET_EXHAUSTED` 收敛，没有成功答案、没有消费一次性授权，也没有写入匿名账本；随后复用已验证查询模式的前台 `/agent` 只执行 1 次检索并新增 `COMPLETED / BOUND / ACCEPT` 记录。第三条 attempt `1`，耗时/TTFB `7288/7274ms`，Prompt `6664B`，Tokens `1715/314/2029`，全部失败计数为 `0`；累计账本为 `3` 条、Judge 身份桶 `1`、完成/绑定/接受 `3/3/3`。本轮距第二条 `4 小时 38 分 33 秒`，只记为独立同日窗口，仍不解锁 JSON/SAF、校准或生产拒绝。
  - 第 106 阶段把匿名账本已有的最早/最新记录时间和精确跨度投影到 Shadow 设置页；该阶段当时的两条时间证据对应北京时间 `2026-07-29 07:27:36 -> 08:13:50`、跨度 `46 分钟 13 秒`。该文本只帮助人工判断后续窗口是否真正分隔，不计算通过/拒绝资格，不触发 Judge，也不开放 JSON/SAF、校准或 production enforcement。投影 JVM `3/3` 覆盖正常跨度、单端缺失和时间逆序，AndroidTest APK 编译通过；Stage 105 遗留的 Compose 旧开关语义断言已同步为“授权下一次”。
  - 第 105 阶段把答案可回答性 Shadow 收紧为单次显式采样窗口：候选存在且答案保存成功后，Publisher 通过原子门禁同时完成开关检查、自动关闭和持久化，再进入观测协调器；并发答案只有一条能消费授权，候选缺失、保存失败或提前撤销不消费窗口。观测开始后的成功、未知、取消或异常都需要下一次重新显式开启。Publisher 与 20 路并发门禁聚焦 JVM 合计 `11/11`，本阶段没有新增 Room 行、真实样本、JSON/SAF 或 production enforcement。
  - 第 104 阶段在完整清理并重启进程后形成第二条 Room v33 真实 Shadow 记录。该窗口距首条约 46 分钟，只能作为独立短间隔复验，不能冒充长期分隔样本。两条累计均为 `COMPLETED / BOUND / ACCEPT`，Judge 身份桶仍为 `1`，attempt `2`，耗时/TTFB `17308/17287ms`，Prompt `14846B`，Tokens `3706/841/4547`，全部失败计数为 `0`。同时修复冷启动初始化重建状态时把已读取的跨进程摘要覆盖为零的问题；Redmi 冷启动后设置页已显示观测 `2`、Judge 身份 `1`、完成/接受 `2/2`、尝试 `2`、耗时 `17308ms` 和 Tokens `4547`。
  - 第 103 阶段完成首个 Room v33 间隔真实 Shadow 窗口：Redmi 前台直接 `/agent` 使用词法兜底命中 `Agent Run retryOfRunId` 本地知识，第 103 阶段当时匿名账本只有 `1` 条 `COMPLETED / BOUND / ACCEPT`。Judge 尝试 `1` 次，耗时/TTFB `9663/9655ms`，Prompt `10879B`，Tokens `2801/469/3270`，所有失败计数为 `0`。第 104 阶段随后形成第二条短间隔记录，第 107 阶段又形成第三条独立同日记录；继续等待真正跨日或长期分隔窗口，不提前实现 JSON/SAF 或生产拒绝。
  - 第 101 项已完成首个间隔真实使用窗口：仅在 Redmi 前台直接 `/agent` 中显式开启 Shadow，使用词法兜底命中的 `Agent Run retryOfRunId` 本地知识候选形成 `1` 条完成样本，Judge 判定为直接回答。删除测试会话和临时知识文档后，notice 有效数由 `1` 归零且裁剪数变为 `1`，Shadow 已恢复关闭；该项继续保持低频观察，不增加 Room Store、跨进程 notice 或 enforcement。
  - 第 100 阶段新增 Android 系统分享入口 v1：分享面板只接收单项 `text/plain` 或单张 PNG/JPEG/JPG/WEBP 图片，文本最多 20,000 字符，图片必须是 `content://` 且继续复用现有 8 MB、MIME、签名和解码校验。`EXTRA_STREAM` 与 `ClipData` 同时携带同一 URI 时按单图兼容，URI 不同时按多图拒绝。内容只进入可编辑的新会话草稿，永不自动发送；已有草稿、附件或活动操作时必须显式“打开分享/忽略分享”，第二个未决分享不会覆盖第一个。冷启动初始化、热启动 `onNewIntent` 和 Activity 重建均有独立处理；来源统一标为外部分享，不信任可伪造的 referrer 或 Intent extra。
  - 第 99 阶段完成首批 Redmi 低频 answerability shadow 观察：同一进程新增 `3` 条有效 Judge 样本，直接回答 `2`、部分回答 `1`，Judge 取消、异常和旁路错误均为 `0`；本批累计耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。首次宽英文检索连续无候选并使 Agent Run 达到工具步数上限，但没有进入 Shadow，不能记作 Judge 失败。
  - 第 98 阶段已在 Redmi 同一进程内扩充用户显式开启的真实前台 answerability shadow 样本：累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次形成 `2` 条直接回答与 `2` 条部分回答；自然 `BUDGET_EXHAUSTED` Run 未进入 Shadow，不能记作 Judge 失败。累计成本为耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`，取消和异常均为 `0`。
  - 第 97 阶段已为默认关闭的 answerability shadow 增加有界进程内样本摘要：只记录 Judge attempt、延迟/TTFB、Prompt 字节、Tokens、失败分类和 notice 生命周期，不记录问题、答案、候选正文、引用、原始响应或凭据。真实 Redmi 前台 Agent 样本已验证答案先保存、Judge 后置、notice 可见且随会话删除裁剪；普通聊天、Workflow 和后台 Worker 不进入样本分母。
  - 设备 Agent 已接入 `device.snapshot / open_app / back / home / tap_ref / type_text / swipe`：仅在用户独立开启、系统 Accessibility 已授权且 Profile/Skill 允许时可用。前台直接 `/agent` 可使用完整限定工具集；前台手动 Workflow 只可使用脱敏 `device.snapshot`；打开应用、点击和输入必须审批，所有动作完成后重新观察并验证；后台或定时 Workflow 不会看到或执行任何设备工具。

- 设置页
  - 一级入口为「模型提供方管理」。
  - 提供「提示词设置」二级页，可分别配置普通对话、会话摘要 / 记忆和 Agent 回复总结模板。
  - 每类模板支持独立启用、恢复默认和预览最终提示词；普通对话的工具边界、摘要事实边界和 Agent 审计边界不可被自定义模板覆盖。
  - 提供「Agent Skills」管理页，展示内置与本地 Skill，可通过系统文件选择器导入版本化 JSON、启停能力并删除本地 Skill。
  - 提供「设备 Agent」页，管理默认关闭的独立开关、系统无障碍入口、四态健康检查和有界脱敏快照预览。
  - 提供「工作流」管理页，可保存、启停、手动运行或创建 1 分钟至 7 天的一次性非精确计划，并查看计划时间、实际启动时间、Workflow Run 与步骤结果。
  - 支持新增、编辑、删除模型提供方。
  - 支持 `Base URL`、`API Key` 和名称配置。
  - 支持扫码导入、剪切板解析和 Base64 解码辅助。
  - 支持拉取上游模型列表，并手动勾选允许在对话页使用的模型。
  - 支持单个同步和批量同步模型列表。

- 请求与安全
  - 支持 `GET /models`。
  - 支持 `POST /chat/completions`。
  - 支持 `POST /responses`。
  - 固定 `max_tokens` / `max_output_tokens` 为 `32768`。
  - Provider、会话、消息及 Text/Reasoning/Image/Document/Tool parts、Agent Run、笔记、长期记忆、Skill、Workflow Ledger、ScheduledTask 和匿名 answerability Shadow 观测使用 Room 保存；附件原始字节写入 BLOB 并随数据库备份，旧 SharedPreferences 数据首次启动时迁入。
  - 后台计划只允许显式声明 `supportsBackground=true` 的 SAFE 只读工具；需要审批的工具在执行前进入 `BLOCKED` 并发送通知，不创建或继承前台临时授权。
  - API Key 使用 Android Keystore + AES-GCM 加密保存。
  - 允许明文 HTTP，便于连接 Ollama、LM Studio、局域网服务和 adb reverse。
  - HTTP 调试日志通过 BuildConfig 开关控制：debug 默认开启，release 默认关闭。
  - 普通对话不具备工具执行能力，不得声称已经调用工具、操作设备、创建笔记或保存长期记忆；真实工具事实只来自可审计 Agent Run。
  - AccessibilityService 只执行标准节点动作与系统返回/主页，不具备坐标手势或截图能力；支付窗口与已知密码管理器、Authenticator、钱包/银行应用整窗拒绝，密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref。

## 使用方式

1. 打开「设置」页，进入「模型提供方管理」。
2. 新增模型提供方，填写：
   - 名称：可选，不填时根据 URL 兜底。
   - `Base URL`：例如 `https://api.example.com/v1` 或 `http://127.0.0.1:8765/v1`。
   - `API Key`：服务需要鉴权时填写。
3. 点击「获取上游模型」，勾选允许在对话页使用的模型并保存。
4. 回到「对话」页，选择模型提供方、模型、接口模式和是否流式输出。
5. 输入消息开始对话；Responses 模式可点击附件图标附加单张图片或一个文档。也可从 Android 分享面板选择「小灵」，把单段文本或单张支持的图片导入新会话草稿，确认内容后再自行发送。
6. 输入 `/agent 现在几点`、`/agent 记住我喜欢紧凑的界面` 可运行本地最小 Agent 工具链路，但当前 `/agent` 不接收附件。
7. 如需扩展声明式能力，可在「设置 -> Agent Skills」导入 [每日回顾示例](docs/examples/daily-review.skill.json)；本地 Skill 只能组合应用已注册工具，不能执行脚本或放宽审批边界。
8. 可在「设置 -> 工作流」保存常用 Agent 目标并手动运行，或点击时钟图标创建一次性计划。WorkManager 只保证在计划时间后尽快运行，不承诺准点；Android 13+ 建议授予通知权限以接收完成、失败和待处理结果。
9. 如需使用设备 Agent，在「设置 -> 设备 Agent」明确开启应用开关并完成系统无障碍授权，再为 Agent Profile 选择只读的 `device-observation` 或有限动作的 `device-control` Skill。当前只允许小灵、系统计算器、时钟和系统设置等首批白名单应用；前台手动 Workflow 只可使用 `device.snapshot`，设备动作和全部后台设备工具仍关闭。

## 本地 mock 调试

真机访问电脑本机服务时，可以使用 adb reverse：

```zsh
adb -s wsvwypiz7xwslvl7 reverse tcp:8765 tcp:8765
```

App 内填写：

```text
Base URL: http://127.0.0.1:8765/v1
API Key: test-key
```

## 构建

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
```

生成正式签名包：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleRelease
```

本机 release 签名配置放在未跟踪目录：

```text
local-signing/xiaoling-release.env
local-signing/xiaoling-release.jks
```

## 当前验证

- 第 108 阶段把主线从 Shadow 样本等待切回个人 Agent 能力，并完成前台手动 Workflow 的只读设备观察闭环。双轴审查后，设备控制器以统一 `READY` 健康态同时约束规划清单和 Executor，Accessibility 未授权或服务断连时不再向模型暴露设备工具；聚焦 JVM `88/88`、`compileDebugKotlin`、Debug APK 和 AndroidTest APK 构建成功，仅 Redmi 的 Room 关联单项为 `OK (1 test)`、耗时 `0.476s`。真实 `stage108_snapshot` 手动 Workflow 在前台完成 6 个步骤，总耗时 `18.868s`，唯一工具调用为 `device.snapshot`，结果 `SAFE / success=1 / PASSED / 193ms / 6128B`，快照含 `15` 个节点、`redacted_node_count=2`、ref 有效期 `30000ms`；设备动作调用与审批请求均为 `0`。更新后的文档语料单项在 Redmi 为 `OK (1 test)`。临时会话已删除，临时 Workflow 因当前没有删除入口而禁用保留，设备 Agent 与 Accessibility 均恢复关闭。验收后曾误装 Room v32 固定 Release，启动明确报 `A migration from 33 to 32 was required but not found`；已在不卸载、不清数据的前提下恢复正式证书签署的当前 Debug，最终 `0.1.13 (14)`、`MainActivity` resumed、PID 存活且 crash buffer 为空。该错误属于开发设备向下覆盖，不新增数据库降级迁移。
- 第 107 阶段只在 Redmi `wsvwypiz7xwslvl7` 执行第三个 Room v33 Shadow 窗口。正式证书签署的当前 Debug 覆盖安装后，导入 `xiaoling-stage107-shadow.md` 为 revision `1`、`8` 个 chunks；Embedding 未建立，真实请求通过词法兜底。首次 Run 因 4 次工具预算耗尽而未消费授权，第二次 Run 只调用 1 次 `knowledge.search` 后完成并新增第三条匿名记录。停进程最终快照为知识文档/chunks/messages `0/0/0`、空壳会话 `1`、Agent Run `4`（完成 `3`、预算耗尽 `1`）、Shadow rows `3`、Provider/Profile `1/1`、Shadow `false`、失败分桶合计 `0`；测试包与临时下载文件不存在。投影真实毫秒夹具同步修正为 `46 分钟 13 秒`，聚焦 JVM `3/3` 与 `assembleDebugAndroidTest` 通过；同步后的 Redmi 文档 corpus 首次/最终单项均为 `OK (1 test)`、耗时 `2.687s / 2.606s`。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 106 阶段按分级验证完成 Shadow 时间窗口证据投影。`AnswerabilityShadowWindowEvidenceProjectionTest` 使用第 103/104 阶段真实时间固定北京时间范围与 `46 分钟 13 秒` 跨度，并覆盖单端缺失和时间逆序时跨度未知，聚焦 JVM `3/3`；`assembleDebugAndroidTest` 成功并编译更新后的 Compose 设置页测试。没有安装 APK、连接设备、调用真实 Judge 或新增 Room 行，也没有运行完整 JVM、Lint、Redmi instrumentation 或 Release。
- 第 105 阶段按分级验证完成单次显式 Shadow 采样窗口。首轮 Red 因缺少消费 seam 按预期编译失败；双轴审查发现检查与关闭分离存在并发复用授权风险，第二轮 Red 增加 20 路并发门禁测试。最终 `tryConsumeObservationWindow` 与 `AnswerabilityShadowObservationWindowGate` 原子完成检查和消费，Publisher `10/10`、门禁并发 `1/1`，聚焦 JVM 合计 `11/11` 并完成 Debug 主源码编译。没有调用真实 Judge、没有新增 Room 行，也没有运行完整 JVM、Lint、APK、Redmi instrumentation 或 Release。第 103/104 阶段匿名账本仍为 `2` 条短间隔记录。
- 第 104 阶段按分级验证完成第二条 Room v33 真实 Shadow 复验和冷启动摘要修复。临时导入 `docs/answerability-shadow-binding.md` 后形成 revision `1`、`5` 个 chunks、`11.4 KB`；Embedding 不可用时，查询 `anonymous shadow calibration validation` 通过词法兜底命中 `1` 个 chunk。真实前台 `/agent` 新增 `1` 条 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7645/7632ms`，Prompt `3967B`，Tokens `905/372/1277`，失败计数为 `0`；与第 103 阶段累计为观测 `2`、Judge 身份桶 `1`、完成/接受 `2/2`、attempt `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`。该窗口距首条约 `46` 分钟，只证明完整清理和进程重启后的独立短间隔链路，不支持 JSON/SAF、显式授权评测集、独立阈值校准或生产拒绝。最终 Room 快照为知识文档/chunks/messages `0/0/0`、空壳会话 `1`、两个旧 Run 均 `COMPLETED`、Shadow rows `2`、Provider/Profile `1/1`；Shadow 关闭、production enforcement 偏好不存在、测试包与临时下载文件不存在。新增聚焦 JVM、Debug/AndroidTest 构建均通过；文档 corpus 前两轮为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充设备收尾并重新打包后的最终复验同样通过。主应用最终冷启动 `3385ms`，Activity resumed、PID 存活、crash buffer 为空。
- 第 103 阶段只执行与真实 Room v33 样本直接相关的分级验证：`assembleDebug` 与 `assembleDebugAndroidTest` 分别成功，Redmi Provider 兼容单项为 `OK (1 test)`；随后使用真实 `gpt-5.5` 完成一次前台 `/agent + knowledge.search + Judge`。停进程数据库快照确认 Schema `33`、知识文档/分块均为 `0`、匿名账本恰好 `1` 条完成且接纳记录，Shadow 偏好为 `false`，生产 enforcement 偏好不存在，Provider/Profile 仍可用。Debug APK 为 `23,685,840` 字节、SHA-256 `f0dc66a6300553511771aeb395fbd07d0b57e97f709c1cea566b78130bb89e2f`；同步后的文档 corpus 单项在 Redmi 为 `OK (1 test)`、耗时 `1.988s`，复验后账本与清理状态不变。测试包已卸载，当前源码 Debug 以正式证书保留在 Redmi 上，最终冷启动 `3441ms`、Activity resumed、crash buffer 为空。本阶段没有执行完整 JVM、Lint、默认完整 instrumentation 或 Release 构建，也没有把 Room v32 的已发布 APK 降级覆盖到 v33 数据库。
- 第 102 阶段冻结 `KnowledgeAnswerabilityExportEnvelope` 强类型契约，并明确匿名 Shadow envelope 固定不能作为 calibration/validation 数据；完整门禁为 JVM `736/736`、Lint `0 error / 51 warnings`、Debug/AndroidTest/Release APK、Redmi 完整 instrumentation XML `248` 条（`236 passed / 12 skipped / 0 failed`）和文档 corpus `1/1`。该阶段没有接入 JSON codec、SAF/UI 出口或生产 enforcement。
- 知识质量工程的首个切片已完成 answerability Shadow 匿名跨进程账本。Room `v32 -> v33` 只创建空表，不从历史消息、Run、检索或人工阶段合计回填；幂等键重复写入只计一条，数据库关闭重开后摘要仍可读取，未知数值保持 `null`，最终异常没有 Provider telemetry 时仍按稳定 `failureKind` 计数。Store 在落库前强制幂等键与候选摘要为 64 位小写 SHA-256；Judge 配置使用 Android Keystore 内不可导出密钥计算 HMAC-SHA-256，数据库单独泄露时不能按公开 Provider/模型配置枚举或跨安装关联。审查后补充的 Redmi Store `4/4` 同时证明公开 SHA-256 不等于落库 HMAC，以及第 2,001 条写入会裁剪最旧记录。完整本地 `141/141` tasks（`2m 38s`）、JVM `734/734`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；Redmi 保持唤醒后的最终完整 JUnit XML 为 `248` 条（`236 passed / 12 skipped / 0 failed`），runner 最终打印 `260 tests`，耗时 `1m 51s`。Debug/Release APK 为 `23,452,761 / 3,220,018` 字节，SHA-256 为 `f186eecb97d84251e241e4e9f97d2d68a2c8b7ca2a70060f30cab29f9cd5a397 / fd840fca412fdcf0b23aa5f2b43c9b90fd0c714881b4fe6fe294d8e1acb1da16`；Release 通过 zipalign、v2 正式证书和单签名者校验。更新后的 README/docs 首次 Redmi corpus gate 为 `OK (1 test)`（`2.505s`），写回审查修复与设备收尾后的最终复验同样通过；固定正式 `v0.1.13` 已恢复，版本、前台 Activity、主进程、测试包卸载、保持唤醒还原和空 crash buffer 均已核对。
- 通用执行恢复矩阵已完成“成功 ToolResult 已落库但缺少 typed 验证结论”的持久化窗口审计。`AgentRunResumePolicy` 现在按“工具定义存在性 -> `COMMITTED + IDEMPOTENT_BY_KEY` 提交证据 -> 当前工具是否开放只读恢复验证”依次短路：定义缺失固定返回 `TOOL_DEFINITION_UNAVAILABLE`，回执缺失或损坏固定返回 `COMMITTED_EFFECT_EVIDENCE_INVALID`，前两类都不会调用 support 回调；只有定义和提交证据完整但未开放只读回查时才返回 `COMMITTED_VERIFICATION_UNAVAILABLE`。本轮只提升 fail-closed 处置精度，不新增 resume kind、Repository 写路径或原地恢复资格，也不补造 `PASSED / FAILED`。强制本地 `141/141` tasks（`4m 15s`）、JVM `734/734`、Lint `0 error / 52 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；仅 Redmi 默认完整 instrumentation 为 `OK (243 tests)`、耗时 `95.348s`。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `954f71d5a90a6f2b63160490eab45ea67486b92f3fe8275ca7cb15498a4de6b5 / 4ecb44ae0a189cd956b9e4f12d5827d5d2477be981ea6ed371c71a0cf6ab3fae`；Release 通过 zipalign、v2 正式证书和单签名者校验。最终 README/docs 已重新打包并通过 Redmi 文档语料 gate；正式 `v0.1.13` 已恢复，版本、前台 Activity、主进程、测试包卸载、保持唤醒还原和 crash buffer 均已核对。
- 通用执行恢复矩阵新增“持久化失败工具验证原子失败终态结算”。当 `ToolResult success=true`、结果后的执行预算完整、typed `tool.verify=FAILED` 携带稳定原因、完整 v20 Tool Ledger 与 Step/Event 身份一致，且最后一个 `TOOL_VERIFY` Step 仍为 `RUNNING` 时，`closeInterruptedRuns()` 只在单个 Room transaction 内把该验证 Step 与原 Run 结算为 `FAILED`，写入 typed `run.recovered(PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status`。该路径不重复 Executor、验证器或 LLM，不追加第二条验证事实、不生成成功总结、不继续 Workflow；event-only、预算缺失或未位于结果之后、失败原因缺失、前缀未完整验证、待审批、尾随事件及身份/状态漂移继续 fail-closed。双轴审查补齐了预算必须为 `Available` 的硬门槛，并把 Runtime 验证异常捕获从 `Throwable` 收紧为 `Exception`。强制本地 `141/141` tasks（`3m 35s`）、JVM `732/732`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；仅 Redmi 定向事务/并发/Runtime 组合为 `OK (3 tests)`，默认完整 instrumentation 为 `OK (243 tests)`、耗时 `96.162s`。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `1d39a89b3bd183253a1e217f3d32f9727cfa957bdcc6b2f884915c6251455fde / ffae97ee1406b667d93c7c9b436bafb50a73f8284d861595380c16415714fb36`；Release 继续通过 zipalign、v2 正式证书和单签名者校验。当前文档 corpus 首轮/中间复验为 `OK (1 test)`（`2.907s / 2.471s`），最终文本 gate 也已通过；正式 `v0.1.13` 已重新覆盖安装，冷启动 `602ms`，版本、前台 Activity、PID、测试包卸载、保持唤醒还原和空 crash buffer 已核对。
- 通用执行恢复矩阵新增“持久化失败 ToolResult 原子失败终态结算”。只有 v20 完整非空 Tool Ledger 能证明 Run 仍为 `EXECUTING`、前序工具全部成功且 `PASSED`、唯一链尾 ToolResult 为 `success=false` 且错误非空、结果后恰有一份完整预算快照、对应最后一个 `TOOL_EXECUTE` Step 仍为 `RUNNING`，并且没有待审批、`tool.verify`、额外 Step 或业务尾部时，`closeInterruptedRuns()` 才会在单个 Room transaction 内把执行 Step 与原 Run 收敛为 `FAILED`，写入 typed `run.recovered(PERSISTED_TOOL_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status`。该路径不调用 Executor、验证器或 LLM，不生成成功上下文、不继续 Workflow；重复和双 Repository 并发只允许一次结算，终态审计写入失败会整体回滚。链尾缺少 ToolResult 仍属于 `COMMIT_UNKNOWN`，成功结果待验证、event-only、预算缺失以及身份/步骤/尾部漂移继续 fail-closed。双轴审查补齐了 Step sequence 与 typed 创建/完成事件身份核验，并删去 Repository 不消费的恢复载荷字段。强制本地 `141/141` tasks（`3m 1s`）、JVM `726/726`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；仅 Redmi 默认完整 instrumentation 为 `OK (240 tests)`、测试耗时 `93.258s`。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `75a62310b023d090eebb89b702f1276fba86b015bbec5a865c660620388a4b14 / 74f546b4f8c77f497ebd5eb5058e4ed850464bdfbbe99aeed14cd8283a655e9a`；Release 继续通过 zipalign、v2 正式证书和单签名者校验。更新后的文档 corpus 首次复验为 `OK (1 test)`（`2.644s`），写回完整门禁与设备收尾后的复验同为 `OK (1 test)`（`2.553s`）；正式 `v0.1.13` 冷启动 `499ms`，版本、前台 Activity、PID、测试包卸载、保持唤醒还原和空 crash buffer 均已核对。
- 通用执行恢复矩阵的“已提交与已验证控制面幂等收尾”已完成。恢复 marker 现在以 `resumeKind + boundary key + from/to status + reason` 完整绑定恢复边界，同一边界只允许唯一且一致的 marker；合法 marker 后出现第二条、损坏或冲突记录时一律 fail-closed。Step 创建/状态事件新增 typed `stepId / sequence / type / fromStatus / toStatus`，恢复策略不再仅凭事件名接受尾部。`COMMITTED_TOOL_VERIFICATION` 的状态 CAS、`run.status` 与 marker 同事务提交，`closeInterruptedRuns()` 也在一个 Room 事务内原子收敛 Step、Approval、Recovery 与 Run 终态；恢复入口写 marker 后重新读取 Room 并重新评估。全部工具已经 `PASSED` 时仍不调用旧 Executor、LLM 或 Workflow 后续步骤，只允许在三类可达控制面尾部幂等完成：尚未创建恢复总结、总结 Step 为 `RUNNING`（typed 总结事件可尚未写入或已经写入）、总结 Step 与事件已完成但 Run 尚未终态。恢复总结 Step/Event 在 Room 内 get-or-create，双协调器并发只产生一份总结事实；`COMPLETED recovery.summarize` 缺少总结事件、边界后出现业务事件或任一 typed 身份漂移都会拒绝恢复。强制本地 `141/141` tasks（`3m 14s`）、JVM `717/717`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；恢复聚焦 JVM `123/123`、Redmi Room 定向 `OK (36 tests)`（`8.434s`）。解锁并保持唤醒后的 Redmi 默认完整 instrumentation 为 `OK (237 tests)`、耗时 `93.062s`；首轮锁屏产生的 `59` 条 Activity/Compose 前台失败已由失败单项和完整套件复验排除。当前文档语料首次复验为 `OK (1 test)`（`2.447s`），写回并重新打包后的最终复验同为 `OK (1 test)`。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `09c360e3a8429e72dd82bf32b21f398c6ae77fa7eb8d3e0dde4c979d223dc6ef / 5878510423499f3de1b1764376b24573abcc04c3d9440b94a97f000e48a14da8`；Release 已通过 R8、lintVital、zipalign 和 v2 正式签名，验收后正式 `v0.1.13` 已恢复。
- “尚未提交”受控关联重试已完成实现和 Redmi 收尾。已收敛 Run 只有在 `run.recovered -> run.status=CANCELLED` 链完整、恢复状态/处置码/证据指纹稳定、当前定义与来源 Profile 都未漂移时才能打开专用确认；确认后 UseCase 在创建新 Run 前再次读取 Room，Runtime 再核恢复契约。新 Run 记录来源 Run、来源 ToolCall、新 ToolCall 与定义指纹，重新审批且只执行一次，不做模型规划；旧 Run 不变。强制本地 `141/141` tasks（`2m 39s`）、JVM `707/707`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK 和 Release lintVital 通过；Debug/Release APK 为 `23,403,609 / 3,187,250` 字节，SHA-256 为 `f8595e8671da28b59b87fbe85b2732d481263f39c1df3b60d17e1df6276764e0 / 7593288da547e95782da1b45d7a7e660dbbcab6d8ffe77102dcf8022636c6a02`。仅 Redmi 的真实磁盘纵向单项为 `OK (1 test)`（`1.573s`），专用 Compose 对话框为 `OK (1 test)`（`2.306s`），默认完整 instrumentation 为 `OK (235 tests)`（`92.954s`）；未向模拟器发送安装或测试命令。当前文档第一次语料复验为 `OK (1 test)`（`2.405s`），写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`（`2.546s`）。正式 Release 已无损恢复，冷启动 `532ms`，版本、前台 Activity、PID、测试包卸载、保持唤醒关闭和空 crash buffer 均已核对。
- 通用执行恢复矩阵的“尚未提交安全重放资格”切片已完成。`ToolDefinition` 新增默认拒绝的 `ToolNotCommittedReplayPolicy`，只有同时声明 `IDEMPOTENT_BY_KEY + CONTROLLED_SAME_CALL + REQUIRE_CONFIRMATION` 的工具才可能进入资格评估；当前仅 `notes.create` 与 `memory.remember` 显式加入。Runtime 会在 proposed/validated 事件中冻结版本化恢复契约及 SHA-256 定义指纹，审批 requested/decided 事件也冻结同一指纹。资格还必须同时证明原 Profile 白名单一致、Tool Ledger 完整、链尾无 ToolResult/`TOOL_EXECUTE`、前序调用均成功验证、唯一审批已批准且 requested 为 `PENDING`、事件顺序为 validated→requested→decided、审批后没有新步骤。通过时只持久化 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE`，旧 Run 与活动 Step 仍收敛为 `CANCELLED`；本切片不重放工具、不恢复旧模型协程/Executor/Workflow，也不原地继续旧 Run。强制本地 `141/141` tasks（`3m 19s`）、JVM `694/694`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 和 v2 正式单签名通过；Debug/Release APK 为 `23,387,225 / 3,187,250` 字节，SHA-256 为 `9b298babd168842031ad5221b2b1c488d5bc7a2b2ee046efdad101ad9f468c97 / c861055daed1ff8cf3264439c1795ed4085fe17b2220fc1c405e67d963e1cbbe`。仅 Redmi 的 Room 磁盘重开单项为 `OK (2 tests)`（`0.783s`），解锁并保持唤醒后的默认完整 instrumentation 为 `OK (233 tests)`（`90.924s`），最终文档语料单项为 `OK (1 test)`；未使用模拟器。
- 通用执行恢复矩阵首个“提交状态未知”切片已完成。当前 Tool Ledger 只有同时具备 `tool.call.validated`、对应 `TOOL_EXECUTE` 持久化步骤且链尾缺少 ToolResult 时，才冻结 `COMMIT_UNKNOWN`；仅 proposed、仅 validated 但执行步骤尚未落库，以及账本/步骤不一致继续按 `RECOVERY_EVIDENCE_INVALID` fail-closed。启动收敛会把恢复分类、重试证据和证据边界一起写入 `run.recovered`：真正进入执行边界的旧 Run 需要确认后创建关联新 Run，尚未进入执行步骤的调用保持 `NOT_COMMITTED`，两者都不会重放工具、恢复旧模型协程或伪造 ToolResult。legacy typed event 也只在唯一链尾 validated 调用和执行步骤可同时核对时进入提交未知。强制本地 `141/141` tasks（`3m 11s`）、JVM `683/683`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 和 v2 正式单签名均通过；Debug/Release APK 为 `23,370,841 / 3,187,250` 字节，SHA-256 为 `d5470aa909bae8a93ff10bcb088ef9ce3b36bbec1da17caaf8ed3c001716b936 / cf0a2cc320bb7ebc6828850e271860ed775a5ebcdc65bc5e9be18e0c5b267dc3`。仅 Redmi `wsvwypiz7xwslvl7` 的两个 Room 恢复单项分别为 `OK (1 test)`（`0.592s / 0.507s`），默认完整 instrumentation 为 `OK (231 tests)`、耗时 `90.302s`；最终文档语料单项为 `OK (1 test)`。在线模拟器未被使用。
- 发布后有界对话框簇收尾已完成代码、本地门禁与 Redmi 验收：Agent Run 重试、Workflow Run 重试、长期记忆编辑/删除和本地 Skill 删除分别迁入 `ui/agenttask`、`ui/workflow`、`ui/memory`、`ui/agentskill`，状态与动作通过各自 contract 投影，弹层仍由应用根全局挂载。备份恢复、全局通知、Android 文件选择器和跨页面导航继续留在 composition root。`XiaoLingApp.kt` 从 `1,103` 行降到 `817` 行；JVM `678/678`、Lint、Debug/AndroidTest/R8 Release APK 和 Release lintVital 已通过。仅 Redmi `wsvwypiz7xwslvl7` 的新增对话框聚焦测试为 `OK (7 tests)`、测试耗时 `9.247s`；默认完整 instrumentation 为 `OK (229 tests)`、测试耗时 `89.151s`；最终文档重新打包后的项目语料单项为 `OK (1 test)`。未向在线的 `emulator-5554` 发送安装或测试命令。
- 小灵 `v0.1.13` 发布门禁通过：强制 Gradle `141/141` tasks、JVM `678/678`、Lint `0 error / 51 warnings`，Debug、AndroidTest、R8 Release APK 和 Release lintVital 均成功。Release 为 `0.1.13 (14)`、`3,170,866` 字节，SHA-256 `b6726cd080d0bd604726b5d77259311e855d2403110053fe41d0c851bd328fe8`，zipalign 和 APK Signature Scheme v2 单签名者均验证通过。仅 Redmi `wsvwypiz7xwslvl7` 执行默认完整 instrumentation，结果 `OK (222 tests)`、耗时 `82.798s`；未向 Pixel_9 或其他模拟器发送 ADB 命令。
- 最终 README/docs 重新打入 AndroidTest APK 后，仅在 Redmi 复跑项目文档语料门禁为 `OK (1 test)`；测试包随后卸载，主应用恢复前台。
- Agent 启动前校验协调迁出完成聚焦 JVM `10/10`，另有运行配置字符串脱敏 `1/1`；覆盖五类入口错误优先级、当前/历史 Profile 来源、旧 Run 空快照回退、冻结请求配置，以及 Base URL/API Key/自定义 Header 脱敏。强制本地门禁 `140/140` tasks 在 `2m 5s` 内通过：JVM `656/656`，Lint `0 error / 50 warnings / 0 information`，Debug/Release/AndroidTest APK 成功。仅 Redmi `wsvwypiz7xwslvl7` 默认完整 instrumentation 为 `196` 条（`184 passed / 12 skipped / 0 failed`），最终测试耗时 `48.8s`；最终文档语料单项为 `OK (1 test)`。Debug APK 为 `23,190,389` 字节、SHA-256 `1633449fdfe317340da8b72e29e698262fde4cae381c8ccfb5706c4db34ffb52`；Release APK 为 `15,950,806` 字节、SHA-256 `00a0170be4fe2ac8e794340f63319f5429df6c3aa9eacc9dbea6fc21ee832e46`。Room v32、Shadow、第 101/102 项和设备后台门禁不变，未使用 Pixel_9。
- Provider 模型同步协调迁出完成聚焦 JVM `8/8`，覆盖请求规范化、模型去重、无效 URL、稳定失败分型、取消传播、配置漂移、Provider 删除、批量顺序/失败继续和并发提交串行。完整 JVM `645/645`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK 已通过；仅 Redmi `wsvwypiz7xwslvl7` 默认完整 instrumentation 为 `OK (196 tests)`、耗时 `49.373s`，最终文档语料单项为 `OK (1 test)`。Room v32、Shadow、第 101/102 项和设备后台门禁不变，未使用 Pixel_9。
- 候选记忆协调迁出完成聚焦 JVM `7/7`，覆盖有界读取、普通聊天/Agent 来源、无候选与存储失败分型、接受/拒绝路由、同 ID 并发、无关候选并行和取消后可重试。强制本地门禁 `140/140` tasks 在 `2m 23s` 内通过：JVM `637/637`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 成功；仅 Redmi `wsvwypiz7xwslvl7` 默认完整 instrumentation 为 `OK (196 tests)`、耗时 `49.633s`，最终文档语料单项为 `OK (1 test)`。Debug APK 为 `23,174,005` 字节、SHA-256 `4992185a39ae9844b171e51126dfbef2d97d2ce06d55edcf123bd85d5cb2007c`；Release APK 为 `15,934,422` 字节、SHA-256 `0cb3df07f601fe8cde4acb74346fd7c18eb47ffab55276e3cf4fab552fde5aab`。Room v32、Shadow、第 101/102 项和后台门禁不变，未使用 Pixel_9。
- 恢复后 Agent 审批协调迁出完成聚焦 JVM `6/6`，并发锁忙以 `Busy` 保留另一会话的可重试卡片；Room 原子拒绝契约已进入默认真机套件。强制本地门禁 `140/140` tasks 在 `1m 51s` 内通过：JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 成功；仅 Redmi `wsvwypiz7xwslvl7` 默认完整 instrumentation 为 `OK (196 tests)`、耗时 `49.015s`，最终文档语料单项为 `OK (1 test)`。Debug APK 为 `23,157,621` 字节、SHA-256 `4579b5bc821bd721b77a76b3110b0451f852b9c8f84f528fa824efc8cc801e4f`；Release APK 为 `15,918,038` 字节、SHA-256 `8fb7d53170a7bff05218b0d4cced8a47dc550bb29bac7dea8278ec0b7e44c6ef`。本轮未采集 Shadow 样本，未使用 Pixel_9。
- 第 101 项首个持续观察窗口只采集 `1` 条真实样本：Run 于 `2026-07-26 10:15:53` 开始、`10:16:04` 完成，`knowledge.search` 返回 `3` 个词法兜底候选，答案展示“本地知识包含直接回答”和 `知识引用 · 3`。本进程摘要为样本/完成/Judge `1/1/1`，取消、异常、未知、跳过及旁路错误均为 `0`；耗时 `5009ms`、TTFB `5002ms`、Prompt `10150B`、Tokens `2720/209/2929`。第 97 至 101 项已记录窗口人工合计为样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`，成本 `43846ms / 43777ms / 66995B / 17164+1822=18986 Tokens`；这不是跨进程 tracker 或 Room 数据。八次 Judge 均未出现自然网络、协议或认证失败，继续固定 Room v32、`store=null / persistenceMode=NONE`、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。本轮强制完整门禁为 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (195 tests)`；Debug APK 为 `23,141,237` 字节，SHA-256 `dc61bbec47e688ea19dea572e9dca5b5d04a4c7ed8a7f0c1efa4b328769f22ca`。
- Agent Run 关联重试协调迁出新增聚焦 JVM `7/7`：覆盖无需确认、写工具副作用确认、同码证据指纹漂移、原 USER 附件恢复、旧 Run 不变、`retryOfRunId`、附件读取失败、请求/确认失效和取消。完整本地门禁为 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK；仅 Redmi 默认完整 instrumentation 为 `OK (195 tests)`、耗时 `48.619s`，最终文档语料单项为 `OK (1 test)`。该横向工程未采集 Shadow 样本，也未扩展 Agent Runtime、工具或后台权限。
- 第 100 阶段系统分享入口 v1 已完成聚焦 JVM `7/7` 与 Redmi `OK (4 tests)`：验证 Manifest 只暴露五种单项 MIME 且不解析 `ACTION_SEND_MULTIPLE`，双来源不同 URI 按多图拒绝，冷/热启动与 Activity 重建不会自动发送或重复投影，外部伪造“已处理” extra 不能跳过导入，PNG 继续走既有附件读取，草稿冲突需显式确认，导入提示在编辑、移除、图片失败和切换会话后清理。完整门禁为 JVM `607/607`、Lint `0 error`（`50 warnings / 1 hint`）、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `195` 条（`183 passed / 12 skipped`）；该入口不扩展到多附件、文档、任意 MIME、自动发送、Workflow 或后台处理。
- 第 99 阶段在 Redmi 完成首批低频真实前台观察：导入当前 README 后因 Embedding Provider 在该窗口不可用而使用词法兜底，三条精确查询均形成有效候选和 Judge measurement，判定为直接回答 `2`、部分回答 `1`。关闭开关并删除 `4` 个测试会话后，notice 从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3`；临时知识文档与下载文件已删除，恢复知识文档 `0`、原会话 `ping` `1`。第 97 至 99 阶段记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次仍未出现自然网络、协议或认证失败；完整 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)` 通过。Room v32、`store=null / persistenceMode=NONE` 与两层 enforcement 关闭不变。
- 第 98 阶段完成 Redmi 真实前台扩样本：累计 `6` 条 Shadow 样本中完成 `4`、无候选跳过 `2`，Judge `4` 次均无取消或异常，判定分布为直接回答 `2`、部分回答 `2`。关闭开关并删除测试会话后，notice 从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`；测试知识文档已删除，恢复为知识文档 `0`、保留原会话 `1`。完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)` 通过。`store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false` 均未改变。
- 第 97 阶段已完成 answerability shadow 真实前台样本与进程内遥测：统计固定上限且重启清空，重试链保留每次失败分类，设置页展示成本、失败和 notice 生命周期；`store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false` 均未改变。
- 本地完整门禁为 JVM `600/600`、Lint `0 issue`、Debug APK 与 AndroidTest APK 构建成功；仅在 Redmi `wsvwypiz7xwslvl7` 执行设置页定向 `OK (1 test)` 和默认完整 instrumentation `OK (191 tests)`。真实前台 `/agent + knowledge.search` 得到 `1` 条完成样本：Judge `1` 次、耗时 `8437ms`、TTFB `8428ms`、Prompt `8952B`、输入/输出/总 Tokens `2340/361/2701`，失败、取消和异常均为 `0`；UI 显示“本地知识包含直接回答”和 `知识引用 · 3`。删除测试会话后 notice 从 `发布 1 / 当前有效 1 / 已裁剪 0` 变为 `发布 1 / 当前有效 0 / 已裁剪 1`；开启状态下普通聊天完成后样本仍为 `1`。验收后开关已恢复关闭，全程未使用 Pixel_9。
- 第 96 阶段已完成默认关闭的生产接线：`OpenAiKnowledgeAnswerabilityJudge` 固定 Responses 非流式协议，逐请求关闭全部 HTTP Debug 日志；identity 从当前 `providerId / model / Base URL fingerprint` 派生并与冻结的 `redmi-provider-compatibility / gpt-5.5` 身份完全匹配后才请求。前台直接 Agent 先展示并保存答案，保存成功后 sibling Job 才启动 Judge；保存失败、取消、Workflow 来源或 Judge 终败都只跳过旁路，不发布猜测 notice。notice 仅以进程内 `messageId` 映射传给知识引用区域，`store=null / persistenceMode=NONE`，不改写消息、引用或生产拒绝。
- 第 96 阶段本地门禁为完整 JVM `593/593`、Lint、Debug/AndroidTest APK 通过；仅在 Redmi `wsvwypiz7xwslvl7` 执行默认完整 instrumentation，结果 `OK (191 tests)`，另有真实生产 adapter `OK (1 test)`、设置/偏好/notice 定向组合 `OK (3 tests)`。冻结身份下的真实 calibration/validation 复验各 `12` 条，网络/解析失败均为 `0`；覆盖率特征族仍未通过，`minimumConfidence=0.85` 不变。没有使用 Pixel_9。
- Redmi 上一正式发布版的收尾基线已复核为 Room `v32`；当前源码与本轮 Debug 验收已升级为 Room `v33`。测试结束后仍需恢复固定正式 `v0.1.13`，因此设备最终发布态与当前开发 Schema 必须分开记录，不能把旧正式版状态误写成新账本已发布。`answerability_shadow_enabled` 偏好文件不存在时继续按代码默认值保持关闭。
- 第 93 阶段已完成答案可回答性 shadow 呈现的离线实现和 Redmi 真机验收：纯 Kotlin 策略把 `ACCEPT / REJECT / UNKNOWN` 翻译成直接回答、部分回答、未回答、矛盾、证据无法回查、低于冻结门禁和未知等用户提示；`KnowledgeReferencesContent` 的提示与原引用共存，`enforcementApplied=false`。新增 UI 断言 `KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已在 Redmi 全量回归中通过。
- 第 92 阶段真实 `gpt-5.5` Judge 探针已在 Redmi `wsvwypiz7xwslvl7` 通过：校准/验证各 `12` 条观测，网络/解析失败均为 `0`；`VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE` 达到预注册标准，覆盖率特征族未通过，生产 enforcement 仍为 `false`。该阶段默认 Redmi instrumentation 为 `OK (188 tests)`（`177 passed / 11 skipped / 0 failed`），收尾基准耗时 `49.641s`；探针耗时 `94.154s`，数字保留为历史基线。
- 收尾后通过应用设置页恢复未跟踪 `AGENTS.md` 的兜底 Provider 和 6 个可用模型，默认 Agent Profile 绑定 `gpt-5.5`；真实普通消息 `ping` 返回 `pong`，耗时 `2.44s`。`MainActivity` 前台、crash buffer 为空，设备 Agent 保持默认关闭/未授权状态。配置端点与密钥未写入仓库；仅使用 Redmi，没有连接或启动 Pixel_9。
- 生产 `Room`、检索、答案链路和 answerability enforcement 继续保持隔离；`productionEnforcementEnabled=false`。
- Debug 请求日志继续脱敏附件、Authorization 和原始/加密推理内容；默认 User-Agent 保持正确。
- APK 元数据：包名 `com.longdev.xiaoling`，应用展示名「小灵」。

## 文档

- [文档索引](docs/README.md)
- [产品需求](docs/requirements.md)
- [个人 Agent 路线图](docs/personal-agent-roadmap.md)
- [参考项目分析](docs/reference-apps-analysis.md)
- [当前实现说明](docs/implementation-notes.md)
- [答案可回答性 shadow 绑定契约](docs/answerability-shadow-binding.md)
- [当前验证报告](docs/verification-report.md)
- [验证历史：基线至第 101 阶段](docs/verification-history/verification-baseline-through-stage-101.md)

## 产物

- 本地 debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 本地 release APK：`app/build/outputs/apk/release/app-release.apk`
- `outputs/` 目录不纳入版本控制。
