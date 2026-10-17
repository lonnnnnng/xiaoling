# Remote Channel 本地持久发送队列

更新日期：2026-10-17（北京时间）。第 285 阶段第六片，开发版本保持 `0.1.22 (23)`，Room v40；本片未发布。

## 实现范围

`tools/remote_channel_sender.py` 实现仓库外的本地 SQLite 发送队列，Python 3.12+，仅使用标准库和 PATH 中的 OpenSSL。发送方用 P-256 / SHA-256 独立生成单条签名、v1 外层批次和签名 ACK 响应，与生产 Kotlin codec/Gateway 互通。批次字节格式沿用[批次协议](remote-channel-batch-v1.md)，未修改 Android 生产代码、前台确认流程或后台许可。

该工具不监听网络，不提供 HTTPS URL、接收端注册 UI、部署脚本或密码学接收端认证。调用者权限来自操作系统对状态目录的管理权限；`receiverId` 只用于路由。任何 HTTP 适配层都必须先完成独立的接收端认证和授权，不能把现有 `pull / ack` 直接暴露到公网，也不能把批次签名理解成接收端身份认证。

第七片在独立 `remote_channel_receiver_auth.py` 增加[接收端签名认证与本地授权](remote-channel-receiver-auth.md)，与原队列共事务；本工具仍为本地管理入口，不得作为网络认证 fallback。第八片补[产品公钥创建/导出与认证 HTTPS 客户端](remote-channel-receiver-https.md)，服务端 HTTP listener 与稳定部署仍待后续；第六片以下证据保持其原验收范围。

## 状态、密钥与持久化

初始化必须指定尚不存在的仓库外目录，目录属主为当前用户，权限为 `0700`；`sender-private.pem / sender-public.der / queue.sqlite3` 为 `0600`。拒绝根状态目录 symlink、状态文件 symlink/hardlink 及 group/other 权限。初始化不覆盖已有目录；失败留下的材料也不自动重置。

每个新 CLI 进程重新检查文件权限、schemaVersion/user_version、SQLite quick_check、私钥导出的 SPKI、公钥文件和 metadata 指纹。缺文件、损坏、错误版本或换钥均拒绝操作，不创建空库，不生成替代密钥。`init / public-info` 只输出身份、公钥和 SHA-256 指纹；私钥永不进入响应。

SQLite 用 `mode=rw`、`autocommit=LEGACY_TRANSACTION_CONTROL, isolation_level=None`，显式执行 `BEGIN IMMEDIATE / COMMIT / ROLLBACK`；配置 foreign keys、`journal_mode=DELETE`、`synchronous=EXTRA` 和 `fullfsync=ON`。多个本地进程串行选择投递与提交 ACK；所有响应必须等事务提交成功。签名或 COMMIT 失败不推进游标。

发送目录的数据库保存明文 pending 正文及签名快照，文件权限只限制本机访问，不提供 Keystore/静态加密。ACK 后把正文、conversation 和 batch_json 设为 NULL 是逻辑清除；不保证 SQLite 页面、日志、磁盘或备份的物理擦除。主机操作者需保护整个状态目录及其备份；不能把手机的 Keystore 加密边界套用到主机。

当前 schema v1 没有自动迁移、换钥、删除或清理命令。恢复已有身份需要原私钥、公钥及数据库一起保留；不能只重建数据库继续旧队列。未验收断电、磁盘故障、备份回滚或跨主机迁移。

## 队列与收据规则

| 状态/操作 | 持久效果 |
| --- | --- |
| register | 登记 32 位小写 hex receiverId，重复登记保留原游标 |
| enqueue | 全发送方唯一 messageId，保存 receiver、正文摘要、nonce 和 FIFO 序号 |
| 相同 ID 重入队 | receiver 和规范化正文/conversation 摘要一致时返回原状态，包括 ACK 后的 tombstone；冲突拒绝 |
| pull | 每个 receiver 最多一个在途逻辑投递，提交批次快照及精确收据后才交付 |
| 重复 pull | 同 requestCursor 在有效期内返回已缓存的同一批次；后来入队的消息不插入已签发快照 |
| 到期或明显时钟回拨 | 更新时间、签名与 batchId，保留 messageId、nonce、逻辑 nextCursor 和旧收据 |
| 首次 ACK | 精确核对 identity、receiver、batchId、digest、cursor、messageIds 顺序和原批次签名；同事务推进游标、清在途及逻辑删除正文 |
| ACK 响应丢失 | 重试同一请求返回原签名响应；旧 ACK 不修改后来投递 |
| 已被另一收据确认的投递 | 旧收据仍能取得属于自身 batchId/digest 的签名确认，不再次推进游标 |

