# Android 滚动方向与边界专项验收

第 298 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、用户授权真实 `gpt-6-luna`、同签名 Debug `0.1.22 (23)`。iOS 产品继续暂停，Room schema 与发布版本不变。

## 当前结果：最新主包四项同轮通过

`live-target-observation` 四项全部通过，配置 ACK、JUnit、精确业务回执与恢复均满足；不是跨轮拼接。正式两项仍由预置限定定义的可见手动运行按钮创建新 Run，左右仍为隔离公开页。

| 项目 | 同轮结果 | 实际证据 |
| --- | --- | --- |
| 正式打开设置 → up → down | 通过，120.17s | Run `workflow-run-7f93b181-bfc6-48fb-8bff-97bade5d7969`；三步九调用、一审批；目标 VERIFIED，首尾内容相等、Activity 重建通过 |
| 正式顶部尝试 down | 边界通过，71.78s | Run `workflow-run-bafde884-e3e5-41e2-ac9a-43425a0432c7`；第一步 COMPLETED、第二步 FAILED；内容/位置未变，目标判定 absent、无 VERIFIED，失败动作后无模型 snapshot |
| 公开页 left | 通过，20.97s | Run `run-386f8c86-b6ea-4980-b85f-c61f684440af`；两调用、零审批、一次 native 动作、准确 scrollX 增量、Executor/PASSED、隔离 Room 重开 |
| 公开页 right | 通过，19.32s | Run `run-1790ee15-4e42-4d93-ad39-6ce527682d92`；两调用、零审批、一次 native 动作、准确 scrollX 减量、Executor/PASSED、隔离 Room 重开 |

两项正式 Workflow 分别保护 2,992 / 3,319 条旧审计行，原 Provider/Profile、当前偏好、Memory undo 与授权选择恢复通过。定向 JVM 五组 53/53，主机门禁 11/11，Debug/AndroidTest/Lint 通过；旧 50 项回归与原失败日志分别保留。独立新进程、最终文档保护、制品凭据与原授权收尾由 `verification-summary.json` 逐项汇总。

最新往返三个 Agent 均未使用完成纠错；顶部首步使用一次既有完成纠错，最终补齐后置 snapshot，失败滚动步未纠错。纠错上限和预算保持，不能扩大为自然恢复的新完成约束已通过。

## 方向契约与生产修正

`device.swipe` 的方向表示可见内容在屏幕上的位移。原四方向策略已经要求 up/down 对应锚点 Y 减少/增加、left/right 对应 X 减少/增加。Android 水平 action 表示滚动位置方向：`ACTION_SCROLL_LEFT` 减小 scrollX、内容右移，`ACTION_SCROLL_RIGHT` 增大 scrollX、内容左移。原服务横向映射与验证器相反，现由统一映射函数修正为 LEFT → ACTION_SCROLL_RIGHT、RIGHT → ACTION_SCROLL_LEFT，工具描述和枚举注释同步明确内容方向。没有坐标回退或手势注入。

已抓取 [AOSP Android 16 HorizontalScrollView 正文](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/widget/HorizontalScrollView.java)，访问于 2026-10-18，blob `0b99df323b0937f360936f4e313b67cd4a965ffb`。关键原文保存在本阶段 `aosp-horizontal-action-evidence.md`。设备真实 action 常量回归通过，不把 fake gateway 的位移测试作为 Android 映射验证。

`open_app` 后置回读原先遇到首个有效窗口就结束；一次真实 Run 中启动返回成功，但 Executor 未观察到目标包，首步 FAILED、边界步骤 CANCELLED。现在只在原六次、100ms 间隔的回读预算内继续等待目标包，启动仅发送一次；授权/隐私失败立即拒绝，耗尽仍返回未验证结果。此改动没有扩大模型、动作、审批、TTL 或工具预算。三项 JVM 回归覆盖延后目标、旧窗口持续与等待期间撤权。没有单独捕获该失败轮目标窗口何时出现，不能将具体系统启动时序写成已确定根因。

## 四项验收边界

