# Android 可靠性修复与 M1 推进记录

当前工作基于 `44f3961` / v0.1.23，保留进入任务前已有的路线图、模板和 `refs/` 改动。当前主线为 M1，后续 M2–M4 仍按 Android 路线图推进；本记录不代表全部路线图完成。

> 发布收口：本轮变更已升级至 v0.1.24 / code25，App 1671 项及发布构建门禁通过，指定 POCO 已覆盖安装，设备 APK 与正式产物哈希一致。最终发布状态与验收边界见[发布记录](android-v0.1.24-release.md)。下方候选哈希、旧模型、失败和“尚未发布”等表述均是过程快照；当前只按用户指令交付本次发布，不自动开展后续阶段。

## 13:26 起：全新安装与 DeepSeek 验收

- 用户确认 `gpt-6-luna` 已不可用，指定后续统一使用 `deepseek-v4.1-flash`，并主动卸载旧验证 App，要求重新安装配置。13:26:17 安装正式候选 `b910708fd65e37006bdb3f7ab6d3e6832677b74c49b1b33321e2ba6a5e399825`；此前 3 条 Run 和配置是旧安装的历史证据，不代表新安装仍持有它们。
- 网络必须使用手机原有 FlClash VPN 和提供方 IP 白名单出口。已撤销临时 USB reverse `17890`，清除 `http_proxy`、`global_http_proxy_host/port/exclusion_list`、`global_proxy_pac_url`，五字段回读均为 null，reverse 为空。此前仅删除 `http_proxy` 遗留 host/port，导致应用无法连接，已纠正；不再使用 USB 临时代理。
- 全新普通入口配置仅一个 Provider `M1-POCO`，只启用 `deepseek-v4.1-flash`；一个 `M1-DEVICE` Profile 使用 Chat、73 工具/44 Skill、记忆关闭。项目说明与 Android 实时验收夹具同步该模型，旧 Run 的模型身份不改写。
- 13:32 普通聊天收到 `M1-DEEPSEEK-OK`，界面耗时 3.41 s，截图 `/tmp/xiaoling-m1-deepseek-chat.png`。13:33 SAFE 查询执行 `app.current_time`，返回 `2026-11-11 13:33:18 / Asia/Shanghai`，验证通过并完成，当时无障碍仍为 0；截图 `/tmp/xiaoling-m1-deepseek-safe.png`。
- 随后通过系统页面启用设备观察。第一次计划生成返回“输出被截断，请调高 max tokens”，未创建执行 Run；规划入口额外硬限 2000 已改为沿用 Provider 统一上限。随后补充提示，明确步骤只能含字符串 `goal`，保持严格 schema/解析门禁；相关规划测试 53 项与后续 PersonalTask 40 项、Lint、Release 均通过。13:51 候选 `4f71796a0e115ac566ceee813e06444ade740771faea011013d9b9fb2f2dd9ab` 已完成计算器真实链。
- 新安装审计来自普通入口导出 `/sdcard/Download/xiaoling-backup-20261111-143314.zip`，主机 `/tmp/xiaoling-m1-deepseek-143314.zip`；SQLite integrity=ok，共 7 个终态 Run，无遗留活动 Run。SAFE Run 为 `run-7689e08c-36e1-4d4b-94c6-9838910d9a00`，`app.current_time/PASSED`。主动拒绝 Run `run-701ef152-2b8b-4823-b4c7-1ee0d8b9aacc` 为 FAILED，审批 DENIED；待审批停止 Run `run-b68811d9-235c-4945-b774-058acc59d982` 为 CANCELLED，审批 CANCELLED。两者均无 `device.open_app` 结果、活动步骤或待定审批。
- 计算器 Workflow `workflow-run-3036871a-6e51-4a3b-872d-644978c7da75` 为 COMPLETED / `ALL_CRITERIA_VERIFIED`，2/2 步、限定与实际最终应用均为 `com.android.calculator2`，匹配 `device.open_app → device.snapshot`。步骤 Run `run-dd56e934-f45a-4271-bda4-a4b789c45a5a`、`run-cd1d80e2-3214-4e4b-a339-0e103a4be261` 均 COMPLETED；打开动作 executorVerified=true、PASSED，并有实际前台截图独立回读。
- 设置搜索旧 Workflow `workflow-run-a81e830b-b120-4238-8584-4bc7b9a994a0` 正确保留为 FAILED：等待人工审批过久，`device.tap_ref` 因快照过期被拒，后续步骤取消，没有输入。之后一次重试在审批期页面变化被取消，也不计通过。
- 14:15 普通聊天曾读超时；现场五项全局代理均 null、ADB forward/reverse 均为空，FlClash VPN CONNECTED/VALIDATED。手机经其本地 `127.0.0.1:7890` 请求模型列表 200，目标模型存在；后续流式和非流式最小请求均 200，14:37 正式 App 返回 `M1-FLCLASH-APP-OK`（6.08 s）。超时根因未确定，没有据此更改代理或延长超时。

