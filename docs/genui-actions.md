# GenUI v2 组件、草稿与显式 action

第 290 阶段第三片。开发版本保持 0.1.22 (23)、Room v44；本片是前台只读实现基线。

## 声明与降级

assistant 正文中的第一段 `xiaoling-ui` fence 可以声明单层 card。v1 text/button 兼容，按钮仍只回填输入框。v2 根有 `version:2 / id / revision`，每个组件与 action 分别声明 `version:1`，支持 text、facts、input、action。未知版本、组件、额外字段、重复 ID、敏感输入、非法类型、半 JSON、尾随内容、单引号方言和解码后重复键均保留原 Markdown。

JSON 上限 24,000 字符，最多 12 个组件；标题 120、正文 2,000 字符。facts 最多 8 项，label 120、value 500；input 的 maxLength 为 1–120，必须显式 `sensitive:false`，不能预填 value。revision 是 1–1,000,000 的整数。ID 是最多 64 字符的固定字符集。

```xiaoling-ui
{
  "type": "card", "version": 2, "id": "recent", "revision": 1,
  "title": "最近会话",
  "items": [
    {"type": "facts", "version": 1, "id": "scope", "entries": [{"label": "范围", "value": "本机只读"}]},
    {"type": "input", "version": 1, "id": "count", "label": "会话条数", "maxLength": 2, "sensitive": false},
    {"type": "action", "version": 1, "id": "read", "label": "申请读取", "action": {
      "type": "host_read", "version": 1, "tool": "app.list_conversations", "arguments": {}, "fields": {"limit": "count"}
    }}
  ]
}
```

action 只接受 `app.current_time / app.get_info / app.get_battery / app.get_connectivity / app.get_storage / app.list_conversations` 六个固定宿主工具；还必须属于点击时冻结的 Agent Profile 且当前原生定义为 SAFE/NONE。前五个工具不接受参数，会话列表仅允许 `limit`，值为字符串 `1`–`10`。arguments 是 literal 字符串映射，fields 是参数到本卡 input ID 的映射；二者不能重叠，不支持模板、URL、脚本、插件、MCP 或任意工具名。没有传条数时保留宿主默认值。

## 草稿与一次性引用

草稿按 conversation/message/正文哈希/组件声明保存在 Compose `remember`，不使用 rememberSaveable，不复制到消息正文、全局输入框或持久来源证据。只有数字条数可在显式选择时成为工具参数；其他文本不能进入审批或 Tool Ledger。Activity 重建丢弃草稿，重组不执行动作。

只有本进程普通聊天的 Completed 事件，且协议明确正常终止，才签发 action 引用。Chat Completions 要求 `finish_reason:stop`；Responses 要求 `status:completed` 且没有 error/incomplete_details；流式 completed 还必须携带可解析的最终正文，空输出或 refusal 不能确认之前缓存的卡片。流式 delta、output_text.done、单独 DONE、正常 EOF、截断或失败都不能代替正常终态；终态后的 delta/finalText 覆盖永久撤销本次 action 完成判定。普通聊天正文兼容既有展示行为。

引用绑定当前 issuer 内存表、conversationId、messageId、完整正文 SHA-256、document ID/revision 与 action ID。有效期 5 分钟、最多 32 项；过期/超容量失效。输入校验失败不消费，成功选择消费整张卡片一次，不能重复选择另一个按钮。恢复历史、点击、重组均不能重新签发。切换、删除、新建和加载会话撤销引用；发送的会话及代次也被冻结，A→B→A 的旧响应不能投影或签发。切入新的 Agent owner 也撤销普通发送代次，旧 Completed 异步收尾不能清空新 Run 的停止入口；UI 只投影当前有效的最多 32 项引用。

## 审批与执行证据

点击经 ConversationActions→ViewModel 的前台校验，拒绝忙碌、附件、共享草稿和待确认个人任务。宿主冻结一个工具及参数，把审批策略提升为 REQUIRE_CONFIRMATION；原生工具仍是 SAFE。新用户消息先持久化，然后固定 AgentLlm 提出唯一调用，原 MinimalAgentRuntime 等待现有审批卡，批准前 Executor 为零。模型不再规划任何额外工具，也没有模型网络请求。

Run 与 `genui.action.selected` 来源标记在同一 Room 事务建立。标记保存来源 ID/hash/revision/action/tool、参数 hash 与原工具定义 fingerprint；不保存输入草稿原文或执行许可。原审批决定、ToolCall/result/PASSED、Profile 与预算均走现有账本。单工具、整轮 60 秒、工具最多 5 秒；无附件、记忆召回/候选/shadow、Skill、Embedding、MCP、插件、Workflow 或 child 继承。

执行前及验证前重新核对动态可见目录、原生/注册定义 fingerprint、原选择及真实 MainActivity resumed 代次。前台暂停后即使恢复，旧代次也无效；撤销或过期会取消审批等待与 Run，迟到读取不能成为成功证据。拒绝、重复或初始化失败保留真实终态。

旧 `genui.action.selected` 即使 metadata 损坏，也要求 GENUI_SELECTION_EXPIRED，关闭旧 Run并保留事实；审批恢复、已提交验证、摘要恢复、普通重试、受控重放均不能继承引用。必须重新生成卡片并选择。

## 验证边界

JVM/Redmi 共用协议和 Runtime 向量，另有隔离 UUID Room 的生产 UseCase/原生时间/PASSED/审批/来源共事务/初始化失败/磁盘重开专项，以及 Compose 表单、流式禁用、重组与 v1 兼容专项。完整结果见[验证报告](verification-report.md)。数据库 reopen 是受控恢复，不能称为自然 LMK；此片 action 本身使用固定本地规划，不把夹具 UI 与生产 UseCase 的分段验收称为完整真实模型→ViewModel→审批点击联合验收。

Workspace/Browser/GenUI 三片实现基线收口后，真实渲染 DOM/网页输入、完整产品真实模型联合、自然系统恢复、性能与正式 Release 独立追踪；下一主线为第 7 点 KMP 协议/codec 固化。
