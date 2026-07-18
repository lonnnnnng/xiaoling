# 验证报告

验证日期：2026-07-18（北京时间）

## 环境

- macOS 原生环境 + zsh
- Android 真机：`wsvwypiz7xwslvl7`
- 设备型号：Redmi Note 8 Pro
- Android：14 / API 34
- App 包名：`com.longdev.xiaoling`
- App 展示名：小灵

## 构建验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleRelease --stacktrace --console=plain
```

结果：

```text
BUILD SUCCESSFUL
```

## Room Schema 与迁移自动化验证

Schema 生成方式：

- Room compiler 使用 KSP `2.3.7`，通过 Room Gradle Plugin `2.8.4` 的 `schemaDirectory` 导出到 `app/schemas/`。
- v4 Schema 从固定历史提交 `425717b` 的数据库实体生成，v6 保存 RunEvent metadata 改造前结构，v7 保存重试关联改造前结构，v8 保存 Memory FTS 改造前结构，v9 保存候选表引入前结构，v10 由当前源码生成。
- 历史中数据库版本曾直接从 v4 跳到 v6，因此没有可复现的独立 v5 源码快照；自动化测试仍按正式 migrations 顺序执行到 `MIGRATION_9_10`。

单测试命令：

```zsh
adb -s wsvwypiz7xwslvl7 shell am instrument -w -r -e class com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

```text
OK (6 tests)
```

已验证：

- 带真实旧数据的 v4 数据库通过正式 migrations 升级到 v10，Room 最终 Schema 校验通过。
- Provider、会话、用户/assistant 消息、Agent Run、Step、审批、Run Event、笔记和长期记忆均可通过 DAO 回读。
- v4 旧消息迁移后 `origin=LEGACY`，`verifiedAgentContext=null`。
- v6 中保存在 `RunEvent.message` 的旧 JSON object 会迁入 `metadataJson`，原 message 改为事件可读摘要；普通文本 message 不会被误标为结构化 metadata。
- v7 旧 Run 升级到 v8 后，状态、结果和错误保持不变，`retryOfRunId` 初始化为 `null`。
- v8 旧记忆升级到 v9 后，`pinned` 初始化为 `false`，FTS 索引立即可检索，无需再次编辑。
- v9 正式记忆升级到 v10 后保持原内容和 FTS，新增候选表为空，不会要求用户重复确认历史记忆。
- 全新 v10 内存数据库可以创建、打开并执行 DAO 查询。
- Room 2.8.4 需要 kotlinx serialization 1.8.1；工程已统一该现有传递依赖版本，避免 KSP Schema 导出和 `room-testing` 运行时接口不一致。

完整回归命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s wsvwypiz7xwslvl7 shell am instrument -w -r com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

```text
101 JVM tests passed
OK (15 Android tests)
BUILD SUCCESSFUL
```

补充说明：首次加入 `lintDebug` 时发现相机权限缺少可选硬件声明；已增加 `android.hardware.camera` 且 `required=false`，重跑完整命令后 lint 通过。

## Provider Adapter 与 Responses 结构化历史验证

实现依据：OpenAI 官方迁移文档确认 Responses API 的 `input` 可以直接接收消息列表，简单文本消息可复用 `role/content` 结构；system 或 developer guidance 也可以使用兼容消息 Item。参考：[Migrate to the Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses)。

单测试命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.network.OpenAiCompatibleAdapterTest
```

已验证：

- Responses 请求的 `input` 是 JSON 数组，system/user/assistant 的角色和正文逐条保留，不再拼接成单一字符串。
- Chat Completions 继续使用 `/chat/completions`、`messages` 和 `max_tokens`，不会混入 Responses 字段。
- `LlmProviderAdapter` 负责 Provider URL、payload 和响应映射，`OpenAiCompatibleClient` 负责 HTTP、取消、计时和 SSE 读取。
- `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` 完整回归通过：70 条 JVM 单元测试、7 条 Redmi Note 8 Pro 真机测试全部通过，lint 与 debug 构建成功。

## RunEvent metadata 与 Responses 函数 Items 验证

实现依据：OpenAI 官方 Function calling 文档规定 Responses 的函数调用 Item 使用 `type=function_call`、`name`、JSON 字符串 `arguments` 和 `call_id`；执行结果使用 `type=function_call_output`、同一 `call_id` 与 `output`。参考：[Function calling](https://developers.openai.com/api/docs/guides/function-calling)。

定向验证命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.agent.MinimalAgentRuntimeTest --tests com.longdev.xiaoling.network.OpenAiCompatibleAdapterTest --tests com.longdev.xiaoling.ui.AgentRunEventPresentationTest
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.storage.RoomAgentRunRepositoryInstrumentedTest
```

已验证：

- ToolCall、ToolResult、审批、失败与恢复事件通过 sealed `RunEventMetadata` variants 暴露合法字段组合，新事件的 `message` 只保存可读摘要。
- Room v7 引入独立 `metadataJson` 列，当前 v10 中 metadata 可以通过 Repository round-trip，v6 旧 JSON event 可迁移且特殊字符不丢失。
- 任务中心 UI 直接读取 typed metadata，不再解析数据库 JSON；纯文本历史事件继续回退显示原文。
- Responses `input` 可同时包含消息、`function_call` 和 `function_call_output`，调用与结果通过相同 `call_id` 关联。

## Agent 任务中心与安全重新运行验证

定向命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest --tests com.longdev.xiaoling.agent.AgentTaskRetryPolicyTest --console=plain
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest#migrate7To8PreservesLegacyRunAndInitializesRetryLink,com.longdev.xiaoling.storage.RoomAgentRunRepositoryInstrumentedTest#retryCreatesLinkedRunWithoutChangingSourceRun --console=plain
```

已验证：

- `FAILED / CANCELLED / BUDGET_EXHAUSTED` 可重试，其他状态不可重试。
- 已成功执行非 SAFE 工具、启动恢复记录表明中断发生在 `EXECUTING/VERIFYING`，或 `tool.execute/tool.verify` 步骤以失败/取消结束时，重试策略要求二次确认。
- 重试创建带 `retryOfRunId` 的新 Run；来源 Run 的状态、结果、步骤和事件快照完全不变。
- Room v7→v8 迁移为旧 Run 初始化空关联，同时保留原状态、结果和错误。
- Redmi Note 8 Pro 上覆盖安装 Debug 包成功，保留应用数据；v8 启动正常，crash buffer 为空。
- UI 自动化通过 UI tree 派生坐标进入「设置 → Agent 任务中心」；1080×2340 视口下，全部/处理中/可重试/已完成筛选与空状态完整显示，无文字或控件重叠。
- 使用真机已有 `gpt-5.5` Provider 在独立测试会话发起 `/agent remember test preference permanently__；模型提出 `memory.remember` 后，第一次审批被拒绝，来源 Run 正确进入 `FAILED`。
- 任务中心展开来源 Run 后可查看步骤、审批参数和结构化事件；点击“重试”立即创建新 Run，新 Run 显示来源 Run ID，来源卡片显示“重试中”并禁用重复点击。
- 新 Run 再次进入 `WAITING_APPROVAL`，说明写入工具没有继承旧审批；第二次审批也被拒绝，最终没有写入长期记忆，且没有遗留待处理任务。
- 审查修复后再次从任务中心点击重试，应用自动切回来源会话；新 `/agent` 消息、实时步骤和审批卡均在当前屏幕可见。第三次写入审批同样被拒绝，任务收敛且 crash buffer 为空。

完整回归结果：

- `testDebugUnitTest`：77 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：9 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

当前验证边界：

- 二次确认弹窗只在“已成功执行非 SAFE 工具”或“中断发生在 EXECUTING/VERIFYING”时出现；本轮真实 Provider 流程在工具执行前拒绝审批，因此该分支由策略单元测试覆盖，未在真机制造真实副作用后再验证。

## 长期记忆管理与 FTS 验证

定向测试覆盖：

- FTS4 英文/标签前缀查询和双引号转义。
- 中文多词与任意子串的 `LIKE` 兜底召回，`%`、`_` 和反斜杠按字面搜索，不会扩大匹配范围。
- 置顶优先、启用状态筛选，以及禁用后不再参与 `memory.search`。
- 编辑内容时保留来源会话/Run；主表与 FTS 索引在新增、编辑和删除事务中保持一致。
- v8→v9 迁移保留旧记忆并立即回填 FTS 索引。

真机 UI 已验证：

- Redmi Note 8 Pro 横屏下，空状态、记忆列表、英文搜索和“已禁用”筛选无文字或控件重叠。
- 从“已禁用”筛选中重新启用记忆后，该条目立即移出当前列表。
- 内容编辑保存、删除二次确认和来源审计信息正常；测试数据通过页面删除后，`agent_memories` 与 `agent_memories_fts` 均为空。

本阶段完整回归结果：

- `testDebugUnitTest`：79 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：11 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

## 候选记忆、敏感过滤与删除撤销验证

定向测试覆盖：

- 明确偏好生成 `PENDING` 候选，普通问答不生成候选；候选确认前不进入正式记忆和 FTS。
- API Key（含 `sk-`、GitHub、Google、AWS 常见前缀）、token、密码、银行卡、身份证和手机号固定样例全部进入 `BLOCKED_SENSITIVE`，候选 content、normalized content、标签和来源摘要不包含原值。
- 忽略空格、标点和大小写后相同的事实标记为 `DUPLICATE`；`memory.remember` 直接写入也复用旧记忆 ID，不产生重复行。
- 同类型、同主题但内容不同的事实标记为 `CONFLICT` 并关联旧记忆；确认时另存新记录，不覆盖旧记录。
- 删除前原子保存最近一次完整快照并立即移除主表与 FTS；撤销后在同一事务中恢复主表、来源、置顶、生命周期字段和 FTS。
- 新增跨 Store 实例测试模拟进程重建：删除后新实例可读取撤销快照并恢复；若快照已写但 Room 正式记忆仍存在，则清理陈旧快照而不重复提供撤销；损坏快照会被删除且不阻断记忆管理。
- v9→v10 迁移保留已确认记忆并创建空候选表；全新 v10 数据库可打开。

