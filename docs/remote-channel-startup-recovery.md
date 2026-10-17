# Remote Channel 启动对账恢复

第 285 阶段第五片补齐后台许可与系统入队之间的恢复入口。正式版本保持 `0.1.22 (23)`、Room v40；既有收件箱 v3、后台许可 schema v1 和 Keystore 密钥不变。本片修复应用进程启动后的缺失调度，不作为自然系统回收或生产发送方的完整验收。

## 恢复入口

`XiaoLingApplication.onCreate()` 在应用级 `SupervisorJob + Dispatchers.IO` 作用域启动一轮异步对账，主线程不等待结果。不依赖创建 ViewModel、进入配对页或加载 Agent/Room。启动不直接调用 HTTPS 同步；正常入队后的 Worker 仍按用户已保存的许可和系统约束运行。

`RemoteChannelBackgroundStartupRecovery` 先加载原后台许可账本，再按需要构建 Controller。空账本返回 `NOT_CONFIGURED`，不访问 WorkManager；损坏账本返回 `UNAVAILABLE`，不构建调度器、不清空原文件。存在停用记录时仍对账，以取消可能遗留的旧任务。

Controller 与配对页使用同一个应用级实例和互斥锁；启动对账、前台启用或停用不会在这个实例内并行改写任务。原后台账本最多 32 个来源、64 KiB；启动只遍历一轮，不新增循环重试或常驻服务。

## 许可与调度

| 持久状态 | 启动对账行为 |
| --- | --- |
| 当前配对已启用、来源地址摘要匹配、后台许可已启用 | 按原 identity 和 generation 补缺失任务；已有同代有效任务保留原 Work ID |
| 后台许可已停用 | 取消该来源旧任务与通知，不创建新许可 |
| 配对失效、来源移除或地址摘要变化 | 先持久失效原许可，再取消旧任务与通知；新地址需要前台重新启用 |
| 许可损坏、注册表构造失败、系统查询或入队失败 | 返回 `UNAVAILABLE`；没有成功提交的修改不伪装为已恢复，保留原存储与配对页重试入口 |

成功遍历返回 `RECONCILED`，只说明系统任务对账完成，不表示收件、通知、ACK 或用户决定完成。系统入队失败不会撤销仍有效的原许可，也不会在启动协程中自动重试；再次启动或打开配对页可以重新对账。多来源对账不是跨存储的全局事务，失败前已经完成的来源可能保留其更新。

恢复不改变通知开关、系统通知权限、许可代次、来源地址或公钥，不复制正文、cursor、receipt/outbox，也不授予模型或工具权限。后台 Worker 继续复核来源与代次；原[正文确认和审计](remote-channel-inbox-audit.md)边界保持不变。

## 失败与生命周期

常规异常隔离为 `UNAVAILABLE`，Application 只写固定警告，不输出异常正文、身份、URL 或凭据。协程取消继续传播，不当作成功或可重试的网络错误。配对页仍提供原对账及故障提示，不因为启动失败清空设置。

这轮恢复需要系统实际创建应用进程；不能在应用未运行时自行填补入队间隙，也不保证 force-stop 后普通后台启动。已有 WorkManager 周期任务仍由系统决定执行时间，15 分钟不是准时承诺。本片没有引入精确闹钟、Foreground Service、启动广播或后台 Agent。

## 依据与验证

已抓取 Android 官方[自定义 WorkManager 初始化](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)正文（访问 2026-10-17，页面更新 2026-02-26）：移除默认 initializer、实现 `Configuration.Provider` 后，`WorkManager.getInstance(Context)` 使用应用配置按需初始化，不需要再次调用 `WorkManager.initialize`。本片沿用项目 WorkManager `2.11.2` 及已有 Manifest 配置。

JVM 覆盖空/损坏账本的惰性依赖、构造失败隔离、原代次与通知许可保留、入队失败后显式重试、停用/地址变化取消、协程取消及启动与前台停用交错。

Redmi 门禁分别验证真实 Application 启动协程完成，以及 UUID 偏好文件重建后的系统任务对账。调度检查使用独立 WorkManager 测试数据库，不调用 TestDriver、不满足联网约束，也不发 HTTPS 请求；重复恢复保留原 Work ID。测试在替换 WorkManager 测试委托前等待正式启动对账结束，只取消和删除本轮隔离夹具。

上述证据不等同自然 15 分钟执行、自然 LMK、Doze、系统重启、厂商后台策略、跨进程调度数据库恢复或稳定生产发送方。真实网络与通知的历史证据仍见[后台收件说明](remote-channel-background.md)，本轮命令和结果见[验证报告](verification-report.md)。第 285 阶段整体退出标准继续未满足。
