# Android v0.1.24 发布记录

本版 `versionName=0.1.24`、`versionCode=25`，应用 ID `com.longdev.xiaoling`，最低 API 26、目标 API 36。仅发布 Android，Room 保持 v44。

## 更新内容

- 修复 Remote Channel 地址策略拒绝可能引发异步线程崩溃的问题，保留公网地址校验。
- 备份恢复增加完整性、外键和真实 Room 结构校验；下次启动原子激活，失败回滚。备份快照支持旧 SQLite、大文本、FTS 和 rowid 保真，恢复后重新核对实际 WorkManager 绑定。
- 切换会话前保存部分流式内容并拒绝迟到事件；模型 failed/incomplete 不再当作成功或进入记忆候选。拆分聊天展示与 Skill 管理职责。
- 设备输入按原节点精确回读空格与换行，观察无事件时优先验证结果；增加常用任务前置诊断、任务中心阶段反馈和按 Run 隔离的停止收敛。
- 启用设备 Agent 后，限定应用内动作默认免逐次确认；暂停再启用保留用户选择，手动关闭仍持久化。动作审计、窗口守护和执行后验证继续保留。
- 修复 DeepSeek 规划输出额度与步骤格式提示，以及系统设置恢复到搜索伴随窗口时的打开结果判定。伴随包不能作为单独启动目标，也不扩展到其他应用。

## 本版发布门禁

| 检查 | 实际结果 |
| --- | --- |
| App JVM | 本轮执行 1671/1671，零失败、错误、跳过 |
| 主机工具测试 | 本轮执行 67/67，通过 |
| shared allTests | 通过，Gradle 判定 UP-TO-DATE；Android Debug/Release 各 118、iOS Simulator 156，零失败/错误/跳过；不计为本轮重跑或 iOS 产品验收 |
| 构建 | Debug、AndroidTest、正式签名 Release 成功 |
| Lint | Debug 与 lintVitalRelease 通过；Debug 0 错误、100 警告、3 提示 |
| POCO 覆盖安装 | 唯一目标 `31936bb6 / lmi`，安装 code25 成功；首次安装时间保持 `2026-11-11 13:26:17` |
| 安装文件回验 | 从设备读取 APK，与待发布产物 SHA-256 一致 |
| 启动与旧结果回读 | 主页面、PID 及当前进程 crash buffer 检查通过；仍显示原设置搜索 4/4 VERIFIED 结果与 DeepSeek 模型选择 |
| APK 校验 | 版本/SDK、v2 签名、固定正式证书连续性与 16 KiB zipalign 通过 |

完整 Gradle 门禁使用 JDK21，单次进程配置 `-Xmx4096m`，8m32s 成功；提交前清理两个新文件末尾空行后，增量 Release 构建 1m03s 成功，以最终构建重新安装并回读 APK。未修改项目或全局默认堆大小。本机证据位于 `outputs/verification/release-v0.1.24/`，正式资产位于 `outputs/release/`，均不进入 Git。

正式 APK：`xiaoling-v0.1.24.apk`，大小 **3,761,614 字节**。

```text
SHA-256: 3ae87d61693952ca85cacc80d02f55b641c7b38c8408edec2ca573fb7be0730d
Certificate SHA-256: 23ad3f3662babb32cc0ed98f8d7a6028254fbb2bb3c7c37ba54730bff8a0906c
```

## 已有真实模型证据与边界

本轮发布前的同代码正式候选（版本仍为 code24）在指定 POCO、手机 FlClash 和 `deepseek-v4.1-flash` 上取得普通配置/对话、SAFE、计算器、主动拒绝、待审批停止与设置搜索证据。设置搜索 Workflow `workflow-run-63fd377c-4017-45c7-a658-acab4fa57479` 为 COMPLETED / VERIFIED，4/4 步，匹配打开、观察、点击、输入和最终观察链，最终窗口为登记的 `com.android.settings.intelligence`。拒绝与停止 Run 无打开应用动作结果，失败审计保留。

这些是此前实际执行证据，本次 code25 升级检查没有重新运行所有真实任务或 instrumentation。隔离 POCO 取消/备份、空白换行、断连诊断、Skill 启停等历史专项结果详见[可靠性与 M1 记录](android-reliability-m1-2026-11-11.md)，不冒充本轮全量重跑。

M1 八类联合清单未全部在 code25 独立重跑；不宣称跨 Run 输入隐私、自然 LMK/Doze/重启、长期 Remote、真实通知或复杂设备任务已验收。M2–M4 仍为路线图计划，本次仅按用户指令发布已完成增量，不自动开展后续阶段。旧模型 `gpt-6-luna` 只保留在历史证据中；当前验收配置不再使用它，不使用 USB 临时代理。

## GitHub 发布

发布入口：[v0.1.24](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.24)。提交、标签及附件重新下载结果在发布完成后追加；发布标签不随文档追加移动。