真机验证：

- 使用 `adb install -r` 覆盖安装 debug 与 androidTest APK，再直接调用 `AndroidJUnitRunner`，未执行会清除设备配置的 Gradle connected 流程。
- 完整真机套件 `OK (15 tests)`；执行后 Provider 记录、选中模型和 Keystore 密文仍存在。
- Redmi Note 8 Pro 横屏下，「长期记忆」页候选开关默认显示“已关闭”；开关、搜索、筛选和空状态完整显示，无重叠或截断。
- 实际数据库 `PRAGMA user_version=10`，应用覆盖安装、启动和 Room 迁移正常。

本阶段完整回归结果：

- `testDebugUnitTest`：99 条 JVM 单元测试通过。
- 手动 `AndroidJUnitRunner`：15 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 通过。

## 记忆引用审计与单次召回关闭验证

本轮 JVM 定向测试覆盖：

- `memory.search` 返回真实命中的 `memoryIdsUsed`；关闭记忆召回时不访问 Store，规划器工具清单移除 `memory.search`。
- `RunEventMetadata.ToolResult` 和 `VerifiedAgentContext` 的 memory ID 编解码；旧 JSON 缺少字段时兼容为空列表。
- `memory.recall.disabled` 事件在单次 Run 写入，关闭只影响读取，不绕过 `memory.remember` 的审批链路。
- 任务中心工具结果展示实际使用的 memory ID，历史事件和普通工具结果保持原有展示。

本轮验证结果：

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug`：`105` 条 JVM 测试通过。
- `memoryIdsUsed` 只来自执行器返回的真实记录，不接受模型总结自由文本伪造；未执行真机 Agent 请求，Provider 上游状态不影响本轮本地契约验证。

## 进程重建恢复边界验证

本轮新增 `AgentRunResumePolicy`，以持久化 Run 快照、审批列表、步骤和事件判断恢复方式：

- `WAITING_APPROVAL` 且存在 `PENDING` 审批、没有 `tool.execute` / `tool.verify` 步骤或结果时，评估为 `APPROVAL_WAIT`，允许后续接入原 Run 审批恢复。
- 已出现工具执行或验证记录，或 Run 处于其他状态时，评估为 `RESTART_REQUIRED`，必须创建新 Run，旧 Run 不修改。
- 已新增 JVM 单元测试覆盖上述三条边界；本环境 Gradle 仍被沙箱阻止，未能重新运行测试。
- 本轮先落地确定性恢复策略；启动协调器对执行/验证中 Run 仍采用收敛为 `CANCELLED` 的保守实现，待审批 Run 可继续进入原 Run 执行入口。

本轮进一步接入启动协调器：

- Room 中符合 `APPROVAL_WAIT` 的 Run 会在启动后重建到对应会话和审批卡片；批准后从原审批步骤继续执行同一 Run，不创建 `retryOfRunId`。
- 已进入工具执行/验证阶段的 Run 仍由启动收敛逻辑关闭；新增 instrumentation 测试覆盖待审批 Run 保持 `WAITING_APPROVAL`、审批保持 `PENDING` 和恢复事件 typed metadata。
- 当前环境仍无法运行 Gradle，因此新增 JVM/Room/UI 编译和真机进程重建验证未执行。

本轮新增原 Run 执行入口：

- `MinimalAgentRuntime.resumeApprovedRun` 只接受 `APPROVAL_WAIT` 评估结果，使用持久化审批中的工具名和参数，不重新调用模型规划。
- 工具执行、后置验证、模型总结、`RunEvent` 和最终 `COMPLETED/FAILED` 状态均写回原 Run；新增 JVM 测试验证 Run ID 不变且不产生 `llm.plan` 步骤。
- 新增 JVM 测试验证恢复工具失败写入原 Run `FAILED`，且重试策略要求二次确认；新增 Room instrumentation 测试用新 Repository 实例模拟组件重建，串起审批重建、批准、工具执行、验证和同 Run `COMPLETED`。
- 已验证 Gradle 8.13 分发包可在临时 `GRADLE_USER_HOME` 启动，但进入构建仍因 `FileLockContentionHandler` 创建本地 Socket 被沙箱拒绝；新增测试尚未实际运行，APK 和真机进程重建验证也未执行，不能把该入口报告为设备验收通过。

## 内置 Skill 按需加载验证边界

本轮新增四类内置声明式 Skill、稳定关键词选择、最多 3 个 Skill 限制、工具白名单包装和 `skill.selected` RunEvent：

- 单元测试覆盖多意图目标的稳定选择、未命中时保留原工具集、Skill 不能引用未注册工具，以及执行层拒绝越过 Skill 白名单。
- `AgentRunUseCase` 已把选中 Skill 的指令和收窄后的工具定义传给规划器；工具风险、审批、权限和验证仍取自原 `ToolDefinition`。
- 当前环境的 Gradle 与 ADB 都因本地 Socket 权限被沙箱阻止，新增测试、APK 构建和真机行为尚未执行；本轮不包含 Skill 导入、管理 UI、多步工具循环或后台执行。

## 记忆过期与时间衰减验证边界

本轮实现新增 Room v10→v11 迁移、可空 `expiresAt` / `lastReferencedAt`、过期检索过滤、引用时间回写、置顶保护和按类型半衰期排序，并在长期记忆管理页提供永久、30 天、90 天和 1 年策略。

当前环境的 Gradle Wrapper 和直接 Gradle 均被沙箱阻止启动本地 `FileLockContentionHandler` Socket，关键错误为 `java.net.SocketException: Operation not permitted`。因此本轮新增 JVM、Room instrumentation、lint 和 APK 构建尚未能在当前环境重新执行；`git diff --check` 和 schema JSON 结构检查已通过。未运行会清理设备数据的 connected instrumentation。

外部服务边界：

- 本机兜底 Provider 的模型列表与鉴权已验证成功；按本机指令选择 `gpt-5.5` 后，真实对话请求到达服务端但返回 `HTTP 503 · 无可用账号`。同端点小范围候选探测也返回 503/429/403，因此当前未取得真实回复成功证据，该结果不归因于应用实现。

## 数据备份与恢复验证

定向测试覆盖：

- ZIP manifest 与 Room 主库字节可往返恢复，manifest 保存 schema/app 版本和 Keystore 依赖标记。
- 未来 schema 在写入目标数据库前被拒绝；导入真实 SQLite 前再次校验 `PRAGMA user_version`。
- 导出前执行 WAL checkpoint；恢复前关闭 Room，保留 `.pre-restore` 安全副本，清理 `-wal/-shm` 并提示重启。
- API Key 不进入 manifest 或明文导出；Provider 表中的密文只能在原设备 Keystore 仍存在时解密。

真机验证：

- Redmi Note 8 Pro 横屏/竖屏下设置页显示「数据备份与恢复」，导出按钮进入系统 Create Document，默认文件名为 `xiaoling-backup-*.zip`。
- 选择保存位置后显示“备份已导出”；Open Document 导入器可打开，实际替换恢复本轮仅完成确认前的文件选择验证，未覆盖当前设备数据库。

本阶段完整回归结果：

- `testDebugUnitTest`：101 条 JVM 单元测试通过。
- `lintDebug`、`assembleDebug` 与 `assembleDebugAndroidTest` 通过。

## Tool Schema 与权限策略验证

定向测试覆盖：

- 输入 Schema 支持字符串、整数、数值和布尔逻辑类型，以及必填、长度、数值范围和枚举约束；未知参数默认拒绝。
- 模型可见 Schema 使用 `object/properties/required/additionalProperties=false`，不再依赖自然语言描述猜测类型。
- 可插拔业务校验器已用于 `memory.remember` 标签数量和单标签长度限制；Schema 失败时不会继续运行业务规则。
- 非 SAFE 工具不能把确认策略降级为 `NONE`；`notes.create` 与 `memory.remember` 要求 Executor 回读验证，只有普通成功文本时 Run 在 `tool.verify` 失败。
- Registry 初始化拒绝重复工具名；当前 8 个生产工具均声明 5 秒超时、确认/验证策略、空 Android 权限集合和 `supportsBackground=false`。
- Runtime 在审批和执行前检查 Android 权限；检查器未注入时默认 fail-closed，模型把风险伪报为 SAFE 也不能绕过定义侧风险和权限。
- 模型返回的 integer/boolean JSON primitive 能通过参数解析进入逻辑类型校验；数组形式 `arguments`、字符串形式 integer、对象形式 STRING 和超出 Long 范围的整数均被解析层拒绝，非 STRING 字段不能声明字符串枚举。
- 前台限定工具在 `BACKGROUND` 来源下于审批前失败。
- 最终确定性回复按实际确认和验证策略渲染，不再根据风险或固定“结果可读”文案推断。

真机验证：

- `AndroidToolPermissionChecker` 在 Redmi Note 8 Pro 上把 manifest 中已授予的 `INTERNET` 识别为可用，并继续拒绝未声明权限。
- 当前生产工具均为应用内能力，不触发 Android 运行时权限弹窗；真实系统工具的授权、撤销和后台策略仍需随对应工具单独做真机验收。

本阶段完整回归结果：

- `testDebugUnitTest`：94 条 JVM 单元测试通过。
- `connectedDebugAndroidTest`：12 条 Redmi Note 8 Pro 真机测试通过。
- `lintDebug` 与 `assembleDebug` 通过。

## 签名验证

执行命令：

```zsh
/Users/long/Library/Android/sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

结果：

