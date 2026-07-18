# 小灵

「小灵」是一款 Android 端个人 Agent 应用。当前阶段先把个人 Agent 的基础底座做稳：多模型提供方配置、上游模型选择、多会话上下文、LLM 摘要压缩、Chat Completions / Responses API、SSE 流式输出、Markdown 渲染、Room 本地存储和可审计 Agent Run。

后续方向不是继续停留在“能不能连上模型”，而是逐步扩展成个人可长期使用的移动端 Agent：持续记忆、工具调用、移动端自动化、任务编排和更完整的个人工作流。

GitHub 仓库：[lonnnnnng/xiaoling](https://github.com/lonnnnnng/xiaoling)

## 当前定位

- 移动端个人 Agent 入口。
- OpenAI-compatible Provider 优先，不绑定单一服务商。
- 先保证模型接入、上下文、会话保存和输出渲染稳定，再扩展工具与自动化。
- 更换了新的 `applicationId`：`com.longdev.xiaoling`。Android 会把它视为新应用，旧版本本地数据不会自动迁移。

## 已有能力

- 对话页
  - 支持选择模型提供方和已启用模型。
  - 支持 Chat Completions 与 Responses API。
  - 支持 SSE 流式输出，展示首字耗时和总耗时。
  - 支持发送中停止生成，取消当前请求和底层 OkHttp Call。
  - 支持多会话本地保存、会话上下文和 LLM 摘要压缩。
  - 支持 Markdown 渲染，覆盖表格、代码块、列表、引用、链接和远程图片。
  - 对话记录有轻量的新内容提示，用户翻看历史时不会被强制拉回底部。
  - 支持 `/agent <目标>` 最小 Agent 链路：当前模型提出工具调用并总结，应用侧按风险决定是否审批，执行结果写入 `AgentRun / AgentStep / RunEvent`。
  - 已内置第一批应用内工具：`app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`memory.search`，以及需要审批的 `notes.create` 和 `memory.remember`。

- 设置页
  - 一级入口为「模型提供方管理」。
  - 提供「提示词设置」二级页，可分别配置普通对话、会话摘要 / 记忆和 Agent 回复总结模板。
  - 每类模板支持独立启用、恢复默认和预览最终提示词；普通对话的工具边界、摘要事实边界和 Agent 审计边界不可被自定义模板覆盖。
  - 支持新增、编辑、删除模型提供方。
  - 支持 `Base URL`、`API Key` 和名称配置。
  - 支持扫码导入、剪切板解析和 Base64 解码辅助。
  - 支持拉取上游模型列表，并手动勾选允许在对话页使用的模型。
  - 支持单个同步和批量同步模型列表。

- 请求与安全
  - 支持 `GET /models`。
  - 支持 `POST /chat/completions`。
  - 支持 `POST /responses`。
  - 固定 `max_tokens` / `max_output_tokens` 为 `32768`。
  - Provider、会话、消息、Agent Run、笔记和长期记忆使用 Room 保存；旧 SharedPreferences 数据首次启动时迁入。
  - API Key 使用 Android Keystore + AES-GCM 加密保存。
  - 允许明文 HTTP，便于连接 Ollama、LM Studio、局域网服务和 adb reverse。
  - HTTP 调试日志通过 BuildConfig 开关控制：debug 默认开启，release 默认关闭。
  - 普通对话不具备工具执行能力，不得声称已经调用工具、操作设备、创建笔记或保存长期记忆；真实工具事实只来自可审计 Agent Run。

## 使用方式

1. 打开「设置」页，进入「模型提供方管理」。
2. 新增模型提供方，填写：
   - 名称：可选，不填时根据 URL 兜底。
   - `Base URL`：例如 `https://api.example.com/v1` 或 `http://127.0.0.1:8765/v1`。
   - `API Key`：服务需要鉴权时填写。
3. 点击「获取上游模型」，勾选允许在对话页使用的模型并保存。
4. 回到「对话」页，选择模型提供方、模型、接口模式和是否流式输出。
5. 输入消息开始对话；输入 `/agent 现在几点`、`/agent 记住我喜欢紧凑的界面` 可运行本地最小 Agent 工具链路。

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

生成正式签名包：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleRelease
```

本机 release 签名配置放在未跟踪目录：

```text
local-signing/xiaoling-release.env
local-signing/xiaoling-release.jks
```

## 当前验证

- `testDebugUnitTest assembleDebug`：通过，当前 121 项 Debug 单元测试通过。
- `assembleRelease`：通过。
- `apksigner verify --print-certs`：通过，证书主体为 `CN=XiaoLing, OU=XiaoLing, O=Long, L=Shanghai, ST=Shanghai, C=CN`。
- 真机 `wsvwypiz7xwslvl7`：debug 包覆盖安装和启动成功；`WAITING_APPROVAL` Run 经进程强制停止、冷启动、批准后在原 Run 完成，未创建重试 Run。
- APK 元数据：包名 `com.longdev.xiaoling`，应用展示名「小灵」。

## 文档

- [文档索引](docs/README.md)
- [产品需求](docs/requirements.md)
- [个人 Agent 路线图](docs/personal-agent-roadmap.md)
- [参考项目分析](docs/reference-apps-analysis.md)
- [当前实现说明](docs/implementation-notes.md)
- [验证报告](docs/verification-report.md)

## 产物

- 本地 debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 本地 release APK：`app/build/outputs/apk/release/app-release.apk`
- `outputs/` 目录不纳入版本控制。
