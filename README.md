# 小灵

「小灵」是一款 Android 端个人 Agent 应用。当前阶段先把个人 Agent 的基础底座做稳：多模型提供方配置、多会话上下文、Chat Completions / Responses API、Room 本地存储、可审计 Agent Run，以及基于 WorkManager 的一次性非精确定时工作流。

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
  - Responses 模式支持从系统文件选择器附加单张 PNG/JPEG/WEBP 图片（最大 8 MB），发送前可预览或移除，历史消息可恢复显示；Chat Completions 和 `/agent` 会明确拒绝图片。
  - Responses 模式支持附加单个 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX 或 XLSX 文档；文件最大 8 MB，PDF 最多 50 页，UTF-8 文本最多 200,000 字符，OpenXML 富文档会校验 ZIP/OPC 结构与展开预算，原始文件随消息恢复。
  - 支持 Markdown 渲染，覆盖表格、代码块、列表、引用、链接和远程图片。
  - 对话记录有轻量的新内容提示，用户翻看历史时不会被强制拉回底部。
  - 支持 `/agent <目标>` 顺序多步 Agent 链路：当前模型可在同一 Run 内逐步选择最多 4 个工具或结束任务，应用侧对每一步独立校验、审批和验证，执行结果写入 `AgentRun / AgentStep / RunEvent`。
  - 已内置第一批应用内工具：`app.current_time`、`app.list_conversations`、`app.search_conversations`、`notes.list`、`notes.search`、`memory.search`、`knowledge.search`，以及需要审批的 `notes.create` 和 `memory.remember`。
  - 设备 Agent 已接入 `device.snapshot / open_app / back / home / tap_ref / type_text / swipe`：仅在用户独立开启、系统 Accessibility 已授权、前台直接 `/agent` 且 Profile/Skill 允许时可用；打开应用、点击和输入必须审批，所有动作完成后重新观察并验证，Workflow 与后台运行不会看到或执行任何设备工具。

- 设置页
  - 一级入口为「模型提供方管理」。
  - 提供「提示词设置」二级页，可分别配置普通对话、会话摘要 / 记忆和 Agent 回复总结模板。
  - 每类模板支持独立启用、恢复默认和预览最终提示词；普通对话的工具边界、摘要事实边界和 Agent 审计边界不可被自定义模板覆盖。
  - 提供「Agent Skills」管理页，展示内置与本地 Skill，可通过系统文件选择器导入版本化 JSON、启停能力并删除本地 Skill。
  - 提供「设备 Agent」页，管理默认关闭的独立开关、系统无障碍入口、四态健康检查和有界脱敏快照预览。
  - 提供「工作流」管理页，可保存、启停、手动运行或创建 1 分钟至 7 天的一次性非精确计划，并查看计划时间、实际启动时间、Workflow Run 与步骤结果。
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
  - Provider、会话、消息及 Text/Reasoning/Image/Document/Tool parts、Agent Run、笔记、长期记忆、Skill、Workflow Ledger 和 ScheduledTask 使用 Room 保存；附件原始字节写入 BLOB 并随数据库备份，旧 SharedPreferences 数据首次启动时迁入。
  - 后台计划只允许显式声明 `supportsBackground=true` 的 SAFE 只读工具；需要审批的工具在执行前进入 `BLOCKED` 并发送通知，不创建或继承前台临时授权。
  - API Key 使用 Android Keystore + AES-GCM 加密保存。
  - 允许明文 HTTP，便于连接 Ollama、LM Studio、局域网服务和 adb reverse。
  - HTTP 调试日志通过 BuildConfig 开关控制：debug 默认开启，release 默认关闭。
  - 普通对话不具备工具执行能力，不得声称已经调用工具、操作设备、创建笔记或保存长期记忆；真实工具事实只来自可审计 Agent Run。
  - AccessibilityService 只执行标准节点动作与系统返回/主页，不具备坐标手势或截图能力；支付窗口与已知密码管理器、Authenticator、钱包/银行应用整窗拒绝，密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref。

## 使用方式

1. 打开「设置」页，进入「模型提供方管理」。
2. 新增模型提供方，填写：
   - 名称：可选，不填时根据 URL 兜底。
   - `Base URL`：例如 `https://api.example.com/v1` 或 `http://127.0.0.1:8765/v1`。
   - `API Key`：服务需要鉴权时填写。
3. 点击「获取上游模型」，勾选允许在对话页使用的模型并保存。
4. 回到「对话」页，选择模型提供方、模型、接口模式和是否流式输出。
5. 输入消息开始对话；Responses 模式可点击附件图标附加单张图片或一个文档。输入 `/agent 现在几点`、`/agent 记住我喜欢紧凑的界面` 可运行本地最小 Agent 工具链路，但当前 `/agent` 不接收附件。
6. 如需扩展声明式能力，可在「设置 -> Agent Skills」导入 [每日回顾示例](docs/examples/daily-review.skill.json)；本地 Skill 只能组合应用已注册工具，不能执行脚本或放宽审批边界。
7. 可在「设置 -> 工作流」保存常用 Agent 目标并手动运行，或点击时钟图标创建一次性计划。WorkManager 只保证在计划时间后尽快运行，不承诺准点；Android 13+ 建议授予通知权限以接收完成、失败和待处理结果。
8. 如需使用设备 Agent，在「设置 -> 设备 Agent」明确开启应用开关并完成系统无障碍授权，再为 Agent Profile 选择只读的 `device-observation` 或有限动作的 `device-control` Skill。当前只允许小灵、系统计算器、时钟和系统设置等首批白名单应用；设备工具仍不能进入 Workflow 或后台自动化。

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

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：通过，当前 348 项 JVM 测试通过，0 失败、0 错误。
- 仅在 Redmi 真机 `wsvwypiz7xwslvl7` 执行完整 123 条 instrumentation，结果为 `OK (123 tests)`；模拟器未参与安装、测试、截图或验收。
- Room v27 已完成本地知识库、`knowledge.search`、稳定引用链和引用生命周期校验；禁用、替换或删除后，旧消息与 Workflow 输出不会再次进入新模型上下文，历史审计保持不变。
- Redmi 真实 AccessibilityService 已验证普通主界面快照成功、敏感字段节点脱敏、支付窗口 `SENSITIVE_WINDOW` 整窗拒绝；应用独立开关默认关闭和关闭即撤销 ref 均已验证，系统服务授权与绑定正常。
- Redmi 有限动作验收覆盖计算器打开/节点点击、设置页滚动/搜索/普通文本输入、敏感输入拒绝、返回、主页和时钟启动；真实 `gpt-5.5 + Responses` `/agent` Run 完成 `device.open_app` 的模型规划、应用侧审批、动作后验证、Tool Ledger 与最终总结，Run 为 `COMPLETED`。
- 五份真实项目文档的自然改写、多词分隔、top-1 和负例检索门禁均通过；真实 `gpt-5.5` Agent Run 已完成知识工具规划与引用一致性验收。
- Debug 请求日志继续脱敏附件、Authorization 和原始/加密推理内容；默认 User-Agent 保持正确。
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
