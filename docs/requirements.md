# 需求汇总

## 背景

小灵的目标是做成个人 Agent 应用，而不是只停留在某个模型连通性工具。前置调研里多个 Android AI 客户端和 Agent 项目都证明了同一件事：移动端 Agent 要可靠可用，首先必须有稳定的模型提供方配置、模型选择、上下文管理、流式输出和本地数据保存。

当前阶段的重点是把这些底座做稳，为后续个人记忆、工具调用、移动端自动化和任务编排留出结构空间。

## 当前目标

用户可以在 Android 真机上完成：

- 配置多个模型提供方。
- 保存 `Base URL`、`API Key` 和启用模型列表。
- 在对话页选择模型提供方和模型。
- 在 Chat Completions 与 Responses API 之间切换。
- 按需启用 SSE 流式输出。
- 进行多轮对话，并让模型参考当前会话上下文。
- 本地保存多个会话。
- 在长会话中使用摘要压缩，避免无限增长上下文。
- 稳定渲染常见 Markdown 输出。

## 当前范围

- Android 原生 App，Kotlin + Jetpack Compose。
- 包名：`com.longdev.xiaoling`。
- OpenAI-compatible API 优先，不绑定单一服务商。
- 支持 `GET /models`、`POST /chat/completions` 和 `POST /responses`。
- `max_tokens` / `max_output_tokens` 固定为 `32768`。
- API Key 通过 Android Keystore + AES-GCM 加密保存。
- 支持明文 HTTP，便于连接局域网服务、Ollama、LM Studio、adb reverse 和本机 mock 服务。
- 对常见失败做可读分类：鉴权失败、接口地址错误、限流、模型不可用、超时、DNS、TLS、连接失败、响应格式错误。
- UI 采用「对话 / 设置」双入口结构；模型提供方配置列表位于设置页二级入口「模型提供方管理」。

## 暂不做

- 不做账号体系和云同步。
- 不内置任何 API Key。
- 不默认代理用户执行手机系统操作。
- 不做 Provider 模板市场。
- 不做工具调用和 MCP，但保留后续扩展空间。

## 验收标准

- 工程能在 macOS 本地通过单元测试、debug 构建和 release 构建。
- APK 元数据展示包名 `com.longdev.xiaoling` 和应用名「小灵」。
- APK 能安装到已连接 Android 真机并启动主界面。
- 设置页能进入「模型提供方管理」。
- 对话页能展示模型提供方选择、模型选择、Resp 和流式开关。
- 对话页能保留会话记录，并在模型返回时展示 Markdown 内容。
- logcat 没有应用崩溃、ANR 或关键异常。
