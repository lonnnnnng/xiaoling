# 验证报告

验证日期：2026-07-26（北京时间）

## 当前验证基线

- 当前发布版本：小灵 `v0.1.12`，`versionCode=13`、`minSdk=26`、`targetSdk=36`。
- 发布提交：`0468c8511c31953c0ce44880b5272b7b315e9654`（`发布小灵 0.1.12`）；GitHub Release 为 [小灵 v0.1.12](https://github.com/lonnnnnng/xiaoling/releases/tag/v0.1.12)。
- 正式产物：`outputs/release/xiaoling-v0.1.12.apk`，大小 `15,950,806` 字节，SHA-256 `0076dbc952fbc5a9db03ce3ebce89261315db43290fe3e407a70707c4939ab66`；v2 签名、zipalign 和单一签名者校验通过。
- 本地完整门禁：JVM `656/656`，0 失败、0 错误、0 跳过；Lint `0 error / 50 warnings / 1 hint`；Debug、AndroidTest、Release APK 和 Release lintVital 均成功。
- Redmi 完整门禁：只使用真机 `wsvwypiz7xwslvl7`，默认 `AndroidJUnitRunner` 为 `OK (196 tests)`，其中 `184 passed / 12 skipped / 0 failed`；没有向 Pixel_9 或其他模拟器发送 ADB 命令。
- 文档语料门禁：最终 README 与长期 `docs/` 打包进 AndroidTest assets 后，`projectDocumentationCorpusMeetsGoldenQueryRecallGate` 为 `OK (1 test)`。
- 设备收尾：测试包已卸载，Debug APK 无损覆盖并冷启动；`MainActivity` 为前台 resumed Activity，主进程存活，近期 AndroidRuntime crash 检查为空。

## 2026-07-26 验证报告历史归档

- 归档边界：原 2,391 行验证报告冻结为 [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)，当前卷收敛为发布基线、当前工程边界、历史索引和归档点之后的新验证。历史卷的 Skill 示例相对链接已按新目录修正。
- 语料契约：AndroidTest 继续显式导入根级当前卷，并新增历史卷 asset；黄金查询分别覆盖当前 v0.1.12 门禁和历史引用清理证据。正例计数改为跟随黄金查询集合，新增历史卷不再要求同步修改固定常量。
- 本地聚焦门禁：`:app:testDebugUnitTest`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest` 成功，`git diff --check` 通过。
- Redmi 单项：只使用 `wsvwypiz7xwslvl7`。首次运行准确暴露旧固定断言 `expected:<5> but was:<6>`；修正后重新构建、安装并运行 `projectDocumentationCorpusMeetsGoldenQueryRecallGate`，结果 `OK (1 test)`。没有连接或向 Pixel_9/其他模拟器发送 ADB 命令。
- 行为边界：本阶段只调整长期文档、AndroidTest 语料清单和质量门禁计数，不修改应用运行时、Room Schema、Provider、Agent、Workflow 或设备工具行为。

## 当前工程边界

- Room 当前为 v32；Agent Runtime、Workflow Ledger、设备 Agent 有限动作、长期记忆、声明式 Skill、RAG/Embedding 与 answerability shadow 既有边界不因文档归档而改变。
- answerability shadow 默认关闭，继续固定 `store=null / persistenceMode=NONE`、`enforcementApplied=false` 和 `productionEnforcementEnabled=false`；第 101 项保持低频观察，第 102 项尚未进入。
- 设备工具仍不进入 Workflow 或后台自动化；精确定时和 Foreground Service 继续依据真实耗时与系统回收证据决定。
- 知识引用生命周期继续按当前文档状态复核；验收产生的临时知识数据必须确认文档、chunks 和检索索引均已清理。

## 历史证据

- [基线至第 101 阶段](verification-history/verification-baseline-through-stage-101.md)：包含 v0.1.0 至 v0.1.12、阶段性 JVM/Lint/APK、Redmi 真机、恢复可靠性、设备 Agent、Workflow、RAG/Embedding 与 answerability shadow 的完整历史记录。

## 维护方式

- 本文件只维护当前发布基线和归档点之后的新增验证，最新记录置于当前基线之后。
- 历史事实冻结在历史卷中；除修复失效链接或明确事实错误外，不在后续任务中反复改写。
- 当前卷再次显著增长时，以明确版本、日期和阶段截止点生成下一份历史卷，并同步更新文档索引与语料门禁。
