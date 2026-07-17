# 验证报告

验证日期：2026-07-17（北京时间）

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
- v4 Schema 从固定历史提交 `425717b` 的数据库实体生成，v6 保存 RunEvent metadata 改造前结构，v7 由当前源码生成。
- 历史中数据库版本曾直接从 v4 跳到 v6，因此没有可复现的独立 v5 源码快照；自动化测试仍按正式 `MIGRATION_4_5`、`MIGRATION_5_6`、`MIGRATION_6_7` 顺序执行完整链路。

单测试命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.longdev.xiaoling.data.XiaoLingDatabaseMigrationInstrumentedTest
```

结果：

```text
Starting 3 tests on Redmi Note 8 Pro - 14
Finished 3 tests on Redmi Note 8 Pro - 14
BUILD SUCCESSFUL
```

已验证：

- 带真实旧数据的 v4 数据库通过正式 migrations 升级到 v7，Room 最终 Schema 校验通过。
- Provider、会话、用户/assistant 消息、Agent Run、Step、审批、Run Event、笔记和长期记忆均可通过 DAO 回读。
- v4 旧消息迁移后 `origin=LEGACY`，`verifiedAgentContext=null`。
- v6 中保存在 `RunEvent.message` 的旧 JSON object 会迁入 `metadataJson`，原 message 改为事件可读摘要；普通文本 message 不会被误标为结构化 metadata。
- 全新 v7 内存数据库可以创建、打开并执行 DAO 查询。
- Room 2.8.4 需要 kotlinx serialization 1.8.1；工程已统一该现有传递依赖版本，避免 KSP Schema 导出和 `room-testing` 运行时接口不一致。

完整回归命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug
```

结果：

```text
Starting 7 tests on Redmi Note 8 Pro - 14
Finished 7 tests on Redmi Note 8 Pro - 14
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
- Room v7 使用独立 `metadataJson` 列，metadata 可以通过 Repository round-trip，v6 旧 JSON event 可迁移且特殊字符不丢失。
- 运行记录 UI 直接读取 typed metadata，不再解析数据库 JSON；纯文本历史事件继续回退显示原文。
- Responses `input` 可同时包含消息、`function_call` 和 `function_call_output`，调用与结果通过相同 `call_id` 关联。

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