### 设备动作默认连续执行

用户要求免逐次审批默认开启、不再卡在每步批准。`UiPreferenceStore` 缺省值改为 true；暂停设备 Agent 只让授权暂不生效，不覆盖该偏好，再启用沿用选择；手动关闭免审仍会保存。前台已确认应用内的打开、点击和普通文本输入沿用既有设置授权，保留动作审计、短期观察、窗口守护及后置验证。

- 9 项 `WorkflowDeviceActionApprovalGateTest`、Lint、Release 通过；日志 `/tmp/xiaoling-m1-autoapproval-default-gate.log`。
- 候选 v0.1.23/code24，SHA-256 `1deaa049f5a6e45d20dabeb799bdb6a0dc7c1b682d4b2c281fe069200400bf7e`，固定正式证书与 16 KiB 对齐通过；覆盖安装保留数据。
- 普通设置显示“默认开启”、开关开启；暂停/再启用设备 Agent 后仍开启。随后重跑已不等待人工审批，但暴露设置搜索伴随包的提示/回读不一致；修复及成功闭环见下文。未发布或提交本轮变更。

### 设置搜索伴随包提示与打开结果修复（17:30 验收收口）

15:29 失败 Run `run-1e94cfb7-c6c1-46f9-acb6-a6bacbf87911` 的快照来自 `com.android.settings.intelligence`，搜索框有 `r2 / type_text`、未脱敏也未截断；模型却选择 `device.back`，未通过 Executor。执行层早已允许该伴随窗口，但 `WorkflowStepPromptPolicy` 只告知主包限制，遗漏了受控流程窗口说明。提示现从 `DeviceActionPolicy.appFlowPackageCandidates` 读取与执行门禁相同的名单，明确可继续观察、点击和输入。

首轮提示修复回验又发现：请求打开主设置包时，系统会恢复上次已有的搜索 Activity，而 Executor、Workflow 完成门禁和答案级证据仍只接受主包/OEM 等价包。三处已统一接受本次主包已登记的流程窗口；启动白名单、启动候选及请求目标校验保持主应用边界，不能直接启动伴随包。该验证只确认应用流程已打开，不等同设置首页或搜索目标完成。

