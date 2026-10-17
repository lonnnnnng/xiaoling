# Remote Channel 接收公钥登记与认证 HTTPS 客户端

更新日期：2026-10-17（北京时间）。第 285 阶段第八片，开发版本保持 `0.1.22 (23)`、Room v40；未发布。

## 产品入口与许可

设置 → 远端身份配对 → 为已核对的发送公钥登记 HTTPS 地址 → 创建接收公钥。点击创建先展示确认对话框；确认后先停用原后台收件，再持久写入必须认证的标记，随后才生成该 receiver 的 Keystore P-256 身份。创建不访问发送方、不授予服务端许可，也不启用后台或通知。

创建完成后展示接收公钥 SHA-256，用户通过系统文件选择器导出 `xiaoling-receiver-<receiverId>.json`。清单仍为[认证协议](remote-channel-receiver-auth.md)的十字段公开数据，不含私钥、消息正文、API key 或用户执行许可。发送方管理员必须另行通过可信渠道核对指纹并运行本地 `authorize`，手机客户端不能代替这一步。

导出只读取原 Keystore 身份。待导出的公开清单用 `rememberSaveable` 保存；页面恢复后、文件选择返回时重新读取当前配对/来源和原公钥，再精确比较全部绑定后写入 UTF-8 JSON。取消选择不会创建密钥或发起网络请求；原登记失效、缺钥或写文件失败不会报告成功。系统文件选择器由用户选定目标，不申请额外存储权限。

首次创建在密钥生成前中断时，页面提供“继续创建接收公钥”，仍须显式确认；如果 alias 已存在则拒绝覆盖，应导出原公钥完成登记。已完成登记的身份缺钥或换钥时拒绝创建、导出和同步，必须使用新的发送方 keyId 重新配对。没有自动换钥、清账本或裸请求恢复。

## 来源与后台持久绑定

来源账本写 schema v2，严格读取 v1 的五字段来源为兼容模式；v2 每条来源增加 `receiverAuthRequired / receiverAuthorization / registered`，字段/类型、公钥、来源指纹和 endpoint 摘要均严格校验。公钥登记与 URL 仍在原 SharedPreferences，正文、cursor 和 outbox 仍在独立 AES-GCM 快照；没有 Room schema 变化。

`receiverAuthRequired` 在生成密钥前同步 commit。生成或登记中断后保持 true，缺清单时同步冻结；来源写盘失败冻结当前 registry，重建后也必须重新核对原磁盘标记。只有显式导出已存在的原公钥才能修复已有密钥的登记。已登记公钥不能替换。

认证来源固定原 HTTPS URL 与发送方公钥指纹。同一个 keyId 不能改地址或公钥；使用新的 keyId 重新配对。移除来源只把认证记录设为 `registered=false`，停止网络使用并保留公开绑定和认证标记；重新登记原地址恢复原身份，不降级。撤销配对也不会删除 Keystore 私钥、原 cursor/outbox 或认证标记。记录继续计入最多 32 个来源、96 KiB 容量，无自动淘汰，未提供生产身份迁移/清理入口。

后台许可写 schema v2，并兼容 v1。新字段 `receiverBindingSha256` 为认证清单的 SHA-256，兼容模式为 null。启用、启动/页面对账及 Worker 建连前都核对地址和认证绑定；旧裸请求许可不能继承新认证。创建确认先持久停用旧许可，认证完成后用户另行启用新代次。Worker 仍只调用 Application 的同一 `RemoteChannelPullService.synchronize`。

## HTTP 客户端 wire

兼容来源继续使用既有 GET / 同址裸 ACK POST，允许原单条或签名批次。认证来源的 pull 和 ACK 都使用**用户登记的同一 HTTPS URL**：