```text
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=XiaoLing, OU=XiaoLing, O=Long, L=Shanghai, ST=Shanghai, C=CN
Signer #1 certificate SHA-256 digest: 5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9
```

说明：

- 从 `v0.1.8` 起应用使用 `com.longdev.xiaoling`；从旧 `applicationId` 升级时，Android 会把它视为新应用。
- 本机 release keystore 为小灵专用证书，`v0.1.9` 与 `v0.1.8` 使用同一签名证书。

## APK 元数据

执行命令：

```zsh
/Users/long/Library/Android/sdk/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/release/app-release.apk
```

关键结果：

```text
package: name='com.longdev.xiaoling' versionCode='10' versionName='0.1.9'
application-label:'小灵'
```

## GitHub Release

- Release：[小灵 v0.1.9](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.9)
- 标签：`v0.1.9`
- 发布提交：`3059b4c53d4c063aaf929352e14cea040bb56287`
- APK：[xiaoling-v0.1.9.apk](https://github.com/lonnnnnng/xiaoling/releases/download/v0.1.9/xiaoling-v0.1.9.apk)
- 远端资产状态：APK 和 SHA-256 文件均为 `uploaded`，Release 不是 draft 或 prerelease。
- 远端 APK digest：`sha256:b8a8c77e6e1f83543d3bd775ffda83615e7f06ad846b0c0f83cf9a1ac778c5b8`

## 真机安装与启动

### 当前 main debug 安装

验证基线：`433d43b` 之后的长期记忆管理工作区

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew installDebug --console=plain
adb -s wsvwypiz7xwslvl7 shell monkey -p com.longdev.xiaoling -c android.intent.category.LAUNCHER 1
```

结果：

```text
Installed on 1 device.
Events injected: 1
```

已确认：

- APK 包名为 `com.longdev.xiaoling`，`versionName=0.1.9`，`versionCode=10`。
- instrumentation 回归后重新执行 `installDebug`，本次没有执行卸载或清数据命令。
- `topResumedActivity` 为 `com.longdev.xiaoling/.MainActivity`，应用已在 Redmi Note 8 Pro 前台。
- 应用进程存活，启动后的 crash buffer 为空。

### v0.1.9 release 覆盖安装历史

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r outputs/release/xiaoling-v0.1.9.apk
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

结果：

```text
Performing Streamed Install
adb: failed to install outputs/release/xiaoling-v0.1.9.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.longdev.xiaoling signatures do not match newer version; ignoring!]
Starting: Intent { cmp=com.longdev.xiaoling/.MainActivity }
```

前台 Activity：

```text
com.longdev.xiaoling/com.longdev.xiaoling.MainActivity
```

已确认：

- 设备上已有同包名但不同签名的安装包，系统拒绝覆盖安装 release APK；未执行卸载或清数据。
- 已有安装包的对话页可启动。
- 本次未在该真机上覆盖安装 release 包；如需从 debug 签名切换到 release 签名，需要用户确认后卸载旧包或换干净设备验证。

## 日志检查

启动期间 logcat 未命中应用崩溃、`FATAL EXCEPTION`、`AndroidRuntime` 或 ANR。

## 提示词设置增量验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:installDebug --console=plain
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

已验证：

- 提示词策略单元测试通过，覆盖普通对话、会话摘要和 Agent 总结的不可覆盖边界。
- 审查修复回归测试通过：用户正文伪造可信标记仍只会落入 JSON `content` 字段；超长普通 assistant 回复和重复兜底摘要均保留非证据标签；Agent 模型只能选择有限展示枚举，合法选择可改变详略和语气，非法自由文本不能增加或展示虚构工具事实；`VerifiedAgentContext` 编解码可无损往返；旧 assistant 消息按普通回复保守迁移。
- debug 源集编译、APK 构建和真机覆盖安装成功。
- Room 数据库先从 v4 升级到 v5，再从 v5 升级到 v6；两次覆盖安装后应用均正常启动，消息来源和独立 Agent 审计上下文列未触发 Room、SQLite 或 Migration 异常。
- 应用进程存活，任务栈包含 `com.longdev.xiaoling/.MainActivity`，crash buffer 为空。

未完成验证：

- 真机停留在 keyguard，`uiautomator` 仍只能读取锁屏节点；未输入用户凭据，因此提示词设置页的点击、预览和重启持久化流程尚未完成可视验证。

## 产物

| 文件 | 说明 |
|---|---|
| `../app/build/outputs/apk/release/app-release.apk` | release 包，已通过正式签名验证。 |
| `../outputs/release/xiaoling-v0.1.9.apk` | GitHub Release 上传用 APK，SHA-256：`b8a8c77e6e1f83543d3bd775ffda83615e7f06ad846b0c0f83cf9a1ac778c5b8`。 |
| `../outputs/release/xiaoling-v0.1.9.apk.sha256` | APK SHA-256 校验文件。 |

## 清理状态

- 2026-07-19 完整 instrumentation 按测试框架语义重置了应用数据；随后已重新安装最新 Debug APK，从未跟踪的本机配置恢复 Provider，获取 6 个上游模型，并用指定模型完成真实 `OK` 冒烟响应。
- 审批恢复验收创建的临时长期记忆已通过管理 UI 删除，数据库确认残留数为 0。
- `outputs/` 目录不纳入版本控制。

## 2026-07-18 待审批 Run 真机进程重建验收

环境与构建：

- 设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro；应用 `com.longdev.xiaoling`，`versionName=0.1.9`，`versionCode=10`。
- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug --console=plain`，121 项 Debug 单元测试通过。
- Debug APK SHA-256：`1c85b53715c1ffd5d519513a1b8b74e0c5665d70bfb2c53426fb45d525dfe899`。
- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装；未卸载、未清数据、未运行 instrumentation 测试，Provider、历史会话和 Keystore 凭据保持可用。

真实流程：

- 输入 `/agent remember process rebuild acceptance marker 20260718-1038`，模型选择 `memory.remember`。
- Run：`run-b6dc92d7-19b8-4b25-a189-a34d4e815297`；Approval：`approval-f8669cc6-1244-4820-ac06-3ff5e4388b5a`。
- 审批前数据库为 `WAITING_APPROVAL / PENDING / retryOfRunId=NULL`；执行 `am force-stop` 后 PID 消失，Run 和审批状态保持不变。
- 首次重启发现 Run 已恢复但用户消息尚未持久化，导致审批卡片没有 UI 锚点；修复为发送 Agent 后立即保存消息，并在恢复旧数据时按 `userMessageId / goal / createdAt` 补回缺失锚点。
- 覆盖安装修复包后，UI 显示“进程重建后待恢复”和“批准并继续”；批准后原 Run 依次进入 `EXECUTING / VERIFYING / THINKING / COMPLETED`。
- 原 Run 的步骤仍只有一次 `llm.plan`，恢复阶段没有新增规划事件或新 Run；同目标 Run 数量为 1，`retryOfRunId` 仍为 `NULL`。
- 工具执行和回读验证均通过；记忆 `memory-fd3b356d-e8cc-434e-a9c9-0cd35374de26` 已写入，`sourceRunId` 指向原 Run，内容为 `process rebuild acceptance marker 20260718-1038`。
- 最终 UI 显示 Agent 已完成，crash buffer 为空。

## 2026-07-18 顺序多步 Agent 真机验收

