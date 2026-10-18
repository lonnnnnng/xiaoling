# Android 正式 Workflow 入口专项验收

第 294 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、本机授权真实 `gpt-6-luna`，同签名 Debug `0.1.22 (23)`；iOS 产品继续暂停。新增 AndroidTest 与主机入口，没有修改生产 main 逻辑、Room schema 或发布版本。

## 本轮通过记录

三项各有真实通过记录，**从三轮报告选择，不是同轮 3/3**。最终只读项来自清理归属及异常链修复后的 `live-editor-final`；批准来自 `live-formal`，该轮因拒绝项测试断言失败仍为 `completed=false`；拒绝最终通过来自 `live-denial-persistence`。原报告不覆盖。

| 项目 | Workflow Run | Agent Run | 持久终态 |
| --- | --- | --- | --- |
| 页面新建两步只读 | `workflow-run-bfa2f610-8dbd-4a2b-9df9-50ff217d2855` | `run-4822cef9-0cdf-4ae3-b996-101bc460a5c9`、`run-d67e8f6d-98fd-4f54-995c-731bacb619ef` | Workflow/两步/两个 Agent 均 COMPLETED |
| 手动运行、批准打开设置 | `workflow-run-68cff1e4-a5cd-4abc-9cf4-7d345d6f83fd` | `run-60983d44-bc10-428e-a9de-c28776e345c9` | APPROVED，Workflow/步骤/Agent COMPLETED |
| 手动运行、拒绝打开设置 | `workflow-run-36a2717e-4ed5-4014-955a-96a72f463580` | `run-e6e70de8-db09-4bdd-ae4a-acfb440f68ce` | DENIED，Workflow/步骤/Agent FAILED，预期拒绝 |

正式路径为 MainActivity → 设置/工作流页 → 可见“手动运行” → `XiaoLingViewModel.runWorkflow` → 每步 `AgentRunUseCase` → 生产真实模型、Runtime、Gate、Accessibility 和正式 Room。测试不直接调用 VM 的 `runWorkflow` 或手工 Runtime 来替代运行按钮。每项还要求配置 USB ACK、JUnit 成功、对应 case、空内容 scope 与恢复回执。

## 新建与设备定义的边界

后续第 300 阶段已补齐[普通编辑器的限定应用与完成标准确认](android-workflow-editor-acceptance.md)，并另行验收正式创建与保存链。下述预置定义边界保留为第 294 阶段原始口径。

只读项通过正式“新建工作流”编辑器填写名称和两个目标、保存，再通过本次定义的按钮发起。第一步只调用一次 `app.current_time`，第二步只调用一次 `app.get_info`，两项结果 PASSED。第二步的持久输入精确包含第一步实际输出；这是正式输入冻结证据，未独立抓取提供方收到的完整 Prompt。每个 Agent 均核对本轮 Profile 选择事件，以及非空真实 LLM 请求事件的模型名称。

**普通 Workflow 编辑器当前没有限定目标应用字段**，`createWorkflow` 保存 `targetAppPackage=null`；单靠步骤文本写包名不能获得设备动作权限。本轮不增加该产品能力。两个设备项由夹具先通过 Repository 预置 `com.android.settings` 定义，取消并保留未执行的种子 Run，再从正式页面按钮创建新的实际验收 Run。不能把这两项称为从编辑器确认限定应用的全链验收。

生产 `device-control` Skill 要求 Profile 保留完整七项静态设备工具，不能使用仅 snapshot/open_app 的 Profile。测试没有修改 Skill 或放宽此规则，只批准精确 `device.open_app` / `package_name=com.android.settings`。其他审批请求会失败并停止本轮；无需审批的 SAFE 工具只能由最终精确账本断言排除，这不是执行前的两工具沙箱。

## 动作、拒绝与页面结果

设备项要求同一 Agent Run 的工具调用序列严格为 `device.snapshot → device.open_app`。批准前对应工具没有结果，实际 active/focused 应用为小灵；实际生产窗口必须为 `TYPE_ACCESSIBILITY_OVERLAY`、title=`XiaoLingDeviceActionApproval`。UiAutomation 点击生产“批准执行”或“拒绝”控件，不称为真实人手触屏验收。

批准项检查 `PASSED / executorVerified=true`、步骤输出中的 `WorkflowDeviceActionDecisionStatus.VERIFIED` 与 `action=open_app`、后置包名 `com.android.settings`，并独立读取实际前台包。之后回到小灵，通过正式 Workflow Run tag 和动作判定 tag 查阅持久结果；回页面不在审批期间发生。

拒绝项检查 Room 审批 DENIED、Workflow/步骤/Agent FAILED、无 open_app 工具结果、持久错误包含“工具未获批准”、实际前台仍为小灵。正式页必须显示该 Run 的历史失败记录和拒绝原因。负向测试通过不等于 Run COMPLETED，也不把拒绝重新执行成成功。

