# Redmi 真机基线（2026-09-30）

## 设备与产物

- 设备：Redmi Note 8 Pro，`ro.product.device=begonia`
- serial：`wsvwypiz7xwslvl7`
- Android SDK：36
- 仅对上述 serial 执行安装和 instrumentation；没有使用 Pixel_9 或其他模拟器。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
  - SHA-256：`12a0c7eea9925eaf34a681951c8c464f1f296b2f969085d01c04f83b2ace467d`
- AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA-256：`5a67b56220703b61e032e83ff0c1507c2f32eadad72bc586a263128e78c853dd`

## 本地门禁

以下命令在工作树 `/Users/long/Documents/CodexProjects/xiaoling` 执行并成功：

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

## 固定真机门禁

Debug 与 AndroidTest APK 通过 `adb -s wsvwypiz7xwslvl7 install -r` 覆盖安装后，执行：

```text
adb -s wsvwypiz7xwslvl7 shell am instrument -w -r \
  -e class 'com.longdev.xiaoling.assistant.XiaoLingAssistantEntryInstrumentedTest,com.longdev.xiaoling.automation.ScheduledTaskSchedulerInstrumentedTest,com.longdev.xiaoling.storage.RoomWorkflowRepositoryInstrumentedTest#startupRecoveryFindsOnlyUnboundScheduledTasks,com.longdev.xiaoling.ui.conversation.ConversationPageInstrumentedTest#exposesSpeakingActionOnlyForCompletedAssistantMessage,com.longdev.xiaoling.ui.settingsroot.SettingsRootPageInstrumentedTest,com.longdev.xiaoling.agent.ExtendedAgentCapabilitiesInstrumentedTest' \
  com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`OK (14 tests)`，0 failures，耗时约 19.5 秒。

覆盖范围：

- 系统 `ASSIST` Activity 与 Voice Interaction Service 可解析；
- WorkManager `KEEP` 复用真实 Work ID；
- 启动恢复只补队没有 `workRequestId` 的 scheduled task；
- 前台已完成 assistant 消息显示朗读入口；
- 设置根页动作和摘要保持可用；
- 浏览器公开页面/私网拒绝、工作区/终端、GitHub Skill commit 固定与哈希、MCP Keystore token 往返。

## 额外回归边界

完整 `AndroidJUnitRunner` 本轮共 476 条，其中 472 条通过、4 条失败。失败项不纳入本固定门禁：一个历史真实 Provider 探针缺少 Provider 配置，一个 WorkManager 停止原因时序探针超时，一个 Redmi 没有 Google Weather launcher，另一个旧 Compose 用例按可见文本点击只设置了 content description。第一组固定门禁本身为 14/14 通过；TTS 系统引擎 smoke 仍因设备 `tts_default_synth=null` 按设计跳过，不代表中文发音质量已验证。

## 第二组第一切片回归（2026-09-30）

- 本轮工作树在新增远程 Channel、ACI 只读桥和插件声明层后，重新执行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest`，均成功；Lint 报告 `0` 个 Error/Fatal。
- 本轮 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `74a809973c41053f889abfbbba45b60e55541fe137223f4f93648b47c65fba72`。
- 本轮 AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `56010011a67576501d6c93d7a205b383aad21c6db8a1db939d07de49f6686e22`。
- 仅向 Redmi `wsvwypiz7xwslvl7` 通过 `adb -s ... install -r` 覆盖安装两个 APK，未清理应用数据；固定 instrumentation 门禁结果为 `OK (14 tests)`、`0 failures`、JUnit `20.76s`。
- 这次真机回归证明已有第一组入口、工作区/终端、MCP/GitHub Skill 和设置链路没有被第二组纯 Kotlin 声明层破坏；远程 Channel 当前仍是进程内 envelope 接收，ACI 当前只读前台直连，插件当前只管理 manifest，不把三者写成网络、远程执行或插件代码已完成。

## ACI 只读设置入口回归（2026-09-30）

- 修复并接通设置根页到 `ACI_READ_ONLY_CAPABILITIES` 子页的宿主回调；页面仅展示当前 Profile 与 SAFE 能力交集，不执行工具。
- 最新 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `0bd794bcbeb33482e580aa18477936e418cee1558a5c75ebe0da6ff6590f6048`。
- 最新 AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `6ca36803b4a8e9a959510b694af4ffbeb115426407c2d0a7ae95402be7c7fe09`。
- 仅向 Redmi `wsvwypiz7xwslvl7` 执行 `adb -s ... install -r` 覆盖安装；`AciReadOnlyCapabilitiesPageInstrumentedTest` 为 `2/2`，`SettingsRootPageInstrumentedTest` 为 `5/5`，均 `0 failed`。
- 全量本地门禁 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均成功；Lint 报告 `0 errors`、`80 warnings`。

## Remote Channel 去重持久化回归（2026-09-30）

- 新增 SharedPreferences 去重键存储与 Shared Draft 纯投影入口；没有接入网络轮询、Webhook、自动发送或后台 Agent。
- 最新 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `4e431c0d39d210808f86f753244e49aa0cfed4a5003c681d704adf241f913d9d`。
- 最新 AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `b217083f893c4073dad4d87eaefd6778a49f51b534bde39593bb3a2a25f9bf88`。
- 仅向 Redmi `wsvwypiz7xwslvl7` 覆盖安装；`RemoteChannelDedupeStoreInstrumentedTest` 为 `1/1`，ACI 页面与设置根页合并为 `7/7`，均 `0 failed`。
- 本轮全量本地门禁 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功；Lint `0 errors`、`80 warnings`。

## 插件来源指纹回归（2026-09-30）

- `AgentPluginManifest` 增加 HTTPS 来源、固定 commit 与内容 SHA-256 校验；未接入签名验证或外部代码加载。
- 最新 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `5ab688940dafca2d68a50e1a9c7ae3acaa9878a9b56c527ca479454532fb6a6c`。
- 最新 AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，SHA-256 `30d785edd3746b615801622f2a9cd3d8162b08a019f7b0220c6376d14e7d7914`。
- 仅向 Redmi `wsvwypiz7xwslvl7` 覆盖安装后，Remote Channel 存储、ACI 页面与设置根页合并定向回归为 `8/8`，无失败；JVM、Lint、Debug/AndroidTest 构建均成功，Lint `0 errors`、`81 warnings`。

## 最终固定门禁回归（2026-09-30）

- 在插件来源指纹和去重账本同步提交后的最新 APK 上，固定 Redmi 门禁重新执行为 `OK (14 tests)`，`0 failures`、`0 skipped`；设备仍为 `wsvwypiz7xwslvl7`，没有向模拟器发送命令。
