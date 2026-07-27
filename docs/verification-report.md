# 验证报告

验证日期：2026-07-27（北京时间）

## 当前验证基线

- 当前发布版本：小灵 `v0.1.13`，`versionCode=14`、`minSdk=26`、`targetSdk=36`。
- 发布范围：`v0.1.12` 之后 15 个工程提交，并由本次版本与文档提交封版；包含验证报告归档、主要 UI 垂直模块、单一系统 Splash、固定设置标题、首帧初始化收敛、R8 和 Baseline/Startup Profile。精确 tag 目标在 GitHub Release 创建后回填。
- 正式产物：`outputs/release/xiaoling-v0.1.13.apk`，大小 `3,170,866` 字节，SHA-256 `b6726cd080d0bd604726b5d77259311e855d2403110053fe41d0c851bd328fe8`；v2 签名、zipalign 和单一签名者校验通过，证书 SHA-256 为 `5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9`。
- 本地完整门禁：Gradle `141/141` tasks（`3m 57s`），JVM `678/678`，0 失败、0 错误、0 跳过；Lint `0 error / 51 warnings`；Debug、AndroidTest、R8 Release APK 和 Release lintVital 均成功。
- Redmi 完整门禁：只使用真机 `wsvwypiz7xwslvl7`，为保留正式签名应用数据，使用同一正式证书签署临时 Debug/Test APK 后无损覆盖；默认 `AndroidJUnitRunner` 为 `OK (222 tests)`、耗时 `82.798s`，没有向 Pixel_9 或其他模拟器发送 ADB 命令。
- 文档语料门禁：最终 README 与长期 `docs/` 打包进 AndroidTest assets 后，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。
- 测试目标边界：最终语料复验首次误以 R8 Release 作为 Debug AndroidTest 的目标，AndroidJUnitRunner 在进入测试前因缺少 `kotlin.jvm.internal.Intrinsics` 崩溃；改回同一正式证书签署的 Debug 主包后运行通过。该失败属于不兼容的测试目标组合，不是产品冷启动崩溃；验收后重新覆盖正式 Release。
- 设备收尾：正式 `v0.1.13` APK 已无损覆盖临时测试构建，测试包已卸载；冷启动 `553ms`，设备报告 `0.1.13 (14)`，`MainActivity` 为前台 resumed Activity、主进程存活，最近 500 行 AndroidRuntime 缓冲区没有小灵相关 FATAL。

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

- Room 当前为 v32；Agent Runtime、Workflow Ledger、设备 Agent 有限动作、长期记忆、声明式 Skill、RAG/Embedding 与 answerability shadow 既有边界不因文档归档而改变。
- 应用导航、Workflow 管理、Agent 任务中心、长期记忆管理、Provider 管理、Agent Profile 管理、Agent Skill 管理、会话主界面、提示词设置、进程退出观察、网络请求设置和设置根页已分别拥有独立 UI 垂直边界；启动收尾后宿主当前 `1,103` 行。`SettingsPage` 继续作为 pane、Android launcher、导航和跨模块适配的 composition root，下一轮重新盘点剩余对话框簇，不继续扩张 Agent Runtime、设备权限或机械搬文件。
- answerability shadow 默认关闭，继续固定 `store=null / persistenceMode=NONE`、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；第 101 项保持低频观察，第 102 项尚未进入。
- 设备工具仍不进入 Workflow 或后台自动化；精确定时和 Foreground Service 继续依据真实耗时与系统回收证据决定。
- 知识引用生命周期继续按当前文档状态复核；验收产生的临时知识数据必须确认文档、chunks 和检索索引均已清理。

## 历史证据

- [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)：包含 v0.1.0 至 v0.1.12、阶段性 JVM/Lint/APK、Redmi 真机、恢复可靠性、设备 Agent、Workflow、RAG/Embedding 与 answerability shadow 的完整历史记录。

## 维护方式

- 本文件只维护当前发布基线和归档点之后的新增验证，最新记录置于当前基线之后。
- 历史事实冻结在历史卷中；除修复失效链接或明确事实错误外，不在后续任务中反复改写。
- 当前卷再次显著增长时，以明确版本、日期和阶段截止点生成下一份历史卷，并同步更新文档索引与语料门禁。
