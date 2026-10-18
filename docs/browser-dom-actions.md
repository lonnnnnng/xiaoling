# Browser 服务端 DOM 与本机草稿动作契约

第 290 阶段第二片，第 6 点 Workspace/Browser/GenUI 的 Browser 基础。Room 保持 v44，发布版本保持 `0.1.22 (23)`。这一片使用服务端 HTML，不包含 WebView、JavaScript 或系统 accessibility 树。第 6 点下一片为 GenUI 版本化组件与显式 action。

## 快照来源与节点

`BrowserDomSnapshot` 固定 `schemaVersion=1 / source=SERVER_HTML`，由 jsoup 解析 GET 返回的 HTML。节点保存稳定的本页 `nodeId / parentId`、tag、原生语义 role、name、文本、公开链接和动作引用。支持 link、heading、textbox、button、paragraph、list、listitem、form；按钮和 form 只读。

- name 来自 `aria-label`、唯一 `label[for]`、祖先 label、文本或 placeholder。没有名字的文本框不开放草稿动作；本片没有 `aria-labelledby`、完整 ARIA 角色计算或 computed CSS。
- 删除 script/style/noscript/template/iframe/object/embed/base，不执行脚本或加载子资源。input 的 value 和 textarea 原值不进入正文或节点。
- HTML 明确隐藏的 hidden/inert/aria-hidden、内联 display:none/visibility:hidden 子树先移除，再聚合父节点和 label 文本，防止隐藏文本经祖先重新进入结果。
- 静态 HTML 无法证明外部 CSS、动态样式或脚本后的实际可见性；class/CSS 隐藏可能仍出现在语义快照。`TYPE_DRAFT` 只指本机会话草稿槽位，不能把它解释为网页上的可见、可输入控件。
- disabled/readonly/aria-disabled 不给动作。输入只允许有名字的 text/search/textarea；密码、密钥、凭据、账号、验证码、银行卡等敏感标记及含 password 字段的整个 form 不给输入动作，即使该 password 节点隐藏。
- 链接与 DOM 来自同一可操作节点集合，隐藏节点没有旧链接引用旁路。最多 100 个不同链接，超额节点撤销导航动作并标记 truncated；每份快照最多 128 节点、扫描 10,000 元素、祖先深度 32。

## 会话身份与动作

会话 owner 精确绑定 `runId / conversationId / userMessageId / processSessionId`。生产 Registry 向每次会话操作传当前 owner，click/type 同时传真实 `toolCallId`。底层无 owner 的兼容调用只使用 reader 私有的本地身份，不赋予生产 Run 权限。

| 工具 | 行为与证据 |
| --- | --- |
| browser.open/read/navigate | 返回页面与语义 DOM、session/snapshot、只读页面证据；read 保持 snapshot，成功导航产生新 snapshot |
| browser.click | 逐次审批，只消费当前 session/snapshot/link ref，经公网 URL 校验后 GET 导航；验证当前动作与新快照 |
| browser.type | 逐次审批，只更新当前非敏感字段的本机内存草稿，原文不发 HTTP、不提交表单、不改变真实网页；验证新快照里的长度与 SHA-256 |
| browser.close | 撤销本会话和在途请求，清除内存草稿；关闭后的引用不可读取或操作 |

草稿长度 1–500 字符，拒绝控制字符。动作成功轮换 snapshot 并重新生成全部 refs，旧引用失效。URL 校验通过后，在发网络请求前原子消费 ref/callId；503、取消或其他请求失败不替换旧快照，但不能重放已消费的动作。需要新快照和新确认才能继续；不存在自动重试。

同一会话已有动作在途时拒绝另一动作或导航。最多 4 个会话，每会话 32 次动作，reader 每个 Run 最多保留 128 个已消费 callId；关闭会话不释放该 Run 的调用去重记录。10 分钟未访问会话在下次访问清理。Run 首次绑定、切换和结束清除会话、草稿与在途 Call，代次和 control 身份复核阻止迟到响应发布。会话不持久化，进程重建必须重新打开。

## 网络与取消

生产 URL 预检及实际建连 DNS 都拒绝空解析、私网、回环、保留地址和混合答案，拒绝账号密码 URL、localhost/.local。DNS 策略拒绝转为 UnknownHostException，不把 RuntimeException 漏到 OkHttp 回调线程。

