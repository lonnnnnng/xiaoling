# Android 正式 Workflow 滚动专项验收

第 297 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、用户指定真实 `gpt-6-luna`、同签名 Debug `0.1.22 (23)`；生产 main、Room schema 和发布版本未变，iOS 产品继续暂停。

## 当前结果：打开设置与单次向上滚动同轮通过

完整通过轮为 `live-navigation`，主机耗时 88.10s、USB 配置 ACK 已记录、JUnit `OK (1 test)`。正式 MainActivity 可见“手动运行”按钮创建 `workflow-run-eb9bbf5a-a183-4ed9-a74e-f8f42055816d`，经过正式 ViewModel、UseCase、Runtime、生产审批浮层、Accessibility 和正式 Room。

| 步骤 | Agent Run | 本轮工具顺序 | 审批 |
| --- | --- | --- | --- |
| 打开设置 | `run-2e8bc91f-1794-4dfa-af67-d3388346e4f5` | snapshot → open_app → snapshot | 一次 APPROVED |
| 向上滚动设置列表一次 | `run-31bc6914-8fab-4895-84d6-92fee2bb294b` | snapshot → swipe → snapshot | 零审批 |

两个 Agent 均 COMPLETED，六条结果全部成功/PASSED，两个动作 `executorVerified=true`，两条步骤动作判定 VERIFIED。目标为 `VERIFIED / ALL_CRITERIA_VERIFIED`，冻结总体工具顺序与两组分步契约全部匹配，最终实际前台为 `com.android.settings`。

首步真实模型使用一次 `LLM_PREMATURE_COMPLETE_RETRIED`，最终补齐后置观察；滚动步骤未使用纠错。原四调用预算及一次纠错上限保持，未修改生产 Runtime 或模型返回。该证据补充了第 296 阶段新鲜前台完成约束的真实使用，不代表自然进程恢复已验收。

回小灵后可见两步动作与本 Run 结果，Activity 重建后结果、滚动动作及 Room 目标相同。独立新进程只读回查同一 Run、取消且无 Agent 的 seed、六项结果和审批，临时定义已移除，原 Provider/Profile 各一条，偏好稳定值相同；未重放模型或工具。

## 滚动验证与范围

本轮使用系统设置的真实列表，未使用半屏公开 Probe、脚本模型、坐标注入或自定义 gateway。`device.swipe` 保持 SAFE；测试仅通过生产浮层批准精确 `open_app(package_name=com.android.settings)`，滚动 Agent 不产生审批。

滚动参数必须且仅为本步 `snapshot_id/ref/direction`，方向为 `up`，选中节点必须声明 SWIPE。生产 Controller 和 Registry 原有门禁要求动作前 viewport、当前目标身份、同包同窗、generation 前进、可见匿名锚点集合变化及共同锚点按请求方向稳定移动；PASSED 由这条生产链产生，不能仅凭节点动作返回 true 或模型 complete 判成功。

夹具另外从同一 Agent 的持久前后 snapshot 检查本步引用身份、同包同窗、generation 增长和显示文本集合变化，只输出布尔结果。滚动动作结果经过严格白名单 codec 解码，不能含 viewport、anchors、fingerprint、snapshot_id、ref 或 nodes。`actionSummaryOnly=true` **仅表示动作结果摘要的字段检查**；未把它扩大为所有事件、context 或业务正文的全局隐私证明。执行期完整 HMAC 锚点仍按既有生产机制只在当前链路内存传递，本轮没有独立导出这些锚点。

限定应用定义、目标契约和两个步骤由夹具预置；seed `workflow-run-36d654e3-0567-43d3-8480-e15de47eb1b3` 取消保留，真正验收 Run 必须由可见按钮另建。普通编辑器当前没有限定应用/目标契约字段，不能把本轮记为编辑器完整创建链。

本轮只验收设置首页一次 `up`，没有验证 down/left/right、列表边界、任意 App 或完整复杂任务。SAFE 工具的精确六调用由最终账本断言约束，不是执行前只允许两种动作的额外沙箱。

## 原失败与导航夹具

`live-initial` 主机耗时 21.78s，USB ACK 成功，但底栏点击后的正式入口等待报 `ComposeTimeoutException`，没有实际运行 Run、模型请求或设备动作；仅留下取消且无 Agent 的 seed。本轮原配置、偏好和 2,269 条旧审计恢复通过，原 report.json 保持 false。

夹具原先在进入 Workflow 页后才收起键盘。现调整为先等待小灵 active/focused、收起输入法，再点击可见且可用的底栏；失败诊断仅报告固定公开入口的节点数，不保存完整语义树或配置正文。第二轮导航通过，**没有独立确定首轮超时的具体原因**，不能将推测的键盘/焦点问题写成已证实根因。

## 验证、数据保护与证据

- 定向 JVM 五组 164/164，零失败/错误/跳过；主机门禁 10/10，覆盖错误方向、滚动审批、未变化内容、旧阶段回执、缺恢复和 USB ACK 拒绝。Debug、AndroidTest APK 与 Debug Lint 通过；未把上一阶段全量 1615 项记为本轮重跑。
- 成功轮保护 2,272 条旧审计行，原 Provider/Profile 集合完整相等，Memory undo absent，原无障碍选择相等；只清理本轮 UUID 配置、正文和冻结定义，成功/失败 Run、步骤、审批和 Tool Ledger 全保留。
- 偏好基线沿用第 295 阶段崩溃夹具恢复后的状态，既有空 alias 保留。本轮未恢复最初新安装无密钥状态或崩溃前全部偏好；独立 inspector 适用于本机原有一组 Provider/Profile 和已存在 alias 的基线，未验收新安装无 alias 场景。
- 证据根 `outputs/verification/stage297-workflow-scroll/`：原始两轮报告、独立回读、机器复判、最终文档语料/偏好保护、APK 文档字节与实际凭据检查分别保存。前两阶段 12 份报告及日志字节保持，其中 11 份失败不改写。
- 最后 instrumentation 后仅按本轮起始已授权组件和精确选择摘要受控重绑，再只读确认 authorized/bound=true、crashed=false；这是收尾恢复，不是自然服务恢复。本轮没有 force-stop 小灵主包。

独立待办：普通编辑器限定应用/目标契约、其他滚动方向及边界、复杂任务、持续/拼接文本隐私、新完成约束自然进程恢复、新安装 alias 正常撤销、Remote 稳定部署/真实通知、Doze/LMK/重启及签名 Release。仅中文本地提交，不 push/tag/Release，保留 refs。

相关：[分步完成与输入隐私](android-workflow-completion-acceptance.md)、[正式 Workflow 入口](android-workflow-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