- 78 项定向 JVM（步骤提示、应用包边界、Executor、Workflow 安全门禁、动作证据和目标验证）、Lint、Release、AndroidTest Kotlin 编译通过，日志 `/tmp/xiaoling-m1-settings-restored-window-gate.log`。新增测试覆盖主设置恢复搜索页、拒绝直接请求伴随包、拒绝其他应用请求落到设置搜索页。
- 当前正式候选仍为 v0.1.23/code24，SHA-256 `986927c166ed47b7e0c050159534359470fed90515ccdc749dc2fdfef65c87d3`；固定证书与 16 KiB 对齐通过，已覆盖安装 POCO，配置及旧审计保留。
- 17:06 普通直接 Agent 请求 `device.open_app(com.android.settings)`，系统恢复 `com.android.settings.intelligence`；Run `run-73ed3e26-7f5a-415a-8399-4d0d641992cb` COMPLETED，Executor=true / PASSED，截图 `/tmp/xiaoling-m1-restored-open-result.png`。
- 随后从设置首页重新发起普通模板，确认四步计划后连续执行打开、点击搜索、输入 `Wi-Fi`、读取结果。独立屏幕回读显示“Wi-Fi 扫描调节”“启用 Wi-Fi 详细日志记录功能”等结果，截图 `/tmp/xiaoling-m1-companion-full-now.png`；小灵显示目标已验证完成 4/4，截图 `/tmp/xiaoling-m1-companion-full-result.png`。
- 普通入口导出 `/sdcard/Download/xiaoling-backup-20261111-173038.zip`，主机 `/tmp/xiaoling-m1-settings-companion-final.zip`，SQLite integrity=ok，无遗留活动 Run。Workflow `workflow-run-63fd377c-4017-45c7-a658-acab4fa57479` COMPLETED / VERIFIED / ALL_CRITERIA_VERIFIED，实际最终包为登记的搜索伴随包。要求与匹配工具均为 `device.open_app → device.snapshot → device.tap_ref → device.type_text → device.snapshot`。
- 四步 Run：`run-b8e441cd-79ed-4d6b-925a-9d84e89c6a23`、`run-c3e998eb-d9e1-46ed-b99c-c145b278cb7b`、`run-b2566496-3f35-4966-979e-af87acb3d536`、`run-7ede44aa-99e0-44c0-9212-c053a81dae84`，均 COMPLETED。打开/点击/输入 Executor=true、PASSED；三次审批均为设置授权来源 APPROVED，无人工逐次点击。
- 输入 ToolCall 与 Approval 只含 `snapshot_id/ref/text_length/text_sha256`，无 `text` 原文，长度5、摘要与公开验收输入一致；同 Run 输入后的 24 节点快照无 text/description/hint。后续独立读取步骤是新 Run，可读取公开搜索结果；不将此证据扩大为跨 Run 输入隐私。

本次设置搜索缺陷已修复并真机通过；M1 其余退出项及发布仍按清单单独收口，不将本次修复等同完整路线图完成。

## 当前设备与验证边界

- 指定真机为 POCO F2 Pro，serial `31936bb6`，device `lmi`，Android API 36。旧文档的 Redmi 是历史证据，不再用于当前开发测试。
- 新的备份测试使用独立 Room 文件与独立 WorkManager 任务；没有替换用户正式数据库。
- 新的输入回归只操作小灵 Debug 公开 Probe，固定非敏感字符串，不依赖模型或用户应用数据。
- 正式包、真实模型与普通入口联合验收仍需按 M1 八项清单逐项完成，不能由 JVM 或隔离夹具替代。

## 已实现的可靠性修复

1. `RemoteChannelPublicDns` 将地址策略拒绝转换为 `UnknownHostException`，异步 OkHttp 请求可正常失败，不再抛出线程未捕获策略异常。保持实际建连 DNS、禁代理与公网地址门禁。
2. 启动按 unique work 回读真实调度绑定，缺失/结束的旧绑定重新入队；领取与绑定竞争时不改写 RUNNING/终态。入队失败保留等待记录供后续对账，显示提示。
3. 备份先在临时副本执行版本一致性、SQLite integrity/foreign key、Room 实际 schema 与迁移验证，再清除恢复库的待执行任务旧绑定。候选库在下次首次打开数据库前原子激活，保留安全副本和安装标记；首次打开失败或未完成激活的进程中断走回滚。文件内容与目录变更均同步。恢复结果通过启动提示告知。
4. 普通聊天的事件投影和流式缓冲抽离至 `ConversationSendPresentation`，界面状态与消息模型分别移至 `XiaoLingUiState` / `ConversationModels`；Skill 管理动作进一步抽离至 `AgentSkillManagementPresentation`。包含新增 M1 接线后，主 ViewModel 从基线 7048 行减为 6506 行。切换/新建/删除前收敛部分正文并安排保存，再失效发送身份；主线程按身份拒绝迟到回调，切回前等待最新保存。未完成正文排除出上下文。
5. 独立解析生成终态。流式 failed/incomplete/error、缺完成事件的 EOF、完成后继续修改正文均失败；保留已投递的部分文本，不进入 Completed/记忆候选。非流式显式失败/截断同样拒绝。
6. 输入验证在同一窗口、原节点路径、未脱敏的可编辑节点上临时回读原文，避免展示快照的空白压缩/截断造成误判。原文不加入公共 snapshot 或编码审计。无事件时先验证实际结果，成功立即结束，迟到结果继续有界回读。

## M1 第一片

