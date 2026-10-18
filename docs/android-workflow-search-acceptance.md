# Android 正式 Workflow 设置搜索专项验收

第 295 阶段，2026-10-18。仅使用 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、本机授权的真实 `gpt-6-luna` 与同签名 Debug `0.1.22 (23)`；iOS 产品继续暂停。

后续第 296 阶段已修复分步完成与后置 snapshot 输入隐私，并获得完整同轮通过，见[分步完成专项](android-workflow-completion-acceptance.md)。下文保留第 295 阶段原失败结论和证据。

## 当前状态：未通过

初次构建/Lint 与主机 9 项门禁测试通过。安装核查发现主包不存在，按用户要求安装同签名 Debug 主包并冷启动（3346ms），确认服务登记。用户已手动开启无障碍，执行基线 authorized=true、bound=true、crashed=false。

最初未授权记录保留在 `outputs/verification/stage295-workflow-search/accessibility-before.json`，新安装并授权后的实际基线另存 `accessibility-authorized-before.json`。首轮 `live-initial` 收到 USB ACK，但在“既有 Provider 密钥不存在，拒绝新增持久密钥”门禁失败（3.0s），尚未配置落库、请求模型或执行动作；报告和日志保留。修正新安装门禁后，`live-fresh-key` 在第二次审批后发生 ART `Unexpected instruction: unused-41 / SIGABRT`（96.46s），原报告仍为失败。独立读取 Room 发现 Workflow 已 COMPLETED，但第三步只有 snapshot；目标本地结论为 `PARTIAL / REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER`，不采用模型的完成状态替代输入验收。失败 Run `workflow-run-b616db80-5519-457e-99bd-28e57fde9eb8` 与三个 Agent 审计保留。

进程原生死亡跳过 finally，独立恢复夹具用冻结 seed 输入、步骤身份和创建版本确认当前定义，按确切 UUID 与 Profile 选择事件核对配置；只清理精确关联的每步用户消息与本地持久结果。首次恢复因会话正文门禁停止，第二次因正式 TOOL 证据分片被误判为附件停止，两份失败日志保留。修正后 `interruption-recovery-parts.log` 为 `OK (1 test)`：七条本轮消息、配置和定义移除，Run/审批/工具审计保留，无模型或工具重放。原偏好原值无法从丢失的内存恢复，只移除仍匹配本轮配置的选择键及测试开关；alias 归属证据已丢失，保留密钥，**不声称恢复了新安装时的无密钥状态或崩溃前全部偏好**。

搜索审批拆成独立挂起方法，改用显式循环并移除挂起 map；这是针对巨型协程方法的夹具修正，不是已证实的 ART 根因结论。第三步目标明确禁止未输入先 complete。`live-split` 在提供方响应缺少 `choices[0].message.content` 时失败（35.49s），无动作审批，原始报告保留；恢复回执缺失，独立检查确认仍有残留，定向恢复通过（`split-failure-recovery.log`），不推断 finally 已执行。随后把完整页面执行体与外层配置恢复分拆，保留全部断言。`live-page-split` 因第二步只执行 snapshot 就提前完成而失败（64.34s）；恢复回执通过，333 条旧审计行与偏好值守护成功，原报告保留。独立读取快照确认起点是设置首页，搜索文字节点没有动作，点击能力在父节点；步骤目标补充沿本轮 parent_index 选择最近可点击且只包含搜索标签的父节点。

`live-search-parent`（92.69s）已真实批准打开设置和点击搜索，两步各三调用/PASSED；第三步 type_text 已提出，但被夹具的固定标签检查拒绝，输入未执行，审批取消。独立快照显示目标是固定搜索伴随包的唯一编辑框，text=hint、无 description，旧标签白名单误拒绝了 ROM 的静态占位符。新增身份分支必须同时满足前序搜索点击已批准且 Executor/PASSED、固定伴随包、唯一未脱敏编辑框、非空 hint 且 text=hint，以及子树没有其他语义标签；已有查询和宽容器仍不进入该分支。uiautomator 未返回层级，不作为任何通过证据。

最新 `live-native-placeholder`（39.77s）首个 Agent 仅执行 snapshot 就提前 COMPLETED，未提出打开设置审批；测试停止关联 Workflow，最终 Room 为 CANCELLED，原 Agent 与工具审计保留。本轮基线偏好、Provider/Profile、Memory undo、原授权选择与 792 条旧审计行恢复通过；独立进程读取确认临时定义已移除，原 Provider/Profile 各一条。**完整九调用、三审批、VERIFIED、精确输入回读、隐私与 Activity 重建仍未通过；新的占位符批准分支尚未得到完整实机正向验收。**

## 本轮范围与通过标准

复用 `AndroidWorkflowLiveInstrumentedTest` 的正式 MainActivity、Workflow 页可见“手动运行”、`XiaoLingViewModel`、`AgentRunUseCase`、生产 Runtime/Gate/Accessibility 与正式 Room。定义、限定目标 `com.android.settings` 和目标完成契约由夹具预置，未执行的种子 Run 取消保留；实际验收 Run 只能由页面按钮新建。普通编辑器仍不能确认限定应用/目标契约，本专项不实现该产品入口。

