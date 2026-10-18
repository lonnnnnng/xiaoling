# Android Workflow 分步完成与输入隐私专项验收

第 296 阶段，2026-10-18。仅 Redmi `wsvwypiz7xwslvl7 / begonia / SDK 36`、授权真实 `gpt-6-luna`、同签名 Debug `0.1.22 (23)`；iOS 产品暂停，发布版本和 Room schema 未变。

## 当前结果：设置搜索同轮通过

`live-navigation` 为完整通过轮次，主机耗时 124.24s、USB ACK 已记录、JUnit `OK (1 test)`。Workflow Run 为 `workflow-run-f4f1575d-b067-49b1-bf3b-ee909a433862`。正式 MainActivity 的可见“手动运行”创建实际 Run，经 ViewModel、生产 UseCase、Runtime、审批浮层、Accessibility 和正式 Room，三个 Agent 均 COMPLETED：

| 步骤 | Agent Run | 本轮验证工具顺序 |
| --- | --- | --- |
| 打开设置 | `run-704fbb3c-5283-4f5b-8019-51009c82b563` | snapshot → open_app → snapshot |
| 打开搜索 | `run-945f14b2-0f30-4150-b792-613e8a118f89` | snapshot → tap_ref → snapshot |
| 输入公开随机文本 | `run-4bb09046-47c8-4679-ac90-a331f28f354f` | snapshot → type_text → snapshot |

九条结果全部成功/PASSED，三项生产审批 APPROVED，动作 Executor 验证成功。正式 Room 目标为 `VERIFIED / ALL_CRITERIA_VERIFIED`；独立读取真实搜索框精确匹配本轮八字母文本，工具事件隐私检查通过。回小灵查看三步动作，Activity 重建后结果、输入动作和 Room 目标判定仍相同。本轮三个 Agent 均未使用完成纠错。

独立进程 `inspection-navigation.log` 为 `OK (1 test)`，回读同一 Run、三条严格工具序列和审批结果；从 seed 冻结 JSON 参数识别公开输入，仅输出事件类别与命中布尔值，27 条工具事件均未命中输入原文。临时定义已移除，原 Provider/Profile 各一条，正式执行审计保留。定义和限定目标应用由夹具预置，普通编辑器的限定应用确认仍未验收。

## 生产修复

`WorkflowGoalVerificationSpec.requiredToolNamesByStep` 只允许把原总契约按顺序拆成非空组。无分组的老契约继续 v1 原样编码；显式分组使用 v2，解码拒绝未知字段/版本和不匹配分组。每步从冻结输入读取对应组，步骤数量或序号不匹配即停止；不从目标文本猜动作，不新增 Room 迁移。

新鲜前台 Workflow Run 将明确完成要求复制到 Runtime，要求数量不能突破原四调用预算。完成判定只消费当前 Run 验证通过的工具事实，保留重复次数；缺动作或后置观察时，显式 complete 与“重复已验证只读工具”的收尾路径共用一次纠错。重复只读不再越过 Executor；再次缺项即失败，最终 completeRun 再检查。模型仍自行选工具，逐次审批、窗口/ref 安全、Executor 验证和总预算保持。未独立验收这些新要求的自然进程恢复入口。

真实隐私失败定位为第三步后置 `device.snapshot` 的 `tool.result.content` 含输入，readableEvidence 未命中；旧投影仅适用于 DIRECT。现在前台 Workflow 也登记只含输入哈希/长度的下一次 snapshot 投影，完全匹配的 windowTitle/text/description/hint 从工具输出省略。Controller 原始快照和精确回读仍留在当前执行链内存；Workflow candidate 另冻结实际输出 JSON，验证比较该投影而非原始 JSON，篡改输出仍拒绝。Run 切换/结束清空投影。后续模型规划的 device/browser 输入 arguments 均改用目标、指纹和长度。

隐私范围仍是**输入后下一次成功 capture、完全匹配显示字段**，保持旧 DIRECT 的一次性行为；不声称覆盖所有后续观察、包含输入的拼接文本或所有业务正文。公开输入仍存在冻结步骤目标中，网络目标正文未改成全局省略。该限制与本轮严格第三步三调用验收分开记录。

## 保留的失败轮次

不合并不同失败轮次的局部动作，不修改原 report.json 的 false：

