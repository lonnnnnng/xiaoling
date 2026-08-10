# 小灵个人 Agent 路线图

## 第 242 阶段：系统分享 XLSX 到显式 Agent 理解闭环（完成）

- Artifact Tool 完整参考工作簿与 5 部件最小 XLSX 均通过导入、值检查、公式错误扫描和渲染。Android 夹具固定为内容类型、根关系、workbook、workbook relationships 与 sheet1 五个 OPC 部件，动态 `TITLE / ACCEPTANCE_CODE / CONCLUSION` 只存在于 `xl/worksheets/sheet1.xml`。
- 统一读取器完成 ZIP 中央目录、本地头、展开预算与 `xl/workbook.xml` 根部件校验后，仍保持 `extractedText=null / pageCount=null`。XLSX 继续先进入新会话可编辑附件草稿；导入、用户改写 prompt 和输入 `/agent` 均不自动发送或创建 Run。
- 验收 prompt、临时 Profile、文件名与 runner 参数均不包含三个动态值，Profile 只开放 `notes.create`。最终 Run `run-568f80c5-9910-4477-a4ec-7f765e446dfd` 为 `COMPLETED`，唯一审批 `APPROVED`、Executor/typed verification `PASSED`、回执 `COMMITTED`；工具参数只能来自模型对工作表单元格的理解。
- Room USER Document 保留 XLSX 原始字节与空提取正文/页数；Tool Message 与 Ledger 参数一致，回执笔记从当前 Store 回读相同标题、验收码和结论。仅 Redmi 真实单项为 `OK (1 test)`、`28.379s`；文档 corpus 首次为 `1/1`、`3.071s`，写回结果后的最终文本 gate 也为 `1/1`、`3.179s`。
- 临时笔记/Profile/会话/MediaStore XLSX 精确清理，原 Profile/会话选择恢复，最近旧 Run 完整摘要不变，新 Run 审计保留。本阶段只增加真实验收夹具，没有修改生产代码、Room v36、Manifest、权限、Provider/Tool/Skill、Workflow、后台或附件协议。

第 242 阶段已经完成 DOCX、PPTX、XLSX 三类 OpenXML 文件理解覆盖。下一阶段优先验证单图片通过系统分享进入可编辑草稿、由用户明确发送后再进入视觉 Agent 的受控闭环；继续禁止多附件、自动发送、后台摄取、远程 Channel、多 Agent 或本地模型。

## 第 241 阶段：系统分享 PPTX 到显式 Agent 理解闭环（完成）

- 测试从 Artifact Tool 真实 PPTX 验证出发，确认 5 部件简化包无法被桌面解析器接受，再补齐 presentation、slide、layout、master 与双向关系，形成可渲染、无溢出的 10 部件 PPTX/OPC 夹具。动态 `TITLE / ACCEPTANCE_CODE / CONCLUSION` 只存在于 `ppt/slides/slide1.xml`。
- 统一读取器完成 ZIP 中央目录、本地头、展开预算与 `ppt/presentation.xml` 根部件校验后，仍保持 `extractedText=null / pageCount=null`。PPTX 继续先进入新会话可编辑附件草稿；导入、用户改写 prompt 和输入 `/agent` 均不自动发送或创建 Run。
- 验收 prompt、临时 Profile、文件名与 runner 参数均不包含三个动态值，Profile 只开放 `notes.create`。最终 Run `run-92128f92-cd87-42f0-bcd9-fc149bcfc5ae` 为 `COMPLETED`，唯一审批 `APPROVED`、Executor/typed verification `PASSED`、回执 `COMMITTED`；工具参数只能来自模型对幻灯片正文的理解。
- Room USER Document 保留 PPTX 原始字节与空提取正文/页数；Tool Message 与 Ledger 参数一致，回执笔记从当前 Store 回读相同标题、验收码和结论。仅 Redmi 真实单项为 `OK (1 test)`、`25.217s`；文档 corpus 首次为 `1/1`、`3.251s`，写回结果后的最终文本 gate 也为 `1/1`。
- 临时笔记/Profile/会话/MediaStore PPTX 精确清理，原 Profile/会话选择恢复，最近旧 Run 完整摘要不变，新 Run 审计保留。本阶段只增加真实验收夹具，没有修改生产代码、Room v36、Manifest、权限、Provider/Tool/Skill、Workflow、后台或附件协议。

第 242 阶段已完成 XLSX/OpenXML 工作簿理解闭环。

## 第 240 阶段：系统分享 DOCX 到显式 Agent 理解闭环（完成）

- 测试生成标准 DOCX/OPC ZIP，动态 `TITLE / ACCEPTANCE_CODE / CONCLUSION` 只存在于 `word/document.xml`。统一读取器完成 ZIP 中央目录、本地头、展开预算与 DOCX 根部件校验后，仍保持 `extractedText=null / pageCount=null`。
- DOCX 继续先进入新会话可编辑附件草稿；导入、用户改写 prompt 和输入 `/agent` 均不自动发送或创建 Run。只有用户明确点击发送后，可信 USER DOCX BLOB 才进入 Responses 规划请求。
- 验收 prompt、临时 Profile、文件名与 runner 参数均不包含三个动态值，Profile 只开放 `notes.create`。最终 Run `run-9f3618fd-b3a2-460f-853e-04ecd2620bdc` 为 `COMPLETED`，唯一审批 `APPROVED`、Executor/typed verification `PASSED`、回执 `COMMITTED`；工具参数只能来自模型对 DOCX 正文的理解。
- Room USER Document 保留 DOCX 原始字节与空提取正文/页数；Tool Message 与 Ledger 参数一致，回执笔记从当前 Store 回读相同标题、验收码和结论。仅 Redmi 真实单项为 `OK (1 test)`、`25.147s`；文档 corpus 首次为 `1/1`、`3.131s`，写回结果后的最终文本 gate 也为 `1/1`。
- 临时笔记/Profile/会话/MediaStore DOCX 精确清理，原 Profile/会话选择恢复，最近旧 Run 完整摘要不变，新 Run 审计保留。本阶段只增加真实验收夹具，没有修改生产代码、Room v36、Manifest、权限、Provider/Tool/Skill、Workflow、后台或附件协议。

第 241 阶段已完成 PPTX/OpenXML 演示文稿理解闭环。

## 第 239 阶段：系统分享 PDF 到显式 Agent 理解闭环（完成）

- 测试使用 Android `PdfDocument` 生成一页真实 PDF，只在页面绘制动态 `TITLE / ACCEPTANCE_CODE / CONCLUSION`。统一文档读取器确认 `%PDF`、`application/pdf` 和 `pageCount=1`，且 `extractedText=null`，因此应用本地没有可供 Agent 直接复用的提取正文。
- PDF 继续先进入新会话可编辑附件草稿；导入、用户改写 prompt 和输入 `/agent` 均不自动发送或创建 Run。只有用户明确点击发送后，可信 USER PDF BLOB 才进入 Responses 规划请求。
- 验收 prompt 与临时 Profile 都不包含三个动态值，Profile 只开放 `notes.create`。最终 Run `run-2019d5f6-03fe-4bb5-a59f-b126f4b1f028` 为 `COMPLETED`，唯一审批 `APPROVED`、Executor/typed verification `PASSED`、回执 `COMMITTED`；工具参数只能来自模型对 PDF 页面的理解。
- Room USER Document 保留 PDF 原始字节、页数和空提取正文；Tool Message 与 Ledger 参数一致，回执笔记从当前 Store 回读相同标题、验收码和结论。仅 Redmi 真实单项为 `OK (1 test)`、`31.691s`，文档 corpus gate 为 `1/1`、`2.919s`。
- 临时笔记/Profile/会话/MediaStore PDF 精确清理，原 Profile/会话选择恢复，最近旧 Run 完整摘要不变，新 Run 审计保留。本阶段只增加真实验收夹具，没有修改生产代码、Room v36、Manifest、权限、Provider/Tool/Skill、Workflow、后台或附件协议。

第 240 阶段已完成 DOCX/OpenXML 文件理解闭环。

## 第 238 阶段：系统分享文档到显式 Agent 理解闭环（完成）

- Markdown 继续先通过 Android `ACTION_SEND` 进入新会话可编辑附件草稿；导入、用户编辑以及把普通说明替换为 `/agent` 的过程都不会自动发送、调用模型或创建 Run。只有用户明确点击发送后，可信 USER Document 才进入既有 Responses Agent 附件链。
- 真实验收命令只描述“从一级标题和两个标签行创建笔记”，不包含动态文档标题、验收码或结论。临时 Profile 仅开放 `notes.create`；模型必须从附件生成唯一工具参数，写入仍需用户批准。
- 最终 Run `run-96f7b55a-7741-4fb8-a92c-3fbe7a3a92cc` 为 `COMPLETED`，唯一审批 `APPROVED`、Executor/typed verification `PASSED`、回执 `COMMITTED`。USER 消息保留完整 Markdown Document BLOB，Tool Message 参数与 Ledger 一致，回执笔记按稳定 ID 从当前 Store 回读标题和两个附件专属字段。
- 仅 Redmi `wsvwypiz7xwslvl7` 真实单项为 `OK (1 test)`、`21.901s`，文档 corpus gate 为 `1/1`、`3.406s`。临时笔记/Profile/会话/MediaStore 文档精确清理，原 Profile/会话选择恢复，最近旧 Run 完整摘要不变，新 Run 审计保留。
- 本阶段只增加真实验收夹具，没有修改生产代码、Room v36、Manifest、权限、Provider/Tool/Skill、Workflow、后台或附件协议；未运行完整 JVM、Lint、Release 或全量 instrumentation。

第 239 阶段已完成 `extractedText=null` 的真实 PDF 文件理解闭环。

## 第 237 阶段：Android 单文档系统分享入口（完成）

- `ACTION_SEND` 从原单文本/单图片扩展到 `DocumentAttachmentPolicy` 已支持的 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX 和 XLSX 精确 MIME；仍不声明 `ACTION_SEND_MULTIPLE`、通配 MIME、GIF、ZIP 或任意文件。
- 解析层只接受单个小写 `content://` URI，并把可选 `EXTRA_TEXT` 作为普通可编辑说明。`text/plain` 无 URI 时继续是文本分享，有 URI 时按 TXT 文档；EXTRA_STREAM 与 ClipData 同 URI 属于兼容重复，不同 URI、多项 ClipData、缺失 URI、非 content URI 和未知 MIME 全部 fail-closed。
- 打开分享后复用既有新会话、草稿冲突确认和 `attachDocument()`；真正字节读取仍由 `DocumentAttachmentReader / DocumentAttachmentPolicy` 完成 8 MB、UTF-8、PDF 1–50 页、扩展名/MIME/签名和 OpenXML ZIP/OPC 结构校验。读取失败或用户移除文档会清除分享来源，不自动发送、调用模型、创建 Run、写入 Room 或扩大工具权限。
- 聚焦 JVM `SharedDraftParserTest 6/6 + SharedDraftProjectionPolicyTest 3/3 + DocumentAttachmentPolicyTest 8/8`（`17/17`）通过，Debug/AndroidTest APK 构建成功。仅 Redmi `wsvwypiz7xwslvl7` 的 Manifest、Markdown 成功/缺失文档、文档标签和文本/图片回归合计 `5/5`、`8.645s`，文档 corpus gate `1/1`、`3.186s`；主应用数据和 Provider 配置保留，未向模拟器发送目标命令。
- 本阶段没有新增 Room Schema、Android 权限、后台 URI 持久化、自动 Agent 执行或多附件能力；完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

第 238 阶段已完成分享 Markdown 经用户明确 `/agent` 发送后的真实 Responses 文档理解闭环。

## 第 236 阶段：系统分享文本到受控单日全天日程（完成）

- Android `text/plain ACTION_SEND` 继续先进入普通可编辑草稿。只有用户点击“创建全天日程”后，`SharedTextAgentDraftPolicy` 才从唯一明确的标题和规范 `yyyy-MM-dd` 日期生成 `/agent calendar.create_all_day_event` 草稿；转换不会自动发送、调用模型、创建 Run 或写入 Provider。
- 缺失、重复、非法或非规范日期全部 fail-closed；分享中只要出现开始、结束或时区等定时字段，全天入口就拒绝，不从定时事件猜测全天日期。五个分享动作继续分行展示，附件、发送、会话加载与个人任务状态门禁没有放宽。
- 正式发送后仍复用既有 `calendar-create-all-day / calendar.create_all_day_event`、逐次审批、UTC 当日零点到次日零点、Provider 回读、稳定事件 ID、答案级导航和按回执精确清理；本阶段没有新增 Tool/Skill、Room Schema、权限、Workflow 或后台能力。
- 聚焦 JVM `SharedTextAgentDraftPolicyTest 9/9`、Debug/AndroidTest APK 构建和差异检查通过；Redmi 入口组合 `4/4`、`7.989s`，真实 Provider 双测试 `2/2`、`21.034s`。
- 最终 Run `run-b038b22d-5697-4460-96f7-88c8b8588755` 的唯一 `calendar.create_all_day_event` 为 `APPROVED / PASSED / COMMITTED`，回执、当前 Provider 回读、消息 Tool part 与答案级导航绑定 `calendar-92`；缺字段样本输出 `runCreated=false`，旧 Run 完整摘要不变。事件只按回执 ID 且标题/UTC 全天边界一致时删除，临时 Profile/会话和本轮本地日历按身份清理。
- 未向 `emulator-5554` 发送目标命令，也未运行完整 JVM、Lint、Release 或全量 instrumentation。

第 237 阶段已完成单份受支持文档的系统分享草稿入口。

## 第 235 阶段：系统分享文本到受控系统日程闭环（完成）

- Android `text/plain ACTION_SEND` 继续先进入普通可编辑草稿。只有用户点击“创建日程”后，`SharedTextAgentDraftPolicy` 才从唯一明确的标题、带偏移起止时间和 IANA 时区生成 `/agent calendar.create_event` 草稿；转换不会自动发送、调用模型、创建 Run 或写入 Provider。
- 缺失、重复、逆序、无偏移、固定偏移时区或时区规则不一致均在发送前 fail-closed。缺时区 Redmi 样本保留原分享和来源标记，没有新增用户消息或 Run；四个分享动作按两行保持窄屏可达。
- 用户仍需明确发送并批准唯一写入。Redmi 真实 Run `run-373fbac0-77a4-4f9c-bc52-134aecbeb550` 完成 `APPROVED / COMMITTED / PASSED`；`calendar-91` 由当前 Provider 回读标题、起止、`Asia/Shanghai`、非全天和非重复事实，消息 Tool part 的答案级入口绑定同一稳定 ID。
- 事件只按回执稳定 ID 且删除前四字段一致时清理；临时 Profile/会话及必要时创建的本地日历清理，Run 审计和旧 Run 不变证据保留。生产 Tool/Skill、日历权限、Room v36、Workflow 与后台能力未扩张。
- 聚焦 JVM `6/6`、Debug/AndroidTest APK、Redmi 入口 `4/4`（`8.06s`）、真实 Provider `2/2`（`32.026s`）和文档 corpus `1/1` 通过；未运行完整 JVM、Lint、Release 或全量 instrumentation，也未向模拟器发送目标命令。

下一阶段继续选择一个新的单一用户任务场景。可优先评估“明确标题 + 唯一日期”的分享文本到现有 `calendar.create_all_day_event`，但必须作为独立全天契约，继续保留可编辑草稿、再次发送、逐次审批、UTC 全天边界、Provider 回读和精确清理；多日、重复、参与人、提醒、后台日历、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 234 阶段：系统分享文本到显式长期记忆闭环（完成）

- Android `text/plain ACTION_SEND` 继续先进入普通可编辑草稿。只有用户点击“保存为记忆”后，`SharedTextAgentDraftPolicy` 才生成明确的 `/agent 使用 memory.remember ...` 草稿；转换不会自动发送、调用模型、创建 Run 或写入 Room。
- 分享来源区将来源标签与“转为任务 / 保存为笔记 / 保存为记忆”三个动作分行，保证 Redmi 窄屏仍可完整点击。记忆入口与既有纯文本门禁一致：图片/文档、附件处理中、消息发送中、会话加载中或个人任务待确认/运行中均不开放。
- 用户仍需明确发送，并对唯一 `memory.remember` 逐次批准。Redmi 真实 Run `run-51d3c846-5bb4-43ba-b904-906b61b58047` 完成 `APPROVED / COMMITTED / PASSED`；回执绑定 `memory-e6c7432a-a94f-4955-85fb-81bdfd7a6400`，当前 Room 回读、会话 Tool Message 的 `VERIFIED` 投影和答案级“查看记忆”稳定身份一致。模型只允许规范空白，不得增删分享文字信息。
- 临时记忆、Profile、会话与撤销文件按回执稳定 ID 清理，Run/Approval/Tool Ledger 审计保留，最近旧 Run 的完整稳定摘要不变。生产 `memory.remember`、`personal-memory`、Room v36、权限、Workflow 和后台能力均未扩张。
- 聚焦 JVM `SharedTextAgentDraftPolicyTest 3/3`、Debug/AndroidTest APK 构建、Redmi 入口 `3/3`、真实 Provider `1/1`（`19.732s`）与最终文档 corpus `1/1`（`3.288s`）通过；test APK 已卸载，crash buffer 为空。未运行完整 JVM、Lint、Release 或全量 instrumentation，也未向模拟器发送目标命令。

下一阶段继续选择能增加个人 Agent 真实任务覆盖的单一场景；优先评估显式外部内容到日程等现有权威 Store 的受控入口，但只有在缺失字段、权限、审批和当前状态验证能够 fail-closed 时才立项。剪贴板常驻读取、后台 Intent 自动执行、任意 deep link、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 233 阶段：提醒结果一次性安全导航（完成）

- 终态 ScheduledTask 通知不再只打开应用首页。Notifier 为当前 Task 签发 32 字节随机 Base64URL token，应用私有 Store 保存 `token -> workflowId / scheduledTaskId / workflowRunId / expiresAt`，有效期 24 小时；同一 Task 重新通知会撤销旧 token。
- PendingIntent 显式指向 `MainActivity`，只携带 token，并使用 `FLAG_IMMUTABLE + FLAG_ONE_SHOT`。Activity 冷创建、系统恢复和 `singleTop onNewIntent()` 均走同一消费链；URI grant、data、ClipData、selector、错误 action、格式错误、过期、重放和随机伪造全部拒绝。
- token 原子消费后，ViewModel 仍从当前 Room 回读 ScheduledTask、Workflow 与 Workflow Run，要求 Task 终态、Workflow 存在、三段稳定 ID 一致，且非空 Run 仍存在并反向绑定同一 Workflow/Task；任何 Room 读取/解析异常只拒绝本次导航。通过后应用壳打开 Workflow 管理页、自动展开目标 Workflow，并以可测试的选中语义和背景高亮对应调度实例及 Workflow Run；一次性导航版本保证同一稳定目标的新通知也能覆盖用户先前的折叠状态。Intent 中没有任何可由外部直接伪造的业务 ID。
- 聚焦 JVM `19/19`、Debug/AndroidTest APK 构建通过。仅 Redmi `wsvwypiz7xwslvl7` 最终组合运行 `6/6`（`12.46s`）；有效 token 冷启动、真实 PendingIntent 热启动/one-shot、伪造 token、Run 删除后拒绝、目标 Task/Run 精确标记、同目标新导航重新展开和系统分享冷/热回归均通过。
- 热路径真实证据为 Workflow `workflow-17a65b57-4daf-46eb-b4b0-456260db20d6`、Task `scheduled-task-6e203265-a9c8-477b-9f26-de261211f727`、Workflow Run `workflow-run-stage233-e1faaa72-c891-4fab-92ee-64bc666980a3`、`navigationVersion=1`。测试夹具、通知和私有 token 状态均在 `finally` 清理。

第 233 阶段完成后，下一阶段回到能直接增加个人 Agent 任务覆盖的单一用户场景，不继续为通知入口叠加远程 Channel、任意外部 deep link 或后台设备自动化。

## 第 232 阶段：系统分享文本到一次性应用内提醒（完成）

- 第 231 阶段的系统分享任务入口已与既有一次性提醒链真正贯通：用户显式转为任务、生成并确认 `ONCE / 1 分钟` 单步计划后，生产 WorkManager 到点创建后台 Workflow/Agent Run；确认前没有调度或执行事实。
- Redmi Task `scheduled-task-e8d56d3f-2cac-4073-befa-9c3d98233a23`、Workflow Run `workflow-run-a418e6a4-9723-4730-bdac-881fbc803f08` 与 Agent Run `run-b2187efb-4d4c-4da1-8563-3786373aeccc` 均完成。唯一 `app.current_time` 结果为 `PASSED`，目标级结论 `VERIFIED`，审批数为 0，结果通知真实可见。
- 临时业务数据和通知已清理、原选择恢复，验收 Workflow 停用，Task/Run 审计保留，旧 Agent/Workflow/ScheduledTask 稳定事实不变。本阶段没有引入 Exact Alarm、Foreground Service、后台分享自动执行或新的生产工具面。

下一阶段（第 233 阶段）：让提醒完成通知精确打开对应 Workflow/Run 或任务结果；使用应用内部可校验、短生命周期导航身份，拒绝外部应用伪造 workflowId。MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 231 阶段：系统分享文本到显式个人任务草稿（完成）

- Android `text/plain ACTION_SEND` 继续遵守草稿冲突确认；分享被打开进入编辑器后成为普通可编辑草稿并退出旧个人任务模式。只有用户点击“转为任务”才进入个人任务编辑态，转换不发送、不请求模型、不生成计划，也不创建 Workflow/Run。
- 用户仍需明确生成并确认计划。Redmi 真实计划只有一个 `app.current_time` 步骤且没有提醒；Workflow Run `workflow-run-e923d6fb-6e35-435b-9c52-df3fa49c2043`、Agent Run `run-feb8faa9-4842-4f32-8dcb-63eab27bfe1e` 均完成，结果为 `PASSED / VERIFIED`，审批数为 0。
- 最近旧 Agent/Workflow Run 的稳定摘要保持不变；临时 Profile/会话已删除、原选择恢复，验收 Workflow 已停用但审计保留。生产 Tool/Skill、Room v36、后台分享、精确定时和任意 Intent 边界没有扩张。

下一阶段（第 232 阶段）：让同一显式任务入口完成一次性提醒闭环，复用现有 WorkManager 非精确定时、计划确认和任务结果验证；暂不引入 Exact Alarm、Foreground Service、后台分享自动执行、MCP、远程 Channel、多 Agent 或本地模型。

## 第 230 阶段：系统分享文本到显式 Agent 笔记草稿（完成）

- `text/plain ACTION_SEND` 延续第 100 阶段安全边界，先进入可编辑新会话草稿；用户只有点击“保存为笔记”才得到明确 `/agent notes.create` 草稿，仍需再次点击发送和逐次批准写入。
- 转换入口只对纯文本分享开放；图片/文档、附件读取、发送中或会话加载中均不显示。转换不会创建 Run、调用模型或执行工具，用户编辑仍可退出分享来源状态。
- Redmi 真实闭环 Run `run-e2b833d7-0e9b-43f3-8589-86874dd049e3` 为 `COMPLETED`，唯一审批 `APPROVED`，`notes.create` 回执 `COMMITTED` 且 typed verification `PASSED`；当前 Store 回读成功，临时笔记/Profile/会话精确清理。
- 前一条成功 Run `run-6d9fef60-635a-4fb7-b9e4-3fd165770fc8` 的稳定摘要在第二轮前后不变。生产 `notes.create`、`local-notes`、Room v36、Workflow 和后台边界未扩张。

下一阶段（第 231 阶段）：继续选择完整用户任务覆盖，而不是增加另一项低价值状态查询；候选必须保留显式用户意图、最小权限、逐次审批和权威结果验证。图片分享自动进入 Agent、剪贴板读取、后台 Intent、任意 App、MCP、远程 Channel、多 Agent 和本地模型仍不前置。

## 第 229 阶段：设备观察真实前台自然语言闭环（完成）

- Redmi `wsvwypiz7xwslvl7` 使用临时最小 Profile，只允许 `device.open_app / device.snapshot` 和 `device-control`；用户在真实 `MainActivity` 发送自然语言 `/agent` 目标，并对 `com.android.settings` 打开动作逐次审批。
- 最终 Run `run-6074ad3d-04bb-4cb6-8f10-b8e555570142` 为 `COMPLETED`，唯一审批 `APPROVED`，两项 ToolResult 均 `PASSED`；`open_app` 具备 Executor 验证，随后 Snapshot 从当前 Settings 窗口读取 29 个有界节点、0 个脱敏节点且未截断。
- 消息投影保持分层语义：设备动作是 `VERIFIED`，只读 Snapshot 是 `READABLE_ONLY`。旧 Run `run-e615ff22-6c4a-447d-bc08-bc49b9c4f85b` 的稳定摘要在新 Run 前后不变；临时 Profile/会话清理后，新旧 Run 审计均保留。
- 本阶段没有新增生产 Tool/Skill、Room、Workflow 或后台能力，只补齐“健康检查之后由自然语言进入审批设备动作，再由操作后观察形成可信答案”的真实应用壳证据；不外推到任意 App。

第 230 阶段已完成系统分享文本到显式 Agent 笔记草稿和真实写入闭环；下一阶段进入第 231 阶段。

## 第 228 阶段：设备 Agent 健康只读切片（完成）

- 新增前台直接 Agent 工具 `app.get_device_agent_health` 与独立 `device-agent-health` Skill；工具无参数、SAFE、仅前台直接调用，不需要审批，不读取窗口、包名、节点、文本或设备动作。
- 健康结果严格限制为四态：未启用、未授权、服务断连、READY。Workflow、后台和无上下文均隐藏该工具；查询不会触发 snapshot、节点引用或设备动作。
- 聚焦 JVM 回归、Debug/AndroidTest APK 构建和 Redmi `wsvwypiz7xwslvl7` 真实 Provider 分段验收通过；真实 Run 完成、ToolResult `PASSED`、审批数为 0，敏感字段边界通过。

第 229 阶段已完成设备观察链的真实前台自然语言、审批和权威账本回读；下一阶段进入第 230 阶段。

## 第 227 阶段：进程重启后的审批恢复真实闭环（完成）

- Redmi `wsvwypiz7xwslvl7` 在 `WAITING_APPROVAL` 期间被强制结束并重新启动，应用恢复同一 `notes.create` 审批卡，显示“批准并继续”。
- 用户批准后原 Run `run-65a2efbf-4bf9-44b3-81d9-71c71cf21cfb` 完成 `COMPLETED / APPROVED / PASSED / COMMITTED`；清理临时笔记/Profile/会话，保留执行审计。
- 该阶段未新增生产能力，验证范围保持 Redmi 定向前台恢复，不扩展后台自动化或 Release。

下一阶段（第 228 阶段）：回到个人 Agent 主线，选择新的受控能力切片。

## 第 226 阶段：Skill 草稿发送、审批与本地笔记真实前台闭环（完成）

- 第 225 阶段生成的 `/agent ...` 草稿必须由用户明确发送后才进入正式 Run；Redmi `wsvwypiz7xwslvl7` 真实 Run `run-b2823b2d-e56a-4931-807d-78c769dc51ef` 记录 Profile 与 `local-notes` Skill 选择。
- `notes.create` 在真实审批卡停留，用户点击“批准执行”后完成 `APPROVED / PASSED / COMMITTED`，Tool Message 与 Ledger 一致。
- 临时笔记、Profile、会话已精确清理，Run 审计保留；仅 Redmi 分段验证和 Debug/AndroidTest APK 构建通过，未扩大到完整测试或 Release。

第 227 阶段已补齐进程重启后的审批恢复闭环。

## 第 225 阶段：Agent Skill 试用真实应用壳闭环（完成）

- 仅在 Redmi `wsvwypiz7xwslvl7` 使用真实 `MainActivity`，从设置根页滚动进入 Skill 管理，选择当前 Profile 已授权的 SAFE `conversation-recall` 示例并回到对话。
- 当前 `agent-profile-default`、选中会话、会话消息摘要和最近 100 条 Run 完整详情摘要前后不变；输入框得到规范 `/agent ...` 草稿，但没有自动发送、模型调用、Run 或工具执行。
- AndroidTest 编译、Debug/AndroidTest APK 和 Redmi 单项 `OK (1 test)`（`7.624s`）通过；Room v36、生产 Tool/Skill、审批、Workflow、后台和 Shadow 边界不变。

下一阶段（第 226 阶段）：只在用户明确发送草稿后验证正式 Run 创建、Skill 选择和逐次审批，继续禁止管理页点击自动执行。

## 第 224 阶段：Agent Skill 直接试用入口（完成）

- Skill 管理页从每项自身 `triggerExamples` 展示最多 3 条去重、非空示例，让既有个人 Agent 能力从配置清单变成可发现入口。
- 试用资格同时绑定 Skill 启用状态、当前 Agent Profile 的 Skill/工具白名单和当前工具注册表；状态漂移、陈旧示例、未授权 Skill 或缺失工具均 fail-closed。
- 点击只把规范 `/agent ...` 填入对话并关闭个人任务模式、回到对话根页，不自动发送、不调用模型、不创建 Run，也不改变后续逐次审批和 Tool Ledger 边界。
- 聚焦 JVM `17/17`、Debug/AndroidTest APK 与仅 Redmi 页面 `OK (4 tests)`（`6.085s`）通过；Room v36、生产 Tool/Skill、权限与后台能力不变。

第 225 阶段已完成“设置 -> Skill 示例 -> 对话草稿”真实应用壳闭环，发送仍由用户决定。

## 第 223 阶段：受控单日全天日程真实前台闭环（完成）

- Redmi 当前 Provider 下，真实自然语言目标只调用 `calendar.create_all_day_event`，经人工审批后完成；Run `run-7614212d-ebf7-4bbd-8be9-c3196b9a3e4b`、ToolCall `tool-call-15700c37-2932-424a-91b0-05e9a20bf312` 为 `COMPLETED / APPROVED / PASSED / COMMITTED`。
- 答案级“查看日程”从当前 Calendar Provider 回读稳定事件 `calendar-90`：标题 `stage223_all_day_1786293137009`、日期 `2026-08-15`、全天、`UTC`、非重复；不是展示历史模型正文。
- 旧 Run `run-73b6e1ca-2b73-4a39-a517-e2461afa5c43` 完整详情未被改写。事件按精确 ID 删除，临时 Profile/会话清理并恢复原选择，新 Run/Approval/Tool Ledger 审计保留。
- 本阶段只新增 AndroidTest 验收夹具；生产能力、Room v36、旧 Profile、Workflow 和后台边界均未扩大。聚焦 AndroidTest 编译/构建与 Redmi prepare/audit/cleanup 单项通过。

下一阶段（第 224 阶段）：重新选择新的用户可体验个人 Agent 主线；不顺带开放多日、重复、参与人、提醒或后台日程。

## 第 222 阶段：受控单日全天日程（完成）

- 新增独立 `calendar.create_all_day_event(title, date)` 与 `calendar-create-all-day` Skill，把个人 Agent 的系统日历创建范围从定时事件扩展到一次性单日全天事件；旧 Skill/Profile 不自动扩权。
- 日期严格为 `yyyy-MM-dd`，Provider 使用 UTC 当日零点、排他的次日 UTC 零点和 `ALL_DAY=1`。写入继续逐次审批，以 ToolCall ID 幂等，回读必须同时匹配标题、日期边界、时区和全天标记。
- `VERIFIED` 成功结果携带唯一稳定事件 ID，答案级入口同时绑定标题和日期并回当前 Provider 查看；多日、重复、参与人、提醒和后台日程继续关闭。
- 聚焦 JVM `126/126`、Debug/AndroidTest APK 和仅 Redmi Calendar Provider 单项 `OK (1 test)`（`0.192s`）通过，测试事件与测试包已清理。

下一阶段（第 223 阶段）：在 Redmi 当前 Provider 下完成真实自然语言创建、人工审批、Tool Ledger、答案级当前日程查看与精确清理；旧 Run 保持不变。

## 第 221 阶段：前台长期记忆安全删除真实闭环（完成）

- Redmi `wsvwypiz7xwslvl7` 当前 Provider 下，真实自然语言 Run `run-73b6e1ca-2b73-4a39-a517-e2461afa5c43` 严格执行 `memory.search -> memory.get -> memory.delete`；人工审批 `APPROVED`，三项结果 `PASSED`，删除回执 `COMMITTED`，稳定 memory ID `memory-ee8cc2f1-27c0-4756-91f6-804ddf2608cf` 在当前长期记忆页不可见。
- 临时 Profile、记忆、撤销文件、验收消息已清理，Run/Approval/Tool Ledger 保留。验收夹具已增加会话边界回归，恢复复用的原空会话 `conversation-1786204146694` 为无消息“新会话”，不再误删用户会话。
- `assembleDebugAndroidTest` 与 Redmi 定向修复核对通过；测试包已卸载，未运行完整 JVM、Lint、主 APK、Release 或全量 instrumentation。

下一阶段（第 222 阶段）：继续个人 Agent 主线，选择一个用户可直接体验的前台窄能力闭环；不提前开放后台自动化、精确定时、MCP、远程 Channel、多 Agent 或本地模型。

## 第 220 阶段：前台长期记忆安全删除（完成）

- 新增生产 `memory.delete(memory_id)` 与独立 `personal-memory-delete` Skill，仅向开启长期记忆召回的前台 `DIRECT` Agent 暴露；不加入 Workflow、后台、Legacy Run 或旧 Profile。
- Registry 在同一 Run 内签发短生命周期授权，严格要求唯一 `memory.search -> memory.get -> memory.delete` 且三步使用同一稳定 `memory-UUID`。跳过搜索/详情、多结果、ID 漂移、Run 切换、关闭召回或读取失败都会清空授权并拒绝删除。
- 删除为 `REQUIRES_APPROVAL + EXECUTOR_VERIFIED`，ToolCall ID 同时作为幂等键，memory ID 作为稳定 operation ID；回执为 `COMMITTED`，恢复契约为 `IDEMPOTENT_BY_KEY + DENY`。已提交恢复只读核对 Room operation ledger 和当前不可见，不重新调用 DELETE。
- Room 在同一事务内写入删除 operation 并删除主记录与 FTS；用户撤销后同 ID 再次存在时，旧 operation 验证返回 `MEMORY_STILL_EXISTS`。没有新增表或 Migration，Room 保持 v36。
- 聚焦 JVM `119/119`、Debug/AndroidTest APK 构建成功；仅 Redmi 分别通过生产 Registry 删除链、Room 跨重开删除账本和文档 corpus gate，测试包已卸载。没有真实 Provider 自然语言 Run、人工审批 UI 或答案级当前不可见验收。

下一阶段（第 221 阶段）：在 Redmi 当前 Provider 下完成真实自然语言 `memory.search -> memory.get -> memory.delete`，人工批准删除，核对 Tool Ledger 的三步稳定 ID、`COMMITTED/PASSED` 证据与长期记忆页当前不可见，并精确清理阶段夹具；旧 Run 保持不变。

## 第 219 阶段：真实前台存储状态 Agent Run（完成）

- 在 Redmi 当前 Provider 下使用临时最小 Profile，正式 `AgentRunUseCase` 根据自然语言目标唯一调用 `app.get_storage`；结果 typed `PASSED`，Run 为 `COMPLETED`，审批数为 0。
- 验收确认 `storage-status` 能从真实模型路由到本地权威容量摘要，并把总容量、可用空间和使用率形成最终回答；Provider 凭据、文件路径、应用数据和设备身份不进入 Tool Ledger 或回答。
- 仅 Redmi `wsvwypiz7xwslvl7` 真实 Provider instrumentation `OK (1 test)`（`13.46s`）通过；测试包已卸载，模拟器未接收目标命令，未运行完整 JVM、Lint、Release 或全量 instrumentation。
- 下一阶段停止继续横向增加同类设备状态字段，重新选择能扩大真实个人任务范围的单一能力；后台设备自动化、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 218 阶段：前台只读存储状态（完成）

- 新增 `app.get_storage` 和独立 `storage-status` Skill，工具无参数、`SAFE`、仅前台直接 Agent 可用，不需要 Android 权限，也不进入 Workflow 或后台设备自动化。
- 应用只投影当前数据分区总容量、可用空间和使用率；不读取文件名、路径、应用数据或设备身份，统计不可用或异常时 fail-closed。
- 聚焦 JVM `116/116`、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation `OK (1 test)`（`0.222s`）通过；测试包已卸载。未向模拟器发送目标命令，未运行完整 JVM、Lint、Release 或全量 instrumentation。
- Room v36、旧 Profile/Run、Workflow、后台执行和高级生态边界保持不变；下一阶段完成真实 Provider 的自然语言存储状态闭环，再选择新的个人 Agent 任务范围。

## 第 217 阶段：真实前台电量/网络双状态 Agent Run（完成）

- 在 Redmi 当前 Provider 下使用临时最小 Profile，正式 `AgentRunUseCase` 根据自然语言目标完成 `app.get_battery -> app.get_connectivity` 两项只读调用；两项结果均 typed `PASSED`，Run 为 `COMPLETED`，审批数为 0。
- 验收确认个人 Agent 主链能够把自然语言目标路由到两个独立 Skill，并把当前本地事实汇总为最终回答；Provider 凭据、Profile 内部 ID 和设备身份不进入 Tool Ledger 结果或最终回答。
- 仅 Redmi `wsvwypiz7xwslvl7` 真实 Provider instrumentation `OK (1 test)`（`24.087s`）通过；未使用 Pixel_9、未运行完整 JVM、Lint、Release 或全量 instrumentation。下一阶段继续选择能扩大真实个人 Agent 任务覆盖面的单一窄闭环，不引入 Provider 健康 Agent Tool、后台设备自动化或高级生态能力。

## 第 216 阶段：前台只读网络状态（完成）

- 新增 `app.get_connectivity` 和独立 `connectivity-status` Skill，工具无参数、`SAFE`、仅前台直接 Agent 可用，不需要 Android 权限，也不进入 Workflow 或后台设备自动化。
- 应用只从当前活动网络能力投影是否连接、传输类型和系统判定的互联网可达性；不返回 SSID、IP 地址、运营商、Provider 配置或凭据，网络栈不可用或异常时 fail-closed。
- 聚焦 JVM `114/114`、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation `OK (1 test)`（`0.261s`）通过；测试包已卸载。未使用 Pixel_9、未运行完整 JVM、Lint、Release 或全量 instrumentation。
- Room v36、旧 Profile/Run、Workflow、后台执行、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型边界保持不变；下一阶段继续选择一个能直接提升“自然语言目标 -> 可验证结果 -> 权威事实查看”的窄能力切片。

## 第 215 阶段：前台只读电池状态（完成）

- 新增 `app.get_battery` 和独立 `battery-status` Skill，工具无参数、`SAFE`、仅前台直接 Agent 可用，不需要 Android 权限，也不进入 Workflow 或后台设备自动化。
- 应用只从当前电池广播投影电量百分比、是否充电和供电方式；广播不可用、数据无效或 OEM 读取异常时 fail-closed，不返回设备标识、应用列表、Provider 配置、电池温度或健康信息。
- 聚焦 JVM `112/112`、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation `OK (1 test)`（`0.198s`）通过；测试包已卸载。未使用 Pixel_9、未运行完整 JVM、Lint、Release 或全量 instrumentation。
- Room v36、旧 Profile/Run、Workflow、后台执行、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型边界保持不变；下一阶段继续选择一个能直接提升“自然语言目标 -> 可验证结果 -> 权威事实查看”的窄能力切片。

## 第 214 阶段：Redmi 当前 Provider 驱动的 Agent Profile 隐私验收（完成）

- `RealProviderAgentProfileInstrumentedTest` 新增仅 AndroidTest 的 `agentProfileUseStoredProvider=true` 入口，从 Redmi 当前选中 Provider 读取真实配置；显式参数模式仍用于隔离环境，不改变生产配置行为。
- Redmi Run `run-b9186054-3f0c-405e-ba62-2afd9f4c75f7` 为 `COMPLETED`，唯一 `agent.get_profile` 调用和结果均通过 typed `PASSED`；只显示 Agent 名称、模型、Responses API 模式和记忆召回状态，Provider URL、API Key、系统提示词、内部 ID 和工具白名单保持不可见。
- 仅构建/安装 AndroidTest APK 并在 Redmi `wsvwypiz7xwslvl7` 定向运行，结果 `OK (1 test)`，测试包已卸载。未使用 Pixel_9、未运行完整 JVM、Lint、Release 或全量 instrumentation。
- 下一阶段继续选择一个能直接提升“自然语言目标 -> 可验证结果 -> 权威事实查看”的单一个人 Agent 窄闭环；旧 Run、后台设备自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型保持现有边界。

## 第 213 阶段：当前应用信息只读验收（完成）

- `app.get_info` 已在 Redmi `wsvwypiz7xwslvl7` 通过生产 Registry 定向 instrumentation，当前包信息四字段投影和敏感字段隔离均为 `OK (1 test)`。
- 本阶段不需要 Provider 网络，不新增权限、Room Schema、Workflow 或后台能力；第 214 阶段已完成第 212 阶段的真实 Provider 重跑，下一步进入新的个人 Agent 能力切片。

## 第 212 阶段：前台 Agent Profile 隐私验收（代码与 Redmi 真实验收完成）

- `agent.get_profile` 的 Redmi-only 定向 AndroidTest 已接入正式 `AgentRunUseCase`，以最小白名单验证前台 `DIRECT` 的真实规划、执行和 Room Tool Ledger 投影；输出只允许 Agent 名称、模型、API 模式和记忆召回状态。
- Provider URL、API Key、系统提示词、内部 Profile ID 和工具白名单均有 fail-closed 断言；旧 Profile 不自动扩权，`默认 Agent` 只通过显式白名单获得该工具。
- 编译和 Debug/AndroidTest APK 构建通过；第 214 阶段改用 Redmi 当前选中 Provider 重跑后已闭环。此前显式兜底域名运行失败仅作为外部网络阻塞记录，不覆盖本次真实通过结论。

## 第 211 阶段：真实历史会话搜索、当前正文与答案级导航验收（完成）

- Redmi 首条 Run `run-4fae0edb-af9a-437b-836e-c8ca95ffaf00` 已选择 `conversation-detail@1` 并完成 `app.search_conversations -> app.get_conversation`，但搜索同时命中当前验收会话和历史目标；旧 Run 保持 `COMPLETED`、两项 typed `PASSED` 和原结果不变。
- 生产搜索现在从 RunContext 取得当前会话 ID，并在应用 limit 前排除它。修复后 Run `run-25bd9d0a-90a9-41b2-adbb-1cca0ddd62ab` 只返回唯一目标 `conversation-stage211-target-20260808`，再以同一稳定 ID 从当前 Room 读取用户/助手正文；全链 SAFE、零审批、两项 `PASSED`。
- Run 后把目标助手正文从 `before` 改成 `after`，历史 Tool 卡继续冻结旧结果；点击“查看会话”显示当前 Room 的 `after`，且没有新增 Run。这补齐了真实前台“搜索摘要 -> 稳定 ID -> 当前正文 -> 当前页面”的完整可清理闭环。
- 夹具会话、临时 Profile、快照和测试包已精确清理，原 Profile/会话选择恢复，两条 Run 审计保留；Room v36、权限、Workflow、后台和发布边界不变。
- 第 212 阶段优先完成真实前台 `agent.get_profile` 验收，核对本次 Run 冻结的 Agent 名称、模型、API 模式和记忆状态，并证明 Provider URL/API Key、系统提示词、内部 ID 与工具白名单不可见；`app.get_info` 留在后续独立切片。

## 第 210 阶段：真实前台系统日程删除、当前不可见与清理验收（完成）

- Redmi `wsvwypiz7xwslvl7` 已完成真实前台 `/agent calendar delete` 人工审批闭环。Run `run-fa9e0a15-db83-4db6-8919-501566d60ebf` 为 `COMPLETED`，唯一 `calendar-delete` 严格执行 `calendar.search_events -> calendar.get -> calendar.delete_event`，稳定 ID、当前指纹和 `scope=event` 全链一致。
- 删除审批为 `APPROVED`，三项 ToolResult 均 typed `PASSED`；删除结果具备 Executor 验证、`RESTART_REQUIRED` 和 `COMMITTED` 回执。当前 Provider 为 NotFound，历史搜索卡的“查看日程”也只显示目标已不存在，没有回放旧详情。
- 夹具事件、阶段会话、临时 Profile、快照和测试包已精确清理，原 Profile/有效会话选择恢复，真实 Run 审计保留。由此系统日程的真实前台创建、修改、删除和当前事实查看均已形成独立可清理闭环；重复系列/occurrence 与后台日程代理仍关闭。
- 第 211 阶段优先完成真实前台 `app.search_conversations -> app.get_conversation`、稳定会话 ID、当前 Room 正文读取和答案级“查看会话”验收；MCP、远程 Channel、多 Agent、本地模型、精确定时和 Foreground Service 继续后置。

## 第 209 阶段：真实前台系统日程修改、查看与清理验收（完成）

- Redmi `wsvwypiz7xwslvl7` 已完成真实前台 `/agent` 的 `calendar.search_events -> calendar.get -> calendar.update_event` 三步人工审批闭环。首条不完整输入只形成两步只读 Run；第二条 Run 完成 `stage209_calendar_20260808_after / 11:20–12:00` 更新；第三条 Run 明确选中 `calendar-update`，使用中间指纹再次更新为 `stage209_final / 13:10–13:50`。
- 两条修改 Run 的审批均为 `APPROVED`，三项 ToolResult 均为 typed `PASSED`；两次 UPDATE 都有 Executor 验证、`RESTART_REQUIRED` 和 `COMMITTED` 回执。答案级“查看日程”从当前 Calendar Provider 回读稳定 ID `calendar-85`、最终标题/时间、`Asia/Shanghai`、非全天和不重复。
- 三条 Run 审计保留且旧 Run 未被改写；夹具事件、阶段会话、临时 Profile、测试包和快照均精确清理，原 Profile 与原会话选择恢复。本阶段没有生产能力、Room、Workflow、权限或后台变化。
- 第 210 阶段优先完成真实前台日程删除的 `search -> get -> delete_event`、逐次审批、提交后不可见回读和精确清理；重复系列/occurrence、后台代理、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 208 阶段：真实前台系统日程创建、查看与清理验收（完成）

- Redmi `wsvwypiz7xwslvl7` 已完成真实前台 `/agent calendar.create_event` 人工审批闭环。Run `run-0850939c-00dd-497a-b70c-4af0306c2168` 为 `COMPLETED`，审批 `APPROVED`，创建结果具备 Executor 验证、typed `PASSED`、`COMMITTED` 回执和稳定事件 ID `calendar-84`。
- 答案级“查看日程”从当前 Calendar Provider 二次读取标题、起止、时区、全天和重复状态；遗漏 `/agent` 的普通聊天没有产生副作用，证明聊天与 Agent 执行入口仍保持明确边界。
- 测试事件、阶段会话和精确四条误入消息已清理，专用 E2E Profile 恢复为原两项只读工具、空 Skill 和关闭长期记忆；阶段 Run、审批与账本审计保持不变。本阶段没有生产 Tool/Skill、Room Schema、权限、Workflow 或后台能力变化。
- 第 209 阶段优先完成真实前台 `calendar.search_events -> calendar.get -> calendar.update_event` 的稳定 ID/指纹传递、逐次审批、当前 Provider 新状态查看和精确清理。日程删除留到后续独立阶段；重复系列/occurrence、后台日程代理、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 207 阶段：真实前台本地笔记删除、失败边界与清理验收（完成）

- Redmi `wsvwypiz7xwslvl7` 已完成真实前台 `/agent` 的 `notes.search -> notes.get -> notes.delete` 人工审批闭环。最终 Run `run-e520f307-96fd-4bc9-b4e8-3b9425c405d4` 为 `COMPLETED`，三项 typed verification 均为 `PASSED`，删除审批 `APPROVED`、Executor 回读不可见、回执 `COMMITTED`，当前 Store 刷新后为 0 条。
- 首次 Run `run-281935cb-a3b5-4661-8be8-264da24ae39b` 证明了重要失败边界：删除已经提交并验证后，模型再次提出重复搜索，Run 按真实事实保留为 `BUDGET_EXHAUSTED`；已提交 ToolResult、回执、Store 不可见与旧 Run 审计均未被改写。后续删除由独立 Run 完成，没有原地恢复或重放旧执行栈。
- 中间只读 Run、最终成功 Run、4 个临时会话与两个 tombstone 夹具均已核对；临时会话/Profile 精确清理后，5 条阶段 Run 仍保留，原 Profile 当前选中且总数恢复为 2。本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变化。
- 第 208 阶段优先完成真实前台 `calendar.create_event` 的人工审批、稳定事件 ID、答案级当前 Calendar Provider 查看和精确清理；同一阶段不扩展日程修改/删除、后台日程代理、精确定时或 Foreground Service。MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 206 阶段：真实前台本地笔记编辑、版本递增与清理验收（完成）

- Redmi `wsvwypiz7xwslvl7` 已完成真实前台 `/agent` 的 `notes.search -> notes.get -> notes.update` 链；正式 Profile 只开放四项笔记工具和 `local-note-update`，同一稳定 note ID 从 revision `1` 经人工审批更新为 revision `2`。
- Run `run-d7cb01df-d13a-4d43-93df-902c19ed972b` 为 `COMPLETED`，审批 `APPROVED`，Executor/typed verification 通过，回执 `COMMITTED`；答案级入口和本地笔记页从当前 Store 回读更新后的标题、正文和版本，而不是依赖模型总结。
- 测试笔记、两个临时会话和临时 Profile 已精确清理，原 Profile 与旧 Run 审计保持不变。本阶段没有生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力变化；仅构建 AndroidTest APK 并通过文档 corpus gate `1/1`，未运行完整测试矩阵、主 APK 或 Release。
- 第 207 阶段优先完成真实前台 `notes.search -> notes.get -> notes.delete` 的人工审批、当前 Store 不可见和精确清理验收；继续保持旧 Run 不变，后台设备自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型后置。

## 第 205 阶段：真实前台本地笔记写入、查看与清理验收（完成）

- 在 Redmi `wsvwypiz7xwslvl7` 完成真实前台 `/agent` 本地笔记闭环：临时 Profile `stage205notesui` 精确开放 `notes.list / notes.search / notes.create` 和 `local-notes` Skill，人工批准后执行 `notes.create`，Run `run-57f1cd8d-30a2-446b-b022-11819487356b` 为 `COMPLETED`，审批 `APPROVED`，Executor/typed verification 为 `PASSED`。
- 写入后从当前 Room 二次读取标题 `stage205_notes_ui`、正文 marker、revision `1` 和稳定 note ID `note-ed6086ba-7590-4d07-8272-8030226622c9`；Tool Ledger、完成卡和笔记页的身份一致。随后通过 UI 删除测试笔记，精确清理临时会话/Profile，恢复原 Profile，旧 Run 与审批审计保持不变。
- 本阶段没有新增生产代码、Tool/Skill、Room Schema、权限、Workflow 或后台能力；仅使用 Redmi，未使用 Pixel_9。按快速迭代分级未运行完整 JVM、Lint、APK、Release 或全量 instrumentation。
- 下一阶段继续回到个人 Agent 主线，选择一个能直接增加“自然语言目标 → 可验证结果 → 权威事实查看”覆盖面的单一窄切片；保持旧 Run 不变、设备动作只限前台，后台设备自动化、精确定时、Foreground Service 和远期生态能力后置。

## 第 204 阶段：真实前台记忆写入与答案级 UI 验收（完成）

- 用 Redmi `wsvwypiz7xwslvl7` 完成真实人工输入 `/agent remember_stage204_ui_memory_marker_1786155352`、审批、`memory.remember` 执行、后置验证和答案级长期记忆查看；同一稳定 `memory-UUID` 在 Tool Ledger、完成卡和当前 Room 页面间保持一致。
- 验收后删除测试记忆、临时 Profile 和临时会话，恢复原 Profile，旧 Run/审批审计保持不变。该结果把第 201–203 阶段的代码与 Debug 证据补成真实前台 UI 闭环，但不扩大生产 Tool/Skill、Room、Workflow、后台或权限边界。
- 默认 User-Agent 的配置化注入在真实请求日志中核对通过；只使用 Redmi，未使用 Pixel_9。按快速迭代分级，本阶段不运行完整 JVM、Lint、Release 或全量 instrumentation。
- 下一阶段回到个人 Agent 主线，优先选择一个仍能直接提升“自然语言目标 → 可验证结果 → 权威事实查看”的窄切片；继续保持旧 Run 不变、设备动作只限前台、后台设备自动化和远期生态能力后置。

## 第 203 阶段：真实长期记忆会话投影验收（完成）

- 新增仅 Debug 的 `memory_remember_conversation_real` 操作，复用第 202 阶段正式 `AgentRunUseCase`、当前 Provider、Room 审批和记忆执行器；真实 `memory.remember` 结果短暂写入专属会话后重新加载，仍保留唯一可信 Tool part、`VERIFIED`、稳定 `memoryIdsUsed` 与 `memoryIdForNavigation()`。
- 临时会话使用精确 ID 原子清理并确认不存在；不触碰用户会话，不改变生产 Tool、Skill、Room Schema、权限、Workflow 或后台能力。Debug 探针验证的是持久化投影，不宣称完成完整人工 UI 输入和审批点击自动化。
- Redmi `wsvwypiz7xwslvl7` 最终 Run `run-f9e8b439-5701-4530-a0cd-39095c037bf9` 为 `COMPLETED`，审批 `APPROVED`、Executor/typed verification `PASSED`、会话投影通过；JVM `MemoryNavigationTest 5/5 + XiaoLingToolRegistryTest 78/78`、Redmi instrumentation `3/3`、Debug/AndroidTest APK 构建和文档 corpus gate `1/1` 均通过。未运行完整 JVM、Lint、Release 或全量 instrumentation，也未使用 Pixel_9。


## 第 202 阶段：真实 Provider 长期记忆写入审批闭环（完成）

- 个人 Agent 主链现在在 Redmi 真机上完成了真实的“模型提出 `memory.remember` -> Room 审批 -> 执行与回读验证 -> 稳定 ID 结果”写入闭环；唯一 ID 同时绑定 Tool Ledger、提交回执和当前 Room 记录。
- Debug 探针使用最小临时 Profile，只开放 `memory.remember`，失败/完成均精确清理测试记忆并恢复原 Profile；没有把测试夹具变成用户长期事实，也没有把 Provider 凭据放进日志。
- 聚焦 JVM `83/83`、Debug/AndroidTest APK 构建和 Redmi 真实 Run `run-b747809a-73f0-4813-9c90-7b6a019c978f` 通过；未运行完整 JVM、Lint、Redmi 全量 instrumentation、文档 corpus gate 或 Release。下一阶段继续从个人 Agent 的直接可用事实闭环中选择一个单一窄切片，不扩展后台、Workflow、Foreground Service、精确定时、MCP、远程 Channel、多 Agent 或本地模型边界。

## 第 201 阶段：长期记忆写入结果答案级导航（完成）

- `memory.remember` 成功和恢复验证结果现在提出应用生成的唯一 `memory-UUID`，并通过 `memoryIdsUsed` 与正文末尾 ID 双重绑定；只有 `VERIFIED` 的固定回执才显示“查看记忆”。
- 参数集合、记忆类型、标签边界和唯一身份任一漂移，或结果为 `READABLE_ONLY`、失败、旧格式、重复正文 ID 时均不导航；旧 Run 不因新结果契约被改写。
- 点击后复用现有记忆管理页，先从当前 Room 二次读取并置顶目标；本阶段不新增写入范围、权限、Room Schema、Tool/Skill、Workflow、后台或 Release 能力。
- 聚焦 JVM `MemoryNavigationTest 5/5 + XiaoLingToolRegistryTest 2/2`、Debug/AndroidTest APK 构建、Redmi Room 导航 `1/1` 和文档 corpus gate `1/1` 通过；本阶段未运行完整 JVM、Lint、Redmi 全量 instrumentation 或 Release。下一阶段继续从个人 Agent 的直接可用事实闭环中选择一个单一窄切片。

## 第 200 阶段：本地笔记详情/编辑结果答案级导航（完成）

- `notes.get` 现在只有在唯一 `note_id`、固定详情/正文安全边界、单一稳定 note UUID 和规范 revision 同时成立时才生成“查看笔记”；正文被明确标记为本地数据，不会成为工具指令或授权。
- `notes.update` 只有在 `VERIFIED`、精确参数集合、标题/ID与请求一致且返回 revision 恰为 `expected_revision + 1` 时才生成入口；`notes.create` 的写入结果继续要求 Executor 验证，`READABLE_ONLY`、版本/标题/ID漂移、额外参数和伪造正文均 fail-closed。
- 点击入口复用现有本地笔记管理页并按当前 Store 二次读取，不把历史 Tool 正文当成权威事实；本阶段不新增权限、Room Schema、Tool/Skill、审批豁免、Workflow、后台或 Release 能力。
- 聚焦 JVM `LocalNoteNavigationTest 7/7`、Debug/AndroidTest APK 构建通过；本阶段未运行完整 JVM、Lint、Redmi instrumentation 或 Release。下一阶段继续从个人 Agent 的直接可用事实闭环中选择一个单一窄切片。

## 第 199 阶段：日程创建/修改结果答案级导航（完成）

- `calendar.create_event` 成功结果现在携带应用生成的稳定事件 ID；`calendar.create_event / calendar.update_event` 只有在 `VERIFIED`、严格参数、固定结果外壳与唯一规范 ID 同时成立时才复用“查看日程”。
- 创建结果必须精确绑定规范化请求标题；修改结果必须绑定同一请求 ID、`scope=event` 与有效新指纹，且新指纹不能等于审批前指纹。失败、只读结果、额外参数、标题/ID/指纹漂移、正文伪造或删除结果均不导航。
- 点击后继续使用第 198 阶段独立详情页，从当前 Calendar Provider 二次读取最小字段；本阶段不扩大创建/修改范围，不新增权限、审批、Room Schema、Workflow 或后台能力。
- 聚焦 JVM `82/82`、Debug/AndroidTest APK 构建通过；未运行 Redmi instrumentation、完整 JVM、Lint 或 Release。下一阶段继续选择个人 Agent 的直接可用窄闭环，优先补当前答案与权威本地事实之间仍缺少的安全查看入口。

## 第 198 阶段：答案级系统日程详情导航（完成）

- 可信 `calendar.list_events / calendar.search_events / calendar.get` Tool part 现在可以投影“查看日程”，但必须满足严格参数、应用生成的动态标题、唯一规范 `calendar-<正整数>` ID 和非失败验证；空/多结果、额外参数、标题漂移、非规范/溢出 ID、详情错配或正文伪造均不产生入口。
- 点击后不信任历史 Tool 正文：应用导航到独立详情页，并使用当前 Calendar Provider 按稳定 ID 二次读取。页面只展示标题、ID、起止时间、全天、时区和重复状态；删除、权限撤销、Provider 不可用或读取失败均 fail-closed。
- 该切片只补答案到当前系统事实的查看闭环，不申请权限、不创建/修改/删除日程，不发送消息、不创建 Run，不新增 Room Schema、Tool/Skill、Workflow、设备动作或后台能力。
- 聚焦导航 JVM、Debug/AndroidTest APK 构建通过；AndroidTest 新用例已编译但未在 Redmi 运行，完整 JVM、Lint、文档 corpus gate 和 Release 未运行。下一阶段继续选择个人 Agent 的直接可用窄闭环。

## 第 197 阶段：答案级历史会话导航（完成）

- 可信的 `app.list_conversations / app.search_conversations / app.get_conversation` Tool part 现在可以投影“查看会话”入口，但必须同时满足应用固定结果标题、严格参数、唯一合法 `conversation-...` ID 和非失败验证；普通模型文本、空/多结果、额外参数、换行注入、ID 不一致或正文伪造均不产生入口。
- 点击入口后不直接相信消息快照：ViewModel 先从当前 Room 重读会话表，只有目标 ID 恰好唯一存在时才复用既有会话选择与消息加载；删除、漂移、重复、读取失败和目标不再存在均 fail-closed。
- 本切片只改变答案级查看体验，不发送消息、不创建 Run、不触发工具/审批/Provider 写入，不新增 Room Schema、权限、Workflow、设备动作或后台能力。旧会话、历史 Run、重试和 Shadow 边界保持不变。
- 聚焦会话导航 JVM `17/17`、Debug/AndroidTest APK 构建通过；未运行完整 JVM、Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release。下一阶段继续选择个人 Agent 的直接可用窄闭环。

## 第 196 阶段：历史会话详情只读闭环（完成）

- 新增 `app.get_conversation(conversation_id)`，补齐历史会话的“列表/搜索摘要 -> 稳定会话 ID -> 当前正文”链路。ID 只能使用 `app.list_conversations` 或 `app.search_conversations` 返回形态的 `conversation-...` 值；会话不存在、格式漂移或额外参数均停止。
- 详情从当前 Room 单会话回读，只投影非空的用户/助手文本；最多 40 条、单条 20,000 字符、总计 60,000 字符。工具参数、Provider 凭据字段、附件二进制、原始推理、Provider 元数据和内部审计字段不读取，并在结果中明确标记为本地历史资料而非工具指令。
- 工具为 `SAFE`、仅前台 `DIRECT`、不支持 Workflow/后台、超时 5 秒。新增独立 `conversation-detail` Skill，既有 `conversation-recall`、旧 Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 不自动加入新工具；没有新增 Room Schema、权限、网络、设备动作或后台副作用。
- 聚焦 JVM `AgentConversationDetailPolicyTest 2/2 + AgentSkillsTest 31/31 + XiaoLingToolRegistryTest 78/78 + LegacyRunToolBoundaryTest 3/3`、Debug/AndroidTest APK 构建通过；未运行完整 JVM、Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release。下一阶段继续选择个人 Agent 的直接可用窄闭环。

## 第 195 阶段：当前 Agent Profile 只读状态（完成）

- 新增 `agent.get_profile`，让前台直接 Agent 可以回答本次 Run 实际冻结的 Agent 名称、模型、API 模式和长期记忆召回状态；结果不展示 Provider 地址、API Key、系统提示词、内部 ID 或工具白名单。
- 工具无参数、`SAFE`、仅前台 `DIRECT`、禁止 Workflow/后台且超时 5 秒。Profile 信息只在当前执行上下文短暂传递；缺少上下文、状态漂移、配置不完整或参数漂移时 fail-closed。
- 新增独立 `agent-profile-info` Skill，既有 Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 不自动加入新工具；没有新增 Room、权限、网络、设备动作、Workflow 或后台副作用。
- 聚焦 JVM `AgentSkillsTest 30/30 + XiaoLingToolRegistryTest 76/76`、Debug/AndroidTest APK 构建通过；未运行完整 JVM、Lint、Redmi 功能 instrumentation、文档 corpus gate 或 Release。下一阶段继续选择个人 Agent 的直接可用窄闭环。

## 第 194 阶段：只读应用信息窄闭环（完成）

- 新增 `app.get_info`，Agent 可以回答当前安装小灵的应用名称、包名、版本名和版本号；工具无参数、`SAFE`、支持后台且超时 5 秒。
- 应用侧只从 PackageManager 回读自身安装事实，固定四字段结果并排除 Provider、API Key、设备标识、安装来源和其他配置；读取失败或参数漂移时 fail-closed。
- 新增独立 `app-info` Skill，旧 Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 不自动扩权；新 Profile 或显式编辑后才可使用。没有新增 Room、权限、设备动作、Workflow 或后台副作用。
- 聚焦 JVM `XiaoLingToolRegistryTest 74/74 + AgentSkillsTest 29/29`、Debug/AndroidTest APK 构建和 Redmi 文档 corpus gate 通过；未运行完整 JVM、Lint、Redmi 功能 instrumentation 或 Release。下一阶段继续选择个人 Agent 的直接可用窄闭环。

## 第 193 阶段：任务中心关联 Run 双向查看与安全导航（完成）

- 任务中心详情现在可以在当前历史内双向查看关联关系：关联 Run 查看唯一来源，来源 Run 查看按创建时间唯一确定的最新关联 Run。
- 历史裁剪、目标缺失、ID 重复或最新时间并列时不提供跳转；点击前重新核对目标，自动切换全部筛选并滚动定位，只改变查看状态，不触发执行。
- 聚焦 JVM `3/3`、Redmi Compose `4/4`、Debug/AndroidTest 构建和文档 corpus gate `1/1` 通过；没有运行完整 JVM、Lint、Release 或全量 instrumentation。
- 下一阶段继续围绕个人 Agent 的直接可用闭环选择窄目标；关联 Run 的旧事实、审批边界、后台和高级生态能力保持不变。

## 第 192 阶段：确认后关联新 Run 的 Room 历史保留验收（完成）

- 在任务中心完成专用确认后，Room 真实落库的关联 Run 通过 `retryOfRunId` 指向原 Run；原 Run 保持 `FAILED` 终态，不会被恢复、重放或覆盖。
- 来源 Run 的 Step、Approval、Tool Result、`COMMITTED` 回执、Event 和 Tool Ledger 在创建新 Run 前后以及两次磁盘 Repository 重建后均保持不变；新 Run 从独立 `QUEUED` 空账本开始。
- Redmi `RoomAgentRunRepositoryInstrumentedTest` 聚焦交叉回归 `4/4` 通过，测试 APK 已卸载，Debug/AndroidTest 构建与文档 corpus gate 通过；未运行完整 JVM、Lint、Release 或全量 instrumentation。
- 下一阶段继续推进个人 Agent 主线的下一个可直接使用窄闭环；旧 Run 保留、重试确认、后台和高级生态边界不因本阶段扩权。

## 第 191 阶段：任务中心重新发起边界统一（完成）

- 持久化 Recovery 带 `restartDisposition` 时，不再根据“明确未提交”就直接准备重试；任何这类 Run 都必须使用专用确认。
- 确认使用最新 Room detail 重核处置码和证据指纹；漂移时刷新确认卡或拒绝，不会误将旧授权套到新边界。
- 任务卡与弹窗统一使用“创建新 Run”文案，明确旧 Run 的终态、模型协程、Executor 和旧工具均不会被恢复或重放；新 Run 写入仍需重新审批。
- 聚焦 JVM `47/47`、Redmi 任务中心确认弹窗 `3/3` 与页面 `2/2` 通过。下一阶段对关联新 Run 的建立与原 Run 历史保留做一次 Room 层交叉验收，不扩展后台、series/occurrence 或其他 Agent 生态能力。
## 第 190 阶段：启动恢复失败可见投影（完成）

- 启动提示在 Room 收敛完成后回读最新 Recovery 事件，统计存在 `restartDisposition` 的无法原地恢复 Run。
- 用户可从提示进入 Agent 任务中心，明确知道继续操作只能通过确认后创建关联新 Run；不恢复或重放旧 Run。
- 提示继续遵守隐私边界，仅展示终态数量和操作边界，不暴露目标、原始错误、Run ID 或工具参数。聚焦 JVM 通过；未扩展恢复执行、自动重试、Room Schema、Workflow 或后台能力。
- 下一阶段对任务中心的“确认后重新发起”边界做一次一致性检查，确保提示、详情卡和重试协调器都不会重放旧 Run。

## 第 189 阶段：失败日程修改 Run 终态恢复验证（完成）

- 在真实 Room 中构造已失败的 `calendar.update_event` Run：执行结果失败、无 `COMMITTED` 回执，工具恢复契约为 `RESTART_REQUIRED + DENY`。
- 重建 `RoomAgentRunRepository` 后，恢复策略为 `RESTART_REQUIRED / RUN_STATE_NOT_RESUMABLE`；运行启动收口返回零变更，Run 保持 `FAILED`，原 Step、Tool Result 和 Event 证据不变，没有 `run.recovered`。
- Debug/AndroidTest APK、Redmi 恢复单项和文档 corpus gate `1/1` 通过；未运行完整 JVM、Lint、Release APK 或全量 instrumentation，未修改生产恢复、Room v36、Workflow 或后台能力。
- 下一阶段评估启动恢复后面向用户的可见故障投影与重新发起边界；继续不扩展 series/occurrence、后台日历自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 或本地模型。

## 第 188 阶段：真实 Provider 审批后漂移失败闭环（完成）

- Debug-only `calendar_update_conflict_real` 在 Redmi 真实模型三步链中，于审批请求落库后制造同一事件的外部标题漂移。
- Run `run-05831fda-73c9-460a-a8e5-a3c52debdfca` 的审批为 `APPROVED`，但条件 UPDATE 正确拒绝，Run 为 `FAILED`，结果没有 `COMMITTED` 回执，Provider 保留外部事实。
- 夹具、临时 Profile 和临时日历按事件 ID 精确清理；聚焦 JVM `196/196`、Debug/AndroidTest APK、Redmi 真实失败探针和文档 corpus gate `1/1` 通过。未运行完整 JVM、Lint、Release APK 或全量 instrumentation。
- 下一阶段补失败 Run 启动收敛的 `RESTART_REQUIRED + DENY` 证据投影和跨进程 Room 回查；不扩展日程修改范围或后台能力。

## 第 187 阶段：日程修改中断恢复边界加固（完成）

- 固定 `calendar.update_event` 的恢复契约为 `RESTART_REQUIRED + DENY`。无回执、非 `COMMITTED`、回执错配或未提交执行边界均不具备原地恢复或受控重放资格。
- 在 Redmi 上重建 `AndroidCalendarEventWriter` 后只读回查已提交事件；重建实例的无回执 UPDATE 仍因旧指纹拒绝，证明跨进程/Registry 重建不会再次写入日程。
- 聚焦 JVM `196/196`、Debug/AndroidTest APK、Redmi `AndroidCalendarEventWriterInstrumentedTest` `4/4` 和文档 corpus gate `1/1` 通过；测试 APK 已卸载，未运行完整 JVM、Lint、Release APK 或全量 instrumentation。
- 下一阶段继续补真实模型失败/中断后的 Run/Room 恢复证据；不扩展 series/occurrence、后台日历自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 或本地模型。

## 第 186 阶段：真实 Provider 受控系统日程修改闭环（完成）

- Debug-only `calendar_update_real` 读取 Redmi 当前真实模型 Provider，创建 stage186 专属一次性非全天夹具和临时 Profile；Profile 只允许 `calendar.search_events / calendar.get / calendar.update_event` 与 `calendar-update`，不把创建或其他工具带入 Run。
- 最终 Redmi Run `run-554e65fa-ca43-461c-8346-034f3a426694` 严格执行三步链。关键词原样传递，get 使用搜索结果稳定 ID，update 再原样复用同一 ID、当前指纹、`scope=event` 和完整新标题/起止/时区；三项 Tool Result 均 `success=true / PASSED`。
- `calendar.update_event` 的 Room 审批为 `APPROVED`，结果具备 Executor 验证和同一事件 ID 的 `COMMITTED` 回执；Provider 回读确认四个目标字段与新指纹一致。最终回答文本不参与成功判断。
- 夹具、必要时创建的本地日历及临时 Profile 在成功与失败路径均精确清理。日志只输出脱敏布尔摘要；真实验收通过显式 Debug Receiver 触发，未改变生产广播或能力面。
- Debug/AndroidTest APK 构建成功，文档 corpus gate `1/1` 通过；本阶段未运行 JVM、Lint、Release APK 或全量 instrumentation，也未改变 Room v36、旧 Skill/Profile/Legacy Run、Workflow 或后台边界。
- 下一阶段先验证真实模型失败/中断后的 `RESTART_REQUIRED + DENY` 恢复契约与跨进程 `COMMITTED` 只读确认，不重放 UPDATE；series/occurrence、后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 185 阶段：受控系统日程修改（完成）

- 新增 `calendar.update_event` 与独立 `calendar-update` Skill，前台 Agent 必须严格执行 `calendar.search_events -> calendar.get -> calendar.update_event`，并原样传递稳定 ID、当前指纹和 `scope=event`。
- 能力面只开放一次性非全天事件的完整标题、起止时间与时区更新。series、occurrence、重复事件和全天事件明确拒绝；时间必须携带偏移并与 IANA 时区一致，空标题、时间逆序和无变化请求不会写入。
- Provider 条件 UPDATE 绑定审批前完整事件快照，写后回读四个目标字段并返回新指纹。审批期间漂移时影响 0 行，不能以旧授权覆盖外部新事实。
- 恢复契约为 `RESTART_REQUIRED + DENY`；只有匹配 `COMMITTED` 回执、`scope=event` 和前台 DIRECT 上下文才能只读验证，恢复阶段不执行 UPDATE。工具发现、定义、正常执行和恢复入口均 fail-closed。
- 聚焦 JVM `101/101`、Debug/AndroidTest APK、仅 Redmi 的真实 Calendar Provider 修改单项及文档 corpus `1/1` 通过。真机单项覆盖四字段更新、新指纹、COMMITTED 只读恢复、无回执不重放和审批期外部漂移拒绝；测试事件已精确清理。
- 本阶段未运行完整 JVM、Lint、Release APK 或全量 instrumentation，也未改变 Room v36、旧 Skill/Profile/Run、Workflow 或后台边界。
- 下一阶段只补真实模型 `calendar.search_events -> calendar.get -> calendar.update_event` 的 Skill 选择、参数传递、逐次审批、typed verification、COMMITTED 回执、Provider 新状态和精确清理证据。series/occurrence、后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 184 阶段：真实 Provider 受控系统日程删除闭环（完成）

- Debug-only `calendar_delete_real` 使用设备当前 Provider、唯一一次性日程夹具和显式临时 Profile；Profile 只允许 `calendar.search_events / calendar.get / calendar.delete_event` 与 `calendar-delete`，不把创建或其他工具带入 Run。
- 最终 Redmi Run `run-3981834b-8d4c-4ade-b3ec-23aa138250cd` 严格执行三步链。关键词原样传递，get 使用搜索结果稳定 ID，delete 再原样复用同一 ID、当前指纹和 `scope=event`；三项 Tool Result 均 `success=true / PASSED`。
- `calendar.delete_event` 的 Room 审批为 `APPROVED`，结果具备 Executor 验证和同一事件 ID 的 `COMMITTED` 回执，删除后当前 Provider 回读 NotFound。最终回答文本不参与成功判断。
- 首轮 Run `run-85260e99-5a2c-40a6-b26a-712643ea1c2e` 已完成工具和删除，但因 Skill 选择目标缺少连续关键词“删除日程”而没有 `skill.selected` 证据，探针按严格门禁判失败并清理；修正确定性选择输入后复验通过，没有放宽 Skill 断言。
- 夹具、必要时创建的本地日历及临时 Profile 在成功与失败路径均精确清理。最终 Debug/AndroidTest APK 与文档 corpus `1/1` 通过；未运行 JVM、Lint、Release APK 或全量 instrumentation，生产能力面与 Room v36 不变。
- 下一阶段先冻结受控系统日程修改：只允许明确字段白名单，继续要求稳定 ID、expected fingerprint、显式 scope、逐次审批、Provider 条件更新、写后回读、COMMITTED 回执和只读恢复。occurrence、后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 183 阶段：受控系统日程删除（完成）

- `calendar.get` 已把当前 Provider 事件快照固化为 `calendar-event-v1-<sha256>`；新增 `calendar.delete_event(event_id, expected_fingerprint, scope)` 与独立 `calendar-delete` Skill，模型必须先搜索、唯一命中并读取详情，再原样传递同一稳定 ID 与指纹。
- 删除工具仅进入前台 DIRECT Run，逐次审批并要求 `READ_CALENDAR + WRITE_CALENDAR`。工具发现和执行入口都检查 RunContext；旧 Skill、Profile、历史/Legacy Run、Workflow 与后台不自动扩权。
- `event` 只允许一次性事件，`series` 只允许整个重复系列；`occurrence` 明确不支持且不会降级。Provider 条件删除同时绑定 ID、标题、起止、全天、时区、RRULE/RDATE 与删除状态，审批期间任何漂移都拒绝。
- 已提交恢复只接受匹配的 `COMMITTED` 回执，并且只读确认目标不可见；`RESTART_REQUIRED + DENY` 禁止未提交、未知或无回执路径重新 DELETE。无回执重复调用按 NotFound 处理，不猜测此前是否由本工具删除。
- 聚焦 JVM `97/97` 通过；仅在 Redmi 运行真实 Calendar Provider 删除单项，覆盖成功删除、COMMITTED 只读恢复、无回执不重放和外部改名后旧指纹拒绝，结果 `OK (1 test)`、耗时 `0.392s`。
- Debug/AndroidTest APK 与更新后的文档 corpus `1/1` 通过；未运行完整 JVM、Lint、Release APK 或全量 instrumentation，测试包已卸载且主应用数据保留。
- 第 184 阶段已补齐真实模型 `calendar.search_events -> calendar.get -> calendar.delete_event`、逐次审批、typed verification、回执与清理证据。日程修改按第 184 阶段列出的受控契约继续推进；occurrence、后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 182 阶段：真实 Provider 系统日程详情闭环（完成）

- Debug-only `calendar_search_get_real` 从设备当前已选择 Provider 构建正式 `AgentRunUseCase`。临时 Profile 只允许 `calendar.search_events / calendar.get` 与 `calendar-detail`，不包含 `calendar.create_event`，事件夹具由 Agent Run 外的正式 Calendar writer 创建。
- 最终 Redmi Run `run-e238ca62-58c5-4c54-a611-e368f2ddace2` 选择唯一 `calendar-detail`，严格执行 `calendar.search_events -> calendar.get`；搜索关键词原样传递，详情参数等于搜索结果的稳定 `calendar-<Events._ID>`，两项 Tool Result 均 `success=true / PASSED`，详情来自当前 Provider且审批为 0。
- 首次覆盖安装后立即触发的 Run `run-f7f6e2d3-25df-48f3-95b8-76ffb4c53f30` 与启动恢复竞态，因 Run 已被收敛而拒绝追加步骤；该轮夹具/Profile 已清理。稳定进程与最终清理修正后的代码均复验成功，不把首次编排失败记作日程工具失败。
- 探针只按应用包名与 stage182 marker 回收中断残留；当前事件按 Provider 返回 ID 删除，只有本轮实际新建的小灵本地日历才按精确 ID 删除。日志不记录 API Key、事件标题或工具参数。
- Debug/AndroidTest APK、Redmi 真实 Provider 与文档 corpus `1/1` 通过。未运行完整 JVM、Lint、Release APK 或全量 instrumentation。第 183 阶段已先冻结并实现受控删除；日程修改、occurrence、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 181 阶段：系统日程稳定身份与权威详情读取（完成）

- `calendar.list_events / calendar.search_events` 为每个实例返回其 `Events._ID` 派生的稳定 `calendar-<正整数>`；重复事件的 ID 指向事件系列。本阶段不承诺单次 occurrence 修改。
- 新增仅前台、SAFE 的 `calendar.get(event_id)`。工具只接受列表或搜索返回形态的稳定 ID，并从当前 Calendar Provider 单条回读标题、起止时间、全天、时区及 RRULE/RDATE 重复状态；地点、描述、参与人、组织者和账户不进入数据模型或 Provider 投影。
- 新增独立 `calendar-detail` Skill，严格执行 `calendar.search_events -> calendar.get`；无结果、结果不唯一、ID 非法、事件已删除、Provider 不可用或权限撤销时停止，不把搜索摘要或旧内容冒充当前详情。
- 聚焦 JVM `89/89`、Debug/AndroidTest APK、仅 Redmi 的真实 Calendar Provider `1/1` 和文档 corpus `1/1` 通过；临时事件按 Provider 返回 ID 精确清理。双轴审查补齐 RDATE-only 重复事件识别；未运行真实模型 Provider Run、完整 JVM、Lint、全量 instrumentation 或 Release。
- 第 182 阶段已完成真实模型 Provider 闭环；后续按第 182 阶段列出的日程修改/删除契约评估推进。

## 第 180 阶段：答案级长期记忆导航（完成）

- 可信 `memory.search / memory.get` Tool part 在成功、验证状态非失败、参数合法、应用生成结果外壳与唯一 `memoryIdsUsed` 稳定 ID 一致时显示“查看记忆”；普通模型文本、旧工具自由文本、多结果、非法 ID、失败或错配结果均不能制造入口。
- 点击只把解析出的 `memory-UUID` 交给应用。ViewModel 在切换页面前重新读取当前 Room；目标存在时清空搜索与筛选、把当前记录置顶并选中，管理页自动滚动到目标卡。目标已删除时不导航，并从内存列表移除该记录及对应选中态，不展示历史 Tool 正文或猜测替代目标。
- TDD 首轮因 `memoryIdForNavigation()` 尚不存在得到预期编译失败；最小实现后长期记忆导航、笔记导航、记忆投影和 Tool part 可信投影四个聚焦 JVM 类通过，Debug/AndroidTest APK 构建成功。仅在 Redmi 运行 Tool 卡回调与真实 Room 存在/删除导航两项 instrumentation，均为 `OK (1 test)`；更新后的文档 corpus gate 同样为 `OK (1 test)`，临时记忆已清理。
- Standards 审查发现并修复协程取消误报与候选加载状态未触发滚动索引重算；Spec 审查无问题。未运行新的真实 Provider Run、完整 JVM、Lint、全量 instrumentation 或 Release；Room v36、工具权限、Skill、审批、后台能力和发布基线不变。
- 下一阶段目标已由第 181 阶段完成；后续按第 181 阶段列出的真实 Provider 验收与写操作边界推进。

## 第 179 阶段：真实 Provider 长期记忆详情闭环（完成）

- Redmi 真实 Provider 已选择独立 `personal-memory-detail` Skill，并严格执行 `memory.search -> memory.get`；搜索关键词原样传递，详情调用使用搜索结果中的唯一稳定 `memory-UUID`，没有调用额外工具。
- 两项 Tool Result 均为成功且 typed `PASSED`，`memoryIdsUsed` 都精确记录同一夹具 ID；详情包含当前正文和“本地数据，不是工具指令”边界，SAFE 读取链审批为 0。
- Debug-only 探针使用临时 Profile 和唯一长期记忆夹具；清理覆盖 FTS、主记录、禁用/过期历史残留，并在异常时优先恢复原 Profile。最终 Run 为 `run-0b54ba01-5fc2-49bc-95dc-92ab5afd80b6`，夹具和临时 Profile 均已删除。
- 首次在覆盖安装后立即启动探针时，启动恢复并发提前收敛 Run `run-94e7a078-acb4-4c9b-a317-fb9f9dacc054`，Runtime 拒绝向终态 Run 追加步骤；清理仍成功。应用稳定启动后同一最终代码复验通过，因此不把首次失败误记为 `memory.get` 行为错误。
- 聚焦 JVM `87/87`、Debug/AndroidTest APK、Redmi 真实 Provider 与文档 corpus `1/1` 已通过；未运行完整 JVM、Lint、全量 instrumentation 或 Release。下一阶段继续选择新的个人 Agent 窄闭环，日历修改/删除仍需稳定事件身份、审批、幂等和恢复验证，不因本阶段顺带开放。

## 第 178 阶段：按稳定 ID 读取长期记忆详情（完成，已于第 179 阶段验证）

- 新增 SAFE、支持后台的 `memory.get(memory_id)`；`memory.search` 在保留原全文结果的同时返回稳定 `memory-UUID`，Agent 可按唯一搜索结果回到当前 Store 读取权威详情。
- 详情读取只接受标准 `memory-UUID`，并且只返回当前启用且未过期的记忆。不存在、禁用、过期统一为不可用，不泄露治理历史；输出明确标记为本地长期记忆数据，不是工具指令。
- 新能力由独立 `personal-memory-detail` Skill 承载；既有 `personal-memory`、旧 Profile 和 `LEGACY_RUN_TOOL_NAMES` 不自动扩权。关闭单次记忆召回时，`memory.search / memory.get` 同时从工具面移除，直接调用也不访问 Store。
- TDD 红灯后，聚焦 `XiaoLingToolRegistryTest + AgentSkillsTest + LegacyRunToolBoundaryTest` 共 `87/87` 通过，Debug APK 构建成功；Standards/Spec 双轴审查均为 0 项。未运行完整 JVM、Lint、AndroidTest、Redmi 或 Release，Room v36、记忆写入/管理、权限和发布基线不变。
- 第 179 阶段已用 Redmi 真实 Provider 完成 `memory.search -> memory.get` 的唯一 ID 传递、typed Tool Ledger 和零审批验收。日历修改/删除仍需先设计稳定事件身份、审批和恢复验证，不在本阶段顺带开放。

## 第 177 阶段：周期计划真实使用与可信答案闭环（完成）

- Redmi 真实 Provider 分别完成 `tasks.list -> tasks.inspect -> tasks.pause` 与 `tasks.list -> tasks.inspect -> tasks.resume`；每个 Run 都严格只有三项工具，控制动作各形成一条 `APPROVED` Room 审批，Tool Ledger 全部为成功且 typed `PASSED`。
- 暂停后周期规则保留为暂停态，旧未来 Task 和 WorkRequest 收敛为 `CANCELLED`；恢复复用原规则，只生成一个当前时间之后的新 Task，绑定唯一 `ENQUEUED` WorkRequest，不补跑暂停窗口，也不改写旧 Task 事实。
- 会话终态、Workflow/ScheduledTask/周期规则刷新和答案级“查看任务”共用同一可信解析：只接受唯一 `tasks.pause / tasks.resume`、严格 `{name}`、typed `VERIFIED` 和与工具动作一致的应用生成首行。点击仍按当前 Room 唯一精确任务名二次解析，不保存内部 ID。
- 聚焦 JVM `13/13`、Debug/AndroidTest APK、Redmi 真实 Provider 双 Run 与文档 corpus `1/1` 已通过；夹具 Workflow 已停用，临时 Profile 和残留 Work 已清理。Room v36、旧 Profile/Run、一次性计划、后台工具面、精确定时、Foreground Service 和高级日历写入均未改变。
- 下一阶段继续选择一个能直接增加个人 Agent 完整任务范围的窄闭环；不回到纯横向打磨，也不提前引入 MCP、远程 Channel、多 Agent 或本地模型。

## 第 176 阶段：应用内周期计划暂停/恢复（完成）

- 个人 Agent 新增仅前台、逐次审批的 `tasks.pause / tasks.resume` 和独立 `task-schedule-control` Skill；`tasks.list / inspect` 现在能区分工作流启停与周期计划暂停。旧 Profile、历史 Run 和原任务 Skill 不自动扩权。
- 暂停保留 DAILY/WEEKLY 规则，只撤销尚未开始的未来实例；正在运行的 Task/Workflow Run 保持不变且完成后不再物化下一次。恢复复用原规则并从现在之后只生成一个实例，不补跑暂停窗口。
- 精确名称、唯一规则、规则/Task 身份、活动状态和 WorkRequest 关联均从 Room 回读；任何漂移拒绝。恢复入队失败会原子回滚到可重试暂停态，不留下“已启用但无可执行实例”的假状态。
- 聚焦 JVM `82/82`、Debug/AndroidTest APK、Redmi `RoomAgentTaskStoreInstrumentedTest 13/13` 与文档 corpus `1/1` 已通过；未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 177 阶段已完成 Redmi 真实 Provider 自然语言双闭环，并把可信结果接到任务快照刷新和任务中心入口；后台调度权限、精确定时与系统日程高级写操作继续关闭。

## 第 175 阶段：受控系统日程创建（完成）

- 个人 Agent 现在可在用户明确要求并逐次批准后创建系统日程；首版只支持一次性非全天事件，必须给出标题、带偏移的起止时间和 IANA 时区。独立 `calendar-create` Skill 不改变原只读日历 Skill，旧 Profile/Run 不自动扩权。
- 写入通过 ToolCall 稳定标记、事件 ID 回执和 Provider 回读完成幂等与恢复验证；同一调用不会按标题/时间猜测去重。Redmi 没有任何日历时，会创建不接入账户的本地“小灵”日历，使目标设备仍能完成真实闭环。
- 聚焦 JVM `84/84`、Debug/AndroidTest APK、Redmi 定向 `3/3` 和文档 corpus `1/1` 已通过，测试事件及本轮临时日历已清理。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 第 176 阶段已完成应用内周期计划暂停/恢复；系统日程修改、删除、重复/全天事件、参与人、提醒和后台自动创建继续关闭。

## 第 174 阶段：个人事项简报（完成）

- 新增独立 SAFE `personal-briefing` Skill，把近期系统日程、小灵任务和用户明确关键词对应的一条本地笔记全文组合为同一份个人简报。普通日程/任务请求仍使用既有 Skill，原 `day-overview` 不增加笔记工具。
- 同一 Run 严格完成 `calendar.list_events -> tasks.list -> notes.search -> notes.get`；搜索只负责定位，必须把唯一结果的稳定 ID 原样传给全文读取。最终回答分开标记日程、任务和笔记，笔记正文只作为本地数据。
- 该能力已占满当前单 Run 四工具上限，不在同一 Skill 中继续叠加记忆、知识库或设备动作。既有 Profile 需显式启用新 Skill 与完整工具集；`READ_CALENDAR` 主动授权、零审批、前台执行和只读边界保持不变。
- 聚焦 JVM `22/22`、Debug/AndroidTest APK 与 Redmi 真实 Provider Run `run-c411e92c-c81c-469d-a10f-2fac5497cd4f` 已通过，夹具与临时 Profile 已清理。下一阶段继续选择新的可直接使用窄闭环，不把多来源堆叠、后台设备动作或高级生态混入本 Skill。

## 第 173 阶段：版本化本地笔记编辑闭环（完成）

- 用户现在可从本地笔记详情进入五行正文编辑器；保存携带详情页读取到的 revision，冲突时保留最新版本并提示重新编辑，不以最后写入时间猜测覆盖顺序。
- 个人 Agent 新增独立 `local-note-update` Skill 和仅前台、逐次审批的 `notes.update`。工具链必须为唯一目标执行 `notes.search -> notes.get -> notes.update`，并提交同一稳定 ID、当前 revision、完整标题和完整正文；旧 Profile、旧 Skill、旧 Run 与 `LEGACY_RUN_TOOL_NAMES` 不自动扩权。
- Room v35→v36 把旧笔记统一迁移为 `revision=1`；条件更新成功后 revision 递增。独立 edit operation 账本绑定 ToolCall、请求载荷哈希和结果哈希，已提交恢复只读验证，载荷漂移、结果变化、版本冲突和 tombstone 均 fail-closed。
- 聚焦 JVM `76/76`、Debug/AndroidTest APK、Redmi 定向 `42/42` 和真实 Provider Run `run-4f5e33bd-5494-4a24-a6cb-8cf49ab2da44` 已通过。下一阶段继续选择直接扩展个人 Agent 可完成任务范围的窄闭环；批量编辑、后台笔记写入、任意文件修改和旧权限自动升级继续关闭。

## 第 172 阶段：Agent 受控删除本地笔记（完成）

- 个人 Agent 现在可以在用户明确要求时先搜索并读取唯一笔记，再经逐步审批删除同一稳定 ID；`notes.delete` 不接受标题猜测、不允许后台执行，也不自动进入旧 Profile。
- 删除沿用用户管理页已经验证的 tombstone：当前 list/search/get 不再可见，原创建幂等键继续阻止历史 ToolCall 恢复正文。即时执行与提交后恢复都必须由应用侧回读验证，模型总结不能升级删除事实。
- 聚焦 JVM `73/73`、Debug/AndroidTest APK、Redmi Room tombstone `1/1` 和真实 Provider Run 已通过。该阶段没有新增 Room Schema、Android 权限、后台写入、批量删除或笔记编辑。
- 第 173 阶段已通过 revision 乐观锁和独立 operation 账本关闭笔记编辑的版本冲突与历史 ToolCall 审计差距；后台设备自动化仍需长任务可靠性与高权限恢复证据，不因笔记能力顺带开放。

## 第 171 阶段：真实 Provider 搜索并读取笔记全文（完成）

- Redmi 真实 Run 已从自然语言目标选择 `local-note-detail`，先按唯一关键词调用 `notes.search`，再把结果中的稳定 ID 原样传给 `notes.get`；两项只读事实均通过 Runtime 验证，模型没有猜 ID 或调用额外工具。
- 该闭环继续使用显式临时 Profile、零审批、当前 Store 回读和正文数据边界。Debug 探针及夹具不进入 Release，用户原 Profile、笔记库和生产权限均不改变。
- 聚焦 JVM、Debug/AndroidTest APK、Redmi 真实 Provider 和文档 corpus gate 已通过；未运行完整矩阵或 Release。
- 第 170 阶段的“搜索摘要 -> 完整正文”主线差距已关闭。下一阶段继续选择能直接增加个人 Agent 可完成任务范围的垂直能力，不回到纯 Shadow 扩样、单文件瘦身或完整矩阵常态化。

## 第 170 阶段：按稳定 ID 读取本地笔记（完成，已于第 171 阶段验证）

- 新增 SAFE `notes.get(note_id)`，Agent 可以先用 `notes.list / notes.search` 定位稳定 `note-UUID`，再从当前 Store 读取正文，补齐“搜索摘要 -> 完整内容”的只读闭环。
- ID 必须是标准 `note-UUID`；不存在和已删除 tombstone 使用同一失败结果，正文最多返回 20,000 字符并标记为本地数据，不能通过任意 ID 探测或恢复已删除内容。
- 新能力由独立 SAFE `local-note-detail` Skill 承载；既有 `local-notes` 与历史 Profile 不自动扩权，用户显式启用后才可用。Room、写入审批、后台副作用和任意 App 边界不变。
- 第 170 阶段落地时按用户要求未运行测试或编译；第 171 阶段已用真实 Profile/Provider 完成该闭环，再选择新的主线能力，不回到纯横向打磨。

## v0.1.16 发布基线

`v0.1.16` 以 `versionCode 17` 汇总 `v0.1.15` 后第 128 至 169 阶段。完整个人 Agent 主线已经贯通自然语言目标、限定 App 多动作、目标级本地验证、记忆/知识上下文、应用内提醒、任务恢复/诊断/重试/取消、只读日历、本地笔记，以及启动中断 Run 与答案级任务/笔记导航。Release APK 为 `3,400,350` 字节，SHA-256 为 `971f0c457c3a802d3bb41bd31ac58fda2c1ee0eebbe6f2967ec428299d801126`；[GitHub Release](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.16) 已发布并成为 latest。本轮按用户明确要求只执行 `assembleRelease`，没有额外运行 JVM、完整 Lint、Debug/AndroidTest、Redmi 安装或 instrumentation。下一阶段继续围绕个人 Agent 的直接可用闭环推进，不把高级生态或纯横向打磨放回主线。

## 第 169 阶段：创建笔记后的答案级导航（完成）

- `notes.create` 成功且完成 Executor 回读验证后，工具结果附带稳定 note ID；答案下可以直接进入本地笔记详情，保持与 `notes.list / notes.search` 一致的用户闭环。
- 导航门禁要求 `success=true`、typed `VERIFIED`、参数严格为完整 `title/content`、标题无换行且结果标题与请求标题精确一致，全文只能出现一个合法 note ID。回读失败、标题漂移、重复/非法 ID 和自由文本均 fail-closed。
- 本阶段不改变 notes.create 的审批、幂等回执、回读验证、旧 Run、Room Schema 或后台能力；下一阶段继续选择一个能直接提升个人 Agent 完整闭环的窄切片。

## 第 168 阶段：答案级本地笔记导航（完成）

- `notes.list / notes.search` 的成功只读结果现在可以在答案下直接打开本地笔记，但只有应用生成的固定标题、合法参数、非失败验证状态和唯一标准 `note-UUID` 条目同时成立时才出现“查看笔记”。多结果、空结果、失败、非法 ID、伪造标题或其他工具均保持无入口。
- 导航只传递稳定 note ID；本地笔记页通过现有 ViewModel/Store `get(id)` 二次读取完整正文，删除、tombstone 或不存在时不展示旧摘要、不创建新数据，沿用现有“笔记已不存在”事实。
- 本阶段不新增 `notes.get/delete/edit` 工具，不修改 MessagePart/Room Schema、写入权限、后台能力或任意 App 能力；下一阶段继续围绕完整个人 Agent 闭环选择一个直接可用场景。

## 第 167 阶段：中断筛选空状态与历史回退（完成）

- “已中断”筛选没有 `FAILED / CANCELLED` 时显示明确的复盘边界，说明不会重放工具；如果任务中心仍有其他历史，提供“显示全部”回到完整 Run 列表。
- 普通筛选的空状态保持原文案，回退按钮只在中断筛选且存在其他历史时出现，避免空数据库出现无效操作。
- 本阶段只补任务中心展示交互，不修改 Run、恢复、重试、取消、Room Schema、工具权限或后台能力。下一阶段继续选择单一真实个人 Agent 场景。

## 第 166 阶段：恢复入口聚焦中断 Run（完成）

- Agent 任务中心新增“已中断”筛选，只匹配已经落定的 `FAILED / CANCELLED` Run；`QUEUED / WAITING_APPROVAL / EXECUTING` 等活动或可恢复状态不会被归入该视图。
- 第165阶段启动恢复提示的入口现在使用该初始筛选；用户仍可切换到全部、处理中、需确认、可重试和已完成，设置页手动进入任务中心仍默认显示全部。
- 本阶段只增加任务中心展示筛选，不修改 Run 终态、恢复、重试、取消、Room Schema、工具权限或后台能力。下一阶段继续选择单一真实个人 Agent 场景，不扩展任意 App 或高级生态。

## 第 165 阶段：启动恢复提示直达任务中心（完成）

- 启动恢复提示现在携带受限的一次性 `OPEN_AGENT_RUN_HISTORY` 动作；失败/取消数量、不会重放工具和隐私边界保持第164阶段不变，不把目标、Run ID 或原始错误放入 UI 动作数据。
- 只有恢复提示带“查看任务”按钮，点击先刷新当前 Agent Run 历史，再打开现有 Agent 任务中心并消费提示；普通设置保存、备份和网络请求结果继续是不可点击的短提示。
- 本阶段只增加应用壳导航和 `OperationResult` 的可选动作字段，不修改恢复策略、Room、Workflow 对账、工具权限或后台能力；第166阶段进一步让该入口默认聚焦中断 Run，不改变第165阶段的导航边界。

## 第 164 阶段：启动中断 Run 用户提示（完成）

- 应用启动先沿用现有恢复策略保留待审批、已提交待验证和已验证可继续的 Run；其余旧进程中间态由 `closeInterruptedRuns()` 原子收敛后，再按同一候选 ID 回读 Room 真实终态。
- 只有本次实际收敛且回读为 `FAILED / CANCELLED` 的 Run 才汇总为一个一次性提示，明确不会重放工具并引导前往 Agent 任务中心；提示只显示分类数量，不包含目标、会话、Run ID、内部错误或工具正文。
- 该切片不修改 Repository、恢复策略、Workflow 对账、Room Schema、工具权限或后台能力；可恢复 Run 继续走原审批/验证恢复链，不因新增提示被关闭。聚焦 JVM `5/5`、Debug/AndroidTest APK 与 Redmi 文档 corpus gate `1/1` 通过。
- 下一阶段继续选择真实用户场景，不重复实现已有失败 ToolResult/typed verification 原子收敛矩阵，也不提前扩展任意 App、后台设备动作或高级生态。

## 第 163 阶段：取消结果后的任务快照刷新（完成）

- 普通 `/agent` 收到可信 `tasks.cancel` 终态后，前台宿主主动重新读取 Workflow、ScheduledTask 和关联 Run 的 Room 快照；用户从会话结果进入任务中心时，不再依赖页面首次打开或手动刷新才能看到取消后的状态。
- 刷新只由唯一 `tasks.cancel`、成功、typed `VERIFIED` 且命中应用生成取消文案的 `VerifiedAgentContext` 触发；模型自由文本、`READABLE_ONLY / FAILED`、重复取消和其他工具均不触发，避免不可信结果制造状态刷新假象。
- 该切片不新增工具、权限、Room Schema 或后台能力，不改变取消副作用、旧 Run 不变和第162阶段名称解析门禁。聚焦 JVM `8/8`、Debug/AndroidTest APK 与 Redmi 文档 corpus gate `1/1` 通过，Pixel_9 未使用。
- 下一阶段再评估启动恢复后的失败/取消用户可见提示，不重复实现已有失败 ToolResult/typed 验证原子收敛矩阵。

## 第 162 阶段：取消结果任务中心导航（完成）

- 已验证的 `tasks.cancel` Tool part 复用现有“查看任务”入口，用户可以从取消结果直接进入任务中心查看最新任务与关联运行，而不需要重新搜索。
- 导航门禁要求成功、typed `VERIFIED`、参数集合严格为 `{name}`、首行包含与参数完全一致的任务名，并命中应用生成的计划取消/后台停止/停止请求/已取消收敛前缀。
- 点击后仍只把任务名带入应用壳；宿主等待当前 Room Workflow 快照，再按唯一精确名称解析 Workflow ID。任务删除、重命名、同名、模型伪造、换行注入和未验证结果都不会定位旧内部 ID。
- 该切片不新增工具、权限、Room Schema 或后台能力，不改变 `tasks.cancel` 副作用和第161阶段会话终态。聚焦 JVM `TaskInspectionNavigationTest 5/5`，Debug APK 和 Redmi 文档 corpus gate 通过。
- 下一阶段再从真实用户场景选择通用执行恢复或任务结果交互，不扩展任意 App、后台设备动作、精确定时、Foreground Service 或高级生态。

## 第 161 阶段：受控任务取消会话终态（完成）

- `tasks.cancel` 的 ToolResult 只代表取消副作用已经通过正式执行器和 typed verification；普通 `/agent` 现在从同一可信 `VerifiedAgentContext` 生成一次会话终态摘要，避免用户停留在模型自由撰写的“已取消”描述。
- 终态投影只接受唯一的 `tasks.cancel` execution、`success=true`、`AgentVerificationStatus.VERIFIED` 和应用生成的稳定结果前缀。任务名统一空白、限制 100 字符；内部 Run/Task ID、参数以外的原始回执和模型文本不进入摘要。
- 计划取消、后台停止、停止请求和重复取消分别显示稳定状态；Workflow 内调用、多个取消 execution、`READABLE_ONLY / FAILED` 或未知结果文案均不生成摘要，避免跨入口或不完整证据升级为完成事实。
- 摘要与 Agent assistant 结果在同一个会话快照中保存，旧 Run、任务取消副作用、答案级 shadow 和任务中心投影边界不变。
- 聚焦 JVM `TaskCancelCompletionPresentationTest 4/4 + TaskRetryCompletionPresentationTest 4/4`，Debug APK 构建通过；更新后的文档 corpus gate 仅在 Redmi 复验。下一阶段再评估通用执行恢复矩阵，不把取消结果投影扩展为后台设备自动化或任意 App 能力。

## 第 160 阶段：受控任务取消（完成）

- 前台直接 Agent 新增独立 `tasks.cancel(name)`，与 `tasks.retry` 使用独立 Skill、风险元数据和审批；工具仅允许在当前 Agent context 中暴露，必须经过用户确认。
- 取消依据 Room 持久化事实按精确名称解析当前唯一活动 ScheduledTask，仅支持 `SCHEDULED / RUNNING / STOP_REQUESTED`；同名 Workflow、多实例、缺失任务、名称漂移和前台手动 Run 均 fail-closed。
- 停止复用 `ScheduledWorkflowStopCoordinator` 和 WorkManager cancel/fallback，以 `STOP_REQUESTED` 作为持久化取消栅栏；重复调用返回 `AlreadyCancelled`，迟到 Worker/模型结果不能覆盖 `CANCELLED`，旧 Run 与既有副作用保持不变。
- 聚焦 JVM、Debug/AndroidTest APK 与 Redmi `RoomAgentTaskStoreInstrumentedTest 9/9` 通过。Redmi 真实 Provider 严格完成 `tasks.list -> tasks.inspect -> tasks.cancel`，得到 `taskStatus=CANCELLED / taskCancel=true / oldRunUnchanged=true`，临时 Profile、任务夹具和测试包均清理（测试包本来未安装）。
- 下一阶段继续补齐取消后的任务中心/会话结果投影或通用执行恢复；不扩展到前台手动 Run、后台设备动作、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 或本地模型。

## 第 159 阶段：受控任务重试用户可见终态（完成）

- `tasks.retry` 的 ToolResult 仍只确认关联新 Run 已原子提交并排队；模型总结和排队文案不能升级为任务完成事实。
- 前台宿主在关联 Run 真正收敛后，按 Room 终态向原会话追加一次结果。成功明确已完成、复用步骤数与旧 Run 不变；阻塞、失败和取消说明稳定恢复边界，并引导用户前往任务中心查看受限诊断。
- 终态投影不接收 Workflow/Run/Step ID、原始错误或步骤正文，任务名会清理换行并限制为 100 字符；失败与取消不会恢复或重放旧执行栈。进程内仍按关联 Run ID 去重启动，不新增任务权限、Room Schema、后台执行或第二套 Runtime。
- 聚焦 JVM 三类 `50/50` 与 Debug APK 通过。仅向 Redmi 安装；真实 Provider 再次完成 `tasks.list -> tasks.inspect -> tasks.retry -> 前台 Workflow`，日志确认来源 `FAILED`、新 Run `COMPLETED`、复用 1 步、旧 Run 不变且 `completionVisible=true`，临时 Profile 与夹具均已清理。
- 下一阶段可单独设计受控任务取消或停止，但必须分别冻结审批、幂等、迟到结果和已提交副作用语义；不从重试闭环顺带扩权。自然 LMK、主动断网、5 至 10 分钟任务、任意 App、后台设备动作、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 与本地模型继续后置。

## 第 158 阶段：真实 Provider 受控任务重试闭环（完成）

- 在第 157 阶段的受控重试生产能力之上，使用 Redmi 当前 Provider 完成 `tasks.list -> tasks.inspect -> tasks.retry -> 前台 Workflow` 完整闭环。
- Debug-only 失败夹具只用于提供稳定的 `FAILED` 来源 Run；真实模型、审批、Tool Ledger、typed verification、幂等回执和 `TaskRetryLaunchPolicy` 均走正式入口。
- 来源 Run 保持不变，新 Run 通过 `retryOfWorkflowRunId` 关联；成功前缀只复用为 `SKIPPED`，首个未完成步骤由真实 Provider 执行并完成目标级验证，不重放已完成前缀。临时 Profile 已删除，夹具 Workflow 已停用。
- Redmi 真实闭环、聚焦 JVM、Debug/AndroidTest APK 和文档 corpus gate 均通过；Pixel_9 未接收命令。
- 下一阶段继续选择单一真实个人 Agent 场景，优先补齐任务操作的用户可见结果与错误恢复边界；不从本阶段扩展任意 App、后台设备动作、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 或本地模型。

## 第 157 阶段：受控任务重试（完成）

- 在第 155/156 阶段只读任务清单、诊断和答案级定位之后，补齐一个受控写操作：用户明确要求重试时，Agent 通过审批调用 `tasks.retry(name)`，只处理当前最新可重试 Run，不回退历史失败 Run。
- 新 Run 使用 ToolCall ID 的确定性幂等身份，成功前缀只在新 Run 中复用为 `SKIPPED`，来源 Run、旧步骤和已有副作用保持不变；同一 ToolCall 已启动或状态漂移后不再回读为“已排队”。
- 前台宿主在 Room Tool Ledger、typed `PASSED` 和提交回执全部一致后才启动关联 Workflow，并用 `TaskRetryLaunchPolicy` 二次检查 `QUEUED`、同会话和步骤状态；Workflow 内递归、后台和旧 Profile 扩权继续关闭。
- 聚焦 JVM `130/130`、Debug/AndroidTest APK 和 Redmi `RoomAgentTaskStoreInstrumentedTest 8/8` 通过；本阶段未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 下一阶段：在 Redmi 上用真实 Provider 形成一次 `tasks.list -> tasks.inspect -> tasks.retry -> 前台 Workflow 执行` 的完整闭环；若现有任务数据不足，使用 Debug-only、可清理的失败任务夹具，不修改生产工具边界。随后再进入通用执行恢复/长任务可靠性，不提前引入精确定时或 Foreground Service。

## 第 156 阶段：任务诊断答案级导航（完成）

- 在第 155 阶段 `tasks.list -> tasks.inspect` 真实只读诊断之后，补齐答案到任务中心的用户闭环：可信工具卡直接提供“查看任务”，唯一名称命中时定位对应 Workflow，用户无需重新搜索。
- 入口只信任已验证 Agent Tool part，并严格限制成功状态、工具名、唯一 `name` 参数与结果头。点击不重发消息、不重试 Run、不修改任务；历史消息不保存内部 ID，删除、重命名、缺失和同名都失败关闭到通用列表。
- 该切片复用现有 Workflow 管理页、导航一次性目标和 ViewModel 的 Room Workflow 刷新，不新增 Room Schema、任务工具、Profile/Skill 权限、后台能力或 Android 权限。定位只在最新快照加载完成后解析，读取失败同样降级通用列表。聚焦 JVM、Debug/AndroidTest APK 和 Redmi `ConversationPageInstrumentedTest 9/9` 通过。
- 至此“任务清单 -> 最近运行受限诊断 -> 答案级查看任务”形成直接可用的只读闭环；第 157 阶段已补齐受控重试，任务取消和停止仍需分别设计审批与副作用语义，不能从重试工具顺带扩权。

## 第 155 阶段：任务最近运行只读诊断（完成）

- 在第 144/146 阶段任务清单与真实查询基础上，补齐“这个任务最近为何没有完成、执行到哪一步”的直接 Agent 场景；新增 SAFE、仅前台的 `tasks.inspect`，必须使用 `tasks.list` 返回的精确名称定位。
- 投影只包含任务公开摘要、最近 Run 状态/触发/时间、步骤序号/状态和稳定诊断分类。内部 ID、原始错误、步骤目标与输入输出、模型文本、工具参数和 ToolResult 正文不进入 Agent 上下文；同名任务拒绝猜测。
- 该切片复用 Room v35 和既有 WorkflowRepository，不新增 Schema、UI 页面、权限、任务修改/取消/重试或后台执行；旧 Profile 不自动获得新工具，历史 Run 的冻结工具面保持不变。
- 聚焦 JVM `57/57`、Debug/AndroidTest APK 和 Redmi Room `5/5` 通过。真实 Provider Run `run-91db12f3-7b7d-445f-bf19-3a4ef92be06e` 严格完成 `tasks.list -> tasks.inspect`，两项均 `PASSED`，最终回答只基于受限投影。
- 下一阶段继续选择新的单一真实使用场景；若要开放任务操作，应分别设计取消、重试和审批语义，不能从只读诊断顺带扩权。笔记编辑/分页/批量、后台设备动作、自然 LMK、主动断网、Foreground Service 和高级生态继续后置。

## 第 154 阶段：本地笔记受控删除（完成）

- 在第 152 阶段自然语言写入与第 153 阶段列表/搜索/详情之上，补齐用户撤回误记或敏感笔记的直接控制：只能从详情发起并二次确认，不向 Agent 开放删除工具。
- 生产删除清空标题/正文，但保留原 note ID 与 ToolCall 幂等键作为不可见 tombstone。list/search/get 不返回 tombstone；历史 `notes.create` 重放命中 tombstone 后失败，不能恢复用户已撤回内容。
- 该方案复用 Room v35，不新增迁移、Profile/Skill 权限、后台写入或 Runtime；Debug 验收临时数据仍可按精确 ID 硬删，生产用户内容不能走该路径。
- ViewModel 在确认前零副作用，提交后移除当前快照并刷新原列表/搜索；若刷新失败，删除成功事实与刷新失败分别展示，不把已提交副作用降级为失败。
- 聚焦 JVM、Debug/AndroidTest APK 与 Redmi ViewModel `2/2`、页面 `2/2`、真实 Room `1/1` 均通过；未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 下一阶段继续选择新的单一真实使用场景；笔记编辑需要先设计历史 ToolCall 与版本审计语义，分页/批量、后台笔记、自然 LMK、主动断网、5 至 10 分钟任务和 Foreground Service 继续后置。

## 第 153 阶段：本地笔记只读管理入口（完成）

- 第 152 阶段已证明 Agent 能经审批、幂等写入并回读本地笔记；本阶段补上用户可直接查看结果的设置入口，形成“自然语言写入 -> Room 持久化 -> 用户列表/搜索/详情核对”的可见闭环。
- 设置根新增“本地笔记”，最近列表和标题/正文搜索各最多 10 条，点击后按稳定 ID 显示完整正文及创建/更新时间；标题和返回入口固定在滚动区之外。
- 页面状态由独立 `LocalNoteManagementViewModel + RoomAgentNoteStore` 承担，不继续扩张主 ViewModel，不新增 Room Schema、Agent Runtime、Profile/Skill 权限或后台能力。
- 本阶段保持严格只读，不提供创建、编辑、删除或批量操作；Agent 写入仍只能走第 152 阶段已有的 `notes.create` 审批、ToolCall ID 幂等和写后回读链。
- 定向 JVM、Debug/AndroidTest APK 和 Redmi ViewModel `1/1`、页面 `2/2`、设置根 `5/5`、真实 Room `1/1` 均通过；未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 下一阶段继续选择一个能直接增强个人 Agent 使用闭环的单一场景；笔记分页/编辑/删除、后台写入、自然 LMK、主动断网、5 至 10 分钟任务和 Foreground Service 不与本阶段捆绑。

## 第 152 阶段：本地笔记写入闭环（完成）

- 选择回到可直接体验的个人 Agent 主线：用户自然语言提出“记录一条本地笔记”的目标，真实 Provider Agent 在冻结的 `local-notes` Skill 和 `notes.search / notes.create / notes.list` Profile 白名单内规划并执行；没有新增 Runtime、后台能力或 Room Schema。
- `notes.create` 沿用 `REQUIRES_APPROVAL`、ToolCall ID 幂等键、Room 事务写入和 operation 回读验证；回读不一致仍不能宣称完成。Redmi 真实 Run `run-66b689fb-6ff3-410f-a851-e0f91765047a` 的审批为 `APPROVED`，Tool Ledger 为 `success=true / executorVerified=true / PASSED`，按唯一标题搜索回读成功。
- Debug 探针只在 `src/debug` 提供 `notes_create_real`，持久化审批 gate 仅用于真机验收，不进入 Release；验证完成后删除本阶段测试笔记、删除临时 Profile、恢复用户原 Profile，Run/审批审计保持不变。为防清理误删，新增精确 note ID 删除回归，Redmi `OK (1 test)`。
- 本阶段相关 Debug 编译、AndroidTest APK、定向 JVM 和 Redmi 单项均通过；不运行完整 JVM、全量 Lint 或 Release。该闭环不扩展笔记删除用户入口、后台笔记操作或任意文件写入。
- 这条闭环补上了“本地写入副作用也能完成审批、幂等、回读和可检索核对”的直接体验缺口。下一阶段优先继续个人 Agent 的真实可用闭环，再根据具体用户场景选择下一个单一能力；自然 LMK、主动断网和 5 至 10 分钟长任务证据仍不足以引入 Foreground Service。

## 第 151 阶段：真实 WorkManager 长任务、熄屏与受控中断恢复（完成）

- Debug 探针只负责通过正式 `RoomWorkflowRepository + WorkManagerScheduledTaskScheduler + ScheduledWorkflowWorker` 创建和观察 8 步 `app.current_time` 任务；临时 Profile 只授权该工具，状态查询确认任务终态时恢复用户原 Profile、删除临时 Profile 并停用探针 Workflow，下一次创建前也会清理上次残留，没有创建第二套 Runtime。
- Redmi 普通后台 Task `scheduled-task-1684ca82-dfb0-45e7-94a7-7a5908094a92` / Run `workflow-run-f20ecc64-e375-47ba-813d-8516297eb920` 为 `8/8 COMPLETED`、Worker 耗时 `95816ms`。熄屏 Task `scheduled-task-0d5a2c12-b952-40cf-b236-ab121ac06263` / Run `workflow-run-e9aa7e03-8557-451e-972c-af56de8051e0` 为 `8/8 COMPLETED`、耗时 `91915ms`；后半程持续 `Wakefulness=Dozing`，PID 全程为 `8228`，两次 `exit-info` 均无新增退出记录。
- 人工 `force-stop` Task `scheduled-task-0b0b35d7-e705-46f8-b235-71e786ba1bf1` / Run `workflow-run-b2f58179-839a-4687-ac68-2b2d02687089` 在旧 PID `8228` 被明确记录为 `USER REQUESTED / FORCE STOP`，新 PID 为 `9134`。恢复不续跑旧执行栈，而是保留已经完成的 4 步，将剩余 4 步和 Task/Run 安全收敛为 `CANCELLED`，不重放工具或后续步骤。
- 真机中断暴露“ToolResult 已 `PASSED`、但 Agent 尚未总结”窗口：恢复曾把 Tool Ledger 的已验证工具传给 `CANCELLED` 步骤，触发“未完成步骤不能持久化已验证工具”并遗留 `RUNNING`。Repository 现在只有在 Workflow Run 真正 `COMPLETED` 时才把 verified tool names 写入步骤输出；取消/失败仍保留独立 Tool Ledger 审计，但不能升级为已验证完成。
- `testDebugUnitTest / assembleDebug / assembleDebugAndroidTest` 成功；新增 Redmi Room 回归 `workerReentryClosesOnlyLinkedAgentAndScheduledTaskWithoutCreatingNewRun` 与更新后文档 corpus 均为 `OK (1 test)`。未运行 Lint、Release 或默认完整 instrumentation。
- 本阶段只证明约 92 至 96 秒 WorkManager 任务在普通后台和 Dozing 下可以完成，以及人工 `force-stop` 后可以安全取消旧执行链；自然 LMK、主动网络失败和 5 至 10 分钟真实任务仍未验证。当前不引入 Foreground Service，下一阶段应回到可直接体验的个人 Agent 能力或等待真实长任务证据，不用人为延时制造结论。

## 第 150 阶段：今日安排与提醒总览 Skill（完成）

- 新增只读 `day-overview` Skill，将已有 `calendar.list_events` 与 `tasks.list` 组合为一个可直接体验的“今天有哪些安排和提醒”入口；最终回复必须区分日程与小灵任务事实。
- Skill 只复用既有前台工具、日历主动授权和 Profile/Skill 双重白名单，不创建新权限、Room Schema 或 Runtime；日历写入、任务修改/取消/执行、后台 Workflow 和静默权限继续关闭。
- 聚焦 JVM `AgentSkillsTest 15/15 + XiaoLingToolRegistryTest 40/40` 通过，Debug APK 构建成功；Redmi `wsvwypiz7xwslvl7` 真实 Run `run-535a90af-b45c-4b18-8574-0aa4c91e6268` 在同一 Run 内依次完成 `calendar.list_events` 与 `tasks.list`，两项均 `success=true / PASSED`，最终回答通过“日程/任务”来源分区检查。
- 首次使用原 Provider 的 `gpt-5.4-mini` 时，第二轮规划返回空工具名并按规则失败；未覆盖该失败 Run。按 `AGENTS.md` 兜底配置恢复后完成闭环。
- 本阶段不新增系统日历字段、任务写入、后台执行、Room Schema 或新权限；下一步回到个人 Agent 主线的真实使用问题，不重复扩展总览字段。

## 第 149 阶段：系统日历标题关键词查找（完成）

- 在第 148 阶段日历只读能力上新增 SAFE `calendar.search_events`，允许用户在未来 1 至 30 天内按日程标题关键词查找，最多返回 20 条；仍只返回标题、起止时间和全天标记。
- 查询复用 `READ_CALENDAR` 主动授权和前台边界，Provider 只投影最小字段，关键词匹配在内存中完成；地点、描述、参与人、账户和日历写入继续不进入 Agent。
- 新增独立 `calendar-search` Skill，不改写旧 `calendar-overview`，既有 Profile 不因新工具或 Skill 自动扩权；后台 Workflow、定时任务和静默权限请求继续关闭。
- 聚焦 JVM `XiaoLingToolRegistryTest + AgentSkillsTest`、Debug/AndroidTest APK 构建通过；仅使用 Redmi `wsvwypiz7xwslvl7` 运行真实 Provider 单项 `AndroidCalendarEventReaderInstrumentedTest`，结果 `OK (2 tests)`，覆盖有界读取与不存在标题的空结果。设备没有可安全创建的日程，因此不伪造标题匹配样本。
- 本阶段未运行完整 JVM、全量 Lint、Release 或默认完整 instrumentation；若无新的用户场景，不继续扩展系统日历字段或写入能力。

## 第 148 阶段：系统日历只读能力（完成）

- 在完整个人 Agent 主链上新增一个明确的系统能力切片：用户主动授权后，前台 Agent 可通过 SAFE `calendar.list_events` 读取未来 1 至 30 天的近期日程；最多 20 条，只返回标题、起止时间和全天标记。
- 日历权限独立于设备 Agent/Accessibility，设置页明确只读范围、隐私边界和 Profile/Skill 显式授权要求。既有 Profile 不自动扩权；不读取地点、描述、参与人、账户，不创建、修改或删除系统日历，不支持后台 Workflow。
- Redmi 真实 Provider 查询 `1/1`，设置页/根入口 instrumentation `7/7`；默认 Agent 真实计划 `1/1`，唯一工具为 `calendar.list_events`，结果为未来 7 天无日程。
- 真实使用同时暴露并修复计划拆步问题：计划提示现在禁止把整理、展示、总结或回复用户拆成独立工具步骤，避免为了满足每个 Run 的工具事实门槛而额外调用无关工具；`PersonalTaskPlanPolicyTest 12/12` 通过。
- 下一阶段继续围绕可直接体验的个人 Agent 主线推进；系统日历写入、后台设备自动化、MCP、远程 Channel、多 Agent、跨设备同步和本地模型仍后置。

## 第 147 阶段：真实多步 Runtime 可靠性与后台时长评估首轮（完成）

- 修复真实 8 步 SAFE Workflow 暴露的两个阻断：模型紧邻重复请求相同且已通过 `RESULT_READABLE` 验证的 SAFE 只读工具时，Runtime 复用已有结果完成，不执行第二次；设备动作、写工具、需要审批或一般重复调用继续由指纹门禁拒绝。
- 当前 Agent Run 尚无工具事实却返回 `complete` 时，应用侧只允许一次带明确边界的纠错重试；再次提前结束仍失败。Workflow 后续步骤同时声明前序结果只作为数据，不能替代当前 Run 的工具执行。
- Redmi 前台 Run `workflow-run-84097511-b21d-4d89-9098-ed439625eba8` 耗时 `104156ms`，8 个步骤均恰好 1 次工具调用、1 次结果和 1 次验证，目标级结论为 `VERIFIED / ALL_CRITERIA_VERIFIED`。
- Redmi 熄屏 Run `workflow-run-2153667c-f664-4034-a566-79a114899c27` 启动约 3 秒后熄屏，耗时 `94155ms`，8/8 完成且目标级 `VERIFIED / ALL_CRITERIA_VERIFIED`。系统持续 `Wakefulness=Dozing`，同一 PID 在熄屏后继续完成模型请求；`dumpsys activity exit-info` 前后没有新增退出记录。
- 聚焦 JVM `MultiStepAgentRuntimeTest 8/8 + WorkflowStepExecutionPolicyTest 14/14`，合计 `22/22`；AndroidTest APK 构建成功，仅在 Redmi 运行更新后文档 corpus，结果 `OK (1 test)`、耗时 `2.468s`。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。
- 当前生产计划最多 8 步，真实耗时约 94 至 104 秒，尚不足以形成 5 至 10 分钟样本，也没有自然 LMK/系统回收证据。本阶段不引入 Foreground Service、不开放后台设备动作、不声称已经验证长时恢复；下一步先寻找真实超过数分钟的生产任务场景，再依据证据决定长任务恢复策略。

## 第 145 阶段：OEM 时钟兼容与三步个人 Agent 闭环（完成）

- `DeviceActionPolicy` 集中声明计算器、时钟的 AOSP/Google 等价应用族。`device.open_app` 仍优先请求冻结包名，仅在该包没有启动入口时尝试同族白名单实现；Controller、Workflow Safety 和答案级 Decision 使用同一等价判断，其他应用仍严格拒绝。
- 对“返回小灵 / 回到小灵”步骤，本地工具面在新鲜 `device.snapshot` 后只开放 `device.back`，并让不可见的 `device.open_app` 定义也不可解析；不修改整份任务冻结包名，不扩大审批或白名单。
- 聚焦 JVM 六组 `95/95`、完整 JVM `904/904`、Debug APK 通过。仅使用 Redmi `wsvwypiz7xwslvl7` 覆盖安装并真实运行三步 Workflow `workflow-run-e1b22a9e-28f9-468a-9046-a5830c0c4f7f`：`app.current_time`、`device.snapshot -> device.open_app -> device.snapshot`、`device.snapshot -> device.back` 全部 `PASSED`，两项动作均 `executorVerified=true`，实际时钟包为 `com.google.android.deskclock`，最终前台为 `com.longdev.xiaoling`，目标级结论为 `VERIFIED / ALL_CRITERIA_VERIFIED`。
- 本阶段没有运行全量 Lint、AndroidTest APK、默认 instrumentation 或 Release；既有失败 Run 与关联来源链保持不变。

## 第 144 阶段：任务/提醒只读总览（完成）

- 新增 SAFE `tasks.list` 和内置 `task-overview` Skill，Agent 可以读取最近更新的 Workflow、启停状态、步骤数、最近 Run 状态与下次应用内提醒时间。
- 投影只复用已有 Room 事实，不返回内部 ID、错误详情或步骤输出；不修改、取消或执行任务，不接入系统日历。
- `tasks.list` 不支持后台执行。既有 Profile 不自动获得新工具/Skill，需要用户显式开启；历史 Run 的冻结工具面保持不变。
- 聚焦 JVM `48/48`、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的 Room 单项 `OK (3 tests)` 和文档 corpus `OK (1 test)` 通过。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 142 阶段：完成结果定向查看 Workflow（完成）

- 完成卡携带对应 `workflowId`，点击“查看任务”后由应用壳进入 Workflow 管理页；提交后失败但没有目标 ID 的通用入口保持不变。
- Workflow 管理页在列表数据加载后自动滚动到目标项并展开该 Workflow，返回设置根页会清理一次性导航目标；不改变 Room、Runtime、审批、权限或执行恢复语义。
- 聚焦 JVM `17/17`、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的两个 Compose 类 `OK (18 tests)` 通过。按快速迭代分级不运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 143 阶段：定向 Workflow 导航重建保存（完成）

- Activity 重建时同时保存知识文档和 Workflow 两个一次性导航目标，完成卡跳转不会因旋转或宿主重建丢失目标。
- Tab、设置子页与根页返回时间继续不持久化；返回设置根页仍清理目标，避免旧任务污染下一次手动进入。
- 本阶段只收紧导航状态保存边界，不扩展 Room、Runtime、审批、设备权限或后台自动化。

## 第 127 至 132 阶段：完整个人 Agent 主线（全部完成）

后续开发改为“先跑通完整个人 Agent，再集中打磨细节”。这里的完整主线固定为：用户以自然语言提出目标，Agent 读取允许使用的记忆与本地知识，生成 1 至 8 步临时计划并等待确认，在前台限定 App 中调用既有应用/设备工具，逐动作执行既有审批和验证，形成目标级本地结论，持久化任务事实，并在中断后从已验证前缀创建关联新执行继续或重试。旧 Run、旧动作和已提交副作用保持不变。

第 127 阶段交付自然语言个人任务入口与可确认计划；第 128 阶段完成限定 App 多动作；第 129 阶段完成目标级本地验证；第 130 阶段接入长期记忆、本地知识和 WorkManager 非精确定时提醒；第 131 阶段完成任务级关联恢复；第 132 阶段以三条 Redmi 完整任务和统一门禁完成主线验收。最终完整 JVM `879/879`、Lint、Debug/AndroidTest APK 和 Redmi `282/282` 通过；Release 仍只在用户明确要求时进行。

## 第 133 阶段：个人任务计划交互首轮打磨（完成）

- 计划生成、立即任务创建和提醒创建改用独立进度状态；页面显示对应文案，停止按钮不再把计划生成笼统写成普通“停止生成”。
- 生成失败、创建失败或持久化落定前主动停止都会保留原始目标与具体原因，并提供重新生成。重试使用失败快照目标，确认弹层“返回修改”继续恢复原目标。
- 确认后的前台操作绑定原会话和计划 ID。会话切换会使旧代次失效并取消当前 Job；已创建 Run 按既有 Ledger 收敛为取消，未创建事实不伪造执行消息，迟到成功、失败或 finally 不能覆盖新会话状态。
- 提醒确认在 Android 13+ 通知权限返回前保持等待，确认与返回都不可重复触发；权限回调只有在同一计划仍有效时才提交。拒绝通知权限后仍创建用户已经确认的应用内提醒，沿用“系统通知可能不可见”的产品语义。
- 本阶段不扩展工具、Room Schema、后台设备控制或定时精度。相关 JVM、Debug/AndroidTest APK 通过；Redmi 解锁后的两个 Compose 类最终为 `OK (9 tests)`（`12.418s`）。文档 corpus 首轮、两次证据写回及冻结文本复验均为 `OK (1 test)`（`2.522s / 2.512s / 2.529s / 2.327s`）。不重复第 132 阶段完整门禁，不构建 Release。

## 第 134 阶段：计划生成成本可见性（完成）

- 确认前计划弹层展示当前单次模型请求的真实调用次数、总耗时、TTFB、Prompt 字节数和 Provider 返回的 input/output/total tokens，帮助用户判断计划生成的实际延迟与用量。
- TTFB 或 Token usage 缺失时明确显示未知，不用 0 或估算值补齐；本阶段不做货币成本估算、不建价格表、不累计跨计划历史。
- 遥测只保存在待确认 UI 状态，确认前后都不写 Room、RunEvent、Workflow 或 Agent Run；计划生成请求仍不属于执行链上的 LLM 事件。
- 聚焦验证通过：相关 JVM、Debug/AndroidTest APK，以及仅 Redmi `wsvwypiz7xwslvl7` 的两个 Compose 类 `OK (10 tests)`（`13.583s`）。按快速迭代分级不运行完整 JVM、全量 Lint、默认完整 instrumentation、文档 corpus 或 Release。

## 第 135 阶段：常用任务模板快捷入口（完成）

- 任务模式新增三个受控模板：打开计算器、搜索系统设置、打开时钟，均来自已经在 Redmi 上验收过的限定 App 目标。
- 模板选择只回填现有任务输入框，不自动发送、不提前请求模型、不创建 Workflow/Run、不直接执行设备动作；用户仍沿用 `目标 -> 计划 -> 确认 -> 执行` 主链。
- 模板不携带包名授权，不扩大工具白名单、审批或目标级验证；最终计划继续由 Profile 边界、严格 Schema 和本地策略共同校验。
- 聚焦验证通过：相关 JVM、Debug/AndroidTest APK，以及仅 Redmi `ConversationPageInstrumentedTest` `OK (6 tests)`（`9.751s`）；更新后的文档 corpus 首轮/证据写回后复验均为 `OK (1 test)`（`2.459s / 2.616s`）。按快速迭代分级不运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 第 136 阶段：首个 App 兼容扩展（完成）

- 候选评审后只新增 Google 天气 `com.google.android.apps.weather`。Chrome 会把节点观察与动作面扩到任意网页，联系人、短信和文件直接涉及高敏个人数据，日历容易与后置系统日历集成混淆，因此本阶段全部拒绝，不开放任意 App。
- 天气包进入精确 Manifest queries、`DeviceActionPolicy` 和 `device.open_app.package_name` enum；不申请 `QUERY_ALL_PACKAGES`。任务模式新增“查看天气”，仍只回填目标，不自动发送、创建 Workflow/Run 或绕过计划确认。
- 天气复用原生产 `snapshot -> open_app` 的 Room Approval、Accessibility overlay、Tool Ledger、Executor/typed 验证、答案级本地 Decision 和动作后包名核对；独立场景不能复用计算器审批。Redmi 最终为 `APPROVED / executorVerified=true / PASSED / afterPackage=com.google.android.apps.weather / answerDecision=VERIFIED`，且不产生可复用节点引用。
- 天气页面可能展示用户当前选择的粗粒度位置文本，只允许在用户主动发起查看天气时作为前台观察内容处理；不扩到后台采集、新的持久化旁路或跨任务节点引用。
- 聚焦 JVM `64/64`、Debug/AndroidTest APK、Redmi 包可见性与模板两个定向单项通过；更新后的文档 corpus 首轮/证据写回后复验均为 `OK (1 test)`（`2.409s / 2.606s`），当前不再改写的文本用于最终冻结复验。按快速迭代分级不运行完整 JVM、Lint、默认完整 instrumentation 或 Release。

## 第 137 阶段：计划上下文请求精简（完成）

- 保留 system 安全规则、用户目标、规划时间、工具/App 边界和计划 Schema，只对长期记忆与本地知识正文增加 8 KiB UTF-8 全局预算；检索授权、失败阻止生成、确认和执行语义不变。
- 记忆与知识按稳定交替顺序选择，预算只接受完整条目；知识正文与记忆完全相同时不重复发送。使用数量、省略数量和上下文字节与真正发给模型的同一请求对象绑定。
- 确认页展示上下文实际占用；只有发生去重或预算省略时才明确显示各来源省略数，不估算货币成本，也不新增 Room、RunEvent、Workflow 或 Agent Run 历史。
- 聚焦 JVM `12/12`、Debug/AndroidTest APK、Redmi Compose `OK (5 tests)` 和显式真实模型 `OK (1 test)` 通过。真实模型请求使用上下文 `7,264B`、Prompt `11,190B`，记忆 `2/3`、知识 `1/3`，返回 1 步可解析计划；主应用、Provider 和前台状态已恢复，测试包已卸载。未运行完整 JVM、Lint、默认完整 instrumentation 或 Release。
- 下一阶段继续以完整个人 Agent 的真实使用问题为依据，优先处理已观察到的计划等待/失败、常用任务闭环或限定 App 兼容问题；没有用户证据时不连续堆模板/App，也不回到纯结构瘦身或 Shadow 扩样。

## 第 138 阶段：计划生成取消重试闭环（完成）

- 用户主动停止计划生成时，必须保留原始目标并展示稳定的失败卡与“重新生成”入口；不能只清除 loading 让用户重新回忆目标。
- 取消回写必须绑定原计划 request ID 和会话。会话切换/删除先使旧 ID 失效，旧协程即使随后收到 `CancellationException` 也不得向新会话写入失败卡或目标。
- 本阶段只覆盖计划尚未创建 Workflow/Run 的取消状态，不改变已确认立即任务、应用内提醒、Room 事实、关联重试或设备工具执行语义。
- 聚焦验证为 JVM `1/1`、Debug/AndroidTest APK 和 Redmi `ConversationPageInstrumentedTest` `OK (6 tests)`（`9.791s`）；完整 JVM、Lint、默认完整 instrumentation 和 Release 留到里程碑。
- 下一阶段继续从真实使用证据中选择计划等待/失败、常用任务闭环或单个限定 App 兼容切片；没有明确场景时不连续增加模板/App，不回到纯结构瘦身或 Shadow 扩样。

## 第 130 阶段：记忆、知识与应用内提醒（完成）

- 已完成计划上下文纵向切片：当前 Profile 只有在允许 `memory.search` 且记忆总开关、单次召回开关均开启时读取长期记忆；只有允许 `knowledge.search` 时读取本地知识。每类最多 3 条、每条最多 800 字符，检索异常阻止生成，无命中继续普通计划。
- 上下文在结构化计划 Prompt 中明确为不能扩权的不可信只读事实；确认页只展示长期记忆和知识片段使用数量。Profile 工具白名单、首批允许应用、逐动作审批、Room Ledger 和目标级本地验证均保持原边界。
- 应用内提醒已经复用现有一次性/Daily/Weekly Workflow、WorkManager 非精确调度和通知能力。确认前不写 Room；确认后 Workflow 与首个调度实例原子创建且不产生 Manual Run。修改/取消继续由用户在既有调度管理入口明确操作，不开放自然语言静默变更。
- 定时计划在模型输出和本地解析两层禁止目标 App、`device.*` 完成标准和设备最终应用；需要审批的其他动作不能在后台自动获批，只能进入既有待处理通知。系统日历、精确闹钟、Foreground Service 和第二套 Runtime 未引入。
- 最终验证为 `PersonalTaskPlanPolicyTest 7/7`、Debug/AndroidTest APK、Redmi Room/Compose 两个定向单项和真实模型 `ONCE / delay=30`。没有运行完整 JVM、Lint、Release 或默认完整 instrumentation。

这些阶段必须各自产生用户可直接体验的新能力，不再把纯重构、单层 evidence、Shadow 扩样或文档整理单独作为主线阶段。截图/视觉、后台设备控制、任意 App、精确定时、MCP、系统日历、远程 Channel、多 Agent、跨设备同步和本地模型继续后置；它们不作为完整前台个人 Agent MVP 的前置条件。

## v0.1.15 历史发布基线

`v0.1.15` 以 `versionCode 16` 汇总 `v0.1.14` 后第 122 至 127 阶段：`device.swipe` 完整前台 Workflow 链和自然语言个人任务与可确认计划。Release APK 为 `3,318,322` 字节，SHA-256 为 `a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`；[GitHub Release](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.15) 已发布并成为 latest。本轮按用户明确要求只执行 `assembleRelease`，没有额外运行 JVM、完整 Lint、Debug/AndroidTest APK、签名/zipalign 复核、Redmi 安装或 instrumentation；此前阶段的聚焦证据继续有效，但不冒充本次发布门禁。发布后的主干已完成第 132 阶段完整里程碑和第 133 至 136 阶段真实使用打磨，开发数据库保持 Room v35，尚未形成新的 Release。

## 第 131 阶段：任务级恢复与关联重试（完成）

- 复用现有 `WorkflowRunRetryPolicy`、`RoomWorkflowRepository.retryRun()` 和 Workflow 管理确认入口；不创建第二套 Runtime，不恢复旧模型协程、旧 Executor 或未知提交执行栈。
- 只有 `BLOCKED / FAILED / CANCELLED` 且存在不完整步骤的旧 Run 才能进入重试。连续成功前缀在新 Run 中标记为 `SKIPPED` 并通过 `reusedFromStepId` 回指来源步骤；首个未完成步骤及后续步骤保持 `PENDING`，由新 Run 重新执行。
- 已启动过的失败步骤仍要求用户二次确认；确认时沿用当前会话、Profile、Provider 和工具边界预检。旧 Run、旧步骤、已提交副作用和审计事件保持不变，新 Run 通过 `retryOfWorkflowRunId` 关联来源。
- 聚焦验证为 `WorkflowStepExecutionPolicyTest 13/13`、Debug/AndroidTest APK；仅 Redmi `wsvwypiz7xwslvl7` 的 `retryReusesCompletedStepsAndKeepsSourceRunUnchanged` 为 `OK (1 test)`、`0.444s`。测试包已卸载，设备已恢复 `MainActivity`。没有运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 第 129 阶段：目标级本地验证与最终回答约束（完成）

任务计划的严格 Schema 新增 `verification.required_tool_names` 和 `verification.expected_final_package`。必需工具只能来自当前 Agent Profile 白名单并按预期顺序填写；最终应用只能为空或首批允许包。确认弹层在执行前展示这两项标准，确认后以 `workflow-goal-verification-contract-v1` 冻结用户原始目标和完成标准，手动运行、定时运行、步骤准备/启动和关联重试都沿用 Run 快照，不能由模型或后续配置改写。

Room 升级到 v35：`workflows.goalVerificationContract` 和 `workflow_runs.goalVerificationDecision` 均为 nullable。v34→v35 不回填历史记录；旧 Workflow 没有 Contract 时继续保持无目标级 Decision，非空但损坏或版本漂移的 Contract 会阻止新 Run，不能降级成 legacy 任务。每个成功步骤的已验证工具名从同 Run Tool Ledger 中按顺序重建，只保存工具名，不复制参数、原始结果、snapshot/ref、节点正文、坐标或 HMAC。

`WorkflowGoalVerificationPolicy` 允许辅助工具夹在必需工具之间，但要求必需工具保持子序列顺序；`COMPLETED` 或关联重试的 `SKIPPED` 步骤只有携带冻结的已验证工具事实才计入已验证步骤。最终应用取时间最新的 `device.snapshot.capturedAt` 或设备动作 `afterObservedAt`。全部标准满足时输出 `VERIFIED`，有可信进度但工具/步骤/最终应用不完整时输出 `PARTIAL`，没有已验证进度时输出 `INCOMPLETE`；最终用户文案完全由本地策略生成，模型正文不能升级结论。

聚焦 JVM 为 `WorkflowGoalVerificationPolicyTest 6/6`、`WorkflowStepExecutionPolicyTest 13/13`、`PersonalTaskPlanPolicyTest 3/3`，合计 `22/22`；Debug 与 AndroidTest APK 构建成功。仅 Redmi `wsvwypiz7xwslvl7` 的 v34→v35 迁移、Contract/Decision 持久化、损坏 Contract 拒绝和确认弹层合并为 `OK (5 tests)`（`3.33s`）。真实 `snapshot -> swipe(up) -> snapshot -> back` production Registry tracer 为 `success=true / actions=swipe, back / verified=2/2 / approvals=0 / freshSnapshots=true / finalPackage=com.longdev.xiaoling / goalDecision=VERIFIED / privacySafe=true`。文档语料门禁移除易腐的历史测试数量查询并增加逐查询排名诊断，更新后的 Redmi 首轮/写回后复验均为 `OK (1 test)`（`2.461s / 2.444s`）。按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation；下一主线进入第 130 阶段。

## 第 128 阶段：限定 App 多动作连续执行（完成）

自然语言计划现在可以冻结首批允许包中的 `target_app_package`，并把它贯穿 Workflow、Run 与所有步骤快照。Room v34 只为 Workflow 增加 nullable 目标包列，v33 旧记录保持 `null`；手动运行、定时运行和关联重试均不能改写原任务的目标应用。生产策略 `workflow-device-action-safety-v2` 在 Runtime 外独立执行本地门禁：`open_app` 只能打开目标包，引用动作必须在目标包内开始并结束，`back / home` 只能从目标包起步，离开后必须显式重新打开目标包才能继续引用动作。

页面变化后，Runtime 只允许紧跟已验证设备动作重新调用同参数 `device.snapshot`，连续观察和重复副作用仍被循环指纹拒绝。Redmi `wsvwypiz7xwslvl7` 在系统设置应用详情页用同一真实 Agent Run 完成 `snapshot -> swipe(up) -> snapshot -> back`，两项动作均为 production Registry typed `PASSED`、零审批、使用新 snapshot，最终回到小灵；日志为 `success=true / verified=2/2 / freshSnapshots=true / targetPackage=com.android.settings / privacySafe=true`。八组聚焦 JVM `92/92`、Debug APK 及三个 Room/Compose 定向真机单项通过。该证据只确认限定 App 内动作级连续执行，不把动作成功、模型文本或历史 ref 当作任务目标完成；这一缺口随后由第 129 阶段补齐。

## 第 127 阶段：自然语言个人任务与可确认计划（完成）

对话页现有“对话 / 任务”模式。任务模式不要求 `/agent` 前缀，先使用当前 Agent Profile 冻结的 Provider、模型、API 模式和工具白名单生成任务名与 1 至 8 个顺序步骤；Chat Completions 使用 `response_format.json_schema`，Responses 使用 `text.format`，客户端仍严格拒绝额外字段、JSON 外文本、错误类型、空步骤与数量越界。计划弹层显示原目标、步骤、Agent/模型、工具边界及可能审批项，API Key 只留在 ViewModel 私有执行快照。确认前不创建消息、Workflow、Run、审批或 Tool Ledger；取消恢复原目标，切换/删除会话会撤销在途请求或丢弃待确认计划。

本阶段只冻结 Profile 的模型与工具白名单，不把长期记忆或本地知识正文注入计划请求；完整主链中的记忆/知识计划上下文仍按既定顺序由第 130 阶段交付。第 127 阶段“完成”只表示自然语言入口、严格计划、确认边界和既有执行链接通，不代表第 130 阶段能力提前完成。

确认后 `RoomWorkflowRepository` 在单事务创建普通 Workflow、步骤定义、手动 Run 和全部步骤快照，再复用既有 Workflow/Agent Runtime、审批、验证与 Room Ledger；确认计划会保留在工作流管理中。聚焦 JVM `34/34`、Debug/AndroidTest APK 和仅 Redmi 的 Compose + Room `OK (2 tests)`（`2.03s`）通过。真实模型生成单步 `Read Current Time` 计划；首个 Runtime Run 因 `60000ms` 模型规划超时保持失败，第二个手动 Run 在同一 Workflow 下独立完成 `app.current_time`、参数校验、工具执行、后置验证和总结，旧 Run 没有被覆盖。更新后的文档语料首轮为 `OK (1 test)`（`2.453s`），写回后的最终资产已复验通过。后续第 128 阶段限定 App 多动作连续执行已经完成。

## 第 126 阶段：`device.swipe` 生产默认接线与 Redmi 真实链（完成）

前台手动 Workflow 的生产默认 Registry 现精确开放 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。`swipe` 继续是 SAFE 零审批动作，并复用第 122 至 125 阶段冻结的 snapshot/ref/TTL、当前 window generation、同窗方向 evidence、Executor/typed 验证、动作后观察和答案级脱敏边界；方向、viewport/HMAC、snapshot/ref、节点正文和坐标仍不进入持久层。

TDD 转绿后 `XiaoLingToolRegistryTest` 为 `36/36`，六个相邻测试类合计 `101/101`，Debug/AndroidTest APK 构建成功。仅 Redmi `wsvwypiz7xwslvl7` 的真实生产 `snapshot -> swipe` tracer 为 `success=true action=swipe verified=true approvals=0 registryCompletion=PASSED answerDecision=VERIFIED privacySafe=true`，前后包均为 `com.android.settings`；更新后的项目文档语料首轮/最终单项均为 `OK (1 test)`，耗时 `2.307s / 2.3s`。测试包已卸载，主应用、Accessibility 和 crash buffer 已完成收尾。这只完成首批限定 App/页面能力，不承诺任意 App；后台/定时设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## v0.1.14 发布基线（完成）

`v0.1.14` 汇总 `v0.1.13` 之后 31 个工程提交，并由版本与文档提交封版：通用执行恢复矩阵、提交状态未知分类、用户确认的受控安全重放、失败 ToolResult/typed 验证的原子终态结算、Room v33 answerability Shadow 跨进程匿名账本与单次采样窗口，以及前台 Workflow `snapshot / open_app / back / home / tap_ref / type_text` 生产闭环。发布不恢复旧 Executor、模型协程或 Workflow 后续步骤，不扩大 `swipe`、后台设备自动化、任意 App、生产 answerability enforcement、精确定时或 Foreground Service 边界。

发布门禁为 Gradle `141/141` tasks、JVM `837/837`、Lint `0 error / 56 warnings / 0 information`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign、v2 正式单签名和仅 Redmi 默认完整 `OK (271 tests)`（`121.242s`）。Release APK 为 `3,301,938` 字节，SHA-256 为 `927579c852ab272a08bd82412821ea7779fb57363f67598660e50a1017e2fc6a`。

## 第 125 阶段：`device.swipe` 答案级脱敏 Decision 与 Room/UI 投影（完成，生产未开放）

已经通过 Registry 专属完成门禁、Executor 验证和 typed `PASSED` 的 `swipe` 通用摘要，现在可以从同 Run Tool Ledger 重建为答案级 Decision，并进入 Workflow step output、关联重试和 Compose 证据卡。持久层只保存动作、前后包名、后置计数/截断/时间和规则版本；方向、viewport/HMAC、snapshot/ref、节点正文和坐标仍只驻留当前执行链。UI 只显示“滚动”与通用后置摘要，历史引用不可复用，也不会为 SAFE swipe 伪造审批证据。

三个聚焦 JVM 测试类合计 `44/44`、Debug/AndroidTest APK 和仅 Redmi `wsvwypiz7xwslvl7` 的 Room + Compose 两个单项均通过，真机单项结果为 `OK (2 tests)`（`3.615s`）。生产 Workflow 继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`；下一阶段在全部后台设备自动化保持关闭的前提下，单独把 `device.swipe` 接入生产默认集合，并只用 Redmi 验收真实生产 Workflow `snapshot -> swipe`。

## 第 124 阶段：`device.swipe` Registry 完成态交接与 Redmi 限定验收（完成，生产未开放）

Registry 已把 Controller 当前动作产生的 `DeviceActionOutcome.swipeEvidence` 交给既有专属完成策略，但只接受与本次授权前后 snapshot 精确绑定的证据：`beforeSnapshotId` 必须匹配，before/after viewport 的包名、window 与 generation 必须分别匹配已授权 snapshot 和真实后置 snapshot。错串窗口或旧动作证据不会形成完成 evidence。Result codec 只增加无节点正文的 `swipe` 通用摘要，完整 viewport/HMAC 继续只驻留当前执行链；DecisionPolicy、Room/Workflow output 和 Compose 尚未接入。

八组聚焦 JVM 为 `91/91`，Debug APK 构建成功。仅 Redmi `wsvwypiz7xwslvl7` 在系统设置应用详情页运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 的 `snapshot -> swipe(up)`，得到 `success=true / verified=true / approvals=0 / registryCompletion=PASSED / privacySafe=true`，前后包均为 `com.android.settings`；这证明首个限定 App/页面，不承诺任意 App。测试后小灵恢复前台，Accessibility 为 `Enabled / Bound / Crashed services:{}`，crash buffer 无本应用异常。按快速迭代分级未运行完整 JVM、Lint、AndroidTest、Release 或默认完整 instrumentation，也没有使用模拟器。

生产 Workflow 继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。第 125 阶段随后完成 swipe 的答案级脱敏判定、Room/Workflow output 与 UI 投影；生产默认集合与 Redmi 生产链仍留给下一独立阶段。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 123 阶段：`device.swipe` Controller/Registry HMAC evidence seam（完成，生产未开放）

Controller 现在以每实例随机 256-bit 密钥和结构化 `HmacSHA256` 为当前滚动目标及其未脱敏语义后代生成执行期匿名身份；锚点身份绑定当前滚动目标，重复语义身份全部丢弃。成功 snapshot 与 ref 共用内存生命周期，并在 capture/动作失败、显式清理或 Agent Run 切换时同步撤销；inspection 在同一生命周期锁内核对 snapshot/ref 并生成 viewport。直接 `/agent` 的 `swipe` 不再接受 generation-only 证明，必须满足同应用、同 window、同目标、generation 前进、可见内容变化，以及共同锚点按请求方向发生至少 `8px` 的主位移；反向、横向占优、矛盾、内容不变和锚点不足都保持未验证。

`inspectReference()` 已把动作前匿名 viewport 暴露给 Registry 的显式测试集合，并在证据构造后复读 window generation，期间页面变化时不返回 target/viewport；SAFE 滚动使用真实执行时钟核对 30 秒 TTL。设备层与 Workflow 层共享 viewport/anchor 类型和方向验证器，完整前后 evidence 不进入通用 codec、Result codec、Room、日志、Workflow 输出或 UI。聚焦 JVM `89/89` 通过；按快速迭代分级未运行完整 JVM、Lint、APK、Release、Redmi instrumentation 或真实滚动。

生产 Workflow 继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。第 124 阶段随后接通 Registry 完成态纯内存 evidence，并只在 Redmi 对系统设置应用详情页完成真实滚动验收；Result codec 仅识别通用摘要，Room、答案级 UI 与生产工具集合继续不变。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 122 阶段：前台 Workflow `device.swipe` 专属安全契约（完成，生产未开放）

纯 Kotlin `WorkflowSwipeSafetyPolicy` 已冻结滚动的最小执行与完成证据：精确 `snapshot_id / ref / direction`、启用且未脱敏并支持 `SWIPE` 的当前目标，以及包含同应用、window、generation、目标指纹和至少两个去重匿名锚点的动作前 viewport。`swipe` 依据既有 SAFE ToolDefinition 使用 `SAFE_NO_APPROVAL`，但仍受同 Run/ToolCall、新鲜 snapshot/ref、30 秒 TTL、generation 与完整 Executor/typed/动作后观察门禁约束。

完成时必须证明同应用、同 window、同一目标、generation 前进和可见匿名内容变化；至少一个共同锚点还要按请求方向产生不小于 `8px` 且主方向占优的位移，任一显著共同锚点反向或横向占优时整体拒绝。专属授权只保存方向和动作前 viewport SHA-256 摘要，不复制包名、snapshot/ref、目标或完整锚点。聚焦 JVM `55/55` 通过；本阶段未运行 APK、Lint、Release、Redmi instrumentation 或真实滚动。

生产 Workflow 仍精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。第 123/124 阶段随后完成 Controller/Registry 执行期 opaque/HMAC evidence seam、完成态纯内存交接与 Redmi 限定验收，并替代 generation-only 成功判断；Result codec 仅识别通用摘要，Room、答案级 UI 与生产工具集合继续不变。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 121 阶段：前台 Workflow `device.open_app` 生产闭环（完成）

前台手动 Workflow 的生产工具面扩展为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。`open_app` 只接受唯一 `package_name`，在 SafetyPolicy、ApprovalGate 与 Executor 三层限制为首批四个包，并逐包绑定 Room/Accessibility overlay 审批、当前 Run/ToolCall/session、30 秒 snapshot 和 generation；完成门禁与答案级 Room 重建再次要求后置包名等于获批目标。

六组聚焦 JVM `95/95`、Debug/AndroidTest APK、仅 Redmi Compose/Room 单项和真实 `snapshot -> open_app(com.android.calculator2)` tracer 均通过；首次超时审批按设计拒绝，稳定窗口重试成功。第 122 阶段随后转向 `swipe` 的纯安全契约，生产工具面没有继续扩大。

## 第 120 阶段：前台 Workflow `device.home` 生产闭环（完成）

前台手动 Workflow 的生产工具面现精确为 `device.snapshot / device.back / device.home / device.tap_ref / device.type_text`。`home` 只接受空参数并固定为 `SAFE_NO_APPROVAL`，不创建 Room Approval 或 Accessibility 审批浮层；异常审批对象也不能替代当前执行时钟。零审批不放宽其他门禁：仍要求明确步骤意图、当前 Run/Step/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 window generation、Executor 验证、typed `PASSED` 和动作后重新观察。

后置验证通过系统 `ACTION_MAIN + CATEGORY_HOME` 动态解析 launcher 包，不写死 Redmi 或其他厂商桌面。答案级 Decision、Workflow step snapshot、Room 下一步与 UI 已贯通“返回桌面”；该动作不生成节点目标或可复用 ref，后续动作必须重新观察并按各自风险规则执行。六组聚焦 JVM 合计 `87/87`、Debug/AndroidTest APK 通过；仅 Redmi 的 Compose、Room 纵向单项和真实 tracer 均通过，真实链为 `snapshot -> home / SAFE / approvals=0 / PASSED / VERIFIED`。同步后的文档 corpus 首轮为 `OK (1 test)`（`2.76s`），写回后最终复验同样通过。

下一阶段继续一次只做一个动作，只从 `open_app / swipe` 中选择；每项仍需独立冻结风险、审批或 SAFE 依据、后置验证、答案级证据和 Redmi 验收。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 119 阶段：前台 Workflow `device.back` 生产闭环（完成）

前台手动 Workflow 的生产工具面现精确为 `device.snapshot / device.back / device.tap_ref / device.type_text`。`back` 只接受空参数并固定为 `SAFE_NO_APPROVAL`，不创建 Room Approval 或 Accessibility 审批浮层；异常审批对象也不能替代当前执行时钟。零审批不放宽其他门禁：仍要求明确步骤意图、当前 Run/Step/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 window generation、Executor 验证、typed `PASSED` 和动作后重新观察。

答案级 Decision、Workflow step snapshot、下一步、关联重试、Room 投影和 Compose 已能展示“返回”，同时保持“只确认当前动作和后置观察，不确认最终业务目标”的边界。审批证据继续只覆盖 `tap_ref / type_text`，因此不会为 SAFE `back` 伪造审批卡。五组聚焦 JVM、Debug/AndroidTest 编译与 APK 通过；仅 Redmi 的 Compose 和 Room 纵向单项均为 `OK (1 test)`，真实 tracer 为 `back / verified=true / approvals=0 / VERIFIED`，同步后的文档 corpus 首轮与最终复验均为 `OK (1 test)`（`2.733s / 2.725s`）。

第 120 阶段随后以相同 SAFE 导航边界完成 `home`。后续继续一次只做一个动作，只从 `open_app / swipe` 中选择；每项仍需独立冻结风险、审批或 SAFE 依据、后置验证、答案级证据和 Redmi 验收。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 118 阶段：统一直接 `/agent` 的 `type_text` 持久化隐私（完成）

`device.type_text` 的原文现在统一只驻留当前执行进程。通用 `DeviceTypeTextAuditPolicy` 同时服务 Workflow 与直接 `/agent`，把 Runtime proposed/validated、ToolCall ledger、Room Approval 与审批事件、Workflow gate、`VerifiedAgentContext` 和消息 Tool parts 收窄为 `snapshot_id / ref / text_sha256 / text_length`；Repository 作为所有审批调用方共享的最后一道持久化边界，即使未来入口直接传入原始 ToolCall 也不能绕过。

当前进程的普通会话审批卡仍显示真实输入，方便用户确认即将写入的内容，但它必须先证明 Room 请求与内存 ToolCall 的 ID、工具名、风险和安全投影完全一致。最终复审把该校验移动到审批 ticket 注册之前，并补齐四类身份漂移反例，拒绝路径不会遗留活动 waiter。历史任务中心、会话恢复和消息重建只读取安全投影。由于指纹不能恢复原文，应用重启后的 `type_text` 待审批 Run 不再进入 `APPROVAL_WAIT`；策略固定返回 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE`，旧 Approval 与旧 Run 按启动事务安全取消，用户必须创建新 Run 重新确认。

TDD 覆盖直接/Workflow Runtime 审计、Room 审批、跨进程恢复和当前进程 UI 身份绑定。提交前的持久化旁路复核又发现 `VerifiedAgentContext` 仍使用 Executor 原始 ToolCall 构造，会随消息 parts 进入历史；新增失败断言后复用同一投影修复。最终聚焦 JVM `130/130`、Debug/AndroidTest APK 通过，仅 Redmi 的直接 Room 脱敏、重启收敛和既有 Workflow Room 投影 3 个单项均为 `OK (1 test)`；文档 corpus 三轮写回门禁也均为 `OK (1 test)`（`3.101s / 2.693s / 2.920s`）。本阶段不开放其他 Workflow 动作或后台设备工具，不运行完整 JVM、Lint、Release 或默认完整 instrumentation。

下一阶段可以在统一隐私边界保持不变的前提下，从 `swipe / open_app / back / home` 中选择一个单一前台 Workflow 动作切片；每项仍需独立冻结用户意图、风险/审批、后置验证、答案级证据和 Redmi 验收，不能批量放开。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 117 阶段：前台 Workflow `type_text` 生产闭环（完成）

第 117 阶段完成时，前台手动 Workflow 的生产设备工具面为 `device.snapshot / device.tap_ref / device.type_text`。文本动作继续复用第 115/116 阶段的敏感输入拒绝、当前 ref 可编辑/未脱敏节点证据、最小指纹授权、同 Run/ToolCall、Executor/typed 验证、动作后观察和原 `nodePath` 精确回读；该阶段的 `open_app / back / home / swipe` 与全部后台/定时设备工具仍在规划清单和 Executor 两层关闭。

`WorkflowDeviceActionApprovalGate` 为 `type_text` 创建独立 Room 审批；第 117 阶段的 Workflow 审计投影让 proposed/validated/ToolCall ledger 与审批统一只持久化 `snapshot_id / ref / text_sha256 / text_length`。原文只在当前 ToolCall 内存中供执行与回读；Accessibility 浮层只展示步骤意图和“输入 N 个字符，内容不展示”，答案级判定、下一步、关联重试与 Compose 只保留无原文白名单摘要。该阶段当时没有改变直接 `/agent`；第 118 阶段随后已用通用 `DeviceTypeTextAuditPolicy` 统一直接 `/agent`、Workflow 和可信消息上下文的持久化边界。

Redmi overlay 移除后的连续窗口事件通过 `100ms` settle 收敛：只有活动根和完整窗口集合仍精确回到基线才返回用户决定，外来窗口、内容变化、服务断连、超时及最终漂移全部 fail-closed。真实 tracer 已得到 `type_text / verified=true / APPROVED / VERIFIED / exactReadBack=true`，并复核 ToolCall ledger 无原文；Compose、Room Approval 和 Room Workflow 纵向单项各为 `OK (1 test)`。提交前 Spec 复核发现并修复 proposed/validated/ToolCall ledger 原文旁路，聚焦 JVM 增至 `75/75`，Debug/AndroidTest APK 通过；按快速迭代分级未运行完整 JVM、Lint、Release 或默认完整 instrumentation。

第 118 阶段随后已统一前台直接 `/agent` 的持久化隐私，同时保留当前进程会话卡显示原文的核对体验；进程重建后的旧文本审批改为专属 fail-closed 处置。后续转向下一个独立最小动作切片，`swipe / open_app / back / home` 仍需逐项完成专属意图、审批或 SAFE 依据、后置验证和 Redmi 验收，不能批量放开。后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 116 阶段：前台 Workflow `type_text` evidence seam（完成，生产未开放）

当前短生命周期 ref 已能返回 enabled、editable、redacted 和动作集合；`type_text` 动作后只按动作前原 `nodePath` 在新 references 中定位目标，并读取该节点 `text`。页面其他节点出现相同文本、目标路径不唯一或目标无动作后 ref 时不能误判成功。强类型 readback 只驻留 Controller/Registry 执行链，不增加 Room 字段或通用结果原文。

Registry 默认生产集合仍只有 `device.tap_ref`；JVM 测试态显式加入 `type_text` 后，执行前节点 evidence、原始 identity、最小指纹授权、同 Run/ToolCall 和动作后精确回读可以完成全链。结果 codec 可识别无原文 `type_text` 摘要，但答案级 DecisionPolicy 仍要求工具名和结果动作都为 `tap_ref`，Compose 没有提前扩权。

主线程双轴复审补齐测试 seam 的权限边界：构造注入集合现在只能是 `{device.tap_ref, device.type_text}` 子集，`open_app / back / home / swipe` 会在 Registry 创建时拒绝。聚焦 JVM `67/67`、Debug/AndroidTest APK 通过。Redmi 系统设置搜索框真实输入 `stage116_exact_readback` 为 `success=true / verified=true`；随后敏感输入返回 `SENSITIVE_INPUT`，界面仍保留原安全文本。更新后的文档 corpus 首轮为 `OK (1 test)`、耗时 `4.358s`；Gradle 收尾曾卸载主包并清除配置，现已完成 Debug 重装、Accessibility 重授权、Provider/Profile 恢复及 `/models + /responses` 可用性复核。最终 corpus 改用保留主包的手动 instrumentation 流程。第 117 阶段已补齐 Room 独立审批、Accessibility overlay、无原文答案级判定/UI 和真实 Redmi Workflow，并把 `type_text` 加入生产前台 Workflow；其他前台动作、后台自动化及后置能力继续关闭。

## 第 115 阶段：前台 Workflow `type_text` 专属安全契约（完成，生产未开放）

新增纯 Kotlin `WorkflowTypeTextSafetyPolicy`，把文本输入从通用设备动作白名单中隔离出来。执行前只接受精确 `snapshot_id / ref / text` 参数，继续复用敏感文本预审计，并要求当前节点启用、可编辑、未脱敏且支持 `TYPE_TEXT`。专属授权只保存规则版本、Run/ToolCall 身份、文本 SHA-256 与长度，不保存原文、snapshot ID 或 ref；通用授权中的 `type_text` identity 也会移除 `text`。

通用 `WorkflowDeviceActionSafetyPolicy` 在第 115 阶段已对 `type_text` 强制委托专属策略：缺少目标证据、专属授权或动作后精确回读时，即使显式加入通用白名单也会以 `TYPE_TEXT_POLICY_DENIED` 拒绝。完成还要求同一 Run/ToolCall、Executor 与 typed 验证、动作后已验证观察和正确时序。该阶段当时的生产 Registry 工具面仍精确为 `device.snapshot / device.tap_ref`，强行执行 `type_text` 会失败且不会触发设备控制器；第 117 阶段已在不放宽上述门禁的前提下完成生产接入。

新增策略先取得预期编译 Red，再完成 `4/4` 新契约测试；相邻通用策略、文本规则、Registry 与敏感参数预审计为 `35/35`，合计 `39/39`，Debug/AndroidTest APK 通过。双轴子代理仍被本地 `/responses` 404 阻断，主线程 Standards/Spec 复审未发现遗留 finding。更新后的文档 corpus 仅在 Redmi 为 `OK (1 test)`、首轮耗时 `2.658s`，证据写回后的最终 assets 已以同一单项复验通过。本阶段没有执行真实文本输入、完整 JVM、Lint、Release 或默认完整 instrumentation。

第 116 阶段随后已完成当前 ref 节点证据、原路径精确回读、Registry 测试态生命周期与 Redmi 直接动作正反例；生产白名单仍保持不变。`type_text` 只有在 Room/overlay、无原文答案级证据和真实 Workflow 验收形成单一闭环后才可评估开放；`swipe / open_app / back / home`、全部后台设备自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 114 阶段：Workflow 设备动作答案级证据 UI（完成）

前台 Workflow 现在能把已持久化的 `workflow-device-action-decision-v1` 投影为只读动作证据卡。成功卡只显示 `tap_ref`、动作前后应用、后置节点/脱敏/截断/观察时间、规则版本和明确能力边界；节点引用固定失效，不能从历史页面恢复执行。step output、previous outputs、Run result 和 Run error 中的原始动作 JSON 会统一隐藏。

失败链从同一 Agent Run 的 Room 审批记录投影，稳定区分用户拒绝、普通取消、窗口变化、审批浮层不可用、Accessibility 服务断连和 BUSY；审批已通过后发生的窗口变化或服务断连会显示为执行验证失败。审批按 SQLite 参数上限分块批量读取，原始参数与决定原因只在 IO 边界用于分类，进入 `XiaoLingUiState` 前已收敛为安全枚举；跨 Run 记录拒绝绑定，多次尝试不会互相遮蔽。

固定基线 `f300dfc` 的 Standards/Spec 双轴复审发现并修正两项：补齐全部生产 overlay 窗口/浮层原因签名，以及保留“先取消、后批准但执行验证失败”的两条事实。聚焦 JVM 最终 `30/30`，Debug/AndroidTest APK 成功；仅 Redmi 页面 `OK (4 tests)`、批量审批读取 `OK (1 test)`、Room 判定到 UI 投影 `OK (1 test)`，更新后的文档 corpus `OK (1 test)`。本阶段没有开放新动作、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App，也没有运行 Lint、Release 或默认完整 instrumentation。

该阶段规划的 `type_text` 独立隐私、参数和后置验证契约已在第 115 阶段完成，第 116 阶段又接通节点 evidence 与精确回读；生产开放仍等待 Room/overlay、答案级证据和真实 Workflow 闭环。

## 第 113 阶段：前台 Workflow `tap_ref` 首个生产切片（完成）

前台手动 Workflow 现在只新增 `device.tap_ref`，模型必须先在同一 Agent Run 执行并验证 `device.snapshot`；Workflow 设备工具面精确为 `snapshot + tap_ref`。每一步用当前 `preparedStep.detail` 创建独立审批 gate，Room 先冻结请求身份，Accessibility overlay 再展示用户意图；非 `tap_ref` 审批和直接 `/agent` 继续走原会话卡。旧 Run、旧进程审批、旧 snapshot/ref、页面 generation 漂移、30 秒过期、持久化身份漂移和额外结果字段全部 fail-closed。

正式 overlay 标题为 `XiaoLingDeviceActionApproval`，只使用 `TYPE_ACCESSIBILITY_OVERLAY / NOT_FOCUSABLE / NOT_TOUCH_MODAL / SECURE`，并按导航栏 inset 上移。按钮决定先移除浮层，只有窗口事件确认自有 overlay 消失后才返回 Runtime；窗口集合只有在“基线”或“基线 + 唯一自有 overlay”时才抑制 generation 作废。动作后重新观察，Tool Ledger 只保存可审计原结果，Workflow 输出只保留 `workflow-device-action-decision-v1` 白名单判定，不含 snapshot ID、ref、节点或参数。

聚焦 JVM `45/45`、完整 Debug JVM `784/784`、Debug/AndroidTest APK 均通过；更新后的项目文档 corpus 为 `OK (1 test)`、耗时 `2.596s`。双轴复审又把原始参数彻底移出 overlay 请求数据面，并要求持久 Tool Ledger 显式满足 `executorVerified=true + typed PASSED`；复审后受影响 JVM `108/108`、Debug/AndroidTest APK、Redmi Room 正反向 `OK (2 tests)`（`0.775s`）与最终文档 corpus `OK (1 test)` 通过。Redmi 最终真实 Run `run-7f252a9c-711b-466f-bd5b-ff2a545605b7` 为 `COMPLETED`，审批 `APPROVED`，`device.tap_ref` 为 `success=true / executorVerified=true / verification=PASSED`，后置 snapshot 观察到“动作已完成”。首轮因人工核对超过 30 秒被 TTL 正确拒绝，另一次 generation 已变化也被拒绝，均没有绕过安全闸口。测试包已卸载，Accessibility 已恢复 `Bound / Crashed services:{}`；在线模拟器只出现在设备列表，没有收到定向命令。本阶段未运行 Lint、Release 或默认完整 instrumentation。

第 114 阶段已完成设备动作答案级证据 UI，并让拒绝、取消、窗口变化、浮层不可用、服务断连和 BUSY 具有稳定可见状态。后续不批量开放动作，先单独冻结 `type_text` 的隐私与安全契约；`swipe / open_app / back / home`、后台设备工具、截图、坐标、视觉定位和任意 App 继续关闭。

## 第 112 阶段：前台 Workflow 有限设备动作安全契约（完成，生产未开放）

前台 Workflow 设备动作先经过独立纯 Kotlin `WorkflowDeviceActionSafetyPolicy`，生产默认白名单为空。后续显式动作只有同时绑定用户步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 已验证 snapshot、30 秒最大 TTL、window generation、实时 ref、当前进程逐动作审批和完整参数，才可能获得不可变 `workflow-device-action-safety-v1` 授权。后台、旧 Run、关联重试、前一动作、进程重建前审批、旧 ref 与页面漂移均 fail-closed。

完成资格要求同一授权与动作身份、成功 ToolResult、Executor 验证、typed `tool.verify=PASSED` 和强类型后置观察。后置 snapshot 必须绑定当前 Agent Run 与动作 ToolCall，动作完成时间晚于审批、观察时间不早于动作完成；取消后迟到结果、旧授权和任一证据缺失不得收敛为完成。双轴审查补齐初版遗漏的最大 TTL 与后置身份/时序绑定，最终聚焦 JVM 为新策略 `11/11`、观察判定 `4/4`、Tool Registry `20/20`，合计 `35/35`。

本阶段没有接入 Registry、Room、Accessibility 或 Workflow 生产执行链，没有执行 APK、Lint、Release、文档 corpus、Redmi instrumentation 或真实动作。第 113 阶段随后只选择 `device.tap_ref` 完成首个生产切片；截图、坐标、视觉定位、任意 App 和全部后台设备工具继续关闭。

## 第 111 阶段：Workflow 设备观察真实双 Run 闭环（完成）

真实前台 Workflow 已完成“观察 Run → 本地判定 → 独立消费 Run”。第一 Run 只调用 `device.snapshot`，第二 Run 只调用 `app.current_time`作为新 Run 的 SAFE 工具事实，然后只使用前序本地判定回答。两个 ToolResult 均 `success=1 / PASSED / executorVerified=NULL`，没有任何设备动作或审批。Debug Receiver 写 Room 后活动 ViewModel 不会自动刷新 Profile；本次验收通过冷启动重建运行态，没有因调试旁路放宽生产权限。

真机首轮成功后继续以持久数据验收，发现第一步的 Workflow `outputSnapshot.text` 仍保留模型转述的 `snapshot_id`。现在 Repository 完成事务会重新回查同 Run Tool Ledger，将 step `result/outputSnapshot`、前台 Workflow 消息和后台会话文本统一收窄为 `workflow-device-observation-v1` 白名单判定。双轴审查又补齐 `completeRun()` 单步骤兼容旁路，单步骤和多步骤最终 Run result 都只从净化 step 聚合。未验证证据在步骤完成前即 fail-closed，调用方判定与 Ledger 漂移也会被拒绝。最终真实 Run 的第一步输出和第二步前序输入均为 169 字符，不含 `snapshot_id / nodes / ref`；UI 显示已验证、有限可复核、规则版本和节点引用过期。聚焦 Redmi instrumentation 为 `OK (5 tests)`，Debug/AndroidTest APK 构建与最终项目文档 corpus `OK (1 test)` 均通过。

这一阶段关闭了只读设备观察在 Workflow 中的首条真实可组合链。它当时仍未开放设备动作，不开放任何后台设备工具，也不增加截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service。第 112 阶段完成安全契约冻结，第 113 阶段随后只接入 `device.tap_ref` 首个生产切片。

## 第 110 阶段：Workflow 设备观察本地判定（完成）

前台 Workflow 新增纯 Kotlin `workflow-device-observation-v1` 规则。它只消费同 Run、成功、验证通过且结构合法的 `device.snapshot` Tool Ledger，把包名、节点/脱敏数、截断状态和采集时间投影为“可复核”或“有限可复核”；规则明确不确认节点正文、用户目标完成、页面仍然有效或动作授权。窗口标题、snapshot/window 身份、ref、bounds、actions 和原始 JSON 均不进入本地判定。

新 Run 会把安全判定写入既有 Workflow output snapshot。下一步骤不再接收可能包含完整快照或模型语义扩张的步骤正文，而是重新通过 `agentRunId` 回查 Ledger，并用版本化本地判定替换；关联重试通过 `reusedFromStepId` 回查来源步骤，持久判定与 Ledger 漂移、来源缺失、未验证或畸形证据都会 fail-closed，后续 Agent Run 不会启动。第 111 阶段起，原始工具结果只保留在独立 Tool Ledger 中审计；新 Workflow step 的 `result/outputSnapshot` 本身也只保留本地判定。

Workflow 证据卡现在同时显示已验证来源、本地判断、规则版本、受限原因和结论范围。聚焦 JVM `19/19`、AndroidTest 编译、Debug/AndroidTest APK 均通过；仅 Redmi 定向执行本地判定传递、关联重试、未验证阻断和 Compose 卡片为 `OK (3 tests)`、耗时 `3.343s`，更新后的文档 corpus 首次/最终均为 `OK (1 test)`、耗时 `2.662s / 2.534s`。测试包已卸载，当前 Debug 主应用冷启动 `3.540s`，`0.1.13 (14)`、前台 Activity 与进程正常，清空后的 crash buffer 为空。本阶段没有运行完整 JVM、Lint、Release 或默认完整 instrumentation，也没有向在线模拟器发送定向命令。设备动作、后台设备工具、截图、坐标、视觉定位、任意 App、精确定时与 Foreground Service 继续关闭。第 111 阶段已在此契约上完成真实前台双 Run 验收，并将 Workflow 输出本身收窄为白名单判定。

## 第 109 阶段：Workflow 设备观察证据 UI（完成）

前台 Workflow 现在能消费已持久化 `device.snapshot` 结果并形成答案级可复核证据。读取链复用 Workflow step 的 `agentRunId` 与 Agent Tool Ledger，不改 Room Schema、不复制原始 JSON；runId 以 `900` 个为一批读取，避免长期历史触发 SQLite bind 上限。读取后立即投影成安全 DTO，Compose 根状态只保留包名、节点/脱敏节点数、截断状态、采集时间、耗时和已验证标签。只有工具名匹配、Run 身份一致、`success=true`、`verificationStatus=PASSED` 且当前 codec 顶层与逐节点 JSON 都合法的结果可进入 UI。

Workflow 详情页在步骤输出下展示紧凑证据卡，并明确持久化节点引用已过期、不可用于动作。窗口标题、节点正文、hint、ref、bounds、actions、snapshot ID 和原始 JSON 不进入证据 DTO；旧步骤输出、前序输入和 Run 汇总一旦同时出现 `nodes` 与三类 snapshot 特征就整段替换，并兼容缺少 `snapshot_id`、camelCase 和转义 JSON。真实 Redmi 首次复核由此发现 Stage 108 历史输出仍渲染完整 JSON，修复后 `stage108_snapshot` 显示 `com.longdev.xiaoling / 38 节点 / 脱敏 2 / 未截断 / 193ms`，页面层级对原始快照字段为 0 命中。

审查收尾后投影 JVM `7/7`、快照策略 JVM `9/9`、`compileDebugKotlin`、Debug/AndroidTest APK、仅 Redmi Workflow Compose `OK (2 tests)` 和同步后的文档 corpus `OK (1 test)` 通过；最终主应用为 `0.1.13 (14)`、前台进程存活、测试包不存在且 crash buffer 为空。本阶段不开放动作、截图、坐标、视觉定位、任意 App 或后台设备工具，也不提前引入精确定时与 Foreground Service。第 110 阶段已在该证据之上补齐本地可复核判定与下一步 fail-closed 传递，仍未扩大动作权限。

## 第 108 阶段：前台 Workflow 只读设备观察（完成）

主线已从等待 Shadow 样本切回个人 Agent 能力。前台手动 Workflow 在设备 Agent 开关、Accessibility 与 Profile/Skill 白名单都有效时，只能看到并执行 `device.snapshot`；`open_app / back / home / tap_ref / type_text / swipe` 继续限定前台直接 `/agent`，后台或定时 Workflow 拒绝全部设备工具。Registry 的工具清单和 Executor 分别门禁，审批恢复从 Room 关联还原原 Run 的 `WORKFLOW / DIRECT` 来源，避免进程重建扩大权限。

双轴审查补齐 Accessibility 未授权/服务断连时的清单级 fail-closed；规划和执行现在共享 `DeviceController.health() == READY`。聚焦 Registry/Runtime/设备健康 JVM `88/88`、`compileDebugKotlin`、Debug/AndroidTest APK 构建均通过；Redmi Room 关联单项为 `OK (1 test)`、耗时 `0.476s`，更新后的文档 corpus 单项同样为 `OK (1 test)`。真实前台 Workflow `stage108_snapshot` 在 `18.868s` 内完成，唯一工具调用 `device.snapshot` 为 `SAFE / success=1 / PASSED / 193ms / 6128B`，快照 `15` 个节点、脱敏节点 `2`、ref `30000ms`，动作调用与审批请求都为 `0`。设备开关、Accessibility 与测试包已恢复；临时 Workflow 因没有删除入口而禁用保留。

该切片不开放设备动作进入 Workflow，不开放任何后台设备工具，也不增加截图、坐标、视觉定位或任意 App 能力。验收后误覆盖固定 Room v32 Release 暴露数据库降级错误，已无损恢复当前 Room v33 Debug；该开发设备问题不通过新增降级迁移掩盖。Shadow 改为真正分隔窗口下的低频并行观察，不再阻塞个人 Agent 主线。

## 第 10 项知识质量工程：Shadow 匿名跨进程持久化（首个切片完成）

answerability Shadow 已从只保留当前进程 tracker 扩展为 Room v33 匿名观测账本。只有显式开启、冻结身份匹配、答案已保存的前台直接 `/agent` 候选以 `OPTIONAL` 模式写入；SHA-256 幂等键去重，最多保留 2,000 条。持久字段限于候选摘要、Keystore HMAC Judge 匿名桶、状态枚举、attempt、延迟/TTFB、Prompt 字节、Tokens、usage、失败计数和时间，不保存消息/Run ID、问题、答案、引用、原始 Judge/Provider/模型、原始响应、URL 或凭据。

设置页新增独立跨进程匿名摘要；现有进程内摘要继续记录 notice 生命周期与旁路失败，notice 不从历史消息恢复。v32→v33 迁移只创建空表，第 97–101 阶段人工合计不回填，因此历史“不持久化”结论仍按当时窗口成立。production enforcement、检索排序、答案路径、Workflow/后台、ANN 和自动后台索引重建不变。

本切片完成 TDD、Schema、迁移、磁盘重开、幂等、未知数值、稳定失败 fallback、Judge HMAC 匿名分桶、隐私字段和值检查、2,001 条裁剪边界和设置页验收。完整本地为 `141/141` tasks（`2m 38s`）、JVM `734/734`、Lint `0 error / 51 warnings`、三类 APK 与 Release lintVital；Redmi 保持唤醒后的最终 JUnit XML `248` 条（`236 passed / 12 skipped / 0 failed`），runner 最终打印 `260 tests`，耗时 `1m 51s`；更新后的文档 corpus gate 首次与写回设备收尾后的最终复验均为 `OK (1 test)`，固定正式 `v0.1.13` 已恢复。类型级离线评测导出契约与首批 Room v33 间隔真实样本均已完成；后续只在真正分隔窗口低频并行积累，样本足够后再评估 JSON codec 或 UI/SAF 出口。production enforcement 继续关闭，该观察不再占据个人 Agent 主线。

## 第 107 阶段：第三条独立同日 Shadow 记录（完成）

Redmi `wsvwypiz7xwslvl7` 使用正式证书签署的当前 Debug 保留 Room v33、Provider/Profile 和前两条匿名记录。临时导入 `docs/answerability-shadow-binding.md` 为 `xiaoling-stage107-shadow.md`，形成 revision `1`、`8` 个 chunks、`16.3 KB`；Embedding 未建立，检索使用词法兜底。首次较宽请求连续完成 4 次 `knowledge.search`，第五次参数校验触发工具预算上限，Run 收敛为 `BUDGET_EXHAUSTED`。由于没有成功答案，一次性授权没有消费，开关仍为 `true`，匿名账本、attempt 和失败分桶均未变化。

第二次复用已验证查询模式，只执行 1 次 `knowledge.search` 后完成答案、引用保存和真实 Judge，Run 为 `COMPLETED`，一次性授权自动关闭。新增记录为 `COMPLETED / BOUND / ACCEPT`，时间为北京时间 `2026-07-29 12:52:23.355`，距第二条 `4 小时 38 分 33.243 秒`；attempt `1`，耗时/TTFB `7288/7274ms`，Prompt `6664B`，Tokens `1715/314/2029`，usage `1`，失败分桶全为 `0`。累计三条完成/绑定/接受 `3/3/3`、Judge 匿名桶 `1`、耗时/TTFB `24596/24561ms`、Prompt `21510B`、Tokens `5421/1155/6576`，最早到最新为 `5 小时 24 分 46.689 秒`。

本轮只形成第三个独立同日窗口，不声明长期分隔资格。清理后 documents/chunks/messages `0/0/0`、空壳会话 `1`、Agent Run `4`（完成 `3`、预算耗尽 `1`）、Shadow rows `3`、Provider/Profile `1/1`、Shadow `false`，测试包和临时下载文件不存在；旧 Run 保持不变。真机证据同时暴露第 106 阶段夹具把真实毫秒截断后误记跨度的问题，修正后 Room 精确差为 `46 分钟 13.446 秒`、页面显示 `46 分钟 13 秒`，聚焦 JVM `3/3` 与 `assembleDebugAndroidTest` 通过；同步后的 Redmi 项目文档 corpus 首次/最终单项均为 `OK (1 test)`、耗时 `2.687s / 2.606s`。JSON/SAF、显式授权评测集、独立阈值校准和 production enforcement 继续关闭。

## 第 106 阶段：Shadow 时间窗口证据投影（完成）

跨进程摘要已经持有 `oldestRecordedAt / latestRecordedAt`，但设置页此前没有展示，判断“真正分隔窗口”仍需要停进程读取数据库。本阶段新增纯展示投影，按设备本地时区显示最早、最新记录和精确跨度；第 103/104 阶段两条证据投影为北京时间 `2026-07-29 07:27:36 -> 08:13:50`、跨度 `46 分钟 13 秒`。界面明确该信息只供人工核对，不自动判定为分隔窗口。

投影不引入小时/天数阈值，不调用 Judge、不修改 Room、不新增样本，也不改变单次授权、JSON/SAF、显式授权评测集、独立阈值校准或 production enforcement。TDD 与边界复核聚焦 JVM `3/3`，覆盖正常跨度、单端缺失和时间逆序；`assembleDebugAndroidTest` 成功，Stage 105 后 Compose instrumentation 仍查找旧开关语义的问题同步修正并完成编译。本轮按分级验证不安装 APK、不连接 Redmi，也不运行完整 JVM、Lint、instrumentation 或 Release。

## 第 105 阶段：单次显式 Shadow 采样窗口（完成）

答案可回答性 Shadow 开关已从持续授权收紧为一次性显式采样窗口。只有候选存在且最终答案保存成功时，Publisher 才通过 `AnswerabilityShadowObservationWindowGate` 在同一临界区检查开关、关闭并持久化设置；并发答案只有一条能让 `tryConsumeObservationWindow()` 返回成功并进入协调器。候选缺失、答案保存失败或用户提前撤销不会消费窗口。即使 Judge 取消、未知或异常，本次已经开始的观测也不会让开关继续保持开启。

本阶段只修改前台直接 `/agent` 的 Shadow 授权生命周期和设置页说明，不新增采样、不改变 Room v33 账本、Judge 重试、匿名字段、notice、Workflow/后台或 production enforcement。首轮 TDD 确认缺少消费 seam；双轴审查发现检查与关闭分离的并发风险后，第二轮用 20 路并发 Red 固定原子边界。Publisher `10/10`、门禁并发 `1/1`，聚焦 JVM 合计 `11/11`；本轮按分级验证不执行完整 JVM、Lint、APK、Redmi instrumentation 或 Release。当前账本仍为第 103/104 阶段形成的 `2` 条短间隔记录，继续等待真正分隔开的低频使用窗口。

## 第 104 阶段：第二条真实 Shadow 样本与冷启动摘要修复（完成）

完整清理并重启进程后，Redmi 使用词法兜底命中 `anonymous shadow calibration validation` 本地知识，形成第二条 `COMPLETED / BOUND / ACCEPT` 匿名记录。两条累计为观测 `2`、Judge 身份桶 `1`、完成/绑定/接受 `2/2/2`、attempt `2`、耗时/TTFB `17308/17287ms`、Prompt `14846B`、Tokens `3706/841/4547`、usage `2`，全部失败分桶为 `0`。

第二条距首条约 `46` 分钟，只记为独立短间隔复验，不满足长期分隔样本的证据强度。冷启动时跨进程摘要被初始化整表重建覆盖为零的问题已经通过纯状态合并函数和 JVM 回归修复，Redmi 设置页已显示真实累计。清理后知识文档/chunks、消息均为 `0`，两个旧 Run 均保持 `COMPLETED`，Shadow 关闭，测试包与临时文件不存在。聚焦 JVM、Debug/AndroidTest 构建通过；当前文档 corpus 前两轮均为 `OK (1 test)`、耗时 `2.431s / 2.602s`，补充设备收尾并重新打包后的最终复验同样通过。主应用最终冷启动 `3385ms`、前台进程正常且 crash buffer 为空。下一步等待真正分隔开的低频使用窗口，不在当前窗口继续采样；JSON/SAF、显式授权评测集、独立阈值校准和 production enforcement 继续后置。

## 第 102 阶段：answerability 离线评测导出契约（完成）

新增版本化 Kotlin sealed envelope，匿名 Shadow 观测证据与显式授权内容评测案例使用两个不能混装的强类型结构。匿名导出不携带原始 Judge 或数据集身份，只允许 v33 不可逆 fingerprint、枚举、失败分桶和保持未知 `null` 的数值 telemetry，不得用于 calibration/validation；显式内容导出才允许携带授权、数据集/评测身份和可校验正文、引用、label/assessment。未增加 production enforcement，Workflow/后台、检索排序和答案路径保持不变。

## 第 103 阶段：Room v33 首个间隔真实 Shadow 样本（完成）

仅在 Redmi `wsvwypiz7xwslvl7` 前台直接 `/agent` 中短时开启 Shadow，导入当前 README 后以词法兜底检索 `Agent Run retryOfRunId`，形成首条 v33 匿名记录。停进程数据库快照确认只有 `1` 条 `COMPLETED / BOUND / ACCEPT`，Judge 尝试 `1` 次，耗时/TTFB `9663/9655ms`、Prompt `10879B`、Tokens `2801/469/3270`、usage `1`，所有失败计数和未知绑定均为 `0`。

测试会话、知识文档、chunks、下载文件和测试包已清理，Schema 保持 `33`，Shadow 偏好为关闭，production enforcement 偏好不存在，Provider/Profile 仍绑定 `gpt-5.5`。同步后的文档 corpus 单项在 Redmi 为 `OK (1 test)`、耗时 `1.988s`，复验后账本与清理状态不变，最终冷启动 `3441ms` 且 crash buffer 为空。本阶段按分级验证没有运行完整 JVM、Lint、默认完整 instrumentation 或 Release。以上为第 103 阶段当时的首条快照；第 104 阶段已形成第二条短间隔记录，当前继续等待真正分隔样本。样本量不足前不实现 JSON/SAF，不启用生产拒绝。

## 通用执行恢复矩阵：已提交与已验证控制面幂等收尾（完成）

两个既有原地恢复例外已经完成统一持久化复核。恢复 marker 现在以 `resumeKind + recoveryBoundaryKey + fromStatus + toStatus + reason` 绑定唯一边界；同一边界只允许一条完整一致的 marker，合法 marker 后追加第二条、损坏或冲突记录都会 fail-closed。Step 创建与状态变化使用 typed `stepId / sequence / stepType / fromStatus / toStatus`，恢复策略逐项核对事件归属和顺序，不再仅凭 `step.created / step.status` 类型接受尾部。

`COMMITTED_TOOL_VERIFICATION` 的状态 CAS、`run.status` 和 marker 在同一 Room 事务提交；`closeInterruptedRuns()` 也在一个事务内收敛活动 Step、Approval、Recovery 与 Run 终态。恢复入口写入或命中 marker 后重新读取 Room 并重新评估，不能携带旧快照直接进入 Runtime。全部工具结果和 `PASSED` 验证完整时，恢复只补控制面：尚未创建恢复总结时创建一次，`RUNNING` 总结在 typed 总结事件前后都可重入，Step/Event 已完成但 Run 未终态时只补终态；总结 Step/Event 在 Room 内 get-or-create，两个协调器并发恢复只留下唯一记录。`COMPLETED recovery.summarize` 缺少总结事件、边界后出现业务事件、typed Step 身份不一致或 marker 漂移一律拒绝。

本切片仍不调用旧 LLM、Executor 或 Workflow 后续步骤，不把设备工具开放到 Workflow/后台，也不改变 Room v32。恢复聚焦 JVM `123/123`、完整 JVM `717/717`；强制 Gradle `141/141` tasks、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 与 v2 正式单签名通过。仅 Redmi Room 定向为 `OK (36 tests)`、耗时 `8.434s`；解锁并保持唤醒后的默认完整为 `OK (237 tests)`、耗时 `93.062s`。首轮锁屏的 `59` 条前台失败已由失败单项和完整套件复验排除，最终文档语料为 `OK (1 test)`，正式 `v0.1.13` 已恢复。下一格只处理仍能由持久化事实严格证明的验证不完整边界，提交未知和旧执行栈继续 fail-closed。

## 通用执行恢复矩阵：尚未提交受控关联重试（完成）

`NOT_COMMITTED_REPLAY_ELIGIBLE` 现在具备用户控制的生产入口。任务中心请求和确认不再依赖内存历史，而是分别读取 Room 最新 Detail，核对 `run.recovered -> run.status=CANCELLED` 收敛链、来源 Profile、当前 Registry、资格码和证据指纹；确认只授权创建关联新 Run，不替代新 Run 内的工具审批。UseCase 在写入新 Run 前第三次读取 Room 并比较完整资格，Runtime 在执行前再次匹配冻结恢复契约，关闭确认与执行之间的定义/账本漂移窗口。

新 Run 使用来源会话、来源 Profile 与 `retryOfRunId`，冻结来源工具名称、风险、参数和定义指纹，同时生成全新 ToolCall ID 并写入 `run.controlled_replay.linked`。它不调用模型重新规划，重新创建独立审批；批准后只执行该调用一次并直接总结。旧 Run、旧 Tool Ledger、旧审批、旧模型协程和旧 Executor 保持原终态，Workflow 与后台入口不开放受控重放。Room 仍为 v32。

强制本地 `141/141` tasks（`2m 39s`）、JVM `707/707`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 与 v2 单签名通过。仅 Redmi 的真实磁盘纵向单项为 `OK (1 test)`（`1.573s`），证明新 Run、新 ToolCall、新审批、单次结果和来源审计持久化且旧 Run 完全不变；专用 Compose 对话框为 `OK (1 test)`（`2.306s`），默认完整 instrumentation 为 `OK (235 tests)`（`92.954s`）。当前路线图第一次重新打包后的项目语料为 `OK (1 test)`（`2.405s`），写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`（`2.546s`）；正式 Release 已恢复并通过版本、前台 Activity、PID、测试包卸载、保持唤醒关闭和 crash buffer 收尾。未向模拟器发送安装或测试命令。该切片完成后回到恢复矩阵，统一复核“已提交只读验证”和“已验证只补控制面”，仍不开放旧 Run 原地续跑。

## 通用执行恢复矩阵：尚未提交安全重放资格（完成）

“尚未提交”现在只是一项持久化资格，不是执行动作。Runtime 在 ToolCall proposed/validated 时写入版本化恢复契约；契约指纹覆盖 Schema/契约版本、工具名称与说明、风险、审批/验证/重放策略、超时、后台能力、Android 权限、参数 Schema 和业务校验器数量。业务校验器代码本身不可序列化，其语义变化必须显式递增 `recoveryContractVersion`。审批 requested/decided 事件沿用请求时冻结的定义指纹，未知未来策略或缺少契约的历史事件按无资格处理。

资格只允许默认拒绝之外的显式 opt-in：工具必须同时为 `IDEMPOTENT_BY_KEY`、`CONTROLLED_SAME_CALL` 和 `REQUIRE_CONFIRMATION`；当前只有 `notes.create`、`memory.remember`。此外还必须证明原 Profile 允许该工具、Tool Ledger 完整、链尾已 validated 且无 ToolResult/`TOOL_EXECUTE`、前序调用全部成功验证、唯一审批已经批准、requested 状态原本为 `PENDING`、requested/decided 参数与定义指纹一致、事件顺序为 validated→requested→decided，且审批 Step 完成后没有新步骤。任何定义、参数、指纹、顺序或步骤漂移都 fail-closed。

通过资格时 `AgentRunResumePolicy` 只写入 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE`；启动收敛仍把旧 Run 和活动 Step 置为 `CANCELLED`。本资格切片不调用工具、不恢复旧模型协程或 Executor、不继续旧 Workflow，也不原地继续旧 Run。强制本地 `141/141` tasks、JVM `694/694`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital、zipalign 与 v2 单签名通过；仅 Redmi 的磁盘 Room 单项 `OK (2 tests)`（`0.783s`），默认完整 `OK (233 tests)`（`90.924s`），同步后的最终文档语料为 `OK (1 test)`。后继受控关联新 Run 入口已由上一节完成，但仍不得从资格直接跳到旧 Run 原地续跑。

## 通用执行恢复矩阵：提交状态未知（完成）

恢复矩阵的“提交状态未知”已形成首个持久化垂直切片。只有 validated ToolCall、对应 `TOOL_EXECUTE` 步骤和缺失 ToolResult 同时成立，旧 Run 才冻结 `RESTART_REQUIRED / COMMIT_UNKNOWN`；proposed-only、validated-only 但执行步骤尚未落库和不一致步骤链继续按恢复证据无效处理。启动收敛同时冻结重试证据：真正越过执行边界的调用要求用户确认后创建关联新 Run，尚未进入执行步骤的调用保持 `NOT_COMMITTED`。legacy typed event 也必须有唯一链尾调用和执行步骤双重证据。

该切片不重放工具、不恢复旧模型协程或 Workflow 后续步骤，也不伪造 ToolResult；旧 Run 与活动 Step 保持可审计终态。强制本地 `141/141` tasks、JVM `683/683`、Lint `0 error / 51 warnings`、三类 APK、Release lintVital、zipalign 和 v2 正式单签名通过；仅 Redmi 两个 Room 单项分别为 `OK (1 test)`，默认完整为 `OK (231 tests)`、耗时 `90.302s`，最终文档语料为 `OK (1 test)`。相邻的“尚未提交安全重放资格”已由上一节完成，但不能把 `NOT_COMMITTED` 或该资格直接等同于允许恢复旧执行栈。

## 横向结构工程：功能对话框归属收口（完成）

Agent Run 重试证据确认、Workflow Run 重试步骤复用确认、长期记忆编辑/删除和本地 Skill 删除已分别迁入 `ui/agenttask`、`ui/workflow`、`ui/memory`、`ui/agentskill`。确认草稿、待删除对象和 busy 状态进入各模块 `UiState`/Projection；Agent、Workflow 与 Memory 通过功能 Actions 路由，Skill 删除使用窄确认/取消回调。四个 dialog host 仍由 `XiaoLingContent` 全局挂载，切换 pane 不会丢失待处理对话框。

`SettingsPage` 继续承担 pane、Android launcher、跨页导航和模块适配；备份恢复继续留在根层处理 `Uri`、Room 替换、Keystore 与重启提示，全局通知也不迁移。`XiaoLingApp.kt` 从 `1,103` 行降到 `817` 行。JVM `678/678`、Lint、Debug/AndroidTest/R8 Release APK 和 Release lintVital 已通过；仅 Redmi `wsvwypiz7xwslvl7` 的新增 7 条 Compose 为 `OK (7 tests)`、测试耗时 `9.247s`，默认完整 instrumentation 为 `OK (229 tests)`、测试耗时 `89.151s`，最终文档重新打包后的项目语料单项为 `OK (1 test)`。未使用在线模拟器。达到本轮停止条件后不再继续结构拆分，下一主线正式切到通用执行恢复。

## 横向结构工程：Workflow 管理垂直 UI module（完成）

Workflow 管理页已从 `XiaoLingApp.kt` 迁入 `ui/workflow`。模块使用专用 projection 按 Workflow 聚合定义、Run、调度实例与周期规则，统一计算运行/变更状态和操作资格，并在 Compose 前容错解码步骤快照；页面只依赖 `WorkflowManagementUiState` 与 10 个动作组成的 `WorkflowManagementActions`，新建、编辑、调度和展开状态由模块自己拥有。通知权限、设置导航和全局 Workflow 重试确认仍留在应用宿主。

`XiaoLingApp.kt` 从导航阶段的 `6,925` 行降到 `6,217` 行。聚焦 projection JVM `2/2`、Redmi fake actions Compose `OK (1 test)`；强制本地 `140/140` tasks、JVM `664/664`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 通过，仅 Redmi 默认完整 instrumentation 为 `OK (198 tests)`、耗时 `51.74s`，最终文档语料单项为 `OK (1 test)`。Room v32、Workflow 执行/调度/恢复语义、设备后台门禁和第 101/102 项均不变。

## 横向结构工程：Agent 任务中心垂直 UI module（完成）

Agent 任务中心已从 `XiaoLingApp.kt` 迁入 `ui/agenttask`。`AgentTaskCenterProjection` 只按稳定 Run ID 投影选中与重试状态；`AgentTaskCenterPage` 只依赖窄 UI state、三项 `AgentTaskCenterActions` 和返回回调，自己持有筛选、首刷、历史指标、Run 卡片、选中详情、Ledger 一致性、恢复处置、步骤、审批和事件呈现。设置导航、全局重试确认以及成功后回来源会话仍留在应用宿主。

对话 Run 时间线与任务中心共用 `AgentRunUiPrimitives.kt`，统一状态徽标、Step 行和中文状态文案。review 已移除设置入口的提前刷新，空列表首刷由任务中心页面自己持有；`XiaoLingApp.kt` 从 Workflow 阶段的 `6,217` 行降到 `5,176` 行。两轮 TDD 的 projection/page Red 均已转绿；Projection JVM `1/1`、仅 Redmi 动作路由/筛选/恢复处置 Compose `3/3`、强制本地 `140/140` tasks、JVM `665/665`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 通过，仅 Redmi 默认完整 instrumentation 为 `OK (199 tests)`、耗时 `52.659s`，最终文档语料单项为 `OK (1 test)`。Room v32、重试证据、旧 Run 保持不变、设备后台门禁和第 101/102 项均不变。

## 横向结构工程：长期记忆管理垂直 UI module（完成）

长期记忆管理页已从 `XiaoLingApp.kt` 迁入 `ui/memory`。`MemoryManagementProjection` 统一投影正式记忆、仍可决定的 `PENDING / CONFLICT` 候选、搜索/筛选、删除撤销和按稳定 ID 绑定的 selected/mutating；`MemoryManagementPage` 只依赖窄 UI state、15 项 `MemoryManagementActions` 和返回回调，自己呈现空列表首刷、候选开关、搜索/筛选、来源与召回审计、生命周期操作和撤销入口。

该阶段编辑/删除确认弹窗及来源会话/Run 导航 effect 留在应用宿主；后继收口已将弹窗迁入 `ui/memory`，来源导航仍由宿主处理；真实 Room、候选协调器和跨进程撤销由 ViewModel 动作实现复用。`XiaoLingApp.kt` 从任务中心阶段的 `5,176` 行降到 `4,644` 行。双轴 review 已把候选标签、主按钮文案和冲突标记进一步收口到 projection，页面不再处理已过滤的不可达状态。Projection JVM `1/1`、仅 Redmi 动作路由与跨重组首刷 Compose `OK (2 tests)`、强制本地 `140/140` tasks、JVM `666/666`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 通过，仅 Redmi 默认完整 instrumentation 为 `OK (201 tests)`、耗时 `54.857s`，最终文档语料单项为 `OK (1 test)`。Room v32、候选治理、FTS、生命周期、来源审计、跨进程删除撤销、设备后台门禁和第 101/102 项均不变。

## 横向结构工程：Provider 管理垂直 UI module（完成）

Provider 管理页已从 `XiaoLingApp.kt` 迁入 `ui/provider`。`ProviderManagementProjection` 按稳定 Provider ID 绑定选中、单项/批量同步和逐项结果，并只保留应在编辑器内展示的网络结果；`ProviderManagementPage` 只依赖窄 UI state、14 项 `ProviderManagementActions` 和返回回调，自己呈现列表、编辑器、扫码/剪切板/Base64 辅助、字段、模型获取/勾选和保存入口。应用宿主继续统一管理编辑器返回优先级、底栏显隐和聊天 Provider 下拉，真实保存、删除、同步与 Agent Profile 修复仍由原 ViewModel 动作复用。

`XiaoLingApp.kt` 从长期记忆阶段的 `4,644` 行降到 `4,003` 行。双轴 review 的 Standards 轴无 finding，Spec 轴发现最终文档与真实宿主组合覆盖待补，现已增加 MainActivity 返回/底栏回归并同步四份长期文档。Projection JVM `2/2`、仅 Redmi Provider 页面 Compose `OK (2 tests)`、宿主导航 Compose `OK (2 tests)`、强制本地 `140/140` tasks、JVM `668/668`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 通过，仅 Redmi 默认完整 instrumentation 为 `OK (204 tests)`、耗时 `59.619s`，最终文档语料单项为 `OK (1 test)`。Room v32、Provider 持久化/同步/删除语义、设备后台门禁和第 101/102 项均不变。

## 横向结构工程：Agent Profile 管理垂直 UI module（完成）

Agent Profile 管理页已从 `XiaoLingApp.kt` 迁入 `ui/agentprofile`。`AgentProfileManagementProjection` 按稳定 Profile ID 绑定选中、变更、删除资格和 Provider/模型有效性；`AgentProfileManagementPage` 只依赖窄 UI state、选择/保存/删除三项 `AgentProfileManagementActions` 和返回回调，自己呈现增删改选、Provider/模型、Chat/Responses、长期记忆、工具与 Skill 双向依赖和字段门禁。应用宿主继续负责设置返回、底栏显隐、聊天页 Profile 下拉和全局结果提示；ViewModel 保存入口继续做 Provider、模型、工具和 Skill 依赖的防御性校验，旧 Run 与 Profile 快照不变。

`XiaoLingApp.kt` 从 Provider 阶段的 `4,003` 行降到 `3,631` 行。双轴 review 已同步四份长期文档、补齐业务不变量注释、将过宽的 `configurationValid` 收窄为 `providerModelValid`，并增加列表重排及 Profile 对象替换后的稳定 ID 保存回归。Projection JVM `2/2`、仅 Redmi 页面 Compose `OK (3 tests)`、宿主返回/底栏 `OK (1 test)`、强制本地 `140/140` tasks、JVM `670/670`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 均通过。Redmi 默认完整运行的 JUnit XML 为 `208` 条（`196 passed / 12 skipped / 0 failed`），耗时 `69.14s`；Gradle 控制台原文为 `Finished 220 tests`、`BUILD SUCCESSFUL in 1m 22s`，两种总数差异来自 skipped 统计口径。最终文档语料单项为 `OK (1 test)`。Room v32、Agent Runtime、Workflow、设备工具后台门禁和第 101/102 项均不变；后继 Agent Skill 管理见下一节。

## 横向结构工程：Agent Skill 管理垂直 UI module（完成）

Agent Skill 管理页已从 `XiaoLingApp.kt` 迁入 `ui/agentskill`。`AgentSkillManagementProjection` 按稳定 Skill ID 绑定启停/删除资格，以 Tool Registry 标记依赖的已注册/缺失状态，并从最近 Run 的 `skill.selected` 事件投影最多三条版本与终态审计；损坏旧事件保守忽略。`AgentSkillManagementPage` 只依赖窄 UI state、刷新 Skill/审计、请求导入、启停和请求删除五项 Actions 及返回回调，自己持有首刷和稳定 ID 展开状态。该阶段 Android 文件选择器、全局删除确认与真实持久化副作用留在应用宿主；后继收口已将删除确认迁入 `ui/agentskill` 并继续由宿主全局挂载。

`XiaoLingApp.kt` 从 Agent Profile 阶段的 `3,631` 行降到 `3,497` 行。双轴 review 已删除未消费的 mutating 原始字段，补齐依赖/Run 审计 projection，并把导入意图收口进 Actions、移除 ViewModel 审计刷新透传。Projection JVM `3/3`、仅 Redmi 页面 Compose `OK (2 tests)`、宿主返回/底栏 `OK (1 test)`、强制本地 `140/140` tasks、JVM `673/673`、Lint `0 error / 50 warnings / 0 information`、三类 APK 和 Release lintVital 均通过；Redmi 默认完整 `OK (211 tests)`、耗时 `70.952s`，最终文档语料单项为 `OK (1 test)`。Room v32、Skill 导入/Runtime/旧 Run、设备工具后台门禁和第 101/102 项均不变；下一轮从宿主剩余 `3,497` 行重新盘点完整垂直簇。

## 横向结构工程：会话主界面垂直 UI module（完成）

会话主界面已从 `XiaoLingApp.kt` 迁入 `ui/conversation`。`ConversationProjection` 把 Header、Provider、消息/知识引用和输入区投影为四组窄 UI state，并统一派生普通聊天与 `/agent` 的发送、附件、记忆、等待和答案引用状态；`ConversationPage` 只依赖该 state、单一 Actions 和页面可见状态，自己持有滚动跟尾并组合消息、SharedDraft、附件、Agent Run/审批与输入区。Android 文件选择器、URI 读取和知识文档跨页导航继续留在应用壳。

`XiaoLingApp.kt` 从经纠正的 Agent Skill 基线 `3,497` 行降到 `1,796` 行；Contract/Page/消息渲染分别为 `224 / 1,235 / 643` 行。双轴 review 未发现明确行为回归，已补普通聊天禁发、忙态、知识引用去重和图片/文档、发送/停止、SharedDraft 动作路由。Projection JVM `4/4`、仅 Redmi 页面 Compose `OK (3 tests)`、强制本地 `140/140` tasks、完整 JVM `677/677`、Lint `0 error / 50 warnings`、三类 APK 和 Release lintVital 均通过；仅 Redmi 默认完整为 `OK (214 tests)`、耗时 `74.329s`。Debug/Release APK 分别为 `23,337,963 / 16,016,342` 字节，SHA-256 分别为 `61b5cb5b14b43c8e01fe07a9ea4067e918d8c6f8e3d98baab25bc1cee2bce1f6 / f537287d9a6ec10f2e3d7e8675fef6bf9690dda00b8994961336b7afd8c6b9d9`，最终文档语料单项为 `OK (1 test)`。Room v32、普通聊天与 `/agent`、滚动、附件、知识引用、审批、旧会话、设备工具后台门禁和第 101/102 项均不变。下一轮从宿主剩余 `1,796` 行重新盘点完整垂直簇。

## 横向结构工程：提示词设置垂直 UI module（完成）

提示词设置已从 `XiaoLingApp.kt` 迁入 `ui/promptsettings`。页面只接收六字段 `PromptSettings`、九项 `PromptSettingsActions` 和返回回调，自己持有普通对话、会话摘要与 Agent 总结三类最终预览的互斥展开状态；最终文本继续由 `PromptPolicy` 生成。`XiaoLingViewModel` 只实现原有输入即保存、开关和逐项恢复动作，设置导航仍属于应用壳。

`XiaoLingApp.kt` 从 `1,796` 行降到 `1,582` 行；Contract/Page/共享 `CompactSection` 分别为 `21 / 222 / 64` 行。TDD 编译 Red 后，Redmi 页面 Compose 为 `OK (2 tests)`；固定点审查完成长期文档同步，并保留三类显式映射以防动作交叉。强制本地 `140/140` tasks、JVM `677/677`、Lint `0 error / 50 warnings`、三类 APK 和 Release lintVital 均通过。Debug/Release APK 为 `23,354,347 / 16,016,342` 字节，SHA-256 为 `194f25d3173f50d20fe8cbc3c11be1a73cdbd7738638218d3b3fc1758b9704cc / 78470c153f4a2477dec0dfb9c8377b9c55abd233435554f6b3ca54260ace4d66`。仅 Redmi 默认完整 XML 为 `216` 条（`204 passed / 12 skipped / 0 failed`）、耗时 `79.503s`，最终文档语料单项为 `OK (1 test)`。Room v32、三类提示词持久化与策略、Agent/Workflow、设备后台门禁和第 101/102 项均不变；下一轮从宿主剩余 `1,582` 行重新盘点完整垂直簇。

## 横向结构工程：进程退出观察垂直 UI module（完成）

进程退出观察页已从 `XiaoLingApp.kt` 迁入 `ui/processexit`。页面只接收独立账本、loading/error 组成的 `ProcessExitObservationUiState`、单项刷新 Actions 和返回回调，自己呈现六类证据标签、稳定数值、Room 同源 key、加载/失败/空态和固定证据边界。应用壳继续保持进入前只读刷新，ViewModel 继续持有 `latest()` IO Job；前台/Worker 平台采集、Room 和 system 分类边界不变。

`XiaoLingApp.kt` 从 `1,582` 行降到 `1,404` 行，Contract/Page 为 `13 / 217` 行。TDD 编译 Red 和最小 wrapper Green 后完成迁移；双轴 review 的 Spec 轴无 finding，Standards 轴的长期文档缺口已修正。Redmi 页面 Compose 为 `OK (4 tests)`；强制本地 `140/140` tasks、JVM `677/677`、Lint `0 error / 50 warnings`、三类 APK 和 Release lintVital 均通过。Debug/Release APK 为 `23,354,347 / 16,016,342` 字节，SHA-256 为 `260620b0a6a3ebc0780f7f2c3eeecc3533297ff96ac5515caf14dea11466c265 / 2f919076cd17d58f05522a3a5162b5e80d8ae9086aec6a07ff4115db6328999f`。仅 Redmi 默认完整 XML 为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `80.011s`，最终文档语料单项为 `OK (1 test)`。Room v32、自然 LMK 证据、Agent/Workflow、设备后台门禁、Foreground Service 和第 101/102 项均不变；后继网络请求设置迁移见下一节。

## 横向结构工程：网络请求设置垂直 UI module（完成）

网络请求设置页已从 `XiaoLingApp.kt` 迁入 `ui/networksettings`。页面只接收单字段 `NetworkRequestSettingsUiState`、更新/恢复默认两项 `NetworkRequestSettingsActions` 和返回回调，自己拥有五行 User-Agent 编辑区、复制、清空、恢复默认及剪贴板适配；`XiaoLingViewModel` 继续负责去除换行、512 字符上限、即时 UI 更新与偏好保存。

`XiaoLingApp.kt` 从 `1,404` 行降到 `1,317` 行，Contract/Page 为 `11 / 116` 行。TDD 编译 Red、Redmi 页面 Compose `OK (1 test)` 和双轴审查均通过；强制本地 `140/140` tasks、JVM `677/677`、Lint `0 error / 50 warnings`、三类 APK、Release lintVital、zipalign 与 v2 单签名均通过。Debug/Release APK 为 `23,370,731 / 16,016,342` 字节，SHA-256 为 `8e1d71862a6c6ec428834936bf607bdb15237fc9bfb5e4845e7473c7975034e9 / 0101fed9730bc2787f94471e553d7d75747b5aae3aaa5e5b7c5a1523efd51ccc`。仅 Redmi 默认完整为 `217` 条（`205 passed / 12 skipped / 0 failed`）、耗时 `78.642s`，最终文档语料单项为 `OK (1 test)`。清空后当前页面为空、重启后恢复默认的既有时序及所有请求共用 Header 的语义不变；后继设置根页窄投影、Actions 和页面迁出已由下一节完成，`SettingsPage` composition root 继续保留。

## 横向结构工程：设置根页垂直 UI module（完成）

设置根页已从 `XiaoLingApp.kt` 迁入 `ui/settingsroot`。`SettingsRootProjection` 把全局状态压缩为主题、当前 Agent Profile、Provider/模型、Shadow、Skill、Workflow、Agent Run、进程退出观察和备份摘要；页面只接收 `SettingsRootUiState / SettingsRootActions`，拥有原 14 项顺序、动态文案、主题选择及备份按钮。宿主继续把导航、ViewModel 主题动作和 Android 备份 launcher 映射为 Actions，`SettingsPage` 仍是 composition root。

`XiaoLingApp.kt` 从 `1,317` 行降到 `1,097` 行，Contract/Page 为 `88 / 264` 行。Projection JVM `1/1`、Redmi 页面 Compose `OK (4 tests)`、双轴审查和强制本地 `140/140` tasks 均通过；JVM `678/678`、Lint `0 error / 50 warnings`、三类 APK、Release lintVital、zipalign 与 v2 单签名均通过。Debug/Release APK 为 `23,387,174 / 16,032,726` 字节，SHA-256 为 `309faa26a77d42fccca4108e9849a474ca9ec53ba38e190570facfd82659f757 / cee1e20edd6ce0ae536e9331fa18729e1e793ac946ae6dde08da62734c7962cd`。仅 Redmi 默认完整 XML 为 `221` 条（`209 passed / 12 skipped / 0 failed`）、耗时 `85.834s`，控制台为 `Finished 233 tests`。备份 busy 语义、Room v32、全部设置子页、Agent/Workflow、设备后台门禁和第 101/102 项不变；后继有界对话框簇已完成且没有机械迁移 `SettingsPage`。

## 横向体验收尾：单一启动画面、启动性能与固定设置标题（完成）

Android 12+ 系统 Splash 继续保留品牌 Logo；应用内 Compose 品牌页及 `880ms + 260ms` 人工过渡已删除。重型 Room、Agent、Workflow、网络和备份对象改为惰性构造，可见首帧后才启动恢复链；WorkManager 移除进程启动时自动初始化，保留官方按需配置入口。Release 启用 R8 `9.1.29` 与 Baseline/Startup Profile，Profile 只在 Redmi `wsvwypiz7xwslvl7` 生成；源文本各 `18,011` 行，APK 内编译后 `baseline.prof` 为 `13,847` 字节。设置根页把标题和主题入口保留在滚动区域之外，仅让 14 项卡片滚动。

Redmi 当次原 Debug 冷启动约 `3.4–3.7s`，最终 R8 Release 覆盖安装后为 `533ms`，`speed-profile` 三次为 `580 / 504 / 587ms`；Release 主界面、设置滚到底、PID 与 crash buffer 已验证。最终本地门禁为 JVM `678/678`、Lint `0 error / 51 warnings` 和三类 APK/Release lintVital；Redmi 完整为 `OK (222 tests)`、耗时 `83.58s`。PNG 分享测试现在直接捕获并断言“图片不可用”瞬时结果，静默丢弃附件不再能通过回归；生产分享解析、附件校验与不自动发送行为未改。最终文档语料为 `OK (1 test)`。Debug/Release APK 为 `23,354,457 / 3,170,866` 字节，SHA-256 为 `7394d986be7a12d0b2b0b853d54f7af4ac438017a7f2ec28f843e816ce556c84 / 6c28ac665471e4cddda4d58f0c36a79458cadb929bc3fe11c289113cf9ba004e`。Room v32、个人 Agent 路线、第 101/102 项和下一轮宿主对话框盘点顺序不变。

## 横向工程：Agent 启动前校验协调迁出（完成）

普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批的会话、Profile、工具注册与 Provider 校验已从 `XiaoLingViewModel` 迁入独立 `AgentLaunchPreflightCoordinator`。普通 `/agent` 保持可创建新会话；其余入口先要求指定会话存在。普通、Workflow 与两类重试使用当前 Profile，恢复审批优先使用原 Run Profile 快照，旧 Run 无有效快照时才回退当前 Profile。校验成功后的 UI、附件、Room 和 Runtime 副作用仍留在 ViewModel，长 Workflow 继续使用入口冻结配置。

运行配置中的解密 API Key 只允许进程内传递，`ProviderRequestConfig.toString()` 已对 Base URL、API Key 和自定义 Header 做类型级脱敏。聚焦 JVM 为 Coordinator `10/10`、脱敏 `1/1`；强制完整门禁为 `140/140` tasks、JVM `656/656`、Lint `0 error / 50 warnings / 0 information` 和三类 APK，仅 Redmi 默认完整 `196` 条（`184 passed / 12 skipped / 0 failed`）、耗时 `48.8s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：Provider 模型同步协调迁出（完成）

Provider `/models` 请求、模型去重/回退、失败分型、批量顺序和完整快照提交已从 `XiaoLingViewModel` 迁入独立 `ProviderModelSyncCoordinator`。批量同步按输入顺序执行，普通失败继续下一项，取消立即终止；并行单项只在提交阶段通过 Mutex 串行。提交端以最新 Provider 快照和规范化身份拒绝删除、配置漂移或保存期间变化，保留最新名称与仍有效模型，Room 保存成功后才发布成功并修复空模型 Agent Profile。

聚焦 JVM `8/8`，完整门禁为 JVM `645/645`、Lint `0 error / 50 warnings` 和三类 APK；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.373s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：候选记忆协调迁出（完成）

候选记忆的有界列表、普通聊天/Agent Run 成功回合来源身份、采集和接受/拒绝已从 `XiaoLingViewModel` 迁入独立 `AgentMemoryCandidateCoordinator`。协调器以 typed outcome 区分无候选、缺失、锁忙和存储失败；同一候选 ID 的接受/拒绝不能并发，不同候选可并行，失败和取消都会释放 claim。关闭候选开关同时取消旧列表 Job，防止迟到 Room 结果重新填充界面。敏感过滤、去重、冲突和 transaction 继续由既有 Room Store/Manager 管理，成功回合入口与默认关闭语义不变。

聚焦 JVM `7/7`，强制完整门禁为 `140/140` tasks、JVM `637/637`、Lint `0 error / 50 warnings / 1 hint` 和三类 APK；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.633s`，最终文档语料单项为 `OK (1 test)`。本轮不采集 Shadow，不改变 Room v32、第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：恢复后 Agent 审批协调迁出（完成）

进程重建后重新展示的链尾审批已从 `XiaoLingViewModel` 迁入独立 `RecoveredAgentApprovalCoordinator`。批准和拒绝都会重新读取最新 Room detail，并复用 `AgentRunResumePolicy` 核验唯一 `PENDING` Approval、链尾 ToolCall、参数和已验证前缀；进程内一次性互斥阻止批准/拒绝交叉或重复进入，锁忙时以 `Busy` 保留另一会话的可重试卡片。批准前先恢复原 USER 附件，前置失败或决定落库前取消时恢复可重试卡片；拒绝由 Repository 单事务收敛 Approval、审批 Step 和原 Run，避免 `WAITING_APPROVAL + DENIED` 半状态。

协调器不拥有 Provider/Profile 选择、Compose、消息、Workflow 后续步骤或普通当前进程 waiter；`AgentApprovalDecisionCoordinator` 继续只管理当前进程 ticket/claim，Room v32 继续是共同事实源。聚焦 JVM `6/6` 与新增 Room 原子拒绝 instrumentation 契约已落地；强制本地 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`、Debug/Release/AndroidTest APK 通过，仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。本轮不采集 Shadow 样本，不改变第 101/102 项，也不扩展设备 Workflow/后台、精确定时、Foreground Service 或远期能力。

## 横向工程：Agent 审批决策协调迁出（完成）

`XiaoLingViewModel` 原先直接持有的全局 `pendingApprovalDecision` 已迁入纯内存 `AgentApprovalDecisionCoordinator`。每次审批使用独立 ticket，匹配当前 `requestId` 的首次按钮操作才能领取 claim；重复点击、过期 UI 和旧 claim 不再并发写 Room。Room 成功后才完成 waiter，异常会释放 claim 并恢复可重试审批卡片，Repository 返回 `null` 时取消 waiter；停止生成同时取消当前 ticket，使迟到持久化结果无法继续放行工具。旧 ticket 的完成、释放和清理均通过身份比较隔离，不会误伤后来注册的新审批。

协调器不写 Room、不判断工具风险、不维护 Run/Workflow、不投影 Compose，也不处理进程恢复后的审批；这些职责继续留在 Repository、Runtime 和 ViewModel 原边界。五轮 TDD 聚焦 `5/5`，完整 JVM `624/624`、Lint `0 error / 50 warnings / 1 hint`，Debug/Release/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)`、耗时 `48.776s` 通过；7 份长期文档语料为 `OK (1 test)`。本轮不产生 Shadow 样本，不扩大设备工具、Workflow/后台、精确定时或 Foreground Service 能力，第 101 项继续低频观察，第 102 项保持后置。

## 横向工程：会话级 Agent 运行态 Store 迁出（完成）

`XiaoLingViewModel` 原先维护的 Run/Approval 两张会话 Map 已迁入纯内存 `AgentConversationRuntimeStateStore`。Store 统一同会话替换、审批 `deciding` 更新、只清审批、删除会话整组清理、新建占位不恢复和启动明细重建后的目标会话投影；ViewModel 继续负责 Compose 状态、Room 审批决策、Run history 与真正 Agent 执行。该切片把运行态生命周期从 `4408` 行 ViewModel 中抽离后降至 `4404` 行，没有改变 Room v32、Provider、Runtime、工具审批/验证、Workflow 或设备后台门禁。

五轮 TDD 聚焦 `5/5`，完整 JVM `619/619`、Lint `0 error / 50 warnings`、Debug/Release/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)`、耗时 `50.018s` 通过。真机收尾保持 Provider/Profile 各 `1`、知识文档 `0`、默认 Profile `16` 个工具/`7` 个 Skill/记忆开启、Shadow 关闭。该横向工程不产生新 Shadow 样本，第 101 项继续低频观察，第 102 项仍后置。

更新后的 7 份长期文档已重新打包，并通过 Redmi 项目文档语料门禁 `OK (1 test)`。

## 第 101 项：answerability Shadow 持续观察（首个窗口完成，继续观察）

首个间隔真实使用窗口只在 Redmi 前台直接 `/agent` 中显式开启 Shadow，并只采集 `1` 条样本。当前 README 的 `Agent Run retryOfRunId` 词法查询命中 `3` 个候选，真实 Run 完成 `knowledge.search`，Judge 判定为直接回答；样本/完成/Judge 为 `1/1/1`，取消、异常、未知、跳过和旁路错误均为 `0`，成本为 `5009ms / 5002ms / 10150B / 2720+209=2929 Tokens`。

关闭开关并删除测试会话后，notice 有效 `1 -> 0`、裁剪 `0 -> 1`；临时知识文档与下载文件已删除，知识文档恢复 `0`。第 97 至 101 项已记录窗口人工合计为样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`；八次 Judge 均无自然网络、协议或认证失败。该合计不代表跨进程持久化，且本窗口使用词法兜底，不能作为 Embedding 质量证据。

当前没有明显成本异常或新的自然失败证据，不增加 Room Store、Schema、跨进程 notice 或 enforcement。第 101 项仍保持持续低频观察，不在同一窗口堆样本，也不提前进入第 102 项。同步文档后的强制门禁为 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK、仅 Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (195 tests)`。

## 横向工程：Agent Run 关联重试协调迁出（完成）

失败 Run 的关联重试已从 `XiaoLingViewModel` 迁入独立 `AgentRunRetryCoordinator`。协调器统一资格判断、副作用证据确认、确认时 canonical fingerprint 漂移复核、用户取消、原 USER 附件异步恢复和关联新 Run 请求；只输出 typed event 与包含原会话、目标、附件、`retryOfRunId` 的不可变启动数据，不写旧 Run，也不持有 Agent Runtime。ViewModel 继续负责 Compose 投影、原会话与 Agent Profile/Provider 校验、会话导航和真正调用 `sendAgentRun`。

新增聚焦 JVM `7/7`，完整 JVM `614/614`、Lint `0 error / 50 warnings`、Debug/AndroidTest APK 和仅 Redmi 默认完整 `OK (195 tests)` 已通过。本轮不采集 Shadow 样本，不改变第 101 项“间隔真实使用窗口低频观察”，也不扩展设备工具、Workflow/后台、精确定时、Foreground Service 或远期能力。

## 第 100 阶段：Android 系统分享入口 v1（完成）

本阶段补齐个人 Agent 的移动端输入入口，而不是扩大执行权。Android 分享面板只允许单项纯文本或单张 PNG/JPEG/JPG/WEBP 图片进入新会话草稿；文本最多 20,000 字符，图片必须是单个 `content://` 并继续走现有 8 MB 与内容校验。`EXTRA_STREAM` 和 `ClipData` 重复同一 URI 时保持 Android 分享兼容，不同 URI 则按多图拒绝。分享内容永不自动发送，不触发普通聊天、`/agent`、工具、Workflow 或后台任务。

空闲编辑器可直接打开分享；已有本地草稿、附件或活动操作时显示“打开分享/忽略分享”，只有用户明确打开才替换。已有未决分享时保留第一个、明确忽略第二个并要求重试。冷启动在初始化后投影，热启动走 `onNewIntent`，Activity 重建不重复导入；来源只显示为不可信的外部分享，不使用 referrer 或外部 extra 归因与去重。

聚焦 JVM `7/7`、仅 Redmi `wsvwypiz7xwslvl7` 的定向 instrumentation `OK (4 tests)` 已通过；完整 JVM `607/607`、Lint `0 error`、APK、文档语料 `OK (1 test)` 和默认完整 `195` 条（`183 passed / 12 skipped`）通过。该入口保持为既有编辑器和附件校验的薄适配层，不建立第二套消息、附件、发送或 Agent Runtime。下一阶段仍不扩到 `ACTION_SEND_MULTIPLE`、任意文件、自动发送、分享后后台处理或跨应用自动执行；Shadow 继续低频旁路观察，其余远期能力继续按原顺序后置。

## 第 99 阶段：answerability shadow 首批低频观察（完成，持续旁路）

本阶段没有继续密集扩样本，而是在新的 Redmi 真实使用窗口内显式开启 Shadow，取得 `3` 条有效前台 Judge 样本：直接回答 `2`、部分回答 `1`。Judge 尝试 `3` 次、取消 `0`、异常 `0`；本批成本为耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。

首次宽英文问题连续四次未取得知识候选，Agent Run 因工具调用次数达到上限失败；因为没有成功答案和合格 Shadow 入口，tracker 保持 `0`，没有把它伪造成 Judge 失败。随后三条精确词法查询分别覆盖 Responses 文档限制、Workflow 范围/准点语义与普通聊天工具事实边界。当前窗口 Embedding Provider 不可用，候选通过词法兜底形成；本批只证明 answerability 旁路和词法候选链路，不证明 Embedding 质量。

关闭开关并删除四个测试会话后，notice 从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3`；临时知识文档与下载文件已删除，恢复知识文档 `0`、原会话 `1`。第 97 至 99 阶段记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次、直接回答 `4`、部分回答 `3`，仍没有自然网络、协议或认证 Judge 失败。该跨阶段合计来自书面证据，不是跨进程持久化。

本阶段继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。完整 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)` 通过。后续 Shadow 只在间隔开的真实使用窗口低频观察，不为凑数量连续采样；没有自然失败或明显成本异常前，不设计 Room Store、跨进程 notice 或生产拒绝。

## 第 98 阶段：answerability shadow Redmi 扩样本（完成）

本阶段在不修改生产实现的前提下，继续使用 Redmi 收集用户显式开启、同一进程、前台直接 `/agent` 的真实 Shadow 证据。累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次均无取消或异常；完成样本分布为直接回答 `2`、部分回答 `2`。累计成本为耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`。

新增三条有效 Judge 样本中，两条自然判为部分回答，一条判为直接回答；两条长词法 query 自然无候选并按跳过统计。另一次 Agent Run 自然 `BUDGET_EXHAUSTED`，由于没有进入 Shadow，不计作 Judge 失败、取消或用量。关闭开关并删除测试会话后，notice 从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`；测试知识文档恢复为 `0`，保留原会话 `1`。

当前已经观察到自然无候选和预算耗尽的入口隔离，但四次 Judge 本身全部成功，仍没有网络、协议或认证等自然 Judge 失败分布。完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 与默认完整 `OK (191 tests)` 通过。`store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 与 `productionEnforcementEnabled=false` 继续不变。下一阶段只继续低频积累真实成本和自然 Judge 失败证据，不增加 Room Store，也不开启 enforcement；设备工具进入 Workflow/后台自动化及其余远期能力继续后置。

## 第 97 阶段：answerability shadow 真实样本与进程内遥测（完成）

已在第 96 阶段默认关闭生产接线之上增加固定上限的进程内 tracker。它只累计样本终态、Judge attempt、延迟/TTFB、Prompt 字节、Tokens、usage attempt、失败分类和 notice 生命周期，不保存问题、答案、候选正文、引用、原始响应、消息 ID 或凭据；设置页明确说明重启清空。答案保存后、Judge 发出前再次检查开关，用户关闭后不再发送问题或候选正文；重试成功仍保留前序失败分布。

Redmi 真实前台 `/agent + knowledge.search` 已得到 `1` 条完成样本，Judge `1` 次，耗时 `8437ms`、TTFB `8428ms`、Prompt `8952B`、Tokens `2340/361/2701`，失败/取消/异常均为 `0`。答案先保存，UI 后置显示“本地知识包含直接回答”和 `知识引用 · 3`；删除测试会话后 notice 从有效 `1` 裁剪为 `0`。开启状态下普通聊天未增加样本；Workflow/后台仍在 caller 边界外。完整 JVM `600/600`、Lint `0 issue`、APK、Redmi 定向 `OK (1 test)` 和完整 `OK (191 tests)` 通过，验收后开关恢复关闭。

本阶段没有新增 Room Store/migration，没有开启 notice 跨进程恢复或 enforcement。下一阶段应先基于更多真实前台样本评审 Provider 成本与失败分布是否稳定，再决定是否值得设计最小化持久化；单条成功样本不足以开启生产拒绝。设备工具进入 Workflow/后台自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。

## 第 96 阶段：answerability shadow 默认关闭的生产接线（完成）

本阶段完成生产 Judge adapter、冻结身份门禁、独立设置开关、答案保存后的异步 caller 和旁路 notice。Judge 固定使用第 92 阶段的 Responses 非流式协议、关闭 reasoning、`temperature=0 / topP=1 / maxTokens=220`，逐请求关闭全部 HTTP Debug 日志；实际 Provider identity 从当前配置的 ID、模型和 Base URL 指纹派生，必须与 `redmi-provider-compatibility / gpt-5.5 / configuration fingerprint / prompt version` 完全匹配，否则在请求前保持关闭。

前台直接 Agent 的答案先展示并进入正常会话保存，`AgentAnswerabilityShadowPublisher` 的 sibling Job 等待对应保存 Job 成功后才调用协调器。保存失败、旧保存被新快照取消、Workflow 来源或 Judge 失败只跳过 shadow，终败不发布 notice，也不进入 Agent 主失败分支。notice 只以进程内 `messageId` 映射注入知识引用区域，不改写消息、可信上下文或引用；会话删除或重载时裁剪悬空键。当前固定 `store=null / persistenceMode=NONE`，Room 仍为 v32，没有 migration。

完整 JVM `593/593`、Lint、Debug/AndroidTest APK、Redmi 默认完整 `OK (191 tests)`、显式生产 adapter `OK (1 test)` 和设置/偏好/notice `OK (3 tests)` 均通过。实际 Provider ID 下的 `12 + 12` calibration/validation 复验继续保持两类特征族通过，冻结阈值不回调。普通聊天、Workflow、后台 Worker、notice 持久化、Room shadow Store 与 enforcement 继续关闭。该阶段提出的真实前台样本后续已由第 97 阶段取得首条完成证据；最小化持久化仍需更多样本，且不得从旁路 notice 直接扩张为答案拒绝。

## 第 95 阶段：answerability shadow 真实测量协调（完成，生产未接入）

本阶段把第 94 阶段留下的“何时调用 Judge、失败如何收敛、是否持久化”冻结成纯 Kotlin 协调契约。离线 `KnowledgeAnswerabilityObservation` 继续保留人工 `label`，线上新增无标签 `KnowledgeAnswerabilityShadowMeasurement`；共享 `KnowledgeAnswerabilityAssessment` 和同一证据回查映射保证两条路径决策一致，但线上 measurement 不会被误计入 calibration/validation。

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 默认关闭且只接受前台直接 Agent。候选不完整或缺少冻结绑定时不调用 Judge；瞬时网络、限流、服务端和协议错误最多重试一次，认证、普通请求、身份、候选和未知错误不重试；取消原样传播。Judge identity 漂移保留 measurement 但 binding 未知。可选 Store 只保存指纹、幂等键、身份、尝试次数、状态、决策、失败分类和时间，Store 失败不会改变原答案、引用或 `enforcementApplied=false`。

新增协调器契约 `14/14`，完整 JVM `578/578`、Lint、Debug/AndroidTest APK 与仅 Redmi `OK (188 tests)` 通过。该阶段当时没有生产 Provider adapter、消息 caller 或答案引用 UI 接线；这些默认关闭的接线已由第 96 阶段完成。Room schema/store、普通聊天、Workflow、后台 Worker 和 enforcement 保持关闭。

## 第 94 阶段：真实消息流只读 answerability shadow 绑定（完成，生产未接入）

本阶段把第 93 阶段的展示 seam 与真实 Agent 消息中的可信知识检索证据连接起来，但仍保持只读。`VerifiedAgentContext.latestKnowledgeAnswerabilityCandidate(question)` 从最近的成功 `knowledge.search` ToolResult 提取候选；失败、无引用、空正文、空 Run 和其他工具不能冒充候选，旧单工具消息继续兼容。`KnowledgeAnswerabilityShadowBindingPolicy` 绑定来源 Run、同一 Judge 的强类型 calibration/validation identity、观测和冻结 gate，只让第 92 阶段已通过的两类特征族进入消息 shadow。

覆盖率特征族、Judge identity 漂移、缺观测、缺冻结绑定、观测 Run 不匹配和候选证据不完整均保持 `UNKNOWN`；候选引用在绑定时冻结快照，无观测时不伪造观测时间，引用顺序/身份与原答案不变，结果固定 `enforcementApplied=false`。本阶段不调用 Provider、不写 Room、不接入 `KnowledgeReferencesContent`、不改变普通聊天或 Workflow，也不打开生产拒绝。聚焦 JVM `11/11`，完整 JVM `564/564`，Lint、Debug/AndroidTest APK 通过；仅 Redmi `wsvwypiz7xwslvl7` 的真实套件为 `OK (188 tests)`，没有使用 Pixel_9。

第 94 阶段当时留下的 Judge 生成、失败/重试和可选持久化问题已由第 95 阶段冻结；第 96 阶段已补齐默认关闭的 Provider/caller/UI 接线。Room schema/store、生产 enforcement、设备 Workflow/后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续按路线图后置。

## 第 93 阶段：答案可回答性 shadow 呈现（实现与 Redmi 验收完成，生产未接入）

本阶段先补齐“如果 Judge 以后产生结果，用户应该看到什么”的纯展示契约，但不提前接入生产答案链路。`KnowledgeAnswerabilityShadowPresentationPolicy` 将 `ACCEPT / REJECT / UNKNOWN` 映射为直接回答、部分回答、未回答、矛盾、证据无法回查、低于冻结门禁和未知提示；输入引用始终原样保留，固定 `enforcementApplied=false`。`KnowledgeReferencesContent` 只增加默认 `null` 的可选参数，现有生产调用行为不变。

离线门禁与真机 UI 验收均已完成：既有 answerability 策略 `7/7` 与新增 shadow 呈现 `5/5` 合计 `12/12`；`KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已随 Redmi 默认完整 `OK (188 tests)` 通过，确认提示与原引用共存。没有连接或启动 Pixel_9。

第 93 阶段当时留下的真实消息流只读绑定、Judge 生成时机、持久化和失败语义已由第 94、95 阶段冻结。第 92 阶段仍只有两类特征族通过，覆盖率特征族未通过；因此后续生产接线仍不得修改答案、移除引用、升级生产相关性身份或开启 `productionEnforcement`。

## 第 92 阶段：答案可回答性策略（实现与真实 Provider shadow 验收完成）

本阶段先把第 91 阶段暴露的“同主题不等于真正回答”问题收敛成可审计的独立策略，而不继续调检索分数。实现已完成：严格 JSON 协议、固定 verdict、候选原文 quote 匹配、矛盾/部分回答拒绝、`UNKNOWN` 保守决策，以及三类特征族的 calibration/validation 身份隔离。`KnowledgeAnswerabilityPolicyTest` 离线 `7/7` 通过；Lint、Debug APK 和 AndroidTest APK 构建成功。第 92 阶段当时生产检索、Room、答案引用 UI、普通聊天和 enforcement 尚未读取该策略；第 96 阶段仅增加默认关闭的前台直接 Agent 旁路，`productionEnforcementEnabled=false`。

真实验收已只在 Redmi `wsvwypiz7xwslvl7` 完成：以 `redmi-provider-compatibility / gpt-5.5` 运行显式 calibration/validation 探针，各取得 `12` 条观测，网络/解析失败均为 `0`，最新身份校正复验 `OK (1 test)`、耗时 `94.154s`。`VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE` 通过，覆盖率特征族未通过；重复采集不用于回调已冻结门禁。主 APK、Activity、Provider/Profile 和设备状态已恢复，没有连接或启动 Pixel_9。

第 92 阶段当时规划的 shadow 呈现、只读绑定和 Judge 协调已由第 93 至 95 阶段完成；未通过的覆盖率特征族仍被排除，Judge 失败继续收敛为未知。本轮证据不升级生产 `VERIFIED`、不进入相关性 final holdout，也不允许拒绝执行。设备工具仍不得进入 Workflow/后台自动化，精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 91 阶段：跨主题平移不变特征探针否决

已完成新的跨主题归一化设计、纯 Kotlin 契约和 Redmi 三轮真实证据。`KnowledgeRelevanceCrossTopicNormalizationPolicy` 只使用已有审计字段构造 `top1 - 候选均值` 与 `margin / 候选标准差`，比较两个单特征和组合共三族；候选标准差接近零或输入非有限时直接拒绝。正式身份、配置指纹和互异 calibration/validation 版本继续强绑定，validation 不参与选阈值，生产 Room、Store、答案链路与 enforcement 不变。

`stage91-cross-topic-calibration-v1 / validation-v1` 各 12 篇新主题文档、三桶各 4 条查询并重复 2 次，共 `24 + 24` 条观测；Recall@5 均为 `1.0`，三轮 Redmi 均 `OK (1 test)` 且通过族为 `0`。`top1-均值` 与组合在 validation 上正例接纳 `1.0`、近负例拒绝 `0.75`、远负例拒绝 `1.0`、稳定率 `1.0`；`margin/标准差` 更弱，仅为 `0.75 / 0.25 / 1.0 / 1.0`。因此平移不变性虽然解决了本轮正例保留，却没有解决“同主题但语料未回答”的近负例误接纳。

本阶段明确否决继续围绕同一检索分数微调。下一阶段若继续相关性路线，应先设计 answerability/重排证据，让系统判断候选文档是否真正包含问题答案，再重新注册 calibration/validation；新的独立证据通过前，不进入 final holdout、不升级 `VERIFIED`、不接入生产答案路径。完整本地门禁为 JVM `541/541`、Lint、Debug/AndroidTest APK；Redmi 默认全量 JUnit XML 已完成，为 `186` 条（`176 passed / 10 skipped / 0 failed`）。

## 第 90 阶段：正式相关性 calibration/validation 预注册门禁否决

已完成正式 Provider 身份下的独立 calibration/validation 证据采集，但结论是质量门禁否决，不是生产通过。`KnowledgeRelevanceProductionCalibrationPolicy` 绑定 Provider、模型、配置指纹和互异 `datasetVersion`，只从 calibration 冻结七类特征族阈值，再在 validation 原样评估。Redmi `wsvwypiz7xwslvl7` 使用 `redmi-production-embedding-v1 / Qwen/Qwen3-Embedding-0.6B`，配置指纹 `2f22bfe3b9db92555f493c173116c58970490ece7fa90b8c7bf156aa7456dbf6`，两套数据各 24 条观测、Recall@5 均为 `1.0`；最新 raw top1 validation 正例接纳 `0.75`，近/远负例拒绝 `1.0`，七类特征族通过数 `0`，重复运行的正例接纳在 `0.625–0.75` 之间，说明跨主题绝对分数漂移仍未解决。

显式真实校准测试已改为“预期门禁否决即测试成功”，Redmi `OK (1 test)`；没有降低标准、没有用 validation 回调阈值、没有升级 `VERIFIED`、没有进入 final holdout，`productionEnforcementEnabled=false`。新增模型漂移 JVM 回归；完整本地 JVM `535/535`，默认 Redmi instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`）。

下一阶段不应把正确排序误读为相关性门禁通过，也不应继续围绕同一绝对阈值调参；应先决定是否建立新的跨主题归一化特征/标注设计并重新注册数据，或保持生产拒绝关闭继续积累证据。答案级知识引用、生产 Store、`knowledge.search`、普通聊天和 Workflow 继续不读取控制面。

## 第 89 阶段：生产身份绑定与相关性灰度控制面

已完成身份状态机、持久化控制面和 Redmi 真实候选探针，但仍未启用生产拒绝。正式身份区分 `UNBOUND / CANDIDATE / VERIFIED / REVOKED`；真实 `/models + /embeddings` 只能证明端点、模型与向量协议可用，因此当前只绑定 `CANDIDATE`。Base URL 仅保存 SHA-256 配置指纹，不保存原始端点或密钥。升级为 `VERIFIED` 必须同时匹配冻结 gate、Provider、模型、配置指纹、独立数据集身份和明确通过的 final holdout 证据。

灰度控制面现在把“用户请求开启”与“生产身份已验证”分开：只有 `VERIFIED` 身份、gate、Provider、模型、证据版本和配置指纹全部一致时才可能解析为 `ENFORCE`，任何漂移都回到 `SHADOW`。设置页只展示身份、证据、当前 `SHADOW` 和撤销入口；撤销清除执行资格并把绑定标为 `REVOKED`，没有直接绑定、升级或绕过证据开启生产拒绝的入口。完整 JVM `532/532`、Lint、Debug/AndroidTest APK 和仅 Redmi 默认 instrumentation `184` 条（`176 passed / 8 skipped / 0 failed`）通过；真实身份探针 `1/1` 得到 `2 × 1024` 有限向量并保持 `CANDIDATE`。

第 90 阶段已在同一正式 Provider、模型和配置指纹下完成版本化 calibration/validation，但七类特征族全部被预注册标准否决。不能复用 validation 调参、降低正例标准或直接进入 final holdout；只有新的跨主题归一化/数据设计在重新注册后通过，才评审把 control-plane resolution、生产 decision 和 UX presentation 接入答案级知识路径。完成前 `RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台行为保持不变。

## 第 88 阶段：相关性降级、引用一致性与身份灰度契约

已完成契约实现、审查修复和 Redmi 完整门禁，但仍未启用生产拒绝。检索 hit 现在能区分 `LEXICAL / SEMANTIC` 来源；未来低分 enforcement 只能删除 semantic-only，词法和语义重叠命中继续保留，引用始终从最终候选生成。用户侧固定三种解释：“已降级为关键词匹配”“未找到足够可靠的本地知识”“相关性检查暂未应用”；来源未知、决策矛盾或 shadow 删除指令全部 fail-open。零引用时 UI 也能独立显示解释，但生产消息流尚未传入该提示。

灰度偏好默认关闭，并绑定 gate 版本、Provider 与模型；缺项、版本漂移或身份漂移自动回到 shadow，撤销清除四项资格。双轴审查进一步补齐了 calibration/validation datasetVersion 完整且不同的冻结身份约束。Stage 87+88 聚焦 JVM `16/16`、完整 JVM `522/522`、Lint、APK 和仅 Redmi instrumentation `180` 条（`173 passed / 7 skipped / 0 failed`）通过。首次完整套件的 20 个 Compose 失败由 Redmi dream/keyguard 引起，唤醒后的失败批次与保持唤醒的完整复验均全绿。

第 90 阶段已在正式身份下完成 calibration/validation，但七类特征族均未通过预注册标准；不能把该结果升级为生产身份或 final holdout。默认仍关闭，必须先重新注册跨主题归一化/数据设计并获得独立通过证据，才评审把 rollout resolution、生产 decision 和 UX presentation 串入答案级知识路径。完成这些前，`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台行为保持不变。

## 第 87 阶段：生产相关性拒绝设计评审

已完成纯策略设计与真实项目回归，但尚未把拒绝接入生产检索。`KnowledgeRelevanceProductionDesignPolicy` 复用 Stage 86 冻结 gate，要求 Provider/模型身份一致；高于 raw top1 下限的语义结果保持，低于下限只计划移除语义候选并保留词法兜底。开关默认关闭，关闭时只产生 shadow 判断；非语义状态、身份漂移、缺失或非有限分数全部 fail-open。策略没有被 `RoomKnowledgeDocumentStore.search()` 调用，不改变 Room v32、UI、检索排序或历史审计。

聚焦 JVM `5/5`、完整 JVM `511/511`、Lint、Debug/AndroidTest APK 和仅 Redmi 默认 instrumentation `178` 条（`171 passed / 7 skipped / 0 failed`）通过。正式应用已恢复 Room v32、兜底 Provider、设备 Agent 开关和 Accessibility 服务。下一阶段再完成用户可见“语义候选被降级/词法兜底”的文案与引用行为、灰度/回滚开关和真实接入前的评审，不直接开启生产拒绝。

## 第 86 阶段：冻结 raw top1 的第三套 final holdout

已完成实现与最终 Redmi 复验。新增 `KnowledgeRelevanceRawTopScoreFrozenGate` 与 `KnowledgeRelevanceFinalHoldoutPolicy`，冻结 `stage85-raw-top1-qwen-v1`、Stage 85 calibration/validation 完整身份和 raw top1 `0.6416276358587735`。策略只应用冻结 raw top1，强制第三套数据身份、Provider/模型一致、三桶与有限值完整，失败后不搜索新阈值。

`stage86-final-holdout-v1` 在运行前固定 20 篇全新成对主题文档，三桶各 10 条英文查询、每条重复 2 次；同时预注册正例/近负例/远负例/决策稳定和 Recall@1/5、MRR、排序稳定门禁。预注册后的首次有效 Redmi 运行耗时 `63.077s`；补齐 validation 身份校验并同步重建 Debug/Test APK 后，最终复验耗时 `67.018s`，60 条观测得到正例接纳 `0.90`、近/远负例拒绝 `1.0`、决策稳定 `1.0`、balanced accuracy `0.9667`，Recall@1/5、MRR、排序稳定均为 `1.0`，通过。中间 ABI 不一致和一次检索空分数回归未计入门禁。下一阶段进入生产相关性拒绝设计评审，但本阶段不直接改生产 Room v32、检索、UI 或 Provider 配置；生产拒绝继续关闭直至设计、回退与用户可见行为另行验收。

## 第 85 阶段：Embedding 特征族独立 calibration/validation

已完成实现与 Redmi 验证。新增 `KnowledgeRelevanceFeatureComparisonPolicy`，预注册比较 raw top1、margin、top1 z-score 及四种组合。每个特征族只从 calibration 观测点选择 gate；validation 原样应用冻结 gate。策略强制 Provider/模型一致、数据集版本不同、三桶完整、数值有限和 case 标签稳定。生产 Room v32、cosine+RRF、FTS4+LIKE 与无拒绝边界保持不变。

全新 `stage85-calibration-v1` 与 `stage85-validation-v1` 分别使用独立内存 Room、20 篇成对主题文档、三桶各 10 条查询和每条 2 次重复，共 `60 + 60` 条有效观测；Recall@5 均为 `1.0`。raw top1 gate `0.6416276358587735` 与 raw+margin gate `0.6416276358587735 + 0.021738810541493292` 的 validation 指标完全相同：正例接纳 `0.90`、近/远负例拒绝 `1.0`、稳定率 `1.0`、balanced accuracy `0.9667`。单 margin、单 z 和含 z 的组合没有更优；首轮把 companion 可直接回答的问题误标为近负例的数据草案已废弃，不进入证据或阈值。

下一阶段按简约原则冻结 raw top1 候选及其 Provider/模型/calibration 身份，用第三套全新 final holdout 预注册验证；若再次出现正例接纳不足，候选必须被否决，不能回调。本阶段完整门禁为 JVM `502/502`、Lint、Debug/AndroidTest APK 和仅 Redmi `177/177` instrumentation；6 个显式联网用例默认 skipped。生产相关性拒绝继续关闭。

## 第 84 阶段：Embedding 查询内相对分布 shadow 观测

已完成实现与 Redmi 验证。新增纯 Kotlin `KnowledgeRelevanceRelativeDiagnosticsPolicy`，从同次查询当前有界语义索引候选池的全部有效 cosine 候选计算均值、总体标准差和 top1 z-score；整体分数平移不改变 z-score，单候选或零方差保持未知。生产 Store 在 top-K 截断前计算这些值，Room v32 持久化三个可空字段，v31 历史记录保持 `null`；知识管理页继续以“校准观测”展示，不改变召回或拒绝行为。

Redmi v31→v32 迁移、Room 写入回读与 UI 聚焦 `3/3` 通过，真实 Provider 小型语义链 `1/1` 通过。对已退休 Stage 83 holdout 的一次 shadow 观测只验证新字段：正例 z-score `2.929–3.722`、近负例 `2.226–3.232`、远负例 `1.579–2.879`。正例与近负例区间重叠，说明查询内标准化缓解绝对分数平移，但 z-score 本身仍不足以形成生产门禁；本轮没有搜索阈值，也没有把 Stage 83 holdout 变成校准数据。

完整门禁为 JVM `499/499`、Lint、Debug/AndroidTest APK 和仅 Redmi `176/176` instrumentation 通过；5 个显式联网用例默认 skipped。Room v32、cosine+RRF、FTS4+LIKE 与无生产拒绝边界保持不变。下一阶段建立全新版本的 calibration/validation 数据，预注册比较 raw top1、margin、z-score 及组合候选；Stage 83 holdout 继续封存，只有新的独立证据通过后才讨论最终 holdout。

## 第 83 阶段：冻结候选门禁的独立 holdout 验证

已完成实现与 Redmi 三轮独立验证，结论是第 82 阶段候选被否决。新增 `KnowledgeRelevanceDatasetIdentity`、`KnowledgeRelevanceFrozenGate`、`KnowledgeRelevanceHoldoutCriteria`、`KnowledgeRelevanceHoldoutReport` 与 `KnowledgeRelevanceHoldoutPolicy`，强制 Provider/模型一致、校准集与 holdout 版本分离、三桶完整、数值有限和 case 标签稳定。holdout 只应用冻结 top1 `0.6735426515268672` 与 margin `0.0178535973263384`，没有候选搜索或生产配置写入。

全新 20 篇成对主题语料包含三桶各 10 条英文查询、每条重复 2 次。Redmi 三个独立进程均得到正例接纳率 `0.80`、近负例拒绝率 `1.0`、远负例拒绝率 `1.0`、决策稳定率 `1.0`；Recall@1、Recall@5、MRR 和排序稳定率也均为 `1.0`。手冲咖啡正例 top1 约 `0.6169`，缝纫机张力正例约 `0.6661`，两者都稳定低于冻结下限。索引耗时 `14.237–14.696s`，查询中位数 `0.757–0.800s`、P95 `0.814–0.836s`，20 行 1024 维向量共 `81,920` 字节。

这说明排序质量全对不等于拒绝门禁可用：冻结候选会稳定误拒 `20%` 正例，未达到预注册 `90%` 正例保留率。因此本阶段实施完成，但候选没有进入生产拒绝设计的资格；不得使用已见 holdout 回调当前阈值。Room v31、cosine+RRF、词法兜底和 shadow 审计保持不变。下一阶段应建立新版本的嵌套 calibration/validation/holdout 方案或可跨主题归一化的门禁特征，再用未见数据重新验证；在新的独立证据通过前，不实现生产相关性拒绝。完整离线门禁为 JVM `495/495`、Lint、Debug/AndroidTest APK 和仅 Redmi `175/175` instrumentation 通过；5 个显式联网用例默认 skipped，三轮显式 holdout 按预注册断言失败并作为否决证据保留。

## 第 82 阶段：Embedding 相关性校准扩样与 shadow 候选门禁

已完成实现与 Redmi 三轮真实校准。新增纯 Kotlin `KnowledgeRelevanceCalibrationPolicy`，对三个业务桶输出 top1/margin 的 P05/P50/P95，并从观测值组合中选出同集 balanced accuracy 最优的 `minimumTopScore + minimumScoreMargin` 候选。语料由 10 篇孤立主题扩为 20 篇成对主题，正例、近负例、远负例各 10 条、每条重复 2 次；三次独立 instrumentation 共 180 个观测，Recall@1、Recall@5、MRR、重复排序稳定率和同集 shadow balanced accuracy 三轮均为 `1.0`。20 行 1024 维 Float32 向量共 `81,920` 字节，索引耗时 `14.712–15.332s`，查询中位数 `0.797–0.807s`、P95 `0.824–0.866s`；检索后 PSS 为 `202,124–202,359 KB`。

三轮候选 top1 下限为 `0.6735–0.6741`，margin 下限为 `0.0179–0.0184`；正例 top1 P05 `0.6735–0.6741`，近负例 top1 P95 `0.6063–0.6073`，当前固定语料存在稳定间隔。但这仍是同一小样本上的选择与回测，不代表未知问题、其他语料或其他 Provider/模型。第 83 阶段应先冻结 Provider/模型专属候选与版本化校准身份，再加入完全不参与调参的 holdout 正例/近负例/远负例；只有 holdout 的正例保留率、两类负例拒绝率、排序质量和失败回退同时满足预先定义的门禁，才进入生产拒绝设计。最终 JVM `491/491`、Lint、Debug/AndroidTest APK 和仅 Redmi `174/174` instrumentation 已通过；ANN、后台批量重建、设备 Workflow/后台自动化及后续生态能力继续后置。

## 第 81 阶段：Embedding 相关性 shadow 诊断与首轮校准

已完成实现和 Redmi 验收。Room v31 为检索审计保存可空的 top1、top2、margin 和有效候选数，v30 历史记录保持未知；知识管理页显示“校准观测”，但不改变生产召回。真实 Provider 在第 80 阶段同一 10 篇语料上运行正例、近负例、远负例各 2 条，三类 top1 区间为 `0.6806–0.7130 / 0.6502–0.6854 / 0.3704–0.4083`，margin 区间为 `0.2743–0.2828 / 0.1311–0.2114 / 0.0274–0.0507`；全部查询均有 10 个有效候选且词法零命中，真实校准 `1/1` 通过。该结果说明分数与 margin 的组合值得继续验证，但每桶 2 条不能支撑跨 Provider/模型阈值。第 82 阶段应先扩大标注查询与干扰文档、重复采集各分桶，再决定只作用于纯语义候选的“分桶绝对下限 + margin + 词法兜底”；当前不启用生产拒绝。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 和仅 Redmi `174/174` instrumentation 通过。ANN、后台批量重建、设备 Workflow/后台自动化及后续生态能力继续后置。

## 第 80 阶段：真实 Embedding 有界语料基线

已完成实现和 Redmi 三轮验收。新增的显式联网 instrumentation 在内存 Room 导入 10 篇中文单主题文档，用 5 个词法零命中的英文问题各重复检索两次。三轮 Recall@5、MRR 和重复排序稳定率均为 `1.0`；10 行 1024 维 Float32 向量共 `40,960` 字节，SQLite 内存页总量 `593,920` 字节。索引耗时为 `7.935–10.039s`，中位数 `8.881s`；每轮 10 次查询的中位数为 `0.811–1.100s`，P95 为 `1.016–1.496s`；检索后 PSS 较基线增加 `7,358–15,941 KB`。无关珊瑚产卵问题每轮仍返回 5 个近邻，证明当前 cosine+RRF 只做排名而不做相关性拒绝。这一边界比 ANN 或后台批量索引更紧迫，下一阶段应先使用固定正负语料建立可校准的相似度分布与拒绝策略，不得凭单一经验阈值破坏跨模型兼容。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `171` 个用例，`168` passed、`3` skipped、`0` failed。设备 Workflow/后台自动化和后续生态能力继续后置。

## 第 79 阶段：真实 Provider 语义检索端到端

已完成实现和 Redmi 验收。显式联网测试直接复用生产 Embedding 适配器与 Room Store，使用内存数据库隔离正式数据；真实 Provider 的模型同步与双输入向量协议 `1/1` 通过，完整语义链 `1/1` 在约 `5.947s` 内完成。英文问题在词法路径零命中，真实向量路径首位命中中文目标文档，检索审计为 `USED`，Provider/模型、chunk IDs、索引摘要和显式重建均可核对。默认完整套件缺少显式参数时继续跳过联网测试，因此 CI/本地门禁不依赖公网。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `170` 个用例，`168` passed、`2` skipped、`0` failed。下一阶段应扩展到更大但有界的真实语料，记录索引耗时、查询耗时、向量行数、Recall/MRR 与内存边界，再根据证据决定是否需要 ANN 或后台批量索引；设备 Workflow/后台自动化和后续生态能力继续后置。

## 第 78 阶段：Embedding 检索质量与兼容诊断

已完成并通过收尾门禁。新增独立质量评测，把文档级去重后的 Recall@5、MRR、负例准确率和重复排序稳定率变成可重复契约；5 份核心长期文档作为黄金语料，5 个正例与 1 个负例各运行两次，门禁满足 `1.0 / >=0.8 / 1.0 / 1.0`。知识管理页现在显示本次检索实际走过的语义融合、仅词法、无索引、Provider 不可用或维度不匹配路径，并在可用时标出 Provider/模型。该阶段使用的聊天兜底 Provider 没有 Embedding 模型，所以当时诚实停在词法兜底；第 79 阶段已使用独立真实 Embedding Provider 补齐协议与完整语义链。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个显式联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段：Embedding 索引生命周期

已完成并通过收尾门禁。知识管理页可查看当前 revision 下各 Provider/模型索引的维度和分块数，并可对启用文档显式重建当前选中 Provider/模型。升级前旧文档不再只能永久使用 `NO_INDEX`；Provider/模型切换后多个索引空间共存，重复重建某一空间不会覆盖其他空间。写入遵循“先请求和校验、后事务替换”，事务内再次核对 revision、enabled 和 chunk 身份；异常、超时、停用或并发替换保留已有索引及词法能力。正文替换继续清理所有旧 revision 空间，删除继续清理全部索引。Room 保持 v30。完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和仅 Redmi `164/164` instrumentation 通过。ANN、自动后台批量重建、设备 Workflow/后台自动化、精确定时、Foreground Service 及后续生态能力仍按既定顺序后置。

## 第 76 阶段：Embedding 检索 v1

已完成并通过收尾门禁。Provider 只有在已同步模型列表包含 Embedding 模型时才启用语义索引；`ProviderRequestConfig` 固定 Provider 身份和可选 Embedding 模型，`/embeddings` 请求沿用鉴权、User-Agent、自定义 Header 和 URL 规范化。Room 从 v29 升到 v30，新增按 `providerId + model` 隔离的 `knowledge_chunk_embeddings`，并为 `knowledge_retrievals` 保存 Provider、模型和稳定状态。向量采用 little-endian Float32 BLOB，导入/替换索引有 30 秒总时限，查询有 2 秒边界；异常、超时、无索引和维度漂移回退 FTS4+LIKE。语义、FTS、LIKE 使用稳定 RRF 融合，最终读取再次核对 enabled/revision，替换和删除不保留旧 revision 向量。新增 JVM、Room 迁移和知识存储 instrumentation；该阶段完整 JVM、Lint、Debug/AndroidTest APK 及仅 Redmi `158/158` instrumentation 通过。第 77 阶段已在此基础上补齐显式重建和多索引空间共存。

## 第 75 阶段：`/agent` 附件输入 v1

已完成并通过收尾门禁。前台直接 `/agent` 在 Responses 模式支持单条 USER Image 或 Document；同一 Run 的每轮规划请求保留附件，summary、VerifiedAgentContext、ToolResult、Tool part 和 Agent 输出不接收附件。Chat Completions、混合附件、持久化重复附件和非 USER 伪造来源在请求前 fail-closed。初次发送先提交 Room USER MessagePart 再建立 Run；审批恢复与任务中心重试从 Room 原消息重建，重试复制到新 USER 消息和新 Run，旧 Run 不变。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过；图片 Run `run-e2c23f3d-c7f9-41cc-9964-e0364741727e`、文档 Run `run-9e66e0eb-7684-4d92-8e5d-cfd3ec044d10` 的创建/回读验证均为 `PASSED`，无工具直接 `complete` 的 Run `run-9f4c1380-60de-4998-b689-65d570812431` 被 fail-closed。Workflow/后台 Agent 仍无附件入口；Embedding、设备后台自动化、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续后置。

## 第 73 阶段：会话选择与删除副作用协调迁出 ViewModel

已完成并通过标准门禁。新增 `ConversationSelectionCoordinator`，只组合既有 Session Policy、Persistence Coordinator 与 Load Coordinator，统一新建、历史选择和删除切换的副作用顺序；删除失败由协调器在发布 Failed 前按捕获代次回滚，`ConversationLoadRequest` 不再了解持久化意图。ViewModel 只清理/读取 Agent Run 与审批 Map、投影 UI 并保存成功选择，从 4121 行降到 4087 行。聚焦 `4/4`、第 68 至 73 阶段组合 `30/30` 和完整 ViewModel Kotlin 2.3.20 手工编译通过；后续原生完整 JVM、Lint、APK、仅 Redmi instrumentation、正式包复装与 Room/crash 检查也已补齐，门禁基线为 JVM `472/472`、Redmi `153/153`。

## 第 72 阶段：会话新建与删除选择规则迁出 ViewModel

已完成。`ConversationSessionPolicy` 新增纯 `ConversationSelectionPlan.Immediate / Load`：当前空会话幂等复用且不改变其他空占位；已有内容时复用并折叠最新空占位；无空占位时按注入时钟创建稳定新会话；删除后选择最新剩余会话并进入加载，删至空列表则立即创建占位。`restoreRuntimeState` 明确区分复用与新建，防止新占位因时间戳 ID 碰撞恢复旧 Agent Run/审批。ViewModel 从 4178 行降到 4121 行，只保留取消、删除意图、运行态 Map、加载、回滚和保存。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `5/5`，完整 JVM `468/468`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 71 阶段：会话加载 UI 投影规则迁出 ViewModel

已完成。新增纯 Kotlin `ConversationLoadProjectionPolicy`，统一 Loading 清理旧结果、Loaded 原子选择会话与 Failed 错误收敛；非当前会话索引同时剥离 Image/Document BLOB，当前可见会话仍注入完整消息与附件。ViewModel 从 4200 行降到 4178 行，只保留删除意图回滚、Agent Run/审批映射读取、成功后的选择保存和其他副作用；`ConversationLoadCoordinator` 的 Job/代次边界不变。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`，完整 JVM `463/463`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 70 阶段：异步会话加载协调迁出 ViewModel

已完成。新增纯 Kotlin `ConversationLoadCoordinator`，统一 latest-load Job、单调选择代次和 Loading/Loaded/Failed 事件。取消是协作式的，因此旧 Room 查询或 loader 在取消后仍返回/失败时只会被代次门禁丢弃，不能覆盖当前会话、删除回滚或失败提示；新 Job 先登记再派发 Loading，因此回调重入选择也不会丢失最新 Job。ViewModel 继续负责会话选择、附件轻量化后的原子 Compose 投影、当前删除意图回滚和选择保存，不以总行数变化宣称 UI 编排已经迁出。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。四轮 TDD 后聚焦 JVM `4/4`，完整 JVM `460/460`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 69 阶段：会话保存协调迁出 ViewModel

已完成。新增纯 Kotlin `ConversationPersistenceCoordinator`，统一 latest-save Job、Room 单写者串行、发送前等待旧保存，以及显式删除 ID 的代次化确认与回滚。旧保存即使已进入不可取消提交区，最新快照也会等待并最后写入；事务失败、取消、同 ID 在提交期间重新标记或旧读取失败回调晚到时不清除新删除意图。`XiaoLingViewModel` 不再持有会话保存 Job 和待删除集合，从 4189 行降到 4183 行；异步会话加载、删除后的 UI 切换/失败回滚与 Compose 副作用仍保留。Room v29、附件 BLOB、协议、UI、`/agent` 与 Workflow 不变。八轮 TDD 后聚焦 JVM `8/8`，完整 JVM `456/456`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 68 阶段：会话状态投影规则迁出 ViewModel

已完成。新增纯 Kotlin `ConversationSessionPolicy`，统一第一条 `role=user` 消息标题（正文空白时保持“新会话”）、重复空会话折叠、既有会话时间戳、摘要元数据默认继承、blank ID 生成，以及非当前会话迟到更新与当前 UI 的隔离。`XiaoLingViewModel` 删除 83 行对应私有实现，从 4272 行降到 4189 行；异步 Room 加载、保存 Job、删除事务与 Compose 副作用仍留在 ViewModel。Room v29、Provider 协议、UI、`/agent` 与 Workflow 行为不变。六轮 TDD 后聚焦 JVM `6/6`，完整 JVM `448/448`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 67 阶段：普通聊天网络发送编排迁出 ViewModel

已完成。新增纯 Kotlin `ConversationSendCoordinator`，用稳定事件统一发送前 Room 快照持久化、上下文准备、模型请求、流式增量和成功/取消/失败终态。`XiaoLingViewModel.sendMessage()` 从约 190 行收敛到约 104 行，只保留入口校验、用户输入投影、旧保存 Job 协调和发送 Job 生命周期；Compose 状态、30ms 流式节流与最终消息投影继续留在 ViewModel，因此不宣称总文件继续缩小。取消事件携带最近已准备上下文并在 UI 收敛后继续传播 `CancellationException`；持久化失败不调用 preparer 或模型。Room v29、协议、UI、`/agent` 和 Workflow 不变。新增聚焦 JVM `3/3`，完整 JVM `442/442`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 66 阶段：普通聊天请求上下文准备迁出 ViewModel

已完成。新增可独立测试的 `ConversationRequestContextPreparer`，统一负责消息上下文资格、知识引用生命周期、旧摘要失效/复用、最近 16 条窗口、增量摘要、窗口外最多 8 条可信 Agent 结果和 Responses 用户附件请求投影。`XiaoLingViewModel` 只注入知识 Store、摘要网络调用与当前提示词设置，从 4439 行降到 4224 行；知识核验和摘要生成的协程取消现在继续传播。Room v29、协议与 UI 不变。新增聚焦 JVM `8/8`，完整 JVM `439/439`、仅 Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 65 阶段：进程退出观察只读诊断 UI

已完成。设置页可以只读查看 Room v29 最近 30 条进程退出记录，刷新只调用 `latest()`，不会再次采集或改变样本。页面以不同中文标签区分直接 LMK、LMK 候选、应用故障、系统资源限制、受控/维护和未归因退出，并展示稳定数值字段；固定声明不关联 Agent Run、工作流或任务，候选和受控退出不能作为自然 LMK。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；真实受控 `force-stop` 显示为 `USER_REQUESTED / 受控退出或包维护`，刷新前后账本均为 1 条。Room 仍为 v29，Foreground Service、旧执行栈恢复和设备后台自动化边界不变。

## 第 64 阶段：Android 进程退出观察账本

已完成。Android 11+ `ApplicationExitInfo` 由前台启动和生产 Worker 冷启动旁路采集；Worker 先登记当前进程所有权，再读取退出历史。Room v29 使用无 Task/Run 外键的独立表保存稳定数值字段，以稳定身份去重并裁剪到最新 30 条，不保存 description、trace 或进程状态摘要。分类只把 `REASON_LOW_MEMORY` 作为直接 LMK；设备不支持直接报告时的 `SIGNALED + SIGKILL` 仅是候选，应用/用户取消和包维护保持受控分类。普通采集异常不阻断主流程，协程取消继续传播。JVM `431/431`、Redmi 聚焦 `5/5`、完整 instrumentation `149/149` 通过；正式 schema 29 的受控 `force-stop` 被记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不构成自然 LMK，也不改变 Foreground Service 后置策略。

## 第 63 阶段：真实应用取消原因与用户停止优先级

已完成。Redmi Android 14 的真实 WorkManager 在运行中 Worker 被应用取消时返回 `CANCELLED_BY_APP(1)`，生产停止原因策略可稳定识别；用户停止路径则继续以先落库的 `STOP_REQUESTED` 和用户原因作为权威事实，后到的应用取消码不会覆盖或伪造独立系统停止原因。聚焦 `2/2`、完整 Redmi instrumentation `145/145`、JVM `424/424` 通过。本阶段不属于自然 LMK、配额或超时，不改变普通 WorkManager 与 Foreground Service 后置结论。

## 第 62 阶段：后台停止原因可观测性

已完成。`ScheduledWorkflowWorker` 在 Android 12+ 读取 WorkManager 停止码，使用隐私安全的稳定 `code + name` 分类，系统停止时把原因透传到 Workflow 终态并在同一事务写入 ScheduledTask/WorkflowRun；任务中心展示该分类。Room v27→v28 迁移不为旧记录发明停止原因，旧 Android、`NOT_STOPPED` 与未知码保持保守结论。JVM `424/424`、Redmi `143/143` 通过；没有自然系统停止证据，因此不引入 Foreground Service，也不扩大旧执行栈恢复能力。

## 结论

第 58 阶段早期真实后台长任务样本曾因 Redmi TLS 握手失败而在约 4 至 6 秒收敛；Task/Workflow/Agent 正常收敛且预算单调、没有复制 Run，设备 `curl` 独立复现同一失败。网络恢复后的成功复验见下一段。

网络恢复后已补齐成功样本：同一 Redmi 生产 Worker 的 8 步 SAFE Workflow 用时 `92.667s`，8 个 Agent Run、8 个步骤和全部工具验证均成功，预算快照单调且只有一个 Workflow Run；历史退出仍为 `lowMemoryExits=0`，没有 Android 自主 LMK。该耗时仍由普通 WorkManager 稳定承载，不提前引入 Foreground Service；设备工具继续不进入 Workflow/后台自动化。

第 59 阶段又完成更长真实后台样本：Redmi 正式 WorkManager 在 `229.416s` 内完成 8 步复合 SAFE Workflow；8 个 AgentRun 全部完成，每步依次调用 3 个只读工具，24/24 ToolResult 与验证事件通过，72 条预算更新、`llmFailureKinds=[]`。同轮 LMK 取证为 `supported=true / lowMemory=0`，14 条历史退出均可归因于 instrumentation 或安装停止。普通 WorkManager 已有约 229 秒成功证据，仍不引入 Foreground Service；本阶段没有新增自然回收或数值预算单调结论。

第 60 阶段把运行身份从“instrumentation 持续等待终态”收紧为真实后台冷启动：Probe 在 `0.255s` 后退出、原 PID 消失，JobScheduler 冷启动新 PID `25825`，同一持久化 WorkRequest/ScheduledTask/WorkflowRun 在 `204.977s` 内完成 8 步与 32 次只读工具调用。8 个 Run 的预算快照均无回退，32/32 工具回执和验证通过，`lowMemory=0`。这增加了普通 WorkManager 的后台可信度，但没有自然 LMK、后台中断恢复或 Foreground Service 必要性证据。

第 61 阶段在 Redmi 熄屏状态继续验证：Probe 退出后原 PID 消失，JobScheduler 延迟 `159.479s` 冷启动 PID `26797`，屏幕持续 `Asleep` 期间同一 WorkRequest/ScheduledTask/WorkflowRun 完成 `244.236s` 的 8 步、32 次只读工具调用。8 个 Run 的预算快照无回退，最大约 `44.856s`，32/32 工具回执和验证通过，`lowMemory=0`。这是当前最接近真实用户离开应用场景的成功样本，仍不等同自然 LMK 或 Foreground Service 需求。

当前主线已具备可执行应用内任务的最小个人 Agent：普通聊天、直接 `/agent` 与可确认“任务”模式分流，任务模式能把自然语言目标严格转换为 1 至 8 步计划，并在用户确认后原子创建普通 Workflow/Run、复用既有 Runtime；确认前不写执行事实。Runtime 可取消、可限步、可确认、可验证并记录 Run、Step、Approval、Event 和 Memory；Agent Profile v1 已分离身份与能力，Room v33 已让结构化消息、知识引用、Embedding/Shadow、后台停止原因和独立进程退出观察持久化。长期记忆、声明式 Skill、1 至 8 步 Workflow、WorkManager 非精确定时、本地知识库、答案级引用 UI，以及设备 Agent 观察与有限动作层均已交付。完整限定设备工具集开放给前台直接 `/agent`；前台手动 Workflow 当前精确开放同一 Agent Run 内的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`，其中打开应用、点击与输入逐动作审批，`open_app` 的唯一包名经过三层白名单并与动作后包名绑定，`back / home / swipe` 为零审批 SAFE 动作。所有动作都要求当前观察和动作后 Executor/typed 验证，`home` 还必须匹配系统动态解析的 launcher，`swipe` 必须证明同窗内容变化和共同匿名锚点方向主位移。文本输入的跨入口持久路径只保存指纹与长度；Workflow 对七项工具投影白名单成功证据和稳定失败状态，其中 swipe 只消费通过专属完成门禁后的通用脱敏摘要。首批只对小灵、系统计算器、时钟、设置和桌面完成 Redmi 验收，不承诺任意 App；全部后台设备自动化、坐标与截图继续关闭。通用执行恢复矩阵已交付提交状态未知分类、用户确认的受控关联新 Run、已验证控制面收尾、两类原子失败结算、持久停止栅栏、Worker 所有权隔离和单调执行预算；旧 Run 与旧副作用事实保持不变，不恢复旧 Executor、模型协程或 Workflow 后续步骤。第 59 阶段约 229.416 秒复合 SAFE 后台成功样本仍未形成自然 LMK 或 Foreground Service 引入依据。第 108 至 127 阶段依次完成 Workflow 只读观察、答案级证据、本地判定、真实双 Run、动作安全契约、`tap_ref`、`type_text`、SAFE `back / home`、逐包审批 `open_app`、`swipe` 全链，以及自然语言个人任务与可确认计划；相关性生产拒绝与 answerability enforcement 继续关闭，Shadow 只在真正分隔窗口低频并行观察。

第 43 阶段的同一 WorkRequest Redmi 冷启动重入已完成真实验收：旧 PID 在首步 Agent `THINKING` 时被受控强杀，新 PID 自动重入并按 Agent→Workflow→Task 收敛，没有创建第二个 Agent Run 或继续后续步骤。该样本使用 `run-as kill -9` fallback，不代表 Android 自主回收；该阶段当时的重点是更长/自然回收样本。第 46 阶段已进一步补充 Doze、受控内存和无压力对照，第 47 阶段解决了同一进程前台启动恢复与新 Worker 并发时的所有权隔离；当前仍缺自然 LMK。

参考项目中最值得学习的不是工具数量，而是以下工程原则：

- `meow-agent`：工具风险元数据、权限策略、后置验证、运行事件和 Workflow Ledger。
- `Operit`：Android 原生工具体系、MCP/Skill、记忆空间和多种移动端能力组合。
- `X-OmniClaw`：统一设备工具、界面观察后执行、按需工具路由、Markdown 记忆与索引、定时自动化。
- `openclaw`：Channel、Gateway、Session、Skill 和自动化边界，以及对长时间运行 Agent 的工程化拆分。
- `mobilerun`：面向移动 UI 的观察、动作和多步任务执行模型。
- `RikkaHub`、`PocketPal AI`、`OGAM`：Provider/模型能力、结构化消息、工具事件流、本地模型和 RAG 的产品化经验。

完整证据见 [参考项目分析](reference-apps-analysis.md)。

## 当前基础与主要缺口

### 已有基础

- OpenAI-compatible Provider 管理和模型同步。
- Chat Completions / Responses API。
- SSE 流式输出和 30ms UI 节流。
- 多会话、摘要压缩、Room 本地持久化。
- Provider、模型、接口模式、流式和耗时等消息元数据。
- Android Keystore 密钥保护和网络错误分类。
- 请求取消和停止生成。
- 多 Agent Profile 创建、编辑、选择和删除；每个 Profile 固定 Provider/模型、API 模式、系统提示词、上下文策略、工具/Skill 白名单和记忆开关。
- Text/Reasoning/Image/Document/Tool 消息 parts：历史/流式文本兼容、单附件用户图片/文档、供应商 summary 折叠展示、Tool 可信投影、前后台统一 Room 写入和同气泡证据展示。
- `AgentRun / AgentStep / ApprovalRequest / RunEvent / AgentMemory` 初始数据模型，以及 `/agent` 模型规划 + 应用内低风险工具链路。
- 最小 Agent Runtime 已具备工具调用预算、模型/工具步骤超时、整次 Run 超时、完整 Schema/业务规则/Android 权限校验、重复工具调用检测和结构化事件记录。
- 对话区已能显示当前 `/agent` Run 的最小时间线和审批卡片，批准后继续执行，拒绝后写入失败终态；交互审批当前不主动过期，审批请求已具备待确认状态和决定结果落库。
- 设置页已有 Agent 任务中心，可按全部/处理中/可重试/已完成筛选，查看完整工具结果、步骤、审批和结构化事件，并为失败、取消或预算耗尽任务创建关联的新 Run。
- 启动协调器已接入并通过真机进程重建验收：首个工具或任意已验证前缀之后的链尾待审批 Run、用户消息锚点和审批卡片可从 Room 重建；批准后 Runtime 从原审批步骤继续当前工具、验证和后续规划并写回同一 Run，前序工具不重放。

### 主要缺口

- 当前重试默认采用安全重新运行：旧 Run 保持不变，新 Run 关联 `retryOfRunId` 并重新走模型规划、工具审批和验证；`WAITING_APPROVAL` 原地恢复、两个白名单写工具的已提交结果只读验证，以及全部工具已验证后的本地收尾恢复已经接入。
- 旧模型协程、提交状态未知、成功结果尚无 typed 验证结论和其他证据不完整形状的通用工具执行栈仍不恢复。已交付例外都有完整持久化证据：待审批路径不重放已验证前缀，白名单写工具只读验证原 operation，全部 `PASSED` 路径只补控制面与本地总结，严格失败 ToolResult 或 typed 失败验证路径只原子结算对应 Step/Run 为 `FAILED`。
- 第一批真实 Tool Registry 已统一声明 JSON Schema、可插拔业务校验器、风险/确认、Android 权限、后台能力、超时和验证策略；生产权限检查器默认 fail-closed，Runtime 已按前台/后台来源执行能力门禁。
- 已有结构化长期记忆表、`memory.search / memory.remember`、FTS 检索、管理 UI、候选确认、敏感过滤、跨进程删除撤销、生命周期、时间衰减、引用审计、去重和冲突处理；更大数据量下的召回质量仍需持续验证。
- 已有 Room v31 知识文档、chunks、FTS4/LIKE/Embedding、带相关性 shadow 字段的检索审计、管理 UI、只读 Agent 工具、模型引用注入和答案引用呈现；第 82 阶段已完成扩样校准，生产拒绝、规模化 ANN 与更大语料泛化仍需验证。
- 已有内置与本地声明式 Skill 按需选取、严格导入校验、工具白名单和管理 UI；多步骤 Workflow 定义/编辑、前台与后台顺序执行、步骤快照、新 Run 重试、一次性和 Daily/Weekly 调度、通知和审批 blocked 状态已完成。
- AccessibilityService 观察与有限动作层已经交付；前台手动 Workflow 已精确开放 `snapshot / open_app / back / home / tap_ref / type_text / swipe`，后台设备执行、坐标/截图兜底和任意 App 通用能力仍未开放。
- ViewModel 仍偏重，但 Compose 结构工程已到停止点：第 66 至 73 阶段及后续横向工程迁出了普通聊天、会话、Agent Run/审批、候选记忆和 Provider 模型同步编排；应用导航、十一个业务页面和四组功能对话框均已拥有窄状态、局部呈现状态与 actions/callback 边界。`XiaoLingApp.kt` 从 `7,018` 行降到当前 `817` 行，`SettingsPage` 继续保留平台协调职责。后续不继续按行数拆宿主或 ViewModel；通用执行恢复矩阵、前台 Workflow 七项设备工具和第 127 阶段可确认任务入口已经完成，下一轮直接进入第 128 阶段限定 App 多动作连续执行，不再单独立项结构瘦身或设备原语证据打磨。

## 目标架构

```text
Compose UI
  |-- App Navigation / Back Effects (`ui/navigation`)
  |-- Workflow Management (`ui/workflow`)
  |-- Agent Task Center (`ui/agenttask`)
  |-- Memory Management (`ui/memory`)
  |-- Provider Management (`ui/provider`)
  |-- Process Exit Diagnostics (`ui/processexit`)
  |-- Chat
  |-- Agent Run Timeline / Approval Card
  |-- Skills / Agent Profile / Settings
  |
Application services
  |-- ConversationRequestContextPreparer
  |-- ConversationSendCoordinator
  |-- ConversationSessionPolicy
  |-- ConversationPersistenceCoordinator
  |-- ConversationLoadCoordinator
  |-- ConversationLoadProjectionPolicy
  |-- ConversationSelectionCoordinator
  |-- AgentRunRetryCoordinator
  |-- AgentConversationRuntimeStateStore
  |-- AgentApprovalDecisionCoordinator
  |-- RecoveredAgentApprovalCoordinator
  |-- AgentMemoryCandidateCoordinator
  |-- ProviderModelSyncCoordinator
  |-- AgentService
  |-- WorkflowService
  |
Agent Runtime
  |-- Intent Router: direct chat or agent run
  |-- Bounded Tool Loop
  |-- Tool Policy / Approval / Verification
  |-- Cancellation / Resume / Event Log
  |
Capability layer
  |-- ToolRegistry
  |-- SkillRegistry
  |-- MemoryRetriever
  |-- DeviceController
  |
Data layer
  |-- Room: conversations, runs, steps, memories, skills, tasks
  |-- Android Keystore: provider secrets
  `-- WorkManager / AlarmManager: scheduled execution
```

已落地的 UI 垂直包为 `com.longdev.xiaoling.ui.navigation`、`com.longdev.xiaoling.ui.workflow`、`com.longdev.xiaoling.ui.agenttask`、`com.longdev.xiaoling.ui.memory`、`com.longdev.xiaoling.ui.provider`、`com.longdev.xiaoling.ui.agentprofile` 和 `com.longdev.xiaoling.ui.agentskill`。其余领域仍按依赖方向逐步收口：

```text
com.longdev.xiaoling.domain.agent
com.longdev.xiaoling.domain.tool
com.longdev.xiaoling.domain.memory
com.longdev.xiaoling.data.db
com.longdev.xiaoling.data.repository
com.longdev.xiaoling.llm
com.longdev.xiaoling.agent.runtime
com.longdev.xiaoling.agent.tools
com.longdev.xiaoling.agent.skills
com.longdev.xiaoling.automation
com.longdev.xiaoling.device
com.longdev.xiaoling.ui.memory
com.longdev.xiaoling.ui.provider
com.longdev.xiaoling.ui.agentprofile
com.longdev.xiaoling.ui.agentskill
```

不必立刻拆成多个 Gradle Module，但代码依赖方向必须先固定，避免 UI、网络、存储和工具互相直接调用。

## 里程碑 0：稳定现有聊天底座（部分完成）

目标：在引入 Agent 前，让现有请求和数据结构具备扩展条件。

当前状态：请求取消、停止生成、Room 迁移、Schema 导出、v4→v32 迁移测试、Text/Reasoning/Image/Document/Tool 消息 parts、KnowledgeReference、独立进程退出观察、Repository、Responses API 结构化文本/附件历史、函数 typed Items、可选 Reasoning summary、`LlmProviderAdapter`、普通聊天上下文 Preparer、发送 Coordinator、会话状态/选择 Policy、保存 Coordinator、加载 Coordinator、加载投影 Policy、选择 Coordinator、Agent Run 重试 Coordinator、会话级 Agent 运行态 Store、当前进程审批决策 Coordinator、恢复后审批 Coordinator、候选记忆 Coordinator、Provider 模型同步 Coordinator，以及导航、Workflow、Agent 任务中心、长期记忆、Provider、Agent Profile、Agent Skill、会话主界面和功能对话框 UI 边界均已完成；面向用户的 Room ZIP 备份/恢复也已交付。结构瘦身达到停止条件，后续只在通用执行恢复需要新的可靠接口时继续调整 ViewModel 或宿主。

### 要做什么

- 已完成：给当前请求增加明确的取消能力和“停止生成”按钮。
- 已完成：Responses API 改为结构化消息数组，保留 system/user/assistant 边界。
- 已完成：抽出 `LlmProviderAdapter`，由 `OpenAiCompatibleAdapter` 负责 URL、payload 和响应协议映射。
- 已完成：Responses 输入支持 `function_call / function_call_output` typed Items，并使用 `call_id` 关联调用和结果。
- 部分完成：`ProviderRepository`、`ConversationRepository`、`ConversationRequestContextPreparer`、`ConversationSendCoordinator`、`ConversationSessionPolicy`、`ConversationPersistenceCoordinator`、`ConversationLoadCoordinator`、`ConversationLoadProjectionPolicy`、`ConversationSelectionCoordinator`、`AgentRunRetryCoordinator`、`AgentConversationRuntimeStateStore`、`AgentApprovalDecisionCoordinator`、`RecoveredAgentApprovalCoordinator`、`AgentMemoryCandidateCoordinator` 与 `ProviderModelSyncCoordinator` 已落地；普通聊天上下文资格、知识生命周期、窗口、摘要准备、网络发送状态机、会话纯状态/选择投影、保存/加载/选择协调、Agent Run 重试资格/确认复核/附件准备、会话级 Run/Approval 生命周期、当前进程审批 waiter、恢复后审批、候选记忆与 Provider 模型同步编排已经迁出 ViewModel，Compose 副作用与其他 Agent/Workflow 编排仍需继续迁出。
- 已完成：引入 Room，并为现有 Provider、Conversation、Message 数据实现一次性迁移。
- 已完成：启用 Room Schema 导出，并为带旧数据的 v4→v32 migration 链、event metadata、Run 重试、Memory/Knowledge FTS、候选表、生命周期、Skill、Workflow、调度、多步骤快照、笔记幂等键、记忆 operation ledger/结果快照、独立工具账本、Agent Profile、MessagePart、知识引用和进程退出观察提供自动化测试。
- 已完成：增加面向用户的数据库 ZIP 备份与恢复能力；恢复前校验 schema，替换前保留 `.pre-restore`，并明确 Keystore 密文不可跨设备解密。
- 部分完成：普通聊天上下文准备、网络发送状态机、会话纯状态/选择投影、保存协调、加载协调、加载 UI 投影、删除失败回滚顺序、Agent Run 关联重试、会话级 Run/Approval Store、当前进程审批 waiter、恢复后审批、候选记忆与 Provider 模型同步协调已迁出；继续收敛 Compose 副作用与其他编排，使 ViewModel 更接近只负责 UI 状态投影和宿主副作用。

### 验收标准

- 原有 Provider 和会话升级后不丢失。
- 流式和非流式请求都能立即取消，不再追加内容。
- Chat Completions 与 Responses API 回归测试通过。
- 进程重建后可以正确恢复会话，但不会把未完成请求当成功。

## 里程碑 1：最小可用 Agent Runtime（最小闭环已交付）

目标：完成“判断是否需要工具 -> 调用工具 -> 获取结果 -> 继续推理 -> 输出最终答案”的受控闭环。

当前状态：`/agent` 最多 4 步的顺序工具闭环、单调累计执行预算、超时、取消、逐步审批、后置验证、多工具可信上下文、Run 时间线、RunEvent typed metadata、独立 ToolCall/ToolResult Room Ledger、可操作任务中心、安全重新运行和第一批应用内工具已完成；链尾待审批恢复可从任意已验证前缀继续，并恢复已消耗调用数、累计执行时间与循环指纹。所有成功 ToolResult 和 `PASSED` 验证已经落库时，原 Run 还可补齐最后验证 Step 并用本地可信总结收尾；严格失败 ToolResult 可原子结算执行 Step/Run 为 `FAILED`；成功 ToolResult 与 typed `FAILED` 验证、结果后完整预算、最后运行中验证 Step 和无尾随事件同时成立时，也只可原子结算验证 Step/Run 为 `FAILED`。两类失败结算都不调用 Executor、验证器或 LLM，不生成总结、不继续 Workflow。独立账本承接 v20 新事件的原子双写，任务中心、各类恢复与失败 Run 重试副作用判断均已切换为 Ledger-first；账本异常时重试 fail-safe 要求确认，账本完全为空的旧 Run 保守回退 typed RunEvent，但失败终态结算禁止 event fallback。并行调用、提交状态未知、成功结果尚无验证结论和其他证据不完整形状的通用原地断点恢复继续关闭。

### 核心数据模型

- `AgentProfile`：已交付名称、标识、system prompt、Provider/模型、API 模式、上下文策略、工具/Skill 白名单和记忆开关；新 Run 冻结完整快照。
- `AgentRun`：目标、来源、状态、开始/结束时间、当前步骤、最终结果。
- `RunEvent`：状态变化、模型决策、工具调用、工具结果、确认、错误。
- `ToolDefinition`：名称、描述、输入 Schema、风险、权限、确认和验证规则。
- `ToolCall` / `ToolResult`：参数、结果、错误、耗时、重试和验证状态。v20 已独立落表并与 typed RunEvent 原子双写；任务中心、受限恢复和重试副作用判断对新 Run 优先读取账本，事件仅核对锚点与字段，旧 Run 在账本全空时回退事件。
- `ApprovalRequest`：待确认动作、风险说明、过期策略和用户决定。当前每个非 SAFE 工具步骤独立审批且不主动过期；只要所有前序工具均已成功验证、链尾 ToolCall 尚未执行且 Approval 与账本完全匹配，首步或后续审批都允许原 Run 恢复。

### 运行状态

第一版保持简单，不照搬多阶段重型规划器：

```text
idle -> deciding -> waiting_model -> waiting_approval
     -> executing_tool -> verifying -> waiting_model
     -> completed / failed / cancelled
```

### 必须实现的运行约束

- 普通问答走 direct chat fast path。
- 最大工具步数、单步超时和整次 Run 超时均由应用配置。当前最小 Runtime 已有初版配置。
- 连续重复同一工具和相同参数时触发循环检测；顺序多步循环复用同一 Run 级指纹集合和工具调用预算。
- 模型只能看到当前允许的少量工具，不在每轮注入全部 Tool Schema。
- 已完成：工具参数先做 JSON Schema、未知参数、业务规则和 Android 权限校验，再进入审批与 Executor。
- 风险和确认要求取自 `ToolDefinition`，忽略模型自己声明的风险级别。
- Run 可取消；取消后不再接受迟到的流式事件或工具结果。
- 所有状态变化写入 `RunEvent`，UI 显示简洁任务时间线。当前已在对话流里显示最小时间线，并在设置页提供可筛选、可重试的任务中心；ToolResult 完整正文、成功/验证状态和耗时均可查看。

### 第一批工具

先做可验证、低风险、应用内部工具：

- `app.current_time`
- `app.list_conversations`
- `app.search_conversations`
- `notes.list`
- `notes.search`
- `notes.create`，执行前确认，执行后重新读取验证
- `memory.search` / `memory.remember`，以及长期记忆管理 UI、FTS、启停/删除、来源审计、候选确认、敏感过滤、去重/冲突和当前会话删除撤销。

暂不做任意文件写入、Shell、应用安装、发送消息和系统设置修改。

### 验收标准

- 模型可通过工具完成“查找旧会话”和“创建一条笔记”。
- 用户能看到工具名称、关键参数、执行结果和验证状态。
- 拒绝确认后 Run 正确结束或改走其他方案。
- 工具返回成功但后置读取不一致时，结果标记为“未验证”，不得宣称完成。
- 状态机、循环检测、取消和确认都有确定性测试。

## 里程碑 2：长期记忆（候选治理闭环已完成）

目标：把“会话摘要”与“跨会话个人记忆”分开，让记忆可见、可控、可追溯。

当前状态：Room 结构、来源审计、`memory.search / memory.remember`、FTS4 + 中文兜底、管理 UI、默认关闭的候选生成、敏感阻断、去重/冲突、跨进程删除撤销、实际引用 ID 审计、单次召回关闭、可空过期策略和时间衰减排序已完成。

### 记忆类型

- `Preference`：稳定偏好，例如语言、常用格式和习惯。
- `ProfileFact`：用户明确提供的个人信息。
- `Episode`：重要任务和事件结果。
- `Procedure`：经过验证的重复操作方法。

### 实现顺序

1. 已完成：Room 保存结构化 Memory，包含来源会话/Run、原文摘要、类型、置信度、更新时间、启用和置顶状态。
2. 已完成：提供记忆管理页，支持搜索、查看/跳转来源、编辑、置顶、禁用和删除确认。
3. 已完成：成功轮次结束后只从明确陈述生成“候选记忆”，由确定性规则过滤；候选功能默认关闭，敏感内容只保存类别和固定提示。列表、来源身份、采集和接受/拒绝由独立协调器编排，同一候选 ID 的并发决定被拒绝，关闭开关会取消迟到列表读取。
4. 已完成：第一版使用 Room FTS4，并为中文和任意子串保留 `LIKE` 兜底；验证更大数据集召回质量后再考虑 Embedding 和向量索引。
5. 已完成：将检索结果以有限条目注入 Agent 工具结果，并在 `ToolResult`、任务中心和 `VerifiedAgentContext` 记录本轮实际使用的 memory ID；旧事件按空列表兼容。
6. 已完成：对话输入区的 `/agent` 单次「记忆」开关；关闭后从规划器工具清单移除 `memory.search`，执行层保留二次保护并写入关闭召回审计事件，发送后自动恢复默认开启。
7. 已完成：规范化去重和同主题冲突标记，不直接覆盖旧事实；过期字段默认为空，管理页可选择永久、30 天、90 天或 1 年，启用检索排除过期项，置顶项不参与时间衰减。

### 验收标准

- 用户可以回答“你为什么记住这件事”，并跳转到来源。
- 删除或禁用的记忆不再被检索。
- 同一事实不会无限重复写入。
- API Key、token、密码、银行卡、身份证和手机号命中后不保存原值。
- 删除后立即退出检索；最近一次删除在应用重启后仍可撤销，并恢复主表、来源字段、生命周期字段和 FTS。
- 记忆检索失败不影响普通聊天和工具执行。

## 里程碑 3：Skill 与能力按需加载

目标：把可复用任务知识从系统提示词中移出，并避免工具数量增长后 Prompt 膨胀。

当前状态：已交付会话检索、本机笔记、长期记忆、设备时间和本地知识库五类内置声明式 Skill，以及版本化本地 JSON 导入、严格静态校验、Room 持久化、启停和删除管理。规则按目标稳定选择最多 3 个已启用 Skill，工具白名单只能缩小已注册工具面并写入 Run 审计；顺序多步 Runtime 可以在多个已选 Skill 的工具并集中逐步执行。

### Skill 结构

每个 Skill 至少包含：

- `id`、名称、版本和说明。
- 触发描述和示例任务。
- 依赖的工具列表。
- 所需 Android 权限和风险等级。
- 执行步骤、失败恢复和完成标准。
- 来源、校验状态和是否启用。

### 实现策略

- 内置 Skill 当前由 Kotlin 稳定定义并在启动时同步到 Room；本地 Skill 使用 `schemaVersion=1` JSON，后续如需将内置定义迁为 assets 文件必须保持同一验证契约。
- 先通过轻量分类器或规则选择 1-3 个 Skill，再只加载其指令和工具。
- Skill 不能直接获得未注册工具，也不能降低工具风险或绕过确认。
- 已完成：第一版只允许导入本地声明式 JSON Skill，不执行任意代码；未知字段、未注册工具、风险或权限不一致均拒绝导入。
- “从成功任务生成 Skill”放到后期，生成后必须经过用户审核和静态校验。

### 首批 Skill

- 会话检索与总结。
- 笔记整理。
- 每日回顾。
- Provider 健康检查。
- 失败请求诊断。

## 里程碑 4：任务与自动化

目标：让用户保存可重复任务，并能查看每次执行结果。

当前状态：已交付 `Workflow / WorkflowStepDefinition / WorkflowRun / WorkflowStep / WorkflowSchedule / ScheduledTask` Room Ledger、1 至 8 步创建/编辑、前台与后台顺序执行、步骤级输入/输出快照和幂等键、失败新 Run 重试、一次性与 Daily/Weekly 计划、结果通知和后台 blocked 审批；前台三步骤重试、定义编辑冻结历史、后台三步骤与审批恢复继续下一步骤均已通过真机。第 47 阶段加入进程内 Worker 注册表和启动恢复候选快照；第 48 阶段为 `RUNNING` 实例加入可见停止和定向兜底；第 50 阶段进一步让停止意图先持久化为 `STOP_REQUESTED`，并让 Worker 重入、启动恢复、迟到结算和周期物化共享同一取消栅栏。后台执行栈断点续跑和精确定时仍待评估，当前证据仍不需要 Foreground Service。

### 要做什么

- 已完成：`Workflow`、`WorkflowStepDefinition`、`WorkflowRun`、`WorkflowStep`、`WorkflowSchedule` 与一次性/周期 `ScheduledTask` 数据表及关联字段。
- 已完成第一版：WorkManager 负责带联网约束的一次性可延迟任务；Daily/Weekly 规则每次物化一个未来 OneTime 实例，确需准确时间时再评估 AlarmManager 和精确闹钟权限。
- 暂不引入：真实 8 步复合 SAFE 后台 Run 已在约 229.416 秒全部成功，真实运行中停止样本约 32.6 秒；进程内 Worker 所有权、可见停止与 `STOP_REQUESTED` 持久化重对账均已完成。设备虽支持 LMK 原因报告，但最新 14 条历史退出仍没有 `REASON_LOW_MEMORY`。只有超过 WorkManager 适用边界或任务对用户足够重要时，才使用 WorkManager 的 long-running worker/Foreground Service 支持。
- 已完成：每次执行保存计划/实际时间、步骤定义快照、输入/输出、重试来源、结果和失败原因。
- 已完成：步骤使用稳定幂等键；重试只复用连续成功前缀，旧 Run 保持不变，已启动失败步骤需要二次确认。
- 已完成：后台任务遇到需要用户确认的敏感操作时进入 blocked 状态，不得静默执行。

### 第一批自动化

- 每日/每周生成会话回顾。
- 定时提醒并附带上下文。
- 定时检查指定 Provider 是否可用。
- 定时整理候选记忆，结果等待用户确认。

## 里程碑 5：Android 设备 Agent

目标：在独立开关和明确权限下，完成有限、可观察、可验证的跨应用操作。

当前状态：观察与有限动作层已完成。应用开关默认关闭，健康检查区分关闭、未授权、服务断连和 READY；前台直接 `/agent` 与前台手动 Workflow 均可使用 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。打开应用、点击与输入必须在同一 Run 重新观察、逐动作 Room/overlay 审批并取得 Executor 与 typed 验证；`open_app` 的唯一包名由 SafetyPolicy、ApprovalGate 和 Executor 三层白名单限制，动作后包名还要在完成门禁和答案级 Room 重建中与获批目标一致。`back / home / swipe` 是零审批 SAFE 动作，但同样要求当前 snapshot、TTL、generation 和完整后置验证；`home` 还要求动态 launcher 匹配，`swipe` 还要求同窗内容变化和共同匿名锚点按请求方向主位移。文本输入的跨入口持久路径、Workflow 浮层、答案级输出和重试链均不保存原文。结构化快照、节点/文本预算、30 秒 ref、窗口 generation/路径/指纹失效、敏感节点脱敏、高敏窗口/隐私应用整窗拒绝、首批应用白名单、敏感输入拒绝、必要审批和动作后重新观察验证均已通过 Redmi 验收。全部后台设备工具继续关闭；Service 使用标准节点动作与系统返回/主页，不具备坐标手势或截图能力。

### 技术方案

- 使用 AccessibilityService 获取可访问节点树和执行标准动作。
- 为一次观察生成短生命周期的节点引用，页面变化后引用失效。
- 点击、输入和滚动只按短生命周期节点引用执行；当前不提供坐标兜底。
- 截图和视觉模型继续后置，不能成为当前节点校验或隐私过滤的绕过路径。
- 每个改变业务状态的动作后重新观察，不能仅凭点击成功返回判断完成。
- 增加 Accessibility 健康检查、权限失效提示和稳定态恢复。

### 第一批设备工具

- 前台直接 `/agent` 已完成：`device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。
- 前台手动 Workflow 已完成：`device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`。
- Workflow `device.swipe` 生产接线已完成。第 122/123/124/125 阶段依次冻结纯策略契约、Controller/Registry 执行期 HMAC evidence、完成态纯内存交接、Redmi 限定滚动，以及答案级脱敏 Decision、Room/Workflow output 与 UI 投影；第 126 阶段把它加入生产默认集合，并仅用 Redmi 完成真实生产 Workflow `snapshot -> swipe`，得到 `approvals=0 / registryCompletion=PASSED / answerDecision=VERIFIED / privacySafe=true`。同窗内容变化与共同匿名锚点方向主位移继续替代 generation-only 验证，后台/定时设备自动化与任意 App 仍不开放。第 121 阶段 `open_app` 以聚焦 JVM `95/95`、Debug/AndroidTest APK、Redmi Compose/Room 单项、真实逐包审批 tracer 和文档 corpus 首轮 `OK (1 test)` 完成生产闭环；冻结 corpus 复验同样通过。

### 安全边界

- 默认关闭，需要单独启用设备 Agent。
- 支付、下单、删除、发送、发布、授权和系统设置修改必须再次确认。
- 密码框、验证码、支付页面和隐私应用默认不读取或记录内容。
- 工具结果按隐私级别控制落盘，release 日志不保存原始敏感内容；当前不采集截图。
- 第一阶段只支持少量已验证应用和流程，不承诺任意 App 通用自动化。

## 里程碑 6：高级能力

以下能力在前述基础稳定后再进入：

- 文件附件、图片、富文档直传和 `/agent` Responses 附件输入已完成；语音输入与 TTS 仍待实现。
- 文档解析、知识库管理、RAG 检索、Agent 接入、答案级引用 UI、Embedding v1、显式索引重建和多 Provider/模型空间共存已完成；规模化 ANN、自动后台批量重建与召回质量验证仍待实现。
- MCP Client 与远程工具，但必须增加 Server 信任、工具审核和网络权限策略。
- 通知摘要、日历、联系人和系统分享入口。
- 多 Agent 分工、远程 Channel、跨设备同步。
- 手机端本地模型和模型下载管理。

## 横向工程任务

这些任务不属于单个功能，但必须贯穿所有里程碑：

- 已建立 Room Schema 导出、migration 测试，以及面向用户的数据库 ZIP 备份与恢复工具。
- 已建立脱敏网络/运行日志和稳定 `runId / stepId / toolCallId` 审计身份；新增设备或远程工具时继续沿用该边界。
- 已为 Agent Runtime 提供假的 LLM 和 Tool Executor，覆盖确定性状态机、取消、超时、预算和恢复测试。
- 已建立工具契约测试，持续校验 Schema、风险、权限、确认、后台能力和验证信息不能缺失。
- 已完成当前可审计性能指标：任务中心展示 Run 总耗时、终态成功率、平均耗时、模型/工具/审批次数、模型总耗时、平均 TTFB、最终 JSON Prompt 字节、上游 Token usage 覆盖率和失败终态分布；未返回 usage 的请求不补零，Prompt 正文不重复落库。
- 对低能力模型做回归，减少多阶段 LLM 调用和超长工具提示词。
- 已完成当前故障注入基线：用户取消、模型/工具/整次 Run 超时、网络响应中断、Workflow 重复回调、执行/验证中进程终止，以及审批期间和工具执行期间 Android 权限撤销均有确定性测试；`tool.verify` 落库后和验证 Step 完成后两个终止点均确认恢复不重复工具或验证事实；真机外部 `pm revoke` 同时确认系统会直接终止应用进程。启动恢复候选快照还覆盖快照期间新 Worker 等待，以及旧链收敛、当前进程链保持并完成且不复制 Run。停止故障注入覆盖 WorkManager 与即时 fallback 同时失败、当前进程所有权仍登记、迟到成功结算和周期下一实例门禁，均以持久化 `STOP_REQUESTED` 收敛。Redmi 另有强制 Doze、trim-memory 和无压力对照，但这些受控命令不冒充自然 LMK 或连接失败因果证据。
- 每个涉及 Android 系统能力的里程碑都必须在真机验证，不以单元测试替代。

## 优先级清单

| 优先级 | 工作项 | 当前状态 | 原因 |
|---|---|---|---|
| P0 | 请求取消、结构化 Responses 输入、Provider Adapter | 已完成，包括用户 Image/Document、函数调用与结果 typed Items、可选 Reasoning summary | 后续 Agent 循环的基础协议 |
| P0 | Room、Repository、迁移测试和导出 | Room/Repository、普通聊天上下文 Preparer、发送 Coordinator、会话状态 Policy 与保存 Coordinator、Schema 导出、v4→v32、event metadata、Memory/Knowledge FTS、Embedding/相关性 shadow 审计、Tool Ledger、Agent Profile、MessagePart、知识引用、进程退出观察和用户 ZIP 备份/恢复已完成 | 保证升级和本地数据可恢复 |
| P0 | AgentRun 状态机、事件日志、取消与恢复 | 最小状态机、事件、取消、安全重新运行、进程终止、运行中撤权、多步骤审批等待恢复、两个白名单写工具受限验证、全部工具 `PASSED` 后的本地收尾，以及严格失败 ToolResult/typed 失败验证的两类原子失败终态结算已完成；提交状态未知、成功结果尚无验证结论和其他证据不完整形状仍 fail-closed | 决定任务是否可靠、可观察 |
| P0 | Tool Registry、Schema、风险、确认和验证 | 已完成完整类型/约束/枚举、业务校验器、风险/确认、Android 权限、前后台来源门禁、超时、回读验证策略和重复名称启动校验 | 决定执行边界和安全性 |
| P1 | 应用内低风险工具和任务时间线 UI | 第一批工具、对话时间线、任务中心、完整工具结果、失败重试及 Run/历史运行指标已完成 | 已形成第一条端到端 Agent 链路 |
| P1 | 长期记忆管理与 FTS 检索 | 管理 UI、FTS、中文兜底、来源审计、候选确认、敏感过滤、去重/冲突、跨进程删除撤销、引用 ID 审计、单次召回关闭、过期策略和时间衰减已完成 | 形成个人化和跨会话连续性 |
| P1 | Skill 按需加载 | 内置与本地声明式 Skill、版本化 JSON、严格导入校验、Room Catalog、规则选择、工具白名单、启停/删除管理和 Run 审计已完成 | 控制 Prompt 和工具面增长 |
| P1 | Agent Profile v1 | 多 Profile 管理、固定 Provider/模型/协议、角色提示、上下文策略、工具/Skill 白名单、记忆硬边界和 Run 快照恢复已完成 | 把 Agent 身份与普通聊天配置分离 |
| P1 | 结构化消息 parts | Text/Reasoning/Image/Document/Tool 持久化、旧 text 回填、供应商摘要折叠展示、可信 Tool 投影、用户附件选择/预览/请求/备份和 Compose 展示已完成 | 让聊天内容、用户附件、供应商摘要与工具执行事实进入同一可恢复消息模型 |
| P1 | Workflow Ledger 与后台调度 | 多步骤定义/编辑、前后台顺序执行、步骤快照、新 Run 重试、一次性与 Daily/Weekly WorkManager、SAFE/blocked/通知和规则替换/停用已完成；进程内 Worker 所有权、启动恢复隔离、运行中可见停止、`STOP_REQUESTED` 持久化栅栏和 Workflow/Task 原子结算已完成，执行中断仍按 fail-closed 收敛；已有 229.416 秒八步复合只读成功与 32.6 秒停止样本，仍缺自然 LMK，Foreground Service 暂无引入依据 | 支持持续任务且可追溯 |
| P2 | Accessibility 设备工具 | 观察、有限动作、审批、操作后验证和少量指定 App Redmi E2E 已完成；前台手动 Workflow 精确开放 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`，后台与任意 App 继续关闭 | 扩展到真正移动端执行，风险较高 |
| P2 | 附件、视觉、语音和 RAG | 单张用户 Image、PDF/UTF-8 Document 与 DOCX/PPTX/XLSX 直传、`/agent` Responses 附件输入，以及 RAG 数据、管理 UI、`knowledge.search`、引用审计、模型上下文投影、答案引用 UI、Embedding v1、显式索引重建、相关性扩样校准、answerability shadow 协调、生产 adapter、保存后 caller、设置开关、进程内 notice、Room 匿名 Shadow Store、强类型离线评测契约和首个 v33 间隔真实样本已完成；notice 跨进程恢复、JSON/SAF 出口、生产拒绝、语音、ANN 与自动后台批量重建未完成 | 提升输入输出能力 |
| P3 | MCP、远程 Channel、多 Agent、本地模型 | 暂缓 | 生态价值高，但复杂度和攻击面更大 |

## 明确不照搬的做法

- 不照搬多阶段 Analyze/Reflect/Plan/Review 全部依赖 LLM 的重型流程；先用单循环和确定性状态机。
- 不把所有工具 Schema、数据库结构和 Skill 全量注入每次请求。
- 不允许模型决定工具风险或确认策略。
- 不把“工具返回 success”直接等同于任务完成。
- 不以任意 Shell 作为移动 Agent 的通用工具。
- 不在缺少 Run Ledger、取消和恢复前上线后台自动化。
- 不在缺少权限隔离和工具审核前开放 Skill 市场或 MCP Server 任意接入。

## 已确认的后续执行顺序

基于 `v0.1.12` 之后的已验证提交，后续主线固定为以下顺序：

1. 已完成：发布 `v0.1.13`，把验证报告归档、主要 UI 垂直模块、单一系统启动画面、固定设置标题、R8 和 Baseline/Startup Profile 形成可回退的稳定基线。
2. 已完成：发布后有停止条件的对话框簇收尾。只迁移 Agent/Workflow 重试、长期记忆编辑/删除和本地 Skill 删除；`SettingsPage`、备份恢复、全局通知与 Android launcher 继续留在 composition root。`XiaoLingApp.kt` 收敛到 `817` 行后停止结构拆分，不再以压低宿主或 ViewModel 行数为目标。
3. 已完成：通用执行恢复矩阵首个“提交状态未知”切片。真实执行步骤缺结果稳定冻结为 `COMMIT_UNKNOWN`；proposed-only 和执行步骤尚未落库保持 `NOT_COMMITTED / RECOVERY_EVIDENCE_INVALID`，旧 Run 不重放、不续跑。
4. 已完成：“尚未提交”的安全重放资格。只有副作用边界明确未进入、原请求可重建、当前/历史工具恢复契约一致且用户审批语义不漂移时，才冻结 `NOT_COMMITTED_REPLAY_ELIGIBLE`；旧 Run 仍取消，不执行重放。
5. 已完成：把安全重放资格接入证据漂移复核和用户控制的关联新 Run；确认、创建和执行前分别重新核验，旧 Run 不变，新 Run 使用新 ToolCall 与新审批且只执行一次。
6. 已完成：统一复核“已提交结果只读验证”和“全部已验证只补控制面”两格。marker、状态与启动关闭实现事务收敛，Step/Event 使用 typed 身份，恢复入口重新读取 Room，总结尾部并发幂等；旧 LLM、Executor 和 Workflow 后续步骤仍不恢复。
7. 已完成：持久化失败 ToolResult 的原子失败终态结算。只接受 v20 完整 Ledger、完整成功验证前缀、唯一失败链尾、结果后完整预算、最后运行中执行 Step 和无业务尾部；原 Step/Run 只结算为 `FAILED`，不重放、不验证、不总结、不继续 Workflow。双轴审查补齐 Step sequence 与 typed 事件身份核验；完整门禁为 `141/141` tasks、JVM `726/726`、Lint、三类 APK、Release lintVital 和仅 Redmi `OK (240 tests)`。
8. 已完成：持久化失败工具验证的原子失败终态结算。只接受成功 ToolResult、typed `tool.verify=FAILED(reason)`、结果后完整预算、完整 v20 Ledger/Step/Event 身份与最后运行中验证 Step；原 Step/Run 只结算为 `FAILED`，不重复 Executor/验证器/LLM，不总结、不继续 Workflow。双轴审查补齐预算 `Available` 硬门槛并收紧异常捕获；完整门禁为 `141/141` tasks、JVM `732/732`、Lint、三类 APK、Release lintVital 和仅 Redmi `OK (243 tests)`。当前文档 corpus 首轮/中间复验为 `OK (1 test)`（`2.907s / 2.471s`），最终文本 gate 也已通过；正式 `v0.1.13` 已恢复并完成版本、前台、PID、测试包、保持唤醒与 crash 收尾。
9. 已完成：通用恢复矩阵剩余持久化窗口闭环审计。成功结果缺 typed 验证结论时按工具定义、已提交幂等回执、只读恢复验证支持依次判定，分别稳定落入 `TOOL_DEFINITION_UNAVAILABLE / COMMITTED_EFFECT_EVIDENCE_INVALID / COMMITTED_VERIFICATION_UNAVAILABLE`；没有唯一安全动作的形状继续关联新 Run 或 fail-closed，不扩大原地恢复能力。完整门禁为 `141/141` tasks、JVM `734/734`、Lint、三类 APK、Release lintVital 和仅 Redmi `OK (243 tests)`。
10. 已完成：将主线切回个人 Agent，并为前台手动 Workflow 开放只读 `device.snapshot`。工具清单和执行层保持双重门禁，审批恢复保留原调用来源；Redmi 真实 Workflow 只产生一条通过验证的 snapshot，没有设备动作或审批。
11. 已完成：前台 Workflow 已能把 `device.snapshot` 形成可复核证据与版本化本地判定，并在独立后续 Agent Run 中只消费该判定；Workflow 输出、前序输入和后台会话均已排除原始节点、ref 与模型转述。
12. 已完成：前台 Workflow 有限设备动作安全契约、`device.tap_ref` 首个生产切片、答案级动作证据 UI、`type_text` 专属安全契约、evidence seam 与生产闭环。第 117 阶段完成时生产面精确允许同 Run `device.snapshot / device.tap_ref / device.type_text`；文本动作已具备当前 ref 的可编辑/未脱敏目标证据、最小指纹授权、绑定原 `nodePath` 的精确回读、Room 独立审批、脱敏 Accessibility overlay、无原文答案级判定/UI 和 Redmi 真实 Workflow 验收。
13. 已完成：统一前台直接 `/agent` 与 Workflow 的 `type_text` 持久化隐私模型。所有持久路径只保存 snapshot/ref、文本 SHA-256 与长度，当前进程审批卡仍可显示原文并强绑定 Room 安全投影；重启后的旧文本审批以 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE` 安全取消。
14. 已完成：接入前台 Workflow `device.back`。它为空参数、零审批 SAFE 动作，但继续要求用户意图、当前 snapshot/TTL/generation、Executor/typed 验证和动作后观察；Redmi 已完成 `approvals=0 / verified=true / VERIFIED` 真实闭环。
15. 已完成：接入前台 Workflow `device.home`。它复用空参数、零审批 SAFE 边界，并以系统动态解析的 launcher 完成后置验证；Redmi 已完成 `approvals=0 / verified=true / VERIFIED` 真实闭环。
16. 已完成：接入前台 Workflow `device.open_app`。它只接受逐包审批的唯一白名单包名，并在动作完成与答案级 Room 重建时再次绑定后置包名；Redmi 已完成 `APPROVED / PASSED / afterPackage=com.android.calculator2 / VERIFIED` 真实闭环。
17. 已完成：第 122 至 126 阶段依次冻结 `device.swipe` 安全契约、执行期 HMAC evidence、完成态内存交接、Redmi 限定验收、答案级脱敏投影和生产默认接线；前台 Workflow 七项设备工具闭环完成，后台设备工具继续关闭。
18. 已完成：第 127 阶段新增自然语言个人任务入口，把用户目标转换为 1 至 8 步严格临时计划，展示计划、风险和能力边界，并要求用户确认后才原子创建普通 Workflow/Run、进入既有 Workflow/Agent 执行链；没有建立第二套 Runtime。Redmi 真实模型计划和同一 Workflow 下失败旧 Run + 独立成功新 Run 已验证。
19. 已完成：第 128 阶段在限定 App 范围完成多动作连续执行。每次页面变化都重新 snapshot/ref，逐动作复用既有审批、TTL、generation、Executor/typed 验证和动作后观察；Redmi 跑通首条设置页连续任务。
20. 已完成：第 129 阶段新增目标级本地验证和最终回答约束。只用同 Run 已验证 Tool Ledger、步骤快照和最终观察形成 `VERIFIED / PARTIAL / INCOMPLETE`；Room v35 保持旧任务无判定，模型自由文本不能扩大结论。
21. 已完成：第 130 阶段把长期记忆、本地知识和应用内提醒接入个人任务。计划上下文受 Profile/单次开关和有界只读 Prompt 约束；提醒复用现有 WorkManager 非精确定时与通知能力，创建确认与既有修改/取消用户操作保持显式，不引入系统日历或精确闹钟。
22. 已完成第 131 阶段：任务级恢复与关联重试。中断后从已验证前缀创建关联新执行，旧 Run、旧步骤和已提交副作用保持不变；不恢复无法证明的旧模型协程或 Executor。
23. 已完成第 132 阶段：三条 Redmi 完整任务、完整 JVM `879/879`、Lint、Debug/AndroidTest APK 与默认 instrumentation `282/282` 通过；修正 Room v35 常量、规则夹具、知识引用测试选择器及 AOSP/Google 计算器和时钟包名兼容。正式 Release 未执行。
24. 已完成第 133 阶段：计划生成/任务创建/提醒创建专属状态、失败保留目标与重新生成、通知权限等待防重复，以及确认后创建请求的会话代际隔离。
25. 并行低频观察：answerability Shadow 等待真正跨日或长期分隔的真实窗口；样本足够后再评估 JSON/SAF、显式授权离线评测集、独立阈值校准和生产拒绝。该等待不阻塞个人 Agent 功能开发。
26. 已完成第 134 至 137 阶段：计划请求遥测、常用模板、Google 天气兼容，以及 8 KiB 上下文预算、跨来源去重和用户可见省略结果。
27. 已完成第 138 阶段：计划生成主动取消恢复原目标并提供重新生成，旧会话取消不污染新会话。
28. 已完成第 139 阶段：确认后立即任务和提醒创建在不可取消提交边界内捕获持久化身份，提交交接处取消会收敛同一 Run/调度而不是误判为空，避免重复重试创建。
29. 已完成第 140 阶段：已提交任务停止/失败只显示“查看任务”并打开现有工作流页；未提交失败才允许重新生成，避免从失败入口重复创建 Workflow。
30. 已完成第 141 阶段：立即任务结束后把 Repository 持久化的 `VERIFIED / PARTIAL / INCOMPLETE` 目标级结论或普通完成状态投影为可见入口；提醒创建成功也可进入既有工作流页。入口不会重发、重建或自动重试，编辑输入、切换模式和下一次计划会清除旧结果。
31. 已完成第 142 至 143 阶段：完成卡携带 Workflow ID 定向打开并展开任务详情，Activity 重建继续保存一次性 Workflow/知识文档目标，返回设置根页后清理。
32. 已完成第 144 阶段：新增 SAFE `tasks.list` 与 `task-overview` Skill，只读返回任务/提醒摘要；第 146 阶段补充真实 `gpt-5.6-luna` Agent Run，ToolResult 为 `PASSED`，既有 Profile 不静默扩权。
33. 已完成第 145 阶段：显式 AOSP/Google 计算器与时钟应用族兼容，“返回小灵”步骤只开放 `device.snapshot -> device.back`，Redmi 三步任务完成真实目标级验证。
34. 已完成第 146 阶段：多级关联重试沿 `reusedFromStepId` 回查原始 Tool Ledger；全部步骤成功但 Run 目标收敛失败时，允许创建只复用步骤并重新判定的关联新 Run，不重放模型、审批或设备动作。Redmi 新 Run `workflow-run-3e4b422e-d48e-4244-b21a-2668a980fe10` 三步全部复用并得到 `VERIFIED`，来源失败 Run 保持不变。
35. 下一候选第 147 阶段只做后台长任务可靠性证据评估，不预先引入 Foreground Service：使用现有无需审批的 SAFE Workflow 在 Redmi 形成至少 5 至 10 分钟的前台、切后台和熄屏样本，记录 WorkManager 计划/实际时间、Run/Step/Tool Ledger、进程退出原因和系统配额。后台设备动作继续关闭；人工 `force-stop`、安装、instrumentation 或 `kill -9` 不计作自然系统回收证据。只有真实任务时长、用户可见进度或系统回收数据证明现有机制不足，才立项 Foreground Service 或更细恢复能力。
36. MCP、系统日历/通知写入、远程 Channel、多 Agent、跨设备同步和本地模型保持在后台长任务评估之后；截图/视觉、任意 App、坐标与后台设备控制继续单独立项。没有明确用户场景时不连续堆模板/App，不回到持续按行数拆分或单纯 Shadow 扩样。

本顺序替代此前“持续按行数拆分 ViewModel/Compose 宿主”的开放式结构路线。结构工程只处理已经识别且能形成深边界的模块；进入通用恢复后，除非结构改动直接支撑恢复契约或消除明确风险，否则不再单独立项瘦身。

## 已完成阶段索引

1. 已完成：`WAITING_APPROVAL` 可在任意已验证前缀后恢复原 Run。恢复要求唯一待审批请求与链尾 ToolCall 完全匹配，前序结果全部成功并 `PASSED`，步骤、Ledger 与 typed event 一致；Runtime 重建可信前缀、工具调用预算和循环指纹，不重放前序工具。磁盘 Room 关闭重开与 Redmi 124 条完整 instrumentation 已通过。
2. 已完成跨进程删除撤销；后续后台任务必须复用原子快照与 Room 状态核对边界。
3. 已完成本地 Skill 文件格式、导入校验与启停/管理 UI。
4. 已完成：不依赖调度器的 `Workflow / WorkflowRun / WorkflowStep` Ledger 和前台手动执行闭环。
5. 已完成结构化 `ScheduledTask`、WorkManager 一次性非精确调度、计划/实际时间、结果通知和后台 blocked 审批。
6. 已完成：真机一次性 SAFE/blocked、完成/失败/blocked 通知，以及触发前进程回收后的 WorkManager 冷启动执行验证。
7. 已完成 Daily/Weekly 周期规则：每次触发创建独立 ScheduledTask/Workflow Run，规则替换和停用同步取消 WorkManager，周期触发不复用前台审批等待。
8. 已完成多步骤 Workflow 定义、编辑、步骤级幂等键、输入/输出快照和安全新 Run 重试；后台中断继续收敛旧 Run，不在没有副作用证明时原地续跑。
9. 已完成多步骤前台/后台真实模型真机验收：编辑只影响未来定义，审批后继续下一步骤，失败来源 Run 保持不变，新 Run 正确关联来源并重新执行未完成步骤。
10. 已完成第一批 Run 性能指标和故障注入：任务中心展示总耗时、终态成功率、平均耗时、模型/工具/审批次数；网络响应中断归类为连接失败，取消、超时和重复回调测试保持通过。
11. 已完成请求级审计：规划/总结成功后写入 usage、TTFB、最终 JSON Prompt 字节；规划语义解析失败仍保留已返回遥测；任务中心展示 Token 覆盖率和失败终态分布。
12. 已完成执行/验证中进程终止和 Android 权限运行中撤销故障注入：审批后执行前和工具返回后验证前都会复检权限；进程重建默认把旧 Run 与活动 Step 一致取消，只有显式白名单工具的完整历史证据可以进入受限恢复。
13. 已完成：建立持久化 `ToolExecutionReceipt`、执行时 `ToolReplaySafety` 声明快照和纯证据判定 module；回执绑定 ToolCall，错配时 Runtime fail-closed，旧事件默认不可重放，当前定义升级不能放宽历史证据，任务中心不显示原始幂等键。
14. 已完成：`notes.create` 使用 ToolCall ID 作为可审计的存储层唯一幂等键，同键同载荷在数据库重开后仍返回同一 operation ID，同键载荷漂移被拒绝；工具已声明 `IDEMPOTENT_BY_KEY`。Room v17 迁移保留旧笔记并把其幂等键留空，Pixel_9 与 Redmi 各 42 条 instrumentation 通过。
15. 已完成：仅针对具有完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据的 `notes.create` 开放“验证阶段恢复”。从持久化 ToolResult 唯一还原 ToolCall，按 operation ID 回读原笔记，补齐 `tool.verify` 和本地 `recovery.summarize`；多工具 Run 会从历史验证事件重建成功前缀，Workflow 启动对账会保留候选直到当前步骤输出落库。确定性进程中断、Room 重建和真实 Registry 不重复写入均已覆盖。旧模型协程、其他工具执行栈和 Workflow 后续步骤仍不恢复。
16. 已完成：`memory.remember` 使用独立 Room operation ledger，以 ToolCall ID 主键绑定原始载荷哈希和 memory ID；数据库重开后同键同载荷复用原 operation，载荷漂移被拒绝。工具已声明 `IDEMPOTENT_BY_KEY`。
17. 已完成：Room v19 为记忆 operation 增加提交结果业务快照哈希，Registry 从持久化 Run Context 重建原请求并开放 `verifyCommittedEffect()`。未修改、启用、未过期的记忆验证成功；内容、标签、类型、来源或置信度编辑返回 `MEMORY_CHANGED`，禁用返回 `MEMORY_DISABLED`，过期返回 `MEMORY_EXPIRED`，删除返回 `MEMORY_NOT_FOUND`，删除后按原快照撤销恢复可再次成功。置顶、引用时间和未来过期时间不影响验证；v18 历史 operation 因缺少结果快照保持 `EVIDENCE_INCOMPLETE`。冷启动恢复不再次调用 `remember()`，原 operation ID 和回执保持不变。
18. 已完成：`memory.remember` 的八类只读恢复失败通过 `run.recovery_failed` typed event 保存稳定错误码、原因和建议动作；任务中心详情顶部直接显示恢复处理状态带，事件区保留完整字段。所有建议都要求创建新 Run，旧 Run 保持 `FAILED`。生产 Registry 当前只有 `notes.create` 与 `memory.remember` 两个写工具，不为套用模式虚构第三个写工具；通用执行栈、旧模型协程和 Workflow 后续步骤继续 fail-closed。
19. 已完成：Room v20 新增 `agent_tool_calls / agent_tool_results`，`appendEvent()` 在同一事务内按 typed metadata 双写参数、proposed/validated 锚点、结果、显式错误、耗时、Executor/最终验证、记忆引用、重放声明和执行回执。ToolCall 身份或参数漂移整笔回滚；Repository 重建后可按 Run 查询。v19 旧 Run 保留 event-only，不补造关联，验证阶段恢复仍可追加事件；恢复策略未切换到新表。
20. 已完成：任务中心对 v20 新 Run 使用 Tool Ledger-first 明细，Repository 批量加载调用/结果，UI 以调用为单位展示 proposed→validated→result→verified 状态；没有 Ledger 但存在 typed 工具事件的旧 Run 自动回退。缺少 ToolCall ID 的旧结果/验证显示“关联未知”，不伪造调用关联；账本与事件的身份、字段、锚点或孤立记录异常显示一致性告警。`AgentRunResumePolicy`、重试和指标继续读取 RunEvent，恢复证据切换留待独立阶段。
21. 已完成：`AgentRunRecoveryEvidencePolicy` 将 `notes.create / memory.remember` 的受限验证恢复切换为 Ledger-first。v20 非空账本要求每个调用恰好一个结果，按 proposed 锚点重建顺序，并核对 proposed→validated→result→verified 的身份、字段、派生错误、时间和事件顺序；部分账本、重复身份、额外事件或双源漂移均 fail-closed。账本完全为空的旧 Run 才回退 typed event，缺少 ToolCall ID 的历史验证返回关联未知并升级为 `EVIDENCE_INCOMPLETE`，不按结果顺序猜配。多步骤只重建已验证前缀并恢复最后一个尚无验证终态的已提交结果；白名单、旧模型协程、通用执行栈和 Workflow 后续步骤边界不变。
22. 已完成：失败 Run 的重试副作用判断改为 Ledger-first，并复用 `AgentToolLedgerConsistencyPolicy` 的完整链路检查。非 SAFE 调用只要结果成功，或回执为 `COMMITTED / UNKNOWN`，就要求二次确认；异常账本同样 fail-safe，明确 `NOT_COMMITTED` 的失败结果和仅 validated 尚未执行的调用不额外抬高门禁。账本全空的旧 Run 保留 typed event 回退，旧 Run 本身仍不修改。Run 质量和模型遥测继续使用 Step/LLM typed event，因为 Tool Ledger 没有等价耗时、TTFB、Prompt 与 usage 字段，不为追求形式统一而改变统计口径。
23. 已完成：Agent Profile v1 使用 Room v21 `agent_profiles` 保存名称、标识、Provider/模型、API 模式、系统提示词、当前会话上下文策略、工具/Skill 白名单和记忆开关。新 Run 写入唯一 `agent.profile.selected` 快照；Profile Registry 是工具执行硬边界，Skill 只能继续缩小。审批恢复和已提交结果恢复固定原 Run 快照，重复、损坏或越权审计 fail-closed；前台/后台 Workflow 单次执行固定同一 Profile。设置页已完成新增、编辑、选择、删除和 Provider 绑定保护，真实 `Time Agent + gpt-5.5 + Responses + app.current_time` 已在 Redmi 完成端到端验收。
24. 已完成：Room v22 新增 `message_parts`，Text/Tool 使用稳定 ID 与 sequence。v21→v22 只回填旧 Text；新 Agent 结果依据 `AGENT_RESULT + VerifiedAgentContext` 生成 Tool，普通聊天无法伪造。`MessageRepository` 统一前后台原子写入，前台快照增量 upsert 且只按显式 ID 删除会话，Compose 同气泡展示 Text 与 Tool 证据。242 条 JVM、仅 Redmi 执行的 73 条 instrumentation 和真实 `gpt-5.5 + app.current_time` Run 均通过；交错写测试确认旧前台快照不会删除后台追加消息或新建会话，数据库确认最新消息为 sequence 0 Text + sequence 1 Tool。
25. 已完成：Room v23 为 Reasoning part 增加来源、供应商 item ID 和 summary index。普通对话仅在 Responses 模式且用户显式开启时发送 `reasoning.summary=auto`；非流式和 SSE 流式只接收供应商 `summary_text`，按来源身份去重并固定在 Text 前，Compose 默认折叠并标注“供应商提供”。原始 `reasoning_text`、Chat Completions 非标准 `reasoning_content` 和 Agent 结果中的 Reasoning 均不能进入正文、parts 或 `VerifiedAgentContext`；debug 响应与 SSE 日志也做结构化脱敏，无法解析的可疑内容失败关闭。256 条 JVM、仅 Redmi 执行的 77 条 instrumentation 均通过；`gpt-5.5` 非流式和流式真实请求都返回 Reasoning summary，Room 回查均为 sequence 0 Reasoning + sequence 1 Text，应用最终保持 Redmi 前台且 crash buffer 为空。
26. 已完成：Room v24 为 Image part 增加 MIME、文件名、BLOB 和 detail。系统选择器单次接收 PNG/JPEG/WEBP，读取上限 8 MB，并核对声明大小、MIME、文件签名和可解码性；进入消息后不再依赖 URI。Responses 把近期 USER Image 映射为 `input_image` Data URL，Chat Completions 在发送前明确拒绝，`/agent` 仅在 Responses 规划请求接收；Agent 信任策略只允许 USER 保留 Image，不能提升为 Tool 或 `VerifiedAgentContext`。Compose 支持待发送缩略图、移除和历史图片，debug 日志脱敏图片 Base64、`file_data`、生成图片结果与 `encrypted_content`。图片 BLOB 按当前会话加载，轻量快照保留未加载 BLOB；发送前等待 Room 事务，切换会话原子更新，显式删除过滤阻止陈旧快照复活。267 条 JVM、仅 Redmi 执行的 85 条 instrumentation 均通过；真实 `gpt-5.5` 图片轮次返回 `IMAGE_OK`，Room v24 回读确认 PNG BLOB 持久化。
27. 已完成：Room v25 增加 Document part 的提取文本、PDF 页数和 detail，并复用附件 MIME、文件名与 BLOB。Document v1 单次接收 PDF、TXT、Markdown、JSON、CSV，最大 8 MB；PDF 签名与扩展名在领域策略交叉校验，DocumentsProvider 错报 MIME 也不能绕过 `PdfRenderer` 和最多 50 页预算，文本严格使用 UTF-8 并限制 200,000 字符。原始文件与受限提取文本同事务保存，附件 BLOB 按当前会话加载，轻量快照保留未加载 Image/Document。Responses 映射为 `input_file` Data URL，PDF 使用 `detail=auto`；Chat Completions 明确拒绝，`/agent` 仅在 Responses 规划请求接收，USER-only 信任边界不变。Compose 附件菜单、待发送元数据/移除和历史 Document 展示已完成。281 条 JVM、仅 Redmi 执行的 92 条 instrumentation 均通过；真实 `gpt-5.5` Markdown 轮次在 4.33 秒返回 `DOC_STAGE27_OK`，Room v25 回读确认 67 字节 BLOB 与 67 字符提取文本持久化。
28. 已完成：Document part 在不升级 Room 的前提下扩展 DOCX、PPTX、XLSX。`OpenXmlDocumentPolicy` 解析 ZIP 中央目录并逐条核对 local header、文件名、加密位、磁盘号、ZIP64 extra 与实际数据范围，再以固定缓冲区流式核对条目集合、CRC 和真实展开量；加密、分卷、ZIP64、超过 4,096 条目、声明或实际展开总量超过 64 MB、扩展名/MIME/结构不一致均在进入消息前拒绝。系统选择器、Room BLOB、轻量快照、Responses `input_file`、Compose 元数据和 USER-only 信任边界继续复用第 27 阶段契约。284 条 JVM、仅 Redmi 执行的 93 条正式 instrumentation 均通过；一次性真机 E2E 使用设备现有 `gpt-5.5 + Responses` 在 4800 ms 返回 `RICH_DOC_STAGE28_OK`，日志确认 DOCX `file_data`、Authorization 与加密推理内容均脱敏。
29. 已完成：Room v26 新增知识文档、chunks、FTS4 和检索审计。严格 UTF-8 导入规范换行、拒绝空白/NUL，并按规范全文计算 SHA-256；确定性分块优先段落边界、保留有限重叠和精确 offset，不切断 UTF-16 代理对。替换在同一事务递增 revision 并全量更新 chunks/FTS，失败注入确认整笔回滚；禁用/删除立即退出检索，旧 chunk ID 随 revision 失效。该阶段 291 条 JVM、仅 Redmi 执行的 98 条 instrumentation 均通过；Redmi 主库升级为 v26，原 Provider 保留。该阶段当时尚未接入管理 UI、Agent 工具和模型引用注入，后续第 30、31 阶段已经补齐。
30. 已完成：设置页新增知识库管理 UI，使用 SAF 有界读取、轻量摘要 projection 和最多 4,000 个 UTF-16 单元且不切断代理对的详情预览，支持导入、列表、详情、启停、替换、删除和带 retrieval ID/chunk offset 的检索预览。独立 ViewModel 串行化变更、取消旧详情/刷新/检索、变更开始即隐藏失效详情，并区分存储提交失败与提交后的刷新失败。291 条 JVM、仅 Redmi 执行的 106 条 instrumentation 均通过；真实 UI 验证 revision 1→2、停用/删除 0 命中、旧词失效、新词命中和 r1/r2 审计引用分离，最终主库知识表清空且 Provider 保留。

31. 已完成：新增 SAFE、后台可用的 `knowledge.search` 和内置 `local-knowledge` Skill。`query` 必填 1 至 200 字符，`limit` 默认 3、最大 5；ToolResult、RunEvent、独立 Tool Ledger、VerifiedAgentContext、MessagePart、规划历史、Workflow 输出和任务中心统一保存稳定 retrieval/document/revision/chunk/offset 引用。Room v27 为 ToolResult/MessagePart 增加默认空引用列，不猜造旧证据。失效引用会让整条历史知识消息、可能污染的旧摘要和 Workflow 前序正文退出新模型请求，历史审计不回写；旧 Profile 不自动扩权，无 Profile 审计的历史 Run 固定在知识工具上线前的工具集合。309 条 JVM、仅 Redmi 执行的 113 条 instrumentation 通过；五份真实项目长期文档 corpus 的多词重排查询、top-1 和负例门禁全部通过。真实 `Time Agent + gpt-5.5` 已选择 `knowledge.search`，Run、Ledger、retrieval 和 MessagePart 引用一致。

32. 已完成：Agent 回复新增独立、默认折叠的答案引用区域，只从可信 `MessagePart.Tool`/`VerifiedAgentContext` 投影，不解析模型自由文本。展开后展示文档名、revision、chunk 和半开 offset 区间；Room 使用文档摘要与引用 chunk 的 projection 判定“当前有效 / 历史版本 / 当前不可用”，按最多 900 个 SQLite 参数分批核验，取消旧 Job 不回写失败状态，停用状态优先于历史 revision。文档仍存在时可跳转知识库详情，删除后关闭跳转。320 条 JVM、仅 Redmi 执行的 118 条 instrumentation 均通过；真实 `MainActivity` E2E 覆盖当前引用跳转、替换后的历史标记、跳转当前 revision，以及删除后的不可用状态和清理。Embedding 继续后置。

33. 已完成：设备 Agent 只读观察层。新增默认关闭的独立开关、系统 Accessibility 入口、四态健康检查、只读预览、`device.snapshot` 和内置 `device-observation` Skill。快照最多 200 个节点/4000 字符，文本不切断 UTF-16 代理对；可操作非敏感节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、路径和指纹，任一失败或页面变化立即撤销。敏感字段脱敏，高敏窗口与隐私应用整窗拒绝；Service 不具备手势或截图能力。工具仅在前台直接 `/agent` 暴露，Workflow、后台和关闭状态双层拒绝，旧 Profile/Skill 不自动扩权。337 条 JVM、仅 Redmi 执行的 122 条 instrumentation 均通过；真实 Service 在 instrumentation 外验证主界面 `nodes=27 / refs=8`、敏感探针 `redacted=2 / refs=1`、支付探针 `SENSITIVE_WINDOW`，最终独立开关关闭、系统服务绑定、主界面前台且 crash buffer 为空。

34. 已完成：前台直接 `/agent` 的设备 Agent 有限动作层。新增 `device.open_app / back / home / tap_ref / type_text / swipe` 和 `device-control` Skill；打开应用、点击和输入要求审批，返回、主页和节点滚动为 SAFE。应用白名单只含小灵、系统计算器、时钟和系统设置；输入在审计前拒绝敏感值。节点动作再次核对 snapshot/ref/generation/path/fingerprint，动作后重新 capture 并按包名、桌面、回读文本或 generation 变化验证；首次启动权限页的瞬时空窗口通过只针对窗口过渡的 6×100 ms 有界重试收敛。348 条 JVM、仅 Redmi 执行的 123 条 instrumentation 均通过。Redmi 真实动作覆盖计算器打开/点击、设置滚动/搜索/输入、敏感输入拒绝、返回/主页和时钟启动；真实 `gpt-5.5 + Responses` Run `run-13bcfa28-346f-4a71-b98b-5b44cf28bd92` 完成模型规划、`device.open_app` 审批、动作后验证、Tool Ledger 和最终总结，状态 `COMPLETED`、审批 `APPROVED`、Executor 验证 `PASSED`。该历史阶段不代表当时已开放 Workflow 动作；Workflow 在第 108 至 121 阶段逐项接入。首批验收不扩展到任意 App。

35. 已完成：多步骤审批等待恢复。`AgentRunResumePolicy` 只接受一个 `PENDING` Approval 与最后一个已校验、无 ToolResult 的 ToolCall 完全一致，所有前序调用均有成功结果和 `PASSED` 验证，执行/验证/审批 Step 与 Ledger/Event 严格对应。恢复后重建 `completedTools`、已执行调用数和调用指纹，批准当前工具后继续原 Run 的后续规划；前序工具不会重放，预算与重复调用检测不会因重启清零。354 条 JVM 与仅 Redmi 执行的 124 条 instrumentation 全部通过；新增磁盘 Room 测试真实关闭并重开数据库，确认原 Run ID、第一步已验证前缀、第二次审批和审批 Step 保持。

36. 已完成：所有工具结果与验证事实已持久化后的原 Run 收尾恢复。`AgentRunResumePolicy` 新增 `VERIFIED_TOOL_COMPLETION`，只接受 `VERIFYING`、无待审批、每个结果成功且每个验证均为 `PASSED`、执行/验证 Step 一一对应、最后验证 Step 为 `RUNNING/COMPLETED`、其后没有新 Step，并要求 Ledger/Event/Profile 一致。恢复重建全部可信工具、调用数和指纹；若需要只补齐最后验证 Step，再生成本地可信总结，不调用 Executor/LLM、不追加第二条 `tool.verify`、不续跑 Workflow。两个确定性终止点、磁盘 Room 重开、358 条 JVM 和仅 Redmi 执行的 125 条 instrumentation 全部通过。

37. 已完成：`AgentExecutionBudget` 改用可注入单调时钟，规划、工具和总结段共享同一累计 Run 预算，工具 duration 使用同一时钟。新 Run 和每个成功执行段写入 `run.execution_budget.updated` typed 快照；审批及受限恢复继承原 Run 的 total/consumed，旧 Run 先建立零值兼容起点，缺 metadata、越界、总额漂移、累计回退，或最后 ToolResult 晚于最后预算快照时拒绝原地恢复。Step 上限小于剩余预算时报告 Step timeout，二者相等或剩余更少时报告 Run timeout；审批等待不计入，调用方外部超时仍按取消收敛。多段累计、精确边界、恢复剩余 20ms、工具结果/预算崩溃窗口、旧 Run 起点、codec/UI 和损坏证据测试均已覆盖；374 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

38. 已完成：重试前副作用证据分类。`AgentTaskRetryPolicy.assessEvidence()` 统一读取独立 Tool Ledger、旧 typed RunEvent、Receipt 状态和执行/验证中断，输出 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE`。任务中心卡片和确认弹窗展示稳定分类码、原因和建议；确认提交前重新读取当前 Run，状态不可重试时关闭弹窗，证据码变化时更新弹窗并停止本次旧确认，只有分类稳定后才继续。该阶段没有扩大原地恢复能力，仍禁止恢复旧模型协程、调用旧 Executor 或把 UNKNOWN 当作未提交；所有重试继续创建关联新 Run，旧 Run 保持不变。381 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

39. 已完成：Workflow 步骤落库后的进程终止与启动对账。`ScheduledWorkflowOrchestrator` 在 `completeWorkflowStep()` 成功返回后、下一步骤启动前提供专用故障注入 seam；模拟进程终止直接退出，不触发普通失败结算、通知或 `Result.retry`。JVM 测试确认第一步只执行一次、输出已保存、第二步仍为 `PENDING` 且没有结算；Room 测试再确认启动 `reconcileInterruptedRuns()` 会保留完成前缀并关闭旧 Run，`retryRun()` 创建关联新 Run，将前缀标为 `SKIPPED` 并设置 `reusedFromStepId`，只从首个未完成步骤继续。生产保持 no-op 注入，不自动恢复旧 Workflow 或复制 Agent Run。382 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 13 条定向 Workflow instrumentation 全部通过。

40. 已完成：通用重试证据可见性。任务中心卡片现在直接显示 `NO_SIDE_EFFECT / NOT_COMMITTED / COMMIT_UNKNOWN / COMMITTED_UNVERIFIED / COMMITTED_VERIFIED / EVIDENCE_INCOMPLETE` 的稳定分类、原因和建议动作；确认弹窗与卡片仍复用同一证据评估，确认提交前继续校验证据码。该切片只改善恢复处置的可解释性，不改变 `COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED` 和 `EVIDENCE_INCOMPLETE` 的确认门禁，不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。383 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

41. 已完成：启动恢复证据快照。不可原地恢复的活动 Run 在步骤/审批收敛前按原始状态计算重试证据，并写入 typed `run.recovered.retryEvidenceCode`；执行/验证中无结果固定为 `COMMIT_UNKNOWN`，纯思考中断且无副作用为 `NOT_COMMITTED`，Ledger 漂移为 `EVIDENCE_INCOMPLETE`。任务中心和确认前仍重新计算当前证据；带快照的 Run 使用快照还原收敛前中断边界，避免把启动清理产生的 `PENDING -> CANCELLED` 误判成副作用，当前 Ledger 真正漂移时仍升级为 `EVIDENCE_INCOMPLETE`。旧 Recovery 事件缺字段继续兼容，可原地恢复候选不写取消证据；该阶段不恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。388 条 JVM、lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 125 条 instrumentation 全部通过。

42. 已完成：Worker 冷启动重入收敛。`ScheduledWorkflowReentryCoordinator` 只拦截仍为 `RUNNING` 的 ScheduledTask，沿当前 Task→WorkflowRun→AgentRun 关联链按 ID 定向关闭旧执行栈，再按 Agent→Workflow→Task 顺序完成对账；普通 `SCHEDULED` 任务不改变 claim/执行路径，不使用 `Result.retry`，不恢复旧模型协程或 Workflow 后续步骤。无关前台 Agent 保持不变，周期下一实例仍等待旧任务进入终态后再物化。391 条 JVM、Lint、Debug 与 AndroidTest 构建，以及仅 Redmi 执行的 126 条 instrumentation 全部通过；该阶段当时只完成确定性 Room/协调器重入验收，真实系统强杀 Worker 的耗时与回收位置留待第 43 阶段。

第 42 阶段当时的下一阶段边界：继续在 Redmi 验收真实较长 Worker 任务的系统回收位置、WorkRequest 重入和持久化耗时；提交状态未知或验证事实不完整仍只安全收敛并引导关联新 Run。旧模型协程和 Workflow 后续步骤仍不原地恢复；设备工具继续禁止进入 Workflow 或后台自动化。

43. 已完成：Redmi 真实 Worker 冷启动重入。临时 instrumentation 在 Redmi `wsvwypiz7xwslvl7` 创建 7 步 SAFE Workflow 并保持目标进程存活；`WorkRequest=0d9aa2a5-ff1b-4a04-ad74-5d3c7bdf76db` 于 `06:05:03` 启动，`06:05:05` 首个 Agent Run 处于 `THINKING`。`am kill` 因 instrumentation 前台身份未终止 PID `25755`，立即使用 `run-as ... kill -9` fallback；约 `0.2s` 后新 PID `26092` 冷启动同一 WorkRequest。重入只收敛关联的 Agent/Workflow/ScheduledTask，后 6 步未启动，关联 Agent Run 数量仍为 1，工具调用/结果为 0，实际耗时 `3360ms`。该受控强杀不等同 Android 自主回收，也不扩大为通用原地恢复。

44. 已完成：任务中心“需确认”队列。新增 `AgentTaskFilterPolicy` 和 `NEEDS_CONFIRMATION` 筛选，只聚合已结束、可重试且必须确认副作用证据的 Run；提交未知、已提交未验证/已验证和证据不完整沿用现有卡片说明与确认弹窗。确认提交前重新核对证据码，稳定后只创建关联新 Run，旧 Run 保持不变。新增 3 条 JVM 筛选策略测试和 1 条 Redmi Compose instrumentation，完整门禁为 394 条 JVM、127 条 Redmi instrumentation。

45. 已完成：不可原地恢复 Run 的结构化处置。`AgentRunResumePolicy` 的 `RESTART_REQUIRED` 现在由构造约束强制携带稳定 `AgentRunRestartDispositionCode`，覆盖 Run 状态、Profile、预算、审批边界、恢复证据、步骤对应、工具定义和已提交副作用证明等类别；每类同时给出具体策略原因、证据边界和只创建关联新 Run 的建议。`closeInterruptedRuns()` 在改变 Step/Approval 前完成评估，并把恢复类型、处置码、策略原因、边界、建议和重试证据一起写入 typed `run.recovered`。任务卡、详情顶部与事件区读取同一历史快照，旧事件不补造，未知未来枚举 fail-closed。完整门禁为 395 条 JVM、128 条仅 Redmi instrumentation。

46. 已完成：Redmi 长任务与系统策略证据。8 步 SAFE Workflow 的首步成功，第二步重复 `app.current_time` 被循环保护安全终止，Worker 总耗时约 28.5 秒；强制 Doze 在 20 秒观察窗内保持同一任务 `SCHEDULED`，退出后只创建一个 Workflow/Agent Run。退出 Doze 与 `RUNNING_CRITICAL` trim-memory 样本均快速出现 `connection closed`，但无压力对照暴露的是前台启动恢复与新 Worker 的状态竞态，因此不建立因果。竞态曾使 ScheduledTask/Workflow 为 `CANCELLED`、AgentRun 被迟到协程覆盖为 `COMPLETED`；现已用 DAO 原子非终态更新冻结 AgentRun 终态，并在 Redmi 增加回归。`force-idle`、`kill -9` 和 trim-memory 均不等于 Android 自主 LMK。

47. 已完成：当前进程 Worker 所有权与启动恢复隔离。`ScheduledWorkflowWorker` 在任何 Repository 构造、重入对账和 claim 前以引用计数注册 Task；`StartupRecoveryCoordinator` 在同一互斥边界冻结活动 AgentRun、WorkflowRun 和 RUNNING ScheduledTask，快照期间新 Worker 等待，已注册 Task 对应的 Workflow/Agent 链从旧候选中排除。ViewModel 后续审批恢复、受限验证恢复、关闭旧 Agent、Workflow 对账和 ScheduledTask 对账全部只消费该候选快照。纯 Kotlin 测试覆盖快照后的 Worker 不进入旧候选；Redmi Room 测试确认旧链收敛，当前链保持活动并可完成，Agent Run 数不增加。实现不使用墙上时间，不新增 owner token/Schema，不恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。完整门禁为 397 条 JVM、130 条仅 Redmi instrumentation。

48. 已完成：后台 `RUNNING` Workflow 可见停止。停止入口先取消目标 WorkRequest 并等待 Worker 正常写入终态，超出有界窗口或系统取消异常时按 Task→Workflow→Agent 持久化链兜底；Agent 尚未关联时仍关闭 Task/Workflow，`SCHEDULED→RUNNING` 抢占会升级为运行中停止。取消只影响目标链且幂等；Run 终态后 Step、Approval、Event 和 Tool Ledger 一并冻结，迟到 HTTP、模型和审批结果不能覆盖或污染 `CANCELLED`。Redmi 真实停止样本约 32.6 秒；另一个三步 SAFE Workflow 依次执行当前时间、会话列表和笔记列表，约 21.8 秒全部完成。LMK probe 显示报告能力可用、历史退出 11 条、`REASON_LOW_MEMORY=0`，不构成自主 LMK 样本。完整门禁为 402 条 JVM、134 条仅 Redmi instrumentation。

49. 已完成：Redmi 正式 8 步 SAFE 后台 Workflow 全成功样本。Task `scheduled-task-b7cae61a-e311-42bc-98a7-f8d601a9be59` 只关联一个 WorkRequest 和一个 Workflow Run，8 个 Agent Run 顺序执行当前时间、会话列表/检索和笔记列表/检索，全部 `COMPLETED` 且 ToolResult 为 `success=true / PASSED`，总耗时约 62.2 秒。先行样本约 49 秒时因模型未调用第 6 步 `memory.search` 安全失败，后两步取消且没有复制 Run。最新 LMK probe 为 `supported=true / exits=6 / lowMemory=0`，6 条均是本轮 instrumentation `FORCE STOP`；生产代码未改变，完整门禁继续为 402 条 JVM、134 条仅 Redmi instrumentation。

50. 已完成：停止异常后的持久化重对账。Workflow 仍活动时，运行中停止先把 ScheduledTask 原子写为 `STOP_REQUESTED`，再请求 WorkManager 取消；系统取消与即时 fallback 同时失败时仍保留停止意图。若 Workflow 在停止事务前已经终态，则停止请求不改写历史终态，直接把半结算 Task 对账到该状态。Worker 重入、启动恢复和停止兜底都识别中间态，当前进程所有权只排除真正 `RUNNING` 的链；Agent Run 尚未关联时，Workflow 对账也会先读取唯一关联 Task 的停止栅栏，将 Run 和未完成步骤收敛为 `CANCELLED`，不会误记为关联缺失失败。停止 fallback 在定向关闭 Agent 后也使用同一原子 API 结算 Workflow/Task；若接管前 Workflow 已有终态，则在事务内直接映射到活动 Task，不再被通用停止栅栏改成矛盾终态。最终 Workflow/Task 在同一 Room transaction 重新读取栅栏与既有 Workflow 终态并原子收敛；步骤完成与成功消息也共享同一停止栅栏，迟到成功不能写成 `COMPLETED` 或追加到会话，周期下一实例不会在旧任务终态前物化。该状态复用既有 TEXT 列，Room v27 Schema 不变；完整门禁为 405 条 JVM、141 条仅 Redmi instrumentation。

51. 已完成：旧验证事件缺少稳定调用身份时 fail-closed。v19 及更早 event fallback 仍允许带完整 ToolCall ID 的 typed 结果/验证进入恢复证据；`tool.verify` 缺少 ID 时不再按同名工具和事件顺序猜配，而是返回无效并由恢复/重试策略保守映射为 `EVIDENCE_INCOMPLETE`。带完整 ID 的前序验证事实与已提交工具只读恢复保持不变，Room v27 Schema 不变。Redmi `ApplicationExitInfo` 基线 `supported=true / exits=2 / lowMemory=0 / fallbackSigkillCandidates=0`，退出来自 instrumentation 与安装包，不是自主 LMK。完整门禁仍为 406 条 JVM、141 条仅 Redmi instrumentation。

52. 已完成：恢复证据 canonical fingerprint。`AgentTaskRetryEvidenceFingerprint` 对工具调用/结果账本与非恢复 typed event 进行长度前缀规范化并计算 SHA-256；启动收敛在修改 Step/Approval 前把摘要与证据码一起写入 `run.recovered.retryEvidenceFingerprint`。任务中心确认弹窗也冻结打开时的码和摘要，提交前重算；新增合法 ToolCall、替换参数或 Receipt、改变验证事件即使仍归类 `COMMIT_UNKNOWN` 也会拒绝旧确认并提示重新确认。指纹一致时原有确认路径不变，旧恢复事件只有证据码但缺少指纹时按 `EVIDENCE_INCOMPLETE` 处理。Room v27 Schema 不变，旧 Run 仍保持不变。完整门禁为 408 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

53. 已完成：ToolResult/预算/验证三段持久化边界。`AgentRuntimeFaultInjector` 分别暴露 Result 事件写入后、预算快照写入后和 `tool.verify` 事件写入后的进程终止 seam；Result 已落库但后续预算快照缺失时，`AgentRunResumePolicy` 固定以 `EXECUTION_BUDGET_INVALID` 拒绝原地恢复，不能因为 Receipt 为 `COMMITTED` 就猜测剩余执行预算。`tool.verify` 已落库但验证 Step 尚未收尾时，Runtime 只补控制面和本地可信总结，不重复 Executor、ToolResult 或验证事件。生产默认注入器保持 no-op，不扩大旧模型协程、旧 Executor 或 Workflow 后续步骤恢复。完整门禁为 409 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

54. 已完成：模型/网络异常预算审计与总结兜底。规划请求带 `AgentLlmResponseException` 时先持久化失败 telemetry，再写入本次已消耗的执行预算；没有统一 telemetry 的网络/网关异常也至少冻结预算后进入失败终态。总结请求网络失败不再把已成功验证的工具 Run 改判为失败，而是写入 fallback 事件、冻结预算并使用本地可信回复完成原 Run。Receipt 回读验证失败继续写入稳定 `RecoveryFailure`，旧写入事实保持 `COMMIT_UNKNOWN` 并要求确认后新 Run 重试，不调用旧 Executor。完整门禁为 411 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

55. 已完成：流式/上游错误 typed 分类。新增 `AgentLlmFailureKind` 与 `llm.request.failed` 事件，统一记录鉴权、地址、限流、模型、超时、DNS、TLS、连接、响应和未知错误；流式断流沿 `ApiFailureClassifier` 的 CONNECTION 语义进入同一事件，未知未来 kind 在 Codec 中降级为 `UNKNOWN`。任务事件区展示阶段、错误码和原因，不保存请求正文；规划/总结预算审计和本地兜底保持第 54 阶段边界。完整门禁为 413 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

56. 已完成：部分流式 delta 的用户可见收敛。普通对话已经显示部分正文后发生断流时，保留已见正文但把消息收敛为 `finishReason=failed` 并展示“内容不完整”，用户取消同样结束“接收中”状态；失败/取消的部分 assistant 不再进入后续请求和会话摘要。新增真实 socket 断流、消息终态和上下文资格测试，完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

57. 已完成：取消时的后台预算写回竞态。Runtime 在新 Run、审批恢复和受限恢复的取消出口统一进入 `NonCancellable`，先追加最新单调预算快照，再取消活动 Step、写入 `run.cancelled` 并冻结 Run；模型或工具 `finally` 已累计的时间因此不会被 WorkManager/用户停止丢失。确定性测试验证取消前 `37ms` 预算可恢复读取，预算事件严格先于取消终态；完整门禁为 420 条 JVM、Lint、Debug/AndroidTest 构建，以及仅 Redmi 执行的 141 条 instrumentation。

60. 已完成：instrumentation 退出后的真实后台冷启动成功样本。入队 Probe 立即结束，JobScheduler 冷启动新 PID，生产 Worker 使用持久化 WorkRequest/ScheduledTask/WorkflowRun 完成 8 步、32 次只读工具调用；预算快照无回退、无复制 Run、无模型失败，耗时 `204.977s`。本轮仍无自然 LMK。
61. 已完成：熄屏冷启动长任务成功样本。计划时间后延迟 `159.479s` 冷启动，屏幕持续 `Asleep`，生产 Worker 完成 8 步、32 次只读工具调用，耗时 `244.236s`；预算快照无回退，未发生自然 LMK。
62. 已完成：后台 Worker 系统停止原因审计。Android 12+ 取消收敛读取 WorkManager `getStopReason()`，把 JobScheduler 停止码映射为隐私安全的稳定 `code + name`，并在同一 Room v28 事务写入 ScheduledTask/WorkflowRun；任务中心展示分类。旧 Android、`NOT_STOPPED` 与未知码维持保守结论，v27→v28 不为历史 Run 补造停止原因。JVM `424/424`、仅 Redmi instrumentation `143/143` 通过；确定性 `QUOTA(10)` 映射和双表持久化已验收，但尚无自然 Android 系统停止样本。
63. 已完成：真实 WorkManager 应用取消原因与用户停止优先级。Redmi Android 14 运行中 Worker 经 `cancelWorkById()` 后实际报告 `CANCELLED_BY_APP(1)`；生产策略映射通过。Room 契约确认 `STOP_REQUESTED` 已存在时保留用户原因并忽略后到机制码，Task/Workflow 同事务取消且不伪造系统停止字段。完整门禁为 JVM `424/424`、仅 Redmi instrumentation `145/145`。
64. 已完成：Android 进程退出观察账本。前台启动与生产 Worker 冷启动读取 Android 11+ `ApplicationExitInfo`，Worker 先登记当前进程所有权；Room v29 独立保存最多 30 条稳定数值记录，不关联 Task/Run，不保存 description、trace 或状态摘要。只有 `LOW_MEMORY` 为直接 LMK，报告能力缺失时的 `SIGNALED + SIGKILL` 仅为候选，用户/应用/包维护保持受控分类。旁路失败不阻断主流程，协程取消继续传播。JVM `431/431`、Redmi 聚焦 `5/5`、完整 instrumentation `149/149` 通过；受控 `force-stop` 被正确记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`。
65. 已完成：进程退出观察只读诊断 UI。设置页只调用 `latest()` 显示最近 30 条 Room v29 记录，以稳定中文标签区分直接 LMK、候选、故障、系统资源、受控维护和未归因证据；刷新不触发采集，不增加 Task/Run/Workflow 归因。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；受控 `force-stop` 页面显示与数据库一致，刷新前后计数不变。
66. 已完成：普通聊天请求上下文准备迁出 ViewModel。独立 preparer 统一消息资格、知识生命周期、摘要失效/复用、最近窗口、增量摘要、可信 Agent 历史和 Responses 附件投影；协程取消不再被兜底吞掉。ViewModel 减少 215 行，Room/协议/UI 不变；新增聚焦 JVM `8/8`、完整 JVM `439/439`、仅 Redmi instrumentation `152/152` 通过。

67. 已完成：普通聊天网络发送编排迁出 ViewModel。独立 coordinator 统一发送前 Room 快照、上下文准备、模型请求、流式增量和终态事件顺序；取消先收敛 UI 再继续传播，持久化失败不触发模型。`sendMessage()` 从约 190 行收敛到约 104 行，Compose 投影仍留在 ViewModel；Room/协议/UI/Agent/Workflow 不变。新增聚焦 JVM `3/3`、完整 JVM `442/442`、仅 Redmi instrumentation `152/152` 通过。

68. 已完成：会话状态投影规则迁出 ViewModel。独立 policy 统一第一条 `role=user` 消息标题（空白保持“新会话”）、重复空会话折叠、时间戳、摘要元数据继承、blank ID 和非当前更新隔离；ViewModel 从 4272 行降到 4189 行，异步 Room/Job/删除事务与 Compose 副作用仍保留。Room/协议/UI/Agent/Workflow 不变。六轮 TDD 后聚焦 JVM `6/6`、完整 JVM `448/448`、仅 Redmi instrumentation `152/152` 通过。

69. 已完成：会话保存协调迁出 ViewModel。独立 coordinator 统一 latest-save Job、Room 单写者、发送前等待和显式删除意图代次；旧事务不可取消时仍保证最新快照最后写入，失败、重标记或旧失败回调晚到时不误确认或回滚新删除。ViewModel 从 4189 行降到 4183 行，异步加载、删除 UI 与 Compose 副作用仍保留。Room/附件 BLOB/协议/UI/Agent/Workflow 不变。八轮 TDD 后聚焦 JVM `8/8`、完整 JVM `456/456`、仅 Redmi instrumentation `152/152` 通过。

70. 已完成：异步会话加载协调迁出 ViewModel。独立 coordinator 统一 latest-load Job、选择代次和稳定事件，底层查询在取消后迟到成功或失败均不会覆盖最新选择、删除回滚或失败提示；新 Job 先登记再派发 Loading，回调重入也保持最新 Job。ViewModel 继续投影完整消息/轻量会话、选择下一会话、删除回滚和保存；Room/附件 BLOB/协议/UI/Agent/Workflow 不变。四轮 TDD 后聚焦 JVM `4/4`、完整 JVM `460/460`、仅 Redmi instrumentation `152/152` 通过。

71. 已完成：会话加载 UI 投影规则迁出，统一 Loading/Loaded/Failed 与附件轻量化，完整 JVM `463/463`、Redmi `152/152` 通过。
72. 已完成：会话新建和删除后的选择规则迁出，完整 JVM `468/468`、Redmi `152/152` 通过。
73. 已完成：会话选择与删除副作用协调迁出，固定取消加载、删除代次、运行态清理和选择/加载顺序，完整 JVM `472/472`、Redmi `152/152` 通过。
74. 已完成：网络请求根设置改为独立入口页，User-Agent 输入区至少 5 行并提供复制/清空/恢复默认，Redmi 完整 `153/153` 通过。
75. 已完成：`/agent` Responses USER 单一 Image/Document 输入、审批恢复与关联新 Run 附件重建，完整 JVM `477/477`、Redmi `153/153` 通过。
76. 已完成：Room v30 Embedding 检索 v1，按 Provider/模型隔离 Float32 向量，语义与词法稳定融合并在失败时回退。
77. 已完成：当前 Provider/模型 Embedding 索引摘要与显式单文档重建，失败、停用和 revision 竞态不破坏旧索引。
78. 已完成：固定语料 Recall@5、MRR、负例准确率、重复排序稳定率与实际 Embedding 状态诊断。
79. 已完成：真实 Provider 语义检索端到端，英文查询在词法零命中时首位召回中文目标文档。
80. 已完成：10 篇真实 Embedding 有界语料三轮质量、耗时、向量与内存基线；无关查询仍返回近邻，确认相关性拒绝优先于 ANN。
81. 已完成：Room v31 保存 top1、top2、margin 和候选数 shadow 观测，知识管理页展示但生产检索不拒绝。
82. 已完成：20 篇成对语料、三桶各 10 条、每条重复两次并在 Redmi 三个独立进程校准；同集候选保持 shadow。下一阶段冻结 Provider/模型候选后用独立 holdout 验证，不直接上线生产拒绝。
83. 已完成验证并否决候选：冻结 Stage 82 的 Provider/模型、阈值和校准集身份，以全新 holdout 三轮复验；排序指标全为 `1.0`，但正例接纳率仅 `0.80`，低于预注册 `0.90`，因此不进入生产拒绝且不使用 holdout 回调阈值。
84. 已完成：Room v32 保存候选均值、总体标准差和 top1 z-score shadow 观测；退休 holdout 显示正例与近负例 z-score 仍重叠，不启用生产拒绝。

85. 已完成：两套全新正式身份 calibration/validation 的七类特征族比较，校准与验证数据各 24 条，Recall@5 均为 `1.0`，但七类特征族无一达到预注册相关性标准。
86. 已完成：正式门禁否决被编码为成功的 Redmi 显式验收（`OK (1 test)`），不降低阈值、不回调 validation、不升级 `VERIFIED` 或进入 final holdout。
87. 已完成：模型漂移身份回归，Provider、模型、配置指纹和数据集版本任一漂移均在比较前 fail-closed。
88. 已完成：完整本地门禁更新为 JVM `535/535`、Lint、Debug/AndroidTest APK；仅 Redmi 默认 instrumentation XML 为 `185` 条（`176 passed / 9 skipped / 0 failed`）。
89. 已完成：新的跨主题归一化策略只使用 `top1-均值` 与 `margin/标准差`，绑定正式身份和独立数据集，JVM 契约覆盖冻结阈值、身份漂移、缺桶、标签漂移与零方差拒绝。
90. 已完成：两套各 24 条 Redmi 观测的 Recall@5 均为 `1.0`，但三种归一化特征族通过数仍为 `0`；最优族近负例拒绝只有 `0.75`，稳定得到预注册门禁否决。
91. 已完成：生产身份仍为 `CANDIDATE`，生产相关性拒绝与答案路径接入保持关闭；不再调同一检索分数，转向 answerability/重排证据。
92. 已完成：严格答案可回答性策略、离线 `7/7` 契约和 Redmi `12 + 12` 真实 `gpt-5.5` shadow 观测；网络/解析失败为 `0`，两类特征族通过、覆盖率特征族未通过，生产 enforcement 继续关闭。
93. 已完成：答案可回答性 shadow 提示与引用共存契约新增 `5/5`，合计聚焦 JVM `12/12`；Redmi 默认完整 `OK (188 tests)` 已覆盖提示/引用共存 UI；该阶段当时生产消息流仍未绑定。
94. 已完成：真实消息流只读 answerability shadow 绑定；候选只来自可信 `knowledge.search`，强类型数据集身份、Run/观测一致性、引用快照和保守 `UNKNOWN` 契约均已验收；该阶段当时尚未接入生产消息流。
95. 已完成：线上无标签 measurement 与离线带标签 observation 分离；默认关闭、前台直接来源、两次有界尝试、取消透传、Judge 身份漂移、隐私指纹和 Store 失败隔离均已冻结，完整 JVM `578/578`。
96. 已完成：默认关闭的生产 Judge adapter、冻结身份门禁、答案保存后异步 caller、独立设置开关和进程内 `messageId` notice 已接入；固定 `store=null / persistenceMode=NONE`，保存失败/取消与 Judge 失败均旁路跳过，完整 JVM `593/593`。
97. 已完成：固定上限的进程内样本遥测、Judge 成本/重试失败分布和 notice 生命周期统计已接入；Redmi 真实前台样本 `1` 条完成，完整 JVM `600/600`、默认 instrumentation `OK (191 tests)`，Room Store 与 enforcement 继续关闭。
98. 已完成：Redmi 同一进程累计 Shadow 样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次形成直接回答 `2`、部分回答 `2`；关闭并删除测试会话后 notice 有效 `4 -> 1`、裁剪 `0 -> 3`，Room Store 与 enforcement 继续关闭。
99. 已完成：新的 Redmi 真实使用窗口新增 `3` 条有效 Judge 样本，直接回答 `2`、部分回答 `1`，无取消、异常或自然 Judge 失败；宽查询导致的无候选 Agent 失败未进入 Shadow，清理后 notice 有效 `3 -> 0`、裁剪 `0 -> 3`。
100. 已完成：Android 系统分享入口 v1。单文本/单图片只进入新会话草稿，冲突需用户确认，冷/热启动和重建行为明确；不自动发送、不信任来源、不扩展工具权或后台能力。
101. 已完成历史观察窗口：v33 之前的首个间隔真实使用窗口新增 `1` 条直接回答，第 97 至 101 项书面人工合计为样本 `10`、有效 Judge `8`；这些历史数字不回填新账本。
102. 已完成：冻结匿名 Shadow 与显式授权内容不能混装的版本化离线评测导出契约；匿名证据固定不能用于 calibration/validation，JSON/SAF 与 production enforcement 未接入。
103. 已完成首个 Room v33 间隔真实样本：第 103 阶段当时账本 `1` 条 `COMPLETED / BOUND / ACCEPT`，失败计数为 `0`，清理后知识文档/分块为 `0`、Shadow 关闭。
104. 已完成第二条 Room v33 短间隔真实样本与冷启动摘要修复：当前账本 `2` 条，均为 `COMPLETED / BOUND / ACCEPT`；两条只相隔约 `46` 分钟，继续等待真正分隔窗口。
105. 已完成单次显式 Shadow 采样窗口：原子门禁保证每次开启最多启动一轮观测，开始前自动关闭并持久化；并发答案、无候选、保存失败或提前撤销不能复用授权。
106. 已完成 Shadow 时间窗口证据投影：设置页显示最早/最新匿名记录和精确跨度，只供人工核对，不自动授予分隔、校准、导出或生产拒绝资格。
107. 已完成第三条独立同日 Shadow 记录：首次预算耗尽 Run 没有成功答案，因此不消费一次性授权、不增加账本；第二次成功新增第三条 `COMPLETED / BOUND / ACCEPT`，累计跨度 `5 小时 24 分 46 秒`，仍不授予长期分隔资格。
108. 已完成前台 Workflow 只读设备观察：只开放 `device.snapshot`，动作工具、后台/定时 Workflow、截图、坐标、视觉定位和任意 App 继续关闭。
109. 已完成 Workflow 设备观察证据 UI：只从持久 Tool Ledger 投影已验证白名单证据，原始 JSON 不进入 Compose 根状态，历史 ref 显式过期。
110. 已完成 `workflow-device-observation-v1` 本地判定：下一步和关联重试都重新回查 Ledger，证据不足或漂移时 fail-closed。
111. 已完成真实双 Run 验收与 Workflow 输出净化：观察/消费 Run 各只一个 SAFE 工具，无动作和审批；step result、output snapshot、previous outputs 和后台会话都只保留 169 字符本地判定。
112. 已完成：前台 Workflow 有限设备动作安全契约。纯策略 Interface 默认不启用任何动作，冻结前台来源、用户意图、当前 Run/Step/ToolCall、同 Run 新观察、逐动作实时审批、window/ref 漂移、后置 snapshot/typed 验证与恢复/重试/取消拒绝语义；该阶段冻结时生产 Registry 动作面为空。
113. 已完成：前台 Workflow 首个生产动作 `device.tap_ref`。工具面精确为 `snapshot + tap_ref`，Room 审批、Accessibility 安全浮层、generation/ref 实时复核、动作后 `executorVerified=true + typed PASSED` 和白名单输出已在 Redmi 闭环；其他前台动作与全部后台设备自动化继续关闭。
114. 已完成：把持久化设备动作判定和审批结果投影到 Workflow 答案级证据 UI；成功卡只保留白名单后置摘要，拒绝、取消、窗口变化、浮层不可用、服务断连和 BUSY 使用稳定枚举，原始参数/原因不进入 Compose，历史动作 JSON 全链脱敏。
115. 已完成：为前台 Workflow `type_text` 冻结独立安全契约，覆盖敏感文本拒绝、精确参数、可编辑且未脱敏目标、最小指纹授权、通用门禁强制委托、输入后重新观察和精确回读；生产白名单仍不包含 `type_text`。下一阶段才接 Registry/Accessibility evidence seam 并在 Redmi 验收。`swipe / open_app / back / home`、后台自动化、精确定时、Foreground Service、MCP、日历/通知、远程 Channel、多 Agent 和本地模型继续后置。
116. 已完成：接通 `type_text` 的 Registry/Accessibility evidence seam。当前 ref 可返回 enabled/editable/redacted/动作证据；动作后只按原 `nodePath` 精确回读目标 `text`，其他节点同文不能误判；Registry 测试态完成原 identity、指纹授权与强类型 readback 全链，结果摘要无原文且答案级判定仍只消费 `tap_ref`。Redmi 设置搜索框普通输入成功、敏感输入拒绝且原值保持不变；生产白名单仍不包含 `type_text`。
117. 已完成：把 `type_text` 接入生产前台 Workflow。Room 独立审批只保存 snapshot/ref、文本 SHA-256 与长度，Accessibility 浮层和答案级判定不展示原文；`100ms` settle 吸收 Redmi 连续 detach 事件并在最终活动根/窗口集合漂移时 fail-closed。生产工具面精确为 `snapshot / tap_ref / type_text`，Redmi 真实 Workflow 已完成 `APPROVED / PASSED / VERIFIED / exactReadBack=true`；其他动作与全部后台自动化继续关闭。
118. 已完成：统一直接 `/agent` 与 Workflow 的 `device.type_text` 持久化隐私。Runtime、Tool Ledger、Room Approval、审批事件、可信消息上下文和 Tool parts 只保存 snapshot/ref、文本 SHA-256 与长度；当前进程审批卡仍显示原文但与 Room 安全投影强绑定。重启后旧文本审批以 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE` 取消并要求新 Run，不能从指纹恢复输入。
119. 已完成：把空参数、零审批的 SAFE `device.back` 接入前台 Workflow。生产工具面精确为 `snapshot / back / tap_ref / type_text`；当前步骤意图、同 Run snapshot、30 秒 TTL、generation、Executor/typed 验证和动作后观察保持强制，Room/UI 不伪造 Approval。Redmi 真实 tracer 为 `approvals=0 / verified=true / VERIFIED`。

120. 已完成：把空参数、零审批的 SAFE `device.home` 接入前台 Workflow。生产工具面精确为 `snapshot / back / home / tap_ref / type_text`；当前步骤意图、同 Run snapshot、30 秒 TTL、generation、Executor/typed 验证和动作后观察保持强制，后置包名必须匹配系统动态解析的 launcher，Room/UI 不伪造 Approval。Redmi 真实 tracer 为 `approvals=0 / verified=true / VERIFIED`。

121. 已完成：把逐包审批的 `device.open_app` 接入前台 Workflow。生产工具面精确为 `snapshot / open_app / back / home / tap_ref / type_text`；唯一 `package_name` 在 SafetyPolicy、ApprovalGate 和 Executor 三层限制为首批四个包，审批绑定当前 Run/ToolCall/session、30 秒 snapshot 和 generation，完成门禁与答案级 Room 重建再次要求后置包名等于获批目标。Redmi Compose、Room 纵向单项和真实 `snapshot -> open_app(com.android.calculator2)` tracer 均通过；首次超时审批按设计拒绝。`swipe` 与全部后台设备自动化继续关闭。

122. 已完成：冻结前台 Workflow `device.swipe` 专属纯策略契约，覆盖精确参数、当前可滚动目标、动作前匿名 viewport、SAFE 零审批授权、同应用/同 window/同目标、generation 前进、可见内容变化和四方向共同锚点主位移；专属授权只保存方向与 viewport 摘要。该阶段当时没有接线生产 Registry、Controller/Result/Room/UI，Workflow 工具面继续保持六项；第 123 阶段随后完成执行期 opaque/HMAC 锚点 evidence seam，后台设备自动化仍关闭。

123. 已完成：建立 `device.swipe` 的 Controller/Registry 执行期 HMAC evidence seam。Controller 以每实例随机密钥生成目标和绑定当前滚动目标的唯一语义锚点身份，snapshot/ref/viewport 同生命周期撤销，inspection 以 generation 双读拒绝构造期间的窗口漂移，并用同窗内容变化与共同锚点方向主位移替代 generation-only；Registry 显式测试集合可消费动作前 viewport，生产默认集合、Result codec、Room、UI 和后台自动化保持关闭。聚焦 JVM `89/89`；下一阶段先接完成态内存交接和 Redmi 限定 App 真实滚动验收。

124. 已完成：把 `device.swipe` 的 Controller outcome 以纯内存方式交给 Registry 完成门禁，并把前后 viewport 与本次授权前后 snapshot 精确绑定；通用 Result 只保留无 HMAC 摘要。八组聚焦 JVM `91/91`、Debug APK 和仅 Redmi 系统设置应用详情页真实 `snapshot -> swipe(up)` 通过，日志为 `verified=true / approvals=0 / registryCompletion=PASSED / privacySafe=true`。生产默认集合、DecisionPolicy、Room/UI 和后台自动化仍关闭。

125. 已完成：把已经通过 Registry 专属完成门禁、Executor 验证和 typed `PASSED` 的 `swipe` 通用摘要接入答案级 Decision、Workflow step output、Room 重建与 Compose 证据卡。持久层只显示“滚动”与通用后置摘要，不保存方向、viewport/HMAC、snapshot/ref、节点正文或坐标，不为 SAFE swipe 伪造审批证据。聚焦 JVM、Debug/AndroidTest APK 和仅 Redmi 的 Room + Compose `OK (2 tests)` 通过；生产默认集合与后台自动化继续关闭。

126. 已完成：把 `device.swipe` 加入生产默认 Registry。前台手动 Workflow 现精确开放七项，SAFE swipe 不创建 Approval，但继续受同 Run snapshot/ref、30 秒 TTL、generation、专属同窗方向 evidence、Executor/typed 验证和答案级脱敏门禁约束。聚焦 Registry `36/36`、六个相邻测试类 `101/101`、Debug/AndroidTest APK 和仅 Redmi 的真实生产 `snapshot -> swipe` 均通过，日志为 `approvals=0 / registryCompletion=PASSED / answerDecision=VERIFIED / privacySafe=true`；后台自动化保持关闭。

127. 已完成：新增“对话 / 任务”模式、严格 JSON Schema 的 1 至 8 步计划、风险/能力边界弹层和确认前零执行。确认后 Room 单事务创建普通 Workflow、手动 Run 与全部步骤快照，并复用既有 Runtime/审批/验证/Ledger；聚焦 JVM `34/34`、Debug/AndroidTest APK、仅 Redmi `OK (2 tests)` 和真实模型 `app.current_time` 独立重试闭环均通过，旧失败 Run 保持不变。

128. 已完成：把首批允许包的 `target_app_package` 冻结到 Workflow/Run/步骤快照，Room 升级至 v34 且旧记录保持空目标包；`workflow-device-action-safety-v2` 约束 open、引用动作与受控导航，Runtime 只在已验证动作后允许刷新 snapshot。聚焦 JVM `92/92`、三个 Redmi Room/Compose 单项和真实 `snapshot -> swipe -> snapshot -> back` 同 Run tracer 通过，日志为 `verified=2/2 / approvals=0 / freshSnapshots=true / privacySafe=true`；目标级验证当时留给第 129 阶段。
129. 已完成：计划增加严格完成标准并冻结到 Workflow/步骤快照，Room 升级至 v35；Repository 从持久 Tool Ledger 和脱敏设备观察重建目标判定，本地输出 `VERIFIED / PARTIAL / INCOMPLETE`，旧 Workflow 保持无 Decision，损坏 Contract fail-closed。聚焦 JVM `22/22`、Redmi 定向 `OK (5 tests)` 和真实多动作 `goalDecision=VERIFIED` tracer 通过。

横向结构工程补充记录：应用导航、Workflow 管理、Agent 任务中心、长期记忆管理、Provider 管理、Agent Profile 管理、Agent Skill 管理、会话主界面、提示词设置、进程退出观察、网络请求设置和设置根页均已迁入独立 UI module；发布后的有界对话框簇又将 Agent/Workflow 重试、长期记忆编辑/删除和本地 Skill 删除归入对应模块。宿主当前 `817` 行并达到停止条件，通用执行恢复矩阵闭环审计也已完成。第 10 项知识质量工程已完成匿名跨进程持久化、第 102 阶段导出契约、第 103/104/107 阶段三条 v33 同日样本、第 105 阶段单次显式采样窗口和第 106 阶段时间证据投影；第 108 至 128 阶段已切回个人 Agent，并依次完成 Workflow 只读 snapshot、答案级观察证据 UI、版本化本地判定、真实双 Run 消费与输出净化、有限设备动作安全契约冻结、`tap_ref` 首个生产切片、答案级动作证据 UI、`type_text` 专属安全/evidence seam/生产闭环、跨直接 `/agent` 的持久化隐私统一、SAFE `back / home`、逐包审批 `open_app`、`swipe` 完整生产链、自然语言个人任务与可确认计划，以及限定 App 多动作连续执行。Shadow 后续只做低频并行观察，不机械搬运 `SettingsPage` composition root，也不阻塞个人 Agent 功能。

后续若继续相关性工作，必须先重新注册能够区分“同主题”和“文档真正回答问题”的 answerability/重排设计，不能用第 90 或 91 阶段 validation 回调阈值或降低标准；在新的独立证据达到预注册标准前，生产拒绝与答案路径继续关闭。第 97 至 101 项已记录窗口人工合计 Shadow 样本 `10`、其中有效 Judge `8`：直接回答 `5`、部分回答 `3`，另有两条无候选跳过；没有自然 Judge 网络/协议/认证失败。无候选跳过、没有成功答案且未进入 Shadow 的预算耗尽或工具步数耗尽不得用来扩权。该人工合计早于 v33 匿名账本且不会回填；第 103/104/107 阶段的新账本当前有 `3` 条完成且接纳记录，最早到最新跨度 `5 小时 24 分 46.689 秒`，仍属于同日窗口，不足以作为长期分隔或 calibration/validation 证据。第 105 阶段已把每次显式开启收紧为最多一轮观测，第 106 阶段只把时间证据展示到设置页，第 107 阶段真实确认预算耗尽但没有成功答案时不消费授权、不增加账本；后续继续在真正跨日或长期分隔的真实使用窗口低频观察。同时只在真实使用中继续积累 Android 自主 LMK、系统配额、超时或自然回收记录，并以 Room v33 中自 v29 延续的进程退出独立账本及只读诊断页核对。没有新自然样本时不再增加模拟回收代码，不把 `force-stop`、应用取消、安装、instrumentation、Doze、trim-memory 或 `kill -9` 包装成自然系统证据。不尝试恢复无法证明的旧执行栈。Daily/Weekly 继续使用非精确定时语义并记录计划/实际时间。Foreground Service 只提高系统存活概率，不代表旧执行栈可以安全恢复；当前熄屏 244.236 秒样本和受控取消仍不支持预先引入。前台 Workflow 当前精确开放 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`；第 126 阶段已完成 swipe 的生产默认接线和仅 Redmi 真实生产 Workflow 验收，全部后台设备自动化继续关闭。第 127 至 130 阶段的自然语言计划、限定 App 多动作连续执行、目标级本地验证、记忆/知识计划上下文和应用内提醒均已完成；下一主线是第 131 阶段任务级恢复与关联重试，再进行 Redmi 完整里程碑验收。精确定时、MCP、系统日历、远程 Channel、多 Agent 和本地模型继续后置。