已接入常用任务可用性投影、菜单原因和配置入口、打开/恢复前台刷新、选择时重读及模板目标发送前复核。仅使用有限诊断状态；配置中的密钥不进入诊断对象。选择模板只填草稿，不调用模型、创建 Workflow/Run 或执行设备动作。

POCO 上已验证菜单诊断、配置入口、只填草稿及真实 Provider 普通/流式响应；这些是 Debug 组件/客户端证据。后续仍需完成当前正式包八项联合验收、任务阶段/审批反馈复核和增量发布；然后进入 M2 高频任务组合。M3 文件交付与 M4 长上下文仍未实施。

## M1 第二片：任务反馈与停止

任务中心增加普通 Run 的停止入口和当前步骤摘要，按持久步骤的状态与序号选择当前步骤；终态不提供停止，取消意图未结算时显示“正在停止”并禁用重复操作。child 批次继续使用既有父级停止入口，避免停止单个 child 连带取消兄弟任务。

运行时五类执行/恢复入口监听各自 Run 的持久取消，模型和审批等待可及时结束；审批返回后及 Executor 前再次核对取消。进程内执行登记用于区分仍在执行与无执行者的恢复记录：前者先写取消意图，由 Runtime 保存最后一次耗时再进入取消终态；后者直接关闭活动步骤和审批。停止旧历史任务的回读不会替换同会话后来创建的 Run。所有停止均按 Run ID 处理，不取消其他会话的全局发送 Job。

停止入口通过 `AgentRunStopCoordinator` 回读持久归属。后台步骤停止先走原 Task `STOP_REQUESTED`、系统 Work 取消与兜底联合结算；无执行者的恢复审批直接结算关联 Workflow 未完成后缀。已切换步骤时，旧 Step 仍可定位同一后台 Task。完成提交改为 Room 事务内 CAS 并返回真实写入结果；停止栅栏拒绝完成时 Runtime 抛取消，不能向 Workflow 返回成功。执行 scope 退出前补齐未结算取消，保留预算与既有终态。

- 本次完整 App 单测 **1660/1660**、lint、Debug/AndroidTest/正式 Release 构建通过；日志 `/tmp/xiaoling-m1-stop-final-gate-rerun.log`。原 2 GiB Gradle 堆因 GC thrashing 退出，单次 `-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8` 重跑通过，没有修改全局配置。
- POCO 任务中心按钮/重组、Room 目标隔离及实际运行时取消预算收尾 **8/8** 通过；日志 `/tmp/xiaoling-m1-stop-poco-final.log`。新测试确认模型等待收到持久取消后结束、活动 Step 关闭、真实耗时先于取消终态保存。
- 正式签名候选 APK 已更新，仍为 v0.1.23/code24；SHA-256 `4e6554acc3ef5b266ba538c2652d7c50d61251fe9220729b6e90617a554c4ffe`，正式证书与 v0.1.23 相同，`apksigner`、`zipalign -P 16` 通过。
- 用户已授权卸载 Debug 切换正式候选，并明确后续本项目测试所需卸载可直接执行。已在 `31936bb6` 卸载本轮 Debug/测试包、安装并启动正式候选，应用无 DEBUGGABLE 标记。
- 正式包普通入口当前已确认：缺模型菜单提供明确诊断和配置入口；从提供方页面真实获取模型、仅启用 `gpt-6-luna`、保存后普通聊天返回 `M1-POCO-READ`。后续 Profile、SAFE、设备任务、审批/停止和同 Run 重建回读仍在验收，未将八项清单标为全部完成。

### 正式候选普通入口验收（持续进行）