- method 为 POST，无 query、重定向、Cookie 或代理；沿用公网 DNS、TLS、20 秒总超时、HTTP 200、JSON MIME、解压后 64 KiB 限制。
- `X-Xiaoling-Receiver-Auth: 1`、`Accept: application/json`、`Cache-Control: no-store`，正文 MIME 为 `application/json`。
- 正文恰为 `{"request": {...}, "proof": {...}}`；request/proof 字段和 canonical 字节完全沿用认证核心。receiver/cursor 只在已签逻辑请求内，不另发兼容协议的路由/游标头。
- pull request 来自当前加密 batchState；ACK request 来自 durable outbox，包含原有顺序的 messageIds。请求不包含正文。
- 每次调用或 ACK 恢复以当前时间和新 nonce 重签；有效期 60 秒。重建不会复用旧 proof，也不改写同一 receipt。
- 认证 pull 仅接受原签名批次；拒绝九字段单条响应。4xx、验签、缺钥、登记失败或协议拒绝都停止，不尝试 GET、裸 ACK 或新的网络地址。

`pull()` 旧单条 API 对必须认证的来源直接拒绝；生产配对页和后台统一使用 `synchronize()`。有待 ACK 时一次调用只补同一 ACK，成功后结束，不另拉批次。正常调用最多一个 pull POST 和一个 ACK POST。

来源、启用配对、后台许可及原接收公钥在建连前、网络返回后和 ACK 清除前复核。批次提交及 ACK 清除还在收件箱锁内复核许可/来源/公钥和配对；排队等待锁期间的撤销不能用锁外旧检查继续清 outbox。已经到达服务端的合法请求不能被客户端撤回，服务端已提交 ACK 与本地许可失效须分别审计；迟到响应不清本地 receipt。ACK 继续仅表示安全入箱，不表示用户批准或 Agent 执行。

## 服务端适配仍待下一片

本片实现客户端接线，**没有 HTTP listener、反向代理配置或部署服务**。发送端仍由本地 `AuthenticatedSender.execute(raw, trustedEndpointHash)` 验证；现有 `remote_channel_https_fixture.py` 不能作为新的认证服务使用，旧 sender CLI 也不能充当未认证网络 fallback。

未来服务端必须从可信配置取得完整登记地址摘要，精确匹配 method/path、拒绝 query 和额外 wire 路由字段，按收到的 POST body 严格解析结构化 request/proof；不能相信请求自报 endpoint 摘要，不能给裸 GET/ACK 旁路。还需 TLS、限流、错误到 HTTP 状态映射、服务进程恢复、稳定部署和真实公网互通验收。认证核心的 4096 nonce 上限、32 receiver 上限、主机明文 pending、无长期回收策略保持不变。

## 验证边界与官方依据

聚焦 JVM 覆盖兼容 schema、显式创建中断、登记 commit 失败、移除/重新登记不降级、缺钥/签名器缺失、旧后台许可失效，以及生产 OkHttp 请求交给独立 Python/OpenSSL 的 pull/ACK、丢响应恢复和撤销拒绝。Android 门禁使用独立 UUID preferences/Keystore/缓存文件；合成正文只进入隔离待确认箱。

Redmi 测试通过 OkHttp 拦截器与主机离线交换，验证真实 Keystore 和生产服务，不进行 DNS/socket/TLS。文件导出门禁通过测试 ActivityResultRegistry 模拟文件选择及保存状态恢复，再由生产回调写独立缓存文件；不证明所有 DocumentsProvider 行为或真实系统文件选择器操作。完整结果及失败记录见[验证报告](verification-report.md)。自然周期、Doze、LMK、系统重启、稳定公网部署及正式 Release 后置。

官方正文访问于 2026-10-17，经 smart-search 保存到忽略审计目录：

- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：用户通过系统选择器指定创建目标，框架不需要额外系统权限；ACTION_CREATE_DOCUMENT 不覆盖已存在同名文件。
- [Activity Result API](https://developer.android.com/training/basics/intents/result)：Activity 可在等待结果期间重建，需要另存处理结果所需状态；测试可提供 ActivityResultRegistry 并用 onLaunch/dispatchResult 交付受控结果。
