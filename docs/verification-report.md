# 验证报告

验证日期：2026-07-17（北京时间）

## 环境

- macOS 原生环境 + zsh
- Android 真机：`wsvwypiz7xwslvl7`
- 设备型号：Redmi Note 8 Pro
- Android：14 / API 34
- App 包名：`com.longdev.xiaoling`
- App 展示名：小灵

## 构建验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleRelease --stacktrace
```

结果：

```text
BUILD SUCCESSFUL
```

## 签名验证

执行命令：

```zsh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

结果：

```text
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=XiaoLing, OU=XiaoLing, O=Long, L=Shanghai, ST=Shanghai, C=CN
Signer #1 certificate SHA-256 digest: 5e9ecb9a560858b439392af355ecee3af082dc78d74feb84d9cb236947073fa9
```

说明：

- 本次更换了 `applicationId`，Android 会把它视为新应用。
- 本机 release keystore 已重新生成为小灵专用证书。
- 旧证书文件已移到 `/tmp` 做临时备份，没有继续作为当前项目签名输入。

## APK 元数据

执行命令：

```zsh
aapt dump badging app/build/outputs/apk/release/app-release.apk
```

关键结果：

```text
package: name='com.longdev.xiaoling' versionCode='9' versionName='0.1.8'
application-label:'小灵'
```

## 真机安装与启动

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r outputs/release/xiaoling-v0.1.8.apk
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

结果：

```text
Performing Streamed Install
adb: failed to install outputs/release/xiaoling-v0.1.8.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.longdev.xiaoling signatures do not match newer version; ignoring!]
Starting: Intent { cmp=com.longdev.xiaoling/.MainActivity }
```

前台 Activity：

```text
com.longdev.xiaoling/com.longdev.xiaoling.MainActivity
```

已确认：

- 设备上已有同包名但不同签名的安装包，系统拒绝覆盖安装 release APK；未执行卸载或清数据。
- 已有安装包的对话页可启动。
- 本次未在该真机上覆盖安装 release 包；如需从 debug 签名切换到 release 签名，需要用户确认后卸载旧包或换干净设备验证。

## 日志检查

启动期间 logcat 未命中应用崩溃、`FATAL EXCEPTION`、`AndroidRuntime` 或 ANR。

## 产物

| 文件 | 说明 |
|---|---|
| `../app/build/outputs/apk/release/app-release.apk` | release 包，已通过正式签名验证。 |
| `../outputs/release/xiaoling-v0.1.8.apk` | GitHub Release 上传用 APK。 |
| `../outputs/release/xiaoling-v0.1.8.apk.sha256` | APK SHA-256 校验文件。 |

## 清理状态

- 真机上保留原有同包名安装包，未卸载、未清数据。
- `outputs/` 目录不纳入版本控制。