环境与构建：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug --console=plain`，审查修复后的最终版本有 128 项 Debug 单元测试通过。
- 最终 Debug APK SHA-256：`b83390ed8966bffcc16b1dfbaa39a1aadb2a2842637a9a838890c270cf85f710`。
- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装；未卸载、未清数据、未运行 instrumentation 测试，应用冷启动成功且 crash buffer 为空。

真实流程：

- Run `run-e055f8b5-572e-4068-ae14-1ddacc8ace8d`：目标要求先读取当前时间再列出最近会话；同一 Run 依次执行 `app.current_time -> app.list_conversations`，两次参数校验、SAFE 审批跳过、工具执行和后置验证均完成，第三次 `llm.plan` 返回完成决策。
- Run `run-713e0101-cb53-431c-a388-a98a0885f9b0`：反向要求先列会话再读时间；同一 Run 依次执行 `app.list_conversations -> app.current_time` 后完成，证明生产规划器按目标和已验证历史选择下一步，不是固定工具脚本。
- 两个 Run 都只创建一个 `AgentRun`，最终为 `COMPLETED`；UI 时间线显示 10 个步骤，最终回复按步骤包含两个真实工具结果。
- 最终消息的 `VerifiedAgentContext.toolExecutions` 按真实执行顺序保存两个工具、参数、结果和验证状态；顶层旧字段映射最后一步。
- 首次真机持久化发现 Android `org.json` 将 Kotlin 字符串列表写成字符串 `"[]"`；最终修复版显式构造 `JSONArray` 并兼容读取旧字符串化数组。第二个 Run 的数据库原文确认顶层和两个工具步骤的 `memoryIdsUsed` 均为 JSON 数组 `[]`。
- 审查修复后的最终 APK 新建 Run `run-c8c6abdd-2c06-48dd-8b16-efeb08dc53be`，再次完成 `app.current_time -> app.list_conversations -> complete`；数据库确认 10 个步骤全部完成，最终可信上下文包含两个有序工具执行项。
- Run `run-0c6c64be-1605-4733-9de3-d7e759dde7ae` 在首步 `memory.remember` 审批前强制停止进程；冷启动后 UI 重建“进程重建后待恢复”审批卡片，批准后同一 Run 继续 `memory.remember -> app.current_time -> complete`，11 个步骤全部完成且未创建替代 Run。
- 另一 Run 在 `memory.search` 已完成、`memory.remember` 等待审批时强制停止进程，启动协调器按安全策略把原 Run 和审批分别收敛为 `CANCELLED`，验证“已有任意工具执行记录则不原地恢复”。本轮写入的恢复测试记忆已通过长期记忆管理 UI 删除，主表与 FTS 索引均确认无残留。
- 双轴审查发现首步审批恢复曾使用默认 `memoryRecallEnabled=true`；最终修复版改为从原 Run 的 `memory.recall.disabled` 持久化事件还原开关，并由单元测试确认恢复后的 Tool Registry Context 仍为关闭召回，避免后续步骤重新暴露 `memory.search`。
- 最终修复 APK 再次通过真机进程重建验收：Run `run-cb007d20-4a6b-4f26-b1c2-8b5183658719` 在关闭单次记忆召回后停在首步 `memory.remember` 审批；冷启动批准后，同一 Run 完成 `memory.remember -> app.current_time -> complete`。恢复后的实际规划请求工具清单只含 `app.current_time` 和 `memory.remember`，不含 `memory.search`；测试记忆已通过管理 UI 删除，主表与 FTS 均无残留。

边界：

- 首版仅支持顺序执行，不支持并行工具调用。
- 任一步仍独立执行 Schema、权限、风险、审批和验证策略；前一步批准不能放宽后续工具。
- 进程重建只允许尚未执行任何工具的首个 `WAITING_APPROVAL` 边界原地继续；已有工具执行/验证记录的多步 Run 仍安全收敛并通过关联新 Run 重试。

## 2026-07-18 本地 Agent Skill 导入与管理验证

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；136 项 Debug 单元测试通过，失败数为 0。
- `AgentSkillDocumentCodecTest` 覆盖合法 v1 JSON、未知可执行字段、空触发词和 UTF-8 64 KiB 字节上限；`AgentSkillsTest` 覆盖本地导入、关键词/触发示例选择、停用、升版保持停用、选择审计版本恢复和禁止覆盖内置 ID；`RunEventMetadataCodecTest` 覆盖 `skill.selected` 的 Room 编解码。
- AndroidTest APK 编译通过；迁移测试已扩展到 v4→v12、v9→v12、v11→v12 和全新 v12 建库，`RoomAgentSkillStoreInstrumentedTest` 覆盖内置定义升级后保留用户停用决定。本轮为保护设备 Keystore API Key，没有执行 instrumentation。
- Debug APK SHA-256：`0d077db6c40e97e1e61dfc87090f8a52d5f6dfd09ce1c78a1436d76723575402`。

真机验证：

- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装成功，未卸载、未清数据；安装后为 `versionName=0.1.9`、`versionCode=10`。
- 应用启动触发主库升级；只读取非敏感结构信息确认 `PRAGMA user_version=12`、`agent_skills` 表和 `index_agent_skills_source_enabled_updatedAt` 索引存在，初始本地表记录数为 0；crash buffer 为空。
- 手机停留在系统锁屏，ADB 无法关闭锁屏。因此本轮尚未完成「设置 -> Agent Skills」中 4 个内置 Skill 展示、导入 [`examples/daily-review.skill.json`](examples/daily-review.skill.json)、启停、`skill.selected` RunEvent 和删除后无残留的可视验收；不以单元测试替代这部分真机结论。

安全边界：

- 本地 Skill 只允许 `schemaVersion=1` 声明式 JSON，不执行脚本；字段白名单、工具注册、最高风险和 Android 权限必须全部匹配后才能写入 Room。
- 本地 Skill 不能覆盖内置 ID，同 ID 更新必须提高版本；启停立即影响后续 Skill 选择，删除只允许 `source=LOCAL`。
- 新 Run 的 `skill.selected` 事件记录 `id@version`；恢复审批时只接受原版本仍存在的定义，Skill 在等待期间被删除或升版不会扩大工具面。
- 兼容旧 Run 时，无版本的 Skill 审计只允许解析为内置 Skill；本地 Skill 缺少版本记录时 fail-closed，并要求创建新 Run。

## 2026-07-18 Workflow Ledger 与前台手动执行验证

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；139 项 Debug 单元测试通过，失败数为 0。
- `WorkflowDefinitionPolicyTest` 覆盖合法定义、空名称、超长目标和 Agent→Workflow 共享终态映射；`RoomWorkflowRepositoryInstrumentedTest` 已编译覆盖手动 Run/Step 原子创建、事务内拒绝重复活动 Run、重复 Agent 快照幂等关联、完成结果，以及进程重建时保留审批等待并收敛已取消 Agent Run。
- `XiaoLingDatabaseMigrationInstrumentedTest` 已编译覆盖 v4→v13、v9→v13、v12→v13 与全新 v13 建库。为保护设备 Keystore API Key，本轮没有执行 instrumentation。
- Debug APK SHA-256：`66a3c3e94164fd5f6bc552d16a753caecf1cadb3f5d1d2bff4083afbb7e2a798`。

真机验证：

- 在 `wsvwypiz7xwslvl7` 使用 `adb install -r` 覆盖安装成功，未卸载、未清数据；安装后仍为 `versionName=0.1.9`、`versionCode=10`。
- 首次在 Activity 刚启动时抢先读取数据库仍得到 v12，并出现 `no such table: workflows`；等待 Room 完成打开后重试，确认 `PRAGMA user_version=13`，`workflows / workflow_runs / workflow_steps` 三张表均存在且初始记录数为 0。
- Activity 正常显示，Room Schema 校验日志完成，crash buffer 为空。
- 手机仍停留在系统锁屏，`uiautomator` 只能读取 `com.android.systemui`。因此本轮尚未完成设置页新建/启停工作流、手动运行跳回会话、SAFE 完成、审批拒绝/批准以及 Ledger 结果展示的可视验收。

边界：

- 当前每个 Workflow 固定为一个 `AGENT_RUN` 步骤，只支持 `MANUAL` 前台触发；同一 Workflow 有未完成 Run 时拒绝重复启动。
- 重复活动 Run 的保护位于 Room 创建事务内；UI 展开工作流可查看已加载的多次历史 Run。恢复前置校验失败且 Agent 仍在等待审批时，不会提前把 Workflow 标为失败。
- 工作流不创建新的工具授权层，所有工具继续执行现有 Schema、权限、风险、审批和后置验证策略。
- 本轮没有引入 WorkManager、定时规则、通知、Foreground Service 或后台审批；这些能力将在下一阶段基于现有 Ledger 接入。

## 2026-07-18 一次性非精确定时工作流自动化验证

官方依据：

- Android WorkManager 官方文档确认 `OneTimeWorkRequest.setInitialDelay` 只保证任务在最小延迟后具备执行资格，实际时间仍受系统优化与约束影响；实现和 UI 均不承诺准点。
- WorkManager 使用唯一工作名和 `ExistingWorkPolicy.KEEP` 防止同一 ScheduledTask 重复入队，并要求联网后才执行 Agent 请求。
- Android 8.0+ 通知使用稳定 Channel；Android 13+ 从用户创建计划的操作中请求 `POST_NOTIFICATIONS`，通知被拒绝不改变 Room 中的业务终态。

来源：

- <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>
- <https://developer.android.com/reference/kotlin/androidx/work/WorkManager>
- <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- <https://developer.android.com/develop/ui/views/notifications/channels>

构建与自动化验证：

- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；150 项 Debug 单元测试通过，失败数为 0。
- `AgentToolCallParser` 新增兼容测试：当模型把同一个已声明工具名同时写入 `action` 与 `tool` 时归一化为工具调用；两者不一致时仍拒绝，后续继续执行注册表、Schema、风险、后台能力和审批门禁。
- `MinimalAgentRuntimeTest` 覆盖后台 SAFE 工具仅在显式 `supportsBackground=true` 时执行，以及需审批工具在调用 Gate/Executor 前进入 Agent `BLOCKED`。
- `ScheduledWorkflowOrchestratorTest` 覆盖完成、失败、blocked、系统取消和领取拒绝；确认各业务终态先写入 Ledger，再发送对应通知，取消在 `NonCancellable` 中收敛后继续向 WorkManager 传播。
- `RoomWorkflowRepositoryInstrumentedTest` 已编译覆盖一次性 ScheduledTask 创建、WorkRequest 关联、原子领取、计划/实际时间、Workflow/Agent Run 关联和 blocked 终态。
- `XiaoLingDatabaseMigrationInstrumentedTest` 已编译覆盖 v4→v14、v9→v14、v12→v14、v13→v14 与全新 v14 建库；Room 导出的 `14.json` 包含 `scheduled_tasks` 及 `workflow_runs.scheduledTaskId / plannedAt`。
- 为保护真机 Keystore 中的 Provider API Key，本轮仍未执行 instrumentation；AndroidTest 只完成源码编译和 APK 组装。

当前边界：

- 第一版只支持 1 分钟至 7 天的一次性非精确计划，不支持 Daily/Weekly、AlarmManager、精确闹钟权限或 Foreground Service。
- SAFE 后台白名单仅包含当前时间、会话查询、笔记查询和长期记忆查询；`notes.create / memory.remember` 等需审批工具不会继承前台授权，而是写入 Agent/Workflow/ScheduledTask `BLOCKED` 并提示用户以前台新 Run 重试。
- WorkManager 业务结果不使用系统自动重试，避免复制可能已经执行过的 Agent Run；触发前进程回收后的冷启动执行已验证，后台执行中的断点续跑仍未实现。

真机覆盖安装与结构验证：

- 实现提交 `ed8d7a5 实现一次性后台工作流调度` 已推送到 `origin/main`。
- 最新 Debug APK SHA-256：`1d485174f3dd508528e811f418dc7185c83f8839943a6708fd74eb2dffe13394`。
- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装成功；未卸载、未清数据、未运行 instrumentation。
- 安装后仍为 `versionName=0.1.9`、`versionCode=10`；应用进程 PID `7420`，`com.longdev.xiaoling/.MainActivity` 已进入 resumed 状态，crash buffer 为空。
- 只读取非敏感结构信息确认 `PRAGMA user_version=14`；`workflows / workflow_runs / workflow_steps / scheduled_tasks` 四张表存在，`workflow_runs` 含 `plannedAt / scheduledTaskId`，初始 `scheduled_tasks` 记录数为 0。
- 合并后的 Manifest 已注册 `androidx.startup.InitializationProvider` 和 WorkManager `SystemJobService`。

真机一次性调度与通知验收：

- 用户解锁手机后，从「设置 → 工作流」创建 `SAFE_time_test` 与 `BLOCKED_note_test`，并通过应用触发的 Android 14 系统弹窗授予 `POST_NOTIFICATIONS`；没有使用 shell 强改权限。
- 首条 SAFE 计划在权限授予前因上游 `HTTP 503` 收敛为 `FAILED`；第二条计划 `20:50:04.940` 入队，`20:50:05.022` 实际启动，偏差 `82ms`，`20:50:15.385` 完成。ScheduledTask、Workflow Run、Workflow Step、Agent Run 和 WorkRequest 全部关联，工具结果为 `app.current_time`，第一条失败 Run 保持不变。
- 成功通知真实写入 `workflow_results` Channel，标题为「工作流已完成 · SAFE_time_test」，正文包含受验证的当前时间结果；后续上游 `HTTP 502` 失败通知标题为「工作流执行失败 · BLOCKED_note_test」。
- 在另一条已入队计划触发前退到桌面并执行 `am kill com.longdev.xiaoling`，确认旧 PID 消失；WorkManager 随后由 `SystemJobService` 冷启动 PID `9845`，创建并收敛新的 Ledger。该次因上游返回 `HTTP 401` 进入 `FAILED`，但 Authorization 仍存在且没有 Keystore 解密错误，证明进程回收未破坏密钥或丢失计划。
- 更新后的外部模型独立健康探测返回 HTTP 200。该模型曾把合法工具名重复写入 `action/tool`，新增受限归一化后覆盖安装 Debug 包，保留 Provider、工作流、通知权限和 Room v14 数据。
- 最终 blocked 计划 `21:48:33.823` 入队，`21:48:34.016` 实际启动，偏差 `193ms`，`21:48:39.374` 收敛。Agent Run、Workflow Run、Workflow Step 和 ScheduledTask 均为 `BLOCKED`，错误为「后台任务需要用户确认工具：notes.create」。
- blocked Run 的 `ApprovalRequest` 数量为 `0`，目标正文 `blocked_background_test_20260718` 的笔记数量为 `0`，证明后台没有等待前台审批、没有继承临时授权且没有执行写入。
- blocked 通知真实显示在锁屏通知区，标题为「工作流需要你处理 · BLOCKED_note_test」，正文提示打开应用以前台重试。
- 全程未卸载、未清数据、未执行 instrumentation；最终 crash buffer 为空。Daily/Weekly、精确定时、Foreground Service 和后台执行中的断点续跑仍未验收。

## 2026-07-18 Daily/Weekly 周期工作流验证

设计与自动化验证：

- 周期规则没有直接使用 `PeriodicWorkRequest`。每条规则只物化一个 `OneTimeWorkRequest`，每次执行保留独立 ScheduledTask/Workflow Run/Agent Run 终态，再按保存的 `ZoneId` 计算下一个未来墙上时间；该策略继续使用 WorkManager 非精确定时语义。
- Room 升级到 v15，新增 `workflow_schedules` 和 `scheduled_tasks.scheduleId`；导出的 `15.json` 已生成。迁移测试源码覆盖 v14→v15，Repository instrumentation 源码覆盖规则创建、替换、停用和下一实例物化。
- `WorkflowSchedulePolicy` 单元测试覆盖 Daily/Weekly 下一触发时间、已过时间推进一个周期、时区和非法字段；Repository/启动协调逻辑保证只补一个未来实例，不补跑历史周期，也不恢复旧执行栈。
- 执行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain`，构建成功；153 项 Debug 单元测试通过，lint、Debug APK 和 AndroidTest APK 均组装成功。为保护真机 Keystore API Key，没有执行 instrumentation。
- Debug APK SHA-256：`f19fa0d8ab26409f2b8b9ff9c12bc263fb98f0d056ea6a6e639d67c8353389e9`。使用 `adb install -r` 覆盖安装到 `wsvwypiz7xwslvl7`，未卸载、未清数据；`versionName=0.1.9`、`versionCode=10`，`POST_NOTIFICATIONS` 保持授权。
- 真机迁移确认 `PRAGMA user_version=15`，`workflow_schedules` 表和 `scheduled_tasks.scheduleId` 列存在，应用启动后 crash buffer 为空。

