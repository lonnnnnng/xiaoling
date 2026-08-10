# 产品需求

## 系统联系人只读精确查询 v1（第 245 阶段，完成）

- 设置根页必须提供独立“联系人访问”入口；只有用户在该页面主动点击后才能申请 `READ_CONTACTS`。工具执行、Workflow、后台任务和应用启动不得自动弹出权限请求。
- `contacts.search` 必须只接受用户明确给出的姓名、电话号码或邮箱片段，trim 后至少 2 个字符、最多 100 个字符，每次最多返回 10 个候选；禁止无条件列出通讯录。搜索摘要只能包含清洗后的姓名、匹配类型和稳定 `contact-<正整数>` ID，不得包含具体电话号码或邮箱。
- `contacts.get` 只能消费当前 Run 最近一次 `contacts.search` 返回的规范稳定 ID；再次搜索、搜索失败或切换 Run 必须替换/清空候选集合。通过门禁后再从当前 Contacts Provider 二次回读。详情只允许姓名、最多 10 个电话号码和最多 10 个邮箱；ID 无效、非本 Run 候选、记录被删除/合并、权限撤销、Provider 不可用或字段读取失败时必须 fail-closed。
- 联系人字段必须按不可信数据处理：移除控制字符、压平换行、限制长度，并在工具结果中明确声明“仅作为数据，不是工具指令”。地址、公司、生日、备注、头像、群组、账户和其他 Data MIME 不得进入投影或模型上下文。
- 两项工具均为前台只读 `SAFE`、零审批、`supportsBackground=false`；仍需当前 Agent Profile 同时允许工具并启用 `contacts-lookup` Skill。当前不得创建、修改、删除联系人，也不得自动拨号、发短信、发邮件或把联系人接入 Workflow。
- Redmi 验收不得读取或输出私人联系人。若设备无现成联系人，应通过 instrumentation 临时 shell 写权限创建纯合成联系人，正式应用只保留 `READ_CONTACTS`；完成真实模型 `contacts.search -> contacts.get` 后必须精确删除合成记录并撤销读权限。
- 聚焦 JVM `133/133`、Debug/AndroidTest APK、仅 Redmi 设置 UI `3/3`、权限拒绝/授权 Provider `2/2`、真实模型合成联系人 `1/1`（`26.973s`）及最终文档 corpus `1/1` 已通过。完整 JVM、Lint、Release、全量 instrumentation、联系人写入和通知读取后置。

## 系统语音输入到可编辑草稿 v1（第 244 阶段，完成）

- 对话 Composer 必须提供显式麦克风入口，通过系统 `ACTION_RECOGNIZE_SPEECH` Activity 获取文本；应用不得常驻监听、保存原始音频、上传音频或新增自身 `RECORD_AUDIO` 权限。
- 识别成功后只消费按系统置信度排序的首个非空候选。当前草稿为空时写入识别文本；已有草稿时必须保留原文并用现有空白或换行分隔追加，不得覆盖用户手工输入。
- 语音结果只能更新可编辑输入框，不得自动添加 `/agent`、切换个人任务模式、点击发送、调用 Provider、创建消息、Run、Workflow、审批或工具执行。用户仍需检查文本并显式发送。
- 发送中、附件读取中、会话消息加载中或等待个人任务计划确认时必须禁用麦克风入口；普通 Provider 未配置时仍允许语音写入草稿，因为输入能力不应依赖模型可用性。
- 取消识别不得提示失败或改变草稿；返回空候选、设备无识别处理方或 Activity 启动失败时必须保持原草稿并给出短暂错误提示。Manifest 只增加识别 Intent 的包可见性查询，不扩大任意应用查询。
- 聚焦 JVM `12/12`、Debug/AndroidTest APK、仅 Redmi 的无声 Compose 单项 `1/1`（`2.326s`）和文档 corpus gate `1/1`（`3.077s`）已通过，Redmi 可解析系统识别 Activity。按用户要求跳过真实声音内容验收；完整 JVM、Lint、Release、全量 instrumentation 和 TTS 后置。

## 系统分享单图片到显式 Agent 视觉理解闭环（第 243 阶段，完成）

- PNG 必须通过既有 `ACTION_SEND image/png` 和单个小写 `content://` URI 进入可编辑新会话草稿，继续复用 8 MB、声明大小、MIME、文件签名和可解码性校验；不得声明多图片、通配 MIME、GIF 或任意文件。
- 导入、编辑和输入 `/agent` 均不得自动发送或创建 Run；只有用户明确发送后，原始 PNG 才能作为单一可信 USER Image 进入 Responses `input_image` 请求。Chat Completions、Image/Document 混合、重复图片和非 USER 伪造来源继续在请求前 fail-closed。
- 动态标题、验收码和结论只能绘制在 PNG 像素中，不得出现在用户 prompt、Profile system prompt、文件名、runner 参数或其他工具结果。唯一 `notes.create` 参数必须准确恢复这些值，证明来源是供应商视觉理解。
- 最终必须满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；Room PNG Image、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只可依据本轮 `COMMITTED` note ID；临时笔记、Profile、会话和 MediaStore PNG 必须精确删除，原选择恢复，旧 Run 不变而新 Run 审计保留。失败恢复只能依赖预先记录的稳定 ID；原选择缺失时必须拒绝运行，不得从其他 Profile 或会话推断。
- 仅 Redmi 最终真实单项 `1/1`（`32.886s`）已通过，crash buffer 为空；文档 corpus 首次为 `1/1`（`2.979s`），审查后的最终文本 gate 也为 `1/1`（`3.067s`）。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 系统分享 XLSX 到显式 Agent 理解闭环（第 242 阶段，完成）

- XLSX 必须通过既有 `ACTION_SEND` 精确 MIME 和单个小写 `content://` URI 进入草稿，继续复用 8 MB、ZIP/OPC 中央目录、本地头、CRC、展开预算和 `xl/workbook.xml` 根部件校验。验收夹具必须包含可解析的 workbook、worksheet 关系和真实单元格结构，并通过桌面表格工具读取与渲染；应用侧 `extractedText` 与 `pageCount` 保持为空。
- 导入、编辑和输入 `/agent` 均不得自动发送或创建 Run；只有用户明确发送后，XLSX 原始 BLOB 才能作为单一可信 USER Document 进入 Responses Agent 请求。
- 动态标题、验收码和结论只能写入 `xl/worksheets/sheet1.xml` 的工作表单元格，不得出现在用户 prompt、Profile system prompt、文件名、runner 参数或其他工具结果。唯一 `notes.create` 参数必须准确恢复这些值，证明来源是供应商工作簿理解。
- 最终必须满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；Room XLSX Document、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只可依据本轮 `COMMITTED` note ID；临时笔记、Profile、会话和 MediaStore XLSX 必须精确删除，原选择恢复，旧 Run 不变而新 Run 审计保留。
- 仅 Redmi 真实单项 `1/1`（`28.379s`）和文档 corpus 首次 `1/1`（`3.071s`）已通过；写回结果后的最终文本 gate 也为 `1/1`（`3.179s`）。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 系统分享 PPTX 到显式 Agent 理解闭环（第 241 阶段，完成）

- PPTX 必须通过既有 `ACTION_SEND` 精确 MIME 和单个小写 `content://` URI 进入草稿，继续复用 8 MB、ZIP/OPC 中央目录、本地头、CRC、展开预算和 `ppt/presentation.xml` 根部件校验。验收夹具还必须包含可解析的 slide/layout/master 关系，应用侧 `extractedText` 与 `pageCount` 保持为空，不能把结构不完整的 ZIP 或本地伪造文本当作模型理解证据。
- 导入、编辑和输入 `/agent` 均不得自动发送或创建 Run；只有用户明确发送后，PPTX 原始 BLOB 才能作为单一可信 USER Document 进入 Responses Agent 请求。
- 动态标题、验收码和结论只能写入 `ppt/slides/slide1.xml`，不得出现在用户 prompt、Profile system prompt、文件名、runner 参数或其他工具结果。唯一 `notes.create` 参数必须准确恢复这些值，证明来源是供应商幻灯片理解。
- 最终必须满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；Room PPTX Document、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只可依据本轮 `COMMITTED` note ID；临时笔记、Profile、会话和 MediaStore PPTX 必须精确删除，原选择恢复，旧 Run 不变而新 Run 审计保留。
- 仅 Redmi 真实单项 `1/1`（`25.217s`）已通过；文档 corpus 首次为 `1/1`（`3.251s`），写回结果后的最终文本 gate 也为 `1/1`。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 系统分享 DOCX 到显式 Agent 理解闭环（第 240 阶段，完成）

- DOCX 必须通过既有 `ACTION_SEND` 精确 MIME 和单个小写 `content://` URI 进入草稿，继续复用 8 MB、ZIP/OPC 中央目录、本地头、CRC、展开预算和 `word/document.xml` 根部件校验。应用侧 `extractedText` 与 `pageCount` 必须保持为空，不能把本地伪造文本当作模型理解证据。
- 导入、编辑和输入 `/agent` 均不得自动发送或创建 Run；只有用户明确发送后，DOCX 原始 BLOB 才能作为单一可信 USER Document 进入 Responses Agent 请求。
- 动态标题、验收码和结论只能写入 DOCX 的 `word/document.xml`，不得出现在用户 prompt、Profile system prompt、文件名、runner 参数或其他工具结果。唯一 `notes.create` 参数必须准确恢复这些值，证明来源是供应商文件理解。
- 最终必须满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；Room DOCX Document、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只可依据本轮 `COMMITTED` note ID；临时笔记、Profile、会话和 MediaStore DOCX 必须精确删除，原选择恢复，旧 Run 不变而新 Run 审计保留。
- 仅 Redmi 真实单项 `1/1`（`25.147s`）已通过；文档 corpus 首次为 `1/1`（`3.131s`），写回结果后的最终文本 gate 也为 `1/1`。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 系统分享 PDF 到显式 Agent 理解闭环（第 239 阶段，完成）

- PDF 必须通过既有 `ACTION_SEND application/pdf` 和单个小写 `content://` URI 进入草稿，继续复用 8 MB、PDF 签名和 1–50 页校验。验收文件必须为真实可解析 PDF，且应用侧 `extractedText` 保持为空，不能把本地伪造文本当作模型理解证据。
- 导入、编辑和输入 `/agent` 均不得自动发送或创建 Run；只有用户明确发送后，PDF 原始 BLOB 才能作为单一可信 USER Document 进入 Responses Agent 请求。
- 动态标题、验收码和结论只能绘制在 PDF 页面中，不得出现在用户 prompt、Profile system prompt、runner 参数或其他工具结果。唯一 `notes.create` 参数必须准确恢复这些值，证明来源是供应商文件理解。
- 最终必须满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；Room PDF Document、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只可依据本轮 `COMMITTED` note ID；临时笔记、Profile、会话和 MediaStore PDF 必须精确删除，原选择恢复，旧 Run 不变而新 Run 审计保留。
- 仅 Redmi 真实单项 `1/1`（`31.691s`）和文档 corpus gate `1/1`（`2.919s`）已通过。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 系统分享文档到显式 Agent 理解闭环（第 238 阶段，完成）

- 第 237 阶段导入的单文档必须继续停留在可编辑草稿。外部 Intent、文档读取成功、用户编辑说明以及输入 `/agent` 本身均不得自动发送、调用模型、创建 Run、请求审批或执行工具；只有用户明确触发发送才可进入 Agent。
- 前台直接 Agent 仅在 Responses 模式接收一个可信 USER Document；发送前必须沿用现有单附件、USER 来源、文档格式和 Profile/Provider 校验。附件必须先随 USER MessagePart 完整写入 Room，Run 才能建立，确保审批等待或进程恢复仍可按 userMessageId 重建原附件。
- 真实验收命令不得包含动态文档标题、验收码或结论；模型提出的唯一 `notes.create(title, content)` 必须准确包含这些仅存在于文档的事实，证明工具参数来自附件而不是测试 prompt。
- 写入必须经过 `REQUIRES_APPROVAL`，最终满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`。Room USER Document、Tool Message、Ledger 参数和当前 Note Store 回读必须一致。
- 清理只能从本轮 `COMMITTED` 回执恢复 note ID；临时笔记、Profile、会话和 MediaStore 文档必须精确删除，原 Profile/会话选择恢复，旧 Run 不变而本轮 Run 审计保留。
- 仅 Redmi 真实单项 `1/1`（`21.901s`）和文档 corpus gate `1/1`（`3.406s`）已通过。本阶段不修改生产代码、Room v36、Manifest、权限、Tool/Skill、Workflow、后台或附件协议；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## Android 单文档系统分享入口（第 237 阶段，完成）

- Manifest 只可为 `ACTION_SEND` 暴露现有 `DocumentAttachmentPolicy` 支持的 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX 和 XLSX 精确 MIME；不得声明 `ACTION_SEND_MULTIPLE`、通配文档/图片 MIME、GIF、ZIP 或任意二进制类型。
- 分享文档必须只有一个流 URI，且为小写 `content://`。EXTRA_STREAM 与单项 ClipData 携带同一 URI 可视为兼容重复；两者不同、多项 ClipData、缺失 URI、`file://`/大写 scheme、未知 MIME 必须在草稿投影前拒绝。
- `text/plain` 没有流 URI 时继续按最多 20,000 字符的普通文本分享；携带流 URI 时按 TXT 文档处理。文档分享可带最多 20,000 字符的可编辑说明，但说明和附件都不得自动发送、添加 `/agent`、调用模型、创建 Run 或写入 Room。
- 打开分享必须复用既有新会话与草稿冲突确认，然后调用统一 `attachDocument()`。`DocumentAttachmentReader / DocumentAttachmentPolicy` 继续承担 8 MB、UTF-8、PDF 页数、文件名/MIME/签名、OpenXML ZIP/OPC 和文本长度校验；分享解析层不得复制或放宽这些规则。
- 文档读取失败时必须展示“文档不可用”，清除加载态与分享来源且不留下 `pendingDocument`；用户主动移除分享文档也必须清除来源。成功时只保留内存/消息模型中的规范附件，不依赖后续外部 URI 权限。
- 验收必须覆盖精确 Manifest MIME、`ACTION_SEND_MULTIPLE` 拒绝、冲突 URI、Markdown 成功读取、缺失文档失败、文档类型标签、普通文本与图片回归以及无自动发送。聚焦 JVM `17/17`、Debug/AndroidTest APK、仅 Redmi `5/5`（`8.645s`）和文档 corpus gate `1/1`（`3.186s`）已通过；完整 JVM、Lint、Release 和全量 instrumentation 后置。
- 本阶段不新增 Room Schema、Android 权限、持久 URI 授权、后台摄取、多附件、自动 Agent 或工具能力。

## 系统分享文本到受控单日全天日程（第 236 阶段，完成）

- `text/plain ACTION_SEND` 必须继续先投影为普通可编辑草稿；外部 Intent、冷/热启动和来源标签不得自动添加 `/agent`、发送消息、调用模型、创建 Run、请求审批或写入 Calendar Provider。
- “创建全天日程”与既有四个分享转换动作共享纯文本、无附件、未发送、会话已加载且个人任务没有待确认/运行中操作的门禁。五个入口必须在窄屏保持完整可点击，不能因新增入口遮挡来源说明或已有动作。
- `SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft()` 只接受唯一且非空的标题和唯一规范 `yyyy-MM-dd` 日期。缺失或重复字段、标题越界、非法/非规范日期必须停止；出现开始、结束或时区等定时字段时也必须拒绝，不得丢弃具体时间后猜成全天日程。
- 用户点击后只生成包含 `title / date` 两个明确参数的可编辑 `/agent 使用 calendar.create_all_day_event ...` 草稿，同时清除分享来源并退出旧个人任务模式；不得调用 `sendMessage()`。用户仍需明确发送，写入仍需经过当前 Profile、`calendar-create-all-day` Skill、日历读写权限、Registry 校验和逐次审批。
- 成功闭环必须为 Run `COMPLETED`、唯一 Approval `APPROVED`、唯一 ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；当前 Calendar Provider 必须按 operation ID 回读相同标题、UTC 当日零点、排他的次日 UTC 零点、`ALL_DAY=1`、`UTC` 和非重复事实，消息 Tool part 的答案级入口必须绑定同一 `calendar-<正整数>`。
- 验收清理只能从本轮 `COMMITTED` 回执恢复稳定事件 ID，并在删除前再次核对标题和 UTC 全天边界；不得按标题或日期搜索删除。临时 Profile/会话和本轮新建的本地日历需按身份清理，新 Run 审计保留，最近旧 Run 完整摘要不得变化。
- 本阶段不新增 Tool/Skill、Room Schema、Manifest、Android 权限、多日/重复/参与人/提醒、Workflow 或后台日历能力。聚焦 JVM `9/9`、Debug/AndroidTest APK、Redmi 入口 `4/4`（`7.989s`）和真实 Provider `2/2`（`21.034s`）已通过；最终 Run `run-b038b22d-5697-4460-96f7-88c8b8588755` 与 `calendar-92` 满足审批、回读、导航和精确清理契约，缺字段未创建 Run，旧 Run 不变。完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

## 系统分享文本到受控系统日程（第 235 阶段，完成）

- `text/plain ACTION_SEND` 必须继续先投影为普通可编辑草稿；外部 Intent、冷/热启动和来源标签不得自动添加 `/agent`、发送消息、调用模型、创建 Run、请求审批或写入 Calendar Provider。
- “创建日程”与“转为任务 / 保存为笔记 / 保存为记忆”共享纯文本、无附件、未发送、会话已加载且个人任务没有待确认/运行中操作的门禁；四个入口必须在 Redmi 窄屏按两行保持完整可点击。
- `SharedTextAgentDraftPolicy.createCalendarEventDraft()` 只接受唯一且非空的标题、带 UTC 偏移的 ISO-8601 开始时间、结束时间和 IANA 时区。缺少或重复任一字段、标题越界、时间无法解析、结束不晚于开始、固定偏移冒充时区或偏移与时区规则不一致时必须停止，不得让模型补全或猜测。
- 用户点击后只生成包含 `title / start_at / end_at / time_zone` 四个明确参数的可编辑 `/agent 使用 calendar.create_event ...` 草稿，同时清除分享来源并退出旧个人任务模式；不得调用 `sendMessage()`。用户仍需明确发送，`calendar.create_event` 仍需经过当前 Profile、`calendar-create` Skill、日历读写权限、Registry 校验和逐次审批。
- 成功闭环必须为 Run `COMPLETED`、唯一 Approval `APPROVED`、唯一 ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`；当前 Calendar Provider 必须按 operation ID 回读相同标题、起止时间、时区、非全天和非重复事实，消息 Tool part 的答案级入口必须绑定同一 `calendar-<正整数>`。
- 验收清理只能从本轮 `COMMITTED` 回执恢复稳定事件 ID，并在删除前再次核对四字段；不得按标题、日期范围或模糊搜索删除日程。临时 Profile/会话和必要时创建的本地日历需清理，新 Run 审计保留，最近旧 Run 完整摘要不得变化。
- 本阶段不新增 Tool/Skill、Room Schema、Manifest、Android 权限、全天/多日/重复/参与人/提醒、Workflow 或后台日历能力。Android 验收只使用 Redmi；聚焦 JVM `6/6`、Debug/AndroidTest APK、入口 `4/4`、真实 Provider `2/2` 与文档 corpus `1/1` 已通过，完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

## 系统分享文本到显式长期记忆（第 234 阶段，完成）

- `text/plain ACTION_SEND` 必须继续先投影为普通可编辑草稿；外部 Intent、冷/热启动和来源标签不得自动添加 `/agent`、发送消息、调用模型、创建 Run、请求审批或写入长期记忆。
- 只有当前分享为非空纯文本、没有图片/文档、附件读取/消息发送/会话加载均已停止，且个人任务没有待确认或运行中操作时，才显示“保存为记忆”。三个分享转换动作必须在 Redmi 窄屏保持完整可点击，不得因增加入口遮挡来源说明或已有动作。
- 用户点击后只允许通过 `SharedTextAgentDraftPolicy.createMemoryDraft()` 生成明确的 `/agent 使用 memory.remember ...` 草稿，同时退出个人任务模式、清理旧任务结果并移除分享来源标记；不得调用 `sendMessage()`。草稿必须保留原正文顺序，用户仍可编辑或放弃。
- 正式发送后必须继续经过当前 Agent Profile、`personal-memory` Skill、单次记忆召回开关、Registry 业务校验和 `memory.remember` 独立审批。只允许一次写入调用；模型可规范连续空白，但不得删改或补充分享文字事实。敏感信息拒绝、2,000 字符和标签/类型约束沿用现有工具边界。
- 成功闭环必须为 Run `COMPLETED`、唯一 Approval `APPROVED`、唯一 ToolResult `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`，并要求 `memoryIdsUsed`、operation ID、当前 Room 记录与消息 Tool part 的答案级导航身份全部绑定同一 `memory-UUID`。
- 验收清理只能从本轮 `COMMITTED` 回执恢复稳定 memory ID，删除对应临时记忆、撤销文件、临时 Profile 和会话；不得按正文模糊搜索或影响用户记忆。新 Run 审计保留，最近旧 Run 的 Step、Approval、Event 与 Tool Ledger 必须保持不变。
- 本阶段不新增 Tool/Skill、Room Schema、Android 权限、Workflow、后台分享、剪贴板监听或 Intent 自动执行。Android 验收只使用 Redmi；聚焦 JVM、Debug/AndroidTest APK、入口 `3/3`、真实 Provider `1/1` 与文档 corpus `1/1` 已通过，完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

## 提醒结果一次性安全导航（第 233 阶段，完成）

- 只有 `BLOCKED / COMPLETED / FAILED / CANCELLED` ScheduledTask 可以生成点击导航；通知 Intent 不得直接携带或信任 workflowId、scheduledTaskId、workflowRunId、agentRunId 或任意嵌套 Intent。
- 每枚导航 token 必须由应用使用安全随机数生成，至少 256 bit，限制为严格 Base64URL 格式，保存在 `MODE_PRIVATE` 状态中并绑定 Workflow/Task/Run、签发时间和过期时间。本阶段有效期为 24 小时；同一 Task 新通知必须撤销旧 token。
- PendingIntent 必须显式指向 `MainActivity`，默认不可变且单次使用；接收 Intent 若 action、token 格式、URI grant、data、ClipData、selector 或 MIME 边界异常，必须拒绝。MainActivity 继续因系统分享而 exported，不能把 exported 状态、action 或普通 extra 当作可信调用方身份。
- token 必须原子消费且只能成功一次；消费删除未同步落盘时不得返回业务目标。随机伪造、过期、重放、重复持久化身份、损坏状态或旧 token 均不得改变应用导航。
- Activity 冷启动、进程恢复后的 `onCreate()` 和 `singleTop onNewIntent()` 必须使用同一通知导航校验；`onNewIntent()` 先更新当前 Intent。系统分享仍只在首次创建导入，避免导航修复重新引入分享草稿重复导入。
- 私有 Store 消费不是最终业务授权。ViewModel 必须从当前 Room 回读目标 ScheduledTask、Workflow 与非空 Workflow Run，要求 Task 仍为终态、Workflow 仍存在、Task ID、Workflow ID 与 Workflow Run ID 全部一致，且 Run 仍存在并反向绑定同一 Workflow/Task；任何读取/解析异常、删除、悬空引用、重建或漂移均 fail-closed，不得因通知入口崩溃。
- 通过后只能复用既有 Workflow 管理页，自动定位并展开 Workflow，明确高亮当前调度实例与 Workflow Run；同一稳定目标的新一次性导航也必须重新展开，不得被页面先前的本地折叠状态吞掉。不得新建外部 deep link、导出中转 Activity、绕过现有返回栈或从历史通知正文重建结果。
- 本阶段不得修改 Room Schema、Android 权限、Manifest exported 范围、WorkManager、Tool/Skill、审批或后台执行语义。Android 验收只使用 Redmi；聚焦 JVM `19/19`、Debug/AndroidTest APK 与 Redmi 最终组合 `6/6` 已通过，完整 JVM、Lint、Release 和全量 instrumentation 按分级约束后置。

## 系统分享文本到一次性应用内提醒（第 232 阶段，完成）

- 系统分享文本必须继续先成为普通草稿，并由用户依次点击“转为任务”、生成计划和确认计划；确认前不得创建 Workflow、ScheduledTask、WorkRequest、Workflow Run 或 Agent Run。
- 一次性提醒计划必须使用 `PersonalTaskScheduleType.ONCE`，延迟为用户明确要求的 1 至 10080 分钟，页面展示“非精确定时，系统可能延迟执行”；本阶段真实样本限定 1 分钟、单步、唯一 `app.current_time`，不得退化为立即任务、周期规则、Exact Alarm 或 Foreground Service。
- 用户确认后必须复用 `RoomWorkflowRepository.createWorkflowAndOneTimeScheduledTask()` 与 `WorkManagerScheduledTaskScheduler`，先原子写入 Workflow/Task，再关联唯一 WorkRequest；Worker 到点 claim 前不得伪造 Run，后台执行不得绕过现有 Profile、工具白名单、审批门和目标级验证。
- 真实闭环必须满足 ScheduledTask `COMPLETED`、Workflow Run `SCHEDULED / COMPLETED`、步骤 `COMPLETED`、关联 Agent Run `COMPLETED`、唯一 `app.current_time` ToolResult `PASSED`、审批数为 0、目标级结论 `VERIFIED`，并出现绑定该 ScheduledTask 的完成通知。
- 验收必须冻结最近旧 Agent Run、Workflow Run 与稳定终态 ScheduledTask 的摘要并证明不被新提醒改写。临时 Profile、输入会话、后台生成会话和通知需清理，原选择恢复，验收 Workflow 停用；ScheduledTask、Workflow/Agent Run 与 Tool Ledger 审计保留。
- 本阶段不修改生产 Room Schema、调度器、Worker、Tool/Skill 或后台审批语义，只补跨入口真实集成证据；完整 JVM、Lint、Release 与全量 instrumentation 后置，Android 验收只使用 Redmi。

## 系统分享文本到显式个人任务草稿（第 231 阶段，完成）

- `text/plain ACTION_SEND` 必须继续遵守既有草稿冲突确认；分享被用户打开进入编辑器后，必须先退出既有个人任务模式并投影为普通可编辑草稿。冷启动、热启动和二次分享均不得自动请求模型、生成计划、创建 Workflow/Run 或执行工具。
- 只有当前仍是纯文本分享、无图片/文档、无附件读取、未发送、会话未加载且没有待确认/运行中的个人任务时，来源标签才显示“转为任务”。“保存为笔记”保持独立入口；图片分享、用户后续附加附件或来源标记已因编辑清理时不得开放任务转换。
- 用户点击“转为任务”后只允许保留去除首尾空白的原正文、清除分享来源并进入个人任务编辑模式，同时清理上一轮任务失败/完成提示和普通结果；不得调用 `sendMessage()`。用户随后仍需明确生成计划并确认计划，只有确认后才可创建 Workflow 和 Agent Run。
- 本阶段真实计划必须为立即执行的单步 `app.current_time`，不得生成提醒；Workflow Run 必须为 `COMPLETED`、步骤为 `COMPLETED`、目标级结论为 `VERIFIED`，关联 Agent Run 必须为 `COMPLETED`、唯一 ToolResult 为 `PASSED` 且审批数为 0。
- 验收必须冻结最近旧 Agent Run 与 Workflow Run 的稳定摘要并证明新执行不改写旧事实；临时 Profile/会话需清理、原选择需恢复，验收 Workflow 保留审计但必须停用。分享正文、Provider 凭据和 runner 恢复参数不得写入源码、文档、日志或 Git。
- 本阶段不新增生产 Tool/Skill、Room Schema、精确定时、Foreground Service、后台分享自动执行或任意 Intent 能力；按快速迭代分级只执行 AndroidTest 编译/构建、Redmi 聚焦 UI/Activity、真实模型单项和文档 corpus gate，完整 JVM、Lint、Release 与全量 instrumentation 后置。

## 系统分享文本到显式 Agent 笔记草稿（第 230 阶段，完成）

- 第 100 阶段的 `text/plain ACTION_SEND` 必须继续先投影为用户可编辑草稿；外部 Intent、冷启动、热启动和分享来源标签均不得自动添加 `/agent`、调用模型、创建 Run、发送消息或写入笔记。
- 只有纯文本分享、当前无图片/文档、无附件读取、未发送、会话未加载且没有待确认/运行中的个人任务时，来源标签才显示“保存为笔记”。图片分享、用户后续附加图片/文档、空文本或来源标记已因编辑清理时不得开放该动作。
- 用户点击后只允许用 `SharedTextAgentDraftPolicy` 把当前分享正文改写为明确的 `/agent notes.create` 草稿，同时退出个人任务模式并清除分享来源标记；正文必须保持原顺序。用户仍需再次点击发送，`notes.create` 仍必须走现有 Profile/Skill 校验和逐次审批。
- 真实闭环必须在 Redmi 当前可用 Provider 下满足 Run `COMPLETED`、唯一 Approval `APPROVED`、唯一 `notes.create` 结果 `success=true / executorVerified=true / PASSED`、回执 `COMMITTED`，并按 operation ID 从当前 `AgentNoteStore` 回读同一条笔记。清理只能删除该稳定 note ID、临时 Profile 和临时会话，Run/Approval/Tool Ledger 审计保留。
- 重复验收必须冻结最近旧 Run 的完整稳定摘要并证明新执行不改写旧 Run。instrumentation 导致 Provider/Keystore 丢失时，只允许显式 runner 参数恢复本地 `AGENTS.md` 兜底配置；凭据不得进入源码、日志、Run、Tool Ledger、文档或 Git。
- 本阶段不改变 `notes.create`、`local-notes`、Room v36、普通图片分享、Workflow、后台或任意 Intent 能力；按快速迭代分级验证只运行聚焦 JVM、AndroidTest 编译、Debug/Test APK 和 Redmi 单项，完整 JVM、Lint、Release 与全量 instrumentation 后置。

## 设备观察真实前台自然语言闭环（第 229 阶段，完成）

- 验收必须只使用 Redmi `wsvwypiz7xwslvl7`、真实 `MainActivity` 和当前可用 Provider；临时 Profile 的工具白名单必须精确为 `device.open_app / device.snapshot`，Skill 白名单精确为 `device-control`，长期记忆关闭。
- 用户自然语言目标必须只规划 `device.open_app(package_name=com.android.settings) -> device.snapshot`。打开动作必须显示真实审批卡并由用户批准；不得由测试代码代替发送或审批，不得调用 `tap_ref / type_text / swipe / back / home`。
- Run 必须为 `COMPLETED`，唯一审批为 `APPROVED`，两项 ToolResult 均为 `PASSED`；`open_app` 必须具备 `executorVerified=true` 并绑定动作后包名，Snapshot 必须回读 `com.android.settings`、保持 200 节点与 20,000 字符上限并通过隐私过滤。
- 会话 Tool Message 必须与 Tool Ledger 的顺序、参数和结果一致；设备动作投影为 `VERIFIED`，只读 Snapshot 投影为 `READABLE_ONLY`，不得把 `RESULT_READABLE` 错报为 Executor 验证。
- prepare 必须冻结最近旧 Run 的稳定摘要，audit 证明新执行不改写旧 Run；临时 Profile/会话清理后保留新旧 Run、Approval 与 Tool Ledger 审计。生产 Tool/Skill、Room v36、Workflow、后台设备自动化和任意 App 边界不得扩张。
- 本阶段按快速迭代分级验证只运行 AndroidTest 编译/构建与 Redmi 分段单项；完整 JVM、Lint、Release 和全量 instrumentation 后置。第 230 阶段继续选择新的个人 Agent 受控能力切片。

## 设备 Agent 健康只读能力（第 228 阶段，完成）

- 新增 `app.get_device_agent_health`，无参数、`SAFE`、仅前台直接 Agent 可见，不需要审批，不支持 Workflow、后台或无上下文调用。
- 结果只能是“未启用”“未授权”“服务断连”“READY”四种有限状态；不得返回窗口、包名、节点、文本、设备身份、Provider 配置或凭据，也不得触发设备动作。
- 真实 Provider 验收使用 Redmi 当前 Provider 和临时最小 Profile，Run 完成、ToolResult `PASSED`、审批数为 0；临时会话不改变正式 Profile/Provider，审计保持可追溯。
- 本阶段按快速迭代分级验证执行聚焦 JVM、Debug/AndroidTest APK 和单项 Redmi instrumentation；完整 JVM、Lint、Release、全量 instrumentation 留到里程碑或正式发版。

## 进程重启后的审批恢复（第 227 阶段，完成）

- `WAITING_APPROVAL` Run 在进程结束后必须从 Room 恢复到原会话，不得丢失审批卡或回退为普通消息。
- 恢复卡必须标记“进程重建后待恢复”，按钮使用“批准并继续”，并继续绑定原 Run、Approval、ToolCall、工具名和持久化参数。
- 用户批准后必须原地恢复原 Run，满足 `COMPLETED / APPROVED / PASSED / COMMITTED`；不得新建 Run、重新规划或扩大 Profile 工具白名单。
- 验收只在 Redmi 前台执行，临时业务数据按稳定身份清理，Run/Approval/Tool Ledger 审计保留；第 228 阶段转向新的个人 Agent 受控能力。

## Skill 草稿发送与审批真实闭环（第 226 阶段，完成）

- 第 225 阶段生成的 `/agent ...` 草稿只有在用户明确点击发送后才允许创建 Run；Skill 管理页和示例点击不得自动发送、调用模型或执行工具。
- 真实 Run 必须审计当前 Profile 的 `PROFILE_SELECTED`、唯一 `skill.selected` 和 `notes.create` 工具身份；写入工具必须先停在审批卡，不能由测试代码代替用户批准。
- 用户批准后必须同时满足 Run `COMPLETED`、Approval `APPROVED`、ToolResult `PASSED`、执行回执 `COMMITTED`，并核对当前会话 Tool Message 与 Ledger 一致。
- 验收清理只能按稳定回执 ID 删除临时笔记，移除临时 Profile/会话并恢复原选择；Run、Approval、Tool Ledger 审计必须保留。生产 Tool/Skill、Room v36、权限、Workflow、后台与 Shadow 边界不得扩张。
- 第 227 阶段已完成进程重启后的 `WAITING_APPROVAL` 恢复展示与原地批准。

## Agent Skill 试用真实应用壳闭环（第 225 阶段，完成）

- 验收必须使用真实 `MainActivity`，从设置根页进入 Skill 管理并点击当前 Agent Profile 已授权的 SAFE Skill 示例；不得直接调用生产回调或写入测试 Profile/Skill 绕过导航。
- Skill 列表和示例可能位于滚动区域屏幕外，测试必须按真实可见路径滚动后点击；设置覆盖层的合并语义不得成为绕过页面行为的理由。
- 点击前后必须保持选中 Profile、选中会话、当前会话消息和最近 100 条 Run 完整详情不变；只允许对话输入草稿变化为规范 `/agent ...`。
- 不得自动发送、调用模型、创建 Run、执行工具或触发审批。主应用数据、Provider 配置、用户会话和 Profile 不得为验收被清理。
- 第 226 阶段只有在用户明确发送试用草稿后才允许创建 Run，并必须继续验证正式 Skill 选择和逐次审批边界。

## Agent Skill 直接试用入口（第 224 阶段，完成）

- Skill 管理页展开项必须展示自身最多 3 条去重、非空 `triggerExamples`，并明确告知点击只填入对话输入框，不会自动发送或执行。
- 只有 Skill 已启用、当前 Agent Profile 允许该 Skill 及其全部工具、且所需工具仍在当前 Registry 注册时，试用按钮才可用；禁用原因必须在页面可见。
- 页面只提交稳定 Skill ID 和被点击示例；应用壳必须按最新 Skill/Profile/Registry 状态二次核对，示例不再属于当前定义、状态漂移或重复 Skill 身份时 fail-closed。
- 成功只生成规范 `/agent ...` 草稿、关闭个人任务模式并回到对话根页。不得自动调用 `sendMessage()`、模型或工具，不得创建 Run、伪造 Skill 选择或绕过后续审批。
- 本地导入 Skill 示例仍是不可信用户内容，只能作为可见草稿。生产 Tool/Skill 定义、Room v36、权限、Workflow 和后台能力不得因本阶段扩张。
- 第 225 阶段已完成 Redmi 真实应用壳的“设置 -> Skill 示例 -> 对话草稿”闭环；是否发送继续由用户决定。

## 受控单日全天日程真实前台闭环（第 223 阶段，完成）

- 必须只在 Redmi 当前 Provider 下，以自然语言目标驱动最小临时 Profile 唯一调用 `calendar.create_all_day_event`；用户必须在审批卡核对标题和日期后主动批准，不得通过测试代码代替模型规划或审批点击。
- Run、Approval、Tool Ledger 与 Provider 当前事实必须同时成立：Run 为 `COMPLETED`，审批为 `APPROVED`，结果为 `PASSED / COMMITTED`，稳定事件 ID 可从答案级入口回当前 Provider 查看标题、日期、全天、UTC 时区和非重复状态。
- 验收必须比较既有 Run 的完整详情摘要，证明新增执行不会改写旧 Step、Approval、Event 或 Tool Ledger；新 Run 审计保留。
- 清理只能使用本次 `COMMITTED` 回执中的精确 Provider 事件 ID，并在删除前重新匹配本轮随机标题、日期边界、全天、UTC 与非重复状态；不得按标题或日期扫描删除用户日程，身份漂移时必须保留事件并失败。临时 Profile 使用动态夹具 ID，删除前核对前缀/名称；临时 Profile/会话删除后必须恢复原选择。
- 本阶段不修改生产代码、Room v36、旧 Profile、Workflow 或后台边界。第 224 阶段重新选择新的用户可体验个人 Agent 主线，不顺带开放多日、重复、参与人、提醒或后台日程。

## 受控单日全天日程（第 222 阶段，完成）

- 全天创建必须使用独立 `calendar.create_all_day_event(title, date)` 和独立 Skill；不得把旧定时创建工具改成条件参数，也不得让旧 Profile 自动获得新工具。
- `date` 必须是规范 `yyyy-MM-dd`，首版只允许一次性单日全天事件。Provider 必须保存 `ALL_DAY=1`、UTC 当日零点和排他的次日 UTC 零点；不得创建多日、重复、参与人或提醒。
- 工具必须逐次审批、按 ToolCall ID 幂等写入，并在当前 Provider 回读验证标题、UTC 日期边界、全天标记和稳定事件 ID；提交后恢复只读核对，不按标题或日期猜测重复事件。
- 只有应用生成的固定成功结果、`VERIFIED`、原参数标题/日期和唯一合法 `calendar-N` 同时成立时，答案才提供“查看日程”；详情继续从当前 Provider 二次读取。
- 第 223 阶段只做 Redmi 真实自然语言与人工审批验收；旧 Run、Room v36、后台日程和高级日历字段保持不变。

## 前台长期记忆安全删除真实闭环（第 221 阶段，完成）

- Redmi `wsvwypiz7xwslvl7` 真实 Provider Run 必须严格使用同一稳定 memory ID 完成 `memory.search -> memory.get -> memory.delete`，删除前由用户批准，结果需同时具备 `PASSED`、Executor 验证和 `COMMITTED` 回执。
- 删除后的当前长期记忆视图、搜索结果和临时验收数据必须为空；临时 Profile、撤销快照和临时会话可清理，但 Run、Approval 和 Tool Ledger 审计必须保留。
- 验收夹具不得把原会话误判为临时会话；若临时 Run 复用了原空会话，清理只删除消息而不删除原会话记录，并恢复其选中状态。
- 本阶段不新增权限、Workflow、后台执行或 Release 能力。第 222 阶段回到个人 Agent 主线，按分级验证选择下一项前台窄能力。

## 前台长期记忆安全删除（第 220 阶段，完成）

- 生产删除工具必须命名为 `memory.delete`，只接受一个合法稳定 `memory_id`；只有开启长期记忆召回的前台 `DIRECT` Agent 才可发现，不得进入 Workflow、后台、Legacy Run 或旧 Profile。
- 删除前必须在同一 Run 内完成唯一 `memory.search -> memory.get`，并由 `memory.get` 确认唯一候选；删除参数必须与搜索和详情的 ID 完全一致。跳步、多结果、ID 漂移、Run 切换、召回关闭、新搜索或读取失败后均不得沿用旧授权。
- 删除必须逐次审批，并在提交后从当前 Store 验证目标不可见。回执必须绑定当前 ToolCall ID、同值幂等键、稳定 memory ID operation、`COMMITTED` 状态和 `IDEMPOTENT_BY_KEY + DENY` 恢复声明。
- 已提交恢复只能只读核对 operation ledger、载荷哈希和目标当前不可见，不得重新调用删除；用户撤销导致同 ID 再次可见时，旧删除验证必须明确返回 `MEMORY_STILL_EXISTS`。
- operation 记录、主记录删除与 FTS 删除必须位于同一 Room 事务；本阶段不得为该能力新增 Schema 或 Migration，Room 维持 v36。
- 第 220 阶段的隔离测试证据不能替代真实前台验收。第 221 阶段必须只在 Redmi 当前 Provider 下完成自然语言三步链、人工审批、Tool Ledger、记忆页当前不可见和测试夹具精确清理；旧 Run 保持不变。

## 真实前台存储状态 Agent Run（第 219 阶段，完成）

- Redmi 真实验收必须使用当前选中 Provider 和正式 `AgentRunUseCase`；临时 Profile 工具白名单精确限制为 `app.get_storage`，Skill 白名单精确限制为 `storage-status`，长期记忆关闭。
- 用户目标必须驱动模型唯一调用存储状态工具，不得创建审批、调用其他工具或把 Provider URL/API Key、Profile 内部 ID、文件路径、应用数据、设备序列和应用包名带入 Tool Ledger 或最终回答。
- Run 只有在 `COMPLETED`、唯一 ToolResult 为 `success=true / verificationStatus=PASSED` 且审批数为 0 时才算通过；读取失败或结果字段漂移不得被模型总结升级为成功。
- 本阶段只完成 Redmi 单项真实 Provider 验收和 AndroidTest APK 编译，未运行完整 JVM、全量 Lint、Release 或全量 instrumentation；Room v36、旧 Profile/Run、Workflow 和后台边界不变。

## 前台只读存储状态（第 218 阶段，完成）

- `app.get_storage` 必须是无参数、`SAFE`、仅前台可调用的只读工具，`supportsBackground=false`，不申请 Android 权限，不进入 Workflow 或后台设备自动化。
- 结果只能包含当前数据分区总容量、可用空间和使用率三项固定事实；不得读取或返回文件名、路径、应用数据、Provider 配置或设备身份。分区统计无效或读取异常时必须 fail-closed。
- 必须由独立 `storage-status` Skill 在用户询问存储剩余或使用率时选择该工具；旧 Profile、历史 Run 和 Legacy 工具集合不得自动扩权。
- 本阶段的聚焦 JVM、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation 已通过；完整 JVM、全量 Lint、Release 和全量 instrumentation 按快速迭代分级策略后置。

## 真实前台电量/网络双状态 Agent Run（第 217 阶段，完成）

- Redmi 真实验收必须使用当前选中 Provider 和正式 `AgentRunUseCase`；临时 Profile 工具白名单精确限制为 `app.get_battery / app.get_connectivity`，Skill 白名单精确限制为 `battery-status / connectivity-status`，长期记忆关闭。
- 用户目标必须驱动模型调用两项只读工具，不得创建审批、调用其他工具或把 Provider URL/API Key、Profile 内部 ID、设备序列和应用包名带入 Tool Ledger 或最终回答。
- Run 只有在 `COMPLETED`、两项 ToolResult 均 `success=true / verificationStatus=PASSED` 且审批数为 0 时才算通过；网络、电池任一读取失败都不得被模型总结升级为成功。
- 本阶段只完成 Redmi 单项真实 Provider 验收和 AndroidTest APK 编译，未运行完整 JVM、全量 Lint、Release 或全量 instrumentation；Room v36、旧 Profile/Run、Workflow 和后台边界不变。

## 前台只读网络状态（第 216 阶段，完成）

- `app.get_connectivity` 必须是无参数、`SAFE`、仅前台可调用的只读工具，`supportsBackground=false`，不申请 Android 权限，不进入 Workflow 或后台设备自动化。
- 结果只能包含当前是否有活动网络、传输类型和系统判定的互联网可达性三项固定事实；不得返回 SSID、IP 地址、运营商、Provider URL/API Key、设备标识或其他网络配置。网络能力不可用或读取异常时必须 fail-closed。
- 必须由独立 `connectivity-status` Skill 在用户询问联网状态或网络类型时选择该工具；旧 Profile、历史 Run 和 Legacy 工具集合不得自动扩权。
- 本阶段的聚焦 JVM、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation 已通过；完整 JVM、全量 Lint、Release 和全量 instrumentation 按快速迭代分级策略后置。

## 前台只读电池状态（第 215 阶段，完成）

- `app.get_battery` 必须是无参数、`SAFE`、仅前台可调用的只读工具，`supportsBackground=false`，不申请 Android 权限，不进入 Workflow 或后台设备自动化。
- 结果只能包含当前电量百分比、是否充电和供电方式三项固定事实；不得返回设备标识、应用列表、Provider URL/API Key、温度、健康信息或其他系统配置。电池广播不可用、数据无效或读取异常时必须 fail-closed。
- 必须由独立 `battery-status` Skill 在电量、充电或供电问题下选择该工具；旧 Profile、历史 Run 和 Legacy 工具集合不得自动扩权。
- 本阶段的聚焦 JVM、Debug/AndroidTest APK 和 Redmi `wsvwypiz7xwslvl7` 单项 instrumentation 已通过；完整 JVM、全量 Lint、Release 和全量 instrumentation 按快速迭代分级策略后置。

## Redmi 当前 Provider 驱动的 Agent Profile 隐私验收（第 214 阶段，完成）

- `RealProviderAgentProfileInstrumentedTest` 支持 `agentProfileUseStoredProvider=true`，只在 AndroidTest 中读取 Redmi 当前选中 Provider；显式参数模式继续保留，生产代码不增加配置旁路。
- 真实 Run 必须复用正式 `AgentRunUseCase`，临时 Profile 只允许 `agent.get_profile`；结果只能包含 Agent 名称、模型、Responses API 模式和记忆召回状态，Provider URL、API Key、系统提示词、内部 Profile ID 与工具白名单必须不可见。
- Redmi `wsvwypiz7xwslvl7` 单项 instrumentation `OK (1 test)`，Run `run-b9186054-3f0c-405e-ba62-2afd9f4c75f7` 为 `COMPLETED`，ToolResult `success=true / PASSED`；测试包完成后卸载，不扩展旧 Profile、Workflow、后台、权限或 Release 边界。

## 当前应用信息只读验收（第 213 阶段，完成）

- `app.get_info` 只返回当前安装包的应用名称、包名、版本名和版本号，结果固定四字段；Provider、API Key、设备标识、安装来源和其他配置不得进入 Agent 结果。
- Redmi `wsvwypiz7xwslvl7` 必须通过生产 Registry 的定向 instrumentation；该切片不依赖模型网络，不扩大工具、权限、Workflow 或后台能力。
- 第 212 阶段 `agent.get_profile` 的真实 Provider 验收已由第 214 阶段使用 Redmi 当前选中 Provider 重跑通过；本阶段本地只读结果与真实模型隐私证据分别保留。

## 前台 Agent Profile 隐私验收（第 212 阶段，代码完成）

- `agent.get_profile` 只能由当前前台直接 Agent 调用；本阶段定向 AndroidTest 必须复用正式 `AgentRunUseCase`，Profile 工具白名单精确包含该工具，且不得扩大旧 Profile 的工具面。
- 结果只允许返回本次 Run 冻结的 Agent 名称、模型、API 模式和长期记忆召回状态；Provider URL、API Key、系统提示词、内部 Profile ID 和工具白名单必须保持不可见，Room Tool Ledger 也必须满足同一边界。
- 真实 Provider 验收只在 Redmi `wsvwypiz7xwslvl7` 执行。当前设备网络只有 `tun0` 且域名不可解析时，记录为外部网络阻塞，不把失败转换成通过，也不改用 Pixel_9 或扩大测试矩阵。
- 第 214 阶段已重跑该单项并通过；后续进入新的独立个人 Agent 窄能力切片。

## 真实前台历史会话搜索、当前正文与答案级导航验收（第 211 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。临时 Profile 必须精确限制为 `app.list_conversations / app.search_conversations / app.get_conversation`、`conversation-detail` Skill 和长期记忆关闭；不得开放会话写入、设备动作、Provider 写入、Workflow 或后台能力。
- `app.search_conversations` 用于查找旧会话时，必须在排序与应用 `limit` 前排除当前 RunContext 会话 ID。当前用户指令已经写入本轮会话，不得让它凭相同关键词污染历史结果；无 RunContext 的 Store 调用保持既有搜索语义。
- 模型必须先用精确关键词搜索唯一历史会话，再把结果中的同一稳定 `conversation-...` ID 原样传给 `app.get_conversation`。两项工具均为 SAFE、零审批，成功账本必须具备 typed verification `PASSED`；详情只允许当前 Room 的用户/助手正文和既有有界说明。
- 答案级“查看会话”必须只携带可信 Tool part 中的稳定会话 ID，并在点击时重新读取当前 Room。若 ToolResult 生成后正文发生变化，页面必须显示新正文；不得回放旧 ToolResult、发送消息、创建 Run 或触发工具。
- 首次暴露自命中的 Run 必须保持原终态和审计，不因修复而重写。验收结束必须精确删除目标/验收会话、临时 Profile、快照和测试包，恢复原有效会话与 Profile；阶段 Run 和 Tool Ledger 审计继续保留。
- 本阶段只修复历史会话搜索的当前上下文排除，不新增 Room Schema、权限、会话写入、Workflow、后台或 Release 能力；下一阶段转向 `agent.get_profile` 真实前台隐私边界验收。

## 真实前台系统日程删除、当前不可见与清理验收（第 210 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。正式删除 Profile 必须精确限制为 `calendar.search_events / calendar.get / calendar.delete_event` 与 `calendar-delete` Skill，长期记忆关闭；夹具创建必须在 Run 外完成，不得把创建、修改、设备动作或后台工具带入模型工具面。
- 用户目标必须明确连续包含“删除日程”或 `calendar delete`，模型必须严格执行 `search -> get -> delete_event`；搜索只允许唯一命中，get/delete 必须原样复用同一稳定事件 ID，删除还必须使用详情返回的当前指纹和与一次性事件匹配的 `scope=event`。
- 删除必须等待真实应用侧人工审批。成功账本必须同时具备审批 `APPROVED`、三项 typed verification `PASSED`、删除 Executor 验证、`RESTART_REQUIRED` 和同一事件的 `COMMITTED` 回执；无回执路径不得重放 DELETE，也不得把当前不可见猜测为本轮成功。
- 成功后当前 Calendar Provider 必须返回 NotFound，`calendar.delete_event` 结果不得生成详情入口；删除前搜索/详情卡若仍保留历史入口，点击后也必须重新读取当前 Provider 并显示目标已不存在，不得回放历史 Tool 正文。
- 验收结束必须精确删除阶段会话、快照、测试包和仍可见的 marker 夹具，恢复 Room 中仍存在的原会话以及原 Profile 工具/Skill/记忆；阶段 Run、审批和 Tool Ledger 审计必须保留。悬空的旧会话选择不得作为恢复目标。
- 本阶段只验证既有生产删除链的真实人工闭环，不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 能力；下一阶段转向历史会话搜索/正文/答案级查看，重复系列/occurrence 和后台日程代理继续关闭。

## 真实前台系统日程修改、查看与清理验收（第 209 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。正式修改 Profile 必须精确限制为 `calendar.search_events / calendar.get / calendar.update_event` 与 `calendar-update` Skill，长期记忆关闭；不得同时开放创建、删除、设备动作或后台工具。
- 模型必须先以 `calendar.search_events -> calendar.get` 定位唯一稳定事件 ID 和当前指纹，再调用 `calendar.update_event`；每次 UPDATE 都必须携带同一 `event_id`、前一版本 `expected_fingerprint`、`scope=event`、标题、起止时间和 IANA 时区，禁止按标题或模型文本猜测身份。
- 每次修改都必须等待真实应用侧人工审批。成功账本必须具备 `APPROVED`、Executor 写后回读、typed verification `PASSED`、`RESTART_REQUIRED` 和同一事件的 `COMMITTED` 回执；连续 Run 不得改写旧 Run，新的版本必须使用前一提交返回的指纹。
- 答案级“查看日程”只能携带稳定 `calendar-<正整数>` ID，并从当前 Calendar Provider 二次读取标题、起止、全天、时区和重复状态；模型总结、审批参数和历史 Tool 正文不得作为当前事实。
- 验收结束必须在严格核对事件 ID、最终标题、时间、时区和应用 marker 后删除唯一夹具，精确删除阶段会话/快照/测试包，恢复原 Profile 工具、Skill、记忆和会话选择；三条阶段 Run/审批/Tool Ledger 审计必须保留。
- 本阶段只验证既有生产修改链的真实人工闭环，不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 能力；下一阶段再做真实前台日程删除，重复系列/occurrence、后台、精确定时和 Foreground Service 继续关闭。

## 真实前台系统日程创建、查看与清理验收（第 208 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。正式创建时专用 E2E Profile 必须精确限制为 `calendar.create_event` 与 `calendar-create` Skill，长期记忆关闭；不得同时开放日程修改、删除、设备动作或后台工具。
- 用户必须从前台以 `/agent` 明确进入 Agent 执行；缺少前缀的普通聊天不得创建 Run、审批或 Calendar Provider 事件，也不得根据模型文本推断副作用已经发生。
- `calendar.create_event` 必须等待真实应用侧人工审批。成功账本必须同时具备审批 `APPROVED`、Executor 写后回读、typed verification `PASSED`、`COMMITTED` 回执和应用生成的唯一稳定 `calendar-<正整数>` ID；标题、起止时间和 IANA 时区必须与审批参数一致。
- 答案级“查看日程”必须只携带稳定事件 ID，并从当前 Calendar Provider 二次读取标题、起止、全天、时区和重复状态；模型总结和历史 Tool 正文不得作为当前日程事实。
- 验收结束必须在严格核对事件 ID、应用 marker、标题、时间和时区后删除唯一夹具，精确删除阶段会话及误入既有会话的消息，恢复原 Profile 工具/Skill/记忆配置；阶段 Run、审批与 Tool Ledger 审计必须保留。
- 本阶段只验证既有生产创建链的真实人工闭环，不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 能力；验证投入遵循快速迭代分级。

## 真实前台本地笔记删除与失败边界验收（第 207 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。正式删除 Profile 必须精确限制为 `notes.list / notes.search / notes.get / notes.delete` 与 `local-note-delete` Skill，长期记忆关闭；夹具创建可以临时开放 `notes.create`，但删除前必须恢复最小权限。
- 模型必须按 `notes.search -> notes.get -> notes.delete` 使用同一应用生成稳定 note ID；删除必须等待真实应用侧人工审批。成功账本必须具备 `proposed / validated / result / verified`、审批 `APPROVED`、Executor 回读不可见、typed verification `PASSED` 和与同一 note ID 绑定的 `COMMITTED` 回执。
- 未提交删除的重放策略必须保持 `DENY`；只有已有匹配 `COMMITTED` 回执时，恢复链才允许只读确认当前 Store 不可见，不再次执行 DELETE。账本展示的 `IDEMPOTENT_BY_KEY` 只描述已提交结果证据，不能放宽未提交路径。
- 如果删除已经提交，但模型随后继续规划并因重复调用、预算或其他错误结束，Run 必须保留真实失败终态，已提交 ToolResult、审批、回执和 Store 事实不得回滚、覆盖或伪装为整体成功。后续尝试必须创建独立 Run，不能改写旧 Run。
- 验收结束必须刷新本地笔记页确认测试记录不可见，精确删除临时会话/Profile 并恢复原 Profile；阶段 Run 审计必须继续保留。不得修改其他会话、笔记或 Profile。
- 本阶段只验证既有生产删除链的真实人工闭环和失败边界，不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 能力；验证投入遵循快速迭代分级。

## 真实前台本地笔记编辑与版本递增验收（第 206 阶段，完成）

- 真实验收只允许在 Redmi `wsvwypiz7xwslvl7` 执行。正式更新 Run 的临时 Profile 必须精确限制为 `notes.list / notes.search / notes.get / notes.update` 与 `local-note-update` Skill，长期记忆关闭；建立唯一 revision `1` 夹具时可以短暂加入 `notes.create / local-notes`，但必须在更新前恢复最小权限。
- 模型必须严格执行 `notes.search -> notes.get -> notes.update`：搜索只允许唯一命中，详情和更新必须原样传递同一应用生成 note ID；更新参数必须使用详情返回的当前 revision，禁止猜测 ID、跳过详情或以旧版本覆盖。
- `notes.update` 必须通过真实应用侧人工审批。Tool Ledger 必须同时证明审批 `APPROVED`、Executor 验证、typed verification `PASSED` 和 `COMMITTED` 回执；成功结果 revision 必须恰为 `expected_revision + 1`。
- 答案级“查看笔记”和本地笔记页必须从当前 Note Store 二次读取更新后的标题、正文和 revision，不能把模型总结或历史 Tool 正文当作当前事实。验收后必须删除测试笔记、临时会话和临时 Profile，恢复原 Profile；旧 Run 审计保持不变。
- 本阶段只验证既有生产编辑链的真实人工闭环，不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 能力；验证投入遵循快速迭代分级。

## 真实前台本地笔记写入、查看与清理验收（第 205 阶段，完成）

- 真实验收必须只在 Redmi `wsvwypiz7xwslvl7` 执行；临时 Profile `stage205notesui` 只能开放 `notes.list / notes.search / notes.create`，并只选择 `local-notes` Skill。用户必须在前台 `/agent` 输入自然语言目标并通过现有审批卡批准，不能用 Debug 直写替代人工 UI 事实。
- Tool Ledger、完成卡和笔记管理页必须绑定同一 `notes.create` Run 与应用生成的稳定 note ID；查看笔记时从当前 Room/Note Store 回读标题、正文和 revision `1`，不能信任模型自由文本或历史 Tool 正文。
- 验收成功或失败都必须精确清理测试笔记、临时 Profile 和临时会话，并恢复原 Profile；旧 Run、审批记录、用户会话及其他笔记不得改写或删除。删除后的列表必须显示为空或不再包含测试笔记。
- 本阶段只证明真实人工输入、审批、写入验证、当前 Room 查看和清理闭环，不新增生产 Tool/Skill、权限、Room Schema、Workflow、后台能力或 Release 门禁；验证投入遵循分级约束，只使用 Redmi。

## 真实前台记忆写入与答案级 UI 验收（第 204 阶段，完成）

- 真实前台验收必须只在 Redmi `wsvwypiz7xwslvl7` 执行；临时 Profile 只能开放 `memory.remember`，用户输入和审批必须经过现有对话/Room 链路，不得用 Debug 直写替代人工 UI 事实。
- Tool Ledger、完成卡和长期记忆页必须都绑定同一应用生成的 `memory-UUID`、来源 Run 和当前 Room 记录；页面展示的正文、类型和来源只能来自权威 Room 回读，不能信任模型自由文本。
- 验收结束或失败都必须精确删除测试记忆、临时 Profile 和临时会话，恢复原 Profile；旧 Run、审批审计、用户会话和其他记忆不得改写或删除。
- 默认 User-Agent 必须继续由统一请求配置注入并可在 HTTP 日志中核对；日志不得包含 API Key、原始凭据或不必要的记忆正文。
- 本阶段只证明真实人工输入、审批、Room 回读、答案级查看和清理闭环，不新增生产 Tool/Skill、权限、Room Schema、Workflow、后台能力或 Release 门禁；验证投入遵循分级约束。

## 真实长期记忆会话投影验收（第 203 阶段，完成）

- Debug-only `memory_remember_conversation_real` 必须复用正式 `AgentRunUseCase` 和当前 Provider，不能通过直接写 Room 或伪造 ToolResult 宣称普通会话支持；只允许把本轮真实结果短暂写入带专属 ID 的临时会话。
- 临时会话重新从 Room 加载后，必须得到唯一 `memory.remember` Tool part，并同时保留 `VERIFIED`、成功状态、原始已声明参数、稳定 `memoryIdsUsed` 和可解析的 `memoryIdForNavigation()`；任一缺失或漂移都 fail-closed。
- 探针完成或失败都必须按精确会话 ID 原子删除消息部件、消息和会话，并确认查询不到该会话；不得触碰用户会话、记忆、Profile 或 Provider 凭据，日志不得输出正文或参数。
- 本阶段只验证真实结果到普通会话/答案导航的持久化投影，不等同完整人工 UI 输入、审批点击自动化或生产路径扩权；不新增 Tool/Skill、权限、Room Schema、Workflow、后台或 Release 行为。
- Redmi `wsvwypiz7xwslvl7` 真实 Run、聚焦 JVM、三个定向 instrumentation 和 Debug/AndroidTest APK 均通过；完整 JVM、Lint、Release 和全量 instrumentation 后置。

## 真实 Provider 长期记忆写入审批闭环（第 202 阶段，完成）

- 真实 Provider 写入验收必须复用正式 `AgentRunUseCase`、Tool Ledger、Room 审批和 `memory.remember` 执行器；Debug 探针不得旁路调用 Store 伪造 Agent 成功。
- 临时 Profile 只开放 `memory.remember`，调用必须保留本阶段唯一标记且只使用已声明的 `note/type/tags` 参数；结果必须是唯一合法 `memory-UUID`，并同时出现在 `memoryIdsUsed`、提交回执和当前 Room 回读记录中。
- Room 回读必须核对实际 note、规范化类型、标签、启用状态和当前 Run 来源；唯一审批必须为 `APPROVED`，Executor 和 typed verification 必须为通过。模型对可选字段的合法补充可以保留，但任意未声明参数、标记丢失、ID 不一致或正文无法回读均 fail-closed。
- 探针失败或完成都必须按专属前缀/稳定 ID 清理测试记忆，恢复原 Profile；不得把测试正文、Provider 凭据或内部参数写入日志，也不得影响用户已有记忆。
- 本阶段仅新增 Debug 验收入口，不新增生产 Tool/Skill、Android 权限、Room Schema、写入范围、Workflow、后台自动化或 Release 行为；验证按快速迭代聚焦相关 JVM、Debug/AndroidTest APK 和 Redmi 单项真实 Provider 闭环，完整矩阵后置。

## 长期记忆写入结果答案级导航（第 201 阶段，完成）

- `memory.remember` 只有在应用回读验证成功后，才输出唯一 `memory-UUID` 并填充 `memoryIdsUsed`；只有 `VERIFIED`、参数集合合法、固定成功结果外壳、结果 ID 与 `memoryIdsUsed` 唯一一致时才能显示“查看记忆”。
- `note` 必须非空且不超过 2,000 字符；`type` 只能是 `Preference / ProfileFact / Episode / Procedure`；标签最多 10 个、单个最多 50 字符、总长度最多 500 字符。额外参数、非法类型/标签、ID 漂移、重复 ID、结果改写或正文伪造必须 fail-closed。
- `READABLE_ONLY`、失败结果和没有新稳定 ID 的旧 Run 不得生成入口；旧历史事实不得被回填或改写。
- 点击入口必须复用现有记忆管理页，并从当前 Room 二次读取目标；目标不存在、禁用、过期或读取失败时显示当前失败状态，不展示历史回执正文，不重新执行 `memory.remember`。
- 本阶段不新增 Android 权限、Room Schema、Tool/Skill、写入范围、Workflow、后台自动化或 Release 行为；按快速迭代运行聚焦 JVM、必要的 APK 编译、Redmi 当前 Room 导航单项和文档 corpus 单项门禁，完整 JVM、Lint、Redmi 全量 instrumentation 与 Release 后置。

## 本地笔记详情/编辑结果答案级导航（第 200 阶段，完成）

- `notes.get` 只有在参数精确为唯一 `note_id`、ID 为规范 note UUID、结果首行含同一 ID 与规范正 revision、下一行是固定正文安全警示且全文只出现一个合法 ID 时才能显示“查看笔记”。`READABLE_ONLY` 允许只读详情；失败、ID/版本漂移、额外参数、正文伪造或重复 ID 必须 fail-closed。
- `notes.update` 只有在 `VERIFIED` 且参数集合精确包含 `note_id / expected_revision / title / content` 时才能生成入口；结果标题和 ID 必须与请求一致，结果 revision 必须严格等于 `expected_revision + 1`。标题、ID、版本、结果外壳或正文任一漂移均不得导航。
- `notes.create` 属于本地写入副作用，只有 `VERIFIED` 的固定结果才允许入口；`READABLE_ONLY` 写入结果不得生成入口。删除结果不在本阶段新增入口。
- 点击入口必须复用既有本地笔记管理页并从当前 Note Store 二次读取；不得把历史 Tool 正文当成详情权威，不得创建 Run、重新执行工具、绕过审批或扩大编辑权限。
- 本阶段不新增 Android 权限、Room Schema、Tool/Skill、Workflow、后台自动化或 Release 行为；按快速迭代运行聚焦 JVM 与 Debug/AndroidTest APK 编译，完整 JVM、Lint、Redmi instrumentation 和 Release 后置。

## 日程创建/修改结果答案级导航（第 199 阶段，完成）

- `calendar.create_event` 成功结果必须由应用输出规范标题与唯一 `calendar-<正整数>` ID；只有 `VERIFIED` 且标题、四项请求参数和固定单行结果外壳一致时才能显示“查看日程”。
- `calendar.update_event` 只有 `VERIFIED`、参数集合精确、`scope=event`、请求 ID 与结果 ID 一致、审批前指纹有效、结果新指纹有效且不同于旧指纹时才能显示入口。额外参数、非法时间字段长度、换行注入、ID/指纹漂移或额外正文均须 fail-closed。
- `calendar.delete_event` 成功后不得显示日程详情入口；失败、`READABLE_ONLY` 或未验证写入也不得形成入口。普通模型文本不能制造 Tool 卡或导航。
- 点击后必须复用当前 Calendar Provider 二次读取页；事件被删除、撤权或 Provider 不可用时显示当前失败状态，不得展示写入结果正文作为详情。
- 本阶段不得新增权限、日程创建/修改范围、审批豁免、Room Schema、Workflow、后台自动化或 Release 行为。

## 答案级系统日程详情导航（第 198 阶段，完成）

- 只有成功且验证状态非失败的 `calendar.list_events`、`calendar.search_events` 或 `calendar.get` Tool 结果，在参数契约、应用生成的动态标题和唯一规范 `calendar-<正整数>` ID 同时一致时，才能显示“查看日程”。空结果、多结果、额外参数、日期/关键词标题漂移、非规范/溢出 ID、正文伪造或详情 ID 与参数不一致不得形成入口。
- 列表/搜索仅在应用结果明确只有一条事件时提供入口；点击只传稳定事件 ID。Activity 重建可以保存这一个一次性目标，返回设置根页必须清除，不能让旧目标污染下次进入。
- 详情页必须使用现有 `AndroidCalendarEventReader` 按当前 Calendar Provider 二次读取，只显示标题、事件 ID、起止时间、全天、时区和是否重复；不得读取或展示地点、描述、参与人、组织者、账户或历史 Tool 正文。
- 事件已删除、权限撤销、Provider 不可用、读取失败或目标非法时必须显示当前失败状态，不得回退工具摘要或模型文本。页面不得自动申请权限、写入日程、创建审批或触发 Agent Run。
- 本阶段不新增 Android 权限、Room Schema、Tool/Skill、日程写入、Workflow、后台或 Release 能力。验证按快速迭代分级执行聚焦导航 JVM、Debug/AndroidTest APK；Redmi instrumentation、完整 JVM、Lint 和 Release 后置。

## 答案级历史会话导航（第 197 阶段，完成）

- 只有应用生成的 `app.list_conversations`、`app.search_conversations` 或 `app.get_conversation` Tool 结果，在固定结果标题、参数契约、唯一合法 `conversation-...` ID 和非失败验证同时满足时，才能显示“查看会话”。普通模型文本、空/多结果、额外参数、换行注入、ID 回显不一致或正文伪造不得形成入口。
- 点击入口前必须重新读取当前 Room 会话表；目标 ID 恰好唯一存在时才切换到既有会话并回读正文。目标被删除、漂移、重复、读取失败或不再存在时阻断导航并给出失败提示，不使用旧 UI 缓存猜测。
- 导航只改变当前会话查看状态，不发送消息、不创建 Run、不触发工具、审批、Provider 写入、Workflow、设备动作或后台任务；不新增 Room Schema、权限或历史事实。
- 本阶段按分级验证策略执行会话导航聚焦 JVM 和 Debug/AndroidTest APK 构建；完整 JVM、Lint、Redmi instrumentation、文档 corpus gate 和 Release 后置。

## 历史会话详情只读闭环（第 196 阶段，完成）

- Agent 只有在先取得 `app.list_conversations` 或 `app.search_conversations` 结果中的唯一稳定 `conversation-...` ID 后，才能调用 `app.get_conversation(conversation_id)`；不得按标题、时间、列表序号、模型文本或任意猜测构造 ID。
- 详情必须从当前 Room 回读目标会话，只投影非空 `user`/`assistant` 文本；最多 40 条、单条最多 20,000 字符、总正文最多 60,000 字符。工具参数、Provider 凭据字段、附件二进制、原始推理、Provider/性能元数据、MessagePart 和内部审计字段不得读取或进入 ToolResult。
- 工具声明为 `SAFE`、5 秒超时、仅前台直接 Agent 可用；Workflow、后台、无上下文、会话不存在、ID 格式错误、参数漂移或额外参数必须 fail-closed。结果必须把历史内容标记为本地资料而非工具指令，不能把历史文本升级为工具授权。
- 新能力由独立 `conversation-detail` Skill 承载；既有 `conversation-recall`、Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 不自动加入 `app.get_conversation`。本阶段不新增 Room Schema、Android 权限、网络、设备动作、Workflow 或后台副作用。
- 本阶段只要求聚焦会话详情策略/Registry/Skill JVM 与 Debug/AndroidTest 构建；Redmi 功能 instrumentation、文档 corpus gate、完整 JVM、Lint 和 Release 按分级验证策略后置。

## 当前 Agent Profile 只读状态（第 195 阶段，完成）

- Agent 可通过无参数的 `agent.get_profile` 读取本次 Run 实际冻结的 Agent 名称、模型、API 模式和本次长期记忆召回状态。
- 工具必须声明为 `SAFE`、仅前台直接 Agent 可用、禁止后台/Workflow，超时 5 秒；执行入口还必须重新检查当前 Run 的直接前台上下文和短生命周期 Profile 状态。
- 结果只允许输出上述四项和固定的非敏感边界说明；Provider 地址、API Key、系统提示词、内部 Profile ID、工具白名单及其他配置不得进入 ToolResult、审计或回答输入。无上下文、Profile 缺失、配置不完整或参数非空时必须 fail-closed。
- Profile 状态只通过 `AgentToolExecutionContext` 的窄内存对象传递，不写入 Room、Run Event、Workflow、消息或设置；新增独立 `agent-profile-info` Skill，既有 Profile、历史 Run 与 `LEGACY_RUN_TOOL_NAMES` 不自动扩权。
- 本阶段只要求聚焦 Registry/Skill JVM 与 Debug/AndroidTest 构建；完整 JVM、全量 Lint、Redmi 功能 instrumentation、文档 corpus gate 和 Release 按分级验证策略后置。

## 只读应用信息工具（第 194 阶段，完成）

- Agent 可通过无参数的 `app.get_info` 读取当前安装的小灵应用最小身份信息：应用名称、包名、版本名和版本号。
- 工具必须声明为 `SAFE`、支持后台、超时 5 秒；应用侧从当前 `PackageManager` 读取权威安装事实，不从 Provider 配置、构建历史或模型文本猜测版本。
- 结果只能包含上述四个字段；Provider、API Key、设备序列号/标识、安装来源、签名和其他配置不得进入 ToolResult、审计或回答输入。PackageManager 不可用、包读取失败或参数非空时必须 fail-closed。
- 新能力由独立 `app-info` Skill 承载；已有 Profile、历史 Run 和 `LEGACY_RUN_TOOL_NAMES` 不自动加入 `app.get_info`，新 Profile 或用户显式编辑的 Profile 才能使用。不得新增 Room Schema、权限、设备动作或后台副作用。
- 本阶段只要求聚焦 Registry/Skill JVM、Debug/AndroidTest 构建和文档 corpus gate；完整 JVM、全量 Lint、Redmi 功能 instrumentation 和 Release 按分级验证策略后置。

## 任务中心关联 Run 双向查看与安全导航（第 193 阶段，完成）

- 选中的关联 Run 必须展示来源 Run；只有来源 Run 在当前任务中心历史中恰好存在一个时，才提供“查看来源 Run”操作。来源被历史裁剪、目标缺失或 ID 不唯一时，只展示不可导航的状态，不猜测或重新读取未知历史。
- 选中的来源 Run 若有多个关联 Run，只在当前历史中按 `createdAt` 唯一确定最新目标时提供“查看关联 Run”；最新时间并列或关系不明确时不提供跳转入口。
- 点击关联入口前再次核对当前任务中心列表中的唯一目标，随后切换到全部筛选并滚动到目标 Run；导航只调用现有选择动作，不创建 Run、不触发重试、审批、模型规划、工具执行或 Provider 写入。
- 本阶段只增加任务中心查看体验与投影/Compose 边界测试，不新增 Room Schema、Repository 查询、恢复执行、Workflow、后台能力或其他设备动作；完整 JVM、Lint、Release 和全量 instrumentation 继续按分级验证策略后置。

## 确认后关联新 Run 的 Room 历史保留验收（第 192 阶段，完成）

- 任务中心确认后创建的新 Run 必须在 Room 中持久化 `retryOfRunId`，来源 Run 可以是 `FAILED` 或其他已落定终态，但不得被改写为可执行或新的恢复事实。
- 来源 Run 的 `AgentRun` 终态字段、全部 `AgentStep`、Approval 记录、Tool Call/Tool Result、执行回执和 Run Event 必须在创建关联 Run 前后逐项保持不变；新 Run 不复制来源步骤、工具结果、审批或事件。
- 关闭并重建 `RoomAgentRunRepository` 后，来源历史与 `retryOfRunId` 关系仍必须可读；新 Run 从独立 `QUEUED` 状态开始，后续工具和审批由新 Run 自己产生。
- 本阶段只增加 Room 持久化交叉验收，不新增自动恢复、旧协程/Executor 重放、Provider 写入、Room Schema、Workflow 或后台能力；完整 JVM、Lint、Release 和全量 instrumentation 继续按分级验证策略后置。

## 任务中心重新发起边界统一（第 191 阶段，完成）

- 对最新 `run.recovered` 中存在 `restartDisposition` 的终态 Run，任务中心必须显示专用“创建新 Run”入口，不能以普通“重试”让用户误以为会继续旧执行栈。
- 用户确认前必须从最新 Room detail 重新核对 `restartDisposition.code` 和证据指纹；处置码不一致时拒绝，证据漂移时刷新确认卡，不把旧确认当成新授权。
- 专用弹窗必须明确保留旧 Run 的终态与审计记录，不恢复旧模型协程、Executor 或工具；新 Run 中的写入工具仍需重新审批。
- 本阶段只统一投影、确认和证据校验，不新增恢复执行器、自动重放、Provider 写入、Room Schema、Workflow 或后台能力。

## 启动恢复失败可见投影（第 190 阶段，完成）

- 启动收敛完成后，提示必须基于 Room 中最新的 Recovery 元数据统计无法原地恢复的 Run，不根据启动前的中间状态或文本错误推断。
- 可见提示只说明失败/取消数量、无法原地恢复的数量和下一步边界；不展示目标、原始错误、Run ID、工具参数或密码。
- 继续跳转 Agent 任务中心查看详情；如需继续，只能在任务中心中经过重试证据和必要确认创建关联新 Run，不恢复或重放旧 Run。
- 本阶段只修改启动提示投影和证据测试，不新增恢复执行通道、自动重试、Provider 写入、Room Schema、Workflow 或后台能力。

## 失败日程修改 Run 终态恢复验证（第 189 阶段，完成）

- 已收敛为 `FAILED` 的 `calendar.update_event` Run 在应用或 Repository 重建后必须保持终态；启动恢复不得将其改写为可执行、可重放或新的失败事实。
- 无 `COMMITTED` 执行回执且定义为 `RESTART_REQUIRED + DENY` 的日程修改，恢复策略必须返回 `RESTART_REQUIRED / RUN_STATE_NOT_RESUMABLE`；不得根据 Provider 当前状态推测原 UPDATE 是否成功。
- `closeInterruptedRuns()` 只处理可收口的中断 Run，对已失败 Run 必须返回零变更；原 Step、Tool Result 和 Event 证据不变，不得新增 `run.recovered`。
- 本阶段只增加 Room instrumentation 证据，不新增生产恢复通道、UPDATE 重放、Provider 写入、Room Schema、Workflow 或后台能力。

## 真实 Provider 审批后漂移失败闭环（第 188 阶段，完成）

- Redmi 真实模型必须仍严格执行 `calendar.search_events -> calendar.get -> calendar.update_event`；测试夹具只能在 `calendar.update_event` 审批请求已写入 Room 后修改同一 Provider 事件，模拟用户审批等待期间的外部漂移。
- 条件 UPDATE 必须拒绝旧指纹，Run 收敛为失败；失败结果不能携带 `COMMITTED` 回执或 `executorVerified=true`，不能覆盖外部新标题/事实。
- 失败探针必须回读 Provider 确认外部漂移仍存在，并按事件 ID、marker 精确清理事件、临时日历和 Profile；日志不得输出标题、指纹、参数或凭据。
- 本阶段只验证真实失败事实，不新增恢复重放、后台日历、重复事件、occurrence、权限或 Room Schema 能力。

## 日程修改中断恢复边界（第 187 阶段，完成）

- `calendar.update_event` 的未提交、执行中断、回执缺失或回执状态不是 `COMMITTED` 时，恢复策略必须返回 `RESTART_REQUIRED`，且 `ToolNotCommittedReplayPolicy` 固定为 `DENY`；不得因为事件当前看起来已变化而猜测 UPDATE 是否成功。
- 只有原 ToolCall、同一事件 operation ID、幂等键、`scope=event` 和 `COMMITTED` 回执全部一致时，恢复入口才可在前台 DIRECT 上下文执行只读 `verifyUpdateCommitted()`；该入口不得调用 `updateOrReadBack()`。
- Registry/Writer/Room 重建后仍只按稳定事件 ID回读当前 Provider；重建后的验证不得依赖进程内缓存，也不得新增 UPDATE、审批或新的模型规划事实。
- 本阶段只加固契约与测试证据，不新增权限、Room Schema、Skill/Profile、Workflow、后台日历能力或任意 App 修改能力。

## 真实 Provider 受控系统日程修改闭环（第 186 阶段，完成）

- 必须仅在 Redmi 使用设备当前已选择的真实模型 Provider，通过唯一 `calendar-update` Skill 严格执行 `calendar.search_events -> calendar.get -> calendar.update_event`；临时 Profile 只能开放这三个工具，不得包含创建、列表、删除、设备动作或其他 Skill。
- 搜索必须原样使用 Debug 夹具唯一关键词；`calendar.get.event_id` 必须等于搜索结果稳定 ID，修改调用必须原样复用同一 `event_id`、详情返回的当前 `expected_fingerprint`、`scope=event` 和探针指定的完整标题、带偏移起止时间、IANA 时区。
- Room Tool Ledger 三项结果必须 `success=true` 且 typed `PASSED`；修改结果必须 `executorVerified=true` 并携带同一稳定事件 ID 的 `COMMITTED` 回执。Room 中只能出现一条 `calendar.update_event` 审批且必须收敛为 `APPROVED`；Provider 回读必须确认四个目标字段和新指纹。
- 夹具只能在 Agent Run 外使用正式 Calendar writer 创建，并使用 stage186 专属 marker。成功、模型失败、审批失败、断言失败或进程中断时都按 Provider 事件 ID 精确清理；只有本轮新建的应用本地日历才允许删除，原 Profile 必须恢复，临时 Profile 必须移除。
- Debug 日志不得包含 API Key、事件标题、工具参数、事件指纹或工具正文，只记录 Run ID、状态、Skill、工具名和布尔结论。本阶段不得修改生产 Registry、Skill、Writer、Reader、Room Schema、旧 Profile/Run、权限或后台边界。
- 验证只要求 Debug APK、Redmi 真实模型核心路径和文档 corpus 单项；JVM、Lint、Release APK、AndroidTest APK 和全量 instrumentation 按分级策略后置。

## 受控系统日程修改（第 185 阶段，完成）

- 新增 `calendar.update_event(event_id, expected_fingerprint, scope, title, start_at, end_at, time_zone)` 与独立 `calendar-update` Skill。模型必须先通过 `calendar.search_events -> calendar.get` 定位唯一目标，再原样传递稳定事件 ID、当前版本化指纹和 `scope=event`；不得按标题、时间、列表序号或模型文本猜测身份和版本。
- 工具只允许前台 DIRECT Run、逐次审批并要求 `READ_CALENDAR + WRITE_CALENDAR`。允许修改的字段仅为完整标题、开始时间、结束时间和时区；起止时间必须带 UTC 偏移并与 IANA 时区一致。空标题、时间逆序、无变化请求及字段缺失必须拒绝。
- 当前只支持一次性非全天事件。`scope=series`、`scope=occurrence`、Provider 当前为重复事件或全天事件时必须明确拒绝，不得创建 exception event、改为系列修改或把全天事件隐式转换为定时事件。
- UPDATE 必须用审批前详情的 `_ID / DELETED / ALL_DAY / TITLE / DTSTART / DTEND / EVENT_TIMEZONE / RRULE / RDATE` 组成条件选择。审批期间发生外部改名、改期、改变重复规则、删除或其他快照漂移时，影响行数必须为 0；不得覆盖新事实。
- 写入影响恰好一行后，必须按同一事件 ID 回读标题、起止时间和时区。四个目标字段全部一致才形成绑定同一事件的 `COMMITTED` 回执，并返回由新 Provider 快照计算的版本化指纹；回读不可用或字段不一致不能宣称修改成功。
- 恢复契约固定为 `RESTART_REQUIRED + DENY`。无回执、非 `COMMITTED`、回执错配或未提交路径不得重放 UPDATE；只有匹配回执、`scope=event` 与前台 DIRECT 上下文同时成立时允许只读回读验证。恢复验证不得调用 UPDATE。
- 旧 Skill、Profile、历史/Legacy Run、Workflow 和后台不自动获得修改能力；Room v36、重复系列/occurrence 修改、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型保持不变。验证只要求聚焦 JVM、Debug/AndroidTest APK、Redmi 真实 Provider 单项与文档 corpus；完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

## 真实 Provider 受控系统日程删除闭环（第 184 阶段，完成）

- 必须仅在 Redmi 使用设备当前已选择的真实模型 Provider，通过唯一 `calendar-delete` Skill 严格执行 `calendar.search_events -> calendar.get -> calendar.delete_event`；临时 Profile 只能开放这三个工具，不得包含创建、列表、设备动作或其他 Skill。
- 搜索必须原样使用 Debug 夹具唯一关键词；`calendar.get.event_id` 必须等于搜索结果稳定 ID，删除调用必须原样复用同一 `event_id`、详情返回的当前 `expected_fingerprint` 和 `scope=event`，不得由模型猜测、重算或改写。
- Room Tool Ledger 三项结果必须 `success=true` 且 typed `PASSED`；删除结果必须 `executorVerified=true` 并携带同一稳定事件 ID 的 `COMMITTED` 回执。Room 中只能出现一条 `calendar.delete_event` 审批且必须收敛为 `APPROVED`，最终 Provider 回读必须为 NotFound。
- 夹具只能在 Agent Run 外使用正式 Calendar writer 创建，并使用 stage184 专属 marker。成功、模型失败、审批失败、断言失败或进程中断时都按 Provider 事件 ID 精确清理；只有本轮新建的应用本地日历才允许删除，原 Profile 必须恢复，临时 Profile 必须移除。
- Debug 日志不得包含 API Key、事件标题、工具参数、事件指纹或工具正文，只记录 Run ID、状态、Skill、工具名和布尔结论。本阶段不得修改生产 Registry、Skill、Writer、Reader、Room Schema、旧 Profile/Run、权限或后台边界。
- 验证只要求 Debug APK、Redmi 真实模型核心路径和文档 corpus 单项；JVM、Lint、Release APK 和全量 instrumentation 按分级策略后置。

## 受控系统日程删除（第 183 阶段，完成）

- `calendar.get` 必须返回 `calendar-event-v1-<sha256>` 事件指纹；指纹绑定 `_ID / TITLE / DTSTART / DTEND / ALL_DAY / EVENT_TIMEZONE / RRULE / RDATE` 与派生重复状态的规范值，条件删除再额外要求 `DELETED=0`。删除只能接受同一详情读取返回的稳定 `calendar-<Events._ID>` 和当前指纹，不得按标题、时间、列表序号或模型文本猜测目标。
- 新增 `calendar.delete_event(event_id, expected_fingerprint, scope)` 与独立 `calendar-delete` Skill。工具只允许前台 DIRECT Run、逐次审批，并同时要求 `READ_CALENDAR + WRITE_CALENDAR`；不得进入 Workflow、后台自动化或 Legacy Run。
- `scope=event` 只允许一次性事件，`scope=series` 只允许删除整个重复系列；`scope=occurrence` 必须明确拒绝，不能静默降级为系列删除。scope 与 Provider 当前重复状态不匹配时必须 fail-closed。
- 删除必须通过 Provider 条件选择再次绑定审批时看到的全部指纹字段。审批期间发生外部改名、改期、改变重复规则、删除或其他字段漂移时，影响行数必须为 0 并返回冲突；成功后必须按同一事件 ID 回读不可见才能形成 `COMMITTED` 回执。
- 恢复契约固定为 `RESTART_REQUIRED + DENY`。只有可信 `COMMITTED` 回执与当前 ToolCall、稳定 ID、请求指纹和 scope 一致时，才允许只读确认目标仍不可见；没有回执、`NOT_COMMITTED`、`UNKNOWN` 或回执错配均不得再次 DELETE，也不得把当前不可见猜测成成功。
- 旧日历 Skill、Profile、历史/Legacy Run 不自动获得删除能力；日程修改、occurrence 修改/删除、后台、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续关闭。验证只要求聚焦 JVM、Debug/AndroidTest APK、Redmi 真实 Provider 单项及文档 corpus；完整 JVM、Lint、Release 和全量 instrumentation 按分级策略后置。

## 真实 Provider 系统日程详情闭环（第 182 阶段，完成）

- 必须仅在 Redmi 使用设备当前真实模型 Provider，选择唯一 `calendar-detail`，并严格执行 `calendar.search_events -> calendar.get`；不得调用列表、创建、设备动作或其他工具。
- 搜索参数必须原样使用 Debug 夹具的唯一关键词；`calendar.get.event_id` 必须精确等于搜索结果返回的稳定 `calendar-<Events._ID>`，不得从标题、时间或模型文本猜测。
- Room Tool Ledger 中两项结果必须 `success=true` 且 typed `PASSED`；搜索结果必须包含唯一事件和同一稳定 ID，详情必须包含当前 Provider 的 ID、标题、时区和重复状态，SAFE 链不得产生审批记录。
- 临时 Profile 必须只开放两个只读工具和 `calendar-detail`。事件夹具允许在 Agent Run 外用正式 writer 创建，但必须使用专属幂等 marker；成功、模型失败、断言失败或启动恢复竞态都按 Provider 返回事件 ID 精确清理，并恢复原 Profile。只有本轮新建的小灵本地日历才允许删除。
- Debug 日志不得包含 API Key、事件标题、工具参数或详情正文。本阶段不得修改生产 Registry、Skill、Room Schema、Android 权限申请、旧 Profile/Run、日程写删工具或后台边界。
- 验证只要求 Debug/AndroidTest APK、Redmi 真实 Provider 核心路径和文档 corpus 单项门禁；完整 JVM、Lint、Release APK 和全量 instrumentation 按分级策略后置。

## 系统日程稳定身份与权威详情读取（第 181 阶段，完成）

- `calendar.list_events / calendar.search_events` 必须返回从 `CalendarContract.Instances.EVENT_ID` 取得、绑定 `Events._ID` 的稳定 `calendar-<正整数>`。模型只能使用工具已经返回的 ID，不得从标题、时间、列表序号或模型文本猜测 Provider 内部身份。
- 新增 SAFE `calendar.get(event_id)` 与独立 `calendar-detail` Skill。Skill 必须先调用 `calendar.search_events`，且只有唯一结果与用户目标一致时才读取详情；搜索摘要不得冒充当前详情。
- 详情必须按单一 `Events/<id>` URI 从当前系统 Provider 回读，只返回标题、事件起止时间、全天、时区和 RRULE/RDATE 是否重复。地点、描述、参与人、组织者、日历账户和其他 Provider 字段不得进入 projection、结果类型、日志或模型上下文。
- ID 非法或溢出、事件不存在/已删除、Provider 返回空、权限在调用间撤销或查询异常时必须 fail-closed。重复事件身份指向系列，本阶段不承诺 occurrence 级修改语义。
- 工具仅允许前台并要求 `READ_CALENDAR`，不进入 committed-effect verification。旧 Skill、Profile、历史 Run、Legacy Run 不自动扩权；日程修改/删除、后台 Workflow、精确定时、Foreground Service 和高级生态继续关闭。
- 验证包括公开 Registry/Skill 契约、非法 ID 与权限/删除失败边界、Debug/AndroidTest APK，以及仅 Redmi 上创建一条临时事件后验证 `list -> search -> get` 同一 ID 与详情回读并精确清理。完整 JVM、Lint、全量 instrumentation 与 Release 按分级验证策略后置。

## 答案级长期记忆导航（第 180 阶段，完成）

- 只有可信 `memory.search / memory.get` Tool part 可以生成“查看记忆”入口。入口必须要求工具成功、验证状态非失败、工具名和参数严格合法、`memoryIdsUsed` 只有一个标准 `memory-UUID`，并且应用生成的搜索条目或详情首行指向同一 ID。
- 普通模型文本、用户文本、旧工具自由文本、失败结果、非法参数、多条搜索结果、重复或错配 ID 均不得生成入口。点击只能传递解析出的稳定 ID，不得携带 Tool 正文、历史记忆快照或模型补全内容。
- 应用必须在进入长期记忆管理页前重新读取当前 Room。记录存在时应清空可能遮挡目标的搜索/筛选，选中当前记录并保证目标卡可见；记录已删除时必须阻断导航、移除缓存中的目标正文与选中态，并给出不可用提示，不得回退到历史正文或猜测其他记录。
- 本阶段不新增 Agent 工具、Skill、Profile 权限、审批、Room Schema、Android 权限、后台能力或记忆写入行为。真实 Provider 已在第 179 阶段证明 `search -> get`，第 180 阶段只验收答案入口、当前 Room 二次读取和 UI 定位。
- 验证应包括解析拒绝单测、Tool 卡点击测试及真实 Room 的存在/删除导航测试；Android 安装与 instrumentation 只允许使用 Redmi。完整 JVM、Lint、全量 instrumentation 与 Release 继续按分级验证策略后置。

## 真实 Provider 长期记忆详情闭环（第 179 阶段，完成）

- 必须仅在 Redmi 使用设备当前真实 Provider，选择 `personal-memory-detail`，严格执行 `memory.search -> memory.get`；搜索关键词必须原样传递，详情参数必须等于唯一搜索结果中的稳定 `memory-UUID`，不得调用额外工具或猜测 ID。
- Room Tool Ledger 中两项结果都必须 `success=true`、typed `PASSED`，并且 `memoryIdsUsed` 精确等于同一目标 ID。详情必须包含当前 Store 正文与本地数据边界；SAFE 链不得产生审批记录。
- Debug 夹具必须使用专属来源与唯一内容；成功或失败都删除记忆主记录和 FTS 索引、移除临时 Profile 并恢复原 Profile。历史残留清理必须覆盖禁用和过期夹具，不得触碰用户记忆或 API Key。
- 覆盖安装后立即运行与启动恢复并发导致 Run 被提前收敛时，应记录为验收编排失败并重新在稳定进程中执行，不得把终态追加步骤错误伪装成工具失败，也不得留下夹具。
- 本阶段不修改生产 Registry、Room Schema、记忆治理、审批、后台权限、发布基线或高级生态；完整 JVM、Lint、全量 instrumentation 和 Release 仍按里程碑统一执行。

## 按稳定 ID 读取长期记忆详情（第 178 阶段，完成，已于第 179 阶段验证）

- 新增 `memory.get(memory_id)` 与独立 `personal-memory-detail` Skill。工具必须为 SAFE 只读能力，可在现有后台只读边界内使用；既有 `personal-memory`、旧 Profile、历史 Run 和 legacy 工具集合不得自动扩权。
- `memory.search` 必须继续返回当前可直接回答的全文结果，并为每条结果附带稳定 `memory-UUID`。`memory.get` 只接受标准 ID，内容必须从当前 `AgentMemoryStore` 回读，不得根据模型文本、历史摘要或自由输入恢复记忆。
- 详情只允许当前启用且未过期的记忆；不存在、禁用和过期必须使用同一不可用结果，且不能泄露正文、状态差异或删除历史。成功结果必须记录实际 memory ID，并明确正文属于本地数据而非工具指令。
- 单次记忆召回关闭时，规划工具面和执行入口必须同时阻断 `memory.search / memory.get`，且不得访问 Store。Profile 仍需显式允许新工具和 Skill。
- 本阶段不新增 Room Schema、Android 权限、审批、记忆写入/编辑/删除、跨会话工作区或后台批量处理。第 179 阶段已在 Redmi 单独完成真实 Provider 的 `search -> get` 规划与 ID 传递验收。

## 周期计划真实使用与可信答案闭环（第 177 阶段，完成）

- 必须在 Redmi 使用真实 Provider 分别验证 `tasks.list -> tasks.inspect -> tasks.pause` 与 `tasks.list -> tasks.inspect -> tasks.resume`，并核对两次逐项 Room 审批、Tool Ledger typed verification、Room schedule/task 状态和 WorkManager 绑定；Debug 夹具、临时 Profile 与系统工作项无论成功失败都必须清理。
- 暂停验收必须从真实已入队的 DAILY/WEEKLY 未来实例开始，确认规则保留且停用、未来 Task/WorkRequest 已取消、正在运行和旧事实不被改写。恢复必须确认规则重新启用、仅有一个当前时间之后的新 Task 与有效 WorkRequest，且不补跑暂停窗口。
- 可信暂停/恢复结果应在普通前台 Agent 会话生成受限终态，并刷新 Workflow、ScheduledTask 与周期规则快照。结果解析必须要求唯一控制 execution、`success=true`、typed `VERIFIED`、严格 `{name}` 参数和与工具动作一致的应用生成首行；模型文本、重复 execution、状态错配、换行名称和未验证结果必须 fail-closed。
- 可信工具卡应提供“查看任务”，点击只传递清理后的任务名，并从当前 Room Workflow 快照做唯一精确名称二次解析；删除、重命名、同名或缺失时不得猜测历史内部 ID。
- 本阶段不得扩大到 Workflow/后台调用、一次性计划控制、精确定时、Foreground Service、系统日程修改/删除、MCP、远程 Channel、多 Agent 或本地模型；Room v36、旧 Profile 和历史 Run 保持不变。

## 应用内周期计划暂停/恢复（第 176 阶段，完成）

- 新增 `tasks.pause(name)`、`tasks.resume(name)` 与独立 `task-schedule-control` Skill。两项工具只允许前台直接 Agent、需要逐次用户确认且不支持后台；旧 Profile、历史 Run、`task-overview/retry/cancel` Skill 和 legacy 工具集合不得自动扩权。
- 目标必须由 `tasks.list / tasks.inspect` 返回的精确唯一任务名解析，并且存在唯一 DAILY/WEEKLY 规则。一次性计划、同名任务、工作流停用、规则与实例身份不一致、缺失指针、终态指针或系统调度证据缺失都必须 fail-closed。
- 暂停必须保留周期规则行，只取消仍为 `SCHEDULED` 的未来实例并清空规则的未来指针；已经进入 `RUNNING / STOP_REQUESTED` 的实例及其 Workflow/Agent Run 继续按原链收敛，不得被暂停改写。
- 恢复必须复用原 schedule ID，并根据当前时间、原墙上时间和时区只物化一个未来实例；暂停期间错过的周期不得补跑。重复暂停/恢复按当前 Room 状态幂等，不得生成重复 Task 或 WorkRequest。
- 恢复只有在未来 Task 已绑定 WorkRequest 且回读一致时才能报告成功。系统入队或关联失败必须把本次新 Task 收敛，并把规则回滚为无未来指针的暂停态，使用户可以重新恢复。
- 本阶段不修改 Room v36 Schema、历史 Run、一次性计划、前台手动 Run、设备 Workflow、Foreground Service、精确定时或系统日程；真实 Provider 自然语言闭环与可信结果导航已由第 177 阶段完成。

## 受控系统日程创建（第 175 阶段，完成）

- 新增 `calendar.create_event(title, start_at, end_at, time_zone)` 与独立 `calendar-create` Skill。工具为 `REQUIRES_APPROVAL`、`supportsBackground=false`，需要 `READ_CALENDAR + WRITE_CALENDAR`；用户必须在前台逐次确认，旧 Profile、历史 Run 和既有只读日历 Skill 不自动获得写能力。
- 首版只允许一次性非全天事件。`start_at / end_at` 必须是带 UTC 偏移的 ISO-8601 时间，`time_zone` 必须是有效 IANA 时区；两端偏移必须与该时区在对应时刻的规则一致，结束时间必须晚于开始时间。地点、描述、参与人、组织者、提醒、重复规则和全天事件不在输入面。
- Provider 写入必须包含 `CALENDAR_ID / TITLE / DTSTART / DTEND / EVENT_TIMEZONE / ALL_DAY=0`，并用 `CUSTOM_APP_PACKAGE + CUSTOM_APP_URI` 保存 ToolCall 稳定标记。重放先按标记精确回读，载荷漂移时拒绝；写入后按事件 ID 回读标题、起止时间和时区，只有完全一致才可声明成功并签发 `COMMITTED` 回执。
- 目标日历只选择 `CALENDAR_ACCESS_LEVEL >= CONTRIBUTOR` 且 `SYNC_EVENTS=1` 的日历，优先 primary、visible 和稳定 ID。系统日历表为空或没有可写日历时，按 Android 官方 LOCAL sync-adapter 契约创建唯一的本地“小灵”日历；该日历不接入、不读取也不向 Agent 暴露账户信息。
- 日历访问页必须把只读授权和创建授权分开呈现，权限只能由用户主动点击触发。创建工具不会在后台自动执行，不修改或删除已有日程；Room v36、Workflow 与 Foreground Service 边界不因本阶段改变。

## 个人事项简报（第 174 阶段，完成）

- 应新增独立 SAFE `personal-briefing` Skill，在用户明确要求个人简报并提供笔记检索关键词时，组合现有 `calendar.list_events`、`tasks.list`、`notes.search` 与 `notes.get`；不得修改原 `day-overview` 或让旧 Profile 自动获得新组合能力。
- 简报必须先读取有界近期日程和当前任务清单，再原样使用用户关键词搜索本地笔记；只有唯一命中时才可把搜索结果中的稳定 note ID 传给 `notes.get`。搜索摘要不得冒充全文，不得猜测 ID、正文、日程或任务事实。
- 四项 Tool Result 必须来自同一 Run 且成功通过 typed 验证；最终回答必须明确区分日程、任务和笔记来源。任一来源不可用、笔记未命中或不唯一时，应标明缺失来源，而不是从历史对话补猜。
- 新 Skill 必须继续要求用户主动授予 `READ_CALENDAR`，并保持前台、SAFE、零审批和只读执行；笔记正文必须标记为本地数据而非工具指令。
- 本阶段不新增 Agent 工具、Android 权限、Room Schema、写入、后台 Workflow、任意 App 或多来源恢复协议；当前四工具上限内不继续加入记忆、知识库和设备动作。

## 版本化本地笔记编辑闭环（第 173 阶段，完成）

- 用户本地笔记详情页应提供编辑入口，标题和正文使用当前完整内容初始化；正文输入区默认至少五行。保存必须携带详情读取到的 revision，取消不得修改 Room，保存期间不得重复提交。
- 应新增仅前台、需要逐次用户确认的 `notes.update(note_id, expected_revision, title, content)` 与独立 `local-note-update` Skill。Agent 必须先定位唯一笔记并用 `notes.get` 读取稳定 ID、完整正文和 revision；旧 Profile、旧 Skill、历史 Run 与后台 Workflow 不得自动获得编辑权限。
- Room v35→v36 必须把旧笔记迁移为 `revision=1`。更新只能在 ID、非 tombstone 和 `expected_revision` 同时匹配时提交；成功后 revision 必须恰好递增 1 并回读标题、正文和版本，版本漂移、删除或不存在不得产生覆盖副作用。
- 每次成功提交并产生内容变化的编辑应在同一事务写入独立 operation 账本，绑定 ToolCall 幂等键、note ID、期望/结果 revision、请求载荷哈希和结果哈希。标题和正文均未变化的请求不得伪造提交回执；它仍由普通 Tool Ledger 记录为未执行编辑。同一已提交调用使用同一载荷时只回读原结果，载荷漂移必须拒绝；恢复期若 operation 已存在，只能验证当前结果，不得再次执行 UPDATE。
- 本阶段不开放批量编辑、后台笔记写入、任意文件修改或任意 App 能力。Debug 夹具和临时 Profile 无论成功失败都必须精确清理，不扫描或改写用户笔记。

## Agent 受控删除本地笔记（第 172 阶段，完成）

- 应新增仅前台、需要逐次用户确认的 `notes.delete(note_id)`；`note_id` 必须是当前 `notes.list/search/get` 返回的标准稳定 ID，畸形、不存在、已删除或未经授权的目标不得产生副作用。
- Agent 必须先定位唯一笔记并用 `notes.get` 核对正文，再删除同一个 ID。独立 `local-note-delete` Skill 不得修改既有只读/创建 Skill，旧 Profile 与历史 Run 不得自动获得删除权限。
- 生产删除必须复用 Room tombstone，清空用户正文但保留 ID 与原创建幂等键；成功前必须回读 list/search/get 不可见，历史 `notes.create` 重放必须继续失败。
- 成功删除必须生成绑定 ToolCall 和 note ID 的 `COMMITTED` 回执。只有该回执完整存在时才可恢复期只读验证；未提交、回执缺失、参数或 operation 漂移时不得再次执行 delete，也不得宣称成功。
- 本阶段不新增 Room Schema、Android 权限、后台笔记写入、批量删除、编辑或任意 App 能力。Debug 夹具必须使用固定幂等键精确清理，不能扫描或物理删除用户笔记。

## 真实 Provider 搜索并读取笔记全文（第 171 阶段，完成）

- Debug 验收必须读取手机当前已保存 Provider，创建唯一且可精确清理的测试笔记；API Key 不得进入广播参数、探针日志或测试夹具。
- 临时 Profile 必须显式启用 `local-note-detail` 与其完整只读工具集合；实际 Agent Run 必须严格执行 `notes.search -> notes.get`，不得猜测 ID、调用额外工具或生成审批。
- 验收必须从 Room Tool Ledger 核对调用顺序、搜索关键词、稳定 ID 传递以及两项 `success=true / PASSED`；`notes.get` 结果必须包含测试全文和本地数据边界，模型最终回答不能代替工具事实。
- 无论运行成功或失败，都必须精确硬删除测试笔记、恢复原 Profile 并删除临时 Profile；生产 Profile、Room Schema、Release 工具面和历史 Run 不得改变。

## 按稳定 ID 读取本地笔记（第 170 阶段，完成，已于第 171 阶段验证）

- 应提供 SAFE `notes.get(note_id)`，允许 Agent 在 `notes.list / notes.search` 返回稳定 ID 后读取当前笔记正文；工具支持后台只读调用，但不得新增写入、审批或后台副作用。
- `note_id` 必须是标准 41 字符 `note-UUID`，畸形、空值和额外参数必须拒绝。不存在与 tombstone 必须使用同一失败结果，不得泄露已删除笔记曾经存在或恢复其正文。
- 成功结果必须从当前 Store 回读，标题中的换行需归一化，正文输出最多 20,000 字符，超过时显式标记截断；正文必须明确标记为本地数据而非工具指令。
- 现有 `local-notes` Skill 和历史 Profile 不得因新增工具而隐式改变权限；新增独立 SAFE `local-note-detail` Skill，既有 Profile 只有显式启用新工具和 Skill 后才可使用。
- 本阶段不修改 Room Schema、`AgentNoteStore` 契约、`notes.create` 审批/幂等/恢复语义或任意 App 边界。第 170 阶段落地时按用户要求未验证，第 171 阶段已补齐真实 Agent 使用闭环。

## 小灵 v0.1.16 发布基线

`v0.1.16` 使用 `versionCode=17`、Room v35，并保持 `minSdk=26 / targetSdk=36` 与既有 `releaseLocal` 签名配置。发布范围汇总 `v0.1.15` 后第 128 至 169 阶段：完整个人 Agent 主链、目标级验证、应用内提醒、任务恢复/诊断/重试/取消、只读日历、本地笔记，以及启动中断 Run 与答案级任务/笔记导航。

本次发布不得扩大既有安全边界：后台设备自动化、任意 App、恢复旧执行栈、生产 answerability enforcement、精确定时、Foreground Service、MCP、远程 Channel、多 Agent 和本地模型继续关闭。按用户明确要求，本轮只执行必要的 `assembleRelease`，不额外运行 JVM、完整 Lint、Debug/AndroidTest、Redmi 安装或 instrumentation；该边界必须在 Release Notes 和验证报告中披露。

## 创建笔记后的答案级导航（第 169 阶段，完成）

- `notes.create` 只有成功并完成应用侧回读验证后，答案下才可显示“查看笔记”；结果必须附带稳定 note ID，且不得改变既有审批、幂等和失败语义。
- 入口必须同时满足完整 `title/content` 参数、typed `VERIFIED`、标题与请求精确绑定、标题无换行和全文唯一合法 note ID；回读失败、标题漂移、重复/非法 ID、普通模型文本不得制造入口。
- 点击只携带 note ID，详情仍从当前本地笔记 Store 重新读取；本阶段不新增写工具、Room Schema、后台能力或任意 App 权限。

## 答案级本地笔记导航（第 168 阶段，完成）

- 对成功的 `notes.list / notes.search` 单结果，答案下可提供“查看笔记”，但必须同时满足固定应用结果标题、合法参数、非失败验证状态和唯一标准 `note-UUID` 条目；多结果、空结果、失败、非法 ID、伪造文本和其他工具不得出现入口。
- 点击只传递稳定 note ID，应用进入本地笔记管理页后重新从当前 Store 读取完整正文；笔记已删除、tombstone 或不存在时必须显示当前事实，不得恢复历史摘要或创建新笔记。
- 本阶段只增加只读导航投影，不新增 `notes.get/delete/edit`、不改变 Room Schema、写入审批、后台能力、任意 App 能力或历史 MessagePart 结构。

## 中断筛选空状态与历史回退（第 167 阶段，完成）

- “已中断”筛选没有 `FAILED / CANCELLED` Run 时，必须说明当前没有可复盘的中断 Run，并明确恢复入口不会重放工具。
- 如果完整历史存在其他 Run，必须提供“显示全部”回退；没有任何历史时不得展示无效回退动作。
- 回退只改变当前页面筛选，不刷新、不执行、不重试、不取消、不修改 Room，也不改变其他筛选的空状态语义。

## 恢复入口聚焦中断 Run（第 166 阶段，完成）

- 启动恢复提示进入 Agent 任务中心时，初始筛选必须只展示已落定的 `FAILED / CANCELLED` Run；活动、待审批、可恢复和已完成 Run 不得混入该视图。
- 用户可以手动切换到全部、处理中、需确认、可重试和已完成；设置页直接进入任务中心时默认仍为全部，刷新数据不得重置用户已经选择的筛选。
- 该筛选只改变任务中心展示，不改变 Run、Workflow、审批、重试、取消或后台事实，不携带内部 ID，也不新增工具权限。

## 启动恢复提示直达任务中心（第 165 阶段，完成）

- 启动中断 Run 提示可提供一次性“查看任务”按钮；按钮只由应用生成的恢复结果动作驱动，普通模型文本和普通操作结果不能制造入口。
- 点击必须先刷新当前 Agent Run 历史，再进入现有任务中心；不得携带内部 Run/Workflow ID、目标、错误原文或工具正文，也不得重放工具或改变 Run 终态。
- 提示自动消失或用户不点击时不产生导航；普通设置、备份和网络请求结果继续使用无动作的轻提示。
- 本阶段只增加 UI 导航投影和可选结果动作，不新增工具、权限、Room Schema、后台执行或高级生态能力。

## 启动中断 Run 用户提示（第 164 阶段，完成）

- 应用启动必须先按现有策略识别可恢复 Run，并通过正式 Repository 入口收敛其他旧进程中间态；提示只能读取收敛后的 Room 终态，不能根据启动前状态推断失败或取消。
- 本次实际收敛为 `FAILED / CANCELLED` 的 Run 应合并为一个一次性提示，展示分类数量、明确不重放工具并引导 Agent 任务中心；不得包含目标、会话、Run ID、错误原文、工具参数或结果正文。
- 待审批、已提交待验证和已验证可继续 Run 不得进入该提示，也不得因提示逻辑被关闭。提示不修改旧 Run、恢复策略、Workflow 对账、Room Schema、工具权限或后台能力。

## 取消结果后的任务快照刷新（第 163 阶段，完成）

- 普通 Agent 会话收到可信 `tasks.cancel` 终态后，应用应主动刷新 Workflow、ScheduledTask、周期规则和 Run 投影，保证随后打开任务中心看到当前取消状态。
- 刷新门禁必须复用唯一 `tasks.cancel`、成功、typed `VERIFIED` 和应用生成稳定取消文案；模型文本、失败/未验证结果、重复 execution 与其他工具不得触发刷新。
- 本阶段不新增工具、权限、Room Schema、后台执行或前台手动 Run 取消能力；旧 Run 与取消副作用保持不变。

## 取消结果任务中心导航（第 162 阶段，完成）

- 成功且 typed `VERIFIED` 的 `tasks.cancel` Tool part 可显示“查看任务”入口；入口只消费唯一 `name` 参数和应用生成的取消状态首行，不扫描普通 assistant 文本。
- 点击入口不得携带内部 Task/Workflow ID；应用壳必须重新读取当前 Workflow 列表，只有一个精确名称匹配时才定位，否则进入通用任务中心。
- 失败验证、`READABLE_ONLY`、多个参数、名称含换行、模型伪造结果、删除/重命名/同名任务均 fail-closed，不重发 Agent、不取消/重试任务、不修改 Room。
- 本阶段只增加会话到任务中心的导航投影，不改变任务取消副作用、审批、旧 Run、Workflow 或后台能力。

## 受控任务取消会话终态（第 161 阶段，完成）

- 普通 `/agent` 在成功 Run 收尾时，只有唯一的 `tasks.cancel` Tool execution 同时满足 `success=true`、typed `VERIFIED`，并且结果包含应用定义的稳定取消状态，才追加会话终态摘要。
- 摘要必须与 Agent assistant 结果在同一个会话快照中持久化；它只展示清理后的任务名、取消/停止状态和旧运行记录不变，不展示内部 ID、原始 ToolResult、审批参数或模型自由文本。
- `SCHEDULE_CANCELLED / STOPPED / STOP_REQUESTED / AlreadyCancelled` 使用稳定的用户文案；`WORKFLOW` 来源、重复取消、`READABLE_ONLY`、验证失败、多个取消调用和无法识别的结果必须不生成摘要。
- 该投影只改变用户可见会话结果，不改变 `ScheduledTask`、Workflow、Agent Run 或 StopCoordinator 的副作用语义，也不为后台任务增加新权限。

## 受控任务取消（第 160 阶段，完成）

- `tasks.cancel(name)` 是独立的前台直接 Agent 工具，必须独立请求用户确认；它不属于 `tasks.retry` 的隐式扩权，也不允许后台 Agent 或前台手动 Workflow Run 调用。
- 工具只接受 1 个精确任务名称，由 Room 解析当前唯一活动 ScheduledTask。任务缺失、同名 Workflow、多活动实例、停用、状态已变化或名称不一致时必须拒绝并保持原事实不变；不向模型暴露内部任务/Run ID。
- `SCHEDULED` 取消必须原子写入 `CANCELLED`；`RUNNING / STOP_REQUESTED` 先写入 `STOP_REQUESTED` 并经正式停止编排、WorkManager cancel 与 fallback 收敛。迟到 Worker、模型响应或重复请求不能把取消后的任务改回成功；重复取消从持久化状态返回幂等结果。
- 取消只影响目标 ScheduledTask 及其关联后台 Workflow 链，前台手动 Run 保持不变。结果必须来自已提交的 Room 状态，不使用模型文本推断。
- 本阶段验证聚焦 JVM、Debug/AndroidTest APK、Redmi Room instrumentation 与 Redmi 真实 Provider；不要求完整 JVM、全量 Lint、Release 或全量 instrumentation。

## 受控任务重试用户可见终态（第 159 阶段，完成）

- `tasks.retry` 的成功 ToolResult 只证明关联重试提交与 typed verification 已通过，不得把“已排队”解释为 Workflow 已完成。
- 前台宿主必须使用 Repository 返回或重新读取的关联 Run 终态生成用户消息。`COMPLETED` 显示完成结果；`BLOCKED / FAILED / CANCELLED` 显示稳定状态、旧 Run 不变和下一步任务中心入口，不向用户暴露原始异常。
- 用户可见投影只允许任务名、复用步骤数和稳定恢复文案；不得接收或输出 Workflow/Run/Step ID、原始错误、步骤正文、工具参数或结果正文。任务名中的换行必须归一化，显示上限保持 100 字符。
- 失败、阻塞或取消不能恢复旧模型协程、旧 Executor 或旧 Workflow 后续步骤，也不能重放已完成前缀；旧 Run、旧步骤和已提交副作用事实保持不变。
- 同一关联 Run 在进程内最多启动一次，只有终态才生成最终结果；`QUEUED / RUNNING` 不生成完成摘要。本阶段不新增任务取消/停止工具、Room Schema、后台能力或权限。

## 真实 Provider 受控任务重试闭环（第 158 阶段，完成）

- Debug-only 验收入口必须创建可清理、可停用的失败 Workflow 夹具，不修改生产工具边界，不把测试任务留在可再次执行状态。
- 使用当前已配置 Provider，让真实 Agent 严格按 `tasks.list -> tasks.inspect -> tasks.retry` 顺序调用；三项 Tool Ledger 必须成功且 typed verification 为 `PASSED`，审批和幂等提交回执必须可回读。
- 真实 Provider 回执只有在 `TaskRetryLaunchPolicy` 通过后才能接管关联前台 Workflow。来源 Run 保持 `FAILED`，来源步骤、结果和副作用保持不变；成功前缀只复用为 `SKIPPED`，首个未完成步骤必须由关联前台 Workflow 真实执行并通过验证。
- 验收结束必须恢复原 Agent Profile、删除临时 Profile，并停用夹具 Workflow；日志只记录稳定结果，不输出 API Key 或工具正文隐私字段。该阶段只运行聚焦 JVM、Debug/AndroidTest APK、Redmi 真实闭环和文档 corpus gate。

## 受控任务重试（第 157 阶段，完成）

- 用户明确要求重试任务时，前台直接 Agent 才能在 `REQUIRES_APPROVAL` 审批后调用 `tasks.retry(name)`；Workflow 内部递归调用、后台调用和旧 Profile 默认扩权均禁止。
- 工具按去除首尾空白后的精确名称匹配唯一 Workflow，只读取该任务当前最新 Run；仅 `BLOCKED / FAILED / CANCELLED` 且通过 `WorkflowRunRetryPolicy` 的 Run 可进入重试，不能回退历史失败 Run。停用任务、完成态、活动态、缺失步骤证据、同名或身份漂移必须拒绝。
- ToolCall ID 派生确定性新 Workflow Run。相同 ToolCall 只在新 Run 仍为 `QUEUED`、为当前最新 Run 且步骤仍为 `SKIPPED / PENDING` 时回读同一提交；已启动或状态漂移不得再次启动。
- 新 Run 只复用来源 Run 的成功前缀，使用 `SKIPPED / reusedFromStepId` 保存关联；来源 Run、来源步骤、结果和已有副作用保持不变。工具正文不返回 Workflow/Run/Step ID、原始错误或步骤正文。
- 前台宿主从 Room Tool Ledger 重读调用与结果，要求 `executorVerified=true`、typed verification `PASSED`、幂等回执一致且新 Run 可启动后，才接管 Workflow；模型总结不参与启动判断。聚焦 JVM `130/130`、Debug/AndroidTest APK 与 Redmi Room `8/8` 通过。

## 任务诊断答案级导航（第 156 阶段，完成）

- 成功的 `tasks.inspect` Tool part 必须在结果卡提供“查看任务”入口，使用户无需离开答案上下文重新寻找任务；点击只负责导航，不重新发送消息、重试工具、执行或修改任务。
- 入口只能由可信 Agent 工具事实生成：工具名必须为 `tasks.inspect`，执行必须成功且验证状态不能为 `FAILED`，参数键必须严格只有非空且不超过工具 Schema 上限 100 字符的 `name`，结果首行必须严格为“任务最近运行”。普通模型文本、失败结果、额外参数、超长参数或伪造结果头都不得生成入口。
- 历史 Tool part 只携带用户可见任务名称，不持有或推断 Workflow ID。点击时必须用当前 Workflow 列表做区分大小写的唯一精确名称匹配；删除、重命名、缺失、尾部空格或同名均不得猜测，统一降级打开通用 Workflow 管理列表。
- 唯一命中时必须打开 Workflow 管理页并请求定位目标；降级时仍进入同一管理页，用户可自行选择。该切片不新增 Room Schema、Agent 工具、Profile/Skill 权限、任务写操作、后台能力或 Android 权限。
- 聚焦 `TaskInspectionNavigationTest`、Debug/AndroidTest APK 构建与仅 Redmi 的 `ConversationPageInstrumentedTest 9/9` 通过。真机现有生产会话没有 `tasks.inspect` 消息，因此未以篡改数据库或临时扩权方式伪造视觉样本。

## 任务最近运行只读诊断（第 155 阶段，完成）

- Agent 必须先通过 `tasks.list` 获得用户可见任务名称，再用 `tasks.inspect` 按去除首尾空白后的精确名称读取最近一次运行；名称不存在时明确说明，同名任务必须拒绝选择任意一项。
- 详情只允许返回任务名称/目标/启停、最近 Run 状态、手动或计划触发、可用的起止时间，以及步骤序号/状态。失败原因只能收敛为“等待用户处理、存在失败步骤、系统中断、无法进一步分类、已取消、证据不完整”等稳定分类。
- Workflow/Run/Step/ScheduledTask 内部 ID、原始错误、步骤目标/详情、输入输出快照、模型文本、工具参数、ToolResult 正文和审批内容不得进入 `tasks.inspect` 结果或最终回答依据。
- `tasks.inspect` 为 SAFE、`supportsBackground=false`，只读当前 Room 事实；不修改、取消、执行或重试任务，不新增 Profile 静默扩权、Room Schema、UI 页面、Android 权限或后台能力。
- `task-overview` Skill 必须在用户追问任务失败或执行进度时先列清单再查看精确名称，只能依据受限投影回答，不根据旧会话或原始错误猜测。聚焦 JVM `57/57`、Debug/AndroidTest APK、Redmi Room `5/5` 和真实 Provider 双工具 Run 均通过。

## 本地笔记受控删除（第 154 阶段，完成）

- 用户只能从已回读的本地笔记详情发起删除，必须经过独立二次确认；删除进行中禁止重复确认、取消或其他列表操作。该操作是用户直接管理本机数据，不新增 `notes.delete` Agent 工具。
- 生产删除必须清空标题和正文，使 list/search/get 都不再暴露该笔记；同时必须保留原 note ID 和 ToolCall 幂等键。历史 `notes.create` 使用同一幂等键重放时必须明确失败，不能恢复用户已撤回的内容。
- “标题和正文同时为空”只作为不可见 tombstone。正常 `notes.create` 继续拒绝空标题或空正文；Debug 测试数据清理可继续使用精确 ID 硬删除，但不能作为生产用户删除实现。
- 删除成功后立即从当前 UI 快照移除目标并刷新当前列表或搜索结果。若删除已提交但刷新失败，必须同时保留成功提示并明确报告“删除成功、列表刷新失败”，不能把已提交副作用误报为删除失败。
- 本阶段不新增 Room Schema、编辑、分页、批量删除、后台写入、Agent Profile/Skill 权限或 Runtime。聚焦 `XiaoLingToolRegistryTest 40/40`、Debug/AndroidTest APK 通过；Redmi ViewModel `OK (2 tests)`、页面 `OK (2 tests)`、Room tombstone `OK (1 test)`。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 本地笔记只读管理入口（第 153 阶段，完成）

- 设置根页必须提供“本地笔记”独立入口，用户可以查看 Agent 已写入 `agent_notes` 的最近记录、按标题或正文关键词搜索，并点击条目查看完整正文；长期记忆和知识库仍是不同数据域，不能在 UI 中混为同一能力。
- 最近列表与搜索结果继续复用 `AgentNoteStore` 的最多 10 条边界；详情必须按稳定 note ID 回读。页面标题和返回入口位于滚动列表之外，笔记较多时不能随内容滚出视口。
- 该入口严格只读：不提供创建、编辑、删除、批量操作或后台入口，不新增 Room Schema、权限、Agent 工具、Profile/Skill 白名单或 Runtime。第 152 阶段的 `notes.create` 审批、幂等与回读语义保持不变。
- UI 使用独立 `LocalNoteManagementViewModel` 注入 `RoomAgentNoteStore`，不把列表、搜索和详情状态继续塞入主 `XiaoLingViewModel`；加载、空结果、错误和详情关闭必须形成稳定状态。
- 定向导航/设置 JVM、Debug/AndroidTest APK 构建成功；仅 Redmi `wsvwypiz7xwslvl7` 的 ViewModel `OK (1 test)`、页面 `OK (2 tests)`、设置根 `OK (5 tests)`、真实 Room list/search/get `OK (1 test)`。未运行完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 本地笔记写入闭环（第 152 阶段，完成）

- 用户以自然语言要求记录一条本机笔记时，Agent 必须在当前 Profile/Skill 白名单内规划 `notes.create`；写入前仍需要审批，不能由模型文本直接宣称已保存。
- `notes.create` 继续使用 ToolCall ID 幂等键，并在写入后按 operation ID 回读标题和正文；回读不一致时 Run 必须保持失败/未验证，不能升级为完成。
- 本阶段 Redmi 真实 Run `run-66b689fb-6ff3-410f-a851-e0f91765047a` 的 Room 审批为 `APPROVED`，Tool Ledger 为 `success=true / executorVerified=true / PASSED`，写入后按标题搜索回读成功。
- Debug 验收探针使用临时 `local-notes` Profile 和持久化审批 gate，只在 Debug source set 存在；测试笔记和临时 Profile 在验收结束后删除，Run/审批审计保留。未新增 Room Schema、后台笔记操作或笔记删除用户能力。
- 聚焦 `RoomAgentNoteStoreInstrumentedTest#debugProbeCleanupDeletesOnlyTheRequestedNote` 在 Redmi 为 `OK (1 test)`；Debug/AndroidTest APK 构建成功。本阶段不执行完整 JVM、全量 Lint 或 Release。

## WorkManager 长任务、熄屏与中断恢复边界（第 151 阶段，完成）

- 长任务验证必须复用生产 `RoomWorkflowRepository`、WorkManager 调度和 `ScheduledWorkflowWorker`，不得通过 Debug 代码建立第二套 Runtime。探针 Profile 只能授权 `app.current_time`；状态查询确认终态时必须恢复原 Profile、删除临时 Profile 并停用探针 Workflow，创建新探针前也必须清理上次遗留状态。
- 至少分别形成普通后台与熄屏的 8 步 SAFE 任务样本，保存 Task/Run 身份、步骤终态、Worker 耗时、PID、Wakefulness 和 `exit-info`。当前 Redmi 样本分别为 `95816ms / 91915ms`，均 `8/8 COMPLETED`；熄屏样本后半程保持 Dozing 且进程未变化。
- 人工 `force-stop` 必须明确标记为 `USER REQUESTED / FORCE STOP`，不得表述为自然 LMK。应用重启后不能恢复旧协程或续跑旧执行栈，只能从 Room 事实保留已完成前缀并取消剩余步骤、关联 Run 和 ScheduledTask；当前真实结果为 `4 COMPLETED + 4 CANCELLED`，没有重放工具。
- ToolResult 已成功验证但 Agent 尚未总结时，中断后的取消/失败步骤不得持久化 `verifiedToolNames` 或生成完成输出；独立 Tool Ledger 必须保留审计。只有真正 `COMPLETED` 的 Workflow 步骤可以把已验证工具升级为步骤完成证据。
- 本阶段实现必须通过针对上述中断窗口的 Redmi Room 单项，并把六份长期文档重新打入 AndroidTest APK 后通过项目文档 corpus 单项；当前两项结果均为 `OK (1 test)`。
- 自然 LMK、主动断网与 5 至 10 分钟真实生产任务尚未验证。只有这些真实证据或明确的用户可见常驻进度需求证明普通 WorkManager 不足时，才评审 Foreground Service；本阶段不引入常驻服务，也不开放后台设备动作。

## 今日安排与提醒总览 Skill（第 150 阶段，完成）

- 新增内置只读 `day-overview` Skill，允许用户询问“今天有哪些安排和提醒”时分别调用 `calendar.list_events` 与 `tasks.list`，最终回复必须标明日程和小灵任务的来源边界。
- Skill 所需工具仍由 Agent Profile 显式授权；日历继续要求用户主动授予 `READ_CALENDAR`，任务继续只读当前 Room 事实。既有 Profile 不自动扩权。
- 本阶段不新增 Android 权限、Room Schema、工具 Executor 或第二套 Runtime，不允许修改、取消、执行任务，不允许创建/修改/删除日程，也不开放后台 Workflow、定时任务或静默权限。
- 聚焦 JVM `AgentSkillsTest 15/15 + XiaoLingToolRegistryTest 40/40`、Debug APK 构建和 Redmi 真实闭环均已完成；同一 Run `run-535a90af-b45c-4b18-8574-0aa4c91e6268` 的两项 ToolResult 均为 `PASSED`，最终回答分别标明日程与任务来源。

## 系统日历标题关键词查找（第 149 阶段，完成）

- 新增 SAFE `calendar.search_events` 与独立 `calendar-search` Skill；用户必须先在日历访问设置页主动授权 `READ_CALENDAR`，并在 Agent Profile 中显式启用工具和 Skill。
- 输入限制为 `query` 1..100 个字符、`days_ahead` 1..30、`limit` 1..20。查询只匹配 Provider 最小投影中的标题，结果只返回标题、起止时间和全天标记；不读取地点、描述、参与人、组织者或账户，也不提供 `WRITE_CALENDAR`。
- `supportsBackground=false`，不允许后台 Workflow、定时任务或静默权限请求。关键词匹配失败、权限撤销竞态和 Provider 异常继续 fail-closed；旧 Profile 不自动扩权。
- 本阶段不改变 `calendar.list_events`、Room Schema、日历设置页或 Runtime；聚焦 JVM、Debug/AndroidTest APK 和 Redmi `AndroidCalendarEventReaderInstrumentedTest 2/2` 通过。设备没有可安全创建的日程，仅验证真实 Provider 的有界读取与不存在标题空结果，不伪造匹配样本。

## 系统日历只读能力（第 148 阶段，完成）

- 新增 SAFE `calendar.list_events` 与内置 `calendar-overview` Skill；只有用户在独立设置页主动授权 `READ_CALENDAR` 后，前台 Agent 才能使用。既有 Profile 不自动扩权，必须显式启用工具和 Skill。
- 输入限制为 `days_ahead=1..30`、`limit=1..20`；Provider 查询只投影标题、开始时间、结束时间和全天标记，标题压成单行并限制长度。地点、描述、参与人、组织者、账户等字段不进入 Agent，也没有 `WRITE_CALENDAR`。
- `supportsBackground=false`，日历读取不允许后台 Workflow、定时任务或静默权限请求；权限撤销竞态和 Provider 异常均 fail-closed。工具执行使用 `Dispatchers.IO`，不阻塞 UI 线程。
- Redmi 真实证据：日历 Provider 读取 `AndroidCalendarEventReaderInstrumentedTest 1/1`；设置页和设置根入口 `7/7`；真实 Agent 计划 `1/1`，唯一工具为 `calendar.list_events`，返回未来 7 天无日程。
- 计划提示词明确整理、展示、总结或回复用户属于最终回复，不得拆成独立步骤；回归 `PersonalTaskPlanPolicyTest 12/12`，避免运行时为纯展示步骤额外调用无关工具。
- 本阶段不创建/修改/删除系统日历，不开放后台设备动作、MCP、远程 Channel、多 Agent 或本地模型；后续继续按 Redmi-only 和分级验证约束推进。

## 真实多步 Runtime 可靠性与后台时长评估首轮（第 147 阶段，完成）

- 已成功验证的 SAFE 只读工具被模型紧邻重复请求时，只有无需审批、使用 `RESULT_READABLE` 验证、前次结果成功且非空、调用指纹完全相同，才允许复用已有结果完成；不得再次执行工具。设备动作、写工具和普通重复继续拒绝。
- 当前 Agent Run 没有任何工具事实却返回 `complete` 时，只允许一次应用侧纠错重试；再次提前结束必须失败。Workflow 前序输出只能作为数据，不能替代当前步骤实际工具执行。
- Redmi 必须形成前台和熄屏真实 SAFE Workflow 样本，并保存 Run/Step/Tool Ledger、目标级结论、进程状态和退出原因。人工 `force-stop`、安装、instrumentation、测试框架终止或 `kill -9` 不得冒充自然 LMK/系统回收。
- 本阶段两条 8 步 Run 已完成：前台 `workflow-run-84097511-b21d-4d89-9098-ed439625eba8` 耗时 `104156ms`，熄屏 `workflow-run-2153667c-f664-4034-a566-79a114899c27` 耗时 `94155ms`，均为 `VERIFIED / ALL_CRITERIA_VERIFIED`。熄屏样本证明 Dozing 期间同一进程可继续执行，但没有自然回收证据。
- 只有真实任务时长、用户可见进度需求、系统回收或配额数据证明现有执行与 Room 收敛不足时，才能立项 Foreground Service 或新的恢复能力。当前样本不足 5 分钟，不以人为等待凑样本，不预先增加常驻服务。
- 后台设备动作、截图/视觉、任意 App、坐标、MCP、系统日历写入、远程 Channel、多 Agent、跨设备同步和本地模型继续关闭，分别在长任务评估之后依据明确用户场景立项。

## 真实任务总览与关联重试收口（第 146 阶段，完成）

- `tasks.list` 除 Room/Registry 测试外，必须在 Profile 显式启用工具与 `task-overview` Skill 后完成一次真实模型 Agent Run；只允许读取当前任务事实，不得扩大为任务修改、执行或后台调用。
- 关联重试的 `SKIPPED` 步骤必须沿 `reusedFromStepId` 多级链回查最初执行步骤的 Agent Run 和 Tool Ledger。已验证工具、设备观察与设备动作必须使用同一来源规则；来源缺失、链损坏或循环时继续 fail-closed。
- 当 Workflow Run 为 `FAILED`、但全部步骤已经 `COMPLETED / SKIPPED` 时，允许创建只重新执行目标收敛的关联新 Run。新 Run 必须复用全部步骤、写入 `retryOfWorkflowRunId`，不得重放模型、Executor、设备动作或审批；来源失败 Run 和全部旧事实保持不变。
- Accessibility `onInterrupt()` 不得被解释为服务断连，不得据此取消正在等待的审批或 detach Runtime；真正 `onDestroy()` 仍必须取消审批并断开 Runtime。审批浮层显示期间不得使用会销毁/重建 Accessibility Service 的 UIAutomator 做观察。
- Redmi 真机验收必须同时证明：真实 `tasks.list` ToolResult 为 `PASSED`；三步设备动作来源 Run 保持失败历史；新的仅收敛 Run 三步全部复用，工具顺序和最终包满足本地完成标准，目标级结论为 `VERIFIED`。

## 任务/提醒只读总览（第 144 阶段，完成）

- Agent 必须能在 Profile 明确允许时回答“我有哪些任务、提醒或工作流”，结果应包含名称、目标、启停、步骤数、最近执行状态、提醒类型和可用的下次时间。
- 任务总览必须以 Room 中当前 Workflow、Run、ScheduledTask 和 Schedule 为权威事实；最近 Run 按 Workflow 独立查询，一次性与周期计划并存时展示最早的下次触发。
- 工具结果不得包含 Workflow/Run/Task/Schedule 内部 ID、错误详情、步骤输出或其他执行证据；未知状态必须收敛为稳定展示，不根据历史对话猜测。
- `tasks.list` 必须为 SAFE 只读工具、限制 `limit=1..10` 且禁止后台 Workflow 调用。既有 Profile 和历史 Run 不得静默扩权；用户需显式开启 `tasks.list` 及 `task-overview` Skill。
- 本阶段不增加任务修改、取消、重试或执行工具，不新增 Android 权限、Room Schema、系统日历、后台设备自动化或第二套 Runtime。

## 完成结果定向查看 Workflow（第 142 阶段，完成）

- 个人任务完成结果卡必须保留对应 Workflow 身份；点击“查看任务”应打开现有 Workflow 管理页并定位到该 Workflow，而不是重新发送目标、重新规划或创建新事实。
- Workflow 管理页在目标存在时自动滚动并展开对应条目；目标不存在或入口没有目标 ID 时使用通用列表入口，不阻断用户查看其他任务。
- 一次性 Workflow 导航目标只在当前设置子页链路内有效；返回设置根页时清理，不能污染下一次手动进入。
- 本阶段只修改 UI 导航与列表展示，不扩展 Room Schema、Agent Runtime、工具白名单、审批、设备权限、后台执行或恢复语义。验证按快速迭代分级执行聚焦 JVM、Debug/AndroidTest APK 和 Redmi 定向 Compose 用例。

## 定向 Workflow 导航重建保存（第 143 阶段，完成）

- Activity 重建或旋转后，完成卡传入的 `requestedWorkflowId` 必须保留，Workflow 管理页仍能定位并展开原目标；知识文档目标保持相同保存语义。
- 只保存一次性内容目标，不保存 Tab、设置子页或根页返回时间等暂态导航字段；返回设置根页必须清理目标。
- 本阶段不改变 Room Schema、Workflow/Run、Agent Runtime、工具白名单、审批、设备权限或后台执行。

## 完整个人 Agent 主线（第 127 至 132 阶段，已完成）

完整前台个人 Agent MVP 必须跑通统一主链：用户以自然语言提出目标，系统读取当前 Profile 允许的长期记忆与本地知识，生成 1 至 8 步临时计划并展示风险/能力边界，用户确认后复用既有 Workflow、Agent Runtime、Tool Registry、Room Ledger、审批和验证执行；完成时只允许使用已验证步骤与最终观察形成目标级结论，并把任务事实持久化。不得建立绕过现有安全和审计边界的第二套 Runtime。

实现顺序固定为：第 127 阶段已交付自然语言个人任务入口与可确认计划；第 128 阶段已交付限定 App 多动作连续执行；第 129 阶段已交付目标级验证和最终回答约束；第 130 阶段接入长期记忆、本地知识和复用 WorkManager 非精确定时的应用内提醒；第 131 阶段从已验证前缀创建关联新执行完成任务级恢复/重试，旧 Run 与旧副作用事实保持不变；第 132 阶段仅用 Redmi 验收三条完整用户任务，并统一运行完整 JVM、Lint、Debug/AndroidTest APK 和默认 instrumentation。正式 Release 只在用户明确要求时执行。

## 个人任务计划交互打磨（第 133 阶段，完成）

- 计划生成、立即任务创建和提醒创建必须使用独立 UI 状态，页面显示与实际业务阶段一致的进度和停止语义，不得复用普通聊天的模型等待提示冒充创建进度。
- 计划生成失败、Workflow/ScheduledTask 原子创建前失败或用户在创建前停止时，必须恢复原始目标并提供重新生成。重试必须以失败快照中的目标为准，不能依赖可能被其他状态覆盖的输入框文本。
- 确认后的前台操作必须绑定计划 ID 与原会话。切换或删除会话时需要取消当前 Job；已创建 Run 按既有 Ledger 收敛为取消，尚未创建时不得伪造执行消息。所有成功、失败和 `finally` 回写必须拒绝旧请求污染新会话。
- Android 13+ 缺少通知权限时，提醒确认必须等待系统权限结果返回，等待期间禁用重复确认和返回。权限回调只有在原计划 ID 仍有效时才提交；拒绝权限不撤销用户已经确认的应用内调度语义，但界面必须保持“通知可能不可见”的既有边界。
- 本阶段不修改 Room Schema、计划 Schema、工具白名单、审批、目标级验证或后台执行权限。验证遵守快速迭代分级，只覆盖相关 JVM、Debug/AndroidTest APK 和 Redmi 定向用例；不重复完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 计划生成成本可见性（第 134 阶段，完成）

- 计划确认弹层必须展示本次计划生成请求的真实模型调用次数、总耗时、TTFB、Prompt 字节数，以及 Provider 实际返回的 input/output/total Token usage。
- 当前计划流程严格只发起一次模型请求，因此调用次数显示为 1；展示层只能使用 `ModelResponseResult` 的真实字段，不根据模型、Prompt 或耗时推算货币成本，不新增价格表或成本估算。
- TTFB 或任一 Token usage 缺失时必须明确显示“未采集/未返回”或对应未知字段，不能用 0、平均值或估算值冒充真实使用量。
- 计划生成遥测只属于确认前的 `PendingPersonalTaskPlanUiState`，不得写入 Room、RunEvent、Workflow、Agent Run 或跨计划历史账本；计划请求不伪装成 Agent Run 请求。
- 本阶段只增加展示 DTO、格式化和 UI/聚焦测试，不修改计划 Schema、Room Schema、执行工具、审批或后台权限。验证按快速迭代分级完成，Release 与全量门禁留到里程碑。

## 常用任务模板（第 135 阶段，完成）

- 任务模式必须提供一组受控的常用目标模板，首批仅覆盖已完成 Redmi 验收的计算器、系统设置和时钟目标。
- 选择模板只能回填现有任务输入框，不能自动发送、请求计划模型、创建 Workflow/Run、写入 Room 或直接执行设备动作；用户仍必须手动发送并确认计划。
- 模板内容只是用户意图起点，不得扩大当前 Agent Profile 工具白名单、首批 App 包名白名单、审批或目标级验证边界；最终计划仍由现有严格 Schema 和本地策略校验。
- 模板入口只在任务模式下展示，等待计划确认、生成中或创建中必须禁用；切换回普通对话不应保留模板专属状态。
- 本阶段不新增 Room Schema、Runtime 或后台权限。验证只覆盖模板 Compose 交互、相关 JVM、Debug/AndroidTest APK 和更新后的文档 corpus；不重复完整 JVM、全量 Lint、默认完整 instrumentation 或 Release。

## 首个 App 兼容扩展（第 136 阶段，完成）

- 首批兼容扩展一次只允许增加一个经过真机核实的明确 App；本阶段唯一新增目标为 Google 天气 `com.google.android.apps.weather`。Manifest 只声明该包可见，不得申请 `QUERY_ALL_PACKAGES`，也不得借此开放 Chrome、联系人、短信、文件、日历或任意 App。
- `DeviceActionPolicy`、`device.open_app.package_name` Schema、Workflow SafetyPolicy、Room Approval 和 Executor 必须继续对同一精确包名 fail-closed；每次天气打开仍需独立用户审批、动作后重新观察、`executorVerified=true`、typed `PASSED` 和答案级 `VERIFIED`，不得从既有计算器审批复用授权。
- “查看天气”模板只允许回填自然语言目标，不自动发送或执行。天气页中用户主动查看时可见的粗粒度位置属于当前前台观察内容，不得新增后台采集、跨任务复用节点引用或额外持久化旁路。
- Redmi 真机必须验证包可见性、模板不自动发送，以及生产 `snapshot -> open_app` 的审批身份、Tool Ledger、后置天气包名和答案级 Decision；不以模拟器结果代替。
- 当前聚焦验收为 JVM `64/64`、Debug/AndroidTest APK、Redmi 包可见性与模板两个定向单项，以及更新后文档 corpus 首轮/证据写回后复验 `OK (1 test)`（`2.409s / 2.606s`）；完整 JVM、Lint、默认完整 instrumentation 和 Release 留到里程碑或用户明确要求。

## 计划上下文请求精简（第 137 阶段，完成）

- 长期记忆与本地知识仍分别遵守每来源最多 3 条、单条正文最多 800 个 UTF-16 字符和代理对安全边界；进入计划 Prompt 的两类正文合计不得超过 8 KiB UTF-8，预算必须包含来源标题、编号和知识文档名。
- 选择必须按稳定的记忆/知识交替顺序进行，不能让单一来源先占满预算；超限时只能省略完整条目，不能为填满预算二次切断正文。知识正文与已经候选的记忆完全相同时不得重复发送，并计入知识省略数。
- system 安全规则、用户目标、规划时间、工具边界、允许 App、计划 Schema、检索授权和检索失败阻止生成语义不得因精简而删除或放宽。
- 计划确认页必须展示真正发送给模型的记忆/知识数量和上下文字节；发生去重或预算裁剪时必须分别显示两类省略数。展示数据必须与模型请求来自同一不可分割结果，不能使用检索原始数量推断。
- 本阶段不修改 Room Schema、Workflow/Run、审批、工具白名单、Runtime 或后台权限，不估算货币成本。聚焦验收覆盖 JVM、Debug/AndroidTest APK、Redmi Compose 和一条显式真实 Provider 计划请求；完整 JVM、Lint、默认完整 instrumentation 与 Release 留到里程碑。

## 计划生成取消重试（第 138 阶段，完成）

- 用户主动停止计划生成时，必须恢复发送前的原始目标，清除生成中状态，并生成包含该目标的可重试失败状态；不能只结束 loading 或把目标留在不可操作的隐式状态。
- 失败状态必须由原计划 request ID 和会话身份守护。会话切换/删除先使 request ID 失效，旧协程的取消回调不得修改当前会话的 Prompt、失败卡或进度。
- 本阶段仅覆盖计划模型请求尚未创建 Workflow/Run 的取消；确认后的立即任务、应用内提醒和已经落定的 Room 事实继续沿既有取消、失败、WorkManager 撤销和关联重试契约执行。
- 本阶段不新增 Room Schema、Runtime、工具白名单、设备权限或后台能力。聚焦验证为 JVM、Debug/AndroidTest APK 和 Redmi `ConversationPageInstrumentedTest`；完整 JVM、Lint、默认完整 instrumentation 与 Release 留到里程碑。

## 确认后任务创建提交边界（第 139 阶段，完成）

- 立即任务创建和应用内提醒创建必须在同一个不可取消的短边界内完成 Room 原子写入，并在该边界内捕获 Workflow/Run、ScheduledTask 或周期调度的持久化身份。
- 外层协程在捕获身份后才检查取消状态。用户停止发生在 Room 提交与返回值交接之间时，已创建 Run 必须沿既有 `CANCELLED`/清理路径收敛；不能误判为“尚未创建”、展示可重复创建的失败卡或产生第二个任务。
- 会话切换仍先使操作 request ID 失效；迟到的旧操作不能回写新会话。该边界不恢复旧 Executor、模型协程或后台权限，也不改变旧 Run 保持不变的重试语义。
- 本阶段新增纯协程提交边界测试，聚焦验证为 `PersonalTaskCreationCommitTest 1/1 + PersonalTaskPlanCancellationTest 1/1`、Debug/AndroidTest APK；完整 JVM、全量 Lint、Redmi instrumentation 和 Release 延后到里程碑或用户明确要求。

## 已提交任务失败后的唯一后续动作（第 140 阶段，完成）

- Workflow/Run、ScheduledTask 或周期调度尚未提交时，生成/创建失败仍必须恢复原目标并提供“重新生成”。一旦任一任务记录已经提交，停止或失败提示不得继续复用该动作。
- 已提交任务的失败状态必须明确说明记录已经保留，并只提供“查看任务”；该动作刷新并打开现有工作流管理页。不得从失败条自动创建新 Workflow、Run 或提醒，也不得自动重试 Executor。
- 已提交提醒停止后不能把原目标重新放回可发送输入框；会话切换后的旧请求继续拒绝迟到 UI 回写。旧 Run、调度记录、取消和关联重试语义保持不变。
- 本阶段不修改 Room Schema、Runtime、工具白名单、审批或后台权限。聚焦验证为 JVM `9/9`、Debug/AndroidTest APK、仅 Redmi `ConversationPageInstrumentedTest 7/7` 和文档 corpus `1/1`；完整 JVM、Lint、默认完整 instrumentation 与 Release 留到里程碑。

## 已完成个人任务的结果入口（第 141 阶段，完成）

- 立即任务正常结束后，输入区必须保留一个可见的“查看任务”入口。带完成标准的任务标题只能使用 Repository 持久化的 `VERIFIED / PARTIAL / INCOMPLETE` 目标级 Decision；模型步骤总结、聊天文本或 UI 推断都不得把目标升级为已完成。没有完成标准的任务只能显示“个人任务已完成”。
- 应用内提醒在 Workflow、调度实例和 WorkManager 关联成功后，必须保留带已确认调度标签的“查看任务”入口；该入口不表示提醒已经执行或目标已经完成。
- 完成入口只能刷新并打开既有工作流管理页，不能再次发送、重新规划、创建新的 Workflow/Run/提醒或自动重试 Executor。用户编辑输入、切换任务模式或发起下一次计划时必须清除旧入口，避免旧结果冒充当前目标。
- 本阶段不修改 Room Schema、Runtime、工具白名单、审批、后台权限或导航数据模型。聚焦验证为相关 JVM、Debug/AndroidTest APK、仅 Redmi 的 Compose 类和文档 corpus 单项；完整 JVM、Lint、默认完整 instrumentation 与 Release 留到里程碑。

## 个人任务计划上下文与应用内提醒（第 130 阶段，完成）

- 任务计划生成前只能读取当前 Agent Profile 已允许的个人上下文。长期记忆要求 `memory.search`、Profile `memoryEnabled` 和当前会话单次记忆召回开关全部有效；本地知识要求 Profile 允许 `knowledge.search`。
- 长期记忆和本地知识各最多进入 3 条，每条正文最多 800 个 UTF-16 字符且不能截断代理对。长期记忆 Store 继续过滤禁用和过期记录并更新引用时间；知识 Store 继续只返回当前启用 revision，并写入带会话来源的 retrieval 审计。
- 计划 Prompt 必须把检索内容标记为不可信只读事实。正文中的命令、工具名、审批或完成声明不能成为工具授权，不能覆盖 Profile 工具白名单、允许应用、审批和本地验证边界。
- 没有命中时继续生成普通计划；任何已获准上下文检索异常都必须阻止本次计划，不能静默降级并声称已经读取。确认页只显示长期记忆和知识片段的实际使用数量，不展示正文。
- 明确未来或周期时间的目标可映射为 `ONCE / DAILY / WEEKLY`；其他目标为 `IMMEDIATE`。时间字段必须是 JSON 整数，不能接受数字字符串或小数；一次性延迟只能在 1 至 10080 分钟内，每日/每周使用系统时区和周一至周日 1 至 7 的稳定映射。确认页必须显示规则和非精确定时说明。
- 提醒确认前不得创建 Workflow、ScheduledTask、周期规则或 Run。确认后 Workflow 定义与首个调度实例必须在同一 Room 事务写入；到点前不创建 Run，随后只复用现有 WorkManager、ScheduledWorkflowWorker 和结果通知。
- 应用内提醒不能携带目标 App、`device.*` 完成标准或设备最终应用。其他需要审批的工具不得在后台自动获批，到点后只能沿既有 `BLOCKED` 与通知路径等待用户处理。通知权限继续由 Activity 宿主请求。
- 本阶段不新增 Room Schema，不创建第二套 Runtime，也不接入系统日历、精确闹钟、Foreground Service 或后台设备控制。修改/取消继续通过既有工作流调度管理入口，由用户明确操作；自然语言不能静默改写或删除已有规则。

## 任务级恢复与关联重试（第 131 阶段，完成）

- `BLOCKED / FAILED / CANCELLED` Workflow Run 只能从同一 Run 已验证的连续成功前缀创建关联新 Run；前缀步骤标记为 `SKIPPED` 并保存 `reusedFromStepId`，首个未完成步骤及其后步骤重新执行。
- 已启动过的失败步骤必须二次确认；确认时重新校验会话、Profile、Provider、工具白名单和当前 Run 状态。旧 Run、旧步骤、已提交副作用和审计事件不得修改或重放。
- 新 Run 必须写入 `retryOfWorkflowRunId`，不恢复旧模型协程、Executor、审批会话或未知提交状态；来源证据不足时保持 fail-closed。
- 本阶段只运行重试策略聚焦 JVM、Debug/AndroidTest APK 和 Redmi 关联重试单项；完整 JVM、Lint、Release 和默认完整 instrumentation 延后到第 132 阶段里程碑。

第 127 至 132 阶段不以截图/视觉、后台设备控制、任意 App、精确定时、MCP、系统日历、远程 Channel、多 Agent、跨设备同步或本地模型为前置条件。每个阶段必须形成用户可直接体验的新能力；纯重构、单层 evidence、Shadow 扩样和文档整理只能作为功能切片的必要组成，不能替代主线交付。
第 132 阶段已完成：完整 JVM `879/879`、Lint `0 error`、Debug/AndroidTest APK、三条 Redmi 完整任务和默认完整 instrumentation `282/282` 通过。正式 Release 未执行。
第 133 阶段已完成首轮计划交互打磨；第 134 至 136 阶段继续交付计划成本可见性、常用模板和首个 Google 天气兼容扩展，不回到纯结构或 Shadow 扩样主线。
Redmi 当前 ROM 使用 `com.google.android.calculator / com.google.android.deskclock`，与既有 AOSP 包名不同。首批限定应用白名单同时接受两套明确实现，并单独增加 Google 天气，仍只覆盖明确列出的应用，不开放任意 App 或 `QUERY_ALL_PACKAGES`。
第 145 阶段把计算器、时钟的 AOSP/Google 实现收敛为显式等价应用族：启动仍优先冻结包名，只能回退到同族白名单实现；动作前后与答案级证据允许同族包名匹配，跨族仍 fail-closed。“返回小灵 / 回到小灵”步骤在 Workflow 中只允许 `device.snapshot -> device.back`，不得改写为重新打开小灵或扩大任务目标应用。Redmi 已真实完成读取时间、打开 Google 时钟和返回小灵的三步目标级 `VERIFIED` 闭环。

## 目标级本地验证与最终回答约束（第 129 阶段，完成）

计划 Schema 必须要求 `verification.required_tool_names` 和 `verification.expected_final_package`。必需工具至少一项、按预期先后顺序排列且只能来自当前 Agent Profile 的工具白名单；最终应用只能为空或首批允许包。确认 UI 必须在任何执行前展示完成标准；确认后用户原始目标与完成标准必须随 Workflow 及每个步骤输入快照冻结，手动/定时运行、准备/启动步骤和关联重试不得重新调用模型改写标准。

目标判定只能消费已持久化步骤、同 Run Tool Ledger 中 `success=true + typed PASSED` 的工具名顺序、脱敏设备观察/动作 Decision 和时间最新的最终包名。辅助工具可以出现在必需工具之间，但不能改变必需工具顺序；关联重试的 `SKIPPED` 成功前缀只有携带冻结工具事实时才计入已验证步骤。不得读取模型总结正文、原始动作 JSON、snapshot/ref、节点正文、坐标或 HMAC 形成完成结论。

全部步骤、必需工具和最终应用满足时输出 `VERIFIED`；存在可信进度但任一标准不足时输出 `PARTIAL`；没有已验证进度时输出 `INCOMPLETE`。最终用户文案必须由本地策略从该 Decision 生成。Room v35 只增加 nullable `workflows.goalVerificationContract / workflow_runs.goalVerificationDecision`；v34 历史记录保持 `null`，不能按旧成功状态补造 `VERIFIED`，非空但损坏或版本不支持的 Contract 必须阻止新 Run。

当前验收为聚焦 JVM `22/22`、Debug/AndroidTest APK、Redmi 定向 `OK (5 tests)`（`3.33s`）和真实多动作 `goalDecision=VERIFIED` tracer。文档语料黄金查询不得依赖会随阶段变化的历史测试总数；当前改用验证报告的稳定职责词，并在 Recall 不满时输出逐查询实际排名。更新后的 Redmi 首轮/写回后复验均为 `OK (1 test)`（`2.461s / 2.444s`）。

## 限定 App 多动作连续执行（第 128 阶段，完成）

设备任务计划可以携带可空 `target_app_package`；非空值必须来自首批允许包，并在用户确认时展示。确认后目标包必须冻结到 Workflow、Run 和全部步骤输入快照，后续手动运行、定时运行或关联重试不得按当前设置重新推断。Room v34 迁移只增加 nullable 列，v33 历史 Workflow 必须保持空目标包，不能猜造旧授权。

设备动作必须由本地策略绑定冻结目标包：`open_app` 只能打开该包，`tap_ref / type_text / swipe` 动作前后都必须位于该包，`back / home` 只能从该包开始。每次页面变化后必须重新取得通过验证的 snapshot/ref；Runtime 只允许紧跟已验证设备动作刷新同参数 snapshot，不得放宽连续 snapshot、重复副作用、TTL、generation、审批、Executor/typed 验证或动作后观察。

Redmi 验收必须在同一真实 Agent Run 中完成至少两个设备动作并证明中间观察为新 snapshot。当前限定设置页链路为 `snapshot -> swipe(up) -> snapshot -> back`，两项动作均通过 production Registry 验证、审批数为零、最终返回小灵且持久结果隐私安全。第 128 阶段本身只证明动作级连续执行，不把单步成功、模型自由文本或历史 ref 表述为最终业务目标；第 129 阶段已经在同一真实链上补充 `goalDecision=VERIFIED` 的本地目标结论。

## 自然语言个人任务与可确认计划（第 127 阶段，完成）

对话页必须提供显式“对话 / 任务”模式；任务模式不要求 `/agent` 前缀，并使用当前 Agent Profile 冻结的 Provider、模型、API 模式和工具白名单生成任务名及 1 至 8 个独立步骤。Chat Completions 必须通过 `response_format.json_schema` 请求严格结构化输出，Responses 必须通过 `text.format` 请求相同 Schema；客户端仍须拒绝额外字段、Markdown fence、JSON 外文本、错误类型、空名称/步骤和数量越界，不能把 Provider 的 Schema 支持当作唯一信任边界。

第 127 阶段只冻结 Profile 的模型与工具能力边界，不在计划生成前注入长期记忆或本地知识正文。完整主链中“读取允许的记忆/知识后生成计划”的要求由第 130 阶段接入；在此之前不得把第 127 阶段的完成状态解释为记忆/知识计划上下文已经交付。

计划弹层必须展示原目标、顺序步骤、Agent/模型、允许工具和可能触发审批的工具。用户确认前不得创建会话消息、Workflow、Workflow Run、Agent Run、审批、工具调用或其他执行账本；API Key 只能保留在私有执行快照，不得进入 Compose UI state。取消计划必须恢复原目标供修改；切换或删除会话必须取消在途请求或丢弃待确认计划，迟到响应不得进入新会话。

用户确认后必须在一个 Room 事务中创建普通可管理 Workflow、手动 Run、步骤定义和全部运行步骤快照，再由既有 Workflow/Agent Runtime 执行；不得建立第二套 Runtime 或绕过既有工具白名单、逐动作审批、后置验证和 Room Ledger。确认后的 Workflow 必须保留在管理列表中。执行失败或重试不得改写旧 Run：Redmi 真实验收中首个 Run 因模型规划 `60000ms` 超时保持失败，第二个手动 Run 独立完成 `app.current_time` 的六段审计链。

## 前台 Workflow `device.swipe` 生产默认接线（第 126 阶段，完成）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。`device.swipe` 必须继续使用既有 `SAFE_NO_APPROVAL` 风险语义，不创建 Room Approval 或 Accessibility 审批浮层；零审批不得绕过用户步骤意图、同 Run 新鲜 snapshot/ref、30 秒 TTL、当前 window generation、当前进程授权、Executor 验证、typed `PASSED` 和动作后重新观察。

生产 Registry 只能消费第 122 至 125 阶段已经冻结的完整证据链：动作前目标必须启用、未脱敏且支持 `SWIPE`，执行期 viewport/HMAC 只驻留当前进程，完成时必须证明同应用、同 window、同目标、generation 前进、可见匿名内容变化，以及共同锚点按请求方向发生主位移。答案级 Decision、Workflow output、Room 重建和 UI 只持久化“滚动”、前后包名、后置计数/截断/时间和规则版本；方向、viewport/HMAC、snapshot/ref、节点正文、坐标和可复用节点身份不得进入持久层。任一证据缺失、错窗、错向、action 伪装或身份漂移都必须 fail-closed。

验收只允许使用 Redmi `wsvwypiz7xwslvl7`，并运行真实生产 `MinimalAgentRuntime + RoomAgentRunRepository` 的 `snapshot -> swipe` 链路。当前证据为 `success=true action=swipe verified=true approvals=0 registryCompletion=PASSED answerDecision=VERIFIED privacySafe=true`，前后包均为 `com.android.settings`；更新后的项目文档语料首轮/最终单项还必须保持通过，当前均为 `OK (1 test)`、耗时 `2.307s / 2.3s`。这只证明首个限定 App/页面，不承诺任意 App。后台或定时设备自动化、恢复自动续跑、坐标、截图和任意 App 继续关闭。

## 小灵 v0.1.15 历史发布基线

`v0.1.15` 使用 `versionCode=16`、Room v33，并保持 `minSdk=26 / targetSdk=36` 与既有 `releaseLocal` 签名配置。发布范围汇总 `v0.1.14` 后第 122 至 127 阶段：前台 Workflow `device.swipe` 的安全/evidence/答案级投影/生产默认接线，以及自然语言个人任务、严格 1 至 8 步计划、确认前零执行和确认后原子创建普通 Workflow/Run。

发布不得扩大既有安全边界：旧 Run 和旧副作用事实保持不变，不恢复旧 Executor、模型协程或 Workflow 后续步骤；`device.type_text` 原文不得进入持久化路径；`open_app` 仍只允许四个首批包并逐包审批；`swipe` 只进入前台手动 Workflow。后台或定时设备自动化、恢复自动续跑、坐标、截图、任意 App、JSON/SAF 导出、生产 answerability enforcement、精确定时和 Foreground Service 继续关闭。

用户本轮明确要求“不要验证，直接发版”，因此本次发布例外只执行必要的 `assembleRelease`，不运行完整 JVM、完整 Lint、Debug/AndroidTest APK、签名/zipalign 复核、Redmi 安装或 instrumentation。该例外必须在 Release Notes 和验证报告中明确披露，不能把第 126/127 阶段既有聚焦结果表述成本次发布门禁。Release APK 为 `3,318,322` 字节，SHA-256 为 `a9c5b57dd3aa9d7f262d7909499dbdd7f91361cccf3b4d6bcd893d100c34e674`。

## 前台 Workflow `device.swipe` 答案级脱敏判定与持久投影（第 125 阶段，生产未开放）

答案级判定只能从同一 Agent Run 的持久 Tool Ledger 重建。`device.swipe` 结果必须同时满足 `success=true`、`executorVerified=true`、typed verification `PASSED`、严格 `workflow-device-action-result-v1` 解码，且工具名与 `action=swipe` 精确匹配；任一条件缺失或 action 伪装都必须 fail-closed。typed `PASSED` 只能由当前执行链已通过 Registry 专属同窗方向完成门禁后写入；答案层不重放或伪造瞬态 evidence。

`WorkflowDeviceActionDecision` 和 `workflow-step-output-v1` 继续复用既有通用白名单字段：动作、前后包名、后置节点/脱敏计数、截断状态、观察时间和规则版本。不新增 Room 表或列，不持久滚动方向、viewport、目标/锚点 HMAC、snapshot/ref、节点正文或坐标。因此持久判定只声明一次“滚动”已通过执行与后置验证，不单独声明方向、历史节点可继续执行或用户最终业务目标已完成。

Workflow 页面必须把该动作标记为“滚动”，只显示通用后置摘要，并明确“本次滚动不产生可复用节点引用，后续设备动作必须重新观察并按各自风险规则执行”。`swipe` 是 SAFE 零审批动作，Room 和 UI 不得伪造 Approval 记录或“重新审批”结论。本阶段只完成 Decision/Room/Workflow output/UI 安全投影；生产默认集合继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`，后台或定时设备自动化继续关闭。

## 前台 `device.swipe` 完成态内存交接与 Redmi 限定验收（第 124 阶段，生产未开放）

Registry 只有在当前动作的 `DeviceActionOutcome.swipeEvidence` 与授权前后 snapshot 精确绑定时，才允许构造 `WorkflowSwipeCompletionEvidence`。`outcome.beforeSnapshotId` 必须等于已授权 snapshot ID；动作前 viewport 的包名、window ID 与 generation 必须等于该 snapshot；动作后 viewport 的同三项必须等于 outcome 的真实后置 snapshot。任一错配都必须按缺少专属滚动后置证据 fail-closed，不能把另一窗口、旧动作或伪造 viewport 交给完成策略。

`WorkflowDeviceActionResultCodec` 可以识别 `action=swipe`，但只能复用既有的版本化通用结果摘要；snapshot/ref、目标/锚点 HMAC、节点正文、坐标和完整 viewport 不得加入 schema。完整 evidence 只允许在当前 Controller/Registry 执行链内消费，不进入 Room、日志、Workflow output、答案级 DecisionPolicy 或 Compose。生产默认 Workflow 集合仍不得包含 `device.swipe`；只有 Debug/JVM 显式测试集合可以注入。

Redmi 限定验收必须运行真实 `MinimalAgentRuntime + RoomAgentRunRepository` 链路，在固定的系统设置应用详情页执行 fresh `device.snapshot -> device.swipe(up)`，并证明 Run 完成、ToolCall 为 SAFE 精确参数、审批数为 `0`、Executor 验证和 typed 验证均通过、Registry 完成门禁通过、动作前后均为 `com.android.settings`，且 Result 不包含 snapshot/ref 或 64 位 HMAC。该验收只证明首个限定 App/页面，不承诺任意 App，也不等同于生产 Workflow 开放。

第 124 阶段完成后，第 125 阶段已单独完成答案级脱敏判定、Room/Workflow output 与 UI 投影。当前只剩生产默认集合接线和新的 Redmi 生产 Workflow 前台闭环；完成前默认工具面继续精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`，后台或定时设备自动化继续关闭。

## 前台 `device.swipe` 执行期 HMAC evidence seam（第 123 阶段，生产未开放）

Controller 必须为每个实例生成至少 256-bit 的随机 HMAC 密钥，测试只允许在随机系统边界注入固定密钥。目标与锚点身份必须使用长度前缀结构化输入和 `HmacSHA256`，且锚点身份必须绑定当前滚动目标，不能在同一窗口的不同滚动容器间复用；身份不得包含 bounds、generation、snapshot ID 或 ref，也不得使用裸节点指纹或无密钥文本摘要。可见锚点只允许来自当前滚动目标下未脱敏、具有稳定语义的后代；同一 viewport 中重复语义身份必须全部丢弃，不能任意选择一个位置。

当前成功 snapshot 必须与节点 ref 同生命周期只驻留内存。capture 失败、显式清理或 Agent Run 切换时必须同步撤销 snapshot、ref 和 viewport。`inspectReference()` 必须在同一生命周期锁内确认 snapshot/ref/TTL 并生成 viewport，随后再次读取 window generation；证据构造期间 generation 变化时必须返回不匹配，且只允许支持 `SWIPE` 的目标返回该证据。Registry 只允许显式测试 Workflow 集合消费，生产默认 Workflow 动作集合不得增加 `device.swipe`；直接 `/agent` 的既有工具面不属于本阶段扩权。SAFE 滚动不得使用异常审批时间延长 snapshot TTL。

Controller 的滚动结果不得继续用 generation-only 判定。动作后必须重新 capture，并以同应用、同 window、同匿名目标、generation 前进、可见匿名内容集合变化及共同锚点至少 `8px` 的请求方向主位移共同证明成功；内容未变、锚点不足、方向相反、横向占优或显著锚点互相矛盾时必须返回 `verified=false`。设备层和 Workflow 层必须共享同一 viewport/anchor 类型和方向验证器，避免阈值或方向语义漂移。

完整前后 viewport 只允许留在当前 Controller/Registry 执行链，不得进入 `DeviceActionCodec`、`WorkflowDeviceActionResultCodec`、Room、日志、Workflow output、答案级 UI 或后台自动化。第 123 阶段当时不修改 Result codec、DecisionPolicy、Room、审批、Compose 或生产 Workflow 默认工具集合；第 124 阶段随后只为严格通用摘要识别 swipe，完成 Registry 完成态纯内存交接并仅用 Redmi 验收真实滚动，生产默认集合仍未开放。

## 前台 Workflow `device.swipe` 专属安全契约（第 122 阶段，生产未开放）

`device.swipe` 的纯策略契约只能接受精确的 `snapshot_id / ref / direction`，其中方向固定为 `up / down / left / right`。动作目标必须来自当前有效 ref，处于启用、未脱敏状态并声明 `SWIPE` 能力；动作前 viewport 必须绑定非空应用包名、有效 window ID、非负 generation、同一匿名目标指纹，以及至少两个互不重复的 64 位匿名可见锚点。缺少专属证据时，即使调用方把 `device.swipe` 注入通用动作白名单，也必须以 `SWIPE_POLICY_DENIED` fail-closed。

`swipe` 沿用现有 ToolDefinition 的 `SAFE` 语义，在 Workflow 专属策略中固定为 `SAFE_NO_APPROVAL`，但零审批不得绕过同 Run/ToolCall、同 Run 已验证 snapshot、30 秒 TTL、当前 generation、实时 ref、Executor 验证、typed `PASSED` 和动作后观察。专属授权只能保存 Run/ToolCall 身份、方向与动作前 viewport 的 SHA-256 摘要；不得复制包名、snapshot/ref、目标指纹、完整锚点或节点正文。该摘要只用于当前授权身份绑定，不能作为可逆内容标识或长期检索键。

完成判定必须要求动作前后属于同一应用、同一 window 和同一目标，动作后 generation 严格前进，可见匿名内容集合发生变化，并至少有一个前后共同锚点产生不小于 `8px` 的位移。该位移必须与请求方向一致且主方向绝对值大于横向分量；任一达到阈值的共同锚点若反向或横向占优，必须整体拒绝，不能由另一个方向正确的锚点掩盖。内容未变、目标漂移或只有 Android API 接收动作同样不得判定为成功。四个方向必须分别覆盖正向回归，并覆盖正确与矛盾锚点同时出现的拒绝反例。

第 122 阶段只冻结上述纯策略并强制 `WorkflowDeviceActionSafetyPolicy` 委托，当时没有修改生产 Registry、`DeviceObservationController`、Result codec、DecisionPolicy、Room、审批、答案级 UI 或 Workflow UI，也没有执行真实设备滚动。第 123/124 阶段随后完成 Controller/Registry 执行期 opaque/HMAC evidence、完成态纯内存交接和 Redmi 限定验收，但生产前台 Workflow 工具面仍精确为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text`；完整锚点继续不得进入 Room、日志、Workflow output 或答案级输出。

## 前台 Workflow `device.open_app` 生产闭环（第 121 阶段）

第 121 阶段把 `device.open_app` 接入当时的前台手动 Workflow 工具面；第 126 阶段随后加入 `device.swipe`，当前精确集合为 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。`device.open_app` 必须标记为 `REQUIRES_APPROVAL`，参数只能包含唯一 `package_name`；第 136 阶段增加 Google 天气后，当前目标只能是小灵、系统计算器、时钟、系统设置或 Google 天气。该白名单必须分别由 `WorkflowDeviceActionSafetyPolicy`、`WorkflowDeviceActionApprovalGate` 和最终 `DeviceActionPolicy` Executor 核验；任一层不得依赖其他层替自己补齐空参数、额外字段或非白名单包的拒绝。

每次打开应用都必须创建独立 Room Approval，并通过 Accessibility 安全浮层让用户确认目标包；审批证据要精确绑定 Workflow Run/Step、Agent Run、ToolCall、参数、当前进程 session、同 Run 已验证 snapshot、最多 30 秒 TTL 和当前 window generation。`open_app` 不消费节点 ref，但仍要求动作前观察保持同一窗口 generation。首次人工审批超过 TTL 时必须拒绝为观察过期，不能因为用户最终批准而延长旧 snapshot。

动作完成后必须重新观察，并同时取得 `success=true / executorVerified=true / typed PASSED`。后置包名必须在执行完成门禁和答案级 Room 证据重建时分别与获批 `package_name` 精确比较；打开另一个白名单包也不得收敛为当前调用成功。答案级 Decision、下一步、关联重试和 Workflow 管理页只显示“打开应用”、获批后实际验证的前后包名与有限节点摘要，不保存 Intent、原始审批参数或可复用节点引用。拒绝、取消、执行失败和窗口漂移继续使用稳定脱敏投影。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。六组聚焦 JVM 合计 `95/95`，Debug/AndroidTest APK 构建成功；Compose `pageDisplaysVerifiedOpenAppTargetAndFollowUpBoundary` 与 Room `workflowPersistsVerifiedOpenAppDecisionForNextStepAndUi` 均为 `OK (1 test)`（`2.681s / 0.628s`）；真实 tracer 最终为 `workflow-open-app-e2e success=true / action=open_app / approval=APPROVED / executorVerified=true / verification=PASSED / afterPackage=com.android.calculator2 / answerDecision=VERIFIED`。同步后的文档 corpus 首轮为 `OK (1 test)`（`2.783s`），该证据写回后的冻结文本复验同样通过。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`swipe`、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## 前台 Workflow `device.home` 生产闭环（第 120 阶段）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.back / device.home / device.tap_ref / device.type_text`。`device.home` 只允许空参数并固定为 `SAFE_NO_APPROVAL`；Runtime 不得为它创建 Room Approval 或显示 Accessibility 审批浮层，Workflow 管理页也不得伪造审批卡。异常调用方即使传入审批对象，也不能让审批决定时间替代当前执行时钟或延长 snapshot 有效期。

零审批不等于无约束。`home` 必须绑定用户明确编写的步骤意图、当前 Workflow Run/Step/Agent Run/ToolCall、同 Run 已验证 `device.snapshot`、30 秒 TTL、当前 window generation、当前进程授权和完整动作后观察。Executor 只有在后置窗口包名属于系统 `ACTION_MAIN + CATEGORY_HOME` 动态解析的 launcher 时才可返回 `verified=true`；不得写死 Redmi 或其他厂商桌面包名。Tool Ledger 仍需同时具备 `success=true / executorVerified=true / typed PASSED`，答案级判定、下一步、关联重试和 UI 只能消费严格白名单结果。

`home` 不生成节点目标或可复用 ref。答案级与 Workflow 管理页统一显示“返回桌面”，并明确后续动作必须重新观察、再按各自风险规则执行。`tap_ref / type_text` 仍需逐动作 Room/Accessibility overlay 审批；审批证据投影不得因新增 SAFE 导航动作而扩大。真实验收只使用 Redmi `wsvwypiz7xwslvl7`：六组聚焦 JVM 合计 `87/87`、Debug/AndroidTest APK、Compose 与 Room 纵向单项以及真实 `snapshot -> home` tracer 均已通过；同步后的文档 corpus 首轮为 `OK (1 test)`（`2.76s`），写回后最终复验同样通过。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`open_app / swipe`、全部后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## 前台 Workflow `device.back` 生产闭环（第 119 阶段）

前台手动 Workflow 的生产设备工具面扩展为精确的 `device.snapshot / device.back / device.tap_ref / device.type_text`。`device.back` 只允许空参数并固定为 `SAFE_NO_APPROVAL`；Runtime 不得为它创建 Room Approval 或显示 Accessibility 审批浮层，Workflow 管理页也不得伪造审批卡。异常调用方即使传入审批对象，也不能让审批决定时间替代当前执行时钟或延长 snapshot 有效期。

零审批不等于零门禁。每次 `back` 仍必须绑定用户明确编写的 Workflow 步骤意图、当前 Workflow/Step/AgentRun/ToolCall、同 Run 独立且已验证的 `device.snapshot`、最多 30 秒 TTL 和当前 window generation；恢复、关联重试、旧 snapshot、页面漂移、后台/定时来源、额外参数或证据缺失均必须在 Executor 前 fail-closed。动作完成后必须重新观察，并取得同一 ToolCall 的成功结果、`executorVerified=true`、typed `PASSED` 和已验证后置 snapshot 才能形成答案级完成事实。

`WorkflowDeviceActionDecisionPolicy`、step output snapshot、下一步、关联重试、Room 投影和 Compose 必须把 `device.back -> back` 显示为“返回”，且只保留前后应用、后置节点摘要、规则版本和“仅确认当前动作及后置观察”的能力边界。原始 snapshot、ref、节点、参数和模型转述不得进入答案级路径；Room Approval 投影继续只覆盖 `tap_ref / type_text`。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。完成门禁包括受影响聚焦 JVM、Debug/AndroidTest 编译与 APK、Workflow Compose 单项、Room 判定到下一步/UI 且无 Approval 的纵向单项，以及真实 `snapshot -> back` 的 Tool Ledger、答案级判定和后置包名复核；同步后的项目文档 corpus 首轮与最终复验均为 `OK (1 test)`（`2.733s / 2.725s`）。当前快速迭代阶段不运行完整 JVM、Lint、Release 或默认完整 instrumentation；`open_app / home / swipe`、全部后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续关闭。

## `device.type_text` 跨入口持久化隐私统一（第 118 阶段）

前台直接 `/agent` 与前台手动 Workflow 的 `device.type_text` 必须共享同一持久化安全投影。原始 `text` 只允许驻留当前执行进程，用于当前 ToolCall 的用户确认、Executor 输入和动作后精确回读；Runtime 的 `tool.call.proposed / tool.call.validated`、独立 ToolCall ledger、Room Approval、`approval.requested / approval.request_decided` 事件、Workflow gate、`VerifiedAgentContext` 与消息 Tool parts 只能保存 `snapshot_id / ref / text_sha256 / text_length`。任何入口都不得以会话类型、审批 UI 或消息投影为由绕过该边界。

`RoomAgentRunRepository.createApprovalRequest()` 必须作为所有审批调用方共享的最终持久化防线：即使上层误传含原文的 ToolCall，也要在事务写入前重新生成安全投影。当前进程的直接 `/agent` 审批卡可以显示内存中的真实输入，便于用户确认即将写入的内容，但展示前必须证明 Room 请求与原 ToolCall 的 Run/ToolCall ID、工具名、风险和安全投影完全一致；只按工具名或指纹匹配不足以恢复或展示原文。

文本 SHA-256 与长度只能用于同一当前进程内的身份绑定，不能用于恢复输入。应用重启后，历史 `type_text` 待审批 Run 不得重新进入可批准状态；`AgentRunResumePolicy` 必须返回 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE`，并在启动收敛事务中安全取消旧 Approval 与旧 Run。用户若仍需执行输入，必须新建 Run、重新生成 ToolCall 并重新确认原文，旧 Run、旧 ToolCall 与旧审批保持终态不变。

验收至少覆盖直接 `/agent` 与 Workflow 的 Runtime 审计投影、Repository 最终净化、`VerifiedAgentContext`/消息 Tool parts 无原文、当前进程审批卡的强身份绑定，以及进程重建后旧文本审批 fail-closed。当前阶段不因此开放 `swipe / open_app / back / home`、后台或定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App；下一动作只能另立单一前台 Workflow 切片，独立冻结意图、风险或 SAFE 依据、后置验证、答案级证据和 Redmi 验收。

## 前台 Workflow `type_text` 生产闭环（第 117 阶段）

前台手动 Workflow 的生产设备工具面可以从 `device.snapshot / device.tap_ref` 扩展为精确的 `device.snapshot / device.tap_ref / device.type_text`，但文本输入必须继续同时满足第 112、115、116 阶段冻结的前台来源、同 Run/ToolCall、当前 snapshot/ref、30 秒 TTL、window generation、可编辑且未脱敏目标、敏感文本预审计、Executor/typed 验证、动作后观察和原 `nodePath` 精确回读。`open_app / back / home / swipe`、全部后台或定时设备工具、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续在规划清单与 Executor 两层拒绝。

Workflow 的 `device.type_text` 必须使用独立 Room 审批和 Accessibility 安全浮层。原始 `text` 只允许留在当前 ToolCall 内存中供执行和精确回读，不得进入 `tool.call.proposed / tool.call.validated`、ToolCall ledger、Approval record、`approval.requested / approval.request_decided` 事件或浮层请求；这些持久参数只能包含 `snapshot_id / ref / text_sha256 / text_length`。浮层只展示 Workflow 步骤意图、工具说明和“输入 N 个字符，内容不展示”的脱敏摘要，不得展示原文、指纹、snapshot ID 或 ref。第 117 阶段最初只冻结 Workflow 来源；第 118 阶段已经把同一持久化边界扩展到直接 `/agent`、可信消息上下文和 Tool parts，同时保留当前进程审批卡显示原文的核对体验。

Accessibility overlay 移除后允许最多 `100ms` 的短结算窗口，用于吸收 Redmi 连续产生的自有 `TYPE_WINDOWS_CHANGED`。只有活动根仍是原目标且完整窗口集合精确回到显示前基线时，才可返回用户批准或拒绝；结算期间的外来窗口、活动根切换、目标内容变化、服务断连或最终窗口集合漂移必须返回稳定失败并使旧 generation/ref 失效。连续基线 detach 事件只能调度一次结算，不能提前完成，也不能吞掉外来窗口变化。

答案级动作判定必须按工具名与结果动作一一对应，只接受 `device.tap_ref -> tap_ref` 和 `device.type_text -> type_text` 的严格白名单结果。Workflow step output、下一步 `previousOutputs`、关联重试和 Compose 只允许保存版本化本地判定、动作前后应用、后置节点摘要和“输入内容未进入答案级证据”的隐私说明；输入原文、文本指纹、snapshot/ref、节点与原始动作 JSON 均不得进入这些路径。拒绝、取消、窗口变化、浮层不可用、服务断连和 BUSY 继续使用稳定状态，并按同一 Agent Run 中最后一个已批准白名单动作归因执行阶段失败。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`。完成门禁至少包括受影响聚焦 JVM、Debug/AndroidTest APK、Compose 无原文展示、Room 审批无原文持久化、Room Workflow 判定到下一步/重试/UI 的纵向单项，以及真实 Workflow `snapshot -> type_text` 的 Room Approval、Tool Ledger、答案级判定和原目标精确回读。当前快速迭代阶段不因此默认运行完整 JVM、Lint、Release 或默认完整 instrumentation。

## 前台 Workflow `type_text` 目标证据与精确回读 seam（第 116 阶段）

`DeviceController.inspectReference()` 必须从当前未过期、generation 未漂移的 ref 返回结构化目标证据，至少包含 enabled、editable、redacted 和动作集合。证据必须来自 `DeviceSnapshotPolicy -> DeviceNodeReferenceStore` 的同一短生命周期引用，不得从模型正文、历史 Workflow 输出或单独的 `liveReferenceMatched` 布尔值推断；ref 不存在、过期或窗口变化时目标证据为空并 fail-closed。

`device.type_text` 动作后验证必须绑定动作前解析出的原 `nodePath`。新的 snapshot 只能通过动作后 references 找到相同路径，再用新 ref 定位该目标节点并读取其 `text`；页面其他节点的正文、description 或 hint 即使与预期文本相同，也不能证明原输入框完成。目标消失、路径不唯一、动作后无 ref、文本为空、被截断或与获批输入不完全一致时必须保持 `verified=false`。精确回读以强类型瞬态证据留在 Controller/Registry 内存中，不增加 Room 字段，也不进入通用设备动作结果摘要。

Registry 必须在生产默认动作集合仍只有 `device.tap_ref` 的前提下，提供仅供聚焦测试使用的 `type_text` 生命周期 seam：执行前把当前目标属性转换为 `WorkflowTypeTextExecutionEvidence`，执行后使用原始 identity、专属指纹授权和强类型回读构造完成证据。通用授权继续移除 `text`；无原文的 `workflow-device-action-result-v1` 可以标识 `type_text`，但答案级 `WorkflowDeviceActionDecisionPolicy` 与现有 Compose 仍只能消费 `tap_ref`，工具名与结果动作必须双重一致。

Registry 的测试动作注入集合只能是 `{device.tap_ref, device.type_text}` 的子集；即使其他设备动作已经注册或被通用安全策略识别，也必须在 Registry 构造阶段拒绝，不能借测试 seam 提前开放 `open_app / back / home / swipe`。生产调用方继续使用仅含 `device.tap_ref` 的默认集合。

本阶段不得把 `type_text` 加入生产 Workflow 工具清单，不接入 Room 独立审批、Accessibility overlay、Workflow Repository 或答案级动作 UI，也不开放 `open_app / back / home / swipe`、后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。验收至少覆盖当前可编辑 ref 的完整证据、其他节点同文不误判、测试态 Registry 正向完成、不可编辑目标在设备动作前拒绝、测试 seam 对 `swipe` 等越界动作构造即拒绝、生产强行执行继续失败，以及仅 Redmi 上普通文本精确回读成功和敏感输入不覆盖原值。

## 前台 Workflow `type_text` 专属安全契约（第 115 阶段）

前台 Workflow 的 `device.type_text` 在进入生产前必须经过独立于通用设备动作白名单的专属安全策略。工具参数必须精确为非空 `snapshot_id / ref / text`，不得接受缺失或额外字段；文本继续复用 `DeviceActionPolicy`，在 `tool.call.proposed` 持久化前拒绝空值、超过 500 字符、非法控制字符、密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱。

当前 ref 对应节点必须有结构化证据证明其处于启用、可编辑、未脱敏状态并声明 `TYPE_TEXT` 能力。缺少节点证据、节点禁用、不可编辑、已脱敏或不支持输入时必须 fail-closed；仅有通用 `liveReferenceMatched=true` 不足以签发文本输入授权。

专属授权只能保存规则版本、Workflow/Step/AgentRun/ToolCall 身份、文本 UTF-8 SHA-256 指纹和字符长度，不得保存文本原文、snapshot ID 或 ref。通用 `WorkflowDeviceActionAuthorization` 对 `device.type_text` 只能保留 `snapshot_id / ref`，移除 `text` 并携带专属授权；直接把 `device.type_text` 加入通用 `enabledToolNames` 而没有专属执行证据时必须拒绝。

完成判定必须重新绑定原 ToolCall 文本指纹，要求结果属于同一 Agent Run/ToolCall/工具，Executor 已验证、typed 验证通过、动作后观察已验证且观察时间不早于动作完成；动作后目标文本必须与获批文本精确一致。通用完成门禁缺少专属回读证据或授权时必须拒绝。本阶段只冻结纯策略和反例，生产 Registry 仍只允许前台 Workflow 使用 `device.snapshot / device.tap_ref`，不修改 Room、Accessibility、Workflow Repository、后台/定时设备工具、恢复自动续跑或任意 App 边界。

## 前台 Workflow 设备动作答案级证据 UI（第 114 阶段）

Workflow 详情页必须从已持久化的 `workflow-device-action-decision-v1` 和同一 Agent Run 的 Room 审批记录生成设备动作证据，不得依赖模型自由文本。成功证据只允许展示 `tap_ref`、动作前后应用包名、后置节点数、脱敏节点数、截断状态、观察时间和规则版本，并明确“只确认当前动作及后置观察已验证，不确认最终业务目标”；持久节点引用必须标记为失效，任何后续动作都要重新观察和审批。

审批证据必须在 IO 加载边界把 `ApprovalRequestRecord` 收敛为只含 `runId / toolName / outcome` 的安全 DTO。UI 状态不得保存原始 `arguments`、snapshot/ref、节点正文、完整 `decisionReason` 或其他工具参数。拒绝、普通取消、窗口变化、审批浮层不可用、Accessibility 服务断连和 BUSY 必须投影为不同稳定状态；批准后在执行验证阶段发生的窗口变化或服务断连也必须可见。一个 Agent Run 出现多次动作尝试时，各失败尝试和批准后的执行失败不得互相遮蔽；已有成功本地判定时，以该成功判定作为步骤最终可信结果。

审批读取必须按 Workflow steps 的 `agentRunId` 去重并分块批量查询，避免 N+1 和 SQLite bind 参数溢出；返回证据的自身 `runId` 必须再次匹配当前 step 的 Agent Run，跨 Run 或工具名不匹配的记录不得绑定。step output、previous outputs、Workflow Run result 和 Run error 中只要出现潜在原始动作结果签名，都必须整段替换为固定提示，不能把动作 JSON 当普通答案正文渲染。

本阶段只增加审计与可见性，不开放 `open_app / back / home / type_text / swipe` 进入 Workflow，不改变 `device.snapshot -> device.tap_ref` 的前台来源、逐动作审批、30 秒 ref、generation、后置观察和 typed 验证门禁，也不开放后台/定时设备工具、恢复自动续跑、截图、坐标、视觉定位或任意 App。验收至少覆盖成功证据字段及能力边界、全部稳定失败状态、生产 overlay 原因签名、多次尝试、跨 Run 拒绝、原始审批参数不进入 UI、四处历史 JSON 脱敏、批量 Room 读取、Room 持久判定到投影和仅 Redmi Compose 展示。

## 前台 Workflow `tap_ref` 首个生产切片（第 113 阶段）

第 113 阶段当时，前台手动 Workflow 只允许按 `device.snapshot -> device.tap_ref` 顺序执行一个节点点击。`XiaoLingToolRegistry` 对 Workflow 暴露的设备工具必须精确等于这两项；`open_app / back / home / type_text / swipe`、后台或定时 Workflow、恢复自动续跑、截图、坐标、视觉定位和任意 App 继续拒绝。直接 `/agent` 的设备动作审批仍走原会话审批卡，不得因 Workflow 接线改变。

每个 `tap_ref` 必须先以当前 `conversationId / agentRunId / toolCallId / toolName / arguments` 在 Room 创建 `PENDING` 审批，再由 Accessibility overlay 展示用户明确编写的 Workflow 步骤意图和工具说明。浮层只能使用 `TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL + FLAG_SECURE`，标题固定为 `XiaoLingDeviceActionApproval`，并避开系统导航栏；snapshot ID、ref、节点正文和原始参数不得进入屏幕、日志、UIAutomator 或截图。重复请求返回 `BUSY`；拒绝、取消、服务断连、窗口变化、浮层不可用和协程取消都必须把同一 Room 请求收敛为非批准终态。

活动 generation 必须跟随真实 `rootInActiveWindow`，不得跟随 overlay 的状态事件。只有窗口集合精确等于显示前基线，或基线加唯一小灵自有 overlay 时，才可抑制该 overlay attach/detach 引起的 generation 作废；外来窗口、目标窗口内容变化、滚动、活动根切换、overlay 身份漂移或服务断连必须立即 fail-closed。用户点击批准/拒绝后先移除浮层，系统窗口事件确认自有 overlay 已消失后才能把决定返回 Runtime；无法确认移除时按 `OVERLAY_UNAVAILABLE` 拒绝。

执行前仍需同 Run 已验证且未过 30 秒的 snapshot、实时 ref/fingerprint、当前 generation、当前进程审批和完整参数。执行后必须重新观察并得到同一 ToolCall 的白名单 `workflow-device-action-result-v1`、`executorVerified=true` 与 typed `PASSED`；结果出现任何额外字段、snapshot/ref、节点、指纹、坐标或原始参数都必须拒绝。Workflow step、Run result、下一步输入和 Compose 只能消费 `workflow-device-action-decision-v1` 本地判定。

真实验收只使用 Redmi `wsvwypiz7xwslvl7`，至少确认 overlay 的 flags、标题、导航栏避让、活动窗口保持、Room `APPROVED`、Tool Ledger `PASSED`、后置页面变化和输出零泄露。阶段完成门禁为聚焦 JVM、完整 Debug JVM、Debug/AndroidTest APK 与该真实动作核心路径；Lint、Release 和默认完整 instrumentation 仍按分级验证留到里程碑或明确发版。

## 前台 Workflow 有限设备动作安全基础契约（第 112 阶段冻结）

前台手动 Workflow 的设备动作必须先经过独立、默认全关闭的安全策略 Module，不得直接放宽 `XiaoLingToolRegistry` 的全部动作集。策略只接受 `invocationSource=WORKFLOW + executionOrigin=FOREGROUND` 和显式列入当前阶段白名单的动作；后台、定时、恢复自动续跑、坐标、截图、视觉定位和任意 App 仍固定拒绝。第 112 阶段冻结时生产白名单为空；第 113 阶段只将 `device.tap_ref` 作为首个最小切片接入，其余动作继续遵守默认拒绝。

每个动作必须同时绑定用户明确编写的 Workflow 步骤意图、当前 `workflowRunId / workflowStepId / agentRunId / toolCallId`、完整参数和一个独立审批。审批必须是当前进程会话内的实时用户决定；前一个动作的批准、旧 Run 审批、关联重试审批、进程重建前已批准决定和只按工具名匹配的决定都不得复用。审批、参数、Run 或 ToolCall 任一漂移时必须 fail-closed。

动作执行前必须存在同一 Agent Run 中已通过验证、尚在 30 秒有效期内的 `device.snapshot` 证据，并确认当前 window generation 未漂移。`tap_ref / type_text / swipe` 还必须使用该 snapshot 的 ref，并在 Accessibility Adapter 内再次核对 node path 与 fingerprint；旧 Workflow 输出、`reusedFromStepId`、历史 Tool Ledger 或进程重建前的 ref 不得恢复为可执行引用。页面漂移、ref 过期、服务断连、权限变化、取消或证据缺失都必须在 Executor 之前拒绝。

动作结束后必须由现有 `DeviceController` Adapter 重新 snapshot，并将同一 ToolCall 的成功 ToolResult、`executorVerified=true`、typed `tool.verify=PASSED` 和后置观察绑定为完成资格。“Android 已接收动作”、只有 ToolResult、验证为假、后置快照缺失、取消后迟到结果或身份不一致都不得宣称完成。后续生产接线必须把动作前后白名单证据写入独立 Tool Ledger，Workflow step、Run result、`previousOutputs` 和 Compose 只消费版本化本地判定，不复制节点正文、ref、指纹、坐标或原始快照。

验收至少覆盖：默认全关闭；仅前台 Workflow 来源可进入策略；用户意图、当前 Run/Step/ToolCall、同 Run snapshot、window generation、ref 和当前进程审批的独立漂移矩阵；每个动作必须独立审批；重试、恢复和取消不复用旧证据；后置观察与 typed 验证缺失时拒绝完成。第一个生产动作只能在该纯 Kotlin 契约经过聚焦 JVM 门禁后另立阶段接入，并只用 Redmi `wsvwypiz7xwslvl7` 做真实动作验收。

## 前台 Workflow 设备观察双 Run 与持久化净化边界

设备观察 Workflow 必须能由两个独立 Agent Run 完成：观察 Run 只调用 `device.snapshot`，消费 Run 不得再调用任何设备工具，只能使用前序步骤传入的版本化本地判定。两个 Run 必须各自建立 Tool Ledger，旧 Run 的工具调用不能被视为新 Run 已完成的步骤；验收必须确认没有设备动作或审批请求。

Workflow 步骤进入 `COMPLETED` 前，Repository 必须从该 step 关联的同 Run 持久 Tool Ledger 重新生成设备观察判定，不得相信调用方传入的模型正文或判定副本。单步骤兼容调用直接收敛 `completeRun()` 时也不得旁路该规则；最终 Run result 必须从已净化 step 重新聚合。`verificationStatus=PASSED` 是可读结果的验证事实；`executorVerified` 对 `RESULT_READABLE` 工具可为 `null`，不得因此降级或拒绝已通过验证的 snapshot。调用方显式提供的判定与 Ledger 当前投影不一致时必须 fail-closed。

只要同 Run 存在合法设备观察，step `result` 与 `outputSnapshot.text` 就必须被替换为 `workflow-device-observation-v1` 白名单判定；前台 Workflow 会话文本与后台完成消息也必须发布同一净化文本。原始快照、节点、ref、坐标、动作数组和模型转述只能保留在独立 Agent Tool Ledger 中审计，不得复制到 Workflow step、Run result、下一步 `previousOutputs` 或后台会话文本。

真实验收至少检查：两个 Agent Run 分别只有一次指定工具调用；观察结果 `success=true / PASSED`；审批数为零；第一步 `result/outputSnapshot.text` 与第二步 `previousOutputs[0]` 对 `snapshot_id / nodes / ref` 零命中；Workflow 总账完成；页面显示已验证、本地判断、规则版本、受限原因和节点引用过期说明。Debug Receiver 在应用进程存活期间直接写 Room 后，真机流程必须重建运行态或从 UI 正常保存 Profile，不得把旧 ViewModel Profile 快照误当当前配置。

## 前台 Workflow 设备观察本地判定边界

前台 Workflow 只能从同一 Agent Run 的持久化 Tool Ledger 生成设备观察本地判定。输入必须是 `device.snapshot`、`success=true`、验证通过且能被当前 `DeviceSnapshotCodec` 完整解码的快照；模型自由文本、跨 Run 结果、失败/未验证结果和畸形 JSON 均不得形成判定。规则版本固定为 `workflow-device-observation-v1`，结果只能确认采集时的应用包名、节点数量、脱敏数量、截断状态和采集时间。

本地判定只区分“可复核”和“有限可复核”：未脱敏且未截断时为可复核；存在脱敏节点或截断时为有限可复核。两者都不确认节点正文、用户目标完成、页面仍处于原状态或任何设备动作授权。窗口标题、snapshot ID、window ID/generation、节点正文、description、hint、ref、bounds、actions 和原始 JSON 不得进入判定 DTO、下一步 Prompt 或 Compose 根状态。

新步骤完成时，安全判定必须进入既有 Workflow output snapshot；下一步骤准备时仍需通过当前 step 的 `agentRunId`，或重试步骤的 `reusedFromStepId` 回查来源 Tool Ledger，并核对持久化判定与当前证据一致。合法设备观察必须用版本化本地判定替换模型步骤正文后再进入 `previousOutputs`；来源缺失、结果未验证、结构畸形或判定漂移必须以稳定原因 fail-closed，阻止后续步骤继续声称已确认设备事实。原始工具结果只保留在独立 Agent Tool Ledger 中审计，新 Workflow step 的 `result/outputSnapshot` 不得再保留原始快照或模型转述。

Workflow 详情页必须同时展示已验证来源、本地判定结果、规则版本、白名单输入摘要和明确的结论范围。该闭环不新增 Room 表或列，不开放 `open_app / back / home / tap_ref / type_text / swipe`，不恢复历史 ref，也不改变后台/定时 Workflow、截图、坐标、视觉定位、任意 App、精确定时或 Foreground Service 边界。验收至少覆盖完整/受限判定、非设备结果不受影响、跨 Run/失败/未验证/畸形证据拒绝、安全 output snapshot、下一步 Prompt 替换、关联重试来源回查、证据不足阻断和 Redmi Compose 展示。

## 前台 Workflow 设备观察证据 UI 边界

Workflow 管理页必须从持久化 Agent Tool Ledger 而不是模型自由文本生成设备观察证据。每个 Workflow step 只能通过自己已保存的 `agentRunId` 关联对应 Ledger；结果必须同时满足 Run 身份一致、工具名为 `device.snapshot`、`success=true`、`verificationStatus=PASSED` 且快照 JSON 结构合法，才能标记为“已验证”。结构合法必须能对齐当前 `DeviceSnapshotCodec` 的顶层身份、窗口、时间、计数字段和逐节点对象；节点必须具备索引、层级、角色、边界、布尔状态与动作数组，脱敏计数必须与节点一致。失败、未验证、跨 Run、非 snapshot 或任意层级畸形的结果必须 fail-closed，不得生成已确认证据。

证据白名单只包括应用包名、节点数量、脱敏节点数量、是否截断、采集时间和工具执行耗时。窗口标题、snapshot ID、window ID/generation、节点正文、description、hint、ref、bounds、actions 与原始 JSON 不得进入 Workflow UI state。Ledger 批量读取后必须在 IO 加载边界立即收敛为安全 DTO；不得把完整 JSON 复制到 Workflow 表、Compose 根状态、文档、日志或新的 Room 列。

旧 Workflow step output、previous outputs 和 Workflow Run result 可能已经持有模型转述的完整 snapshot JSON。只要文本同时出现 `nodes` 与至少三类设备快照特征，历史页必须整段替换为稳定脱敏提示；识别必须兼容 snake_case、camelCase 和再次 JSON 转义形态。宁可少展示模型原文也不能回流节点数据。证据卡必须明确持久化 ref 已过期、不可用于后续动作；任何动作前仍需在允许的前台直接 `/agent` 中重新 snapshot，不得从历史 UI 复活 ref。

验收至少覆盖：合法 `PASSED` snapshot 只产生白名单字段；窗口标题、节点正文、ref 和 snapshot ID 不存在于证据 DTO；失败、`FAILED`、畸形、非 snapshot 与跨 Run 结果均被忽略；旧步骤输出、前序输入和 Run 汇总被脱敏；Compose 页面显示已验证证据和过期提示；Redmi 真实历史 Ledger 能显示包名、节点/脱敏数和耗时，且最终页面层级不含原始快照字段。该 UI 不开放设备动作、截图、坐标、视觉定位、任意 App 或后台设备工具。

## 前台 Workflow 只读设备观察边界

用户主动在应用前台运行 Workflow 时，只允许在设备 Agent 独立开关开启、Accessibility 已授权且 Profile/Skill 白名单允许的前提下暴露 `device.snapshot`。该组合固定为 `invocationSource=WORKFLOW + executionOrigin=FOREGROUND`；`device.open_app / back / home / tap_ref / type_text / swipe` 不得进入模型工具清单，强行执行时也必须由 Executor 二次拒绝。前台直接 `/agent` 继续保留既有限定动作集；`BACKGROUND` 来源无论来自直接 Run、Workflow、定时 Worker 或恢复链，都不得看到或执行任何设备工具。

`device.snapshot` 继续复用既有 200 节点、4,000 字符、敏感节点脱敏、整窗隐私拒绝和 30 秒 ref 生命周期，不增加截图、坐标、手势、任意 App 或写操作能力。Workflow 只读观察不产生设备动作审批；如果后续步骤需要动作，必须明确失败并要求用户转到前台直接 `/agent`，不能由模型把 snapshot 权限解释为动作授权。

审批恢复必须保留原 Run 的调用来源。应用需从 Room 中已持久化的 WorkflowRun↔AgentRun 关联判断 `WORKFLOW / DIRECT`，并在 IO 调度完成查询；Runtime 恢复上下文必须显式携带该来源，不能因默认值把 Workflow Run 恢复成直接对话权限。工具清单与执行层必须共享同一只读/动作边界，任一层缺少上下文、开关关闭、服务断连或来源不合法时都要 fail-closed。

验收至少覆盖：前台直接 Run 可见既有设备工具；前台 Workflow 只可见且可执行 `device.snapshot`；Workflow 强行执行动作被拒绝且控制器未收到动作；后台 Workflow 看不到 snapshot 且执行失败；审批恢复保留 `WORKFLOW` 来源；Room 关联在绑定 AgentRun 前为 false、绑定后为 true。Redmi 真实验收必须证明 Workflow 只产生 snapshot ToolResult、没有动作和审批，并在结束后恢复设备 Agent 与 Accessibility 状态。

## 知识质量工程：answerability Shadow 匿名跨进程观测边界

只有用户显式开启、冻结 Judge 身份与当前 Provider 配置完全匹配、来源为前台直接 `/agent`，且最终答案已成功保存时，answerability Shadow 才能请求 Judge 并以 `OPTIONAL` 模式写入观测账本。普通聊天、Workflow、后台 Worker、身份漂移、候选缺失或答案保存失败不得写入。Judge 与 Store 仍是答案发布后的旁路；任一失败不得改写答案、知识引用、可信上下文、Agent Run 终态或主失败路径，协程取消继续传播。

Room v33 的 `knowledge_answerability_shadow_observations` 必须以 64 位小写 SHA-256 幂等键为主键，重复写入只保留首次观测，并按时间最多保留 2,000 条。表只允许保存幂等键、候选摘要、Keystore 安装级密钥生成的 Judge HMAC-SHA-256 匿名桶、观测/绑定/判定/失败枚举、attempt、延迟/TTFB、Prompt 字节、Tokens、usage 次数、稳定失败计数和记录时间。禁止保存消息 ID、Run ID、问题、答案、候选正文、引用正文或身份、原始响应、Provider ID、模型、Base URL、API Key 或其他凭据；Store 必须拒绝不符合 SHA-256 形状的上游摘要，Judge 桶不得使用可按公开配置枚举的无盐摘要。

设置页必须把跨进程匿名累计与当前进程样本/notice 生命周期分开展示。notice 继续使用短生命周期 `messageId` Map，重启后不得从历史消息恢复。v32→v33 迁移只能创建空表，不得从历史消息、Run、检索审计或第 97–101 阶段人工汇总猜造记录；旧阶段“不持久化”是当时证据下的历史边界，保留原文，不得改写成已由新账本采集。生产相关性拒绝与 answerability enforcement 继续关闭；本切片不改变检索排序、答案、Workflow/后台权限、ANN 或自动后台索引重建。

应用冷启动时，跨进程摘要读取可能先于 Profile、会话和 Workflow 初始化完成。后续整表状态重建必须保留已经读取的 `answerabilityShadowPersistentSummary`、当前 Shadow 开关和进程内摘要，禁止使用默认零值覆盖真实 Room 累计。该合并只恢复 UI 投影，不得改写匿名账本、补造记录或把进程内 notice 持久化。

验收必须覆盖 `OPTIONAL` 生产请求、持久记录携带数值遥测、幂等重复、数据库关闭重开、未知数值保持 `null`、无 attempt telemetry 的最终稳定失败分类、Judge HMAC 匿名分桶、公开 SHA-256 与落库 HMAC 不同、第 2,001 条裁剪最旧记录、v32→v33 空迁移、Schema/表值隐私、原始正文误入摘要字段的拒绝，以及冷启动状态重建不会丢失已加载跨进程摘要。当前实现已通过本地 `141/141` tasks、JVM `734/734`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 最终 JUnit XML `248` 条（`236 passed / 12 skipped / 0 failed`）；第 104 阶段新增聚焦 JVM 回归和 Redmi 冷启动真实摘要复验。第 107 阶段进一步通过真实预算耗尽 Run 验证“没有成功答案不消费一次性授权”，并形成第三条独立同日记录；三条仍不授予长期分隔、校准、导出或生产拒绝资格。更新后的项目文档 corpus gate 首次与写回设备收尾后的最终复验均为 `OK (1 test)`，正式发布基线与当前 Room v33 开发设备态继续分开记录。

## 成功 ToolResult 缺少 typed 验证结论的闭环审计边界（通用执行恢复矩阵）

成功 ToolResult 已落库但没有 typed `tool.verify` 时，不得从 `ToolResult.verified`、工具名、事件顺序或当前 Registry 猜造 `PASSED / FAILED`。现有严格白名单只读验证资格保持不变；其他形状没有唯一可持久化安全动作，必须关联新 Run 或 fail-closed，不得新增原 Run 恢复、旧 Executor 重放、LLM 总结或 Workflow 后续步骤。

处置必须依次核验当前工具定义存在性、`COMMITTED + IDEMPOTENT_BY_KEY` 提交证据、当前工具是否显式开放只读恢复验证。`definition=null + support=false` 必须返回 `TOOL_DEFINITION_UNAVAILABLE`；定义存在但回执缺失/损坏且 `support=false` 必须返回 `COMMITTED_EFFECT_EVIDENCE_INVALID`；只有定义与提交证据完整但 `support=false` 才返回 `COMMITTED_VERIFICATION_UNAVAILABLE`。前两类错误阶段不得调用 support 回调，避免较宽泛的能力结论遮蔽更准确的持久化事实。

验收必须覆盖上述三类组合，并证明处置优先级调整不改变任何恢复资格。当前实现已通过强制本地 `141/141` tasks、JVM `734/734`、Lint `0 error / 52 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (243 tests)`（`95.348s`）；Room Schema 保持 v32。

## 持久化失败工具验证原子失败终态结算边界（通用执行恢复矩阵）

进程在成功 ToolResult、结果后的执行预算以及 typed 失败验证均已落库，但正常 Runtime 尚未把验证 Step/Run 写成失败终态时，只允许补齐与原验证异常路径等价的失败控制面。资格必须同时满足：Run 为 `VERIFYING` 且没有终态字段；证据来自完整非空 v20 Tool Ledger；所有 ToolResult 均为成功，前序验证全部为 `PASSED`，唯一链尾验证为 `FAILED` 且带非空稳定原因；预算证据必须为 `Available` 并位于链尾 ToolResult 之后；执行与验证 Step 和 typed 创建/完成事件一一对应，链尾 `TOOL_VERIFY` 是最后一个 Step、仍为 `RUNNING` 且没有状态事件；原 Profile 允许全部工具；不存在待审批或失败验证后的任何控制面/业务事件。

命中资格时，Repository 必须在 `closeInterruptedRuns()` 的单个 Room transaction 内把链尾验证 Step 更新为 `FAILED`，以条件状态更新把原 Run 从 `VERIFYING` 改为 `FAILED`，并写入 typed `run.recovered(PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status=FAILED`。失败原因只能来自已持久化 `tool.verify`；不得调用 Executor、验证器或 LLM，不得追加第二条 ToolResult/验证事实、成功总结或 Workflow 后续步骤。重复进入必须无变更，双 Repository 并发只能有一个成功者，任一终态审计写入失败必须回滚 Step、Run 和全部新事件。

以下形状必须继续 fail-closed：Legacy/event-only 或不完整 Ledger、预算缺失/损坏/未位于结果之后、ToolResult 失败、前序失败或未验证、链尾验证不是 `FAILED`、失败原因缺失、Run 非 `VERIFYING`、Step/Event 身份或 sequence 漂移、验证 Step 已终态或不是最后一步、待审批、尾随事件以及既有 Run 终态字段。尤其不能把“成功结果但尚无验证结论”推断为失败；该窗口仍按提交后验证不可用边界处理。

验收必须覆盖策略正例和预算/原因/Ledger/尾部拒绝矩阵、Codec round-trip、Runtime 在失败验证落库后的故障注入、Executor 只执行一次、真实 Room 双 Repository 并发与事务回滚。当前实现已通过强制本地 `141/141` tasks、JVM `732/732`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (243 tests)`（`96.162s`）。Room Schema 保持 v32，真机只允许 Redmi `wsvwypiz7xwslvl7`。

## 持久化失败 ToolResult 原子失败终态结算边界（通用执行恢复矩阵）

进程在失败 ToolResult 与执行预算已落库、但正常 Runtime 尚未把 Step/Run 写成失败终态时，只允许补齐与原 Runtime catch 等价的失败控制面结算，不得把该窗口解释为工具重放、验证恢复或继续执行。资格必须同时满足：Run 为 `EXECUTING` 且尚无终态结果、错误或完成时间；证据来自 v20 完整非空 Tool Ledger；所有前序 ToolResult 成功且 `tool.verify=PASSED`；唯一链尾 ToolResult 为 `success=false`、错误非空、没有验证事实；链尾结果后恰有一份完整执行预算快照；执行/验证 Step 与账本一一对应，前序 Step 已完成，链尾 `TOOL_EXECUTE` 是最后一个 Step 且仍为 `RUNNING`；原 Profile 仍允许全部工具；不存在待审批、额外 Step 或其他业务尾部。

命中资格时，Repository 必须在 `closeInterruptedRuns()` 已有的单个 Room transaction 内把链尾执行 Step 更新为 `FAILED`，以条件状态更新把原 Run 从 `EXECUTING` 改为 `FAILED`，并按同一原子边界写入 typed `run.recovered(PERSISTED_TOOL_FAILURE_SETTLEMENT)`、`run.failed` 和 `run.status=FAILED`。失败原因必须来自已持久化 ToolResult；不得调用 Executor、验证器或 LLM，不得追加 ToolResult、`tool.verify`、成功可信上下文或 Workflow 后续步骤。重复进入必须返回无变更；并发 Repository 只能有一个成功结算者；任一终态审计写入失败必须回滚 Step、Run 和全部新事件。

以下形状必须继续 fail-closed：v19 及更早 typed-event fallback、空或不完整 Ledger、成功链尾、失败错误为空、前序失败或未验证、预算缺失/损坏/漂移、失败结果后不是恰好一份完整预算、Run 非 `EXECUTING`、Step 身份/sequence/type/status 漂移、链尾执行 Step 不是最后一步、待审批、`tool.verify`、额外 Step/业务事件、多条失败结果或非链尾失败结果。链尾 ToolResult 缺失仍属于提交状态未知；成功 ToolResult 已持久化但验证事实不完整仍不得借用本需求结算。

验收必须覆盖策略正例与上述拒绝矩阵、Codec round-trip、Runtime 故障注入后 Executor 只执行一次、真实 Room 磁盘重开、重复与双 Repository 并发、事务回滚，以及结算后重试证据稳定。当前实现已通过强制本地 `141/141` tasks、JVM `726/726`、Lint `0 error / 51 warnings`、Debug/AndroidTest/R8 Release APK、Release lintVital，以及仅 Redmi 默认完整 `OK (240 tests)`（`93.258s`）。Room Schema 保持 v32，真机只允许 Redmi `wsvwypiz7xwslvl7`。

## 已提交与已验证控制面幂等收尾边界（通用执行恢复矩阵）

已提交结果只读验证与全部已验证后的控制面收尾必须共享严格的持久化身份边界。每条恢复 marker 必须同时保存恢复类型、稳定边界键、来源/目标状态和具体原因；同一恢复边界只允许一条完整一致的 marker。边界后的第二条、损坏、字段不完整或内容冲突的 marker 必须 fail-closed，不得因为先遇到一条合法记录而忽略后续漂移。状态变化需要 CAS 时，Run 状态、`run.status` 事件与 marker 必须在同一 Room transaction 提交；启动关闭旧 Run 的活动 Step、待审批、Recovery 与 Run 终态也必须原子收敛。

`step.created / step.status` 必须使用 typed metadata 绑定 `stepId`、sequence、step type 和 from/to status。恢复策略必须逐项核对最后验证 Step、恢复总结 Step 与对应事件，不得只按事件类型、文案或时间邻近猜测归属。恢复入口写入或命中 marker 后必须重新读取 Room Detail 并重新评估；旧快照不能继续进入 Runtime。`COMPLETED recovery.summarize` 缺少 typed 总结事件、总结后出现模型/审批/工具等业务事件、Step/Event 身份不一致或边界漂移时必须拒绝恢复。

全部 ToolResult 成功且所有 `tool.verify` 为 `PASSED` 时，只允许三个可达控制面阶段幂等重入：尚未创建恢复总结；恢复总结 Step 为 `RUNNING`，typed 总结事件可能尚未写入或已经写入；恢复总结 Step 与 typed 事件已完成但 Run 尚未进入终态。总结 Step 与事件必须在 Room transaction 内 get-or-create，并发启动协调器只能留下一个 Step、一个总结事件和一个 Run 终态。该路径只能重建已持久化可信工具上下文并生成本地总结，不得调用旧 LLM、Executor、重放工具、追加第二条验证事实或继续 Workflow 后续步骤。

验收必须覆盖 marker 缺失/重复/损坏/冲突、状态 CAS 漂移、事务回滚、typed Step 身份错配、不可达业务尾部、已完成总结缺事件、三个可达总结持久化窗口、重复与双协程并发恢复、启动关闭原子收敛，以及不调用旧模型/Executor/Workflow。当前恢复聚焦 JVM `123/123`、完整 JVM `717/717`，强制 Gradle `141/141` tasks、Lint `0 error / 51 warnings` 和三类 APK 通过；仅 Redmi Room 定向 `OK (36 tests)`。解锁并保持唤醒后的 Redmi 默认完整为 `OK (237 tests)`、耗时 `93.062s`；首轮锁屏的 `59` 条前台失败已由失败单项和完整复验确认不是产品回归。最终 README/docs 语料门禁为 `OK (1 test)`，正式 `v0.1.13` 已恢复。Room 保持 v32，设备工具仍不进入 Workflow/后台。

## 尚未提交受控关联重试边界（通用执行恢复矩阵）

只有已持久化 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE` 的旧 Run 才能进入受控关联重试。请求和确认必须分别读取 Room 最新 Detail，重新核对来源 Profile、当前 Registry、typed `run.recovered -> run.status=CANCELLED` 收敛链、`fromStatus=EXECUTING / toStatus=CANCELLED`、恢复处置码和重试证据指纹；任何恢复后业务事件、Tool Ledger、状态、定义或指纹漂移都必须 fail-closed。普通 `NOT_COMMITTED` 不得被自动升级为受控重放。

第一次用户确认只允许创建关联新 Run，不代表工具审批。确认通过后必须继续恢复来源 USER 附件并使用来源 Profile/Provider preflight；创建新 Run 前必须再次从 Room 与生产 Registry 权威重核完整资格，执行前还要匹配冻结恢复契约。新 Run 必须带 `retryOfRunId`，生成与来源不同的 ToolCall ID，并持久化来源 Run、来源 ToolCall、新 ToolCall 和定义指纹。来源工具名称、风险和参数不得重新规划或改写。

新 Run 必须走正常工具校验、权限和逐次审批；批准后只允许执行该冻结调用一次并直接总结，不得调用模型规划额外工具。旧 Run、旧 Tool Ledger、旧审批、旧模型协程、旧 Executor 和旧 Workflow 必须保持不变。受控重放只开放前台直接 Agent Run 重试，不进入 Workflow 或后台自动化；Room Schema 保持 v32，真机验收只使用 Redmi `wsvwypiz7xwslvl7`。

验收必须覆盖：收敛事件缺失或状态错误、恢复后异常业务事件、证据/当前定义/处置码漂移、请求与确认两次 Room 读取、创建前第三次重核、来源 Profile、新 ToolCall ID、新审批、零次模型规划、工具只执行一次、关联审计 Codec 持久化和旧 Run 完全不变。当前实现已通过 JVM `707/707`、仅 Redmi 真实磁盘纵向 `OK (1 test)`、专用 Compose `OK (1 test)` 和默认完整 instrumentation `OK (235 tests)`；本需求文本第一次重新打包后的项目语料为 `OK (1 test)`，写回验收与设备收尾结果后的最终复验同为 `OK (1 test)`。

## 尚未提交安全重放资格边界（通用执行恢复矩阵）

`NOT_COMMITTED` 只能说明当前持久化证据没有进入工具副作用边界，不能直接授权重放。工具默认必须使用 `ToolNotCommittedReplayPolicy.DENY`；只有同时具备稳定幂等键、显式 `CONTROLLED_SAME_CALL` 和逐次用户审批的工具才允许进入资格评估。当前生产范围只包括 `notes.create` 与 `memory.remember`，新工具不得因为风险等级或当前实现看似安全而自动继承资格。

Runtime 必须在 ToolCall proposed 与 validated 事件中持久化同一版本化恢复契约。契约必须冻结工具名称、说明、风险、审批/验证/重放策略、超时、后台能力、Android 权限、输入 Schema 和业务校验器版本语义，并使用 canonical SHA-256 指纹防止当前 Registry 事后升级历史证据。未知未来策略、缺少快照、Schema/契约版本不匹配或当前定义漂移必须 fail-closed。业务校验器代码无法稳定序列化时，语义变化必须显式递增契约版本。

Run 资格必须同时满足：状态为 `EXECUTING`；原 Profile 快照允许该工具；独立 Tool Ledger 完整；链尾 ToolCall 已 validated、没有 ToolResult、没有对应 `TOOL_EXECUTE`；所有前序调用成功且验证通过；唯一审批记录为 `APPROVED`。审批 requested 事件必须保留原始 `PENDING`，requested/decided 的工具、风险、参数和定义指纹必须一致，顺序必须为 validated→requested→decided；最后一个审批 Step 必须完成，且其后不得出现任何步骤。任一字段、事件顺序、步骤或定义漂移均拒绝资格；在本资格评估中，链尾仍缺少 ToolResult 却已出现执行步骤时必须继续按 `COMMIT_UNKNOWN` 处理。已持久化且严格符合失败结算契约的失败 ToolResult 由独立失败终态结算边界处理，不属于本重放资格。

资格通过只允许持久化 `RESTART_REQUIRED / NOT_COMMITTED_REPLAY_ELIGIBLE`，供后继受控关联重试在重新核验证据和用户控制下创建 `retryOfRunId` 新 Run。本资格阶段不得调用工具、恢复旧模型协程/Executor、继续旧 Workflow、伪造 ToolResult 或原地继续旧 Run；启动收敛继续把旧 Run 与活动 Step 置为 `CANCELLED`。Room Schema 保持 v32。验收必须覆盖当前定义、参数、审批指纹、状态与事件顺序漂移，历史契约缺失、默认拒绝、执行步骤降级、Codec 未知策略、Runtime 双事件一致性和 Room 磁盘重开；真机只允许 Redmi `wsvwypiz7xwslvl7`。

## Agent 启动前校验协调边界（横向可靠性工程）

普通 `/agent`、Workflow 首次运行、Workflow Run 重试、Agent Run 关联重试和恢复后审批必须通过单一 `AgentLaunchPreflightCoordinator` 完成启动前校验。普通 `/agent` 的会话为可选，继续允许既有发送链创建新会话；其余四类入口必须先证明指定会话仍存在，并保留各入口原有的缺失提示。会话错误必须先于 Profile、未注册工具和 Provider 错误返回。

普通 `/agent`、Workflow 与两类重试必须使用当前选中 Profile；恢复后审批必须优先使用原 Run 的 Profile 快照，只有旧 Run 没有有效快照时才能回退当前 Profile。Profile 校验顺序固定为可运行性、未知工具、Provider 请求配置；未知工具名称必须稳定排序。Provider 仍按当前保存配置解析，校验只保证当前时刻一致性，不得在迁出时新增执行前二次校验或改成 Workflow 逐步骤重校验。

协调器必须为同步、无 UI/Room/网络副作用的纯决策边界，只返回强类型 `Ready / Rejected`。成功后的导航、确认弹层清理、附件读取、发送态、Room 写入、Runtime 调用与 Workflow 后续步骤继续由 ViewModel 宿主负责。`ProviderRequestConfig` 含解密 API Key，只能在当前进程启动链内传递，不得写入日志、UI、Room 或事件；其字符串表示必须脱敏 Base URL、API Key 和全部自定义 Header。

验收必须覆盖会话错误优先、Profile 缺失/非法、未知工具排序、Provider 缺失/URL/模型错误、普通空会话、历史快照优先、旧 Run 缺少快照时回退当前 Profile、冻结配置与凭据字符串脱敏。当前聚焦 JVM 为 `10/10 + 1/1`；完整门禁为 `140/140` tasks、JVM `656/656`、Lint `0 error / 50 warnings / 0 information`、三类 APK、仅 Redmi 默认完整 `196` 条（`184 passed / 12 skipped / 0 failed`）与最终文档语料 `OK (1 test)`。本切片保持 Room v32，不采集 Shadow 样本，不改变第 101/102 项及设备后台门禁。

## Provider 模型同步协调边界（横向可靠性工程）

已保存 Provider 的 `/models` 同步必须由单一 `ProviderModelSyncCoordinator` 编排。网络请求前必须校验 Base URL，并使用 trim 后的 Base URL/API Key、空模型和当前可配置 User-Agent 构造请求；上游模型按返回顺序去重，当前模型仍有效时继续保留，否则回退到首个模型或空值。`availableModels / enabledModels` 必须延续现有行为更新为本次上游全集，不得在迁出过程中顺手改成旧启用列表的交集。

批量同步必须按用户看到的 Provider 顺序逐项执行并发布结果；普通网络、协议、认证或保存失败只收敛当前项并继续下一项，协程取消必须原样传播并终止后续项。不同 Provider 的单项网络请求可以并行，但完整 Provider 快照提交必须通过同一互斥边界串行，避免迟到结果覆盖另一项刚保存的配置。

提交前必须等待更早的 Provider 快照保存结束，并从最新 UI 快照重新查找 Provider。规范化 `/models` URL 或 trim 后 API Key 漂移时返回 `Stale`，Provider 已删除时返回 `Missing`；名称和当前模型采用最新用户配置。Room 保存期间若 Provider 列表、选中项或身份再次变化，必须拒绝迟到结果并重新排队最新快照。只有 `ProviderRepository.save()` 成功后才能发布 `Succeeded`，随后才允许修复空模型 Agent Profile；网络成功不得冒充配置已持久化。

协调器只返回 `Invalid / Failed / Missing / Stale / Succeeded` 强类型结果，不持有 Compose 页面状态、Agent Runtime、Workflow 或 Shadow。ViewModel 只负责 busy、批量逐项结果和弹窗投影。验收必须覆盖请求规范化、去重/回退、无效 URL 网络前拒绝、稳定失败分型、取消传播、Provider 删除、身份漂移、批量顺序与失败继续，以及并发提交串行。当前聚焦 `8/8`、完整 JVM `645/645`、Lint `0 error / 50 warnings`、三类 APK、仅 Redmi 默认完整 `OK (196 tests)` 与最终文档语料 `OK (1 test)` 通过。Room v32、第 101/102 项及设备后台门禁不变。

## 候选记忆协调边界（横向可靠性工程）

候选记忆的有界列表、成功回合来源身份、采集和接受/拒绝必须由单一 `AgentMemoryCandidateCoordinator` 编排。普通聊天来源必须包含会话 ID、空 Run ID 和稳定摘要，Agent Run 来源必须携带同一会话与真实 Run ID；Store 返回无候选、记录缺失和异常必须分别映射为 `Ignored`、`Missing` 和 `Failed`，不得把正常空结果伪造成错误。

同一候选 ID 的接受与拒绝必须先领取短生命周期 claim；第二个决定返回 `Busy`，不得并发写 Room。不同候选不能被全局串行化。Room 异常、协程取消和外层 Job 取消均必须释放 claim，取消继续原样传播，后续操作可以重试。关闭候选功能必须取消旧列表 Job、清空 UI 列表并结束 loading；迟到 Room 读取不得重新填充已关闭界面，但关闭开关不得删除候选或正式记忆。

协调器只能路由现有列表、创建、接受和拒绝入口并返回 typed outcome，不得复制敏感过滤、规范化去重、同主题冲突、正式记忆写入、FTS 或 transaction。候选仍只在完整成功的普通聊天或 Agent Run 后采集，失败/取消回合不采集；ViewModel 继续负责 Compose 结果、页面 Job 和刷新。验收必须覆盖有界读取、两类来源、无候选/异常分型、接受/拒绝、Missing、同 ID Busy、不同 ID 并行及取消后重试。当前聚焦 `7/7`、完整 JVM `637/637`、Lint `0 error / 50 warnings / 1 hint`、三类 APK、仅 Redmi 默认完整 `OK (196 tests)` 与最终文档语料 `OK (1 test)` 通过。Room v32、第 101/102 项及设备后台门禁不变。

## 恢复后 Agent 审批协调边界（横向可靠性工程）

进程重建后重新展示的链尾审批必须由独立 `RecoveredAgentApprovalCoordinator` 编排，不得复用只管理当前进程 waiter 的 `AgentApprovalDecisionCoordinator`。每次批准或拒绝都必须重新读取 Room 中的 Run detail，并通过 `AgentRunResumePolicy` 确认 Run 仍为 `WAITING_APPROVAL`、恰好一个 `PENDING` Approval 与链尾 ToolCall 完全一致、所有前序工具均已验证且请求/Run/会话/ToolCall/参数身份未漂移。旧 UI、过期 request 和证据变化必须 fail-closed，不得读取内存历史继续执行；并发第二个决定必须返回 `Busy` 且保留其 `PENDING` 卡片，不得按 stale 清除。

批准路径必须在审批决定写入前恢复原 USER 消息的单一可信附件；附件、Skill、Profile 或配置前置失败且 Room 审批仍为合法 `PENDING` 时，卡片必须恢复 `deciding=false` 供用户重试，不能提前消失。停止发生在决定落库前时同样保留可重试卡片；决定已落库或 Run 已进入终态时不得把审批重新开放。真正执行仍由 `AgentRunUseCase.resumeApprovedRun()` 与 `MinimalAgentRuntime` 完成，继续使用原 Profile/Skill/预算/已验证前缀，不重放已完成工具。

拒绝路径必须在同一 Room transaction 内重新核验证据，依次把 Approval 写为 `DENIED`、活动审批 Step 写为 `FAILED`、原 Run 写为 `FAILED`；任一步异常必须整体回滚。Repository 返回 `null` 时不得补写 Run 终态。协调器只能返回 `Completed / Rejected / StillPending / Busy / Stale / Failed` 等强类型结果；Compose 投影、Provider 选择、消息保存、Workflow Ledger 与后续步骤仍由 ViewModel 宿主负责。

验收必须覆盖合法恢复只执行一次、最新证据漂移、附件失败保留审批、Repository 过期拒绝、原子拒绝顺序和批准/拒绝并发互斥。当前协调器 JVM 为 `6/6`，完整本地 JVM `630/630`、Lint `0 error / 50 warnings / 1 hint`，三类 APK 成功；仅 Redmi 默认完整 `OK (196 tests)`、耗时 `49.015s`，最终文档语料 `OK (1 test)`。本切片保持 Room v32，不采集 Shadow 样本，不改变第 101/102 项及设备后台门禁。

## Agent 审批决策协调边界（横向可靠性工程）

前台直接 Agent 和前台 Workflow 的进程内审批等待必须由单一 `AgentApprovalDecisionCoordinator` 管理。每次 Repository 创建审批后注册携带 `requestId + conversationId` 的独立 ticket；注册新 ticket 必须取消旧 waiter。用户批准或拒绝时只有匹配当前 `requestId` 的首次调用能领取 claim，连续点击、批准/拒绝交叉调用、过期 UI 和旧 claim 均不得启动第二次 Room 写入。

Room `decideApprovalRequest()` 成功返回后才能完成 waiter 并让 Runtime 继续。写入抛异常时必须只释放当前 claim、把同一审批投影恢复为 `deciding=false` 并显示可重试错误；Repository 因 Run 已终止、请求已处理或记录不存在返回 `null` 时必须取消 waiter，不得把未持久化的批准交给工具执行。停止生成必须取消当前 ticket 并使已领取 claim 失效；旧 ticket 的完成、释放和 `finally` 清理不得清除或完成后来注册的新审批。

协调器只能拥有进程内 ticket、claim 与 `CompletableDeferred`，不得写 Room、决定风险/策略、维护 Run/Workflow、投影 Compose 或恢复进程后的审批。Room v32 继续是审批事实源，`XiaoLingViewModel` 继续执行 Repository 写入、UI 错误呈现和宿主副作用。验收必须覆盖一次性领取、失败释放后重试、替换时旧 waiter 取消、停止取消、过期 ticket 隔离和 Repository 无可决定记录时 fail-closed。当前结果为聚焦 `5/5`、完整 JVM `624/624`、Lint `0 error / 50 warnings / 1 hint`、三类 APK 成功、仅 Redmi 默认完整 `OK (195 tests)`；7 份长期文档语料为 `OK (1 test)`。本切片不采集 Shadow 样本，不改变第 101/102 项边界。

## 会话级 Agent 运行态 Store 边界（横向可靠性工程）

进程内 Agent UI 运行态必须以 `conversationId` 为唯一归属，同时保存该会话最新 Run 和待审批投影。新 Run 只能替换同会话旧 Run；审批从等待进入 `deciding` 只能替换同会话审批，不得影响 Run 或其他会话。审批批准、拒绝或取消后只清除审批，Run 必须继续保留；删除会话时必须在新会话投影前同时清除该会话 Run 与审批。

新建占位会话必须显式返回空运行态，不能因 ID 复用或迟到加载恢复旧卡片；该投影不得反向删除 Store 中其他会话状态。启动恢复必须从持久化 Run/Approval 明细重建进程内 Store，并只把当前选中会话的状态投影给 UI。ViewModel 不得再绕过 Store 维护第二张 Run/Approval Map。

本 Store 只能承载进程内 UI 运行态，不得持久化、决定或执行审批，不得成为 Room Run/Approval、Agent Runtime、Tool Ledger、Workflow 或 answerability Shadow 的第二事实源。验收需覆盖同会话替换、跨会话隔离、`deciding` 更新、只清审批、整组清理和禁止占位恢复；完整门禁必须记录 JVM、Lint、Debug/Release/AndroidTest APK 与仅 Redmi instrumentation。当前结果为聚焦 `5/5`、完整 JVM `619/619`、Lint `0 error / 50 warnings`、默认完整 `OK (195 tests)`，第 101/102 项边界不变。

7 份长期文档更新后必须重新打入 AndroidTest assets，并通过 Redmi 项目文档语料门禁；本轮结果为 `OK (1 test)`。

## 第 101 项 answerability Shadow 持续观察边界

第 101 项是跨真实使用窗口的持续观察，不是一次性完成阶段。每个窗口只能在用户显式开启、同一应用进程、前台直接 `/agent`、答案成功保存且存在冻结知识候选时采样；不得在同一窗口堆样本，也不得断网、篡改认证或伪造协议错误制造自然失败。词法兜底必须如实记录，不得当作 Embedding 质量证据。

首个已记录窗口仅取得 `1` 条完成样本：词法查询 `Agent Run retryOfRunId` 命中 `3` 个候选，Judge 形成直接回答；样本、完成和 Judge attempt 均为 `1`，取消、异常、未知、跳过及旁路错误均为 `0`。成本为耗时 `5009ms`、TTFB `5002ms`、Prompt `10150B`、Tokens `2720/209/2929`、usage attempts `1`。关闭开关并删除测试会话后，notice 必须从有效 `1 / 裁剪 0` 变为有效 `0 / 裁剪 1` 且累计成本不回退；临时知识文档和下载文件必须删除，知识文档恢复为 `0`。

第 97 至 101 项已记录窗口只允许按阶段报告人工合计：样本 `10`、完成 `8`、无候选跳过 `2`，Judge `8` 次、直接回答 `5`、部分回答 `3`；成本 `43846ms / 43777ms / 66995B / 17164+1822=18986 Tokens`。不得把该合计表述为跨进程 tracker 或 Room 持久化。八次 Judge 均未出现自然网络、协议或认证失败，也没有明显成本异常，因此继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；不得提前进入第 102 项。文档同步后必须通过强制 JVM `614/614`、Lint `0 error`、Debug/AndroidTest APK、仅 Redmi 文档语料 `OK (1 test)` 与默认完整 `OK (195 tests)`。

## Agent Run 关联重试协调边界（横向可靠性工程）

失败、取消、阻塞或预算耗尽 Run 的关联重试必须继续使用 `AgentTaskRetryPolicy` 作为唯一资格与副作用证据来源。已有发送或重试正在进行、来源 Run 不存在、Run 已不再可重试时必须给出稳定失败事件；需要确认的 Run 必须冻结弹窗打开时的证据码和 canonical fingerprint。用户确认时必须重新读取当前 Run 详情并核对状态、证据码和指纹，即使分类仍相同，只要账本、Receipt、工具调用或验证证据漂移，也必须刷新确认并要求再次批准，不得沿用旧授权继续执行。

确认通过或无需确认时，协调器只进入准备阶段。原会话存在性、当前 Agent Profile/Provider 可运行性、会话导航和真正的 `sendAgentRun` 仍由 ViewModel 宿主负责；协调器只能异步读取来源 USER 消息的可信单一附件，并生成包含原会话、`/agent` 原目标、附件快照和 `retryOfRunId` 的启动请求。来源 Run 的状态、步骤、事件、审批与 Tool Ledger 均不得修改；新 Run 继续重新规划、审批和验证。附件读取失败必须发布稳定 `Failed` 事件并清理忙碌态，协程取消继续传播；用户取消确认必须发布 `Cancelled` 并只清理未决确认。

ViewModel 只投影 `ConfirmationRequired / ConfirmationRefreshed / PreparationRequired / RetryStarting / RetryReady / Failed / Cancelled` 事件，并继续保有 Compose 状态和宿主副作用。本次迁移不得修改 Room Schema、Agent Runtime、工具权限、Workflow、设备工具后台门禁或第 101 项 Shadow 低频规则。验收必须覆盖无需确认、成功写工具需确认、同码证据漂移、附件恢复、旧 Run 不变、`retryOfRunId`、附件读取失败、请求拒绝、确认来源消失/状态变化和用户取消；聚焦 JVM 为 `7/7`，完整门禁必须以实际 JVM、Lint、APK 和仅 Redmi 默认 instrumentation 结果记录。

## 第 100 阶段 Android 系统分享入口 v1 边界

系统分享入口只接收 `ACTION_SEND`，不得声明或兼容 `ACTION_SEND_MULTIPLE`；即使调用方在单个 `ACTION_SEND` 中塞入多项 `ClipData`，文本和图片也必须统一拒绝。Manifest 仅暴露 `text/plain`、`image/png`、`image/jpeg`、`image/jpg` 和 `image/webp`；不支持 GIF、PDF、任意文档或通配 `image/*`。文本需规范 CRLF/CR、去除首尾空白，规范化后必须非空且不超过 20,000 字符。图片必须只有一项、使用小写 `content://` URI，并继续通过既有 `ImageAttachmentReader` 的 8 MB、MIME、文件签名和解码验证；`EXTRA_STREAM` 与 `ClipData` 携带相同 URI 属于兼容性重复，携带不同 URI 必须按多图拒绝，不得为分享入口建立放宽的第二套附件读取路径。

外部 Intent 只能创建用户可编辑的新会话草稿，不得自动发送、调用普通聊天 Provider、启动 `/agent`、触发工具、Workflow 或后台任务。编辑器稳定空闲时可直接打开草稿；已有文本、图片、文档、未决分享或正在发送、附加、加载时不得静默覆盖。已有本地草稿时必须展示“打开分享/忽略分享”，只有用户明确打开才替换；已有未决分享时保留第一个并明确拒绝新的分享，用户处理完后需从来源应用重试。

冷启动分享必须等待 Room/Keystore 初始化完成后再投影，不能被初始化快照覆盖；`singleTop + onNewIntent` 必须支持运行中分享；Activity 重建不得重复导入。外部应用可以控制 referrer 和任意 Intent extra，因此二者不得作为可信来源身份或内部去重凭据；界面只显示稳定的“外部分享”提示，用户编辑、移除分享图片、图片读取失败或切换会话后清理提示。解析异常、空内容、超长文本、多附件、缺失 URI、非 `content://`、不支持 MIME 均需给出稳定拒绝结果，不得崩溃或降级为自动发送。

验收必须覆盖纯 Kotlin 解析/投影策略、Manifest MIME 与 `ACTION_SEND_MULTIPLE` 不可解析、双来源 URI 冲突、冷/热启动、Activity 重建、伪造 extra、草稿冲突、图片成功/失败校验、编辑/移除/切换会话后的来源提示清理和“无自动发送”。真机只允许 Redmi `wsvwypiz7xwslvl7`；完整门禁必须以实际 JVM、Lint、APK、文档语料和默认 instrumentation 结果记录，不能由第 99 阶段数字算术推导。

## 第 99 阶段 answerability shadow 低频观察边界

真实 Shadow 观察必须分散在间隔开的用户显式开启窗口，不得为了达到样本数量在同一窗口持续压测，也不得通过断网、篡改认证或伪造协议错误制造“自然失败”。每次显式开启最多授权一轮观测：只有候选存在、答案成功保存且调用前仍保持开启时才消费授权，开关检查、关闭和持久化必须在同一原子门禁内完成，多个并发答案只能有一个进入协调器；候选缺失、保存失败或提前撤销不消费窗口，观测开始后的成功、未知、取消或异常均不得让开关继续保持开启。每个窗口仍只允许同一应用进程中的 `DIRECT_FOREGROUND` `/agent` Run 进入 Judge；普通聊天、Workflow、后台 Worker 和未形成成功答案的 Agent Run 不得进入样本、attempt 或失败分母。

设置页必须从匿名跨进程摘要展示最早记录时间、最新记录时间和二者的实际跨度，使用设备本地时区并在证据缺失、顺序异常时显示未知。该投影只能帮助人工核对，不得内置未经预注册的小时/天数阈值，不得自动声明样本已满足分隔、calibration/validation、JSON/SAF 出口或 production enforcement 资格。

Agent Run 因无候选、预算耗尽或工具步数耗尽而失败时，只有确实进入 tracker 的稳定 Shadow 事件才允许计数；没有成功答案和合格候选时不得伪造 `SKIPPED`、Judge 失败、取消或 usage。知识检索使用词法兜底形成候选时必须如实记录，不能把 answerability Judge 结果表述为 Embedding 质量证据。

第 107 阶段 Redmi 真实窗口验证了这一边界：首次 Run 连续执行 4 次 `knowledge.search` 后因工具预算耗尽，没有成功答案，因此一次性授权保持开启，Room v33 匿名账本仍为 `2`，attempt、usage 和失败分桶均不增加；随后第二个 Run 只执行 1 次检索并成功保存答案后才消费授权，新增第三条 `COMPLETED / BOUND / ACCEPT`。当前三条最早到最新跨度 `5 小时 24 分 46.689 秒`，仍只属于同日证据，不满足长期分隔、calibration/validation、JSON/SAF 或 production enforcement 资格。

第 99 阶段首批窗口新增样本 `3`、完成 `3`、Judge `3` 次，直接回答 `2`、部分回答 `1`；取消、异常、答案保存失败、Shadow Store 失败和绑定未知均为 `0`。成本为耗时 `15737ms`、TTFB `15708ms`、Prompt `17930B`、Tokens `4474/638/5112`。关闭开关只能撤销后续授权；删除四个测试会话后 notice 必须从有效 `3 / 裁剪 0` 变为有效 `0 / 裁剪 3` 且累计成本不回退，删除临时知识文档后恢复文档 `0`、原会话 `1`。

跨阶段样本合计只允许从阶段报告人工汇总，不得暗示 tracker 已跨进程持久化。第 97 至 99 阶段书面记录合计样本 `9`、完成 `7`、无候选跳过 `2`，Judge `7` 次仍没有自然网络、协议或认证失败；该证据不得用于增加 Room Store、Schema、跨进程 notice 或生产拒绝。继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。验收必须同时通过 JVM `600/600`、Lint `0 error`、Debug/AndroidTest APK、Redmi 文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)`。

## 第 98 阶段 answerability shadow Redmi 扩样本验收边界

扩样本必须限定在同一应用进程、用户显式开启、`DIRECT_FOREGROUND` 且答案成功保存的真实 `/agent` 链路。没有可用知识候选时必须记为 `SKIPPED / NO_CANDIDATE`，不得调用 Judge，也不得伪造成 `UNKNOWN`、失败、取消或 token usage。自然 `BUDGET_EXHAUSTED` Agent Run 若未满足 Shadow eligibility，不得进入 Shadow 样本、attempt 或失败分母。

关闭开关只能撤销后续 Shadow 授权，不得删除或修改知识文档、会话及消息。删除测试会话只能裁剪对应进程内 notice，不得回退累计样本和成本；删除本轮测试知识文档后，知识文档必须恢复为 `0`，并保留原会话 `1`。第 98 阶段验收累计样本 `6`、完成 `4`、无候选跳过 `2`，Judge `4` 次、取消 `0`、异常 `0`，完成判定为直接回答 `2`、部分回答 `2`；累计耗时 `23100ms`、TTFB `23067ms`、Prompt `38915B`、Tokens `9970/975/10945`。notice 发布 `4`，删除测试会话后从有效 `4 / 裁剪 0` 变为有效 `1 / 裁剪 3`。

本轮不得增加生产持久化或执行权：继续固定 `store=null / persistenceMode=NONE`、Room v32、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`。两次自然无候选跳过与一次自然预算耗尽只证明入口隔离有效，不能表述为已经取得自然 Judge 失败分布，也不能据此开启 Room Store、跨进程 notice 或生产拒绝。验收必须保持完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、仅 Redmi 的文档语料 `OK (1 test)` 和默认完整 `OK (191 tests)`；不得连接或启动 Pixel_9。

## 第 97 阶段 answerability shadow 真实样本与遥测边界

只有用户显式开启后，前台直接 Agent 的真实 answerability shadow 才能进入样本分母；默认关闭期间、普通聊天、Workflow 和后台 Worker 均不得产生样本。答案必须先展示并成功保存，Judge 请求发出前必须再次检查开关；用户在此之前关闭即撤销本次授权，不得继续发送问题和知识候选。Judge 已发出后的取消可以记录取消终态，但不得伪造上游未返回的 token usage。

进程内 tracker 必须固定容量上限且重启清空，只允许记录样本终态、Judge attempt、延迟/TTFB、Prompt 字节、input/output/total Tokens、usage attempt、稳定失败枚举和 notice 发布/有效/裁剪数量。禁止记录问题、答案、候选正文、引用正文、原始响应、消息 ID、Base URL、API Key 或其他凭据。重试成功也必须保留前序失败分类；答案保存失败、Shadow Store 失败、身份不匹配、候选缺失、绑定未知、用户取消和意外异常必须分别统计。

第 97 阶段继续固定 `store=null / persistenceMode=NONE`，不得增加 Room 表、migration 或 notice 持久化；`enforcementApplied=false` 与 `productionEnforcementEnabled=false` 不得改变。验收必须覆盖设置页可滚动摘要、真实 Provider 成本、notice 发布与会话删除/重载裁剪、普通聊天隔离和关闭恢复。结果为完整 JVM `600/600`、Lint `0 issue`、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的定向 `OK (1 test)` 与默认完整 `OK (191 tests)`；真实样本 `1` 条完成、Judge `1` 次、失败/取消/异常为 `0`。不得连接或启动 Pixel_9。

## 第 96 阶段 answerability shadow 生产接线边界

生产 Judge 必须复用第 92 阶段冻结协议：Responses、非流式、关闭 reasoning summary、`temperature=0`、`topP=1`、`maxTokens=220` 和严格 JSON codec。adapter 每次 attempt 只允许一个 Provider 请求，不得自行重试；实际 Judge identity 必须从当前 Provider 配置的 `providerId / model / Base URL fingerprint` 派生，不能复制调用方期望值。请求包含用户问题和知识候选，因此必须逐请求关闭全部 HTTP Debug 日志；统一 Provider 的鉴权、User-Agent 和兼容 Header 行为保持不变。

生产 shadow 开关必须独立、默认关闭。只有前台直接 Agent、用户开关开启、实际 Provider identity 与冻结的 `redmi-provider-compatibility / gpt-5.5 / configuration fingerprint / prompt version` 完全匹配时才允许请求；身份漂移必须在网络前保持关闭。普通聊天、Workflow、后台 Worker、恢复链和 enforcement 不得继承该开关。

Agent 答案必须先展示并进入正常会话保存。旁路 publisher 只能等待该答案对应的保存 Job 成功后异步调用 Judge；保存失败、保存被新快照取消、候选缺失、Workflow 来源或 Judge 失败都只跳过 shadow，不得把已成功 Agent Run 改为失败、不得延迟答案显示。Judge 终败或未形成真实 measurement 时不得发布猜测 notice；只有完成观测后形成的绑定可以以进程内 `messageId` 映射投影到知识引用区域，且不得改写消息、可信上下文、MessagePart 或引用。

第 96 阶段固定 `store=null / persistenceMode=NONE`，不得增加 Room 表、Schema 版本或持久化 notice。验收必须覆盖默认关闭、身份完全匹配、固定请求配置、请求/响应/流事件 HTTP Debug 日志关闭、5xx/协议错误分类、保存成功后顺序、保存失败/取消跳过、Workflow 跳过、notice 不改写消息和设置偏好。完整 JVM `593/593`、Lint、Debug/AndroidTest APK、仅 Redmi `wsvwypiz7xwslvl7` 的默认完整 `OK (191 tests)`、真实生产 adapter `OK (1 test)` 和设置/偏好/notice `OK (3 tests)` 均通过；不得连接或启动 Pixel_9。

## 第 95 阶段 answerability shadow 真实测量协调边界

Judge 的共享决策字段必须抽象为 `KnowledgeAnswerabilityAssessment`。带人工真值的 calibration/validation 数据继续使用 `KnowledgeAnswerabilityObservation`，并且只有该离线类型可以携带 `KnowledgeRelevanceLabel`；真实 Agent Run 生成的线上结果必须使用不带 `label` 的 `KnowledgeAnswerabilityShadowMeasurement`，不得伪造人工真值或把线上测量混入离线质量统计。两条路径必须复用同一候选原文证据匹配和字段映射，避免决策语义漂移。

`KnowledgeAnswerabilityShadowObservationCoordinator.observe()` 是唯一协调入口。默认模式必须为关闭；只有前台直接 Agent 来源可以进入 shadow，普通聊天、非直接来源、Workflow 和后台 Worker 必须跳过。调用 Judge 前冻结候选与引用快照；候选 Run、问题、正文或引用不完整，以及缺少冻结绑定时不得发送网络请求，而是保守返回未知绑定。

Judge 请求最多执行两次。只有瞬时网络、限流、服务端和协议失败允许重试一次；认证、普通请求、Judge 身份、候选和未知异常不得重试。上层取消必须原样传播，不得生成 `UNKNOWN`、不得写 shadow 记录。Judge 响应身份与冻结身份漂移时，可以保留本次 measurement 用于审计，但绑定必须为 `UNKNOWN`，原答案、引用和 `enforcementApplied=false` 不得改变。

shadow 持久化必须可选。记录只允许保存来源 Run、已持久化消息 ID、候选 SHA-256 指纹、幂等键、Judge 身份、尝试次数、观测/绑定状态、决策、失败分类和时间；不得保存候选正文、模型原始响应、问题原文副本或引用正文。Store 普通失败只标记持久化失败，不得反向改变已经形成的绑定或用户答案；Store 取消仍必须传播。

验收门禁：新增协调器契约 `14/14`，完整 JVM `578/578`、Lint、Debug/AndroidTest APK 通过；真机只允许 Redmi `wsvwypiz7xwslvl7`，完整 `AndroidJUnitRunner` 为 `OK (188 tests)`，不得连接或启动 Pixel_9。第 95 阶段当时不提供生产 Judge Provider adapter、消息 caller 或答案引用 UI 接线；这些默认关闭的接线已由第 96 阶段完成，Room schema/store、普通聊天/Workflow/后台接入和 `productionEnforcement` 仍关闭。

## 第 94 阶段真实消息流只读 answerability shadow 绑定边界

消息流绑定只允许消费现有 Agent Run 中可信的 `knowledge.search` ToolResult。候选必须来自成功执行，验证状态不能是 `FAILED`，正文和稳定知识引用都不能为空；多步 Run 取最近一条满足条件的执行，旧消息没有执行列表时兼容顶层单工具字段。其他工具、失败执行、无引用或空正文不得进入 Judge 候选。

冻结绑定必须同时包含强类型 calibration/validation `KnowledgeAnswerabilityDatasetIdentity` 和已冻结 `KnowledgeAnswerabilityGate`；两套数据必须绑定同一 Judge identity、版本非空且互异。消息 shadow 只允许 `VERDICT_AND_EXACT_EVIDENCE` 与 `VERDICT_EVIDENCE_AND_CONFIDENCE`；第 92 阶段未通过的 `VERDICT_EVIDENCE_CONFIDENCE_AND_COVERAGE` 禁止进入。实际 Judge identity 不一致、缺少冻结绑定/measurement、来源 Run 为空、measurement 的 `sourceRunId` 与候选 Run 不一致或候选证据不完整，都必须返回 `UNKNOWN`，不能抛错或把未知转换为拒绝。

绑定结果必须在绑定开始时复制并按原顺序保留全部 `KnowledgeReference`，同时保留来源 Run、候选正文、已有 measurement 的时间和 shadow 提示；没有 measurement 时 `observedAt` 必须保持 `null`，不能伪造观测时间。结果固定 `enforcementApplied=false`。绑定只表达观察关系，不得删除、替换、重排答案或引用，不得写 Room、修改消息 schema、接入普通聊天/Workflow 或开启 `productionEnforcement`。第 94 阶段只冻结纯 Kotlin 绑定契约，第 95 阶段补齐 Judge 协调，第 96 阶段完成默认关闭的 Provider/caller/UI 接线；Room Store 与 enforcement 仍关闭。

验收门禁：绑定策略 `7/7`、候选提取 `4/4`；完整 JVM `564/564`、Lint、Debug/AndroidTest APK 通过；真机只使用 Redmi `wsvwypiz7xwslvl7`，完整 `AndroidJUnitRunner` 为 `OK (188 tests)`，不得连接或启动 Pixel_9。

## 第 93 阶段答案可回答性 shadow 呈现边界

答案可回答性 shadow 呈现只能消费现有 `KnowledgeAnswerabilityObservation` 和冻结 `KnowledgeAnswerabilityGate`，不得在展示层重新计算阈值、调用 Provider、读取 Room 或改变检索结果。输入引用必须按原顺序和身份完整保留，结果固定 `enforcementApplied=false`；`ACCEPT` 只能显示“直接回答”的观察提示，`REJECT` 必须按部分回答、未回答、矛盾、证据无法回查或低于冻结门禁区分，缺观测、缺门禁或 `UNKNOWN` 必须显示未知。

`KnowledgeReferencesContent` 的 answerability 提示入口必须默认 `null`，保证未接入的普通调用不变。有提示且零引用时应显示解释但不得显示“知识引用 · 0”；有引用时提示与原折叠引用必须同时存在，不能因 Judge 结果删除、替换或重排引用。第 93 阶段当时不得把该参数接到普通聊天、生产消息持久化、Room、`knowledge.search`、Workflow 或后台 Worker；第 96 阶段仅把它接到默认关闭的前台直接 Agent 旁路。

验收必须覆盖提示状态、引用不变和 `enforcementApplied=false`，并在 Redmi 执行 Compose UI 用例；第 93 阶段结果为既有策略 `7/7`、新增呈现 `5/5`、合计 `12/12`，`KnowledgeReferencesContentInstrumentedTest#answerabilityShadowNoticeCoexistsWithRetainedReference` 已随默认完整套件 `OK (188 tests)` 通过。第 93 阶段生产消息流尚未传入该提示；第 96 阶段仅接入前台直接 Agent，enforcement、答案改写和引用过滤继续禁止。Android 真机验证只允许 `wsvwypiz7xwslvl7`，不得连接或启动 Pixel_9。

## 第 92 阶段答案可回答性策略边界

答案可回答性 Judge 只能返回单个严格 JSON 对象，字段集合固定为 `verdict`、`confidence`、`evidence_quotes`、`contradiction_detected`、`reason_code`；verdict 只能是 `ANSWERED`、`PARTIALLY_ANSWERED`、`NOT_ANSWERED` 或 `UNKNOWN`。`ANSWERED` 必须携带候选正文中可匹配的原文 quote；`NOT_ANSWERED` 与 `UNKNOWN` 不得携带 quote；字段异常、解析错误、矛盾或部分回答必须 fail-closed，`UNKNOWN` 不得计作负例拒绝。

策略必须在候选正文上重新匹配并合并 quote 区间，记录 quote 数、匹配数和覆盖率；不能把模型自行生成的“证据”直接展示或用于接受决策。三类预注册特征族分别使用固定 verdict/原文证据、置信度和证据覆盖率；calibration 只能冻结阈值，validation 只能应用冻结阈值。两侧必须绑定同一 Provider/Judge identity、prompt version 和配置指纹，dataset version 必须互异，三桶标签完整且 case ID 不得漂移。

第 92 阶段只允许独立测试和显式 Redmi 探针采集 shadow 证据，不得读取生产 Room、修改召回、改变答案引用 UI、升级相关性身份或开启 `productionEnforcement`。预注册真实探针为两套各 6 个用例、每例重复 2 次（`12 + 12`）；缺参数时跳过，网络/解析最终失败进入 `UNKNOWN` 并计入失败数。Redmi 真实执行已取得 calibration/validation 各 `12` 条、网络与解析失败均为 `0`；两类 verdict/原文证据特征族通过，覆盖率特征族未通过，`productionEnforcementEnabled=false`。设备只允许 `wsvwypiz7xwslvl7`，不得连接或启动 Pixel_9。

## 第 91 阶段跨主题平移不变特征验证边界

新的相关性实验只能使用生产检索已经具备的审计事实，不得为了实验直接修改 Room v32 或线上召回。预注册特征固定为 `top1 - 候选均值`、`margin / 候选标准差` 及两者组合；非有限 top1/均值/margin、负 margin 或不高于 `1e-12` 的候选标准差必须 fail-closed。calibration/validation 必须绑定同一生产 Provider、模型、配置指纹并使用不同数据集版本；阈值只能从 calibration 真实观测点选择，validation 不得重新选参。

`stage91-cross-topic-calibration-v1 / validation-v1` 必须使用与第 90 阶段不同的全新主题语料，每套 12 篇文档、正例/近负例/远负例各 4 条英文查询并重复 2 次。近负例必须是同主题但语料未覆盖的具体事实，不能被 companion 文档直接回答；Recall@5 仍需 `>=0.80`。相关性标准保持正例接纳 `>=0.90`、近负例拒绝 `>=0.80`、远负例拒绝 `>=0.90`、决策稳定 `1.0`，不得因为新特征接近通过而降低标准。

Redmi 三轮有效结果均为 `24 + 24` 条观测、Recall@5 `1.0 / 1.0`、通过族 `0`。`top1-均值` 与组合的 validation 为正例接纳 `1.0`、近负例拒绝 `0.75`、远负例拒绝 `1.0`、稳定率 `1.0`；`margin/标准差` 为 `0.75 / 0.25 / 1.0 / 1.0`。该结论必须记录为预期门禁否决，不能进入 final holdout、升级 `VERIFIED` 或开启 `productionEnforcement`。后续若继续，必须先建立 answerability/重排证据，而不是使用 validation 回调本轮阈值。完整本地门禁为 JVM `541/541`、Lint、Debug/AndroidTest APK；Redmi 默认全量 JUnit XML 已完成，为 `186` 条（`176 passed / 10 skipped / 0 failed`），且不得连接或操作 Pixel_9。

## 第 90 阶段正式相关性 calibration/validation 预注册门禁边界

正式相关性证据必须绑定同一生产 Provider ID、模型和配置指纹，并使用互异的 calibration/validation `datasetVersion`。七类特征族（raw top1、margin、top1 z-score 及四种组合）只能从 calibration 选择阈值，validation 必须原样评估；不得使用 validation 回调阈值、降低预注册标准或复用旧 holdout。

第 90 阶段 Redmi 真实验收使用 `redmi-production-embedding-v1 / Qwen/Qwen3-Embedding-0.6B`，配置指纹 `2f22bfe3b9db92555f493c173116c58970490ece7fa90b8c7bf156aa7456dbf6`，两套数据各 24 条观测、Recall@5 均为 `1.0`。预注册相关性标准未有特征族通过；最新 raw top1 validation 正例接纳率为 `0.75`，重复取证范围为 `0.625–0.75`，近/远负例拒绝均为 `1.0`。因此“质量门禁否决”必须作为显式、可审计且测试成功的结果，不能把 JUnit 失败断言伪装成通过，也不能升级 `VERIFIED`、进入 final holdout 或开启 `productionEnforcement`。

在新跨主题归一化特征或新标注数据重新注册并达到标准前，生产 Store、`knowledge.search`、普通聊天、答案级引用和 Workflow 不读取该控制面。门禁记录为 JVM `535/535`、仅 Redmi 默认 instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`）；不得连接或操作 Pixel_9。

## 第 89 阶段生产身份绑定与灰度控制面边界

相关性生产身份必须区分 `UNBOUND / CANDIDATE / VERIFIED / REVOKED`。真实 Provider 探针至少校验非空 Provider ID、模型与配置指纹，模型列表包含目标 Embedding 模型，并成功返回数量、维度和有限值均有效的向量；该探针只允许建立 `CANDIDATE`，不得因 `/models` 或 `/embeddings` 成功直接授予生产 enforcement。

配置身份只能保存 Provider ID、模型和规范化 Base URL 的 SHA-256 指纹，不得把原始 Base URL 或 API Key 写入偏好、Room、源码或长期文档。候选升级为 `VERIFIED` 时，身份必须与冻结 gate 的 calibration/validation、全新 holdout 和证据中的 Provider/模型一致；gate 版本、证据版本和配置指纹必须匹配，final holdout 必须明确通过，holdout 数据集不得复用 calibration 或 validation。任一缺失、失败或漂移都必须拒绝升级。

灰度偏好必须继续默认关闭，并新增身份证据版本和配置指纹。控制面只有在偏好本身通过 gate/Provider/模型校验、生产身份为 `VERIFIED`、身份 gate 与当前 gate 一致、证据版本和配置指纹相同时，才允许解析为 `ENFORCE`；候选、撤销、过期 gate、身份漂移或偏好不完整全部回到 `SHADOW`。撤销执行资格与身份审计必须分开：前者清除未来执行键，后者保留身份和证据指针但标记 `REVOKED`。

设置页必须提供独立「相关性灰度控制面」入口，展示身份、Provider、模型、配置指纹、gate、证据和 holdout，并明确当前生产答案路径尚未接入。页面不得提供直接绑定、升级或绕过证据开启 enforcement 的入口。第 90 阶段已完成同一正式 Provider 身份下的新 calibration/validation，但七类特征族均未达到预注册标准；因此 ViewModel、`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 和后台 Worker 继续不读取控制面。门禁为完整 JVM `535/535`、Lint、Debug/AndroidTest APK、仅 Redmi 默认 instrumentation `185` 条（`176 passed / 9 skipped / 0 failed`），以及显式真实校准 `1/1`；不得连接或操作 Pixel_9。

## 第 88 阶段相关性降级、引用一致性与身份灰度边界

本阶段必须在不接入生产拒绝的前提下冻结用户体验与灰度契约。每个 `KnowledgeSearchHit` 必须携带同一次融合输入中的 `LEXICAL / SEMANTIC` 来源；该元数据不得改变现有 RRF、FTS4+LIKE、top-K、enabled/revision 复核或 Room v32。未来低分执行只能移除 semantic-only：词法-only 和词法/语义重叠候选必须保留一次，最终引用集合必须由最终候选直接生成，不能继续暴露已移除 chunk。

用户提示标题固定为“已降级为关键词匹配”“未找到足够可靠的本地知识”“相关性检查暂未应用”。没有词法兜底时允许零引用但必须保留解释；既有调用未传提示时 UI 行为不变。候选来源缺失、决策与来源矛盾，以及 shadow/关闭状态中出现任何删除 disposition 时必须 fail-open，保留当前 hits 与引用。默认关闭的正常 shadow 判断不得向用户显示未实际应用的警告。

灰度资格必须同时绑定 gate 版本、Provider 和模型。偏好缺项、gate 版本过期或身份漂移时自动解析为 `SHADOW`；撤销必须清除 enforcement、gate、Provider、模型四个键。冻结 gate 的 calibration/validation 身份必须完整、Provider/模型一致且 datasetVersion 不同，阈值必须有限；结构非法时直接拒绝，不能降级成可执行资格。Stage 85/86 的实验 Provider ID 不能直接作为正式生产身份，接入前必须以真实 Provider/模型重新绑定并复验。

本阶段不得让 ViewModel、`RoomKnowledgeDocumentStore.search()`、`knowledge.search`、普通聊天、Workflow 或后台 Worker 读取灰度偏好，也不得修改生产排序、拒绝、Room Schema 或历史记录。验收门禁为 Stage 87+88 聚焦 JVM `16/16`、完整 JVM `522/522`、Lint、Debug/AndroidTest APK，以及只在 Redmi `wsvwypiz7xwslvl7` 执行的默认 instrumentation `180` 条（`173 passed / 7 skipped / 0 failed`）。首次长套件若因设备 dream/keyguard 导致 Compose hierarchy 缺失，只能作为设备状态失败记录；唤醒后的失败批次与完整套件必须重新全绿，不能把失败静默忽略。

## 第 87 阶段生产相关性拒绝设计边界

本阶段只建立可审计的候选决策契约，不把 final holdout 结果直接变成线上拒绝。策略必须绑定 Stage 86 冻结的 gate、calibration/validation Provider/模型身份和 raw top1 下限；默认开关关闭时只能输出 shadow 结论，不得改变现有语义、FTS4 或 LIKE 结果。开关未来开启时，低于下限只能移除语义候选；若同一查询有词法命中，必须保留词法结果，不能把“语义低分”显示成“知识为空”。

Provider/模型漂移、`LEXICAL_ONLY`、`NO_INDEX`、`PROVIDER_UNAVAILABLE`、`DIMENSION_MISMATCH`、缺失或非有限 top1 必须 fail-open，继续保留当前结果；未知事实不得按低分拒绝。策略不新增 Room 列、不改变历史审计、不进入 Workflow/后台路径，也不在本阶段修改 UI 文案。聚焦 JVM `5/5`、完整 JVM `511/511`、Lint、APK 和仅 Redmi 默认 instrumentation `178` 条（`171 passed / 7 skipped / 0 failed`）作为本阶段门禁。下一阶段先验收用户可见回退、灰度与撤销，再评估是否接入生产检索。

## 第 86 阶段预注册验证边界

本阶段只验证 Stage 85 已冻结的 raw top1，不得重新比较特征族、搜索阈值、修改测试语料或启用生产拒绝。冻结门禁版本为 `stage85-raw-top1-qwen-v1`，raw top1 下限为 `0.6416276358587735`；身份必须保留 Stage 85 calibration 的 Provider、模型与 `stage85-calibration-v1`，并记录已见 `stage85-validation-v1`。final holdout 必须使用同一 Provider/模型且 datasetVersion 同时不同于前两套数据；任何身份漂移、空值、非有限值、缺桶或 case 标签漂移都必须 fail-closed。

`stage86-final-holdout-v1` 在首次真实运行前固定为 20 篇全新成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次；不得复用 Stage 82 calibration、Stage 83 holdout 或 Stage 85 calibration/validation 的主题与用例。近负例只询问同主题但两篇文档均未覆盖的具体事实。预注册相关性标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`；排序标准为 Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定率 `1.0`。

Redmi `wsvwypiz7xwslvl7` 已在预注册 commit 后执行有效采集：首次有效运行耗时 `63.077s`；补齐 validation Provider/模型身份校验并同步重建 Debug/Test APK 后，最终复验耗时 `67.018s`。最终 60 条观测的正例接纳 `0.90`、近/远负例拒绝 `1.0`、决策稳定 `1.0`、balanced accuracy `0.9667`，Recall@1/5、MRR 和排序稳定均为 `1.0`，满足全部标准。中间 ABI 不一致和一次检索空分数回归不得计入门禁，也不得用于调参。该结果只允许进入“生产拒绝设计评审”，不能直接修改 Room v32、cosine+RRF、FTS4+LIKE 或 UI；后续若出现失败证据，必须保留冻结阈值和失败事实，不得使用 final holdout 回调或降低标准。显式 Provider 参数缺失时继续 skipped，日志和文档不得包含 Base URL 或 API Key。

## 第 85 阶段验证边界

本阶段只能比较特征族，不得修改生产 `RoomKnowledgeDocumentStore`、Room Schema、UI 或相关性拒绝行为。预注册特征族固定为 raw top1、margin、top1 z-score、raw+margin、raw+z、margin+z、raw+margin+z 共 7 种；每种门禁只能从 calibration 观测值的笛卡尔积选择阈值，validation 只能应用冻结阈值，不能重新选参。数据集身份必须包含 Provider、模型和版本；calibration/validation 的 Provider、模型必须一致，版本必须不同。

`stage85-calibration-v1` 与 `stage85-validation-v1` 必须使用全新且互相隔离的内存 Room，每套包含 20 篇成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次。近负例是同主题但语料未覆盖的具体事实，不能由 companion 文档直接回答；Stage 83 holdout、Stage 82 calibration 及其阈值不得参与本轮搜索。预注册 validation 标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`，同时要求两套语料 Recall@5 `>=0.80`。

有效 Redmi 结果中 raw top1 与 raw+margin 都满足预注册标准且结果完全相同；为减少过拟合维度，下一阶段只把更简单的 raw top1 `0.6416276358587735` 作为待冻结候选。该候选必须在第三套全新 final holdout 上原样验证，不能用 validation 回调；本阶段不得发布生产阈值。完整默认门禁为 JVM `502/502`、Lint、Debug/AndroidTest APK 和仅 Redmi instrumentation `177/177`；6 个真实 Provider 用例缺参按设计 skipped。

## 第 84 阶段验证边界

第 83 阶段已证明原始 cosine 绝对阈值会跨主题漂移，本阶段只能新增相对分布 shadow 观测，不能放宽旧门禁或启用生产拒绝。每次真实语义检索必须基于现有 2000 行上限内、截断 top-K 之前的全部有效候选计算均值、总体标准差和 top1 z-score；z-score 在候选分数整体平移时应保持不变。单候选或零方差没有可证明的相对区分度，z-score 必须为 `null`，不能补零。

Room v31→v32 只为检索审计增加可空的 `embeddingScoreMean`、`embeddingScoreStandardDeviation` 和 `embeddingTopScoreZScore`。v31 历史记录缺少完整候选分布，三个字段必须保持 `null`；不得从 top1、top2、margin 或当前向量索引回填。Provider 未执行、Provider 不可用、无索引或维度不匹配时相对字段同样保持未知。管理 UI 只能在“校准观测”中展示这些值，不使用通过、拒绝或置信度结论文案。

纯 Kotlin 策略必须覆盖总体分布计算、整体平移不变性、单候选/零方差和空/非有限输入。Redmi 必须验证 v31→v32 迁移、生产 Room 写入回读、UI 展示和真实 Provider 语义链。已退休 `stage83-holdout-v1` 只允许用于确认观测链，不允许成为新校准集或阈值搜索来源；其正例与近负例 z-score 区间存在重叠，进一步证明单一 z-score 不能直接上线。完整默认门禁为 JVM `499/499`、Lint、Debug/AndroidTest APK 和仅 Redmi instrumentation `176/176`；5 个真实 Provider 用例缺参按设计 skipped。

## 第 83 阶段验证边界

第 82 阶段候选必须冻结为版本化门禁后再接触独立 holdout。冻结身份至少包含 Provider、模型、门禁版本和校准数据集版本；holdout 必须使用不同数据集版本，Provider 或模型漂移时不得复用旧阈值。门禁阈值、比例标准和输入分数必须为有限值，三个标签桶缺一不可，同一 case ID 不得标签漂移。评估只能应用冻结的 `minimumTopScore + minimumScoreMargin`，不得搜索 holdout 自身最佳阈值或用失败样本回调当前门禁。

本阶段冻结模型 `Qwen/Qwen3-Embedding-0.6B`、门禁 `stage82-qwen-v1`、校准集 `stage82-calibration-v1`，top1 下限 `0.6735426515268672`、margin 下限 `0.0178535973263384`；独立 `stage83-holdout-v1` 使用全新 20 篇成对主题中文短文，正例、近负例、远负例各 10 条英文查询，每条重复 2 次。预注册标准为正例接纳率 `>=0.90`、近负例拒绝率 `>=0.80`、远负例拒绝率 `>=0.90`、决策稳定率 `1.0`、Recall@1 `>=0.90`、Recall@5 `1.0`、MRR `>=0.90`、排序稳定率 `1.0`。

Redmi 三个独立进程均观测到正例接纳率 `0.80`、两类负例拒绝率 `1.0`、决策稳定率 `1.0`、Recall@1/5 `1.0`、MRR `1.0`、排序稳定率 `1.0`。手冲咖啡和缝纫机张力正例的 top1 稳定低于冻结下限，因此候选相关性门禁失败，即使所有正例仍排在首位也不得降低预注册标准后宣称通过。第 82 阶段候选不具备进入生产拒绝设计的资格；生产 Room v31、cosine+RRF、FTS4+LIKE 词法兜底和审计语义保持不变。下一阶段若继续校准，必须建立新版本并重新分离 calibration/validation/holdout，当前 holdout 只能作为既有门禁的最终否决证据。

默认离线回归仍需独立全绿：JVM `495/495`、Lint、Debug/AndroidTest APK 和仅 Redmi 完整 instrumentation `175/175`；5 个真实 Provider 用例缺少显式参数时按设计 skipped。显式联网 holdout 三轮按预注册断言失败，不得与默认套件通过混写成同一结论。

## 第 82 阶段验证边界

相关性校准必须继续与生产检索解耦。纯 Kotlin 策略接收带稳定 case ID、正例/近负例/远负例标签、top1 与 margin 的观测；三个桶缺一不可，分数必须为有限值，同一 case ID 不得跨标签。每桶必须同时报告样本数、唯一用例数，以及 top1、margin 的 nearest-rank P05/P50/P95。候选门禁只允许从已观测的 `minimumTopScore + minimumScoreMargin` 组合中选择，以正例接纳率、近负例拒绝率、远负例拒绝率三桶等权计算 balanced accuracy，并使用固定 tie-break 保证报告可重复。该候选是同集 shadow 诊断，不得写入生产配置、Room Schema 或检索拒绝状态。

真实校准使用隔离的内存 Room、同一明确 Provider/模型与 20 篇成对主题中文短文；正例、近负例、远负例各 10 条英文查询，每条在同一进程重复 2 次，并从外部启动 3 个独立 Redmi instrumentation 进程。每次观测必须记录短日志，汇总记录 Recall@1、Recall@5、MRR、排序稳定率、三桶分位数、shadow 候选门禁、查询耗时、候选数、向量行数/维度/字节、PSS 与 Java heap。缺少显式 Provider 参数继续 skipped，日志和文档不得包含 Base URL 或 API Key。三轮真实结果虽然全部达到 Recall@1/5、MRR、稳定率和同集 balanced accuracy `1.0`，但阈值选择与评估来自同一数据集；第 83 阶段必须先冻结候选，再使用不参与调参的独立 holdout 验证。完成 holdout 前，Room v31、cosine+RRF、词法兜底与“无生产拒绝”边界保持不变。最终门禁为 JVM `491/491`、Lint、Debug/AndroidTest APK 和仅 Redmi 完整 instrumentation `174/174` 通过；4 个真实 Provider 用例缺少显式参数时按设计 skipped。

## 第 81 阶段验证边界

每次使用 Embedding 的知识检索都必须在既有 `providerId + model` 身份下记录 shadow 校准数据：有效候选数、top1、top2，以及仅在至少两个有效候选存在时计算的 `top1 - top2` margin。未执行 Provider、Provider 不可用或历史记录的字段保持 `null`；索引为空或已有索引全部无法比较时候选数为 `0`，不得用零分补造未知观测。Room 从 v30 升到 v31，迁移必须保留历史审计并让四个新字段全部为 `null`。知识管理页只能把这些值标为“校准观测”，不得显示为通过、拒绝或相关性结论。

真实校准必须继续使用隔离的内存 Room 和固定 10 篇中文语料，按同一 Provider/模型至少覆盖正例、近负例、远负例三类；每类记录查询、词法命中数、top-K 文档、候选数、top1、top2 与 margin。用例只校验身份、有限值、排序关系和审计完整性，不把单次样本中的经验分数写成生产阈值。首轮 Redmi 每类 2 条的分布仅作为下一阶段扩充数据的基线；在更多标注样本和 Provider/模型分桶验证完成前，生产 cosine+RRF 行为保持不变，不新增 `RELEVANCE_INSUFFICIENT` 或其他拒绝状态。最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 和 Redmi 完整 instrumentation `174/174` 通过；4 个真实 Provider 用例缺少显式参数时按设计跳过。

## 第 80 阶段验证边界

真实 Embedding 规模基线必须使用固定、内置且有界的语料，不依赖手机正式知识库或外部文件。至少导入 10 篇语义不同文档，使用 5 个与目标正文无词法交集的跨语言正例，每例重复检索两次。纯词法 Store 必须零命中，真实语义 Store 必须记录 `USED` 及正确 Provider/模型；Recall@5 不低于 `0.8`、MRR 不低于 `0.7`、重复排序稳定率不低于 `0.8`。向量行数必须等于 chunk 数，维度必须一致，原始 BLOB 字节数必须等于 `rows * dimensions * 4`。索引和查询使用单调时钟记录耗时，同时记录 SQLite 页、PSS 和 Java heap；网络耗时与 PSS 只作观测证据，不作易波动的硬门禁。无关查询必须单独记录返回数；在当前没有相似度阈值时，不得伪造负例准确率。真实验收必须在 Redmi 上以三次独立 instrumentation 进程启动重复，使每轮 PSS 都有独立进程基线；单次测试内部不循环伪造三个独立样本。缺少显式 Provider 参数时默认跳过，配置和密钥不得写入 Git 或报告。本阶段最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `171` 个用例，`168` passed、`3` skipped、`0` failed。

## 第 79 阶段验证边界

真实 Embedding Provider 验收不得只停留在 `/embeddings` 协议或假向量 Store。显式联网测试必须直接组合生产 `OpenAiKnowledgeEmbeddingProvider` 和 `RoomKnowledgeDocumentStore`，先确认模型列表包含指定模型，再在隔离的内存 Room 中导入至少两个语义不同文档。验收查询必须与目标文档没有词法命中，并证明纯词法 Store 返回空、真实语义 Store 首位命中目标文档；检索审计必须为 `USED`，Provider/模型身份和最终 chunk IDs 必须一致。索引摘要必须记录非零维度与分块数，显式重建不得改变 document revision。测试缺少显式 Base URL、API Key 或模型参数时必须跳过，完整测试套件不得依赖公网；真实配置与密钥不得写入源码、测试报告或 Git。本阶段最终门禁为 JVM `488/488`、Lint、Debug/AndroidTest APK 通过；Redmi JUnit XML 共 `170` 个用例，`168` passed、`2` skipped、`0` failed。

## 第 78 阶段验证边界

Embedding 检索质量必须使用固定语料和稳定指标验收：排名先按文档 ID 去重再截取 K，正例计算 Recall@K 与 MRR，负例只在返回为空时计为正确，多次执行按完整排名一致性计算稳定率；空语料、单一正/负语料、K 外命中、负例误命中、排序漂移和非法用例都要有 JVM 契约。项目长期 `docs/` 作为固定语料，5 个正例与 1 个负例各执行两次，门禁为 Recall@5 `1.0`、MRR `>= 0.8`、负例准确率 `1.0`、稳定率 `1.0`。知识管理页必须显示本次检索实际使用的 Embedding 状态，Provider/模型为空时不得渲染多余分隔符，零命中仍保留审计与回退原因。真实 Provider 只在同步模型列表明确包含 Embedding 模型时调用 `/embeddings`；本轮 Redmi 兜底 Provider 同步成功但没有该模型，因此仅验证配置恢复与词法兜底，不宣称真实向量端点兼容。完整 JVM `488/488`、Lint 和 APK 已通过；Redmi 完整 instrumentation 共 `169` 个用例，`168` passed、`1` 个无显式参数的联网冒烟按设计 skipped、`0` failed。

## 第 77 阶段验证边界

知识管理页必须为当前文档展示所有仍指向当前 revision 的 Embedding 索引摘要，包括 Provider、模型、维度和分块数；没有索引时明确提示词法兜底。启用文档可手动重建当前选中 Provider/模型的索引，升级前旧文档不得因缺少导入时向量而永久停留在 `NO_INDEX`。Provider 请求、数量和维度校验全部成功后才允许进入事务；事务内必须再次核对 document revision、enabled 和 chunk 身份，并且只替换当前 `providerId + model` 空间。切换 Provider/模型后既有空间不得被覆盖，重复重建某一空间不得删除其他空间；失败、超时、停用和并发替换不得先删除已有向量。替换正文继续清理全部旧 revision 空间，删除继续清理全部索引。Room 保持 v30；当前只支持前台单文档显式重建，不承诺 ANN、自动后台批量重建或规模化性能。完整 JVM `483/483`、Lint、Debug/AndroidTest APK 和仅 Redmi `164/164` instrumentation 已通过。

## 第 76 阶段验证边界

知识库语义检索只在当前 Provider 的模型列表包含 Embedding 模型时启用。请求配置必须保留 Provider 身份和 Embedding 模型名；向量按 `providerId + model` 隔离，不能把不同 Provider 或模型的 BLOB 混用。Room v30 新增向量表并保留历史检索的 `LEXICAL_ONLY` 默认事实。索引建立最长 30 秒，查询向量最长 2 秒；Provider 不可用、超时、无索引或维度不一致时必须回退原 FTS4+LIKE，且每次检索审计记录最终状态。语义候选与 FTS/LIKE 以稳定 RRF 融合，结果交付前必须再次核对当前文档启用状态和 revision。当前只验证有限规模内存扫描，不承诺 ANN、后台增量建索引或任意 Provider 的 Embedding 兼容性；完整 JVM、Lint、Debug/AndroidTest APK 和仅 Redmi `158/158` instrumentation 已通过。

## 第 75 阶段验证边界

`/agent` 附件 v1 仅支持 Responses API。单条 USER 消息最多携带一种经既有 8 MB、MIME、签名、PDF/OpenXML 和 UTF-8 策略校验的 Image 或 Document；Chat Completions、Image+Document 混合、持久化重复附件、assistant/Tool 伪造附件必须 fail-closed。初次发送必须先把附件与正文作为 USER MessagePart 提交，再建立 Run；同一 Run 的每轮规划请求继续携带附件，模型总结请求、`VerifiedAgentContext`、ToolResult 和 Agent 输出不得携带附件。审批恢复和任务中心重试必须从已持久化 USER MessagePart 重建附件，重试创建新 Run 且旧 Run 不变；Workflow/后台 Agent 暂无附件入口。完整 JVM `477/477`、Lint、Debug/AndroidTest APK 和仅 Redmi `153/153` instrumentation 已通过；图片/文档真实 E2E 的 `notes.create` 回执均为 `PASSED`，直接 `complete` 的无工具 Run 被运行时拒绝。

## 第 73 阶段验证边界

会话新建、历史选择和删除后的副作用顺序必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSelectionCoordinator`。协调器只组合现有 `ConversationSessionPolicy`、`ConversationPersistenceCoordinator` 与 `ConversationLoadCoordinator`：新建先取消旧加载再发布即时选择；删除先取消旧加载、标记版本化删除意图并发布运行态清理事件，再按计划即时选择或完整加载；只有当前加载代次失败时，必须先回滚该请求捕获的删除代次，再发布 Failed。旧失败不得清除同 ID 的新删除意图，删最后会话仍保留空占位，删除后有剩余会话仍加载 `updatedAt` 最新项。`ConversationLoadRequest` 不得继续承载持久化回滚意图；ViewModel 只负责 Agent Run/审批 Map、UI 投影和成功后的选择保存。Room v29、附件 BLOB、Provider 协议、UI、`/agent` 与 Workflow 不变。当前已验证聚焦 `4/4`、第 68 至 73 阶段会话组合 `30/30` 和完整 ViewModel 手工编译；后续标准门禁已补齐，完整 JVM `472/472`、Lint、Debug/AndroidTest APK 与仅 Redmi 执行的 instrumentation `153/153` 均通过。

## 第 72 阶段验证边界

新建会话与删除后的选择规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`。当前选中会话已经为空时必须幂等复用且不额外折叠其他占位；当前会话有内容时优先复用 `updatedAt` 最新空占位并折叠其余空占位，没有空占位才按同一注入时钟创建 `conversation-$now`。删除后有剩余会话必须返回 `Load` 计划并选择 `updatedAt` 最新项，删至空列表必须返回带新占位的 `Immediate` 计划。计划必须显式区分复用既有会话与新建占位，后者不得因时间戳 ID 碰撞恢复旧 Agent Run 或审批。取消加载、标记删除意图、清理运行态 Map、完整消息加载、失败回滚和选择保存继续留在 ViewModel；加载与持久化 coordinator 不重复实现选择规则。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `5/5`、完整 JVM `468/468`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 71 阶段验证边界

会话加载事件的纯 UI 投影必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadProjectionPolicy`。Loading 只开启消息加载并清除旧提示；Loaded 必须把请求会话、标题、摘要、完整当前消息、Agent Run、待审批状态和结果在同一次状态替换中切换，同时从所有非当前会话索引移除 Image/Document BLOB；Failed 只关闭加载并保留真实错误消息或稳定兜底。删除意图回滚必须继续发生在失败投影之前，成功后的选择保存和 Agent/审批映射读取继续留在 ViewModel，`ConversationLoadCoordinator` 的 Job/代次门禁不重复实现。Room v29、附件 BLOB 生命周期、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `463/463`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 70 阶段验证边界

异步会话加载 Job、取消与迟到结果隔离必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationLoadCoordinator`。每次选择生成单调代次并取消旧加载，且必须先登记新 Job 再派发可重入的 Loading；底层 Room 查询或 loader 即使在取消后仍返回或失败，也只能由当前代次发出 Loaded/Failed，不能覆盖当前会话、删除回滚或错误提示。当前代次成功后 ViewModel 仍负责把完整消息和轻量会话列表原子投影到 Compose，并继续触发选择保存；当前代次失败时才允许回滚该次删除意图并显示读取失败。删除后的下一会话选择、空会话兜底、Compose 副作用、`ConversationRepository` 的显式删除事务与附件 BLOB 生命周期不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `4/4`、完整 JVM `460/460`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 69 阶段验证边界

会话保存 Job、Room 写入串行化和显式删除意图必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationPersistenceCoordinator`。快速连续保存只保留最新待提交快照；旧事务若已进入不可取消提交区，最新快照必须等待并最后写入。普通聊天发送必须先取消并等待旧保存，再捕获当前删除意图并通过同一单写者提交用户消息与附件，成功后才能准备上下文和请求模型。删除 ID 只有在包含该代意图的事务成功后才能确认；失败、取消或事务期间同 ID 被重新标记时必须保留新意图，读取失败只回滚该次切换捕获的删除代次，不能清除同 ID 的后续删除意图。`ConversationRepository` 的显式删除事务、后台 Workflow 并发保护和附件 BLOB 保留逻辑不变；异步会话加载、删除后的 UI 切换/失败提示和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `8/8`、完整 JVM `456/456`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 68 阶段验证边界

会话状态投影的纯规则必须从 `XiaoLingViewModel` 迁入可独立测试的 `ConversationSessionPolicy`：第一条 `role=user` 消息正文生成标题并在 trim 后限制 18 字符，正文空白时保持“新会话”且不向后寻找下一条用户消息；全部真实会话必须保留，多个空占位只保留 preferred 或最新一个；更新既有会话必须保留 `createdAt`、推进 `updatedAt`，默认继承摘要边界、更新时间与摘要模型；非当前会话的迟到更新只能修改会话列表，不能污染当前 UI；blank ID 使用同一次注入时钟生成稳定 ID，并沿用既有非当前隔离语义。异步 Room 加载、保存 Job、删除事务和 Compose 副作用不在本阶段迁移。Room v29、Provider 协议、UI、`/agent` 与 Workflow 不变。聚焦 JVM `6/6`、完整 JVM `448/448`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 67 阶段验证边界

普通聊天的发送前持久化、请求上下文准备、模型网络调用、流式增量和成功/取消/失败终态必须由独立 `ConversationSendCoordinator` 按稳定顺序编排。ViewModel 只负责入口校验、Compose 状态投影、30ms 流式节流和发送 Job 生命周期，不得复制第二套网络 try/catch 状态机。用户停止时必须先用最近已准备上下文收敛部分 assistant，再继续传播 `CancellationException` 以取消底层请求；Room 或网络普通异常必须发出带最近可证明上下文的失败事件，持久化失败时不得继续准备上下文或调用模型。Room v29、Provider 协议、消息格式、UI、`/agent` 与 Workflow 不变。聚焦 JVM `3/3`、完整 JVM `442/442`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 66 阶段验证边界

普通聊天的请求上下文准备必须从 `XiaoLingViewModel` 迁入可独立测试的应用组件。该组件统一决定失败/取消 assistant 是否进入上下文、知识引用失效时是否移除历史 Agent 消息并废弃旧摘要、最近 16 条窗口、窗口外可信 Agent 结果上限、摘要增量边界与复用元数据，以及 Responses 用户附件是否进入最近窗口。ViewModel 只提供当前提示词设置、Room 知识引用核验和摘要网络实现；不能复制第二套上下文规则。知识核验或摘要调用的协程取消必须传播，普通异常才允许保守移除知识或使用本地摘要兜底。Room Schema、请求协议、摘要长度和 UI 不变。聚焦 JVM `8/8`、完整 JVM `439/439`、Redmi instrumentation `152/152`、Lint 与构建通过。

## 第 65 阶段验证边界

用户必须能在应用内只读查看最近 30 条进程退出观察，不依赖 ADB。页面刷新只能查询 Room v29 已有记录，不能再次调用平台采集，也不能给记录增加 Agent Run、Workflow 或 ScheduledTask 关联。六类稳定证据必须明确区分，尤其不能把 `LOW_MEMORY_CANDIDATE`、`CONTROLLED_OR_MAINTENANCE` 或 `UNATTRIBUTED` 呈现为自然 LMK。Redmi 聚焦 UI `3/3`、完整 instrumentation `152/152`、JVM `431/431` 通过；真实受控 `force-stop` 在页面显示为 `USER_REQUESTED / 受控退出或包维护`，刷新前后数据库均为 1 条。该只读控制面不改变普通 WorkManager、fail-closed 恢复、设备工具前台限制与 Foreground Service 后置策略。

## 第 64 阶段验证边界

Android 11+ 的系统进程退出事实已进入独立、有限、隐私安全的 Room v29 账本：前台启动与后台 Worker 冷启动均可补采，Worker 必须先登记当前进程所有权。退出记录不得凭时间邻近关联 Task/Run，不保存 description、trace 或进程状态摘要，稳定去重后最多保留 30 条。只有 `REASON_LOW_MEMORY` 是直接 LMK 证据；设备无法直接报告 LMK 时的 `REASON_SIGNALED + SIGKILL` 只能标记为候选，用户/应用取消和包维护必须保持受控分类。旁路采集失败不能阻断主流程，但不得吞掉协程取消。Redmi 聚焦 `5/5`、完整 instrumentation `149/149`、JVM `431/431` 通过；受控 `force-stop` 的正式记录为 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 63 阶段验证边界

Redmi Android 14 已用真实 WorkManager 验证应用取消运行中 Worker 会报告 `CANCELLED_BY_APP(1)`，并由生产停止原因策略映射为稳定分类。用户可见停止仍以先落库的 `STOP_REQUESTED` 和用户原因作为业务事实；随后 WorkManager 的应用取消码只说明执行机制，不得覆盖用户意图或写入独立系统停止原因。完整 Redmi instrumentation `145/145`、JVM `424/424` 通过。本阶段不属于自然 LMK、系统配额或超时证据，不改变普通 WorkManager、fail-closed 恢复与 Foreground Service 后置策略。

## 第 62 阶段验证边界

后台 Worker 的系统停止原因已纳入可审计执行结果：Android 12+ 的 WorkManager 停止码映射为稳定分类，按同一 Room 事务写入 ScheduledTask 与 WorkflowRun，并在任务中心展示；旧 Android、`NOT_STOPPED` 和未知码保持通用或未知结论，不把历史自由文本反推成具体原因。Room v27→v28 迁移只新增可空字段。Redmi 完整 instrumentation `143/143`、JVM `424/424` 通过；本阶段未取得自然停止样本，不改变普通 WorkManager、fail-closed 恢复和 Foreground Service 后置策略。

## 第 61 阶段验证边界

Redmi 熄屏状态下（`mWakefulness=Asleep / mScreenOn=false / mState=ACTIVE`），JobScheduler 冷启动生产 Worker，在计划时间后 `159.479s` 启动，并完成 `244.236s` 的 8 步复合 SAFE Workflow。8 个 AgentRun、32/32 ToolResult 与验证事件全部成功；每个 Run 的 `consumedMs` 预算快照无回退，最大值 `18.283s–44.856s`，无模型失败。该结果扩大了普通 WorkManager 在熄屏场景的可信度，但不承诺自然系统回收、任意长度存活或 Foreground Service 必要性。

## 第 60 阶段验证边界

Redmi 的一次性入队 Probe 在 `0.255s` 后退出，原应用 PID 随 instrumentation 结束消失；JobScheduler 随后冷启动新 PID，由生产 Worker 独立完成 `204.977s` 的 8 步复合 SAFE Workflow。WorkRequest、ScheduledTask 和单一 WorkflowRun 关联完整，8 个 AgentRun、32/32 ToolResult 与 32 条验证事件全部成功；每个 Run 的 11 条 `consumedMs` 预算快照无回退，`llmFailures=0`。这证明普通 WorkManager 可以承载当前规模的真实冷启动后台链，不证明 Android 会保证任意长任务存活，也没有新增自然 LMK 或 Foreground Service 需求证据。

## 第 59 阶段验证边界

Redmi `wsvwypiz7xwslvl7` 的正式 WorkManager 已完成 `229.416s` 的 8 步复合 SAFE Workflow：每步依次调用 3 个应用内只读工具，24/24 ToolResult 均成功并通过验证，Task/Workflow/8 个 AgentRun 全部完成，单一 ScheduledTask 未复制执行；记录 72 条预算更新、24 条 `tool.verify`，没有 `llm.request.failed`。`ApplicationExitInfo` 为 `supported=true / lowMemory=0`，14 条历史退出均为 instrumentation 或安装停止，仍没有 Android 自主 LMK。该样本扩大了普通 WorkManager 的真实耗时证据，但不承诺任意长度的系统存活，也不触发 Foreground Service 预先引入。

## 第 58 阶段验证边界

第 58 阶段早期两次真实后台 Workflow 因 Redmi TLS 握手失败在约 4 至 6 秒收敛，设备自带 `curl` 可独立复现 `BoringSSL SSL_ERROR_SYSCALL`；这两次不是成功任务耗时，也不是 Android 自主回收证据。系统没有通过关闭证书校验或预先引入 Foreground Service 绕过该问题，网络恢复后的成功样本见下一段。

网络恢复后第 58 阶段已取得一条成功长任务样本：普通 WorkManager 在 Redmi 上以 `92.667s` 完成 8 步 SAFE Workflow，8 个 Agent Run 和工具验证全部成功，预算快照单调且未复制 Run；历史退出中 `lowMemoryExits=0`，因此仍不能宣称 Android 自主 LMK 或据此预先引入 Foreground Service。前述 TLS 失败仍保留为网络阻断样本。

## 产品定位

小灵是一款运行在 Android 手机上的个人 Agent。它不是单纯的模型聊天客户端，也不是默认拥有手机全部权限的自动化脚本。它应当先理解用户目标，再在明确授权的能力范围内调用工具、记录过程、验证结果，并把控制权留给用户。

## 核心用户价值

- 一个长期可用的个人入口：对话、记忆、任务和工具在同一应用内协作。
- 一个可控的执行者：每次操作可解释、可停止、可确认、可追溯。
- 一个开放的模型客户端：支持用户自己的 OpenAI-compatible 服务，不绑定单一模型厂商。
- 一个逐步扩展的移动 Agent：先做应用内安全工具，再扩展日程、通知、文件和跨应用自动化。

## 产品原则

1. **聊天与执行分流**：普通问答走快速路径；只有需要工具的任务才进入 Agent Runtime。
2. **能力来自注册表**：模型只能调用代码注册且当前启用的工具，不能自行声明权限或执行任意命令。
3. **风险由代码决定**：工具风险、Android 权限、确认要求和结果验证规则由应用定义，不能信任模型给出的风险等级。
4. **动作不等于完成**：写入、发送、删除、修改等操作必须通过工具结果或重新读取状态验证。
5. **记忆可见可控**：用户可以查看、编辑、删除和禁用长期记忆；记忆必须保留来源和更新时间。
6. **本地优先**：会话、运行记录、记忆和配置默认保存在设备本地；密钥继续使用 Android Keystore 保护。
7. **渐进授权**：首次使用不要求一次性开放全部权限，只有启用具体能力时才请求对应权限。

## 当前已交付能力

- 多 Provider、上游模型同步和模型选择。
- 模型请求 User-Agent 可在设置页按设备自定义；“网络请求”在设置根页与其他设置项保持相同的入口卡片样式，点击后进入独立页面编辑，不在根页行内修改。编辑区默认至少显示 5 行，右下角提供复制和清空操作，并保留恢复默认入口；默认值为 `Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)`，空白配置自动回退默认值，并统一用于模型列表、普通对话、Agent 和后台 Workflow 请求。
- Chat Completions、Responses API 和 SSE 流式输出；Responses 输入支持保留 system/user/assistant 边界的消息，以及通过 `call_id` 关联的 `function_call / function_call_output` typed Items。普通对话可显式开启供应商推理摘要；只有 Responses 请求会发送 `reasoning.summary=auto`，关闭时和 Chat Completions 均不发送。
- Text/Reasoning/Image/Document/Tool 消息 parts：Room 独立保存 part ID、消息内顺序、类型、正文、供应商摘要来源与 item 身份、附件 MIME/文件名/BLOB/detail、文档提取文本/PDF 页数、工具参数、结果、成功状态、验证状态和记忆引用。Image/Document 仅允许 USER 来源，且单条消息最多携带一种附件。图片支持 PNG/JPEG/WEBP；Document 支持 PDF、TXT、Markdown、JSON、CSV、DOCX、PPTX、XLSX，单文件最大 8 MB。PDF 必须由平台解析且最多 50 页；文本必须是有效 UTF-8 且最多 200,000 字符；DOCX/PPTX/XLSX 必须是未加密、非分卷、非 ZIP64 的 ZIP/OPC 包，条目不超过 4,096 个、声明及实际流式展开总量都不超过 64 MB，并包含真实可读的 `[Content_Types].xml` 与对应 Word/PowerPoint/Excel 根入口。OpenXML 只接受匹配格式的 MIME、空 MIME 或通用 ZIP/二进制 MIME。Responses 分别映射为 `input_image` / `input_file` Data URL；Chat Completions 明确拒绝附件，`/agent` 仅允许 Responses USER 单一附件并只送入规划请求。Reasoning 只接受 Responses `reasoning.summary[].summary_text`，不读取或展示原始 `reasoning_text/reasoning_content/encrypted_content`；附件 Base64 与原始/加密推理在 debug 日志中必须脱敏。Agent Tool part 必须由应用可信上下文投影，Image/Document/Reasoning 均不进入 `VerifiedAgentContext`、不能生成或替代 Tool。附件 BLOB 只为当前会话加载，切换会话必须在完整 parts 就绪后原子更新界面；轻量快照保存不得清空未加载 BLOB，网络请求必须等待用户消息与附件事务提交。前台快照保存只能增量 upsert，用户删除必须显式传递会话 ID，并在事务前过滤删除集合，以保留并发后台 Workflow 证据且防止陈旧快照复活已删会话。
- 本地知识库、管理 UI、Agent 检索与答案引用：第一版只导入 TXT、Markdown、JSON 和 CSV 的严格 UTF-8 文本，最大 64 MB / 1600 万 UTF-16 字符，统一移除 BOM、规范换行、拒绝空白文档与二进制空字符，并对规范全文计算 SHA-256。文档身份与 revision 分离；确定性分块优先在段落边界结束，默认每块 1600 字符、固定最多 200 字符重叠，offset 指向 Room 内规范全文且不得切断 UTF-16 代理对。Room v33 保存全文、chunks、FTS4/LIKE 索引、Embedding 向量、带 top1/top2/margin/候选数/均值/标准差/top1 z-score shadow 字段的检索审计和 Tool/Message 知识引用；检索同时保留中文与字面通配符安全的 `LIKE` 兜底。设置页支持 SAF 导入、轻量摘要列表、详情、启停、替换、删除、显式检索预览和当前 Provider/模型的 Embedding 重建；列表不得读取完整正文，详情只从 SQLite 投影并安全截取最多 4,000 个 UTF-16 单元。`knowledge.search` 必须是 SAFE、支持后台、`query` 1 至 200 字符、`limit` 默认 3 且最大 5，并把 conversation/run/retrieval/document/revision/chunk/offset 身份写入审计。Agent 回复下方的独立引用区域只允许从结构化 MessagePart/VerifiedAgentContext 投影，不解析模型自由文本；默认折叠，展开后展示文档名、revision、chunk 和 `[startOffset, endOffset)`，文档仍存在时可跳转知识库详情。引用状态查询必须分批控制 SQLite 绑定参数数量，取消的旧核验任务不得把新状态覆盖为失败；无法核验时显示独立未知状态，不得误报为引用失效。替换必须在同一事务内递增 revision、更新 parser/hash、删除旧 chunks/FTS 并生成新 chunk ID；禁用、替换或删除后立即退出检索。历史 Run/消息审计不回写，启用的新 revision 标记“历史版本”，停用、删除或证据漂移标记“当前不可用”，且失效知识消息和可能包含旧片段的摘要不得再次进入模型上下文。Embedding 检索、显式单文档重建、多 Provider/模型索引空间和离线相关性扩样校准已接入；生产拒绝、规模化 ANN、自动后台批量重建继续后置。
- 设备 Agent 观察与有限动作层：应用内独立开关默认关闭，并与系统 Accessibility 授权分别生效；健康状态必须区分应用关闭、未授权、已授权但服务断连和可用。前台直接 `/agent` 可在 Profile/Skill 允许时使用 `device.snapshot / open_app / back / home / tap_ref / type_text / swipe`；前台手动 Workflow 当前精确开放同一 Agent Run 内的 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe`。打开应用、点击与文本输入都必须使用当前步骤意图、Room 独立审批和 Accessibility 安全浮层，并在动作后同时取得 `executorVerified=true`、typed `PASSED` 与白名单后置观察；`open_app` 的唯一包名在三层白名单和答案级重建中绑定。`back / home` 只接受空参数并固定为 SAFE 零审批，`swipe` 只接受严格的 snapshot/ref/direction 参数并同样固定为 SAFE 零审批；三者仍要求同 Run 已验证 snapshot、30 秒 TTL、当前 generation 和相同后置验证，其中 `home` 还必须匹配系统动态解析的 launcher，`swipe` 还必须证明同窗内容变化与共同匿名锚点的请求方向主位移。文本输入还必须通过敏感文本预审计、当前 ref 的 enabled/editable/redacted/动作证据、最小指纹授权和绑定原 `nodePath` 的精确回读。`device.type_text` 原文只留在当前 ToolCall 内存，Workflow 与直接 `/agent` 的 proposed/validated/ToolCall ledger、Room 审批、可信消息上下文和 Tool parts 仅持久化 snapshot/ref、SHA-256 指纹与长度；历史、浮层和答案级证据不展示或保存原文。后台或定时 Workflow、恢复自动续跑、开关关闭和缺少 Run Context 时仍在规划与 Executor 两层拒绝全部设备动作。快照最多返回 200 个可见有效节点和 4,000 个文本字符，文本截断不得切断 UTF-16 代理对；可操作且未脱敏节点获得 30 秒 ref，ref 绑定 snapshot、窗口 generation、节点路径、指纹和目标属性，页面变化、过期、关闭开关或任一失败后立即失效，不回退坐标。在前台直接 `/agent` 中，`open_app / tap_ref / type_text` 必须审批，`back / home / swipe` 按 SAFE 执行；Workflow 只开放前述七项并继续要求各自动作门禁。打开应用仅允许小灵、系统计算器、时钟、系统设置和 Google 天气，输入限制为 500 字符并拒绝密码、验证码、密钥、账号及身份信息。每次动作后必须重新 snapshot，瞬时空窗口或读取期页面变化只允许短时有界重试；`type_text` 只接受原路径目标节点的 `text` 精确回读，其他节点同文不能替代，最终只有 `verified=true` 才能通过 Executor 验证。密码、验证码、API Key、Token、手机号、身份证、银行卡和邮箱节点不返回正文、动作或 ref；支付/高敏身份验证窗口及已知密码管理器、Authenticator、钱包/银行应用整窗拒绝。AccessibilityService 只使用标准节点动作与系统返回/主页，不执行坐标手势或截图；Release 不包含可外部触发的诊断 Receiver/探针 Activity。既有 Profile/Skill 不因新工具自动扩权。
- 多会话、本地保存、会话摘要压缩和 Markdown 渲染；普通聊天上下文筛选、知识生命周期核验、最近窗口、增量摘要与请求消息构造已统一迁入 `ConversationRequestContextPreparer`，网络发送顺序由 `ConversationSendCoordinator` 编排，会话标题、空占位折叠、时间戳、摘要元数据继承、非当前更新隔离、新建会话和删除后选择计划由 `ConversationSessionPolicy` 统一投影，latest-save Job、Room 单写者和显式删除意图由 `ConversationPersistenceCoordinator` 协调，异步加载 Job/代次与加载 UI 投影分别由 `ConversationLoadCoordinator` 和 `ConversationLoadProjectionPolicy` 承担，选择/删除副作用顺序由 `ConversationSelectionCoordinator` 组合。ViewModel 继续负责依赖注入、运行态 Map、UI 投影、成功保存和其他 Compose 副作用。
- Provider、模型、请求模式、流式状态、首字耗时和总耗时等消息元数据。
- API Key 的 Android Keystore + AES-GCM 加密存储。
- 常见网络、鉴权、限流、模型和响应解析错误分类。
- `/agent <目标>` 顺序多步执行入口，以及 `AgentRun / AgentStep / ApprovalRequest / RunEvent` 可审计运行链路。
- Agent Profile v1：用户可以创建、编辑、选择和删除多个 Agent；每个 Profile 固定名称、标识、Provider、模型、Chat Completions/Responses 模式、系统提示词、当前会话上下文策略、工具白名单、Skill 白名单和长期记忆开关。至少保留一个 Profile，仍被 Agent 使用的 Provider 或模型不能直接删除/停用。
- 新 Run 必须写入唯一 `agent.profile.selected` typed event 并冻结完整 Profile 快照。Profile 系统提示词只能调整表达方式和授权工具内的任务偏好，不能覆盖 JSON 协议、工具白名单、风险、审批、Android 权限、后置验证和可信事实边界；Profile 工具白名单在 Registry 执行层强制生效，Skill 只能继续缩小工具面。
- 有界 Agent Runtime：同一 Run 最多 4 次工具调用，模型每轮返回继续调用或完成；具备模型与工具超时、整次 Run 超时、取消、完整输入 Schema/业务规则、重复调用检测，以及参数校验时、审批结束后执行前、工具返回后验证前的 Android 权限复检。任一检查点权限缺失都必须 fail-closed。AgentRun 一旦进入任一终态，迟到的模型、HTTP、工具或恢复写入不得覆盖状态、结果和错误证据，也不得新增/改写 Step、覆盖一次性 Approval 决定，或追加 RunEvent/Tool Ledger 成功事实。兼容模型把同一个工具名同时写入 `action/tool` 时可以归一化，不一致动作仍拒绝。
- 模型规划、工具和模型总结必须使用同一单调执行时钟累计 Run 预算；Step 剩余时间小于或等于单步上限时归因 Run timeout，否则归因 Step timeout。审批等待不消耗执行预算。新 Run 从零值快照开始，每个成功执行段后以 typed RunEvent 持久化累计值；审批及受限恢复必须继承原 Run 的总额和已消耗值。旧 Run 可从零值兼容起点继续，但快照缺失结构、越界、总额漂移、累计回退，或 ToolResult 已落库而后续预算快照缺失时必须拒绝恢复。
- 应用侧 Tool Registry 统一声明字符串/整数/数值/布尔类型、长度/范围/枚举、风险、确认、Android 权限、后台能力、超时和验证策略；Runtime 按前台/后台来源执行能力门禁，模型不能增加未知参数、修改工具风险或自行增加执行事实。
- 工具执行证据使用 `ToolExecutionReceipt` 记录 ToolCall ID、业务 operation ID、可选幂等键和提交状态，并把执行时的 `ToolReplaySafety` 声明快照随 `tool.result` typed metadata 持久化。Executor 提供的回执必须绑定当前 ToolCall；错配时 Runtime fail-closed。只有执行时快照和当前工具定义都显式声明 `IDEMPOTENT_BY_KEY`、回执状态为 `COMMITTED`、ToolCall 身份一致且幂等键存在时，证据策略才可判定已提交副作用可复用；旧事件缺少快照时默认 `RESTART_REQUIRED`。`notes.create` 使用 ToolCall ID 作为存储层唯一幂等键；`memory.remember` 使用独立 operation ledger 保存载荷与结果快照。两者同键同载荷返回原 operation，同键不同载荷必须拒绝且不覆盖旧数据。
- ToolCall/ToolResult 使用独立 Room Ledger 保存稳定调用 ID、参数、proposed/validated 事件锚点、结果、显式错误、耗时、Executor 回读状态、最终验证状态、记忆引用、重放声明和执行回执。RunEvent 与 Ledger 必须在同一事务写入；同一 ToolCall 身份或参数漂移时整笔回滚。迁移期 RunEvent 继续作为时间线事实源，v19 旧 Run 不根据可能缺失身份的历史事件补造 Ledger，也不因 Ledger 为空失去原有恢复能力。
- 第一批应用内工具：当前时间、会话列表与检索、本机笔记列表/检索/创建、长期记忆检索/写入，以及只读本地知识库检索。
- 声明式 Skill 按需加载与管理：内置和本地 Skill 统一进入 Room Catalog；本地 `schemaVersion=1` JSON 经过字段白名单、工具注册表、风险与 Android 权限一致性校验后才可导入，设置页支持查看、启停和删除本地 Skill。Run 审计固定所选 Skill 的 ID/版本，审批恢复不得因期间停用、删除或升版而扩大工具面。
- 多步骤 Workflow Ledger：用户可保存、编辑、启停和运行包含 1 至 8 个顺序 Agent 步骤的工作流；活动 Run 存在时禁止编辑，历史 Run 保留创建时的步骤定义、输入/输出快照、幂等键、触发来源、会话、关联 Agent Run、结果和失败原因。手动运行复用现有前台审批与验证链路，后台调度按相同顺序执行且不会绕过审批门禁；前台三步骤、后台三步骤和审批后继续下一步骤均已通过真实模型真机验收。
- Workflow 安全重试：`BLOCKED / FAILED / CANCELLED` Run 可创建带 `retryOfWorkflowRunId` 的新 Run；只复用来源 Run 连续成功前缀的输出，首个未完成步骤及后续步骤重新执行。已启动过的失败步骤重试前必须二次确认，旧 Run 和步骤快照保持不变；真机已确认来源失败 Run 不变、新 Run 正确关联来源且定义编辑不回写历史快照。
- Workflow 知识证据边界：涉及 `knowledge.search` 的步骤输出必须把正文和结构化引用作为同一版本化快照保存。前台、后台、审批恢复和关联新 Run 重试在准备下一步骤时，都必须重新核对当前文档启用状态、revision、名称、chunk sequence 和 offset；引用缺失、畸形或任一引用失效时不得把该步骤正文写入新 Agent Run 目标，但来源 Run 和步骤快照必须原样保留供审计。
- 一次性非精确定时：用户可为已启用 Workflow 创建或取消 1 分钟至 7 天的一次性计划；`ScheduledTask` 记录计划时间、实际启动时间、WorkRequest 和关联 Workflow Run。`RUNNING` 实例必须提供用户可见停止入口：Workflow 仍活动时先在 Room 原子写入持久中间态 `STOP_REQUESTED`，再取消目标 WorkRequest，并按 Task→Workflow→Agent 持久化链定向收敛。系统取消异常、即时 fallback 失败、Worker 未及时收敛和 Agent 尚未关联都不能丢失停止意图；`SCHEDULED→RUNNING` 抢占不能让同一次点击只取消调度而遗漏执行链。停止必须幂等，不影响其他 Run，不创建替代 Run；`STOP_REQUESTED` 下的迟到成功只能收敛为取消，Workflow/Task 终态必须在同一事务重新读取栅栏后原子结算，停止 fallback 也不得分两次写入这两个终态。若 Workflow 在停止事务前已经终态，必须保留该历史终态并在同一事务把半结算 Task 映射到一致状态，不得让通用 `STOP_REQUESTED→CANCELLED` 栅栏再次改写该映射、写入伪停止栅栏或覆盖历史结果。后台只允许显式开放的 SAFE 只读工具，需审批工具进入 `BLOCKED` 并通知用户以前台新 Run 继续；真机已验证触发前进程被回收后由 WorkManager 冷启动执行并收敛 Ledger。
- Daily/Weekly 周期规则：用户可按当前系统时区保存每日或每周墙上时间；每次只物化一个独立的 OneTime `ScheduledTask`，终态后再生成下一未来实例，不补跑错过的历史周期。`STOP_REQUESTED` 不是终态，旧实例完成停止重对账前不得物化下一实例。替换规则会取消旧待执行实例，停用规则或 Workflow 会同步取消系统队列；每次触发仍建立独立 Workflow/Agent Run，旧 Run 和历史实例保持不变。
- Workflow 进程终止对账：步骤结果已经事务落库而下一步骤尚未启动时，进程终止不得被当作普通业务失败通知或 WorkManager 自动重试；启动对账须保留已完成步骤和输出，旧 Run 收敛为 `FAILED`，用户创建关联新 Run 后只复用连续成功前缀，不能自动继续旧 Workflow 或复制 Agent Run。
- 设置页长期记忆管理：FTS4 + 中文子串兜底搜索、全部/启用/禁用筛选、内容/标签/类型/置信度编辑、置顶、启停、删除确认、跨进程撤销和来源会话/Run 跳转；禁用或删除后不再参与 Agent 检索。
- 默认关闭的候选记忆：成功轮次结束后只从明确陈述生成候选，由用户确认或忽略；API Key、token、密码、银行卡、身份证和手机号只记录敏感类别，不保存原值。
- 记忆治理：规范化相同事实复用旧记忆，同类型同主题的不同事实标记冲突并保留旧记录；`memory.remember` 同样执行敏感过滤和去重。
- 记忆引用审计：`memory.search` 的实际命中 ID 必须进入 RunEvent 和已验证 Agent 上下文；`/agent` 单次可关闭记忆召回，关闭后不能访问 `memory.search`。
- 对话内 Run 时间线和审批卡片，以及设置页 Agent 任务中心；任务中心支持全部/需确认/处理中/可重试/已完成筛选、完整 ToolResult、步骤、审批、结构化事件和失败任务重试。“需确认”只包含已结束、可重试且副作用证据要求确认的 Run，不混入仍在等待审批的活动 Run；确认时必须重新核对证据码，稳定后只创建关联新 Run。v20 新 Run 有独立工具账本时必须按调用优先展示账本中的 proposed→validated→result→verified，typed RunEvent 只用于一致性核对；账本完全为空且存在 typed 工具事件的旧 Run 才回退事件。旧结果或验证缺少 ToolCall ID 时必须标为“关联未知”，不得按工具名或时间顺序伪造调用关联。双源字段、身份或事件锚点不一致时必须显示审计告警，但不得自动修补旧 Run 或改变恢复结论。`memory.remember` 恢复失败必须展示稳定错误码、具体原因和建议动作；建议动作只能引导修复记忆状态后创建新 Run，不能暗示旧 Run 会继续。当前筛选范围会基于持久化审计数据展示 Run 数、终态成功率、平均耗时、非成功数、模型/工具调用数、模型总耗时、平均 TTFB、Prompt 字节、上游 Token usage 覆盖率和失败终态分布；单 Run 使用同一持久化口径。活动 Run 不进入质量或失败分母，上游未返回 usage 时必须显示未返回，不能补零。
- 失败、取消和预算耗尽 Run 可创建新 Run 重新执行；新 Run 通过 `retryOfRunId` 关联来源，旧 Run 保持不变。v20 非空工具账本必须作为副作用判断事实源：非 SAFE 调用只要结果成功，或执行回执为 `COMMITTED / UNKNOWN`，重试前都必须二次确认；账本缺锚点、字段漂移、事件链不完整等异常同样保守要求确认，不得回退旧事件。账本完全为空的旧 Run 才沿用 typed event 成功结果判断；仅停在 proposed/validated 且尚未执行的调用不因此增加确认。恢复事件或失败步骤表明中断发生在 `EXECUTING/VERIFYING` 时仍必须确认。重试启动后必须进入来源会话，使重新触发的审批对用户可见。
- 重试门禁必须把副作用证据归一化为稳定分类：`NO_SIDE_EFFECT`、`NOT_COMMITTED`、`COMMIT_UNKNOWN`、`COMMITTED_UNVERIFIED`、`COMMITTED_VERIFIED` 和 `EVIDENCE_INCOMPLETE`。任务卡、确认弹窗和确认前二次校验共享同一分类；`COMMIT_UNKNOWN`、已提交或证据不完整必须确认，不能自动恢复旧 Run 或重放工具。分类只是新 Run 重试指导，不代表旧 Run 已被恢复。
- 任务中心必须直接显示当前重试证据的分类、原因和建议动作；卡片与确认弹窗使用同一评估结果，不能只显示会被截断的分类码而隐藏提交未知或证据不完整的处理边界。
- 应用启动关闭不可原地恢复的活动 Run 时，必须在修改步骤/审批终态前计算副作用证据分类，并把分类和 Ledger/Event canonical fingerprint 写入 typed `run.recovered` 事件。后续重试仍需重新核对当前 Ledger/Event 指纹；启动清理把未执行的 `PENDING` 步骤统一改成 `CANCELLED` 时不得据此虚构副作用中断，当前分类或指纹与启动快照真正不一致时必须升级为 `EVIDENCE_INCOMPLETE`，不能让旧快照掩盖账本漂移。
- ToolResult 事件、执行预算快照和 `tool.verify` 事件之间的持久化边界必须可独立审计：Result 已落库但后续预算快照缺失时必须拒绝原地恢复并归类为执行预算证据无效；验证事件已落库但验证 Step 尚未收尾时只能补齐控制面，不得重复调用 Executor、ToolResult 或验证事件。生产故障注入默认为 no-op，不能改变正常执行顺序。
- 模型规划异常必须先保存可用的失败 telemetry 和已消耗预算，再进入失败终态；无 telemetry 的网络/网关异常至少保存预算快照。模型总结异常不能覆盖已经成功验证的工具事实，必须写入 fallback 审计并使用本地可信回复完成 Run；Receipt 回读失败必须保留稳定 `RecoveryFailure`、`COMMIT_UNKNOWN` 和二次确认重试边界。
- 规划和总结阶段的模型异常必须统一追加 typed `llm.request.failed`，并把上游错误映射为稳定的 `AUTHENTICATION / REQUEST_URL / RATE_LIMIT / MODEL / TIMEOUT / DNS / TLS / CONNECTION / RESPONSE / UNKNOWN` 分类；流式连接中断归入 `CONNECTION`，无法识别的当前异常和未来枚举都保守降级为 `UNKNOWN`。事件只展示阶段、错误码和脱敏原因，不保存请求正文。
- Runtime 因用户停止、WorkManager 取消或系统协程取消而收敛 Run 时，必须在 `NonCancellable` 中先持久化当前单调执行预算，再写取消 Step、`run.cancelled` 事件和 Run 终态；取消前模型/工具 finally 已累计的时间不能停留在旧快照，预算事件必须排在取消终态之前。
- 普通对话流式输出已经产生部分 delta 后发生断流或取消时，必须保留用户已见正文但写入明确的失败/取消终态，UI 不得继续显示“接收中”，也不能把该部分 assistant 作为完整回复再次进入后续模型请求或会话摘要；没有收到 delta 时不得凭空创建 assistant 正文。
- `AgentRunResumePolicy` 返回 `RESTART_REQUIRED` 时必须同时给出稳定处置码、具体策略原因、可证明的证据边界和下一步动作，不能只依赖可变中文文案。启动收敛必须把该处置与重试证据共同冻结到 typed `run.recovered`；任务卡、详情顶部和事件区读取同一历史快照。旧事件缺少处置字段时不得用当前版本策略回填或猜造，未知未来枚举必须保守降级。所有建议只允许保留旧 Run 并创建关联新 Run，不能暗示恢复旧模型协程、旧 Executor 或 Workflow 后续步骤。
- WorkManager 再次拉起已处于 `RUNNING / STOP_REQUESTED` 的 ScheduledTask 时，必须按 `ScheduledTask -> WorkflowRun -> AgentRun` 关联链定向收敛旧实例，不能重新 claim、重新创建 Agent Run 或返回 `Result.retry` 复制可能已执行的副作用；Agent、Workflow、ScheduledTask 按顺序进入终态后才允许物化周期下一实例，无关前台 Run 不得受影响。`STOP_REQUESTED` 必须固定按用户停止收敛为取消，不能依据迟到 Workflow 成功改写；停止发生在 Workflow 已认领但 Agent Run 尚未关联的窗口时，也必须依据 Workflow→ScheduledTask 的持久关联取消 Run、未完成步骤和 Task，不能以“关联 Agent 缺失”写成失败。该链路已在 Redmi 完成同一 WorkRequest 的受控冷启动重入、强制 Doze 延迟、trim-memory、无压力对照和持久停止恢复；每个样本都只创建一个 Workflow/Agent Run。`run-as kill -9`、`force-idle` 与 `send-trim-memory` 不得表述为 Android 自主回收或连接关闭的因果证明。前台启动恢复与新 Worker 并发时，AgentRun 终态必须以原子条件更新保护，不能出现 Task/Workflow 已取消而 AgentRun 被迟到结果改成完成。
- 同一进程内，ScheduledWorkflowWorker 必须在任何 Room claim、重入对账或状态修改前登记 Task 执行所有权。应用启动恢复必须在同一互斥边界冻结旧 AgentRun、WorkflowRun 和 `RUNNING / STOP_REQUESTED` ScheduledTask 候选，并沿 Task→Workflow→Agent/Step 关联排除当前进程真正 `RUNNING` 的 Worker；已经写入 `STOP_REQUESTED` 的链不得再被进程所有权排除。快照期间新 Worker 必须等待，快照后的执行不得进入旧候选。后续 Agent 恢复/关闭和 Workflow/Task 对账只能消费冻结 ID，不能重新全库扫描误伤新执行。该能力不得依赖墙上时间、不得为当前版本新增持久 owner token 或 Room Schema，也不得借此恢复旧模型协程、未知提交执行栈或 Workflow 后续步骤。
- 系统进程退出观察必须是 Task/Run 之外的独立诊断账本。不得仅凭退出时间接近某次执行就建立因果关联；只保存 Android 稳定数值字段与应用侧稳定分类，description、trace 和进程状态摘要不得进入持久化或测试日志。只有 `REASON_LOW_MEMORY` 可作为直接 LMK，`SIGKILL` fallback 必须同时满足设备不支持直接报告且仍只能作为候选；用户停止、应用取消、权限/包变更不得冒充自然回收。重复历史稳定去重并最多保留 30 条；旁路采集异常不能阻断前台恢复或后台 Workflow，但协程取消必须传播。
- Room v33 本地保存 Provider、Agent Profile、会话、消息及 MessagePart、Agent Run、审批、独立工具调用/结果、笔记、长期记忆、候选记忆、记忆操作映射、Skill、Workflow、WorkflowStepDefinition、WorkflowSchedule、ScheduledTask、独立进程退出观察、匿名 answerability Shadow 观测，以及知识文档全文、chunks、FTS、Embedding 向量和检索审计；RunEvent 使用独立 typed metadata 保存时间线事实。v25→v26 只创建空知识库表，v26→v27 增加知识引用 JSON，v27→v28 增加后台 Worker 停止原因，v28→v29 创建退出观察表，v29→v30 增加按 Provider/模型隔离的向量表与检索身份/状态，v30→v31 增加可空的 top1、top2、margin 和有效候选数，v31→v32 增加可空的候选均值、总体标准差和 top1 z-score，v32→v33 创建空的匿名 Shadow 账本；升级不从旧正文、URI、`verifiedAgentContext`、工具记录、旧错误文案、当前向量、历史阶段合计或时间邻近关系猜造知识引用、Embedding 分数、相对分布、Shadow 记录、系统停止原因或 Task/Run 归因。v4→v33、各增量迁移和全新 v33 建库已有 Schema 与迁移测试保护。
- 普通对话、会话摘要 / 记忆、Agent 回复总结三类独立提示词设置，支持开关、即时保存、恢复默认和最终 system prompt 预览。
- 用户可通过 Android 系统文件选择器导出或恢复本地 Room ZIP 备份；恢复必须先校验版本并明确提示重启，API Key 密文不能脱离当前 Keystore 直接恢复。
- `MessageOrigin` 与 `VerifiedAgentContext` 可信来源边界：普通聊天、用户正文和模型自由文本不能伪造工具执行事实。
- 恢复边界已明确：`WAITING_APPROVAL` Run 在恰好一个 `PENDING` Approval 与最后一个已校验但无结果的 ToolCall 完全匹配、所有前序 ToolCall 均成功且 `PASSED`、执行/验证 Step 数量与顺序一致时，可以保留原 Run 等待用户决定；首步和第二次及后续审批使用同一证据规则。发起消息会先持久化，旧数据缺少消息锚点时按 Run 重建。执行/验证中的 Run 默认必须创建新 Run 安全重新运行，但有四个严格且互斥的例外：最后一个 `notes.create` 或 `memory.remember` 同时具备完整 `COMMITTED + IDEMPOTENT_BY_KEY` 历史证据、工具白名单能力且尚无 `tool.verify` 时，可在原 Run 恢复只读后置验证；Run 已处于 `VERIFYING`，所有 ToolResult 均成功、所有 `tool.verify` 均为 `PASSED`，且 Step、Ledger、typed RunEvent 与原 Agent Profile 完全一致时，可在原 Run 恢复控制面收尾；Run 仍为 `EXECUTING` 且严格失败 ToolResult、预算、Step 与尾部证据完整时，只可把原执行 Step/Run 原子结算为 `FAILED`；Run 为 `VERIFYING` 且成功结果、typed `FAILED` 验证原因、结果后预算、最后运行中验证 Step 与无尾随事件同时完整时，只可把原验证 Step/Run 原子结算为 `FAILED`。v20 Run 只要存在独立工具账本，恢复必须 Ledger-first 并用 typed RunEvent 核对身份、字段、派生错误与事件顺序；任一缺失、重复或漂移都不得回退旧事件推断，账本完全为空的旧 Run 才保留严格 typed event 身份语义，但失败终态结算禁止 event fallback。旧验证事件缺少 ToolCall ID 时必须返回关联未知并升级为证据不完整，不得以工具名或事件顺序配对。
- 应用重启后会把可恢复审批重新显示到对应会话；符合证据条件的 `notes.create` 或 `memory.remember` 只读回读原 operation、写入唯一一条 `tool.verify` 并用本地可信总结完成原 Run，不调用写入方法。所有验证事实均已落库的通用工具恢复不会调用 Executor 或 LLM，也不会追加第二条 `tool.verify`；最后验证 Step 尚为 `RUNNING` 时只补为 `COMPLETED`，随后重建全部可信工具上下文并生成本地总结。失败 ToolResult 与 typed 失败验证两类结算同样不调用 Executor、验证器或 LLM，但只补对应 `FAILED` Step/Run 与终态审计，不生成总结或继续 Workflow。四种恢复都不恢复旧模型协程，也不执行 Workflow 后续步骤；关联 Workflow 只保存当前步骤输出，剩余步骤要求创建关联新 Run。记忆恢复还必须保证记录仍启用、未过期且业务字段与提交结果快照一致；`OPERATION_NOT_FOUND / EVIDENCE_INCOMPLETE / PAYLOAD_MISMATCH / OPERATION_MISMATCH / MEMORY_NOT_FOUND / MEMORY_CHANGED / MEMORY_DISABLED / MEMORY_EXPIRED` 必须以独立 typed event 持久化。除上述严格边界外，其他执行/验证中 Run 仍直接安全收敛并通过关联新 Run 重试；旧 Run 及其所有 `PENDING/RUNNING` Step 必须一致进入 `CANCELLED`。
- 审批恢复和已提交结果恢复必须使用原 Run 的 Agent Profile 快照，而不是当前选中的 Profile。缺少 Profile 审计的历史 Run 只能使用知识工具上线前的固定工具集合；新 Run 出现重复、损坏、引用未注册工具或 Skill 越权的 Profile 审计时必须拒绝恢复，不能回退当前 Profile 或当前 Registry 扩大能力。既有 Profile 和 Skill 也不得因注册新工具自动扩权。
- 应用重启后可恢复的链尾审批批准后，会从持久化审批步骤继续同一 Run；前序已验证工具不会重放，`completedTools`、已消耗工具调用数和重复调用指纹会从持久化证据重建，再执行当前 ToolCall、后续规划和最终总结。第一步已经执行后在第二次或后续审批处中断现已支持原 Run 恢复；若当前工具已经进入执行/验证阶段，则按两个受限恢复例外或安全新 Run 边界处理，提交状态未知时不得猜测执行结果。

当前仍未交付相关性生产拒绝、规模化 ANN 和自动后台批量 Embedding 重建，以及提交状态未知、成功结果尚无 typed 验证结论或其他验证事实不完整形状的通用执行栈原地恢复、并行工具调用、后台 Workflow 执行栈断点续跑、精确定时和 Foreground Service。成功结果缺 typed 验证结论的持久化窗口已完成审计，结论是除既有严格白名单只读回查外没有唯一安全动作，因此“不交付原地恢复”是明确安全边界，不是待补实现。多步骤审批等待恢复、“全部验证通过后的控制面收尾恢复”、失败 ToolResult 与 typed 失败验证的两类原子失败终态结算、跨模型/工具段累计预算、当前进程 Worker 启动恢复隔离、后台运行中可见停止、`STOP_REQUESTED` 持久化异常重对账、旧验证事件缺少 ToolCall ID 时的 fail-closed 证据降级、Ledger/Event 指纹漂移拒绝，以及 Result/预算/验证三段持久化边界故障注入、模型异常预算审计和总结本地兜底已经交付，但都不等同于恢复旧模型协程或任意执行栈。设备 Agent 的 Accessibility 授权、健康检查、`device.snapshot`、短生命周期节点引用、隐私过滤、有限动作、风险审批、操作后重新观察和结果验证已交付，并已在 Redmi 上限定小灵、系统计算器、时钟、设置、Google 天气与桌面完成首批验收；不承诺任意 App。前台手动 Workflow 已完成 `device.snapshot / device.open_app / device.back / device.home / device.tap_ref / device.type_text / device.swipe` 生产闭环：所有动作都要求当前步骤意图、同 Run 新鲜观察、当前 generation、`executorVerified=true + typed PASSED` 和白名单后置判定；`open_app / tap_ref / type_text` 额外走 Room/Accessibility overlay 审批，`open_app` 还要求三层包白名单以及完成/答案级目标包绑定，`back / home` 为空参数、零审批 SAFE 导航，`home` 还要求动态 launcher 验证；`swipe` 同为零审批 SAFE 动作，并额外要求 snapshot/ref/direction、同窗内容变化和共同匿名锚点方向主位移。文本输入另具备敏感参数预审计、当前 ref 的可编辑/未脱敏目标证据、最小指纹授权、绑定原 `nodePath` 的精确回读，以及跨 Workflow/直接 `/agent` 的无原文持久化投影；Redmi 真实 Workflow 已分别取得计算器打开 `APPROVED / PASSED / VERIFIED / afterPackage=com.android.calculator2`、天气打开 `APPROVED / PASSED / VERIFIED / afterPackage=com.google.android.apps.weather`、文本输入 `APPROVED / PASSED / VERIFIED / exactReadBack=true`、返回动作 `approvals=0 / verified=true / VERIFIED`、返回桌面动作 `approvals=0 / verified=true / VERIFIED` 和滚动动作 `approvals=0 / registryCompletion=PASSED / answerDecision=VERIFIED / privacySafe=true`。全部后台/定时设备工具继续关闭，截图、坐标、视觉定位和任意 App 也未开放。多步骤 Workflow 与非精确调度已完成真机验收；当前约 229.416 秒八步复合只读成功和 32.6 秒停止样本尚无引入 Foreground Service 的依据。Room v33 沿用 v29 引入的独立账本有界观察 Android 进程退出事实，并新增匿名 answerability Shadow 账本；Redmi 支持 LMK 原因报告，现有受控 `force-stop` 只产生 `CONTROLLED_OR_MAINTENANCE / USER_REQUESTED`，仍没有 Android 自主 LMK。数据库恢复已交付，但跨设备 Provider 密文恢复仍受 Android Keystore 限制。

本文第 122 至 125 阶段和 `v0.1.14` 发布基线中的 `swipe` “继续关闭”，都描述对应阶段当时的生产 Workflow 默认集合。第 126 阶段已经完成生产接线和 Redmi 真实生产 Workflow 验收；方向、viewport/HMAC、snapshot/ref、节点正文和坐标仍不得持久化，后台/定时设备自动化仍保持关闭。

补充：`WAITING_APPROVAL` 的审批恢复已经可以在原 Run 上保留任意长度的已验证前缀，并继续链尾工具、验证、后续规划和总结；恢复同时继承持久化累计执行预算，不因进程重建获得新的总时长。`notes.create` 与 `memory.remember` 开放已提交但尚未验证结果的受限只读验证；所有工具都可在成功结果和 `PASSED` 验证已经完整持久化后恢复本地收尾；严格持久化失败结果或 typed 失败验证只允许原子结算为 `FAILED`。成功结果缺 typed 验证结论的处置优先级已经审计并固定，但不扩大恢复资格；上述未交付项指这些证据不完整形状的通用执行栈、Workflow 后续步骤断点续跑以及尚未完成的自动化能力。

长期记忆最近一次删除的撤销快照保存在应用私有原子文件中；启动时会与 Room 正式记录核对，陈旧或损坏快照不会复活未删除数据，也不会阻断应用启动。

当前实现详情见 [当前实现说明](implementation-notes.md)。

## 目标能力范围

### 第一层：可靠 Agent 基座

- 可取消、可限步、可恢复的 Agent Run。
- Tool Registry、结构化参数校验和统一 Tool Result。
- 运行时间线、错误事件、停止生成和失败重试。
- 工具级风险、确认、权限和验证策略。

### 第二层：个人数据与工作流

- 可管理的长期记忆和用户画像。
- 笔记、提醒、日历、文件等应用内或系统标准能力。
- 可保存、启停和查看历史的定时任务与工作流。
- 可按需加载的 Skill，不把全部工具定义塞入每次模型请求。
- Skill 只能引用已注册工具并缩小工具面，不能修改工具风险、审批、Android 权限和后置验证策略。

### 第三层：移动端执行

- 在独立授权后使用 AccessibilityService 读取界面结构并执行有限操作。
- 以可定位节点为主，截图和坐标只作为兜底。
- 高风险动作前确认，动作后重新观察验证。
- 权限失效、页面变化、任务取消和系统回收后能够明确失败并恢复。

## 暂缓范围

- 账号体系、云同步和跨设备一致性。
- 任意 Shell、Root、ADB 或隐藏系统接口执行。
- 无确认的支付、下单、删除、发送消息和系统设置修改。
- 开放式 Skill 市场和未经审查的远程代码安装。
- 一开始就引入多 Agent 自主协作、完整 MCP 生态或端侧大模型管理。

## 质量要求

- 每个 Agent Run 都有稳定 ID、状态、步骤、事件、耗时和最终结果；终态写入后不可被任何迟到执行路径覆盖。
- App 被杀死或进程重建后，不得把运行中的任务误报为成功，也不得让 AgentRun 与关联 Workflow/ScheduledTask 形成互相矛盾的终态；同一进程刚启动且仍正常 `RUNNING` 的 Worker 不得被前台启动恢复当作旧进程遗留收敛。Workflow 仍活动且用户停止已经写入 `STOP_REQUESTED` 后，平台取消失败、迟到回调、进程所有权或应用重启都不得把 Workflow/Task 写成成功，也不得追加成功会话结果或提前物化周期下一实例；步骤终态与对应成功消息必须共享同一停止栅栏和事务边界。若 Workflow 已先进入终态而 Task 仍活动，后续原子结算必须保留该持久 Workflow 终态并映射到 Task，不得用来晚的停止或迟到 outcome 反向覆盖。
- 工具参数在执行前必须完成类型和业务校验。
- 敏感工具必须在应用侧确认，后台任务不得绕过确认策略。
- 工具报告成功后，关键变更必须有后置验证；无法验证时明确标为“未验证”。
- RunEvent 与独立工具账本双写必须原子完成；v20 非空账本在展示和受限恢复中均为事实源，事件只用于一致性核对，任一身份、字段、时间、顺序或基数漂移都必须 fail-closed。旧 Run 账本完全为空时保守回退到原事件，不得伪造 ToolCall 关联或改变历史恢复结论。
- 记忆写入必须可追溯到会话或任务；候选未经确认不得参与检索，敏感阻断不得保存原值，删除后不再参与检索。
- debug 日志可以诊断请求和工具过程，release 日志不得泄露密钥、完整隐私内容或敏感参数。
- 每个里程碑都需要单元测试、关键状态机测试和 Android 真机验证。

具体开发顺序见 [个人 Agent 路线图](personal-agent-roadmap.md)。