| 目录 | 主机耗时 | 失败事实 |
| --- | --- | --- |
| live-contract | 34.45s | 重复 snapshot，无审批；完成门禁阻止误收尾，Agent BUDGET_EXHAUSTED、Workflow FAILED |
| live-corrected | 119.05s | 九调用/三审批及 Room VERIFIED，但夹具预期 tap 与实际 tap_ref 不一致；隐私、独立回读、重建未验 |
| live-action-name | 116.56s | 两项动作已审批，第三步文本哈希不符合本轮随机值，拒绝输入 |
| live-input-data | 121.74s | JSON 参数版完成动作链，工具事件隐私断言失败；独立检查确认后置 snapshot 泄漏 |
| live-privacy | 11.63s | 进入正式 Workflow 页前找不到“工作流”节点，无实际 Run/模型动作；恢复通过 |

夹具修正包括：终态判断复用生产 isTerminal，保留预算耗尽/阻断历史并继续禁止 PENDING；动作 codec 的正确名称是 tap_ref；随机输入保持八字母并作为唯一 JSON 参数块逐字复制；底栏点击后增加十秒有界正式入口等待，仍经可见按钮进入。未确定导航失败具体瞬态原因，未放宽动作/隐私断言。

完整 JVM 初次还遇到取消夹具 `SocketException: Connection reset`：仅取消后的对端 read 接受 EOF 或精确 Connection reset，其他 SocketException 继续失败，并仍检查 CancellationException 和 job.isCancelled。原失败 XML/构建日志、缺 JSONObject import 的编译失败和错误 Python 启动日志全部保留。

## 验证与保护

- App JVM `1615/1615`、零失败/错误/跳过；Debug、AndroidTest 和 Debug Lint 通过。回归覆盖单次完成纠错、缺后置观察、重复只读旁路、共享纠错额度、老契约与预算，以及 DIRECT/Workflow 的事件/可信上下文隐私、显示字段投影、原 Controller 回读和投影篡改拒绝。
- 主机门禁 `9/9`。真实入口继续复用 `tools/verify_android_live_acceptance.py --phase workflow-settings-search` 与第 295 阶段 instrumentation namespace；本阶段证据独立保存，不覆盖第 295 阶段六份失败。
- 成功轮保护 1,928 条旧审计行；原 Provider/Profile、偏好稳定值、Memory undo absent、原授权选择全部恢复。临时正文/定义/配置清理，实际 Run/审批/工具账本保留。
- 当前偏好基线来自第 295 阶段显式崩溃夹具恢复之后。既有空 Keystore alias 保留，**未恢复最初新安装 absent 状态，也未恢复崩溃前丢失的全部偏好值**。正常本轮恢复不等于自然进程死亡恢复。

首次最终语料/保护测试为 `OK (2 tests)`，但 Room 偏好摘要另行复判不符合基线：只新增 selected_agent_profile_id，删除该键即可精确匹配原摘要，其值不是临时 Profile。只读诊断和源码确认默认 Profile 初始化会写选择键，具体触发时序未独立捕获。错误 seed 的只读诊断被归属检查拒绝，原日志保留；正确 seed 诊断不修改数据。定向恢复在隔离测试进程核对同一成功 Run/seed、三项已完成审批、空业务/PENDING、唯一原默认实体和完整基线摘要后，仅移除新增选择键；不重放模型/工具，不改生产默认选择逻辑。终检再从新进程核对偏好，不把首次测试的 JUnit 成功当作恢复成功。

证据根为 `outputs/verification/stage296-workflow-completion/`。原始报告、独立 Room 读取、`business-evidence-revalidation.json` 和 `verification-summary.json` 区分功能与收尾。首次保护回读保留于 `final-corpus-protected.log`；最终文档语料/偏好复判、APK 文档字节/实际凭据/本地链接检查与原已授权服务受控重绑分别记录于 `final-corpus-protected-rechecked.log`、`artifact-audit-closeout.json`、`accessibility-closeout.json`；受控重绑不作为自然系统恢复证据。USB 配置仅通过既有长度前缀/ACK 入口，未独立抓取提供方完整请求或单独捕获 ACK。

独立待办：普通编辑器限定应用/目标契约；正式 Workflow 滚动和复杂任务；持续或拼接文本隐私；新完成约束的自然进程恢复；新安装临时 alias 正常撤销；Remote 稳定部署/真实通知、Doze/LMK/重启和签名 Release。仅中文本地提交，不 push/tag/Release，保留 refs。

相关：[第 295 阶段失败历史](android-workflow-search-acceptance.md)、[正式 Workflow 入口](android-workflow-acceptance.md)、[路线图](personal-agent-roadmap.md)、[验证报告](verification-report.md)。