搜索流程拆为三个独立 Agent Run，每步严格三调用，保留默认四调用预算：

1. `device.snapshot → device.open_app → device.snapshot`，打开系统设置根页。
2. `device.snapshot → device.tap_ref → device.snapshot`，点击搜索入口。
3. `device.snapshot → device.type_text → device.snapshot`，向搜索框精确输入本轮八字母公开随机文本。

准备期间只结束 Settings 与已登记伴随应用的旧窗口，从根页开始；不清应用数据、不改设置项。允许系统登记的搜索伴随包 `com.android.settings.intelligence`，不把它当作可任意打开的目标包。

三个动作都要求本 Workflow 当前步骤关联的 Room PENDING、正确工具与参数、无对应执行结果、生产 `XiaoLingDeviceActionApproval` 浮层，再由 UiAutomation 点击可见“批准执行”。节点引用检查只读当前 Controller 的内存快照与 `inspectReference`；不得在审批期间重新 capture、注入 snapshot/ref 或调用节点动作。搜索入口按标签/子树确认；ROM 占位符输入框另需上述已验证前序点击、固定伴随包与唯一编辑框联合身份，输入参数的持久投影必须为 `snapshot_id/ref/text_sha256/text_length`；不批准其他设置项。

每个 Agent 必须保留本轮 Profile 选择与非空真实模型请求事件，精确三调用序列、三条成功/PASSED 结果、一条 APPROVED、动作 `executorVerified=true`。步骤快照冻结同一契约；三个动作持久 VERIFIED。正式 Room 最终目标判定必须为 `VERIFIED / ALL_CRITERIA_VERIFIED`、3/3 步骤、匹配完整九调用顺序和合法最终包。目标契约自身不含文本谓词，精确文本成功由生产 type_text 回读验证并在结束后独立读取真实搜索框确认。

ToolCall、Approval、type_text ToolResult 与工具证据事件不得包含输入原文，结果不得含节点/ref 正文。公开文本存在步骤目标中，不把工具账本隐私投影称为所有业务正文均无文本。

动作完成后回小灵查看本 Run 及三步动作卡；Activity 重建后要求结果和输入动作仍可见、Room 判定不变。配置重建不等于进程死亡恢复；Workflow 页面是否展示独立目标级结论不由动作卡替代。

## 数据保护与证据

沿用第 294 阶段空业务内容/无未结束 Run/PENDING、无 Memory undo 主文件及旁文件门禁。既有 Keystore alias 不删除；仅本专项在旧十表审计全空、Provider IV/密文全空，且共用此 alias 的旧版 `xiaoling` 与 MCP `xiaoling_mcp` 偏好全空的新安装环境允许通过生产 `ApiKeyCipher` 临时创建 alias。公开随机探针与创建时间只在内存中检查归属，停止确认且配置集合、两份密钥消费者偏好及所有保护项恢复后才删除本轮 alias、恢复原 absent 状态；归属不明或恢复失败即保留现场。只创建本轮 UUID 配置和会话；停止确认后按稳定定义 ID 与完整定义/步骤快照清理，保留正式 Agent/Workflow Run、步骤、审批与工具审计。原 Provider/Profile、偏好稳定值、旧十表逐行摘要、undo 与授权选择均须一致；正文失败、停止失败及恢复失败保留完整异常链。停止确认基于 VM 状态，另用最终新进程偏好检查，不声称逐一 join 所有持久化 Job。

入口为 `tools/verify_android_live_acceptance.py --phase workflow-settings-search`。USB 配置只进入内存，主机记录 ACK，日志脱敏。主机门禁同时要求本阶段 scope、目标 VERIFIED/精确回读/隐私字段、Activity 重建、正式 case、恢复回执和 JUnit 成功；错阶段、缺证据或 ACK、PARTIAL/回读失败均拒绝。证据根 `outputs/verification/stage295-workflow-search/` 被忽略；失败报告与原日志不得覆盖。

本轮最后 Run 为 `workflow-run-fead5158-c740-46ea-8c3c-89fe8fdfe439`，Agent 为 `run-f01f8237-f615-4c20-b22b-64911d55a7a1`。六份 live 原报告均保留 completed=false，不从各轮部分动作拼出全链通过。最新独立读取为 `native-placeholder-failure-inspection.log`，新进程偏好摘要使用该轮 baseline 动态比较，不能套用第 294 阶段旧值。

最终文档语料/偏好保护、制品与凭据检查、原授权受控重绑分别记录在证据根的 `final-corpus-protected.log`、`artifact-audit-closeout.json`、`accessibility-closeout.json` 与 `verification-summary.json`；这些收尾检查不提升搜索功能验收状态。生产 main、Room schema、发布版本未改，iOS 产品暂停，不推送/打标签/发布。

独立待办：真实模型稳定遵循每步动作目标；完整搜索/输入/隐私/Activity 重建正向复验；新安装临时 alias 正常撤销路径。普通编辑器限定应用/目标契约、自然 LMK/Doze/系统恢复与正式 Release 继续单列。

相关：[第 294 阶段正式入口](android-workflow-acceptance.md)、[生产浮层专项](android-device-approval-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
