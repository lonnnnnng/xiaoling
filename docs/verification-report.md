# 验证报告

验证日期：2026-07-15（北京时间）

## 环境

- macOS 原生环境 + zsh
- Android 真机：`wsvwypiz7xwslvl7`
- 设备型号：Redmi Note 8 Pro
- Android：14 / API 34
- App 包名：`com.longdev.endpointtester`

## 构建验证

执行命令：

```zsh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleRelease
```

结果：

```text
BUILD SUCCESSFUL
```

## 安装验证

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/release/app-release.apk
```

结果：

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.longdev.endpointtester signatures do not match newer version
```

原因：

- 设备上已有 debug 签名的同包名应用。
- Release APK 使用新生成的正式证书签名，Android 不允许不同签名覆盖安装。
- 为避免清除本机配置，本次没有自动卸载旧 debug 版。
- 干净安装或手动卸载旧 debug 版后可安装正式包。

## UI 与启动验证

启动 App：

```zsh
adb -s wsvwypiz7xwslvl7 shell am start -n com.longdev.endpointtester/.MainActivity
```

已确认：

- 测试页展示固定标题“测试”。
- 测试页展示 Provider / 已勾选模型选择区域。
- 测试页展示 Chat / Responses 接口类型选择和流式开关。
- 测试页底部输入区固定，发送按钮在输入区右下角。
- 管理页展示固定标题“管理”。
- 管理页 Provider 列表显示已勾选模型数量。
- 底部 TabBar 位于页面底部并保持紧凑高度。

## 日志检查

当前进程 logcat 中未命中：

- `FATAL EXCEPTION`
- `AndroidRuntime`
- `ANR`
- `crash`
- `ApiFailure`
- `StrictMode`
- `Exception`

error 级日志里只看到系统侧噪声：

- `ion ioctl ... Invalid argument`
- `ImeBackDispatcher: Ime callback not found`

没有看到 App 崩溃。

## 产物

| 文件 | 说明 | SHA-256 |
|---|---|---|
| `../app/build/outputs/apk/release/app-release.apk` | 正式证书签名 Release APK，GitHub Release 资产来源 | 以 GitHub Release 资产和 release notes 为准 |

签名证书：

- DN：`CN=Endpoint Model Tester, OU=Endpoint Tester, O=Long, L=Shanghai, ST=Shanghai, C=CN`
  - 当前 Android 应用展示名已改为「灵测」；证书 DN 保持不变，保证后续版本继续使用同一正式签名覆盖安装。
- SHA-256：`1b9a68dfc2c0a6d0d54cace169ec8ca1378f665ec83c77b0d9d36915a331e7c2`

## 清理状态

- App 保留在真机上。
- `outputs/` 目录不再纳入版本控制。
