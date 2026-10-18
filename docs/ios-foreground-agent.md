# iOS 前台只读 Agent 验收宿主

第 291 阶段第九片完成第 7 点原定实现基线：共享协议/codec 已固化，原生 workspace、HTTP、固定页面浏览器与真实前台 Agent loop 已联合。shared Agent v5、HTTP/Browser v1、Android Room v44、发布版本 `0.1.22 (23)` 保持。该基线不承诺 iOS 与 Android 能力对齐。

## 宿主与模型

`shared` 导出静态 `XiaoLingShared.framework`，支持 iOS arm64 和 Simulator arm64 编译。`ios-host/ForegroundHost.swift` 是单窗口 UIKit Simulator 验收 App，内部版本 `0.0.1 (1)`、bundle ID `com.longdev.xiaoling.iosforegroundhost`；不是完整 iOS 产品，也没有 Room、持久 Run、会话恢复或用户配置管理。Framework 使用普通平台类，未增加 expect/actual factory。

模型 adapter 使用普通非流式 Chat Completions，每轮严格 JSON 单步规划；工具目录仅有 `workspace.list/read_file`、`browser.list_pages/read_page`。模型只能选择宿主固定 page_id，不能提交 URL、认证头、写文件、shell、网页 action 或后台调用。JSON 参数仍由只读 Executor 最终校验。

每轮最多 8 个规划步骤、总时限 90 秒；单次 HTTP 总时限沿 HTTP 端口。模型输入最多 64 KiB，响应最多 128 KiB，content 最多 16,384 字符，总结最多 2000 字符。拒绝多 choice、非 stop、原生 tool/function_calls、重复字段/坏转义/多余字段或未知工具；错误固定为 RESPONSE_INVALID、INPUT_LIMIT、TRANSPORT、HTTP_STATUS_数字或宿主终态原因，不回显 Provider 正文/cause。

每个 Run 新建 HTTP/browser/files/history。Executor 完成实际执行并复核 owner 后记录完整结果 codec 帧；LLM 的 completedCalls 必须与历史逐项精确匹配。调用 ID 由宿主生成，防重复、复制和后续参数变化；只读历史拒绝 verified、typed verification、executionReceipt 或 browserAction。空历史 complete 拒绝，但非空历史是否充分回答任意用户目标仍是模型质量边界。界面“已完成”只表示 loop 正常结束，不构成业务 PASSED/COMMITTED。

认证值只来自启动进程内存，宿主通过 Authorization header 使用；不进 prompt、模型工具参数、日志或 App 包。adapter 对输入及两层 JSON 解码后的字段检查凭据反射，事件在字段序列化前脱敏，覆盖 Unicode、引号和反斜杠转义。公开文件/页面内容始终视为数据，模型提示词声明其不能作为指令；这不保证模型完全抵抗 prompt injection。

## 前台许可与取消

`IosForegroundAgentHost` 的初始化/start/cancel/close 和模型请求前检查在 Main，绑定真实 UIApplication active；后台 Executor 只读 NSLock 下的 owner 缓存，不从后台线程读取 UIKit。只允许一个当前 Run。STARTED 回调内取消的 lazy job，以及首次回调异常，均通过完成钩子释放槽位。

UIKit willResignActive/didEnterBackground、用户取消或 host close 先撤销 owner，再取消 job、关闭真实 HTTP/browser/files。Executor 发布结果、模型等待返回和最终总结前均再次检查；终态冻结，迟到回调不能改写状态。已终态 Run 不会被之后的前台撤销覆盖。

重新激活只更新 active 状态，不自动续跑；下一次必须显式点击“运行”，新 Run 空历史起步。后台撤销和本机请求取消不证明 Provider 远端已停止计算、未计费或 POST 未提交。没有 iOS 后台 Agent、LMK/进程恢复、自然系统长期运行或完整产品入口验收。

## 可复现的验收入口

在 macOS/Xcode 环境构建：

```zsh
python3 tools/build_ios_foreground_host.py
python3 tools/launch_ios_foreground_host.py --device EADF8DFC-ED26-4C03-8735-C0889ECB2DA5
```

构建脚本不读取凭据；显式 Simulator SDK、静态缓存 dead_strip、本地 ad-hoc 签名，输出 `outputs/ios-foreground-host/XiaoLingForegroundHost.app`。启动脚本核对指定 iPhone 16 Pro，安装 App，进程内读取被忽略的本机 AGENTS 测试配置并以 SIMCTL_CHILD 环境注入；不写凭据文件/argv，也不自动 start。默认仅创建 UUID 私有临时工作区的 `验收.txt`，内容带公开随机 marker；页面固定为 `https://example.com/`，不接触正式用户文件。

Simulator 可见界面点击“运行/取消/Home”产生实际 UIKit 生命周期。App Documents 的 `foreground-events.jsonl` 仅保存公开夹具实际结果、序号、Run ID、工具名、model_requests、终态和 AppDelegate 生命周期；这是验收专用审计，不是正式持久 Run。可用 `xcrun simctl get_app_container` 回读文件，再独立执行：

```zsh
python3 tools/verify_ios_foreground_host.py --events <事件文件> --marker <公开标记> --output <结果文件>
```

该审计要求：显式 active 点击/空历史、新旧调用 ID 不复用、完整帧只读字段、浏览器调用绑定和正文 SHA-256、至少两轮实际文件/页面总结、模型等待期间取消与前台撤销、终态后零事件、重新激活至下一次显式点击间零 Run 事件。事件核对与可见 UI 联合使用，不把注入传输或人为通知当成真实 UIKit 证据。

## 本轮证据与独立待办

指定 iPhone 16 Pro / iOS 18.3，Xcode 16.2 (16C5032a)、编译 SDK iPhoneSimulator18.2。真实 `gpt-6-luna` 通过生产 IosHttpClient 公网 HTTPS 调用；example 正文通过独立 NSURLSession/libxml2 提取。四个 Run 分别为 COMPLETED、USER_CANCELLED、FOREGROUND_REVOKED、COMPLETED，两个成功总结均准确引用随机文件标记和 Example Domain。取消/失前台发生在下一次模型请求等待期间，重新激活没有自动续跑。

三端与 Android 定向验证、文档语料/数据保护、凭据/制品审计结果以[验证报告](verification-report.md)为准，证据目录 `outputs/verification/stage291-ios-foreground/`。完整原始公开事件、截图、失败/最终构建与 `ui-acceptance.json` 保留。普通公网 TLS 成功不等于坏证书/mTLS 专项或任意 URL/DNS pinning 安全验收。

第 7 点按原退出标准收口实现基线；iOS 正式产品、持久 Run/恢复、Provider 流式/SSE、多窗口、MCP/Remote 平台能力、任意 URL 实际连接 IP 门禁、自然 LMK/后台系统及正式 Release 分别作为独立待办。前六点的长期部署/质量待办仍独立追踪，不追加到本阶段。完整矩阵见[共享协议能力矩阵](kmp-readable-evidence.md)，阶段边界见[路线图](personal-agent-roadmap.md)。

依据：[Kotlin/Native framework](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) 与当前 UIKit SDK 声明；正文和 SDK 摘录在本片证据目录。实际接口兼容以本轮 Swift/Kotlin 编译与 Simulator 运行为准。
