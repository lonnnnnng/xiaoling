# iOS 固定页面只读浏览器

第 291 阶段第八片，继续第 7 点。新增 common 浏览器端口与前台只读 Executor，`iosMain` 的普通平台类 `IosReadOnlyBrowser` 使用上一片[HTTP 宿主端口](ios-http-client.md)和系统 libxml2。浏览器契约 v1，shared Agent v5、Room v44、发布版本 `0.1.22 (23)` 保持。下一片为真实前台 loop；当前没有 Swift App、framework 或 expect/actual factory。

## 固定页面能力

宿主通过 `SharedBrowserPageRegistry.https` 登记 1–8 项 `page_id → 完整 URL`，初始化后防御性复制。ID 为小写字母开头、最多 32 字符的字母/数字/下划线/连字符；URL 最多 512 ASCII 字符，仅 HTTPS/443、DNS 名称，不含 query、fragment 或 userinfo。明显本机后缀和数字地址拒绝，路径按宿主原文保留。登记表和页面对象的 `toString` 不输出地址或正文。

这些限制只筛选名称，**没有验证 DNS 结果或实际连接 IP**。宿主必须选择可信的公开固定页面，不能让模型、插件或外部网页修改登记表。NSURLSession 的系统解析、系统代理与网络配置仍受宿主环境影响；这不是任意 URL 的 SSRF 门禁，也不具备 Android 在实际建连时再次执行的 `BrowserPublicDns` 策略。iOS 任意公开 URL 浏览和 DNS pinning 后置。

模型工具只有 `browser.list_pages` 与 `browser.read_page`：list 无参数，仅返回 ID；read 只接受 `page_id` 和规范正整数 `max_chars`。模型没有 URL、headers、body、链接、点击、输入、导航、打开会话或提交表单的参数与工具。HTTP origin 策略允许同源其他路径，但 reader 始终只请求登记的完整 URL；全部重定向拒绝且 3xx 读取失败，不自动访问 Location。

每个 reader 独占一个 HTTP client，固定 GET、Accept 和 User-Agent，无认证头、Cookie、请求正文或应用重试；独立 ephemeral session 的凭据/cache/challenge/redirect 与取消策略沿上一片。没有网页子资源加载器或 WebView。内部 loopback 构造入口只服务模块测试，不是产品清文配置开关。

## 响应与静态文本

只接受 HTTP 200 与明确的 `text/html` 或 `text/plain`，任意 Content-Disposition 都拒绝。Content-Type 只允许缺省 charset，或单一 UTF-8/US-ASCII 参数；其他 MIME、charset 或多参数拒绝。正文最多 512 KiB，先严格 UTF-8 解码，拒绝 NUL 与非空白 C0 控制字符；声明 US-ASCII 时拒绝非 ASCII 字节。不根据 HTML meta 切换其他编码。

HTML 通过 `htmlCtxtReadMemory` 从内存解析，每次独立 context；不加载 DTD、不替换外部实体，独立 SAX resolver 拒绝外部解析、externalSubset 回调关闭。保留 NONET 作为当前 SDK 的防御项，但不把该 flag 当跨版本保证；不改全局 loader，不启用 HUGE。仅一次锁内初始化 libxml，不调用可能影响其他宿主的全局 cleanup。

解析不执行 JavaScript，也不请求 img/script/iframe 等资源。遍历前排除 script/style/noscript/template/iframe/object/embed/base/input/textarea/select/svg/math，以及 hidden/inert/aria-hidden=true、已知 inline display:none/visibility:hidden/collapse 子树；不会先聚合输入原值再尝试脱敏。保留链接文本，不输出 href、可执行 ref 或 DOM 节点；无表单草稿。标题来自 title，正文不含 head 文本；常见块元素分隔，空白折叠。