- Provider/模型通过普通设置输入、真实获取和勾选后保存。普通聊天收到 `M1-POCO-READ`，截图 `/tmp/xiaoling-m1-release-chat.png`。
- 普通 Profile 编辑器新建 `M1-SAFE`，仅 `app.current_time`、Skill 0、记忆关闭。未授权设备服务时从聊天发送 `/agent` 查询，Run `run-a9d0f0f3-3e89-42c7-ac07-5fe70eec974c` 完成，1 次工具、0 次审批，时间 `2026-11-11 03:01:12 / Asia/Shanghai`，工具账本 verified。系统授权仍为 `enabled_accessibility_services=null / accessibility_enabled=0`。
- 旋转引发 Activity 重建后返回主界面，重新进入任务中心可见同一 Run、结果、1 个 Run 与 0 次审批；原会话/提供方仍在。恢复原系统旋转配置 `accelerometer_rotation=1 / user_rotation=0`。截图 `/tmp/xiaoling-m1-release-safe-task-center.png`。
- 正式包诊断已观察：缺模型→提供方入口；缺工具→Profile 入口；设备 Agent 关闭→开启提示；开启后无系统授权→授权失效提示；天气无登记包→当前不支持。此前任务中心仍只有上述 SAFE Run，未产生诊断任务。
- 普通编辑器保存 `M1-DEVICE`（默认 73 工具/44 Skill、记忆关闭），用于后续受控设备用例；从系统页面授权小灵设备观察后，App 显示服务正常、系统 Bound services 可见且 Crashed services 为空。验收结束需要恢复本轮前的关闭状态。
- 计算器普通入口已完成两次计划与执行尝试，但尚未成功；失败原因、后续停止/导出回读进展与剩余项见下方记录。

### 真实验收暴露的导出一致性问题及修复

正式包的普通导出入口产生了可复现差异：任务中心已显示 Run `run-98c151d3-5621-40a8-af3c-036f57424081` 失败，旧备份却仍为 THINKING。旧导出未读取 checkpoint 游标，且在在线写入期间直接复制主文件，不能证明 WAL 中的最新事务已进入归档。

导出按设备实际 SQLite 版本选择快照路径：3.27 起使用 `VACUUM main INTO ?`；更旧版本使用独立目标连接 ATTACH 源库，在一个 IMMEDIATE 事务中复制结构和数据。业务行通过 SQLite 内部 `INSERT … SELECT` 复制，避免大文档超过 CursorWindow；保留 rowid、版本，重建 FTS4 索引，复制完数据后才创建索引/视图/触发器。原 Room 连接不参与 ATTACH，兼容路径复制期间会短暂占用源库写锁。两种路径均在关闭快照后 sync，执行版本、integrity/foreign key 检查，再归档；不再复制在线主文件，也不再以旧 SQLite 为由拒绝导出。

- POCO 独立宿主 `com.longdev.xiaoling.releasecheck` 的 WAL 导出与恢复/调度专项 **5/5** 通过，日志 `/tmp/xiaoling-m1-export-wal-poco-rerun.log`。夹具强制最新失败状态只在 WAL 中，导出包含最新状态与事件，独立 Room 能打开，原库继续可写。
- 导出修复后的 lint/正式包构建通过：`/tmp/xiaoling-m1-export-release-gate.log`。此前 1660 项 JVM 回归结果继续适用于停止链；本次导出增量通过上述 5 项 Android 专项验证，没有重复宣称重跑了完整 JVM。
- WAL 修复时正式候选：v0.1.23/code24，SHA-256 `a78a75374fc03399fe4d4ff5038eaf965d430543871589b27236d90cbbaea902`，正式证书不变，签名/16 KiB 对齐通过。已覆盖升级 POCO 现有正式安装并保留配置和审计；尚未发布或打 tag。
- 正式包普通导出再次通过：`/sdcard/Download/xiaoling-backup-20261111-032837.zip`，主机证据 `/tmp/xiaoling-m1-release-audit-fixed.zip`。独立 SQLite integrity 为 ok，3 条 Run 分别为 COMPLETED/FAILED/FAILED，未完成数为 0；原问题 Run 已为 FAILED，SAFE Run 与结果保留。归档数据库中未发现测试 API Key 明文。
- 正式包普通模型等待停止通过：截图 `/tmp/xiaoling-m1-release-stop-start2.png` 为真实发送后的加载/停止按钮，`/tmp/xiaoling-m1-release-stop-result2.png` 显示“已停止生成”。升级及归档回读仍保留该用户消息和停止消息，没有追加 assistant 回答。第一次坐标未形成有效发送的试验不计入通过。
- 独立测试宿主及测试包已移除。小灵无障碍通过系统页面关闭，恢复本轮前的无设备服务授权状态；正式配置、3 条 Run 及失败原因保留。

### M1 当前未完成项与外部条件