Daily 执行与下一实例：

- 在 `SAFE_time_test` 上通过 UI 创建 `每日 22:55 · Asia/Shanghai`。规则 ID 为 `workflow-schedule-b9d65489-1e78-4b8b-953f-1b3e1ba3428e`，首个 ScheduledTask 为 `scheduled-task-8520ed09-9ba1-49cd-9b4b-6facea510ace`，WorkRequest 为 `f68bb610-bd87-4ea5-9cf0-3357ad27cc1a`。
- 首个实例计划时间 `2026-07-18 22:55:00`，实际同秒启动，`22:55:09` 完成。Workflow Run `workflow-run-d473feb7-c1de-4ef1-bbb6-a8fce4601347` 与 Agent Run `run-3f9aafff-8d09-4fe1-a268-0821515e7656` 均为 `COMPLETED`，真实工具结果为 `app.current_time` 返回 `2026-07-18 22:55:02 · Asia/Shanghai`。
- 终态后自动生成 `scheduled-task-877916e0-f5f9-4b9e-b7c3-c0c90a724079`，计划时间为次日 `2026-07-19 22:55:00`，WorkRequest 为 `46501ad7-deeb-4b90-9f61-bdba028e7377`；Task 和 WorkRequest ID 均与首个实例不同。

规则替换与停用：

- 将规则替换为 `每周一 22:55` 后，7 月 19 日旧实例进入 `CANCELLED`，错误摘要为「周期规则已更新」；WorkManager 对应 WorkSpec 状态为 `CANCELLED(5)`。
- 同一规则只保留一个新未来实例 `scheduled-task-efb7a081-409a-4d0e-9b56-e63d4fb2a1c4`，计划时间为 `2026-07-20 22:55:00`，WorkRequest `145379b7-89a1-45d1-8d6a-fb44a67a9194` 在 WorkManager 中为 `ENQUEUED(0)`。
- 通过页面“停用周期计划”后，Room 中规则 `enabled=0` 且清空 `nextTaskId / nextPlannedAt`；新周实例变为 `CANCELLED`，WorkManager 同一 WorkRequest 同步为 `CANCELLED(5)`，没有生成额外实例。
- 1080×2340 真机页面已检查创建弹窗、周期摘要和展开历史；每日/每周信息、时区、取消原因、Workflow/Agent 结果均完整显示，没有文字、按钮或卡片重叠。最终 crash buffer 为空。

当前边界：

- 本轮真机确认了外部模型可完成真实后台 SAFE 请求，但没有再次执行触发前杀进程的周期实例；周期启动恢复由单元测试、已编译的 Repository instrumentation 源码和此前一次性任务冷启动真机证据覆盖。
- Daily/Weekly 仍不是精确定时，不使用 AlarmManager 或 Foreground Service；后台执行中断后不恢复旧 Agent 执行栈，旧 Run 保持可审计终态并只生成未来周期实例。

## 2026-07-18 User-Agent 配置验证

- `ProviderRequestConfig` 新增设备级 `userAgent`，默认值为 `Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`；设置页可编辑并恢复默认，空白值自动回退。
- `OpenAiCompatibleClient` 的统一 Request Builder 对模型列表、Chat Completions、Responses、前台 Agent 和 WorkManager 后台 Agent 写入同一 `User-Agent` Header。
- `OpenAiCompatibleClientTest` 使用 MockWebServer 读取真实收到的请求 Header，已验证自定义值原样发送和空白配置回退默认值；测试不访问外部服务。

## 2026-07-18 多步骤 Workflow、步骤快照与安全重试验证

实现范围：

- Room 升级到 v16，新增 `workflow_step_definitions`、`workflow_runs.retryOfWorkflowRunId`，以及 Workflow Step 的定义 ID、幂等键、输入/输出快照和复用来源字段。
- Workflow 支持 1 至 8 个顺序 Agent 步骤；设置页可创建、编辑、增删和排序步骤，活动 Run 存在时 Repository 拒绝编辑。历史 Run 使用创建时物化的步骤快照，不受后续定义变化影响。
- 前台和 WorkManager 后台均逐步创建独立 Agent Run，后续步骤只接收已落库的连续成功前缀输出；每一步继续使用现有 Schema、权限、审批和后置验证链路。
- `BLOCKED / FAILED / CANCELLED` Run 可创建新 Run 重试。连续成功前缀在新 Run 中标记为 `SKIPPED` 并保留 `reusedFromStepId`，首个未完成步骤及后续步骤重新执行；旧 Run 保持不变。
- 进程重建时若当前 Agent 已完成但后续步骤尚未启动，先保存当前输出，再把旧 Workflow Run 收敛为失败；重试可复用该完成前缀。恢复审批完成当前步骤后，继续同一 Workflow Run 的后续步骤，不留下永久 `RUNNING` 状态。

本地自动化验证：

