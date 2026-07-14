# Endpoint Model Tester

一个简单的 Android 端 OpenAI-compatible 模型可用性测试工具。

## 当前定位

本项目来自前置调研结论：很多 Android AI Agent / AI 客户端都允许配置自定义 Provider、Base URL、API Key 和模型名，但如果只是想快速判断“某个自定义端点和模型到底能不能用”，完整 Agent 项目过重。

所以本项目只做一个小而明确的垂直切片：在 Android 真机上填写自定义端点、密钥和模型名，分别验证模型列表接口和聊天补全接口是否可用。

## 功能

- 自定义 `Base URL`、`API Key`、`Model ID`。
- 支持 `GET /models` 获取模型列表。
- 支持 `POST /chat/completions` 发送一条测试消息。
- 支持自定义 Headers，便于测试 Azure、代理网关或私有服务。
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

1. 安装 `outputs/endpoint-model-tester-debug.apk`。
2. 打开 App，填写：
   - `Base URL`：例如 `https://api.example.com/v1` 或 `http://127.0.0.1:8765/v1`
   - `API Key`：服务需要鉴权时填写
   - `Model ID`：可手动填写，也可以先点“获取模型”自动选择第一个模型
3. 点“获取模型”确认 `/models` 是否可用。
4. 点“测试模型”确认 `/chat/completions` 是否可用。

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
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
```

## 本次验证

- 验证设备：`wsvwypiz7xwslvl7`，Redmi Note 8 Pro，Android 14 / API 34。
- 构建结果：`BUILD SUCCESSFUL`。
- 真机安装：`adb install -r` 成功。
- 真机端到端结果：
  - `GET /v1/models` 成功，返回 `mock-model`。
  - `POST /v1/chat/completions` 成功，返回 `OK`。
  - App 显示 `模型可用`，耗时 `14 ms`。
- 崩溃检查：当前进程 logcat 未命中 `FATAL EXCEPTION`、`AndroidRuntime`、`ANR`、`crash`、`Exception` 等关键字。

## 产物

- APK：`outputs/endpoint-model-tester-debug.apk`
- APK SHA-256：`59f7fc4c2ab8fe25ed86d7ecdda87d4e0b4a17e480b2065b271debd0d14fa212`
- 真机验证截图：`outputs/endpoint-tester-success.png`
