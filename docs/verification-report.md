# 验证报告

验证日期：2026-07-27（北京时间）

## 当前验证基线

- 当前发布版本：小灵 `v0.1.12`，`versionCode=13`、`minSdk=26`、`targetSdk=36`。
- 发布提交：`0468c8511c31953c0ce44880b5272b7b315e9654`（`发布小灵 0.1.12`）；GitHub Release 为 [小灵 v0.1.12](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.12)。
- 正式产物：`outputs/release/xiaoling-v0.1.12.apk`，大小 `15,950,806` 字节，SHA-256 `0076dbc952fbc5a9db03ce3ebce89261315db43290fe3e407a70707c4939ab66`；v2 签名、zipalign 和单一签名者校验通过。
- 本地完整门禁：JVM `656/656`，0 失败、0 错误、0 跳过；Lint `0 error / 50 warnings / 1 hint`；Debug、AndroidTest、Release APK 和 Release lintVital 均成功。
- Redmi 完整门禁：只使用真机 `wsvwypiz7xwslvl7`，默认 `AndroidJUnitRunner` 为 `OK (196 tests)`，其中 `184 passed / 12 skipped / 0 failed`；没有向 Pixel_9 或其他模拟器发送 ADB 命令。
- 文档语料门禁：最终 README 与长期 `docs/` 打包进 AndroidTest assets 后，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。
- 设备收尾：测试包已卸载，Debug APK 无损覆盖并冷启动；`MainActivity` 为前台 resumed Activity，主进程存活，近期 AndroidRuntime crash 检查为空。

## 2026-07-26 验证报告历史归档

- 归档边界：原 2,391 行验证报告冻结为 [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)，当前卷收敛为发布基线、当前工程边界、历史索引和归档点之后的新验证。历史卷的 Skill 示例相对链接已按新目录修正。
- 语料契约：AndroidTest 继续显式导入根级当前卷，并新增历史卷 asset；黄金查询分别覆盖当前 v0.1.12 门禁和历史引用清理证据。正例计数改为跟随黄金查询集合，新增历史卷不再要求同步修改固定常量。
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

## 当前工程边界

- Room 当前为 v32；Agent Runtime、Workflow Ledger、设备 Agent 有限动作、长期记忆、声明式 Skill、RAG/Embedding 与 answerability shadow 既有边界不因文档归档而改变。
- 应用导航、Workflow 管理和 Agent 任务中心已分别拥有 `ui/navigation`、`ui/workflow` 与 `ui/agenttask` 垂直边界；当前下一项是长期记忆管理 UI，而不是继续扩张 Agent Runtime 或设备权限。
- answerability shadow 默认关闭，继续固定 `store=null / persistenceMode=NONE`、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；第 101 项保持低频观察，第 102 项尚未进入。
- 设备工具仍不进入 Workflow 或后台自动化；精确定时和 Foreground Service 继续依据真实耗时与系统回收证据决定。
- 知识引用生命周期继续按当前文档状态复核；验收产生的临时知识数据必须确认文档、chunks 和检索索引均已清理。

## 历史证据

- [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)：包含 v0.1.0 至 v0.1.12、阶段性 JVM/Lint/APK、Redmi 真机、恢复可靠性、设备 Agent、Workflow、RAG/Embedding 与 answerability shadow 的完整历史记录。

## 维护方式

- 本文件只维护当前发布基线和归档点之后的新增验证，最新记录置于当前基线之后。
- 历史事实冻结在历史卷中；除修复失效链接或明确事实错误外，不在后续任务中反复改写。
- 当前卷再次显著增长时，以明确版本、日期和阶段截止点生成下一份历史卷，并同步更新文档索引与语料门禁。
