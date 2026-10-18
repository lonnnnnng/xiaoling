# Android 真实模型与产品验收

第 292 阶段，2026-10-18。短期暂停 iOS 产品工作，保留第 291 阶段已经固化的协议与 codec。本轮使用本机授权配置的 `gpt-6-luna`，只操作 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`，内部 Debug 版本保持 `0.1.22 (23)`。

## 执行与证据

主机通过长度前缀、单次 USB socket 向测试传入配置，收到 CONFIG_READY 后发送并核对 ACK。凭据不进入 argv、Intent、源码、APK 或主机配置文件；直接网络验收关闭 HTTP 调试，日志在主机落盘前脱敏。测试成功必须同时具有配置 ACK、JUnit 成功与对应业务证据，ADB 退出零不能替代 JUnit。

入口：`tools/verify_mcp_live_acceptance.py`、`tools/verify_android_live_acceptance.py`。每轮输出目录必须为空；失败保留原报告，不覆盖后改成成功。各功能独立执行，最后整轮按全部项目结果退出。主机门禁逐项匹配业务标识、API/流式模式和设备动作；正式 UI 同时要求两个聊天结果、GenUI 与恢复回执。最后以保留日志回查 24 项，没有重新执行设备动作。

证据目录 `outputs/verification/stage292-android-live/` 被忽略。`verification-summary.json` 汇总各项目最终选用的成功记录及原失败目录；它不重写任何原报告的 `completed=false`。`mcp-installed` 保存产品故障，`functions-initial/expanded/final/reconnected/authorized/service-wait/safe-final/corrected-final` 分别保留夹具、模型遵循、提供方超时、USB 断连和前台干预的实际结果。

| 功能 | 当前本轮证据 | 范围 |
| --- | --- | --- |
| Context7 认证 HTTPS | 通过 | 默认 DNS/TLS、发现目录、解析 Kotlin 文档库 |
| MCP SSE 联合 | 成功、取消、停用通过 | 真实模型与生产 Registry/Run/Room；SSE 服务为 loopback，Context7 为真实公网 Streamable HTTP |
| 普通聊天 | 两种 API 通过 | Chat Completions、Responses 回传本轮随机公开标识并具有正常终态 |
| 流式聊天与取消 | 两种 API 与取消通过 | 实际 delta、首 token、正常终态；首段后本地取消无迟到交付，不证明远端停止计费 |
| Responses 附件 | 文本、图片通过 | 随机文本标识、现场合成红色 PNG，未上传用户文件 |
| 原生状态 | 通过 | 真实时间、安装版本、电池、网络、存储；五个工具的 PASSED 与 context transcript |
| Workspace/终端 | 通过 | 隔离私有文件写/读、固定 argv、两次受控 ApprovalGate 审批、持久 transcript 与 Run 撤销 |
| Browser | 通过 | 真实 IANA 公网 DNS/TLS、SERVER_HTML、读取与审批后的站内链接导航；不是 WebView 渲染 DOM |
| Memory/Notes/Knowledge | 通过 | 四个独立真实目标、保存/笔记/知识检索及 search→get 召回，当前引用、CAS 冲突、受控 reopen |
| 实际长历史压缩 | 通过 | 两次原生大文件读取后派生输入、完整原始来源/账本、最近正文与持久回读 |
| GenUI 组件联合 | 通过 | 真实模型卡片、生产组件点击、可见验收审批按钮、生产 UseCase、原生时间 PASSED；不称为完整 ViewModel |
| 正式产品 UI | 通过 | 正式 ViewModel/发送/Room 消息、卡片与正式批准按钮，批准前零执行，来源消息 hash 绑定 |
| 签名插件 | 通过 | 真实模型、签名选择、生产 UseCase、原生时间、隔离 Room |
| 两个 child | 通过 | 实际 Activity resumed、两个真实模型 child、PASSED、父结果单次消费 |
| 设备点击 | 通过 | 用户已授予无障碍，真实模型与原生服务点击公开 Probe，页面变化、受控审批和 PASSED |
| 设备输入 | 通过 | 模型提出的公开文字精确匹配，实际 EditText 回读一致；持久文本指纹、后续输入省略投影 |
| 设备滚动 | 通过 | 公开固定内容半屏上滑，共同锚点方向与实际 scrollY；不证明所有应用滚动可验证 |

正式产品 UI 测试没有通用沙箱注入能力，因此先拒绝非空的消息、记忆、笔记、知识库、Workflow 和定时任务；只允许无摘要、无消息的默认空会话，并保留原会话。只新增 UUID 配置/会话/Profile，测试 Provider 走现有 Keystore 加密。最后按 UUID 删除本轮配置、消息和 Profile，原 Provider 不重写；逐键恢复仍属于本轮的选择值，其他偏好只核验，不整表或整文件回滚。清理分别尝试后汇总失败，Run 审计保留。进程崩溃不能保证执行 finally，不把正常恢复断言扩大为断电清理保证。

其余 Runtime 使用 UUID Room、独立 Memory undo、Workspace 和 transcript；最终版本保留隔离公开账本并输出 Run/库名供审计。早期夹具版本清理过自身库，只能回查保留的主机日志。原生工具、设备、Workspace、Memory 使用受控 ApprovalGate，不能冒充正式审批 UI；正式产品卡片项目才核实了实际 ViewModel 的可见批准按钮。

设备测试仅在显式选择、begonia/SDK36 和用户原已授权组件存在时执行。instrumentation 重启宿主在本机留下 enabled-but-crashed 服务，测试只临时重绑原组件、恢复原服务字符串，保留其他服务选择，不新增授权。公开 Probe 的 nonce、resumed 和窗口焦点在采集前及执行前复核，动作绑定预期按钮/编辑框/滚动节点；这不是自然进程恢复证据。

另有 8 项无真实模型的隔离受控用例，以及 context / maintenance / Workspace 分别 3、3、2 轮独立 PID 验收通过。它们覆盖损坏拒绝、CAS/undo/outbox 回滚、生产 Worker 停用门禁、审批复核、提醒计划持久化和旧执行不重放；没有证明系统提醒已真实触发或自然 LMK/Doze 恢复。成功的进程夹具只清理自己的 UUID 库/目录。

保护审计区分文件字节和偏好值：用户开启系统权限并打开 App 后取得有效基线，`room_state.xml` 最终字节摘要发生变化，正式 UI 测试的原值逐项恢复断言通过。按键恢复会重新序列化 XML，不能为匹配旧摘要而覆盖整个文件。追加稳定值摘要前后对比，详见 `protected-audit.json`；不宣称整份文件字节未变。

## 本轮发现

1. 首次手机只有 test 包，主包不存在；instrumentation 因 `Unable to find instrumentation target package` 失败，配置未发送。构建并覆盖安装主包与 test 包后才取得有效保护基线；未卸载或清应用数据。
2. 真实 MCP Run 在 `AgentContextCompressionPolicy.history` 失败。多行目标、工具目录、Skill 与 `trimIndent()` 组合保留模板缩进，历史前缀不再满足严格边界。改成显式前缀加历史，并以真实适配器回归短历史和长历史；修复前两项失败，修复后通过，MCP 五项复验通过。校验强度保持。
3. 初版终端验收指定了两个 `printf` 参数，违反现有单参数白名单；改成单一公开标识参数，不扩大终端权限。下一轮写/读/执行均完成，但测试误要求写入回执含正文，改成检查实际文件、读取和 stdout，仍保留原失败。
4. 组合 Memory 目标出现模型跳过步骤及调整步骤顺序，两次严格断言失败保留。改成四个独立目标后保存、笔记和知识检索完成；召回夹具错误关闭 Runtime 的单次召回开关，修正为仅在显式选择 search/get 时开启，最终通过。另一次笔记写入后提供方超时，FAILED Run 保留，不自动重放写入。不能据独立目标通过宣称任意长计划可靠。
5. 新测试的 Memory confidence 类型、审批枚举与 GenUI policy 包名编译错误均保留失败构建日志，修正后再执行。
6. USB 断连导致一轮 ADB 255、forward 清理失败与后续配置未发送；另一次用户切到系统设置使真实前台 owner 撤销。它们没有改写为模型功能通过。首次正式 UI 因 Compose 测试时钟未推进超时，后续推动真实 UI 调度并收窄默认空会话门禁后通过。
7. 设备夹具的数字 UUID 被敏感文本规则拒绝；改为汉字后模型提交 8 字而完整目标为 14 字，精确审批拒绝，原 FAILED Run 保留。改用明确 JSON 字符串目标后输入、回读与隐私投影通过。顶部向后滚动无动作，上滑整屏后缺少共同锚点而验证失败；公开页改为实际半屏滚动后通过，没有放宽生产敏感文本、snapshot/ref 或 Executor 验证。
8. 结束检查曾观察到系统无障碍授权列表为空，原因未确认。用户再次手动开启后，最终只读检查确认授权、服务绑定正常且无 crashed service；保留之前的空列表和未绑定快照，不以最终成功覆盖。收口阶段没有再运行 instrumentation 或写系统设置。

## 独立待办

- 设备系统授权由用户再次手动开启，最终只读检查已确认绑定；测试临时设备 Agent 开关与关闭免审批开关已恢复。正式设备审批浮层的完整用户点击链、所有目标应用动作及自然服务恢复仍需专项。
- Remote Channel 稳定部署、真实通知/导航、长期 Doze/LMK/系统重启保持原独立待办；受控数据库重开和进程重启不等于自然系统恢复。
- 联系人、日历、通知、语音/TTS、全部分享格式与全部 Workflow 产品流程未因此自动获得本轮真实模型验收结论。
- 正式签名 Release、推送、标签、发布不在本轮范围；短期不继续 iOS 产品工作。

路线状态见[路线图](personal-agent-roadmap.md)，本轮最终计数与保护/制品审计见[验证报告](verification-report.md)。
