# Android v0.1.23 发布记录

本版 `versionName=0.1.23`、`versionCode=24`，应用 ID `com.longdev.xiaoling`，最低 API 26、目标 API 36。仅发布 Android；短期暂停 iOS 产品工作。

## 更新内容

- Remote Channel：cursor、批次、ACK、持久重试与发送队列，独立后台收件和通知开关，认证 HTTPS 及处理审计。后台许可不代替本机消息确认。
- MCP：统一 `McpSessionRegistry`，支持 Streamable HTTP 和 legacy SSE；配置变更与取消撤销旧会话，后续显式发现，不重放 `tools/call`。
- Context / Memory：CAS 版本保护、实际模型输入快照、有界提取压缩、恢复核验与显式整理建议；正式记忆仍需用户确认。
- Skill / 插件：签名 manifest、公钥确认、有界回滚与前台 SAFE 宿主只读执行。
- 多 Agent：限定 1–2 个只读 child，持久 checkpoint、父取消、失败聚合及结果单次消费。
- Workspace / Browser / GenUI：有界会话 transcript，服务端静态 HTML 的语义 DOM 与逐次审批导航，版本化卡片和受限显式 action。
- Workflow：编辑器确认限定应用与每步完成标准，输入后的持续隐私保护，各方向滚动与无移动失败边界，恢复时拒绝缺少动作事实的完成结论。
- README 精简为下载、能力、上手、边界、构建与文档入口；阶段历史以专项文档和验证报告为准。

## 数据升级

Room 从上一正式版本 v40 升到 v44：40→41 为记忆补 CAS 基线，41→42 增加 context snapshot，42→43 增加压缩记录，43→44 增加整理账本。迁移不自动调用模型或启动整理。

本次新增直接 40→44 的隔离真机迁移门禁，检查旧记忆全部字段、FTS、旧幂等 operation 和后续 CAS 更新；与各段迁移门禁一起执行，不改动正式数据库。

## 本版发布门禁

本机发布门禁已通过。GitHub 标签、资产上传和远端回验结果在发布后追加。

| 检查 | 实际结果 |
| --- | --- |
| App JVM | 1632/1632，零失败、错误、跳过；本轮执行 |
| 主机工具测试 | 67/67；负向用例的 FAIL 输出不是 unittest 失败 |
| shared allTests | 通过；Android Debug/Release 各 118、iOS Simulator 156，Gradle 判定 UP-TO-DATE；不是 iOS 产品发布验收 |
| 构建 | Debug、AndroidTest、正式签名 Release 均成功 |
| Lint | Debug 与 lintVitalRelease 通过；Debug 0 错误、93 警告 |
| Redmi 迁移 | 5/5：各段及直接 40→44；旧数据、FTS、operation 和 CAS 通过 |
| Redmi 编辑器 / Room | 6/6：显式确认、编辑撤销、旧标准拒绝、冻结历史及活动 Run 边界 |
| 文档语料 | 1/1；隔离知识库检索黄金查询通过 |
| 正式包升级与启动 | v0.1.22→v0.1.23，首次安装时间保持；主页面、进程及该 PID crash buffer 检查通过 |
| 安装产物 | 从设备回读 APK 与正式产物 SHA-256 一致 |
| APK 验证 | 版本/SDK、v2 签名、旧证书连续性、16 KiB zipalign、Debug 入口缺失和 HTTP 日志关闭通过 |

正式 APK：`xiaoling-v0.1.23.apk`，大小 **3,728,850 字节**。

```text
SHA-256: ce85338066e050d896ee0b264c0263041aef31dc367a82c31a109b04fbedb4b9
Certificate SHA-256: 23ad3f3662babb32cc0ed98f8d7a6028254fbb2bb3c7c37ba54730bff8a0906c
```

首次完整 Gradle 门禁在 `packageDebug / IncrementalSplitterRunnable` 失败，单独重试 Debug/AndroidTest 打包成功；原失败未改记通过。随后 Release/Lint 构建成功，期间出现 2 GiB heap 告警。失败、重试和最终成功日志均保留；未修改项目默认内存设置。

本机证据目录：`outputs/verification/release-v0.1.23/`。正式资产目录：`outputs/release/`。

## 已有真机证据与待办

第 300 阶段在指定 Redmi `wsvwypiz7xwslvl7 / begonia` 使用 `gpt-6-luna`，同轮完成编辑器批准、拒绝及两步只读链：批准目标 VERIFIED，拒绝保留 FAILED 且无动作结果；Activity 重建、Room 冻结和独立进程回查通过。详见[编辑器专项](android-workflow-editor-acceptance.md)。其他已验收功能的等级见[真实模型验收](android-live-acceptance.md)和[验证报告](verification-report.md)。

以下仍独立追踪：稳定 Remote Channel 部署与容量回收、真实通知、自然 Doze/LMK/重启恢复、复杂设备任务、Context7 `query-docs`、真实外部 SSE、OAuth/stdio/后台 MCP、精确多 Agent 共享扣账及完整 iOS 产品。

本轮开始时 Redmi 主应用已缺失，仅残留测试 APK，无障碍授权为空；无法将第 300 阶段的安装和数据状态视为本轮基线。本轮以旧正式包 v0.1.22 的全新安装作为签名升级起点，已无损升级到 v0.1.23 并验证启动；迁移细项使用独立 Debug 测试宿主 `com.longdev.xiaoling.releasecheck`。该宿主只用于隔离门禁，最终停用以避免与正式应用入口混淆。不卸载现有主应用、不清数据、不自动增加系统授权；此前 Debug 的真实模型与设备动作证据不等同于正式签名包完整功能验收。
