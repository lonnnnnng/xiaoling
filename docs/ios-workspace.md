# iOS 本地 workspace 平台实现

第 291 阶段第六片，继续第 7 点。新增 `iosMain` 原生文件实现与共享只读 Executor，接到现有 shared Agent loop；没有 Swift App、framework 导出或 expect/actual factory。shared contract 保持 v5，文件策略 v1，Room v44、发布版本 `0.1.22 (23)` 保持。下一片推进 iOS HTTP client，随后只读浏览器与真实前台 loop。

## 平台能力与宿主责任

`SharedWorkspaceFiles` 提供同步 `list / readText / close`，与已有终端命令端口 `SharedWorkspaceRuntime` 分开。`IosLocalWorkspace` 是普通平台实现类，由宿主注入；不提供 iOS shell、终端执行或 Android 命令能力。

宿主须先创建并选定可信 App 私有根目录；模型不能指定根目录。初始化只对该绝对根路径执行一次 `open`，用 `O_DIRECTORY / O_NOFOLLOW / O_CLOEXEC` 固定句柄，根本身为符号链接时拒绝。系统祖先目录可能含合法链接，本片没有逐段验证根的祖先。根句柄和后续相对路径解析才是文件能力边界，不抵御任意同 UID 写入者对可信目录的恶意更改。

后续父路径逐段 `openat` 并要求目录与 `NOFOLLOW`，读叶子也禁止跟随链接。每次 list 独立打开目录描述，交给 `fdopendir / closedir`，不复用根描述的枚举游标。当前实例通过 `NSLock` 串行操作及 close；关闭幂等，关闭后拒绝读取和创建。

## 路径、容量与文件事实

- 只接受相对路径，拒绝绝对路径、反斜线、控制字符、空段、`.`/`..` 子段和 `.xiaoling-stage-` 保留前缀。list 的空白路径或单独 `.` 表示根；普通文件名首尾空格保留，不 trim。
- 完整相对路径最多 512 UTF-16 code units，各文件名最多 255 UTF-8 bytes；孤立 surrogate 拒绝，中文和 emoji 保留。
- 单文件最多 512 KiB，严格 UTF-8 解码。只读普通单硬链接文件；符号链接、FIFO、目录文件读取及多硬链接普通文件拒绝。
- 默认预览 20,000、最大 100,000 UTF-16 code units，截断不切开 emoji 代理对。结果同时返回原文件 `sizeBytes` 和 `truncated`，不把预览长度当原始字节数。
- list 仅枚举一级，最多扫描 256 项，返回相对路径合计最多 64 KiB UTF-8。暂存项隐藏但仍消耗扫描预算；发现不安全条目或超限即拒绝整次 list，不静默丢弃普通条目。目录排前，随后按路径排序。

读取先 `fstat` 限制类型、链接数和容量，按已知长度读取，再额外探测一字节增长并复核 inode/尺寸/mtime。可检测的变化返回 `FILE_CHANGED`，不发布半段正文。这不是一致快照：另一宿主实例或同 UID 写入者若等长改写并恢复 mtime，可能通过；实例锁不锁住其他写入者。

平台访问失败使用固定 `SharedWorkspaceAccessException` 分类，不带宿主绝对路径、文件正文或系统 cause。参数校验失败由共享 Executor 转为 `INVALID_WORKSPACE_ARGUMENTS`。

## 宿主创建接口

`IosLocalWorkspace.createText` 仅供宿主创建新 UTF-8 文件，不进入模型工具目录；父目录须已存在。先以 0600 权限独占创建暂存文件，完整写入并 `fsync`，再通过 `linkat` 发布目标，已有目标永不覆盖，最后删除暂存名。容量与路径沿同一策略。

完整发布与不覆盖不等于失败时无副作用：`linkat` 成功后若暂存删除失败，返回 `STAGING_CLEANUP_FAILED`，目标可能已经创建；调用方不能据此自动重试或假定目标不存在。父目录未 `fsync`，没有承诺突然断电后的目录持久性或自然系统恢复。中断暂存名不可被模型读取。

## 共享 Executor 与前台门禁

`SharedReadOnlyWorkspaceExecutor` 目录仅有 `workspace.list / workspace.read_file`，均 `supportsBackground=false`。参数仅允许 `path`，读取另允许规范正整数 `max_chars`；写入、创建、shell 和未知工具拒绝。只读工具不申请动作审批，宿主文件根与前台 owner 仍是必要的可信能力。

宿主必须把 `isForegroundOwnerActive` 绑定真实当前 Run/会话和前台生命周期。执行前检查 owner，文件访问前检查协程取消；完成后再次核对取消与 owner，撤销或取消后不发布迟到正文。取消传播，不改成普通失败。文件访问是有界同步操作，已开始的原生调用不保证中途立即中断；取消门禁防止后续成功发布。

成功结果的 JSON content 为 entries，或 `path / text / sizeBytes / truncated`；`verified / verification / executionReceipt` 保持 null，不生成 PASSED、COMMITTED、审批许可或自动重放资格。完整结果可以经[Tool result v1 codec](kmp-tool-result.md)往返。本片未接真实 UIKit 生命周期，测试中的恒 true callback 只能证明接口联合路径。

## 已验证范围

| 验证层 | 范围 |
| --- | --- |
| shared common | 10 条新增路径/UTF-8/预览/目录/参数零访问/前台撤销/取消与结果往返门禁；Android Debug/Release 各 85 条通过 |
| iOS Simulator arm64 | 96 条通过，含 11 条原生专项；真实隔离目录创建/读取/list/reopen、不覆盖、链接/硬链接/FIFO、坏 UTF-8、容量、close 与 shared loop 联合 |
| iOS arm64 编译 | 平台文件实现编译通过；不是设备 App 验收 |
| shared loop smoke | 原生文件 → scripted LLM → shared Executor → 完整结果 codec；不是外部真实模型或 UIKit 产品入口 |

Simulator 为 iPhone 16 Pro / iOS 18.3，Xcode 16.2 (16C5032a)，编译 SDK 为 iPhoneSimulator18.2.sdk。通过 `xcrun simctl spawn --standalone` 执行 native test.kexe，没有启动可见 App 窗口。测试使用 `NSTemporaryDirectory()` 下 UUID 夹具，finally 只清自身目录。Android 回归、最终语料/保护与产物审计见[验证报告](verification-report.md)，证据目录 `outputs/verification/stage291-ios-workspace/`。

iOS HTTP/Provider、只读浏览器、真实前台入口、持久运行与真实模型联合尚待后续；Phone Use、Root/Shizuku 和持续后台不承诺对齐。第 7 点尚未整体收口。

官方与 SDK 依据：[Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)、[严格文本解码 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/decode-to-string.html)、[Apple open 手册](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/open.2.html)。当前 Xcode SDK 的 openat/linkat/fstatat/fdopendir 声明及抓取正文保存在本片证据目录。