## 失败与夹具修正

- `initial-build-lint.log`：夹具误从 `AgentRunRecord` 读取不存在的 profileId/model，编译失败；改为实际 ProfileSelection/LlmRequest 事件，不改生产事件模型。
- `live-editor-initial`：两步执行检查之后，正式结果 tag 在合并语义树中不可见，JUnit 失败。可点击卡片合并后代，独立 tag 保留于未合并树；修正测试查询，保留实际 Run 与失败报告。
- `live-formal`：只读与批准通过，拒绝在临时 `workflowError` 字段断言失败。正式刷新会清空该字段，改查持久 Run 错误及正式历史失败文本；整轮原 `completed=false` 保持。
- `live-denial-corrected`：内联集合查询抛 `NoSuchElementException`，没有检查点，不能精确确定该次是审批落库间隙还是窗口清单瞬态。源码明确存在 Runtime 先发 WAITING_APPROVAL、Gate 后创建 Room 请求的顺序；夹具改为等待本 Workflow 步骤关联的真实 PENDING，窗口改为等待唯一 active/focused 应用匹配目标包。新日志记录实际 PENDING 身份及窗口检查点，最终拒绝通过；窗口检查仅为一次匹配，不是持续稳定观察。

清理复核还发现只读定义按名称删除的归属风险，已改为正式回读的稳定 ID 加完整定义/步骤快照匹配；尚未确认 ID、定义被修改或未确认 Workflow 停止时保留现场并失败。停止和恢复异常附加到原始失败，不由 finally 覆盖。`live-editor-owned` 的稳定 ID 版本定向通过，最终快照/异常链版本 `live-editor-final` 再次通过并保护 1,054 条旧审计行；批准和拒绝沿用原成功记录，最新清理保护仅在只读项复验。停止确认基于 VM 运行状态，后续另用新进程检查偏好值，不声称逐一 join 所有持久化 Job。这些修改只校正夹具等待、查询和清理，没有修改生产审批、权限、窗口守护或拒绝行为。最终选择及源码/报告边界由机器汇总保留。

## 数据与系统保护

正式 VM 没有通用隔离 Room 注入，因此本轮要求消息、记忆、笔记、候选、知识、Workflow、周期规则和定时任务均为空，只允许无标题/摘要/消息的原默认空会话，并拒绝旧未结束 Run/PENDING 审批。既有 Memory undo 主文件及 AtomicFile `.bak/.new` 任一存在即拒绝，避免启动加载删除损坏内容；既有 Keystore Provider alias 必须已存在，不新增持久密钥。

临时 Provider 使用既有 Keystore 加密，配置由单次长度前缀 USB/ACK 输入，密钥不进 argv、Intent、源码、APK 或日志。按本轮 UUID 配置/会话和精确定义 ID 删除临时正文、Profile、Provider、定义；正式 Workflow Run、持久步骤、Agent Run/Approval/Tool Ledger 全部保留。定义/会话删除不存在这些审计的外键级联，删除后再次核实本次 Run 可回读。

分别核对原 Provider 密文/原 Profile 实体集合、按本轮归属逐键恢复 UI/Room 选择、原 Memory undo 与授权选择。十张审计表按原 rowid 和整行内容摘要核对，旧记录不得删除或改变。所选三项分别保护 `1,054 / 687 / 891` 条本机原审计行，均通过；不保存或输出原行正文。偏好稳定值摘要一致不等于 XML 逐字节相等。正常 finally 的恢复不保证异常进程死亡清理。

instrumentation 退出仍观察到原授权存在、服务 crashed/unbound。文档门禁结束后仅受控重绑原已授权组件，并只读确认最终状态，见 `accessibility-closeout.json`；不作为自然系统恢复证据。

## 证据与待办

主机入口 `tools/verify_android_live_acceptance.py` 的三项 `workflow-*`，夹具 `AndroidWorkflowLiveInstrumentedTest`。证据根 `outputs/verification/stage294-workflow-ui/` 被忽略。`verification-summary.json` 与 `business-evidence-revalidation.json` 从保留报告的 USB ACK 回执及原日志逐项核对 JUnit/case/scope/restore；原失败目录与报告摘要同时记录。构建/Lint、主机 8 项测试、文档语料/偏好保护、两份 APK 与实际凭据审计单列。

- 普通编辑器的限定应用确认入口仍是产品缺口；本轮没有实现它。
- 正式 Workflow 页面中的节点点击、输入、滚动、目标级复杂任务与全部应用流程仍需独立验收。
- 自然 Accessibility/LMK/Doze/系统重启、Remote 稳定部署/真实通知、其他模块完整入口和正式签名 Release 保持独立待办；iOS 产品暂停。

相关：[第 293 阶段生产浮层](android-device-approval-acceptance.md)、[第 292 阶段真实模型](android-live-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
