# 小灵

<p align="center">
  <strong>Android 端、本地优先、可审计、可恢复的个人 Agent</strong>
</p>

<p align="center">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Room v36" src="https://img.shields.io/badge/Room-v36-4285F4">
  <img alt="Release v0.1.18" src="https://img.shields.io/badge/Release-v0.1.18-2E7D32">
</p>

小灵不是一个只会生成文字的聊天客户端，也不是默认拥有全部权限的自动化脚本。它把自然语言目标转换成受控工具调用，在 Android 本地保存计划、审批、执行与验证证据，并让用户始终掌握最终控制权。

## 下载

- [GitHub Release v0.1.18](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.18)
- 安装包：`xiaoling-v0.1.18.apk`
- 完整性校验：`xiaoling-v0.1.18.apk.sha256`
- 系统要求：Android 8.0（API 26）及以上

> 安装前请核对 Release 页面中的 SHA-256。Android 可能提示允许从当前来源安装应用，需要由用户在系统设置中显式授权。

## 它能做什么

| 能力 | 当前实现 |
| --- | --- |
| 模型接入 | 用户自配 OpenAI-compatible Provider；支持 Chat Completions、Responses 与 SSE 流式响应 |
| 对话 | 多会话本地保存、Markdown、图片与文档附件、系统分享入口、语音草稿输入 |
| Agent Run | 自然语言目标生成 1 至 8 步计划；确认后才创建 Run；全过程写入本地审计账本 |
| 个人信息 | 本地笔记、长期记忆、知识库、任务、日历、联系人及当前通知的受控访问 |
| 知识引用 | 回答可携带文档版本、分块与偏移身份，并跳转当前权威原文；内容漂移时拒绝猜测 |
| Workflow | 前台手动 Workflow、一次性与非精确定时任务、WorkManager 执行、通知结果导航 |
| 设备 Agent | 前台观察与受控动作：`snapshot`、`open_app`、`back`、`home`、`tap_ref`、`type_text`、`swipe` |
| 结果验证 | 以持久化 Tool Ledger 和操作后观察给出 `VERIFIED / PARTIAL / INCOMPLETE`，模型总结不能升级事实结论 |

## 一条任务如何完成

```text
自然语言目标
    ↓
显式 Agent 意图与最小 Profile
    ↓
生成计划并由用户确认
    ↓
逐步执行、按风险审批
    ↓
操作后重新观察与强类型验证
    ↓
持久化审计证据与答案级事实入口
```

关键写入只有在执行器结果、typed verification 和当前数据回读一致时，才会形成 `COMMITTED` 回执。失败、中断或证据不足不会被润色成成功；无法确认提交状态的旧 Run 默认保留不变，并通过关联新 Run 重新开始。

## 安全边界

- 工具必须同时通过应用注册表、当前 Profile、Skill 白名单、运行模式和系统权限检查。
- 写入、打开应用、点击与文本输入等动作按风险显式审批；`back / home / swipe` 虽为 SAFE，仍要求新鲜观察和操作后验证。
- API Key 使用 Android Keystore 保护；敏感输入不在审计记录中保存原文，只保留最小指纹与长度。
- Accessibility 独立授权、独立开关，设备节点引用短生命周期化，并在进入模型前进行隐私过滤。
- 不提供任意 Shell、Root、ADB、隐藏系统 API，也不执行未经确认的支付、下单、删除、发消息或系统设置修改。
- 设备动作目前只承诺少量已验收应用与前台场景，不承诺任意 App；后台或定时设备动作、坐标/截图兜底仍关闭。
- 账号与云同步、开放 Skill 市场、远程代码安装、完整 MCP、多 Agent 和端侧模型管理尚未开放。

## 快速开始

1. 安装并打开小灵。
2. 进入“设置 → 模型提供方”，添加 Provider，填写 Base URL 与 API Key。
3. 同步模型列表，启用需要的模型。
4. 返回对话页，选择模型与 API 模式后开始普通对话。
5. 需要执行工具时，使用 Agent 任务入口或显式 `/agent` 意图，检查计划后再确认执行。