只做 GET，独立 client 禁 Cookie、proxy、cache 和自动重定向；不提供登录、表单提交、上传、下载、脚本、后台或远端自动执行。只接受 HTTP 200，HTML/plain/xhtml MIME（兼容缺失 MIME）；拒绝 Content-Disposition 和超过 2 MiB 的响应。当前正文按 UTF-8 读取。网页可读证据的 sourceRef 去掉 query/fragment/账号信息，但普通公开页面结果仍包含所读取的 URL 和正文。

OkHttp enqueue 与协程取消绑定，覆盖响应头与正文读取。close/clear 同时取消真实 Call，发布前再次检查 owner control/Run generation 和协程状态；失败或取消不得发布新快照。工具仍只向前台 DIRECT 暴露，继续使用已有 Runtime/审批/Profile 门禁，本片没有新增真实 Activity 生命周期许可。

## 持久审计与验证语义

草稿原文仅存在当前进程的审批参数与内存草稿。统一 `BrowserTypeTextAuditPolicy` 将持久参数投影为 session/snapshot/ref、`text_length / text_sha256`。Room 审批最后边界、Runtime 事件、Tool Ledger 和 VerifiedContext 使用相同投影；OpenAI 后续工具历史也只回传目标和摘要。

含 browser.type 历史的 context snapshot 整条正文省略，沿用历史枚举 `DEVICE_INPUT_REDACTED` 保持 codec/Room 兼容，正文标识浏览器草稿；该枚举不表示本片执行了设备输入。这样的输入不参加精确文本提取压缩，旧待审批动作恢复返回 `EPHEMERAL_TOOL_INPUT_UNAVAILABLE / RESTART_REQUIRED`，重新打开会话并确认。

普通页面保持 `READABLE_ONLY`。click/type 使用 `EXECUTOR_VERIFIED`，在页面证据的可选 `browserAction` 中保存 schema v1 动作 ID、动作种类、toolCallId、sessionId、输入/输出 snapshot、ref 和输入内容 SHA-256；输出 SHA-256 来自页面证据，摘要包含 DOM 与草稿长度/hash。旧页面证据没有该字段仍可读，动作字段损坏拒绝整份证据。

Registry 和 Runtime 核对动作种类、当前调用、session、输入 ref/snapshot 与输出 snapshot 后才接受 PASSED，原因码为 `BROWSER_LOCAL_DRAFT_UPDATED / BROWSER_LINK_NAVIGATED`。输入 hash 是可信 reader 计算的审计字段，Runtime 不独立从持久历史重读旧页面；联合测试核对它与前一步页面 hash 一致。页面内容只是外部数据，不代表已批准指令。动作没有 `ToolExecutionReceipt(COMMITTED)`、持久业务副作用或恢复重放资格；PASSED 只证明本机动作和对应快照变化。

## 验证分级与后续

日志在 `outputs/verification/stage290-browser/`，本轮最终结果见[验证报告](verification-report.md)。JVM 验证 DOM 隐私/容量、owner/stale/replay、失败消费、并发关闭、clear/cancel、DNS 策略、codec 错配、原 Runtime/审批链和模型历史投影；Redmi 通过隔离 Room 与真实 loopback socket 验生产 Registry/Runtime、Room 重开、取消和旧审批恢复。

隔离测试用固定公网 IP 的来源 URL，由仅测试拦截器转到 MockWebServer，并显式注入测试 DNS；这不代表公网网页互通、真实模型规划或网页真实输入验收。另一个 IANA 单项通过默认 DNS/HTTPS 验公开 HTML DOM 与链接导航，仍不是真实模型联合。磁盘重开不代表独立 PID、自然 LMK 或系统重启。本片未运行完整产品界面联合、完整 JVM/Lint/全量 instrumentation 或正式 Release。

独立待办：真实渲染 DOM/动态 CSS 与完整 accessibility 命名、真实网页输入及表单提交的权限契约、Activity 生命周期许可、自然系统与完整产品到真实模型联合、性能与正式 Release。Cookie、登录、脚本、上传/下载、后台继续独立评审，不追加为本片已验收能力。第 6 点仍有 GenUI，尚未进入第 7 点。

官方资料已由 smart-search 获取正文：[jsoup 下载与版本](https://jsoup.org/download)、[selector 语法](https://jsoup.org/cookbook/extracting-data/selector-syntax)、[Element API](https://jsoup.org/apidocs/org/jsoup/nodes/Element.html)。版本固定 `1.23.2`；公开资料不替代本项目的测试证据。
