# Endpoint Model Tester

一个简单的 Android 端 OpenAI-compatible 模型可用性测试工具。

## 当前定位

本项目来自前置调研结论：很多 Android AI Agent / AI 客户端都允许配置自定义 Provider、Base URL、API Key 和模型名，但如果只是想快速判断“某个自定义端点和模型到底能不能用”，完整 Agent 项目过重。

所以本项目只做一个小而明确的垂直切片：在 Android 真机上维护模型提供方，选择已勾选的上游模型，并验证 Chat Completions / Responses 接口是否可用。

## 功能

- 管理多个模型提供方：名称、`Base URL`、`API Key`。
- 支持 `GET /models` 获取模型列表。
- 支持手动勾选允许在测试页使用的上游模型。
- 支持 `POST /chat/completions` 发送测试消息。
- 支持 `POST /responses` 测试 Responses API。
- 支持 SSE streaming 流式响应测试。
- 测试页可切换 Chat / Responses 和是否启用流式输出。
- 固定 `max_tokens` / `max_output_tokens` 为 `32768`，避免每个 Provider 重复配置。
- 流式输出显示首字耗时和总耗时，非流式输出显示总耗时。
- API Key 使用 Android Keystore 加密保存。
- 允许明文 HTTP，便于测试 Ollama、局域网端点和 adb reverse。
- 对常见错误做分类提示，包括鉴权失败、404、429、超时、DNS、TLS、连接失败和响应格式错误。

## 文档

- [文档索引](docs/README.md)
- [需求汇总](docs/requirements.md)
- [参考资料来源](docs/reference-sources.md)
- [实现说明](docs/implementation-notes.md)
- [验证报告](docs/verification-report.md)

## 使用方式

1. 从 GitHub Release 下载并安装 APK，或本地执行构建后安装 `app/build/outputs/apk/release/app-release.apk`。
2. 打开 App，填写：
   - 在“管理”页新增或选择模型提供方。
   - 填写 `Base URL`：例如 `https://api.example.com/v1` 或 `http://127.0.0.1:8765/v1`。
   - 填写 `API Key`，服务需要鉴权时填写。
   - 点“获取上游模型”确认 `/models` 是否可用，并勾选允许在测试页使用的模型。
3. 保存后回到“测试”页，选择模型提供方和已勾选的具体模型。
4. 按需选择 Chat Completions / Responses API、是否启用 SSE streaming。
5. 输入消息并发送，确认 `/chat/completions` 或 `/responses` 是否可用。

## 本地 mock 调试

真机访问电脑本机服务时，可以使用 adb reverse：

```zsh
adb -s wsvwypiz7xwslvl7 reverse tcp:8765 tcp:8765
```

App 内填写：

```text
Base URL: http://127.0.0.1:8765/v1
API Key: test-key
```

## 构建

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleRelease
```

## 本次验证

- 验证设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro，Android 14 / API 34。
- 构建结果：`BUILD SUCCESSFUL`。
- 真机安装：`adb install -r` 成功。
- 真机检查：
  - 测试页和管理页可正常启动。
  - 测试页展示固定标题、紧凑底部 TabBar、Provider / 模型选择、Chat / Responses 和流式开关。
  - 管理页展示固定标题、Provider 列表和模型数量。
- 崩溃检查：当前进程 logcat 未命中 `FATAL EXCEPTION`、`AndroidRuntime`、`ANR`、`crash`、`Exception` 等关键字。

## 产物

- Release：<https://github.com/lonnnnnng/endpoint-model-tester/releases>
- 本地 release APK：`app/build/outputs/apk/release/app-release.apk`
- `outputs/` 目录不纳入版本控制。
