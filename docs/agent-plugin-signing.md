# 插件签名、发布者公钥与版本回滚

第 288 阶段第一片，2026-10-18。第 4 点 Skill/插件正在推进，本片只建立声明安装的可信账本，SAFE 前台执行仍待下一片。Room 保持 v44，发布版本仍为 `0.1.22 (23)`。

## 导入与确认

设置 → Agent 插件可粘贴旧 manifest，或签名 envelope。无签名声明仅供既有只读能力预览；签名通过也不运行外部代码、不下载 source，不自动授予工具调用权限。

签名 envelope v1 严格包含：

| 字段 | 内容 |
| --- | --- |
| envelopeVersion | 十进制整数 `1` |
| algorithm | `SHA256withECDSA-P256-DER` |
| manifest | 现有 manifest 的全部字段，apiVersion 为 `1` |
| publicKeySpkiBase64 | 标准、带 padding 的 Base64；X.509 SubjectPublicKeyInfo，named P-256 |
| signatureBase64 | 标准、带 padding 的 Base64；ECDSA ASN.1 DER 签名 |

算法名称是本项目协议标记，不是 JWS ES256；实现使用 Java `SHA256withECDSA`。公钥要求完整曲线参数等于 secp256r1，并与解析后的 SPKI 重编码一致。DER 只接受两项最小编码、正数且小于曲线阶的整数，拒绝尾随字节；签名长度 8–72 bytes。未知字段、重复字段（包含转义后的同名 key）、宽松 JSON、嵌套超过 32、错误算法/版本及非规范 Base64 都拒绝。API/schema/envelope 版本只接受原始十进制整数 token，不接受指数或小数。

公钥来自 envelope，只能证明此公钥持有人签署了声明。首装或旧声明转签名时，用户须在前台通过可信渠道核对 SHA-256 公钥指纹后确认；publisher 仍是签名绑定的文本，不是已认证的真实身份。确认绑定具体 manifest SHA-256 与公钥 SHA-256；对话框保存冻结 envelope，点击后 Registry 再验签。不确认或取消不写账本。

后续升级绑定原插件 ID、公钥和 publisher 文本，换公钥或发布者不可隐式进行。旧无签名记录转签名须版本不低于原记录，publisher 相同；同版本必须完整 manifest 相同。旧声明不会在恢复时自动成为已确认签名插件。

## 规范签名字节

`AgentPluginSigning.canonicalBytes` 独立于 JSON 字段顺序与排版，不采用 RFC JCS。所有整数使用 4-byte big-endian；字符串为 4-byte UTF-8 byte length 后跟原始 UTF-8，孤立代理项拒绝。依次写入：

1. 字符串域标签 `XIAOLING_AGENT_PLUGIN_MANIFEST_V1`。
2. id、version、apiVersion 整数、name、description、publisher。
3. capabilities 项数和按 UTF-16 排序的字符串。
4. permissions 项数和按 UTF-16 排序的枚举名称。
5. executionMode 名称。
6. source 存在性 1-byte boolean（0/1）；存在时追加 url、commit、contentSha256 三个字符串。

SHA-256 manifest 摘要与签名使用同一字节序列。API 缺省或 null 的旧 JSON 规范化为 1 后验签；所有业务字段的最终值均参与签名。source hash 是发布者签署的声明，本片没有下载或验证对应 artifact。

独立离线签名入口：

```zsh
python3 tools/plugin_manifest_signer.py \
  --manifest /absolute/path/manifest.json \
  --private-key /absolute/path/publisher-p256.pem \
  --output /absolute/path/signed-manifest.json
```

工具仅从指定文件读取已有密钥，调用本机 OpenSSL；输出拒绝覆盖，仅包含 manifest、公钥和签名。manifest 上限 16 KiB，envelope 上限 24 KiB。密钥生成/分发、加密私钥密码输入和公钥轮换不由此工具管理。测试使用临时密钥，私钥不进入项目、APK 或证据。

## 版本与原子账本

`AgentPluginRegistry` 仍是 Application 单例，实例方法同步；不是多进程 CAS 存储。SharedPreferences 同步 commit 整个 schema v2 快照，成功后才更新内存；无存储的 Registry 也执行同样的编码、签名与容量校验。输入与返回的能力/权限/历史集合不可修改，避免绕开验签或写盘修改内存。schema v1 旧 `{manifest, enabled}` 记录兼容，下一次成功写入转为 v2。

每个签名插件保存 current signed manifest、highest signed manifest、最多 3 个历史签名版本、enabled 与 removed。当前 manifest 必须与当前签名内容一致；所有保存版本均重新验签，且 ID、公钥、publisher 相同；版本不得超过最高锚点，最高同版本必须同内容。状态最多 128 条（包括移除身份）、256 KiB UTF-8，满容量拒绝、不淘汰身份；历史按较高版本优先保留，旧版本裁剪后不能通过导入旧 envelope 重新加入。

- 新安装、旧声明转签名、升级、回滚、移除后重装都默认停用。
- 同当前完整 manifest 的重复签署幂等，保留当前启停；ECDSA 随机签名字节不作为内容版本身份。
- 普通导入低于最高版本拒绝；回滚只选择已保存且低于当前版本的历史。回滚重新验签、保留最高锚点，确认绑定打开对话框时的当前 manifest。
- 已签名插件不能由无签名声明替换。移除只隐藏已安装项并停用，保留公钥、最高版本与有界历史；重装仍受约束。同最高版本且内容一致可重装，旧版本不可通过移除重装绕过。
- 无签名声明移除保留旧行为，删除记录；其状态不代表执行信任。
- 损坏/不兼容快照冻结全部变更并保留原文；写入失败不改内存或旧快照，管理页冻结操作。移除确认也绑定当前 manifest，版本已变化则拒绝。

签名完整性不替代 Android 应用私有存储边界；具有修改应用账本权限的主体不在本片防护范围。永久信任撤销/密钥轮换、自动容量回收与审计事件列表另行设计，不通过移除自动遗忘身份。

## 证据与后续

聚焦 JVM、同向量 Redmi 真机、管理页及三轮独立进程证据见[验证报告](verification-report.md)。独立 Python/OpenSSL 签名由生产 Kotlin 解码/验签，并覆盖补充字符的跨语言排序；受控 force-stop 不是自然 LMK/Doze/系统重启。

下一片补签名身份下的 SAFE 前台执行权限交集、冻结目录、即时撤销、超时取消及 Tool Ledger。当前签名插件仍只参与预览，未接入 Run 或真实 ACI 执行，不宣称整个第 4 点收口。

官方依据（已通过 smart-search 抓取正文，访问于 2026-10-18）：[Java 17 Signature](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/Signature.html)、[Android X509EncodedKeySpec](https://developer.android.com/reference/java/security/spec/X509EncodedKeySpec)、[OpenSSL dgst](https://docs.openssl.org/3.5/man1/openssl-dgst/)。算法/编码的实际兼容性以本片 JVM 与指定 Redmi 验签结果为准。
