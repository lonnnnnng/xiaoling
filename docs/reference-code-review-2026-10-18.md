# 六个参考项目源码对照（2026-10-18）

> 当前实施顺序见[Android 后续路线图](android-product-roadmap.md)。本文是固定 commit 的源码快照；[旧分析](reference-apps-analysis.md)中的旧表格保留为历史。

## 1. 同步结果与范围

本轮先检查干净工作树，再 fetch 并按远端默认 HEAD 快进；没有切换开发分支或强制覆盖。最终再次执行六仓 `git ls-remote --symref origin HEAD`，均与下表一致，六个主工作树仍干净。小灵基线为 `44f396186b22687de74e14aab636815bae2d21ae`，正式版本 `v0.1.23`。

| 项目 | 默认分支 | 同步前 → 当前 commit | 新提交数 |
| --- | --- | --- | ---: |
| [Aether](https://github.com/Zhou-Shilin/Aether) | main | `3d782a5` → `3f485e5a73fd0c8752f507011aef58420030945b` | 1 |
| [Eta](https://github.com/Mangi-11/Eta) | main | `213e8ed` → `213e8ed6610007d0758bc64ad54408841b839793` | 0 |
| [OmniBot](https://github.com/omnimind-ai/OmniBot) | main | `06221df` → `d34de04e65fb0fa8f8fb1e5549199b0813e31ff3` | 1 |
| [Operit](https://github.com/AAswordman/Operit) | main | `dbf7191` → `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` | 0 |
| [ZorvAI](https://github.com/Quor-a/ZorvAI) | main | `7e61f9e` → `76b9e44ae363ea682df71d1814d160ebcc2fc99d` | 18 |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | master | `893e83f` → `9ce1ae1531222009a1a1b5379f1cba1580f65ec2` | 15 |

子模块按父仓锁定提交补齐，未追子模块分支最新版：

- Aether `third_party/ish-arm64@16bfe2e`、其 `libapps@b8cacae`、`libarchive@fc6563f` 已就位；`deps/linux@8ec9bf1` 按上游 `.gitmodules` 的 `update=none` 跳过。
- Operit `terminal@0458689`、`OperitNightlyRelease@3501652`，RikkaHub `material-color-utilities@5b3618b` 已就位。
- **ZorvAI 递归源码不完整**：HEAD 有 `DesktopFriends@b9a379f` gitlink，但 `.gitmodules` 没有映射，`git submodule status --recursive` 返回 128：`fatal: no submodule mapping found in .gitmodules for path 'DesktopFriends'`。未猜测来源或修改上游。

本地原始同步证据位于 `outputs/verification/reference-refresh-20261018/`（被仓库忽略）：`before.json`、`sync-result.json`、各仓快进日志；同步结果区分 `rootTreesLatestAndClean=true` 与 `allRecursiveSourcesAvailable=false`。下文引用均为实际可读主仓源码。

本轮只做 Git 同步与源码核对，未构建、安装、运行六个参考 App。六个只读探子均因服务 `429 Too Many Requests` 失败，以下结论由主代理点验源码形成，未采用失败探子的推断。

## 2. RikkaHub：状态权威与统计口径

**更新事实。** 15 个新增提交涉及会话状态、工具生成循环、usage/TPS、压缩提示与矮屏侧栏等。`ConversationSession` 明确“加载之后它就是唯一的权威”，修改作用于最新状态，flush 串行落库；`incarnation` 用于识别删除/替换后的迟到操作。[R1] `AgentLoop.withStepStats` 累加跨请求 usage 和模型生成时间，另保存末次请求 `contextTokens`；`TokenUsage.merge` 与 `sum` 分别处理单请求快照和跨请求累计。[R2][R3] 压缩提示放在历史尾部，要求保留目标、决定、工具产物和未完成项。[R4]

**小灵已有。** `ConversationLoadCoordinator` 用加载代次拒绝迟到结果；`ConversationPersistenceCoordinator` 有单写者锁及删除意图代次。[X1][X2] `AgentRunMetricsPolicy` 已按请求累加 token、模型延迟并记录 usage 覆盖；计划界面已有记忆/知识数量及字节展示。[X3][X4] 普通聊天已有增量模型摘要，Agent 已有保留完整账本的提取式压缩，不能再写“缺少压缩”。[X5][X6]

**实际增量。** 活动上下文占用、累计消耗和缓存统计需进一步分开呈现；长摘要要有任务保真与继续对话的质量门禁。会话状态归属只在复现竞争或引入新生命周期入口后调整。对应 M4，不复制整套 `ChatService/ConversationSession`。

## 3. OmniBot：执行过程可见，停止与完成分开

**更新事实。** 本轮唯一新提交修复场景模型选择菜单；源码把 Agent、语音、压缩、Embedding、Memory Rollup 作为配置场景。[O1] 执行层已有 `ExecutionControls` 和 `ExecutionOverlay`，区分停止与用户手动完成，并展示推理/自动执行、暂停状态；暂停文本为“已暂停，可手动操作”。[O2][O3]

**小灵已有。** 任务中心已有 Run/Step、审批、metrics、重试与 child 停止入口；个人任务完成文案来自持久化目标判定。[X7][X8] `DeviceActionPolicy` 则限制应用包族、伴随窗口与敏感输入。[X9] 常用任务菜单仍使用静态模板，应用目录交集在规划层处理；设备健康状态已有单独的读取入口。[X18][X19][X20]

**实际增量。** M1 先优化任务发起前的可用性、执行期间的阻塞原因和停止反馈。若引入暂停/接管，必须撤销旧 ref、重新观察并确认；“用户结束”不能等价 `VERIFIED`。场景路由只在能力/成本出现明确分工时再考虑，不因上游菜单改动立刻引入 GUI/VLM 双模型或 ACP 重构。

## 4. Eta：owner 和 checkpoint 是控制面事实

**更新事实。** 默认 main 没有新增提交，已是 `213e8ed`。`ExecutionLeaseRegistry` 为停止回调登记 owner，旧服务 drain 不取消下一次启动登记的任务。[E1] `AgentRunCheckpointStore` 保存 owner、handoff、事件、context snapshot 和 transcript；读取中断工具批次时做显式收敛，不把未完成记录当成功。[E2]

**小灵已有。** child checkpoint 绑定当前进程和父来源；原地重试旧批次被拒绝，预算从父持久快照约束，最多两个只读 child。[X10] Workspace journal 有独立进程 token，恢复时将未知命令结算为 `INTERRUPTED`。[X11] 新 Run 的 Profile/Provider/会话前置检查集中在 `AgentLaunchPreflightCoordinator`。[X12]

**实际增量。** 自然系统恢复需要真实中断事实，属于独立待办；已有取消/恢复实现不再重做。若后续引入服务持有长任务，再明确 owner lease 的进程/服务代次关系。不能从 Eta 的控制面实现推导小灵已经具备后台设备执行授权或可靠保活。

## 5. Operit：优先吸收文件任务的产品入口

**更新事实。** 默认 main 仍为 `dbf7191`，未为追新切换 dev。`MCPManager` 已缓存 transport-neutral runtime session/descriptor。[P1] `WorkspaceChangeTracker` 按 owner、chat、工作区路径绑定观察，输出有界变更列表及省略数。[P2] 浏览器为真实 WebView，源码启用 JavaScript、DOM storage 和多窗口，并安装 JS bridge；其权限面比小灵当前静态浏览器宽。[P3]

**小灵已有。** `McpSessionRegistry` 统一 owner/server 生命周期、配置撤销和工具授权；legacy SSE 已有实际执行路径，stdio 则明确拒绝。[X13][X14] Workspace 有私有目录边界、文件读写及命令账本；`write()` 仍直接写字节。[X15][X11] Browser 的 click 做审批绑定的链接导航；type 只存本机会话草稿，不写 HTTP、Cookie 或 Room。[X16]

**实际增量。** M3 做用户选定文件、修改预览/版本冲突、原子写入、产物回读和导出，先让已有 workspace 能交付结果。需要动态页面时才启动 M5，可见 WebView 与当前 DOM/ref 一起设计，不直接继承上游任意 JS bridge、登录或表单提交能力。MCP registry/SSE 不重新排为开发项。

## 6. Aether：会话与工作区归属，平台范围要看最新源码

**更新事实。** 最新提交将 Computer 观察限定到 iPhone Duo；宿主确认 Duo 能力后才装配该面板，bridge 仅在 `platform === "ios"` 时启用 `observe_computer`，Android 的 run payload 不再申请它。[A1][A2][A5] 共享客户端仍支持 session 导出/导入，bridge 有对应 dispatch；run 请求绑定 session、branch 和 workspace/runtime。[A3][A4]

**小灵已有。** `WorkspaceSessionOwner` 绑定 Run、会话、用户消息和进程；journal 独立持久化命令结果和中断事实。[X11] shared 已承接多类协议/codec；本轮不以参考平台能力决定小灵产品平台。

**实际增量。** M3 的文件产物同样绑定任务与版本，M4 检查会话状态一致性。会话导出只能在明确内容范围与脱敏规则后设计，不能把命令 transcript、聊天备份与可执行恢复混为一项。iOS 产品暂停；旧分析中的 Android Computer 面板描述不能当作当前事实。

## 7. ZorvAI：执行来源可核验，不扩大 shell 权限

**更新事实。** 18 个新增提交主要覆盖 Ubuntu/LANXIU 双环境、SSH/VNC 及安装/网络可靠性。`TerminalExecTool` 的 LANXIU 分支要求执行所选 guest 并如实返回错误，不静默退到设备 sh；Ubuntu 分支仍存在 `device/Toybox` 回退，两个分支不能概括为统一安全隔离。[Z1] 当前 `isRootfsReady()` 检查 bash（两种路径）与 `etc` 目录，属于就绪启发式，不能称为完整安全或密码学完整性验证。[Z2]

**小灵已有。** Workspace 固定宿主命令、限制目录和输出，并用 session journal 保留来源、exitCode、超时/取消/中断事实。[X15][X11] 插件是签名确认后映射 SAFE 宿主能力，当前不执行任意插件代码。[X17]

**实际增量。** M5 如做 stdio/代码运行，先固定执行环境、权限和父子进程生命周期，准备失败直接拒绝；禁止用宿主 shell 回退隐藏沙箱失败。不把 proot 或签名本身当安全边界。`DesktopFriends` 缺映射的源码不参与本轮判断。

## 8. 对照后的结论

已完成的协议和控制面应服务真实任务。近期顺序为 **可用入口 → 高频任务组合 → 文件产物 → 长会话与消耗 → 有条件的能力扩展**。Remote 长期部署、真实通知、自然恢复与外部 MCP 保留为独立待办。下一开发片为 M1 的常用任务可用性和前置诊断；各阶段退出条件见[当前路线](android-product-roadmap.md)。

## 源码定位

链接固定到本轮 commit，行号是该版本的起始定位。参考源码存在仅证明实现机制，不证明其运行效果；小灵通过范围见[发布记录](android-v0.1.23-release.md)。

[R1]: https://github.com/rikkahub/rikkahub/blob/9ce1ae1531222009a1a1b5379f1cba1580f65ec2/app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt#L38
[R2]: https://github.com/rikkahub/rikkahub/blob/9ce1ae1531222009a1a1b5379f1cba1580f65ec2/ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt#L222
[R3]: https://github.com/rikkahub/rikkahub/blob/9ce1ae1531222009a1a1b5379f1cba1580f65ec2/ai/src/main/java/me/rerere/ai/core/Usage.kt#L13
[R4]: https://github.com/rikkahub/rikkahub/blob/9ce1ae1531222009a1a1b5379f1cba1580f65ec2/app/src/main/java/me/rerere/rikkahub/data/ai/prompts/CompressPrompt.kt#L3
[O1]: https://github.com/omnimind-ai/OmniBot/blob/d34de04e65fb0fa8f8fb1e5549199b0813e31ff3/ui/lib/features/home/pages/scene_model_setting/scene_model_setting_page.dart#L44
[O2]: https://github.com/omnimind-ai/OmniBot/blob/d34de04e65fb0fa8f8fb1e5549199b0813e31ff3/omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/ui/ExecutionControls.kt#L21
[O3]: https://github.com/omnimind-ai/OmniBot/blob/d34de04e65fb0fa8f8fb1e5549199b0813e31ff3/omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/ui/ExecutionStatus.kt#L11
[E1]: https://github.com/Mangi-11/Eta/blob/213e8ed6610007d0758bc64ad54408841b839793/app/src/main/kotlin/io/github/mangi/eta/agent/runtime/ExecutionLeaseRegistry.kt#L9
[E2]: https://github.com/Mangi-11/Eta/blob/213e8ed6610007d0758bc64ad54408841b839793/app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRunCheckpointStore.kt#L20
[P1]: https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/mcp/MCPToolExecutor.kt#L464
[P2]: https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/process/WorkspaceChangeTracker.kt#L55
[P3]: https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/BrowserWebViewSupport.kt#L76
[A1]: https://github.com/Zhou-Shilin/Aether/blob/3f485e5a73fd0c8752f507011aef58420030945b/pi-bridge/src/bridge.ts#L2771
[A2]: https://github.com/Zhou-Shilin/Aether/blob/3f485e5a73fd0c8752f507011aef58420030945b/app/src/main/java/com/zhousl/aether/data/pi/PiAgentRunner.kt#L115
[A3]: https://github.com/Zhou-Shilin/Aether/blob/3f485e5a73fd0c8752f507011aef58420030945b/shared/src/commonMain/kotlin/com/zhousl/aether/runtime/SharedPiBridgeClient.kt#L215
[A4]: https://github.com/Zhou-Shilin/Aether/blob/3f485e5a73fd0c8752f507011aef58420030945b/pi-bridge/src/bridge.ts#L3718
[A5]: https://github.com/Zhou-Shilin/Aether/blob/3f485e5a73fd0c8752f507011aef58420030945b/iosApp/App/AetherApp.swift#L233
[Z1]: https://github.com/Quor-a/ZorvAI/blob/76b9e44ae363ea682df71d1814d160ebcc2fc99d/app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsTerminal.kt#L41
[Z2]: https://github.com/Quor-a/ZorvAI/blob/76b9e44ae363ea682df71d1814d160ebcc2fc99d/terminal-core/src/main/java/com/ai/assistance/quro/terminal/runtime/TerminalEnvironment.kt#L469
[X1]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/ConversationLoadCoordinator.kt#L33
[X2]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/ConversationPersistenceCoordinator.kt#L64
[X3]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/AgentRunMetrics.kt#L41
[X4]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/PersonalTaskPlanContextUsagePresentation.kt#L3
[X5]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/ConversationRequestContextPreparer.kt#L93
[X6]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/AgentContextCompression.kt#L24
[X7]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/agenttask/AgentTaskCenterPage.kt#L380
[X8]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/PersonalTaskCompletionPresentation.kt#L7
[X9]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/device/DeviceActionPolicy.kt#L44
[X10]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/AgentChildLifecycle.kt#L35
[X11]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/WorkspaceSessionJournal.kt#L13
[X12]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/AgentLaunchPreflightCoordinator.kt#L95
[X13]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/McpSessionRegistry.kt#L27
[X14]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/McpClient.kt#L677
[X15]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/WorkspaceSandbox.kt#L137
[X16]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/BrowserAgent.kt#L220
[X17]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/AgentPluginExecution.kt#L79
[X18]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/ui/conversation/PersonalTaskTemplate.kt#L9
[X19]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/agent/PersonalTaskPlan.kt#L386
[X20]: https://github.com/lonnnnnng/xiaoling/blob/44f396186b22687de74e14aab636815bae2d21ae/app/src/main/java/com/longdev/xiaoling/device/DeviceObservationController.kt#L44