| 清单项 | 当前证据与缺口 |
| --- | --- |
| 普通配置与对话 | 已通过正式包普通入口；Profile 配置可回读 |
| 缺配置诊断 | 无模型、缺工具、缺目标包、应用开关关闭、无系统授权已验证；04:27 已补验已授权但未连接，修复系统列表被抑制时的授权误报，未创建任务 |
| 计算器任务 | 未通过：首次 Run `run-696071d0-8b9b-4370-a2ce-830680233dd6` 被 XML 抓取工具临时断开无障碍，动作审批取消，open_app 未执行；第二次 Run `run-98c151d3-5621-40a8-af3c-036f57424081` 在模型规划收到上游 HTTP 401，没有工具结果 |
| 普通编辑器设置搜索 | 未完成，等待真实模型上游恢复 |
| SAFE 本地查询 | 已通过；Run `run-a9d0f0f3-3e89-42c7-ac07-5fe70eec974c`，app.current_time/PASSED，0 审批 |
| 主动拒绝动作审批 | 未完成；服务断开自动取消不能算用户拒绝 |
| 停止等待 | 普通模型请求停止已通过；真实 Agent 待审批停止尚未完成，JVM/Room 定向证据不能替代它 |
| Activity 重建/数据回读 | 同一 SAFE Run、结果与配置回读已通过；升级后及正式导出再次确认审计身份保持 |

第二次设备用例发生的 `HTTP 401 · Encountered invalidated oauth token for user, failing request` 已保留。随后使用同一配置的最小 Chat Completions 请求多次返回 `503 auth_unavailable: no auth available (providers=codex, model=gpt-6-luna)`，并报告上游 OAuth token 失效。这是当前真实模型后续验收的外部阻塞；没有切换用户指定模型，也没有把规划成功、前台包或历史 Debug 成功当成计算器完成。M1 尚未达到增量发布退出条件，M2–M4 不提前标为完成。

### 取消竞态与旧 SQLite 兼容回归（04:08）

- 定向 JVM **78/78** 通过，含 Runtime 73 项、取消等待 3 项、停止路由 2 项；独立 Debug/AndroidTest 构建通过。日志 `/tmp/xiaoling-m1-cas-snapshot-build.log`。
- 指定 POCO 独立宿主专项 **11/11** 通过，日志 `/tmp/xiaoling-m1-cas-snapshot-poco.log`。覆盖提交前取消、终态不覆盖/完成幂等、恢复审批关闭 Workflow 后缀、后台先写停止栅栏及旧 Step 归属、模型等待取消预算。
- 两种备份路径均实际执行，验证 4 MiB 文档完整相等、FTS MATCH 命中、同毫秒事件顺序，兼容路径额外检查 FTS/事件 rowid 空洞保留；导出后原库仍可写，WAL/恢复/调度既有回归通过。强制兼容路径在 API36 运行不等于已在 API26–29 系统实测。
- 04:08 最小模型探测：Python urllib 客户端被边缘层返回 403/1010；改用 OkHttp User-Agent 的 curl 请求到达网关，返回 503 `upstream_authentication_required` / `refresh_token_invalidated`。凭据未写入命令参数或日志。
- 本次完整 App 回归 **1662/1662**、lint（0 error）、正式 Release 构建通过，日志 `/tmp/xiaoling-m1-cas-snapshot-final-gate.log`。随后设备诊断增量另验，见下文。
- 04:12 已覆盖安装取消/快照修复候选 `f9d58d4e3100aaded1d8cfee4c9dfa70c6ad88e827037ad6c80f9dc2ee9e1626`，正式证书与 16 KiB 对齐通过。普通设置仍显示原提供方、M1-DEVICE、3 个 Profile；任务中心 3 个 Run、1 成功/2 失败保持，截图 `/tmp/xiaoling-m1-release-cas-safe-readback.png`。
- 04:15 普通导出 `/sdcard/Download/xiaoling-backup-20261111-041512.zip` 成功，主机 `/tmp/xiaoling-m1-release-cas-audit.zip`。独立 SQLite integrity=ok，三条原 Run 为 COMPLETED/FAILED/FAILED、两条 Workflow FAILED，未发现测试 API Key 明文。

### 系统已授权但服务断开的诊断修复

