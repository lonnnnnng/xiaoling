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
99 JVM tests passed
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
- 删除返回完整快照并立即移除主表与 FTS；撤销后在同一事务中恢复主表、来源字段和 FTS。
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

外部服务边界：

- 本机兜底 Provider 的模型列表与鉴权已验证成功；按本机指令选择 `gpt-5.5` 后，真实对话请求到达服务端但返回 `HTTP 503 · 无可用账号`。同端点小范围候选探测也返回 503/429/403，因此当前未取得真实回复成功证据，该结果不归因于应用实现。

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

- 真机上保留原有同包名安装包，未卸载、未清数据。
- `outputs/` 目录不纳入版本控制。
