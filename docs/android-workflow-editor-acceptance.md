# Android 普通工作流编辑器确认专项

第 300 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`，真实模型 `gpt-6-luna`，同签名 Debug `0.1.22 (23)`；Room schema、发布版本不变，iOS 产品暂停。

## 产品入口与保存规则

普通“新建工作流 / 编辑工作流”增加允许范围内的限定应用选择、工作流完成目标、每步完成标准以及完成时应用确认。标准以可读动作选择：读取时间、小灵版本、观察页面，或打开应用 / 点击 / 输入公开文本 / 滚动后复查页面。设备动作组合明确包含本步前后观察；完成时应用只来自显式限定选择，不从目标文字猜包名或工具。

应用、目标、标准、步骤增删或排序变化会撤销确认勾选；排序移动完整步骤草稿，完成标准随对应目标移动。现有不适用于选择器的旧标准不能静默变成无契约，必须重新选择或显式关闭核验并确认。编辑器当前只支持上述有界事实组合，不能编写任意工具契约。完成目标的文字用于记录用户意图，核验口径仍是实际工具事实的顺序与最终应用，不保证任意业务正文条件。

确认对象经 feature actions → `XiaoLingViewModel` → `RoomWorkflowRepository` 完整保存。Repository 在写入前拒绝允许范围外应用、每步标准数量不匹配、最终应用冲突和未限定应用的设备动作标准。活动 Run 不能编辑；历史 Run 使用原冻结输入，后续 Run 读取更新后的定义。旧内部调用未提供新确认对象时，继续保留限定应用并撤销旧完成标准。

没有扩展应用允许列表、Profile / Skill 工具范围、默认调用预算、设备权限或生产审批。未限定应用仍不能仅凭目标文字取得设备动作权限；设备动作审批按生产风险规则执行。

## 本轮验证

完整 JVM 1632/1632，零失败、错误和跳过；主机门禁 13/13。Debug、AndroidTest 构建及 Debug Lint 通过。指定 Redmi 的编辑器与隔离 Room 专项 `OK (6 tests)`：显式确认、文本不授权、排序/确认撤销、不支持的旧标准不降级、冻结历史不变、错位标准不落库及活动 Run 编辑拒绝。

真实页面批准链完成：Run `workflow-run-44ce22c5-32a3-41da-8c9d-29274f865420`、Agent `run-91bca171-f708-4dfb-a901-22283b2e7439`，工具严格为 snapshot → open_app → snapshot，三项 PASSED、一项生产浮层 APPROVED，Executor 后置验证和独立前台包回读均通过；目标结论 VERIFIED / ALL_CRITERIA_VERIFIED。Activity 重建后动作、结果和 Room detail 相同。

真实拒绝链 Run `workflow-run-d30c71bd-744e-4593-b2d1-b7b02878cdd4`、Agent `run-db4bb4e9-442a-425d-8b31-7d04b179ccfa`，只留下 snapshot / open_app 的两个调用、一项 DENIED；没有 open_app 结果和后置 snapshot，Workflow / Step / Agent 均 FAILED，目标未 VERIFIED。重建后持久拒绝原因仍可见。预期拒绝的验收通过不会将失败 Run 改为成功。

两项均从普通编辑器创建并保存，正式编辑入口回填一致；随后只经可见“手动运行”按钮新建实际 Run，没有预置定义或种子 Run。每个 Run 输入都冻结相同限定应用与 v2 每步完成契约，生产 Profile / LLM 事件确认本轮模型。审批由 UiAutomation 点击真实生产浮层，不称为人手触屏。主机要求 USB ACK、JUnit、业务/冻结/重建和完整恢复字段同时成立。

`live-corrected` 同轮三项通过，主机耗时分别为批准 72.40s、拒绝 59.22s、原有两步只读 51.65s，均有 USB ACK 与 JUnit。只读 Run `workflow-run-745a7645-77dc-448f-b9b0-584f945e12f1`，两个 Agent 各调用一次 current_time / get_info，第二步输入保留第一步实际输出，正式结果与 Room 均通过。

两个第 300 阶段 Run 另经独立 instrumentation 进程指定 ID 只读回查：冻结契约、终态、审批与账本一致，完整对象回读前后相同；各 `OK (1 test)`，不启动模型或工具、不重放动作。这不同于自然进程回收后续跑。

正式 VM 验收要求空业务 scope，三项分别保护 3503 / 3625 / 3694 条既有审计行。只有本轮 UUID 配置、正文与完整定义快照匹配时才清理，成功/失败 Run、Step、审批和工具账本保留。每项验证原 Provider 密文/Profile、偏好值、Memory undo、授权选择、alias 与旧审计不变；最终制品/文件摘要、文档语料与服务绑定以本阶段 `verification-summary.json` 为准。

## 失败记录与边界

`editor-room-initial.log` 的新 Room 测试把取消前 PENDING 对象与取消后 CANCELLED 对象比较，断言失败；冻结输入一致。改为取消后的完整记录作编辑基线，并独立核对输入快照未变；`editor-room-corrected.log` 六项通过。

`live-initial` 的两项在编辑回填前置断言失败：按钮完整文字为“限定应用：系统设置”，夹具用“系统设置”作完整匹配。没有创建实际执行 Run、调用模型或执行动作；两项均有恢复回执，原 `completed=false` 保留。修正为完整文案，不改变生产保存或权限逻辑。

自然 LMK / Doze / 系统重启、跨 Run 输入隐私、复杂任务与任意 App、Remote 稳定部署/真实通知、签名 Release 仍独立追踪。Activity 重建或受控重绑不替代自然系统恢复。仅本地中文提交，不推送、打标签或发布，保留无关 `refs/`。

相关：[正式 Workflow 入口](android-workflow-acceptance.md)、[恢复与输入隐私](android-recovery-input-privacy-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