系统分享的文本、图片和文档只会进入可编辑草稿，不会自动发送、调用模型或创建 Agent Run。

## 本地构建

环境要求：macOS、JDK 21、Android SDK，以及项目所需的 API 36 构建工具。

```zsh
# Debug 与 JVM 测试
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug

# Release（需要本地签名配置）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleRelease
```

产物位置：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

Release 签名从未跟踪文件 `local-signing/xiaoling-release.env` 读取。请勿把签名文件、密码、API Key 或设备端 Provider 配置提交到仓库。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 正式版本 | `v0.1.18`（`versionCode 19`） |
| 数据库 | Room v36 |
| 开发里程碑 | 第 266 阶段设备观察恢复、模型失败处置、审批取消及排队进程对账完成；长任务切片继续 |
| JVM 历史基线（2026-08-13） | `1118 / 1118` 通过 |
| Lint 历史基线（2026-08-13） | 通过 |
| Redmi 全量历史基线（2026-08-13） | `424 tests / 363 passed / 61 skipped / 0 failed / 0 errors` |
| 验收设备 | Redmi `begonia` 真机；不使用模拟器 |

第 263 阶段发布了 `v0.1.18`：将 Android 12+ 系统 Splash 图标改为透明占位、背景与应用窗口同色，并启用 Release 资源收缩；`assembleRelease`、签名、`zipalign` 和 SHA-256 资产校验均已完成。本次发布按用户要求没有重复运行 JVM、Lint、Debug/AndroidTest 或 Redmi instrumentation。第 262 阶段已在 Redmi 完成“当前通知 → 用户选择保存为笔记 → 可见审批 → 当前笔记详情”的真实竖屏闭环，最终 `OK (1 test)`（`57.861s`），旧 Run 保持不变。详细证据见 [路线图](docs/personal-agent-roadmap.md) 和 [验证报告](docs/verification-report.md)。

完整回归基线完成于 2026-08-13；它与后续第 260 至 264 阶段的聚焦 Redmi 证据分开记录。当前 Release 为 `v0.1.18`，发布资产、未执行的验证项和未覆盖边界均以验证报告为准。

第 264 阶段一次性提醒改期已完成：精确唯一任务、完整时间审批、当前计划指纹核对、系统入队与 Room 原子替换、答案级查看任务均已通过 Redmi 聚焦验收，旧 Run 保持不变。第 265 阶段已在 Redmi 真机完成指定系统计算器闭环：自然语言目标生成计划、用户确认、5 次逐动作审批、每步操作后重新观察与验证，最终读取 `56`；小灵恢复后显示 `7/7` 步骤完成，目标级结论为已验证。第 266 阶段已完成四个可靠性切片：设备观察证据过期/失效不执行动作并保留 typed `recovery.failed`；模型请求失败按错误类别和已完成工具事实写入 typed `retryDisposition`；审批恢复后取消旧 Run 时，迟到决定被拒绝且关联新 Run 使用独立空账本；排队 Agent/Workflow 在缺少可恢复执行栈时按既有 fail-closed 语义收敛，不补造 Run。旧 Run、审批与工具账本不被重放或覆盖，不随阶段自动发版；剩余长任务可靠性继续推进。

## 文档

- [文档索引](docs/README.md)：长期文档入口与当前项目基线
- [产品需求](docs/requirements.md)：目标、能力边界、隐私与非目标
- [个人 Agent 路线图](docs/personal-agent-roadmap.md)：阶段计划、完成度与后续优先级
- [实现说明](docs/implementation-notes.md)：架构、数据模型和关键实现约束
- [验证报告](docs/verification-report.md)：构建、真机测试与发布证据
- [参考项目分析](docs/reference-apps-analysis.md)：外部项目调研与取舍

## 项目原则

小灵优先保证一条完整个人 Agent 主链真实可用，再扩展更多工具与入口。每项能力都应具备明确意图、最小授权、可审计执行、操作后验证和当前权威事实回读；细节打磨不能取代主线闭环。
