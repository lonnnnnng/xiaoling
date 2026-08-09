# 验证报告

验证日期：2026-08-10（北京时间）

## 当前验证基线

## 2026-08-10 第 226 阶段：Skill 草稿发送、审批与本地笔记真实前台闭环

### 结论

- 第 225 阶段生成的 Skill 草稿只有在用户明确点击发送后才创建真实 Agent Run；设置页/示例页点击本身不执行任务。
- Redmi `wsvwypiz7xwslvl7` 的 Run `run-b2823b2d-e56a-4931-807d-78c769dc51ef` 记录了动态 Profile 的 `PROFILE_SELECTED` 和 `skill.selected=local-notes@...`，唯一高风险调用为 `notes.create`。
- 用户在真实审批卡点击“批准执行”后，审批为 `APPROVED`，ToolResult 为 `success=true / executorVerified=true / PASSED`，执行回执为 `COMMITTED`；当前会话 Tool Message 与 Run Ledger 的参数、结果和 `VERIFIED` 状态一致。
- 清理按执行回执中的稳定 note ID 删除临时笔记，移除临时 Profile/会话并恢复原选择；Run、Approval、Tool Ledger 审计保留，未清理用户原有数据。

### 验证证据

- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功；分段 instrumentation `prepareMinimalProfileAndDedicatedConversation`、`sendSkillDraftAndStopAtApproval`、`auditApprovedRunAndExactCleanup` 均为 `OK (1 test)`，耗时 `3.297s / 109.099s / 3.116s`。
- send 段显式传入 `stage226Manual=true stage226HoldForApproval=true`，只在 Redmi 前台保留审批窗口；用户点击后日志输出 `STAGE226_AUDITED ... approval=APPROVED verification=PASSED receipt=COMMITTED` 和 `STAGE226_CLEANUP ... runPreserved=true`。
- Debug 主 APK 与测试 APK只向 Redmi 覆盖安装；未向 Pixel_9 或其他模拟器发送命令。未运行完整 JVM、Lint、Release 或全量 instrumentation，符合快速迭代分级验证约束。

### 下一阶段

第 227 阶段验证进程重启后的 `WAITING_APPROVAL` 恢复展示、同一 Approval 身份校验和批准后原地恢复；不扩展到后台自动化或 Release。

## 2026-08-10 第 225 阶段：Agent Skill 试用真实应用壳闭环

### 结论

- 真实 `MainActivity` 已从设置根页进入 `Agent Skills`，读取 Redmi 当前选中的 Profile、会话和 Skill Store，并选择已授权 SAFE `conversation-recall` 的首条示例。
- 点击后回到对话根页，输入框为规范 `/agent ...` 草稿；选中 Profile `agent-profile-default`、选中会话、当前会话消息和最近 100 条 Run 完整详情均未变化。
- 没有自动发送、调用模型、创建 Run、执行工具或触发审批；没有写入测试 Profile/Skill，也没有清理用户数据或 Provider 配置。
- 生产 Tool/Skill、Room v36、权限、审批、Workflow、后台能力和答案可回答性 Shadow 均未改变。

### 验证证据

- `:app:compileDebugAndroidTestKotlin` 与 `:app:assembleDebug :app:assembleDebugAndroidTest` 成功。
- 仅 Redmi `wsvwypiz7xwslvl7` 运行 `Stage225SkillTryUiInstrumentedTest#currentSafeSkillExampleOnlyPrefillsAgentDraftWithoutCreatingFacts`，结果 `OK (1 test)`、`7.624s`。
- 设备日志输出：`STAGE225_SKILL_TRY profileId=agent-profile-default skillId=conversation-recall conversationUnchanged=true messagesUnchanged=true runsUnchanged=true promptPrefilled=true autoSent=false`。
- 首轮测试暴露设置入口和 Skill 示例位于屏幕外时直接点击无效，以及真实设置壳合并子树 semantics；最终测试以稳定入口/列表/输入标签、真实滚动和 unmerged tree 收敛，未增加测试夹具业务数据。
- Debug 主应用和测试 APK 均只向 Redmi 覆盖安装；未向 Pixel_9 或其他模拟器发送命令。未运行完整 JVM、Lint、Release 或全量 instrumentation，符合快速迭代分级验证约束。

### 下一阶段

- 第 226 阶段只在用户明确发送试用草稿后验证 Run 创建、正式 Skill 选择和逐次审批；Skill 管理页点击继续不得自动执行。

## 2026-08-10 第 224 阶段：Agent Skill 直接试用入口

### 结论

- Skill 管理页展开项新增最多 3 条去重、非空试用示例；按钮资格绑定 Skill 启用、当前 Agent Profile 的 Skill/工具白名单和当前 Registry 工具集合。
- 页面 action 只提交稳定 Skill ID 与示例，应用壳按最新状态二次核对；通过后仅关闭个人任务模式、预填规范 `/agent ...` 草稿并回到对话根页。
- 点击不会调用 `sendMessage()`、模型或工具，不创建 Run，也不改变后续逐次审批。状态漂移、陈旧示例、未授权 Skill、工具缺失或重复 Skill 身份均 fail-closed。
- 生产 Tool/Skill 定义、Room v36、权限、Workflow、后台能力和答案可回答性 Shadow 均未改变。

### 验证证据

- 聚焦 JVM：`AgentSkillTryPolicyTest 4/4 + AgentSkillManagementProjectionTest 4/4 + XiaoLingNavigationCoordinatorTest 9/9`，合计 `17/17`，无失败或跳过。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功，最终一轮耗时约 `8s`。
- 仅 Redmi `wsvwypiz7xwslvl7` 运行 `AgentSkillManagementPageInstrumentedTest`，结果 `OK (4 tests)`、`6.085s`；覆盖稳定 ID action、最多 3 条去重示例、刷新重排和平台回调。
- 更新后的项目文档 corpus gate 首轮为 `OK (1 test)`、`3.172s`。
- Debug 主应用和测试 APK 均只向 Redmi 覆盖安装；主应用用户数据保留，文档门禁后卸载测试包。未向模拟器发送安装、测试、日志或 UI 命令。
- 未运行完整 JVM、Lint、Release 或全量 instrumentation，符合快速迭代分级验证约束。

### 下一阶段

- 第 225 阶段已完成上述真实应用壳闭环；试用草稿仍由用户决定是否发送。

## 2026-08-10 第 223 阶段：受控单日全天日程真实前台闭环

### 结论

- 仅在 Redmi `wsvwypiz7xwslvl7` 当前 Provider 下，人工输入自然语言目标并在审批卡核对后点击“批准执行”；测试代码没有代替模型规划、审批点击或答案级页面查看。
- Run `run-7614212d-ebf7-4bbd-8be9-c3196b9a3e4b`、ToolCall `tool-call-15700c37-2932-424a-91b0-05e9a20bf312` 最终为 `COMPLETED / APPROVED / PASSED / COMMITTED`；稳定事件 ID 为 `calendar-90`。
- 答案级“查看日程”已真实点击，当前 Provider 页面显示标题 `stage223_all_day_1786293137009`、开始日期 `2026-08-15`、全天“是”、时区 `UTC`、重复“否”。
- 旧 Run `run-73b6e1ca-2b73-4a39-a517-e2461afa5c43` 的完整详情摘要保持不变；`calendar-90` 按精确 ID 删除，临时 Profile/会话清理并恢复原选择，新 Run、Approval、Tool Ledger 审计保留。

### 验证证据

- `:app:compileDebugAndroidTestKotlin` 与 `:app:assembleDebugAndroidTest` 成功。
- Redmi prepare 单项通过；audit 输出 `STAGE223_AUDITED ... eventId=calendar-90 status=COMPLETED approval=APPROVED verification=PASSED receipt=COMMITTED navigationBound=true providerCurrent=true oldRunUnchanged=true`，结果 `OK (1 test)`、`0.688s`。
- Redmi cleanup 输出 `STAGE223_CLEANUP ... runPreserved=true eventId=calendar-90 exactEventRemoved=true temporaryProfileRemoved=true conversationRemoved=true originalProfileRestored=true`，结果 `OK (1 test)`、`0.427s`。
- 所有 Android 命令只指定 Redmi；未清理主应用数据或 Provider 配置。未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，符合快速迭代分级验证约束。

### 下一阶段

- 第 224 阶段重新选择新的用户可体验个人 Agent 主线；不顺带开放多日、重复、参与人、提醒或后台日程。

## 2026-08-10 第 222 阶段：受控单日全天日程

### 结论

- 生产新增独立 `calendar.create_all_day_event(title, date)` 与 `calendar-create-all-day` Skill；只接受规范单日日期，旧定时创建 Skill/Profile 不自动扩权。
- Provider 写入固定为 UTC 当日零点、排他的次日 UTC 零点和 `ALL_DAY=1`；ToolCall ID 幂等、逐次审批、Executor 回读和提交后只读验证继续沿用既有日程创建边界。
- 已验证成功结果携带唯一稳定事件 ID，答案级导航同时绑定请求标题、日期和应用固定结果，再从当前 Calendar Provider 二次读取。
- 多日、重复、参与人、提醒、后台日程、Room v36 和旧 Run 均未改变。

### 验证证据

- TDD 首轮在 `CalendarEventWriteRequest.allDay` 缺失处按预期编译失败；实现后 `AgentSkillsTest + XiaoLingToolRegistryTest + CalendarNavigationTest` 共 `126/126` 通过。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功，耗时约 `34s`。
- 仅 Redmi `wsvwypiz7xwslvl7` 运行 `AndroidCalendarEventWriterInstrumentedTest#writableProviderCreatesReplaysAndVerifiesSingleDayAllDayEvent`，结果 `OK (1 test)`、`0.192s`；真实 Provider 回读 `ALL_DAY=true / timeZone=UTC / end=start+1day`，幂等重放与提交后验证通过。
- 测试事件按 Provider 返回的精确事件 ID 删除；测试包 `com.longdev.xiaoling.test` 已卸载，主 Debug 应用覆盖安装且用户数据保留。设备清单虽包含 `emulator-5554`，但未向模拟器发送安装、测试、日志、UI 或卸载命令。
- 未运行完整 JVM、Lint、Release 或全量 instrumentation。

### 下一阶段

- 第 223 阶段只在 Redmi 当前 Provider 下完成真实自然语言创建、人工审批、Tool Ledger、答案级当前日程查看和精确清理；不顺带开放其他高级日历字段。

## 2026-08-09 第 221 阶段：前台长期记忆安全删除真实闭环

### 结论

- 真实前台 Run `run-73b6e1ca-2b73-4a39-a517-e2461afa5c43` 在 Redmi 当前 Provider 下严格完成 `memory.search -> memory.get -> memory.delete`；人工审批为 `APPROVED`，三项 ToolResult 均 `PASSED`，删除回执为 `COMMITTED`，稳定 memory ID 为 `memory-ee8cc2f1-27c0-4756-91f6-804ddf2608cf`。
- 删除后从当前 Room/长期记忆页面核对目标不可见；临时 Profile、临时记忆、撤销文件和验收消息均清理，Run、Approval 与 Tool Ledger 审计保留。原清理夹具误把复用的原空会话当作临时会话，已恢复 `conversation-1786204146694` 为无消息“新会话”并恢复选中状态。
- 生产能力边界仍为前台 `DIRECT` Agent；Workflow、后台、Legacy Run、旧 Profile、Room v36 和答案可回答性生产拒绝均不变。

### 验证证据

- 构建：`:app:assembleDebugAndroidTest` 成功，耗时约 `22s`；本阶段未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation。
- Redmi 定向修复核对 `Stage221MemoryDeleteUiInstrumentedTest#repairOriginalConversationBoundaryAndVerifyRun` 为 `OK (1 test)`，耗时 `0.338s`；确认原会话为空、Run 仍为 `COMPLETED`，临时 Profile/记忆/撤销文件不存在。
- 测试包 `com.longdev.xiaoling.test` 已卸载，主应用和用户数据保留；所有 ADB 安装、instrumentation 和卸载命令均显式指定 Redmi，未向模拟器发送命令。

### 下一阶段

- 第 222 阶段回到个人 Agent 主线，选择下一个用户可直接体验的前台窄能力闭环；继续遵守分级验证约束，后台自动化、精确定时和远期生态能力保持后置。

## 2026-08-09 第 219 阶段：真实前台存储状态 Agent Run

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 读取当前选中 Provider，创建临时最小 Profile（只允许 `app.get_storage / storage-status`，关闭长期记忆），复用正式 `AgentRunUseCase` 执行自然语言存储查询。
- 真实 Run 为 `COMPLETED`；Tool Ledger 恰有一项 `app.get_storage` 结果，`success=true / verificationStatus=PASSED`，审批数为 0。工具结果和最终回答不包含 Provider URL、API Key、Profile 内部 ID、文件路径、应用数据、设备序列或应用包名。
- `RealProviderStorageStatusInstrumentedTest#foregroundAgentReadsCurrentStorageFactsOnly` 结果为 `OK (1 test)`，耗时 `13.46s`；测试 APK 完成后已卸载，主应用和用户数据保留，阶段 Run 审计保留。
- ADB 清单中的 `emulator-5554` 没有收到目标安装、测试或卸载命令。本阶段未运行完整 JVM、全量 Lint、Release APK 或全量 instrumentation；生产工具、Room v36、旧 Profile/Run、Workflow、后台和发布边界保持不变。

## 2026-08-09 第 218 阶段：前台只读存储状态闭环

- 新增 `app.get_storage`，由正式 `XiaoLingToolRegistry` 注入 `AndroidStorageStatusReader`，无参数、`SAFE`、`supportsBackground=false`；结果固定为总容量、可用空间和使用率三项，不包含文件名、路径、应用数据、Provider 配置或设备身份。
- 聚焦 JVM `XiaoLingToolRegistryTest` `82/82` 与 `AgentSkillsTest` `34/34`，合计 `116/116`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均构建成功。
- ADB 清单包含 Redmi `wsvwypiz7xwslvl7` 和 `emulator-5554`，但所有安装、instrumentation 和卸载命令都显式指定 Redmi。Redmi 单项 `AndroidStorageStatusInstrumentedTest#foregroundRegistryReadsCurrentStorageFactsOnly` 为 `OK (1 test)`，耗时 `0.222s`；测试包已卸载，主应用和用户数据保留。
- 本阶段没有向模拟器发送目标 ADB 命令，也未运行完整 JVM、全量 Lint、Release APK 或全量 instrumentation；Room v36、旧 Profile/Run、Workflow、后台和发布边界保持不变。

## 2026-08-09 第 217 阶段：真实前台电量/网络双状态 Agent Run

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 读取当前选中 Provider，创建临时最小 Profile（只允许 `app.get_battery / app.get_connectivity` 与两个对应 Skill，关闭长期记忆），复用正式 `AgentRunUseCase` 执行自然语言目标。
- 真实 Run 为 `COMPLETED`；Tool Ledger 恰有两项结果，分别为 `app.get_battery` 与 `app.get_connectivity`，两项均 `success=true / verificationStatus=PASSED`，审批数为 0。最终回答不包含 Provider URL、API Key、Profile 内部 ID、设备序列或应用包名。
- `RealProviderDeviceStatusInstrumentedTest#foregroundAgentReadsBatteryAndConnectivityFactsOnly` 结果为 `OK (1 test)`，耗时 `24.087s`；测试 APK 完成后已卸载，主应用和用户数据保留，阶段 Run 审计保留。
- 本阶段没有使用 Pixel_9 或其他模拟器，也未运行完整 JVM、全量 Lint、Release APK 或全量 instrumentation；生产工具、Room v36、旧 Profile/Run、Workflow、后台和发布边界保持不变。

## 2026-08-09 第 216 阶段：前台只读网络状态闭环

- 新增 `app.get_connectivity`，由正式 `XiaoLingToolRegistry` 注入 `AndroidConnectivityStatusReader`，无参数、`SAFE`、`supportsBackground=false`；结果固定为网络状态、网络类型和互联网可达性三项，不包含 SSID、IP 地址、运营商、Provider 配置或凭据。
- 聚焦 JVM `XiaoLingToolRegistryTest` `81/81` 与 `AgentSkillsTest` `33/33`，合计 `114/114`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均构建成功。
- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 安装 Debug 主包与测试包，运行 `AndroidConnectivityStatusInstrumentedTest#foregroundRegistryReadsCurrentConnectivityFactsOnly`，结果为 `OK (1 test)`，耗时 `0.261s`。断言确认三项输出存在且不泄露 Provider、API Key、设备序列或应用包名；测试包已卸载，主应用和用户数据保留。
- 本阶段没有使用 Pixel_9 或其他模拟器，也未运行完整 JVM、全量 Lint、Release APK 或全量 instrumentation；Room v36、旧 Profile/Run、Workflow、后台和发布边界保持不变。

## 2026-08-09 第 215 阶段：前台只读电池状态闭环

- 新增 `app.get_battery`，由正式 `XiaoLingToolRegistry` 注入 `AndroidBatteryStatusReader`，无参数、`SAFE`、`supportsBackground=false`；结果固定为电量百分比、充电状态和供电方式三项，不包含设备标识、应用列表、Provider 配置、电池温度或健康信息。
- 聚焦 JVM `XiaoLingToolRegistryTest` `80/80` 与 `AgentSkillsTest` `32/32`，合计 `112/112`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均构建成功。
- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 安装 Debug 主包与测试包，运行 `AndroidBatteryStatusInstrumentedTest#foregroundRegistryReadsCurrentBatteryFactsOnly`，结果为 `OK (1 test)`，耗时 `0.198s`。断言确认三项输出存在且不泄露 Provider、API Key、设备序列或应用包名；测试包已卸载，主应用和用户数据保留。
- 本阶段没有使用 Pixel_9 或其他模拟器，也未运行完整 JVM、全量 Lint、Release APK 或全量 instrumentation；Room v36、旧 Profile/Run、Workflow、后台和发布边界保持不变。

## 2026-08-09 第 214 阶段：Redmi 当前 Provider 驱动的 Agent Profile 隐私验收

- 第 212 阶段首次显式传入兜底 Provider 参数时出现 `ApiFailure: 无法解析服务器域名`；为区分外部兜底配置失效与手机当前配置，AndroidTest 新增 `agentProfileUseStoredProvider=true`，从 Redmi 当前选中 Provider 读取配置，不把 API Key 写入命令参数或日志。
- 仅在 Redmi `wsvwypiz7xwslvl7` 安装最新 AndroidTest APK 并运行 `RealProviderAgentProfileInstrumentedTest#foregroundAgentReadsOnlyAllowlistedProfileState`，结果为 `OK (1 test)`，耗时 `12.853s`。Run `run-b9186054-3f0c-405e-ba62-2afd9f4c75f7` 为 `COMPLETED`；唯一 `agent.get_profile` ToolResult 为 `success=true / verificationStatus=PASSED`，日志摘要为 `privacySafe=true`。
- 结果只包含 Agent 名称、`gpt-5.6-sol`、Responses API 和长期记忆状态；Provider URL、API Key、系统提示词、Profile ID 和工具白名单均未进入结果。HTTP 日志中的 Authorization 为 `***MASKED***`，User-Agent 为配置默认值。
- 测试包已卸载，主应用和 Room/Provider 数据保留；未使用 Pixel_9、未运行完整 JVM、Lint、Release 或全量 instrumentation。第 212 阶段真实 Provider 隐私验收现已闭环，下一步进入新的窄能力切片。

## 2026-08-08 第 213 阶段：当前应用信息只读验收

- Redmi `wsvwypiz7xwslvl7` 定向执行 `AndroidAppInfoInstrumentedTest#foregroundRegistryReadsCurrentPackageMetadataOnly`，结果为 `OK (1 test)`。
- 生产 `XiaoLingToolRegistry + AndroidAppInfoReader` 返回四项：应用名称、包名 `com.longdev.xiaoling`、版本名和版本号；断言确认 Provider、API Key、设备标识和安装来源均不在结果中。
- 本阶段只构建/安装 AndroidTest APK，完成后卸载测试包；没有使用 Pixel_9、完整 JVM、Lint、Release 或全量 instrumentation。第 212 阶段当时的显式兜底参数运行记录为网络阻塞，后续已由第 214 阶段使用手机当前 Provider 完成重跑。

## 2026-08-08 第 212 阶段：前台 Agent Profile 隐私验收探针（首次显式配置尝试）

- 新增 `RealProviderAgentProfileInstrumentedTest`，使用显式临时 Profile（仅 `agent.get_profile`）和随机会话 ID，复用正式 `AgentRunUseCase`、真实 `OpenAiCompatibleClient` 与 Room Run Repository；探针断言四项允许状态和全部敏感字段不可见。
- `:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功，测试 APK 已安装到 Redmi `wsvwypiz7xwslvl7`。未向 Pixel_9 或其他模拟器发送目标 ADB 命令。
- Redmi 单项测试已启动，但真实 Provider 请求失败于 `ApiFailure: 无法解析服务器域名`。主机 DNS 解析到 `198.18.0.245`，主机 `GET /models` 返回 `404`（服务可达）；Redmi `ip route` 只有 `10.10.14.0/30 dev tun0`，到 `1.1.1.1` 和 Provider 域名均不可达。该项暂记为“网络阻塞”，不记为通过，也不运行无关全量验证。
- 设置页手工核对：旧 `设备打开应用 E2E` Profile 的工具数仍为 2，未自动获得新工具；`默认 Agent` 的显式工具列表包含 `agent.get_profile`。下一步是网络恢复后重跑同一单项，成功后再进入 `app.get_info`。

## 2026-08-08 第 211 阶段：真实历史会话搜索、当前正文与答案级导航验收

- 仅在 Redmi `wsvwypiz7xwslvl7` 建立唯一历史夹具 `conversation-stage211-target-20260808 / stage211_history_target_20260808`，含用户正文 `stage211_room_user_marker_20260808` 和助手正文 `stage211_room_assistant_before_20260808`；专用验收会话为 `conversation-stage211-agent-20260808`。现有 Profile 临时收窄为 `app.list_conversations / app.search_conversations / app.get_conversation`、`conversation-detail` 和长期记忆关闭。
- 初次准备测试虽在 Room 断言选中验收会话，但仍存活的旧前台 ViewModel 随后把原选择写回；强停主应用后重跑幂等准备单项，专用会话稳定选中，未重复创建夹具或覆盖原恢复快照。两次准备单项本身均为 `OK (1 test)`。
- 首条真实 Run `run-4fae0edb-af9a-437b-836e-c8ca95ffaf00` 为 `COMPLETED`，选择 `conversation-detail@1`，严格执行 `app.search_conversations(limit=10, query=stage211_history_target_20260808) -> app.get_conversation(conversation-stage211-target-20260808)`；两项 ToolResult 均 `success=true / verificationStatus=PASSED`，审批数为 0。搜索结果同时包含当前验收会话和历史目标，因为当前用户消息本身含同一 marker；详情仍正确读取目标的两条 Room 正文。
- 生产修复为 `AgentConversationStore.search(query, limit, excludeConversationId)`、Registry 传入当前 RunContext 会话 ID、Room Store 在排序和截断前排除该 ID。聚焦 JVM `conversationSearchFindsOldConversation + conversationSearchExcludesCurrentRunConversationBeforeApplyingLimit` 为 `2/2`，`:app:assembleDebug :app:assembleDebugAndroidTest` 成功。
- 修复后 Run `run-25bd9d0a-90a9-41b2-adbb-1cca0ddd62ab` 同样为 `COMPLETED` 并选择 `conversation-detail@1`；搜索结果只包含唯一历史目标，随后 get 原样使用同一稳定 ID。两项 ToolResult 继续为 `success=true / PASSED`，审批数为 0。
- Run 审计后把目标助手正文改为 `stage211_room_assistant_after_20260808`。对话页历史 get Tool 卡仍显示冻结的 `before`；点击其“查看会话”后，目标页面显示用户 marker 和当前 Room 的 `after`。导航审计 `OK (1 test)` 确认选中 ID 已切到目标、`before` 不可见、修复后 Run 仍为 `COMPLETED` 且没有新建 Run。
- 准备、真实 Run/正文变化审计、答案导航审计和精确清理四个独立单项均为 `OK (1 test)`。清理后目标/验收会话、临时 Profile、快照、截图/XML、本机只读数据库副本和临时测试源码已删除，原 Profile 与有效会话选择恢复；两条真实 Run、Skill 选择和 Tool Ledger 审计保留。
- 最终文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。没有向 Pixel_9 或其他模拟器发送目标 ADB 命令；未运行完整 JVM、Lint、Release 或全量 instrumentation，也未主动 push。Room v36、权限、Workflow、后台、Shadow 和发布边界保持不变。

## 2026-08-08 第 210 阶段：真实前台系统日程删除、当前不可见与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台链路。阶段外使用正式 Calendar writer 创建唯一一次性夹具 `calendar-88 / stage210_calendar_delete_20260808 / 2026-08-14 10:20–10:50 / Asia/Shanghai`，事件指纹为 `calendar-event-v1-7039017f961da7c6d64409f73c562cf0dbf985d4fcb657509c284fb627c80996`；专用 E2E Profile 临时收窄为 `calendar.search_events / calendar.get / calendar.delete_event`、`calendar-delete` Skill 和长期记忆关闭。
- 准备重试发现阶段前 `selectedConversationId` 指向已经删除的第 209 阶段会话。临时验收夹具没有继续保存悬空身份，而是只接受当前 Room 仍存在的最近会话 `conversation-answer-reference-e2e` 作为恢复目标；随后重新选择第 210 阶段专属会话，未创建第二个事件或改写旧 Run。
- 在前台输入完整短指令 `/agent calendar delete stage210_calendar_delete_20260808`。Run `run-fa9e0a15-db83-4db6-8919-501566d60ebf` 为 `COMPLETED`，明确选择唯一 `calendar-delete`，严格执行 `calendar.search_events -> calendar.get -> calendar.delete_event`；搜索参数原样为唯一关键词，详情与删除绑定同一 `calendar-88`，删除继续原样使用当前指纹和 `scope=event`。
- 唯一 `calendar.delete_event` 审批为 `APPROVED`；三项 ToolResult 均 `success=true / verificationStatus=PASSED`，删除结果为 `executorVerified=true / replaySafety=RESTART_REQUIRED / receiptStatus=COMMITTED / receiptOperationId=calendar-88`。删除后当前 Calendar Provider 回读为 NotFound。
- 删除成功卡本身没有制造已不存在资源的导航。点击删除前搜索结果保留的“查看日程”后，详情页重新查询当前 Provider 并显示“当前日程已不存在或已被删除”，没有回放历史标题、时间或 Tool 正文。
- 精确清理 instrumentation 删除阶段会话，恢复原 Profile 的 `calendar.list_events / tasks.list`、空 Skill、长期记忆关闭和有效原会话 `conversation-answer-reference-e2e`；事件保持不可见，Run/审批/Tool Ledger 审计保留。临时测试源码、数据库快照、本机副本、截图/XML 和测试包均已移除，主应用恢复前台。
- 本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更，也没有向 Pixel_9 或其他模拟器发送目标 ADB 命令。按分级验证约束，仅构建 `:app:assembleDebugAndroidTest`，运行准备、删除审计、精确清理和文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，四项均为 `OK (1 test)`；未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 209 阶段：真实前台系统日程修改、查看与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台链路。专用 E2E Profile 在正式 Run 前临时配置为 `calendar.search_events / calendar.get / calendar.update_event`、`calendar-update` Skill 和长期记忆关闭；唯一夹具事件为 `calendar-85`，应用 marker 为 `xiaoling://calendar-event/stage209-calendar-update-fixture-20260808`。
- 首次 Run `run-9edc1948-884e-4652-b961-c4f596af2767` 因 ADB 长文本没有完整进入输入框，只执行 `calendar.search_events -> calendar.get` 后以 `COMPLETED` 收敛，没有审批或写入；该旧 Run 保持原样，未被后续尝试覆盖。
- 第二条 Run `run-94aab56a-05f5-4025-a24e-cf97f93d8eaf` 完整执行 `calendar.search_events -> calendar.get -> calendar.update_event`，审批 `APPROVED`，三项结果均 `success=true / verificationStatus=PASSED`，修改结果为 `executorVerified=true / replaySafety=RESTART_REQUIRED / receiptStatus=COMMITTED`。事件从原夹具更新为 `stage209_calendar_20260808_after / 2026-08-12 11:20–12:00 / Asia/Shanghai`，新指纹为 `calendar-event-v1-6fb258ec8d7f849217667110cfc6af3289834d8feb5d3a909317712ea5377845`。该目标没有连续的 `calendar update` 关键词，因此真实审计中没有 `skill.selected`，但既有工具授权仍允许完成受控修改。
- 第三条 Run `run-9bef4fe7-6fb2-4c27-91f9-3ad4a6893d96` 明确命中 `calendar-update`，再次严格执行三步；`calendar.update_event` 使用第二条 Run 的新指纹、同一稳定事件 ID 和 `scope=event`，人工审批为 `APPROVED`。三项 ToolResult 均通过 typed verification，写入结果具备 Executor 验证、`RESTART_REQUIRED` 与同一事件的 `COMMITTED` 回执；最终指纹为 `calendar-event-v1-898dc7739f8a0bed19760163c670c2dd0abf3d3df41b7bbda06df86b43f181be`。
- 点击最终更新卡的“查看日程”后，详情页从当前 Calendar Provider 回读 `calendar-85 / stage209_final / 2026-08-12 13:10–13:50 / Asia/Shanghai / 非全天 / 不重复`；没有使用模型总结或历史 Tool 正文替代当前 Provider 事实。
- 精确清理 instrumentation 核对事件 ID、最终标题、最终时间、时区与应用 marker 后删除夹具，删除阶段会话并恢复原会话选择；专用 E2E Profile 恢复为 `calendar.list_events / tasks.list`、空 Skill 和长期记忆关闭。三条 Run 均保持 `COMPLETED` 且审计保留；阶段数据库快照、本机副本和测试包已删除，主应用恢复前台。
- 本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更，也没有向 Pixel_9 或其他模拟器发送目标 ADB 命令。按分级验证约束，仅构建 `:app:assembleDebugAndroidTest`，运行三 Run/Provider 核对单项、精确清理单项和文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，三项均为 `OK (1 test)`；未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 208 阶段：真实前台系统日程创建、查看与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台链路。专用 E2E Profile 在正式 Run 前临时配置为仅 `calendar.create_event`、`calendar-create` Skill 和长期记忆关闭；`READ_CALENDAR / WRITE_CALENDAR` 均已授权。
- 首次在既有会话中输入创建目标时遗漏 `/agent` 前缀，只新增四条普通用户/助手消息；模型明确拒绝实际写入，没有生成 Agent Run、审批、ToolCall 或 Calendar Provider 事件。随后新建会话并输入 `/agent create a one time calendar event titled stage208_calendar_ui_20260808 on August 10 2026 from 10:20 to 10:50 in Asia/Shanghai`。
- 正式 Run `run-0850939c-00dd-497a-b70c-4af0306c2168` 为 `COMPLETED`。唯一 ToolCall `tool-call-a406bf1f-0b83-4810-9d3a-3993c74a0637` 的审批 `approval-d1841b1a-2e56-40a1-bbd8-8c29a00be93e` 为 `APPROVED`；创建结果为 `success=1 / executorVerified=1 / verificationStatus=PASSED / replaySafety=IDEMPOTENT_BY_KEY / receiptStatus=COMMITTED / receiptOperationId=84`，稳定事件 ID 为 `calendar-84`。
- 点击答案级“查看日程”后，详情页从当前 Calendar Provider 回读标题 `stage208_calendar_ui_20260808`、开始 `2026-08-10 10:20`、结束 `2026-08-10 10:50`、时区 `Asia/Shanghai`、全天“否”、重复“否”；没有使用模型总结或历史 Tool 正文替代当前 Provider 事实。
- 精确清理 instrumentation 首次已删除匹配应用 marker 的 `calendar-84`，随后因临时测试把返回 `Unit` 的 `deleteConversations` 错误断言为删除行数而失败；Room 事务回滚，没有形成部分消息/Profile 清理。修正为可重试两态后再次仅在 Redmi 运行，结果 `OK (1 test)`：事件已不可见，阶段会话和既有会话中精确四条误入消息不存在，Profile 恢复为 `calendar.list_events / tasks.list`、空 Skill、长期记忆关闭，原 Run 仍为 `COMPLETED`。测试包已卸载，主应用冷启动成功，Run、审批和 Tool Ledger 审计保留。
- 本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更；没有向 Pixel_9 或其他模拟器发送目标 ADB 命令。按分级验证约束，仅构建 AndroidTest APK、运行上述清理单项和文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，两项均为 `OK (1 test)`；未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 207 阶段：真实前台本地笔记删除、失败边界与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台 `/agent` 链。临时 Profile `stage207notesui` 的正式配置为 `notes.list / notes.search / notes.get / notes.delete`、`local-note-delete`、长期记忆关闭；夹具创建完成后已移除 `notes.create / local-notes`。
- 首次夹具 `stage207_fixture_20260808` 的稳定 ID 为 `note-124651a0-85d6-4d78-8790-f64029cc746a`、revision `1`。Run `run-281935cb-a3b5-4661-8be8-264da24ae39b` 严格完成 `notes.search -> notes.get -> notes.delete`，删除审批 `APPROVED`，结果 `success=true / executorVerified=true / verificationStatus=PASSED / receiptStatus=COMMITTED / replaySafety=IDEMPOTENT_BY_KEY`；Store 回读目标不可见。随后模型又提出同查询 `notes.search`，该额外调用只有 proposed、未 validated，重复工具调用保护使 Run 保留为 `BUDGET_EXHAUSTED`，错误为“检测到重复工具调用：notes.search”。已提交删除、审批、回执和 typed `tool.verify=PASSED` 均未回滚。
- 第二个夹具 `stage207_retry_20260808` 的稳定 ID 为 `note-07ab7353-7f75-4d4e-b08c-4f818f454c92`、revision `1`。Run `run-20b449fe-da68-4718-9eaf-5ac6d691d888` 只完成 `notes.search / notes.get` 后即以 `COMPLETED` 停止，没有提出删除，因此不能算删除验收成功。
- 后续独立 Run `run-e520f307-96fd-4bc9-b4e8-3b9425c405d4` 严格完成 `notes.search -> notes.get -> notes.delete`，终态 `COMPLETED`，耗时约 `3m12s`，共 15 个 Step、5 次模型请求、3 次工具调用和 1 次审批。搜索、详情、审批与删除绑定同一 note ID；三项 ToolResult 均 `success=true / verificationStatus=PASSED`，删除审批 `APPROVED`，删除 Executor 验证为“是”，账本为 `proposed / validated / result / verified`，回执 `COMMITTED`、重放 `IDEMPOTENT_BY_KEY`。`tool.call.proposed` 中的恢复契约仍为 `notCommittedReplayPolicy=DENY`，无回执路径不会重放 DELETE。
- 打开“本地笔记”并点击刷新后，页面显示“最近 0 条 · 最多展示 10 条 / 还没有本地笔记”。Room 只读复核显示两条夹具均为标题/正文空字符串、revision `2` 的 tombstone，当前可见笔记数为 0。删除 4 个临时会话后，对应会话数为 0，但 5 条阶段 Agent Run 仍保留；删除 `stage207notesui` 前先选择原 Profile“设备打开应用 E2E”，清理后 Profile 总数为 2，且原 Profile 仍为当前 Agent。
- 本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更；没有向 Pixel_9 或其他模拟器发送目标 ADB 命令。按分级验证约束，仅运行文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`；未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 206 阶段：真实前台本地笔记编辑、版本递增与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台 `/agent` 链。为建立唯一夹具，临时 Profile `stage206notesui` 曾短暂加入 `notes.create / local-notes`；夹具创建并从当前 Store 确认为 `note-f7519a66-7573-492a-813f-9883b5c947d5 / stage206_fixture_20260808 / stage206_fixture_body_v1 / revision=1` 后，正式更新前已恢复为 `notes.list / notes.search / notes.get / notes.update`、`local-note-update`、长期记忆关闭。
- 正式 Run `run-d7cb01df-d13a-4d43-93df-902c19ed972b` 为 `COMPLETED`，耗时约 `2m16s`，含 15 个步骤、5 次模型请求、3 次工具调用和 1 次审批。调用顺序严格为 `notes.search -> notes.get -> notes.update`；搜索、详情和更新均绑定同一稳定 note ID，更新参数为 `expected_revision=1 / title=stage206_note_v2 / content=stage206_body_v2`。
- 人工点击审批卡“批准执行”后，任务中心显示 `notes.update` 审批已批准，Tool Ledger 为 `proposed / validated / result / verified`，Executor 验证为“是”，执行回执 `COMMITTED`，重放策略 `IDEMPOTENT_BY_KEY`，结果 revision 恰为 `2`；三个工具步骤的执行后验证均为通过。
- 对话 Tool 卡的“查看笔记”与刷新后的本地笔记页均从当前 Store 回读 `stage206_note_v2 / stage206_body_v2 / 版本 2`，创建时间保持不变且更新时间对应本轮提交。随后通过 UI 删除测试笔记，删除创建/更新两个临时会话和 `stage206notesui`，恢复原 Profile“设备打开应用 E2E”；旧 Run 审计继续保留。
- 本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更；未向 Pixel_9 或其他模拟器发送 ADB 命令。按分级验证约束，仅构建 `:app:assembleDebugAndroidTest` 并在 Redmi 运行文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`；测试包随后卸载，未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 205 阶段：真实前台本地笔记写入、查看与清理验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台 `/agent` 链路。临时 Profile `stage205notesui` 精确配置为 Provider “设备动作 E2E”、模型 `gpt-5.6-luna`、Responses 模式、`notes.list / notes.search / notes.create` 三项工具、`local-notes` Skill，长期记忆关闭。
- 人工输入 `/agent create a local note titled stage205_notes_ui with body stage205_notes_ui_marker_20260808_153711` 并批准审批卡后，Run `run-57f1cd8d-30a2-446b-b022-11819487356b` 为 `COMPLETED`；唯一写工具为 `notes.create`，`success=true`、`executorVerified=true`、typed verification `PASSED`，审批为 `APPROVED`。
- 从当前 Room 二次读取笔记确认标题 `stage205_notes_ui`、正文 marker、revision `1`、稳定 ID `note-ed6086ba-7590-4d07-8272-8030226622c9`；这证明答案级查看使用权威本地事实，而不是模型回执正文。随后通过 UI 删除测试笔记，页面显示已删除并回到“还没有本地笔记”。
- 删除临时会话和 Profile，恢复原 Profile“设备打开应用 E2E”；清理后再次核对旧 Run/审批/Executor/typed verification 审计仍为 `COMPLETED / APPROVED / success=true / executorVerified=true / PASSED`。Redmi 当前前台为 `com.longdev.xiaoling/.MainActivity`，Biu 未被修改或启动。
- 本阶段没有代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变更；只使用 Redmi，未向 Pixel_9 或其他模拟器发送命令。按分级验证约束，未运行完整 JVM、Lint、APK、Release 或全量 instrumentation，也未主动 push。

## 2026-08-08 第 204 阶段：真实前台记忆写入与答案级 UI 验收

- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 完成真实前台人工链路：临时 Profile `stage204-memory-ui` 只允许 `memory.remember`，输入 `/agent remember_stage204_ui_memory_marker_1786155352` 后人工批准审批卡，Run `run-291cc29a-bd05-4829-b7f5-086f1857257d` 最终为 `COMPLETED`；审批为 `APPROVED`，Executor/typed verification 为 `PASSED`。
- 对话页的 Tool Ledger 与完成卡同时显示唯一 `memory.remember`、来源 Run 和稳定记忆 ID `memory-2015dbef-dad1-4ce8-b73d-ca35ba61dd28`；进入“长期记忆”页后从当前 Room 重新读取并显示同一 marker、来源 `/agent Run`、Run ID、类型 `Episode` 和正文，证明答案级入口到权威 Room 事实的真实前台闭环。
- 通过 UI 删除该唯一测试记忆后，页面显示“已删除：remember_stage204_ui_memory_marker_1786155352 / 还没有长期记忆”。随后删除临时 Profile，确认原 Profile“设备打开应用 E2E”仍为当前 Agent；临时会话已删除，会话列表只剩原有用户会话，旧 Run/审批审计保持不变。
- HTTP 日志核对到配置化默认 User-Agent：`Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`。未输出 Provider API Key、记忆正文到日志，也未向 Pixel_9 或任何模拟器发送命令。
- 这是一次真实人工输入、审批点击、Room 回读和清理验收，不等同 Debug-only 探针，也没有新增生产 Tool/Skill、Room Schema、权限、Workflow 或后台能力。按分级验证约束，本阶段未运行完整 JVM、Lint、APK 构建、Release 或全量 instrumentation。

## 2026-08-08 第 203 阶段：真实长期记忆会话投影验收

- 最终 Debug/AndroidTest APK 重新构建成功并仅安装到 Redmi `wsvwypiz7xwslvl7`。三个定向 instrumentation 均通过：`RoomMessagePartStoreInstrumentedTest#verifiedMemoryRememberResultProjectsStableToolPartAfterRoomReopen`、`ConversationPageInstrumentedTest#opensVerifiedRememberedMemoryToolResultByStableId`、`XiaoLingViewModelMemoryNavigationInstrumentedTest#refreshesCurrentRoomBeforeSelectingOrRejectingAnswerMemoryNavigation`，结果为 `OK (3 tests)`。
- 聚焦 JVM `MemoryNavigationTest 5/5 + XiaoLingToolRegistryTest 78/78` 通过；`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`，文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。没有运行完整 JVM、全量 Lint、Release 或全量 instrumentation。
- 真实探针 `memory_remember_conversation_real` 复用第 202 阶段正式链路。Redmi Run `run-f9e8b439-5701-4530-a0cd-39095c037bf9` 为 `COMPLETED`，日志仅报告 `approval=APPROVED`、`executorVerified=true`、`verification=PASSED`、`roomReadBack=true`、`conversationProjection=true`；临时 Profile、测试记忆和专属会话均清理成功。未向 Pixel_9 或其他模拟器发送命令。
- 证据边界：该 Debug-only 探针验证真实结果经 Room 重建后仍能生成可信 Tool part 和稳定“查看记忆”目标，不等同于完整人工 UI 输入、审批点击自动化或生产 `sendAgentRun()` 扩权；没有新增 Tool/Skill、Room Schema、权限、Workflow 或后台能力。

## 2026-08-08 第 202 阶段：真实 Provider 长期记忆写入审批闭环

- Debug-only `memory_remember_real` 使用 Redmi `wsvwypiz7xwslvl7` 当前已选 Provider 和最小临时 Profile，只开放 `memory.remember`，通过正式 `AgentRunUseCase`、`OpenAiCompatibleClient`、Room Tool Ledger 和 `DebugRoomApprovalGate` 完成真实模型写入。
- 最终 Run `run-b747809a-73f0-4813-9c90-7b6a019c978f` 的唯一 `memory.remember` 调用保留第 202 阶段唯一标记；Room 审批为 `APPROVED`，`executorVerified=true`、`verificationStatus=PASSED`，`memoryIdsUsed`、`executionReceipt.operationId` 和当前 Room 回读记录绑定同一合法 `memory-UUID`。回读核对实际 note、规范化 type、tags、enabled 与 `sourceRunId`，结果正文同时包含实际 note 和稳定 ID。
- 首次运行只暴露了验收断言过度要求模型完全复制提示词 note/可选字段外壳的问题；未形成错误成功结论，探针在 `finally` 中清理了本轮数据。调整为允许声明内的可选 `type/tags`、但必须保留唯一标记并与 Room 回读一致后，第二次 Redmi 运行通过。两次运行均恢复原 Profile，最终测试记忆和临时 Profile 已清理。
- 聚焦 JVM `MemoryNavigationTest 5/5 + XiaoLingToolRegistryTest 78/78`（`83/83`），`:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；Debug 主包通过 `adb -s wsvwypiz7xwslvl7 install -r` 覆盖安装且未清除应用数据。按分级验证约束，本阶段未运行完整 JVM、Lint、Redmi 全量 instrumentation、文档 corpus gate 或 Release，也未向 Pixel_9/模拟器发送命令。

## 2026-08-08 第 201 阶段：长期记忆写入结果答案级导航

- `memory.remember` 的成功与恢复验证结果现在由应用回读后携带唯一 `memory-UUID`，并在 `memoryIdsUsed` 中绑定同一身份；`MemoryNavigation.kt` 只接受 `VERIFIED`、合法参数、固定成功外壳和单一一致 ID。只读写入、失败、旧格式、额外参数、ID 漂移或正文重复身份均不生成入口。
- 点击后复用 `refreshMemoriesAndResolveNavigation()`，从当前 Room 重新读取目标并进入记忆管理页；旧 Run 没有新身份时保持不可导航，未新增写入、审批、Room、Workflow 或后台能力。
- 聚焦 JVM `MemoryNavigationTest 5/5 + XiaoLingToolRegistryTest 2/2`、`:app:assembleDebug :app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；Redmi `XiaoLingViewModelMemoryNavigationInstrumentedTest` 为 `OK (1 test)`，文档 corpus gate `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。本阶段尚未运行完整 JVM、Lint、Redmi 全量 instrumentation 或 Release，没有使用 `Pixel_9`。

## 2026-08-08 第 200 阶段：本地笔记详情/编辑结果答案级导航

- `LocalNoteNavigation.kt` 现在支持可信 `notes.get` 与 `notes.update` 结果导航：详情必须绑定唯一请求 `note_id`、固定详情/正文安全边界、单一规范 ID 和正 revision；编辑必须为 `VERIFIED`，参数集合精确，标题/ID回显一致，且新 revision 严格为 `expected_revision + 1`。`notes.create` 写入结果继续要求 `VERIFIED`。
- 点击后复用现有本地笔记管理页，从当前 Note Store 二次读取；失败、只读写入、额外参数、标题/ID/revision 漂移、重复身份或正文伪造均不生成入口，不展示历史 Tool 正文。
- 聚焦 JVM `LocalNoteNavigationTest 7/7`，`:app:assembleDebug :app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；本阶段尚未运行完整 JVM、Lint、Redmi instrumentation、文档 corpus gate 或 Release，没有使用 `Pixel_9`。

## 2026-08-08 第 199 阶段：日程创建/修改结果答案级导航

- `calendar.create_event` 成功结果新增唯一稳定事件 ID；`CalendarNavigation.kt` 只接受 `VERIFIED` 创建/修改结果。创建绑定规范化标题和四项参数，修改绑定同一事件 ID、`scope=event`、有效审批前指纹与不同的新指纹；结果外壳、参数或 ID/指纹任一漂移均不生成入口。
- 成功后复用第 198 阶段“查看日程”与 Provider 二次读取详情页；删除结果、失败结果和 `READABLE_ONLY` 写操作不会导航。Room v36、写入审批、Tool/Skill、Workflow、后台和 Shadow 边界不变。
- 聚焦 JVM `CalendarNavigationTest 4/4 + XiaoLingToolRegistryTest 78/78`，合计 `82/82`；`:app:assembleDebug :app:assembleDebugAndroidTest` `BUILD SUCCESSFUL`。首次编译发现遗漏工具常量与多行布尔表达式解析问题，修复后同一命令通过。
- 按快速迭代分级，本阶段未运行完整 JVM、全量 Lint、Redmi instrumentation、文档 corpus gate 或 Release；没有向 `Pixel_9`/模拟器发送命令。

## 2026-08-08 第 198 阶段：答案级系统日程详情导航

- `CalendarNavigation.kt` 只接受成功、验证状态非失败且参数/应用结果外壳一致的 `calendar.list_events / calendar.search_events / calendar.get`。列表/搜索必须精确为单条结果，详情参数与正文 ID 必须一致；多结果、额外参数、动态标题漂移、非规范/溢出 ID 和正文伪造均不生成“查看日程”。
- 点击入口只携带稳定 `calendar-<正整数>` ID。独立详情 pane 每次进入都用 `AndroidCalendarEventReader` 重新查询当前 Calendar Provider，只显示标题、ID、起止、全天、时区和重复状态；目标删除、权限撤销、Provider 不可用或读取失败时不显示历史 Tool 正文。
- 聚焦 JVM `CalendarNavigationTest 3/3 + XiaoLingNavigationCoordinatorTest 9/9`，合计 `12/12`；`:app:assembleDebug :app:assembleDebugAndroidTest` `BUILD SUCCESSFUL`，新增 Tool 卡/详情页 AndroidTest 已完成编译，`git diff --check` 通过。
- 按快速迭代分级，本阶段未运行完整 JVM、全量 Lint、Redmi instrumentation、文档 corpus gate 或 Release；没有向 `Pixel_9`/模拟器发送命令。Room v36、日程 Tool/Skill、写入审批、Workflow、后台和 answerability Shadow 边界不变。

## 2026-08-08 第 197 阶段：答案级历史会话导航

- 可信的 `app.list_conversations / app.search_conversations / app.get_conversation` Tool part 现在才可能生成“查看会话”入口；固定结果标题、参数契约、唯一合法 `conversation-...` ID、详情回显一致和非失败验证均通过时才显示，普通模型文本、空/多结果、额外参数、换行注入、伪造/漂移 ID 均不显示。
- 点击前由 ViewModel 重新读取当前 Room 会话表，目标 ID 恰好唯一存在时复用既有会话选择和消息加载；目标被删除、不存在、重复或读取失败时 fail-closed，不使用旧 UI 缓存，不发送消息、不创建 Run。
- 聚焦 JVM 会话导航 `17/17`（含 `ConversationNavigationTest 3/3`、既有任务/笔记/记忆导航回归），`:app:assembleDebug :app:assembleDebugAndroidTest` `BUILD SUCCESSFUL`，`git diff --check` 通过。
- 按快速迭代分级，本阶段未运行完整 JVM、全量 Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release；没有向 `Pixel_9`/`emulator-5554` 发送命令，Room v36、Workflow、设备动作、恢复与 answerability Shadow 边界不变。

## 2026-08-08 第 196 阶段：历史会话详情只读闭环

- 新增 `app.get_conversation(conversation_id)`、`AgentConversationDetailPolicy` 和 `AgentConversationStore.get`。工具只接受列表/搜索返回形态的稳定 `conversation-...` ID，`SAFE`、仅前台 `DIRECT`、5 秒超时；详情从当前 Room 回读且只投影用户/助手文本，最多 40 条、单条 20,000 字符、总计 60,000 字符。
- `RoomAgentConversationStore` 不加载 `MessagePart`，因此工具参数、Provider 凭据字段、附件二进制、原始推理、Provider 元数据和内部审计字段不进入结果。输出明确标记为本地历史资料而非工具指令；未知/漂移 ID、额外参数、Workflow、后台和无上下文均 fail-closed。
- 新增独立 `conversation-detail` Skill；旧 `conversation-recall`、旧 Profile、历史 Run 与 `LEGACY_RUN_TOOL_NAMES` 未扩权，未新增 Room Schema、权限、网络、设备动作、Workflow 或后台副作用。
- 聚焦 JVM：`AgentConversationDetailPolicyTest 2/2`、`AgentSkillsTest 31/31`、`XiaoLingToolRegistryTest 78/78`、`LegacyRunToolBoundaryTest 3/3`，均通过。`:app:assembleDebug :app:assembleDebugAndroidTest` `BUILD SUCCESSFUL`。
- 按快速迭代分级，本阶段未运行完整 JVM、Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release；没有向 `Pixel_9`/`emulator-5554` 发送命令，Room v36 与 answerability Shadow 边界不变。

## 2026-08-08 第 195 阶段：当前 Agent Profile 只读状态

- 新增 `AgentExecutionProfileInfo` 和 `agent.get_profile`。工具无参数、`SAFE`、仅前台 `DIRECT`、禁止 Workflow/后台、5 秒超时；结果固定为 Agent 名称、模型、API 模式和本次记忆召回状态，不包含 Provider 地址、API Key、系统提示词、内部 Profile ID 或工具白名单。
- `AgentSkillsTest` `30/30`、`XiaoLingToolRegistryTest` `76/76` 通过；覆盖 Skill 选择、工具定义、直接上下文成功、无上下文/Workflow/带参调用 fail-closed 和敏感字段排除。`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。
- Profile 信息只在当前进程的 `AgentToolExecutionContext` 窄对象中传递；没有新增 Room、权限、网络请求、Provider 读取、设备动作、Workflow 或后台副作用，旧 Profile/历史 Run/Legacy 工具集合未自动扩权。
- 按分级验证约束，本阶段未运行完整 JVM、全量 Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release；没有使用或启动 Pixel_9，Room v36 与 answerability Shadow 边界不变。

## 2026-08-08 第 194 阶段：只读应用信息工具

- 新增 `AppInfoReader`/`AndroidAppInfoReader`，生产 `XiaoLingToolRegistry` 注入当前 PackageManager 读取器；`app.get_info` 无参数、`SAFE`、支持后台、5 秒超时，结果固定为应用名称、包名、版本名和版本号四字段。
- `XiaoLingToolRegistryTest` `74/74`、`AgentSkillsTest` `29/29` 通过；覆盖定义属性、成功结果、敏感字段排除、不可用与带参失败。`:app:assembleDebug` `BUILD SUCCESSFUL`。
- 新增独立 `app-info` Skill；既有 Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 未扩权。文档写回后的 AndroidTest 资产仅在 Redmi `wsvwypiz7xwslvl7` 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，最终 `OK (1 test)`；测试包随后卸载，主应用包保留。
- 按分级验证约束，本阶段未运行完整 JVM、全量 Lint、Redmi 功能 instrumentation 或 Release；没有使用或启动 Pixel_9，Room v36、Workflow、设备动作、日历、Shadow 和后台副作用边界不变。

## 2026-08-08 第 193 阶段：任务中心关联 Run 双向查看与安全导航

- `AgentTaskCenterProjection` 新增来源/关联目标投影：关联 Run 只有在当前历史唯一存在来源时可“查看来源 Run”；来源 Run 只有在 `createdAt` 唯一确定最新关联 Run 时可“查看关联 Run”。缺失、历史裁剪、重复 ID 和最新时间并列均保持不可导航。
- `AgentTaskCenterPage` 的导航点击先核对当前列表中的唯一目标，再切换到全部筛选、滚动到目标并调用现有 `selectAgentRun`。本阶段没有新增重试、审批、模型规划、工具执行、Provider 写入或 Room 查询。
- `AgentTaskCenterProjectionTest` 聚焦 JVM 为 `3/3`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功。仅在 Redmi `wsvwypiz7xwslvl7` 覆盖安装测试 APK 和最新 Debug 主 APK，`AgentTaskCenterPageInstrumentedTest` 最终为 `OK (4 tests)`。
- 首次真机运行准确暴露测试 APK 与设备旧主 APK 不同步的 `NoSuchMethodError`；没有修改业务断言或清理数据，仅在 Redmi `adb install -r` 最新主 APK 后复跑通过。文档更新后重建 AndroidTest APK，并在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`；测试包随后卸载。没有向 `emulator-5554` 发送任何 ADB 命令。
- 按分级验证约束，本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation；Room v36、旧 Run/关联 Run 持久化语义、重试确认、Workflow、设备动作、Provider、answerability shadow 和后台边界均未改变。

## 2026-08-08 第 192 阶段：确认后关联新 Run 的 Room 历史保留验收

- 新增 `RoomAgentRunRepositoryInstrumentedTest#linkedRetryPersistsRelationAndPreservesSourceAuditAcrossRepositoryRestart`。夹具来源 Run 含已批准的 `notes.create`、完成 Step、成功 Tool Result、`COMMITTED` 回执和独立审计 Event，随后收敛为 `FAILED` 终态。
- `:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功。仅在 Redmi `wsvwypiz7xwslvl7` 运行新增测试及三个相邻恢复/关联测试，合计 `RoomAgentRunRepositoryInstrumentedTest` 聚焦 `4/4` 通过。
- 测试在确认后的 Room 创建路径写入 `retryOfRunId`，创建前后和两次磁盘 Repository 重建后，来源 Run 的终态、Step、Approval、Tool Call/Result、执行回执、Event 与 Tool Ledger 均保持不变；新 Run 持久化为独立 `QUEUED`，没有复制来源账本或事件。
- 更新文档后重建 AndroidTest 资产，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`；测试包随后从 Redmi 卸载，主应用数据未清理。未向 `emulator-5554` 发送任何 ADB 命令；本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，也未修改生产代码、Room v36 Schema、恢复策略、Workflow 或后台能力。

## 2026-08-08 第 191 阶段：任务中心重新发起边界统一

- 聚焦 JVM `AgentTaskRetryPolicyTest` `28/28` + `AgentRunRetryCoordinatorTest` `13/13` + `AgentTaskRetryEvidencePresentationTest` `4/4` + `AgentRetryConfirmationPresentationTest` `2/2`，合计 `47/47` 通过。
- `:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功。仅在 Redmi `wsvwypiz7xwslvl7` 覆盖安装 Debug 主包和测试包，`AgentTaskCenterDialogsInstrumentedTest` 结果 `OK (3 tests)`，`AgentTaskCenterPageInstrumentedTest` 结果 `OK (2 tests)`。
- 真机验证覆盖专用确认弹窗和任务卡的“创建关联新 Run”文案；未向 `emulator-5554` 发送任何安装、启动或测试命令。
- 本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未修改旧 Run 状态、Room Schema、Workflow 或后台执行能力。

## 2026-08-08 第 190 阶段：启动恢复失败可见投影

- `:app:testDebugUnitTest --tests com.longdev.xiaoling.ui.StartupRunRecoveryNoticePolicyTest` 通过。测试覆盖失败/取消终态汇总、Recovery 中 `restartDisposition` 的边界提示、私密内容脱敏与无收敛时不读候选。
- 启动提示仅回读收敛后的 `AgentRunDetailRecord`，统计无法原地恢复的 Run，跳转任务中心后还是由现有重试证据和确认流程决定是否新建 Run。未调用恢复执行器，未重放工具。
- 本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未修改生产恢复策略、Room v36、Workflow 或后台边界。

## 2026-08-08 第 189 阶段：失败日程修改 Run 终态恢复验证

- `:app:assembleDebug :app:assembleDebugAndroidTest` 通过；新增 `RoomAgentRunRepositoryInstrumentedTest#failedCalendarUpdateRunStaysTerminalAcrossRestartAndCannotReplay`。
- 仅向 Redmi `wsvwypiz7xwslvl7` 安装 AndroidTest APK 并运行该单项，结果 `OK (1 test)`。测试使用真实 Room 构造无 `COMMITTED` 回执的 `calendar.update_event` 失败 Run，重建 `RoomAgentRunRepository` 后仍得到 `RESTART_REQUIRED / RUN_STATE_NOT_RESUMABLE`。
- `closeInterruptedRuns()` 返回 `0`，Run 保持 `FAILED`，原 Step ID、Tool Result 和 Event 数量不变，未新增 `run.recovered`；这证明启动恢复不会重新打开终态 Run 或重放 UPDATE。
- 更新文档资产并重建 AndroidTest APK 后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`。测试包随后卸载，主应用与用户数据保留。
- 没有向 `emulator-5554` 发送安装、启动、日志或测试命令。本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未修改生产 Repository/Resume Policy/Registry、Room v36、旧 Run/Workflow 或后台边界。

## 2026-08-08 第 188 阶段：真实 Provider 审批后漂移失败闭环

- `:app:testDebugUnitTest --tests com.longdev.xiaoling.agent.XiaoLingToolRegistryTest --tests com.longdev.xiaoling.agent.AgentRunResumePolicyTest --tests com.longdev.xiaoling.agent.MinimalAgentRuntimeTest` 通过，结果 `196/196`；`:app:assembleDebug :app:assembleDebugAndroidTest` 成功。
- 仅在 Redmi `wsvwypiz7xwslvl7` 安装 Debug APK，通过显式 `com.longdev.xiaoling/.agent.AgentE2eDebugReceiver` 和 action `com.longdev.xiaoling.debug.AGENT_E2E` 触发 Debug-only `calendar_update_conflict_real`。真实 Provider Run `run-05831fda-73c9-460a-a8e5-a3c52debdfca` 严格执行 `calendar.search_events -> calendar.get -> calendar.update_event`。
- `calendar.update_event` 审批已写入 Room 并为 `APPROVED` 后，夹具修改同一事件标题；条件 UPDATE 被拒绝，Run 为 `FAILED`，结果没有 `COMMITTED` 回执，Provider 回读仍为外部漂移事实。日志只包含 Run、状态、工具名和布尔结论。
- 夹具事件、临时 Profile 和必要时创建的本地日历已精确清理；没有向 `emulator-5554` 发送 ADB 命令。本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未新增生产权限、Room Schema、后台或恢复重放能力。
- 文档资产更新并重建 AndroidTest APK 后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`、耗时 `2.702s`；测试包随后卸载。

## 2026-08-08 第 187 阶段：日程修改中断恢复边界加固

- 聚焦 JVM 命令 `:app:testDebugUnitTest --tests com.longdev.xiaoling.agent.XiaoLingToolRegistryTest --tests com.longdev.xiaoling.agent.AgentRunResumePolicyTest --tests com.longdev.xiaoling.agent.MinimalAgentRuntimeTest` 通过，结果 `196/196`。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功。仅向 Redmi `wsvwypiz7xwslvl7` 安装测试 APK，并运行 `AndroidCalendarEventWriterInstrumentedTest`，结果 `OK (4 tests)`；没有向 `emulator-5554` 发送安装、授权、启动、日志或测试命令。
- 真机测试在成功 UPDATE 后重建 `AndroidCalendarEventWriter`，`verifyUpdateCommitted()` 只读回当前 Provider 并通过；重建实例无 `COMMITTED` 回执直接 UPDATE 仍因旧指纹拒绝。该证据证明恢复不会再次调用 UPDATE，事件与本轮资源按既有逻辑精确清理。
- 更新文档资产并重建 AndroidTest APK 后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`、耗时 `2.597s`；测试 APK 已卸载，主应用与用户数据保留。本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未修改生产 Registry/Writer/Reader、Room v36、旧 Profile/Run、Workflow 或后台边界。

## 2026-08-08 第 186 阶段：真实 Provider 受控系统日程修改闭环

- `:app:assembleDebug` 与后续 `:app:assembleDebugAndroidTest` 均成功。仅向 Redmi `wsvwypiz7xwslvl7` 覆盖安装主 Debug APK 和测试 APK，授予日历读写权限并启动主应用；没有向在线模拟器发送安装、授权、启动、日志或测试命令。
- 通过显式 `com.longdev.xiaoling/.agent.AgentE2eDebugReceiver` 触发 Debug-only `calendar_update_real`，读取设备当前 Provider，创建 stage186 专属一次性非全天事件和临时 Profile。隐式广播未产生新 Run，显式触发后才进入正式探针；这只是 Android 广播投递方式修正，不改变生产入口。
- 最终 Run `run-554e65fa-ca43-461c-8346-034f3a426694` 为 `COMPLETED`，选择唯一 `calendar-update`，严格执行 `calendar.search_events -> calendar.get -> calendar.update_event`。搜索关键词、稳定事件 ID、当前指纹、`scope=event`、完整标题/起止/时区均原样传递。
- 三项 Tool Result 均 `success=true / PASSED`；修改审批为 `APPROVED`，结果 `executorVerified=true` 且回执为同一事件的 `COMMITTED`。Provider 回读确认标题、起止时间、时区四字段更新成功，指纹从旧值变为新值。
- 成功日志为 `resultsVerified=true / stableIdBound=true / fingerprintBound=true / receipt=COMMITTED / providerUpdated=true / newFingerprint=true`，清理日志为 `temporaryProfileRemoved=true / testEventRemoved=true / temporaryCalendarRemoved=true`。日志未输出 API Key、标题、参数、指纹或正文。
- 更新文档资产后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`；测试包随后卸载并保留主应用数据。本阶段未运行 JVM、Lint、Release APK 或默认全量 instrumentation；Room v36、旧 Skill/Profile/Legacy Run、Workflow 和后台未扩权。下一阶段先验证真实模型失败/中断后的 `RESTART_REQUIRED + DENY` 恢复和跨进程只读确认。

## 2026-08-08 第 185 阶段：受控系统日程修改

- TDD 首轮因 `CalendarEventUpdateRequest / Result / Scope` 与生产工具尚不存在得到预期编译失败。实现与最终审查修正后，聚焦 `XiaoLingToolRegistryTest 71/71 + AgentSkillsTest 28/28 + LegacyRunToolBoundaryTest 2/2`，合计 JVM `101/101` 通过。
- 审查确认正常执行已限制前台 DIRECT，但最初的 `COMMITTED` 恢复入口只核对 scope 和回执。最终补上同一运行上下文门禁，并增加 Workflow 与后台 DIRECT 反例；两种拒绝路径均不调用 UPDATE，也不触发 Provider 恢复回读。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 最终构建成功。仅向 Redmi `wsvwypiz7xwslvl7` 安装主包与测试包并授予日历读写权限；没有向在线模拟器发送安装、授权、启动、日志或测试命令。
- Redmi `AndroidCalendarEventWriterInstrumentedTest#conditionalUpdateVerifiesNewFingerprintAndCommittedRecoveryOnlyReadsProvider` 最终结果 `OK (1 test)`、耗时 `0.35s`。真实 Calendar Provider 证明标题、起止时间和时区四字段修改、新事件指纹、已有 COMMITTED 回执只读恢复、无回执重复调用不重放 UPDATE，以及外部改名后旧指纹条件更新影响 0 行；测试事件已精确清理。
- 更新长期文档并重建 AndroidTest 资产后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`。该 gate 当前覆盖六个项目文档资产（含历史验证卷，不含仓库 README），测试包随后卸载并重新启动主应用。
- 本阶段未运行完整 JVM、Lint、Release APK 或默认全量 instrumentation。Room v36、旧 Skill/Profile/Legacy Run、Workflow 和后台未扩权；下一阶段验证真实模型 `calendar.search_events -> calendar.get -> calendar.update_event` 的审批闭环。

## 2026-08-08 第 184 阶段：真实 Provider 受控系统日程删除闭环

- `:app:assembleDebug` 构建成功并仅向 Redmi `wsvwypiz7xwslvl7` 覆盖安装。通过显式 Debug Receiver 触发 `calendar_delete_real`，并授予主应用日历读写权限；没有向模拟器发送安装、启动、授权、日志或测试命令。
- 首轮 Run `run-85260e99-5a2c-40a6-b26a-712643ea1c2e` 已为 `COMPLETED`，状态探针确认最后一项 `calendar.delete_event success=true / executorVerified=true / PASSED` 且审批 `APPROVED`。但 `skillSelectionGoal` 的“删除”和“日程”不连续，确定性 Skill 匹配返回空，严格断言拒绝把该轮记为完整成功；`finally` 仍清理事件、本地日历和临时 Profile。
- 将选择目标改为显式“删除日程”后重新构建、覆盖安装并等待稳定启动。最终 Run `run-3981834b-8d4c-4ade-b3ec-23aa138250cd` 为 `COMPLETED`，选择唯一 `calendar-delete`，严格执行 `calendar.search_events -> calendar.get -> calendar.delete_event`。
- 最终 Run 中搜索关键词原样使用，get 参数等于搜索结果稳定 ID，delete 参数等于同一 ID、当前 Provider 指纹和 `scope=event`；三项结果均 `success=true / PASSED`。删除审批为 `APPROVED`，结果 `executorVerified=true` 且回执为同一事件的 `COMMITTED`，最终 Provider 回读 NotFound。
- 成功日志为 `stableIdBound=true / fingerprintBound=true / receipt=COMMITTED / providerInvisible=true`，清理日志为 `temporaryProfileRemoved=true / testEventRemoved=true / temporaryCalendarRemoved=true`。日志未输出 API Key、事件标题、参数、指纹或正文。
- 更新长期文档后构建 AndroidTest 资产，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`；测试包随后卸载，主应用数据保留。
- 本阶段未修改生产 Registry、Skill、Writer、Reader、Room Schema、权限模型、旧 Profile/Run 或后台边界；未运行 JVM、Lint、Release APK 或默认全量 instrumentation。下一阶段冻结受控日程修改契约。

## 2026-08-07 第 183 阶段：受控系统日程删除

- TDD 首轮因指纹、删除契约和 Registry 工具尚不存在得到预期失败。实现后聚焦运行 `XiaoLingToolRegistryTest + AgentSkillsTest + LegacyRunToolBoundaryTest + CalendarEventFingerprintTest`，合计 JVM `97/97` 通过。
- Standards 审查发现 `calendar.delete_event` 只在工具发现层检查前台 DIRECT，直接调用 `execute()` 可绕过。执行入口已补同一 `calendarDeleteAllowed(runContext)` 门禁，并新增 null context 与 Workflow 直接调用反例；修复后上述聚焦 JVM 仍为 `97/97`。
- 仅在 Redmi `wsvwypiz7xwslvl7` 运行 `AndroidCalendarEventWriterInstrumentedTest#conditionalDeleteRejectsDriftAndCommittedRecoveryOnlyReadsProvider`，结果 `OK (1 test)`、耗时 `0.392s`。真实 Calendar Provider 证明成功删除、已有 COMMITTED 回执只读确认不可见、无回执重复调用返回 NotFound，以及外部改名后旧指纹条件删除影响 0 行。
- 测试同时证明 scope 边界：一次性事件只接受 `event`，重复事件只接受整个 `series`，`occurrence` 明确拒绝；旧 Profile/Run、Workflow、后台和日程修改未扩权。测试包已卸载，主 Debug 应用重新启动；未向在线模拟器发送 ADB 命令。
- 最终 `:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。首次 corpus 命令误用 `.knowledge` 包名并得到预期的 `ClassNotFoundException`；按源码包名 `.storage` 修正后，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`，测试包随后卸载且主应用数据保留。
- 本阶段未运行完整 JVM、Lint、Release APK 或默认全量 instrumentation。下一阶段应以真实模型 Provider 验证 `calendar.search_events -> calendar.get -> calendar.delete_event` 的 Skill 选择、稳定 ID/指纹传递、逐次审批、typed verification、COMMITTED 回执和精确清理。

## 2026-08-07 第 182 阶段：真实 Provider 系统日程详情闭环

- `:app:assembleDebug` 构建成功。仅向 Redmi `wsvwypiz7xwslvl7` 覆盖安装并显式授权日历读写；写权限只用于 Debug 夹具创建/清理，临时 Agent Profile 精确只含 `calendar.search_events / calendar.get`。虽然设备枚举中有 `emulator-5554`，没有向模拟器发送安装、启动、授权、日志或测试命令。
- 覆盖安装后首次 Run `run-f7f6e2d3-25df-48f3-95b8-76ffb4c53f30` 与启动恢复并发，日志为 `Agent Run 已结束，不能追加步骤`；`finally` 仍报告事件、本地日历和临时 Profile 清理成功。应用稳定后同一实现首次成功 Run 为 `run-7b552e08-4aba-4f00-9f1f-66e70c1dc0df`。
- 审查前补强“Provider 已写入但回读验证失败”的清理窗口：`Committed` 一产生即捕获精确事件/日历 ID。重新构建、覆盖安装并等待稳定启动后，最终代码 Run `run-e238ca62-58c5-4c54-a611-e368f2ddace2` 为 `COMPLETED`，选择 `calendar-detail`，严格执行 `calendar.search_events -> calendar.get`。
- 最终 Run 中搜索关键词原样使用，`calendar.get.event_id` 等于搜索结果稳定 ID；两项结果均 `success=true / PASSED`，搜索命中唯一夹具，详情包含同一 ID、标题、时区与“重复：否”，审批为 0，模型回复长度为 `347`。清理日志为 `temporaryProfileRemoved=true / testEventRemoved=true / temporaryCalendarRemoved=true`。
- Standards 审查唯一硬问题是长期文档尚未同步，本节及其余六份文档已补齐；长 Debug 探针方法是沿用既有真实验收模式的非阻断结构建议。Spec 审查未发现缺失或扩权：事件创建不在 Agent Run 内，生产能力面保持不变。
- 六份长期文档同步后重新构建 AndroidTest 文档资产，仅在 Redmi 运行项目文档 corpus gate，结果 `OK (1 test)`。
- 本阶段未运行完整 JVM、Lint、Release APK 或默认全量 instrumentation；Debug/AndroidTest APK 已构建，未新增生产工具、权限、Room Schema、后台能力或发布版本。

## 2026-08-07 第 181 阶段：系统日程稳定身份与权威详情读取

- TDD 首轮运行 `XiaoLingToolRegistryTest`，因 `eventId`、`CalendarEventDetailRecord / CalendarEventDetailReadResult` 和 `getEvent` 尚不存在得到预期编译失败；最小实现后 `XiaoLingToolRegistryTest 63/63 + AgentSkillsTest 26/26`，合计聚焦 JVM `89/89` 通过。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。仅向 Redmi `wsvwypiz7xwslvl7` 安装主包和测试包；设备枚举中虽然存在 `emulator-5554`，但没有向模拟器发送安装、授权、启动、日志或测试命令。
- Redmi `AndroidCalendarEventWriterInstrumentedTest#stableProviderEventIdLinksListSearchAndAuthoritativeDetail` 最终复验耗时 `0.252s`，结果 `OK (1 test)`。探针创建唯一临时事件，验证 list/search 返回同一 `Events._ID`、get 回读标题/起止/时区/全天/重复状态，再按 Provider 返回的精确事件 ID 删除；必要时新建的应用本地日历也在 `finally` 清理。
- Standards 审查没有发现仓库规则违规，仅记录日期格式器三行重复为非阻断低优先级建议。Spec 审查发现 RDATE-only 重复事件会被误判，最终 projection 与判断同时补入 `Events.RDATE`；“calendar-1 长度不足 10”为误报，实际长度为 10，并新增 Schema 合法性测试锁定。
- 六份长期文档同步后重新构建 AndroidTest 资产，仅在 Redmi 运行项目文档 corpus gate，结果 `OK (1 test)`。
- 本阶段没有新增 Room Schema、日程写/删工具、后台能力或 Release；未运行真实模型 Provider Run、完整 JVM、Lint、默认全量 instrumentation 或 Release。第 182 阶段优先验证真实 Agent 的 `calendar.search_events -> calendar.get` 闭环。

## 2026-08-07 第 180 阶段：答案级长期记忆导航

- `MemoryNavigationTest` 先因 `memoryIdForNavigation()` 不存在得到预期编译失败；实现后，长期记忆导航、既有笔记导航、记忆管理投影和 Agent Tool part 可信投影四个聚焦 JVM 类均通过。解析覆盖单条 `memory.search/get`、空/多结果、错配 ID、失败状态、错工具和非法参数。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。仅向 Redmi `wsvwypiz7xwslvl7` 安装主包和测试包；虽然 `emulator-5554` 在线，但没有向模拟器发送安装、启动、日志或测试命令。
- Redmi `ConversationPageInstrumentedTest#opensTrustedMemoryToolResultByStableId` 通过，最终复验耗时 `2.589s`，结果 `OK (1 test)`；可信 `memory.get` Tool 卡显示“查看记忆”，点击只向 `ConversationActions` 传唯一稳定 ID。
- Redmi `XiaoLingViewModelMemoryNavigationInstrumentedTest#refreshesCurrentRoomBeforeSelectingOrRejectingAnswerMemoryNavigation` 通过，最终复验耗时 `0.36s`，结果 `OK (1 test)`；真实 Room 临时记忆存在时刷新、置顶并选中，删除后不再触发导航且缓存列表不含旧记录。临时记忆已在测试清理路径中删除。
- 六份长期文档同步后重新构建 AndroidTest 资产，仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，最终复验耗时 `2.815s`，结果 `OK (1 test)`。
- Standards 审查发现协程取消可能被 `runCatching` 当作失败，以及候选从加载中转为空态时滚动 effect 不重算；已分别重新抛出 `CancellationException`、补齐 `loadingCandidates` key。Spec 审查没有发现缺失、越界或错误行为。
- 本阶段未运行新的真实 Provider Run、完整 JVM、Lint、默认全量 instrumentation 或 Release。第 179 阶段的 Provider Run 只证明 `memory.search -> memory.get` 工具链，不冒充本阶段 UI 端到端证据；Room v36 与 `v0.1.16 / Room v35` 发布基线不变。

## 2026-08-07 第 179 阶段：真实 Provider 长期记忆详情闭环

- Debug-only `memory_search_get_real` 使用设备当前 Provider、唯一记忆夹具和临时只读 Profile，通过正式 Runtime 验证 `personal-memory-detail`。探针从 Room 核对 Skill、调用顺序、参数、Tool Result、`memoryIdsUsed`、审批和最终状态，日志不记录凭据或正文。
- 仅使用 Redmi `wsvwypiz7xwslvl7`。最终 Run `run-0b54ba01-5fc2-49bc-95dc-92ab5afd80b6` 为 `COMPLETED`，严格执行 `memory.search -> memory.get`；两项结果均 `success=true / PASSED`，搜索关键词原样使用，详情参数与唯一夹具 ID 一致，`memoryIdsUsed` 同样精确，正文数据边界存在且审批为 0。
- 首次覆盖安装后立即启动的 Run `run-94e7a078-acb4-4c9b-a317-fb9f9dacc054` 与启动恢复并发，提前进入终态后触发 `Agent Run 已结束，不能追加步骤`。该轮 `finally` 已成功删除夹具和临时 Profile；应用稳定后使用同一最终代码复验通过，因此保留为编排失败证据而非工具失败。
- 审查发现夹具原先在 `try/finally` 之前创建且残留查询只覆盖 10 条启用记录；最终版把创建移入保护区，管理查询覆盖 ALL/200，并让 FTS/主记录删除、原 Profile 恢复和临时 Profile 移除独立执行。最终清理日志为 `temporaryProfileRemoved=true / testMemoryRemoved=true`。
- 聚焦 JVM `87/87` 冻结通过，`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。更新后的 AndroidTest 资产仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，耗时 `2.524s`，结果 `OK (1 test)`；测试包随后卸载。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release；没有向模拟器发送任何 ADB 命令。

## 2026-08-07 第 178 阶段：按稳定 ID 读取长期记忆详情

- 新增 SAFE `memory.get(memory_id)` 和独立 `personal-memory-detail` Skill。搜索结果补充稳定 `memory-UUID`，详情从当前 Store 回读；非法 ID 在访问 Store 前拒绝，禁用、过期和不存在统一失败且不返回正文。关闭单次召回时搜索与详情工具均隐藏并在执行入口阻断。
- TDD 首轮运行 `XiaoLingToolRegistryTest + AgentSkillsTest + LegacyRunToolBoundaryTest` 共 `87` 项，因工具/Skill 尚不存在、召回开关未覆盖详情和搜索结果缺 ID 得到预期 `7` 项失败。最小实现后同组 `87/87` 通过，`:app:assembleDebug` 为 `BUILD SUCCESSFUL`。
- Standards 审查确认中文 `long` 业务注释、公开测试 seam、旧 Skill/Profile/Legacy Run 权限冻结均符合项目约束；Spec 审查确认 ID、启用/过期治理、召回关闭和无 Schema/写入扩权行为完整，两个轴均为 0 项。
- 本阶段没有运行完整 JVM、Lint、AndroidTest、Redmi instrumentation/真实 Provider 或 Release。第 179 阶段已仅在 Redmi 验证真实模型稳定完成 `memory.search -> memory.get`；Room v36 与 `v0.1.16 / Room v35` 发布基线不变。

## 2026-08-07 第 177 阶段：周期计划真实使用与可信答案闭环

- 新增共享可信结果解析，将 `tasks.pause / tasks.resume` 的应用生成首行同时用于受限会话终态、Workflow 快照刷新和答案级“查看任务”。唯一 execution、严格 `{name}`、typed `VERIFIED`、工具/状态一致与单行名称缺一不可；模型文本、重复 execution、状态错配和未验证结果 fail-closed。点击仍从当前 Room 按唯一精确名称解析，不保存内部 ID。
- TDD 红灯首先因 `presentTaskScheduleControlCompletion` 与刷新策略不存在而编译失败；最小实现及共享策略收口后，`TaskScheduleControlCompletionPresentationTest 4/4 + TaskScheduleControlWorkflowRefreshPolicyTest 2/2 + TaskInspectionNavigationTest 7/7 = 13/13` 通过。`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 覆盖安装 Debug 并运行真实 Provider。最终暂停 Run `run-07179fa4-f970-4727-8d98-14952e6accd0` 严格执行 `tasks.list -> tasks.inspect -> tasks.pause`；恢复 Run `run-8f66d1a8-ef4a-4ef2-a6f5-7ffa7c3d952d` 严格执行 `tasks.list -> tasks.inspect -> tasks.resume`。两次控制动作各有唯一 `APPROVED` Room 审批，全部 ToolResult 为成功且 typed `PASSED`。
- 暂停后原未来 Task 与 WorkRequest 均为 `CANCELLED`，Workflow 保持启用；恢复后原 Task 事实不变，只形成一个当前时间之后的新 Task，绑定唯一 `ENQUEUED` WorkRequest，`noBackfill=true / completionVisible=true`。最终日志同时确认 `oldTaskUnchanged=true / futureTaskUnique=true`。
- 双轴审查的 Standards 轴发现探针关键状态段缺少贴近实现的中文业务注释和 WorkInfo Future 没有超时；现已补充分段 `long` 注释并将读取限制为 10 秒。共享可信策略消除了会话终态与导航 marker 漂移风险；Spec 轴未发现遗漏或越界。
- 真实闭环结束后停用夹具 Workflow、取消残留 Work、删除临时 Profile 并恢复原 Profile。更新后的长期文档重新打入 AndroidTest APK，仅在 Redmi 运行 corpus gate，首轮为 `OK (1 test)`、耗时 `2.831s`。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；没有向已连接模拟器发送任何 ADB 命令。

## 2026-08-07 第 176 阶段：应用内周期计划暂停/恢复

- 新增仅前台直接 Agent 可见、逐次审批的 `tasks.pause(name)` 与 `tasks.resume(name)`，并由独立 `task-schedule-control` Skill 承载。`tasks.list / inspect` 显示独立周期计划状态；旧 Profile、历史 Run、任务只读/重试/取消 Skill、一次性计划和后台工具面不变。
- Room 事务验证精确唯一名称、唯一规则、schedule/workflow/task 关联和活动状态。暂停保留规则、取消未开始 Task、清空未来指针，运行实例不受影响；恢复复用规则、只生成当前时间之后的一个实例，绑定 WorkRequest 后回读。指针或终态漂移拒绝，入队失败回滚为可重试暂停态。
- TDD 首个 Skill 红灯为 `24 tests / 1 failed`，原因是 `task-schedule-control` 尚未注册；Registry 注册/门禁红灯为 `2/2 failed`，执行分派红灯为 `1/1 failed`。最小实现及审查修复后，聚焦 JVM 为 `AgentSkillsTest 24/24 + XiaoLingToolRegistryTest 58/58 = 82/82`；`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。
- 双轴审查发现并修复两项状态问题：启用规则若缺少有效活动实例会误报已恢复；恢复入队失败会留下 `enabled=true` 且指向失败 Task。修复后所有控制入口在漂移时 fail-closed，失败恢复精确回滚；重复的任务/规则解析也收敛为共享 helper。
- 仅使用 Redmi `wsvwypiz7xwslvl7` 安装和运行任务 Store 类。首轮 `12/13`，唯一失败是测试夹具只认领 Run、未调用 `markAgentRunStarted`，因此期望 RUNNING 时实际为 QUEUED；补齐既有状态推进后复验 `OK (13 tests)`。应用代码在该失败前已正确保留 RUNNING ScheduledTask，修复未放宽生产断言。
- 更新后的六份长期文档重新打入 AndroidTest assets，仅在 Redmi 运行 corpus gate 并通过 `1/1`。按快速迭代分级未运行完整 JVM、全量 Lint、默认完整 instrumentation、Release 或真实 Provider；没有向已连接的模拟器发送安装、启动、日志或测试命令。真实 Provider 自然语言暂停/恢复与可信结果导航作为下一独立阶段。

## 2026-08-07 第 175 阶段：受控系统日程创建

- 新增前台 `REQUIRES_APPROVAL` 的 `calendar.create_event`、独立 `calendar-create` Skill、`WRITE_CALENDAR` 主动授权和 `CalendarEventWriter` Provider seam。首版只接受一次性非全天事件；带偏移 ISO-8601 起止时间、IANA 时区、时间顺序和时区实际偏移均在执行前验证。
- 写入用 `CUSTOM_APP_PACKAGE + CUSTOM_APP_URI` 保存 ToolCall 稳定标记，事件 ID 作为 operation identity。重复调用先按标记回读并拒绝载荷漂移，首次结果和提交后恢复均按事件 ID 核对标题、起止时间、时区及标记；旧 Profile/Run 不自动扩权，Room v36 和后台边界不变。
- Android 官方 `CalendarContract.Calendars` 与 `CalendarContract.Events` 文档核验确认：普通事件插入需要 `DTSTART / DTEND / EVENT_TIMEZONE / CALENDAR_ID`，事件应用字段可写；无现有日历时可用 `ACCOUNT_TYPE_LOCAL` 创建本地日历。来源：<https://developer.android.com/reference/android/provider/CalendarContract.Calendars>、<https://developer.android.com/reference/android/provider/CalendarContract.Events>。
- TDD 红灯首先因 `CalendarEventWriter` 与 Registry 注入点不存在而编译失败；最小实现后聚焦 JVM 为 `XiaoLingToolRegistryTest 56/56 + AgentSkillsTest 23/23 + LegacyRunToolBoundaryTest 2/2 + ToolExecutionRecoveryEvidencePolicyTest 3/3 = 84/84`。`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。
- 仅向 Redmi `wsvwypiz7xwslvl7` 安装和执行。首轮权限页 `2/2` 通过，但 Provider 安全字段确认日历表为空 `[]`，Writer 正确返回 `NoWritableCalendar`；加入 LOCAL“小灵”日历兜底后，真实事件创建、同 ToolCall 重放命中同一事件 ID、提交回执再验证、按事件 ID 清理和本轮临时日历精确清理全部通过，与权限页合计 `OK (3 tests)`。测试 APK 已卸载，主应用数据和 Provider 配置保留。
- 双轴审查发现并修复真机探针在“已有其他可写日历但没有小灵日历”时可能误判清理目标的问题：现在只有本轮事件实际落在新出现的小灵日历时才记录其 ID，并按精确 calendar ID 清理。最终长期文档重新打入 AndroidTest APK 后，仅在 Redmi 运行 corpus gate 并通过 `1/1`。按快速迭代分级未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release，也未向模拟器发送命令。

## 2026-08-07 第 174 阶段：个人事项简报

- 新增独立 SAFE `personal-briefing` Skill，只在显式个人简报与笔记关键词目标下组合现有 `calendar.list_events / tasks.list / notes.search / notes.get`；`READ_CALENDAR` 主动授权、原 `day-overview`、旧 Profile、Room v36、审批和后台边界均不改变。
- TDD 红灯为 `AgentSkillsTest 22 tests / 1 failed`，失败原因是新 Skill 尚未实现；加入 Skill 后第二轮仍为 `1 failed`，定位为复合目标同时命中旧日历/任务/笔记 Skill，新 Skill 未进入前三。收紧组合短语评分后 `AgentSkillsTest 22/22` 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL in 13s`。
- Redmi `wsvwypiz7xwslvl7` 覆盖安装后确认 `READ_CALENDAR: granted=true`。真实 Run `run-c411e92c-c81c-469d-a10f-2fac5497cd4f` 为 `COMPLETED`，选择 `personal-briefing` 并严格执行 `calendar.list_events -> tasks.list -> notes.search -> notes.get`；四项结果均通过 typed 验证，未来 1 天参数、唯一关键词、稳定 ID 传递、全文尾标、本地数据边界、零审批和日程/任务/笔记分区均成立。
- 真实 Provider 从开始到成功约 `42.23s`，临时 Profile 和测试笔记已清理，主应用与用户 Provider/数据保留。最终文档 corpus gate 仅在 Redmi 运行并通过；本阶段没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release，也没有向模拟器发送安装、启动、日志或测试命令。

## 2026-08-07 第 173 阶段：版本化本地笔记编辑闭环

- Room 升级至 v36：旧笔记迁移后 `revision=1`，编辑使用 revision 条件更新并在同一事务写入独立 operation 账本；版本漂移、tombstone、载荷漂移或恢复结果漂移均拒绝覆盖。新增前台 `REQUIRES_APPROVAL` 的 `notes.update`、独立 `local-note-update` Skill 和用户笔记编辑 UI，旧 Profile/Skill/Run 不自动扩权。
- 聚焦 JVM `AgentSkillsTest 21/21 + LegacyRunToolBoundaryTest 2/2 + XiaoLingToolRegistryTest 53/53 = 76/76` 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL`。仅 Redmi `wsvwypiz7xwslvl7` 运行数据库迁移、Room Note Store、ViewModel 与 Compose 四个定向类，最终 `OK (42 tests)`、耗时 `13.984s`。
- Redmi 真实 Run `run-4f5e33bd-5494-4a24-a6cb-8cf49ab2da44` 为 `COMPLETED`，选择 `local-note-update` 并严格执行 `notes.search -> notes.get -> notes.update`；审批为 `APPROVED`，稳定 ID、`expectedRevision=1 / resultRevision=2`、结果回读、operation 恢复验证和历史创建阻断均通过。临时 Profile 和测试笔记已清理。
- 一次排查时误加 `--receiver-foreground`，MIUI 在约 10 秒真实模型尚未返回时以 `bg anr` 结束 Debug 进程；去掉该标志并显式指定 Receiver 后约 32 秒完成，上述通过样本不包含该失败尝试。最终文档 corpus gate 仅在 Redmi 运行并通过；测试包已卸载，主应用与用户 Provider/数据保留。按分级验证未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release，未向模拟器发送任何安装、启动、日志或测试命令。

## 2026-08-07 第 172 阶段：Agent 受控删除本地笔记

- 新增 `notes.delete(note_id)` 与独立 `local-note-delete` Skill。工具仅前台、需要确认、要求标准稳定 note ID，执行生产 Room tombstone 后回读不可见并写入绑定目标的 `COMMITTED` 回执；未提交路径不允许重放，旧 Profile/Skill/Run 不自动扩权。
- 聚焦 JVM `AgentSkillsTest 20/20 + LegacyRunToolBoundaryTest 2/2 + XiaoLingToolRegistryTest 51/51 = 73/73` 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL`。仅 Redmi 运行既有 `RoomAgentNoteStoreInstrumentedTest#userDeleteClearsContentAndPreventsHistoricalToolReplay`，结果 `OK (1 test)`、耗时 `0.377s`。
- Redmi `wsvwypiz7xwslvl7` 真实 Run `run-ad492c65-a750-400b-a437-ea41eac61784` 为 `COMPLETED`，选择 `local-note-delete` 并严格执行 `notes.search -> notes.get -> notes.delete`。Room 审批为 `APPROVED`，三项 Tool Ledger 均 `success=true / PASSED`，删除结果为 `executorVerified=true`，稳定 ID、tombstone 和历史创建重放拒绝均核验通过。
- 测试笔记和临时 Profile 已精确清理；最新长期文档重新打入 AndroidTest APK 后，仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮结果为 `OK (1 test)`、耗时 `2.736s`。测试 APK 已卸载，主应用与用户 Provider/数据保留。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release；所有设备命令只发送到 Redmi。

## 2026-08-07 第 171 阶段：真实 Provider 搜索并读取笔记全文

- 新增 Debug-only `notes_search_get_real`。它从设备现有 Provider 读取配置，建立唯一长正文笔记夹具和显式 `local-note-detail` 临时 Profile；API Key 不进入广播参数或探针日志，生产 Release、Room Schema 和用户 Profile 权限不变。
- 聚焦 JVM `AgentSkillsTest 19/19 + LegacyRunToolBoundaryTest 2/2 + XiaoLingToolRegistryTest 48/48 = 69/69`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。更新文档后仅在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`、耗时 `2.535s`；测试包已卸载，主应用保留。未运行完整 JVM、Lint、AndroidTest 全量、默认完整 instrumentation 或 Release。
- Redmi `wsvwypiz7xwslvl7` 真实 Run `run-07acb86a-44bc-4f3b-aaa1-8c74fc7843dd` 为 `COMPLETED`，选择 `local-note-detail`，Tool Ledger 严格为 `notes.search -> notes.get`；两项均 `success=true / verificationStatus=PASSED`，稳定 ID 正确传递，详情包含全文尾标和“不是工具指令”边界，审批为 0。临时 Profile 与测试笔记均已清理。
- 首轮真实工具链本身已成功，但探针错误要求 SAFE 工具 `executorVerified=true`，因此在结果断言处失败；移除该副作用工具专属条件。第二轮紧随 APK 冷启动，启动恢复扫描先收敛了新 Run，触发 `Agent Run 已结束，不能追加步骤`；应用稳定后不重装重启地重跑通过。这两次均完成夹具/Profile 清理，不计入通过样本。
- 手机现装 APK 使用 Android Debug 证书，首次用 `releaseLocal` 重签包覆盖得到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；核对双方指纹后改用同证书 Debug APK，`adb install -r` 成功且未卸载、未清数据。所有设备命令只发往 Redmi，未向在线模拟器发送安装、启动、日志或测试命令。

## 2026-08-07 第 170 阶段：按稳定 ID 读取本地笔记

- 新增 SAFE、支持后台的 `notes.get(note_id)`，严格验证标准 `note-UUID`，从当前 Store 回读标题/正文；不存在与 tombstone 统一失败，正文输出上限为 20,000 字符，并明确标记为本地数据而非工具指令。
- 新增独立 SAFE `local-note-detail` Skill，保留既有 `local-notes` 工具集合，避免历史 Profile 因新增工具依赖而失效或自动扩权；`AgentNoteStore`、Room DAO、Schema、写入审批和恢复边界不变。
- 第 170 阶段落地时只同步 Registry/Skill/legacy 工具集合及 JVM 测试源码断言，按当时用户要求未执行验证；第 171 阶段现已补齐聚焦 JVM、Debug APK 与 Redmi 真实 Provider 使用闭环。

## 2026-08-07 小灵 v0.1.16 发布构建

- 当前发布版本：小灵 `v0.1.16`，`versionCode=17`、`minSdk=26`、`targetSdk=36`、Room v35。
- 发布范围：`v0.1.15` 后第 128 至 169 阶段；覆盖完整个人 Agent 主链、目标级验证、应用内提醒、任务恢复/诊断/重试/取消、只读日历、本地笔记、启动中断 Run 提示和答案级任务/笔记导航。
- 本地发布构建只执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleRelease`，结果为 `BUILD SUCCESSFUL in 2m 38s`。构建内部正常经过 R8 与 `lintVitalRelease`，没有额外运行 JVM、完整 Lint、Debug/AndroidTest、Redmi 安装、instrumentation 或其他验收。
- 正式 APK 为 `3,400,350` 字节，SHA-256 为 `971f0c457c3a802d3bb41bd31ac58fda2c1ee0eebbe6f2967ec428299d801126`。本轮没有额外执行签名、zipalign、版本回读或真机安装复核；既有阶段证据不冒充本次发布门禁。
- 发布提交为 `4fed1d712247c82d61de43cfc949dacd0e5fc8a9`（`发布小灵 0.1.16`）；annotated tag `v0.1.16` 的 tag object 为 `fa04c9ce163309dd63a7d9bde1694e1845173c92`，解引用到该提交。[小灵 v0.1.16](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.16) 已发布并成为 latest，状态为非草稿、非预发布；两个远端资产均为 `uploaded`，APK digest 与本地产物一致。
- 后台设备自动化、任意 App、生产 answerability enforcement、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续关闭。

## 2026-08-07 第 169 阶段：创建笔记后的答案级导航

- `notes.create` 与恢复验证成功结果现在附带 `· id=<noteId>`；导航只接受成功、typed `VERIFIED`、完整 `title/content` 参数、标题精确绑定和全文唯一合法 note ID。审批、回读、幂等回执和失败语义不变。
- 聚焦 JVM `LocalNoteNavigationTest 4/4 + XiaoLingNavigationCoordinatorTest 8/8 + TaskInspectionNavigationTest 5/5 + XiaoLingToolRegistryTest 46/46 + AgentRunResumePolicyTest 57/57` 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 成功。仅 Redmi `wsvwypiz7xwslvl7` 的 `ConversationPageInstrumentedTest` 为 `OK (10 tests)`，文档 corpus gate 为 `OK (1 test)`；测试包已卸载，主应用保留，Pixel_9 未使用。
- 按分级验证，本阶段不运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-07 第 168 阶段：答案级本地笔记导航

- `notes.list / notes.search` 结果下新增“查看笔记”入口；纯策略同时校验工具名、成功状态、非失败验证、参数契约、固定结果标题和唯一标准 `note-UUID` 条目。入口点击只携带 note ID，`LocalNoteManagementPage` 再通过 ViewModel/Room Store 读取完整详情，删除或不存在继续由当前事实失败。
- 聚焦 JVM `LocalNoteNavigationTest 3/3 + XiaoLingNavigationCoordinatorTest 8/8 + TaskInspectionNavigationTest 5/5` 通过；`:app:assembleDebug :app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；仅 Redmi `wsvwypiz7xwslvl7` 的 `ConversationPageInstrumentedTest` 为 `OK (9 tests)`，文档 corpus gate 为 `OK (1 test)`。测试包已卸载，主应用保留，Pixel_9 未使用。
- 按分级验证，本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；一次错误 runner 参数导致的全量 instrumentation 已在未完成时中止，不计入通过数，也没有写入任何验收结论。

## 2026-08-07 第 167 阶段：中断筛选空状态与历史回退

- `AgentTaskCenterPage` 在 `INTERRUPTED` 筛选为空时显示失败/取消复盘边界和不会重放工具的说明；若仍有其他历史，提供“显示全部”本地筛选回退，空数据库不展示该按钮。
- 聚焦 JVM `AgentTaskFilterPolicyTest 5/5 + StartupRunRecoveryNoticePolicyTest 5/5 = 10/10`，`:app:assembleDebug :app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。仅在 Redmi `wsvwypiz7xwslvl7` 运行文档 corpus gate，结果 `OK (1 test)`；测试包已卸载，Pixel_9 未使用。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-07 第 166 阶段：恢复入口聚焦中断 Run

- 新增 `AgentTaskFilter.INTERRUPTED`，只匹配 `FAILED / CANCELLED`；启动恢复动作进入 Agent 任务中心时使用该初始筛选，设置页手动入口仍使用 `ALL`，用户后续筛选不会因 Room 刷新被覆盖。
- 聚焦 JVM `AgentTaskFilterPolicyTest 4/4 + StartupRunRecoveryNoticePolicyTest 5/5 = 9/9`，`:app:assembleDebug :app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。仅在 Redmi `wsvwypiz7xwslvl7` 运行文档 corpus gate，结果 `OK (1 test)`；测试包已卸载，Pixel_9 未使用。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-07 第 165 阶段：启动恢复提示直达任务中心

- 实现 `OperationResultAction.OPEN_AGENT_RUN_HISTORY`：仅启动恢复的 `FAILED / CANCELLED` 汇总提示携带该动作；普通 `OperationResult` 默认无动作，原有隐私文案和一次性消费语义不变。
- `CenterNotice` 在存在该动作时显示“查看任务”按钮；点击刷新 Agent Run 历史、打开 `AGENT_RUN_HISTORY`，并立即清除提示。未点击或自动消失不会导航，不传递 Run/Workflow ID。
- `StartupRunRecoveryNoticePolicyTest` 定向 `5/5` 通过；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。仅在 Redmi `wsvwypiz7xwslvl7` 运行文档 corpus gate，最终 `OK (1 test)`；测试包已卸载，未使用 Pixel_9。本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-07 第 164 阶段：启动中断 Run 用户提示

- 已实现 `settleStartupInterruptedRuns()` 与 `projectStartupRunRecoveryNotice()`；启动恢复继续先保留三类可恢复 Run，再关闭其余旧进程候选。只有 `closeInterruptedRuns()` 实际收敛后回读为 `FAILED / CANCELLED` 的记录进入提示。
- 提示通过现有 `OperationResult -> CenterNotice -> clearResult()` 一次性消费，只显示失败/取消数量、不重放工具和任务中心指引，不暴露目标、Run ID、原始错误或工具正文。
- 聚焦 JVM `StartupRunRecoveryNoticePolicyTest 5/5`，`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。六份长期文档更新后，仅在 Redmi `wsvwypiz7xwslvl7` 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，最终结果 `OK (1 test)`、耗时约 `2.7s`；测试包已卸载，主应用保留；Pixel_9 未接收命令。按分级验证未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。

## 2026-08-07 第 163 阶段：取消结果后的任务快照刷新

- 已实现 `shouldRefreshWorkflowsAfterTaskCancel()`：复用可信取消终态投影，只有唯一 `tasks.cancel`、成功、typed `VERIFIED` 且命中应用生成稳定文案时才允许刷新 Workflow 快照。
- `XiaoLingViewModel.sendAgentRun()` 在普通 Agent 会话状态更新并发起保存后触发 `refreshWorkflows()`；该刷新重新读取 Workflow、ScheduledTask、周期规则、Run 与设备证据，避免任务中心继续显示取消前的旧状态。Workflow 来源和重试路径不受影响。
- 聚焦 JVM `TaskCancelWorkflowRefreshPolicyTest 4/4 + TaskCancelCompletionPresentationTest 4/4 = 8/8`，`:app:assembleDebug :app:assembleDebugAndroidTest` 构建成功。六份长期文档更新后，仅在 Redmi `wsvwypiz7xwslvl7` 运行 corpus gate，结果 `OK (1 test)`、耗时 `2.64s`；Pixel_9 未接收命令。按分级验证未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。

- 第 162 阶段取消结果任务中心导航已完成：`tasks.cancel` Tool part 复用现有“查看任务”入口；导航要求成功、typed `VERIFIED`、唯一 `name` 参数、首行精确任务名和应用生成的取消状态前缀，点击时由 Room 当前快照重新解析唯一 Workflow。模型伪造、失败/未验证、换行注入和同名均 fail-closed。
- 聚焦 JVM `TaskInspectionNavigationTest 5/5`，Debug APK 构建成功；本阶段未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。文档更新后仅在 Redmi `wsvwypiz7xwslvl7` 运行 corpus gate，结果 `OK (1 test)`，未向 Pixel_9 发送命令。

- 第 161 阶段受控任务取消会话终态已完成：新增纯 Kotlin `TaskCancelCompletionPresentation`，只接受唯一 `tasks.cancel`、`success=true`、typed `AgentVerificationStatus.VERIFIED` 和应用生成的稳定结果前缀；普通 `/agent` 将摘要与 assistant 结果写入同一会话快照，Workflow 内、重复调用、未验证结果和未知文案均不生成摘要。
- 聚焦 JVM `TaskCancelCompletionPresentationTest 4/4 + TaskRetryCompletionPresentationTest 4/4`，均为 `0 failure / 0 error / 0 skipped`；`:app:assembleDebug` `BUILD SUCCESSFUL`。本阶段未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。
- 六份长期文档更新后重新构建 AndroidTest APK，并仅在 Redmi `wsvwypiz7xwslvl7` 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`；未向 Pixel_9 或其他模拟器发送命令。

- 第 160 阶段受控任务取消已完成：新增独立 `tasks.cancel` 前台工具和 `task-cancel` Skill。工具必须独立审批，只按精确任务名称解析唯一活动 ScheduledTask；同名、多实例、缺失或状态漂移 fail-closed，不中断前台手动 Run。Room `STOP_REQUESTED` 栅栏、WorkManager cancel 和 fallback reconcile 共同收敛取消事实，重复取消读取持久化状态保持幂等，迟到结果不能覆盖 `CANCELLED`。
- 聚焦 JVM `XiaoLingToolRegistryTest + AgentSkillsTest` 修复后通过（合计 `64 tests completed`），`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。Redmi `wsvwypiz7xwslvl7` 的 `RoomAgentTaskStoreInstrumentedTest` 为 `OK (9 tests)`。
- Redmi 真实 Provider `task_cancel_real` 严格执行 `tasks.list -> tasks.inspect -> tasks.cancel`，最终日志为 `task-cancel-real success=true ... taskStatus=CANCELLED taskCancel=true oldRunUnchanged=true`，并确认 `cleanup=true workflowDisabled=true temporaryProfileRemoved=true`。测试包检查时本来未安装，卸载命令因此返回 `DELETE_FAILED_INTERNAL_ERROR`；正式包保留安装，未使用 Pixel_9。
- 按快速迭代分级约束，本阶段未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation；AndroidTest APK 仅完成构建，未把局部结果冒充全量门禁。

- 第 159 阶段受控任务重试用户可见终态已完成：新增纯终态投影和 ViewModel 完成/失败/取消接线，排队 ToolResult 不再被当成 Workflow 完成。聚焦 JVM `TaskRetryCompletionPresentationTest 4/4 + TaskRetryLaunchPolicyTest 2/2 + XiaoLingToolRegistryTest 44/44 = 50/50`，`:app:assembleDebug` 成功。Debug APK 只覆盖安装到 Redmi `wsvwypiz7xwslvl7`；真实 Provider `task_retry_real` 日志为 `sourceRunStatus=FAILED / retryRunStatus=COMPLETED / retryRunLinked=true / reusedSteps=1 / oldRunUnchanged=true / foregroundWorkflow=true / completionVisible=true`，并确认 `cleanup=true workflowDisabled=true temporaryProfileRemoved=true`。AndroidTest APK 仅为文档 corpus gate 构建并运行单项；未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；模拟器未接收安装、启动或测试命令。

- 第 158 阶段真实 Provider 受控重试已完成：Redmi `wsvwypiz7xwslvl7` 上 Debug-only `task_retry_real` 最终运行成功，真实工具顺序为 `tasks.list -> tasks.inspect -> tasks.retry`，三项 Tool Ledger 均 `success=true / verificationStatus=PASSED`。生产 `TaskRetryLaunchPolicy` 通过后，新 Run 与来源 Run 正确关联；来源保持 `FAILED`，首个步骤复用为 `SKIPPED`，第二个 `app.current_time` 步骤由关联前台 Workflow 真实执行并完成目标级收敛。清理日志确认临时 Profile 已删除、夹具 Workflow 已停用。局部 JVM、Debug/AndroidTest APK 和 Redmi 文档 corpus gate `OK (1 test)` 通过；未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release，Pixel_9 未接收命令。

- 第 157 阶段开发验证已完成：`tasks.retry` 受控重试、前台接管和 stale Registry context 修复均已落地。聚焦 JVM 四个类共 `130/130` 通过，Debug/AndroidTest APK 构建成功；仅 Redmi `wsvwypiz7xwslvl7` 安装并运行 `RoomAgentTaskStoreInstrumentedTest`，结果 `OK (8 tests)`。本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；Pixel_9 仅在设备列表枚举，未接收命令。

- 上一发布版本：小灵 `v0.1.15`，`versionCode=16`、`minSdk=26`、`targetSdk=36`、Room v33。
- 发布范围：`v0.1.14` 后第 122 至 127 阶段；包含 `device.swipe` 的专属安全契约、执行期/完成态 evidence、答案级脱敏投影、生产默认接线与 Redmi 限定验收，以及自然语言个人任务、严格 1 至 8 步计划、确认前零执行和确认后原子创建普通 Workflow/Run。
- 发布提交：`b42defa06f02000b841ad7688e76edcf8bc8ce55`（`发布小灵 0.1.15`）；annotated tag `v0.1.15` 的 tag object 为 `7ecfd5269a2822feffbbda88cad0f53964f89aac`，解引用到该提交。[小灵 v0.1.15](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.15) 已发布并成为 latest，状态为非草稿、非预发布。
- 正式产物：`outputs/release/xiaoling-v0.1.15.apk`，大小 `3,318,322` 字节，SHA-256 为 `a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`；使用现有 `releaseLocal` 配置构建，但本轮未额外执行 `apksigner`、zipalign 或证书复核。
- 本地发布构建：只执行 `:app:assembleRelease`，结果为 `BUILD SUCCESSFUL in 1m 52s`。构建内部正常经过 R8 与 release lintVital task，但没有单独运行完整 JVM、完整 Lint、Debug/AndroidTest APK 或其他测试任务。
- Redmi 发布验收：按用户“不要验证，直接发版”的明确要求，本轮没有安装 `v0.1.15`，没有运行 instrumentation、冷启动、版本回读、Accessibility 或 crash 收尾，也没有向 Pixel/模拟器发送 ADB 命令。第 126/127 阶段既有 Redmi 聚焦证据保留为功能阶段事实，不记作本次发布门禁。
- 当前开发主线：第 127 至 132 阶段完整个人 Agent 主线已经完成，开发数据库保持 Room v35；第 133 至 159 阶段继续真实使用打磨。第 152 至 154 阶段完成本地笔记写入、只读管理和受控删除，第 155 至 159 阶段形成“任务清单 -> 最近运行受限诊断 -> 答案级查看任务 -> 受控重试 -> 真实执行 -> 用户可见终态”闭环。第 132 阶段完整 JVM `879/879`、Lint `0 error`、Debug/AndroidTest APK、三条 Redmi 完整任务和默认 instrumentation `282/282` 继续作为最近完整门禁；自然 LMK、主动断网与 5 至 10 分钟任务仍无证据。任务取消/停止仍需分别设计审批与副作用语义；后台设备自动化、恢复旧执行栈、坐标、截图、任意 App、笔记编辑/分页/批量/后台写入、JSON/SAF、生产 answerability enforcement、精确定时和 Foreground Service 继续关闭。
- 第 142 阶段验证：聚焦 JVM `17/17`、`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。只使用 Redmi `wsvwypiz7xwslvl7` 运行 `ConversationPageInstrumentedTest + WorkflowManagementPageInstrumentedTest`，结果为 `OK (18 tests)`；没有向 Pixel_9 或其他模拟器发送命令。该入口只改变导航与列表展示，不改变 Room、Runtime、审批、权限或执行逻辑。
- 第 143 阶段验证：导航 Saver 同时保存知识文档和 Workflow 两个一次性目标，Activity 重建后可恢复目标定位；本阶段只做聚焦 JVM 编译/测试与 Debug 编译，不重复 Redmi instrumentation、完整 JVM、Lint、AndroidTest APK 或 Release。
- 第 144 阶段验证：`XiaoLingToolRegistryTest 37/37 + AgentSkillsTest 11/11`，聚焦 JVM 合计 `48/48`；Debug 与 AndroidTest APK 构建成功。只向 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 安装并运行 `RoomAgentTaskStoreInstrumentedTest`，结果 `OK (3 tests)`、耗时 `1.828s`；在设备列表中看到的 `emulator-5554` 未接收安装、instrumentation 或功能命令。
- 文档语料门禁：第 151 阶段六份长期文档重新打包后，仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮结果 `OK (1 test)`、耗时 `2.471s`。该结果属于当前开发主线，不表述为 `v0.1.15` 发布复验；历史阶段证据保持不变。
- 第 145 阶段验证：聚焦 JVM 六组 `95/95`；完整 `testDebugUnitTest` 的 136 份 XML 合计 `904/904`，`0 failure / 0 error / 0 skipped`；`:app:assembleDebug` 成功。仅在 Redmi `wsvwypiz7xwslvl7` 覆盖安装，Accessibility 最终为 `Enabled / Bound / Crashed services:{}`。真实 Run `workflow-run-e1b22a9e-28f9-468a-9046-a5830c0c4f7f` 三个步骤均为 `COMPLETED`；Tool Ledger 顺序为 `app.current_time`、`device.snapshot / device.open_app / device.snapshot`、`device.snapshot / device.back`，六条结果全部 `verificationStatus=PASSED`，两个动作均 `executorVerified=1`。实际打开 `com.google.android.deskclock`，最终前台 `com.longdev.xiaoling`，持久化目标级 Decision 为 `VERIFIED / ALL_CRITERIA_VERIFIED`。历史失败 Run `workflow-run-c6e2c804-2f47-4085-919b-3c300406b1d8` 及其来源链未修改。本阶段未运行全量 Lint、AndroidTest APK、默认 instrumentation 或 Release。
- 第 146 阶段验证：真实 Agent Run `run-9736a67f-0662-487c-ac77-489f6132f82f` 使用 `gpt-5.6-luna` 调用 `tasks.list`，Run 为 `COMPLETED`，ToolResult 为 `success=true / verificationStatus=PASSED`，返回 1 条任务。聚焦 JVM 生命周期与重试策略、Debug/AndroidTest APK 通过；仅 Redmi 运行新增 Room 单项，最终 `OK (2 tests)`，更新后文档 corpus 为 `OK (1 test)`。真实来源 Run `workflow-run-cdaaf42d-aa85-44ef-95a9-9ab972ed8f2d` 保持 `FAILED` 且三步动作事实不变；关联新 Run `workflow-run-3e4b422e-d48e-4244-b21a-2668a980fe10` 三步全部 `SKIPPED/已复用`，没有重放模型或设备动作，目标级 Decision 为 `VERIFIED / ALL_CRITERIA_VERIFIED`。本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 发布阶段设备收尾：`v0.1.15` 发布当时未执行安装验收；后续开发阶段已在 Redmi 覆盖安装 `0.1.15 (16)` Debug 并完成第 147 阶段真实 Workflow。该开发验证不追溯记作发布门禁。

## 2026-08-07 第 159 阶段：受控任务重试用户可见终态

- 新增 `TaskRetryCompletionPresentation`。生产投影只接收任务名、Workflow 终态和复用步骤数；任务名换行归一化并限制 100 字符，`QUEUED / RUNNING` 不生成终态结果，内部 Run/Step ID、原始错误和步骤正文不能进入输出。
- `XiaoLingViewModel` 在关联重试成功完成、取消或失败并由 Repository 收敛后，才向原会话追加一次结果；成功明确任务已完成和旧 Run 不变，失败/取消明确不恢复或重放旧执行栈并引导任务中心。`tasks.retry` 原 ToolResult 继续只表示提交排队。
- 聚焦 JVM 三类合计 `50/50`，`:app:assembleDebug` 为 `BUILD SUCCESSFUL`。Debug APK 只安装到 Redmi；没有对 `emulator-5554` 发送安装、启动、instrumentation 或功能命令。
- Redmi 真实 Provider 复用 `task_retry_real`：工具顺序仍为 `tasks.list,tasks.inspect,tasks.retry`，最终日志为 `success=true`、来源 `FAILED`、关联新 Run `COMPLETED`、复用 1 步、旧 Run 不变、`completionVisible=true`；生产投影同时断言新旧 Run ID 均未进入文案。清理日志确认夹具停用、临时 Profile 删除。
- 本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；AndroidTest APK 仅为六份长期文档同步后的文档 corpus gate 构建，随后 Redmi 单项复验 `OK (1 test)`、耗时 `2.797s`。

## 2026-08-07 第 158 阶段：真实 Provider 受控任务重试闭环

- Debug-only 广播 `com.longdev.xiaoling.debug.AGENT_E2E / task_retry_real` 创建可清理的失败 Workflow 夹具，夹具包含两个已完成步骤，随后故意将来源 Run 收敛为 `FAILED`；没有用生产工具制造额外副作用。
- Redmi 真实 Provider 的最终成功运行打印 `success=true`：工具顺序严格为 `tasks.list,tasks.inspect,tasks.retry`；`sourceRunStatus=FAILED`、`retryRunStatus=COMPLETED`、`retryRunLinked=true`、`reusedSteps=1`、`oldRunUnchanged=true`、`foregroundWorkflow=true`、`finalizationOnly=false`。同时验证关联 Workflow 的第二步 `app.current_time` 为 `success=true / PASSED`；最终输出 `cleanup=true workflowDisabled=true temporaryProfileRemoved=true`。此前一次运行暴露的 Workflow Profile 隐藏工具接线问题已修复并由最终运行复验。
- 真实 Run 使用正式 `AgentRunUseCase`、`XiaoLingToolRegistry`、Room Tool Ledger、审批门禁和当前 Provider；`TaskRetryLaunchPolicy.resolve/canStart` 与 `RoomWorkflowRepository.verifyTaskRetry` 均通过。成功前缀不重放，首个未完成步骤由关联 Workflow 重新调用并验证 `app.current_time`。`AgentRunUseCase` 同时按当前 invocation context 裁剪 Profile 工具白名单，保持 `tasks.retry` 的直接 Agent 边界。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest --tests com.longdev.xiaoling.agent.TaskRetryLaunchPolicyTest --tests com.longdev.xiaoling.agent.XiaoLingToolRegistryTest --tests com.longdev.xiaoling.automation.WorkflowStepExecutionPolicyTest --stacktrace --console=plain`：`BUILD SUCCESSFUL`。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain`：`BUILD SUCCESSFUL`；两个 APK 只安装到 Redmi。`RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`：`OK (1 test)`。本阶段没有运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-07 第 157 阶段：受控任务重试

- 实现：新增 `tasks.retry(name)` 和独立 `task-retry` Skill。工具只允许前台直接 Agent，风险为 `REQUIRES_APPROVAL`，执行器验证为 `EXECUTOR_VERIFIED`，重放安全为 `IDEMPOTENT_BY_KEY`；Workflow 内递归与后台调用从工具面隐藏并拒绝。
- 持久化：Room 事务按精确任务名称匹配唯一 Workflow，读取该任务当前最新 Run；仅 `BLOCKED / FAILED / CANCELLED` 且通过 `WorkflowRunRetryPolicy` 的 Run 可以创建关联新 Run。ToolCall ID 派生确定性 Run ID，重复调用只回读仍为最新 `QUEUED` 且步骤只含 `SKIPPED / PENDING` 的提交。已启动、被其他运行取代、步骤状态变化、身份漂移、停用、完成态、缺失证据和同名任务均拒绝；时间戳极值也 fail-closed。
- 旧事实保护：成功前缀在新 Run 标记 `SKIPPED` 并记录 `reusedFromStepId`，来源 Run、步骤、结果、错误与已有副作用不修改。Agent 可见结果不含 Workflow/Run/Step ID、原始错误或步骤正文。
- 前台接管：`TaskRetryLaunchPolicy` 只接受完成的 Agent Run，重新核对 Room Tool Ledger、typed `PASSED`、提交回执、同会话和新 Run 可启动条件；`XiaoLingViewModel` 进程内按 Run ID 去重后执行一次关联 Workflow，模型总结不参与启动判断。`AgentRunUseCase` 在规划前显式绑定当前 context，避免跨 Run Registry 状态残留。
- 验证：`XiaoLingToolRegistryTest 44/44`、`AgentSkillsTest 17/17`、`TaskRetryLaunchPolicyTest 2/2`、`MinimalAgentRuntimeTest 67/67`，合计 `130/130`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；Redmi `RoomAgentTaskStoreInstrumentedTest` `OK (8 tests)`。真实 Provider 的完整闭环在第 158 阶段通过 Debug-only `task_retry_real` 另行验收。

## 2026-08-07 第 156 阶段：任务诊断答案级导航

- 实现：可信 `tasks.inspect` Tool part 增加“查看任务”按钮；点击经 `ConversationActions.openInspectedTask(name)` 请求 ViewModel 重新加载 Room Workflow UI 数据，完成后才解析定位。最新快照唯一精确名称匹配时传入 ID，删除、重命名、读取失败、缺失或同名时传 `null` 并降级到通用列表。
- 信任边界：入口要求 `toolName=tasks.inspect`、`success=true`、验证状态非 `FAILED`、参数键严格为 `{name}`、名称非空且最多符合工具 Schema 的 100 字符、结果首行为“任务最近运行”。普通模型文本不能投影为 Tool part；点击不调用发送、任务执行、修改或重试路径。
- JVM 与构建：`TaskInspectionNavigationTest` 通过；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL in 16s`。新增 AutoMirrored 图标后没有新的弃用警告，项目既有 `LocalClipboardManager` 警告仍在。
- Redmi 定向验收：设备确认为 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`；两个 Debug APK 定向覆盖安装成功，`ConversationPageInstrumentedTest` 为 `OK (9 tests)`、`13.33s`。新增用例使用真实 `AGENT_RESULT + VerifiedAgentContext`，点击“查看任务”后只回调“每日回顾”且 `sendCount=0`。
- 视觉证据边界：Redmi 主应用当前生产会话中只有既有 `tasks.list` 消息，没有可直接点击的 `tasks.inspect` 消息；本阶段没有通过篡改 Room、伪造生产消息或临时扩权 Profile 制造视觉样本。第 155 阶段真实 Provider `tasks.list -> tasks.inspect` 与 Room 受限投影证据继续作为上游工具事实。
- 文档 corpus：六份长期文档进入 AndroidTest 资产后，Redmi `com.longdev.xiaoling.storage.RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、`2.777s`。首次沿用旧包名 `com.longdev.xiaoling.knowledge...` 时仅产生 `ClassNotFoundException`，测试未执行；确认源码当前包名后重跑通过。
- 验证边界：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；设备列表中的 `emulator-5554` 仅被 `adb devices -l` 枚举，未接收安装、instrumentation、日志或功能命令。

## 2026-08-07 第 155 阶段：任务最近运行只读诊断

- 实现：新增 SAFE、仅前台的 `tasks.inspect(name)` 和 `AgentTaskStore.inspect()`；`task-overview` Skill 先列任务，再按精确名称读取最近 Run。名称不存在返回明确空结果，同名任务失败关闭，不新增任务修改、取消、重试或后台能力。
- 隐私边界：Room 只投影任务名称/目标/启停、Run 状态/触发/时间、步骤序号/状态和稳定诊断枚举。Workflow/Run/Step ID、原始错误、步骤详情、输入输出快照、模型文本、工具参数和 ToolResult 正文不进入工具结果；Run 详情缺失收敛为证据不完整。
- JVM 与构建：`XiaoLingToolRegistryTest 41/41 + AgentSkillsTest 16/16 = 57/57`；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`。
- Redmi Room：只向 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 安装 APK 并运行 `RoomAgentTaskStoreInstrumentedTest`，结果 `OK (5 tests)`、`2.03s`；覆盖既有列表、200 条干扰 Run、计划时间、失败步骤分类、同名拒绝和不存在任务。
- 真实 Provider：Debug 入口使用当前 `gpt-5.6-luna + Responses`、正式 `AgentRunUseCase` 和只含 `tasks.list / tasks.inspect` 的临时 Profile。Run `run-91db12f3-7b7d-445f-bf19-3a4ef92be06e` 为 `COMPLETED`，工具顺序严格为 `tasks.list -> tasks.inspect`，两项均 `success=true / PASSED`，受限投影字段检查通过，最终回答长度 749。
- 文档 corpus：六份长期文档进入 AndroidTest 资产后，只在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮为 `OK (1 test)`、`2.607s`；记录本条后重新构建并复验最终资产。
- 验证边界：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；设备列表中的 `emulator-5554` 未接收安装、instrumentation、日志或功能命令。

## 2026-08-07 第 154 阶段：本地笔记受控删除

- 实现：`AgentNoteManagementStore` 只为用户管理页扩展 `delete(id)`；生产 Agent Registry 继续依赖不含删除的 `AgentNoteStore`。详情页提供删除入口，二次确认后才提交；删除中禁用重复确认和取消。
- 幂等与隐私：生产删除不硬删整行，而是在 Room 事务中清空 title/content、保留 note ID 与 idempotencyKey。活动 get/list/search 过滤 tombstone；历史 `notes.create` 命中同键时抛出 `AgentNoteDeletedException`，不能恢复已撤回正文。Room 仍为 v35，无 Schema 迁移。
- 提交边界：ViewModel 确认前不调用 Store；删除成功后立即移除当前快照并刷新原列表/搜索。若刷新失败，成功 notice 保持，错误明确说明列表刷新失败；删除异常则保留确认目标供用户重试。
- 构建与 JVM：`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`；`XiaoLingToolRegistryTest` 为 `40/40`。
- 独立复核：发现删除已经提交但刷新失败时会清空其余笔记快照；现已改为保留已移除目标后的快照。ViewModel 测试新增剩余条目可见断言，页面测试新增删除中确认/取消按钮禁用断言。
- Redmi 定向验收：只向 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 安装 APK。修正后 `LocalNoteManagementViewModelInstrumentedTest` 为 `OK (2 tests)`、`0.247s`；`LocalNoteManagementPageInstrumentedTest` 为 `OK (2 tests)`、`5.111s`；`RoomAgentNoteStoreInstrumentedTest#userDeleteClearsContentAndPreventsHistoricalToolReplay` 为 `OK (1 test)`、`0.369s`。
- 文档 corpus：六份长期文档进入 AndroidTest 资产后，只在 Redmi 运行 `RoomKnowledgeDocumentStoreInstrumentedTest#projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮为 `OK (1 test)`、`2.66s`；记录首轮结果后重新构建，第二轮为 `OK (1 test)`、`1.537s`。
- 验证边界：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；设备列表中的 `emulator-5554` 未接收安装、instrumentation 或功能命令。

## 2026-08-06 第 153 阶段：本地笔记只读管理入口

- 实现：设置根新增“本地笔记”，经 `XiaoLingSettingsPane.LOCAL_NOTE_MANAGEMENT` 进入独立页面。`LocalNoteManagementViewModel` 默认注入生产 `RoomAgentNoteStore`，读取最近最多 10 条，按标题/正文搜索最多 10 条，并按稳定 ID 回读详情；标题与返回固定在滚动列表之外。
- 只读边界：页面只展示标题、正文摘要、完整正文和创建/更新时间，不提供创建、编辑、删除、批量或后台操作。没有新增 Room Schema、Android 权限、Agent 工具、Profile/Skill 白名单或 Runtime；第 152 阶段写入审批、幂等与回读契约保持不变。
- 构建与 JVM：`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、定向 `XiaoLingNavigationCoordinatorTest + SettingsRootProjectionTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`。
- Redmi 定向验收：仅向物理设备 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 安装 APK并运行 `LocalNoteManagementViewModelInstrumentedTest`，结果 `OK (1 test)`、`0.163s`；补齐详情加载反馈后的 `LocalNoteManagementPageInstrumentedTest` 最终为 `OK (2 tests)`、`4.469s`；`SettingsRootPageInstrumentedTest` 为 `OK (5 tests)`、`11.72s`；真实 Room `listSearchAndGetServeTheReadOnlyManagementPage` 为 `OK (1 test)`、`0.338s`。
- 首次页面测试失败属于夹具问题：静态 fake state 不会因输入重组，且正文同时命中列表摘要和弹窗；修正测试状态与选择器后生产代码边界未改变，最终单项全部通过。
- 文档门禁：六份长期文档重新打入 AndroidTest APK 后，Redmi `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 首轮为 `OK (1 test)`、`2.565s`；写回该证据并重建后的最终文档资产使用同一单项复验通过。
- 验证边界：本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；设备列表中的 `emulator-5556` 未接收安装、instrumentation 或功能命令。

## 2026-08-06 第 152 阶段：本地笔记写入闭环

- 真实入口：仅在 Debug source set 增加 `notes_create_real`，读取当前 Provider，临时冻结 `local-notes` Profile 的 `notes.search / notes.create / notes.list` 工具面，并复用正式 `AgentRunUseCase + XiaoLingToolRegistry + RoomAgentRunRepository`。没有第二套 Runtime、没有新增 Room Schema、没有进入 Release。
- Redmi `wsvwypiz7xwslvl7` 真实 Run `run-66b689fb-6ff3-410f-a851-e0f91765047a` 完成自然语言目标“创建并核对一条本地笔记”。`notes.create` Room 审批为 `APPROVED`；Tool Ledger 为 `success=true / executorVerified=true / verificationStatus=PASSED`；生产工具内部写入后回读成功，探针随后以唯一标题搜索回读正文。
- 真实收尾日志确认 `temporaryProfileRemoved=true / testNoteRemoved=true`；用户原 Profile 恢复，Run/Approval/Tool Ledger 事实保留供审计。DAO 精确 ID 清理回归 `RoomAgentNoteStoreInstrumentedTest#debugProbeCleanupDeletesOnlyTheRequestedNote` 在 Redmi 为 `OK (1 test)`（`0.344s`）。
- 验证：`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、定向 JVM `XiaoLingToolRegistryTest + AgentSkillsTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`；仅 Redmi 执行一条清理 instrumentation。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 边界：本阶段证明的是前台真实 Provider 的本地笔记创建、审批持久化、写入后回读和测试数据清理；笔记删除用户能力、后台笔记写入、自然 LMK/主动网络失败、长任务、MCP、远程 Channel、多 Agent 和本地模型均未扩展。

## 2026-08-06 第 151 阶段：真实 WorkManager 长任务、熄屏与受控中断恢复

- 探针边界：Debug Receiver 新增 `workflow_long_scheduled / workflow_long_status`，但执行严格复用正式 `RoomWorkflowRepository + WorkManagerScheduledTaskScheduler + ScheduledWorkflowWorker`。临时 Profile 只允许 `app.current_time`；状态查询确认终态时恢复原 `stage3-device-e2e-profile`、删除临时 Profile、清空探针状态并停用 Workflow，创建新探针前也会清理上次残留。该清理是 Debug 观测协议的一部分，不是生产 Worker 的额外 Runtime。
- 首次失败样本：Task `scheduled-task-0e688878-5227-4fc9-8d6e-b986af0d0d18` / Run `workflow-run-07877d2d-4da1-484a-90db-28feba8d5c4c` 在 `10212ms` 后 `FAILED`，原因是原选中 Profile 没有注册 `app.current_time`。失败历史保留，Workflow 已停用；后续用最小临时 Profile 修正探针前置条件。
- 普通后台样本：Task `scheduled-task-1684ca82-dfb0-45e7-94a7-7a5908094a92` / Run `workflow-run-f20ecc64-e375-47ba-813d-8516297eb920`，`8/8 COMPLETED`，Worker 耗时 `95816ms`。PID 全程为 `8228`，前后 `exit-info` 没有新增退出记录。
- 熄屏后台样本：Task `scheduled-task-0d5a2c12-b952-40cf-b236-ab121ac06263` / Run `workflow-run-e9aa7e03-8557-451e-972c-af56de8051e0`，`8/8 COMPLETED`，Worker 耗时 `91915ms`。后半程持续 `mWakefulness=Dozing`，PID 全程为 `8228`，`exit-info` 无新增记录。
- 受控中断：Task `scheduled-task-0b0b35d7-e705-46f8-b235-71e786ba1bf1` / Run `workflow-run-b2f58179-839a-4687-ac68-2b2d02687089` 在执行中被人工 `force-stop`；旧 PID `8228`，新 PID `9134`，`exit-info` 明确为 `USER REQUESTED / FORCE STOP`。该样本不是自然 LMK。恢复后 Task/Run 为 `CANCELLED`，步骤为 `4 COMPLETED + 4 CANCELLED`，完成前缀保持不变，没有重放工具或后续步骤。
- 缺陷与修复：中断发生在 `app.current_time` ToolResult 已 `PASSED`、但 Agent 尚未总结的窗口。旧 `completeByAgentRunId()` 把 Tool Ledger 的 verified tool names 传给 `CANCELLED` 步骤，触发“未完成步骤不能持久化已验证工具”并遗留 `RUNNING`。Repository 现在只对 `WorkflowRunStatus.COMPLETED` 传递已验证工具；取消/失败继续保留独立 Tool Ledger 审计，但步骤输出为空。
- 验证：`testDebugUnitTest / assembleDebug / assembleDebugAndroidTest` 均 `BUILD SUCCESSFUL`。仅在 Redmi `wsvwypiz7xwslvl7` 运行 `RoomWorkflowRepositoryInstrumentedTest#workerReentryClosesOnlyLinkedAgentAndScheduledTaskWithoutCreatingNewRun`，结果 `OK (1 test)`、耗时 `0.851s`；测试包已卸载，主应用恢复前台。未运行 Lint、Release 或默认完整 instrumentation。
- 文档门禁：六份长期文档重新打入 AndroidTest APK 后，Redmi `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 首轮为 `OK (1 test)`、耗时 `2.471s`；随后按相同步骤重建并复验写回后的文档版本。
- 结论：普通后台与 Dozing 下约 92 至 96 秒任务可以完成；人工 `force-stop` 后旧执行栈不能续跑，只能保留已完成前缀并取消剩余步骤。自然 LMK、主动网络失败和 5 至 10 分钟真实生产任务尚未验证，当前不引入 Foreground Service，也不开放后台设备动作。

## 2026-08-06 第 150 阶段：今日安排与提醒总览 Skill

- 实现：新增内置只读 `day-overview` Skill，组合 `calendar.list_events` 与 `tasks.list`，要求最终回复区分系统日程和小灵任务事实；不新增工具、权限、Room Schema 或后台能力。
- 白名单：Skill 仍需 Agent Profile 显式允许两个工具和该 Skill；日历主动授权、前台限制、任务只读和旧 Profile 不自动扩权语义保持不变。
- 聚焦验证：`AgentSkillsTest 15/15`、`XiaoLingToolRegistryTest 40/40` 通过；`:app:assembleDebug` 成功。按分级验证未运行完整 JVM、全量 Lint、AndroidTest APK 全量构建、全量 instrumentation 或 Release。
- Redmi 真实闭环：仅使用物理设备 `wsvwypiz7xwslvl7`，`READ_CALENDAR` 已授权。真实 Run `run-535a90af-b45c-4b18-8574-0aa4c91e6268` 状态为 `COMPLETED`，同一 Run 的工具顺序为 `calendar.list_events -> tasks.list`，两项 ToolResult 均 `success=true / verificationStatus=PASSED`；最终回答通过 `answerSeparated=true` 校验，分别展示日程与小灵任务事实。
- 异常边界：原 Provider 的 `gpt-5.4-mini` 首次尝试在第二轮返回空工具名，Run 按 Runtime 规则失败且未被覆盖；依据 `AGENTS.md` 兜底配置恢复后完成本次验收。真实 Provider、任务数据和日历结果未伪造，未向 Pixel_9 或其他模拟器发送命令。

## 2026-08-06 第 149 阶段：系统日历标题关键词查找

- 实现：新增 SAFE `calendar.search_events`，输入 `query`（1..100）、`days_ahead`（1..30）和 `limit`（1..20）；复用 `READ_CALENDAR`、前台限制和最小字段投影，仅对标题做内存关键词匹配。新增独立 `calendar-search` Skill，旧 `calendar-overview` 和既有 Profile 不自动扩权。
- 隐私与边界：Provider 仍只返回标题、开始时间、结束时间和全天标记；地点、描述、参与人、组织者、账户和日历写入继续关闭。后台 Workflow、定时任务和静默权限请求继续拒绝。
- 聚焦验证：`XiaoLingToolRegistryTest` 与 `AgentSkillsTest` 通过，`:app:compileDebugKotlin` 为 `BUILD SUCCESSFUL`。按分级验证未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation。
- Redmi 真实验收：仅使用 `wsvwypiz7xwslvl7` 运行 `AndroidCalendarEventReaderInstrumentedTest`，结果 `OK (2 tests)`，覆盖真实 Provider 有界读取与不存在标题的空结果；设备没有可安全创建的用户日程，因此没有伪造标题匹配样本。测试包已卸载，主应用 PID `18766` 存活，crash buffer 未发现小灵异常。

## 2026-08-06 第 148 阶段：系统日历只读能力与真实 Agent 闭环

- 实现：新增 `CalendarEventReader`/`AndroidCalendarEventReader`，通过 `CalendarContract.Instances` 在 IO 调度器查询未来 1 至 30 天、最多 20 条；只投影标题、开始时间、结束时间和全天标记。`XiaoLingToolRegistry` 注册 SAFE `calendar.list_events`，声明 `READ_CALENDAR`、`supportsBackground=false`，权限撤销和 Provider 异常均 fail-closed；无 `WRITE_CALENDAR`。
- 隐私与授权：设置根页新增“日历访问”入口，独立页面只在用户主动点击时申请 `READ_CALENDAR`，返回应用或系统设置后重新读取授权状态。页面明确不读取地点、描述、参与人或账户，不创建/修改/删除日程，并要求 Agent Profile 显式启用工具和 `calendar-overview` Skill；旧 Profile 不自动扩权。
- 聚焦验证：`PersonalTaskPlanPolicyTest 12/12` 通过；Debug APK 与 AndroidTest APK 构建成功；Redmi `CalendarAccessSettingsPageInstrumentedTest + SettingsRootPageInstrumentedTest` 为 `OK (7 tests)`；已授权 Redmi 的 `AndroidCalendarEventReaderInstrumentedTest` 为 `OK (1 test)`。
- 真实 Agent：默认 Agent 显式启用 `calendar.list_events` 与 `calendar-overview` 后，模型计划初版曾把“整理并展示”拆成第二步，导致 Runtime 为满足当前 Run 工具事实而额外选择 `app.current_time`。新增规划提示后复跑，计划为 `1/1`，唯一工具 `calendar.list_events`，参数 `days_ahead=7 / limit=20`，ToolResult `success=true / verificationStatus=PASSED`，结果“未来 7 天没有日程”，目标结论 `VERIFIED / ALL_CRITERIA_VERIFIED`。
- 边界：本阶段只开放前台日历只读查询；日历写入、后台 Workflow、地点/描述/参与人/账户、MCP、远程 Channel、多 Agent 和本地模型继续关闭。按快速迭代分级未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation；只使用 Redmi `wsvwypiz7xwslvl7`，未向模拟器发送命令。

## 2026-08-06 第 147 阶段：真实多步 Runtime 可靠性与后台时长评估首轮

- 首轮真实阻断：8 步 SAFE Workflow 中，模型可能在工具已成功验证后紧邻请求完全相同的只读调用，旧 Runtime 会以重复指纹让整条任务失败；重复目标步骤还可能把前序输出误当成本 Agent Run 的工具事实，在零工具状态直接返回 `complete`。
- Runtime 修复：只有 `SAFE + approvalPolicy=NONE + verificationPolicy=RESULT_READABLE`、前次结果成功且非空、紧邻指纹完全相同的调用，才复用已有结果并记录 `llm.repeat_completed`；不会再次经过 Executor。设备动作、写工具、需要审批和普通重复仍拒绝。零工具提前结束只记录一次 `llm.premature_complete_retried` 并要求重新规划；再次提前结束仍失败，避免无限循环。Workflow Prompt 明确前序输出不属于当前 Agent Run，不能替代当前步骤工具事实。
- 前台真实 Run：`workflow-run-84097511-b21d-4d89-9098-ed439625eba8` 状态 `COMPLETED`，耗时 `104156ms`，8 个步骤全部完成；每步恰好 1 次工具调用、1 次结果和 1 次验证，目标结论为 `VERIFIED / ALL_CRITERIA_VERIFIED`。第 2 至 7 步真实触发 `llm.repeat_completed`，第 8 步同时触发 `llm.premature_complete_retried` 与 `llm.repeat_completed`。
- 熄屏真实 Run：`workflow-run-2153667c-f664-4034-a566-79a114899c27` 状态 `COMPLETED`，耗时 `94155ms`，目标结论同样为 `VERIFIED / ALL_CRITERIA_VERIFIED`。Run 启动约 3 秒后熄屏，系统持续 `Wakefulness=Dozing`；同一 PID 在熄屏后继续发起并完成模型请求，最终 8/8 完成。
- 系统证据边界：`dumpsys activity exit-info` 前后完全一致，没有新增自然退出或系统回收记录；前台 Workflow 没有对应 JobScheduler/WorkManager Job。本轮没有用 `force-stop`、instrumentation、`kill -9` 或人为延时制造样本。当前生产最大 8 步实际只有约 94 至 104 秒，不能声称验证了 5 至 10 分钟任务或自然进程回收恢复。
- 分级验证：`MultiStepAgentRuntimeTest 8/8 + WorkflowStepExecutionPolicyTest 14/14`，聚焦 JVM 合计 `22/22`；`:app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL`。只向 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 安装测试 APK 并运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`、耗时 `2.468s`；随后卸载测试包，主应用数据保留。未向在线模拟器发送安装、测试或功能命令。
- 结论：现有前台 Runtime 在短时后台/熄屏期间可以继续执行，本阶段证据支持暂不引入 Foreground Service；后台设备动作继续关闭。下一步先寻找真实超过数分钟的生产任务，再决定 Foreground Service、通用长任务恢复或系统配额适配，不为凑时长增加虚假等待。

## 2026-08-06 第 146 阶段：真实任务总览与关联重试收口

- `tasks.list` 真实模型证据：默认 Agent Profile 已显式启用工具与 `task-overview` Skill。请求“Use tasks.list to list my current tasks and reminders. Do not answer from memory.” 后，Agent Run `run-9736a67f-0662-487c-ac77-489f6132f82f` 使用 `gpt-5.6-luna` 完成；唯一 ToolResult 为 `tasks.list / success=true / verificationStatus=PASSED / executorVerified=null`，返回任务“读取时间并返回小灵”、已启用、3 步、最近已完成。
- 首轮真实关联重试：Run `workflow-run-cdaaf42d-aa85-44ef-95a9-9ab972ed8f2d` 来源为 `workflow-run-9f188e89-f950-430b-bc3c-69eba6f79971`。步骤状态为“已复用 / 已完成 / 已完成”；第二步实际完成 `com.longdev.xiaoling -> com.google.android.deskclock`，第三步完成 `com.google.android.deskclock -> com.longdev.xiaoling`，两个动作都已通过后置观察。旧实现最终以“Workflow 已验证工具缺少 Agent Run 来源”收敛为失败，暴露连续重试的多级复用来源缺口。
- 修复：`RoomWorkflowRepository` 对已验证工具、设备观察和设备动作统一沿 `reusedFromStepId` 链回查最初带 `agentRunId` 的步骤，并用 visited 集合拒绝循环。`WorkflowRunRetryPolicy / retryRun()` 允许 `FAILED` 且全部步骤成功的 Run 创建仅目标收敛的新 Run；全部步骤复用，不重新调用模型、审批或 Executor。Accessibility 生命周期策略同时确认 `onInterrupt()` 只失效活动窗口，只有 `onDestroy()` 才取消审批和 detach Runtime。
- 验收工具边界：Redmi 上 UIAutomator 会在审批期间临时触发 Accessibility Service `onDestroy()` 并约 1.6 秒后重建，因此 Run 启动后到审批完成期间不调用 UIAutomator；审批后待 Run 收敛再读取 Workflow UI。`FLAG_SECURE` 让浮层截图区域变黑属于预期。
- 最终真实结果：新 Run `workflow-run-3e4b422e-d48e-4244-b21a-2668a980fe10` 的来源为 `workflow-run-cdaaf42d-aa85-44ef-95a9-9ab972ed8f2d`，三步全部“已复用”。目标页显示已验证步骤 `3/3`、工具顺序 `app.current_time -> device.open_app -> device.back` 全部匹配、期望/实际最终应用均为 `com.longdev.xiaoling`，结论为“任务目标已验证完成 / ALL_CRITERIA_VERIFIED”。来源 Run 仍显示 `2026-08-06 00:50:07 · 失败`，没有被回写。
- 验证：聚焦 JVM `DeviceAccessibilityServiceLifecyclePolicyTest` 与 `WorkflowStepExecutionPolicyTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均通过；Redmi `RoomWorkflowRepositoryInstrumentedTest#repeatedRetryKeepsVerifiedToolProvenanceForGoalDecision` 与 `#retryRevalidatesAllSuccessfulStepsWithoutReplayingThem` 最终 `OK (2 tests)`、耗时 `1.02s`，更新后的 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。首轮测试曾因第二步夹具没有任何已验证工具而按规则得到 `PARTIAL`，补充合法 `conversation.list` Tool Ledger 后通过；该失败证明目标策略仍要求每个步骤具备可核验事实。本阶段不替代第 132 阶段完整门禁。

## 2026-08-05 第 142 阶段：完成结果定向查看 Workflow

- 实现：完成结果卡保留对应 `workflowId`，Conversation 通过应用壳导航到 Workflow 设置页；设置页把一次性目标传给 Workflow 管理列表。提交后失败但没有目标 ID 的入口继续打开通用 Workflow 列表。
- 展示行为：Workflow 列表在目标数据可用后按 ID 自动滚动，目标项以 `initiallyExpanded` 展开；返回设置根页清理 `requestedWorkflowId`，防止旧任务目标复用。未找到目标时保持原有列表和折叠行为。
- 边界：本阶段不改 Room Schema、Workflow/Run、Agent Runtime、工具白名单、审批、设备权限或后台执行；只修复完成卡到既有任务详情的定向导航。
- 验证：聚焦 JVM `17/17`；Debug 与 AndroidTest APK 构建成功；Redmi `wsvwypiz7xwslvl7` 定向 instrumentation 为 `OK (18 tests)`。未运行完整 JVM、全量 Lint、默认完整 instrumentation、文档 corpus 或 Release。

## 2026-08-05 第 143 阶段：定向 Workflow 导航重建保存

- 实现：导航 Saver 从只保存知识文档目标改为同时保存 `requestedKnowledgeDocumentId` 与 `requestedWorkflowId`；目标为空时恢复为 `null`。
- 边界：Tab、设置子页、根页返回时间等暂态字段仍不保存；返回设置根页的清理逻辑不变。该修复只影响 Activity 重建后的导航连续性。
- 验证：随后运行第 143 阶段聚焦 JVM 与 Debug 编译；未运行 Redmi instrumentation、完整 JVM、全量 Lint、AndroidTest APK 或 Release。
- 远端资产：`xiaoling-v0.1.15.apk` 与 `xiaoling-v0.1.15.apk.sha256` 均为 `uploaded`；APK 远端大小 `3,318,322` 字节、digest `sha256:a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`，与本地产物一致。校验文件大小为 `87` 字节，远端 digest 为 `sha256:86bef3194ddda319bba39649b7f17cf30a49c928752ad254cc9faed575fd1aeb`。

## 2026-08-05 第 144 阶段：任务/提醒只读总览

- 实现：新增 `AgentTaskStore / RoomAgentTaskStore`、SAFE `tasks.list` 和内置 `task-overview` Skill。工具最多返回 10 条最近更新 Workflow，展示名称、目标、启停、步骤数、最近 Run 状态、调度类型和下次时间。
- 持久化边界：DAO 直接按 Workflow 查询各自最新 Run，避免全局 200 条窗口被其他任务挤占；一次性任务与周期计划并存时选最早 `plannedAt`。`AgentTaskRecord` 不携带 Workflow ID，错误详情、步骤输出和其他 Room 内部证据不进入工具结果。
- 权限边界：`tasks.list` 为 SAFE 只读工具且 `supportsBackground=false`；既有 Profile 与历史 Run 不自动扩权。本阶段不修改、取消、重试或执行任务，不新增 Android 权限、Room Schema、系统日历、后台设备自动化或新 Runtime。
- 本地验证：`XiaoLingToolRegistryTest 37/37 + AgentSkillsTest 11/11`，合计 `48/48`；`:app:assembleDebug + :app:assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL in 16s`。首次把 `--tests` 与编译任务合并时被 Gradle 拒绝为 `Unknown command-line option '--tests'`，拆分后成功；两个 Gradle 进程并发时出现过 Kotlin 增量文件竞争，两个进程最终均成功，后续 APK 由单一 Gradle 进程编排。
- Redmi 验收：显式安装主/测试 APK 到 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`，`RoomAgentTaskStoreInstrumentedTest` 为 `OK (3 tests)`（`1.828s`）。用例覆盖基本投影、其他 Workflow 超过 200 条 Run 时仍保留目标最新状态，以及一次性/周期计划并存时选最早发生项。更新后文档 corpus 单项为 `OK (1 test)`（`2.648s`）。未向在线模拟器发送任何命令。
- 验证分级：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；本阶段不替代第 132 阶段里程碑门禁。

## 2026-08-05 第 139 阶段：确认后任务创建提交竞态收敛

- 实现：新增 `capturePersonalTaskCommit`。立即任务和应用内提醒的 Room 原子创建在 `NonCancellable` 区域内完成，并在同一区域先捕获 Workflow/Run、ScheduledTask 或周期调度身份；外层随后调用 `ensureActive()` 再继续执行或进入取消/清理路径。
- 行为边界：用户停止发生在提交完成与协程返回值交接之间时，已创建 Run 不再被误判为“尚未创建”；它会沿既有 `CANCELLED`、WorkManager 撤销或调度失败收敛，避免重复点击“重新生成”产生第二个任务。会话切换仍由 request ID 隔离，未恢复旧 Executor、模型协程或后台权限。
- 测试：`PersonalTaskCreationCommitTest 1/1` 模拟提交函数返回过程中取消调用方，并确认身份回调仍执行；既有 `PersonalTaskPlanCancellationTest 1/1` 继续通过。`assembleDebug` 与 `assembleDebugAndroidTest` 成功。该用例不替代 ViewModel/Room/WorkManager 全链路故障注入或真机验收。
- 验证分级：本阶段未运行完整 JVM、全量 Lint、Redmi instrumentation、文档 corpus 或 Release；本地测试和构建只证明提交边界与编译，不表述为真机验收。

## 2026-08-05 第 140 阶段：已提交任务失败后的查看入口

- 实现：`PersonalTaskFailureUiState` 新增 `RETRY_PLAN / VIEW_WORKFLOW` 动作。提交前失败继续恢复原目标并重新生成；立即任务或提醒已经提交后，停止/失败只展示“查看任务”，由宿主刷新并打开既有 Workflow 管理页。
- 重复创建边界：已提交提醒停止后不再把原目标写回可发送输入框；失败条不会自动创建 Workflow/Run、重新规划或执行工具。旧记录继续按第 131/139 阶段的取消、清理和关联重试契约保留。
- 本地验证：`PersonalTaskPlanCancellationTest 2/2 + PersonalTaskCreationCommitTest 1/1 + ConversationProjectionTest 6/6`，合计 `9/9`；`assembleDebug / assembleDebugAndroidTest` 成功。
- Redmi：仅 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `ConversationPageInstrumentedTest`，结果 `7/7`、`0 failure / 0 error / 0 skipped`，耗时 `11.939s`；更新后的文档 corpus 单项为 `1/1`、耗时 `3.568s`。Gradle 测试结束时自动卸载测试应用，随后重新安装当前 Debug `0.1.15`，`MainActivity` 恢复前台、进程存活；模拟器未接收安装、测试或 UI 命令。
- 验证分级：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 2026-08-05 第 141 阶段：个人任务完成后的结果入口

- 实现：新增 `PersonalTaskCompletionUiState` 与 `PersonalTaskCompletionPresentation`。立即任务在 `executeForegroundWorkflow()` 正常完成后，只读取 Repository 已持久化的 `goalVerificationDecision` 映射完成卡：`VERIFIED / PARTIAL / INCOMPLETE` 分别显示已验证完成、仅部分完成和尚未完成；没有完成标准时只显示普通完成。提醒在 Workflow、调度实例和 WorkManager 关联成功后显示已确认调度标签。
- 行为边界：完成卡的“查看任务”只复用既有工作流管理页，不重新发送、重新规划、创建新 Run/提醒或自动重试。输入编辑、任务模式切换和下一次计划会清除旧卡；模型自由文本不能提升本地目标结论。当前入口是通用工作流页面，不声明按 Workflow ID 定位。
- TDD 与本地验证：`PersonalTaskCompletionPresentationTest 3/3 + PersonalTaskCreationCommitTest 1/1 + PersonalTaskPlanCancellationTest 2/2 + ConversationProjectionTest 7/7`，合计 `13/13`；`assembleDebug` 成功。
- Redmi：仅 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `ConversationPageInstrumentedTest`，结果 `8/8`、`0 failure / 0 error / 0 skipped`；其中新增用例验证完成文案、查看入口和不会再次发送。更新后的文档 corpus 单项为 `1/1`。模拟器没有接收安装、测试或 UI 命令。
- 验证分级：未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；本阶段不替代真实模型完成任务的端到端验收。

## 2026-08-05 第 137 阶段：计划上下文请求精简

- 实现：`PersonalTaskPlanPolicy.prepareRequest()` 把最终两条消息和 `PersonalTaskPlanContextUsage` 一并返回。长期记忆/本地知识仍各最多 3 条、正文最多 800 字符；新选择器按两类来源交替尝试，以包含标题、编号、文档名和正文的真实 UTF-8 块执行 `8,192B` 预算，只接受完整条目。知识正文与记忆完全相同时不重复发送并计入省略数。
- UI 与边界：`PendingPersonalTaskPlanUiState` 保存真正发送的使用/省略数和上下文字节；确认弹层展示实际占用，只有真实省略时显示“上下文精简”。system 安全规则、用户目标、时间、工具/App 边界、计划 Schema、检索异常阻止生成、审批、Room 和 Runtime 均未改变。
- TDD 与构建：策略与展示测试先分别以缺少 `prepareRequest/MAX_CONTEXT_BYTES`、缺少 presentation 函数失败；实现后 `PersonalTaskPlanPolicyTest 10/10 + PersonalTaskPlanContextUsagePresentationTest 2/2`，合计 `12/12`。`assembleDebug / assembleDebugAndroidTest` 成功，未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- Redmi UI：仅 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `PersonalTaskPlanDialogInstrumentedTest`，结果 `OK (5 tests)`；确认页可见上下文占用和分来源省略数。在线模拟器只出现在 `adb devices` 列表，没有接收安装、instrumentation、UI 或功能命令。
- 真实模型：显式 Provider 探针只发起一次结构化计划请求。最终手动运行 `OK (1 test)`，上下文 `7,264B`、记忆使用/省略 `2/1`、知识使用/省略 `1/2`，完整请求 Prompt `11,190B`，模型耗时 `5,851ms`，返回 1 步可解析 `IMMEDIATE` 计划。探针成功后按显式参数恢复并回读 Provider；只卸载测试包，主应用重新启动并保持 `MainActivity` 前台。

## 2026-08-05 第 138 阶段：计划生成取消重试闭环

- 实现：`preparePersonalTaskPlan()` 的 `CancellationException` 分支在 request ID 仍属于当前会话时，恢复 `prompt=goal`、清理 `GENERATING_PLAN`，并写入 `personalTaskPlanCancellationFailure(goal)`；UI 因此显示“计划生成已停止”和既有“重新生成”入口。会话切换/删除先使 request ID 失效，旧取消不会写入新会话。
- 边界：本阶段只处理计划模型请求尚未创建 Workflow/Run 的主动停止；已确认立即任务、应用内提醒、Room/WorkManager 和关联重试保持第 133 阶段既有语义。未新增 Room Schema、Runtime、工具白名单或设备权限。
- TDD 与构建：取消状态映射测试先因缺少 `personalTaskPlanCancellationFailure` 失败，实现后 `PersonalTaskPlanCancellationTest 1/1` 通过；`assembleDebug / assembleDebugAndroidTest` 成功。
- Redmi：仅 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 手动运行 `ConversationPageInstrumentedTest`，结果 `OK (6 tests)`，耗时 `9.791s`；测试包随后卸载，主应用重新启动。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。

## 2026-08-05 第 136 阶段：首个 Google 天气 App 兼容扩展

- 候选与边界：本阶段只新增 Google 天气 `com.google.android.apps.weather`。Chrome 会把观察和动作面扩大到任意网页，联系人、短信、文件涉及高敏个人数据，日历容易与后置系统日历集成混淆，因此全部继续关闭；没有申请 `QUERY_ALL_PACKAGES`，不承诺任意 App。
- 实现：天气包加入 Manifest queries、`DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES` 和 `device.open_app.package_name` enum；任务模式增加“查看天气”，但仍只回填“打开天气并查看当前天气”，不自动发送、不请求模型、不创建 Workflow/Run。Debug 真实链保留原计算器入口，并把 open_app tracer 参数化为独立场景和目标包，天气不走旁路。
- 隐私：天气页真实 snapshot 可能包含用户当前选择的粗粒度位置文本；该内容只允许在用户主动发起查看天气时作为前台观察处理，不新增后台采集、跨任务节点引用或额外持久化。`open_app` 的答案级文案明确不产生可复用节点引用。
- 聚焦 JVM：`DeviceActionPolicyTest / PersonalTaskPlanPolicyTest / WorkflowDeviceActionSafetyPolicyTest / XiaoLingToolRegistryTest` 合计 `64/64` 通过。首次编译因 Kotlin 字符串插值与中文相邻造成 unresolved reference，修正为显式 `${targetLabel}`；首轮 Registry 断言检查层级错误，改为检查 `package_name` 字段描述后全部通过。
- APK 与 Redmi 单项：`:app:assembleDebug / :app:assembleDebugAndroidTest` 成功。仅 Redmi `wsvwypiz7xwslvl7` 运行包可见性和天气模板单项，分别 `OK (1 test)`（`0.039s`）与 `OK (1 test)`（`2.734s`）；模板验证目标已回填且发送次数为 `0`。
- 文档语料：六份长期文档同步第 136 阶段后重新打包 AndroidTest assets，仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮/证据写回后复验均为 `OK (1 test)`（`2.409s / 2.606s`）；当前不再改写的文本用于最终冻结复验。
- Redmi 真实链：Accessibility 恢复并稳定绑定后，从小灵 Probe snapshot 出发显示独立天气审批。前两次观察浮层时调用 `uiautomator dump`，其 `UiAutomation` 中断 Accessibility，按设计得到 `SERVICE_DISCONNECTED`；第三次人工等待超过 30 秒 snapshot TTL，按设计拒绝“device.snapshot 已过期”。最终改用 `dumpsys window` 检测自有 overlay 并立即点击批准，日志为 `workflow-weather-open-app-e2e success=true action=open_app verified=true approval=APPROVED executorVerified=true verification=PASSED beforePackage=com.longdev.xiaoling afterPackage=com.google.android.apps.weather answerDecision=VERIFIED`。
- 收尾与验证边界：真实链结束后小灵 `MainActivity` 恢复前台，crash buffer 无本应用异常，测试包已卸载。按快速迭代分级未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；在线模拟器未接收安装、instrumentation、UI 或功能验收命令。

## 2026-08-04 第 134 阶段：计划生成成本可见性

- 实现边界：`ModelResponseResult` 的单次真实遥测映射到 `PendingPersonalTaskPlanUiState.generationMetrics`，确认弹层显示模型调用次数、总耗时、TTFB、Prompt 字节数和 input/output/total tokens。当前计划流程只调用模型一次，因此调用次数固定为 1。
- 未知语义：Provider 没有返回 TTFB 或 Token usage 时显示 `未采集` / `未返回`，不把缺失当作 0，不估算货币成本，也不写 Provider 价格表。
- 持久化边界：遥测只在确认前 UI 状态中存在；不写 Room、RunEvent、Workflow、Agent Run 或历史成本汇总，计划生成请求不伪装成 Runtime LLM 事件。确认、取消、失败和重试的既有语义保持不变。
- 聚焦验证：定向 JVM 与 `compileDebugAndroidTestKotlin` 通过；`assembleDebug / assembleDebugAndroidTest` 成功。仅 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `ConversationPageInstrumentedTest + PersonalTaskPlanDialogInstrumentedTest`，结果 `OK (10 tests)`、`13.583s`。
- 验证边界：本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation、文档 corpus 或 Release；ADB 安装与 instrumentation 明确使用 Redmi，未向在线模拟器发送命令。

## 2026-08-04 第 135 阶段：常用任务模板快捷入口

- 实现边界：任务模式增加三个纯 UI 模板“打开计算器”“搜索系统设置”“打开时钟”。模板使用现有 `updatePrompt` 回填目标文本，不自动发送、不请求计划模型、不创建 Workflow/Run，也不直接调用设备工具。
- 权限边界：模板只提供已验收 App 范围内的意图起点，不改变 Profile 工具白名单、首批包名白名单、审批、目标级验证或 Room 语义；选择后仍由用户手动发送并确认严格计划。
- 聚焦验证：`ConversationProjectionTest` 与 `compileDebugAndroidTestKotlin` 通过；`assembleDebug / assembleDebugAndroidTest` 成功。仅 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `ConversationPageInstrumentedTest`，结果 `OK (6 tests)`、`9.751s`，新增模板测试确认目标已回填且发送次数为 `0`。更新后的文档 corpus 首轮/证据写回后复验均为 `OK (1 test)`（`2.459s / 2.616s`）。
- 验证边界：本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；ADB 明确只使用 Redmi，在线模拟器未执行命令。

## 2026-08-04 第 133 阶段：个人任务计划交互首轮打磨

- 交互状态：新增 `GENERATING_PLAN / CREATING_TASK / CREATING_REMINDER`。对话页优先显示对应进度，停止按钮按生成计划、创建任务或创建提醒给出明确语义，不再复用普通模型等待动画。
- 失败与取消：计划生成、Workflow/ScheduledTask 创建前失败会保留原始目标和具体错误；创建落定前主动停止同样恢复目标并提供重新生成。重试先使用 `PersonalTaskFailureUiState.goal` 回填输入，再复用原发送入口。
- 会话一致性：确认后的前台操作以计划 ID 作为操作代次并绑定原会话。会话切换先使旧代次失效再取消 Job；已创建 Run 继续按既有 Ledger 收敛为取消，尚未创建时不写伪执行消息。成功、异常和最终清理只在 ID 与会话仍匹配时更新可见 UI，避免旧任务状态覆盖新会话。
- 提醒权限：Android 13+ 缺少通知权限时，确认弹层进入等待状态并禁用确认/返回。权限回调只有在原计划仍是当前计划时才提交；权限拒绝不改变已确认的应用内调度语义，只影响系统通知可见性。
- 聚焦验证：`ConversationProjectionTest` 与 `compileDebugAndroidTestKotlin` 通过；`assembleDebug / assembleDebugAndroidTest` 成功。Redmi 首轮重跑时设备为 `Dozing + keyguard showing`，测试 Activity 被系统标记 `isSleeping=true`，9 项统一以 `No compose hierarchies found in the app` 失败；该运行是锁屏基础设施失败，不计作产品断言结果。设备解锁后，同一最终二进制的 `ConversationPageInstrumentedTest + PersonalTaskPlanDialogInstrumentedTest` 为 `OK (9 tests)`，耗时 `12.418s`。
- 文档语料：README 与五份长期文档重新打包后，Redmi `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 首轮、两次证据写回及冻结文本复验均为 `OK (1 test)`（`2.522s / 2.512s / 2.529s / 2.327s`）。
- 验证边界：按快速迭代分级未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release；所有 ADB 安装与测试命令只显式发送到 Redmi `wsvwypiz7xwslvl7`，在线模拟器未收到命令。

## 2026-08-04 第 131 阶段：任务级恢复与关联重试

- 实现边界：复用 `WorkflowRunRetryPolicy`、`RoomWorkflowRepository.retryRun()` 和现有 Workflow 管理二次确认。旧 `BLOCKED / FAILED / CANCELLED` Run 只从连续成功前缀创建带 `retryOfWorkflowRunId` 的新 Run；成功前缀以 `SKIPPED / reusedFromStepId` 保留来源，首个未完成步骤及后续步骤重新执行。
- 安全边界：不恢复旧模型协程、旧 Executor、审批会话或未知提交状态；旧 Run、旧步骤、已提交副作用和审计事件保持不变，已启动失败步骤仍需二次确认。
- 聚焦验证：`WorkflowStepExecutionPolicyTest 13/13`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功。只在 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 `RoomWorkflowRepositoryInstrumentedTest#retryReusesCompletedStepsAndKeepsSourceRunUnchanged`，结果 `OK (1 test)`、`0.444s`。测试包已卸载，冷启动后 `topResumedActivity=com.longdev.xiaoling/.MainActivity`。没有运行完整 JVM、Lint、Release 或默认完整 instrumentation，也没有向模拟器发送 ADB 命令。
- 后续：第 132 阶段已经用 Redmi 完成知识、记忆、设备动作、提醒和恢复组成的三条完整用户任务，并统一通过完整 JVM、Lint、Debug/AndroidTest APK 和默认 instrumentation。

## 2026-08-04 第 132 阶段：完整个人 Agent 里程碑验收完成

- 修复：`WorkflowDeviceActionDecisionPolicyTest` 中 6 个通过路径夹具仍使用 `workflow-device-action-safety-v1`，与当前生产 `workflow-device-action-safety-v2` 不一致；仅将有效夹具更新到 v2，历史版本和拒绝路径继续由 codec fail-closed。
- 已验证：聚焦策略测试通过；完整 JVM `879/879`；Lint `0 error`；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。未执行 Release。
- 最终门禁：完整 JVM `879/879`、Lint `0 error`、Debug/AndroidTest APK 和 Redmi 默认完整 instrumentation `OK (282 tests)`（`139.622s`）通过；12 个显式联网探针按设计 skipped，0 个真实失败。未构建 Release。
- 三条任务：记忆/知识检索形成严格 ONCE 提醒并完成 Room 原子创建、WorkManager 入队与测试工作项撤销；真实设置页 `snapshot -> swipe -> snapshot -> back` 得到 `verified=2/2 / approvals=0 / freshSnapshots=true / goalDecision=VERIFIED / privacySafe=true`；关联重试复用已验证前缀并保持来源 Run 不变。
- 设备收尾：测试包已卸载，测试提醒工作项已撤销；Accessibility 与保持唤醒恢复原关闭状态，小灵主 Activity 恢复前台。第一次误用 Gradle `connectedDebugAndroidTest` 时序列号属性未过滤在线模拟器，模拟器收到一次无效 initialization test；该结果已废弃，后续安装、三条任务和最终完整套件全部显式使用 Redmi 序列号，有效证据只来自 Redmi。
- 文档语料：README 与五份长期文档重新打包后的 Redmi corpus gate 首轮为 `OK (1 test)`（`2.699s`）；写回证据后的最终复验同样通过。
- 真机复核：Redmi 已重新连接后，首轮显式 `am instrument` 运行 `281` 项，发现 AndroidTest 中两处 `safety-v1` 有效夹具仍未同步，另有旧测试 APK 导致当前 Room 版本断言不一致；失败集中在测试夹具/安装产物漂移，未据此判定生产回归。已同步剩余夹具到 `workflow-device-action-safety-v2`，将清理重建并重跑 Redmi。
- 代码修正：Room `@Database` 已为 v35，但 `XiaoLingDatabase.CURRENT_VERSION` 仍为 34；已同步常量到 35，使“新库版本等于当前版本”测试与实际 schema 一致。
- Redmi 包名兼容：当前 ROM 安装的是 `com.google.android.calculator / com.google.android.deskclock`，不是原 AOSP 包。白名单与 Manifest queries 增加这两个精确包名并保留 AOSP 兼容；不申请 `QUERY_ALL_PACKAGES`，不放宽任意 App。
- Compose 稳定性：知识引用 E2E 返回对话原用“文本 + 可点击”选择器，会同时命中合并语义节点；改用已有唯一 `bottom_tab_conversation` tag，不改变生产页面行为。
- 三条里程碑任务：新增 Redmi 持久化验收，把真实 Room 长期记忆、本地知识检索、严格 ONCE 计划、Workflow/ScheduledTask 原子创建和 WorkManager 入队串成第一条完整任务，并在验收后撤销测试工作项；第二条复用真实设置多动作 tracer，第三条复用关联重试磁盘验收。
- 第一条任务首轮按真实 FTS 查询发现泛化记忆“每隔一段时间”不能命中“30 分钟后提醒我”；验收夹具改为用户明确确认过的同一提醒意图，不绕过真实检索或直接注入上下文。
- 第二轮记忆已命中，知识文档因只写“非精确定时”仍未匹配完整目标；知识验收语料同步加入同一“30 分钟后喝温水”主题，继续通过生产 FTS 召回。

## 2026-08-04 第 130 阶段：个人任务上下文与应用内提醒

- 权限边界：任务计划只有在 Profile 允许 `memory.search`、Profile 记忆开启且当前会话单次召回开启时检索长期记忆；本地知识只依据 Profile 的 `knowledge.search` 权限。关闭来源不会调用对应 Store。
- 有界上下文：记忆与知识并行读取，各最多 3 条；每条最多 800 个 UTF-16 字符并避免截断代理对。记忆继续由现有 Store 排除禁用/过期记录并更新引用时间，知识继续由现有 Store 核对当前 revision 并写 retrieval 审计。
- 提示词与 UI：上下文被标记为不可信只读事实，其中的命令、工具、审批和完成声明不能扩权。确认弹层只展示“长期记忆 N 条 / 本地知识 N 个片段”，不展示正文。检索异常阻止本次计划；空命中继续普通计划。
- 提醒 Schema：新增严格 `IMMEDIATE / ONCE / DAILY / WEEKLY`；一次性延迟为 1 至 10080 分钟，每日/每周保存系统时区时分与周几。未使用字段必须为 0；数字字符串、小数、目标 App、`device.*` 完成标准或设备最终应用均在本地拒绝。
- 确认与持久化：确认页显示规则、非精确定时和后台审批边界，并复用 Activity 通知权限请求。用户确认后 Room 原子创建 Workflow 与首个 ScheduledTask/周期规则，不建立 Manual Run；随后只调用现有 WorkManager enqueue/attach。入队或关联失败会先撤销同一 WorkManager 唯一任务，再按既有调度失败状态收敛。
- 聚焦验证：首切片策略实际为 `5/5`，本阶段最终 `PersonalTaskPlanPolicyTest 7/7`；审查修复后的 `:app:assembleDebug / assembleDebugAndroidTest` 成功。只在 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 Room 原子创建与 Compose 提醒确认两个新单项，最终分别为 `OK (1 test)`、`0.318s / 2.12s`。中间一次安装序列使主包短暂缺失并报 `Unable to find instrumentation target package`，重装主包后同一最终二进制通过；未改用模拟器规避。
- 真实模型：临时真机探针从未跟踪 `AGENTS.md` 兜底配置取值且不打印凭据；“30 分钟后提醒我喝水，提醒时读取当前时间”返回 `schedule=ONCE / delay=30`，约 32 秒后正常 finished。临时探针已删除，不进入正式测试资产。
- 边界：没有新增 Room Schema、第二 Runtime、系统日历、精确闹钟、Foreground Service 或后台设备控制。修改/取消仍由既有工作流页面的明确用户操作处理。按快速迭代分级没有运行完整 JVM、Lint、Release、默认完整 instrumentation 或文档 corpus 门禁，也没有向在线模拟器发送 ADB 命令。

## 2026-08-04 第 129 阶段：目标级本地验证与最终回答约束

- 范围：计划严格 Schema 新增 `verification.required_tool_names / expected_final_package`，并按当前 Agent Profile 工具白名单和首批允许包校验。确认弹层在执行前显示工具顺序和最终应用；确认后以版本化 Contract 冻结用户原始目标与完成标准。
- Room v35：新增 nullable `workflows.goalVerificationContract / workflow_runs.goalVerificationDecision`。v34→v35 迁移保持旧值为 `null`，旧 Workflow 完成后不补造 Decision；非空但损坏或版本漂移的 Contract 会阻止新 Run。Contract 继续贯穿手动/定时 Run、步骤准备/启动和关联重试快照。
- 本地判定：Repository 从持久步骤和同 Run Tool Ledger 重建 `success=true + PASSED` 工具名顺序，从脱敏观察/动作 Decision 选取时间最新最终包。必需工具按子序列匹配；无工具证据的空壳 `SKIPPED` 不计入已验证步骤。全部标准满足输出 `VERIFIED`，有可信进度但证据不足输出 `PARTIAL`，无已验证进度输出 `INCOMPLETE`；模型总结正文不参与结论，最终文案由本地策略生成。
- 隐私与兼容：步骤 output 只新增已验证工具名，不复制工具参数、原始结果、snapshot/ref、节点正文、坐标或 HMAC。Decision Codec 拒绝状态/原因/工具前缀/步骤计数互相矛盾的持久记录；旧 Workflow 没有 Contract 时保持 `goalVerificationDecision=null`。
- 聚焦验证：`WorkflowGoalVerificationPolicyTest 6/6`、`WorkflowStepExecutionPolicyTest 13/13`、`PersonalTaskPlanPolicyTest 3/3`，合计 `22/22`；`assembleDebug / assembleDebugAndroidTest` 成功。只在 Redmi `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro` 运行 v34→v35 迁移、Contract/Decision 持久化、损坏 Contract 拒绝和确认弹层，共 `OK (5 tests)`、耗时 `3.33s`。
- Redmi 真实动作：Accessibility 恢复为 `Enabled / Bound / Crashed services:{}` 后，同一 production Registry tracer 执行 `snapshot -> swipe(up) -> snapshot -> back`，日志为 `workflow-settings-multi-e2e success=true actions=swipe, back verified=2/2 approvals=0 freshSnapshots=true targetPackage=com.android.settings finalPackage=com.longdev.xiaoling goalDecision=VERIFIED privacySafe=true`。
- 文档语料：原黄金查询仍绑定历史 `Redmi 271 tests`，在当前报告中返回空结果并使 Recall 降为 `5/6`。门禁现改用验证报告顶部稳定存在的 `当前验证基线 / 正式产物 / 发布提交` 职责词，阈值、Top 5 和检索算法均未放宽；失败断言同时输出六条查询的实际文档排名。更新查询后的 Redmi 首轮/写回后复验均为 `OK (1 test)`，耗时 `2.461s / 2.444s`。
- Debug 探针收尾：移除支付密码提示、登录密码输入框和硬编码假凭据，默认页改为“设备动作验收 / 测试按钮”。所有前台设备 tracer 都在成功或失败后统一恢复 `MainActivity`；一次 Accessibility 陈旧连接导致的观察失败已证明清理路径生效，干净重绑后多动作 tracer 再次 `success=true / goalDecision=VERIFIED`，且 top resumed 为小灵主页面。
- 审查与边界：Standards 复审要求补齐长期文档和关键策略中文业务注释，均已修复；Spec 复审指出无证据 `SKIPPED` 可能被计为完成，现已收紧为必须携带冻结工具事实并增加反例。Repository 继续作为持久 Ledger 重建边界，不新增第二套 Runtime。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation，也没有向模拟器发送 ADB 命令。

## 2026-08-04 第 128 阶段：限定 App 多动作连续执行

- 范围：计划新增严格可空 `target_app_package`，非空时只能来自首批允许包。目标包冻结到 Workflow、Run 和每个步骤输入快照；手动/定时运行及关联重试继续沿用冻结值。确认 UI 显示限定应用，空值继续兼容非设备任务。
- Room v34：`workflows` 增加 nullable `targetAppPackage`，v33→v34 迁移不回填历史值。Redmi 定向迁移、Repository 目标包持久化和确认弹层分别为 `OK (1 test)`，耗时 `0.456s / 0.354s / 2.278s`；生成的 `34.json` 与迁移一致。
- 安全边界：策略版本升级为 `workflow-device-action-safety-v2`。`open_app` 参数和审批必须绑定冻结目标包；`tap_ref / type_text / swipe` 动作前后必须位于目标包；`back / home` 允许从目标包受控离开，但离开后下一次非 `open_app` 动作继续拒绝。包名门禁由本地策略执行，不信任 Prompt 或模型文本。
- Runtime TDD：新增 `snapshot -> back -> snapshot` 回归首轮按预期以 `AgentBudgetExceededException: 检测到重复工具调用：device.snapshot` 失败。实现只放行紧跟已验证设备动作的 snapshot 刷新，连续 snapshot 和其他重复 ToolCall 保持拒绝。八组聚焦 JVM 为 `MultiStepAgentRuntimeTest 6/6`、`PersonalTaskPlanPolicyTest 3/3`、`WorkflowDeviceActionApprovalGateTest 8/8`、`XiaoLingToolRegistryTest 36/36`、`WorkflowDeviceActionSafetyPolicyTest 18/18`、`WorkflowStepExecutionPolicyTest 13/13`、`WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowTypeTextSafetyPolicyTest 4/4`，合计 `92/92`，0 failure/error/skipped。
- Redmi 真实多动作：只使用 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`，覆盖安装 Debug APK 后 Accessibility 为 `Enabled / Bound / Crashed services:{}`。同一真实 `MinimalAgentRuntime + RoomAgentRunRepository` Run 在系统设置应用详情页执行 `snapshot -> swipe(up) -> snapshot -> back -> Complete`，production Registry 日志为 `workflow-settings-multi-e2e success=true actions=swipe, back verified=2/2 approvals=0 freshSnapshots=true targetPackage=com.android.settings finalPackage=com.longdev.xiaoling privacySafe=true`。
- 审查与边界：Standards/Spec 双轴复审确认 schema/迁移、目标包贯穿和动作后 snapshot 例外符合第 128 阶段；唯一过程 finding 是文档尚未同步，本节及其余长期文档已经修正。第 129 阶段目标级判定没有提前实现；本阶段没有运行完整 JVM、Lint、Release、默认完整 instrumentation 或文档语料门禁，也没有使用模拟器。

## 2026-08-04 第 127 阶段：自然语言个人任务与可确认计划

- 范围：新增“对话 / 任务”模式、严格 1 至 8 步计划和确认弹层；旧 `/agent` 直接执行保持不变。计划使用当前 Agent Profile 冻结的模型与工具白名单，显示任务名、原目标、步骤、可能审批项及 Agent/模型/工具边界；不支持附件。API Key 只存在 ViewModel 私有执行快照，不进入 Compose UI state。
- 阶段边界：本阶段计划请求只使用用户目标与 Profile 工具白名单，没有注入长期记忆或本地知识正文；该上下文接线仍属于第 130 阶段。第 127 阶段完成不等于完整第 127 至 132 阶段主链已经完成。
- 零执行边界：用户确认前不创建消息、Workflow、Workflow Run、Agent Run、审批或工具调用。取消会把原目标恢复到输入框；切换或删除会话会取消在途生成或丢弃待确认计划，迟到结果不能跨会话出现。确认后 `createWorkflowAndManualRun()` 在 Room 单事务创建普通 Workflow、手动 Run、定义步骤和全部运行步骤，再复用既有 Workflow/Agent Runtime、审批、验证与 Ledger。
- 结构化输出：Chat Completions 使用 `response_format={type:json_schema,json_schema:{strict:true,...}}`，Responses 使用 `text.format={type:json_schema,strict:true,...}`。客户端继续严格拒绝额外字段、Markdown fence、JSON 外文本、类型错误、空名称/步骤和 0 或 9 步等越界结果。
- 聚焦验证：`PersonalTaskPlanPolicyTest 3/3`、`OpenAiCompatibleAdapterTest 15/15`、`OpenAiCompatibleClientTest 11/11`、`ConversationProjectionTest 5/5`，合计 `34/34`；`assembleDebug / assembleDebugAndroidTest` 成功。Compose 计划弹层和 Room 原子创建单项只在 Redmi `wsvwypiz7xwslvl7` 合并运行为 `OK (2 tests)`、耗时 `2.03s`。
- Instrumentation 兼容修复：首次运行因传递依赖仍为 Espresso `3.5.0`，在当前 Android 上反射 `android.hardware.input.InputManager.getInstance()` 抛出 `NoSuchMethodException`。依据 AndroidX 稳定版本显式升级 `androidx.test.espresso:espresso-core` 至 `3.7.0` 后同两项通过；没有通过降低 Android 版本或改用模拟器规避。
- 真实模型闭环：默认 Agent `grok-4.20-0309` 生成 `Read Current Time` 单步计划，步骤为 `Determine the current system time`。确认后创建 Workflow `workflow-baa42c6e-6723-4739-aa27-ec6ceb0b67ee` 和首个 Run `workflow-run-b84d6b20-a7e9-4ad6-bc89-2a7ffc406b22`；该 Run 的 Runtime 模型规划在 `60000ms` 超时，保持 `FAILED`，关联 Agent Run 保持 `BUDGET_EXHAUSTED`。随后从同一 Workflow 手动运行新 Run `workflow-run-96d20f03-f5f2-4598-8d14-a18fbb7e2908`，独立完成 `app.current_time`、参数校验、工具执行、后置验证、完成规划和总结，结果为 `2026-08-04 00:47:10 · Asia/Shanghai`。Room 快照确认两个 Run 分别保留失败和完成终态，重试未覆盖旧事实。
- 文档语料：七份长期文档重新打入 AndroidTest APK 后，仅向 Redmi 覆盖安装测试包并运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`；首轮为 `OK (1 test)`、耗时 `2.453s`。写回首轮证据后的最终资产已以相同 `am instrument` 单项复验通过，随后只卸载测试包，主应用和私有数据保留。
- 验证边界：本阶段没有运行完整 JVM、Lint、Release 或默认完整 instrumentation，没有启动或向 Pixel/模拟器发送 ADB 命令；未新增 Room schema、第二套 Runtime、后台设备控制、任意 App 或新工具权限。

## 2026-08-03 第 127 至 132 阶段主线规划调整

- 目标：先跑通“自然语言目标 -> 记忆/知识 -> 可确认计划 -> 限定 App 多动作 -> 目标级验证 -> 持久化 -> 恢复/提醒”的完整前台个人 Agent，再集中处理体验、性能和高级生态。
- 顺序：第 127 阶段自然语言个人任务入口；第 128 阶段限定 App 多动作连续执行；第 129 阶段目标级验证；第 130 阶段记忆/知识/应用内提醒；第 131 阶段任务级关联恢复；第 132 阶段 Redmi 三条真实任务和统一完整门禁。
- 验证策略：第 127 至 131 阶段按快速迭代约束只运行聚焦测试、必要编译和功能闭环对应的 Redmi 单项；第 132 阶段再统一执行完整 JVM、Lint、Debug/AndroidTest APK 和默认 instrumentation。Release 只在用户明确要求时执行。
- 后置边界：截图/视觉、后台设备控制、任意 App、精确定时、Foreground Service、MCP、系统日历、远程 Channel、多 Agent、跨设备同步和本地模型不作为当前 MVP 的前置条件。

## 2026-08-03 第 126 阶段：`device.swipe` 生产默认 Registry 与 Redmi 真实链

- 范围：只把已经完成安全契约、执行期/完成态 evidence、答案级 Decision/Room/UI 投影和 Redmi 限定验收的 `device.swipe` 加入生产 `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES`。前台手动 Workflow 生产工具面现精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`；后台/定时设备工具、恢复自动续跑、任意 App、坐标和截图没有扩权，也没有新增 Room schema。
- 安全与隐私：`swipe` 继续固定为 `SAFE_NO_APPROVAL`，不创建 Room Approval 或 Accessibility 审批浮层。执行仍要求同 Run 新鲜 snapshot/ref、30 秒 TTL、当前 window generation、启用且未脱敏的 `SWIPE` 目标、动作前匿名 viewport、同窗内容变化和共同匿名锚点的请求方向主位移；完成后还要取得 Executor 验证、typed `PASSED`、动作后观察和严格 `device.swipe -> action=swipe` 答案级判定。方向、viewport/HMAC、snapshot/ref、节点正文、坐标和可复用节点身份不进入 Result、Room、Workflow output、日志或 UI。
- TDD/JVM：生产工具面断言首轮按预期失败，加入默认集合后 `XiaoLingToolRegistryTest` 为 `36/36`。六个相邻测试类合计 `101/101`，0 failure/error/skipped；`assembleDebug / assembleDebugAndroidTest` 成功。增强 Debug tracer 的答案级判定后再次 `assembleDebug` 成功。
- Redmi 真实生产链：只使用 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`，Debug 主包与 AndroidTest APK 覆盖安装成功。tracer 改用默认生产 Registry，在系统设置应用详情页运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 的 `snapshot -> swipe`；最终日志为 `workflow-swipe-e2e success=true action=swipe verified=true approvals=0 registryCompletion=PASSED answerDecision=VERIFIED beforePackage=com.android.settings afterPackage=com.android.settings privacySafe=true afterNodes=36`。这同时证明 SAFE 零审批、Registry 完成门禁、typed 验证、答案级本地判定和脱敏结果，但只覆盖首个限定 App/页面。
- 文档语料：七份长期文档重新打入 AndroidTest assets 后，只在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`；首轮/写回后的最终复验均为 `OK (1 test)`，耗时 `2.307s / 2.3s`。这项 focused instrumentation 只验证更新后的文档语料，不扩大为默认完整套件。
- 设备收尾：测试包已卸载，主应用仍为 `0.1.14 (15)`，`MainActivity` top resumed；单项结束后 Accessibility 一度为 Enabled 但未 Bound 并列入 crashed，保留其他状态、只移除并恢复小灵唯一服务组件后重新进入 `Enabled / Bound / Crashed services:{}`。crash buffer 无小灵异常，没有清主应用数据、执行 `force-stop`，也没有启动、使用或向模拟器发送 ADB 命令。
- 验证边界：按快速迭代分级未运行完整 JVM、Lint、Release、默认完整 instrumentation 或正式发版；本阶段只运行与生产 Registry、相邻安全链、Debug/AndroidTest APK 和 Redmi 真实 tracer 直接相关的验证。

## 2026-08-01 第 125 阶段：`device.swipe` 答案级脱敏 Decision 与 Room/UI 投影

- 范围：在不改变生产 `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 六项集合的前提下，接通 `device.swipe -> swipe` 的 `WorkflowDeviceActionDecisionPolicy`、`workflow-step-output-v1`、Room Tool Ledger 重建、下一步 previous output、关联重试和 Workflow Compose 证据卡。没有增加 Room schema、审批类型、生产 Registry 工具或后台设备自动化。
- 证据与隐私：Decision 必须来自同 Run `success=true / executorVerified=true / verificationStatus=PASSED`，通过严格 `WorkflowDeviceActionResultCodec` 并与 `action=swipe` 一致。Registry 专属完成门禁仍在 typed `PASSED` 前消费瞬态 viewport/HMAC；持久 Decision 只保留通用动作、前后包名、后置计数/截断/时间和规则版本。方向、viewport、HMAC、snapshot/ref、节点正文和坐标不进入 Room/Workflow output/UI。
- TDD：第一轮因 DecisionPolicy 返回 `NotApplicable` 和 UI label 显示 `swipe` 失败；第二轮纵向投影因 `WorkflowStepSnapshotCodec` 严格动作集合丢弃 swipe Decision 失败。补齐映射、脱敏文案和 snapshot 白名单后，`WorkflowDeviceActionDecisionPolicyTest`、`WorkflowStepExecutionPolicyTest` 和 `WorkflowManagementProjectionTest` 聚焦 JVM `44/44` 通过；`compileDebugAndroidTestKotlin`、`assembleDebug` 与 `assembleDebugAndroidTest` 通过。
- Redmi 单项：`adb devices -l` 确认唯一目标为 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`。Debug 主包与 AndroidTest 包均 `install -r -t` 成功；Room 纵向和 Compose 展示两个新增单项合并为 `OK (2 tests)`，耗时 `3.615s`。这只验收 Room/Workflow output/UI 投影，不是生产 Registry 真实滚动验收。
- 设备收尾：覆盖安装后 Accessibility 设置一度显示服务已 Enabled，但 `accessibility_enabled=0`、未 Bound 且有 crashed 标记。保留唯一小灵服务身份，定向关闭/重开后已恢复 `Enabled / Bound / Crashed services:{}`。`MainActivity` 为 top resumed，版本 `0.1.14 (15)`，crash buffer 对 `com.longdev.xiaoling / FATAL EXCEPTION` 零命中。
- 验证边界：未运行完整 JVM、Lint、Release、默认完整 instrumentation 或文档 corpus instrumentation，没有启动或操作模拟器。下一阶段再单独扩展生产默认集合，并仅用 Redmi 执行真实生产 Workflow `snapshot -> swipe`。

## 2026-08-01 第 124 阶段：`device.swipe` Registry 完成态交接与 Redmi 限定验收

- 范围：把 `DeviceActionOutcome.swipeEvidence` 交给 Registry 完成门禁，并新增仅供 Debug/测试显式注入的真实 Workflow swipe tracer；生产默认 Workflow 集合继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。没有扩展答案级 DecisionPolicy、Room/Workflow output、Compose 或后台自动化。
- 完成证据与隐私：Registry 只有在 outcome 的 `beforeSnapshotId` 等于授权前 snapshot，before viewport 的包名/window/generation 等于该 snapshot，after viewport 的同三项等于真实后置 snapshot 时才构造 `WorkflowSwipeCompletionEvidence`。错串窗口反例先红后转绿。Result codec 只增加 `action=swipe` 的既有通用摘要，完整 viewport、目标/锚点 HMAC、snapshot/ref、节点正文和坐标仍不进入 Result、Room、日志或 UI；真实 tracer 对 snapshot/ref 和 64 位十六进制 HMAC 均做了零泄漏断言。
- 聚焦 JVM：`DeviceObservationControllerTest 12/12`、`DeviceActionControllerTest 8/8`、`DeviceActionCodecTest 1/1`、`DeviceSnapshotPolicyTest 9/9`、`WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowDeviceActionSafetyPolicyTest 17/17`、`WorkflowTypeTextSafetyPolicyTest 4/4`、`XiaoLingToolRegistryTest 36/36`，合计 `91/91`，0 failure/error/skipped。`compileDebugKotlin` 与 `assembleDebug` 成功，`git diff --check` 通过。
- Redmi 真实验收：`adb devices -l` 只有 `wsvwypiz7xwslvl7 / Redmi_Note_8_Pro`。Debug tracer 在系统设置应用详情页运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 的 `snapshot -> swipe(up)`，最终日志为 `workflow-swipe-e2e success=true action=swipe verified=true approvals=0 registryCompletion=PASSED beforePackage=com.android.settings afterPackage=com.android.settings privacySafe=true afterNodes=24`。这同时证明 SAFE 零审批、Executor/typed 验证、Registry 专属完成门禁、同包后置观察和有限 Result 隐私，不承诺其他 App 或页面。
- 设备收尾：Debug APK 覆盖安装成功；首次按错误顺序恢复 Accessibility 后又执行 `force-stop`，该 Redmi ROM 按既有行为清除了授权。随后确认 `ACCESS_RESTRICTED_SETTINGS=allow`，在应用启动后重新写入唯一小灵服务组件且不再强停，最终 `MainActivity` top resumed，版本 `0.1.14 (15)`，Accessibility 为 `Enabled / Bound / Crashed services:{}`，crash buffer 无本应用异常。
- 验证边界：按快速迭代分级未运行完整 JVM、Lint、AndroidTest APK、Release、默认完整 instrumentation 或文档 corpus instrumentation，也没有启动或操作模拟器。下一阶段先独立评审 swipe 的答案级脱敏判定、Room/Workflow output 和 UI 投影，再决定是否进入生产默认集合；后台/定时设备自动化继续关闭。

## 2026-08-01 第 123 阶段：`device.swipe` Controller/Registry HMAC evidence seam

- 范围：完成 Controller 前后 snapshot 的执行期匿名 evidence、`inspectReference()` 动作前 viewport 和 Registry 显式测试态交接；生产 Workflow 默认集合仍精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。没有修改 Result codec、DecisionPolicy、Room、审批、Compose、答案级 UI 或后台自动化。
- 隐私与生命周期：每个 Controller 实例默认生成随机 32-byte HMAC key，测试只在随机系统边界注入固定 key。目标与锚点使用长度前缀结构化 `HmacSHA256`，锚点额外绑定当前滚动目标，不含 bounds、generation、snapshot/ref 或节点明文；只选择目标下未脱敏的语义后代，重复语义身份全部移除。相同页面在不同 key 下产生不同指纹，同一窗口不同滚动目标中的相同语义也产生不同锚点指纹。当前成功 snapshot、ref 和 viewport 在 capture/动作失败、显式清理或 Agent Run 切换时一起撤销；inspection 在同一生命周期锁内核对 snapshot/ref 并生成 viewport，锁外复读 generation，证据构造期间页面变化时不返回 target/viewport。
- 后置验证：`DeviceObservationController.swipe()` 已替换 generation-only 判定。动作前后必须同应用、同 window、同匿名目标，generation 前进、两侧至少两个唯一锚点且可见内容集合变化；至少一个共同锚点按请求方向发生不小于 `8px` 的主位移，任一显著反向或横向占优锚点会整体拒绝。设备层与 Workflow 层共享 viewport/anchor 类型和方向验证器。
- Registry 与泄漏边界：`SUPPORTED_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 允许测试显式注入 swipe，但生产 `DEFAULT_WORKFLOW_DEVICE_ACTION_TOOL_NAMES` 不变。Registry 将当前 inspection viewport 交给既有 `WorkflowSwipeSafetyPolicy`，并对 SAFE swipe 使用真实执行时钟而非异常 approval 时间核对 TTL。完整 evidence 不进入 `DeviceActionCodec`、`WorkflowDeviceActionResultCodec`、Room、日志、Workflow output 或 UI。
- TDD：红灯先确认缺少 Controller evidence API、Registry 构造期拒绝 swipe 和 Run 切换未撤销 refs；随后逐片转绿，并补充不同滚动目标的相同语义锚点不可关联、inspection 期间 generation 漂移时 fail-closed。最终八组聚焦 JVM：`DeviceObservationControllerTest 12/12`、`DeviceActionControllerTest 8/8`、`DeviceActionCodecTest 1/1`、`DeviceSnapshotPolicyTest 9/9`、`WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowDeviceActionSafetyPolicyTest 17/17`、`WorkflowTypeTextSafetyPolicyTest 4/4`、`XiaoLingToolRegistryTest 34/34`，合计 `89/89`，0 failure/error/skipped。
- 审查与验证边界：早期 Standards/Spec 子代理在给出部分结果后被本地 `http://localhost:8080/responses` 的 404 终止；最终独立文档核验确认并清理了旧阶段措辞，Standards 复核无 findings，Spec 复核确认 HMAC/方向契约并指出 generation 读取竞态。另一项“直接 `/agent` swipe 扩权”经固定点 `062bb7f` 反查确认是基线既有行为，本阶段只把 swipe 加入显式测试 Workflow supported 集合，生产默认 Workflow 集合仍关闭。当前实现已补 generation 双读 fail-closed、同锁 snapshot/ref/viewport 一致性和锚点目标域隔离，新增反例随八组聚焦 JVM `89/89` 通过。主线程此前还合并了重复 viewport/anchor 类型和方向算法，避免设备层与 Workflow 阈值漂移。按快速迭代分级未运行完整 JVM、Lint、APK、Release、Redmi instrumentation 或真实滚动；第 124 阶段随后完成 Registry 完成态纯内存 evidence 交接和 Redmi 真实滚动验收。

## 2026-08-01 第 122 阶段：前台 Workflow `device.swipe` 专属安全契约

- 范围：只冻结纯 Kotlin swipe 专属执行与完成策略，不接生产 Registry、Controller、Result codec、DecisionPolicy、Room、审批或 UI。生产前台 Workflow 工具面仍精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`；`swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时和 Foreground Service 继续关闭。
- 执行门禁：参数必须精确为 `snapshot_id / ref / direction`，方向只接受 `up / down / left / right`。当前目标必须启用、未脱敏且支持 `SWIPE`；动作前 viewport 必须包含同应用、有效 window ID、非负 generation、同一匿名目标指纹和至少两个去重的 64 位匿名可见锚点。通用策略缺少专属证据时以 `SWIPE_POLICY_DENIED` 拒绝。
- SAFE 与最小授权：现有 `device.swipe` ToolDefinition 标记 `SAFE`，因此专属 Workflow 策略使用 `SAFE_NO_APPROVAL`；同 Run/ToolCall、已验证 snapshot、30 秒 TTL、当前 generation、实时 ref、Executor/typed 验证和动作后观察仍全部强制。专属授权只保存方向与动作前 viewport SHA-256 摘要，不保存包名、snapshot/ref、目标指纹、完整锚点或节点正文。
- 后置证明：动作前后必须属于同一应用、同一 window 和同一目标，generation 严格前进，可见匿名内容集合发生变化；至少一个共同锚点必须按请求方向产生不小于 `8px` 且主方向占优的位移。任一显著共同锚点反向或横向占优时整体拒绝，不能由另一个正确锚点掩盖；内容不变、目标或窗口漂移、只有 generation 变化及只有 Android API 接收动作也不能判定成功。四个方向均有正向回归，并覆盖正确/矛盾锚点混合出现的拒绝反例。
- 聚焦验证：`WorkflowSwipeSafetyPolicyTest 4/4`、`WorkflowDeviceActionSafetyPolicyTest 17/17`、`WorkflowTypeTextSafetyPolicyTest 4/4`、`XiaoLingToolRegistryTest 30/30`，合计 `55/55`，0 failure/error/skipped；变更后编译测试为 `BUILD SUCCESSFUL in 17s`，无改动复跑仍为 `BUILD SUCCESSFUL`。
- 验证边界：按快速迭代分级没有运行完整 JVM、Lint、APK、Release、Redmi instrumentation 或真实设备滚动，Redmi 正式 `v0.1.14` 保持安装状态。第 123 阶段随后完成 Controller/Registry 执行期 opaque/HMAC evidence seam；完整锚点继续不得进入 Room、日志、Workflow output 或答案级输出。

## 2026-07-31 第 121 阶段：前台 Workflow `device.open_app` 生产闭环

- 范围：只把逐包审批的 `device.open_app` 接入前台手动 Workflow。生产工具面精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`；`swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时和 Foreground Service 继续关闭。
- 安全与审批：`open_app` 只接受唯一 `package_name`，且目标必须属于小灵、系统计算器、时钟或系统设置。SafetyPolicy、Workflow ApprovalGate 和 Executor 三层分别核验白名单；Room Approval 与 Accessibility overlay 精确绑定当前 Run、ToolCall、参数、进程 session、同 Run 已验证 snapshot、30 秒 TTL 和 window generation。首次人工查看浮层超过 TTL 后按设计拒绝“device.snapshot 已过期或时间证据不完整”，没有使用迟到审批放行旧观察。
- 执行与答案证据：Registry 为非 ref 动作读取当前 window generation，`open_app` 不伪造节点引用；动作后必须重新观察并得到 `success=true / executorVerified=true / typed PASSED`。严格 result codec、完成安全门禁与 Room 答案级重建共同要求后置包名等于同一 ToolCall 获批包名；成功只投影“打开应用”、前后包名、有限节点摘要和不可复用节点边界，原始审批参数、Intent、snapshot/ref 与模型转述不进入 Workflow UI。
- 独立审查：首轮发现 SafetyPolicy 自身未拒绝空参数、额外参数或非白名单包，补失败测试后在策略层修复；提交前审查又发现答案级 Decision 只信任结果正文中的 `verified`，没有把 `afterPackageName` 与获批目标绑定，现已在完成门禁和 Room 重建两层补齐一致性核验与错配反例。最终 Standards/Spec 双轴复核没有规格缺口；唯一规范问题是 `DeviceController.currentWindowGeneration()` 通过空 snapshot/ref 默认委托且缺少关键业务注释，现已改为实现方显式契约，禁用控制器固定返回不可用代次，并同步修正 Registry 生产工具注释。
- 聚焦验证：`XiaoLingToolRegistryTest`、`WorkflowDeviceActionApprovalGateTest`、`WorkflowDeviceActionSafetyPolicyTest`、`WorkflowDeviceActionDecisionPolicyTest`、`WorkflowStepExecutionPolicyTest` 与 `WorkflowManagementProjectionTest` 合计 `95/95`，0 failure/error/skipped；`assembleDebug / assembleDebugAndroidTest` 均成功。
- Redmi 定向：仅使用 `wsvwypiz7xwslvl7`。Compose `pageDisplaysVerifiedOpenAppTargetAndFollowUpBoundary` 为 `OK (1 test)`、`2.681s`；Room `workflowPersistsVerifiedOpenAppDecisionForNextStepAndUi` 为 `OK (1 test)`、`0.628s`。稳定窗口重试并及时批准后的真实日志为 `workflow-open-app-e2e success=true action=open_app verified=true approval=APPROVED executorVerified=true verification=PASSED beforePackage=com.longdev.xiaoling afterPackage=com.android.calculator2 answerDecision=VERIFIED`。
- 文档与验证边界：七份长期文档打入 AndroidTest assets 后，Redmi `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 首轮为 `OK (1 test)`、`2.783s`，该证据写回后的冻结文本复验同样通过。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。设备收尾只卸载测试包，保留主应用与数据；精确移除并恢复唯一 Accessibility 组件后为 `Enabled / Bound / Crashed services:{}`，`MainActivity` 已恢复前台，清空后的 crash buffer 没有小灵相关 FATAL。

## 2026-07-31 第 120 阶段：前台 Workflow `device.home` 生产闭环

- 范围：只把空参数 SAFE `device.home` 接入前台手动 Workflow。生产工具面精确为 `device.snapshot / device.back / device.home / device.tap_ref / device.type_text`；`open_app / swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时和 Foreground Service 继续关闭。
- 安全与执行：`home` 固定为 `SAFE_NO_APPROVAL`，不创建 Room Approval 或 Accessibility overlay；额外参数在 Schema/策略层拒绝。零审批仍要求用户步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 window generation、Executor 验证、typed `PASSED` 和动作后已验证观察。Registry 对 `back / home` 始终使用当前执行时钟，异常审批对象不能把已过期 snapshot 重新变为可执行。
- Launcher 与答案证据：`DeviceObservationController.home()` 的后置验证调用 `AndroidDeviceAccessibilityGateway.isHomePackage()`，由系统 `ACTION_MAIN + CATEGORY_HOME` 动态解析 launcher 包，不写死 Redmi 桌面。`WorkflowDeviceActionDecisionPolicy` 只接受 `device.home -> home`；step output snapshot、下一步、关联重试、Room Repository 与 Workflow 管理页统一显示“返回桌面”和白名单前后窗口摘要，并明确不产生可复用节点引用。
- 审批边界：审批证据投影继续只覆盖 `tap_ref / type_text`，因此 SAFE `home` 不会产生伪审批卡。历史 snapshot/ref、节点、原始结果和模型转述仍不进入答案级 UI；后续设备动作必须重新观察并按各自风险规则执行。
- 聚焦 JVM/构建：六组聚焦 JVM 合计 `87/87`，均为 0 failure/error/skipped；`assembleDebug / assembleDebugAndroidTest` 为 `BUILD SUCCESSFUL`，`git diff --check` 通过。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Standards/Spec：以 `7e88ab8` 为固定点完成独立审查。白名单只新增 `home`，`open_app / swipe` 未扩权；SAFE 导航统一使用当前时钟；后置验证依赖动态 launcher；Room/UI 未生成伪 Approval。额外复核确认动作后的 snapshot 作为 `device.home` 结果内嵌证据绑定同一 ToolCall、Executor 与 typed `PASSED`，tracer 末尾的再次 capture 只验证设备仍停留在 launcher，不需要制造第三个工具调用。
- Redmi 定向：只向 `wsvwypiz7xwslvl7` 安装和发送 instrumentation，在线模拟器没有收到命令。Compose `pageDisplaysVerifiedHomeAsSafeNavigationEvidence` 为 `OK (1 test)`、`2.472s`；Room 纵向 `workflowPersistsVerifiedHomeDecisionForNextStepAndUiWithoutApprovalEvidence` 为 `OK (1 test)`、`0.605s`。真实 tracer 为 `workflow-home-e2e success=true action=home verified=true approvals=0 beforePackage=com.android.settings afterPackage=com.android.launcher3 answerDecision=VERIFIED`。
- Accessibility 收尾：两条 instrumentation 与 tracer 完成后只卸载测试包，主应用与数据保留；精确移除并原样恢复唯一 Accessibility 组件后服务重新进入 `Bound`，`Crashed services:{}`。主应用 `MainActivity` resumed、PID `4027`，清空后的 crash buffer 与 `AndroidRuntime` 没有输出。
- 文档门禁：七份长期文档同步后重新打入 AndroidTest assets，只在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`；首轮结果为 `OK (1 test)`、测试时间 `2.76s`。该证据写回后重新构建并运行同一单项，最终复验同样为 `OK (1 test)`。

## 2026-07-30 第 119 阶段：前台 Workflow `device.back` 生产闭环

- 范围：只把空参数 SAFE `device.back` 接入前台手动 Workflow。生产工具面精确为 `device.snapshot / device.back / device.tap_ref / device.type_text`；`open_app / home / swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时和 Foreground Service 继续关闭。
- 安全与执行：`back` 固定为 `SAFE_NO_APPROVAL`，不创建 Room Approval 或 Accessibility overlay；额外参数在 Schema/策略层拒绝。零审批仍要求用户步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 window generation、Executor 验证、typed `PASSED` 和动作后已验证观察。Registry 对 `back` 始终使用当前执行时钟，异常审批对象不能把已过期 snapshot 重新变为可执行。
- 持久化与 UI：`WorkflowDeviceActionDecisionPolicy` 只接受 `device.back -> back`，step output snapshot、下一步、关联重试、Room Repository 与 Workflow 管理页统一显示“返回”和白名单前后窗口摘要。审批证据投影继续只覆盖 `tap_ref / type_text`，因此 SAFE `back` 不会产生伪审批卡；历史 snapshot/ref、节点、原始结果和模型转述仍不进入答案级 UI。
- 聚焦 JVM/构建：`WorkflowDeviceActionSafetyPolicyTest`、`WorkflowDeviceActionDecisionPolicyTest`、`WorkflowStepExecutionPolicyTest`、`XiaoLingToolRegistryTest` 和 `WorkflowManagementProjectionTest` 同批运行通过；新增回归证明旧审批时间不能延长 `back` snapshot TTL。`compileDebugKotlin / compileDebugAndroidTestKotlin / assembleDebug / assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 定向：只向 `wsvwypiz7xwslvl7` 安装和发送 instrumentation，在线 `emulator-5554` 仅出现在设备列表。Compose `pageDisplaysVerifiedBackAsSafeNavigationEvidence` 与 Room 纵向 `workflowPersistsVerifiedBackDecisionForNextStepAndUiWithoutApprovalEvidence` 均为 `OK (1 test)`。真实 tracer 为 `workflow-back-e2e success=true action=back verified=true approvals=0 beforePackage=com.android.settings afterPackage=com.longdev.xiaoling answerDecision=VERIFIED`。
- Accessibility 收尾：两条 instrumentation 结束后主包被强制停止，系统一度显示 `Enabled services` 含小灵、`Bound services:{}`、`Crashed services` 含小灵；没有应用 FATAL。移除并原样恢复该组件授权后，服务重新进入 `Bound`，`Crashed services:{}`，未清主应用数据、Provider 或 Keystore 凭据。
- 文档门禁：七份长期文档同步后重新打入 AndroidTest assets，只在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮结果为 `OK (1 test)`、测试时间 `2.733s`；该证据写回后重新构建并再次运行同一单项，最终结果为 `OK (1 test)`、测试时间 `2.725s`。

## 2026-07-30 第 118 阶段：统一直接 `/agent` 的 `type_text` 持久化隐私

- 范围：只统一前台直接 `/agent` 与前台 Workflow 的 `device.type_text` 持久化隐私，不开放 `swipe / open_app / back / home`，不改变后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service 边界。
- 统一投影：新增 `DeviceTypeTextAuditPolicy`，Runtime proposed/validated、独立 ToolCall ledger、Room Approval、requested/decided 事件、Workflow gate、`VerifiedAgentContext` 与消息 Tool parts 统一只保存 `snapshot_id / ref / text_sha256 / text_length`。`RoomAgentRunRepository.createApprovalRequest()` 在事务写入前再次净化参数，成为所有入口共享的最终持久化防线。
- 当前进程审批：直接 `/agent` 审批卡仍从内存 ToolCall 展示真实输入，但 Room 请求必须与原 ToolCall 的 Run/ToolCall ID、工具名、风险和安全投影逐项一致；任一漂移都不展示或批准。一致性校验在审批协调器注册 ticket 之前完成，失败不能遗留活动 waiter。任务中心、消息恢复和历史审批只读取安全投影，不能从 SHA-256 指纹恢复原文。
- 恢复处置：`AgentRunResumePolicy` 新增 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE`。应用重启后，旧 `type_text` 待审批链尾不再恢复为 `APPROVAL_WAIT`；旧 Approval 与旧 Run 在启动事务中安全取消，用户必须创建新 Run 重新输入。旧 Run、旧 ToolCall 与旧审批不重放、不改写为成功。
- 持久化旁路复核：首轮实现后，人工审查发现 `VerifiedAgentContext` 仍从 Executor 原始 ToolCall 构造，并会经 `AgentMessagePartPolicy` 写入消息 Tool parts。先增加“可信消息上下文不含输入原文”的失败断言，再让 Runtime 复用同一安全投影；复核后没有发现其他持久化原文入口。
- Standards/Spec：两个默认子代理仍在启动时被本地 `http://localhost:8080/responses` 的 `404 Not Found` 阻断，主线程按两条轴线分别复核。Standards 轴未发现项目注释、Redmi-only、分级验证或结构硬违规；Spec 轴发现安全投影校验原本发生在审批 ticket 注册之后，漂移异常可能遗留活动 waiter。实现改为先校验再注册，并补齐 ID、名称、风险和参数投影四类反例，复跑后无剩余 finding。
- 聚焦 JVM/构建：`MinimalAgentRuntimeTest 66/66`、`AgentRunResumePolicyTest 57/57`、`WorkflowDeviceActionApprovalGateTest 6/6`、`AgentApprovalUiStateTest 1/1`，合计 `130/130`，均为 0 failure/error/skipped。`assembleDebug / assembleDebugAndroidTest` 通过；按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 定向：只向真机 `wsvwypiz7xwslvl7` 发送安装和 instrumentation 命令。`typeTextApprovalPersistsFingerprintWithoutInputText`、`typeTextPendingApprovalClosesAfterProcessRecoveryWithoutInputText`、`typeTextApprovalPersistsFingerprintAndLengthWithoutInputText` 均为 `OK (1 test)`；在线 `emulator-5554` 仅出现在设备列表，没有收到安装、测试或 UI 命令。
- 文档门禁：第 118 阶段长期文档首次重新打入 AndroidTest assets 后，仅在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`，测试时间 `3.101s`、命令墙钟 `5.39s`；写回首轮证据后的复验仍为 `OK (1 test)`，测试时间 `2.693s`、命令墙钟 `4.91s`；代码复审修复和最终阶段摘要全部写入后的门禁继续为 `OK (1 test)`，测试时间 `2.920s`、命令墙钟 `5.52s`。该最终证据写回后的冻结文本已再次打包并通过同一单项，最终测试时间 `2.826s`。

## 2026-07-30 第 117 阶段：前台 Workflow `type_text` 生产闭环

- 范围：把第 115/116 阶段已经冻结并在 Redmi 直接动作验证的 `device.type_text` 接入前台手动 Workflow 生产链。工具面精确为 `device.snapshot / device.tap_ref / device.type_text`；`open_app / back / home / swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时和 Foreground Service 继续关闭。第 117 阶段当时没有改变直接 `/agent`；第 118 阶段已经统一其持久化隐私。
- 持久化/overlay 隐私：`WorkflowDeviceActionApprovalGate` 同时截获 tap 与文本输入。`type_text` 原文只留在当前 ToolCall 内存；该阶段的 Workflow 审计投影让 proposed/validated/ToolCall ledger、Room Approval 和 requested/decided 事件统一只保存 `snapshot_id / ref / text_sha256 / text_length`，Accessibility overlay 请求只携带步骤意图、工具说明和“输入 N 个字符，内容不展示”。持久化请求与终态决定继续逐字段核对身份，取消先在不可取消 IO 区收敛；第 118 阶段已用通用 `DeviceTypeTextAuditPolicy` 取代专属投影并覆盖直接 `/agent` 与可信消息上下文。
- 执行与答案证据：生产 Registry 默认集合加入 `device.type_text`，仍强制专属文本策略、当前 ref 的可编辑/未脱敏目标证据、同 Run/ToolCall、30 秒 TTL、window generation、敏感文本预审计、Executor/typed 验证、动作后观察和原 `nodePath` 精确回读。`WorkflowDeviceActionDecisionPolicy` 只接受 `device.tap_ref -> tap_ref / device.type_text -> type_text`；step output、下一步、关联重试和 Compose 只保留版本化本地判定，页面显示“输入文本（内容不展示）/ 输入原文未保存到答案级证据”。
- Overlay 稳定性：Redmi 实测 overlay 移除后连续产生多条 `TYPE_WINDOWS_CHANGED`。Coordinator 现在保留请求 `100ms` 并只调度一次 settle；连续基线 detach 继续抑制自有 generation 作废，最终再核对活动根和完整窗口集合。结算期间的外来窗口、内容变化、服务断连、超时及最终漂移仍 fail-closed。新增反例覆盖活动根漂移、没有尾随事件时最终窗口集合漂移，以及 settle 期间外来窗口不能被抑制。
- Standards/Spec：以 `87067d4` 为固定点。两个默认子代理均在启动时被本地 `http://localhost:8080/responses` 的 `404 Not Found` 阻断；主线程保持两轴分离复核。Standards 轴未发现项目注释、分级验证、Redmi-only 或结构硬违规；Spec 轴先补充最终窗口集合反例，提交前复核又发现原始 `text` 仍进入 `tool.call.proposed / validated` 与 ToolCall ledger，违反“只驻留当前 Workflow ToolCall 内存”的要求。新增 Runtime TDD 回归与统一审计投影后，Workflow 持久链无原文；第 117 阶段当时未修改的直接 `/agent` 边界已由第 118 阶段继续收敛。
- 聚焦 JVM/构建：Runtime 审计 `1/1`、Gate `6/6`、Registry `25/25`、动作判定 `4/4`、通用动作安全 `11/11`、文本安全 `4/4`、overlay coordinator `9/9`、Workflow 投影 `15/15`，合计 `75/75`，均为 `0 failed / 0 errors / 0 skipped`。`assembleDebug / assembleDebugAndroidTest` 与 `git diff --check` 通过；按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 定向：只向 `wsvwypiz7xwslvl7` 安装 Debug/Test APK 和发送 instrumentation。Compose `pageDisplaysVerifiedTypeTextWithoutInputContent`、Room Approval `typeTextApprovalPersistsFingerprintAndLengthWithoutInputText`、Room Workflow `workflowReplacesVerifiedTypeTextWithPrivacySafeDecisionForNextStepRetryAndProjection` 均为 `OK (1 test)`。真实 tracer 日志为 `workflow-type-text-e2e success=true action=type_text verified=true approval=APPROVED answerDecision=VERIFIED exactReadBack=true afterNodes=2`，并逐字段确认 Approval 与 ToolCall ledger 都没有原文；tap 对照链仍成功。在线 `emulator-5554` 只出现在设备列表，没有收到安装、测试或 UI 命令。
- 文档门禁：README 与长期文档已同步为第 117 阶段状态。写回首轮证据前的 Redmi corpus gate 为 `OK (1 test)`、耗时 `2.907s`；首轮证据进入 assets 后复验为 `2.763s`，第一次最终文本复验为 `2.658s`。提交前审查修复 ToolCall 审计旁路并更新全部长期文档后，最终 assets 再次为 `OK (1 test)`、耗时 `3.062s`；本条结果写回后的最终 assets 已重新构建并通过同一单项。收尾只卸载 `com.longdev.xiaoling.test`，保留主包、Provider/Profile、Keystore 与 Accessibility 配置。

## 2026-07-30 第 116 阶段：前台 Workflow `type_text` evidence seam

- 范围：只接通当前 ref 的可编辑目标证据、动作后原目标精确回读和 Registry 测试态 `type_text` 生命周期；生产 Workflow 默认工具面继续为 `device.snapshot / device.tap_ref`。不修改 Room、Workflow 审批 gate、Accessibility overlay、Workflow Repository、答案级动作 UI、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service。
- TDD Red：设备层先新增“当前 ref 返回可编辑/未脱敏/`TYPE_TEXT` 证据”和“原目标文本不一致但页面其他节点含预期文本时仍未验证”两条测试；首轮 `compileDebugUnitTestKotlin` 因 `DeviceReferenceTargetInspection / typeTextReadBack` 不存在失败。Registry 再新增测试态完整正例与不可编辑目标反例，首轮因缺少 `workflowDeviceActionToolNames` seam 编译失败。Result codec 接受 `type_text` 后，答案级错误动作反例首次以 `ClassCastException` 暴露 DecisionPolicy 会误收摘要，随后按工具名和结果动作双重一致收紧。
- 引用目标证据：`DeviceSnapshotPolicy` 生成 ref 时把 enabled、editable、redacted 和动作集合与 node path/fingerprint 一起冻结到 `DeviceNodeReferenceStore`。`inspectReference()` 只对当前 snapshot/ref、未过期 TTL 与相同 window generation 返回 `DeviceReferenceTargetInspection`；旧 ref、过期、缺失或页面漂移仍没有目标证据。
- 精确回读：`DeviceActionOutcome` 增加瞬态 `DeviceTypeTextReadBack`。动作后 capture 必须在新 references 中唯一匹配动作前原 `nodePath`，再按新 ref 读取该 `DeviceSnapshotNode.text`；不再扫描任意节点的 text/description/hint。原目标消失、路径不唯一、无动作后 ref 或文本不完全相等时保持 `verified=false`，其他节点同文不能误判。
- Registry 与输出边界：生产默认动作集合仍只有 `device.tap_ref`；JVM 测试显式加入 `device.type_text` 时，执行前目标证据进入 `WorkflowTypeTextExecutionEvidence`，原始 identity 仅在内存中跟随授权状态，完成时从强类型 readback 构造专属证据并重新核对文本指纹。注入集合被硬限制为 `{device.tap_ref, device.type_text}` 子集，其他已注册设备动作不能借该 seam 进入 Workflow。`workflow-device-action-result-v1` 可标识 `type_text` 但不包含原文、snapshot ID、ref 或节点；答案级 DecisionPolicy 仍只消费工具名与结果动作均为 `tap_ref` 的证据。
- 双轴复审：Standards/Spec 子代理均再次被本地 `http://localhost:8080/responses` 的 404 阻断；主线程以 `1d7bb59` 为固定点审查完整工作树。Standards 轴发现构造参数能误启用 `open_app / back / home / swipe` 等其他已知设备动作，与“仅测试 `type_text`”的注释和规格不一致；已增加构造期 fail-closed 限制与 `device.swipe` 反例。Spec 轴复核目标证据、原路径回读、无原文结果、答案级关闭和生产默认集合后无遗留 finding。
- 聚焦 JVM/构建：`DeviceObservationControllerTest 4/4`、`DeviceActionControllerTest 7/7`、引用存储 `2/2`、快照策略 `9/9`、文本策略 `2/2`、专属 Workflow 策略 `4/4`、通用 Workflow 动作策略 `11/11`、答案级判定 `3/3`、Registry `24/24` 和敏感参数预审计 `1/1`，合计 `67/67`，均为 `0 failed / 0 errors / 0 skipped`。`assembleDebug / assembleDebugAndroidTest` 与 `git diff --check` 通过；按分级验证未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 正反例：所有命令显式使用 `wsvwypiz7xwslvl7`。默认 Debug APK `install -r` 成功；安装后系统将 Accessibility 置为关闭，首次 shell 标记启用虽显示 Bound，但 Debug capture 连续返回 `NO_ACTIVE_WINDOW`。在系统无障碍详情页完成“关闭→允许”真实确认后，快照恢复为 `com.android.settings / nodes=26 / refs=11 / redacted=0 / truncated=false`。点击“搜索设置”进入 `com.android.settings.intelligence` 后，真实搜索框输入 `stage116_exact_readback` 返回 `type_text success=true / verified=true / nodes=5 / refs=4`；随后伪密钥输入返回 `SENSITIVE_INPUT`，UIAutomator 回读仍为 `stage116_exact_readback`，证明敏感值没有覆盖原目标。一次使用旧标签“搜索设置”寻找输入框以 `REFERENCE_NOT_FOUND` 在动作前拒绝，也没有执行输入。在线 `emulator-5554 / emulator-5556` 只出现在设备列表，没有收到定向命令。
- Redmi 文档门禁：README 与五份长期文档重新打入 AndroidTest assets 后，只在 `wsvwypiz7xwslvl7` 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首轮为 `OK (1 test)`、JUnit 时间 `4.358s`；写回首轮证据后的 Gradle 最终单项 JUnit testcase 为 `2.569s`，但 `connectedDebugAndroidTest` 结束时卸载了主包与测试包。恢复应用后改用手动 `install -r + am instrument`，保留主包的首轮为 `OK (1 test)`、耗时 `2.632s`；写回审查与设备收尾事实后的最终 assets 已以相同手动流程复验通过，并只卸载测试包。没有向在线模拟器安装主包、测试包或发送 instrumentation 命令。
- 生产关闭：默认 Registry 的现有回归继续精确暴露 `device.snapshot / device.tap_ref`，强行执行 `device.type_text` 仍返回“尚未开放给 Workflow”且 controller 不产生输入动作。下一阶段必须把 Room 独立审批、Accessibility overlay、无原文答案级判定/UI 和真实 Redmi Workflow 同时闭环后，才可评估修改生产默认动作集合。

## 2026-07-30 第 115 阶段：前台 Workflow `type_text` 专属安全契约

- 范围：只冻结 `device.type_text` 进入前台 Workflow 前必须满足的独立文本、节点和完成证据，不把它加入生产 Registry 白名单，不修改 Room、Accessibility、Workflow Repository、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service。
- TDD Red：先新增 `WorkflowTypeTextSafetyPolicyTest` 和 Registry 强制调用反例。首轮 `:app:compileDebugUnitTestKotlin FAILED`，核心错误为 `Unresolved reference 'WorkflowTypeTextSafetyPolicy'`，同时确认通用证据、授权和失败枚举尚无专属契约；其余类型推断错误均为缺少新类型引起的连锁错误。
- 执行契约：新增纯 Kotlin `WorkflowTypeTextSafetyPolicy`。参数键必须精确为 `snapshot_id / ref / text`，文本复用 `DeviceActionPolicy` 的 500 字符、控制字符和敏感信息拒绝规则；当前目标节点必须启用、可编辑、未脱敏且支持 `TYPE_TEXT`。专属授权只保存规则版本、Run/ToolCall 身份、文本 SHA-256 指纹和长度，不保存输入原文、snapshot ID 或 ref。
- 通用门禁与完成：`WorkflowDeviceActionSafetyPolicy` 对 `type_text` 强制委托专属策略，通用授权 identity 移除 `text` 并携带专属授权；缺少任一专属证据时以 `TYPE_TEXT_POLICY_DENIED` 拒绝。完成要求原文本指纹、同一 Run/ToolCall 结果、Executor 验证、typed 验证、动作后已验证观察、正确时序和精确文本回读全部一致。
- 生产关闭反例：`XiaoLingToolRegistry` 的 Workflow 工具面仍为 `device.snapshot / device.tap_ref`。测试绕过工具清单直接执行 `device.type_text` 时返回失败，文案包含“尚未开放给 Workflow”，Fake controller 只保留此前 `tap_ref` 动作，没有输入动作。
- 双轴复审：Standards/Spec 子代理均被本地 `/responses` 404 阻断，主线程以 `4c22efc` 为固定点审查完整工作树 diff。代码符合项目中文业务注释和 fail-closed 约束；规格中的精确参数、敏感文本、可编辑目标、最小授权、强制委托、同 Run/ToolCall 与精确回读均已实现，未发现遗留 finding。
- 聚焦验证：新策略 `4/4`；相邻通用策略 `11/11`、文本策略 `2/2`、Registry `21/21`、敏感参数预审计 `1/1`，合计 `39/39`，均为 `0 failed / 0 errors / 0 skipped`。首次 `--rerun-tasks` 完成 Debug 主代码与测试编译，`assembleDebug / assembleDebugAndroidTest` 和 `git diff --check` 通过。本阶段按分级验证不运行完整 JVM、Lint、Release、默认完整 instrumentation 或真实文本输入。
- Redmi 文档门禁：只向 `wsvwypiz7xwslvl7` 发送安装和 instrumentation 命令。首次误用正式证书签署的临时 Debug，覆盖明确以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失败，未卸载或清数据；只读拉取设备 `base.apk` 后确认当前安装包与默认 Debug APK 的证书 SHA-256 同为 `6c8823ff4295d7b29e8e2c58b13c864f795b082255b67c80aa8cb783155e3899`。改用匹配证书的 Debug/Test APK 后无损覆盖成功，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、首轮耗时 `2.658s`；写回本节后的最终 assets 已以相同步骤复验通过。在线 `emulator-5554 / emulator-5556` 仅出现在设备列表，没有收到定向命令。

## 2026-07-30 第 114 阶段：Workflow 设备动作答案级证据 UI

- 范围：只把第 113 阶段已持久化的 `workflow-device-action-decision-v1` 和 Room 审批终态投影为 Workflow 详情证据；不新增设备动作，不改变 `device.snapshot -> device.tap_ref` 的前台安全门禁，不开放后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service。
- 成功证据：步骤卡显示 `tap_ref`、动作前后应用、后置节点/脱敏数、截断、观察时间和规则版本，并固定声明“仅确认当前设备动作和后置观察已验证，不确认最终业务目标”“节点引用已失效”。成功事实只来自版本化 output snapshot，不从模型正文或审批状态猜造。
- 失败证据与隐私：Room 审批在 IO 边界转换为只含 `runId / toolName / outcome` 的安全 DTO，稳定区分用户拒绝、普通取消、窗口变化、浮层不可用、Accessibility 断连和 BUSY；批准后 window generation 或服务断连显示为执行验证失败。原始审批参数、snapshot/ref、节点正文和完整原因不进入 `XiaoLingUiState` 或 Compose。step output、previous outputs、Run result 和 Run error 中的动作 JSON 统一替换为固定提示。
- 批量与身份：`RoomAgentRunRepository.approvalRequests()` 对 Run ID 去重并按 `900` 个分块查询；投影层再次要求证据自身 `runId` 等于 step 的 Agent Run，跨 Run 记录不绑定。同一 Run 多次动作尝试时，早期取消和批准后的执行失败都保留；已有成功本地判定时，以成功作为步骤最终可信结果。
- 双轴复审：Standards/Spec 子代理仍因本地 `/responses` 404 无法产出，主线程以 `f300dfc` 为固定点完成复审。首版遗漏活动页面切换、目标内容变化、多个 overlay、overlay 身份漂移和“当前窗口状态不允许显示审批”等生产原因，且 `.ifEmpty` 组合会让早期取消遮蔽后续批准后的执行失败；新增回归后修复，未发现权限扩大、原始参数回流或跨 Run 绑定。
- JVM/构建：最终聚焦为 `WorkflowManagementProjectionTest 14/14`、`WorkflowDeviceActionDecisionPolicyTest 3/3`、`WorkflowDeviceObservationDecisionPolicyTest 4/4`、`WorkflowStepExecutionPolicyTest 9/9`，合计 `30/30`；`assembleDebug`、`assembleDebugAndroidTest` 与 `git diff --check` 通过。本阶段按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 定向：设备列表同时存在 Redmi 与在线模拟器，因此所有安装和 instrumentation 均显式使用 `wsvwypiz7xwslvl7`。Workflow 页面类为 `OK (4 tests)`；`approvalRequestsLoadsOnlyRequestedAgentRuns` 为 `OK (1 test)`；`workflowReplacesVerifiedTapRefWithLocalActionDecisionForNextStepAndRetry` 为 `OK (1 test)`。README/docs 首次重新打入 AndroidTest APK 后，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、耗时 `2.677s`；写回设备收尾后最终复验同样通过。测试包随后卸载，主应用恢复前台；Accessibility 保持用户原有关闭状态，`Bound services:{}`、`Crashed services:{}`，crash buffer 没有小灵 FATAL；在线 `emulator-5554` 没有收到定向命令。

## 2026-07-30 第 113 阶段：前台 Workflow `tap_ref` 首个生产切片

- 范围：只把 `device.tap_ref` 接入前台手动 Workflow，必须先有同 Run 已验证 `device.snapshot`；`open_app / back / home / type_text / swipe`、后台/定时 Workflow、恢复自动续跑、截图、坐标、视觉定位与任意 App 继续关闭。直接 `/agent` 的原会话审批卡保持不变，未改 Room Schema、精确定时或 Foreground Service。
- 生产审批：新增 `WorkflowDeviceActionApprovalGate` 与 Room Persistence Adapter。请求先以完整 Run/ToolCall/参数身份落为 `PENDING`，再调用 Accessibility overlay；只有同一请求成功落为 `APPROVED` 才把批准返回 Runtime。拒绝、取消、BUSY、服务断连、窗口变化、浮层不可用、持久化身份漂移和协程取消都 fail-closed，取消会先在不可取消 IO 区收敛 Room。
- Accessibility：正式 overlay 标题 `XiaoLingDeviceActionApproval`，类型 `TYPE_ACCESSIBILITY_OVERLAY`，flags 为 `NOT_FOCUSABLE / NOT_TOUCH_MODAL / SECURE`。Redmi 实际 frame `[0,1781]-[1080,2274]`，与 2340 高屏幕保留 66px 导航栏；系统截图/UIAutomator不暴露审批内容。活动 generation 跟随底层 `rootInActiveWindow`；只有精确基线或基线加唯一自有 overlay 时抑制自身 attach/detach，外来窗口、内容变化、滚动、身份漂移或服务断连立即拒绝。用户决定在系统事件确认 overlay 消失后才完成。
- Runtime 与净化：Workflow 工具面精确为 `device.snapshot / device.tap_ref`。Run 切换清空旧 snapshot/ref；tap 前核验 30 秒 TTL、window generation、实时 fingerprint、当前进程审批与参数，tap 后重新观察并要求 `executorVerified=true + typed PASSED`。动作结果 codec 只接受固定字段，额外字段直接拒绝；Workflow output snapshot 只写入 `workflow-device-action-decision-v1`，Debug tracer 自检原始结果不含 snapshot ID/ref。
- 双轴复审：Standards 与 Spec 子代理均因本地 `/responses` 404 无法产出，改由主线程按同一双轴完成审查。复审移除 `DeviceActionApprovalOverlayRequest` 与协调器中的原始 `arguments`，使 snapshot/ref 根本不进入 overlay 数据面；同时让 `WorkflowDeviceActionDecisionPolicy` 显式要求 Room Tool Ledger 的 `executorVerified=true`，不能只凭 typed `PASSED` 和结果 JSON 自述。新增 Redmi Room 反例固定 `executorVerified=false` 必须以 `EXECUTOR_VERIFICATION_MISSING` fail-closed，并统一修正长期文档中仍停留在第 108/112 阶段的当前状态摘要。
- TDD/构建：审批协调器、Workflow gate、安全策略、动作判定与 Registry 聚焦 `45/45`；完整 `testDebugUnitTest` 为 `784` tests、`0 failed / 0 errors / 0 skipped`。`assembleDebug`、`assembleDebugAndroidTest` 与 `git diff --check` 通过，清理后源码没有原型 API、`PROTOTYPE` 标记或窗口诊断 tag。更新后的文档 corpus 在 Redmi 为 `OK (1 test)`、耗时 `2.596s`。本阶段按分级验证没有运行 Lint、Release 或默认完整 instrumentation。
- 复审验证：受影响的 Gate、overlay、动作判定、Registry、Runtime 与输出 codec 六个 JVM 类合计 `108/108`；`assembleDebug / assembleDebugAndroidTest` 再次通过。只向 Redmi `wsvwypiz7xwslvl7` 定向运行 Room 正向投影与 `executorVerified=false` 反向拒绝，结果 `OK (2 tests)`、耗时 `0.775s`；复审后的最终文档 corpus 再次为 `OK (1 test)`。测试包随后卸载，主应用恢复前台，Accessibility 重置后为 `Bound services`、`Crashed services:{}`，crash buffer 无小灵 FATAL。
- Redmi 过程：只向 `wsvwypiz7xwslvl7` 发送定向命令。第一轮因人工核对 frame 导致审批后 snapshot 超过 30 秒，真实日志为 `device.snapshot 已过期或时间证据不完整`；第二轮审批已 `APPROVED`，但 generation 已变化，执行前以 `页面 window generation 已变化，必须重新观察` 拒绝。两次均证明安全闸口没有被旁路。随后缩短审批时间，诊断构建与删除诊断后的最终构建各成功一次。
- 最终证据：最终日志为 `workflow-device-action-e2e success=true action=tap_ref verified=true ... postText=true`。Room Run `run-7f252a9c-711b-466f-bd5b-ff2a545605b7` 为 `COMPLETED`，审批 `APPROVED`，最新 Tool Ledger 为 `device.tap_ref / success=true / executorVerified=true / verification=PASSED`。测试包已卸载；instrumentation 强停应用后系统曾把 Accessibility 标为 crashed，但 crash buffer 没有小灵 FATAL，重置同一服务组件后最终为 `Bound services`、`Crashed services:{}`。在线 `emulator-5554` 仅出现在 `adb devices -l`，没有收到安装、点击、测试或其他定向命令。

## 2026-07-30 第 112 阶段：前台 Workflow 有限设备动作安全契约

- 范围：先冻结前台 Workflow 设备动作进入生产前必须满足的安全证据，不开放任何生产动作，不修改 Registry、Room、Accessibility、Workflow Repository、Room Schema、后台设备工具、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service。
- 实现：新增纯 Kotlin `WorkflowDeviceActionSafetyPolicy`。默认动作白名单为空，未知动作名直接拒绝；显式动作只接受 `WORKFLOW + FOREGROUND`、用户步骤意图、完整 Workflow/Step/AgentRun/ToolCall 身份、同 Run 独立且已验证的 `device.snapshot`、有效 window/ref、当前进程逐动作审批和完整参数绑定。通过时签发不可变 `workflow-device-action-safety-v1` 授权快照。
- 恢复与完成边界：旧 Run、关联重试、前一动作、进程重建前审批、过期 ref、页面 generation 漂移、取消后迟到结果或身份/参数不一致都 fail-closed。完成还要求同一授权、同一 Run/ToolCall 成功结果、Executor 验证、typed `tool.verify=PASSED` 和后置 snapshot；后置观察绑定当前 Agent Run 与动作 ToolCall，动作完成晚于审批且观察不早于动作完成。Android 接收动作本身不构成完成证据。
- 双轴审查：Standards 轴发现初版没有强制 `expiresAt - capturedAt <= 30_000`，Spec 轴发现后置 snapshot 缺少 Run/ToolCall 与动作时序身份。两项均先以新增 Red 复现，再分别增加 30 秒最大 TTL 与强类型后置观察证据；审查后没有遗留 finding。
- 聚焦验证：`WorkflowDeviceActionSafetyPolicyTest` `11/11`、`WorkflowDeviceObservationDecisionPolicyTest` `4/4`、`XiaoLingToolRegistryTest` `20/20`，合计 `35/35`；命令使用 JDK 21、`--rerun-tasks --console=plain`，结果 `BUILD SUCCESSFUL in 26s`。
- 验证边界：本阶段没有执行完整 JVM、Lint、APK、Release、文档 corpus 或 Redmi instrumentation，没有安装应用或执行真实设备动作。下一阶段才从 Redmi 已验收的限定动作中选择一个最小生产切片，接入前仍保持 Workflow 动作面为空。

## 2026-07-30 第 111 阶段：Workflow 设备观察真实双 Run 与输出净化

- 范围：在第 110 阶段本地判定契约上执行真实前台多步 Workflow，证明新 Agent Run 只能消费前序白名单判定，不复用旧 Run 工具调用；同时检查 Workflow 自身持久输出是否排除原始 snapshot。不开放设备动作、后台设备工具、截图、坐标、视觉定位或任意 App，不修改 Room Schema。
- 首轮阻塞：Debug Receiver 在进程存活期间将 Profile 工具从单个 `device.snapshot` 更新为 `device.snapshot + app.current_time`，Room 已正确持久，但 `XiaoLingViewModel.initialize()` 只加载一次，所以旧运行态报“模型选择了未注册工具：app.current_time”。源码 Registry 已注册该工具，Debug Profile 没有 Skill 白名单，因此排除 Skill 收窄与设备门禁。冷启动重建 ViewModel 后页面显示双工具 Profile，未为调试旁路修改生产权限。
- Ledger 修复：真实 `device.snapshot` 结果为 `success=1 / verificationStatus=PASSED / executorVerified=NULL`。旧前台完成路径评估进程内 `VerifiedAgentContext`，将 `RESULT_READABLE` 误判为缺失 Executor 验证。现在 `RoomWorkflowRepository.requireDeviceObservationDecisions()` 成为前台、恢复和重试的共同事实源；`PASSED` 仍可生成 `LIMITED`，可空 Executor 布尔值不再误拒绝。
- 持久化净化：首个成功双 Run 的第二步 `previousOutputs[0]` 已为 169 字符白名单判定，但第一步 `outputSnapshot.text` 仍有 `6702` 字符并含 `snapshot_id`。新 TDD 首轮三条全部失败；修复后 `completeWorkflowStep()` 在完成事务中重新回查同 Run Tool Ledger，校验调用方判定，并用 `renderForPrompt()` 替换 step `result/outputSnapshot.text`。前台 Workflow 消息与后台会话文本也只发布净化结果；未验证快照在完成事务前拒绝。双轴审查又发现 `completeRun()` 单步骤兼容路径与 Run 汇总仍信任调用方正文，现已让兼容步骤同样回查 Ledger，Run result 只从净化 step 聚合。
- 聚焦验证：`assembleDebug` 与 `assembleDebugAndroidTest` 成功。仅向 Redmi `wsvwypiz7xwslvl7` 安装主包/测试包并运行 `workflowReplacesVerifiedDeviceSnapshotWithLocalDecisionForNextStepAndRetry`、`workflowBlocksNextStepWhenPersistedDeviceSnapshotIsNotVerified`、`scheduledWorkflowPersistsAndPublishesOnlyLocalDeviceDecision`、`completeRunSanitizesSingleStepDeviceObservationAndRunResult`、`completeRunAggregatesMultiStepResultFromSanitizedStepOutputs`，结果 `OK (5 tests)`、耗时 `1.21s`。第 110 阶段新增的可空 Executor 单项在本阶段前置验证也已为 `OK (1 test)`；最终项目文档 corpus 为 `OK (1 test)`。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- 真实最终证据：Workflow `workflow-run-0a2cc22f-1212-413c-8026-76576e009dce` 与两步均 `COMPLETED`。Agent Run `run-8dc7eebb-a162-464a-86f2-ed00184db905` 只有 `device.snapshot / SAFE / success=1 / PASSED / 278ms`，Agent Run `run-7f1ff488-7583-4961-942b-f6b33d6ee0a4` 只有 `app.current_time / SAFE / success=1 / PASSED / 1ms`，两者 `executorVerified=NULL`，审批 `0`。第一步 `outputSnapshot` 只有 `schema/text/requiresCurrentKnowledgeReferences/knowledgeReferences/deviceObservationDecisions`，判定只有 `ruleVersion/status/packageName/nodeCount/redactedNodeCount/truncated/capturedAt`；`result` 和第二步 `previousOutputs[0]` 均为 169 字符，`snapshot_id / nodes / ref` 均为 0 命中。当次 `LIMITED` 判定为 `com.longdev.xiaoling / 49 节点 / 6 脱敏 / 未截断`。
- UI 与设备收尾：最新 Run 详情显示“设备观察 · 已验证”、“本地判断 · 有限可复核”、`6 个节点已脱敏 · 规则 workflow-device-observation-v1`和“节点引用已过期，不可用于后续动作”。Accessibility 保持 `Bound services` 且 `Crashed services:{}`；在线模拟器只出现于 `adb devices -l` 清单，没有收到安装、测试、UI、截图或其他定向 ADB 命令。

## 2026-07-30 第 110 阶段：Workflow 设备观察本地判定

- 范围：在第 109 阶段已验证设备观察证据之上形成最小本地判定，并安全传给后续前台 Workflow 步骤；不开放设备动作、后台设备工具、截图、坐标、视觉定位或任意 App，不修改 Room Schema、精确定时或 Foreground Service。
- 实现：`workflow-device-observation-v1` 只从同 Run、`device.snapshot / success / PASSED` 且结构合法的 Ledger 生成“可复核/有限可复核”。输入和持久 output snapshot 只包含 package、节点/脱敏数、截断与采集时间。下一步准备时重新回查 Ledger并用该判定替换模型步骤正文；关联重试沿 `reusedFromStepId` 回查来源。来源缺失、未验证、畸形或持久判定漂移会在 input snapshot 落库前失败，后续 Agent Run 不启动。
- TDD 与构建：新增 policy `4/4`、Workflow snapshot/prompt `8/8`、管理页 Projection `7/7`，聚焦 JVM 合计 `19/19`；`compileDebugAndroidTestKotlin`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。双轴 Standards 首轮唯一硬 finding 是长期文档尚未同步，本节与 README/路线图/实现说明/需求同步后已修复；轻微重复适配保留在存储与 UI 边界，未发现动作权限扩大。
- Redmi 定向验证：设备列表同时存在 Redmi 与在线模拟器，因此未使用通用 `connectedDebugAndroidTest`；主包、测试包安装与 instrumentation 每条命令都显式指定 `wsvwypiz7xwslvl7`。`workflowReplacesVerifiedDeviceSnapshotWithLocalDecisionForNextStepAndRetry`、`workflowBlocksNextStepWhenPersistedDeviceSnapshotIsNotVerified` 和 `pageDisplaysVerifiedDeviceObservationAsExpiredReadOnlyEvidence` 为 `OK (3 tests)`、耗时 `3.343s`；更新后的项目文档 corpus 首次/最终均为 `OK (1 test)`、耗时 `2.662s / 2.534s`。测试包已卸载；当前 Debug 主应用冷启动 `3.540s`，版本 `0.1.13 (14)`、`MainActivity` 为 top resumed、PID `10641` 存活，清空后的 crash buffer 没有小灵 FATAL。模拟器没有收到安装、测试、截图、UI 或其他定向 ADB 命令。
- 边界：本地判断只确认采集时的包名和快照摘要；即使状态为“可复核”，也不确认节点正文、用户目标完成、当前页面仍未变化或动作授权。原始工具结果仅保留在独立 Agent Tool Ledger 中审计，不会进入 Workflow step、Run 汇总或下一步 Prompt；本阶段按分级验证没有运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 2026-07-29 第 109 阶段：Workflow 设备观察证据 UI

- 范围：前台 Workflow 消费已持久化 `device.snapshot`，在步骤详情中形成可复核的白名单证据；不开放动作、截图、坐标、视觉定位、任意 App 或后台设备工具，不修改 Room Schema、Workflow 执行语义、精确定时或 Foreground Service。
- 实现：Workflow step 以 `agentRunId` 批量读取独立 Tool Ledger，IO 加载边界立即通过安全 Projection 转成 DTO，Compose 根状态不保存原始 JSON。读取按 `900` 个 runId 分块，避免超长历史超过 SQLite bind 上限。只有 Run 身份一致、`device.snapshot / success / PASSED` 且当前 codec 顶层与逐节点结构都合法的结果进入证据；卡片只显示 package、节点/脱敏数、截断状态、采集时间、耗时和过期 ref 提示。旧 step output、previous outputs 与 Run result 中缺少 `snapshot_id`、camelCase 或再次转义的 snapshot JSON 变体也统一 fail-closed 替换。
- TDD 与构建：审查收尾后 `WorkflowManagementProjectionTest` 聚焦 JVM `7/7`、`DeviceSnapshotPolicyTest` `9/9`，覆盖白名单、跨 Run、失败/未验证/逐节点畸形/非 snapshot 拒绝，以及三处历史输出与旧形态脱敏；`compileDebugKotlin`、`assembleDebug`、`assembleDebugAndroidTest` 均通过。本阶段按快速迭代分级约束未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- Redmi 页面单项：只向 `wsvwypiz7xwslvl7` 定向安装与测试。主应用因现有正式证书与默认 Debug 证书不同，第一次覆盖明确失败为 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，未卸载、未清数据；随后使用同一正式证书签署 Debug/Test APK，ROM 的 incremental 安装以 `Incremental installation not allowed` 拒绝后自动回退 streamed install 成功。Compose 首轮 1/2 因 test tag 位于 unmerged semantics tree 失败，按框架提示修正测试后为 `OK (2 tests)`、耗时 `4.164s`，再次复验为 `OK (2 tests)`、耗时 `4.277s`。
- 真实历史证据：保留的 `stage108_snapshot` Workflow Run 通过现有 Ledger 投影为 `设备观察 · 已验证 / com.longdev.xiaoling / 38 节点 / 脱敏 2 / 未截断 / 2026-07-29 15:48:35 / 193ms`，ref 明确显示已过期。首次展开同时发现旧“输出”和 Run“结果”仍包含完整 JSON、节点正文、ref、bounds 与 actions；新增 Red 后修复，最终真实页面只保留步骤/Run 脱敏提示和证据卡，层级检查 `snapshot_id / window_title / bounds / actions` 为 0 命中。
- 文档与收尾：README 与五份长期文档重新打入 AndroidTest APK 后，Redmi 文档 corpus 为 `OK (1 test)`、耗时 `2.453s`；审查加固文本再次打包后复验为 `OK (1 test)`、耗时 `2.785s`。最终测试包已卸载，Room v33、Provider/Profile、Stage 108 Workflow 与旧 Run 均保留，设备 Agent/Accessibility 仍保持关闭；主应用为 `0.1.13 (14)`，`MainActivity` 前台 resumed、进程存活，清空后 crash buffer 为空。在线模拟器只出现在 `adb devices -l` 清单，没有收到安装、测试、截图、UI 或其他定向 ADB 命令。

## 2026-07-29 第 108 阶段：前台 Workflow 只读设备观察

- 范围：主线从等待 answerability Shadow 样本切回个人 Agent 能力。本切片只允许用户主动在前台运行的 Workflow 使用 `device.snapshot`；`device.open_app / back / home / tap_ref / type_text / swipe` 仍只允许前台直接 `/agent`，后台或定时 Workflow 拒绝全部设备工具。设备开关、Accessibility、Profile/Skill 白名单、隐私过滤、200 节点/4,000 字符、30 秒 ref 和整窗拒绝边界均未放宽。
- 实现：`XiaoLingToolRegistry` 将 snapshot 与动作工具的清单/执行门禁拆开。`MinimalAgentRuntime.resumeApprovedRun()` 和 `AgentRunUseCase.resumeApprovedRun()` 显式传递 `invocationSource`；`XiaoLingViewModel` 在 IO 调度调用 `RoomWorkflowRepository.isWorkflowAgentRun()`，从已持久化 WorkflowRun↔AgentRun 关联恢复来源，防止审批恢复后的 Workflow Run 默认为 `DIRECT` 并获得动作权限。
- 聚焦验证：双轴审查发现规划清单只检查设备开关、没有检查 Accessibility 授权/连接；修复后 `DeviceController.health()` 同时约束清单与 Executor，并增加未授权、断连和后台动作强行执行反例。Registry、Runtime、设备控制器和健康策略 JVM 合计 `88/88`，`compileDebugKotlin`、`assembleDebug`、`assembleDebugAndroidTest` 通过。Redmi 定向 `RoomWorkflowRepositoryInstrumentedTest#manualRunKeepsSingleIdempotentAgentStepAndCompletes` 为 `OK (1 test)`、耗时 `0.476s`，覆盖绑定 AgentRun 前关联为 false、绑定后为 true；更新后的项目文档 corpus 单项为 `OK (1 test)`。本阶段按快速迭代分级约束未运行完整 JVM、Lint、Release 或默认完整 instrumentation。
- 真实服务快照：只使用 Redmi `wsvwypiz7xwslvl7`。正常应用进程中临时开启 Accessibility 与设备 Agent 后，设置页真实 snapshot 包名为 `com.longdev.xiaoling`，包含 `15` 个节点，ref 有效期 `30000ms`。
- 真实 Workflow：前台手动 Workflow `stage108_snapshot` 的 Workflow Run `workflow-run-002160cf-e1cc-4039-9ca2-709550ee0462` 与 Agent Run `run-8c5b7b82-197d-4f4c-8751-ffbda0af260e` 均为 `COMPLETED`，总耗时 `18.868s`。唯一工具调用为 `device.snapshot`，结果 `SAFE / success=1 / PASSED / 193ms / 6128B`，`redacted_node_count=2`；设备动作调用 `0`，审批请求 `0`，规划、校验、执行、验证、再规划和总结六个步骤全部完成。Workflow 定义 ID 为 `workflow-a15308cf-65ba-428c-ac15-f9beb3ae4f0a`。
- 清理：临时会话已删除；当前产品没有 Workflow 删除入口，因此临时 Workflow 禁用后保留验收证据。设备 Agent 开关恢复关闭，Accessibility 恢复 `0/null`，测试包卸载。Shadow 保持默认关闭，本阶段没有调用 Judge、增加匿名记录或进入 JSON/SAF、校准和 production enforcement。
- 降级覆盖事故：验收结束时曾误安装固定 `outputs/release/xiaoling-v0.1.13.apk`。该发布包为 Room v32，而设备数据已是 v33，启动因此明确报 `A migration from 33 to 32 was required but not found`；多条 crash buffer 记录均来自重复恢复旧 Release，不是 Workflow 执行崩溃。随后使用正式证书签署的 `app/build/outputs/apk/debug/app-debug-release-signed.apk` 执行保留数据的 `adb install -r`；incremental 安装被 ROM 以 `Incremental installation not allowed` 拒绝后自动回退 streamed install 并成功。最终设备为 `0.1.13 (14)`、`MainActivity` resumed、PID `29886` 存活，清空后 crash buffer 为空。该问题不通过增加 Room 降级迁移解决，后续不得再用 v32 固定 Release 覆盖 v33 开发数据。

## 2026-07-29 第 107 阶段：第三条独立同日 Shadow 记录

- 安装与临时知识：只使用 Redmi `wsvwypiz7xwslvl7`。当前 Debug 以与设备既有应用相同的正式证书签署并覆盖安装，证书摘要前后一致，Room v33、Provider/Profile 和前两条匿名记录保留。`docs/answerability-shadow-binding.md` 导入为 `xiaoling-stage107-shadow.md`，形成 revision `1`、`8` 个 chunks、`16.3 KB`；Embedding 未建立，检索使用词法兜底。
- 预算耗尽边界：首次较宽请求连续完成 4 次 `knowledge.search`，第五次参数校验触发工具预算上限，Run 收敛为 `BUDGET_EXHAUSTED`。该 Run 没有成功答案，未进入答案保存后的 Publisher，因此一次性 Shadow 授权没有消费，开关仍为 `true`，匿名账本保持 `2` 条，attempt、usage 与全部失败分桶均未变化。
- 第三条真实记录：第二次使用已验证的 `anonymous shadow calibration validation` 查询，只执行 1 次 `knowledge.search` 并完成答案、引用保存和真实 Judge。Run 为 `COMPLETED`，授权自动关闭；新增记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7288/7274ms`，Prompt `6664B`，输入/输出/总 Tokens `1715/314/2029`，usage `1`，失败分桶全为 `0`，记录时间为北京时间 `2026-07-29 12:52:23.355`。
- 累计与时间窗口：第三条距第二条 `4 小时 38 分 33.243 秒`。三条累计 completed/bound/accept `3/3/3`、Judge 匿名桶 `1`、耗时/TTFB `24596/24561ms`、Prompt `21510B`、Tokens `5421/1155/6576`；最早到最新跨度 `5 小时 24 分 46.689 秒`。这只是第三个独立同日窗口，不能表述为长期分隔证据，也不解锁 JSON/SAF、显式授权评测集、独立阈值校准或 production enforcement。
- 夹具修正与分级验证：Room 原始毫秒证明第 103/104 阶段精确差为 `46 分钟 13.446 秒`，页面按秒显示 `46 分钟 13 秒`；此前 `46 分钟 14 秒` 属于夹具提前截断的误差。JVM 与 Compose 夹具改为真实 epoch millis，聚焦 `AnswerabilityShadowWindowEvidenceProjectionTest` JVM `3/3` 和 `assembleDebugAndroidTest` 均为 `BUILD SUCCESSFUL`，只有既存 `createComposeRule` v1 弃用 warning。最新文档重新打入 AndroidTest assets 后，Redmi 项目文档 corpus 首次/最终单项均为 `OK (1 test)`、耗时 `2.687s / 2.606s`。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 清理状态：应用 UI 删除临时会话正文和知识文档，精确删除 `/sdcard/Download/xiaoling-stage107-shadow.md`。最终 documents/chunks/messages `0/0/0`、空壳会话 `1`、Agent Run `4`（`COMPLETED` 为 `3`、`BUDGET_EXHAUSTED` 为 `1`）、Shadow rows `3`、Provider/Profile `1/1`、Shadow `false`，production enforcement 偏好不存在，测试包与临时下载文件不存在。旧 Run 均保持原终态，应用已 force-stop；没有向 Pixel_9 或其他模拟器发送定向 ADB 命令。

## 2026-07-29 第 106 阶段：Shadow 时间窗口证据投影

- TDD Red：新增 `AnswerabilityShadowWindowEvidenceProjectionTest`，使用第 103/104 阶段真实时间要求北京时间 `2026-07-29 07:27:36 -> 08:13:50` 和精确跨度 `46 分钟 13 秒`；首次运行因投影函数不存在而按预期编译失败。
- Green 实现：`projectAnswerabilityShadowWindowEvidence()` 从跨进程摘要读取最早/最新时间，按设备本地时区格式化，并以真实毫秒差投影天、小时、分钟和秒。缺失或逆序时间保守显示未知；界面固定说明该证据不自动判定为分隔窗口。
- UI 契约修复：Stage 105 已把开关语义改成“授权下一次”，但既有 Compose instrumentation 仍查找旧的“启用”content description。测试现已同步新语义，注入两条真实时间并断言范围与跨度。
- 分级验证：投影聚焦 JVM `3/3` 为 `BUILD SUCCESSFUL`，覆盖真实正向跨度、单端缺失和时间逆序；`./gradlew :app:assembleDebugAndroidTest` 成功，包含更新后的 Compose 测试。仅有既存 `createComposeRule` v1 弃用 warning；本阶段没有安装 APK、连接设备、调用 Judge、增加 Room 行，也没有运行完整 JVM、Lint、Redmi instrumentation 或 Release。
- 证据边界：第 106 阶段结束时 Room v33 为 `2` 条 `COMPLETED / BOUND / ACCEPT`，时间跨度 `46 分钟 13 秒`；投影不内置分隔阈值，不支持 calibration/validation、JSON/SAF、显式授权评测集或 production enforcement。第 107 阶段随后新增第三条同日记录，未改变该边界。

## 2026-07-29 第 105 阶段：单次显式 Shadow 采样窗口

- TDD Red：在 `AgentAnswerabilityShadowPublisherTest` 增加观测开始前消费授权、候选缺失不消费、保存失败不消费和提前撤销不消费的断言；首次运行因 `publish()` 不存在消费 seam 而按预期编译失败。
- Green 与审查修复：首版 Publisher 在候选存在、答案保存成功且调用前仍开启后先关闭开关再进入协调器。Standards / Spec 双轴审查共同发现“检查后关闭”不是原子操作，两个并发答案可能复用同一授权；第二轮 Red 增加 20 路并发消费者，随后由 `AnswerabilityShadowObservationWindowGate` 在同一临界区检查并关闭，Publisher 只在必填的 `tryConsumeObservationWindow()` 返回成功时继续，不保留默认放行路径。
- 设置页：开关语义调整为“授权下一次答案可回答性 Shadow”，说明每次显式开启最多启动一轮观测并在开始时自动关闭；候选缺失、答案保存失败和提前撤销仍不消费窗口。
- 分级验证：Publisher `10/10` 与原子门禁 20 路并发 `1/1` 合计 `11/11`，`BUILD SUCCESSFUL`，同时完成 Debug 主源码编译。本阶段未新增 Room 行或真实 Judge 请求，未执行完整 JVM、Lint、APK、Redmi instrumentation 或 Release。
- 证据边界：Room v33 匿名账本仍为第 103/104 阶段形成的 `2` 条 `COMPLETED / BOUND / ACCEPT`；两条相隔约 `46` 分钟，仍不作为长期分隔、calibration/validation 或 production enforcement 依据。JSON/SAF、显式授权评测集、独立阈值校准继续后置。

## 2026-07-29 第 104 阶段：第二条真实 Shadow 样本与冷启动摘要修复

- 第二条真实样本：完整清理第 103 阶段临时数据并重启进程后，把 `docs/answerability-shadow-binding.md` 导入为 `xiaoling-stage104-shadow.md`，形成 revision `1`、`5` 个 chunks、`11.4 KB`。Embedding 不可用，查询 `anonymous shadow calibration validation` 通过词法兜底命中 `1` 个 chunk；真实请求 `/agent Use knowledge.search with query anonymous shadow calibration validation and explain why anonymous Shadow cannot be used for calibration or validation.` 完成检索、答案/引用保存和 Judge。
- 停进程 Room 证据：新增记录为 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `7645/7632ms`，Prompt `3967B`，输入/输出/总 Tokens `905/372/1277`，usage `1`，所有失败分桶为 `0`，记录时间 `2026-07-29 08:13:50`（北京时间）。第 103+104 阶段累计为 rows `2`、Judge identity buckets `1`、completed/bound/accept `2/2/2`、attempts `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`、usage `2`，所有失败分桶仍为 `0`。
- 证据边界：第二条距首条约 `46` 分钟，只能视为完整清理和进程重启后的独立短间隔窗口，不能夸大为长期分隔样本。两条匿名记录仍不得用于 calibration/validation；本阶段不进入 JSON codec、UI/SAF、显式授权评测集、独立阈值校准或 production enforcement，并停止在当前窗口继续制造样本。
- 冷启动回归与修复：数据库已有 `2` 条记录时，设置页“跨进程匿名摘要”曾稳定显示全零。根因是异步摘要读取完成后，Profile/会话初始化整表重建 `uiState` 时遗漏 `answerabilityShadowPersistentSummary`。新增纯状态合并函数和聚焦 JVM 回归，保留 Shadow 开关、进程内摘要与跨进程摘要；正式证书签署的源码 Debug 无损覆盖 Redmi 后，真实冷启动设置页显示观测 `2`、Judge 身份 `1`、完成/接受 `2/2`、Judge 尝试 `2`、累计耗时 `17308ms`、Tokens `4547`。
- 清理状态：通过应用 UI 删除临时 Agent 会话正文和知识文档，精确删除 `/sdcard/Download/xiaoling-stage104-shadow.md`。最终停进程快照为 documents/chunks `0/0`、messages `0`、自动保留空壳会话 `1`、Agent Run `2` 且均为 `COMPLETED`、shadow rows `2`、Provider/Profile `1/1`、Shadow `false`、production enforcement 偏好不存在；测试包和临时下载文件不存在。旧 Run 保持不变。
- 分级门禁：`XiaoLingInitializationStateTest` 聚焦 JVM、`assembleDebug` 和 `assembleDebugAndroidTest` 均通过；没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release。两个 APK 使用正式证书签署并只覆盖 Redmi；当前文档 corpus 前两轮均为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充本节设备收尾并重新打包后的最终复验同样通过。最终测试包不存在，源码 Debug 冷启动 `3385ms`，`MainActivity` resumed、PID `19521` 存活，清空后 crash buffer 为空。Pixel_9 和其他模拟器未参与。

## 2026-07-29 第 103 阶段：Room v33 首个间隔真实 Shadow 样本

- 分级构建与配置恢复：只执行 `assembleDebug` 和 `assembleDebugAndroidTest`，分别在 `11s / 7s` 内成功；没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release。Debug APK 为 `23,685,840` 字节，SHA-256 `f0dc66a6300553511771aeb395fbd07d0b57e97f709c1cea566b78130bb89e2f`。Debug/Test APK 使用正式证书重新签名并在 Redmi `wsvwypiz7xwslvl7` 覆盖安装，未使用 Pixel_9 或其他模拟器。
- Provider 定向验证：只运行 `ProviderEmbeddingCompatibilityInstrumentedTest`，结果 `OK (1 test)`；Provider 配置加密保存为 `redmi-provider-compatibility`，Embedding 因该 Provider 没有 Embedding 模型按假设跳过。随后从真实 Agent Profile UI 保存默认 Agent，绑定 `gpt-5.5`，既有 `16` 个工具与 `7` 个 Skills 保持不变。
- 真实样本：本窗口距第 101 阶段记录时间约 `69` 小时，且 v33 采样前匿名账本为 `0`。当前 README 导入为 `xiaoling-stage103-shadow.md`，形成 revision `1`、`19` 个 chunks；Embedding 不可用时查询 `Agent Run retryOfRunId` 由词法兜底命中 `5` 个 chunks。显式开启 Shadow 后，前台直接 `/agent` 完成 `knowledge.search`、答案和引用保存，并触发一次真实 Judge。
- 停进程 Room 证据：Schema 为 `33`，`knowledge_answerability_shadow_observations` 恰好 `1` 条，Judge 匿名身份桶 `1`；状态 `COMPLETED / BOUND / ACCEPT`，attempt `1`，耗时/TTFB `9663/9655ms`，Prompt `10879B`，输入/输出/总 Tokens `2801/469/3270`，usage samples `1`。unknown、reject、undecided、binding unknown 和全部失败分桶均为 `0`；记录时间 `2026-07-29 07:27:36`（北京时间）。
- 文档与最终状态：通过应用 UI 删除临时知识文档和 Agent 会话，知识文档/chunks 均恢复为 `0`；精确删除 `/sdcard/Download/xiaoling-stage103-shadow.md`，卸载 `com.longdev.xiaoling.test`。同步后的 AndroidTest 文档 assets 以正式证书签署，只在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首次结果 `OK (1 test)`、耗时 `1.988s`；安装器的 incremental 尝试被设备拒绝后自动回退 streamed install 并成功，补充间隔证据后的最终文本复验同样通过。再次停进程快照仍为 `documents=0 / chunks=0 / shadow=1 / completed=1 / accepted=1 / failures=0`，Shadow 偏好为 `false`，production enforcement 偏好不存在，测试包已卸载，Provider/Profile 仍绑定 `gpt-5.5`。最终冷启动 `3441ms`，`MainActivity` resumed，crash buffer 为空。
- 结论：截至第 103 阶段，这是 Room v33 新匿名账本的第一条间隔真实样本，不与第 97 至 101 项人工合计混算。第 104 阶段随后已增加第二条短间隔记录；当前证据仍不足以进入 JSON/SAF、独立阈值校准或生产拒绝，继续等待真正分隔的后续窗口。

## 2026-07-29 第 102 阶段：answerability 离线评测导出契约

- 冻结版本化 `KnowledgeAnswerabilityExportEnvelope` sealed 契约，匿名 Shadow 与显式授权内容分别使用不能混装的 envelope。匿名 envelope 不提供原始 Judge 或 dataset identity，只允许 v33 不可逆 fingerprint、状态/绑定/决策/失败枚举、失败分桶和保持 `null` 的未知成本，`eligibleForCalibrationOrValidation()` 固定为 false；显式内容 envelope 才携带授权、数据集身份、正文、引用、label 与 assessment。
- 该阶段未增加 production enforcement，也未接入 Workflow/后台、检索排序、答案路径、JSON codec 或 UI/SAF 出口。完整门禁为 JVM `736/736`、Lint `0 error / 51 warnings`、Debug/AndroidTest/Release APK；仅 Redmi 完整 instrumentation XML 为 `248` 条（`236 passed / 12 skipped / 0 failed`），runner 打印 `260 tests`，文档 corpus gate `1/1` 通过。

## 2026-07-28 知识质量工程：answerability Shadow 匿名跨进程账本

- TDD：Publisher 聚焦测试先要求生产请求使用 `OPTIONAL`，Coordinator 聚焦测试先要求持久记录携带聚合 telemetry；首次编译因 `KnowledgeAnswerabilityShadowObservationRecord.telemetry` 不存在而失败，最小实现后两个测试类转绿。随后 Redmi 单项先证明“最终异常无 attempt telemetry”在持久失败分布中得到 `null`，补齐 fallback 分类后同一用例转绿。
- Room/隐私聚焦：v32→v33 迁移创建空 `knowledge_answerability_shadow_observations` 表；两条观测加一次重复幂等写入在数据库关闭重开后仍为 `2` 条，attempt 为 `3`，已知耗时/Tokens 正确累计，未知数值保持 `null`。PRAGMA 与全行值检查确认新表不含消息/Run ID、Provider/模型、问题/答案、URL/密钥列或值；原始候选正文冒充指纹会被 Store 拒绝。设置页 Compose 同时显示跨进程与当前进程摘要。新增聚焦组合在 Redmi 为 `OK (4 tests)`，补充分布用例 red→green 后单项 `OK (1 test)`。
- 审查修复：Standards/Spec 双轴审查分别得到 `1 / 0` 个发现。Standards 指出无盐 Judge SHA-256 可按低熵公开配置枚举，已改为 Android Keystore 不可导出安装级密钥驱动的 HMAC-SHA-256；Spec 没有功能偏差，但指出 2,000 条裁剪缺少第 2,001 条直接测试。Redmi Store 最终 `OK (4 tests)`，同时证明公开 SHA-256 不等于落库 HMAC、数据库重开后同一身份桶稳定，以及第 2,001 条写入后只保留最新 2,000 条。
- 完整本地门禁：`./gradlew --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease lintVitalRelease` 为 `BUILD SUCCESSFUL`，`141/141` tasks，耗时 `2m 38s`。JUnit XML 为 JVM `734/734`、0 失败/错误/跳过；Lint 为 `0 error / 51 warnings`。Debug、AndroidTest 和 R8 Release APK 均成功。
- Redmi 完整门禁：只向 `wsvwypiz7xwslvl7` 发送目标化 ADB 命令。首轮默认完整在设备没有保持唤醒时出现 `2` 个前台生命周期失败：分享 Activity 重建停在 `STOPPED`，设置页测试没有 Compose hierarchy；同一轮其余用例无业务断言失败。读取设备原值 `stay_on_while_plugged_in=0` 后临时设为 `15`，两个失败用例分别 `OK (1 test)`（`16s / 15s`），排除产品回归。审查修复后的最终默认完整 JUnit XML 为 `248` 条（`236 passed / 12 skipped / 0 failed`）；12 条显式真实 Provider 用例因没有 runner 参数按预期跳过，runner 最终打印 `Finished 260 tests`，Gradle `BUILD SUCCESSFUL in 1m 51s`。未使用 Pixel_9 或其他模拟器。
- 产物：最终 Debug / Release APK 分别为 `23,452,761 / 3,220,018` 字节，SHA-256 分别为 `f186eecb97d84251e241e4e9f97d2d68a2c8b7ca2a70060f30cab29f9cd5a397 / fd840fca412fdcf0b23aa5f2b43c9b90fd0c714881b4fe6fe294d8e1acb1da16`；AndroidTest APK 构建成功并在每次文档写回后重新打包，不把随语料变化的中间哈希记作稳定发布证据。Release 通过 zipalign、APK Signature Scheme v2、正式证书 SHA-256 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9` 和单签名者校验。
- 文档语料门禁：写回完整本地、Redmi 与产物证据后重新打包 AndroidTest APK，仅在 Redmi 执行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首次为 `OK (1 test)`、用例耗时 `2.505s`；把该结果写回六份长期文档并再次重新打包后的最终复验同样通过。
- 设备收尾：两个 `adb uninstall` 在包已由 Gradle 清理/替换的设备状态下返回 `DELETE_FAILED_INTERNAL_ERROR`，因此没有把命令返回值误记为成功，而是继续用 `pm path` 核对。固定正式 `outputs/release/xiaoling-v0.1.13.apk` 安装成功并冷启动 `554ms`；设备报告 `0.1.13 (14)`、`MainActivity` resumed、PID `11988` 存活、测试包路径不存在、`stay_on_while_plugged_in` 已从临时 `15` 还原为原值 `0`，crash 与 AndroidRuntime 缓冲区均无小灵异常。最终 corpus gate 后再次恢复同一固定产物并复核上述状态。
- 边界：第 97–101 阶段“不持久化”和人工样本合计保留为历史事实，新 v33 表从空账本开始，只记录本切片上线后的新观测。notice、答案、引用和 enforcement 不持久化或改写；Workflow/后台、ANN、自动索引重建、相关性生产拒绝与 answerability enforcement 继续后置。

## 2026-07-28 通用执行恢复矩阵：成功 ToolResult 缺 typed 验证结论闭环审计

- 审计边界：逐项复核成功 `tool.result` 落库后、typed `tool.verify` 落库前的全部持久化窗口。结果后预算缺失继续为 `EXECUTION_BUDGET_INVALID`；预算完整时只有既有严格定义、已提交幂等回执和只读回查能力同时成立才进入 `COMMITTED_TOOL_VERIFICATION`；全部 typed 验证为 `PASSED` 才进入 `VERIFIED_TOOL_COMPLETION`。`ToolResult.verified=true` 不替代 typed 验证状态。
- 实现变化：`AgentRunResumePolicy` 现在先核工具定义，再核 `COMMITTED + IDEMPOTENT_BY_KEY` 提交证据，最后查询只读恢复验证支持。定义缺失固定为 `TOOL_DEFINITION_UNAVAILABLE`，回执缺失/损坏固定为 `COMMITTED_EFFECT_EVIDENCE_INVALID`，证据完整但能力未开放保持 `COMMITTED_VERIFICATION_UNAVAILABLE`；前两类不会调用 support 回调。
- 安全结论：本轮不新增 resume kind、恢复载荷、Repository 写路径、Room Schema 或工具白名单，不补造 `PASSED / FAILED`，不重放 Executor，不调用旧 LLM，也不继续 Workflow。没有唯一持久化结论的形状继续关联新 Run 或 fail-closed。
- TDD 与本地门禁：两条新增策略用例分别先红后绿，完整策略测试类通过。强制 Gradle `141/141` tasks、耗时 `4m 15s`；JVM `734/734`、0 失败/错误/跳过；Lint `0 error / 52 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `954f71d5a90a6f2b63160490eab45ea67486b92f3fe8275ca7cb15498a4de6b5 / 4ecb44ae0a189cd956b9e4f12d5827d5d2477be981ea6ed371c71a0cf6ab3fae`；Release 通过 zipalign、v2 正式证书和单签名者校验。
- Redmi 完整门禁：只向真机 `wsvwypiz7xwslvl7` 发送目标化 ADB 命令；在线模拟器只出现在设备清单。以同一正式证书签署本轮 Debug/Test APK 后无损覆盖，默认 `AndroidJUnitRunner` 为 `OK (243 tests)`、耗时 `95.348s`，0 失败；测试前 `stay_on_while_plugged_in=0`，期间临时保持唤醒。
- 文档与设备收尾：最终 README/docs 已重新打包进 AndroidTest assets 并在同一 Redmi 通过 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`。随后卸载测试包、覆盖固定 `outputs/release/xiaoling-v0.1.13.apk`；设备报告 `0.1.13 (14)`、`MainActivity` resumed、主进程存活、测试包不存在、`stay_on_while_plugged_in=0`，crash buffer 无小灵相关 FATAL。

## 2026-07-28 通用执行恢复矩阵：持久化失败工具验证原子失败终态结算

- 实现边界：`ToolVerificationStatus.FAILED` 与稳定 `reason` 进入 typed Event/Tool Ledger；Runtime 在失败验证后、正常 catch 前提供持久化故障窗口。恢复只接受 `VERIFYING` Run、完整 v20 Ledger、成功 ToolResult、完整 `PASSED` 前缀、唯一链尾 `FAILED` 验证、结果后的 `Available` 预算、最后一个 `RUNNING TOOL_VERIFY` Step、完整 typed Step/Event 身份、无待审批和无尾随事件。
- 原子与安全语义：`closeInterruptedRuns()` 在一个 Room transaction 内把链尾验证 Step 与原 Run 结算为 `FAILED`，写入 typed `run.recovered(PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT)`、`run.failed` 与 `run.status`。该路径不重复 Executor、验证器或 LLM，不追加第二条验证事实、不生成成功总结、不继续 Workflow；成功结果尚无验证结论、Legacy/event-only、预算/原因缺失和身份/尾部漂移继续 fail-closed。
- 双轴审查：Standards 发现 Runtime 捕获 `Throwable` 过宽，已收紧为 `Exception` 并继续单独传播取消与进程终止模拟；Spec 发现专用结算会接受完全缺少预算的 Legacy Run，已要求预算证据必须为 `Available`，并新增无预算、缺少结果后预算两个反例。聚焦策略/Codec/Runtime JVM 强制重跑为 `138/138`；Redmi 并发、事务回滚与 Runtime 故障窗口联合为 `OK (3 tests)`、耗时 `1.335s`。
- 本地完整门禁：强制 Gradle `141/141` tasks、耗时 `3m 35s`；JVM `732/732`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug/Release APK 为 `23,436,377 / 3,203,634` 字节，SHA-256 为 `1d39a89b3bd183253a1e217f3d32f9727cfa957bdcc6b2f884915c6251455fde / ffae97ee1406b667d93c7c9b436bafb50a73f8284d861595380c16415714fb36`；Release 通过 zipalign、v2 正式证书和单签名者校验，证书 SHA-256 保持 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9`。
- Redmi 完整门禁：只向 `wsvwypiz7xwslvl7` 发送目标化设备命令；在线模拟器只出现在设备清单。设备初始熄屏锁屏，唤醒并由系统成功关闭锁屏后临时启用插电常亮；默认完整 `AndroidJUnitRunner` 为 `OK (243 tests)`、耗时 `96.162s`，0 失败；结束后 `stay_on_while_plugged_in` 已恢复原值 `0`。
- 文档与设备收尾：本节第一轮写入后的 AndroidTest 文档 assets 已在 Redmi 通过 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`、耗时 `2.907s`；中间复验同为 `OK (1 test)`、耗时 `2.471s`，最终文本 gate 也已通过。中间已卸载临时 Debug/Test 包并重新覆盖固定 `outputs/release/xiaoling-v0.1.13.apk`；SHA-256 与发布基线一致，冷启动 `602ms`，设备报告 `0.1.13 (14)`、`MainActivity` resumed、PID 存活、测试包不存在、`stay_on_while_plugged_in=0` 且 crash buffer 无小灵相关 FATAL。最终 corpus 后再次用同一固定 Release 覆盖并复核上述状态。

## 2026-07-28 通用执行恢复矩阵：持久化失败 ToolResult 原子失败终态结算

- 实现边界：新增 `AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT` 与 `AgentPersistedToolFailureRecovery`。只接受 v20 完整非空 Tool Ledger、`EXECUTING` Run、完整成功且 `PASSED` 的前序、唯一失败链尾 ToolResult、非空错误、结果后恰好一份完整预算、与账本一一对应的 Step，以及作为最后 Step 的 `RUNNING TOOL_EXECUTE`；不得存在待审批、验证事实、额外 Step、业务尾部或既有 Run 终态字段。
- 原子与安全语义：`closeInterruptedRuns()` 在既有单个 Room transaction 内把链尾执行 Step 和原 Run 结算为 `FAILED`，写入 typed `run.recovered(PERSISTED_TOOL_FAILURE_SETTLEMENT)`、`run.failed` 与 `run.status`。该路径不调用 Executor、验证器或 LLM，不追加 ToolResult/`tool.verify`，不生成成功上下文，也不继续 Workflow；链尾缺少 ToolResult 仍为 `COMMIT_UNKNOWN`，成功结果待验证、event-only、预算缺失和身份/步骤/尾部漂移继续 fail-closed。
- 已完成定向验证：策略/Codec JVM 聚焦通过；Redmi 原子失败结算、Runtime 故障窗口 Executor 只执行一次及两项联合复验分别为 `OK (1 test) / OK (1 test) / OK (2 tests)`；SQLite trigger 强制 `run.failed` 插入失败的事务回滚为 `OK (1 test)`。并发测试证明两个 Repository 同时结算只得到 `[0, 1]`，重复进入返回 `0`；回滚后 Run/Step/marker 均保持原状态，移除 trigger 后可正常结算。
- 双轴审查与本地门禁：Standards 无硬违规；Spec 审查发现策略未核对 Step sequence 与 typed 创建/完成事件身份，修复并补 3 条漂移 JVM 后复验通过，同时删去 Repository 不消费的失败恢复载荷字段。强制 Gradle `141/141` tasks、耗时 `3m 1s`；JVM `726/726`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `75a62310b023d090eebb89b702f1276fba86b015bbec5a865c660620388a4b14 / 74f546b4f8c77f497ebd5eb5058e4ed850464bdfbbe99aeed14cd8283a655e9a`；Release 为 `0.1.13 (14)`，通过 zipalign、v2 正式证书和单签名者校验。
- Redmi 完整门禁：只向 `wsvwypiz7xwslvl7` 发送设备命令。设备初始为安全锁屏，未输入凭据，仅唤醒、滑动并请求系统关闭锁屏；原 `stay_on_while_plugged_in=0`，测试期间临时设为 `15`。覆盖安装本轮 Debug/Test APK 后冷启动 `3523ms`；默认完整 `AndroidJUnitRunner` 为 `OK (240 tests)`、测试耗时 `93.258s`、墙钟 `95.73s`，0 失败。在线 `emulator-5554` 只出现在设备清单中，未收到目标化 ADB 命令。
- 文档与设备收尾：本轮文档首次重新打包后的 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、耗时 `2.644s`；写回完整门禁与设备收尾后的复验同为 `OK (1 test)`、耗时 `2.553s`。中间收尾已卸载测试包和临时 Debug 主包，安装固定发布产物 `outputs/release/xiaoling-v0.1.13.apk`（`3,170,866` 字节，SHA-256 `b6726cd080d0bd604726b5d77259311e855d2403110053fe41d0c851bd328fe8`），并把 `stay_on_while_plugged_in` 从临时值 `15` 还原为 `0`。正式版冷启动 `499ms`，设备报告 `0.1.13 (14)`、`MainActivity` resumed、PID `9753` 存活，清空后 crash buffer 无小灵相关 FATAL。

## 2026-07-28 通用执行恢复矩阵：已提交与已验证控制面幂等收尾

- 实现边界：`StepCreated / StepStatus` 使用 typed metadata 绑定 Step 身份、sequence、type 和状态变化；恢复 marker 使用 `resumeKind + recoveryBoundaryKey + from/to status + reason` 绑定唯一持久化边界。Repository 完整扫描边界后的 marker，重复、损坏、字段半缺和先合法后冲突全部拒绝；恢复写入后重新读取 Room 并重新评估。
- 原子与幂等：committed 状态 CAS、`run.status` 和 marker 同事务提交；`closeInterruptedRuns()` 在单个 Room transaction 内收敛活动 Step、Approval、Recovery 与 Run 终态。全部已验证后的总结 Step/Event 在 Room 内 get-or-create，双协调器并发恢复只生成一份总结事实。允许的控制面尾部限定为尚未创建总结、`RUNNING` 总结在 typed 总结事件前后，以及总结 Step/Event 已完成但 Run 未终态；`COMPLETED recovery.summarize` 缺事件或总结后出现业务事件一律 fail-closed。
- 安全边界：该路径只消费已持久化 ToolResult、`PASSED` Verification、Profile 与预算证据，使用本地可信总结完成原 Run；不调用旧 LLM、Executor，不重放工具或验证事件，也不继续 Workflow 后续步骤。Room 保持 v32，设备工具仍不进入 Workflow/后台。
- TDD 与审查：恢复聚焦 JVM `123/123`，覆盖 marker 唯一性/漂移、typed Step 错配、状态 CAS、事务回滚、三个总结持久化窗口、已完成总结缺事件、尾随业务事件、重复与双协程并发恢复。完整 JVM 为 `717/717`。
- 本地完整门禁：强制 Gradle `141/141` tasks、耗时 `3m 14s`；JVM `717/717`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug/Release APK 为 `23,419,993 / 3,203,634` 字节，SHA-256 为 `09c360e3a8429e72dd82bf32b21f398c6ae77fa7eb8d3e0dde4c979d223dc6ef / 5878510423499f3de1b1764376b24573abcc04c3d9440b94a97f000e48a14da8`；Release 通过 zipalign、v2 正式证书和单签名者校验，证书 SHA-256 仍为 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9`。
- Redmi 定向门禁：只使用 `wsvwypiz7xwslvl7`，Room 恢复/事务/并发组合为 `OK (36 tests)`、耗时 `8.434s`；未向 Pixel_9 或其他模拟器发送 ADB 命令。默认完整 runner 首轮因设备进入 `mWakefulness=Asleep / screenState=OFF / Keyguard showing=true` 产生 `59` 条前台失败，其中第一条 Activity 停在 `STOPPED`，其余均为 `No compose hierarchies found`。解锁后的第一条失败单项为 `OK (1 test)`、耗时 `3.71s`；临时保持唤醒后的默认完整复验为 `OK (237 tests)`、耗时 `93.062s`，因此首轮失败属于测试前台条件，不是产品逻辑回归。
- 文档与设备收尾：当前文本第一次重新打包前的 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、耗时 `2.447s`；写回本节并重新构建、签名、覆盖后的最终语料复验同为 `OK (1 test)`。验收后恢复正式 `outputs/release/xiaoling-v0.1.13.apk`，卸载测试包并把 `stay_on_while_plugged_in` 从临时值 `7` 还原为原值 `0`；设备报告 `0.1.13 (14)`、`MainActivity` resumed、主进程存活，清空后 crash buffer 没有小灵相关 FATAL。

## 2026-07-28 通用执行恢复矩阵：尚未提交受控关联重试

- 实现边界：`AgentRunRetryCoordinator` 在请求和确认时分别读取 Room 最新 Detail，对 `NOT_COMMITTED_REPLAY_ELIGIBLE` 强制显示专用确认。收敛后资格重核要求旧 Run 为 `CANCELLED`，恢复链恰好是 typed `run.recovered` 后跟无 metadata 的 `run.status=CANCELLED`，并核对原 `EXECUTING` 状态、处置码、重试证据指纹、来源 Profile、Tool Ledger 与当前 Registry。普通 `NOT_COMMITTED` 仍保持既有直接重试。
- 执行边界：确认后使用来源 Profile/Provider preflight；UseCase 在创建新 Run 前第三次读取 Room 并以生产 Registry 比较完整资格，Runtime 再次匹配恢复契约。新 Run 带 `retryOfRunId`，生成新 ToolCall ID，写入来源 Run、来源 ToolCall、新 ToolCall 与定义指纹；不调用 LLM planning，重新创建工具审批，批准后只执行一次并总结。旧 Run、旧 Tool Ledger、旧审批、旧协程与旧 Executor 不变，Workflow/后台没有接线，Room 保持 v32。
- TDD 与审查：聚焦策略、协调器和 Runtime 测试覆盖收敛事件缺失/状态错误、恢复后业务事件、证据/定义/处置码漂移、两次 Room 读取、来源 Profile、新 ToolCall、新审批、零规划与单次执行。双轴审查发现真实 Room 的尾随状态事件会使协调器误取绝对最后事件，以及测试夹具对尾事件 metadata 强转；修复后抽出共享 typed Recovery helper，并加强 Room Codec 审计断言。
- 本地完整门禁：强制重跑 Gradle `141/141` tasks，耗时 `2m 39s`；JVM `707/707`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug/Release APK 为 `23,403,609 / 3,187,250` 字节，SHA-256 为 `f8595e8671da28b59b87fbe85b2732d481263f39c1df3b60d17e1df6276764e0 / 7593288da547e95782da1b45d7a7e660dbbcab6d8ffe77102dcf8022636c6a02`；Release 为 `0.1.13 (14)`，zipalign、v2 正式证书和单签名者通过。
- Redmi 已验证：只向 `wsvwypiz7xwslvl7` 发送设备命令，使用同一正式证书签署当前 Debug/Test APK 后无损覆盖。`controlledReplayCreatesFreshApprovedLedgerAndLeavesClosedSourceRunUnchanged` 为 `OK (1 test)`、耗时 `1.573s`，证明新 Run、ToolCall、审批、单次结果和关联 metadata 从 Room 读取完整，来源 Detail 前后相等。
- Redmi 完整验收：设备接入时处于安全锁屏；仅向 `wsvwypiz7xwslvl7` 发送唤醒、滑动和测试命令，并根据设备实际 `AC powered=true` 临时启用全插电保持唤醒。`controlledReplayExplainsLinkedRunBoundaryAndFreshToolApproval` 为 `OK (1 test)`、耗时 `2.306s`；默认完整 `AndroidJUnitRunner` 为 `OK (235 tests)`、耗时 `92.954s`，受控重放 UI 同时包含在全量回归中。当前报告第一次重新打包后的 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`、耗时 `2.405s`，写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`、耗时 `2.546s`。随后已无损覆盖当前正式签名 Release，卸载测试包并关闭保持唤醒；冷启动 `532ms`，设备报告 `0.1.13 (14)`、`MainActivity` resumed、PID `23208` 存活，清空后 crash buffer 无小灵相关 FATAL。

## 2026-07-27 通用执行恢复矩阵：尚未提交安全重放资格

- 实现边界：新增默认 `DENY` 的 `ToolNotCommittedReplayPolicy`、版本化 `ToolDefinitionRecoverySnapshot/Contract` 和 `AgentNotCommittedReplayQualificationPolicy`。只有 `IDEMPOTENT_BY_KEY + CONTROLLED_SAME_CALL + REQUIRE_CONFIRMATION` 的工具才可 opt-in；当前仅 `notes.create`、`memory.remember`。Runtime 在 proposed/validated 事件冻结同一恢复契约，审批 requested/decided 冻结请求时的定义指纹；未知策略、旧事件缺快照和当前定义漂移均 fail-closed。
- 资格边界：Run 必须为 `EXECUTING`，原 Profile 白名单、独立 Tool Ledger 和当前定义一致；链尾已 validated 且没有 ToolResult/`TOOL_EXECUTE`，前序调用全部成功验证；唯一审批已批准，requested 原状态为 `PENDING`，requested/decided 的名称、风险、参数与指纹一致，事件严格按 validated→requested→decided，审批 Step 完成后没有新步骤。出现任一漂移或执行步骤时不签发资格。
- 安全语义：资格只以 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE` 写入 `run.recovered`，用于未来有用户控制的关联新 Run 入口。启动收敛仍把旧 Run 和活动 Step 置为 `CANCELLED`；没有重放工具、恢复旧模型协程/Executor、继续 Workflow、伪造 ToolResult 或原地续跑。Room 保持 v32。
- TDD 与审查：新增 11 条 JVM，覆盖正例、当前定义/审批指纹/参数漂移、requested 非 `PENDING`、审批事件倒序、历史契约缺失、默认 `DENY`、执行步骤仍为 `COMMIT_UNKNOWN`、Codec round-trip 和未知策略 fail-closed；既有 Runtime 用例增加 proposed/validated 同契约断言。双轴审查发现并修复 decided 指纹、requested 状态和事件顺序三个缺口；代码业务注释与默认拒绝约束已补齐。
- 本地完整门禁：强制重跑 Gradle `141/141` tasks，耗时 `3m 19s`；JVM `694/694`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug APK 为 `23,387,225` 字节、SHA-256 `9b298babd168842031ad5221b2b1c488d5bc7a2b2ee046efdad101ad9f468c97`；Release APK 为 `3,187,250` 字节、SHA-256 `c861055daed1ff8cf3264439c1795ed4085fe17b2220fc1c405e67d963e1cbbe`，版本 `0.1.13 (14)`，zipalign、v2 正式证书和单签名者通过。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。磁盘 Room 完全关闭重开的两个资格/收敛用例合并执行为 `OK (2 tests)`、`0.783s`。首轮默认完整因设备熄屏锁定使 Activity 停在 `STOPPED`，触发用例在解锁后单独复验为 `OK (1 test)`；临时启用 USB 保持唤醒后重跑默认完整为 `OK (233 tests)`、`90.924s`，随后执行 `svc power stayon false` 恢复设备设置。该首轮失败属于测试前台条件，不是产品逻辑失败；没有向任何模拟器发送 ADB 命令。
- 文档门禁：README 与长期 `docs/` 同步后重新打包 AndroidTest assets；只在 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，首次结果为 `OK (1 test)`、耗时 `2.171s`。写回该结果后再次重建，最终复验同为 `OK (1 test)`，确保提交文档与 APK assets 一致。

## 2026-07-27 通用执行恢复矩阵：提交状态未知

- 实现边界：新增 `AgentRunRecoveryEvidenceAssessment.CommitUnknown` 和 `AgentRunRestartDispositionCode.COMMIT_UNKNOWN`。独立 Tool Ledger 只有在链尾 ToolCall 已 validated、对应 `TOOL_EXECUTE` 步骤持久化且 ToolResult 缺失时，才返回 `RESTART_REQUIRED` 并冻结“无法证明副作用未发生”的证据边界。v19 及更早 typed event 只有在稳定 ToolCall ID、唯一链尾 validated 调用与执行步骤同时成立时进入同一分类。
- 相邻边界：`tool.call.validated` 在审批和 Executor 之前落库，不能单独证明工具已启动。proposed-only、validated-only 但执行步骤尚未落库，以及调用/步骤数量不一致统一保留 `RECOVERY_EVIDENCE_INVALID`；启动收敛的重试证据在这些窗口保持 `NOT_COMMITTED`。真正执行步骤缺结果时，重试证据与恢复处置均为 `COMMIT_UNKNOWN`，同一 `run.recovered` 不再出现相互冲突的分类。
- 安全语义：所有不能原地恢复的旧 Run 与活动 Step 仍收敛为 `CANCELLED`，Tool Ledger 和 typed event 原样保留；策略不恢复旧模型协程、不调用旧 Executor、不继续旧 Workflow、不伪造 ToolResult。后续重试仍经过确认并创建带 `retryOfRunId` 的关联新 Run。
- TDD 与审查：新增 5 条 JVM，覆盖 ledger 缺结果、proposed-only、validated-only 无执行步骤、legacy fallback 和重试证据一致性；新增 2 条 Room instrumentation 跨 Repository 实例验证恢复事件持久化。双轴审查发现 `validated` 早于执行边界、`retryEvidenceCode` 只看 Run 状态两项问题，补红测后修复；最终 `git diff --check` 通过。
- 本地完整门禁：强制重跑 Gradle `141/141` tasks，耗时 `3m 11s`；JVM `683/683`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 与 Release lintVital 成功。Debug APK 为 `23,370,841` 字节、SHA-256 `d5470aa909bae8a93ff10bcb088ef9ce3b36bbec1da17caaf8ed3c001716b936`；Release APK 为 `3,187,250` 字节、SHA-256 `cf0a2cc320bb7ebc6828850e271860ed775a5ebcdc65bc5e9be18e0c5b267dc3`，版本 `0.1.13 (14)`，zipalign、v2 正式证书和单签名者校验通过。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`，以同一正式证书签署最新 Debug/Test APK 后无损覆盖。`interruptedExecutingToolWithoutResultPersistsCommitUnknownDisposition` 为 `OK (1 test)`、`0.592s`；`interruptedBeforeExecutionStepDoesNotPersistCommitUnknownDisposition` 为 `OK (1 test)`、`0.507s`。默认完整 `AndroidJUnitRunner` 为 `OK (231 tests)`、耗时 `90.302s`，无失败；在线 `emulator-5554` 未接收安装、测试或其他设备命令。
- 文档与相邻矩阵：README 和长期 `docs/` 更新后重新打包，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。相邻的“尚未提交安全重放资格”已由上一节完成，但本切片和新资格都没有开放旧 Run 原地重放。

## 2026-07-27 功能对话框归属收口（横向结构工程完成）

- 实现边界：Agent Run 重试、Workflow Run 重试、长期记忆编辑/删除和本地 Skill 删除的实现分别迁入 `ui/agenttask`、`ui/workflow`、`ui/memory`、`ui/agentskill`。待确认/编辑状态和按稳定 ID 推导的 busy 状态进入对应 Projection；确认类型定义也归属各自 contract。
- 宿主边界：四个 dialog host 继续在 `XiaoLingContent` 页面内容之外全局挂载，切换 pane 不会丢失对话框。备份恢复继续由根层持有 `ActivityResultLauncher`、`Uri`、Room 替换、Keystore 提示与重启语义；`CenterNoticePopup`、Android 文件选择器和 `SettingsPage` composition root 未迁移。
- 结构结果：`XiaoLingApp.kt` 从 `1,103` 行降到 `817` 行；AgentTask/Workflow/Memory/AgentSkill dialogs 文件分别为 `94 / 90 / 233 / 44` 行。新增 7 条 Compose 测试覆盖重试证据与步骤复用文案、确认/取消、记忆编辑路由和 busy 禁用；Projection JVM 同步覆盖 overlay 身份与 busy 推导。
- 本地已验证：`git diff --check`、JVM `678/678`、`lintDebug`、Debug APK、AndroidTest APK、R8 Release APK 与 `lintVitalRelease` 通过。Debug/Release APK 分别为 `24,106,927 / 3,187,250` 字节；对应 SHA-256 为 `683746618cb0ff4e8f8e7d0f81ad963b156f55750719ff9797b527ca7da213e2 / 7fa5e68999551d42385a915151f3ca8145de535142c6a851565459e075a1c724`。AndroidTest APK 会打包持续更新的 `docs/` corpus，不记录自引用大小或哈希。
- Redmi 已验证：仅向真机 `wsvwypiz7xwslvl7` 发送设备命令，使用同一正式证书签署最新 Debug/Test APK 后无损覆盖安装；新增四个对话框测试类共 `OK (7 tests)`，测试耗时 `9.247s`、墙钟 `11.48s`。默认完整 `AndroidJUnitRunner` 为 `OK (229 tests)`，测试耗时 `89.151s`、墙钟 `91.71s`，无失败。最终 README/docs 重新打包后的 `projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。在线的 `emulator-5554` 未用于安装或测试。
- 设备收尾：重新覆盖正式 `outputs/release/xiaoling-v0.1.13.apk` 并卸载 `com.longdev.xiaoling.test`；设备报告 `0.1.13 (14)`，冷启动 `546ms`，`MainActivity` 为前台 resumed Activity、主进程存活，清空后重新采集的 crash buffer 为空。
- 保持边界：旧 Run、Room v32、Agent/Workflow 执行语义、Skill 导入、记忆持久化、设备工具前台门禁、answerability shadow、精确定时、Foreground Service 和第 101/102 项均未改变。结构工程达到停止条件，下一主线切换到通用执行恢复矩阵。

## 2026-07-26 验证报告历史归档

- 归档边界：原 2,391 行验证报告冻结为 [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)，当前卷收敛为发布基线、当前工程边界、历史索引和归档点之后的新验证。历史卷的 Skill 示例相对链接已按新目录修正。
- 语料契约：AndroidTest 继续显式导入根级当前卷，并新增历史卷 asset；黄金查询分别覆盖当前发布门禁和历史引用清理证据。正例计数改为跟随黄金查询集合，新增历史卷不再要求同步修改固定常量。
- 本地聚焦门禁：`:app:testDebugUnitTest`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 成功，`git diff --check` 通过。
- Redmi 单项：只使用 `wsvwypiz7xwslvl7`。首次运行准确暴露旧固定断言 `expected:<5> but was:<6>`；修正后重新构建、安装并运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 行为边界：本阶段只调整长期文档、AndroidTest 语料清单和质量门禁计数，不修改应用运行时、Room Schema、Provider、Agent、Workflow 或设备工具行为。

## 2026-07-26 应用导航宿主迁出（横向结构工程）

- 实现边界：新增纯 Kotlin `XiaoLingNavigationCoordinator`、Compose `XiaoLingNavigationController` 和独立底栏实现，统一类型化 Tab、14 个设置目标、知识文档跳转、五类跨域导航、返回优先级和严格两秒退出窗口。Android effect 仍由 `XiaoLingContent` 执行。
- 保存兼容：Activity 重建仍只保存知识文档目标；Tab、设置子页和根返回时间按原行为重置。Provider 编辑器优先关闭，设置子页返回清除知识目标，根页面第一次返回只显示提示。
- 结构结果：`XiaoLingApp.kt` 从 `7,018` 行降到 `6,925` 行；导航 module 的 controller interface 隐藏状态转换与 Compose 保存实现，没有引入页面级透传 wrapper。
- 聚焦门禁：`XiaoLingNavigationCoordinatorTest` JVM `6/6`；Debug 与 AndroidTest APK 构建成功。只在 Redmi `wsvwypiz7xwslvl7` 运行新增 MainActivity 导航单项和既有知识引用跨域 E2E，两项分别为 `OK (1 test)`。未连接或操作 Pixel_9。
- 保持边界：Room v32、Provider、Agent Runtime、Workflow Ledger、设备工具、answerability shadow 和第 101/102 项状态均未改变。下一项横向结构工程为 Workflow 管理垂直 UI module。

## 2026-07-26 Workflow 管理垂直 UI module（横向结构工程）

- 实现边界：新增 `WorkflowManagementUiState`、按 Workflow 聚合四类账本的 `WorkflowManagementProjection` 和 10 个动作组成的 `WorkflowManagementActions`。页面及条目、编辑/调度弹窗、步骤快照呈现和格式化 helper 迁入 `ui/workflow`，不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：应用壳只投影 Workflow 字段并提供通知权限与返回回调；`XiaoLingViewModel` 实现动作 interface。全局 Workflow Run 重试确认继续属于应用宿主，Repository、WorkManager、Agent preflight、Ledger 与调度恢复逻辑不变。
- 结构结果：`XiaoLingApp.kt` 从导航阶段的 `6,925` 行降到 `6,217` 行。新模块拥有页面局部的新建、编辑、调度和展开状态，Compose 不再自行过滤 Run/Task/Schedule 或解码步骤快照。
- 本地完整门禁：强制重跑 `140/140` tasks，review 修复后完整回归为 JVM `664/664`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。最终 Debug/Release APK 分别为 `24,228,509 / 15,967,190` 字节；AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录会被文档自身改写的包大小。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`，新增 fake actions Workflow Compose 单项为 `OK (1 test)`；默认完整 `AndroidJUnitRunner` 为 `OK (198 tests)`、耗时 `51.74s`；更新后的当前 README/docs 重新打包后，最终文档语料单项为 `OK (1 test)`。在线模拟器没有接收 ADB 设备命令。
- 保持边界：Room v32、Workflow 执行/调度/停止/恢复语义、Provider、Agent Runtime、设备工具、answerability shadow 和第 101/102 项状态均未改变。后继 Agent 任务中心切片见下一节。

## 2026-07-27 Agent 任务中心垂直 UI module（横向结构工程）

- 实现边界：新增 `AgentTaskCenterUiState`、按稳定 Run ID 绑定 selected/retrying 的 `AgentTaskCenterProjection` 和三项动作组成的 `AgentTaskCenterActions`。筛选、首刷、历史指标、Run 卡片、选中详情、Ledger-first 工具明细、双源一致性、恢复处置、步骤、审批和事件呈现迁入 `ui/agenttask`，页面不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：应用壳只投影 loading、error、history、selected 和 retrying，并提供设置返回；`XiaoLingViewModel` 实现刷新、选择和请求重试。全局 Agent Run 重试确认及成功后回来源会话的导航继续属于宿主，Repository、`AgentRunRetryCoordinator`、Runtime 和旧 Run 语义不变。
- 共享呈现：对话 Run 时间线和任务中心共用 `AgentRunUiPrimitives.kt` 的状态徽标、Step 行和中文状态文案，不复制同一业务状态的颜色与结论文案。
- 结构结果：`XiaoLingApp.kt` 从 Workflow 阶段的 `6,217` 行降到 `5,176` 行；`AgentTaskCenterPage.kt / AgentTaskCenterContract.kt / AgentRunUiPrimitives.kt` 分别为 `1,045 / 47 / 175` 行。双轴 review 从 `d232bbc` 固定点执行；Spec 轴发现设置入口仍提前刷新，修复后空列表首刷真正由页面持有，Standards 轴无硬性违规。页面边界通过专用 projection 和 actions interface 收口，不是仅移动私有 Composable。
- 本地完整门禁：review 修复后强制重跑 `140/140` tasks，完整 JVM `665/665`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,239,600` 字节、SHA-256 `f5210905d08774f6927a4a3ef59f36f7f18253b1678ff3a00da84b87c6bad8ee`；Release APK 为 `15,967,190` 字节、SHA-256 `b36f50f8466db3254040eb5165cee549ae4e705a7b2322e5ab9caffce3bd3ba7`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。新增页面动作路由、筛选和恢复处置 Compose 聚焦为 `OK (3 tests)`；review 修复后覆盖安装最新 Debug/Test APK，默认完整 `AndroidJUnitRunner` 为 `OK (199 tests)`、耗时 `52.659s`。在线模拟器没有接收 ADB 设备命令。
- 文档门禁：四份长期文档完成同步后重新构建 AndroidTest APK，并在同一 Redmi 复验 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：Room v32、任务筛选/指标、重试证据和关联新 Run 行为、Provider、Agent Runtime、Workflow、设备工具、answerability shadow 和第 101/102 项状态均未改变。下一项横向结构工程优先迁出长期记忆管理主体，Provider 管理随后处理。

## 2026-07-27 长期记忆管理垂直 UI module（横向结构工程）

- 实现边界：新增 `MemoryManagementUiState`、只保留 `PENDING / CONFLICT` 可操作候选并按稳定 ID 绑定 selected/mutating 的 `MemoryManagementProjection`，以及 15 项动作组成的 `MemoryManagementActions`。空列表首刷、候选开关、搜索、筛选、候选决定、正式记忆列表、来源与召回审计、生命周期操作和删除撤销呈现迁入 `ui/memory`，页面不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：应用壳只投影长期记忆字段并提供设置返回；`XiaoLingViewModel` 实现真实 Room、候选协调器、跨进程撤销和一次性导航动作。编辑/删除确认弹窗及来源会话/Run 导航 effect 继续属于宿主，Room Store 与业务语义不变。
- 结构结果：`XiaoLingApp.kt` 从 Agent 任务中心阶段的 `5,176` 行降到 `4,644` 行；`MemoryManagementPage.kt / MemoryManagementContract.kt` 分别为 `678 / 115` 行。页面边界通过专用 projection 和 actions interface 收口，不是仅移动私有 Composable；设置入口不再提前刷新，空列表跨重组只首刷一次。双轴 review 从 `052f97f` 固定点执行；Spec 轴无 finding，Standards 轴发现页面重复解释已限定候选状态，修复后标签、主按钮文案和冲突标记由 projection 呈现模型统一产出，不可达状态分支已删除。
- 本地完整门禁：强制重跑 `140/140` tasks，完整 JVM `666/666`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,272,368` 字节、SHA-256 `f084cfaa35e6838daffff74e7ffbcbdc2a27c5ae53162046846b258098b650ab`；Release APK 为 `15,983,574` 字节、SHA-256 `88d2fd4ba706b34d3410681748ad443328cae8d25e6c30948a3300ee89019666`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。新增 projection JVM 为 `1/1`，页面动作路由与空页面跨重组首刷 Compose 聚焦为 `OK (2 tests)`；覆盖安装最新 Debug/Test APK 后，默认完整 `AndroidJUnitRunner` 为 `OK (201 tests)`、耗时 `54.857s`。在线模拟器没有接收 ADB 设备命令。
- 文档门禁：四份长期文档完成同步后重新构建 AndroidTest APK，并在同一 Redmi 复验 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，最终结果为 `OK (1 test)`。
- 保持边界：Room v32、候选协调器、敏感过滤、去重/冲突、FTS、生命周期、来源审计、跨进程删除撤销、Provider、Agent Runtime、Workflow、设备工具、answerability shadow 和第 101/102 项状态均未改变。下一项横向结构工程为 Provider 管理垂直 UI。

## 2026-07-27 Provider 管理垂直 UI module（横向结构工程）

- 实现边界：新增 `ProviderManagementUiState`、按稳定 Provider ID 绑定 selected/syncing/result 的 `ProviderManagementProjection`，以及 14 项动作组成的 `ProviderManagementActions`。列表、空态、批量/单项同步、新增/编辑/删除入口、扫码/剪切板/Base64 辅助、字段编辑、模型获取/勾选和保存入口迁入 `ui/provider`，页面不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：应用壳只投影 Provider 字段并继续统一处理编辑草稿优先级、系统返回与底栏显隐；聊天 Provider 下拉仍属于对话宿主。`XiaoLingViewModel` 实现窄 actions interface，原 Provider 保存、删除、模型同步、Agent Profile 修复和二维码解析语义不变。
- 结构结果：`XiaoLingApp.kt` 从长期记忆阶段的 `4,644` 行降到 `4,003` 行；`ProviderManagementPage.kt / ProviderManagementContract.kt` 分别为 `793 / 85` 行。双轴 review 从 `05a2f99` 固定点执行；Standards 轴无 finding，Spec 轴发现最终文档与真实宿主组合覆盖尚未完成，现已补齐 MainActivity 的编辑器返回/底栏回归并同步四份长期文档。
- 本地完整门禁：强制重跑 `140/140` tasks，完整 JVM `668/668`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,288,752` 字节、SHA-256 `c03cddc3a08824e3f92302ccd6caff1efa9a25c69a189d95b654e4273f583e66`；Release APK 为 `15,983,574` 字节、SHA-256 `2b3b8c1952125c6a99e7cb2573a08b3ea62732639d628c7b3dc36bd8a1b86566`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。Projection JVM 为 `2/2`，Provider 页面列表动作与编辑器字段/平台回调 Compose 为 `OK (2 tests)`，真实宿主设置返回与编辑器优先级/底栏显隐为 `OK (2 tests)`；覆盖安装最新 Debug/Test APK 后，默认完整 `AndroidJUnitRunner` 为 `OK (204 tests)`、耗时 `59.619s`。在线模拟器没有接收 ADB 设备命令。
- 文档门禁：四份长期文档完成同步后重新构建 AndroidTest APK，并在同一 Redmi 复验 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，最终结果为 `OK (1 test)`。
- 保持边界：Room v32、Provider 持久化/删除/选中修复、模型同步协调、Agent 启动前校验、扫码参数、Agent Runtime、Workflow、设备工具、answerability shadow 和第 101/102 项状态均未改变。下一项横向结构工程为 Agent Profile 管理垂直 UI。

## 2026-07-27 Agent Profile 管理垂直 UI module（横向结构工程）

- 实现边界：新增 `AgentProfileManagementUiState`、按稳定 Profile ID 绑定 selected/mutating/deleteEnabled/providerModelValid 的 `AgentProfileManagementProjection`，以及三项动作组成的 `AgentProfileManagementActions`。列表、增删改选、编辑草稿、Provider/模型选择、Chat/Responses 模式、长期记忆开关、工具与 Skill 双向依赖和字段长度限制迁入 `ui/agentprofile`；`CompactTextField` 提升为共享控件。
- 宿主边界：应用壳只负责 projection、设置返回、底栏显隐和全局结果提示；`XiaoLingViewModel` 实现 actions，并在持久化入口重新校验 Provider、模型、注册工具与 Skill 依赖。编辑状态按稳定 Profile ID 持有，列表重排或记录替换不会把保存/删除动作绑定到错误对象；旧 Run/Profile 快照、Provider/模型删除保护和业务保存顺序不变。
- 结构结果：`XiaoLingApp.kt` 从 Provider 阶段的 `4,003` 行降到 `3,631` 行；`AgentProfileManagementContract.kt / AgentProfileManagementPage.kt / CompactTextField.kt` 分别为 `104 / 610 / 46` 行。双轴 review 从 `8a12c90` 固定点执行：Standards 轴发现文档未同步、业务不变量注释和命名问题，已修复；Spec 轴要求的稳定 ID 重排/对象替换回归已补齐，无范围膨胀。
- 本地完整门禁：强制重跑 `140/140` tasks，完整 JVM `670/670`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug/Release APK 分别为 `23,305,195 / 15,999,958` 字节，SHA-256 分别为 `9cce542e7e2e1bdb8c4801e7566942110e4e6713aa0dd5515e1079755c619fb8 / 39e69ece1c7d9da5afa235e054028ff37e2576034dac32978fe3ab06cf1fedf6`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。Agent Profile 页面 Compose 聚焦为 `OK (3 tests)`，真实宿主返回与底栏为 `OK (1 test)`；最终默认完整 `AndroidJUnitRunner` 的 Gradle 控制台为 `Finished 220 tests`、`BUILD SUCCESSFUL in 1m 22s`。JUnit XML 精确记录 `208` 条（`196 passed / 12 skipped / 0 failed`），执行时间 `69.14s`；控制台与 XML 的差异来自 skipped 用例统计口径，不代表隐藏失败。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 复验 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：Room v32、Agent Runtime、Workflow Ledger、设备工具前台门禁、answerability shadow、精确定时、Foreground Service 和第 101/102 项状态均未改变。后继 Agent Skill 管理见下一节。

## 2026-07-27 Agent Skill 管理垂直 UI module（横向结构工程）

- 实现边界：新增 `AgentSkillManagementUiState`、按稳定 Skill ID 绑定操作资格并投影工具依赖/最近 Run 选择审计的 `AgentSkillManagementProjection`，以及五项动作组成的 `AgentSkillManagementActions`。列表、首刷、展开、启停、请求导入/删除、依赖可用性和最近三条 Skill 版本/Run 终态迁入 `ui/agentskill`；损坏旧 `skill.selected` 事件保守忽略。
- 宿主边界：应用壳只投影 Skill、Tool Registry 与最近 Run 历史，通过动作适配器保留 Android 文件选择器，并继续持有本地 Skill 删除确认、设置返回和底栏显隐；ViewModel 保留真实 Room 刷新、启停和删除副作用。Skill JSON 校验、Runtime 选择/审计写入和 Agent Profile 白名单语义不变。
- 结构结果：`XiaoLingApp.kt` 从 Agent Profile 阶段的 `3,631` 行降到 `3,497` 行；`AgentSkillManagementContract.kt / AgentSkillManagementPage.kt` 分别为 `126 / 295` 行。双轴 review 从 `adf00bd` 固定点执行并经复审：删除未消费的 mutating 原始字段，补齐工具依赖与 Run 审计 projection，把导入请求收口进 Actions，并移除 ViewModel 审计刷新透传；跨 source set 的小型测试 fixture 重复因提取成本高于收益而保留。
- 本地完整门禁：强制重跑 `140/140` tasks，完整 JVM `673/673`、0 失败/错误/跳过；Lint `0 error / 50 warnings / 0 information`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug/Release APK 分别为 `23,321,579 / 15,999,958` 字节，SHA-256 分别为 `cbb7f0e00d7597d288502727fb18fac3db6d2989292451959fff2b459bf10289 / f9862caff455ad8385d7c3a69a152b16a593370c8b716251a4a94a2729a34885`。AndroidTest APK 会打包持续维护的 `docs/` corpus，因此不记录自引用大小或哈希。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。Projection JVM 为 `3/3`，页面动作/稳定展开 Compose 聚焦为 `OK (2 tests)`，真实宿主返回/底栏为 `OK (1 test)`；覆盖安装最新 Debug/Test APK 后，默认完整 `AndroidJUnitRunner` 为 `OK (211 tests)`、耗时 `70.952s`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 复验 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：Room v32、Skill 导入/持久化、Runtime 选择与历史审计、旧 Run、Agent Profile、设备工具前台门禁、answerability shadow、精确定时、Foreground Service 和第 101/102 项状态均未改变。下一轮从 `XiaoLingApp.kt` 剩余 `3,497` 行重新盘点完整垂直簇。

## 2026-07-27 会话主界面垂直 UI module（横向结构工程）

- 实现边界：新增分组 `ConversationUiState`、统一派生发送/附件/记忆/等待/知识引用状态的 `ConversationProjection`、单一 `ConversationActions`，以及独立 `ConversationPage` 与消息渲染文件。页面不再读取整份 `XiaoLingUiState` 或具体 ViewModel，并自己持有滚动跟尾、消息组合、附件/SharedDraft、Agent Run/审批和输入区。
- 宿主边界：`XiaoLingContent` 只投影会话字段并适配 Actions；图片/文档 `OpenDocument`、URI 读取和答案知识引用跨页导航仍属于应用壳。原 ViewModel 的会话、Provider/模型、输入、发送/停止、审批和草稿副作用原样复用。
- 结构结果：Agent Skill 阶段的真实宿主基线经复核为 `3,497` 行，本轮 `XiaoLingApp.kt` 降到 `1,796` 行；`ConversationContract.kt / ConversationPage.kt / ConversationMessageContent.kt` 分别为 `224 / 1,235 / 643` 行。双轴 review 未发现明确行为回归；同模块重复时间格式已合并，普通聊天无模型禁发、附件/加载忙态、知识引用去重和更多 Actions 路由已补测试。
- 本地完整门禁：review 修复后强制重跑 `140/140` tasks，完整 JVM `677/677`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,337,963` 字节、SHA-256 `61b5cb5b14b43c8e01fe07a9ea4067e918d8c6f8e3d98baab25bc1cee2bce1f6`；Release APK 为 `16,016,342` 字节、SHA-256 `f537287d9a6ec10f2e3d7e8675fef6bf9690dda00b8994961336b7afd8c6b9d9`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7` 覆盖安装最终 Debug/Test APK，会话页面图片/文档、发送/停止与 SharedDraft 路由为 `OK (3 tests)`；默认完整 instrumentation 为 `OK (214 tests)`、测试耗时 `74.329s`、墙钟 `76.96s`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：切会话归尾、Tab 往返保留阅读位置、用户离尾后不被流式更新强拉、流式完成后二次校准、普通聊天与 `/agent`、附件、知识引用、审批、Room v32、Workflow、设备工具、answerability shadow 和第 101/102 项均未改变。下一轮从宿主剩余 `1,796` 行重新盘点完整垂直簇。

## 2026-07-27 提示词设置垂直 UI module（横向结构工程）

- 实现边界：新增 `PromptSettingsActions` 与独立 `PromptSettingsPage`。页面只接收 `PromptSettings`、Actions 和返回回调，持有三类最终预览的互斥展开状态；`PromptPolicy`、默认模板和 `UiPreferenceStore` 边界不变。
- 宿主边界：`SettingsPage` 只传入当前提示词设置并由 `XiaoLingViewModel` 实现九项动作。输入仍即时更新 state 并保存，恢复默认只改对应模板；设置导航继续留在应用壳。三个设置页复用的 `CompactSection` 已迁为共享 UI 原语。
- 结构结果：`XiaoLingApp.kt` 从 `1,796` 行降到 `1,582` 行；`PromptSettingsContract.kt / PromptSettingsPage.kt / CompactSection.kt` 分别为 `21 / 222 / 64` 行。固定点 `817f29f` 审查已解决文档未同步硬缺口；三组显式页面映射保留，用于清楚区分普通对话、会话摘要和 Agent 总结动作。
- 本地完整门禁：强制重跑 `140/140` tasks，完整 JVM `677/677`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,354,347` 字节、SHA-256 `194f25d3173f50d20fe8cbc3c11be1a73cdbd7738638218d3b3fc1758b9704cc`；Release APK 为 `16,016,342` 字节、SHA-256 `78470c153f4a2477dec0dfb9c8377b9c55abd233435554f6b3ca54260ace4d66`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`，页面动作路由和最终策略预览聚焦为 `OK (2 tests)`。默认完整 instrumentation 的 JUnit XML 为 `216` 条（`204 passed / 12 skipped / 0 failed`）、耗时 `79.503s`；Gradle 控制台按 skipped 的另一口径显示 `Finished 228 tests`，完整运行耗时 `2m 14s`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：三类提示词开关、文本持久化、逐项恢复和不可覆盖安全尾部保持不变；Room v32、普通聊天、Agent/Workflow、设备工具、answerability shadow 和第 101/102 项均未改变。下一轮从宿主剩余 `1,582` 行重新盘点完整垂直簇。

## 2026-07-27 进程退出观察垂直 UI module（横向结构工程）

- 实现边界：新增 `ProcessExitObservationUiState`、单项刷新 `ProcessExitObservationActions` 和独立 `ProcessExitObservationPage`。六类证据、稳定数值、Room 同源列表 key、加载/错误/空态和固定证据边界迁入 `ui/processexit`，页面不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：应用壳继续在进入页面前先调用 `refreshProcessExitObservations()` 再导航；`XiaoLingViewModel` 只实现 actions interface，刷新仍取消旧 Job、在 IO 线程只调用 `latest()` 并传播协程取消。前台/Worker 的平台采集、Room v32 与 system 分类不变。
- 结构结果：`XiaoLingApp.kt` 从 `1,582` 行降到 `1,404` 行；`ProcessExitObservationContract.kt / ProcessExitObservationPage.kt` 为 `13 / 217` 行。TDD 先取得三个新 seam 未定义的编译 Red，再以最小 wrapper 转绿并迁入完整页面。双轴 review 的 Spec 轴无 finding；Standards 轴指出的文档缺口已同步修正，运行时历史时间继续保持原页面的设备时区语义。
- 本地完整门禁：强制重跑 `140/140` tasks，JVM `677/677`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功。Debug APK 为 `23,354,347` 字节、SHA-256 `260620b0a6a3ebc0780f7f2c3eeecc3533297ff96ac5515caf14dea11466c265`；Release APK 为 `16,016,342` 字节、SHA-256 `2f919076cd17d58f05522a3a5162b5e80d8ae9086aec6a07ff4115db6328999f`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。新页面动作、边界、加载/错误和六类证据 Compose 为 `OK (4 tests)`；默认完整 JUnit XML 为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `80.011s`。Gradle 控制台按 skipped 的另一口径显示 `Finished 229 tests`。没有启动、连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：查看或刷新不触发 `collect()`，不新增 Agent Run、Workflow 或 Task 关联；Room v32、自然 LMK 证据、设备工具前台门禁、answerability shadow、Foreground Service 和第 101/102 项均未改变。

## 2026-07-27 网络请求设置垂直 UI module（横向结构工程）

- 实现边界：新增单字段 `NetworkRequestSettingsUiState`、更新/恢复默认两项 `NetworkRequestSettingsActions` 和独立 `NetworkRequestSettingsPage`。五行 User-Agent 编辑器、复制、清空、恢复默认与剪贴板适配迁入 `ui/networksettings`，页面不再接收整份 `XiaoLingUiState` 或具体 ViewModel。
- 宿主边界：`SettingsPage` 只投影 `state.userAgent`、传入 Actions 和返回回调；`XiaoLingViewModel` 继续执行 CR/LF 过滤、512 字符截断、即时 UI 更新和 `UiPreferenceStore` 保存。清空后当前页面为空、重启后回到默认值的既有时序保持不变。
- 结构结果：`XiaoLingApp.kt` 从 `1,404` 行降到 `1,317` 行；`NetworkRequestSettingsContract.kt / NetworkRequestSettingsPage.kt` 为 `11 / 116` 行。TDD 先取得新 seam 未定义的编译 Red，再以原页面交互转绿并迁入完整页面；双轴审查未发现规范或行为 finding。
- 本地完整门禁：强制重跑 `140/140` tasks，JVM `677/677`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功，Release 通过 zipalign 与 v2 单签名。Debug APK 为 `23,370,731` 字节、SHA-256 `8e1d71862a6c6ec428834936bf607bdb15237fc9bfb5e4845e7473c7975034e9`；Release APK 为 `16,016,342` 字节、SHA-256 `0101fed9730bc2787f94471e553d7d75747b5aae3aaa5e5b7c5a1523efd51ccc`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。复制、清空、输入替换、恢复默认和返回动作 Compose 聚焦为 `OK (1 test)`；默认完整为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `78.642s`。没有启动、连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：模型列表、Chat Completions、Responses 和后台 Agent 继续共用原 Header 构造；Room v32、Provider、Agent Runtime、Workflow、设备工具前台门禁、answerability shadow、Foreground Service 和第 101/102 项均未改变。后继设置根页已由下一节完成窄投影、Actions 和页面迁出，`SettingsPage` composition root 继续留在应用壳。

## 2026-07-27 设置根页垂直 UI module（横向结构工程）

- 实现边界：新增 `SettingsRootUiState`、`SettingsRootActions`、`SettingsRootProjection` 和独立 `SettingsRootPage`。Projection 按稳定 Profile ID 绑定当前 Agent 身份，并将 Provider/模型、Shadow、Skill、Workflow、Agent Run、进程退出观察和备份状态压缩成显示摘要；原 14 项顺序、主题选择和备份交互迁入 `ui/settingsroot`。
- 宿主边界：`SettingsPage` 只负责从 `XiaoLingUiState` 产生窄投影，并把 ViewModel 主题动作、13 个子页导航及 Android 备份 launcher 适配为 Actions。pane 分派、Provider editor 优先级、底栏显隐、平台生命周期和跨模块协调仍属于 composition root，没有机械迁移。
- 结构结果：`XiaoLingApp.kt` 从 `1,317` 行降到 `1,097` 行；`SettingsRootContract.kt / SettingsRootPage.kt` 为 `88 / 264` 行。TDD 分别取得 projection 与 page/actions 未定义的编译 Red，再转绿并删除旧内嵌实现。双轴 review 的 Spec 轴无 finding；Standards 轴无硬性违规，匿名 Actions 适配和显式摘要字段属于保留依赖方向的有意结构。
- 本地完整门禁：强制重跑 `140/140` tasks（`1m 58s`），JVM `678/678`、0 失败/错误/跳过；Lint `0 error / 50 warnings`；Debug、AndroidTest、Release APK 和 Release lintVital 全部成功，Release 通过 zipalign 与 v2 单签名。Debug APK 为 `23,387,174` 字节、SHA-256 `309faa26a77d42fccca4108e9849a474ca9ec53ba38e190570facfd82659f757`；Release APK 为 `16,032,726` 字节、SHA-256 `cee1e20edd6ce0ae536e9331fa18729e1e793ac946ae6dde08da62734c7962cd`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。动态摘要、14 项入口、主题选择、空态和备份 busy 交互 Compose 为 `OK (4 tests)`；默认完整 JUnit XML 为 `221` 条（`209 passed / 12 skipped / 0 failed`）、耗时 `85.834s`。Gradle 控制台按 skipped 的另一口径显示 `Finished 233 tests`。没有启动、连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档完成同步并重新构建 AndroidTest APK 后，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：备份忙时两个图标继续禁用、父卡仍可触发导出；Room v32、设置子页实现、Provider、Agent Runtime、Workflow、设备工具前台门禁、answerability shadow、Foreground Service 和第 101/102 项均未改变。

## 2026-07-27 单一启动画面、Release Profile 与固定设置标题

- 实现边界：Android 12+ 继续保留 `Theme.XiaoLing` 的系统 Splash Logo；删除 `XiaoLingLaunch.kt`，`MainActivity` 直接渲染 `XiaoLingApp`，不再保留 Compose 品牌页、`880ms` 等待或 `260ms` Crossfade。设置根页将标题/主题选择器留在固定外层，仅让 14 项卡片区域滚动。
- 启动初始化：Provider、会话、附件、知识库、网络、Agent、Workflow、WorkManager Scheduler、退出观察和备份对象改为惰性构造；`XiaoLingApp` 交付可见首帧后才调用单次 `initialize()`。Manifest 只移除 WorkManager Startup initializer，`XiaoLingApplication : Configuration.Provider` 保留官方按需初始化路径。
- Profile/R8：Release 启用 R8，并按 Android 官方 Kotlin metadata 兼容表使用 `9.1.29`；干净 Release 构建成功且旧 metadata 警告消失。`baselineprofile` module 只在 Redmi 生成冷启动 Profile；`baseline-prof.txt / startup-prof.txt` 各 `18,011` 行，Release APK 内 `baseline.prof / baseline.profm` 为 `13,847 / 719` 字节，低于 `1.5 MB` 上限。
- 测试边界：设置根页保留“滚到底后标题和主题入口仍显示”回归。PNG 分享测试在发起缺失图片分享前监听瞬时 `OperationResult`，明确断言“图片不可用”和 `success=false`，同时验证附件为空、不自动发送。
- 本地完整门禁：JVM `678/678`、0 失败/错误/跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 和 Release lintVital 全部成功。Release 通过 zipalign、v2 固定证书与单签名者校验。Debug APK 为 `23,354,457` 字节、SHA-256 `7394d986be7a12d0b2b0b853d54f7af4ac438017a7f2ec28f843e816ce556c84`；Release APK 为 `3,170,866` 字节、SHA-256 `6c28ac665471e4cddda4d58f0c36a79458cadb929bc3fe11c289113cf9ba004e`。
- Redmi 门禁：只使用 `wsvwypiz7xwslvl7`。原 Debug 冷启动约 `3.4–3.7s`；最终 R8 Release 覆盖安装后首次 `am start -W` 为 `533ms`，`speed-profile` 编译后三次为 `580 / 504 / 587ms`。Release 主页、设置滚到底、前台 Activity、PID 与空 crash buffer 通过；默认完整 instrumentation 为 `OK (222 tests)`、耗时 `83.58s`。没有启动、连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 文档门禁：四份长期文档同步后重新构建 AndroidTest APK，在同一 Redmi 运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果为 `OK (1 test)`。
- 保持边界：Android 系统 Splash 不移除，当前已无应用人为等待；后续只在 Macrobenchmark 数据证明回归时继续调整 Profile 范围。Room v32、分享解析/附件校验、设置子页导航、Provider、Agent Runtime、Workflow、设备工具前台门禁、answerability shadow、Foreground Service 和第 101/102 项均未改变。

## 当前工程边界

- 当前源码与 Redmi 开发数据为 Room v33；固定发布产物 `v0.1.13` 仍是 Room v32 基线，不能在保留 v33 数据时直接向下覆盖。Agent Runtime、Workflow Ledger、设备 Agent 有限动作、长期记忆、声明式 Skill、RAG/Embedding 与 answerability shadow 既有边界不因文档归档而改变。
- 应用导航、Workflow 管理、Agent 任务中心、长期记忆管理、Provider 管理、Agent Profile 管理、Agent Skill 管理、会话主界面、提示词设置、进程退出观察、网络请求设置、设置根页和四组功能对话框已分别拥有独立 UI 边界；宿主当前 `817` 行。`SettingsPage` 继续作为 pane、Android launcher、导航和跨模块适配的 composition root。结构工程已达到停止条件；受控关联新 Run、已提交只读验证、全部已验证控制面收尾，以及失败 ToolResult/typed 失败验证两类原子失败结算已完成持久化幂等复核。当前主线已切回个人 Agent 能力，并完成前台手动 Workflow 的只读 snapshot、答案级观察证据、本地判定、真实双 Run、安全契约、`tap_ref` 首个生产动作、答案级动作证据 UI、`type_text` 专属安全/evidence seam/生产闭环、跨入口持久化隐私和 SAFE `back / home`；提交未知、成功结果尚无 typed 验证结论和其他证据漂移继续 fail-closed，不机械搬文件，也不把当前四个动作授权批量扩大到其他动作。
- answerability shadow 默认关闭；第 103/104/107 阶段后 Room v33 匿名账本为 `3` 条完成且接纳记录，最早到最新跨度 `5 小时 24 分 46.689 秒`，仍只属于同日证据。第 105 阶段已把每次开启收紧为最多一轮观测，第 106 阶段把时间证据投影到设置页但不自动判定资格，第 107 阶段真实确认预算耗尽但没有成功答案时不消费授权、不增加账本；`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。第 102 阶段强类型离线契约已完成，但 JSON/SAF、显式授权评测集、独立阈值校准和生产拒绝尚未进入。
- 前台手动 Workflow 当前精确允许同一 Agent Run 的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。`open_app / tap_ref / type_text` 要求逐动作 Room/overlay 审批、`executorVerified=true + typed PASSED` 和白名单后置判定；`open_app` 还要求唯一包名三层白名单、当前 generation，以及完成门禁和答案级 Room 重建都与获批目标一致。`back / home` 为空参数、零审批 SAFE 动作，但仍要求同 Run snapshot、TTL、当前 generation 和完整后置验证，`home` 还必须匹配系统动态解析的 launcher。文本输入的敏感参数预审计、当前 ref 节点 evidence、最小指纹授权、原 `nodePath` 精确回读和无原文答案级投影均已交付；第 118 阶段进一步统一直接 `/agent` 与 Workflow 的持久化隐私。Redmi 真实 Workflow 已取得打开应用 `APPROVED / PASSED / VERIFIED / afterPackage=com.android.calculator2`、文本输入 `APPROVED / PASSED / VERIFIED / exactReadBack=true`、返回动作 `approvals=0 / verified=true / VERIFIED` 和返回桌面动作 `approvals=0 / verified=true / VERIFIED`。`swipe` 与全部后台/定时设备工具继续关闭；精确定时和 Foreground Service 继续依据真实耗时与系统回收证据决定。
- 知识引用生命周期继续按当前文档状态复核；验收产生的临时知识数据必须确认文档、chunks 和检索索引均已清理。

## 历史证据

- [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)：包含 v0.1.0 至 v0.1.12、阶段性 JVM/Lint/APK、Redmi 真机、恢复可靠性、设备 Agent、Workflow、RAG/Embedding 与 answerability shadow 的完整历史记录。

## 维护方式

- 本文件只维护当前发布基线和归档点之后的新增验证，最新记录置于当前基线之后。
- 历史事实冻结在历史卷中；除修复失效链接或明确事实错误外，不在后续任务中反复改写。
- 当前卷再次显著增长时，以明确版本、日期和阶段截止点生成下一份历史卷，并同步更新文档索引与语料门禁。
