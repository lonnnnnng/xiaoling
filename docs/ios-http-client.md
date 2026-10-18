# iOS 有界 HTTP 宿主端口

第 291 阶段第七片，继续第 7 点。commonMain 新增 HTTP 契约 v1，`iosMain` 的普通平台类 `IosHttpClient` 通过 `SharedHttpClient` 注入宿主。shared Agent contract 保持 v5，Room v44、发布版本 `0.1.22 (23)` 保持。当前仅普通有界 GET/POST；下一片只读浏览器，随后真实前台 loop。

## 宿主目标与交换契约

HTTP 请求不是模型工具参数。宿主创建 `SharedHttpTargetPolicy.https` 登记 1–8 个 HTTPS endpoint URL；策略只保存规范 origin，匹配 scheme/host/port，允许同 origin 的其他路径。host 小写、默认端口规范化，但不改请求路径/query。该策略信任宿主选择，不验证公网 DNS、连接地址或提供浏览器 SSRF 门禁。

首片 URL 只接受最多 8192 个 ASCII 可见字符，域名/IPv4及可选规范十进制端口1–65535；拒绝 userinfo、fragment、反斜线、空白、坏百分号转义、IPv6及未编码 Unicode。Unicode路径须先编码。HTTPS并不意味着目标是公网。清文HTTP只有模块内部 `loopbackTest` 夹具入口，固定127.0.0.1，不是产品配置开关。

| 项目 | 边界 |
| --- | --- |
| 请求 | GET无正文；POST原始ByteArray最多512 KiB |
| 响应正文 | 默认1 MiB，可选1 byte–2 MiB；保留原始字节，不解码或解析业务 |
| 总时限 | 每请求100–60,000 ms，默认30秒 |
| 并发 | 最多4个已登记请求，第5个直接CLIENT_BUSY，不排队 |
| 请求Header | 最多32项，名称1–128字符、值≤4096字符、总计16 KiB |
| 响应Header | 最多64项、同名称/值限额、总计32 KiB；Foundation聚合元数据，不保存原始重复header顺序 |

Header名称为ASCII token，值为可见ASCII；响应额外允许TAB。大小写重复键拒绝；请求禁止Host/Cookie/Content-Length/Connection/Transfer-Encoding/Proxy-Authorization，自定义Authorization由可信宿主显式提供。平台自行管理传输头。

请求/响应在复制ByteArray前检查容量，headers/body防御性复制；toString仅包含状态/方法/数量/字节数，不含URL、query、header值或正文。HTTP状态100–599和原始bytes独立返回，包括普通401/503；没有业务PASSED/COMMITTED、审批或重放许可。带认证challenge的401可因明确拒绝challenge而返回NETWORK，不能等同普通401正文路径。

## 原生session与结算

每个client创建独立ephemeral NSURLSession和串行delegate queue，禁Cookie handling/storage、URLCredentialStorage及URLCache，不使用shared/background session。无条件拒绝同源及跨源redirect，让原3xx作为当前响应；不自动选择Location。session层与task层认证challenge仅ServerTrust交由系统默认信任处理，其余拒绝，不提供客户端证书或隐式凭据。没有TLS信任绕过。

请求总时限由协程覆盖头/正文/回调等待，另设置系统请求超时；Apple的idle timeout会随传输重置，不能单独当总预算。取消传播CancellationException，并撤销Exchange/cancel实际task。NSURL错误转固定TIMEOUT/NETWORK，格式/预算/目标/关闭/并发失败同样固定分类，无原NSError的正文、URL或cause。

NSLock保护task创建、登记与Exchange领取。取消、close、complete、fail只有锁内领取者能结算；晚到回调查不到记录即忽略。close幂等、拒绝复用、撤销仍登记请求并invalidate session。若complete先领取，之后close不把已完成响应改写为失败，即使continuation稍后交付；调用者协程取消仍保留prompt cancellation。宿主须显式close，丢弃对象引用不能替代生命周期结束。

Content-Length提前拒绝超预算响应；未知长度/分块在每次追加前复核剩余空间，用固定ByteArray累计，超限不返回半段正文。四个Exchange正文buffer合计最多8 MiB，但复制、NSData、Foundation解析header/网络内部内存不含在此上限中；本片不承诺进程总内存同样有界。

没有应用层重试循环、自动恢复或重放。NSURLSession底层连接处理不能据此宣称绝无传输重试；取消/超时/close也不证明POST未被服务端处理。请求或响应中的凭据/正文不因这个端口变成可持久公开内容，上层仍须执行自己的脱敏和许可门禁。

## 验收与能力矩阵

common门禁覆盖origin绑定、非法URL、Header注入/重复/限额、body/总时限/响应预算、防御性复制、诊断脱敏和非UTF8原始bytes。iOS专项在当前Simulator进程内创建真实随机端口socket，覆盖GET/POST/status/显式fixture认证头、同源及跨源redirect、Cookie/cache、精确预算与declared/chunked溢出、头前/正文取消、总时限、close、目标拒绝、并发、截断、二进制字节及Basic challenge拒绝。取消在夹具清理前等待服务端recv EOF或ECONNRESET，证明实际socket关闭；其他读取错误不算关闭成功。

| 能力 | Android | iOS |
| --- | --- | --- |
| common HTTP请求/响应及目标契约 | 可编译及common测试；原OkHttp未迁移 | 同一common契约与普通原生NSURLSession平台实现 |
| Provider、MCP HTTP/SSE、Remote传输 | 保留原生产实现和既有权限 | 本片没有这些上层adapter或流式SSE |
| workspace与网络宿主端口 | 原平台路径保持 | 已具备文件与HTTP平台类；未串成真实产品模型链路 |
| Browser reader、UIKit前台owner、Swift App | 原Android产品路径 | 后续推进；本片没有factory/framework/App |

实际计数、Simulator/Xcode/SDK与Android回归、文档语料/保护和产物审计见[验证报告](verification-report.md)，证据目录 `outputs/verification/stage291-ios-http/`。网络专项为真实系统NSURLSession + HTTP loopback，不是NSURLProtocol模拟；它不证明公网DNS/TLS、有效/无效证书或mTLS行为已活体验证。本片没有真实模型、UIKit/产品前台生命周期、后台或正式Release证据，第7点尚未整体收口。

依据为本机Xcode 16.2 / iPhoneSimulator18.2.sdk 的 NSURLSession/NSURLRequest 声明，已保存带行号快照。Apple官方配置参考页抓取只得到导航，旧URLLoadingSystem正文为缺页，两者不作为API证明；当前SDK明确区分ephemeral、Cookie/credential/cache nil、idle/resource timeout与redirect/challenge委派。官方入口：[URLSessionConfiguration](https://developer.apple.com/documentation/foundation/urlsessionconfiguration)。
