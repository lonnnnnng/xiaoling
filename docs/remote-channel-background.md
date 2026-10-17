# Remote Channel 后台收件与送达通知

第 285 阶段第三片的开发源码在既有[签名批次协议](remote-channel-batch-v1.md)上增加后台调度，正式 Release 保持 `v0.1.22`。远端消息只进入加密待确认箱；通知点击只打开列表，打开消息后仍需现有会话确认，不创建 Agent Run，不调用模型或授予工具权限。

## 用户入口与许可

设置 → 远端身份配对中，每个已启用 P-256 身份和已登记 HTTPS 地址有独立的“后台收件”开关，默认关闭。“允许验签”不等于同意后台访问。启用时生成新的 UUID 代次，持久绑定 `channelId / senderId / keyId / 公钥指纹` 的来源身份及 HTTPS URL SHA-256；系统任务只携带来源身份与代次，不携带正文、cursor、receipt 或响应提供的地址。

后台许可 schema v1 严格校验字段、布尔/整数类型、UUID、SHA-256 和重复身份，最多保留 32 个来源摘要、64 KiB。许可缺失、停用、代次变化、配对失效、地址变更或存储损坏均在建连前拒绝；写盘失败冻结当前进程，不能继续信任旧的启用状态。旧 Worker 的失效撤销只作用于自己的代次，不撤销重新启用的新许可或取消其新通知。正文与 outbox 仍以原 Keystore 加密快照为唯一权威，后台账本不复制它们。

停用先持久撤销许可，再取消该来源唯一任务与通知。旧响应在网络返回、整批提交和 ACK 清除前复核后台许可，取消不记网络故障；已经提交的正文/cursor/outbox 保留。停用验签、撤销配对或修改/移除来源也取消后台任务，新地址或重新启用的身份需要重新启用后台收件。撤销公钥优先于取消任务的成功，取消失败不能令已撤销身份继续验签。

## 有界任务与恢复

使用当前项目的 WorkManager `2.11.2`，每个来源一个唯一 `PeriodicWorkRequest`，间隔 15 分钟且需要 `NetworkType.CONNECTED`，由系统决定实际执行时间。默认 Worker 工厂通过标准两参数构造器恢复任务；不增加常驻服务、不申请精确闹钟、不使用后台 Agent Runtime。

每次 Worker 只调用一次既有 `synchronize`：正常至多 GET + POST，已有 outbox 时只补一次 POST。每个 HTTP 调用仍有 20 秒总超时、JSON MIME、64 KiB 解压限制、P-256 验签与公网 DNS 限制；没有无限循环、分页追赶或持续连接。

网络/超时/5xx 和尚未结束的持久退避可返回系统重试，WorkManager 指数退避从 30 秒开始，每个周期最多 3 次重试（加初次共 4 次尝试）。既有 checkpoint 在建连前再次阻断过早请求，不能用系统调度绕过最多 1 小时的持久退避。4xx、格式/签名/身份拒绝和存储错误不进行该周期的网络重试，receipt 不丢弃。约束丢失或 Worker 停止会取消 HTTPS 协程；上一次已提交 outbox 在后续任务优先补发。

许可提交与 WorkManager 入队属于不同存储，配对页重建时进行有界对账：同一来源/地址/代次的有效任务保留，缺失任务补入队，失效或停用任务取消。对账不生成新代次、不替新 URL 授权。WorkManager 自身保留已入队任务；若在许可落盘后、系统入队前中断，这个间隙需要下一次打开配对页修复。应用不会用 force-stop 后的普通后台恢复作为保证，系统对周期工作也不提供准时送达承诺。

## 独立通知

“送达通知”默认关闭，只在后台收件已启用时可单独打开。前台请求 Android 13+ 的 `POST_NOTIFICATIONS`，后台不弹权限框；应用或通知渠道被禁用时只保留消息，通知未发出就不记录为已通知，用户之后允许时可补提示。独立渠道 `remote_channel_inbox` 的打扰级别由系统和用户控制。

通知只使用固定标题与说明，不展示正文、来源文本或任务参数，锁屏可见性为 PRIVATE。摘要去重使用当前已入箱消息身份的 SHA-256；重复批次和 ACK 补发不重复提示，进程在入箱后、通知前中断可在下次任务补提示。通知与停用共用本地许可锁，停用后取消的提示不能由迟到 Worker 再发出。

点击使用显式、immutable、one-shot PendingIntent。MainActivity 导出以支持系统分享，因此 action/extra 本身不能作为信任凭据；只有私有偏好文件里 24 小时内的随机 UUID 导航令牌同步消费成功才打开列表。令牌至多 32 条，未知、过期、重复、类型损坏及 URI 授权/ClipData/selector/type Intent 拒绝。令牌不包含正文，导航不打开单条草稿，更不执行远端任务。复用已打开的 Activity 时，收件页重新读取快照与后台状态。

## 依据与验证边界

已抓取 Android 官方正文（2026-10-17）：[定义工作](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[管理与取消工作](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)、[通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)、[WorkManager 集成测试](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)。正文确认周期执行受约束/系统优化影响、约束失效停止 Worker、`Result.retry` 与退避、用户拒绝通知权限时阻断通知。

JVM 覆盖许可、代次、损坏/写失败、调度失败回滚、对账、取消、通知去重和退避边界。Redmi 测试使用 WorkManager TestDriver 控制网络条件与周期，并由独立 Python/OpenSSL 公网夹具验证 Worker 的真实 DNS/TLS/批次/ACK；它证明受控调度下的实际 Worker 执行，不证明自然 15 分钟周期、Doze、自然 LMK、系统重启、厂商后台策略或稳定生产发送方。通知/令牌测试与公网合成消息分开记录，实际结果见[验证报告](verification-report.md)。正文过期的独立审计及上述自然恢复证据留待后续切片。