- `WorkflowDefinitionPolicyTest`、`WorkflowStepExecutionPolicyTest` 和 `ScheduledWorkflowOrchestratorTest` 覆盖步骤数量、顺序门禁、输入快照、前序输出提示词、重试资格/二次确认和后台多步骤执行。
- `RoomWorkflowRepositoryInstrumentedTest` 源码覆盖定义物化、编辑冻结历史 Run、活动 Run 拒绝编辑、完成前缀复用、来源 Run 不变、恢复终态聚合和进程重建后的失败收敛。
- `XiaoLingDatabaseMigrationInstrumentedTest` 源码覆盖 v4/v9/v12/v13/v14→v16、v15→v16 和全新 v16 建库；`app/schemas/.../16.json` 已生成并通过 JSON 结构检查。
- 为保护真机 Android Keystore 中的 Provider API Key，本轮未执行 instrumentation；AndroidTest 源码编译和 APK 组装均通过。

执行命令：

```zsh
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug assembleDebugAndroidTest
```

结果：

```text
164 JVM tests passed
lintDebug passed
assembleDebug passed
assembleDebugAndroidTest passed
BUILD SUCCESSFUL
```

真机覆盖安装与 UI 验收：

- 使用 `adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装成功，未卸载、未清数据；Provider、会话、历史 Workflow 和 Keystore 配置保持存在。
- 应用启动后 PID 存活，`com.longdev.xiaoling/.MainActivity` 为前台 Activity，crash buffer 为空。
- 只读确认 `PRAGMA user_version=16`，`workflow_step_definitions` 表存在；v15 的两个历史 Workflow 均回填单步骤定义，旧 Run 状态和结果仍可展开查看。
- 通过 UI tree 派生坐标进入「设置 -> 工作流」：编辑弹窗可增加第二步，步骤上移/下移/删除状态正确，空步骤时保存按钮禁用；取消后没有修改原定义。
- 展开历史卡片可见步骤定义、Run ID、目标/错误快照和重试入口。点击已启动过的 blocked Run 后显示二次确认，明确“新 Run 保留来源 ID、旧 Run 不修改、写工具重新审批”；取消后数据库中 `retryOfWorkflowRunId IS NOT NULL` 的记录数仍为 0。
- 本轮没有确认真实重试，也没有创建或运行多步骤模型任务；前台/后台顺序执行、成功前缀复用和审批恢复继续下一步骤仍以 JVM 测试、已编译 instrumentation 源码和 Room v16 结构验证为依据。

Debug APK SHA-256：`4bf39cd1d69bc03c120ecb0f22cc0339bf8ac9d70d8ec7f3dec126178319cdaa`。

## 2026-07-19 多步骤 Workflow 完整真机验收

环境与自动化回归：

- 设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro，Android 14。
- `testDebugUnitTest`：165 条 JVM 测试通过。
- `connectedDebugAndroidTest`：38 条真机 instrumentation 全部通过；同时通过 `lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest`。
- 本轮修复了旧单步骤 Workflow 兼容入口重复关联同一 Agent Run 时的幂等问题，并让 `RunEventMetadata.ToolResult.memoryIdsUsed` 在 Android `org.json` 中显式编码为 `JSONArray`，读取端兼容早期字符串化数组。

前台失败与安全重试：

- 来源 Run `workflow-run-984dcb6f-b10f-4e88-90b4-73cd04fd573d` 使用不兼容响应失败，最终保持 `FAILED`，后续两个步骤保持 `CANCELLED`。
- 重试 Run `workflow-run-85981ac4-a060-44ca-8f3b-619336a4d455` 的 `retryOfWorkflowRunId` 正确指向来源 Run，三个步骤分别创建独立 Agent Run 并全部 `COMPLETED`。
- 第 1 步调用 `app.current_time`，第 2 步调用 `app.list_conversations`，第 3 步再次调用 `app.current_time`；第 2、3 步输入快照逐项包含连续成功前缀输出。
- 完成后把未来定义第 1 步改为带 `FUTURE_EDIT` 标记，已完成 Run 的 `detail`、`inputSnapshot` 和 `outputSnapshot` 均保持原值，确认历史快照冻结成立。

真实后台 WorkManager：

- 一次性任务 `scheduled-task-d72e18e6-e02d-4aa2-b3dd-957d6e739f74` 在应用位于桌面后台时由系统调度启动。
- Workflow Run `workflow-run-38a1b2a2-b445-4b1b-b00a-0498ac3cec47` 于 `02:06:11` 开始、`02:06:42` 完成；三个 SAFE 步骤及其独立 Agent Run 全部 `COMPLETED`，总耗时约 31 秒。
- 当前耗时没有形成引入 Foreground Service 的证据；继续使用普通 WorkManager，后续只有在长任务或持续可见停止入口成为真实需求时再评估。

审批后继续下一步骤：

- Workflow Run `workflow-run-992e234c-baeb-4259-9481-55a59168e2b0` 的第 1 步选择 `memory.remember` 并进入待审批，第 2 步保持 `PENDING`。
- Approval `approval-fcbd9d77-4e5e-4c10-bd19-a4f57fd9b852` 批准后，第 1 步完成，系统自动创建第 2 个 Agent Run 执行 `app.current_time`，最终两个步骤和 Workflow Run 均为 `COMPLETED`。
- 临时记忆 `QA_APPROVAL_RESUME_20260719` 已通过长期记忆页面删除，数据库确认无残留。

instrumentation 后可用性恢复：

- 测试完成后重新安装最新 Debug APK，从未跟踪的本机配置恢复 Base URL 和 API Key；文档、日志和提交均未包含凭据。
- 上游模型列表成功返回 6 项，配置指定模型完成真实普通对话请求，应用显示精确回复 `OK`。

## 2026-07-19 Agent Run 指标与网络故障注入验证

实现范围：

- 新增 `AgentRunMetricsPolicy`，从持久化 `AgentRunDetailRecord` 计算单 Run 耗时、模型调用、工具调用和审批次数；历史成功率与平均耗时只使用终态 Run，活动 Run 不进入质量分母。
- 任务中心在当前筛选范围展示 Run 数、成功率、平均耗时、非成功数、模型调用和工具调用；列表卡与展开详情展示同一口径的单 Run 指标。
- `OpenAiCompatibleClientTest` 使用 MockWebServer 在 HTTP 200 响应体中途主动断开，确认明确的流中断从 `UNKNOWN` 改为 `CONNECTION`；分类器测试同时确认非法 HTTP 协议仍为 `RESPONSE`，无法识别的 I/O 仍为 `UNKNOWN`。
- 现有确定性测试继续覆盖用户取消、模型步骤超时、整次 Run 超时、工具执行超时，以及同一 Agent Run 重复回调时的 Workflow 幂等关联。

自动化结果：

- `testDebugUnitTest`：175 条 JVM 测试通过。
- `lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 38 条，合计 76 条全部通过。
- 双轴复核确认共享终态判断和 LLM 步骤类型已贯通 Runtime、Repository 与指标消费端；网络分类边界收窄后，`CONNECTION / RESPONSE / UNKNOWN` 均有确定性测试。复核修复后重新执行上述完整构建和 instrumentation，结果保持通过。

真实模型与 UI：

- instrumentation 后在 Redmi Note 8 Pro 重新安装最新 Debug APK，并从未跟踪的本机配置恢复 Provider；凭据未进入文档、日志或提交。
- `gpt-5.4-mini` 真实执行 `/agent Read current time and return verified result`，Run `run-8880e351-0c00-4a91-b6cb-06a48f2e0410` 调用 `app.current_time` 并进入 `COMPLETED`。
- 任务中心实测显示 `1 个 Run · 成功率 100% · 平均 13.28s` 和 `终态 1 · 非成功 0 · 模型 3 · 工具 1`；单 Run 卡片与详情均显示 `耗时 13.28s · 模型 3 · 工具 1 · 审批 0`。
- 1080×2340 UI tree 与截图确认筛选栏、汇总带、Run 卡和展开详情没有文字或控件重叠；`com.longdev.xiaoling/.MainActivity` 保持前台，crash buffer 为空，后续仍需在更长历史列表上持续观察汇总性能。
- 最终 Debug APK SHA-256：`c167e36f02f7a7a26d4b8f245857e2f9f59a7160cac484df8d48d9278bc6f6b1`。

## 2026-07-19 Agent 请求遥测与失败分布验证

实现与口径：

- `ModelResponseResult` 记录最终 JSON 请求体 UTF-8 字节数、总耗时、首个响应 body 字节实际可读时的 TTFB，以及上游明确返回的 Token usage；Chat Completions 和 Responses 字段名统一映射，缺失 usage 保持空值。
- `OpenAiAgentLlm` 把规划和总结请求遥测交给 Runtime，Runtime 以 `llm.request.completed` typed metadata 持久化阶段、模型、耗时、TTFB、Prompt 字节和 Token。规划 JSON 或工具语义解析失败时，已经返回的遥测先落库，随后 Run 再收敛为 `FAILED`。
- 任务中心的汇总带、Run 卡和详情区使用同一指标策略展示模型总耗时、平均 TTFB、Prompt 字节、Token 总量与 usage 覆盖率；失败分布只统计 `FAILED / CANCELLED / BUDGET_EXHAUSTED / BLOCKED` 终态，活动 Run 不进入分母。

自动化结果：

- TDD 覆盖网络请求体字节、TTFB、Chat usage 解析、typed metadata 往返、规划解析失败仍保留遥测、Run/历史聚合、Token 覆盖率、失败分布和 UI 呈现；原始 `ServerSocket` 测试先发送响应头、延迟 200ms 再发送 body，确认 TTFB 必须包含 body 延迟。
- `testDebugUnitTest`：182 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 38 条，合计 76 条全部通过。
- Standards/Spec 双轴审查指出并验证修复两项问题：TTFB 不能在响应头返回时记录；规划语义解析失败不能丢失上游已返回 usage。修复后完整构建与两设备 instrumentation 重新通过。

真实模型与 UI：

