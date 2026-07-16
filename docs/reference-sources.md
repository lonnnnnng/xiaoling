# 参考资料来源

## 来源边界

本文件汇总小灵当前用到的参考来源。这里没有重新联网抓取；资料来自前面已经保存的 Smart Search / GitHub 调研结果、本地参考项目、以及当前 Android 工程的实现与真机验证。

Star、提交日期、Release 日期会随 GitHub 改变。历史调研文件中的数值是对应日期的快照，不能当成长期不变的数据。

## 原始调研报告

| 文件 | 作用 |
|---|---|
| `../../outputs/android-ai-agent-github-research.md` | 第一份 GitHub/API 调研清单，用于了解高 Star、高活跃项目的 Android 形态、模型自定义能力和 Agent 能力。 |
| `../../outputs/android-ai-agent-github-smart-search-20260714.md` | 第二份 Smart Search 独立检索清单，用于补充模型服务端、AI 键盘、Android LLM Server 和实验性 Agent 项目。 |
| `../../outputs/android-ai-agent-github-research-smart-search-verified-20260714.md` | 第一份清单的逐仓库核验报告，并与第二份清单做重复仓库比较。 |

## 参考项目类型

| 项目类型 | 代表仓库 | 对小灵的启发 |
|---|---|---|
| 多 Provider Android 客户端 | RikkaHub、GPT Mobile、Maid、ChatterUI | Base URL、Model ID、API Key、Provider 配置应该直接暴露给用户，而不是硬编码。 |
| OpenAI-compatible / Ollama 客户端 | ChatterUI、GPT Mobile、Reins、ollama-app、Conduit | `/models`、`/chat/completions` 和 `/responses` 是个人 Agent 接入模型服务的基础链路。 |
| 端侧 GGUF / 本地模型 App | PocketPal AI、SmolChat-Android、OfflineLLM、Box | 用户可能连接本地或局域网模型服务，所以必须支持 HTTP 和自定义地址。 |
| 完整 Android Agent | Operit、Aether、OpenDroid、Agora、Kai、AIOPE | Agent 能力需要建立在稳定模型接入、上下文和任务状态之上，当前先把这些底座做稳。 |
| 手机控制 / 自动化框架 | Mobilerun、mobile-use、X-OmniClaw | 后续工具调用和手机自动化需要明确权限边界、任务状态和失败回放。 |

## 当前工程证据

| 文件 | 说明 |
|---|---|
| `../app/src/main/java/com/longdev/xiaoling/ui/XiaoLingApp.kt` | Compose 主界面，包含对话页、设置页、模型提供方管理、会话切换和消息输入。 |
| `../app/src/main/java/com/longdev/xiaoling/ui/XiaoLingViewModel.kt` | Provider 状态、会话状态、上下文构造、摘要压缩、模型同步和对话发送入口。 |
| `../app/src/main/java/com/longdev/xiaoling/network/OpenAiCompatibleClient.kt` | OpenAI-compatible HTTP 请求、Responses API、SSE 流式处理和日志切口。 |
| `../app/src/main/java/com/longdev/xiaoling/network/ProviderApiUrlBuilder.kt` | API 地址校验和 `/models`、`/chat/completions`、`/responses` 地址归一化。 |
| `../app/src/main/java/com/longdev/xiaoling/network/OpenAiResponseParser.kt` | 模型列表、聊天文本和 SSE 增量解析。 |
| `../app/src/main/java/com/longdev/xiaoling/storage/SecureConfigStore.kt` | Android Keystore + AES-GCM 加密保存 API Key。 |
| `../app/src/main/java/com/longdev/xiaoling/storage/ConversationStore.kt` | 会话、消息和结构化消息元数据保存。 |
| `../app/src/test/java/com/longdev/xiaoling/network/ProviderApiUrlBuilderTest.kt` | API 地址归一化单元测试。 |
| `../app/src/test/java/com/longdev/xiaoling/network/OpenAiResponseParserTest.kt` | Responses API 和 SSE 增量解析单元测试。 |
| `../app/src/test/java/com/longdev/xiaoling/ui/MarkdownVerificationSamplesTest.kt` | 常见 Markdown 输出样例和表格解析回归覆盖。 |

## 已验证事实

- App 包名已切换为 `com.longdev.xiaoling`。
- APK 展示名已切换为「小灵」。
- Debug APK 能安装并启动到真机主界面。
- Release APK 使用主体为 `XiaoLing` 的正式证书签名。
- `outputs/` 不纳入版本控制。

## 后续候选方向

- 个人长期记忆。
- 工具调用和 MCP。
- 手机端自动化执行。
- 任务计划、任务回放和失败恢复。
- 多模型协作和结果对比。
