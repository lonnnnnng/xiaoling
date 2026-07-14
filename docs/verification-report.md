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
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew testDebugUnitTest assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
```

## 安装验证

执行命令：

```zsh
adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk
```

结果：

```text
Performing Streamed Install
Success
```

设备上已安装包：

```text
com.longdev.endpointtester
```

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
| `../app/build/outputs/apk/debug/app-debug.apk` | 已验证 debug APK，Release 资产来源 | `547405328f665a122dad1b1bc0e1b22d4ec878059a8ddc11f282308849ed8645` |

## 清理状态

- App 保留在真机上。
- `outputs/` 目录不再纳入版本控制。