- instrumentation 后重新安装最新 Debug APK，从未跟踪的本机配置恢复 Provider 并成功获取 6 个模型；凭据未进入文档、日志或提交。
- `gpt-5.4-mini` 真实执行 `/agent Read current time and return telemetry result`，Run `run-1c833310-12c8-4dda-ad8e-dc2c7915475b` 调用 `app.current_time` 并进入 `COMPLETED`。
- Room 回读确认 3 条 `llm.request.completed`：模型总耗时 `8274ms`、平均 TTFB `2755ms`、Prompt `4066B`、输入 Token `805`、输出 Token `215`、总 Token `1020`。
- TTFB 文案和单调时钟修复包覆盖安装后，真实执行 `/agent Read current time with final TTFB`；Run `run-4dd3b467-7825-4b3f-8ef4-d7f66dbb1405` 进入 `COMPLETED`，3 条请求事件共计模型耗时 `10735ms`、平均 TTFB `3576ms`、Prompt `4042B`、总 Token `1171`。
- 任务中心最终实测显示 `2 个 Run · 成功率 100% · 平均 11.06s`、`模型耗时 19.01s · TTFB 3.17s · Prompt 7.9KB · Token 2191（6/6）` 和 `失败分布 无`；汇总、Run 卡、详情及 typed Event 字段一致。
- 1080×2340 UI tree 与截图确认新增四行汇总和两行 Run 指标没有重叠；`com.longdev.xiaoling/.MainActivity` 保持前台，crash buffer 为空。
- 最终 Debug APK SHA-256：`cbbf2d16ae64a2f46b8ea901a3478a24ad7e00fbc32bbe6298389fb979a34976`。

## 2026-07-19 执行中断与 Android 权限撤销验证

实现与恢复决策：

- Runtime 在参数校验、审批结束后执行前、工具返回后验证前三个检查点读取 Android 权限。审批期间撤权时不创建 `tool.execute` 且不调用 Executor；工具执行期间撤权时保留成功 `tool.result`，执行步骤为 `COMPLETED`，验证步骤和 Run 为 `FAILED`，重试要求二次确认。
- 启动恢复会把不可原地恢复的旧 Run 收敛为 `CANCELLED`，并把该 Run 下所有 `PENDING/RUNNING` Step 同步收敛为 `CANCELLED`；`run.recovered` 保留中断前状态。新 Run 通过 `retryOfRunId` 关联来源，旧 Run、Step 和事件保持不变。
- 本轮中断恢复决策时还没有持久化执行回执或幂等副作用证明，因此 `EXECUTING/VERIFYING` 不原地恢复旧执行栈。后续阶段虽已建立回执 contract，但生产工具仍没有幂等键；Foreground Service 只提高存活概率，不改变此安全边界。

自动化与系统故障注入：

- `testDebugUnitTest`：184 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- `connectedDebugAndroidTest` 在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 39 条，合计 78 条全部通过。
- 定向 Room instrumentation 覆盖 `EXECUTING/VERIFYING` 进程重建：旧 Run 和活动 Step 均进入 `CANCELLED`，重试策略返回 `requiresConfirmation=true`，创建关联新 Run 后来源快照不变。
- 模拟器先授予 CAMERA，再用系统 `pm revoke` 从应用外部撤权；应用 PID `9479` 随即消失，确认系统会终止目标进程。权限状态回读为未授权，随后应用冷启动成功，crash buffer 为空。
- 未保留调用 `UiAutomation.revokeRuntimePermission` 的自撤权 instrumentation：系统终止测试目标进程后测试框架只能报告 `shortMsg=Process crashed`，无法继续断言应用恢复状态；确定性 Runtime 测试与外部 `pm revoke` 分别覆盖业务边界和真实系统行为。

真实模型与任务中心：

- instrumentation 后覆盖安装最新 Debug APK 并恢复未跟踪的本机 Provider，成功获取 6 个模型；凭据未进入文档、日志或提交。
- 首个 Chat Completions Run `run-d97266b5-8b0b-4f54-997b-4488ef628f1b` 因上游响应不含 `choices[0].message.content` 进入 `FAILED`，只创建 1 个失败的 `llm.plan` Step，工具未执行。旧 Run 保持原样，并在「可重试」筛选中显示重试入口。
- 切换到 Responses API 与 `gpt-5.5` 后，新 Run `run-270704e9-048d-42a5-a8fc-d8a9518a01f7` 执行 `/agent Read current time after fault boundary retry`，依次完成规划、参数校验、`app.current_time` 执行、验证、完成判断和总结共 6 步，最终为 `COMPLETED`。
- 成功 Run 的 3 条模型请求共计 `9915ms`、Prompt `4061B`、总 Token `942`，工具结果为 `当前时间：2026-07-19 04:11:28 · 时区：Asia/Shanghai`。任务中心实测显示 `2 个 Run · 成功率 50% · 平均 7.60s`、`模型耗时 9.92s · TTFB 3.30s · Prompt 4.0KB · Token 942（3/4）` 和 `失败分布 失败 1`；可重试筛选单独显示旧失败 Run。
- `com.longdev.xiaoling/.MainActivity` 保持前台，最终 crash buffer 为空。Debug APK SHA-256：`8c94c9ff037e19076f6cf477304b72fe1cb94e303f688149997e1833fa35d357`。

## 2026-07-19 执行回执与幂等证据 contract 验证

实现边界：

- `ToolExecutionReceipt` 记录 ToolCall ID、业务 operation ID、可选幂等键和 `COMMITTED / NOT_COMMITTED / UNKNOWN` 状态，并把执行时 `ToolReplaySafety` 声明快照嵌入 `tool.result` typed metadata；旧事件没有回执时继续按 `null` 解码，没有重放快照时默认 `RESTART_REQUIRED`。
- Runtime 在写入成功 `tool.result` 前校验回执必须属于当前 ToolCall，错配回执使执行步骤和 Run fail-closed，且不会落库为成功结果。
- `ToolExecutionRecoveryEvidencePolicy` 只有在执行时快照和当前定义都声明 `IDEMPOTENT_BY_KEY`、结果成功、ToolCall 身份一致、回执为 `COMMITTED` 且幂等键存在时，才判定已提交副作用可复用；应用升级后的当前定义不能放宽历史证据。该判定不等于恢复旧协程，也尚未接入 `AgentRunResumePolicy`。
- `notes.create / memory.remember` 使用真实 note/memory ID 记录 `COMMITTED` 回执；回读失败仍保留 operation ID。当前存储层没有按 ToolCall 去重，幂等键为 `null`，两项工具继续保持默认 `RESTART_REQUIRED`。
- 任务中心 typed Event 显示调用 ID、operation ID、回执状态及“幂等证明已记录/未记录”，不展示原始幂等键。

TDD 与自动化结果：

- Red/Green 覆盖回执 JSON 往返、旧事件兼容、回执跨 ToolCall 错配拒绝、幂等工具完整证据判定、两类真实写工具 operation ID、任务中心脱敏呈现，以及 Runtime 在成功事件落库前拒绝错配回执。
- `testDebugUnitTest`：190 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过。
- 新增 Room instrumentation 验证 Android `org.json` 与 Room 快照能完整往返嵌套执行回执。最终代码在 Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 40 条，合计 80 条全部通过。
- 最终 APK 覆盖安装后，真机 Provider 仍为 1 条，`com.longdev.xiaoling/.MainActivity` 为前台 Activity，crash buffer 为空；未创建临时真实笔记或记忆，避免为可由确定性测试覆盖的 contract 验收污染用户数据。
- Debug APK SHA-256：`7a2ea0e569715f11b5bd0848727b4598c286190c9f4a143dac670d3d66491b26`。

## 2026-07-19 `notes.create` 存储层幂等验证

实现与恢复边界：

- `AgentNoteStore.create` 强制接收幂等键，`notes.create` 使用当前 `ToolCall.id`；`agent_notes.idempotencyKey` 由 Room v17 可空唯一索引约束。
- 同键同标题/正文在 Room 数据库重开后仍返回原笔记和同一 operation ID；同键不同载荷抛出幂等冲突，不覆盖也不新建记录。
- `notes.create` 的执行回执现在包含 ToolCall 幂等键并声明 `IDEMPOTENT_BY_KEY`；`memory.remember` 仍保持 `RESTART_REQUIRED`。
- 本阶段不接入 `AgentRunResumePolicy`；完整幂等证据只证明已提交副作用可识别，不代表通用旧协程、验证栈或其他工具可原地恢复。
- v16→v17 迁移保留旧笔记内容并把其幂等键保持为 `NULL`；新 v17 Schema 已导出。

自动化结果：

- `testDebugUnitTest`：192 条 JVM 测试通过；`lintDebug`、`assembleDebug` 和 `assembleDebugAndroidTest` 通过，完整 Gradle 构建成功。
- `XiaoLingToolRegistryTest` 覆盖重复 ToolCall 返回同一 operation ID、载荷漂移拒绝，以及 `notes.create / memory.remember` 的重放声明边界。
- Pixel_9 Android 15 模拟器和 Redmi Note 8 Pro Android 14 真机各执行 42 条 instrumentation，合计 84 条全部通过。其中迁移类 12 条，新增 Room 笔记幂等定向测试 1 条。

最终设备状态：

- instrumentation 后重新覆盖安装最新 Debug APK，Pixel_9 和 Redmi 均冷启动成功，`com.longdev.xiaoling/.MainActivity` 为前台 Activity。
- Redmi 只读回读确认 `PRAGMA user_version=17`、Provider 仍为 1 条、`index_agent_notes_idempotencyKey` 存在；最终 crash buffer 为空。
- Debug APK SHA-256：`528e7e79f0b973420a1c5b9180f7bc46f9143ca0b6e15c6969541b979a657036`。

## 2026-07-19 `notes.create` 验证阶段恢复

实现边界：

