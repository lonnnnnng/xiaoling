# 产品需求

## 小灵 v0.1.14 发布基线

`v0.1.14` 使用 `versionCode=15`、Room v33，并保持 `minSdk=26 / targetSdk=36` 与既有正式签名。发布范围汇总 `v0.1.13` 后 31 个工程提交：通用执行恢复矩阵、提交状态未知分类、用户确认的受控安全重放、失败 ToolResult/typed 验证的原子终态结算、answerability Shadow 跨进程匿名账本与单次采样窗口，以及前台 Workflow `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text` 生产闭环。

发布不得扩大既有安全边界：旧 Run 和旧副作用事实保持不变，不恢复旧 Executor、模型协程或 Workflow 后续步骤；`device.type_text` 原文不得进入持久化路径；`open_app` 仍只允许四个首批包并逐包审批。`swipe`、后台或定时设备自动化、恢复自动续跑、坐标、截图、任意 App、JSON/SAF 导出、生产 answerability enforcement、精确定时和 Foreground Service 继续关闭。

正式门禁必须包含完整 JVM、Lint、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign、v2 单签名和仅 Redmi `wsvwypiz7xwslvl7` 的默认完整 instrumentation。当前结果为 Gradle `141/141` tasks、JVM `837/837`、Lint `0 error / 56 warnings / 0 information`、Redmi `OK (271 tests)`（`121.242s`）；Release APK 为 `3,301,938` 字节，SHA-256 `927579c852ab272a08bd82412821ea7779fb57363f67598660e50a1017e2fc6a`。

## 前台 `device.swipe` 完成态内存交接与 Redmi 限定验收（第 124 阶段，生产未开放）

Registry 只有在当前动作的 `DeviceActionOutcome.swipeEvidence` 与授权前后 snapshot 精确绑定时，才允许构造 `WorkflowSwipeCompletionEvidence`。`outcome.beforeSnapshotId` 必须等于已授权 snapshot ID；动作前 viewport 的包名、window ID 与 generation 必须等于该 snapshot；动作后 viewport 的同三项必须等于 outcome 的真实后置 snapshot。任一错配都必须按缺少专属滚动后置证据 fail-closed，不能把另一窗口、旧动作或伪造 viewport 交给完成策略。

`WorkflowDeviceActionResultCodec` 可以识别 `action=swipe`，但只能复用既有的版本化通用结果摘要；snapshot/ref、目标/锚点 HMAC、节点正文、坐标和完整 viewport 不得加入 schema。完整 evidence 只允许在当前 Controller/Registry 执行链内消费，不进入 Room、日志、Workflow output、答案级 DecisionPolicy 或 Compose。生产默认 Workflow 集合仍不得包含 `device.swipe`；只有 Debug/JVM 显式测试集合可以注入。

Redmi 限定验收必须运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 链路，在固定的系统设置应用详情页执行 fresh `device.snapshot -> device.swipe(up)`，并证明 Run 完成、ToolCall 为 SAFE 精确参数、审批数为 `0`、Executor 验证和 typed 验证均通过、Registry 完成门禁通过、动作前后均为 `com.android.settings`，且 Result 不包含 snapshot/ref 或 64 位 HMAC。该验收只证明首个限定 App/页面，不承诺任意 App，也不等同于生产 Workflow 开放。

第 124 阶段完成后，生产接线仍需单独评审答案级脱敏判定、Room/Workflow output 与 UI 投影；在这些独立评审完成、生产边界定稿并再次通过 Redmi 前台闭环前，默认工具面继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`，后台或定时设备自动化继续关闭。

## 前台 `device.swipe` 执行期 HMAC evidence seam（第 123 阶段，生产未开放）

Controller 必须为每个实例生成至少 256-bit 的随机 HMAC 密钥，测试只允许在随机系统边界注入固定密钥。目标与锚点身份必须使用长度前缀结构化输入和 `HmacSHA256`，且锚点身份必须绑定当前滚动目标，不能在同一窗口的不同滚动容器间复用；身份不得包含 bounds、generation、snapshot ID 或 ref，也不得使用裸节点指纹或无密钥文本摘要。可见锚点只允许来自当前滚动目标下未脱敏、具有稳定语义的后代；同一 viewport 中重复语义身份必须全部丢弃，不能任意选择一个位置。

当前成功 snapshot 必须与节点 ref 同生命周期只驻留内存。capture 失败、显式清理或 Agent Run 切换时必须同步撤销 snapshot、ref 和 viewport。`inspectReference()` 必须在同一生命周期锁内确认 snapshot/ref/TTL 并生成 viewport，随后再次读取 window generation；证据构造期间 generation 变化时必须返回不匹配，且只允许支持 `SWIPE` 的目标返回该证据。Registry 只允许显式测试 Workflow 集合消费，生产默认 Workflow 动作集合不得增加 `device.swipe`；直接 `/agent` 的既有工具面不属于本阶段扩权。SAFE 滚动不得使用异常审批时间延长 snapshot TTL。

Controller 的滚动结果不得继续用 generation-only 判定。动作后必须重新 capture，并以同应用、同 window、同匿名目标、generation 前进、可见匿名内容集合变化及共同锚点至少 `8px` 的请求方向主位移共同证明成功；内容未变、锚点不足、方向相反、横向占优或显著锚点互相矛盾时必须返回 `verified=false`。设备层和 Workflow 层必须共享同一 viewport/anchor 类型和方向验证器，避免阈值或方向语义漂移。

完整前后 viewport 只允许留在当前 Controller/Registry 执行链，不得进入 `DeviceActionCodec`、`WorkflowDeviceActionResultCodec`、Room、日志、Workflow output、答案级 UI 或后台自动化。第 123 阶段当时不修改 Result codec、DecisionPolicy、Room、审批、Compose 或生产 Workflow 默认工具集合；第 124 阶段随后只为严格通用摘要识别 swipe，完成 Registry 完成态纯内存交接并仅用 Redmi 验收真实滚动，生产默认集合仍未开放。

## 前台 Workflow `device.swipe` 专属安全契约（第 122 阶段，生产未开放）

`device.swipe` 的纯策略契约只能接受精确的 `snapshot_id / ref / direction`，其中方向固定为 `up / down / left / right`。动作目标必须来自当前有效 ref，处于启用、未脱敏状态并声明 `SWIPE` 能力；动作前 viewport 必须绑定非空应用包名、有效 window ID、非负 generation、同一匿名目标指纹，以及至少两个互不重复的 64 位匿名可见锚点。缺少专属证据时，即使调用方把 `device.swipe` 注入通用动作白名单，也必须以 `SWIPE_POLICY_DENIED` fail-closed。

`swipe` 沿用现有 ToolDefinition 的 `SAFE` 语义，在 Workflow 专属策略中固定为 `SAFE_NO_APPROVAL`，但零审批不得绕过同 Run/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 generation、实时 ref、Executor 验证、typed `PASSED` 和动作后观察。专属授权只能保存 Run/ToolCall 身份、方向与动作前 viewport 的 SHA-256 摘要；不得复制包名、snapshot/ref、目标指纹、完整锚点或节点正文。该摘要只用于当前授权身份绑定，不能作为可逆内容标识或长期检索键。

完成判定必须要求动作前后属于同一应用、同一 window 和同一目标，动作后 generation 严格前进，可见匿名内容集合发生变化，并至少有一个前后共同锚点产生不小于 `8px` 的位移。该位移必须与请求方向一致且主方向绝对值大于横向分量；任一达到阈值的共同锚点若反向或横向占优，必须整体拒绝，不能由另一个方向正确的锚点掩盖。内容未变、目标漂移或只有 Android API 接收动作同样不得判定为成功。四个方向必须分别覆盖正向回归，并覆盖正确与矛盾锚点同时出现的拒绝反例。

第 122 阶段只冻结上述纯策略并强制 `WorkflowDeviceActionSafetyPolicy` 委托，当时没有修改生产 Registry、`DeviceObservationController`、Result codec、DecisionPolicy、Room、审批、答案级 UI 或 Workflow UI，也没有执行真实设备滚动。第 123/124 阶段随后完成 Controller/Registry 执行期 opaque/HMAC evidence、完成态纯内存交接和 Redmi 限定验收，但生产前台 Workflow 工具面仍精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`；完整锚点继续不得进入 Room、日志、Workflow output 或答案级输出。

## 前台 Workflow `device.open_app` 生产闭环（第 121 阶段）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。`device.open_app` 必须标记为 `REQUIRES_APPROVAL`，参数只能包含唯一 `package_name`，且目标只能是小灵、系统计算器、时钟或系统设置。该白名单必须分别由 `WorkflowDeviceActionSafetyPolicy`、`WorkflowDeviceActionApprovalGate` 和最终 `DeviceActionPolicy` Executor 核验；任一层不得依赖其他层替自己补齐空参数、额外字段或非白名单包的拒绝。

每次打开应用都必须创建独立 Room Approval，并通过 Accessibility 安全浮层让用户确认目标包；审批证据要精确绑定 Workflow Run/Step、Agent Run、ToolCall、参数、当前进程 session、同 Run 已验证 snapshot、最多 30 秒 TTL 和当前 window generation。`open_app` 不消费节点 ref，但仍要求动作前观察保持同一窗口 generation。首次人工审批超过 TTL 时必须拒绝为观察过期，不能因为用户最终批准而延长旧 snapshot。

动作完成后必须重新观察，并同时取得 `success=true / executorVerified=true / typed PASSED`。后置包名必须在执行完成门禁和答案级 Room 证据重建时分别与获批 `package_name` 精确比较；打开另一个白名单包也不得收敛为当前调用成功。答案级 Decision、下一步、关联重试和 Workflow 管理页只显示“打开应用”、获批后实际验证的前后包名与有限节点摘要，不保存 Intent、原始审批参数或可复用节点引用。拒绝、取消、执行失败和窗口漂移继续使用稳定脱敏投影。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。六组聚焦 JVM 合计 `95/95`，Debug/AndroidTest APK 构建成功；Compose `pageDisplaysVerifiedOpenAppTargetAndFollowUpBoundary` 与 Room `workflowPersistsVerifiedOpenAppDecisionForNextStepAndUi` 均为 `OK (1 test)`（`2.681s / 0.628s`）；真实 tracer 最终为 `workflow-open-app-e2e success=true / action=open_app / approval=APPROVED / executorVerified=true / verification=PASSED / afterPackage=com.android.calculator2 / answerDecision=VERIFIED`。同步后的文档 corpus 首轮为 `OK (1 test)`（`2.783s`），该证据写回后的冻结文本复验同样通过。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`swipe`、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## 前台 Workflow `device.home` 生产闭环（第 120 阶段）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.back / device.home / device.tap_ref / device.type_text`。`device.home` 只允许空参数并固定为 `SAFE_NO_APPROVAL`；Runtime 不得为它创建 Room Approval 或显示 Accessibility 审批浮层，Workflow 管理页也不得伪造审批卡。异常调用方即使传入审批对象，也不能让审批决定时间替代当前执行时钟或延长 snapshot 有效期。

零审批不等于无约束。`home` 必须绑定用户明确编写的步骤意图、当前 Workflow Run/Step/Agent Run/ToolCall、同 Run 已验证 `device.snapshot`、30 秒 TTL、当前 window generation、当前进程授权和完整动作后观察。Executor 只有在后置窗口包名属于系统 `ACTION_MAIN + CATEGORY_HOME` 动态解析的 launcher 时才可返回 `verified=true`；不得写死 Redmi 或其他厂商桌面包名。Tool Ledger 仍需同时具备 `success=true / executorVerified=true / typed PASSED`，答案级判定、下一步、关联重试和 UI 只能消费严格白名单结果。

`home` 不生成节点目标或可复用 ref。答案级与 Workflow 管理页统一显示“返回桌面”，并明确后续动作必须重新观察、再按各自风险规则执行。`tap_ref / type_text` 仍需逐动作 Room/Accessibility overlay 审批；审批证据投影不得因新增 SAFE 导航动作而扩大。真实验收只使用 Redmi `wsvwypiz7xwslvl7`：六组聚焦 JVM 合计 `87/87`、Debug/AndroidTest APK、Compose 与 Room 纵向单项以及真实 `snapshot -> home` tracer 均已通过；同步后的文档 corpus 首轮为 `OK (1 test)`（`2.76s`），写回后最终复验同样通过。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`open_app / swipe`、全部后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## 前台 Workflow `device.back` 生产闭环（第 119 阶段）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.back / device.tap_ref / device.type_text`。`device.back` 只允许空参数并固定为 `SAFE_NO_APPROVAL`；Runtime 不得为它创建 Room Approval 或显示 Accessibility 审批浮层，Workflow 管理页也不得伪造审批卡。异常调用方即使传入审批对象，也不能让审批决定时间替代当前执行时钟或延长 snapshot 有效期。

零审批不等于零门禁。每次 `back` 仍必须绑定用户明确编写的 Workflow 步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 独立且已验证的 `device.snapshot`、最多 30 秒 TTL 和当前 window generation；恢复、关联重试、旧 snapshot、页面漂移、后台/定时来源、额外参数或证据缺失均必须在 Executor 前 fail-closed。动作完成后必须重新观察，并取得同一 ToolCall 的成功结果、`executorVerified=true`、typed `PASSED` 和已验证后置 snapshot 才能形成答案级完成事实。

`WorkflowDeviceActionDecisionPolicy`、step output snapshot、下一步、关联重试、Room 投影和 Compose 必须把 `device.back -> back` 显示为“返回”，且只保留前后应用、后置节点摘要、规则版本和“仅确认当前动作及后置观察”的能力边界。原始 snapshot、ref、节点、参数和模型转述不得进入答案级路径；Room Approval 投影继续只覆盖 `tap_ref / type_text`。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。完成门禁包括受影响聚焦 JVM、Debug/AndroidTest 编译与 APK、Workflow Compose 单项、Room 判定到下一步/UI 且无 Approval 的纵向单项，以及真实 `snapshot -> back` 的 Tool Ledger、答案级判定和后置包名复核；同步后的项目文档 corpus 首轮与最终复验均为 `OK (1 test)`（`2.733s / 2.725s`）。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`open_app / home / swipe`、全部后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## `device.type_text` 跨入口持久化隐私统一（第 118 阶段）

前台直接 `/agent` 与前台手动 Workflow 的 `device.type_text` 必须共享同一持久化安全投影。原始 `text` 只允许驻留当前执行进程，用于当前 ToolCall 的用户确认、Executor 输入和动作后精确回读；Runtime 的 `tool.call.proposed / tool.call.validated`、独立 ToolCall ledger、Room Approval、`approval.requested / approval.request_decided` 事件、Workflow gate、`VerifiedAgentContext` 与消息 Tool parts 只能保存 `snapshot_id / ref / text_sha256 / text_length`。任何入口都不得以会话类型、审批 UI 或消息投影为由绕过该边界。

`RoomAgentRunRepository.createApprovalRequest()` 必须作为所有审批调用方共享的最终持久化防线：即使上层误传含原文的 ToolCall，也要在事务写入前重新生成安全投影。当前进程的直接 `/agent` 审批卡可以显示内存中的真实输入，便于用户确认即将写入的内容，但展示前必须证明 Room 请求与原 ToolCall 的 Run/ToolCall ID、工具名、风险和安全投影完全一致；只按工具名或指纹匹配不足以恢复或展示原文。

