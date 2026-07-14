# 参考资料来源

## 来源边界

本文件汇总当前项目用到的参考来源。这里没有重新联网抓取；资料来自前面已经生成并保存的 Smart Search / GitHub 调研结果、本地证据文件，以及当前 Android 工程的实现与真机验证。

Star、提交日期、Release 日期会随 GitHub 改变。调研文件中的数值是 2026-07-14 的快照，不能当成长期不变的数据。

## 原始调研报告

| 文件 | 作用 |
|---|---|
| `../../outputs/android-ai-agent-github-research.md` | 第一份 GitHub/API 调研清单，主榜 23 个项目，额外观察 5 个项目。用于了解高 Star、高活跃项目的 Android 形态、模型自定义能力和 Agent 能力。 |
| `../../outputs/android-ai-agent-github-smart-search-20260714.md` | 第二份 Smart Search 独立检索清单。用于补充第一份没有覆盖到的半成品、模型服务端、AI 键盘、Android LLM Server 和实验性 Agent 项目。 |
| `../../outputs/android-ai-agent-github-research-smart-search-verified-20260714.md` | 第一份清单的逐仓库核验报告，并与第二份清单做重复仓库比较。用于确认哪些项目真正适合借鉴，哪些只是客户端或底座。 |

## Smart Search 证据目录

| 目录 | 内容 |
|---|---|
| `../../work/first-file-smart-search-verification/` | 第一份清单 28 个唯一仓库的逐仓库 Markdown 抓取证据。 |
| `../../work/new-smart-search-evidence/` | 第二份 Smart Search 独立检索中保存的仓库证据。 |

## 对本项目有直接启发的项目类型

| 项目类型 | 代表仓库 | 对 Endpoint Model Tester 的启发 |
|---|---|---|
| 多 Provider Android 客户端 | RikkaHub、GPT Mobile、Maid、ChatterUI | Base URL、Model ID、API Key、Provider 配置应该直接暴露给用户，而不是硬编码。 |
| OpenAI-compatible / Ollama 客户端 | ChatterUI、GPT Mobile、Reins、ollama-app、Conduit | `/models` 和 `/chat/completions` 是最小可用性测试路径。 |
| 端侧 GGUF / 本地模型 App | PocketPal AI、SmolChat-Android、OfflineLLM、Box | 用户可能测试本地或局域网模型服务，所以必须支持 HTTP 和自定义地址。 |
| 完整 Android Agent | Operit、Aether、OpenDroid、Agora、Kai、AIOPE | 完整 Agent 能力很重，当前 MVP 只取“模型连接验证”这一条基础链路。 |
| 手机控制 / 自动化框架 | Mobilerun、mobile-use、X-OmniClaw | 这些项目证明 Android 自动化 Agent 需要稳定 Provider 配置，但本项目先解决 Provider 是否可用。 |

## 当前工程来源

| 文件 | 说明 |
|---|---|
| `../app/src/main/java/com/long/endpointtester/ui/EndpointTesterScreen.kt` | Compose 双 Tab 界面，测试页负责选择已勾选模型并对话，管理页负责 Provider 列表、新增编辑、模型获取和勾选。 |
| `../app/src/main/java/com/long/endpointtester/ui/EndpointTesterViewModel.kt` | Provider 状态、校验、模型获取、对话发送和配置保存入口。 |
| `../app/src/main/java/com/long/endpointtester/network/OpenAiCompatibleClient.kt` | OpenAI-compatible HTTP 请求实现。 |
| `../app/src/main/java/com/long/endpointtester/network/EndpointUrlBuilder.kt` | Base URL 校验和 `/models`、`/chat/completions` 地址归一化。 |
| `../app/src/main/java/com/long/endpointtester/network/HeaderParser.kt` | 自定义 Header 解析。 |
| `../app/src/main/java/com/long/endpointtester/network/OpenAiResponseParser.kt` | 模型列表和聊天补全文本解析。 |
| `../app/src/main/java/com/long/endpointtester/storage/SecureConfigStore.kt` | Android Keystore + AES-GCM 加密保存 API Key。 |
| `../app/src/test/java/com/long/endpointtester/network/EndpointUrlBuilderTest.kt` | URL 归一化单元测试。 |
| `../app/src/test/java/com/long/endpointtester/network/OpenAiResponseParserTest.kt` | Responses API 和 SSE 增量解析单元测试。 |

## 已验证事实

- 本 App 已在 `wsvwypiz7xwslvl7` 真机安装。
- 测试页和管理页已完成启动、布局和基础导航验证。
- logcat 未发现应用崩溃、ANR 或关键异常。
- Release APK 发布到 GitHub Releases；`outputs/` 不再纳入版本控制。

## 候选来源

完整 Agent 能力、Provider 模板市场和本地模型文件管理可以继续参考前置调研中的项目；当前 App 已实现多 Provider 配置、上游模型获取、模型勾选和模型连接测试。
