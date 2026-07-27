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
  - 进程恢复后的链尾审批已由独立 `RecoveredAgentApprovalCoordinator` 编排：每次决定都重新读取 Room detail 并复用 `AgentRunResumePolicy` 核验唯一链尾证据，批准前先恢复原 USER 附件，重复批准/拒绝由一次性互斥门禁拒绝。另一会话的恢复审批占用门禁时返回 `Busy`，当前 `PENDING` 卡片保持可重试；附件或前置能力失败且审批仍为 `PENDING` 时也会恢复卡片。拒绝在一个 Room 事务中原子收敛 Approval、审批 Step 与原 Run，避免半状态。普通前台审批仍由独立 `AgentApprovalDecisionCoordinator` 管理 waiter，两条边界不合并。
  - 候选记忆的列表、成功回合采集和接受/拒绝已由独立 `AgentMemoryCandidateCoordinator` 编排：普通聊天与 Agent Run 使用稳定来源身份，同一候选 ID 的并发决定返回 `Busy`，不同候选可以并行；失败和取消都会释放 claim。关闭候选开关会取消旧列表读取，避免迟到 Room 结果重新填充界面。敏感过滤、去重、冲突、事务与正式记忆检索继续由既有 Room Store/Manager 负责。
  - Provider 模型同步已由独立 `ProviderModelSyncCoordinator` 编排：单项与批量同步统一 URL 校验、请求规范化、模型去重与当前模型回退；批量严格按列表顺序执行，普通失败继续下一项，取消立即终止。网络请求可以并行，但完整 Provider 快照通过提交互斥串行落库；保存前后都会拒绝已删除或身份漂移的迟到结果，成功必须以 Room 持久化完成为准。ViewModel 只保留忙碌态、逐项结果和弹窗投影。
  - Agent 启动前校验已由独立 `AgentLaunchPreflightCoordinator` 编排：普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批统一执行会话、Profile、工具注册与 Provider 校验。普通 `/agent` 仍可在没有当前会话时创建会话；其余入口要求原会话存在。恢复审批优先使用原 Run 的 Profile 快照，旧 Run 没有有效快照时才回退当前选中 Profile；其他入口继续使用当前 Profile。校验只冻结本次进程内运行配置，不写 UI、Room 或日志；运行配置自身的字符串表示会脱敏 Base URL、API Key 与自定义 Header。
  - 第 101 项已完成首个间隔真实使用窗口：仅在 Redmi 前台直接 `/agent` 中显式开启 Shadow，使用词法兜底命中的 `Agent Run retryOfRunId` 本地知识候选形成 `1` 条完成样本，Judge 判定为直接回答。删除测试会话和临时知识文档后，notice 有效数由 `1` 归零且裁剪数变为 `1`，Shadow 已恢复关闭；该项继续保持低频观察，不增加 Room Store、跨进程 notice 或 enforcement。
  - 第 100 阶段新增 Android 系统分享入口 v1：分享面板只接收单项 `text/plain` 或单张 PNG/JPEG/JPG/WEBP 图片，文本最多 20,000 字符，图片必须是 `content://` 且继续复用现有 8 MB、MIME、签名和解码校验。`EXTRA_STREAM` 与 `ClipData` 同时携带同一 URI 时按单图兼容，URI 不同时按多图拒绝。内容只进入可编辑的新会话草稿，永不自动发送；已有草稿、附件或活动操作时必须显式“打开分享/忽略分享”，第二个未决分享不会覆盖第一个。冷启动初始化、热启动 `onNewIntent` 和 Activity 重建均有独立处理；来源统一标为外部分享，不信任可伪造的 referrer 或 Intent extra。
  - 第 99 阶段完成首批 Redmi 低频 answerability shadow 观察：同一进程新增 `3` 条有效 Judge 样本，直接回答 `2`、部分回答 `1`，Judge 取消、异常和旁路错误均为 `0`；本批累计耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。首次宽英文检索连续无候选并使 Agent Run 达到工具步数上限，但没有进入 Shadow，不能记作 Judge 失败。
  - 第 98 阶段已在 Redmi 同一进程内扩充用户显式开启的真实前台 answerability shadow 样本：累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次形成 `2` 条直接回答与 `2` 条部分回答；自然 `BUDGET_EXHAUSTED` Run 未进入 Shadow，不能记作 Judge 失败。累计成本为耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`，取消和异常均为 `0`。
  - 第 97 阶段已为默认关闭的 answerability shadow 增加有界进程内样本摘要：只记录 Judge attempt、延迟/TTFB、Prompt 字节、Tokens、失败分类和 notice 生命周期，不记录问题、答案、候选正文、引用、原始响应或凭据。真实 Redmi 前台 Agent 样本已验证答案先保存、Judge 后置、notice 可见且随会话删除裁剪；普通聊天、Workflow 和后台 Worker 不进入样本分母。
  - 设备 Agent 已接入 `device.snapshot / open_app / back / home / tap_ref / type_text / swipe`：仅在用户独立开启、系统 Accessibility 已授权、前台直接 `/agent` 且 Profile/Skill 允许时可用；打开应用、点击和输入必须审批，所有动作完成后重新观察并验证，Workflow 与后台运行不会看到或执行任何设备工具。

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
  - Provider、会话、消息及 Text/Reasoning/Image/Document/Tool parts、Agent Run、笔记、长期记忆、Skill、Workflow Ledger 和 ScheduledTask 使用 Room 保存；附件原始字节写入 BLOB 并随数据库备份，旧 SharedPreferences 数据首次启动时迁入。
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
9. 如需使用设备 Agent，在「设置 -> 设备 Agent」明确开启应用开关并完成系统无障碍授权，再为 Agent Profile 选择只读的 `device-observation` 或有限动作的 `device-control` Skill。当前只允许小灵、系统计算器、时钟和系统设置等首批白名单应用；设备工具仍不能进入 Workflow 或后台自动化。

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
- Redmi 收尾状态已复核：Room `v32`、Provider/Profile 各 `1` 条，Provider ID/模型为 `redmi-provider-compatibility / gpt-5.5`，Keystore IV/密文均非空；测试包已卸载，主 `MainActivity` 前台运行，AccessibilityService 为 Enabled/Bound，crash buffer 未命中本应用崩溃。`answerability_shadow_enabled` 偏好文件不存在时按代码默认值保持关闭。
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