文本 SHA-256 与长度只能用于同一当前进程内的身份绑定，不能用于恢复输入。应用重启后，历史 `type_text` 待审批 Run 不得重新进入可批准状态；`AgentRunResumePolicy` 必须返回 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE`，并在启动收敛事务中安全取消旧 Approval 与旧 Run。用户若仍需执行输入，必须新建 Run、重新生成 ToolCall 并重新确认原文，旧 Run、旧 ToolCall 与旧审批保持终态不变。

验收至少覆盖直接 `/agent` 与 Workflow 的 Runtime 审计投影、Repository 最终净化、`VerifiedAgentContext`/消息 Tool parts 无原文、当前进程审批卡的强身份绑定，以及进程重建后旧文本审批 fail-closed。当前阶段不因此开放 `swipe / open_app / back / home`、后台或定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App；下一动作只能另立单一前台 Workflow 切片，独立冻结意图、风险或 SAFE 依据、后置验证、答案级证据和 Redmi 验收。

## 前台 Workflow `type_text` 生产闭环（第 117 阶段）

前台手动 Workflow 的生产设备工具面可以从 `device.snapshot / device.tap_ref` 扩展为精确的 `device.snapshot / device.tap_ref / device.type_text`，但文本输入必须继续同时满足第 112、115、116 阶段冻结的前台来源、同 Run/ToolCall、当前 snapshot/ref、30 秒 TTL、window generation、可编辑且未脱敏目标、敏感文本预审计、Executor/typed 验证、动作后观察和原 `nodePath` 精确回读。`open_app / back / home / swipe`、全部后台或定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续在规划清单与 Executor 两层拒绝。

Workflow 的 `device.type_text` 必须使用独立 Room 审批和 Accessibility 安全浮层。原始 `text` 只允许留在当前 ToolCall 内存中供执行和精确回读，不得进入 `tool.call.proposed / tool.call.validated`、ToolCall ledger、Approval record、`approval.requested / approval.request_decided` 事件或浮层请求；这些持久参数只能包含 `snapshot_id / ref / text_sha256 / text_length`。浮层只展示 Workflow 步骤意图、工具说明和“输入 N 个字符，内容不展示”的脱敏摘要，不得展示原文、指纹、snapshot ID 或 ref。第 117 阶段最初只冻结 Workflow 来源；第 118 阶段已经把同一持久化边界扩展到直接 `/agent`、可信消息上下文和 Tool parts，同时保留当前进程审批卡显示原文的核对体验。

Accessibility overlay 移除后允许最多 `100ms` 的短结算窗口，用于吸收 Redmi 连续产生的自有 `TYPE_WINDOWS_CHANGED`。只有活动根仍是原目标且完整窗口集合精确回到显示前基线时，才可返回用户批准或拒绝；结算期间的外来窗口、活动根切换、目标内容变化、服务断连或最终窗口集合漂移必须返回稳定失败并使旧 generation/ref 失效。连续基线 detach 事件只能调度一次结算，不能提前完成，也不能吞掉外来窗口变化。

答案级动作判定必须按工具名与结果动作一一对应，只接受 `device.tap_ref -> tap_ref` 和 `device.type_text -> type_text` 的严格白名单结果。Workflow step output、下一步 `previousOutputs`、关联重试和 Compose 只允许保存版本化本地判定、动作前后应用、后置节点摘要和“输入内容未进入答案级证据”的隐私说明；输入原文、文本指纹、snapshot/ref、节点与原始动作 JSON 均不得进入这些路径。拒绝、取消、窗口变化、浮层不可用、服务断连和 BUSY 继续使用稳定状态，并按同一 Agent Run 中最后一个已批准白名单动作归因执行阶段失败。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。完成门禁至少包括受影响聚焦 JVM、Debug/AndroidTest APK、Compose 无原文展示、Room 审批无原文持久化、Room Workflow 判定到下一步/重试/UI 的纵向单项，以及真实 Workflow `snapshot -> type_text` 的 Room Approval、Tool Ledger、答案级判定和原目标精确回读。当前快速迭代阶段不因此默认运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 前台 Workflow `type_text` 目标证据与精确回读 seam（第 116 阶段）

`DeviceController.inspectReference()` 必须从当前未过期、generation 未漂移的 ref 返回结构化目标证据，至少包含 enabled、editable、redacted 和动作集合。证据必须来自 `DeviceSnapshotPolicy -> DeviceNodeReferenceStore` 的同一短生命周期引用，不得从模型正文、历史 Workflow 输出或单独的 `liveReferenceMatched` 布尔值推断；ref 不存在、过期或窗口变化时目标证据为空并 fail-closed。

`device.type_text` 动作后验证必须绑定动作前解析出的原 `nodePath`。新的 snapshot 只能通过动作后 references 找到相同路径，再用新 ref 定位该目标节点并读取其 `text`；页面其他节点的正文、description 或 hint 即使与预期文本相同，也不能证明原输入框完成。目标消失、路径不唯一、动作后无 ref、文本为空、被截断或与获批输入不完全一致时必须保持 `verified=false`。精确回读以强类型瞬态证据留在 Controller/Registry 内存中，不增加 Room 字段，也不进入通用设备动作结果摘要。

Registry 必须在生产默认动作集合仍只有 `device.tap_ref` 的前提下，提供仅供聚焦测试使用的 `type_text` 生命周期 seam：执行前把当前目标属性转换为 `WorkflowTypeTextExecutionEvidence`，执行后使用原始 identity、专属指纹授权和强类型回读构造完成证据。通用授权继续移除 `text`；无原文的 `workflow-device-action-result-v1` 可以标识 `type_text`，但答案级 `WorkflowDeviceActionDecisionPolicy` 与现有 Compose 仍只能消费 `tap_ref`，工具名与结果动作必须双重一致。

Registry 的测试动作注入集合只能是 `{device.tap_ref, device.type_text}` 的子集；即使其他设备动作已经注册或被通用安全策略识别，也必须在 Registry 构造阶段拒绝，不能借测试 seam 提前开放 `open_app / back / home / swipe`。生产调用方继续使用仅含 `device.tap_ref` 的默认集合。

本阶段不得把 `type_text` 加入生产 Workflow 工具清单，不接入 Room 独立审批、Accessibility overlay、Workflow Repository 或答案级动作 UI，也不开放 `open_app / back / home / swipe`、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。验收至少覆盖当前可编辑 ref 的完整证据、其他节点同文不误判、测试态 Registry 正向完成、不可编辑目标在设备动作前拒绝、测试 seam 对 `swipe` 等越界动作构造即拒绝、生产强行执行继续失败，以及仅 Redmi 上普通文本精确回读成功和敏感输入不覆盖原值。

## 前台 Workflow `type_text` 专属安全契约（第 115 阶段）

前台 Workflow 的 `device.type_text` 在进入生产前必须经过独立于通用设备动作白名单的专属安全策略。工具参数必须精确为非空 `snapshot_id / ref / text`，不得接受缺失或额外字段；文本继续复用 `DeviceActionPolicy`，在 `tool.call.proposed` 持久化前拒绝空值、超过 500 字符、非法控制字符、密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱。

当前 ref 对应节点必须有结构化证据证明其处于启用、可编辑、未脱敏状态并声明 `TYPE_TEXT` 能力。缺少节点证据、节点禁用、不可编辑、已脱敏或不支持输入时必须 fail-closed；仅有通用 `liveReferenceMatched=true` 不足以签发文本输入授权。

专属授权只能保存规则版本、Workflow/Step/AgentRun/ToolCall 身份、文本 UTF-8 SHA-256 指纹和字符长度，不得保存文本原文、snapshot ID 或 ref。通用 `WorkflowDeviceActionAuthorization` 对 `device.type_text` 只能保留 `snapshot_id / ref`，移除 `text` 并携带专属授权；直接把 `device.type_text` 加入通用 `enabledToolNames` 而没有专属执行证据时必须拒绝。

完成判定必须重新绑定原 ToolCall 文本指纹，要求结果属于同一 Agent Run/ToolCall/工具，Executor 已验证、typed 验证通过、动作后观察已验证且观察时间不早于动作完成；动作后目标文本必须与获批文本精确一致。通用完成门禁缺少专属回读证据或授权时必须拒绝。本阶段只冻结纯策略和反例，生产 Registry 仍只允许前台 Workflow 使用 `device.snapshot / device.tap_ref`，不修改 Room、Accessibility、Workflow Repository、后台/定时设备工具、恢复自动续跑或任意 App 边界。

## 前台 Workflow 设备动作答案级证据 UI（第 114 阶段）

Workflow 详情页必须从已持久化的 `workflow-device-action-decision-v1` 和同一 Agent Run 的 Room 审批记录生成设备动作证据，不得依赖模型自由文本。成功证据只允许展示 `tap_ref`、动作前后应用包名、后置节点数、脱敏节点数、截断状态、观察时间和规则版本，并明确“只确认当前动作及后置观察已验证，不确认最终业务目标”；持久节点引用必须标记为失效，任何后续动作都要重新观察和审批。

审批证据必须在 IO 加载边界把 `ApprovalRequestRecord` 收敛为只含 `runId / toolName / outcome` 的安全 DTO。UI 状态不得保存原始 `arguments`、snapshot/ref、节点正文、完整 `decisionReason` 或其他工具参数。拒绝、普通取消、窗口变化、审批浮层不可用、Accessibility 服务断连和 BUSY 必须投影为不同稳定状态；批准后在执行验证阶段发生的窗口变化或服务断连也必须可见。一个 Agent Run 出现多次动作尝试时，各失败尝试和批准后的执行失败不得互相遮蔽；已有成功本地判定时，以该成功判定作为步骤最终可信结果。

审批读取必须按 Workflow steps 的 `agentRunId` 去重并分块批量查询，避免 N+1 和 SQLite bind 参数溢出；返回证据的自身 `runId` 必须再次匹配当前 step 的 Agent Run，跨 Run 或工具名不匹配的记录不得绑定。step output、previous outputs、Workflow Run result 和 Run error 中只要出现潜在原始动作结果签名，都必须整段替换为固定提示，不能把动作 JSON 当普通答案正文渲染。

本阶段只增加审计与可见性，不开放 `open_app / back / home / type_text / swipe` 进入 Workflow，不改变 `device.snapshot -> device.tap_ref` 的前台来源、逐动作审批、30 秒 ref、generation、后置观察和 typed 验证门禁，也不开放后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。验收至少覆盖成功证据字段及能力边界、全部稳定失败状态、生产 overlay 原因签名、多次尝试、跨 Run 拒绝、原始审批参数不进入 UI、四处历史 JSON 脱敏、批量 Room 读取、Room 持久判定到投影和仅 Redmi Compose 展示。

## 前台 Workflow `tap_ref` 首个生产切片（第 113 阶段）

第 113 阶段当时，前台手动 Workflow 只允许按 `device.snapshot -> device.tap_ref` 顺序执行一个节点点击。`XiaoLingToolRegistry` 对 Workflow 暴露的设备工具必须精确等于这两项；`open_app / back / home / type_text / swipe`、后台或定时 Workflow、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续拒绝。直接 `/agent` 的设备动作审批仍走原会话审批卡，不得因 Workflow 接线改变。

每个 `tap_ref` 必须先以当前 `conversationId / agentRunId / toolCallId / toolName / arguments` 在 Room 创建 `PENDING` 审批，再由 Accessibility overlay 展示用户明确编写的 Workflow 步骤意图和工具说明。浮层只能使用 `TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL + FLAG_SECURE`，标题固定为 `XiaoLingDeviceActionApproval`，并避开系统导航栏；snapshot ID、ref、节点正文和原始参数不得进入屏幕、日志、UIAutomator 或截图。重复请求返回 `BUSY`；拒绝、取消、服务断连、窗口变化、浮层不可用和协程取消都必须把同一 Room 请求收敛为非批准终态。

活动 generation 必须跟随真实 `rootInActiveWindow`，不得跟随 overlay 的状态事件。只有窗口集合精确等于显示前基线，或基线加唯一小灵自有 overlay 时，才可抑制该 overlay attach/detach 引起的 generation 作废；外来窗口、目标窗口内容变化、滚动、活动根切换、overlay 身份漂移或服务断连必须立即 fail-closed。用户点击批准/拒绝后先移除浮层，系统窗口事件确认自有 overlay 已消失后才能把决定返回 Runtime；无法确认移除时按 `OVERLAY_UNAVAILABLE` 拒绝。

执行前仍需同 Run 已验证且未过 30 秒的 snapshot、实时 ref/fingerprint、当前 generation、当前进程审批和完整参数。执行后必须重新观察并得到同一 ToolCall 的白名单 `workflow-device-action-result-v1`、`executorVerified=true` 与 typed `PASSED`；结果出现任何额外字段、snapshot/ref、节点、指纹、坐标或原始参数都必须拒绝。Workflow step、Run result、下一步输入和 Compose 只能消费 `workflow-device-action-decision-v1` 本地判定。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`，至少确认 overlay 的 flags、标题、导航栏避让、活动窗口保持、Room `APPROVED`、Tool Ledger `PASSED`、后置页面变化和输出零泄露。阶段完成门禁为聚焦 JVM、完整 Debug JVM、Debug/AndroidTest APK 与该真实动作核心路径；Lint、Release 和默认完整 instrumentation 仍按分级验证留到里程碑或明确发版。

## 前台 Workflow 有限设备动作安全基础契约（第 112 阶段冻结）

前台手动 Workflow 的设备动作必须先经过独立、默认全关闭的安全策略 Module，不得直接放宽 `XiaoLingToolRegistry` 的全部动作集。策略只接受 `invocationSource=WORKFLOW + executionOrigin=FOREGROUND` 和显式列入当前阶段白名单的动作；后台、定时、恢复自动续跑、坐标、截图、视觉定位和任意 App 仍固定拒绝。第 112 阶段冻结时生产白名单为空；第 113 阶段只将 `device.tap_ref` 作为首个最小切片接入，其余动作继续遵守默认拒绝。

每个动作必须同时绑定用户明确编写的 Workflow 步骤意图、当前 `workflowRunId / workflowStepId / agentRunId / toolCallId`、完整参数和一个独立审批。审批必须是当前进程会话内的实时用户决定；前一个动作的批准、旧 Run 审批、关联重试审批、进程重建前已批准决定和只按工具名匹配的决定都不得复用。审批、参数、Run 或 ToolCall 任一漂移时必须 fail-closed。

动作执行前必须存在同一 Agent Run 中已通过验证、尚在 30 秒有效期内的 `device.snapshot` 证据，并确认当前 window generation 未漂移。`tap_ref / type_text / swipe` 还必须使用该 snapshot 的 ref，并在 Accessibility Adapter 内再次核对 node path 与 fingerprint；旧 Workflow 输出、`reusedFromStepId`、历史 Tool Ledger 或进程重建前的 ref 不得恢复为可执行引用。页面漂移、ref 过期、服务断连、权限变化、取消或证据缺失都必须在 Executor 之前拒绝。

动作结束后必须由现有 `DeviceController` Adapter 重新 snapshot，并将同一 ToolCall 的成功 ToolResult、`executorVerified=true`、typed `tool.verify=PASSED` 和后置观察绑定为完成资格。“Android 已接收动作”、只有 ToolResult、验证为假、后置快照缺失、取消后迟到结果或身份不一致都不得宣称完成。后续生产接线必须把动作前后白名单证据写入独立 Tool Ledger，Workflow step、Run result、`previousOutputs` 和 Compose 只消费版本化本地判定，不复制节点正文、ref、指纹、坐标或原始快照。

验收至少覆盖：默认全关闭；仅前台 Workflow 来源可进入策略；用户意图、当前 Run/Step/ToolCall、同 Run snapshot、window generation、ref 和当前进程审批的独立漂移矩阵；每个动作必须独立审批；重试、恢复和取消不复用旧证据；后置观察与 typed 验证缺失时拒绝完成。第一个生产动作只能在该纯 Kotlin 契约经过聚焦 JVM 门禁后另立阶段接入，并只用 Redmi `wsvwypiz7xwslvl7` 做真实动作验收。

## 前台 Workflow 设备观察双 Run 与持久化净化边界

设备观察 Workflow 必须能由两个独立 Agent Run 完成：观察 Run 只调用 `device.snapshot`，消费 Run 不得再调用任何设备工具，只能使用前序步骤传入的版本化本地判定。两个 Run 必须各自建立 Tool Ledger，旧 Run 的工具调用不能被视为新 Run 已完成的步骤；验收必须确认没有设备动作或审批请求。

Workflow 步骤进入 `COMPLETED` 前，Repository 必须从该 step 关联的同 Run 持久 Tool Ledger 重新生成设备观察判定，不得相信调用方传入的模型正文或判定副本。单步骤兼容调用直接收敛 `completeRun()` 时也不得旁路该规则；最终 Run result 必须从已净化 step 重新聚合。`verificationStatus=PASSED` 是可读结果的验证事实；`executorVerified` 对 `RESULT_READABLE` 工具可为 `null`，不得因此降级或拒绝已通过验证的 snapshot。调用方显式提供的判定与 Ledger 当前投影不一致时必须 fail-closed。

只要同 Run 存在合法设备观察，step `result` 与 `outputSnapshot.text` 就必须被替换为 `workflow-device-observation-v1` 白名单判定；前台 Workflow 会话文本与后台完成消息也必须发布同一净化文本。原始快照、节点、ref、坐标、动作数组和模型转述只能保留在独立 Agent Tool Ledger 中审计，不得复制到 Workflow step、Run result、下一步 `previousOutputs` 或后台会话文本。

真实验收至少检查：两个 Agent Run 分别只有一次指定工具调用；观察结果 `success=true / PASSED`；审批数为零；第一步 `result/outputSnapshot.text` 与第二步 `previousOutputs[0]` 对 `snapshot_id / nodes / ref` 零命中；Workflow 总账完成；页面显示已验证、本地判断、规则版本、受限原因和节点引用过期说明。Debug Receiver 在应用进程存活期间直接写 Room 后，真机流程必须重建运行态或从 UI 正常保存 Profile，不得把旧 ViewModel Profile 快照误当当前配置。

## 前台 Workflow 设备观察本地判定边界

前台 Workflow 只能从同一 Agent Run 的持久化 Tool Ledger 生成设备观察本地判定。输入必须是 `device.snapshot`、`success=true`、验证通过且能被当前 `DeviceSnapshotCodec` 完整解码的快照；模型自由文本、跨 Run 结果、失败/未验证结果和畸形 JSON 均不得形成判定。规则版本固定为 `workflow-device-observation-v1`，结果只能确认采集时的应用包名、节点数量、脱敏数量、截断状态和采集时间。

本地判定只区分“可复核”和“有限可复核”：未脱敏且未截断时为可复核；存在脱敏节点或截断时为有限可复核。两者都不确认节点正文、用户目标完成、页面仍处于原状态或任何设备动作授权。窗口标题、snapshot ID、window ID/generation、节点正文、description、hint、ref、bounds、actions 和原始 JSON 不得进入判定 DTO、下一步 Prompt 或 Compose 根状态。

新步骤完成时，安全判定必须进入既有 Workflow output snapshot；下一步骤准备时仍需通过当前 step 的 `agentRunId`，或重试步骤的 `reusedFromStepId` 回查来源 Tool Ledger，并核对持久化判定与当前证据一致。合法设备观察必须用版本化本地判定替换模型步骤正文后再进入 `previousOutputs`；来源缺失、结果未验证、结构畸形或判定漂移必须以稳定原因 fail-closed，阻止后续步骤继续声称已确认设备事实。原始工具结果只保留在独立 Agent Tool Ledger 中审计，新 Workflow step 的 `result/outputSnapshot` 不得再保留原始快照或模型转述。

Workflow 详情页必须同时展示已验证来源、本地判定结果、规则版本、白名单输入摘要和明确的结论范围。该闭环不新增 Room 表或列，不开放 `open_app / back / home / tap_ref / type_text / swipe`，不恢复历史 ref，也不改变后台/定时 Workflow、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service 边界。验收至少覆盖完整/受限判定、非设备结果不受影响、跨 Run/失败/未验证/畸形证据拒绝、安全 output snapshot、下一步 Prompt 替换、关联重试来源回查、证据不足阻断和 Redmi Compose 展示。

## 前台 Workflow 设备观察证据 UI 边界

Workflow 管理页必须从持久化 Agent Tool Ledger 而不是模型自由文本生成设备观察证据。每个 Workflow step 只能通过自己已保存的 `agentRunId` 关联对应 Ledger；结果必须同时满足 Run 身份一致、工具名为 `device.snapshot`、`success=true`、`verificationStatus=PASSED` 且快照 JSON 结构合法，才能标记为“已验证”。结构合法必须能对齐当前 `DeviceSnapshotCodec` 的顶层身份、窗口、时间、计数字段和逐节点对象；节点必须具备索引、层级、角色、边界、布尔状态与动作数组，脱敏计数必须与节点一致。失败、未验证、跨 Run、非 snapshot 或任意层级畸形的结果必须 fail-closed，不得生成已确认证据。

证据白名单只包括应用包名、节点数量、脱敏节点数量、是否截断、采集时间和工具执行耗时。窗口标题、snapshot ID、window ID/generation、节点正文、description、hint、ref、bounds、actions 与原始 JSON 不得进入 Workflow UI state。Ledger 批量读取后必须在 IO 加载边界立即收敛为安全 DTO；不得把完整 JSON 复制到 Workflow 表、Compose 根状态、文档、日志或新的 Room 列。

旧 Workflow step output、previous outputs 和 Workflow Run result 可能已经持有模型转述的完整 snapshot JSON。只要文本同时出现 `nodes` 与至少三类设备快照特征，历史页必须整段替换为稳定脱敏提示；识别必须兼容 snake_case、camelCase 和再次 JSON 转义形态。宁可少展示模型原文也不能回流节点数据。证据卡必须明确持久化 ref 已过期、不可用于后续动作；任何动作前仍需在允许的前台直接 `/agent` 中重新 snapshot，不得从历史 UI 复活 ref。

验收至少覆盖：合法 `PASSED` snapshot 只产生白名单字段；窗口标题、节点正文、ref 和 snapshot ID 不存在于证据 DTO；失败、`FAILED`、畸形、非 snapshot 与跨 Run 结果均被忽略；旧步骤输出、前序输入和 Run 汇总被脱敏；Compose 页面显示已验证证据和过期提示；Redmi 真实历史 Ledger 能显示包名、节点/脱敏数和耗时，且最终页面层级不含原始快照字段。该 UI 不开放设备动作、截图、坐标、视觉定位、任意 App 或后台设备工具。

## 前台 Workflow 只读设备观察边界

用户主动在应用前台运行 Workflow 时，只允许在设备 Agent 独立开关开启、Accessibility 已授权且 Profile/Skill 白名单允许的前提下暴露 `device.snapshot`。该组合固定为 `invocationSource=WORKFLOW + executionOrigin=FOREGROUND`；`device.open_app / back / home / tap_ref / type_text / swipe` 不得进入模型工具清单，强行执行时也必须由 Executor 二次拒绝。前台直接 `/agent` 继续保留既有限定动作集；`BACKGROUND` 来源无论来自直接 Run、Workflow、定时 Worker 或恢复链，都不得看到或执行任何设备工具。

`device.snapshot` 继续复用既有 200 节点、4,000 字符、敏感节点脱敏、整窗隐私拒绝和 30 秒 ref 生命周期，不增加截图、坐标、手势、任意 App 或写操作能力。Workflow 只读观察不产生设备动作审批；如果后续步骤需要动作，必须明确失败并要求用户转到前台直接 `/agent`，不能由模型把 snapshot 权限解释为动作授权。

审批恢复必须保留原 Run 的调用来源。应用需从 Room 中已持久化的 WorkflowRun↔AgentRun 关联判断 `WORKFLOW / DIRECT`，并在 IO 调度完成查询；Runtime 恢复上下文必须显式携带该来源，不能因默认值把 Workflow Run 恢复成直接对话权限。工具清单与执行层必须共享同一只读/动作边界，任一层缺少上下文、开关关闭、服务断连或来源不合法时都要 fail-closed。

验收至少覆盖：前台直接 Run 可见既有设备工具；前台 Workflow 只可见且可执行 `device.snapshot`；Workflow 强行执行动作被拒绝且控制器未收到动作；后台 Workflow 看不到 snapshot 且执行失败；审批恢复保留 `WORKFLOW` 来源；Room 关联在绑定 AgentRun 前为 false、绑定后为 true。Redmi 真实验收必须证明 Workflow 只产生 snapshot ToolResult、没有动作和审批，并在结束后恢复设备 Agent 与 Accessibility 状态。

## 知识质量工程：answerability Shadow 匿名跨进程观测边界

只有用户显式开启、冻结 Judge 身份与当前 Provider 配置完全匹配、来源为前台直接 `/agent`，且最终答案已成功保存时，answerability Shadow 才能请求 Judge 并以 `OPTIONAL` 模式写入观测账本。普通聊天、Workflow、后台 Worker、身份漂移、候选缺失或答案保存失败不得写入。Judge 与 Store 仍是答案发布后的旁路；任一失败不得改写答案、知识引用、可信上下文、Agent Run 终态或主失败路径，协程取消继续传播。

Room v33 的 `knowledge_answerability_shadow_observations` 必须以 64 位小写 SHA-256 幂等键为主键，重复写入只保留首次观测，并按时间最多保留 2,000 条。表只允许保存幂等键、候选摘要、Keystore 安装级密钥生成的 Judge HMAC-SHA-256 匿名桶、观测/绑定/判定/失败枚举、attempt、延迟/TTFB、Prompt 字节、Tokens、usage 次数、稳定失败计数和记录时间。禁止保存消息 ID、Run ID、问题、答案、候选正文、引用正文或身份、原始响应、Provider ID、模型、Base URL、API Key 或其他凭据；Store 必须拒绝不符合 SHA-256 形状的上游摘要，Judge 桶不得使用可按公开配置枚举的无盐摘要。

设置页必须把跨进程匿名累计与当前进程样本/notice 生命周期分开展示。notice 继续使用短生命周期 `messageId` Map，重启后不得从历史消息恢复。v32→v33 迁移只能创建空表，不得从历史消息、Run、检索审计或第 97–101 阶段人工汇总猜造记录；旧阶段“不持久化”是当时证据下的历史边界，保留原文，不得改写成已由新账本采集。生产相关性拒绝与 answerability enforcement 继续关闭；本切片不改变检索排序、答案、Workflow/后台权限、ANN 或自动后台索引重建。

应用冷启动时，跨进程摘要读取可能先于 Profile、会话和 Workflow 初始化完成。后续整表状态重建必须保留已经读取的 `answerabilityShadowPersistentSummary`、当前 Shadow 开关和进程内摘要，禁止使用默认零值覆盖真实 Room 累计。该合并只恢复 UI 投影，不得改写匿名账本、补造记录或把进程内 notice 持久化。