- `AgentRunResumePolicy` 新增 `COMMITTED_TOOL_VERIFICATION`。只有 `EXECUTING / VERIFYING` Run 的工具执行 Step 与 ToolResult 一一对应、最后一个结果尚无 `tool.verify`、ToolCall 可唯一还原，且历史快照与当前定义均为 `IDEMPOTENT_BY_KEY`、回执为 `COMMITTED` 并带完整幂等键时，才允许原 Run 恢复。
- `ToolRegistry.verifyCommittedEffect()` 是只读恢复入口；默认返回不支持。生产实现仅为 `notes.create` 按 receipt `operationId` 回读笔记，并核对 ToolCall ID、幂等键、状态、标题和正文，不调用 `create()`，不会产生第二条笔记。
- Runtime 补齐原 execution Step 后只执行权限复检、operation 回读和后置验证，写入 `tool.verify`；随后使用本地可信事实写入 `recovery.summarize` 和 `run.recovery_summary`，不调用模型，也不恢复旧规划协程。
- 若该 Agent Run 属于多步骤 Workflow，只保存当前步骤的恢复输出并把剩余 Workflow 收敛为 `FAILED`；后续仍创建关联新 Run 并复用成功前缀。其他工具、证据不完整的旧事件和通用执行栈继续 fail-closed。
- 双轴审查修复了两项启动一致性问题：多工具 Run 从前序 `tool.result + tool.verify` 重建按顺序排列的可信事实；Workflow 启动对账显式跳过已筛选的恢复候选，避免在 operation 回读前提前把当前步骤判为失败。

TDD、自动化与真实恢复：

- Red/Green 覆盖完整证据判定、Runtime 不调用 `execute()` 的只读恢复、真实 Registry 按 operation ID 回读且不新增笔记、`tool.result` 落库后的确定性进程终止、Room Repository 重建保留候选，以及 `run.recovery_summary` typed metadata 往返。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；198 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机各执行 44 条 instrumentation，合计 88 条全部通过。
- Redmi 真实强停/冷启动验收从 `EXECUTING + RUNNING tool.execute + COMMITTED tool.result` 恢复为 `COMPLETED`；原 Run 新增 `tool.verify` 和 `recovery.summarize`，`tool.result` 仍只有 1 条，同幂等键笔记仍只有 1 条，并生成可信 `AGENT_RESULT`。临时 Run、Step、Event、Note、Conversation 和 Message 均已清理。

最终设备状态：

- instrumentation 后向 Pixel_9 与 Redmi 重新覆盖安装最终 Debug APK，两台设备均冷启动到 `com.longdev.xiaoling/.MainActivity`，crash buffer 为空。
- Redmi 回读 `PRAGMA user_version=17`、Provider 为 1 条、临时笔记为 0 条；使用未跟踪的本机兜底配置重新获取 6 个模型并选择 `gpt-5.4-mini`。
- 真实普通对话冒烟请求返回 HTTP 200 和 `OK`；请求使用设置项中的默认 User-Agent，Authorization 日志保持脱敏。
- Debug APK SHA-256：`aaf9cdeb087b0349d72ec4777526b26355ed06401a098874a5c53e373c6e2852`。

## 2026-07-19 `memory.remember` ToolCall 级存储幂等验证

实现与安全边界：

- Room v18 新增 `agent_memory_operations`：`idempotencyKey` 为主键，映射 memory ID、原始请求载荷 SHA-256 和创建时间。操作映射与正式记忆/FTS 写入位于同一事务；旧记忆迁移后不伪造 ToolCall 来源。
- `memory.remember` 使用 ToolCall ID 调用 Store，同键同载荷在数据库重开后返回原 memory operation，同键载荷漂移在写入前抛出冲突。独立 operation ledger 可承受记忆后续编辑和语义去重；目标被删除时明确失败，不重新创建第二条记忆。
- 工具回执现在包含 ToolCall 幂等键并声明 `IDEMPOTENT_BY_KEY`。`ToolRegistry.supportsCommittedEffectVerification()` 独立表达只读恢复能力，生产 Registry 仍只对白名单 `notes.create` 开放；`memory.remember` 继续 `RESTART_REQUIRED`。

TDD 与自动化：

- Red/Green 覆盖 Registry 回执、同 ToolCall 重放、载荷漂移拒绝、数据库重开、v17→v18 迁移、旧记忆保留，以及“有幂等证据但无只读验证能力”仍不得进入原 Run 恢复。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；200 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机各执行 46 条 instrumentation，合计 92 条全部通过。
- instrumentation 后向两台设备覆盖安装最终 Debug APK；Redmi 回读 `PRAGMA user_version=18`、Provider 为 1 条、临时笔记/记忆/记忆 operation 均为 0，重新同步 6 个模型并选择 `gpt-5.4-mini`。真实普通对话请求返回 HTTP 200 和 `OK`，默认 User-Agent 正确且 Authorization 日志保持脱敏。
- 最终 Debug APK SHA-256：`b989507989b101796a4fb79e4a4daaa70fa9bebc9f0e608b9dc22da071a025ab`。

## 2026-07-19 `memory.remember` 验证阶段恢复

实现与安全边界：

- Room v19 为 `agent_memory_operations` 增加可空 `resultHash`。新 operation 原子保存内容、标签、类型、来源和置信度的提交结果快照哈希；v18→v19 只新增空列，不为历史 operation 伪造结果证据。
- `AgentMemoryStore.verifyRememberedOperation()` 按 ToolCall 幂等键、回执 memory ID、原请求载荷和当前记录做只读验证。未修改、启用且未过期时成功；业务字段编辑、禁用、过期、删除分别返回 `MEMORY_CHANGED`、`MEMORY_DISABLED`、`MEMORY_EXPIRED`、`MEMORY_NOT_FOUND`，缺少 v19 结果快照返回 `EVIDENCE_INCOMPLETE`。
- 置顶、引用时间和尚未到期的未来过期时间不属于提交业务快照，不阻止恢复；删除后使用原撤销快照恢复全部业务字段时可再次验证成功。
- `XiaoLingToolRegistry` 为 `memory.remember` 开放受限 `verifyCommittedEffect()`，从持久化 Run Context 重建原来源请求；恢复不调用 `remember()`，原 operation ID、幂等键和执行回执保持不变。Runtime 仍只恢复最后一项已提交结果的后置验证和本地总结，不恢复旧模型协程、通用执行栈或 Workflow 后续步骤。

TDD、自动化与真机可用性：

- Registry Red/Green 证明只读恢复调用验证接口且 `remember()` 总调用数保持为 1；Room 测试覆盖数据库重开、载荷漂移、内容/标签/类型/来源/置信度字段矩阵、置顶/引用时间/未来过期时间例外、删除撤销、v18 缺证据 fail-closed，以及 `tool.result` 落库后关闭并重开磁盘 Room 的组件重建恢复。
- 完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；201 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机分别执行完整 54 条 instrumentation，合计 108 条全部通过。
- instrumentation 后覆盖安装最终 Debug APK，Redmi 冷启动到 `com.longdev.xiaoling/.MainActivity`；原 Provider 与 `gpt-5.4-mini` 仍可用。最终真实普通对话 `Reply only OK final recovery smoke` 在 4.83 秒返回 HTTP 200 和 `OK`，默认 User-Agent 正确，Authorization 日志保持脱敏，crash buffer 为空。
- 以 `08a4002` 为固定点的 Standards/Spec 双轴审查均已完成；修复注释理由、重复成功文案、文档残留、完整字段矩阵和磁盘 Room 冷启动覆盖后，两轴最终均为 0 项 finding。
- 最终 Debug APK SHA-256：`c3b8c5cee6d7a7fcf9ad00428247611980526eb621deb5ace01c7edbfb3468e9`。

## 2026-07-19 `memory.remember` 恢复失败产品呈现

实现与边界：

- `ToolRecoveryFailure` 统一携带稳定错误码、用户可读原因和建议动作；`XiaoLingToolRegistry` 覆盖 `OPERATION_NOT_FOUND / EVIDENCE_INCOMPLETE / PAYLOAD_MISMATCH / OPERATION_MISMATCH / MEMORY_NOT_FOUND / MEMORY_CHANGED / MEMORY_DISABLED / MEMORY_EXPIRED` 八类只读恢复失败。
- Runtime 只把结构化恢复失败写成 `run.recovery_failed + RunEventMetadata.RecoveryFailure`；普通恢复异常仍写 `run.failed`。失败 Run 和活动 Step 收敛为 `FAILED`，不会继续旧 Run、旧模型协程或 Workflow 后续步骤。
- 任务中心详情顶部显示最新恢复处理状态带，事件列表继续展示工具名、错误码、原因和建议。所有建议均明确要求修复记忆状态后创建新 Run，旧 Run 保持不变。
- 生产 Registry 当前没有第三个适合推广“提交快照 + 只读 probe”的写工具，因此本阶段没有虚构工具或放宽恢复白名单；下一阶段转向独立 ToolCall/ToolResult Room Ledger。

自动化与双机验收：

- JVM 测试覆盖结构化失败从 Registry 到 Runtime、JSON 往返、旧事件兼容、八类建议映射和任务中心呈现；Room instrumentation 覆盖 typed event 持久化往返。完整命令 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace --console=plain` 通过；205 条 JVM 测试通过，lint、Debug APK 和 AndroidTest APK 均构建成功。
- Pixel_9 Android 15 模拟器与 Redmi Note 8 Pro Android 14 真机分别执行完整 55 条 instrumentation，合计 110 条全部通过。
- Redmi 临时构造 `MEMORY_DISABLED` 失败 Run，任务中心顶部状态带与事件字段均可见；UI tree 和截图确认无截断、重叠或横向溢出。临时 Run、Step、Event 已从生产库清理，查询结果为 0；应用最终 force-stop，Provider 数据未修改。
- 最终 Debug APK SHA-256：`7e62e115fcd7ca1ff2016dad0feac8234f068e9d7a9170384549b9f79c98bf63`。