这不是完整 HTML5 树算法、真实渲染 DOM、accessibility 树或 CSS 可见性判断。外部 CSS、动态内容、CSS 注释或更复杂隐藏方式不在可见性保证内；页面可以包含不可信指令和普通公开文本中的敏感内容，静态排除规则不是通用 DLP。`text/plain` 同样输出折叠空白的预览，不冒充 DOM。

默认预览 12,000、可选 1–50,000 UTF-16 code units；标题最多 512。截断不保留孤立高代理，输出 `truncated`。遍历最多 10,000 个访问节点、深度 32，超限拒绝整页。隐藏子树被跳过，其后代不消耗遍历预算。原生树在遍历前已经建立；HTTP buffer、ByteArray 复制、UTF-8 文本、libxml 树及 Foundation 分配不合并为一个进程内存上限，解析调用不保证能在协程取消的瞬间中断。

## 前台、关闭与证据

Executor 目录全部 `requiresApproval=false / supportsBackground=false`。宿主必须把 `isForegroundOwnerActive` 绑定当前 Run/会话和真实前台生命周期。访问前检查取消和前台，返回前再次检查；回调自身触发取消也不能发布成功。前台撤销丢弃正文和页面证据，取消传播 CancellationException，不降为普通失败。宿主应在 owner 结束时取消执行并 close reader；只有 callback 变 false 不保证即时停止正在进行的网络请求。

reader close 幂等，撤销 pending HTTP 请求并使后续 list/read 失败；响应之后及解析完成后再次检查关闭，最终快照领取与 close 共用 NSLock。close 先领取则不能发布正文；若完成先领取，之后 close 不追改已完成事实。实例没有持久 session、自动恢复或快照重放能力。

成功 content 为 `page_id / mode=STATIC_TEXT / title / text / truncated`。每次读取生成新的随机 snapshot；同一输出的 SHA-256 来自紧凑 JSON content 的严格 UTF-8 字节，使用系统 CommonCrypto，输入不含 call ID 或随机快照。这个格式是浏览器端口 v1 的输出摘要，不冒充 Android 原页面的 canonical hash。

`SharedToolReadableEvidence` 绑定当前 call、snapshot、hash 与无 query 的登记 sourceRef；content 本身不输出 URL。没有 browserAction、verified、typed PASSED 或 executionReceipt；读到页面不代表审批、业务成功、COMMITTED 或恢复重放许可。结果可经[完整结果 v1 codec](kmp-tool-result.md)往返。

## 验收和后续边界

common 测试核对固定完整地址、防御性复制、非法/敏感 URL、目录与参数零访问、前台撤销、访问前/访问中/最终回调取消、调用身份和完整结果往返。iOS 专项使用真实随机端口 socket、系统 NSURLSession 与 libxml2，核对 Unicode/实体/隐藏子树、正文摘要、没有资源加载、MIME/下载/status/charset、坏 UTF-8/NUL、代理对截断、深度/节点、未知页零连接、重定向、容量、取消/close 真实 socket、原生前台撤销、外部 DTD/本地文件实体和 scripted shared loop 联合。

实际测试计数、Simulator/Xcode/SDK、Android 聚焦回归、语料/正式数据保护和产物审计见[验证报告](verification-report.md)，证据目录 `outputs/verification/stage291-ios-browser/`。loopback 为 HTTP 测试专用路径，不证明公网 DNS/TLS、坏证书/mTLS、任意公开 URL 安全、真实模型或 UIKit 产品生命周期。第 7 点尚未整体收口，下一片真实前台 loop；前六点长期独立待办不扩入本片。

依据：[libxml2 HTML API](https://gnome.pages.gitlab.gnome.org/libxml2/html/HTMLparser_8h.html)、[2.9.13 版本源码](https://github.com/GNOME/libxml2/blob/v2.9.13/HTMLparser.c)。当前 Xcode 16.2 / iPhoneSimulator18.2 SDK 头文件声明 libxml2 2.9.13；SDK行号、官方正文和失败抓取保存在证据目录。最新 API 文档不是当前系统运行库的版本证明，实际原生行为以测试为准。
