# 小灵

Android 端、本地优先的个人 Agent。把自然语言目标转换成受控工具调用，在本地保存计划、审批与执行证据，并根据实际结果判断任务是否完成。

## 下载

[下载 v0.1.24](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.24) · Android 8.0 及以上

Release 提供 `xiaoling-v0.1.24.apk` 和同名 `.apk.sha256` 校验文件。本版更新与验收范围见[发布记录](docs/android-v0.1.24-release.md)。

## 主要能力

| 能力 | 说明 |
| --- | --- |
| 对话 | 自配 OpenAI-compatible 模型；支持 Chat Completions、Responses、流式输出、图片与文档附件 |
| 个人信息 | 本地笔记、记忆、知识库与任务，以及经授权的日历、联系人和通知访问 |
| Agent 与工作流 | 检查计划后执行；支持手动工作流、限定应用、逐步完成标准与非精确定时任务 |
| 设备操作 | 前台观察、打开应用、点击、输入和滚动；限定应用内可连续执行，并检查操作后的页面 |
| 浏览器与工作区 | 公开网页静态内容读取、确认后的链接导航，以及私有工作区文件和有界命令会话 |
| 扩展 | HTTP / SSE MCP、GitHub Skill、签名插件、受限只读子 Agent 与 GenUI 显式动作 |
| 审计与恢复 | 保存工具调用和验证结果；中断后检查持久事实，证据不足时保留失败 |

## 快速开始

1. 安装后进入“设置 → 模型提供方”，填写 Base URL 和 API Key，同步并启用模型。
2. 返回对话页选择模型，开始对话；需要执行工具时使用 Agent 任务入口或 `/agent`，检查计划后确认。
3. 在“设置 → 工作流”创建任务。设备步骤需确认限定应用；需要核验完成情况时，为每步选择事实标准。
4. 按需配置 MCP Servers、Agent Skills 或远端身份配对，并在系统设置中授权需要的权限。

## 权限与边界

- API Key 使用 Android Keystore 保护。无障碍权限独立授权，设备观察会过滤敏感内容。
- 写入按风险审批；启用设备 Agent 后，限定应用的设备动作默认免逐次确认，可在设置中关闭。动作审计和执行后验证仍保留，设备操作目前限于已验收应用和前台场景。
- Remote Channel 的后台收件与通知默认关闭、分别授权；消息仍需本机确认，不自动运行 Agent。
- 浏览器使用静态 HTML，输入仅保存非敏感本机草稿。MCP 不自动重放工具调用；插件与 GenUI 只开放受限宿主能力。
- 稳定远端部署、真实通知、自然系统恢复和复杂设备任务仍在验收。短期暂停 iOS 产品工作。

## 本地构建

macOS 环境需要 JDK 21、Android SDK 和 API 36 构建工具。

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:testDebugUnitTest :app:assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleRelease
```

APK 位于 `app/build/outputs/apk/`。Release 签名读取未跟踪的 `local-signing/xiaoling-release.env`；凭据与签名文件不得提交。

## 文档

- [文档索引](docs/README.md)：各功能契约与专项验收
- [产品需求](docs/requirements.md) · [实现说明](docs/implementation-notes.md)
- [后续路线图](docs/android-product-roadmap.md) · [验证报告](docs/verification-report.md)