验收必须覆盖 `OPTIONAL` 生产请求、持久记录携带数值遥测、幂等重复、数据库关闭重开、未知数值保持 `null`、无 attempt telemetry 的最终稳定失败分类、Judge HMAC 匿名分桶、公开 SHA-256 与落库 HMAC 不同、第 2,001 条裁剪最旧记录、v32→v33 空迁移、Schema/表值隐私、原始正文误入摘要字段的拒绝，以及冷启动状态重建不会丢失已加载跨进程摘要。当前实现已通过本地 `141/141` tasks、JVM `734/734`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 最终 JUnit XML `248` 条（`236 passed / 12 skipped / 0 failed`）；第 104 阶段新增聚焦 JVM 回归和 Redmi 冷启动真实摘要复验。第 107 阶段进一步通过真实预算耗尽 Run 验证“没有成功答案不消费一次性授权”，并形成第三条独立同日记录；三条仍不授予长期分隔、校准、导出或生产拒绝资格。更新后的项目文档 corpus gate 首次与写回设备收尾后的最终复验均为 `OK (1 test)`，正式发布基线与当前 Room v33 开发设备态继续分开记录。

## 成功 ToolResult 缺少 typed 验证结论的闭环审计边界（通用执行恢复矩阵）

成功 ToolResult 已落库但没有 typed `tool.verify` 时，不得从 `ToolResult.verified`、工具名、事件顺序或当前 Registry 猜造 `PASSED / FAILED`。现有严格白名单只读验证资格保持不变；其他形状没有唯一可持久化安全动作，必须关联新 Run 或 fail-closed，不得新增原 Run 恢复、旧 Executor 重放、LLM 总结或 Workflow 后续步骤。

处置必须依次核验当前工具定义存在性、`COMMITTED + IDEMPOTENT_BY_KEY` 提交证据、当前工具是否显式开放只读恢复验证。`definition=null + support=false` 必须返回 `TOOL_DEFINITION_UNAVAILABLE`；定义存在但回执缺失/损坏且 `support=false` 必须返回 `COMMITTED_EFFECT_EVIDENCE_INVALID`；只有定义与提交证据完整但 `support=false` 才返回 `COMMITTED_VERIFICATION_UNAVAILABLE`。前两类错误阶段不得调用 support 回调，避免较宽泛的能力结论遮蔽更准确的持久化事实。

验收必须覆盖上述三类组合，并证明处置优先级调整不改变任何恢复资格。当前实现已通过强制本地 `141/141` tasks、JVM `734/734`、Lint `0 error / 52 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (243 tests)`（`95.348s`）；Room Schema 保持 v32。

## 持久化失败工具验证原子失败终态结算边界（通用执行恢复矩阵）

进程在成功 ToolResult、结果后的执行预算以及 typed 失败验证均已落库，但正常 Runtime 尚未把验证 Step/Run 写成失败终态时，只允许补齐与原验证异常路径等价的失败控制面。资格必须同时满足：Run 为 `VERIFYING` 且没有终态字段；证据来自完整非空 v20 Tool Ledger；所有 ToolResult 均为成功，前序验证全部为 `PASSED`，唯一链尾验证为 `FAILED` 且带非空稳定原因；预算证据必须为 `Available` 并位于链尾 ToolResult 之后；执行与验证 Step 和 typed 创建/完成事件一一对应，链尾 `TOOL_VERIFY` 是最后一个 Step、仍为 `RUNNING` 且没有状态事件；原 Profile 允许全部工具；不存在待审批或失败验证后的任何控制面/业务事件。

命中资格时，Repository 必须在 `closeInterruptedRuns()` 的单个 Room transaction 内把链尾验证 Step 更新为 `FAILED`，以条件状态更新把原 Run 从 `VERIFYING` 改为 `FAILED`，并写入 typed `run.recovered(PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status=FAILED`。失败原因只能来自已持久化 `tool.verify`；不得调用 Executor、验证器或 LLM，不得追加第二条 ToolResult/验证事实、成功总结或 Workflow 后续步骤。重复进入必须无变更，双 Repository 并发只能有一个成功者，任一终态审计写入失败必须回滚 Step、Run 和全部新事件。

以下形状必须继续 fail-closed：Legacy/event-only 或不完整 Ledger、预算缺失/损坏/未位于结果之后、ToolResult 失败、前序失败或未验证、链尾验证不是 `FAILED`、失败原因缺失、Run 非 `VERIFYING`、Step/Event 身份或 sequence 漂移、验证 Step 已终态或不是最后一步、待审批、尾随事件以及既有 Run 终态字段。尤其不能把“成功结果但尚无验证结论”推断为失败；该窗口仍按提交后验证不可用边界处理。