非空批次按 receiver 的 FIFO 截取，最多 8 条、UTF-8 JSON 最多 64 KiB。首个空批次提供非空 nextCursor；已有游标后的空批次保持该游标，仍需 ACK。pull 传入的 cursor 必须与对应在途 requestCursor 或已确认游标匹配，不能用未 ACK 的 nextCursor 跳过消息。

正文统一 CRLF/CR 为 LF 并去除首尾空白，最多 20000 个 UTF-16 code units。入队按最长本机游标、时间和签名预留单条完整报文空间；控制字符 JSON 转义可能使较短正文仍超 64 KiB，此时拒绝入队，避免永久卡在 FIFO 首部。批次 TTL 为 60 秒；不修改主机或手机时间。旧 ACK 可以确认已接收的过期批次，过期不等于队列删除或已执行。

全局上限为 32 个接收队列、1024 个 messageId 和 4096 个已签发收据。messageId 上限包含已 ACK tombstone；重签、空批次也消耗收据容量。满容量拒绝新增，不淘汰旧身份或收据；已存在的有效 ACK 仍可完成。在途有效快照可重复读取，满收据后无法重签或建立新投递。该策略适用于本地有界验证，长期运营的安全保留/压缩机制尚未实现。

## CLI 使用

以下命令在仓库根目录运行，身份、正文与 receiver 都是合成示例；状态路径必须为尚不存在的仓库外目录。`queue_directory` 是本轮队列路径，不修改系统环境变量。

```zsh
queue_directory="$HOME/xiaoling-sender-example-20261017"
python3 tools/remote_channel_sender.py --state-dir "$queue_directory" init \
  --channel-id example --sender-id local-sender --key-id local-key-1
python3 tools/remote_channel_sender.py --state-dir "$queue_directory" public-info

python3 tools/remote_channel_sender.py --state-dir "$queue_directory" register \
  --receiver-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf '%s' '合成正文' | python3 tools/remote_channel_sender.py --state-dir "$queue_directory" enqueue \
  --receiver-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --message-id example-1 --conversation-key example-conversation
python3 tools/remote_channel_sender.py --state-dir "$queue_directory" pull \
  --receiver-id aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
python3 tools/remote_channel_sender.py --state-dir "$queue_directory" stats
```

第一次 pull 不传 `--cursor`；后续已 ACK 时传上次确认的 nextCursor，丢失 GET 响应时传原 requestCursor。`pull` 输出完整签名批次；`ack` 从标准输入读取由生产收件端 outbox 生成的完整 ACK JSON：

```zsh
python3 tools/remote_channel_sender.py --state-dir "$queue_directory" ack < /absolute/path/receiver-ack.json
```

不能在常规使用中由发送方自己拼 ACK 冒充接收端入箱。本片隔离测试从 Kotlin 生产收件快照导出请求再交给 CLI。示例 receiver 不能代替手机实际身份；第八片应使用配对页导出的公开清单，经管理员独立核对指纹后由认证 CLI authorize，稳定网络服务部署仍待后续。

成功输出单个 JSON，退出码 0；错误只输出固定 `sender error: <CODE>`，退出码 2（BrokenPipe 为 1），不打印正文或底层异常。主要错误：