04:18 在正式包通过系统页面授权后，短时 `uiautomator events` 占用无障碍自动化连接。系统 `enabled_accessibility_services` 仍含正式服务，而 Bound/Binding/Crashed 均为空；常用任务误报“请在系统设置中授权”。这是 `AccessibilityManager` 的返回列表被抑制时，授权判定未读取系统配置导致的诊断错误，未发生模型请求或设备任务动作。强制停止方案会被本机 LineageOS 同时清除授权，因此没有将它当作已授权断连证据。

`AndroidDeviceAccessibilityGateway` 增加只读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，解析并精确匹配本包组件；连接仍由实际服务实例独立判断，未连接不能执行动作。系统字段不可读时保留原服务列表/已连接依据。

- 相关 JVM **22/22**、lint、正式 Release 构建通过，日志 `/tmp/xiaoling-m1-disconnected-gate.log`。此前 1662 项完整 App 回归与 11 项 POCO 备份/取消回归未因诊断改动重复执行。
- 诊断修复候选仍为 v0.1.23/code24，SHA-256 `50e38b6b13bbac57004f5e4c606a689bcdec560b86ee9ae8b7d445198fe5bb93`，固定正式证书与 16 KiB 对齐通过，04:26 覆盖安装并正常冷启动，首次安装时间保持 02:50:54。
- 04:27 正式包显示“无障碍服务尚未连接，请检查设备 Agent 状态”；同时系统授权组件仍在，Bound/Binding/Crashed 均为空。打开计算器/设置搜索/时钟入口均禁用，点击禁用计算器条目未发起任务。截图 `/tmp/xiaoling-m1-disconnected-fixed-menu.png`。
- 结束短时 UiAutomation 后，通过系统页面关闭临时授权，菜单恢复“请在系统设置中授权”；截图 `/tmp/xiaoling-m1-disconnected-revoked-menu.png`。隔离宿主/test 已卸载，仅保留正式包；授权开关恢复为 0，服务绑定/崩溃列表为空。
- 04:29 任务中心最终回读仍为 3 个 Run、终态 3、非成功 2，原失败 Run ID 保持；诊断与禁用条目点击没有创建新任务。正式安装 lastUpdateTime=04:26:21，无 DEBUGGABLE 标记。

## 本轮验证进展

### ViewModel 的 Skill 管理提取

Skill 列表刷新、本地/GitHub 导入、候选确认、启停及删除动作由独立 Presentation 管理。ViewModel 的原公开入口只转发，初始化列表与 Profile 选择仍由原流程负责；本地文件仍由原读取器执行 64 KiB 限制及严格 UTF-8 解码。异步返回合并宿主最新 UI 状态，不捕获旧会话快照。被替换的刷新遇到协程取消时继续抛出取消，不再把新请求的 loading 状态改为失败。

相关 JVM **64/64**、lint、正式 Release 构建通过，其中新增 5 项覆盖重叠刷新、其他页面状态保持、GitHub 0/1/多候选与过期候选、重复启停/导入、内置删除拒绝及本地删除确认。日志 `/tmp/xiaoling-m1-skill-extraction-gate.log`。

- **最新正式候选**：v0.1.23/code24，SHA-256 `b910708fd65e37006bdb3f7ab6d3e6832677b74c49b1b33321e2ba6a5e399825`，固定正式证书与 16 KiB 对齐通过；已覆盖升级 POCO，冷启动正常。
- 普通 Agent Skills 页面加载列表、停用首个内置 MCP Skill 后刷新回读为停用、恢复启用后再次刷新为启用，成功结果和列表一致。操作结束保留原启用状态，截图 `/tmp/xiaoling-m1-release-skill-extracted.png`；不依赖模型或新增 Agent Run。
- 本轮 04:29 最小模型探测仍为 503 `auth_unavailable` / `refresh_token_invalidated`。M1 剩余计算器、设置搜索、拒绝审批与 Agent 待审批停止验收仍等待同一上游恢复，没有换模型或提前启动 M2。
- 04:42 再次使用项目配置和 `gpt-6-luna` 实际请求，返回 HTTP 503、`upstream_authentication_required`、`refresh_token_invalidated`、`retryable=false`。相同外部条件已连续多轮阻止 M1 剩余真实用例；当前独立修复与拆分已验证，目标标记受阻，恢复条件为指定网关的上游授权可用。候选哈希仍为 `b910708f…e399825`，POCO 安装时间 04:39:21、无障碍关闭、原首次安装时间保持；没有发布、提交或推送本轮变更。

