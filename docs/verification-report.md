# 验证报告

验证日期：2026-08-04（北京时间）

## 当前验证基线

- 当前发布版本：小灵 `v0.1.15`，`versionCode=16`、`minSdk=26`、`targetSdk=36`、Room v33。
- 发布范围：`v0.1.14` 后第 122 至 127 阶段；包含 `device.swipe` 的专属安全契约、执行期/完成态 evidence、答案级脱敏投影、生产默认接线与 Redmi 限定验收，以及自然语言个人任务、严格 1 至 8 步计划、确认前零执行和确认后原子创建普通 Workflow/Run。
- 发布提交：`b42defa06f02000b841ad7688e76edcf8bc8ce55`（`发布小灵 0.1.15`）；annotated tag `v0.1.15` 的 tag object 为 `7ecfd5269a2822feffbbda88cad0f53964f89aac`，解引用到该提交。[小灵 v0.1.15](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.15) 已发布并成为 latest，状态为非草稿、非预发布。
- 正式产物：`outputs/release/xiaoling-v0.1.15.apk`，大小 `3,318,322` 字节，SHA-256 为 `a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`；使用现有 `releaseLocal` 配置构建，但本轮未额外执行 `apksigner`、zipalign 或证书复核。
- 本地发布构建：只执行 `:app:assembleRelease`，结果为 `BUILD SUCCESSFUL in 1m 52s`。构建内部正常经过 R8 与 release lintVital task，但没有单独运行完整 JVM、完整 Lint、Debug/AndroidTest APK 或其他测试任务。
- Redmi 发布验收：按用户“不要验证，直接发版”的明确要求，本轮没有安装 `v0.1.15`，没有运行 instrumentation、冷启动、版本回读、Accessibility 或 crash 收尾，也没有向 Pixel/模拟器发送 ADB 命令。第 126/127 阶段既有 Redmi 聚焦证据保留为功能阶段事实，不记作本次发布门禁。
- 当前开发主线：第 127 至 130 阶段的自然语言计划、限定 App 多动作连续执行、目标级本地验证、记忆/知识计划上下文和应用内提醒已经完成，开发数据库保持 Room v35。下一阶段按任务级恢复与关联重试、Redmi 完整里程碑验收推进。纯重构、单层 evidence 和 Shadow 扩样不再抢占主线。前台 Workflow 当前七项仍为 `snapshot / open_app / back / home / tap_ref / type_text / swipe`；后台或定时设备自动化、恢复旧执行栈、坐标、截图、任意 App、JSON/SAF、生产 answerability enforcement、精确定时和 Foreground Service 继续关闭。
- 文档语料门禁：本轮未执行；最近一次仍是第 127 阶段最终资产在 Redmi 的 `OK (1 test)`，不能表述为 `v0.1.15` 发布复验。
- 发布阶段设备收尾：本轮未执行；Redmi 保留发布前的 `0.1.14 (15)` Debug 开发状态和私有数据，没有因本次发布被卸载、覆盖或清理。
- 远端资产：`xiaoling-v0.1.15.apk` 与 `xiaoling-v0.1.15.apk.sha256` 均为 `uploaded`；APK 远端大小 `3,318,322` 字节、digest `sha256:a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`，与本地产物一致。校验文件大小为 `87` 字节，远端 digest 为 `sha256:86bef3194ddda319bba39649b7f17cf30a49c928752ad254cc9faed575fd1aeb`。

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
