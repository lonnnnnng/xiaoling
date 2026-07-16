# 验证报告

验证日期：2026-07-16（北京时间）

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
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug --stacktrace
```

结果：

```text
BUILD SUCCESSFUL
```

Release 构建：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleRelease --stacktrace
```

结果：

```text
BUILD SUCCESSFUL
```

## 签名验证

执行命令：

```zsh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

结果：

```text
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
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

关键结果：

```text
package: name='com.longdev.xiaoling' versionCode='7' versionName='0.1.6'
application-label:'小灵'
```

## 真机安装与启动

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.xiaoling/.MainActivity
```

结果：

```text
Performing Streamed Install
Success
Starting: Intent { cmp=com.longdev.xiaoling/.MainActivity }
```

前台 Activity：

```text
com.longdev.xiaoling/com.longdev.xiaoling.MainActivity
```

已确认：

- 对话页可启动。
- 顶部标题为「对话」。
- 底部入口为「对话 / 设置」。
- 首次安装时展示默认配置，并提示到设置页获取上游模型并勾选可对话模型。
- 输入区、Resp、流式和模型选择控件可见。

## 日志检查

启动期间 logcat 未命中应用崩溃、`FATAL EXCEPTION`、`AndroidRuntime` 或 ANR。

## 产物

| 文件 | 说明 |
|---|---|
| `../app/build/outputs/apk/debug/app-debug.apk` | debug 包，已安装到真机验证启动。 |
| `../app/build/outputs/apk/release/app-release.apk` | release 包，已通过正式签名验证。 |

## 清理状态

- 新包名应用保留在真机上。
- `outputs/` 目录不纳入版本控制。