- 首轮定向测试暴露后置观察预算与旧测试夹具假设不一致，已保留有窗口事件的验证收尾和打开应用六次观察边界。
- 完整 App 1653 项单测与 shared 118 项检查通过，Debug/AndroidTest 构建与 lint 通过；日志 `/tmp/xiaoling-current-full-check.log`。之后只新增备份故障注入与归档入口测试，定向文件替换测试 5/5 通过。
- 最新 POCO 备份产品入口与调度竞态 4/4 通过：`/tmp/xiaoling-backup-product-poco.log`。正常归档经真实 `XiaoLingBackupManager.restore(uri)` 准备后，在线旧库保持，激活后新库行和绑定正确；缺表、损坏文件、保留旧 Room hash 却改变表结构均拒绝。
- 最新备份与菜单联合 6/6 通过：`/tmp/xiaoling-current-poco-tests.log`。菜单定向 39 项 JVM 检查通过：`/tmp/xiaoling-m1-menu-final-build.log`。
- POCO 原生空白/换行输入 1/1 通过：`/tmp/xiaoling-input-poco-rebind-test.log`。首次测试因 Runner 重建后的服务未绑定超时，未发送输入，失败保存在 `/tmp/xiaoling-input-poco-test.log`；重绑只作用于本轮已授权组件，结束后系统授权列表与开关恢复原值。
- 用户配置的真实 `gpt-6-luna` 普通/流式调用在 POCO 上通过 1/1：`/tmp/xiaoling-provider-poco-smoke.log`，普通完成、流式正常终态和 3 次 delta。凭据只经单次 USB socket 进入测试内存，未写入 APK、Intent 或日志。它不是正式设置/聊天入口联合证据。
- 签名 Release 候选构建通过，`apksigner` 与 `zipalign -P 16` 检查通过，日志 `/tmp/xiaoling-release-candidate-build.log`。候选仍为 v0.1.23 / versionCode 24，用于预发布验收，尚未发布或打 tag。
- 候选 APK：`app/build/outputs/apk/release/app-release.apk`，SHA-256 `1ddfce75a0f5912d8faae5a52552a86b6875bfb9c2cd090d4bb53a028b85b6ac`；正式证书 SHA-256 `23ad3f3662babb32cc0ed98f8d7a6028254fbb2bb3c7c37ba54730bff8a0906c`。
- 切换前 POCO 是本轮新装的 Debug 包，证书 `6c8823ff4295d7b29e8e2c58b13c864f795b082255b67c80aa8cb783155e3899`，与正式签名不兼容。最初等待卸载授权；用户随后已明确允许切换，最新安装与联合验收进展见上方 M1 第二片。历史 Debug 证据仍不标成正式包验收。

## 契约来源

2026-11-11 通过 Smart Search 读取的官方文档：

- [OpenAI Responses 事件与终态](https://developers.openai.com/api/reference/resources/responses)：completed、failed、incomplete 为不同事件，传输结束不代表生成成功。
- [Android SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)：integrity 检查与事务/WAL 行为。
- [Android Os](https://developer.android.com/reference/android/system/Os)：公开 `open`、`fsync`、`close` 接口用于同步恢复目录。

- [SQLite VACUUM INTO](https://www.sqlite.org/lang_vacuum.html)：明确输出为原库一致事务快照；[SQLite 3.27.0](https://www.sqlite.org/releaselog/3_27_0.html) 引入该指令。[Android SQLite 版本表](https://developer.android.com/reference/android/database/sqlite/package-summary)说明需读取设备实际 SQLite 版本。三页正文已于 2026-11-11 通过 Smart Search 抓取。
- [SQLite ATTACH](https://www.sqlite.org/lang_attach.html) 与 [事务](https://www.sqlite.org/lang_transaction.html)：读取事务中的源快照不受其他连接后续提交影响；兼容导出只写目标库，不依赖 WAL 下跨库提交原子性。正文已于 2026-11-11 抓取。
- [Android Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES](https://developer.android.com/reference/android/provider/Settings.Secure#ENABLED_ACCESSIBILITY_SERVICES)：公开的已启用无障碍服务列表字段，API4 起可用；2026-11-11 通过 Smart Search 抓取正文，并核对本机 SDK 源码的 `@Readable` 声明。