| 错误码 | 处理边界 |
| --- | --- |
| PYTHON_3_12_REQUIRED | 使用 Python 3.12+，旧运行时在初始化前拒绝 |
| STATE_ALREADY_EXISTS / INVALID_STATE_PATH / STATE_MUST_BE_OUTSIDE_REPOSITORY | 不覆盖旧目录，不改用仓库内状态或链接 |
| INVALID_STATE_PERMISSIONS / STATE_UNAVAILABLE | 权限、密钥或账本不可用，保留现场；不重置游标或换钥 |
| UNKNOWN_RECEIVER | 本地管理员先登记正确接收路由 |
| MESSAGE_ID_CONFLICT | 同一 ID 不能换 receiver、conversation 或规范化正文 |
| ACK_REQUIRED / CURSOR_MISMATCH / ACK_MISMATCH | 补发精确 outbox 请求，不跳过在途投递或人工推进游标 |
| RECEIVER_CAPACITY / MESSAGE_CAPACITY / RECEIPT_CAPACITY | 满额保留记录，不自动腾空间 |
| INVALID_TEXT / MESSAGE_TOO_LARGE / INVALID_REQUEST / INVALID_CLOCK | 修正输入；不通过删账本或改系统时间规避 |
| SIGNING_UNAVAILABLE | 签名失败不提交状态，恢复本机 OpenSSL/密钥条件后重试 |

## 验证证据

本机为 Python `3.14.8`、SQLite `3.54.0`、OpenSSL `4.0.3`。详细命令、最终结果与证据路径见[验证报告](verification-report.md)顶部。

- Python 19 项：仓库外临时状态、合成正文和密钥；验证并发、GET/ACK 丢响应、旧 ACK、重签/回拨、FIFO/限额、损坏/权限/换钥、签名和 COMMIT 注入失败，以及未提交事务中独立进程 `os._exit(9)` 后恢复。后者是进程退出后的 SQLite 事务恢复证据，不是断电验证。
- JVM 90 项聚焦回归中包含一个独立 Python/OpenSSL ↔ 生产 Kotlin 门禁；覆盖 Unicode/CRLF、重复 GET/ACK、空批次及旧 ACK 不影响新投递。Gradle Test task 把 Python 发送脚本声明为输入，脚本变化后不能沿用 UP-TO-DATE 结果。
- Redmi `wsvwypiz7xwslvl7 / begonia`、SDK 36：两轮独立 instrumentation 分别 `OK (1 test)`，PID `13846 → 13885`。seed 经生产 Gateway 验签并写真实 Keystore 密文/outbox；独立主机 CLI 接收生产 ACK 并幂等回复；restore 在新手机进程读回 outbox、验签响应、清待 ACK，同时保留两条 pending 正文。成功后仅移除本轮 UUID preferences/AES alias，并断言删除成功、重读为空及别名不存在；失败保留现场。额外 shell 文件检查被设备 SELinux 拒绝，未调整系统安全设置。
- 手机与主机仅通过 instrumentation 参数传合成批次/ACK，`https://sender-queue.example/batch` 只作来源 hash 绑定，没有 DNS、HTTP、TLS 或模型 API。主机测试目录/私钥留在仓库外，审计输出只有公钥与合成记录。

自然 15 分钟周期、Doze、LMK、系统重启和厂商后台策略均未重新验收；本片不能宣称稳定生产发送方或第 285 阶段整体完成。认证核心与客户端现已分别由第七/八片补齐，后续仍需稳定 HTTPS 服务适配与部署，并独立验收长期保留和自然恢复。

## 官方正文依据

以下正文通过本机 smart-search 抓取，访问日期 2026-10-17，保存于忽略的 `outputs/verification/stage285-sender-queue/`：

- [Python sqlite3](https://docs.python.org/3/library/sqlite3.html)：`isolation_level=None` 允许显式 SQL 控制事务；Connection 上下文管理器不关闭连接，因此测试同时显式释放连接。
- [SQLite PRAGMA](https://sqlite.org/pragma.html)：DELETE 日志模式下 `synchronous=EXTRA` 包含 unlink 后目录同步；使用配置不能替代实际断电验收。
- [OpenSSL dgst](https://docs.openssl.org/4.0/man1/openssl-dgst/)：`-sha256 -sign` 与 `-verify / -signature / -keyform` 的独立签名验证接口。
- [Gradle incremental build](https://docs.gradle.org/current/userguide/incremental_build.html)：通过 runtime inputs API 追加文件输入，并以 `withPropertyName / withPathSensitivity` 描述其属性。