正式往返与顶部边界使用 MainActivity 可见手动运行按钮、正式 ViewModel/UseCase/Runtime、生产审批浮层、Accessibility 与正式 Room。限定应用和分步目标契约仍由夹具预置，seed 取消且无 Agent；真正执行的 Run 必须由可见按钮另建，不能计为普通编辑器创建限定应用任务。

往返为打开设置、up、down 三步，每步严格 snapshot → action → snapshot，共九调用。打开设置一次审批，滚动零审批；每次 swipe 重新获取本步 snapshot/ref，通过生产同窗、generation、共同匿名锚点的方向主位移验证，首尾内容集合相等才通过。

顶部边界为打开设置后尝试一次 down。只有第一步 COMPLETED、第二步 FAILED、当前可滚动 ref 的原生拒绝或 Executor 失败、零滚动审批、目标未 VERIFIED、没有执行失败后的模型 snapshot，且终态独立真实 capture 的内容与屏幕位置完全相同，才算边界通过。模型、网络、首步打开失败都不能冒充边界成功。

左右使用随机 nonce 绑定真实 resumed/focus 的公开 HorizontalScrollView、固定公开条目、隔离 Room 和生产 native gateway；起点位于中段属于夹具定位，不计模型动作。每项只允许本次 snapshot/swipe 的最终账本，断言一次 native 动作、零审批、准确半屏 scrollX 位移、Executor/PASSED 与同进程 Room 关闭重开相等。公开页保留共同锚点，不能扩大为任意应用、原生整屏横向列表或正式 Workflow 左右验收。

正式 Run 的独立新进程 inspector 不运行 VM、模型或工具，核对目标、分步契约、调用、审批、结果、定义移除、原配置/偏好与 alias；往返还比较持久首尾 snapshot。顶部物理未移动由执行当轮 capture 证明，独立 inspector 仅复核失败账本，不重放物理边界。

## 原失败与恢复

`live-initial` 正式往返在打开设置审批阶段发生 ART `unused-3f / SIGABRT`，没有滚动调用；后继顶部项被残留 messages 门禁拒绝。公开左右项通过，该轮 completed 仍为 false。原 Run 保留 FAILED、APPROVED 和失败工具结果，不用后续成功覆盖。

将巨型入口中的恢复块拆为独立非内联协程后，`live-recovery-split` 往返通过；其顶部项首步 open_app 的 Executor 回读失败，仍保留 false。没有确定 ART/D8 的底层根因。恢复诊断首次编译的 nullable 状态字段错误已修正，原编译失败日志保留。

首轮原生崩溃跳过 finally 后，先独立只读核对，再按 seed 冻结定义、稳定 UUID、Profile/Provider、Agent 来源消息与完整偏好摘要定向恢复。仅删除本轮配置/定义/正文，保留 Run、审批、Tool Ledger 与原 alias；恢复不重放动作。本轮摘要可由去掉测试选择并补回唯一原默认 Profile 精确匹配，恢复已通过。后续复核进一步要求 assistant 结果携带本轮 Agent 的可信上下文归属，避免只凭相同正文清理。

首轮实际 Room 基线为 `d9e1f42a…`，与第 297 阶段历史 `e8b76d36…` 不同；UI 基线保持空值摘要。安装前未独立捕获 Room 偏好完整值，不确定安装/初始化的具体写入时点。本阶段按首轮记录的实际基线验证恢复，不宣称恢复第 297 阶段历史选择或新安装无 alias 状态。

## 证据与独立待办

证据根为 `outputs/verification/stage298-directional-scroll/`。配置沿一次性 USB/长度前缀/ACK 进入内存，日志落盘前脱敏；各失败目录保持原报告，最终汇总需同时匹配业务回执、JUnit、ACK、独立账本、最终保护和制品审计。

普通编辑器限定应用/目标契约、持续/拼接文本隐私、新完成约束的自然进程恢复、复杂任务/任意 App、Remote 稳定部署与真实通知、Doze/LMK/系统重启和签名 Release 继续独立。动作摘要白名单和实际凭据零命中不代表所有上下文正文隐私通过。仅中文本地提交，不 push/tag/Release，保留 refs。

相关：[单次正式滚动](android-workflow-scroll-acceptance.md)、[分步完成与输入隐私](android-workflow-completion-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
