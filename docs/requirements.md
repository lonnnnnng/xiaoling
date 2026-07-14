# 需求汇总

## 背景

前置任务先调研了 GitHub 上 Android 端可自定义模型的 AI Agent / AI 客户端，要求至少 20 个仓库、Star 较多、更新较频繁，并核实这些项目是否支持自定义模型、Provider、OpenAI-compatible endpoint、Ollama 或本地模型。

调研后得到的产品判断是：完整 Android Agent 项目可以借鉴，但当前目标不需要做成 Agent。更实用的第一版是一个轻量 Android 工具，用来快速判断自定义端点、API Key 和模型名是否可用。

## 当前目标

做一个 Android App，用户可以在真机上输入：

- `Base URL`
- `API Key`
- 模型提供方名称

并通过上游模型列表勾选允许测试的 `Model ID`。

然后 App 可以：

- 请求 `GET /models` 获取可用模型列表。
- 请求 `POST /chat/completions` 测试指定模型是否能正常返回。
- 请求 `POST /responses` 测试 Responses API。
- 按需启用 SSE streaming 流式响应。
- 显示成功、错误类型、响应内容、耗时和最终请求地址。
- 安全保存 API Key，方便重复测试。
- 保存多个 Provider 配置，并支持为每个 Provider 勾选允许测试的上游模型。
- 固定使用 `32768` 作为 `max_tokens` / `max_output_tokens`。

## MVP 范围

- Android 原生 App，Kotlin + Jetpack Compose。
- OpenAI-compatible API 优先，不绑定单一服务商。
- Base URL 支持标准根路径，例如 `https://api.example.com/v1`。
- 用户粘贴完整接口地址时，自动归一化到 API 根路径，避免生成重复路径。
- 支持明文 HTTP，便于测试局域网、Ollama、LM Studio、adb reverse、本机 mock 服务。
- API Key 通过 Android Keystore + AES-GCM 加密保存。
- 对常见失败做可读分类：鉴权失败、404、429、超时、DNS、TLS、连接失败、响应格式错误。
- UI 采用明亮、紧凑的双 Tab 结构：测试页负责选择模型并对话，管理页负责维护 Provider 和端点配置。

## 暂不做

- 不做完整聊天历史。
- 不做多轮 Agent、工具调用、MCP、自动化执行。
- 不做 Provider 模板市场。
- 不做真实服务商账号的内置配置。
- 不内置任何 API Key。

这些能力可以后续参考 RikkaHub、Operit、Agora、Kai、OpenDroid、PocketPal AI 等项目继续扩展。

## 验收标准

- 工程能在 macOS 本地通过单元测试和 debug 构建。
- APK 能安装到已连接 Android 真机。
- 通过 adb reverse 访问电脑本机 mock OpenAI-compatible 服务。
- 管理页点击“获取上游模型”后能请求 `GET /v1/models`，展示返回的 `mock-model` 并允许勾选。
- 保存后管理列表显示该 Provider 共多少个可测试模型。
- 测试页只能选择已勾选模型，并在发送消息后显示模型回复。
- logcat 没有应用崩溃、ANR 或关键异常。
