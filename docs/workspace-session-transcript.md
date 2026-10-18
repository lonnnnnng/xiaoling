# Workspace 会话与命令记录契约

第 290 阶段第一片，第 6 点 Workspace/Browser/GenUI 的 Workspace 基础。发布版本保持 `0.1.22 (23)`，Room 保持 v44。Browser DOM/action、GenUI 版本化组件与显式 action 继续作为本阶段后续切片。

## 身份与存储

- `WorkspaceSessionRecord.sessionId` 为 16 位随机 ID；每次命令生成独立的 32 位 `executionId`。每条命令保存原始 argv、Tool Ledger 的 `toolCallId`、开始/结束时间、输出截断标记与终态。
- owner 精确匹配 `runId / conversationId / userMessageId / processSessionId`。物理应用进程另有随机 `processToken`，不能用会话 ID 代替 owner，也不能跨 Run 继续旧终端。
- 默认文件为 `filesDir/agent-workspace-sessions/transcript.json`，位于 `filesDir/agent-workspace` 外。工作区文件工具按 canonical 路径限制在根内，因此无法写控制文件、临时文件与锁文件。测试使用独立 UUID 路径。
- 每次访问在同进程共享 gate 和文件锁内重读；写入同目录 `.new`，同步文件内容后使用 `Files.move(... ATOMIC_MOVE ...)` 原子替换。不支持原子替换就冻结，不退回普通覆盖。没有完整初始文件但存在 `.new` 时也拒绝执行。已有完整文件旁的中断临时文件不被当作新账本。
- 文件损坏、版本不支持、结构/状态不合法、读写失败都会冻结该账本并撤销活动进程，不清除旧证据，不把损坏内容当空表。此片验收进程重开；未验证断电后的目录项持久性。

## 容量与权限

- 最多同时 4 个 OPEN 会话；历史最多 16 个会话，每个最多 8 条命令；整个 JSON 最大 24 MiB。关闭、过期和中断仍占历史容量，满容量明确拒绝新增，不自动删除或淘汰证据。容量查看、导出与用户显式回收入口后置。
- stdout/stderr 各收集最多 20,000 原始字节；持续读掉其余输出以免管道背压阻塞，记录截断。UTF-8 解码替换字节可能增加重新编码后的长度，读盘校验容许最多原始上限的三倍。
- 保持固定 `pwd/date/true/false/echo/printf/uname` argv 白名单、8 参数/每项 512 字符与目录约束。无常驻 shell，不支持管道、重定向、stdin、环境变量或恢复旧进程。Android 使用固定 `/system/bin` 命令并清空环境。
- execute/open/write/close 都经原 Runtime 逐次审批，均不支持后台；read 为 SAFE。Registry 只向 DIRECT/FOREGROUND Run 暴露工具。此片不把静态前台来源称为新的 Activity 生命周期许可。
- 命令参数最多 30 秒，execute/write 的工具超时为 32 秒，给进程终止和回执留出余量。带 `toolCallId` 的执行在同 owner 下去重，重复调用拒绝重放。

## 状态与竞争

1. 参数和 owner 通过后，先原子保存 RUNNING，再进入启动边界。保存失败不启动命令。同会话已有 RUNNING 时拒绝第二条 write。
2. exitCode 为 0 才记 COMPLETED；非零记 FAILED；超时记 TIMED_OUT，未知退出码保持 null。Registry write 只对 COMPLETED/0 返回 success/verified，execute 使用同一判断；定义强制 EXECUTOR_VERIFIED。
3. 协程取消、close、TTL 过期、Run 切换/结束都撤销活动进程。取消异常继续传播到原 Runtime。Android 在杀进程后可能中断管道读，仅已请求终止时接受该读流异常并保留已收集输出；其他 I/O 故障仍失败。
4. close 和启动共用控制锁。已持久 CANCELLED 的执行不能被迟到成功覆盖；结算可补充已收集字节，但不填成功退出码。Run 生命周期 hook 为同步接口，关闭与原子写入在 hook 返回前完成；磁盘故障仍撤销进程，下一次终端访问拒绝。
5. 有会话的 Run 结束后持久 `runRevoked`，重建执行器仍不能再次打开该 Run。尚无会话的 Run 在同进程共享撤销集合；没有旧终端事实可恢复，跨进程 Run 终态继续由原 Room Runtime 门禁负责。
6. 新物理进程把旧 OPEN 会话收敛为 INTERRUPTED，并把 RUNNING 命令收敛为 INTERRUPTED。已完成的输出和退出码保留，不猜造退出码，不执行旧命令，不继承审批。旧进程已有历史的 Run 不能新开可写会话。
7. `terminal.execute` 也建立一次性会话，执行后关闭但保留记录。`terminal.read` 返回有界历史，owner 正确时底层审计接口可读关闭记录；产品工具仍受当前 Run 生命周期约束。本片没有跨 Run 历史浏览器。

Workspace 原子文件和 Room Tool Ledger 是两份关联证据，靠 Run/toolCallId 联结，不宣称跨存储事务；进程在二者之间中断时，Workspace 保留未知执行事实，Runtime 保留自身已有审计并禁止自动重放。

## 验证与边界

本轮日志位于 `outputs/verification/stage290-workspace/`。最终数量和结果见[验证报告](verification-report.md)。

- JVM：成功/非零、持久重开、owner/调用去重、超时、取消、并发关闭、Run 结束、容量、写盘失败不启动、损坏冻结、TTL/输出限额与真实 Registry/Runtime 审批链。
- Redmi：真实 argv/进程终止、原审批和 Room typed Tool Ledger、目录/owner/损坏边界及既有工作区单项。
- 两个独立 Android PID：先保存真实已完成结果并注入 RUNNING 边界，受控 force-stop 后只收敛未知命令；不是自然 LMK、系统重启或真实遗留 OS 进程验收。
- 独立待办：历史显式回收/导出、断电/自然系统与大规模性能、完整产品界面到真实模型联合、正式 Release。此片不开放 shell/stdio/后台终端，也不进入第 7 点。

官方 API 正文已通过 smart-search 获取：[Files.move](https://developer.android.com/reference/java/nio/file/Files#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...)) 明确 ATOMIC_MOVE 不受支持时抛异常，目标已存在时替换行为依实现；[Process.destroyForcibly](https://developer.android.com/reference/java/lang/Process#destroyForcibly()) 明确进程可能短暂仍存活，因此终止后有界等待并核实退出。实际 Android 原子替换与终止行为以上述 Redmi 单项为准。
