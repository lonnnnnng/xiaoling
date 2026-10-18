# Android 设备审批浮层专项验收

第 293 阶段，2026-10-18。仅使用 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、本机授权的真实 `gpt-6-luna` 和同签名 Debug `0.1.22 (23)`。本轮不继续 iOS 产品工作。

## 验收结果与范围

用户停止操作手机后，`live-unattended` **同一轮四项全部通过**；另有点击诊断版的拒绝单项通过。成功必须同时具有单次 USB 配置 ACK、JUnit `OK (1 test)`、对应动作/决定业务证据和恢复回执，不能只看 ADB 退出零。

| 项目 | Run | 审批 / Runtime 终态 | 节点动作调用尝试数 |
| --- | --- | --- | --- |
| 批准点击 | `run-0ba345f2-9a2b-4db1-b813-66359a71445a` | APPROVED / COMPLETED | 1 |
| 批准输入 | `run-0b484e65-6aec-439a-a235-bb306575a9c9` | APPROVED / COMPLETED | 1 |
| 拒绝点击 | `run-e64d3d9e-cc52-45b2-99c6-dccec0ac0802` | DENIED / FAILED | 0 |
| 窗口变化撤销 | `run-744d08ee-7494-43d0-bbed-967130fc807d` | CANCELLED / FAILED | 0 |

拒绝和窗口撤销后，生产 Runtime 抛出 `工具未获批准：…` 并持久化 FAILED 是本项预期行为，JUnit 与主机专项判定通过不意味着这些 Run 是 COMPLETED。

调用链为真实模型 → `MinimalAgentRuntime` → `WorkflowDeviceActionApprovalGate` → 生产 Accessibility Service 浮层/动作 → UUID 隔离 Room。验收通过 UiAutomation 点击实际浮层中的“批准执行”或“拒绝”，窗口撤销通过启动另一个公开 nonce Probe 触发。实际窗口必须为 `TYPE_ACCESSIBILITY_OVERLAY`、title=`XiaoLingDeviceActionApproval`，不能用测试自绘审批控件替代。

**本轮未经过正式 Workflow 管理页面、`XiaoLingViewModel` 或 `AgentRunUseCase` 的完整入口。** 生产浮层可见控件与底层 Runtime 联合通过，不提升为全部 Workflow 产品流程通过；也不代表真实人手触屏的独立验收。

## 硬断言与诊断

- Probe 采集和动作限定本轮 nonce、实际 resumed、窗口焦点及本包公开页面。点击只允许“测试按钮”，输入只允许普通编辑框和本轮随机八字公开 marker。动作执行前仍检查 nonce 归属，并复用生产 generation/fingerprint/Executor 验证。
- PENDING 时同时检查本轮动作调用计数为零、对应工具无结果、确切 Probe Activity 实例的实际 View 未改变；浮层可见后再次核实零调用、零结果与前台归属。
- 批准后要求同一 approval id/toolCallId、完整 `device.snapshot → action` 序列、`PASSED`、`executorVerified=true`、一次节点动作尝试及实际 View 回读一致。拒绝/窗口撤销要求无动作结果、零尝试、原 View 不变，浮层最终消失。
- 输入原文不得出现在浮层、审批参数或工具账本；浮层只展示长度，持久审批核对 `text_length=8` 与 `text_sha256`。公开文字存在显式用户目标中，不把这一点误报为工具泄露。
- Debug 公开 Probe 增加点击次数和状态变更的最多 30 帧 `className#methodName` 诊断，不记录参数或用户窗口。它是诊断输出，点击次数本身不是新增独立 JUnit 断言；硬门禁仍来自节点动作计数、实际 View 和生产验证。

最终点击日志在 initial/pending 为 `clicks=0 mutationTrace=none`，批准后为 `clicks=1` 且包含 `performAccessibilityActionClick / performAccessibilityActionUiThread`。最终拒绝和窗口撤销的 initial/pending/结束均为 `clicks=0 mutationTrace=none`，本轮未观察到审批前状态漂移。

## 失败历史保留

| 原报告目录 | 批准点击 | 批准输入 | 拒绝点击 | 窗口撤销 |
| --- | --- | --- | --- | --- |
| `live-initial` | PASS | PASS | FAIL | FAIL |
| `live-corrected` | FAIL | PASS | PASS | FAIL |
| `live-probe-bound` | FAIL | PASS | FAIL | PASS |

首轮拒绝用例没有收集生产 Runtime 的预期异常，async 子任务取消父测试而中断后续断言。修正为收集 `runCatching` 结果，再核对异常、Room 决定及零动作；没有改变生产拒绝行为。

早期多次观察到公开按钮已变为“动作已完成”，但本轮 gateway 节点动作调用计数为零。先后调整页面回读方式并固定确切 Probe 实例仍有失败，因此不能只归因于 Activity 实例选择或 Accessibility 根。用户随后明确告知当时正在操作手机，并在重新验收前停止操作；这是已确认的测试干扰条件。早期没有点击来源栈，不能把所有旧状态漂移逐次确定归因于人工点击，也不能用本轮成功倒推旧失败已经解释。三个原报告仍保持 `completed=false`，没有覆盖或删除。

## 配置、数据与系统状态

配置由长度前缀、单次 USB socket 和 ACK 输入测试内存，直接网络关闭 HTTP debug；密钥不进入 argv、Intent、源码、APK、主机配置文件或日志。沿用第 292 阶段默认参数，仅显式 stage293 才进入本专项。主机输出目录必须为空；新增门禁单测覆盖错决定和缺恢复，即使 JUnit 成功也拒绝通过。

UUID Room 保留本轮公开 Run/Approval/Tool Ledger 供审计；正式 Provider 与数据不重写，不卸载、不清应用数据。只临时修改两个设备 Agent/免审批偏好键，并按归属逐键恢复。四项均核对全部原 UI 偏好、正式 Room 选择偏好、Memory undo 和原系统授权选择。

最终四项恢复回执一致：UI 稳定值摘要为 `e3b0c442…`，Room 选择稳定值摘要为 `2ddf3214…`，Memory undo 为 `absent`。稳定值相等不等于 XML 文件逐字节相等。异常进程死亡不保证 finally 清理。

测试只在用户原已授权小灵时重绑原组件，写前检查选择归属并保留其他服务，不新增系统授权。instrumentation 退出后仍观察到原组件 enabled、bound 为空、crashed；这是待保留的系统现象。文档门禁结束后按原授权选择受控重绑并只读核实最终状态，结果见 `accessibility-closeout.json`；这一操作不构成自然服务恢复证据。

## 证据与独立待办

入口为 `tools/verify_android_live_acceptance.py` 的四项 `overlay-*`，设备夹具为 `AndroidDeviceApprovalLiveInstrumentedTest`。证据根 `outputs/verification/stage293-device-approval/` 被忽略，包含各轮原报告、诊断栈、构建/Lint、主机 7 项门禁、文档语料与系统状态。`verification-summary.json` 重新核对保留日志并保存原报告摘要，`artifact-audit-closeout.json` 核对两项实际凭据零匹配、APK 文档资产字节和本地链接。

- 正式 Workflow 页面发起到 ViewModel/UseCase、实际生产目标应用动作及自然 Accessibility 服务恢复仍需单独验收。
- Remote Channel 稳定部署、真实通知/导航、长期 Doze/LMK/系统重启保持独立待办。
- 联系人、日历、通知、语音/TTS、全部分享格式与各模块全部正式入口未因本专项自动通过。
- 正式签名 Release、推送、标签与发布不在本轮范围；iOS 产品继续暂停。

相关：[第 292 阶段真实模型验收](android-live-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