验收必须覆盖策略正例和预算/原因/Ledger/尾部拒绝矩阵、Codec round-trip、Runtime 在失败验证落库后的故障注入、Executor 只执行一次、真实 Room 双 Repository 并发与事务回滚。当前实现已通过强制本地 `141/141` tasks、JVM `732/732`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (243 tests)`（`96.162s`）。Room Schema 保持 v32，真机只允许 Redmi `wsvwypiz7xwslvl7`。

## 持久化失败 ToolResult 原子失败终态结算边界（通用执行恢复矩阵）

进程在失败 ToolResult 与执行预算已落库、但正常 Runtime 尚未把 Step/Run 写成失败终态时，只允许补齐与原 Runtime catch 等价的失败控制面结算，不得把该窗口解释为工具重放、验证恢复或继续执行。资格必须同时满足：Run 为 `EXECUTING` 且尚无终态结果、错误或完成时间；证据来自 v20 完整非空 Tool Ledger；所有前序 ToolResult 成功且 `tool.verify=PASSED`；唯一链尾 ToolResult 为 `success=false`、错误非空、没有验证事实；链尾结果后恰有一份完整执行预算快照；执行/验证 Step 与账本一一对应，前序 Step 已完成，链尾 `TOOL_EXECUTE` 是最后一个 Step 且仍为 `RUNNING`；原 Profile 仍允许全部工具；不存在待审批、额外 Step 或其他业务尾部。

命中资格时，Repository 必须在 `closeInterruptedRuns()` 已有的单个 Room transaction 内把链尾执行 Step 更新为 `FAILED`，以条件状态更新把原 Run 从 `EXECUTING` 改为 `FAILED`，并按同一原子边界写入 typed `run.recovered(PERSISTED_TOOL_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status=FAILED`。失败原因必须来自已持久化 ToolResult；不得调用 Executor、验证器或 LLM，不得追加 ToolResult、`tool.verify`、成功可信上下文或 Workflow 后续步骤。重复进入必须返回无变更；并发 Repository 只能有一个成功结算者；任一终态审计写入失败必须回滚 Step、Run 和全部新事件。

以下形状必须继续 fail-closed：v19 及更早 typed-event fallback、空或不完整 Ledger、成功链尾、失败错误为空、前序失败或未验证、预算缺失/损坏/漂移、失败结果后不是恰好一份完整预算、Run 非 `EXECUTING`、Step 身份/sequence/type/status 漂移、链尾执行 Step 不是最后一步、待审批、`tool.verify`、额外 Step/业务事件、多条失败结果或非链尾失败结果。链尾 ToolResult 缺失仍属于提交状态未知；成功 ToolResult 已持久化但验证事实不完整仍不得借用本需求结算。

验收必须覆盖策略正例与上述拒绝矩阵、Codec round-trip、Runtime 故障注入后 Executor 只执行一次、真实 Room 磁盘重开、重复与双 Repository 并发、事务回滚，以及结算后重试证据稳定。当前实现已通过强制本地 `141/141` tasks、JVM `726/726`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (240 tests)`（`93.258s`）。Room Schema 保持 v32，真机只允许 Redmi `wsvwypiz7xwslvl7`。

## 已提交与已验证控制面幂等收尾边界（通用执行恢复矩阵）

已提交结果只读验证与全部已验证后的控制面收尾必须共享严格的持久化身份边界。每条恢复 marker 必须同时保存恢复类型、稳定边界键、来源/目标状态和具体原因；同一恢复边界只允许一条完整一致的 marker。边界后的第二条、损坏、字段不完整或内容冲突的 marker 必须 fail-closed，不得因为先遇到一条合法记录而忽略后续漂移。状态变化需要 CAS 时，Run 状态、`run.status` 事件与 marker 必须在同一 Room transaction 提交；启动关闭旧 Run 的活动 Step、待审批、Recovery 与 Run 终态也必须原子收敛。

`step.created / step.status` 必须使用 typed metadata 绑定 `stepId`、sequence、step type 和 from/to status。恢复策略必须逐项核对最后验证 Step、恢复总结 Step 与对应事件，不得只按事件类型、文案或时间邻近猜测归属。恢复入口写入或命中 marker 后必须重新读取 Room Detail 并重新评估；旧快照不能继续进入 Runtime。`COMPLETED recovery.summarize` 缺少 typed 总结事件、总结后出现模型/审批/工具等业务事件、Step/Event 身份不一致或边界漂移时必须拒绝恢复。

全部 ToolResult 成功且所有 `tool.verify` 为 `PASSED` 时，只允许三个可达控制面阶段幂等重入：尚未创建恢复总结；恢复总结 Step 为 `RUNNING`，typed 总结事件可能尚未写入或已经写入；恢复总结 Step 与 typed 事件已完成但 Run 尚未进入终态。总结 Step 与事件必须在 Room transaction 内 get-or-create，并发启动协调器只能留下一个 Step、一个总结事件和一个 Run 终态。该路径只能重建已持久化可信工具上下文并生成本地总结，不得调用旧 LLM、Executor、重放工具、追加第二条验证事实或继续 Workflow 后续步骤。

验收必须覆盖 marker 缺失/重复/损坏/冲突、状态 CAS 漂移、事务回滚、typed Step 身份错配、不可达业务尾部、已完成总结缺事件、三个可达总结持久化窗口、重复与双协程并发恢复、启动关闭原子收敛，以及不调用旧模型/Executor/Workflow。当前恢复聚焦 JVM `123/123`、完整 JVM `717/717`，强制 Gradle `141/141` tasks、Lint `0 error / 51 warnings` 和三类 APK 通过；仅 Redmi Room 定向 `OK (36 tests)`。解锁并保持唤醒后的 Redmi 默认完整为 `OK (237 tests)`、耗时 `93.062s`；首轮锁屏的 `59` 条前台失败已由失败单项和完整复验确认不是产品回归。最终 README/docs 语料门禁为 `OK (1 test)`，正式 `v0.1.13` 已恢复。Room 保持 v32，设备工具仍不进入 Workflow/后台。

## 尚未提交受控关联重试边界（通用执行恢复矩阵）

只有已持久化 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE` 的旧 Run 才能进入受控关联重试。请求和确认必须分别读取 Room 最新 Detail，重新核对来源 Profile、当前 Registry、typed `run.recovered -> run.status=CANCELLED` 收敛链、`fromStatus=EXECUTING / toStatus=CANCELLED`、恢复处置码和重试证据指纹；任何恢复后业务事件、Tool Ledger、状态、定义或指纹漂移都必须 fail-closed。普通 `NOT_COMMITTED` 不得被自动升级为受控重放。

第一次用户确认只允许创建关联新 Run，不代表工具审批。确认通过后必须继续恢复来源 USER 附件并使用来源 Profile/Provider preflight；创建新 Run 前必须再次从 Room 与生产 Registry 权威重核完整资格，执行前还要匹配冻结恢复契约。新 Run 必须带 `retryOfRunId`，生成与来源不同的 ToolCall ID，并持久化来源 Run、来源 ToolCall、新 ToolCall 和定义指纹。来源工具名称、风险和参数不得重新规划或改写。

新 Run 必须走正常工具校验、权限和逐次审批；批准后只允许执行该冻结调用一次并直接总结，不得调用模型规划额外工具。旧 Run、旧 Tool Ledger、旧审批、旧模型协程、旧 Executor 和旧 Workflow 必须保持不变。受控重放只开放前台直接 Agent Run 重试，不进入 Workflow 或后台自动化；Room Schema 保持 v32，真机验收只使用 Redmi `wsvwypiz7xwslvl7`。

验收必须覆盖：收敛事件缺失或状态错误、恢复后异常业务事件、证据/当前定义/处置码漂移、请求与确认两次 Room 读取、创建前第三次重核、来源 Profile、新 ToolCall ID、新审批、零次模型规划、工具只执行一次、关联审计 Codec 持久化和旧 Run 完全不变。当前实现已通过 JVM `707/707`、仅 Redmi 真实磁盘纵向 `OK (1 test)`、专用 Compose `OK (1 test)` 和默认完整 instrumentation `OK (235 tests)`；本需求文本第一次重新打包后的项目语料为 `OK (1 test)`，写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`。

## 尚未提交安全重放资格边界（通用执行恢复矩阵）

`NOT_COMMITTED` 只能说明当前持久化证据没有进入工具副作用边界，不能直接授权重放。工具默认必须使用 `ToolNotCommittedReplayPolicy.DENY`；只有同时具备稳定幂等键、显式 `CONTROLLED_SAME_CALL` 和逐次用户审批的工具才允许进入资格评估。当前生产范围只包括 `notes.create` 与 `memory.remember`，新工具不得因为风险等级或当前实现看似安全而自动继承资格。

Runtime 必须在 ToolCall proposed 与 validated 事件中持久化同一版本化恢复契约。契约必须冻结工具名称、说明、风险、审批/验证/重放策略、超时、后台能力、Android 权限、输入 Schema 和业务校验器版本语义，并使用 canonical SHA-256 指纹防止当前 Registry 事后升级历史证据。未知未来策略、缺少快照、Schema/契约版本不匹配或当前定义漂移必须 fail-closed。业务校验器代码无法稳定序列化时，语义变化必须显式递增契约版本。

Run 资格必须同时满足：状态为 `EXECUTING`；原 Profile 快照允许该工具；独立 Tool Ledger 完整；链尾 ToolCall 已 validated、没有 ToolResult、没有对应 `TOOL_EXECUTE`；所有前序调用成功且验证通过；唯一审批记录为 `APPROVED`。审批 requested 事件必须保留原始 `PENDING`，requested/decided 的工具、风险、参数和定义指纹必须一致，顺序必须为 validated→requested→decided；最后一个审批 Step 必须完成，且其后不得出现任何步骤。任一字段、事件顺序、步骤或定义漂移均拒绝资格；在本资格评估中，链尾仍缺少 ToolResult 却已出现执行步骤时必须继续按 `COMMIT_UNKNOWN` 处理。已持久化且严格符合失败结算契约的失败 ToolResult 由独立失败终态结算边界处理，不属于本重放资格。

资格通过只允许持久化 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE`，供后继受控关联重试在重新核验证据和用户控制下创建 `retryOfRunId` 新 Run。本资格阶段不得调用工具、恢复旧模型协程/Executor、继续旧 Workflow、伪造 ToolResult 或原地继续旧 Run；启动收敛继续把旧 Run 与活动 Step 置为 `CANCELLED`。Room Schema 保持 v32。验收必须覆盖当前定义、参数、审批指纹、状态与事件顺序漂移，历史契约缺失、默认拒绝、执行步骤降级、Codec 未知策略、Runtime 双事件一致性和 Room 磁盘重开；真机只允许 Redmi `wsvwypiz7xwslvl7`。

## Agent 启动前校验协调边界（横向可靠性工程）

普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批必须通过单一 `AgentLaunchPreflightCoordinator` 完成启动前校验。普通 `/agent` 的会话为可选，继续允许既有发送链创建新会话；其余四类入口必须先证明指定会话仍存在，并保留各入口原有的缺失提示。会话错误必须先于 Profile、未注册工具和 Provider 错误返回。

普通 `/agent`、Workflow 与两类重试必须使用当前选中 Profile；恢复后审批必须优先使用原 Run 的 Profile 快照，只有旧 Run 没有有效快照时才能回退当前 Profile。Profile 校验顺序固定为可运行性、未知工具、Provider 请求配置；未知工具名称必须稳定排序。Provider 仍按当前保存配置解析，校验只保证当前时刻一致性，不得在迁出时新增执行前二次校验或改成 Workflow 逐步骤重校验。

协调器必须为同步、无 UI/Room/网络副作用的纯决策边界，只返回强类型 `Ready / Rejected`。成功后的导航、确认弹层清理、附件读取、发送态、Room 写入、Runtime 调用与 Workflow 后续步骤继续由 ViewModel 宿主负责。`ProviderRequestConfig` 含解密 API Key，只能在当前进程启动链内传递，不得写入日志、UI、Room 或事件；其字符串表示必须脱敏 Base URL、API Key 和全部自定义 Header。

验收必须覆盖会话错误优先、Profile 缺失/非法、未知工具排序、Provider 缺失/URL/模型错误、普通空会话、历史快照优先、旧 Run 缺少快照时回退当前 Profile、冻结配置与凭据字符串脱敏。当前聚焦 JVM 为 `10/10 + 1/1`；完整门禁为 `140/140` tasks、JVM `656/656`、Lint `0 error / 50 warnings / 0 information`、三类 APK、仅 Redmi 默认完整 `196` 条（`184 passed / 12 skipped / 0 failed`）与最终文档语料 `OK (1 test)`。本切片保持 Room v32，不采集 Shadow 样本，不改变第 101/102 项及设备后台门禁。

## Provider 模型同步协调边界（横向可靠性工程）

已保存 Provider 的 `/models` 同步必须由单一 `ProviderModelSyncCoordinator` 编排。网络请求前必须校验 Base URL，并使用 trim 后的 Base URL/API Key、空模型和当前可配置 User-Agent 构造请求；上游模型按返回顺序去重，当前模型仍有效时继续保留，否则回退到首个模型或空值。`availableModels / enabledModels` 必须延续现有行为更新为本次上游全集，不得在迁出过程中顺手改成旧启用列表的交集。

批量同步必须按用户看到的 Provider 顺序逐项执行并发布结果；普通网络、协议、认证或保存失败只收敛当前项并继续下一项，协程取消必须原样传播并终止后续项。不同 Provider 的单项网络请求可以并行，但完整 Provider 快照提交必须通过同一互斥边界串行，避免迟到结果覆盖另一项刚保存的配置。

提交前必须等待更早的 Provider 快照保存结束，并从最新 UI 快照重新查找 Provider。规范化 `/models` URL 或 trim 后 API Key 漂移时返回 `Stale`，Provider 已删除时返回 `Missing`；名称和当前模型采用最新用户配置。Room 保存期间若 Provider 列表、选中项或身份再次变化，必须拒绝迟到结果并重新排队最新快照。只有 `ProviderRepository.save()` 成功后才能发布 `Succeeded`，随后才允许修复空模型 Agent Profile；网络成功不得冒充配置已持久化。

协调器只返回 `Invalid / Failed / Missing / Stale / Succeeded` 强类型结果，不持有 Compose 页面状态、Agent Runtime、Workflow 或 Shadow。ViewModel 只负责 busy、批量逐项结果和弹窗投影。验收必须覆盖请求规范化、去重/回退、无效 URL 网络前拒绝、稳定失败分型、取消传播、Provider 删除、身份漂移、批量顺序与失败继续，以及并发提交串行。当前聚焦 `8/8`、完整 JVM `645/645`、Lint `0 error / 50 warnings`、三类 APK、仅 Redmi 默认完整 `OK (196 tests)` 与最终文档语料 `OK (1 test)` 通过。Room v32、第 101/102 项及设备后台门禁不变。

## 候选记忆协调边界（横向可靠性工程）

候选记忆的有界列表、成功回合来源身份、采集和接受/拒绝必须由单一 `AgentMemoryCandidateCoordinator` 编排。普通聊天来源必须包含会话 ID、空 Run ID 和稳定摘要，Agent Run 来源必须携带同一会话与真实 Run ID；Store 返回无候选、记录缺失和异常必须分别映射为 `Ignored`、`Missing` 和 `Failed`，不得把正常空结果伪造成错误。

同一候选 ID 的接受与拒绝必须先领取短生命周期 claim；第二个决定返回 `Busy`，不得并发写 Room。不同候选不能被全局串行化。Room 异常、协程取消和外层 Job 取消均必须释放 claim，取消继续原样传播，后续操作可以重试。关闭候选功能必须取消旧列表 Job、清空 UI 列表并结束 loading；迟到 Room 读取不得重新填充已关闭界面，但关闭开关不得删除候选或正式记忆。

协调器只能路由现有列表、创建、接受和拒绝入口并返回 typed outcome，不得复制敏感过滤、规范化去重、同主题冲突、正式记忆写入、FTS 或 transaction。候选仍只在完整成功的普通聊天或 Agent Run 后采集，失败/取消回合不采集；ViewModel 继续负责 Compose 结果、页面 Job 和刷新。验收必须覆盖有界读取、两类来源、无候选/异常分型、接受/拒绝、Missing、同 ID Busy、不同 ID 并行及取消后重试。当前聚焦 `7/7`、完整 JVM `637/637`、Lint `0 error / 50 warnings / 1 hint`、三类 APK、仅 Redmi 默认完整 `OK (196 tests)` 与最终文档语料 `OK (1 test)` 通过。Room v32、第 101/102 项及设备后台门禁不变。

## 恢复后 Agent 审批协调边界（横向可靠性工程）

进程重建后重新展示的链尾审批必须由独立 `RecoveredAgentApprovalCoordinator` 编排，不得复用只管理当前进程 waiter 的 `AgentApprovalDecisionCoordinator`。每次批准或拒绝都必须重新读取 Room 中的 Run detail，并通过 `AgentRunResumePolicy` 确认 Run 仍为 `WAITING_APPROVAL`、恰好一个 `PENDING` Approval 与链尾 ToolCall 完全一致、所有前序工具均已验证且请求/Run/会话/ToolCall/参数身份未漂移。旧 UI、过期 request 和证据变化必须 fail-closed，不得读取内存历史继续执行；并发第二个决定必须返回 `Busy` 且保留其 `PENDING` 卡片，不得按 stale 清除。

批准路径必须在审批决定写入前恢复原 USER 消息的单一可信附件；附件、Skill、Profile 或配置前置失败且 Room 审批仍为合法 `PENDING` 时，卡片必须恢复 `deciding=false` 供用户重试，不能提前消失。停止发生在决定落库前时同样保留可重试卡片；决定已落库或 Run 已进入终态时不得把审批重新开放。真正执行仍由 `AgentRunUseCase.resumeApprovedRun()` 与 `MinimalAgentRuntime` 完成，继续使用原 Profile/Skill/预算/已验证前缀，不重放已完成工具。

拒绝路径必须在同一 Room transaction 内重新核验证据，依次把 Approval 写为 `DENIED`、活动审批 Step 写为 `FAILED`、原 Run 写为 `FAILED`；任一步异常必须整体回滚。Repository 返回 `null` 时不得补写 Run 终态。协调器只能返回 `Completed / Rejected / StillPending / Busy / Stale / Failed` 等强类型结果；Compose 投影、Provider 选择、消息保存、Workflow Ledger 与后续步骤仍由 ViewModel 宿主负责。

验收必须覆盖合法恢复只执行一次、最新证据漂移、附件失败保留审批、Repository 过期拒绝、原子拒绝顺序和批准/拒绝并发互斥。当前协调器 JVM 为 `6/6`，完整本地 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`，三类 APK 成功；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。本切片保持 Room v32，不采集 Shadow 样本，不改变第 101/102 项及设备后台门禁。

## Agent 审批决策协调边界（横向可靠性工程）

前台直接 Agent 和前台 Workflow 的进程内审批等待必须由单一 `AgentApprovalDecisionCoordinator` 管理。每次 Repository 创建审批后注册携带 `requestId + conversationId` 的独立 ticket；注册新 ticket 必须取消旧 waiter。用户批准或拒绝时只有匹配当前 `requestId` 的首次调用能领取 claim，连续点击、批准/拒绝交叉调用、过期 UI 和旧 claim 均不得启动第二次 Room 写入。

Room `decideApprovalRequest()` 成功返回后才能完成 waiter 并让 Runtime 继续。写入抛异常时必须只释放当前 claim、把同一审批投影恢复为 `deciding=false` 并显示可重试错误；Repository 因 Run 已终止、请求已处理或记录不存在返回 `null` 时必须取消 waiter，不得把未持久化的批准交给工具执行。停止生成必须取消当前 ticket 并使已领取 claim 失效；旧 ticket 的完成、释放和 `finally` 清理不得清除或完成后来注册的新审批。

协调器只能拥有进程内 ticket、claim 与 `CompletableDeferred`，不得写 Room、决定风险/策略、维护 Run/Workflow、投影 Compose 或恢复进程后的审批。Room v32 继续是审批事实源，`XiaoLingViewModel` 继续执行 Repository 写入、UI 错误呈现和宿主副作用。验收必须覆盖一次性领取、失败释放后重试、替换时旧 waiter 取消、停止取消、过期 ticket 隔离和 Repository 无可决定记录时 fail-closed。当前结果为聚焦 `5/5`、完整 JVM `624/624`、Lint `0 error / 50 warnings / 1 hint`、三类 APK 成功、仅 Redmi 默认完整 `OK (195 tests)`；7 份长期文档语料为 `OK (1 test)`。本切片不采集 Shadow 样本，不改变第 101/102 项边界。

## 会话级 Agent 运行态 Store 边界（横向可靠性工程）

进程内 Agent UI 运行态必须以 `conversationId` 为唯一归属，同时保存该会话最新 Run 和待审批投影。新 Run 只能替换同会话旧 Run；审批从等待进入 `deciding` 只能替换同会话审批，不得影响 Run 或其他会话。审批批准、拒绝或取消后只清除审批，Run 必须继续保留；删除会话时必须在新会话投影前同时清除该会话 Run 与审批。

新建占位会话必须显式返回空运行态，不能因 ID 复用或迟到加载恢复旧卡片；该投影不得反向删除 Store 中其他会话状态。启动恢复必须从持久化 Run/Approval 明细重建进程内 Store，并只把当前选中会话的状态投影给 UI。ViewModel 不得再绕过 Store 维护第二张 Run/Approval Map。

本 Store 只能承载进程内 UI 运行态，不得持久化、决定或执行审批，不得成为 Room Run/Approval、Agent Runtime、Tool Ledger、Workflow 或 answerability Shadow 的第二事实源。验收需覆盖同会话替换、跨会话隔离、`deciding` 更新、只清审批、整组清理和禁止占位恢复；完整门禁必须记录 JVM、Lint、Debug/Release/AndroidTest APK 与仅 Redmi instrumentation。当前结果为聚焦 `5/5`、完整 JVM `619/619`、Lint `0 error / 50 warnings`、默认完整 `OK (195 tests)`，第 101/102 项边界不变。

7 份长期文档更新后必须重新打入 AndroidTest assets，并通过 Redmi 项目文档语料门禁；本轮结果为 `OK (1 test)`。

## 第 101 项 answerability Shadow 持续观察边界

第 101 项是跨真实使用窗口的持续观察，不是一次性完成阶段。每个窗口只能在用户显式开启、同一应用进程、前台直接 `/agent`、答案成功保存且存在冻结知识候选时采样；不得在同一窗口堆样本，也不得断网、篡改认证或伪造协议错误制造自然失败。词法兜底必须如实记录，不得当作 Embedding 质量证据。

首个已记录窗口仅取得 `1` 条完成样本：词法查询 `Agent Run retryOfRunId` 命中 `3` 个候选，Judge 形成直接回答；样本、完成和 Judge attempt 均为 `1`，取消、异常、未知、跳过及旁路错误均为 `0`。成本为耗时 `5009ms`、TTFB `5002ms`、Prompt `10150B`、Tokens `2720/209/2929`、usage attempts `1`。关闭开关并删除测试会话后，notice 必须从有效 `1 / 裁剪 0` 变为有效 `0 / 裁剪 1` 且累计成本不回退；临时知识文档和下载文件必须删除，知识文档恢复为 `0`。

第 97 至 101 项已记录窗口只允许按阶段报告人工合计：样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`；成本 `43846ms / 43777ms / 66995B / 17164+1822=18986 Tokens`。不得把该合计表述为跨进程 tracker 或 Room 持久化。八次 Judge 均未出现自然网络、协议或认证失败，也没有明显成本异常，因此继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；不得提前进入第 102 项。文档同步后必须通过强制 JVM `614/614`、Lint `0 error`、Debug/AndroidTest APK、仅 Redmi 文档语料 `OK (1 test)` 与默认完整 `OK (195 tests)`。

## Agent Run 关联重试协调边界（横向可靠性工程）

失败、取消、阻塞或预算耗尽 Run 的关联重试必须继续使用 `AgentTaskRetryPolicy` 作为唯一资格与副作用证据来源。已有发送或重试正在进行、来源 Run 不存在、Run 已不再可重试时必须给出稳定失败事件；需要确认的 Run 必须冻结弹窗打开时的证据码和 canonical fingerprint。用户确认时必须重新读取当前 Run 详情并核对状态、证据码和指纹，即使分类仍相同，只要账本、Receipt、工具调用或验证证据漂移，也必须刷新确认并要求再次批准，不得沿用旧授权继续执行。

确认通过或无需确认时，协调器只进入准备阶段。原会话存在性、当前 Agent Profile/Provider 可运行性、会话导航和真正的 `sendAgentRun` 仍由 ViewModel 宿主负责；协调器只能异步读取来源 USER 消息的可信单一附件，并生成包含原会话、`/agent` 原目标、附件快照和 `retryOfRunId` 的启动请求。来源 Run 的状态、步骤、事件、审批与 Tool Ledger 均不得修改；新 Run 继续重新规划、审批和验证。附件读取失败必须发布稳定 `Failed` 事件并清理忙碌态，协程取消继续传播；用户取消确认必须发布 `Cancelled` 并只清理未决确认。

ViewModel 只投影 `ConfirmationRequired / ConfirmationRefreshed / PreparationRequired / RetryStarting / RetryReady / Failed / Cancelled` 事件，并继续保有 Compose 状态和宿主副作用。本次迁移不得修改 Room Schema、Agent Runtime、工具权限、Workflow、设备工具后台门禁或第 101 项 Shadow 低频规则。验收必须覆盖无需确认、成功写工具需确认、同码证据漂移、附件恢复、旧 Run 不变、`retryOfRunId`、附件读取失败、请求拒绝、确认来源消失/状态变化和用户取消；聚焦 JVM 为 `7/7`，完整门禁必须以实际 JVM、Lint、APK 和仅 Redmi 默认 instrumentation 结果记录。

## 第 100 阶段 Android 系统分享入口 v1 边界

系统分享入口只接收 `ACTION_SEND`，不得声明或兼容 `ACTION_SEND_MULTIPLE`；即使调用方在单个 `ACTION_SEND` 中塞入多项 `ClipData`，文本和图片也必须统一拒绝。Manifest 仅暴露 `text/plain`、`image/png`、`image/jpeg`、`image/jpg` 和 `image/webp`；不支持 GIF、PDF、任意文档或通配 `image/*`。文本需规范 CRLF/CR、去除首尾空白，规范化后必须非空且不超过 20,000 字符。图片必须只有一项、使用小写 `content://` URI，并继续通过既有 `ImageAttachmentReader` 的 8 MB、MIME、文件签名和解码验证；`EXTRA_STREAM` 与 `ClipData` 携带相同 URI 属于兼容性重复，携带不同 URI 必须按多图拒绝，不得为分享入口建立放宽的第二套附件读取路径。

外部 Intent 只能创建用户可编辑的新会话草稿，不得自动发送、调用普通聊天 Provider、启动 `/agent`、触发工具、Workflow 或后台任务。编辑器稳定空闲时可直接打开草稿；已有文本、图片、文档、未决分享或正在发送、附加、加载时不得静默覆盖。已有本地草稿时必须展示“打开分享/忽略分享”，只有用户明确打开才替换；已有未决分享时保留第一个并明确拒绝新的分享，用户处理完后需从来源应用重试。

冷启动分享必须等待 Room/Keystore 初始化完成后再投影，不能被初始化快照覆盖；`singleTop + onNewIntent` 必须支持运行中分享；Activity 重建不得重复导入。外部应用可以控制 referrer 和任意 Intent extra，因此二者不得作为可信来源身份或内部去重凭据；界面只显示稳定的“外部分享”提示，用户编辑、移除分享图片、图片读取失败或切换会话后清理提示。解析异常、空内容、超长文本、多附件、缺失 URI、非 `content://`、不支持 MIME 均需给出稳定拒绝结果，不得崩溃或降级为自动发送。

验收必须覆盖纯 Kotlin 解析/投影策略、Manifest MIME 与 `ACTION_SEND_MULTIPLE` 不可解析、双来源 URI 冲突、冷/热启动、Activity 重建、伪造 extra、草稿冲突、图片成功/失败校验、编辑/移除/切换会话后的来源提示清理和“无自动发送”。真机只允许 Redmi `wsvwypiz7xwslvl7`；完整门禁必须以实际 JVM、Lint、APK、文档语料和默认 instrumentation 结果记录，不能由第 99 阶段数字算术推导。

## 第 99 阶段 answerability shadow 低频观察边界

真实 Shadow 观察必须分散在间隔开的用户显式开启窗口，不得为了达到样本数量在同一窗口持续压测，也不得通过断网、篡改认证或伪造协议错误制造“自然失败”。每次显式开启最多授权一轮观测：只有候选存在、答案成功保存且调用前仍保持开启时才消费授权，开关检查、关闭和持久化必须在同一原子门禁内完成，多个并发答案只能有一个进入协调器；候选缺失、保存失败或提前撤销不消费窗口，观测开始后的成功、未知、取消或异常均不得让开关继续保持开启。每个窗口仍只允许同一应用进程中的 `DIRECT_FOREGROUND` `/agent` Run 进入 Judge；普通聊天、Workflow、后台 Worker 和未形成成功答案的 Agent Run 不得进入样本、attempt 或失败分母。

设置页必须从匿名跨进程摘要展示最早记录时间、最新记录时间和二者的实际跨度，使用设备本地时区并在证据缺失、顺序异常时显示未知。该投影只能帮助人工核对，不得内置未经预注册的小时/天数阈值，不得自动声明样本已满足分隔、calibration/validation、JSON/SAF 出口或 production enforcement 资格。

Agent Run 因无候选、预算耗尽或工具步数耗尽而失败时，只有确实进入 tracker 的稳定 Shadow 事件才允许计数；没有成功答案和合格候选时不得伪造 `SKIPPED`、Judge 失败、取消或 usage。知识检索使用词法兜底形成候选时必须如实记录，不能把 answerability Judge 结果表述为 Embedding 质量证据。

第 107 阶段 Redmi 真实窗口验证了这一边界：首次 Run 连续执行 4 次 `knowledge.search` 后因工具预算耗尽，没有成功答案，因此一次性授权保持开启，Room v33 匿名账本仍为 `2`，attempt、usage 和失败分桶均不增加；随后第二个 Run 只执行 1 次检索并成功保存答案后才消费授权，新增第三条 `COMPLETED / BOUND / ACCEPT`。当前三条最早到最新跨度 `5 小时 24 分 46.689 秒`，仍只属于同日证据，不满足长期分隔、calibration/validation、JSON/SAF 或 production enforcement 资格。

第 99 阶段首批窗口新增样本 `3`、完成 `3`、Judge `3` 次，直接回答 `2`、部分回答 `1`；取消、异常、答案保存失败、Shadow Store 失败和绑定未知均为 `0`。成本为耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。关闭开关只能撤销后续授权；删除四个测试会话后 notice 必须从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3` 且累计成本不回退，删除临时知识文档后恢复文档 `0`、原会话 `1`。

跨阶段样本合计只允许从阶段报告人工汇总，不得暗示 tracker 已跨进程持久化。第 97 至 99 阶段书面记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次仍没有自然网络、协议或认证失败；该证据不得用于增加 Room Store、Schema、跨进程 notice 或生产拒绝。继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。验收必须同时通过 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)`。

## 第 98 阶段 answerability shadow Redmi 扩样本验收边界

扩样本必须限定在同一应用进程、用户显式开启、`DIRECT_FOREGROUND` 且答案成功保存的真实 `/agent` 链路。没有可用知识候选时必须记为 `SKIPPED / NO_CANDIDATE`，不得调用 Judge，也不得伪造成 `UNKNOWN`、失败、取消或 token usage。自然 `BUDGET_EXHAUSTED` Agent Run 若未满足 Shadow eligibility，不得进入 Shadow 样本、attempt 或失败分母。

关闭开关只能撤销后续 Shadow 授权，不得删除或修改知识文档、会话及消息。删除测试会话只能裁剪对应进程内 notice，不得回退累计样本和成本；删除本轮测试知识文档后，知识文档必须恢复为 `0`，并保留原会话 `1`。第 98 阶段验收累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次、取消 `0`、异常 `0`，完成判定为直接回答 `2`、部分回答 `2`；累计耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`。notice 发布 `4`，删除测试会话后从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`。

本轮不得增加生产持久化或执行权：继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。两次自然无候选跳过与一次自然预算耗尽只证明入口隔离有效，不能表述为已经取得自然 Judge 失败分布，也不能据此开启 Room Store、跨进程 notice 或生产拒绝。验收必须保持完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、仅 Redmi 的文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)`；不得连接或启动 Pixel_9。

## 第 97 阶段 answerability shadow 真实样本与遥测边界

只有用户显式开启后，前台直接 Agent 的真实 answerability shadow 才能进入样本分母；默认关闭期间、普通聊天、Workflow 和后台 Worker 均不得产生样本。答案必须先展示并成功保存，Judge 请求发出前必须再次检查开关；用户在此之前关闭即撤销本次授权，不得继续发送问题和知识候选。Judge 已发出后的取消可以记录取消终态，但不得伪造上游未返回的 token usage。

进程内 tracker 必须固定容量上限且重启清空，只允许记录样本终态、Judge attempt、延迟/TTFB、Prompt 字节、input/output/total Tokens、usage attempt、稳定失败枚举和 notice 发布/有效/裁剪数量。禁止记录问题、答案、候选正文、引用正文、原始响应、消息 ID、Base URL、API Key 或其他凭据。重试成功也必须保留前序失败分类；答案保存失败、Shadow Store 失败、身份不匹配、候选缺失、绑定未知、用户取消和意外异常必须分别统计。

第 97 阶段继续固定 `store=null / persistenceMode=NONE`，不得增加 Room 表、migration 或 notice 持久化；`enforcementApplied=false` 与 `productionEnforcementEnabled=false` 不得改变。验收必须覆盖设置页可滚动摘要、真实 Provider 成本、notice 发布与会话删除/重载裁剪、普通聊天隔离和关闭恢复。结果为完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的定向 `OK (1 test)` 与默认完整 `OK (191 tests)`；真实样本 `1` 条完成、Judge `1` 次、失败/取消/异常为 `0`。不得连接或启动 Pixel_9。

## 第 96 阶段 answerability shadow 生产接线边界

生产 Judge 必须复用第 92 阶段冻结协议：Responses、非流式、关闭 reasoning summary、`temperature=0`、`topP=1`、`maxTokens=220` 和严格 JSON codec。adapter 每次 attempt 只允许一个 Provider 请求，不得自行重试；实际 Judge identity 必须从当前 Provider 配置的 `providerId / model / Base URL fingerprint` 派生，不能复制调用方期望值。请求包含用户问题和知识候选，因此必须逐请求关闭全部 HTTP Debug 日志；统一 Provider 的鉴权、User-Agent 和兼容 Header 行为保持不变。

生产 shadow 开关必须独立、默认关闭。只有前台直接 Agent、用户开关开启、实际 Provider identity 与冻结的 `redmi-provider-compatibility / gpt-5.5 / configuration fingerprint / prompt version` 完全匹配时才允许请求；身份漂移必须在网络前保持关闭。普通聊天、Workflow、后台 Worker、恢复链和 enforcement 不得继承该开关。

Agent 答案必须先展示并进入正常会话保存。旁路 publisher 只能等待该答案对应的保存 Job 成功后异步调用 Judge；保存失败、保存被新快照取消、候选缺失、Workflow 来源或 Judge 失败都只跳过 shadow，不得把已成功 Agent Run 改为失败、不得延迟答案显示。Judge 终败或未形成真实 measurement 时不得发布猜测 notice；只有完成观测后形成的绑定可以以进程内 `messageId` 映射投影到知识引用区域，且不得改写消息、可信上下文、MessagePart 或引用。

第 96 阶段固定 `store=null / persistenceMode=NONE`，不得增加 Room 表、Schema 版本或持久化 notice。验收必须覆盖默认关闭、身份完全匹配、固定请求配置、请求/响应/流事件 HTTP Debug 日志关闭、5xx/协议错误分类、保存成功后顺序、保存失败/取消跳过、Workflow 跳过、notice 不改写消息和设置偏好。完整 JVM `593/593`、Lint、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的默认完整 `OK (191 tests)`、真实生产 adapter `OK (1 test)` 和设置/偏好/notice `OK (3 tests)` 均通过；不得连接或启动 Pixel_9。

## 第 95 阶段 answerability shadow 真实测量协调边界

Judge 的共享决策字段必须抽象为 `KnowledgeAnswerabilityAssessment`。带人工真值的 calibration/validation 数据继续使用 `KnowledgeAnswerabilityObservation`，并且只有该离线类型可以携带 `KnowledgeRelevanceLabel`；真实 Agent Run 生成的线上结果必须使用不带 `label` 的 `KnowledgeAnswerabilityShadowMeasurement`，不得伪造人工真值或把线上测量混入离线质量统计。两条路径必须复用同一候选原文证据匹配和字段映射，避免决策语义漂移。

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一协调入口。默认模式必须为关闭；只有前台直接 Agent 来源可以进入 shadow，普通聊天、非直接来源、Workflow 和后台 Worker 必须跳过。调用 Judge 前冻结候选与引用快照；候选 Run、问题、正文或引用不完整，以及缺少冻结绑定时不得发送网络请求，而是保守返回未知绑定。

Judge 请求最多执行两次。只有瞬时网络、限流、服务端和协议失败允许重试一次；认证、普通请求、Judge 身份、候选和未知异常不得重试。上层取消必须原样传播，不得生成 `UNKNOWN`、不得写 shadow 记录。Judge 响应身份与冻结身份漂移时，可以保留本次 measurement 用于审计，但绑定必须为 `UNKNOWN`，原答案、引用和 `enforcementApplied=false` 不得改变。

shadow 持久化必须可选。记录只允许保存来源 Run、已持久化消息 ID、候选 SHA-256 指纹、幂等键、Judge 身份、尝试次数、观测/绑定状态、决策、失败分类和时间；不得保存候选正文、模型原始响应、问题原文副本或引用正文。Store 普通失败只标记持久化失败，不得反向改变已经形成的绑定或用户答案；Store 取消仍必须传播。

验收门禁：新增协调器契约 `14/14`，完整 JVM `578/578`、Lint、Debug/AndroidTest APK 通过；真机只允许 Redmi `wsvwypiz7xwslvl7`，完整 `AndroidJUnitRunner` 为 `OK (188 tests)`，不得连接或启动 Pixel_9。第 95 阶段当时不提供生产 Judge Provider adapter、消息 caller 或答案引用 UI 接线；这些默认关闭的接线已由第 96 阶段完成，Room schema/store、普通聊天/Workflow/后台接入和 `productionEnforcement` 仍关闭。

## 第 94 阶段真实消息流只读 answerability shadow 绑定边界

消息流绑定只允许消费现有 Agent Run 中可信的 `knowledge.search` ToolResult。候选必须来自成功执行，验证状态不能是 `FAILED`，正文和稳定知识引用都不能为空；多步 Run 取最近一条满足条件的执行，旧消息没有执行列表时兼容顶层单工具字段。其他工具、失败执行、无引用或空正文不得进入 Judge 候选。

冻结绑定必须同时包含强类型 calibration/validation `KnowledgeAnswerabilityDatasetIdentity` 和已冻结 `KnowledgeAnswerabilityGate`；两套数据必须绑定同一 Judge identity、版本非空且互异。消息 shadow 只允许 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`；第 92 阶段未通过的 `VERDICT_EVIDENCE_CONFIDENCE_AND_COVERAGE` 禁止进入。实际 Judge identity 不一致、缺少冻结绑定/measurement、来源 Run 为空、measurement 的 `sourceRunId` 与候选 Run 不一致或候选证据不完整，都必须返回 `UNKNOWN`，不能抛错或把未知转换为拒绝。

绑定结果必须在绑定开始时复制并按原顺序保留全部 `KnowledgeReference`，同时保留来源 Run、候选正文、已有 measurement 的时间和 shadow 提示；没有 measurement 时 `observedAt` 必须保持 `null`，不能伪造观测时间。结果固定 `enforcementApplied=false`。绑定只表达观察关系，不得删除、替换、重排答案或引用，不得写 Room、修改消息 schema、接入普通聊天/Workflow 或开启 `productionEnforcement`。第 94 阶段只冻结纯 Kotlin 绑定契约，第 95 阶段补齐 Judge 协调，第 96 阶段完成默认关闭的 Provider/caller/UI 接线；Room Store 与 enforcement 仍关闭。

验收门禁：绑定策略 `7/7`、候选提取 `4/4`；完整 JVM `564/564`、Lint、Debug/AndroidTest APK 通过；真机只使用 Redmi `wsvwypiz7xwslvl7`，完整 `AndroidJUnitRunner` 为 `OK (188 tests)`，不得连接或启动 Pixel_9。

## 第 93 阶段答案可回答性 shadow 呈现边界

答案可回答性 shadow 呈现只能消费现有 `KnowledgeAnswerabilityObservation` 和冻结 `KnowledgeAnswerabilityGate`，不得在展示层重新计算阈值、调用 Provider、读取 Room 或改变检索结果。输入引用必须按原顺序和身份完整保留，结果固定 `enforcementApplied=false`；`ACCEPT` 只能显示“直接回答”的观察提示，`REJECT` 必须按部分回答、未回答、矛盾、证据无法回查或低于冻结门禁区分，缺观测、缺门禁或 `UNKNOWN` 必须显示未知。

`KnowledgeReferencesContent` 的 answerability 提示入口必须默认 `null`，保证未接入的普通调用不变。有提示且零引用时应显示解释但不得显示“知识引用 · 0”；有引用时提示与原折叠引用必须同时存在，不能因 Judge 结果删除、替换或重排引用。第 93 阶段当时不得把该参数接到普通聊天、生产消息持久化、Room、`knowledge.search`、Workflow 或后台 Worker；第 96 阶段仅把它接到默认关闭的前台直接 Agent 旁路。

验收必须覆盖提示状态、引用不变和 `enforcementApplied=false`，并在 Redmi 执行 Compose UI 用例；第 93 阶段结果为既有策略 `7/7`、新增呈现 `5/5`、合计 `12/12`，`KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已随默认完整套件 `OK (188 tests)` 通过。第 93 阶段生产消息流尚未传入该提示；第 96 阶段仅接入前台直接 Agent，enforcement、答案改写和引用过滤继续禁止。Android 真机验证只允许 `wsvwypiz7xwslvl7`，不得连接或启动 Pixel_9。

## 第 92 阶段答案可回答性策略边界

答案可回答性 Judge 只能返回单个严格 JSON 对象，字段集合固定为 `verdict`、`confidence`、`evidence_quotes`、`contradiction_detected`、`reason_code`；verdict 只能是 `ANSWERED`、`PARTIALLY_ANSWERED`、`NOT_ANSWERED` 或 `UNKNOWN`。`ANSWERED` 必须携带候选正文中可匹配的原文 quote；`NOT_ANSWERED` 与 `UNKNOWN` 不得携带 quote；字段异常、解析错误、矛盾或部分回答必须 fail-closed，`UNKNOWN` 不得计作负例拒绝。

策略必须在候选正文上重新匹配并合并 quote 区间，记录 quote 数、匹配数和覆盖率；不能把模型自行生成的“证据”直接展示或用于接受决策。三类预注册特征族分别使用固定 verdict/原文证据、置信度和证据覆盖率；calibration 只能冻结阈值，validation 只能应用冻结阈值。两侧必须绑定同一 Provider/Judge identity、prompt version 和配置指纹，dataset version 必须互异，三桶标签完整且 case ID 不得漂移。

第 92 阶段只允许独立测试和显式 Redmi 探针采集 shadow 证据，不得读取生产 Room、修改召回、改变答案引用 UI、升级相关性身份或开启 `productionEnforcement`。预注册真实探针为两套各 6 个用例、每例重复 2 次（`12 + 12`）；缺参数时跳过，网络/解析最终失败进入 `UNKNOWN` 并计入失败数。Redmi 真实执行已取得 calibration/validation 各 `12` 条、网络与解析失败均为 `0`；两类 verdict/原文证据特征族通过，覆盖率特征族未通过，`productionEnforcementEnabled=false`。设备只允许 `wsvwypiz7xwslvl7`，不得连接或启动 Pixel_9。

## 第 91 阶段跨主题平移不变特征验证边界

新的相关性实验只能使用生产检索已经具备的审计事实，不得为了实验直接修改 Room v32 或线上召回。预注册特征固定为 `top1 - 候选均值`、`margin / 候选标准差` 及两者组合；非有限 top1/均值/margin、负 margin 或不高于 `1e-12` 的候选标准差必须 fail-closed。calibration/validation 必须绑定同一生产 Provider、模型、配置指纹并使用不同数据集版本；阈值只能从 calibration 真实观测点选择，validation 不得重新选参。

`stage91-cross-topic-calibration-v1 / validation-v1` 必须使用与第 90 阶段不同的全新主题语料，每套 12 篇文档、正例/近负例/远负例各 4 条英文查询并重复 2 次。近负例必须是同主题但语料未覆盖的具体事实，不能被 companion 文档直接回答；Recall@5 仍需 `>=0.80`。相关性标准保持正例接纳 `>=0.90`、近负例拒绝 `>=0.80`、远负例拒绝 `>=0.90`、决策稳定 `1.0`，不得因为新特征接近通过而降低标准。

Redmi 三轮有效结果均为 `24 + 24` 条观测、Recall@5 `1.0 / 1.0`、通过族 `0`。`top1-均值` 与组合的 validation 为正例接纳 `1.0`、近负例拒绝 `0.75`、远负例拒绝 `1.0`、稳定率 `1.0`；`margin/标准差` 为 `0.75 / 0.25 / 1.0 / 1.0`。该结论必须记录为预期门禁否决，不能进入 final holdout、升级 `VERIFIED` 或开启 `productionEnforcement`。后续若继续，必须先建立 answerability/重排证据，而不是使用 validation 回调本轮阈值。完整本地门禁为 JVM `541/541`、Lint、Debug/AndroidTest APK；Redmi 默认全量 JUnit XML 已完成，为 `186` 条（`176 passed / 10 skipped / 0 failed`），且不得连接或操作 Pixel_9。

## 第 90 阶段正式相关性 calibration/validation 预注册门禁边界

正式相关性证据必须绑定同一生产 Provider ID、模型和配置指纹，并使用互异的 calibration/validation `datasetVersion`。七类特征族（raw top1、margin、top1 z-score 及四种组合）只能从 calibration 选择阈值，validation 必须原样评估；不得使用 validation 回调阈值、降低预注册标准或复用旧 holdout。

第 90 阶段 Redmi 真实验收使用 `redmi-production-embedding-v1 / Qwen/Qwen3-Embedding-0.6B`，配置指纹 `2f22bfe3b9db92555f493c173116c58970490ece7fa90b8c7bf156aa7456dbf6`，两套数据各 24 条观测、Recall@5 均为 `1.0`。预注册相关性标准未有特征族通过；最新 raw top1 validation 正例接纳率为 `0.75`，重复取证范围为 `0.625–0.75`，近/远负例拒绝均为 `1.0`。因此“质量门禁否决”必须作为显式、可审计且测试成功的结果，不能把 JUnit 失败断言伪装成通过，也不能升级 `VERIFIED`、进入 final holdout 或开启 `productionEnforcement`。

在新跨主题归一化特征或新标注数据重新注册并达到标准前，生产 Store、`knowledge.search`、普通聊天、答案级引用和 Workflow 不读取该控制面。门禁记录为 JVM `535/535`、仅 Redmi 默认 instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`）；不得连接或操作 Pixel_9。

## 第 89 阶段生产身份绑定与灰度控制面边界

相关性生产身份必须区分 `UNBOUND / CANDIDATE / VERIFIED / REVOKED`。真实 Provider 探针至少校验非空 Provider ID、模型与配置指纹，模型列表包含目标 Embedding 模型，并成功返回数量、维度和有限值均有效的向量；该探针只允许建立 `CANDIDATE`，不得因 `/models` 或 `/embeddings` 成功直接授予生产 enforcement。

配置身份只能保存 Provider ID、模型和规范化 Base URL 的 SHA-256 指纹，不得把原始 Base URL 或 API Key 写入偏好、Room、源码或长期文档。候选升级为 `VERIFIED` 时，身份必须与冻结 gate 的 calibration/validation、全新 holdout 和证据中的 Provider/模型一致；gate 版本、证据版本和配置指纹必须匹配，final holdout 必须明确通过，holdout 数据集不得复用 calibration 或 validation。任一缺失、失败或漂移都必须拒绝升级。

灰度偏好必须继续默认关闭，并新增身份证据版本和配置指纹。控制面只有在偏好本身通过 gate/Provider/模型校验、生产身份为 `VERIFIED`、身份 gate 与当前 gate 一致、证据版本和配置指纹相同时，才允许解析为 `ENFORCE`；候选、撤销、过期 gate、身份漂移或偏好不完整全部回到 `SHADOW`。撤销执行资格与身份审计必须分开：前者清除未来执行键，后者保留身份和证据指针但标记 `REVOKED`。

设置页必须提供独立「相关性灰度控制面」入口，展示身份、Provider、模型、配置指纹、gate、证据和 holdout，并明确当前生产答案路径尚未接入。页面不得提供直接绑定、升级或绕过证据开启 enforcement 的入口。第 90 阶段已完成同一正式 Provider 身份下的新 calibration/validation，但七类特征族均未达到预注册标准；因此 ViewModel、`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台 Worker 继续不读取控制面。门禁为完整 JVM `535/535`、Lint、Debug/AndroidTest APK、仅 Redmi 默认 instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`），以及显式真实校准 `1/1`；不得连接或操作 Pixel_9。

## 第 88 阶段相关性降级、引用一致性与身份灰度边界

本阶段必须在不接入生产拒绝的前提下冻结用户体验与灰度契约。每个 `KnowledgeSearchHit` 必须携带同一次融合输入中的 `LEXICAL / SEMANTIC` 来源；该元数据不得改变现有 RRF、FTS4+LIKE、top-K、enabled/revision 复核或 Room v32。未来低分执行只能移除 semantic-only：词法-only 和词法/语义重叠候选必须保留一次，最终引用集合必须由最终候选直接生成，不能继续暴露已移除 chunk。

用户提示标题固定为“已降级为关键词匹配”“未找到足够可靠的本地知识”“相关性检查暂未应用”。没有词法兜底时允许零引用但必须保留解释；既有调用未传提示时 UI 行为不变。候选来源缺失、决策与来源矛盾，以及 shadow/关闭状态中出现任何删除 disposition 时必须 fail-open，保留当前 hits 与引用。默认关闭的正常 shadow 判断不得向用户显示未实际应用的警告。

灰度资格必须同时绑定 gate 版本、Provider 和模型。偏好缺项、gate 版本过期或身份漂移时自动解析为 `SHADOW`；撤销必须清除 enforcement、gate、Provider、模型四个键。冻结 gate 的 calibration/validation 身份必须完整、Provider/模型一致且 datasetVersion 不同，阈值必须有限；结构非法时直接拒绝，不能降级成可执行资格。Stage 85/86 的实验 Provider ID 不能直接作为正式生产身份，接入前必须以真实 Provider/模型重新绑定并复验。

本阶段不得让 ViewModel、`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 或后台 Worker 读取灰度偏好，也不得修改生产排序、拒绝、Room Schema 或历史记录。验收门禁为 Stage 87+88 聚焦 JVM `16/16`、完整 JVM `522/522`、Lint、Debug/AndroidTest APK，以及只在 Redmi `wsvwypiz7xwslvl7` 执行的默认 instrumentation `180` 条（`173 passed / 7 skipped / 0 failed`）。首次长套件若因设备 dream/keyguard 导致 Compose hierarchy 缺失，只能作为设备状态失败记录；唤醒后的失败批次与完整套件必须重新全绿，不能把失败静默忽略。

## 第 87 阶段生产相关性拒绝设计边界

本阶段只建立可审计的候选决策契约，不把 final holdout 结果直接变成线上拒绝。策略必须绑定 Stage 86 冻结的 gate、calibration/validation Provider/模型身份和 raw top1 下限；默认开关关闭时只能输出 shadow 结论，不得改变现有语义、FTS4 或 LIKE 结果。开关未来开启时，低于下限只能移除语义候选；若同一查询有词法命中，必须保留词法结果，不能把“语义低分”显示成“知识为空”。

Provider/模型漂移、`LEXICAL_ONLY`、`NO_INDEX`、`PROVIDER_UNAVAILABLE`、`DIMENSION_MISMATCH`、缺失或非有限 top1 必须 fail-open，继续保留当前结果；未知事实不得按低分拒绝。策略不新增 Room 列、不改变历史审计、不进入 Workflow/后台路径，也不在本阶段修改 UI 文案。聚焦 JVM `5/5`、完整 JVM `511/511`、Lint、APK 和仅 Redmi 默认 instrumentation `178` 条（`171 passed / 7 skipped / 0 failed`）作为本阶段门禁。下一阶段先验收用户可见回退、灰度与撤销，再评估是否接入生产检索。

## 第 86 阶段预注册验证边界

本阶段只验证 Stage 85 已冻结的 raw top1，不得重新比较特征族、搜索阈值、修改测试语料或启用生产拒绝。冻结门禁版本为 `stage85-raw-top1-qwen-v1`，raw top1 下限为 `0.6416276358587735`；身份必须保留 Stage 85 calibration 的 Provider、模型与 `stage85-calibration-v1`，并记录已见 `stage85-validation-v1`。final holdout 必须使用同一 Provider/模型且 datasetVersion 同时不同于前两套数据；任何身份漂移、空值、非有限值、缺桶或 case 标签漂移都必须 fail-closed。

`stage86-final-holdout-v1` 在首次真实运行前固定为 20 篇全新成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次；不得复用 Stage 82 calibration、Stage 83 holdout 或 Stage 85 calibration/validation 的主题与用例。近负例只询问同主题但两篇文档均未覆盖的具体事实。预注册相关性标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`；排序标准为 Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定率 `1.0`。

Redmi `wsvwypiz7xwslvl7` 已在预注册 commit 后执行有效采集：首次有效运行耗时 `63.077s`；补齐 validation Provider/模型身份校验并同步重建 Debug/Test APK 后，最终复验耗时 `67.018s`。最终 60 条观测的正例接纳 `0.90`、近/远负例拒绝 `1.0`、决策稳定 `1.0`、balanced accuracy `0.9667`，Recall@1/5、MRR 和排序稳定均为 `1.0`，满足全部标准。中间 ABI 不一致和一次检索空分数回归不得计入门禁，也不得用于调参。该结果只允许进入“生产拒绝设计评审”，不能直接修改 Room v32、cosine+RRF、FTS4+LIKE 或 UI；后续若出现失败证据，必须保留冻结阈值和失败事实，不得使用 final holdout 回调或降低标准。显式 Provider 参数缺失时继续 skipped，日志和文档不得包含 Base URL 或 API Key。

## 第 85 阶段验证边界

本阶段只能比较特征族，不得修改生产 `RoomKnowledgeDocumentStore`、Room Schema、UI 或相关性拒绝行为。预注册特征族固定为 raw top1、margin、top1 z-score、raw+margin、raw+z、margin+z、raw+margin+z 共 7 种；每种门禁只能从 calibration 观测值的笛卡尔积选择阈值，validation 只能应用冻结阈值，不能重新选参。数据集身份必须包含 Provider、模型和版本；calibration/validation 的 Provider、模型必须一致，版本必须不同。

`stage85-calibration-v1` 与 `stage85-validation-v1` 必须使用全新且互相隔离的内存 Room，每套包含 20 篇成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次。近负例是同主题但语料未覆盖的具体事实，不能由 companion 文档直接回答；Stage 83 holdout、Stage 82 calibration 及其阈值不得参与本轮搜索。预注册 validation 标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`，同时要求两套语料 Recall@5 `>=0.80`。

有效 Redmi 结果中 raw top1 与 raw+margin 都满足预注册标准且结果完全相同；为减少过拟合维度，下一阶段只把更简单的 raw top1 `0.6416276358587735` 作为待冻结候选。该候选必须在第三套全新 final holdout 上原样验证，不能用 validation 回调；本阶段不得发布生产阈值。完整默认门禁为 JVM `502/502`、Lint、Debug/AndroidTest APK 和仅 Redmi instrumentation `177/177`；6 个真实 Provider 用例缺参按设计 skipped。

## 第 84 阶段验证边界

第 83 阶段已证明原始 cosine 绝对阈值会跨主题漂移，本阶段只能新增相对分布 shadow 观测，不能放宽旧门禁或启用生产拒绝。每次真实语义检索必须基于现有 2000 行上限内、截断 top-K 之前的全部有效候选计算均值、总体标准差和 top1 z-score；z-score 在候选分数整体平移时应保持不变。单候选或零方差没有可证明的相对区分度，z-score 必须为 `null`，不能补零。

Room v31→v32 只为检索审计增加可空的 `embeddingScoreMean`、`embeddingScoreStandardDeviation` 和 `embeddingTopScoreZScore`。v31 历史记录缺少完整候选分布，三个字段必须保持 `null`；不得从 top1、top2、margin 或当前向量索引回填。Provider 未执行、Provider 不可用、无索引或维度不匹配时相对字段同样保持未知。管理 UI 只能在“校准观测”中展示这些值，不使用通过、拒绝或置信度结论文案。

纯 Kotlin 策略必须覆盖总体分布计算、整体平移不变性、单候选/零方差和空/非有限输入。Redmi 必须验证 v31→v32 迁移、生产 Room 写入回读、UI 展示和真实 Provider 语义链。已退休 `stage83-holdout-v1` 只允许用于确认观测链，不允许成为新校准集或阈值搜索来源；其正例与近负例 z-score 区间存在重叠，进一步证明单一 z-score 不能直接上线。完整默认门禁为 JVM `499/499`、Lint、Debug/AndroidTest APK 和仅 Redmi instrumentation `176/176`；5 个真实 Provider 用例缺参按设计 skipped。

## 第 83 阶段验证边界

第 82 阶段候选必须冻结为版本化门禁后再接触独立 holdout。冻结身份至少包含 Provider、模型、门禁版本和校准数据集版本；holdout 必须使用不同数据集版本，Provider 或模型漂移时不得复用旧阈值。门禁阈值、比例标准和输入分数必须为有限值，三个标签桶缺一不可，同一 case ID 不得标签漂移。评估只能应用冻结的 `minimumTopScore + minimumScoreMargin`，不得搜索 holdout 自身最佳阈值或用失败样本回调当前门禁。

本阶段冻结模型 `Qwen/Qwen3-Embedding-0.6B`、门禁 `stage82-qwen-v1`、校准集 `stage82-calibration-v1`，top1 下限 `0.6735426515268672`、margin 下限 `0.0178535973263384`；独立 `stage83-holdout-v1` 使用全新 20 篇成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次。预注册标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`、Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定率 `1.0`。

Redmi 三个独立进程均观测到正例接纳率 `0.80`、两类负例拒绝率 `1.0`、决策稳定率 `1.0`、Recall@1/5 `1.0`、MRR `1.0`、排序稳定率 `1.0`。手冲咖啡和缝纫机张力正例的 top1 稳定低于冻结下限，因此候选相关性门禁失败，即使所有正例仍排在首位也不得降低预注册标准后宣称通过。第 82 阶段候选不具备进入生产拒绝设计的资格；生产 Room v31、cosine+RRF、FTS4+LIKE 词法兜底和审计语义保持不变。下一阶段若继续校准，必须建立新版本并重新分离 calibration/validation/holdout，当前 holdout 只能作为既有门禁的最终否决证据。

默认离线回归仍需独立全绿：JVM `495/495`、Lint、Debug/AndroidTest APK 和仅 Redmi 完整 instrumentation `175/175`；5 个真实 Provider 用例缺少显式参数时按设计 skipped。显式联网 holdout 三轮按预注册断言失败，不得与默认套件通过混写成同一结论。

## 第 82 阶段验证边界

相关性校准必须继续与生产检索解耦。纯 Kotlin 策略接收带稳定 case ID、正例/近负例/远负例标签、top1 与 margin 的观测；三个桶缺一不可，分数必须为有限值，同一 case ID 不得跨标签。每桶必须同时报告样本数、唯一用例数，以及 top1、margin 的 nearest-rank P05/P50/P95。候选门禁只允许从已观测的 `minimumTopScore + minimumScoreMargin` 组合中选择，以正例接纳率、近负例拒绝率、远负例拒绝率三桶等权计算 balanced accuracy，并使用固定 tie-break 保证报告可重复。该候选是同集 shadow 诊断，不得写入生产配置、Room Schema 或检索拒绝状态。

真实校准使用隔离的内存 Room、同一明确 Provider/模型与 20 篇成对主题中文短文；正例、近负例、远负例各 10 条英文查询，每条在同一进程重复 2 次，并从外部启动 3 个独立 Redmi instrumentation 进程。每次观测必须记录短日志，汇总记录 Recall@1、Recall@5、MRR、排序稳定率、三桶分位数、shadow 候选门禁、查询耗时、候选数、向量行数/维度/字节、PSS 与 Java heap。缺少显式 Provider 参数继续 skipped，日志和文档不得包含 Base URL 或 API Key。三轮真实结果虽然全部达到 Recall@1/5、MRR、稳定率和同集 balanced accuracy `1.0`，但阈值选择与评估来自同一数据集；第 83 阶段必须先冻结候选，再使用不参与调参的独立 holdout 验证。完成 holdout 前，Room v31、cosine+RRF、词法兜底与“无生产拒绝”边界保持不变。最终门禁为 JVM `491/491`、Lint、Debug/AndroidTest APK 和仅 Redmi 完整 instrumentation `174/174` 通过；4 个真实 Provider 用例缺少显式参数时按设计 skipped。

## 第 81 阶段验证边界

每次使用 Embedding 的知识检索都必须在既有 `providerId + model` 身份下记录 shadow 校准数据：有效候选数、top1、top2，以及仅在至少两个有效候选存在时计算的 `top1 - top2` margin。未执行 Provider、Provider 不可用或历史记录的字段保持 `null`；索引为空或已有索引全部无法比较时候选数为 `0`，不得用零分补造未知观测。Room 从 v30 升到 v31，迁移必须保留历史审计并让四个新字段全部为 `null`。知识管理页只能把这些值标为“校准观测”，不得显示为通过、拒绝或相关性结论。

真实校准必须继续使用隔离的内存 Room 和固定 10 篇中文语料，按同一 Provider/模型至少覆盖正例、近负例、远负例三类；每类记录查询、词法命中数、top-K 文档、候选数、top1、top2 与 margin。用例只校验身份、有限值、排序关系和审计完整性，不把单次样本中的经验分数写成生产阈值。首轮 Redmi 每类 2 条的分布仅作为下一阶段扩充数据的基线；在更多标注样本和 Provider/模型分桶验证完成前，生产 cosine+RRF 行为保持不变，不新增 `RELEVANCE_INSUFFICIENT` 或其他拒绝状态。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 和 Redmi 完整 instrumentation `174/174` 通过；4 个真实 Provider 用例缺少显式参数时按设计跳过。

## 第 80 阶段验证边界

真实 Embedding 规模基线必须使用固定、内置且有界的语料，不依赖手机正式知识库或外部文件。至少导入 10 篇语义不同文档，使用 5 个与目标正文无词法交集的跨语言正例，每例重复检索两次。纯词法 Store 必须零命中，真实语义 Store 必须记录 `USED` 及正确 Provider/模型；Recall@5 不低于 `0.8`、MRR 不低于 `0.7`、重复排序稳定率不低于 `0.8`。向量行数必须等于 chunk 数，维度必须一致，原始 BLOB 字节数必须等于 `rows * dimensions * 4`。索引和查询使用单调时钟记录耗时，同时记录 SQLite 页、PSS 和 Java heap；网络耗时与 PSS 只作观测证据，不作易波动的硬门禁。无关查询必须单独记录返回数；在当前没有相似度阈值时，不得伪造负例准确率。真实验收必须在 Redmi 上以三次独立 instrumentation 进程启动重复，使每轮 PSS 都有独立进程基线；单次测试内部不循环伪造三个独立样本。缺少显式 Provider 参数时默认跳过，配置和密钥不得写入 Git 或报告。本阶段最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `171` 个用例，`168` passed、`3` skipped、`0` failed。

## 第 79 阶段验证边界

真实 Embedding Provider 验收不得只停留在 `/embeddings` 协议或假向量 Store。显式联网测试必须直接组合生产 `OpenAiKnowledgeEmbeddingProvider` 和 `RoomKnowledgeDocumentStore`，先确认模型列表包含指定模型，再在隔离的内存 Room 中导入至少两个语义不同文档。验收查询必须与目标文档没有词法命中，并证明纯词法 Store 返回空、真实语义 Store 首位命中目标文档；检索审计必须为 `USED`，Provider/模型身份和最终 chunk IDs 必须一致。索引摘要必须记录非零维度与分块数，显式重建不得改变 document revision。测试缺少显式 Base URL、API Key 或模型参数时必须跳过，完整测试套件不得依赖公网；真实配置与密钥不得写入源码、测试报告或 Git。本阶段最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `170` 个用例，`168` passed、`2` skipped、`0` failed。

## 第 78 阶段验证边界

Embedding 检索质量必须使用固定语料和稳定指标验收：排名先按文档 ID 去重再截取 K，正例计算 Recall@K 与 MRR，负例只在返回为空时计为正确，多次执行按完整排名一致性计算稳定率；空语料、单一正/负语料、K 外命中、负例误命中、排序漂移和非法用例都要有 JVM 契约。项目长期 `docs/` 作为固定语料，5 个正例与 1 个负例各执行两次，门禁为 Recall@5 `1.0`、MRR `>= 0.8`、负例准确率 `1.0`、稳定率 `1.0`。知识管理页必须显示本次检索实际使用的 Embedding 状态，Provider/模型为空时不得渲染多余分隔符，零命中仍保留审计与回退原因。真实 Provider 只在同步模型列表明确包含 Embedding 模型时调用 `/embeddings`；本轮 Redmi 兜底 Provider 同步成功但没有该模型，因此仅验证配置恢复与词法兜底，不宣称真实向量端点兼容。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个无显式参数的联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段验证边界

知识管理页必须为当前文档展示所有仍指向当前 revision 的 Embedding 索引摘要，包括 Provider、模型、维度和分块数；没有索引时明确提示词法兜底。启用文档可手动重建当前选中 Provider/模型的索引，升级前旧文档不得因缺少导入时向量而永久停留在 `NO_INDEX`。Provider 请求、数量和维度校验全部成功后才允许进入事务；事务内必须再次核对 document revision、enabled 和 chunk 身份，并且只替换当前 `providerId + model` 空间。切换 Provider/模型后既有空间不得被覆盖，重复重建某一空间不得删除其他空间；失败、超时、停用和并发替换不得先删除已有向量。替换正文继续清理全部旧 revision 空间，删除继续清理全部索引。Room 保持 v30；当前只支持前台单文档显式重建，不承诺 ANN、自动后台批量重建或规模化性能。完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和仅 Redmi `164/164` instrumentation 已通过。

## 第 76 阶段验证边界

知识库语义检索只在当前 Provider 的模型列表包含 Embedding 模型时启用。请求配置必须保留 Provider 身份和 Embedding 模型名；向量按 `providerId + model` 隔离，不能把不同 Provider 或模型的 BLOB 混用。Room v30 新增向量表并保留历史检索的 `LEXICAL_ONLY` 默认事实。索引建立最长 30 秒，查询向量最长 2 秒；Provider 不可用、超时、无索引或维度不一致时必须回退原 FTS4+LIKE，且每次检索审计记录最终状态。语义候选与 FTS/LIKE 以稳定 RRF 融合，结果交付前必须再次核对当前文档启用状态和 revision。当前只验证有限规模内存扫描，不承诺 ANN、后台增量建索引或任意 Provider 的 Embedding 兼容性；完整 JVM、Lint、Debug/AndroidTest APK 和仅 Redmi `158/158` instrumentation 已通过。

## 第 75 阶段验证边界

`/agent` 附件 v1 仅支持 Responses API。单条 USER 消息最多携带一种经既有 8 MB、MIME、签名、PDF/OpenXML 和 UTF-8 策略校验的 Image 或 Document；Chat Completions、Image+Document 混合、持久化重复附件、assistant/Tool 伪造附件必须 fail-closed。初次发送必须先把附件与正文作为 USER MessagePart 提交，再建立 Run；同一 Run 的每轮规划请求继续携带附件，模型总结请求、`VerifiedAgentContext`、ToolResult 和 Agent 输出不得携带附件。审批恢复和任务中心重试必须从已持久化 USER MessagePart 重建附件，重试创建新 Run 且旧 Run 不变；Workflow/后台 Agent 暂无附件入口。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过；图片/文档真实 E2E 的 `notes.create` 回执均为 `PASSED`，直接 `complete` 的无工具 Run 被运行时拒绝。

## 第 73 阶段验证边界

会话新建、历史选择和删除后的副作用顺序必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSelectionCoordinator`。协调器只组合现有 `ConversationSessionPolicy`、`ConversationPersistenceCoordinator` 与 `ConversationLoadCoordinator`：新建先取消旧加载再发布即时选择；删除先取消旧加载、标记版本化删除意图并发布运行态清理事件，再按计划即时选择或完整加载；只有当前加载代次失败时，必须先回滚该请求捕获的删除代次，再发布 Failed。旧失败不得清除同 ID 的新删除意图，删最后会话仍保留空占位，删除后有剩余会话仍加载 `updatedAt` 最新项。`ConversationLoadRequest` 不得继续承载持久化回滚意图；ViewModel 只负责 Agent Run/审批 Map、UI 投影和成功后的选择保存。Room v29、附件 BLOB、Provider 协议、UI、`/agent` 与 Workflow 不变。当前已验证聚焦 `4/4`、第 68 至 73 阶段会话组合 `30/30` 和完整 ViewModel 手工编译；后续标准门禁已补齐，完整 JVM `472/472`、Lint、Debug/AndroidTest APK 与仅 Redmi 执行的 instrumentation `153/153` 均通过。

## 第 72 阶段验证边界

新建会话与删除后的选择规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`。当前选中会话已经为空时必须幂等复用且不额外折叠其他占位；当前会话有内容时优先复用 `updatedAt` 最新空占位并折叠其余空占位，没有空占位才按同一注入时钟创建 `conversation-$now`。删除后有剩余会话必须返回 `Load` 计划并选择 `updatedAt` 最新项，删至空列表必须返回带新占位的 `Immediate` 计划。计划必须显式区分复用既有会话与新建占位，后者不得因时间戳 ID 碰撞恢复旧 Agent Run 或审批。取消加载、标记删除意图、清理运行态 Map、完整消息加载、失败回滚和选择保存继续留在 ViewModel；加载与持久化 coordinator 不重复实现选择规则。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `5/5`、完整 JVM `468/468`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 71 阶段验证边界

会话加载事件的纯 UI 投影必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadProjectionPolicy`。Loading 只开启消息加载并清除旧提示；Loaded 必须把请求会话、标题、摘要、完整当前消息、Agent Run、待审批状态和结果在同一次状态替换中切换，同时从所有非当前会话索引移除 Image/Document BLOB；Failed 只关闭加载并保留真实错误消息或稳定兜底。删除意图回滚必须继续发生在失败投影之前，成功后的选择保存和 Agent/审批映射读取继续留在 ViewModel，`ConversationLoadCoordinator` 的 Job/代次门禁不重复实现。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `463/463`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 70 阶段验证边界

异步会话加载 Job、取消与迟到结果隔离必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadCoordinator`。每次选择生成单调代次并取消旧加载，且必须先登记新 Job 再派发可重入的 Loading；底层 Room 查询或 loader 即使在取消后仍返回或失败，也只能由当前代次发出 Loaded/Failed，不能覆盖当前会话、删除回滚或错误提示。当前代次成功后 ViewModel 仍负责把完整消息和轻量会话列表原子投影到 Compose，并继续触发选择保存；当前代次失败时才允许回滚该次删除意图并显示读取失败。删除后的下一会话选择、空会话兜底、Compose 副作用、`ConversationRepository` 的显式删除事务与附件 BLOB 生命周期不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `4/4`、完整 JVM `460/460`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 69 阶段验证边界

会话保存 Job、Room 写入串行化和显式删除意图必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationPersistenceCoordinator`。快速连续保存只保留最新待提交快照；旧事务若已进入不可取消提交区，最新快照必须等待并最后写入。普通聊天发送必须先取消并等待旧保存，再捕获当前删除意图并通过同一单写者提交用户消息与附件，成功后才能准备上下文和请求模型。删除 ID 只有在包含该代意图的事务成功后才能确认；失败、取消或事务期间同 ID 被重新标记时必须保留新意图，读取失败只回滚该次切换捕获的删除代次，不能清除同 ID 的后续删除意图。`ConversationRepository` 的显式删除事务、后台 Workflow 并发保护和附件 BLOB 保留逻辑不变；异步会话加载、删除后的 UI 切换/失败提示和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `8/8`、完整 JVM `456/456`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 68 阶段验证边界

会话状态投影的纯规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`：第一条 `role=user` 消息正文生成标题并在 trim 后限制 18 字符，正文空白时保持“新会话”且不向后寻找下一条用户消息；全部真实会话必须保留，多个空占位只保留 preferred 或最新一个；更新既有会话必须保留 `createdAt`、推进 `updatedAt`，默认继承摘要边界、更新时间与摘要模型；非当前会话的迟到更新只能修改会话列表，不能污染当前 UI；blank ID 使用同一次注入时钟生成稳定 ID，并沿用既有非当前隔离语义。异步 Room 加载、保存 Job、删除事务和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `6/6`、完整 JVM `448/448`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 67 阶段验证边界

普通聊天的发送前持久化、请求上下文准备、模型网络调用、流式增量和成功/取消/失败终态必须由独立 `ConversationSendCoordinator` 按稳定顺序编排。ViewModel 只负责入口校验、Compose 状态投影、30ms 流式节流和发送 Job 生命周期，不得复制第二套网络 try/catch 状态机。用户停止时必须先用最近已准备上下文收敛部分 assistant，再继续传播 `CancellationException` 以取消底层请求；Room 或网络普通异常必须发出带最近可证明上下文的失败事件，持久化失败时不得继续准备上下文或调用模型。Room v29、Provider 协议、消息格式、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `442/442`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 66 阶段验证边界

普通聊天的请求上下文准备必须从 `XiaoLingViewModel` 迁入可独立测试的应用组件。该组件统一决定失败/取消 assistant 是否进入上下文、知识引用失效时是否移除历史 Agent 消息并废弃旧摘要、最近 16 条窗口、窗口外可信 Agent 结果上限、摘要增量边界与复用元数据，以及 Responses 用户附件是否进入最近窗口。ViewModel 只提供当前提示词设置、Room 知识引用核验和摘要网络实现；不能复制第二套上下文规则。知识核验或摘要调用的协程取消必须传播，普通异常才允许保守移除知识或使用本地摘要兜底。Room Schema、请求协议、摘要长度和 UI 不变。聚焦 JVM `8/8`、完整 JVM `439/439`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 65 阶段验证边界

用户必须能在应用内只读查看最近 30 条进程退出观察，不依赖 ADB。页面刷新只能查询 Room v29 已有记录，不能再次调用平台采集，也不能给记录增加 Agent Run、Workflow 或 ScheduledTask 关联。六类稳定证据必须明确区分，尤其不能把 `LOW_MEMORY_CANDIDATE`、`CONTROLLED_OR_MAINTENANCE` 或 `UNATTRIBUTED` 呈现为自然 LMK。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；真实受控 `force-stop` 在页面显示为 `USER_REQUESTED / 受控退出或包维护`，刷新前后数据库均为 1 条。该只读控制面不改变普通 WorkManager、fail-closed 恢复、设备工具前台限制与 Foreground Service 后置策略。

## 第 64 阶段验证边界

Android 11+ 的系统进程退出事实已进入独立、有限、隐私安全的 Room v29 账本：前台启动与后台 Worker 冷启动均可补采，Worker 必须先登记当前进程所有权。退出记录不得凭时间邻近关联 Task/Run，不保存 description、trace 或进程状态摘要，稳定去重后最多保留 30 条。只有 `REASON_LOW_MEMORY` 是直接 LMK 证据；设备无法直接报告 LMK 时的 `REASON_SIGNALED + SIGKILL` 只能标记为候选，用户/应用取消和包维护必须保持受控分类。旁路采集失败不能阻断主流程，但不得吞掉协程取消。Redmi 聚焦 `5/5`、完整 instrumentation `149/149`、JVM `431/431` 通过；受控 `force-stop` 的正式记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 63 阶段验证边界

Redmi Android 14 已用真实 WorkManager 验证应用取消运行中 Worker 会报告 `CANCELLED_BY_APP(1)`，并由生产停止原因策略映射为稳定分类。用户可见停止仍以先落库的 `STOP_REQUESTED` 和用户原因作为业务事实；随后 WorkManager 的应用取消码只说明执行机制，不得覆盖用户意图或写入独立系统停止原因。完整 Redmi instrumentation `145/145`、JVM `424/424` 通过。本阶段不属于自然 LMK、系统配额或超时证据，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 62 阶段验证边界

后台 Worker 的系统停止原因已纳入可审计执行结果：Android 12+ 的 WorkManager 停止码映射为稳定分类，按同一 Room 事务写入 ScheduledTask 与 WorkflowRun，并在任务中心展示；旧 Android、`NOT_STOPPED` 和未知码保持通用或未知结论，不把历史自由文本反推成具体原因。Room v27→v28 迁移只新增可空字段。Redmi 完整 instrumentation `143/143`、JVM `424/424` 通过；本阶段未取得自然停止样本，不改变普通 WorkManager、fail-closed 恢复和 Foreground Service 后置策略。

## 第 61 阶段验证边界

Redmi 熄屏状态下（`mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`），JobScheduler 冷启动生产 Worker，在计划时间后 `159.479s` 启动，并完成 `244.236s` 的 8 步复合 SAFE Workflow。8 个 AgentRun、32/32 ToolResult 与验证事件全部成功；每个 Run 的 `consumedMs` 预算快照无回退，最大值 `18.283s–44.856s`，无模型失败。该结果扩大了普通 WorkManager 在熄屏场景的可信度，但不承诺自然系统回收、任意长度存活或 Foreground Service 必要性。

## 第 60 阶段验证边界

Redmi 的一次性入队 Probe 在 `0.255s` 后退出，原应用 PID 随 instrumentation 结束消失；JobScheduler 随后冷启动新 PID，由生产 Worker 独立完成 `204.977s` 的 8 步复合 SAFE Workflow。WorkRequest、ScheduledTask 和单一 WorkflowRun 关联完整，8 个 AgentRun、32/32 ToolResult 与 32 条验证事件全部成功；每个 Run 的 11 条 `consumedMs` 预算快照无回退，`llmFailures=0`。这证明普通 WorkManager 可以承载当前规模的真实冷启动后台链，不证明 Android 会保证任意长任务存活，也没有新增自然 LMK 或 Foreground Service 需求证据。

## 第 59 阶段验证边界

Redmi `wsvwypiz7xwslvl7` 的正式 WorkManager 已完成 `229.416s` 的 8 步复合 SAFE Workflow：每步依次调用 3 个应用内只读工具，24/24 ToolResult 均成功并通过验证，Task/Workflow/8 个 AgentRun 全部完成，单一 ScheduledTask 未复制执行；记录 72 条预算更新、24 条 `tool.verify`，没有 `llm.request.failed`。`ApplicationExitInfo` 为 `supported=true / lowMemory=0`，14 条历史退出均为 instrumentation 或安装停止，仍没有 Android 自主 LMK。该样本扩大了普通 WorkManager 的真实耗时证据，但不承诺任意长度的系统存活，也不触发 Foreground Service 预先引入。

## 第 58 阶段验证边界

第 58 阶段早期两次真实后台 Workflow 因 Redmi TLS 握手失败在约 4 至 6 秒收敛，设备自带 `curl` 可独立复现 `BoringSSL SSL_ERROR_SYSCALL`；这两次不是成功任务耗时，也不是 Android 自主回收证据。系统没有通过关闭证书校验或预先引入 Foreground Service 绕过该问题，网络恢复后的成功样本见下一段。

网络恢复后第 58 阶段已取得一条成功长任务样本：普通 WorkManager 在 Redmi 上以 `92.667s` 完成 8 步 SAFE Workflow，8 个 Agent Run 和工具验证全部成功，预算快照单调且未复制 Run；历史退出中 `lowMemoryExits=0`，因此仍不能宣称 Android 自主 LMK 或据此预先引入 Foreground Service。前述 TLS 失败仍保留为网络阻断样本。

## 产品定位

小灵是一款运行在 Android 手机上的个人 Agent。它不是单纯的模型聊天客户端，也不是默认拥有手机全部权限的自动化脚本。它应当先理解用户目标，再在明确授权的能力范围内调用工具、记录过程、验证结果，并把控制权留给用户。

## 核心用户价值

- 一个长期可用的个人入口：对话、记忆、任务和工具在同一应用内协作。
- 一个可控的执行者：每次操作可解释、可停止、可确认、可追溯。
- 一个开放的模型客户端：支持用户自己的 OpenAI-compatible 服务，不绑定单一模型厂商。
- 一个逐步扩展的移动 Agent：先做应用内安全工具，再扩展日程、通知、文件和跨应用自动化。

## 产品原则

1. **聊天与执行分流**：普通问答走快速路径；只有需要工具的任务才进入 Agent Runtime。
2. **能力来自注册表**：模型只能调用代码注册且当前启用的工具，不能自行声明权限或执行任意命令。
3. **风险由代码决定**：工具风险、Android 权限、确认要求和结果验证规则由应用定义，不能信任模型给出的风险等级。
4. **动作不等于完成**：写入、发送、删除、修改等操作必须通过工具结果或重新读取状态验证。
5. **记忆可见可控**：用户可以查看、编辑、删除和禁用长期记忆；记忆必须保留来源和更新时间。
6. **本地优先**：会话、运行记录、记忆和配置默认保存在设备本地；密钥继续使用 Android Keystore 保护。
7. **渐进授权**：首次使用不要求一次性开放全部权限，只有启用具体能力时才请求对应权限。

## 当前已交付能力

- 多 Provider、上游模型同步和模型选择。
- 模型请求 User-Agent 可在设置页按设备自定义；“网络请求”在设置根页与其他设置项保持相同的入口卡片样式，点击后进入独立页面编辑，不在根页行内修改。编辑区默认至少显示 5 行，右下角提供复制和清空操作，并保留恢复默认入口；默认值为 `Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`，空白配置自动回退默认值，并统一用于模型列表、普通对话、Agent 和后台 Workflow 请求。
- Chat Completions、Responses API 和 SSE 流式输出；Responses 输入支持保留 system/user/assistant 边界的消息，以及通过 `call_id` 关联的 `function_call / function_call_output` typed Items。普通对话可显式开启供应商推理摘要；只有 Responses 请求会发送 `reasoning.summary=auto`，关闭时和 Chat Completions 均不发送。
- Text/Reasoning/Image/Document/Tool 消息 parts：Room 独立保存 part ID、消息内顺序、类型、正文、供应商摘要来源与 item 身份、附件 MIME/文件名/BLOB/detail、文档提取文本/PDF 页数、工具参数、结果、成功状态、验证状态和记忆引用。Image/Document 仅允许 USER 来源，且单条消息最多携带一种附件。图片支持 PNG/JPEG/WEBP；Document 支持 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX、XLSX，单文件最大 8 MB。PDF 必须由平台解析且最多 50 页；文本必须是有效 UTF-8 且最多 200,000 字符；DOCX/PPTX/XLSX 必须是未加密、非分卷、非 ZIP64 的 ZIP/OPC 包，条目不超过 4,096 个、声明及实际流式展开总量都不超过 64 MB，并包含真实可读的 `[Content_Types].xml` 与对应 Word/PowerPoint/Excel 根入口。OpenXML 只接受匹配格式的 MIME、空 MIME 或通用 ZIP/二进制 MIME。Responses 分别映射为 `input_image` / `input_file` Data URL；Chat Completions 明确拒绝附件，`/agent` 仅允许 Responses USER 单一附件并只送入规划请求。Reasoning 只接受 Responses `reasoning.summary[].summary_text`，不读取或展示原始 `reasoning_text/reasoning_content/encrypted_content`；附件 Base64 与原始/加密推理在 debug 日志中必须脱敏。Agent Tool part 必须由应用可信上下文投影，Image/Document/Reasoning 均不进入 `VerifiedAgentContext`、不能生成或替代 Tool。附件 BLOB 只为当前会话加载，切换会话必须在完整 parts 就绪后原子更新界面；轻量快照保存不得清空未加载 BLOB，网络请求必须等待用户消息与附件事务提交。前台快照保存只能增量 upsert，用户删除必须显式传递会话 ID，并在事务前过滤删除集合，以保留并发后台 Workflow 证据且防止陈旧快照复活已删会话。
- 本地知识库、管理 UI、Agent 检索与答案引用：第一版只导入 TXT、Markdown、JSON 和 CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符，统一移除 BOM、规范换行、拒绝空白文档与二进制空字符，并对规范全文计算 SHA-256。文档身份与 revision 分离；确定性分块优先在段落边界结束，默认每块 1600 字符、固定最多 200 字符重叠，offset 指向 Room 内规范全文且不得切断 UTF-16 代理对。Room v33 保存全文、chunks、FTS4/LIKE 索引、Embedding 向量、带 top1/top2/margin/候选数/均值/标准差/top1 z-score shadow 字段的检索审计和 Tool/Message 知识引用；检索同时保留中文与字面通配符安全的 `LIKE` 兜底。设置页支持 SAF 导入、轻量摘要列表、详情、启停、替换、删除、显式检索预览和当前 Provider/模型的 Embedding 重建；列表不得读取完整正文，详情只从 SQLite 投影并安全截取最多 4,000 个 UTF-16 单元。`knowledge.search` 必须是 SAFE、支持后台、`query` 1 至 200 字符、`limit` 默认 3 且最大 5，并把 conversation/run/retrieval/document/revision/chunk/offset 身份写入审计。Agent 回复下方的独立引用区域只允许从结构化 MessagePart/VerifiedAgentContext 投影，不解析模型自由文本；默认折叠，展开后展示文档名、revision、chunk 和 `[startOffset, endOffset)`，文档仍存在时可跳转知识库详情。引用状态查询必须分批控制 SQLite 绑定参数数量，取消的旧核验任务不得把新状态覆盖为失败；无法核验时显示独立未知状态，不得误报为引用失效。替换必须在同一事务内递增 revision、更新 parser/hash、删除旧 chunks/FTS 并生成新 chunk ID；禁用、替换或删除后立即退出检索。历史 Run/消息审计不回写，启用的新 revision 标记“历史版本”，停用、删除或证据漂移标记“当前不可用”，且失效知识消息和可能包含旧片段的摘要不得再次进入模型上下文。Embedding 检索、显式单文档重建、多 Provider/模型索引空间和离线相关性扩样校准已接入；生产拒绝、规模化 ANN、自动后台批量重建继续后置。
- 设备 Agent 观察与有限动作层：应用内独立开关默认关闭，并与系统 Accessibility 授权分别生效；健康状态必须区分应用关闭、未授权、已授权但服务断连和可用。前台直接 `/agent` 可在 Profile/Skill 允许时使用 `device.snapshot / open_app / back / home / tap_ref / type_text / swipe`；前台手动 Workflow 当前精确开放同一 Agent Run 内的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。打开应用、点击与文本输入都必须使用当前步骤意图、Room 独立审批和 Accessibility 安全浮层，并在动作后同时取得 `executorVerified=true`、typed `PASSED` 与白名单后置观察；`open_app` 的唯一包名在三层白名单和答案级重建中绑定，`back / home` 只接受空参数并固定为 SAFE 零审批，但仍要求同 Run 已验证 snapshot、30 秒 TTL、当前 generation 和相同后置验证，其中 `home` 还必须匹配系统动态解析的 launcher。文本输入还必须通过敏感文本预审计、当前 ref 的 enabled/editable/redacted/动作证据、最小指纹授权和绑定原 `nodePath` 的精确回读。`device.type_text` 原文只留在当前 ToolCall 内存，Workflow 与直接 `/agent` 的 proposed/validated/ToolCall ledger、Room 审批、可信消息上下文和 Tool parts 仅持久化 snapshot/ref、SHA-256 指纹与长度；历史、浮层和答案级证据不展示或保存原文。`swipe`、后台或定时 Workflow、恢复自动续跑、开关关闭和缺少 Run Context 时同样在规划与 Executor 两层拒绝。快照最多返回 200 个可见有效节点和 4,000 个文本字符，文本截断不得切断 UTF-16 代理对；可操作且未脱敏节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、节点路径、指纹和目标属性，页面变化、过期、关闭开关或任一失败后立即失效，不回退坐标。在前台直接 `/agent` 中，`open_app / tap_ref / type_text` 必须审批，`back / home / swipe` 按 SAFE 执行；Workflow 则只开放前述六项并继续要求各自动作门禁。打开应用仅允许小灵、系统计算器、时钟和系统设置，输入限制为 500 字符并拒绝密码、验证码、密钥、账号及身份信息。每次动作后必须重新 snapshot，瞬时空窗口或读取期页面变化只允许短时有界重试；`type_text` 只接受原路径目标节点的 `text` 精确回读，其他节点同文不能替代，最终只有 `verified=true` 才能通过 Executor 验证。密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref；支付/高敏身份验证窗口及已知密码管理器、Authenticator、钱包/银行应用整窗拒绝。AccessibilityService 只使用标准节点动作与系统返回/主页，不执行坐标手势或截图；Release 不包含可外部触发的诊断 Receiver/探针 Activity。既有 Profile/Skill 不因新工具自动扩权。
- 多会话、本地保存、会话摘要压缩和 Markdown 渲染；普通聊天上下文筛选、知识生命周期核验、最近窗口、增量摘要与请求消息构造已统一迁入 `ConversationRequestContextPreparer`，网络发送顺序由 `ConversationSendCoordinator` 编排，会话标题、空占位折叠、时间戳、摘要元数据继承、非当前更新隔离、新建会话和删除后选择计划由 `ConversationSessionPolicy` 统一投影，latest-save Job、Room 单写者和显式删除意图由 `ConversationPersistenceCoordinator` 协调，异步加载 Job/代次与加载 UI 投影分别由 `ConversationLoadCoordinator` 和 `ConversationLoadProjectionPolicy` 承担，选择/删除副作用顺序由 `ConversationSelectionCoordinator` 组合。ViewModel 继续负责依赖注入、运行态 Map、UI 投影、成功保存和其他 Compose 副作用。
- Provider、模型、请求模式、流式状态、首字耗时和总耗时等消息元数据。
- API Key 的 Android Keystore + AES-GCM 加密存储。
- 常见网络、鉴权、限流、模型和响应解析错误分类。
- `/agent <目标>` 顺序多步执行入口，以及 `AgentRun / AgentStep / ApprovalRequest / RunEvent` 可审计运行链路。
- Agent Profile v1：用户可以创建、编辑、选择和删除多个 Agent；每个 Profile 固定名称、标识、Provider、模型、Chat Completions/Responses 模式、系统提示词、当前会话上下文策略、工具白名单、Skill 白名单和长期记忆开关。至少保留一个 Profile，仍被 Agent 使用的 Provider 或模型不能直接删除/停用。
- 新 Run 必须写入唯一 `agent.profile.selected` typed event 并冻结完整 Profile 快照。Profile 系统提示词只能调整表达方式和授权工具内的任务偏好，不能覆盖 JSON 协议、工具白名单、风险、审批、Android 权限、后置验证和可信事实边界；Profile 工具白名单在 Registry 执行层强制生效，Skill 只能继续缩小工具面。
- 有界 Agent Runtime：同一 Run 最多 4 次工具调用，模型每轮返回继续调用或完成；具备模型与工具超时、整次 Run 超时、取消、完整输入 Schema/业务规则、重复调用检测，以及参数校验时、审批结束后执行前、工具返回后验证前的 Android 权限复检。任一检查点权限缺失都必须 fail-closed。AgentRun 一旦进入任一终态，迟到的模型、HTTP、工具或恢复写入不得覆盖状态、结果和错误证据，也不得新增/改写 Step、覆盖一次性 Approval 决定，或追加 RunEvent/Tool Ledger 成功事实。兼容模型把同一个工具名同时写入 `action/tool` 时可以归一化，不一致动作仍拒绝。
- 模型规划、工具和模型总结必须使用同一单调执行时钟累计 Run 预算；Step 剩余时间小于或等于单步上限时归因 Run timeout，否则归因 Step timeout。审批等待不消耗执行预算。新 Run 从零值快照开始，每个成功执行段后以 typed RunEvent 持久化累计值；审批及受限恢复必须继承原 Run 的总额和已消耗值。旧 Run 可从零值兼容起点继续，但快照缺失结构、越界、总额漂移、累计回退，或 ToolResult 已落库而后续预算快照缺失时必须拒绝恢复。
- 应用侧 Tool Registry 统一声明字符串/整数/数值/布尔类型、长度/范围/枚举、风险、确认、Android 权限、后台能力、超时和验证策略；Runtime 按前台/后台来源执行能力门禁，模型不能增加未知参数、修改工具风险或自行增加执行事实。
- 工具执行证据使用 `ToolExecutionReceipt` 记录 ToolCall ID、业务 operation ID、可选幂等键和提交状态，并把执行时的 `ToolReplaySafety` 声明快照随 `tool.result` typed metadata 持久化。Executor 提供的回执必须绑定当前 ToolCall；错配时 Runtime fail-closed。只有执行时快照和当前工具定义都显式声明 `IDEMPOTENT_BY_KEY`、回执状态为 `COMMITTED`、ToolCall 身份一致且幂等键存在时，证据策略才可判定已提交副作用可复用；旧事件缺少快照时默认 `RESTART_REQUIRED`。`notes.create` 使用 ToolCall ID 作为存储层唯一幂等键；`memory.remember` 使用独立 operation ledger 保存载荷与结果快照。两者同键同载荷返回原 operation，同键不同载荷必须拒绝且不覆盖旧数据。
- ToolCall/ToolResult 使用独立 Room Ledger 保存稳定调用 ID、参数、proposed/validated 事件锚点、结果、显式错误、耗时、Executor 回读状态、最终验证状态、记忆引用、重放声明和执行回执。RunEvent 与 Ledger 必须在同一事务写入；同一 ToolCall 身份或参数漂移时整笔回滚。迁移期 RunEvent 继续作为时间线事实源，v19 旧 Run 不根据可能缺失身份的历史事件补造 Ledger，也不因 Ledger 为空失去原有恢复能力。
- 第一批应用内工具：当前时间、会话列表与检索、本机笔记列表/检索/创建、长期记忆检索/写入，以及只读本地知识库检索。
- 声明式 Skill 按需加载与管理：内置和本地 Skill 统一进入 Room Catalog；本地 `schemaVersion=1` JSON 经过字段白名单、工具注册表、风险与 Android 权限一致性校验后才可导入，设置页支持查看、启停和删除本地 Skill。Run 审计固定所选 Skill 的 ID/版本，审批恢复不得因期间停用、删除或升版而扩大工具面。
- 多步骤 Workflow Ledger：用户可保存、编辑、启停和运行包含 1 至 8 个顺序 Agent 步骤的工作流；活动 Run 存在时禁止编辑，历史 Run 保留创建时的步骤定义、输入/输出快照、幂等键、触发来源、会话、关联 Agent Run、结果和失败原因。手动运行复用现有前台审批与验证链路，后台调度按相同顺序执行且不会绕过审批门禁；前台三步骤、后台三步骤和审批后继续下一步骤均已通过真实模型真机验收。
- Workflow 安全重试：`BLOCKED / FAILED / CANCELLED` Run 可创建带 `retryOfWorkflowRunId` 的新 Run；只复用来源 Run 连续成功前缀的输出，首个未完成步骤及后续步骤重新执行。已启动过的失败步骤重试前必须二次确认，旧 Run 和步骤快照保持不变；真机已确认来源失败 Run 不变、新 Run 正确关联来源且定义编辑不回写历史快照。
- Workflow 知识证据边界：涉及 `knowledge.search` 的步骤输出必须把正文和结构化引用作为同一版本化快照保存。前台、后台、审批恢复和关联新 Run 重试在准备下一步骤时，都必须重新核对当前文档启用状态、revision、名称、chunk sequence 和 offset；引用缺失、畸形或任一引用失效时不得把该步骤正文写入新 Agent Run 目标，但来源 Run 和步骤快照必须原样保留供审计。
- 一次性非精确定时：用户可为已启用 Workflow 创建或取消 1 分钟至 7 天的一次性计划；`ScheduledTask` 记录计划时间、实际启动时间、WorkRequest 和关联 Workflow Run。`RUNNING` 实例必须提供用户可见停止入口：Workflow 仍活动时先在 Room 原子写入持久中间态 `STOP_REQUESTED`，再取消目标 WorkRequest，并按 Task→Workflow→Agent 持久化链定向收敛。系统取消异常、即时 fallback 失败、Worker 未及时收敛和 Agent 尚未关联都不能丢失停止意图；`SCHEDULED→RUNNING` 抢占不能让同一次点击只取消调度而遗漏执行链。停止必须幂等，不影响其他 Run，不创建替代 Run；`STOP_REQUESTED` 下的迟到成功只能收敛为取消，Workflow/Task 终态必须在同一事务重新读取栅栏后原子结算，停止 fallback 也不得分两次写入这两个终态。若 Workflow 在停止事务前已经终态，必须保留该历史终态并在同一事务把半结算 Task 映射到一致状态，不得让通用 `STOP_REQUESTED→CANCELLED` 栅栏再次改写该映射、写入伪停止栅栏或覆盖历史结果。后台只允许显式开放的 SAFE 只读工具，需审批工具进入 `BLOCKED` 并通知用户以前台新 Run 继续；真机已验证触发前进程被回收后由 WorkManager 冷启动执行并收敛 Ledger。
- Daily/Weekly 周期规则：用户可按当前系统时区保存每日或每周墙上时间；每次只物化一个独立的 OneTime `ScheduledTask`，终态后再生成下一未来实例，不补跑错过的历史周期。`STOP_REQUESTED` 不是终态，旧实例完成停止重对账前不得物化下一实例。替换规则会取消旧待执行实例，停用规则或 Workflow 会同步取消系统队列；每次触发仍建立独立 Workflow/Agent Run，旧 Run 和历史实例保持不变。
- Workflow 进程终止对账：步骤结果已经事务落库而下一步骤尚未启动时，进程终止不得被当作普通业务失败通知或 WorkManager 自动重试；启动对账须保留已完成步骤和输出，旧 Run 收敛为 `FAILED`，用户创建关联新 Run 后只复用连续成功前缀，不能自动继续旧 Workflow 或复制 Agent Run。
- 设置页长期记忆管理：FTS4 + 中文子串兜底搜索、全部/启用/禁用筛选、内容/标签/类型/置信度编辑、置顶、启停、删除确认、跨进程撤销和来源会话/Run 跳转；禁用或删除后不再参与 Agent 检索。
- 默认关闭的候选记忆：成功轮次结束后只从明确陈述生成候选，由用户确认或忽略；API Key、token、密码、银行卡、身份证和手机号只记录敏感类别，不保存原值。
- 记忆治理：规范化相同事实复用旧记忆，同类型同主题的不同事实标记冲突并保留旧记录；`memory.remember` 同样执行敏感过滤和去重。
- 记忆引用审计：`memory.search` 的实际命中 ID 必须进入 RunEvent 和已验证 Agent 上下文；`/agent` 单次可关闭记忆召回，关闭后不能访问 `memory.search`。
- 对话内 Run 时间线和审批卡片，以及设置页 Agent 任务中心；任务中心支持全部/需确认/处理中/可重试/已完成筛选、完整 ToolResult、步骤、审批、结构化事件和失败任务重试。“需确认”只包含已结束、可重试且副作用证据要求确认的 Run，不混入仍在等待审批的活动 Run；确认时必须重新核对证据码，稳定后只创建关联新 Run。v20 新 Run 有独立工具账本时必须按调用优先展示账本中的 proposed→validated→result→verified，typed RunEvent 只用于一致性核对；账本完全为空且存在 typed 工具事件的旧 Run 才回退事件。旧结果或验证缺少 ToolCall ID 时必须标为“关联未知”，不得按工具名或时间顺序伪造调用关联。双源字段、身份或事件锚点不一致时必须显示审计告警，但不得自动修补旧 Run 或改变恢复结论。`memory.remember` 恢复失败必须展示稳定错误码、具体原因和建议动作；建议动作只能引导修复记忆状态后创建新 Run，不能暗示旧 Run 会继续。当前筛选范围会基于持久化审计数据展示 Run 数、终态成功率、平均耗时、非成功数、模型/工具调用数、模型总耗时、平均 TTFB、Prompt 字节、上游 Token usage 覆盖率和失败终态分布；单 Run 使用同一持久化口径。活动 Run 不进入质量或失败分母，上游未返回 usage 时必须显示未返回，不能补零。
- 失败、取消和预算耗尽 Run 可创建新 Run 重新执行；新 Run 通过 `retryOfRunId` 关联来源，旧 Run 保持不变。v20 非空工具账本必须作为副作用判断事实源：非 SAFE 调用只要结果成功，或执行回执为 `COMMITTED / UNKNOWN`，重试前都必须二次确认；账本缺锚点、字段漂移、事件链不完整等异常同样保守要求确认，不得回退旧事件。账本完全为空的旧 Run 才沿用 typed event 成功结果判断；仅停在 proposed/validated 且尚未执行的调用不因此增加确认。恢复事件或失败步骤表明中断发生在 `EXECUTING/VERIFYING` 时仍必须确认。重试启动后必须进入来源会话，使重新触发的审批对用户可见。
- 重试门禁必须把副作用证据归一化为稳定分类：`NO_SIDE_EFFECT`、`NOT_COMMITTED`、`COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED`、`COMMITTED_VERIFIED` 和 `EVIDENCE_INCOMPLETE`。任务卡、确认弹窗和确认前二次校验共享同一分类；`COMMIT_UNKNOWN`、已提交或证据不完整必须确认，不能自动恢复旧 Run 或重放工具。分类只是新 Run 重试指导，不代表旧 Run 已被恢复。
- 任务中心必须直接显示当前重试证据的分类、原因和建议动作；卡片与确认弹窗使用同一评估结果，不能只显示会被截断的分类码而隐藏提交未知或证据不完整的处理边界。
- 应用启动关闭不可原地恢复的活动 Run 时，必须在修改步骤/审批终态前计算副作用证据分类，并把分类和 Ledger/Event canonical fingerprint 写入 typed `run.recovered` 事件。后续重试仍需重新核对当前 Ledger/Event 指纹；启动清理把未执行的 `PENDING` 步骤统一改成 `CANCELLED` 时不得据此虚构副作用中断，当前分类或指纹与启动快照真正不一致时必须升级为 `EVIDENCE_INCOMPLETE`，不能让旧快照掩盖账本漂移。
- ToolResult 事件、执行预算快照和 `tool.verify` 事件之间的持久化边界必须可独立审计：Result 已落库但后续预算快照缺失时必须拒绝原地恢复并归类为执行预算证据无效；验证事件已落库但验证 Step 尚未收尾时只能补齐控制面，不得重复调用 Executor、ToolResult 或验证事件。生产故障注入默认为 no-op，不能改变正常执行顺序。
- 模型规划异常必须先保存可用的失败 telemetry 和已消耗预算，再进入失败终态；无 telemetry 的网络/网关异常至少保存预算快照。模型总结异常不能覆盖已经成功验证的工具事实，必须写入 fallback 审计并使用本地可信回复完成 Run；Receipt 回读失败必须保留稳定 `RecoveryFailure`、`COMMIT_UNKNOWN` 和二次确认重试边界。
- 规划和总结阶段的模型异常必须统一追加 typed `llm.request.failed`，并把上游错误映射为稳定的 `AUTHENTICATION / REQUEST_URL / RATE_LIMIT / MODEL / TIMEOUT / DNS / TLS / CONNECTION / RESPONSE / UNKNOWN` 分类；流式连接中断归入 `CONNECTION`，无法识别的当前异常和未来枚举都保守降级为 `UNKNOWN`。事件只展示阶段、错误码和脱敏原因，不保存请求正文。
- Runtime 因用户停止、WorkManager 取消或系统协程取消而收敛 Run 时，必须在 `NonCancellable` 中先持久化当前单调执行预算，再写取消 Step、`run.cancelled` 事件和 Run 终态；取消前模型/工具 finally 已累计的时间不能停留在旧快照，预算事件必须排在取消终态之前。
- 普通对话流式输出已经产生部分 delta 后发生断流或取消时，必须保留用户已见正文但写入明确的失败/取消终态，UI 不得继续显示“接收中”，也不能把该部分 assistant 作为完整回复再次进入后续模型请求或会话摘要；没有收到 delta 时不得凭空创建 assistant 正文。
- `AgentRunResumePolicy` 返回 `RESTART_REQUIRED` 时必须同时给出稳定处置码、具体策略原因、可证明的证据边界和下一步动作，不能只依赖可变中文文案。启动收敛必须把该处置与重试证据共同冻结到 typed `run.recovered`；任务卡、详情顶部和事件区读取同一历史快照。旧事件缺少处置字段时不得用当前版本策略回填或猜造，未知未来枚举必须保守降级。所有建议只允许保留旧 Run 并创建关联新 Run，不能暗示恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- WorkManager 再次拉起已处于 `RUNNING / STOP_REQUESTED` 的 ScheduledTask 时，必须按 `ScheduledTask -> WorkflowRun -> AgentRun` 关联链定向收敛旧实例，不能重新 claim、重新创建 Agent Run 或返回 `Result.retry` 复制可能已执行的副作用；Agent、Workflow、ScheduledTask 按顺序进入终态后才允许物化周期下一实例，无关前台 Run 不得受影响。`STOP_REQUESTED` 必须固定按用户停止收敛为取消，不能依据迟到 Workflow 成功改写；停止发生在 Workflow 已认领但 Agent Run 尚未关联的窗口时，也必须依据 Workflow→ScheduledTask 的持久关联取消 Run、未完成步骤和 Task，不能以“关联 Agent 缺失”写成失败。该链路已在 Redmi 完成同一 WorkRequest 的受控冷启动重入、强制 Doze 延迟、trim-memory、无压力对照和持久停止恢复；每个样本都只创建一个 Workflow/Agent Run。`run-as kill -9`、`force-idle` 与 `send-trim-memory` 不得表述为 Android 自主回收或连接关闭的因果证明。前台启动恢复与新 Worker 并发时，AgentRun 终态必须以原子条件更新保护，不能出现 Task/Workflow 已取消而 AgentRun 被迟到结果改成完成。
- 同一进程内，ScheduledWorkflowWorker 必须在任何 Room claim、重入对账或状态修改前登记 Task 执行所有权。应用启动恢复必须在同一互斥边界冻结旧 AgentRun、WorkflowRun 和 `RUNNING / STOP_REQUESTED` ScheduledTask 候选，并沿 Task→Workflow→Agent/Step 关联排除当前进程真正 `RUNNING` 的 Worker；已经写入 `STOP_REQUESTED` 的链不得再被进程所有权排除。快照期间新 Worker 必须等待，快照后的执行不得进入旧候选。后续 Agent 恢复/关闭和 Workflow/Task 对账只能消费冻结 ID，不能重新全库扫描误伤新执行。该能力不得依赖墙上时间、不得为当前版本新增持久 owner token 或 Room Schema，也不得借此恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。
- 系统进程退出观察必须是 Task/Run 之外的独立诊断账本。不得仅凭退出时间接近某次执行就建立因果关联；只保存 Android 稳定数值字段与应用侧稳定分类，description、trace 和进程状态摘要不得进入持久化或测试日志。只有 `REASON_LOW_MEMORY` 可作为直接 LMK，`SIGKILL` fallback 必须同时满足设备不支持直接报告且仍只能作为候选；用户停止、应用取消、权限/包变更不得冒充自然回收。重复历史稳定去重并最多保留 30 条；旁路采集异常不能阻断前台恢复或后台 Workflow，但协程取消必须传播。
- Room v33 本地保存 Provider、Agent Profile、会话、消息及 MessagePart、Agent Run、审批、独立工具调用/结果、笔记、长期记忆、候选记忆、记忆操作映射、Skill、Workflow、WorkflowStepDefinition、WorkflowSchedule、ScheduledTask、独立进程退出观察、匿名 answerability Shadow 观测，以及知识文档全文、chunks、FTS、Embedding 向量和检索审计；RunEvent 使用独立 typed metadata 保存时间线事实。v25→v26 只创建空知识库表，v26→v27 增加知识引用 JSON，v27→v28 增加后台 Worker 停止原因，v28→v29 创建退出观察表，v29→v30 增加按 Provider/模型隔离的向量表与检索身份/状态，v30→v31 增加可空的 top1、top2、margin 和有效候选数，v31→v32 增加可空的候选均值、总体标准差和 top1 z-score，v32→v33 创建空的匿名 Shadow 账本；升级不从旧正文、URI、`verifiedAgentContext`、工具记录、旧错误文案、当前向量、历史阶段合计或时间邻近关系猜造知识引用、Embedding 分数、相对分布、Shadow 记录、系统停止原因或 Task/Run 归因。v4→v33、各增量迁移和全新 v33 建库已有 Schema 与迁移测试保护。
- 普通对话、会话摘要 / 记忆、Agent 回复总结三类独立提示词设置，支持开关、即时保存、恢复默认和最终 system prompt 预览。
- 用户可通过 Android 系统文件选择器导出或恢复本地 Room ZIP 备份；恢复必须先校验版本并明确提示重启，API Key 密文不能脱离当前 Keystore 直接恢复。
- `MessageOrigin` 与 `VerifiedAgentContext` 可信来源边界：普通聊天、用户正文和模型自由文本不能伪造工具执行事实。
- 恢复边界已明确：`WAITING_APPROVAL` Run 在恰好一个 `PENDING` Approval 与最后一个已校验但无结果的 ToolCall 完全匹配、所有前序 ToolCall 均成功且 `PASSED`、执行/验证 Step 数量与顺序一致时，可以保留原 Run 等待用户决定；首步和第二次及后续审批使用同一证据规则。发起消息会先持久化，旧数据缺少消息锚点时按 Run 重建。执行/验证中的 Run 默认必须创建新 Run 安全重新运行，但有四个严格且互斥的例外：最后一个 `notes.create` 或 `memory.remember` 同时具备完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据、工具白名单能力且尚无 `tool.verify` 时，可在原 Run 恢复只读后置验证；Run 已处于 `VERIFYING`，所有 ToolResult 均成功、所有 `tool.verify` 均为 `PASSED`，且 Step、Ledger、typed RunEvent 与原 Agent Profile 完全一致时，可在原 Run 恢复控制面收尾；Run 仍为 `EXECUTING` 且严格失败 ToolResult、预算、Step 与尾部证据完整时，只可把原执行 Step/Run 原子结算为 `FAILED`；Run 为 `VERIFYING` 且成功结果、typed `FAILED` 验证原因、结果后预算、最后运行中验证 Step 与无尾随事件同时完整时，只可把原验证 Step/Run 原子结算为 `FAILED`。v20 Run 只要存在独立工具账本，恢复必须 Ledger-first 并用 typed RunEvent 核对身份、字段、派生错误与事件顺序；任一缺失、重复或漂移都不得回退旧事件推断，账本完全为空的旧 Run 才保留严格 typed event 身份语义，但失败终态结算禁止 event fallback。旧验证事件缺少 ToolCall ID 时必须返回关联未知并升级为证据不完整，不得以工具名或事件顺序配对。
- 应用重启后会把可恢复审批重新显示到对应会话；符合证据条件的 `notes.create` 或 `memory.remember` 只读回读原 operation、写入唯一一条 `tool.verify` 并用本地可信总结完成原 Run，不调用写入方法。所有验证事实均已落库的通用工具恢复不会调用 Executor 或 LLM，也不会追加第二条 `tool.verify`；最后验证 Step 尚为 `RUNNING` 时只补为 `COMPLETED`，随后重建全部可信工具上下文并生成本地总结。失败 ToolResult 与 typed 失败验证两类结算同样不调用 Executor、验证器或 LLM，但只补对应 `FAILED` Step/Run 与终态审计，不生成总结或继续 Workflow。四种恢复都不恢复旧模型协程，也不执行 Workflow 后续步骤；关联 Workflow 只保存当前步骤输出，剩余步骤要求创建关联新 Run。记忆恢复还必须保证记录仍启用、未过期且业务字段与提交结果快照一致；`OPERATION_NOT_FOUND / EVIDENCE_INCOMPLETE / PAYLOAD_MISMATCH / OPERATION_MISMATCH / MEMORY_NOT_FOUND / MEMORY_CHANGED / MEMORY_DISABLED / MEMORY_EXPIRED` 必须以独立 typed event 持久化。除上述严格边界外，其他执行/验证中 Run 仍直接安全收敛并通过关联新 Run 重试；旧 Run 及其所有 `PENDING/RUNNING` Step 必须一致进入 `CANCELLED`。
- 审批恢复和已提交结果恢复必须使用原 Run 的 Agent Profile 快照，而不是当前选中的 Profile。缺少 Profile 审计的历史 Run 只能使用知识工具上线前的固定工具集合；新 Run 出现重复、损坏、引用未注册工具或 Skill 越权的 Profile 审计时必须拒绝恢复，不能回退当前 Profile 或当前 Registry 扩大能力。既有 Profile 和 Skill 也不得因注册新工具自动扩权。
- 应用重启后可恢复的链尾审批批准后，会从持久化审批步骤继续同一 Run；前序已验证工具不会重放，`completedTools`、已消耗工具调用数和重复调用指纹会从持久化证据重建，再执行当前 ToolCall、后续规划和最终总结。第一步已经执行后在第二次或后续审批处中断现已支持原 Run 恢复；若当前工具已经进入执行/验证阶段，则按两个受限恢复例外或安全新 Run 边界处理，提交状态未知时不得猜测执行结果。

当前仍未交付相关性生产拒绝、规模化 ANN 和自动后台批量 Embedding 重建，以及提交状态未知、成功结果尚无 typed 验证结论或其他验证事实不完整形状的通用执行栈原地恢复、并行工具调用、后台 Workflow 执行栈断点续跑、精确定时和 Foreground Service。成功结果缺 typed 验证结论的持久化窗口已完成审计，结论是除既有严格白名单只读回查外没有唯一安全动作，因此“不交付原地恢复”是明确安全边界，不是待补实现。多步骤审批等待恢复、“全部验证通过后的控制面收尾恢复”、失败 ToolResult 与 typed 失败验证的两类原子失败终态结算、跨模型/工具段累计预算、当前进程 Worker 启动恢复隔离、后台运行中可见停止、`STOP_REQUESTED` 持久化异常重对账、旧验证事件缺少 ToolCall ID 时的 fail-closed 证据降级、Ledger/Event 指纹漂移拒绝，以及 Result/预算/验证三段持久化边界故障注入、模型异常预算审计和总结本地兜底已经交付，但都不等同于恢复旧模型协程或任意执行栈。设备 Agent 的 Accessibility 授权、健康检查、`device.snapshot`、短生命周期节点引用、隐私过滤、有限动作、风险审批、操作后重新观察和结果验证已交付，并已在 Redmi 上限定小灵、系统计算器、时钟、设置与桌面完成首批验收；不承诺任意 App。前台手动 Workflow 已完成 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text` 生产闭环：所有动作都要求当前步骤意图、同 Run 新鲜观察、当前 generation、`executorVerified=true + typed PASSED` 和白名单后置判定；`open_app / tap_ref / type_text` 额外走 Room/Accessibility overlay 审批，`open_app` 还要求三层包白名单以及完成/答案级目标包绑定，`back / home` 为空参数、零审批 SAFE 导航，`home` 还要求动态 launcher 验证。文本输入另具备敏感参数预审计、当前 ref 的可编辑/未脱敏目标证据、最小指纹授权、绑定原 `nodePath` 的精确回读，以及跨 Workflow/直接 `/agent` 的无原文持久化投影；Redmi 真实 Workflow 已分别取得打开应用 `APPROVED / PASSED / VERIFIED / afterPackage=com.android.calculator2`、文本输入 `APPROVED / PASSED / VERIFIED / exactReadBack=true`、返回动作 `approvals=0 / verified=true / VERIFIED` 和返回桌面动作 `approvals=0 / verified=true / VERIFIED`。`swipe` 与全部后台/定时设备工具继续关闭，截图、坐标、视觉定位和任意 App 也未开放。多步骤 Workflow 与非精确调度已完成真机验收；当前约 229.416 秒八步复合只读成功和 32.6 秒停止样本尚无引入 Foreground Service 的依据。Room v33 沿用 v29 引入的独立账本有界观察 Android 进程退出事实，并新增匿名 answerability Shadow 账本；Redmi 支持 LMK 原因报告，现有受控 `force-stop` 只产生 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，仍没有 Android 自主 LMK。数据库恢复已交付，但跨设备 Provider 密文恢复仍受 Android Keystore 限制。

补充：`WAITING_APPROVAL` 的审批恢复已经可以在原 Run 上保留任意长度的已验证前缀，并继续链尾工具、验证、后续规划和总结；恢复同时继承持久化累计执行预算，不因进程重建获得新的总时长。`notes.create` 与 `memory.remember` 开放已提交但尚未验证结果的受限只读验证；所有工具都可在成功结果和 `PASSED` 验证已经完整持久化后恢复本地收尾；严格持久化失败结果或 typed 失败验证只允许原子结算为 `FAILED`。成功结果缺 typed 验证结论的处置优先级已经审计并固定，但不扩大恢复资格；上述未交付项指这些证据不完整形状的通用执行栈、Workflow 后续步骤断点续跑以及尚未完成的自动化能力。

长期记忆最近一次删除的撤销快照保存在应用私有原子文件中；启动时会与 Room 正式记录核对，陈旧或损坏快照不会复活未删除数据，也不会阻断应用启动。

当前实现详情见 [当前实现说明](implementation-notes.md)。

## 目标能力范围

### 第一层：可靠 Agent 基座

- 可取消、可限步、可恢复的 Agent Run。
- Tool Registry、结构化参数校验和统一 Tool Result。
- 运行时间线、错误事件、停止生成和失败重试。
- 工具级风险、确认、权限和验证策略。

### 第二层：个人数据与工作流

- 可管理的长期记忆和用户画像。
- 笔记、提醒、日历、文件等应用内或系统标准能力。
- 可保存、启停和查看历史的定时任务与工作流。
- 可按需加载的 Skill，不把全部工具定义塞入每次模型请求。
- Skill 只能引用已注册工具并缩小工具面，不能修改工具风险、审批、Android 权限和后置验证策略。

### 第三层：移动端执行

- 在独立授权后使用 AccessibilityService 读取界面结构并执行有限操作。
- 以可定位节点为主，截图和坐标只作为兜底。
- 高风险动作前确认，动作后重新观察验证。
- 权限失效、页面变化、任务取消和系统回收后能够明确失败并恢复。

## 暂缓范围

- 账号体系、云同步和跨设备一致性。
- 任意 Shell、Root、ADB 或隐藏系统接口执行。
- 无确认的支付、下单、删除、发送消息和系统设置修改。
- 开放式 Skill 市场和未经审查的远程代码安装。
- 一开始就引入多 Agent 自主协作、完整 MCP 生态或端侧大模型管理。

## 质量要求

- 每个 Agent Run 都有稳定 ID、状态、步骤、事件、耗时和最终结果；终态写入后不可被任何迟到执行路径覆盖。
- App 被杀死或进程重建后，不得把运行中的任务误报为成功，也不得让 AgentRun 与关联 Workflow/ScheduledTask 形成互相矛盾的终态；同一进程刚启动且仍正常 `RUNNING` 的 Worker 不得被前台启动恢复当作旧进程遗留收敛。Workflow 仍活动且用户停止已经写入 `STOP_REQUESTED` 后，平台取消失败、迟到回调、进程所有权或应用重启都不得把 Workflow/Task 写成成功，也不得追加成功会话结果或提前物化周期下一实例；步骤终态与对应成功消息必须共享同一停止栅栏和事务边界。若 Workflow 已先进入终态而 Task 仍活动，后续原子结算必须保留该持久 Workflow 终态并映射到 Task，不得用来晚的停止或迟到 outcome 反向覆盖。
- 工具参数在执行前必须完成类型和业务校验。
- 敏感工具必须在应用侧确认，后台任务不得绕过确认策略。
- 工具报告成功后，关键变更必须有后置验证；无法验证时明确标为“未验证”。
- RunEvent 与独立工具账本双写必须原子完成；v20 非空账本在展示和受限恢复中均为事实源，事件只用于一致性核对，任一身份、字段、时间、顺序或基数漂移都必须 fail-closed。旧 Run 账本完全为空时保守回退到原事件，不得伪造 ToolCall 关联或改变历史恢复结论。
- 记忆写入必须可追溯到会话或任务；候选未经确认不得参与检索，敏感阻断不得保存原值，删除后不再参与检索。
- debug 日志可以诊断请求和工具过程，release 日志不得泄露密钥、完整隐私内容或敏感参数。
- 每个里程碑都需要单元测试、关键状态机测试和 Android 真机验证。

具体开发顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
